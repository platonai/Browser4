/**
 * llm.js — Resilient LLM invocation wrapper for the coworker GUI.
 *
 * Features:
 *   - Retry with exponential backoff (3 attempts, 1s/2s/4s)
 *   - Circuit breaker (3 consecutive failures → 60s cooldown)
 *   - Pre-warm check (tiny prompt to verify LLM liveness before real work)
 *   - Kill escalation (SIGKILL after 5s grace period past timeout)
 *   - Heuristic fallback (stats + similarity when LLM is unavailable)
 *   - Health tracking (is the LLM currently reachable?)
 *
 * Usage:
 *   const llm = require('./llm.js');
 *   llm.init({ claudePath: 'claude', tasksRoot: '...' });
 *   const result = await llm.review(prompt);
 */

'use strict';

const { execFile, spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

// ── State ─────────────────────────────────────────────────────────────────

let _claudePath = 'claude';
let _tasksRoot = null;
let _reviewHistory = null; // populated after init

// Circuit breaker
let _consecutiveFailures = 0;
let _circuitOpenUntil = 0;      // timestamp (ms) until which circuit is open
const CIRCUIT_THRESHOLD = 3;    // failures before opening
const CIRCUIT_COOLDOWN_MS = 60000; // 60s

// Health
let _lastHealthCheck = 0;
let _lastHealthResult = null;   // { ok: bool, message: string }
const HEALTH_CACHE_MS = 10000;  // cache health for 10s

// ── Initialisation ────────────────────────────────────────────────────────

function init(opts) {
  opts = opts || {};
  _claudePath = opts.claudePath || process.env.CLAUDE_PATH || 'claude';
  _tasksRoot = opts.tasksRoot || path.resolve(__dirname, '..', 'tasks');
  // Defer review-history require to avoid circular dependency
  _reviewHistory = opts.reviewHistory || require('./review-history.js');
}

// ── Public API ────────────────────────────────────────────────────────────

/**
 * Health check: is the LLM reachable right now?
 * Cached for HEALTH_CACHE_MS to avoid spamming.
 */
function checkHealth() {
  return new Promise((resolve) => {
    const now = Date.now();
    if (_lastHealthResult && (now - _lastHealthCheck) < HEALTH_CACHE_MS) {
      resolve(_lastHealthResult);
      return;
    }

    // Try a trivial prompt
    const child = execFile(_claudePath, ['-p', 'say ok'], {
      timeout: 10000,
      maxBuffer: 1024,
      env: { ...process.env },
    }, (err, stdout) => {
      _lastHealthCheck = Date.now();
      if (err) {
        _lastHealthResult = { ok: false, message: err.message, circuitOpen: isCircuitOpen() };
      } else {
        _lastHealthResult = { ok: true, message: 'LLM reachable', circuitOpen: false };
      }
      resolve(_lastHealthResult);
    });

    // Kill escalation for health check too
    _attachKillEscalation(child, 10000, 5000);
  });
}

/** Force reset health cache (e.g., after a failure). */
function invalidateHealth() {
  _lastHealthResult = null;
  _lastHealthCheck = 0;
}

/** Is the circuit breaker currently open? */
function isCircuitOpen() {
  return Date.now() < _circuitOpenUntil;
}

/** Get circuit breaker status for monitoring. */
function getCircuitStatus() {
  return {
    open: isCircuitOpen(),
    consecutiveFailures: _consecutiveFailures,
    cooldownRemaining: isCircuitOpen() ? Math.ceil((_circuitOpenUntil - Date.now()) / 1000) : 0,
  };
}

/**
 * Send a prompt to the LLM with retry, circuit breaker, and pre-warm.
 *
 * @param {string} prompt - the full prompt text
 * @param {object} opts
 * @param {number} opts.timeout - ms before timeout (default 60000)
 * @param {number} opts.retries - max retry attempts (default 3)
 * @param {boolean} opts.skipPreWarm - skip the pre-warm check (default false)
 * @param {function} opts.heuristic - fallback function () => result if LLM unavailable
 * @returns {Promise<{stdout: string, stderr: string, heuristic: boolean}>}
 */
function sendPrompt(prompt, opts) {
  opts = opts || {};
  const timeout = opts.timeout || 60000;
  const maxRetries = opts.retries || 3;
  const skipPreWarm = opts.skipPreWarm || false;

  return _sendWithRetry(prompt, timeout, maxRetries, skipPreWarm, opts.heuristic || null);
}

// ── Internal: retry loop ──────────────────────────────────────────────────

async function _sendWithRetry(prompt, timeout, maxRetries, skipPreWarm, heuristic) {
  // Circuit breaker check
  if (isCircuitOpen()) {
    const remaining = Math.ceil((_circuitOpenUntil - Date.now()) / 1000);
    if (heuristic) {
      console.log(`[llm] Circuit open (${remaining}s remaining) — using heuristic fallback`);
      const h = heuristic();
      return { stdout: '', stderr: '', heuristic: true, heuristicResult: h };
    }
    throw new Error(`LLM circuit breaker open — try again in ${remaining}s`);
  }

  let lastError = null;

  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      // Pre-warm on first attempt only
      if (attempt === 1 && !skipPreWarm) {
        const warm = await _preWarm();
        if (!warm.ok && heuristic) {
          console.log(`[llm] Pre-warm failed: ${warm.message} — using heuristic fallback`);
          const h = heuristic();
          return { stdout: '', stderr: '', heuristic: true, heuristicResult: h };
        }
      }

      const result = await _invokeClaude(prompt, timeout);

      // Success — reset circuit breaker
      _consecutiveFailures = 0;
      _circuitOpenUntil = 0;
      invalidateHealth();
      return { ...result, heuristic: false };

    } catch (err) {
      lastError = err;
      _consecutiveFailures++;

      console.log(`[llm] Attempt ${attempt}/${maxRetries} failed: ${err.message}`);

      // Open circuit if threshold reached
      if (_consecutiveFailures >= CIRCUIT_THRESHOLD) {
        _circuitOpenUntil = Date.now() + CIRCUIT_COOLDOWN_MS;
        console.log(`[llm] Circuit breaker OPEN for ${CIRCUIT_COOLDOWN_MS / 1000}s`);
      }

      // Last attempt failed — try heuristic fallback
      if (attempt >= maxRetries && heuristic) {
        console.log(`[llm] All ${maxRetries} attempts failed — using heuristic fallback`);
        const h = heuristic();
        return { stdout: '', stderr: '', heuristic: true, heuristicResult: h };
      }

      // Wait before retry (exponential backoff: 1s, 2s, 4s)
      if (attempt < maxRetries) {
        const delay = Math.min(1000 * Math.pow(2, attempt - 1), 10000);
        await _sleep(delay);
      }
    }
  }

  throw lastError || new Error('LLM invocation failed');
}

