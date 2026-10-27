# -*- coding: utf-8 -*-
"""Generate docs-dev/examples/amazon-microwaves-top20/README.md from the crawl artifacts."""
import json
import os
import re
import shutil

import openpyxl

ROOT = r"D:\workspace\Browser4\Browser4-4.15-feat"
OUT = os.path.join(ROOT, "output", "amazon-microwaves-2026-10-26")
DOCDIR = os.path.join(ROOT, "docs-dev", "examples", "amazon-microwaves-top20")
os.makedirs(DOCDIR, exist_ok=True)

XLSX = os.path.join(OUT, "amazon_microwaves_top20.xlsx")
wb = openpyxl.load_workbook(XLSX)
ws = wb["microwaves_top20"]
hdr = [c.value for c in ws[1]]
rows = []
for r in range(2, ws.max_row + 1):
    rows.append({h: (ws.cell(row=r, column=i + 1).value) for i, h in enumerate(hdr)})


def s(v):
    return "" if v is None else str(v).strip()


def cat_rank(rank2):
    m = re.findall(r"#([\d,]+) in ([A-Za-z&,'\- ]+)", rank2 or "")
    if not m:
        return "—"
    return "#%s in %s" % (m[-1][0], m[-1][1].strip().rstrip("(See").strip())


def dash(v):
    v = s(v)
    return v if v else "—"


result_rows = []
for r in rows:
    result_rows.append([
        s(r["id"]), s(r["asin"]),
        s(r["brand"]).replace("Visit the ", "").replace(" Store", ""),
        (s(r["title"]) or "")[:48] + ("…" if len(s(r["title"])) > 48 else ""), dash(r["price"]), dash(r["listprice"]),
        s(r["score"]), s(r["reviews"]), dash(r["soldby"]), cat_rank(s(r["rank2"])),
    ])

prices = [float(re.sub(r"[^0-9.]", "", s(r["price"]))) for r in rows if re.search(r"\d", s(r["price"]))]
reviews = [int(re.sub(r"[^0-9]", "", s(r["reviews"])) or 0) for r in rows]
brands = sorted({s(r["brand"]).replace("Visit the ", "").replace(" Store", "") for r in rows})
withdeal = sum(1 for r in rows if s(r["withdeal"]))
ac_yes = sum(1 for r in rows if s(r["isac"]) == "1")
bs_yes = [s(r["id"]) for r in rows if s(r["isbs"]) == "1"]

