# 自开发编码任务的工作区与恢复设计（worktree 隔离）

> 状态：**S0–S3 已实施**（分支 `feat/self-dev-worktree-isolation`，2026-11-13）；S4 未做
> 代码基线：`4.16.x @ 553664f4d0`（本文行号以此为准）
> 范围：`agent run --coding` 的执行位置、验证闸门、合并协议、恢复通道、构建产物保留、并发与启动链路保护
> 不在范围：`agent run` 的交互体验、LLM 配置、`coding.devTask` 的计划器语义（只复用其隔离原语）
> 关系：本文**取代** [coding-run-isolation-design.md](coding-run-isolation-design.md) 的 §5.1 选型（镜像 + 会话级工作区）与 §6.7 回收方案的一半；该文部分现状描述已与实现漂移，差异见附录 A
> 实施记录与偏差见 §10

---

## 0. TL;DR

1. **自开发编码任务默认在 git worktree 里执行**：`<repo>/.browser4/worktrees/<taskId>`，从主仓库派生、任务独占。
2. **合并是 patch，不是直写**：任务结束、验证通过后，用 `git diff <baseline> --binary` 生成 patch，`git apply --3way` 落回主仓库工作树；**不自动 commit**。
3. **报告位置指向主仓库**：coding 任务的 `agent result.workspace` = 主仓库根（改动落地处）；worktree 只作为 `isolatedWorkspace` 附注字段。未合并时结果必须显式说明"改动未落地"。
4. **三道防线回答"改坏了起不来"**：
   - 验证闸门：没有覆盖改动模块的 passed verdict 就不合并；
   - 恢复通道独立于源码树：失败时用保留的上一份 runtime bundle 或 release runtime 起后端；
   - 一键回退：每次合并保留 patch + `refs/b4/*`，`b4w recover` 一条命令还原。
5. **镜像（junction）路径降级为 `isolation=off` 的回退**，不再是 coding 任务的默认行为。

---

## 1. 问题

### 1.1 现状的两个结构性缺陷

**缺陷 A：镜像工作区"写穿"真实检出，隔离名不副实。**

`WorkspaceMirror` 把每个 reactor 模块做成 junction/symlink 指回真实检出（`WorkspaceMirror.kt:196-233,312-344`），所以：

- 两个并发 coding 任务改的是同一棵树；
- `mvn` 的 `target/` 落进真实模块目录，工作区回收（设计稿 §6.7）永远清不到；
- 任务失败/取消留下的中间产物直接躺在用户的 `git status` 里。

**缺陷 B：镜像没有 `.git`，证据面降级。**

镜像里 `git status` 失败 → `FinishReportGate` 的 git 规则被静默跳过，只剩 mutation log（上限 200 条，`CodingAgentFileSystem`，且看不到 `mvn`/脚本写的文件）。**最需要严格验收的编码任务，恰好是验收能力最弱的路径。**

### 1.2 "改坏起不来"的现实链路

- `b4w.ps1` 检测到后端源码比 bundle 新，会在下一次需要后端时自动 Maven 重建（`b4w.ps1:498-571`）；
- 重建脚本**先删** `browser4-apps/browser4-bundle/target` 再构建（`bin/build/build.ps1:748-759`），构建失败时**上一份能用的 bundle 已经没了**；
- 结果：源码树不可构建 = 下次启动没有可用后端。

### 1.3 目标与非目标

**目标**

1. 编码任务的写入落在可丢弃层；主工作树只在"验证通过"后接收改动。
2. 失败/取消不污染主树，且可续跑（resume）。
3. 源码树坏掉时，有一条不依赖它的恢复/启动路径，且回退是一条命令。
4. 证据面恢复到 `git diff` 级别：`filesChanged` 由真实 patch 支撑。

**非目标**

- 不做容器/VM 级隔离。
- 不改变"改动最终进用户真实仓库"的交付形态（不引入远程分支工作流）。
- 不重写 `devTask` 计划器；只把 `WorkspaceIsolation` 提升为主路径。

---

## 2. 现状事实（代码为准）

| 事实 | 位置 |
|---|---|
| 每任务一个数据目录：`<agent base>/workspace.<taskId>` | `AgentResume.kt:94-120`、`StatefulAgentRunner.kt:751-789` |
| CLI 引擎下 coding 任务的工作区被镜像成检出形状 | `AgentToolManager.kt:202-262`、`RobustBrowserAgent.kt:1014-1026` |
| 镜像模块是 junction/symlink，写穿真实检出 | `WorkspaceMirror.kt:196-233,312-344`、`MirrorRoots.kt:7-21` |
| 隔离原语已存在：`git worktree add` → patch 合并 / 失败丢弃 | `WorkspaceIsolation.kt:39-51,72-93,162-213` |
| 隔离默认关闭，且只接在 `coding.devTask` | `CodingWorkspace.kt:98-105`、`CodingToolExecutor.kt:2308-2324` |
| `devTask` 的合并条件是"步骤没抛异常"，不是"verify 通过" | `CodingToolExecutor.kt:2025-2037` |
| `coding.verify` 的 verdict 已在运行期记录（`VERDICT_JSON`） | `BasicBrowserAgent.kt:77-104`、`VerificationReport.kt:62-94` |
| 模块推断已有私有实现 `inferModule(absolutePath)` | `CodingToolExecutor.kt:2531` |
| 受保护文件只有 6 个 + 2 个目录，**不含启动/构建脚本** | `CodingAgentFileSystem.kt:166-190`、`AgentAccessPolicy.kt:106-108` |
| `.browser4/`、`target/`、根 `.b4scratch/` 已被 git 忽略；**`.b4inputs/` 没有** | `.gitignore:80,98,161` |
| `changedFiles()` 用裸 `git status --porcelain`，会把 `.b4inputs/`、模块内 `.b4scratch/` 当成改动 | `WorkspaceIsolation.kt:146-152` |
| `b4w` 有本地子命令路由表（`build`/`test`/`sc`/`coworker`） | `b4w.ps1:448-464,766-778` |
| CLI 支持强制走远端 bundle / 强制重建 / 允许 stale | `daemon.rs:2843-2870` |
| 本地 bundle 的布局与健康检查（jvm.cfg） | `daemon.rs:7893-7983` |

