X-SQL
=
[Prev](12massive-crawling.md) [Home](1home.md) [Next](14AI-extraction.md)

Browser4 has developed X-SQL to directly query the internet and convert web pages into tables and charts. X-SQL extends SQL to manage web data, including web crawling, data collection, data extraction, web content mining, web BI, and more.

When Browser4 runs as a REST service, X-SQL can be used to collect web pages or directly query the internet anytime, anywhere, without opening an IDE, just like an upgraded version of Google and Baidu.

Now, in large-scale data collection projects, all [extraction rules](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl/) ([Chinese mirror](https://gitee.com/platonai_galaxyeye/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl/)) are written in X-SQL, with data type conversion and data cleaning also handled by the powerful X-SQL inline processing. The experience of writing X-SQL for data collection projects is as simple and efficient as traditional CRUD projects. A good example is [x-asin.sql](https://github.com/platonai/exotic-amazon/blob/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl/x-asin.sql) ([Chinese mirror](https://gitee.com/platonai_galaxyeye/exotic-amazon/blob/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl/x-asin.sql)), which extracts more than 70 fields from each product page.

[Exotic Amazon](https://github.com/platonai/exotic-amazon) ([Chinese mirror](https://gitee.com/platonai_galaxyeye/exotic-amazon)) is a complete solution for collecting the entire amazon.com website, ready to use out of the box, covering most data types on Amazon, and it will be permanently provided for free and open source. Thanks to the comprehensive web data management infrastructure provided by Browser4, the entire solution consists of no more than 3500 lines of Kotlin code and less than 700 lines of X-SQL to extract over 650 fields.

This course introduces the basic concepts, basic usage, and the most common SQL functions of X-SQL.

A concise example is:

```kotlin
fun main() {
    val context = SQLContexts.create()
    val sql = """
select
      dom_first_text(dom, '#productTitle') as title,
      dom_first_text(dom, '#bylineInfo') as brand,
      dom_first_text(dom, '#price tr td:matches(^Price) ~ td, #corePrice_desktop tr td:matches(^Price) ~ td') as price,
      dom_first_text(dom, '#acrCustomerReviewText') as ratings,
      str_first_float(dom_first_text(dom, '#reviewsMedley .AverageCustomerReviews span:contains(out of)'), 0.0) as score
  from load_and_select('https://www.amazon.com/dp/B08PP5MSVB  -i 1s -njr 3', 'body');
            """
    val rs = context.executeQuery(sql)
    println(ResultSetFormatter(rs, withHeader = true))
}
```

Complete code: [kotlin](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_10_XSQL.kt), [Chinese mirror](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_10_XSQL.kt).

## Language Introduction

X-SQL is based on the H2 database, and Browser4 mainly extends the H2 database by registering UDFs, but it also does other extensions.

Each X-SQL function has a namespace, for example:

```sql
dom_base_uri() -> dom
str_substring_after() -> str
```

In the above example, dom and str are namespaces. If an X-SQL function is declared as hasShortcut, the namespace can be ignored.

X-SQL functions are not case-sensitive, and all underscores ( _ ) are ignored.

The following X-SQL functions are the same:

```sql
DOM_LOAD_AND_SELECT(url, 'body');
dom_loadAndSelect(url, 'body');
Dom_Load_And_Select(url, 'body');
dom_load_and_select(url, 'body');
dOm_____lo_AdaNd_S___elEct_____(url, 'body');
```

Since LOAD_AND_SELECT is declared as hasShortcut, the namespace can be ignored, and the following functions are still the same:

```sql
LOAD_AND_SELECT(url, 'body');
loadAndSelect(url, 'body');
Load_And_Select(url, 'body');
load_and_select(url, 'body');
_____lo_AdaNd_S___elEct_____(url, 'body');
```

## Table Functions

Each table function returns a ResultSet and can appear in the from clause.

### LOAD_AND_SELECT

```sql
LOAD_AND_SELECT(url [cssSelector [, offset [, limit]]])
```

Load a page and select elements, returning a ResultSet. The resulting ResultSet has two columns: DOM and DOC, both of which are of type ValueDom.

**Example:**

```sql
select
    dom_base_uri(dom)
from
    load_and_select('https://www.amazon.com/dp/B08PP5MSVB',  'body', 1, 10)
```

### DOM Functions

DOM functions are designed to query DOM attributes. Each DOM function accepts a ValueDom argument, which is a wrapper for a Jsoup Element. DOM functions are defined in the following file: [DomFunctions](/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/DomFunctions.kt), and all DOM functions are in the namespace DOM.

### DOM_BASE_URI

```sql
DOM_BASE_URI(dom)
```

Returns the URI of the HTML document.

**Example:**

```sql
select dom_base_uri(dom) from load_and_select('https://www.amazon.com/dp/B08PP5MSVB',  'body')
```

## DOM Selection Functions

DOM selection functions are designed to query elements and their attributes from the DOM. Each DOM function accepts a parameter named DOM (case-insensitive), of type ValueDom, which is a wrapper for a Jsoup Element. DOM selection functions usually also accept a cssSelector parameter to select a child element of the DOM. The most important DOM selection functions are defined in the following file: [DomSelectFunctions](/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/DomSelectFunctions.kt). All DOM selection functions are in the namespace DOM.

### DOM_FIRST_TEXT

```sql
DOM_FIRST_TEXT(dom, cssSelector)
```

Returns the text content of the first element selected by cssSelector within dom, similar to the following JavaScript code.

```javascript
dom.querySelector(cssSelector).textContent
```

**Example:**

```sql
select
    dom_first_text(dom, '#productName') as Name,
    dom_first_text(dom, '#price') as Price,
    dom_first_text(dom, '#star') as StarNum
from
    load_and_select('https://www.example.com/zgbs/appliances',  'ul.item-collection li.item')
```

### DOM_ALL_TEXTS

```sql
DOM_ALL_TEXTS(dom, cssSelector)
```

Returns an array of text content from all elements selected by cssSelector within dom, similar to the following JavaScript pseudocode.

```javascript
dom.querySelectorAll(cssSelector).map(e => e.textContent)
```

**Example:**

```sql
select
    dom_all_texts(dom, 'ul li.item a.name') as ProductNames,
    dom_all_texts(dom, 'ul li.item span.price') as ProductPrices,
    dom_all_texts(dom, 'ul li.item span.star') as ProductStars
from
    load_and_select('https://www.example.com/zgbs/appliances',  'div.products')
```

## String Functions

Most string functions are automatically converted from org.apache.commons.lang3.StringUtils through programming. You can find the UDF definitions in the following file: [StringFunctions](/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/StringFunctions.kt) ([Chinese mirror](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/StringFunctions.kt)), and all string functions are in the namespace STR.

### STR_SUBSTRING_AFTER

```sql
STR_SUBSTRING_AFTER(str, separator)
```

Get the substring after the first occurrence of the separator.

Example:

```sql
select
    str_substring_after(dom_first_text(dom, '#price'), '$') as Price
from
    load_and_select('https://www.amazon.com/dp/B08PP5MSVB',  'body');
```

------

[Prev](12massive-crawling.md) | [Home](1home.md) | [Next](14AI-extraction.md)
