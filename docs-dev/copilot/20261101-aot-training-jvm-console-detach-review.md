# AOT 训练 JVM 附着控制台：异常行为分析与修复方案

- 日期：2026-11-01
- 范围：`cli/browser4-cli/src/daemon.rs`（AOT 缓存训练 JVM 的进程创建与完成判定）
- 触发场景：Windows；CLI 由受限会话、一次性终端、IDE 集成终端或 CI runner 拉起，命令结束后控制台被销毁
- 状态：问题已确认（静态取证 + 本机现象一致）；2026-11-01 第二轮复审已修正初稿的触发面夸大与方案错误，4.2 定案为"包装进程按退出码提交"；批次 1–4 已全部实施，并在真机实测后为包装脚本追加尺寸下限（见 §6 / §8 / §8.9）
- 关联：后端启动的 `DETACHED_PROCESS` 修复（[daemon.rs L56-L62](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L56-L62)、[L8642](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L8642)）、`aot-training-during-install-design.md`

---

## 0. 摘要

后端的"随控制台早退"问题已经修复（`DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP`），但同一文件里 **AOT 训练 JVM 只设置了优先级标志，没有真正脱管**。它仍然附着调用方控制台，因此控制台关闭时被系统强杀。而 AOT 缓存是在 JVM 退出阶段才落盘的，强杀会同时造成两类后果：**留下半成品缓存**，以及**训练永远无法完成**。

| 编号 | 严重度 | 一句话 |
| --- | --- | --- |
| A1 | P0 | 半写 `app.aot` 会被判定为"训练完成"，此后永不重训；后端加载只看文件是否存在，坏文件必然被附加 |
| A2 | P0 | 后端需要重启时形成"训练 → 被杀 → 下次启动再训练"的空转循环 |
| A3 | P1 | 已常驻的后端不会因训练完成而受益，必须重启；期间无任何可见提示 |
| A4 | P1 | PID 判定假存活，造成最长 5 分钟的静默阻塞与误导提示 |
| A5 | P1 | `training.log` 被截断为 0 字节，失败原因不可见 |
| A6 | P2 | `training.pid` / `training-args.txt` 残留到下一次启动，`training.lock` 至多 5 分钟被回收 |
| A7 | P2 | Ctrl+C / 控制台关闭事件直接打到训练 JVM，放大半写概率 |
| A8 | P2 | "并发重训无害（last writer wins）"的假设不成立 |
| A9 | P2 | 注释与设计文档均声称"detached"，掩盖了实现缺陷 |
| A10 | P1 | 用户显式 `-XX:AOTCache` 与 state 缓存被重复注入，用户配置被覆盖（复审新增） |
| A11 | P1 | 多版本 runtime 共用同一全局 `aot-cache`，互相删缓存、抢 marker（复审新增） |

> 复审说明：初稿把 A2 写成"每条命令都拉起训练 JVM"、把 A3 写成"下一条 CLI 命令会补训"，均不准确——`ensure_aot_cache_trained` 只在**拉起后端**（[resolve_server_launch_spec L6278-L6282](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6278-L6282)）与 **install/upgrade**（`main.rs` L24192 / L27805 / L28066）被调用；后端存活期间的普通命令（`tabs` / `eval` / `snapshot` …）不会进入该路径。
>
> 第 1、2 节记录的是**修复前**的行为与代码位置（扁平缓存目录 + `app.aot.key` 侧车）。批次 3 已把缓存布局改为 `aot-cache/<key>/` 并删除侧车，完成判定改为"key 目录内存在 `app.aot`"（见 §8.6）。

---

## 1. 已确认事实

### 1.1 后端已脱管，训练 JVM 没有

后端的进程创建标志（[daemon.rs L56-L62](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L56-L62)）：

```rust
#[cfg(windows)]
const BACKEND_CREATION_FLAGS: u32 = {
    const DETACHED_PROCESS: u32 = 0x0000_0008;
    const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
    DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
};
```

训练 JVM 的进程创建标志（[daemon.rs L6862-L6870](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6862-L6870)）：

```rust
#[cfg(windows)]
{
    use std::os::windows::process::CommandExt;
    const CREATE_BELOW_NORMAL_PRIORITY_CLASS: u32 = 0x0000_4000;
    cmd.creation_flags(CREATE_BELOW_NORMAL_PRIORITY_CLASS);
}
```

`CREATE_BELOW_NORMAL_PRIORITY_CLASS` 只影响调度优先级，**不影响控制台归属**。未设置 `DETACHED_PROCESS` 时，`CreateProcess` 默认让子进程继承父进程的控制台，训练 JVM 因此成为终端会话的一员。

### 1.2 注释与设计文档都与实现不符

[daemon.rs L6782-L6785](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6782-L6785) 的文档注释：

> The child is deliberately detached: it outlives this CLI invocation ...

[daemon.rs L6881-L6883](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6881-L6883) 的落地注释：

> Deliberately detached: the training run outlives this CLI invocation. Dropping the handle does not kill the process.

两处都把"丢弃 `Child` 句柄"等同于"进程脱管"。二者不是一回事：丢句柄只放弃轮询/等待能力，进程的控制台与作业归属完全由 `creation_flags` 决定。

`aot-training-during-install-design.md` 2.2 节同样写着"`spawn_aot_training`：detached JVM"，这个错误前提已经进入设计口径，后续复用该文档的人会继续假设它不会死。

### 1.3 Windows 控制台生命周期语义

1. 控制台（conhost）销毁前，系统向所有附着进程发送 `CTRL_CLOSE_EVENT`；
2. 处理函数默认只有约 5 秒宽限，随后 `TerminateProcess`；
3. JVM 在宽限期内可能启动关闭钩子（Spring context close、`-XX:AOTCacheOutput` 的写出都在退出路径上），时间不够就被强杀；
4. 被强杀的进程退出码呈 `0xffffffff` / `0xC000013A` 一类，**不会产生 `hs_err_pid*.log`**，日志里通常也没有 `Shutting down` 或 `Application run failed`。

### 1.4 现场症状与本机观察一致

- 首条命令首次触发训练，打印 `Training JVM AOT cache in the background (one-time, only on first launch) ...`；
- 命令结束后一段时间，训练 JVM 消失，`aot-cache/training.log` 为 **0 字节**；
- 下一条命令再次触发训练，提示语重复出现；后端本身（已修复）继续存活但始终没有缓存可用。

