#!/usr/bin/env node

/**
 * agent-log-parser.js — parsing & enumeration for the Agent Logs viewer.
 *
 * Turns the `logs/agent` tree (agent run traces + LLM chat sessions) into
 * structured, viewer-friendly data. Designed as a standalone module so it can
 * be unit-tested without booting the express server.
 *
 * Directory layout (see AgentPaths.kt in browser4-agentic):
 *   <logs root>/<YYYYMMDD.HHMMSS>/<run-uuid>/
 *       cli-events.jsonl         run-level events (run.start / overflow / complete)
 *       cli-tool-trace.jsonl     tool calls (seq, tool, arguments, durationMs, resultText)
 *       cli-usage.jsonl          per-request token usage
 *       cli-compactions.jsonl    context compaction records
 *       cli-prompt/*.request.json  raw LLM request payloads
 *       task-<id>/
 *           agent-trace.jsonl    trajectory events (resolveStart / action / complete ...)
 *           context.jsonl        context snapshots
 *           history.jsonl        task summaries
 *           state-history.jsonl  agent state snapshots
 *           process_trace.log    human-readable event dump
 *   <logs root>/chat/<MMDD>/
 *       chat-<timestamp>.chat.sys.log    system prompt (plain text)
 *       chat-<timestamp>.chat.user.log   request/response blocks (;;KEY: format)
 *
 * The default logs root resolves to `<repo>/logs/agent` when present (the dev
 * symlink created by AgentPaths), otherwise `~/.browser4/logs/agent` (the
 * durable data-dir home).
 */

'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');

// Safety caps for reading large traces.
const MAX_READ_BYTES = 16 * 1024 * 1024;   // refuse to slurp files bigger than this
const MAX_JSONL_ITEMS = 20000;             // per-file jsonl item cap
const MAX_TEXT_LINES = 50000;              // per-file text line cap
const MAX_EVENTS_PER_RUN = 200;            // cli-events preview per run in listings

// ── Root resolution ───────────────────────────────────────────────────────

/** Walk up from the module dir to find the repository root (dir with .git). */
function findRepoRoot() {
  let d = path.resolve(__dirname, '..', '..');
  while (d !== path.dirname(d)) {
    if (fs.existsSync(path.join(d, '.git'))) return d;
    d = path.dirname(d);
  }
  return null;
}

/**
 * Resolve the agent logs root directory.
 *
 * @param {string|null} explicit --logs-root CLI value (already resolved)
 * @returns {{ root: string, source: string }} root path and how it was chosen
 */
function resolveAgentLogsRoot(explicit) {
  if (explicit) return { root: path.resolve(explicit), source: 'explicit' };

  const repo = findRepoRoot();
  if (repo) {
    const dev = path.join(repo, 'logs', 'agent');
    if (fs.existsSync(dev)) return { root: dev, source: 'dev-link' };
  }

  const home = process.env.HOME || process.env.USERPROFILE || os.homedir();
  return { root: path.join(home, '.browser4', 'logs', 'agent'), source: 'data-dir' };
}

// ── Small filesystem helpers ──────────────────────────────────────────────

function readdirDirs(dir) {
  if (!fs.existsSync(dir)) return [];
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('.')) continue;
    if (entry.isDirectory()) out.push(entry.name);
  }
  return out.sort();
}

function listFilesRecursive(dir, base) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('.')) continue;
    const full = path.join(dir, entry.name);
    const rel = path.relative(base, full).replace(/\\/g, '/');
    if (entry.isDirectory()) {
      out.push(...listFilesRecursive(full, base));
    } else {
      let stat = null;
      try { stat = fs.statSync(full); } catch (e) { /* ignore */ }
      out.push({
        name: entry.name,
        path: rel,
        size: stat ? stat.size : 0,
        modified: stat ? stat.mtime.toISOString() : null,
      });
    }
  }
  out.sort((a, b) => a.path.localeCompare(b.path));
  return out;
}

function safeReadText(filePath) {
  try {
    const stat = fs.statSync(filePath);
    if (stat.size > MAX_READ_BYTES) return { text: null, truncated: true, size: stat.size };
    return { text: fs.readFileSync(filePath, 'utf-8'), truncated: false, size: stat.size };
  } catch (e) {
    return { text: null, truncated: false, size: 0, error: e.message };
  }
}

