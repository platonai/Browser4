#!/usr/bin/env python3
"""
Browser4 Log Dashboard — real-time TUI log monitor.

A polished terminal dashboard for watching all Browser4 log sources:
Kotlin backend, Rust CLI startup, Coworker tasks, build output, git log,
and RWS (Real-World Scenario) test output.

Requires: textual, watchfiles
Install:  pip install textual watchfiles

Usage:
    python bin/tools/watch-logs.py
    python bin/tools/watch-logs.py --repo /path/to/browser4 --tail-lines 500

Tabs:
    0-9  Switch between log sources (pulsar, server, browser, api, pages,
         coworker, build, startup, combined, git)
    r    RWS test output (target/*.raw.md + .test-sessions/*-progress.json)
    g    Git log (toggle compact/detail)
    ↑↓   Scroll up/down one line (pauses auto-follow)
    PgUp/PgDn  Scroll up/down one page
    Home/End   Jump to top / resume bottom-follow
    space  Pause/resume scrolling
    c    Clear buffer
    y    Copy buffer to clipboard
    ^F, /  Filter/search (regex with highlight, F3=next Shift+F3=prev)
    Esc   Clear filter
    q    Quit
"""

from __future__ import annotations

import argparse
import asyncio
import os
import re
import subprocess
import sys
from collections import deque
from datetime import datetime
from pathlib import Path
from typing import Optional

# ── Dependency checks ──────────────────────────────────────────────────────────

try:
    from textual.app import App, ComposeResult
    from textual.binding import Binding
    from textual.containers import Container
    from textual.reactive import reactive
    from textual.widgets import (
        Footer,
        Header,
        Input,
        RichLog,
        TabbedContent,
        TabPane,
        Label,
    )
    from textual.widgets._rich_log import RichLog
    from rich.text import Text
    from rich.style import Style
except ImportError:
    print(
        "textual is required. Install with:  pip install textual watchfiles"
    )
    sys.exit(1)

try:
    import watchfiles
    HAS_WATCHFILES = True
except ImportError:
    HAS_WATCHFILES = False


# ═══════════════════════════════════════════════════════════════════════════════
# Configuration
# ═══════════════════════════════════════════════════════════════════════════════

SEVERITY_COLORS: dict[str, str] = {
    "FATAL":   "bold red",
    "ERROR":   "bold red",
    "SEVERE":  "bold red",
    "WARN":    "yellow",
    "WARNING": "yellow",
    "INFO":    "white",
    "DEBUG":   "dim white",
    "TRACE":   "dim white",
}
DEFAULT_COLOR = "#aaaaaa"

SEVERITY_RE = re.compile(
    r"\b(FATAL|ERROR|SEVERE|WARN|WARNING|INFO|DEBUG|TRACE)\b"
)

# RWS (Real-World Scenario) test output markers
RWS_STEP_START_RE = re.compile(r">>>\s+STEP\s+(\S+):\s*(.+)")
RWS_STEP_PASS_RE = re.compile(r"<<<\s+STEP\s+(\S+):\s+PASS")
RWS_STEP_FAIL_RE = re.compile(r"<<<\s+STEP\s+(\S+):\s+FAIL")
RWS_ABORT_RE = re.compile(r"!!!\s+ABORT\s+at\s+step\s+(\S+):\s*(.+)\s*!!!")

LOG_SOURCES: list[dict] = [
    {"key": "1", "label": "pulsar",  "desc": "Root backend log",
     "paths": ["logs/pulsar.log"]},
    {"key": "2", "label": "server",  "desc": "Server / framework",
     "paths": ["logs/pulsar.s.log"]},
    {"key": "3", "label": "browser", "desc": "Browser / CDP ops",
     "paths": ["logs/pulsar.bs.log"]},
    {"key": "4", "label": "api",     "desc": "Scrape API tasks",
     "paths": ["logs/pulsar.api.log"]},
    {"key": "5", "label": "pages",   "desc": "Page processing",
     "paths": ["logs/pulsar.pg.log"]},
    {"key": "6", "label": "coworker","desc": "Coworker task runner",
     "paths": ["__coworker__"]},
    {"key": "7", "label": "build",   "desc": "Spring Boot build",
     "paths": [".build/spring-boot.log"]},
    {"key": "8", "label": "startup", "desc": "Server startup log",
     "paths": ["__startup__"]},
    {"key": "9", "label": "combined","desc": "pulsar + server + browser",
     "paths": ["logs/pulsar.log", "logs/pulsar.s.log", "logs/pulsar.bs.log"]},
    {"key": "0", "label": "git",     "desc": "Git log",
     "paths": ["__git__"]},
    {"key": "r", "label": "rws",     "desc": "RWS test output",
     "paths": ["__rws__"]},
]


# ═══════════════════════════════════════════════════════════════════════════════
# Helpers
# ═══════════════════════════════════════════════════════════════════════════════

def resolve_repo_root(start: Path | None = None) -> Path:
    """Find the git repo root, starting from *start* or cwd."""
    d = (start or Path.cwd()).resolve()
    while d != d.parent:
        if (d / ".git").exists():
            return d
        d = d.parent
    return Path.cwd()


