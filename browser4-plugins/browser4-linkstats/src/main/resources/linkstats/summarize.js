/**
 * summarize.js — linkstats plugin (统计页面链接分布)
 *
 * Runs inside the browser page (via WebDriver.evaluateValue / tab.eval).
 * Counts every <a href> anchor on the page and classifies it:
 *   - skips anchors nested inside <script>/<style> containers
 *   - skips empty href values and javascript: pseudo-protocol links
 *   - internal: relative paths or links sharing the page host
 *   - external: links to any other host
 *   - mailto:/tel: links are counted separately (not internal/external)
 *   - nofollow: anchors whose rel attribute contains "nofollow"
 *
 * Returns a JSON string: {url, title, total, internal, external, mailto, tel, nofollow}.
 *
 * Named-function + invocation pattern (not an IIFE) to survive the JS confuser
 * pipeline, which breaks parenthesized function expressions.
 */
function __b4_linkstats_summarize() {
  'use strict';

  var pageUrl = location.href;
  var pageTitle = document.title || '';

  var pageHost = '';
  try {
    pageHost = new URL(pageUrl).host;
  } catch (e) {
    pageHost = '';
  }

  var counts = { total: 0, internal: 0, external: 0, mailto: 0, tel: 0, nofollow: 0 };

  var anchors = document.querySelectorAll('a[href]');
  for (var i = 0; i < anchors.length; i++) {
    var a = anchors[i];

    // Skip anchors inside script/style containers
    if (a.closest && (a.closest('script') || a.closest('style'))) {
      continue;
    }

    var rawHref = (a.getAttribute('href') || '').trim();
    if (!rawHref) {
      continue; // empty href
    }
    if (/^javascript:/i.test(rawHref)) {
      continue; // javascript: pseudo-protocol
    }

    var lowerHref = rawHref.toLowerCase();
    if (lowerHref.indexOf('mailto:') === 0) {
      counts.mailto++;
      counts.total++;
      continue;
    }
    if (lowerHref.indexOf('tel:') === 0) {
      counts.tel++;
      counts.total++;
      continue;
    }

    counts.total++;

    var host = '';
    try {
      host = new URL(a.href, document.baseURI).host;
    } catch (e2) {
      host = '';
    }

    // Relative paths resolve against the page host; an href that cannot be
    // resolved to another host never leaves the site, so it is internal.
    if (host && pageHost && host === pageHost) {
      counts.internal++;
    } else if (!host) {
      counts.internal++;
    } else {
      counts.external++;
    }

    var rel = (a.getAttribute('rel') || '').toLowerCase();
    if (/(^|\s)nofollow(\s|$)/.test(rel)) {
      counts.nofollow++;
    }
  }

  return {
    url: pageUrl,
    title: pageTitle,
    total: counts.total,
    internal: counts.internal,
    external: counts.external,
    mailto: counts.mailto,
    tel: counts.tel,
    nofollow: counts.nofollow
  };
}

JSON.stringify(__b4_linkstats_summarize())