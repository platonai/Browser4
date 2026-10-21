# `htmlsnapshot summary` 命令审查报告

- **审查日期**: 2026-10-03
- **审查范围**: CLI 层、后端算法、文档与测试
- **审查方式**: 静态代码分析（未运行实测）

---

## 一、架构与职责分离

| 层面 | 文件 | 职责 | 评价 |
|---|---|---|---|
| CLI 定义 | `cli/browser4-cli/src/commands.rs#L4186` | 命令元数据、参数映射、MCP tool 名称（`html_snapshot_summary`） | 存在缺陷：`tool_params_fn` 只透传 `raw`/`stdout`，漏掉 `verbose`（见 P1） |
| CLI 处理 | `cli/browser4-cli/src/main.rs#L9495` | 并发调用 page_url/page_title/summary、结果落盘、大纲渲染 | 良好。`run_on_big_stack` 针对 Windows 1MB 主线程栈溢出有明确防护 |
| 后端入口 | `browser4-rest/.../agent/tool/HTMLSnapshotToolExecutor.kt#L730` | session 解析、live DOM 优先、归档回退 | 良好。`managed.withLock` + live-first/archival-fallback 两级策略 |
| 核心算法 | `browser4-core/browser4-skeleton/.../parse/html/PageSummaryIndexService.kt` | WPSI 生成：索引 → 打分 → landmark → 关键节点 → 列表 → 链接组 → 表格 → YAML | 优秀。1471 行纯函数式 object，确定性、无 AI 依赖、KDoc 完备 |
| 输出渲染 | `main.rs#L9068` `format_summary_outline` | 手写逐行 YAML 解析 → 紧凑大纲 | 可用但脆弱：约 400 行字符串匹配解析器，与后端 YAML 字段顺序隐式耦合，且零单元测试 |

**结论**：分层合理，后端纯函数可独立测试；CLI 渲染层是全链路最薄弱环节（手写 YAML 解析 + 无测试）。

---

## 二、核心算法审查（PageSummaryIndexService）

### 2.1 整体流程

`generate()` 分阶段执行：克隆清洗 DOM（去 script/style/meta/link/noscript）→ 按 `vi` 属性 BFS 索引 → 确定性打分（h1=100、h2=50、table=60、button/input=50 等固定权重 + id/class 加成）→ landmark 过滤 → Top-100 关键节点（按 `typeLabel|text` 去重并计 `repeats`）→ 列表检测 → 链接组视觉聚类 → 表格摘要 → 统计 → YAML 输出。

**优点**：
- `ViBox.parse` 自动识别 base-36 / 紧凑十进制 / 遗留空格分隔三种 wire format，兼容旧 fixture
- 页面类型推断有明确护栏：`isContentRich`（≥5 个 >10 字符的 heading/p）防止新闻门户因页眉一个登录链接被误判为 "Login / Auth"，注释写明了推理过程
- `toYamlValue` 对引号、换行、类布尔、类数字字符串的转义处理完备，有对应测试
- 链接组检测（`detectLinkGroups`）基于视觉几何聚类（宽 ±10% → 高 ±15% → x 聚类 → y 间距正则性 → 列合并 → 导航抑制 → LCA 容器 → 重叠消解），与语言、class 命名无关；常数全部命名化（`MIN_CARD_WIDTH` 等）

### 2.2 发现的问题

1. **无效分支（dead branch）**：`inferPageType` L555 的 `isContentRich && isHomepage -> "News / Content"` 不可达——前面 L552 `isHomepage -> "Homepage"` 已拦截所有 homepage 路径
2. **ToolSpec 描述不完整**：`HTMLSnapshotToolExecutor.kt#L182` 的 description 只提 "title, statistics, and detected link groups"，漏掉 landmarks、key content nodes、lists、tables
3. **`detectLists` 子标签硬编码**：仅识别 `{div, li, tr, article, section, option}`，其他重复结构（如 `dl > dt`）不进 lists 段——启发式可接受，但文档未说明该白名单

### 2.3 无 JSON Schema 契约（有意为之）

