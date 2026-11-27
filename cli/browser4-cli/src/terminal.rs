//! Unified terminal ANSI styling for the Browser4 CLI.
//!
//! Supports 16 colors (standard + bright), 256-color indexed, and true-color
//! RGB, plus common text effects (bold, dim, italic, underline, strikethrough,
//! inverse). Styles compose freely — foreground + background + any subset of
//! effects in one [`Style`] value.
//!
//! ## How to use
//!
//! ```ignore
//! use crate::terminal::{paint, Color, Style};
//!
//! // Just a foreground color
//! paint("done", Color::Green);
//!
//! // Just an effect
//! paint("important", Style::new().bold());
//!
//! // Color + effect combined
//! paint("warning", Style::new().fg(Color::Yellow).bold());
//!
//! // Predefined constants for the common cases
//! use crate::terminal::{DIM, CYAN, RED, BOLD, BOLD_GREEN};
//! paint("[00:12]", DIM);
//! paint("click", CYAN);
//! paint("failed", BOLD_RED);
//! ```
//!
//! All [`paint`] / [`paint_if`] functions emit ANSI escape sequences only when
//! the terminal is capable — stdout is a TTY, neither `--quiet` nor `--json`
//! is active, and the `NO_COLOR` environment variable is unset. See
//! [`color_enabled_for`].

use std::io::IsTerminal;

// ---------------------------------------------------------------------------
// Color
// ---------------------------------------------------------------------------

/// ANSI foreground/background colors.
///
/// Standard 16 colors are universally supported. [`Color::Indexed`] (256-color)
/// and [`Color::Rgb`] (true color) depend on the terminal; most modern ones
/// handle both. [`Color::Default`] resets the foreground or background to the
/// terminal's default without touching the other.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Color {
    Black,
    Red,
    Green,
    Yellow,
    Blue,
    Magenta,
    Cyan,
    White,
    BrightBlack,
    BrightRed,
    BrightGreen,
    BrightYellow,
    BrightBlue,
    BrightMagenta,
    BrightCyan,
    BrightWhite,
    /// 256-color mode — index 0–255.
    Indexed(u8),
    /// True-color RGB.
    Rgb(u8, u8, u8),
    /// Reset to the terminal's default foreground/background.
    Default,
}

