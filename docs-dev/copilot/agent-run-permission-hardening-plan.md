# `agent run` 读写权限优化计划

> 状态：**M0–M7 已实现**（PR1 + PR2 主体，含 CLI `--access`、文档与转发层 E2E；§11.4 记录了 M6 未覆盖的 4 条真实后端场景）· 版本：v2 · 范围：内置智能体 `agent run`（CLI 引擎）的文件系统、Shell 与工具暴露权限
> 依据：`browser4-coding` / `browser4-agentic` / `browser4-rest` 现状代码（2026-10-27 分支）
>
> v2 修订（评审反馈）：补齐工具矩阵遗漏的 6 个方法；新增 §4.1.1 策略→Shell 档位映射
> （含 `read-build` 的构建 shell 问题）；敏感路径启发式补环境变量形态并厘清增量价值；
> 批处理路径显式收口；删除无写入方的"会话配置"优先级；拒绝不改任务终态；工作量重估。
> 实现状态与偏离见 §11。

## 1. 背景

`agent run` 是无人值守的自主智能体，没有任何"允许/拒绝"的交互式审批。当前权限由三层各自实现，
策略分散、语义不清、且存在可绕过的路径：

| 层 | 实现 | 问题 |
|---|---|---|
| 工具暴露 | `RobustBrowserAgent.buildCliEngineToolSet()` + `ToolDisclosureTools` | 只决定**初始**暴露；`system.exposeTools` 可把 `coding.shell` 拉回来，browsing 任务也能拿到 shell |
| 路径沙箱 | `CodingAgentFileSystem.resolvePath()` | 只覆盖 `coding.*` 文件工具；`coding.shell` 的参数路径完全不解析 |
| 操作开关 | `CodingWorkspace.allowExternalAccess/allowDestructive` | 只有两个全局 JVM 属性；`allowDestructive=false` 名字像总开关，实际只关 `delete/copy/move`，不拦 `write/append/replace` |

本次优化的目标不是"再加一个开关"，而是把三层收敛成**一个可描述、可强制执行、可观测的权限模型**，
并保持默认行为不变（默认 = 现状，新能力全部 opt-in）。

## 2. 现状问题清单

| # | 级别 | 问题 | 证据 | 影响 |
|---|---|---|---|---|
| P0-1 | 安全 | 没有真正的只读模式 | `CodingAgentFileSystem` 无 `readOnly`；`CodingToolExecutor` 无方法级策略 | 调用方无法用"只读"约束一次任务 |
| P0-2 | 安全 | 工具披露绕过初始工具集 | `RobustBrowserAgent.kt:95-114`（初始集）vs `ToolDisclosure.kt:191-218`（可任意 expose） | "browser 任务不给 shell"只是提示词层面的假设 |
| P0-3 | 安全 | Shell 参数路径不沙箱 | `CodingAgentShell.kt:620-689` 只校验命令名；cwd 在 `:528-551` 受限，但参数不限 | `type %USERPROFILE%\.ssh\id_rsa`、`cat /etc/passwd` 直接可读；`mvn`/`npm` 等价任意代码执行 |
| P0-4 | 安全 | 受保护文件清单硬编码且过窄 | `CodingAgentFileSystem.kt:94-101`（6 项）；`.git/**` 只防删除，不防写入 | 治理文件清单不可扩展；`git config`、`settings.xml`、其余 workflow 未保护 |
| P0-5 | 正确性 | `--fresh-workspace` 与 coding 镜像叠加后"隔离"名不符实 | `StatefulAgentRunner.kt:603-612` + `WorkspaceMirror.kt:198-233`（模块 junction 直写真实检出） | 用户以为分支/目录已隔离，实际模块写入仍落到真实 checkout |
| P1-1 | 可用性 | 无 per-task 权限 | 仅 `CodingWorkspace` 读 JVM 属性 | 同一 backend 上多任务无法差异化授权（会话级暂无写入方，见 §4.1） |
| P1-2 | 可用性 | 模型与用户都看不到"当前权限" | 无 `coding.permissions`；`AgentTaskStatus` 只有 `workspace` | 模型反复尝试被拒操作；用户无法确认沙箱边界 |
| P1-3 | 可用性 | 拒绝语义是自由文本 | `errorResult("Path not resolved: …")` | CLI/上层无法区分"路径越界"与"文件不存在"，也无法给出开闸提示 |
| P1-4 | 文档 | `agent.md` 无权限章节 | `skills/browser4-cli/references/agent.md:61-97` 只讲 workspace 位置 | 用户按文档无法推断能读什么、能写什么 |
| P1-5 | 安全 | 权限矩阵未覆盖全部已注册方法 | `CodingToolExecutor` 注册 54 个方法，矩阵漏掉 `protect`/`classInfo`/`toolsDetect`/`projectType`/`runCodeLanguages`/`estimateTokens` | `protect(on=false)` 是会话级保护的**自修改入口**；`classInfo` 反射**后端类路径**（工作区之外的服务端 API），是只读预设下唯一的信息外泄面 |
| P2-1 | 工程 | 三个构造点重复策略 | `AgentToolManager.kt:316-327`、`CodingToolExecutor.kt:1879-1891`、`MCPToolController.kt:451-470` | 新增开关必然漂移 |
| P2-2 | 工程 | 无权限矩阵测试 | 现有 FS/Shell 单测按功能切分 | 新增策略组合没有回归网 |

## 3. 目标与非目标

**目标**

1. 新增统一策略对象 `AgentAccessPolicy`，作为**唯一事实来源**，由文件系统、Shell、工具注册、MCP 直调四个入口共同消费。
2. 提供真实可用的预设：`full`（默认）/ `write` / `read-build` / `read`。
3. 让"工具不可见"升级为"工具不可用"：披露、批量、MCP `tool call` 三条路径都被同一策略拦截。
4. 策略可 per-task 覆盖（`command.run` 参数 + CLI 选项），并在任务结束时复位。
5. 权限可观测：`coding.permissions` 工具、任务状态字段、一条结构化拒绝审计。
6. 矩阵完备：每个已注册的 `coding.*` 方法都被显式归类（未归类 fail-closed），并有测试防止漏登记。
7. `read-build` 真正可用：把面向模型的 shell 与内部构建 shell 拆开，避免"允许构建"和"禁止执行"互相打脸（§4.1.1）。
8. 文档与测试同步，默认行为零变化。

**非目标**

- 容器 / VM / seccomp 级隔离（Windows 成本过高，`coding-run-isolation-design.md` 已否决）。
- OS 用户切换或 ACL 改写。
- 浏览器侧 URL/域名白名单（另立计划，属于网络出口策略而非文件权限）。
- 改变"junction 直写真实检出"的自开发形态（只做显式告知与可选关闭）。

## 4. 设计

### 4.1 策略对象

放在 `browser4-coding`（与 `CodingWorkspace` 同模块，供 `browser4-agentic` 与 `browser4-rest` 共用）：

