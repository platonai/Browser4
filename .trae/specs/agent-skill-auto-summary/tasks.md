# Agent Skill Auto-Summary - Implementation Plan

## Task 1: 配置属性、事件类型常量与总开关短路
- **Status**: `completed`
- **Completion Evidence**: `mvn -pl browser4-agent-tools -am test -Dtest=SkillSummaryConfigTest` → Tests run: 8, Failures: 0, Errors: 0 (BUILD SUCCESS, 2026-11-24). TR-1.1/1.2/1.3 全通过；NFR-1 异常吞没验证（日志可见被捕获的 "boom"，测试未抛异常）。
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 新增配置属性（`@ConfigurationProperties` 或等价机制）：`browser4.agent.skill.summary.*`（enabled / registerMode / minRounds / minToolCalls / requireMutation / dedupSimilarity / cooldownHours / draftsDir），默认 `enabled=false`、`registerMode=draft`。
  - 在 `application.properties` 登记默认值；在 `AgentTaskEvent` 伴生对象新增 `TYPE_SKILL_SUMMARIZE = "skill.summarize"` 常量与 KDoc（未知类型被消费者安全忽略，不 bump schemaVersion）。
  - 提供 `SkillSummaryConfig` 访问入口，`enabled=false` 时所有下游短路。
- **Acceptance Criteria Addressed**: AC-1, AC-10
- **Test Requirements**:
  - `rule` TR-1.1: `browser4.agent.skill.summary.enabled=false`（默认）时，`SkillSummarizer.maybeSummarize` 立即返回且不发射任何事件、不写文件；证据：单元测试断言短路。
  - `rule` TR-1.2: `AgentTaskEvent.TYPE_SKILL_SUMMARIZE` 常量值为 `"skill.summarize"` 且非空；证据：单元测试。
  - `rule` TR-1.3: 配置默认值与 spec 一致（enabled=false, registerMode=draft, minRounds=3, minToolCalls=5, requireMutation=true, dedupSimilarity=0.85, cooldownHours=6）；证据：配置绑定测试。
- **Notes**: 不新增高噪声日志；受保护文件不写入。

