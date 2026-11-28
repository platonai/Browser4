//! Line editing for the interactive prompt (`agent run -i`, the `agent>` REPL).
//!
//! A Linux terminal is a byte device, not a line-oriented console: the tty line
//! discipline hands the process the raw bytes of special keys — `←` arrives as
//! `ESC [ D`, `F1` as `ESC O P`, and `Shift+Space` as a NUL byte. Windows is
//! different: its console line editor has already turned those keys into buffer
//! edits before the program reads the line.
//!
//! Reading the prompt in cooked mode therefore means the terminal echoes the
//! escape bytes into the visible line (`agent> 检查^[[D词句`) and the submitted
//! task text carries them too. Stripping them afterwards (`sanitize_stdin_line`)
//! hides the noise but silently deletes what the user typed around it — the keys
//! still do not do what they say.
//!
//! This module owns the prompt instead: it puts the terminal in raw mode, turns
//! each key press into an edit of the line buffer, and redraws the line. The
//! previous "strip the noise" behaviour stays as a fallback for piped input.
//!
//! It also owns the **prompt line as a whole** ([`Console`]): while the editor is
//! active, every human line the session prints goes through
//! [`write_human_line`], which erases the prompt line first and redraws it with
//! the unfinished draft afterwards. Without that arbitration a streamed step line
//! landed on top of `agent> <half a sentence>`, and the next keystroke redrew the
//! draft one row lower. [`ConsoleGuard`] restores the terminal on every exit
//! path — `/quit`, an error, SIGTERM — because the cbreak settings are device
//! state that outlives the process.

use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

/// One editing action, decoded from the bytes a terminal sends for a key.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Key {
    /// A character to insert at the cursor.
    Char(char),
    Enter,
    Backspace,
    Delete,
    Left,
    Right,
    Home,
    End,
    Up,
    Down,
    /// Alt+← / Alt+b: move to the start of the previous word.
    PrevWord,
    /// Alt+→ / Alt+f: move past the end of the next word.
    NextWord,
    /// Ctrl+W: delete the word before the caret.
    DeleteWordBack,
    /// Ctrl+U: delete from the start of the line to the caret.
    DeleteToStart,
    /// Ctrl+K: delete from the caret to the end of the line.
    DeleteToEnd,
    /// Tab: the session completes the line (it owns the command grammar).
    Complete,
    /// Ctrl+L: clear the screen and redraw.
    ClearScreen,
    /// The terminal's bracketed paste opened: line breaks inside it are text.
    PasteStart,
    /// … and closes.
    PasteEnd,
    /// Ctrl+C: discard the line.
    Interrupt,
    /// Ctrl+D on an empty line: end the session.
    Eof,
    /// A key this editor has no use for (F-keys, mouse reports …).
    Ignored,
}

/// What the editor wants the caller to do after a key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// The line is still being edited.
    Pending,
    /// The user submitted `line` (already trimmed).
    Submit(String),
    /// Tab: complete `line` at `cursor`. The editor has no business knowing the
    /// session's commands, so the session answers with [`Console::set_text`].
    Complete { line: String, cursor: usize },
    /// Ctrl+C.
    Interrupt,
    /// Ctrl+D at the start of an empty line.
    Eof,
}

/// What the stdin reader hands the session.
///
/// A completion request is not a line: the editor owns the buffer but the session
/// owns the grammar, so Tab travels to the session and comes back as a new line.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Input {
    /// A submitted line (an empty one when the reader hit Ctrl+D).
    Line(String),
    /// Tab: complete `line` at the caret.
    Complete { line: String, cursor: usize },
    /// A piped 0x03 — on a real terminal Ctrl+C is a signal, never a byte.
    Interrupt,
}

/// Decode one key from the front of `bytes`.
///
/// Returns the key and how many bytes it consumed. `(None, 0)` means the buffer
/// ends in the middle of a UTF-8 sequence: the caller must read more bytes.
pub fn decode_key(bytes: &[u8]) -> (Option<Key>, usize) {
    let Some(&first) = bytes.first() else {
        return (None, 0);
    };

    // Every escape sequence starts with ESC. A bare ESC (the user pressed Esc)
    // is one key and must not eat the character that follows it.
    if first == 0x1b {
        return decode_escape(bytes);
    }
    if bytes.len() == 1 && (0xc2..=0xf4).contains(&first) {
        // A multi-byte character whose continuation bytes have not arrived yet.
        return (None, 0);
    }

    match first {
        // Shift+Space and Ctrl+Space both send NUL on a Unix terminal. A NUL is
        // a word separator, never text.
        0x00 => (Some(Key::Char(' ')), 1),
        b'\r' | b'\n' => (Some(Key::Enter), 1),
        0x7f | 0x08 => (Some(Key::Backspace), 1),
        0x03 => (Some(Key::Interrupt), 1),
        0x04 => (Some(Key::Eof), 1),
        // Tab completes. It used to insert a literal tab into the task text.
        0x09 => (Some(Key::Complete), 1),
        // The readline control keys, so the prompt behaves like a shell prompt.
        0x01 => (Some(Key::Home), 1),
        0x02 => (Some(Key::Left), 1),
        0x05 => (Some(Key::End), 1),
        0x06 => (Some(Key::Right), 1),
        0x0b => (Some(Key::DeleteToEnd), 1),
        0x0c => (Some(Key::ClearScreen), 1),
        0x0e => (Some(Key::Down), 1),
        0x10 => (Some(Key::Up), 1),
        0x15 => (Some(Key::DeleteToStart), 1),
        0x17 => (Some(Key::DeleteWordBack), 1),
        // Any other C0 control carries no text.
        0x01..=0x1f => (Some(Key::Ignored), 1),
        _ => decode_char(bytes),
    }
}

/// Decode one UTF-8 scalar value, rejecting control characters (C1 controls are
/// how some terminals spell the same escapes).
fn decode_char(bytes: &[u8]) -> (Option<Key>, usize) {
    for len in 1..=4.min(bytes.len()) {
        match std::str::from_utf8(&bytes[..len]) {
            Ok(text) => {
                let ch = text.chars().next().expect("non-empty");
                let key = if ch.is_control() {
                    Key::Ignored
                } else {
                    Key::Char(ch)
                };
                return (Some(key), len);
            }
            Err(error) if error.error_len().is_none() && len < 4 => continue,
            Err(_) => return (Some(Key::Ignored), 1),
        }
    }
    (None, 0)
}

