# Frames: iframe switching in browser4-cli

Web pages often embed forms, editors, or content in `<iframe>` elements. By
default every element command (`click`, `fill`, `type`, `hover`, `focus`,
`is visible|enabled|checked`, `wait`, element-scoped reads) resolves its CSS
selector against the **main document** — selectors never see inside iframes.

`frame` changes that: it switches the frame that subsequent element operations
resolve against, so iframe-heavy flows work without hand-written
`contentDocument` evaluation.

## Commands

| Command | Purpose |
|---|---|
| `frames` | List the page's frame tree (depth-first, main frame first): frame name, url, depth, parent, and which frame is active. |
| `frame <target>` | Switch the element-operation scope into the target frame. |
| `frame main` | Return to the main document. |

The `frame` target is resolved in this order:

1. **element ref** — a snapshot ref of the `<iframe>` element (`e12`,
   `backend:123`, `fbn:<frame>,123`); the frame it owns is resolved through
   `DOM.describeNode`, so it works even for iframes without `name`/`id`/`src`;
2. **CSS selector** matching an `<iframe>`/`<frame>` element inside the
   *currently scoped* document — nested iframes work by switching repeatedly;
3. **frame id** — the CDP frame id printed by `frames`;
4. **frame name** — the iframe's `name` attribute (exact match);
5. **url** — a case-insensitive substring of the frame's document URL.

`frame main` (or navigating the page) clears the scope.

## Example

```bash
browser4-cli goto "https://example.com/checkout"
browser4-cli frames                      # find the payment iframe
# frame 0 (main) url=...
#   frame 1 name=payframe url=.../pay
browser4-cli frame "#pay-frame"          # or: frame payframe / frame /pay
browser4-cli fill "#card-number" "4111 1111 1111 1111"
browser4-cli fill "#card-name" "Ada Lovelace"
browser4-cli click "#pay-submit"
browser4-cli is visible "#pay-error"     # assertions also resolve in-frame
browser4-cli frame main
```

## Semantics and limits

- **Scope**: the frame scope affects *element* operations that take a CSS
  selector/XPath: `click`, `dblclick`, `fill`, `type`, `press`, `hover`,
  `focus`, `check`, `uncheck`, `select`, `scroll-to`, `is visible|enabled|checked`,
  `exists`, `wait`, `text`, `attr`/`get`, element-scoped `eval --ref`.
- **`eval` stays in the main document** (same as agent-browser). To read
  same-origin iframe state from `eval`, reach through
  `document.querySelector('#pay-frame').contentDocument...`.
- **Same-origin iframes** are fully supported (the common case: same-site
  checkout frames, editors, widgets).
- **Cross-origin iframes** (out-of-process frames) are **not supported in
  this version**: they run in a separate browser process, and this driver
  does not attach per-frame CDP sessions (`Target.setAutoAttach`), so they
  are not part of the frame tree `frames` reports — attempting `frame` on one
  fails with an actionable error ("Frame not found", or "not reachable" on
  Chrome builds without site isolation). Options: drive the frame's origin
  directly, or use `cdp` with `Target.attachToTarget` for full control.
- **Navigation resets the scope** — the whole frame tree is replaced on
  `goto`/`open`/`reload`/back/forward. Re-run `frame` after navigating.
- **Stale frames**: if the selected iframe is removed or navigates away, the
  next operation reports an actionable "frame is not reachable" error; run
  `frame main` (or re-select) to recover.
- **XPath inside a frame** is not supported in this version — use a CSS
  selector while scoped, or switch back to the main frame first.

## Agent (MCP) tools

The same capability is exposed to agents as tab-domain tools:

- `tab.frameList()` — the frame tree (marks the active frame)
- `tab.frameSwitch(frame)` — switch scope (target forms as above)
- `tab.frameMain()` — back to the main frame

## Implementation notes

Frame resolution and scoped element resolution run in the driver
(`FrameManager` + `DOMHandler` in the pulsar-browser base library). Scoped
CSS resolution queries the selected frame's document through the pierced DOM
tree of the page's CDP session, so the resolved nodes keep the same semantics
as main-document nodes (scrolling, bounding boxes, input events).
