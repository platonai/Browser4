# Agent 权限能力模型 — 执行计划

> 依据：[agent-permission-capability-model-design.md](agent-permission-capability-model-design.md)（设计稿 v0.1）
> 代码基线：`4.15.x @ 3283822f03`
> 状态：**A1–A7、B1–B5、C1–C2 已实现**（browser4-coding 530；browser4-agentic 全绿除既有
> `CliAgentPromptTest.hygieneFollowsMode`）；C3–C5 待在具备管理员权限的环境落地
> **剩余工作的计划以 [开发计划 v2](agent-permission-capability-model-dev-plan-v2.md) 为准**；
> 本文档保留 A–E 阶段定义与逐阶段实现记录。
> 原则：**先等价、后收紧**；每个阶段独立可回滚；判定只在 `Authorizer` 一处，强制只在进程边界一处。

## 1. 范围与不变式

范围内：`coding.*` 的文件、shell、runCode、工具暴露与审批语义；`skill.*` 的 `run`/`install` 等 EXEC 方法。

不在范围内：CLI 输出渲染、LLM 配置加载、`DevTaskPlanner` 计划逻辑、浏览器 URL/域名白名单。

贯穿全程的四条不变式：

1. **M0–M2 不得改变任何现有判定结果**。M0 的验收是"逐条一致"，不是"更安全了"。发现 B1–B4 绕过时，
   只能登记到计划，不能在重构里顺手修掉——否则无法证明等价，也无法回滚。
2. **工具实现内不再出现路径/命令判断**，全部经 `Authorizer`；这是收敛的唯一标准。
3. **字符串层永远不是安全声明**，只用于快失败、给模型可执行提示、省一次沙箱启动。
4. **沙箱启动失败 = 任务失败**，不得回退到无沙箱直跑。

## 2. 落地锚点（已核对的代码事实）

| 设计条目 | 现状代码 | 位置 |
|---|---|---|
| 方法级矩阵 | `AgentAccessPolicy.gateOf` / `requiredPresetOf`、`READ/BUILD/MUTATE/EXEC_METHODS` | `browser4-coding/.../AgentAccessPolicy.kt` L143-162、L267-272、L351-381 |
| 结构化拒绝 | `PermissionAudit.denied/approved/format/record`、`CODE_*` 码表 | `PermissionAudit.kt` L34-69、L86-136 |
| 唯一构造点 | `CodingComponents.shell/buildShell/fs` | `CodingComponents.kt` L33-103 |
| 文件路径判定 | `CodingAgentFileSystem.resolvePath` | L1378-1410（`normalize` 判边界，`toRealPath` 只用于凭据判定） |
| 三份默认清单 | `DEFAULT_PROTECTED_FILES/DIRS`、`DEFAULT_SENSITIVE_PATHS` | L125-164 |
| 凭据参数守卫 | `CodingAgentShell.argumentPathViolation` | L885-916，开关 `browser4.agent.shell.pathGuard` L79-80 |
| 工作区边界守卫 + B3/B4 | `workspaceBoundaryViolation`、`isLauncherReference`、`toolchainRoots` | L936-959、L966-971、L974-984 |
| 受保护目录守卫 | `protectedPathViolation` | L997-1007 |
| 环境全量继承 | `processBuilder.environment()` 直接继承 + `putAll` | L1548-1553 |
| runCode 无共享沙箱 | `CodeRunner.run` 私有 cwd + 超时，无环境构造 | `CodeRunner.kt` L75-161 |
| 工具注册表 | `toolSpec` 逐条声明、无 `effects` 字段 | `CodingToolExecutor.kt` L204 起（`permissions` L256） |
| 矩阵完备性测试 | `CodingToolMatrixCoverageTest` | `browser4-agentic/src/test/.../CodingToolMatrixCoverageTest.kt` |
| junction 直写真实检出 | `WorkspaceMirror` 用 junction/symlink 镜像模块 | `WorkspaceMirror.kt` L110-299 |

**关键冲突（必须先解决，否则 M0 会破坏模块写入）**：设计 §9 要求判定改用 `toRealPath()`，但
`WorkspaceMirror` 把模块目录做成 junction，realpath 落在 workspace 之外的真实检出。若直接替换，
`--fresh-workspace` 下所有模块写入都会被判越界。`CodingAgentFileSystem.isSensitiveLocation`
（L1423-1429）已经用"real 路径仍在 workspace 内才豁免"绕开了这个问题，但镜像目标不在 workspace 内。
→ 见 A3：capability 的读写 scope 必须**显式包含镜像源根**（由 `WorkspaceMirror` 的链接目标或
`WorkspaceManifest` 派生），realpath 判定才有意义。

## 3. 工作分解

### 阶段 A —— 判定收敛，行为等价（对应设计 M0）