/// Emit the ANSI SGR parameter string for `color` as a foreground (`fg=true`)
/// or background (`fg=false`) color.
///
/// Called once per color; the return is joined with `;` before the final `m`.
fn color_param(color: Color, fg: bool) -> String {
    match color {
        Color::Black   => (30 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::Red     => (31 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::Green   => (32 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::Yellow  => (33 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::Blue    => (34 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::Magenta => (35 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::Cyan    => (36 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::White   => (37 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        // Bright: 90–97 foreground, 100–107 background
        Color::BrightBlack   => (90 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::BrightRed     => (91 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::BrightGreen   => (92 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::BrightYellow  => (93 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::BrightBlue    => (94 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::BrightMagenta => (95 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::BrightCyan    => (96 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::BrightWhite   => (97 + fg as u8 * 0 + !fg as u8 * 10).to_string(),
        Color::Indexed(n) => format!("{};5;{n}", if fg { "38" } else { "48" }),
        Color::Rgb(r, g, b) => {
            format!("{};2;{r};{g};{b}", if fg { "38" } else { "48" })
        }
        Color::Default => (if fg { "39" } else { "49" }).to_string(),
    }
}

// ---------------------------------------------------------------------------
// Effects (text decorations)
// ---------------------------------------------------------------------------

/// Bitflags for ANSI text effects. Combined freely on a [`Style`].
///
/// Not every terminal honours every effect — [`Effects::Italic`],
/// [`Effects::Strikethrough`] and [`Effects::Inverse`] are the ones that most
/// often work, but this module doesn't check. If a flag doesn't render, just
/// drop it from your style.
#[derive(Clone, Copy, Default, Debug, PartialEq, Eq)]
pub struct Effects {
    bits: u16,
}

// Flag values — single-bit positions so they OR together cleanly.
const EF_BOLD:       u16 = 1 << 0;
const EF_DIM:        u16 = 1 << 1;
const EF_ITALIC:     u16 = 1 << 2;
const EF_UNDERLINE:  u16 = 1 << 3;
const EF_STRIKE:     u16 = 1 << 4;
const EF_INVERSE:    u16 = 1 << 5;
const EF_HIDDEN:     u16 = 1 << 6;

impl Effects {
    pub const BOLD:       Self = Self { bits: EF_BOLD };
    pub const DIM:        Self = Self { bits: EF_DIM };
    pub const ITALIC:     Self = Self { bits: EF_ITALIC };
    pub const UNDERLINE:  Self = Self { bits: EF_UNDERLINE };
    pub const STRIKE:     Self = Self { bits: EF_STRIKE };
    pub const INVERSE:    Self = Self { bits: EF_INVERSE };
    pub const HIDDEN:     Self = Self { bits: EF_HIDDEN };

    /// Whether this effect is active.
    pub fn has(self, flag: Effects) -> bool {
        (self.bits & flag.bits) != 0
    }

    /// Set one or more flags (logical OR).
    pub fn set(mut self, flag: Effects) -> Self {
        self.bits |= flag.bits;
        self
    }

    /// ANSI SGR code for a single flag, only when it is active.
    fn sgr_param(self, flag: Effects) -> Option<&'static str> {
        if !self.has(flag) {
            return None;
        }
        let code = match flag {
            Effects::BOLD      => "1",
            Effects::DIM       => "2",
            Effects::ITALIC    => "3",
            Effects::UNDERLINE => "4",
            Effects::STRIKE    => "9",
            Effects::INVERSE   => "7",
            Effects::HIDDEN    => "8",
            _ => return None,
        };
        Some(code)
    }

    /// All active effects joined with `;`, in ANSI order.
    fn sgr_string(self) -> String {
        let mut params = Vec::with_capacity(7);
        for flag in [
            Effects::BOLD,
            Effects::DIM,
            Effects::ITALIC,
            Effects::UNDERLINE,
            Effects::INVERSE,
            Effects::HIDDEN,
            Effects::STRIKE,
        ] {
            if let Some(p) = self.sgr_param(flag) {
                params.push(p);
            }
        }
        params.join(";")
    }
}

// ---------------------------------------------------------------------------
// Style — the composition: fg color + bg color + effects
// ---------------------------------------------------------------------------

/// A complete text style — foreground, background and any combination of
/// effects. [`Style`] values compose freely, and are [`Copy`] so they are
/// cheap to pass around.
///
/// The builder pattern is intentional — one call sets one field and returns a
/// new [`Style`]:
///
/// ```ignore
/// use crate::terminal::{Style, Color, Effects};
/// let s = Style::new()
///     .fg(Color::Yellow)
///     .bold()
///     .underline();
/// ```
///
/// [`From`] impls for [`Color`] and [`Effects`] let single-argument paint
/// calls be short: `paint("click", Color::Cyan)` or `paint("done", Effects::BOLD)`.
#[derive(Clone, Copy, Default, Debug, PartialEq, Eq)]
pub struct Style {
    pub fg: Option<Color>,
    pub bg: Option<Color>,
    pub effects: Effects,
}

impl Style {
    /// An empty style — no color, no effects. [`paint`] on the empty style is a
    /// no-op (returns the text unchanged).
    pub const fn new() -> Self {
        Self {
            fg: None,
            bg: None,
            effects: Effects { bits: 0 },
        }
    }

    pub const fn fg(mut self, color: Color) -> Self {
        self.fg = Some(color);
        self
    }

    pub const fn bg(mut self, color: Color) -> Self {
        self.bg = Some(color);
        self
    }

    pub const fn bold(mut self) -> Self {
        self.effects = Effects {
            bits: self.effects.bits | EF_BOLD,
        };
        self
    }

    pub const fn dim(mut self) -> Self {
        self.effects = Effects {
            bits: self.effects.bits | EF_DIM,
        };
        self
    }

    pub const fn italic(mut self) -> Self {
        self.effects = Effects {
            bits: self.effects.bits | EF_ITALIC,
        };
        self
    }

    pub const fn underline(mut self) -> Self {
        self.effects = Effects {
            bits: self.effects.bits | EF_UNDERLINE,
        };
        self
    }

    pub const fn strikethrough(mut self) -> Self {
        self.effects = Effects {
            bits: self.effects.bits | EF_STRIKE,
        };
        self
    }

    pub const fn inverse(mut self) -> Self {
        self.effects = Effects {
            bits: self.effects.bits | EF_INVERSE,
        };
        self
    }
}

impl From<Color> for Style {
    fn from(c: Color) -> Self {
        Style::new().fg(c)
    }
}

impl From<Effects> for Style {
    fn from(e: Effects) -> Self {
        Style {
            fg: None,
            bg: None,
            effects: e,
        }
    }
}

/// Build the SGR sequence string (everything between `\x1b[` and `m`) for
/// a style. Empty string means "no ANSI needed" — the paint functions skip
/// wrapping in that case.
fn sgr_sequence(style: Style) -> String {
    let mut parts = Vec::with_capacity(10);

    let effects = style.effects.sgr_string();
    if !effects.is_empty() {
        parts.push(effects);
    }
    if let Some(fg) = style.fg {
        parts.push(color_param(fg, true));
    }
    if let Some(bg) = style.bg {
        parts.push(color_param(bg, false));
    }

    parts.join(";")
}

// ---------------------------------------------------------------------------
// Paint functions
// ---------------------------------------------------------------------------

/// Wrap `text` in ANSI sequences for `style`, **only when** stdout is a
/// terminal and `NO_COLOR` is unset. Returns the text unchanged otherwise.
///
/// This function only probes terminal capability and the `NO_COLOR` env var.
/// CLI entry points that also need to respect `--quiet` / `--json` should use
/// [`paint_if`] with a boolean derived from [`color_enabled_for`] instead.
pub fn paint(text: &str, style: impl Into<Style>) -> String {
    let style = style.into();
    if !std::io::stdout().is_terminal() || no_color_requested() {
        return text.to_string();
    }
    paint_raw(text, style)
}

/// Like [`paint`], but the caller already knows whether ANSI should be on —
/// typically because they computed the flag once up front (e.g. in a loop).
pub fn paint_if(text: &str, style: impl Into<Style>, color_on: bool) -> String {
    let style = style.into();
    if !color_on {
        return text.to_string();
    }
    paint_raw(text, style)
}

fn paint_raw(text: &str, style: Style) -> String {
    let seq = sgr_sequence(style);
    if seq.is_empty() {
        return text.to_string();
    }
    format!("\x1b[{seq}m{text}\x1b[0m")
}

// ---------------------------------------------------------------------------
// Color gate — whether this invocation may emit ANSI at all
// ---------------------------------------------------------------------------

/// The pure predicate that decides whether ANSI is allowed. Takes the four
/// inputs explicitly so it needs no terminal probes and no environment reads
/// of its own — perfect for unit tests.
///
/// CLI entry points assemble this from: `is_terminal()`, `quiet_active()`,
/// `json_active()` and [`no_color_requested`].
pub fn color_enabled_for(is_tty: bool, quiet: bool, json: bool, no_color: bool) -> bool {
    is_tty && !quiet && !json && !no_color
}

/// Whether the environment asks to disable ANSI entirely.
///
/// Implements the [no-color.org](https://no-color.org/) convention — any
/// non-empty value of `NO_COLOR` disables ANSI even on a terminal.
pub fn no_color_requested() -> bool {
    std::env::var_os("NO_COLOR")
        .map(|value| !value.is_empty())
        .unwrap_or(false)
}

// ---------------------------------------------------------------------------
// Predefined style constants — drop-in replacements for the old ad-hoc enums
// ---------------------------------------------------------------------------

// Foreground-only styles ---------------------------------------------------
pub const BLACK:        Style = Style::new().fg(Color::Black);
pub const RED:          Style = Style::new().fg(Color::Red);
pub const GREEN:        Style = Style::new().fg(Color::Green);
pub const YELLOW:       Style = Style::new().fg(Color::Yellow);
pub const BLUE:         Style = Style::new().fg(Color::Blue);
pub const MAGENTA:      Style = Style::new().fg(Color::Magenta);
pub const CYAN:         Style = Style::new().fg(Color::Cyan);
pub const WHITE:        Style = Style::new().fg(Color::White);
pub const BRIGHT_BLACK: Style = Style::new().fg(Color::BrightBlack);
pub const BRIGHT_RED:   Style = Style::new().fg(Color::BrightRed);
pub const BRIGHT_GREEN: Style = Style::new().fg(Color::BrightGreen);
pub const BRIGHT_YELLOW: Style = Style::new().fg(Color::BrightYellow);
pub const BRIGHT_BLUE:  Style = Style::new().fg(Color::BrightBlue);
pub const BRIGHT_MAGENTA: Style = Style::new().fg(Color::BrightMagenta);
pub const BRIGHT_CYAN:  Style = Style::new().fg(Color::BrightCyan);
pub const BRIGHT_WHITE: Style = Style::new().fg(Color::BrightWhite);

// Effect-only styles (no color) -------------------------------------------
pub const BOLD:       Style = Style::new().bold();
pub const DIM:        Style = Style::new().dim();
pub const ITALIC:     Style = Style::new().italic();
pub const UNDERLINE:  Style = Style::new().underline();

// Effect + color combinations (most common) --------------------------------
pub const BOLD_RED:     Style = Style::new().fg(Color::Red).bold();
pub const BOLD_GREEN:   Style = Style::new().fg(Color::Green).bold();
pub const BOLD_YELLOW:  Style = Style::new().fg(Color::Yellow).bold();
pub const BOLD_BLUE:    Style = Style::new().fg(Color::Blue).bold();
pub const BOLD_MAGENTA: Style = Style::new().fg(Color::Magenta).bold();
pub const BOLD_CYAN:    Style = Style::new().fg(Color::Cyan).bold();
pub const BOLD_WHITE:   Style = Style::new().fg(Color::White).bold();
pub const BOLD_BRIGHT_RED:   Style = Style::new().fg(Color::BrightRed).bold();
pub const BOLD_BRIGHT_GREEN: Style = Style::new().fg(Color::BrightGreen).bold();
pub const BOLD_BRIGHT_YELLOW: Style = Style::new().fg(Color::BrightYellow).bold();

pub const DIM_GRAY:  Style = Style::new().fg(Color::BrightBlack).dim();

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paint_emits_ansi_when_tty_and_nothing_disables_it() {
        let enabled = color_enabled_for(true, false, false, false);
        let out = paint_if("click", CYAN, enabled);
        assert_eq!(out, "\x1b[36mclick\x1b[0m");
    }

    #[test]
    fn paint_strips_ansi_when_not_a_tty() {
        let enabled = color_enabled_for(false, false, false, false);
        let out = paint_if("click", CYAN, enabled);
        assert_eq!(out, "click");
    }

    #[test]
    fn paint_strips_ansi_when_quiet() {
        let enabled = color_enabled_for(true, true, false, false);
        let out = paint_if("click", CYAN, enabled);
        assert_eq!(out, "click");
    }

    #[test]
    fn paint_strips_ansi_when_json() {
        let enabled = color_enabled_for(true, false, true, false);
        let out = paint_if("click", CYAN, enabled);
        assert_eq!(out, "click");
    }

    #[test]
    fn paint_strips_ansi_when_no_color_flag() {
        let enabled = color_enabled_for(true, false, false, true);
        let out = paint_if("click", CYAN, enabled);
        assert_eq!(out, "click");
    }

    #[test]
    fn bold_yields_ansi_1() {
        let out = paint_if("title", BOLD, true);
        assert_eq!(out, "\x1b[1mtitle\x1b[0m");
    }

    #[test]
    fn dim_yields_ansi_2() {
        let out = paint_if("[00:12]", DIM, true);
        assert_eq!(out, "\x1b[2m[00:12]\x1b[0m");
    }

    #[test]
    fn bold_red_combines_two_sgr_codes() {
        let out = paint_if("failed", BOLD_RED, true);
        assert_eq!(out, "\x1b[1;31mfailed\x1b[0m");
    }

    #[test]
    fn italic_underline_yields_1_3_4() {
        let s = Style::new().italic().underline();
        let out = paint_if("link", s, true);
        assert_eq!(out, "\x1b[3;4mlink\x1b[0m");
    }

    #[test]
    fn empty_style_returns_text_unchanged() {
        let out = paint_if("plain", Style::new(), true);
        assert_eq!(out, "plain");
    }

    #[test]
    fn default_style_returns_text_unchanged() {
        let out = paint_if("plain", Style::default(), true);
        assert_eq!(out, "plain");
    }

    #[test]
    fn foreground_and_background_combine() {
        let s = Style::new().fg(Color::Black).bg(Color::Yellow);
        let out = paint_if("highlighted", s, true);
        assert_eq!(out, "\x1b[30;43mhighlighted\x1b[0m");
    }

    #[test]
    fn bright_color_uses_90_series() {
        let out = paint_if("title", BRIGHT_CYAN, true);
        assert_eq!(out, "\x1b[96mtitle\x1b[0m");
    }

    #[test]
    fn bright_bg_uses_100_series() {
        let s = Style::new().bg(Color::BrightRed);
        let out = paint_if("bg", s, true);
        assert_eq!(out, "\x1b[101mbg\x1b[0m");
    }

    #[test]
    fn indexed_256_color_emits_38_5() {
        let s = Style::new().fg(Color::Indexed(196)); // bright red
        let out = paint_if("x", s, true);
        assert_eq!(out, "\x1b[38;5;196mx\x1b[0m");
    }

    #[test]
    fn rgb_true_color_emits_38_2() {
        let s = Style::new().fg(Color::Rgb(255, 128, 0));
        let out = paint_if("amber", s, true);
        assert_eq!(out, "\x1b[38;2;255;128;0mamber\x1b[0m");
    }

    #[test]
    fn color_default_resets_without_style() {
        // Color::Default alone as foreground means "just reset foreground"
        let s = Style::new().fg(Color::Default);
        let out = paint_if("text", s, true);
        assert_eq!(out, "\x1b[39mtext\x1b[0m");
    }

    #[test]
    fn color_param_works_for_standard_colors() {
        assert_eq!(color_param(Color::Red, true), "31");
        assert_eq!(color_param(Color::Red, false), "41");
        assert_eq!(color_param(Color::BrightGreen, true), "92");
        assert_eq!(color_param(Color::BrightGreen, false), "102");
    }

    #[test]
    fn effects_build_combined_sgr_order_is_correct() {
        let mut e = Effects::BOLD;
        e = e.set(Effects::UNDERLINE);
        e = e.set(Effects::DIM);
        let params = e.sgr_string();
        // BOLD=1, DIM=2, UNDERLINE=4 — order fixed by the [for flag in [...]] loop
        assert_eq!(params, "1;2;4");
    }

    #[test]
    fn style_from_color_is_fg_only() {
        let s: Style = Color::Cyan.into();
        assert_eq!(s, Style::new().fg(Color::Cyan));
        assert_eq!(s.effects, Effects::default());
        assert!(s.bg.is_none());
    }

    #[test]
    fn style_from_effect_is_effects_only() {
        let s: Style = Effects::BOLD.into();
        assert_eq!(s.effects, Effects::BOLD);
        assert!(s.fg.is_none());
        assert!(s.bg.is_none());
    }

    #[test]
    fn paint_accepts_color_directly_via_from_impl() {
        let out = paint_if("click", Color::Cyan, true);
        assert_eq!(out, "\x1b[36mclick\x1b[0m");
    }

    #[test]
    fn paint_accepts_effect_directly_via_from_impl() {
        let out = paint_if("title", Effects::BOLD, true);
        assert_eq!(out, "\x1b[1mtitle\x1b[0m");
    }

    // --- round-trip tests: every code path for every variant -----------------

    #[test]
    fn all_eight_standard_foreground_codes() {
        for (i, c) in [
            Color::Black, Color::Red, Color::Green, Color::Yellow,
            Color::Blue, Color::Magenta, Color::Cyan, Color::White,
        ].into_iter().enumerate() {
            assert_eq!(color_param(c, true), (30 + i).to_string());
        }
    }

    #[test]
    fn all_eight_standard_background_codes() {
        for (i, c) in [
            Color::Black, Color::Red, Color::Green, Color::Yellow,
            Color::Blue, Color::Magenta, Color::Cyan, Color::White,
        ].into_iter().enumerate() {
            assert_eq!(color_param(c, false), (40 + i).to_string());
        }
    }

    #[test]
    fn all_eight_bright_foreground_codes() {
        for (i, c) in [
            Color::BrightBlack, Color::BrightRed, Color::BrightGreen,
            Color::BrightYellow, Color::BrightBlue, Color::BrightMagenta,
            Color::BrightCyan, Color::BrightWhite,
        ].into_iter().enumerate() {
            assert_eq!(color_param(c, true), (90 + i).to_string());
        }
    }

    #[test]
    fn all_eight_bright_background_codes() {
        for (i, c) in [
            Color::BrightBlack, Color::BrightRed, Color::BrightGreen,
            Color::BrightYellow, Color::BrightBlue, Color::BrightMagenta,
            Color::BrightCyan, Color::BrightWhite,
        ].into_iter().enumerate() {
            assert_eq!(color_param(c, false), (100 + i).to_string());
        }
    }
}
