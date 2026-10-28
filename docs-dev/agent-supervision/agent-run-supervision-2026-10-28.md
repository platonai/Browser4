# `agent run "抓取亚马逊微波炉 Top20"` 监督记录（2026-10-28）

> 角色：监督方（本会话为**崩溃恢复会话**）。
> 任务：执行 `./b4w agent run "到亚马逊上找到今天卖得最好的20个微波炉……保存为电子表格" --follow`，
> 全程监督并找出 `agent run` 执行链路上的问题（权限、SKILL 文档、工具集、工具披露、工具选择等）。
> 环境：`D:/workspace/Browser4/Browser4-4.15-feat`，分支 `4.15.x`，HEAD `36c248ca10`，
> dev 后端 8282（runtime bundle 于 23:53 现构建），模型 `deepseek-v4-flash`（deepseek 引擎）。
> 现场：`docs-dev/agent-supervision/run.log`（`--follow` 全量输出）。

## 0. 崩溃恢复说明（本次会话新增）

上一监督会话 `48c1b0f1-e622-4edc-a3fa-a5d95aba5d03` 在 **00:23:32** 意外退出，`agent run` 随之终止。
本次恢复做了四件事，**没有重跑 agent**（理由见 §6）：

1. 解压上一会话 transcript（`~/.dsh/sessions/--D-workspace-…-feat--/48c1b0f1…/session.v4.jsonl.zstd`），
   复原其 177 条助手消息、139 段推理笔记与 todo。它的 todo 列表停在"启动运行 in_progress"（没来得及更新），
   但从记录看：运行已启动、系统提示/披露的基线阅读与逐段监督都做了大半，报告尚未动笔。
2. 重新从磁盘汇总量化证据：`cli-tool-trace.jsonl`、`cli-usage.jsonl`、`cli-compactions.jsonl`、
   `cli-prompt/*.request.json`（381 份）、后端 `pulsar.log`、agent 工作区产物。
3. 对上一会话的每个代码级结论在源码里复核（行号见各条「证据」）。
4. 补齐它尚未记录的量化项：重复爬取次数、pwsh 解析错误数、失败执行的两种口径（`b4.run` 35 + `coding.shell` 22）、4 次 20 页重爬。

崩溃同时中断了运行本身：`run.log` 与 `pulsar.log` 均停在 00:23:33，8282 无监听，
`cli-managed-processes.json` 记录的 server 进程 294248 已不存在——**运行随监督进程树一起终止，没有任何断点/续跑**（见 D12）。

## 1. 运行事实与结论

| 指标 | 值 |
|---|---|
| 运行时长 | 23:54:02 → 00:23:33（**29m31s**） |
| 工具调用 | **422**（`b4_run` 128、`coding_shell` 119、`coding_write` 53、`coding_read` 29、`memory_read` 16、`b4_wait` 15、`coding_listDir` 14、`b4_help` 9、`system_skillDoc` 7、`memory_search` 6、`coding_replace` 5、`system_listTools` 4…） |
| LLM 请求 | **380**（`cli-usage.jsonl`） |
| Token | 输入 **7,873,892** + 输出 104,513（默认预算 5,000,000，**未触发中止**） |
| 内层循环溢出 | **6** 次（40 次/轮上限；00:11:03 / 00:13:27 / 00:15:25 / 00:19:17 / 00:20:40 / 00:22:25） |
| 权限拒绝 | **20** 次（`coding_shell` 13、`coding_stat` 4、`coding_listDir` 2、`coding_grep` 1） |
| 失败执行 | **57** 次（`b4.run` 侧 `[exit code: N≠0]` 35 条 + `coding.shell` 侧 `Exit Code≠0` 22 条；另有 6 条超时） |
| PowerShell 解析错误 | **18** 条（结果含 `ParserError`，且中文报错乱码） |
| 上下文压缩 | 405 registered / 17 folded / 2 pruned |
| 首次触达 Amazon | 第 **124** 次调用（00:04:01）——前 10 分钟、123 次调用都在找输入文件和试错 |
| 完整 20 页爬取 | **4 次**（同一批 20 个 URL 重复拉取，≈80 次页面加载） |
| 交付物 | **0 个 xlsx**；仅 `.b4scratch/out20.csv`、`out21.csv`、`out22.csv`（各 121 列 × 20 行） |
| 完成信号 | `system.taskComplete` **0 次** |

**结论：这次失败不是"模型不会用浏览器"，而是执行链路五类缺陷叠加。**

1. **输入不可达**（D1/D2）：任务给的字段说明文件在本分支不存在，且即使存在也在 agent 工作区之外；模型用 123 次调用、10 分钟去"找文件"，并用 `file://`/`coding.shell` 绕过沙箱才找回。
2. **反馈不可读**（D3/D8/D9）：权限拒绝被包成 `[ok]`、`b4.wait` 丢掉作业终态、`b4.run` 的 pwsh 报错是乱码；模型无法从结果里判断"被拒绝/被杀死/语法错了"，于是重复同样的调用。
3. **能力缺口**（D6/D11）：没有读写电子表格的工具，没有"多页采集→增量落盘"的 SKILL 指导，没有 locale/币种归一指导；20 页采集被迫用 `coding_write`+python 手搓（53+119 次调用）。
4. **上下文结构**（D5/D7）：browsing 初始只给 25 个工具，其中不含提示词点名要求的 `coding.changeSummary`；40 次/轮的内层循环 6 次溢出，每次只剩 6 条工具结果 + 2K 摘要，模型丢失进度后重爬。
5. **成本与可观测性**（D10/D13）：5M 预算在默认 CLI 引擎里根本没接，7.87M 照跑；`--follow` 不显示拒绝/溢出/预算，监督者只能靠后端日志。

## 2. 时间线（关键节点）

| 时刻 | 事件 |
|---|---|
| 23:53:59 | dev 后端就绪（14.4s，含 runtime bundle 现构建）；app-data `…feat-2ec92531` |
| 23:54:02 | 任务提交 `1d5ec7d3…`，agent `8bad79e4…`，记忆任务 `fb5ac917` |
| 23:54:40 | `#1 coding_glob("**/asin_field_description.xlsx")` 开始找字段文件 |
| 23:56:03 | **第 1 次内层循环溢出**（40 次调用，仍未触达 Amazon） |
| 00:03:25 | 从 git 历史（`d80c3a0172`）/ 兄弟检出取回 `asin_field_description.xlsx`（721,965 B），落在 `.b4scratch/` |
| 00:04:01 | `#124 b4_run("goto https://www.amazon.com/dp/B0DY95T5HB")` —— 首次真正上网 |
| 00:08:03 | `#165` 首次 `crawl --seed-file`（`@` 未加引号 → pwsh splatting 报错） |
| 00:09:18 | `out2.csv`（2 URL）；00:11:00 `out20.csv`（20 行 × 121 列，**中文标题 / HKD 价格**） |
| 00:14:09 | `out21.csv`（第三次爬取）；随后改用 `document.cookie` 设 `lc-main/i18n-prefs` + `?language=en_US` 修 locale |
| 00:18:59 | `out22.csv`（`?language=en_US` 重爬，英文 / USD；20 行 × 121 列） |
| 00:22:13 | X-SQL 语法错误：生成的 SQL 列别名 `AS ""TITLE""`（双引号重复） |
| 00:22:25 | **第 6 次溢出**；此后改写 75 字段 JS 抽取器 |
| 00:23:33 | 最后一条记忆事件 `page_viewed …/dp/B0DY95T5HB?language=en_US`；与监督会话同时终止 |

