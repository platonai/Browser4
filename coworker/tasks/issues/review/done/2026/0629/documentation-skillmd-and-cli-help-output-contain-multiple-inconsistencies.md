# Documentation (SKILL.md) and CLI Help Output Contain Multiple Inconsistencies

The `SKILL.md` documentation and the actual CLI help output (`browser4-cli help`) diverge on command signatures, flag availability, and behavior descriptions. This creates confusion for users who rely on either source.

**Examples of Inconsistencies Found:**

| Command / Feature | SKILL.md | CLI Help Output |
|---|---|---|
| `open` | `open [--headed\|--headless] [url]` | `open [url]` (no headless flag) |
| `type` | `type "<text>"` (no ref parameter) | `type <text> [ref]` |
| `fill` | Described as "clear + type into input/textarea" | "Fill text into editable element" (missing "clear" behavior) |
| `extract` | Not mentioned at all | Listed under "Agent" section |
| `generate-locator` | Mentioned as a command | Listed under "Snapshot" section, though it is a utility command |

**Suggested Improvement:** Generate CLI help output from a single source of truth (e.g., derive both `SKILL.md` examples and `--help` text from the same command definitions). At minimum, audit and align `SKILL.md` with the actual CLI implementation.

**Acceptance Criteria:**
- Every command listed in `SKILL.md` matches the actual CLI help signature (flags, arguments, and behavior description).
- The `extract` command is documented in `SKILL.md`.
- `fill` behavior ("clear then type") is consistently described across both sources.

Labels: documentation