fn decode_escape(bytes: &[u8]) -> (Option<Key>, usize) {
    if bytes.len() == 1 {
        return (Some(Key::Ignored), 1);
    }
    if bytes[1] != b'[' && bytes[1] != b'O' {
        // `ESC x` with an ASCII x is Alt+x. An `ESC` followed by a non-ASCII
        // byte is a lone Esc (the user pressed the key) and must not swallow
        // the character that follows it.
        return match bytes[1] {
            // Alt+b / Alt+f: the other spelling terminals use for word movement.
            b'b' => (Some(Key::PrevWord), 2),
            b'f' => (Some(Key::NextWord), 2),
            byte if byte < 0x80 => (Some(Key::Ignored), 2),
            _ => (Some(Key::Ignored), 1),
        };
    }

    // Find the final byte of the CSI/SS3 sequence: 0x40..=0x7E.
    let mut index = 2;
    while index < bytes.len() {
        let byte = bytes[index];
        if (0x40..=0x7e).contains(&byte) {
            let parameter = &bytes[2..index];
            let key = match byte {
                b'A' => Key::Up,
                b'B' => Key::Down,
                // The xterm modifier form of Alt+← / Alt+→ (1;3 = Alt).
                b'C' => match parameter {
                    b"1;3" => Key::NextWord,
                    _ => Key::Right,
                },
                b'D' => match parameter {
                    b"1;3" => Key::PrevWord,
                    _ => Key::Left,
                },
                b'H' => Key::Home,
                b'F' => Key::End,
                b'~' => match parameter {
                    b"1" | b"7" => Key::Home,
                    b"4" | b"8" => Key::End,
                    b"3" => Key::Delete,
                    // Bracketed paste: the terminal brackets a paste, so the line
                    // breaks inside it are text instead of submissions.
                    b"200" => Key::PasteStart,
                    b"201" => Key::PasteEnd,
                    _ => Key::Ignored,
                },
                _ => Key::Ignored,
            };
            return (Some(key), index + 1);
        }
        index += 1;
    }

    // An unterminated sequence: swallow what we have so far as one key.
    (Some(Key::Ignored), bytes.len())
}

/// The editable line: text, cursor position (in characters) and history.
#[derive(Debug, Default)]
pub struct EditorState {
    chars: Vec<char>,
    cursor: usize,
    history: Vec<String>,
    /// The line being edited when the user first pressed Up, so Down can return.
    draft: String,
    history_index: Option<usize>,
    /// Inside a bracketed paste: a line break there is text, not a submission.
    pasting: bool,
}

impl EditorState {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn text(&self) -> String {
        self.chars.iter().collect()
    }

    pub fn is_empty(&self) -> bool {
        self.chars.is_empty()
    }

    /// The history, oldest first — persisted by the console so that arrow-up
    /// survives a restart.
    pub fn history(&self) -> &[String] {
        &self.history
    }

    /// Replace the buffer, caret at the end.
    pub fn set_text(&mut self, text: &str) {
        self.chars = text.chars().collect();
        self.cursor = self.chars.len();
    }

    /// Replace the buffer with the caret at `cursor` (clamped).
    ///
    /// Used when the session completes a line: it owns the grammar, the editor
    /// only stores the answer.
    pub fn set_text_at(&mut self, text: &str, cursor: usize) {
        self.chars = text.chars().collect();
        self.cursor = cursor.min(self.chars.len());
    }

    /// Seed the history (the console loads the persisted entries at startup).
    pub fn seed_history(&mut self, entries: impl IntoIterator<Item = String>) {
        self.history = entries.into_iter().collect();
    }

    /// Drop every history entry.
    pub fn clear_history(&mut self) {
        self.history.clear();
        self.history_index = None;
    }

    /// Throw away the draft (Ctrl+C on a line).
    pub fn clear(&mut self) {
        self.chars.clear();
        self.cursor = 0;
        self.history_index = None;
    }

    /// Apply one key. Returns what the caller should do next.
    pub fn apply(&mut self, key: Key) -> Outcome {
        match key {
            Key::Char(ch) => {
                if !ch.is_control() {
                    self.chars.insert(self.cursor, ch);
                    self.cursor += 1;
                }
                Outcome::Pending
            }
            Key::Backspace => {
                if self.cursor > 0 {
                    self.cursor -= 1;
                    self.chars.remove(self.cursor);
                }
                Outcome::Pending
            }
            Key::Delete => {
                if self.cursor < self.chars.len() {
                    self.chars.remove(self.cursor);
                }
                Outcome::Pending
            }
            Key::Left => {
                self.cursor = self.cursor.saturating_sub(1);
                Outcome::Pending
            }
            Key::Right => {
                if self.cursor < self.chars.len() {
                    self.cursor += 1;
                }
                Outcome::Pending
            }
            Key::PrevWord => {
                self.word_left();
                Outcome::Pending
            }
            Key::NextWord => {
                self.word_right();
                Outcome::Pending
            }
            Key::DeleteWordBack => {
                let end = self.cursor;
                self.word_left();
                self.chars.drain(self.cursor..end);
                Outcome::Pending
            }
            Key::DeleteToStart => {
                self.chars.drain(..self.cursor);
                self.cursor = 0;
                Outcome::Pending
            }
            Key::DeleteToEnd => {
                self.chars.truncate(self.cursor);
                Outcome::Pending
            }
            Key::Home => {
                self.cursor = 0;
                Outcome::Pending
            }
            Key::End => {
                self.cursor = self.chars.len();
                Outcome::Pending
            }
            Key::Up => {
                self.recall(-1);
                Outcome::Pending
            }
            Key::Down => {
                self.recall(1);
                Outcome::Pending
            }
            Key::Complete => Outcome::Complete {
                line: self.text(),
                cursor: self.cursor,
            },
            Key::ClearScreen => Outcome::Pending,
            Key::PasteStart => {
                self.pasting = true;
                Outcome::Pending
            }
            Key::PasteEnd => {
                self.pasting = false;
                Outcome::Pending
            }
            Key::Enter => {
                if self.pasting {
                    // A pasted line break is text: a pasted three-line task is ONE
                    // task (it used to submit the first line and steer twice), and
                    // the buffer stays on a single row so the caret math holds.
                    self.insert_collapsed_space();
                    return Outcome::Pending;
                }
                if self.continue_on_backslash() {
                    return Outcome::Pending;
                }
                let line = self.text().trim().to_string();
                self.chars.clear();
                self.cursor = 0;
                self.history_index = None;
                if !line.is_empty() && self.history.last() != Some(&line) {
                    self.history.push(line.clone());
                }
                Outcome::Submit(line)
            }
            Key::Interrupt => {
                self.clear();
                Outcome::Interrupt
            }
            Key::Eof => {
                if self.chars.is_empty() {
                    Outcome::Eof
                } else {
                    Outcome::Pending
                }
            }
            Key::Ignored => Outcome::Pending,
        }
    }

    /// A trailing `\` continues the instruction instead of submitting it.
    ///
    /// The backslash is consumed and the continuation joins with a single space,
    /// so a composed instruction stays one logical line (the prompt is one row).
    fn continue_on_backslash(&mut self) -> bool {
        let mut end = self.chars.len();
        while end > 0 && self.chars[end - 1].is_whitespace() {
            end -= 1;
        }
        if end == 0 || self.chars[end - 1] != '\\' {
            return false;
        }
        // A doubled backslash is a literal one, not a continuation.
        if end >= 2 && self.chars[end - 2] == '\\' {
            return false;
        }
        self.chars.truncate(end - 1);
        self.cursor = self.chars.len();
        self.insert_collapsed_space();
        true
    }

