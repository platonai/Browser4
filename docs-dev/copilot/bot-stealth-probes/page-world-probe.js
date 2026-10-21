(function () {
  // Read the PAGE's main world, not the world this expression is evaluated in.
  //
  // An inline <script> appended to the document runs in the page's main world, and the
  // two worlds do not share JS globals (an isolated world has its own `window`), so the
  // main-world reading is handed back through a DOM attribute — the document itself is
  // shared.
  var inline = "(function(){" +
    "function src(o,p){try{var d=Object.getOwnPropertyDescriptor(o,p);var f=d&&(d.get||d.value);" +
    "return f?String(Function.prototype.toString.call(f)).replace(/\\s+/g,' '):'absent'}catch(e){return 'error'}}" +
    "var s={" +
    "fts:String(Function.prototype.toString.call(Function.prototype.toString))," +
    "createElement:src(Document.prototype,'createElement')," +
    "contentWindow:src(HTMLIFrameElement.prototype,'contentWindow')," +
    "hardwareConcurrency:src(Navigator.prototype,'hardwareConcurrency')," +
    "availWidth:src(Screen.prototype,'availWidth')," +
    "getImageData:src(CanvasRenderingContext2D.prototype,'getImageData')," +
    "getByteFrequencyData:src(AnalyserNode.prototype,'getByteFrequencyData')};" +
    "var distinct={};Object.keys(s).forEach(function(k){distinct[s[k]]=(distinct[s[k]]||0)+1});" +
    "var payload=JSON.stringify({samples:s,distinctSources:Object.keys(distinct).length," +
    "sharedByAll:Object.keys(distinct).length===1?Object.keys(distinct)[0]:null," +
    "allLookNative:Object.keys(distinct).every(function(x){return /\\{\\s*\\[native code\\]\\s*\\}/.test(x)})," +
    "userAgent:navigator.userAgent,hardwareConcurrency:navigator.hardwareConcurrency," +
    "deviceMemory:navigator.deviceMemory,maxTouchPoints:navigator.maxTouchPoints});" +
    "document.documentElement.setAttribute('data-pw-probe',payload);" +
    "})();";
  var script = document.createElement('script');
  script.textContent = inline;
  (document.head || document.documentElement).appendChild(script);
  script.remove();
  return document.documentElement.getAttribute('data-pw-probe') || 'page-world probe did not run';
})()
