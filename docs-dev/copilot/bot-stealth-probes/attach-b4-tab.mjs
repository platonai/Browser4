// Attach to Browser4-managed Chrome, explicitly attach to the ipfighter page target,
// install timeline hook, reload, dump window.__leakLog.
const cdpBase = process.argv[2];
const urlPart = process.argv[3] || 'ipfighter.com';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const hookSource = `(() => {
  const log = [];
  const t0 = Date.now();
  let last = '';
  let seenSm = false;
  const sig = (sm) => sm
    ? (sm.hasOwnProperty('prototype') ? 'OWN-PROTO' : 'no-own-proto') + '|name=' + (sm.name||'') + '|' + Function.prototype.toString.call(sm).slice(0, 110)
    : 'ABSENT';
  const emit = (when) => {
    try {
      const c = window.chrome;
      const rt = c && c.runtime;
      const sm = rt && rt.sendMessage;
      const state = 'chrome=[' + (c ? Object.getOwnPropertyNames(c).join(',') : 'ABSENT') + '] runtime=['
        + (rt ? Object.getOwnPropertyNames(rt).slice(0, 30).join(',') : 'ABSENT') + '] sm=' + sig(sm);
      if (state === last) return;
      const rec = { t: Date.now() - t0, when, state };
      if (sm && !seenSm) {
        seenSm = true;
        const d1 = Object.getOwnPropertyDescriptor(rt, 'sendMessage');
        const d2 = Object.getOwnPropertyDescriptor(c, 'runtime');
        rec.firstSeen = {
          smDesc: d1 ? { writable: d1.writable, configurable: d1.configurable, enumerable: d1.enumerable, hasValue: 'value' in d1, hasGetter: 'get' in d1 } : null,
          rtDesc: d2 ? { writable: d2.writable, configurable: d2.configurable, enumerable: d2.enumerable, hasValue: 'value' in d2, hasGetter: 'get' in d2 } : null,
          readyState: document.readyState,
          smOwnProps: Object.getOwnPropertyNames(sm),
          rtAllKeys: Object.getOwnPropertyNames(rt),
          resources: performance.getEntriesByType('resource').map((e) => e.initiatorType + ':' + e.name.split('/').slice(-2).join('/')).slice(-14)
        };
      }
      log.push(rec);
      last = state;
    } catch (e) { log.push({ t: Date.now() - t0, when, err: String(e) }); }
  };
  emit('docstart');
  let n = 0;
  const iv = setInterval(() => { emit('p' + n); if (++n > 1499) clearInterval(iv); }, 10);
  document.addEventListener('readystatechange', () => emit('rs:' + document.readyState));
  window.addEventListener('load', () => emit('load'));
  window.__leakLog = log;
})();`;

const main = async () => {
  const targets = await (await fetch(`${cdpBase}/json/list`)).json();
  console.log('ALL TARGETS:');
  for (const t of targets) console.log(' ', t.type, t.url.slice(0, 90), t.id);
  const page = targets.find((t) => t.type === 'page' && t.url.includes(urlPart));
  if (!page) {
    console.log('NO MATCHING PAGE TARGET');
    process.exit(2);
  }
  console.log('ATTACHING to', page.id, page.url);

  const version = await (await fetch(`${cdpBase}/json/version`)).json();
  const ws = new WebSocket(version.webSocketDebuggerUrl);
  await new Promise((r, e) => { ws.addEventListener('open', r, { once: true }); ws.addEventListener('error', e); });

  let nextId = 1;
  const pending = new Map();
  const send = (method, params = {}, sessionId) =>
    new Promise((resolve, reject) => {
      const id = nextId++;
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }));
    });
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
    }
  });

  const { sessionId } = await send('Target.attachToTarget', { targetId: page.id, flatten: true });
  console.log('SESSION', sessionId);
  await send('Page.enable', {}, sessionId);
  await send('Page.addScriptToEvaluateOnNewDocument', { source: hookSource }, sessionId);
  await send('Page.reload', { ignoreCache: true }, sessionId);
  await sleep(12000);

  const r = await send('Runtime.evaluate', { expression: 'JSON.stringify(window.__leakLog || null)', returnByValue: true }, sessionId);
  console.log('\n===== TIMELINE =====');
  const val = r.result.value;
  if (!val) console.log('NO __leakLog');
  else for (const rec of JSON.parse(val)) console.log(JSON.stringify(rec));

  const f = await send('Runtime.evaluate', {
    expression: `(() => { const c=window.chrome,rt=c&&c.runtime,sm=rt&&rt.sendMessage;
      let verdict=null; document.querySelectorAll('*').forEach(el=>{
        if(el.children.length===0 && /Prototype leak|Clean|N\\/A/.test(el.textContent||'')){
          const row=el.closest('div'); if(row&&/runtime|sendMessage/i.test(row.textContent)&&row.textContent.length<400)
            verdict=(verdict?verdict+' || ':'')+row.textContent.replace(/\\s+/g,' ').trim(); }});
      return JSON.stringify({chromeKeys:c?Object.getOwnPropertyNames(c):null, runtimeType:typeof rt,
        sm: sm?{ownProto:sm.hasOwnProperty('prototype'),str:Function.prototype.toString.call(sm).slice(0,110)}:null,
        verdictText:verdict, url:location.href}); })()`,
    returnByValue: true
  }, sessionId);
  console.log('\n===== FINAL =====');
  console.log(JSON.stringify(JSON.parse(f.result.value), null, 1));
  ws.close();
  process.exit(0);
};

main().catch((e) => { console.error('FATAL', e); process.exit(1); });
