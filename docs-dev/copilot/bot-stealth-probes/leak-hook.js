(() => {
  window.__leakLog = [];
  window.__leakT0 = Date.now();
  const snap = (when) => {
    try {
      const c = window.chrome;
      const rt = c && c.runtime;
      const sm = rt && rt.sendMessage;
      window.__leakLog.push({
        t: Date.now() - window.__leakT0,
        when,
        chromeKeys: c ? Object.getOwnPropertyNames(c).slice(0, 15) : null,
        runtimeKeys: rt ? Object.getOwnPropertyNames(rt).slice(0, 25) : null,
        sm: sm ? {
          ownProto: sm.hasOwnProperty('prototype'),
          protoIn: 'prototype' in sm,
          str: Function.prototype.toString.call(sm).slice(0, 130),
          name: sm.name
        } : null
      });
    } catch (e) { window.__leakLog.push({t: Date.now() - window.__leakT0, when, err: String(e)}); }
  };
  snap('docstart');
  let n = 0;
  const iv = setInterval(() => {
    snap('p' + n);
    if (++n > 159) clearInterval(iv);
  }, 50);
  document.addEventListener('readystatechange', () => snap('rs:' + document.readyState));
  window.addEventListener('load', () => snap('load'));
})();
