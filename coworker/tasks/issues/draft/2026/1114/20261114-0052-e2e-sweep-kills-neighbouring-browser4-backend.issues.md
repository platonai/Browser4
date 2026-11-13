# Issues: e2e harness 的 pre/post sweep 会强杀同机其他 checkout 的 Browser4 后端（全局 JVM 扫描 + 共享 dev 端口）

> **Source:** 2026-11-14 00:30–00:36 本机实测（一次 `cargo test --test e2e -- --scenario=test_e2e_agent_run_mock_*unrestricted*`）+ 源码核对
> **Date:** 20261114-0052 · **Mode:** dev
> **GitHub:** [#640 browser4-cli e2e sweep 会强杀同机其他 checkout 的 Browser4 后端](https://github.com/platonai/Browser4/issues/640)
> **状态:** **已修复**（2026-11-14，见 §7；§1–§5 保留修复前的现场与方向）
> **代码基线:** `4.16.x` @ `733b3236b5`
> **受害现场:** 另一 checkout `D:\workspace\Browser4\Browser4-4.16-feat`（state 命名空间 `Browser4-4.16-feat-adc2c6c2`）
> **报告人:** 本会话 agent（跑 e2e 的一方），以下时间均为 Asia/Shanghai

---

## 0. 结论摘要

| 编号 | 问题 | 是产品缺陷吗 | 置信度 | 建议决策 |
|---|---|---|---|---|
| I1 | e2e 的 pre/post sweep 用**全局 JVM 命令行扫描**强杀所有 Browser4 后端，不区分 checkout / state 目录 / 端口 | 是（跨会话误杀） | 高 | ACCEPT |
| I2 | sweep 的端口列表 = **陈旧 registry** + 硬编码默认端口（8182/18182），会固定瞄准邻居的端口 | 是（放大 I1） | 高 | ACCEPT |
| I3 | 浏览器清扫同样全局（`PULSAR_CHROME` / 命令行含 `browser4`），会关掉别人的浏览器窗口 | 潜在缺陷（本次 0 命中） | 中高 | ACCEPT |
| I4 | 两个 checkout 的 dev 后端都解析到 **8282**，`cli-state.json` 陈旧时不会纠正 | 是（隔离缺口） | 高 | ACCEPT |
| I5 | sweep 只打印计数，不打印被杀的 pid / 命令行；e2e 输出被截断后**无法审计** | 是（可观测性） | 高 | ACCEPT |

**一句话**：在 checkout A 跑任何 e2e 场景（哪怕全是 mock、完全不需要 Browser4），A 的 pre-sweep 都会对全机所有 `Browser4.jar` JVM 执行强杀 —— checkout B 正在跑的后端与它当前的 agent 任务会**无提示**中断，然后被 B 的 CLI 自动重启。

---

## 1. 本次事故时间线（实测证据）

| 时间 | 事件 | 证据 |
|---|---|---|
| 00:30:25–00:30:28 | 邻居 checkout `Browser4-4.16-feat` 的后端启动并提交任务 `9c97e8c0…` | `…/Browser4-4.16-feat-adc2c6c2/app-data/logs/agent/20261114.003025/`；`async-tasks.json` 中 `submittedAt=11/14/2026 00:30:28`；`aot-cache/…/logs/pulsar.*.log` 00:30:28 |
| 00:30:29–00:31:16 | 该任务持续推理，`cli-prompt/*.request.json` 每隔数秒一个 | 同目录 `cli-prompt/20261114.0030xx.*.request.json` |
| 00:31:26–00:31:28 | 该任务**最后写入**（`agent-trace.jsonl`、`cli-events.jsonl`、`cli-usage.jsonl`、`cli-compactions.jsonl`） | 同目录 |
| **00:31:29–00:31:37（推断区间）** | **本 checkout 的 e2e pre-sweep 执行**：`stop_browser4_server_forcibly()`（下界 = 邻居最后一次写入 00:31:28，上界 = 本 checkout 场景启动 00:31:38） | `cli/browser4-cli/tests/e2e/mod.rs:6979-6992` |
| 00:31:38 | 本 checkout 的 final cleanup：`Notifying … http://localhost:8282 → unreachable`；`Stopped 0, forced 0, still running 0` | 我的 e2e 输出（pre-sweep 段被尾部截断，见 §9） |
| 00:32:39 | 邻居 checkout 重写 `server-plugins-fingerprint.json`（后端重新启动/接管） | `…/Browser4-4.16-feat-adc2c6c2/server-plugins-fingerprint.json` |
| 00:33:34–00:33:37 | 邻居 checkout 新建 `logs/agent/20261114.003334/`，并提交新任务 `b059b770…` | 同目录；`async-tasks.json` |
| 00:39 | 复核：18182 / 8282 / 23785 均无监听；无 `Browser4.jar` JVM；无 `PULSAR_CHROME` 浏览器 | `Get-NetTCPConnection`、`Get-Process` |

**影响**：邻居 checkout 的任务 `9c97e8c0…`（00:30:28 提交）从 00:31:28 起再无任何写入（是否被信号终止需邻居侧日志确认，本次未取得其 stderr/exit code）；其 workspace/checkpoint 仍在 `…/app-data/agent/2026/11/14/9c97e8c0…/`，00:33:37 起以新任务 `b059b770…` 继续。**未**误杀用户浏览器（见 I3 的现场核对）。

---

## I1：pre/post sweep 是全局 JVM 扫描

**Severity:** High（静默中断其他会话的进行中任务，接近 Critical 的数据丢失风险）· **Category:** Reliability · **复现状态:** 已现场复现

### 现象

e2e harness 在开跑前和结束后都会调用 `stop_browser4_server_forcibly()`：

- `cli/browser4-cli/tests/e2e/mod.rs:6979-6992`（pre-sweep）与结束清理均调用它；
- `managed_processes.rs:309-338` → `stop_browser4_server_with_fallback_force_kill()`（`:340-353`）
  → `force_kill_all_browser4_server_processes(&ports)`（`:1237-1245`）；
- 该函数第一步是 **`find_browser4_server_processes()`（`:1153-…`）**：PowerShell 快照全部 JVM，按命令行匹配
  `browser4.jar | browser4launcherkt | browser4bundleapplicationkt | browser4standaloneapplicationkt`
  （`:1162`、`:1177-…`），**不检查 checkout、state 命名空间、端口、父进程**；
- 第二步才是按端口 `find_java_pids_listening_on_ports(ports)`（`:1242-1243`）。

同文件已经有一个**安全版本**：

```rust
/// Used by the development-mode `stop`, which must not touch backends owned by
/// neighbouring workspaces (each checkout runs its own port ...)
fn force_kill_browser4_server_processes_on_ports(ports: &[u16]) -> ServerKillResult
```

`managed_processes.rs:1247-1255` —— 但 e2e 的 sweep 没有用它。

### 影响

- 同一台机器上并行跑多个 checkout / 多个 agent 会话是本项目的正常工作方式（`~/.browser4/workspaces/` 下已有数百个 per-checkout 命名空间）；任一 checkout 跑 e2e 都会把其余所有 checkout 的后端杀掉；
- 被杀会话的 `agent run` 任务在无提示的情况下中止（本次是 00:31:28 断流），只能靠 CLI 自动重启后端 + 用户/agent 重新提交任务恢复；
- CI 容器里通常只有一个后端，所以该缺陷在 CI 中不可见，只在开发机/多会话环境暴露。

### 候选修复（未实施）

| 改动 | 位置 | 内容 |
|---|---|---|
| sweep 限定归属 | `managed_processes.rs:1237-1245` / `stop_browser4_server_forcibly` | 新增"仅本 state 命名空间"的停止路径：registry 里的 pid + `is_registered_browser4_server_process(pid, port)` + 进程可执行/jar 路径属于本 checkout 的 runtime 目录 |
| 全局扫描降级为显式 | `managed_processes.rs:1153` / `tests/e2e/mod.rs:6980` | 全局 `find_browser4_server_processes()` 只留给 `kill-all` / `stop --all`，由 `--sweep-others`（或 env，如 `BROWSER4_E2E_SWEEP_OTHERS=1`）显式开启；e2e 默认关闭 |
| 默认端口只在 kill-all 加 | `managed_processes.rs:1398-1410` | `managed_server_ports_for_sweep()` 不再无条件加入 8182/18182，改为调用方可选参数 |
| 浏览器清扫同规则 | `managed_processes.rs:598` / `:680` | `kill_all_browsers`/`kill_orphaned_browser_processes` 同样加归属判断（见 I3） |

---

## I2：端口列表来自陈旧 registry + 硬编码默认端口

**Severity:** High（把 I1 的"误杀范围"进一步对准邻居）· **Category:** Reliability · **复现状态:** 已核对

- `managed_server_ports_for_sweep()`（`managed_processes.rs:1398-1410`）读**本命名空间** registry 的端口，再无条件追加 `[8182, 18182]`。
- 本 checkout 的命名空间 `Browser4-4.16-02e52167` 最后一次真实活动是 2026-11-12 23:59；其 `cli-managed-processes.json` 现已不存在（`write_managed_server_processes` 在空列表时 `remove_file`，`managed_processes.rs:131-139`，死 pid 会被清空），命名空间目录 mtime 恰在 00:31:29 变化，与该写入一致（推断）。
- 无论端口来自 registry 还是 `cli-state.json`（它记录 `"baseUrl": "http://localhost:8282"`，2026-11-12 创建），本次 final cleanup 的 notify 目标是 **8282** —— 正是邻居的端口。也就是说：**陈旧的 state 让 sweep 瞄准了邻居**，而 I1 的全局扫描则保证"即使端口填错也照样杀得到"。

**候选修复**：sweep 前重新解析端口（校验监听者归属），而不是信任陈旧 state；`cli-state.json` 的 `stale: true` 应阻止用它做任何终止决策。

---

## I3：浏览器清扫也是全局的（本次未命中）

**Severity:** Medium-High（并发会话时会关掉别人的浏览器）· **Category:** Reliability · **复现状态:** 代码路径已核对；本次现场 0 命中

- `kill_all_browsers()`（`managed_processes.rs:598`）→ `find_orphaned_browser_roots()`（`:698`）/ `find_unique_pulsar_browser_processes()`（`:973`），全机匹配 `PULSAR_CHROME` 环境标记或命令行含 `browser4` 的 Chrome/Edge；e2e 的 pre/post sweep 每个阶段都调用（`managed_processes.rs:414-423`、`:443-448`）。
- 本次两次扫描均报 `Found 0 browser process(es), killed 0`；且 `~/.browser4/workspaces/*/app-data/tmp/browser4-pereg/context/**` 今天没有任何 `PULSAR_CHROME` profile 写入 → 今日无 Browser4 浏览器在跑，未造成损失。
- 但若邻居会话正在用浏览器，`taskkill /F /T` 会连同标签页与未保存页面一起关闭。

**候选修复**：给浏览器进程加"本 checkout/本会话"归属（marker 文件记录 checkout 根或 state 目录），e2e 只清扫自己的；或与 I1 共用同一个显式开关。

---

## I4：两个 checkout 的 dev 后端都解析到 8282

**Severity:** Medium（端口隔离在 state 陈旧时失效）· **Category:** Reliability · **复现状态:** 已核对两份 state

- `daemon.rs:1441` `pub const DEV_SERVER_PORT_START: u16 = 8282;`，范围 8282–8313；注释（`:1596`）说占用时会顺延到 8283、不接管邻居；
- 实测两份 `cli-state.json` 都写 8282：
  - 本 checkout：`browser4\workspaces\Browser4-4.16-02e52167\cli-state.json` → `baseUrl: http://localhost:8282`（11-12 创建）；
  - 邻居：`…\Browser4-4.16-feat-adc2c6c2\cli-state.json` → `baseUrl: http://localhost:8282`（11-14 00:04，`stale: true`）。
- 结论：端口顺延逻辑只在新启动时生效；**陈旧 state 被 sweep 直接采信**时就固定指向 8282。

**候选修复**：端口归属以"当前监听者 + 本 checkout 的 jar 路径"为准；陈旧 state 只做提示；或按 checkout 根哈希派生固定端口段，避免 8282 争用。

---

## I5：sweep 只打印计数，事后无法审计

**Severity:** Medium · **Category:** 可观测性 · **复现状态:** 本次实测

- 阶段输出只有 `Stopped {n}, already stopped {n}, forced {n}, still running {n}`（`managed_processes.rs:429-436`）与 `Found {n} browser process(es), killed {n}`（`:418-423`、`:443-448`）；
- 不打印 pid、命令行、jar 路径、所属 checkout。本次我的 e2e 输出用 `Select-Object -Last 30` 保留尾部，**pre-sweep 段整段丢失**，只能靠邻居侧文件时间线反推；
- 建议每个被终止的 pid 打一行结构化日志（`[sweep] kill pid=… port=… jar=… cmd=…`），并写入 e2e 的 timing/report 产物。

---

## 2. 复现步骤（最小）

1. 机器上准备两个 checkout（或一个 checkout + 一个 `b4w` 起的 dev 后端），各自 `b4w` 起后端并跑一个 `agent run` 长任务；
2. 在另一个 checkout 执行**任意** e2e 场景（例如完全 mock 的 `cargo test --test e2e -- --scenario=test_e2e_agent_run_mock_*`）；
3. 观察另一个后端的进程消失：其 `app-data/logs/agent/<ts>/…` 在 sweep 时刻断流，`server-plugins-fingerprint.json` 随后被重写（自动重启），新任务从新的 `<ts>` 目录开始。

---

## 3. 临时规避（本次之后本会话采用）

- 同机跑 e2e 前先确认没有别的 Browser4 后端（`Get-NetTCPConnection -State Listen` 查 8282–8313 / 18182 + `Get-CimInstance Win32_Process` 过滤 `Browser4.jar`）；
- **注意：`BROWSER4_CLI_STATE_DIR=<temp>` 隔离不足以自保**——`find_browser4_server_processes()` 的全局扫描不受 state 目录影响（I1）；
- 最安全的本地做法是给 sweep 加显式开关后再跑（未实施，见 §5）；在此之前建议只在独占机器/CI 上跑 e2e。

---

## 4. 测试建议（修复时同步补）

| 用例 | 断言 |
|---|---|
| 归属判定单元测试 | 给定 registry 里 pid 指向"另一个 checkout 的 `Browser4.jar`"（jar 路径不同），sweep 不产生 kill |
| 端口列表 | `managed_server_ports_for_sweep()` 默认不包含 8182/18182；仅 `kill-all` 模式包含 |
| 显式开关 | 未设置 `BROWSER4_E2E_SWEEP_OTHERS` 时，pre/post sweep 不调用全局 JVM 扫描 |
| 审计输出 | 杀掉一个假 pid 时，输出包含 `pid=`/`cmd=`；e2e report 里可回查 |
| 浏览器清扫 | 带 `PULSAR_CHROME` 标记但不属于本 checkout 的进程不被 kill |
| 回归 | 现有 `managed_processes` 单测（如 `force_kill_browser4_server_processes_on_ports` 相关）继续通过 |

---

## 5. 置信度与证据缺口

- **高**：I1（全局 JVM 扫描代码路径 + 邻居后端在 sweep 时刻断流 + 00:32:39 重启痕迹）、I2（sweep 端口含 8282 的 notify 记录）、I4（两份 `cli-state.json`）；
- **中高**：I3（代码路径明确，本次 0 命中，未造成浏览器侧损失）；
- **证据缺口**：pre-sweep 自身的计数与 kill 明细未捕获（e2e 输出被尾部截断；I5 即为此提出）；被 kill 的 pid 只能由邻居侧重启痕迹反推；
- **未覆盖**：Linux/macOS 上等价的 `pgrep -f 'browser4\.jar|…'`（`:1159-1167`）同样是全局扫描，未在本机实测；CI 单后端场景下该缺陷不可见，无法靠 CI 回归。

---

## 6. 关联

- 同源安全实现：`managed_processes.rs:1247-1255`（`force_kill_browser4_server_processes_on_ports`，注释已写明"neighbouring workspaces"约束）；
- e2e 入口：`cli/browser4-cli/tests/e2e/mod.rs:6976-6992`（pre-sweep）、结束清理；
- dev 端口：`cli/browser4-cli/src/daemon.rs:1441`、`:1594-1600`（顺延策略）、`:13458-13542`（相关单测）；
- 本次受影响任务：`9c97e8c0-…`（邻居 checkout，00:30:28 提交）→ 新任务 `b059b770-…`（00:33:37）。

---

## 7. 修复记录（2026-11-14，`4.16.x`）

### 7.1 核心思路：从"识别"改为"归属"

旧 sweep 用 `command_line_matches_browser4_server()`（"这是不是 Browser4 后端"）决定杀谁，同机每个 checkout
的后端都满足，所以它必然跨工作区。新 sweep 用它判断"**这是不是本 checkout 启动的后端**"：

```
owned(cmd) = command_line_matches_browser4_server(cmd)      // 还是后端
          && command_line_mentions_path(cmd, checkout_root) // 且命令行带本 checkout 根
```

检查点实证（本机正在运行的邻居后端 `pid 75556`）：

```
"D:\workspace\Browser4\Browser4-4.16-feat\browser4-apps\browser4-bundle\target\runtime-bundle\
 _work\browser4-bundle-runtime-windows-x64\browser4-bundle-runtime-windows-x64\runtime\bin\java.exe"
 @C:\Users\pereg\AppData\Local\Temp\browser4-argfile-51512-…\java-args.txt
```

bundle 后端在 Windows 上走 `@argfile` 且启动后 argfile 已被删除（`daemon.rs:9852`），
`-Dbrowser4.agent.workspace` 与 classpath 都看不见 —— 但**它自己的 JRE 就在 checkout 里**，
这条路径永远可见。所以归属判定 = `-Dbrowser4.agent.workspace=<root>`（未走 argfile 时）
**或** 命令行里出现 checkout 根。

**关键细节**：`D:/workspace/Browser4/Browser4-4.16` 是邻居 `Browser4-4.16-feat` 的**前缀**，
纯子串匹配会把邻居判成自己 —— 这正是事故的直接成因。`command_line_mentions_path()` 要求匹配后的
下一个字符不能是路径名可续字符（字母数字、`-`、`_`、`.`、`~`），因此 `/`、引号、空格、行尾才算命中。

### 7.2 逐条修复

| 编号 | 修复 | 位置 |
|---|---|---|
| I1 | e2e 的 4 处 sweep 改调新的 `stop_owned_browser4_servers_forcibly()`：候选 = 命令行带本 checkout 根的 JVM ∪ 本 workspace registry 里的 pid ∪ **通过归属校验**的 registry 端口监听者；不再调用全局 `find_browser4_server_processes()` | `managed_processes.rs`、`tests/e2e/mod.rs` |
| I2 | registry 端口只在归属校验通过后才停（端口被邻居接管 → 不动）；`cli-state.json` 的 URL 仅在**确实存在 state 文件且未 `stale`** 时才作为 `close_all_sessions` 目标，且仍要过 `owned_base_url()` 归属校验 | `notify_base_url_candidates()`、`owned_base_url()` |
| I3 | 浏览器清扫改为归属：`kill_browsers_launched_by(本次杀掉的 server pid)` + 仅"本 workspace 自己的 `app-data/tmp` 下的孤儿"；场景 `test_open_recovery_after_browser_kill` 从 `kill_all_browsers()` 改为新的 `kill_browsers_of_owned_servers()`，并断言必须真的杀到 1 个 | `kill_owned_orphaned_browser_processes()`、`scenarios/browser.rs` |
| I4 | 陈旧/缺失 state 不再影响终止决策（见 I2）；归属以"命令行 + registry + 端口监听者"为准，不再以 `base_url` 为准 | 同上 |
| I5 | 每个被杀进程打印一行 `[sweep] kill pid=… source={checkout,registry-port,orphan-browser} port=… cmd=…`（命令行截断 200 字符） | `force_stop_owned_browser4_server_processes()` |

旧行为可按需恢复：`BROWSER4_SWEEP_ALL_WORKSPACES=1`（`1/true/TRUE/yes/YES/on/ON`），
未设置或写错都不会放宽清扫范围（有单测）。未从 checkout 运行时（安装版，`dev_workspace_root()` 为 `None`）
仍走全局 sweep —— 安装环境只有一个后端，等于安全。`kill-all` / 生产版 `stop` 语义不变，仍是全局锤子。

### 7.3 验证

| 项 | 命令 | 结果 |
|---|---|---|
| 单测 | `cargo test --bin browser4-cli managed_processes` | **53 passed**（新增 8 个：兄弟 checkout 前缀陷阱、引号/反斜杠归一化、Surefire fork 不算后端、邻居端口被接管不杀、陈旧 state 不 notify、env 开关拼写、审计截断） |
| 真实后端 e2e | `cargo test --test e2e -- --scenario=test_session_lifecycle` | 1 passed；pre-sweep `Stopped 0`；收尾 `[sweep] kill pid=72588 source=checkout`（**证明"未注册的遗留后端"也能按命令行归属抓到并停掉**） |
| 浏览器清扫 e2e | `cargo test --test e2e -- --scenario=test_open_recovery_after_browser_kill --level EXTENDED` | 1 passed；`killed browser PIDs: [65168]`（归属自查成功），收尾 `[sweep] kill pid=76104 source=checkout` |
| 不误杀（现场） | 两次 e2e 前后对比 | 邻居 checkout 后端 `pid 75556`（命令行为 `Browser4-4.16-feat`）**始终存活**；本机无 `PULSAR_CHROME` 残留浏览器 |

修复前的同一命令必然先把 `75556` 杀掉（I1 的全局扫描），所以上表第 4 行就是本 issue 的回归证据。

### 7.4 未覆盖 / 后续

- 归需要命令行里能看到 checkout 根：若某天 dev 模式改用装在 checkout 之外的 JRE，并同时把 classpath 折进
  已删除的 argfile，则该后端不可归属 —— 方向是"宁可少杀"（可由 `BROWSER4_SWEEP_ALL_WORKSPACES=1` 兜底）；
- 嵌套 worktree（`<checkout>/.claude/worktrees/x`）的命令行含 `checkout 根 + /`，按当前实现算作本 checkout，
  如需严格隔离再单独收紧；
- `stop` 的 `kill_orphaned_browser_processes()` 仍是全局孤儿扫描（只杀父进程已死的浏览器），本次未改。