/** Read only the first n lines of a file (for previews). */
function readHeadLines(filePath, n) {
  const { text, truncated } = safeReadText(filePath);
  if (text === null) return { lines: [], truncated, tooBig: true };
  const all = text.split(/\r?\n/);
  if (all.length > 0 && all[all.length - 1] === '') all.pop();
  const lines = all.slice(0, n);
  return { lines, truncated: truncated || all.length > n, tooBig: false };
}

function dirModified(dir) {
  let latest = 0;
  const walk = (d) => {
    let entries = [];
    try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch (e) { return; }
    for (const entry of entries) {
      if (entry.name.startsWith('.')) continue;
      const full = path.join(d, entry.name);
      try {
        const st = fs.statSync(full);
        if (st.mtimeMs > latest) latest = st.mtimeMs;
        if (entry.isDirectory()) walk(full);
      } catch (e) { /* ignore */ }
    }
  };
  walk(dir);
  return latest > 0 ? new Date(latest).toISOString() : null;
}

// ── JSONL parsing ─────────────────────────────────────────────────────────

/**
 * Parse JSONL text into an array of objects. Broken lines are skipped and
 * reported rather than failing the whole file.
 *
 * @param {string} text
 * @param {number} [limit=MAX_JSONL_ITEMS]
 * @returns {{ items: object[], parsed: number, skipped: number, errors: string[], truncated: boolean }}
 */
function parseJsonl(text, limit) {
  const cap = Math.max(parseInt(limit, 10) || MAX_JSONL_ITEMS, 1);
  const items = [];
  const errors = [];
  let parsed = 0;
  let skipped = 0;
  let truncated = false;

  const lines = String(text == null ? '' : text).split(/\r?\n/);
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    try {
      const obj = JSON.parse(trimmed);
      parsed++;
      if (items.length >= cap) { truncated = true; continue; }
      items.push(obj);
    } catch (e) {
      skipped++;
      if (errors.length < 5) errors.push(`line ${parsed + skipped + 1}: ${e.message}`);
    }
  }

  return { items, parsed, skipped, errors, truncated };
}

/** Parse a single .jsonl file from disk. */
function parseJsonlFile(filePath, limit) {
  const { text, truncated: tooBig, size } = safeReadText(filePath);
  if (text === null) {
    return { items: [], parsed: 0, skipped: 0, errors: [`file > ${MAX_READ_BYTES} bytes`], truncated: true, tooBig, size };
  }
  const result = parseJsonl(text, limit);
  result.truncated = result.truncated || tooBig;
  result.size = size;
  return result;
}

// ── Chat user log parsing ─────────────────────────────────────────────────

const CHAT_KEY_RE = /^;;([A-Z ]+):\s*(.*)$/;
const CHAT_SEPARATOR_RE = /^-{10,}$/;

/**
 * Parse a `chat-*.chat.user.log` file into request/response blocks.
 *
 * Block shape:
 *   ;;REQUEST ID: 1
 *   ;;TIMESTAMP: 2026-08-25T13:30:49.956857400
 *   ;;USER MESSAGE:
 *   <multiline text>
 *   ;;RESPONSE TIMESTAMP: ...
 *   ;;RESPONSE STATE: TOOL_EXECUTION
 *   ;;TOKEN USAGE: in: 3.2K out: 87 total: 3.3K
 *   ;;RESPONSE CONTENT:
 *   <multiline text>
 *
 * @param {string} text
 * @param {number} [limit=2000] max blocks
 * @returns {{ blocks: object[], truncated: boolean, malformed: number }}
 */
