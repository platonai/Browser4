/**
 * countLinks.js — linkcheck plugin
 *
 * Counts all <a href="..."> links on the current page and classifies them.
 *
 * - total    = number of a[href] elements
 * - external = absolute http/https links whose origin differs from location.origin
 * - internal = everything else (relative links, # anchors, mailto:, tel:,
 *              same-origin absolute links)
 *
 * Runs inside the browser page (via WebDriver.evaluateValue / tab.eval).
 * Returns JSON.stringify({ total, external, internal }).
 */
(function () {
  'use strict';

  var links = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
  var total = links.length;
  var external = 0;

  links.forEach(function (a) {
    var href = a.getAttribute('href');
    if (!href) {
      return;
    }

    // Only absolute http(s) links can be external.
    if (/^https?:\/\//i.test(href)) {
      try {
        if (new URL(href).origin !== location.origin) {
          external += 1;
        }
      } catch (e) {
        // Malformed absolute URL — leave it in the internal bucket.
      }
    }
  });

  var internal = total - external;

  return JSON.stringify({ total: total, external: external, internal: internal });
})();
