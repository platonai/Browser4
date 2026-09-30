#!/usr/bin/env node

/**
 * Unit tests for log-parser.js — run with: node --test log-parser.test.js
 */

const test = require('node:test');
const assert = require('node:assert');
const { parseLogLine, parseLogLines, KNOWN_LEVELS } = require('./log-parser.js');

test('parses the standard pulsar line format (ts level [thread] logger - message)', () => {
  const line = '2026-08-25 01:19:45.227  INFO [-worker-7] a.p.p.a.a.RobustBrowserAgent - ✅ task.complete sid=abc complete=true';
  const entry = parseLogLine(line);
  assert.ok(entry, 'line must parse');
  assert.strictEqual(entry.ts, '2026-08-25 01:19:45.227');
  assert.strictEqual(entry.level, 'INFO');
  assert.strictEqual(entry.thread, '-worker-7');
  assert.strictEqual(entry.logger, 'a.p.p.a.a.RobustBrowserAgent');
  assert.strictEqual(entry.message, '✅ task.complete sid=abc complete=true');
  assert.deepStrictEqual(entry.continuation, []);
});

test('parses lines with a T separator and millis comma (log4j style)', () => {
  const line = '2026-08-25T01:19:45,227 ERROR [main] com.example.Foo - boom';
  const entry = parseLogLine(line);
  assert.ok(entry);
  assert.strictEqual(entry.ts, '2026-08-25T01:19:45,227');
  assert.strictEqual(entry.level, 'ERROR');
  assert.strictEqual(entry.logger, 'com.example.Foo');
});

test('parses thread-only and bare formats', () => {
  const threadOnly = parseLogLine('2026-08-25 01:19:45.227  WARN [-worker-1] plain message without logger');
  assert.ok(threadOnly);
  assert.strictEqual(threadOnly.level, 'WARN');
  assert.strictEqual(threadOnly.thread, '-worker-1');
  assert.strictEqual(threadOnly.logger, '');
  assert.strictEqual(threadOnly.message, 'plain message without logger');

  const bare = parseLogLine('2026-08-25 01:19:45.227  DEBUG plain message without thread');
  assert.ok(bare);
  assert.strictEqual(bare.level, 'DEBUG');
  assert.strictEqual(bare.thread, '');
  assert.strictEqual(bare.message, 'plain message without thread');
});

test('parses time-only timestamps with thread-before-level (pulsar.log format)', () => {
  const line = '01:08:47.811 [7923432-55] INFO  a.p.p.r.m.c.MCPToolController - Calling tool: page_url --sessionId=f6b41ac2';
  const entry = parseLogLine(line);
  assert.ok(entry, 'pulsar.log line must parse');
  assert.strictEqual(entry.ts, '01:08:47.811');
  assert.strictEqual(entry.level, 'INFO');
  assert.strictEqual(entry.thread, '7923432-55');
  assert.strictEqual(entry.logger, 'a.p.p.r.m.c.MCPToolController');
  assert.strictEqual(entry.message, 'Calling tool: page_url --sessionId=f6b41ac2');
});

test('parses time-only with thread-before-level and no logger', () => {
  const entry = parseLogLine('01:08:47.811 [-worker-7] WARN plain message');
  assert.ok(entry);
  assert.strictEqual(entry.ts, '01:08:47.811');
  assert.strictEqual(entry.level, 'WARN');
  assert.strictEqual(entry.thread, '-worker-7');
  assert.strictEqual(entry.logger, '');
  assert.strictEqual(entry.message, 'plain message');
});

test('returns null for non-entry lines', () => {
  assert.strictEqual(parseLogLine('   at com.example.Foo.run(Foo.java:42)'), null);
  assert.strictEqual(parseLogLine('Caused by: java.lang.NullPointerException'), null);
  assert.strictEqual(parseLogLine('──── git log ────'), null);
  assert.strictEqual(parseLogLine(''), null);
  assert.strictEqual(parseLogLine('   '), null);
});