function parseChatUserLog(text, limit) {
  const cap = Math.max(parseInt(limit, 10) || 2000, 1);
  const blocks = [];
  let current = null;
  let key = null;
  let truncated = false;
  let malformed = 0;

  const flush = () => {
    if (current) {
      if (current.id != null) {
        blocks.push(current);
      } else {
        malformed++;
      }
      current = null;
    }
  };

  const lines = String(text == null ? '' : text).split(/\r?\n/);
  for (const raw of lines) {
    const line = raw.replace(/\r$/, '');

    if (CHAT_SEPARATOR_RE.test(line.trim())) {
      flush();
      continue;
    }

    const m = CHAT_KEY_RE.exec(line);
    if (m && m[1] === 'REQUEST ID') {
      flush();
      const id = parseInt(m[2].trim(), 10);
      current = {
        id: Number.isFinite(id) ? id : m[2].trim(),
        timestamp: null,
        userMessage: '',
        responseTimestamp: null,
        responseState: null,
        tokenUsage: null,
        tokenUsageParsed: null,
        responseContent: '',
      };
      key = 'REQUEST ID';
      continue;
    }

    if (!current) continue; // stray text before the first block

    if (m && (m[1] === 'TIMESTAMP' || m[1] === 'RESPONSE TIMESTAMP')) {
      // Header keys are uppercase; object properties are camelCase.
      const prop = key === 'USER MESSAGE' ? 'userMessage' : key === 'RESPONSE CONTENT' ? 'responseContent' : null;
      if (prop) {
        // Timestamp header appearing while a multiline value is open — close it.
        current[prop] = current[prop].replace(/\n$/, '');
        key = null;
      }
      // First TIMESTAMP → request, RESPONSE TIMESTAMP / second TIMESTAMP → response.
      if (m[1] === 'RESPONSE TIMESTAMP' || current.timestamp) {
        current.responseTimestamp = m[2].trim();
        key = 'RESPONSE TIMESTAMP';
      } else {
        current.timestamp = m[2].trim();
        key = 'TIMESTAMP';
      }
      continue;
    }

    if (m && (m[1] === 'USER MESSAGE' || m[1] === 'RESPONSE CONTENT' || m[1] === 'RESPONSE STATE' || m[1] === 'TOKEN USAGE')) {
      const value = m[2].trim();
      if (m[1] === 'USER MESSAGE') {
        if (blocks.length >= cap) { truncated = true; continue; }
        current.userMessage = value;
        key = 'USER MESSAGE';
      } else if (m[1] === 'RESPONSE CONTENT') {
        current.responseContent = value;
        key = 'RESPONSE CONTENT';
      } else if (m[1] === 'RESPONSE STATE') {
        current.responseState = value;
        key = null;
      } else if (m[1] === 'TOKEN USAGE') {
        current.tokenUsage = value;
        current.tokenUsageParsed = parseTokenUsage(value);
        key = null;
      }
      continue;
    }

    // Continuation of the current multiline value.
    if (key === 'USER MESSAGE') current.userMessage += (current.userMessage ? '\n' : '') + line;
    else if (key === 'RESPONSE CONTENT') current.responseContent += (current.responseContent ? '\n' : '') + line;
  }
  flush();

  for (const b of blocks) {
    b.userMessage = b.userMessage.replace(/\n$/, '');
    b.responseContent = b.responseContent.replace(/\n$/, '');
  }

  return { blocks, truncated, malformed };
}

/** Parse `in: 3.2K out: 87 total: 3.3K` into { inputTokens, outputTokens, totalTokens } or null. */
function parseTokenUsage(text) {
  if (!text) return null;
  const m = text.match(/in:\s*([\d.]+)([KM]?)\s+out:\s*([\d.]+)([KM]?)\s+total:\s*([\d.]+)([KM]?)/i);
  if (!m) return null;
  const mult = (s) => (s === 'K' ? 1000 : s === 'M' ? 1000000 : 1);
  return {
    inputTokens: Math.round(parseFloat(m[1]) * mult(m[2])),
    outputTokens: Math.round(parseFloat(m[3]) * mult(m[4])),
    totalTokens: Math.round(parseFloat(m[5]) * mult(m[6])),
  };
}

// ── Run enumeration ───────────────────────────────────────────────────────

/** Read cli-events.jsonl and produce a compact status summary for a run. */
function summarizeEvents(runDir, relBase) {
  const eventsPath = path.join(runDir, 'cli-events.jsonl');
  if (!fs.existsSync(eventsPath)) return { status: 'unknown', events: [], summary: null, error: null };

  const { items, errors } = parseJsonlFile(eventsPath, MAX_EVENTS_PER_RUN);
  const events = items.map((e) => ({
    event: e.event || null,
    timestamp: e.timestamp || null,
    message: e.message || e.summary || e.modelError || null,
    instruction: e.instruction || null,
    summary: e.summary || null,
    keyFindings: Array.isArray(e.keyFindings) ? e.keyFindings : null,
    filesChanged: Array.isArray(e.filesChanged) ? e.filesChanged : null,
    modelError: e.modelError || null,
  }));

  let status = 'running';
  const names = new Set(events.map((e) => e.event));
  if (names.has('complete')) status = 'complete';
  else if (names.has('overflow')) status = 'overflow';
  else if (names.has('error') || names.has('failed')) status = 'error';

  const completed = events.filter((e) => e.event === 'complete').pop() || null;

  return { status, events, summary: completed ? completed.summary : null, error: errors.length ? errors[0] : null };
}

