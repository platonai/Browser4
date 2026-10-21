# Coworker 1ready 批处理 — 工作存档（2026-09-04）

> 用途：会话重启后的恢复依据。所有代码改动**未提交**，留在工作树（暂存+未暂存混合，约 218 项 git 改动）。重启后直接继续即可，无需重做。

## 一、任务与结果总览

**目标**：处理 `coworker/tasks/main/1ready/` 下全部 26 个 issue/任务文件 → 已全部完成并路由。
- 19 个 → `coworker/tasks/main/3done/2026/0904/`
- 7 个 → `coworker/tasks/main/3complete/2026/0904/`
- 1ready / 2working 均已清零。

（注：coworker 组织器会自动把 3complete 条目归档到 3done，属正常流转。）

## 二、26 个文件处置明细

### 路由到 3done/2026/0904（19 个）
| 文件 | 处置 |
|---|---|
| 20260814-124330-bulk-scale-routing | 8 issue 全 DEFER（记录类） |
| 20260814-132326-crawl-link-options | 8 issue 全 DEFER |
| 20260814-133326-crawl-sql-formats | 5 issue 全 DEFER |
| 20260815-attach-cdp-false-success | 文件自述 I1-I3/I5 已修复（代码核验属实：PulsarSessionManager /json/version 探测、CdpEndpointVerificationTest、daemon.rs 端口解析），I4 DEFER |
| 20260902-204416-swarm-parallel-scraping | 8 issue 全 DEFER |
| 20260814-140901-form-filling | I1(捕获后端B1)、I2(后端B3+CLI帮助)、I3(CLI消息)、I4(select=CLI)、I5(后端B3+CLI提示)、I6(snapshot -i 噪音→外置依赖，见遗留1)、I7(CLI编号)、I8 WONTFIX |
| 20260814-141423-javascript-evaluation | I1(eval envelope 原已修+补测)、I2/I3(CLI)、I4(goto tip) |
| 20260814-142038-storage-state-management | I1-I4(CLI cookie 家族)、I5(文档 E1) |
| 20260902-201601-form-filling | I1/I2(select)、I3(config 免快照)、I4(★图例)、I5(snapshot 提示) — CLI |
| 20260902-202249-javascript-evaluation | I1(包装脚本A)、I2(envelope)、I3(后端B3)、I4(CLI)、I5(fixture 我改) |
| 20260902-203534-storage-state-management | I1(cookie --path，见遗留2)、I2(包装脚本)、I3 DUPLICATE、I4(CLI)、I5(CLI)、I6(CLI) |
| 20260903-105854/112600/122605 readme-update ×2 目标 + refresh-mvn-readme-staleness（共 7 个 README 任务） | .mvn/README.md 核实已达标（版本信号 3.3.4/3.9.16 为 maven-wrapper 工具链锁定值，非误报可忽略项已记录）；ec/README.md 修复 README-AI.md→AGENTS.md 引用 |
| test-x-sql-extraction-functions | I1/I2/I3(文档 E1，I3 按批注"create issue, do not fix")、I4/I5/I7 DEFER、I6(inspect 已修+B1 核验) |
| triage-ecommerce-workflow-issues | I1(B1)、I2(A 包装守卫+E1 文档)、I3 DEFER、I4(B3+E1)、I5(CLI)、I6(CLI) |

### 路由到 3complete/2026/0904（7 个，本轮最后一批）
| 文件 | 处置 |
|---|---|
| 20260814-072407-bulk-scale-routing | I1(后端 crawl 深度)、I2(swarm UX 提示类, 主要 DEFER 化处理)、I3(CLI+E1)、I4(CLI 会话提示)、I5(文档 loop) |
| 20260814-073841-extraction-method-routing | I1/I6/I7(文档)、I2(CLI get 退出码/消息)、I3/I5(CLI extract)、I4(CLI snapshot.rs 提示+文档) |
| 20260902-195619-crawl-sql-formats | I1(包装脚本)、I2(state.rs 表格, 先前暂存)、I3(CLI 错误本地化)、I4(CLI 进度去重) |
| 20260902-200836-extraction-method-routing | I1(B1 活 DOM 捕获)、I2(B1 错误映射)、I3 DEFER、I4/I5/I6(CLI/文档) |
| 20260902-203534-storage-state-management | 见上表同文件 |
| fix-ecommerce-workflow-product-issues | I1(B1)、I2(B3 extract 信封)、I3(snapshot -i 噪音, 见遗留1)、I4(CLI+文档)、I5(CLI grep+文档)、I6(CLI 警告措辞)、I7(CLI doctor) |
| fix-git-bash-path-mangling | I1(A+文档)、I2(后端 crawl 并发/标题)、I3(CLI+文档)、I4(CLI+文档)、I5(C3 已修+补全)、I6(文档)、I7(C3 已修)、I8/I10(CLI swarm 消息)、I9(后端+CLI) |

