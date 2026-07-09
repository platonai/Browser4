/**
 * review-history.js — Review history index for AI-assisted issue review.
 *
 * Scans issues/review/done/ for all .issues.md files, parses them with
 * issue-model.js, and builds an in-memory index that supports:
 *
 *   - getStats()              → aggregate decision stats by severity & category
 *   - getFewShotExamples(n)   → stratified sample of past reviewed issues
 *   - findSimilarIssues(title)→ token-overlap similarity against past titles
 *   - getReviewedIssues()     → flat array of all reviewed issues
 *
 * The index is built on first access (lazy) and cached for the server lifetime.
 * Call invalidate() to force a rebuild.
 */

'use strict';

const path = require('path');
const fs = require('fs');

// issue-model.js exports itself for both browser (ISSUE_MODEL global) and
// CommonJS (module.exports).  The JS uses var declarations which are fine
// under Node's require().
const ISSUE_MODEL = require('./frontend/issue-model.js');

// Default tasks root — relative to this script: ../tasks/
const DEFAULT_TASKS_ROOT = path.resolve(__dirname, '..', 'tasks');

// ── Stopword list for title tokenisation ──────────────────────────────────

const STOPWORDS = new Set([
  'a', 'an', 'the', 'is', 'are', 'was', 'were', 'be', 'been', 'being',
  'have', 'has', 'had', 'do', 'does', 'did', 'will', 'would', 'could',
  'should', 'may', 'might', 'can', 'shall', 'to', 'of', 'in', 'for',
  'on', 'with', 'at', 'by', 'from', 'as', 'into', 'through', 'during',
  'before', 'after', 'above', 'below', 'between', 'under', 'again',
  'further', 'then', 'once', 'here', 'there', 'when', 'where', 'why',
  'how', 'all', 'both', 'each', 'few', 'more', 'most', 'other', 'some',
  'such', 'no', 'nor', 'not', 'only', 'own', 'same', 'so', 'than',
  'too', 'very', 'just', 'now', 'also', 'this', 'that', 'these', 'those',
  'it', 'its', 'or', 'and', 'but', 'if', 'because', 'until', 'while',
  'about', 'up', 'out', 'down', 'off', 'over', 'doesn', 'don', 'doesnt',
  'dont', 'isnt', 'cant', 'cannot', 'without', 'within', 'along',
  'following', 'across', 'behind', 'beyond', 'plus', 'via', 'per',
  'using', 'used', 'rather', 'instead', 'already', 'yet', 'still',
]);

// ── Internal state ────────────────────────────────────────────────────────

let _tasksRoot = null;
let _cache = null;          // { stats, byDecision, byCategory, bySeverity, allIssues, titles }

// ── Public API ────────────────────────────────────────────────────────────

/**
 * Initialise (or re-initialise) the index from the given tasks root.
 * Call once on server startup.  Scans immediately.
 */
function init(tasksRoot) {
  _tasksRoot = tasksRoot || DEFAULT_TASKS_ROOT;
  _cache = null;
  _ensureLoaded();
  return _cache;
}

/** Drop the cache so the next access rebuilds from disk. */
function invalidate() {
  _cache = null;
}

// ── Accessors ─────────────────────────────────────────────────────────────

/** Return aggregate decision statistics. */
function getStats() {
  _ensureLoaded();
  return _cache.stats;
}

/**
 * Return a stratified sample of reviewed issues for few-shot prompting.
 * @param {number} count - desired number of examples (default 5)
 * @returns {Array<{title, severity, category, decision, notes, scenario}>}
 */
