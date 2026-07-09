/**
 * llm.js — Resilient, provider-configurable LLM invocation wrapper.
 *
 * Supports multiple AI providers following the same pattern as the
 * PowerShell agent.ps1 / config.psd1 abstraction:
 *
 *   claude   → `claude -p <prompt> [baseArgs]`
 *   copilot  → `gh copilot suggest -p <prompt> [baseArgs]`
 *   openai   → `<binary> -p <prompt> [baseArgs]` (generic)
 *   custom   → `<binary> [baseArgs] <prompt>` (fully configurable)
 *
 * Provider detection order:
 *   1. LLM_PROVIDER env var (explicit selection)
 *   2. config.psd1 file (same file the PowerShell scripts read)
 *   3. Default: 'claude' if config.psd1 has CLAUDE key, otherwise 'copilot'
 *
 * Features (unchanged from previous version):
 *   - Retry with exponential backoff (3 attempts, 1s/2s/4s)
 *   - Circuit breaker (3 consecutive failures → 60s cooldown)
 *   - Pre-warm check (tiny prompt to verify LLM liveness)
 *   - Kill escalation (SIGKILL after 5s grace period past timeout)
 *   - Heuristic fallback (stats + similarity when LLM is unavailable)
 *   - Health tracking with caching (10s)
 */

'use strict';

const { execFile } = require('child_process');
const path = require('path');
const fs = require('fs');

// ── Provider definitions ──────────────────────────────────────────────────

/**
 * Each provider defines:
 *   - binary: default executable name
 *   - promptFlag: CLI flag for the prompt text (e.g., '-p')
 *   - promptPosition: 'flag' (binary flag prompt) | 'stdin' | 'last'
 *   - baseArgs: default base arguments (overridden by config/env)
 *   - envVars: environment variables needed by this provider
 */
const PROVIDER_DEFS = {
  claude: {
    binary: 'claude',
    promptFlag: '-p',
    promptPosition: 'flag',    // claude -p "<prompt>" [args]
    baseArgs: ['--dangerously-skip-permissions'],
    envVars: [],
  },
  copilot: {
    binary: 'gh',
    promptFlag: '-p',
    promptPosition: 'separator', // gh copilot [args] -- -p "<prompt>"
    promptSeparator: '--',
    baseArgs: ['copilot'],
    envVars: [],
  },
  openai: {
    binary: 'openai',
    promptFlag: '-p',
    promptPosition: 'flag',    // openai -p "<prompt>" [args]
    baseArgs: [],
    envVars: ['OPENAI_API_KEY'],
  },
  custom: {
    binary: 'claude',
    promptFlag: '-p',
    promptPosition: 'flag',    // <binary> -p "<prompt>" [args]
    baseArgs: [],
    envVars: [],
  },
};

// ── State ─────────────────────────────────────────────────────────────────

let _provider = null;       // { type, binary, promptFlag, promptPosition, baseArgs, envVars }
let _tasksRoot = null;
let _reviewHistory = null;

// Circuit breaker
let _consecutiveFailures = 0;
let _circuitOpenUntil = 0;
const CIRCUIT_THRESHOLD = 3;
const CIRCUIT_COOLDOWN_MS = 60000;

// Health
let _lastHealthCheck = 0;
let _lastHealthResult = null;
const HEALTH_CACHE_MS = 10000;

// ── Config file parsing ───────────────────────────────────────────────────

/**
 * Parse a PowerShell .psd1 config file to detect the active AI provider.
 * Follows the same logic as config.ps1: if CLAUDE key exists → 'claude',
 * otherwise → 'copilot'. Extracts the array values for base arguments.
 *
 * Returns: { type, binary, baseArgs } or null if file not found
 */
function _parsePsd1Config(configPath) {
  try {
    if (!fs.existsSync(configPath)) return null;
    const content = fs.readFileSync(configPath, 'utf-8');

    // Check for active CLAUDE block (uncommented)
    const claudeMatch = content.match(/^\s*CLAUDE\s*=\s*@\(([\s\S]*?)\)/m);
    if (claudeMatch) {
      const args = _parsePsd1Array(claudeMatch[1]);
      const binary = args.length > 0 ? args[0] : 'claude';
      const baseArgs = args.slice(1);
      return { type: 'claude', binary, baseArgs };
    }

    // Check for active COPILOT block (uncommented)
    const copilotMatch = content.match(/^\s*COPILOT\s*=\s*@\(([\s\S]*?)\)/m);
    if (copilotMatch) {
      const args = _parsePsd1Array(copilotMatch[1]);
      const binary = args.length > 0 ? args[0] : 'gh';
      const baseArgs = args.slice(1);
      return { type: 'copilot', binary, baseArgs };
    }

    return null;
  } catch (e) {
    console.error('[llm] Failed to parse config.psd1:', e.message);
    return null;
  }
}