```kotlin
enum class AccessPreset { FULL, WRITE, READ_BUILD, READ }

data class AgentAccessPolicy(
    val preset: AccessPreset = AccessPreset.FULL,
    val allowExternalAccess: Boolean = false,
    val allowDestructive: Boolean = true,
    val allowNetwork: Boolean = false,
    val denyTools: Set<String> = emptySet(),              // "domain.method"
    val protectedFiles: Set<String> = DEFAULT_PROTECTED_FILES,
    val sensitiveReadPaths: Set<Path> = DEFAULT_SENSITIVE_PATHS,
    val auditDenials: Boolean = true,
) {
    fun allowsTool(domain: String, method: String): Boolean
    /** 工作区文件内容是否可改。注意 `READ_BUILD` 为 false，但它允许的 `mvnBuild` 仍会写 `target/`。 */
    fun allowsFileMutation(): Boolean   // preset == FULL || preset == WRITE
    fun allowsExec(): Boolean           // preset == FULL（`write` 也禁用 shell）
}
```

预设 → 工具矩阵（`coding.*`，覆盖 `CodingToolExecutor` 注册的**全部** 54 个方法）：

| 方法族 | FULL | WRITE | READ_BUILD | READ |
|---|---|---|---|---|
| `read/readLines/checksum/listDir/glob/grep/stat/diff/changeSummary/languages/workspaceRoot` | ✓ | ✓ | ✓ | ✓ |
| `symbols/references/kt*/impact/moduleGraph/diagnostics/lspServers/tokenStats` | ✓ | ✓ | ✓ | ✓ |
| `toolsDetect/projectType/runCodeLanguages/estimateTokens` | ✓ | ✓ | ✓ | ✓ |
| `classInfo`（后端类路径反射，非工作区信息） | ✓ | ✓ | ✓ | ✓ |
| `mvnBuild/verify/validate` | ✓ | ✓ | ✓ | ✗ |
| `write/append/replace/replaceRegex/editLines/insertAfter/delete/mkdir/copy/move/revert/scaffold*/devTask/resumeDevTask/trapCheck` | ✓ | ✓ | ✗ | ✗ |
| `protect`（无 `path`：列出动态保护） | ✓ | ✓ | ✓ | ✓ |
| `protect(path=…, on=…)`（会话级保护增删） | ✓ | ✓ | ✗ | ✗ |
| `shell/shellOutput/shellStatus/shellList/shellSetEnv/runCode` | ✓ | ✗ | ✗ | ✗ |

矩阵完备性规则：**新增 `coding.*` 方法必须同时登记到本矩阵**，未登记的方法默认按 `FULL` only 处理
（fail-closed，而不是默认放行），并由 §6 的 `CodingToolMatrixCoverageTest` 用
`CodingToolExecutor.toolSpec.keySet()` 反射比对，防止计划外的漂移。

`classInfo` 保留在全部预设：它不触碰工作区、无副作用，只是披露后端类签名；若调用方认为该信息敏感，
用 `denyTools=["coding.classInfo"]` 关掉。`protect(on=false)` 只能移除**动态**保护，
治理文件受 `DEFAULT_PROTECTED_FILES` 常驻保护（`CodingAgentFileSystem.kt:1454-1466`），
因此它不是"解除治理保护"的后门；但在只读预设下它仍是一次状态修改，故禁用写形态。

策略解析优先级（高 → 低）：

1. 任务参数 `command.run(access=…, allowExternalAccess=…, allowDestructive=…)`
2. JVM 属性 `browser4.agent.access`（默认 `full`），以及旧属性的兼容映射：
   `browser4.agent.allowExternalAccess`（默认 `false`）→ `allowExternalAccess`；
   `browser4.agent.allowDestructive`（默认 `true`）→ `allowDestructive`
3. 内置默认 `AgentAccessPolicy()`（`FULL`）

**不再保留"会话配置"这一级**：`sessionConfig` 目前只由 `PulsarSettings` 从会话能力解析，
没有任何写入 `browser4.agent.access` 的路径（`PulsarSessionManager.kt:235` 只读 `conf`），
加了也是死代码。等 CLI 具备会话级设置命令后再引入。

`CodingWorkspace.allowExternalAccess` / `allowDestructive` 降级为**服务级默认策略的只读投影**
（内部改为读 `AgentAccessPolicy.serverDefault`），不再是第二个事实来源；
`CodingComponents`（§4.3）是唯一构造点。

`allowDestructive=false` 保持旧语义（只关 `delete/copy/move`），但文档明确标注它**不是只读开关**，推荐迁移到 `access`。

#### 4.1.1 策略 → Shell 档位映射

`CodingAgentShell` 已有 `allowDevTools` / `allowNetwork` / `allowDestructive` 三档，新策略不得与之
形成两个事实来源。另外必须先解决一个已存在的问题：`mvnBuild` / `verify` 是**复用同一个 shell 实例**
执行 Maven 的（`CodingToolExecutor.kt:1063-1069` 传入 `receiver.shell`，`:1519-1540` 同理），
所以 `READ_BUILD` 不能"不构造 shell"，否则该预设的核心能力（构建自检）直接失效。

因此 `Target` 拆成**两个 shell**：面向模型的 `shell`（`coding.shell*` 用）与内部的 `buildShell`
（`mvnBuild`/`verify`/`devTask` 用）。

| preset | 面向模型的 `shell` | 内部 `buildShell` | 说明 |
|---|---|---|---|
| FULL | 正常构造 | 同一个实例 | 与现状逐字节一致 |
| WRITE | 不暴露（E3+E4 禁 `shell*`） | `buildOnly=true` | 有写无执行 |
| READ_BUILD | 不暴露 | `buildOnly=true` | 只读 + 构建 |
| READ | 无 | 无 | `mvnBuild/verify/validate` 同样被 E3 拒绝 |

`buildOnly=true` 是 `CodingAgentShell` 新增的档位：`DEV_COMMANDS` 收窄为 `BUILD_COMMANDS`
（`mvn/mvnw/gradle/gradlew/cargo/rustc/npm/npx/tsc/go/dotnet/make/cmake/ninja/javac/kotlinc`），
并剔除解释器（`python/node/pwsh/bash/sh/cmd`——它们等于任意代码执行）、云 CLI、`docker`、`ssh/scp`、VCS 写操作。
`allowNetwork` 不因 preset 放宽，只在策略显式要求时开启（默认 `false`，与现状一致）；
`allowDevTools` 在 `buildOnly` 下由 `BUILD_COMMANDS` 决定，不再是布尔总闸。

**诚实的威胁模型**：`READ_BUILD` 允许跑本仓自己的构建，而构建会执行工程自身的代码
（Maven 插件、surefire、`build.rs`、npm scripts）。所以它的定位是**防误操作与防外泄**，
不是"防恶意代码执行"；真正的执行隔离只有容器/VM，已在非目标中否决。
这一句必须同时进文档（§8）和 `coding.permissions` 的返回说明，避免用户误判。

### 4.2 强制执行点（纵深，不依赖上层自觉）

