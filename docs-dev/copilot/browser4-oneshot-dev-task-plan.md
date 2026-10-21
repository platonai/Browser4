# Browser4 oneshot 开发任务实现计划（验证权威层 · 实现步骤 · 工作区事务）

> 状态：**P0–P3 全部落地**（P3.3 为 advisory workflow，不阻断 PR）—— 实施状态见 §12
> 日期：2026-10-18 制定（2026-10-21 起按 §12 实施）
> 版本基线：`4.14.2-SNAPSHOT` @ `61b7962f29`
> 输入文档：
> - `docs-dev/copilot/eval-code-devtask.md` —— `code devtask` 评测任务定义与基线（已知差距 K1–K8）
> - `docs-dev/copilot/browser4-agent-feedback-implementation-plan.md` —— 其中的 **P3（完成报告硬校验）只兑现了一半**
> - `docs-dev/copilot/browser4-code-tool-supervision-v1.3.md` / `-v1.4.md` —— 插件任务实测复盘（假完成、LLM 挂起、门禁未被报告锚定）
> - `docs-dev/copilot/supervision-timeline-{hello,linkcheck,pagetitle}.md` —— 端到端监督记录
>
> 总体策略：**先把"完成"变成可判定的（验证权威层）→ 再让计划能表达"实现" → 再给失败一个回环预算 → 最后补工作区事务与隔离**。
> 每一阶段独立可回退（配置开关/新增工具，不改既有默认语义），每一项都带锚点、测试与验收门。

---

## 1. 结论与范围

### 1.1 一句话诊断

分层是合理的（计划层 / 工具层 / 机械执行 / 代理循环 / 状态传输 / 人工队列，六层职责清晰），
**但控制面反了**：把"任务是否完成"的裁决权交给了 LLM 自述，而计划器只输出"检查计划"、不输出"工作计划"。
结果是现在只能做 **supervised oneshot（有人盯）**，不能做 **autonomous oneshot（无人值守）**。

### 1.2 oneshot 的定义（本计划的目标态）

一次调用，无人值守，满足全部：

| # | 要求 | 含义 |
|---|---|---|
| O1 | 改完 | 任务描述 → 代码真的被改了（不是只读+编译） |
| O2 | 自证 | 结论来自**确定性门禁**（编译/测试/治理校验）的结构化裁决，不是模型自述 |
| O3 | 可回滚 | 失败能干净退回到任务开始前（含 shell 副作用） |
| O4 | 可重放 | 同输入可重复、可断点续跑，不靠运气 |
| O5 | 可判定 | 退出码 + 结构化报告承载成败，CI 可直接消费 |

### 1.3 非目标（本计划不做）

- 不做多代理并行/任务编排调度器（swarm 已有独立体系）
- 不改浏览器交互链路（tab/browser 域与 oneshot 无关）
- 不引入新 LLM provider 或改变模型选择策略
- 不重写 planner 的解析规则（沿用现有 9 类信号，只补"实现/验收"步骤表示）

---

## 2. 现状证据（断点 G1–G8）

标注 **[实测]** 的在 `61b7962f29` 上直接跑过；**[读码]** 为静态阅读结论，均带锚点。

