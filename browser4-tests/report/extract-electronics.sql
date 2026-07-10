-- X-SQL query: Extract Electronics products from MockSite
-- Multi-field extraction with DOM functions, STR cleaning, ARRAY fallbacks, PowerCSS :expr() filtering, WHERE, ORDER BY, LIMIT

SELECT
    -- Title: extract and clean with STR functions
    STR_TRIM(
        DOM_FIRST_TEXT(DOM, '[class*="product-title"]')
    ) AS title_raw,
    STR_UPPER_CASE(
        STR_TRIM(DOM_FIRST_TEXT(DOM, '[class*="product-title"]'))
    ) AS title_upper,

    -- Price: extract as float using DOM_FIRST_FLOAT
    DOM_FIRST_FLOAT(DOM, '[class*="product-price"]', 0.0) AS price_num,
    -- Price text: extract with STR_FIRST_FLOAT from text
    STR_FIRST_FLOAT(
        DOM_FIRST_TEXT(DOM, '[class*="product-price"]'), 0.0
    ) AS price_from_text,

    -- Link: extract href URL
    DOM_FIRST_HREF(DOM, 'a[class*="product-link"]') AS link_raw,
    -- Link with STR_DEFAULT_IF_BLANK fallback
    STR_DEFAULT_IF_BLANK(
        DOM_FIRST_HREF(DOM, 'a[class*="product-link"]'),
        '/no-link'
    ) AS link,

    -- Image: extract image URL
    DOM_FIRST_IMG(DOM, 'img[class*="product-img"]') AS image_raw,
    STR_DEFAULT_IF_BLANK(
        DOM_FIRST_IMG(DOM, 'img[class*="product-img"]'),
        'https://placeholder.example.com/no-image.jpg'
    ) AS image,

    -- Data attributes: extract data-category-id from product card
    DOM_FIRST_ATTR(DOM, 'data-category-id') AS data_category_id,

    -- Rating: use ARRAY_FIRST_NOT_BLANK for fallback selectors
    ARRAY_FIRST_NOT_BLANK(
        MAKE_ARRAY(
            DOM_FIRST_TEXT(DOM, 'div.product-rating'),
            DOM_FIRST_TEXT(DOM, '[data-rating]'),
            'No rating'
        )
    ) AS rating,

    -- Badges: with STR_ABBREVIATE for truncation
    STR_ABBREVIATE(
        STR_DEFAULT_IF_BLANK(
            DOM_FIRST_TEXT(DOM, 'div.product-badges'),
            'None'
        ),
        20
    ) AS badge,

    -- Product ID from element id attribute
    STR_DEFAULT_IF_BLANK(
        DOM_ATTR(DOM, 'id'),
        'unknown'
    ) AS product_id,

    -- Visual dimensions from computed features
    DOM_WIDTH(DOM) AS card_width,
    DOM_HEIGHT(DOM) AS card_height

FROM DOM_LOAD_AND_SELECT(
    @url,
    -- Use PowerCSS :expr() to filter product cards wider than 100px (exclude narrow elements)
    'div[class*="product-card"]:expr(width > 100)',
    1, 20
)

WHERE DOM_IS_NOT_NIL(DOM)
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, '[class*="product-title"]'))
  -- Filter: only products with a valid price
  AND DOM_FIRST_FLOAT(DOM, '[class*="product-price"]', -1.0) > 0

ORDER BY DOM_FIRST_FLOAT(DOM, '[class*="product-price"]', 999999.0) ASC

LIMIT 10
