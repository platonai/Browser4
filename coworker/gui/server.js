#!/usr/bin/env node

/**
 * Coworker Task Manager — Node.js web server.
 *
 * Serves the task-management GUI and a REST API over the local filesystem.
 * Start with:
 *   node server.js [--port 8090] [--tasks-root ./coworker/tasks/] [--host 127.0.0.1] [--open-browser]
 */

const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');
const { execFile } = require('child_process');
const reviewHistory = require('./review-history.js');
const llm = require('./llm.js');
const { buildSummaryContent } = require('./summary-builder.js');

// ── CLI argument parsing ────────────────────────────────────────────────

const args = process.argv.slice(2);
function argFlag(name, fallback) {
  const idx = args.indexOf(`--${name}`);
  return idx >= 0 && idx + 1 < args.length ? args[idx + 1] : fallback;
}
function argBool(name) {
  return args.includes(`--${name}`);
}

const HOST = argFlag('host', '127.0.0.1');
const PORT = parseInt(argFlag('port', '8090'), 10);
// Default tasks root is ../tasks/ relative to this script (i.e. coworker/tasks/).
// Use __dirname so it works regardless of where the process is started from.
const DEFAULT_TASKS_ROOT = path.resolve(__dirname, '..', 'tasks');
const TASKS_ROOT = path.resolve(argFlag('tasks-root', DEFAULT_TASKS_ROOT));
const OPEN_BROWSER = argBool('open-browser');

// Initialise the review-history index and LLM wrapper
reviewHistory.init(TASKS_ROOT);
llm.init({
  provider: process.env.LLM_PROVIDER || null,
  binary: process.env.LLM_PATH || null,
  baseArgs: process.env.LLM_ARGS ? process.env.LLM_ARGS.split(/\s+/).filter(Boolean) : null,
  tasksRoot: TASKS_ROOT,
  reviewHistory: reviewHistory,
});

// ── Stage registry ──────────────────────────────────────────────────────

const STAGES = [
  // Main pipeline
  { id: '0draft',                    display_name: 'Draft',           path_suffix: 'main/0draft',                    date_stamped: false, group: 'main',   hidden: false },
  { id: '1ready',                    display_name: 'Ready',           path_suffix: 'main/1ready',                    date_stamped: false, group: 'main',   hidden: false },
  { id: '2working',                  display_name: 'Working',         path_suffix: 'main/2working',                  date_stamped: false, group: 'main',   hidden: false },
  { id: '3done',                     display_name: 'Done',            path_suffix: 'main/3done',                     date_stamped: true,  group: 'main',   hidden: false },
  { id: '4review',                   display_name: 'Review',          path_suffix: 'main/4review',                   date_stamped: false, group: 'main',   hidden: false },
  { id: '5approved',                 display_name: 'Approved',        path_suffix: 'main/5approved',                 date_stamped: true,  group: 'main',   hidden: false },
  { id: '6git-pushed',               display_name: 'Git Pushed',      path_suffix: 'main/6git-pushed',               date_stamped: true,  group: 'main',   hidden: false },
  // Refinement sub-pipeline
  { id: '0draft/refine/0draft',      display_name: 'Refine Source',   path_suffix: 'main/0draft/refine/0draft',      date_stamped: false, group: 'refine', hidden: false },
  { id: '0draft/refine/1ready',      display_name: 'Refine Ready',    path_suffix: 'main/0draft/refine/1ready',      date_stamped: false, group: 'refine', hidden: false },
  { id: '0draft/refine/2working',    display_name: 'Refine Working',  path_suffix: 'main/0draft/refine/2working',    date_stamped: false, group: 'refine', hidden: false },
  { id: '0draft/refine/3done',       display_name: 'Refine Done',     path_suffix: 'main/0draft/refine/3done',       date_stamped: false, group: 'refine', hidden: false },
  { id: '0draft/refine/0error',      display_name: 'Refine Errors',   path_suffix: 'main/0draft/refine/0error',      date_stamped: false, group: 'refine', hidden: false },
  // Source directories (input feeders)
  { id: '0draft/issues/github',         display_name: 'GitHub Issues',   path_suffix: 'main/0draft/issues/github',       date_stamped: true,  group: 'sources', hidden: false },
  // GitHub issues pipeline
  { id: 'issues/draft/refine/0ready',  display_name: 'Issues Ready',    path_suffix: 'issues/draft/refine/0ready',  date_stamped: false, group: 'issues', hidden: false },
  { id: 'issues/draft/refine/1working', display_name: 'Issues Working', path_suffix: 'issues/draft/refine/1working', date_stamped: false, group: 'issues', hidden: false },
  { id: 'issues/draft/refine/2done',   display_name: 'Issues Done',     path_suffix: 'issues/draft/refine/2done',   date_stamped: true,  group: 'issues', hidden: false },
  { id: 'issues/draft/refine/0error',  display_name: 'Issues Errors',   path_suffix: 'issues/draft/refine/0error',  date_stamped: false, group: 'issues', hidden: false },
  { id: 'issues/github/commit/ready',  display_name: 'GH Commit Ready', path_suffix: 'issues/github/commit/ready',  date_stamped: false, group: 'issues', hidden: false },
  { id: 'issues/github/commit/done',   display_name: 'GH Committed',    path_suffix: 'issues/github/commit/done',   date_stamped: false, group: 'issues', hidden: false },
  { id: 'issues/github/commit/failed', display_name: 'GH Failed',       path_suffix: 'issues/github/commit/failed', date_stamped: false, group: 'issues', hidden: false },
  // Issue review
  { id: 'issues/review',             display_name: 'Review Queue',    path_suffix: 'issues/review',              date_stamped: true,  group: 'review', hidden: false },
];

const stageById = Object.fromEntries(STAGES.map(s => [s.id, s]));
const visibleStages = STAGES.filter(s => !s.hidden);

// ── Valid move transitions ───────────────────────────────────────────────
// Each key is a source stage ID; its value is an array of valid target stage IDs.
// Moving to any stage NOT in the list is rejected by the server.
const VALID_TRANSITIONS = {
  // Main pipeline
  '0draft':       ['1ready', '0draft/refine/0draft', '0draft/issues/github', 'issues/draft/refine/0ready'],
  '1ready':       ['2working', '0draft'],
  '2working':     ['3done', '5approved', '1ready', '0draft'],
  '3done':        ['4review', '5approved', '6git-pushed'],
  '4review':      ['3done', '5approved', '0draft'],
  '5approved':    ['6git-pushed', '4review'],
  '6git-pushed':  ['0draft'],  // rework

  // Refinement sub-pipeline
  '0draft/refine/0draft':   ['0draft/refine/1ready', '1ready'],
  '0draft/refine/1ready':   ['0draft/refine/2working', '0draft/refine/0draft'],
  '0draft/refine/2working': ['0draft/refine/3done', '0draft/refine/0error', '0draft/refine/1ready'],
  '0draft/refine/3done':    ['0draft', '1ready', '0draft/refine/0draft'],
  '0draft/refine/0error':   ['0draft/refine/0draft', '0draft'],

  // GitHub issues pipeline
  '0draft/issues/github':   ['1ready', '0draft'],
  'issues/draft/refine/0ready':  ['issues/draft/refine/1working', '0draft'],
  'issues/draft/refine/1working': ['issues/draft/refine/2done', 'issues/github/commit/ready', 'issues/draft/refine/0error'],
  'issues/draft/refine/2done':   ['issues/github/commit/ready', '0draft'],
  'issues/draft/refine/0error':  ['issues/draft/refine/0ready', '0draft'],
  'issues/github/commit/ready':  ['issues/github/commit/done', 'issues/github/commit/failed', 'issues/draft/refine/2done'],
  'issues/github/commit/done':   [],
  'issues/github/commit/failed': ['issues/github/commit/ready', '0draft'],
};