| # | 断点 | 证据 | 影响 |
|---|---|---|---|
| G1 | **计划里没有"实现"步骤** | **[实测]** `code devtask --execute "please improve the overall performance"` → `✓ All checks passed.`、exit 0、零代码改动；9 个探针的计划工具集合 = `{scaffoldToDir, read, listDir, impact, mvnBuild, shell, trapCheck, validate}` | `--execute` 永远不可能实现功能，只会"体检" |
| G2 | **完成判定是自述** | **[读码]** `RobustBrowserAgent.kt:887-914`（`system.taskComplete` / 纯文本收尾）；硬门禁只有零工具调用（`:1892`）；`gates.ran` 不匹配**只 warn**（`:1904-1913`）；`filesChanged` 不与 git 比对（`:1915-1924`）；系统提示词对编码的全部约束只有一句（`:1613`） | 假完成仍然是"成功" |
| G3 | **门禁无结构化裁决** | **[读码]** 内部有结构（`ValidationResult.valid`、`BuildResult.diagnostics`、`ShellResult.exitCode/success`），工具边界全 `.format()` 成散文（`CodingToolExecutor.kt:952/1350/1682`）；CLI 无退出码 —— **[实测]** 即评测 K4/K5：Windows 上 `mvnw.cmd` 被白名单拦掉、`Build failed with exit -1` 却 `exit 0` | 任何门禁用不了这条链 |
| G4 | **失败无结构性回环** | **[读码]** 编译失败→改→再编译全靠模型自觉；硬中止只有 `maxSteps=100`、`textOnlyStallLimit=5`、`noopLimit=5`（`AgentConfig.kt:54/104/63`）；中止已能抛异常判 failed（`RobustBrowserAgent.kt:430`、`:1960`） | 长任务靠运气，不靠预算 |
| G5 | **工作区无事务** | **[读码]** `Files.writeString` 就地写、无 temp+rename（`CodingAgentFileSystem.kt:231/296/353/397/429`）；快照仅内存且只回退到会话首写（`:1028`、`:443-464`）；shell 副作用不进快照；`checksum` 存而不用（`:142`、`:1037`） | 失败无法干净回滚（违反 O3） |
| G6 | **沙箱形同虚设** | **[读码]** 只校验首 token（`CodingAgentShell.kt:506-529`）→ `ls && rm -rf` 通过；`bash/sh/pwsh` 在 `DEV_COMMANDS`（`:83`）；`allowDevTools` 默认 true + PATH 兜底（`:36`、`:496-498`）；`protectedFiles` 只有 5 个生效点（`CodingAgentFileSystem.kt:271/323/381/414/517`），`write(214)/append(243)/copy(578)/move(603)` **不做保护检查**（与 `:49-53` 注释矛盾）；符号链接未 realpath（`:1007-1026`） | 无人值守时风险不可接受 |
| G7 | **反馈信号质量差** | **[读码]** 编译诊断只留 basename（`MavenBuildSupport.kt:79-90`）、非 kotlin-maven 形态不匹配；shell 输出按**头部**截断 200k（`CodingAgentShell.kt:617`，编译错误在尾部）；`editLines/insertAfter` 把 CRLF 规范化成 LF（`:397/:429`） | 模型看到的是坏信号，修复靠猜 |
| G8 | **无验证钩子 / 扫描抖动** | **[读码]** 没有任务级 post-run 验证钩子（全仓唯一 `afterRun` 是 `AbstractPrivacyContext.kt:322`，与开发任务无关）；REST 只有 MCP 同步工具 + 异步 agent task；`ModuleGraph.scanPoms` 每次全树 `Files.walk`（`ModuleGraph.kt:112-120`）—— **[实测]** 冷 11.4–12.5s / 热 4.5s（评测 K8） | 缺"谁来验收"；计划调用是热路径却抖 |

---

## 3. 阶段总览

| 阶段 | 内容 | 依赖 | 规模 | 回退开关 |
|---|---|---|---|---|
| **P0** | 验证权威层：`coding.verify` + 完成锚定 + 最小加固 | 无 | 2.5d | `browser4.agent.verify.enabled=false`、`finishGateCheck=warn` |
| **P1** | 可执行：计划补"实现/验收"表示 + 失败回环预算 + 扫描缓存 | P0 | 3.5d | `browser4.devtask.plan=check\|work`、`browser4.agent.repair.maxRounds=0` |
| **P2** | 可隔离：事务工作区 + 断点续跑 | P0 | 5d | `browser4.agent.workspace.isolation=off` |
| **P3** | 收口：评测/文档/CI 集成 + 默认值切换 | P0–P2 | 1.5d | — |

依赖图：`P0 → {P1, P2} → P3`；P1 与 P2 可并行（P1 改 planner/loop，P2 改 workspace/runner）。

---

## 4. P0 — 验证权威层（2.5d，最高优先级）

**目标**：把"完成"从模型自述变成确定性裁决，并顺手根治评测里的 K4/K5。

