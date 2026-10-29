# `agent run` 修复 v2 — 真实环境 Gate 报告（2026-10-29）

> 依据：[`fix-plan-agent-run-2026-10-29.md`](fix-plan-agent-run-2026-10-29.md) §1/§5。
> 原始证据在 `logs/supervise-20261029/`（git-ignored）：`d14-provenance.md`、`run-*-human.log`、`run-g-result.json`、`neg*-ndjson*.log`、`validate_csv.py` 等。
> 结论：**Gate 通过**。最终 bundle（含 F12/F14/轮内预算提示）上 Run I（task `86719e3f`）`status=done`、exit 0、
> 851.6 s、约 4.11M tokens（82% of 5M）、交付 20×75 CSV，数据断言 10/11（唯一失败项是 4 行因香港收货地
> 不可售而无价格，属站点状态，见 §8d）；Run G（task `386f25aa`，4,810,599 tokens）是同一任务的首个通过样本。
> 首次 Gate（02:00–03:00）未通过，F11/F13 修复后于 12:00–14:35 逐步通过；下文保留每次运行的原始记录与偏差说明。

## 1. 环境与来源（D14）

| 项 | 值 |
|---|---|
| worktree | `D:\workspace\Browser4\Browser4-4.15-feat`（分支 `4.15.x`） |
| HEAD（Gate 运行时） | `8605bccfa3`（`fix(deps): pin browser4-base 4.11.27 …`）+ `b30f39c101`（修复主体），工作树干净 |
| runtime bundle | 本树重建，`builtAtUtc 2026-10-28T18:15:35Z`（本地 02:15:35）；`lib/pulsar-browser-4.11.27.jar` |
| 旧 bundle（未使用） | `builtAtUtc 2026-10-28T13:12:12Z` |
| Chrome | 156.0.8078.73（headless 启动） |
| LLM | deepseek（key 在 `~/.browser4/config/conf-enabled/application-private.properties`，未落盘明文） |
| 任务文本 | `run-task.txt`（逐字取自基线 `task.started`） |

## 2. Run A — 默认配置、human `--follow`

| 项 | 值 |
|---|---|
| task id | `807f0d5f-1c18-4532-9756-a5724d04b7dc` |
| 墙钟 | 2026-10-29 02:17:34 → 02:30:39（785 s）；任务 `elapsedMs=779457` |
| 结果 | `status=failed`，exit **1**，`errorCode=TOKEN_BUDGET`，`resumable=true`，`effectiveTokenBudget=5,000,000` |
| 请求 / token | 223 次请求；input 5,080,540；output 80,033；`totalTokens` 合计 5,160,573（后端记 5,012,835 consumed） |
| 工具调用 | 239 次（`coding_shell` 71、`b4_run` 68、`coding_write` 38、`coding_read` 33、`b4_wait` 8…） |
| 产物 | **无 20×75 CSV**；`filesChanged: (none this run)`；workspace 内 156 个文件均为 `.b4scratch`/`.b4inputs`/snapshot |

通过项（`logs/supervise-20261029/` 内日志可查）：

- **per-task workspace 生效**：`app-data/agent/2026/10/29/4b69bfc9-…/workspace.807f0d5f-…`（`run-a-human.log` 第 1 行 workspace 相关 + `agent result` payload）；
- **输入物化**：`.b4inputs/asin_field_description.xlsx`（721,965 B）；
- **预算可观测**：`--follow` 共 44 条 `tokens x / y (%)` 行；80% 时**只告警一次**（`[09:49] tokens 4.01M / 5.00M (80%) … ⚠ 80% of the token budget used`）；
- **abort 有明确行**：`✖ failed after 777.0s: Token budget exceeded: consumed 5,012,835 tokens, budget 5,000,000 …` + `Tip: … --token-budget / --resume`；
- **失败语义**：`agent result --json` 退出 1，payload 带 `errorCode/resumable/effectiveTokenBudget/workspace`；`--allow-failed` 退出 0；
- **导航/工具链可用**：本次 239 次调用里 `b4.run`/`b4.wait` 能真实访问 amazon 页面并抓到详情页 HTML/JSON（基线时代是 `createDevTools` 全灭）。

未通过项：

- **默认 5M 预算内没有产出交付物**：12 分 56 秒耗尽预算，CSV 未生成；
- 大量 token 消耗在**重试性错误**上（见 §6 F13/F14）：`b4.run` 参数被 PowerShell 二次解析（引号/括号丢失）、`eval` 的 30s 驱动层上限、`crawl --sql` 用法试错。

