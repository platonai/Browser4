//! Bot-stealth scenarios — real web pages and real bot-detection services.
//!
//! These are **opt-in** (`--enable-stealth-scenario`) because they need the public
//! internet, and one of them needs it for minutes at a time.  They cover the two
//! findings that unit tests cannot reach:
//!
//! 1. [`test_e2e_stealth_navigator_invariants`] — the launch-time user agent and the
//!    navigator surface a bot detector reads, measured on a real https page, plus the
//!    page's **main world** natives (the driver evaluates in an isolated world, which
//!    can disagree with what the page sees).
//! 2. [`test_e2e_stealth_detector_sweep`] — the five services from the bot-stealth
//!    issue reports.  A service that cannot be reached is reported and skipped; a
//!    service that loads is asserted on the signals that are about *us* (the user-agent
//!    and headless tokens), never on a vendor's aggregate opinion alone.
//!
//! Diagnostic counterparts live in `docs-dev/copilot/bot-stealth-probes/` (including a
//! plain-Chrome baseline over raw CDP, which is what attributes a finding to Browser4
//! rather than to the host or to the detector).

use crate::*;
use std::thread;
use std::time::{Duration, Instant};

/// Real https page used for the navigator invariants.  No CSP, so the page-world probe
/// below can run; override for another origin with `BROWSER4_E2E_STEALTH_URL`.
fn stealth_page_url() -> String {
    std::env::var("BROWSER4_E2E_STEALTH_URL")
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "https://example.com".to_string())
}

/// Every reading the navigator invariants need, in one round trip.
///
/// `navigator.deviceMemory` is `[SecureContext]`, so the page must be https for it to
/// be present at all.
const NAVIGATOR_PROBE_JS: &str = r#"JSON.stringify({
  ua: navigator.userAgent,
  brands: navigator.userAgentData
    ? navigator.userAgentData.brands.map(function (b) { return b.brand + ' ' + b.version; }).join(', ')
    : '',
  webdriver: String(navigator.webdriver),
  deviceMemory: navigator.deviceMemory,
  maxTouchPoints: navigator.maxTouchPoints,
  touchStart: ('ontouchstart' in window),
  coarse: matchMedia('(pointer: coarse)').matches,
  fine: matchMedia('(pointer: fine)').matches,
  anyCoarse: matchMedia('(any-pointer: coarse)').matches
})"#;

/// Read the **page's main world**, not the world `eval` runs in.
///
/// An inline `<script>` executes in the page world, and the two worlds do not share JS
/// globals (an isolated world has its own `window`), so the reading is handed back
/// through a DOM attribute — the document is shared.  What this checks is that the
/// members a detector hashes still look like Chrome's own: distinct sources that each
/// stringify to `[native code]`.  A page-world patch that wraps many natives with one
/// shared function collapses them into a single source, which is exactly the "Pattern"
/// creepjs reports as a stealth extension.
const PAGE_WORLD_PROBE_JS: &str = r#"(function () {
  var inline = `(function () {
    function source(object, property) {
      try {
        var descriptor = Object.getOwnPropertyDescriptor(object, property);
        var fn = descriptor && (descriptor.get || descriptor.value);
        return fn ? String(Function.prototype.toString.call(fn)).replace(/\s+/g, ' ') : 'absent';
      } catch (error) { return 'error'; }
    }
    var samples = {
      fts: String(Function.prototype.toString.call(Function.prototype.toString)),
      createElement: source(Document.prototype, 'createElement'),
      contentWindow: source(HTMLIFrameElement.prototype, 'contentWindow'),
      hardwareConcurrency: source(Navigator.prototype, 'hardwareConcurrency'),
      availWidth: source(Screen.prototype, 'availWidth'),
      getImageData: source(CanvasRenderingContext2D.prototype, 'getImageData'),
      getByteFrequencyData: source(AnalyserNode.prototype, 'getByteFrequencyData')
    };
    var distinct = {};
    Object.keys(samples).forEach(function (key) { distinct[samples[key]] = (distinct[samples[key]] || 0) + 1; });
    var sources = Object.keys(distinct);
    // NOTE: this script is built inside a template literal, and a template literal
    // processes escape sequences (\\{ -> {, \\s -> s). The backslashes below are
    // therefore doubled so the regexes reach this realm intact; the sanity flag makes
    // a future escaping mistake fail loudly instead of silently reporting 'not native'.
    var nativePattern = /\\{\\s*\\[native code\\]\\s*\\}/;
    var members = {};
    Object.keys(samples).forEach(function (key) {
      members[key] = nativePattern.test(samples[key]) ? 'native' : samples[key].slice(0, 120);
    });
    document.documentElement.setAttribute('data-b4-realm', JSON.stringify({
      distinct: sources.length,
      shared: sources.length === 1 ? sources[0] : null,
      allNative: sources.every(function (s) { return nativePattern.test(s); }),
      regexSanity: nativePattern.test('function get contentWindow() { [native code] }'),
      members: members,
      ua: navigator.userAgent
    }));
  })();`;
  var script = document.createElement('script');
  script.textContent = inline;
  (document.head || document.documentElement).appendChild(script);
  script.remove();
  return document.documentElement.getAttribute('data-b4-realm') || 'unavailable';
})()"#;