`ToolResultSchemas.kt#L109` 注释明确：`html_snapshot.summary` 返回 YAML 而非 JSON，故无 JSON-Schema 契约可强制执行。设计决策合理且有记录。

---

## 三、CLI 层问题

### P1 — `-v/--verbose` 被静默丢弃（功能失效）

- `commands.rs#L4196` 声明了 `OptionDef { name: "verbose", short: Some("v") }`
- `main.rs#L9512` 的 handler 从 `tool_params.get("verbose")` 读取
- 但 `tool_params_fn`（commands.rs#L4200-L4205）只写入 `raw`/`stdout`，**从不写入 `verbose`**；已确认主流程（main.rs#L25122）不存在 parsed→tool_params 的通用合并

**后果**：`format_summary_outline` 中所有 verbose 分支（内容节点 score 列 L9363、链接组 score L9248、打分图例 L9396-L9399）全部是死代码；用户加 `-v` 无效果也无报错。

对照组 `htmlsnapshot-inspect`（commands.rs#L4428-L4430）有 "Pass through CLI-side flags" 注释并透传 `stdin`/`selectorBase64`——summary 未遵循同一模式。

**修复（一行）**：

```rust
if let Some(true) = get_bool(args, "verbose") { p["verbose"] = json!(true); }
```

### P2 — "Use --verbose" 提示条件写反（当前被 P1 掩盖）

`main.rs#L9456`：`if verbose { "# Use --verbose to see internal scoring..." }`——已开启 verbose 时才提示用户去开 verbose，应为 `if !verbose`。P1 修复后此 bug 立刻显现，两处须一起改。

### P3 — 结果拼包依赖换行分隔

`main.rs#L9537-L9573`：三个并发调用结果用 `{url}\n{title}\n{summary}` 拼接、`splitn(3, '\n')` 拆包。合法 HTML title 可含换行符，一旦含 `\n`，title 被截断且残余混入 summary。且 YAML 本身已含 `page.url`/`page.title`，两次额外 MCP 调用存在冗余（`tokio::join!` 并发下延迟代价小，但协议脆弱性是实的）。

### P3 — 其他次要项

- 无空结果检测：对照 `handle_summarize`（main.rs#L7925 `detect_empty_extraction`），后端返回空串时会静默保存空文件
- `--json` + `--raw` 同时使用会让裸 YAML 经 `println!` 与 JSON 缓冲混排 stdout
- tips 复用合理：`htmlsnapshot-summary` 映射到 `TIPS_INSPECT`（tips.rs#L460），与 inspect 的发现工作流互补

---

## 四、文档审查

### P2 — `docs/htmlsnapshot-inspect-summary.md` 自相矛盾

- L5 声称 "Both `inspect` and `summary` operate on the cached HTML snapshot"
- L19 又说 "read the **live page** … no prior capture is required"
- L153-L158 的 usage 写 "Capture a snapshot first, then summarize" 并在 `summary` 前加了 `browser4-cli htmlsnapshot`

后端实现是 live-document-first（`HTMLSnapshotToolExecutor.kt#L735`），help.rs 与三份 README 统一口径为 "no prior capture needed"。L5 与 usage 示例应改为 live-page 口径（inspect 段落 L47-L50 同样问题）。

### P3 — `references/htmlsnapshot.md` Summary 章节过简

L207-L213 仅一句话 + 一个示例，未提 `--raw`/`--stdout`/`--verbose` 与落盘路径 `.browser4-cli/snapshot/htmlsnapshot-summary-<timestamp>.yml`；同文件 grep 章节有完整 flag 表，详略失衡。（`--verbose` 文档化须在 P1 修复后进行。）

### 一致的部分

- `README.md#L438` / `README.zh.md#L445` / `cli/README.md#L406` 三处表格条目口径一致
- help.rs 命令组列表（L1868）、说明段（L1952）、示例（L2032）齐全，且测试锁定（L3566、L3676）
- 落盘路径文档（htmlsnapshot-inspect-summary.md L162）与 `resolve_output_path` 实际行为一致