/**
 * Parse the contents of a PowerShell @(...) array.
 * Handles quoted strings with single quotes.
 */
function _parsePsd1Array(text) {
  const result = [];
  const regex = /'([^']*)'/g;
  let match;
  while ((match = regex.exec(text)) !== null) {
    result.push(match[1]);
  }
  return result;
}

/**
 * Find the config.psd1 file relative to the GUI directory.
 * Path: ../../scripts/config.psd1 from coworker/gui/
 */
function _findConfigPath() {
  // Try relative to this file first
  const fromGui = path.resolve(__dirname, '..', 'scripts', 'config.psd1');
  if (fs.existsSync(fromGui)) return fromGui;

  // Try relative to tasks root
  if (_tasksRoot) {
    const fromTasks = path.resolve(_tasksRoot, '..', 'scripts', 'config.psd1');
    if (fs.existsSync(fromTasks)) return fromTasks;
  }

  return null;
}

// ── Initialisation ────────────────────────────────────────────────────────

/**
 * Initialise the LLM wrapper with provider configuration.
 *
 * Provider detection order:
 *   1. opts.provider — explicit provider name
 *   2. opts.binary — explicit binary path
 *   3. LLM_PROVIDER env var — explicit provider name
 *   4. LLM_PATH env var — explicit binary path
 *   5. LLM_ARGS env var — space-separated base arguments
 *   6. config.psd1 file — same file the PowerShell scripts read
 *   7. Default: 'claude'
 *
 * @param {object} opts
 * @param {string} [opts.provider]   - provider type: 'claude'|'copilot'|'openai'|'custom'
 * @param {string} [opts.binary]     - path to LLM binary (overrides provider default)
 * @param {string[]} [opts.baseArgs] - additional base arguments
 * @param {string} [opts.tasksRoot]  - path to coworker/tasks/ (for finding config.psd1)
 * @param {object} [opts.reviewHistory] - review-history module instance
 */
function init(opts) {
  opts = opts || {};
  _tasksRoot = opts.tasksRoot || path.resolve(__dirname, '..', 'tasks');
  _reviewHistory = opts.reviewHistory || null;

  let providerType = null;
  let binary = null;
  let baseArgs = null;

  // 1. Explicit provider type
  if (opts.provider && PROVIDER_DEFS[opts.provider]) {
    providerType = opts.provider;
  }

  // 2. Explicit binary
  if (opts.binary) {
    binary = opts.binary;
  }

  // 3. Explicit base args
  if (opts.baseArgs) {
    baseArgs = opts.baseArgs;
  }

  // 4. Env var: LLM_PROVIDER
  if (!providerType && process.env.LLM_PROVIDER) {
    const envProv = process.env.LLM_PROVIDER.toLowerCase();
    if (PROVIDER_DEFS[envProv]) {
      providerType = envProv;
    } else {
      // Treat as custom provider type
      providerType = 'custom';
    }
  }

  // 5. Env var: LLM_PATH (binary override)
  if (!binary && process.env.LLM_PATH) {
    binary = process.env.LLM_PATH;
  }

  // 6. Env var: LLM_ARGS (base args override)
  if (!baseArgs && process.env.LLM_ARGS) {
    baseArgs = process.env.LLM_ARGS.split(/\s+/).filter(Boolean);
  }

  // 7. Env var: CLAUDE_PATH (legacy — backward compatibility)
  if (!binary && process.env.CLAUDE_PATH && !providerType) {
    binary = process.env.CLAUDE_PATH;
    providerType = 'claude';
  }

  // 8. config.psd1 file — only when no explicit provider/binary/args
  //    were set (i.e., use the shared config as defaults)
  const hasExplicitConfig = !!(opts.provider || opts.binary || opts.baseArgs ||
                                process.env.LLM_PROVIDER || process.env.LLM_PATH ||
                                process.env.LLM_ARGS || process.env.CLAUDE_PATH);
  if (!hasExplicitConfig) {
    const configPath = _findConfigPath();
    const psd1Config = configPath ? _parsePsd1Config(configPath) : null;

    if (psd1Config) {
      if (!providerType) providerType = psd1Config.type;
      if (!binary) binary = psd1Config.binary;
      if (!baseArgs) baseArgs = psd1Config.baseArgs;
    }
  }

  // 9. Default fallback
  if (!providerType) providerType = 'claude';

  // Build provider config — use provider definition defaults for
  // binary/baseArgs when not explicitly overridden
  const def = PROVIDER_DEFS[providerType] || PROVIDER_DEFS.custom;
  _provider = {
    type: providerType,
    binary: binary || def.binary,
    promptFlag: def.promptFlag,
    promptPosition: def.promptPosition,
    baseArgs: baseArgs !== null ? baseArgs : [...def.baseArgs],
    envVars: def.envVars,
  };

  console.error(`[llm] Provider: ${_provider.type} → ${_provider.binary} ${_provider.baseArgs.join(' ')}`);
}

