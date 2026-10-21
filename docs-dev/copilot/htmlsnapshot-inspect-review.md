# `htmlsnapshot inspect` 命令审查报告

- **审查日期**: 2026-10-03
- **审查范围**: CLI 层、后端算法、文档与测试
- **审查方式**: 静态代码分析（未运行实测）

---

## 一、架构与职责分离

| 层面 | 文件 | 职责 | 评价 |
|---|---|---|---|
| CLI 定义 | `cli/browser4-cli/src/commands.rs#L4402` | 命令元数据、参数映射、MCP tool 名称转换 | 良好。支持 `@file`、`--stdin`、`--selector-base64` 三种 selector 输入方式，均在 `tool_params_fn` 中透传 |
| CLI 处理 | `cli/browser4-cli/src/main.rs#L9596` | selector 解析（文件/stdin/base64）、服务端调用、结果渲染 | 良好。CLI 端负责输入解析和人性化输出渲染，与后端 JSON 契约分离 |
| 后端入口 | `browser4-rest/.../agent/tool/HTMLSnapshotToolExecutor.kt#L751` | session 解析、live DOM 获取、调用 `inspectDocument` | 良好。统一使用 `liveDocumentOrNull()` 优先读取 live DOM，回退到独立加载 |
| 核心算法 | `browser4-rest/.../rest/mcp/controller/MCPToolController.kt#L2196` | `inspectDocument()`：视觉检测、自动发现、建议生成 | 复杂但结构清晰。纯函数设计（无 session/browser 依赖），可直接单元测试 |
| 输出渲染 | `main.rs#L9677-L10084` | JSON 解析、表格化展示、actionable tips | 优秀。分层渲染 quality suggestions → bare-tag fallbacks → singleton suggestions → next-step tips |

**结论**：分层合理，CLI 与后端契约通过 JSON 传递，后端算法纯函数化可独立测试。

---

## 二、核心算法审查

### 2.1 自动发现（`autoDiscoverRepeatingSelector`，MCPToolController.kt#L2023）

算法流程：遍历所有父元素，按 direct children 的 CSS signature（`tag.class1.class2`）分组；过滤 size ≥ 2 的组；多维评分（size × class-boost × text-diversity × structural-richness × image-presence × tag-diversity × text-length × chrome-penalty × viewport-position）。

**优点**：
- 视觉几何优先（`runVisualDetection`）：通过 `PageSummaryIndexService.detectLinkGroups` 利用 bounding box 聚类，对 class-name-independent 的页面更鲁棒
- 容器内发现：用户 selector 精确匹配 1 个元素时，先在容器内部尝试发现（L2224-L2243），避免全局搜索噪音
- chrome 惩罚：导航/页眉/页脚区域乘 0.3，有效抑制导航项干扰

**潜在问题**：
1. **性能**：`document.select("*")` 遍历整棵树（L2029），对超大 DOM（数万节点）可能耗时；文档未说明上限
2. **body 特殊处理**：`parentTag in structuralTags && parentTag != "body"`（L2031）允许 body 参与分组，其 children 通常高度异构（header/main/footer），可能产生大量无效分组
3. **viewport 加权依赖 `vi` 属性**：`yPositions` 从 `member.attr("vi")` 解析（L2148），若 live DOM 无 `vi`（如 plain outerHTML 回退路径）则跳过该加权——预期行为，但文档未明确说明失效条件

### 2.2 建议生成与质量排序（L2392-L2550）

候选生成：对每个 match 的 descendant，生成 class、id、attr、bare tag、PowerCSS `:expr()` 五种候选；PowerCSS 候选依赖 `vi` 属性（L2449），阈值取整到最近的 100。

过滤与排序：阈值 `max(2, matches.size * 0.5)`；质量分 = `count + specificity*n + distinctBoost*n + semanticBoost*n + weightBoost*n`；Tier 按 P75 分 high/medium/low。