fn stealth_probe(ctx: &mut E2ECtx, expression: &str) -> serde_json::Value {
    let result = run_command_allowing_failure(ctx, &["eval", expression]);
    let text = strip_snapshot_output(&result.stdout);
    serde_json::from_str(text.trim()).unwrap_or_else(|error| {
        panic!(
            "Stealth probe did not return JSON ({error}).\nexpression: {expression}\nstdout:>>>\n{}\n<<<\nstderr:>>>\n{}\n<<<",
            result.stdout, result.stderr
        )
    })
}

/// Re-evaluate `expression` until `accept` holds, returning the settled reading.
///
/// Third-party pages compute their verdict asynchronously and at wildly different
/// speeds (one of them needs ~90 s and, when its own scripts time out, renders nothing
/// at all), so the scenarios poll for the observable state instead of sleeping for a
/// fixed guess.
///
/// `None` means the service never produced a verdict within `timeout`: the caller
/// **skips** that service.  That distinction is the point — "the service did not answer"
/// must not be reported as a stealth regression, while "the service answered, and the
/// answer is bad" must fail.
fn poll_probe(
    ctx: &mut E2ECtx,
    label: &str,
    expression: &str,
    timeout: Duration,
    accept: impl Fn(&serde_json::Value) -> bool,
) -> Option<serde_json::Value> {
    let deadline = Instant::now() + timeout;
    loop {
        let reading = stealth_probe(ctx, expression);
        if accept(&reading) {
            return Some(reading);
        }
        if Instant::now() >= deadline {
            println!(
                "⚠  {label}: no verdict within {}s — treating as unreachable for this run (last reading: {reading})",
                timeout.as_secs()
            );
            return None;
        }
        thread::sleep(Duration::from_secs(2));
    }
}

/// Navigate to a real page, returning `false` (with a printed notice) when it cannot be
/// reached.  Stealth scenarios depend on the public internet; an unreachable host is a
/// skip, not a regression.
fn goto_or_skip(ctx: &mut E2ECtx, url: &str) -> bool {
    let result = run_command_allowing_failure(ctx, &["goto", url]);
    if result.exit_code != 0 {
        println!(
            "⚠  skipping {url}: navigation failed (exit={}) — this scenario needs network access\n{}",
            result.exit_code,
            result.stderr.trim()
        );
        return false;
    }
    let _ = run_command_allowing_failure(ctx, &["wait", "--load", "networkidle"]);
    true
}

