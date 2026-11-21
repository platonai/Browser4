# 将 WebPage 持久层插件化的可行性评估

日期：2026-11-20
范围：`Browser4` 分支 `4.16.x` 的 WebPage 持久层（`WebDb` / Gora `DataStore`）；
依赖 `browser4-base 4.11.32` 的 `pulsar-persist`
性质：**评估（不做实现）**。第 8 节列出本轮实际读过/跑过的内容；没有做的事也写明。

---

## 0. 结论先行

1. **可做，但不是“抽个接口”那么小。** 当前持久层的主类 `WebDb` 在外部依赖
   `pulsar-persist` 里，是**具体类、方法全 final**；`DataStorageFactory` 也是 final，
   `_dataStore` 私有；`Class.forName` 又只看应用 classloader、看不到 `plugins/` 里的 jar。
   因此真正的插件化只有三条路：**加浏览器层接口**、**反射注入**、或**改 base**。
2. **推荐方案 B（浏览器层 `PageStore` SPI）**，但分两步：先做 **8–10 人日**的最小可替换缝，
   默认行为完全不变；确认有第二个后端需求后，再投入 **18–22 人日**做生产级
   （参考后端 + 插件加载验证 + E2E + 文档）。不要一上来做模型去 Gora 化。
3. **四个硬约束必须先拍板**：目标后端是什么、是第三方插件生态还是内部可替换、
   是否必须保持 4.x 二进制兼容、是否需要 native image / 多进程共享。
4. 如果只是“内部换后端”，**方案 A（反射注入 Gora DataStore）8–11 人日**更划算；
   如果能推动 base 发版，**方案 C 5–8 人日 + 1–3 周上游周期**最省核心代码。
5. **不建议现在做方案 D（模型去 Gora 化）**：35–50 人日，触及解析、协议状态、PageModel、
   ActiveDOM 统计和全部测试，收益不成比例。

### 估算总表

| 目标 | 估算 | 交付物 |
|---|---:|---|
| 最小可替换缝（默认行为不变） | **8–10 人日** | `PageStore` 接口 + 默认适配器 + 调用点迁移 + 契约测试 |
| 生产级 SPI（推荐） | **18–22 人日** | 上述 + 参考后端 + 插件加载验证 + E2E + 文档/PDK |
| 内部换后端（反射注入 Gora DataStore） | **8–11 人日** | 注入器 + 参考 `DataStore` + 测试 |
| 改 base classloader + `storage.data.store.class` | **5–8 人日 + 上游发版周期** | base 一行补丁 + 浏览器侧配置/文档 + 参考 `DataStore` |
| 仅双写外部存储（不替换读路径） | **1–2 人日** | `LoadEventMount` 的 `handlers.onLoaded` 插件，零核心改动 |
| 模型去 Gora 化 | **35–50 人日（不建议）** | 存储中立 DTO + 映射 + 全链路迁移 |

> 置信度约 ±30%。最大变量是目标后端（SQLite / PostgreSQL / S3 / Redis 相差 2–4 人日）、
> 兼容性要求（±1–2 人日）和 native / 多进程要求（各 +3–5 人日）。
> 以上为一名熟悉本仓库的工程师的人日；不熟悉仓库时按 +30% 计。

---

## 1. 评估方法与边界

- 读本仓库：`LoadComponent`、`BatchFetchComponent`、`AbstractPulsarContext`、
  `AbstractPulsarSession`、`StreamingTaskRunner`、`CoreMetrics`、`Browser4AutoConfiguration`、
  `PluginManager` / `PluginClasspathEnhancer` / `PluginCompatibility`、PDK BOM 与 archetype。
- 读 base 源码（`platonai/browser4-base` 4.11.x 分支 + `pulsar-persist-4.11.32.jar`，`pulsar-core/pulsar-persist`）：
  `WebDb`、`DataStorageFactory`、`GoraStorage`、`FileBackendPageStore`、`GoraWebPage`、
  `webpage.avsc`、`gora.properties`。
- 用 `javap` 核对 `WebDb` / `DataStorageFactory` / `MemStore` / Gora `DataStore` 的方法签名的
  `final` 性与字段可见性（结论见 §3.1）。
- 解码一条真实 `.avro` 页面记录，确认持久化字段、metadata map、headers、DOM 统计的实际内容
  （见 §2.6）。
