# 编码任务的工作区隔离与 shell 沙箱设计

> 状态：设计稿 v0.1（未实现） · 日期：2026-10-23
> 依据：2026-10-22 `agent run "重构 Browser4WebDriver"` 监督轮次（证据见
> [agent-run-supervision-2026-10-22.md](agent-run-supervision-2026-10-22.md)）
> 范围：`WorkspaceMirror` / `BasicBrowserAgent.workspaceRoot` / `AgentToolManager` /
> `CodingAgentShell` / `CodingAgentFileSystem` / 完成门禁（`FinishReportGate`）
> 不在本设计内：`browser4-coding` 的计划器（`DevTaskPlanner`）、CLI 侧命令、LLM 配置加载

## 1. 背景

编码类 `agent run` 的执行形态是「把检出镜像进 agent 的 scratch 工作区，模型在镜像里改代码」。
镜像的模块目录是 **junction（Windows）/ symlink**，因此模型对模块的写入会**直接落到用户的真实检出**；
工作区根目录（`workspace/`）本身是普通目录，放 `pom.xml`、`mvnw`、`.b4scratch/` 等。

2026-10-22 的监督轮次跑出了可用的重构产物（`Browser4WebDriver.kt` 拆出
`Browser4WebDriverScripts/Parsers/Storage.kt`，115 个单测通过），但同时暴露了四个**结构性**问题：
它们不是某一处 bug，而是"工作区生命周期 + 沙箱边界"的设计欠账。本轮只修了直接阻塞项
（镜像时序、scaffold 文件不计入交付、LLM 键绑定），下面这些需要设计决策后再动手。

## 2. 现状（代码事实）

### 2.1 工作区生命周期

| 事实 | 位置 |
|---|---|
| 工作区 = `<app-data>/agent/<yyyy>/<MM>/<dd>/<agentUuid>/workspace` | `BasicBrowserAgent._baseDir` + `RobustBrowserAgent.agentWorkspaceDir` |
| `agentUuid` 在 **agent 实例**创建时生成，`StatefulAgentRunner` 取的是 `session.companionAgent` | `BasicBrowserAgent._uuid`、`StatefulAgentRunner`（`val agent = session.companionAgent`） |
| 会话是 CLI 的 `DEFAULT` 会话，后端进程存活期间一直复用 | `PulsarSessionManager` 的会话缓存 |
| 因此：**同一后端进程内，所有 `agent run` 共享同一个 workspace**，直到进程重启 | 实测：21:12 起的后端里 3 个 agent 目录只有第一个有镜像内容 |

推论：文档承诺的"每个任务一个 workspace，产物不会互相碰撞"（`skills/browser4-cli/references/agent.md`）
与实现不符；被取消任务留下的半成品会成为下一个任务的起点。

### 2.2 沙箱边界：文件工具严、shell 松

- `CodingAgentFileSystem` 有明确的工作区包含检查：`!allowExternalAccess && !resolved.startsWith(canonicalRoot)`
  → 拒绝越界路径（`allowExternalAccess` 默认 false，`-Dbrowser4.agent.allowExternalAccess=true` 才放开）。
- `CodingAgentShell.resolveWorkingDir` **没有任何包含检查**：绝对路径直接 `normalize()` 采用，
  相对路径相对 `canonicalBaseDir`，且对不存在的目录会 `mkdirs()`。
- `cd` 在 `SAFE_COMMANDS` 白名单里，所以 `coding.shell("cd /d D:\\workspace\\Browser4 && dir")`
  或 `coding.shell(command="dir", workingDir="C:\\Windows")` 都能离开工作区。

实测（监督轮次，模型迷路时自发产生）：

```
coding_shell: cd /d D:\workspace\Browser4\Browser4-4.14-feat && findstr /s /m "Browser4WebDriver" *.kt
coding_shell: dir /b /ad "C:\Users\pereg\.browser4\workspaces\Browser4-4.14-…"
coding_shell: head browser4-core/.../Browser4WebDriver.kt      ← POSIX 工具在 cmd.exe 下不存在
```

同一轮次里 `coding.shell` 还把 `workingDir` 的 `mkdirs()` 当副作用执行过（目录不存在时会创建）。

### 2.3 镜像的错误信息

`WorkspaceMirror.ensure()` 先链接 `<module>`，再链接 `.mvn`、复制根文件；`linkDirectory` 用
`cmd /c mklink /J`，把子进程输出用默认字符集读回。根 pom 的 `<module>` 顺序里
`browser4-tests/pulsar-tests-common` 排在 `browser4-tests` **之前**，此时 `workspace/browser4-tests`
还不存在 → `mklink` 报 "系统找不到指定的路径"，而该输出按 UTF-8 解码成了乱码：

