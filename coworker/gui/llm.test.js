/**
 * llm.test.js — Tests for the LLM invocation wrapper.
 *
 * Run: node --test llm.test.js
 */

'use strict';

const { describe, it, before, after } = require('node:test');
const assert = require('node:assert/strict');
const path = require('path');
const fs = require('fs');
const os = require('os');

// ── Module under test ──────────────────────────────────────────────────────

const llm = require('./llm.js');

// ── Helpers ────────────────────────────────────────────────────────────────

/** Ensure provider is initialised before each test that needs it. */
function ensureInit() {
  llm.init({
    provider: 'claude',
    binary: 'claude',
    baseArgs: ['--dangerously-skip-permissions'],
  });
}

// ════════════════════════════════════════════════════════════════════════════
// _buildArgs — argument construction
// ════════════════════════════════════════════════════════════════════════════

describe('_buildArgs', () => {

  describe('when prompt is null (stdin mode)', () => {
    it('should return base args + print flag, without prompt text', () => {
      ensureInit();
      const args = llm._buildArgs(null);

      assert.ok(args.includes('--dangerously-skip-permissions'), 'should include base args');
      assert.ok(args.includes('-p'), 'should include -p (--print) for non-interactive mode');
      assert.ok(!args.includes(null), 'should not contain null');
      assert.equal(args.includes(undefined), false, 'should not contain undefined');
      // The last argument must NOT be the prompt — last should be -p
      assert.equal(args[args.length - 1], '-p', '-p should be the last argument');
    });

    it('should return exactly baseArgs + promptFlag', () => {
      ensureInit();
      const args = llm._buildArgs(null);

      // -p is the only flag beyond base args — no prompt text
      assert.equal(args.length, 2, 'should be 2 args: baseArg + -p');
    });
  });

  describe('when prompt is provided (backward compat)', () => {
    it('should include the prompt text as a positional argument', () => {
      ensureInit();
      const args = llm._buildArgs('say ok');

      assert.ok(args.includes('--dangerously-skip-permissions'), 'should include base args');
      assert.ok(args.includes('-p'), 'should include -p flag');
      assert.ok(args.includes('say ok'), 'should include prompt text');
    });

    it('should handle long prompts', () => {
      ensureInit();
      const longPrompt = 'A'.repeat(5000);
      const args = llm._buildArgs(longPrompt);

      assert.ok(args.includes(longPrompt), 'should include the full long prompt');
    });

    it('should handle empty string prompt', () => {
      ensureInit();
      // empty string is falsy, so it hits the null branch
      const args = llm._buildArgs('');

      assert.ok(args.includes('-p'), 'should include -p');
      // With '' prompt, it hits null branch, no prompt text
    });
  });

  describe('when prompt is undefined (edge cases)', () => {
    it('should treat undefined as no-prompt', () => {
      ensureInit();
      const args = llm._buildArgs(undefined);

      assert.ok(args.includes('-p'), 'should include -p');
      assert.ok(!args.includes(undefined), 'should not contain undefined');
    });
  });

  // dsh takes the task as a bare positional argument of its headless profile
  // (`dsh --profile headless "<task>"`); it has no prompt flag and rejects stdin.
  describe('when the provider takes a positional prompt (dsh)', () => {
    it('should append the prompt after the profile args with no flag', () => {
      llm.init({ provider: 'dsh' });
      const args = llm._buildArgs('say ok');

      assert.deepEqual(args, ['--profile', 'headless', 'say ok']);
    });

    it('should leave the args alone when the prompt is missing', () => {
      llm.init({ provider: 'dsh' });

      assert.deepEqual(llm._buildArgs(null), ['--profile', 'headless']);
      assert.deepEqual(llm._buildArgs(''), ['--profile', 'headless'],
        'an empty prompt must not become an empty positional argument');
    });

    it('should add the headless profile when config supplies only the binary', () => {
      // config.psd1's `DSH = @('dsh')` yields an empty baseArgs array.
      llm.init({ provider: 'dsh', baseArgs: [] });

      assert.deepEqual(llm._buildArgs('say ok'), ['--profile', 'headless', 'say ok']);
    });

    it('should keep a profile pinned in the base args', () => {
      llm.init({ provider: 'dsh', baseArgs: ['--profile', 'acp'] });

      assert.deepEqual(llm._buildArgs('say ok'), ['--profile', 'acp', 'say ok']);
    });
  });
});

