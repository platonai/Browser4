# `agent run "重构 Browser4WebDriver"` 监督记录（2026-10-22）

> 角色：监督方。任务：跑 `./b4w agent run "重构 Browser4WebDriver"`，监督执行，修复/优化执行链路代码。
> 环境：本仓库 `D:/workspace/Browser4/Browser4-4.14-feat`（分支 4.14.x，HEAD 352e05ef82），
> backend runtime bundle 构建于 16:23（源码改动后未重建），CLI 4.14.3-SNAPSHOT。

## 1. 结论

- 第一轮真实运行 **没有产出任何文件修改**：175 次工具调用、12.2 分钟、3 次内层工具循环溢出（40 次/轮），
  工作区文件时间戳仍停留在上一次（被取消的）运行的 18:29。运行被监督方取消（`status=cancelled`，417）。
- 根因不是模型能力，而是**执行链路的缺陷**（§2 D1-D6、§4.2 D7、§5 D8），其中：
  2 个会让"编码任务"被当成"浏览任务"（D1/D2）、1 个让 `coding.verify` 的 test 检查**假红**（D3）、
  1 个让模型唯一的跨轮记忆被静默截断（D5）、1 个让同一后端进程内**第二个起的工作区为空**（D8）。
- 已修复 D1-D5、D8（含 6 个新/改单测，`BUILD SUCCESS`，37 例全绿），D6/D7 作为遗留项记录（D7 需环境变量绕过）。
- 运行时复跑确认修复生效：`profile=browsing → profile=coding`、首轮不再调用 `b4_run`/`system.listTools`、
  system prompt 切到编码角色。

## 2. 观察到的缺陷（含证据）

### D1 `CodingTaskDetector` 把"重构 X"判成浏览任务 → 用错工具剖面与提示词

证据（`pulsar.log`）：

```
20:48:22.214 RobustBrowserAgent - 🚀 cli-agent.start sid=f7d5c101 turns=100 instr='重构 Browser4WebDriver'
20:48:22.217 RobustBrowserAgent - cli-agent tools exposed: 23 initial (of 67 CLI-domain, ..., profile=browsing)
```

后果（第一轮前 10 次调用）：`memory_search, coding_listDir, memory_read, coding_stat, coding_read, b4_run,
coding_grep, system_listTools, system_exposeTools, coding_changeSummary, coding_workspaceRoot`。
其中 `b4_run` 是浏览器 CLI，`system_listTools/system_exposeTools` 是在"发现" `coding.verify`、`coding.readLines`
这些提示词已经要求它用的工具。提示词同时以 "You are a browser automation agent. Drive the browser EXCLUSIVELY
through browser4-cli" 开头，并附带约 5 KB 浏览器 CLI 速查表。

根因：`CodingTaskDetector.CODING_SIGNALS` 只识别扩展名/路径/`mvn`/中文"代码、文件、修改"等，**不识别"重构"**；
`重构 Browser4WebDriver` 里没有任何一个信号，于是落到浏览分支。

### D2 编码剖面的初始工具集缺少契约要求的工具

`CLI_CORE_CODING_METHODS` 不含 `verify`、`mvnBuild`、`readLines`、`diagnostics`，而系统提示词明确要求
"Before taskComplete ... run coding.verify(module=...)"。真实运行中模型必须自己 `system.exposeTools([...])`
才拿到 `coding_verify`（日志 `Enabled: coding_changeSummary, coding_verify, ...`）。

### D3 `coding.verify(checks=[test])` 假红：surefire 报告按未解析的模块名去找

证据（`pulsar.log` + 工具结果）：

```
20:55:10.930 VerificationRunner - coding.verify: module=browser4-browser testModule=browser4-browser checks=[compile, test]
20:55:34.535 MavenBuildSupport - Maven: ...\workspace\mvnw.cmd test -pl browser4-core/browser4-browser -am -Dtest=... 
→ coding.verify [ok] Verification: FAILED (2/2 ran, 1 failed) in 80.4s
  2. ✗ test browser4-browser [-Dtest=...] — the -Dtest=... filter matched no test in browser4-browser
     (surefire wrote no report) — run the test in the module that owns it
```

但同一时间 surefire **确实写了 6 个新报告**：

```
2026/10/22 20:57:58  TEST-ai.platon.pulsar.chrome.Browser4ExtensionsTest.xml
2026/10/22 20:58:03  TEST-ai.platon.pulsar.chrome.Browser4WebDriverTest.xml  (等 6 个)
路径：<workspace>/browser4-core/browser4-browser/target/surefire-reports/
```