---

## 3. 设计决策

### D1 执行位置：git worktree（每任务独占）

- 路径：`<repo>/.browser4/worktrees/<taskId>`（git 忽略；`.browser4` 已在 `DEFAULT_PROTECTED_DIRS`，agent 不能篡改）。
- 基线：
  - 主树干净 → `HEAD`；
  - 主树脏 → `git stash create` 得到的 commit（**不动用户工作树、不产生 stash 记录**），立即 `git update-ref refs/b4/baseline/<taskId>` 防止被 gc；worktree 从该 ref 检出，因此**用户未提交的改动在任务里可见**。
  - 已知限制：`git stash create` 不含 untracked 文件，任务看不到它们；需要在 instruction 里说明或先提交。
- 检出方式：`git worktree add --detach --force <path> <baseline>`（现有实现，`WorkspaceIsolation.kt:79-90`）。
- **嵌套保护**：若候选根已经是 linked worktree（`git rev-parse --git-dir` ≠ `--git-common-dir`），拒绝创建并向用户报错，不静默嵌套。

### D2 执行根与报告位置分离：改动指向主仓库

- **执行根**（`coding.*` 工具、manifest、快照、`.b4scratch/`、`.b4inputs/`）：worktree。
- **报告位置**：coding 任务的 `agent result.workspace` = **主仓库根**（`sourceRepo`），不是 worktree。用户问"改动在哪"时，答案必须是主仓库。
- `filesChanged` 保持工作区相对路径：现在天然相对主仓库（`browser4-coding/src/...` 就是仓库内路径），语义更直接。
- **未合并（`merge != applied`）**：`workspace` 仍是主仓库，但结果必须显式写"改动**未**落地"，并给出 `isolatedWorkspace` 与 `patch`；不能让人误以为主仓库已经改了（渲染规则见 D10）。
- **非 coding 任务**：`workspace` = per-task 目录，行为不变。
- **实现层不改 `AgentTaskStatus.workspace` 的内部语义**（它仍是执行根，被 deliverables / resume / mutation log 使用），只在结果装配层映射：`CommandStatus.toCommandResult()`（`CommandStatus.kt:367`）对 coding 任务把 `result.workspace` 设为 `sourceRepo`，并新增 `isolatedWorkspace`。改 `status.workspace` 会连带破坏 `listWorkspaceFiles` 的归属判定，不做。
- 改动安全的依据：CLI 只把该字段用于展示（`agent_result.rs:350`、`main.rs:16605`），没有任何行为依赖它。
- worktree 仍然保留（review / resume / GC），但不作为用户可见的"改动位置"。

### D3 验证闸门（合并前）

- **规则**：有代码改动（`*.kt|*.java|*.rs|pom.xml|*.gradle*`）时，必须存在一个 `VerificationReport.passed == true` 的 verdict，且 `verdict.module` 覆盖改动模块。
- **覆盖判定**：
  - 从 `changedFiles` 推断模块（把 `CodingToolExecutor.inferModule` 提取为 `browser4-coding` 的 `ModuleResolver.moduleOf(workspaceRoot, relativePath)`，`ModuleMap` 已有模块表）；
  - 判定式：对每个改动模块 `m`，要求 `verdict.module == m` **或** `verdict.module ∈ ModuleMap.transitiveDependents(m)`（后者即"验证 `m` 的下游模块时 `-am` 会连带编译 `m`"）；
  - 封装为 `ModuleResolver.covers(verifier: String, changed: Set<String>): Boolean`，单测锁定，避免两处各写一套。
- **豁免**：全部改动都是文档（`*.md`、`docs/`、`*.txt`）→ 不要求 verify。
- **失败处理**：拒绝合并 → worktree 保留 → 任务标记 `failed`，`problems` 里写清"verify 未运行/未通过/覆盖模块不足"，并给出重跑命令。
- **配置**：`browser4.agent.isolation.requireVerify`（默认 `true`）。
- **verdict 来源**：`BasicBrowserAgent.lastInnerVerdictJson`（运行期已记录）→ runner 解析 `VerificationReport.parseFromText`。

### D4 合并协议（成功路径）

按顺序执行，任一步失败即中止并保留 worktree：