    /// Insert one space unless the text already ends with one.
    fn insert_collapsed_space(&mut self) {
        let previous = if self.cursor > 0 {
            self.chars.get(self.cursor - 1).copied()
        } else {
            None
        };
        if previous != Some(' ') {
            self.chars.insert(self.cursor, ' ');
            self.cursor += 1;
        }
    }

    fn word_left(&mut self) {
        while self.cursor > 0 && self.chars[self.cursor - 1].is_whitespace() {
            self.cursor -= 1;
        }
        while self.cursor > 0 && !self.chars[self.cursor - 1].is_whitespace() {
            self.cursor -= 1;
        }
    }

    fn word_right(&mut self) {
        while self.cursor < self.chars.len() && !self.chars[self.cursor].is_whitespace() {
            self.cursor += 1;
        }
        while self.cursor < self.chars.len() && self.chars[self.cursor].is_whitespace() {
            self.cursor += 1;
        }
    }

    fn recall(&mut self, step: isize) {
        if self.history.is_empty() {
            return;
        }
        if self.history_index.is_none() {
            self.draft = self.text();
            if step > 0 {
                // Down on a fresh line: there is nothing below the draft.
                return;
            }
            self.history_index = Some(self.history.len() - 1);
        } else {
            let last = self.history.len() as isize - 1;
            let index = self.history_index.unwrap_or(0) as isize + step;
            if index > last {
                // Past the newest entry: back to what the user was typing.
                self.history_index = None;
                let draft = self.draft.clone();
                self.set_text(&draft);
                return;
            }
            self.history_index = Some(index.clamp(0, last) as usize);
        }
        let text = self.history[self.history_index.unwrap_or(0)].clone();
        self.set_text(&text);
    }
}

/// Terminal width of one character: East Asian wide characters take two cells.
fn cell_width(ch: char) -> usize {
    let code = ch as u32;
    let wide = (0x1100..=0x115f).contains(&code)
        || (0x2e80..=0xa4cf).contains(&code)
        || (0xac00..=0xd7a3).contains(&code)
        || (0xf900..=0xfaff).contains(&code)
        || (0xfe30..=0xfe6f).contains(&code)
        || (0xff00..=0xff60).contains(&code)
        || (0xffe0..=0xffe6).contains(&code)
        || (0x1f300..=0x1f64f).contains(&code)
        || (0x1f900..=0x1f9ff).contains(&code)
        || (0x20000..=0x3fffd).contains(&code);
    if wide {
        2
    } else {
        1
    }
}

/// Terminal width of a string, in cells.
fn text_width(text: &str) -> usize {
    text.chars().map(cell_width).sum()
}

/// The columns a prompt line may occupy when the terminal does not say.
pub const DEFAULT_TERMINAL_WIDTH: usize = 100;

/// Never trust a width outside this range: a 0 (or a garbage value) would make
/// the redraw move the caret to a row that does not exist.
const MIN_TERMINAL_WIDTH: usize = 20;
const MAX_TERMINAL_WIDTH: usize = 1_000;

/// The terminal's width in columns.
///
/// `stty size` is the only answer that reflects the real window — a program
/// inherits `COLUMNS` only when the shell exports it — so it is tried first;
/// `COLUMNS` is the fallback for a terminal without `stty`, and
/// [DEFAULT_TERMINAL_WIDTH] the last resort.
pub fn terminal_width() -> usize {
    if let Ok(output) = std::process::Command::new("stty").arg("size").output() {
        if output.status.success() {
            let text = String::from_utf8_lossy(&output.stdout);
            if let Some(columns) = text
                .split_whitespace()
                .nth(1)
                .and_then(|value| value.parse::<usize>().ok())
            {
                return columns.clamp(MIN_TERMINAL_WIDTH, MAX_TERMINAL_WIDTH);
            }
        }
    }
    std::env::var("COLUMNS")
        .ok()
        .and_then(|value| value.trim().parse::<usize>().ok())
        .unwrap_or(DEFAULT_TERMINAL_WIDTH)
        .clamp(MIN_TERMINAL_WIDTH, MAX_TERMINAL_WIDTH)
}

/// One rendered prompt line, plus where the caret was left.
pub struct Rendered {
    /// Erase the previous rendering, draw this one, then position the caret.
    pub bytes: String,
    /// Row of the caret relative to the first row of the rendering: the next
    /// render must move up this many rows to reach its start.
    pub caret_row: usize,
}

/// The bytes that erase a rendering whose caret sits `caret_row` rows below its
/// first row: go back to that row, then clear everything from there down.
fn erase(caret_row: usize) -> String {
    let mut bytes = String::new();
    if caret_row > 0 {
        bytes.push_str(&format!("\x1b[{caret_row}A"));
    }
    bytes.push('\r');
    // The prompt line is the last thing on screen, so erasing to the end of the
    // screen clears a line that wrapped over several rows — `\x1b[K` would leave
    // the wrapped rows behind.
    bytes.push_str("\x1b[J");
    bytes
}

/// Render the prompt line for a raw-mode terminal.
///
/// The line may wrap: `width` columns make one row, so a long task (or a CJK one,
/// whose characters take two cells) occupies several, and the caret has to be
/// put back on the right one. `from_caret_row` is where the caret of the
/// rendering currently on screen sits — 0 when nothing is drawn yet or when the
/// cursor is already on its first row.
pub fn render(
    prompt: &str,
    state: &EditorState,
    width: usize,
    from_caret_row: usize,
) -> Rendered {
    let width = width.max(1);
    let mut bytes = erase(from_caret_row);
    bytes.push_str(prompt);
    bytes.push_str(&state.text());

    let prompt_width = text_width(prompt);
    let head: usize = state.chars[..state.cursor]
        .iter()
        .copied()
        .map(cell_width)
        .sum();
    let cells = prompt_width
        + state.chars.iter().copied().map(cell_width).sum::<usize>();
    let caret_cell = prompt_width + head;
    // Where the terminal leaves the cursor after writing the line: the row of the
    // last written cell. A line that ends exactly on a column boundary has not
    // wrapped yet (deferred wrap), so that is still the last written row.
    let last_row = cells.saturating_sub(1) / width;
    let caret_row = caret_cell / width;
    let caret_col = caret_cell % width;
    if last_row > caret_row {
        bytes.push_str(&format!("\x1b[{}A", last_row - caret_row));
    }
    bytes.push('\r');
    if caret_row > last_row {
        bytes.push_str(&format!("\x1b[{}B", caret_row - last_row));
    }
    if caret_col > 0 {
        bytes.push_str(&format!("\x1b[{caret_col}C"));
    }
    Rendered { bytes, caret_row }
}

/// The prompt the interactive session draws while no task is running.
pub const PROMPT: &str = "agent> ";

/// How many submitted lines arrow-up remembers.
const HISTORY_LIMIT: usize = 200;

/// The history file, or `None` when the feature is switched off.
///
/// One file per user, like a shell history: the tasks a person types are theirs,
/// not a session's. `BROWSER4_CLI_NO_HISTORY=1` turns it off (the file is then
/// neither read nor written), and a failure to read or write it is silent — a
/// convenience must never be able to break a session.
fn history_file() -> Option<PathBuf> {
    if history_disabled() {
        return None;
    }
    Some(crate::state::resolve_default_state_dir().join("agent-repl-history"))
}

