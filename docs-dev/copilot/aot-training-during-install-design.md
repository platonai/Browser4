# AOT 训练前置到 install 阶段 + install 流程并行化 — 设计文档

- 状态: **已实施**（方案 A 全部落地 + 失败恢复增强：训练 PID 记录 + 死亡检测即时重训 + stale 兜底）
- 未实施: 第 4 节可选优化（分片下载 / 流式 sha256 / 尾部 join），留待后续迭代
- 关联改动: AOT 缓存后台训练（`daemon.rs` ensure_aot_cache_trained 非阻塞化）、缓存持久化到 state dir、`browser4.aot.training` 训练模式（McpHttpServerConfiguration 条件注解）
- 目标版本: 4.14.x 之后（下一迭代）

## 1. 背景与目标

上一轮已落地：首次 `open` **不再阻塞**在 AOT 训练上（后台训练 + 缓存跨卸载持久化）。
仍存在的空窗：**新装/升级后的首次 `open` 一定没有 AOT 缓存**（训练只能由 open 触发，
用户在 open 时才刚开始训练）。本设计把训练触发点**前置到 install/upgrade 命令**，
并评估 install 流程整体并行化的空间。

目标：
1. install/upgrade 完成后，训练在后台尽早开始；用户稍后首次 `open` 大概率直接命中缓存（秒级启动）。
2. install 命令自身耗时**不增加**（训练非阻塞 spawn）。
3. 顺带量化并设计 install 流程其余可并行环节（分片下载、流式 sha256、尾部步骤并行）。

## 2. 现状分析

### 2.1 install 流程（`handle_install` main.rs:14293 / `handle_upgrade` main.rs:16188）

```
install_browser4_runtime()          daemon.rs:3127
  ├─ resolve latest tag             :3139   (~0.5–2s)
  ├─ 磁盘空间检查                   :3183   (<1s)
  ├─ mirror 测速/选择               :3274   (1–2s)
  ├─ 下载 135MB                     :3332   (本机 ~8–15s @17MB/s; download_file_blocking daemon.rs:2167, 流式 8KB 块)
  ├─ sha256 校验                    :3377   (1–2s; compute_file_sha256 daemon.rs:2720 — 下载后全文件二次读盘)
  ├─ 解压                           :3414   (5–15s)
  └─ commit（挪入版本目录+metadata）:3439   (<1s)
format_install_output               main.rs:14300
ensure_chrome_available()           main.rs:14310   (~1s, 仅新装)
sync_skills_for_runtime()           main.rs:14330   (2–5s)
sync_skills_to_agents_dir()         main.rs:14344   (1–2s)
```

本机实测 install 总时长 ~20–40s；AOT 训练（完整 Spring Boot 启动 + AOTCache 写出）实测 60–180s。

### 2.2 现有 AOT 机制（上一轮已落地，本设计直接复用）

- `ensure_aot_cache_trained(runtime)` daemon.rs:4698（当前 `fn`，私有）：
  - 幂等：缓存有效（`app.aot` + key 匹配）→ 直接返回；`training.lock` 新鲜（<10min）→ "训练进行中"返回。
  - 无效 → 删旧缓存 → 原子抢 `training.lock`（`create_new`）→ `spawn_aot_training`（daemon.rs:4795 附近）。
- `spawn_aot_training`：detached JVM、`--server.port=0`、`-Xmx1G`、`-Dbrowser4.aot.training=true`（训练模式跳过 MCP/Chrome）、输出进 `aot-cache/training.log`、Windows `CREATE_BELOW_NORMAL_PRIORITY_CLASS`。
- 缓存位于 state dir（`~/.browser4/aot-cache`，跨卸载保留），key = tag + jar 列表 + JVM flags。
- CI 关闭开关：`BROWSER4_CLI_DISABLE_AOT_CACHE=1`（ensure 入口已检查）。

### 2.3 关键约束

- 训练**强依赖解压产物**（`lib_dir` + JDK），无法与下载/解压并行；但训练是独立进程，**可与 install 所有尾部步骤及 install 返回后的空闲时间并行**。

## 3. 方案 A（主方案）：install/upgrade 尾部后台 spawn 训练

