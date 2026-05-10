# X-SQL

English | [简体中文](/docs/zh/x-sql.md)

## Introduction

We have developed X-SQL, a tool designed to directly query the web and convert web pages into tables and charts for easier data analysis and visualization.

X-SQL is built on the H2 database engine, ensuring compatibility with H2 and utilizing its SQL dialect.

Here is an example of a typical X-SQL query:

```sql
select
  dom_base_uri(dom) as url,
  dom_first_text(dom, '#productTitle') as title,
  str_substring_after(dom_first_href(dom, '#wayfinding-breadcrumbs_container ul li:last-child a'), '&node=') as category,
  dom_first_slim_html(dom, '#bylineInfo') as brand,
  dom_all_slim_htmls(dom, '#imageBlock img') as gallery,
  dom_first_slim_html(dom, '#landingImage, #imgTagWrapperId img, #imageBlock img:expr(width > 400)') as img,
  dom_first_text(dom, '#price tr td:contains(List Price) ~ td') as listprice,
  dom_first_text(dom, '#price tr td:matches(^Price) ~ td') as price,
  str_first_float(dom_first_text(dom, '#reviewsMedley .AverageCustomerReviews span:contains(out of)'), 0.0) as score
from load_and_select('https://www.amazon.com/dp/B08PP5MSVB   -i 1d -njr 3', 'body');
```

Browser4 primarily extends the functionality of the H2 database by registering User-Defined Functions (UDFs) and making other enhancements.

Each X-SQL function belongs to a specific namespace, as illustrated below:

```
dom_base_uri() -> dom
str_substring_after() -> str
```

In the example provided, `dom` and `str` represent namespaces. If an X-SQL function is declared with `hasShortcut`, the namespace can be disregarded.

It's important to note that X-SQL functions are case-insensitive, and all underscores (`_`) are disregarded.

The following X-SQL functions are considered identical:

```sql
DOM_LOAD_AND_SELECT(url, 'body');
dom_loadAndSelect(url, 'body');
Dom_Load_And_Select(url, 'body');
dom_load_and_select(url, 'body');
dOm_____lo_AdaNd_S___elEct_____(url, 'body');
```

Since `LOAD_AND_SELECT` is declared with `hasShortcut`, the namespace is optional, and the following functions are also considered identical:

```sql
LOAD_AND_SELECT(url, 'body');
loadAndSelect(url, 'body');
Load_And_Select(url, 'body');
load_and_select(url, 'body');
_____lo_AdaNd_S___elEct_____(url, 'body');
```

## Table Functions

Each table function returns a ResultSet and can be used in the `from` clause of a query.

### LOAD_AND_SELECT

```sql
LOAD_AND_SELECT(url [, cssSelector [, offset [, limit]]])
```

This function loads a web page and selects elements, returning a ResultSet. The resulting ResultSet contains two columns: `DOM` and `DOC`, both of which are of type `ValueDom`.

Here is an example of how to use this function:

```sql
select
  dom_base_uri(dom)
from
  load_and_select('https://www.amazon.com/dp/B08PP5MSVB',  'body', 1, 10);
```

## DOM Functions

DOM functions are designed to query attributes of the DOM (Document Object Model). Each DOM function accepts a `ValueDom` argument, which is a wrapper around a Jsoup Element.

DOM functions are defined in the following file: [DomFunctions](https://github.com/apache/pulsar/blob/master/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/DomFunctions.kt).

All DOM functions belong to the namespace `DOM`.

### DOM_BASE_URI

```sql
DOM_BASE_URI(dom)
```

This function returns the URI of the HTML document.

Here is an example of how to use this function:

```sql
select dom_base_uri(dom) from load_and_select('https://www.amazon.com/dp/B08PP5MSVB',  'body');
```

## DOM Selection Functions

DOM selection functions are designed to query elements and their attributes from the DOM.

Each DOM function accepts a parameter named `DOM` (case-insensitive), of type `ValueDom`, which is a wrapper for a Jsoup `Element`.

DOM selection functions typically also accept a `cssSelector` parameter to select a child element of the `DOM`.

The most important DOM selection functions are defined in the following file: [DomSelectFunctions](https://github.com/apache/pulsar/blob/master/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/DomSelectFunctions.kt).

All DOM selection functions belong to the namespace `DOM`.

### DOM_FIRST_TEXT

```sql
DOM_FIRST_TEXT(dom, cssSelector)
```

This function returns the text content of the first element selected by `cssSelector` within `dom`, similar to the following JavaScript code:

```javascript
dom.querySelector(cssSelector).textContent
```

Here is an example of how to use this function:

```sql
select
  dom_first_text(dom, '#productName') as Name,
  dom_first_text(dom, '#price') as Price,
  dom_first_text(dom, '#star') as StarNum
from
  load_and_select('https://www.example.com/zgbs/appliances',  'ul.item-collection li.item');
```

### DOM_ALL_TEXTS

```sql
DOM_ALL_TEXTS(dom, cssSelector)
```

This function returns an array of text content from all elements selected by `cssSelector` within `dom`, similar to the following JavaScript pseudocode:

```javascript
dom.querySelectorAll(cssSelector).map(e => e.textContent)
```

Here is an example of how to use this function:

```sql
select
  dom_all_texts(dom, 'ul li.item a.name') as ProductNames,
  dom_all_texts(dom, 'ul li.item span.price') as ProductPrices,
  dom_all_texts(dom, 'ul li.item span.star') as ProductStars
from
  load_and_select('https://www.example.com/zgbs/appliances',  'div.products');
```

## String Functions

Most string functions are automatically converted from `org.apache.commons.lang3.StringUtils` through programming. 
You can find the UDF definitions in the following file: 
[StringFunctions](https://github.com/apache/pulsar/blob/master/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/StringFunctions.kt).

All string functions belong to the namespace `STR`.

### STR_SUBSTRING_AFTER

```sql
STR_SUBSTRING_AFTER(str, separator)
```

This function retrieves the substring that follows the first occurrence of the separator.

Here is an example of how to use this function:

```sql
select
  str_substring_after(dom_first_text(dom, '#price'), '$') as Price
from
  load_and_select('https://www.amazon.com/dp/B08PP5MSVB',  'body');
```