test('attaches continuation lines to the previous entry (stack traces)', () => {
  const lines = [
    '2026-08-25 01:19:45.227  ERROR [main] com.example.Foo - operation failed',
    'java.lang.NullPointerException: boom',
    '\tat com.example.Foo.run(Foo.java:42)',
    '\tat com.example.Main.main(Main.java:10)',
    'Caused by: java.io.IOException: nope',
    '2026-08-25 01:19:46.000  INFO [main] com.example.Foo - recovered',
  ];
  const { entries } = parseLogLines(lines);
  assert.strictEqual(entries.length, 2);
  const failed = entries[0];
  assert.strictEqual(failed.level, 'ERROR');
  assert.strictEqual(failed.continuation.length, 4);
  assert.ok(failed.text.includes('java.lang.NullPointerException: boom'));
  assert.ok(failed.text.includes('Caused by: java.io.IOException: nope'));
  assert.strictEqual(entries[1].message, 'recovered');
  assert.strictEqual(entries[1].continuation.length, 0);
});

test('computes per-level stats over the parsed window', () => {
  const lines = [
    '2026-08-25 01:00:00.000  INFO [t] a.b.C - one',
    '2026-08-25 01:00:01.000  WARN [t] a.b.C - two',
    '2026-08-25 01:00:02.000  ERROR [t] a.b.C - three',
    '2026-08-25 01:00:03.000  ERROR [t] a.b.C - four',
    '2026-08-25 01:00:04.000  DEBUG [t] a.b.C - five',
    '   continuation of five',
  ];
  const { stats, totalParsed } = parseLogLines(lines);
  assert.strictEqual(totalParsed, 5);
  assert.deepStrictEqual(stats.byLevel, { INFO: 1, WARN: 1, ERROR: 2, DEBUG: 1 });
  assert.strictEqual(stats.continuations, 1);
  assert.strictEqual(stats.total, 5);
});

test('filters by level and query before returning', () => {
  const lines = [
    '2026-08-25 01:00:00.000  INFO [t] a.b.C - started job 42',
    '2026-08-25 01:00:01.000  WARN [t] a.b.C - slow response',
    '2026-08-25 01:00:02.000  ERROR [t] a.b.C - job 42 failed',
  ];
  const warnOnly = parseLogLines(lines, { levels: ['WARN'] });
  assert.strictEqual(warnOnly.entries.length, 1);
  assert.strictEqual(warnOnly.entries[0].level, 'WARN');

  const query = parseLogLines(lines, { query: 'job 42' });
  assert.strictEqual(query.entries.length, 2);
  assert.ok(query.entries.every((e) => e.text.includes('job 42')));

  const both = parseLogLines(lines, { levels: ['ERROR'], query: 'job 42' });
  assert.strictEqual(both.entries.length, 1);
  assert.strictEqual(both.entries[0].message, 'job 42 failed');
});

test('caps returned entries keeping the newest tail', () => {
  const lines = [];
  for (let i = 0; i < 10; i++) {
    lines.push(`2026-08-25 01:00:0${i}.000  INFO [t] a.b.C - message ${i}`);
  }
  const { entries, truncated } = parseLogLines(lines, { maxEntries: 3 });
  assert.strictEqual(truncated, true);
  assert.strictEqual(entries.length, 3);
  assert.strictEqual(entries[0].message, 'message 7');
  assert.strictEqual(entries[2].message, 'message 9');
});

test('carries label/file metadata and line numbers', () => {
  const raw = [
    { line: '2026-08-25 01:00:00.000  ERROR [t] a.b.C - bad', label: 'pulsar', file: 'pulsar.log' },
    { line: '2026-08-25 01:00:01.000  INFO [t] a.b.C - fine', label: 'server', file: 'pulsar.s.log' },
  ];
  const { entries, files } = parseLogLines(raw);
  assert.strictEqual(files.length, 2);
  assert.strictEqual(entries[0].label, 'pulsar');
  assert.strictEqual(entries[0].file, 'pulsar.log');
  assert.strictEqual(entries[0].lineNo, 1);
  assert.strictEqual(entries[1].lineNo, 2);
});

test('KNOWN_LEVELS is exported for UI use', () => {
  assert.deepStrictEqual(KNOWN_LEVELS, ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL', 'OFF']);
});