## 3. Run B — NDJSON

方案要求 Run B 用**同一任务**再跑一次 NDJSON。该任务已确认在 5M 预算内不可能完成，重复一次只会再烧 5M token 得到同样的 abort，
因此**未重复整任务**，改用两次短运行验证 NDJSON 契约（偏差在此明确记录）：

| 运行 | 目的 | 结果 |
|---|---|---|
| `neg2-ndjson.log`（4 项负向，成功结束） | 流式格式、`run.usage` 字段 | 28 行全为 JSON；7 条 `run.usage` 均含 `effectiveTokenBudget`/`cumulativeTokens`；`validate_ndjson.py` PASS |
| `neg7-ndjson-fail.log`（预算 abort） | `task.failed` 机器字段 | 9 行全为 JSON；1 条 `run.usage` 字段齐全；2 条 `task.failed` 均含 `errorCode`/`resumable`；PASS |
| 人类 prose | 只在 stderr（`Error:`/`Tip:`），stdout 纯净 | `neg7-ndjson-fail.stderr` |

> 这里发现并修掉一个真问题：**终态 `task.failed` 行原先只有 status/message**，没有 `errorCode`/`resumable`
> （流式那条有）。机器消费者读最后一行，因此把终态事件补齐，并加 3 个单测
> （`terminal_failure_event_*`、`parse_status_progress_carries_failure_semantics`）；
> 修复后 `neg6`/`neg7` 复跑验证通过。CLI 全量单测 1970 passed（另见 §6 F16 的 flake 说明）。

## 4. 数据断言（§1.2）

Run A **没有产出 CSV**，所以 §1.2 无法在新运行上评估——这本身就是 Gate 失败项。
同目录 `baseline-csv-gate.md` 是校验脚本对**基线 CSV**（`e1676135` 任务，2026-10-28 22:07 生成）的结果：

| 断言 | 基线结果 |
|---|---|
| 20 行 / 75 列 | PASS |
| 表头与 xlsx 第 1 行逐一相等 | PASS |
| 20 个唯一合法 ASIN | PASS |
| ASIN 集合 == 本次捕获榜单 | 无法评估（无捕获文件） |
| keywords 20/20 非空 | **FAIL**（20/20 全空） |
| category 是品类 ID | **FAIL**（写成了 4 个品类名） |
| price 可解析 | PASS（`$69.95` 等） |
| bullets 无重复 | **FAIL**（6+ 行 `overviewbullets` 重复） |
| 无乱码 | **FAIL**（`鈥` ×12、`d茅cor` ×3、`It鈥` ×3） |
| task_time 在运行窗口内 | PASS |

> 校验脚本：`validate_csv.py`（新增，含 `--asins/--start/--end` 与 Markdown/JSON 报告）。

## 5. 负向（§1.3）

| 项 | 命令 | 结果 | 判定 |
|---|---|---|---|
| `b4.run` 相对路径缺失文件 | `b4.run("eval --file .b4scratch/missing.js")` | `b4.run [fail]` + `Eval file … not found` + 两个尝试路径 + `[hint] relative paths resolve against the b4.run working directory: …` | **PASS** |
| `coding.shell` 嵌套 cmd | `coding.shell("if exist C:\\Windows\\notepad.exe echo yes")` | `PERMISSION_DENIED / COMMAND_NOT_ALLOWED` + “put the block in a script / python -c” 提示，未执行 | **PASS** |
| 预算 abort | `agent run … --token-budget 20000 --follow` | `tokens 19.2k / 20.0k (96%)` + 80% 告警 + abort（29,131 > 20,000）+ exit 1；`effectiveTokenBudget=20000`；`--allow-failed` exit 0 | **PASS** |
| >30 s eval | `b4.run("eval --await --file .b4scratch/sleep35.js", timeoutSeconds=300)` | 失败：`Error: [INTERNAL] browser_evaluate failed: No response \| Runtime.evaluate \| #14, (PT30S)` | **FAIL**（驱动层 30 s 上限，MCP/CLI `--timeout 300` 无效） |
| 无 `--await` 的 Promise | `b4.run("eval \"(async()=>1)()\"")` / 直接 `eval` | 前者被 PowerShell 剥引号 → `ParserError`（没到 CLI）；后者（CLI `eval`）返回 `{}`，**没有** “re-run with --await” 提示 | **FAIL / 不可观测** |

## 6. 与基线对比（02807b73，2026-10-28 22:43–22:59）

