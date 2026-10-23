# 编码任务的工作区隔离与 shell 沙箱设计

> 状态：设计稿 v0.3（未实现）· 日期：2026-10-23（同日评审后修订；v0.2→v0.3 修 manifest 写点时序、异常穿越 catch-all、filesChanged 公式等，见附录 C R13–R19）
> 依据：2026-10-22 `agent run "重构 Browser4WebDriver"` 监督轮次（证据见
> [agent-run-supervision-2026-10-22.md](agent-run-supervision-2026-10-22.md)），以及 2026-10-23 对 v0.1 的代码评审
> （附录 C）。**代码基线：`origin/4.14.x @ d8dfcfdd69`**，本文行号以此为准（含 2026-10-23 的
> `--coding` 显式任务类型重构）。
> 范围：`WorkspaceMirror` / `BasicBrowserAgent.workspaceRoot` / `AgentToolManager` /
> `CodingAgentShell` / `CodingAgentFileSystem` / `WorkspaceIsolation` / 完成门禁（`FinishReportGate`）/
> `agent run` 的任务级开关（含 `--fresh-workspace`）
> 不在本设计内：`browser4-coding` 的计划器（`DevTaskPlanner`）、CLI 帮助文案与输出渲染、LLM 配置加载

## 1. 背景

编码类 `agent run` 的执行形态是「把检出镜像进 agent 的 scratch 工作区，模型在镜像里改代码」。
镜像的模块目录是 **junction（Windows）/ symlink**，因此模型对模块的写入会**直接落到用户的真实检出**；
工作区根目录（`workspace/`）本身是普通目录，放 `pom.xml`、`mvnw`、`.b4scratch/` 等。

2026-10-22 的监督轮次跑出了可用的重构产物（`Browser4WebDriver.kt` 拆出
`Browser4WebDriverScripts/Parsers/Storage.kt`，115 个单测通过），但同时暴露了六个**结构性**问题：
它们不是某一处 bug，而是"工作区生命周期 + 沙箱边界 + 证据面"的设计欠账。其中直接阻塞项
（镜像时序、scaffold 文件不计入交付、LLM 键绑定）已在当轮修复，本文处理其余部分。

2026-10-23 的两项上游变化影响本设计：

1. **任务类型改为显式开关**（`acbe2cb25f`）：`CodingTaskDetector` 已删除，`coding` 由
   `command.run` 的 `coding` 参数 / CLI `agent run --coding` 显式给出，默认 browser。
   本设计不再依赖"关键词判定"，并且 `coding` 开关就是 `--fresh-workspace` 的现成管道模板（§6.3）。
2. **`WorkspaceIsolation`（git worktree）已经实现**并接到 `coding.runDevTask`，但**没有**接到
   `agent run`（§5.1 方案 C 的现状更正）。

## 2. 现状（代码事实）

### 2.1 工作区生命周期

| 事实 | 位置 |
|---|---|
| 工作区 = `<app-data>/agent/<yyyy>/<MM>/<dd>/<agentUuid>/workspace` | `BasicBrowserAgent.kt:41`、`RobustBrowserAgent.kt:314-316` |
| `agentUuid` 在 **agent 实例**创建时生成；`StatefulAgentRunner` 取 `session.companionAgent` | `BasicBrowserAgent.kt:40`、`StatefulAgentRunner.kt:406` |
| 会话的 companion agent 是 `by lazy`：一个会话一个 agent 实例 | `AgenticSession.kt:49` |
| 会话（含 CLI 的 `DEFAULT`）在后端进程存活期内复用 | `PulsarSessionManager` 的会话缓存 |
| **只有 `CLI_TOOL_LOOP` 引擎**把 `workspaceRoot` 指向 agent scratch；其他引擎是 `CodingWorkspace.workspaceRoot`（真实检出） | `BasicBrowserAgent.kt:66-70` |
| 任务类型是显式开关，默认 browser：`codingMode = coding == true`，任务结束重置 | `StatefulAgentRunner.kt:443-449`、`:519-530` |

**因此（更正 v0.1）**：workspace 按**会话 / agent 实例**复用，不是"按后端进程"——同一进程里不同会话各有
workspace。同一会话内的连续 `agent run` 共享同一个 workspace，直到后端重启或换会话。

推论：文档承诺的"每个任务一个 workspace，产物不会互相碰撞"
（`skills/browser4-cli/references/agent.md:90`）与实现不符；被取消任务留下的半成品会成为**同一会话**下
下一个任务的起点。

### 2.2 变更追踪与快照也按 agent 实例累积（v0.1 遗漏）

- `clearMutations()` 只清 mutation log（`CodingAgentFileSystem.kt:190-193`），**不清** `snapshots`
  与 `changeCounter`；每轮 CLI 循环开始时只调用了它（`RobustBrowserAgent.kt:930`）。
- `AgentToolManager` 以 `persistSnapshots = true` 构造 fs（`AgentToolManager.kt:73-74`），写前快照落盘到
  `<workspace>/.browser4/coding-snapshots/`（`CodingAgentFileSystem.kt:213`、`:1256-1285`）。
- 于是下一轮任务对同一路径的 `revert` 会加载**上一轮**的快照（`loadPersistedSnapshot`，`:1272`），
  即把上一轮的成果回退掉；`diff` 也给出跨轮 diff；`changeSummary` 开局就带着上一轮的记录
  （实测：`Changes tracked: 177 operations on 146 files`）。

这与 Q1 同源但**独立**：重置不能依赖"换目录"——方案 B 下 fs 实例本来就复用，追踪状态照样会串。

### 2.3 沙箱边界：文件工具严、shell 松

- `CodingAgentFileSystem` 有明确包含检查：`!allowExternalAccess && !resolved.startsWith(canonicalRoot)`
  → 拒绝越界路径（`:1179-1198`）。判定是**词法**的（`normalize()` 后比较，不 `toRealPath`），因此
  junction 之下的"工作区相对路径"能通过——这是设计内的（§6.6）。
- `CodingAgentShell.resolveWorkingDir` **没有任何包含检查**（`:492-511`）：绝对路径直接 `normalize()`
  采用，相对路径相对 `canonicalBaseDir`，不存在的目录会 `mkdirs()`。
- `cd` 在 `SAFE_COMMANDS` 白名单里（`:101`），所以 `coding.shell("cd /d D:\\workspace\\Browser4 && dir")`
  或 `coding.shell(command="dir", workingDir="C:\\Windows")` 都能离开工作区。
- `executeRaw` 在目录解析失败时**静默回落**到 baseDir（`:306-334`），与"失败必须显式"原则冲突。
- 命令**参数**里的绝对路径完全不受约束（`type C:\Windows\win.ini`、`findstr /s /m X D:\…`、
  `git -C …`）；本设计的 cwd 包含策略**不覆盖**这一点（§4 非目标、§9 风险）。

实测（监督轮次，模型迷路时自发产生）：

```
coding_shell: cd /d D:\workspace\Browser4\Browser4-4.14-feat && findstr /s /m "Browser4WebDriver" *.kt
coding_shell: dir /b /ad "C:\Users\pereg\.browser4\workspaces\Browser4-4.14-…"
coding_shell: head browser4-core/.../Browser4WebDriver.kt      ← POSIX 工具在 cmd.exe 下不存在
```

### 2.4 镜像的错误信息

`WorkspaceMirror.ensure()` 先链接 `<module>`，再链接 `.mvn`、复制根文件；`linkDirectory` 用
`cmd /c mklink /J`，把子进程输出按默认字符集读回。

