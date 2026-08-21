# code 工具与 b4 代理改进建议 v1.5（browser4-linkcheck 监督轮）

> 版本：1.5 · 日期：2026-08-21 · 作者：监督会话（browser4-linkcheck 插件任务，HEAD b7ed815d85，反馈闭环+任务隔离已落地）
> 结论先行：**插件最终 100% 完成**（scaffold → 实现 → 编译 → 7/7 测试 → 双重校验 → 打包 → 部署 → 重启加载 → 工具直连返回正确结果）。
> 但 HEAD 存在 **2 个 P0 代理回路缺陷**（每任务首步 LLM 调用必崩；工具循环溢出丢弃全部进度且被熔断误杀），
> 以及 **1 个 DevTaskPlanner 模块绑定回归**（任务文本含 DEPENDENTS 键时编译/测试绑错模块、--verify 假阳性）。
> 本轮 b4 成功完成任务的配方：**调参（toolLoop.maxIterations=40、textOnlyStallLimit=8、noop.limit=8）+ 小任务拆分**，与 v1.3/v1.4 的"小任务配方"结论一致。

---

## P0 — 代理执行回路缺陷（HEAD 上稳定复现）

### 0.1 每任务首步 LLM 调用必崩：空历史渲染成空白 user 消息

- **证据**：6 个全新会话任务（b656657b、acb53adb、32189a1f、f3dae5ad、f12d4ba2、1a1cb1c0）step 1 的 act 响应全部为
  `{"content":"Unknown exceptiontext cannot be null or blank","state":"OTHER","tokenUsage":{0,0,0}}`；
  对应 request.json 的消息列表在 system 提示与 Browser State 之间有一条无 content 的空 user 消息 `{"role":"user"}`。
- **根因链**：`DefaultHistoryRenderStrategy.render()` 对空 history 返回 `""`；
  `PromptBuilder.buildMultistepMessageListStart()` 无条件 `messages.addUser(buildAgentStateHistoryMessage(...))`；
  `SimpleMessage.toChatMessage()` 对 user 消息执行 `TextContent.from(content)`，LangChain4j 对 blank text 抛
  "text cannot be null or blank"；ContextToAction 捕获后包装成 `ModelResponse("Unknown exception"+brief)`。
- **影响**：每个任务第一步白白烧掉一次生成（还让 step 1 变成"成功无动作"），且把异常文本喂给后续解析。
- **修复建议**：① `AgentMessageList.addUser`/`toChatMessage` 过滤 blank content；② 空 history 渲染占位串
  （如 "No execution history yet."）；③ 加回归测试：全新会话首步生成不抛异常。

### 0.2 工具循环溢出丢弃全部进度，且被 text-only 熔断误杀

- **证据**：
  - 全量任务每步 `AgentToolCallLoop` 12 次跑满：`modelError="Tool call loop exceeded max iterations (12); executed: coding_workspaceRoot, coding_listDir, coding_glob, coding_read, coding_shell, coding_readLines, coding_classInfo"`；T4 step2 40 次跑满（单步输入 token 1,252,044）。
  - 循环内真实执行了 scaffoldToDir/replace/editLines（磁盘可见），但溢出后整步被外层记为 `observeActNoAction`、`isSuccess=true`、`actionDescription.toolCall=null` → 进度全部丢弃，下一步从 workspaceRoot/listDir 重新探索（每步重复同一批探索工具）。
  - text-only 熔断把"有内部工具执行但最终无 action"的步骤计为文本空转，`textOnly.stall step=5 consecutive=5 limit=5` 在第 5 步杀死任务；`--noop-limit 8` 只改 consecutiveNoOpLimit，不影响 textOnlyStallLimit。
- **影响**：全量任务（>12 工具调用的任务）在 HEAD 上必然空转至多 5 步后被误杀；每步烧 220K-1.25M 输入 token，效率灾难。
- **修复建议**（按性价比）：
  1. 循环溢出时把已执行工具及结果摘要**持久化进该步 actionDescription**（或标记 step 失败进入重试/中止语义），绝不静默丢弃；
  2. text-only 熔断按"本步是否执行过 ≥1 个工具"重置计数（无论最终 actionDescription 是否有 toolCall）；
  3. `--noop-limit` 同时提高 textOnlyStallLimit（或 CLI 暴露独立参数）；
  4. 长任务支持**跨步续跑工具循环**（把循环消息链作为上下文延续），而不是每步从初始消息重启；
  5. 循环内上下文严格预算（每步 ≤50K token、工具结果截断更狠、去重已读文件），并让 RequestTokenLimiter 在超限时真正裁剪而不是放任到 1.25M。

