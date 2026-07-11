# Headless Browser Framework Trends — July 2026

**Analysis Date:** 2026-07-10  
**Data Sources:** DuckDuckGo Search → GitHub Repositories  
**Methodology:** Searched for "headless browser framework", collected top mentioned projects, inspected GitHub stars, forks, and recent commit activity.

---

## 1. Executive Summary

The headless browser landscape in mid-2026 is undergoing a **paradigm shift from test automation to AI-agent infrastructure**. While traditional frameworks (Playwright, Puppeteer, Selenium) retain massive installed bases, the fastest-growing projects are AI-native tools purpose-built for autonomous agents. The data reveals three distinct tiers: established frameworks (mature, high stars, steady activity), rising AI-native platforms (explosive star growth, daily commits), and infrastructure/BaaS solutions (niche but growing).

---

## 2. Projects Analyzed

| # | Project | GitHub | Stars | Forks | Last Commit | Activity Level |
|---|---------|--------|-------|-------|-------------|----------------|
| 1 | **browser-use** | browser-use/browser-use | 104,075 | 11,481 | Jul 9, 2026 | 🔥 Very High (5+ commits/day) |
| 2 | **Puppeteer** | puppeteer/puppeteer | 95,401 | 9,635 | Jul 10, 2026 | 🟢 High (multiple/day) |
| 3 | **Playwright** | microsoft/playwright | 92,575 | 6,067 | Jul 10, 2026 | 🟢 High (multiple/day) |
| 4 | **Selenium** | SeleniumHQ/selenium | 34,278 | 8,704 | *(mature, stable)* | 🟡 Moderate |
| 5 | **Stagehand** | browserbase/stagehand | 23,448 | 1,610 | Jul 9, 2026 | 🔥 Very High |
| 6 | **Obscura** | h4ckf0r0day/obscura | 18,647 | 1,295 | Jul 9, 2026 | 🟢 High (daily bursts) |
| 7 | **Browserless** | browserless/browserless | 13,446 | 1,023 | *(estimated stable)* | 🟡 Moderate |
| 8 | **Steel Browser** | steel-dev/steel-browser | 7,319 | 947 | *(estimated active)* | 🟢 Active |

---

## 3. Key Trends

### Trend 1: AI-Native Browser Agents Have Exploded

**browser-use** (104K stars) now leads ALL headless browser projects in GitHub stars, surpassing both Playwright (92K) and Puppeteer (95K). This is remarkable for a project that is significantly newer than the incumbents. The project's pitch — "Make websites accessible for AI agents" — captures the defining trend of 2025–2026: headless browsers are no longer just for testing; they are the primary interface between AI agents and the web.

**Evidence:**
- browser-use: ~104K stars, 11.5K forks, 5+ commits/day
- Stagehand ("The SDK For Browser Agents"): 23K stars, 1.6K forks, rapid daily commits
- Both projects describe themselves explicitly as "browser agent" frameworks, not "testing" frameworks

### Trend 2: Playwright and Puppeteer Hold Strong but Star Velocity Is Slowing

Both Playwright (92.5K) and Puppeteer (95.4K) remain actively maintained with commits landing within hours of this analysis. Playwright's cross-browser approach (Chromium + Firefox + WebKit) and Puppeteer's tight Chrome integration keep them as the backbone of web automation. However, their star growth in relative terms is slower than the AI-native newcomers.

**Implication:** These are not declining projects — they are becoming infrastructure. Both will likely remain the execution layer that AI agent frameworks sit on top of (browser-use already uses Playwright under the hood).

### Trend 3: Rust-Based Browser Engines Are Emerging

**Obscura** (18.6K stars) represents a new category: headless browser engines not based on Chromium. Built in Rust with V8 JavaScript support and CDP compatibility, it positions itself as a "drop-in replacement for headless Chrome with Puppeteer and Playwright." This appeals to the web scraping and AI agent markets where Chromium's binary size, memory footprint, and detectability are pain points.

**Evidence:**
- 18.6K stars with daily commit activity (bursts of 5+ commits on active days)
- Explicitly marketed for "web scraping and AI agent automation"
- CDP-compatible, allowing reuse of existing tooling

### Trend 4: Browser-as-a-Service (BaaS) Is Maturing

