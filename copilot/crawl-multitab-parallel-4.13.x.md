# crawl 多标签页并行化采集（4.13.x）

> 目标：`crawl` 命令真正按标签页并行采集，而不是"看起来并发、实际排在一个 tab 上"。
> 关联：#592、`docs-dev/copilot/ci-stabilization-4.13.x.md` §15。

## 1. 问题

`crawl` 一直**并发提交**，但整场抓取只驱动一个标签页，被 `DriverLeaseRegistry` 按
driver 串行化。§15.1 的探针结论是：守卫拒绝 234+ 条**全部指向同一个 driver id**，
多个 worker 线程在 2ms 内同时报它。

§15.3 的收尾note已经点明下一步：

> crawl 想并行需要**不绑定会话驱动**、改为按 tab 从驱动池租用……这属于下一步的吞吐优化。

## 2. 根因链（本仓代码，可复现）

1. 一个 URL 被 `session.submit()` 交给**全局 URL 池**，由 `StreamingTaskLoop` 消费；
   因此抓取用的是**循环自己的会话**，不是提交它的那个会话。
2. 循环原先用 `cx.sessions[label=="SWARM"] ?: cx.getOrCreateSession()`。后者是上下文
   默认会话，`open`/`goto` 会**绑一个 driver** 到它上面。
3. `PrivacyManagedBrowserFetcher.getSpecifiedWebDriver(page)` 先看页面/会话上有没有
   "指定驱动"（`page.conf` 继承 `sessionConfig`）——命中就**直接 fetch，绕过驱动池租约**。
4. 于是该会话的**所有** fetch 共用一个 tab；`DriverLeaseRegistry` 使它们互斥。
   拒绝从 282 降到 0 的代价，就是并行度从"多 tab"降到"单 tab"。

## 3. 修复

### 3.1 抓取会话不得拥有标签页（`StreamingTaskLoop`）

`resolveFetchSession()` 取代原来的会话选择：

* SWARM 会话**且未绑定 driver/browser** 时复用（swarm 的并行靠它）；
* 否则复用/新建带 `FETCH` 标签的**无标签页会话**。

"设计上无标签页"不够——tab 是**懒绑定**的（swarm 会话上一次 `open` 就会绑），所以
判定条件是 `boundDriver == null && boundBrowser == null`，命中时打 INFO 说明为什么不用它。
调用方（例如某一轮 crawl）创建的会话**绝不收养**：那一轮结束就会 close 它。

### 3.2 采集单元并行（`CrawlService`）

一轮 crawl 是一组**互相独立的单元**：`depth=0` 时一个种子 = 一个单元；`depth>=1` 时
一个种子 = 一轮链接发现（它自己的出链再由共享驱动池并行抓）。原先只有 `depth=0`
并发，且共用**一个被绑定的**驱动。

现在：

* `parallelTabs` 预算统一作用于所有单元（`mapCrawlSeedsConcurrently` 的许可数）；
* `depth=0` 的共享会话**不绑驱动**，每次 load 各自从驱动池租 tab；
* `parallelTabs=1` 保留历史严格串行路径（含 `SEED_INTERVAL_MS` 间隔）；
* 结果仍按种子下标落位，所以**列表顺序确定**，与完成顺序无关。

### 3.3 让并行度可观测、可验证

* `CrawlRequest.parallelTabs`（CLI `--parallel <n>`，默认 4，上限 32）；
* `CrawlResponse.parallelTabs`（**生效后**的预算，钳制后的值）+ `maxConcurrentFetches`
  （实测**峰值在飞单元数**）；
* CLI 提交时打印预算，完成时打印"预算 N / 峰值 M"，`M<=1` 且单元数>1 时**明确点出
  "nothing overlapped"**——串行化的"并行"crawl 不再只是"慢"。

钳制而非拒绝：请求超上限时服务端给 400（说得清），配置的默认值超上限时静默钳制并在
响应里报出真实值。