## 3. 缺陷清单

优先级：**P0** 会直接让真实任务失败或让安全承诺不成立；**P1** 显著浪费轮次/Token 或让模型系统性误判；**P2** 数据质量与可观测性。

### D1（P0）任务引用的字段说明文件在 `4.15.x` 分支不存在

- 现象：任务把 `browser4-tests\pulsar-tests-common\src\test\resources\asin_field_description.xlsx` 当作输入，实际该路径无文件。
- 证据：
  - `git cat-file -e HEAD:browser4-tests/pulsar-tests-common/src/test/resources/asin_field_description.xlsx` → `fatal: … does not exist in 'HEAD'`；
  - `git log --all --diff-filter=A -- "*asin_field_description.xlsx"` → 只有 `d80c3a0172 docs(examples): add Amazon microwave best-seller crawl walkthrough`（在 `main`，不在 `4.15.x`）；
  - 运行开始时唯一副本是 `target/test-classes/` 里的构建残留，随 23:53 的重建被 `clean` 删除。
- 影响：模型前 123 次调用（10 分钟）都在找它：`glob`→`listDir`→`memory_search`→`system_listTools`→`b4_run --help`→`file://` 翻盘→`dir /s /b` 全盘搜索；直到用 `coding_shell` + `git show d80c3a0172:…` 才拿到。
- 建议：把 fixture 从 `main` 移植到 `4.15.x`（并 `git add`，别只留在 `target/`）；或任务描述改用本分支可解析的路径。

### D2（P0）browsing agent 的工作区隔离使 repo 相对路径不可读，且提示词不告知

- 现象：`coding.read/stat/listDir/grep` 对工作区外路径一律 `PATH_DENIED`；任务给的却正是 repo 相对路径。
- 证据（`cli-tool-trace.jsonl`，4 次同路径重试）：

  ```
  coding.stat  [ok] Error: PERMISSION_DENIED | code=PATH_DENIED | tool=coding.fs
    detail=path 'D:\workspace\Browser4\browser4-tests\pulsar-tests-common\src\test\resources\asin_field_description.xlsx' is outside the workspace…
    hint=use a workspace-relative path; paths outside the workspace require -Dbrowser4.agent.allowExternalAccess=true
  coding.stat  [ok] … detail=path 'D:\workspace\Browser4' is outside the workspace…
  coding.stat  [ok] … detail=path '../../../../../../../browser4-tests/…' is outside the workspace…
  ```

  工作区根（`coding.workspaceRoot`）= `…app-data/agent/2026/10/27/8bad79e4-…/workspace`，与 repo 无交集。
  权限模型默认 `allowExternalAccess=false`（`AgentAccessPolicy`），但提示词的守卫条件写错了：
  `CliAgentPrompt.kt:174` 在 `preset == FULL && denyTools.isEmpty()` 时**直接返回空串**，而工作区边界来自
  `allowExternalAccess`——它在 FULL 下同样生效。于是该文件 `:164-172` 注释里点名的失败模式
  （“模型把同一个被拒调用换个写法重试，直到步数预算耗尽”）在本次运行里原样发生了（4 次同路径重试）。
- 影响：凡是"任务书里给了仓库内文件/样例"的真实任务都不可完成；模型只能靠 D4 的绕过通道自救。
- 建议（已采纳 (a)，见 §7 "D2 工作区隔离使 repo 路径不可读"）：(a) 任务启动时把任务描述里出现的仓库相对路径**自动复制进工作区**并在 user message 里给出新路径；(b) 给 browsing 任务默认只读的 checkout 访问权；(c) 至少在提示词里写明"工作区之外的路径会被拒绝，请改为……"（(c) 已随 `### Workspace boundary` 落地）。

### D3（P0）权限拒绝被包装成 `[ok]`，`--follow` 也不显示

- 现象：工具返回"拒绝"文本，但外层判 ok；`--follow` 只打印失败结果，所以人工监督完全看不到被拒。
- 证据：
  - `ToolOutcome.kt:27` `header = "$domain.$method [${if (ok) "ok" else "fail"}]"`，`:93` `ok = evaluate.success && exception == null`——拒绝是**正常返回值**，故 `[ok]`；
  - `ToolExecutionCoordinator.kt:53` 用 `ToolOutcome.render()` 作为模型可见结果；实测 20 条拒绝全部以 `[… ] [ok] Error: PERMISSION_DENIED …` 形式返回；
  - 4 次针对同一文件的路径拒绝（绝对路径 / `..` 穿越 / 父目录）说明模型把"拒绝"读成了"没找到/成功"；
  - `--follow` 只在 `ok == false` 或耗时超阈值时打印结果（`agent_interactive.rs:539-543`），
    而 `ok` 由结果文本是否以 `[fail]` 开头推导（`RobustBrowserAgent.kt:1359`）——拒绝文本以
    `coding.stat [ok] …` 开头，所以整轮 20 次拒绝在 `run.log` 里**一条都看不见**。
- 影响：模型与人都失去"被边界拦住"的信号。20 次拒绝里 7 次是对同一路径的重复尝试
  （`coding_stat` 4、`coding_listDir` 2、`coding_grep` 1），13 次是 `coding_shell` 的命令白名单拒绝。
- 建议：策略拒绝应 `ok=false` 并渲染为 `[fail]`/`[denied]`，`--follow` 打印拒绝行；提示词里补 `### Permissions`（即便 FULL，也要写明 `allowExternalAccess=false`）。

### D4（P0）沙箱可绕过：`coding.shell` 参数不限路径、`b4.run` + `file://` 可读任意目录

