// Plain-Chrome creepjs baseline over raw CDP.
//
// Drives a stock Chrome (no Browser4 code, no Browser4 launch flags, no page-world
// injection) and reads creepjs' own result blocks, so the "extension: puppeteer-extra"
// line reported for a Browser4 session can be attributed instead of guessed at.
//
// Usage: node plain-creepjs.mjs [url] [timeout-ms]
//
// Chrome is spawned with stdio 'ignore' and a throwaway profile; the DevTools port is
// read back from <profile>/DevToolsActivePort, so no pipes are involved.

import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const url = process.argv[2] ?? 'https://abrahamjuliot.github.io/creepjs/';
const timeoutMs = Number(process.argv[3] ?? 90_000);
const chrome = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

const here = dirname(fileURLToPath(import.meta.url));
const probe = readFileSync(join(here, 'creep-blocks.js'), 'utf8');
const nativeProbe = readFileSync(join(here, 'native-tostring.js'), 'utf8');

const profile = mkdtempSync(join(tmpdir(), 'plain-cdp-'));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const child = spawn(
  chrome,
  [
    '--headless=new',
    '--disable-gpu',
    '--no-sandbox',
    '--no-first-run',
    '--no-default-browser-check',
    '--remote-debugging-port=0',
    `--user-data-dir=${profile}`,
    url,
  ],
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

  const read = async () => {
    const result = await send('Runtime.evaluate', { expression: probe, returnByValue: true });
    const value = result?.result?.result?.value;
    return typeof value === 'string' ? JSON.parse(value) : null;
  };
  const readExpression = async (expression) => {
    const result = await send('Runtime.evaluate', { expression, returnByValue: true });
    const value = result?.result?.result?.value;
    if (typeof value !== 'string') { return value ?? null; }
    try { return JSON.parse(value); } catch { return value; }
  };

  // creepjs fills its blocks asynchronously; poll until the headless percentages move
  // off the placeholder zeros, or until the budget runs out.
  const deadline = Date.now() + timeoutMs;
  let last = null;
  let settled = null;
  while (Date.now() < deadline) {
    last = await read();
    const headless = last?.headless ?? [];
    const filled = headless.some((cell) => /[1-9]\d*% like headless/.test(cell));
    if (filled) { settled = last; break; }
    await sleep(2000);
  }

  console.log(JSON.stringify({
    mode: 'plain-chrome (raw CDP, no browser4)',
    chromeFlags: 'headless=new, disable-gpu, no-sandbox, remote-debugging-port=0',
    settled: Boolean(settled),
    creepjs: settled ?? last,
    nativeToString: await readExpression(nativeProbe),
  }, null, 2));
} finally {
  try { socket?.close(); } catch { /* ignore */ }
  cleanup();
}