def find_latest_log(directory: Path, pattern: str = "*.log",
                    exclude_pattern: str | None = None) -> Path | None:
    """Find the most recently modified log file in a directory tree."""
    if not directory.exists():
        return None
    files = sorted(
        directory.rglob(pattern),
        key=lambda f: f.stat().st_mtime,
        reverse=True,
    )
    for f in files:
        if exclude_pattern and re.search(exclude_pattern, f.name):
            continue
        return f
    return None


def copy_to_clipboard(text: str) -> bool:
    """Copy *text* to the system clipboard. Returns True on success."""
    if not text:
        return False
    try:
        if sys.platform == "win32":
            subprocess.run(
                ["clip"], input=text, text=True,
                creationflags=subprocess.CREATE_NO_WINDOW
                if hasattr(subprocess, "CREATE_NO_WINDOW") else 0,
                check=True,
            )
        elif sys.platform == "darwin":
            subprocess.run(["pbcopy"], input=text, text=True, check=True)
        else:
            import shutil
            for cmd in (["wl-copy"], ["xclip", "-selection", "clipboard"]):
                if shutil.which(cmd[0]):
                    subprocess.run(cmd, input=text, text=True, check=True)
                    return True
            return False
        return True
    except Exception:
        return False


def colorize_line(line: str) -> Text:
    """Apply Rich style to a log line based on severity level."""
    m = SEVERITY_RE.search(line)
    if m:
        color = SEVERITY_COLORS.get(m.group(1), DEFAULT_COLOR)
        return Text(line, style=Style.parse(color))
    return Text(line, style=Style.parse(DEFAULT_COLOR))


def colorize_git_compact(line: str) -> Text:
    """Colorize a compact git log line (--oneline --graph)."""
    if not line.strip():
        return Text(line)
    style = "dim white"
    if "(" in line:
        if "(HEAD" in line:
            style = "cyan"
        elif "(tag:" in line:
            style = "yellow"
        elif "(origin" in line:
            style = "green"
    elif line.strip().startswith("*"):
        style = "white"
    return Text(line, style=Style.parse(style))


def colorize_git_detail(line: str) -> Text:
    """Colorize a detailed git log line."""
    if line.startswith("commit "):
        return Text(line, style=Style.parse("bold yellow"))
    elif line.startswith("Author:"):
        return Text(line, style=Style.parse("cyan"))
    elif line.startswith("Date:"):
        return Text(line, style=Style.parse("dark_cyan"))
    elif line.startswith("    "):
        return Text(line, style=Style.parse("white"))
    elif line.strip():
        return Text(line, style=Style.parse("white"))
    return Text(line)


def colorize_rws_line(line: str) -> Text:
    """Colorize RWS test output: STEP markers, agent output, progress JSON."""
    # Abort markers — bold red, highest priority
    if RWS_ABORT_RE.search(line):
        return Text(line, style=Style.parse("bold red"))
    # Step fail — bold red
    if RWS_STEP_FAIL_RE.search(line):
        return Text(line, style=Style.parse("bold red"))
    # Step pass — green
    if RWS_STEP_PASS_RE.search(line):
        return Text(line, style=Style.parse("green"))
    # Step start — cyan
    if RWS_STEP_START_RE.search(line):
        return Text(line, style=Style.parse("cyan"))
    # Heartbeat / progress lines — dim cyan
    if "·" in line and ("running" in line or "Checkpoints:" in line or "step(s) completed" in line):
        return Text(line, style=Style.parse("dim cyan"))
    # Fall back to severity-based colorization for agent output
    return colorize_line(line)


# ═══════════════════════════════════════════════════════════════════════════════
# LogView — a single tab's log display + watcher
# ═══════════════════════════════════════════════════════════════════════════════

