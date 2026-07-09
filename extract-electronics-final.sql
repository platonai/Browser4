SELECT
    STR_TRIM(DOM_FIRST_TEXT(DOM, '[class*="product-title"]')) AS title,
    STR_UPPER_CASE(STR_TRIM(DOM_FIRST_TEXT(DOM, '[class*="product-title"]'))) AS title_upper,
    DOM_FIRST_FLOAT(DOM, '[class*="product-price"]', 0.0) AS price,
    STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, '[class*="product-price"]'), 0.0) AS price_from_text,
    STR_DEFAULT_IF_BLANK(DOM_FIRST_HREF(DOM, 'a[class*="product-link"]'), '/no-link') AS link,
    STR_DEFAULT_IF_BLANK(DOM_FIRST_IMG(DOM, 'img[class*="product-img"]'), 'no-image') AS image,
    DOM_FIRST_ATTR(DOM, 'data-category-id') AS category_id,
    ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(DOM_FIRST_TEXT(DOM, 'div.product-rating'), 'No rating')) AS rating,
    STR_ABBREVIATE(STR_DEFAULT_IF_BLANK(DOM_FIRST_TEXT(DOM, 'div.product-badges'), 'None'), 20) AS badge,
    DOM_WIDTH(DOM) AS card_width,
    DOM_HEIGHT(DOM) AS card_height
FROM DOM_LOAD_AND_SELECT(@url, 'div[class*="product-card"]:expr(width > 100)', 1, 20)
WHERE DOM_IS_NOT_NIL(DOM)
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, '[class*="product-title"]'))
  AND DOM_FIRST_FLOAT(DOM, '[class*="product-price"]', 0.0) IS NOT NULL
ORDER BY DOM_FIRST_FLOAT(DOM, '[class*="product-price"]', 999999.0) ASC
LIMIT 6