- 检查本机 `~/.browser4/data/store` 的组成（190 个 `.avro`、115 个 `.html`、76 个历史 `.htm`，
  约 302 MB）。
- **未做**：没有改任何代码；没有做 spike；没有起 MongoDB / 插件 jar 做真实加载；
  没有 benchmark 后端读写延迟；没有跑 E2E。第 8 节逐项列明。

---

## 2. 当前实现（事实）

### 2.1 模型：`WebPage` → `GoraWebPage` → `GWebPage`

```
WebPage (interface, 业务面)
  └─ GoraWebPage (Kotlin wrapper)
       └─ GWebPage (Avro/Gora 生成的持久化记录，63 字段)
```

- Schema：`pulsar-core/pulsar-persist/src/main/resources/avro/webpage.avsc`。
- 持久化字段覆盖抓取调度（`fetchTime`/`fetchInterval`/`fetchCount`/`fetchRetries`/`batchId`）、
  协议与解析状态、`content`（bytes）、页面模型、链接图、`metadata`/`headers` map、
  `activeDOMStatTrace` 等。
- `location`/`href`/`fetchMode`/`maxRetries` 不是独立 Avro 字段，而是 `metadata` map 里的
  `F_LOCATION`/`F_HREF`/`F_MD`/`F_MR`；`args` 存在 `params`。
- **非持久化**：`variables`、`data`、`tmpContent`、`conf`、`id`/`uuid`、
  `isCached`/`isLoaded`/`isFetched`/`isCanceled`、`pageDatum`、绑定的 bean。
- `clearPersistContent()` 会把 `content` 移到 `tmpContent` 并把持久化字段置空，
  用于“解析需要内容、但不落大字段”的场景。

### 2.2 存储入口：`WebDb`

```kotlin
// pulsar-core/pulsar-persist/.../WebDb.kt:23
open class WebDb(val conf: ImmutableConfig) {
    val dataStorageFactory = DataStorageFactory(conf)   // 内部自己 new
    private val dataStore get() = dataStorageFactory.getOrCreatePageStore()

    // :166
    fun put(page: WebPage, replaceIfExists: Boolean = false) = putInternal(page, replaceIfExists)

    // :169 / :189
    private fun putInternal(page: WebPage, replaceIfExists: Boolean): Boolean {
        val key = page.key                  // 反向 URL
        require(page is GoraWebPage)
        dataStore.put(key, page.unbox())    // 底层 GWebPage
    }
}
```

- Spring 单例 bean：`Browser4AutoConfiguration.kt:54-57`，`@Bean(name = ["webDb"], destroyMethod = "close")`。
- key = `URLUtils.reverseUrl(url)`，例如 `uk.co.amazon.www:https/dp/B0E000001`；
  schema 默认 `webpage120`，可用 `storage.crawl.id` 做前缀隔离。
- 读：`getOrNull`（:98）→ `dataStore.get(key, fields)` → `GoraWebPage.box(...)`，设置 `isLoaded = true`。
- 写：`put`（:166）→ `dataStore.put(key, page.unbox())`。
- `scan` / `query` 在 base 里是 `NotImplementedError()`，不是真实能力。

### 2.3 后端：文件后端是实际默认

`DataStorageFactory.detectDataStoreClassName()`（`DataStorageFactory.kt:64`）的选择顺序：

1. 构造参数显式传入的 class
2. 配置 `storage.data.store.class`
3. 操作系统 + 进程探测：本机有 `mongod` 进程 → `MongoStore`，否则 → `FileBackendPageStore`
4. 目标后端不健康时 fallback 到文件后端

**文件后端**（`FileBackendPageStore.kt:42,54,59,60`）：

```
内存：static ConcurrentSkipListMap（进程级）
磁盘：AppPaths.LOCAL_STORAGE_DIR/<host>/<host>-<md5(url)>.avro
                                        ...-<md5(url)>.html
```

- `put`：先 `super.put` 更新内存，再 `writeAvro`（重写整个文件）和 `writeHtml`（内容镜像）。
- `get`：内存 map → `.avro` → `.html` fallback（`.avro` 不存在时合成一个“永不过期”的成功页）。
- 方法带 `@Synchronized`，单实例内写入串行；`MemStore.flush()` 是空实现，`close()` 也是空实现。
- `MemStore.map` 是 **static**，不随实例关闭释放；`truncate` 只清 map，不删磁盘文件。