- **嵌套模块**：`reactorModules()` 扫的是根 pom 里**所有** `<module>` 标签，包括 `<profiles>` 块。
  `browser4-tests/pulsar-tests-common`（`pom.xml:81`）排在 `browser4-tests`（`pom.xml:119`，profile）之前，
  此时 `workspace/browser4-tests` 还不存在 → `mklink` 报"系统找不到指定的路径"。**每个新工作区的首次 provisioning 都会出现**（workspace 按会话/agent 实例复用，同一会话后续任务不会再触发这条噪声，见 §2.1 的更正）。
- **编码**：`ProcessBuilder(...).inputStream.bufferedReader().readText()`（`:178`）用默认 UTF-8 解码 OEM 输出：

  ```
  21:39:48 WorkspaceMirror - workspace mirror …: linked=[…13 项…] copied=[pom.xml,mvnw,mvnw.cmd,VERSION,ROOT.md]
                                skipped=4 errors=[mklink failed for pulsar-tests-common: ϵͳ�Ҳ���ָ����·����]
  21:39:48 AgentToolManager - + Coding workspace mirror: mirrored Browser4-4.14-feat: 13 linked, 5 copied, 1 failed
  ```

- **假超时**：`readText()` 先阻塞到子进程 EOF，`process.waitFor(30, …)`（`:179`）此时必然已退出——
  超时参数从未生效，`mklink` 挂住会挂死 provisioning。
- **状态上报**：`Report.summary()` 只有计数（`skipped` 已存在，`:57`、`:62-67`），没有"哪些模块不可用、
  为什么"。

### 2.5 完成门禁的证据面（v0.1 未覆盖）

- 门禁比较报告与 `git status`（`FinishReportGate.kt:110-127`）以及运行期 mutation log（`:162-174`）。
- 但镜像工作区**没有 `.git`**：`git -C <mirror> status` 失败，`resolveFinishGitWorkspace`/`findGitRoot`
  返回 null，git 规则被整体跳过（`FinishReportGate.kt:251-253`、`:260-286`；运行时只打一条 debug：
  `RobustBrowserAgent.kt:2018-2020`）。**镜像里的编码运行只剩 mutation log 一条证据。**
- mutation log 只记录 `CodingAgentFileSystem` 的写操作（`:195-209`）：`mvn`、脚本、`>` 重定向产生的文件
  **不在其中**；日志上限 200 条（`:69`）。

### 2.6 现成的任务级开关管道（v0.2 新增）

`coding` 开关的完整链路是 `--fresh-workspace` 可以直接照抄的模板：

| 层 | 位置 |
|---|---|
| CLI 选项 + payload | `cli/browser4-cli/src/commands.rs`（`agent-run` 的 `options` 与 `tool_params_fn`：仅 true 时写 key） |
| MCP 参数校验 | `CommandToolExecutor.kt:214-237`（`allowed = setOf(…, "coding")`、`paramBool(..., default = null)`） |
| REST 透传 | `UserCommandExecutor.kt:179/218/288` |
| 任务级 override + 重置 | `StatefulAgentRunner.kt:292-321`、`:431-450`、`:519-530` |
| 使用者 | `RobustBrowserAgent.codingMode` → 镜像 provisioning（`AgentToolManager.kt:206-218`）、提示词选择 |

## 3. 问题清单

| 编号 | 问题 | 影响 | 现有缓解 |
|---|---|---|---|
| **Q1** | workspace 按会话/agent 实例复用（非按任务） | 上一任务/被取消任务的半成品、`changeSummary` 污染、模型"考古"读旧日志 | 提示词只说"工作区可能有上一轮文件，先看 changeSummary"（`CliAgentPrompt.kt:79-81`） |
| **Q2** | `coding.shell` 的 cwd 可越出工作区；会 `mkdirs()` 外部目录；`executeRaw` 静默回落 | 隔离失效：模型能读写真实检出与任意 cwd；构建产物落到真实仓库 | 文件工具有检查，shell 没有 |
| **Q3** | 镜像失败原因不可诊断（父目录未建 + OEM 乱码 + 只报数量 + 假超时） | 每轮一条噪声；真失败与噪声无法区分；`mklink` 卡死无上限 | 无 |
| **Q4** | junction 直写与"交付改动"的边界没有显式契约；门禁的 git 证据在镜像里失效 | 临时文件污染真实仓库；`filesChanged` 语义靠提示词约定；`mvn`/脚本写的文件对门禁不可见 | `.b4scratch/` 约定 + mutation 过滤（已实现）、门禁 git 规则自动跳过 |
| **Q5** | 工作区没有归档/回收 | 磁盘单调增长（每次后端重启新增一份完整镜像 + 构建 target）；崩溃残留无人清理 | 无 |
| **Q6** | 变更追踪/快照跨任务累积（`snapshots`、`changeCounter`、落盘快照都不重置） | `revert` 回退上一轮成果；`diff`/`changeSummary` 跨轮；无法"从零开始"统计本次改动 | 无 |

## 4. 设计目标与非目标

**目标**

1. 每个任务有可预测的工作区身份与生命周期：默认在会话内复用但**任务开始时把上一轮归档并重置**，
   重跑不会继承半成品；`--fresh-workspace` 提供"每个任务一个全新目录"。
2. `coding.*` 的所有**工作目录**入口（`workingDir` + `cd`）遵循同一套包含策略，策略可配置、可审计、
   失败显式。
3. 镜像失败必须可诊断：失败条目、原因（可读）、是否影响可用性，进工作区状态摘要与日志。
4. 磁盘可回收：按保留数 + 过期时间回收工作区，崩溃残留可识别；删除必须对 junction 安全。
5. 全部改动可用单测 + 一次真实 `agent run` 验证（不引入需要人工确认的步骤）。

**非目标**

- 不做容器/VM 级隔离（Windows 上成本过高）。
- 不改变"junction 直写真实检出"这一自开发形态（`git` 就是交付审计面）；只把边界写清楚（§6.6）。
- **不做命令参数级的路径沙箱**：`coding.shell` 的 cwd 被约束，但参数里的绝对路径仍然可用（§2.3）。
  这一点写进 §9 风险与工具文档，避免"以为被沙箱了"；真正闭合只能靠容器。
- 不重写 `DevTaskPlanner`/计划器语义，不改 `coding.runDevTask` 的 worktree 模式。

**实施前提（必须显式检查）**：本设计的工作区语义只对 `RunEngine.CLI_TOOL_LOOP` 成立。其他引擎下
`workspaceRoot == CodingWorkspace.workspaceRoot`（真实检出），manifest/归档/fresh 一律**不启用**，
并在请求 `freshWorkspace` 时显式失败（§7）。

## 5. 方案对比与选型

### 5.1 工作区生命周期（Q1/Q5/Q6）

| 方案 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **A. 每任务新目录** | `<agentUuid>/workspace-<taskShortId>/`，镜像每次重建 | 与文档一致、彻底隔离 | 每任务一次镜像（junction 很便宜，但首次 copies + 目录数增加）；跨任务续做需要显式 resume；所有工具实例都要重绑（§6.3） |
| **B. 会话复用 + 任务级归档/重置 + 继承摘要**（推荐默认） | 保持一个会话一个工作区；新任务开始时归档上一轮、重置变更追踪、注入"上一轮做了什么"摘要 | 无额外镜像成本；解决"考古"与污染 | 同一任务连续重跑仍共享目录（可接受：显式 resume 语义）；仍需要 §6.2.1 的重置 |
| C. git worktree 隔离 | `git worktree add` 到工作区 | 真正的分支隔离、可 diff | 需要真实检出可写且干净；Windows 上 worktree + junction 组合复杂 |

**现状更正**：C **不是**"未实现"——`WorkspaceIsolation.kt` 已实现 worktree 创建/成功回并 patch/失败丢弃，
并通过 `CodingWorkspace.isolationMode` 接到 `coding.runDevTask`（`CodingToolExecutor.kt:1889-1905`）。
它没有接到 `agent run`，原因见 §9（镜像工作区没有 `.git`，worktree 必须落在源仓库）。本设计不重复实现它。