1. `git -C <worktree> add -A -- . ':(exclude).b4scratch' ':(exclude).b4inputs' ':(exclude).browser4'`（`target/` 由 gitignore 排除）。
2. `git -C <worktree> diff --cached --binary <baseline>` → patch 文本。
3. 落盘 `.browser4/patches/<taskId>.patch`（主仓库侧，git 忽略），`git update-ref refs/b4/pre/<taskId> <main HEAD>`。
4. 主树 `git apply --check --3way <patch>`；失败 → **不合并**，报告冲突文件，worktree 保留。
5. 主树 `git apply --3way --whitespace=nowarn <patch>`；成功 → manifest 写 `merge=applied`、`mergedAt`、`changedFiles`。
6. **不自动 commit**；CLI 输出 `git diff` 提示用户 review。
7. 幂等：同一 taskId 重跑合并时，若 `git apply --check -R` 成立（patch 已应用）则直接返回已合并。

冲突与并发语义：3-way 失败即"主树自基线后被人改过"，提示用户手动 `git apply`（patch 与 worktree 都保留），不自动 `reset`。

### D5 恢复通道 `b4w recover`

一个 **PowerShell 脚本（`bin/recover.ps1`）+ `b4w` 子命令**，不依赖后端、不依赖源码可编译。

```
b4w recover [--status] [--revert <taskId>] [--hard <taskId>] [--use-last-good] [--remote] [--yes]
```

| 选项 | 行为 |
|---|---|
| `--status`（默认） | 列出：隔离 worktree 与对应任务、`.browser4/patches/*.patch`、`refs/b4/*`、当前 bundle 与 `.prev` 是否存在、`mvn` 快速编译探测结果 |
| `--revert <taskId>` | `git apply -R --3way <patch>`；冲突则中止并保留现场（不自动 `reset`）。这是**默认恢复动作**，因为它只撤销这次任务 |
| `--hard <taskId>` | `git reset --hard refs/b4/pre/<taskId>`；破坏工作树未提交改动，必须显式给出且打印将丢失的文件 |
| `--use-last-good` | 把 `browser4-apps/browser4-bundle/target/runtime-bundle.prev` 换回 `runtime-bundle`，用于源码不可构建时先起后端 |
| `--remote` | 设置 `BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1`（现有开关，`daemon.rs:2843-2850`）并提示从 release runtime 启动 |

退出码：`0` 无需恢复/已恢复，`1` 需要人工介入，`2` 用法错误。安全规则：任何 `--hard`、任何删除都在输出里先列出目标路径；路径必须落在 `<repo>` 内。

### D6 构建产物保留（回答"最后一根救命稻草"）

- `bin/build/build.ps1:748-759` 改成**先存档再删**：构建前把 `browser4-apps/browser4-bundle/target/runtime-bundle` 重命名为 `target/runtime-bundle.prev`（已有 `.prev` 则先删旧的，只保留 1 份）。
- 构建成功：删除 `.prev`（或按配置保留 1 份，默认保留，交给 `b4w recover --use-last-good` 使用）。
- 构建失败：保留 `.prev`，错误输出末尾追加一行：
  `Previous runtime bundle kept at .../runtime-bundle.prev — run: b4w recover --use-last-good`
- 不改 Rust 侧启动逻辑（不要在两处实现回退）；`.prev` 只由 recover 消费。

### D7 并发：仓库级锁

- 锁文件：`<repo>/.git/b4-coding.lock`，JSON `{pid, host, taskId, startedAt, worktree}`。
- 获取时机：coding 任务在 `worktree add` 之前；释放：任务 `finally`。
- 冲突：立即失败，错误信息给出持锁任务的 taskId/pid/启动时间，以及 `agent status` 的查法；**不排队**（v1），避免静默等待。
- stale：pid 不存在，或 `startedAt` 超过 `browser4.agent.lock.staleHours`（默认 12）→ 接管并 WARN 记录原持有者。
- 只锁 coding 任务；browsing 任务不受影响。

### D8 启动链路保护

在 `AgentAccessPolicy` 上新增 `browser4.agent.protected.extra`（逗号分隔、仓库相对路径），与默认集合并。**默认新增**：

- 文件：`b4w.ps1`、`b4w.sh`、`b4w.bat`、`bin/build.ps1`、`bin/build/build.ps1`、`bin/recover.ps1`、`browser4-apps/browser4-bundle/build-runtime-bundle.ps1`、`mvnw`、`mvnw.cmd`
- 目录：`.mvn/`

判据：改这些文件**不会让 agent 更会改代码，只会让"怎么起来"不可信**。测试脚本（`bin/test.ps1`、`bin/eval-devtask.ps1` 等）**不**保护。

配套 shell 守卫：`browser4.agent.shell.forbidBootstrap=true`（默认）时，`coding.shell` 拒绝 `b4w build|install|upgrade|stop|uninstall`、`bin/build.ps1`、`build-runtime-bundle.ps1`；`coding.mvnBuild`/`coding.verify` 不受影响（它们编译/测试，不重建 bundle）。

### D9 shadow boot（P2，可选）

仅在改动命中启动链路模块（`browser4-rest`、`browser4-core/browser4-boot`、`browser4-apps`、`browser4-agent-tools`）且 `browser4.agent.isolation.shadowBoot != off` 时：

1. worktree 内 `mvn -pl <mod> -am -DskipTests package`；
2. 起一个子 JVM：独立端口（`server.port=0` 或 18299）、独立 app-data（临时目录）、超时 60s；
3. 轮询健康端点（与 `doctor` 同源），就绪 → 杀进程、允许合并；失败/超时 → 拒绝合并，附启动日志尾部 50 行。