根因：`VerificationRunner.testCheck` 用调用方传入的裸模块名 `browser4-browser` 调
`countFreshSurefireReports(workspaceRoot, module, since)`，而报告在嵌套路径
`browser4-core/browser4-browser` 下（`maven.test` 内部会解析，计数不会）。于是"跑了 6 个测试类"被判成"一个都没跑"。
模型随后用 `coding_shell` 手动 `mvn ... -Dtest=ConsoleMessageBufferTest` 复现，白烧数轮。

### D4 `memory.read()` 无参调用直接报错

```
20:51:59.709 WARN AbstractToolExecutor - Error executing expression: memory.read() - Missing parameter 'taskId' for read
```

`taskId` 在 spec 里是必填（`ToolSpec.Arg("taskId", "String", null, ...)`），而模型想读的就是"自己这条任务"。
`memory.note` 的 `taskId` 早已声明为可选并默认当前任务，二者不一致。

### D5 scratchpad note 被静默截断到 200 字符 —— 跨轮记忆失效

真实 note（请求 dump 第 6 条消息）：

```
## Task Scratchpad
- refactor-state: Target: ...Browser4WebDriver.kt (4200 lines, 206KB, ...). Workspa...
```

`TaskScratchpad.VALUE_MAX_CHARS = 200` 且返回 `Note saved: <key>`，模型既看不到截断，也无法修正。
该 note 是压缩/溢出后模型唯一的跨轮状态载体。

### D6 工作区复用 + 上一轮被取消的残留 → 考古陷阱（未改代码，属设计权衡）

工作区 `<app-data>/agent/2026/10/22/<agentUuid>/workspace` 按 **agent 实例**（等价于会话）复用，
与被取消的前一轮共享：`coding.changeSummary` 在有任务开始前就报 `Changes tracked: 177 operations on 146 files`
（含 `b4ref/*.py`、`cbuild.log`、`b4ref/backup/Browser4WebDriver.kt.orig`）。
本轮模型因此花了大量轮次读旧日志/备份来重建历史；此外镜像**没有 `.git`**（`git status` → `fatal: not a git repository`），
模型无法用它熟悉的 diff 手段确认自己的改动。已在编码提示词中显式写明"无 `.git`，用 coding.changeSummary/diff"，
并在结尾要求把临时文件放 `.b4scratch/`（D1/D2 的提示词修复中包含）。工作区按任务隔离属更大的改动，本轮未动。

## 3. 修复

| # | 文件 | 改动 |
|---|---|---|
| D1 | `browser4-agent-tools/.../CodingTaskDetector.kt` | 新增重构/工程动词（重构、重写、实现、修复、优化、迁移、拆分、refactor、rewrite、implement、migrate…）与源码符号识别（限定名 `a.b.C`；`Browser4WebDriver` 这类 ≥2 大写且以 Driver/Executor/Controller… 结尾的标识符）。判定优先级：URL/显式导航动词 > 编码信号 > 页内交互动词 > 源码符号。`重构登录流程` 判编码，`打开网页并点击登录按钮` 仍判浏览。 |
| D1/D6 | `browser4-agentic/.../agents/CliAgentPrompt.kt`（新增） | 编码任务的系统提示词与浏览任务分离：编码模式不再附带浏览器 CLI 速查表，改为写明工作区镜像**无 `.git`**（用 `coding.changeSummary`/`coding.diff` 复核）、`coding.shell` 在 Windows 是 `cmd.exe`（无 `head`/`sed`，用 `workingDir` 而非 `cd /d`）、临时文件写 `.b4scratch/`、复用工作区可能残留上一轮产物（先看 changeSummary，别翻旧日志）、`coding.verify` 通过后才能 `system.taskComplete`。 |
| D2 | `browser4-agentic/.../agents/RobustBrowserAgent.kt` | `CLI_CORE_CODING_METHODS` 增加 `readLines`、`verify`、`mvnBuild`、`diagnostics`（提示词与门禁都直接点名这些工具）。 |
| D4 | `browser4-agentic/.../memory/MemoryToolExecutor.kt` | `memory.read.taskId` 改为可选；缺省读 `AgentMemory.currentTaskId`，没有当前任务时才报错。 |
| D3 | `browser4-coding/.../VerificationReport.kt` | 新增 `resolveTestReportModule(workspaceRoot, module)`：计数前先把裸模块名解析成工作区相对路径（`browser4-browser` → `browser4-core/browser4-browser`），解析失败保留原名。`testCheck` 的 Maven 调用、报告扫描、target/detail 统一使用解析后的选择器。 |
| D5 | `browser4-agentic/.../memory/TaskScratchpad.kt` | 单条 note 上限 200 → 800 字符（整块仍受 `scratchpadMaxChars`=1500 约束，LRU 逐出），并在发生截断时返回 `Note saved: <key> (truncated from N to 800 chars …)`。 |