FIELD_NOTES = [
    ("id", "序号 1–20", "脚本生成"),
    ("url", "`https://www.amazon.com/dp/<ASIN>`", "规范化主链接"),
    ("href", "榜单卡片 `<a href>` 转绝对地址", "带 `ref=zg_bs_g_289935_d_sccl_N`"),
    ("label", "任务标签常量 `microwave-bestsellers-2026-10-26`", ""),
    ("keywords", "常量 `microwave ovens`", "榜单品类，不是搜索词"),
    ("category", "榜单节点 ID `289935`", "Microwave Ovens"),
    ("overviewbullets", "`#productOverview_feature_div` 的 tr/li 键值对", "品牌/尺寸/颜色/容量…"),
    ("detailbullets", "`#productDetails_feature_div` 全部 tr/li 键值 → JSON", "60+ 项规格，截断 6000 字符"),
    ("task_time", "导出时刻", ""),
    ("img", "`#landingImage` 的 outerHTML", "截断 1200 字符"),
    ("imgsrc", "`#landingImage[data-old-hires]` → `src`", "最高分辨率主图"),
    ("dynamicimgsrcs", "`#landingImage[data-a-dynamic-image]` 的 key 集合", "7 档尺寸 URL"),
    ("rank", "含 `Best Sellers Rank` 的 tr 内层 HTML", "无该模块时回退榜单位次"),
    ("rank2", "同 tr 的纯文本", "`#277 in Kitchen & Dining … #3 in Countertop Microwave Ovens`"),
    ("title", "`#productTitle`", ""),
    ("featurebullets", "`#feature-bullets`", "About this item 五条卖点"),
    ("desc", "`#productDescription` → **`#productDescription_feature_div`** → `#aplus` 文本", "v3 才补上的容器；纯图片 A+ 仍为空"),
    ("asin", "`input#ASIN` → URL 正则", "双保险"),
    ("pasin", "页面内联脚本 `parentAsin`", "变体父 ASIN"),
    ("totalvariations", "内联脚本 `num_total_variations`", ""),
    ("jsVariables", "含 twister 数据的内联 `<script>`", "截断 1500 字符"),
    ("brand", "`#bylineInfo` → 概览 Brand → 详情 Brand Name", "三级回退"),
    ("soldby", "`#merchantInfoFeature_feature_div` 的 `Shipper / Seller X` → tabular-buybox → buybox 内 `Sold by:` → 全文", "第一轮踩坑字段"),
    ("shipsfrom", "同上", ""),
    ("quantity", "`#quantity option` 文本列表", "18/20 有下拉"),
    ("globaldeliverto", "`#glow-ingress-line2`", "校验地区是否生效"),
    ("deliverto", "`#mir-layout-DELIVERY_BLOCK`", "预计送达文案"),
    ("price", "`#corePrice_feature_div` 首个 `$` → `#apex_desktop`", ""),
    ("buyboxprice", "`#buybox` 内首个 `$` → price", "交叉校验：20/20 与 price 一致"),
    ("instock", "`#availability` 文本", "截断 60 字符"),
    ("isaddcart", "`#add-to-cart-button` 是否存在", "0/1"),
    ("isbuy", "`#buy-now-button` 是否存在", "0/1"),
    ("isac", "`#acBadge_feature_div` **渲染文本**是否为 `Amazon's Choice`（或其中 aria-label）", "容器恒存在 → 只能看内容"),
    ("isbs", "`#zeitgeistBadge_feature_div` **渲染文本**是否含 `Best Seller`", "同上；本次 1/20"),
    ("iscoupon", "`#promoPriceBlockMessage` 或 `Apply $X coupon`", "0/1"),
    ("isprime", "buybox 内 `i.header-prime-logo` / `i.a-icon-prime` / `#prime-badge` 元素", "未登录 Prime → 0/20"),
    ("withdeal", "`#dealBadge_feature_div`", "`Limited time deal`"),
    ("yousave", "List Price − Price 计算 + 折扣百分比", "计算字段：`$30.04 (30%)`"),
    ("detailimgs", "A+ 区 img（`data-src` 优先，过滤 grey-pixel）", "懒加载坑"),
    ("detailvideos", "A+ video / vse 播放器链接", ""),
    ("isa", "`#aplus` 是否存在", "0/1，表示有图文详情"),
    ("boughttogethermetadata", "FBT 容器内 `[data-a-state]`", ""),
    ("boughttogetherimgs", "FBT 容器内 img", ""),
    ("boughttogether", "FBT 容器文本（要求含 `bought together`）", "15/20 页面有 FBT"),
    ("othersellernum", "正文 `New & Used (N)` / `(N) from`", ""),
    ("qanum", "正文 `N answered questions`", "0/20：新版无 Q&A 区块"),
    ("stock_status", "`#availability` 首行", ""),
    ("score", "`#acrPopover[title]` 正则 → 文本", "`4.3 out of 5` → `4.3`"),
    ("reviews", "`#acrCustomerReviewText` 数字", ""),
    ("starnum", "同 reviews", "源表口径一致"),
    ("score5percent", "`a[aria-label=\"69 percent of reviews have 5 stars\"]`", "新版用 aria-label"),
    ("score4percent", "同上", ""),
    ("score3percent", "同上", ""),
    ("score2percent", "同上", ""),
    ("score1percent", "同上", ""),
    ("scoresbyfeature", "页面无该模块", "0/20"),
    ("weight", "详情 `Item Weight`", "18/20"),
    ("volume", "详情 `Item Dimensions D x W x H` / `Product Dimensions`", ""),
    ("reviewsmention", "不采集", "源表标注“暂时取不到”"),
    ("onsaletime", "详情 `Date First Available`", "0/20：新版不再输出"),
    ("feedbackurl", "卖家链接 / `sp?seller=<ID>`", ""),
    ("sellerID", "URL 里 `seller=` → Amazon 自营 `ATVPDKIKX0DER`", "19/20"),
    ("marketplaceID", "常量 `ATVPDKIKX0DER`", "amazon.com 美国站"),
    ("reviewsurl", "`/product-reviews/<ASIN>`", ""),
    ("sellsameurl", "`/gp/offer-listing/<ASIN>`", ""),
    ("categorypath", "面包屑 `a[href]` 绝对化", ""),
    ("categoryname", "面包屑最后一级", ""),
    ("categorynamelevel", "面包屑文本用竖线连接", ""),
    ("listprice", "`#apex_desktop` 的 `List Price:` → `#listPrice`", "14/20 有划线价"),
    ("gallery", "`#altImages li img`，规格换成 `_AC_SL1200_`", ""),
    ("referer", "常量：榜单页 URL", "模拟来源页"),
    ("numchars", "`document.body.innerText.length`", ""),
    ("numlinks", "`a[href]` 计数", ""),
    ("numimgs", "`img` 计数", ""),
    ("height", "`documentElement.scrollHeight`", "整页高度"),
]
assert len(FIELD_NOTES) == len(hdr), (len(FIELD_NOTES), len(hdr))

