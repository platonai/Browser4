#!/usr/bin/env python3
"""List tasks with token usage extracted from Claude Code JSONL session traces.

Matches evaluation sessions to draft task files, computes token usage per
session, and prints a summary table.

Usage:
    python3 list-tasks.py [--recent N] [--detail] [--all]
"""

import os
import json
import re
import argparse
from datetime import datetime, timedelta, timezone
from collections import defaultdict

PROJECT_NAME = "-home-vincent-workspace-Browser4"
LOG_DIR = os.path.expanduser(f"~/.claude/projects/{PROJECT_NAME}")
DRAFT_DIR = "coworker/tasks/issues/draft"


def parse_size(s):
    """Format a token count for display."""
    if s >= 1_000_000:
        return f"{s/1_000_000:.1f}M"
    if s >= 1_000:
        return f"{s/1_000:.0f}k"
    return str(s)


def extract_task_signatures(draft_dir):
    """Extract first task step from each draft .issues.md file as a signature."""
    signatures = {}
    if not os.path.isdir(draft_dir):
        return signatures

    for fname in sorted(os.listdir(draft_dir)):
        if not fname.endswith(".issues.md"):
            continue
        m = re.match(r"\d{8}-\d{6}-(.+)\.issues\.md", fname)
        if not m:
            continue
        name = m.group(1)
        fpath = os.path.join(draft_dir, fname)
        try:
            with open(fpath, "r") as fh:
                content = fh.read()
            steps = re.findall(r"^\d+\.\s+(.+)$", content, re.MULTILINE)
            if steps:
                signatures[name] = steps[0][:120]
        except Exception:
            pass
    return signatures


def match_task(first_step, signatures):
    """Fuzzy-match a first task step against draft task signatures."""
    if not first_step or not signatures:
        return ""
    step_words = set(re.findall(r"\w+", first_step.lower()))
    if not step_words:
        return ""

    best_name = ""
    best_score = 0
    for tname, sig in signatures.items():
        sig_words = set(re.findall(r"\w+", sig.lower()))
        common = step_words & sig_words
        if len(sig_words) == 0:
            continue
        score = len(common) / max(len(sig_words), 1)
        if score > 0.4 and len(common) >= 3 and score > best_score:
            best_score = score
            best_name = tname
    return best_name


def analyze_sessions(log_dir, signatures, since=None):
    """Walk JSONL files and extract token usage per session."""
    if not os.path.isdir(log_dir):
        print(f"Error: log directory not found: {log_dir}")
        return []

    sessions = []
    for fname in os.listdir(log_dir):
        if not fname.endswith(".jsonl"):
            continue
        fpath = os.path.join(log_dir, fname)
        mtime = datetime.fromtimestamp(os.path.getmtime(fpath))

        if since and mtime < since:
            continue

        total_in = 0
        total_out = 0
        total_cache_read = 0
        total_cache_create = 0
        model = ""
        task_name = ""
        first_step = ""
        assistant_msgs = 0

        try:
            with open(fpath, "r") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                    except json.JSONDecodeError:
                        continue

                    if obj.get("type") == "assistant":
                        assistant_msgs += 1
                        usage = obj.get("message", {}).get("usage", {})
                        if usage:
                            total_in += usage.get("input_tokens", 0)
                            total_out += usage.get("output_tokens", 0)
                            total_cache_read += usage.get("cache_read_input_tokens", 0)
                            total_cache_create += usage.get(
                                "cache_creation_input_tokens", 0
                            )
                            if not model and usage.get("model"):
                                model = usage["model"]

                    if not first_step and obj.get("type") == "user":
                        content = obj.get("message", {}).get("content", "")
                        if isinstance(content, str):
                            # Extract task step
                            task_section = content
                            if "# Task" in content:
                                task_section = content.split("# Task")[-1]
                            m = re.search(
                                r"^\d+\.\s+(.+)$", task_section, re.MULTILINE
                            )
                            if m:
                                first_step = m.group(1)[:120]
        except Exception as e:
            continue

        if total_in < 1000:
            continue  # skip trivial sessions

        if not task_name and first_step:
            task_name = match_task(first_step, signatures)

        sessions.append(
            {
                "file": fname,
                "mtime": mtime,
                "model": model or "unknown",
                "task": task_name,
                "step": first_step[:80] if first_step else "",
                "in": total_in,
                "out": total_out,
                "cache_read": total_cache_read,
                "cache_create": total_cache_create,
                "total": total_in + total_out + total_cache_read + total_cache_create,
                "msgs": assistant_msgs,
            }
        )

    sessions.sort(key=lambda s: s["mtime"])
    return sessions


