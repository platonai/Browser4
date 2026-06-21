# Snapshot file naming convention differs between documentation and actual output

The SKILL.md documentation shows snapshot files named with the prefix `page-*.yml`, but the actual CLI produces files named `snapshot-*.yml`. This inconsistency causes confusion when users try to locate files by following the documentation.

## Steps to reproduce

1. Run `browser4-cli open https://www.baidu.com` (which auto-generates a snapshot)
2. Run additional `browser4-cli snapshot` commands
3. Observe the generated file names

## Expected behavior

File names match the convention shown in documentation (`page-*.yml`).

## Actual behavior

Files are named `snapshot-2026-06-21T02-52-08Z.yml` (from `open`) while documentation examples show `page-2026-02-14T19-22-42-679Z.yml`.

## Additional context

- Align documentation examples with the actual output, or allow a configurable naming prefix.
- The inconsistency also suggests the naming prefix may have changed at some point without a corresponding documentation update.