---

## 2. 异常行为清单

### A1（P0）半写缓存被"晋升"为有效缓存，从此永不重训

**链路**

1. 训练 JVM 开始写 `app.aot`（JVM 退出阶段落盘）；
2. 控制台销毁，宽限期不足，进程被强杀，`app.aot` 只写了一部分但**文件已经存在**；
3. 下一条命令进入 `ensure_aot_cache_trained`：

[daemon.rs L6727-L6734](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6727-L6734)：

```rust
let marker_key = read_aot_training_marker_key();
if cache_file.is_file() && marker_key == key {
    let _ = fs::write(&key_file, &key);   // 视为训练完成
    release_aot_training_marker();
    let _ = fs::remove_file(aot_training_argfile());
    let _ = fs::remove_file(aot_training_pid_file());
    return;
}
```

完成判定只有两个条件："`app.aot` 存在" + "marker 内容等于当前 key"。**没有 size、没有校验和、没有成功标记**。

**后果**

- [daemon.rs L6714](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6714) 的"缓存已训练"快速路径从此恒为真，**再也不会重训**；
- 后端按 [daemon.rs L6917-L6920](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6917-L6920) 附加 `-XX:AOTCache`，**判定条件只有 `cache_file.is_file()`——不校验 key、不看有没有完成标记**。因此半写文件不需要等到"被晋升"就已经会进入后端启动参数，"完成判定"只决定会不会重训，决定不了后端是否吃坏文件；
- 按 [daemon.rs L196](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L196) 的自述，JVM 在缓存不匹配时 "silently falls back"：启动变慢、告警可能被忽略，AOT 从优化手段退化为不可见的性能损失；
- 截断严重的文件还会让缓存加载阶段报错，把"优化"变成启动风险；
- 缓存位于 state dir（`~/.browser4/aot-cache`，跨卸载/重装保留），key 由 tag + jar 列表 + JVM flags 计算，同版本重装后 key 不变，**重装无法自愈**，只能手动删除 `aot-cache` 目录。

**可观测信号**：`app.aot` 大小明显小于正常训练产物；`app.aot.key` 存在而 `training.pid` 缺失；此后不再出现训练提示。

### A2（P0）"训练 → 被杀 → 下次启动再训练"的空转循环

**触发面（复审修正）**：`ensure_aot_cache_trained` 只在拉起后端（[L6280](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6280)）与 install/upgrade 时执行。因此循环的节拍是"后端每次需要（重）启动"，而不是"每条 CLI 命令"；后端存活期间的普通命令不会触发重训。

**链路**

1. 训练 JVM 被杀，`training.pid` 保留；
2. 下一次需要拉起后端的命令在 [daemon.rs L6748-L6769](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6748-L6769) 抢 marker 失败，走失败恢复快速路径 `reclaim_dead_aot_training()`；
3. [daemon.rs L6531-L6546](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6531-L6546) 确认 PID 已死，打印 `previous training run (pid ...) is no longer running — retraining now`，释放 marker 并**立即重训**；
4. 新训练 JVM 同样附着控制台，同样在命令结束后被杀，循环成立。

**后果**

- 每次后端（重）启动都额外拉起一个完整 Spring 上下文 JVM：`-Xmx1G`、全量类加载、磁盘写入，`CREATE_BELOW_NORMAL_PRIORITY_CLASS` 只能降低优先级，不能免除 CPU/IO 争用；在后端反复早退的场景下（本机实测），等于每条命令都付出一次训练成本；
- 命令行启动变慢，风扇/内存抖动可被用户感知；
- "one-time, only on first launch" 的提示长期反复出现，用户预期被破坏；
- [daemon.rs L6740-L6741](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6740-L6741) 的"先删缓存再训练"被反复执行，`app.aot` 反复被删除重写，进一步增加 A1 的撞车概率。

### A3（P1）后端长期无加速运行，且无可见提示

后端已脱管，训练 JVM 未脱管。于是出现**生命周期倒挂**：

| 进程 | 控制台关闭后 | 结果 |
| --- | --- | --- |
| 后端 JVM | 存活（已 `DETACHED_PROCESS`） | 继续提供服务 |
| 训练 JVM | 被强杀 | 缓存永远不生成 |

`/actuator/health` 仍是 `UP`，`status` 命令也不会提示"当前无 AOT 缓存"。用户只看到"启动一次比一次慢"。自愈（重训）只在**后端下次重启或 install/upgrade** 时触发；后端一直存活就一直不会重训。

另一个初稿遗漏的预期管理问题：**即使训练成功，正在运行的后端也不会受益**。AOT 缓存只在 JVM 启动时通过 `-XX:AOTCache` 附加，已常驻的进程无法 retroactively 加载它。所以"训练完成"与"后端变快"之间隔着一次后端重启，这一点必须在文档/提示中讲清楚，否则用户会以为训练完成后端就会自动提速。

### A4（P1）PID 判定假存活，造成最长 5 分钟静默阻塞与误导提示

[daemon.rs L6506-L6512](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6506-L6512)：

```rust
let out = Command::new("tasklist")
    .args(["/FI", &format!("PID eq {pid}"), "/NH"])
    .output();
match out {
    Ok(o) => String::from_utf8_lossy(&o.stdout).contains(&format!(" {pid} ")),
    Err(_) => true,
}
```

存活判定只检查 PID 这个数字是否出现在输出里，**不看镜像名、创建时间、命令行**；工具调用失败时直接返回 `true`（"保守判活"）。

**后果**

- 训练 JVM 死后 PID 被系统复用给任意无关进程，即被判为"仍在训练"；
- `reclaim_dead_aot_training()` 返回 false，[daemon.rs L6754-L6758](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6754-L6758) 打印 `JVM AOT cache training already in progress ...` 并直接返回；
- 接下来所有命令都不训练，直到 [daemon.rs L6572-L6582](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6572-L6582) 的 5 分钟 stale 窗口到期；
- 提示文案在此场景下是**反向误导**：它说后台正在训练，实际那个进程早已不存在。

### A5（P1）`training.log` 被截断，失败原因不可见

[daemon.rs L6841](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6841) 每轮训练使用 `fs::File::create(&log_file)`，即**截断重写**。

**后果**