新增/更新单测：
- `CodingTaskDetectorTest`：重构动词、限定名、散文不误判、页面动词优先级。
- `CliAgentPromptTest`（新）：编码/浏览两种角色的提示词契约（含 `.git`、`cmd.exe`、`.b4scratch/`、`VERDICT_JSON`）。
- `CliEngineToolSetTest`（新）：编码初始集必须含 `coding_verify`/`coding_mvnBuild`/`coding_readLines`/`coding_diagnostics`；浏览集不得含；长尾仍可 `exposeTools`。
- `MemoryToolExecutorTest`：`read()` 无参读当前任务；spec 允许空参。
- `SurefireReportScanTest`：裸模块名解析后能命中嵌套路径的报告；不可解析的模块名保持原样。
- `TaskScratchpadTest`：超长 note 截断并显式提示；普通 note 无提示。

## 4. 验证

命令：

```
.\mvnw.cmd -B -pl browser4-agent-tools,browser4-coding,browser4-agentic -am \
  -D"test=CodingTaskDetectorTest,CliAgentPromptTest,CliEngineToolSetTest,MemoryToolExecutorTest,TaskScratchpadTest,SurefireReportScanTest" \
  -D"failIfNoTests=false" -D"surefire.failIfNoSpecifiedTests=false" test
```

结果 `BUILD SUCCESS`（37 个用例，0 失败）：

| 模块 | 测试 | 结果 |
|---|---|---|
| browser4-coding | `SurefireReportScanTest` | 6/6（含新增"裸模块名解析到嵌套路径"） |
| browser4-agentic | `CliAgentPromptTest` | 5/5（新增） |
| browser4-agentic | `CliEngineToolSetTest`（`CLI engine initial tool set`） | 3/3（新增） |
| browser4-agentic | `MemoryToolExecutorTest` | 11/11（含新增"read 默认当前任务"） |
| browser4-agentic | `TaskScratchpadTest` | 7/7（含新增截断提示） |
| browser4-agent-tools | `CodingTaskDetectorTest` | 9/9（含新增重构/符号/散文/优先级用例） |

### 4.1 运行时复跑（重建 bundle 后）

`./b4w.ps1 stop` → `BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 ./b4w.ps1 agent run "重构 Browser4WebDriver"`。

修复前后同一任务的启动行：

```
# 修复前（bundle 16:23）
cli-agent.start sid=f7d5c101 turns=100 instr='重构 Browser4WebDriver'
cli-agent tools exposed: 23 initial (of 67 CLI-domain, disclosure=true, mode='core', profile=browsing)
首轮调用: memory_search, coding_listDir, memory_read, coding_stat, coding_read, b4_run, coding_grep,
          system_listTools, system_exposeTools, coding_changeSummary, coding_workspaceRoot …

# 修复后（bundle 21:12）
StatefulAgentRunner - Agent task …: coding mode — skipping driver health check and page navigation
cli-agent.start sid=2ea935fe turns=100 instr='重构 Browser4WebDriver'
cli-agent tools exposed: 37 initial (of 67 CLI-domain, disclosure=true, mode='core', profile=coding)
首轮调用: coding_changeSummary, memory_read, coding_readLines …（无 b4_run / system.listTools）
```

请求 dump 的第一条 system 消息已变成编码角色（且不再包含 `### CLI Quick Reference`）：

```
You are a coding agent working on a Browser4 checkout. This run is a repository
task (read, refactor, fix, build, test) — not a browsing task.
Working tree: …\app-data\agent\2026\10\22\3c93848e-…\workspace
It mirrors the repository for this run. The mirror has NO `.git`:
… coding.shell runs through cmd.exe (… POSIX tools such as `head` … NOT available)
… Scratch files: … under `.b4scratch/` …
```

