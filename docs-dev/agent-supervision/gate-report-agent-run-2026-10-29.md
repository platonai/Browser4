# `agent run` 修复 v2 — 真实环境 Gate 报告（2026-10-29）

> 依据：[`fix-plan-agent-run-2026-10-29.md`](fix-plan-agent-run-2026-10-29.md) §1/§5。
> 原始证据在 `logs/supervise-20261029/`（git-ignored）：`d14-provenance.md`、`run-a-human.log`、`result-a-human.json`、`neg*-ndjson*.log`、`validate_csv.py` 等。
> 结论：**Gate 未通过**（数据/预算维度），行为维度大部分通过；下方 §7 列出阻断项。

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

| 判定项 | 结果 |
|---|---|
| §1.2 全部机器断言 | **FAIL**（无 CSV；基线 CSV 也只在部分维度达标） |
| §1.3 全部负向 | **FAIL**（>30 s eval、无 `--await` Promise 两项不达方案预期） |
| Run A/B 均基于本树 bundle | **PASS**（D14 已取证；NDJSON 契约用短运行验证，偏差已记录） |
| 与基线对比表签字 | 已产出（§6），结论：**失败可见性与隔离修好了，任务仍未在默认预算内完成** |

**Gate 结论：不通过。** 阻断项按优先级：

1. **F13（新）`b4.run` 参数被 PowerShell 二次解析**：含引号/括号的 `eval` 命令会被拆坏
   （`eval (async()=>1)()` → `ParserError`；`eval --js JSON.stringify({…})` → “参数列表中缺少参数”）。
   Agent 只能改用文件方式重试，这是本次 5M 预算里最大的一块浪费，也直接压掉了交付物。
   证据：`run-a-human.log` 第 100/170/205/228 行附近 + `neg2` 的【2】。
2. **F14（新）驱动层 `Runtime.evaluate` 30 s 上限**：MCP/CLI 的 `--timeout 300` 覆盖不到，
   任何 >30 s 的页面内等待都不可能通过 `eval --await` 完成；错误文本只到 `[INTERNAL] browser_evaluate … (PT30S)`，未提示替代方案。
3. **F15（新）P0-4 的 Promise 反馈不可达**：CLI `eval` 返回 `{}`（静默），`b4.run` 路径被 F13 破坏；
   `JsEvaluationFeedbackTest` 覆盖的是 agent 工具层，用户/模型实际走的两条路都看不到“re-run with --await”。
4. **F12（未修）`coding.shell` 可杀后端**：第一次 Run A（`a1-run-human.log`）里模型执行
   `taskkill /PID 24448 /F /T` 把后端杀掉；`33be64` 只封了 `b4.run` 的生命周期命令。
5. **数据 Gate 的语义缺口仍在**：基线 CSV 的 keywords 全空、category 写品类名、bullets 重复、乱码
   —— 这些属于 P2-1 的“字段规格/输入保真”没有覆盖到的采集语义，不是本次 4 个 P0/P1 包能修的。
6. **F16（环境）** CLI 单测偶发顺序依赖失败：
   `daemon::tests::test_expected_runtime_tag_prefers_local_bundle_in_checkout`
   在全量跑时曾失败（`Some("v4.14.0")` vs `Some("local")`），单跑与复跑均通过（1970 passed）。

## 8. 已修项（本轮 Gate 发现并落地）

| 编号 | 修复 | 验证 |
|---|---|---|
| F11 | `browser4-base.version` 4.11.25 → 4.11.27（Chrome 156 `/json/version` 的 `File-Path-Style`） | `open --headless https://example.com` → `Example Domain`；`ChromeVersionCompatibilityTest`（2 tests） |
| F15a | NDJSON 终态 `task.failed` 补 `errorCode`/`resumable` | `neg7` 校验 3/3 PASS；CLI 单测 1970 passed |

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