- 上一轮失败的证据被下一轮覆盖；结合 A2 的循环，日志持续被清空；
- JVM 尚未输出任何内容就被杀时，得到 0 字节日志（本机已实测）；
- 现场排障只剩"进程消失了"这一条线索，既无堆栈、无 `hs_err`，也没有退出码记录。

### A6（P2）残留文件长期不被清理

`training.pid` / `training-args.txt` / `training.lock` 的清理只发生在三处：缓存有效快速路径（[L6717-L6719](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6717-L6719)）、绑定完成判定（[L6730-L6732](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6730-L6732)）、spawn 失败分支（[L6891-L6893](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6891-L6893)）。被强杀这条路径完全依赖下一次 `reclaim`；一旦命中 A4 的假存活，残留会持续存在。注意三者的残留时长并不相同（复审修正）：`training.lock` 有 5 分钟 stale 上限，会被 [L6572-L6582](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6572-L6582) 强制回收；真正长期残留的是 `training.pid` 与 `training-args.txt`。`training-args.txt` 还是固定名，跨进程共用。

### A7（P2）Ctrl+C 直接打到训练 JVM

未设置 `CREATE_NEW_PROCESS_GROUP`，训练 JVM 与控制台同组。用户 `Ctrl+C`、终端关闭、任务被终止时，训练 JVM 与 CLI 一起收到事件，把"强制终止发生在写缓存阶段"的概率推高，与 A1 叠加。宽限期内 JVM 关闭钩子与 AOT 写出都存在，两者谁先被切断取决于时机。

### A8（P2）"并发重训无害"的假设不成立

[daemon.rs L229-L232](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L229-L232) 的注释断言：

> Concurrent retraining of the same key is harmless (same input → same output, last writer wins)

`-XX:AOTCacheOutput` 直接写目标路径，两个 JVM 写同一个 `app.aot` 不是原子操作；"一个被杀、另一个刚起"或人工越过 marker 防护时，结果是**交错写**而不是"最后一个赢"。在正常单机路径上 marker 提供了互斥，但 marker 本身依赖 A4 的 PID 判定才能被正确释放，所以这个假设实际挂在不可靠的前提上。

### A9（P2）注释与设计文档掩盖了实现缺陷

"deliberately detached" 出现在两处代码注释（L6782、L6881）与一份设计文档（`aot-training-during-install-design.md` 2.2）。这类不一致会让后续修复者与评审者跳过这条路径——它比缺陷本身更难发现。

### A10（P1，复审新增）用户显式 `-XX:AOTCache` 被 state 缓存重复注入覆盖

**链路**

1. 用户通过 `BROWSER4_SERVER_OPTS=-XX:AOTCache=/path/to/my.aot` 显式指定缓存；该 token 被 `is_jvm_option` 识别并进入 `jvm_opts`（[L6374-L6382](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6374-L6382)）；
2. `ensure_aot_cache_trained` 检测到这一点，**正确地**跳过训练（[L6677-L6682](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6677-L6682)，注释写明 "Respect an explicit user-provided AOT configuration"）；
3. 但 `build_jar_launch_spec` 没有做同样的检查：只要 state dir 的 `app.aot` 存在，就再 push 一个 `-XX:AOTCache=<state>`（[L6917-L6920](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6917-L6920)）。

**后果**：同一条 JVM 命令行出现两个 `-XX:AOTCache`，且 CLI 注入的 state 缓存排在用户值**之后**。用户显式配置被覆盖；如果 state 缓存恰好是 A1 的半写文件，用户越显式指定自己的好缓存，越会被坏缓存顶替。两处"尊重用户配置"的逻辑只写了一半。

**修复方向**：`build_jar_launch_spec` 附加前检查 `jvm_opts` 是否已含 `-XX:AOTCache`，含则跳过（与 ensure 共用同一判定，避免再次漂移）。JVM 对重复 `-XX:AOTCache` 的确切行为（后者生效 vs 报错）需实测记录。

### A11（P1，复审新增）多版本 runtime 共用全局 `aot-cache`，互相删缓存、抢 marker

**事实**：缓存目录是 state dir 下的**全局唯一** `~/.browser4/aot-cache`（[L6449](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6449)），不按 runtime 版本分目录；`training.lock` / `training.pid` / `training-args.txt` 同样是单槽。

**链路**：key 由 tag + jar 列表 + JVM flags 计算，不同版本 key 必然不同。两个版本交替使用时：

1. 版本 A 训练完成，`app.aot` + `app.aot.key` 记录 A 的 key；
2. 版本 B 启动，key 不匹配 → [L6740-L6741](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6740-L6741) **删掉 A 的缓存**，开始训练 B；
3. 版本 A 再启动，又删掉 B 的缓存训练 A。

**后果**：多版本共存（4.13.x 与 4.14.x 并行、多个 checkout、CI 与本机共用 HOME）时缓存**永远命中不了**，且每次切换版本都付出一次完整训练；两版本还会互相 `reclaim` 对方的 marker/PID，A8 的"同 key 并发无害"假设在跨版本场景下更不成立。初稿只讨论了同 key 并发，遗漏了这个更常见的互踩场景。

**修复方向**：缓存与训练状态按 key（或 tag）隔离，例如 `aot-cache/<key>/{app.aot,app.aot.key,training.*}`；这样多版本各自缓存、互不删除，跨版本切换直接命中。

### 临时缓解（无需改代码）

`BROWSER4_CLI_DISABLE_AOT_CACHE=1`（接受 `1/true/yes/on`，[L2128-L2136](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L2128-L2136)）同时关闭**训练**（[L6673](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6673)）与**缓存附加**（[L6918](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6918)）。在修复落地前，这是一键止损手段：不再产生半写缓存、不再有空转训练 JVM，代价是失去 AOT 加速。

已怀疑存在坏缓存时：先删除 `~/.browser4/aot-cache`，再决定重训或暂时禁用。仅删除缓存而不禁用，会在下次后端启动时重新进入训练流程。

---

## 3. 根因归纳