**选型**：默认 **B**；A 以 `agent run --fresh-workspace`（CLI）/ `browser4.agent.workspace.fresh=true`
暴露；C 的收尾（把它接到 `agent run`）留作 S3 可选。

### 5.2 shell 沙箱（Q2）

| 方案 | 做法 | 说明 |
|---|---|---|
| **A. 工作目录包含策略**（推荐） | `resolveWorkingDir` 复用 fs 的**词法**包含判定；越界抛结构化错误；`cd` 越界在命令校验阶段拒绝 | 一处策略、两个入口；参数路径仍不设防（见非目标） |
| B. 白名单路径 | 只允许 `<workspace>`、系统临时目录 | 与"读取真实检出做对比"的合理用法冲突；且镜像本身已含检出内容，收益低 |
| C. 允许但审计 | 越界放行 + 记录 | 等于现状，只是加日志 |

**选型**：**A**。`allowExternalAccess=true` 时放行（与文件工具同一个开关，并且同样通过构造参数注入）。
`cd` 校验定位为**可操作提示**（避免模型误入歧途），不是安全边界——安全边界只有 cwd。

### 5.3 镜像错误（Q3）

不做方案对比，直接修四件事：嵌套模块深度排序 + 父目录兜底、错误可读（复用现有解码器）、超时真正生效、
`unavailable` 上报。嵌套模块采用**路径深度升序**（§6.5.2）：浅层模块先成为 junction，嵌套模块自然
`skipped`；"只建父目录"会让 profile 聚合模块被跳过、缺聚合 pom，已否决。

## 6. 详细设计

### 6.1 工作区身份与清单（Q1）

新增 `WorkspaceManifestStore`，清单在 `<workspace>/.browser4/workspace.json`：

```json
{
  "schema": 2,
  "workspaceId": "9859fc76-8eec-4621-bdde-3a553598f98e",
  "agentUuid": "9859fc76-8eec-4621-bdde-3a553598f98e",
  "engine": "cli",
  "sourceRepo": "D:/workspace/Browser4/Browser4-4.14-feat",
  "createdAt": "2026-10-22T17:16:12Z",
  "state": "complete",
  "currentTask": null,
  "lastTask": {
    "taskId": "bd706cbd-d648-4fb6-899b-8df43386ef48",
    "instruction": "重构 Browser4WebDriver",
    "coding": true,
    "status": "cancelled",
    "startedAt": "2026-10-22T17:16:20Z",
    "endedAt": "2026-10-22T18:33:04Z",
    "filesChanged": ["browser4-core/.../Browser4WebDriverScripts.kt"],
    "mutations": 177,
    "baselineCaptured": true
  }
}
```

- `schema` 升为 **2**（v0.3 新增 `currentTask` 字段，v0.1/v0.2 单字段 manifest 由 store 在读取时兼容）。
- `state` ∈ `active | complete | cancelled | failed | stale`；任务开始时写 `active`，结束时写终态，
  `stale` 由回收器标记（§6.7）。**没有 manifest 的目录**才叫 legacy（不是 `state` 取值）。
- `currentTask`：当前正在运行的任务快照（`taskId/instruction/coding/startedAt`）；任务终态时清空。
  `currentTask == null` 且 `state ∈ {complete, cancelled, failed}` 是"已完成"的判据。
- `lastTask`：上一个已完成任务的快照。v0.2→v0.3 修订（R13）：从 runner "直接写 `lastTask.taskId`"
  改为**任务开始时先把上一轮 `currentTask` rotate 到 `lastTask`**（保留完整快照），再用当前任务
  覆盖 `currentTask`。这样归档判定（§6.2 读 `lastTask.taskId` 与当前 taskId 对比）不会被 runner
  的当前任务写入冲掉。
- `workspaceId`：默认工作区 = `agentUuid`；`--fresh-workspace` 的工作区 = `<agentUuid>-<taskShortId>`，
  `agentUuid` 字段始终是 agent 实例 id（回收器按它定位目录）。
- `instruction` 截断到 200 字（它会进入系统提示词，§6.2.2）。
- `filesChanged` ≤ 200 条；归档只取前 50 条（§6.2.1）。
- 写入方 `WorkspaceManifestStore` 是**非挂起**的纯文件操作（临时文件 + `ATOMIC_MOVE`），
  因此可在取消路径的 `finally` 里安全调用（§6.2.3）。
- 写点（v0.3 修订，解决 v0.2 的两个时序问题）：
  1. **镜像 provisioning 后，仅当 manifest 缺失时创建**（写 `workspaceId/agentUuid/engine/sourceRepo/createdAt`）。
     **不再**写 `state`/`lastTask`/`currentTask`，避免"同一工作区的下一轮 coding 任务重新 provisioning 时
     把上一轮终态覆盖回 `active`"（`provisionCodingWorkspaceMirror()` 幂等但每轮都被调用，
     `RobustBrowserAgent.kt:903-906`）。
  2. **`StatefulAgentRunner` 任务开始**：先读 manifest 当前值，若 `currentTask != null`（上一轮写点 3 没来得及 rotate 的罕见场景，见下）则 rotate 到 `lastTask` 作为兜底；再把 `state=active`、`currentTask.taskId/startedAt/instruction/coding`
     以及 git 基线（`baselineCaptured=true`）一起写入。**runner 永远不直接覆盖 `lastTask`**。
  3. **`StatefulAgentRunner` 的 `finally`（主 rotate 点）**：
     a. 写 `state` 终态（`complete`/`cancelled`/`failed`）到 manifest 的 `state` 字段；
     b. 把 `currentTask` 的快照填完 `status`/`endedAt`/`filesChanged`/`mutations`；
     c. **立即 rotate**：`lastTask = currentTask`，然后 `currentTask = null`。
     rotate 的目的是让**本任务的终态快照在 finally 结束前就到位**，下一轮任务写点 2 一进来就读到完整的上一轮快照。如果省略这一步而等下轮写点 2 再 rotate，则写点 2 在归档判定之前执行，判定条件读不到 `lastTask`（它还空着），归档永不触发——这正是 v0.3 第一版写点 3 "写完就清空"犯的时序错误。
     写点 2 的兜底 rotate 覆盖"进程在写点 3 的 rotate 之前被杀掉"的场景，避免那一轮快照丢失。
  浏览器任务也写（`coding=false`），这样回收器和"上轮做了什么"对所有任务都有据可查。
- 损坏/缺失按 legacy 处理：store 读失败或 schema<1 时，记 `workspace.legacy` 并创建 schema 2 manifest；
  归档/继承摘要条件在 legacy 工作区跳过（§7）。

`agent result` 的 `workspace` 字段继续指向该目录（`StatefulAgentRunner.kt:587`），不新增用户可见概念。

### 6.2 复用时的归档、重置与继承摘要（Q1 + Q6）

任务开始、且 manifest 的 `lastTask.taskId` 存在**且与当前 taskId 不同**（即上一轮任务的终态快照已到位，且不是同一 taskId 的重跑/恢复），按顺序做下列动作（任一步失败只记日志，
不阻塞任务）。**同 taskId 重跑的 guard**：取消任务被用户显式 resume（同 taskId 续跑）或在同一 taskId 上修复时，归档/继承摘要必须跳过——否则会把上一轮中途留下的半成品归档、再注入一条"上一轮改了 X"的误导摘要。这条 guard 在写点 3 主 rotate 之后仍然成立（主 rotate 写的是同一个 taskId 的终态快照），也覆盖写点 2 兜底 rotate 的罕见场景（进程在上轮 rotate 前被杀掉，本轮发现 `currentTask != null` 先 rotate，但同 taskId 仍要跳过归档）。**归档与继承摘要只对 coding 任务做；变更追踪重置对 CLI 引擎下的所有任务都做**
（fs 实例是共享的，浏览器任务同样会累积快照，§2.2）。