```
21:39:48 WorkspaceMirror - workspace mirror …: linked=[…13 项…] copied=[pom.xml,mvnw,mvnw.cmd,VERSION,ROOT.md]
                              skipped=4 errors=[mklink failed for pulsar-tests-common: ϵͳ�Ҳ���ָ����·����]
21:39:48 AgentToolManager - + Coding workspace mirror: mirrored Browser4-4.14-feat: 13 linked, 5 copied, 1 failed
```

每次运行都会出现这一条（`browser4-tests` 随后被链接，所以模块其实可用），但：
模型看到的工作区状态摘要只有"1 failed"、原因不可读，排障时无法区分"可以忽略"和"真的缺东西"。

## 3. 问题清单

| 编号 | 问题 | 影响 | 现有缓解 |
|---|---|---|---|
| **P1** | workspace 按会话复用（非按任务） | 上一任务/被取消任务的半成品、`changeSummary` 污染、模型"考古"读旧日志；跨任务串味 | 无（提示词只说"工作区可能有上一轮文件，先看 changeSummary"） |
| **P2** | `coding.shell` 可越出工作区，且会 `mkdirs()` 外部目录 | 隔离失效：模型能读/改真实检出与任意路径；构建产物落到真实仓库 | 无（文件工具有检查，shell 没有） |
| **P3** | 镜像失败原因不可诊断（父目录未建 + OEM 乱码 + 只报数量） | 每轮一条噪声；真失败与噪声无法区分 | 无 |
| **P4** | junction 直写与"交付改动"的边界没有显式契约 | 模型在模块目录里写临时文件会污染真实仓库，且会被 `git status`/门禁看到；`filesChanged` 语义靠提示词约定 | `.b4scratch/` 约定 + mutation log 过滤（本轮已加） |
| **P5** | 工作区没有归档/回收 | 磁盘单调增长（每次后端重启新增一份完整镜像 + 构建 target）；崩溃残留无人清理 | 无 |

## 4. 设计目标与非目标

**目标**

1. 每个 agent **任务**有可预测的工作区身份与生命周期；重跑同一任务不会继承上一个任务的半成品，
   除非显式要求"接着上一轮做"。
2. `coding.*` 的所有路径入口（file + shell）遵循**同一套**包含策略，策略可配置、可审计。
3. 镜像失败必须可诊断：失败条目、原因（可读）、是否影响可用性，进工作区状态摘要与日志。
4. 磁盘可回收：默认保留最近 N 个工作区，其余归档/删除；崩溃遗留可在下次启动识别。
5. 全部改动可用单测 + 一次真实 `agent run` 验证（不引入需要人工确认的步骤）。

**非目标**

- 不做容器/VM 级隔离（Windows 上成本过高；文件/命令白名单已覆盖主要风险）。
- 不改变"junction 直写真实检出"这一自开发形态（`git` 就是交付审计面）；只把边界写清楚。
- 不重写 `DevTaskPlanner`/计划器语义。

## 5. 方案对比与选型

### 5.1 工作区生命周期（P1）

| 方案 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **A. 每任务新目录** | `<agentUuid>-<taskShortId>/workspace`，镜像每次重建 | 与文档一致、彻底隔离 | 每个任务一次镜像（junction 很便宜，但首次 copies + 目录数增加）；跨任务续做需要显式 resume |
| **B. 会话复用 + 继承摘要 + 归档**（推荐默认） | 保持一个会话一个工作区；新任务启动时把上一轮归档到 `workspace.archive/<taskId>/`，并注入"上一轮做了什么"摘要 | 无额外镜像成本；解决"考古"与污染 | 同一任务连续重跑仍共享目录（可接受：显式 resume 语义） |
| C. git worktree 隔离 | `git worktree add` 到工作区 | 真正的分支隔离、可 diff | 需要真实检出可写且干净；Windows 上 worktree + junction 组合复杂 |

**选型**：默认 **B**；A 以 `--fresh-workspace`（CLI）/ `browser4.agent.workspace.fresh=true` 暴露；
C 留作后续（`CodingWorkspace.isolationMode=worktree` 已有开关位，但当前是 `off` 且未实现）。

### 5.2 shell 沙箱（P2）