| # | 位置 | 作用 |
|---|---|---|
| E1 | `CodingAgentFileSystem` 新增 `readOnly` / `sensitiveReadPaths` | 最低层硬拦：所有 mutator 提前返回结构化拒绝；读敏感路径直接拒绝 |
| E2 | `CodingAgentShell` 新增 `policy` / `buildOnly` | `allowsExec()==false` 时 `shell` 整体拒绝；参数路径命中敏感集时拒绝；`buildOnly` 收窄命令集 |
| E3 | `CodingToolExecutor.callFunctionOn`（`:804`） | 方法级 allowlist：兜住 `runCode`/`devTask`/`mvnBuild`/`scaffold*`/`protect` 等不经过 FS 的写入口 |
| E4 | `buildCliEngineToolSet`（`RobustBrowserAgent.kt:170`） | 构造 registry 时过滤被禁方法 → `system.listTools`/`exposeTools` 无法复活 |
| E5 | `MCPToolController.standaloneCodingTarget`（`:451-470`） | MCP `tool call` 直调同一策略，不允许旁路 |
| E6 | `StatefulAgentRunner`（`:587-618` / `finally:687-718`） | 注入并复位 per-task 策略，避免 companion agent 串味 |
| E7 | `MCPToolController.handleBatchTool`（`:1269`）→ `executeAgentToolText` → `dispatchToToolExecutor` | 批处理**已**与单发调用走同一条链路，因此天然收敛到 E3；不新增代码，只加 §6 的断言把它锁死 |

E3 是方法级 allowlist 的唯一入口：单发、批量、MCP 直调三条路径最终都进 `callFunctionOn`，
所以"不允许旁路"只依赖这一处。

E4 是"披露绕过"的正解：被禁工具根本不在披露注册表里，`exposeTools` 只会返回 `unknown`。

### 4.3 消除三处构造重复

新增工厂（`browser4-coding` 或 `browser4-agentic` 的公共处）：

```kotlin
object CodingComponents {
    /** 面向模型的 shell（`coding.shell*`）。preset 不允许执行时返回 null。 */
    fun shell(root: Path, policy: AgentAccessPolicy): CodingAgentShell?
    /** 内部构建 shell（`mvnBuild`/`verify`/`devTask`）。preset == READ 时返回 null。 */
    fun buildShell(root: Path, policy: AgentAccessPolicy): CodingAgentShell?
    fun fs(root: Path, policy: AgentAccessPolicy): CodingAgentFileSystem
    fun target(root: Path, policy: AgentAccessPolicy): CodingToolExecutor.Target
}
```

`AgentToolManager`、`CodingToolExecutor.targetAt`、`MCPToolController` 全部改走工厂；新增开关只改一处。
`CodingToolExecutor` 内所有构建/校验入口改用 `receiver.buildShell`，`coding.shell*` 继续用 `receiver.shell`
（§4.1.1）。

### 4.4 敏感读路径（默认开启，低误伤）

**先厘清增量价值**：默认 `allowExternalAccess=false` 时，`coding.*` 文件工具本就无法解析到工作区外的绝对路径，
所以敏感读路径**不是**给默认配置新增的文件读保护。它只在三个场景产生实际收益：

1. `allowExternalAccess=true`（放行外部路径）时的二次拦截；
2. `coding.shell` 的**参数路径**（P0-3：shell 从不解析参数）；
3. 工作区内的**链接/junction**：路径字面在 workspace 内，解析后落到敏感目录
   （`WorkspaceMirror` 的模块 junction 就是这个形态）。

因此比对必须在**真实路径**上做：路径存在时取 `toRealPath()`，不存在时退回词法规范化。
纯词法前缀比对会漏掉第 3 种情况。

默认集合：`~/.ssh`、`~/.aws`、`~/.gnupg`、`~/.config/gcloud`、`~/.browser4`（含 `conf-enabled` 里的 LLM key）、
浏览器 profile 根（`browser.profile.mode` 解析结果）。构建/包管理目录显式放行：`~/.m2`、`~/.gradle`、`~/.cargo`、`~/.npm`、系统临时目录。

Shell 侧是**参数路径启发式**，必须覆盖各平台的环境变量写法，否则形同虚设
（计划 v1 的例子 `type %USERPROFILE%\.ssh\id_rsa` 恰好两种形态都不是，会直接穿过守卫）：

| 形态 | 例子 | 处理 |
|---|---|---|
| 绝对路径 | `/etc/passwd`、`C:\Users\me\.ssh\id_rsa` | 直接比对 |
| `~` 前缀 | `~/.ssh/id_rsa` | 展开为 home 后比对 |
| Windows `%VAR%` | `%USERPROFILE%\.ssh\id_rsa`、`%APPDATA%\gcloud\…` | 用 `System.getenv(VAR)` 展开后比对 |
| POSIX `$VAR` / `${VAR}` | `$HOME/.ssh/id_rsa` | 同上 |
| PowerShell `$env:VAR` | `$env:USERPROFILE\.ssh\id_rsa` | 同上 |

变量未定义时：**不整体 fail-closed**（见下方实现说明），仅当 token 命中敏感词（`.ssh`、`id_rsa`、`id_ed25519`、`.aws`、`.gnupg`、`gcloud`、`.browser4`）才拒绝，
否则 `%JAVA_HOME%\bin\mvn` 这类未设变量的正常调用会被误杀。
未展开的位置参数（`$1`、`%1`）不作路径处理，但命中敏感词时仍拒绝。
其余 token 一律放行，避免破坏 `mvn`/`git`。
`-Dbrowser4.agent.shell.pathGuard=off` 可关闭整套参数路径检查。

**PR2 第一刀已实现的状态**：

- 保护目录 `CodingAgentFileSystem.DEFAULT_PROTECTED_DIRS = {".browser4", ".github/workflows"}`：
  `.browser4` 是 Agent 自己的记账目录（快照、检查点、隔离 worktree），`.github/workflows` 是 CI 定义。
  命中前缀即拒绝全部内容写入（write/append/replace/delete/copy/move）。
  工具自身的快照落盘走 `writeAtomic` / `Files.writeString`，不经写入口，因此不受影响
  （`CodingAgentFileSystemWritePathTest.snapshotWriterBypassesProtection` 固化该点）。
- 敏感集 `DEFAULT_SENSITIVE_PATHS = {~/.ssh, ~/.aws, ~/.gnupg, ~/.config/gcloud, ~/.browser4}`，
  由 `CodingAgentFileSystem.resolvePath`（文件侧）与 `SensitivePaths`（共享真实路径判定）执行；
  **工作区内一律豁免**——`--fresh-workspace` 的 root 就在 `~/.browser4` 下，否则整棵工作区不可达。
- Shell 参数路径守卫 `CodingAgentShell.argumentPathViolation`，逃生门 `-Dbrowser4.agent.shell.pathGuard=off`。

### 4.5 可观测性

- 新工具 `coding.permissions`（只读，永远可用）返回 JSON：`preset / workspaceRoot / allowExternalAccess / allowDestructive / allowNetwork / deniedTools / protectedFiles / mirror{linked,copied,failed} / execModel`（`execModel` 为一句威胁模型说明，见 §4.1.1）。
- `CliAgentPrompt.hygiene()` 追加一行权限摘要（模型第一轮就知道边界，减少无效尝试）。
- `AgentTaskStatus` 增加 `access` 与 `deniedCalls` 字段（`agent status`/`agent result` 输出）；`access` 只描述策略，**不参与终态判定**（§9 决策 1）。默认值不破坏旧客户端（CLI 用 `serde_json::Value` 取值，见 M5）。
- 拒绝审计：结构化事件 `permission.denied {taskId, tool, path, reason}`，每任务计数进状态；日志级别 info，同一 `(tool,reason)` 每任务只打一次，避免刷屏。

