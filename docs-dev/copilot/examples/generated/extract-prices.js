/**
 * extract-prices — DOM data extraction
 *
 * Runs in browser context via tab.eval or plugin resource.
 * Returns a JSON string with extracted data.
 */
(function() {
    'use strict';

    var result = {
        url: window.location.href,
        title: document.title,
        timestamp: new Date().toISOString(),
        data: {}
    };

    // TODO: Extract data from the DOM
    // Example:
    // result.data.headings = Array.from(
    //   document.querySelectorAll('h1, h2, h3')
    // ).map(function(h) { return h.textContent.trim(); });

    return JSON.stringify(result, null, 2);
})();