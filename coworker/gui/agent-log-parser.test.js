#!/usr/bin/env node

/**
 * Unit tests for agent-log-parser.js — run with: node --test agent-log-parser.test.js
 */

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const {
  parseJsonl,
  parseChatUserLog,
  parseTokenUsage,
  resolveAgentLogsRoot,
  listRuns,
  listChatSessions,
  loadAgentFile,
} = require('./agent-log-parser.js');

// ── parseJsonl ────────────────────────────────────────────────────────────

test('parseJsonl parses valid objects and skips broken lines', () => {
  const text = [
    '{"a":1}',
    '{"b":2}',
    'not json at all',
    '',
    '{"c":3}',
  ].join('\n');
  const r = parseJsonl(text);
  assert.strictEqual(r.parsed, 3);
  assert.strictEqual(r.skipped, 1);
  assert.strictEqual(r.items.length, 3);
  assert.deepStrictEqual(r.items[0], { a: 1 });
  assert.strictEqual(r.errors.length, 1);
  assert.ok(r.errors[0].includes('not json at all'));
});

test('parseJsonl caps items and reports truncation', () => {
  const text = Array.from({ length: 10 }, (_, i) => JSON.stringify({ i })).join('\n');
  const r = parseJsonl(text, 3);
  assert.strictEqual(r.items.length, 3);
  assert.strictEqual(r.truncated, true);
  assert.strictEqual(r.parsed, 10);
});

// ── parseChatUserLog ──────────────────────────────────────────────────────

const SAMPLE_CHAT = `--------------------------------------------------------------------
;;REQUEST ID: 1
;;TIMESTAMP: 2026-08-25T13:30:49.956857400
;;USER MESSAGE:
1. go to https://www.amazon.com/
2. search for pens
;;RESPONSE TIMESTAMP: 2026-08-25T13:30:51.506788
;;RESPONSE STATE: TOOL_EXECUTION
;;TOKEN USAGE: in: 3.2K out: 87 total: 3.3K
;;RESPONSE CONTENT:
I'll start by reading the SKILL.md document.
--------------------------------------------------------------------
;;REQUEST ID: 2
;;TIMESTAMP: 2026-08-25T13:30:52.179589800
;;USER MESSAGE:
continue
;;RESPONSE TIMESTAMP: 2026-08-25T13:30:53.536814700
;;RESPONSE STATE: STOP
;;TOKEN USAGE: in: 4.1K out: 130 total: 4.2K
;;RESPONSE CONTENT:
Done.
--------------------------------------------------------------------
`;

test('parseChatUserLog splits request/response blocks', () => {
  const r = parseChatUserLog(SAMPLE_CHAT);
  assert.strictEqual(r.blocks.length, 2);
  assert.strictEqual(r.malformed, 0);

  const b1 = r.blocks[0];
  assert.strictEqual(b1.id, 1);
  assert.strictEqual(b1.timestamp, '2026-08-25T13:30:49.956857400');
  assert.strictEqual(b1.userMessage, '1. go to https://www.amazon.com/\n2. search for pens');
  assert.strictEqual(b1.responseTimestamp, '2026-08-25T13:30:51.506788');
  assert.strictEqual(b1.responseState, 'TOOL_EXECUTION');
  assert.strictEqual(b1.responseContent, "I'll start by reading the SKILL.md document.");

  const b2 = r.blocks[1];
  assert.strictEqual(b2.id, 2);
  assert.strictEqual(b2.responseState, 'STOP');
  assert.strictEqual(b2.responseContent, 'Done.');
});

test('parseChatUserLog extracts token usage', () => {
  const r = parseChatUserLog(SAMPLE_CHAT);
  assert.deepStrictEqual(r.blocks[0].tokenUsageParsed, {
    inputTokens: 3200,
    outputTokens: 87,
    totalTokens: 3300,
  });
});