- 现象：文件工具的边界被两条旁路完整绕过，且这次绕过**救了任务**（取回字段文件）。
- 证据：
  - `coding.shell` 允许 `dir /s /b "D:\workspace\Browser4\…"`（两次跑到 120s / 180s 超时）、
    `git -C "D:\workspace\Browser4\Browser4-4.15-feat" ls-files …` → `Status: SUCCESS Exit Code: 0`；
    只有 `app-data` 之类"敏感位置"被 `COMMAND_PATH_DENIED` 拦住——即**只查敏感目录，不查工作区边界**；
  - `b4_run("goto \"file:///D:/workspace/Browser4/\"")` 成功，随后 `eval` 读目录正文；整轮共 27 处 `file:///` 调用；
  - 还尝试过 `upload <ref> "D:/…绝对路径"` 与 `curl http://localhost:8282/actuator/mappings`（后者被命令白名单拒）。
- 影响：`allowExternalAccess=false` 的安全承诺在 browsing 任务里不成立；同时说明"浏览器是唯一的真实数据通道"这一设计没被约束。
- 建议：`coding.shell` 的命令参数做路径审查（解析出路径 token，按同一套 `allowsPath` 判定），或明确在 FULL 下把 shell 标为"逃逸口"并从 browsing 初始集移除；`b4.run` 的 `file://`/`upload` 限制在允许的根（工作区 + 显式授权的 checkout）；若产品意图是"能读 checkout"，就把它做成受支持的只读能力，而不是靠绕过。

### D5（P1）工具披露与提示词不一致；日志计数与实际下发不一致

- 现象 1：browsing 初始工具集不含 `coding.changeSummary` / `coding.diff`，但提示词要求"结束时跑 `coding.changeSummary`"。
  - 证据：`RobustBrowserAgent.kt:111-115`（browsing 只给 read/write/append/replace/listDir/glob/grep/stat/mkdir/delete）、
    `CliAgentPrompt.kt:149`（"run coding.changeSummary — it lists exactly the files this run wrote"）。
    实测模型先 `system.exposeTools(["coding_changeSummary","coding_workspaceRoot"])` 才拿到。
- 现象 2：模型猜了不存在的工具名 `coding_exec`、`coding_copy`、`coding_run`（`system_exposeTools` 调用）——`system.listTools` 的结果没有把"猜测失败"变成有信息量的反馈。
- 现象 3（已核实，非缺陷）：首份请求快照只有 **25** 个工具；模型 `exposeTools` 后，后续快照变成 27 个（`+changeSummary +workspaceRoot`）、再变成 29 个（`+shell`）。这是 P2-3 "exposed 集合跨轮保留"在正常工作。
  后端日志那行 `27 initial` 打印的是**当轮**初始集（含此前暴露的工具），不要当成"首次披露"读——监督时容易误判（本次上一会话就误读过一次）。
- 建议：提示词点名的工具必须进初始集（或从提示词删掉）；`exposeTools` 未命中时返回"最接近的 3 个工具名"；修正披露计数（记录实际下发数）。

### D6（P1）没有"结构化落盘/表格"能力，模型被迫用 shell+python 手搓

- 现象：53 次 `coding_write`（几乎全是临时脚本）+ 119 次 `coding_shell`（python 数据整形），仍没产出 xlsx。
- 证据：`coding.write` 只写文本；`coding.runCode` 在私有临时目录执行（产物不进工作区、不进变更追踪）；browsing 初始集不含 `shell`/`runCode`；`crawl` 只支持 `csv/json/table` 等文本格式，无 xlsx。
  agent 自己的说明（步骤 #350+）：反复 `python -c "import pandas…"`、`openpyxl` 读输入、手工拼列。
- 影响：把"采集→整理→交付"的最后一公里变成了几十轮 shell 往返；也解释了为什么 29 分钟仍没有交付物。
- **决定（2026-10-28，已确认）：不引入 Excel 依赖、不内置 Excel 支持。** 落盘改走纯文本：`crawl --format csv -o …` 或
  `coding.write` 写 CSV/JSONL；SKILL 与 browsing 提示词已写明"交付 CSV/JSONL，`.xlsx` 输出不在范围内，不要手搓"。若任务**输入**是
  `.xlsx`，用脚本抽取（`coding.shell` + python/openpyxl）或事先转成 CSV——`coding.read` 对二进制直接拒绝。
  因此 D6 剩下的建议只有："采集→落盘"的标准路线写进 SKILL（不再要求表格工具）。

### D7（P1）40 次/轮的内层循环 + 有损摘要，对"20 项采集"这类任务结构性不够

- 现象：6 次溢出；每次溢出后只剩"最近 6 条工具结果、总 2,000 字符"的摘要，模型丢失已完成项与计划，随后重复爬取/重复读文件。
- 证据：`RobustBrowserAgent.kt:262-263`（browsing 下限 40）、`:241-244`（`ROUND_DIGEST_RESULTS=6`、`ROUND_DIGEST_MAX_CHARS=2_000`）、
  `pulsar.log` 6 条 `Tool loop exceeded max iterations (40)`；
  重复劳动：4 次完整 20 页 crawl（`fd205503`/`2b1bae85`/`b3bad5c0`/`06fc0a78`，各 `20 pages, 0 lost, status OK`）。
- 影响：约 80 次页面加载完成 20 个商品的工作；且每次溢出都要重新披露工具、重新建立计划。
- 建议：任务的"项数"应换算成轮次预算（例如每项 ≥3 次调用），或把进度做成显式状态（`todo`/`checkpoint` 工具）并在摘要里保留"任务 + 已完成清单"；摘要至少保留用户任务与文件清单。

### D8（P1）`b4.run` 的 10s yield + `b4.wait` 丢终态，导致模型重跑整轮爬取

- 现象：慢命令先拿 `[job: id]`，再 `b4.wait`，但 `wait` 只返回 stdout 文本，不返回 `state`/exit code；模型无法判断作业已失败，于是原样重发。
- 证据：
  - `B4CliToolExecutor.kt:147`（`await(jobId, jobYieldMs)`，`jobYieldMs=10_000`）、`:169-179`（`wait` → `result.toModelText()`，无 state）；
  - `CliModels.kt:69-73`：`[timed out after …ms]` 既是"命令被杀死"的标记，也被误读为"等待超时"；
  - `cli-tool-trace.jsonl`：一次 20 页 crawl 用 `timeoutSeconds: 40` 提交 → 49,035ms 被杀，输出停在 "13 pages…"；随后 `b4.status` 才显示 `state=FAILED`，接着整条命令重发（#303→#307）。
  - 计数：128 次 `b4_run` 伴随 15 次 `b4_wait`（作业升级），每次慢命令多 2 个往返。
- 建议：`b4.wait` 返回 `state=…` + exit code + 是否超时；区分"命令超时被杀"与"等待超时"；对 `crawl/query` 这类天然长命令直接把 yield 窗口提到 60s 或默认返回 job；`wait` 失败时给出"不要重跑，先看 status"的提示。

### D9（P1）`b4.run` 实际经 `pwsh -Command` 解释；`coding.shell` 又是 cmd.exe

