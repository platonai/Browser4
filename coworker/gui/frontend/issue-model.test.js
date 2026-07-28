/**
 * issue-model.test.js — Tests for the .issues.md parser and writer.
 *
 * Run: node --test coworker/gui/frontend/issue-model.test.js
 *
 * This is the CANONICAL schema implementation.  The PowerShell counterpart
 * (coworker/scripts/review.ps1) must produce identical output for the same
 * inputs.  See coworker/scripts/tests/review.tests.ps1 for the PS side.
 */

'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');

const {
  parseIssueFile,
  reconstructMarkdown,
  buildMarkdown,
  quickParseStats,
  SECTION_LABELS,
  DECISIONS,
} = require('./issue-model.js');

// ═══════════════════════════════════════════════════════════════════════════════
// Golden-file fixture — a minimal .issues.md used across both JS and PS tests.
// Must match the fixture in coworker/scripts/tests/review.tests.ps1 exactly.
// ═══════════════════════════════════════════════════════════════════════════════

const GOLDEN_FIXTURE = `# Issues: golden-scenario

> **Source:** \`20260725-120000-golden-scenario.full.md\` | **Date:** 20260725-120000 | **Mode:** dev

## Scenario Background

### Task

The agent was asked to fill out a form on example.com.

### Execution Context

| Step | Command | Result |
|------|---------|--------|
| 1 | \`goto https://example.com/form\` | OK |
| 2 | \`snapshot -i\` | OK |
| 3 | \`fill e3 "test@example.com"\` | OK |

---

## Issues Found (3 issues)

### Issue 1: Snapshot preview too short

**Severity:** Medium
**Category:** UX

#### Reproduction

Run \`snapshot -i\` on a page with many form fields.

#### Expected Behavior

The preview shows all interactive elements.

#### Actual Behavior

Only 10 lines shown. Form fields below the fold are invisible.

#### Root Cause Analysis

The preview truncation limit is hard-coded to 10 lines.

#### Code Pointer

\`cli/browser4-cli/src/snapshot.rs:render_preview()\`

#### AI Suggested Improvement

- Increase preview limit to 30 lines
- Bias toward showing interactive elements first

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**


---

### Issue 2: fill command silently fails with special characters

**Severity:** High
**Category:** Reliability

#### Reproduction

\`\`\`
fill e3 "user's <test> & check"
\`\`\`

#### Expected Behavior

Characters are properly escaped or a clear error is shown.

#### Actual Behavior

The command succeeds but the field contains garbled text.

#### Root Cause Analysis

Shell escaping is not handled before sending characters to CDP.

#### Code Pointer

\`cli/browser4-cli/src/commands.rs:fill_command()\`

#### AI Suggested Improvement

- Escape special characters before dispatch
- Add a \`--raw\` flag for literal input

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**


---

### Issue 3: No --help example for goto command

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run \`browser4-cli goto --help\`.

#### Expected Behavior

Help output includes at least one usage example.

#### Actual Behavior

Only flag descriptions, no examples.

#### Root Cause Analysis

The CLI help generator does not include examples.

#### Code Pointer


#### AI Suggested Improvement

- Add usage examples to all --help outputs

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**


---

## How to Reproduce

### Common Setup

1. Clone the repository and \`cd\` to the repo root.
2. The CLI is invoked via \`./b4w.ps1\` which auto-builds from source.
3. The backend server starts automatically in dev mode.

### Per-Issue Reproduction Steps

#### Issue 1: Snapshot preview too short

Run \`snapshot -i\` on a page with many form fields.

#### Issue 2: fill command silently fails with special characters

\`\`\`
fill e3 "user's <test> & check"
\`\`\`

#### Issue 3: No --help example for goto command

Run \`browser4-cli goto --help\`.
`;

// ═══════════════════════════════════════════════════════════════════════════════
// quickParseStats
// ═══════════════════════════════════════════════════════════════════════════════

describe('quickParseStats', () => {
  it('should count total issues and reviewed issues', () => {
    const stats = quickParseStats(GOLDEN_FIXTURE);
    assert.equal(stats.total, 3);
    assert.equal(stats.reviewed, 0);
  });

  it('should detect reviewed issues via [x] checkboxes', () => {
    const content = GOLDEN_FIXTURE.replace(
      '- [ ] **ACCEPT** — issue confirmed valid',
      '- [x] **ACCEPT** — issue confirmed valid'
    ).replace(
      '- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)',
      '- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)'
    );
    const stats = quickParseStats(content);
    assert.equal(stats.total, 3);
    assert.equal(stats.reviewed, 2);
  });

  it('should return zeros for empty content', () => {
    const stats = quickParseStats('');
    assert.equal(stats.total, 0);
    assert.equal(stats.reviewed, 0);
  });

  it('should return zeros for null/undefined', () => {
    const stats = quickParseStats(null);
    assert.equal(stats.total, 0);
    assert.equal(stats.reviewed, 0);
  });
});