| 根因 | 描述 | 对应问题 |
| --- | --- | --- |
| R1 | 训练 JVM 的 `creation_flags` 缺少 `DETACHED_PROCESS`（及 `CREATE_NEW_PROCESS_GROUP`），生命周期绑定调用方控制台 | A2 A3 A7 |
| R2 | 完成判定只看"文件存在 + marker key"，没有完整性/成功标记，半写文件会被晋升 | A1 |
| R3 | 存活判定只看 PID 数字，无法区分"训练 JVM"与"复用同一 PID 的任意进程" | A4 A6 A8 |
| R4 | 训练生命周期缺少单一真相：marker 同时承担锁与完成记录、`training.pid` 是旁证、日志被覆盖、清理分散在多个分支 | A5 A6 A8 |
| R5 | "尊重用户显式 AOT 配置"只在 ensure 里实现，后端加载路径没有同一判定 | A10 |
| R6 | 缓存与训练状态是全局单槽，不按 runtime key 隔离 | A11 A8 |

R1 是触发器，R2 是把"偶发被杀"升级成"永久坏缓存"的关键放大器。只修 R1 能让现象消失，但下一次任意原因（OOM、用户杀进程、机器重启）仍会触发 A1。R6 则解释了为什么"多版本共存"场景下即使没有任何进程被杀，缓存也始终命中不了。

---

## 4. 修复方案

### 4.1 P0-1：训练 JVM 真正脱管（最小改动，先落地）

**改动点**：`daemon.rs` `spawn_aot_training` 的 `#[cfg(windows)]` 块（[L6862-L6870](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6862-L6870)）。

**实现**：复用后端常量，与优先级标志组合。

```rust
#[cfg(windows)]
{
    use std::os::windows::process::CommandExt;
    // 与后端启动一致：脱离调用方控制台，避免控制台销毁时被强杀。
    cmd.creation_flags(BACKEND_CREATION_FLAGS | CREATE_BELOW_NORMAL_PRIORITY_CLASS);
}
```

`BACKEND_CREATION_FLAGS` 已含 `DETACHED_PROCESS` 与 `CREATE_NEW_PROCESS_GROUP`，三个标志可在同一次 `CreateProcess` 中组合。DETACHED_PROCESS 不分配控制台，`CREATE_NEW_PROCESS_GROUP` 让 `Ctrl+C` 不再直达训练 JVM（同时覆盖 A7）。

**需要同步**：

- 两处"deliberately detached"注释（L6782、L6881）改为准确描述：脱管由 `creation_flags` 实现，`drop(child)` 只是放弃等待；
- `aot-training-during-install-design.md` 2.2 节的"detached JVM"补一句依赖 `DETACHED_PROCESS` 的前提；
- 回归测试：在 `mod tests` 里增加与 [L9602-L9617](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L9602-L9617) 同构的断言，验证训练标志位同时包含 `DETACHED_PROCESS`、`CREATE_NEW_PROCESS_GROUP`、`CREATE_BELOW_NORMAL_PRIORITY_CLASS`。

**风险**：训练 JVM 不再随 CLI 退出而消失，若训练本身挂死，会长期占用 1G 堆。需与 A4 的存活判定改进配套（见 4.3），让"真正卡死的训练"能被回收，而不是被误判为活着。

### 4.2 P0-2：让 `app.aot` 只在训练成功时出现（复审定案）

这是根治 A1 的关键，必须与 4.1 一起做。初稿给出的两条路径在复审中被推翻，先记录原因，避免后续再绕回去：

- **"由训练 JVM 自己在成功时写 `.done`" 不可行**（初稿 4.4 曾这么写）：`-XX:AOTCacheOutput` 在 JVM **退出阶段**落盘，Java 侧没有任何代码能保证在"缓存写完之后"执行，shutdown hook 与 AOT dump 的先后顺序不受应用控制。凡依赖 Java 侧产出完成证据的方案都不成立。
- **路径 B（`.done` 侧车）即使可行也不够**：后端加载只看 `app.aot.is_file()`（A1），不看 key、不看 `.done`。`.done` 只能改善"是否重训"，**无法阻止后端加载半写文件**。
- **路径 A（CLI 下次启动时校验 tmp 再 rename）有证据缺口**：CLI 不等待训练进程，拿不到退出码，"tmp 存在 + PID 已退出"无法区分"正常写完"与"被杀留下半截"，只能靠大小/校验和猜测，而猜测正是当前 bug 的成因。

**定案：用包装进程把 JVM 退出码变成提交动作。**

训练不再直接 spawn `java`，而是 spawn 一个 shell 包装：

```text
Windows:  cmd /c "java @training-args.txt > training.log 2>&1 && ren app.aot.tmp app.aot"
POSIX:    sh  -c 'java ... > training.log 2>&1 && mv app.aot.tmp app.aot'
```

要点：

1. `-XX:AOTCacheOutput` 指向同目录临时名 `app.aot.tmp`（[L6805](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6805)）；
2. 用 `&&` 串联：**只有 JVM 退出码为 0 才执行 rename**。JVM 被杀、崩溃、机器重启都不会执行，`app.aot` 根本不会出现；`app.aot.tmp` 残留由下次启动清理；
3. rename 与目标同目录，同卷原子改名，不存在"半个 `app.aot`"的中间态；
4. 由此 **`app.aot` 存在 ⇔ 训练成功提交**，两处既有逻辑不用改就自动变安全：
   - 后端 [L6918](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6918) 的 `is_file()` 判定不再可能吃到半写文件；
   - [L6728](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6728) 的"文件存在 + marker key"晋升也变得可信（文件只可能来自成功提交）；
5. 不需要 `.done`、不需要大小/sha256 校验、**不需要改 Java 侧**；
6. 包装进程与 JVM 一起使用 4.1 的 `DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP`，JVM 继承 cmd/sh 的无控制台状态；训练 PID 记录包装进程 PID 即可（判活语义不变）；
7. 顺带解决 A8：两个并发训练都成功时，最后一次原子 rename 覆盖前一份完整文件，"last writer wins" 在文件级别真正成立，而不是交错写。

**实施前必须验证的两个假设**：

- AOT 缓存文件不内嵌自身输出路径，rename 后仍可被 `-XX:AOTCache` 正常加载；
- JVM 在 AOT 写出失败时以非 0 退出。若实测发现写出失败仍返回 0，需在包装中追加最小尺寸检查（`&& if exist ...` 判断大小下限）作为补充条件。