#### 6.2.1 归档上一轮产物 + 任务级重置

1. **归档**：把 `lastTask.filesChanged` 中仍存在于工作区的普通文件复制到
   `<agentUuid>/workspace.archive/<lastTask.taskId>/`（与 `workspace` 同级，避免 junction 递归）；
   跳过 junction/symlink 条目；上限 50 个文件 / 20 MB；写一份 `archive.json`（来源、计数、截断原因）。
   归档失败不阻塞任务，只记日志。
2. **重置变更追踪（Q6，新增）**：`CodingAgentFileSystem.beginTask(archiveDir?)`：
   - coding 任务：把 `.browser4/coding-snapshots/` 整体移动到归档目录（而不是留在原地）；
   - 非 coding 任务（`archiveDir == null`）：直接删除该目录（只记删除数量）；
   - 两种情况下都 `snapshots.clear()`、`changeCounter.set(0)`、`clearMutations()`。

   调用点就在当前 `clearMutations()` 的位置（`RobustBrowserAgent.kt:930`），顺序必须是"先归档、后重置"。
   没有这一步，§8 的"第二轮 `changeSummary` 为 0"不可能成立，且 `revert` 会跨轮回退（§2.2）。

#### 6.2.2 继承摘要

把下面的文本追加到 CLI 循环的系统提示词 `cliAgentSystemPrompt() + memorySection` 之后
（`RobustBrowserAgent.kt:916-920`，与 recall 同段，KV 前缀稳定），**仅 coding 任务注入**：

```
## Inherited workspace state
This workspace was used by an earlier task (bd706cbd, cancelled 2026-10-22T18:33Z): 重构 Browser4WebDriver…
It changed 3 file(s): browser4-core/.../Browser4WebDriver.kt, …Scripts.kt, …Parsers.kt.
Those edits are already in the working tree. Verify them with coding.changeSummary / coding.diff
before building on them; do not read old build logs or backup copies.
```

约束：总行数 ≤ 10；文件名最多列 5 个；instruction 已截断（§6.1）；不写"已完成"，只写"存在"
（上一轮可能被人工回滚）。

#### 6.2.3 取消路径

`StatefulAgentRunner` 捕 `CancellationException` 后重抛（`:502-513`），`finally`（`:519-530`）里做挂起写会
直接不执行。因此 manifest 的终态写入必须是**非挂起**调用（或包在 `withContext(NonCancellable)`），
且放在 `finally` 中。这是 Q5 崩溃残留识别的前提，也是被取消任务留痕的唯一位置。

#### 6.2.4 清理临时区

删除 `.b4scratch/`（上一轮脚本不是输入）；被取消任务的 scratch 同样适用。归档不包含 scratch
（它已被 `isNonDeliverableMutationPath` 过滤，`CodingAgentFileSystem.kt:204`），两者不冲突。

### 6.3 `fresh workspace` 开关（方案 A）

- CLI：`agent run --fresh-workspace` → `command.run` 参数 `freshWorkspace=true`（仅在为 true 时写 payload）。
- 后端：`StatefulAgentRunner` 在任务开始时若 `freshWorkspace=true`，把 agent 的 workspace 覆盖为
  `<agentUuid>/workspace-<taskShortId>`（`taskShortId = status.id.take(8)`），provisioning 走完整的
  `WorkspaceMirror.ensure()`。
- 配置键：`browser4.agent.workspace.fresh`（默认 false）。
- fresh 工作区没有"上一轮"，因此不归档、不注入继承摘要；旧工作区原样保留，交给 §6.7 回收。

**实现路径（v0.1 缺失，必须按此设计）**

| 层 | 改动 | 模板 |
|---|---|---|
| CLI `commands.rs` | `agent-run` 新增布尔选项；仅 true 时 `p["freshWorkspace"]=true` | `coding`（`test_agent_run_forwards_coding_flag` 同款单测） |
| `CommandToolExecutor.callFunctionOn("run")` | `allowed += "freshWorkspace"`、`paramBool(..., default = null)` | `:214-237` |
| `UserCommandExecutor` | `submit/execute` 透传 | `:179/218/288` |
| `StatefulAgentRunner` | 参数 + `workspaceDirOverride` + `finally` 重置 | `:292-321`、`:431-450`、`:519-530` |
| `RobustBrowserAgent` | `@Volatile var workspaceDirOverride: Path?`；`agentWorkspaceDir`/提示词/`finishWorkspaceRoot` 一律读**生效根**；`workspaceRoot` 覆盖 | `BasicBrowserAgent.kt:66-70`、`RobustBrowserAgent.kt:314-316`、`:1742`、`:2088-2092` |
| `AgentToolManager` | `workspaceRoot` 由 `val` 改为 `@Volatile var` + `rebindWorkspace(root)`：重建 `codingShell`/`codingFs`/`codingTarget`，并更新 `b4CliExecutor` 的默认工作目录（当前是构造参数 `B4CliToolExecutor.kt:41`，需加 setter 或重建） | `AgentToolManager.kt:31`、`:60-87`、`:399` |

关键约束：

1. `AgentToolManager` 在构造时就固定了 workspace 并在 `init` 里做镜像（`:170-190`），`codingTarget`
   是 `by lazy`（`:77`）。**不能只改路径不重建工具**；`rebindWorkspace` 用新的 fs 实例天然达成 §6.2.1 的
   重置（新实例无快照）。
2. `agentWorkspaceDir` 目前是 `by lazy`，**改造成 override 感知的 getter**：直接 getter 读 `workspaceDirOverride ?: _baseDir`（`_baseDir` 来自 agent 构造期的 timed directory），彻底去掉"override 必须在第一次取根之前设置"的前提——会话复用上的第二个任务也能安全改写（上一轮早已初始化过 lazy，旧说法不成立）。所有读取点（`finishWorkspaceRoot`、提示词、`BasicBrowserAgent.workspaceRoot`）统一走这个 getter。
3. 重建 manager 会重复注册工具 spec（`ToolCallSpecificationRenderer.registerBuiltinDomainSpecs`）：注册
   必须幂等（按 domain 覆盖），并用一次单测锁住。
4. **替代方案（已否决）**：为 fresh 任务新建会话/agent。代价是丢掉 agent 记忆与 `stateHistory`，与
   "同一任务连续重跑"的语义冲突。
5. 非 CLI 引擎 + `freshWorkspace=true`：显式失败（400/`[fail]`），不静默降级（§7）。

### 6.4 shell 沙箱（Q2）

```kotlin
// CodingAgentShell：新增 allowExternalAccess 构造参数（与 CodingAgentFileSystem 同源注入）
private fun resolveWorkingDir(workingDir: String?): File {
    val raw = workingDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    val candidate = when {
        raw == null -> canonicalBaseDir
        raw.isAbsolute -> raw.normalize()
        else -> canonicalBaseDir.resolve(raw).normalize()
    }
    // 与 CodingAgentFileSystem.resolvePath 相同的**词法**包含策略。
    // 不能 toRealPath()：模块是 junction，realpath 落在真实仓库，会把合法路径全部判成越界。
    if (!allowExternalAccess && !candidate.startsWith(canonicalBaseDir)) {
        throw WorkspaceEscapeException(candidate, canonicalBaseDir)   // → 工具层 [fail]
    }
    if (!Files.exists(candidate)) {
        // 只有"工作区内的相对路径"允许创建；绝对路径必须已存在
        if (raw == null || raw.isAbsolute) throw WorkspaceDirMissingException(candidate)
        Files.createDirectories(candidate)
    }
    if (!Files.isDirectory(candidate)) throw WorkspaceDirMissingException(candidate)
    return candidate.toFile()
}
```