pub(super) fn test_e2e_stealth_navigator_invariants(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let url = stealth_page_url();

    let open_result = run_command_allowing_failure(
        ctx,
        &["open", "--headless", &url, OPEN_PROFILE_MODE_ARG],
    );
    assert_eq!(
        open_result.exit_code, 0,
        "Could not open {url} (exit={}) — this scenario needs network access.\nstdout:>>>\n{}\n<<<\nstderr:>>>\n{}\n<<<",
        open_result.exit_code, open_result.stdout, open_result.stderr
    );

    let navigator = stealth_probe(ctx, NAVIGATOR_PROBE_JS);
    println!("navigator readings: {navigator}");

    // --- The user agent must not advertise the headless token -------------------
    let ua = navigator["ua"].as_str().unwrap_or_default();
    assert!(!ua.is_empty(), "navigator.userAgent was empty");
    assert!(
        !ua.to_ascii_lowercase().contains("headless"),
        "the user agent still advertises the headless token: {ua}"
    );
    assert!(
        ua.contains("Chrome/"),
        "expected a Chrome product token in the user agent: {ua}"
    );

    // --- The client hints must agree with it ------------------------------------
    let brands = navigator["brands"].as_str().unwrap_or_default();
    if !brands.is_empty() {
        assert!(
            !brands.to_ascii_lowercase().contains("headless"),
            "the client hints advertise the headless token: {brands}"
        );
        assert!(
            brands.contains("Google Chrome"),
            "the client hints do not name the brand the user agent claims: {brands}"
        );
    }

    // --- Automation flags ------------------------------------------------------
    // `navigator.webdriver` must not be `true`.  (`false` and `undefined` are both
    // accepted: current Chrome keeps a `webdriver` accessor on `Navigator.prototype`
    // in a plain page, so the value — not the presence — is the signal.)
    let webdriver = navigator["webdriver"].as_str().unwrap_or("");
    assert_ne!(webdriver, "true", "navigator.webdriver is true: the page can see the automation");

    // --- Values that come from Chrome and the host, not from us -----------------
    if let Some(device_memory) = navigator["deviceMemory"].as_f64() {
        let exponent = device_memory.log2();
        assert!(
            (exponent - exponent.round()).abs() < 1e-9,
            "navigator.deviceMemory must be a power of two (Chrome rounds the host RAM down), got {device_memory}"
        );
    }
    let max_touch_points = navigator["maxTouchPoints"].as_i64().unwrap_or(0);
    let any_coarse = navigator["anyCoarse"].as_bool().unwrap_or(false);
    let touch_start = navigator["touchStart"].as_bool().unwrap_or(false);
    let coarse = navigator["coarse"].as_bool().unwrap_or(false);
    if max_touch_points > 0 {
        assert!(
            any_coarse || touch_start,
            "maxTouchPoints={max_touch_points} without a coarse pointer or touch events"
        );
    }
    if coarse {
        assert!(any_coarse, "'(pointer: coarse)' without '(any-pointer: coarse)'");
    }

    // --- The page's own world must still look like Chrome -----------------------
    let realm_text = eval_text(ctx, PAGE_WORLD_PROBE_JS);
    let realm_text = realm_text.trim().to_string();
    assert_ne!(
        realm_text, "unavailable",
        "the page-world probe did not run on {url} (inline <script> blocked?) — point \
         BROWSER4_E2E_STEALTH_URL at a page without a restrictive CSP so this check keeps working"
    );
    let realm: serde_json::Value = serde_json::from_str(&realm_text).unwrap_or_else(|error| {
        panic!("page-world probe returned non-JSON ({error}): {realm_text}")
    });
    println!("page-world natives: {realm}");
    assert_eq!(
        realm["regexSanity"].as_bool(),
        Some(true),
        "the probe's own native-code pattern does not match a known native member — the probe is \
         broken (escaping), not the page: {realm}"
    );
    assert_eq!(
        realm["allNative"].as_bool(),
        Some(true),
        "the page's main world no longer sees native members — a page-world patch is wrapping them: {realm}"
    );
    let distinct = realm["distinct"].as_i64().unwrap_or(0);
    assert!(
        distinct >= 6,
        "the page's main world collapsed {distinct} of 7 distinct native members onto a shared source \
         ({}) — that is the shape detectors label a stealth extension",
        realm["shared"]
    );

    run_command(ctx, &["close"]);
}

// ---------------------------------------------------------------------------
// Detector sweep
// ---------------------------------------------------------------------------

/// The five services from the bot-stealth issue reports.
const DETECTOR_SANNYSOFT: &str = "https://bot.sannysoft.com/";
const DETECTOR_DEVICE_INFO: &str = "https://deviceandbrowserinfo.com/are_you_a_bot";
const DETECTOR_INCOLUMITAS: &str = "https://bot.incolumitas.com/";
const DETECTOR_BROWSERSCAN: &str = "https://www.browserscan.net/bot-detection";
const DETECTOR_CREEPJS: &str = "https://abrahamjuliot.github.io/creepjs/";

const SANNYSOFT_PROBE_JS: &str = r#"JSON.stringify({
  rows: document.querySelectorAll('table tr').length,
  failedCells: document.querySelectorAll('.failed, td.failed, span.failed').length,
  anyFailedText: /failed/i.test(document.body.innerText),
  headchrUaOk: /HEADCHR_UA\s*ok/i.test(document.body.innerText),
  chrMemoryOk: /CHR_MEMORY\s*ok/i.test(document.body.innerText)
})"#;

const DEVICE_INFO_PROBE_JS: &str = r#"JSON.stringify({
  settled: document.body.innerText.indexOf('"isBot"') >= 0,
  isBot: (document.body.innerText.match(/"isBot":\s*(true|false)/) || [])[1] || null,
  saysHuman: /You are (a )?human/i.test(document.body.innerText),
  saysBot: /You are (a )?bot/i.test(document.body.innerText)
})"#;