**兼容性与旧缓存清理**：旧版本留下的 `app.aot` 无法自证来源（可能就是半写品）。建议与 A11 的按 key 隔离合并实施：新格式使用 `aot-cache/<key>/` 目录，旧的全局 `app.aot` 天然不再被读取，等效于"旧缓存一律重训一次"，避免专门的迁移分支。

### 4.3 P1：存活判定强化

**改动点**：`aot_training_process_alive`（[L6500-L6522](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6500-L6522)）。

**实现方向（择一，按成本递增）**：

1. `tasklist /FI "PID eq <pid>" /FO CSV /NH` 解析完整字段，同时校验**镜像名**为 `java.exe`（`java_path` 的 basename），不匹配即判死；
2. 追加创建时间校验：把训练 JVM 的创建时间写入 `training.pid` 的伴随字段（如 `training.pid` 内容改为 `pid,createdAtMillis`），判活时比对，防止 PID 复用；
3. Job Object：spawn 后把训练进程放进命名 Job，用句柄存在性判断存活，彻底摆脱 `tasklist` 解析（Windows 专用，代码量最大）。

判活失败（工具不可用）时**保留**当前"保守返回 `true`"，但应把等待上限从 5 分钟收敛到与训练实际时长匹配的窗口，并在超过后强制回收 + 重训（避免无限期误判）。

### 4.4 P2：日志、清理与一致性

| 项 | 改动 | 位置 |
| --- | --- | --- |
| A5 | `File::create` 改为追加（`OpenOptions::new().append(true).create(true)`），或按 `training-<timestamp>.log` 分文件并保留最近 N 份；采用 4.2 的包装进程后，重定向由包装 shell 完成，追加语义在 shell 命令里用 `>>` 表达 | [L6840-L6860](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6840-L6860) |
| A5 | 训练退出状态不再需要额外记录：4.2 中 `app.aot` 是否出现本身就是退出码的物化结果；训练耗时可由包装 shell 在 rename 后追加一行到日志 | 与 4.2 合并 |
| A6 | `ensure_aot_cache_trained` 入口做一次**幂等清理**：缓存无效时统一删除 `app.aot` / `app.aot.tmp` / `training-args.txt` / `training.pid`，清理逻辑收敛到一处 | [L6711-L6752](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6711-L6752) |
| A8 | 删除 L229-L232 的 "last writer wins" 断言，改为"训练写临时名，包装进程仅在退出码为 0 时原子 rename；并发成功的结果是完整文件覆盖完整文件" | [L229-L232](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L229-L232) |
| A9 | 修正两处 "deliberately detached" 注释与设计文档 2.2 | L6782、L6881、`aot-training-during-install-design.md` |
| A3 | `status` / 启动日志在无缓存时输出一行：`AOT cache: absent (server running without acceleration)`；训练完成后提示"缓存已就绪，下次后端重启生效"，避免用户误以为常驻后端会自动提速 | `ensure_aot_cache_trained` 或 `handle_status` |
| A10 | `build_jar_launch_spec` 附加 state 缓存前，先检查 `jvm_opts` 是否已含 `-XX:AOTCache`（与 ensure 共用同一判定函数），含则跳过 | [L6917-L6920](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6917-L6920) |
| A11 | 缓存与训练状态按 key 隔离：`aot-cache/<key>/{app.aot,app.aot.key,training.*}`，多版本互不删除、互不抢 marker；同时天然完成旧格式缓存失效（见 4.2 兼容性） | [L6449-L6469](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L6449-L6469) |

---

## 5. 验收与测试

### 5.1 单元测试（Windows）

1. `training_launch_detaches_from_the_callers_console`：断言训练标志包含 `DETACHED_PROCESS`、`CREATE_NEW_PROCESS_GROUP`、`CREATE_BELOW_NORMAL_PRIORITY_CLASS`（与后端同构）；
2. 提交语义：构造"`app.aot.tmp` 存在但无 `app.aot`"（模拟训练被杀）→ 断言不进入"缓存已训练"分支、不附加 `-XX:AOTCache`，并清理 tmp 后重训；
3. 提交语义：构造"`app.aot` 存在且 marker key 匹配"（模拟包装进程已成功 rename）→ 断言写 `app.aot.key` 并清理 marker/pid/argfile；
4. 存活判定：给定一个确定不存在的 PID → 判死；给定一个非 java 镜像名的 PID（如 `notepad.exe`，测试内以 `cmd.exe` 模拟）→ 判死；
5. 日志策略：两次训练后旧日志内容仍可读（追加模式）或存在多份文件；
6. A10：`jvm_opts` 已含 `-XX:AOTCache` 时，`build_jar_launch_spec` 不再追加第二个；
7. A11：两个不同 key 的缓存目录可并存，训练其一不删除另一个。

### 5.2 手工端到端（关键场景）

在一次性终端（命令结束即销毁窗口的会话）中执行：

1. `browser4-cli install`（或清空 `~/.browser4/aot-cache` 后 `browser4-cli open`）→ 触发训练；
2. 命令结束后立刻关闭终端；
3. 等待训练时长（约 60–180s），检查：
   - `tasklist /FI "IMAGENAME eq java.exe"` 中训练 JVM 仍在（脱管生效）；
   - `training.log` 有内容且非空（A5）；
   - 训练结束后 `app.aot` 出现，且训练过程中任何时刻都不存在"半个 `app.aot`"（只有 `app.aot.tmp`）；
   - 后端启动参数中 `-XX:AOTCache` 指向该文件，启动明显变快（缓存命中）。
4. 反向验证：手工 `taskkill /PID <训练 pid> /F` 模拟训练失败 → 断言 **`app.aot` 不出现**（包装进程 `&&` 未执行 rename），只有 tmp 残留；下一次后端启动应在**秒级**内判定死亡并重训，而不是等 5 分钟（A4 + 4.3）。

### 5.3 回归

