# Linking Browser4 viewport coordinates to the Windows desktop pointer

Research record for `browser4-os-input-prototype`. Everything below is either read from this
repository (`file:line` given) or from the Win32 / JDK documentation; where an item was
reasoned out rather than observed, it is marked **[inferred]**.

---

## 1. Why Browser4 cannot click outside the page

Browser4 defines exactly three coordinate spaces, all page-local
(`browser4-core/browser4-common/src/main/kotlin/ai/platon/browser4/common/model/Box.kt:12`):

| `BoxSpace` | Origin | Produced by |
| --- | --- | --- |
| `document` | document top-left, unaffected by scrolling | `snapshot --boxes`, annotated HTML `vi` boxes, screenshot clips |
| `viewport` | main-frame layout viewport top-left | `getBoundingClientRect(...)`, `mouseMove(x, y)` |
| `body` | `<body>` box | page-relative measurements |

The input API says so explicitly
(`browser4-core/browser4-resources/src/main/resources/code-mirror/WebDriver.kt.txt`, `mouseMove`
KDoc): the coordinates are the main-frame **viewport's** top-left corner in CSS px, **not**
desktop/screen coordinates, and the method only injects page events through CDP — it never
moves the OS cursor, and the pointer cannot leave the page. A coordinate beyond the viewport is
clamped to the page edge and produces a `mouseleave`, not a desktop event.

Consequences that shaped this prototype:

* There is no `space=screen` and no OS-input tool anywhere in `browser4-plugins/`.
* A cross-application click must therefore be **provided by a plugin** that (a) invents the
  missing mapping and (b) calls an OS input API itself.
* The mapping cannot be derived from page data alone: nothing in the page knows where its
  viewport sits on the monitor. Window geometry must come from the browser
  (CDP `Browser.getWindowForTarget`) and the chrome thickness must be measured/calibrated.

## 2. The derivation, step by step

### 2.1 Window geometry (device-independent pixels)

`Browser.getWindowForTarget` returns `{windowId, bounds:{left, top, width, height}}` in **DIP**
(device-independent pixels) — the same units the Windows "display scale" applies to. Already
wrapped in this repo as `WindowBounds` (`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/window/SessionWindowProbe.kt:53`).
`WindowFrame` multiplies by `dpiScale`, taken from the window's monitor:

```
physicalLeft   = left   * dpiScale
physicalTop    = top    * dpiScale
physicalWidth  = width  * dpiScale      (includes the border, the title bar, everything)
physicalHeight = height * dpiScale
```

For a monitor with the Windows scale at 150 %, `dpiScale = 1.5`. Note that DIP→physical is a
plain multiply **only** because Chrome reports the bounds per-monitor; mixing a
primary-monitor scale with a window on a 100 % monitor is the classic 1-off bug.

### 2.2 Viewport metrics (CSS px)

One evaluation in the page:

```js
({ innerWidth, innerHeight, outerWidth, outerHeight, devicePixelRatio,
   visualOffsetX: visualViewport?.offsetLeft ?? 0,
   visualOffsetY: visualViewport?.offsetTop ?? 0 })
```

`devicePixelRatio` is the **only** scalar needed to go CSS → device px: Chrome folds the OS
display scale *and* the page zoom into it, so `dpr = 1.5` covers both "150 % display" and "100 %
display at 150 % page zoom". That is why the prototype never uses `outerWidth / innerWidth` —
that ratio also contains the chrome borders and the scrollbar width.

```
viewportWidthDevice  = innerWidth  * dpr
viewportHeightDevice = innerHeight * dpr
```

### 2.3 Chrome insets (device px)

The gap between the window frame edge and the page viewport: title bar, tab strip, bookmarks
bar, and the in-window borders. **No API reports it.** Three ways, in increasing accuracy:

1. `ChromeInsets.EMPTY` — only correct for a frameless window (`--app`, kiosk mode).
2. `ChromeInsets.derived(frame, metrics, assumedBottomInset)` — assumes the horizontal chrome is
   centred and the vertical chrome sits on top:
   ```
   left  = (physicalWidth  - viewportWidthDevice) / 2
   top   = physicalHeight - viewportHeightDevice - assumedBottomInset
   ```
   Good to a few pixels for a default Chrome/Edge window; the `assumedBottomInset` exists
   because the status bar / download shelf occupies the bottom, which genuinely cannot be
   separated from the top without more information.
3. `ViewportScreenMapper.calibrate(frame, metrics, referenceCss, referenceScreen)` — exact,
   from **one** point whose CSS and physical coordinates are both known (a previous click that
   landed correctly, a template match on a screenshot, a title-bar hit test).

`ViewportScreenMapper.isConsistent` should be checked before trusting any of them: if the derived
viewport is larger than the window frame, the probe data is wrong (wrong monitor scale, a
headless window, or a metrics/geometry mismatch) and the click will be off.

### 2.4 The map

```
deviceScale   = metrics.devicePixelRatio
viewportOrigin = (frame.physicalLeft + insets.left  + visualOffsetX * deviceScale,
                  frame.physicalTop  + insets.top   + visualOffsetY * deviceScale)
screen(point) = viewportOrigin + (cssX, cssY) * deviceScale
```

`visualViewport.offset*` covers pinch-zoom and the mobile-emulation viewport; for a normal
desktop page it is `(0, 0)`. The inverse (`screenToViewport`) is what you need when a point was
obtained from a *screenshot* rather than from the DOM.

