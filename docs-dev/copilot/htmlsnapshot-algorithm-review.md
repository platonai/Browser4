# htmlsnapshot 后端算法评审与改进建议

评审对象：两条命令的后端实现
- `cargo run -- htmlsnapshot` → 工具名 `html_snapshot_capture` → `handleHtmlSnapshotCapture`（`browser4-rest/.../MCPToolController.kt:278`）
- `cargo run -- htmlsnapshot inspect` → 工具名 `html_snapshot_inspect` → `handleHtmlSnapshotInspect`（`:600`）→ 核心纯函数 `inspectDocument`（`:955`）

共用的视觉几何算法：`PageSummaryIndexService.detectLinkGroups`（`:553`，`browser4-skeleton`）。

---

## 一、后端流程梳理

### capture（快照抓取）
1. 取会话并加锁 → `pulsarSession.capture(driver)` 抓静态 HTML。
2. `parse(page, noCache=true)` 得到 jsoup `FeaturedDocument`。
3. 统计 `img`/`a` 数量（两次独立 `select` 全文档遍历）。
4. 用 22 子句的大 `interactiveSelector` 选中所有可交互元素，`take(200)` 后交给 `computeInteractiveWeights` 取前 100。
   - `computeInteractiveWeights`（`:1415`）：过滤隐藏元素（`_h=1`/`aria-hidden`/`disabled`/`pointer-events:none`）→ 解析 `vi` 包围盒 → Tier1（按钮/输入/role/可编辑）权重 `1_000_000+面积`，Tier2 链接按 x 坐标(ε=10px)+面积(20%容差)分组，权重=组面积和。
5. 为每个元素生成 Section-8 格式 ref、包围盒、截断文本、weight、tier、semanticGroup。
6. `detectLinkGroups(document)` 做视觉几何链接分组。
7. 组装并返回 JSON 元数据。

### inspect（选择器自动发现）
1. 取页面（`getOrNull` 优先，否则 `capture`）→ parse。
2. `runVisualDetection` 跑 `detectLinkGroups` → 取最高分组的 `visualBestSelector`。
3. 决策：
   - 若用户 selector 匹配数 ≤1 → 优先用 `visualBestSelector`；否则 `autoDiscoverRepeatingSelector`（结构签名法）。
   - 若 ≥2 且视觉发现不同 selector → 作为 `speculativeSuggestion` 附带（不覆盖用户选择）。
4. `matches = select(effective).take(max)`；`matchCount = select(effective).size`（再算一次）。
5. 预计算 `elementWeightMap`（交互元素权重）。
6. 前 3 个 match 采样结构；对每个 match 的 descendant（≤maxDepth）生成候选选择器：class/id/裸标签/优先级属性(data-testid,aria-label,role,itemprop)/通用 data-*/PowerCSS `:expr(width>/height>/img>/a>)`。
7. 过滤：候选需出现在 ≥50% 的 match 中（至少 2）。
8. `qualityScore` 排序取前 40，按 p75 分档 high/medium/low。
9. 组装 JSON（matchCount、samples、suggestions、linkGroups）。

---

## 二、问题分级与改进建议

### P0 — 正确性与健壮性（应先修）

**1. `vi` 属性是单点故障，且无优雅降级**
capture 的权重、`detectLinkGroups`、inspect 的 PowerCSS 候选，全部依赖 Pulsar 注入的 `vi` 包围盒属性。一旦缺失（非 Pulsar 路径、缓存页、解析失败）：
- `computeInteractiveWeights` 因 `vi.isBlank()` 直接跳过所有元素 → `interactiveElements` 静默为空；
- `detectLinkGroups` 返回空 → inspect 退化为纯结构法，且与视觉无关。
建议：检测 `vi` 覆盖率，缺失时给出显式 warning 字段，并启用基于 DOM 顺序/布局的兜底（如用 `:nth-child` 或兄弟结构），而不是静默空结果。

**2. PowerCSS `:expr()` 选择器是“非标准 CSS”，会下游静默失败**
inspect 把 `${tag}:expr(width>200)` 这类 Pulsar 扩展选择器混在 `suggestions` 里，与标准 CSS 并列，且 `selectorType:"power"` 只存在于内部、输出 JSON 未标注。
而 `htmlsnapshot get`（`html_snapshot_scrape`）用的是标准 jsoup `select` —— 用户把 `:expr(...)` 复制过去会得到空结果。
建议：在输出中明确标注 `standard: false` / `engine: "powercss"`，或仅在显式 `--power` 标志下产出，避免误导。

