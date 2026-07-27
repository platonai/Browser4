SELECT
    DOM_FIRST_TEXT(DOM, 'main h1') AS title,
    DOM_FIRST_TEXT(DOM, '.product-info div:nth-child(4)') AS price,
    DOM_FIRST_ATTR(DOM, 'img', 'src') AS image_url,
    DOM_ALL_TEXTS(DOM, '#product-specs tr') AS specs
FROM DOM_LOAD_AND_SELECT(@url, ':root')
