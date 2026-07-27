SELECT
    DOM_FIRST_TEXT(DOM, 'a[href*="/ec/dp/"]') AS title,
    DOM_FIRST_TEXT(DOM, 'div:nth-child(5)') AS price,
    DOM_FIRST_ATTR(DOM, 'a[href*="/ec/dp/"]', 'href') AS link
FROM DOM_LOAD_AND_SELECT(@url, '.best-sellers > div')
WHERE DOM_FIRST_TEXT(DOM, 'a[href*="/ec/dp/"]') != ''