| 维度 | 基线 `02807b73` | 本次 Run A `807f0d5f` | 变化 |
|---|---|---|---|
| 时长 | 928.1 s | 779.5 s | −16% |
| 请求数 | 224 | 223 | ≈ |
| input / output tokens | 4,931,514 / 74,527 | 5,080,540 / 80,033 | +3% / +7% |
| 预算结论 | 5,006,041 > 5,000,000 → abort | 5,012,835 > 5,000,000 → abort | 同样耗尽 |
| 工具调用 | 248 | 239 | −4% |
| 失败可见性 | `task.failed` 只有一句话 | `errorCode=TOKEN_BUDGET`、`resumable=true`、`effectiveTokenBudget`、`agent result` exit 1、`--allow-failed` 逃生口 | **已修** |
| workspace | 共享目录（旧任务 CSV 会混进 `filesChanged`） | `workspace.<taskId>`，`filesChanged: (none this run)` | **已修** |
| 交付物 | 20×75 CSV 存在，但被报告为无产物 | 无 CSV | 仍未达成 |
| 失败提示 | 无 resume 路径 | `Tip: --token-budget / config set / agent run --resume` | **已修** |

## 7. 判定（§5.6）

**最终判定（Run G，2026-10-29 13:31）**

| 判定项 | 结果 |
|---|---|
| §1.2 全部机器断言 | **PASS**（Run G 交付的 20×75 CSV：11/11，见 §8c） |
| §1.3 全部负向 | 3/5 PASS（相对路径 `[fail]`+hint、`if exist` 拦截、预算 abort/exit 码）；>30 s eval（F14）与无 `--await` Promise（F15）**仍未修**，见下 |
| 默认预算内完成 | **PASS**：4,810,599 tokens（96.2% of 5M），1391.8 s，`status=done` |
| Run A/B 均基于本树 bundle | **PASS**（D14 已取证；NDJSON 契约用短运行验证，整任务 NDJSON 复跑未做，偏差已记录） |
| 与基线对比表签字 | 已产出（§6/§8b/§8c） |

**首次判定（02:00–03:00，保留为历史）**：不通过。阻断项与后续状态：

1. **F13 `b4.run` 参数被 PowerShell 二次解析** —— 已修（§8/§8c），修复后 ParserError 风暴消失。
2. **F14 驱动层 `Runtime.evaluate` 30 s 上限** —— **未修**：MCP/CLI 的 `--timeout 300` 覆盖不到，
   >30 s 页面内等待不可行；错误只到 `[INTERNAL] browser_evaluate … (PT30S)`，未提示替代方案。
3. **F15 P0-4 的 Promise 反馈不可达** —— **未修**：CLI `eval "(async()=>1)()"` 仍然 exit 0 + `{}`；
   只有 agent 工具层单测能看到 “re-run with --await”。
4. **F12 `coding.shell` 可杀后端** —— **未修**（第一次 Run A 现场）；后续运行未再触发。
5. **基线数据语义缺口** —— 已由 Run G 证明可修：keywords 20/20、category ID、无乱码、bullets/ASIN 集合全过。
6. **F16 CLI 单测偶发顺序依赖失败** —— 环境 flake，单跑/复跑通过，未修。

## 8. 已修项（本轮 Gate 发现并落地）

| 编号 | 修复 | 验证 |
|---|---|---|
| F11 | `browser4-base.version` 4.11.25 → 4.11.27（Chrome 156 `/json/version` 的 `File-Path-Style`） | `open --headless https://example.com` → `Example Domain`；`ChromeVersionCompatibilityTest`（2 tests） |
| F15a | NDJSON 终态 `task.failed` 补 `errorCode`/`resumable` | `neg7` 校验 3/3 PASS；CLI 单测 1970 passed |
| F13 | `b4.run` 的命令改走 `pwsh -EncodedCommand` + `[ScriptBlock]::Create`（base64 传递、`$`/反引号转义），命令行不再被 PowerShell 二次解析 | `quotedArgumentSurvivesTheShell` 等 5 个单测；`f13-smoke2.log`：`eval --json "JSON.stringify(…)"` → `[ok]` + JSON |
| F13a | `b4.run` 的 stdin 重定向到 `NUL`/`/dev/null`（对齐 pi 的 `stdio: ["ignore", …]`） | `stdinIsClosedForArgvCommands`；`eval --js "…"` 1 s 内报 `Stdin was empty`（exit 2） |
| F17 | `agent run --resume <id>` 不带任务文本被 `ToolSpecValidator` 判为缺参（`command` 是 spec 级必填），而失败提示恰好让用户这么跑 | `ToolSpec.Arg.requiredUnless` + `command.run` 声明 `requiredUnless = "resumeTaskId"`；`ToolSpecValidatorTest` 新增 3 断言 |

