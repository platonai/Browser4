/**
 * issue-model.js — Shared structured representation for browser4-cli issues.
 *
 * Used by both the Coworker Task Manager GUI and the Issue Review SPA.
 * PowerShell counterpart: ConvertTo-IssueJson / ConvertFrom-IssueJson in
 * browser4-tests/real-world-scenarios/scripts/common.ps1.
 *
 * ## Canonical schema (JSON):
 *
 * {
 *   "meta": {
 *     "scenario": "form-filling",            // short name
 *     "source": "20260706-...full.md",       // source .full.md filename
 *     "date": "20260706-203229",             // timestamp
 *     "mode": "dev"                          // "dev" | "production"
 *   },
 *   "background": {
 *     "task": "...",                         // Section A summary
 *     "executionContext": "..."              // Section B trace (markdown)
 *   },
 *   "issues": [
 *     {
 *       "number": 1,
 *       "title": "Interactive snapshot...",
 *       "severity": "Medium",                // Critical | High | Medium | Low
 *       "category": "UX",                    // Product | Documentation | UX | Reliability | Discoverability
 *       "sections": [
 *         { "label": "Reproduction",         "body": "`cargo run -- snapshot -i`" },
 *         { "label": "Expected Behavior",    "body": "..." },
 *         { "label": "Actual Behavior",      "body": "..." },
 *         { "label": "Root Cause Analysis",  "body": "..." },
 *         { "label": "Code Pointer",         "body": "..." },
 *         { "label": "AI Suggested Improvement", "body": "..." }
 *       ],
 *       "review": {
 *         "decision": null,                  // null | "ACCEPT" | "ACCEPT with improvements" | "DEFER" | "WONTFIX" | "REJECT"
 *         "notes": ""                        // free-text review notes
 *       }
 *     }
 *   ]
 * }
 *
 * ## Section labels (canonical order, matching common.ps1 Write-IssuesToReadyQueue):
 *
 *   Reproduction → Expected Behavior → Actual Behavior →
 *   Root Cause Analysis → Code Pointer → AI Suggested Improvement
 */

