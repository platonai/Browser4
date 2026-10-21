# A. Task Result

**Fix-round 2026-09-03 for reviewed batch `20260902-212331-x-sql-extraction-functions` (draft/2026/0902).** This ticket bundles the TWO engine-level root causes from that batch (Issue 1: numeric predicates on `DOM_FIRST_FLOAT`/`DOM_FIRST_INTEGER`; Issue 2: `DOM_*_IMG` ignores `:expr()`) into ONE upstream fix tracked here, landing in Browser4 with the next pulsar (`pulsar-ql`) dependency bump, per the human review notes.

Both root causes live in the **external** `ai.platon.pulsar` X-SQL UDF engine — no `ai/platon/pulsar/ql/**` sources exist in this repo, and `browser4-rest/pom.xml` declares `pulsar-ql` / `pulsar-ql-common` as dependencies, so no in-repo code change can fix either defect.

In-repo deliverables for both issues were shipped on 4.13.x on 2026-09-03 (server-side hints, `:expr` warning, CLI error surfacing, docs corrections, parity tests) — see "In-repo Status" in the embedded JSON below.

# B. Execution Trace

**Upstream ticket creation (2026-09-03, cwd `D:\workspace\Browser4\Browser4-4.13`):**
1. Confirmed the external-engine boundary: no `ai/platon/pulsar/ql` sources in repo; `browser4-rest/pom.xml` deps `pulsar-ql` + `pulsar-ql-common`.
2. Mapped the error-propagation chain for the hex error: CLI `html_snapshot` MCP tool → `HTMLSnapshotToolExecutor.query()` → `ScrapeService.executeQuery` (417 auto-retry) → `XSQLHyperlink`/`AbstractScrapeHyperlink` sets `statusCode=417`, `message=e.message`.
3. Identified the 417 discriminator: a genuine H2 engine error carries `SQL statement:` + full SQL echo in the envelope message (the session-race 417 does not).
4. Applied the in-repo mitigations (see the issue JSON "In-repo Status"), then created this single upstream ticket for the engine fixes.

**Key decisions:** One ticket, not two — the human review for both issues directed a single bundled upstream fix landing with a dependency bump; in-repo work was limited to hints/warnings/docs/tests that remain valid after the engine fix (they only fire on the two failure modes). Detector functions were placed at the tool-executor level (`HTMLSnapshotToolExecutor`) next to the existing `shouldAppendSelectorQuoteHint` precedent and covered by pure JUnit tests, so the parity expectations are pinned in this repo too.

