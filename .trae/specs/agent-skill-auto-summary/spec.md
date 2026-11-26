# Agent Skill Auto-Summary - Product Requirements Document

## Overview
- **Summary**: 每次 agent 任务经 finish gate 判定成功后，自动把本次运行的做法总结成一个可复用的 Browser4 SKILL（含 `SKILL.md`），写入草稿区，并按配置决定是否注册到 `SkillRegistry` 供后续 `agent run` 复用。
- **Purpose**: 把一次性成功的任务经验沉淀为可复用资产，降低同类任务的重复推理成本；默认保守（仅落草稿、不自动激活），质量与安全可控。
- **Target Users**: 使用 `browser4-cli agent run` 或 coworker 调度执行重复性工程任务的开发者/自动化运维。

## Goals
- 任务成功后自动产出一份符合现有 SKILL.md 规范的草稿。
- 产出的 SKILL 可被现有 `coding.validate(type="skill")` 校验通过，并能被 `skill.activate` / `skill.run` 复用。
- 不污染仓库、不破坏 finish gate 语义、不泄漏敏感信息。
- 通过去重与阈值避免低价值/重复 SKILL 爆炸。

## Non-Goals
- 不生成 TRAE IDE 侧 `.trae/skills/` 的 skill（与 Browser4 后端解耦；留作可选后续）。
- 不自动修改/回写仓库内 `skills/` 下的人工维护 SKILL。
- 不对失败、取消、repair 耗尽的任务做总结。
- 不保证自动生成 SKILL 的内容 100% 准确（默认仅草稿，需人工审核后才注册）。
- 不引入新的 LLM provider 或密钥配置（复用现有 `llm.provider.config.path`）。