with open(os.path.join(OUT, "bs_list.json"), encoding="utf-8") as f:
    bs = json.load(f)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


js = read(os.path.join(OUT, "scripts", "extract_product_v3.js"))
ps1 = read(os.path.join(OUT, "scripts", "run_pass.ps1"))
py = read(os.path.join(OUT, "scripts", "export_to_xlsx.py"))
diag = read(os.path.join(OUT, "scripts", "diag.js"))

for name in ("extract_product_v3.js", "run_pass.ps1", "export_to_xlsx.py", "diag.js", "bs_list.js"):
    shutil.copyfile(os.path.join(OUT, "scripts", name), os.path.join(DOCDIR, name))

parts = []
A = parts.append

A("""# Amazon 微波炉「今日畅销 Top 20」采集实录

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

""")

A("| # | ASIN | 品牌 | 标题（截断） | 价格 | 划线价 | 评分 | 评论数 | 卖家 | 详情页类目排名 |\n")
A("|---|------|------|--------------|------|--------|------|--------|------|----------------|\n")
for r in result_rows:
    A("| %s | `%s` | %s | %s | %s | %s | %s | %s | %s | %s |\n" % tuple(r))
A("\n")

A("""> **"详情页类目排名"读法**：取自该商品详情页的 `Best Sellers Rank` 模块，是**最末级子类目**的名次（多数是
> *Countertop Microwave Ovens*；个别商品页面只给父类目名次，例如 `#12 in Microwave Ovens`）。它和左边"今日畅销榜位次"不是同一个榜——畅销榜按 *Microwave Ovens* 节点排，
> 详情页排名按子类目排，所以第 6 位可能对应子类目第 8 名。两个口径都保留在表里（`rank2` 含完整原文）。

**汇总统计：**

| 指标 | 值 |
| --- | --- |
| 商品数 | 20 |
| 价格区间 | $%.2f – $%.2f（均值 $%.2f，19 个有价） |
| 评论数合计 | %s 条 |
| 评分区间 | %s – %s |
| 品牌数 | %d（%s） |
| `Amazon's Choice` | %d/20 |
| `Best Seller` 徽章 | %d/20（`B00F2QFX5O`：*#1 Best Seller in Over-the-Range Microwave Ovens*） |
| 限时 Deal | %d/20 |
| 卖家 | 18 条 `Amazon.com`、1 条 `Amazon Resale`、1 条页面无卖家（不可售） |
| 缺价 | 1 条（`B00F2QFX5O` 当日 `Currently unavailable`） |

""" % (
    min(prices), max(prices), sum(prices) / len(prices),
    format(sum(reviews), ","),
    min(s(r["score"]) for r in rows), max(s(r["score"]) for r in rows),
    len(brands), "、".join(brands),
    ac_yes, len(bs_yes), withdeal,
))

A("""---

## 3. 字段口径

字段表 `asin_field_description.xlsx` 的 `Sheet2` 只有两行：第 1 行是 **75 个英文字段名**（也就是最终列名与列序），第 2 行是中文说明。
导出时直接用第 1 行做表头，第 2 行单独生成 `fields` 页做对照——既不破坏机器可读性，又保留了业务口径。

读取方式（Python `openpyxl`。注意：Windows 控制台是 GBK，直接 `print` 中文会 `UnicodeEncodeError`，必须写 UTF-8 文件或设 `PYTHONIOENCODING=utf-8`）：

```powershell
@'
import openpyxl, io
p = r"browser4-tests\\pulsar-tests-common\\src\\test\\resources\\asin_field_description.xlsx"
ws = openpyxl.load_workbook(p, data_only=True)["Sheet2"]
rows = list(ws.iter_rows(min_row=1, max_row=3, values_only=True))
with io.open(r"%TEMP%\\fields.txt", "w", encoding="utf-8") as f:
    f.write("\\n".join("%d. %s — %s" % (i, h, d) for i, (h, d) in enumerate(zip(rows[0], rows[1]), 1)))
'@ | Set-Content -Encoding utf8 "$env:TEMP\\read_xlsx.py"; python "$env:TEMP\\read_xlsx.py"
```

### 3.1 字段 → 取值来源映射

75 个字段里，约 40 个是"一个选择器直接取"，其余都需要**多级回退、正则解析或计算**。下表是提取器（v3）里的真实口径：

| 字段 | 取值来源 / 选择器 | 备注 |
| --- | --- | --- |
""")
for name, src, note in FIELD_NOTES:
    A("| `%s` | %s | %s |\n" % (name, src, note))
