# No Guidance for Extracting Prices from Amazon-Style Split-Price DOM Patterns

When using `browser4-cli get text` (or `eval`) to extract prices from e-commerce pages, Amazon's split-price DOM pattern — where the whole-dollar amount and fractional cents are in separate elements — produces non-obvious results that require special handling. There is no documentation helping users navigate this common pattern.

**Steps to Reproduce:**
1. Use `browser4-cli get text <ref>` on an Amazon price element.

**Expected Behavior:** Clean, human-readable text such as `"$7.49"`.

**Actual Behavior (observed via `eval`):** The `.a-price-whole` span contains `"7."` (decimal point included) and `.a-price-fraction` contains `"49"`. Reassembling the full price requires understanding this Amazon-specific DOM structure and writing custom concatenation logic.

**Suggested Improvement:** While this is partly an Amazon-specific quirk, it represents a broader class of DOM pattern where semantic values are split across elements. Document common DOM patterns for price extraction and other structured data in `SKILL.md`, with Amazon as a worked example. This could also motivate a higher-level data extraction helper.

**Acceptance Criteria:**
- `SKILL.md` includes a section or example covering common e-commerce DOM patterns (split prices, variant selectors, review counts).
- A user can follow the documented pattern to extract a complete price from an Amazon product page.

Labels: documentation, enhancement

