/**
 * check-issues.js — Browser4 SEO plugin
 *
 * Runs inside the browser page and returns a list of SEO issues found.
 * Each issue: { severity, field, message, value }
 *
 * Standalone usage:
 *   browser4-cli eval --script seo/check-issues.js
 */
(function () {
  function pick(selector, attr) {
    var el = document.querySelector(selector);
    return el ? (el[attr] || el.getAttribute(attr)) : null;
  }
  var issues = [];
  function add(severity, field, message, value) {
    issues.push({ severity: severity, field: field, message: message, value: value });
  }

  var title = document.title || '';
  var description = pick('meta[name="description"]', 'content') || '';
  var canonical = pick('link[rel="canonical"]', 'href');
  var ogTitle = pick('meta[property="og:title"]', 'content');
  var ogImage = pick('meta[property="og:image"]', 'content');
  var ogDescription = pick('meta[property="og:description"]', 'content');
  var twitterCard = pick('meta[name="twitter:card"]', 'content');
  var robots = (pick('meta[name="robots"]', 'content') || '').toLowerCase();
  var h1Count = document.querySelectorAll('h1').length;
  var jsonLdCount = document.querySelectorAll('script[type="application/ld+json"]').length;
  var wordCount = (document.body && document.body.innerText || '').trim()
    ? (document.body.innerText || '').trim().split(/\s+/).length : 0;
  var imagesWithoutAlt = Array.from(document.querySelectorAll('img')).filter(function (i) { return !i.alt; }).length;

  // Title checks
  if (!title) add('error', 'title', 'Page has no <title> element', null);
  else {
    if (title.length < 10) add('warning', 'title', 'Title is too short (' + title.length + ' chars, recommend 30-60)', title);
    if (title.length > 60) add('warning', 'title', 'Title is too long (' + title.length + ' chars, recommend 30-60)', title);
  }

  // Description checks
  if (!description) add('error', 'description', 'Page has no meta description', null);
  else {
    if (description.length < 50) add('warning', 'description', 'Description is too short (' + description.length + ' chars, recommend 50-160)', description);
    if (description.length > 160) add('warning', 'description', 'Description is too long (' + description.length + ' chars, recommend 50-160)', description);
  }

  // Canonical
  if (!canonical) add('warning', 'canonical', 'Page has no canonical link', null);

  // Open Graph
  if (!ogTitle) add('warning', 'og:title', 'Missing og:title meta tag', null);
  if (!ogImage) add('warning', 'og:image', 'Missing og:image meta tag — social shares will have no preview image', null);
  if (!ogDescription) add('info', 'og:description', 'Missing og:description meta tag', null);

  // Twitter Card
  if (!twitterCard) add('info', 'twitter:card', 'Missing twitter:card meta tag', null);

  // Headings
  if (h1Count === 0) add('warning', 'h1', 'Page has no <h1> heading', null);
  if (h1Count > 1) add('warning', 'h1', 'Page has multiple <h1> headings (' + h1Count + '), recommend exactly one', h1Count);

  // Structured data
  if (jsonLdCount === 0) add('info', 'jsonLd', 'No JSON-LD structured data found', null);

  // Content
  if (wordCount < 300) add('warning', 'wordCount', 'Page has very little text content (' + wordCount + ' words)', wordCount);

  // Image alt text
  if (imagesWithoutAlt > 0) add('warning', 'images', imagesWithoutAlt + ' image(s) missing alt text', imagesWithoutAlt);

  // Robots
  if (robots.indexOf('noindex') >= 0) add('error', 'robots', 'Page is blocked from indexing (noindex)', robots);

  return {
    url: location.href,
    issueCount: issues.length,
    errorCount: issues.filter(function (i) { return i.severity === 'error'; }).length,
    warningCount: issues.filter(function (i) { return i.severity === 'warning'; }).length,
    infoCount: issues.filter(function (i) { return i.severity === 'info'; }).length,
    issues: issues
  };
})();