配套规则：

1. **不再 `mkdirs()` 任意目录**：只有工作区内的相对路径允许创建；绝对路径只接受已存在的目录。
2. **`executeRaw` 不再静默回落**：解析失败返回结构化错误（当前 `?: canonicalBaseDir` 的写法删除）。
3. `cd` 仍允许，但命令里出现**离开工作区**的 `cd`（`cd /d C:\…`、`cd ..\..`、`chdir`、`pushd`）时，
   在命令校验阶段拒绝并给出可操作提示（用现有 `tokenize`/`splitSimpleCommands`；含 `%VAR%`/`$VAR`
   无法静态判定的按拒绝处理）：

   ```
   [fail] cd outside the workspace is not allowed (workspace: …\workspace).
          Use workingDir=<relative path> for a subdirectory, or set
          -Dbrowser4.agent.allowExternalAccess=true when you really need to leave it.
   ```

4. **错误语义（v0.3 修订，R14）**：`WorkspaceEscapeException` 与 `WorkspaceDirMissingException`
   都是**运行时异常**，**必须在两层 catch 中重抛**：
   - `CodingAgentShell.execute()` 的 `catch (e: Exception)`（`CodingAgentShell.kt:297-299`）与
     `executeRaw()` 的 `catch (e: Exception)`（`:331-333`）：遇这两个异常重抛，其余保持现状；
   - `ToolExecutionCoordinator.execute()` 的 `catch (e: Exception)`（`ToolExecutionCoordinator.kt:54-56`）
     只做 `[fail]` 包装，不再吞类型。
   穿透到 `CodingToolExecutor` 的 `shell`/`executeRaw` 分支时，按异常类型返回 `[fail] <结构化信息>`，
   其余异常保持现状。这样内层工具循环与外层看到同一个前缀，
   `RobustBrowserAgent.kt:1222` 的 `ok=false` 判定也成立。**不改 shell 内部 catch，异常就到不了 executor——
   v0.2 原设计在这里有一个隐含的前提缺口。**
5. 错误信息统一携带"越界路径 + 工作区根 + 开关名"，不再让模型靠试错猜测。
6. 审计：越界尝试记 `coding.shell.escape` 事件（WARN 日志 + Codahale counter；含 workspace、path；
   taskId 由日志 MDC 或调用方补充），供监督复盘使用。
7. 提示词/工具文档同步写清：cwd 受约束、**参数路径不受约束**（§4 非目标）。

> 与反检测/负向影响的关系：这里不涉及页面事件，不触碰 `isTrusted`/随机延迟那套约束；
> 但"静默越界"本身就是负向影响（模型以为自己在工作区里），必须显式失败。

### 6.5 镜像健壮性（Q3）

#### 6.5.1 父目录兜底创建

`linkDirectory` 前 `Files.createDirectories(link.parent)`（只会在工作区内产生目录）。这是**兜底**：
当某个嵌套模块的祖先目录既不是模块声明、也不在任何已链接的模块里时，先建普通目录，
再在其下建 junction。它不改变已可用的链接路径。

#### 6.5.2 嵌套模块：按路径深度升序（v0.2 修订，v0.1 的方向正确但理由要写清）

- 现状：`reactorModules` 返回根 pom 里所有 `<module>`（含 profile），按声明顺序；`ensure()` 按该顺序处理。
- **决定**：`reactorModules` 结果按**路径深度升序**稳定排序后再处理，目标已存在则 `skipped`。
  浅层模块先成为 junction，深层模块随之被它覆盖 → 一律 `skipped`，不再出现 `mklink` 失败。
- **为什么不能"声明顺序 + 只建父目录"**（v0.2 初稿的写法，已否决）：根 pom 里
  `browser4-tests/pulsar-tests-common`（`pom.xml:81`，默认块）声明在 `browser4-tests`（`pom.xml:119`，profile）
  之前。先建父目录会把 `browser4-tests` 变成**普通目录**，随后 profile 的 `browser4-tests` 因"已存在"被
  skipped → 工作区里缺 `browser4-tests/pom.xml`（聚合 pom），`-Pall-test-modules` 构建直接失效。
  深度排序则先链接整个 `browser4-tests`（含聚合 pom 与全部子模块），子模块自然 `skipped`。
- **行为增量**：今天的实际结果已经是这样——子模块先失败、随后 profile 的 `browser4-tests` 成功、
  子模块因已存在被跳过。深度排序只是把"靠失败兜回来"变成确定性顺序，并消掉那条假错误。
  工作区形状不变（`browser4-tests`、`browser4-apps`、`examples` 本来就是整棵链接）。
- **不改为"只解析默认 `<modules>`"**：会让 `-Pall-test-modules` 类构建在工作区里失效；
  当前语义（覆盖所有 profile 声明）是有意为之，写进注释与测试。
- `isComplete()` 继续按"每个模块路径存在"判断（`WorkspaceMirror.kt:159-163`），并在单测里用本仓库真实
  pom 当 fixture：深度排序后必然 0 失败，且 `browser4-tests/pom.xml` 可读。

#### 6.5.3 输出编码与超时

- **复用** `CodingAgentShell.decodeProcessOutput`（`CodingAgentShell.kt:82-93`：严格 UTF-8 失败则按
  `native.encoding`），删除 `WorkspaceMirror` 自己的 `bufferedReader().readText()`。不引入 `chcp 65001`
  （它改的是子进程控制台代码页，重定向时行为还要额外验证）。
- **超时真正生效**：先 `waitFor(30s)`，再读输出；或用带超时的读取 future。超时后 `destroyForcibly()`
  并把原因归类为 `timeout`。

#### 6.5.4 失败分类与状态上报

`Report` 增加 `unavailable: List<MirrorFailure>`（`skipped` 已存在），`MirrorFailure(path, kind, detail)`，
`kind` ∈ `parent-missing | already-exists | privilege | source-missing | timeout | unknown`。

`AgentToolManager` 保留最近一次 `Report`，`coding.workspaceRoot` 的返回体固定三行：

```
D:\…\agent\2026\10\22\<uuid>\workspace
mirror: 13 linked, 5 copied, 1 unavailable (browser4-plugins/browser4-foo: privilege)
inherited: bd706cbd (cancelled 2026-10-22T18:33Z, 3 files) | none
```

为此 `CodingToolExecutor.Target` 需要携带一个 `WorkspaceStatus`（mirror summary + inherited 信息），
由 `AgentToolManager` 在 provisioning/任务开始时更新（线程安全）。

#### 6.5.5 失败不致命

镜像失败不回滚任务；若 `unavailable` 非空，在 §6.2.2 的继承摘要同一段追加一条
"Following mirror entries are unavailable: … (builds touching them will fail)"；没有继承摘要时
（例如全新工作区）单独作为 `Workspace status` 块的一行。

### 6.6 与完成门禁 / mutation log 的契约（Q4）

明确四条规则，写进 `coding` 工具文档与提示词：

1. 工作区内的**普通文件**（含模块 junction 下的文件）都可能是真实仓库改动，会进 mutation log；
2. `<workspace>/.b4scratch/`、`<workspace>/.browser4/`、任意 `*/target/` 是 agent 状态/构建产物，
   不进 mutation log、不进完成门禁（已实现：`isNonDeliverableMutationPath`）；
3. 模块目录里的临时文件**会**被当成仓库改动——临时文件一律放 `.b4scratch/`；`coding.shell` 在模块目录
   下生成的文件，运行结束前必须删除，否则会被要求申报；
