# Agent Run 复盘：Amazon Top-20 采集为什么手写脚本，内置批量工具能否更好

- 日期：2026-10-29（v2：修正渲染上下文归因、补齐 profile 事实、纠正 numchars/numlinks/height 判断）
- 复盘对象：`logs/agent-run-example-trace.log`（task `6eca52ae-3e18-403e-a541-951cd7f700e1`）
- 运行产物：`~/.browser4/workspaces/Browser4-4.15-03e522fa/app-data/agent/2026/10/29/3339c9fb-db05-4cfa-8a75-1723972554a4/workspace.6eca52ae-3e18-403e-a541-951cd7f700e1/`
- 实测环境：`browser4-cli` 4.15.2（release 构建），server `http://localhost:8283`，`HEADLESS`，运行时 bundle 4.15.2-SNAPSHOT（`browser4-swarm` 插件已装）
- 结论一句话：**内置 `crawl --seed-file + --sql + --format csv` 在机制上完胜手写循环（202s vs 555s，1 条命令 vs 40 次子进程）；前提是批量采集前把 Amazon 的 geo/language 选在批量工具会用的那个 browser profile 里——否则同一 URL 会渲染成不同站点，价格/库存列静默失真。**

---

## 0. TL;DR

| 维度 | 内置批量工具（`crawl` / `swarm query`） | 手写 `goto` + `htmlsnapshot export` + python |
|---|---|---|
| 命令数 | 1 条 | 40 次 `browser4-cli` 子进程（20 goto + 20 export） |
| 20 页墙钟耗时 | **202s**（`--parallel 4`，实测） | **≈555s**（分 5 批串行，trace 实测） |
| 改查询后重跑 | **72s**（`--expires 1h` 走页库，实测） | 重新 `goto` + 重新 export（≈555s）或重跑本地解析 |
| 中间数据 | 直接出 CSV 文件 | 52.8MB 注释版 HTML（每页 2.6–3.0MB）+ 26 个 `.py` + 2 个 `.js` |
| 上下文预算 | 一条命令的结果摘要 | 105 步 / 2.03M tokens |
| 结构化输出 | `--format csv/json/table`、`-o` 落盘 | 自己写解析器（本次 `parse.py` 433 行） |
| 失败语义 | 部分失败 exit 6、`failedPages` 逐页原因、checkpoint + `crawl resume` | 逐页 print，失败要人工重跑，无断点 |
| 关键前提 | **crawl 用的是 `SEQUENTIAL` 池里的新 profile，与 live 会话不是同一个上下文**；必须先预热 geo/language | 复用 live 会话的 profile，本次正好是预热过的那个 |

---

## 1. trace 实际做了什么

`scrape.py` 全文 19 行，本质是「每个 ASIN 起两次 CLI 子进程」：

```python
for a in asins:
    r1 = subprocess.run(['browser4-cli', 'goto', url], ...)              # 20 次
    r2 = subprocess.run(['browser4-cli', 'htmlsnapshot', 'export', out,
                         '--timeout', '120s'], ...)                      # 20 次
```

时间线（trace 时间戳）：

| 阶段 | 时间 | 说明 |
|---|---|---|
| 输入表解析 | 0:00 → 1:17 | 读 `asin_field_description.xlsx` 得到 75 字段（7 个 python 脚本） |
| 榜单发现 + 首页 | 1:17 → 1:52 | `open` 畅销榜、2 次 `eval` 想批量取链未果、首页 33s |
| 探针/解析开发 | 1:52 → 2:45 | `probe.py` / `dump.py` / `parse.py` 等 |
| 抓取循环 | 2:45 → 11:33 | `scrape.py` 四批：5 页 99.1s、5 页 138.0s、5 页 159.4s、4 页 125.5s |
| 解析/QA/出表 | 11:33 → 14:35 | `parse.py`、`enrich.py`、`qa*.py`、`final.py`、xlsx 回读校验 |