function getFewShotExamples(count) {
  _ensureLoaded();
  count = count || 5;

  // Stratify: pick examples proportionally from each decision bucket
  const decisions = ['ACCEPT', 'DEFER', 'WONTFIX', 'REJECT', 'ACCEPT with improvements', 'DUPLICATE'];
  const selected = [];
  const usedIds = new Set();

  // Calculate how many to pick per decision (at least 1 for common decisions)
  const totalReviewed = _cache.allIssues.filter(i => i.decision).length;
  for (const dec of decisions) {
    const bucket = (_cache.byDecision[dec] || [])
      .filter(i => i.decision === dec && i.notes && i.notes.trim().length > 0); // prefer examples with notes
    const bucketAll = (_cache.byDecision[dec] || []).filter(i => i.decision === dec);

    // Prefer examples with notes, fall back to any
    const pool = bucket.length > 0 ? bucket : bucketAll;
    if (pool.length === 0) continue;

    // Proportional allocation (minimum 1 for decisions with >5% share)
    const share = bucketAll.length / Math.max(totalReviewed, 1);
    const alloc = share > 0.05 ? Math.max(1, Math.round(count * share)) : 0;
    const n = Math.min(alloc, pool.length);

    for (let i = 0; i < n; i++) {
      const cand = pool[i];
      const id = cand.scenario + '|' + cand.title;
      if (!usedIds.has(id)) {
        usedIds.add(id);
        selected.push({
          title: cand.title,
          severity: cand.severity,
          category: cand.category,
          decision: cand.decision,
          notes: (cand.notes || '').substring(0, 200),
          scenario: cand.scenario,
        });
      }
    }
  }

  // If we didn't get enough, fill with random picks
  if (selected.length < count) {
    for (const iss of _cache.allIssues) {
      if (selected.length >= count) break;
      if (!iss.decision) continue;
      const id = iss.scenario + '|' + iss.title;
      if (!usedIds.has(id)) {
        usedIds.add(id);
        selected.push({
          title: iss.title,
          severity: iss.severity,
          category: iss.category,
          decision: iss.decision,
          notes: (iss.notes || '').substring(0, 200),
          scenario: iss.scenario,
        });
      }
    }
  }

  return selected;
}

/**
 * Find previously reviewed issues similar to the given title.
 * Uses Jaccard similarity on tokenised titles.
 *
 * @param {string} title - the issue title to compare
 * @param {number} threshold - minimum similarity score (0-1), default 0.35
 * @returns {Array<{issue, score}>} matches sorted by score descending
 */
function findSimilarIssues(title, threshold) {
  _ensureLoaded();
  threshold = threshold || 0.30;
  const tokens = tokenize(title);
  if (tokens.length === 0) return [];

  const results = [];
  for (const cand of _cache.titles) {
    const score = jaccardSimilarity(tokens, cand.tokens);
    if (score >= threshold) {
      results.push({
        issue: {
          title: cand.title,
          severity: cand.severity,
          category: cand.category,
          decision: cand.decision,
          notes: cand.notes,
          scenario: cand.scenario,
          date: cand.date,
        },
        score: Math.round(score * 100) / 100,
      });
    }
  }

  results.sort((a, b) => b.score - a.score);
  return results.slice(0, 10);
}

/** Return all reviewed issues (for custom queries). */
function getReviewedIssues() {
  _ensureLoaded();
  return _cache.allIssues;
}

/** Return the total count of reviewed issues. */
function getReviewedCount() {
  _ensureLoaded();
  return _cache.allIssues.filter(i => i.decision).length;
}

// ── Build helpers ─────────────────────────────────────────────────────────

function _ensureLoaded() {
  if (_cache) return;
  _cache = _buildIndex(_tasksRoot || DEFAULT_TASKS_ROOT);
}