---

## 五、测试覆盖

### 后端（23 个单元测试，`PageSummaryIndexServiceTest.kt`）

已覆盖：打分权重（h1>h2、id 加成）、landmark 全集、列表检测阈值（≥3）、表格行/列/表头、YAML 特殊字符与多行转义、统计计数、Product Detail / Article 页面类型、链接组 7 场景（产品网格、单列文章列表、导航抑制、行包装回退、最小数量、最深容器选择、无重复模式）。

**缺口**：
1. `ViBox.parse` 三种 wire format 无直接测试（KDoc 详述了三种格式但零覆盖）
2. `repeats` 去重计数（Phase 5）无测试
3. 页面类型 10 余个分支只测了 2 个——Login/Auth、Homepage、Search Results、Form、Media、Blog、Forum、Documentation、General Page 及 content-rich 护栏均未测
4. `buildElementRef` / `findClosestId` 无直接测试

### CLI

- mock-server e2e（`mock_server.rs#L6632`，Basic 级）验证工具名与 `sessionId` 透传
- **`format_summary_outline`（约 400 行手写 YAML 解析器）零单元测试**——全链路最脆弱、与后端字段顺序隐式耦合的代码无任何保护，建议最优先补
- 无 `--raw` / `--verbose` / 输出文件内容断言（`--verbose` 断言须在 P1 修复后才有意义）

### 后端控制器

`MCPToolControllerTest` 覆盖了 `html_snapshot_summary` 的域提取（`extractDomain` → `html_snapshot`）。

---

## 六、问题清单汇总

| # | 级别 | 位置 | 问题 | 修复建议 |
|---|---|---|---|---|
| 1 | P1 ✅ 已修复 | commands.rs#L4200 | `verbose` 未透传，`-v` 静默失效 | `tool_params_fn` 增加一行 verbose 透传 |
| 2 | P2 ✅ 已修复 | main.rs#L9456 | "Use --verbose" 提示条件写反（原代码含冗余 if/else 双分支，verbose ON 时提示无意义） | 删除冗余分支，仅保留 `if !verbose` 时的提示（与 #1 同改） |
| 3 | P2 | docs/htmlsnapshot-inspect-summary.md L5/L153 | "capture first" 与 live-page 设计矛盾 | 统一为 live-page 口径，去掉 capture 前置步骤 |
| 4 | P2 | PageSummaryIndexService.kt#L555 | `News / Content` 分支不可达 | 删除或上移该分支 |
| 5 | P3 | main.rs#L9560 | url/title/summary 换行拼包可被 title 中的 `\n` 破坏 | 改从 YAML 解析 page.url/page.title，或换 JSON envelope |
| 6 | P3 | main.rs#L9495 | 无空 summary 检测 | 参照 `detect_empty_extraction` 加告警 |
| 7 | P3 | HTMLSnapshotToolExecutor.kt#L182 | ToolSpec 描述漏掉 landmarks/lists/tables | 补全 description |
| 8 | P3 | references/htmlsnapshot.md#L207 | Summary 章节缺 flag 与落盘说明 | 补 `--raw`/`--stdout`/`--verbose` 与输出路径 |
| 9 | 测试 | main.rs#L9068 | `format_summary_outline` 零单测 | 用真实 WPSI fixture 补渲染测试 |
| 10 | 测试 | PageSummaryIndexServiceTest.kt | ViBox.parse / repeats / 页面类型分支未测 | 补对应单测 |

---

## 七、总体结论

该命令整体质量高：后端算法分层清晰、完全确定性、KDoc 完备，链接组视觉聚类设计周密；CLI 侧针对 Windows 主线程栈溢出的 `run_on_big_stack` 防护有记录、有针对性。

**最优先 actionable 项**：#1 + #2（两行代码，修复后 verbose 路径才可用并可测试）、#3（文档口径统一）。**技术债重点**：#9——`format_summary_outline` 是 CLI 侧最大无测试代码块，且与后端 YAML 格式隐式耦合，后端字段重排会导致大纲静默错乱。