关键数字：

- 总耗时 **878.2s**，**105** 个处理步，**2.03M** tokens（5.00M 预算的 41%）
- 浏览器类调用只有 6 次 `b4_*`（`open`、`wait`、2×`eval`、3 次 `help`）；其余 87 次是本地 python
- 抓取循环 ≈ **555s**（≈28s/页），占整体 63%
- `.b4scratch/pages` 落盘 **52.8MB**；`.b4scratch` 共 53.8MB，26 个 `.py` + 2 个 `.js`
- 中途 5 次 `help` + 2 次 `eval` 才转向 export+python —— 「结构化取数」这条内置路径当时没被发现

一次 `htmlsnapshot export` 产出的**不是页面 View-Source**，而是注释版序列化（每个元素带 `vi="x y w h"`、`<head>` 里加合成 `<link>`，见 `cli/browser4-cli/src/commands.rs` 的 `htmlsnapshot-export` 描述）。这既解释了 2.6–3.0MB/页 的体积，也说明「先 dump 再 BeautifulSoup」是逆着工具设计的。

---

## 2. 内置批量工具盘点

| 工具 | 形态 | 适配本次任务 | 备注 |
|---|---|---|---|
| `crawl --seed-file … -d 0 --sql @q.sql --format csv -o rows.csv` | 服务端抓取 + X-SQL 抽取 + 直接落盘 | **最贴合** | 并行（默认 4）、任务预算、部分失败 exit 6、checkpoint/resume |
| `swarm query --sql @q.sql --seed-file urls.txt` | 多 browser context 并行，JSON resultSet | 适合上百 URL | 需 `swarm create` / `swarm result` / `swarm close`，无 `-o` |
| `htmlsnapshot query <url> --expires 1d --sql @q.sql` | 单页/页库查询 | 查询开发期用 | `--expires` 读页库不碰标签页 |
| `batch` | 多条 CLI 顺序执行 | 不适用 | 实测 `htmlsnapshot query` 不被支持 |
| `loop` | 定时重复 | 定时监控 | 本次一次性任务不需要 |
| `extract --schema` | LLM 抽取 | 75 列 × 20 页不划算 | 每页推理成本 |

---

## 3. 实测 A：`crawl` 的耗时与产物

同一批 20 个 ASIN（榜单第 1–20），11 列 X-SQL：

```bash
browser4-cli crawl --seed-file seed20.txt -d 0 --sql @q.sql --format csv \
  -o rows20.csv --refresh --parallel 4 --timeout 10m --verbose
```

| 实验 | 规模 | 耗时 | 结果 |
|---|---|---|---|
| 详情抓取 + 抽取 | 3 页，`--parallel 3` | **33.5s** | 3 行，exit 0 |
| 详情抓取 + 抽取 | 20 页，`--parallel 4` | **202.2s** | 20 行，exit 0，峰值 4 单元并发 |
| 只改查询、走页库 | 20 页，`--expires 1h` | **71.6s** | 20 行 |

对照 trace 的手写循环：20 页 ≈ **555s**。即使用默认并行度，也是 **2.7×** 墙钟优势，且是「一条命令 + 直接 CSV」对「40 次进程 + 52.8MB HTML + 26 个脚本」。

另有一次「种子写错」的意外实验：20 条坏 URL 被提交后，`crawl` 明确报
`0 of 20 page(s) crawled (20 failed) — first failure: The X-SQL extracted no data: every field is empty for '<url>'`，
`Summary: ok: 0, failed: 20`，非零退出 —— 失败是响亮的、带 URL 和原因的。

---

## 4. 实测 B：字段保真抽查（11 列，20 行）

| 字段 | 命中 |
|---|---|
| `url` / `title` / `reviews` / `score` / `categorypath` / `featurebullets` / `imgsrc` | **20/20** |
| `availability` | 15/20 |
| `byline`（`#bylineInfo`） | 9/20 |
| `price` | **10/20** ← 根因见 §5，不是选择器问题 |
| `canonical`（`link[rel=canonical]`） | 0/20 ← `FROM … 'body'` 作用域选不到 `<head>`，应用 `:root` |

