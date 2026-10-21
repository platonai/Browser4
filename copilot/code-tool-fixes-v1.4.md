# 修复记录 v1.4：browser4-hello 监督轮全部问题修复

> 日期：2026-08-20 · 依据：`browser4-code-tool-supervision-v1.3.md`（问题清单）与 `supervision-timeline-hello.md`（证据）。
> 范围：P0×2、P1×6、P2×8（其中 2.3/2.8 由 1.4/1.3 覆盖），全部落地并配回归测试。

## P0

### 0.1 事件挂载死代码 → 已修复
- `browser4-core/browser4-skeleton/.../event/PulsarEventBus.kt`：新增 `ensurePageEventHandlers()`——惰性创建默认 `PageEventHandlers`（默认链为空/中性），全局总线不再为 null。
- `browser4-boot/.../plugin/PluginManager.kt`：`wireAllMounts` 改用 `ensurePageEventHandlers()`，Browse/Load/CrawlEventMount 装配不再有 skip 分支（失败打 WARN）。
- 效果：插件事件处理器经 `InteractiveBrowserEmulator` 的全局链真正触发。

### 0.2 插件工具对 LLM 代理不可用 → 已修复
- 根因一（代理进程内路径）：`AgentToolManager.execute` 自定义域分支要求 `_customTargets[domain]`，插件域从未注册目标 → 抛出 "no target object"。修复：按 `executor.receiverClass` 解析接收器（`WebDriver::class` → 会话 driver）。
- 根因二（外部 MCP 路径）：`extractDomain`/`dispatchToCustomExecutor`/`dispatchToStandaloneCodingTool` 只认 `domain_method`，不认提示词惯例 `domain.method`。修复：三处均接受点号形式（`startsWith("${it}.")` 匹配 + 方法名剥离），提示词渲染格式保持不变（与内建工具一致）。

## P1

### 1.1 后端陈旧无检测 → b4w.ps1 新增后端源码 hash 检测
- 对 6 个核心模块（core/agentic/coding/rest/boot/agent-tools）的 `.kt` + 根 pom + VERSION 做 SHA256，与 `cli/browser4-cli/target/.backend-source-hash` 比对；不一致时警告并给出重建命令；`b4w -Rebuild` 自动设置 `BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1` 并在成功后回写 hash（时间戳检测不可用——可复现构建固定 mtime）。

### 1.2 stop 漏杀 → 端口兜底
- `managed_processes.rs`：`force_kill_all_browser4_server_processes` 除命令行模式匹配外，增加 netstat 监听扫描（`find_java_pids_listening_on_ports`，跨平台：Windows `netstat -ano -p tcp` / unix `netstat -anp`），杀掉监听受管端口（注册表端口 + 默认 8182）的 java 进程。新增 2 个单测。

### 1.3 DevTaskPlanner 计划缺陷 → 已修复（含 4 个新测试）
- `ModuleMap.mavenTestCommand`：带 `-Dtest` 时追加 `-Dsurefire.failIfNoSpecifiedTests=false`（`-am` 上游无匹配测试不再失败）。
- 裸文件名（如 `HelloService.kt`）不再生成根目录 `coding.read`，改为对新插件模块/所属模块的 `coding.listDir` 定位步骤。
- 移除 git commit 步骤（代理不得自动提交）。

### 1.4 代理任务运维 → 已修复
- **取消**：`StatefulAgentRunner` 新增 `taskScope` + `runningJobs` + `submit()/cancel(id)`；`UserCommandExecutor.submitAgentTask` 改走 runner.submit；新增 `cancelAgentTask`；`CommandController` 新增 `POST /api/commands/{id}/cancel`；CLI 新增 `agent cancel <id>`（commands.rs/main.rs 全接线）。
- **LLM 请求超时**：`ContextToAction` 两条原始生成路径包 `withTimeout`（`browser4.agent.chat.requestTimeoutMs`，默认 5 分钟，10s–60min 界）；超时作为可重试步骤错误进入 resolve 重试，不再无限挂起。`AgentConfig.resolveTimeoutMs` 默认 24 小时的问题由该 per-request 超时对冲。
- **队列卫生**：`restoreFromDisk` 跳过超 TTL 的非终态记录（zombie "queued" 不再跨重启复活）。