### 2.5 The AWT step

`java.awt.Robot` does not take physical pixels: on Windows the JDK declares per-monitor DPI
awareness and reports/scales AWT user space by the monitor transform, so

```
awt = device / awtScale
```

with `awtScale` read from `GraphicsEnvironment.getDefaultScreenDevice().defaultConfiguration
.defaultTransform.scaleX` (`AwtScreens.primaryScale()`). Two failure modes, both real:

* process **not** DPI aware → Windows virtualises everything, `scaleX == 1.0` and the reported
  virtual screen is smaller than reality; clicks land at `device * dpiScale` **[inferred from
  documented Win32 DPI virtualisation]**;
* mixed-DPI multi-monitor → AWT user space is anchored at the primary monitor, so the transform
  of *that* monitor is not the transform of the window's monitor. `WindowsSendInputDriver`
  avoids the whole issue by speaking physical pixels directly.

## 3. `java.awt.Robot` vs Win32 `SendInput`

| | `Robot` | `SendInput` (FFM) |
| --- | --- | --- |
| API | `Robot.mouseMove(x, y)`, `mousePress/mouseRelease`, `keyPress` | `SendInput` with `MOUSEEVENTF_MOVE \| MOUSEEVENTF_ABSOLUTE \| MOUSEEVENTF_VIRTUALDESK`, `MOUSEEVENTF_LEFTDOWN/UP` |
| Unit | AWT user space | physical px `0..65535` per virtual desktop |
| Java binding | built in | `java.lang.foreign` (`Linker`, `SymbolLookup.libraryLookup("user32", arena)`, `MemorySegment` over the 40-byte `INPUT` structure). **No JNA needed** — verified: the module compiles with JDK 25 and only `kotlin-stdlib`. |
| Precision | integer pixels after a divide | integer pixels after the Win32 normalisation `(x - vLeft) * 65535 / (vWidth - 1)` |
| Key/text input | `KeyEvent` codes only, US layout assumed | `KEYBDINPUT` can send Unicode (`KEYEVENTF_UNICODE`) — better for non-ASCII text |
| Preconditions | interactive desktop, accessibility-ish rights | interactive desktop, same-integrity target |
| Blocked by | UIPI, secure desktop, locked session | UIPI, secure desktop, locked session |
| Windows-only? | no (X11 on Linux; macOS needs Accessibility grant) | yes, `user32.dll` |
| Risk level | medium (movement is visible and cancellable) | high (indistinguishable from a real device, bypasses some synthetic-input checks) |

Normalisation is the only non-obvious part of the `SendInput` path and it is unit-tested on any
OS (`SendInputCoordinates`):

```
dx = round((x - virtualLeft) * 65535 / (virtualWidth - 1))
```

with `virtualLeft/Width` from `GetSystemMetrics(SM_XVIRTUALSCREEN / SM_CXVIRTUALSCREEN)` so that
secondary monitors left/above the primary (negative coordinates) work.

**Recommendation for the prototype:** `Robot` as the default backend (portable, no FFM, easier
to review), `SendInput` as the opt-in precise backend for DPI-sensitive or text-heavy scenarios,
and one `PointerDriver` interface so a plugin never knows which is active. That is exactly what
`input/PointerDriver.kt` does.

## 4. What a production plugin must add

* **Opt-in per session.** OS input is a capability, not a tool: a page must never be able to
  invoke it implicitly through a DOM event. Gate it behind an explicit session flag and a tool
  prefix (`os.*`), and log every call with its device coordinate.
* **Bounds check.** `PointerDriver.virtualScreen.contains(point)` before every event; a
  mis-scaled coordinate must fail loudly, not click at `(0, 0)`.
* **Freshness.** Re-read `WindowFrame` + `ViewportMetrics` per click (two cheap calls); a cached
  mapper silently rots when the window is dragged or a devtools panel opens.
* **Calibration loop.** After an `os.click`, verify the effect in-page (focus, `document.activeElement`,
  a screenshot diff). If it landed wrong, re-calibrate the insets from the expected vs. actual
  point instead of retrying the same click.
* **Panic stop.** `Robot` movement can be hijacked; provide `os.release()` (all buttons up) and
  a watchdog that releases if the session dies mid-click.
* **Platform matrix.** Windows first; Linux X11 next (`Robot`); macOS only with a documented
  Accessibility requirement; Wayland and headless **not supported** (fail fast, do not silently
  no-op).
* **No secure-desktop support by design** — document it, do not attempt elevation tricks.

## 5. Open questions / known unknowns

1. **Chrome's own frame thickness** on Windows 11 (rounded corners, DWM shadow) is reported
   differently by `Browser.getWindowForTarget` depending on whether the window is maximised
   **[inferred]** — worth a runtime probe before `derived()` is trusted without calibration.
2. **`visualViewport.offset*` under device emulation**: `devicePixelRatio` then describes the
   emulated device, so the mapping is only valid if the emulated viewport is the real one. Fail
   the mapping when `Browser4`'s device-emulation flag is on rather than guessing.
3. **`SendInput` coordinate rounding** at the 65535 normalisation can be up to one pixel off at
   large virtual-desktop widths; for pixel-critical targets use `SetCursorPos` + `mouse_event`,
   or re-check with `GetCursorPos`.
4. **Rail/integrity**: an automated click into an app whose window sits behind a UAC-elevated
   child is silently swallowed; detecting that requires comparing `GetForegroundWindow` before
   and after.