4. **证据面现实**：镜像没有 `.git`，门禁的 git 规则在镜像里被跳过（§2.5），mutation log 是唯一证据，
   而它看不到 `mvn`/脚本写的文件。因此"报告里没写"与"实际没写"之间的缺口要靠下两条补：

   - **归档用 git 基线补全（v0.3 修订，R15）**：manifest 的 `filesChanged` = **(任务结束时在 `sourceRepo` 执行的
     `git status --porcelain`) − (任务开始时的 git 基线)** 与 **(mutation log 交付项)** 的并集。
     公式记作 `Δgit ∪ mutation`。基线在任务开始的同一写点采集（§6.1 写点 2，`baselineCaptured=true`）；
     差集 = 当前 `git status --porcelain` − 基线，只保留工作区相对路径。**只取差集而不取"当前状态全集"**
     ——任务开始前就存在的脏文件不应被归到本任务，v0.2 原文的"基线 与 mutation log 的并集"
     语义是模糊的，容易误解为把任务前就存在的改动也算进来。
     `sourceRepo` 由镜像提供；没有 git/没有 `sourceRepo` 时退回 mutation log。
     (`git` 通道已存在于门禁侧：`RobustBrowserAgent.kt:2120-2163`、
     `FinishReportGate.kt:238-286`。)
   - **`coding.write(scratch=true)` 纳入本设计**（不再是"可选增强"）：写入强制重定向到
     `.b4scratch/<原相对路径>`，返回体里回显真实落点；让"临时文件"在 API 层面显式化，而不是只靠提示词纪律。

**S3 可选**：把门禁的 git 工作区从 agent workspace 切到 manifest 的 `sourceRepo`（镜像里 `git` 不可用时），
让规则 4/6 在编码运行里重新生效。需要先确认"上一轮留下的改动也算 git-changed"这一超集语义可接受。

### 6.7 工作区回收（Q5，v0.2 新增）

**对象**：`<app-data>/agent/<yyyy>/<MM>/<dd>/<agentUuid>/`（含 `workspace`、`workspace-*`、
`workspace.archive`）。**绝不**触碰该根之外的路径。

**触发**：进程内首次创建 agent 时执行一次（`AtomicBoolean` 保护），失败只记日志。

**存活标记**：agent 目录创建时写 `<agentDir>/.lock`（pid + startedAt），agent 关闭/进程退出时删除。
缺失或 pid 不存在 → 视为无锁。这是规则 3/4 的依据，也是崩溃残留可识别的前提。

**规则**（按顺序）：

1. 按 manifest `lastTask.endedAt`（缺失则目录 mtime）倒序，保留最新 `keep`（默认 3）个；
2. 其余目录必须满足：manifest `state ∈ {complete, cancelled, failed}` 且结束时间早于
   `archive.maxDays`（默认 7）；
3. 有 `.lock`（pid + startedAt）且该 pid 仍存活 → 跳过；
4. `state=active` 但无存活 lock → 视为崩溃残留：改 `state=stale`，本次保留，日志提示；
5. 删除前校验 `path.startsWith(agentDataRoot)`。

**链接安全删除（必须专用实现）**：`File.deleteRecursively()` / `Files.walkFileTree` 在 Windows 上会把
junction 当普通目录走进去，删除工作区就会删掉真实仓库。实现 `deleteWorkspaceTree(root)`：

- `Files.walkFileTree(root)`，对每个条目用与 `CodingAgentFileSystem.resolveLinkTarget`
  （`:689-693`，`toRealPath() != toAbsolutePath()`）相同的判定；
- 命中链接：`Files.delete(entry)`（只删 reparse point），**不下降**；
- 普通文件/空目录：`Files.delete`；非空目录在子项删完后删；
- 单测用一个真实 junction fixture 断言"链接被删、目标文件还在"。

**配置**：`browser4.agent.workspace.keep=3`、`workspace.archive.maxDays=7`、`workspace.gc.enabled=true`、
`workspace.gc.dryRun=false`（dry-run 只记录将删除的目录，供首轮观察）。

## 7. 兼容性与迁移

- **旧工作区**：无 manifest 的目录按 legacy 处理——不归档、不注入继承摘要，只记 `workspace.legacy`；
  首次任务结束后写入 manifest。
- **归档命名**：`<agentUuid>/workspace.archive/<taskId>/`（与 workspace 同级，避免 junction 递归）。
- **CLI 兼容**：`--fresh-workspace` 是新增选项，默认行为不变（方案 B）；`--coding` 语义不变
  （本设计只在 coding 任务上启用归档/摘要）。
- **引擎前提**：工作区特性只对 `CLI_TOOL_LOOP` 生效；`observe-act` 下 coding 工具直接改真实检出，
  日志显式写 `workspace.engine=observe-act`；`freshWorkspace=true` 且非 CLI 引擎 → 显式失败。
- **`browser4 code shell`**：`MCPToolController.kt:452` 用 `CodingWorkspace.workspaceRoot`（真实仓库）
  构造同一个 shell，本设计的 cwd 包含策略会把"绝对外部目录"变成拒绝。这是有意的行为变更，
  错误信息里给出 `allowExternalAccess` 开关；`skills/browser4-cli` 相关文档同步更新。
- **配置默认值**：`allowExternalAccess=false`、`workspace.fresh=false`、`workspace.keep=3`、
  `workspace.archive.maxDays=7`、`workspace.gc.enabled=true`、`workspace.gc.dryRun=false`。
- **回滚**：行为全部由配置键控制；manifest/归档都是新增文件，缺失按 legacy 处理，无不可逆数据格式。

## 8. 测试计划

| 层 | 用例 |
|---|---|
| 单测 | `WorkspaceManifestStore` 读写/损坏容错/原子写；`beginTask` 重置（快照归档 + 计数归零 + `revert` 不跨轮）；继承摘要渲染（≤10 行、instruction 截断、不写"已完成"）；`resolveWorkingDir` 越界拒绝（绝对路径、`..`、越界 `cd`）+ **junction 内相对路径放行（正向回归）**；`executeRaw` 不再静默回落；`executeRaw`/`execute()` 内部 catch 穿透 `WorkspaceEscapeException`；`linkDirectory` 父目录创建；嵌套模块用本仓库真实 pom 当 fixture（深度排序后 0 失败，且 `browser4-tests/pom.xml` 可读）；`decodeProcessOutput` 复用；mklink 超时生效（v0.3 补充：`mklink` 是 cmd.exe 内建，不能靠 PATH 伪造——实现上把 `ProcessBuilder` 包一层 `ProcessRunner` 接口，单测注入一个返回虚假 hang 的垫片 `Process`；或在 cmd.exe 前放一个同名 `cmd.cmd` 垫片让它 sleep 再 exit）；`Report.unavailable` 分类；`deleteWorkspaceTree` 链接安全（真实 junction fixture）；`isNonDeliverableMutationPath` 既有用例；`rebindWorkspace` 后 `coding.workspaceRoot` 指向新目录且 spec 注册幂等 |
| 集成（无浏览器） | 两次 `coding` 会话模拟：第二次任务看到归档 + 摘要、`changeSummary` 开局 0；`freshWorkspace=true` 得到新目录 + manifest；取消路径写入 `status=cancelled`（`NonCancellable`）；GC 保留/过期/存活 lock 三分支 + dry-run |
| E2E（真实后端） | 连续两次 `agent run --coding`（同一会话）→ 断言第二个工作区有镜像、`.b4scratch` 被清理、`changeSummary` 为 0、无 `cd /d` 越界调用；`agent run --coding --fresh-workspace` → 新目录 + manifest；`coding.shell(workingDir=真实仓库绝对路径)` → `[fail]` 且日志有 `coding.shell.escape`；`coding.shell(workingDir="browser4-core")` → 成功（junction 正向） |

**机器可检查的验收（Definition of Done 对齐）**

