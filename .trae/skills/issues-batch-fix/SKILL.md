---
name: issues-batch-fix
description: Fix Browser4 test-issue reports under coworker/tasks/issues/draft via the locate-fix-verify-docs loop. Use when the user says 继续修复 #N 报告中的问题 or asks to fix the next dated .issues.md report. Not for unrelated bug fixes.
---

# Issues 报告批量修复（Browser4 专属）

按批次修复 `coworker/tasks/issues/draft/` 下的测试问题报告。核心循环：读报告 → 静态定位 → 双端修复 → 定向验证 → 文档联动 → 批次总结。全程中文沟通。

## 1. 定位报告

- 报告目录：`coworker/tasks/issues/draft/`，命名 `YYYYMMDD-HHMMSS-<slug>.issues.md`（同名 `.full.md` 为长版参考，一般不必读）。
- 用户说"继续修复 #N"：先用 §6 进度表把 #N 映射到文件；映射不明或表未覆盖时，按文件名时间顺序列出未修报告候选，让用户确认后再动手。
- 编号可能跳过已在会话空档修过的报告。判断某候选是否已修的快速探针：取报告中一个标志性 issue 的代码指针（如某函数的回退逻辑），Grep 当前代码看修复是否已存在。探针确认已修后，把该报告移入 §6 已完成清单。
- 用户说"下一个报告"：取时间顺序下一个未修报告，先报出文件名与 issue 数量。
- 批次范围以用户指定为准；历史批次（如 20260916/20/23-*）默认不在范围内，除非用户点名。

## 2. 单 Issue 修复循环

1. 建 todo：id 用 `#N-<issue序号>`，逐个 issue 推进，完成即标 completed。
2. 静态定位：Grep/Read 双端排查 —— CLI（`cli/browser4-cli/src/`，Rust）与后端（`browser4-agentic/`、`browser4-rest/`、`browser4-core/`，Kotlin）。先找根因，再想修法。
3. 确认修复点是否在本仓：部分行为由外部 artifact 实现（如 pulsar-browser 的 AX 渲染器，版本锁在 `browser4-dependencies/pom.xml`），本仓不可改。此时用组合方案：CLI 侧文本后处理、executor 侧预检（大声失败）、文档如实化。
4. 修复原则：大声失败 —— 错误信息直达 CLI/用户，不静默吞错、不被重试机制包装；重试幂等；不回退上游错误语义；拿不准的边界显式报错而非静默 no-op。
5. 测试：主路径 + 边界各一。Rust 测试放 `main.rs` 底部 `mod tests`；Kotlin 测试放对应 `*Test.kt`（camelCase 方法名 + `@DisplayName`，禁 backtick 命名）。
6. 文档联动：公开行为变化必须同步 `skills/browser4-cli/SKILL.md`、`skills/browser4-cli/references/*.md`、`cli/browser4-cli/src/help.rs`、`tips.rs`（见 AGENTS.md 的 Documentation Update Rule）。

## 3. 验证命令

PowerShell 环境，注意引号与 `-am` 陷阱：

- Rust 单测：`cd cli/browser4-cli; cargo test --bin browser4-cli`
- Kotlin 单测：`.\mvnw.cmd test -pl <module> -Dtest=<TestClass> "-Dsurefire.failIfNoSpecifiedTests=false"`
  - 不要加 `-am`：上游模块会报 No tests matching pattern
  - `-D` 参数必须加引号：PowerShell 会把点号参数拆开
- 跨模块编译确认：`.\mvnw.cmd -q -D"skipTests"`
- e2e 仅编译确认：`cargo test --test e2e --no-run`；真跑需要后端在线（默认端口 18182）
- 全绿才算完成；测试失败先定位根因，禁止 sleep 重试循环。

## 4. 已知坑位（本仓实测教训）

- 管道提前关闭：裸 `println!` 会 EPIPE panic → 用 `print_stdout_line`。
- 退出码语义（如 grep 零匹配 exit 1）：用哨兵错误字符串向上传播 + 主错误处理器特判静默返回对应 ExitCode。
- 工具参数可能存 JSON 整数或字符串：容错读取（`as_i64` 优先，回退 `as_str().parse()`）。
- 字符串预处理的 trim 顺序：先 `trim_start()` 空白，再 `trim_start_matches(前缀符号)`，再 trim —— 顺序反了缩进行匹配不到。
- 需要真实浏览器 e2e 才能验证的 issue：有意暂缓，向用户说明理由与残余风险，不硬修。
- 下载/输出目录用绝对路径（如 `~/.browser4/downloads`），避免文件落进 bundle build 树。
- 修一个 `.ps1` 时检查同目录兄弟脚本是否有同样问题。

## 5. 批次收尾

1. 所有 todo 标 completed 后，输出总结表：Issue 序号 → 级别 → 根因 → 修复方案 → 涉及文件 → 验证结果。
2. 更新 §6 进度表（把报告移入"已完成"）。
3. 询问是否继续下一个报告。

## 6. 批次进度（每完成一个报告后更新本节）

已完成（截至 2026-10-05，20261004 批次 18 个中的 17 个）：

- `20261004-150935-agent-extraction`
- `20261004-151355-agent-status-result-tracking`
- `20261004-152127-attach-failure-boundaries`
- `20261004-153016-attach-remote-debug`
- `20261004-160851-crawl-link-discovery`（其中 2 项需真实浏览器 e2e，有意暂缓）
- `#24 20261004-161445-html-snapshot-extraction`
- `#25 20261004-161908-headed-window-visibility`
- `#26 20261004-162205-htmlsnapshot-inspect-discovery`
- `#27 20261004-163201-loop-monitoring`
- `#28 20261004-164400-plugin-captcha-detection`
- `#29 20261004-165144-plugin-image-detection-download`
- `20261004-165857-plugin-markdown-conversion`（#29/#30 之间空档修过；经 resolve_plugin_method 探针验证）
- `20261004-171100-plugin-media-video-detection`（同批空档修过；经 ProbeResult: DirectValue 探针验证）
- `#30 20261004-172853-plugin-pptx-generation`
- `#31 20261004-173150-session-management`
- `#32 20261004-173914-snapshot-mastery`
- `#33 20261004-174324-tab-management`（Issue 1/2 空档已修并补回归测试；本轮补 Issue 3 友好错误与 Issue 2 关闭后当前 tab 提示）

待修候选：

- `#34 20261004-174741-x-sql-query-methods`
