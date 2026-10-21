# Browser4 Coding 模块 Token 效率评估报告

> 生成日期：2026-08-16
> 范围：`browser4-coding` + `browser4-agentic` 中的 coding 工具执行器
> 配套能力：本次新增的 `coding.tokenStats` / `coding.estimateTokens` 工具 + `TokenStats.kt`
> 评估脚本：`docs-dev/copilot/scripts/token_efficiency_analysis.py`

## 一、结论速览

| 维度 | 现状 | 评级 |
|---|---|---|
| 工具定义静态成本（KOTLIN 渲染，默认 prompt 口径） | coding 域 ≈ 1,504 tok | ✅ 良好 |
| 工具定义静态成本（JSON/原生 tool-calling 口径，含 description） | coding 域 ≈ 9,003 tok | ⚠️ 偏高 |
| `coding.read` 单次输出上限 | 5 MB ≈ 1.5M tok，**无截断** | 🔴 高风险 |
| `coding.readLines` | `endLine=-1` 时整文件，无截断 | 🔴 高风险 |
| `coding.shell` 输出 | stdout+stderr 各 200K 字符 ≈ 114K tok | 🟡 已有上限 |
| `coding.grep` / `coding.glob` | 200 条 ×~260 字符 ≈ 17K / 5K tok | ✅ 良好 |
| 典型 .kt 文件 read 成本 | 中位 ≈ 1,116 tok；p90 ≈ 4,578 tok | ✅ 良好 |
| description 总量 | 8,259 字符 ≈ 2,359 tok（仅 JSON 口径付出） | ✅ 可接受 |

**核心风险**：`read` / `readLines` / `diff` 没有 token/字符级截断，5 MB 上限远超任何主流上下文窗口（128K–256K）。一次不慎的大文件 read 即可撑爆上下文，使后续对话全部失效。这是 token 效率的最大隐患。

## 二、新增能力概览

`browser4-coding/.../TokenStats.kt`（零依赖）提供：

- `TokenEstimator.estimateTokens(text)`：BPE 风格启发式估算，camelCase 拆词 + CJK 按字符 + 数字按位，±25% 精度，仅供成本核算，不可用于计费。
- `CodingTokenStats`：线程安全（`ConcurrentHashMap` + `AtomicLong`）按方法聚合 `calls / errors / inChars / inTokens / outChars / outTokens / maxOutChars / totalMillis`，固定内存（每方法一条目），`report()` 输出按输出 token 降序的表格。

`CodingToolExecutor` 接入：

- 重写 `callFunctionOn(tc, receiver)`，对每个非元工具调用记录输入（序列化参数）与输出（结果文本）的 token、错误与耗时；元工具 `tokenStats` / `estimateTokens` 不计入，避免自我膨胀。
- 新增工具：
  - `coding.tokenStats(reset=false)` — 输出 per-method 统计表；`reset=true` 先报告再清零。
  - `coding.estimateTokens(text)` — 估算任意文本的 token 数，发送给模型前自检。

## 三、静态成本：工具定义（每轮注入 prompt）

| 口径 | 字符 | 估算 token |
|---|---|---|
| 硬编码 builtin（tab/browser/fs/agent/system） | 2,461 | 1,072 |
| coding 域 KOTLIN 渲染（默认；仅签名） | 3,368 | 1,504 |
| coding 域 JSON 渲染（签名 + description） | 18,593 | 9,003 |

说明：默认 prompt 走 `ToolCallSpecificationRenderer.render()` 的 KOTLIN 路径，**不含 description**（`renderSpec` 仅输出 `domain.method(args): ReturnType`）。JSON 路径（`renderJson`）与 LangChain4j 原生 tool-calling 路径会带上 description，coding 域 description 合计 8,259 字符（≈2,359 tok），其中 `scaffoldFromExample`(811)、`devTask`(662)、`scaffold`(557)、`scaffoldFlow`(514) 最重。

Top-10 工具定义成本（JSON 口径）：

| method | 签名 tok | 描述 tok | 描述字符 |
|---|---|---|---|
| scaffoldFromExample | 71 | 307 | 811 |
| scaffold | 154 | 247 | 557 |
| devTask | 45 | 253 | 662 |
| scaffoldFlow | 98 | 215 | 514 |
| mvnBuild | 48 | 144 | 338 |
| moduleGraph | 19 | 146 | 376 |
| ktReferences | 32 | 139 | 360 |
| protect | 25 | 139 | 327 |
| validate | 23 | 136 | 321 |
| ktInheritance | 27 | 115 | 304 |

