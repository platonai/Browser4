(function () {
  // creepjs names iframe members first (contentDocument / contentWindow) and reports
  // hasIframeProxy, so the "Pattern" it hashes may come from a child realm rather than
  // the top document.  Read both realms from the page's main world and compare.
  var inline = "(function(){" +
    "function src(win,o,p){try{var d=Object.getOwnPropertyDescriptor(win[o].prototype,p);" +
    "var f=d&&(d.get||d.value);return f?String(win.Function.prototype.toString.call(f)).replace(/\\s+/g,' '):'absent'}catch(e){return 'error:'+e.message}}" +
    "function digest(win){" +
    "var s={fts:String(win.Function.prototype.toString.call(win.Function.prototype.toString))," +
    "createElement:src(win,'Document','createElement')," +
    "getElementById:src(win,'Document','getElementById')," +
    "contentWindow:src(win,'HTMLIFrameElement','contentWindow')," +
    "appendChild:src(win,'Node','appendChild')," +
    "getImageData:src(win,'CanvasRenderingContext2D','getImageData')," +
    "toDataURL:src(win,'HTMLCanvasElement','toDataURL')," +
    "getByteFrequencyData:src(win,'AnalyserNode','getByteFrequencyData')," +
    "hardwareConcurrency:src(win,'Navigator','hardwareConcurrency')," +
    "availWidth:src(win,'Screen','availWidth')};" +
    "var distinct={};Object.keys(s).forEach(function(k){distinct[s[k]]=(distinct[s[k]]||0)+1});" +
    "return {samples:s,distinctSources:Object.keys(distinct).length," +
    "sharedByAll:Object.keys(distinct).length===1?Object.keys(distinct)[0]:null," +
    "allNative:Object.keys(distinct).every(function(x){return /\\{\\s*\\[native code\\]\\s*\\}/.test(x)})}}" +
    "var top=digest(window);" +
    "var f=document.createElement('iframe');f.style.display='none';document.documentElement.appendChild(f);" +
    "var inner=digest(f.contentWindow);f.remove();" +
    "document.documentElement.setAttribute('data-pw-probe',JSON.stringify({topDocument:top,childFrame:inner}));" +
    "})();";
  var script = document.createElement('script');
  script.textContent = inline;
  (document.head || document.documentElement).appendChild(script);
  script.remove();
  return document.documentElement.getAttribute('data-pw-probe') || 'page-world probe did not run';
})()
