# `agent run` 读写权限优化计划

> 状态：待评审 · 版本：v1 · 范围：内置智能体 `agent run`（CLI 引擎）的文件系统、Shell 与工具暴露权限
> 依据：`browser4-coding` / `browser4-agentic` / `browser4-rest` 现状代码（2026-10-27 分支）

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
| P1-1 | 可用性 | 无 per-task / per-session 权限 | 仅 `CodingWorkspace` 读 JVM 属性 | 同一 backend 上多任务无法差异化授权 |
| P1-2 | 可用性 | 模型与用户都看不到"当前权限" | 无 `coding.permissions`；`AgentTaskStatus` 只有 `workspace` | 模型反复尝试被拒操作；用户无法确认沙箱边界 |
| P1-3 | 可用性 | 拒绝语义是自由文本 | `errorResult("Path not resolved: …")` | CLI/上层无法区分"路径越界"与"文件不存在"，也无法给出开闸提示 |
| P1-4 | 文档 | `agent.md` 无权限章节 | `skills/browser4-cli/references/agent.md:61-97` 只讲 workspace 位置 | 用户按文档无法推断能读什么、能写什么 |
| P2-1 | 工程 | 三个构造点重复策略 | `AgentToolManager.kt:316-327`、`CodingToolExecutor.kt:1879-1891`、`MCPToolController.kt:451-470` | 新增开关必然漂移 |
| P2-2 | 工程 | 无权限矩阵测试 | 现有 FS/Shell 单测按功能切分 | 新增策略组合没有回归网 |

## 3. 目标与非目标

**目标**

