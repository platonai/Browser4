---
title: "Emulation (`set`) Reference"
description: "Reference for the set command: emulate geolocation, offline mode, extra request headers, color scheme and device metrics on the current tab, and undo each override."
tier: procedure
---

# Emulation (`set`) Reference

`browser4-cli set …` issues Chrome DevTools Protocol emulation commands against the **current tab of
the session** and prints what it applied (`✓ Geolocation emulated: latitude=31.23, …`). No read-back
command exists — verify the effect from the page with `eval`.

## Quick Start

```bash
browser4-cli set geo --lat 48.8584 --lon 2.2945 --accuracy 10        # geolocation
browser4-cli set offline on                                          # offline network
browser4-cli set headers --json '{"X-Api-Key":"abc"}'                 # extra request headers
browser4-cli set media --color-scheme dark                           # prefers-color-scheme
browser4-cli set device --width 390 --height 844 --dpr 2 --mobile    # device metrics
```

## When to Use

Use `set` when a page must behave as if it were somewhere else, offline, behind different headers,
in another color scheme, or on another device — bot-detection retries, mobile-layout checks,
service-worker/offline states, and A/B or locale headers. For viewport size alone, `resize` is
enough; `set device` is what also changes the reported metrics (`devicePixelRatio`, mobile flag).

## How It Works

`set` applies the override to the **current tab**, prints the applied values, and leaves them active
until you change them, clear them, or the session/tab goes away. Overrides live on the tab's CDP
session, so a new tab/session starts un-emulated.

| Mode | Command (required flags in bold) | Survives `goto` / `reload` | How to undo |
|------|----------------------------------|-----------------------------|-------------|
| Geolocation | `set geo --lat <d> --lon <d> [--accuracy <m>]` | yes | `cdp Emulation.clearGeolocationOverride` |
| Offline | `set offline on\|off` | yes (`off` = normal network) | `set offline off` |
| Extra headers | `set headers --json '{"X-Api-Key":"abc"}'` | yes | `set headers --json '{}'` |
| Media | `set media --color-scheme light\|dark\|no-preference` | yes | `--color-scheme no-preference` |
| Device metrics | `set device --width <px> --height <px> [--dpr <n>] [--mobile]` | yes — re-applied after `goto`; `resize` keeps the scale factor | `cdp Emulation.clearDeviceMetricsOverride` |

## Patterns

- **Device metrics survive navigation and resize.** `goto` re-applies the active emulation after the
  backend normalises its viewport, and `resize` carries `deviceScaleFactor`/`mobile` forward, so
  `set device --width 390 --height 844 --dpr 2 --mobile` stands until you clear it or close the tab.
  Verify from the page: `eval "innerWidth + 'x' + innerHeight + ' dpr ' + devicePixelRatio"`.
- **Geolocation needs the browser's geolocation service enabled.** `set geo` grants the permission for
  the page origin (without it `getCurrentPosition()` never settles), but a browser launched with
  `--disable-geolocation` (the default, so unattended browsers never block on a prompt) has the service
  off, and a launch flag cannot be undone at run time — relaunch with
  `browser.launch.disable.geolocation=false` if the position still never resolves.
- **Mobile is metrics-only.** `set device --mobile` sets the metrics override's mobile flag
  (`maxTouchPoints` 10) but leaves `navigator.userAgent` at the desktop UA, so a UA-sniffing site sees
  a desktop browser on a phone-sized viewport.

## Flags

| Flag | Mode | Description |
|---|---|---|
| `--lat`, `--lon` | geo | Latitude/longitude in degrees; both required for `geo` |
| `--accuracy` | geo | Position accuracy in metres (default: 1) |
| `--json <headers>` | headers | Headers as a JSON object string; required for `headers` |
| `--color-scheme` | media | `light`, `dark`, or `no-preference` (resets it) |
| `--width`, `--height` | device | Viewport size in px; both required for `device` |
| `--dpr` | device | Device scale factor (default: 1) |
| `--mobile` | device | Enable mobile (touch) emulation — touch metrics only, the UA is unchanged |

## Errors & Recovery

Inputs are validated before anything is applied: `--lat`/`--lon`, `--json` and `--width`/`--height`
are required for their mode, a mode's own options are rejected on another mode (`set geo --dpr 2`),
an unknown mode names the closest valid one, and malformed `--json` headers are refused **without**
clearing the active headers. `cdp Emulation.clear*Override` (or a new tab) is the way back when an
override no longer applies.

## See also

- [browser4-cli SKILL.md](../SKILL.md) — the decision layer: core loop, command map, reference map
- [browser-modes.md](browser-modes.md) — session × display × browser source, and headed escalation
- [network.md](network.md) — request inspection, HAR recording and request routing