### 3.1 嵌入点

`install_browser4_runtime` 返回后（此时 lib/JDK 已 commit 就位）**立即**调用 `ensure_aot_cache_trained`，
使其与后续 `ensure_chrome_available` / skills 同步并行执行：

```rust
// main.rs handle_install, ~line 14299 之后:
let runtime = install_browser4_runtime(tag, force).await?;
ensure_aot_cache_trained(&runtime);   // NEW: 后台训练前置，非阻塞，失败非致命
for line in format_install_output(&runtime) { cli_println!("{}", line); }

// main.rs handle_upgrade, ~line 16206 之后: 同样插入
let mut runtime = install_browser4_runtime(tag, force).await?;
ensure_aot_cache_trained(&runtime);   // NEW
```

### 3.2 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `daemon.rs:4698` | `fn ensure_aot_cache_trained` → `pub(crate) fn ensure_aot_cache_trained` |
| 2 | `daemon.rs` `spawn_aot_training` | 提示文案参数化：install 场景消息改为 `Training JVM AOT cache in the background (one-time, only on first launch) ...`（去掉 "the server will start without AOT acceleration this time" 的 server 语境，open 场景保留）；或拆两个 eprintln 分支 |
| 3 | `main.rs` `handle_install` / `handle_upgrade` | 上述两处插入调用（各 1 行） |

总计 ~6 行有效改动。**不引入任何新机制**：幂等/锁/低优先级/detached/非致命/CI 开关全部复用。

### 3.3 行为与收益（本机量化）

- install 总时长不变（spawn ~0.1s）。
- 训练在 install 尾部即开始（与 Chrome 检查、skills 同步并行），install 返回后 ~1–3min 完成。
- "装完稍后才用"（看文档/配 key/装插件——主流路径）的用户：**首次 `open` 直接命中缓存，秒级启动**。
- "装完立即 open" 的用户：与现状一致（open 不阻塞，训练继续跑），无回退。
- 与 open 路径（`resolve_server_launch_spec` → ensure）**天然去重**：marker 锁保证只训一次，
  先到者胜出，后到者打印一条 "already in progress" 后正常继续。
- 升级场景：新版本 jar 列表变化 → key 变化 → 自动重训（现有逻辑）。

### 3.4 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| install 退出后训练进程残留 1–3min | CPU 占用（低优先级）+ 磁盘 +103MB | 与现状 open 触发完全同构（已被接受）；CI 可 `BROWSER4_CLI_DISABLE_AOT_CACHE=1` 关闭 |
| 训练失败 | 无（非致命，仅 training.log 记录） | 现有逻辑；下次 open 自动重试 |
| 并发 install/upgrade/open | 双训练 | `RuntimeInstallLock` + `training.lock` 原子锁 |
| open 自动安装路径重复触发 | 消息噪音 | 嵌入点放在 handle_install/upgrade（命令层），不放在 `install_browser4_runtime` 库函数内部 |
| 验收脚本（test-production.ps1） | 无 | 验收跑已发布 CLI，无此逻辑；新 CLI 下 `BROWSER4_CLI_DISABLE_AOT_CACHE` 可兜底 |
| 缓存目录权限/只读（如企业锁定的 ~/.browser4） | 无 | ensure 内部全部非致命，`create_dir_all` 失败即跳过 |

### 3.5 测试清单

1. **Rust 单测**：无新逻辑函数，主要为编译级验证；补充一条 `ensure_aot_cache_trained` 可见性/幂等调用冒烟测试可选。
2. **install 实跑冒烟**（手动，本机）：
   - 全新 `BROWSER4_CLI_STATE_DIR` 沙箱 → `install --tag v4.14.0-rc.1` → 断言：install 耗时无明显增加；`state/aot-cache/training.lock` + `training.log` 出现；**install 返回后训练仍在跑**（进程存活）。
   - 等待训练完成（`app.aot` 出现）→ `open` → 断言：无训练消息、服务器就绪、`app.aot.key` 补写。
   - 重复 install（快速路径 `reused_existing`）→ 断言：不重复训练（幂等）。
   - 升级模拟（换 tag/改 jar）→ 断言：key 变化 → 自动重训。
   - `BROWSER4_CLI_DISABLE_AOT_CACHE=1 install` → 断言：不 spawn 训练。