- 现象：文档给的是 argv 形式命令行，实际执行是 PowerShell 字符串解析，`@file`、`<`、`&&`、`%d`、引号语义全变；报错经 GBK 编码后成为乱码，模型读不懂。
- 证据：
  - `CliProcessManager.kt:354-363`：`listOf("pwsh","-NoLogo","-NoProfile","-NonInteractive","-Command", preamble + "& " + commandLine)`；`preamble` 只对 Windows PowerShell 5.1 设置 UTF-8（pwsh 分支为空）；
  - 18 条 `ParserError` 结果，例如

    ```
    b4.run [ok] stderr: ParserError:
      stderr: ParserError: Line | 1 | �� li\browser4-cli\target\debug\browser4-cli.exe' eval --json (() => Arr ��
    ```

  - 反向不一致：`coding.shell` 走 cmd.exe（`git … | head` → `'head' 不是内部或外部命令`；`dir /b` 可用），模型在两套 shell 语法间来回试错。
- 建议：要么直接 `exec` 二进制（不经 shell，按 argv 传），要么在 `b4.run` 描述里明确"args 会被 pwsh -Command 解释，路径/`@` 必须加引号"，并在 SKILL 里给 PowerShell 示例；统一输出编码（乱码是独立 bug）。

### D10（P0，成本）Token 预算在默认 CLI 引擎里未生效

- 现象：默认预算 5,000,000，本次 **7,873,892** 输入 token（380 请求）仍继续跑，没有任何告警/中止。
- 证据：
  - `TokenBudget.kt:83-91`（`agent.token.budget.total` / `DEFAULT_MAX_TOTAL_TOKENS = 5_000_000`）；
  - 扣减只在 `ContextToAction.recordUsage`（`ContextToAction.kt:397-414`），而 CLI 引擎走 `BrowserChatModel.langChainChat` 的独立循环，不经过它（`RobustBrowserAgent` 里只有捕获 `TokenBudgetExceededException` 的代码，没有扣减）；
  - 配置侧没有覆盖（`application-private.properties` 只有 deepseek model/key）。
- 影响：单次运行的成本无上限；监督/CI 场景无法靠预算兜底。
- 建议：把预算扣减接到 CLI 工具循环（每轮 usage 后累加，80% 告警、100% 中止并在 `--follow` 显示），或复用 `RequestTokenLimiter`。

### D11（P1）SKILL/quickstart 缺关键场景指导（本次直接造成 UX 与数据返工）

- 缺"增量落盘"：20 项采集必须边采边写；提示词只说"helper scripts 放 `.b4scratch/`"，没有"每采完 N 项就 append 到数据文件"的契约（与 D7 叠加）。
- 缺 locale/币种归一：host 无 `Accept-Language`，首轮 `out20.csv` 全是**中文标题 + HKD**；模型自己写 `document.cookie`（`lc-main`/`i18n-prefs`）并加 `?language=en_US`，花了约 8 分钟、3 轮爬取才拿到 USD。SKILL 未提 `set headers`/`set geo`，`crawl` 也没有 `--locale/--headers`。
- 缺 PowerShell 引号说明：`--sql @file` 必须加引号；`b4.run` 的 args 是 pwsh 字符串（D9）。
- 缺"字段规格文件"读取方式（xlsx 是二进制，`coding.read` 读不了；`runCode` 产物在私有临时目录）。
- 建议：在 `skills/browser4-cli/SKILL.md` / `references/` 增加"批量采集与落盘""locale/币种""PowerShell 引号""xlsx 输入/输出"四节，并在 quickstart 里给一条完整示例（采集 20 项 → 写 xlsx → `taskComplete`）。

### D12（P1，运维）运行无法续跑：监督进程崩溃 = 29 分钟 / 8M token 归零

- 现象：崩溃后没有断点、没有 `agent run --resume`（CLI 无 `agent run resume` 子命令），后端是客户端子进程，随会话一起死。
- 证据：`run.log`/`pulsar.log` 同秒截止；8282 无监听；`cli-managed-processes.json` 的 294248 不存在；`commands.rs` 中 `resume` 仅属 daemon/crawl 命令。
- 建议：`agent run` 落盘进度（已完成项、脚本、数据文件）并支持 `--resume <taskId>`；或让 dev 后端以 daemon 方式脱离客户端存活（现有 `cli-managed-processes.json` 已具备管理雏形）。

### D13（P2）数据质量与自校验缺口：121 列 vs 75 字段，模型把工作簿读错

- 现象：交付 CSV 是 `x-asin.sql` 的 121 列，与 `asin_field_description.xlsx` 的 75 个字段（第 1 行）命名不同（如 `overviewbullets` vs `product_overview`）；模型用 python 比对时把工作簿的 300 行数据当字段清单，产出 `FIELDS(298)` / `MISSING_IN_CSV(298)` 的无意义结论。
- 证据：`.b4scratch/cmpfields.txt` 第 5-7 行；`.b4scratch/asin_field_description.xlsx` sheet=`Sheet2` dims=A1:BW300，第 1 行 75 个字段、第 2 行中文说明、其余为示例数据；`out22.csv` = 121 列 × 20 行。
- **决定（2026-10-28）：不内置 xlsx 读取。** 改为约定：目标字段表以 CSV 形式随任务下发（或先用脚本把 xlsx 转 CSV），
  `crawl --sql` 输出与字段表的差异用一段 `.b4scratch/` 脚本模板比对；SKILL 给模板，不再要求引擎能力。

### D14（P1，环境）dev 模式 bundle 会从共享 `~/.m2` 拷到"另一个 worktree 的 jar"且无提示

- 现象：同一版本（`4.15.2-SNAPSHOT`）的两个检出（本仓库与 `D:\workspace\Browser4\Browser4-4.15`）共用 `D:\Users\pereg\.m2\repository`。
  `b4w` 重建 runtime bundle 时先 `mvn install` 再 `dependency:copy-dependencies` 从本地仓库拷 jar；
  若另一个检出在中间 `install`，本工作区 bundle 里就会是**别人的 jar**，而启动日志仍说"Local runtime bundle built successfully"。
- 证据（2026-10-28 01:15 实测，本次验证时踩到）：
  - bundle 内 `browser4-agentic-4.15.2-SNAPSHOT.jar` = 2,578,463 B / 01:15:56；本工作区 `browser4-agentic/target/…jar` = 2,577,513 B / 01:14:49；
  - 兄弟检出 `Browser4-4.15/browser4-agentic/target/…jar` 与 m2 中的 jar **同为 2,578,463 B / 01:15:56**，且不含本次修复的字符串（`### Workspace boundary`、`isFailedToolResult`）；
  - 该 bundle 上跑的冒烟任务回到旧行为（25 个初始工具、无边界块）；把本工作区 jar 拷回 bundle 后立刻恢复（28 个工具、边界块、`[fail]`）。
