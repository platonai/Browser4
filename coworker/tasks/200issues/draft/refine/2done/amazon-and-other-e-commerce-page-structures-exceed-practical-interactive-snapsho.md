# Amazon and other e-commerce page structures exceed practical interactive snapshot depth limits

Content-heavy e-commerce pages like Amazon search results have deeply nested DOM structures where product cards are not reachable at reasonable interactive snapshot depths. This makes interactive snapshot-based workflows impractical for a common use case and frustrates users who follow the documented interactive approach.

**Steps to reproduce:**
1. Navigate to Amazon search results:
   `browser4-cli goto "https://www.amazon.com/s?k=pens+to+draw+on+whiteboards"`
2. Run an interactive snapshot at a generous depth:
   `browser4-cli snapshot -i -d 8`
3. Observe that individual product cards are not visible in the snapshot output.

**Expected behavior:** Product listing elements should be reachable and selectable at a reasonable snapshot depth (e.g., depth 8 or less). Alternatively, the documentation should set clear expectations about which page types work well with interactive snapshots and which require alternative approaches.

**Actual behavior:** Product cards on Amazon require a depth greater than 8 due to deeply nested wrapper `div` elements, making interactive snapshots impractical. The user must bypass form interaction entirely (using direct URL navigation) and rely on `extract` or `eval` for data retrieval — but this is not obvious to a first-time user.

**Suggested resolution:**
- Add e-commerce-specific guidance to SKILL.md, explicitly recommending `extract` as the primary tool for search result pages and noting that interactive snapshots are better suited for simpler page structures.
- Consider whether snapshot depth could be made adaptive (e.g., automatically increasing depth when no interactive elements are found) or whether a "deep scan" mode could be added.

Labels: documentation, enhancement

