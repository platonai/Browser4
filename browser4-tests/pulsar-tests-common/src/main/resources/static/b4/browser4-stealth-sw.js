/*
 * Service-worker side of the stealth probe fixture
 * (see stealth-probe-fixture.html).
 *
 * The service worker reports the same navigator surface as the page, so a test can detect the
 * cross-scope inconsistency a main-world-only patch produces: `Page.addScriptToEvaluateOnNewDocument`
 * reaches frames only, and `Emulation.setUserAgentOverride` does not reach service-worker globals,
 * so this scope is the one that betrays a session when the User-Agent (or any navigator value) is
 * patched anywhere else.
 */
self.addEventListener('install', () => self.skipWaiting())

self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))

self.addEventListener('message', (event) => {
  const port = event.ports && event.ports[0]
  if (!port) {
    return
  }
  port.postMessage({
    ua: navigator.userAgent,
    brands: navigator.userAgentData ? navigator.userAgentData.brands : null,
    platform: navigator.platform,
    language: navigator.language,
    languages: Array.from(navigator.languages || []),
    cores: navigator.hardwareConcurrency,
    webdriver: navigator.webdriver,
    tz: Intl.DateTimeFormat().resolvedOptions().timeZone
  })
})
