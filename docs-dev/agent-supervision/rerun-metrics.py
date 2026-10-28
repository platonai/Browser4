#!/usr/bin/env python3
"""Summarize one supervised `agent run` from its on-disk trace.

Usage:
    python rerun-metrics.py                 # newest run under the app-data agent logs
    python rerun-metrics.py <run-dir>       # a specific .../logs/agent/<ts>/<uuid> dir

Reads only written artifacts (cli-tool-trace.jsonl, cli-usage.jsonl, cli-prompt/,
the memory events tail) plus the task workspace, so it works after the backend is
gone.
"""
import collections
import csv
import glob
import json
import os
import re
import sys
from datetime import datetime

APP_DATA = r"C:\Users\pereg\.browser4\workspaces\Browser4-4.15-feat-2ec92531\app-data"


def newest_run_dir() -> str:
    root = os.path.join(APP_DATA, "logs", "agent")
    runs = []
    for ts in glob.glob(os.path.join(root, "*")):
        for uuid in glob.glob(os.path.join(ts, "*")):
            trace = os.path.join(uuid, "cli-tool-trace.jsonl")
            if os.path.exists(trace):
                runs.append((os.path.getmtime(trace), uuid))
    if not runs:
        raise SystemExit("no agent run found under " + root)
    return max(runs)[1]


def read_jsonl(path):
    if not os.path.exists(path):
        return []
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def main() -> None:
    run_dir = sys.argv[1] if len(sys.argv) > 1 else newest_run_dir()
    name = os.path.basename(run_dir)
    trace = read_jsonl(os.path.join(run_dir, "cli-tool-trace.jsonl"))
    usage = read_jsonl(os.path.join(run_dir, "cli-usage.jsonl"))
    prompts = sorted(glob.glob(os.path.join(run_dir, "cli-prompt", "*.request.json")))

    print(f"run: {run_dir}")
    if trace:
        print(f"first tool: {trace[0].get('timestamp')}   last tool: {trace[-1].get('timestamp')}")
    print(f"tool calls: {len(trace)}")
    counts = collections.Counter(row.get("tool") for row in trace)
    print("  top:", counts.most_common(10))
    denied = [row for row in trace if "PERMISSION_DENIED" in (row.get("resultText") or "")]
    failed = [row for row in trace if re.search(r"\[fail\]|\[exit code: [1-9]", row.get("resultText") or "")]
    parser_errors = [row for row in trace if "ParserError" in (row.get("resultText") or "")]
    print(f"  denied: {len(denied)}   failed: {len(failed)}   ParserError: {len(parser_errors)}")

    total_in = sum(row.get("inputTokens") or 0 for row in usage)
    total_out = sum(row.get("outputTokens") or 0 for row in usage)
    print(f"requests: {len(usage)}   input tokens: {total_in:,}   output tokens: {total_out:,}")
    print(f"prompt dumps: {len(prompts)}")

    done = [row for row in trace if row.get("tool") == "system_taskComplete"]
    print(f"taskComplete calls: {len(done)}")
    if done:
        text = done[-1].get("resultText") or ""
        print("  summary head:", text[:300].replace("\n", " "))

    # Workspace: the run dir's sibling workspace is not beside the trace; find the newest
    # task workspace by mtime instead (the caller can pass a run dir with a known task id).
    task_dirs = sorted(
        glob.glob(os.path.join(APP_DATA, "agent", "*", "*", "*", "*", "workspace")),
        key=os.path.getmtime,
    )
    if task_dirs:
        workspace = task_dirs[-1]
        print(f"workspace: {workspace}")
        files = []
        for root, _dirs, names in os.walk(workspace):
            for filename in names:
                path = os.path.join(root, filename)
                files.append((os.path.relpath(path, workspace), os.path.getsize(path)))
        for rel, size in sorted(files):
            print(f"  {rel}  ({size:,} B)")
        for rel, _ in sorted(files):
            if rel.lower().endswith(".csv"):
                path = os.path.join(workspace, rel)
                try:
                    with open(path, encoding="utf-8", newline="") as handle:
                        rows = list(csv.reader(handle))
                    non_empty = sum(1 for row in rows[1:] if any(cell.strip() for cell in row))
                    print(f"  CSV {rel}: {len(rows) - 1} rows, {len(rows[0]) if rows else 0} cols, {non_empty} non-empty")
                except Exception as error:  # noqa: BLE001 - diagnostics only
                    print(f"  CSV {rel}: unreadable ({error})")


if __name__ == "__main__":
    main()