先按 `auto`（仅启动链路模块）实现，测量一次真实耗时后再决定是否默认开。

### D10 结果字段、渲染与提示词

- **`agent result` 字段（coding 任务）**：
  - `workspace`：**主仓库根**（改动落地处）；
  - `isolatedWorkspace`：worktree 路径（隔离执行时存在）；
  - `isolation`：`worktree|mirror|none`；
  - `merge`：`applied|refused|pending`；
  - `patch`：`.browser4/patches/<taskId>.patch`（存在时）。
- **渲染**（`cli/browser4-cli/src/agent_result.rs:354`、`main.rs:16622`）：
  - 正常合并：`Files written (N) · workspace: <repo>`；
  - `merge != applied`：首行必须是 `Changes NOT applied — isolated tree: <worktree>`，`filesChanged` 标注为未落地；
  - 失败/取消仍按现有失败渲染，但同样带 `isolatedWorkspace`。
- `CliAgentPrompt.codingRole`（`CliAgentPrompt.kt:63-98`）改写：
  - 工作树是 `git worktree`，**有 `.git`**，`git status`/`git diff` 可用；
  - 权威改动列表仍是 `coding.changeSummary`；
  - 明确"不要执行 `b4w build|stop`、不要重建后端"；
  - 未通过 `coding.verify` 的改动不会合并回主仓库。
- `coding.permissions` 增加同一组字段（D11 之外不加新开关）。

### D11 配置键

| 键 | 默认 | 说明 |
|---|---|---|
| `browser4.agent.workspace.isolation` | `worktree` | `off` 回退到镜像 + per-task 目录（旧行为，保留一个版本） |
| `browser4.agent.isolation.requireVerify` | `true` | 代码改动必须有 passed verdict |
| `browser4.agent.isolation.keep` | `3` | 保留的 worktree 数 |
| `browser4.agent.isolation.maxAgeDays` | `7` | 过期回收 |
| `browser4.agent.isolation.gc` | `on` | worktree 回收开关（锁内执行） |
| `browser4.agent.isolation.gc.dryRun` | `false` | 只记录将删除的 worktree |
| `browser4.agent.isolation.shadowBoot` | `auto` | `off|auto|always`（P2） |
| `browser4.agent.lock.staleHours` | `12` | 锁接管阈值 |
| `browser4.agent.protected.extra` | 启动链列表 | 见 D8 |
| `browser4.agent.shell.forbidBootstrap` | `true` | 见 D8 |

---

## 4. 生命周期（时序）

```
agent run --coding
  ├─ 锁：.git/b4-coding.lock（失败即报错）
  ├─ 解析 sourceRepo（CodingWorkspace.workspaceRoot / git rev-parse）
  ├─ 基线：HEAD 或 git stash create → refs/b4/baseline/<task>
  ├─ WorkspaceIsolation.begin(sourceRepo, taskId)
  ├─ workspaceDirOverride = worktree; AgentToolManager.rebindWorkspace(worktree)
  ├─ 跳过镜像：provisionCodingWorkspaceMirror 在 worktree 下直接 return
  ├─ beginTask(): manifest(isolation=worktree, baseline, worktree) + 变更追踪归零
  ├─ 运行（coding.* 全部落在 worktree；target/ 也在 worktree）
  └─ 结束
       ├─ 成功 → 验证闸门 → 合并协议 → manifest(merge=applied) → 保留 worktree
       ├─ 失败/取消 → manifest(状态, merge=refused) → 保留 worktree（可 --resume）
       ├─ 结果装配：workspace=sourceRepo，isolatedWorkspace=worktree，merge/patch 随行
       └─ finally：释放锁、恢复 access policy、清 workspaceDirOverride
```

GC（进程内首次创建 agent 时一次）：`keep` 内保留；超出且 `state ∈ {complete,failed,cancelled}` 且早于 `maxAgeDays` → `git worktree remove --force` + `prune`（**不能直接删目录**，否则 `.git/worktrees/<id>` 元数据残留）。

---

## 5. 分阶段实施

### S0 — 前置正确性修复（无行为变化，先做）

| # | 改动 | 文件 |
|---|---|---|
| S0-1 | `changedFiles()` 增加 pathspec 排除 `.b4scratch`/`.b4inputs`/`.browser4` | `WorkspaceIsolation.kt:146-152` |
| S0-2 | `.gitignore` 增加 `/.b4inputs/` | `.gitignore` |
| S0-3 | 把 `inferModule` 提取为公共 `ModuleResolver`（`moduleOf`、`covers`） | `CodingToolExecutor.kt:2531` → `browser4-coding/.../ModuleResolver.kt`（新） |
| S0-4 | `WorkspaceIsolation.begin` 增加嵌套 worktree 检测 | `WorkspaceIsolation.kt:72-93` |

**验收**：`mvn test -pl browser4-coding -am -Dtest=WorkspaceIsolationTest`；单测断言：scratch 文件不出现在 `changedFiles`，嵌套 worktree 抛 `IllegalStateException`。

**回退**：纯本地重构 + 排除规则，独立可回退。

### S1 — worktree 成为 coding 任务默认执行位置

