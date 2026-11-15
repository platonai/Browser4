---
title: "IP Proxy & Egress IP"
description: "How to route the managed browser's page traffic through one fixed HTTP/SOCKS proxy or a rotating proxy pool, how that differs from the CLI's download proxy, and how to verify the egress IP actually changed."
tier: reference
---

# IP Proxy & Egress IP

[中文版](ip-proxy.zh.md)

Browser4 has two unrelated "proxy" settings. Confusing them is the most common
reason a configured proxy "does not work":

| What you want proxied | Setting | Covers |
|---|---|---|
| **Page traffic** — the browser's own requests, i.e. the IP a website sees | `browser.launch.chrome.args=--proxy-server=…` (one fixed proxy) or `proxy.rotation.url` (a pool) | The Chrome instance Browser4 launches |
| Installing / updating the runtime, `webminer` downloads | `--proxy <url>`, `config set proxy`, `BROWSER4_CLI_PROXY`, `https_proxy` | The CLI's own HTTP client |
| A browser you already run | That browser's own proxy settings | Nothing on the Browser4 side |

`browser4-cli --proxy` is the **second** row only. Its help text is literally
"HTTP proxy for downloads (runtime bundle, webminer)", and
`resolve_download_proxy()` in `cli/browser4-cli/src/daemon.rs` is the only
consumer — it never reaches the browser, and never reaches a page request.

Decision in one line: **page traffic → section 1 or 2; downloads → section 3.**

## 1. One fixed proxy

The backend reads its configuration **once, at startup**. The user-editable file is:

```
~/.browser4/config/conf-enabled/application-private.properties
# Windows: C:\Users\<you>\.browser4\config\conf-enabled\application-private.properties
```

`browser4-cli doctor --fix` creates and enables that file from the bundled
template, and `browser4-cli doctor` prints the exact path in use.

Add one line:

```properties
browser.launch.chrome.args=--proxy-server=http://127.0.0.1:7890
```

Chrome accepts a scheme (`http://`, `https://`, `socks4://`, `socks5://`) or a
bare `host:port` (HTTP). Restart, then prove it:

```shell
browser4-cli stop                                # configuration is read only at startup
browser4-cli open --fresh https://httpbin.org/ip
browser4-cli eval "document.body.innerText"      # {"origin": "<the proxy's IP>"}
```

Rules that explain most surprises:

- The value is handed to Chrome verbatim. Arguments are whitespace-separated and
  double quotes group one argument; the shipped
  `browser4-core/browser4-resources/src/main/resources/config/application.properties`
  carries the `--proxy-server` example.
- Configured arguments have the **lowest** priority: an argument takes effect only
  for a switch the program did not effectively set itself (`ChromeOptions.toList()`).
  A proxy pool (section 2) sets `--proxy-server` programmatically per browser
  context, so it silently wins over this line — do not enable both.
- The same value can come from the environment, also before backend startup:
  `BROWSER_LAUNCH_CHROME_ARGS=--proxy-server=http://127.0.0.1:7890`
  (relaxed binding resolves it to `browser.launch.chrome.args`).
- An existing session keeps its already-launched browser. Use `open --fresh`
  (or `close` first) — otherwise the new configuration applies only to browsers
  launched later.

### Authentication

Chrome cannot take `user:pass@host:port` in `--proxy-server`: a 407 renders an
auth dialog in headed mode and just fails in headless mode. Three ways out:

1. **IP allow-listing at the provider** — the usual commercial setup; nothing to
   configure on the Browser4 side.
2. **Use the pool** (section 2). A proxy entry's `username`/`password` are answered
   automatically through CDP `Fetch.authRequired` when the browser context is
   created.
3. **Attach** — configure the proxy in the browser itself (or with an extension),
   then `browser4-cli attach --cdp <endpoint>` (section 4).

## 2. A rotating pool

Set the rotation URL — a URL that returns one or more fresh proxies every time it
is fetched:

```shell
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
# Windows PowerShell: $env:PROXY_ROTATION_URL = "https://..."
```