| 方案 | 做法 | 说明 |
|---|---|---|
| **A. 统一包含策略**（推荐） | `CodingAgentShell.resolveWorkingDir` 复用 `CodingAgentFileSystem` 的包含判定；越界直接返回结构化错误 | 一处策略、两个入口 |
| B. 白名单路径 | 只允许 `/workspace`、`/tmp` | 与"读取真实检出做对比"的合理用法冲突 |
| C. 允许但审计 | 越界放行 + 记录 | 等于现状，只是加日志 |

**选型**：**A**。`allowExternalAccess=true` 时放行（与文件工具同一开关）。

### 5.3 镜像错误（P3）

不做方案对比，直接修三件事：父目录先建、嵌套模块去重、错误可读（编码 + 归类）。

## 6. 详细设计

### 6.1 工作区身份与清单（P1）

新增 `WorkspaceManifest`（`<workspace>/.browser4/workspace.json`，随镜像一起创建）：

```json
{
  "schema": 1,
  "workspaceId": "9859fc76-8eec-4621-bdde-3a553598f98e",
  "agentUuid": "9859fc76-8eec-4621-bdde-3a553598f98e",
  "sourceRepo": "D:/workspace/Browser4/Browser4-4.14-feat",
  "createdAt": "2026-10-22T17:16:12Z",
  "lastTask": {
    "taskId": "bd706cbd-d648-4fb6-899b-8df43386ef48",
    "instruction": "重构 Browser4WebDriver",
    "status": "cancelled",
    "endedAt": "2026-10-22T18:33:04Z",
    "filesChanged": ["browser4-core/.../Browser4WebDriverScripts.kt"],
    "mutations": 177
  }
}
```

写入时机：镜像 provisioning 后（首次）、任务开始时（`taskStart`）、任务结束时（`taskEnd`，
含 status）。`agent result` 的 `workspace` 字段继续指向该目录，不新增用户可见概念。

### 6.2 复用时的继承摘要（P1 的核心）

任务开始、且 manifest 的 `lastTask` 与当前 taskId 不同时：

1. **归档上一轮产物**：把 `lastTask.filesChanged` 中仍存在于工作区的文件复制到
   `<workspace>.archive/<lastTask.taskId>/`（只复制改动文件，不复制 `target/`、`.b4scratch/`）。
   归档失败不阻塞任务，只记日志。
2. **注入继承摘要**（替代模型自己考古）：把下面的文本作为 SYSTEM 消息追加到 CLI 循环的
   `memorySection` 之后（与 recall 同段，KV 前缀稳定）：

```
## Inherited workspace state
This workspace was used by an earlier task (bd706cbd, cancelled 2026-10-22T18:33Z): 重构 Browser4WebDriver.
It changed 3 file(s): browser4-core/.../Browser4WebDriver.kt, …Scripts.kt, …Parsers.kt.
Those edits are already in the working tree. Verify them with coding.changeSummary / coding.diff
before building on them; do not read old build logs or backup copies.
```

3. **清理临时区**：删除 `.b4scratch/`（上一轮的脚本不是输入）。

收益：模型不再需要读 `cbuild.log`/`b4ref/*.py` 反推历史（实测这类"考古"消耗了 40+ 次工具调用）。

### 6.3 `fresh workspace` 开关（A 方案）

- CLI：`agent run --fresh-workspace` → `command_run` 参数 `freshWorkspace=true`。
- 后端：`StatefulAgentRunner` 在任务开始时，若 `freshWorkspace=true`：
  用 `taskId` 派生的新 agent 工作区（`<date>/<agentUuid>/workspace-<taskShortId>`），
  provisioning 走完整的 `WorkspaceMirror.ensure()`。
- 配置键：`browser4.agent.workspace.fresh`（默认 false，便于有环境变量偏好的部署）。

### 6.4 shell 沙箱（P2）

```kotlin
// CodingAgentShell.resolveWorkingDir（改造后）
private fun resolveWorkingDir(workingDir: String?): File? {
    val resolved = if (workingDir == null) canonicalBaseDir
                   else Path.of(workingDir).let { if (it.isAbsolute) it.normalize() else canonicalBaseDir.resolve(it).normalize() }
    // 与 CodingAgentFileSystem 相同的包含策略：越界时不再 mkdirs，也不再启动子进程
    if (!CodingWorkspace.allowExternalAccess && !resolved.toRealPathSafe().startsWith(canonicalBaseDir)) {
        throw WorkspaceEscapeException(resolved, canonicalBaseDir)   // 结构化错误，工具层转成 [fail] 文本
    }
    ...
}
```

配套规则：

