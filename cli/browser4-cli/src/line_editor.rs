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

use std::io::{self, Write};
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
    /// Ctrl+C.
    Interrupt,
    /// Ctrl+D at the start of an empty line.
    Eof,
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
        // Tab is a legitimate separator.
        0x09 => (Some(Key::Char('\t')), 1),
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
        if bytes[1] < 0x80 {
            return (Some(Key::Ignored), 2);
        }
        return (Some(Key::Ignored), 1);
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
                b'C' => Key::Right,
                b'D' => Key::Left,
                b'H' => Key::Home,
                b'F' => Key::End,
                b'~' => match parameter {
                    b"1" | b"7" => Key::Home,
                    b"4" | b"8" => Key::End,
                    b"3" => Key::Delete,
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
}

impl EditorState {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn text(&self) -> String {
        self.chars.iter().collect()
    }

    /// Replace the buffer (used by tests and by a caller restoring a line).
    pub fn set_text(&mut self, text: &str) {
        self.chars = text.chars().collect();
        self.cursor = self.chars.len();
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
            Key::Enter => {
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
                self.chars.clear();
                self.cursor = 0;
                self.history_index = None;
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

/// Render the prompt line for a raw-mode terminal: carriage return, erase the
/// old line, print prompt + text, then move the cursor back under the caret.
pub fn render(prompt: &str, state: &EditorState) -> String {
    let text = state.text();
    let tail: usize = state.chars[state.cursor..]
        .iter()
        .copied()
        .map(cell_width)
        .sum();
    let mut out = String::with_capacity(prompt.len() + text.len() + 16);
    out.push('\r');
    out.push_str("\x1b[K");
    out.push_str(prompt);
    out.push_str(&text);
    if tail > 0 {
        out.push_str(&format!("\x1b[{tail}D"));
    }
    out
}

/// The prompt the interactive session draws.
pub const PROMPT: &str = "agent> ";

/// The shared prompt, written by the reader thread and redrawn by the caller.
pub struct Editor {
    prompt: String,
    state: Mutex<EditorState>,
    enabled: bool,
    out: Mutex<io::Stdout>,
}

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
        let _ = write!(out, "{}", render(&self.prompt, &state));
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
            }
            if self.enabled {
                let mut out = self.out.lock().unwrap_or_else(|p| p.into_inner());
                if !submitted.is_empty() {
                    let _ = write!(out, "{}", self.prompt);
                } else {
                    let _ = write!(out, "{}", render(&self.prompt, &state));
                }
                let _ = out.flush();
            }
        }
        submitted
    }
}

/// Put the terminal into cbreak mode for the lifetime of the guard, restoring the
/// previous settings on drop (including on panic).
///
/// `-icanon -echo` (not `raw`): the CLI reads one key at a time and echoes the
/// line itself, while the terminal keeps `ISIG` — so Ctrl+C still raises SIGINT
/// and the session's existing interrupt handling keeps working.
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
        // The two wide characters right of the caret take four cells.
        let rendered = render("agent> ", &state);
        assert_eq!(rendered, "\r\x1b[Kagent> ab检查\x1b[4D");
        state.apply(Key::End);
        assert_eq!(render("agent> ", &state), "\r\x1b[Kagent> ab检查");
    }

    #[test]
    fn a_pasted_line_with_escape_noise_keeps_its_text() {
        // The bytes the user's terminal sends when they paste a line and press
        // arrow keys inside it: the words must survive as text and the keys must
        // move the caret instead of being inserted.
        let editor = Editor::new("agent> ", false);
        let lines = editor.feed(b"one\x00two\x1b[D\x1b[DX\r");
        assert_eq!(lines, vec!["one tXwo".to_string()]);
    }
}