function _buildIndex(tasksRoot) {
  const reviewDoneDir = path.join(tasksRoot, 'issues', 'review', 'done');
  const allIssues = [];
  const byDecision = {};   // "ACCEPT" → [issue, ...]
  const bySeverity = {};   // "High" → [issue, ...]
  const byCategory = {};   // "Product" → [issue, ...]
  const titles = [];       // { title, tokens, decision, ... } for similarity

  if (!fs.existsSync(reviewDoneDir)) {
    return _emptyCache();
  }

  // Recursively find all .issues.md files
  const files = _findIssueFiles(reviewDoneDir);
  if (files.length === 0) {
    return _emptyCache();
  }

  for (const filePath of files) {
    try {
      const content = fs.readFileSync(filePath, 'utf-8');
      const model = ISSUE_MODEL.parseIssueFile(content);

      for (const issue of model.issues) {
        const decision = issue.review.decision || null;
        const entry = {
          title: issue.title,
          severity: issue.severity || 'N/A',
          category: issue.category || 'N/A',
          decision: decision,
          notes: issue.review.notes || '',
          scenario: model.meta.scenario || '',
          source: model.meta.source || '',
          date: model.meta.date || '',
          sections: issue.sections || [],
        };

        allIssues.push(entry);

        // Index by decision
        const decKey = decision || 'UNREVIEWED';
        if (!byDecision[decKey]) byDecision[decKey] = [];
        byDecision[decKey].push(entry);

        // Index by severity
        const sev = issue.severity || 'N/A';
        if (!bySeverity[sev]) bySeverity[sev] = [];
        bySeverity[sev].push(entry);

        // Index by category
        const cat = issue.category || 'N/A';
        if (!byCategory[cat]) byCategory[cat] = [];
        byCategory[cat].push(entry);

        // Index for similarity search
        titles.push({
          title: issue.title,
          tokens: tokenize(issue.title),
          severity: issue.severity || '',
          category: issue.category || '',
          decision: decision,
          notes: issue.review.notes || '',
          scenario: model.meta.scenario || '',
          date: model.meta.date || '',
        });
      }
    } catch (e) {
      // Skip unparseable files silently — they're likely malformed
      console.error(`[review-history] Failed to parse ${filePath}: ${e.message}`);
    }
  }

  // Build aggregate stats
  const stats = _buildStats(allIssues, byDecision, bySeverity, byCategory);

  return { stats, byDecision, byCategory, bySeverity, allIssues, titles };
}

function _findIssueFiles(dir) {
  const results = [];
  try {
    const entries = fs.readdirSync(dir, { withFileTypes: true });
    for (const ent of entries) {
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) {
        // Skip the "discard" subdirectory
        if (ent.name === 'discard') continue;
        results.push(..._findIssueFiles(full));
      } else if (ent.isFile() && ent.name.endsWith('.issues.md')) {
        results.push(full);
      }
    }
  } catch (e) {
    // Directory doesn't exist or isn't readable
  }
  return results;
}

function _buildStats(allIssues, byDecision, bySeverity, byCategory) {
  const reviewed = allIssues.filter(i => i.decision);
  const total = allIssues.length;
  const totalReviewed = reviewed.length;

  // Decision distribution
  const decisionDist = {};
  for (const [dec, items] of Object.entries(byDecision)) {
    if (dec === 'UNREVIEWED') continue;
    decisionDist[dec] = {
      count: items.length,
      pct: totalReviewed > 0 ? Math.round((items.length / totalReviewed) * 100) : 0,
    };
  }

  // Severity × Decision matrix
  const sevDec = {};
  for (const [sev, items] of Object.entries(bySeverity)) {
    sevDec[sev] = { total: items.length };
    const reviewedItems = items.filter(i => i.decision);
    for (const item of reviewedItems) {
      sevDec[sev][item.decision] = (sevDec[sev][item.decision] || 0) + 1;
    }
  }

  // Category × Decision matrix
  const catDec = {};
  for (const [cat, items] of Object.entries(byCategory)) {
    catDec[cat] = { total: items.length };
    const reviewedItems = items.filter(i => i.decision);
    for (const item of reviewedItems) {
      catDec[cat][item.decision] = (catDec[cat][item.decision] || 0) + 1;
    }
  }

  return {
    total,
    totalReviewed,
    unreviewed: total - totalReviewed,
    decisionDistribution: decisionDist,
    bySeverity: sevDec,
    byCategory: catDec,
  };
}

function _emptyCache() {
  return {
    stats: { total: 0, totalReviewed: 0, unreviewed: 0, decisionDistribution: {}, bySeverity: {}, byCategory: {} },
    byDecision: {},
    byCategory: {},
    bySeverity: {},
    allIssues: [],
    titles: [],
  };
}

// ── Similarity helpers ────────────────────────────────────────────────────

/**
 * Tokenise a title string into a set of lowercase keywords.
 * Splits on non-alphanumeric characters, removes stopwords and short tokens.
 */
function tokenize(text) {
  if (!text) return [];
  return text.toLowerCase()
    .replace(/[^a-z0-9\s]/g, ' ')
    .split(/\s+/)
    .filter(t => t.length > 2 && !STOPWORDS.has(t));
}