**问题**：
1. **阈值截断误差**：`(matches.size * 0.5).toInt()` 截断而非向上取整——matches.size=5 时阈值=2，实际接受 40% 覆盖率，与文档声称的「≥50%」不符（奇数时偏差）
2. **qualityScore 线性叠加**：`div`/`span` 的 specificity 为负（-0.3/-0.1），但乘以 n 后 count 仍可主导——20/20 出现的 bare div（14 分）可能排在 10/20 的 h2（12 分）之前
3. **`distinctTextCount` 基于 `ownText`**（L2486）：`<a><em>$</em><span>140</span></a>` 类复合元素 ownText 为空，丢失 distinctiveness boost（注释表明有意为之，但与 L2324 的注释自相矛盾——采样用 `text()`，统计用 `ownText()`）
4. **负值参数未校验**：`--max -1` → Kotlin `take(-1)` 抛 IllegalArgumentException → 500；`--max 0` → 返回 `matchCount: 0` 硬编码值（L2293），与实际匹配数不符
5. **CLI 端 `--max abc` 静默忽略**（commands.rs `if let Ok(n) = m.parse::<i32>()`）：无效输入无任何提示，回落到默认值

### 2.3 Singleton 检测（L2575-L2627）

- id 元素遍历所有 `[id]`，跳过结构型 id；heading 检测 h1-h3；价格模式正则 `[$€¥£]\s*\d+|\d+\s*[$€¥£]`

**问题**：
1. **作用域泄漏**：singleton 检测在**整个 document** 上运行（`document.select("[id]")` / `document.select("*")`），而非用户 selector 限定的 scope——用户 inspect `.sidebar` 时会收到主内容区的 singleton 建议
2. **性能**：`document.select("*")` + 每元素 `ownText()` 是额外 O(n) 全树扫描
3. **价格正则局限**：不支持负号/千分位（`-$19.99`、`$1,234.56`）；仅匹配 `ownText`，价格文本在 descendant 时漏检（启发式可接受，但应知悉）

### 2.4 Truncation 检测（L2343-L2367）

`text().trim().endsWith("...")` 时检查 child 的 title/aria-label/alt 属性。

**问题**：仅检查 `children()`（直接子元素），不递归 descendant——截断文本在深层 span 中而父级无 hint 时漏检。

---

## 三、CLI 渲染审查

### 3.1 输出渲染（main.rs#L9768-L10084）— 优点

- 人性化标题、auto-discovery 可视化、speculative suggestion 提示
- Truncation hint 直接给出 `DOM_FIRST_ATTR(DOM, '...', '...')` 代码示例
- 根据发现结果动态推荐 next steps；无重复模式时引导 `htmlsnapshot summary`

### 3.2 发现的问题

| 优先级 | 问题 | 位置 | 说明 |
|---|---|---|---|
| **P1** | **0-match 时陈旧/错误提示** | main.rs#L9731 | 提示「ensure a HTML snapshot has been captured」——与全局「read 命令读 live page，无需 capture」的设计口径直接矛盾 |
| **P1** | **0-match 时 `--json` 输出结构不完整** | main.rs#L9707-L9765 | 提前 return 仅写入 `matchCount`/`selector` 两个字段；成功路径写 `json_field("inspect", data)` 完整对象——同一命令两种 JSON schema |
| **P2** | stdin 与 positional/base64 冲突静默 | main.rs#L9611-L9651 | 同时给 `--stdin` 和位置参数时 stdin 静默胜出；base64 又静默覆盖前两者，无冲突报错 |

---

## 四、一致性与文档

### 4.1 文档不一致（需修复）

| 来源 | `--max` 默认值 | 状态 |
|---|---|---|
| `commands.rs#L4412`、`help.rs#L796`、后端 ToolSpec | **20** | 实际值 |
| `skills/browser4-cli/references/htmlsnapshot.md#L325` | **10** | **错误，需修正为 20** |
| `docs/htmlsnapshot-inspect-summary.md` 参数表 | 20 | 正确 |

### 4.2 其余口径检查

- 「无需 prior capture」口径：`commands.rs` 描述、`htmlsnapshot.md`、`docs/htmlsnapshot-inspect-summary.md` 一致；**唯 CLI 0-match 错误提示（main.rs#L9731）陈旧**
- 输出格式示例与 `main.rs` 渲染格式一致；Section 8 ref 格式前后端一致

---

## 五、测试覆盖

### 5.1 单元测试（`browser4-rest/.../InspectDocumentTest.kt`）— 覆盖全面