```properties
# or, in the same application-private.properties:
proxy.rotation.url=https://your-proxy-provider.com/rotation-endpoint
```

The value must be a standard `http(s)` URL: `ProxyPoolManager.isProxyEnabled()`
treats anything else as "proxy disabled", so the pool silently never runs.

### Why rotation needs `SEQUENTIAL` / `TEMPORARY`

A proxy is bound **per browser context**, not per request:
`ProxyContext.create()` takes one entry from the pool and stores it in
`activeProxyEntries` keyed by the context directory. One context = one egress IP
for its whole lifetime.

`DEFAULT` uses one shared, permanent context, so it is effectively a *sticky*
proxy. IP rotation happens because fresh contexts are created — that is what
`SEQUENTIAL` (a pool of profiles) and `TEMPORARY` (a new profile per context) do:

```shell
# rotate within one session
browser4-cli open --fresh --profile-mode sequential https://example.com

# bulk crawling: contexts are the throughput dial, so they are also the IP dial
browser4-cli swarm create --profile-mode TEMPORARY --max-browser-contexts 3
browser4-cli swarm query --sql @q.sql --seed-file urls.txt
browser4-cli swarm close
```

The server-side default is `browser.profile.mode` (environment variable
`BROWSER_PROFILE_MODE`); the CLI's `--profile-mode` capability wins for the
session it creates. Named sessions (`-s <name>`) pin a dedicated profile
directory by design, so they keep one proxy — use them when you *want* a stable
IP, not when you want rotation.

### What the rotation URL should return

The response is parsed into proxy entries (`host`, `port`, optional
username/password/type/TTL):

| Parser | Accepted shape | When it is used |
|---|---|---|
| `ProxyHubParser` | `{"status":"success","data":{"proxies":[{"host":"…","port":1080,"username":"…","password":"…","type":"SOCKS5","declaredTTL":"…"}]}}` | Default when **no** rotation URL is configured |
| `UniversalProxyParser` | Any text/JSON — an LLM extracts the entries | What parses a configured `proxy.rotation.url` today |
| a parser registered for the exact URL | whatever it implements | `ProxyParserFactory.register(<rotation-url>, parser)` — takes precedence over both |

