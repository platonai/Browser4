SELECT
    -- Core fields
    STR_TRIM(DOM_FIRST_TEXT(DOM, '[class*="product-title"]')) AS title,
    DOM_FIRST_FLOAT(DOM, '[class*="product-price"]', 0.0) AS price,
    DOM_FIRST_HREF(DOM, 'a[class*="product-link"]') AS product_link,
    DOM_FIRST_IMG(DOM, 'img[class*="product-img"]') AS image_url,

    -- Data attribute extraction (DOM_FIRST_ATTR)
    DOM_FIRST_ATTR(DOM, ':root', 'data-category-id') AS category_id,
    DOM_FIRST_ATTR(DOM, 'div.product-rating', 'data-rating') AS rating_data_attr,

    -- STR function: UPPER_CASE normalization
    STR_UPPER_CASE(STR_TRIM(DOM_FIRST_TEXT(DOM, '[class*="product-title"]'))) AS title_upper,

    -- STR function: DEFAULT_IF_BLANK for fallback values
    STR_DEFAULT_IF_BLANK(DOM_FIRST_TEXT(DOM, 'span.badge'), 'No Badge') AS badge,

    -- STR function: FIRST_FLOAT to extract numeric rating from text like "4.6 (521)"
    STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, 'div.product-rating'), 0.0) AS rating_numeric,

    -- STR function: ABBREVIATE to truncate long titles
    STR_ABBREVIATE(STR_TRIM(DOM_FIRST_TEXT(DOM, '[class*="product-title"]')), 20) AS title_short,

    -- ARRAY function: ARRAY_FIRST_NOT_BLANK with fallback selectors
    ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(
        DOM_FIRST_TEXT(DOM, '[class*="product-title"]'),
        DOM_FIRST_TEXT(DOM, 'h3'),
        DOM_FIRST_TEXT(DOM, 'a'),
        'Unknown Product'
    )) AS fallback_title,

    -- Visual feature: element width
    DOM_WIDTH(DOM) AS card_width

FROM DOM_LOAD_AND_SELECT(
    @url,
    'div[class*="product-card"]:expr(width > 150 && height > 100)',
    1, 6
)
WHERE DOM_IS_NOT_NIL(DOM)
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, '[class*="product-title"]'))
ORDER BY DOM_FIRST_FLOAT(DOM, '[class*="product-price"]', 0.0) ASC
LIMIT 5
