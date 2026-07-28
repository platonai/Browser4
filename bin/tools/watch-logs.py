#!/usr/bin/env python3
"""
Browser4 Log Dashboard — real-time TUI log monitor.

A polished terminal dashboard for watching all Browser4 log sources:
Kotlin backend, Rust CLI startup, Coworker tasks, build output, and git log.

Requires: textual, watchfiles
Install:  pip install textual watchfiles

Usage:
    python bin/tools/watch-logs.py
    python bin/tools/watch-logs.py --repo /path/to/browser4 --tail-lines 500
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

    # ── Compose ────────────────────────────────────────────────────────────

    def compose(self) -> ComposeResult:
        self.rich_log = RichLog(
            max_lines=5000,
            highlight=True,
            markup=False,
            wrap=False,
        )
        self.rich_log.auto_scroll = True
        yield self.rich_log

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
                for line in f:
                    line = line.rstrip("\n\r")
                    if label:
                        self._buffer.append(f"[{label}] {line}")
                    else:
                        self._buffer.append(line)
            self._file_positions[fp] = current_size
            self._refresh_display()
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
        return ""

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

    def _refresh_display(self) -> None:
        """Rebuild RichLog content from buffer, applying filter."""
        if self.paused or self.rich_log is None:
            return

        self.rich_log.clear()
        is_git = self.source["paths"][0] == "__git__"

        for line in list(self._buffer):
            # Apply filter
            if self._filter_compiled:
                if not self._filter_compiled.search(line):
                    continue

            if is_git:
                if self._git_detail:
                    text = colorize_git_detail(line)
                else:
                    text = colorize_git_compact(line)
            elif line.startswith("──── git log"):
                text = Text(line, style=Style.parse("dark_cyan"))
            else:
                text = colorize_line(line)

            self.rich_log.write(text)

    def _write_line(self, text: Text) -> None:
        """Write a single line directly to RichLog."""
        if self.rich_log and not self.paused:
            self.rich_log.write(text)

    # ── Actions ─────────────────────────────────────────────────────────────

    def action_toggle_pause(self) -> None:
        """Toggle paused state."""
        self.paused = not self.paused
        if not self.paused:
            self._refresh_display()

    def action_clear(self) -> None:
        """Clear the display buffer."""
        self._buffer.clear()
        self._git_last_hash = ""
        if self.rich_log:
            self.rich_log.clear()

    def action_set_filter(self, pattern: str) -> None:
        """Apply a regex filter to the display."""
        pattern = pattern.strip()
        if pattern:
            try:
                self._filter_compiled = re.compile(pattern, re.IGNORECASE)
            except re.error:
                self._filter_compiled = re.compile(re.escape(pattern), re.IGNORECASE)
        else:
            self._filter_compiled = None
        self.filter_regex = pattern
        self._refresh_display()

    def action_toggle_git_detail(self) -> None:
        """Toggle git log detail mode."""
        self.toggle_git_detail()


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
        Binding("g", "git_tab", "Git log"),
        Binding("space", "pause", "Pause"),
        Binding("c", "clear", "Clear"),
        Binding("ctrl+f", "show_filter", "Filter"),
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
