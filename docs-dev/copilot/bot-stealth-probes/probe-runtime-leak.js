() => {
  const sleep = (ms) => new Promise(r => setTimeout(r, ms));
  return (async () => {
    await sleep(6000);
    const out = {};

    // 1) 对象形态
    out.hasChrome = typeof window.chrome === 'object' && window.chrome !== null;
    out.chromeKeys = window.chrome ? Object.getOwnPropertyNames(window.chrome) : null;
    const rt = window.chrome && window.chrome.runtime;
    out.runtimeType = typeof rt;
    out.runtimeKeys = rt ? Object.getOwnPropertyNames(rt) : null;
    out.runtimeToString = rt ? Function.prototype.toString.call(rt) : null;
    const sm = rt && rt.sendMessage;
    if (sm) {
      out.sm_toString = Function.prototype.toString.call(sm);
      out.sm_hasOwnPrototype = Object.prototype.hasOwnProperty.call(sm, 'prototype');
      out.sm_prototypeIn = 'prototype' in sm;
      out.sm_prototypeOwnProps = sm.prototype ? Object.getOwnPropertyNames(sm.prototype) : null;
      out.sm_ownProps = Object.getOwnPropertyNames(sm);
      out.sm_name = sm.name;
      out.sm_length = sm.length;
      // 描述符
      const d = Object.getOwnPropertyDescriptor(rt, 'sendMessage');
      out.sm_desc = d ? {hasValue: 'value' in d, hasGet: 'get' in d, writable: d.writable, configurable: d.configurable, enumerable: d.enumerable} : null;
      // toString 来源
      const tsDesc = Object.getOwnPropertyDescriptor(Function.prototype, 'toString');
      out.sm_toStringNative = /\[native code\]/.test(out.sm_toString);
    }
    // onMessage 等其他 runtime 成员形态对照
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

    // 2) 页面判定文本（找含 runtime / prototype / leak 的结果项）
    const texts = [];
    document.querySelectorAll('*').forEach(el => {
      const t = el.children.length === 0 ? el.textContent : '';
      if (t && /runtime|prototype|leak|sendMessage/i.test(t) && t.length < 300) {
        texts.push(t.trim());
      }
    });
    out.matchedTexts = [...new Set(texts)].slice(0, 30);

    // 3) 抓站点检测脚本里 sendMessage 相关代码
    const scripts = performance.getEntriesByType('resource')
      .filter(e => /\.js(\?|$)/.test(e.name)).map(e => e.name);
    out.scriptCount = scripts.length;
    out.snippets = [];
    for (const url of scripts) {
      try {
        const txt = await fetch(url).then(r => r.text());
        const idx = txt.indexOf('sendMessage');
        if (idx >= 0) {
          out.snippets.push({url: url.split('/').slice(-2).join('/'), idx, code: txt.slice(Math.max(0, idx - 600), idx + 400)});
        }
      } catch (e) {
        out.snippets.push({url, error: String(e)});
      }
    }
    return out;
  })();
}
