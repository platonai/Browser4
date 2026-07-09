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
let _feedbackLog = [];      // { timestamp, issueTitle, aiDecision, humanDecision, match }

// ── Curated few-shot examples ─────────────────────────────────────────────
//
// Hand-picked exemplar issues that best illustrate each decision boundary.
// These are used in preference to random samples because they show clearer
// decision rationale than the average reviewed issue.

const CURATED_EXAMPLES = [
  // ── ACCEPT: clear bug, blocks AI agents, well-scoped fix ────────────
  {
    title: "`get all` arrays produce unaligned multi-field data",
    severity: "High",
    category: "Reliability",
    decision: "ACCEPT",
    notes: "Arrays for different fields have different lengths with no index alignment, making cross-field correlation impossible for agents. Clear data-integrity bug.",
    scenario: "amazon",
  },
  {
    title: "`htmlsnapshot capture` HTTP timeout on first attempt",
    severity: "High",
    category: "Reliability",
    decision: "ACCEPT",
    notes: "Reproducible timeout blocks agent extraction workflows. Needs retry logic or increased timeout.",
    scenario: "html-snapshot-extraction",
  },
  {
    title: "`drag` command fails with snapshot element refs",
    severity: "High",
    category: "Product",
    decision: "ACCEPT",
    notes: "The drag command silently fails when given snapshot refs — agents can't detect the failure.",
    scenario: "advanced-mouse-interaction",
  },

  // ── ACCEPT with improvements: valid but fix needs refinement ────────
  {
    title: "Relative SQL file path resolution from CLI directory is confusing",
    severity: "Low",
    category: "UX",
    decision: "ACCEPT with improvements",
    notes: "Valid issue but fix should resolve @file paths relative to repo root first, then fall back to CWD — not the other way around.",
    scenario: "amazon",
  },
  {
    title: "`htmlsnapshot query` with URL submits asynchronously without clear indication",
    severity: "Medium",
    category: "UX",
    decision: "ACCEPT with improvements",
    notes: "Real UX issue for agents, but suggested fix (blocking wait) would break async workflows. Should add a --wait flag instead.",
    scenario: "x-sql-query-methods",
  },

  // ── DEFER: real but large scope or low priority ─────────────────────
  {
    title: "`htmlsnapshot inspect` auto-discover fails on e-commerce product grids",
    severity: "High",
    category: "Reliability",
    decision: "DEFER",
    notes: "The inspect algorithm picks the first repeating element from :root, which on e-commerce pages is navigation, not products. Fixing requires refactoring the container-priority heuristic — postpone until inspect gets dedicated attention.",
    scenario: "Calabi-Yau",
  },
  {
    title: "`snapshot` vs `htmlsnapshot` — confusing two-system design for new users",
    severity: "Medium",
    category: "Discoverability",
    decision: "DEFER",
    notes: "Valid design concern but unifying two snapshot systems is an architectural change. Document the distinction clearly for now.",
    scenario: "hacker-news",
  },
  {
    title: "No automatic re-snapshot after navigation — silent ref staleness risk",
    severity: "Medium",
    category: "Reliability",
    decision: "DEFER",
    notes: "Real footgun for agents, but auto-re-snapshot would change core navigation semantics. Needs careful design.",
    scenario: "hacker-news",
  },

  // ── WONTFIX: external, platform-specific, intentional design ────────
  {
    title: "Documentation's recommended CSS selectors fail on non-English Amazon locale",
    severity: "Medium",
    category: "Documentation",
    decision: "WONTFIX",
    notes: "Amazon serves different HTML per locale. The tool cannot control upstream DOM. Document the locale-specific selector discovery workflow instead.",
    scenario: "amazon",
  },
  {
    title: "Template variables in task specification are undefined",
    severity: "Low",
    category: "Documentation",
    decision: "WONTFIX",
    notes: "Template placeholders like \$cliInvocation are evaluation-framework artifacts, not product bugs. The template system is internal.",
    scenario: "comprehensive-ecommerce-workflow",
  },
  {
    title: "Cargo build status lines pollute command output",
    severity: "Low",
    category: "UX",
    decision: "WONTFIX",
    notes: "Cargo output during dev-mode runs is expected. Production builds don't have this. Not worth adding cargo output filtering.",
    scenario: "amazon",
  },

  // ── REJECT: not a real problem, intentional, human-only concern ─────
  {
    title: "Interactive snapshot (`-i`) does not display element refs inline",
    severity: "Medium",
    category: "UX",
    decision: "REJECT",
    notes: "AI agents parse the YAML snapshot file directly — inline refs in the terminal preview are irrelevant. The -i flag is for human debugging, not agent workflows.",
    scenario: "form-filling",
  },
  {
    title: "Snapshot default output is a file path, not inline content",
    severity: "Low",
    category: "UX",
    decision: "REJECT",
    notes: "The file output is intentional — agents read the YAML file. Use --stdout for inline output. The default is correct for the primary (agent) user.",
    scenario: "attach-remote-debug",
  },

  // ── DUPLICATE: same root cause as another issue ─────────────────────
  {
    title: "`tab-new` does not auto-switch to the new tab",
    severity: "Medium",
    category: "Reliability",
    decision: "DUPLICATE",
    notes: "Same root cause as Issue 3 (stale CDP session after tab creation). Fixing the session refresh will resolve both.",
    scenario: "comprehensive-ecommerce-workflow",
  },
];