A("\n")

A("""---

## 4. 环境与准备

```text
仓库：D:\\workspace\\Browser4\\Browser4-4.15-feat   （ROOT.md + pom.xml 存在 → dev 模式）
CLI ：4.15.2（cargo 从源码编译）
后端：4.15.2-SNAPSHOT，dev 模式自动选端口 8282
JDK ：graalvm-jdk-25.0.3+9.1（Maven enforcer 要求 JDK 25+）
浏览器：Chrome，headless（AI agent 默认模式）
```

dev 模式下每个 checkout 独占后端端口与状态目录，不会和别的 worktree 抢会话：

```text
Server URL: http://127.0.0.1:8282
  resolved from: development port allocation
Workspace app data: C:\\Users\\<user>\\.browser4\\workspaces\\Browser4-4.15-feat-2ec92531\\app-data
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
./b4w.ps1 --session amz-mw eval --file "output\\amazon-microwaves-2026-10-26\\scripts\\bs_list.js"
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
./b4w.ps1 --session amz-mw eval --file "$env:TEMP\\openpop.js"

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
./b4w.ps1 --session amz-mw eval --file "output\\amazon-microwaves-2026-10-26\\scripts\\extract_product.js"
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
python "output\\amazon-microwaves-2026-10-26\\scripts\\export_to_xlsx.py"
```

```text
saved: ...\\amazon_microwaves_top20.xlsx
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
cd D:\\workspace\\Browser4\\Browser4-4.15-feat

# 1) 起后端 + 打开榜单页（首次会编译，几分钟）
./b4w.ps1 --session amz-mw open --headless "https://www.amazon.com/Best-Sellers-Kitchen-Dining-Microwave-Ovens/zgbs/kitchen/289935"

# 2) 币种 / 语言（配送地按 5.3 用 ref 交互设置一次）
./b4w.ps1 --session amz-mw eval "document.cookie='i18n-prefs=USD; domain=.amazon.com; path=/'"

# 3) 抓榜单 30 条 → bs_list.json
./b4w.ps1 --session amz-mw eval --file "output\\amazon-microwaves-2026-10-26\\scripts\\bs_list.js"

# 4) 20 个详情页跑批（约 8 分钟）
pwsh -NoProfile -File "output\\amazon-microwaves-2026-10-26\\scripts\\run_pass.ps1" -ExtractFile "output\\amazon-microwaves-2026-10-26\\scripts\\extract_product_v3.js" -RawDirName "raw3"

# 5) 导出 xlsx + csv
python "output\\amazon-microwaves-2026-10-26\\scripts\\export_to_xlsx.py"
python "output\\amazon-microwaves-2026-10-26\\scripts\\finalize.py"
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
""")

A(js)
A("""```

## 附录 B：跑批器 `run_pass.ps1`

> `-ExtractFile` 指定提取器、`-RawDirName` 指定输出目录（`raw` / `raw2` / `raw3`）、`-SkipExisting` 断点续跑。

```powershell
""")
A(ps1)
A("""```

## 附录 C：导出器 `export_to_xlsx.py`

> 源表读列名与中文说明 → 按 `raw3` → `raw2` → `raw` 合并 → 补列表级字段 → 写 xlsx（含 `fields` 对照页）。

```python
""")
A(py)
A("""```

## 附录 D：探活脚本 `diag.js`（定位新版布局的关键工具）

```js
""")
A(diag)
A("""```

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

""")

A("| 位次 | ASIN | 卡片价格 | 卡片评论数 | 卡片评分 |\n|---|---|---|---|---|\n")
for it in bs["items"][:20]:
    A("| %s | `%s` | %s | %s | %s |\n" % (it["rank"], it["asin"], it["listPrice"] or "—", it["listReviews"], it["listRating"]))
A("\n")
A("""> 卡片价格与详情页价格可能有个别差异（榜单缓存 / 变体推荐），最终以详情页 `price` 为准。
> 第 21–30 位未采集，仍保留在 `bs_list.json` 中。
""")

dest = os.path.join(DOCDIR, "README.md")
with open(dest, "w", encoding="utf-8") as f:
    f.write("".join(parts))
print("wrote:", dest)
print("bytes:", os.path.getsize(dest))