- `coding.workspaceRoot` 返回体含 3 行：路径、`mirror: …`、`inherited: <taskId|none>`（§6.5.4）。
- 越界 `coding.shell` 返回 `[fail]`（抛 `WorkspaceEscapeException` → 工具层转 `[fail]`），
  日志含 `coding.shell.escape`；junction 内路径**不**返回 `[fail]`。
- 同一会话第二次 coding 任务：`changeSummary` 开局为 0（依赖 §6.2.1 的 `beginTask`）。
- 取消任务后 manifest 的 `lastTask.status` 为 `cancelled` 且 `filesChanged` 非空。
- 镜像在本仓库上跑完 `errors=[]`（不再出现 `1 failed`）。

## 9. 风险与回滚

| 风险 | 说明 | 缓解 |
|---|---|---|
| 词法包含判定的边界 | junction/symlink 可以逃出"词法工作区"；这正是 §6.6 的交付形态，不是漏洞 | 文档写明；`mklink`/`ln` 已在 `FORBIDDEN_COMMANDS`；删除侧有链接保护 |
| shell 参数路径仍不设防 | `type C:\Windows\win.ini` 一类调用不会被 cwd 策略拦住 | 非目标明确写出；§8 用显式断言把行为固定下来；只有容器能真正闭合 |
| 归档耗磁盘 | 每次任务复制改动文件 | 只复制 `filesChanged` 前 50 个且 `< 20 MB`；`archive.maxDays` 过期回收 |
| 继承摘要误导模型 | 上一轮的改动可能已被人回滚 | 摘要里显式要求先 `coding.diff` 复核；不写"已完成"只写"存在" |
| 重置误删证据 | `beginTask` 把快照移走而不是删掉 | 快照移到归档目录；失败只记日志、不阻塞任务 |
| shell 严格化破坏既有用法 | `browser4 code shell` 从"任意 cwd"变成"仓库内 cwd"；需要读真实仓库外部目录的脚本会失败 | 同一个 `allowExternalAccess` 开关；错误信息给出开关名；文档同步 |
| `fresh` 重绑遗漏组件 | LSP、`DevTaskCheckpointStore`、`b4CliExecutor` 都可能持有旧根 | §6.3 列出全部读取点；单测断言"新任务的每个工具都报告新根" |
| GC 误删 | 删除 mirrored workspace 会顺着 junction 删真实仓库 | §6.7 的链接安全删除 + 前缀校验 + `.lock` + `dryRun` 先观察一轮；单测用真实 junction |
| worktree 仍是长期方向 | 现状只接到 `coding.runDevTask`，`agent run` 的镜像没有 `.git` | S3 再评估"把 worktree 落到 sourceRepo 并在工作区暴露只读 `.git`"；不在本期 |
| junction 直写仍是"真改仓库" | 不影响隔离设计的正确性，但用户需知情 | 文档（`agent.md`）+ `agent result.workspace` 已说明；§6.6 写明边界 |
| 并发会话共享同一真实检出（v0.3 补充，R16） | 同进程内两个会话并发跑 coding 任务时，两边 junction 直写同一个源仓库；§6.6 的 `Δgit ∪ mutation` 会把对方的改动归到本任务（`git status --porcelain` 无法区分是谁写的） | §6.6 公式里 mutation log 只记录本工具实例的写入（天然隔离），Δgit 用**任务开始前的 git 基线**切边界——并发时基线之后、本任务 mutation 看不到的外部写入会进 Δgit；接受这个"超集"语义并在 manifest 里注明"Δgit 可能包含外部写入"；真正隔离还是 S3 worktree |

回滚：全部行为由配置键控制（默认值见 §7），回滚 = 关开关；代码层不引入不可逆的数据格式
（manifest/归档是新增文件，缺失时按 legacy 处理）。

## 10. 分期

| 期 | 内容 | 验收 |
|---|---|---|
| **S0**（小、独立） | Q3 镜像健壮性（嵌套模块深度排序、父目录兜底、编码解码器、真超时、`unavailable`）+ Q2 的"不 `mkdirs` 外部目录 / `executeRaw` 不静默回落" | 单测 + 本仓库镜像日志 `errors=[]` |
| **S1** | Q2 完整 cwd 沙箱（包含检查 + `cd` 校验 + 结构化错误 + 审计）+ `coding.write(scratch=true)` | 单测 + E2E 越界/junction 正反断言 |
| **S2** | Q1 + Q6（manifest + `beginTask` 重置 + 归档 + 继承摘要 + `--fresh-workspace`）+ Q5 回收 | 单测 + 两次真实 `agent run`（同会话）+ GC dry-run |
| **S3**（可选） | Q4 门禁侧收尾（git 工作区指向 `sourceRepo`）+ 把 `WorkspaceIsolation` 接到 `agent run` | 设计另开 |

> 阶段编号 S0–S3 与问题编号 Q1–Q6 分离，避免 v0.1 里 "P1 是问题还是阶段" 的歧义。

## 附录 A：本设计引用的实测证据

- **工作区复用**：同一会话连续任务的开局 `changeSummary` 直接继承上一轮记录（实测
  `Changes tracked: 177 operations on 146 files`）——同一个 `CodingAgentFileSystem` 实例。
  v0.1 用"3 个 agent 目录"当证据是**误读**：3 个目录说明当时存在 3 个 agent 实例（3 个会话），
  那 3 份空工作区的真实原因是监督记录 D8（`codingMode` 设定晚于镜像 provisioning，已修复，
  见 `RobustBrowserAgent.kt:894-906`）。
- **考古消耗**：被取消任务留下 `b4ref/*.py`、`cbuild.log` 等 146 个文件；新任务读
  `b4ref/backup/Browser4WebDriver.kt.orig`、`b4ref/t1.log` 反推历史。
- **越界实例**：`cd /d D:\workspace\Browser4\Browser4-4.14-feat && findstr /s /m "Browser4WebDriver" *.kt`、
  `dir /b /ad "C:\Users\pereg\.browser4\workspaces\Browser4-4.14-…"`。
- **镜像噪声**：`mklink failed for pulsar-tests-common: ϵͳ�Ҳ���ָ����·����`（OEM 码页被按 UTF-8 解码）；
  根因是 profile 里的 `browser4-tests` 排在默认模块之后被处理（§2.4）。
- **临时文件与门禁冲突**：`Finish report rejected: the run modified 23 file(s) the report does not list:
  .b4scratch/outline.py, …`（已由 mutation 过滤修复）。
- **v0.2 评审补充**：`WorkspaceIsolation` 已实现但只接到 `coding.runDevTask`；门禁的 git 规则在镜像里
  `resolveFinishGitWorkspace`/`findGitRoot` 均返回 null（`FinishReportGate.kt:251-253`、`:260-286`）；
  `CodingAgentShell` 没有 `allowExternalAccess` 构造参数；`linkDirectory` 超时因先 `readText()` 而失效。

## 附录 B：涉及文件