// ════════════════════════════════════════════════════════════════════════════
// _writePromptFile — temp file creation
// ════════════════════════════════════════════════════════════════════════════

describe('_writePromptFile', () => {
  it('should write the prompt to a temp file and return its path', () => {
    const prompt = 'This is a test prompt for stdin-based invocation.';
    const filePath = llm._writePromptFile(prompt);

    try {
      assert.ok(filePath, 'should return a non-empty path');
      assert.ok(path.isAbsolute(filePath), 'should return an absolute path');
      assert.ok(filePath.includes('llm-prompt-'), 'filename should include llm-prompt- prefix');
      assert.ok(filePath.endsWith('.txt'), 'filename should end with .txt');

      // Verify file content
      const content = fs.readFileSync(filePath, 'utf-8');
      assert.equal(content, prompt, 'file content should match the prompt exactly');
    } finally {
      try { fs.unlinkSync(filePath); } catch (_) {}
    }
  });

  it('should handle multi-line prompts with special characters', () => {
    const prompt = 'Line 1\nLine 2\n\tIndented\nSpecial chars: "quotes" \'apostrophes\' `backticks` $dollar & ampersand';
    const filePath = llm._writePromptFile(prompt);

    try {
      const content = fs.readFileSync(filePath, 'utf-8');
      assert.equal(content, prompt, 'multi-line content with special chars should be preserved');
    } finally {
      try { fs.unlinkSync(filePath); } catch (_) {}
    }
  });

  it('should handle very long prompts (100KB+)', () => {
    const prompt = 'A'.repeat(100 * 1024);  // 100 KB
    const filePath = llm._writePromptFile(prompt);

    try {
      const stat = fs.statSync(filePath);
      assert.ok(stat.size >= 100 * 1024, 'file should be at least 100KB');
      const content = fs.readFileSync(filePath, 'utf-8');
      assert.equal(content, prompt, 'full long prompt should be preserved');
    } finally {
      try { fs.unlinkSync(filePath); } catch (_) {}
    }
  });

  it('should handle empty prompt', () => {
    const prompt = '';
    const filePath = llm._writePromptFile(prompt);

    try {
      const content = fs.readFileSync(filePath, 'utf-8');
      assert.equal(content, '', 'empty prompt should produce empty file');
    } finally {
      try { fs.unlinkSync(filePath); } catch (_) {}
    }
  });

  it('should create unique filenames per invocation', () => {
    const file1 = llm._writePromptFile('prompt 1');
    const file2 = llm._writePromptFile('prompt 2');

    try {
      assert.notEqual(file1, file2, 'each call should produce a unique filename');
    } finally {
      try { fs.unlinkSync(file1); } catch (_) {}
      try { fs.unlinkSync(file2); } catch (_) {}
    }
  });
});

// ════════════════════════════════════════════════════════════════════════════
// PROVIDER_DEFS — provider structure
// ════════════════════════════════════════════════════════════════════════════

describe('PROVIDER_DEFS', () => {
  it('should define claude provider correctly', () => {
    const def = llm.PROVIDER_DEFS.claude;
    assert.equal(def.binary, 'claude');
    assert.equal(def.promptFlag, '-p');
    assert.equal(def.promptPosition, 'flag');
    assert.ok(Array.isArray(def.baseArgs));
  });

  it('should define copilot provider correctly', () => {
    const def = llm.PROVIDER_DEFS.copilot;
    assert.equal(def.binary, 'gh');
    assert.equal(def.promptFlag, '-p');
    assert.equal(def.promptPosition, 'separator');
    assert.ok(Array.isArray(def.baseArgs));
  });

  it('should define openai provider correctly', () => {
    const def = llm.PROVIDER_DEFS.openai;
    assert.equal(def.binary, 'openai');
    assert.equal(def.promptFlag, '-p');
  });

  it('should define dsh provider with a positional prompt and no run subcommand', () => {
    const def = llm.PROVIDER_DEFS.dsh;
    assert.equal(def.binary, 'dsh');
    assert.equal(def.promptPosition, 'positional');
    assert.deepEqual(def.baseArgs, ['--profile', 'headless']);
    assert.notEqual(def.promptFlag, 'run', 'dsh has no `run` subcommand');
  });
});

