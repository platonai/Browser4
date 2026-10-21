# CLI 发起会话（Session）管理审核报告

审核日期：2026-07-22（基于当前仓库代码）
审核范围：`browser4-cli`（Rust）发起/复用/列出/关闭会话的完整链路，以及 `browser4-rest` 端
`PulsarSessionManager` 对会话的生命周期管理。

> **修复状态（2026-07-22）**：P1、P2、P4、P5（原子写部分）、P6、P7、P8 已修复；
> **第二轮重构（2026-07-22）**：P3 已根治（getSession 读写分离）、P7 已根治（注册表持久化）、
> status 魔法字符串 → SessionStatus 枚举、CLI 遗留字段统一走 SessionKind 并拆分大函数。
> 剩余：P5 的进程间锁部分、P9 的浏览器实测待后续。
> 修复详情见文末「5. 修复记录」与「6. 重构记录」。

## 1. 会话管理链路总览

```
CLI 状态文件（~/.browser4/cli-state.json 或 sessions/<name>.json）
   │  open/goto/attach/swarm 写入 sessionId（后端返回的 UUID）
   ▼
MCP 工具调用（open_session / close_session / list_sessions / check_session_ready …）
   ▼
browser4-rest  MCPToolController ──► PulsarSessionManager（纯内存 ConcurrentHashMap）
   ▼
AgenticSession（browser4-agentic / core）──► PulsarBrowser / PulsarWebDriver（CDP）
```

关键事实：

- CLI 侧会话状态全部持久化在磁盘 JSON；后端 `PulsarSessionManager.sessions` 与
  `displayNameToSessionId` **纯内存、无持久化**。
- 后端为显示名（如 `-s team-a` 或 `default`）分配稳定 UUID 并记录映射；CLI 保存的是该 UUID。
- 会话“健康”判定：`getSession` → `resolveHealthySession` → `checkHealthyBlocking`，
  不健康且 `kind.ownsBrowser` 的会话会被**静默重建**（同 UUID，新浏览器实例）。

## 2. 主要问题（按严重度排序）

### P1. 后端会话无空闲回收、无容量上限（内存/磁盘泄漏风险）

- 位置：`PulsarSessionManager.sessions` / `displayNameToSessionId`
  （browser4-rest/.../session/PulsarSessionManager.kt）
- 现状：两个 `ConcurrentHashMap` 只增不减；`lastAccessedAt` 被记录但**从未用于驱逐**；
  全模块无 `@Scheduled` / 定时清理（仅扩展 WebSocket pending 连接在下次创建时有 2 分钟
  TTL 顺带清理）。`deleteAllSessions()`/`shutdown()` 是仅有的批量释放路径。
- 触发：脚本/CI 用动态名反复 `-s run-<n>` 创建会话且不 `close`；或 `close` 失败被 CLI
  吞掉错误（见 P4）留下孤儿条目。
- 影响：每个 `ManagedSession` 持有 `AgenticSession`（惰性绑浏览器，但 `getOrCreateBoundDriver`
  一旦调用即绑定 driver），SEQUENTIAL 模式还会在磁盘生成浏览器 profile；
  条目累积 → 内存与磁盘双泄漏，直到 `close-all`/`kill-all`/后端重启。

### P2. `list_sessions` 状态不经过健康检查，“Active”不可靠

- 位置：`MCPToolController.handleListSessions()` 直接返回内存 `s.status`；
  后台没有主动健康巡检。
- 影响：浏览器进程崩溃或 driver 失效后，条目状态仍为 `active`。CLI `session list` 显示
  “Active”、`open` 判定为可复用（`find_reusable_persisted_session_id` → `session_is_active`），
  实际导航时才会失败并走重试+重建路径。用户看到的状态与真实可用性可能长期不一致。
- CLI 侧只有 `list_sessions` 查不到该 session 时才判定 Stale（后端重启场景），
  对“条目在但浏览器已死”的场景无效。

### P3. 每次工具调用都执行阻塞健康检查，且可能静默重建会话

- 位置：`PulsarSessionManager.getSession()` → `resolveHealthySession()` →
  `checkHealthyBlocking()`（`runBlocking`），失败路径 `recreateUnhealthySession()`
  在 `sessions.compute()` 内**再跑一次** `checkHealthyBlocking`。