| # | 任务 | 锚点 | 内容 | 测试 |
|---|---|---|---|---|
| 0.1 | `coding.verify` 原语 | 新增 `browser4-coding/.../VerificationReport.kt`；`CodingToolExecutor.kt` 新分支 | 一次调用聚合 `mvnBuild(compile)` / 模块测试 / `validate repo-consistency` / `trapCheck`，返回结构化 verdict：`{passed:bool, checks:[{name, ran, ok, exitCode, durationMs, evidence}]}`；**同时**渲染人读摘要。参数：`module`、`checks`（子集）、`runTests`、`timeoutSeconds` | 单测：全绿/编译失败/测试失败/子集选择/超时；结构字段稳定（JSON 快照） |
| 0.2 | 工具边界保留结构 | `CodingToolExecutor.kt:952/1350/1682`、`CodingAgentShell.formatResult` | `mvnBuild`/`validate`/`shell` 增加**结构化旁路**（MCP 回复里附带 `{"verify":{...}}` 段或 `verdict` 字段），人读文本保持不变 | 单测：文本不变 + 结构可解析；e2e：CLI `--json` 可取到 |
| 0.3 | 失败退出码 | `cli/browser4-cli/src/main.rs`（`code-*` 家族） | `--verify` / `code mvn` / `code validate` 汇总子检查：任一失败 → **非零退出码**；新增 `--json` 机器可读输出 | e2e：失败 exit≠0；成功 exit=0；评测 K5 转 XPASS |
| 0.4 | Windows launcher 白名单 | `MavenBuildSupport.kt:53-60`、`CodingAgentShell.kt:64-96`、`:453-504` | 白名单判定用**去扩展名 basename**（`mvnw.cmd → mvnw`），或显式补 `mvn.cmd/mvnw.cmd/gradlew.bat`；`mavenLauncher` 返回绝对路径 | 单测（平台参数化）：Windows/Linux 都能通过 `validateCommand`；评测 K4 转 XPASS |
| 0.5 | 完成判定锚定证据 | `RobustBrowserAgent.kt:1883-1924` | ① `gates` 里 `ran:true` 但无匹配调用 → **fail**（不再 warn）；② `ran:false` 必须带 `detail`，否则 fail；③ `filesChanged` 与实际 `git status --porcelain` 比对，不一致 → fail（`finishGateCheck=warn` 时仅告警）；④ 任务声明 `success:true` 但必需门禁（可配置清单）未跑或 `ok:false` → fail | 单测：四种拒绝路径 + warn 模式；集成：假完成任务被拒 |
| 0.6 | 保护覆盖补齐 | `CodingAgentFileSystem.kt:271/323/381/414/517`（现有 5 处） vs `:214/243/578/603` | `writeFile`/`appendFile`/`copy`/`move` 补 `protectionViolation()` 检查（与 `:49-53` 注释一致）；`protect()` 覆盖全部写路径 | 单测：`write("AGENTS.md")`、`move` 受保护文件均被拒 |
| 0.7 | 命令整串校验 | `CodingAgentShell.validateCommand` | 除首 token 外，对 `&&`/`;`/`|`/`$(`/反引号/重定向拆分出的**每个** base 命令逐一校验；`sh -c`/`bash -c`/`pwsh -c` 递归校验内层 | 单测：`ls && rm -rf` 被拒、管道被拒、`sh -c "curl x"` 被拒；合法复合命令放行 |
| 0.8 | 写入原子性 | `CodingAgentFileSystem` 写路径 | 写临时文件 + `ATOMIC_MOVE`（同目录），保留原编码/换行；`editLines/insertAfter` 保留原文件换行风格 | 单测：原子替换、CRLF 保留、崩溃注入（临时文件清理） |

**验收**：`bin/eval-devtask.ps1 -Strict` 全绿（K4/K5 转 XPASS 后删除登记）；新增 `coding.verify` 单测 + `MCPToolControllerTest` 分支；`mvn test -pl browser4-coding,browser4-agentic -am` 通过。

---

## 5. P1 — 可执行：计划能表达"实现"，失败有预算（3.5d）

**目标**：让 `--execute` 与代理都有明确的"做什么/怎么算完"契约；让编译失败自动变成修复轮次。