fn history_disabled() -> bool {
    match std::env::var("BROWSER4_CLI_NO_HISTORY") {
        Ok(value) => {
            let value = value.trim().to_ascii_lowercase();
            !(value.is_empty() || value == "0" || value == "false" || value == "off")
        }
        Err(_) => false,
    }
}

/// Read the history (oldest first), keeping the newest [HISTORY_LIMIT] entries.
///
/// An append-only file grows forever, so loading is where it gets compacted: once
/// it has grown past twice the cap it is rewritten with the entries it kept.
fn load_history(path: &Path) -> Vec<String> {
    let Ok(text) = std::fs::read_to_string(path) else {
        return Vec::new();
    };
    let mut entries: Vec<String> = text
        .lines()
        .map(str::trim_end)
        .filter(|line| !line.is_empty())
        .map(str::to_string)
        .collect();
    if entries.len() > HISTORY_LIMIT {
        entries.drain(..entries.len() - HISTORY_LIMIT);
        let _ = write_history(path, &entries);
    }
    entries
}

fn write_history(path: &Path, entries: &[String]) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let mut options = std::fs::OpenOptions::new();
    options.create(true).write(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path)?;
    for entry in entries {
        writeln!(file, "{entry}")?;
    }
    Ok(())
}

/// Legacy editor kept for callers that use the simple key-decoding path. Prefer
/// [Console] for new code — it owns the prompt line and the human writer together,
/// so streamed output and typed input cannot tear each other apart.
#[allow(dead_code)]
pub struct Editor {
    prompt: String,
    state: Mutex<EditorState>,
    enabled: bool,
    out: Mutex<io::Stdout>,
}

#[allow(dead_code)]
impl Editor {
    pub fn new(prompt: &str, enabled: bool) -> Arc<Self> {
        Arc::new(Self {
            prompt: prompt.to_string(),
            state: Mutex::new(EditorState::new()),
            enabled,
            out: Mutex::new(io::stdout()),
        })
    }

    /// Whether this editor owns the terminal (TTY stdin).
    pub fn enabled(&self) -> bool {
        self.enabled
    }

    /// Redraw the current line. Called after the caller printed output, so the
    /// prompt is not left buried under a task event.
    pub fn redraw(&self) {
        if !self.enabled {
            return;
        }
        let state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let mut out = self
            .out
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let rendered = render(&self.prompt, &state, DEFAULT_TERMINAL_WIDTH, 0);
        let _ = out.write_all(rendered.bytes.as_bytes());
        let _ = out.flush();
    }

    /// Feed raw bytes read from the terminal. Returns the submitted lines.
    pub fn feed(&self, bytes: &[u8]) -> Vec<String> {
        let mut submitted = Vec::new();
        let mut remaining = bytes;
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        loop {
            let (key, used) = decode_key(remaining);
            let Some(key) = key else { break };
            remaining = &remaining[used..];
            match state.apply(key) {
                Outcome::Pending => {}
                Outcome::Submit(line) => {
                    if self.enabled {
                        let mut out = self.out.lock().unwrap_or_else(|p| p.into_inner());
                        let _ = write!(out, "\r\n");
                        let _ = out.flush();
                    }
                    submitted.push(line);
                }
                Outcome::Interrupt => {
                    // Under `-icanon` with ISIG on, Ctrl+C is a signal and never
                    // reaches this decoder; a piped 0x03 (if this path is ever
                    // wired for pipes) just clears the line.
                    if self.enabled {
                        let mut out = self.out.lock().unwrap_or_else(|p| p.into_inner());
                        let _ = write!(out, "^C\r\n{}", self.prompt);
                        let _ = out.flush();
                    }
                }
                Outcome::Eof => {
                    submitted.push(String::new());
                    break;
                }
                Outcome::Complete { .. } => {}
            }
            if self.enabled {
                let mut out = self.out.lock().unwrap_or_else(|p| p.into_inner());
                if !submitted.is_empty() {
                    let _ = write!(out, "{}", self.prompt);
                } else {
                    let rendered = render(&self.prompt, &state, DEFAULT_TERMINAL_WIDTH, 0);
                    let _ = out.write_all(rendered.bytes.as_bytes());
                }
                let _ = out.flush();
            }
        }
        submitted
    }
}

/// The prompt line, the draft being typed on it, and the one writer every human
/// line of an interactive session goes through.
///
/// Owning the line is what keeps typed text and streamed steps from tearing each
/// other apart. Before a line is printed the console erases the prompt line;
/// afterwards it draws the prompt and the unfinished draft again. Without that, a
/// step line was appended to `agent> <half a sentence>`, and the next keystroke
/// redrew the draft one row lower — the user could no longer tell which line was
/// their input.
struct Line {
    prompt: String,
    state: EditorState,
    width: usize,
    /// Whether the prompt line is on screen right now.
    drawn: bool,
    /// Row of the caret relative to the first row of that prompt line.
    caret_row: usize,
    /// The session ended: the line is no longer claimed, so nothing is drawn on
    /// it again (the shell prompt follows).
    released: bool,
}

/// The interactive console: prompt line plus the single writer for human output.
pub struct Console {
    line: Mutex<Line>,
    out: Mutex<Box<dyn Write + Send>>,
    /// Whether this session draws its own prompt line at all: a terminal on both
    /// ends, and human output enabled. `--quiet` / `--json` leave stdout to the
    /// machine, and `-i | tee log` must not carry prompts or escape codes into
    /// the log.
    enabled: bool,
    /// Whether key-level input is active. A cooked console (no `stty` — the
    /// Windows console) echoes and edits the input line itself, so clearing that
    /// line from here would fight it; such a console only writes lines out.
    key_editing: bool,
    /// Where submitted lines are remembered (`None`: not persisted).
    history_path: Option<std::path::PathBuf>,
}

impl Console {
    pub fn new(prompt: &str, enabled: bool, key_editing: bool) -> Arc<Self> {
        let width = if key_editing {
            terminal_width()
        } else {
            DEFAULT_TERMINAL_WIDTH
        };
        // History is only useful where keys are decoded; a piped or machine-output
        // session must not touch the state directory for it.
        let history_path = if key_editing { history_file() } else { None };
        Self::build(
            prompt,
            enabled,
            key_editing,
            width,
            Box::new(io::stdout()),
            history_path,
        )
    }

    /// A console writing to `sink`, without persisting history.
    ///
    /// Tests and any caller that owns its own output use this; [`Console::new`] is
    /// the one a session gets.
    #[cfg(test)]
    pub fn with_sink(
        prompt: &str,
        enabled: bool,
        key_editing: bool,
        width: usize,
        sink: Box<dyn Write + Send>,
    ) -> Arc<Self> {
        Self::build(prompt, enabled, key_editing, width, sink, None)
    }

