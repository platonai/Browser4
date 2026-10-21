# Browser4 新插件开发计划

> 基于 `docs-dev/copilot/plugin-design-proposal.md` 的设计提案，本计划覆盖 6 个新插件的实施路径。
> 不含编码，仅含任务分解、依赖分析、风险登记和验收标准。

---

## 一、总览

| 维度 | 内容 |
|------|------|
| 目标 | 为 browser4-plugins 新增 6 个插件，补齐自主浏览器代理价值链的交互/提取/导航/监控缺口 |
| 插件数 | 6（forms, structured, tables, pagination, auth, diff） |
| 阶段数 | 3 |
| 总工期 | 约 27 个工作日（单人串行）；阶段一/二可并行后约 17 个工作日 |
| 架构约束 | 每个插件遵循现有四层结构：config / service / integration / tools |
| 注册机制 | Spring Boot AutoConfiguration（`META-INF/spring/...AutoConfiguration.imports`）+ `AbstractToolExecutor` 自动注册到 agent 工具表 |
| 测试策略 | JUnit5 单测（`@Tag("Unit")` + `@Tag("Fast")`）+ MockSite E2E 场景（`@Tag("E2E")`） |
| 文档 | 每个插件配一份 `skills/browser4-cli/references/<name>.md` 技能文档 |

---

## 二、阶段划分

### 阶段一 · 基础（约 8 工作日）

**目标**：补齐最大交互缺口 + 最高信号密度提取，让 agent 的"理解→操作"闭环完整。

| 顺序 | 插件 | 工期 | 风险 | 理由 |
|------|------|------|------|------|
| 1.1 | structured | 3 天 | 低 | 纯解析，无 DOM 交互，无外部依赖，可立即产出价值 |
| 1.2 | forms | 5 天 | 中 | 涉及动态 DOM 交互（下拉/联动/校验），是最大缺口 |

**阶段一验收**：agent 能用 `structured.extract()` 零 token 判断页面类型，用 `form.fill()` 填充搜索框并提交。

---

### 阶段二 · 规模化（约 9 工作日）

**目标**：补齐分页遍历和表格提取，支撑 10 万页/天的规模化爬取场景。

| 顺序 | 插件 | 工期 | 风险 | 依赖 |
|------|------|------|------|------|
| 2.1 | pagination | 4 天 | 中 | 弱依赖 forms（表单提交后翻页场景） |
| 2.2 | tables | 5 天 | 中 | 无 |

**阶段二验收**：agent 能用 `pagination.collectAll()` 遍历多页列表，用 `table.extract()` 提取跨行跨列表格为 CSV。

---

### 阶段三 · 生态（约 10 工作日）

**目标**：补齐会话持久化和变更监控，覆盖登录态采集和定时监测场景。

| 顺序 | 插件 | 工期 | 风险 | 依赖 |
|------|------|------|------|------|
| 3.1 | auth | 5 天 | 高 | 涉及加密存储、跨 session 恢复、安全边界 |
| 3.2 | diff | 5 天 | 中 | 弱依赖 structured（变更分类用结构化数据辅助） |

**阶段三验收**：agent 能用 `auth.save()`/`auth.load()` 跨任务保持登录态，用 `diff.watch()` 注册价格监控并收到变更通知。

---

## 三、逐插件任务分解

### 1.1 browser4-structured（3 天）

| # | 任务 | 产出 | 天数 |
|---|------|------|------|
| S1 | 创建 Maven 模块骨架 | `pom.xml` + 目录结构 + `StructuredAutoConfiguration` + imports 文件 | 0.5 |
| S2 | 实现 `StructuredDataExtractor` | JSON-LD 解析（`<script type="application/ld+json">`）+ 微数据解析（itemscope/itemprop）+ OG/Twitter meta 解析 | 1 |
| S3 | 实现 `SchemaNormalizer` | 多来源归一化为 `PageStructuredData`，标注来源和置信度 | 0.5 |
| S4 | 实现 `StructuredToolExecutor` | 声明 domain="structured"，注册 5 个 toolSpec（extract/jsonld/opengraph/product/article） | 0.5 |
| S5 | 实现 `StructuredBrowseEventHandler` | `onDocumentSteady` 时提取并注入 page metadata | 0.25 |
| S6 | 单元测试 | JSON-LD/微数据/OG 各 3+ 用例，归一化合并用例，缺失数据兜底用例 | 0.25 |