// ── Internal: single invocation ───────────────────────────────────────────

function _invokeClaude(prompt, timeout) {
  return new Promise((resolve, reject) => {
    const child = execFile(_claudePath, ['-p', prompt], {
      timeout: timeout,
      maxBuffer: 2 * 1024 * 1024,
      env: { ...process.env },
    }, (err, stdout, stderr) => {
      if (err) {
        if (err.killed) {
          reject(new Error(`LLM request timed out after ${timeout / 1000}s`));
        } else {
          reject(new Error(`LLM request failed: ${err.message}`));
        }
        return;
      }
      resolve({ stdout, stderr, heuristic: false });
    });

    // Kill escalation: if the process doesn't die after timeout, force-kill it
    _attachKillEscalation(child, timeout, 5000);
  });
}

// ── Internal: pre-warm ────────────────────────────────────────────────────

function _preWarm() {
  return new Promise((resolve) => {
    const child = execFile(_claudePath, ['-p', 'say ok'], {
      timeout: 15000,
      maxBuffer: 1024,
      env: { ...process.env },
    }, (err, stdout) => {
      if (err) {
        resolve({ ok: false, message: err.message });
      } else {
        resolve({ ok: true, message: 'LLM warm' });
      }
    });

    _attachKillEscalation(child, 15000, 5000);
  });
}

// ── Internal: kill escalation ─────────────────────────────────────────────

