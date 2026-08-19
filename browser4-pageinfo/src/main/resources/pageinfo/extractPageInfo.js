/**
 * extractPageInfo.js — pageinfo plugin (Extract page title, URL and meta tags via browser-side JavaScript)
 *
 * Runs inside the browser page (via WebDriver.evaluateValue / tab.eval).
 * Returns a plain object serialized as JSON.
 */
(function () {
  'use strict';

  function metaContent(name) {
    var el = document.querySelector('meta[name="' + name + '"]') ||
             document.querySelector('meta[property="' + name + '"]');
    return el ? (el.getAttribute('content') || '').trim() : '';
  }

  function canonicalUrl() {
    var link = document.querySelector('link[rel="canonical"]');
    return link ? (link.getAttribute('href') || '') : '';
  }

  function headingStructure() {
    var counts = { h1: 0, h2: 0, h3: 0, h4: 0, h5: 0, h6: 0 };
    for (var i = 1; i <= 6; i++) {
      counts['h' + i] = document.querySelectorAll('h' + i).length;
    }
    var firstH1 = document.querySelector('h1');
    return {
      counts: counts,
      firstH1: firstH1 ? firstH1.textContent.trim().slice(0, 200) : ''
    };
  }

  var result = {
    url: location.href,
    title: document.title,
    lang: document.documentElement.getAttribute('lang') || '',
    description: metaContent('description'),
    keywords: metaContent('keywords'),
    ogTitle: metaContent('og:title'),
    ogDescription: metaContent('og:description'),
    canonical: canonicalUrl(),
    charset: document.characterSet || '',
    headings: headingStructure(),
    textLength: document.body ? document.body.innerText.length : 0,
    linkCount: document.querySelectorAll('a[href]').length,
    imageCount: document.querySelectorAll('img').length
  };

  return JSON.stringify(result);
})();