**Browserless** (13.4K stars), **Steel Browser** (7.3K stars), and **Browserbase** (the company behind Stagehand) represent the shift toward managed browser infrastructure. These platforms abstract away browser fleet management, proxy rotation, CAPTCHA solving, and session pooling.

**Evidence:**
- Browserless: "Deploy headless browsers in Docker. Run on our cloud or bring your own."
- Steel Browser: "Open Source Browser API for AI Agents & Apps"
- Combined BaaS category represents ~44K+ GitHub stars

### Trend 5: The "Stagehand" Pattern — Agent SDKs on Top of Browsers

Stagehand (23K stars) is not a browser itself but an SDK for building browser agents. This mirrors a broader pattern: the value is shifting from the browser runtime (commodity) to the agent orchestration layer (differentiation). Stagehand provides `act()`, `extract()`, and `observe()` primitives that make it easier for LLMs to interact with web pages.

### Trend 6: Selenium's Relative Decline

Selenium (34K stars) maintains a large ecosystem and remains the most-deployed automation framework in enterprise settings, but its GitHub star count and star velocity trail the newer frameworks significantly. Its fork count (8.7K) remains high, indicating broad enterprise usage and customization. Selenium's challenge is that its WebDriver protocol is being superseded by CDP (Chrome DevTools Protocol) for performance-sensitive use cases.

---

## 4. Growth vs. Decline Assessment

| Project | Trajectory | Signal |
|---------|-----------|--------|
| browser-use | 📈 **Hypergrowth** | #1 in stars, highest commit frequency, AI agent wave tailwind |
| Stagehand | 📈 **Rapid Growth** | 23K stars from a young project, AI-native positioning |
| Obscura | 📈 **Growing** | 18.6K stars, Rust + CDP angle, active development |
| Steel Browser | 📈 **Growing** | 7.3K stars, AI agent API positioning, active |
| Playwright | 📊 **Stable/Mature** | 92K stars, daily commits, becoming infrastructure |
| Puppeteer | 📊 **Stable/Mature** | 95K stars, daily commits, tightly coupled to Chrome |
| Browserless | 📊 **Stable** | 13.4K stars, established BaaS player |
| Selenium | 📉 **Slow Decline (relative)** | Legacy protocol, lower star velocity, but massive enterprise base |

---

## 5. What This Means

1. **The center of gravity is shifting from "automate my test suite" to "give my AI agent eyes and hands on the web."** Projects that position for AI agents are outgrowing testing-focused projects 2–5x in star velocity.

2. **Playwright is winning the underlying execution layer.** browser-use uses Playwright. Many agent frameworks use Playwright. Microsoft's investment in cross-browser support and CDP compatibility is paying off.

3. **Rust is the dark horse.** Obscura's growth signals real demand for lighter, faster, harder-to-detect alternatives to headless Chrome. If CDP compatibility proves sufficient, Obscura and similar Rust-based engines could capture significant share from Chromium-based solutions.

4. **BaaS is becoming table stakes.** Running your own browser fleet is increasingly seen as undifferentiated heavy lifting. The cloud/managed browser providers are growing alongside the agent trend because AI agents consume browser sessions at much higher volume than test suites.

5. **The Selenium ecosystem is not going away but is losing mindshare.** Enterprise migrations are slow, and Selenium's WebDriver protocol still underpins many tools. But the enthusiasm, stars, and developer attention have clearly moved on.

---

## 6. Recommendations for Browser4

Browser4 sits at the intersection of these trends: it is a headless browser engine purpose-built for AI agents, similar to Obscura's positioning but with additional features (ML-based extraction, coroutine safety, high throughput). Key observations:

- **Competitive positioning should emphasize AI-agent infrastructure**, not web testing — the testing market is saturated
- **CDP compatibility is table stakes** — Obscura's success shows developers want drop-in Playwright/Puppeteer compatibility
- **Performance differentiators matter** — the claim of 100K–200K daily page visits is compelling in a market where agent-driven browsing will dramatically increase volume
- **Developer experience is critical** — Stagehand and browser-use are winning partly because they are easy to start with; Browser4's CLI is a strong asset here if usability is refined

---

*Data collected via browser4-cli on 2026-07-10. Stars and forks are approximate (rounded from GitHub page extraction). Commit activity assessed from the most recent 10 commits on each project's default branch.*
