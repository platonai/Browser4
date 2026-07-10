SELECT
    DOM_FIRST_TEXT(DOM, '[class*="product-title"]') AS title,
    DOM_FIRST_FLOAT(DOM, '[class*="product-price"]', 0.0) AS price,
    DOM_FIRST_HREF(DOM, 'a[class*="product-link"]') AS product_link,
    DOM_FIRST_IMG(DOM, 'img[class*="product-img"]') AS image_url
FROM DOM_LOAD_AND_SELECT(@url, 'div[class*="product-card"]', 1, 6)
WHERE DOM_IS_NOT_NIL(DOM)
