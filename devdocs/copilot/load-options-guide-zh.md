# LoadOptions AI 友好指南（中文版）

## 概览

`LoadOptions` 是 Browser4 中控制网页抓取、处理和存储的主要配置类。本指南帮助 AI 代理理解和有效使用 LoadOptions 参数。

**位置**: `ai.platon.pulsar.skeleton.common.options.LoadOptions`

**关键特性**:
- 命令行风格参数（例如：`-expires 1d -parse -storeContent`）
- 可在 URL 字符串中直接指定或通过编程方式指定
- 同时支持门户（索引）页面和详情（item）页面
- 多个来源的参数会自动合并

---

## 快速参考

### 最常用模式

```kotlin
// 强制刷新（类似浏览器刷新按钮）
session.load(url, "-refresh")

// 设置缓存过期时间
session.load(url, "-expires 1d")  // 或 "-i 1d"

// 解析并存储内容
session.load(url, "-parse -storeContent")

// 从门户页提取出链
session.loadOutPages(url, "-outLink a[href~=item] -topLinks 20")

// 完整的门户+详情模式
session.loadOutPages(url, 
    "-expires 1d -outLink a.product -topLinks 10 " +
    "-itemExpires 7d -itemRequireImages 5"
)
```

### 时间格式支持

支持 ISO-8601 和 Hadoop 时间格式：
- **ISO-8601**: `PT1H30M`, `P1D`, `PT10S`
- **Hadoop**: `1s`, `10m`, `1h`, `1d`, `7d`
- **单位**: ns, us, ms, s（秒）, m（分钟）, h（小时）, d（天）

---

## 参数分类

### 1. 任务标识与组织

| 参数名 | 命令行选项 | 类型 | 默认值 | 说明 | 示例 |
|--------|-----------|------|--------|------|------|
| entity | `-e`, `-entity` | String | "" | 内容类型（如"product", "article"） | `-entity product` |
| label | `-l`, `-label` | String | "" | 任务逻辑分组标签 | `-label 电子产品-2024-Q1` |
| taskId | `-taskId` | String | "" | 任务唯一标识符 | `-taskId task-12345` |
| taskTime | `-taskTime` | Instant | EPOCH | 批次分组时间戳 | `-taskTime 2024-01-05T10:00:00Z` |
| deadline | `-deadline` | Instant | doomsday | 任务绝对截止时间 | `-deadline 2024-01-05T18:00:00Z` |

**AI 提示**: 使用这些参数组织和追踪大规模爬虫任务

---

### 2. 缓存与刷新控制

| 参数名 | 命令行选项 | 类型 | 说明 | 示例 |
|--------|-----------|------|------|------|
| expires | `-i`, `-expires` | Duration | 缓存有效期，超过后重新抓取 | `-expires 1d` |
| expireAt | `-expireAt` | Instant | 缓存的绝对过期时间点 | `-expireAt 2024-01-06T00:00:00Z` |
| refresh | `-refresh` | Boolean | 强制立即重新抓取（类似浏览器刷新） | `-refresh` |
| ignoreFailure | `-ignF`, `-ignoreFailure` | Boolean | 即使之前失败也重试 | `-ignoreFailure` |

**重要说明**:
- `refresh` = `-ignoreFailure -expires 0s` + 重置重试计数器
- `expires` 和 `expireAt` 任选其一使用
- 1天 = `1d` = `24h` = `PT24H`

---

### 3. 页面质量要求

| 参数名 | 命令行选项 | 类型 | 说明 | 示例 |
|--------|-----------|------|------|------|
| requireSize | `-rs`, `-requireSize` | Int | 最小页面大小（字节），小于此值重新抓取 | `-requireSize 300000` |
| requireImages | `-ri`, `-requireImages` | Int | 最小图片数量，少于此值重新抓取 | `-requireImages 10` |
| requireAnchors | `-ra`, `-requireAnchors` | Int | 最小链接数量，少于此值重新抓取 | `-requireAnchors 50` |
| requireNotBlank | `-rnb`, `-requireNotBlank` | String | CSS 选择器，指定元素必须有非空文本 | `-requireNotBlank .product-title` |
| waitNonBlank | `-wnb`, `-waitNonBlank` | String | 等待指定元素出现非空文本后再继续 | `-waitNonBlank .dynamic-content` |

**AI 提示**: 
- 质量要求用于验证页面完整性
- `requireNotBlank` 验证完整性，不满足则重新抓取
- `waitNonBlank` 等待内容加载，不是验证

