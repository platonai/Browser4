#!/usr/bin/env python3
"""Organize task files across the Coworker task state machine.

Scans coworker/tasks/ for .full.md and .issues.md files, matches pairs,
finds duplicates, flags empty issues files, and can move files between
pipeline stages.

Usage:
    python3 organize.py summary
    python3 organize.py list [--dir DIR]
    python3 organize.py pairs [--dir DIR] [--json]
    python3 organize.py dupes [--dir DIR] [--json]
    python3 organize.py empty [--dir DIR] [--json]
    python3 organize.py move --filter FILTER --target DIR [--force]
"""

import os
import re
import sys
import json
import shutil
import argparse
from collections import defaultdict
from datetime import datetime

BASE_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "tasks"
)


def parse_filename(fname):
    """Parse a task filename, returning (timestamp, taskname, filetype, ext).

    Handles:
      - YYYYMMDD-HHMMSS-taskname.full.md  → (datetime, taskname, 'full', '.md')
      - YYYYMMDD-HHMMSS-taskname.issues.md → (datetime, taskname, 'issues', '.md')
      - taskname.md                         → (None, taskname, None, '.md')
      - taskname.issues.md                  → (None, taskname, 'issues', '.md')
    """
    ts_pat = r"^(\d{8}-\d{6})-(.+?)\.(full|issues)\.md$"
    m = re.match(ts_pat, fname)
    if m:
        ts_str, name, ftype = m.groups()
        try:
            ts = datetime.strptime(ts_str, "%Y%m%d-%H%M%S")
        except ValueError:
            ts = None
        return ts, name, ftype, ".md"

    # Plain name.issues.md
    m2 = re.match(r"^(.+?)\.(issues)\.md$", fname)
    if m2:
        return None, m2.group(1), m2.group(2), ".md"

    # Plain name.md
    m3 = re.match(r"^(.+)\.md$", fname)
    if m3:
        return None, m3.group(1), None, ".md"

    return None, fname.rsplit(".", 1)[0] if "." in fname else fname, None, ".md"


def pipeline_stage(dirpath, fname):
    """Classify a file by its pipeline stage based on directory path."""
    path = os.path.join(dirpath, fname).replace(str(BASE_DIR) + "/", "")

    # issues/review/done/discard
    if "issues/review/done/discard" in path:
        return "review/done/discard"
    # issues/review/done
    if "issues/review/done" in path:
        return "review/done"
    # issues/review
    if "issues/review" in path:
        return "review"
    # issues/draft/refine
    if "issues/draft/refine/0ready" in path:
        return "draft/refine/0ready"
    if "issues/draft/refine/1working" in path:
        return "draft/refine/1working"
    if "issues/draft/refine/2done" in path:
        return "draft/refine/2done"
    if "issues/draft/refine/0error" in path:
        return "draft/refine/0error"
    # issues/draft
    if "issues/draft" in path:
        return "issues/draft"
    # issues/local
    if "issues/local/done" in path:
        return "local/done"
    if "issues/local/wontfix" in path:
        return "local/wontfix"
    # issues/github
    if "issues/github/commit/done" in path:
        return "github/done"
    if "issues/github/commit/failed" in path:
        return "github/failed"
    if "issues/github/commit/ready" in path:
        return "github/ready"
    # main pipeline
    if "main/0draft" in path:
        return "main/0draft"
    if "main/1ready" in path:
        return "main/1ready"
    if "main/2working" in path:
        return "main/2working"
    if "main/3done" in path:
        return "main/3done"
    if "main/4review" in path:
        return "main/4review"
    if "main/5approved" in path:
        return "main/5approved"
    if "main/6git-pushed" in path:
        return "main/6git-pushed"

    # fallback: use relative path from tasks/
    return path.split("/")[0] if "/" in path else path