function resolveStageDir(stage, date) {
  const base = path.join(TASKS_ROOT, stage.path_suffix);
  if (stage.date_stamped) {
    const y = date.getFullYear().toString();
    const mmdd = String(date.getMonth() + 1).padStart(2, '0') + String(date.getDate()).padStart(2, '0');
    return path.join(base, y, mmdd);
  }
  return base;
}

// ── Safe path resolution ────────────────────────────────────────────────

function safeResolve(relPath, mustExist) {
  if (typeof relPath !== 'string' || relPath.includes('..') || relPath.includes('\0')) {
    return { error: 403, message: 'Path traversal not allowed' };
  }
  const abs = path.resolve(TASKS_ROOT, relPath);
  if (!abs.startsWith(TASKS_ROOT + path.sep) && abs !== TASKS_ROOT) {
    return { error: 403, message: 'Path traversal not allowed' };
  }
  if (mustExist && !fs.existsSync(abs)) {
    return { error: 404, message: 'File not found' };
  }
  return { abs };
}

function validateFilename(name) {
  if (!name || typeof name !== 'string') return 'Filename is required';
  if (name.startsWith('.')) return 'Filenames starting with "." are not allowed';
  if (/[<>:"|?*]/.test(name)) return 'Filename contains invalid characters';
  return null;
}

function ensureMdExt(name) {
  return /\.(md|json)$/i.test(name) ? name : name + '.md';
}

// ── File listing helpers ────────────────────────────────────────────────

function listTasksRecursive(dir, root) {
  const results = [];
  if (!fs.existsSync(dir)) return results;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('.')) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...listTasksRecursive(full, root));
    } else if (/\.(md|json)$/i.test(entry.name)) {
      const stat = fs.statSync(full);
      results.push({
        name: entry.name,
        path: path.relative(root, full).replace(/\\/g, '/'),
        size: stat.size,
        modified: stat.mtime.toISOString().replace(/\.\d{3}Z$/, 'Z'),
      });
    }
  }
  results.sort((a, b) => a.path.localeCompare(b.path));
  return results;
}

function listTasksFlat(dir, root) {
  const results = [];
  if (!fs.existsSync(dir)) return results;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) continue;
    if (!/\.(md|json)$/i.test(entry.name)) continue;
    const full = path.join(dir, entry.name);
    const stat = fs.statSync(full);
    results.push({
      name: entry.name,
      path: path.relative(root, full).replace(/\\/g, '/'),
      size: stat.size,
      modified: stat.mtime.toISOString().replace(/\.\d{3}Z$/, ''),
    });
  }
  results.sort((a, b) => a.path.localeCompare(b.path));
  return results;
}

function countTasksRecursive(dir) {
  let count = 0;
  if (!fs.existsSync(dir)) return count;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('.')) continue;
    if (entry.isDirectory()) {
      count += countTasksRecursive(path.join(dir, entry.name));
    } else if (/\.(md|json)$/i.test(entry.name)) {
      count++;
    }
  }
  return count;
}

function countTasksFlat(dir) {
  if (!fs.existsSync(dir)) return 0;
  return fs.readdirSync(dir).filter(f => {
    const full = path.join(dir, f);
    return fs.statSync(full).isFile() && /\.(md|json)$/i.test(f);
  }).length;
}

// ── Express app ─────────────────────────────────────────────────────────

const app = express();
app.use(cors());
app.use(express.json({ limit: '1mb' }));

// Serve static assets (JS, CSS) from the frontend directory
app.use('/static', express.static(path.join(__dirname, 'frontend')));

// Serve frontend
app.get('/', (_req, res) => {
  res.sendFile(path.join(__dirname, 'frontend', 'index.html'));
});

// Serve issue review SPA
app.get('/issues/review', (_req, res) => {
  res.sendFile(path.join(__dirname, 'frontend', 'issue-review.html'));
});

// GET /api/stats
app.get('/api/stats', (_req, res) => {
  const stages = {};
  let total = 0;
  for (const s of visibleStages) {
    const dir = path.join(TASKS_ROOT, s.path_suffix);
    const count = s.date_stamped ? countTasksRecursive(dir) : countTasksFlat(dir);
    total += count;
    stages[s.id] = {
      count,
      display_name: s.display_name,
      date_stamped: s.date_stamped,
      group: s.group,
      path_suffix: s.path_suffix,
    };
  }
  res.json({ stages, total });
});

// GET /api/tasks?stage=<id>
app.get('/api/tasks', (req, res) => {
  const stage = stageById[req.query.stage];
  if (!stage) return res.status(400).json({ error: `Unknown stage: ${req.query.stage}` });
  const dir = path.join(TASKS_ROOT, stage.path_suffix);
  const tasks = stage.date_stamped
    ? listTasksRecursive(dir, TASKS_ROOT)
    : listTasksFlat(dir, TASKS_ROOT);
  res.json({ stage: req.query.stage, tasks });
});

// GET /api/task?path=<rel>
app.get('/api/task', (req, res) => {
  const r = safeResolve(req.query.path, true);
  if (r.error) return res.status(r.error).json({ error: r.message });

  try {
    const content = fs.readFileSync(r.abs, 'utf-8');
    const stat = fs.statSync(r.abs);
    res.json({
      path: req.query.path,
      content,
      size: stat.size,
      modified: stat.mtime.toISOString().replace(/\.\d{3}Z$/, ''),
    });
  } catch (e) {
    res.status(400).json({ error: `Failed to read file: ${e.message}` });
  }
});

// POST /api/task?path=<rel>
app.post('/api/task', (req, res) => {
  const r = safeResolve(req.query.path, false);
  if (r.error) return res.status(r.error).json({ error: r.message });

  const filename = path.basename(req.query.path);
  const nameErr = validateFilename(filename);
  if (nameErr) return res.status(400).json({ error: nameErr });

  try {
    fs.mkdirSync(path.dirname(r.abs), { recursive: true });
    fs.writeFileSync(r.abs, req.body.content || '', 'utf-8');
    const stat = fs.statSync(r.abs);
    res.json({
      path: req.query.path,
      content: req.body.content || '',
      size: stat.size,
      modified: stat.mtime.toISOString().replace(/\.\d{3}Z$/, ''),
    });
  } catch (e) {
    res.status(400).json({ error: `Failed to write file: ${e.message}` });
  }
});

// DELETE /api/task?path=<rel>
app.delete('/api/task', (req, res) => {
  const r = safeResolve(req.query.path, true);
  if (r.error) return res.status(r.error).json({ error: r.message });

  if (fs.statSync(r.abs).isDirectory()) {
    return res.status(400).json({ error: 'Cannot delete a directory.' });
  }

  try {
    fs.unlinkSync(r.abs);
    res.json({ success: true });
  } catch (e) {
    res.status(400).json({ error: `Failed to delete file: ${e.message}` });
  }
});

