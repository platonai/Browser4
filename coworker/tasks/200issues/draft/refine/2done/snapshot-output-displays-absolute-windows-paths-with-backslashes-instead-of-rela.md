# Snapshot output displays absolute Windows paths with backslashes instead of relative forward-slash paths

On Windows, snapshot paths in CLI output are printed as absolute paths with backslashes, which is noisy and inconsistent with the documentation examples that show relative forward-slash paths.

**Steps to reproduce:**
1. Use browser4-cli on Windows.
2. Run any command that produces a snapshot (e.g., `goto <url>`).
3. Observe the snapshot path in the output.

**Expected behavior:** The path shown matches the documentation style: `.browser4-cli/snapshot/page-<timestamp>.yml` (relative, forward slashes).

**Actual behavior:** The output prints an absolute path with Windows backslashes: `D:\workspace\Browser4\Browser4-4.11\cli\browser4-cli\.browser4-cli\snapshot\snapshot-2026-06-25T17-48-34Z.yml`. This is noisy and inconsistent with the documentation examples, making CLI output harder to scan.

**Suggested improvement:** Either always display snapshot paths relative to the working directory (using forward slashes), or normalize paths for display.

Labels: bug, ux, windows