def scan_files(base_dirs=None):
    """Scan task directories and return a list of file records.

    Each record: {
        'path': absolute_path,
        'relpath': relative from tasks/,
        'stage': pipeline stage,
        'name': task name,
        'type': 'full' | 'issues' | None,
        'ts': datetime or None,
        'fname': filename,
        'size': file size in bytes,
    }
    """
    if base_dirs is None:
        # Default: scan issues, main
        base_dirs = [
            os.path.join(BASE_DIR, "issues"),
            os.path.join(BASE_DIR, "main"),
        ]

    files = []
    for base in base_dirs:
        if not os.path.isdir(base):
            continue
        for root, dirs, fnames in os.walk(base):
            # Skip .gitkeep and lock files
            if ".locks" in root:
                continue
            for fname in fnames:
                if fname == "INDEX.md" or fname == "README.md":
                    continue
                if fname.endswith(".json") and not fname.endswith(".md"):
                    continue  # skip .json state files
                if not fname.endswith(".md"):
                    continue
                # Skip .md files that are actually JSON
                fpath = os.path.join(root, fname)
                if fname.endswith(".md"):
                    ts, name, ftype, ext = parse_filename(fname)
                    stage = pipeline_stage(root, fname)
                    size = os.path.getsize(fpath)
                    files.append(
                        {
                            "path": fpath,
                            "relpath": os.path.relpath(fpath, BASE_DIR),
                            "stage": stage,
                            "name": name,
                            "type": ftype,
                            "ts": ts,
                            "fname": fname,
                            "size": size,
                        }
                    )

    files.sort(key=lambda f: (f["stage"], f.get("ts") or datetime.min, f["name"]))
    return files


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------


def cmd_summary(files):
    """Print a summary overview of all pipeline stages."""
    stages = defaultdict(lambda: {"total": 0, "full": 0, "issues": 0, "plain": 0})
    for f in files:
        s = stages[f["stage"]]
        s["total"] += 1
        if f["type"] == "full":
            s["full"] += 1
        elif f["type"] == "issues":
            s["issues"] += 1
        else:
            s["plain"] += 1

    print(f"\n{'Pipeline Stage':<30} {'Total':>6} {'Full':>6} {'Issues':>7} {'Plain':>6}")
    print("-" * 60)
    grand = {"total": 0, "full": 0, "issues": 0, "plain": 0}
    for stage in sorted(stages.keys()):
        s = stages[stage]
        for k in grand:
            grand[k] += s[k]
        print(f"{stage:<30} {s['total']:>6} {s['full']:>6} {s['issues']:>7} {s['plain']:>6}")
    print("-" * 60)
    print(f"{'GRAND TOTAL':<30} {grand['total']:>6} {grand['full']:>6} {grand['issues']:>7} {grand['plain']:>6}")
    print()


def cmd_list(files, limit_stage=None):
    """List all files grouped by stage."""
    if limit_stage:
        files = [f for f in files if f["stage"] == limit_stage]

    by_stage = defaultdict(list)
    for f in files:
        by_stage[f["stage"]].append(f)

    for stage in sorted(by_stage.keys()):
        items = by_stage[stage]
        print(f"\n── {stage} ({len(items)} files) ──")
        for f in items:
            ts_str = f["ts"].strftime("%Y-%m-%d %H:%M") if f["ts"] else "----"
            ftype = f["type"] or "md"
            size_kb = f["size"] / 1024
            print(f"  {ts_str}  {ftype:>6}  {size_kb:>7.1f}K  {f['fname']}")


def cmd_pairs(files, limit_stage=None, as_json=False):
    """Match .full.md ↔ .issues.md pairs and flag orphans."""
    if limit_stage:
        files = [f for f in files if f["stage"] == limit_stage]

    # Group by (stage, name)
    by_key = defaultdict(lambda: {"full": [], "issues": []})
    for f in files:
        if f["type"] == "full":
            by_key[(f["stage"], f["name"])]["full"].append(f)
        elif f["type"] == "issues":
            by_key[(f["stage"], f["name"])]["issues"].append(f)

    paired = []
    full_only = []
    issues_only = []

    for (stage, name), group in sorted(by_key.items()):
        fulls = group["full"]
        issues = group["issues"]
        if fulls and issues:
            paired.append((stage, name, fulls, issues))
        elif fulls:
            full_only.extend(fulls)
        elif issues:
            issues_only.extend(issues)

    if as_json:
        result = {
            "paired": [
                {
                    "stage": stage,
                    "name": name,
                    "full_count": len(fulls),
                    "issues_count": len(issues),
                    "full_files": [f["fname"] for f in fulls],
                    "issues_files": [f["fname"] for f in issues],
                }
                for stage, name, fulls, issues in paired
            ],
            "full_only": [
                {"stage": f["stage"], "name": f["name"], "fname": f["fname"]}
                for f in full_only
            ],
            "issues_only": [
                {"stage": f["stage"], "name": f["name"], "fname": f["fname"]}
                for f in issues_only
            ],
        }
        print(json.dumps(result, indent=2, default=str))
        return

    # Pretty print
    print(f"\n=== Paired (.full.md ↔ .issues.md): {len(paired)} tasks ===")
    for stage, name, fulls, issues in paired:
        nf, ni = len(fulls), len(issues)
        flag = ""
        if nf > 1 or ni > 1:
            flag = f"  ⚠ {nf}F/{ni}I (duplicates!)"
        elif nf == 1 and ni == 1:
            f_size = fulls[0]["size"] / 1024
            i_size = issues[0]["size"] / 1024
            flag = f"  F:{f_size:.0f}K  I:{i_size:.0f}K"
        print(f"  [{stage}] {name}{flag}")

    print(f"\n=== Full-only (no matching .issues.md): {len(full_only)} files ===")
    for f in full_only:
        print(f"  [{f['stage']}] {f['fname']}")

    print(f"\n=== Issues-only (no matching .full.md): {len(issues_only)} files ===")
    for f in issues_only:
        print(f"  [{f['stage']}] {f['fname']}")


