SELECT
    dom_base_uri(dom) AS url,
    dom_count(dom, 'a[href*="/jobs/"]') AS job_links,
    dom_text(dom) AS full_text
FROM load_and_select(@url, 'body')