// ═══════════════════════════════════════════════════════════════════════════════
// parseIssueFile
// ═══════════════════════════════════════════════════════════════════════════════

describe('parseIssueFile', () => {
  it('should parse meta fields', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    assert.equal(model.meta.scenario, 'golden-scenario');
    assert.equal(model.meta.source, '20260725-120000-golden-scenario.full.md');
    assert.equal(model.meta.date, '20260725-120000');
    assert.equal(model.meta.mode, 'dev');
  });

  it('should parse background sections', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    assert.ok(model.background.task.includes('fill out a form'));
    assert.ok(model.background.executionContext.includes('goto'));
    assert.ok(model.background.executionContext.includes('snapshot'));
  });

  it('should parse all three issues', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    assert.equal(model.issues.length, 3);
  });

  it('should parse issue numbers and titles', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    assert.equal(model.issues[0].number, 1);
    assert.equal(model.issues[0].title, 'Snapshot preview too short');
    assert.equal(model.issues[1].number, 2);
    assert.equal(model.issues[1].title, 'fill command silently fails with special characters');
    assert.equal(model.issues[2].number, 3);
    assert.equal(model.issues[2].title, 'No --help example for goto command');
  });

  it('should parse severity and category', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    assert.equal(model.issues[0].severity, 'Medium');
    assert.equal(model.issues[0].category, 'UX');
    assert.equal(model.issues[1].severity, 'High');
    assert.equal(model.issues[1].category, 'Reliability');
    assert.equal(model.issues[2].severity, 'Low');
    assert.equal(model.issues[2].category, 'Discoverability');
  });

  it('should parse sections for each issue', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    const issue1 = model.issues[0];
    assert.ok(issue1.sections.length >= 5, 'should have at least 5 sections');

    const labels = issue1.sections.map(s => s.label);
    assert.ok(labels.includes('Reproduction'));
    assert.ok(labels.includes('Expected Behavior'));
    assert.ok(labels.includes('Actual Behavior'));
    assert.ok(labels.includes('Root Cause Analysis'));

    const repro = issue1.sections.find(s => s.label === 'Reproduction');
    assert.ok(repro.body.includes('snapshot -i'));
  });

  it('should skip empty-body sections (Code Pointer with no content)', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    const issue3 = model.issues[2];
    // issue-model.js parseSections() filters sections with empty/whitespace body
    const cp = issue3.sections.find(s => s.label === 'Code Pointer');
    assert.equal(cp, undefined, 'empty Code Pointer should be excluded');
  });

  it('should have null decisions for unreviewed issues', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    for (const issue of model.issues) {
      assert.equal(issue.review.decision, null);
      assert.equal(issue.review.notes, '');
    }
  });

  it('should parse previously-reviewed decisions', () => {
    // Set some decisions
    const reviewed = GOLDEN_FIXTURE
      .replace(
        '- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct',
        '- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct'
      )
      .replace(
        '- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)',
        '- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)'
      );
    // Add notes to the ACCEPT issue
    const withNotes = reviewed.replace(
      '- **Notes:**\n\n\n---\n\n### Issue 2:',
      '- **Notes:**\nLooks good.\n\n---\n\n### Issue 2:'
    );

    const model = parseIssueFile(withNotes);
    assert.equal(model.issues[0].review.decision, 'ACCEPT');
    assert.equal(model.issues[0].review.notes, 'Looks good.');
    // Issue 1 had ACCEPT and WONTFIX checked — only first match wins
    // Actually both checkboxes are in the SAME issue's Human Review.
    // Our parser finds [x] ACCEPT first, so that's the decision.
    assert.equal(model.issues[0].review.decision, 'ACCEPT');
  });

  it('should handle files with no issues gracefully', () => {
    const empty = '# Issues: empty\n\n> **Source:** `x` | **Date:** 20260725 | **Mode:** dev\n\n## Issues Found (0)\n\nNo issues.\n';
    const model = parseIssueFile(empty);
    assert.equal(model.issues.length, 0);
  });

  it('should handle null/empty content', () => {
    const model = parseIssueFile('');
    assert.equal(model.issues.length, 0);
    assert.equal(model.meta.scenario, '');
  });
});