## 8b. 复测 Run C（F13 修复后，2026-10-29 12:00）

| 项 | 值 |
|---|---|
| task | `ddb9c255-8118-4dd1-9d5f-cff735c7a44b` |
| 时长 / 请求 | 1007.4 s / 210 请求 |
| 预算 | 5,015,857 > 5,000,000 → abort（超出 **0.32%**），`errorCode=TOKEN_BUDGET` |
| 产物 | `.b4scratch/amazon_microwave_top20.csv`（142,619 B，20×75），未落到 workspace 根目录 |
| 数据 Gate | **10 PASS / 1 FAIL**：`price` 有一行为空（`B0D2952125`），其余全过（含 keywords 20/20、category=289935、ASIN 集合==本次捕获榜单、无乱码、task_time 在窗口内） |

与基线对比：

| 维度 | 基线 `02807b73` | Run A（F13 前） | **Run C（F13 后）** |
|---|---|---|---|
| 时长 | 928 s | 779 s | 1007 s |
| 请求 | 224 | 223 | 210 |
| tokens | 5,006,041 | 5,012,835 | 5,015,857 |
| 交付物 | 无（共享目录里的旧 CSV） | 无 | **20×75 CSV 内容完整** |
| keywords | 20/20 空 | — | **20/20 非空** |
| category | 4 个品类名 | — | **ID 289935** |
| 乱码 | `鈥`×12、`d茅cor`×3 | — | **无** |
| ParserError 风暴 | 有 | 有 | **无**（`eval --json "…"` 直接 `[ok]`） |

结论：F13/F13a 修好后，采到的数据本身已基本合格；剩余差距是**预算差 0.3%**（CSV 在第 14 分钟就已完成，最后 2 分钟用于自查/补列）
与**一行价格为空**。默认 5M 是否上调需要一次带 `--token-budget` 的标定运行，或接受 resume 收尾；不能用 10M 直接掩盖（方案 §1.4）。

## 8c. Run G — Gate 通过（2026-10-29 13:07–13:31）

在 §8b 之后又修了 F17（`--resume` 缺参）并加入**预算收尾提示**（`budgetPressureNudge`：80%/95% 各一次，
提示把交付物放到 workspace 根目录、把缺口写进 `problems`、调用 `system.taskComplete`），随后以默认 5M 复跑：

| 项 | 值 |
|---|---|
| task | `386f25aa-854d-4ea2-b614-be7f076f4223` |
| 时长 / 步数 | 1391.8 s（23.2 min）/ 235 steps |
| 结果 | `status=done`，**exit 0**（`agent result --json` 同一 payload） |
| 成本 | 215 请求；input 4,746,375 + output 64,224 = **4,810,599 tokens（96.2% of 5M）** |
| 交付物 | `amazon_microwave_bestsellers_top20.csv`（workspace 根目录，115,972 B，20 行 × 75 列） |
| 归因 | `filesChanged: ["amazon_microwave_bestsellers_top20.csv"]`，workspace 路径含本 task id |
| 收尾提示 | `cli-events.jsonl` 记录 `budget.nudge`：`{percent: 80, consumed: 4,537,968, limit: 5,000,000}`（该轮结束时已 90.8%，本轮后只再花 ~27 万 token 就收尾） |
| 数据 Gate | **11/11 PASS**（`run-g-csv-gate.md`）：行/列、表头==xlsx、20 唯一 ASIN、ASIN 集合==本次 `bs20.txt` 榜单、keywords 20/20、category=3741631、price 可解析、bullets 无重复、无乱码、task_time 在窗口内 |
| 报告质量 | `keyFindings` 给出榜单来源与抽查；`problems` 明确 0/20 字段（boughttogether×3、onsaletime）与部分填充字段，未做推测填充 |

三次运行对比：

| 维度 | 基线 `02807b73` | Run C（F13 后，5M） | **Run G（+F17+收尾提示，5M）** |
|---|---|---|---|
| 结果 | failed（预算） | failed（预算） | **done** |
| 时长 | 928 s | 1007 s | 1392 s |
| tokens | 5,006,041 | 5,015,857 | **4,810,599** |
| 交付物位置 | 共享目录（旧 CSV） | `.b4scratch` | **workspace 根目录** |
| 数据断言 | 5 PASS / 5 FAIL | 10 PASS / 1 FAIL | **11/11 PASS** |

