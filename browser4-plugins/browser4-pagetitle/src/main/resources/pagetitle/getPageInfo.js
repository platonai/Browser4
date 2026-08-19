(function () {
  'use strict';

  var description = '';
  var meta = document.querySelector('meta[name="description"]');
  if (meta) {
    description = meta.getAttribute('content') || '';
  }

  return JSON.stringify({
    title: document.title,
    url: location.href,
    description: description
  });
})();