function _attachKillEscalation(child, timeoutMs, graceMs) {
  const totalWait = timeoutMs + graceMs;
  const timer = setTimeout(() => {
    try {
      if (child.exitCode === null && !child.killed) {
        console.log(`[llm] Kill escalation: sending SIGKILL after ${totalWait / 1000}s`);
        child.kill('SIGKILL');
      }
    } catch (e) {
      // Process already gone — fine
    }
  }, totalWait);

  // Don't let the timer keep the process alive
  if (timer.unref) timer.unref();
}

// ── Utility ───────────────────────────────────────────────────────────────

function _sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// ── Heuristic fallback ────────────────────────────────────────────────────

/**
 * Generate a heuristic review decision using stats + similarity.
 * Used when the LLM is unavailable. Marks decisions with a clear
 * "[Heuristic]" prefix so the user knows it's not an AI review.
 *
 * @param {object} issue - { title, severity, category, sections, ... }
 * @returns {{ decision: string, notes: string, heuristic: boolean }}
 */
function heuristicDecision(issue) {
  if (!_reviewHistory) {
    return { decision: 'DEFER', notes: '[Heuristic] No review history available — defaulting to DEFER.', heuristic: true };
  }

  const title = issue.title || '';
  const severity = issue.severity || 'Medium';
  const category = issue.category || '';

  // Rule 1: Similar past issues — adopt the most common decision for similar titles
  const similar = _reviewHistory.findSimilarIssues(title, 0.30);
  if (similar.length > 0) {
    // Count decisions among similar issues
    const decCounts = {};
    for (const s of similar) {
      if (s.issue.decision) {
        decCounts[s.issue.decision] = (decCounts[s.issue.decision] || 0) + 1;
      }
    }
    const top = Object.entries(decCounts).sort((a, b) => b[1] - a[1])[0];
    if (top && top[1] >= 1) {
      return {
        decision: top[0],
        notes: `[Heuristic] Based on ${similar.length} similar past issue(s) (best match: "${similar[0].issue.title.substring(0, 60)}" was ${similar[0].issue.decision}). LLM unavailable — review this decision carefully.`,
        heuristic: true,
      };
    }
  }

  // Rule 2: Severity + Category heuristics from 126 reviewed issues
  const stats = _reviewHistory.getStats();

  // Critical → almost always ACCEPT
  if (severity === 'Critical') {
    return {
      decision: 'ACCEPT',
      notes: '[Heuristic] Critical-severity issues are almost always ACCEPTed. LLM unavailable — verify.',
      heuristic: true,
    };
  }

  // Reliability + High → likely ACCEPT (~43% in past reviews)
  if (category.toLowerCase().indexOf('reliability') >= 0 && (severity === 'High' || severity === 'Critical')) {
    return {
      decision: 'ACCEPT',
      notes: '[Heuristic] High-severity reliability issues have ~63% ACCEPT rate. LLM unavailable — verify.',
      heuristic: true,
    };
  }

  // Documentation + Low → likely WONTFIX
  if (category.toLowerCase().indexOf('documentation') >= 0 && severity === 'Low') {
    return {
      decision: 'WONTFIX',
      notes: '[Heuristic] Low-severity documentation issues are often WONTFIX (human readability concerns). LLM unavailable — verify.',
      heuristic: true,
    };
  }

  // Low severity + UX/Discoverability → likely DEFER
  if (severity === 'Low' && (category.toLowerCase().indexOf('ux') >= 0 || category.toLowerCase().indexOf('discoverability') >= 0)) {
    return {
      decision: 'DEFER',
      notes: '[Heuristic] Low-severity UX/discoverability issues are often DEFERred. LLM unavailable — verify.',
      heuristic: true,
    };
  }

  // Default: DEFER — safest option that doesn't lose data
  return {
    decision: 'DEFER',
    notes: '[Heuristic] No strong signal from past reviews — defaulting to DEFER (safest). LLM unavailable — review carefully.',
    heuristic: true,
  };
}

// ── Exports ───────────────────────────────────────────────────────────────

module.exports = {
  init,
  checkHealth,
  invalidateHealth,
  isCircuitOpen,
  getCircuitStatus,
  sendPrompt,
  heuristicDecision,
};