| ID | 工作项 | 主要改动 | 产出 |
|---|---|---|---|
| A1 | 定义效果与能力数据结构 | 新增 `browser4-coding/.../Capability.kt`：`Effect`（封闭枚举：`fs.read`/`fs.write`/`fs.exec`/`net.connect`/`proc.spawn`/`secret.read`）、`Target`、`Scope`、`Limits`、`Capability`、`CapabilityId` | 可单测的数据模型，不接线 |
| A2 | 定义 `Authorizer` 纯函数 | 新增 `Authorizer.kt`：`Request(tool, effects, targets)`、`Decision{Allow, AllowWithBudget, Deny(code, effect, target, needed)}`；不读 JVM 属性/环境变量（配置由构造参数注入） | 可 dry-run、可重放的判定核心 |
| A3 | 迁文件路径判定（**已实现**） | `resolvePath` 的两处词法 `startsWith(canonicalRoot)` 合并为一次 `Authorizer` 判定，目标与 scope 根都经 `canonicalPath`（realpath）匹配；scope 根 = workspace real root + [MirrorRoots] 自动派生的镜像模块真实目标（扫 junction/symlink，用 `toRealPath()≠toAbsolutePath()` 检测，跨平台含 NTFS junction）；敏感/受保护清单仍作为正交护栏保留 | 与 A2 同源判定；模块 junction 写入仍允许，外部越界仍拒绝 |
| A4 | 迁 Shell 边界守卫（**已实现，语义原样保留**） | `workspaceBoundaryViolation` 的词法 `startsWith` 边界判定迁入 `boundaryAuthorizer`（`Authorizer`，`canonicalize=false` 保留词法语义）；`toolchainRoots`（B4）进入 `Scope.readPaths/writePaths`；`isLauncherReference`（B3）迁入 `Scope.launcherBasenames`/`executableExtensions`，由 `CanonicalScope.isLauncher` 判定。`argumentPathViolation`（凭据）与 `protectedPathViolation`（受保护目录）按设计 §16 作为正交护栏保留，不进 capability scope | 边界判定点从词法 `startsWith` 收敛到 `Authorizer`；B3/B4 表达为 scope 条目 |
| A5 | 注入与接线（**已实现**） | `CodingComponents.boundaryAuthorizer(root)` 构造**单一共享实例**，含两个 grant：`cap-fs-boundary`（canonical scope，workspace+mirror）与 `cap-shell-boundary`（lexical scope，workspace+mirror+toolchain+launcher，`canonicalize=false`）。`canonicalize` 从 Authorizer 构造参数下沉到 `Scope`（per-capability）。`Request` 新增 `capabilityId` 字段：FS 钉 `CAP_FS_BOUNDARY`，shell 钉 `CAP_SHELL_BOUNDARY`，避免 shell 更宽 scope 误覆盖 FS 请求。实例按 canonical root 缓存（`ConcurrentHashMap`），同 workspace 的 FS/shell 拿到同一对象 | 单一构造点，杜绝漂移 |
| A6 | `coding.explain`（**已实现**） | 在 `CodingToolExecutor.toolSpec` 注册 dry-run 工具；只返回 `Decision`，不执行副作用；矩阵归入 `READ_METHODS` | 模型可自诊断"要什么" |
| A7 | 黄金判定基线（**已实现**） | 从现有 `CodingAgentShell*Test` / `CodingAgentFileSystem*Test` / `AgentAccessPolicyTest` 抽取输入集，落一份 JSON 基线（命令 → allow/deny + code），新增 `PermissionDecisionBaselineTest` 逐条比对 | M0 验收证据 |

**A1/A2 实现记录**（2026-10-31）：

- `Capability.kt`：`Effect`（封闭枚举）、`Target`（Path/Host/Command）、`Scope`、`Limits`、`Cost`、
  `Capability`、`CapabilityRequest`、`CapabilitySource`；`AuditCode` 复用 `PermissionAudit` 码表，
  不新造第二套拒绝码。
- `Authorizer.kt`：`Request`、`Decision`、`Authorizer.decide(request, consumed)`，`Deny` 必带 `needed`。
  判定顺序：无 effects → `Allow`；无授予 → `TOOL_DENIED`；效果缺目标 → `TOOL_DENIED`；
  目标越界 → `PATH_OUTSIDE_WORKSPACE` / `COMMAND_NOT_ALLOWED`；单一 capability 未覆盖整个请求 →
  `TOOL_DENIED`；预算超限 → `BUDGET_EXCEEDED`。
- 与设计稿的三处偏差（后续阶段需要知道）：
  1. `Scope` 用"目录根即递归覆盖"表达 `**`，不引入 glob 展开——只收敛、不扩张；
  2. 一个请求必须由**单一** capability 覆盖，保证预算归属唯一；
  3. `CODE_BUDGET_EXCEEDED` 提前在 A2 加入 `PermissionAudit`（原计划 B3）。
- `Authorizer` 不读 JVM 属性/环境变量：输入是构造期的 capabilities 与 `decide` 的 request/consumed；
  `canonicalPath` 对存在的路径用 `toRealPath()`，不存在的路径按最近存在父目录解析。

**A3/A4 实现记录**（2026-10-31）：

- A3（FS 边界）：`CodingAgentFileSystem.resolvePath` 两处 `startsWith(canonicalRoot)` 合并为一次
  `boundaryAuthorizer.decide`（`canonicalize=true`，realpath 匹配）；scope 根 = workspace real root +
  `MirrorRoots.derive(workspaceRoot)`。`MirrorRoots` 用 `toRealPath() ≠ toAbsolutePath()` 检测
  junction/symlink（NIO 的 `isSymbolicLink` 识别不了 NTFS junction）。