### 4.2 复跑暴露的环境/既有缺陷：后端不读 conf-enabled 的 LLM key

重建后第一次复跑直接失败：

```
agent.fail sid=89c586fd steps=0 err=No LLM configured. AI features turned off.
```

- 同时 `browser4-cli doctor` 报告 `✓ LLM is configured`、`Config file: <app-data>/config/conf-enabled/application-private.properties`、
  `Active model: deepseek-v4-flash`；该文件里确有 `deepseek.api.key=…`（`~/.browser4/config/conf-enabled/` 副本也有）。
- 用**普通浏览任务**（`agent run "reply with the single word ok"`）复现同样 417 —— 与本轮编码模式改动无关，是后端级问题。
- 决定性实验：把该 key 以 `DEEPSEEK_API_KEY` 放进启动 CLI 的环境后再起后端 → 同一任务 `status=done`（4.5 s），
  日志出现 `ChatModelFactory - Using LLM provider | provider=deepseek … apiKey=DEEPSEEK_API_KEY`。

结论：**引擎侧的 LLM 解析只认环境变量/`-D` 系统属性，不认 conf-enabled 配置文件**（`LlmConfigNormalizer` 只把文件里的
env 风格键改写成点号键，**点号形式的键从未被注入 JVM 属性**）。文档、doctor 与实际行为不一致，属既有缺陷。

### 修复（D7）：点号形式的 LLM 键也注入 system properties

`LlmConfigNormalizer.normalizeFile` 现在把文件里**所有** provider 键（`llm.*` / `deepseek.*` / `openrouter.*` /
`volcengine.*` / `openai.*`，无论 env 风格还是已点号形式）镜像到 `System.setProperty`（已存在则不覆盖），
因为引擎的解析顺序是 volatile → Spring Environment → **System.getProperty** → 文件。原先只有 env 风格键被镜像，
所以一份完全按文档写的 `deepseek.api.key=…` 文件在后端重建后失效。单测：
`LlmConfigNormalizerTest.testMirrorsDottedKeysIntoSystemProperties` / `testNonProviderDottedKeysAreNotMirrored`，
并把"点号键优先"的用例改为断言镜像值是文件里的点号值（9 例全绿）。

**端到端验证**（不带任何环境变量重建后端）：

```
env key: ABSENT
23:56:17 LlmConfigNormalizer - Mirrored LLM config deepseek.api.key from application-private.properties into system properties
23:56:17 LlmConfigNormalizer - Mirrored LLM config deepseek.model.name …
23:56:17 LlmConfigNormalizer - Mirrored LLM config deepseek.base.url …
23:56:27 ChatModelFactory - Using LLM provider | provider=deepseek model=deepseek-v4-flash …
agent run "reply with the single word ok" → status=done (3.1s)
```

即：配置文件的点号键现在能真正绑定，`export DEEPSEEK_API_KEY` 不再是必需步骤。

## 5. 同一后端进程内第二个及以后的 agent 工作区为空（已修复，待复跑验证）

在 LLM 可用的后端上复跑同一任务（21:18:23）：coding 模式生效、提示词正确，但工作区
`<app-data>/agent/2026/10/22/<uuid>/workspace` **是空目录**，且没有 `WorkspaceMirror` 日志；模型于是满盘找仓库：

```
coding_shell: cd /d D:\workspace\Browser4\Browser4-4.14-feat && findstr /s /m …
coding_shell: dir /b /ad "C:\Users\pereg\.browser4\workspaces\Browser4-4.14-…
```

同一后端三次任务的工作区条目数：

| agent 目录（创建时刻） | workspace 条目 |
|---|---|
| `1cd75d5d…`（21:12:58，服务启动后第一个 agent） | 18（镜像成功：`13 linked, 5 copied, 1 failed`） |
| `b1a1ff19…`（21:17:27，浏览任务） | 0 |
| `3c93848e…`（21:17:36，编码任务） | 0 |

根因：`AgentToolManager.provisionCodingWorkspaceMirror()` 在工具管理器**构造时**执行，门控是 `agent.codingMode`；
工具管理器懒加载，可能在 `StatefulAgentRunner` 设置 `codingMode` **之前**就被创建（memory/tool 初始化会先碰到它），
镜像因此被跳过。没有镜像的 coding run 找不到 checkout（还会 `cd /d` 到真实仓库，隔离性也被削弱）。