## Background & Context
- 成功终态判定在 [StatefulAgentRunner.kt:1998-2002](file:///d:/workspace/Browser4/Browser4-4.17-feat/browser4-agent-tools/src/main/kotlin/ai/platon/pulsar/agentic/tools/advanced/agent/StatefulAgentRunner.kt#L1998-L2002)：`status.done()` 后，`cancelled==false && failureReason==null` 时发射 `task.done` 事件。此处 `status` 与 `eventLog` 持有完整上下文。
- 全流程事件已 JSONL 持久化（`AgentTaskEventStore`），含 `tool.call`/`tool.result`/`verify.verdict`/`gate.evaluated`/mutation trace/stats，且经 `AgentTaskEventSanitizer` + `SecretRedactor` 脱敏。
- SKILL 运行时安装能力已存在：`skill.install` → `SkillService` → `SkillRegistry.register`，支持运行时热注册，安装目录为 `AgentPaths.SKILLS_DIR`（`<app data>/agent/skills`）。
- SKILL.md frontmatter 规范见 [skills/browser4-coding/SKILL.md:1-7](file:///d:/workspace/Browser4/Browser4-4.17-feat/skills/browser4-coding/SKILL.md#L1-L7) 与 [skills README:203-263](file:///d:/workspace/Browser4/Browser4-4.17-feat/browser4-agentic/src/main/resources/skills/README.md#L203-L263)；`coding.validate(type="skill")` 校验 name/description/allowed-tools/目录名一致性。
- 项目约定：新增行为默认不自动启用，需用户显式开启（见项目记忆）。

## Functional Requirements
- **FR-1**: 提供总开关 `browser4.agent.skill.summary.enabled`，默认 `false`；关闭时全链路零行为变化。
- **FR-2**: 仅当任务成功（finish gate 通过、无 failureReason、未取消）时触发总结；失败/取消/repair 耗尽均不触发。
- **FR-3**: 总结以异步副作用执行，在 `task.done` 之后，不阻塞终端路径，不回写 `status`，不影响原任务 verdict。
- **FR-4**: 采集本次运行的上下文：原始 prompt、工具调用序列（配对 callId）、工具频次 Top-N、改动文件清单、verify verdict、最终结果、耗时/轮次/token。
- **FR-5**: 判定层过滤：最小轮次、最小工具调用数、是否要求有文件改动；不满足则跳过（记原因）。
- **FR-6**: 去重：对 prompt 做语义指纹，与已生成 SKILL 比对；高相似度则追加到已有 SKILL 的 Examples 并 bump 版本，不新建。
- **FR-7**: 生成层用 LLM 按固定模板生成 SKILL.md 草稿，frontmatter 含 `generated: true`、`sourceTaskId`、`generatedAt` 溯源标记。
- **FR-8**: 生成后调用现有 `coding.validate(type="skill")` 等价校验；不通过则不注册，仅落草稿并记失败原因。
- **FR-9**: 注册模式三级：`draft`（默认，只写草稿不注册）、`auto`（校验通过自动 `skill.install(overwrite=false)`）、`off`。
- **FR-10**: 草稿写入 `<app data>/agent/skills-drafts/`，不回写仓库；维护 `index.json` 指纹索引供去重。
- **FR-11**: 可观测：新增 `skill.summarize` 事件（detail 含 action/reason/skillId/durationMs），走现有事件流与 JSONL 持久化，`/api/commands/{id}/events` 可见。
- **FR-12**: REST 管理面：`GET /api/skills/drafts` 列草稿，`POST /api/skills/drafts/{id}/promote` 手动安装，形成审核闭环。

## Non-Functional Requirements
- **NFR-1**: 总结失败（LLM 异常、校验失败、IO 异常）绝不反向影响原任务成功状态，全部 best-effort + try/catch。
- **NFR-2**: 生成内容不得含密钥/敏感值；输入复用现有脱敏链路，生成层二次兜底扫描。
- **NFR-3**: 不新增高噪声日志；成功/跳过 INFO，失败 WARN。
- **NFR-4**: 不写入仓库受保护文件（VERSION/AGENTS.md/root pom/BOM/CI）。
- **NFR-5**: 默认配置（`enabled=false` / `registerMode=draft`）下，现有测试与行为零回归。
- **NFR-6**: 新增配置项在 `docs/config.md` 登记。

## Constraints
- **Technical**: 复用现有 LLM provider、SkillRegistry、AgentTaskEventStore、coding.validate；不得新造校验/注册/脱敏逻辑。
- **Business**: 默认保守关闭；自动注册需显式配置 `registerMode=auto`。
- **Dependencies**: 依赖 finish gate 已判定成功（`gate.evaluated` passed）作为触发前提。

## Assumptions
- 任务成功时 `eventLog(taskId)` 内存 ring buffer 仍持有最近事件；更早事件可从 `AgentTaskEventStore.load(taskId)` 回放。
- LLM provider 在运行时可用（若不可用，生成层降级为只写原始上下文草稿，不调 LLM）。
- `skill.install(overwrite=false)` 的重名行为可用于"追加而非覆盖"的去重策略。

## Acceptance Criteria

### AC-1: 默认零行为变化
- **Type**: `rule`
- **Given**: `browser4.agent.skill.summary.enabled=false`（默认）
- **When**: 任意 agent 任务成功结束
- **Then**: 不产生任何总结副作用，无 `skill.summarize` 事件，无草稿文件，无 SKILL 注册；现有测试全部通过
- **Pass Condition**: 现有相关测试套件在默认配置下绿；代码路径在 enabled=false 时短路
- **Evidence**: 单元测试断言 enabled=false 时 Summarizer 不被调用；现有 StatefulAgentRunner 测试无回归

### AC-2: 仅成功任务触发
- **Type**: `rule`
- **Given**: `enabled=true`
- **When**: 任务成功（task.done）/ 失败（task.failed）/ 取消（task.cancelled）
- **Then**: 仅成功任务触发总结尝试；失败与取消不触发
- **Pass Condition**: 单测模拟三种终态，断言只有成功分支调用 Summarizer
- **Evidence**: 单元测试覆盖成功/失败/取消三分支

### AC-3: 总结失败不影响原任务
- **Type**: `rule`
- **Given**: `enabled=true` 且 Summarizer 内部抛异常
- **When**: 任务成功结束
- **Then**: 原任务仍标记成功，verdict 不变，`task.done` 照常发射；异常仅被捕获记日志
- **Pass Condition**: 单测注入 Summarizer 异常，断言 status 仍为成功且无 failureReason
- **Evidence**: 单元测试 + try/catch 包裹的代码审查

### AC-4: 异步不阻塞
- **Type**: `rule`
- **Given**: `enabled=true`，LLM 调用耗时 10s
- **When**: 任务成功
- **Then**: `task.done` 立即发射，原任务终端路径不等待总结完成
- **Pass Condition**: 总结提交到独立后台作用域；单测断言触发后立即返回
- **Evidence**: 代码审查（异步提交）+ 单元测试

### AC-5: 生成的 SKILL 通过校验
- **Type**: `rule`
- **Given**: 一次录制的真实成功任务事件流
- **When**: 生成层产出 SKILL.md
- **Then**: 该 SKILL.md 通过 `coding.validate(type="skill")`（name==目录名、description 1-1024、allowed-tools 真实存在）
- **Pass Condition**: 集成测试对生成产物调用校验，返回通过
- **Evidence**: 集成测试 + 校验输出

### AC-6: draft 模式不注册
- **Type**: `rule`
- **Given**: `registerMode=draft`
- **When**: 任务成功并生成 SKILL
- **Then**: SKILL.md 写入草稿区，但 SkillRegistry 中查不到该 skill；`skill.activate` 不可用
- **Pass Condition**: 草稿文件存在；SkillRegistry.list 不含该 id
- **Evidence**: 集成测试断言文件存在 + registry 未注册

### AC-7: auto 模式自动注册且可复用
- **Type**: `rule`
- **Given**: `registerMode=auto`，生成校验通过
- **When**: 任务成功
- **Then**: 该 skill 出现在 SkillRegistry，后续 `skill.activate` 能读到完整 SKILL.md
- **Pass Condition**: registry 含该 id；activate 返回内容
- **Evidence**: 集成测试

### AC-8: 相似任务去重
- **Type**: `rule`
- **Given**: 已存在一个由相似 prompt 生成的 SKILL
- **When**: 又一个高相似度 prompt 的任务成功
- **Then**: 不新建 SKILL 目录，而是向已有 SKILL 追加 Example 并 bump `metadata.version`
- **Pass Condition**: 目录数不增；已有 SKILL.md 的 Examples 段新增条目；version 递增
- **Evidence**: 集成测试（相同 prompt 连续两次 → 1 目录 + 追加）

### AC-9: 敏感信息不泄漏
- **Type**: `rule`
- **Given**: 任务工具参数含 API key / 密码（被 SecretRedactor 标记）
- **When**: 生成 SKILL
- **Then**: 生成的 SKILL.md 不含原始密钥值
- **Pass Condition**: grep 生成产物，无敏感明文
- **Evidence**: 单元测试注入敏感参数，断言产物不含明文

### AC-10: 可观测事件
- **Type**: `rule`
- **Given**: `enabled=true`
- **When**: 任务成功后总结执行（生成/追加/跳过/失败）
- **Then**: 事件流中出现 `skill.summarize` 事件，detail 含 action 与 reason
- **Pass Condition**: 从 AgentTaskEventStore 回放该 taskId 事件，能找到 skill.summarize
- **Evidence**: 集成测试回放事件流断言

### AC-11: 生成质量
- **Type**: `rubric`
- **Dimension**: 自动生成 SKILL 的泛化与准确程度
- **Scale**: 1-5
- **Anchors**: 1 = 把一次性硬编码值写进 How It Works、allowed-tools 含不存在的工具；3 = 步骤可读但泛化一般、部分细节未抽象；5 = 步骤清晰泛化、无硬编码、allowed-tools 全部真实、Examples 保留具体案例
- **Pass Threshold**: >= 4
- **Evidence**: 对 3 个真实录制任务生成产物做人工/规则审查（allowed-tools 交叉校验、硬编码正则扫描）

### AC-12: 可观测性完整性
- **Type**: `rubric`
- **Dimension**: 总结过程的可审计与可调试程度
- **Scale**: 1-5
- **Anchors**: 1 = 无事件无日志；3 = 有日志但无结构化事件；5 = skill.summarize 事件覆盖生成/追加/跳过/失败全部分支，detail 含原因与耗时，REST 可列草稿
- **Pass Threshold**: >= 4
- **Evidence**: 代码审查 + 集成测试回放事件

## Open Questions
- [ ] 去重的语义指纹用 embedding 还是关键词 hash？（P1 用关键词 hash 降级，embedding 留 P4）
- [ ] 草稿区 promote 后是否自动从草稿区删除？（倾向保留为已提升标记）
- [ ] 是否需要把生成的 SKILL 同步回仓库 `skills/` 供团队共享？（倾向不自动，由人工 PR）