class LogView(Container):
    """A tab pane that watches log files and displays colorized output."""

    paused: bool = reactive(False)
    filter_regex: str = reactive("")

    def __init__(
        self,
        repo_root: Path,
        source: dict,
        tail_lines: int = 200,
    ) -> None:
        super().__init__()
        self.repo_root = repo_root
        self.source = source
        self.tail_lines = tail_lines
        self._buffer: deque[str] = deque(maxlen=5000)
        self._file_positions: dict[Path, int] = {}
        self._watcher_task: asyncio.Task | None = None
        self._git_task: asyncio.Task | None = None
        self._git_detail = False
        self._git_last_hash = ""
        self.rich_log: RichLog | None = None
        self._stop_event = asyncio.Event()
        self._filter_compiled: re.Pattern | None = None
        # ── Scroll & search state ─────────────────────────────────────────
        # _scroll_from_bottom: 0 = follow bottom; >0 = N lines scrolled up
        self._scroll_from_bottom: int = 0
        self._search_pattern: str = ""
        self._search_matches: list[int] = []  # line indices in _full filtered buffer
        self._search_idx: int = -1
        self._last_filtered_count: int = 0
        self._status_label: Label | None = None

    # ── Compose ────────────────────────────────────────────────────────────

    def compose(self) -> ComposeResult:
        self.rich_log = RichLog(
            max_lines=5000,
            highlight=True,
            markup=False,
            wrap=False,
        )
        self.rich_log.auto_scroll = True
        self.rich_log.border = None
        yield self.rich_log
        self._status_label = Label("", classes="log-status")
        yield self._status_label

    # ── Lifecycle ───────────────────────────────────────────────────────────

    def on_mount(self) -> None:
        """Start watching when this tab becomes visible."""
        self._start_watching()

    def on_unmount(self) -> None:
        """Stop all background tasks."""
        self._stop_watching()

    # ── Watch control ───────────────────────────────────────────────────────

    def _start_watching(self) -> None:
        """Launch background watcher for this tab's log files."""
        self._stop_watching()
        self._stop_event.clear()

        paths = self.source["paths"]
        sentinel = paths[0] if paths else ""

        if sentinel == "__git__":
            self._git_task = asyncio.create_task(self._poll_git_log())
        elif sentinel == "__rws__":
            self._watcher_task = asyncio.create_task(self._watch_rws())
        else:
            self._watcher_task = asyncio.create_task(self._watch_files())

    def _stop_watching(self) -> None:
        """Cancel all background tasks."""
        self._stop_event.set()
        for t in (self._watcher_task, self._git_task):
            if t and not t.done():
                t.cancel()
        self._watcher_task = None
        self._git_task = None

    # ── File watcher ────────────────────────────────────────────────────────

    async def _watch_files(self) -> None:
        """Background task: watch files for changes and stream new lines."""
        paths = self.source["paths"]
        resolved: list[Path] = []

        for p in paths:
            if p == "__coworker__":
                latest = find_latest_log(
                    Path.home() / ".browser4-coworker" / "tasks" / "300logs",
                    pattern="*.log",
                    exclude_pattern=r"\.std(out|err)$",
                )
                if latest:
                    resolved.append(latest)
            elif p == "__startup__":
                temp_dir = Path(os.environ.get(
                    "BROWSER4_SERVER_LOG_DIR",
                    str(Path.home() / ".browser4" / "logs"),
                ))
                latest = find_latest_log(
                    temp_dir,
                    pattern="browser4-server-*.log",
                )
                if latest:
                    resolved.append(latest)
            else:
                rp = self.repo_root / p
                resolved.append(rp)

        # Seed the display with tail lines
        for fp in resolved:
            await self._read_tail(fp)

        if not resolved:
            self._write_line(
                Text("(no log files found)", style=Style.parse("dim white"))
            )
            return

        if HAS_WATCHFILES:
            await self._watch_with_watchfiles(resolved)
        else:
            await self._watch_with_polling(resolved)

    async def _read_tail(self, fp: Path) -> None:
        """Read the last *tail_lines* from a file into the buffer."""
        if not fp.exists():
            return
        try:
            with open(fp, "r", encoding="utf-8", errors="replace") as f:
                f.seek(0, os.SEEK_END)
                size = f.tell()
                if size == 0:
                    self._file_positions[fp] = 0
                    return
                start = max(0, size - 200 * self.tail_lines)
                f.seek(start)
                if start > 0:
                    f.readline()  # skip partial first line
                label = self._label_for(fp)
                for line in f:
                    line = line.rstrip("\n\r")
                    if label:
                        self._buffer.append(f"[{label}] {line}")
                    else:
                        self._buffer.append(line)
            self._file_positions[fp] = fp.stat().st_size
            self._refresh_display()
        except Exception:
            pass

    async def _watch_with_watchfiles(self, paths: list[Path]) -> None:
        """Use watchfiles for efficient file change detection."""
        try:
            watch_dirs = list({str(p.parent) for p in paths})
            async for changes in watchfiles.awatch(
                *watch_dirs,
                stop_event=self._stop_event,
            ):
                for change_type, changed_path in changes:
                    cp = Path(changed_path)
                    if cp in paths or any(
                        cp.resolve() == p.resolve() for p in paths
                    ):
                        await self._read_new_lines(cp)
        except asyncio.CancelledError:
            pass
        except Exception:
            # Fall back to polling on any watchfiles error
            pass

    async def _watch_with_polling(self, paths: list[Path]) -> None:
        """Fallback: poll file sizes every 500ms."""
        while not self._stop_event.is_set():
            for fp in paths:
                try:
                    if fp.exists() and fp.stat().st_size > self._file_positions.get(fp, 0):
                        await self._read_new_lines(fp)
                except Exception:
                    pass
            await asyncio.sleep(0.5)

    async def _read_new_lines(self, fp: Path) -> None:
        """Read new content from a file since last position."""
        if not fp.exists():
            return
        try:
            current_size = fp.stat().st_size
            last_pos = self._file_positions.get(fp, 0)
            if current_size <= last_pos:
                return
            with open(fp, "r", encoding="utf-8", errors="replace") as f:
                f.seek(last_pos)
                label = self._label_for(fp)
                new_lines: list[str] = []
                for line in f:
                    line = line.rstrip("\n\r")
                    if label:
                        line = f"[{label}] {line}"
                    self._buffer.append(line)
                    new_lines.append(line)
            self._file_positions[fp] = current_size

            if not new_lines or self.paused:
                return

            # ── Display strategy ──────────────────────────────────────────
            if self._scroll_from_bottom == 0 and not self._filter_compiled:
                # Following bottom, no filter: append directly to RichLog.
                # This avoids clearing and rebuilding the entire display,
                # preserving any mouse text selection in the terminal.
                self._write_colorized_block(new_lines)
                self._update_search_and_status()
            elif self._filter_compiled:
                # Filter is active — need a full rebuild to correctly
                # show/hide new lines based on the regex.
                self._refresh_display()
            else:
                # Scrolled up, no filter: track offset so the user's view
                # stays pinned, but don't touch the display at all.  This
                # also preserves mouse selection while the user is reading
                # or selecting text.
                self._scroll_from_bottom += len(new_lines)
                self._update_search_and_status()
        except Exception:
            pass

    def _label_for(self, fp: Path) -> str:
        """Return a short label for multi-file views."""
        if len(self.source["paths"]) <= 1:
            return ""
        name = fp.name
        if "pulsar.bs" in name:
            return "browser"
        elif "pulsar.s" in name:
            return "server"
        elif "pulsar" in name:
            return "pulsar"
        elif name.endswith(".raw.md"):
            # Extract scenario name from <timestamp>-<scenario>.raw.md
            stem = name.replace(".raw.md", "")
            parts = stem.split("-", 1)
            return parts[1] if len(parts) > 1 else stem
        elif name.endswith("-progress.json"):
            return name.replace("-progress.json", "")
        elif name == "test-session.json":
            return "session"
        return ""

    # ── RWS file watcher ────────────────────────────────────────────────────

    def _resolve_rws_paths(self) -> list[Path]:
        """Resolve __rws__ sentinel to actual file paths.

        Finds the most recent raw capture files in target/ and progress
        files in .test-sessions/.  Returns up to 5 of each type.
        """
        resolved: list[Path] = []

        # Latest raw capture files in target/
        target_dir = self.repo_root / "target"
        if target_dir.exists():
            raw_files = sorted(
                target_dir.glob("*.raw.md"),
                key=lambda f: f.stat().st_mtime,
                reverse=True,
            )
            for rf in raw_files[:5]:
                resolved.append(rf)

        # Progress files in .test-sessions/
        ts_dir = self.repo_root / ".test-sessions"
        if ts_dir.exists():
            prog_files = sorted(
                ts_dir.glob("*-progress.json"),
                key=lambda f: f.stat().st_mtime,
                reverse=True,
            )
            for pf in prog_files[:5]:
                resolved.append(pf)

            # Most recent test-session.json
            session_files = sorted(
                ts_dir.glob("*/test-session.json"),
                key=lambda f: f.stat().st_mtime,
                reverse=True,
            )
            if session_files:
                resolved.append(session_files[0])

        return resolved

    async def _watch_rws(self) -> None:
        """Watch RWS output files with periodic re-scanning for new files.

        Unlike _watch_files which resolves paths once, this method re-scans
        every 2 seconds so newly created capture files (from freshly started
        RWS tests) are picked up automatically.
        """
        if not self.rich_log:
            return

        while not self._stop_event.is_set():
            # Re-scan for RWS files
            resolved = self._resolve_rws_paths()

            # Seed newly discovered files
            for fp in resolved:
                if fp not in self._file_positions:
                    await self._read_tail(fp)

            # Poll all tracked files for new content
            for fp in list(self._file_positions.keys()):
                try:
                    if fp.exists():
                        current_size = fp.stat().st_size
                        if current_size > self._file_positions.get(fp, 0):
                            await self._read_new_lines(fp)
                except Exception:
                    pass

            # Show a status line when no files are found yet
            if not resolved:
                # Only show once — write directly to buffer to avoid flicker
                if "rws:no-files" not in str(self._buffer):
                    self._buffer.append(
                        "(waiting for RWS output — run 'b4w test rws sc <name>' to start)"
                    )
                    self._refresh_display()

            await asyncio.sleep(2)

    # ── Git log poller ──────────────────────────────────────────────────────

    async def _poll_git_log(self) -> None:
        """Background task: poll git log every 10 seconds."""
        while not self._stop_event.is_set():
            try:
                await self._fetch_git_log()
            except Exception:
                pass
            await asyncio.sleep(10)

    async def _fetch_git_log(self) -> None:
        """Run git log and push colorized lines to display."""
        n = min(self.tail_lines, 50)
        if self._git_detail:
            cmd = [
                "git", "-C", str(self.repo_root), "log",
                "--format=commit %H%d%nAuthor: %an <%ae>%nDate:   %ad%n%n    %B%n",
                f"--date=local", "--all", f"--max-count={n}",
            ]
            header = f"──── git log detail (all branches, last {n}) — {datetime.now():%H:%M:%S} ────"
        else:
            cmd = [
                "git", "-C", str(self.repo_root), "log",
                "--oneline", "--graph", "--all", "--decorate", f"-{n}",
            ]
            header = f"──── git log (all branches, last {n}) — {datetime.now():%H:%M:%S} ────"

        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        stdout, _ = await proc.communicate()
        lines = stdout.decode("utf-8", errors="replace").splitlines()

        # Only refresh if something changed
        if not lines:
            return
        new_first = lines[0]
        if new_first == self._git_last_hash:
            return
        self._git_last_hash = new_first

        # Clear previous git output and repaint
        self._buffer.clear()
        self._buffer.append("")
        self._buffer.append(header)
        for line in lines:
            self._buffer.append(line)
        self._refresh_display()

    def toggle_git_detail(self) -> None:
        """Toggle between compact and detailed git log."""
        self._git_detail = not self._git_detail
        self._git_last_hash = ""  # force refresh
        if self.rich_log:
            self.rich_log.clear()

    # ── Display ─────────────────────────────────────────────────────────────

    def _get_filtered(self) -> list[str]:
        """Return the buffer filtered by current regex, as a plain list."""
        result: list[str] = []
        for line in self._buffer:
            if self._filter_compiled and not self._filter_compiled.search(line):
                continue
            result.append(line)
        return result

    def _write_colorized_block(self, lines: list[str]) -> None:
        """Write colorized lines directly to RichLog without clearing.

        Used when following the bottom — appends new lines instead of
        rebuilding the entire display, preserving mouse text selection.
        """
        if not self.rich_log:
            return

        is_git = self.source["paths"][0] == "__git__"
        is_rws = self.source["paths"][0] == "__rws__"
        SEARCH_HIGHLIGHT = Style.parse("reverse bold yellow")

        for line in lines:
            # ── Colorize ──────────────────────────────────────────────────
            if is_git:
                if self._git_detail:
                    text = colorize_git_detail(line)
                else:
                    text = colorize_git_compact(line)
            elif is_rws:
                text = colorize_rws_line(line)
            elif line.startswith("──── git log"):
                text = Text(line, style=Style.parse("dark_cyan"))
            else:
                text = colorize_line(line)

            # ── Search highlight overlay ──────────────────────────────────
            if self._search_pattern:
                try:
                    text.highlight_regex(
                        self._search_pattern, style=SEARCH_HIGHLIGHT
                    )
                except Exception:
                    pass

            self.rich_log.write(text)

    def _update_search_and_status(self) -> None:
        """Update search matches and status bar without clearing display.

        Used after appending new lines (to avoid a full clear+rebuild that
        would destroy mouse text selection).
        """
        filtered = self._get_filtered()
        total = len(filtered)
        try:
            visible_h = max(8, self.rich_log.container_size.height)
        except Exception:
            visible_h = 40

        # Rebuild search matches against current filtered buffer
        if self._search_pattern:
            try:
                pat = re.compile(self._search_pattern, re.IGNORECASE)
                self._search_matches = [
                    i for i, line in enumerate(filtered) if pat.search(line)
                ]
            except re.error:
                self._search_matches = []

        self._last_filtered_count = total
        self._update_status(
            total,
            max(0, total - visible_h - max(0, self._scroll_from_bottom)),
            visible_h,
        )

    def _refresh_display(self) -> None:
        """Rebuild RichLog content from buffer, applying filter and scroll.

        This does a full clear+rebuild every time.  Normal data arrival
        while following the bottom is handled by the append path in
        _read_new_lines instead — this keeps mouse text selection intact.
        """
        if self.rich_log is None:
            return

        # Freeze display when explicitly paused
        if self.paused:
            return

        is_git = self.source["paths"][0] == "__git__"
        is_rws = self.source["paths"][0] == "__rws__"

        filtered = self._get_filtered()
        total = len(filtered)

        # ── Visible height ──────────────────────────────────────────────────
        try:
            visible_h = max(8, self.rich_log.container_size.height)
        except Exception:
            visible_h = 40  # fallback

        # ── Detect new lines arriving at the bottom ─────────────────────────
        new_lines = total - self._last_filtered_count
        if new_lines > 0 and self._scroll_from_bottom > 0:
            self._scroll_from_bottom += new_lines
        self._last_filtered_count = total

        # ── Compute visible window ──────────────────────────────────────────
        if self._scroll_from_bottom <= 0:
            # Follow bottom
            start = max(0, total - visible_h)
            self._scroll_from_bottom = 0
        else:
            # Scrolled up — pin view N lines above the bottom
            start = max(0, total - visible_h - self._scroll_from_bottom)

        visible = filtered[start : start + visible_h]

        # ── Update search matches (full filtered buffer) ────────────────────
        if self._search_pattern:
            try:
                pat = re.compile(self._search_pattern, re.IGNORECASE)
                self._search_matches = [
                    i for i, line in enumerate(filtered) if pat.search(line)
                ]
            except re.error:
                self._search_matches = []

        # ── Render ──────────────────────────────────────────────────────────
        self.rich_log.clear()
        self.rich_log.auto_scroll = (self._scroll_from_bottom == 0)

        SEARCH_HIGHLIGHT = Style.parse("reverse bold yellow")

        for i, line in enumerate(visible):
            actual_idx = start + i

            # Colorize
            if is_git:
                if self._git_detail:
                    text = colorize_git_detail(line)
                else:
                    text = colorize_git_compact(line)
            elif is_rws:
                text = colorize_rws_line(line)
            elif line.startswith("──── git log"):
                text = Text(line, style=Style.parse("dark_cyan"))
            else:
                text = colorize_line(line)

            # Search highlight overlay
            if self._search_pattern and actual_idx in self._search_matches:
                try:
                    text.highlight_regex(self._search_pattern, style=SEARCH_HIGHLIGHT)
                except Exception:
                    pass

            # Highlight the "current" match line with a stronger style
            if (
                self._search_idx >= 0
                and self._search_matches
                and actual_idx == self._search_matches[self._search_idx]
            ):
                try:
                    text.highlight_regex(
                        self._search_pattern,
                        style=Style.parse("reverse bold white on dark_orange"),
                    )
                except Exception:
                    pass

            self.rich_log.write(text)

        # ── Status label ────────────────────────────────────────────────────
        self._update_status(total, start, visible_h)

    def _update_status(self, total: int, start: int, visible_h: int) -> None:
        """Refresh the per-tab status bar."""
        if not self._status_label:
            return

        pct = (min(start + visible_h, total) * 100 // max(total, 1)) if total else 0
        parts: list[str] = []

        parts.append(f"L: {start + 1}-{min(start + visible_h, total)}/{total} ({pct}%)")

        if self._search_pattern:
            match_count = len(self._search_matches)
            cur = self._search_idx + 1 if self._search_idx >= 0 else 0
            parts.append(f"│ match {cur}/{match_count}" if match_count else "│ 0 matches")
            parts.append(f'"{self._search_pattern}"')

        if self._scroll_from_bottom > 0:
            parts.append("│ ↥ scroll")
        elif self.paused:
            parts.append("│ PAUSED")
        else:
            parts.append("│ LIVE")

        if self._filter_compiled:
            parts.append(f"│ filter active")

        self._status_label.update("  ".join(parts))

    def _write_line(self, text: Text) -> None:
        """Write a single line directly to RichLog (used when no buffering needed)."""
        if self.rich_log and not self.paused:
            self.rich_log.write(text)

    # ── Scroll actions ─────────────────────────────────────────────────────

    def action_scroll_up(self) -> None:
        """Scroll up one line."""
        filtered = self._get_filtered()
        try:
            visible_h = max(8, self.rich_log.container_size.height)
        except Exception:
            visible_h = 40
        max_scroll = max(0, len(filtered) - visible_h)
        self._scroll_from_bottom += 1
        if self._scroll_from_bottom > max_scroll:
            self._scroll_from_bottom = max_scroll
        self._refresh_display()

    def action_scroll_down(self) -> None:
        """Scroll down one line (toward bottom)."""
        if self._scroll_from_bottom <= 0:
            return  # already at bottom
        self._scroll_from_bottom -= 1
        self._refresh_display()

    def action_scroll_page_up(self) -> None:
        """Scroll up one page."""
        filtered = self._get_filtered()
        try:
            visible_h = max(8, self.rich_log.container_size.height)
        except Exception:
            visible_h = 40
        max_scroll = max(0, len(filtered) - visible_h)
        self._scroll_from_bottom += max(1, visible_h - 2)
        if self._scroll_from_bottom > max_scroll:
            self._scroll_from_bottom = max_scroll
        self._refresh_display()

    def action_scroll_page_down(self) -> None:
        """Scroll down one page."""
        if self._scroll_from_bottom <= 0:
            return
        try:
            visible_h = max(8, self.rich_log.container_size.height)
        except Exception:
            visible_h = 40
        self._scroll_from_bottom = max(0, self._scroll_from_bottom - max(1, visible_h - 2))
        self._refresh_display()

    def action_scroll_home(self) -> None:
        """Jump to top of buffer."""
        filtered = self._get_filtered()
        try:
            visible_h = max(8, self.rich_log.container_size.height)
        except Exception:
            visible_h = 40
        self._scroll_from_bottom = max(0, len(filtered) - visible_h)
        self._refresh_display()

    def action_scroll_end(self) -> None:
        """Jump to bottom and resume following."""
        self._scroll_from_bottom = 0
        self._refresh_display()

    # ── Search actions ──────────────────────────────────────────────────────

    def action_search_next(self) -> None:
        """Jump to next search match."""
        if not self._search_matches:
            return
        # Rebuild matches against current filtered buffer
        self._refresh_search_matches()
        if not self._search_matches:
            return
        self._search_idx = (self._search_idx + 1) % len(self._search_matches)
        self._jump_to_match()

    def action_search_prev(self) -> None:
        """Jump to previous search match."""
        if not self._search_matches:
            return
        self._refresh_search_matches()
        if not self._search_matches:
            return
        self._search_idx = (self._search_idx - 1) % len(self._search_matches)
        self._jump_to_match()

    def _refresh_search_matches(self) -> None:
        """Recompute search matches from current filtered buffer."""
        if not self._search_pattern:
            self._search_matches = []
            self._search_idx = -1
            return
        filtered = self._get_filtered()
        try:
            pat = re.compile(self._search_pattern, re.IGNORECASE)
            self._search_matches = [
                i for i, line in enumerate(filtered) if pat.search(line)
            ]
        except re.error:
            self._search_matches = []

    def _jump_to_match(self) -> None:
        """Scroll so the current search match is visible."""
        if self._search_idx < 0 or self._search_idx >= len(self._search_matches):
            return
        target_line = self._search_matches[self._search_idx]
        filtered = self._get_filtered()
        total = len(filtered)
        try:
            visible_h = max(8, self.rich_log.container_size.height)
        except Exception:
            visible_h = 40
        # Place target in the middle third of the viewport
        offset_from_bottom = max(0, total - target_line - visible_h // 3)
        self._scroll_from_bottom = offset_from_bottom
        self._refresh_display()

    def action_search_set(self, pattern: str) -> None:
        """Set the search pattern (non-filtering highlight + F3 navigation)."""
        pattern = pattern.strip()
        self._search_pattern = pattern
        self._search_idx = -1
        if pattern:
            self._refresh_search_matches()
            if self._search_matches:
                self._search_idx = 0
                self._jump_to_match()
                return
        self._refresh_display()

    # ── Standard actions ────────────────────────────────────────────────────

    def action_toggle_pause(self) -> None:
        """Toggle paused state."""
        self.paused = not self.paused
        if not self.paused:
            self._scroll_from_bottom = 0
            self._refresh_display()

    def action_clear(self) -> None:
        """Clear the display buffer."""
        self._buffer.clear()
        self._git_last_hash = ""
        self._scroll_from_bottom = 0
        self._search_matches = []
        self._search_idx = -1
        self._last_filtered_count = 0
        if self.rich_log:
            self.rich_log.clear()
        if self._status_label:
            self._status_label.update("")

    def action_set_filter(self, pattern: str) -> None:
        """Apply a regex filter to the display (also sets search pattern)."""
        pattern = pattern.strip()
        if pattern:
            try:
                self._filter_compiled = re.compile(pattern, re.IGNORECASE)
            except re.error:
                self._filter_compiled = re.compile(re.escape(pattern), re.IGNORECASE)
        else:
            self._filter_compiled = None
        self.filter_regex = pattern
        # Also set as search pattern for F3 navigation
        self._search_pattern = pattern
        self._search_idx = -1
        self._scroll_from_bottom = 0
        self._last_filtered_count = 0
        if pattern:
            self._refresh_search_matches()
            if self._search_matches:
                self._search_idx = 0
        self._refresh_display()

    def action_toggle_git_detail(self) -> None:
        """Toggle git log detail mode."""
        self.toggle_git_detail()

    def action_copy(self) -> None:
        """Copy buffer contents to the system clipboard."""
        text = "\n".join(list(self._buffer))
        if copy_to_clipboard(text):
            self.notify(
                f"Copied {len(self._buffer)} lines to clipboard",
                timeout=2,
            )
        else:
            self.notify(
                "No clipboard tool found (install xclip/wl-clipboard on Linux)",
                severity="warning",
                timeout=3,
            )


# ═══════════════════════════════════════════════════════════════════════════════
# Main App
# ═══════════════════════════════════════════════════════════════════════════════

class WatchLogsApp(App):
    """Real-time log monitoring dashboard."""

    CSS = """
    TabbedContent {
        height: 1fr;
    }
    TabPane {
        height: 1fr;
    }
    LogView {
        height: 1fr;
    }
    RichLog {
        height: 1fr;
        background: $surface;
    }
    .log-status {
        dock: bottom;
        height: 1;
        background: $panel;
        color: $text-muted;
        padding: 0 1;
    }
    #filter-input {
        dock: top;
        height: 3;
        margin: 0 1;
        display: none;
    }
    #filter-input.visible {
        display: block;
    }
    #filter-label {
        dock: top;
        height: 1;
        margin: 0 1;
        color: $text-muted;
        display: none;
    }
    #filter-label.visible {
        display: block;
    }
    """

    BINDINGS = [
        Binding("0", "switch_tab(0)", "Git log", show=False),
        Binding("1", "switch_tab(1)", "Pulsar", show=False),
        Binding("2", "switch_tab(2)", "Server", show=False),
        Binding("3", "switch_tab(3)", "Browser", show=False),
        Binding("4", "switch_tab(4)", "API", show=False),
        Binding("5", "switch_tab(5)", "Pages", show=False),
        Binding("6", "switch_tab(6)", "Coworker", show=False),
        Binding("7", "switch_tab(7)", "Build", show=False),
        Binding("8", "switch_tab(8)", "Startup", show=False),
        Binding("9", "switch_tab(9)", "Combined", show=False),
        Binding("r", "rws_tab", "RWS tests"),
        Binding("g", "git_tab", "Git log"),
        Binding("up", "scroll_up", "↑", show=False),
        Binding("down", "scroll_down", "↓", show=False),
        Binding("pageup", "scroll_page_up", "PgUp", show=False),
        Binding("pagedown", "scroll_page_down", "PgDn", show=False),
        Binding("home", "scroll_home", "Home", show=False),
        Binding("end", "scroll_end", "End", show=False),
        Binding("space", "pause", "Pause"),
        Binding("c", "clear", "Clear"),
        Binding("y", "copy", "Yank"),
        Binding("f3", "search_next", "Next match", show=False),
        Binding("shift+f3", "search_prev", "Prev match", show=False),
        Binding("ctrl+f", "show_filter", "Filter"),
        Binding("slash", "show_filter", "Filter", show=False),
        Binding("escape", "clear_filter", "Clear filter", show=False),
        Binding("q", "quit", "Quit"),
    ]

    def __init__(
        self,
        repo_root: Path,
        tail_lines: int = 200,
        refresh_ms: int = 150,
        **kwargs,
    ) -> None:
        super().__init__(**kwargs)
        self.repo_root = repo_root
        self.tail_lines = tail_lines
        self.refresh_ms = refresh_ms
        self._log_views: dict[str, LogView] = {}
        self._filter_visible = False

    def compose(self) -> ComposeResult:
        yield Header()
        yield Label("Filter:", id="filter-label")
        yield Input(placeholder="regex pattern (Enter to apply)", id="filter-input")

        with TabbedContent():
            for src in LOG_SOURCES:
                lv = LogView(
                    repo_root=self.repo_root,
                    source=src,
                    tail_lines=self.tail_lines,
                )
                self._log_views[src["label"]] = lv
                with TabPane(src["label"], id=f"tab-{src['label']}"):
                    yield lv

        yield Footer()

    def on_mount(self) -> None:
        """Set up header and subtitle."""
        self.title = "Browser4 Log Dashboard"
        self.sub_title = str(self.repo_root.name)
        if not HAS_WATCHFILES:
            self.notify(
                "watchfiles not installed — using polling fallback (pip install watchfiles)",
                severity="warning",
                timeout=10,
            )

    @property
    def _active_view(self) -> LogView | None:
        """Return the LogView for the currently active tab."""
        try:
            tabs = self.query_one(TabbedContent)
            active = tabs.active
        except Exception:
            return None
        for src in LOG_SOURCES:
            if src["label"] == active:
                return self._log_views.get(src["label"])
        return None

    # ── Actions ─────────────────────────────────────────────────────────────

    def action_switch_tab(self, index: str) -> None:
        """Switch to a tab by number key (0-9)."""
        i = int(index)
        if 0 <= i < len(LOG_SOURCES):
            label = LOG_SOURCES[i]["label"]
            try:
                tabs = self.query_one(TabbedContent)
                tabs.active = label
            except Exception:
                pass

    def action_git_tab(self) -> None:
        """Switch to git tab, or toggle detail if already there."""
        try:
            tabs = self.query_one(TabbedContent)
            if tabs.active == "git":
                # Toggle detail mode
                view = self._log_views.get("git")
                if view:
                    view.action_toggle_git_detail()
            else:
                tabs.active = "git"
        except Exception:
            pass

    def action_rws_tab(self) -> None:
        """Switch to RWS test output tab."""
        try:
            tabs = self.query_one(TabbedContent)
            tabs.active = "rws"
        except Exception:
            pass

    def action_pause(self) -> None:
        """Toggle pause on the active tab."""
        view = self._active_view
        if view:
            view.action_toggle_pause()
            state = "PAUSED" if view.paused else "LIVE"
            self.notify(f"{view.source['label']}: {state}", timeout=2)

    def action_clear(self) -> None:
        """Clear the active tab's buffer."""
        view = self._active_view
        if view:
            view.action_clear()

    def action_copy(self) -> None:
        """Copy the active tab's buffer to clipboard."""
        view = self._active_view
        if view:
            view.action_copy()

    def action_show_filter(self) -> None:
        """Show the filter input bar."""
        inp = self.query_one("#filter-input", Input)
        lbl = self.query_one("#filter-label", Label)
        self._filter_visible = True
        lbl.add_class("visible")
        inp.add_class("visible")
        inp.value = ""
        inp.focus()

    def action_clear_filter(self) -> None:
        """Hide the filter input and clear filter."""
        inp = self.query_one("#filter-input", Input)
        lbl = self.query_one("#filter-label", Label)
        self._filter_visible = False
        lbl.remove_class("visible")
        inp.remove_class("visible")
        inp.value = ""
        view = self._active_view
        if view:
            view.action_set_filter("")

    # ── Scroll & search actions (routed to active view) ─────────────────────

    def action_scroll_up(self) -> None:
        if (view := self._active_view):
            view.action_scroll_up()

    def action_scroll_down(self) -> None:
        if (view := self._active_view):
            view.action_scroll_down()

    def action_scroll_page_up(self) -> None:
        if (view := self._active_view):
            view.action_scroll_page_up()

    def action_scroll_page_down(self) -> None:
        if (view := self._active_view):
            view.action_scroll_page_down()

    def action_scroll_home(self) -> None:
        if (view := self._active_view):
            view.action_scroll_home()

    def action_scroll_end(self) -> None:
        if (view := self._active_view):
            view.action_scroll_end()

    def action_search_next(self) -> None:
        if (view := self._active_view):
            view.action_search_next()

    def action_search_prev(self) -> None:
        if (view := self._active_view):
            view.action_search_prev()

    def on_input_submitted(self, event: Input.Submitted) -> None:
        """Apply filter on Enter."""
        pattern = event.value
        view = self._active_view
        if view:
            view.action_set_filter(pattern)
        # Keep filter bar visible with active filter shown
        inp = self.query_one("#filter-input", Input)
        if pattern:
            inp.value = pattern  # keep visible for reference
        self.notify(
            f"Filter: '{pattern}'" if pattern else "Filter cleared",
            timeout=2,
        )


# ═══════════════════════════════════════════════════════════════════════════════
# Entry point
# ═══════════════════════════════════════════════════════════════════════════════

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Browser4 Log Dashboard — real-time TUI log monitor"
    )
    parser.add_argument(
        "--repo", type=Path, default=None,
        help="Path to Browser4 repo root (auto-detected if omitted)",
    )
    parser.add_argument(
        "--tail-lines", type=int, default=200,
        help="Number of history lines to show (default: 200)",
    )
    parser.add_argument(
        "--refresh-ms", type=int, default=150,
        help="Polling interval in ms for fallback mode (default: 150)",
    )
    args = parser.parse_args()

    repo_root = resolve_repo_root(args.repo)

    print(f"Browser4 Log Dashboard")
    print(f"  Repo: {repo_root}")
    if not HAS_WATCHFILES:
        print(f"  ⚠  watchfiles not installed — using polling fallback")
        print(f"     Install for better performance: pip install watchfiles")
    print()

    app = WatchLogsApp(
        repo_root=repo_root,
        tail_lines=args.tail_lines,
        refresh_ms=args.refresh_ms,
    )
    app.run()


if __name__ == "__main__":
    main()