/**
 * Jaccard similarity between two token arrays.
 *   score = |intersection| / |union|
 */
function jaccardSimilarity(tokensA, tokensB) {
  if (tokensA.length === 0 || tokensB.length === 0) return 0;
  const setA = new Set(tokensA);
  const setB = new Set(tokensB);

  let intersection = 0;
  for (const t of setA) {
    if (setB.has(t)) intersection++;
  }

  const union = setA.size + setB.size - intersection;
  return union > 0 ? intersection / union : 0;
}

// ── Prompt-building helpers ───────────────────────────────────────────────

/**
 * Build the "Historical Decision Patterns" section for the AI review prompt.
 */
function buildStatsSection() {
  const stats = getStats();
  if (stats.totalReviewed === 0) return '';

  let section = '## Historical Decision Patterns\n\n';
  section += `From **${stats.totalReviewed}** previously reviewed issues:\n\n`;

  // Overall distribution
  section += '**Overall:** ';
  const distParts = [];
  for (const [dec, info] of Object.entries(stats.decisionDistribution)) {
    distParts.push(`${dec}: ${info.pct}%`);
  }
  section += distParts.join(' | ') + '\n\n';

  // By severity
  section += '**By Severity:**\n';
  for (const [sev, info] of Object.entries(stats.bySeverity)) {
    const parts = [];
    for (const [dec, count] of Object.entries(info)) {
      if (dec === 'total') continue;
      const pct = Math.round((count / info.total) * 100);
      parts.push(`${dec} ${pct}%`);
    }
    section += `- **${sev}**: ${parts.join(', ')} (${info.total} issues)\n`;
  }
  section += '\n';

  // By category
  section += '**By Category:**\n';
  for (const [cat, info] of Object.entries(stats.byCategory)) {
    const parts = [];
    for (const [dec, count] of Object.entries(info)) {
      if (dec === 'total') continue;
      const pct = Math.round((count / info.total) * 100);
      parts.push(`${dec} ${pct}%`);
    }
    section += `- **${cat}**: ${parts.join(', ')} (${info.total} issues)\n`;
  }

  return section;
}

/**
 * Build the "Examples of Past Review Decisions" section.
 */
function buildFewShotSection(count) {
  const examples = getFewShotExamples(count);
  if (examples.length === 0) return '';

  let section = '## Examples of Past Review Decisions\n\n';
  for (let i = 0; i < examples.length; i++) {
    const ex = examples[i];
    section += `### Example ${i + 1}\n`;
    section += `- **Issue:** "${ex.title}"\n`;
    section += `- **Severity:** ${ex.severity} | **Category:** ${ex.category}\n`;
    section += `- **Decision:** ${ex.decision}\n`;
    if (ex.notes) {
      section += `- **Reviewer Notes:** ${ex.notes}\n`;
    }
    section += '\n';
  }
  return section;
}

/**
 * Build the "Potentially Duplicate Past Issues" section.
 */
function buildSimilarSection(title) {
  const similar = findSimilarIssues(title);
  if (similar.length === 0) return '';

  let section = '## Potentially Duplicate Past Issues\n\n';
  section += 'The following previously-reviewed issues are similar. Consider marking as DUPLICATE if they describe the same problem:\n\n';
  for (const match of similar) {
    section += `- **"${match.issue.title}"** (${match.issue.severity}, ${match.issue.category}) — was **${match.issue.decision}** (similarity: ${match.score})\n`;
  }
  section += '\n';
  return section;
}

/**
 * Build the full enriched system prompt components for a review.
 * Returns an object with { statsSection, examplesSection, similarSection }
 * that the caller can splice into the prompt sent to claude.
 */
function buildEnrichedContext(issueTitle) {
  return {
    statsSection: buildStatsSection(),
    examplesSection: buildFewShotSection(5),
    similarSection: issueTitle ? buildSimilarSection(issueTitle) : '',
  };
}

// ── Exports ───────────────────────────────────────────────────────────────

module.exports = {
  init,
  invalidate,
  getStats,
  getFewShotExamples,
  findSimilarIssues,
  getReviewedIssues,
  getReviewedCount,
  buildStatsSection,
  buildFewShotSection,
  buildSimilarSection,
  buildEnrichedContext,
};