var ISSUE_MODEL = (function() {
  'use strict';

  // Canonical section labels in display order
  var SECTION_LABELS = [
    'Reproduction',
    'Expected Behavior',
    'Actual Behavior',
    'Root Cause Analysis',
    'Code Pointer',
    'AI Suggested Improvement'
  ];

  // Valid review decisions
  var DECISIONS = [
    'ACCEPT',
    'ACCEPT with improvements',
    'DEFER',
    'WONTFIX',
    'REJECT'
  ];

  // ── Parse markdown → structured object ──────────────────────────────────

  /**
   * Parse a .issues.md file into the canonical structured representation.
   * @param {string} content - Raw markdown content
   * @returns {object} Canonical issue file object
   */
  function parseIssueFile(content) {
    if (!content) return emptyResult();

    var result = emptyResult();

    // Parse meta line: > **Source:** `file.full.md` | **Date:** ts | **Mode:** mode
    var metaMatch = content.match(/>\s*\*\*Source:\*\*\s*`([^`]+)`\s*\|\s*\*\*Date:\*\*\s*(\S+)\s*\|\s*\*\*Mode:\*\*\s*(\S+)/);
    if (metaMatch) {
      result.meta.source = metaMatch[1];
      result.meta.date = metaMatch[2];
      result.meta.mode = metaMatch[3];
    }

    // Parse scenario name from the first heading
    var titleMatch = content.match(/^# Issues:\s*(.+)/m);
    if (titleMatch) {
      result.meta.scenario = titleMatch[1].trim();
    }

    // Find "## Issues Found" header
    var issuesIdx = content.search(/^## Issues Found/m);
    var beforeIssues = issuesIdx >= 0 ? content.substring(0, issuesIdx) : content;
    var afterIssues = issuesIdx >= 0 ? content.substring(issuesIdx) : '';

    // Parse background sections
    var bgTaskMatch = beforeIssues.match(/### Task\n([\s\S]*?)(?=\n###\s|\n---|$)/);
    if (bgTaskMatch) {
      result.background.task = bgTaskMatch[1].trim();
    }
    var bgExecMatch = beforeIssues.match(/### Execution Context\n([\s\S]*?)(?=\n---|$)/);
    if (bgExecMatch) {
      result.background.executionContext = bgExecMatch[1].trim();
    }

    // Parse individual issues
    if (afterIssues) {
      var blocks = splitIssueBlocks(afterIssues);
      for (var i = 0; i < blocks.length; i++) {
        var issue = parseIssueBlock(blocks[i]);
        if (issue) result.issues.push(issue);
      }
    }

    // Store original content for reconstruction
    result._originalContent = content;

    return result;
  }

  function emptyResult() {
    return {
      meta: { scenario: '', source: '', date: '', mode: 'dev' },
      background: { task: '', executionContext: '' },
      issues: [],
      _originalContent: ''
    };
  }

  function splitIssueBlocks(section) {
    var blocks = [];
    var lines = section.split('\n');
    var current = [];
    var started = false;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (/^### Issue \d+:/i.test(line)) {
        if (started && current.length > 0) blocks.push(current.join('\n'));
        current = [line];
        started = true;
      } else if (started) {
        // Stop at "## How to Reproduce" section
        if (/^## How to Reproduce/.test(line)) break;
        current.push(line);
      }
    }
    if (started && current.length > 0) blocks.push(current.join('\n'));
    return blocks;
  }

  function parseIssueBlock(block) {
    var lines = block.split('\n');
    var titleMatch = lines[0].match(/^### Issue (\d+):\s*(.+)/);
    if (!titleMatch) return null;

    var issue = {
      number: parseInt(titleMatch[1], 10),
      title: titleMatch[2].trim(),
      severity: '',
      category: '',
      sections: [],
      review: { decision: null, notes: '' }
    };

    // Extract severity/category from bold labels
    for (var i = 1; i < Math.min(lines.length, 5); i++) {
      var sevMatch = lines[i].match(/^\*\*Severity:\*\*\s*(.+)/);
      var catMatch = lines[i].match(/^\*\*Category:\*\*\s*(.+)/);
      if (sevMatch) issue.severity = sevMatch[1].trim();
      if (catMatch) issue.category = catMatch[1].trim();
    }

    // Find Human Review section boundary
    var reviewIdx = -1;
    for (var j = 0; j < lines.length; j++) {
      if (/^#### Human Review/.test(lines[j])) { reviewIdx = j; break; }
    }

    // Everything between metadata and Human Review goes into sections
    var contentEnd = reviewIdx >= 0 ? reviewIdx : lines.length;
    var bodyLines = lines.slice(1, contentEnd);
    issue.sections = parseSections(bodyLines);

    // Parse review state
    if (reviewIdx >= 0) {
      for (var k = reviewIdx; k < lines.length; k++) {
        var decMatch = lines[k].match(/^- \[x\] \*\*(ACCEPT|ACCEPT with improvements|DEFER|WONTFIX|REJECT)\*\*/);
        if (decMatch) { issue.review.decision = decMatch[1]; break; }
      }
      // Parse notes
      var notesIdx = -1;
      for (var m = reviewIdx; m < lines.length; m++) {
        if (/^\*\*Notes:\*\*/.test(lines[m])) { notesIdx = m; break; }
      }
      if (notesIdx >= 0) {
        var notesLines = [];
        for (var n = notesIdx + 1; n < lines.length; n++) {
          if (/^---\s*$/.test(lines[n])) break;
          notesLines.push(lines[n]);
        }
        issue.review.notes = notesLines.join('\n').trim();
      }
    }

    return issue;
  }

  /**
   * Parse sections from body lines.  Lines before the first #### header
   * are collected under the "Overview" label.  Subsequent #### headers
   * become section labels.
   */
  function parseSections(lines) {
    var sections = [];
    var currentLabel = 'Overview';
    var currentBody = [];

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var h4Match = line.match(/^#### (.+)/);
      if (h4Match) {
        var body = currentBody.join('\n').trim();
        if (body) sections.push({ label: currentLabel, body: body });
        currentLabel = h4Match[1].trim();
        currentBody = [];
      } else {
        currentBody.push(line);
      }
    }
    var body = currentBody.join('\n').trim();
    if (body) sections.push({ label: currentLabel, body: body });

    return sections;
  }

  // ── Structured object → markdown ────────────────────────────────────────

  /**
   * Reconstruct a .issues.md file from the canonical object, updating
   * review decisions and notes.
   */
  function reconstructMarkdown(model) {
    var orig = model._originalContent || '';
    if (!orig) return buildMarkdown(model);

    // For each issue, update the Human Review section in the original content
    for (var i = 0; i < model.issues.length; i++) {
      orig = updateIssueReviewInContent(orig, model.issues[i]);
    }
    return orig;
  }

  function updateIssueReviewInContent(content, issue) {
    // Find the issue block
    var pattern = new RegExp('^### Issue ' + issue.number + ':.+', 'm');
    var start = content.search(pattern);
    if (start < 0) return content;

    // Find next issue or end
    var nextPattern = /^### Issue \d+:/gm;
    nextPattern.lastIndex = start + 1;
    var nextMatch = nextPattern.exec(content);
    var end = nextMatch ? nextMatch.index : content.length;

    // Check for "## How to Reproduce" boundary
    var howTo = content.indexOf('\n## How to Reproduce', start);
    if (howTo >= 0 && howTo < end) end = howTo;

    var block = content.substring(start, end);
    var hrMatch = block.match(/#### Human Review\n([\s\S]*?)(?=\n---\n|\n## |$)/);
    if (!hrMatch) return content;

    var hrStartInBlock = block.indexOf('#### Human Review\n');
    var hrStart = start + hrStartInBlock;
    var hrEnd = hrStart + '#### Human Review\n'.length + hrMatch[1].length;

    // Build replacement
    var newHR = '#### Human Review\n\n';
    for (var d = 0; d < DECISIONS.length; d++) {
      var checked = (issue.review.decision === DECISIONS[d]) ? '[x]' : '[ ]';
      newHR += '- ' + checked + ' **' + DECISIONS[d] + '**';
      if (d === 0) newHR += ' — issue confirmed valid; suggested improvement is correct';
      else if (d === 1) newHR += ' — issue valid but fix needs refinement (add details in Notes)';
      else if (d === 2) newHR += ' — issue acknowledged but intentionally deferred (add rationale in Notes)';
      else if (d === 3) newHR += ' — issue acknowledged but will not be fixed (add rationale in Notes)';
      else if (d === 4) newHR += ' — issue invalid, not a problem, or already addressed';
      newHR += '\n';
    }
    newHR += '- **Notes:**';
    if (issue.review.notes && issue.review.notes.trim()) {
      newHR += '\n' + issue.review.notes.trim();
    }
    newHR += '\n';

    return content.substring(0, hrStart) + newHR + content.substring(hrEnd);
  }

  /**
   * Build a fresh .issues.md file from the model (fallback when no original).
   */
  function buildMarkdown(model) {
    var m = model;
    var out = '# Issues: ' + (m.meta.scenario || 'unknown') + '\n\n';
    out += '> **Source:** `' + (m.meta.source || '') + '` | ';
    out += '**Date:** ' + (m.meta.date || '') + ' | ';
    out += '**Mode:** ' + (m.meta.mode || 'dev') + '\n\n';

    if (m.background.task) {
      out += '## Scenario Background\n\n### Task\n\n' + m.background.task + '\n\n';
    }
    if (m.background.executionContext) {
      out += '### Execution Context\n\n' + m.background.executionContext + '\n\n';
    }
    out += '---\n\n';

    if (m.issues.length > 0) {
      out += '## Issues Found (' + m.issues.length + ' issue' + (m.issues.length !== 1 ? 's' : '') + ')\n\n';
      for (var i = 0; i < m.issues.length; i++) {
        out += buildIssueMarkdown(m.issues[i]);
      }
      out += '## How to Reproduce\n\n';
      out += '### Common Setup\n\n';
      out += '1. Clone the repository and `cd` to the repo root.\n';
      out += '2. Build the CLI: `cd cli/browser4-cli && cargo build`\n';
      out += '3. The backend server starts automatically in dev mode.\n';
      out += '4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`\n\n';
      out += '### Per-Issue Reproduction Steps\n\n';
      for (var j = 0; j < m.issues.length; j++) {
        var iss = m.issues[j];
        var reproSection = findSection(iss, 'Reproduction');
        out += '#### Issue ' + iss.number + ': ' + iss.title + '\n\n';
        out += (reproSection ? reproSection.body : '(No reproduction steps recorded)') + '\n\n';
      }
    } else {
      out += '## Issues Found (0)\n\nNo issues could be parsed.\n\n';
      if (m.meta.source) {
        out += 'See `' + m.meta.source + '` for the complete evaluation output.\n\n';
      }
    }

    return out;
  }

  function buildIssueMarkdown(issue) {
    var out = '### Issue ' + issue.number + ': ' + issue.title + '\n\n';
    out += '**Severity:** ' + (issue.severity || 'N/A') + '\n';
    out += '**Category:** ' + (issue.category || 'N/A') + '\n\n';

    for (var i = 0; i < issue.sections.length; i++) {
      var s = issue.sections[i];
      out += '#### ' + s.label + '\n\n' + s.body + '\n\n';
    }

    out += '#### Human Review\n\n';
    for (var d = 0; d < DECISIONS.length; d++) {
      var checked = (issue.review.decision === DECISIONS[d]) ? '[x]' : '[ ]';
      out += '- ' + checked + ' **' + DECISIONS[d] + '**';
      if (d === 0) out += ' — issue confirmed valid; suggested improvement is correct';
      else if (d === 1) out += ' — issue valid but fix needs refinement (add details in Notes)';
      else if (d === 2) out += ' — issue acknowledged but intentionally deferred (add rationale in Notes)';
      else if (d === 3) out += ' — issue acknowledged but will not be fixed (add rationale in Notes)';
      else if (d === 4) out += ' — issue invalid, not a problem, or already addressed';
      out += '\n';
    }
    out += '- **Notes:**';
    if (issue.review.notes && issue.review.notes.trim()) {
      out += '\n' + issue.review.notes.trim();
    }
    out += '\n\n---\n\n';
    return out;
  }

  // ── Helpers ─────────────────────────────────────────────────────────────

  function findSection(issue, label) {
    for (var i = 0; i < issue.sections.length; i++) {
      if (issue.sections[i].label === label) return issue.sections[i];
    }
    return null;
  }

  /** Count total issues and reviewed issues from a model. */
  function getIssueStats(model) {
    var total = model.issues.length;
    var reviewed = 0;
    for (var i = 0; i < model.issues.length; i++) {
      if (model.issues[i].review.decision) reviewed++;
    }
    return { total: total, reviewed: reviewed };
  }

  /** Quick parse: count issues and reviewed from raw content without full parse. */
  function quickParseStats(content) {
    if (!content) return { total: 0, reviewed: 0 };
    var total = (content.match(/^### Issue \d+:/gm) || []).length;
    var reviewed = (content.match(/^- \[x\] \*\*(ACCEPT|DEFER|WONTFIX|REJECT)/gm) || []).length;
    return { total: total, reviewed: reviewed };
  }

  // ── Public API ──────────────────────────────────────────────────────────

  return {
    SECTION_LABELS: SECTION_LABELS,
    DECISIONS: DECISIONS,
    parseIssueFile: parseIssueFile,
    reconstructMarkdown: reconstructMarkdown,
    buildMarkdown: buildMarkdown,
    parseSections: parseSections,
    findSection: findSection,
    getIssueStats: getIssueStats,
    quickParseStats: quickParseStats,
    emptyResult: emptyResult
  };
})();

// Also expose for Node.js / CommonJS usage
if (typeof module !== 'undefined' && module.exports) {
  module.exports = ISSUE_MODEL;
}