## 5. 里程碑

| 里程碑 | 内容 | 主要文件 | 工作量 | 验收 |
|---|---|---|---|---|
| M0 | 基线与设计冻结：补权限矩阵测试骨架，记录当前行为快照 | `browser4-coding/src/test/...` | 0.5d | 现有测试全绿；矩阵测试先标 `@Disabled` 并注明本计划，**且 PR1 的 DoD 必须写明"PR2 合并前全部启用"**，防止随 PR1 上 main 后被遗忘 |
| M1 | `AgentAccessPolicy` + `CodingComponents` 工厂 + 三处构造点收口 | `browser4-coding/.../AgentAccessPolicy.kt`、`CodingComponents.kt`、`AgentToolManager.kt:316`、`CodingToolExecutor.kt:1879`、`MCPToolController.kt:451` | 1.0d | 策略解析单测（3 级优先级 + 旧属性映射）；`full` 下行为与基线完全一致 |
| M2 | 只读/禁执行强制执行（E1–E5）+ `buildShell`/`buildOnly` 拆分 | `CodingAgentFileSystem.kt`、`CodingAgentShell.kt`、`CodingToolExecutor.kt:804/1063/1879`、`RobustBrowserAgent.kt:170` | 2.5d | 每个 preset 的矩阵测试；`read-build` 下 `mvnBuild` 可用而 `coding.shell` 不可用；`exposeTools` 无法启用被禁方法；MCP 直调与批处理同样被拒 |
| M3 | 受保护文件扩展 + 敏感路径 + 审计事件 | `CodingAgentFileSystem.kt:94/1454`、`CodingAgentShell.kt:620` | 1.0d | 写 `.git/config`、`settings.xml`、`AGENTS.md` 被拒；`type %USERPROFILE%\.ssh\id_rsa` 被拒；构建目录不误伤 |
| M4 | per-task 覆盖 + CLI `--access` / `--allow-external` / `--allow-destructive` | `commands.rs:4025`、`CommandToolExecutor.kt:46`、`StatefulAgentRunner.kt:603`、`AgentToolManager.kt` | 1.0d | `agent run --access read` 端到端生效并在任务结束后复位；缺省不改变 payload |
| M5 | 可观测性：`coding.permissions`、prompt 摘要、状态字段、拒绝审计 | `CodingToolExecutor.kt`、`CliAgentPrompt.kt`、`Models.kt:26`（`AgentTaskStatus`） | 0.5d | `agent result` 显示 `access`；`coding.permissions` 与真实策略一致；CLI 侧已确认 `print_agent_deliverables` 用 `serde_json::Value` 取值、全 crate 无 `deny_unknown_fields`（`main.rs:15718`），新增字段不会打破旧 CLI——作为 DoD 断言写进验收 |
| M6 | 测试补齐（单测 + e2e） | 见 §6 | 1.5d | 见 §6 验收 |
| M7 | 文档与技能同步 | 见 §8 | 0.5d | 文档清单逐项勾掉 |

合计约 **9 人日**（M2 原估 1.5d 偏乐观：5 个强制执行点 + 每 preset 矩阵测试 + `buildShell` 拆分），
拆 3 个 PR：PR1 = M0+M1（无行为变化）、PR2 = M2+M3（安全基座）、PR3 = M4~M7（可用性与文档）。

## 6. 测试计划

**单元 / 组件（`browser4-coding`、`browser4-agentic`）**

- `AgentAccessPolicyTest`：3 级优先级、旧属性到策略字段的映射、preset 到方法矩阵的映射。
- `CodingToolMatrixCoverageTest`：反射 `CodingToolExecutor.toolSpec.keySet()`，断言每个方法都在矩阵里被显式归类
  （未归类 = 失败，而不是默认放行）；新增方法忘记登记时这条测试先红。
- `CodingAgentFileSystemAccessTest`：`read`/`read-build` 下全部 mutator 拒绝且错误码为 `PERMISSION_DENIED`；读仍可用；`write` 下 `delete` 按 `allowDestructive` 生效。
- `CodingAgentShellPolicyTest`：`allowsExec=false` 时 `shell` 拒绝；敏感路径参数拒绝（含绝对路径、`~`、`%VAR%`、`$VAR`、`$env:VAR` 五种形态，逐个验证）；未定义变量 fail-closed；`~/.m2` 放行；`pathGuard=off` 生效。
- `CodingAgentShellBuildOnlyTest`：`buildOnly=true` 时 `mvn`/`gradlew`/`cargo` 放行，`python -c`/`node -e`/`pwsh -Command`/`ssh`/`docker` 拒绝；`SAFE_COMMANDS` 不受影响。
- `CodingToolExecutorPolicyTest`：`runCode`/`devTask`/`scaffold*`/`protect(on=…)` 在只读下被拒，`protect` 的列表形态仍可用；`read-build` 下 `mvnBuild` 走 `buildShell` 可用而 `coding.shell` 被拒；批量步骤中混入被禁方法时整批失败且错误指明第几步（E7 锁定）。
- `ToolDisclosurePolicyTest`：`buildCliEngineToolSet(policy=READ)` 产出的 registry 不含写/执行方法；`exposeToolsResult` 对这些名字返回 `unknown`。
- `ProtectedFilesTest`：扩展后的清单（`.git/config`、`settings.xml`、`.github/workflows/*`）逐个验证写入/删除被拒，模块 `pom.xml` 仍可改。
- `SensitiveReadPathTest`：工作区内的 junction 指向敏感目录（真实路径在集合内、字面路径在集合外）时被拒。
- 命名遵循 `<Name>Test.kt` + camelCase + `@DisplayName`（AGENTS.md 约定），打 `Unit`/`Fast` 标签。

**E2E（`cli/browser4-cli/tests/e2e/scenarios/agent_run_permissions.rs`，接 mock 后端 + `FileBackedChatModel`）**

1. `--access read` 任务让模型调用 `coding.write` → 结果里出现 `PERMISSION_DENIED`，且任务终态**仍由完成信号决定**
   （§9 决策 1：不回退为 failed）；`agent result` 的拒绝计数为 1。
2. 默认（无选项）同任务 → 文件写入 workspace，`agent result` 的 `filesChanged` 含该文件。
3. `--access read` 下 `system.exposeTools(["coding_shell"])` → 返回 skipped/unknown，后续 shell 调用不存在。
4. `--access read --allow-external` 下读 `%TEMP%` 文件成功；缺省时被拒；同一任务里 `type %USERPROFILE%\.ssh\id_rsa` 被拒。
5. 任务结束后紧随一个默认任务 → 仍能写（验证 per-task 覆盖已复位）。
6. `command.run(access="read")` 的 MCP 直调路径与 CLI 行为一致（`MCPToolControllerE2ETest`）。
7. `--access read-build` 任务调用 `coding.mvnBuild` → 成功；调用 `coding.shell` → 被拒（验证 `buildShell` 拆分）。

**验收命令**

