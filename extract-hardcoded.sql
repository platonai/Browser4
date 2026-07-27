SELECT
    dom_text(dom) AS text
FROM load_and_select('http://localhost:18080/generated/crawl/product/1.html', 'h1')