1. 新增统一策略对象 `AgentAccessPolicy`，作为**唯一事实来源**，由文件系统、Shell、工具注册、MCP 直调四个入口共同消费。
2. 提供真实可用的预设：`full`（默认）/ `write` / `read-build` / `read`。
3. 让"工具不可见"升级为"工具不可用"：披露、批量、MCP `tool call` 三条路径都被同一策略拦截。
4. 策略可 per-task 覆盖（`command.run` 参数 + CLI 选项），并在任务结束时复位。
5. 权限可观测：`coding.permissions` 工具、任务状态字段、一条结构化拒绝审计。
6. 文档与测试同步，默认行为零变化。

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
    fun allowsMutation(): Boolean   // preset != READ && preset != READ_BUILD
    fun allowsExec(): Boolean       // preset == FULL || preset == WRITE
}
```

预设 → 工具矩阵（`coding.*`）：

| 方法族 | FULL | WRITE | READ_BUILD | READ |
|---|---|---|---|---|
| `read/readLines/checksum/listDir/glob/grep/stat/diff/changeSummary/languages/workspaceRoot` | ✓ | ✓ | ✓ | ✓ |
| `symbols/references/kt*/impact/moduleGraph/diagnostics/lspServers/tokenStats` | ✓ | ✓ | ✓ | ✓ |
| `mvnBuild/verify/validate` | ✓ | ✓ | ✓ | ✗ |
| `write/append/replace/replaceRegex/editLines/insertAfter/delete/mkdir/copy/move/revert/scaffold*/devTask/resumeDevTask/trapCheck` | ✓ | ✓ | ✗ | ✗ |
| `shell/shellOutput/shellStatus/shellList/shellSetEnv/runCode` | ✓ | ✗ | ✗ | ✗ |

策略解析优先级（高 → 低）：

1. 任务参数 `command.run(access=…, allowExternalAccess=…, allowDestructive=…)`
2. 会话配置（`session.sessionConfig["browser4.agent.access"]`）
3. JVM 属性 `browser4.agent.access`（默认 `full`）
4. 兼容旧属性：`browser4.agent.allowExternalAccess`（默认 `false`）、`browser4.agent.allowDestructive`（默认 `true`）

`allowDestructive=false` 保持旧语义（只关 `delete/copy/move`），但文档明确标注它**不是只读开关**，推荐迁移到 `access`。

### 4.2 强制执行点（纵深，不依赖上层自觉）

| # | 位置 | 作用 |
|---|---|---|
| E1 | `CodingAgentFileSystem` 新增 `readOnly` / `sensitiveReadPaths` | 最低层硬拦：所有 mutator 提前返回结构化拒绝；读敏感路径直接拒绝 |
| E2 | `CodingAgentShell` 新增 `policy` | `allowsExec()==false` 时 `shell` 整体拒绝；参数路径命中敏感集时拒绝 |
| E3 | `CodingToolExecutor.callFunctionOn`（`:804`） | 方法级 allowlist：兜住 `runCode`/`devTask`/`mvnBuild`/`scaffold*` 等不经过 FS 的写入口 |
| E4 | `buildCliEngineToolSet`（`RobustBrowserAgent.kt:170`） | 构造 registry 时过滤被禁方法 → `system.listTools`/`exposeTools` 无法复活 |
| E5 | `MCPToolController.standaloneCodingTarget`（`:451-470`） | MCP `tool call` 直调同一策略，不允许旁路 |
| E6 | `StatefulAgentRunner`（`:587-618` / `finally:687-718`） | 注入并复位 per-task 策略，避免 companion agent 串味 |

E4 是"披露绕过"的正解：被禁工具根本不在披露注册表里，`exposeTools` 只会返回 `unknown`。

### 4.3 消除三处构造重复

新增工厂（`browser4-coding` 或 `browser4-agentic` 的公共处）：

```kotlin
object CodingComponents {
    fun shell(root: Path, policy: AgentAccessPolicy): CodingAgentShell
    fun fs(root: Path, policy: AgentAccessPolicy): CodingAgentFileSystem
    fun target(root: Path, policy: AgentAccessPolicy): CodingToolExecutor.Target
}
```

`AgentToolManager`、`CodingToolExecutor.targetAt`、`MCPToolController` 全部改走工厂；新增开关只改一处。

### 4.4 敏感读路径（默认开启，低误伤）

默认集合：`~/.ssh`、`~/.aws`、`~/.gnupg`、`~/.config/gcloud`、`~/.browser4`（含 `conf-enabled` 里的 LLM key）、
浏览器 profile 根（`browser.profile.mode` 解析结果）。构建/包管理目录显式放行：`~/.m2`、`~/.gradle`、`~/.cargo`、`~/.npm`、系统临时目录。

Shell 侧只做**参数路径启发式**：token 形如绝对路径或 `~` 开头，且解析后命中敏感集合才拒绝；其余仍放行（避免破坏 `mvn`/`git`）。
`-Dbrowser4.agent.shell.pathGuard=off` 可关闭。

### 4.5 可观测性

- 新工具 `coding.permissions`（只读，永远可用）返回 JSON：`preset / workspaceRoot / allowExternalAccess / allowDestructive / allowNetwork / deniedTools / protectedFiles / mirror{linked,copied,failed}`。
- `CliAgentPrompt.hygiene()` 追加一行权限摘要（模型第一轮就知道边界，减少无效尝试）。
- `AgentTaskStatus` 增加 `access` 字段（`agent status`/`agent result` 输出），默认值不破坏旧客户端（Jackson 忽略未知字段的旧客户端不受影响）。
- 拒绝审计：结构化事件 `permission.denied {taskId, tool, path, reason}`，每任务计数进状态；日志级别 info，同一 `(tool,reason)` 每任务只打一次，避免刷屏。

## 5. 里程碑

| 里程碑 | 内容 | 主要文件 | 工作量 | 验收 |
|---|---|---|---|---|
| M0 | 基线与设计冻结：补权限矩阵测试骨架，记录当前行为快照 | `browser4-coding/src/test/...` | 0.5d | 现有测试全绿；矩阵测试先全部标 `@Disabled` 并注明本计划 |
| M1 | `AgentAccessPolicy` + `CodingComponents` 工厂 + 三处构造点收口 | `browser4-coding/.../AgentAccessPolicy.kt`、`CodingComponents.kt`、`AgentToolManager.kt:316`、`CodingToolExecutor.kt:1879`、`MCPToolController.kt:451` | 1.0d | 策略解析单测（4 级优先级）；`full` 下行为与基线完全一致 |
| M2 | 只读/禁执行强制执行（E1–E5） | `CodingAgentFileSystem.kt`、`CodingAgentShell.kt`、`CodingToolExecutor.kt:804`、`RobustBrowserAgent.kt:170` | 1.5d | 每个 preset 的矩阵测试；`exposeTools` 无法启用被禁方法；MCP 直调同样被拒 |
| M3 | 受保护文件扩展 + 敏感路径 + 审计事件 | `CodingAgentFileSystem.kt:94/1454`、`CodingAgentShell.kt:620` | 1.0d | 写 `.git/config`、`settings.xml`、`AGENTS.md` 被拒；读 `~/.ssh/id_rsa` 被拒；构建目录不误伤 |
| M4 | per-task / per-session 覆盖 + CLI `--access` / `--allow-external` / `--allow-destructive` | `commands.rs:4025`、`CommandToolExecutor.kt:46`、`StatefulAgentRunner.kt:603`、`AgentToolManager.kt` | 1.0d | `agent run --access read` 端到端生效并在任务结束后复位；缺省不改变 payload |
| M5 | 可观测性：`coding.permissions`、prompt 摘要、状态字段、拒绝审计 | `CodingToolExecutor.kt`、`CliAgentPrompt.kt`、`AgentTaskStatus` | 0.5d | `agent result` 显示 `access`；`coding.permissions` 与真实策略一致 |
| M6 | 测试补齐（单测 + e2e） | 见 §6 | 1.5d | 见 §6 验收 |
| M7 | 文档与技能同步 | 见 §8 | 0.5d | 文档清单逐项勾掉 |

合计约 **8 人日**，拆 3 个 PR：PR1 = M0+M1（无行为变化）、PR2 = M2+M3（安全基座）、PR3 = M4~M7（可用性与文档）。

## 6. 测试计划

**单元 / 组件（`browser4-coding`、`browser4-agentic`）**

- `AgentAccessPolicyTest`：4 级优先级、preset 到方法矩阵的映射、旧属性兼容。
- `CodingAgentFileSystemAccessTest`：`read`/`read-build` 下全部 mutator 拒绝且错误码为 `PERMISSION_DENIED`；读仍可用；`write` 下 `delete` 按 `allowDestructive` 生效。
- `CodingAgentShellPolicyTest`：`allowsExec=false` 时 `shell` 拒绝；敏感路径参数拒绝；`~/.m2` 放行；`pathGuard=off` 生效。
- `CodingToolExecutorPolicyTest`：`runCode`/`devTask`/`mvnBuild`/`scaffold*` 在只读下被拒；批量步骤中混入被禁方法时整批失败且错误指明第几步。
- `ToolDisclosurePolicyTest`：`buildCliEngineToolSet(policy=READ)` 产出的 registry 不含写/执行方法；`exposeToolsResult` 对这些名字返回 `unknown`。
- `ProtectedFilesTest`：扩展后的清单（`.git/config`、`settings.xml`、`.github/workflows/*`）逐个验证写入/删除被拒，模块 `pom.xml` 仍可改。
- 命名遵循 `<Name>Test.kt` + camelCase + `@DisplayName`（AGENTS.md 约定），打 `Unit`/`Fast` 标签。

**E2E（`cli/browser4-cli/tests/e2e/scenarios/agent_run_permissions.rs`，接 mock 后端 + `FileBackedChatModel`）**

1. `--access read` 任务让模型调用 `coding.write` → 结果里出现 `PERMISSION_DENIED`，`agent status` 为 failed（或按实现为 done-with-denial，需在 M0 冻结语义）。
2. 默认（无选项）同任务 → 文件写入 workspace，`agent result` 的 `filesChanged` 含该文件。
3. `--access read` 下 `system.exposeTools(["coding_shell"])` → 返回 skipped/unknown，后续 shell 调用不存在。
4. `--allow-external` 下读 `%TEMP%` 文件成功；缺省时被拒。
5. 任务结束后紧随一个默认任务 → 仍能写（验证 per-task 覆盖已复位）。
6. `command.run(access="read")` 的 MCP 直调路径与 CLI 行为一致（`MCPToolControllerE2ETest`）。

**验收命令**

```bash
mvn test -pl browser4-coding -am -Dtest='CodingAgent*Test,AgentAccessPolicyTest,ProtectedFilesTest'
mvn test -pl browser4-agentic -am -Dtest='CodingToolExecutor*Test,ToolDisclosure*Test'
./mvnw -DskipTests
cargo test --test e2e -- --scenario='test_e2e_agent_run_permissions_*'
./b4w.ps1 code verify --module browser4-coding     # 结构化 VERDICT_JSON
```

## 7. 风险与回滚

| 风险 | 影响 | 缓解 |
|---|---|---|
| 只读模式误伤 `coding.verify`/构建 | 任务无法自检 | `read-build` 预设显式允许 `mvnBuild/verify/validate`；文档给推荐组合 |
| 披露过滤改变现有 prompt | 模型可用工具变少 | 只在 `preset != FULL` 时过滤；默认路径与现状逐字节一致 |
| shell 敏感路径误伤 | 构建失败 | 只拦明确敏感目录；`~/.m2` 等白名单；`pathGuard=off` 逃生 |
| `--fresh-workspace` 语义争议 | 用户预期落空 | 不改默认行为：prompt + 日志 + `coding.permissions.workspaceRoot` 明确"模块走 junction"；新增 `browser4.agent.workspace.mirror=off` 与 `--no-mirror` |
| MCP 直调旁路 | 策略被绕过 | E5 收口 + e2e 用例 6 作为回归门 |
| 旧属性语义混淆 | 用户以为已只读 | 文档标注 `allowDestructive` 的边界；拒绝信息里直接给出 `access=read` 建议 |

回滚：策略对象默认 `FULL`，删掉 CLI 选项即可回到现状；M1 的工厂是纯重构，可独立 revert。

## 8. 文档更新清单（DoD 要求，全部要改）

1. `skills/browser4-cli/references/agent.md` — 新增"Permissions"章节（预设表 + 默认值 + 拒绝示例 + 敏感路径）。
2. `skills/browser4-cli/SKILL.md` — `agent run` 段落补 `--access` 与"默认 full"。
3. `cli/browser4-cli/src/help.rs` — `agent-run` 选项与样例。
4. `cli/browser4-cli/src/tips.rs` — 一条 tip：只读任务用 `--access read`。
5. `cli/browser4-cli/README.md`、`README.md`、`README.zh.md` — 命令表补选项。
6. `docs/config.md` — `browser4.agent.access` / `shell.pathGuard` / `workspace.mirror`。
7. `skills/browser4-coding/SKILL.md` — 受保护文件与敏感路径清单。
8. `docs/TESTING.md` — 若新增测试标签（预计不需要）。

## 9. 待决策

1. `--access read` 下模型尝试写入，任务终态算 `failed` 还是 `done` + 拒绝记录？（建议 `failed`，与现有失败语义一致，M0 冻结）
2. `coding.permissions` 是否也暴露给 MCP 客户端（非 agent 场景）？（建议暴露，只读无副作用）
3. 敏感路径默认集合是否包含浏览器 profile 根？（建议包含；`browser.profile.mode=DEFAULT` 下 profile 内含 cookie）
4. 是否需要 `--no-mirror`，还是只做文档告知？（建议先做文档 + 日志，`--no-mirror` 放 P2 观察）

## 10. 参考

- `docs-dev/copilot/coding-run-isolation-design.md` §2.4/§5/§6（镜像与 junction 直写、白名单路径被否决的理由）
- `browser4-coding/.../CodingWorkspace.kt:29-33`（现有开关注释）
- `browser4-agentic/.../RobustBrowserAgent.kt:76-114`（任务自适应工具集）
- `browser4-agentic/.../ToolDisclosure.kt:88-218`（披露与扩展）
