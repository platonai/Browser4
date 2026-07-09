/**
 * summary-builder.test.js — Tests for the summary builder module.
 *
 * Run: node --test summary-builder.test.js
 */

'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const path = require('path');
const fs = require('fs');

const {
  buildSummaryContent,
  buildIssueBlockFromModel,
  buildAbstractFromModel,
} = require('./summary-builder.js');

// ── Helpers ─────────────────────────────────────────────────────────────

/** Minimal valid issue object matching the issue-model schema. */
function makeIssue(overrides = {}) {
  return Object.assign({
    number: 1,
    title: 'Test issue title',
    severity: 'Medium',
    category: 'UX',
    sections: [],
    review: { decision: null, notes: '' },
  }, overrides);
}

// ── buildIssueBlockFromModel ────────────────────────────────────────────

describe('buildIssueBlockFromModel', () => {
  it('should produce markdown for an ACCEPT decision', () => {
    const issue = makeIssue({
      review: { decision: 'ACCEPT', notes: '' },
    });
    const result = buildIssueBlockFromModel(issue);

    assert.ok(result.includes('### Issue 1: Test issue title'));
    assert.ok(result.includes('**Severity:** Medium'));
    assert.ok(result.includes('**Category:** UX'));
    assert.ok(result.includes('#### Human Review'));
    assert.ok(result.includes('- [x] **ACCEPT**'));
    assert.ok(result.includes('- [ ] **ACCEPT with improvements**'));
    assert.ok(result.includes('- [ ] **DEFER**'));
    assert.ok(result.includes('- [ ] **WONTFIX**'));
    assert.ok(result.includes('- [ ] **REJECT**'));
    assert.ok(result.includes('- [ ] **DUPLICATE**'));
    assert.ok(result.includes('- **Notes:**'));
  });

  it('should produce markdown for a DEFER decision', () => {
    const issue = makeIssue({
      review: { decision: 'DEFER', notes: 'Will fix later' },
    });
    const result = buildIssueBlockFromModel(issue);

    assert.ok(result.includes('- [ ] **ACCEPT**'));
    assert.ok(result.includes('- [x] **DEFER**'));
    assert.ok(result.includes('Will fix later'));
  });

  it('should produce markdown with no decision checked when decision is null', () => {
    const issue = makeIssue({
      review: { decision: null, notes: '' },
    });
    const result = buildIssueBlockFromModel(issue);

    // Nothing should be checked
    assert.ok(!result.includes('[x]'));
    // But all options should be present
    for (const d of ['ACCEPT', 'ACCEPT with improvements', 'DEFER', 'WONTFIX', 'REJECT', 'DUPLICATE']) {
      assert.ok(result.includes('- [ ] **' + d + '**'), 'should have unchecked ' + d);
    }
  });

  it('should include section bodies', () => {
    const issue = makeIssue({
      sections: [
        { label: 'Reproduction', body: 'Run `cargo test`' },
        { label: 'Expected Behavior', body: 'Tests pass' },
      ],
    });
    const result = buildIssueBlockFromModel(issue);

    assert.ok(result.includes('#### Reproduction'));
    assert.ok(result.includes('Run `cargo test`'));
    assert.ok(result.includes('#### Expected Behavior'));
    assert.ok(result.includes('Tests pass'));
  });

  it('should skip sections with empty body', () => {
    const issue = makeIssue({
      sections: [
        { label: 'Reproduction', body: 'Has content' },
        { label: 'Empty Section', body: '' },
        { label: 'Whitespace Only', body: '   ' },
      ],
    });
    const result = buildIssueBlockFromModel(issue);

    assert.ok(result.includes('#### Reproduction'));
    assert.ok(!result.includes('#### Empty Section'));
    assert.ok(!result.includes('#### Whitespace Only'));
  });

  it('should handle undefined sections gracefully', () => {
    const issue = makeIssue({ sections: undefined });
    const result = buildIssueBlockFromModel(issue);

    // Should not crash, and should still produce Human Review
    assert.ok(result.includes('#### Human Review'));
  });

  it('should include review notes when present', () => {
    const issue = makeIssue({
      review: { decision: 'ACCEPT with improvements', notes: 'Needs better error message in the fix' },
    });
    const result = buildIssueBlockFromModel(issue);

    assert.ok(result.includes('- [x] **ACCEPT with improvements**'));
    assert.ok(result.includes('Needs better error message in the fix'));
  });

  it('should handle multiline review notes', () => {
    const issue = makeIssue({
      review: { decision: 'DEFER', notes: 'Line 1\nLine 2\nLine 3' },
    });
    const result = buildIssueBlockFromModel(issue);

    assert.ok(result.includes('Line 1\nLine 2\nLine 3'));
  });
});

// ── buildAbstractFromModel ──────────────────────────────────────────────