| # | 任务 | 锚点 | 内容 | 测试 |
|---|---|---|---|---|
| 1.1 | 计划增加 `implement`/`verify` 步骤 | `DevTaskPlanner.kt:169-285`、`PlanStep` | 新增两类步骤：`implement`（目标文件集 + 验收清单，不含具体代码，作为代理的执行契约）与 `verify`（`coding.verify` 调用，含必需门禁清单）。`--execute` 只跑确定性步骤（scaffold/read/impact/build/test/verify/validate），遇到 `implement` 时显式输出"需代理实现"并指向 `agent run` | 单测：plan 形状（9 探针快照更新）；`--execute` 遇 implement 的行为断言 |
| 1.2 | 计划输出的"验收清单" | `DevTaskPlanner.summarize` | 计划尾部追加 `Acceptance: [compile <module>, test <class>, validate repo-consistency, trapCheck <file>]`，可直接被 supervisor 与代理复用 | 单测：清单内容与步骤一致 |
| 1.3 | 失败回环预算 | 新增 `RepairLoop`（browser4-agentic） | 门禁失败 → 把**结构化诊断**（文件/行/列/消息）作为一条 user 消息回灌 → 模型修复 → 重跑 verify；最多 `browser4.agent.repair.maxRounds`（默认 3）轮；超预算 → 任务 failed 并附证据 | 单测：单轮成功、超预算失败、诊断回灌格式 |
| 1.4 | 诊断精度 | `MavenBuildSupport.parseDiagnostics` | 保留**模块相对路径 + 行/列**（不只 basename）；补 Maven `[ERROR] /abs/Foo.kt:[12,5]` 形态；区分 `e:`（error）与 `w:`（warning） | 单测：三种真实输出形态夹具 |
| 1.5 | 输出截断策略 | `CodingAgentShell.kt:617` | 编译/测试类命令改**尾部**截断（保留头部摘要若干行）；其余改截断时明确标注保留区间 | 单测：尾部保留断言 |
| 1.6 | 模块图缓存 | `CodingToolExecutor.kt:1690`、`ModuleGraph.kt:112` | 进程内缓存 + 按相关 pom 的 mtime/大小失效；`Files.walkFileTree` 在 `preVisitDirectory` 跳过 `target/.git/node_modules/.test*` | 单测：缓存命中/失效；评测 K8 转 XPASS；`code devtask` 热态 ≤2s |
| 1.7 | 计划步骤去噪 | `DevTaskPlanner.inferModules`（`:87-92`）、`impactPath`（`:215-226`） | 评测 K1/K2/K3：裸文件名按"文件名→模块"索引解析；`impact` 与 `mvnBuild` 共用"最深模块"规则；祖先聚合器不计入信号 | 单测：三条回归（评测探针对应）；`bin/eval-devtask.ps1 -Strict` K1–K3 转 XPASS |

**验收**：`-Strict` 下 K1/K2/K3/K8 全部 XPASS；一个真实小任务（如"给 browser4-coding 补一个纯函数 + 单测"）能通过 `agent run` 在 2 轮修复内达成 `verify.passed=true`。

---

## 6. P2 — 可隔离：事务工作区与断点续跑（5d）

**目标**：失败可干净回滚（O3），任务可重放（O4）。默认关闭，成熟后再切默认。

| # | 任务 | 锚点 | 内容 | 测试 |
|---|---|---|---|---|
| 2.1 | 工作区隔离 | 新增 `WorkspaceIsolation`（browser4-coding） | 任务开始时 `git worktree add` 到受管目录（或 `git stash`），所有写落在隔离树；任务成功 → 合并回主树；失败 → 丢弃 worktree | 单测：创建/合并/丢弃；e2e：失败后主树 `git status` 干净 |
| 2.2 | shell 副作用纳入事务 | `CodingAgentShell.executeRaw` | 记录任务窗口内的工作区变更快照（文件列表 + 内容 hash），失败时可反向恢复；或强制在隔离树内执行（优先） | 单测：模拟"编译写了 build 产物"后回滚 |
| 2.3 | 快照落盘 | `CodingAgentFileSystem.FileSnapshot` | 会话快照持久化到 `.browser4/.../coding-snapshots/<taskId>/`，后端重启可恢复；`checksum` 在写入前**真正比对**（读-改-写竞态防护） | 单测：重启后 revert 可用；外部改动后 replace 被拒 |
| 2.4 | 断点续跑 | `StatefulAgentRunner` + `AgentTaskStatus` | 每步 checkpoint（已完成步骤、已改文件、门禁结果），崩溃/超时后 `agent resume <id>` 从断点继续；重复执行步骤幂等 | 单测：断点序列化；集成：中途 kill 后 resume 成功 |
| 2.5 | 任务级幂等 | `executeDevTaskPlan` / `RepairLoop` | scaffoldToDir 已存在即跳过（幂等）；写操作带"期望旧内容"校验（乐观并发） | 单测：二次执行无副作用 |

**验收**：构造一个"编译必失败"的任务，跑完主树 `git status --porcelain` 与执行前一致；构造一个"第 40 步崩溃"的任务，`agent resume` 能继续到 `verify.passed=true`。

---

