/**
 * fetchWeather.js — weather plugin (Fetch the current weather for the active page)
 *
 * Runs inside the browser page (via WebDriver.evaluateValue / tab.eval).
 * Returns a plain object serialized as JSON.
 */
(function () {
  'use strict';

  var result = {
    url: location.href,
    data: {}
  };

  // TODO: implement fetchWeather logic here, e.g.:
  // result.data.headings = Array.from(document.querySelectorAll('h1'))
  //   .map(function (h) { return h.textContent.trim(); });

  return JSON.stringify(result, null, 2);
})();