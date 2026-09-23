(function () {
  var src = Function.prototype.toString;
  function nativeSource(object, property) {
    try {
      var descriptor = Object.getOwnPropertyDescriptor(object, property);
      var fn = descriptor && (descriptor.get || descriptor.value);
      if (!fn) { return 'absent'; }
      return String(src.call(fn)).replace(/\s+/g, ' ');
    } catch (error) {
      return 'error: ' + error.message;
    }
  }
  var samples = {
    'Function.prototype.toString': String(src.call(src)),
    'Document.prototype.createElement': nativeSource(Document.prototype, 'createElement'),
    'HTMLIFrameElement.prototype.contentWindow': nativeSource(HTMLIFrameElement.prototype, 'contentWindow'),
    'Navigator.prototype.hardwareConcurrency': nativeSource(Navigator.prototype, 'hardwareConcurrency'),
    'Screen.prototype.availWidth': nativeSource(Screen.prototype, 'availWidth'),
    'CanvasRenderingContext2D.prototype.getImageData': nativeSource(CanvasRenderingContext2D.prototype, 'getImageData'),
    'AnalyserNode.prototype.getByteFrequencyData': nativeSource(AnalyserNode.prototype, 'getByteFrequencyData')
  };
  var distinct = {};
  Object.keys(samples).forEach(function (key) {
    if (key === 'Function.prototype.toString') { return; }
    distinct[samples[key]] = (distinct[samples[key]] || 0) + 1;
  });
  var sources = Object.keys(distinct);
  return JSON.stringify({
    samples: samples,
    distinctNativeSources: sources.length,
    sharedByAll: sources.length === 1 ? sources[0] : null,
    allLookNative: sources.every(function (s) { return /\{\s*\[native code\]\s*\}/.test(s); })
  });
})()