// POST /api/move
app.post('/api/move', (req, res) => {
  const { path: srcPath, target_stage, new_name, source_stage } = req.body;
  if (!srcPath || !target_stage) {
    return res.status(400).json({ error: 'path and target_stage are required' });
  }

  const src = safeResolve(srcPath, true);
  if (src.error) return res.status(src.error).json({ error: src.message });

  const stage = stageById[target_stage];
  if (!stage) return res.status(400).json({ error: `Unknown stage: ${target_stage}` });

  // Validate transition (use client-provided source_stage if available)
  const detectedSrcStage = source_stage || null;
  if (detectedSrcStage && VALID_TRANSITIONS[detectedSrcStage] !== undefined) {
    if (!VALID_TRANSITIONS[detectedSrcStage].includes(target_stage)) {
      return res.status(400).json({
        error: `Invalid transition: cannot move from "${detectedSrcStage}" to "${target_stage}". Allowed targets: ${VALID_TRANSITIONS[detectedSrcStage].join(', ') || '(none)'}`,
      });
    }
  }

  const today = new Date();
  const targetDir = resolveStageDir(stage, today);

  let filename = new_name || path.basename(srcPath);
  filename = ensureMdExt(filename);
  const nameErr = validateFilename(filename);
  if (nameErr) return res.status(400).json({ error: nameErr });

  // Unique filename
  let targetPath = path.join(targetDir, filename);
  if (fs.existsSync(targetPath)) {
    const stem = filename.replace(/\.(md|json)$/i, '');
    const ext = filename.match(/\.(md|json)$/i)?.[0] || '.md';
    for (let n = 2; n < 1000; n++) {
      const alt = path.join(targetDir, `${stem}.${n}${ext}`);
      if (!fs.existsSync(alt)) { targetPath = alt; break; }
    }
    // Fallback
    if (fs.existsSync(targetPath)) {
      const ts = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
      targetPath = path.join(targetDir, `${stem}.${ts}${ext}`);
    }
  }

  if (src.abs === targetPath) {
    return res.json({ success: true, new_path: srcPath });
  }

  try {
    fs.mkdirSync(targetDir, { recursive: true });
    const content = fs.readFileSync(src.abs, 'utf-8');
    fs.writeFileSync(targetPath, content, 'utf-8');
    fs.unlinkSync(src.abs);

    const newRel = path.relative(TASKS_ROOT, targetPath).replace(/\\/g, '/');
    res.json({ success: true, new_path: newRel });
  } catch (e) {
    res.status(400).json({ error: `Failed to move: ${e.message}` });
  }
});

// POST /api/issue-review/ai-suggest
app.post('/api/issue-review/ai-suggest', (req, res) => {
  const { title, severity, category, sections, suggestedImprovement, siblingIssues, scenarioContext } = req.body;
  if (!title) return res.status(400).json({ error: 'Issue title is required' });

  // Build enriched context from review history
  const ctx = reviewHistory.buildEnrichedContext(title);

  // Build the issue text
  const parts = [`Issue: ${title}`, `Severity: ${severity || 'N/A'}`, `Category: ${category || 'N/A'}`];
  if (sections) {
    for (const s of sections) {
      parts.push(`${s.label}: ${s.body}`);
    }
  }
  if (suggestedImprovement) {
    parts.push(`AI Suggested Improvement: ${suggestedImprovement}`);
  }
  const issueText = parts.join('\n\n');

  // Build sibling context if provided
  let siblingText = '';
  if (siblingIssues && siblingIssues.length > 0) {
    siblingText = '\n## Other Issues in the Same Evaluation\n\n';
    siblingText += 'The following issues were found in the same scenario. ';
    siblingText += 'Consider whether this issue duplicates or relates to any of them:\n\n';
    for (const sib of siblingIssues) {
      const decTag = sib.decision ? ` (${sib.decision})` : ' (unreviewed)';
      siblingText += `- Issue ${sib.number}: "${sib.title}"${decTag}\n`;
    }
    siblingText += '\n';
  }

  // Build scenario context if provided
  let scenarioText = '';
  if (scenarioContext) {
    scenarioText = `\n## Scenario Context\n\n${scenarioContext}\n`;
  }

  const prompt = `You are reviewing issues for browser4-cli, a browser automation CLI tool built for AI AGENTS (not humans) to use. Analyze this issue and choose the best decision.

## Review Guidelines

- **ACCEPT** — Issue is valid and the suggested fix is correct. Use for real bugs, broken behavior, missing features that block AI agents.
- **ACCEPT with improvements** — Issue is valid but the suggested fix needs refinement (describe what in Notes).
- **DEFER** — Issue is acknowledged as real but intentionally deferred — typically large architectural changes, low-priority nice-to-haves, or things that need more design.
- **WONTFIX** — Issue is acknowledged as real but will NOT be fixed. Use for: third-party behavior the tool can't control, platform-specific quirks that are impractical to fix, or intentional design decisions.
- **REJECT** — Issue is NOT valid. Use when: the reported behavior is intentional and correct, the issue misunderstands the tool's purpose, or the problem only affects human readability (not AI agents). Remember: this tool is for AI AGENTS — what looks like a UX problem to a human may be perfectly fine for an AI.
- **DUPLICATE** — Issue describes the same problem as another existing issue (reference which one in Notes).

**Decision rules of thumb:**
- If the issue blocks or misleads an AI agent → ACCEPT or ACCEPT with improvements
- If the issue only matters for human readability (verbose output, not machine-parseable) → REJECT or DEFER
- If the fix requires major architectural changes → DEFER
- If the behavior is about external websites or platforms → WONTFIX
- If the issue is about development-mode friction (cargo run overhead, cd into subdirs) → WONTFIX
- If the same root cause appears in multiple issues → mark one ACCEPT, rest DUPLICATE

**CRITICAL — Notes requirement:**
- If your decision is ACCEPT, notes are OPTIONAL (can be empty or brief).
- If your decision is ANYTHING OTHER THAN ACCEPT (DEFER, WONTFIX, REJECT, DUPLICATE, ACCEPT with improvements), you MUST provide specific, actionable notes explaining WHY. Include: (1) the reason, and (2) what would need to change for a different outcome.
- Never leave notes empty for a non-ACCEPT decision.

${ctx.statsSection ? ctx.statsSection + '\n' : ''}\
${ctx.examplesSection ? ctx.examplesSection + '\n' : ''}\
${ctx.similarSection ? ctx.similarSection + '\n' : ''}\
${scenarioText}\
${siblingText}\
## Issue to Review

${issueText}

Respond with ONLY a single JSON object (no markdown, no backticks):
{"decision": "<one of the six options above>", "notes": "<rationale — REQUIRED unless decision is ACCEPT>"}`;

  // Validate and enforce notes for non-ACCEPT decisions
  function validateDecisionNotes(parsed) {
    const decision = (parsed.decision || '').trim();
    if (decision !== 'ACCEPT') {
      const notes = (parsed.notes || '').trim();
      if (!notes || notes.length < 5) {
        // If notes are missing/too short for non-ACCEPT, add a default note
        parsed.notes = '[AI did not provide rationale — review required] Decision: ' + decision + '. Original notes: ' + (notes || '(none)');
      }
    }
    return parsed;
  }

  // Use the resilient LLM wrapper with retry, circuit breaker, pre-warm, and heuristic fallback
  llm.sendPrompt(prompt, {
    timeout: 60000,
    retries: 3,
    heuristic: function() {
      return llm.heuristicDecision({ title, severity, category, sections });
    },
  }).then(function(result) {
    if (result.heuristic) {
      // Heuristic fallback — return the heuristic decision with a flag
      res.json({
        decision: result.heuristicResult.decision,
        notes: result.heuristicResult.notes,
        heuristic: true,
      });
      return;
    }

    const stdout = result.stdout;
    const jsonMatch = stdout.match(/\{[\s\S]*\}/);
    if (!jsonMatch) {
      return res.status(500).json({ error: 'AI response did not contain valid JSON', raw: stdout.substring(0, 500) });
    }

    try {
      const parsed = JSON.parse(jsonMatch[0]);
      if (!parsed.decision) {
        return res.status(500).json({ error: 'AI response missing decision field', raw: jsonMatch[0] });
      }
      const validated = validateDecisionNotes(parsed);
      res.json({
        decision: validated.decision.trim(),
        notes: validated.notes.trim(),
        heuristic: false,
      });
    } catch (e) {
      res.status(500).json({ error: 'Failed to parse AI response', raw: jsonMatch[0] });
    }
  }).catch(function(err) {
    res.status(500).json({ error: `AI review failed: ${err.message}` });
  });
});