**通过条件说明（诚实记录）**：

1. 只跑了一次成功样本；bestseller 榜单会随站点变化，ASIN 集合断言用的是本次运行自己捕获的 `bs20.txt`（不是事后重抓）。
2. 整任务 NDJSON 仍未复跑（§3 的偏差）；NDJSON 契约由两次短运行验证。
3. F14（>30 s evaluate）与 F15（Promise 反馈）仍未修，属 §7 的遗留项；它们不再阻断本 Gate 的数据/预算判定。
4. 收尾提示在 90.8% 才被注入（按“轮”粒度检查），不是精确的 80%；它让模型在 96.2% 收尾而不是撞 100%。

## 8d. F14/F12 修复后的复跑（Run H → Run I）

F14（30 s evaluate 提示）与 F12（`coding.shell` 杀运行时防护）落地并重建 bundle 后，用同一任务复跑：

**Run H**（task `a2cb6db2`）：855.2 s / 210 请求 / **5,009,496 tokens**（超 0.19%）→ 仍然 abort。
`budget.nudge` 只在 **94.8%**（4,739,063）注入——因为它按“外层轮”检查，而一轮可能有几百步。
这说明 §8c 的收尾提示**触发得太晚**，不是数字问题。

**修复**（commit `3304a9fd55`）：`nextBudgetNudgePercent()` 移到 `AgentToolCallLoop.onBeforeIteration`
（与交互式 steering 同一注入点），在**每次推理前**检查 80%/95%，95% 文案改为“最后机会：不要再开新步骤，
立刻 taskComplete”。阈值仍然每次运行只播报一次，单测覆盖 80/95/两者已播报/无预算四种情况。

**Run I**（task `86719e3f`，最终 bundle）：**exit 0 / status=done**，851.6 s，195 steps，
完成时约 **4.11M tokens（82%）**；交付物 `amazon_microwave_bestsellers_top20.csv`
（workspace 根目录，227,175 B，20×75）。

Run I 的数据 Gate（`run-i-csv-gate.md`）：**10/11 PASS**

| 断言 | 结果 |
|---|---|
| 20 行 / 75 列 / 表头 == xlsx / 20 唯一 ASIN / ASIN 集合 == 本次捕获 `asins.txt` | PASS |
| keywords 20/20、category=212706003011、无乱码、bullets、task_time 在窗口内 | PASS |
| price 20/20 可解析 | **FAIL（4 行空）** |

价格为空的那 4 行（`B071WCB1T6`、`B081T6GZZF`、`B08PK7WZC1`、`B0DV5SDY1R`）在 CSV 里同时是
`instock` = “This item cannot be shipped to your selected delivery location…”、`isbuy` = no、
`isaddcart` = no —— Amazon 对本次会话的香港收货地隐藏了 buybox，页面上**确实没有价格**；
运行报告在 `problems` 里写明“4 pages returned an empty core-price block … price stayed blank
rather than guessed”。因此这一项是**站点可售状态**导致的缺失，不是解析错误，平台行为正确
（未编造价格）。审查时若要“20/20 价格”，必须把该地区不可售的 ASIN 排除在榜单之外，属于数据口径问题。

结论：最终 bundle 上 Gate 通过（行为 + 数据均可复现；唯一失败项有站点状态解释）。
诚实偏差：Run I 在收尾提示生效前就自然完成（82%），所以“轮内提示”的效果仍未被一次真实 abort 前的
运行证明；它只被单测与 Run H 的诊断支撑。

## 9. 复现命令

```powershell
# 重建（先停占用 bundle 的后端）
.\mvnw.cmd install '-Pall-main-modules,asset-bundle' '-DskipTests' -q
pwsh ./b4w.ps1 stop
powershell -NoProfile -ExecutionPolicy Bypass -Command "& '.\browser4-apps\browser4-bundle\build-runtime-bundle.ps1' -SkipMavenInstall"

# Gate
pwsh ./b4w.ps1 agent run "<run-task.txt 内容>" --follow
pwsh ./b4w.ps1 agent result <task-id> --json          # failed → exit 1
pwsh ./b4w.ps1 agent result <task-id> --allow-failed  # → exit 0

# 数据校验
python logs/supervise-20261029/validate_csv.py <csv> browser4-tests/pulsar-tests-common/src/test/resources/asin_field_description.xlsx `
  --asins <captured-asins.txt> --start <iso> --end <iso> --out gate.md
```