---

### 4. 浏览器交互设置

| 参数名 | 命令行选项 | 类型 | 说明 | 示例 |
|--------|-----------|------|------|------|
| autoScrollCount | `-sc`, `-scrollCount` | Int | 页面加载后滚动次数 | `-scrollCount 5` |
| scrollInterval | `-si`, `-scrollInterval` | Duration | 滚动之间的时间间隔 | `-scrollInterval 1s` |
| scriptTimeout | `-stt`, `-scriptTimeout` | Duration | 注入 JavaScript 执行超时 | `-scriptTimeout 30s` |
| pageLoadTimeout | `-plt`, `-pageLoadTimeout` | Duration | 页面加载超时 | `-pageLoadTimeout 60s` |
| interactLevel | `-ilv`, `-interactLevel` | Enum | 交互级别（高=更好的内容，低=更快） | `-interactLevel HIGH` |

**AI 提示**: 
- 更多滚动 = 更多懒加载内容
- 更长间隔 = 内容加载更完整，但速度更慢
- `interactLevel` 是预设，单独设置可覆盖

---

### 5. 出链提取（门户页面）

| 参数名 | 命令行选项 | 类型 | 说明 | 示例 |
|--------|-----------|------|------|------|
| outLinkSelector | `-ol`, `-outLink` | String | CSS 选择器，提取门户页链接 | `-outLink div.list a` |
| outLinkPattern | `-olp`, `-outLinkPattern` | String | 正则表达式，过滤提取的链接 | `-outLinkPattern .*/product/.*` |
| topLinks | `-tl`, `-topLinks` | Int | 最多提取的链接数量 | `-topLinks 20` |

**使用场景**: 
```kotlin
// 从列表页提取商品详情页链接
session.loadOutPages(portalUrl, 
    "-outLink a.product-link " +
    "-outLinkPattern .*/dp/.* " +
    "-topLinks 50"
)
```

---

### 6. 详情页专用选项

所有 item 选项与主选项工作方式相同，但仅应用于从门户页提取的详情页。

| 主选项 | 详情页选项 | 说明 |
|--------|-----------|------|
| expires | itemExpires (`-ii`, `-itemExpires`) | 详情页缓存过期时间 |
| requireSize | itemRequireSize | 详情页最小大小 |
| requireImages | itemRequireImages | 详情页最小图片数 |
| requireAnchors | itemRequireAnchors | 详情页最小链接数 |
| scrollCount | itemScrollCount | 详情页滚动次数 |
| scrollInterval | itemScrollInterval | 详情页滚动间隔 |
| scriptTimeout | itemScriptTimeout | 详情页脚本超时 |
| pageLoadTimeout | itemPageLoadTimeout | 详情页加载超时 |
| waitNonBlank | itemWaitNonBlank | 详情页等待非空元素 |
| requireNotBlank | itemRequireNotBlank | 详情页要求非空元素 |

**典型用法**:
```kotlin
session.loadOutPages(url,
    "-expires 1d " +           // 门户页1天过期
    "-outLink a.product " +    // 提取商品链接
    "-topLinks 20 " +          // 最多20个
    "-itemExpires 7d " +       // 详情页7天过期
    "-itemRequireImages 5"     // 详情页至少5张图
)
```

---

### 7. 存储与持久化

| 参数名 | 命令行选项 | 类型 | 说明 | 示例 |
|--------|-----------|------|------|------|
| persist | `-persist` | Boolean | 是否立即持久化抓取的页面 | `-persist true` |
| storeContent | `-sct`, `-storeContent` | Boolean | 是否在数据库中存储 HTML 内容 | `-storeContent true` |
| dropContent | `-dropContent` | Boolean | 明确不存储 HTML 内容（优先级高） | `-dropContent` |
| lazyFlush | `-lazyFlush` | Boolean | 批量写入（true）vs 立即写入（false） | `-lazyFlush true` |

**AI 提示**: 
- HTML 内容通常是最大的部分
- 仅需元数据时使用 `-dropContent` 节省存储
- `dropContent` 优先级高于 `storeContent`

---

### 8. 解析与链接处理

