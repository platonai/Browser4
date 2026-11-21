# Coding agent：系统提示词与工具披露的 token 基线

> 目的：agent 的**固定每轮开销**（系统提示词 + 工具 spec）散落在手写的 Kotlin 字符串和
> `ToolSpec` 声明里，改动它不会让任何测试变红，却会让每一次运行、每一轮请求都多付钱。
> 这份文档把开销变成两个可比较的数字，并给出一个可复跑的测量脚本。
>
> 基线测量日期：2026-11-15，重测 2026-11-16（4.16.x，含 `b4.batch`）、2026-11-20（coding 提示词加入
> PowerShell 编码警告；见 §8）
> 测量脚本：`bin/measure-prompt-tokens.ps1`
> 回归测试：`bin/measure-prompt-tokens.tests.ps1`（`pwsh bin/test.ps1 ps` 会带上它）

---

## 1. 结论

字符数为源码实测；token 为换算值（英文散文 ≈ 4.0 字符/token，JSON schema ≈ 3.5）。

| 项 | 字符 | token @4.0 | token @3.5 |
|---|---:|---:|---:|
| **系统提示词，coding**（默认 `full` + 无外部访问） | 5,133 | 1,283 | 1,467 |
| 系统提示词，coding（受限预设，如 `write`）† | 6,063 | 1,516 | 1,732 |
| 系统提示词，coding（`full` + 允许外部访问） | 4,336 | 1,084 | 1,239 |
| 系统提示词，browsing（常驻 `quickstart.md`） | 22,216 | 5,554 | 6,347 |
| **工具披露，coding 任务**（39 个工具 + 2 个元工具） | 23,583 | 5,896 | 6,738 |
| 工具披露，browsing 任务（29 个工具 + 2 个元工具） | 17,564 | 4,391 | 5,018 |
| 全量 CLI 注册表，关闭披露（78 个工具） | 38,907 | 9,727 | 11,116 |
| **固定开销合计，coding**（提示词 + 工具） | **28,716** | **7,179** | **8,205** |

† 权限块按"受限预设分支的全部字面量"计，是上界：实际下发的是其中一个分支（依赖 `denyTools`、
`allowExternalAccess`、`unrestricted`）。

两条设计不变量：

1. **coding 的系统提示词不到 browsing 的 1/4**（5,133 vs 22,216 字符）——因为 `quickstart.md`
   （14,977 字符）只在浏览模式下常驻
   （`RobustBrowserAgent.cliAgentSystemPrompt()`：`if (!codingMode)`）。编码任务按需用
   `system.skillDoc(...)` 分页取。
2. **披露后比全量小 42%**——39 个工具 22.5k 字符，全量 78 个 38.9k 字符。

---

## 2. 系统提示词拆解（coding）

| 片段 | 字符 | 来源 |
|---|---:|---|
| `role`（coding 分支，含工作树与 shell 变体） | 2,607 | `CliAgentPrompt.codingRole()` |
| `hygiene`（coding 分支） | 1,727 | `CliAgentPrompt.hygiene()` |
| 工作区边界块（默认 `full` + `allowExternalAccess=false`） | 797 | `CliAgentPrompt.permissionsBlock()` |
| 受限预设的 `### Permissions` 块（`read`/`write` 等） | 1,727 | 同上 |
| `quickstart.md`（仅 browsing） | 14,977 | `skills/browser4-cli/references/quickstart.md` |

`role` 里的两处变体（脚本按 Windows + mirror 计入口径，`-Json` 的 `PromptBreakdown` 给出全部组合）：

| 变体 | 字符 | 组合后的 `role` 总长 |
|---|---:|---:|
| 工作树：mirror（无 `.git`） | 307 | 2,607（Windows + mirror） |
| 工作树：隔离 worktree（有 `.git`） | 709 | 3,009（Windows + worktree） |
| shell：Windows `cmd.exe` 说明 | 884 | 1,867（POSIX + mirror） |
| shell：POSIX `sh -c` 说明 | 144 | — |

---

## 3. 工具披露拆解（coding 任务）

初始集 = `CLI_CORE_DOMAINS`（`b4`、`system`、`memory` 全域）+ `CLI_CORE_CODING_METHODS`（25 个）
+ 强制契约工具 + 两个披露元工具。

