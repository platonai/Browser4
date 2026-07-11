SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, 'title') AS title,
    dom_first_text(dom, 'body') AS body_text
FROM load_and_select(@url, ':root')