**风险**：低。纯解析逻辑，无 DOM 交互。唯一注意点是 JSON-LD 可能有多个 `<script>` 块且含 `@graph` 数组。

---

### 1.2 browser4-forms（5 天）

| # | 任务 | 产出 | 天数 |
|---|------|------|------|
| F1 | 创建 Maven 模块骨架 | pom + 目录 + `FormAutoConfiguration` + imports | 0.5 |
| F2 | 实现 `FormDetector` | 扫描 `<form>` + 游离字段（带 label 关联分析：`<label for>` / aria-label / placeholder / 相邻文本） | 1 |
| F3 | 实现 `FieldTypeInferrer` | 语义类型推断（email/phone/zip/credit-card/name/address/date/captcha），基于 label 文本 + input type + autocomplete + placeholder + select 选项 | 1 |
| F4 | 实现 `FormFiller` | 填充逻辑：text/password/textarea 直接设值；select 按 value/文本匹配；checkbox/radio 组按值选中；date picker 触发；contenteditable 聚焦输入；动态联动等待（选省份→等城市出现→选城市） | 1.5 |
| F5 | 实现 `FormSubmitter` | 提交 + 等待导航/网络空闲 + 校验错误检测（字段级 error 文本提取） | 0.5 |
| F6 | 实现 `FormToolExecutor` | domain="form"，注册 5 个 toolSpec（detect/inferTypes/fill/fillAndSubmit/getValidationErrors） | 0.25 |
| F7 | 实现 `FormBrowseEventHandler` | `onDocumentSteady` 时检测表单存在性，注入 `__b4_forms__` 全局对象 | 0.25 |

**风险**：中。动态联动（省份→城市）和 date picker 是难点，需要 CDP 级交互。建议先用 MockSite 的表单页面做 E2E 验证。

**依赖**：复用 `computeInteractiveWeights`（已有交互元素排序）和 captcha 插件（表单含验证码时联动检测）。

---

### 2.1 browser4-pagination（4 天）

| # | 任务 | 产出 | 天数 |
|---|------|------|------|
| P1 | 创建 Maven 模块骨架 | pom + 目录 + `PaginationAutoConfiguration` + imports | 0.5 |
| P2 | 实现 `PaginationDetector` | 检测四种模式：经典分页（`a[rel=next]` / `.next` / 数字页码）、加载更多按钮、无限滚动（IntersectionObserver/scroll 事件）、URL 规律（`?page=N` / `/page/N/`） | 1.5 |
| P3 | 实现 `PaginationTraverser` | 按模式遍历：点击下一页→等待→收集新增项；滚动到底→等待→收集；URL 模式生成。跟踪已访问页和总页数 | 1 |
| P4 | 实现 `PaginationToolExecutor` | domain="pagination"，注册 4 个 toolSpec（detect/next/collectAll/hasNext） | 0.5 |
| P5 | 实现 `PaginationBrowseEventHandler` | `onDocumentSteady` 时预检测分页模式，注入 page metadata | 0.25 |
| P6 | 单元测试 + MockSite E2E | 检测逻辑单测；MockSite 搭建经典分页/无限滚动两种测试页面做 E2E | 0.25 |

**风险**：中。无限滚动的"是否到底"判断（区分加载中 vs 真到底）容易误判。需要设置合理的超时 + 新增项计数停滞检测。

**依赖**：弱依赖 forms（搜索表单提交后翻页的场景）。

---

### 2.2 browser4-tables（5 天）