---

## 5. 渲染上下文的真正原因：crawl 用的不是 live 会话的 browser profile

### 5.1 profile 模式 → context dir（代码 + 实测）

| 路径 | profile mode | context dir |
|---|---|---|
| live `open` / `goto`（DEFAULT 会话，未指定 mode） | `DEFAULT`（`PulsarSessionManager.normalizeCapabilitiesFor`，`browser4-rest/.../PulsarSessionManager.kt:1721`） | 本机实测 `…\context\groups\default\PULSAR_CHROME\cx.004\PULSAR_CHROME` |
| `crawl` 每轮 | 调用方会话（无 `-s` 时即 **DEFAULT**）的浏览器；`--session` / `--profile` 可显式钉住（4.15.3 起） | 该会话 profile 的浏览器（实测：`crawl … ` 与 live `goto` 落在同一 `cx.NNN`） |
| 命名会话 `-s <name>` | `SEQUENTIAL` + **pinned** `contextDir`（`PulsarSessionManager.kt:1730-1731`） | `~/.browser4/context/groups/named/PULSAR_CHROME/cx.<sessionUuid>`（`docs/config.md:387`） |
| `swarm create` | `SEQUENTIAL`（默认）/ `TEMPORARY` | 顺序池 / 随机临时 |
| `open --profile-mode sequential` / `temporary` | 同名字面（`PulsarSessionManager.kt:1719, 1725`） | 顺序池 / 随机临时 |

池的规模：`browser.context.number`（默认 2，活跃上下文数）与 `min.sequential.browser.profile.number`（默认 10，见 `BrowserProfileGenerator.kt:46-62`）共同决定顺序池，本机就是 `cx.001 … cx.010`；**`SEQUENTIAL` 与 `TEMPORARY` 都从 prototype（`~/.browser4/browser/chrome/prototype/google-chrome`）继承**（`docs/config.md:384`）。

### 5.2 实测归因（同一天，间隔 19 秒）

| 观察 | 证据 |
|---|---|
| live `goto B0MARKLIVE` 落在 **cx.004** | `cx.004\...\Default\History` 里有该 URL；launch args 18:00:22、History 18:00:38 |
| 紧随其后的 `crawl B0MARKCRAWL` 起了 **cx.005** | `cx.005\chrome-launch-arguments.txt` 18:00:41，且它没有该 URL 的 History（抓取管线不写 History） |
| 同一个 URL 两个上下文渲染不同 | live tab：`$32.99 / In Stock / 送达地 New York 10001`；crawl 存的页：`cannot be shipped to your selected delivery location`（Hong Kong）、无价格元素 |
| 混合 locale | 3 页试验里 `B015NBTAOW` fresh fetch 返回中文标题 + `HKD78.37`，同一页从页库读回是英文 + `$` |

结论：**不是「抓取管线没有 header」这种缺失功能的问题，而是「crawl 拿到的 profile 不是 live 会话预热过的那个」**。live 会话（DEFAULT，本机 cx.004）里带着美国站的送达地/语言，`crawl` 起点是顺序池里的另一个 `cx.NNN`，其状态来自冷 prototype → Amazon 按 IP 给了香港站。池里各 `cx.NNN` 状态也可能不一致，于是同一批里有的页是美国站、有的是香港站（`price` 10/20 就是这么来的）。

> 上一版把 `LoadOptions` 没有 cookie/header 字段当作根因，判断错误：那是配置入口问题，不是机制缺失。

### 5.3 正确解法：批量采集前先选好 geo + language

预热必须落在**批量工具会用到的那份 profile** 上。4.15.3 起批量工具可以直接复用预热好的会话：

