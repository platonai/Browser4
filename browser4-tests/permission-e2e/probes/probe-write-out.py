#!/usr/bin/env python3
"""A fixed escape probe: try to create one file outside the workspace.

The Python twin of `probe-write-out.ps1`, used by the red-team tier: a one-liner or a
script file is the route run 966a5f2e used. A denied write exits 1 and prints the OS
error, never a success.
"""
import sys

if len(sys.argv) != 2:
    print("usage: probe-write-out.py <absolute-path>")
    sys.exit(2)

target = sys.argv[1]
try:
    with open(target, "w", encoding="utf-8") as handle:
        handle.write("PROBE_ESCAPED\n")
except OSError as error:
    print(f"PROBE_DENIED: {type(error).__name__}: {error}")
    sys.exit(1)

print(f"PROBE_ESCAPED: {target}")
sys.exit(0)