1. **不再 `mkdirs()` 任意目录**：只有工作区内的相对路径允许创建；绝对路径只接受已存在的目录。
2. `cd` 仍允许，但命令里出现**离开工作区**的 `cd`（`cd /d C:\...`、`cd ..\..`）时，
   在命令校验阶段拒绝并给出可操作提示：

   ```
   [fail] cd outside the workspace is not allowed (workspace: …\workspace).
          Use workingDir=<relative path> for a subdirectory, or set
          -Dbrowser4.agent.allowExternalAccess=true when you really need to leave it.
   ```

3. 错误信息统一携带"越界路径 + 工作区根 + 开关名"，不再让模型靠试错猜测。
4. 审计：越界尝试记 `coding.shell.escape` 事件（含 taskId、path），供 `/metrics` 与监督复盘使用。

> 与反检测/负向影响的关系：这里不涉及页面事件，因此不触碰 `isTrusted`/随机延迟那套约束；
> 但"静默越界"本身就是负向影响（模型以为自己在工作区里），必须显式失败。

### 6.5 镜像健壮性（P3）

1. **父目录先建**：`linkDirectory` 前 `Files.createDirectories(link.parent)`（只创建工作区内的父目录）。
2. **嵌套模块去重**：若某模块的祖先目录已被链接/复制，跳过该模块（`reactorModules` 结果按路径深度排序，
   或维护已处理前缀集合）。这样 `browser4-tests/pulsar-tests-common` 在 `browser4-tests` 链接后自然可达。
3. **输出编码**：`cmd /c chcp 65001 >nul && mklink /J …`，保持 JVM 侧按 UTF-8 读；失败信息进日志时
   保留原始输出 + 归类（"parent missing" / "already exists" / "privilege"）。
4. **状态上报**：`Report` 增加 `unavailable: List<String>`（真正不可用）与 `skipped: List<String>`，
   `AgentToolManager` 把可读摘要写进工作区状态：`coding.workspaceRoot` 的返回体追加一行
   `mirror: 13 linked, 5 copied, 1 unavailable (pulsar-tests-common: parent … )`。
5. **失败不致命**：镜像失败不回滚任务，但若 `unavailable` 非空，提示词注入一条"以下模块不可用"的说明。

### 6.6 与完成门禁 / mutation log 的契约（P4）

明确三条规则，写进 `coding` 工具文档与提示词：

1. 工作区内的**普通文件**（含模块 junction 下的文件）都可能是真实仓库改动，会进 mutation log；
2. `<workspace>/.b4scratch/`、`<workspace>/.browser4/`、任意 `*/target/` 是 agent 状态/构建产物，
   不进 mutation log、不进完成门禁（本轮已实现：`isNonDeliverableMutationPath`）；
3. 模块目录里的临时文件**会**被当成仓库改动——因此临时文件一律放 `.b4scratch/`；
   `coding.shell` 若在模块目录下生成文件，运行结束前必须删除，否则会被要求申报。

> 后续可选增强：`coding.write` 支持 `scratch=true` 参数，直接重定向到 `.b4scratch/`，
> 让"临时文件"在 API 层面显式化（避免只靠提示词纪律）。

## 7. 兼容性与迁移

- **旧工作区**：无 manifest 的目录按"未知来源"处理——不归档、不注入继承摘要，只在日志里记一条
  `workspace.legacy`。首次任务结束后写入 manifest。
- **归档命名**：`<workspace>.archive/<taskId>/`（与 workspace 同级，避免 junction 递归）。
- **CLI 兼容**：`--fresh-workspace` 是新增选项，默认行为不变（方案 B）。
- **配置默认值**：`allowExternalAccess=false`、`workspace.fresh=false`、`workspace.keep=3`、
  `workspace.archive.maxDays=7`。

## 8. 测试计划

| 层 | 用例 |
|---|---|
| 单测 | `WorkspaceManifest` 读写/损坏容错；继承摘要渲染（≤N 行、无旧日志路径）；`resolveWorkingDir` 越界拒绝（绝对路径、`..`、symlink 逃逸）；`cd` 校验；`linkDirectory` 父目录创建；嵌套模块去重；`chcp` 失败回退；`isNonDeliverableMutationPath` 既有用例 |
| 集成（无浏览器） | 两次 `coding` 会话模拟：第二次任务看到归档 + 摘要；`freshWorkspace=true` 得到新目录 |
| E2E（真实后端） | 连续两次 `agent run`（第二次为编码任务）→ 断言第二个工作区有镜像、`.b4scratch` 被清理、无 `cd /d` 越界调用；`agent run --fresh-workspace` → 断言新目录与 manifest |

