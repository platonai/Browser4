# Amazon 微波炉「今日畅销 Top 20」采集实录

> 生成时间：2026-10-27（Asia/Shanghai）
> 环境：Browser4 `4.15.2-SNAPSHOT` 源码树 · `b4w.ps1`（dev 模式，后端端口 8282）· Chrome headless · Windows
> 数据源：[Amazon Best Sellers in Microwave Ovens](https://www.amazon.com/Best-Sellers-Kitchen-Dining-Microwave-Ovens/zgbs/kitchen/289935)（榜单每小时更新）
> 采集窗口：2026-10-26 23:55 → 2026-10-27 00:40，**三轮全量**明细页采集（v1 → v2 → v3）
> 产出：`output/amazon-microwaves-2026-10-26/amazon_microwaves_top20.xlsx`（20 行 × 75 列）

这份文档是一次**真实任务的完整复盘**：需求怎么拆、命令怎么敲、踩了哪些坑、字段口径怎么定、最后数据长什么样。
所有命令与输出都是实跑记录（个别输出做了截断），脚本原文见
[附录 A](#附录-a提取器-extract_product_v3js) / [B](#附录-b跑批器-run_passps1) / [C](#附录-c导出器-export_to_xlsxpy)。

**为什么有三轮？** 不是"重试"，而是每次都被 QA 逼出来的：

| 轮次 | 时间 | 变更 | 触发原因 |
| --- | --- | --- | --- |
| v1 | 00:04 → 00:13 | 首版提取器，按"常见 Amazon 选择器"写 | 基线 |
| v2 | 00:14 → 00:22 | 换容器、加多级回退、修正卖家字段 | 2026 新版详情页把 `#detailBullets` / `#tabular-buybox` 全套换掉；`brand` 只有 11/20 |
| v3 | 00:33 → 00:40 | 徽章判定改为"看渲染文本"、`desc` 补容器 | 覆盖率统计全绿，但**语义全错**：`isac` 20/20 都为 1、`desc` 漏了新容器 |

---

## 1. 任务与拆解

**原始需求：**

> 到亚马逊上找到今天卖得最好的 20 个微波炉，访问每个详情页，采集产品信息，保存为电子表格。
> 需采集的产品字段见 `browser4-tests/pulsar-tests-common/src/test/resources/asin_field_description.xlsx`

拆成五步，其中第 4 步占了 80% 的调试时间：

| # | 步骤 | 难点判断 |
|---|------|----------|
| 1 | 读字段表，确认 75 个字段的口径 | 表里只有"表头 + 中文说明"两行，没有取值规则，口径要自己定 |
| 2 | 定位"今天卖得最好的微波炉" | 不是搜索页，是 **Best Sellers 榜单页**（每小时滚动更新，一页 30 个卡片） |
| 3 | 抓 Top 20 的 ASIN 与详情页链接 | 卡片结构稳定，但价格/图片异步渲染，且受地区/币种影响 |
| 4 | 逐个详情页采集 75 个字段 | 2026 新版详情页把老选择器几乎全换掉；地区/币种/语言会让价格字段整列归零 |
| 5 | 落成电子表格 | 列名顺序必须与源表一致，长文本要控长度（Excel 单格上限 32 767） |

**最终交付物：**

| 文件 | 说明 |
| --- | --- |
| `output/amazon-microwaves-2026-10-26/amazon_microwaves_top20.xlsx` | 主表：Sheet `microwaves_top20`（20 行 × 75 列）+ Sheet `fields`（字段名 ↔ 中文说明，76 行） |
| `output/amazon-microwaves-2026-10-26/amazon_microwaves_top20.csv` | 同数据 CSV（UTF-8 with BOM，Excel 双击可读） |
| `output/amazon-microwaves-2026-10-26/raw3/<ASIN>.json` | **定稿**：每个 ASIN 的原始采集 JSON（v3），20 个 |
| `output/amazon-microwaves-2026-10-26/raw2/` · `raw/` | 前两轮原始 JSON，留作对照 |
| `output/amazon-microwaves-2026-10-26/bs_list.json` | 榜单页抓到的 30 条原始记录（取前 20） |
| 本目录 | 本文档 + 5 个脚本副本（提取器 / 跑批器 / 导出器 / 探活 / 榜单抓取） |

---

## 2. 结果速览

榜单页一次返回 30 个卡片，按 `#1`–`#20` 取前 20 个（`#21`–`#30` 保留在 `bs_list.json` 里未采）。

| # | ASIN | 品牌 | 标题（截断） | 价格 | 划线价 | 评分 | 评论数 | 卖家 | 详情页类目排名 |
|---|------|------|--------------|------|--------|------|--------|------|----------------|
| 1 | `B0DY95T5HB` | Chefman | Chefman Countertop Microwave Oven 0.7 Cu. Ft., 7… | $69.95 | $99.99 | 4.3 | 5,325 | Amazon.com | #1 in Countertop Microwave Ovens |
| 2 | `B071WCB1T6` | TOSHIBA | TOSHIBA Countertop Microwave Oven, 1.2 Cu.Ft, 10… | $149.99 | — | 4.4 | 32,831 | Amazon.com | #2 in Countertop Microwave Ovens |
| 3 | `B07HG9Y3VL` | BLACK+DECKER | BLACK+DECKER Microwave Oven Countertop, 1.1 Cu.F… | $99.89 | $132.99 | 4.2 | 23,116 | Amazon.com | #3 in Countertop Microwave Ovens |
| 4 | `B0DPQPGC75` | TOSHIBA | TOSHIBA Microwave Oven, 0.9 Cu.Ft, 900W, Black S… | $92.22 | $117.99 | 4.5 | 1,658 | Amazon.com | #4 in Countertop Microwave Ovens |
| 5 | `B07HG9YGZY` | BLACK+DECKER | BLACK+DECKER Countertop Microwave Oven, 0.9 Cu.F… | $89.87 | $106.99 | 4.4 | 12,472 | Amazon.com | #5 in Countertop Microwave Ovens |
| 6 | `B0DV5SDY1R` | Nostalgia | Nostalgia Retro Microwave Oven - 0.7 Cu Ft, 700-… | $89.99 | — | 4.0 | 1,118 | Amazon.com | #8 in Countertop Microwave Ovens |
| 7 | `B01EIZSDWO` | Farberware | Farberware Countertop Microwave Oven, 0.7 cu.ft.… | $67.99 | $89.99 | 4.1 | 7,026 | Amazon.com | #6 in Countertop Microwave Ovens |
| 8 | `B07GV78ZKK` | COMFEE' | COMFEE' Countertop Microwave Oven, 0.7 Cu.Ft, 70… | $68.95 | — | 4.4 | 25,200 | Amazon.com | #7 in Countertop Microwave Ovens |
| 9 | `B07VS476X8` | Farberware | Farberware Countertop Microwave Oven 0.7 cu.ft. … | $89.99 | $99.99 | 4.3 | 10,087 | Amazon.com | #9 in Countertop Microwave Ovens |
| 10 | `B0H79N9LTR` | BLACK+DECKER | BLACK+DECKER Countertop Microwave Oven 0.7 Cu.Ft… | $69.93 | $84.99 | 4.4 | 336 | Amazon.com | #10 in Countertop Microwave Ovens |
| 11 | `B0GSGNT5RQ` | Chefman | Chefman Countertop Microwave, 1.1 Cu Ft, 1000W, … | $89.00 | $129.99 | 4.5 | 1,170 | Amazon.com | #11 in Countertop Microwave Ovens |
| 12 | `B0F1HFBHRK` | Frigidaire | Frigidaire, 0.7 Cu. Ft. Retro Countertop Microwa… | $69.99 | $79.99 | 4.2 | 920 | Amazon.com | #12 in Microwave Ovens |
| 13 | `B0DTQ3MF8G` | TOSHIBA | TOSHIBA Countertop Microwave Oven with Smart Sen… | $129.99 | $148.99 | 4.5 | 492 | Amazon.com | #12 in Countertop Microwave Ovens |
| 14 | `B0BVPQ6Z21` | COMFEE' | COMFEE' Countertop Microwave Oven 0.7 Cu.Ft, 700… | $69.95 | $84.99 | 4.3 | 1,647 | Amazon.com | #13 in Countertop Microwave Ovens |
| 15 | `B0GN34JCB6` | TOSHIBA | TOSHIBA Flatbed Microwave Oven with Inverter Tec… | $199.99 | — | 4.5 | 139 | Amazon.com | #14 in Countertop Microwave Ovens |
| 16 | `B00F2QFX5O` | GE | GE 30" Over-the-Range Microwave 1.6 Cu. Ft., 100… | — | — | 4.0 | 1,530 | — | #1 in Over-the-Range Microwave Ovens |
| 17 | `B0D295GDXX` | GE | GE Countertop Microwave Oven, 1100 Watt, 1.4 Cu.… | $149.00 | $220.00 | 4.3 | 419 | Amazon.com | #15 in Countertop Microwave Ovens |
| 18 | `B0DD87CCQP` | GE | GE Countertop Microwave Oven, Compact 1.1 Cubic … | $118.00 | $175.00 | 4.1 | 322 | Amazon.com | #16 in Countertop Microwave Ovens |
| 19 | `B0DD85QFY6` | GE | GE Countertop Microwave Oven, 1150 Watt, 1.6 Cu.… | $155.91 | — | 4.2 | 238 | Amazon Resale | #17 in Countertop Microwave Ovens |
| 20 | `B0D294MHR5` | GE | GE Countertop Microwave, 700 Watt 0.7 Cu. Ft., S… | $108.09 | $139.00 | 4.1 | 753 | Amazon.com | #18 in Countertop Microwave Ovens |

> **"详情页类目排名"读法**：取自该商品详情页的 `Best Sellers Rank` 模块，是**最末级子类目**的名次（多数是
> *Countertop Microwave Ovens*；个别商品页面只给父类目名次，例如 `#12 in Microwave Ovens`）。它和左边"今日畅销榜位次"不是同一个榜——畅销榜按 *Microwave Ovens* 节点排，
> 详情页排名按子类目排，所以第 6 位可能对应子类目第 8 名。两个口径都保留在表里（`rank2` 含完整原文）。

**汇总统计：**

| 指标 | 值 |
| --- | --- |
| 商品数 | 20 |
| 价格区间 | $67.99 – $199.99（均值 $104.14，19 个有价） |
| 评论数合计 | 126,799 条 |
| 评分区间 | 4.0 – 4.5 |
| 品牌数 | 8（BLACK+DECKER、COMFEE'、Chefman、Farberware、Frigidaire、GE、Nostalgia、TOSHIBA） |
| `Amazon's Choice` | 17/20 |
| `Best Seller` 徽章 | 1/20（`B00F2QFX5O`：*#1 Best Seller in Over-the-Range Microwave Ovens*） |
| 限时 Deal | 10/20 |
| 卖家 | 18 条 `Amazon.com`、1 条 `Amazon Resale`、1 条页面无卖家（不可售） |
| 缺价 | 1 条（`B00F2QFX5O` 当日 `Currently unavailable`） |

---

## 3. 字段口径

字段表 `asin_field_description.xlsx` 的 `Sheet2` 只有两行：第 1 行是 **75 个英文字段名**（也就是最终列名与列序），第 2 行是中文说明。
导出时直接用第 1 行做表头，第 2 行单独生成 `fields` 页做对照——既不破坏机器可读性，又保留了业务口径。

读取方式（Python `openpyxl`。注意：Windows 控制台是 GBK，直接 `print` 中文会 `UnicodeEncodeError`，必须写 UTF-8 文件或设 `PYTHONIOENCODING=utf-8`）：

```powershell
@'
import openpyxl, io
p = r"browser4-tests\pulsar-tests-common\src\test\resources\asin_field_description.xlsx"
ws = openpyxl.load_workbook(p, data_only=True)["Sheet2"]
rows = list(ws.iter_rows(min_row=1, max_row=3, values_only=True))
with io.open(r"%TEMP%\fields.txt", "w", encoding="utf-8") as f:
    f.write("\n".join("%d. %s — %s" % (i, h, d) for i, (h, d) in enumerate(zip(rows[0], rows[1]), 1)))
'@ | Set-Content -Encoding utf8 "$env:TEMP\read_xlsx.py"; python "$env:TEMP\read_xlsx.py"
```

### 3.1 字段 → 取值来源映射

75 个字段里，约 40 个是"一个选择器直接取"，其余都需要**多级回退、正则解析或计算**。下表是提取器（v3）里的真实口径：

| 字段 | 取值来源 / 选择器 | 备注 |
| --- | --- | --- |
| `id` | 序号 1–20 | 脚本生成 |
| `url` | `https://www.amazon.com/dp/<ASIN>` | 规范化主链接 |
| `href` | 榜单卡片 `<a href>` 转绝对地址 | 带 `ref=zg_bs_g_289935_d_sccl_N` |
| `label` | 任务标签常量 `microwave-bestsellers-2026-10-26` |  |
| `keywords` | 常量 `microwave ovens` | 榜单品类，不是搜索词 |
| `category` | 榜单节点 ID `289935` | Microwave Ovens |
| `overviewbullets` | `#productOverview_feature_div` 的 tr/li 键值对 | 品牌/尺寸/颜色/容量… |
| `detailbullets` | `#productDetails_feature_div` 全部 tr/li 键值 → JSON | 60+ 项规格，截断 6000 字符 |
| `task_time` | 导出时刻 |  |
| `img` | `#landingImage` 的 outerHTML | 截断 1200 字符 |
| `imgsrc` | `#landingImage[data-old-hires]` → `src` | 最高分辨率主图 |
| `dynamicimgsrcs` | `#landingImage[data-a-dynamic-image]` 的 key 集合 | 7 档尺寸 URL |
| `rank` | 含 `Best Sellers Rank` 的 tr 内层 HTML | 无该模块时回退榜单位次 |
| `rank2` | 同 tr 的纯文本 | `#277 in Kitchen & Dining … #3 in Countertop Microwave Ovens` |
| `title` | `#productTitle` |  |
| `featurebullets` | `#feature-bullets` | About this item 五条卖点 |
| `desc` | `#productDescription` → **`#productDescription_feature_div`** → `#aplus` 文本 | v3 才补上的容器；纯图片 A+ 仍为空 |
| `asin` | `input#ASIN` → URL 正则 | 双保险 |
| `pasin` | 页面内联脚本 `parentAsin` | 变体父 ASIN |
| `totalvariations` | 内联脚本 `num_total_variations` |  |
| `jsVariables` | 含 twister 数据的内联 `<script>` | 截断 1500 字符 |
| `brand` | `#bylineInfo` → 概览 Brand → 详情 Brand Name | 三级回退 |
| `soldby` | `#merchantInfoFeature_feature_div` 的 `Shipper / Seller X` → tabular-buybox → buybox 内 `Sold by:` → 全文 | 第一轮踩坑字段 |
| `shipsfrom` | 同上 |  |
| `quantity` | `#quantity option` 文本列表 | 18/20 有下拉 |
| `globaldeliverto` | `#glow-ingress-line2` | 校验地区是否生效 |
| `deliverto` | `#mir-layout-DELIVERY_BLOCK` | 预计送达文案 |
| `price` | `#corePrice_feature_div` 首个 `$` → `#apex_desktop` |  |
| `buyboxprice` | `#buybox` 内首个 `$` → price | 交叉校验：20/20 与 price 一致 |
| `instock` | `#availability` 文本 | 截断 60 字符 |
| `isaddcart` | `#add-to-cart-button` 是否存在 | 0/1 |
| `isbuy` | `#buy-now-button` 是否存在 | 0/1 |
| `isac` | `#acBadge_feature_div` **渲染文本**是否为 `Amazon's Choice`（或其中 aria-label） | 容器恒存在 → 只能看内容 |
| `isbs` | `#zeitgeistBadge_feature_div` **渲染文本**是否含 `Best Seller` | 同上；本次 1/20 |
| `iscoupon` | `#promoPriceBlockMessage` 或 `Apply $X coupon` | 0/1 |
| `isprime` | buybox 内 `i.header-prime-logo` / `i.a-icon-prime` / `#prime-badge` 元素 | 未登录 Prime → 0/20 |
| `withdeal` | `#dealBadge_feature_div` | `Limited time deal` |
| `yousave` | List Price − Price 计算 + 折扣百分比 | 计算字段：`$30.04 (30%)` |
| `detailimgs` | A+ 区 img（`data-src` 优先，过滤 grey-pixel） | 懒加载坑 |
| `detailvideos` | A+ video / vse 播放器链接 |  |
| `isa` | `#aplus` 是否存在 | 0/1，表示有图文详情 |
| `boughttogethermetadata` | FBT 容器内 `[data-a-state]` |  |
| `boughttogetherimgs` | FBT 容器内 img |  |
| `boughttogether` | FBT 容器文本（要求含 `bought together`） | 15/20 页面有 FBT |
| `othersellernum` | 正文 `New & Used (N)` / `(N) from` |  |
| `qanum` | 正文 `N answered questions` | 0/20：新版无 Q&A 区块 |
| `stock_status` | `#availability` 首行 |  |
| `score` | `#acrPopover[title]` 正则 → 文本 | `4.3 out of 5` → `4.3` |
| `reviews` | `#acrCustomerReviewText` 数字 |  |
| `starnum` | 同 reviews | 源表口径一致 |
| `score5percent` | `a[aria-label="69 percent of reviews have 5 stars"]` | 新版用 aria-label |
| `score4percent` | 同上 |  |
| `score3percent` | 同上 |  |
| `score2percent` | 同上 |  |
| `score1percent` | 同上 |  |
| `scoresbyfeature` | 页面无该模块 | 0/20 |
| `weight` | 详情 `Item Weight` | 18/20 |
| `volume` | 详情 `Item Dimensions D x W x H` / `Product Dimensions` |  |
| `reviewsmention` | 不采集 | 源表标注“暂时取不到” |
| `onsaletime` | 详情 `Date First Available` | 0/20：新版不再输出 |
| `feedbackurl` | 卖家链接 / `sp?seller=<ID>` |  |
| `sellerID` | URL 里 `seller=` → Amazon 自营 `ATVPDKIKX0DER` | 19/20 |
| `marketplaceID` | 常量 `ATVPDKIKX0DER` | amazon.com 美国站 |
| `reviewsurl` | `/product-reviews/<ASIN>` |  |
| `sellsameurl` | `/gp/offer-listing/<ASIN>` |  |
| `categorypath` | 面包屑 `a[href]` 绝对化 |  |
| `categoryname` | 面包屑最后一级 |  |
| `categorynamelevel` | 面包屑文本用竖线连接 |  |
| `listprice` | `#apex_desktop` 的 `List Price:` → `#listPrice` | 14/20 有划线价 |
| `gallery` | `#altImages li img`，规格换成 `_AC_SL1200_` |  |
| `referer` | 常量：榜单页 URL | 模拟来源页 |
| `numchars` | `document.body.innerText.length` |  |
| `numlinks` | `a[href]` 计数 |  |
| `numimgs` | `img` 计数 |  |
| `height` | `documentElement.scrollHeight` | 整页高度 |

---

## 4. 环境与准备

```text
仓库：D:\workspace\Browser4\Browser4-4.15-feat   （ROOT.md + pom.xml 存在 → dev 模式）
CLI ：4.15.2（cargo 从源码编译）
后端：4.15.2-SNAPSHOT，dev 模式自动选端口 8282
JDK ：graalvm-jdk-25.0.3+9.1（Maven enforcer 要求 JDK 25+）
浏览器：Chrome，headless（AI agent 默认模式）
```

dev 模式下每个 checkout 独占后端端口与状态目录，不会和别的 worktree 抢会话：

```text
Server URL: http://127.0.0.1:8282
  resolved from: development port allocation
Workspace app data: C:\Users\<user>\.browser4\workspaces\Browser4-4.15-feat-2ec92531\app-data
```

**首次调用的代价**（新手最容易误判"卡死"）：

- 第一次 `./b4w.ps1 status` 会**编译 Rust CLI**：`Finished dev profile ... in 1m 35s`
- 第一次 `open` 会**Maven 打包 runtime bundle**（几分钟，输出在 spinner 之前）+ Spring Boot 启动 `Server ready in 13.3s`
- 之后每次命令 1–2 秒；后端常驻，不再重启

---

## 5. 完整执行过程

### 5.1 打开榜单页

"今天卖得最好的微波炉"= Amazon Best Sellers 节点（Home & Kitchen → Kitchen & Dining → Microwave Ovens，节点 `289935`）：

```powershell
./b4w.ps1 --session amz-mw open --headless "https://www.amazon.com/Best-Sellers-Kitchen-Dining-Microwave-Ovens/zgbs/kitchen/289935"
```

```text
Session opened: e0d55f28-...            ← 默认会话，后来关闭重建
### Page
- Page URL: https://www.amazon.com/Best-Sellers-Kitchen-Dining-Microwave-Ovens/zgbs/kitchen/289935
- Page Title: Amazon Best Sellers: Best Microwave Ovens
```

> `--session` / `-s` 是**全局参数，必须写在子命令前面**。
> `./b4w.ps1 --session amz-mw open ...` ✅　`./b4w.ps1 -Session amz-mw open ...` ❌（`Unknown option: '-Session'`）

### 5.2 抓榜单卡片（第一次就成功）

一次 `eval --file` 取回 30 个卡片的 9 个字段，比 `htmlsnapshot get all` 少 8 次往返：

```js
const nodes = Array.from(document.querySelectorAll('#gridItemRoot'));
// .zg-bdg-text = "#1"；a[href*="/dp/"] = 详情页；img/src、.a-icon-alt、价格 span、评论数
```

```powershell
./b4w.ps1 --session amz-mw eval --file "output\amazon-microwaves-2026-10-26\scripts\bs_list.js"
```

首轮抓回来，**价格是 HKD**：

```text
count=30
#1  B0DY95T5HB  HKD 847.98   5,325  4.3 out of 5 stars  Chefman Countertop Microwave Oven 0.7 Cu. Ft. ...
#2  B071WCB1T6  HKD 1,168.93 32,831 4.4 out of 5 stars  TOSHIBA Countertop Microwave Oven, 1.2 Cu.Ft ...
```

这就是**第一个坑**：Amazon 按 IP 把会话判定在 Hong Kong、币种 HKD，部分商品还"不支持配送到该地区"→ 详情页价格会整列丢失。

### 5.3 把 "Hong Kong / HKD" 改成 "New York 10001 / USD"

两步，缺一不可。

**① 币种**——直接写 cookie（Amazon 读 `i18n-prefs`）：

```js
document.cookie = 'i18n-prefs=USD; domain=.amazon.com; path=/';
```

**② 配送地**——走导航栏 "Deliver to" 弹层，用 **ref 触发的可信事件**（`fill` / `click`）而不是 JS `.click()`：

```powershell
# 1) 打开弹层（这一步用 JS click 没问题）
./b4w.ps1 --session amz-mw eval --file "$env:TEMP\openpop.js"

# 2) 从无障碍快照里拿 ref
./b4w.ps1 --session amz-mw snapshot grep -i -C 3 "zip code"
#    → textbox "or enter a US zip code" [ref=e25650]
#    → button "Apply"                  [ref=e25654]
./b4w.ps1 --session amz-mw fill e25650 "10001"
./b4w.ps1 --session amz-mw click e25654

# 3) 出现 "You're now shopping for delivery to: 10001" → 点 Continue
./b4w.ps1 --session amz-mw snapshot grep -i -C 3 "Continue"    # → button "Continue" [ref=e27351]
./b4w.ps1 --session amz-mw click e27351
```

校验（`price` 从空变成实际美元价）：

```json
{"loc":"New York 10001‌","price":"$69.95","avail":"In Stock"}
```

> 第一次是用纯 JS 点 `#GLUXZipUpdate` 完成地址变更的。地址变了，但**紧接着访问下一个详情页就被丢到
> `/checkpoint/continue?token=…`** 人机确认页。换成"新开一个干净的 `amz-mw` 会话 + 用 ref 的可信事件重做地址"
> 之后，后续 60 次页面导航（三轮 × 20 页）再没被拦过。见[坑 3](#6-十个坑现象--根因--处理)。

### 5.4 顺手把界面语言改回英文

系统 locale 是 zh-CN，Amazon 会把标题翻成中文（最终表里就全是中文标题，选择器也会跟着变）：

```text
Amazon.com: Chefman 台面微波炉 0.7 立方英尺,700 瓦,10 个功率级别 ...
```

写 `lc-main` cookie 解决：

```js
document.cookie = 'lc-main=en_US; domain=.amazon.com; path=/';
```

改完标题即恢复英文，后面所有选择器都基于英文布局验证。

### 5.5 单页试采 → "选择器全军覆没"

先拿 `#1` 试采，确认"能开页、能取数"：

```powershell
./b4w.ps1 --session amz-mw goto "https://www.amazon.com/dp/B0DY95T5HB"
./b4w.ps1 --session amz-mw eval --file "output\amazon-microwaves-2026-10-26\scripts\extract_product.js"
```

v1 返回 40 多个字段，但一眼就能看出不对：

```text
rank        =              ← 空
jsVariables =              ← 空
soldby      =              ← 空
instock     = In Stock {"isInternal":false,...}         ← 把内联 JSON 也抓进来了
overviewbullets = .po-break-word { word-break: ...}     ← 抓到 <style> 文本
weight      = 23.8 pounds Additional details Color Bla  ← 贪婪正则串行
```

不猜，直接跑**探活脚本** `diag.js`，把候选选择器逐个探一遍：

| 探测项 | 结果 |
| --- | --- |
| `#detailBullets_feature_div` | ❌ 不存在 |
| `#productDetails_detailBullets_sections1` | ❌ 不存在 |
| `#productDetails_techSpec_section_1` | ❌ 不存在 |
| `#tabular-buybox` | ❌ 不存在（没有 "Sold by / Ships from" 两列表） |
| `#sellerProfileTriggerId` | ❌ 不存在 |
| `#sims-consolidated-1` / `#sims_fbt` | ❌ 不存在（FBT 换成 `#sims-simsContainer_feature_div_0`） |
| `#merchantInfoFeature_feature_div` | ✅ 文本 `Shipper / Seller Amazon.com` |
| `#productDetails_feature_div` | ✅ 折叠面板：Features & Specs / Measurements / Additional details |
| `#productOverview_feature_div` | ✅ `Brand / Product Dimensions / Color / Capacity …` |
| 详情表格 | ✅ 31 个 `tr`：`Item Weight 23.8 pounds`、`Item Dimensions D x W x H 12.93"D x 17.52"W x 9.53"H` |
| 星级直方图 | ⚠️ 形态变成 `a[aria-label="69 percent of reviews have 5 stars"]`，不再有可读的 "5 star 69%" |
| A+ 图片 | ⚠️ 全是 `grey-pixel.gif`，真地址在 `data-src` |

**结论：2026 版详情页整体换血**。只靠老选择器的提取器必然大面积空值——不是"页面没有内容"，而是"选择器问错了地方"。

### 5.6 重写提取器 v2

改动要点（完整代码见[附录 A](#附录-a提取器-extract_product_v3js)，v3 是在 v2 基础上的徽章/描述修正）：

1. **规格表用"键值对抽取器"统一**：`kvFrom(root)` 遍历 `tr`（取首/末单元格）与 `li`（按第一个冒号切分）产出 `{label: value}`，
   再 `JSON.stringify` 落到 `detailbullets`。`weight` / `volume` / `onsaletime` 都从这张字典按 key 取，不再写贪婪正则。
2. **卖家/发货方**：`#merchantInfoFeature_feature_div` 的 `Shipper / Seller X` → `#tabular-buybox` → buybox 范围内的
   `Sold by:` / `Ships from:` → 全文兜底，四级回退。
3. **评分直方图**：优先 `aria-label="N percent of reviews have M stars"`，退化为 `#histogramTable` 文本正则，
   再退化为"按 5→1 顺序取百分数"。
4. **A+ 图片**：`data-src` → `src`，过滤 `grey-pixel` / `transparent-pixel`。
5. **价格**：`#corePrice_feature_div` → `#apex_desktop`；`yousave` 变成**计算字段**（`List Price − Price` + 折扣百分比）。
6. **排名**：全页找含 `Best Sellers Rank` 的 `tr` / `li`，`rank` 存内层 HTML、`rank2` 存纯文本；
   两者都空时回退成榜单位次（`#5 in Microwave Ovens (Best Sellers list)`）。
7. **品牌**：`#bylineInfo`（`Visit the Chefman Store`）→ 概览里的 `Brand` → 详情里的 `Brand Name`。
8. **库存**：只取 `#availability` 的 `innerText` 首行，避开紧跟其后的内联 JSON。

### 5.7 为什么还要第三轮：覆盖率全绿 ≠ 数据对

v2 跑完，导出脚本打印 `字段 nonempty/20`，看上去很健康。但抽查几个 0/1 字段就露馅了：

```text
isac    Counter({'1': 20})   ← 20 个商品全是 Amazon's Choice？不可能
isbs    Counter({'1': 1})    ← 合理
isprime Counter({'0': 20})   ← 需要确认是"真的没有"还是"没抓对"
```

回看 v2 的判定条件就明白了：

```js
// v2 —— 错在这里：容器在每一页都存在（可能为空），存在性判断恒真
const isAC = /Amazon's Choice/i.test(badgeTxt) || !!q('#acBadge_feature_div, [...]');
```

`#acBadge_feature_div` 是**每页都有的骨架节点**，没徽章时里面是空的，但元素存在 → `!!q(...)` 恒为 `true`。
同类问题还有两处：

- `desc` 只查了 `#productDescription`（新版已迁到 `#productDescription_feature_div`）→ 大量空值；
- `isprime` 只找 `i.a-icon-prime` / `#prime-badge`，没覆盖 2026 布局的 `i.header-prime-logo`——
  结果恰好也是 0（本会话未登录 Prime），但**判定逻辑本身不可信**，属于"碰巧对了"。

v3 的修法：

```js
// 只看"渲染出来的内容"，不看容器是否存在
const acBadgeText = txt('#acBadge_feature_div');          // innerText，隐藏即空
const isAC = /Amazon's Choice/i.test(acBadgeText) || !!q('#acBadge_feature_div [aria-label*="Choice"]');
const isBS = /Best Seller/i.test(txt('#zeitgeistBadge_feature_div'));
// Prime 看徽标元素本身（本会话未登录，页面渲染的是 "Join Prime" 引导 → 0/20 是真实结果）
const primeHit = !!q('#desktop_buybox i.header-prime-logo, #centerCol i.a-icon-prime, #prime-badge');
```

修完的真实分布：`isac` 17/20、`isbs` 1/20、`isprime` 0/20（未登录 Prime）。**这才是能用的数据。**

> 教训：**覆盖率只证明"有值"，不证明"值对"**。0/1 字段要抽查分布是否合理
> （20/20 全为 1 通常就是 bug），文本字段要肉眼读两条。

### 5.8 三轮全量跑批

跑批器 `run_pass.ps1` 的逻辑（见[附录 B](#附录-b跑批器-run_passps1)）：

```text
for 每个 ASIN：
  goto /dp/<ASIN>
    ├─ 落在 /checkpoint/ | /captcha/ | /signin/ → 点一次 "Continue shopping"，再 goto
    ├─ 仍在拦截页 → 休息 25s，再 goto
    └─ 仍被拦 → 记日志跳过（本轮不写文件，保留上一轮数据）
  sleep 2–4s（随机，避免机械节奏）
  eval --file extract_product_v3.js
    ├─ 输出里找不到 '{"url"' → 4s 后重试一次
    └─ 校验 JSON 可解析 → 写 raw3/<ASIN>.json
  sleep 2–5s
```

三轮各写独立目录（`raw/` `raw2/` `raw3/`），单页失败**绝不覆盖**上一轮的成品：

```text
v1  00:04 → 00:13   20/20   raw/    首版，布局选择器大面积失效
v2  00:14 → 00:22   20/20   raw2/   布局修好，brand/soldby 修回 19–20/20
v3  00:33 → 00:40   20/20   raw3/   徽章与 desc 修好，定稿
```

每轮 8–9 分钟：20 页 × 2 条命令（`goto` + `eval`）+ 随机间隔（页面加载 2–5s、页间 2–5s）。

### 5.9 导出电子表格

导出脚本（[附录 C](#附录-c导出器-export_to_xlsxpy)）做四件事：

1. 从源表读第 1 行做列名，读第 2 行做 `fields` 页；
2. `raw3/` → `raw2/` → `raw/` 逐级回退合并；
3. 补齐**列表级字段**：`id`（1–20）、`href`（榜单卡片链接）、`label`、`keywords`、`category`（`289935`）、
   `referer`（榜单页）、`task_time`；
4. 清洗单元格：去控制字符、单格截断 30 000 字符（Excel 上限 32 767）。

```powershell
python "output\amazon-microwaves-2026-10-26\scripts\export_to_xlsx.py"
```

```text
saved: ...\amazon_microwaves_top20.xlsx
rows: 20 cols: 75 missing: []
```

---

## 6. 十个坑：现象 / 根因 / 处理

| # | 现象 | 根因 | 处理 |
|---|------|------|------|
| 1 | 第一次命令"没反应"1–3 分钟 | 源码树首次运行要编译 Rust CLI + Maven 打包 runtime bundle | 等，只发生一次；强制重建用 `./b4w.ps1 -Rebuild <cmd>` |
| 2 | 价格全是 `HKD`，部分商品无价 | 按 IP 判定地区 = Hong Kong | 写 `i18n-prefs=USD` cookie + 把配送地设为 `10001` |
| 3 | 改完配送地后访问详情页被丢到 `/checkpoint/continue?token=…` | Amazon 对"地址刚变更 + 连续导航"的会话要求一次人机确认 | 该页只有一个 `POST /checkpoint/validate` 表单（`button[name=continueClick]`），JS 点完会被踢到 sign-in。**换干净会话重做地址设置**，之后 60 次导航零拦截 |
| 4 | 标题变中文 | 系统 locale zh-CN，Amazon 自动翻译 | 写 `lc-main=en_US` cookie 后重载 |
| 5 | `rank` / `jsVariables` / `soldby` 整片为空，`instock` 混进 JSON | 2026 新版详情页换容器：`#detailBullets`→`#productDetails_feature_div`、`#tabular-buybox`→`#merchantInfoFeature` | 先用 `diag.js` 探活，再重写选择器 + 多级回退 |
| 6 | `soldby` 全是 `Amazon Resale` | 正则命中页面下方"其他卖家"区块的第一行 `Sold by:` | 改为优先 `Shipper / Seller X`，其次限定 buybox 作用域 |
| 7 | 直方图只抓到 `5星 69%` 一条 | 新版把星数标签做成 `aria-hidden`，纯文本只剩一列 | 解析 `a[aria-label="N percent of reviews have M stars"]` |
| 8 | `detailimgs` 全是 `grey-pixel.gif` | A+ 图片懒加载，`src` 是占位图 | 读 `data-src` / `data-old-hires` |
| 9 | `isac` 20/20 全为 1 | 徽章容器在每页都存在（没徽章时为空），"元素存在"判断恒真 | 改成看**渲染文本**或**徽标元素**，原始文本（`acBadgeText` / `bsBadgeText`）留进 JSON 便于复查；顺带补上 `i.header-prime-logo` |
| 10 | `desc` 大面积空 | 新版描述迁到 `#productDescription_feature_div`，旧代码只查 `#productDescription` | 补容器，并保留 `#aplus` 兜底 |

> 关于坑 3 的取舍：也可以"带着 Hong Kong 地区"采集，但那样 `price` / `buyboxprice` / `deliverto` 会大面积空缺，
> 数据没法用。值得花一次会话重建。

---

## 7. 为什么用 `eval` 而不是 `htmlsnapshot query`

| 方案 | 适合 | 本次为何不选 |
| --- | --- | --- |
| `htmlsnapshot get all text "<css>"` | 取单一字段的列表 | 75 个字段要发 75 次请求，且拿不到属性（`data-src`、`aria-label`、`data-a-dynamic-image`） |
| `htmlsnapshot query --sql @q.sql`（X-SQL） | 相关行（一个列表页 N 行 × 少量列） | 详情页是**单行 75 列**的异构结构；X-SQL 里做 4 级回退、算折扣、读动态属性很别扭 |
| `eval --file page.js` | ✅ 一次往返返回整页结构化 JSON | 需要自己维护 JS，但换来"一次拿 75 个字段 + 任意回退逻辑" |

`eval` 的硬约束（都踩过）：

- `eval --ref` 必须是**箭头函数**：`element => element.textContent`；写 `element.textContent` 会返回 `null`；
- 页面 JS 的 `undefined` 会打印成空串，所以统一 `JSON.stringify` 再按 `{"url"` … `}` 截取（本次所有产物都是这样落盘的）；
- Windows 下**别内联复杂 JS**，一律 `--file`；`--sql @file` 同理，PowerShell 里 `@` 是 splatting 操作符，路径要加引号。

---

## 8. 数据质量与缺口

75 列**全部有值来源**，有些字段在 2026 版详情页上确实不存在（不是脚本失败）：

| 字段 | 覆盖 | 原因 |
| --- | --- | --- |
| `qanum` | 0/20 | 新版详情页不再渲染 Q&A 区块（`#askATFLink` 已移除） |
| `scoresbyfeature` | 0/20 | 页面无"按功能评分"模块 |
| `reviewsmention` | 0/20 | 源表本身标注"暂时取不到" |
| `onsaletime` | 0/20 | 新版不再输出 `Date First Available`（全页文本已确认查无此项） |
| `isprime` | 0/20 | 会话未登录 Prime，页面只渲染 "Join Prime" 引导（`#primeDPUpsellStaticContainerNPA`），无 Prime 徽标 |
| `listprice` | 14/20 | 只有存在划线价的商品才有 |
| `withdeal` | 10/20 | 只有带 `Limited time deal` 徽章的商品才有 |
| `boughttogether` | 15/20 | 部分商品无 FBT（Frequently Bought Together）模块 |
| `desc` | 18/20 | `#1`、`#11` 的 A+ 是纯图片 / 视频，没有文本 |
| `price` | 19/20 | `B00F2QFX5O`（GE 30" 吊柜式）当日 `Currently unavailable`，确实无价 |
| `soldby` / `shipsfrom` / `sellerID` / `feedbackurl` | 19/20 | 同上一条：不可售商品没有 buybox 卖家信息 |
| `weight` / `quantity` / `deliverto` | 18–19/20 | 个别页面无该模块 |

可校验的交叉点（都已核对）：

- `price` 与 `buyboxprice` **20/20 完全一致**（其中 1 条两边都为空）；
- `globaldeliverto` 20 条全为 `New York 10001`，说明地区设置对 60 次导航持续生效；
- `reviews` 与 `starnum` 同源同值（与源表口径一致）；
- `isbs=1` 的唯一一条（`B00F2QFX5O`）页面上确实写着 `#1 Best Seller in Over-the-Range Microwave Ovens`；
- `isac=0` 的 3 条（`#7` `#16` `#19`）复查后确实没有 Amazon's Choice 文案。

---

## 9. 复现（TL;DR）

```powershell
# 0) 前提：JDK 25+、Rust、本仓库源码树
cd D:\workspace\Browser4\Browser4-4.15-feat

# 1) 起后端 + 打开榜单页（首次会编译，几分钟）
./b4w.ps1 --session amz-mw open --headless "https://www.amazon.com/Best-Sellers-Kitchen-Dining-Microwave-Ovens/zgbs/kitchen/289935"

# 2) 币种 / 语言（配送地按 5.3 用 ref 交互设置一次）
./b4w.ps1 --session amz-mw eval "document.cookie='i18n-prefs=USD; domain=.amazon.com; path=/'"

# 3) 抓榜单 30 条 → bs_list.json
./b4w.ps1 --session amz-mw eval --file "output\amazon-microwaves-2026-10-26\scripts\bs_list.js"

# 4) 20 个详情页跑批（约 8 分钟）
pwsh -NoProfile -File "output\amazon-microwaves-2026-10-26\scripts\run_pass.ps1" -ExtractFile "output\amazon-microwaves-2026-10-26\scripts\extract_product_v3.js" -RawDirName "raw3"

# 5) 导出 xlsx + csv
python "output\amazon-microwaves-2026-10-26\scripts\export_to_xlsx.py"
python "output\amazon-microwaves-2026-10-26\scripts\finalize.py"
```

---

## 10. 可复用经验

1. **先探活，再写选择器。** 详情页布局一年一换，跑一个 `diag.js` 列出"哪些容器在/不在、文本长什么样"，比猜 20 分钟划算。
2. **地区 / 币种 / 语言是三个独立开关**：`i18n-prefs`（币种）、配送 ZIP（地区）、`lc-main`（语言）。任何一个没对齐，价格或标题就失真。
3. **可信事件优先。** 开弹层可以用 JS `click()`，但**填输入框、点提交**尽量用 `fill` / `click` + ref（CDP 可信事件）。
4. **地址变更期间别连续导航。** 改完地区先回列表页缓一下，再开始批量抓取。
5. **容器存在 ≠ 有值。** 徽章、Prime、促销这类"骨架节点"在每页都有，判定只能看**渲染出的文本或徽标元素**。
6. **覆盖率全绿不代表数据对。** 0/1 字段看分布（20/20 全 1 基本就是 bug），文本字段抽两条肉眼读。
7. **跑批要幂等 + 可续跑**：写文件前 `Test-Path` 跳过、单页失败不覆盖旧数据、每轮结果分目录（`raw/` `raw2/` `raw3/`）。
8. **节奏随机化**：页间 2–5 秒随机、失败退避 25 秒；固定 0 延迟是最明显的机器特征。
9. **长文本控长度**：Excel 单格上限 32 767 字符，`detailbullets` / `jsVariables` 必须截断，否则文件写坏。
10. **留原始 JSON**。表格是"投影"，`raw3/*.json` 才是事实来源；改口径时不必重抓网站。
11. **列表级字段在导出阶段注入**：`id` / `href` / `label` / `keywords` / `category` / `referer` / `task_time` 详情页里没有。

---

## 附录 A：提取器 `extract_product_v3.js`

> 单页执行，返回一个 75 键（外加若干 QA 键）的 JSON 字符串，用 `eval --file` 调用。

```js
(() => {
  const q = s => document.querySelector(s);
  const nAll = s => document.querySelectorAll(s).length;
  const txt = s => { const e = q(s); return e ? e.innerText.replace(/\s+/g, ' ').trim() : ''; };
  const abs = u => { try { return new URL(u, location.origin).href; } catch (e) { return u; } };
  const stars = v => { const m = (v || '').match(/([0-9.]+)\s*out of 5/); return m ? m[1] : ''; };
  const clean = s => (s || '').replace(/\s+/g, ' ').trim();

  const asin = (q('input#ASIN') || {}).value
    || (location.pathname.match(/\/(?:dp|gp\/product)\/([A-Z0-9]{10})/) || [])[1] || '';

  // ---------- key/value tables -------------------------------------------
  const kvFrom = root => {
    const out = {};
    if (!root) return out;
    root.querySelectorAll('tr').forEach(tr => {
      const cells = Array.from(tr.querySelectorAll('th, td')).map(c => clean(c.innerText));
      if (cells.length >= 2) {
        const k = cells[0].replace(/:$/, '');
        const v = cells[cells.length - 1];
        if (k && v && !out[k]) out[k] = v;
      }
    });
    root.querySelectorAll('li').forEach(li => {
      const t = clean(li.innerText);
      const i = t.indexOf(':');
      if (i > 0 && i < 60) {
        const k = t.slice(0, i).trim(); const v = t.slice(i + 1).trim();
        if (k && v && !out[k]) out[k] = v;
      }
    });
    return out;
  };

  const detailsRoot = q('#productDetails_feature_div') || q('#prodDetails') || q('#detailBullets_feature_div');
  const kv = kvFrom(detailsRoot);
  const kvOverview = kvFrom(q('#productOverview_feature_div'));
  const rawDetailText = clean((detailsRoot ? detailsRoot.textContent : '') + ' ' +
    ((q('#detailBulletsWrapper_feature_div') || {}).textContent || '') + ' ' +
    ((q('#productDetails_detailBullets_sections1') || {}).textContent || ''));
  const grabRaw = re => { const m = rawDetailText.match(re); return m ? clean(m[1]) : ''; };

  const bodyText = document.body ? document.body.innerText : '';
  const bestSellersRank = (() => {
    const m = bodyText.match(/Best Sellers Rank:?[\s\S]{0,400}?(?=\n[A-Z][a-z]+ [A-Z]|\n\n|$)/);
    return m ? clean(m[0]).slice(0, 600) : '';
  })();
  const rankPair = (() => {
    const tr = Array.from(document.querySelectorAll('tr, li')).find(r => /Best Sellers Rank/i.test(clean(r.innerText)));
    if (tr) return { html: tr.innerHTML.replace(/\s+/g, ' ').trim(), text: clean(tr.innerText) };
    return { html: '', text: '' };
  })();

  // ---------- variations --------------------------------------------------
  const htmlAll = document.documentElement.innerHTML;
  const mVar = htmlAll.match(/num_total_variations"\s*:\s*"?(\d+)/);
  const mPar = htmlAll.match(/parentAsin"\s*:\s*"([A-Z0-9]{10})"/);
  const jsSeg = (() => {
    const s = Array.from(document.querySelectorAll('script:not([src])')).find(x => x.textContent.indexOf('num_total_variations') >= 0 || x.textContent.indexOf('asinVariationValues') >= 0);
    return s ? s.textContent.replace(/\s+/g, ' ').trim().slice(0, 1500) : '';
  })();

  // ---------- images ------------------------------------------------------
  const li = q('#landingImage');
  const imgSrc = li ? (li.getAttribute('data-old-hires') || li.getAttribute('src') || '') : '';
  let dyn = '';
  if (li) {
    const raw = li.getAttribute('data-a-dynamic-image');
    if (raw) { try { dyn = JSON.stringify(Object.keys(JSON.parse(raw))); } catch (e) { dyn = raw.slice(0, 1200); } }
  }
  const galleryUrls = Array.from(document.querySelectorAll('#altImages li img'))
    .map(i => i.getAttribute('src') || '')
    .filter(u => u && u.indexOf('play-icon') < 0 && u.indexOf('grey-pixel') < 0 && u.indexOf('transparent-pixel') < 0)
    .map(u => u.replace(/\._[A-Z0-9,_]+_\./, '._AC_SL1200_.'));

  // ---------- A+ / description -------------------------------------------
  const aplusRoot = q('#aplus, #aplus_feature_div');
  const aplusImgs = Array.from(document.querySelectorAll('#aplus img, #aplus_feature_div img, #aplusBrandStory_feature_div img'))
    .map(i => i.getAttribute('data-src') || i.getAttribute('src') || '')
    .filter(u => u && u.indexOf('grey-pixel') < 0 && u.indexOf('transparent-pixel') < 0).slice(0, 12);
  const aplusVideos = Array.from(document.querySelectorAll('#aplus video, #aplus [data-video-url]'))
    .map(v => v.getAttribute('data-video-url') || v.getAttribute('src') || '').filter(Boolean).slice(0, 8);
  const descText = clean((q('#productDescription') || {}).innerText || '')
    || clean((q('#productDescription_feature_div') || {}).innerText || '')
    || clean((q('#aplus') || {}).innerText || '').replace(/^Product description\s*/i, '').slice(0, 4000);

  // ---------- price -------------------------------------------------------
  const money = s => { const m = (s || '').match(/\$[\d,]+(?:\.\d{2})?/); return m ? m[0] : ''; };
  const num = s => { const v = parseFloat((s || '').replace(/[^0-9.]/g, '')); return isNaN(v) ? 0 : v; };
  const apexTxt = clean((q('#apex_desktop') || {}).innerText || '');
  const price = money(clean((q('#corePrice_feature_div') || {}).innerText)) || money(apexTxt) || clean((q('#corePrice_feature_div') || {}).innerText);
  const buyboxPrice = money(clean((q('#buybox') || {}).innerText)) || price;
  const listPriceRaw = (apexTxt.match(/List Price:\s*(\$[\d,]+(?:\.\d{2})?)/) || [])[1]
    || money(clean((q('#listPrice') || {}).innerText)) || '';
  const pctOff = (apexTxt.match(/-(\d+)%/) || [])[1] || '';
  const yousave = (() => {
    if (listPriceRaw && price && num(listPriceRaw) > num(price)) {
      const diff = (num(listPriceRaw) - num(price)).toFixed(2);
      return '$' + diff + (pctOff ? ' (' + pctOff + '%)' : '');
    }
    return pctOff ? '-' + pctOff + '%' : '';
  })();

  // ---------- rating ------------------------------------------------------
  const hist = {};
  document.querySelectorAll('a[aria-label*="percent of reviews have"], #histogramTable a').forEach(a => {
    const lab = a.getAttribute('aria-label') || clean(a.innerText);
    const m = lab.match(/(\d+)\s*percent of reviews have\s*(\d)\s*star/i);
    if (m) hist['score' + m[2] + 'percent'] = m[1] + '%';
  });
  if (!Object.keys(hist).length) {
    const ht = clean((q('#histogramTable') || {}).innerText);
    (ht.match(/(\d)\s*star\s*(\d+)%/g) || []).forEach(s => {
      const m = s.match(/(\d)\s*star\s*(\d+)%/);
      if (m) hist['score' + m[1] + 'percent'] = m[2] + '%';
    });
  }
  // histogram is emitted 5,4,3,2,1 without star labels -> fall back to order
  if (!Object.keys(hist).length) {
    const pcts = (clean((q('#histogramTable') || {}).innerText).match(/(\d+)%/g) || []).map(x => x);
    ['5', '4', '3', '2', '1'].forEach((s, i) => { if (pcts[i]) hist['score' + s + 'percent'] = pcts[i]; });
  }

  // ---------- seller / delivery ------------------------------------------
  const merchantInfoText = clean((q('#merchantInfoFeature_feature_div') || {}).innerText || '');
  const tabTexts = Array.from(document.querySelectorAll('#tabular-buybox .tabular-buybox-text')).map(e => clean(e.innerText));
  let tabSold = '', tabShip = '';
  for (let i = 0; i < tabTexts.length - 1; i++) {
    if (/^sold by$/i.test(tabTexts[i])) tabSold = tabTexts[i + 1];
    if (/^ships from$/i.test(tabTexts[i])) tabShip = tabTexts[i + 1];
  }
  const buyboxScopeText = ['#desktop_buybox', '#buybox', '#tabular-buybox', '#merchantInfoFeature_feature_div',
    '#corePrice_feature_div', '#apex_desktop']
    .map(s => clean((q(s) || {}).innerText || '')).filter(Boolean).join(' \n ');
  const scopeSold = (buyboxScopeText.match(/Sold by:\s*([^\n]{1,60}?)(?=\s{2,}|Ships from|$)/) || [])[1] || '';
  const scopeShip = (buyboxScopeText.match(/Ships from:\s*([^\n]{1,60}?)(?=\s{2,}|Sold by|$)/) || [])[1] || '';
  const miPair = merchantInfoText.match(/Shipper\s*\/\s*Seller\s*[:\s]\s*(.+)/i);
  const soldBy = (miPair ? miPair[1] : '') || tabSold || scopeSold
    || (bodyText.match(/Sold by:\s*([^\n]{1,60})/) || [])[1] || clean((q('#sellerProfileTriggerId') || {}).innerText || '');
  const shipsFrom = (miPair ? miPair[1] : '') || tabShip || scopeShip
    || (bodyText.match(/Ships from:\s*([^\n]{1,60})/) || [])[1] || clean((q('#fulfillerInfoFeature_feature_div') || {}).innerText || '');
  const sellerLink = Array.from(document.querySelectorAll('a[href*="seller="], a[href*="/sp?"]'))
    .map(a => a.getAttribute('href') || '').find(h => /seller=[A-Z0-9]{5,}/.test(h)) || '';
  const sellerID = (sellerLink.match(/seller=([A-Z0-9]{5,})/) || [])[1] || (/amazon/i.test(soldBy) ? 'ATVPDKIKX0DER' : '');
  const otherSellers = (bodyText.match(/New & Used \((\d+)\)/) || [])[1]
    || (bodyText.match(/\((\d+)\)\s*from/) || [])[1]
    || (bodyText.match(/(\d+)\s+other sellers/) || [])[1] || '';
  const questions = (bodyText.match(/(\d+)\s+answered questions?/i) || [])[0] || '';
  const coupon = clean((q('#promoPriceBlockMessage') || {}).innerText || '') || (bodyText.match(/Apply \$?[\d.]+ coupon[^\n]{0,40}/i) || [])[0] || '';

  // ---------- bought together --------------------------------------------
  const simsBox = Array.from(document.querySelectorAll('div[id*="sims-simsContainer"], div[id*="sims-consolidated"], div#sims_fbt, div[id*="sims-productBundle"]'))
    .find(d => /Frequently bought together|bought together/i.test(d.innerText));
  const simsText = simsBox ? clean(simsBox.innerText).slice(0, 2000) : '';
  const simsImgs = simsBox ? Array.from(simsBox.querySelectorAll('img')).map(i => i.getAttribute('src') || '').filter(Boolean).slice(0, 6) : [];
  const simsMeta = simsBox ? Array.from(simsBox.querySelectorAll('[data-a-state]')).map(e => (e.getAttribute('data-a-state') || '').slice(0, 200)).filter(Boolean).slice(0, 4) : [];

  // ---------- breadcrumb --------------------------------------------------
  const crumbAnchors = Array.from(document.querySelectorAll('#wayfinding-breadcrumbs_feature_div a, #productCategory a'));
  const crumbLinks = crumbAnchors.map(a => abs(a.getAttribute('href') || ''));
  const crumbNames = crumbAnchors.map(a => clean(a.innerText));

  // ---------- badges ------------------------------------------------------
  // NOTE: the badge *containers* (#acBadge_feature_div / #zeitgeistBadge_feature_div)
  // exist on every detail page even when empty, so presence alone is not a signal -
  // match the rendered text / aria-label instead.
  const acBadgeText = txt('#acBadge_feature_div');
  const bsBadgeText = txt('#zeitgeistBadge_feature_div');
  const ariaAc = !!q('#acBadge_feature_div [aria-label*="Choice"]');
  const ariaBs = !!q('#zeitgeistBadge_feature_div [aria-label*="Best Seller"]');
  const isAC = /Amazon's Choice|Amazon\u2019s Choice/i.test(acBadgeText) || ariaAc;
  const isBS = /Best Seller/i.test(bsBadgeText) || ariaBs;

  // Prime: the 2026 layout keeps #primeBadgeAndShippingMessageAbbreviated_feature_div on
  // EVERY page (empty when the offer is not Prime), so look for the actual badge element.
  const primeBox = q('#primeBadgeAndShippingMessageAbbreviated_feature_div');
  const primeBadgeText = primeBox ? clean(primeBox.innerText) : '';
  const primeHit = !!(q('#desktop_buybox i.header-prime-logo, #centerCol i.header-prime-logo, ' +
    '#buybox i.header-prime-logo, #desktop_buybox i.a-icon-prime, #centerCol i.a-icon-prime, ' +
    '#buybox i.a-icon-prime, #buybox .a-icon-prime, #prime-badge'))
    || !!(primeBox && primeBox.querySelector('i, img, svg'));

  return JSON.stringify({
    url: location.origin + '/dp/' + asin,
    finalUrl: location.href,
    asin: asin,
    title: txt('#productTitle'),
    brand: clean((q('#bylineInfo') || {}).innerText || '')
      || kvOverview['Brand'] || kv['Brand Name'] || kv['Brand'] || '',
    brandHref: (() => { const a = q('#bylineInfo'); return a ? abs(a.getAttribute('href') || '') : ''; })(),
    merchantInfoText: merchantInfoText,
    buyboxScopeText: buyboxScopeText.slice(0, 300),
    overviewbullets: Object.entries(kvOverview).map(([k, v]) => k + ': ' + v).join(' | '),
    featurebullets: txt('#feature-bullets'),
    detailbullets: JSON.stringify(kv).slice(0, 6000),
    desc: descText,
    rank: rankPair.html,
    rank2: rankPair.text,
    bestSellerRankPage: bestSellersRank || rankPair.text,
    pasin: mPar ? mPar[1] : '',
    totalvariations: mVar ? mVar[1] : '',
    jsVariables: jsSeg,
    soldby: clean(soldBy),
    shipsfrom: clean(shipsFrom),
    sellerID: sellerID,
    sellerHref: sellerLink ? abs(sellerLink) : (sellerID ? location.origin + '/sp?seller=' + sellerID : ''),
    feedbackurl: sellerLink ? abs(sellerLink) : (sellerID ? location.origin + '/sp?seller=' + sellerID : ''),
    quantity: (() => { const o = Array.from(document.querySelectorAll('#quantity option')).map(x => x.textContent.trim()); return o.length ? '(' + o.join(', ') + ')' : ''; })(),
    globaldeliverto: txt('#glow-ingress-line2'),
    deliverto: txt('#mir-layout-DELIVERY_BLOCK'),
    price: price,
    buyboxprice: buyboxPrice,
    listprice: listPriceRaw,
    instock: txt('#availability').slice(0, 60),
    stock_status: (clean((q('#availability') || {}).innerText || '').split(/\n/)[0] || '').slice(0, 60),
    isaddcart: q('#add-to-cart-button') ? 1 : 0,
    isbuy: q('#buy-now-button') ? 1 : 0,
    isac: isAC ? 1 : 0,
    isbs: isBS ? 1 : 0,
    iscoupon: coupon ? 1 : 0,
    couponText: clean(coupon),
    isprime: primeHit ? 1 : 0,
    acBadgeText: acBadgeText.slice(0, 60),
    bsBadgeText: bsBadgeText.slice(0, 60),
    primeBadgeText: primeBadgeText.slice(0, 60),
    withdeal: txt('#dealBadge_feature_div'),
    yousave: yousave,
    img: li ? li.outerHTML.replace(/\s+/g, ' ').trim().slice(0, 1200) : '',
    imgsrc: imgSrc,
    dynamicimgsrcs: dyn,
    gallery: '(' + galleryUrls.join(', ') + ')',
    detailimgs: '(' + aplusImgs.join(', ') + ')',
    detailvideos: '(' + aplusVideos.concat(Array.from(document.querySelectorAll('#vseVideosContainer a, .vse-player-container a')).map(a => a.getAttribute('href') || '').filter(Boolean)).slice(0, 10).join(', ') + ')',
    isa: aplusRoot ? 1 : 0,
    boughttogethermetadata: '(' + simsMeta.join(', ') + ')',
    boughttogetherimgs: '(' + simsImgs.join(', ') + ')',
    boughttogether: simsText,
    othersellernum: clean(otherSellers),
    qanum: clean(questions),
    score: stars((q('#acrPopover') || {}).title || '') || stars(clean((q('#acrPopover') || {}).innerText || '')),
    reviews: (clean((q('#acrCustomerReviewText') || {}).innerText || '').match(/[\d,]+/) || [''])[0],
    starnum: (clean((q('#acrCustomerReviewText') || {}).innerText || '').match(/[\d,]+/) || [''])[0],
    score5percent: hist.score5percent || '',
    score4percent: hist.score4percent || '',
    score3percent: hist.score3percent || '',
    score2percent: hist.score2percent || '',
    score1percent: hist.score1percent || '',
    scoresbyfeature: '',
    weight: kv['Item Weight'] || kv['Weight'] || '',
    volume: kv['Item Dimensions D x W x H'] || kv['Product Dimensions'] || '',
    reviewsmention: '',
    onsaletime: kv['Date First Available'] || grabRaw(/Date First Available[:\s]*([A-Z][a-z]+ \d{1,2}, \d{4})/) || grabRaw(/Date First Available[:\s]*(\d{1,2}\/\d{1,2}\/\d{4})/) || '',
    marketplaceID: 'ATVPDKIKX0DER',
    reviewsurl: location.origin + '/product-reviews/' + asin,
    sellsameurl: location.origin + '/gp/offer-listing/' + asin,
    categorypath: '(' + crumbLinks.join(', ') + ')',
    categoryname: crumbNames[crumbNames.length - 1] || '',
    categorynamelevel: crumbNames.join('|'),
    referer: document.referrer || '',
    numchars: bodyText.length,
    numlinks: nAll('a[href]'),
    numimgs: nAll('img'),
    height: document.documentElement.scrollHeight
  });
})()

```

## 附录 B：跑批器 `run_pass.ps1`

> `-ExtractFile` 指定提取器、`-RawDirName` 指定输出目录（`raw` / `raw2` / `raw3`）、`-SkipExisting` 断点续跑。

```powershell
param(
    [string]$ExtractFile = "output\amazon-microwaves-2026-10-26\extract_product.js",
    [string]$RawDirName = "raw",
    [switch]$SkipExisting
)
$ErrorActionPreference = 'Stop'
$root = "D:\workspace\Browser4\Browser4-4.15-feat"
Set-Location $root
$outDir = Join-Path $root "output\amazon-microwaves-2026-10-26"
$rawDir = Join-Path $outDir $RawDirName
New-Item -ItemType Directory -Force -Path $rawDir | Out-Null
$extractFile = Join-Path $root $ExtractFile
$session = "amz-mw"
$items = (Get-Content (Join-Path $outDir "bs_list.json") -Raw | ConvertFrom-Json).items | Select-Object -First 20

function Invoke-B4([string[]]$b4args) {
    return (& "$root\b4w.ps1" --session $session @b4args 2>&1 | Out-String)
}
function Get-PageUrl([string]$text) {
    $m = [regex]::Match($text, '- Page URL:\s*(\S+)')
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
}
function Swallow-Checkpoint {
    $f = Join-Path $env:TEMP "cp_continue2.js"
    Set-Content -Path $f -Value "(() => { const h = document.querySelector('#a-autoid-0'); if (!h) return 'no-btn'; const b = h.querySelector('button, input'); if (b) { b.click(); return 'clicked'; } return 'no-target'; })()" -Encoding utf8
    Start-Sleep -Seconds 2
    return (Invoke-B4 @("eval", "--file", $f))
}

$i = 0
foreach ($it in $items) {
    $i++
    $asin = $it.asin
    $dest = Join-Path $rawDir "$asin.json"
    if ($SkipExisting -and (Test-Path $dest)) { Write-Host "[$i/20] $asin cached, skip"; continue }
    Write-Host "[$i/20] $asin"
    $url = "https://www.amazon.com/dp/$asin"
    $r = Invoke-B4 @("goto", $url)
    $pageUrl = Get-PageUrl $r
    if ($pageUrl -match 'checkpoint|captcha|signin|sorry') {
        Write-Host "  !! landed on $pageUrl ; continue-click"
        $null = Swallow-Checkpoint
        Start-Sleep -Seconds 4
        $r = Invoke-B4 @("goto", $url); $pageUrl = Get-PageUrl $r
    }
    if ($pageUrl -match 'checkpoint|captcha|signin|sorry') {
        Write-Host "  !! still blocked ($pageUrl) ; pause 25s and retry"
        Start-Sleep -Seconds 25
        $r = Invoke-B4 @("goto", $url); $pageUrl = Get-PageUrl $r
    }
    if ($pageUrl -match 'checkpoint|captcha|signin|sorry') { Write-Host "  !! BLOCKED $asin"; continue }

    Start-Sleep -Seconds (Get-Random -Minimum 2 -Maximum 4)
    $out = Invoke-B4 @("eval", "--file", $extractFile)
    $s = $out.IndexOf('{"url"'); $e = $out.LastIndexOf('}')
    if ($s -lt 0) {
        Write-Host "  !! retry extract"
        Start-Sleep -Seconds 4
        $out = Invoke-B4 @("eval", "--file", $extractFile)
        $s = $out.IndexOf('{"url"'); $e = $out.LastIndexOf('}')
    }
    if ($s -ge 0 -and $e -gt $s) {
        $json = $out.Substring($s, $e - $s + 1)
        try { $null = $json | ConvertFrom-Json } catch { Write-Host "  !! invalid json"; continue }
        Set-Content -Path $dest -Value $json -Encoding utf8
        Write-Host "  ok -> $asin"
    } else { Write-Host "  !! no json for $asin" }
    Start-Sleep -Seconds (Get-Random -Minimum 2 -Maximum 5)
}
Write-Host "PASS DONE"
```

## 附录 C：导出器 `export_to_xlsx.py`

> 源表读列名与中文说明 → 按 `raw3` → `raw2` → `raw` 合并 → 补列表级字段 → 写 xlsx（含 `fields` 对照页）。

```python
# -*- coding: utf-8 -*-
"""Build the final spreadsheet for the Amazon microwave best-seller crawl.

Field order and column names come from
browser4-tests/pulsar-tests-common/src/test/resources/asin_field_description.xlsx (Sheet2 header row).
"""
import json
import os
import re
from datetime import datetime

import openpyxl
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

ROOT = r"D:\workspace\Browser4\Browser4-4.15-feat"
OUT = os.path.join(ROOT, "output", "amazon-microwaves-2026-10-26")
SRC = os.path.join(ROOT, "browser4-tests", "pulsar-tests-common", "src", "test",
                   "resources", "asin_field_description.xlsx")

BESTSELLER_PAGE = ("https://www.amazon.com/Best-Sellers-Kitchen-Dining-"
                   "Microwave-Ovens/zgbs/kitchen/289935")
LABEL = "microwave-bestsellers-2026-10-26"
KEYWORDS = "microwave ovens"
CATEGORY_ID = "289935"
MAXCELL = 30000

# extra (non-exported) keys collected by the extractor for QA / post-processing
EXTRA_KEYS = ["merchantInfoText", "buyboxScopeText", "couponText", "couponText",
              "brandHref", "sellerHref", "finalUrl", "bestSellerRankPage"]


def read_header():
    wb = openpyxl.load_workbook(SRC, data_only=True)
    ws = wb["Sheet2"]
    rows = list(ws.iter_rows(min_row=1, max_row=2, values_only=True))
    names = [c for c in rows[0] if c]
    descs = list(rows[1])[:len(names)] if len(rows) > 1 else []
    legend = [(n, (descs[i] if i < len(descs) else "") or "") for i, n in enumerate(names)]
    return names, legend


def clean(v):
    if v is None:
        return ""
    if isinstance(v, (int, float)):
        return v
    s = str(v)
    s = s.replace("\x00", "")
    s = re.sub(r"[\x01-\x08\x0b\x0c\x0e-\x1f]", " ", s)
    if len(s) > MAXCELL:
        s = s[:MAXCELL] + "...[truncated]"
    return s.strip()


def load_record(asin):
    for sub in ("raw3", "raw2", "raw"):
        p = os.path.join(OUT, sub, asin + ".json")
        if os.path.exists(p):
            try:
                with open(p, encoding="utf-8") as f:
                    return json.load(f), sub
            except Exception:
                continue
    return None, None


def brand_from_overview(rec):
    m = re.search(r"Brand:\s*([^|]+)", rec.get("overviewbullets") or "")
    if m:
        return m.group(1).strip()
    try:
        kv = json.loads(rec.get("detailbullets") or "{}")
        for k in ("Brand Name", "Brand", "Manufacturer"):
            if kv.get(k):
                return kv[k]
    except Exception:
        pass
    return ""


def main():
    header, legend = read_header()
    with open(os.path.join(OUT, "bs_list.json"), encoding="utf-8") as f:
        bs = json.load(f)
    items = bs["items"][:20]

    rows, missing = [], []
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    for idx, it in enumerate(items, start=1):
        asin = it["asin"]
        d, src = load_record(asin)
        if d is None:
            missing.append(asin)
            continue

        rec = dict(d)
        rec["id"] = idx
        rec["url"] = "https://www.amazon.com/dp/" + asin
        href = it.get("href") or ""
        rec["href"] = ("https://www.amazon.com" + href) if href.startswith("/") else href
        rec["label"] = LABEL
        rec["keywords"] = KEYWORDS
        rec["category"] = CATEGORY_ID
        rec["task_time"] = now
        rec["referer"] = BESTSELLER_PAGE

        # rank: page rank block when present, otherwise the position on today's best-seller list
        if not (rec.get("rank2") or "").strip():
            fallback = "%s in Microwave Ovens (Best Sellers list)" % (it.get("rank") or "#%d" % idx)
            rec["rank"] = fallback
            rec["rank2"] = fallback
        if not (rec.get("rank") or "").strip():
            rec["rank"] = rec.get("rank2", "")

        if not (rec.get("brand") or "").strip():
            rec["brand"] = brand_from_overview(rec)
        if not (rec.get("title") or "").strip():
            rec["title"] = it.get("title", "")
        if not (rec.get("imgsrc") or "").strip():
            rec["imgsrc"] = it.get("listImg", "")
        if not (rec.get("price") or "").strip():
            rec["price"] = it.get("listPrice", "")
        if not (rec.get("buyboxprice") or "").strip():
            rec["buyboxprice"] = rec.get("price") or it.get("listPrice", "")
        if not (rec.get("soldby") or "").strip():
            mi = rec.get("merchantInfoText") or ""
            m = re.search(r"Shipper\s*/\s*Seller\s*[:\s]\s*(.+)", mi)
            if m:
                rec["soldby"] = m.group(1).strip()
        if not (rec.get("shipsfrom") or "").strip():
            rec["shipsfrom"] = rec.get("soldby", "")
        if not (rec.get("sellerID") or "").strip() and "amazon" in (rec.get("soldby") or "").lower():
            rec["sellerID"] = "ATVPDKIKX0DER"
        if not (rec.get("feedbackurl") or "").strip() and rec.get("sellerID"):
            rec["feedbackurl"] = "https://www.amazon.com/sp?seller=" + rec["sellerID"]

        rows.append(rec)

    out_wb = openpyxl.Workbook()
    ws = out_wb.active
    ws.title = "microwaves_top20"

    hdr_font = Font(bold=True, color="FFFFFF")
    hdr_fill = PatternFill("solid", fgColor="305496")
    for c, name in enumerate(header, start=1):
        cell = ws.cell(row=1, column=c, value=name)
        cell.font = hdr_font
        cell.fill = hdr_fill
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)

    for r, rec in enumerate(rows, start=2):
        for c, name in enumerate(header, start=1):
            ws.cell(row=r, column=c, value=clean(rec.get(name, "")))

    widths = {"id": 5, "title": 55, "url": 40, "href": 30, "asin": 12, "pasin": 12,
              "price": 10, "buyboxprice": 11, "listprice": 10, "yousave": 14,
              "score": 7, "reviews": 9, "rank2": 38, "brand": 22, "soldby": 16,
              "shipsfrom": 14, "weight": 14, "volume": 26, "categoryname": 24,
              "instock": 12, "stock_status": 11, "label": 22, "keywords": 16,
              "task_time": 20, "deliverto": 28, "withdeal": 16, "othersellernum": 10,
              "score5percent": 9, "score4percent": 9, "score3percent": 9,
              "score2percent": 9, "score1percent": 9, "gallery": 30, "pay": 10}
    for c, name in enumerate(header, start=1):
        ws.column_dimensions[get_column_letter(c)].width = widths.get(name, 14)
    ws.freeze_panes = "D2"
    ws.auto_filter.ref = "A1:%s%d" % (get_column_letter(len(header)), len(rows) + 1)

    # legend sheet: field name + its description from the source workbook
    lg = out_wb.create_sheet("fields")
    lg.cell(row=1, column=1, value="field").font = hdr_font
    lg.cell(row=1, column=1).fill = hdr_fill
    lg.cell(row=1, column=2, value="description").font = hdr_font
    lg.cell(row=1, column=2).fill = hdr_fill
    for r, (nm, ds) in enumerate(legend, start=2):
        lg.cell(row=r, column=1, value=str(nm))
        lg.cell(row=r, column=2, value=str(ds))
    lg.column_dimensions["A"].width = 24
    lg.column_dimensions["B"].width = 42
    lg.freeze_panes = "A2"

    dest = os.path.join(OUT, "amazon_microwaves_top20.xlsx")
    out_wb.save(dest)

    # QA summary
    print("saved:", dest)
    print("rows:", len(rows), "cols:", len(header), "missing:", missing)
    for name in header:
        filled = sum(1 for r in rows if str(r.get(name, "")).strip() not in ("", "0", "None")
                     or str(r.get(name, "")).strip() == "0" and name.startswith("is"))
        print("  %-22s %2d/%d" % (name, filled, len(rows)))


if __name__ == "__main__":
    main()


```

## 附录 D：探活脚本 `diag.js`（定位新版布局的关键工具）

```js
(() => {
  const q = s => document.querySelector(s);
  const info = s => { const e = q(s); return e ? { found: true, len: e.innerText.length, txt: e.innerText.replace(/\s+/g, ' ').slice(0, 220) } : { found: false }; };
  const sels = ['#productOverview_feature_div', '#productDetails_feature_div', '#productDetails_detailBullets_sections1',
    '#productDetails_techSpec_section_1', '#detailBullets_feature_div', '#prodDetails', '#tabular-buybox',
    '#merchantInfoFeature_feature_div', '#sellerProfileTriggerId', '#fulfillerInfoFeature_feature_div',
    '#olp_feature_div', '#availability', '#histogramTable', '#askATFLink', '#askATF_feature_div',
    '#sims-consolidated-1', '#sims_fbt', '#aplus', '#aplus_feature_div', '#productDescription',
    '#productDescription_feature_div', '#acrPopover', '#acrCustomerReviewText', '#zeitgeistBadge_feature_div',
    '#acBadge_feature_div', '#promoPriceBlockMessage', '#corePrice_feature_div', '#apex_desktop', '#dealBadge_feature_div'];
  const out = {}; sels.forEach(s => out[s] = info(s));

  const body = document.body.innerText;
  out.__bestSellerRankLines = (body.match(/[^\n]*Best Sellers Rank[^\n]*/gi) || []).slice(0, 3);
  out.__soldByLines = (body.match(/[^\n]*(Sold by|Ships from)[^\n]*/gi) || []).slice(0, 6);
  out.__otherSellers = (body.match(/[^\n]*(other sellers|Other Sellers|more buying choices|used|options)[^\n]*/gi) || []).slice(0, 8);
  out.__questions = (body.match(/[^\n]*question[^\n]*/gi) || []).slice(0, 5);
  out.__histogramHtml = (q('#histogramTable') ? q('#histogramTable').innerHTML.replace(/\s+/g, ' ').slice(0, 900) : '');
  out.__detailRows = Array.from(document.querySelectorAll('#detailBullets_feature_div li, #productDetails_detailBullets_sections1 tr, #productDetails_techSpec_section_1 tr'))
    .map(r => r.innerText.replace(/\s+/g, ' ').trim()).slice(0, 40);
  out.__aplusImgs = Array.from(document.querySelectorAll('#aplus img, #aplus_feature_div img')).map(i => (i.getAttribute('src') || i.getAttribute('data-src') || '').slice(0, 100)).slice(0, 6);
  out.__simsText = Array.from(document.querySelectorAll('[id*="sims"], [data-a-component*="sims"]')).map(e => e.id).slice(0, 10);
  out.__primeBadges = Array.from(document.querySelectorAll('[class*="prime"], [aria-label*="Prime"]')).map(e => (e.className || '').toString().slice(0, 60)).slice(0, 6);
  return JSON.stringify(out, null, 1);
})()
```

## 附录 E：文件树

```text
output/amazon-microwaves-2026-10-26/
├── amazon_microwaves_top20.xlsx      # 主交付：20 行 × 75 列 + fields 对照页
├── amazon_microwaves_top20.csv       # CSV 副本（UTF-8 BOM）
├── bs_list.json                      # 榜单页 30 条原始记录
├── bs_list_raw.txt                   # bs_list.js 的原始 stdout（含 CLI 头部信息）
├── README.md                         # 面向交付对象的简短说明
├── raw/<ASIN>.json                   # 第一轮（v1 提取器）
├── raw2/<ASIN>.json                  # 第二轮（v2）
├── raw3/<ASIN>.json                  # 第三轮（v3，定稿）
└── scripts/
    ├── bs_list.js                    # 榜单卡片提取
    ├── diag.js                       # 详情页选择器探活
    ├── extract_product.js            # v1 提取器
    ├── extract_product_v2.js         # v2 提取器
    ├── extract_product_v3.js         # v3 提取器（定稿）
    ├── run_crawl.ps1                 # 首轮跑批
    ├── run_pass.ps1                  # 通用跑批（-ExtractFile / -RawDirName）
    ├── export_to_xlsx.py             # 导出 xlsx
    ├── finalize.py                   # 导出 csv + output/README.md
    └── gen_doc.py                    # 生成本文档

docs-dev/examples/amazon-microwaves-top20/
├── README.md                         # 本文档
├── extract_product_v3.js
├── run_pass.ps1
├── export_to_xlsx.py
├── diag.js
└── bs_list.js
```

---

## 附录 F：榜单页原始数据（前 20 / 共 30）

| 位次 | ASIN | 卡片价格 | 卡片评论数 | 卡片评分 |
|---|---|---|---|---|
| #1 | `B0DY95T5HB` | $69.95 | 5,325 | 4.3 out of 5 stars |
| #2 | `B071WCB1T6` | $149.99 | 32,831 | 4.4 out of 5 stars |
| #3 | `B07HG9Y3VL` | $99.89 | 23,116 | 4.2 out of 5 stars |
| #4 | `B0DPQPGC75` | $92.22 | 1,658 | 4.5 out of 5 stars |
| #5 | `B07HG9YGZY` | $89.87 | 12,472 | 4.4 out of 5 stars |
| #6 | `B0DV5SDY1R` | $89.99 | 1,118 | 4.0 out of 5 stars |
| #7 | `B01EIZSDWO` | $67.99 | 7,026 | 4.1 out of 5 stars |
| #8 | `B07GV78ZKK` | $68.95 | 25,200 | 4.4 out of 5 stars |
| #9 | `B07VS476X8` | $89.99 | 10,087 | 4.3 out of 5 stars |
| #10 | `B0H79N9LTR` | $69.93 | 336 | 4.4 out of 5 stars |
| #11 | `B0GSGNT5RQ` | $89.00 | 1,170 | 4.5 out of 5 stars |
| #12 | `B0F1HFBHRK` | $69.99 | 920 | 4.2 out of 5 stars |
| #13 | `B0DTQ3MF8G` | $129.99 | 492 | 4.5 out of 5 stars |
| #14 | `B0BVPQ6Z21` | $69.95 | 1,647 | 4.3 out of 5 stars |
| #15 | `B0GN34JCB6` | $199.99 | 139 | 4.5 out of 5 stars |
| #16 | `B00F2QFX5O` | — | 1,530 | 4.0 out of 5 stars |
| #17 | `B0D295GDXX` | $149.00 | 419 | 4.3 out of 5 stars |
| #18 | `B0DD87CCQP` | $118.00 | 322 | 4.1 out of 5 stars |
| #19 | `B0DD85QFY6` | — | 238 | 4.2 out of 5 stars |
| #20 | `B0D294MHR5` | $108.09 | 753 | 4.1 out of 5 stars |

> 卡片价格与详情页价格可能有个别差异（榜单缓存 / 变体推荐），最终以详情页 `price` 为准。
> 第 21–30 位未采集，仍保留在 `bs_list.json` 中。