- A4（Shell 边界）：`CodingAgentShell.workspaceBoundaryViolation` 改为调用 `boundaryAuthorizer.decide`
  （`canonicalize=false`，保留词法匹配——shell 历史契约按拼写判定，workspace 内 junction 仍可用）。
  - `toolchainRoots`（B4：java.home / ~/.m2 / ~/.gradle / ~/.cache / tmpdir / SystemRoot）→
    `Scope.readPaths`/`writePaths`；
  - `isLauncherReference`（B3：`LAUNCHER_BASENAMES` + `EXECUTABLE_EXTENSIONS`）→
    `Scope.launcherBasenames`/`executableExtensions`，由 `CanonicalScope.isLauncher` 判定；
  - 删除 `isLauncherReference` 方法；`argumentPathViolation`/`protectedPathViolation` 按设计 §16
    作为正交护栏保留（敏感/受保护是护栏，不是 capability scope）。
- `Authorizer` 扩展：新增构造参数 `canonicalize: Boolean = true`（FS 用 true，shell 用 false）；
  `CanonicalScope` 携带 `launcherBasenames`/`executableExtensions` 与 `canonicalize`；
  `Scope` 数据类新增 `launcherBasenames`/`executableExtensions` 字段。
- 偏差/待办：FS 与 shell 的 boundary Authorizer 是两个实例（canonicalize 与 scope 根不同）。
  A5 "共享单一 Authorizer 实例" 需先把 `canonicalize` 从 Authorizer 级下沉到 `Scope` 级，
  否则一个实例无法同时服务 FS（canonical）与 shell（lexical）。M0 目标——边界判定收敛到
  `Authorizer` 一处——已达成，实例共享推迟到 A5。

**A5 实现记录**（2026-10-31）：

- `canonicalize` 从 `Authorizer` 构造参数下沉到 `Scope.canonicalize`（per-capability，默认
  `true`）。`CanonicalScope.of(scope)` 直接读 `scope.canonicalize`，`Authorizer` 构造函数恢复为
  `(capabilities, clock)`。
- `Request` 新增 `capabilityId: CapabilityId? = null`：当设置时，`decide` 只对该 grant 判定，
  其余 grant 不参与。这是共享实例能正确服务 FS（canonical）与 shell（lexical）的关键——否则
  shell 更宽的 lexical scope 会误覆盖 FS 对 junction-escape 路径的 canonical 拒绝。
- `CodingComponents`：
  - 新增 `CAP_FS_BOUNDARY` / `CAP_SHELL_BOUNDARY` 常量；
  - 新增 `boundaryAuthorizer(root): Authorizer`，按 canonical root 缓存（`ConcurrentHashMap`），
    同一 workspace 的 FS 与 shell 拿到同一实例；
  - 实例含两个 grant：`cap-fs-boundary`（canonical，workspace+mirror）、
    `cap-shell-boundary`（lexical，workspace+mirror+toolchain+launcher）；
  - `shell()` / `buildShell()` / `fs()` 均注入该实例。
- `CodingAgentFileSystem` / `CodingAgentShell` 新增 `boundaryAuthorizer: Authorizer? = null` 构造
  参数；非空时使用并钉对应 `capabilityId`，为空时回退到各自 lazy `fallbackBoundaryAuthorizer`
  （供测试与直接构造）。`toolchainRoots` 计算上移到 `CodingComponents.toolchainRoots()`，shell 复用。
- 等价性：`browser4-coding` 513 测试全绿；`browser4-agentic` `CodingToolExecutorTest`（73）通过。
  直接构造（`CodingAgentFileSystem(root)`）走 fallback，行为不变；经 `CodingComponents` 构造走
  共享实例，capability pinning 保证两层语义互不串扰。

**A6 实现记录**（2026-10-31）：

- `CodingAgentFileSystem` 新增 public `activeBoundaryAuthorizer: Authorizer`，返回注入实例或
  fallback，使 dry-run 工具能拿到与真实工具一致的判定器。
- `CodingToolExecutor` 注册 `coding.explain(path, method?)` dry-run 工具：仅解析路径并对共享
  boundary Authorizer 调两次 `decide`（`CAP_FS_BOUNDARY` 走 canonical，
  `CAP_SHELL_BOUNDARY` 走 lexical），渲染 ALLOW/DENY 摘要，不读不写不执行。凭据位置与
  protected path 明确标注为"独立护栏、不在能力范围内"。
- `AgentAccessPolicy.READ_METHODS` 纳入 `explain`，`CodingToolMatrixCoverageTest` 继续绿。
- 等价性：`browser4-coding` 513 测试全绿；`browser4-agentic`
  `CodingToolExecutorTest`（74，含新增 `testExplainBoundary`，覆盖工作区内 ALLOW 与工作区外
  DENY）与 `CodingToolMatrixCoverageTest` 全绿。注：`CliAgentPromptTest.hygieneFollowsMode` 失败
  为既有问题，与 A6 无关。

**A7 实现记录**（2026-10-31）：

- 新增 `browser4-coding/src/test/resources/ai/platon/pulsar/coding/permission-decision-baseline.json`：
  三组金标准 `shell`（22 条）/ `fs`（9 条）/ `policy`（15 条），每条记 `id`、输入、
  `allowed`、可选 `code`。输入含占位符 `{workspace}` / `{outsideFile}` / `{rootPath}` /
  `{relativeEscape}`，在回放时按临时目录解析，基线本身不写死平台路径。