test('parseTokenUsage handles plain numbers and K/M suffixes', () => {
  assert.deepStrictEqual(parseTokenUsage('in: 100 out: 20 total: 120'), {
    inputTokens: 100, outputTokens: 20, totalTokens: 120,
  });
  assert.deepStrictEqual(parseTokenUsage('in: 1.5K out: 2M total: 2001500'), {
    inputTokens: 1500, outputTokens: 2000000, totalTokens: 2001500,
  });
  assert.strictEqual(parseTokenUsage('garbage'), null);
});

test('parseChatUserLog tolerates empty content and stray separators', () => {
  const text = `--------------------------------------------------------------------
;;REQUEST ID: 7
;;TIMESTAMP: 2026-08-25T13:30:56.765967800
;;USER MESSAGE:

;;RESPONSE TIMESTAMP: 2026-08-25T13:30:58.040680600
;;RESPONSE STATE: TOOL_EXECUTION
;;TOKEN USAGE: in: 4.1K out: 130 total: 4.2K
;;RESPONSE CONTENT:

--------------------------------------------------------------------
`;
  const r = parseChatUserLog(text);
  assert.strictEqual(r.blocks.length, 1);
  assert.strictEqual(r.blocks[0].userMessage, '');
  assert.strictEqual(r.blocks[0].responseContent, '');
});

// ── Filesystem-backed enumeration ─────────────────────────────────────────

function makeFixtureTree() {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-logs-test-'));
  const runA = path.join(base, '20260825.133048', 'c9471e15-812d-46c4-a04e-0cf987cc8001');
  const taskA = path.join(runA, 'task-f84d68da');
  const runB = path.join(base, '20260824.100000', '11111111-2222-3333-4444-555555555555');
  fs.mkdirSync(taskA, { recursive: true });
  fs.mkdirSync(runB, { recursive: true });

  fs.writeFileSync(path.join(runA, 'cli-events.jsonl'), [
    JSON.stringify({ timestamp: '20260825.133049', event: 'run.start', instruction: 'do the thing' }),
    JSON.stringify({ timestamp: '20260825.133653', event: 'complete', summary: 'All done.', keyFindings: ['k1'], filesChanged: ['out.md'] }),
  ].join('\n') + '\n');
  fs.writeFileSync(path.join(runA, 'cli-usage.jsonl'), [
    JSON.stringify({ timestamp: '20260825.133051', requestSeq: 1, inputTokens: 100, outputTokens: 10, totalTokens: 110, finishReason: 'TOOL_EXECUTION' }),
    JSON.stringify({ timestamp: '20260825.133053', requestSeq: 2, inputTokens: 200, outputTokens: 20, totalTokens: 220, finishReason: 'STOP' }),
  ].join('\n') + '\n');
  fs.writeFileSync(path.join(runA, 'cli-tool-trace.jsonl'), [
    JSON.stringify({ timestamp: '20260825.133052', seq: 1, tool: 'b4_run', arguments: '{"args":"goto x"}', durationMs: 500, resultText: 'ok' }),
    JSON.stringify({ timestamp: '20260825.133053', seq: 2, tool: 'b4_snapshot', arguments: '{}', durationMs: 50, resultText: 'ok' }),
  ].join('\n') + '\n');
  fs.writeFileSync(path.join(taskA, 'agent-trace.jsonl'), [
    JSON.stringify({ step: 0, event: 'resolveStart', isComplete: false, message: '🚀 resolve START', timestamp: '2026-08-25T05:30:49Z' }),
    JSON.stringify({ step: 0, event: 'complete', isComplete: true, message: '#0 complete', timestamp: '2026-08-25T05:36:53Z' }),
  ].join('\n') + '\n');
  fs.writeFileSync(path.join(taskA, 'process_trace.log'), '🚩2026-08-25T05:30:49Z | step=0, event=resolveStart\n');
  fs.writeFileSync(path.join(runB, 'cli-events.jsonl'), [
    JSON.stringify({ timestamp: '20260824.100001', event: 'run.start', instruction: 'second run' }),
  ].join('\n') + '\n');

  // Chat fixture
  const chatDir = path.join(base, 'chat', '0825');
  fs.mkdirSync(chatDir, { recursive: true });
  fs.writeFileSync(path.join(chatDir, 'chat-20260825.133049.chat.sys.log'), 'You are a browser automation agent.');
  fs.writeFileSync(path.join(chatDir, 'chat-20260825.133049.chat.user.log'), SAMPLE_CHAT);

  return base;
}