| 参数名 | 命令行选项 | 类型 | 说明 | 示例 |
|--------|-----------|------|------|------|
| parse | `-ps`, `-parse` | Boolean | 抓取后立即解析 | `-parse` |
| reparseLinks | `-rpl`, `-reparseLinks` | Boolean | 即使已解析也重新提取链接 | `-reparseLinks` |
| ignoreUrlQuery | `-ignoreUrlQuery` | Boolean | 从 URL 中删除查询参数 | `-ignoreUrlQuery` |
| noNorm | `-noNorm` | Boolean | 禁用 URL 标准化 | `-noNorm` |
| noFilter | `-noFilter` | Boolean | 禁用 URL 过滤 | `-noFilter` |

**使用建议**: 
- `-parse` 启用解析子系统，用于大规模爬虫
- `-reparseLinks` 在链接提取规则变化时使用

---

### 9. 重试与失败处理

| 参数名 | 命令行选项 | 类型 | 默认值 | 说明 | 示例 |
|--------|-----------|------|--------|------|------|
| priority | `-p`, `-priority` | Int | 0 | 任务优先级（数值越小优先级越高） | `-priority -2000` |
| nMaxRetry | `-nmr`, `-nMaxRetry` | Int | 3 | 标记为"gone"前的最大重试次数 | `-nMaxRetry 5` |
| nJitRetry | `-njr`, `-nJitRetry` | Int | 系统默认 | RETRY(1601) 状态时立即重试次数 | `-nJitRetry 2` |

**AI 提示**: 
- `nMaxRetry`: 跨爬虫循环的重试
- `nJitRetry`: 单次抓取操作中的立即重试
- 优先级遵循 Java PriorityBlockingQueue 规则

---

### 10. 认证与安全

| 参数名 | 命令行选项 | 类型 | 说明 | 示例 |
|--------|-----------|------|------|------|
| authToken | `-authToken` | String | 受保护资源的认证令牌 | `-authToken Bearer_xyz123` |
| readonly | `-readonly` | Boolean | 非破坏性模式，防止页面修改 | `-readonly` |

---

## 使用模式

### 模式 1: 简单页面抓取

```kotlin
// 1天缓存抓取
val page = session.load(url, "-expires 1d")

// 强制刷新
val page = session.load(url, "-refresh")

// 带质量要求的抓取
val page = session.load(url, 
    "-expires 1d -requireSize 300000 -requireImages 5"
)
```

### 模式 2: 门户+详情爬虫

```kotlin
// 门户页1天过期，详情页7天过期
// 提取前20个商品链接，每个至少5张图
val pages = session.loadOutPages(url,
    "-expires 1d " +
    "-outLink a.product-link " +
    "-topLinks 20 " +
    "-itemExpires 7d " +
    "-itemRequireImages 5 " +
    "-itemRequireSize 500000"
)
```

### 模式 3: 解析和存储

```kotlin
// 立即解析并存储内容
val page = session.load(url, "-parse -storeContent")

// 解析但不存储大量内容
val page = session.load(url, "-parse -dropContent")
```

### 模式 4: 重试控制

```kotlin
// 允许最多5次重试，忽略之前的失败
val page = session.load(url, 
    "-ignoreFailure -nMaxRetry 5 -nJitRetry 2"
)
```

### 模式 5: 自定义交互

```kotlin
// 滚动10次，间隔2秒，高交互级别
val page = session.load(url,
    "-scrollCount 10 " +
    "-scrollInterval 2s " +
    "-interactLevel HIGH " +
    "-pageLoadTimeout 120s"
)
```

### 模式 6: 任务管理

```kotlin
// 带标签和截止时间的有组织任务
val page = session.load(url,
    "-label Q1-电子产品 " +
    "-taskId task-${UUID.randomUUID()} " +
    "-deadline 2024-01-05T23:59:59Z " +
    "-expires 1d"
)
```

---

## 常见问题与解决方案

### 问题：页面不刷新
**解决方案**: 使用 `-refresh` 或显式设置 `-expires 0s -ignoreFailure`

### 问题：缺少懒加载内容
**解决方案**: 增加 `-scrollCount` 并调整 `-scrollInterval`
```kotlin
"-scrollCount 10 -scrollInterval 2s"
```

### 问题：慢速页面超时
**解决方案**: 增加超时值
```kotlin
"-pageLoadTimeout 180s -scriptTimeout 60s"
```

### 问题：页面不完整（太小）
**解决方案**: 设置质量要求并允许重试
```kotlin
"-requireSize 300000 -requireImages 5 -nMaxRetry 5"
```

### 问题：提取的出链太多
**解决方案**: 用 `topLinks` 限制并用 `outLinkPattern` 过滤
```kotlin
"-topLinks 20 -outLinkPattern .*/product/.*"
```