修复：`AgentToolManager.provisionCodingWorkspaceMirror()` 提升为 `internal`（幂等），并在
`RobustBrowserAgent.doRunCliAgentLoop()` 中 `codingMode` 已确定的位置再调用一次；工作区已镜像时
`WorkspaceMirror.ensure` 直接 skip。已通过 `mvn -pl browser4-agentic -am -DskipTests compile`。

### 5.1 复跑验证（第二次重建 bundle 后，21:38 起的后端）

同一后端进程内先跑一个浏览任务（`reply with the single word ok`，done 4.6s），再跑编码任务：

```
21:39:47.754 StatefulAgentRunner - Agent task 116138d0…: coding mode — skipping driver health check …
21:39:48.173 WorkspaceMirror - workspace mirror …\agent\2026\10\22\9081f980-…\workspace:
             linked=[browser4-dependencies,browser4-core,…,examples,.mvn] copied=[pom.xml,mvnw,mvnw.cmd,VERSION,ROOT.md]
21:39:48.177 AgentToolManager - + Coding workspace mirror: mirrored Browser4-4.14-feat: 13 linked, 5 copied, 1 failed
21:39:48.206 RobustBrowserAgent - cli-agent tools exposed: 37 initial (… profile=coding)
```

工作区条目从 0 → 20；模型不再满盘找仓库，而是直接在镜像里工作，并按新提示词把临时脚本写进 `.b4scratch/`
（前一轮同类任务把 100+ 个 `.py` 直接丢在仓库根）：

```
.b4scratch/{show.py, outline.py, outline.txt, members.py, members.txt, refs.py, map.py, map3.py …}
coding_devTask[{"task":"重构 Browser4WebDriver","module":"browser4-core/browser4-browser"}]
```

镜像仍有 `1 failed`：`mklink failed for pulsar-tests-common: 系统找不到指定的路径`（嵌套模块
`browser4-tests/pulsar-tests-common` 在其父目录成为 junction 之前就尝试链接）。不影响可用性（经
`browser4-tests` junction 可达），但每次运行都会报一次，建议：链接前先 `Files.createDirectories(parent)`，
或跳过父模块已链接的嵌套模块。

## 6. 第三轮：收敛仍然失败（记录，未再改代码）

在 D1-D5/D8 修复之上，又针对"分析不收敛"做了两处改动：

1. 编码任务内层工具循环下限 40 → 60（`cliToolLoopMaxIterations(configured, codingMode)`，历史浏览任务仍 40）；
2. 编码提示词增加"计划检查点"：每 ~10 次工具调用用 `memory.note(key="plan", …)` 记录 done/next，
   并"拿到成员图后立刻落第一笔修改"；`memory.note` 的 examples 增加 plan 模板。

复跑（21:55 提交，`eea0557d`）：

```
21:55:33 AgentToolManager - + Coding workspace mirror: mirrored …: 13 linked, 5 copied, 1 failed
21:55:33 AgentToolManager - + Coding workspace mirror: workspace already mirrors … (23 entries)   ← 幂等调用生效
21:55:33 RobustBrowserAgent - cli-agent tools exposed: 37 initial (… profile=coding)
21:58:50 WARN AgentToolCallLoop - Tool loop exceeded max iterations (60)                          ← 新下限生效
```

但 4 分钟 / 100 次工具调用仍然 **0 源码修改**，且 **0 次 `memory.note`**（提示词要求未被采纳）：

```
coding_readLines[browser4-core/…/Browser4WebDriver.kt]
coding_write[.b4scratch/outline2.py] → coding_shell[python .b4scratch\outline2.py]
coding_read[.b4scratch/companion.py | .b4scratch/testrefs.py | .b4scratch/mainrefs.py]
```

判断：**剩余瓶颈是模型行为（deepseek-v4-flash 反复测绘而不落笔），不是执行链路**。要让这类任务稳定收敛，需要
链路层的"进度强制"而不是再接一条提示词：

- 只读轮次计数：连续 N 轮（例如 2 轮）没有任何 `coding.write/replace/editLines` 时，注入一条
  `system` 级别的指令（"你已经读了足够多的代码，现在必须落第一笔修改，或显式说明阻塞原因"），并把该轮计入
  `noop` 语义；
- 或者在 `coding.devTask` 计划里把"第一个可编译的改动"作为必备步骤，由 runner 检查计划与执行是否一致。

## 7. 遗留 / 建议