def format_model(m):
    """Shorten a model name for display."""
    return m.split("/")[-1] if "/" in m else m


def print_summary(sessions):
    """Print a grouped summary by task."""
    # Group by task
    groups = defaultdict(list)
    for s in sessions:
        key = s["task"] or s["step"] or "(unknown)"
        groups[key].append(s)

    print(f"\n{'Task':<34} | {'Runs':>4} | {'In Tok':>8} | {'Out Tok':>7} | {'Cache':>8} | {'TOTAL':>9} | {'Avg/run':>8}")
    print("-" * 120)

    # Sort by total tokens descending
    sorted_groups = sorted(groups.items(), key=lambda g: sum(x["total"] for x in g[1]), reverse=True)

    grand_in = 0
    grand_out = 0
    grand_cache = 0
    grand_total = 0
    grand_runs = 0

    for label, items in sorted_groups:
        total_in = sum(s["in"] for s in items)
        total_out = sum(s["out"] for s in items)
        total_cache = sum(s["cache_read"] for s in items) + sum(s["cache_create"] for s in items)
        total = sum(s["total"] for s in items)
        runs = len(items)
        avg = total // runs if runs else 0

        grand_in += total_in
        grand_out += total_out
        grand_cache += total_cache
        grand_total += total
        grand_runs += runs

        # Truncate label for display
        display = label[:33]
        print(f"{display:<34} | {runs:>4} | {parse_size(total_in):>8} | {parse_size(total_out):>7} | {parse_size(total_cache):>8} | {parse_size(total):>9} | {parse_size(avg):>8}")

    print("-" * 120)
    print(f"{'TOTAL':<34} | {grand_runs:>4} | {parse_size(grand_in):>8} | {parse_size(grand_out):>7} | {parse_size(grand_cache):>8} | {parse_size(grand_total):>9}")
    print(f"\n  {grand_runs} sessions  |  {grand_total:,} total tokens")


def print_detail(sessions):
    """Print per-session detail."""
    print(f"\n{'Time':<14} | {'Task':<32} | {'In':>8} | {'Out':>7} | {'CacheR':>8} | {'TOTAL':>9} | Model")
    print("-" * 130)

    for s in sessions:
        label = s["task"] or s["step"][:31] or "(unknown)"
        print(
            f"{s['mtime'].strftime('%m-%d %H:%M'):<14} | {label:<32} | {parse_size(s['in']):>8} | {parse_size(s['out']):>7} | {parse_size(s['cache_read']):>8} | {parse_size(s['total']):>9} | {format_model(s['model']):<20}"
        )

    total_all = sum(s["total"] for s in sessions)
    print("-" * 130)
    print(
        f"{'':<14} | {'TOTAL (' + str(len(sessions)) + ' sessions)':<32} | {parse_size(sum(s['in'] for s in sessions)):>8} | {parse_size(sum(s['out'] for s in sessions)):>7} | {parse_size(sum(s['cache_read'] for s in sessions)):>8} | {parse_size(total_all):>9}"
    )


def main():
    parser = argparse.ArgumentParser(
        description="List tasks with token usage from Claude Code session traces"
    )
    parser.add_argument(
        "--recent",
        type=int,
        default=24,
        help="Only show sessions from last N hours (default: 24, use 0 for all)",
    )
    parser.add_argument(
        "--detail", "-d", action="store_true", help="Show per-session breakdown"
    )
    parser.add_argument(
        "--all", "-a", action="store_true", help="Show all sessions (no time filter)"
    )
    args = parser.parse_args()

    since = None
    if not args.all and args.recent > 0:
        since = datetime.now() - timedelta(hours=args.recent)

    signatures = extract_task_signatures(DRAFT_DIR)
    sessions = analyze_sessions(LOG_DIR, signatures, since=since)

    if not sessions:
        print("No sessions found.")
        return

    time_range = f"last {args.recent}h" if since else "all time"
    print(f"=== Task Token Usage ({time_range}) ===")

    if args.detail:
        print_detail(sessions)
    else:
        print_summary(sessions)


if __name__ == "__main__":
    main()
