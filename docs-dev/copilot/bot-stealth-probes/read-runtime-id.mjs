// Read chrome.runtime.id on the ipfighter tab to identify which extension leaks runtime.
const cdpBase = process.argv[2];
const urlPart = process.argv[3] || 'ipfighter.com';

const main = async () => {
  const targets = await (await fetch(`${cdpBase}/json/list`)).json();
  const page = targets.find((t) => t.type === 'page' && t.url.includes(urlPart));
  if (!page) { console.log('NO PAGE'); process.exit(2); }
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
  const r = await send('Runtime.evaluate', {
    expression: `JSON.stringify({
      id: window.chrome && window.chrome.runtime && window.chrome.runtime.id,
      keys: window.chrome && window.chrome.runtime ? Object.getOwnPropertyNames(window.chrome.runtime) : null,
      smOwnProto: window.chrome && window.chrome.runtime && window.chrome.runtime.sendMessage
        ? window.chrome.runtime.sendMessage.hasOwnProperty('prototype') : null
    })`,
    returnByValue: true
  }, sessionId);
  console.log(r.result.value);
  // 顺带列出该浏览器的全部扩展 targets
  const all = await send('Target.getTargets', {});
  for (const t of all.targetInfos) {
    if (t.type === 'service_worker' || t.type === 'background_page' || t.type === 'shared_worker') {
      console.log('EXT-TARGET', t.type, t.url.slice(0, 110), 'id=' + t.targetId);
    }
  }
  ws.close();
  process.exit(0);
};
main().catch((e) => { console.error('FATAL', e); process.exit(1); });