**3. inspect 的 `coverage` 分母不一致**
`coverage = stats.count*100/matches.size`，其中 `matches = select(effective).take(max)`（最多 20 个被分析），而 `matchCount` 报告的是 `select(effective).size`（全量）。
当 effectiveSelector 实际匹配 500 个、只分析前 20 个时，某候选在 20 个里全中出现 → coverage 显示 100%，但 `matchCount=500` 暗示它只占真实匹配的 4%。
建议：coverage 统一基于全量 `matchCount`，或对 `analyzed` 子集明确标注“基于前 N 个样本”。

### P1 — 算法质量

**4. capture 多次全文档 `select` 遍历可合并**
`img` 计数、`a` 计数、`interactiveSelector`、以及 `detectLinkGroups` 内部多次遍历，是 4+ 次独立全文档扫描。对“10万~20万页/天”的性能目标，单遍遍历 + 一次 `select` 即可收集计数与交互元素。
另：`parse(page, noCache=true)` 每次强制重解析，若上次 capture 已解析可复用。

**5. inspect 重复求值 effectiveSelector + 惰性计算缺失**
`matches = select(effective).take(max)` 后，`matchCount = select(effective).size` 又求一次；应复用同一列表。
`elementWeightMap` 与 `interactiveSelector` 大字符串在 `matches` 为空或无需权重时仍会计算（try 块包住但始终执行），应惰性化。

**6. `autoDiscoverRepeatingSelector` 的魔力乘子缺乏校准/验证**
`2.0x class`、`1.8 text≥5`、`1.4 image`、`1.5 子标签多样` 等是凭经验写的硬编码权重，无回归基线。一旦页面结构变化（如导航项含图标 `<img>`）可能误判。建议：抽成可配置参数 + 用 `InspectDocumentTest` 里的真实页面固化“应发现 X”的断言，防止回归。

**7. 候选去重/归并不足，top-40 偏拥挤**
同一元素会同时产出 `.product-card`、`div.product-card`、`[data-testid=...]`、`div:expr(width>200)` 等多个语义等价的候选，淹没真正有用的选择器。建议：按“目标元素”归并为单一 canonical 选择器（优先 class > id > data-* > 裸标签，power 单独标注），降低噪声。

**8. depth 计算方式低效**
`desc.parents().indexOfFirst { it === match } + 1` 对每个 descendant 都向上走一遍父链（O(后代×深度) 额外 DOM 游走）。应在遍历时增量记录 depth。

### P2 — 可维护性与性能

**9. 重复定义的常量应集中**
`interactiveSelector` 大字符串、`structuralTags` 集合、`semanticTags` 集合在 `computeInteractiveWeights` / `autoDiscoverRepeatingSelector` / `inspectDocument`（`:1085`、`:853`）等处至少重复 3 次且略有差异。建议抽到 `object SnapshotAlgoConstants`。

**10. Tier1 权重 `1_000_000 + area` 把“重要性”绑死在“面积”上**
面积大的装饰性按钮会压过小的主按钮；同时 inspect 的 `weightBoost` 用 `maxWeight/1_000_000` 归一化 —— 两个算法通过“面积”隐性耦合，capture 调权重 inspect 会静默变化。建议：重要性用语义信号（role/标签/onclick）而非面积；面积最多作为微弱 tie-breaker。

**11. 错误处理返回 HTTP 200 + 文本**
两个 handler 的 catch 都 `ResponseEntity.ok(errorResponse(...))`，把应用错误包装成 200。MCP 调用方无法用状态码区分“无结果”与“崩溃”，只能字符串匹配。建议：明确区分（结构化的 `error` 字段 + 可选的 4xx/5xx，或统一错误协议）。

**12. `runVisualDetection` 在每次 inspect 都跑**
即使已给出匹配 ≥2 的好 selector，仍会跑完整的 `detectLinkGroups`（仅在产生 `speculativeSuggestion` 时有用）。建议：仅当用户 selector 匹配 ≤1（需 auto-discover）或显式 `--speculate` 时才跑视觉检测，降低常规 inspect 延迟。

---

## 三、整体评价

设计亮点：**视觉几何优先（语言无关、class 无关、结构容忍）+ 结构签名兜底** 的两段式发现思路是对的，`detectLinkGroups` 的“宽度→高度→x→y 间距规律性”聚类也相当扎实；capture 用 `vi` 包围盒做交互元素重要性排序是有洞察的做法。

主要短板集中在**鲁棒性（vi 单点故障、PowerCSS 误导、coverage 分母）**与**性能（重复全文档遍历、面积耦合）**。P0 三项是“用户会踩的坑”，建议优先修；P1 是算法质量，P2 是长期可维护性。

> 注：本地沙箱拦截 `~/.m2`/kotlin daemon，未能实际运行 `InspectDocumentTest` 验证；以上基于源码静态评审，落地前建议补一个“vi 缺失降级”与“PowerCSS 标注”的回归测试。
