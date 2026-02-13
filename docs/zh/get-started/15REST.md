REST 服务
=

当 Browser4 作为 REST 服务运行时，X-SQL 可用于随时随地采集网页或直接查询 Web 数据，无需打开 IDE。它就像是升级版的 Google 搜索框：将关键词查询升级为 SQL 查询。

示例：

```bash
curl -X POST --location "http://localhost:8182/api/x/e" -H "Content-Type: text/plain" -d "
  select
      dom_base_uri(dom) as url,
      dom_first_text(dom, '#productTitle') as title,
      str_substring_after(dom_first_href(dom, '#wayfinding-breadcrumbs_container ul li:last-child a'), '&node=') as category,
      dom_first_slim_html(dom, '#bylineInfo') as brand,
      cast(dom_all_slim_htmls(dom, '#imageBlock img') as varchar) as gallery,
      dom_first_slim_html(dom, '#landingImage, #imgTagWrapperId img, #imageBlock img:expr(width > 400)') as img,
      dom_first_text(dom, '#price tr td:contains(List Price) ~ td') as listprice,
      dom_first_text(dom, '#price tr td:matches(^Price) ~ td') as price,
      str_first_float(dom_first_text(dom, '#reviewsMedley .AverageCustomerReviews span:contains(out of)'), 0.0) as score
  from load_and_select('https://www.amazon.com/dp/B08PP5MSVB -i 1d -njr 3', 'body');"
```

示例代码可以在这里找到:[bash](/bin/legacy/scrape.sh)，[batch](/bin/legacy/scrape.bat)，[java](/pulsar-client/src/main/java/ai/platon/pulsar/client/Scraper.java)，[kotlin](/pulsar-client/src/main/kotlin/ai/platon/pulsar/client/Scraper.kt)，[php](/pulsar-client/src/main/php/Scraper.php)。

------

[上一章](14AI-extraction.md) [目录](1home.md) [下一章](16console.md)