**Mongo 后端**：代码里保留了 `MongoStore` 选择逻辑（Gora，db 默认 `webpage120`，
映射文件 `pulsar-persist-mongo/src/main/resources/gora-mongodb-mapping.xml`），
但**当前仓库依赖里没有 `pulsar-persist-mongo` / `gora-mongodb`**，`pulsar-persist-4.11.32.jar`
里也没有 Mongo 类。`docker-compose.yml` 起的 `mongod` 在另一个容器里，进程探测也看不到。
换句话说，**在这个仓库的实际发布物里，文件后端是唯一可用的后端**；Mongo 分支更像上游保留能力。

### 2.4 写入链路

```
session.load / open / capture
  └─ LoadComponent.load
       ├─ createPageShell：内存 pageCache (GlobalCache/Caffeine) → webDb.getOrNull (:364)
       ├─ fetchContent（需要时，经 CDP/HTTP）
       ├─ onLoaded：pageCache.putDatum → parse（可选）→ persist (:688)
       └─ persist：
            ├─ options.dropContent || !options.storeContent → clearPersistContent()
            ├─ 缓存命中 → 清 content 的 dirty 标记
            ├─ webDb.put(page) (:703)
            └─ flush 启发式：numWrite < 200 每次都 flush，否则按 lazyFlush / 每 20 次
```

- 开关：`options.persist`（`LoadOptions.kt:478`，默认 true）、`options.readonly`、
  `page.isCanceled`；`LoadOptions.kt:490` 的 `storeContent`、`:511` 的 `dropContent`
  决定是否把内容落库。
- 其他显式入口：`session.persist(page)` → `context.webDb.put(page)`
  （`AbstractPulsarSession.kt:771`）、`context.persist(page)`（`AbstractPulsarContext.kt:534`）。
- 指标：`WebDb.dbPutCount` / `dbPutAveMillis`，`CoreMetrics.kt:67-72` 读静态计数器。

### 2.5 读取链路

1. `LoadComponent.createPageShell` 先查内存 `pageCache`，受 `-expires` / `-refresh` 控制；
2. 未命中 → `webDb.getOrNull(normURL.urlString)`（`LoadComponent.kt:364`）；
3. `DataStore.get(key)`：文件后端是内存 map → `.avro` → `.html` fallback；
4. `GoraWebPage.box(url, page, conf)` 包装，`isLoaded = true`；
5. `initFetchState()` 根据 `protocolStatus`/`prevFetchTime`/`persistedContentLength`/DOM 统计决定是否重抓；
6. 需要时 fetch → parse → 回到 §2.4 的 persist。

### 2.6 一条真实记录（本机样本）

`~/.browser4/data/store/example-com/example-com-182ccedb...avro` 解码后：

```
baseUrl          = https://example.com/
fetchCount       = 1
params           = -parse
browser          = PULSAR_CHROME
protocolStatus   = {majorCode=1, minorCode=200}
content          = 2258 bytes
contentLength    = 2258
metadata.F_LOCATION = https://example.com/
metadata.F_MD       = BROWSER
metadata.F_MR       = 3
headers.Content-Length = 2258
activeDOMStatTrace.lastStat = {ni=0, na=1, w=1728, h=1034}
```

同一 URL 的 `.html` 镜像也是 2258 字节。`.avro` 是权威数据，`.html` 只是内容副本；
旧版本写出的 `.htm` 当前代码不再读取。

---

## 3. 插件化的四个硬约束

### 3.1 `WebDb` 是具体类，方法全 final

`javap` 结果（`pulsar-persist-4.11.32.jar`）：

```
public class ai.platon.pulsar.persist.WebDb implements java.lang.AutoCloseable {
  public final java.lang.String getSchemaName();
  public final boolean canConnect();
  public final WebPage getOrNull(...);
  public final boolean exists(...);
  public final WebPage get(...);
  public final boolean put(...);
  ...
}
public final class ai.platon.pulsar.persist.DataStorageFactory {
  private ai.platon.gora.store.DataStore _dataStore;
  ...
}
```

`WebDb` 虽然声明为 `open class`，但方法都是 final，**子类无法覆盖行为**；
`DataStorageFactory` 是 final class，`_dataStore` 私有，`WebDb` 在构造时自己 `new` 它，
没有 setter 或构造注入。因此：