- `cargo build`、`cargo test --bin browser4-cli`；
- 确认既有 2010 项测试不回退；
- 确认后端 `DETACHED_PROCESS` 修复的回归测试仍通过（[L9602-L9617](file:///d:/workspace/Browser4Team/submodules/Browser4/cli/browser4-cli/src/daemon.rs#L9602-L9617)）。

---

## 6. 实施顺序与状态

| 批次 | 内容 | 状态 |
| --- | --- | --- |
| 1 | 4.1 训练脱管（`AOT_TRAINING_CREATION_FLAGS`）+ 4.2 包装脚本按退出码提交（`app.aot.tmp` → `app.aot`） | ✅ 已实施，见 §8.1 |
| 1 | A5 日志追加、A6 清理 `app.aot.tmp` / 训练脚本、A9 注释修正、A10 重复注入 | ✅ 已实施，见 §8.1 |
| 2 | 4.3 判活强化：`is_pid_alive` + 记录并校验进程启动时间（不引入 Job Object） | ✅ 已实施，见 §8.5 |
| 3 | A11 按 key 隔离缓存目录 `aot-cache/<key>/`，含 LRU 修剪与 legacy 处理 | ✅ 已实施，见 §8.6 |
| 4 | 4.4 的 status 提示（`AOT cache: …` 一行 + JSON 字段） | ✅ 已实施，见 §8.7 |

只做 4.1 而不做 4.2 是不够的：脱管只消除"控制台关闭"这一个触发源，OOM、用户杀进程、机器重启仍会产生半写文件，而 L6728 与 L6918 会分别把它固定下来、把它带进后端。

修复落地前的临时手段见第 2 节末尾"临时缓解"：`BROWSER4_CLI_DISABLE_AOT_CACHE=1`。

---

## 7. 待确认

1. ~~完成判定采用路径 A 还是路径 B~~（已定案并实施：包装进程按退出码提交，见 4.2 / §8.1）；
2. ~~判活是否引入 Job Object~~（已定案：不引入。改为 `is_pid_alive` + PID 文件记录并校验进程启动时间，见 §8.5；Job Object 是"进程树随句柄回收"的更强方案，仍可作为后续增强）；
3. ~~A11 是否按 key 隔离缓存目录~~（已定案并实施：`aot-cache/<key>/`，见 §8.6）；
4. ~~`status` 提示是否纳入~~（已定案并实施，见 §8.7）；
5. ~~AOT 缓存 rename 后能否被 `-XX:AOTCache` 正常加载；JVM 在 AOT 写出失败时是否非 0 退出~~（已实测并据此加固，见 §8.9：rename 不影响加载；写出失败仍返回 0，故包装脚本追加了尺寸下限）。

---

## 8. 实施记录（2026-11-01，批次 1–4）

### 8.1 代码改动（`cli/browser4-cli/src/daemon.rs`）

| 位置 | 改动 |
| --- | --- |
| 常量区 | 新增 `AOT_TRAINING_OUTPUT_FILE_NAME`（`app.aot.tmp`）、`AOT_TRAINING_SCRIPT_FILE_NAME`（`training.cmd` / `training.sh`）、`AOT_TRAINING_CREATION_FLAGS`（`BACKEND_CREATION_FLAGS \| CREATE_BELOW_NORMAL_PRIORITY_CLASS`）；`AOT_TRAINING_MARKER_STALE_AFTER` 的注释从 "last writer wins" 改为"两次训练都写 tmp、只有完整文件覆盖完整文件" |
| 路径访问器 | 新增 `aot_training_output_file()`、`aot_training_script_file()` |
| `ensure_aot_cache_trained` | 无效分支追加删除 `app.aot.tmp`；晋升分支注释改为"包装进程已提交"（`app.aot` 只可能来自退出码 0） |
| `spawn_aot_training` | `-XX:AOTCacheOutput` 指向 `app.aot.tmp`；改为 spawn `cmd /d /c training.cmd`（POSIX 为 `sh training.sh`）；creation flags 换用 `AOT_TRAINING_CREATION_FLAGS`；日志改为追加并写入 `--- AOT training run started ---` 分隔行；训练恒用 `@argfile`；失败分支清理脚本与 tmp |
| `aot_training_script_contents()` | 新增：脚本 `cd` 到缓存目录，JVM 退出码非 0 立即退出；为 0 时还要校验 `app.aot.tmp` 存在且 ≥ `AOT_CACHE_MIN_COMMIT_BYTES`（真机实测发现写出失败也返回 0，见 §8.9），通过才 `move /y app.aot.tmp app.aot` |
| `build_jar_launch_spec` | `jvm_opts` 已含 `-XX:AOTCache=`（用户显式配置）时不再追加 state 缓存（A10） |

### 8.2 与 4.2 初稿的差异（实施时修正）

1. **POSIX 也改用 `@argfile`**：`should_use_java_argfile` 原本只在 Windows 且超阈值时返回 true；AOT 训练只在 JDK 24+ 启用，argfile 必然可用，两平台统一走 argfile，脚本文本更简单。
2. **脚本 `cd` 到缓存目录**：避免 Windows 上 `@"C:\path with space\args.txt"` 的引号歧义；argfile、tmp、最终缓存在脚本内都用相对名。
3. **`spawn_aot_training` 删除 `cache_file` 参数**：rename 目标由脚本里的 `AOT_CACHE_FILE_NAME` 表达，参数已无使用者（避免留下未使用参数）。

### 8.3 验证

| 项 | 结果 |
| --- | --- |
| `cargo build --bin browser4-cli` | 通过 |
| `cargo test --bin browser4-cli aot` | 9 passed，含新增 `aot_training_launch_detaches_from_the_callers_console`（三个 flag 位）、`aot_training_wrapper_commits_only_on_success`（脚本提交条件） |
| `cargo test --bin browser4-cli`（全量） | 2012 passed / 0 failed / 4 ignored |
| 脚本语义实测（临时目录 + `cmd`） | 成功分支：`exit=0` 且 `app.aot` 出现；失败分支（java 路径不存在）：`exit=1`、`app.aot` **不存在**、`app.aot.tmp` 保留。这是方案的核心保证 |

### 8.4 未验证 / 遗留

- ~~`-XX:AOTCache` 指向 rename 后的文件能否正常加载~~（2026-11-01 已实测：**可以**，见 §8.9）。
- ~~JVM 在 AOT 写出失败时的退出码是否非 0~~（2026-11-01 已实测：**仍为 0**，且留下 0 字节产物；因此脚本已追加尺寸下限，见 §8.9）。
- `aot-training-during-install-design.md` 2.2 已同步（detached 的真实来源、`app.aot.tmp` 提交语义、5 分钟 stale 窗口）；该文档多处行号（`daemon.rs:4698` 等）已随代码增长漂移，未逐一重算。
- 批次 2–4（判活强化、A11 按 key 隔离、status 提示）已在批次 1 之后落地，见 §8.5–§8.7。

### 8.5 批次 2：判活强化（4.3）

| 项 | 内容 |
| --- | --- |
| 存活探测 | 不再走 `tasklist` 字符串匹配，改用文件内已有的 `is_pid_alive`（Windows 走 `OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION)`，POSIX 走 `kill -0`） |
| PID 复用识别 | `training.pid` 改为两行：第一行 PID，第二行写入时刻（epoch 秒）。Windows 用 `GetProcessTimes` 读实际创建时间，与记录值比对，容差 `AOT_TRAINING_START_TIME_TOLERANCE_SECS = 10s` |
| 未知值降级 | 旧格式 `training.pid`（无第二行）或无法查询创建时间时，按"仍存活"处理，继续用 5 分钟 stale 窗口兜底，不冒并发重训风险 |
| 未做 | Job Object（成本高，且需要新依赖；当前两级校验已消除 PID 复用误判这一主要场景） |
| 测试 | `test_is_pid_alive_tracks_a_child_process`、`test_is_pid_alive_rejects_an_unused_pid`、`test_aot_training_start_time_guard_rejects_recycled_pid`、`test_aot_training_pid_file_roundtrip`（校验第二行启动时间） |

> 注：原计划的"镜像名"校验未实现——创建时间校验严格强于镜像名（PID 被复用时创建时间必然不同），且省掉一次 `tasklist` 子进程调用。

### 8.6 批次 3：A11 按 key 隔离缓存目录

| 项 | 内容 |
| --- | --- |
| 目录结构 | `~/.browser4/aot-cache/<key>/`，内含 `app.aot`、`app.aot.tmp`、`training.lock`、`training.pid`、`training-args.txt`、`training.cmd`/`.sh`、`training.log` |
| 路径 API | `aot_cache_root()`（根）、`aot_cache_dir(key)` 及全部文件访问器改为接收 `key` |
| 完成判定 | 删除 `app.aot.key` 侧车与 marker-key 晋升逻辑：key 目录内的 `app.aot` 只可能来自包装脚本在退出码 0 时的 rename，文件存在即完成 |
| 服务端附加 | `build_jar_launch_spec` 现按同一套 key 计算定位缓存目录；`lib_dir` 不可读时不附加（等价于"无缓存"） |
| 修剪 | `prune_stale_aot_cache_dirs`：只清理 key 子目录，保留最近 `AOT_CACHE_KEEP_DIRS = 3` 个；含 `training.lock` / `training.pid` 的目录跳过；根目录下的文件一律不动 |
| legacy | 旧扁平布局（缓存文件直接位于 `aot-cache/` 根）不迁移也不删除：旧版 CLI 可能仍在使用，新版按 key 目录未命中即一次性重训 |
| 测试 | `test_aot_cache_dir_lives_under_state_dir`（根固定为 state/aot-cache，key 目录挂在其下）、`test_aot_training_marker_is_scoped_to_one_key`（不同 key 的 marker 互不阻塞） |

### 8.7 批次 4：`status` 的 AOT 缓存提示

- `daemon::aot_cache_status_line()` 输出一行状态，`handle_status` 打印为 `AOT cache: …` 并写入 JSON 字段 `aot_cache`：
  - `disabled (BROWSER4_CLI_DISABLE_AOT_CACHE)`；
  - `user-configured via BROWSER4_SERVER_OPTS (-XX:AOTCache)`；
  - `training in progress (log: …)`（某 key 目录有 marker 且无 `app.aot`）；
  - `N trained cache(s) under … — attached when the server starts`；
  - `none yet under … — the next server start trains it in the background`。
- 文案刻意不说"当前 runtime 已加速"：缓存按 key 隔离，且只在服务端**启动**时附加（A3）。

### 8.8 批次 2–4 验证

| 项 | 结果 |
| --- | --- |
| `cargo build --bin browser4-cli` | 通过，无警告 |
| `cargo test --bin browser4-cli aot` | 9 passed |
| `cargo test --bin browser4-cli`（全量） | 2014 passed / 0 failed / 4 ignored |

### 8.9 真机实测（2026-11-01，本机 Windows + Temurin 25.0.4.1）

环境：`C:\Users\pereg\AppData\Roaming\browser4\runtime\v4.15.2\runtime\bin\java`（Temurin 25.0.4.1）；state 目录下已有一份旧扁平布局产物（`aot-cache/app.aot` 91,160,576 bytes、`app.aot.key`、`training.log`，日志末尾为 `AOTCache creation is complete ... 91160576 bytes`）。

| 验证 | 方法 | 结果 |
| --- | --- | --- |
| rename 后能否加载 | `java -XX:AOTCacheOutput=<tmp>\a.aot -version` 生成缓存 → 改名为 `b.aot` → `java -XX:AOTCache=<tmp>\b.aot -Xlog:aot -version` | **可加载**：`[info][aot] Opened AOT cache ...\b.aot`，无 mismatch 告警，`exit=0`。文件名/路径不参与缓存校验，包装脚本的 rename 提交不影响可用性 |
| 写出失败的退出码 | `-XX:AOTCacheOutput` 指向一个已存在目录 | **exit=0**，日志出现 `[error][aot] Unable to create AOT cache ... (I/O error)` + `An error has occurred while writing the shared archive file`，随后仍打印 `AOTCache creation is complete: ... 0 bytes` |
| 写出失败的退出码（另一路径） | `-XX:AOTCacheOutput=C:\Windows\System32\aot-deny.aot` | 同上：`exit=0`，先 `Unable to create AOT configuration file` 失败，文件未创建 |

**结论与加固**：退出码**不能**作为成功信号，仅凭 `exit 0` 会把 0 字节产物 rename 成 `app.aot`，正是 A1 换了形态的复发。已在包装脚本的提交条件上加尺寸下限：

| 位置 | 改动 |
| --- | --- |
| 常量区 | 新增 `AOT_CACHE_MIN_COMMIT_BYTES = 1 MiB`（注释记录本次实测证据） |
| `training.cmd` | `if not exist app.aot.tmp exit /b 1` → `for %%A in ("app.aot.tmp") do set AOT_OUTPUT_BYTES=%%~zA` → `if %AOT_OUTPUT_BYTES% LSS <floor> exit /b 1` → 才 `move /y` |
| `training.sh` | 等价：`[ -f ... ] || exit 1`、`wc -c` 取字节数、`[ "$output_bytes" -ge <floor> ] || exit 1` → 才 `mv -f` |
| 失败时 | 不提交、保留 `app.aot.tmp` 作为现场证据，下一次训练在 spawn 前删除它 |
| 测试 | `aot_training_wrapper_commits_only_on_success` 增加尺寸下限断言 |

脚本语义实测（临时目录 + `cmd /d /c`）：0 字节 tmp → `exit=1`、`app.aot` 未生成、tmp 保留；tmp 缺失 → `exit=1`；2 MiB tmp → `exit=0` 且提交为 `app.aot`。

> 上限说明：尺寸下限只能覆盖"完全写不出 / 被截断为近 0"这类失败，无法识别"写到一半被杀"的部分文件（真实缓存约 91 MB，1 MiB 下限对部分写不敏感）。这部分由批次 1 的脱管控制台（2026-11-14 起为 `CREATE_NO_WINDOW` 隐藏控制台，见 §8.10）消除控制台强杀路径，叠加 JVM 加载不匹配时静默回退，最坏结果是"没有加速"而非启动失败。

> 另注：本机遗留的扁平布局产物（91 MB）在新布局下不再被附加——`aot-cache/` 根目录未命中 key 目录即触发一次性重训（§8.6 的 legacy 策略）。

### 8.10 批次 5：训练不再弹出控制台窗口（2026-11-14）

**现象**：Windows 交互式桌面上，`install` / 首次 `open` 触发 AOT 训练后，会出现一个终端标签页/窗口，标题就是训练 JVM 的命令行，持续到训练结束（实测全程 60–180s）。

**根因**：§4.1 的 `DETACHED_PROCESS` 只作用于**直接子进程**。训练的命令树是 `cmd.exe /d /c training.cmd` → `java.exe`：

1. `DETACHED_PROCESS` 让 `cmd.exe` 无控制台（这部分是对的，控制台关闭不会再杀训练）；
2. 但 Windows 会给"父进程无控制台的控制台子系统程序"分配一个**新控制台**，`java.exe` 于是拿到自己的控制台；
3. 默认终端宿主（Windows Terminal）把它显示为可见窗口/标签页——`Win32_Process.Create` 那条后端路径当初需要 `SW_HIDE` 的正是同一个机制。

**实测**（本机 Windows，精确复刻 spawn 形状：`cmd /d /c wrapper.cmd` → 控制台探针，stdout/stderr 重定向到日志、继承句柄）：

| 创建 flags | 新 conhost 的父进程 | 探针看到的控制台 |
| --- | --- | --- |
| `0x4208`（旧：`DETACHED\|NEWPG\|BELOW`） | `java.exe` | `hwnd` 非 0、`visible=true` → **可见窗口** |
| `0x8004200`（新：`NO_WINDOW\|NEWPG\|BELOW`） | `cmd.exe` | `hwnd=0`、`GetConsoleProcessList=2` → 隐藏控制台，无窗口 |
| `0x8004208`（`DETACHED\|NO_WINDOW\|…`） | `java.exe` | 可见窗口——**两者不叠加，DETACHED 胜出** |
| 直启 `java.exe` + `DETACHED` | 无新 conhost | 无控制台（后端直启路径因此本来就没事） |

**改动**：

| 位置 | 改动 |
| --- | --- |
| `AOT_TRAINING_CREATION_FLAGS` | 不再复用 `BACKEND_CREATION_FLAGS`：改为 `CREATE_NO_WINDOW \| CREATE_NEW_PROCESS_GROUP \| CREATE_BELOW_NORMAL_PRIORITY_CLASS`，注释记录"为何不能用 DETACHED、为何两者不能叠加" |
| `spawn_aot_training` 文档注释 | 从"detached"改为"自有隐藏控制台"，并说明生命周期保证来自哪里 |
| 测试 | 旧 `aot_training_launch_detaches_from_the_callers_console` → `aot_training_launch_shows_no_console_window`：先钉住 flag 组合（含 `DETACHED_PROCESS` 必须缺席），再用**真实进程树**跑一遍 `cmd.exe → 控制台探针`，断言探针拿到的控制台没有窗口 |
| 探针 | 用测试二进制自身当探针（`--exact daemon::tests::aot_training_console_probe_child` + 环境变量回传报告），需要 `GetConsoleWindow` / `IsWindowVisible` 两个 P/Invoke；正常跑测试时该用例因环境变量缺失直接返回 |

**验证**：

1. 单测：`cargo test --bin browser4-cli aot` → 10 passed；把探针临时改回旧 flags 后该用例失败（`hwnd=5967762 visible=1`），证明断言不是空跑；全量 `cargo test --bin browser4-cli` → 2208 passed / 0 failed / 4 ignored。
2. 真机端到端（2026-11-14，本机 Windows + 已安装 runtime v4.16.6 / Temurin 25.0.5）：用修复后的 CLI 执行 `browser4-cli install --tag v4.16.6`（复用已安装 runtime → 触发真实 `spawn_aot_training`），同时以 1s 间隔采样可见控制台窗口、conhost 归属与训练进程：

| 观测 | 结果 |
| --- | --- |
| 可见控制台窗口集合（115 个采样，覆盖训练全程 13:45:47–13:48:57） | **始终只有** harness 自己的一个窗口，从未变化 |
| 训练进程树 | `cmd.exe 81740`（父 = CLI）+ `java.exe 21232`（父 = cmd），`training.pid=81740` |
| conhost 归属 | 只有 `78920<-81740`（cmd.exe 的隐藏控制台）在训练期间存在；**从未**出现父进程为 java 的 conhost（与 §8.10 表中 `DETACHED` 的行为相反） |
| 训练结果 | 190s 完成，`app.aot` 87,818,240 字节由包装脚本 `move` 提交，`training.log` 结尾为 `AOTCache creation is complete ... 87818240 bytes` |
| 二次 `install` | 1s 返回，无新训练进程；`training.pid` / `training-args.txt` / `training.lock` 被清理，`app.aot` 保留 → 缓存晋升路径正常 |

**遗留**：POSIX 未改（`sh training.sh` 不涉及终端窗口，也仍未 `setsid`，终端关闭时的 SIGHUP 风险与本次修复无关）。