// POST /api/issue-review/discard
// Moves the issue file to issues/review/done/discard/ — for files that
// contain no issues or no valuable issues.
app.post('/api/issue-review/discard', (req, res) => {
  const { path: srcPath } = req.body;
  if (!srcPath) return res.status(400).json({ error: 'path is required' });

  const src = safeResolve(srcPath, true);
  if (src.error) return res.status(src.error).json({ error: src.message });

  // Safety: ensure the source is under issues/review/
  const reviewRoot = path.join(TASKS_ROOT, 'issues', 'review');
  if (!src.abs.startsWith(reviewRoot + path.sep)) {
    return res.status(400).json({ error: 'Only files under issues/review can be discarded.' });
  }

  try {
    // Move to issues/review/done/discard, preserving date subdirectory structure
    const srcRelToReview = path.relative(reviewRoot, src.abs);
    const discardDir = path.join(reviewRoot, 'done', 'discard');
    const discardPath = path.join(discardDir, srcRelToReview);
    fs.mkdirSync(path.dirname(discardPath), { recursive: true });
    fs.renameSync(src.abs, discardPath);

    const discardRel = path.relative(TASKS_ROOT, discardPath).replace(/\\/g, '/');

    res.json({
      success: true,
      discarded_path: discardRel,
    });
  } catch (e) {
    res.status(400).json({ error: `Failed to discard: ${e.message}` });
  }
});

// POST /api/issue-review/mark-done
// Creates a summary copy in main/1ready (approved issues keep full detail,
// others condensed to abstract), then moves the original to review/done.
app.post('/api/issue-review/mark-done', (req, res) => {
  const { path: srcPath, auto_approve } = req.body;
  if (!srcPath) return res.status(400).json({ error: 'path is required' });

  const src = safeResolve(srcPath, true);
  if (src.error) return res.status(src.error).json({ error: src.message });

  // Safety: ensure the source is under issues/review/
  const reviewRoot = path.join(TASKS_ROOT, 'issues', 'review');
  if (!src.abs.startsWith(reviewRoot + path.sep)) {
    return res.status(400).json({ error: 'Only files under issues/review can be marked done.' });
  }

  try {
    const content = fs.readFileSync(src.abs, 'utf-8');

    // Check for zero issues — should be discarded instead
    const issueModel = require('./frontend/issue-model.js');
    const parsed = issueModel.parseIssueFile(content);
    if (!parsed.issues || parsed.issues.length === 0) {
      return res.status(400).json({
        error: 'This file has no issues. Use the Discard endpoint instead — zero-issue files should not enter the ready queue.',
      });
    }

    // Build the summary version
    let summaryContent = buildSummaryContent(content);

    // Append #auto-approve tag if requested — the coworker pipeline
    // detects this tag and auto-moves the file to 5approved, then triggers push.
    if (auto_approve) {
      summaryContent = summaryContent.trimEnd() + '\n\n#auto-approve\n';
    }

    // Destination: coworker/tasks/main/1ready/<basename>
    const filename = path.basename(srcPath);
    const readyDir = path.join(TASKS_ROOT, 'main', '1ready');
    fs.mkdirSync(readyDir, { recursive: true });
    let readyPath = path.join(readyDir, filename);
    // Unique filename
    if (fs.existsSync(readyPath)) {
      const stem = filename.replace(/\.(md|json)$/i, '');
      const ext = filename.match(/\.(md|json)$/i)?.[0] || '.md';
      for (let n = 2; n < 1000; n++) {
        const alt = path.join(readyDir, `${stem}.${n}${ext}`);
        if (!fs.existsSync(alt)) { readyPath = alt; break; }
      }
    }
    fs.writeFileSync(readyPath, summaryContent, 'utf-8');

    // Move original to issues/review/done, preserving date subdirectory structure
    const srcRelToReview = path.relative(reviewRoot, src.abs);
    const doneDir = path.join(reviewRoot, 'done');
    const donePath = path.join(doneDir, srcRelToReview);
    fs.mkdirSync(path.dirname(donePath), { recursive: true });
    fs.renameSync(src.abs, donePath);

    // ── Post-review actions ──────────────────────────────────────────

    // Hot-reload: index the newly reviewed file so stats and similarity
    // reflect the latest decisions without a server restart.
    reviewHistory.invalidate();
    reviewHistory.init(TASKS_ROOT);

    // Feedback loop: compare pre-review decisions (if any were AI-suggested)
    // against final human decisions for calibration tracking.
    try {
      const model = require('./frontend/issue-model.js').parseIssueFile(content);
      for (const issue of model.issues) {
        if (issue.review.decision) {
          // We record the human decision. If the issue had an AI suggestion
          // stored in notes (e.g., "[AI suggested: ACCEPT]"), compare it.
          const humanDecision = issue.review.decision;
          const notes = issue.review.notes || '';
          const aiMatch = notes.match(/\[AI suggested:\s*([^\]]+)\]/);
          if (aiMatch) {
            const aiDecision = aiMatch[1].trim();
            reviewHistory.recordFeedback(issue.title, aiDecision, humanDecision);
          }
        }
      }
    } catch (fbErr) {
      // Feedback recording is best-effort — don't fail the request
      console.error('[server] Feedback tracking error:', fbErr.message);
    }

    const readyRel = path.relative(TASKS_ROOT, readyPath).replace(/\\/g, '/');
    const doneRel = path.relative(TASKS_ROOT, donePath).replace(/\\/g, '/');

    res.json({
      success: true,
      summary_path: readyRel,
      archived_path: doneRel,
    });
  } catch (e) {
    res.status(400).json({ error: `Failed to mark done: ${e.message}` });
  }
});