- 新增 `PermissionDecisionBaselineTest`：逐条回放 `CodingAgentShell.isAllowed/explain`、
  `CodingAgentFileSystem.readFile/writeFile/delete`、`AgentAccessPolicy.denial`，只比对
  **allow/deny + 审计码**，不断言拒绝文案。shell 的审计码由 `explain` 的原因前缀映射
  （`path guard:` → `COMMAND_PATH_DENIED`，`protected path:` → `PROTECTED_PATH`，其余
  `COMMAND_NOT_ALLOWED`）；fs 从返回文本提取 `code=`；policy 从 `denial` 的
  `PermissionAudit` 文本提取 `code=`。
- 说明：shell 的"工作区外"用例必须用 `{rootPath}`（文件系统根）而不是临时目录下的文件——
  `CodingComponents.toolchainRoots()` 含 `java.io.tmpdir`，临时目录内的路径属于 B4 例外，
  会被允许；这是既有语义，基线如实记录。
- 灵敏度自检：临时把 `policy.read-explain` 期望改成 deny，`PermissionDecisionBaselineTest`
  如实报 `[policy] policy.read-explain: allow/deny drifted`，确认比对不是空转；随后恢复。
- 等价性：`browser4-coding` **516** 测试全绿（513 + 新增 3）。A 阶段完成。

**A 阶段验收**：`browser4-coding` 与 `browser4-agentic` 现有单测全绿；`PermissionDecisionBaselineTest`
逐条一致（零差异）；`CodingToolMatrixCoverageTest` 覆盖 `explain`；构建通过。

**A 阶段禁止事项**：不要在这个阶段引入新的拒绝、不要删除 B1–B4、不要改拒绝文案里的 JVM 开关提示
（那条留到 E2）。

### 阶段 B —— 效果声明与审计，只记录不拦截（对应设计 M1）

| ID | 工作项 | 主要改动 | 产出 |
|---|---|---|---|
| B1 | `ToolSpec` 增加 `effects` | `browser4-agentic` 的 `ToolSpec` 增加 `effects: Set<Effect>`；`CodingToolExecutor.toolSpec` 逐条声明；`skill` 的 EXEC 方法同样声明 | 静态能力声明 |
| B2 | 注册期 fail-closed | 扩展 `CodingToolMatrixCoverageTest` → 新增 `CodingToolEffectsCoverageTest`：缺声明的工具在**启动期/测试期**失败，不是运行期拒绝 | 不漂移 |
| B3 | 审计码表扩容 | `PermissionAudit` 新增 `CODE_OUT_OF_SCOPE_WRITE`、`CODE_NETWORK_TARGET`、`CODE_BUDGET_EXCEEDED`、`CODE_SANDBOX_DENIED`；新增 `writeEvent(...)` 记录（区别于 `denied`） | 三类事实可查询 |
| B4 | `coding.permissions` 扩展 | 返回 active capability、已用预算、越界写入计数、网络目标摘要 | 观测面 |
| B5 | 越界写入检测 | 文件工具：写入前 realpath 判定即可记录，可信。Shell：**字符串层不可观测**，只记录"命令可能越界"的标记，不伪造结论；真正的写入事实等 C 阶段沙箱侧提供 | 如实的能力边界 |

**B 阶段验收**：连续跑一周真实任务（M1 目标），产出"实际需要的 scope 清单"；审计计数与
`coding.permissions` 一致；无新增 high-noise 日志（沿用 `logged` 去重机制）。

**B 实现记录**（2026-10-31）：

- B1：`ToolSpec` 新增 `effects: Set<Effect>?`（`@get:JsonIgnore`，不进入 spec 快照 / MCP JSON）。
  `CodingToolExecutor` 在 init 末尾按 `CodingToolEffects.TOOL_EFFECTS` 逐条填充；56 个 method
  全部声明（`shell` = `{FS_READ, FS_WRITE, FS_EXEC, NET_CONNECT}`，纯报告/会话工具 = `emptySet()`）。
  该表原本放在类内第二个 `private companion object`，与已有的 `companion object` 冲突，已移到
  同文件的顶层 `internal object CodingToolEffects`。skill 域：`SkillToolExecutor` 的 6 个入口 +
  `SkillScriptToolExecutor` 的 `readReference`（`FS_READ`）/`runScript`（`{FS_EXEC, PROC_SPAWN}`）。
- B2：新增 `CodingToolEffectsCoverageTest`（4 个用例）：注册集合与 `TOOL_EFFECTS` 逐键比对（漂移即失败）、
  所有 coding method `effects != null`、代表项语义（shell 最大化 / 报告工具为空集）、skill 入口非 null。
  失败发生在**测试/启动期**，不是运行期拒绝。
- B3：`PermissionAudit` 新增 `CODE_OUT_OF_SCOPE_WRITE` / `CODE_NETWORK_TARGET` / `CODE_SCOPE_UNCERTAIN`
  （`CODE_BUDGET_EXCEEDED`、`CODE_SANDBOX_DENIED` 已在码表内），新增 `writeEvent(tool, code, detail)`：
  复用同一计数器，但日志用 `event:` 前缀去重，因此同一 `tool:code` 的拒绝与事实各留一行、互不压制。