**机器可检查的验收（Definition of Done 对齐）**

- `coding.workspaceRoot` 返回体含 3 行：路径、`mirror: …`、`inherited: <taskId|none>`。
- 越界 `coding.shell` 调用必须返回 `[fail]` 且日志有 `coding.shell.escape`（E2E 断言）。
- 第二次 `agent run` 的 `changeSummary` 开局为 0 处改动（B 方案清理临时区 + 归档后再统计）。

## 9. 风险与回滚

| 风险 | 说明 | 缓解 |
|---|---|---|
| 归档耗磁盘 | 每次任务复制改动文件 | 只复制 `filesChanged` 且 `< 50` 个文件；`workspace.archive.maxDays` 过期清理 |
| 继承摘要误导模型 | 上一轮的改动可能已被人回滚 | 摘要里显式要求先 `coding.diff` 复核；摘要不写"已完成"，只写"存在" |
| shell 严格化破坏既有用法 | 例如模型需要读真实仓库做对比 | `allowExternalAccess` 仍是同一个开关；错误信息给出开关名 |
| junction 直写仍是"真改仓库" | 不影响隔离设计的正确性，但用户需知情 | 文档（`agent.md`）+ `agent result.workspace` 已说明；本设计 §6.6 写明边界 |

回滚：全部行为由配置键控制（默认值见 §7），回滚 = 关开关；代码层不引入不可逆的数据格式
（manifest 是新增文件，缺失时按 legacy 处理）。

## 10. 分期

| 期 | 内容 | 验收 |
|---|---|---|
| **P0**（小、独立） | P3 镜像健壮性（父目录、去重、编码、`unavailable` 上报）+ P2 的"不 `mkdirs` 外部目录" | 单测 + 一次镜像日志无 `1 failed` 噪声 |
| **P1** | P2 完整沙箱（包含检查 + `cd` 校验 + 结构化错误 + 审计） | 单测 + E2E 越界断言 |
| **P2** | P1 工作区（manifest + 继承摘要 + 归档 + `--fresh-workspace`） | 单测 + 两次真实 `agent run` |
| **P3**（可选） | worktree 隔离（`CodingWorkspace.isolationMode=worktree`） | 设计另开 |

## 附录 A：本设计引用的实测证据

- 工作区复用：同一后端进程内 3 个 agent 目录的 `workspace` 条目数 18 / 0 / 0；修复镜像时序后第二个编码任务
  得到 `+ Coding workspace mirror: mirrored Browser4-4.14-feat: 13 linked, 5 copied, 1 failed`。
- 考古消耗：被取消任务留下 `b4ref/*.py`、`cbuild.log` 等 146 个文件；新任务 `changeSummary` 开局即
  `Changes tracked: 177 operations on 146 files`，随后模型读 `b4ref/backup/Browser4WebDriver.kt.orig`、
  `b4ref/t1.log` 反推历史。
- 越界实例：`cd /d D:\workspace\Browser4\Browser4-4.14-feat && findstr /s /m "Browser4WebDriver" *.kt`、
  `dir /b /ad "C:\Users\pereg\.browser4\workspaces\Browser4-4.14-…"`。
- 镜像噪声：`mklink failed for pulsar-tests-common: ϵͳ�Ҳ���ָ����·����`（OEM 码页被按 UTF-8 解码）。
- 临时文件与门禁冲突：`Finish report rejected: the run modified 23 file(s) the report does not list:
  .b4scratch/outline.py, …`（已由本轮 mutation 过滤修复）。

## 附录 B：涉及文件

| 文件 | 角色 |
|---|---|
| `browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/WorkspaceMirror.kt` | 镜像 provisioning、链接、错误 |
| `browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/CodingAgentShell.kt` | shell 执行 / 工作目录解析 |
| `browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/CodingAgentFileSystem.kt` | 文件工具、包含策略、mutation log |
| `browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/CodingWorkspace.kt` | 工作区根 / `allowExternalAccess` / `isolationMode` |
| `browser4-agentic/.../agents/BasicBrowserAgent.kt` / `RobustBrowserAgent.kt` | 工作区选择、CLI 循环、提示词注入点 |
| `browser4-agentic/.../tools/AgentToolManager.kt` | 镜像 provisioning 调用点、工作区状态上报 |
| `browser4-agent-tools/.../StatefulAgentRunner.kt` | `session.companionAgent`、任务级开关、`workspace` 上报 |
| `skills/browser4-cli/references/agent.md` | 用户可见的工作区承诺（需与实现对齐） |