test('listRuns enumerates runs newest-first with status/usage/tools', () => {
  const base = makeFixtureTree();
  try {
    const { exists, runs } = listRuns(base);
    assert.ok(exists);
    assert.strictEqual(runs.length, 2);
    // Newest first
    assert.strictEqual(runs[0].time, '20260825.133048');
    assert.strictEqual(runs[1].time, '20260824.100000');

    const a = runs[0];
    assert.strictEqual(a.status, 'complete');
    assert.strictEqual(a.summary, 'All done.');
    assert.strictEqual(a.taskCount, 1);
    assert.strictEqual(a.tasks[0].dir, 'task-f84d68da');
    assert.ok(a.tasks[0].files.includes('agent-trace.jsonl'));
    assert.deepStrictEqual(a.usage, {
      requests: 2, inputTokens: 300, outputTokens: 30, totalTokens: 330,
      lastTimestamp: '20260825.133053',
    });
    assert.strictEqual(a.toolTrace.count, 2);
    assert.deepStrictEqual(a.toolTrace.tools[0], { name: 'b4_run', count: 1 });

    const b = runs[1];
    assert.strictEqual(b.status, 'running');
    assert.strictEqual(b.taskCount, 0);
    assert.strictEqual(b.usage, null);
  } finally {
    fs.rmSync(base, { recursive: true, force: true });
  }
});

test('listChatSessions pairs sys and user logs', () => {
  const base = makeFixtureTree();
  try {
    const { exists, chats } = listChatSessions(base);
    assert.ok(exists);
    assert.strictEqual(chats.length, 1);
    const c = chats[0];
    assert.strictEqual(c.stem, 'chat-20260825.133049');
    assert.ok(c.sys, 'sys log found');
    assert.ok(c.user, 'user log found');
    assert.strictEqual(c.user.path, 'chat/0825/chat-20260825.133049.chat.user.log');
  } finally {
    fs.rmSync(base, { recursive: true, force: true });
  }
});

test('loadAgentFile dispatches jsonl / chat-user / text kinds', () => {
  const base = makeFixtureTree();
  try {
    const j = loadAgentFile(base, '20260825.133048/c9471e15-812d-46c4-a04e-0cf987cc8001/task-f84d68da/agent-trace.jsonl');
    assert.strictEqual(j.kind, 'jsonl');
    assert.strictEqual(j.data.length, 2);
    assert.strictEqual(j.data[0].event, 'resolveStart');
    assert.strictEqual(j.meta.truncated, false);

    const chat = loadAgentFile(base, 'chat/0825/chat-20260825.133049.chat.user.log');
    assert.strictEqual(chat.kind, 'chat-user');
    assert.strictEqual(chat.data.length, 2);

    const text = loadAgentFile(base, '20260825.133048/c9471e15-812d-46c4-a04e-0cf987cc8001/task-f84d68da/process_trace.log');
    assert.strictEqual(text.kind, 'text');
    assert.strictEqual(text.data.length, 1);

    const err = loadAgentFile(base, '../secret.txt');
    assert.strictEqual(err.kind, 'error');
    assert.ok(err.error.includes('traversal'));

    const missing = loadAgentFile(base, 'nope/nope.jsonl');
    assert.strictEqual(missing.kind, 'error');
  } finally {
    fs.rmSync(base, { recursive: true, force: true });
  }
});

test('resolveAgentLogsRoot honors an explicit root', () => {
  const explicit = resolveAgentLogsRoot('/tmp/whatever');
  assert.strictEqual(explicit.root, path.resolve('/tmp/whatever'));
  assert.strictEqual(explicit.source, 'explicit');
});
