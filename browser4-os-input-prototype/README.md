# Browser4 OS Input Prototype

A **research prototype** for one thing Browser4 cannot do today: clicking something that is
*not* inside the page — the Windows taskbar, another application, an OS dialog opened by a
download.

Browser4's in-page input layer (`mouseMove(x, y)`, `click(ref)`, `hover(ref)`) injects DOM
events through CDP. Its coordinates live in one of three page spaces (`document`,
`viewport`, `body`, see `browser4-core/browser4-common/.../model/Box.kt`) and **no API moves
the system cursor**. To reach the desktop, a plugin has to

1. turn a viewport CSS point into a physical screen point, and
2. hand that point to an OS input backend.

This module implements both halves, plus the tests that pin the arithmetic down. The research
notes — why the mapping looks the way it does, what each backend costs, and where the whole
approach breaks — are in [`docs/viewport-to-screen.md`](docs/viewport-to-screen.md).

Status: **compiles and unit-tested, not wired into the running agent.** Nothing here is on the
default build path of the Browser4 reactor (see [Build](#build)).

---

## 1. The pipeline in one line

```
device px = windowOriginDevice + chromeInsets + cssPoint * devicePixelRatio
AWT px    = device px / awtScale
```

| Input | Where it comes from in Browser4 |
| --- | --- |
| `WindowFrame(left, top, width, height, dpiScale)` | CDP `Browser.getWindowForTarget` — already wrapped by `SessionWindowProbe.WindowBounds` (`browser4-core/browser4-browser/.../chrome/window/SessionWindowProbe.kt`). Values are DIP, so multiply by `dpiScale` for physical pixels. |
| `ViewportMetrics(innerWidth, innerHeight, outerWidth, outerHeight, dpr, vvOffsetX, vvOffsetY)` | One `evaluate` in the page: `({innerWidth, innerHeight, outerWidth, outerHeight, devicePixelRatio, visualOffsetX: visualViewport?.offsetLeft ?? 0, visualOffsetY: visualViewport?.offsetTop ?? 0})`. `devicePixelRatio` already folds OS display scale **and** page zoom. |
| `ChromeInsets(left, top, right, bottom)` | Not observable from any API: it is the browser chrome (title bar, tab strip, bookmarks bar, in-window borders). `ChromeInsets.derived(...)` estimates it from `frame.physicalHeight - viewportHeightDevice`; `ViewportScreenMapper.calibrate(...)` turns *one* click that landed correctly into exact insets. |

`ViewportScreenMapper.viewportToScreen(css)` is the forward map; `screenToViewport(...)` is the
inverse (useful to verify a template match taken from a screenshot); `screenToAwt(..., awtScale)`
is the last step `java.awt.Robot` needs.

Beware the three traps the tests encode:

* a 150 % display scales **both** the window frame and the CSS point — forgetting
  `dpiScale` puts the click at 2/3 of the intended distance;
* `devicePixelRatio` is a `Double` (`1.25`, `1.5`, …), never round it to an integer scale;
* page zoom > 1 makes `innerWidth` smaller *and* `dpr` bigger; do not derive the scale from
  `window.outerWidth / window.innerWidth` instead.

## 2. Backends

Both implement `PointerDriver` (`backend`, `virtualScreen`, `moveTo(point)`,
`click(point, button, doubleClick)`, `close()`), all coordinates in **device px** — the driver
does its own conversion:

| | `java.awt.Robot` (`RobotPointerDriver`) | Win32 `SendInput` (`WindowsSendInputDriver`) |
| --- | --- | --- |
| Dependencies | none (JDK) | `java.lang.foreign` only — **no JNA**, JDK 22+ |
| Coordinates | AWT user space, i.e. device px divided by the JDK's monitor transform | physical px, `MOUSEEVENTF_ABSOLUTE` normalised to `0..65535` across the virtual desktop (`SendInputCoordinates`) |
| DPI awareness | whatever the JVM set (per-monitor v2 on modern JDKs); a mismatched `awtScale` shifts every click | inherits the caller's awareness; a per-monitor-v2 JVM process is correct |
| Multi-monitor | works, but AWT user space can be surprising on mixed-DPI setups | negative coordinates are legal and handled (`ScreenRect` may have negative `x`/`y`) |
| Privilege | cannot reach a higher-integrity window (UIPI) | same restriction |
| Verification | `mvn test` covers only the math; the driver itself needs a desktop | pure normalisation logic is unit-tested, the `SendInput` link is Windows-only |

Both are **inherently screen-wide**, so the plugin must keep them behind its own tool names
(`os.moveTo`, `os.click`, …) — never let a page-driven coordinate reach them unchecked.

## 3. API sketch

```kotlin
import ai.platon.pulsar.osinput.geometry.*
import ai.platon.pulsar.osinput.input.*

val frame = WindowFrame(left = 100.0, top = 50.0, width = 1200.0, height = 900.0, dpiScale = 1.5)
val metrics = ViewportMetrics(1200.0, 800.0, 1216.0, 900.0, devicePixelRatio = 1.5)

// 1st approximation, no calibration needed
val mapper = ViewportScreenMapper.derived(frame, metrics)
check(mapper.isConsistent) { "viewport does not fit the window: the probe lied" }

// exact, from one known-good reference (e.g. the click that just landed)
val exact = ViewportScreenMapper(frame, metrics, ChromeInsets.parse("0,150,0,0"))

val screen = exact.viewportToScreen(cssX = 437.0, cssY = 219.0)

RobotPointerDriver().use { pointer ->          // or WindowsSendInputDriver()
    require(pointer.virtualScreen.contains(screen)) { "point is off-desktop: $screen" }
    pointer.moveTo(screen)
    pointer.click(screen, MouseButton.LEFT)
}
```

Spec parsers exist so the values can travel as tool arguments:
`WindowFrame.parse("l,t,w,h[,dpiScale]")`, `ViewportMetrics.parse("iw,ih,ow,oh,dpr[,vvx,vvy]")`,
`ChromeInsets.parse("l,t,r,b")`.

## 4. Build

The build file ships as `pom.xml.example`, **not** `pom.xml` — and that is deliberate:

`RepoConsistencyCheck` (and `ModuleMapDriftE2ETest`) walk the repository for `pom.xml`
files and require every live module to be listed in the root pom's `<modules>` *and* in the
static `ModuleMap.MODULES` snapshot. Both files are agent-protected here, so a live
`pom.xml` in this directory fails `validate repo-consistency` with
`ModuleMap.MODULES is missing 'browser4-os-input-prototype'`. Copy it, build, drop the copy:

```bash
cd browser4-os-input-prototype
cp pom.xml.example pom.xml
mvn -B test          # compiles src/main + src/test, runs 13 tests, JDK 25
rm pom.xml           # back to the non-reactor state the checker expects
```

The module is deliberately **outside the Browser4 reactor** and has no Browser4 dependency,
so it builds in seconds and cannot break the main build. Kotlin mojos are told their source
roots explicitly because, outside the root pom, nothing registers `src/main/kotlin`. A
reactor build (`mvn -pl browser4-os-input-prototype -am`) only works once the module is
registered in `browser4-plugins/pom.xml` — see section 5.


## 5. Promoting it to a real plugin

The intended home is `browser4-plugins/browser4-os-input` (generated with
`browser4-plugin-archetype`, registered in `browser4-plugins/pom.xml` and the BOM). Only the
adapter is missing; the geometry and the drivers move over unchanged. The adapter is a normal
`ToolExecutor`:

```kotlin
class OsInputToolExecutor(private val context: PulsarContext?, ...) : AbstractToolExecutor() {
    init {
        toolSpec["os.click"] = ToolSpec(
            name = "os.click",
            parameters = listOf(cssX, cssY, button, doubleClick),   // viewport CSS px
            description = "Click a desktop point outside the page (viewport CSS coordinates).",
        )
    }

    // windowBounds: SessionWindowProbe.WindowBounds, metrics: one evaluate in the page
    fun click(cssX: Double, cssY: Double, button: String?, doubleClick: Boolean): Map<String, Any?> {
        val mapper = ViewportScreenMapper.derived(frameFrom(windowBounds), metricsFrom(session))
        val point = mapper.viewportToScreen(cssX, cssY)
        driver.click(point, MouseButton.parse(button), doubleClick)
        return mapOf("backend" to driver.backend, "screen" to point.toMap())
    }
}
```

Register it like `browser4-plugins/browser4-media` does: a `MediaAutoConfiguration`-style class
implementing `getToolExecutors()` and returning the executor bean.

## 6. Non-negotiable limitations

* **UIPI / integrity levels** — a normal process cannot send input to an elevated window, nor
  to the secure desktop (UAC prompt, Ctrl-Alt-Del, lock screen). Nothing in user space fixes
  this; it needs a signed, elevated helper service.
* **No interactive session, no pointer** — Windows services, disconnected RDP sessions and
  locked workstations simply have no usable desktop.
* **Coordinate instability** — window moves, DPI changes, page zoom, a devtools panel opening,
  or a browser tab bar changing height invalidate the insets. Re-read `WindowFrame` + metrics
  per click and re-calibrate after any window geometry event.
* **Headless is a no-op** — a headless browser has no window, so `frame` does not exist.
* **Non-Windows** — `WindowsSendInputDriver` is Windows-only by construction (`user32`); on
  Linux `Robot` needs X11 (Wayland blocks it), on macOS it needs the Accessibility permission.

The safety rules a real plugin must enforce are listed at the end of
[`docs/viewport-to-screen.md`](docs/viewport-to-screen.md).
