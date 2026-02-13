X-SQL
=

Browser4 开发了 X-SQL 来直接查询互联网，并将网页转换成表格和图表。X-SQL 扩展了 SQL 来管理 Web 数据：网络爬取、数据采集、数据提取、Web 内容挖掘、Web BI，等等。

当 Browser4 作为 REST 服务运行时，X-SQL 可用于随时随地采集网页或直接查询互联网，无需打开 IDE，就象是升级版的 Google 和百度。

现在，我们在大型数据采集项目中，所有 [提取规则](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl/)（[国内镜像](https://gitee.com/platonai_galaxyeye/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl/)）都是用 X-SQL 编写的，数据类型转换、数据清理等工作也由强大的 X-SQL 内联处理。编写 X-SQL 做数据采集项目的体验，就像传统的 CRUD 项目一样简单高效。一个很好的例子是 [x-asin.sql](https://github.com/platonai/exotic-amazon/blob/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl/x-asin.sql)（[国内镜像](https://gitee.com/platonai_galaxyeye/exotic-amazon/blob/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl/x-asin.sql)），它从每个产品页面中提取 70 多个字段。

[Exotic Amazon](https://github.com/platonai/exotic-amazon)（[国内镜像](https://gitee.com/platonai_galaxyeye/exotic-amazon)）是采集整个 amazon.com 网站的**完整解决方案**，**开箱即用**，包含亚马逊大多数数据类型，它将永久免费提供并开放源代码。得益于 Browser4 提供的完善的 Web 数据管理基础设施，整个解决方案由不超过 3500 行的 kotlin 代码和不到 700 行的 X-SQL 组成，以提取 650 多个字段。

本课程介绍了 X-SQL 的基本概念、基本用法和最常见的几个 SQL 函数。

一个简明扼要的例子是：

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
  from load_and_select('https://www.amazon.com/dp/B08PP5MSVB -i 1s -njr 3', 'body');
            """
    val rs = context.executeQuery(sql)
    println(ResultSetFormatter(rs, withHeader = true))
}
```

完整代码：[kotlin](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_10_XSQL.kt)，[国内镜像](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_10_XSQL.kt)。

## 语言介绍

X-SQL 基于 H2 数据库，Browser4 主要通过注册 UDF 来扩展 H2 数据库，但也做了其他扩展。

每个 X-SQL 函数都有一个命名空间，例如：

```sql
dom_base_uri() -> dom
str_substring_after() -> str
```

在上面的例子中，dom 和 str 是命名空间。如果将 X-SQL 函数声明为 hasShortcut，则可以忽略该命名空间。

X-SQL 函数不区分大小写，所有下划线( _ )都会被忽略。

以下 X-SQL 函数都是相同的：

```sql
DOM_LOAD_AND_SELECT(url, 'body');
dom_loadAndSelect(url, 'body');
Dom_Load_And_Select(url, 'body');
dom_load_and_select(url, 'body');
dOm_____lo_AdaNd_S___elEct_____(url, 'body');
```

由于 LOAD_AND_SELECT 被声明为 hasShortcut，因此可以忽略命名空间，以下函数仍然相同：

```sql
LOAD_AND_SELECT(url, 'body');
loadAndSelect(url, 'body');
Load_And_Select(url, 'body');
load_and_select(url, 'body');
_____lo_AdaNd_S___elEct_____(url, 'body');
```

## Table Functions

每个 table function 都返回一个 ResultSet，并且可以出现在 from 子句中。

###  LOAD_AND_SELECT

```sql
LOAD_AND_SELECT(url [cssSelector [, offset [, limit]]])
```

加载一个页面并选择元素，返回一个 ResultSet。返回的结果集有两列：DOM 和 DOC，这两列的类型是 ValueDom。

**示例：**

```sql
select
    dom_base_uri(dom)
from
    load_and_select('https://www.amazon.com/dp/B08PP5MSVB', 'body', 1, 10)
```

### DOM 函数

DOM 函数被设计为查询 DOM 的属性。每个 DOM 函数都接受一个 ValueDom 参数，它是一个 Jsoup Element 的包装。DOM 函数定义在下面文件中：[DomFunctions](/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/DomFunctions.kt)，所有的 DOM 函数都在命名空间 DOM 中。

### DOM_BASE_URI

```sql
DOM_BASE_URI(dom)
```

返回 HTML 文档的 URI。

**示例：**

```sql
select dom_base_uri(dom) from load_and_select('https://www.amazon.com/dp/B08PP5MSVB', 'body')
```

## DOM 选择函数

DOM 选择函数被设计为从 DOM 中查询元素及其属性。每个 DOM 函数都接受一个名为 DOM（大小写不敏感），类型为 ValueDom 的参数，它是一个 Jsoup Element 的包装。DOM 选择函数通常也接受一个 cssSelector 参数，从而选择 DOM 的子元素。最重要的 DOM 选择函数定义在下面文件中：[DomSelectFunctions](/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/DomSelectFunctions.kt)。所有的 DOM 选择函数都在命名空间 DOM 中。

###  DOM_FIRST_TEXT

```sql
DOM_FIRST_TEXT(dom, cssSelector)
```

 返回由cssSelector在dom内选择的第一个元素的文本内容，它类似于下面的 javascript 代码。

```javascript
dom.querySelector(cssSelector).textContent
```

**示例：**

```sql
select
    dom_first_text(dom, '#productName') as Name,
    dom_first_text(dom, '#price') as Price,
    dom_first_text(dom, '#star') as StarNum
from
    load_and_select('https://www.example.com/zgbs/appliances', 'ul.item-collection li.item')
```

### DOM_ALL_TEXTS

```sql
DOM_ALL_TEXTS(dom, cssSelector)
```

返回由 cssSelector 在 dom 内选择的所有元素的文本内容组成的数组，它类似于下面的 javascript 伪代码。

```javascript
dom.querySelectorAll(cssSelector).map(e => e.textContent)
```

**示例：**

```sql
select
    dom_all_texts(dom, 'ul li.item a.name') as ProductNames,
    dom_all_texts(dom, 'ul li.item span.price') as ProductPrices,
    dom_all_texts(dom, 'ul li.item span.star') as ProductStars
from
    load_and_select('https://www.example.com/zgbs/appliances', 'div.products')
```

## String Functions

大多数字符串函数是通过程序从 org.apache.commons.lang3.StringUtils 自动转换而来。你可以在下述文件中找到 UDF 定义：[StringFunctions](/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/StringFunctions.kt)（[国内镜像](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/StringFunctions.kt)），所有的字符串函数都在命名空间 STR 中。

### STR_SUBSTRING_AFTER

```sql
STR_SUBSTRING_AFTER(str, separator)
```

获取第一次出现的分隔符后的子串。

示例：

```sql
select
    str_substring_after(dom_first_text(dom, '#price'), '$') as Price
from
    load_and_select('https://www.amazon.com/dp/B08PP5MSVB', 'body');
```

------

[上一章](12massive-crawling.md) [目录](1home.md) [下一章](14AI-extraction.md)
