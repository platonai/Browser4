package ai.platon.pulsar.test.server

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Serves mock HTML pages tailored to each scenario in
 * skills/browser4-cli/references/htmlsnapshot-scenarios.md.
 *
 * All endpoints live under /htmlsnapshot-test/ and produce text/html.
 * Each page contains 5–8 repeating elements so X-SQL `load_and_select`
 * queries return meaningful multi-row results.
 */
@RestController
class HtmlSnapshotMockController {

    // =========================================================================
    // Scenario 2 — News Headline Aggregator
    // =========================================================================

    @GetMapping("/htmlsnapshot-test/news", produces = [MediaType.TEXT_HTML_VALUE])
    fun newsPage(): String = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Hacker News</title>
    <style>
        body { font-family: Verdana, Geneva, sans-serif; margin: 8px; }
        table { border-collapse: collapse; }
        .titleline > a { color: #000; text-decoration: none; }
        .titleline > a:visited { color: #828282; }
        .titleline > a:hover { text-decoration: underline; }
        .score { color: #828282; font-size: 10pt; }
        .hnuser { color: #828282; font-size: 10pt; text-decoration: none; }
        .hnuser:hover { text-decoration: underline; }
        .athing { background: #f6f6ef; }
        .athing td { padding: 4px 8px; }
    </style>
</head>
<body>
    <center>
        <table border="0" cellpadding="0" cellspacing="0" width="85%" bgcolor="#f6f6ef">
            <tr><td>
                <table border="0" cellpadding="0" cellspacing="0" width="100%">
                    <tr><td bgcolor="#ff6600" height="24">
                        <b style="margin-right:8px;">Hacker News</b>
                    </td></tr>
                </table>

                <!-- Story list -->
                <table border="0" cellpadding="0" cellspacing="0" width="100%">
                    <tr class="athing" id="37000001">
                        <td align="right" valign="top">1.</td>
                        <td>
                            <span class="titleline">
                                <a href="https://example.com/ai-breakthrough">New AI Breakthrough in Natural Language Understanding</a>
                            </span>
                        </td>
                    </tr>
                    <tr>
                        <td colspan="1"></td>
                        <td class="subtext">
                            <span class="score">120 points</span> by
                            <a class="hnuser" href="https://news.ycombinator.com/user?id=alex">alex</a>
                        </td>
                    </tr>

                    <tr class="athing" id="37000002">
                        <td align="right" valign="top">2.</td>
                        <td>
                            <span class="titleline">
                                <a href="https://example.com/rust-2-release">Rust 2.0 Released with Major Async Improvements</a>
                            </span>
                        </td>
                    </tr>
                    <tr>
                        <td colspan="1"></td>
                        <td class="subtext">
                            <span class="score">89 points</span> by
                            <a class="hnuser" href="https://news.ycombinator.com/user?id=rustacean">rustacean</a>
                        </td>
                    </tr>

                    <tr class="athing" id="37000003">
                        <td align="right" valign="top">3.</td>
                        <td>
                            <span class="titleline">
                                <a href="https://example.com/webassembly-perf">WebAssembly Performance Tuning Guide</a>
                            </span>
                        </td>
                    </tr>
                    <tr>
                        <td colspan="1"></td>
                        <td class="subtext">
                            <span class="score">45 points</span> by
                            <a class="hnuser" href="https://news.ycombinator.com/user?id=wasm_dev">wasm_dev</a>
                        </td>
                    </tr>

                    <tr class="athing" id="37000004">
                        <td align="right" valign="top">4.</td>
                        <td>
                            <span class="titleline">
                                <a href="https://example.com/kubernetes-security">Kubernetes Security Best Practices in 2025</a>
                            </span>
                        </td>
                    </tr>
                    <tr>
                        <td colspan="1"></td>
                        <td class="subtext">
                            <span class="score">210 points</span> by
                            <a class="hnuser" href="https://news.ycombinator.com/user?id=cloudsec">cloudsec</a>
                        </td>
                    </tr>

                    <tr class="athing" id="37000005">
                        <td align="right" valign="top">5.</td>
                        <td>
                            <span class="titleline">
                                <a href="https://example.com/postgres-17">PostgreSQL 17: What's New in Query Optimization</a>
                            </span>
                        </td>
                    </tr>
                    <tr>
                        <td colspan="1"></td>
                        <td class="subtext">
                            <span class="score">156 points</span> by
                            <a class="hnuser" href="https://news.ycombinator.com/user?id=dbadmin">dbadmin</a>
                        </td>
                    </tr>

                    <tr class="athing" id="37000006">
                        <td align="right" valign="top">6.</td>
                        <td>
                            <span class="titleline">
                                <a href="https://example.com/llm-fine-tuning">Fine-Tuning LLMs with Limited Data</a>
                            </span>
                        </td>
                    </tr>
                    <tr>
                        <td colspan="1"></td>
                        <td class="subtext">
                            <span class="score">67 points</span> by
                            <a class="hnuser" href="https://news.ycombinator.com/user?id=mlwhiz">mlwhiz</a>
                        </td>
                    </tr>
                </table>

                <a id="more-link" href="https://news.ycombinator.com/news?p=2">More</a>
            </td></tr>
        </table>
    </center>
</body>
</html>"""

    // =========================================================================
    // Scenario 3 — SEO Health Audit
    // =========================================================================

    @GetMapping("/htmlsnapshot-test/seo", produces = [MediaType.TEXT_HTML_VALUE])
    fun seoPage(): String = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>SEO Health Audit Test Page</title>
    <meta name="description" content="A comprehensive guide to SEO best practices including meta tags, heading structure, image alt text, and link management.">
    <meta name="keywords" content="SEO, meta tags, heading structure, accessibility, web development">
    <link rel="canonical" href="https://example.com/blog/seo-best-practices">
    <style>
        body { font-family: Arial, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; }
        img { max-width: 100%; }
        h2 { margin-top: 24px; }
        .content-section { margin-bottom: 20px; }
    </style>
</head>
<body>
    <header>
        <h1>SEO Health Audit Test Page</h1>
    </header>

    <nav>
        <a href="/blog">Blog Home</a> |
        <a href="/about">About</a> |
        <a href="/contact">Contact</a>
    </nav>

    <main>
        <section class="content-section">
            <h2>Section A — On-Page SEO</h2>
            <p>On-page SEO involves optimizing individual web pages to rank higher. Key elements include title tags, meta descriptions, and heading structure.</p>
            <!-- Image WITH alt text -->
            <img src="/images/onpage-seo.png" alt="On-page SEO diagram showing title, meta, and heading elements">
        </section>

        <section class="content-section">
            <h2>Section B — Technical SEO</h2>
            <p>Technical SEO ensures that search engines can crawl and index your site effectively.</p>
            <!-- Image WITHOUT alt text (missing alt attribute) -->
            <img src="/images/technical-seo.png">
        </section>

        <section class="content-section">
            <h2>Section C — Link Building</h2>
            <p>Quality backlinks remain one of the strongest ranking signals.</p>
            <!-- Another image WITHOUT alt text -->
            <img src="/images/link-building-chart.png">
            <!-- Image WITH alt text -->
            <img src="/images/backlinks-quality.png" alt="Chart comparing backlink quality metrics">
        </section>

        <section class="content-section">
            <h3>Outbound Resources</h3>
            <ul>
                <li><a href="https://developers.google.com/search/docs" rel="nofollow noopener" target="_blank">Google Search Documentation</a></li>
                <li><a href="https://moz.com/beginners-guide-to-seo" rel="nofollow" target="_blank">Moz Beginner's Guide to SEO</a></li>
                <li><a href="https://ahrefs.com/blog/seo-basics/">Ahrefs SEO Basics</a></li>
                <li><a href="https://www.semrush.com/blog/seo-best-practices/" rel="noopener">SEMrush Best Practices</a></li>
                <li><a href="https://backlinko.com/hub/seo/technical" target="_blank">Backlinko Technical SEO Guide</a></li>
                <li><a href="/internal/seo-checklist">Internal SEO Checklist</a></li>
            </ul>
        </section>
    </main>

    <footer>
        <p>&copy; 2025 SEO Test Site</p>
        <a href="/privacy">Privacy Policy</a>
    </footer>
</body>
</html>"""

    // =========================================================================
    // Scenario 5 — Job Board Scraper
    // =========================================================================

    @GetMapping("/htmlsnapshot-test/jobs", produces = [MediaType.TEXT_HTML_VALUE])
    fun jobsPage(): String = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Job Search Results — Senior Frontend Engineer</title>
    <style>
        body { font-family: -apple-system, system-ui, sans-serif; background: #f3f2ef; margin: 0; padding: 0; }
        .jobs-search-results { max-width: 800px; margin: 24px auto; }
        .jobs-search-results__list-item { font-size: 14px; color: #666; padding: 8px; }
        .job-card-container { background: #fff; border: 1px solid #e0e0e0; border-radius: 8px; padding: 16px; margin-bottom: 12px; }
        .job-card-container:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
        .job-card-list__title { font-size: 18px; font-weight: 600; color: #0a66c2; margin-bottom: 4px; }
        .job-card-list__title:hover { text-decoration: underline; cursor: pointer; }
        .job-card-container__company-name { font-size: 14px; color: #333; }
        .job-card-container__metadata-item { font-size: 13px; color: #666; }
        .job-search-card__salary-info { font-size: 13px; color: #057642; margin-top: 4px; }
    </style>
</head>
<body>
    <div class="jobs-search-results">
        <h1>Senior Frontend Engineer Jobs</h1>
        <p class="jobs-search-results__list-item">Showing 1-5 of 5 results</p>

        <article class="job-card-container" data-job-id="job-001">
            <h2 class="job-card-list__title">Senior Frontend Engineer</h2>
            <div class="job-card-container__company-name">TechCorp</div>
            <div class="job-card-container__metadata-item">San Francisco, CA</div>
            <div class="job-search-card__salary-info">$150k - $200k</div>
        </article>

        <article class="job-card-container" data-job-id="job-002">
            <h2 class="job-card-list__title">Lead Frontend Developer</h2>
            <div class="job-card-container__company-name">StartupXYZ</div>
            <div class="job-card-container__metadata-item">Remote, US</div>
            <div class="job-search-card__salary-info">$140k - $180k</div>
        </article>

        <article class="job-card-container" data-job-id="job-003">
            <h2 class="job-card-list__title">Senior React Engineer</h2>
            <div class="job-card-container__company-name">BigData Inc.</div>
            <div class="job-card-container__metadata-item">New York, NY</div>
            <div class="job-search-card__salary-info">$160k - $210k</div>
        </article>

        <article class="job-card-container" data-job-id="job-004">
            <h2 class="job-card-list__title">Frontend Architect</h2>
            <div class="job-card-container__company-name">CloudNative Ltd.</div>
            <div class="job-card-container__metadata-item">Austin, TX</div>
            <!-- intentionally missing salary info for nullability testing -->
        </article>

        <article class="job-card-container" data-job-id="job-005">
            <h2 class="job-card-list__title">Staff Frontend Engineer</h2>
            <div class="job-card-container__company-name">FinanceHub</div>
            <div class="job-card-container__metadata-item">Chicago, IL</div>
            <div class="job-search-card__salary-info">$170k - $220k</div>
        </article>
    </div>
</body>
</html>"""

    // =========================================================================
    // Scenario 6 — Compliance Verification
    // =========================================================================

    @GetMapping("/htmlsnapshot-test/compliance", produces = [MediaType.TEXT_HTML_VALUE])
    fun compliancePage(): String = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Bank Example — Savings Products</title>
    <style>
        body { font-family: 'Segoe UI', sans-serif; margin: 0; padding: 0; }
        header { background: #003366; color: white; padding: 16px 24px; }
        main { max-width: 900px; margin: 24px auto; padding: 0 24px; }
        .legal-disclaimer {
            background: #fff3cd; border: 1px solid #ffc107; padding: 16px;
            margin: 24px 0; border-radius: 4px; font-size: 13px; color: #856404;
        }
        #cookie-consent-banner {
            position: fixed; bottom: 0; left: 0; right: 0;
            background: #222; color: #fff; padding: 12px 24px;
            font-size: 13px; display: flex; justify-content: space-between; align-items: center;
        }
        #cookie-consent-banner button {
            background: #4CAF50; color: white; border: none; padding: 8px 16px;
            border-radius: 4px; cursor: pointer;
        }
        footer { background: #f5f5f5; padding: 24px; margin-top: 48px; font-size: 13px; }
        footer a { color: #003366; margin-right: 16px; }
        .product-card { border: 1px solid #ddd; border-radius: 6px; padding: 16px; margin: 12px 0; }
        .product-card h3 { margin-top: 0; }
        .rate { font-size: 24px; color: #003366; font-weight: bold; }
    </style>
</head>
<body>
    <header>
        <h1>Bank Example</h1>
        <nav>
            <a href="/" style="color: white; margin-right: 16px;">Home</a>
            <a href="/products" style="color: white; margin-right: 16px;">Products</a>
            <a href="/about" style="color: white;">About</a>
        </nav>
    </header>

    <main>
        <h2>Savings Products</h2>

        <div class="product-card">
            <h3>High-Yield Savings Account</h3>
            <p class="rate">4.25% APY</p>
            <p>Minimum balance: ${'$'}1,000</p>
        </div>

        <div class="product-card">
            <h3>Premium Savings Account</h3>
            <p class="rate">4.75% APY</p>
            <p>Minimum balance: ${'$'}10,000</p>
        </div>

        <!-- Legal disclaimer (scenario 6a target) -->
        <div class="legal-disclaimer">
            <p><strong>Legal Disclaimer:</strong> The information provided on this page is for informational purposes only and does not constitute financial advice. Interest rates are subject to change without notice. Terms and conditions apply. Please consult the full product disclosure statement before making any financial decisions. Past performance is not indicative of future results. All deposits are FDIC insured up to applicable limits.</p>
        </div>
    </main>

    <!-- Cookie consent banner (scenario 6b target) -->
    <div id="cookie-consent-banner">
        <span>This website uses cookies to ensure you get the best experience. By continuing to use this site, you agree to our use of cookies.</span>
        <button id="cookie-accept-btn">Accept All</button>
    </div>

    <footer>
        <a href="/about/accessibility-statement">Accessibility Statement</a>
        <a href="/about/privacy-policy">Privacy Policy</a>
        <a href="/about/terms-of-service">Terms of Service</a>
        <a href="/contact">Contact Us</a>
        <p style="margin-top: 8px; color: #666;">&copy; 2025 Bank Example. All rights reserved. Member FDIC.</p>
    </footer>
</body>
</html>"""

    // =========================================================================
    // Scenario 7 — Academic Literature Metadata Extraction
    // =========================================================================

    @GetMapping("/htmlsnapshot-test/research", produces = [MediaType.TEXT_HTML_VALUE])
    fun researchPage(): String = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>PubMed Search Results — machine learning drug discovery</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 0; padding: 0; }
        .search-results { max-width: 900px; margin: 24px auto; }
        .docsum-content { border-bottom: 1px solid #ddd; padding: 16px 0; }
        .docsum-title { font-size: 16px; font-weight: 600; color: #0d47a1; margin-bottom: 4px; }
        .docsum-title a { color: #0d47a1; text-decoration: none; }
        .docsum-title a:hover { text-decoration: underline; }
        .full-author-list { font-size: 13px; color: #555; margin-bottom: 2px; }
        .docsum-journal-citation { font-size: 12px; color: #777; }
        .abstract-content { margin-top: 8px; font-size: 13px; color: #333; line-height: 1.5; }
        .heading-title { font-size: 22px; font-weight: bold; color: #333; }
    </style>
</head>
<body>
    <div class="search-results">
        <h1>PubMed Search Results</h1>
        <p>Search: machine learning drug discovery — 5 results</p>

        <div class="docsum-content" data-pmid="12345678">
            <div class="docsum-title">
                <a href="/pubmed/12345678">Machine Learning Approaches in Drug Discovery: A Systematic Review</a>
            </div>
            <div class="full-author-list">Smith J, Doe A, Chen L, Williams K</div>
            <div class="docsum-journal-citation">Nature Reviews Drug Discovery. 2025 Mar;24(3):180-195. doi: 10.1038/s41573-025-00123-4</div>
            <a href="/pubmed/12345678" class="abstract-link">Show abstract</a>
            <div class="abstract-content">This systematic review examines recent advances in machine learning approaches for drug discovery, covering deep learning models for molecular property prediction, generative models for de novo drug design, and reinforcement learning for lead optimization. We analyzed 250 papers published between 2020 and 2025, identifying key trends and methodological improvements that have significantly accelerated the drug discovery pipeline.</div>
        </div>

        <div class="docsum-content" data-pmid="23456789">
            <div class="docsum-title">
                <a href="/pubmed/23456789">Deep Learning for Protein-Ligand Binding Affinity Prediction</a>
            </div>
            <div class="full-author-list">Garcia M, Johnson P, Lee S</div>
            <div class="docsum-journal-citation">Journal of Chemical Information and Modeling. 2025 Feb;65(2):450-462. doi: 10.1021/acs.jcim.4c00891</div>
            <a href="/pubmed/23456789" class="abstract-link">Show abstract</a>
        </div>

        <div class="docsum-content" data-pmid="34567890">
            <div class="docsum-title">
                <a href="/pubmed/34567890">Graph Neural Networks for Molecular Toxicity Prediction</a>
            </div>
            <div class="full-author-list">Wang X, Brown R, Taylor M, Nguyen H</div>
            <div class="docsum-journal-citation">Bioinformatics. 2025 Jan;41(1):120-132. doi: 10.1093/bioinformatics/btae123</div>
            <a href="/pubmed/34567890" class="abstract-link">Show abstract</a>
        </div>

        <div class="docsum-content" data-pmid="45678901">
            <div class="docsum-title">
                <a href="/pubmed/45678901">Transformer-Based Models for Drug-Target Interaction Prediction</a>
            </div>
            <div class="full-author-list">Patel R, Kim D, Anderson J</div>
            <div class="docsum-journal-citation">Briefings in Bioinformatics. 2025 Jan;26(1):bbae456. doi: 10.1093/bib/bbae456</div>
            <a href="/pubmed/45678901" class="abstract-link">Show abstract</a>
        </div>

        <div class="docsum-content" data-pmid="56789012">
            <div class="docsum-title">
                <a href="/pubmed/56789012">Generative AI for De Novo Molecular Design: Opportunities and Challenges</a>
            </div>
            <div class="full-author-list">Thompson E, Zhang Y, Martinez C, Park J, Davis R</div>
            <div class="docsum-journal-citation">Trends in Pharmacological Sciences. 2024 Dec;45(12):1012-1025. doi: 10.1016/j.tips.2024.10.005</div>
            <a href="/pubmed/56789012" class="abstract-link">Show abstract</a>
        </div>
    </div>
</body>
</html>"""

    // =========================================================================
    // Scenario 8 — Real Estate Listing Monitor
    // =========================================================================

    @GetMapping("/htmlsnapshot-test/real-estate", produces = [MediaType.TEXT_HTML_VALUE])
    fun realEstatePage(): String = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>San Francisco CA Real Estate — Homes for Sale</title>
    <style>
        body { font-family: -apple-system, system-ui, sans-serif; margin: 0; padding: 0; background: #fafafa; }
        .search-results { max-width: 1000px; margin: 24px auto; padding: 0 16px; }
        article[data-test="property-card"] {
            background: #fff; border: 1px solid #e0e0e0; border-radius: 8px;
            padding: 16px; margin-bottom: 12px; display: flex; gap: 16px;
        }
        article[data-test="property-card"]:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.08); }
        .property-info { flex: 1; }
        [data-test="property-card-address"] { font-size: 17px; font-weight: 600; color: #333; }
        [data-test="property-card-price"] { font-size: 22px; font-weight: 700; color: #006aff; margin: 4px 0; }
        .property-stats { display: flex; gap: 16px; margin-top: 8px; font-size: 14px; color: #666; }
        .beds-container { }
        .baths-container { }
        .sqft-container { }
        .property-photo { width: 200px; height: 140px; background: #e0e0e0; border-radius: 4px; flex-shrink: 0; }
    </style>
</head>
<body>
    <div class="search-results">
        <h1>San Francisco CA Homes for Sale</h1>
        <p>5 results — Sorted by newest</p>

        <article data-test="property-card" data-listing-id="sf-001">
            <div class="property-photo"></div>
            <div class="property-info">
                <div data-test="property-card-address">123 Main St, San Francisco, CA 94102</div>
                <div data-test="property-card-price">${'$'}1,200,000</div>
                <div class="property-stats">
                    <span class="beds-container">3 beds</span> |
                    <span class="baths-container">2 baths</span> |
                    <span class="sqft-container">1,500 sqft</span>
                </div>
            </div>
        </article>

        <article data-test="property-card" data-listing-id="sf-002">
            <div class="property-photo"></div>
            <div class="property-info">
                <div data-test="property-card-address">456 Oak Avenue, San Francisco, CA 94110</div>
                <div data-test="property-card-price">${'$'}950,000</div>
                <div class="property-stats">
                    <span class="beds-container">2 beds</span> |
                    <span class="baths-container">1 bath</span> |
                    <span class="sqft-container">950 sqft</span>
                </div>
            </div>
        </article>

        <article data-test="property-card" data-listing-id="sf-003">
            <div class="property-photo"></div>
            <div class="property-info">
                <div data-test="property-card-address">789 Pine Street, San Francisco, CA 94108</div>
                <div data-test="property-card-price">${'$'}2,450,000</div>
                <div class="property-stats">
                    <span class="beds-container">4 beds</span> |
                    <span class="baths-container">3 baths</span> |
                    <span class="sqft-container">2,800 sqft</span>
                </div>
            </div>
        </article>

        <article data-test="property-card" data-listing-id="sf-004">
            <div class="property-photo"></div>
            <div class="property-info">
                <div data-test="property-card-address">321 Market St #501, San Francisco, CA 94105</div>
                <div data-test="property-card-price">${'$'}750,000</div>
                <div class="property-stats">
                    <span class="beds-container">1 bed</span> |
                    <span class="baths-container">1 bath</span> |
                    <span class="sqft-container">650 sqft</span>
                </div>
            </div>
        </article>

        <article data-test="property-card" data-listing-id="sf-005">
            <div class="property-photo"></div>
            <div class="property-info">
                <div data-test="property-card-address">555 Divisadero St, San Francisco, CA 94117</div>
                <div data-test="property-card-price">${'$'}1,650,000</div>
                <div class="property-stats">
                    <span class="beds-container">3 beds</span> |
                    <span class="baths-container">2.5 baths</span> |
                    <span class="sqft-container">1,850 sqft</span>
                </div>
            </div>
        </article>
    </div>
</body>
</html>"""
}
