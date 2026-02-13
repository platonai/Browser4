REST Service
=

[Prev](14AI-extraction.md) [Home](1home.md) [Next](16console.md)

When Browser4 runs as a REST service, X-SQL can be used to scrape webpages or to query web data directly at any time, from anywhere, without opening an IDE.

## Build from Source

```
git clone https://github.com/platonai/Browser4.git
cd Browser4 && bin/build-run.sh
```
For Chinese developers, we strongly suggest you to follow [this](/bin/tools/maven/maven-settings.md) instruction to accelerate the building process.

## Use X-SQL to Query the Web

Start the pulsar server if it is not started:

```shell
bin/pulsar
```
Scrape a webpage in another terminal window:

```shell
bin/scrape.sh
```
The bash script is quite simple; it just uses curl to post an X-SQL:

```shell
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
  from load_and_select('https://www.amazon.com/dp/B08PP5MSVB  -i 1d -njr 3', 'body');
"
```
Example code: [bash](/bin/legacy/scrape.sh), [batch](/bin/legacy/scrape.bat), [java](/pulsar-client/src/main/java/ai/platon/pulsar/client/Scraper.java), [kotlin](/pulsar-client/src/main/kotlin/ai/platon/pulsar/client/Scraper.kt), [php](/pulsar-client/src/main/php/Scraper.php).

The response is as follows in JSON format:

```json
{
    "uuid": "cc611841-1f2b-4b6b-bcdd-ce822d97a2ad",
    "statusCode": 200,
    "pageStatusCode": 200,
    "pageContentBytes": 1607636,
    "resultSet": [
        {
            "title": "Tara Toys Ariel Necklace Activity Set - Amazon Exclusive (51394)",
            "listprice": "$19.99",
            "price": "$12.99",
            "categories": "Toys & Games|Arts & Crafts|Craft Kits|Jewelry",
            "baseuri": "https://www.amazon.com/dp/B08PP5MSVB"
        }
    ],
    "pageStatus": "OK",
    "status": "OK"
}
```

------

[Prev](14AI-extraction.md) | [Home](1home.md) | [Next](16console.md)
