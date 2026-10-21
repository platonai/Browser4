# JavaScript `eval` Variables Persist Across Calls, Causing Redeclaration Errors

The JavaScript evaluation context in `browser4-cli eval` persists across invocations within the same session. This causes `const` and `let` redeclaration errors on subsequent calls that reuse variable names, which is surprising behavior for users who expect a clean scope per command.

**Steps to Reproduce:**
1. Run `browser4-cli eval "const r = document.querySelector('...')"`.
2. Run another `browser4-cli eval "const r = document.querySelector('...')"`.

**Expected Behavior:** Each `eval` call has a clean JavaScript context, or the scoping rules are clearly documented so users know to expect shared state.

**Actual Behavior:** The second call fails with `Identifier 'r' has already been declared`. The current workaround is to wrap code in an IIFE: `(function() { ... })()`.

**Suggested Improvement:** Either:
- Automatically wrap each `eval` invocation in its own block scope so `const`/`let` declarations do not leak across calls.
- Document the persistence behavior and recommended patterns (IIFE, unique variable names) in `SKILL.md` and help output.

**Acceptance Criteria:**
- Running the same `const` declaration in two consecutive `eval` calls does not produce a redeclaration error.
- OR: the variable persistence behavior is clearly documented with recommended workarounds.

Labels: bug