| # | 改动 | 文件 |
|---|---|---|
| S1-1 | 任务开始：coding + git 可用 + `isolation != off` → 创建 worktree 并 rebind；非 coding 保持现状 | `StatefulAgentRunner.kt:751-789` |
| S1-2 | `provisionCodingWorkspaceMirror`：worktree 下直接 return（不再镜像） | `AgentToolManager.kt:238-262` |
| S1-3 | 任务结束：收集 changedFiles → 验证闸门（D3）→ 合并协议（D4）；失败/取消保留 worktree | `StatefulAgentRunner` 的 finally 区域 `:941-960` |
| S1-4 | manifest 新字段：`isolation`、`worktree`、`baseline`、`preRef`、`patch`、`merge` | `WorkspaceManifest.kt`（现有） |
| S1-5 | `--resume`：复用被中断任务的 worktree（`WorkspaceIsolation.reopen`），并在 prompt 里声明"主树在此之后的变化不可见" | `AgentResume.kt`、`StatefulAgentRunner.kt` |
| S1-6 | 提示词与 `coding.permissions` 字段（D10） | `CliAgentPrompt.kt`、`CodingToolExecutor.kt:2109` |
| S1-7 | 结果装配：coding 任务 `result.workspace=sourceRepo` + 新增 `isolatedWorkspace/isolation/merge/patch`；未合并时显式告警 | `CommandResult.kt`、`CommandStatus.kt:367`、`StatefulAgentRunner` 结果段、`agent_result.rs:354`、`main.rs:16622` |

**验收（机器可检查）**

- `agent run --coding "<改一个测试文件>"` → 主树出现该改动；`agent result --json` 的 `.workspace` 等于**仓库根**、`.isolatedWorkspace` 指向 `.browser4/worktrees/<taskId>`、`.merge == "applied"`。
- 故意让任务只改不验证 → 主树无改动，`merge=refused`，任务 `failed`，worktree 保留；人类输出首行是 `Changes NOT applied — isolated tree: …`，`.workspace` 仍是仓库根。
- `--resume <失败 id>` → 续跑成功并可合并。
- 非 coding 任务（browsing）仍使用 `workspace.<taskId>` 且 `result.workspace` 不变，回归测试通过。

**回退**：`-Dbrowser4.agent.workspace.isolation=off` 回到现状（镜像路径保留）。

### S2 — 恢复通道

| # | 改动 | 文件 |
|---|---|---|
| S2-1 | 保留 patch + `refs/b4/pre/<taskId>`（S1-3 已产生，补文档与输出） | `WorkspaceIsolation.kt` |
| S2-2 | 新增 `bin/recover.ps1`（D5 全套） | `bin/recover.ps1`（新） |
| S2-3 | `b4w` 路由 `recover` + help 文本 + `LocalCommands` 表 | `b4w.ps1:448-464,766-778,780+` |
| S2-4 | bundle `.prev` 存档/保留（D6） | `bin/build/build.ps1:748-759` |
| S2-5 | Pester 测试（临时 git 仓库 fixture） | `bin/recover.tests.ps1`（新；`bin/test.ps1 ps` 自动发现仓库内所有 `*.tests.ps1`，无需登记） |

**验收**

- 手工制造"patch 已应用 + 源码不可构建"：`b4w recover --status` 列出可回退项；`--revert <task>` 后 `git status` 恢复；`git status` 干净时退出码 0。
- 构建失败后 `target/runtime-bundle.prev` 存在，`--use-last-good` 能把它换回并让 `b4w status` 起来。
- `pwsh bin/test.ps1 ps` 通过。

**回退**：新增脚本与路由，删掉即可；`build.ps1` 的改动有独立开关（`-NoKeepPreviousBundle`）。

### S3 — 并发与启动链路保护

| # | 改动 | 文件 |
|---|---|---|
| S3-1 | 仓库锁（D7） | `StatefulAgentRunner`（或 `browser4-coding/.../RepoLock.kt` 新建） |
| S3-2 | `browser4.agent.protected.extra` + 默认启动链清单（D8） | `AgentAccessPolicy.kt:106-108`、`CodingComponents.kt:378-392` |
| S3-3 | shell 拒绝 bootstrap 命令（D8） | `CodingAgentShell.kt` 命令校验段 |
| S3-4 | 文档：`agent-workspace.md`（现在写的是 mirror，`:48`）、`agent.md`、`docs/config.md`、`skills/browser4-cli/SKILL.md`、`b4w` help | 多处 |

**验收**

- 并发两个 `agent run --coding` → 第二个立即失败，错误含持锁任务 id。
- 任务尝试写 `b4w.ps1` / `bin/build.ps1` → `PROTECTED_PATH`；写 `bin/test.ps1` → 允许。
- `coding.shell("b4w build")` → 拒绝；`coding.mvnBuild(...)` → 正常。

**回退**：锁与保护都是新增开关，默认值可单独关。

### S4 — shadow boot 与 GC/复用（可选）

| # | 改动 |
|---|---|
| S4-1 | shadow boot（D9） |
| S4-2 | worktree GC（keep / maxAge）与 `git worktree prune` |
| S4-3 | per-session worktree 复用（任务间 `reset --hard`，保留 `target/`）——`workspace` 仍指 repo，只需重新定义 `isolatedWorkspace` 的语义（单独评审） |

---

## 6. 测试计划