- 影响：
  1. 每个 MCP 工具调用（点击、输入、导航…）都附带一次阻塞健康检查，可能含 CDP 往返；
  2. 会话/浏览器不健康时，下一次工具调用会静默拉起**新 Chrome 实例**。sessionId 不变，
     CLI 无感知，但 cookies/标签页/滚动位置等真实状态已丢失，用户可能误以为状态保留；
  3. `getSession("default")` 会走 `getOrCreateSession` —— 任何带 `sessionId=default`
     的调用都会隐式创建会话（CLI 传 UUID 所以不触发，但遗留/第三方客户端会）。

### P4. CLI `close` 吞掉后端关闭错误 → 孤儿会话

- 位置：`main.rs::handle_close`（`let _ = call_tool(... "close_session")`，注释
  “Ignore errors — session might already be closed”），`--fresh` 路径同样忽略。
- 影响：若 `close_session` 因超时/后端瞬时故障失败，CLI 仍清空本地状态并提示
  “Session closed. Browser terminated.”，而后端会话与浏览器继续存活且不再被任何
  CLI 状态引用 → 孤儿。只有 `close-all`/`kill-all`/重启能清理。用户无法从输出区分
  是否真的关闭成功。

### P5. CLI 状态文件无并发保护（TOCTOU / 覆盖写）

- 位置：`state.rs::write_state`（直接 `fs::write`，无锁）、`check_unnamed_slot_free`
  （读-判断-写存在竞态窗口）。
- 影响：两个并发 `open` 都能通过 unnamed 槽检查，先后创建两个后端会话，后写者覆盖
  `cli-state.json`，先创建的后端会话沦为孤儿；`persist_active_selector` 等读-改-写
  也会互相覆盖。`SessionRegistry` 文档自述“not shared between concurrent CLI processes”。
- 缓解方向：写前原子 rename（先写临时文件再 `fs::rename`）+ 按会话加锁（如 `~/.browser4/.lock`）。

### P6. `SessionRegistry` 是死代码，两套并存实现有漂移风险

- 位置：`session_registry.rs`（已实现并导出到 `lib.rs`），但 `main.rs` 的
  `handle_list` / `count_tracked_sessions` / `close-all` 仍各自重新扫描目录。
- 影响：文档声称 registry “replaces the previous pattern”，实际并未替换；未来改列表
  逻辑需要同步多处，容易不一致（例如 `handle_list` 对默认会话有
  `backend_knows_session` 过滤，registry 没有该逻辑）。
- 建议：要么接入 registry（list/close-all/switch 统一走它），要么删除并保留注释。

### P7. 后端重启 = 全部 CLI 会话失效（无持久化设计）

- 后端 `sessions`/`displayNameToSessionId` 纯内存：重启后 DEFAULT 与命名会话的
  UUID 映射全部丢失。CLI 侧有恢复闭环（下次 `open` 通过 `list_sessions` 发现缺失 →
  `invalidate_session` → 重建），但：
  - 磁盘上 `sessions/<name>.json` 会保留旧 UUID 条目，`session list` 中显示为 Stale，
    需用户手动 `close -s <name>` 清理，不会自动剪除；
  - 若重启前后端会话仍在（如 JVM 崩溃恢复场景之外的人工重启），重启即释放，无泄漏，
    但任何“会话继续”的预期都会被打破。
- 属于设计取舍，建议至少在文档中明示“后端重启会丢失全部会话”，并考虑
  `displayNameToSessionId` 持久化（如 `~/.browser4/backend-sessions.json`）以提升
  命名会话跨重启的稳定性。

### P8. `deleteSession` 与 `getSession` 不对称：显示名无法关闭

- 位置：`PulsarSessionManager.deleteSession()` 直接 `sessions.remove(sessionId)`，
  不做 `displayNameToSessionId` 解析；而 `getSession()` 会解析。
- 影响：CLI 正常路径保存的是 UUID，`close`/`close-all` 不受影响；但直接通过 MCP
  以显示名调用 `close_session`（如 `close_session {sessionId:"team-a"}`）会返回
  “Session not found”并遗留会话。属 API 不一致，建议 `deleteSession` 同样先解析显示名。

### P9（待验证）. 关闭附加会话时对“外部浏览器”的 close 语义