- 不能写 `class MyWebDb : WebDb()` 替换读写；
- 不能通过 Spring 注入自定义 `DataStore`；
- `webDb` bean 只能是真正的 `WebDb` 实例。

### 3.2 `Class.forName` 看不到插件 jar

```kotlin
// DataStorageFactory.kt:109
return Class.forName(detectDataStoreClassName(dataStoreClassName, conf)) as ...
```

单参 `Class.forName` 使用**定义类的 classloader**（应用 fat jar 的 loader），不是 TCCL。
而插件 jar 是 `PluginClasspathEnhancer` 放进子 `URLClassLoader` 并设为 TCCL 的：

```kotlin
// PluginClasspathEnhancer.kt:79-81
val loader = URLClassLoader(jarUrls.toTypedArray(), currentLoader)
enhancedLoader = loader
Thread.currentThread().contextClassLoader = loader
```

结论：**插件里实现一个 Gora `DataStore`，再用 `storage.data.store.class` 指定它，默认找不到类。**
要让这条路成立，必须改 base 的 `Class.forName(..., TCCL)`，或改用浏览器层接口让 Spring 直接实例化插件 bean。

### 3.3 模型绑死 `GoraWebPage`

`LoadComponent` 里有两处硬性 `require`：

```kotlin
// LoadComponent.kt:341, :698
require(page is GoraWebPage)
page.unbox().clearDirty(GWebPage.Field.CONTENT.index)
```

扩展属性也一样：

```kotlin
// .../skeleton/common/persist/ext/WebPageEx.kt:20
require(this is GoraWebPage)
```

所以 SPI 只能是“**存储可替换**”，不是“模型可替换”：插件 `get()` 最终必须构造
`GoraWebPage`（内部包 `GWebPage`）。这可行，但插件作者必须依赖 `pulsar-persist`、
理解 Avro 记录；真正的模型解耦是方案 D 的量级。

### 3.4 公开 API 兼容性 + 其他耦合

`PluginCompatibility.kt:11-13` 的契约是：**同一个 major 内二进制兼容**。以下都是公开面：

- `LoadComponent.webDb: WebDb`（`LoadComponent.kt:54`）
- `BatchFetchComponent.webDb: WebDb`（`:10`）
- `UpdateComponent.webDb: WebDb`（`:18`）
- `AbstractPulsarContext.webDb: WebDb`（`:154`，另有 `:77` 的私有 `webDbOrNull`）
- 子类覆盖：`AgenticContext.kt:142`、`GenericPulsarContext.kt:26`、`StaticPulsarContext.kt:31`

直接把类型改成 `PageStore` 会破坏 4.x 插件；必须走“新增 `pageStore`、保留 `webDb`”的加法路线，
或者接受一次 major 升级。

还有两个容易被忽略的耦合：

- **`StreamingTaskRunner.kt:1075`**：WebDB 失联等待循环读 `context.webDb.canConnect()`。
  插件模式下如果不改，`webDb` 没有被使用，`canConnect()` 可能永远 false，循环不会退出。
- **`CoreMetrics.kt:67-72`**：直接读 `WebDb` 的静态计数器；插件 store 绕过 `WebDb` 后指标会归零，
  需要 `PageStoreStats` 或类似的桥接。

---

## 4. 方案对比

| 方案 | 做法 | 核心改动 | 人日 | 主要风险 |
|---|---|---|---:|---|
| **A. 反射注入 Gora `DataStore`** | 插件提供 `DataStore<String, GWebPage>`；核心创建 `WebDb` 后反射设置 `DataStorageFactory._dataStore` | 4–5 个文件 | **8–11** | 依赖 base 私有字段，base 升级易碎；不是公开契约 |
| **B. 浏览器层 `PageStore` SPI** | 新接口 + 默认适配器 + provider；插件实现 `PageStoreFactory` | 10–12 个文件 | **18–22**（最小 **8–10**） | 工作量大；契约测试要求高；仍需 `GoraWebPage` |
| **C. base 补 TCCL** | 上游把 `Class.forName` 改为 TCCL；浏览器侧只加配置/文档；插件实现 Gora `DataStore` | base 1 行 + 浏览器配置 | **5–8 + 上游周期** | 受 base 发版约束；仍需实现 Gora `DataStore` |
| **D. 模型去 Gora** | 存储中立 DTO + 映射层 + SPI | 全仓 | **35–50** | 高风险；收益不成比例 |
| **E. 仅双写** | 插件在 `LoadEventMount.configureLoadHandlers` 里挂 `handlers.onLoaded`，把页面复制到外部存储；读路径不变 | 0 | **1–2** | 只写不读；不是真正的可替换持久层 |

