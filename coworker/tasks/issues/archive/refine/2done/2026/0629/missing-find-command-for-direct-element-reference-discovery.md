# Missing `find` Command for Direct Element Reference Discovery

There is no command to search the accessibility tree for an element by name or role and return its reference (`ref`). Users must capture a full snapshot, read a potentially large YAML file, and manually scan or grep for the desired element — a slow and error-prone process.

**Steps to Reproduce:**
1. Want to interact with the "Search Amazon" searchbox on a page.
2. Look for a command to discover its reference.

**Expected Behavior:** A command such as `browser4-cli find "Search Amazon"` returns `searchbox "Search Amazon" [ref=e48]` directly.

**Actual Behavior:** The user must:
1. Run `browser4-cli snapshot -i` to capture the accessibility tree.
2. Read the resulting YAML file (often hundreds of lines).
3. Visually scan or grep for the element name.
4. Extract the reference manually.

The `-i` (interactive only) and `-c` (compact) flags reduce output size but do not eliminate the multi-step discovery process.

**Suggested Improvement:** Add a `browser4-cli find "<name|role>"` command that searches the accessibility tree server-side and returns matching elements with their names, roles, and references.

**Acceptance Criteria:**
- Running `browser4-cli find "Search Amazon"` on an Amazon homepage returns the searchbox element with its reference.
- The command supports searching by accessible name and by ARIA role.

Labels: enhancement, ux