## 四、动态成本：单次调用输出上限（代码常量）

| 工具 | cap 字符 | cap token ≈ | 来源 |
|---|---|---|---|
| read | 5,242,880 | 1,497,965 | `MAX_READ_SIZE_BYTES = 5 MB`，**无截断** |
| readLines | 5,242,880 | 1,497,965 | `endLine=-1` 返回整文件，无截断 |
| diff | 5,242,880 | 1,497,965 | 整文件 unified diff |
| shell | 400,000 | 114,285 | `MAX_OUTPUT_CHARS = 200_000` ×2（stdout+stderr） |
| scaffoldFromExample(dir) | ~400,000 | 114,285 | 多文件骨架拼接 |
| devTask(verify) | ~50,000 | 16,250 | plan + 编译诊断 + 一致性 |
| grep | 52,000 | 16,900 | 200 条 ×~260 字符 |
| glob | 16,000 | 5,200 | 200 条路径 |
| listDir | 16,000 | 5,200 | `maxDepth>=2` 大树时无截断 |

## 五、典型成本：`coding.read` 实测分布（仓库 1,897 个文本文件）

| 维度 | 中位 | p90 | max |
|---|---|---|---|
| 全部文本文件 | 1,477 tok | 6,938 tok | 306,822 tok |
| 仅 .kt 文件 | 1,116 tok | 4,578 tok | 29,006 tok |

最大文件（read 一次即爆上下文）：`browser4-tests/.../react-dom_18.1.0.js`（306,822 tok）、`cli/browser4-cli/src/main.rs`（247,392 tok）。

结论：typical 路径（小/中文件）效率良好；但 read 对大文件无防护，一个 `coding.read(path="main.rs")` 即可压垮 128K 窗口。

## 六、优化建议（按 ROI 排序）

1. **🔴 给 `read` 加 token/字符截断（最高 ROI）**
   - 现状 5 MB 无截断是系统性隐患。建议新增 `maxChars`（默认 20,000–50,000）与 `head+tail` 折叠策略（前 N 行 + `... (truncated K lines) ...` + 后 M 行），并返回截断标记。
   - 同步约束 `readLines`（`endLine=-1` 时不应等于"返回全部"，应与 read 共享上限）与 `diff`。
   - 效益：单次调用上限从 1.5M tok 降至 ~12K tok，杜绝上下文爆裂。

2. **🟡 `shell` 输出按 token 折叠**
   - 现有 200K 字符 ×2 仍达 114K tok。建议保留字符上限，但额外提供"头部 + 尾部 + 中段省略"折叠，并在超阈值时只回尾部（错误信息多在尾部）。

3. **🟡 `scaffoldFromExample(dir)` 拼接前提示文件清单**
   - 目录骨架可能拼出 10 万+ token。建议先生成清单（文件名 + 各自行数），由 agent 决定取哪些；或单文件分次返回。

4. **🟢 description 精简（JSON/原生口径）**
   - coding 域 description 合计 2,359 tok。KOTLIN 默认口径下不付出，无需改动；若启用 JSON/原生 tool-calling，可把 `scaffoldFromExample`/`devTask`/`scaffold` 的长描述拆为"一句话 + 详见 skill"。

5. **🟢 用 `coding.tokenStats` 闭环**
   - 建议在长任务收尾调用 `coding.tokenStats()` 复盘高耗工具，定位低效调用模式（如反复 read 同一大文件），形成"统计→发现→加截断"的迭代。

## 七、验证说明

本机 Kotlin daemon/Kapt 受 RMI 故障阻塞（项目记忆已知），故采用降级验证：

- ✅ `TokenStats.kt` 独立编译通过；`browser4-coding` 全部 18 个主源文件一起编译通过（仅 1 个既有 warning）。
- ✅ `TokenStatsTest.kt`（9 个用例）**编译并运行通过**（JUnit Platform 6，真实执行，9/9 绿）。
- ✅ `CodingToolExecutor.kt` 经 K2 编译器针对模块真实类完成类型检查。
- ✅ `CodingToolExecutorTokenStatsTest.kt` 针对真实 API + mockk + junit 完成编译（类型检查通过）。
- ⚠️ 执行器测试未在 JDK 25 运行时下实跑（仓库 stale classes 为 class file v69，shell 仅 JDK 17）。类型层面已验证。
