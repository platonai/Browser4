# Readability 站点适配矩阵（P3）

> 日期：2026-08-28
> 环境：Browser4 4.14.0-rc.1（本地重建 bundle），Windows，headless Chrome，经 `htmlsnapshot readability`（真实浏览器 capture → jsoup 解析 → `ReadabilityExtractor`）全链路执行。
> 复跑方式：`browser4-cli -s <name> open "<url>"` 后 `browser4-cli -s <name> htmlsnapshot readability`；或单步 `htmlsnapshot readability "<url>"`（独立抓取）。

## 1. 结果总览

| # | 站点 / 页面 | 类型 | 结果 | 字符数 | 置信度 | 判定 |
|---|---|---|---|---|---|---|
| 01 | en.wikipedia.org/wiki/Memory_safety | 维基百科条目 | ✅ | 15,847 | 94% | 正文完整（含 References 章节，属合理正文） |
| 02 | blog.rust-lang.org/2025/01/09/Rust-1.84.0/ | 官方发布博客 | ✅ | 6,627 | 96% | 正文完整，代码块保留 |
| 03 | overreacted.io/a-complete-guide-to-useeffect/ | 长文技术博客 | ✅ | 62,889 | 100% | 近乎完美 |
| 04 | developer.mozilla.org/zh-CN/docs/Web/JavaScript | 文档站落地页 | ⚠️ 部分 | 3,434 | 59% | 提取到最密集文本区（"Beginner's tutorials"），但落地页本质是链接卡片，非文章页 |
| 05 | bbc.com（门户首页） | 新闻门户 | ❌ 拒绝 | — | — | 无文章结构（+ 可能的同意墙），正确拒绝 |
| 06 | news.ycombinator.com | 聚合器 | ⚠️ 弱 | 3,544 | 96% | 提取到的是链接文本；置信度对聚合页有误导（覆盖率≠质量） |
| 07 | ruanyifeng.com/blog/（博客索引） | 列表页 | ❌ 拒绝 | — | — | 短段落/链接列表，低于阈值，正确拒绝 |
| 08 | martinfowler.com/articles/continuousIntegration.html | 长文 | ✅ | 80,984 | 100% | 完美，byline=Martin Fowler 正确 |
| 09 | blog.cloudflare.com/welcome-to-connectivity-cloud/ | 企业博客 | ✅ | — | 92% | 正文完整 |
| 10 | example.com | 极短页 | ❌ 拒绝 | — | — | 低于阈值，正确拒绝 |

**统计**：文章类页面 5/6 强提取（83%）；非文章页 3/4 正确拒绝、1 个弱提取（HN 聚合页）。

## 2. 过程中的发现

1. **404 页正确拒绝**：初测 blog.rust-lang.org 用了错误 URL（`1.84.0.html`，实际为 `2025/01/09/Rust-1.84.0/`），提取器对 404 页正确报"无可读内容"——失败是 URL 问题而非算法问题（用正确 URL 后 96% 成功）。
2. **置信度语义**：`confidence` = 文本覆盖率。对聚合页（HN）会高达 96% 但内容只是链接文本——属已知局限，见改进项 A。
3. **门户/列表页**（BBC、ruanyifeng 索引、example.com）均按设计拒绝或低质量处理，未出现"提取出导航"的严重误报。

## 3. 改进项（backlog）

- **A. 链接密度后置过滤**：对提取结果计算链接文本占比（`a` 文本 / 总文本），超过阈值（如 60%）判定为聚合/导航页并拒绝或降级提示——解决 HN 类页面的"高置信度但无正文"。
- **B. 文档站适配**：MDN 类落地页可识别 `role=main` + 首段提示；或文档页场景引导用户使用 `htmlsnapshot get`/X-SQL（文档站结构稳定，选择器更可靠）。
- **C. 同意墙/consent wall**（BBC）：capture 后检测 "consent"/"cookie" 关键词密度，提示可能被墙。
- **D. 站点矩阵自动化**：将本文流程固化为 fixture 化 e2e（mock 站点，见 `HtmlSnapshotMockController./htmlsnapshot-test/readability-article`）+ 定期人工抽查真实站点。

## 4. 配套 fixture

`browser4-tests/pulsar-tests-common/.../HtmlSnapshotMockController.kt` 新增高杂讯文章页 `/htmlsnapshot-test/readability-article`（导航 + 广告位 + 分享组件 + 侧栏 + 页脚包裹单篇文章），供浏览器级 e2e 断言"只提取正文、不泄漏杂讯"。