/** Summarize cli-usage.jsonl for a run. */
function summarizeUsage(runDir) {
  const usagePath = path.join(runDir, 'cli-usage.jsonl');
  if (!fs.existsSync(usagePath)) return null;
  const { items } = parseJsonlFile(usagePath, 50000);
  if (items.length === 0) return null;
  let input = 0, output = 0;
  for (const u of items) {
    input += u.inputTokens || 0;
    output += u.outputTokens || 0;
  }
  return {
    requests: items.length,
    inputTokens: input,
    outputTokens: output,
    totalTokens: input + output,
    lastTimestamp: items[items.length - 1].timestamp || null,
  };
}

/** Count tool calls from cli-tool-trace.jsonl (cheap scan of file sizes + first parse). */
function summarizeToolTrace(runDir) {
  const tracePath = path.join(runDir, 'cli-tool-trace.jsonl');
  if (!fs.existsSync(tracePath)) return { count: 0, tools: [] };
  const { items } = parseJsonlFile(tracePath, 50000);
  const byTool = {};
  for (const t of items) {
    const name = t.tool || '?';
    byTool[name] = (byTool[name] || 0) + 1;
  }
  const tools = Object.entries(byTool)
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count);
  return { count: items.length, tools };
}

/** Describe one run directory (`<time>/<uuid>`). */
function describeRun(runDir, time, uuid, root) {
  const files = listFilesRecursive(runDir, root);
  const tasks = [];
  for (const name of readdirDirs(runDir)) {
    const taskDir = path.join(runDir, name);
    const taskFiles = listFilesRecursive(taskDir, taskDir).map((f) => f.name);
    tasks.push({ dir: name, files: taskFiles });
  }
  tasks.sort((a, b) => a.dir.localeCompare(b.dir));

  const events = summarizeEvents(runDir, root);
  const usage = summarizeUsage(runDir);
  const trace = summarizeToolTrace(runDir);

  return {
    time,
    uuid,
    dir: `${time}/${uuid}`,
    path: path.relative(root, runDir).replace(/\\/g, '/'),
    modified: dirModified(runDir),
    status: events.status,
    events: events.events,
    summary: events.summary,
    usage,
    toolTrace: trace,
    taskCount: tasks.length,
    tasks,
    runLevelFiles: files.filter((f) => !f.path.includes('/')),
    allFiles: files,
  };
}

/**
 * Enumerate all agent runs under a logs root, newest first.
 *
 * @param {string} root
 * @returns {{ exists: boolean, runs: object[] }}
 */
function listRuns(root) {
  if (!fs.existsSync(root)) return { exists: false, runs: [] };
  const runs = [];
  for (const time of readdirDirs(root)) {
    if (time === 'chat') continue;
    if (!/^\d{8}\.\d{6}/.test(time)) continue; // time dirs only
    const timeDir = path.join(root, time);
    for (const uuid of readdirDirs(timeDir)) {
      if (!/^[0-9a-f-]{8,}$/i.test(uuid)) continue; // uuid dirs only
      runs.push(describeRun(path.join(timeDir, uuid), time, uuid, root));
    }
  }
  runs.sort((a, b) => String(b.time).localeCompare(String(a.time)) || String(b.uuid).localeCompare(String(a.uuid)));
  return { exists: true, runs };
}

// ── Chat session enumeration ──────────────────────────────────────────────

/**
 * Enumerate LLM chat sessions under `<root>/chat`, newest first.
 *
 * @param {string} root
 * @returns {{ exists: boolean, chats: object[] }}
 */