```json
{
  "issues": [
    {
      "title": "pulsar-ql X-SQL engine: DOM_FIRST_FLOAT/INTEGER not comparable to numeric literals in WHERE, and DOM_*_IMG ignores :expr() selectors — fix together, land with dependency bump",
      "severity": "High",
      "category": "Product",
      "reproduction": "Against the MockSite Electronics category (6 product cards):\n1. Numeric predicate: SELECT DOM_FIRST_TEXT(DOM, '.product-title') FROM DOM_LOAD_AND_SELECT(@url, '.product-card') WHERE DOM_FIRST_FLOAT(DOM, '.product-price') >= 25.0 → 417 'Expectation Failed', H2 error 'Hexadecimal string contains non-hex character: \"899.99\"' (SQL 90004-197). Also fails with an integer literal (>= 25) and with the 3-arg overload DOM_FIRST_FLOAT(DOM, sel, 0.0). Works: CAST(... AS DOUBLE) wrapper, STR_FIRST_FLOAT(DOM_FIRST_TEXT(...), 0.0), and raw DOM_FIRST_FLOAT in SELECT / ORDER BY.\n2. :expr on images: SELECT DOM_FIRST_IMG(DOM, 'img.product-img:expr(width > 100 && height > 100)') → empty string / no match, even for the tautology :expr(width >= 0), with no error or warning. The identical selector returns URLs through DOM_FIRST_ATTR(DOM, sel, 'src'), DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, sel)), the FROM selector, and htmlsnapshot get attr.",
      "expected": "1. WHERE DOM_FIRST_FLOAT(DOM, '.price') >= 25.0 filters rows numerically — the same expression already works in SELECT and ORDER BY. 2. DOM_FIRST_IMG(DOM, 'img.x:expr(...)') returns the src of a matching image, consistent with the documented claim that :expr is usable in X-SQL DOM_* functions.",
      "actual": "1. WHERE/HAVING comparison against a numeric literal triggers a fallback type-conversion path that hex-decodes the custom value type's string form ('899.99') and dies with the opaque H2 error (SQL 90004-197); zero rows, no hint about the offending expression or the CAST workaround. 2. DOM_*_IMG evaluates its selector through a path that does not parse/evaluate :expr(...) (or evaluates features on an unmeasured DOM copy where every expression is false) → silent no-match, indistinguishable from 'page has no such image'.",
      "rootCause": "Both defects sit in the external pulsar X-SQL UDF engine (ai.platon.pulsar pulsar-ql), not in this repo. 1. DOM_FIRST_FLOAT/DOM_FIRST_INTEGER register a custom H2 value type that is not numeric-comparable in predicates. 2. DOM_FIRST_IMG / DOM_NTH_IMG / DOM_ALL_IMGS alias implementation diverges from DOM_FIRST_ATTR / DOM_SELECT_FIRST / DOM_LOAD_AND_SELECT selector handling (img-scanning path vs PowerCSS-capable selector engine). Reproduce in pulsar-ql with its own H2 test harness; no in-repo code change can fix either.",
      "codePointer": "External dependency: ai.platon.pulsar pulsar-ql / pulsar-ql-common (function aliases per docs: DomSelectFunctions DOM_FIRST_FLOAT + DOM_FIRST_IMG implementations, H2 function/type registration). Deps declared in browser4-rest/pom.xml. Lands in Browser4 via the next pulsar dependency version bump; add regression tests in pulsar-ql (numeric predicate on DOM_FIRST_FLOAT/INTEGER; :expr parity across DOM_FIRST_IMG vs DOM_FIRST_ATTR vs DOM_SELECT_FIRST vs DOM_LOAD_AND_SELECT).",
      "suggestion": "1. Register DOM_FIRST_FLOAT / DOM_FIRST_INTEGER (and DOM_NTH_*/DOM_ALL_* float/integer results) as numeric H2 types — or return plain DOUBLE/INT — so predicate comparisons work without CAST. 2. Route DOM_*_IMG selectors through the same PowerCSS-capable selector engine as DOM_FIRST_ATTR/DOM_SELECT_FIRST (or, if that is intentional, make the engine reject :expr on img functions with a clear error instead of a silent empty match). 3. Ship with a dependency bump in browser4-rest."
    }
  ],
  "assessment": {
    "completionStatus": "Upstream ticket created; in-repo mitigations for both reviewed issues shipped on 4.13.x (hints, warning, CLI surfacing, docs, parity tests).",
    "successRate": "100%",
    "issuesFound": 1,
    "majorBlockers": "None — engine defects are external and tracked here for the dependency bump; no in-repo blocker remains.",
    "mostConfusingAspects": "N/A (fix-round ticket, not a scenario run).",
    "mostValuableImprovements": "Engine numeric-type registration for DOM_FIRST_FLOAT/INTEGER predicates and selector-engine parity for DOM_*_IMG :expr support in pulsar-ql.",
    "usabilityRating": null
  }
}
```

# C. Review State

| Original issue (0902 batch) | Review decision | How routed |
|---|---|---|
| #1 `DOM_FIRST_FLOAT … WHERE` hex error (High) | ACCEPT with improvements | Root cause external → bundled here; in-repo hint + CAST docs shipped |
| #2 `DOM_FIRST_IMG` ignores `:expr()` (High) | ACCEPT with improvements | Root cause external → bundled here; in-repo `:expr` warning + docs + parity tests shipped |
| #3 `--format table` / `--result-only` undiscoverable (Medium) | ACCEPT | Docs (SKILL.md §4e/§6, htmlsnapshot.md, x-sql.md) + terminal tip shipped — no engine involvement |
| #4 `bin/test.ps1` PowerShell 5.1 (Low) | ACCEPT | `#requires -Version 7` + BOM sweep on `bin/*.ps1` + docs shipped |

Upstream items waiting on the pulsar dependency bump:
- [ ] pulsar-ql: numeric H2 type registration for `DOM_FIRST_FLOAT`/`DOM_FIRST_INTEGER` predicate comparisons (drop the CAST workaround requirement)
- [ ] pulsar-ql: `DOM_*_IMG` `:expr(...)` selector parity with `DOM_FIRST_ATTR`/`DOM_SELECT_FIRST` (or explicit rejection, not silent empty match)
- [ ] browser4-rest: dependency bump + regression tests, then remove "tracked upstream" workaround wording from executor comments and the docs added on 2026-09-03