// ════════════════════════════════════════════════════════════════════════════
// _parsePsd1Config — backend priority (dsh preferred)
// ════════════════════════════════════════════════════════════════════════════

describe('_parsePsd1Config', () => {
  function writeTempConfig(content) {
    const file = path.join(os.tmpdir(),
      `llm-config-${Date.now()}-${Math.random().toString(36).slice(2)}.psd1`);
    fs.writeFileSync(file, content, 'utf-8');
    return file;
  }

  function withConfig(content, fn) {
    const file = writeTempConfig(content);
    try {
      return fn(llm._parsePsd1Config(file));
    } finally {
      try { fs.unlinkSync(file); } catch (_) {}
    }
  }

  it('should prefer an active DSH block over an active CLAUDE block', () => {
    withConfig(
      "@{\n  DSH = @(\n    'dsh'\n  )\n  CLAUDE = @(\n    'claude'\n    '--verbose'\n  )\n}\n",
      (cfg) => {
        assert.equal(cfg.type, 'dsh');
        assert.equal(cfg.binary, 'dsh');
        assert.deepEqual(cfg.baseArgs, []);
      });
  });

  it('should prefer an active CLAUDE block over KIMI/CODEX when DSH is commented', () => {
    withConfig(
      "@{\n  # DSH = @(\n  #   'dsh'\n  # )\n  CLAUDE = @(\n    'claude'\n    '--verbose'\n  )\n  CODEX = @(\n    'codex'\n  )\n}\n",
      (cfg) => {
        assert.equal(cfg.type, 'claude');
        assert.deepEqual(cfg.baseArgs, ['--verbose']);
      });
  });

  it('should fall through to COPILOT when only COPILOT is active', () => {
    withConfig(
      "@{\n  # CLAUDE = @(\n  #   'claude'\n  # )\n  COPILOT = @(\n    'gh'\n    'copilot'\n  )\n}\n",
      (cfg) => {
        assert.equal(cfg.type, 'copilot');
        assert.equal(cfg.binary, 'gh');
        assert.deepEqual(cfg.baseArgs, ['copilot']);
      });
  });

  it('should return null for a config with no active backend block', () => {
    withConfig("@{\n  # CLAUDE = @(\n  #   'claude'\n  # )\n}\n", (cfg) => {
      assert.equal(cfg, null);
    });
  });
});

// ════════════════════════════════════════════════════════════════════════════
// heuristicDecision — existing logic (ensure not broken)
// ════════════════════════════════════════════════════════════════════════════

describe('heuristicDecision', () => {
  // A minimal mock review history so the "no history" guard doesn't
  // short-circuit.  Use a real ReviewHistory instance if available;
  // otherwise inject a duck-typed mock that matches the API surface
  // used by heuristicDecision (findSimilarIssues).
  function withHistory(fn) {
    const origReviewHistory = llm._reviewHistory;  // internal state
    llm.init({
      provider: 'claude',
      binary: 'claude',
      baseArgs: ['--dangerously-skip-permissions'],
      reviewHistory: {
        findSimilarIssues: () => [],
      },
    });
    try {
      fn();
    } finally {
      // Restore (next init call will overwrite anyway, but be tidy)
      llm.init({
        provider: 'claude',
        binary: 'claude',
        baseArgs: ['--dangerously-skip-permissions'],
        reviewHistory: origReviewHistory,
      });
    }
  }

  it('should return DEFER for a basic issue', () => {
    withHistory(() => {
      const result = llm.heuristicDecision({
        title: 'Some new issue',
        severity: 'Low',
        category: 'UX',
      });
      assert.equal(result.decision, 'DEFER');
      assert.equal(result.heuristic, true);
      assert.ok(result.notes.includes('Heuristic'));
    });
  });

  it('should ACCEPT critical severity issues when history is available', () => {
    withHistory(() => {
      const result = llm.heuristicDecision({
        title: 'Critical crash in login',
        severity: 'Critical',
        category: 'Bug',
      });
      assert.equal(result.decision, 'ACCEPT');
    });
  });

  it('should ACCEPT high-severity reliability issues when history is available', () => {
    withHistory(() => {
      const result = llm.heuristicDecision({
        title: 'Memory leak in parser',
        severity: 'High',
        category: 'Reliability',
      });
      assert.equal(result.decision, 'ACCEPT');
    });
  });
});