- 影响：dev 模式"重建后就是当前源码"的前提不成立；监督、验收、e2e 都可能静默测到旧代码（本次已发生一次）。
- 建议：bundle 构建使用 worktree 私有 local repo（`-Dmaven.repo.local=…`）或把 git SHA/构建时间写进 bundle 并在启动时校验；`verify-runtime-bundle.ps1` 增加"bundle 内 jar == 本工作区 target jar"的比对，不一致直接报错。

### D15（P1）工具结果 body 被压成一行：`coding.read` 读文件丢失换行

- 现象：`coding.read` 读一个三行文件，模型看到的是 `coding.read [ok] ALPHA-1` + body `ALPHA-1 BETA-2 GAMMA-3` —— **换行被替换成空格**。模型因此怀疑内容被合并，改用 `python -c "open(...).read()"` 去还原原始字节。
- 证据（2026-10-28 smoke4 的 `cli-tool-trace.jsonl`）：

  ```
  coding_read -> 'coding.read [ok] ALPHA-1\n  ALPHA-1 BETA-2 GAMMA-3\n'
  ```

  代码：`ToolOutcome.from` 对 body 调用 `Strings.compactInline(raw, budget)`，而该函数把所有空白（含换行）折叠为单空格。summary 需要单行，body 是**内容**（文件文本、命令输出），折叠后行/列边界全丢。
- 影响：读 CSV/表格/日志时模型无法分辨行；这是"用 csv 当交付格式"决策下的硬伤，也解释了此前多次"读到了但理解错"的现象。
- 建议：body 只做长度截断、保留换行；只有 summary 走 inline 压缩。

## 4. 修复优先级建议

| 优先级 | 事项 | 关联 |
|---|---|---|
| P0 | 任务输入可达性：启动时把仓库相对路径映射进工作区（或只读 checkout 权限），user message 写明真实路径 | D1/D2 |
| P0 | 拒绝语义：策略拒绝 → `ok=false` + `[fail]`，`--follow` 可见；提示词补 Permissions 段 | D3 |
| P0 | 沙箱一致性：`coding.shell` 参数路径审查、`b4.run` 的 `file://`/`upload` 根限制（或显式产品化只读 checkout） | D4 |
| P0 | 成本兜底：CLI 引擎接入 5M 预算（80%/100%） | D10 |
| P1 | 落盘约定（不引入 Excel 依赖）：CSV/JSONL 即交付格式；SKILL 补"采集→落盘 + 字段比对"路线 | D6/D11 |
| P1 | 披露一致性：提示词点名的工具入初始集；`exposeTools` 未命中给近似建议 | D5 |
| P1 | 循环结构：按任务项数给轮次预算；摘要保留任务+进度；提供 checkpoint | D7 |
| P1 | job 反馈：`b4.wait` 带 state/exit code；区分两类超时；长命令默认更长 yield | D8 |
| P1 | 子 shell：`b4.run` 直接 exec 或明确 pwsh 语义；统一 UTF-8（修乱码） | D9 |
| P1 | 续跑：`agent run --resume` + 进度落盘 | D12 |
| P1 | dev bundle 隔离：worktree 私有 local repo / 校验 bundle 内 jar 来源 | D14 |
| P2 | 字段校验（CSV 口径，xlsx 输入先转 CSV）；`--follow` 增加 token/溢出/拒绝事件 | D13/D5 |

## 5. 证据索引

| 证据 | 位置 |
|---|---|
| `--follow` 全量日志（498 行） | `docs-dev/agent-supervision/run.log` |
| 模型可见工具结果（422 条） | `~/.browser4/workspaces/Browser4-4.15-feat-2ec92531/app-data/logs/agent/20261027.235359/8bad79e4-…/cli-tool-trace.jsonl` |
| Token/请求 | 同目录 `cli-usage.jsonl`（380 行） |
| 每轮请求快照（含 25 个工具规格） | 同目录 `cli-prompt/*.request.json`（381 份） |
| 压缩账本 | 同目录 `cli-compactions.jsonl` |
| 后端日志（披露/溢出/crawl/X-SQL 错误） | `browser4-apps/browser4-bundle/target/runtime-bundle/_work/…/logs/pulsar.log` |
| 运行产物 | `…/app-data/agent/2026/10/27/8bad79e4-…/workspace/.b4scratch/`（`out20/21/22.csv`、`cmpfields.txt`、`mw_extract.js`…） |
| 记忆事件尾（`page_viewed` @00:23:33） | `…/app-data/memory/events/8bad79e4-…/fb5ac917.jsonl` |

## 6. 本次恢复未做的事（留给决策）

- **没有重跑 agent**：29 分钟/7.87M token 的现场已足以支撑 D1-D13；重跑大概率复现同一路径（同样的 20 页 × 4 轮、同样的 40 次溢出），而不会带来新的失败模式。
- 若需要观测**终局**（是否最终产出 xlsx、`taskComplete`/finish gate 如何裁定、预算/轮次上限是否先触发），可重跑同一命令并保留 `--follow` 日志；预计 ≥30 分钟、≥8M token。建议先修 D3/D5/D8/D10 再重跑，否则观测到的仍是同一批缺陷。
- 上一会话的解压 transcript 与推理笔记是临时文件（`%TEMP%`），本报告已吸收其结论；如需原文可用同样方式解压 `~/.dsh/sessions/--D-workspace-Browser4-Browser4-4.15-feat--/48c1b0f1-…/session.v4.jsonl.zstd`。

## 7. 恢复后已修的缺陷（2026-10-28）