| 层 | 用例 |
|---|---|
| 单测 | `WorkspaceIsolationTest`：pathspec 排除、脏树基线、合并冲突保留、reopen、嵌套拒绝；`IsolationGateTest`：verdict 缺失/失败/覆盖不足/文档豁免；`WorktreeWorkspaceTest`：coding→worktree、非 coding→per-task、镜像跳过；`RepoLockTest`：获取/冲突/stale 接管；`ProtectedBootstrapTest`：D8 清单正反例 |
| 脚本 | `bin/recover.tests.ps1`：clean/dirty/conflict/last-good/非法参数；跨平台（`$IsWindows/$IsLinux` 分支） |
| 集成（无浏览器） | 两次连续 coding 任务：第一次失败保留 worktree，第二次 `--resume` 合并；GC 三分支 |
| E2E（真实后端） | `agent run --coding` 改一个模块并 verify → 主树有改动、worktree 保留、`agent result --json` 字段正确；verify 失败 → 主树无改动；`b4w recover --status` 输出符合约定 |

**CI 门禁**：PR 门禁只跑 `Fast`/单元（现有 `pr.yml` 排除列表不变）；E2E 走 nightly。新增的 PowerShell 测试进 `bin/test.ps1 ps` 组。

---

## 7. 兼容与迁移

- `isolation=off`：完整保留今天的镜像路径（`WorkspaceMirror`、`MirrorRoots`、`provisionCodingWorkspaceMirror`），标注 deprecated，至少保留一个版本；`MirrorRoots` 的 scope 逻辑只在 off 时生效。
- 旧任务：`workspace.<taskId>` 仍可读；`agent result` 对旧任务不改语义。
- `--shared-workspace`：coding + isolation=worktree 时**显式拒绝**（共享目录不是 worktree），错误信息给出替代（"每个任务本来就有独立 worktree；需要看旧文件请用 `agent result` 或直接读主树"）。
- `--fresh-workspace`：继续接受（worktree 本来就是新的）。
- 文档更新清单：
  - `skills/browser4-cli/references/agent-workspace.md:48`（现在写"mirror of the checkout"）
  - `skills/browser4-cli/references/agent.md`
  - `cli/browser4-cli/README.md`、`skills/browser4-cli/SKILL.md`
  - `docs/config.md`（D11 配置键）
  - `cli/browser4-cli/src/help.rs`、`tips.rs`（若 `agent run` 文案受影响）
  - `coding-run-isolation-design.md` 顶部加 superseded 注记，指向本文

---

## 8. 风险与回滚

| 风险 | 说明 | 缓解 |
|---|---|---|
| worktree 检出成本 | 每任务一份完整工作树，首次检出几秒~几十秒、磁盘翻倍 | `keep=3` + `maxAgeDays=7`；S4 的 per-session 复用；需要退回旧行为时用 `isolation=off`（不是 `--shared-workspace`，后者对 coding 任务显式拒绝） |
| 脏树基线语义 | `git stash create` 不含 untracked；任务看不到未跟踪文件 | 文档写明；prompt 提示；必要时先提交 |
| 3-way 冲突 | 主树在任务期间被人改动 | 拒绝合并、保留 patch/worktree、给出 `git apply` 指引，不自动 reset |
| 报告位置与真实改动不一致 | 未合并时 `workspace` 指向主仓库，而改动其实只在 worktree | `merge` 字段 + `isolatedWorkspace` + 首行 `Changes NOT applied` 告警；渲染文案由单测锁定 |
| 合并后起不来（编译过、启动崩） | 编译闸门管不到运行期 | S4 shadow boot；S2 的 `--use-last-good` / `--remote` 保证有后端可用 |
| 误用 `--hard` | 丢失用户未提交改动 | 必须显式 flag + 打印将丢失文件 + `--yes` 二次确认 |
| GC 删掉正在用的 worktree | 删除后任务 resume 失败 | 有锁时跳过；只回收 terminal 状态；先 `prune` 再删 |
| 嵌套 git / submodule | `git worktree add` 成本或失败 | 显式报错，不静默降级到直写 |
| 与 `devTask` 语义重叠 | 两条路径都做隔离，行为不一致 | S0 抽公共 `ModuleResolver`；S1 后两条路径共用 `WorkspaceIsolation` |

**总回滚**：`-Dbrowser4.agent.workspace.isolation=off` + 删除 `bin/recover.ps1` 路由与 `.prev` 存档逻辑。

---

## 9. 完成定义（DoD）

- [ ] S0/S1/S2/S3 全部合入，`isolation=off` 回归通过。
- [ ] `mvn -q -DskipTests install` 通过；`mvn test -pl browser4-coding,browser4-agentic,browser4-agent-tools -am` 通过。
- [ ] `coding.verify` 对本次改动给出 `passed=true`，且 `VERDICT_JSON` 与运行记录一致。
- [ ] 真实 `agent run --coding` 两次：一次成功合并、一次失败保留 + resume；两次的 `agent result --json` 中 `.workspace` 都是仓库根、`.isolatedWorkspace` 指向 worktree、`.merge` 与事实一致。
- [ ] `b4w recover --status` 在 clean 仓库返回 0；在人为制造的"patch 已应用"场景能 `--revert`。
- [ ] 构建失败时 `target/runtime-bundle.prev` 存在，`--use-last-good` 可启动。
- [ ] 文档清单（§7）全部更新，`agent-workspace.md` 不再声称 coding 工作区是 mirror。
- [ ] 无新增高噪声日志；锁、合并、恢复的输出均带 taskId。

---

## 10. 实施记录（2026-11-13）

