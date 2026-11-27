SELECT
    DOM_BASE_URI(DOM) AS category_url,
    DOM_FIRST_ATTR(DOM, ':root', 'data-asin') AS asin,
    DOM_FIRST_TEXT(DOM, 'h2') AS title,
    DOM_FIRST_TEXT(DOM, '.a-price .a-offscreen') AS price,
    DOM_FIRST_TEXT(DOM, '.a-icon-alt') AS rating,
    DOM_FIRST_TEXT(DOM, '.a-size-small .a-size-base') AS reviews,
    DOM_FIRST_ATTR(DOM, 'a[href*="/dp/"], a[href*="/gp/product/"]', 'abs:href') AS product_url,
    DOM_FIRST_TEXT(DOM, '.zg-bdg-text') AS rank
FROM DOM_LOAD_AND_SELECT(
    @url,
    '.s-result-item[data-component-type="s-search-result"], div[data-asin]'
)
WHERE DOM_FIRST_ATTR(DOM, ':root', 'data-asin') IS NOT NULL
  AND DOM_FIRST_TEXT(DOM, 'h2') IS NOT NULL
