# Issues: `tool call` 把 PERMISSION_DENIED 当作成功返回（exit 0，`--fail-on-error` 不触发）

> **Source:** 2026-11-20 01:41–01:44 本机实测（permission-e2e Tier A，live backend 4.16.9-SNAPSHOT，`browser4.agent.sandbox=off`）+ 源码核对
> **Date:** 20261120-0145 · **Mode:** dev
> **状态:** **已修复 2026-11-20 12:00**（I1/I2/I3 全部关闭；回归测试见 §5）
> **代码基线:** `4.16.x` @ `6df6fd520e`
> **报告人:** 本会话 agent（实现 `browser4-tests/permission-e2e` 时发现）

---

## 0. 结论摘要

| 编号 | 问题 | 是产品缺陷吗 | 置信度 | 建议决策 |
|---|---|---|---|---|
| I1 | 访问被拒时工具返回的是**文本**（`Error: PERMISSION_DENIED \| code=…`），MCP 信封与进程退出码仍是成功 | 是（可观测性/契约） | 高 | ACCEPT |
| I2 | `tool call --fail-on-error` 只认 `success:false` / 非空 `error`，看不到拒绝文本 | 是（同一根的第二个出口） | 高 | ACCEPT |
| I3 | 任何"只看退出码"的调用方（脚本、CI、其它 agent、`real-world-scenarios` runner）都会把拒绝当成功 | 是（影响面） | 高 | ACCEPT |

**一句话**：`PermissionAudit.denied()` 返回的是字符串而不是异常，所以一次被拒绝的写/读/shell 调用在 CLI 层表现为 `exit 0 + 正常输出`，只有把那段文本读进上下文的人才看得出被拒——机器调用方看不见。

---

## 0.1 修复（2026-11-20）

| 层 | 修改 | 位置 |
|---|---|---|
| MCP 信封 | 分发后识别**行首** `PERMISSION_DENIED \| code=…`（容忍 `coding.fs` 的 `Error: ` 前缀），把响应改成 `isError:true` + `errorCode=PERMISSION_DENIED` + `structuredContent.permissionCode=<PATH_DENIED/…>`；**文本逐字不变**。`command_batch` 的 `op:"tool"` 步骤同步 `ok=false` | `browser4-rest/.../MCPToolController.kt`（`withRefusalEnvelope`）、`ToolErrorCode.PERMISSION_DENIED`（403，非重试） |
| CLI 兜底 | `--fail-on-error` 除 `success:false` / `error` 外，还识别 `isError:true` 与**文本行首**拒绝（覆盖尚未升级的后端），stderr 打印 `the access policy refused the call (code=…)` | `cli/browser4-cli/src/main.rs`（`tool_call_failure_summary` / `tool_call_refusal_reason`） |
| 指标/日志 | 信封的 `isError` 决定成功与否，权限码不再被当成"无 ToolErrorCode ⇒ 成功" | `MCPToolController.callTool` 的 `ToolMetrics.recordToolCall` |

`agent run` 的循环不受影响：`ToolOutcome.isRefusal` 早已把拒绝渲染成 `[fail]`，信封只是让**机器调用方**也看得见。

---

## 1. 复现（最小）

```powershell
# 一次必然被拒的写（工作区外）
browser4-cli tool call coding_write --json '{"path":"D:\\Users\\pereg\\AppData\\Local\\Temp\\x.txt","content":"x"}' --fail-on-error
```

实测输出（2026-11-20 01:41）：

```
Error: PERMISSION_DENIED | code=PATH_DENIED | tool=coding.fs | detail=path 'D:\...\x.txt' is outside the workspace ...
```

随后：

```powershell
$LASTEXITCODE      # → 0
```

`--fail-on-error` 已传入，仍未以非零退出。同一形状出现在 `coding_shell`（`code=COMMAND_PATH_DENIED`，凭证路径）与 `coding_write`（`code=PROTECTED_PATH`，受保护文件）上——见 `browser4-tests/permission-e2e/cases.json` 的 `A03` / `A07` / `A08`。

---

## 2. 根因（代码定位）

| 层 | 位置 | 行为 |
|---|---|---|
| 拒绝构造 | `browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/PermissionAudit.kt:148`（`denied()`） | 返回**格式化字符串**，并计数/记日志；不抛异常（KDoc 明确说"returned, never thrown"） |
| 工具返回 | `browser4-agentic/.../tools/builtin/CodingToolExecutor.kt`（各 `when` 分支） | 该字符串作为普通工具结果返回 |
| MCP 信封 | `browser4-rest/.../MCPToolController` | `success` 由异常决定，字符串结果视为成功 |
| CLI 退出码 | `cli/browser4-cli/src/main.rs:26837` / `26872` / `26883` | `post_tool_call` 只在 `success == false` 或 `error` 非空时失败 |

Agent 循环本身不受影响：`RobustBrowserAgent.kt:1603` 用 `isFailedToolResult(resultText)` 判 `ok`，所以 `agent run --ndjson` 的 `tool.result.ok` 是 `false`（本条已在 permission-e2e 中断言）。**缺口只在 `tool call` 这一层。**

---

## 3. 影响

- 用 `tool call` 写脚本/CI 的人无法分辨"成功"与"被拒"，除非自己 grep 文本；
- `browser4-tests/real-world-scenarios/scripts/*.ps1` 这类 runner 若用退出码判断工具成功，会静默放过拒绝；
- 第三方 agent / MCP 客户端（不读 `detail` 只看 status）会重试成功路径；
- 与文档承诺不符：`docs/agent-permissions.md` 把拒绝描述为**结构化**结果（`PERMISSION_DENIED | code=…`），但结构只存在于文本里，信封层没有字段。

---

## 4. 建议（按代价从低到高）

1. **信封层（推荐）**：`MCPToolController` 在工具结果是 `PERMISSION_DENIED | code=…` 前缀时把信封标成 `success:false` 并带 `errorCode=<code>`。一处修改，所有机器调用方立即受益；`agent run` 已有的 `isFailedToolResult` 不受影响。
2. **CLI 层（兜底）**：`--fail-on-error` 时，若输出行首是 `PERMISSION_DENIED` / `Error:`，以非零退出（与 harness 现在的判定一致）。
3. **不要**让 `denied()` 改抛异常——那会打断 agent 循环"一步失败不等于任务失败"的契约。

---

## 5. 测试

- `permission-e2e` 已把"拒绝必须被识别"编码成断言：A03/A07/A08 的 `ok=false` 由**行首标记**判定（`run-permission-e2e.ps1` 的 `$textRefused`）。
- 修复后补的回归测试：
  - `MCPToolControllerTest.permissionRefusalBecomesAFailedEnvelope`（信封 `isError`/`errorCode`/`permissionCode`，文本逐字不变）、
    `refusalMentionIsNotARefusal`（报告只是提到 prefix）、`refusalParserIsStrict`；
  - CLI `tool_call_permission_refusal_is_a_failure`（`success:true` + 文本拒绝、MCP 信封、报告误报三种形状）。

---

## 6. 修复记录

| 日期 | 变更 |
|---|---|
| 2026-11-20 12:00 | 信封 + CLI 兜底落地；`docs/agent-permissions.md` §8 增加"传输层信封"说明；`cli/browser4-cli/README.md`、`skills/browser4-cli/SKILL.md` 的 `--fail-on-error` 措辞更新 |