- B4：`coding.permissions` 增加 `Capabilities and observed activity:` 区块——boundary grants
  （`CAP_FS_BOUNDARY` canonical / `CAP_SHELL_BOUNDARY` lexical）、declared effects、budget
  （当前 unbounded，preset 预算留到 M2）、以及 out-of-scope writes / network targets / scope uncertain
  三项计数（直接读 `PermissionAudit.snapshot()`）。`explain`/`permissions` 本身为 `NONE`，保持 dry-run。
- B5：文件工具在 `resolvePath(path, write = true)` 中判定：`allowExternalAccess=true` 且 realpath 越界时
  记 `coding.fs:OUT_OF_SCOPE_WRITE`（写入前记录，可信）；11 个 mutator 传入 `write = true`
  （`protect` 是会话状态、不写盘，故意不记）。Shell 只在**执行入口**（`execute`/`executeRaw` 在
  `validateCommand` 通过之后）调用 `recordScopeAdvisories`：`allowExternalAccess` 下对越界拼写记
  `SCOPE_UNCERTAIN`、`allowNetwork` 下对网络命令记 `NETWORK_TARGET`；`isAllowed`/`explain` 不移动计数器。
- 验证：新增 `CapabilityObservabilityTest`（9 用例，覆盖事件计数、拒绝与事件共存、`reset`、
  FS 越界写入/工作区内写入/被拒外部写入三态、shell dry-run 不计数、shell 越界与网络两条 fact）。
  `browser4-coding` **525** 全绿（516 + 9）；`browser4-agentic` **1695**（唯一失败为既有
  `CliAgentPromptTest.hygieneFollowsMode`，与 B 无关）。

### 阶段 C —— 强制层落地（对应设计 M2，opt-in）

| ID | 工作项 | 主要改动 | 产出 |
|---|---|---|---|
| C1 | `ExecutionSandbox` 抽象 + 环境白名单 | 新增 `sandbox/ExecutionSandbox.kt`：`start(workspaceRealpath, capability) -> ProcessSpec`；`SandboxEnvironment` 只透传 `PATH`/`HOME`/`JAVA_HOME`/运行时必需项 + 任务显式声明变量；`CodingAgentShell.runCommand`（L1529-1596）与 `CodeRunner.run`（L104-113）共用同一构造 | 两条 exec 路径统一 |
| C2 | 长期密钥隔离 | `DEEPSEEK_API_KEY` / `OPENROUTER_API_KEY` 等不进 exec 进程；需要时由 broker 签发短期 token | 凭据不泄漏给子进程 |
| C3 | Windows v1 | 独立低权本地用户 + 文件 ACL + `CreateProcessWithToken`；workspace `rw`，只读根 `ro`，其余拒绝 | 覆盖任意子进程 |
| C4 | Linux/macOS v2 | bwrap/nsjail（Linux）、容器或 `sandbox-exec`（macOS）；net namespace 按 `allowNetwork` | 更强隔离 |
| C5 | 硬失败与自检 | 沙箱启动失败抛结构化异常并终止任务；启动时做写探针自检；`browser4.agent.sandbox=off|windows-acl|bwrap|container` 开关 | 不会静默关边界 |

**C 阶段验收**：沙箱内跑通"构建 + 单测 + 文件工具全链路"；沙箱外写入被 OS 拒绝；开关为 off 时行为
与 B 阶段完全一致。

**C 本轮范围（2026-10-31）**：只落地 C1/C2（用户确认）。C3（Windows ACL）/ C4（bwrap/container）/
C5（写探针、`browser4.agent.sandbox` 全开关）留到有管理员权限与对应运行时的环境；本构建里除 `off`
以外的沙箱 id 一律**硬失败**（`SandboxUnavailableException`），不回退。

**C 实现记录**（2026-10-31）：

- C1：新增 `sandbox/ExecutionSandbox.kt`——`ProcessSpec(command, directory, environment)`、
  `ExecutionSandbox.prepare(spec, workspace, allowNetwork)`、`OffSandbox`、`ProcessLauncher.start(...)`；
  `ExecutionSandboxes.current()` 读 `browser4.agent.sandbox`（默认 `off`），未知/未实现值直接抛
  `SandboxUnavailableException`。`CodingAgentShell.runCommand` 与 `CodeRunner.run` 都改为
  `SandboxEnvironment.build(...)` → `sandbox.prepare(...)` → `ProcessLauncher.start(...)`，两条 exec 路径
  不再各写一份环境构造。
- C2：`SandboxEnvironment` 只从后端进程透传白名单（PATH/HOME/JAVA_HOME/TEMP/COMSPEC/PATHEXT/
  USERPROFILE/APPDATA/Gradle/Maven/Python/Node 工具链等），其余一律丢弃；任务显式声明的变量（per-command
  `env` + session `setEnv`）优先并覆盖白名单。**长期密钥不再继承**；显式声明的 secret-shaped 变量仍放行，
  但记 `PermissionAudit` 的 `coding.env:ENV_PASSTHROUGH` 事件；broker 短期 token 未实现（计划后移）。