```bash
mvn test -pl browser4-coding -am -Dtest='CodingAgent*Test,AgentAccessPolicyTest,ProtectedFilesTest,SensitiveReadPathTest'
mvn test -pl browser4-agentic -am -Dtest='CodingToolExecutor*Test,ToolDisclosure*Test,CodingToolMatrixCoverageTest'
./mvnw -DskipTests
cargo test --test e2e -- --scenario='test_e2e_agent_run_permissions_*'
./b4w.ps1 code verify --module browser4-coding     # 结构化 VERDICT_JSON
```

## 7. 风险与回滚

| 风险 | 影响 | 缓解 |
|---|---|---|
| 只读模式误伤 `coding.verify`/构建 | 任务无法自检 | `read-build` 预设通过 `buildShell` 显式允许 `mvnBuild/verify/validate`；文档给推荐组合 |
| `READ_BUILD` 仍等于"可执行代码" | 用户以为已隔离 | 构建必然执行工程自身代码（Maven 插件/surefire/`build.rs`/npm scripts）；§4.1.1 与其文档条目写明"防误操作/防外泄，不防恶意执行"，`coding.permissions` 返回说明同口径 |
| `buildOnly` 误伤构建 | `mvn` 之外的工具被拦 | `BUILD_COMMANDS` 覆盖主流构建链；漏项按"补集合 + 单测"处理，不放宽到解释器 |
| 披露过滤改变现有 prompt | 模型可用工具变少 | 只在 `preset != FULL` 时过滤；默认路径与现状逐字节一致 |
| shell 敏感路径误伤 | 构建失败 | 只拦明确敏感目录；`~/.m2` 等白名单；未定义变量拒绝时给出可读原因；`pathGuard=off` 逃生 |
| `--fresh-workspace` 语义争议 | 用户预期落空 | 不改默认行为：prompt + 日志 + `coding.permissions.workspaceRoot` 明确"模块走 junction"；新增 `browser4.agent.workspace.mirror=off` 与 `--no-mirror` |
| MCP 直调旁路 | 策略被绕过 | E5 收口 + e2e 用例 6 作为回归门 |
| 批处理旁路 | 策略被绕过 | 现有链路已收敛到 E3（E7），e2e/单测断言锁定，防止将来批处理被改成独立路径 |
| 旧属性语义混淆 | 用户以为已只读 | 文档标注 `allowDestructive` 的边界；拒绝信息里直接给出 `access=read` 建议 |

回滚：策略对象默认 `FULL`，删掉 CLI 选项即可回到现状；M1 的工厂是纯重构，可独立 revert。

## 8. 文档更新清单（DoD 要求，全部要改）

1. `skills/browser4-cli/references/agent.md` — 新增"Permissions"章节（预设表 + 默认值 + 拒绝示例 + 敏感路径）。
2. `skills/browser4-cli/SKILL.md` — `agent run` 段落补 `--access` 与"默认 full"。
3. `cli/browser4-cli/src/help.rs` — `agent-run` 选项与样例。
4. `cli/browser4-cli/src/tips.rs` — 一条 tip：只读任务用 `--access read`。
5. `cli/browser4-cli/README.md`、`README.md`、`README.zh.md` — 命令表补选项。
6. `docs/config.md` — `browser4.agent.access` / `shell.pathGuard` / `workspace.mirror`。
7. `skills/browser4-coding/SKILL.md` — 受保护文件与敏感路径清单，以及"`read-build` 会执行工程自身构建代码"的口径。
8. `browser4-coding/.../CodingWorkspace.kt:29-33` 的 KDoc（源码注释也是文档）— 两个旧属性改用新策略描述并标注 deprecated。
9. `docs/TESTING.md` — 若新增测试标签（预计不需要）。

## 9. 待决策

1. `--access read` 下模型尝试写入，任务终态算 `failed` 还是 `done` + 拒绝记录？
   **结论：不改终态语义。** 终态仍由 `system.taskComplete`（及各引擎的完成信号）决定，拒绝只计入
   `permission.denied` 审计与状态计数。理由：一次被拒的写尝试不等于任务失败，模型收到 `PERMISSION_DENIED`
   后完全可以改用只读路径完成；判 `failed` 还与 P1-3「拒绝信息里给出开闸提示」自相矛盾（既然提示了怎么开闸，
   就该允许继续）。"模型无视拒绝、谎报已写入"由既有 finish gate 的目标核对（`filesChanged` 与真实变更比对）兜底，
   那是它本来就负责的事。M0 冻结此语义，§6 e2e 用例 1 按此断言。
2. `coding.permissions` 是否也暴露给 MCP 客户端（非 agent 场景）？（建议暴露，只读无副作用）
3. 敏感路径默认集合是否包含浏览器 profile 根？（建议包含；`browser.profile.mode=DEFAULT` 下 profile 内含 cookie）
4. 是否需要 `--no-mirror`，还是只做文档告知？（建议先做文档 + 日志，`--no-mirror` 放 P2 观察）

## 10. 参考

- `docs-dev/copilot/coding-run-isolation-design.md` §2.4/§5/§6（镜像与 junction 直写、白名单路径被否决的理由）
- `browser4-coding/.../CodingWorkspace.kt:29-33`（现有开关注释）
- `browser4-agentic/.../RobustBrowserAgent.kt:76-114`（任务自适应工具集）
- `browser4-agentic/.../ToolDisclosure.kt:88-218`（披露与扩展）
- `browser4-agentic/.../CodingToolExecutor.kt:1063-1069`（`mvnBuild` 复用 `receiver.shell`——§4.1.1 两个 shell 拆分的依据）
- `browser4-coding/.../CodingAgentFileSystem.kt:1454-1466`（`protect` 只能移除动态保护，治理文件常驻保护）

## 11. 实现状态（PR2 完成）

### 11.1 强制执行点

| # | 位置 | 状态 |
|---|---|---|
| E1 | `CodingAgentFileSystem.readOnly`，11 个 mutator 前置结构化拒绝 | ✅ |
| E2 | `CodingAgentShell.buildOnly` + `allowExecution` + 参数路径守卫 | ✅ |
| E3 | `CodingToolExecutor.callFunctionOn` 方法级 allowlist（`policy.denial`），`protect(path=…)` 额外走 `mutationDenial` | ✅ |
| E4 | `RobustBrowserAgent.buildCliEngineToolSet(policy=…)` 在构造 registry 前过滤 | ✅（调用点已传 `agentToolManager.accessPolicy`） |
| E5 | `MCPToolController.standaloneCodingTarget` 走 `CodingToolExecutor.target(...)`，target 自带策略 | ✅ |
| E6 | `AgentToolManager.applyAccessPolicy/resetAccessPolicy`；`StatefulAgentRunner` 在 `runMutex` 内应用、`finally` 复位 | ✅ |
| E7 | 批处理/单发/MCP 直调共用 `callFunctionOn`，无新增代码 | ✅ |

拒绝统一抛 `ToolAccessDeniedException`（携带 `domain`/`method`/`preset`），`AbstractToolExecutor`
把它包装成失败的 `TcEvaluate` 并写入 `PermissionAudit`（`code=TOOL_DENIED` / `EXEC_DISABLED`）。

### 11.2 与计划的偏离（均已落地并有测试）

1. **`buildShell` 不返回 `null`**：`Target.shell` 必须非空（三处构造点都要实例），因此 READ 下
   仍构造一个 `buildOnly` 的构建 shell；`mvnBuild`/`verify`/`validate` 由 E3 拒绝，构建 shell 不可达。
   这比"READ 返回 null"少一条空指针路径，语义上仍由策略单点决定。