| 缺陷 | 修复 | 代码位置 |
|---|---|---|
| D3 拒绝被伪装成 `[ok]` | 结果首行是 `PERMISSION_DENIED \| code=…` 即判失败：模型看到 `[fail]`，`--follow` 也会打印；拒绝原因与 `hint=` 保留在 summary/body（只查首行，避免把正文里的字样误判） | `ToolOutcome.kt`（`isPermissionRefusal` / `buildRefusalSummary`） |
| D3（补充）`--follow` 仍看不到拒绝 | 事件 `ok` 的推导只认裸 `[fail]` 前缀，而 envelope 是 `domain.method [fail] …`。改为 `isFailedToolResult()`：两种形态都识别，正文里的 `[fail]` 不算 | `RobustBrowserAgent.kt`（`isFailedToolResult` + 两个回调） |
| D2 工作区边界不告知 | FULL 且 `allowExternalAccess=false`（默认）时追加 `### Workspace boundary` 短块；受限预设的 `### Permissions` 也明确写"工作区外路径被拒" | `CliAgentPrompt.kt`（`permissionsBlock`） |
| D5 提示词点名的工具不在初始集 | browsing 初始 coding 集补 `diff` / `changeSummary` / `workspaceRoot`（只读记账工具，不再需要一轮 `system.exposeTools`） | `RobustBrowserAgent.kt`（`CLI_BROWSING_CODING_METHODS`） |
| D8 `b4.wait` 丢终态 | `wait` 返回 `Job <id> state=COMPLETED\|FAILED\|CANCELLED` + 原结果；工具描述同步 | `B4CliToolExecutor.kt` |
| D9 子 shell 语义未披露 | `b4.run` 描述写明 Windows 下 args 经 `pwsh -Command` 解释：`@file` 必须加引号、不要用 cmd 语法（**乱码本身仍是独立问题**） | `B4CliToolExecutor.kt` |
| D10 预算未在默认引擎生效 | 每次运行创建 `AgentTokenBudget`，工具循环的 `onModelResponse` 逐请求 `charge()`；80% 告警一次、超限抛 `TokenBudgetExceededException`，由 `resolveProblemWithRetry` 按永久错误处理（不重试、不再发请求） | `TokenBudget.kt`（`charge`）、`RobustBrowserAgent.kt`（`doRunCliAgentLoop` / `buildCliToolLoop`） |
| D14 dev bundle 来源不可信 | bundle 组装后逐 jar 比对 `lib/` 与本工作区 `*/target/` 的 SHA256：同名不同字节 → **构建失败**并打印两个路径 + 共享仓库提示；本工作区没有的 jar（老版本插件等）只告警。`-SkipMavenInstall` 时跳过（lib 可合法来自上次构建） | `build-runtime-bundle.ps1`（`Get-JarProvenanceReport` + 组装后校验） |
| D11（部分）批量采集没有落盘/地区指导 | browsing 的 context-hygiene 增两条：① 多条目任务边采边写（`.b4scratch/` 数据文件 + `.b4scratch/progress.txt` 记已完成 id），说明"每轮工具循环有上限，只留短摘要，只存在上下文里的条目会丢并重采"；② 站点按 host/IP 本地化时用 `set headers` 的 `Accept-Language`/cookie 或 `?language=en_US` 重新读页，不要对已本地化的值做事后处理 | `CliAgentPrompt.kt`（browsing hygiene） |
| D6/D13（决策） | 不引入 Excel 依赖、不内置 xlsx：交付用 CSV/JSONL；`.xlsx` **输入**用脚本抽取或先转 CSV（`coding.read` 对二进制直接拒绝）。提示词与 agent 参考文档已写明 | `CliAgentPrompt.kt`、`skills/browser4-cli/references/agent.md` |
| D2/D4（文档） | `agent.md` 新增"默认可读根"表（browsing / `--coding` / `--fresh-workspace`）、永远拒绝的敏感路径、只读的受保护文件、如何放开（`browser4.agent.allowExternalAccess=true`），并写明 `coding.shell` 参数与 `b4.run` 的 `file://` 是**受信逃逸口** | `skills/browser4-cli/references/agent.md` |
| D2 工作区隔离使 repo 路径不可读 | 任务启动前扫描 instruction 里的路径（Windows/POSIX 绝对、或含分隔符且带扩展名的相对路径），把工作区外**确实存在**的文件复制进 `<workspace>/.b4inputs/`，并在 user message 末尾给出「原路径 → `.b4inputs/xxx`」映射，同时注明"不要重试原路径、二进制输入仍需脚本读"。凭据路径永不复制、已在工作区内的跳过（coding 镜像不重复）、缺失的忽略；上限 20 个 / 单 10 MB / 总 25 MB。开关 `browser4.agent.copyTaskInputs`（默认 `true`） | `TaskInputs.kt`（新）、`RobustBrowserAgent.materializeTaskInputs`、`SensitivePaths` 改 public、`docs/config.md`、`references/agent.md` |
| D15 工具结果 body 压成一行 | body 改为 `boundedBody()`：CRLF 归一后**保留换行**，超预算时中间截断并标 `…[truncated N chars]…`；只有 summary 继续 inline 压缩 | `ToolOutcome.kt` |
| D11 文档缺口 | 新增 `references/bulk-collection.md`：① 边采边落盘（`crawl -o` + `coding.append` + `.b4scratch/progress.txt`，并说明轮次上限为何会丢数据）② locale/币种（`set headers`/cookie/`?language=en_US`，禁止事后换算）③ 大输出检查 ④ 交付格式（CSV/JSONL，无 xlsx；xlsx 输入先转）⑤ agent 场景下的引号。`shell-quoting.md` 增"agent 里由谁解析命令行"小节（`b4.run`→pwsh、`coding.shell`→cmd.exe、inline 脚本被拒）。同时把 agent.md 拆出两页：`agent-workspace.md`（可读范围/输入复制/交付格式）与 `agent-access.md`（预设/拒绝码/auto-approve），agent.md 605→**500** 行；SKILL.md 的 Reference Map 挂上三个新页 | `skills/browser4-cli/references/bulk-collection.md`、`agent-workspace.md`、`agent-access.md`、`shell-quoting.md`、`SKILL.md` |
| D1 fixture 缺失 | 用 `git checkout d80c3a0172 -- browser4-tests/pulsar-tests-common/src/test/resources/asin_field_description.xlsx` 把 main 上的字段说明表还原到 4.15.x（721,965 B，已 staged） | `browser4-tests/pulsar-tests-common/src/test/resources/asin_field_description.xlsx` |
| D4 沙箱可绕过 | `coding.shell` 新增参数路径边界（`workspaceBoundaryViolation`）：工作区外的字面绝对路径/`..` 越界 → `COMMAND_PATH_DENIED`；工具链目录（JDK/`~/.m2`/`~/.gradle`/temp/`%SystemRoot%`）、启动器（`mvnw`/`python3`/`*.exe|cmd|ps1`）、凭据路径保持原行为；开关 `browser4.agent.shell.workspaceBoundary`。`b4.run` 新增 `file://`/`upload` 越界拒绝（`externalPathRefusal`，`code=PATH_OUTSIDE_WORKSPACE`），`allowExternalAccess=true` 时放开 | `CodingAgentShell.kt`、`B4CliToolExecutor.kt`、`AgentToolManager.kt` |
| D5 尾巴 工具名猜测无反馈 | `system.exposeTools` 未命中时附 `closest: <同域最近 3 个真实工具名>`（前缀/包含优先，再按编辑距离）；`coding_run` → `coding_runCode`、`coding_exec` → `coding_shell` | `ToolDisclosure.kt`（`closestToolNames`） |
| D7 溢出后丢进度 | 溢出续写消息追加"Progress so far (do not start over)"块：本次运行已写文件（`changeSummary`，20 行/1200 字符上限）+ 明确"追加而非重写、先读 `.b4scratch/progress.txt`" | `RobustBrowserAgent.kt`（`progressDigestFromSummary`） |
| D9 报错乱码 | 子进程输出改为按行解码：严格 UTF-8 优先，失败才用 `native.encoding`（GBK/cp1252）回退，去 CR；pwsh 的中文 ParseError 不再乱码 | `CliProcessManager.kt`（`decodeLine`） |
| D12 无法续跑 | `agent run --resume <taskId> [task]`：后端从被中断任务的持久化行读取 instruction/workspace/coding/access，复制进新 run 的 instruction（"## Resume context" + 已有文件清单），复用原工作区并 rebind；CLI `--resume` 映射 `resumeTaskId`（允许省略 task 文本），REST `command.run` 放行空 command | `Models.kt`、`AgentResume.kt`（新）、`StatefulAgentRunner.kt`、`UserCommandExecutor.kt`、`CommandToolExecutor.kt`、`commands.rs`、`main.rs` |
| D14 根因 | build stamp 记录 `commit=<sha>`/`dirty=<bool>`（PowerShell 构建脚本与 Rust 刷新路径都写），launcher 的 staleness 检查发现 stamp 的 commit 与当前 HEAD 不同即判过期并重建（`bundle_commit_mismatch`） | `build-runtime-bundle.ps1`、`cli/browser4-cli/src/daemon.rs` |