// ── Public API ────────────────────────────────────────────────────────────

/**
 * Health check: is the LLM reachable right now?
 */
function checkHealth() {
  return new Promise((resolve) => {
    const now = Date.now();
    if (_lastHealthResult && (now - _lastHealthCheck) < HEALTH_CACHE_MS) {
      resolve(_lastHealthResult);
      return;
    }

    _ensureProvider();
    const args = _buildArgs('say ok');
    const env = _buildEnv();

    const child = execFile(_provider.binary, args, {
      timeout: 10000,
      maxBuffer: 1024,
      env: env,
    }, (err, stdout) => {
      _lastHealthCheck = Date.now();
      if (err) {
        _lastHealthResult = { ok: false, message: err.message, circuitOpen: isCircuitOpen(), provider: _provider.type };
      } else {
        _lastHealthResult = { ok: true, message: 'LLM reachable', circuitOpen: false, provider: _provider.type };
      }
      resolve(_lastHealthResult);
    });

    _attachKillEscalation(child, 10000, 5000);
  });
}

function invalidateHealth() {
  _lastHealthResult = null;
  _lastHealthCheck = 0;
}

function isCircuitOpen() {
  return Date.now() < _circuitOpenUntil;
}

function getCircuitStatus() {
  return {
    open: isCircuitOpen(),
    consecutiveFailures: _consecutiveFailures,
    cooldownRemaining: isCircuitOpen() ? Math.ceil((_circuitOpenUntil - Date.now()) / 1000) : 0,
  };
}

/** Get the active provider info for diagnostics. */
function getProviderInfo() {
  _ensureProvider();
  return {
    type: _provider.type,
    binary: _provider.binary,
    baseArgs: _provider.baseArgs,
    promptFlag: _provider.promptFlag,
  };
}

/**
 * Send a prompt to the LLM with retry, circuit breaker, and pre-warm.
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
      if (attempt === 1 && !skipPreWarm) {
        const warm = await _preWarm();
        if (!warm.ok && heuristic) {
          console.log(`[llm] Pre-warm failed: ${warm.message} — using heuristic fallback`);
          const h = heuristic();
          return { stdout: '', stderr: '', heuristic: true, heuristicResult: h };
        }
      }

      const result = await _invoke(prompt, timeout);

      _consecutiveFailures = 0;
      _circuitOpenUntil = 0;
      invalidateHealth();
      return { ...result, heuristic: false };

    } catch (err) {
      lastError = err;
      _consecutiveFailures++;

      console.log(`[llm] Attempt ${attempt}/${maxRetries} failed: ${err.message}`);

      if (_consecutiveFailures >= CIRCUIT_THRESHOLD) {
        _circuitOpenUntil = Date.now() + CIRCUIT_COOLDOWN_MS;
        console.log(`[llm] Circuit breaker OPEN for ${CIRCUIT_COOLDOWN_MS / 1000}s`);
      }

      if (attempt >= maxRetries && heuristic) {
        console.log(`[llm] All ${maxRetries} attempts failed — using heuristic fallback`);
        const h = heuristic();
        return { stdout: '', stderr: '', heuristic: true, heuristicResult: h };
      }

      if (attempt < maxRetries) {
        const delay = Math.min(1000 * Math.pow(2, attempt - 1), 10000);
        await _sleep(delay);
      }
    }
  }

  throw lastError || new Error('LLM invocation failed');
}

// ── Internal: argument construction ───────────────────────────────────────

function _ensureProvider() {
  if (!_provider) {
    init();
  }
}

/**
 * Build the CLI argument array for the current provider.
 * Follows the same pattern as New-AgentArguments in agent.ps1.
 */