    /// A console that persists its history at `history` — what [`Console::new`]
    /// does with the real state directory, without touching it.
    #[cfg(test)]
    fn with_sink_and_history(
        prompt: &str,
        enabled: bool,
        key_editing: bool,
        width: usize,
        sink: Box<dyn Write + Send>,
        history: PathBuf,
    ) -> Arc<Self> {
        Self::build(prompt, enabled, key_editing, width, sink, Some(history))
    }

    fn build(
        prompt: &str,
        enabled: bool,
        key_editing: bool,
        width: usize,
        sink: Box<dyn Write + Send>,
        history_path: Option<PathBuf>,
    ) -> Arc<Self> {
        let mut state = EditorState::new();
        if let Some(path) = &history_path {
            state.seed_history(load_history(path));
        }
        Arc::new(Self {
            line: Mutex::new(Line {
                prompt: prompt.to_string(),
                state,
                width,
                drawn: false,
                caret_row: 0,
                released: false,
            }),
            out: Mutex::new(sink),
            enabled,
            key_editing,
            history_path,
        })
    }

    /// Whether this console draws the prompt line.
    pub fn enabled(&self) -> bool {
        self.enabled
    }

    /// Whether the terminal hands us individual keys (cbreak mode is on).
    pub fn key_editing(&self) -> bool {
        self.key_editing
    }

    /// Change the prompt line — the session says whether the next typed line is a
    /// task (`agent> `), steers a running one (`steer> `), or has to answer an
    /// access request (`steer!> `).
    ///
    /// A prompt that is on screen is redrawn in place, so the change is visible
    /// while a task is still streaming.
    pub fn set_prompt(&self, prompt: &str) {
        let mut line = self.lock_line();
        if line.prompt == prompt {
            return;
        }
        line.prompt = prompt.to_string();
        if line.drawn {
            let mut out = self.lock_out();
            self.redraw_locked(&mut line, &mut **out);
        }
    }

    /// Draw the prompt and the draft. The session asks for this when it is ready
    /// for input again.
    pub fn draw(&self) {
        if !self.enabled {
            return;
        }
        let mut line = self.lock_line();
        let mut out = self.lock_out();
        self.redraw_locked(&mut line, &mut **out);
    }

    /// Stop claiming the prompt line: erase it and leave the cursor at the start
    /// of a fresh line.
    ///
    /// Called when the session ends. Without it the last `agent> ` stayed on
    /// screen and the shell's own prompt was appended to it.
    pub fn release(&self) {
        let mut line = self.lock_line();
        line.released = true;
        if !line.drawn {
            return;
        }
        let mut out = self.lock_out();
        let _ = out.write_all(erase(line.caret_row).as_bytes());
        let _ = out.flush();
        line.drawn = false;
        line.caret_row = 0;
    }

    /// Print one human line without swallowing what the user is typing.
    ///
    /// Erase the prompt line, write the line, then draw the prompt and the draft
    /// again. Every human line of the session goes through here (see
    /// [`write_human_line`]), so the prompt stays the last thing on screen no
    /// matter how fast steps arrive.
    pub fn write_line(&self, text: &str) {
        let mut line = self.lock_line();
        let mut out = self.lock_out();
        if !self.enabled {
            let _ = writeln!(out, "{text}");
            return;
        }
        if line.drawn {
            let _ = out.write_all(erase(line.caret_row).as_bytes());
            line.drawn = false;
            line.caret_row = 0;
        }
        let _ = writeln!(out, "{text}");
        if self.key_editing && !line.released {
            self.redraw_locked(&mut line, &mut **out);
        }
        let _ = out.flush();
    }

    /// Feed raw bytes read from the terminal.
    pub fn feed(&self, bytes: &[u8]) -> Vec<Input> {
        let mut produced = Vec::new();
        let mut line = self.lock_line();
        let mut out = self.lock_out();
        let mut remaining = bytes;
        loop {
            let (key, used) = decode_key(remaining);
            let Some(key) = key else { break };
            remaining = &remaining[used..];
            // Ctrl+L is a display action, not an edit: the buffer stays, the
            // screen starts over with the prompt and the draft redrawn.
            if key == Key::ClearScreen {
                if self.enabled {
                    let _ = out.write_all(b"\x1b[H\x1b[2J");
                    line.drawn = false;
                    line.caret_row = 0;
                    self.redraw_locked(&mut line, &mut **out);
                }
                continue;
            }
            match line.state.apply(key) {
                Outcome::Pending => self.redraw_locked(&mut line, &mut **out),
                Outcome::Submit(text) => {
                    // The submitted line is committed: end the prompt line and stop
                    // claiming it. The caller owns the screen until it asks for a new
                    // prompt, which is what keeps `agent> ` from preceding a step line.
                    if self.enabled {
                        let _ = out.write_all(b"\r\n");
                        let _ = out.flush();
                    }
                    line.drawn = false;
                    line.caret_row = 0;
                    self.append_history(&line.state, &text);
                    produced.push(Input::Line(text));
                }
                Outcome::Complete {
                    line: text,
                    cursor,
                } => produced.push(Input::Complete {
                    line: text,
                    cursor,
                }),
                Outcome::Interrupt => {
                    // Under `-icanon` with ISIG on, Ctrl+C is a signal and never
                    // reaches this decoder; a piped 0x03 (if this path is ever
                    // wired for pipes) just clears the line.
                    if self.enabled {
                        let _ = out.write_all(b"^C\r\n");
                        line.drawn = false;
                        line.caret_row = 0;
                        self.redraw_locked(&mut line, &mut **out);
                    }
                    produced.push(Input::Interrupt);
                }
                Outcome::Eof => {
                    produced.push(Input::Line(String::new()));
                    break;
                }
            }
        }
        produced
    }

    /// Replace the draft and the caret — the session's answer to a Tab.
    pub fn set_text(&self, text: &str, cursor: usize) {
        let mut line = self.lock_line();
        line.state.set_text_at(text, cursor);
        let mut out = self.lock_out();
        self.redraw_locked(&mut line, &mut **out);
    }

    /// Throw away the draft. Returns whether there was one.
    ///
    /// Ctrl+C with nothing running clears the line instead of ending the session.
    pub fn clear_draft(&self) -> bool {
        let mut line = self.lock_line();
        if line.state.is_empty() {
            return false;
        }
        line.state.clear();
        let mut out = self.lock_out();
        self.redraw_locked(&mut line, &mut **out);
        true
    }

    /// The draft, for tests: the session never reads the buffer back (it answers a
    /// completion with a whole new line).
    #[cfg(test)]
    pub fn text(&self) -> String {
        self.lock_line().state.text()
    }

    /// The caret position, for tests.
    #[cfg(test)]
    pub fn cursor(&self) -> usize {
        self.lock_line().state.cursor
    }

    /// The history, oldest first.
    pub fn history(&self) -> Vec<String> {
        self.lock_line().state.history().to_vec()
    }

    /// Forget the history, in memory and on disk.
    pub fn clear_history(&self) {
        let mut line = self.lock_line();
        line.state.clear_history();
        if let Some(path) = &self.history_path {
            let _ = std::fs::remove_file(path);
        }
    }