## 7. P3 — 收口（1.5d）

| # | 任务 | 内容 |
|---|---|---|
| 3.1 | 评测扩展 | `bin/eval-devtask.ps1` 增加 3 个探针：`verify-verdict`（verify 退出码）、`impl-step`（`--execute` 遇 implement 的行为）、`files-changed-anchor`（完成报告与 git 比对）；新增 K9（保护绕过）/K10（命令注入）登记，**注意**：这两项必须用一次性 worktree 做靶场，绝不能在主树真写 |
| 3.2 | 文档同步 | `AGENTS.md`（Definition of Done 增"门禁结构化裁决"）、`skills/browser4-cli/SKILL.md` + `skills/browser4-coding/SKILL.md`（`code verify` 用法与完成契约）、`cli/browser4-cli/src/help.rs` |
| 3.3 | CI 门禁 | `bin/test.ps1` 增加 `verify` 范围；PR 门禁跑 `coding` 单测 + `eval-devtask -Fast`（作为计划质量回归） |
| 3.4 | 默认值切换 | `finishGateCheck` 保持 strict；`browser4.agent.verify.enabled` 默认 true；隔离默认 off → 观察两轮后评估 |

---

## 8. 验收总纲（完成判据）

1. **评测门禁**：`bin/eval-devtask.ps1 -Strict` 退出码 0（即所有登记差距都已 XPASS 并删除登记）。
2. **单测/集成**：`mvn test -pl browser4-coding,browser4-agentic -am`；`cargo test --bin browser4-cli`；新增 verify/repair/isolation 三组测试。
3. **端到端**：完成一个真实任务（建议：给 `browser4-coding` 补一个纯函数 + 单测 + 文档），全程无人干预，产出 `verify.passed=true` 且 `filesChanged` 与 `git diff --stat` 一致。
4. **回滚**：故意让编译失败，主树 `git status` 与任务前一致。
5. **可判定**：CI 只用退出码 + `--json` 报告就能判定成败，不需要人读日志。

---

## 9. 风险与回退

| 风险 | 影响 | 缓解 |
|---|---|---|
| 完成判定收紧后"真完成"被误判 | 代理任务变红 | `finishGateCheck=warn` 一键降级；先观察轮次再切 strict |
| 命令整串校验误伤 | 合法复合命令被拒 | 白名单放行 `&&`/`;` 的已知安全组合；被拒时返回可复制的 reason |
| worktree 隔离与既有 `worker`/构建路径冲突 | 构建产物路径漂移 | 默认 off；与 `browser4.agent.workspace` 显式互斥并报错 |
| 修复回环放大 token 成本 | 单任务成本上升 | 默认 3 轮上限；诊断消息 ≤4k 字符；`TokenStats` 记账 |
| 评测脚本改动引入不可信门禁 | 评测自身失真 | 所有新探针走只读路径；破坏性探针在一次性 worktree 内运行并自动清理 |

---

## 10. 待决问题（开工前需拍板）

| # | 问题 | 选项 | 建议 |
|---|---|---|---|
| Q1 | 谁消费 `coding.verify` 的裁决？ | (a) 只在代理完成判定消费 (b) 代理 + 外部 supervisor 都能消费 | **(b)**，否则 CI 仍拿不到 |
| Q2 | 保护绕过的评测怎么做才安全？ | (a) 主树试写后恢复（脏） (b) 一次性 `git worktree` 靶场 (c) 不测，只靠单测 | **(b)**；单测覆盖为主、评测靶场为辅 |
| Q3 | 是否允许默认开启工作区隔离？ | (a) 立刻默认 on (b) 先 off 观察两轮 | **(b)**，隔离影响构建产物路径，需实测 |
| Q4 | `--execute` 遇到 `implement` 步骤的语义 | (a) 直接失败 (b) 打印待办并继续确定性步骤 (c) 自动 `git diff` 后交给代理 | **(b)**，保持只读可预测 |

---

## 11. 与既有文档的关系

| 文档 | 关系 |
|---|---|
| `eval-code-devtask.md` | 本计划的回归基线；K1–K8 是 P1/P0 的验收对象 |
| `browser4-agent-feedback-implementation-plan.md` | P3 的 3.1（gates schema）已落地于 `Models.kt:50`；3.2 只做了"零工具"守卫，**本计划 0.5 补其余三条** |
| `browser4-code-tool-supervision-v1.3/v1.4.md` | 假完成、门禁未锚定、命令被拦的历史证据，与本计划 G2/G3 对应 |
| `supervision-timeline-*.md` | 端到端失败模式（LLM 挂起、假完成、越界修改）的来源 |
| `AGENTS.md`「Definition of Done」 | P3.2 需要同步的位置 |