Consequence worth planning for: **with `proxy.rotation.url` set, parsing needs a
working LLM** (`DEEPSEEK_API_KEY` / `OPENROUTER_API_KEY` / … — see
[config.md](config.md)). Without one the log says `LLM not available`, the pool
stays empty, and tasks fail with `NoProxyException`. The easiest way to keep the
LLM's job trivial is to point the rotation URL at
[ProxyHub](https://github.com/platonai/ProxyHub)
(`docker run galaxyeye88/proxy-hub`; `GET http://localhost:8192/api/proxies`),
which already normalises provider-specific formats.

### Tuning

| Key | Default | Meaning |
|---|---|---|
| `proxy.pool.size` | `100` | Capacity of the pool |
| `proxy.pool.polling.interval` | `1s` | How long a take waits before giving up |
| `proxy.idle.timeout` | `10m` | Idle time before the pool manager goes quiet |
| `proxy.max.fetch.success` | unlimited | Successes before a proxy is retired from the context |

The pool is lazy: when the queue is empty the loader fetches the rotation URL
again (up to 10 takes, spaced by the polling interval).

### Failure modes

- `NoProxyException: No proxy found in pool` — the rotation URL returned nothing,
  the LLM is not configured, or the provider is down.
- `ProxyVendorUntrustedException` — ~200 consecutive absences within one day; the
  counter resets at the start of a new day.
- Rotation "not happening" — the session runs in `DEFAULT` mode (one long-lived
  shared context) or is a named session with a pinned profile. Use
  `--profile-mode sequential|temporary` or `swarm`.

## 3. Download-only proxy

For the CLI's own HTTP client (runtime bundle, `webminer`, install/upgrade), the
resolution order is:

1. `BROWSER4_CLI_PROXY` — set by `--proxy <url>` or `config set proxy <url>`
2. `https_proxy` / `HTTPS_PROXY` / `http_proxy` / `HTTP_PROXY` / `all_proxy` / `ALL_PROXY`
3. On Windows: the system proxy (WinHTTP, then the IE/Edge registry key)

`--proxy` is a global flag and must come **before** the command:

```shell
browser4-cli --proxy http://127.0.0.1:7890 install
browser4-cli config set proxy http://127.0.0.1:7890   # persistent default
```

Backend-side downloads of a different kind have their own keys:
`image.download.proxy`, `pptx.download.proxy`, `captcha.solve.proxy` (see
[config.md](config.md)). Those do not route the browser either — they only
control the JVM's own HTTP clients.

## 4. Attached browsers

`attach --cdp` and `attach --extension` bind to a browser that is already running,
so no launch argument can be applied: the egress IP is whatever that browser is
configured with. Two workable patterns:

- Configure the proxy (or an authenticating extension) in Chrome, keep its
  debugging port open, then `browser4-cli attach --cdp 9222`.
- Use a cloud/fingerprint browser that already exits through the IP you want, then
  attach to it.

## 5. Verify the egress IP

```shell
browser4-cli open --fresh https://httpbin.org/ip
browser4-cli eval "document.body.innerText"        # origin must be the proxy IP
```

`http://api.ipify.org`, `https://ifconfig.me/ip` and `https://ipinfo.io/ip` work
the same way.

To see whether Chrome was even started with the switch:

```shell
browser4-cli open --fresh chrome://version
browser4-cli htmlsnapshot grep -i "proxy-server"
```

(The `chrome://version` snapshot is a convenience; the IP echo page is the
authoritative check.)

## 6. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| The page still reports your own IP | Backend not restarted; or the session reused an old browser | `browser4-cli stop`, then `open --fresh` |
| Same, with a pool configured | `--proxy-server` lost to the per-context proxy (expected), but the pool is empty | Check the rotation URL and the LLM log line |
| `NoProxyException` | Rotation URL not `http(s)`; provider empty; no LLM configured | Fix the URL / the key, restart |
| `407` or `ERR_TUNNEL_CONNECTION_FAILED` | Proxy needs authentication, or the scheme is wrong | Credentials through the pool, or attach |
| Proxy set on one machine, ignored on another | Setting came from a shell env var the backend did not inherit | Put it in `application-private.properties` instead |
| `--proxy` "does nothing" | It is the download proxy | Use section 1 or 2 for page traffic |

## 7. Where this lives

| Path | Role |
|---|---|
| `browser4-core/browser4-resources/src/main/resources/config/application.properties` | `browser.launch.chrome.args` documentation and `--proxy-server` example |
| `browser4-boot/src/main/kotlin/ai/platon/pulsar/boot/autoconfigure/Browser4AutoConfiguration.kt` | `proxyPool`, `proxyPoolManagerFactory`, `proxyPoolManager` beans |
| `browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/emulator/context/BrowserPrivacyContext.kt` | `initProxyContextIfNecessary()` — the "proxy enabled" gate per privacy context |
| `browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/emulator/context/ProxyContext.kt` | Per-context proxy assignment, absence counting |
| `browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/controller/LlmConfigTemplate.kt` | `conf-enabled/application-private.properties` path and template installation |
| `cli/browser4-cli/src/daemon.rs` | `resolve_download_proxy()` — the download-proxy chain |
| `cli/browser4-cli/src/help.rs` | `--proxy` help text ("downloads") |
| `browser4-base`: `ProxyPoolManager`, `ProxyPool`, `ProxyVendorLoader`, `UniversalProxyParser`, `ProxyHubParser`, `PulsarWebDriver` | Pool, parsers, and the CDP `Fetch.authRequired` proxy authentication |

## See also

- [config.md](config.md) — the full configuration reference
- [browser-modes.md](../skills/browser4-cli/references/browser-modes.md) — profile modes and display modes
- [ProxyHub](https://github.com/platonai/ProxyHub) — a rotation endpoint that standardises provider formats