| 来源 | 数量 | schema 字符 | 说明 |
|---|---:|---:|---|
| `coding.*`（白名单 25） | 25 | 11,861 | 详见 `RobustBrowserAgent.CLI_CORE_CODING_METHODS` |
| `b4.*` | 7 | 7,094 | `run/batch/status/wait/kill/version/help`；`b4.run` 的描述 3,345 字符，是单个工具里最长的 |
| `memory.*` | 4 | 2,055 | `search/read/note/forget` |
| `system.*` | 3 | 1,756 | `help/skillDoc/taskComplete` |
| `system.listTools` / `system.exposeTools` | 2 | 817 | 每轮都发，元工具成本 |
| **合计** | **39** | **23,583** | 平均 ≈ 605 字符/工具（≈ 151–173 token）；含两个元工具共 41 条 |

单个工具的最大三项：`b4.run`（描述 3,345 字符，含 stdin 载荷与批处理指路）、`b4.batch`
（1,699）、`coding.devTask`（1,161）。`b4` 一个域占了披露总量的 30%——它是"一个工具描述
翻倍"的典型，见 §8。

对照：coding 全域 58 个 spec = 27,462 字符；加上 `b4/system/memory/skill` 的全量 CLI 注册表
= 38,907 字符 / 78 个工具。

---

## 4. 口径与公式

### 4.1 测什么

- **只测源码，不启动后端**：脚本解析 `toolSpec["x"] = ToolSpec(...)` 块与提示词的 Kotlin
  原始字符串。好处是可在 CI/离线跑、结果与代码版本一一对应。
- **不测 tab 域**：`tab.*`（约 131 个方法）不在 CLI 引擎的 `CLI_ENGINE_DOMAINS`
  （`coding/b4/system/memory/skill`）内，不进 agent 的披露池。MCP 面对外目录的规模另算。

### 4.2 单个工具的 schema 字符

按 OpenAI function-calling 形状估算：

```
105                                   # {"type":"function","function":{...}} 固定骨架
+ len(name)
+ len(description)                     # 转换器把 help 也拼进 description
+ (9 + len(returnType))                # "Returns: X"，Unit 时不计
+ Σ(len(argName) + 24)                 # properties: {"a":{"type":"string"}}
+ 16 + Σ(len(required) + 3)            # required 数组
```

**参数描述不计入**：`ToolSpecificationConverter.buildArgDescription()` 的结果被丢弃
（`val elementWithDesc = if (desc != null) { element } else element`，两个分支相同），所以
`listDir(limit="最多 50 条")` 这类说明不会到达模型——模型只能通过 `system.help` 看到。
这让 schema 便宜约 20%，代价是参数语义缺失（见 §7 待办）。

### 4.3 token 换算

`chars / 4.0`（英文散文）与 `chars / 3.5`（JSON 更密）。这是估算：真实 tokenizer 对英文
约 4.0–4.3，对 JSON/标识符约 3.4–3.7，中英混排更低。量级可靠，绝对值 ±10–15%。

### 4.4 权威数字在哪

- **真实用量**：完成行 / `GET /api/commands/{id}/stats` 里 provider 回传的 usage；
- **确切请求体**：`AgentConfig.logInferenceToFile = true`（默认）经 `onBeforeGenerate`
  把每次请求的确切消息列表与 tool specs 落盘；
- `coding.tokenStats()` **不是**这个口径：它只统计 coding 工具的入参/出参，不含系统提示词与 schema。

---

## 5. 复跑与回归

```bash
# 人读表格
pwsh bin/measure-prompt-tokens.ps1

# 机器可读（CI、对比脚本）
pwsh bin/measure-prompt-tokens.ps1 -Json

# 与文档基线比对，漂移超过 10% 时 exit 1
pwsh bin/measure-prompt-tokens.ps1 -Check
pwsh bin/measure-prompt-tokens.ps1 -Check -Tolerance 0.05

# 回归测试（形状 + 不变量 + 漂移检测器的自测）
pwsh bin/measure-prompt-tokens.tests.ps1
```

退出码：`0` 正常（`-Check` 时表示在容忍范围内）；`1` 解析失败或漂移超限。

