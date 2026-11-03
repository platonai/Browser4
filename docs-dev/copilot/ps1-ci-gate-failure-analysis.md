# ps1-tests 门禁失败分析：为什么 PowerShell 检查"总是红"，以及怎么改

日期：2026-11-02 ｜ 触发点：[run 41459924393](https://github.com/platonai/Browser4/actions/runs/41459924393)（`main`，提交 `70723b1`）
｜ 范围：`.github/workflows/ps1-tests.yml`（workflow id 312834004）最近 100 次运行

## 结论摘要

1. **那次运行里 PowerShell 测试并没有失败**。25 个 Pester 文件全部通过
   （`[PASS] PowerShell tests: 25 passed, 0 failed (25 total)`），真正失败的是紧接着的
   `skill-doc-lint`：`skills/browser4-cli/references/browser-modes.md` 是 541 行，超过 M6 的 500 行上限。
2. **这不是偶发，而是主模式**：最近 100 次运行 **34 次失败**，其中 **24 次**是 skills/ 文档的 M6 行数规则
   （其余 10 次是同一个**真实**断言失败，见下文）。最近 21 次失败里 Pester 每次都是 25/25 全绿。
3. 失败被**错误归因**：job 叫 `PowerShell Tests`，失败的 step 叫 `Run PowerShell tests`，而真正反映原因的
   `Skill document conformance` step 因为前一步已失败而**永远被 skip**——看板上只剩"PowerShell 又红了"。
4. 规则本身**超出了方法论的机器口径**：`methodology.md` 的 P1 机器测试只规定 *SKILL.md ≤ 500 行*，
   对 `decision`/`procedure` 参考文档给的是 **target**（目标值）；`skill-doc-lint.ps1` 却把它做成了
   硬门禁。24 次 M6 失败里 17 次纯属参考文档（`agent.md` 513→557、`crawl.md` 502→573、
   `browser-modes.md` 513/541），**没有一次对应真实的 PowerShell 缺陷**。

## 数据

统计口径：GitHub API `repos/platonai/Browser4/actions/workflows/312834004/runs?per_page=100`，
逐次用 `gh run view <id> --log-failed` 提取失败签名。

| 结论 | 次数 |
| --- | --- |
| success | 64 |
| failure | 34 |
| cancelled / action_required | 1 / 1 |

34 次失败的分类（全部人工核对失败签名，不是按 step 名猜的）：

| 类别 | 次数 | 时间窗 | 失败签名 | Pester 状态 |
| --- | --- | --- | --- | --- |
| M6 行数超限（skills/ 文档） | **24** | 10-11 → 11-02 | `[M6] procedure doc is 507 lines (cap 500)` 等 | 25/25 全绿 |
| 真实断言：test-type map 漂移 | 10 | 10-15 → 10-23 | `Map drift: no keys missing from test copy` | 该文件真失败 |

M6 的"罪犯"在**轮换**，这正是行数上限成为噪音的原因：

| 文档 | 出现过的行数 |
| --- | --- |
| `references/agent.md` | 513 → 542 → 555 → 556 → 557 |
| `references/crawl.md` | 502 → 507 → 573 |
| `SKILL.md` | 504 → 512 → 535 |
| `references/browser-modes.md` | 513 → 541 |

削掉一篇的 40 行，另一篇就顶上来；文档在 500 行上下反复被"修剪"，而不是按内容需要增长。

## 根因

- **RC1 归因错位。** 文档合规被塞进 `test.ps1 ps` 的末尾，又作为 `ps1-tests` 的同一次运行出现。
  一次运行里先打印 `[PASS] PowerShell tests`、再打印 `[FAIL] Skill document conformance`，
  最后 `##[error]Process completed with exit code 1.`——但 GitHub 上呈现为 `PowerShell Tests` 红。
  真正命名的那一步被 skip，等于**没有任何地方**报告真实原因。
- **RC2 规则越权。** 方法论把"500 行"同时写成 SKILL.md 的**机器测试**（P1）和参考文档的**目标**
  （tier 表 `Target Length`）；linter 把两者都实现成硬失败。参考文档是按需读取的，
  超 20 行并不会挤占启动上下文——它只是"该整理了"，不是"不能合入"。
- **RC3 触发错配。** `skills/**/*.md` 在 `ps1-tests.yml` 的 `paths` 里：改一行文档要跑 25 个 Pester 文件（~3 min），
  而且会被**另一篇**文档的 4 行溢出卡住；反过来，文档改动也根本不需要 Pester。
- **RC4 判定重复（四处，三个"主人"）。** linter 同时跑在 `test.ps1 ps` 里、`ps1-tests` 的独立 step 里、
  `pr.yml` 里、`ci.yml` 里。CI 那个"独立 step"在失败时永远不执行，本地与 CI 对"哪一步红了"的回答不一致。
- **RC5 无分诊面 + 红是常态。** 失败要翻 12k 行日志才能定位到一行；而 8 天里同一个真实失败
  （map 漂移）连红 10 次没人处理——因为"红"在那段时间不携带信息。
- **RC6 本地防线不可靠。** 本地 `pwsh bin/test.ps1 ps` 会在
  `bin/maintenance/tests/maintenance.tests.ps1` → `python bin/quality/fix-links.py`（外链检查，无超时）
  上阻塞：实测 90 s 仍未结束，而 CI 日志里同一脚本每轮都报 `HTTP 403 (external)`（被测试容忍）。
  也就是说"推送前本地跑一遍"这条防线在离线/受限网络下走不完，不能作为文档门禁的唯一依靠。

## 方案

设计原则：**一个检查只回答一个问题；门禁在改动发生的地方运行；失败必须自带修复路径；
确定性失败不许重试。**

### 1. 把文档合规拆成独立的 check（RC1/RC3/RC4）

- 新增 `.github/workflows/skill-docs.yml`，check 名 `Skill Documents / skill-doc-lint`，
  只由 `skills/**/*.md`、`bin/skill-doc-lint.ps1`、`bin/tests/skill-doc-lint.tests.ps1` 及自身触发，
  另有 nightly cron（`41 3 * * *`）。
- `ps1-tests.yml` 只跑 Pester，路径过滤去掉 `skills/**`，命令改为
  `./bin/test.ps1 ps -SkipDocs`。
- **为什么必须换文件而不是同一个 workflow 里加 job**：workflow run 的结论是各 job 的**与**，
  文档违规照样会把 `PowerShell Tests` 整个标红——用户看到的现象不会变。
- **为什么用 `-SkipDocs` 而不是删掉本地检查**：本地 `pwsh bin/test.ps1 ps` 仍然一条命令覆盖两个门禁
  （默认跑 linter）；CI 用 `-SkipDocs` 让 PowerShell job 在原理上**不可能**因为文档而红。

### 2. 让 M6 与它自己的方法论一致（RC2）

`skill-doc-lint.ps1` 的 M6 改为分层预算：

| 文档 | 读取方式 | 规则 |
| --- | --- | --- |
| `SKILL.md` | 每次激活整篇载入 | 硬上限 **500**，超过即失败，消息直接给出"移走 N 行" |
| `decision` / `procedure` 参考 | 按需 | 500 为 **target**：501–700 只报 **advisory**（计数、不失败）；> 700 才失败（该拆分了） |
| `catalog` | 按需 | 不限（不变） |

- `-Strict` 把 advisory 升级为失败，留给发版门禁 / 集中还债。
- advisory 每次都打印（`checked 59 files, 0 issue(s), 1 advisory(ies)`），所以"超了但没到拆分线"
  仍然可见，只是不再把一次合入打成红色。
- 用新规则回放历史：24 次 M6 失败中 **17 次会变绿**，剩下 7 次是 `SKILL.md` 超限——
  那是启动上下文预算，本就该硬拦。

### 3. 失败自带修复路径（RC5）

- advisory / 失败消息都写清**差多少**（`target 500, +41`）与**下一步动作**
  （`move N line(s) into references/`、`split the overflow into a catalog doc`）。
- `skill-docs.yml` 用 `$GITHUB_STEP_SUMMARY` 给出一屏结论 + 复现命令，不必翻日志。
- **不加重试**：这些失败是确定性的（同一断言、同一文档，8 天连红 10 次都没有变化），
  自动重试只会把真实失败也埋掉。真实的那 10 次失败在任何方案下都应该红。

### 4. 守住新契约

- `bin/tests/skill-doc-lint.tests.ps1`：500 / 501 / 700 / 701 四个边界、`-Strict` 升级、
  catalog 无上限；fixture 现在带 tier 必需的模板小节，所以退出码只反映 M6，不再被 M4 掩盖。
- `bin/tests/test.ps1.tests.ps1`：`ps -SkipDocs` 被接受且列出为 skipped；`ps` 的退出码契约
  （advisory 不失败、> 700 失败）跟着断言。
- `docs/TESTING.md`、`skills/browser4-cli/methodology.md`（tier 表 + M6 条目）同步口径。

### 被否掉的替代方案

| 方案 | 否决理由 |
| --- | --- |
| 失败自动重试 | 全为确定性失败；重试等于给真实缺陷发免死金牌 |
| 把上限抬到 600/700 | 悬崖只是后移；历史行数（502/507/512/513/541/573）说明作者本来就在贴着线修剪 |
| `x-exempt: M6` 逐文件豁免 | 无上限增长且不可见，等于关掉规则 |
| 只把 linter 从 `ps` 里摘掉 | 归因修好了，但 `ps1-tests` 仍会在文档提交时红（它还在路径过滤里），噪音不减 |
| 只加 CI step 名称/日志 | 现象级修补；用户看到的仍然是 `PowerShell Tests` 红 |

## 改动清单

| 文件 | 改动 |
| --- | --- |
| `.github/workflows/skill-docs.yml` | 新增：文档合规独立 workflow + job summary |
| `.github/workflows/ps1-tests.yml` | 去掉文档路径与 lint step；`ps -SkipDocs`；注释记录归因问题 |
| `bin/skill-doc-lint.ps1` | M6 分层（SKILL.md 硬 500 / references advisory 500→700 硬顶）、`-Strict`、advisory 计数、可操作消息 |
| `bin/test.ps1` | `ps -SkipDocs`：本地默认仍覆盖文档门禁，CI 可精确归因 |
| `bin/tests/skill-doc-lint.tests.ps1` | 重写边界用例（含 conformant fixture 骨架 + 退出码断言） |
| `bin/tests/test.ps1.tests.ps1` | 硬顶 fixture 改 701；新增 advisory 与 `-SkipDocs` 断言 |
| `docs/TESTING.md` | 两个 workflow 的口径、本地命令、M6 分层说明 |
| `skills/browser4-cli/methodology.md` | tier 表 `Target Length` 与 M6 条目改为"target + 硬顶" |

## 验收标准

```powershell
pwsh bin/skill-doc-lint.ps1              # 0 issue(s), 1 advisory（browser-modes 541，advisory），exit 0
pwsh bin/skill-doc-lint.ps1 -Strict      # exit 1
pwsh bin/test.ps1 ps -SkipDocs           # 只跑 Pester，并打印 [SKIP] 行
pwsh bin/test.ps1 ps                     # 本地默认：Pester + 文档门禁
pwsh bin/tests/skill-doc-lint.tests.ps1  # 10/10
pwsh bin/tests/test.ps1.tests.ps1        # 0 content assertion failure
```

触发器验收：只改 `skills/**/*.md` → 只有 `Skill Documents` 运行（~15 s）；
只改 `*.ps1` → 只有 `PowerShell Tests` 运行。

## 后续

1. `references/browser-modes.md` 欠 41 行（541 vs target 500）。它现在是 advisory，不再拦合入；
   下次动这篇文档时顺手拆到 catalog 文档，或按 P1 的 overflow 流程处理。
2. `SKILL.md` 仍是唯一会被硬拦的文档（7/24 次历史失败）。若继续超限，按方法论处理：
   核心循环压到 250 行以内，否则拆 skill，而不是拉长上限。
3. 把 `Skill Documents` 设为 PR 必过检查，并在发版 workflow 里加一次
   `bin/skill-doc-lint.ps1 -Strict`——目前发布线是直接 push，文档门禁在合入前没有机会说话。
4. 给 `bin/quality/fix-links.py` 加超时 / `--offline` 开关（或被 maintenance 测试以超时方式调用）：
   它现在会让离线机器上的一次本地全量跑**卡死**（RC6），而它每轮 CI 都报 `HTTP 403`。
   修好后"本地一条命令"才真正可用。