// POST /api/issue-review/ai-suggest-batch
// Reviews all issues in a file together. The AI sees cross-issue context,
// scenario background, and historical review data, enabling consistent
// decisions and intra-file duplicate detection.
app.post('/api/issue-review/ai-suggest-batch', (req, res) => {
  const { issues, scenarioContext, scenarioTitle } = req.body;
  if (!issues || !Array.isArray(issues) || issues.length === 0) {
    return res.status(400).json({ error: 'issues array is required' });
  }

  // Build stats + few-shot examples once for the batch
  const statsSection = reviewHistory.buildStatsSection();
  const examplesSection = reviewHistory.buildFewShotSection(6);

  // Build per-issue text
  let issuesText = '';
  for (const iss of issues) {
    issuesText += `### Issue ${iss.numberber}: ${iss.title}\n`;
    issuesText += `**Severity:** ${iss.severity || 'N/A'} | **Category:** ${iss.category || 'N/A'}\n\n`;
    if (iss.sections) {
      for (const s of iss.sections) {
        // Truncate long sections to keep prompt manageable
        const body = (s.body || '').substring(0, 600);
        issuesText += `**${s.label}:** ${body}\n\n`;
      }
    }
    if (iss.suggestedImprovement) {
      issuesText += `**AI Suggested Improvement:** ${iss.suggestedImprovement}\n\n`;
    }
    issuesText += '---\n\n';
  }

  let scenarioText = '';
  if (scenarioContext) {
    scenarioText = `\n## Scenario Background\n\n${scenarioContext}\n`;
  }

  const prompt = `You are reviewing ALL issues from a single browser4-cli evaluation scenario${scenarioTitle ? ': ' + scenarioTitle : ''}. Review each issue and choose the best decision. Consider how issues relate to each other — if multiple issues share the same root cause, mark one as ACCEPT and the rest as DUPLICATE.

## Review Guidelines

- **ACCEPT** — Issue is valid and the suggested fix is correct. Use for real bugs, broken behavior, missing features that block AI agents.
- **ACCEPT with improvements** — Issue is valid but the suggested fix needs refinement (describe what in Notes).
- **DEFER** — Issue is acknowledged as real but intentionally deferred — typically large architectural changes, low-priority nice-to-haves, or things that need more design.
- **WONTFIX** — Issue is acknowledged as real but will NOT be fixed. Use for: third-party behavior the tool can't control, platform-specific quirks that are impractical to fix, or intentional design decisions.
- **REJECT** — Issue is NOT valid. Use when: the reported behavior is intentional and correct, the issue misunderstands the tool's purpose, or the problem only affects human readability (not AI agents). Remember: this tool is for AI AGENTS — what looks like a UX problem to a human may be perfectly fine for an AI.
- **DUPLICATE** — Issue describes the same problem as another issue IN THIS BATCH (reference which issue number in Notes).

**Decision rules of thumb:**
- If the issue blocks or misleads an AI agent → ACCEPT or ACCEPT with improvements
- If the issue only matters for human readability → REJECT or DEFER
- If the fix requires major architectural changes → DEFER
- If the behavior is about external websites or platforms → WONTFIX
- If the issue is about development-mode friction (cargo run overhead, cd into subdirs) → WONTFIX
- If the same root cause appears in multiple issues → mark one ACCEPT, rest DUPLICATE

**CRITICAL — Notes requirement:**
- If your decision is ACCEPT, notes are OPTIONAL (can be empty or brief).
- If your decision is ANYTHING OTHER THAN ACCEPT (DEFER, WONTFIX, REJECT, DUPLICATE, ACCEPT with improvements), you MUST provide specific, actionable notes explaining WHY. Include: (1) the reason, and (2) what would need to change for a different outcome.
- Never leave notes empty for a non-ACCEPT decision.

${statsSection ? statsSection + '\n' : ''}\
${examplesSection ? examplesSection + '\n' : ''}\
${scenarioText}\
## Issues to Review (${issues.length} issues)

${issuesText}

Respond with ONLY a single JSON object (no markdown, no backticks). Include a decision for EVERY issue:
{"decisions": [
  {"issueNumber": 1, "decision": "ACCEPT", "notes": "optional for ACCEPT"},
  {"issueNumber": 2, "decision": "DEFER", "notes": "REQUIRED: explain why deferred"},
  ...
  ...
]}`;

  // Use the resilient LLM wrapper for batch review
  llm.sendPrompt(prompt, {
    timeout: 120000,
    retries: 2,  // fewer retries for batch (more expensive)
    heuristic: function() {
      // Apply per-issue heuristic for batch fallback
      const decisions = issues.map(function(iss) {
        const h = llm.heuristicDecision(iss);
        return { issueNumber: iss.numberber, decision: h.decision, notes: h.notes };
      });
      return { decisions: decisions };
    },
  }).then(function(result) {
    if (result.heuristic) {
      res.json({ decisions: result.heuristicResult.decisions, heuristic: true });
      return;
    }

    const stdout = result.stdout;
    const jsonMatch = stdout.match(/\{[\s\S]*\}/);
    if (!jsonMatch) {
      return res.status(500).json({ error: 'AI response did not contain valid JSON', raw: stdout.substring(0, 500) });
    }

    try {
      const parsed = JSON.parse(jsonMatch[0]);
      if (!parsed.decisions || !Array.isArray(parsed.decisions)) {
        return res.status(500).json({ error: 'AI response missing decisions array', raw: jsonMatch[0] });
      }
      const decisions = parsed.decisions.map(d => {
        const decision = (d.decision || 'DEFER').trim();
        let notes = (d.notes || '').trim();
        // Enforce notes for non-ACCEPT decisions
        if (decision !== 'ACCEPT' && (!notes || notes.length < 5)) {
          notes = '[AI did not provide rationale — review required] Decision: ' + decision + '. Original notes: ' + (notes || '(none)');
        }
        return { issueNumber: d.issueNumber, decision, notes };
      });
      res.json({ decisions, heuristic: false });
    } catch (e) {
      res.status(500).json({ error: 'Failed to parse AI response', raw: jsonMatch[0] });
    }
  }).catch(function(err) {
    res.status(500).json({ error: `Batch AI review failed: ${err.message}` });
  });
});