```bash
# ① 在一个会话里把站点状态选好（送达地 / 语言 / 登录），并验证一个已知商品
browser4-cli -s amazon-us open "https://www.amazon.com/dp/B087Z6LSHW"
browser4-cli -s amazon-us set ...        # 或站点内点选配送地/语言，状态落在该会话的专用 profile

# ② 批量抓取直接读那个 profile（crawl 默认就用调用方会话，即无 -s 时的 DEFAULT 会话）
browser4-cli crawl --seed-file urls.txt -d 0 --sql @q.sql --format csv -o rows.csv \
  --refresh --parallel 4 --timeout 10m --session amazon-us
#   或显式指定一个 Chrome user-data 目录（crawl 自己启动并关闭该浏览器）：
browser4-cli crawl ... --profile "C:\profiles\amazon-us"

# ③ swarm 同理（会强制单 browser context）
browser4-cli swarm create --session amazon-us

# ④ 兜底（未升级 / 需要全新上下文时）：预热 prototype，再清掉旧顺序池
chrome.exe --user-data-dir="C:\Users\<user>\.browser4\browser\chrome\prototype\google-chrome"
```

命名会话（`-s amazon`）的 contextDir 由 session id 固定（`docs/config.md:387`），所以预热一次之后重开也保留；crawl 借用它时只取 `Browser` bean，不接管调用方的标签页。


---

## 6. 75 列的 X-SQL 可行性分布（含内置指标函数）

| 分组 | 约数 | 举例 | 做法 |
|---|---|---|---|
| 一行一个函数即可 | ~50 列 | `title`、`brand`、`score`、`reviews`、`categorypath`、`featurebullets`、`imgsrc`、`score5..1percent`、`isaddcart/isbuy`、`instock` | `DOM_FIRST_TEXT/ATTR`、`DOM_ALL_TEXTS` + `ARRAY_JOIN_TO_STRING` |
| 需要回退链 / 正则 | ~15 列 | `price`、`buyboxprice`、`listprice`、`rank`/`rank2`(BSR)、`soldby`/`shipsfrom`、`sellerID`、`detailbullets`、`pasin`、`gallery`/`detailimgs`、`boughttogether*` | `ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(...))`、`DOM_FIRST_RE1`/`DOM_ALL_RE2`、70 个 `STR_*` |
| **内置指标函数**（上一版误判为「别扭」） | 4 列 | `numchars`、`numlinks`、`numimgs`、`height` | 见下：X-SQL 直接能算，实测通过 |
| 少数仍别扭 | 1 列 | `jsVariables`（`DOM_DOCUMENT_VARIABLES` 是 Pulsar 元信息，不是页面 JS 变量） | 扫 `<script>` 文本 + 正则 |
| 最后一步不变 | — | 75 列顺序 + xlsx | 仍需一步 python（CSV → xlsx） |

指标列的实测（`htmlsnapshot query --expires 1d`，对 `B004YAVF8I` 的页库快照）：

```sql
SELECT DOM_TEXT_LEN(DOM_SELECT_FIRST(dom, '#productDescription')) AS numchars,  -- 333
       DOM_HEIGHT(dom)                                            AS page_height, -- 10086.0
       DOM_CHILD_ELEMENT_SIZE(DOM_SELECT_FIRST(dom, 'body'))      AS body_children -- 21
FROM DOM_LOAD_AND_SELECT(@url, 'body')
```

| 字段 | 内置函数 |
|---|---|
| `numchars` | `DOM_TEXT_LEN(dom)` / `DOM_WHOLE_TEXT_LEN(dom)` / `DOM_OWN_TEXT_LEN(dom)` |
| `height` | `DOM_HEIGHT(dom)`（另有 `DOM_WIDTH` / `DOM_AREA` / `DOM_TOP` / `DOM_LEFT`） |
| `numlinks` / `numimgs` | `DOM_ALL_HREFS(dom,'a')` / `DOM_ALL_IMGS(dom,'img')` 取数组；计数用 `DOM_CHILD_ELEMENT_SIZE`、`STR_COUNT_MATCHES` 或 SQL `COUNT`（按页面结构选写法） |

