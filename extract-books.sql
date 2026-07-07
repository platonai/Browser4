SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, 'h1') AS title,
  dom_first_text(dom, '.price_color') AS price
FROM load_and_select(@url, ':root')
