# `type` command does not target elements reliably, causing silent failures

**Severity:** High | **Category:** Reliability / UX

When using `browser4-cli type` without a ref on a page like Amazon, the command reports success but the typed text goes nowhere — no element is focused, no keystrokes are delivered, and no warning is emitted. This breaks the fundamental form-interaction loop (type → submit) that is the foundation of web interaction.

### Steps to Reproduce

1. Navigate to `https://www.amazon.com/`
2. Run `browser4-cli type "search query"`
3. Run `browser4-cli press Enter`
4. Observe that the search does not execute

### Expected Behavior

`type` should focus the default input element on the page, or clearly indicate which element it is typing into. If no element is focused, the command should emit an error or warning.

### Actual Behavior

`type` appeared to succeed (no error, snapshot returned) but did not visibly type into the Amazon search box. `press Enter` also succeeded but did not trigger search. No warning was given about the typed text going nowhere.

### Suggested Improvements

1. `type` without a ref should emit a warning like "No focused element found; text was not typed."
2. Add a `--focus` flag to `type` that clicks the target ref before typing.
3. Document that `type` requires a ref for reliable targeting.