---

## 12. 实施状态（2026-10-21）

阶段语义与验收口径见 §3–§7；本节只记录**已落地**与**未开工**，不重复设计。

### 12.1 P0 — 验证权威层（已完成）

| 项 | 落点 | 测试 |
|---|---|---|
| 0.1 `coding.verify` 原语 | `browser4-coding/.../VerificationReport.kt`（`VerificationCheck` / `VerificationReport` / `VerificationRunner`）+ `CodingToolExecutor` 的 `verify` 分支 | `VerificationReportTest`、`CodingToolExecutorVerifyTest` |
| 0.2 工具边界保留结构 | `mvnBuild` / `validate` 的 `json=true` 结构化旁路；`verify` 末尾 `VERDICT_JSON:` 行 | `CodingToolExecutorVerifyTest.jsonModeReturnsPureJson` |
| 0.3 失败退出码 | CLI `code verify` / `code mvn` / `code validate` / `code devtask` 依 verdict 返回非零（`main.rs` 的 `code_command_failure`）；新增 `code verify` 命令 | e2e 评测 `G-VERIFY-VERDICT`；`cargo test --bin browser4-cli` |
| 0.4 Windows launcher 白名单 | `CodingAgentShell.normalizeCommandName()`（basename + 去 `.cmd/.bat/.exe`）、`MavenBuildSupport.mavenLauncher()` 返回绝对路径 | `CodingAgentShellTest`（Windows wrapper 名称在所有平台可用） |
| 0.5 完成判定锚定证据 | `FinishReportGate` + `RobustBrowserAgent.onTaskCompletion` 的取证；`AgentConfig.finishRequiredGates` / `finishWorkspace` | `FinishReportGateTest`（5 条拒绝路径 + warn 语义） |
| 0.6 保护覆盖补齐 | `CodingAgentFileSystem.writeFile/appendFile/copy/move/revert` 全部走 `protectionViolation()` | `CodingAgentFileSystemWritePathTest` |
| 0.7 命令整串校验 | `CodingAgentShell.validateSegments`（`&&`/`||`/`;`/`|`/`&`/换行、`$(...)`、反引号、`sh -c` 递归） | `CodingAgentShellTest` |
| 0.8 写入原子性 | `CodingAgentFileSystem.writeAtomic`（临时文件 + `ATOMIC_MOVE`）、`joinLines` 保留 CRLF 与末尾换行 | `CodingAgentFileSystemWritePathTest` |

### 12.2 P1 — 可执行（已完成）

| 项 | 落点 |
|---|---|
| 1.1 计划增加 `implement`/`verify` 步骤 | `DevTaskPlanner.IMPLEMENT_TOOL` + `coding.verify` 步骤；`--execute` 打印"需代理实现"后继续确定性步骤 |
| 1.2 验收清单 | `DevPlan.acceptance` → 计划尾部 `Acceptance: [...]` |
| 1.3 失败回环预算 | `RepairLoop`（`browser4-agentic/.../agents/RepairLoop.kt`）：失败 verdict → 有界诊断回灌 → 重试，`-Dbrowser4.agent.repair.maxRounds`（默认 3，0 关闭），超预算抛错并附 verdict；`VerificationReport.parseFromText/fromJson` 从工具结果读回裁决；`RepairLoopTest`（9 例） |
| 1.4 诊断精度 | `MavenBuildSupport.parseDiagnostics`：workspace-relative 路径 + 行/列 + `e:`/`w:` + `[ERROR] path:[12,5]` |
| 1.5 输出截断策略 | `CodingAgentShell.truncateOutput` 对编译/测试类命令尾部保留 |
| 1.6 模块图缓存 | `ModuleGraph.cachedGraph()`（pom mtime/size 签名）+ `scanPomFiles` 剪枝；`RepoConsistencyReporter` 的 manifest 扫描同样剪枝 |
| 1.7 计划步骤去噪 | `DevTaskPlanner.inferExplicitModules` / `inferFileModules` / `dropAncestorSignals` / `selectTargetModule`；裸文件名索引（`CodingAgentFileSystem.findFiles` + `DevTaskPlanner.bareFileNames`） |

