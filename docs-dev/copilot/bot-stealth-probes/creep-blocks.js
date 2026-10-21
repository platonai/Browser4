(function () {
  function readBlock(name) {
    var columns = document.querySelectorAll('.col-six');
    for (var i = 0; i < columns.length; i++) {
      var heading = columns[i].querySelector('strong');
      if (!heading || heading.textContent.trim().toLowerCase() !== name) { continue; }
      var values = [];
      var cells = columns[i].querySelectorAll('div');
      for (var j = 0; j < cells.length; j++) {
        var text = (cells[j].textContent || '').replace(/\s+/g, ' ').trim();
        values.push(text);
      }
      return values;
    }
    return null;
  }
  return JSON.stringify({
    headless: readBlock('headless'),
    resistance: readBlock('resistance'),
    ua: navigator.userAgent,
    webdriver: String(navigator.webdriver) + '/' + ('webdriver' in navigator)
  });
})()