- 行为对照：`off` 时判定与 B 阶段逐条一致（`PermissionDecisionBaselineTest` 仍全绿）。
- 验证：新增 `sandbox/ExecutionSandboxTest`（5 用例：硬失败/白名单剥离/显式透传与审计/非密钥静默/
  shell 仍能看到声明变量）。`browser4-coding` **530** 全绿（525 + 5）。

### 阶段 D —— 默认开启与字符串层降级（对应设计 M3）

D1 默认开启沙箱（新安装默认 `windows-acl` / `bwrap`）。
D2 `browser4.agent.shell.pathGuard`、`browser4.agent.allowExternalAccess` 降级为 advisory 并标记 deprecated；
文案不再承诺"这是安全边界"。
D3 沙箱的 OS 级拒绝翻译成 `PermissionAudit` 结构化码（复用 `CODE_SANDBOX_DENIED`），不把
`permission denied` 裸文本丢给模型。
D4 文档更新：`skills/browser4-cli/references/agent.md` 权限章节、`docs/config.md` 开关表。

**D 阶段验收**：沙箱启动失败 = 任务失败（硬失败测试）；无 JVM flag 的分支上跑完整回归。

### 阶段 E —— 审批取代开闸（对应设计 M4）

E1 `coding.requestAccess(effect, target, reason, ttl)`：生成待批准项，不进模型上下文；批准只**收窄**，
不能放宽 preset 已拒绝的效果；无人值守时直接拒绝并给 workspace 内替代方案。
E2 删除 B3（`isLauncherReference` 的外部路径放行）与 B4（`toolchainRoots` 整片豁免），改为 scope 显式列根。
E3 删除拒绝文案中的 JVM 开关提示，改为结构化 `needed` + 审批入口。
E4 CLI 形态：交互式确认 vs 预置策略文件（见 §6 决策 3）。

## 4. 依赖与顺序

```
A1 → A2 → A3/A4 → A5 → A6/A7
                 └→ B1 → B2 → B3/B4/B5
                            └→ C1 → C3/C4 → C5 → D1 → D2/D3 → E1 → E2/E3/E4
```

- A3 依赖 A1 的 scope 模型与 `WorkspaceMirror` 的镜像根推导，**不能先改 realpath**。
- C 阶段依赖 B1 的 effects 声明，否则沙箱不知道"允许什么"。
- D 阶段的"默认开启"必须等 C5 的自检通过，否则一次 ACL 配错就会让构建莫名失败。

## 5. 测试矩阵

| 层 | 测试 | 位置 | 覆盖 |
|---|---|---|---|
| 单元 | `PermissionDecisionBaselineTest` | `browser4-coding/src/test/.../coding/` | M0 逐条等价 |
| 单元 | `AuthorizerTest` | 同上 | 每个 effect × allow/deny/budget |
| 单元 | `CapabilityScopeTest` | 同上 | realpath、`**` 通配只收敛、镜像根命中 |
| 单元 | `CodingToolEffectsCoverageTest` | `browser4-agentic/src/test/.../builtin/` | 缺声明 fail closed |
| 单元 | `SandboxEnvironmentTest` | `browser4-coding/src/test/.../coding/sandbox/` | 白名单构造，密钥不透传 |
| 集成 | `CodingToolExecutorIsolationTest`（扩展） | `browser4-agentic/src/test/.../builtin/` | 预设 × 工具的端到端拒绝 |
| E2E | `CodingToolsE2ETest`（扩展） | `browser4-coding/src/test/.../coding/` | 沙箱内构建 + 文件写入 |
| Windows 专项 | `CodingAgentShellWindowsBuiltinTest`（扩展） | `browser4-coding/src/test/.../coding/` | `cmd /c`、`pwsh -Command`、ACL 拒绝 |

按仓库约定：先 `.\mvnw.cmd -q -D"skipTests"` 编译，再 `.\mvnw.cmd -pl browser4-coding -am test -Dtest=<类名>`
跑最小范围；跨模块（A5、B2、C1）升到 `browser4-agentic` + `browser4-rest` 相关测试。

## 6. 需要确认的决策

| # | 决策 | 选项 | 影响 |
|---|---|---|---|
| 1 | Windows 默认强制方式 | **低权用户 + ACL（推荐 v1）** / WSL2 容器 | 前者与 Chrome/CDP 交互自然，后者隔离更强但 `b4.run` 需要额外通道 |
| 2 | 沙箱内 Chrome 由谁启动 | **后端驱动沙箱外 Chrome（2026-10-31 定）** / 沙箱内 Chrome + CDP 通道 | 已定；不需要跨 token 回环通道，`b4.run` 不纳入沙箱 |
| 3 | `coding.requestAccess` 落地形态 | **混合三通道（2026-10-31 定）**：策略文件 / REST / CLI 斜杠命令；调用非阻塞 | 已定；见 §6.2 |
| 4 | 无人值守是否保留 `autoApproveUpTo` | **取消（2026-10-31 定）**：所有放宽只经策略文件/审批；无人值守默认统一为 `full`（含 exec 与出站网络） | 见 §6.3 |
| 5 | 镜像模块的写 scope | 从 `WorkspaceManifest` 派生 / 显式配置 | 不解决则 realpath 判定会拒绝所有模块写入 |