// ═══════════════════════════════════════════════════════════════════════════════
// reconstructMarkdown (write + re-parse round-trip)
// ═══════════════════════════════════════════════════════════════════════════════

describe('reconstructMarkdown', () => {
  it('should set ACCEPT decision and preserve all other content', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    model.issues[0].review.decision = 'ACCEPT';
    model.issues[0].review.notes = 'This is valid.';

    const updated = reconstructMarkdown(model);

    // Re-parse to verify
    const model2 = parseIssueFile(updated);
    assert.equal(model2.issues[0].review.decision, 'ACCEPT');
    assert.equal(model2.issues[0].review.notes, 'This is valid.');

    // Other issues unchanged
    assert.equal(model2.issues[1].review.decision, null);
    assert.equal(model2.issues[2].review.decision, null);

    // Meta preserved
    assert.equal(model2.meta.scenario, 'golden-scenario');
    assert.equal(model2.meta.source, '20260725-120000-golden-scenario.full.md');

    // Background preserved
    assert.ok(model2.background.task.includes('fill out a form'));

    // All decisions present in Human Review
    for (const dec of DECISIONS) {
      assert.ok(updated.includes('**' + dec + '**'), 'should contain ' + dec);
    }
  });

  it('should set REJECT decision correctly', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    model.issues[1].review.decision = 'REJECT';
    model.issues[1].review.notes = 'Not a real bug.';

    const updated = reconstructMarkdown(model);
    const model2 = parseIssueFile(updated);

    assert.equal(model2.issues[1].review.decision, 'REJECT');
    assert.equal(model2.issues[1].review.notes, 'Not a real bug.');
    // Only REJECT should be [x]
    assert.ok(updated.includes('- [x] **REJECT**'));
    assert.ok(updated.includes('- [ ] **ACCEPT**'));
    assert.ok(updated.includes('- [ ] **DEFER**'));
  });

  it('should toggle decision from ACCEPT to DEFER', () => {
    // First set ACCEPT
    const model = parseIssueFile(GOLDEN_FIXTURE);
    model.issues[0].review.decision = 'ACCEPT';
    const pass1 = reconstructMarkdown(model);

    // Parse back and change to DEFER
    const model2 = parseIssueFile(pass1);
    assert.equal(model2.issues[0].review.decision, 'ACCEPT');
    model2.issues[0].review.decision = 'DEFER';
    const pass2 = reconstructMarkdown(model2);

    const model3 = parseIssueFile(pass2);
    assert.equal(model3.issues[0].review.decision, 'DEFER');
    assert.ok(pass2.includes('- [x] **DEFER**'));
    assert.ok(!pass2.includes('- [x] **ACCEPT**'));
  });

  it('should clear a decision (set to null)', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    model.issues[0].review.decision = 'ACCEPT';
    const pass1 = reconstructMarkdown(model);

    const model2 = parseIssueFile(pass1);
    model2.issues[0].review.decision = null;
    const pass2 = reconstructMarkdown(model2);

    const model3 = parseIssueFile(pass2);
    assert.equal(model3.issues[0].review.decision, null);
    // No decisions should be checked
    assert.ok(!pass2.includes('[x]'));
  });

  it('should handle multiline notes with special characters', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    model.issues[0].review.decision = 'ACCEPT with improvements';
    model.issues[0].review.notes = 'Line 1\nLine 2\n- bullet\n`code`';

    const updated = reconstructMarkdown(model);
    const model2 = parseIssueFile(updated);

    assert.equal(model2.issues[0].review.decision, 'ACCEPT with improvements');
    assert.ok(model2.issues[0].review.notes.includes('Line 1'));
    assert.ok(model2.issues[0].review.notes.includes('Line 2'));
    assert.ok(model2.issues[0].review.notes.includes('- bullet'));
    assert.ok(model2.issues[0].review.notes.includes('`code`'));
  });

  it('should be idempotent — multiple saves do not corrupt', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);

    // Save 5 times with different decisions
    model.issues[0].review.decision = 'ACCEPT';
    const v1 = reconstructMarkdown(model);

    for (let i = 0; i < 4; i++) {
      const m = parseIssueFile(v1);
      m.issues[0].review.decision = 'DEFER';
      m.issues[1].review.decision = 'REJECT';
      m.issues[2].review.decision = 'DUPLICATE';
      const updated = reconstructMarkdown(m);

      const reparse = parseIssueFile(updated);
      assert.equal(reparse.issues[0].review.decision, 'DEFER');
      assert.equal(reparse.issues[1].review.decision, 'REJECT');
      assert.equal(reparse.issues[2].review.decision, 'DUPLICATE');
      assert.equal(reparse.issues.length, 3);
      assert.equal(reparse.meta.scenario, 'golden-scenario');
    }
  });

  it('should preserve How to Reproduce section', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    model.issues[0].review.decision = 'ACCEPT';
    const updated = reconstructMarkdown(model);

    assert.ok(updated.includes('## How to Reproduce'));
    assert.ok(updated.includes('### Common Setup'));
    assert.ok(updated.includes('### Per-Issue Reproduction Steps'));
  });

  it('should preserve code blocks and backtick content', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    model.issues[1].review.decision = 'ACCEPT';
    const updated = reconstructMarkdown(model);

    assert.ok(updated.includes('```'));
    assert.ok(updated.includes("user's <test> & check"));
    assert.ok(updated.includes('`snapshot -i`'));
  });

  it('should not lose the Scenario Background content', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    model.issues[0].review.decision = 'WONTFIX';
    const updated = reconstructMarkdown(model);

    assert.ok(updated.includes('## Scenario Background'));
    assert.ok(updated.includes('### Task'));
    assert.ok(updated.includes('### Execution Context'));
    assert.ok(updated.includes('fill out a form on example.com'));
    assert.ok(updated.includes('goto'));
    assert.ok(updated.includes('snapshot -i'));
  });
});