const INCOLUMITAS_PROBE_JS: &str = r#"JSON.stringify({
  settled: document.body.innerText.indexOf('HEADCHR_UA') >= 0,
  intoliUserAgentOk: /"userAgent":\s*"OK"/.test(document.body.innerText),
  headchrUaOk: /"HEADCHR_UA":\s*"OK"/.test(document.body.innerText),
  headchrUaFail: /"HEADCHR_UA":\s*"FAIL"/.test(document.body.innerText),
  intoliUserAgentFail: /"userAgent":\s*"FAIL"/.test(document.body.innerText),
  chrMemoryFail: /"CHR_MEMORY":\s*"FAIL"/.test(document.body.innerText)
})"#;

const BROWSERSCAN_PROBE_JS: &str = r#"JSON.stringify({
  settled: /Test Results:/i.test(document.body.innerText),
  normal: /Test Results:\s*Normal/i.test(document.body.innerText),
  robot: /Test Results:\s*Robot/i.test(document.body.innerText)
})"#;

const CREEPJS_PROBE_JS: &str = r#"JSON.stringify({
  settled: /% like headless:/.test(document.body.innerText),
  headlessPercent: (document.body.innerText.match(/(\d+)% headless:/) || [])[1] || null,
  stealthPercent: (document.body.innerText.match(/(\d+)% stealth:/) || [])[1] || null,
  extension: ((document.body.innerText.match(/extension:\s*([^\s]+)/) || [])[1] || null)
})"#;