### 6.1 决策 #2 记录（2026-10-31）

**结论**：后端驱动沙箱外 Chrome；`b4.run` 子进程不纳入沙箱，记为唯一显式豁免。

依据：

1. 产品浏览器已由后端 JVM 启动（`DefaultBrowserFactory` → `PulsarBrowserLauncher`），session 区分
   `BROWSER4_LAUNCHED`（后端拥有生命周期）与 `CDP_ATTACHED`（外部浏览器，永不隐式重建），
   后端是唯一的浏览器生命周期持有者。
2. 通信方向是「CLI / agent 子进程**出站**到 `localhost:18182`」，而不是「后端**入站**连沙箱内
   Chrome 的调试端口」。设计 §15 #2 担心的跨 token 回环通道在现有架构下并不存在，P1-2 不需要该项。
3. `b4.run` 由 `CliProcessManager` 用裸 `ProcessBuilder` 启动 browser4-cli，不经过
   `ProcessLauncher`，因此当前就不在沙箱覆盖内；保持现状并显式记录，而不是顺手改道。
4. 沙箱内自起测试浏览器（Playwright / Puppeteer）不在本计划内：默认拒绝，需要
   `PROC_SPAWN` + `NET_CONNECT` + 临时目录写的显式声明，留作后续独立决策。

后续约束：

- P1 / P2 不得为「沙箱内 Chrome」新增端口桥接或 CDP 反向通道。
- `b4.run` 豁免须在 P3-6 文档与 `coding.permissions` 输出中显式标注，避免被误读为沙箱全覆盖。
- Linux `bwrap` 后端可安全使用 `--unshare-net`（`allowNetwork=false`），因为产品链路不经沙箱。

### 6.2 决策 #3 记录（2026-10-31）

**结论**：混合三通道审批 + 非阻塞 `pending`。

| 通道 | 适用场景 | 落地 |
|---|---|---|
| 预置策略文件 | 无人值守（coworker / CI） | 任务或工作区内的 access 声明，启动时加载；按 effect + target 模式 + ttl 预批准，无运行时交互 |
| REST 端点 | IDE / Web 控制台 / 外部 CI | `GET /api/agent/<taskId>/access` 列 pending；`POST .../<requestId>/{approve,deny}` |
| CLI 斜杠命令 | 单机开发者（TTY） | `agent run -i` 增加 `/access`、`/approve <id>`、`/deny <id>`；非 TTY 只打印 pending 摘要，不提示 |

统一收敛点：**三条通道都只写入一条 `Capability(source=OPERATOR, expiresAt=...)`**。`Authorizer` 不感知来源，
判定逻辑无需为审批改动；来源只进审计字段。

调用语义：

1. `coding.requestAccess(effect, target, reason, ttl)` 立即返回 `{requestId, state: "pending", needed, alternatives}`，
   **不阻塞 agent loop**；同一 `effect + target` 的重复申请返回同一 `requestId`。
2. 批准后，下一次同类调用由 `Authorizer` 按 `OPERATOR` grant 放行；本轮不回溯。
3. 过期采用惰性清理（判定时与 `coding.permissions` 输出时检查 `expiresAt`），不引入后台定时器。

边界（沿用设计 §11 的"护栏不可越"）：

- 批准不得越过 `denyTools`、`protectedFiles` / `protectedDirs` / `sensitivePaths`，也不得跨 preset 的硬边界；
  在护栏之内，`OPERATOR` grant 可把 preset 拒绝的 effect 放开到申请声明的 target。
- 无人值守且无可用批准通道时，申请进入 `denied`（`write` 类 effect 由 §6.3 的无人值守默认值覆盖），并把
  "改用 workspace 内路径"的替代方案返回模型。

审计：新增 `ACCESS_REQUESTED` / `ACCESS_APPROVED` / `ACCESS_DENIED` / `ACCESS_EXPIRED`，每条带来源
（`file` / `rest` / `cli`）；`coding.permissions` 列出 pending 与生效中的 `OPERATOR` 条目。

**实现状态（2026-10-31，P4-1 已落地）**：共享签发 API `AccessApprovals` + REST 端点 +
`coding.requestAccess` 工具已实现；`Authorizer` 未改动，审批经 live supplier 收敛到预设门禁与组件层
provider（`readOnlyProvider` / `allowExecutionProvider`）。`file` / `cli` 两通道按计划留到 P4-4，
本轮审计来源为 `rest` / `default`。

**实现状态（2026-10-31，P4-4 已落地）**：`file` 通道 = `<workspace>/.browser4/access-policy.json`
（`AccessPolicyFile` + `AccessApprovals.loadPolicyFile()`，任务启动时预批准，越轨条目转 denial 并
审计）；`cli` 通道 = `agent run -i` 的 `/access` / `/approve` / `/deny`（本地处理，不进模型上下文；
非 TTY 只在结束时打印 pending 摘要，不阻塞）。三通道统一签发 `Capability(source=OPERATOR)`，
审计来源 `file` / `rest` / `cli` / `default` 至此齐备。

### 6.3 决策 #4 记录（2026-10-31）

**结论**：删除 `autoApproveUpTo` 独立机制；无人值守默认统一为 `full`（含 exec 与出站网络），
浏览器与编程模式一致。

删除项：

