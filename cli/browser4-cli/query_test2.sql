SELECT
    DOM_TEXT(DOM) AS full_text,
    DOM_TAG_NAME(DOM) AS tag,
    DOM_CLASS_NAME(DOM) AS classes
FROM DOM_LOAD_AND_SELECT(@url, '#product-list > div')
WHERE DOM_IS_NOT_NIL(DOM)
LIMIT 2