pub(super) fn test_e2e_stealth_detector_sweep(ctx: &mut E2ECtx) {
    reset_cli_artifacts(ctx);
    let mut checked = Vec::new();
    let mut skipped = Vec::new();

    // --- bot.sannysoft.com: the table's own pass/fail cells ---------------------
    // The report measured 2/27 FAIL (HEADCHR_UA, CHR_MEMORY); the user-agent fix takes
    // both out.
    if goto_or_skip(ctx, DETECTOR_SANNYSOFT) {
        if let Some(reading) = poll_probe(
            ctx,
            "bot.sannysoft.com",
            SANNYSOFT_PROBE_JS,
            Duration::from_secs(45),
            |value| value["rows"].as_i64().unwrap_or(0) > 20,
        ) {
            println!("bot.sannysoft.com: {reading}");
            assert!(
                reading["rows"].as_i64().unwrap_or(0) > 20,
                "the sannysoft result table never rendered: {reading}"
            );
            assert_eq!(
                reading["failedCells"].as_i64(),
                Some(0),
                "sannysoft reports failing checks: {reading}"
            );
            assert_eq!(
                reading["anyFailedText"].as_bool(),
                Some(false),
                "sannysoft's page text mentions a failure: {reading}"
            );
            assert_eq!(
                reading["headchrUaOk"].as_bool(),
                Some(true),
                "HEADCHR_UA is not OK — the headless token reached the page: {reading}"
            );
            assert_eq!(
                reading["chrMemoryOk"].as_bool(),
                Some(true),
                "CHR_MEMORY is not OK: {reading}"
            );
            checked.push("bot.sannysoft.com");
        } else {
            skipped.push("bot.sannysoft.com (no verdict)");
        }
    } else {
        skipped.push("bot.sannysoft.com (unreachable)");
    }

    // --- deviceandbrowserinfo.com: the verdict the user-agent alone decided -----
    // Its scripts are slow and occasionally time out on a cold load (the original
    // report measured 56.7 s for one of them), so a missing verdict is a skip.
    if goto_or_skip(ctx, DETECTOR_DEVICE_INFO) {
        if let Some(reading) = poll_probe(
            ctx,
            "deviceandbrowserinfo.com",
            DEVICE_INFO_PROBE_JS,
            Duration::from_secs(180),
            |value| {
                // Wait for the machine-readable verdict *and* the badge it renders —
                // they appear at different moments.
                value["isBot"].is_string()
                    && (value["saysHuman"].as_bool() == Some(true)
                        || value["saysBot"].as_bool() == Some(true))
            },
        ) {
            println!("deviceandbrowserinfo.com: {reading}");
            assert_eq!(
                reading["isBot"].as_str(),
                Some("false"),
                "deviceandbrowserinfo sets isBot=true — the user agent is still detectable: {reading}"
            );
            assert_eq!(
                reading["saysBot"].as_bool(),
                Some(false),
                "deviceandbrowserinfo says 'You are a bot!': {reading}"
            );
            assert_eq!(
                reading["saysHuman"].as_bool(),
                Some(true),
                "deviceandbrowserinfo does not render its 'You are human!' verdict: {reading}"
            );
            checked.push("deviceandbrowserinfo.com");
        } else {
            skipped.push("deviceandbrowserinfo.com (no verdict)");
        }
    } else {
        skipped.push("deviceandbrowserinfo.com (unreachable)");
    }

    // --- bot.incolumitas.com: the legacy intoli / fpscanner blocks --------------
    if goto_or_skip(ctx, DETECTOR_INCOLUMITAS) {
        if let Some(reading) = poll_probe(
            ctx,
            "bot.incolumitas.com",
            INCOLUMITAS_PROBE_JS,
            Duration::from_secs(60),
            |value| value["settled"].as_bool() == Some(true),
        ) {
            println!("bot.incolumitas.com: {reading}");
            assert_eq!(
                reading["intoliUserAgentOk"].as_bool(),
                Some(true),
                "intoli.userAgent is not OK: {reading}"
            );
            assert_eq!(
                reading["intoliUserAgentFail"].as_bool(),
                Some(false),
                "intoli.userAgent FAILs (the report measured exactly this): {reading}"
            );
            assert_eq!(
                reading["headchrUaOk"].as_bool(),
                Some(true),
                "fpscanner.HEADCHR_UA is not OK: {reading}"
            );
            assert_eq!(
                reading["headchrUaFail"].as_bool(),
                Some(false),
                "fpscanner.HEADCHR_UA FAILs (the report measured exactly this): {reading}"
            );
            checked.push("bot.incolumitas.com");
        } else {
            skipped.push("bot.incolumitas.com (no verdict)");
        }
    } else {
        skipped.push("bot.incolumitas.com (unreachable)");
    }

    // --- browserscan.net: the aggregate badge ----------------------------------
    // The report saw "Robot" while every named sub-check said "Normal"; the badge is
    // the vendor's opinion, so it is asserted only after the page has settled.
    if goto_or_skip(ctx, DETECTOR_BROWSERSCAN) {
        if let Some(reading) = poll_probe(
            ctx,
            "browserscan.net",
            BROWSERSCAN_PROBE_JS,
            Duration::from_secs(90),
            |value| value["settled"].as_bool() == Some(true),
        ) {
            println!("browserscan.net: {reading}");
            assert_eq!(
                reading["robot"].as_bool(),
                Some(false),
                "browserscan's verdict is 'Robot': {reading}"
            );
            assert_eq!(
                reading["normal"].as_bool(),
                Some(true),
                "browserscan's verdict is not 'Normal': {reading}"
            );
            checked.push("browserscan.net");
        } else {
            skipped.push("browserscan.net (no verdict)");
        }
    } else {
        skipped.push("browserscan.net (unreachable)");
    }

    // --- creepjs: recorded, not asserted ---------------------------------------
    // Its `headless` score is asserted (a real regression signal); its `extension`
    // label is not — see `docs-dev/copilot/bot-stealth-probes/README.md`: the members
    // creepjs names are pristine in every realm we can read, so the label is its
    // nearest matching known-extension template rather than evidence of one.
    if goto_or_skip(ctx, DETECTOR_CREEPJS) {
        if let Some(reading) = poll_probe(
            ctx,
            "creepjs",
            CREEPJS_PROBE_JS,
            Duration::from_secs(90),
            |value| value["headlessPercent"].is_string(),
        ) {
            println!("creepjs: {reading}");
            let headless = reading["headlessPercent"]
                .as_str()
                .and_then(|value| value.parse::<u32>().ok());
            assert!(
                headless.is_some_and(|percent| percent < 50),
                "creepjs' headless score regressed to {headless:?}% (plain Chrome on the same host reads 100%): {reading}"
            );
            if let Some(extension) = reading["extension"].as_str() {
                if extension != "unknown" {
                    println!(
                        "ℹ  creepjs labels this session's pattern '{}' — recorded, not asserted \
                         (see docs-dev/copilot/bot-stealth-probes/README.md)",
                        extension.chars().take(40).collect::<String>()
                    );
                }
            }
            checked.push("creepjs");
        } else {
            skipped.push("creepjs (no verdict)");
        }
    } else {
        skipped.push("creepjs (unreachable)");
    }

    println!(
        "stealth detector sweep: {} service(s) checked [{}], {} skipped [{}]",
        checked.len(),
        checked.join(", "),
        skipped.len(),
        skipped.join(", ")
    );
    assert!(
        !checked.is_empty(),
        "no detector service could be reached — this scenario needs network access, so its \
         assertions never ran (skipped: {})",
        skipped.join(", ")
    );

    run_command(ctx, &["close"]);
}
