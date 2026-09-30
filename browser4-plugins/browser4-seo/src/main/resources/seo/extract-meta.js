/**
 * extract-meta.js — Browser4 SEO plugin
 *
 * Runs inside the browser page (via driver.evaluate / browser4-cli eval).
 * Returns a plain object with all common SEO metadata found on the page.
 *
 * Standalone usage:
 *   browser4-cli eval --script seo/extract-meta.js
 */
(function () {
  function pick(selector, attr) {
    var el = document.querySelector(selector);
    return el ? (el[attr] || el.getAttribute(attr)) : null;
  }
  function pickAll(selector, attr) {
    var out = {};
    document.querySelectorAll(selector).forEach(function (el) {
      var key = el.getAttribute(attr);
      if (key) out[key] = el.content || el.getAttribute('content');
    });
    return out;
  }

  var bodyText = (document.body && document.body.innerText) || '';
  var jsonLd = [];
  document.querySelectorAll('script[type="application/ld+json"]').forEach(function (el) {
    try { jsonLd.push(JSON.parse(el.textContent)); } catch (e) { /* skip broken JSON-LD */ }
  });

  return {
    url: location.href,
    finalUrl: location.href,
    lang: document.documentElement.lang || null,
    title: document.title || null,
    description: pick('meta[name="description"]', 'content'),
    canonical: pick('link[rel="canonical"]', 'href'),
    robots: pick('meta[name="robots"]', 'content'),
    viewport: pick('meta[name="viewport"]', 'content'),
    charset: pick('meta[charset]', 'charset'),
    generator: pick('meta[name="generator"]', 'content'),
    author: pick('meta[name="author"]', 'content'),
    keywords: pick('meta[name="keywords"]', 'content'),
    og: pickAll('meta[property^="og:"]', 'property'),
    twitter: pickAll('meta[name^="twitter:"]', 'name'),
    headings: {
      h1: document.querySelectorAll('h1').length,
      h2: document.querySelectorAll('h2').length,
      h3: document.querySelectorAll('h3').length,
      h4: document.querySelectorAll('h4').length
    },
    images: document.querySelectorAll('img').length,
    imagesWithoutAlt: Array.from(document.querySelectorAll('img')).filter(function (i) { return !i.alt; }).length,
    links: document.querySelectorAll('a[href]').length,
    internalLinks: Array.from(document.querySelectorAll('a[href]')).filter(function (a) {
      return a.href && a.href.indexOf(location.origin) === 0;
    }).length,
    wordCount: bodyText.trim() ? bodyText.trim().split(/\s+/).length : 0,
    jsonLd: jsonLd,
    jsonLdCount: jsonLd.length
  };
})();
