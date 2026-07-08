SELECT
    DOM_FIRST_TEXT(DOM, 'h1') AS title,
    DOM_FIRST_TEXT(DOM, '#product-price') AS price,
    DOM_FIRST_ATTR(DOM, 'img', 'src') AS image_url
FROM DOM_LOAD_AND_SELECT(@url, '#product-page', 1, 1)
WHERE DOM_IS_NOT_NIL(DOM)