**什么时候更新基线**：改动是**有意**的（新增工具、补写帮助文本、调整提示词策略）→ 同时改
脚本里的 `$Baseline` 与本文 §1 的表格，并在 §8 记一行。改动是**无意**的（顺手加了两段说明）
→ 先看能否精简，再决定是否接受。

建议阈值：默认 10%（能抓住"一个工具描述翻倍"这类漂移）；对提示词敏感的分支可用 5%。

---

## 6. 已知偏差与陷阱

| 现象 | 原因 | 处理 |
|---|---|---|
| 脚本数字与真机相差 ±15% | tokenizer 差异 | 只看量级与趋势；精确值用 §4.4 的落盘日志 |
| 文本模式（`TEXT`/`CHAT`）数字不适用 | 该模式把 spec 渲染成 `domain.method(arg: Type)` 单行，约 1/4 体积，但无 JSON Schema | 需要时用 `ToolCallSpecificationRenderer.render()` 单独量 |
| `-Check` 对提示词块不敏感 | 权限块的文本随预设分支变化，脚本统计"受限预设"这一支的全部字面量（上界） | 视为近似值；精确差异用 `-Json` 的 `PromptBreakdown` 对比 |
| 参数描述没有出现在数字里 | 转换器丢弃（§4.2） | 若将来修好，`-Check` 会因 schema 变大而报警，届时更新基线 |

---

## 7. 这些数字意味着什么

- 一次 coding 请求的固定部分是 **≈6.8–7.7k token**；prompt cache 命中（本仓库实测 90%+，
  见 `RoundTiming.cachedInTokens`）后，这部分几乎免费。**成本主要是输出 token 与轮数**。
- 实测一次运行报告形如 `9 rounds · 64 tools · 182.4k tokens`，即每轮约 20k：固定 6k + 历史
  + 工具结果。**工具结果**（`ToolOutcome` 的 1.6–8k 预算）比工具 schema 更能撑爆 context。
- 优化杠杆排序：**减少轮数 / 缩小工具结果 > 压缩工具 schema > 压缩系统提示词**。
- 待办（顺带记录）：`ToolSpecificationConverter` 丢弃参数描述是死代码，修好后 schema 会
  变大约 15–20%，但模型能直接看到参数语义——改之前先看这份基线。

---

## 8. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-15 | 建立基线：coding 提示词 4,480 字符、工具披露 19,591 字符（38 工具）、固定开销 ≈6.0k token；新增 `bin/measure-prompt-tokens.ps1` + 回归测试 |
| 2026-11-16 | 重测：新增 `b4.batch` 使 `b4.*` 从 3,609 → 6,058 字符（6 → 7 个工具），披露 19,591 → 22,547（39 工具，+15%）、browsing 13,572 → 16,528（+22%），固定开销 ≈6.8k token；提示词两块不变。`b4.run` 描述同期随 stdin 载荷文档涨到 2,309 字符（单工具最大）。同步更新脚本 `$Baseline`、回归测试断言与本文 §1/§3/§7 |
| 2026-11-20 | 重测：coding 提示词的 Windows shell 分支加入 PowerShell 编码警告（BOM-less `.ps1` 被 5.1 按 ANSI 读，见 `48e9b08d02`），`role` 1,980 → 2,607（Windows 分支 257 → 884），coding 提示词 4,480 → 5,133（+14.6%）、受限预设 5,436 → 6,063（+11.5%），固定开销 27,027 → 27,680（+2.4%）；browsing 提示词 22,190 → 22,216，工具披露不变。这次增长让 `-Check` 的 10% 阈值报警（4.16.x nightly 的 ps1-tests 因此变红），确认是有意加的行为后同步 `$Baseline` 与本文 §1/§2 |
| 2026-11-22 | 重测：4.16.x 合并进 4.17.x 时，`b4.run` 的描述两侧各加了一段——4.16.x 的"一次 `eval` 不能超过 30s"与 4.17.x 的 BATCH MODE 指路——合并后两段都在，描述 2,309 → 3,345（+45%），`b4.*` 6,058 → 7,094，披露 coding 22,547 → 23,583（+4.6%）、browsing 16,528 → 17,564（+6.3%），固定开销 27,680 → 28,716（+3.7%）。仍在 10% 阈值内，但按"有意增长即重录"的约定同步 `$Baseline` 与本文 §1/§3/§7 |