### 4.1 方案 A（反射注入）细节

插件接口建议：

```kotlin
interface GoraPageDataStoreFactory {
    val name: String
    fun create(conf: ImmutableConfig): DataStore<String, GWebPage>
}
```

核心在 `webDb` bean 创建后：

1. 读 `persistence.dataStore` 选择 factory；
2. `factory.create(conf)` 得到 `DataStore`；
3. 反射设置 `DataStorageFactory._dataStore`；
4. 字段缺失或类型不符时 **fail loud**，带 base 版本信息，不静默回退。

优点：调用点零改动，`WebDb` 的统计、`canConnect`、`flush`、`close` 全部保留；
`WebDb.close()` 会因为 `dataStorageFactory.isInitialized()` 为 true 而关闭注入的 store。
缺点：反射依赖私有字段名；不适合作为第三方公开 API，只适合“内部/第一方后端”。

### 4.2 方案 B（`PageStore` SPI）细节

建议接口（放在 `browser4-skeleton`，PDK plugins 已经依赖它）：

```kotlin
interface PageStore : AutoCloseable {
    val name: String
    fun getOrNull(url: String, fields: Set<String> = emptySet()): WebPage?
    fun getContent(url: String): ByteBuffer?
    fun exists(url: String): Boolean
    fun put(page: WebPage, replaceIfExists: Boolean = false): Boolean
    fun delete(url: String): Boolean
    fun flush()
    fun canConnect(): Boolean
    fun close()
}
```

默认适配器：

```kotlin
class GoraWebDbPageStore(val webDb: WebDb) : PageStore {
    override val name = "gora"
    // 空 fields 表示全部字段；必须传 null 给 WebDb，
    // 传空数组会得到空页面（base 的坑，见 §6）
    override fun getOrNull(url: String, fields: Set<String>) =
        webDb.getOrNull(url, false, fields.takeIf { it.isNotEmpty() }?.toTypedArray())
            ?.also { it.isLoaded = true }
    override fun put(page: WebPage, replaceIfExists: Boolean) = webDb.put(page, replaceIfExists)
    // ...
}
```

装配（**用 provider，不要依赖自动配置顺序**）：

```kotlin
@Bean(name = ["pageStore"], destroyMethod = "close")
fun pageStore(
    conf: MutableConfig,
    webDb: WebDb,
    factories: ObjectProvider<PageStoreFactory>,
): PageStore {
    val name = conf.get("persistence.store", "gora")
    if (name == "gora") return GoraWebDbPageStore(webDb)
    return factories.orderedStream().firstOrNull { it.name == name }
        ?.create(PageStoreContext(conf, AppPaths.DATA_DIR, "webpage120"))
        ?: error("Persistence store '$name' not found; known: ...")
}
```

插件：

```kotlin
@AutoConfiguration
class MyStoreAutoConfiguration {
    @Bean fun myStoreFactory(conf: MutableConfig): PageStoreFactory = ...
}
```

关键设计点：

- **保留 `webDb` bean 和 `webDb` 属性**；新增 `pageStore`。`AbstractPulsarContext.pageStore`
  默认取 bean，取不到就 `GoraWebDbPageStore(webDb)`，所以 `AgenticContext` /
  `GenericPulsarContext` / `StaticPulsarContext` 不必逐个改。
- `LoadComponent` 主构造器加 `pageStore: PageStore = GoraWebDbPageStore(webDb)`，
  同时保留旧签名的 secondary constructor，保证二进制兼容。
- `StreamingTaskRunner` 改 `context.pageStore.canConnect()`。
- `CoreMetrics` 加 `PageStoreStats` 桥接，避免插件后端指标归零。
- 插件返回的 `WebPage` 必须是 `GoraWebPage`，且每次 `get` 返回独立对象
  （`LoadComponent` 会改 `isCached`/`tmpContent`/`fetchTime`，共享实例会串数据）。
