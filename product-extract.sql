SELECT
    DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
    DOM_FIRST_TEXT(DOM, '#product-price') AS price,
    DOM_FIRST_IMG(DOM, '#product-image') AS image_url
FROM DOM_LOAD_AND_SELECT(@url, ':root')
WHERE DOM_IS_NOT_NIL(DOM);