// POST /api/issue-review/ai-suggest-directory
// Reviews ALL issues across ALL .issues.md files in a directory.
// Accepts a relative directory path under issues/review/ (e.g., "2026/0708").
// Each file gets its own batch review with scenario context.
app.post('/api/issue-review/ai-suggest-directory', async (req, res) => {
  const { directory } = req.body;
  if (!directory) return res.status(400).json({ error: 'directory is required (relative path under issues/review/)' });

  const reviewRoot = path.join(TASKS_ROOT, 'issues', 'review');
  const dirPath = path.join(reviewRoot, directory);

  // Safety check
  const r = safeResolve(path.join('issues', 'review', directory), false);
  if (r.error) return res.status(r.error).json({ error: r.message });

  if (!fs.existsSync(dirPath) || !fs.statSync(dirPath).isDirectory()) {
    return res.status(404).json({ error: `Directory not found: ${directory}` });
  }

  // Find all .issues.md files recursively
  const issueFiles = [];
  function findIssueFiles(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.name.startsWith('.')) continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        findIssueFiles(full);
      } else if (entry.isFile() && /\.issues\.md$/i.test(entry.name)) {
        issueFiles.push(full);
      }
    }
  }
  findIssueFiles(dirPath);

  if (issueFiles.length === 0) {
    return res.json({ results: [], totalFiles: 0, totalIssues: 0 });
  }

  // Build stats + few-shot examples once for the entire batch
  const statsSection = reviewHistory.buildStatsSection();
  const examplesSection = reviewHistory.buildFewShotSection(8);

  const allResults = [];
  let totalIssues = 0;

  for (const filePath of issueFiles) {
    try {
      const content = fs.readFileSync(filePath, 'utf-8');
      const model = require('./frontend/issue-model.js').parseIssueFile(content);
      if (!model.issues || model.issues.length === 0) continue;

      // Build per-issue text for this file (sections already parsed by issue-model)
      let issuesText = '';
      for (const iss of model.issues) {
        const sections = iss.sections || [];

        issuesText += `### Issue ${iss.numberber}: ${iss.title}\n`;
        issuesText += `**Severity:** ${iss.severity || 'N/A'} | **Category:** ${iss.category || 'N/A'}\n\n`;
        for (const s of sections) {
          if (s.body && s.body.trim()) {
            issuesText += `**${s.label}:** ${(s.body || '').substring(0, 600)}\n\n`;
          }
        }
        issuesText += '---\n\n';
      }

      const scenarioBg = ((model.background && model.background.task || '') + '\n\n' + (model.background && model.background.executionContext || '')).substring(0, 3000);
      const fileRel = path.relative(reviewRoot, filePath).replace(/\\/g, '/');

      const prompt = `You are reviewing ALL issues from a browser4-cli evaluation scenario: "${(model.meta && model.meta.scenario) || 'Unknown'}". Review each issue and choose the best decision. Consider how issues relate to each other — if multiple issues share the same root cause, mark one as ACCEPT and the rest as DUPLICATE.

## Review Guidelines

- **ACCEPT** — Issue is valid and the suggested fix is correct.
- **ACCEPT with improvements** — Issue is valid but the suggested fix needs refinement.
- **DEFER** — Issue is real but intentionally deferred (architectural changes, needs more design).
- **WONTFIX** — Issue is real but will NOT be fixed (third-party behavior, intentional design).
- **REJECT** — Issue is NOT valid (intentional behavior, misunderstands tool's purpose, human-only concern).
- **DUPLICATE** — Issue describes the same problem as another issue IN THIS BATCH.

**Decision rules:**
- If the issue blocks or misleads an AI agent → ACCEPT or ACCEPT with improvements
- If the issue only matters for human readability → REJECT or DEFER
- If the fix requires major architectural changes → DEFER
- If the behavior is about external websites or platforms → WONTFIX

**CRITICAL — Notes requirement:**
- If your decision is ACCEPT, notes are OPTIONAL.
- For ANY other decision, you MUST provide specific notes explaining why.

${statsSection ? statsSection + '\n' : ''}\
${examplesSection ? examplesSection + '\n' : ''}\
## Scenario Background

${scenarioBg}

## Issues to Review (${model.issues.length} issues)

${issuesText}

Respond with ONLY a single JSON object (no markdown, no backticks):
{"decisions": [
  {"issueNumber": 1, "decision": "ACCEPT", "notes": "optional for ACCEPT"},
  {"issueNumber": 2, "decision": "DEFER", "notes": "REQUIRED: reason for deferral"},
  ...
]}`;

      // Use the resilient LLM wrapper for this file
      const result = await new Promise((resolve, reject) => {
        llm.sendPrompt(prompt, {
          timeout: 120000,
          retries: 2,
          heuristic: function() {
            const decisions = model.issues.map(function(iss) {
              const h = llm.heuristicDecision({
                title: iss.title,
                severity: iss.severity,
                category: iss.category,
              });
              return { issueNumber: iss.number, decision: h.decision, notes: h.notes };
            });
            return { decisions: decisions };
          },
        }).then(function(r) {
          if (r.heuristic) {
            resolve({ heuristic: true, decisions: r.heuristicResult.decisions, file: fileRel });
            return;
          }
          const stdout = r.stdout;
          const jsonMatch = stdout.match(/\{[\s\S]*\}/);
          if (!jsonMatch) {
            resolve({ heuristic: true, decisions: model.issues.map(iss => ({
              issueNumber: iss.number, decision: 'DEFER',
              notes: '[AI response unparseable — review required]',
            })), file: fileRel });
            return;
          }
          try {
            const parsed = JSON.parse(jsonMatch[0]);
            if (!parsed.decisions || !Array.isArray(parsed.decisions)) {
              resolve({ heuristic: true, decisions: model.issues.map(iss => ({
                issueNumber: iss.number, decision: 'DEFER',
                notes: '[AI response missing decisions — review required]',
              })), file: fileRel });
              return;
            }
            const decisions = parsed.decisions.map(d => {
              const decision = (d.decision || 'DEFER').trim();
              let notes = (d.notes || '').trim();
              if (decision !== 'ACCEPT' && (!notes || notes.length < 5)) {
                notes = '[AI did not provide rationale — review required] ' + decision;
              }
              return { issueNumber: d.issueNumber, decision, notes };
            });
            resolve({ heuristic: false, decisions, file: fileRel });
          } catch (e) {
            resolve({ heuristic: true, decisions: model.issues.map(iss => ({
              issueNumber: iss.number, decision: 'DEFER',
              notes: '[AI response JSON parse error — review required]',
            })), file: fileRel });
          }
        }).catch(function(err) {
          resolve({ heuristic: true, decisions: model.issues.map(iss => ({
            issueNumber: iss.number, decision: 'DEFER',
            notes: '[LLM unavailable: ' + err.message + ' — review required]',
          })), file: fileRel });
        });
      });

      allResults.push({
        file: result.file,
        scenarioName: model.scenarioName || 'Unknown',
        issueCount: model.issues.length,
        decisions: result.decisions,
        heuristic: result.heuristic,
      });
      totalIssues += model.issues.length;

    } catch (fileErr) {
      console.error(`[server] Error processing ${filePath}:`, fileErr.message);
      // Continue with next file
    }
  }

  res.json({
    results: allResults,
    totalFiles: allResults.length,
    totalIssues: totalIssues,
  });
});

// POST /api/issue-review/mark-all-done
// Marks all reviewed .issues.md files in a directory as done.
// Each file is processed: approved issues keep full detail, others condensed,
// summary goes to 1ready, original moved to review/done.
app.post('/api/issue-review/mark-all-done', (req, res) => {
  const { directory, auto_approve } = req.body;
  if (!directory) return res.status(400).json({ error: 'directory is required (relative path under issues/review/)' });

  const reviewRoot = path.join(TASKS_ROOT, 'issues', 'review');
  const dirPath = path.join(reviewRoot, directory);

  const r = safeResolve(path.join('issues', 'review', directory), false);
  if (r.error) return res.status(r.error).json({ error: r.message });

  if (!fs.existsSync(dirPath) || !fs.statSync(dirPath).isDirectory()) {
    return res.status(404).json({ error: `Directory not found: ${directory}` });
  }

  // Find all .issues.md files recursively
  const issueFiles = [];
  function findIssueFiles(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.name.startsWith('.')) continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        findIssueFiles(full);
      } else if (entry.isFile() && /\.issues\.md$/i.test(entry.name)) {
        issueFiles.push(full);
      }
    }
  }
  findIssueFiles(dirPath);

  const results = [];
  let totalApproved = 0;
  let totalCondensed = 0;

  for (const filePath of issueFiles) {
    try {
      const content = fs.readFileSync(filePath, 'utf-8');

      // Parse to check for zero issues
      const model = require('./frontend/issue-model.js').parseIssueFile(content);

      // Auto-discard zero-issue files — they shouldn't create empty tasks
      if (!model.issues || model.issues.length === 0) {
        const srcRelToReview = path.relative(reviewRoot, filePath);
        const discardDir = path.join(reviewRoot, 'done', 'discard');
        const discardPath = path.join(discardDir, srcRelToReview);
        fs.mkdirSync(path.dirname(discardPath), { recursive: true });
        fs.renameSync(filePath, discardPath);
        results.push({
          file: srcRelToReview.replace(/\\/g, '/'),
          issues: 0,
          approved: 0,
          condensed: 0,
          status: 'auto-discarded',
        });
        continue;
      }

      // Build the summary version using the same logic as mark-done
      let summaryContent = buildSummaryContent(content);

      if (auto_approve) {
        summaryContent = summaryContent.trimEnd() + '\n\n#auto-approve\n';
      }

      // Destination: coworker/tasks/main/1ready/<basename>
      const filename = path.basename(filePath);
      const readyDir = path.join(TASKS_ROOT, 'main', '1ready');
      fs.mkdirSync(readyDir, { recursive: true });
      let readyPath = path.join(readyDir, filename);
      if (fs.existsSync(readyPath)) {
        const stem = filename.replace(/\.(md|json)$/i, '');
        const ext = filename.match(/\.(md|json)$/i)?.[0] || '.md';
        for (let n = 2; n < 1000; n++) {
          const alt = path.join(readyDir, `${stem}.${n}${ext}`);
          if (!fs.existsSync(alt)) { readyPath = alt; break; }
        }
      }
      fs.writeFileSync(readyPath, summaryContent, 'utf-8');

      // Move original to issues/review/done
      const srcRelToReview = path.relative(reviewRoot, filePath);
      const doneDir = path.join(reviewRoot, 'done');
      const donePath = path.join(doneDir, srcRelToReview);
      fs.mkdirSync(path.dirname(donePath), { recursive: true });
      fs.renameSync(filePath, donePath);

      const readyRel = path.relative(TASKS_ROOT, readyPath).replace(/\\/g, '/');

      // Count decisions
      const approved = model.issues.filter(iss =>
        iss.review.decision === 'ACCEPT' || iss.review.decision === 'ACCEPT with improvements'
      ).length;
      totalApproved += approved;
      totalCondensed += model.issues.length - approved;

      results.push({
        file: srcRelToReview.replace(/\\/g, '/'),
        readyPath: readyRel,
        issues: model.issues.length,
        approved: approved,
        condensed: model.issues.length - approved,
        status: 'done',
      });
    } catch (fileErr) {
      results.push({
        file: path.relative(reviewRoot, filePath).replace(/\\/g, '/'),
        status: 'error',
        error: fileErr.message,
      });
    }
  }

  res.json({
    results,
    totalFiles: results.length,
    totalApproved,
    totalCondensed,
  });
});

