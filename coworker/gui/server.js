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
  // GitHub issues pipeline
  { id: '200issues/draft/refine/0ready',  display_name: 'Issues Ready',    path_suffix: '200issues/draft/refine/0ready',  date_stamped: false, group: 'issues', hidden: false },
  { id: '200issues/draft/refine/1working', display_name: 'Issues Working', path_suffix: '200issues/draft/refine/1working', date_stamped: false, group: 'issues', hidden: false },
  { id: '200issues/draft/refine/2done',   display_name: 'Issues Done',     path_suffix: '200issues/draft/refine/2done',   date_stamped: true,  group: 'issues', hidden: false },
  { id: '200issues/draft/refine/0error',  display_name: 'Issues Errors',   path_suffix: '200issues/draft/refine/0error',  date_stamped: false, group: 'issues', hidden: false },
  { id: '200issues/github/commit/ready',  display_name: 'GH Commit Ready', path_suffix: '200issues/github/commit/ready',  date_stamped: false, group: 'issues', hidden: false },
  { id: '200issues/github/commit/done',   display_name: 'GH Committed',    path_suffix: '200issues/github/commit/done',   date_stamped: false, group: 'issues', hidden: false },
  { id: '200issues/github/commit/failed', display_name: 'GH Failed',       path_suffix: '200issues/github/commit/failed', date_stamped: false, group: 'issues', hidden: false },
];

const stageById = Object.fromEntries(STAGES.map(s => [s.id, s]));
const visibleStages = STAGES.filter(s => !s.hidden);

// ── Valid move transitions ───────────────────────────────────────────────
// Each key is a source stage ID; its value is an array of valid target stage IDs.
// Moving to any stage NOT in the list is rejected by the server.
const VALID_TRANSITIONS = {
  // Main pipeline
  '0draft':       ['1ready', '0draft/refine/0draft', '200issues/draft/refine/0ready'],
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
  '200issues/draft/refine/0ready':  ['200issues/draft/refine/1working', '0draft'],
  '200issues/draft/refine/1working': ['200issues/draft/refine/2done', '200issues/github/commit/ready', '200issues/draft/refine/0error'],
  '200issues/draft/refine/2done':   ['200issues/github/commit/ready', '0draft'],
  '200issues/draft/refine/0error':  ['200issues/draft/refine/0ready', '0draft'],
  '200issues/github/commit/ready':  ['200issues/github/commit/done', '200issues/github/commit/failed', '200issues/draft/refine/2done'],
  '200issues/github/commit/done':   [],
  '200issues/github/commit/failed': ['200issues/github/commit/ready', '0draft'],
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

// Serve frontend
app.get('/', (_req, res) => {
  res.sendFile(path.join(__dirname, 'frontend', 'index.html'));
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