1. **工作区按任务隔离**：当前按 agent 实例（等价会话）复用，被取消任务的半成品与 146 个临时文件会污染下一轮
   （`changeSummary` 一开始就是 177 处改动）。建议：新任务启动时把上一轮工作区归档，或注入"上一轮做了什么"摘要，
   而不是让模型靠读旧日志考古。
2. **内层工具循环 40 次上限**：本轮 3 次连续溢出，每次外层重开只能看到有损 digest，模型重新探索。
   对重构类任务建议按 `codingMode` 提高上限（或让溢出轮次把已读文件摘要写进 scratchpad）。
3. **镜像无 `.git`**：要么把 `.git` 以只读方式提供（worktree/clone），要么让 `coding.diff` 更显眼；
   目前的提示词补救只是把事实告诉模型。
4. **shell 可离开工作区**：`coding_shell` 允许 `cd /d <任意路径>`，实测模型能列出 `D:\workspace\Browser4\…`
   与 `~/.browser4/workspaces`（隔离性风险）。建议限制 cwd 到工作区，越界时要求 `allowExternalAccess`。
5. **`1 failed` 的镜像条目**：`WorkspaceMirror` 摘要里出现过一项失败，未展开到具体路径，建议把 `errors` 打全（当前
   `report.summary()` 只给数量）。

## 8. 最终结果：任务做出来了，但完成门禁自己把它判失败

第五轮（22:33 提交 `ccbced57`，bundle 含前面全部修复）终于收敛：

```
22:38:10 WARN AgentToolCallLoop - Tool loop exceeded max iterations (60)     ← 编码下限 60
（此后持续落笔，无 nudge：mutation 一直在增长，说明"只读轮次"计数按预期复位）
```

产出（直接写进工作区镜像的 junction → 真实仓库）：

```
 M browser4-core/.../chrome/Browser4WebDriver.kt      206,776 → 162,652 B（-1000 行级）
?? browser4-core/.../chrome/Browser4WebDriverScripts.kt    26,827 B（脚本层）
?? browser4-core/.../chrome/Browser4WebDriverParsers.kt    17,671 B（解析层）
?? browser4-core/.../chrome/Browser4WebDriverStorage.kt       917 B（存储状态）
```

`coding.verify` 全绿（这也验证了 D3 修复的真实效果——test 检查按解析后的模块路径找到了 surefire 报告）：

```
coding.verify: module=browser4-browser checks=[compile, test, repo-consistency, trapCheck]
Verification: PASSED (3/4 ran, 1 skipped) in 53.9s
  1. ✓ compile browser4-browser — exit 0 (16.0s)
  2. ✓ test browser4-core/browser4-browser — exit 0 (37.4s)
  3. ✓ repo-consistency repo — no errors
VERDICT_JSON: {"passed":true, ...}
```

但任务最终 **failed (417)**：

```
Finish report rejected: the run modified 23 file(s) the report does not list:
  .b4scratch/outline.py, .b4scratch/range_outline.py, .b4scratch/external_usage.py, …
```

根因：我的编码提示词让模型把临时脚本写进 `.b4scratch/`，而 `CodingAgentFileSystem` 的 mutation log **把所有写入都记成"仓库改动"**，
完成门禁拿它和 `filesChanged` 比对 → 临时脚本成了"未申报的改动"。提示词建议与门禁互相矛盾。

### 修复（D9）：临时/状态/构建路径不计入 mutation log

`CodingAgentFileSystem.recordMutation` 现在跳过 `isNonDeliverableMutationPath(...)`：`.b4scratch/`、`.browser4/`、
`target/`（含嵌套 `.../target/...`）。它们既不进完成门禁的 evidence，也不计入"本轮是否有进展"（第 6 节的只读轮次计数）。
单测：`CodingAgentFileSystemWritePathTest.nonDeliverablePathsAreNotTracked` + `nonDeliverableClassifier`
（23 个用例全绿）。

### 独立复核 agent 的重构（在真实仓库里重新跑）

```
.\mvnw.cmd -B -pl browser4-core/browser4-browser -am \
  -D"test=Browser4WebDriverTest,Browser4WebDriverConsoleCaptureTest,Browser4WebDriverFocusEmulationTest,Browser4ExtensionsTest,Browser4UserAgentTest,ConsoleMessageBufferTest" test
→ BUILD SUCCESS，Tests run: 115, Failures: 0, Errors: 0
  （Browser4WebDriver helpers 87、console capture 5、focus emulation 3、extensions 3、user agent 10、console buffer 7）
```