## 4. 验证（真机、真浏览器）

新增 MockSite 并发探针 `ConcurrencyProbeController`：`GET /__probe/slow/{id}?delayMs=N`
占住一个 Tomcat worker N 毫秒并把并发高水位记进 `/__probe/stats`。**服务端看到的高水位**
才是"多标签页"的判据——页面级计时分不清"4 个 tab 并行"和"4 个 tab 依次、每个都很快"。

`CrawlParallelTabsTest`（`@Tag("IntegrationTest")`，本机 20 核 Windows + Chrome）：

| 用例 | 预算 | 服务端高水位 | 实测耗时 | 结果 |
|---|---|---|---|---|
| 4 个 1.5s 慢页 | `parallelTabs=1`（对照） | **1** | ~28s（4 × ~4.5s） | 4/4 页，peak 1 |
| 4 个 1.5s 慢页 | `parallelTabs=4` | **4** | **~4.6s** | 4/4 页，peak 4 |

日志原文：

```
Crawl task a74de250-... completed: 4 pages, 0 lost, status OK, parallel budget 1 (peak 1 in flight)
Crawl task e5a2194f-... completed: 4 pages, 0 lost, status OK, parallel budget 4 (peak 4 in flight)
```

对照用例是**关键**：它证明并行用例测的是这次 crawl，而不是背景噪声。零丢页说明并行
没有把 #592 的"少两页还报 OK"带回来。

`depth>=1` 的并发轮次另有一条：两个种子（hub + electronics 分类）在 `-d 1 --parallel 2`
下**同时**提交同一批出链（product/1、product/2 被两轮各提交一次）：

```
Crawl 1b903905-...: seed URL 2/2 completed: .../category/electronics.html → 4 page(s), 0 lost
Crawl 1b903905-...: seed URL 1/2 completed: .../index.html                → 3 page(s), 0 lost
Crawl task 1b903905-... completed: 7 pages, 0 lost, status OK, parallel budget 2 (peak 2 in flight)
```

零丢页说明"某个 URL 被两轮同时提交时，会不会有一轮永远等不到 parse 事件"这个最担心的
回归没有发生（日志里 product/1、product/2 各被两个轮次抓取并各自核销）。

`CrawlParallelTabsTest` 5/5 通过（含继承自 `RestAPITestBase` 的一条），135s。

单测：`CrawlSeedSchedulerTest`（调度器：上限不被突破、确实重叠、输入序保持）、
`CrawlParallelBudgetTest`（预算解析/钳制/默认值、`copy` 保留预算、响应字段往返），16/16 通过。
Rust 侧 13 条新用例覆盖 `--parallel` 的校验与 `parallel → parallelTabs` 映射，
`cargo test --bin browser4-cli` 1208 条全绿。

## 5. 已知边界

* **驱动池才是硬上限**（`browser.context.number` × `browser.max.active.tabs`，
  默认 2×8）。预算高于池容量时峰值会低于预算——响应里两个数字都在，差值就是证据。
  池队列容量在池创建时就固定了，改配置**不能**给已存在的池扩容。
* `depth>=1` 的预算是**轮次数**，不是页数：一轮内部的出链由池按 tab 并行。
* 多轮共享同一批出链时，同一个 URL 会被两轮各抓一次并各记一行（历史行为，本仓
  `testBackToBackCrawlsLoseNoPages` 早已覆盖同类并发提交）。并发轮次有没有为此**挂住**，
  由 `testParallelLinkDiscoveryRoundsLoseNoPages` 守。
* `CrawlRequest`（主构造器带 `@JsonCreator`）无法被**非 Spring** 的 Jackson mapper
  处理（"Conflicting property-based creators"，与本次改动无关：全默认参数的 data class
  本来就会生成带同名注解的无参构造器）。这是为什么 `parallelTabs` 的**线上字段名**由
  Rust 侧映射用例 + 真实 HTTP 集成用例两端固定，而不是靠 DTO 单测。