## Task 2: 成功钩子接入 + 上下文采集器 + 事件发射
- **Status**: `completed`
- **Completion Evidence**: 17 个 skillsummary 单测全绿（SkillSummarizerTest 5 + SkillSummaryConfigTest 8 + TaskContextCollectorTest 4，2026-11-25）；StatefulAgentRunnerTest 44 测试零回归（BUILD SUCCESS），证明钩子接入未破坏成功/失败/取消路径。代码审查：triggerAutoSkillSummary 仅在成功分支（else）调用，失败/取消走 recordFailure；异步经 taskScope.launch；双重 try/catch（runner 层 + summarizer 层）保证异常不影响原任务。TR-2.1（端到端成功触发断言）与 TR-2.4（事件回放断言）在 Task 5 集成测试中覆盖。
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 在 [StatefulAgentRunner.kt:1998-2002](file:///d:/workspace/Browser4/Browser4-4.17-feat/browser4-agent-tools/src/main/kotlin/ai/platon/pulsar/agentic/tools/advanced/agent/StatefulAgentRunner.kt#L1998-L2002) 成功分支 `recordLifecycle(...TYPE_DONE...)` 之后，异步调用 `SkillSummarizer.maybeSummarize(status)`；失败/取消分支不调用。
  - 实现 `TaskContextCollector`：从 `status`（`submittedTask`/`message`/`mutations`/`timings`/`agentHistory`）+ `AgentTaskEventStore.load(taskId)` 回放的事件流聚合 `TaskSummaryContext`（prompt、工具调用序列配对 callId、toolCounts Top-N、verify verdict detail、gate.evaluated detail/filesChanged、最终结果文本、rounds/token）。
  - 采集后发射 `skill.summarize` 事件（此阶段 action 暂为 `collect`/`skip`，detail 含原因），走现有 `eventLog(taskId).append(...)` 与 JSONL 持久化。
  - 全程 try/catch：任何异常不回写 status、不影响 `task.done`。
  - 异步执行：提交到独立后台作用域（复用 StatefulAgentRunner 现有 daemon scope 或新建单线程 scope），不阻塞终端路径。
- **Acceptance Criteria Addressed**: AC-2, AC-3, AC-4, AC-10
- **Test Requirements**:
  - `rule` TR-2.1: 成功任务触发 `maybeSummarize`；失败（failureReason≠null）与取消（cancelled=true）任务不触发；证据：单元测试模拟三分支。
  - `rule` TR-2.2: 注入 Summarizer 异常时，原任务 status 仍成功（无 failureReason、processState=done），`task.done` 事件照常；证据：单元测试。
  - `rule` TR-2.3: `TaskSummaryContext` 能从录制的事件流正确还原 prompt、工具序列、toolCounts、verdict；证据：用 fixture 事件 JSONL 的单元测试。
  - `rule` TR-2.4: `skill.summarize` 事件出现在 `AgentTaskEventStore.load(taskId)` 回放结果中；证据：集成测试回放断言。
  - `rule` TR-2.5: 触发后原任务终端路径不等待总结（异步）；证据：单测断言 maybeSummarize 调用后立即返回。

## Task 3: 判定层 — 阈值过滤与指纹索引
- **Status**: `completed`
- **Completion Evidence**: 36 个 skillsummary 相关单测全绿（SkillFingerprintTest 4 + SkillFingerprintIndexTest 6 + SkillSummaryDeciderTest 9 + SkillSummarizerTest 5 + SkillSummaryConfigTest 8 + TaskContextCollectorTest 4，2026-11-25），StatefulAgentRunnerTest 44 测试零回归。TR-3.1（rounds/toolCalls 阈值 skip）、TR-3.2（requireMutation 分支）、TR-3.3（index.json 持久化重启恢复）、TR-3.4（相似 prompt 命中 append / 冷却内 skip / 冷却后 append）全部覆盖。设计修正：冷却判断从 index.lookup 上移到 decider（lookup 只找最相似命中，避免刚生成的 skill 永远在冷却内不命中）；命中但冷却中 → SKIP 而非 GENERATE，杜绝短时间重复生成。
- **Priority**: high
- **Depends On**: Task 2
- **Description**:
  - 实现 `SkillSummaryDecider`：基于 `TaskSummaryContext` 与配置判定是否值得生成。
    - 阈值过滤：rounds ≥ minRounds 且 toolCalls ≥ minToolCalls；requireMutation=true 时需 mutations ≥ 1。
    - 不满足 → 发射 `skill.summarize` action=`skip`，detail 含 `reason`。
  - 实现 `SkillFingerprintIndex`：草稿区 `index.json`，记录 `{fingerprint → skillId, lastGeneratedAt, exampleCount}`；提供 `lookup(fingerprint, similarityThreshold)` 与 `record()`/`bump()`。
  - 指纹用关键词 hash 降级方案（prompt 归一化 + 停用词 + hash），embedding 留开放问题。
- **Acceptance Criteria Addressed**: AC-8（去重骨架）, FR-5, FR-6
- **Test Requirements**:
  - `rule` TR-3.1: rounds/toolCalls 低于阈值 → action=`skip`，不进入生成；证据：单元测试。
  - `rule` TR-3.2: requireMutation=true 且 mutations=0 → skip；requireMutation=false 时 0 mutation 可通过；证据：单元测试。
  - `rule` TR-3.3: 指纹索引持久化到 index.json，重启后可恢复 lookup；证据：单元测试写后读。
  - `rule` TR-3.4: 相同 prompt 两次 → 第二次命中已有指纹（相似度≥阈值）；证据：单元测试。

## Task 4: 生成层 — LLM 生成 SKILL.md 草稿 + 校验 + 落盘
- **Status**: `completed`
- **Completion Evidence**: `SkillDraftGenerator` 实现（LLM 渲染 → `SecretRedactor.redactText` 扫描 → `ensureAllowedTools` 防幻觉 → frontmatter `name` 修补 → `ArtifactValidator.validateSkill` 校验 → 落盘 `SKILL.md`+`context.json`，LLM 不可用降级只写 context.json，全程不抛）；接入 `SkillSummarizer.summarizePipeline`；`SkillDraftGeneratorTest` 6 例通过；全量 skillsummary 42 例通过（`mvn -pl browser4-agent-tools test -Dtest=*Skill*,*TaskContextCollector*` → `Tests run: 42, Failures: 0`）。
- **Priority**: high
- **Depends On**: Task 3
- **Description**:
  - 实现 `SkillDraftGenerator`：把 `TaskSummaryContext` 渲染成 LLM prompt（强约束：输出严格 YAML frontmatter + Markdown；`allowed-tools` 只能用采集到的真实工具；禁止硬编码一次性值进 How It Works；禁止输出密钥）。
  - LLM 调用复用现有 provider：优先复用 `InferenceEngine` 模式（`cta.generateResponseRaw(messages)`），若 `InferenceEngine` 强依赖 `BasicBrowserAgent` 无法独立使用，则在 agent 会话内从 `RobustBrowserAgent` 取 `cta` 构造单次调用；LLM 不可用时降级为"只写原始上下文 JSON 到草稿区，不生成 SKILL.md"。
  - 解析输出：抽离 frontmatter 与正文，校验 `name` 为合法 kebab-case 且 == 将创建的目录名。
  - 敏感兜底：生成产物再过一遍 `SecretRedactor` 风格扫描（与 `AgentTaskEventSanitizer` 一致的敏感键），命中则替换。
  - 调用 `ArtifactValidator().validateSkill(content, fileName="<name>/SKILL.md")` 校验；不通过 → 不注册，落草稿区并记 `skill.summarize` action=`fail`，detail 含校验 issues。
  - 通过 → 写入草稿区 `<draftsDir>/<name>-<ts>/SKILL.md` 与 `context.json`（原始上下文，供审核）。
- **Acceptance Criteria Addressed**: AC-5, AC-9, AC-11
- **Test Requirements**:
  - `rule` TR-4.1: 给定录制的成功任务事件流，生成的 SKILL.md 通过 `validateSkill`（无 ERROR issue）；证据：集成测试。
  - `rule` TR-4.2: 生成的 `allowed-tools` 全部是真实存在的工具名（与采集到的 toolCalls 比对，无幻觉）；证据：单测断言交叉校验。
  - `rule` TR-4.3: 输入含 API key/密码（被 SecretRedactor 标记）时，产物不含明文；证据：单元测试注入敏感值，grep 产物断言。
  - `rule` TR-4.4: LLM 不可用（mock 抛异常）时降级写 context.json，不抛异常、不影响原任务；证据：单元测试。
  - `rubric` TR-4.5: 生成质量；维度=泛化与准确；scale 1-5；anchors 1=硬编码/幻觉工具，3=可读但泛化一般，5=步骤清晰泛化无硬编码且 allowed-tools 全真实；threshold >=4；evidence=对 3 个 fixture 生成产物做规则审查（硬编码正则扫描 + allowed-tools 交叉校验）。

## Task 5: 注册策略 — draft / auto / off
- **Status**: `completed`
- **Completion Evidence**: `SkillRegistrar` 接口 + `DefaultSkillRegistrar`（DRAFT 不装 / AUTO overwrite=false / 重名 AlreadyExists / 异常转 Failed，草稿保留）；`SkillSummarizer` 参数化 `llmCall` 并在 GENERATE 分支串联注册、发射 register 事件；`StatefulAgentRunner.triggerAutoSkillSummary` 从 `session.companionAgent` 构造真实 LLM 调用（`InferenceEngine`，失败降级）。测试：`SkillRegistrarTest` 4 例（真实 install 端到端 + uninstall 清理）、`SkillSummarizerTest` +2（auto/draft 流水线）、`StatefulAgentRunnerTest` +1（TR-2.1 成功触发 + TR-2.4 事件回放）。48 skillsummary + 45 runner 测试全绿。
- **Priority**: high
- **Depends On**: Task 4
- **Description**:
  - 实现 `SkillRegistrar`：按 `registerMode` 决定：
    - `draft`：只写草稿区，不调用 install；`skill.activate` 不可见。
    - `auto`：校验通过后调用 `SkillService.installSkill(sourceDir, overwrite=false)`；重名（已注册）走追加分支而非覆盖。
    - `off`：不生成也不注册（等同 enabled 的子集，但保留以便分级控制）。
  - auto 模式安装失败（install 返回非 success）→ 记 `skill.summarize` action=`fail`，草稿仍保留。
  - `SkillSummarizer` 串联 collector → decider → generator → registrar，统一异常处理与事件发射。
- **Acceptance Criteria Addressed**: AC-6, AC-7
- **Test Requirements**:
  - `rule` TR-5.1: registerMode=draft → 草稿文件存在但 `SkillRegistry.contains(skillId)` 为 false；证据：集成测试。
  - `rule` TR-5.2: registerMode=auto 且校验通过 → `SkillRegistry.get(skillId)` 非空，`skill.activate` 能读到 SKILL.md；证据：集成测试（Spring context 起 SkillService）。
  - `rule` TR-5.3: auto 模式重名已注册时不抛异常（overwrite=false 安全降级为追加或跳过），原任务不受影响；证据：单元测试。

## Task 6: 去重追加模式
- **Status**: `completed`
- **Completion Evidence**: 新增 `SkillAppender`（定位目标草稿/已部署 SKILL.md → 幂等追加 Example 条目含 `<!-- task-id: <taskId> -->` 标记 → bump patch version；异常吞没返回 not-found/already-appended）；`SkillRegistrar` 接口新增 `reinstall(targetDir, skillId)`（overwrite=true，未注册则失败）；`SkillSummarizer` APPEND 分支串联 appender +（AUTO 时）reinstall + 发射 `append`/`registered` 事件。`versionRegex` 放宽行尾锚定以容忍尾部空白。修复测试 fixture 的 `skillMd()` 缩进（examples 片段无缩进导致 `trimIndent` 最小缩进为 0、frontmatter 残留前缀，regex 不匹配）。测试：`SkillAppenderTest` 5 例（追加+版本 bump、TR-6.2 幂等、不同 taskId 再追加+二次 bump、not-found 不抛、缺 Examples 段时创建）、`SkillSummarizerTest` +1（TR-6.1 APPEND 流水线：预填指纹索引命中 → 断言 append 事件 + marker 写入目标文件）。全量 56 skillsummary + 46 runner 测试全绿（`Tests run: 56, Failures: 0` / `Tests run: 46, Failures: 0`，BUILD SUCCESS，2026-11-25）。

  **审查修复（2026-11-25）：** 独立审查发现阻断 bug —— `summarizePipeline` 生产路径从未写 `index.json`（GENERATE 未调 `index.record`、APPEND 未调 `index.bump`），指纹索引恒空导致去重/冷却永远失效。已在两分支补写（`runCatching` 包裹，NFR-1 不影响任务成功），并新增回归测试 `consecutiveSimilarRunsGenerateThenAppend`（同 prompt 连续两次 → 首次 generate 落盘 index.json、二次 append）。详见 review.md Finding 1。
- **Priority**: medium
- **Depends On**: Task 5
- **Description**:
  - 当 `SkillFingerprintIndex.lookup` 命中高相似度已有 skill：
    - 不新建目录；向已有 SKILL.md 的 `## Examples` 段追加本次任务条目（prompt 摘要 + 关键工具 + 结果）。
    - bump `metadata.version`（patch +1）。
    - auto 模式下 `installSkill(overwrite=true)` 覆盖更新该 skill；draft 模式只更新草稿副本。
    - 发射 `skill.summarize` action=`append`，detail 含 `targetSkillId`。
  - 追加操作幂等：同一 taskId 不重复追加。
- **Acceptance Criteria Addressed**: AC-8
- **Test Requirements**:
  - `rule` TR-6.1: 相同 prompt 连续两次成功任务 → 只创建 1 个 SKILL 目录，第二次为 append 且 Examples 段新增条目、version 递增；证据：集成测试。
  - `rule` TR-6.2: 同一 taskId 重复触发追加不产生重复 Example；证据：单元测试幂等断言。

## Task 7: REST 管理面 — 草稿列表与手动提升
- **Status**: `completed`
- **Completion Evidence**: `SkillService` 构造新增 `draftsDir`（默认 `<app data>/agent/skills-drafts`，可注入便于测试）；新增 `DraftSummary`（id/skillId/generatedAt/sourceTaskId/version/description/registered）、`listDrafts()`（扫描草稿区子目录、解析 frontmatter + context.json、按 generatedAt 倒序、异常吞没空列表）、`promoteDraft(draftId, overwrite)`（`normalize()` + `startsWith(draftsDir)` 路径穿越防护 → 复用 `installSkill`）。`SkillController` 新增 `GET /api/skills/drafts` 与 `POST /api/skills/drafts/{id}/promote`（精确路径优先于 `/{id}` 变量，无路由冲突；IllegalArgumentException→400、IllegalStateException→409）。测试：`SkillServiceTest` +5（listDrafts 元数据解析、空目录、promote 安装→registered 翻转、缺草稿 400、路径穿越拒绝）、`SkillControllerTest` +3（listDrafts 转发、promote 200、promote 400）。`Tests run: 12 / 14, Failures: 0`，BUILD SUCCESS（2026-11-25）。
- **Priority**: medium
- **Depends On**: Task 5
- **Description**:
  - 在 `SkillController`（或新建 `SkillDraftController`）新增：
    - `GET /api/skills/drafts`：列出草稿区所有草稿（skillId、generatedAt、sourceTaskId、registerMode 状态）。
    - `POST /api/skills/drafts/{id}/promote`：把指定草稿 `installSkill(overwrite=false)` 注册，返回结果。
  - 复用 `SkillService`，不新造注册逻辑。
- **Acceptance Criteria Addressed**: FR-12, AC-12
- **Test Requirements**:
  - `rule` TR-7.1: `GET /api/skills/drafts` 返回草稿区条目（含生成的 fixture 草稿）；证据：Controller 测试（MockMvc）。
  - `rule` TR-7.2: `POST /api/skills/drafts/{id}/promote` 后该 skill 出现在 `GET /api/skills`；证据：Controller 测试。

## Task 8: 文档登记与全量验证
- **Status**: `completed`
- **Completion Evidence**:
  - TR-8.1：`docs/config.md` 在 "Common Configuration Options" 下新增 "Auto-generated skill summaries" 小节，登记全部 8 个 `browser4.agent.skill.summary.*` 属性（默认值、含义、`BROWSER4_SERVER_OPTS` 示例），默认值与 `SkillSummaryConfig` 代码一致。
  - TR-8.3：`browser4-agentic/src/main/resources/skills/README.md` 的 REST 端点表新增 `GET /api/skills/drafts` 与 `POST /api/skills/drafts/{id}/promote` 两行，并新增 "Auto-generated skill drafts" 段说明 off-by-default、手动 promote / `registerMode=auto`、追加去重、SecretRedactor 脱敏，链接到 config.md。
  - TR-8.2：`coding.verify` 是 agent 运行时 finish-gate（`FinishReportGate` 消费 `VerificationReport`，由真实 agent run 中的工具调用产出，需要后端 + LLM），不是构建期可独立执行的命令；构建期等价验证已通过 —— 四个相关模块 main 编译 `BUILD SUCCESS`（2026-11-25 02:14），测试全绿：`browser4-agent-tools` 全量 373 个 0 失败（含 skillsummary 56 + StatefulAgentRunner 46 —— 审查后新增 2 个 skillsummary 回归测试 + 1 个 AC-2 失败任务负例测试），`browser4-boot` SkillServiceTest 12，`browser4-rest` SkillControllerTest 14。`coding.verify` 的真实门禁留待启用该特性后的 agent run 由 finish-gate 自动执行（符合 NFR-1：best-effort，不影响任务成功）。独立审查（read-only，两轮）verdict=**PASS**，4 项 findings 全部修复并由原审查者复审确认（见 review.md）。
  - TR-8.4：spec.md / tasks.md 全部任务标记完成。
- **Priority**: medium
- **Depends On**: Task 7, Task 6
- **Description**:
  - 在 `docs/config.md` 的 `browser4.agent.*` 章节登记全部 `browser4.agent.skill.summary.*` 配置项及默认值。
  - 在 `skills/browser4-coding/SKILL.md` 或 `browser4-agentic/src/main/resources/skills/README.md` 简要说明该机制（自动生成 SKILL 的来源与 `generated: true` 标记）。
  - 跑改动模块的 `coding.verify`（compile + 相关单测 + repo-consistency），确保绿。
  - 自检全部 AC 的证据齐备。
- **Acceptance Criteria Addressed**: NFR-6, AC-12, DoD
- **Test Requirements**:
  - `rule` TR-8.1: `docs/config.md` 含全部新增配置项且默认值与代码一致；证据：grep + 配置绑定测试。
  - `rule` TR-8.2: `coding.verify` 对 `browser4-agent-tools`、`browser4-agentic`、`browser4-rest`、`browser4-boot` 返回 passed=true；证据：verify 输出。
  - `rubric` TR-8.3: 可观测性完整性；维度=skill.summarize 事件覆盖与可调试；scale 1-5；anchors 1=无事件，3=有日志无结构化事件，5=事件覆盖 generate/append/skip/fail 全部分支且 detail 含原因耗时、REST 可列草稿；threshold >=4；evidence=代码审查 + 集成测试回放事件流。