## 三、改动文件清单（按簇）

- **CLI Rust**：`cli/browser4-cli/src/main.rs`（约 +1500 行，eval/cookie/select/get/extract/快照提示/no_snapshot 等 + 约 30 新单测）、`commands.rs`、`help.rs`、`daemon.rs`（C3 补平台化重建提示 + doc comment）、`tests/e2e/scenarios/browser.rs`（cookie-get `--full` 断言，我改）、`tests/e2e/scenarios/mock_server.rs`（C1 新增 envelope 断言）
- **后端 Kotlin**：`browser4-rest/.../agent/tool/HTMLSnapshotToolExecutor.kt`（活 DOM 捕获 + 8 新测）、`rest/mcp/controller/MCPToolController.kt`（错误映射/帮助文本 + 3 新测）、`rest/api/service/CrawlService.kt`（深度/去重/排序）、`browser4-agentic/.../BrowserTabToolExecutor.kt`（ref 报错/get 统一解析 + 11 新测）、`AbstractToolExecutor.kt`/`InferenceEngine.kt`/`PerceptiveAgent.kt`/agents（extract 信封净化 + ExtractResultEnvelopeTest 5 测）、`browser4-core/browser4-browser/.../Browser4WebDriver.kt`（cookie 规范化校验 + 4 新测）
- **包装脚本**：`b4w.ps1`（exit 传播）、`b4w.sh`（pwsh -File + MSYS2_ARG_CONV_EXCL）、`bin/test.ps1`（冒烟检查）
- **文档**：`skills/browser4-cli/SKILL.md` + `references/*.md`（8 个文件更新 + E1 保留 quickstart/decision-trees/quick-patterns 三件**未跟踪**新文件）、`browser4-tests/.../ec/README.md`（AGENTS.md 引用）
- **fixture**：`browser4-tests/pulsar-tests-common/src/main/resources/static/generated/interactive-1.html`（+form/3 链接/2 图片）
- **清理**：删除原后端代理解包 jar 遗留的根目录垃圾（BOOT-INF/、ai/、keytab.txt 等 ~40 项；保留 E1 编辑过的 3 个引用文档）

## 四、验证结果（均在本会话实际运行）

- `cargo test --bin browser4-cli`：1147 passed / 0 failed（C1 最终运行）
- 定向 extract 测试（我补判空）：5/5 passed
- browser4-rest：HTMLSnapshotToolExecutorTest+InspectDocumentTest+MCPToolControllerTest = 125 测试绿（B1）
- browser4-agentic 全模块：651 测试绿（B3）
- browser4-browser：Browser4WebDriverTest 绿（B2，含 4 个新 cookie 测试）
- browser4-rest：CrawlServiceTest+CrawlServicePersistenceTest 绿；`-am compile` 通过
- daemon.rs：12 单测绿（C3）；b4w.sh/ps1 真实 Git Bash 验证退出码一致

## 五、遗留事项（重启后可选跟进）

1. **snapshot -i 噪音**（0903-form I6 / fix-ecommerce I3）：根因在**外置 pulsar-browser 依赖的 ARIA 渲染器**（`driver.ariaSnapshot`），本仓库无可改实现 → 已按"报告-记录"处理；如需跟进请升级/覆盖依赖侧。
2. **cookie-set --path 深层拒绝**（0902-storage I1）：CLI 校验+错误映射+后端规范化已落地；真实浏览器 e2e 复测未做（仓库 e2e `browser.rs:338` 断言 `--path=/` 可用）。建议在 CI/真机 e2e 复验。
3. **X-SQL `htmlsnapshot query`** 仍走 ScrapeService 页面存储读取（活 DOM 化仅覆盖 capture/get/get all/export/summary/inspect）。
4. `__pulsar_utils__` 自愈：建议补 `capture → tab-new → capture → tab-select → capture` 回归 e2e。
5. **未提交**：全部改动在 git 工作树（218 项：暂存约 173 项为前序会话成果 + 本会话未暂存增量）。提交前建议 `git add -A && git diff --cached --stat` 审查；注意 daemon.rs 需整体重 stage（C3 提示）。两个 daemon.rs 测试在全量并发下偶发 flake（隔离 5/5 通过）。

## 六、恢复指引

重启会话后：直接说"继续处理 1ready 批处理存档"即可；本文件即上下文。若需提交，执行 `git add -A` 前请先人工审查 diff（含 coworker 任务文件移动与 docs-dev 本存档）。