// POST /api/issue-review/similar
// Find previously reviewed issues that are similar to the given title.
app.post('/api/issue-review/similar', (req, res) => {
  const { title, threshold } = req.body;
  if (!title) return res.status(400).json({ error: 'title is required' });

  const matches = reviewHistory.findSimilarIssues(title, threshold);
  res.json({ matches });
});

// GET /api/issue-review/stats
// Return aggregate review statistics from past reviews.
app.get('/api/issue-review/stats', (req, res) => {
  const stats = reviewHistory.getStats();
  const reviewedCount = reviewHistory.getReviewedCount();
  res.json({ ...stats, reviewedCount });
});

// GET /api/issue-review/health
// Check whether the LLM is reachable and the circuit breaker status.
app.get('/api/issue-review/health', async (req, res) => {
  const circuit = llm.getCircuitStatus();
  const health = await llm.checkHealth();
  const feedback = reviewHistory.getFeedbackStats();
  res.json({
    llm: health,
    circuit: circuit,
    feedback: {
      total: feedback.total,
      accuracy: feedback.accuracy,
      matches: feedback.matches,
      mismatches: feedback.mismatches,
    },
  });
});

// GET /api/issue-review/feedback
// Return detailed feedback statistics (AI vs human decision comparison).
app.get('/api/issue-review/feedback', (req, res) => {
  const stats = reviewHistory.getFeedbackStats();
  res.json(stats);
});

// ═══════════════════════════════════════════════════════════════════════════
// Log Dashboard API
// ═══════════════════════════════════════════════════════════════════════════

// ── Log source definitions ──────────────────────────────────────────────

const LOG_SOURCES = [
  { key: "1", label: "pulsar",  desc: "Root backend",         paths: ["logs/pulsar.log"] },
  { key: "2", label: "server",  desc: "Server / framework",   paths: ["logs/pulsar.s.log"] },
  { key: "3", label: "browser", desc: "Browser / CDP ops",    paths: ["logs/pulsar.bs.log"] },
  { key: "4", label: "api",     desc: "Scrape API tasks",     paths: ["logs/pulsar.api.log"] },
  { key: "5", label: "pages",   desc: "Page processing",      paths: ["logs/pulsar.pg.log"] },
  { key: "6", label: "coworker","desc": "Coworker task runner",paths: ["__coworker__"] },
  { key: "7", label: "build",   desc: "Spring Boot build",    paths: [".build/spring-boot.log"] },
  { key: "8", label: "startup", desc: "Server startup log",   paths: ["__startup__"] },
  { key: "9", label: "combined","desc": "pulsar + server + browser", paths: ["logs/pulsar.log", "logs/pulsar.s.log", "logs/pulsar.bs.log"] },
  { key: "0", label: "git",     desc: "Git log",              paths: ["__git__"] },
  { key: "r", label: "rws",     desc: "RWS test output",      paths: ["__rws__"] },
];

// ── Helpers ─────────────────────────────────────────────────────────────

function resolveRepoRoot() {
  let d = path.resolve(__dirname, '..', '..');
  while (d !== path.dirname(d)) {
    if (fs.existsSync(path.join(d, '.git'))) return d;
    d = path.dirname(d);
  }
  return path.resolve(__dirname, '..', '..');
}

const REPO_ROOT = resolveRepoRoot();

function findLatestLogFile(dir, globPattern, excludePattern) {
  if (!fs.existsSync(dir)) return null;
  const results = [];
  function walk(d) {
    if (!fs.existsSync(d)) return;
    for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
      if (entry.name.startsWith('.')) continue;
      const full = path.join(d, entry.name);
      if (entry.isDirectory()) { walk(full); }
      else {
        // Simple glob: * matches any sequence
        const regex = new RegExp('^' + globPattern.replace(/\*/g, '.*').replace(/\?/g, '.') + '$', 'i');
        if (regex.test(entry.name)) {
          if (excludePattern && new RegExp(excludePattern).test(entry.name)) continue;
          try { results.push({ path: full, mtime: fs.statSync(full).mtimeMs }); }
          catch(e) {}
        }
      }
    }
  }
  walk(dir);
  results.sort((a, b) => b.mtime - a.mtime);
  return results.length > 0 ? results[0].path : null;
}

function resolveSourcePaths(source) {
  const resolved = [];
  const sentinel = source.paths[0] || '';

  for (const p of source.paths) {
    if (p === '__coworker__') {
      const home = process.env.HOME || process.env.USERPROFILE || '.';
      const cowDir = path.join(home, '.browser4-coworker', 'tasks', '300logs');
      const latest = findLatestLogFile(cowDir, '*.log', '\\.std(out|err)$');
      if (latest) resolved.push(latest);
    } else if (p === '__startup__') {
      const tempDir = process.env.BROWSER4_SERVER_LOG_DIR ||
        path.join(process.env.HOME || process.env.USERPROFILE || '.', '.browser4', 'logs');
      const latest = findLatestLogFile(tempDir, 'browser4-server-*.log', null);
      if (latest) resolved.push(latest);
    } else if (p === '__git__' || p === '__rws__') {
      // Handled separately
      resolved.push(p);
    } else {
      resolved.push(path.join(REPO_ROOT, p));
    }
  }
  return resolved;
}

function labelFor(fp, source) {
  if (source.paths.length <= 1) return '';
  const name = path.basename(fp);
  if (name.includes('pulsar.bs')) return 'browser';
  if (name.includes('pulsar.s')) return 'server';
  if (name.includes('pulsar')) return 'pulsar';
  if (name.endsWith('.raw.md')) {
    const stem = name.replace('.raw.md', '');
    const parts = stem.split('-');
    return parts.length > 1 ? parts.slice(1).join('-') : stem;
  }
  if (name.endsWith('-progress.json')) return name.replace('-progress.json', '');
  if (name === 'test-session.json') return 'session';
  return '';
}

function readTailLines(filePath, numLines) {
  if (!fs.existsSync(filePath)) return { lines: [], size: 0 };
  try {
    const fd = fs.openSync(filePath, 'r');
    const stat = fs.statSync(filePath);
    const size = stat.size;
    if (size === 0) { fs.closeSync(fd); return { lines: [], size: 0 }; }

    // Estimate: average line ~200 bytes, read last numLines*200 bytes
    const estBytes = numLines * 200;
    const start = Math.max(0, size - estBytes);
    const buf = Buffer.alloc(size - start);
    fs.readSync(fd, buf, 0, buf.length, start);
    fs.closeSync(fd);

    let text = buf.toString('utf-8');
    // If we started mid-line, drop the partial first line
    if (start > 0) {
      const nlIdx = text.indexOf('\n');
      if (nlIdx >= 0) text = text.substring(nlIdx + 1);
    }
    const allLines = text.split(/\r?\n/);
    // Take last numLines
    const lines = allLines.slice(-numLines);
    return { lines, size };
  } catch(e) {
    return { lines: [], size: 0 };
  }
}

