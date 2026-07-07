SELECT
    DOM_FIRST_TEXT(DOM, '[class*="product-title"]') AS title,
    LLM_EXTRACT(DOM, '[class*="product-title"]', 'Extract the product category from this title') AS category
FROM DOM_LOAD_AND_SELECT(@url, '#product-list > div', 1, 2)
WHERE DOM_IS_NOT_NIL(DOM)