回归基线：`bin/eval-devtask.ps1 -Strict` **60 pass / 0 fail / 0 known / 0 skipped，退出码 0**。K1–K8 全部 XPASS 后登记删除（`-Gap` 标记已移除），`G-PLAN-LATENCY` 判据改为"传输地板 + 1s"。

### 12.3 P2 — 全部落地

| 项 | 状态 |
|---|---|
| 2.1 工作区隔离（git worktree） | **已完成**：`WorkspaceIsolation`（`browser4-coding/.../WorkspaceIsolation.kt`）在 `<repo>/.browser4/worktrees/<runId>/` 建 worktree；`commit()` 用 `git apply --3way` 把 worktree 的补丁并回主树，`rollback()` 只删 worktree（主树从未被写）；`CodingToolExecutor.devTask --execute` 在 `-Dbrowser4.agent.workspace.isolation=worktree` 时整段跑在隔离树；`WorkspaceIsolationTest`（8 例，一次性 git 仓库） |
| 2.2 shell 副作用纳入事务 | **已完成（优先方案）**：隔离模式下步骤执行用的是以 worktree 为 baseDir 的 `CodingAgentShell` + 以 worktree 为 workspaceRoot 的 `CodingAgentFileSystem`，构建产物与 shell 副作用都落在隔离树，随 worktree 一起丢弃/合并 |
| 2.3 快照落盘 + 校验和比对 | **已完成**：`FileSnapshot.checksum` 改为 `sha256:` 内容哈希；快照落盘到 `<workspace>/.browser4/coding-snapshots/`（git-ignored），`revert` 跨重启可用；`persistSnapshots` 默认 false、生产接线开启；`CodingAgentFileSystemSnapshotTest`（8 例） |
| 2.4 断点续跑 | **已完成（dev-task 维度）**：`DevTaskCheckpoint`/`DevTaskCheckpointStore`（`browser4-coding/.../DevTaskCheckpoint.kt`，`.browser4/devtask-checkpoints/<id>.json`）；`coding.devTask(execute=true)` 每步落盘并在输出里给 `checkpoint id`；新工具 `coding.resumeDevTask(taskId)`（CLI `code resume <id>`）只跑剩余步骤、跳过已完成；失败保留 worktree 供续跑；`DevTaskCheckpointStoreTest`（7 例）+ `CodingToolExecutorResumeTest`（4 例）。**注**：这是计划维度的续跑，不是 LLM 会话上下文的精确重放 |
| 2.5 任务级幂等 | **部分**：`write(expectedChecksum=…)` 乐观并发（`checksumOf`/`coding.checksum`），陈旧写入被拒；`scaffoldToDir` 既有的"已存在即跳过"未变；`CodingToolExecutorConcurrencyTest`（5 例） |

### 12.4 P3 — 全部落地（3.3 为 advisory）

| 项 | 状态 |
|---|---|
| 3.1 评测扩展 | **已完成（可安全执行的部分）**：新增 `G-VERDICT-PASS/FAIL`（`code verify` 退出码 + `"passed"`）、`G-IMPL-STEP`（implement 契约 + `Acceptance:`）、`G-PROTECT`（K9：`code write VERSION` 被拒且内容不变，探针自愈）、`G-INJECTION`（K10：`&&`/`;`/`|` 三种复合命令被拒）；`-Strict` 现为 **65 pass / 0 fail / 0 skipped**。未做 `files-changed-anchor`（需要真实 LLM 会话）与 `$(…)` 形态探针（pwsh wrapper 先消费 `$()`，由 `CodingAgentShellTest` 覆盖）。 |
| 3.2 文档同步 | **已完成**（`AGENTS.md` Definition of Done、`skills/browser4-coding/SKILL.md`、`cli/browser4-cli/src/help.rs`、本文件、`eval-code-devtask.md`、`bin/README.md`） |
| 3.3 CI 门禁 | **已完成**：`bin/test.ps1` 新增 `verify` scope（转发 `eval-devtask.ps1`，默认 `-Fast`）+ `bin/README.md`；新增独立 workflow `.github/workflows/plan-quality.yml`（`schedule` 每日一次 + `workflow_dispatch`，YAML 已用 PyYAML 校验）——**advisory，不阻断 PR**（评测要自建后端/构建 bundle，先观察两轮再决定是否升级为门禁），并上传 JSON 报告 |
| 3.4 默认值切换 | **已定案（保持现状并记录理由）**：`finishGateCheck` 默认 `strict`；`browser4.agent.repair.maxRounds=3`（`0` 关闭）；`persistSnapshots` 生产开启；`workspace.isolation` 默认 `off`（观察两轮后再评估，计划 Q3(b)）；不新增 `browser4.agent.verify.enabled` 开关——verify 只能由调用方（工具/CLI/finish gate）触发，没有需要全局关闭的默认路径 |

