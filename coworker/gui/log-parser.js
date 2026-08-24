#!/usr/bin/env node

/**
 * Log parser for the Coworker GUI log reader page.
 *
 * Turns raw log text (the standard Browser4/log4j2 line format) into
 * structured entries: timestamp, level, thread, logger and message, with
 * multi-line continuations (stack traces, wrapped messages) attached to their
 * owning entry. Designed as a standalone module so it can be unit-tested
 * without booting the express server.
 *
 * Supported line shapes (all timestamp-prefixed):
 *   2026-08-25 01:19:45.227  INFO [-worker-7] a.p.p.a.a.RobustBrowserAgent - ✅ task.complete ...
 *   2026-08-25 01:19:45.227  ERROR [main] Some logger - boom
 *   2026-08-25 01:19:45.227  WARN [-worker-1] plain message without logger
 *   2026-08-25 01:19:45.227  DEBUG plain message without thread/logger
 *
 * Any non-matching non-blank line is treated as a continuation of the previous
 * entry (typical for stack traces and multi-line payloads).
 */

const KNOWN_LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL', 'OFF'];

// Timestamp: full date `2026-08-25 01:19:45.227` (space or T separator, dot or
// comma millis) OR time-only `01:19:45.227` (log4j2 %d{HH:mm:ss.SSS} pattern).
// Capturing group 1 — every pattern reads it as `ts`.
const TS = '(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?|\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?)';
const LEVEL = '(TRACE|DEBUG|INFO|WARN|ERROR|FATAL|OFF)';
const LOGGER = '([\\w.$]+(?:\\.[\\w$]+)*)';
const THREAD = '\\[([^\\]]*)\\]';

// Pattern family (all start with a timestamp):
//   A: ts [thread] LEVEL logger - message        ← pulsar.log (log4j2 %d{HH:mm:ss.SSS} [%thread] %level %logger - %msg)
//   B: ts [thread] LEVEL message
//   C: ts LEVEL [thread] logger - message        ← example JVM logs (date-prefixed, level-first)
//   D: ts LEVEL [thread] message
//   F: ts LEVEL logger - message
//   E: ts LEVEL message
function pattern(body) {
  return new RegExp(`^${TS}${body}$`);
}
const PATTERNS = [
  { re: pattern(`\\s+${THREAD}\\s+${LEVEL}\\s+${LOGGER}\\s+-\\s+(.*)`), thread: 2, level: 3, logger: 4, message: 5 },
  { re: pattern(`\\s+${THREAD}\\s+${LEVEL}\\s+(.*)`), thread: 2, level: 3, message: 4 },
  { re: pattern(`\\s+${LEVEL}\\s+${THREAD}\\s+${LOGGER}\\s+-\\s+(.*)`), level: 2, thread: 3, logger: 4, message: 5 },
  { re: pattern(`\\s+${LEVEL}\\s+${THREAD}\\s+(.*)`), level: 2, thread: 3, message: 4 },
  { re: pattern(`\\s+${LEVEL}\\s+${LOGGER}\\s+-\\s+(.*)`), level: 2, logger: 3, message: 4 },
  { re: pattern(`\\s+${LEVEL}\\s+(.*)`), level: 2, message: 3 },
];

/**
 * Parse a single log line into a structured entry, or null when the line does
 * not start a new entry (i.e. it is a continuation of the previous one).
 *
 * @param {string} line raw log line
 * @returns {object|null} entry or null
 */
function parseLogLine(line) {
  if (typeof line !== 'string') return null;

  for (const p of PATTERNS) {
    const m = p.re.exec(line);
    if (!m) continue;
    return {
      ts: m[1],
      level: m[p.level],
      thread: p.thread ? (m[p.thread] || '') : '',
      logger: p.logger ? (m[p.logger] || '') : '',
      message: m[p.message],
      text: m[p.message],
      continuation: [],
      raw: line,
    };
  }
  return null;
}

/**
 * Parse a batch of raw lines into structured entries with stats.
 *
 * @param {Array<string|object>} rawLines lines, or objects { line, label, file }
 * @param {object} opts
 * @param {string[]} [opts.levels] keep only these levels (empty = all)
 * @param {string} [opts.query] case-insensitive substring filter on full text
 * @param {number} [opts.maxEntries=5000] cap on returned entries (keeps the newest)
 * @returns {{entries: object[], stats: object, totalParsed: number, files: string[]}}
 */
function parseLogLines(rawLines, opts = {}) {
  const entries = [];
  const stats = { total: 0, byLevel: {}, continuations: 0 };
  const files = new Set();
  let current = null;

  rawLines.forEach((item, idx) => {
    const line = typeof item === 'string' ? item : (item && item.line) || '';
    const label = typeof item === 'string' ? '' : (item && item.label) || '';
    const file = typeof item === 'string' ? '' : (item && item.file) || '';
    if (file) files.add(file);

    const entry = parseLogLine(line);
    if (entry) {
      entry.lineNo = idx + 1;
      entry.label = label;
      entry.file = file;
      current = entry;
      entries.push(entry);
      stats.total++;
      stats.byLevel[entry.level] = (stats.byLevel[entry.level] || 0) + 1;
    } else if (current && line.trim() !== '') {
      // Continuation line (stack trace frame, wrapped message, ...)
      current.continuation.push(line);
      current.text += '\n' + line;
      stats.continuations++;
    }
  });

  let filtered = entries;
  if (opts.levels && opts.levels.length > 0) {
    const keep = new Set(opts.levels.map((l) => l.toUpperCase()));
    filtered = filtered.filter((e) => keep.has(e.level));
  }
  if (opts.query && String(opts.query).trim()) {
    const q = String(opts.query).toLowerCase();
    filtered = filtered.filter((e) => e.text.toLowerCase().includes(q));
  }

  const cap = Math.max(parseInt(opts.maxEntries, 10) || 5000, 1);
  const truncated = filtered.length > cap;
  if (truncated) filtered = filtered.slice(-cap); // keep the newest tail

  return {
    entries: filtered,
    stats,
    totalParsed: stats.total,
    files: [...files],
    truncated,
  };
}

module.exports = {
  KNOWN_LEVELS,
  parseLogLine,
  parseLogLines,
};
