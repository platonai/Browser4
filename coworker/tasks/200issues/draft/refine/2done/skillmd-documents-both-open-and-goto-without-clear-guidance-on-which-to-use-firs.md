# SKILL.md documents both `open` and `goto` without clear guidance on which to use first

The documentation lists both `open [url]` and `goto [url]` without making the recommended primary entry point obvious to new users. The distinction between "open a session" and "navigate to a URL" is subtle for users who don't yet understand the session model.

**Steps to reproduce:**
1. Read SKILL.md for the first time.
2. See both `open` and `goto` listed in the command table.
3. Try to determine which command to use for first-time navigation.

**Expected behavior:** Clear, opinionated guidance upfront about which command to use for first navigation. A prominent "Quick Start" section should show the recommended entry point.

**Actual behavior:** The SKILL.md states "Prefer `goto` over manual session management" in the prose, but the command table lists `open` first. The distinction between "open a session" and "navigate to a URL" is subtle for new users who don't yet understand the session model.

**Suggested improvement:** Add a prominent "Quick Start" section showing `goto <url>` as the primary entry point. Move session management details (`open`, `close`) to an advanced section.

Labels: documentation

