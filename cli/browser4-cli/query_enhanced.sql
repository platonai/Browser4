SELECT
    -- Title: trim whitespace, use fallback chain, abbreviate long text
    STR_DEFAULT_IF_BLANK(
        STR_ABBREVIATE(
            STR_TRIM(
                ARRAY_FIRST_NOT_BLANK(
                    MAKE_ARRAY(
                        DOM_FIRST_TEXT(DOM, '[class*="product-title"]'),
                        DOM_FIRST_TEXT(DOM, 'a'),
                        DOM_FIRST_TEXT(DOM, 'div')
                    )
                )
            ),
            40
        ),
        '[No Title]'
    ) AS title,

    -- Price: normalize to uppercase for consistent display
    STR_UPPER_CASE(
        STR_TRIM(
            DOM_FIRST_TEXT(DOM, '[class*="product-price"]')
        )
    ) AS price_display,

    -- Price as numeric value
    STR_FIRST_FLOAT(
        DOM_FIRST_TEXT(DOM, '[class*="product-price"]'),
        0.0
    ) AS price_value,

    -- Clean link: remove escaped characters
    STR_REPLACE_CHARS(
        DOM_FIRST_HREF(DOM, 'a'),
        '\"',
        ''
    ) AS link,

    -- Clean image URL: remove escaped characters
    STR_REPLACE_CHARS(
        DOM_FIRST_IMG(DOM, 'img'),
        '\"',
        ''
    ) AS image,

    -- Product ID from data attributes with fallback
    ARRAY_FIRST_NOT_BLANK(
        MAKE_ARRAY(
            DOM_FIRST_ATTR(DOM, 'id'),
            DOM_FIRST_ATTR(DOM, 'data-category-id'),
            '[Unknown]'
        )
    ) AS product_id,

    -- Width for PowerCSS filtering
    DOM_WIDTH(DOM) AS card_width

FROM DOM_LOAD_AND_SELECT(
    @url,
    '#product-list > div[id]:expr(width > 100)',
    1, 48
)
WHERE DOM_IS_NOT_NIL(DOM)
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, '[class*="product-title"]'))
ORDER BY STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, '[class*="product-price"]'), 999999.0) ASC
LIMIT 10
