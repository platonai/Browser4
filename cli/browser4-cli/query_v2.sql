SELECT
    DOM_FIRST_TEXT(DOM, '[class*="product-title"]') AS title,
    DOM_FIRST_TEXT(DOM, '[class*="product-price"]') AS price,
    DOM_FIRST_HREF(DOM, 'a') AS link,
    DOM_FIRST_IMG(DOM, 'img') AS image,
    DOM_FIRST_ATTR(DOM, 'data-category-id') AS category_id,
    DOM_FIRST_ATTR(DOM, 'id') AS product_id
FROM DOM_LOAD_AND_SELECT(@url, '#product-list > div')
WHERE DOM_IS_NOT_NIL(DOM)