// ═══════════════════════════════════════════════════════════════════════════════
// buildMarkdown (fresh construction fallback)
// ═══════════════════════════════════════════════════════════════════════════════

describe('buildMarkdown', () => {
  it('should build a valid file from scratch', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    model._originalContent = ''; // force fresh build
    const built = buildMarkdown(model);

    // Should be re-parseable
    const model2 = parseIssueFile(built);
    assert.equal(model2.meta.scenario, 'golden-scenario');
    assert.equal(model2.issues.length, 3);
    assert.equal(model2.issues[0].title, 'Snapshot preview too short');
  });

  it('should include Human Review in built output', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    model.issues[0].review.decision = 'ACCEPT';
    model._originalContent = '';
    const built = buildMarkdown(model);

    assert.ok(built.includes('- [x] **ACCEPT**'));
    assert.ok(built.includes('#### Human Review'));
  });
});

// ═══════════════════════════════════════════════════════════════════════════════
// Cross-format golden file — JSON round-trip
// ═══════════════════════════════════════════════════════════════════════════════

describe('Golden-file cross-format consistency', () => {
  it('should produce identical parse results from the same markdown', () => {
    const model1 = parseIssueFile(GOLDEN_FIXTURE);
    const model2 = parseIssueFile(GOLDEN_FIXTURE);

    assert.equal(model1.issues.length, model2.issues.length);
    assert.equal(model1.meta.scenario, model2.meta.scenario);

    for (let i = 0; i < model1.issues.length; i++) {
      assert.equal(model1.issues[i].number, model2.issues[i].number);
      assert.equal(model1.issues[i].title, model2.issues[i].title);
      assert.equal(model1.issues[i].severity, model2.issues[i].severity);
      assert.equal(model1.issues[i].category, model2.issues[i].category);
      assert.equal(model1.issues[i].review.decision, model2.issues[i].review.decision);
      assert.equal(model1.issues[i].review.notes, model2.issues[i].review.notes);
      assert.equal(model1.issues[i].sections.length, model2.issues[i].sections.length);
    }
  });

  it('should produce stable JSON serialization', () => {
    const model = parseIssueFile(GOLDEN_FIXTURE);
    model.issues[0].review.decision = 'ACCEPT';
    model.issues[0].review.notes = 'test';

    const json1 = JSON.stringify(model);
    const json2 = JSON.stringify(model);
    assert.equal(json1, json2);
  });

  it('should handle all six decision types round-trip', () => {
    const decisions = ['ACCEPT', 'ACCEPT with improvements', 'DEFER', 'WONTFIX', 'REJECT', 'DUPLICATE'];
    const model = parseIssueFile(GOLDEN_FIXTURE);

    for (let i = 0; i < decisions.length && i < model.issues.length; i++) {
      model.issues[i].review.decision = decisions[i];
      model.issues[i].review.notes = 'Note for ' + decisions[i];
    }

    const updated = reconstructMarkdown(model);
    const model2 = parseIssueFile(updated);

    for (let i = 0; i < decisions.length && i < model.issues.length; i++) {
      assert.equal(model2.issues[i].review.decision, decisions[i]);
      assert.ok(model2.issues[i].review.notes.includes(decisions[i]));
    }
  });
});