describe('buildAbstractFromModel', () => {
  it('should produce a condensed block with decision', () => {
    const issue = makeIssue({
      review: { decision: 'DEFER', notes: 'Not critical' },
    });
    const result = buildAbstractFromModel(issue);

    assert.ok(result.includes('### Issue 1: Test issue title'));
    assert.ok(result.includes('**Decision:** DEFER'));
    assert.ok(result.includes('**Notes:** Not critical'));
  });

  it('should default to WONTFIX when no decision is set', () => {
    const issue = makeIssue({
      review: { decision: null, notes: '' },
    });
    const result = buildAbstractFromModel(issue);

    assert.ok(result.includes('**Decision:** WONTFIX'));
  });

  it('should include AI suggested improvement summary when present', () => {
    const issue = makeIssue({
      sections: [
        { label: 'AI Suggested Improvement', body: 'Fix the timeout to 60 seconds instead of 30' },
      ],
      review: { decision: 'DEFER', notes: '' },
    });
    const result = buildAbstractFromModel(issue);

    assert.ok(result.includes('**Summary:** Fix the timeout to 60 seconds instead of 30'));
  });

  it('should truncate long suggestions at 200 characters', () => {
    const longSuggestion = 'A'.repeat(250);
    const issue = makeIssue({
      sections: [
        { label: 'AI Suggested Improvement', body: longSuggestion },
      ],
      review: { decision: 'WONTFIX', notes: '' },
    });
    const result = buildAbstractFromModel(issue);

    assert.ok(result.includes('...'));
    const summaryLine = result.split('\n').find(l => l.startsWith('**Summary:**'));
    assert.ok(summaryLine.length <= '**Summary:** '.length + 200 + 3);
  });

  it('should extract first non-list-item line from multiline suggestions', () => {
    const issue = makeIssue({
      sections: [
        {
          label: 'AI Suggested Improvement',
          body: '- First bullet\n- Second bullet\nThe actual summary line\n- Third bullet',
        },
      ],
      review: { decision: 'DEFER', notes: '' },
    });
    const result = buildAbstractFromModel(issue);

    assert.ok(result.includes('**Summary:** The actual summary line'));
  });

  it('should fall back to first line if all lines are list items', () => {
    const issue = makeIssue({
      sections: [
        {
          label: 'AI Suggested Improvement',
          body: '- Only bullets\n- More bullets',
        },
      ],
      review: { decision: 'DEFER', notes: '' },
    });
    const result = buildAbstractFromModel(issue);

    // Should not crash; just picks up whatever it can
    assert.ok(result.includes('#### Review Result'));
  });
});

// ── buildSummaryContent ─────────────────────────────────────────────────