验证：`mvnw.cmd -pl browser4-agentic -am "-Dtest=ai.platon.pulsar.agentic.**.*Test" test`
→ **1625 个单测全绿**（在 1615 基础上新增：`TaskInputsTest` 8、`ToolOutcome` body 换行/截断 2；更早的
拒绝语义 2、`--follow` 失败识别 3、预算 `charge` 2、`b4.wait` 终态 1 已计入）。

D14 守卫的验证：`build-runtime-bundle.tests.ps1` **194 项全绿**（新增 6 项：同名同字节 = match、同名异字节 = mismatch、
无本工作区产物 = not-built-here、空输入不炸等）；另对当前 bundle 干跑一次比对——11 个 first-party jar **全部 match**，
说明正常构建不会误报，而 smoke2 那种"拷到兄弟检出 jar"的情况会直接判失败。

D4/D5/D7/D9/D12/D14 的验证（本轮新增）：
- **全量 Kotlin 单测**（`browser4-coding,browser4-agentic,browser4-agent-tools,browser4-rest` + 上游模块）：
  **3787 通过 / 0 失败 / 0 跳过**（`BUILD SUCCESS`）；
- `CodingAgentShellWorkspaceBoundaryTest` 5/5 + `CodingAgentShell*Test` 41/41（既有 36 项在边界守卫下仍绿，
  工具链/启动器/凭据例外保持原行为）；
- `B4CliExternalPathTest` 5/5、`OverflowProgressTest` 3/3、`CliOutputDecodingTest` 3/3、`ToolDisclosureTest` 14/14、
  `AgentResumeTest` 5/5；
- Rust：`cargo test --bin browser4-cli` **1960 passed / 0 failed**（含 `--resume` 映射与 build-stamp commit 2 项）；
- REST：`CommandControllerTest` 16/16（新增参数的 Mockito 桩）、`ToolDocGeneratorTest` 3/3（`docs/mcp-tools.md` 重新生成，
  顺带补上此前遗漏的 `access`/`autoApprove` 与新的 `resumeTaskId`）。

### 7.1 实跑验证（smoke，2026-10-28 01:07–01:27）

任务：`coding_workspaceRoot` + `coding.read("D:\workspace\Browser4\README.md")`（工作区外）+ `taskComplete`。

| 轮次 | 结果 |
|---|---|
| smoke1（01:07） | 首份请求 **28** 个工具（含 `coding_changeSummary`/`coding_diff`/`coding_workspaceRoot`）；system prompt 含 `### Workspace boundary` + `allowExternalAccess=false`；`coding.read` → `coding.read [fail] Error: PERMISSION_DENIED …`（trace 可见）；模型未重试、未改用 shell，4.5s 完成并把该输入列进 `problems`。但 `--follow` **没有**打印拒绝行 → 发现 `ok` 推导只认裸 `[fail]`，补 `isFailedToolResult` |
| smoke2（01:19） | 首份请求退回 25 个工具、无边界块 → 实测跑的是共享 m2 里另一 worktree 的 jar（见 D14），结论作废 |
| smoke3（01:23） | 把本工作区 jar 放回 bundle 后：`--follow` 打印 `↳ coding_read failed after 19ms: coding.read [fail] Error: PERMISSION_DENIED \| code=PATH_DENIED …`；模型把拒绝写进 `problems`、未重试，6.0s 完成 |
| smoke4（02:17，D2 验证） | instruction 引用 `C:\…\Temp\b4-input-smoke.txt`（工作区外）：服务端把它复制进 `<workspace>/.b4inputs/b4-input-smoke.txt`，user message 出现映射（`→ .b4inputs/b4-input-smoke.txt (24 B)`）；模型**第一个调用**就是 `coding.read(".b4inputs/b4-input-smoke.txt")`，13.5s 完成并原样汇报 `ALPHA-1 / BETA-2`。同一轮暴露了 D15（read 结果被压成一行） |

结论：D2/D3/D5 的修复在真实后端上成立（工具集、提示词、模型可见结果、`--follow` 四处一致）；D2 的输入复制端到端可用。

仍未修：见 §8（D1、D4、D7、D11 的 SKILL 小节、D12、D9 的解码、D14 的根因；D6/D13 已按"不做 Excel"重新定义）。
D14 已加**构建期守卫**（不再静默测到别人的代码）。

## 8. 剩余工作项（更新于 2026-10-28 11:15）

| # | 项 | 状态 |
|---|---|---|
| 1 | **D1** fixture | ✅ 已用 `git checkout d80c3a0172 -- browser4-tests/pulsar-tests-common/src/test/resources/asin_field_description.xlsx` 还原（721,965 B，已 staged） |
| 2 | **D4** 沙箱一致性 | ✅ `coding.shell` 参数路径边界（工具链/启动器/凭据例外，开关 `browser4.agent.shell.workspaceBoundary`）；`b4.run` 的 `file://`/`upload` 越界拒绝（`code=PATH_OUTSIDE_WORKSPACE`），`allowExternalAccess=true` 时可放开 |
| 3 | **D11** 文档 | ✅ `bulk-collection.md` / `agent-workspace.md` / `agent-access.md` + `shell-quoting.md`；skill-doc-lint **0 issue** |
| 4 | **D7** 轮次/进度 | ✅ 溢出续写消息附“已写文件”进度块（`progressDigestFromSummary`，20 行 / 1200 字符上限）+ 提示“不要重来、追加而非重写” |
| 5 | **D12** 断点续跑 | ✅ `agent run --resume <taskId>`：读取被中断任务的 instruction/workspace/files，复用工作区、继承 coding/access；CLI→REST→runner 全链路 + 5 个 Kotlin 单测 + 2 个 Rust 单测 |
| 6 | **D9** 乱码 | ✅ 子进程输出按行“严格 UTF-8 → 平台字符集回退”解码（`decodeLine`，3 个单测） |
| 7 | **D14** 根因 | ✅ build stamp 记录 `commit=`/`dirty=`（PowerShell + Rust 双写），launcher 发现 commit 不同即判 bundle 过期；构建期 jar 来源守卫仍在 |
| 8 | **D5 尾巴** | ✅ `system.exposeTools` 未命中时返回同域最近的 3 个真实工具名（`closestToolNames`，含前缀优先） |
| 9 | **验证** | ✅ 全量单测 3787/0、Rust 1960/0；重建 bundle 后重跑亚马逊任务（见 §9） |