2. **`target(...)` 工厂放在 `CodingToolExecutor.companion`**，不放在 `CodingComponents`：
   `CodingComponents` 在 `browser4-coding`，不能依赖 `browser4-agentic` 的 `Target`。
   `CodingComponents` 仍负责组件构造，`CodingToolExecutor.target` 负责把三者与策略组装成 `Target`。
3. **`inlineScript` 增加 wrapper 检查**：原实现只校验 `sh -c "…"` 的脚本体，wrapper 本身不校验，
   `buildOnly` 下会放行 `sh -c "mvn …"`。现在 build-only 档位要求 wrapper 也在 `BUILD_COMMANDS` 中。
4. **`Target.buildShell` 可空**（默认等于 `shell`）：保留既有测试与三参构造的兼容性，
   FULL 下 `shell == buildShell`（单实例，与改造前逐字节一致）。
5. **per-task 只接线了 `access`**：`allowExternalAccess` / `allowDestructive` 仍由 JVM 属性投影
   （`base.copy(preset = …)` 保留旧属性值），未新增 CLI 选项，避免与本轮范围混在一起。
6. **`skill` 域进入 CLI 引擎工具集**，读方法按 `read`、`run`/`runScript` 按 `full`；
   方法清单由 `AgentAccessPolicy.gateOf(domain, method)` 判定，未分类方法 fail closed。
   部署方法 `install`/`uninstall`/`reload` 由 `CLI_ENGINE_EXCLUDED_METHODS` 在**所有**档位下
   直接在构造 registry 前剔除（`b4.run` 也拒绝同一批命令），`exposeTools` 无法复活。
7. **`b4.run` 的写路径**：`externalPathRefusal` 除 `file://` 与 `upload` 外，还扫描
   `--output-file` / `--out` / `-o` / `har stop --path`，这些位置 CLI 直接写盘，
   工作区外同样报 `PATH_OUTSIDE_WORKSPACE`。
8. **`b4.run` 的 preset 越界（P3）**：`code` / `tool call` / `plugin` 由 CLI 子进程回打后端，
   子进程不带 preset（`CliProcessSpec.sessionId` 仅用于并发信号量），请求只能落到服务级策略。
   因此非 `full` 档位下 `b4CliPolicyRefusal` 在派发前拒绝这三类命令（`COMMAND_NOT_ALLOWED`）；
   同时 `b4CliLifecycleRefusal` 覆盖 `tool call skill_install|skill_uninstall|skill_reload`
   （spaced / kebab / `skill.` 点号写法），堵住 `skill <verb>` 黑名单的旁路。
9. **MCP 直调的 skill 管理（P5）**：`SkillMCPToolExecutor` 增加 `accessPolicy` 供体，在
   `callFunctionOn` 入口 `denial("skill", method)`；`SKILL_METHOD_GATES` 补齐 `info → READ`、
   `reload → EXEC`。MCP 请求没有 per-task preset，因此这里与 E5 一样取服务级策略。
10. **`.b4inputs/` 在所有档位物化（P4）**：`TaskInputs.materialize` 去掉 `allowCopy` 参数，
    `RobustBrowserAgent.materializeTaskInputs` 无条件复制；`read` 档不再跳过导入。
    依据：`.b4inputs/` 是平台的任务输入沙箱，不是用户文件/工程；`read` 的承诺是"不改用户文件
    与工程"，只读任务拿不到自己点名的输入文件只会直接失败，而复制不触碰该承诺保护的任何东西
    （模型自身的读取与写入仍受只读 coding 工具约束）。文档措辞同步改为"输入物化在所有档位执行"。

### 11.3 测试

- `AgentAccessPolicyTest`、`CodingToolMatrixCoverageTest`（矩阵完备性）
- `CodingAgentShellBuildOnlyTest`（构建链放行、解释器/inline wrapper/显式路径/网络拒绝、工具清单收窄、`allowExecution=false`）
- `CodingAgentFileSystemReadOnlyTest`（全部 mutator 拒绝、读仍可用）
- `CodingAccessEnforcementTest`（每个 preset 的端到端拒绝、`read-build` 双 shell 拆分、
  `protect` 只读形态可用而写形态被拒、`denyTools` 在 FULL 下生效）
- `RobustBrowserAgentTest.deniedCodingMethodStaysOutOfEveryView`（E4：registry/初始/披露三视图均无被禁方法）
- `RobustBrowserAgentTest` 的 skill 三例（域可见/read 下只能读/部署方法任意档位不可见）
- `B4CliExternalPathTest`（`file://`、`upload` 与 `--output-file`/`-o`/`har stop --path` 的写路径越界）
- `B4CliPolicyGuardTest`（`full` 放行 `code`/`tool call`/`plugin`，`read`/`read-build`/`write` 拒绝且点名 preset）
- `B4CliLifecycleGuardTest.skillManagementIsRefused`（`tool call skill_install` 等 passthrough 旁路）
- `SkillMCPToolExecutorPolicyTest`（MCP 直调在 `read` 档拒绝 install/uninstall/reload，`list` 仍可用）
- `TaskInputsTest`（任务输入在任意档位物化：仓库相对/绝对路径复制、同名去重、上限、敏感路径不复制；
  不再有"`read` 下跳过"的用例）
- CLI 单测：`build_agent_run_extra_forwards_only_a_non_blank_access_preset`

### 11.4 M6 覆盖情况

CLI 侧（mock 后端）已实现，断言的是 CLI 的转发契约：

| 场景 | 文件 |
|---|---|
| `--access read-build` 进入 `command_run` payload，且 `coding` 同时被转发 | `tests/e2e/scenarios/agent_run.rs::test_agent_run_forwards_access_preset` |
| 未传 `--access` 时 payload **不含** `access` 键（避免覆盖服务级默认） | `..._omits_access_when_absent` |
| `agent run -i --access read` 的交互任务同样携带预设 | `..._interactive_forwards_access_preset` |

MCP 直调侧：`CommandToolExecutorTest.runForwardsAccessPreset`（合法预设透传到 `submitPlainCommand`）
与 `runRejectsUnknownAccessPreset`（未知值报错且不提交任务，避免"拼错即全权"）。

**未做**：§6 里依赖真实后端行为的 4 条（`read` 下写文件被拒、`exposeTools` 返回 unknown、
per-task 复位、`read-build` 下 `mvnBuild` 成功而 `shell` 被拒）。这些在单元层已有等价断言
（`CodingAccessEnforcementTest`、`RobustBrowserAgentTest`、`WorkspaceIsolationTest` 等），
E2E 版需要一个带真实木偶后端 + `FileBackedChatModel` 的运行环境，收益低于成本，暂缓。

**未做**：`AgentTaskStatus.access/deniedCalls` 字段仍未回填（`PermissionAudit` 已支持快照差分，
但没有调用方在任务边界写回状态）；`CodingToolExecutor`/`PermissionAudit` 的 KDoc 已按现状修正，
不再声称状态里有这两个字段。

### 11.5 M7 文档（已完成）