function listChatSessions(root) {
  const chatRoot = path.join(root, 'chat');
  if (!fs.existsSync(chatRoot)) return { exists: false, chats: [] };

  const byStem = new Map();
  for (const mmdd of readdirDirs(chatRoot)) {
    const dayDir = path.join(chatRoot, mmdd);
    for (const entry of fs.readdirSync(dayDir, { withFileTypes: true })) {
      if (entry.isDirectory() || entry.name.startsWith('.')) continue;
      const m = entry.name.match(/^(chat-.*?)\.chat\.(sys|user)\.log$/i);
      if (!m) continue;
      const stem = m[1];
      const full = path.join(dayDir, entry.name);
      let stat = null;
      try { stat = fs.statSync(full); } catch (e) { /* ignore */ }
      const info = byStem.get(stem) || { stem, mmdd, dir: `chat/${mmdd}`, name: entry.name, sys: null, user: null, modified: null };
      info[entry.name.includes('.sys.') ? 'sys' : 'user'] = {
        name: entry.name,
        path: path.relative(root, full).replace(/\\/g, '/'),
        size: stat ? stat.size : 0,
        modified: stat ? stat.mtime.toISOString() : null,
      };
      if (stat && (!info.modified || stat.mtimeMs > new Date(info.modified).getTime())) {
        info.modified = stat.mtime.toISOString();
      }
      byStem.set(stem, info);
    }
  }

  const chats = [...byStem.values()];
  chats.sort((a, b) => String(b.stem).localeCompare(String(a.stem)));
  return { exists: chats.length > 0 || fs.existsSync(chatRoot), chats };
}

// ── File dispatch ─────────────────────────────────────────────────────────

/**
 * Load one file from the logs root and parse it into a viewer-friendly shape.
 *
 * @param {string} root absolute logs root
 * @param {string} relPath path relative to the root (forward slashes)
 * @param {object} [opts] { jsonlLimit, chatLimit }
 * @returns {{ kind: string, data: *, meta: object, error?: string }}
 */
function loadAgentFile(root, relPath, opts = {}) {
  const safe = String(relPath || '');
  if (safe.includes('..') || safe.includes('\0') || path.isAbsolute(safe)) {
    return { kind: 'error', data: null, meta: {}, error: 'Path traversal not allowed' };
  }
  const abs = path.resolve(root, safe);
  if (!abs.startsWith(root + path.sep)) {
    return { kind: 'error', data: null, meta: {}, error: 'Path traversal not allowed' };
  }
  if (!fs.existsSync(abs) || !fs.statSync(abs).isFile()) {
    return { kind: 'error', data: null, meta: {}, error: 'File not found' };
  }

  const base = path.basename(abs);
  const meta = { name: base, path: safe, size: fs.statSync(abs).size, modified: fs.statSync(abs).mtime.toISOString() };

  // JSONL — structured event streams (agent-trace, context, history, state-history, cli-*).
  if (base.endsWith('.jsonl')) {
    const parsed = parseJsonlFile(abs, opts.jsonlLimit);
    return {
      kind: 'jsonl',
      data: parsed.items,
      meta: { ...meta, lines: parsed.parsed, skipped: parsed.skipped, errors: parsed.errors, truncated: parsed.truncated, tooBig: parsed.tooBig },
    };
  }

  // Chat user log — request/response blocks.
  if (/\.chat\.user\.log$/i.test(base)) {
    const { text, truncated: tooBig, size } = safeReadText(abs);
    if (text === null) {
      return { kind: 'chat-user', data: [], meta: { ...meta, tooBig: true, truncated: true } };
    }
    const parsed = parseChatUserLog(text, opts.chatLimit);
    return { kind: 'chat-user', data: parsed.blocks, meta: { ...meta, malformed: parsed.malformed, truncated: parsed.truncated || tooBig } };
  }

  // Pretty JSON files (cli-prompt/*.request.json).
  if (base.endsWith('.json')) {
    const { text, truncated } = safeReadText(abs);
    let data = null, error = null;
    if (text !== null) {
      try { data = JSON.parse(text); } catch (e) { error = e.message; }
    }
    return { kind: 'json', data, meta: { ...meta, truncated, error } };
  }

  // Everything else — plain text (sys chat, process_trace, *.log ...).
  const head = readHeadLines(abs, MAX_TEXT_LINES);
  return { kind: 'text', data: head.lines, meta: { ...meta, truncated: head.truncated, tooBig: head.tooBig } };
}

module.exports = {
  MAX_READ_BYTES,
  MAX_JSONL_ITEMS,
  findRepoRoot,
  resolveAgentLogsRoot,
  parseJsonl,
  parseJsonlFile,
  parseChatUserLog,
  parseTokenUsage,
  listRuns,
  listChatSessions,
  loadAgentFile,
};
