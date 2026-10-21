() => {
  const out = {};
  out.hasChrome = typeof window.chrome === 'object' && window.chrome !== null;
  out.chromeKeys = window.chrome ? Object.getOwnPropertyNames(window.chrome) : null;
  const rt = window.chrome && window.chrome.runtime;
  out.runtimeType = typeof rt;
  out.runtimeKeys = rt ? Object.getOwnPropertyNames(rt) : null;
  const sm = rt && rt.sendMessage;
  if (sm) {
    out.sm_toString = Function.prototype.toString.call(sm);
    out.sm_hasOwnPrototype = Object.prototype.hasOwnProperty.call(sm, 'prototype');
    out.sm_prototypeIn = 'prototype' in sm;
    out.sm_prototypeOwnProps = sm.prototype ? Object.getOwnPropertyNames(sm.prototype) : null;
    out.sm_ownProps = Object.getOwnPropertyNames(sm);
    out.sm_name = sm.name;
    const d = Object.getOwnPropertyDescriptor(rt, 'sendMessage');
    out.sm_desc = d ? {hasValue: 'value' in d, hasGet: 'get' in d, writable: d.writable, configurable: d.configurable, enumerable: d.enumerable} : null;
  }
  if (rt) {
    out.others = {};
    for (const k of ['connect','getURL','id','onMessage','onConnect']) {
      if (k in rt) {
        const v = rt[k];
        out.others[k] = typeof v === 'function' ? {
          toString: Function.prototype.toString.call(v).slice(0,120),
          hasOwnPrototype: Object.prototype.hasOwnProperty.call(v,'prototype')
        } : String(v);
      }
    }
  }
  const texts = [];
  document.querySelectorAll('*').forEach(el => {
    const t = el.children.length === 0 ? el.textContent : '';
    if (t && /runtime|prototype|leak|sendMessage/i.test(t) && t.length < 300) texts.push(t.trim());
  });
  out.matchedTexts = [...new Set(texts)].slice(0, 30);
  out.scripts = performance.getEntriesByType('resource').filter(e => /\.js(\?|$)/.test(e.name)).map(e => e.name);
  return out;
}