3. **回归**：`cargo test --bin browser4-cli` 全量；`mvn -pl browser4-rest -am package`（未改 Kotlin，编译级即可）。
4. **验收脚本回归**（可选，重）：`test-production.ps1 -Version <next> -Stress`。

### 3.6 回滚

单文件、~6 行改动，删除两处调用 + 还原可见性即可；无 schema/状态迁移。

## 4. 可选优化（install 并行化全景）

按收益/成本排序；均独立于方案 A，可单独决策。

### 4.1 分片并行下载（慢网专项）

- 现状：`download_file_blocking`（daemon.rs:2167）单连接流式下载，已有 Content-Length 完整性校验 + 进度报告。
- 设计：`download_file_parallel(url, target, chunks: 4)`：
  - HEAD 或首片响应取 `Content-Length` + `Accept-Ranges` 探测；不支持 Range 的 mirror（部分 CDN）回退单连接。
  - tokio/线程池并发 N 个 `Range: bytes=a-b` 请求，各写独立临时分片文件（`.part0..N`），全部完成后按序拼接，删除分片。
  - 复用现有完整性校验（字节数 = Content-Length）+ 现有 `compute_file_sha256` 兜底（校验和缓存比对逻辑不变）。
  - 失败策略：任一分片失败 → 整体回退单连接下载（简单可靠，不搞部分重试）。
- 收益：本机 17MB/s 仅省 ~4–6s；慢网（1–5MB/s）省 2–5× 下载时间。
- 成本：~100 行 + 并发基建；风险低（回退路径保底）。

### 4.2 sha256 流式校验

- 现状：下载（流式）→ 落盘 → `compute_file_sha256` 全文件二次读盘（1–2s）。
- 设计：`download_file_blocking` 的 8KB 读循环里直接喂 `Sha256`（daemon.rs:2225 loop 内加 `hasher.update(&buf[..n])`），`DownloadedFile` 携带 checksum；调用点（daemon.rs:3377）改用携带值，跳过二次读盘。
- 收益：省 1–2s + 一次全文件 IO；成本 ~10 行。**可与 4.1 叠加**（分片场景需在拼接后校验或各片分别喂 hasher）。

### 4.3 install 尾部步骤并行

- 现状：`ensure_chrome_available` → `sync_skills_for_runtime` → `sync_skills_to_agents_dir` 串行（~4–8s）。
- 设计：方案 A 的 ensure 已与三者并行；三者之间再用 `tokio::join!` 并行（chrome 检查与 skills 同步无依赖）。
- 收益：再省 ~1–3s；成本 ~5 行。低优先。

### 4.4 明确不做

| 项 | 原因 |
|---|---|
| 训练与下载/解压并行 | 强依赖解压产物，物理不可行 |
| 解压多线程 | 磁盘 IO 瓶颈，收益不确定，引入依赖 |
| mirror 测速与下载并行 | 选择依赖测速结果，竞态风险 > 收益 |
| install 前台阻塞训练 | install +1–3min，体验倒退（明确否决） |

## 5. 验收标准（方案 A）

- [ ] install/upgrade 耗时与改动前相当（±2s 内，不含训练）
- [ ] install 后 `~/.browser4/aot-cache/` 出现 training.lock + training.log（后台训练启动）
- [ ] 训练完成后首次 `open` 不出现训练消息，服务器正常就绪
- [ ] 快速路径重复 install 不重复训练
- [ ] `BROWSER4_CLI_DISABLE_AOT_CACHE=1` 下 install 不 spawn 训练
- [ ] `cargo test --bin browser4-cli` 全量通过

## 6. 建议实施顺序

1. 方案 A（~6 行）— 与上一轮改动形成闭环：训练触发点 = install 提前跑 + open 兜底；open 永不阻塞；缓存跨卸载持久化。
2. 4.2 流式 sha256（~10 行，顺带）。
3. 4.3 尾部 join（~5 行，顺带）。
4. 4.1 分片下载（~100 行）— 作为慢网专项独立评审，可后置到 4.15。