本轮新增完成：**D1**（fixture 已从 main 还原）、**D2**（任务输入自动复制进 `.b4inputs/`）、**D15**（工具结果 body 保留换行）、**D11**（批量采集/工作区/权限三页文档 + lint 0 issue），见 §7。D1 修好后，重跑里模型第一次调用就找到了 `.b4inputs/asin_field_description.xlsx`（§9）。

## 9. 修复后重跑对照（2026-10-28 12:06–12:26）

同一任务、同一后端（重建后的 bundle，含 D2/D3/D4/D5/D7/D9/D12/D14）：
`b4w.ps1 agent run "到亚马逊上找到今天卖得最好的20个微波炉…asin_field_description.xlsx" --follow`。
为让长任务跑完，`agent.token.budget.total` 临时提到 9M（重跑后已还原为默认）；任务 `c00736fa-…`，
run dir `logs/agent/20261028.120649/d1fef892-…`，完整输出 `rerun-amazon-2026-10-28.log`，
指标提取脚本 `rerun-metrics.py`。

| 指标 | 修复前（10-27 00:23） | 修复后（10-28 12:06） | 依据 |
|---|---|---|---|
| 时长 | 29m31s | 19m28s（预算中止） | follow 日志 |
| 工具调用 | 422 | 425 | `cli-tool-trace.jsonl` |
| LLM 请求 / 输入 token | 380 / 7,873,892 | 375 / 8,882,865（输出 131,979） | `cli-usage.jsonl` |
| 内层轮次溢出 | 6 | 5 | pulsar.log `exceeded max iterations` |
| 权限拒绝 | 20 | 15 | trace `PERMISSION_DENIED` |
| ParserError | 18 | 3 | trace `ParserError` |
| 字段表（xlsx） | 前 10 分钟靠 `dir /s`、`git -C` 越界找，最后仍未找到 | **第 1 个调用** `coding_listDir(".b4inputs")`，30 秒内用 python+openpyxl 读出 75 个字段 | follow 日志 #1–#22 |
| 交付物 | `out*.csv`：121 列、非规格字段，无电子表格 | `amazon_microwave_top20.csv`：**20 行 × 75 列**，列名即字段规格 | workspace 实测 |
| 完成上报 | 无 `taskComplete` | 无 `taskComplete`（预算中止） | trace |

结论：

- ✅ D2/D4 生效：输入不存在于工作区外，但被自动复制并用脚本读出；未再出现越界 `dir /s`、`git -C`（也再次印证 D4 的价值——模型这次改走 `python` 读复制件）。
- ✅ D5/D9/D3 生效：exposeTools 的近似建议、乱码解码、拒绝可见性都在真实运行里工作（拒绝行带 `[fail]`，`--follow` 正常打印）。
- ✅ D10 预算生效：第 19 分钟在 9,014,844 tokens 处**干净中止**并给出可操作的配置提示，而不是像修复前那样无上限烧到 7.87M 还要 29 分钟。
- ✅ 交付物首次按规格产出（20×75 CSV，无 xlsx 尝试）。
- ❌ **仍不收敛**：模型把 75 个字段里的长尾（卖家信息、A+ 内容、图片元数据）逐个补齐，没能在写完 20 行后收尾；
  已加提示（见下 D19），需下一轮验证。

### 9.1 重跑中发现并已修的回归/缺陷

| 编号 | 现象 | 修复 |
|---|---|---|
| **D17**（回归） | `memory.read` 返回的 JSON 内嵌旧拒绝文本（`PERMISSION_DENIED \| code=…`），D3 的"首行包含"判定把**成功**的读取标成 `[fail]` | `isPermissionRefusal` 改为"首行必须以 envelope 开头"（仅容忍 `Error: ` 前缀）；新增 JSON 内嵌拒绝的用例 |
| **D16** | `coding.shell` 把 `2>&1` 的 `&` 当命令分隔符 → `command '1' is not allowed`，一次运行 9 次 | 识别描述符重定向（`2>&1`/`>&2`）为参数；`more` 加入安全命令；新增 1 个用例 |
| **D19** | 写完 20×75 CSV 后继续打磨长尾字段，直到预算耗尽 | 提示词加"写完并抽查（行数+样例值）就 `taskComplete`，填不上的字段写进 `problems`" |

### 9.2 记录未修

- **D18**：`coding.shell` 不支持 bash heredoc（`python - <<EOF`）与 cmd 控制流（`if exist … (…) else (…)`），
  模型改用"写脚本文件再执行"绕过，代价约 10 次调用。属 shell 语法能力边界，下一轮可评估是否支持 heredoc。

### 9.3 D12 断点续跑实跑（13:22）

此前 D12 只有单测（`AgentResumeTest` 5 项 + Rust 2 项），本轮补了真实后端验证：

1. 种子任务 `24574e6c-…`（"不调用工具，直接 taskComplete"）1.5s 完成，workspace
   `…/2026/10/28/7cebbeb5-…/workspace`；
2. `b4w.ps1 agent run --resume 24574e6c-… "…" --follow` → 新任务 `8e8e16ea-…`，1.7s 完成，
   返回的 `workspace` 与种子任务**完全相同**；
3. 请求 dump 的 user message 含：

   ```
   ## Resume context

   This run resumes task 24574e6c-…, which was interrupted before completion
   (last activity 2026-10-28T05:22:03Z, 0 tool call(s) executed).
   Its workspace is C:\…\agent\2026\10\28\7cebbeb5-…\workspace and already contains:
   - (no files yet)
   ```

即 CLI（`--resume`）→ `command.run`（`resumeTaskId`）→ runner（checkpoint 读取、工作区 rebind）→ prompt
全链路在真实后端成立。证据：`resume-smoke-2026-10-28.log`、`resume-seed-2026-10-28.log`。