覆盖：Basic Discovery（recurring class、unique per-card ids、id selectors、empty、0-match auto-discovery）、Smart Ranking、Value Sampling、Attribute Selectors（data-testid/aria-label/role/itemprop/data-*）、PowerCSS（width/img/combined/rounding/no-vi）、Response Structure、`:expr()` initial selector、Edge Cases（no-vi、single match、malformed vi、:root auto-discovery、class 优先、explicit bypass）。

**缺失**：
1. 无超大 DOM（数万节点）性能/压力测试
2. 无并发 inspect 的集成测试（`withLock` 仅保证 session 级互斥）
3. `truncateText` 有 CJK 分支但无中文/日文/韩文 HTML 测试用例
4. 无 `--max 0` / 负数的边界测试（对应 2.2 问题 4）

### 5.2 E2E 测试缺口

- `HtmlSnapshotScenariosE2ETest.kt` 中无 `inspect` 场景（grep 无匹配）
- CLI 侧 `commands.rs` 仅存在性测试（L7529）与 `no_snapshot_commands()` 测试（main.rs#L27419），无参数映射/输出渲染单测
- `commands.rs` 标注 `e2e_coverage: E2eCoverage::Tested`（L4417），与实际 E2E 覆盖不符，需核实

---

## 六、问题清单（按优先级）

| 优先级 | 问题 | 位置 | 建议 |
|---|---|---|---|
| P1 | 文档 `--max` 默认值错误（10 vs 实际 20） | `htmlsnapshot.md#L325` | 修正为 20 |
| P1 | 0-match 提示要求 capture，与 live-read 口径矛盾 | `main.rs#L9731` | 改为引导检查页面加载/选择器，不提 capture |
| P1 | 0-match 时 `--json` 输出 schema 与成功路径不一致 | `main.rs#L9707-L9765` | 提前 return 前写入完整 `json_field("inspect", data)` |
| P2 | `--max` 负数崩溃（`take(-1)` 抛异常）；`--max 0` 返回硬编码 `matchCount: 0` | `MCPToolController.kt#L2271/L2293` | 后端校验 max ≥ 1，非法值返回明确错误 |
| P2 | 阈值截断使奇数 match 实际接受 <50% 覆盖 | `MCPToolController.kt#L2500` | 改为 `ceil(matches.size * 0.5).toInt()` 或文档改为「约 50%」 |
| P2 | singleton 检测作用域泄漏（整棵树而非用户 scope） | `MCPToolController.kt#L2597/L2618` | 限定在 effectiveSelector 命中的子树内 |
| P2 | CLI 对非法 `--max`/`--depth`（如 `abc`）静默忽略 | `commands.rs#L4422-L4427` | parse 失败时返回明确错误 |
| P3 | selector 输入冲突（stdin + 位置参数 + base64）静默覆盖 | `main.rs#L9611-L9651` | 冲突时报错或至少警告 |
| P3 | truncation 检测仅查 direct children | `MCPToolController.kt#L2349` | 递归检查 descendants 的 title/aria-label/alt |
| P3 | `distinctTextCount` 统计口径（ownText）与采样口径（text()）不一致 | `MCPToolController.kt#L2404 vs L2486` | 统一为 `text()` 或补充说明 |
| P3 | `e2e_coverage: Tested` 标注与实际 E2E 覆盖不符 | `commands.rs#L4417` | 补 E2E 场景或修正标注 |

### 可选优化

1. `document.select(effectiveSelector)` 在 L2271-L2272 调用两次，可复用单次结果
2. `elementWeightMap` 全树扫描可与 capture 阶段复用
3. 建议数量上限 40 可提供 CLI 侧 `--limit-suggestions`
4. 补充 CJK 页面 inspect 测试

---

## 七、总结

`htmlsnapshot inspect` 整体实现质量**较高**：职责分层清晰、自动发现算法鲁棒（视觉优先 + 结构回退 + chrome 惩罚）、输出丰富且可操作、单元测试覆盖全面（70+ 用例）。

**必须修复**（P1）：文档默认值错误、0-match 陈旧提示、0-match JSON schema 不一致。

**应修复**（P2）：`--max` 边界值校验、阈值截断、singleton 作用域泄漏、CLI 参数静默忽略。

其余为 P3 体验优化与测试补全项。