| # | 任务 | 产出 | 天数 |
|---|------|------|------|
| T1 | 创建 Maven 模块骨架 | pom + 目录 + `TableAutoConfiguration` + imports | 0.5 |
| T2 | 实现 `TableDetector` | 检测真实 `<table>` + div 模拟表（检测 grid/flex 布局 + 对齐子元素 + 表头特征）；排除布局用 table（无边框、单行单列、无 thead） | 1 |
| T3 | 实现 `TableNormalizer` | rowspan/colspan 展开为二维矩阵；嵌套表提取为子表引用；合并单元格标记 | 1.5 |
| T4 | 实现 `TableExtractor` | 输出 headers + rows + mergedCells 结构化数据 | 0.5 |
| T5 | 实现 `TableExporter` | CSV / JSON（行列数组）/ Excel（.xlsx via Apache POI） | 1 |
| T6 | 实现 `TableToolExecutor` | domain="table"，注册 4 个 toolSpec（detect/extract/export/extractAll） | 0.25 |
| T7 | 实现 `TableBrowseEventHandler` | 检测到表格时在 page metadata 标注 `tableCount` | 0.25 |

**风险**：中。div 模拟表检测准确率取决于启发式规则，需要用真实页面（金融/电商）校准。rowspan/colspan 嵌套是边界 case。

**依赖**：无。可独立于阶段二其他插件开发。

---

### 3.1 browser4-auth（5 天）

| # | 任务 | 产出 | 天数 |
|---|------|------|------|
| A1 | 创建 Maven 模块骨架 | pom + 目录 + `AuthAutoConfiguration` + imports | 0.5 |
| A2 | 实现 `SessionStore` | 持久化 cookie + localStorage + sessionStorage（按域名隔离），加密存储敏感数据 | 1.5 |
| A3 | 实现 `LoginFlowCapture` | 记录登录关键步骤（URL 跳转链、表单提交、token 交换），生成 `LoginRecipe`（不存密码，只存流程） | 1 |
| A4 | 实现 `SessionRestorer` | 从 SessionStore 恢复到新 session，验证有效性（请求受保护页面检查重定向/元素） | 1 |
| A5 | 实现 `AuthStateMonitor` | 检测当前 session 登录态（特定 cookie/元素/重定向），失效时通知 | 0.5 |
| A6 | 实现 `AuthToolExecutor` | domain="auth"，注册 6 个 toolSpec（save/load/captureLogin/replayLogin/isLoggedIn/listSaved） | 0.25 |
| A7 | 实现 `AuthBrowseEventHandler` | 页面加载后检测登录态变化（URL 含 /login、出现登录表单 → 触发 onAuthRequired） | 0.25 |

**风险**：高。安全边界（加密存储、不泄露密码）、跨 session 恢复的可靠性（cookie 过期、CSRF token 失效）、OAuth 流程的复杂性。建议先做 cookie + localStorage 持久化（覆盖 80% 场景），OAuth 留后续迭代。

**依赖**：弱依赖 forms（登录表单检测复用 FormDetector）。

---

### 3.2 browser4-diff（5 天）

| # | 任务 | 产出 | 天数 |
|---|------|------|------|
| D1 | 创建 Maven 模块骨架 | pom + 目录 + `DiffAutoConfiguration` + imports | 0.5 |
| D2 | 实现 `PageSnapshotStore` | 按 URL+时间戳存储快照（DOM 哈希 + 关键区域文本 + 截图路径） | 1 |
| D3 | 实现 `PageDiffer` | 三种 diff：文本 diff（段落级 LCS）、DOM diff（选择器级增删改）、区域 diff（用户指定 CSS 选择器） | 1.5 |
| D4 | 实现 `ChangeClassifier` | 分类：价格变化（数字+货币比较）、库存变化（in-stock/out-of-stock）、内容更新、结构变化 | 1 |
| D5 | 实现 `ChangeNotifier` | 变更触发通知（webhook 回调 / 写文件 / 返回 diff 摘要） | 0.5 |
| D6 | 实现 `DiffToolExecutor` | domain="diff"，注册 5 个 toolSpec（snapshot/compare/compareToLast/watch/listChanges） | 0.25 |
| D7 | 实现 `DiffBrowseEventHandler` | 页面加载后自动存快照（可配置开关），为后续 diff 提供基线 | 0.25 |

