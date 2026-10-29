# Amazon 微波炉今日畅销 Top 20 — 采集结果

## 交付物

| 文件 | 说明 |
| --- | --- |
| `amazon_microwaves_top20.xlsx` | 主交付表格：Sheet `microwaves_top20` = 20 行数据，75 列字段（列名与顺序取自 `asin_field_description.xlsx` 的 Sheet2 表头）；Sheet `fields` = 字段名/中文说明对照 |
| `amazon_microwaves_top20.csv` | 同一份数据的 CSV（UTF-8 with BOM，Excel 直接双击可读） |
| `raw3/` | 定稿：每个 ASIN 的原始采集 JSON（v3 提取器） |`n| `raw2/` `raw/` | 前两轮原始 JSON，留作对照（v2 修布局选择器、v3 修徽章/描述） |
| `bs_list.json` | 畅销榜列表页抓取的原始 30 个条目（取前 20） |

## 数据来源

- 榜单页（今日畅销榜，Amazon 每小时更新）：https://www.amazon.com/Best-Sellers-Kitchen-Dining-Microwave-Ovens/zgbs/kitchen/289935
- 采集时间：2026-10-26 23:55 – 2026-10-27 00:40（北京时间，三轮全量明细页采集）
- 配送地 / 币种：New York 10001 / USD（已在会话内设置，价格均为美元）
- 采集方式：Browser4 CLI（CDP 驱动真实 Chrome，headless）逐页 `goto` + DOM 提取

## 字段覆盖情况

75 个字段全部有列。以下字段在本次 20 个详情页上确实取不到（不是脚本失败）：

- `qanum` 问答数：新版详情页已不再渲染 Q&A 区块
- `scoresbyfeature` 分项评分：页面无该模块
- `reviewsmention` 评论关键词：源表本身标注“暂时取不到”
- `onsaletime` 上架时间：新版布局不再输出 “Date First Available”
- 部分 `listprice`（14/20）只在有划线价时存在；`withdeal`（10/20）只在有 Deal 时存在
- `price` 缺 1 条：B00F2QFX5O（GE 30" 嵌入/吊柜款）当日为 “Currently unavailable”

## 说明

- `rank` / `rank2`：优先取详情页 “Best Sellers Rank” 模块（`rank` 保留原始 HTML 片段，`rank2` 为纯文本）；页面无该模块时用今日畅销榜位次（如 `#5 in Microwave Ovens (Best Sellers list)`）。
- `brand`：优先 `#bylineInfo`（“Visit the X Store”），缺失时回退 “产品概览” 里的 Brand 字段。
- `soldby` / `shipsfrom`：取 buybox 的 “Shipper / Seller” 信息。
- 长文本字段（`detailbullets`、`jsVariables`、`boughttogether` 等）为控制单元格大小做了截断。`n- `isac` / `isbs` / `isprime` 均为 0/1 语义：徽章容器在 EVERY 详情页都存在（可能为空），所以判定依据是**渲染出来的文本 / 徽标元素**，不是容器存在与否。`n`n> 完整过程记录见 `docs-dev/examples/amazon-microwaves-top20/README.md`。
