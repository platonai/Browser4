## Daily Memory - 2026-03-14

- **DOM fidelity boundaries:** Documented the `MergedDOMTree -> TinyDOMTreeNode -> MicroDOMTreeNode` path in `DOMStateBuilder.kt` and where prompt-oriented compaction loses DOM/AX fidelity. Structural rule: fidelity-sensitive logic must run before lossy compaction or retain access to richer trees.

- **Fast regression checks:** `DOMStateBuilderTest` remained the smallest reliable check for DOM serialization / aria snapshot changes; best focused pair was `MicroToNanoTreeHelperTest` + `DOMStateBuilderTest`. In PowerShell, comma-separated Surefire class lists must be quoted in one `-Dtest=...`.

- **Nano-tree range pruning:** Fixed `toNanoTreeInRangeRecursive()` so it recurses first, then prunes only empty placeholder nano nodes. This preserved valid prompt leaves previously deleted by pre-recursion pruning. Added coverage for overlapping leaves, bounds, seen-chunk merges, invalid intervals, and full-range consistency.

- **Canonical full-range behavior:** Reviewed `toNanoTree()`, `toNanoTreeInRange()`, and `toNanoTreeUnfiltered()`, then made sentinel full-range (`0.0..1_000_000.0`) delegate to the unfiltered path. Rule: if “full range” means unfiltered, both must share one code path or drift drops geometry-less / zero-height / sentinel-exceeding nodes.

- **Earlier aria rendering:** Added `AriaSnapshotRenderer`, made `DOMState.ariaSnapshot` prefer `EnhancedDOMTreeNode`, extracted shared YAML helpers, and kept `AriaSnapshotForNanoDOMTreeRenderer` deprecated for compatibility. Lesson: render aria from richer trees when fidelity matters.

- **Playwright aria parity:** Improved formatting so `[cursor=pointer]` appears only at the highest rendered ref in a pointer subtree, collapsed unnamed generic wrappers, filtered default AX noise, derived pointer hints from real snapshot/style data, and promoted title/description into fallback names for titled generic nodes. Parity depended on recursive formatting state, AX-noise filtering, and useful fallback naming.

- **Invalid aria node filtering:** `AriaSnapshotRenderer` now skips comment, `script`, and `style` nodes before recursion while still dropping blank normalized text, keeping snapshots cleaner.

- **Nano aria semantic-role fix:** Investigated why `domState.serializableTree.toNanoTree().ariaSnapshot` downgraded semantic controls like `button` to `generic` while `domState.ariaSnapshot` stayed correct. Root cause: `DOMStateBuilder` removes duplicate compacted `role` attrs when they match native semantics, but legacy `NanoAriaSnapshotRenderer` relied only on flattened attrs and did not recover implicit roles. Fix: keep compacted Nano shape but infer native roles when explicit `role` is absent (`button`, `a[href]`, `input` variants, `textarea`, `select`, `summary`, `img`, `option`). Added regression coverage. Lesson: duplicate-role elision is safe only if downstream renderers recover native semantics from element type.

- **Timeout skip hardening:** In `UniversalProxyParserTest.kt`, only known `ChatModelException` timeout messages become JUnit assumption skips; all other failures are rethrown.

- **Surefire / E2E behavior:** Focused `UniversalProxyParserTest` ran 0 tests until rerun with `-DrunE2ETests=true`, then skipped locally due to missing LLM config. `@Tag("RequiresServer")` still requires `-DrunE2ETests=true` in focused Surefire runs.

- **Wrapper/tooling updates:** Updated `bin/test.ps1`, `bin/test.sh`, and `bin/README.md` to remove deleted `kotlin-sdk` / `python-sdk` modes, keep `nodejs-sdk` mapped to `sdks/browser4-cli`, fail clearly on removed modes, and always pass `-P=-examples` to avoid the missing `browser4/browser4-spa` module. `bash -n ./bin/test.sh` passed after restoring LF endings.

- **E2E renderer loop:** Added `AriaSnapshotRendererE2ETest` in `pulsar-it-tests` using a real Spring-served page with nested frames. Normalize dynamic refs in assertions instead of comparing raw IDs/indentation. Reliable validation loop: pulsar-browser unit test -> local install -> focused `pulsar-it-tests` E2E with `-DrunE2ETests=true`.

- **Scrollable snapshot cleanup:** Re-ran `SnapshotServiceIsScrollableTest`; brittleness was in expectations, not `CDPSnapshotService`. Updated assertions to stable guarantees only. Snapshot tests should target supported guarantees, not optimistic generic-container scrollability.

- **Memory policy:** Verified before appending and never rewrote earlier monthly entries; March already contained the `2026-03-13` rollup, so no backfill was needed.

- **Coworker dotfile-ignore hardening:** Updated the shared coworker file filter so dotfiles and files under dot-directories are always ignored, not just `.gitkeep`. Routed `coworker.ps1` queue scans through the shared helper so draft/created/review/approved/pushed logging and processing consistently skip hidden queue artifacts. Validation covered `task.md`, `.env`, `.gitkeep`, and `.git\HEAD`. Lesson: ignore rules must check ancestor path segments, or recursive scans can still leak hidden-directory files.

- **Memory sync check:** Verified `coworker\tasks\300logs\2026\03\MEMORY.202603.md` already contained the `2026-03-13` rollup, so no monthly backfill was needed.



- **MCPToolController E2E fixes:** Fixed `MCPToolController` so REST error payloads serialize the boolean under the canonical `isError` field (instead of dropping/renaming it), stripped controller-only `sessionId` before dispatching agent tools to avoid extraneous-parameter noise, and made `delete_session_data` clear cookies plus best-effort web storage without failing on browser `SecurityError` pages. Added unit coverage for `isError` serialization and verified with `\.\mvnw.cmd -f browser4-rest\pom.xml "-Dtest=MCPToolControllerTest" -D"surefire.failIfNoSpecifiedTests=false" test`, `\.\mvnw.cmd -f browser4-rest\pom.xml -DskipTests install`, and `\.\mvnw.cmd -f pulsar-tests\pom.xml -pl browser4-rest-tests -am -DrunE2ETests=true "-Dtest=MCPToolControllerE2ETest#testUnknownToolReturnsError+testMissingSessionIdReturnsError+testInvalidSessionIdReturnsError+testCloseSessionNotFound+testDeleteSessionData" -D"surefire.failIfNoSpecifiedTests=false" test`. Lesson: `browser4-rest-tests` consumes `browser4-rest` from the local Maven repo, so controller changes must be installed locally before focused sibling-module E2Es.