- `deleteSession()` 对 CDP/扩展附加会话也会调用 `pulsarSession.close()` 与
  `browserManager.closeBrowser(browser)`（代码内有 TODO 承认冗余 close）。
  `PulsarBrowser` 来自外部依赖（`ai.platon.pulsar.chrome`），本仓库无法确认
  无 launcher 的浏览器 close 是否只断开 CDP 而不杀进程。CLI 文案与测试都假设
  “外部浏览器保持运行”，建议在真实 Chrome 上验证一次 `attach --cdp` 后 `close`
  的行为。

## 3. 做得好的设计（值得保持）

- **SessionKind 显式分类**（Browser4Launched / CdpAttached / ExtensionAttached / Swarm）
  驱动生命周期决策：attached 会话绝不静默重建为 Browser4 启动的会话，避免
  “列表显示 Extension 实际驱动另一个浏览器”的错位。
- **CLI 侧 stale 检测闭环**：`open`/`goto` 前先 `list_sessions` 比对；区分
  “backend 不可达”（保留 attached 状态、仅普通会话 invalidate）与“session 不存在”。
- **导航失败重试**：关闭失败会话 → invalidate → 按原 capability 重建 → 重试导航，
  并有 `BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS` 提示。
- **会话内串行化**：`ManagedSession.mutex`（`withLock`）保证同会话 WebDriver 操作
  不并发，跨会话可并行。
- **`close-all` 与 `kill-all` 语义分离**（保留后端进程 vs 完全关闭）。
- **快照 best-effort**：post-command snapshot 不阻塞命令返回。

## 4. 改进建议（按优先级）

1. **为后端会话增加空闲回收**：利用已有的 `lastAccessedAt`，定时（如每 5 分钟）
   将空闲超过阈值（如 30 分钟）且非 attached/extension/swarm 的会话 `deleteSession`；
   CLI 下次使用时通过现有 stale 闭环自动重建，对用户体验几乎无损。
2. **`list_sessions` 增加健康状态**：对每个条目做轻量健康检查（或懒标记
   “unverified”），让 `session list` 的 Active/Stale 可信。
3. **CLI `close` 不吞错**：`close_session` 失败时至少 stderr 警告“后端关闭失败，
   请重试或 close-all”，并将后端条目列入孤儿清单。
4. **状态文件原子写 + 进程间锁**：临时文件 + `fs::rename`，锁文件防并发 open 竞态。
5. **消除重复实现**：接入或删除 `SessionRegistry`；统一 `list`/`count`/`close-all` 逻辑。
6. **`deleteSession` 支持显示名解析**，与 `getSession` 对齐。
7. **文档明示会话生命周期**：后端重启丢失会话、`-s` 命名会话需显式 `close -s` 清理、
   `--fresh` 与 `close` 的失败语义。

## 5. 修复记录（2026-07-22）

| 问题 | 修复内容 | 文件 |
|---|---|---|
| P1 空闲回收 | 新增 `reapIdleSessions()`：空闲超过 **4 小时**（`DEFAULT_IDLE_SESSION_TIMEOUT`，构造参数可覆盖）的非默认、非 SWARM、非 attached 会话被回收；默认会话（`displayNameToSessionId[DEFAULT]`）永不回收；内部 daemon 线程每 30 分钟扫描，`shutdown()` 时停止；顺带修复 `createAttachedSession`/`createExtensionAttachedSession` 未设置 `kind` 的问题（CDP_ATTACHED / EXTENSION_ATTACHED），使 attached 会话不被回收且不会被静默重建 | `PulsarSessionManager.kt` |
| P2 健康状态 | `list_sessions` 每个条目新增 `healthy` 字段（真实健康检查，失败保守报 false）；CLI `BackendSessionRecord` 解析 `healthy`，`session_is_active_in_records`/`list_session_status` 在 `healthy=false` 时判为 Stale/Refresh；无该字段的旧后端与字符串数组响应保持旧行为 | `MCPToolController.kt`、`main.rs` |
| P4 close 告警 | 新增 `warn_if_session_close_failed()`：`close`、`--fresh`、导航失败重试三处静默 close 均改为失败时 stderr 告警（孤儿会话由 P1 的 4h 回收兜底） | `main.rs` |
| P5 原子写 | 新增 `atomic_write()`（同目录唯一临时文件 + `fs::rename`），应用于会话状态、loop 状态、async-tasks 三处写入；进程间锁未做（见下） | `state.rs` |
| P6 死代码 | 删除未使用的 `session_registry.rs` 及其模块声明 | `session_registry.rs`（删除）、`lib.rs` |
| P7 文档 | `PulsarSessionManager` KDoc 补充会话生命周期说明（纯内存、重启丢失、4h 回收、默认/SWARM/attached 不回收） | `PulsarSessionManager.kt` |
| P8 显示名删除 | `deleteSession()` 先经 `displayNameToSessionId` 解析（与 `getSession` 对齐），显示名与 UUID 均可关闭 | `PulsarSessionManager.kt` |