| 文件 | 角色 |
|---|---|
| `browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/WorkspaceMirror.kt` | 镜像 provisioning、链接、错误分类 |
| `browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/CodingAgentShell.kt` | shell 执行、cwd 解析、输出解码 |
| `browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/CodingAgentFileSystem.kt` | 文件工具、包含策略、mutation log、快照 |
| `browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/CodingWorkspace.kt` | 工作区根 / `allowExternalAccess` / `isolationMode` |
| `browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/WorkspaceIsolation.kt` | 已实现的 worktree 隔离（S3 接线对象） |
| `browser4-coding/.../coding/WorkspaceManifestStore.kt`（新） | manifest 读写、任务开始/结束标记 |
| `browser4-coding/.../coding/WorkspaceRetention.kt`（新） | 回收、链接安全删除、存活 lock |
| `browser4-agentic/.../agents/BasicBrowserAgent.kt` / `RobustBrowserAgent.kt` | 工作区选择、CLI 循环、提示词注入点 |
| `browser4-agentic/.../agents/CliAgentPrompt.kt` | coding/browsing 提示词、工作区措辞 |
| `browser4-agentic/.../agents/FinishReportGate.kt` | 完成门禁规则（git 工作区选择） |
| `browser4-agentic/.../tools/AgentToolManager.kt` | 镜像 provisioning、`rebindWorkspace`、`coding.workspaceRoot` 状态 |
| `browser4-agentic/.../tools/builtin/CodingToolExecutor.kt` | `coding.*` 参数校验、`[fail]` 转换、`scratch=true` |
| `browser4-agentic/.../tools/langchain4j/ToolExecutionCoordinator.kt` | 内层循环的 `[fail]` 通道 |
| `browser4-agent-tools/.../StatefulAgentRunner.kt` | `session.companionAgent`、任务级开关、manifest 终态写入 |
| `browser4-rest/.../agent/tool/CommandToolExecutor.kt` / `UserCommandExecutor.kt` | `command.run` 参数校验与透传 |
| `browser4-rest/.../mcp/controller/MCPToolController.kt`（v0.3 补充，R17） | standalone `coding_shell` / `coding_fs`（`:451-467`）用 `CodingWorkspace.workspaceRoot`，本设计 cwd 包含策略需同样注入 `allowExternalAccess`；`browser4 code shell` 行为变更的落地位置 |
| `cli/browser4-cli/src/commands.rs`（+ `main.rs`） | `agent run --fresh-workspace` 选项与 payload |
| `skills/browser4-cli/references/agent.md` | 用户可见的工作区承诺（§7 需同步改写 "Each task gets its own workspace"） |

## 附录 C：v0.1 → v0.2 修订记录

| # | v0.1 | v0.2 修订 |
|---|---|---|
| R1 | "同一后端进程内所有 `agent run` 共享 workspace" | 更正为按**会话/agent 实例**复用；证据换成 `changeSummary` 继承，"3 个 agent 目录"改判为 D8 镜像时序（已修） |
| R2 | worktree 方案"已有开关位但未实现" | `WorkspaceIsolation` 已实现并接到 `coding.runDevTask`，S3 只做接线 |
| R3 | §6.4 用 `toRealPathSafe()` 判包含 | 改**词法**包含：junction 下 realpath 在真实仓库，按原判据会拒绝全部模块内路径；新增 `allowExternalAccess` 注入与 `[fail]` 语义 |
| R4 | 未提变更追踪重置 | 新增 §2.2 现状 + §6.2.1 `beginTask`（快照归档、计数清零），§8 的"第二轮 changeSummary=0"才可达 |
| R5 | `--fresh-workspace` 只有一句话 | 新增 §6.3 实现路径（`rebindWorkspace`、`workspaceDirOverride`、逐层管道），并说明已否决的替代方案 |
| R6 | P5 只出现在问题/目标/配置里 | 新增 §6.7 回收设计（含 junction 安全删除、lock、dry-run） |
| R7 | 嵌套模块"按深度排序 **或** 维护已处理前缀集合"（未定论）；未指出聚合 pom 缺失的风险 | 明确为**深度升序 + 已存在即跳过**；并写出被否决的"声明顺序 + 只建父目录"为什么会丢 `browser4-tests/pom.xml` |
| R8 | 镜像编码用 `chcp 65001` | 复用 `decodeProcessOutput`；补"`waitFor` 因先 `readText()` 而失效" |
| R9 | 门禁契约只写三条规则 | 补 §2.5 证据面现实（镜像无 `.git`、git 规则被跳过）与 §6.6 的 git 基线归档、`scratch=true` |
| R10 | 问题 P1–P5 / 阶段 P0–P3 撞名 | 问题改 Q1–Q6，阶段改 S0–S3 |
| R11 | 范围声明"CLI 侧命令不在本设计内"却定义 CLI 开关 | 范围改为"含任务级开关，不含 CLI 帮助文案/渲染"；并纳入 2026-10-23 的 `--coding` 显式任务类型 |
| R12 | 默认引擎前提未声明 | §4 增加"工作区语义仅对 `CLI_TOOL_LOOP` 成立"的前提与失败策略 |

## 附录 D：v0.2 → v0.3 修订记录

2026-10-23 同日代码评审（本文评审轮次，证据见本会话历史）后修订。

| # | v0.2 | v0.3 修订 |
|---|---|---|
| R13 | manifest 只有 `lastTask` 单字段；runner "直接写 `lastTask.taskId`" | 改为 `currentTask`/`lastTask` **双字段 rotate 机制**；runner 永远不直接覆盖 `lastTask`。**主 rotate 在写点 3（finally 终态）**：填完 currentTask 的 status/endedAt/filesChanged/mutations 后立即 rotate 到 lastTask；**写点 2 只做兜底**：进程在上轮 rotate 前被杀掉时，本轮开始发现 `currentTask != null` 则先 rotate。解决两个时序问题：① runner 在归档判定之前改写 lastTask.taskId 导致归档永不触发；② v0.3 第一版"写终态就清空 currentTask"导致 lastTask 永远收不到终态快照（已由 Advisor 终审稿捕获并修正）。schema 从 1 升到 2；v0.1/v0.2 单字段 manifest 由 store 读取时兼容（P1） |
| R14 | §6.4 规则 4 只说"`CodingToolExecutor` 捕获该类型" | 补异常穿越链路：`execute()`/`executeRaw()` 内部 catch-all 必须重抛 `WorkspaceEscapeException`/`WorkspaceDirMissingException`；`ToolExecutionCoordinator` 不再吞类型；否则异常到不了 executor。v0.2 原设计有隐含的前提缺口（P3） |
| R15 | §6.6 filesChanged 公式："基线 **与** mutation log 的并集"（模糊） | 统一为 `Δgit ∪ mutation`，明确 `Δgit = 当前 git status − 基线`，只取**差集**（任务前就存在的脏文件不应归到本任务）；v0.2 原文语义容易误读（P1 子项） |
| R16 | 无并发会话风险 | §9 新增一行：同进程内两会话并发跑 coding 时，两边 junction 直写同一源仓库，Δgit 无法区分是谁写的；接受"超集"语义并在 manifest 注明；真正隔离留给 S3 worktree |
| R17 | 附录 B 漏 `MCPToolController.kt` | 补 standalone `coding_shell`/`coding_fs`（`:451-467`）行，说明 §7 `browser4 code shell` 行为变更的落地位置；standalone shell 需同样注入 `allowExternalAccess` |
| R18 | §6.3 约束 2："`workspaceDirOverride` 必须在**第一次取根之前**设置" | 改为"`agentWorkspaceDir` 从 `by lazy` 改为 override 感知的 getter"（`workspaceDirOverride ?: _baseDir`），彻底去掉时序前提——会话复用时上一轮早已初始化过 lazy，旧说法不成立 |
| R19 | §8 mklink 超时测试："`mklink` 卡住时超时生效（假脚本）" | 补实现说明：`mklink` 是 cmd.exe 内建，不能靠 PATH 伪造；实现上把 `ProcessBuilder` 包一层 `ProcessRunner` 接口，单测注入虚假 hang 垫片进程；或在 cmd.exe 前放同名 `cmd.cmd` 垫片；同时加 `executeRaw`/`execute()` 内部 catch 穿透异常的测试行 |
| R20 | §2.4："**每次运行都会出现。**" | 改为"**每个新工作区的首次 provisioning 都会出现**"，并注明"workspace 按会话/agent 实例复用，同一会话后续任务不会再触发这条噪声" |
