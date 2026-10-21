# Browser4 Captcha Plugin

Detect and solve CAPTCHAs (reCAPTCHA, hCaptcha, Cloudflare Turnstile, image) inside the real browser.

## What it does

This plugin registers the `captcha` agent-tool domain, a browse event handler that
auto-solves on `onDocumentSteady`, and a page-category sniffer:

| Tool | Description |
|------|-------------|
| `captcha.detect(waitMillis?)` | Detect the widget on the current page. `waitMillis` polls for lazily rendered widgets (max 30000 ms). |
| `captcha.solve(type, siteKey, pageUrl?)` | Submit the CAPTCHA to the configured solving service and return the token. |
| `captcha.solveImage(base64Image, caseSensitive?)` | Solve a classic image CAPTCHA. |
| `captcha.getBalance()` | Report the configured solving provider(s) and their balance. |

Detection runs in the page via `driver.evaluateValue()`, one JavaScript probe per
detector (reCAPTCHA → hCaptcha → Turnstile → image); the first hit wins.

## Tool result payloads

**Every tool returns a JSON object built in `CaptchaToolPayload`, with documented
keys always present and explicit `null`s/`[]`/`{}` for "not applicable" — because
the shared tool-result renderer serializes with a `NON_EMPTY` mapper
(`pulsarObjectMapper()`), which silently drops `null`, empty-string, empty-collection
and empty-object map values; do not "simplify" `jsonNull()`/`jsonLiteral()` back to
plain `null`/`emptyList()` — those keys would disappear from the client's JSON.**

`jsonNull()` is a Jackson `NullNode` and `jsonLiteral()` a `RawValue`, the two value
forms that survive that mapper; `CaptchaToolPayload`'s KDoc explains the mechanism,
and `CaptchaDetectToolResultTest` / `CaptchaGetBalanceToolResultTest` /
`CaptchaSolveToolResultTest` pin the wire form through the real renderer. Each tool
also declares an `outputSchema` that those tests validate its payload against.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `captcha.auto.solve.enabled` | `true` | Enable the auto-solve browse handler |
| `captcha.detection.enabled` | `true` | Enable detection |
| `captcha.detect.poll.interval.ms` | `250` | Delay between the attempts of a waited `detect` call |
| `captcha.service.provider` | `CAPSOLVER` | Primary solving provider (`CAPSOLVER`, `TWO_CAPTCHA`, `ANTI_CAPTCHA`) |
| `captcha.capsolver.api.key` | — | CapSolver API key |
| `captcha.twocaptcha.api.key` | — | 2Captcha API key |
| `captcha.anticaptcha.api.key` | — | Anti-Captcha API key |
| `captcha.solve.timeout.seconds` | `120` | Maximum time to wait for a solution |
| `captcha.solve.max.retries` | `3` | Retry attempts per browse event |
| `captcha.auto.solve.types` | `RECAPTCHA_V2,HCAPTCHA,TURNSTILE` | Types to solve automatically |

## Build & test

```bash
mvn -pl browser4-plugins/browser4-captcha test     # unit tests, offline
mvn -pl browser4-plugins/browser4-captcha package -DskipTests
```
