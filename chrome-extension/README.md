# Browser4 Chrome Extension

## Development

### Setup

```bash
cd extension
npm install
```

### Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Start Vite dev server with HMR for the UI |
| `npm run build` | Build the extension to `dist/` |
| `npm test` | Run all tests once |
| `npm run test:watch` | Run tests in watch mode |

### Open UI directly (dev mode)

Run the dev server then open in your browser:

```bash
npm run dev
```

| Page | URL |
|------|-----|
| Connect page | `http://localhost:5173/connect.html?mcpRelayUrl=ws://127.0.0.1:9222&client={"name":"test"}` |
| Status page | `http://localhost:5173/status.html` |
| Demo page | `http://localhost:5173/demo.html` |

A dev-only mock (`src/ui/dev-mock-chrome.ts`) stubs `chrome.*` APIs so the UI renders standalone. It is a no-op when running inside the real extension.

The demo page (`demo.html`) showcases all UI components (header, footer, tabs, auth tokens, copy-to-clipboard) in a single interactive page for visual review and testing.

#### Connect page URL parameters

| Param | Purpose |
|-------|---------|
| `mcpRelayUrl` | WebSocket URL of the MCP relay **(required)** |
| `client` | JSON with client info, e.g. `{"name":"browser4-cli"}` |
| `token` | Auth token to bypass the approval dialog |
| `newTab=true` | Hide the tab picker (used for `browser_navigate`) |

### Load in Chrome (full functionality)

```bash
npm run build
```

Then open `chrome://extensions`, enable "Developer mode", click **Load unpacked**, and select the `dist/` folder.

### Attach from Browser4 CLI

The CLI can connect directly to a running extension instance without loading it unpacked:

```bash
browser4-cli attach --extension
```

This opens the extension's demo page and connects through the MCP relay, bypassing the `chrome://extensions` loading step. Useful for development and testing workflows where you need quick attach-detach cycles.

### Tests

64 tests across 3 files covering the protocol handler, relay connection, and pending connections. Shared Chrome API mocks live in `src/__tests__/chromeMocks.ts`.

```bash
npm test              # single run
npm run test:watch    # watch mode
```

### Project structure

```
chrome-extension/
├── manifest.json               # MV3 extension manifest
├── package.json
├── tsconfig.json
├── tsconfig.ui.json            # TS config for UI source
├── vite.config.mts             # Vite config for UI pages
├── vite.config.sw.mts          # Vite config for service worker
├── vitest.config.ts
├── icons/
│   ├── icon-16.png
│   ├── icon-32.png
│   ├── icon-48.png
│   └── icon-128.png
├── src/
│   ├── background.ts           # MV3 service worker
│   ├── relayConnection.ts      # WebSocket relay connection
│   ├── protocolHandlers.ts     # CDP protocol handler
│   ├── pendingConnection.ts    # Deferred connection management
│   ├── connectedTabGroup.ts    # Tab group lifecycle
│   ├── ui/
│   │   ├── connect.html/tsx    # Connection approval page
│   │   ├── connect.css         # Connect page styles
│   │   ├── status.html/tsx     # Status/disconnect page
│   │   ├── demo.html/tsx       # UI component showcase (dev only)
│   │   ├── demo.css
│   │   ├── header.tsx/css      # Reusable header bar
│   │   ├── footer.tsx/css      # Reusable footer bar
│   │   ├── tabItem.tsx         # Tab selection component
│   │   ├── authToken.tsx/css   # Token-based auth UI
│   │   ├── copyToClipboard.tsx/css  # Clipboard utility component
│   │   ├── emptyState.tsx/css  # Empty-state placeholder
│   │   ├── icons.tsx/css       # Inline SVG icon components
│   │   ├── colors.css          # Shared color palette (dark mode)
│   │   ├── dev-mock-chrome.ts  # Chrome API stubs for dev mode
│   │   └── tsconfig.json
│   └── __tests__/
│       ├── chromeMocks.ts
│       ├── protocolHandlers.test.ts
│       ├── relayConnection.test.ts
│       └── pendingConnection.test.ts
```
