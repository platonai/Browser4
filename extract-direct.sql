SELECT
    DOM_BASE_URI(DOM) AS url,
    DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
    DOM_FIRST_TEXT(DOM, '#product-price') AS price
FROM DOM_LOAD_AND_SELECT('http://localhost:18080/generated/crawl/product/1.html', 'body')