- `fields` 语义：**空集合 = 全部字段**；适配器转成 `null` 传给 `WebDb`。

---

## 5. 推荐方案 B 的 WBS

| # | 工作包 | 人日 |
|---|---|---:|
| 1 | SPI 设计评审：方法集、key/字段语义、线程安全、失败语义、兼容策略 | 1.0 |
| 2 | `PageStore` / `PageStoreFactory` / `PageStoreContext` / 默认适配器 | 1.5 |
| 3 | 调用点迁移：LoadComponent、BatchFetch、Update、AbstractPulsarContext、TrivialContextDefaults、AbstractPulsarSession、StreamingTaskRunner、Browser4AutoConfiguration；保留旧构造器 | 2.5 |
| 4 | Spring 装配：provider、`persistence.store` 配置、lazy/生命周期、fail-loud | 1.0 |
| 5 | 指标与兼容：`CoreMetrics` 桥接、`webDb` 兼容/弃用说明 | 1.0 |
| 6 | 契约测试：`PageStoreContractTest` + 默认适配器 parity + provider 单测 + 并发/失败注入 | 3.0 |
| 7 | 参考插件（建议 SQLite 或 JSONL；S3/Postgres 另加 2–3 天） | 3.0 |
| 8 | 插件加载验证：`PluginClasspathEnhancer` + Spring 发现 + 类加载冲突测试 | 1.5 |
| 9 | E2E：mock site crawl → `webdb export` → 重启后读回；插件 store 版本 | 2.0 |
| 10 | 文档：`docs/config.md`、PDK/archetype 示例、设计文档、release notes | 1.5 |
| 11 | 缓冲/评审/CI 修复 | 2.5 |
| **合计** | | **20.5 → 18–22 人日** |

**最小可用版本**（1–6 + 少量缓冲）= **8–10 人日**：默认仍走文件后端，插件只是可选 bean。
**生产级** = 上表全量；建议两人并行（核心 + 参考后端），约 2.5–3 周 + 评审。

---

## 6. 风险与语义陷阱

1. **可变对象别名**：文件后端 `MemStore.map` 是 static，`get` 返回同一个可变 `GWebPage`；
   插件 store 如果缓存对象，`LoadComponent.clearPersistContent()` 会把内容清掉。
   契约必须要求“每次返回独立实例”，并写进测试。
2. **`fields` 空数组坑**：`WebDb.getOrNull(url, false, emptyArray())` 会得到空页面
   （`MemStore.getPersistent` 会 clear 所有字段）。适配器必须把“空 = 全部”转成 `null`。
3. **`canConnect` 死等**：`StreamingTaskRunner.kt:1075` 的失联循环在插件模式下如果不改，
   会一直等待。插件 store 的 `canConnect()` 必须是真实探测，不能永远返回 false。
4. **内容清空语义**：`-dropContent` / `-storeContent false` / 缓存命中路径都会动
   `content`/`dirty`；插件 store 不能假设“put 的内容一定非空”，也不能假设
   “上次写入的内容还在”。
5. **指标归零**：`CoreMetrics` 读 `WebDb` 静态计数器；插件 store 下会归零。
   要加 `PageStoreStats`，否则线上看不到 put/get 延迟。
6. **数据迁移**：默认后端不变则无需迁移；一旦把插件设为默认，需要
   `.avro` → 新后端的导入工具（+2–3 人日）。
7. **多进程/事务**：文件后端是单进程假设；插件 store 若宣称共享存储，要定义并发、
   读写一致、`flush` 持久化语义。`put` 之后必须 read-your-writes。
8. **native image**：`browser4-standalone` 有 GraalVM native profile；`plugins/` 动态加载
   在 native image 下不能按 JVM 方式工作。持久化插件若要在 native 下使用，需要静态编入
   并补 runtime hints（+3–5 人日）。
9. **安全**：插件 store 能看到所有页面内容；现有插件模型是“信任进程内代码”，没有沙箱。
   需要在文档里明确。
10. **性能**：`put` 是同步的；网络后端（S3/Postgres）会让 load 协程阻塞。
    契约里要写清 `flush` 与 batching 的语义，必要时在插件内部做异步写。
