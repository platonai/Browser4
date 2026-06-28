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

A dev-only mock (`src/ui/dev-mock-chrome.ts`) stubs `chrome.*` APIs so the UI renders standalone. It is a no-op when running inside the real extension.

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

### Tests

55 tests across 3 files covering the protocol handler, relay connection, and pending connections. Shared Chrome API mocks live in `src/__tests__/chromeMocks.ts`.

```bash
npm test              # single run
npm run test:watch    # watch mode
```

### Project structure

```
extension/
├── src/
│   ├── background.ts          # MV3 service worker
│   ├── relayConnection.ts     # WebSocket relay connection
│   ├── protocolHandlers.ts    # CDP protocol handler
│   ├── pendingConnection.ts   # Deferred connection management
│   ├── connectedTabGroup.ts   # Tab group lifecycle
│   ├── ui/
│   │   ├── connect.html/tsx   # Connection approval page
│   │   ├── status.html/tsx    # Status/disconnect page
│   │   └── dev-mock-chrome.ts # Chrome API stubs for dev mode
│   └── __tests__/
│       ├── chromeMocks.ts
│       ├── protocolHandlers.test.ts
│       ├── relayConnection.test.ts
│       └── pendingConnection.test.ts
├── vite.config.mts
├── vitest.config.ts
├── tsconfig.json
└── package.json
```