**风险**：中。文本 diff 的段落切分粒度影响可用性（太细则噪声大，太粗则漏变化）。价格变化的数字提取需处理货币格式（¥/$/€）和小数点/千分位。

**依赖**：弱依赖 structured（变更分类可复用结构化数据辅助判断"什么变了"）。

---

## 四、跨插件公共任务

| # | 任务 | 说明 | 时机 |
|---|------|------|------|
| X1 | 更新 `browser4-plugins/pom.xml` | 每个新插件加 `<module>` | 各插件 F1/S1/... |
| X2 | 更新消费方 pom | 在 `browser4-apps/browser4-standalone` 或 `browser4-rest` 加新插件依赖（按需激活） | 各插件完成后 |
| X3 | 技能文档 | 每个插件写 `skills/browser4-cli/references/<name>.md`，含用法示例和参数说明 | 各插件最后一步 |
| X4 | MockSite 测试页面 | 为 forms/pagination/tables 各搭建 MockSite HTML 测试页 | 阶段一/二开始时 |
| X5 | 集成测试 | 在 `browser4-tests` 中加 E2E 场景，验证插件在真实流程中的协作 | 每阶段结束 |

---

## 五、风险登记

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| forms 动态联动不可靠 | 填充失败或填错 | 用 CDP 级等待（DOM 变化 + 网络空闲双重条件），设超时兜底 |
| pagination 无限滚动误判"已到底" | 遗漏数据 | 新增项计数停滞 N 秒后判定到底 + 最大滚动次数限制 |
| tables div 模拟表检测误报 | 把布局 div 当表格 | 要求同时满足：≥2 列对齐 + 有表头特征 + 子元素计数一致 |
| auth 安全边界 | 凭据泄露 | SessionStore 用 AES 加密；LoginRecipe 不存密码；提供 `auth.clear()` |
| auth cookie 过期恢复失败 | 静默失败 | SessionRestorer 恢复后主动验证（请求受保护页面），返回明确状态 |
| diff 文本粒度不当 | 噪声或漏报 | 提供可配置的粒度参数（段落/句子/行），默认段落级 |

---

## 六、验收标准（Definition of Done）

每个插件需满足以下全部条件方可标记完成：

- [ ] Maven 模块编译通过（`mvnw -pl browser4-plugins/browser4-<name> -am compile`）
- [ ] AutoConfiguration 在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册
- [ ] ToolExecutor 声明 domain + 至少 3 个 toolSpec，能被 agent 工具表发现
- [ ] BrowseEventHandler 接入页面加载生命周期，不阻塞主流程
- [ ] JUnit5 单测覆盖核心逻辑，标记 `@Tag("Unit")` + `@Tag("Fast")`，通过 `bin/test.ps1 fast`
- [ ] MockSite E2E 场景至少 1 个，标记 `@Tag("E2E")`
- [ ] `skills/browser4-cli/references/<name>.md` 技能文档完成
- [ ] 不引入新的直接依赖到核心模块（插件保持可选）

---

## 七、里程碑

| 里程碑 | 内容 | 预期工期 |
|--------|------|---------|
| M1 | structured + forms 完成，agent 可零 token 判断页面类型并填充表单 | 阶段一结束（~8 天） |
| M2 | pagination + tables 完成，agent 可遍历多页列表并提取表格 | 阶段二结束（~17 天） |
| M3 | auth + diff 完成，agent 可保持登录态并监控页面变更 | 阶段三结束（~27 天） |

> 若阶段一/二并行开发（两人），M2 可提前到 ~12 天，总工期压缩到 ~22 天。