| 文件 | 内容 |
|---|---|
| `skills/browser4-cli/references/agent.md` | 新增 `### Permissions (`--access`)`（预设表、拒绝样例、拒绝码、威胁模型、per-task 复位）；`--access` 进 Flags 表；错误表补 `PERMISSION_DENIED` 行 |
| `skills/browser4-cli/SKILL.md` | Agent Task Lifecycle 第 10 条补 `--access` 与 `read-build` 语义 |
| `cli/browser4-cli/src/help.rs` | `agent run` Notes 补 `--access` 三行 + 一条样例 |
| `cli/browser4-cli/src/tips.rs` | 新增一条 tip（`read-build` 只读+构建、`write` 可写但无 shell） |
| `cli/browser4-cli/README.md`、`README.md`、`README.zh.md` | `agent run` 行补 `--access` |
| `docs/config.md` | 新增 `browser4.agent.access`（预设表 + 拒绝格式 + 非沙箱声明）、旧属性说明（`allowExternalAccess`/`allowDestructive`/`shell.pathGuard`）、`browser4.agent.workspace`（junction 直写真实检出的显式告知）与 `workspace.isolation` |
| `skills/browser4-coding/SKILL.md` | 新增 `## Permissions (access presets)`（矩阵、设置方式、`coding.permissions`、拒绝码、受保护目录/文件、敏感路径、非沙箱口径） |

`--no-mirror` 未实现（§9 决策为"先做文档告知，`--no-mirror` 放 P2"），因此文档只写 junction 事实，
不写不存在的开关；`browser4.agent.workspace.mirror` 同样未落地，§8 第 6 项据此收敛为
`browser4.agent.workspace`。

### 11.6 已验证（本轮）

| 范围 | 命令 | 结果 |
|---|---|---|
| `browser4-coding` | `mvn -pl browser4-coding -am test -Dtest=AgentAccessPolicyTest,CodingAgentShellBuildOnlyTest,CodingAgentFileSystemReadOnlyTest,CodingAgentShellPathGuardTest,SensitivePathGuardTest,CodingAgentFileSystemWritePathTest` | 全绿 |
| `browser4-agentic` | `mvn -pl browser4-agentic -am test`（整模块） | 全绿（含 `CodingAccessEnforcementTest`、`CodingToolMatrixCoverageTest`、`RobustBrowserAgentTest`） |
| `browser4-rest` | `mvn -DskipTests -pl browser4-rest -am compile` | 通过 |
| CLI | `cargo test --bin browser4-cli` | 1956 passed / 0 failed / 4 ignored |

新增 `coding.permissions` 后，`CodingToolExecutorTest` 的硬编码快照同步更新为 55 个方法
（并在期望方法集里补 `permissions`）。注意：该测试类全部用例都在 `@Nested` 内部类中，
surefire 报告会显示 `tests="0"` 但 `<testcase>` 条目齐全，判断结果以 `failures`/退出码为准。

### 11.7 自动权限审批（增量）

需求「支持自动权限审批」的落地形态为**上限式自动审批（ceiling）**：不是交互式确认，也不是
规则 allowlist，而是给预设一个「可自动批准的宽度上限」，被拒的调用在「授予它所需的预设不宽于
该上限」时自动放行。默认关闭，行为与改造前完全一致。

| 位置 | 改动 |
|---|---|
| `AgentAccessPolicy` | 新增 `autoApproveUpTo: AccessPreset? = null`；`allowsTool` / `allowsFileMutation` / `allowsExec` 三处判定统一走 `presetAllows(required) \|\| autoApproves(required)`，因此 E1（文件系统）、E2（shell/buildShell）、E3（方法闸门）对同一个调用给出同一答案 |
| `AgentAccessPolicy.denial()` | 三层判定：`denyTools` 硬拒绝 → 预设放行 → 自动审批（写 `AUTO_APPROVED` 审计后放行）→ 否则拒绝；拒绝 hint 给出 `autoApprove=<required.id>` 的自救路径 |
| `AgentAccessPolicy.serverDefault` | 新增 `browser4.agent.autoApprove`，空值=关闭，未知值告警后关闭（fail-closed，不回落到 `full`） |
| `PermissionAudit` | 新增 `CODE_AUTO_APPROVED` 与 `approved(tool, required, ceiling)`，按 tool 去重后记一条 `coding access auto-approved: tool=… required=… ceiling=…` |
| `CodingToolExecutor.describePermissions` | 输出 `Automatic approval: up to '<ceiling>' …` 或 `off …`，并声明受保护/敏感路径不在审批范围 |
| `CommandToolExecutor` → `UserCommandExecutor` → `StatefulAgentRunner` | 新增 `autoApprove` 参数（spec、白名单、未知值 `require` 报错），在任务的 `runMutex` 内与 `access` 一起 `applyAccessPolicy`，任务结束随 `finally` 复位。合并逻辑收口到 `AgentAccessPolicy.withTaskOverrides(preset, autoApproveUpTo)`：任务未指定的那一项继承服务端默认（早期实现直接 `autoApproveUpTo = autoApprove`，会把服务端的 `browser4.agent.autoApprove` 抹成 off，与文档承诺不符，已修） |
| CLI | `agent run --auto-approve <preset>` → `build_agent_run_extra` → payload `autoApprove`（空值不写）；交互会话经 `InteractiveAgentSession.auto_approve` 透传 |

**不变量**：`denyTools` 永不被自动审批覆盖；受保护目录/文件（`.browser4`、`.github/workflows`、
治理文件）与敏感路径（凭据）不在审批范围；每次自动批准留审计；`read-build` 上限只放行构建，
不放行 mutation，`write` 上限只放行 mutation，不放行 shell。判定用 `required.ordinal >=
ceiling.ordinal`（枚举顺序即从宽到严）。

**验证**

| 范围 | 命令 | 结果 |
|---|---|---|
| `browser4-coding` | `mvn -q -pl browser4-coding test -Dtest=AgentAccessPolicyTest` | 全绿（16 条，含 `testAutoApprovalWidensUpToCeiling`、`testAutoApprovalNeverOverridesDenyTools`、`testAutoApprovalDefaultsOff`、`testTaskOverridesInheritServerDefaults`） |
| `browser4-agentic` | `mvn -q -pl browser4-agentic -am test -Dtest=CodingAccessEnforcementTest,CodingToolMatrixCoverageTest,CliAgentPromptTest,CodingToolExecutorTest,RobustBrowserAgentTest` | 全绿（含 `testAutoApprovalReachesEveryLayer`、`testAutoApprovalFullGrantsShell`；`CodingToolExecutorTest` 的 73 条用例全在 `@Nested`，按 XML 计） |
| `browser4-agent-tools` | `mvn -q -pl browser4-agent-tools -am test -Dtest=StatefulAgentRunnerTest,AgentRunnerPersistenceTest,AgentTaskStatusTest` | 全绿（23 / 8 / 22） |
| `browser4-rest` | `mvn -q -pl browser4-rest -am test -Dtest=CommandToolExecutorTest` | 全绿（含 `runForwardsAutoApproveCeiling`、`runRejectsUnknownAutoApproveCeiling`） |
| CLI 单测 | `cargo test --quiet --bin browser4-cli` | 1957 passed / 0 failed / 4 ignored |
| CLI e2e | `cargo test --quiet --test e2e -- --scenario '*auto_approve*'` | 2 passed（`agent run` 与交互会话各一条） |