### 10.1 落地清单

| 计划项 | 落地位置 |
|---|---|
| S0-1 changedFiles 排除 | `WorkspaceIsolation.EXCLUDES`（`:(exclude)…` pathspec，含嵌套 `.b4scratch`/`.b4inputs`/`.browser4`） |
| S0-2 `.gitignore` | `/.b4inputs/`、`/.b4build/` |
| S0-3 ModuleResolver | `browser4-coding/.../ModuleResolver.kt`（`moduleOf`/`modulesOf`/`covers`/`isDocumentation`），`CodingToolExecutor.inferModule` 改为委托 |
| S0-4 嵌套 worktree | `WorkspaceIsolation.requireNotLinkedWorktree`（`--git-dir` ≠ `--git-common-dir` 即拒绝） |
| S1 执行位置 / 基线 | `WorkspaceIsolation.begin` 从 baseline commit 检出；脏树用 `git stash create` 并 pin `refs/b4/baseline/<task>` |
| S1 验证闸门 | `browser4-coding/.../IsolationGate.kt` + `StatefulAgentRunner.finishIsolation` |
| S1 合并协议 | `WorkspaceIsolation.applyToMain`：patch 落 `.browser4/patches/<task>.patch`、pin `refs/b4/pre/<task>`、保留 worktree |
| S1 manifest | `TaskSnapshot` 新增 `isolation/worktree/baseline/preRef/patch/merge`；`AgentToolManager.beginTask/endTask` 写入 |
| S1 结果指向主仓库 | `AgentTaskStatus.sourceRepo/isolatedWorkspace/isolationMode/isolationMerge/isolationPatch`；`CommandStatus.toCommandStatus` 映射 `workspace = sourceRepo ?: workspace` |
| S1 渲染 | `cli/browser4-cli/src/agent_result.rs`：`merge != applied` 时先打印 `Changes NOT applied — isolated tree: …` |
| S1 提示词 | `CliAgentPrompt.role(..., isolated)`：有 `.git`、禁止 `b4w build/stop`、未验证不合并 |
| S2 recover | `bin/recover.ps1`（status/revert/hard/use-last-good/remote/check-build）+ `b4w recover` 路由与 help + `bin/recover.tests.ps1`（Pester 9 例） |
| S2 bundle 保留 | `bin/build/build.ps1` 构建前把 `runtime-bundle` 移入 `.b4build/runtime-bundle.prev`，失败日志给出恢复命令 |
| S3 仓库锁 | `browser4-coding/.../RepoLock.kt`（`.git/b4-coding.lock`，pid/host/taskId/startedAt，stale 接管） |
| S3 启动链路保护 | `DEFAULT_PROTECTED_FILES` 增加 `b4w.*`/`bin/build*.ps1`/`bin/recover*.ps1`/`build-runtime-bundle.ps1`/`mvnw*`，`DEFAULT_PROTECTED_DIRS` 增加 `.mvn`；`browser4.agent.protected.extra` |
| S3 shell 守卫 | `CodingAgentShell.bootstrapViolation` + `browser4.agent.shell.forbidBootstrap` |
| S4-2 worktree GC | `browser4-coding/.../WorktreeRetention.kt`（keep / maxAgeDays / dryRun）+ `WorkspaceIsolation.removeWorktree`（`git worktree remove` + prune）；runner 在**持有 checkout 锁之后**、创建 worktree 之前调用，`--resume` 时排除被续跑的树 |
| 文档 | `agent-workspace.md`、`agent.md`、`SKILL.md`、`docs/config.md`、旧设计稿 superseded 注记、`b4w` help |

### 10.2 实施中做出的偏差（比计划更准确）

1. **apply 顺序改成 plain 优先、`--3way` 回退**。实测 `git apply --3way` 会把结果写进索引（`--3way` 隐含 `--index`），与"改动以未暂存状态交付给用户 review"冲突；plain apply 使用工作树上下文，恰好适配"基线含用户未提交改动"的场景。走 3-way 时对 patch 命中的路径执行 `git reset -q -- <paths>` 还原索引。
2. **bundle `.prev` 放到 `.b4build/` 而不是 `target/` 内**。`bin/build/build.ps1` 会在 Maven 之前删除整个 `browser4-bundle/target`，放在里面等于没保留。
3. **`--shared-workspace` + `--coding` 显式失败**（与 §7 一致），不静默退回镜像：那正是隔离要消除的归属问题。
4. **锁在 worktree 创建之前获取**；锁竞争直接让第二个任务失败（不排队），错误里带持有者的 taskId/pid/开始时间。
5. **`AgentTaskStatus.workspace` 语义未改**（仍是执行根），只在结果装配层映射；`coding.permissions` 通过公开的 `WorkspaceIsolation.resolveMainRepoRoot` 显示"worktree → 合并到哪个仓库"。
6. **合并幂等**：`applyToMain` 先做 `git apply --check -R`，成立即返回 `Already merged`。`--resume` 在"已合并、但终态 manifest 未写"的窗口重跑时不会把成功的任务判成 refused。
7. **GC 只删 worktree，不删 patch/refs**：patch 与 `refs/b4/*` 是 `recover -Revert` 的凭据，且只有 KB 级；删除它们会让"树没了但改动还在主树"的场景失去回退能力。GC 在 checkout 锁内执行，`--resume` 时排除被续跑的那棵树。

### 10.3 已执行的验收