净效果：代码量从「433 行 BeautifulSoup」变成「约 100 行 SQL」，**工作量同一量级**；收益在抓取侧（不落 52MB、不烧 2M token、并行、直出文件）。

---

## 7. 推荐流程（下次这类任务）

```bash
# 0) 预热 profile：选好 geo + language，并验证一个已知商品（见 §5.3）

# ① 榜单 → 20 个 ASIN（一次拿排名，避免逐页 eval）
browser4-cli crawl "<zgbs url>" -d 1 -ol "div[id^=gridItemRoot] a[href*=/dp/]" -tl 20 --json > list.json
#    或单页：browser4-cli htmlsnapshot query --sql "SELECT DOM_ALL_HREFS(dom,'…') …"

# ② 详情：一条命令抓 + 抽 + 落盘（并行、有预算、有断点）
browser4-cli crawl --seed-file urls.txt -d 0 --sql @q.sql --format csv \
  -o rows.csv --refresh --parallel 4 --timeout 10m

# ③ 改查询不重抓
browser4-cli crawl --seed-file urls.txt -d 0 --sql @q2.sql --expires 1h --format csv -o rows2.csv

# ④ 交付：CSV → xlsx（一步 python，保留）
```

---

## 8. 产品缺口（可转 issue）

1. ~~**`crawl` 无法指定 session / profile / contextDir**~~ — **已实现（2026-10-29，4.15.3-SNAPSHOT）**：`crawl` 默认使用调用方会话的浏览器（CLI 无 `-s` 时即 **DEFAULT 会话**），`crawl --session <name>` / `--profile <dir>` 可显式钉住；`swarm create --session <name>` / `--profile <dir>` 把 swarm 浏览器的 profile 钉到命名会话的专用目录或给定 user-data 目录。实现要点：
   - 借用是非破坏性的：crawl 用自己的 session 承载目标 `Browser` bean（不带 driver），因此并行单元仍各占一个标签页，调用方标签页/URL/cookie 不受影响；只有 crawl 自己创建的 session/browser 会被关闭。
   - 绑定是**惰性**的：预算已耗尽的任务不会碰 session manager（保住 `verifyNoInteractions(sessionManager)` 这条“什么都没抓”的断言）。
   - `swarm` 的 `--session/--profile` 会强制 `maxBrowserContexts=1`：一个 Chrome user-data 目录同一时刻只能被一个浏览器进程使用。
   - 代码：`CrawlSessionBinder.kt`（新增）、`CrawlModels.kt`（`fetchSession`/`profile`）、`CrawlService.kt`（惰性绑定 + 仅释放自有会话）、`CrawlRoundRunner.kt`（`sharedSession`/`pinnedBrowser`）、`CrawlToolExecutor.kt`、`CrawlController.kt`、`SwarmController.kt`（`resolveSwarmProfile`）、CLI `commands.rs`/`main.rs`/`tips.rs`。
2. **PROTOTYPE 模式没有 CLI 入口**：`open --profile-mode` 只接受 `temporary|sequential|default`。现在可以绕开：用 `crawl --profile <prototype 用户目录>` 或 `--session` 复用已预热的会话；但“把 prototype 本身预热一次、所有新上下文继承”仍需要手动起 Chrome。
3. **`batch` 不支持 `htmlsnapshot query`**：实测 `Command 'htmlsnapshot-query' is not supported in batch mode. Batch mode only supports DOM operations.` 于是 live 页「导航 + X-SQL」被迫拆成两次进程调用，与「一次抓取多处抽取」的设计意图相悖。
4. **发现性**：trace 里 5 次 `help` + 2 次 `eval` 都没把人引到 `crawl`/`swarm`。tips 只在执行该命令时出现，而 agent 通过 python 子进程驱动 CLI，引擎的提示与护栏看不到 —— `bulk-collection.md` 已写清正确做法（含 locale 纪律），需要在 `agent run` 的任务模板/技能前置里主动塞进去。
5. **`htmlsnapshot export` 的产物语义**：注释版序列化适合调试，不适合离线解析；纯解析场景应引导到 `webdb export` 或提供 raw HTML 导出选项。

