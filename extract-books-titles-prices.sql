SELECT
    DOM_FIRST_ATTR(DOM, 'h3 a', 'title') AS title,
    DOM_FIRST_TEXT(DOM, 'p.price_color') AS price
FROM DOM_LOAD_AND_SELECT(@url, '.product_pod')
WHERE DOM_IS_NOT_NIL(DOM)
ORDER BY DOM_FIRST_FLOAT(DOM, 'p.price_color', 999999.0) ASC