def cmd_dupes(files, limit_stage=None, as_json=False):
    """Find duplicate task names."""
    if limit_stage:
        files = [f for f in files if f["stage"] == limit_stage]

    # Group by (name, type)
    by_name = defaultdict(list)
    for f in files:
        by_name[(f["name"], f["type"])].append(f)

    dupes = {
        key: vals
        for key, vals in by_name.items()
        if len(vals) > 1
    }

    if as_json:
        result = [
            {
                "name": name,
                "type": ftype or "plain",
                "count": len(vals),
                "files": [
                    {"stage": v["stage"], "fname": v["fname"], "size": v["size"]}
                    for v in vals
                ],
            }
            for (name, ftype), vals in sorted(dupes.items())
        ]
        print(json.dumps(result, indent=2))
        return

    print(f"\n=== Duplicate Tasks: {len(dupes)} names with multiple files ===")
    for (name, ftype), vals in sorted(dupes.items()):
        print(f"\n  {name}  ({ftype or 'plain'}) — {len(vals)} copies:")
        for v in vals:
            ts_str = v["ts"].strftime("%Y-%m-%d %H:%M") if v["ts"] else "----"
            size_kb = v["size"] / 1024
            print(f"    {ts_str}  [{v['stage']}]  {size_kb:.1f}K  {v['fname']}")


def cmd_empty(files, limit_stage=None, as_json=False):
    """Find .issues.md files with zero issues."""
    if limit_stage:
        files = [f for f in files if f["stage"] == limit_stage]

    empty_files = []
    for f in files:
        if f["type"] != "issues":
            continue
        try:
            with open(f["path"], "r") as fh:
                content = fh.read(4096)  # check first 4KB
            if "Issues Found (0)" in content or "## Issues Found (0)" in content:
                empty_files.append(f)
        except Exception:
            pass

    if as_json:
        result = [
            {
                "stage": f["stage"],
                "name": f["name"],
                "fname": f["fname"],
                "path": f["path"],
                "size": f["size"],
            }
            for f in empty_files
        ]
        print(json.dumps(result, indent=2))
        return

    print(f"\n=== Empty .issues.md Files (0 issues): {len(empty_files)} ===")
    for f in empty_files:
        ts_str = f["ts"].strftime("%Y-%m-%d %H:%M") if f["ts"] else "----"
        print(f"  {ts_str}  [{f['stage']}]  {f['fname']}")


