SELECT
    DOM_FIRST_TEXT(DOM, 'h3 a') AS title,
    DOM_FIRST_TEXT(DOM, 'p.price_color') AS price
FROM DOM_LOAD_AND_SELECT(@url, '.product_pod', 1, 48)