### 问题：存储增长太快
**解决方案**: 临时分析任务不存储内容
```kotlin
"-dropContent" 或 "-storeContent false"
```

### 问题：任务超过截止时间
**解决方案**: 设置明确的截止时间
```kotlin
"-deadline 2024-01-05T18:00:00Z"
```

---

## 编程使用

### 创建 LoadOptions

```kotlin
// 从字符串创建
val options = LoadOptions.parse("-expires 1d -parse", conf)

// 从会话创建
val options = session.options("-expires 1d -parse")

// 合并选项
val merged = LoadOptions.merge(options1, options2)
val merged = LoadOptions.merge(options, "-refresh")

// 克隆并修改
val newOptions = options.clone()
newOptions.expires = Duration.ofDays(7)
```

### 检查值

```kotlin
// 检查是否过期
if (options.isExpired(lastFetchTime)) {
    // 需要重新抓取
}

// 检查是否过了截止时间
if (options.isDead()) {
    // 放弃任务
}

// 检查解析器是否启用
if (options.parserEngaged()) {
    // 解析器将运行
}
```

### 获取修改的参数

```kotlin
// 仅获取非默认参数
val modifiedParams = options.modifiedParams
val modifiedOptions = options.modifiedOptions

// 转换为字符串（标准化）
val argsString = options.toString()
```

### 详情页选项

```kotlin
// 从门户页选项创建详情页专用选项
val itemOptions = options.createItemOptions()
```

---

## AI 代理最佳实践

1. **从简单开始**: 先使用基本选项如 `-expires` 和 `-refresh`
2. **逐层增加复杂性**: 仅在需要时添加质量要求
3. **增量测试**: 一次添加一个选项以了解效果
4. **使用预设**: 尽可能让 `interactLevel` 处理交互设置
5. **监控性能**: 更高质量 = 更慢性能；需要平衡
6. **利用默认值**: 大多数默认值合理；仅在必要时覆盖
7. **门户/详情模式**: 使用详情页专用选项进行两层爬虫
8. **错误处理**: 适当使用重试选项（`nMaxRetry`, `nJitRetry`）
9. **存储意识**: 对于仅分析任务考虑使用 `-dropContent`
10. **时间格式**: 使用 Hadoop 格式（如 `1d`, `2h`）提高可读性

---

## 快速命令构建器

```kotlin
// 最常见场景的模板
val 基本抓取 = "-expires 1d"
val 强制抓取 = "-refresh"
val 质量抓取 = "-expires 1d -requireSize 300000 -requireImages 5"
val 门户爬虫 = "-expires 1d -outLink CSS选择器 -topLinks 20"
val 完整爬虫 = "-expires 1d -outLink CSS选择器 -topLinks 20 -itemExpires 7d -itemRequireImages 5"
val 解析存储 = "-parse -storeContent"
val 仅解析 = "-parse -dropContent"
val 带重试 = "-ignoreFailure -nMaxRetry 5"
val 带截止 = "-deadline ISO时间戳 -expires 1d"
val 自定义交互 = "-scrollCount 10 -scrollInterval 2s -pageLoadTimeout 120s"
```

---

## API 公开选项

这些选项通过 REST API 暴露并标记为 `@ApiPublic`：

- 任务相关: `entity`, `label`, `taskId`, `taskTime`, `deadline`
- 认证: `authToken`, `readonly`, `isResource`, `priority`
- 缓存: `expires`, `expireAt`, `refresh`, `ignoreFailure`
- 出链: `outLinkSelector`, `outLinkPattern`, `topLinks`
- 质量: `requireSize`, `requireImages`, `requireAnchors`, `requireNotBlank`, `waitNonBlank`
- 详情页: `itemExpires`, `itemExpireAt`, `itemScrollCount`, `itemWaitNonBlank`, `itemRequireNotBlank`, 
  `itemRequireSize`, `itemRequireImages`, `itemRequireAnchors`

完整列表: `LoadOptions.apiPublicOptionNames`

---

## 相关文档

- 用户指南: `/docs/get-started/3load-options.md`
- REST API 示例: `/docs/rest-api-examples.md`
- 概念文档: `/docs/concepts.md`
- 源代码: `/pulsar-core/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/common/options/LoadOptions.kt`
- 英文版本: `/devdocs/copilot/load-options-guide.md`

---

**版本**: 2024-01-05  
**维护者**: Browser4 团队  
**状态**: 活文档 - 随 LoadOptions 演进而更新