    /// Append a submitted line to the persisted history.
    ///
    /// The editor keeps the in-memory list; this is the same entry written out so
    /// arrow-up survives a restart. Nothing is written when the feature is off,
    /// and a failure to write is never allowed to disturb the session.
    fn append_history(&self, state: &EditorState, text: &str) {
        let Some(path) = &self.history_path else {
            return;
        };
        if text.trim().is_empty() || state.history().last().map(String::as_str) != Some(text) {
            return;
        }
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let mut options = std::fs::OpenOptions::new();
        options.create(true).append(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        if let Ok(mut file) = options.open(path) {
            let _ = writeln!(file, "{text}");
        }
    }

    fn lock_line(&self) -> std::sync::MutexGuard<'_, Line> {
        self.line
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn lock_out(&self) -> std::sync::MutexGuard<'_, Box<dyn Write + Send>> {
        self.out
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Draw the prompt + draft into `out`, remembering where the caret ended up.
    ///
    /// The caller holds both locks: `line` first, then `out`. That order is the
    /// only one taken anywhere in this module, so there is no lock cycle.
    fn redraw_locked(&self, line: &mut Line, out: &mut dyn Write) {
        if !self.enabled || line.released {
            return;
        }
        let from = if line.drawn { line.caret_row } else { 0 };
        let rendered = render(&line.prompt, &line.state, line.width, from);
        let _ = out.write_all(rendered.bytes.as_bytes());
        let _ = out.flush();
        line.drawn = true;
        line.caret_row = rendered.caret_row;
    }
}

/// The console of this process, when an interactive session owns the terminal.
static INSTALLED: Mutex<Option<Arc<Console>>> = Mutex::new(None);

fn lock_installed() -> std::sync::MutexGuard<'static, Option<Arc<Console>>> {
    INSTALLED
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn install(console: Arc<Console>) {
    *lock_installed() = Some(console);
}

fn uninstall() {
    *lock_installed() = None;
}

/// Print one human line through the installed console.
///
/// Returns `false` when no console owns the terminal, in which case the caller
/// writes the line itself — piped sessions, `--json` and every non-interactive
/// command keep their byte-exact output.
pub fn write_human_line(line: &str) -> bool {
    // The guard is cloned out and dropped before anything is printed: holding the
    // registry lock while writing would make the output path depend on it.
    let console = lock_installed().clone();
    match console {
        Some(console) => {
            console.write_line(line);
            true
        }
        None => false,
    }
}

/// Takes the terminal for one interactive session and gives it back on every exit
/// path.
///
/// The cbreak guard lives **here**, in the session's own stack frame, and not in
/// the reader thread: `/quit` returns from the session and the process exits
/// without ever joining that thread, so a guard held there was never dropped —
/// the terminal stayed in `-icanon -echo` and the shell stopped echoing what the
/// user typed.
pub struct ConsoleGuard {
    raw: Option<RawMode>,
    installed: bool,
}

impl ConsoleGuard {
    /// Install `console` for human output when it owns key-level input, and keep
    /// the cbreak guard alive until the returned value drops.
    pub fn begin(console: &Arc<Console>, raw: Option<RawMode>) -> Self {
        let installed = console.key_editing();
        if installed {
            install(Arc::clone(console));
            // Bracketed paste: the terminal then marks a paste with
            // `ESC [ 200 ~` … `ESC [ 201 ~`, which is how a pasted multi-line task
            // stays ONE task instead of submitting its first line and steering with
            // the rest. A terminal that does not know the mode ignores it.
            let mut out = io::stdout().lock();
            let _ = out.write_all(b"\x1b[?2004h");
            let _ = out.flush();
        }
        Self { raw, installed }
    }
}

impl Drop for ConsoleGuard {
    fn drop(&mut self) {
        if self.installed {
            uninstall();
            let mut out = io::stdout().lock();
            let _ = out.write_all(b"\x1b[?2004l");
            let _ = out.flush();
        }
        if let Some(raw) = self.raw.as_mut() {
            // Idempotent: a signal path may have restored the terminal already.
            raw.restore_now();
        }
    }
}

/// Put the terminal into cbreak mode for the lifetime of the guard, restoring the
/// previous settings on drop (including on panic).
///
/// `-icanon -echo` (not `raw`): the CLI reads one key at a time and echoes the
/// line itself, while the terminal keeps `ISIG` — so Ctrl+C still raises SIGINT
/// and the session's existing interrupt handling keeps working.
///
/// `None` on a platform without `stty` (the Windows console): the session then
/// keeps the console's own line editing — no key decoding, no persisted history,
/// no Tab completion, no bracketed paste — and the rest of the session (steering,
/// `/` commands, Ctrl+C) is unchanged. A raw-mode implementation for that console
/// (`SetConsoleMode` + `ReadConsoleInputW`) is a known follow-up; it would reuse
/// [`Console`] and [`EditorState`] as they are.
pub struct RawMode {
    saved: Option<String>,
}

impl RawMode {
    /// `None` when stdin is not a terminal or `stty` is unavailable.
    pub fn enable() -> Option<Self> {
        let saved = std::process::Command::new("stty")
            .arg("-g")
            .output()
            .ok()
            .filter(|output| output.status.success())
            .map(|output| String::from_utf8_lossy(&output.stdout).trim().to_string())
            .filter(|saved| !saved.is_empty())?;
        let status = std::process::Command::new("stty")
            .args(["-icanon", "-echo", "min", "1", "time", "0"])
            .status()
            .ok()?;
        if !status.success() {
            return None;
        }
        Some(Self { saved: Some(saved) })
    }

    /// Restore the saved settings now.
    ///
    /// Idempotent: the second call (from `Drop`) is a no-op. Signal paths restore
    /// the terminal up front instead of relying on a destructor that a process
    /// killed by SIGTERM would never run.
    pub fn restore_now(&mut self) {
        self.restore();
        self.saved = None;
    }

