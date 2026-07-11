SELECT
    DOM_FIRST_TEXT(DOM, 'h2[itemprop="title"]') AS title,
    DOM_FIRST_TEXT(DOM, 'h3[itemprop="name"]') AS company,
    DOM_FIRST_HREF(DOM, 'a.preventLink') AS link
FROM DOM_LOAD_AND_SELECT(@url, 'tr.job', 1, 200)
WHERE DOM_IS_NOT_NIL(DOM)
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, 'h2[itemprop="title"]'))