function readNewLines(filePath, lastPos) {
  if (!fs.existsSync(filePath)) return { lines: [], size: lastPos };
  try {
    const stat = fs.statSync(filePath);
    const currentSize = stat.size;
    if (currentSize <= lastPos) return { lines: [], size: lastPos };

    const fd = fs.openSync(filePath, 'r');
    const buf = Buffer.alloc(currentSize - lastPos);
    fs.readSync(fd, buf, 0, buf.length, lastPos);
    fs.closeSync(fd);

    const text = buf.toString('utf-8');
    const lines = text.split(/\r?\n/);
    // Remove trailing empty line from split
    if (lines.length > 0 && lines[lines.length - 1] === '') lines.pop();
    return { lines, size: currentSize };
  } catch(e) {
    return { lines: [], size: lastPos };
  }
}

// ── RWS helpers ─────────────────────────────────────────────────────────

function resolveRwsPaths() {
  const resolved = [];
  const targetDir = path.join(REPO_ROOT, 'target');
  if (fs.existsSync(targetDir)) {
    const rawFiles = [];
    for (const entry of fs.readdirSync(targetDir, { withFileTypes: true })) {
      if (entry.isFile() && entry.name.endsWith('.raw.md')) {
        try {
          rawFiles.push({ path: path.join(targetDir, entry.name), mtime: fs.statSync(path.join(targetDir, entry.name)).mtimeMs });
        } catch(e) {}
      }
    }
    rawFiles.sort((a, b) => b.mtime - a.mtime);
    for (const rf of rawFiles.slice(0, 5)) resolved.push(rf.path);
  }

  const tsDir = path.join(REPO_ROOT, '.test-sessions');
  if (fs.existsSync(tsDir)) {
    // Progress files
    const progFiles = [];
    for (const entry of fs.readdirSync(tsDir, { withFileTypes: true })) {
      if (entry.isFile() && entry.name.endsWith('-progress.json')) {
        try {
          progFiles.push({ path: path.join(tsDir, entry.name), mtime: fs.statSync(path.join(tsDir, entry.name)).mtimeMs });
        } catch(e) {}
      }
    }
    progFiles.sort((a, b) => b.mtime - a.mtime);
    for (const pf of progFiles.slice(0, 5)) resolved.push(pf.path);

    // Most recent test-session.json
    const sessionFiles = [];
    function findSessionFiles(d) {
      if (!fs.existsSync(d)) return;
      for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
        if (entry.name.startsWith('.')) continue;
        const full = path.join(d, entry.name);
        if (entry.isDirectory()) { findSessionFiles(full); }
        else if (entry.name === 'test-session.json') {
          try { sessionFiles.push({ path: full, mtime: fs.statSync(full).mtimeMs }); } catch(e) {}
        }
      }
    }
    findSessionFiles(tsDir);
    sessionFiles.sort((a, b) => b.mtime - a.mtime);
    if (sessionFiles.length > 0) resolved.push(sessionFiles[0].path);
  }

  return resolved;
}

// ── Routes ──────────────────────────────────────────────────────────────

// GET /logs — serve the log dashboard SPA
app.get('/logs', (_req, res) => {
  res.sendFile(path.join(__dirname, 'frontend', 'watch-logs.html'));
});

// POST /api/logs/tail — read tail of log files for a source
app.post('/api/logs/tail', (req, res) => {
  const { source: sourceKey, lines } = req.body;
  const numLines = parseInt(lines) || 200;

  const source = LOG_SOURCES.find(s => s.label === sourceKey || s.key === sourceKey);
  if (!source) return res.status(400).json({ error: `Unknown source: ${sourceKey}` });

  const sentinel = source.paths[0] || '';

  if (sentinel === '__rws__') {
    const rwsPaths = resolveRwsPaths();
    const allLines = [];
    const positions = {};
    for (const fp of rwsPaths) {
      const label = labelFor(fp, source);
      const result = readTailLines(fp, Math.floor(numLines / Math.max(1, rwsPaths.length)));
      for (const line of result.lines) {
        allLines.push(label ? `[${label}] ${line}` : line);
      }
      positions[fp] = result.size;
    }
    return res.json({ lines: allLines, positions, message: rwsPaths.length === 0 ? 'No RWS files found' : null });
  }

  const resolved = resolveSourcePaths(source);
  const allLines = [];
  const positions = {};

  for (const fp of resolved) {
    const label = labelFor(fp, source);
    const result = readTailLines(fp, Math.floor(numLines / Math.max(1, resolved.length)));
    for (const line of result.lines) {
      allLines.push(label ? `[${label}] ${line}` : line);
    }
    positions[fp] = result.size;
  }

  if (allLines.length === 0 && resolved.length === 0) {
    allLines.push('(no log files found)');
  }

  res.json({ lines: allLines, positions });
});

// POST /api/logs/poll — poll for new content since given positions
app.post('/api/logs/poll', (req, res) => {
  const { source: sourceKey, positions } = req.body;
  const pos = positions || {};

  const source = LOG_SOURCES.find(s => s.label === sourceKey || s.key === sourceKey);
  if (!source) return res.status(400).json({ error: `Unknown source: ${sourceKey}` });

  const sentinel = source.paths[0] || '';

  if (sentinel === '__rws__') {
    const rwsPaths = resolveRwsPaths();
    const allLines = [];
    const newPositions = {};
    // Add newly discovered files
    for (const fp of rwsPaths) {
      const lastPos = pos[fp] || 0;
      const label = labelFor(fp, source);
      const result = readNewLines(fp, lastPos);
      for (const line of result.lines) {
        allLines.push(label ? `[${label}] ${line}` : line);
      }
      newPositions[fp] = result.size;
    }
    // Keep positions for files that still exist
    for (const fp of Object.keys(pos)) {
      if (!(fp in newPositions)) newPositions[fp] = pos[fp];
    }
    return res.json({ lines: allLines, positions: newPositions });
  }

  const resolved = resolveSourcePaths(source);
  const allLines = [];
  const newPositions = {};

  for (const fp of resolved) {
    const lastPos = pos[fp] || 0;
    const label = labelFor(fp, source);
    const result = readNewLines(fp, lastPos);
    for (const line of result.lines) {
      allLines.push(label ? `[${label}] ${line}` : line);
    }
    newPositions[fp] = result.size;
  }

  res.json({ lines: allLines, positions: newPositions });
});

// GET /api/logs/git — fetch git log output
app.get('/api/logs/git', (req, res) => {
  const n = Math.min(parseInt(req.query.lines) || 50, 100);
  const detail = req.query.detail === '1';
  const now = new Date();
  const timeStr = now.toTimeString().slice(0, 5);

  let args;
  let header;
  if (detail) {
    args = ['git', '-C', REPO_ROOT, 'log',
      '--format=commit %H%d%nAuthor: %an <%ae>%nDate:   %ad%n%n    %B%n',
      '--date=local', '--all', `--max-count=${n}`];
    header = `──── git log detail (all branches, last ${n}) — ${timeStr} ────`;
  } else {
    args = ['git', '-C', REPO_ROOT, 'log',
      '--oneline', '--graph', '--all', '--decorate', `-${n}`];
    header = `──── git log (all branches, last ${n}) — ${timeStr} ────`;
  }

  execFile(args[0], args.slice(1), { timeout: 15000, maxBuffer: 1024 * 1024 }, (err, stdout) => {
    if (err) {
      return res.json({ lines: [`(git log error: ${err.message})`], header: '──── git log ────' });
    }
    const lines = (stdout || '').split(/\r?\n/);
    res.json({ lines, header });
  });
});

// ── Start server ────────────────────────────────────────────────────────

app.listen(PORT, HOST, () => {
  const url = `http://${HOST}:${PORT}`;
  console.log(`Coworker Task Manager → ${url}`);
  console.log(`Tasks root: ${TASKS_ROOT}`);

  if (OPEN_BROWSER) {
    const { exec } = require('child_process');
    const cmd = process.platform === 'win32' ? `start ${url}`
      : process.platform === 'darwin' ? `open ${url}`
      : `xdg-open ${url}`;
    exec(cmd);
  }
});