### 1.5 假完成两变种 → 已修复
- 变种 A：`RobustBrowserAgent.run()` 在 `onDidRun` 之后检查 `actResult.exception` 并抛出——MAX_STEPS/NOOP 中止现在沿异常路径由 `StatefulAgentRunner.executeSerialized` 标 failed（此前 ActResult 里的异常被丢弃，任务以 200/completed 收场）。
- 变种 B：`MainSystemPrompt` "When to Finish" 增加"完成摘要必须锚定实测门禁结果；未跑=not run、失败=failed，禁止无工具输出佐证的成功声明"。

### 1.6 repo-consistency 用内存快照 → 已修复
- 新增 `browser4-coding/.../coding/ModuleMapSource.kt`：解析磁盘 ModuleMap.kt（MODULES 列表 + DEPENDENTS 各键），纯字符串解析、格式不符返回 null。
- `CodingToolExecutor.repoConsistencyReport`：优先用磁盘解析结果；解析失败回退加载类并附加 ⚠ 说明。scaffold 后不重建后端也校验准确。

## P2

| # | 修复 |
|---|---|
| 2.1 | `ArtifactScaffolds.pluginConfig` 模板注释：MutableConfig IS-A ImmutableConfig、禁止双向改动、字符串用 `conf.get(key, default)`（无 getString） |
| 2.2 | 新工具 `coding.classInfo(class)` + CLI `code javap <class>`：反射输出父类链与公开方法签名，解决外部库 API 不可 grep 的盲区 |
| 2.3 | 由 1.4 的 per-request LLM 超时覆盖 |
| 2.4 | `probe_server_state` 两个探测请求加显式 5s 超时，就绪循环不再被慢探测拖过总预算 |
| 2.5 | daemon 构建脚本包装命令的 `[Console]::ErrorEncoding` 加 try/catch（PS5.1 无此属性） |
| 2.6 | `scaffoldToDir` 聚合 pom 注册行对齐最后一个 `<module>` 行的缩进 |
| 2.7 | `submit_plain_command_with_options` 异步提交（agent run）用 180s 超时覆盖（首建 companion agent 慢） |
| 2.8 | 由 1.3 的 commit 步骤移除覆盖 |

## 验证

- Rust：`cargo test --bin browser4-cli` **1107 passed, 0 failed**（含 managed_processes 新增单测、agent-cancel help 映射）。
- Kotlin：DevTaskPlannerTest 17、ModuleMapSourceTest 3、RepoConsistencyCheckTest 16、ToolCallSpecificationRendererTest 19、CodingToolExecutorTest（88 用例含 toolSpec 计数更新）、PluginManagerTest 6、MCPToolControllerTest 71、StatefulAgentRunnerTest 3 全绿。
- 端到端（重建 bundle 后实测）：
  - `validate repo-consistency` 用磁盘快照转绿（仅存既有 pageinfo 警告）✅
  - `code javap ImmutableConfig` 输出父类链与方法签名（含 `get(String, String)`）✅
  - `code devtask` 新计划：bare 文件名→listDir locate、测试命令含 `-Dsurefire.failIfNoSpecifiedTests=false`、无 commit 步骤 ✅
  - `devtask --verify`：hello 模块编译 exit 0 + repo-consistency 通过 ✅
  - 插件部署后日志出现 `+ Configured browse event handlers` ✅
  - 真实页面加载触发处理器：`hello: page loaded title=Example Domain url=https://example.org` ✅
  - 代理调用 `hello.pageInfo` 成功返回 JSON（此前 "no such tool exposed"）✅
  - `agent cancel <id>`：任务被取消并标记 **417 failed**（此前 MAX_STEPS 也标 completed）✅
  - b4w.ps1 后端陈旧警告触发与 hash 缓存写入 ✅；`b4w stop` 端口兜底杀掉监听进程 ✅
  - `-Rebuild` 联动强制重建 bundle（构建脚本 PS5.1 ErrorEncoding 守卫经实际重建验证）✅

## 遗留说明

- `AgentConfig.resolveTimeoutMs` 默认仍为 24h（全任务级上限），由 5 分钟 per-request LLM 超时对冲；后续可将默认下调。
- 代理任务仍为按会话串行（runMutex）；`agent cancel` 现可中断挂起任务，串行队列不再无解。
- P1.5A（MAX_STEPS 标 failed）为代码路径修复 + 既有测试回归，未做 100 步实弹验证（成本高、风险低）。