function _buildArgs(prompt) {
  _ensureProvider();
  const p = _provider;

  // Copilot style: gh copilot [args] -- -p "<prompt>"
  if (p.promptPosition === 'separator') {
    return [...p.baseArgs, p.promptSeparator || '--', p.promptFlag, prompt];
  }

  // Claude / OpenAI / generic: <binary> [args] -p "<prompt>"
  if (p.promptPosition === 'flag') {
    return [...p.baseArgs, p.promptFlag, prompt];
  }

  // stdin: pipe prompt via stdin
  if (p.promptPosition === 'stdin') {
    return [...p.baseArgs];
  }

  // Default: flag
  return [...p.baseArgs, p.promptFlag, prompt];
}

/**
 * Build environment variables for the subprocess.
 */
function _buildEnv() {
  _ensureProvider();
  const env = { ...process.env };

  // Pass through provider-specific env vars
  for (const v of _provider.envVars) {
    if (process.env[v] && !env[v]) {
      env[v] = process.env[v];
    }
  }

  return env;
}

// ── Internal: invocation ──────────────────────────────────────────────────

function _invoke(prompt, timeout) {
  _ensureProvider();
  const args = _buildArgs(prompt);
  const env = _buildEnv();

  console.error(`[llm] Invoking: ${_provider.binary} ${args.slice(0, -1).join(' ')} "..." (${_provider.type})`);

  return new Promise((resolve, reject) => {
    const child = execFile(_provider.binary, args, {
      timeout: timeout,
      maxBuffer: 2 * 1024 * 1024,
      env: env,
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

    _attachKillEscalation(child, timeout, 5000);
  });
}

function _preWarm() {
  _ensureProvider();
  const args = _buildArgs('say ok');
  const env = _buildEnv();

  return new Promise((resolve) => {
    const child = execFile(_provider.binary, args, {
      timeout: 15000,
      maxBuffer: 1024,
      env: env,
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

function _attachKillEscalation(child, timeoutMs, graceMs) {
  const totalWait = timeoutMs + graceMs;
  const timer = setTimeout(() => {
    try {
      if (child.exitCode === null && !child.killed) {
        console.log(`[llm] Kill escalation: sending SIGKILL after ${totalWait / 1000}s`);
        child.kill('SIGKILL');
      }
    } catch (e) {
      // Process already gone
    }
  }, totalWait);

  if (timer.unref) timer.unref();
}

function _sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// ── Heuristic fallback ────────────────────────────────────────────────────

function heuristicDecision(issue) {
  if (!_reviewHistory) {
    return { decision: 'DEFER', notes: '[Heuristic] No review history available — defaulting to DEFER.', heuristic: true };
  }

  const title = issue.title || '';
  const severity = issue.severity || 'Medium';
  const category = issue.category || '';

  // Rule 1: Similar past issues
  const similar = _reviewHistory.findSimilarIssues(title, 0.30);
  if (similar.length > 0) {
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

  // Rule 2: Critical → almost always ACCEPT
  if (severity === 'Critical') {
    return {
      decision: 'ACCEPT',
      notes: '[Heuristic] Critical-severity issues are almost always ACCEPTed. LLM unavailable — verify.',
      heuristic: true,
    };
  }

  // Rule 3: Reliability + High → likely ACCEPT
  if (category.toLowerCase().indexOf('reliability') >= 0 && (severity === 'High' || severity === 'Critical')) {
    return {
      decision: 'ACCEPT',
      notes: '[Heuristic] High-severity reliability issues have ~63% ACCEPT rate. LLM unavailable — verify.',
      heuristic: true,
    };
  }

  // Rule 4: Documentation + Low → likely WONTFIX
  if (category.toLowerCase().indexOf('documentation') >= 0 && severity === 'Low') {
    return {
      decision: 'WONTFIX',
      notes: '[Heuristic] Low-severity documentation issues are often WONTFIX (human readability concerns). LLM unavailable — verify.',
      heuristic: true,
    };
  }

  // Rule 5: Low severity + UX/Discoverability → likely DEFER
  if (severity === 'Low' && (category.toLowerCase().indexOf('ux') >= 0 || category.toLowerCase().indexOf('discoverability') >= 0)) {
    return {
      decision: 'DEFER',
      notes: '[Heuristic] Low-severity UX/discoverability issues are often DEFERred. LLM unavailable — verify.',
      heuristic: true,
    };
  }

  // Default: DEFER
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
  getProviderInfo,
  sendPrompt,
  heuristicDecision,
  PROVIDER_DEFS,
};
