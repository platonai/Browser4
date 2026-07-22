SELECT
    DOM_FIRST_TEXT(DOM, 'h2 a, .a-link-normal.s-line-clamp-4') AS title,
    DOM_FIRST_TEXT(DOM, '.a-price .a-offscreen, .a-price-whole') AS price,
    DOM_FIRST_TEXT(DOM, '.a-icon-alt') AS rating,
    DOM_FIRST_TEXT(DOM, '.a-size-base.s-underline-text, .a-row.a-size-small span:expr(char>10)') AS reviews,
    DOM_FIRST_ATTR(DOM, 'h2 a, a.a-link-normal.s-line-clamp-4', 'href') AS link
FROM DOM_LOAD_AND_SELECT(@url, '[data-component-type="s-search-result"]')