describe('buildSummaryContent', () => {
  it('should handle a complete .issues.md file with mixed decisions', () => {
    const fixturePath = path.join(
      __dirname, '..', '..', 'tasks', 'issues', 'review',
      '2026', '0708', '20260708-162517-Calabi-Yau.issues.md'
    );
    // Skip if fixture doesn't exist (CI / different checkout)
    if (!fs.existsSync(fixturePath)) {
      console.log('  (skipping — fixture file not found: ' + fixturePath + ')');
      return;
    }
    const content = fs.readFileSync(fixturePath, 'utf-8');
    const result = buildSummaryContent(content);

    // Should not throw — this is the main regression test for the
    // "issueModel is not defined" bug
    assert.ok(result.length > 0);

    // Preamble
    assert.ok(result.includes('# Issues: Calabi-Yau'));
    assert.ok(result.includes('**Source:** `20260708-162517-Calabi-Yau.full.md`'));

    // Should include approved issues with full detail
    assert.ok(result.includes('#### Reproduction'));
    assert.ok(result.includes('#### Human Review'));

    // Should show the count line
    assert.ok(result.includes('## Issues Found'));

    // Should append "How to Reproduce" footer
    assert.ok(result.includes('## How to Reproduce'));
  });

  it('should produce a zero-issue summary for files with no issues', () => {
    const content = [
      '# Issues: empty-test',
      '> **Source:** `empty.full.md` | **Date:** 20260709-120000 | **Mode:** dev',
      '',
      '## Scenario Background',
      '',
      '### Task',
      '',
      'No real task.',
      '',
      '---',
      '',
      '## Issues Found (0)',
      '',
      'No issues found.',
    ].join('\n');

    const result = buildSummaryContent(content);

    assert.ok(result.includes('# Issues: empty-test'));
    assert.ok(result.includes('## Issues Found (0)'));
    assert.ok(result.includes('No issues were detected in this evaluation'));
    assert.ok(result.includes('See `empty.full.md` for the complete evaluation output'));
  });

  it('should include execution context in preamble when present', () => {
    const content = [
      '# Issues: with-exec-context',
      '> **Source:** `test.full.md` | **Date:** 20260709-120000 | **Mode:** dev',
      '',
      '## Scenario Background',
      '',
      '### Task',
      '',
      'Test task description.',
      '',
      '### Execution Context',
      '',
      'Ran command: `cargo test`',
      '',
      '---',
      '',
      '## Issues Found (1)',
      '',
      '### Issue 1: Test issue',
      '',
      '**Severity:** Low',
      '**Category:** UX',
      '',
      '#### Reproduction',
      '',
      'steps here',
      '',
      '#### Human Review',
      '',
      '- [x] **ACCEPT**',
      '- [ ] **ACCEPT with improvements**',
      '- [ ] **DEFER**',
      '- [ ] **WONTFIX**',
      '- [ ] **REJECT**',
      '- [ ] **DUPLICATE**',
      '- **Notes:**',
    ].join('\n');

    const result = buildSummaryContent(content);

    assert.ok(result.includes('### Execution Context'));
    assert.ok(result.includes('Ran command: `cargo test`'));
    assert.ok(result.includes('## Issues Found (1 issue)'));
    assert.ok(result.includes('1 approved, 0 deferred/rejected'));
  });

  it('should handle multiple issues with correct approved/condensed counts', () => {
    const content = [
      '# Issues: count-test',
      '> **Source:** `test.full.md` | **Date:** 20260709-120000 | **Mode:** dev',
      '',
      '---',
      '',
      '## Issues Found (3)',
      '',
      '### Issue 1: Accepted',
      '',
      '**Severity:** High',
      '**Category:** Product',
      '',
      '#### Human Review',
      '',
      '- [x] **ACCEPT**',
      '- [ ] **ACCEPT with improvements**',
      '- [ ] **DEFER**',
      '- [ ] **WONTFIX**',
      '- [ ] **REJECT**',
      '- [ ] **DUPLICATE**',
      '- **Notes:**',
      '',
      '---',
      '',
      '### Issue 2: Deferred',
      '',
      '**Severity:** Medium',
      '**Category:** UX',
      '',
      '#### Human Review',
      '',
      '- [ ] **ACCEPT**',
      '- [ ] **ACCEPT with improvements**',
      '- [x] **DEFER**',
      '- [ ] **WONTFIX**',
      '- [ ] **REJECT**',
      '- [ ] **DUPLICATE**',
      '- **Notes:**',
      'Defer for now.',
      '',
      '---',
      '',
      '### Issue 3: Unreviewed',
      '',
      '**Severity:** Low',
      '**Category:** Documentation',
      '',
      '#### Human Review',
      '',
      '- [ ] **ACCEPT**',
      '- [ ] **ACCEPT with improvements**',
      '- [ ] **DEFER**',
      '- [ ] **WONTFIX**',
      '- [ ] **REJECT**',
      '- [ ] **DUPLICATE**',
      '- **Notes:**',
    ].join('\n');

    const result = buildSummaryContent(content);

    assert.ok(result.includes('## Issues Found (3 issues)'));
    assert.ok(result.includes('1 approved, 2 deferred/rejected'));

    // The approved issue (Issue 1) should have full detail with Human Review checkboxes
    assert.ok(result.includes('### Issue 1: Accepted'));
    assert.ok(result.includes('- [x] **ACCEPT**'));

    // The deferred issue (Issue 2) should be condensed
    assert.ok(result.includes('### Issue 2: Deferred'));
    assert.ok(result.includes('**Decision:** DEFER'));
    assert.ok(result.includes('Defer for now.'));

    // The unreviewed issue (Issue 3) should be condensed with WONTFIX default
    assert.ok(result.includes('### Issue 3: Unreviewed'));
    assert.ok(result.includes('**Decision:** WONTFIX'));
  });

  it('should handle an empty string gracefully', () => {
    const result = buildSummaryContent('');
    assert.ok(result.includes('# Issues: unknown'));
    assert.ok(result.includes('## Issues Found (0)'));
  });

  it('should not throw when content has no issues at all', () => {
    const content = '# Just a title\n\nSome content without any issue structure.';
    const result = buildSummaryContent(content);
    // Should not throw and should produce some output
    assert.ok(typeof result === 'string');
    assert.ok(result.length > 0);
  });

  it('should produce singular "issue" for a single issue', () => {
    const content = [
      '# Issues: single-test',
      '> **Source:** `test.full.md` | **Date:** 20260709-120000 | **Mode:** dev',
      '',
      '---',
      '',
      '## Issues Found (1)',
      '',
      '### Issue 1: Only issue',
      '',
      '**Severity:** High',
      '**Category:** Product',
      '',
      '#### Human Review',
      '',
      '- [x] **ACCEPT**',
      '- [ ] **ACCEPT with improvements**',
      '- [ ] **DEFER**',
      '- [ ] **WONTFIX**',
      '- [ ] **REJECT**',
      '- [ ] **DUPLICATE**',
      '- **Notes:**',
    ].join('\n');

    const result = buildSummaryContent(content);

    assert.ok(result.includes('## Issues Found (1 issue)'));
    // Should NOT say "1 issues"
    assert.ok(!result.includes('1 issues'));
  });
});