| 检查 | 结果 |
|---|---|
| `mvn test -pl browser4-rest -am -Dtest=WorkspaceIsolationTest,ModuleResolverTest,IsolationGateTest,RepoLockTest,IsolationProtectionTest,CliAgentPromptTest,CommandStatusPageStatusTest` | 全绿（coding 40、rest 11；`-am` 构建 14 个模块成功） |
| `mvn test -pl browser4-coding -am -Dtest=…`（含幂等与未暂存回归） | 40/40 通过 |
| `Invoke-Pester bin/recover.tests.ps1` | 9/9 通过 |
| `pwsh bin/recover.ps1 --status` / `b4w recover --status`（真实仓库） | 退出码 0，正确报告 worktree/patch/bundle 状态 |
| `cargo test --bin browser4-cli -- agent_result::tests` | 2/2 通过（含 `refused_merge_is_stated_before_files`） |
| `mvn test -pl browser4-coding -am -Dtest=WorktreeRetentionTest` | 6/6 通过（保留/过期/排除/dry-run/越界目录/摘要） |

### 10.4 未完成（S4 / 后续）

- **shadow boot**（D9）未实现。
- **per-session worktree 复用**（S4-3）未实现；当前每任务一次全量检出。
- **真实 `agent run --coding` 端到端**未跑（需要后端 + LLM key）；上线前应补两次运行（成功合并 / 失败保留 + resume）。
- PowerShell 版 `recover` 还没有 `-Prune` 子命令：worktree 回收在下一个 coding 任务的锁内自动执行（`browser4.agent.isolation.gc`），`-Status` 只报告。

---

## 附录 A：与 `coding-run-isolation-design.md`（v0.3）的差异

| v0.3 说法 | 现实 / 本计划 |
|---|---|
| 工作区按会话/agent 实例复用 | 已是**每任务**一个目录（`AgentResume.kt:94-120`），v0.3 已过时 |
| 选型 B：会话复用 + 归档 + 继承摘要 | 本计划改为 worktree + 合并；归档/继承摘要**不实现**（worktree 本身就是干净起点） |
| 镜像 + junction 直写是既定形态 | 降级为 `isolation=off` 回退；默认走 worktree |
| worktree（方案 C）留到 S3 可选 | 提前为默认路径（S1） |
| 回收对象是 `<app-data>/agent/.../workspace*` | 回收对象改为 `.browser4/worktrees/*`（用 `git worktree remove`） |
| manifest 记录 `lastTask/currentTask` | 保留，新增 `isolation/worktree/baseline/preRef/patch/merge` |

## 附录 B：涉及文件索引

| 文件 | 角色 |
|---|---|
| `browser4-coding/.../WorkspaceIsolation.kt` | worktree 创建/合并/回滚/reopen（本次主要改动） |
| `browser4-coding/.../ModuleResolver.kt`（新） | 路径→模块、verify 覆盖判定（S0 抽取） |
| `browser4-coding/.../WorkspaceManifest.kt` | 任务快照新增字段 |
| `browser4-coding/.../AgentAccessPolicy.kt` | `protected.extra`、默认保护集 |
| `browser4-coding/.../CodingAgentShell.kt` | bootstrap 命令守卫 |
| `browser4-coding/.../CodingWorkspace.kt` | `isolation` 默认值改为 `worktree` |
| `browser4-agentic/.../tools/AgentToolManager.kt` | 跳过镜像、rebind、manifest 写点 |
| `browser4-agentic/.../tools/builtin/CodingToolExecutor.kt` | verdict/module 抽取、`coding.permissions` 输出 |
| `browser4-agentic/.../agents/StatefulAgentRunner.kt` | 任务生命周期接线（锁、worktree、闸门、合并、清理） |
| `browser4-agentic/.../agents/CliAgentPrompt.kt` | coding 角色提示词 |
| `browser4-rest/.../entities/CommandResult.kt` | 新增 `isolatedWorkspace/isolation/merge/patch` 字段 |
| `browser4-rest/.../entities/CommandStatus.kt` | 结果装配：coding 任务的 `workspace` = sourceRepo（`:367`） |
| `cli/browser4-cli/src/agent_result.rs`、`main.rs` | 人类可读渲染，未合并时首行告警 |
| `browser4-agent-tools/.../agent/AgentResume.kt` | resume 的 worktree 复用 |
| `bin/recover.ps1`（新）+ `b4w.ps1` | 恢复命令与路由 |
| `bin/build/build.ps1` | bundle `.prev` 保留 |
| `.gitignore` | `/.b4inputs/` |
| `skills/browser4-cli/references/agent-workspace.md` 等 | 用户可见语义 |

## 附录 C：开放问题（评审时决定）

1. 是否需要"合并即 commit 到 `b4/<taskId>` 分支"的无人值守模式？（当前默认不 commit）
2. per-session 复用 worktree 后，`isolatedWorkspace` 变成共享路径；`workspace` 仍指 repo，是否需要额外的 `treeReused` 提示（S4-3）。
3. `.b4inputs/` 是加进 `.gitignore`，还是仅靠 pathspec 排除（前者污染仓库文件，后者漏在 `git status` 里可见）。
4. shadow boot 的默认值（先 `auto` 观测一轮耗时与误报）。
5. 是否保留 `--shared-workspace` 对 coding 任务的兼容（现在计划是显式拒绝）。
