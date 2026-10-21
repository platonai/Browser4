() => {
  const xhr = new XMLHttpRequest();
  xhr.open('GET', 'https://ipfighter.com/js/web/bot-detection.min.js?v=1.0.2', false);
  xhr.send();
  const txt = xhr.responseText;
  const out = {len: txt.length, hits: []};
  let idx = -1;
  while ((idx = txt.indexOf('sendMessage', idx + 1)) >= 0) {
    out.hits.push({idx, code: txt.slice(Math.max(0, idx - 700), idx + 500)});
    if (out.hits.length >= 5) break;
  }
  // 同时找 Prototype leak 文案附近逻辑
  const p = txt.indexOf('Prototype leak');
  if (p >= 0) out.prototypeLeakCtx = txt.slice(Math.max(0, p - 900), p + 200);
  return out;
}