- `AgentAccessPolicy.autoApproveUpTo` 字段、对应 JVM 属性、CLI `--auto-approve`；
- policy 层的自动放行路径与 `CODE_AUTO_APPROVED` 事件（码表保留但不再产生，标注 deprecated）；
- 相关测试改写：`AgentAccessPolicyTest` 的 auto-approval 用例、`CodingAccessEnforcementTest` 的两条用例
  改为策略文件用例。

统一路径：所有放宽只产生 `Capability(source=OPERATOR, expiresAt=...)`，来源为 `file`（策略文件）、
`default`（无人值守默认值）、`rest` / `cli`（运行时批准）。

无人值守判定：

| 运行形态 | 判定 | 默认预批准 |
|---|---|---|
| 后端异步 job（浏览器模式，`agent run` 默认） | 无人值守 | `full`：workspace（含镜像根）内 `FS_READ` / `FS_WRITE` / `FS_EXEC` / `PROC_SPAWN`，`NET_CONNECT` 出站不限域名 |
| 后端异步 job（`--coding` 编程模式） | 无人值守 | 同上 |
| `agent run -i` 且 stdin 为 TTY | 有人值守 | 不套用默认值，按 `--access` 与审批 |
| `agent run -i` 但 stdin 非 TTY | 无人值守 | 同异步 job |

与现状的关系：

- 两种模式默认 `full` 与现有 `AgentAccessPolicy` 的默认 preset 一致（现状默认即 `FULL`），本决策只把
  `allowNetwork` 显式置为 `true`（现状默认 `false`），使脚本能联网。
- 净变化只有三处：删除 `autoApproveUpTo`（收敛为单一 `OPERATOR` 路径）、`allowNetwork=true`、
  默认批准每次记 `ACCESS_APPROVED(default)`；路径边界与护栏不变。
- "不会执行无关程序"仍由 `CodingAgentShell` 的 `DEV_COMMANDS` 白名单保证：默认 `full` 只放开 tool gate，
  命令本身仍要过白名单；非白名单命令需显式 `allowCommand` 或走审批。
- 浏览器模式初始工具集不含 `coding.shell` / `runCode`，需要时由 `system.exposeTools` 暴露，暴露后即可用。

优先级：任务/工作区策略文件 > 无人值守默认值；`access.unattended` 可显式覆盖（`deny` / `read` /
`read-build` / `write` / `full`）；`denyTools`、`protectedFiles` / `protectedDirs` / `sensitivePaths`、
`allowExternalAccess=false` 的 workspace 边界与沙箱边界始终优先。

张力说明：默认 `full` + 出站不限域名，等于无人值守时"workspace 外不可写，但命令与联网不设上限"。缓解：

1. 命令仍受 `DEV_COMMANDS` 白名单与 `allowDestructive` 控制，不是"任意程序"；
2. 出站连接逐条记 `NET_CONNECT(default)` 审计，不限域名但可事后追查；
3. 默认值只在无审批通道时生效，且只在无人值守 job 上派生；
4. `coding.permissions` 首行固定显示 `unattended default: full`；
5. 沙箱默认开启（P3-4）先于本决策实施（P4-1），默认 `full` 的 exec 落在 OS 强制层内；
6. `access.unattended` 可覆盖为 `deny` / `read` / `read-build` / `write`。

**实现状态（2026-10-31，已实现）**：`autoApproveUpTo`、`browser4.agent.autoApprove`、CLI
`--auto-approve`、`command.run(autoApprove=…)` 已全链路删除；`AgentAccessPolicy.unattendedDefault`
默认 `full` + 出站网络，`browser4.agent.access.unattended` 可覆盖（`deny` 以
`READ + allowNetwork=false + denyTools={"coding.*","skill.*"}` 表达，不新增枚举值）。
无人值守标记由 CLI 的 `io::stdin().is_terminal()` 决定：交互会话传 `Some(!tty)`，一次性
`agent run` 省略（后端默认 `true`）；派生默认记 `ACCESS_APPROVED(source=default)`。

## 7. 风险与回滚

| 风险 | 触发点 | 缓解 | 回滚 |
|---|---|---|---|
| 重构顺手修掉 B1–B4，等价证明失效 | A4 | A4 只搬运不改语义，基线测试卡住 | 回退 A4 单文件 |
| realpath 判定拒绝 junction 模块写入 | A3 | scope 显式包含镜像源根（决策 5） | 保留 `normalize` 分支，realpath 仅用于凭据 |
| ACL 配错导致 `~/.m2` 写失败 | C3 | C5 写探针自检 + B 阶段 scope 清单先行 | 开关设 `sandbox=off` |
| 容器启动 50–200ms/命令 | C4 | 每任务复用一个沙箱实例，不是每条命令一个 | 同上 |
| 失败从"command not allowed"变成 OS `permission denied` | D3 | 翻译回结构化码，保留同一码表 | 保留字符串层 advisory 提示 |

## 8. 明确不做

- 不引入策略 DSL（效果枚举保持封闭）。
- 不在 A–D 阶段删除任何现有守卫；M3 之前两者必须同时存在以便对比。
- 不改变 junction 直写真实检出的自开发形态，只做显式告知与 scope 纳入。
- 不把字符串守卫包装成"安全边界"写进文档。