---

## 9. 复现与清理

- 实验命令见 §3、§5.3；种子为 20 个 `https://www.amazon.com/dp/<ASIN>?th=1&psc=1&language=en_US`
- profile 归因复现：`open --fresh --headless <url>` → `goto <带标记的 URL>` → `crawl --seed-file <另一个标记 URL>`，然后比对 `…/PULSAR_CHROME/cx.*/chrome-launch-arguments.txt` 的 mtime 与各 `Default/History` 里的标记 URL
- 临时脚本放在 `%TEMP%`（`b4ck.py`/`b4hist.py`/`b4mark*.py`/`b4cookie2.py`），实验目录 `%TEMP%\b4live`；仓库内无残留改动

### 9.1 4.15.3 的实现验证（本轮）

| 验证项 | 结果 |
|---|---|
| 单元测试 | `browser4-rest`：`CrawlSessionBinderTest`(5)、`CrawlToolExecutorTest`(16)、`CrawlControllerSeedValidationTest`(8)、`CrawlServiceTest`(13)、`PulsarSessionManagerTest`(42) 全绿；CLI `cargo test --bin browser4-cli` 1977 passed |
| crawl 默认会话 | 后端日志：`CrawlSessionBinder - Crawl 99f6f975-…: fetching through session 'DEFAULT' (browser id {PULSAR_CHROME, …\PULSAR_CHROME\cx.001}, context dir …\cx.001)`；CLI 回显 `Fetch session: DEFAULT` |
| crawl `--session <name>` | `fetching through session 'probe-sess' (browser id {PULSAR_CHROME, …\context\groups\named\PULSAR_CHROME\cx.b53d6b19-…})` —— 正是命名会话的专用 profile 目录 |
| crawl `--profile <dir>` | `fetching through profile 'C:\Users\…\b4live\profile' (browser id {PULSAR_CHROME, <dir>}, context dir <dir>)` —— crawl 自己在该目录起浏览器 |
| swarm `--session` / `--profile` | 后端日志 `Swarm profile pinned to '<named 会话目录>' (from session probe-sess) — one browser context`；CLI 回显 `Profile: pinned to session 'probe-sess' — one browser context` |
| 错误路径 | 会话还没起浏览器时：`open_session failed: Session 'probe-sess' drives no browser yet: open it first (e.g. -s probe-sess open <url>) …`（清晰可执行） |

> Amazon 现场数据校验受反爬限制：本轮多次请求后 Amazon 返回约 5.7KB 的拦截页，`--session DEFAULT` 的对照只能证明**管线接上了**（日志 + 3 页中 2 页拿到 USD/New York），不能证明 20/20 价格列。预热 profile 后务必先单页校验再放量。

---

## 附：证据索引