def cmd_move(files, filter_type, target_dir, force=False, limit_stage=None):
    """Move files matching a filter to a target directory.

    filter_type:
      - 'empty' → .issues.md files with 0 issues
      - 'full-only' → .full.md files without matching .issues.md
      - 'issues-only' → .issues.md files without matching .full.md
    """
    if limit_stage:
        files = [f for f in files if f["stage"] == limit_stage]

    # Determine which files to move
    to_move = []

    if filter_type == "empty":
        to_move = cmd_empty(files, limit_stage=limit_stage, as_json=False) or []
        # Re-get list since cmd_empty prints and returns None
        to_move = []
        for f in files:
            if f["type"] != "issues":
                continue
            try:
                with open(f["path"], "r") as fh:
                    content = fh.read(4096)
                if "Issues Found (0)" in content or "## Issues Found (0)" in content:
                    to_move.append(f)
            except Exception:
                pass

    elif filter_type in ("full-only", "issues-only"):
        # Build pair map
        by_key = defaultdict(lambda: {"full": [], "issues": []})
        for f in files:
            if f["type"] == "full":
                by_key[(f["stage"], f["name"])]["full"].append(f)
            elif f["type"] == "issues":
                by_key[(f["stage"], f["name"])]["issues"].append(f)

        ft = "full" if filter_type == "full-only" else "issues"
        other = "issues" if ft == "full" else "full"
        for (stage, name), group in by_key.items():
            if group[ft] and not group[other]:
                to_move.extend(group[ft])

    else:
        print(f"Unknown filter: {filter_type}")
        print("Valid filters: empty, full-only, issues-only")
        sys.exit(1)

    if not to_move:
        print("No files to move.")
        return

    # Determine target path
    target_path = target_dir
    if not os.path.isabs(target_path):
        target_path = os.path.join(BASE_DIR, target_path)

    print(f"\n{'Would move' if not force else 'Moving'} {len(to_move)} file(s) to {target_path}:")
    for f in to_move:
        dest = os.path.join(target_path, f["fname"])
        print(f"  {f['relpath']}")
        print(f"    → {os.path.relpath(dest, BASE_DIR)}")

    if not force:
        print("\n  (dry-run: use --force to actually move)")
        return

    # Create target dir if needed
    os.makedirs(target_path, exist_ok=True)

    moved = 0
    errors = 0
    for f in to_move:
        dest = os.path.join(target_path, f["fname"])
        try:
            shutil.move(f["path"], dest)
            moved += 1
        except Exception as e:
            print(f"  ERROR moving {f['fname']}: {e}")
            errors += 1

    print(f"\nMoved: {moved}, Errors: {errors}")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(
        description="Organize task files across Coworker task pipelines"
    )
    sub = parser.add_subparsers(dest="command", help="Command to run")

    # summary
    sub.add_parser("summary", help="Overview of all pipeline stages")

    # list
    p_list = sub.add_parser("list", help="List all task files")
    p_list.add_argument("--dir", help="Filter by pipeline stage")
    p_list.add_argument("--json", action="store_true", help="JSON output")

    # pairs
    p_pairs = sub.add_parser("pairs", help="Match .full.md ↔ .issues.md pairs")
    p_pairs.add_argument("--dir", help="Filter by pipeline stage")
    p_pairs.add_argument("--json", action="store_true", help="JSON output")

    # dupes
    p_dupes = sub.add_parser("dupes", help="Find duplicate task names")
    p_dupes.add_argument("--dir", help="Filter by pipeline stage")
    p_dupes.add_argument("--json", action="store_true", help="JSON output")

    # empty
    p_empty = sub.add_parser("empty", help="Find .issues.md files with zero issues")
    p_empty.add_argument("--dir", help="Filter by pipeline stage")
    p_empty.add_argument("--json", action="store_true", help="JSON output")

    # move
    p_move = sub.add_parser("move", help="Move files between pipeline stages")
    p_move.add_argument(
        "--filter",
        required=True,
        choices=["empty", "full-only", "issues-only"],
        help="Which files to move",
    )
    p_move.add_argument("--target", required=True, help="Target directory (relative to tasks/ or absolute)")
    p_move.add_argument("--dir", help="Limit to a specific pipeline stage")
    p_move.add_argument("--force", action="store_true", help="Actually perform the move")

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        sys.exit(1)

    # Resolve --dir to limit files
    limit_stage = getattr(args, "dir", None)

    # Determine which directories to scan
    if limit_stage:
        # Map stage to directory base
        scan_dirs = [BASE_DIR]
    else:
        scan_dirs = None

    files = scan_files(scan_dirs)

    if limit_stage:
        files = [f for f in files if f["stage"] == limit_stage or limit_stage in f["stage"]]

    if args.command == "summary":
        cmd_summary(files)
    elif args.command == "list":
        if getattr(args, "json", False):
            result = [
                {
                    "stage": f["stage"],
                    "name": f["name"],
                    "type": f["type"],
                    "ts": f["ts"].astimezone().isoformat(timespec='seconds') if f["ts"] else None,
                    "fname": f["fname"],
                    "size": f["size"],
                }
                for f in files
            ]
            print(json.dumps(result, indent=2))
        else:
            cmd_list(files, limit_stage=None)  # already filtered above
    elif args.command == "pairs":
        cmd_pairs(files, limit_stage=None, as_json=getattr(args, "json", False))
    elif args.command == "dupes":
        cmd_dupes(files, limit_stage=None, as_json=getattr(args, "json", False))
    elif args.command == "empty":
        cmd_empty(files, limit_stage=None, as_json=getattr(args, "json", False))
    elif args.command == "move":
        cmd_move(
            files,
            filter_type=args.filter,
            target_dir=args.target,
            force=args.force,
            limit_stage=limit_stage,
        )


if __name__ == "__main__":
    main()