### 11.8 评审修复（第三轮，2026-10-27）

代码评审发现 4 个 P1 与若干 P2，已全部修复：

| # | 问题 | 修复 | 回归测试 |
|---|---|---|---|
| P1-1 | 受保护目录（`.browser4`、`.github/workflows`）只在文件工具层拦截，`coding.shell` 可 `echo forged > .browser4/data/agent/agent-tasks.jsonl` 直接写穿（默认 `full` 下实测可写） | `CodingAgentShell` 新增 `protectedDirs` 与 `protectedPathViolation`：参数、重定向目标（`>`、`2>>`、`&>`、无空格 `>file`）、glob（`.browser4/*`）与 `workingDir` 全部拒绝；`pathGuard=off` 不解除该档 | `CodingAgentShellPathGuardTest.browser4WriteRefused / workflowsWriteRefused / protectedGuardSurvivesPathGuardOff / protectedWorkingDirRefused`；`CodingAgentFileSystemWritePathTest.mkdirInsideProtectedDirRefused / protectedDirThroughLinkRefused` |
| P1-2 | per-task `--access` 不进系统提示：`RobustBrowserAgent.cliAgentSystemPrompt` 调 `CliAgentPrompt.hygiene` 时没传 policy，`permissionsBlock` 只看服务端默认 | 传入 `agentToolManager.accessPolicy` | `CliAgentPromptTest.permissionsBlockFollowsThePolicy / permissionsBlockReflectsAutoApproval / permissionsBlockAppearsForDenyTools` |
| P1-3 | 敏感路径/受保护目录的"工作区豁免"按词法前缀短路，workspace 内指向凭据目录的 symlink/junction 可读可写（实测 `readFile("link/id_rsa")` 返回私钥） | `resolvePath` 的工作区豁免改为「词法在内 **且** real path 也在内」；`protectionViolation` 用 real path 计算相对路径（模块 junction 仍按词法，保持 `--fresh-workspace` 语义） | `SensitivePathGuardTest.workspaceLinkToCredentialRefused`；`CodingAgentFileSystemWritePathTest.protectedDirThroughLinkRefused` |
| P1-4 | 引号内命令替换绕过参数守卫：`echo "$(cat ~/.ssh/id_rsa)"` 放行（`splitSimpleCommands` 在引号内不切分） | `CodingAgentShell.substitutionViolation`：单引号按字面量、双引号/未引用的 `$(...)`/反引号递归走 `validateSegments`（算术 `$((...))` 跳过），顺带补上了"引号内替换不受 allow-list 约束"的旧洞 | `CodingAgentShellPathGuardTest.quotedSubstitutionRefused / quotedSubstitutionOfSafeCommandAllowed / singleQuotedSubstitutionIsLiteral / arithmeticExpansionAllowed` |
| P2-1 | `coding.permissions` 与 prompt 的 "project build" 行按 `preset != READ` 判断，忽略自动审批上限（`read` + `write` 上限时 `mvnBuild` 实际放行，报告却写 NO） | 两处改用 `policy.allowsTool(domain, "mvnBuild")` | `CodingAccessEnforcementTest.testPermissionsReport`、`CliAgentPromptTest.permissionsBlockReflectsAutoApproval` |
| P2-2 | 文档的 ceiling 表说 `write` 上限只放行 mutation，实际按 ordinal 规则也放行更窄的 build | `docs/config.md`、`references/agent.md`、`skills/browser4-coding/SKILL.md` 表述改为「`write` = mutation + build」 | 文档 |
| P2-3 | 拒绝文本无法被 `FailureCategory` 识别（注释声称按前缀分类，实际只匹配 `"permission denied"` 带空格） | `IntentModels.FailureCategory.classify` 增加 `permission_denied` / `not permitted` 关键字 | `ExperienceModelsTest.testStructuredPermissionRefusal`（5 个 code 全覆盖） |
| P2-4 | 自动审批的 `coding.protect(path=…)` 不产生 `AUTO_APPROVED` 审计 | `mutationDenial` 在「预设不放行、上限放行」时记 `approved(...)` | `CodingAccessEnforcementTest.testAutoApprovedProtectIsAudited` |
| P2-5 | `mkdir` 少了 `protectionViolation`，可在受保护目录里建目录 | 补上同一道检查 | `CodingAgentFileSystemWritePathTest.mkdirInsideProtectedDirRefused` |
| P2-6 | `coding.permissions` 的 `Protections:` 只列动态保护，默认受保护目录/文件与凭据集不显示 | 输出 `protected files / protected dirs / credential paths / session policies` 四行 | `CodingAccessEnforcementTest.testPermissionsReport` |
| P2-7 | `denyTools=["*"]` 会把 `system.*`（含 `taskComplete`）一起过滤掉，任务无法收尾 | 策略过滤只作用于非 system 规格，`systemSpecs` 恒在 | `RobustBrowserAgentTest`（既有） |
| P2-8 | 未知 `browser4.agent.access` / `autoApprove` 每次读取都 warn | `warnedOnce` 去重，每个坏值只告警一次 | `AgentAccessPolicyTest`（既有） |
| 增量 | 嵌套拦截的审计码不够精确：`command substitution rejected — path guard: …` 以 `command substitution` 开头，`commandRefusal` 的 `startsWith` 判定把它归成 `COMMAND_NOT_ALLOWED` | 判定改为 `contains`（受保护路径同理），凭据类拒绝仍报 `COMMAND_PATH_DENIED` | `CodingAgentShellPathGuardTest.quotedSubstitutionRefused` 断言 `code=COMMAND_PATH_DENIED` |
| P3 | `denial()` 里 `preset == FULL` 分支不可达；`AccessPreset.parse` KDoc 声称接受 `readbuild`；`unresolvedPath` 提示对敏感路径误导；`SensitivePathGuardTest` 弱断言；只读测试缺 `replace/replaceRegex` | 删死分支、修 KDoc、改提示、加强断言、补用例 | 见上方各测试类 |

**验证（本轮）**

| 范围 | 命令 | 结果 |
|---|---|---|
| `browser4-coding` | `mvn -o -pl browser4-coding test -Djacoco.skip=true`（整模块） | 470 passed / 0 failed |
| `browser4-agentic` | `mvn -o -pl browser4-agentic test -Djacoco.skip=true`（整模块） | 见 §11.9 |
| CLI 单测 | `cargo test --bin browser4-cli build_agent_run_extra` | 3 passed（未改动 CLI 代码） |

注：评审中"`CodingToolExecutorTest` 的 `@DisplayName` 仍写 51"一说不成立——该显示名在同一次改动里已改成 55，复查 `git diff` 确认。

### 11.9 已知残留

- `--access` 的 per-task 复位只有 CLI 转发契约的 e2e，真实后端的"任务结束后复位"仍靠 `StatefulAgentRunner` 的 `finally` 单测与代码审查（§11.4 同口径）。
- `AgentTaskStatus.access/deniedCalls` 仍未回填，`PermissionAudit.snapshot()` 目前只有测试消费。
- shell 的受保护路径检查是"命令里出现即拒绝"（读写都拒），比文件工具的"只拒写"更严；模型要读 `.browser4` 下的日志需改用 `coding.read`。
