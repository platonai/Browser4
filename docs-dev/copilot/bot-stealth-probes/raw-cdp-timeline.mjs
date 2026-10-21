// Raw-CDP timeline probe for ipfighter chrome.runtime.sendMessage "Prototype leak".
// Drives a PERSISTENT CDP WebSocket session (unlike per-call CLI passthrough) so that
// Page.addScriptToEvaluateOnNewDocument survives navigation.
// Usage: node raw-cdp-timeline.mjs <cdpBase> <targetUrl>
import { spawn } from 'node:child_process';

const cdpBase = process.argv[2] || 'http://127.0.0.1:9333';
const targetUrl = process.argv[3] || 'https://ipfighter.com/bot-detection';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function httpJson(url, method = 'GET') {
  const res = await fetch(url, { method });
  return res.json();
}

function cdpFactory(ws) {
  let nextId = 1;
  const pending = new Map();
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
    }
  });
  return (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = nextId++;
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params }));
    });
}

// 在页面前置脚本：10ms 轮询，仅在状态变化时记录；首次出现 sendMessage 时抓描述符与已加载脚本
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
          resources: performance.getEntriesByType('resource').map((e) => e.initiatorType + ':' + e.name.split('/').slice(-2).join('/')).slice(-12)
        };
      }
      log.push(rec);
      last = state;
    } catch (e) { log.push({ t: Date.now() - t0, when, err: String(e) }); }
  };
  emit('docstart');
  let n = 0;
  const iv = setInterval(() => { emit('p' + n); if (++n > 999) clearInterval(iv); }, 10);
  document.addEventListener('readystatechange', () => emit('rs:' + document.readyState));
  window.addEventListener('load', () => emit('load'));
  window.__leakLog = log;
})();`;

const finalEval = `(() => {
  const c = window.chrome, rt = c && c.runtime, sm = rt && rt.sendMessage;
  let verdict = null;
  document.querySelectorAll('*').forEach((el) => {
    if (el.children.length === 0 && /chrome\\.runtime|Prototype leak|sendMessage/i.test(el.textContent || '')) {
      const row = el.closest('div');
      if (row && row.textContent.length < 400) verdict = (verdict ? verdict + ' || ' : '') + row.textContent.replace(/\\s+/g, ' ').trim();
    }
  });
  return {
    url: location.href,
    finalChromeKeys: c ? Object.getOwnPropertyNames(c) : null,
    finalRuntimeType: typeof rt,
    liveCheck: (c && rt && sm)
      ? { ownProto: sm.hasOwnProperty('prototype'), str: Function.prototype.toString.call(sm).slice(0, 110) }
      : 'runtime-or-sendMessage ABSENT (site test would be pass:N/A)',
    verdictText: verdict,
    allScripts: performance.getEntriesByType('resource').filter((e) => /\\.js(\\?|$)/.test(e.name)).map((e) => e.name)
  };
})()`;

const main = async () => {
  // 1) 找一个现有 page target 或新建
  const targets = await httpJson(`${cdpBase}/json/list`);
  let target = targets.find((t) => t.type === 'page' && t.url.startsWith('about:'));
  if (!target) {
    target = await httpJson(`${cdpBase}/json/new?about:blank`, 'PUT');
  }
  console.log('TARGET', target.id, target.url);
  const ws = new WebSocket(target.webSocketDebuggerUrl, {
    headers: { Host: `127.0.0.1:${new URL(cdpBase).port}` },
  });
  await new Promise((r, e) => { ws.addEventListener('open', r, { once: true }); ws.addEventListener('error', e); });
  const cdp = cdpFactory(ws);

  await cdp('Page.enable');
  const reg = await cdp('Page.addScriptToEvaluateOnNewDocument', { source: hookSource });
  console.log('HOOK identifier:', reg.identifier);
  await cdp('Page.navigate', { url: targetUrl });

  await sleep(11000);

  const tl = await cdp('Runtime.evaluate', { expression: 'window.__leakLog', returnByValue: true });
  const fin = await cdp('Runtime.evaluate', { expression: finalEval, returnByValue: true });

  console.log('\n===== TIMELINE (state changes only) =====');
  for (const rec of tl.result.value || []) console.log(JSON.stringify(rec));
  console.log('\n===== FINAL =====');
  console.log(JSON.stringify(fin.result.value, null, 1));

  // 2) 归因：抓所有已加载脚本，grep chrome.runtime / sendMessage 赋值
  const scripts = (fin.result.value && fin.result.value.allScripts) || [];
  console.log('\n===== SCRIPT GREP =====');
  for (const url of scripts) {
    try {
      const txt = await (await fetch(url)).text();
      const pats = ['chrome.runtime=', 'chrome["runtime"]', 'chrome.runtime=Object', '.runtime={', 'runtime: {', 'sendMessage=', 'sendMessage=function', 'sendMessage:function'];
      const hits = pats.filter((p) => txt.includes(p));
      if (hits.length) {
        console.log('\n## ' + url + '  HITS: ' + hits.join(' | '));
        let i = -1;
        let shown = 0;
        while ((i = txt.indexOf('sendMessage', i + 1)) >= 0 && shown < 3) {
          console.log('  ...' + txt.slice(Math.max(0, i - 180), i + 120).replace(/\s+/g, ' ') + '...');
          shown++;
        }
      }
    } catch (e) {
      console.log('FETCH FAIL ' + url + ' ' + e);
    }
  }
  ws.close();
};

main().catch((e) => { console.error('FATAL', e); process.exit(1); });