即：**重构本身是可用产物**，只是被完成门禁误判；D9 修复后，同类运行不会再因临时脚本被判失败（下次运行生效，
未再做一次完整复跑）。

## 9. 最终状态

- 执行链路修复（D1-D5、D7、D8、D9）+ 收敛优化（编码回合下限 60、只读轮次 nudge、计划检查点提示词）：
  15 个文件改动 + 5 个新测试类，相关模块 `BUILD SUCCESS`、定向用例全绿。
- 任务产物（agent 的重构）：`Browser4WebDriver.kt` 拆出 `Browser4WebDriverScripts/Parsers/Storage.kt`，
  独立编译 + 115 个单测通过；尚未提交。
- LLM 配置（D7）已修复并端到端验证：不带环境变量重建后端，任务 `done`（§4.2）。
- 仍未做运行时验证的：D9（临时文件不计入交付改动）——单测覆盖，未再跑完整重构任务复验。
- 剩余设计层面建议见 §7（按任务隔离工作区、shell 不得越出工作区、镜像 `1 failed` 的报错信息）。

## 10. 2026-10-27 复发：同一扇门，换到浏览任务的临时脚本上

D9 只把 `.b4scratch/`、`.browser4/`、`target/` 从 mutation log 里摘出去。**浏览型任务**（`codingMode=false`）的
提示词却让模型"用相对路径写 eval 用的 JS"（即工作区根目录），这类脚本不在上述豁免内，于是同一个误杀换了个形态复发：

```
✖ failed · 2bb2f4e0… — 🛑 Invalid state: step 0 - Finish report rejected:
   the run modified 17 file(s) the report does not list: ev.js, evf.js, evf2.js, evf3.js, read_events.js
```

证据（`app-data\logs\agent\…\cli-events.jsonl` + 工作区 mtime）：

| run | 实际写入 | 报告 `filesChanged` | 差集 |
|---|---|---|---|
| `90b850ac…`（问句任务，3m41s） | 6（`gh_issues.js` … `url.js`） | 0 | 6，告警前 5 名完全一致 |
| `2bb2f4e0…`（13m52s，issue #623 已提交并验证） | 37 | 20 | 17，告警前 5 名完全一致 |

根因链：模型手写 `filesChanged`（13 分钟 / 39 次 `coding.write`，内层循环 4 次撞 40 次迭代上限，只剩有界摘要）
→ 规则 7 把"报告漏列"当硬违规 → `strict` 直接抛 → `classifyError` 把它包装成
`PermanentError("🛑 Invalid state: step 0 - …")`（CLI 引擎只增 `turn`，`context.step` 恒为 0）
→ `resolveProblemWithRetry` 对 PermanentError 不重试 → 任务红；且**完成门禁没有修复回环**（只有 `coding.verify` 有）。

### 修复（D10）：漏列 = 记账问题，不是谎报

- `FinishReportGate`：规则 7 现在把漏列文件暴露为 `Verdict.unlistedFiles` / `onlyUnlistedFiles`；写后删除的文件不再算交付物
  （最后一次操作是 delete 即摘除）。
- 新增 `FinishReportRepair`（纯策略 + 单测）：漏列先回灌"缺哪些文件 + `coding.changeSummary` 是权威清单"，
  预算同 `browser4.agent.repair.maxRounds`；预算耗尽（或循环已结束）则**用 mutation trace 补全 `filesChanged` 并接受完成**，
  只有"幻影文件 / 编造 gate / 0 次工具调用 / 伪造 VERDICT_JSON"才继续判失败。
- `RobustBrowserAgent`：拆出 `evaluateFinishReport` / `acceptCompletion` / `reconcileFinishReport`，
  `taskComplete`、纯文本收尾、循环结束三条路径都走同一套协调；拒绝时把**全部**违规写日志（原来只剩 `.first()`）。
- 提示词：两种模式都指向 `coding.changeSummary` 作为 `filesChanged` 的权威来源；浏览模式改为把 helper JS 放 `.b4scratch/`
  （`eval --file .b4scratch/ev.js`），不再写工作区根目录。
- 回退开关：`-Dbrowser4.agent.finish.unlistedFiles=reject`（恢复旧的 fail-fast）；`finishGateCheck=warn` 仍然只告警。

验证：`FinishReportGateTest`（26 例）、`FinishReportRepairTest`（10 例）、`CliAgentPromptTest`（7 例）全绿。