## P1 — 计划与工具链

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| 1.1 | DevTaskPlanner 模块绑定回归：任务文本含 DEPENDENTS 键时编译/测试绑错模块 | 计划第 4/5 步为 `mvnBuild(module="browser4-core/browser4-protocol")` 与 `mvn test -pl browser4-core/browser4-protocol -Dtest=LinkcheckConfigTest,...`；--verify 编译旧模块报 Build succeeded（假阳性，插件尚不存在）。根因：`inferModules` 收进 DEPENDENTS 键后，`(modules+newPluginModules).maxByOrNull{斜杠数}` 同深度取先 → 旧模块胜出 | 深度相同时 newPluginModules 优先；或 mvnBuild/test 目标直接取 newPluginModules 而非 modules |
| 1.2 | 计划 read/impact 路径错误 | `coding.read(path="src/main/resources/linkcheck/countLinks.js")` 与 impact 同路径，按工作区根解析必然失败；v1.3 裸文件名修复未覆盖"带斜杠但缺模块前缀"的路径 | 对 newPluginModules 内的文件路径补全模块前缀（`browser4-plugins/<name>/...`） |
| 1.3 | 每步 token 燃烧 | T1b/T2/T3 完成时总 token 500K-900K；T4 step2 单步 1.25M；全量任务 5 步约 1.2M | 见 0.2-5；另建议对 read/shell 结果做内容级去重与摘要 |
| 1.4 | code 工具工作区随后端 user.dir 漂移 | 8182 后端（兄弟树 bundle）让 code workspace 指向 Browser4-4.14；v1.3 已报同类问题，HEAD 未改 | CLI 每次调用把仓库根作为 `-Dbrowser4.agent.workspace` 传给后端（b4w.ps1 或 daemon 注入），后端不再依赖启动 cwd |

## P2 — 小问题

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| 2.1 | 聚合 pom 模块缩进逐级漂移 | browser4-plugins/pom.xml：wordcount 12 空格、linkcheck 16 空格 | scaffoldToDir 的对齐应取第一个 module 行（规范缩进 8 空格）而非"最后一个 module 行" |
| 2.2 | ModuleMap DEPENDENTS 行过长/尾随空格 | ModuleMap.kt protocol/skeleton/pdk 行 `"browser4-wordcount", `（尾随空格），单行超 120 列 | 增加规范化工具或校验（repo-consistency 可加格式检查） |
| 2.3 | 双后端时 8088 MCP-over-HTTP 端口冲突 | 8183 日志 WARN "MCP-over-HTTP will be unavailable (port already in use)" | 端口被占用时自动选空闲端口，或按 server.port 派生 |
| 2.4 | `-am` 构建触发 ModuleMapDriftE2ETest 失败 | T3 实测 `mvnBuild(goals=test, -am)` 触发上游 ModuleMapDriftE2ETest 失败（新模块 DEPENDENTS 未补齐时），改为无 -am 后通过 | 开发期将漂移测试降级为 warn，或 scaffoldToDir 自动补 DEPENDENTS（v1.4 1.3 仍未落地） |
| 2.5 | agent list 把所有历史任务显示为 queued | agent list 输出 25/25 queued（含已完成的 200/417 任务） | 状态序列化/恢复按终态优先；status/result 仍可信 |

## 回归基线（本轮验证通过）

- code 工具：workspace/list/read/write/replace/editLines/glob/validate/mvn/scaffoldToDir 全链路可用；scaffoldToDir 正确注册聚合 pom 并同步 ModuleMap.MODULES。
- b4 小任务：T1b/T2/T3 均 2-3 步完成，产物逐条符合任务规格，编译/测试门禁如实锚定。
- 端到端：mvn package 7/7 测试全过、validate plugin 全过、repo-consistency 全过、JAR 部署后 domain linkcheck 注册、example.com 上 countLinks 返回 LinkCountResult(total=1, external=1, internal=0)。
- 任务隔离生效：每个 agent run 使用独立 sessionId（627a4016、4d03aed0 等），未复现跨任务历史污染。

## 环境备忘（本轮结束后）

- 8183 后端由监督会话持有，带 `-Dbrowser4.agent.toolLoop.maxIterations=40 -Dbrowser4.agent.textOnlyStallLimit=8 -Dbrowser4.agent.noop.limit=8`；如需恢复默认重启即可（不带这些 -D）。
- 插件 JAR 部署于 `D:\workspace\Browser4\Browser4-4.14-feat\plugins\`；8182 后端（兄弟树）未受影响。
- CLI 已重建为 HEAD（08/21），b4w.ps1 的哈希缓存应已失效并会在下次调用时刷新。