    fn restore(&self) {
        if let Some(saved) = &self.saved {
            let _ = std::process::Command::new("stty").arg(saved).status();
        }
    }
}

impl Drop for RawMode {
    fn drop(&mut self) {
        self.restore();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn keys(bytes: &[u8]) -> Vec<Key> {
        let mut out = Vec::new();
        let mut rest = bytes;
        while let (Some(key), used) = decode_key(rest) {
            assert!(used > 0, "decoder made no progress");
            out.push(key);
            rest = &rest[used..];
        }
        out
    }

    #[test]
    fn arrows_from_linux_terminals_are_editing_keys() {
        assert_eq!(
            keys(b"\x1b[D\x1b[C\x1b[A\x1b[B"),
            vec![Key::Left, Key::Right, Key::Up, Key::Down]
        );
        // Application cursor mode (SS3) and Home/End with parameters.
        assert_eq!(keys(b"\x1bOD"), vec![Key::Left]);
        assert_eq!(keys(b"\x1b[1~\x1b[4~"), vec![Key::Home, Key::End]);
        assert_eq!(keys(b"\x1b[3~"), vec![Key::Delete]);
    }

    #[test]
    fn shift_space_separates_words_instead_of_vanishing() {
        assert_eq!(
            keys(b"one\0two"),
            vec![
                Key::Char('o'),
                Key::Char('n'),
                Key::Char('e'),
                Key::Char(' '),
                Key::Char('t'),
                Key::Char('w'),
                Key::Char('o'),
            ]
        );
    }

    #[test]
    fn lone_escape_does_not_eat_the_following_text() {
        assert_eq!(
            keys("a\x1b中".as_bytes()),
            vec![Key::Char('a'), Key::Ignored, Key::Char('中')]
        );
    }

    #[test]
    fn multibyte_input_survives_a_split_buffer() {
        let bytes = "检查词句".as_bytes();
        let (key, used) = decode_key(&bytes[..1]);
        assert_eq!((key, used), (None, 0));
        let (key, used) = decode_key(&bytes[..3]);
        assert_eq!(key, Some(Key::Char('检')));
        assert_eq!(used, 3);
    }

    #[test]
    fn editing_uses_the_cursor_not_the_end_of_the_line() {
        let mut state = EditorState::new();
        state.set_text("检查词句");
        for _ in 0..2 {
            state.apply(Key::Left);
        }
        state.apply(Key::Char('的'));
        assert_eq!(state.text(), "检查的词句");
        state.apply(Key::Backspace);
        assert_eq!(state.text(), "检查词句");
        state.apply(Key::Home);
        state.apply(Key::Delete);
        assert_eq!(state.text(), "查词句");
        state.apply(Key::End);
        state.apply(Key::Char('!'));
        assert_eq!(state.text(), "查词句!");
    }

    #[test]
    fn history_walks_back_and_returns_the_draft() {
        let mut state = EditorState::new();
        state.set_text("first");
        assert_eq!(state.apply(Key::Enter), Outcome::Submit("first".into()));
        state.set_text("second");
        assert_eq!(state.apply(Key::Enter), Outcome::Submit("second".into()));

        state.set_text("dra");
        state.apply(Key::Up);
        assert_eq!(state.text(), "second");
        state.apply(Key::Up);
        assert_eq!(state.text(), "first");
        state.apply(Key::Down);
        assert_eq!(state.text(), "second");
        state.apply(Key::Down);
        assert_eq!(state.text(), "dra");
    }

    #[test]
    fn render_puts_the_caret_back_under_the_cursor() {
        let mut state = EditorState::new();
        state.set_text("ab检查");
        state.apply(Key::Left);
        state.apply(Key::Left);
        // prompt (7 cells) + "ab" (2) + two wide characters (4): the caret sits
        // after 9 cells, on the only row.
        let rendered = render("agent> ", &state, 100, 0);
        assert_eq!(rendered.bytes, "\r\x1b[Jagent> ab检查\r\x1b[9C");
        assert_eq!(rendered.caret_row, 0);
        state.apply(Key::End);
        let rendered = render("agent> ", &state, 100, 0);
        assert_eq!(rendered.bytes, "\r\x1b[Jagent> ab检查\r\x1b[13C");
        assert_eq!(rendered.caret_row, 0);
    }

    #[test]
    fn render_goes_back_to_the_row_the_caret_is_on() {
        // 14 cells on a 10-column terminal: two rows, the caret at the end of the
        // second one.
        let mut state = EditorState::new();
        state.set_text("0123456789ab");
        let rendered = render("> ", &state, 10, 0);
        assert_eq!(rendered.bytes, "\r\x1b[J> 0123456789ab\r\x1b[4C");
        assert_eq!(rendered.caret_row, 1);

        // One character into the input the caret is still on the first row, so the
        // redraw must go back up one row before positioning it (prompt 2 cells +
        // 1 cell = column 3).
        let mut state = EditorState::new();
        state.set_text("0123456789ab");
        state.apply(Key::Home);
        assert_eq!(state.apply(Key::Right), Outcome::Pending);
        let rendered = render("> ", &state, 10, 0);
        assert_eq!(rendered.bytes, "\r\x1b[J> 0123456789ab\x1b[1A\r\x1b[3C");
        assert_eq!(rendered.caret_row, 0);
    }

    #[test]
    fn the_next_render_erases_what_is_on_screen_from_its_first_row() {
        let mut state = EditorState::new();
        state.set_text("x");
        // The caret of the previous rendering sat on row 2: start by going back.
        let rendered = render("agent> ", &state, 100, 2);
        assert!(rendered.bytes.starts_with("\x1b[2A\r\x1b[J"), "{}", rendered.bytes);
    }

    /// A writer that keeps what the console prints, so the arbitration can be
    /// asserted without a terminal.
    #[derive(Clone, Default)]
    struct SharedSink(Arc<Mutex<Vec<u8>>>);

    impl SharedSink {
        fn text(&self) -> String {
            String::from_utf8_lossy(&self.0.lock().unwrap()).to_string()
        }
    }

    impl Write for SharedSink {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            self.0.lock().unwrap().extend_from_slice(buf);
            Ok(buf.len())
        }

        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    fn console_with_sink(enabled: bool, key_editing: bool) -> (Arc<Console>, SharedSink) {
        let sink = SharedSink::default();
        let console = Console::with_sink(
            "agent> ",
            enabled,
            key_editing,
            100,
            Box::new(sink.clone()),
        );
        (console, sink)
    }

    #[test]
    fn output_redraws_the_prompt_and_the_unfinished_draft() {
        let (console, sink) = console_with_sink(true, true);
        console.feed(b"summ");
        let before = sink.text();

        console.write_line("[00:01] #1 b4.run");

        let text = sink.text();
        let printed = text
            .strip_prefix(&before)
            .expect("the console keeps what it already wrote");
        assert_eq!(
            printed, "\r\x1b[J[00:01] #1 b4.run\n\r\x1b[Jagent> summ\r\x1b[11C",
            "the step line owns its row and the draft comes back below it"
        );
    }

    #[test]
    fn a_submitted_line_ends_the_prompt_line_until_the_session_draws_again() {
        let (console, sink) = console_with_sink(true, true);
        let lines = console.feed(b"do it\r");
        assert_eq!(lines, vec![Input::Line("do it".to_string())]);
        assert!(
            sink.text().ends_with("\r\n"),
            "the submitted line must end the prompt line: {:?}",
            sink.text()
        );
        console.draw();
        assert!(
            sink.text().ends_with("agent> \r\x1b[7C"),
            "the session asks for the prompt when it is ready: {:?}",
            sink.text()
        );
    }

    #[test]
    fn a_console_that_does_not_own_the_prompt_writes_lines_verbatim() {
        let (console, sink) = console_with_sink(false, false);
        console.write_line("plain line");
        console.draw();
        assert_eq!(sink.text(), "plain line\n");
    }

    #[test]
    fn releasing_the_line_erases_the_prompt_and_stops_redrawing_it() {
        let (console, sink) = console_with_sink(true, true);
        console.draw();
        console.release();
        assert!(
            sink.text().ends_with("\r\x1b[J"),
            "the prompt must be erased when the session ends: {:?}",
            sink.text()
        );

        let before = sink.text();
        console.write_line("bye");
        assert_eq!(
            sink.text().strip_prefix(&before),
            Some("bye\n"),
            "a released console no longer draws a prompt"
        );
    }

    #[test]
    fn a_pasted_line_with_escape_noise_keeps_its_text() {
        // The bytes the user's terminal sends when they paste a line and press
        // arrow keys inside it: the words must survive as text and the keys must
        // move the caret instead of being inserted.
        let editor = Console::new("agent> ", false, false);
        let lines = editor.feed(b"one\x00two\x1b[D\x1b[DX\r");
        assert_eq!(lines, vec![Input::Line("one tXwo".to_string())]);
    }

    #[test]
    fn the_readline_control_keys_edit_the_line() {
        // Ctrl+A/E/B/F/P/N/U/K/W/L and Tab: the keys a shell prompt answers to.
        // Before this, `decode_key` folded 0x01..=0x1f into `Ignored`, so Ctrl+A
        // and Ctrl+E did nothing while Tab inserted a literal tab into the task.
        assert_eq!(
            keys(b"\x01\x05\x02\x06\x10\x0e\x15\x0b\x17\x0c\x09"),
            vec![
                Key::Home,
                Key::End,
                Key::Left,
                Key::Right,
                Key::Up,
                Key::Down,
                Key::DeleteToStart,
                Key::DeleteToEnd,
                Key::DeleteWordBack,
                Key::ClearScreen,
                Key::Complete,
            ]
        );
        // Alt+← / Alt+→ arrive two ways (CSI with the Alt modifier, and ESC b/f).
        assert_eq!(
            keys(b"\x1b[1;3D\x1b[1;3C"),
            vec![Key::PrevWord, Key::NextWord]
        );
        assert_eq!(keys(b"\x1bb\x1bf"), vec![Key::PrevWord, Key::NextWord]);
    }

    #[test]
    fn word_keys_move_and_delete_by_word() {
        let mut state = EditorState::new();
        state.set_text("one two three");
        state.apply(Key::PrevWord);
        assert_eq!(state.apply(Key::DeleteWordBack), Outcome::Pending);
        assert_eq!(state.text(), "one three");
        // The caret is where the deleted word started, so Ctrl+U clears up to it.
        state.apply(Key::DeleteToStart);
        assert_eq!(state.text(), "three");

        let mut state = EditorState::new();
        state.set_text("one two");
        state.apply(Key::Home);
        state.apply(Key::NextWord);
        state.apply(Key::Char('!'));
        assert_eq!(state.text(), "one !two");
        state.apply(Key::DeleteToEnd);
        assert_eq!(state.text(), "one !");
    }

    #[test]
    fn tab_asks_the_session_to_complete_the_line() {
        // The editor stores the buffer but the session owns the command grammar:
        // Tab travels out as a request instead of inserting a tab character.
        let console = Console::with_sink("agent> ", false, false, 100, Box::new(io::sink()));
        assert_eq!(
            console.feed(b"/sta\t"),
            vec![Input::Complete {
                line: "/sta".to_string(),
                cursor: 4,
            }]
        );
    }

    #[test]
    fn a_pasted_multi_line_task_is_one_submission() {
        // The terminal brackets a paste (`ESC [ 200 ~` … `ESC [ 201 ~`); a line
        // break inside it is text. Three pasted lines used to submit the first one
        // as a task and steer with the other two.
        let console = Console::with_sink("agent> ", false, false, 100, Box::new(io::sink()));
        let inputs =
            console.feed(b"\x1b[200~read the spec\nsummarise it\nkeep it short\x1b[201~\r");
        assert_eq!(
            inputs,
            vec![Input::Line(
                "read the spec summarise it keep it short".to_string()
            )]
        );
    }

    #[test]
    fn a_trailing_backslash_continues_the_instruction() {
        let console = Console::with_sink("agent> ", false, false, 100, Box::new(io::sink()));
        assert!(
            console.feed(b"first part \\\r").is_empty(),
            "a trailing backslash must not submit yet"
        );
        assert_eq!(
            console.feed(b"second part\r"),
            vec![Input::Line("first part second part".to_string())]
        );
    }

    #[test]
    fn ctrl_l_clears_the_screen_and_keeps_the_draft() {
        let (console, sink) = console_with_sink(true, true);
        console.feed(b"draft");
        let before = sink.text().len();
        console.feed(b"\x0c");
        let cleared = sink.text()[before..].to_string();
        assert!(cleared.starts_with("\x1b[H\x1b[2J"), "{cleared:?}");
        assert!(cleared.contains("agent> draft"), "{cleared:?}");
        assert_eq!(console.text(), "draft");
    }

    #[test]
    fn ctrl_c_clears_the_draft_only_when_there_is_one() {
        let (console, _sink) = console_with_sink(true, true);
        console.feed(b"half a task");
        assert!(console.clear_draft(), "a typed line is cleared");
        assert_eq!(console.text(), "");
        assert!(
            !console.clear_draft(),
            "an empty line reports that there was nothing to clear"
        );
    }

    #[test]
    fn the_session_can_replace_the_line_it_completed() {
        let (console, _sink) = console_with_sink(true, true);
        console.feed(b"/sta");
        console.set_text("/status ", 8);
        assert_eq!(console.text(), "/status ");
        assert_eq!(console.cursor(), 8);
    }

    #[test]
    fn history_survives_a_restart_and_stays_capped() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("agent-repl-history");

        let first = Console::with_sink_and_history(
            "agent> ",
            false,
            true,
            100,
            Box::new(io::sink()),
            path.clone(),
        );
        first.feed(b"task one\r");
        first.feed(b"task two\r");
        assert_eq!(
            first.history(),
            vec!["task one".to_string(), "task two".to_string()]
        );

        // A new console is what the next `-i` sees: arrow-up still works.
        let second = Console::with_sink_and_history(
            "agent> ",
            false,
            true,
            100,
            Box::new(io::sink()),
            path.clone(),
        );
        assert_eq!(
            second.history(),
            vec!["task one".to_string(), "task two".to_string()]
        );

        // An append-only file grows, so loading compacts it down to the cap.
        let mut entries: Vec<String> =
            (0..(HISTORY_LIMIT + 50)).map(|index| format!("t{index}")).collect();
        write_history(&path, &entries).expect("write history");
        let third = Console::with_sink_and_history(
            "agent> ",
            false,
            true,
            100,
            Box::new(io::sink()),
            path.clone(),
        );
        entries.drain(..entries.len() - HISTORY_LIMIT);
        assert_eq!(third.history(), entries);
        let reread = std::fs::read_to_string(&path).expect("history file");
        assert_eq!(
            reread.lines().count(),
            HISTORY_LIMIT,
            "the file is compacted on load"
        );
    }

    #[test]
    fn a_history_path_is_optional() {
        let (console, _sink) = console_with_sink(true, true);
        console.feed(b"task\r");
        assert!(
            console.history() == vec!["task".to_string()],
            "the in-memory list still works without a file"
        );
    }
}