const CURATED_BY_DECISION = {};
(function _indexCurated() {
  for (const ex of CURATED_EXAMPLES) {
    if (!CURATED_BY_DECISION[ex.decision]) CURATED_BY_DECISION[ex.decision] = [];
    CURATED_BY_DECISION[ex.decision].push(ex);
  }
})();

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
 * Return curated few-shot examples for the AI review prompt.
 * Uses hand-picked exemplars that best illustrate each decision boundary,
 * with clear rationale in the notes field. Falls back to random stratified
 * samples from the review history if not enough curated examples exist.
 *
 * @param {number} count - desired number of examples (default 6)
 * @returns {Array<{title, severity, category, decision, notes, scenario}>}
 */
function getFewShotExamples(count) {
  _ensureLoaded();
  count = count || 6;

  // Use curated examples first — they have better rationale
  const selected = [];

  // Ensure at least one per decision type from curated set
  const decisions = ['ACCEPT', 'ACCEPT with improvements', 'DEFER', 'WONTFIX', 'REJECT', 'DUPLICATE'];
  for (const dec of decisions) {
    const curated = CURATED_BY_DECISION[dec] || [];
    if (curated.length > 0) {
      selected.push(curated[0]);
    }
  }

  // Fill up to count from remaining curated examples
  for (const ex of CURATED_EXAMPLES) {
    if (selected.length >= count) break;
    if (!selected.includes(ex)) {
      selected.push(ex);
    }
  }

  // If still not enough, supplement from review history
  if (selected.length < count) {
    const usedIds = new Set(selected.map(e => e.title));
    const reviewed = _cache.allIssues.filter(i => i.decision && i.notes && i.notes.trim().length > 0);
    for (const iss of reviewed) {
      if (selected.length >= count) break;
      const id = iss.title;
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

  return selected.slice(0, count);
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

// ── Feedback loop ─────────────────────────────────────────────────────────

/**
 * Record a feedback entry comparing an AI-suggested decision against the
 * final human decision. Used to track AI review calibration over time.
 *
 * @param {string} issueTitle
 * @param {string} aiDecision   - the decision the AI suggested
 * @param {string} humanDecision - the final human decision (after review)
 */
function recordFeedback(issueTitle, aiDecision, humanDecision) {
  const entry = {
    timestamp: new Date().toISOString(),
    issueTitle,
    aiDecision,
    humanDecision,
    match: aiDecision === humanDecision,
  };
  _feedbackLog.push(entry);

  // Log to stderr for operational visibility
  const icon = entry.match ? '✓' : '✗';
  console.error(`[review-history] Feedback ${icon}: AI="${aiDecision}" Human="${humanDecision}" — "${issueTitle.substring(0, 80)}"`);

  return entry;
}

/**
 * Get feedback statistics for calibration monitoring.
 * @returns {{ total, matches, mismatches, accuracy, byAiDecision, recent }}
 */
function getFeedbackStats() {
  const total = _feedbackLog.length;
  const matches = _feedbackLog.filter(e => e.match).length;
  const mismatches = total - matches;

  // Accuracy by AI decision type
  const byAiDecision = {};
  for (const e of _feedbackLog) {
    if (!byAiDecision[e.aiDecision]) {
      byAiDecision[e.aiDecision] = { total: 0, correct: 0 };
    }
    byAiDecision[e.aiDecision].total++;
    if (e.match) byAiDecision[e.aiDecision].correct++;
  }

  return {
    total,
    matches,
    mismatches,
    accuracy: total > 0 ? Math.round((matches / total) * 100) : null,
    byAiDecision,
    recent: _feedbackLog.slice(-20),
  };
}

/** Clear the feedback log (e.g., for testing). */
function clearFeedback() {
  _feedbackLog = [];
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
  recordFeedback,
  getFeedbackStats,
  clearFeedback,
  CURATED_EXAMPLES,
  CURATED_BY_DECISION,
};
