# device-and-network-emulation

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

This scenario covers capability emulation via `set` (`set geo`, `set offline`, `set headers`,
`set media`, `set device`) plus `delete-data` and `resize`.

Fixtures:
- `http://localhost:18080/assets/geolocation.html` — resolves `window.geolocationPromise` with `{latitude, longitude}` from `navigator.geolocation`.
- `http://localhost:18080/assets/highdpi.html` — swaps its background image behind the `(-webkit-min-device-pixel-ratio: 1.5)` media query.
- `http://localhost:18080/assets/mobile.html` — minimal page, useful for reading `innerWidth` / `devicePixelRatio`.
- `http://localhost:18080/b4/network-fixture.html` — issues two fetches, so request headers can be inspected afterwards.

## Steps

### 1. Geolocation

1. Open a fresh session and navigate to the geolocation fixture; read the promise before emulation and record what it returns.
2. Emulate a position with `set geo`, reload the page and read the promise again.

### 2. Offline and headers

3. Enable offline emulation, then try to navigate to another MockSite page.
4. Disable offline emulation and navigate again.
5. Set a custom request header, navigate to the network fixture, list the tracked requests and read one request in detail.

### 3. Media and device

6. Emulate a dark colour scheme on `assets/mobile.html` and read `matchMedia('(prefers-color-scheme: dark)').matches`.
7. Emulate a mobile device (width/height/dpr/mobile) and read `innerWidth`, `innerHeight`, `devicePixelRatio` and `navigator.userAgent`.
8. Navigate to the high-DPI fixture with a device-scale-factor of 2 and note which background image the page selects.
9. Compare the emulated viewport with `resize`.

### 4. Cleanup of session data

10. Set a cookie and a `localStorage` entry, then delete the session data and reload the page.
11. Close the session.

## Acceptance Criteria

1. **AC1 — geo emulation:** after `set geo --lat <lat> --lon <lon> --accuracy <m>`, a reload of the geolocation fixture resolves `window.geolocationPromise` to the emulated coordinates (allow for floating-point rounding). If the promise never settles because the geolocation permission is not granted, record that as a finding together with whether the CLI or skill documents how to grant it.
2. **AC2 — offline:** `set offline on` makes a subsequent `goto` to another MockSite URL fail with a network error (non-zero exit, message naming the failure); `set offline off` restores navigation.
3. **AC3 — extra headers:** after `set headers --json '{"X-B4-Test":"1"}'`, navigating to the network fixture and running `network requests` + `network request <id>` shows `X-B4-Test` in the request headers (record the exact field name if it differs).
4. **AC4 — colour scheme:** `set media --color-scheme dark` makes `matchMedia('(prefers-color-scheme: dark)').matches` report `true`; switching to `light` flips it to `false`.
5. **AC5 — device metrics:** `set device --width 390 --height 844 --dpr 2 --mobile` makes `window.innerWidth` / `innerHeight` / `devicePixelRatio` report the emulated values.
6. **AC6 — dpr is observable to the page:** with `--dpr 2` the high-DPI fixture's `(-webkit-min-device-pixel-ratio: 1.5)` media query matches (verify via `matchMedia`), i.e. the emulation reaches CSS, not only JS.
7. **AC7 — resize vs set device:** `resize 1280 900` changes the viewport dimensions, and the difference between `resize` and `set device` is clear from the help output (record the wording).
8. **AC8 — delete-data:** `delete-data` reports what it deleted and survives as a usable session afterwards; after a reload the cookie is gone and `localStorage.length` is `0`. If session data survives, record it as a finding with the exact commands and outputs.
9. **AC9 — invalid mode is rejected:** `set bogus` exits non-zero with a message listing the valid modes (`geo`, `offline`, `headers`, `media`, `device`).

## What to evaluate while doing this

- Is the `set <mode>` family discoverable from the top-level help, or does it only exist as a sub-usage of a generic `set` command?
- Does the help state that emulation is per-session/per-tab and whether it survives navigation?
- Is it clear how to undo an emulation (`set offline off`, `set media --color-scheme no-preference`, …)?
