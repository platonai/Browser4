/**
 * summary-builder.js — Build summary/conserved versions of .issues.md files.
 *
 * Used by the mark-done and mark-all-done endpoints in server.js.
 * Extracted into its own module so it can be unit-tested independently
 * of the Express server.
 */

'use strict';

const issueModel = require('./frontend/issue-model.js');

// ── Public API ──────────────────────────────────────────────────────────

/**
 * Build a summary version of the issues file.
 * - Approved issues (ACCEPT, ACCEPT with improvements): keep full detail
 * - Other issues (DEFER, WONTFIX, REJECT, DUPLICATE, unreviewed): condensed abstract
 *
 * @param {string} content - Raw .issues.md file content
 * @returns {string} Summary markdown ready for the ready queue
 */
function buildSummaryContent(content) {
  const APPROVED = ['ACCEPT', 'ACCEPT with improvements'];
  const model = issueModel.parseIssueFile(content);

  // Zero-issue files: build a clean "no issues" summary instead of raw content
  if (!model.issues || model.issues.length === 0) {
    let out = '# Issues: ' + (model.meta.scenario || 'unknown') + '\n\n';
    out += '> **Source:** `' + (model.meta.source || '') + '` | ';
    out += '**Date:** ' + (model.meta.date || '') + ' | ';
    out += '**Mode:** ' + (model.meta.mode || 'dev') + '\n\n';
    if (model.background.task) {
      out += '## Scenario Background\n\n### Task\n\n' + model.background.task + '\n\n';
    }
    out += '---\n\n';
    out += '## Issues Found (0)\n\n';
    out += '> **Review complete:** No issues were detected in this evaluation.\n\n';
    if (model.meta.source) {
      out += 'See `' + model.meta.source + '` for the complete evaluation output.\n';
    }
    return out.trim() + '\n';
  }

  // Build preamble from meta + background
  let preamble = '# Issues: ' + (model.meta.scenario || 'unknown') + '\n\n';
  preamble += '> **Source:** `' + (model.meta.source || '') + '` | ';
  preamble += '**Date:** ' + (model.meta.date || '') + ' | ';
  preamble += '**Mode:** ' + (model.meta.mode || 'dev') + '\n\n';
  if (model.background.task) {
    preamble += '## Scenario Background\n\n### Task\n\n' + model.background.task + '\n\n';
  }
  if (model.background.executionContext) {
    preamble += '### Execution Context\n\n' + model.background.executionContext + '\n\n';
  }
  preamble += '---';

  const approvedBlocks = [];
  const abstractBlocks = [];
  let keptCount = 0;
  let condensedCount = 0;

  for (const issue of model.issues) {
    if (issue.review.decision && APPROVED.includes(issue.review.decision)) {
      approvedBlocks.push(buildIssueBlockFromModel(issue));
      keptCount++;
    } else {
      abstractBlocks.push(buildAbstractFromModel(issue));
      condensedCount++;
    }
  }

  let out = preamble + '\n\n';
  out += '## Issues Found (' + model.issues.length + ' issue' + (model.issues.length !== 1 ? 's' : '') + ')\n';
  out += '> **Review complete:** ' + keptCount + ' approved, ' + condensedCount + ' deferred/rejected\n\n';

  for (const b of approvedBlocks) {
    out += b.trimEnd() + '\n\n---\n\n';
  }
  for (const a of abstractBlocks) {
    out += a.trimEnd() + '\n\n---\n\n';
  }

  // Append "How to Reproduce" footer if present in original
  const howToIdx = content.indexOf('\n## How to Reproduce');
  if (howToIdx >= 0) {
    out += content.substring(howToIdx).trim() + '\n';
  }

  return out.trim() + '\n';
}

/**
 * Build full detail block for an approved issue.
 * @param {object} issue - An issue object from issueModel.parseIssueFile()
 * @returns {string} Markdown block for the issue
 */
function buildIssueBlockFromModel(issue) {
  let out = '### Issue ' + issue.number + ': ' + issue.title + '\n\n';
  out += '**Severity:** ' + (issue.severity || 'N/A') + '\n';
  out += '**Category:** ' + (issue.category || 'N/A') + '\n\n';
  for (const s of (issue.sections || [])) {
    if (s.body && s.body.trim()) {
      out += '#### ' + s.label + '\n\n' + s.body + '\n\n';
    }
  }
  out += '#### Human Review\n\n';
  for (const d of issueModel.DECISIONS) {
    const checked = (issue.review.decision === d) ? '[x]' : '[ ]';
    out += '- ' + checked + ' **' + d + '**\n';
  }
  out += '- **Notes:**';
  if (issue.review.notes && issue.review.notes.trim()) {
    out += '\n' + issue.review.notes.trim();
  }
  out += '\n';
  return out;
}

/**
 * Build condensed abstract block for a non-approved issue.
 * @param {object} issue - An issue object from issueModel.parseIssueFile()
 * @returns {string} Condensed markdown block
 */
function buildAbstractFromModel(issue) {
  const decision = issue.review.decision || 'WONTFIX';

  // Get the AI Suggested Improvement text as a one-line summary
  let suggestion = '';
  const aiSection = (issue.sections || []).find(s =>
    s.label && s.label.toLowerCase().indexOf('ai suggested') >= 0
  );
  if (aiSection && aiSection.body) {
    suggestion = aiSection.body.trim();
    const firstLine = suggestion.split('\n').find(l => l.trim() && !l.trim().startsWith('- '));
    if (firstLine) suggestion = firstLine.trim();
    else suggestion = suggestion.split('\n')[0] || '';
    if (suggestion.length > 200) suggestion = suggestion.substring(0, 197) + '...';
  }

  let out = '### Issue ' + issue.number + ': ' + issue.title + '\n\n';
  out += '**Severity:** ' + (issue.severity || 'N/A') + '\n';
  out += '**Category:** ' + (issue.category || 'N/A') + '\n\n';
  out += '#### Review Result\n\n';
  out += '**Decision:** ' + decision + '\n\n';
  if (issue.review.notes) {
    out += '**Notes:** ' + issue.review.notes + '\n\n';
  }
  if (suggestion) {
    out += '**Summary:** ' + suggestion + '\n';
  }

  return out;
}

module.exports = {
  buildSummaryContent,
  buildIssueBlockFromModel,
  buildAbstractFromModel,
};