- P3 已全部落地（3.3 为 advisory workflow）；P3.2 文档（AGENTS.md Definition of Done、
  `skills/browser4-coding/SKILL.md`、`cli/browser4-cli` 的 `code verify`）已同步。
- 因此 §8 的验收总纲**尚未全绿**：第 1 条（`-Strict` 退出码 0）已达成；第 2–3 条已达成（单测 + 端到端 verify 链路）；第 4 条（失败可回滚，主树干净）需要 2.1/2.2 才能验证；第 5 条（CI 只读退出码 + `--json`）已具备，尚缺 P3.3 的 CI 接入。

### 12.5 真实模型验收后的两项加固（2026-10-22）

一次真实 DeepSeek 端到端验收（`agent run` 无人干预，产出 `AcceptanceSummary` + 单测）暴露了两个问题，已修并各自带测试：

1. **workspace 供给缺模块链接** —— 代理在自己的 scratch workspace 里跑 Maven 时遇到 `Could not find the selected project in the reactor`，不得不自己 `mklink /J` 补齐 13 个模块链接、复制 `pom.xml`/`mvnw`/`VERSION`，中途还 `coding_delete` 掉 `browser4-coding` 再重建。
   现由 `WorkspaceMirror` 在 coding 模式 agent 的 `AgentToolManager` 初始化时一次性完成：按根 pom 的 `<module>` 逐个建 junction（非 Windows 用 symlink）、链接 `.mvn`、复制 `pom.xml`/`mvnw`/`mvnw.cmd`/`VERSION`/`ROOT.md`；幂等、不覆盖已有条目、真实 checkout 与无源仓目录都跳过。验证：全新任务 60.7s 完成（此前 442s/537s），首个 `coding.verify(module="browser4-coding")` 直接 PASSED，轨迹里 `mklink`/`coding_delete` 调用数均为 0。
2. **`delete` 允许删除 junction** —— 轨迹显示 `coding_delete{path:"browser4-coding", recursive:true}` 成功。现拒绝删除链接/junction 目标，也拒绝递归删除包含链接的目录（`CodingAgentFileSystemLinkGuardTest`，3 例）。
3. **完成门禁的「叙述 vs 轨迹」缺口** —— 门禁原先只比对结构化断言（gate 名字、filesChanged ⊆ git、必需门禁），散文里的不实叙述（「文件已存在，我只确认了内容」）无法判定。现补三件事：
   - `CodingAgentFileSystem` 记录 per-run **变更日志**（`FileMutation{op,path,ok}`：write/append/edit/replace/replaceLines/insert/revert/delete/copy/move，上限 200，可 reset），由完成门禁读取；
   - 完成报告新增结构化字段 `deletedFiles`（模型 schema、`TextToAction` 解析、`system.taskComplete` 工具、prompt 四处同步）；门禁校验「声称的删除 ⊆ 本次记录的删除 ∪ git 删除」，并校验「本次实际改动的仓库内文件必须都出现在 `filesChanged`」（`.browser4/` 与仓库外临时文件不计）；
   - 报告里引用的 `VERDICT_JSON` 必须与本次真实记录的 verdict 一致（比对 `passed` 与各 check 的 `ok`）。为此在工具执行时即捕获 verdict，修掉「verify 与 taskComplete 同一轮导致记录不到」的时序缺口：首次重建实测命中该假阳性并被修正，二次运行 80.6s 通过且逐字引用真实 verdict。
   - `mklink`/`ln`/`mount` 移入 `FORBIDDEN_COMMANDS`（绝对否决，任何 flag 都抬不起来），workspace 拓扑改由框架供给。
   - 回归：`browser4-coding` 364 tests、agentic 定向 107 tests、`eval-devtask.ps1 -Fast` 61 pass。仍存缺口：语义层的叙述性撒谎不可判定——目前靠结构化断言 + 变更日志比对；LLM 审计留作评测/夜间选项。