**新增测试**：`PulsarSessionManagerTest`（reapIdleSessions 仅回收空闲命名会话、默认/SWARM/刚访问保留、attached 不回收；deleteSession 按显示名删除）、`MCPToolControllerTest`（list 的 healthy true/false）、`main.rs`（healthy=false 显示 Stale/Refresh、healthy 缺失向后兼容）。

**保留项**：P5 的跨进程锁（涉及所有 CLI 命令，建议单独一轮处理）；P9（需真实浏览器验证 `attach --cdp` 后 `close` 是否误杀外部浏览器——已通过 kind 修复降低误杀路径，但行为仍需实测）。

## 6. 重构记录（2026-07-22，第二轮）

| 重构 | 内容 | 文件 |
|---|---|---|
| SessionStatus 枚举 | `ManagedSession.status` 从魔法字符串改为 `SessionStatus` 枚举（ACTIVE/PAUSED/STOPPED/DISCONNECTED/UNHEALTHY），`wire` 保持小写协议格式（MCP/CLI 兼容），`fromWire` 容错解析；全部 `equals("active")` 改为枚举比较 | `SessionStatus.kt`（新增）、`ManagedSession.kt`、`PulsarSessionManager.kt`、`Models.kt` |
| getSession 读写分离（P3 根治） | `getSession()` 变**纯查询**（解析显示名→查 map→返回，不创建、不健康检查、不重建）；新增 `getOrRecoverSession()` 保留完整旧语义（DEFAULT 按需创建 + 健康检查 + 不健康会话同 UUID 重建）；执行路径（工具调度/deleteSessionData/webdb/html_snapshot）改用 `getOrRecoverSession`，只读路径（check_session_ready）保持 `getSession`；extension 未连接状态改为创建时显式 STOPPED（不再依赖查询时健康检查推导） | `PulsarSessionManager.kt`、`MCPToolController.kt`、`WebDbToolExecutor.kt`、`HTMLSnapshotToolExecutor.kt` |
| 注册表持久化（P7 根治） | `displayNameToSessionId` 映射落盘（构造参数 `registryFile`，默认 `~/.browser4/session-registry.json`，测试传 null 不持久化）：启动加载（`loadSessionRegistry`），新增映射/删除映射时原子写（`persistSessionRegistry`，tmp+rename）；`resolveOrCreateDisplayNameMapping()` 用 `putIfAbsent` 避免 `computeIfAbsent` 在条目插入前快照的陷阱；后端重启后命名会话与 DEFAULT 保持同 UUID（浏览器状态仍丢失，但身份稳定） | `PulsarSessionManager.kt`、`CommandServiceConfig.kt` |
| CLI kind 统一 | `main.rs` 全部 `state.is_attached` / `state.attach_type` 读取改为 `state.kind`（`kind.is_attached()` / `kind == SessionKind::ExtensionAttached`）；attach 写入改为设 `kind`（ExtensionAttached/CdpAttached），`create_session` 重置为 `Browser4Launched`；`is_attached`/`attach_type` 降级为纯序列化兼容（write 时同步、读后迁移） | `main.rs` |
| 大函数拆分 | `get_or_create_navigation_session`（200+ 行）拆出 `create_fresh_session()`（新建会话+打印，4 处复用）与 `resolve_attached_session_id()`（attached 会话健康校验/自愈/报错，60 行）；行为完全不变 | `main.rs` |

**新增/更新测试**：`PulsarSessionManagerTest`（getSession 纯查询不创建/不恢复、getOrRecoverSession 恢复语义、注册表跨重启持久化、删除后映射清除）、`MCPToolControllerTest`/`MCPToolControllerE2ETest`/`MCPToolControllerExperienceE2ETest`（stub 切换 getOrRecoverSession）、`SwarmControllerTest`（枚举 status）、`main.rs`（kind 断言）。

**验证**：CLI `cargo test --bin browser4-cli` 1088 passed；browser4-rest 全量 266 tests passed；所有测试目标编译通过。