11. **公开 API 兼容**：`LoadComponent`/`BatchFetchComponent`/`UpdateComponent` 的构造器
    变更必须保留旧签名，否则违反 4.x 同 major 二进制兼容契约。

---

## 7. 验证清单

```bash
# 编译
./mvnw -q -pl browser4-core/browser4-skeleton -am -DskipTests

# 核心单测
./mvnw test -pl browser4-core/browser4-skeleton -am

# 插件加载
./mvnw test -pl browser4-boot -am -Dtest='Plugin*Test'

# REST/webdb 契约
./mvnw test -pl browser4-rest -am -Dtest='WebDb*Test,CrawlSupportTest'

# E2E（需要浏览器/后端）
bin/test.ps1 e2e
```

新增测试至少覆盖：

- `put → get` 63 个字段往返（含 `content=null`、metadata map、headers、DOM stat）；
- `fields` 投影、`delete`、`exists`、`flush`、`close`、`replaceIfExists`；
- 并发 put/get；
- store 抛异常 → CLI 拿到明确错误，不静默回退；
- 插件 jar 经 `PluginClasspathEnhancer` 加载后 bean 类型正确、无 `ClassCastException`；
- 默认文件后端行为零变化（golden test）；
- `canConnect() == false` 时 `StreamingTaskRunner` 不会死循环（超时断言）。

性能上，插件 store 不应让 `get/put` 的 P50 比文件后端差 5% 以上；
至少记录 `dbPutAveMillis` / `dbGetAveMillis` 基线。

---

## 8. 本轮实际做过的事 / 未做

**做过**：

- 读本仓库的 `LoadComponent` / `AbstractPulsarContext` / `AbstractPulsarSession` /
  `StreamingTaskRunner` / `CoreMetrics` / `Browser4AutoConfiguration` /
  `PluginManager` / `PluginClasspathEnhancer` / `PluginCompatibility` / PDK BOM / archetype；
- 读 base 4.11.x 的 `WebDb` / `DataStorageFactory` / `GoraStorage` / `FileBackendPageStore` /
  `GoraWebPage` / `webpage.avsc` / `gora.properties`；
- `javap` 核对 `WebDb`、`DataStorageFactory`、`MemStore`、Gora `DataStore` 的 `final` 性与字段可见性；
- 解码本机一条真实 `.avro` 页面记录，核对字段内容；
- 统计本机 `~/.browser4/data/store` 的文件组成。

**未做**：

- 没有修改任何代码，没有建分支或 worktree；
- 没有做 1 天 spike，没有写 `PageStore` 原型；
- 没有起 MongoDB 或插件 jar 做真实加载验证；
- 没有对任何后端做读写 benchmark；
- 没有跑 E2E 或 `bin/test.ps1`；
- 没有验证 native image 下的插件行为。

---

## 9. 待拍板的问题与建议

1. **目标后端是什么？** SQLite / PostgreSQL / S3 / Redis，还是“先留一个缝”？
   这直接影响参考插件 3–5 人日。
2. **是第三方插件生态，还是内部可替换？**
   第三方 → 方案 B 的公开契约 + 文档；内部 → 方案 A 更划算。
3. **是否必须保持 4.x 插件二进制兼容？**
   是 → `LoadComponent` 等构造器保留旧签名（+1 人日）；否 → 可以走 major 升级。
4. **是否需要 native image / 多进程共享？**
   native +3–5 人日；多进程一致性 +3–5 人日。

### 建议的决策路径

| 场景 | 建议 | 估算 |
|---|---|---:|
| 只是内部换后端 | 方案 A（反射注入 Gora `DataStore`） | 8–11 人日 |
| 要做第三方插件生态 | 方案 B，先交付最小缝，再排参考后端 | 8–10 → 18–22 人日 |
| 能推动 base 发版 | 方案 C（base 补 TCCL） | 5–8 人日 + 1–3 周 |
| 只要把页面复制到外部存储（不替换读） | 方案 E（`LoadEventMount` 的 `handlers.onLoaded`） | 1–2 人日 |
| 想要模型中立 | 方案 D | 35–50 人日，暂不建议 |

**下一步**：先做 **1 天 spike**——只加 `PageStore` 接口 + `InMemoryPageStore`，
只改 `LoadComponent` 一处，跑现有 skeleton 测试。如果全绿，说明扩展点成立，
再按 §5 立项；如果不绿，退回方案 A 或 C。
