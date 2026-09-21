# Snapshot serialization is not CSS-visibility-aware: hidden tooltip text leaks into names, hover-visible tooltips are dropped from viewport snapshots

## Summary

CSS-hidden (`visibility:hidden`) descendant text appears in aggregated/full-page snapshot output (element names and grep matches), and a `:hover`-revealed tooltip never shows up as a node in viewport snapshots — even while the browser confirms it is visible. Snapshot output therefore cannot be used to verify hover-revealed content: a grep match on hidden text is a false positive, and the visible tooltip is simply absent. The two rendering paths disagree about hidden content, and neither exposes the rendered visibility state of `:hover`-revealed nodes.

**Severity:** Medium · **Category:** Reliability (per reporting repo's triage)

## Environment

- Reported from: **Browser4** (platonai/Browser4) dev-mode evaluation `advanced-mouse-interaction` (scenario date `20260905-164437`), fixture `http://localhost:18080/generated/interactive-5.html` ("Advanced Interaction Playground"), where tooltips are spans toggled by CSS `:hover` from `visibility:hidden` to `visibility:visible`.
- Browser4 call chain: CLI `snapshot` → MCP `browser_snapshot` → driver AX snapshot (`ariaSnapshot`) → pulsar renderer. Browser4's own investigation concluded the divergence lives between its snapshot executor and the upstream AX serializer — on the pulsar side `AriaSnapshotFormatting` is internal and unreachable from the Browser4 repo — so the fix belongs in this repository.

## Reproduction

1. Load the fixture and keep the pointer away from the tooltip terms (both tooltip spans are `visibility:hidden`).
2. Run a full-page/aggregated snapshot grep for text that exists only inside the tooltips (e.g. `snapshot grep 'hierarchical representation|static capture'`) → it **matches** and prints both hidden tooltip texts merged into the paragraph/term names, e.g. `Accessibility Tree A hierarchical representation of the page ...`.
3. Hover a tooltip term so the tooltip becomes visible, then take a viewport snapshot (`snapshot -i -v 0`) → the now-visible tooltip content appears **nowhere**: no node, no ref, no text.
4. `getComputedStyle` via eval confirms the tooltip really is `visibility:visible` at that moment.

## Expected behavior

- CSS-hidden content must not appear in any snapshot output (names or grep matches).
- A hover-visible tooltip should be discoverable in a viewport snapshot — its own node/ref, or at least a box/geometry change — so "verify the tooltip appeared" works with snapshot commands.

## Actual behavior

- Aggregated/full-page renderings (snapshot grep and auto-saved snapshot YAML) include `visibility:hidden` descendant text regardless of hover state, so grep matches both before hover (false positive) and after hover — a match proves nothing about visibility.
- Viewport renderings omit the tooltip node entirely even when it is visible; the only observable difference between the two snapshot modes on this fixture was the box geometry of a different CSS mechanism (a card detail).
- The two paths disagree about hidden content, and neither exposes the rendered visibility state of `:hover`-revealed nodes.

## Root cause analysis

- The full-page aggregation path builds element names from descendant text that ignores CSS visibility (DOM-text based); the viewport path serializes per-node AX names and prunes hidden/ignored nodes. The exact divergence point requires comparing the capture path used for viewport-filtered vs full-page requests.
- Neither path exposes the rendered visibility state of `:hover`-revealed nodes, so hover verification cannot be expressed through snapshots at all.

## Suggested fix

- Make the text aggregation visibility-aware (innerText-like semantics) so `visibility:hidden` content never leaks into names/grep matches.
- Include hover/visibility state or geometry in serialized nodes so a tooltip that becomes visible is detectable in viewport snapshots.
- Reporting repo's human review note: prefer **visibility-aware pruning during the AX walk** over blanket innerText semantics (costly, and still won't surface a *visible* tooltip node); the geometry/visibility-state signal in serialized nodes is what actually enables snapshot-based verification.

## Browser4-side status

Human review decision in the reporting repo: **ACCEPT with improvements** (recorded in `coworker/tasks/main/3done/2026/0906/advanced-mouse-interaction-issues.md`, Issue 2 of scenario `20260905-164437`). Browser4 has meanwhile updated its docs to verify hover effects via eval/computed-style or geometry deltas as an interim workaround; the snapshot fix itself is tracked here.

## Relationship to #3

Related but distinct from #3 (ariaSnapshot `interactive` filter): #3 is about the *interactive-only filter* letting non-interactive nodes through. This issue is about *CSS visibility awareness*: `visibility:hidden` descendant text leaking into aggregated names/grep matches, and `:hover`-revealed (visible) nodes being pruned from viewport serializations. A strict interactive predicate does not address either symptom; the fixes likely touch the same serializer files.

Labels: bug
Repo: platonai/Browser4base
