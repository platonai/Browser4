// Plain-browser rebrowser-bot-detector baseline over raw CDP.
//
// Answers "would stock Edge/Chrome on this host be flagged too?" without Browser4,
// without any extension and without a page-world injection. It launches a browser
// with a throwaway profile, opens https://bot-detector.rebrowser.net/ and prints
// the detector's own result table.
//
// Usage: node plain-rebrowser.mjs [edge|chrome] [timeout-ms] [url]
//
// Set HEADED=1 to run headed instead of `--headless=new` (an Edge/Chrome window
// opens for the duration of the run).
//
// The browser is spawned with stdio 'ignore' and a throwaway profile; the DevTools
// port is read back from <profile>/DevToolsActivePort, so no pipes are involved.

import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const browserName = (process.argv[2] ?? 'edge').toLowerCase();
const timeoutMs = Number(process.argv[3] ?? 60_000);
const url = process.argv[4] ?? 'https://bot-detector.rebrowser.net/';

const executables = {
  chrome: [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  ],
  edge: [
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  ],
};
const candidates = executables[browserName] ?? executables.edge;
const executable = candidates.find((path) => {
  try { readFileSync(path); return true; } catch { return false; }
});
if (!executable) {
  throw new Error(`no executable found for ${browserName}: ${candidates.join(', ')}`);
}

const profile = mkdtempSync(join(tmpdir(), `plain-${browserName}-`));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const headed = /^(1|true|yes|on)$/i.test(process.env.HEADED ?? '');
const flags = [
  '--no-first-run',
  '--no-default-browser-check',
  '--remote-debugging-port=0',
  `--user-data-dir=${profile}`,
];
if (!headed) {
  flags.unshift('--headless=new', '--disable-gpu', '--no-sandbox');
} else {
  flags.push('--window-size=1280,900');
}
if (process.env.UA) {
  // Mirrors Browser4's launch-time `--user-agent` switch, to measure the client hints it
  // leaves behind (the headless UA fix is a launch-time flag, not a page patch).
  flags.push(`--user-agent=${process.env.UA}`);
}

const child = spawn(executable, [...flags, url],
  { stdio: 'ignore', detached: false }
);

function cleanup() {
  try { child.kill(); } catch { /* already gone */ }
  try { rmSync(profile, { recursive: true, force: true }); } catch { /* best effort */ }
}

async function devToolsPort() {
  const portFile = join(profile, 'DevToolsActivePort');
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      const [port] = readFileSync(portFile, 'utf8').split('\n');
      if (port && Number(port) > 0) { return Number(port); }
    } catch { /* not written yet */ }
    await sleep(200);
  }
  throw new Error(`DevToolsActivePort never appeared in ${profile}`);
}

async function pageSocket(port, wantedUrl) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/list`);
      const targets = await response.json();
      const pages = targets.filter((t) => t.type === 'page' && t.webSocketDebuggerUrl);
      const match = pages.find((t) => t.url.startsWith(wantedUrl.slice(0, 40))) ?? pages[0];
      if (match) { return match.webSocketDebuggerUrl; }
    } catch { /* endpoint not up yet */ }
    await sleep(300);
  }
  throw new Error('no page target with a debugger URL');
}

function cdp(ws) {
  let nextId = 1;
  const pending = new Map();
  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data);
    const resolver = pending.get(message.id);
    if (resolver) {
      pending.delete(message.id);
      resolver(message);
    }
  });
  return (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = nextId++;
      pending.set(id, resolve);
      ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (pending.delete(id)) { reject(new Error(`${method} timed out`)); }
      }, 30_000);
    });
}

let socket;
try {
  const port = await devToolsPort();
  const wsUrl = await pageSocket(port, url);
  socket = new WebSocket(wsUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve);
    socket.addEventListener('error', () => reject(new Error('debugger socket failed')));
  });
  const send = cdp(socket);
  await send('Runtime.enable');

  const readExpression = async (expression) => {
    const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    return result?.result?.result?.value ?? result?.result?.exceptionDetails?.text ?? null;
  };

  // The table fills in as the tests finish; wait for the last row's note.
  let text = '';
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    text = (await readExpression('document.body.innerText')) ?? '';
    if (/workerConsistency[\s\S]{0,400}?(matches the page|differs)/.test(text)) { break; }
    await sleep(1000);
  }

  const uaData = await readExpression(
    `(async () => JSON.stringify(await navigator.userAgentData.getHighEntropyValues(['fullVersionList'])))()`
  ).catch((error) => `error: ${error.message}`);

  const summary = await readExpression(`(() => {
    const nav = Object.getOwnPropertyNames(navigator);
    let iframeSharesRealm = null;
    const iframe = document.createElement('iframe');
    iframe.srcdoc = '<p></p>';
    iframe.style.display = 'none';
    document.body.appendChild(iframe);
    try { iframeSharesRealm = iframe.contentWindow.Navigator === window.Navigator; }
    catch (error) { iframeSharesRealm = 'error: ' + error.message; }
    iframe.remove();
    return JSON.stringify({
      userAgent: navigator.userAgent,
      navigatorOwnProperties: nav,
      functionToString: String(Function.prototype.toString),
      functionToStringIsProxy: (() => {
        try { Object.setPrototypeOf(Function.prototype.toString, Object.create(Function.prototype.toString)); return true; }
        catch { return false; }
      })(),
      iframeSharesRealm,
    });
  })()`).catch(() => null);

  console.log(JSON.stringify({
    mode: `plain ${browserName} (raw CDP, no browser4, no extensions)`,
    executable,
    userAgentOverride: process.env.UA ?? null,
    nativeChecks: typeof summary === 'string' ? JSON.parse(summary) : summary,
    highEntropy: typeof uaData === 'string' ? JSON.parse(uaData) : uaData,
    detectorText: text,
  }, null, 2));
} finally {
  try { socket?.close(); } catch { /* ignore */ }
  cleanup();
}