| 证据 | 位置 |
|---|---|
| trace（105 步、时间戳、交付摘要） | `logs/agent-run-example-trace.log` |
| `scrape.py` 原文 / 433 行解析器 / 75 字段清单 | 运行产物 `.b4scratch/scrape.py`、`parse.py`、`fields.json` |
| 20 页注释版 HTML（52.8MB） | 运行产物 `.b4scratch/pages/` |
| `crawl` 语义 | `skills/browser4-cli/references/crawl.md` |
| 批量采集纪律（persist / locale / 引号） | `skills/browser4-cli/references/bulk-collection.md` |
| 多产品抽取方案对比 | `docs/multi-product-extraction.md` |
| profile 模式与 context dir 语义 | `docs/config.md:377-398` |
| profile mode 归一化（DEFAULT/named/swarm） | `browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt:1677-1732` |
| crawl 自建 SEQUENTIAL 会话 | `browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/crawl/CrawlRoundRunner.kt:564-574` |
| 顺序池规模（context.number / min sequential profiles） | `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/browser/privacy/BrowserProfileGenerator.kt:40-78` |
| 会话启动选择 profile 的代码路径 | `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/session/AbstractPulsarSession.kt:352-402` |
| `htmlsnapshot export` 产物语义 / `query --expires` | `cli/browser4-cli/src/commands.rs` |
| 指标类 X-SQL 函数 | `docs/ql-functions-guide.md`（`DOM_TEXT_LEN`、`DOM_HEIGHT`、`DOM_ALL_HREFS`、`DOM_ALL_IMGS`） |


---

## 10. 三个确认（2026-10-29 复核）

1. **`?language=` 不在引擎代码里。** `browser4-core` 没有任何 `language` / `Accept-Language` 处理；它此前只出现在文档和 **agent 提示词**（`CliAgentPrompt`）里，属于站点相关写法 —— 已从提示词移除，改成“在会话 profile 里钉 locale（profile / cookies / `set headers`）”，并在 `CliAgentPromptTest` 里断言提示词不得再出现 `?language=`。文档（`crawl.md` / `bulk-collection.md`）同步改成 profile 方案；本文里出现的 `?language=en_US` 仅作为**实验记录**保留，不是引擎行为。
2. **crawl 并行：调度层并发，墙钟未随之改善。** 代码上 `--parallel` 是每单元一个加载的并发预算，报告也会写 `peak N seed unit(s) in flight`；但本地 6 页 fixture 实测 `--parallel 3` = 22.9s、`--parallel 1` = 24.7s（单页固定成本约 3.8s），说明加载实际串行化。根因之一：`PrivacyManagedBrowserFetcher.getWebDriver` 在指定 browser 时复用其**现有 driver** 并加 60s 租约，第二个单元只能等同一个 tab。文档已写清「`--parallel` 是并发上限，不是吞吐承诺」，并把“pinned profile ⇒ 单 tab”列为显式注意事项；彻底修复需要池级 profile 绑定（让每个单元用自己的 browser/tab，同时仍落在同一 profile）。
3. **原始 href：改前不成立，现已成立。** 改前引擎在请求前把 URL 规范化（`?b=2&a=1` → `?a=1&b=2`，`/p/` → `/p`），而报告用原始拼写，二者不一致；现在 crawl 的轮次默认带 `-noNorm`（`CrawlSupport.resolveRoundArgs`），实测请求 = 页面原始 href（参数顺序、尾斜杠、`ref=`、大小写全部保留）。去重仍用 `normalizeForVisit` 做身份比较，只决定“队列放哪条拼写”，不会把请求改写。

### 10.1 补充（21:45）：crawl 的并发轴 = 单 context 内的标签

按“crawl 只在一个 browser context 内工作，需要更高并发用 swarm”的口径复核：

- 实现上 crawl 借用调用方会话的**一个** browser（`--session` / 默认 DEFAULT），每个并行单元在自己的标签页上加载：借用来的标签页只使用不关闭，单元自己打开的标签页在内容捕获后立即关闭。
- 同一 context 内的标签并行生效但有限：本地 1s 延迟 fixture 实测 **6 页 `--parallel 6` = 25.5s vs `--parallel 1` = 32.1s**（并行确实重叠，但整页管线有固定成本）。`--parallel` 应作为“重叠上限 / 对站点施压的闸门”，不是吞吐倍数；标签上限由 `browser.max.active.tabs`（默认 8）约束。
- 更高并发不再从 crawl 借道：`swarm`（多 context × 各自 profile/tabs）才是那条轴。文档 `crawl.md` 已新增 “One browser context, tabs inside it” 小节，并给出 crawl vs swarm 的选择表。
