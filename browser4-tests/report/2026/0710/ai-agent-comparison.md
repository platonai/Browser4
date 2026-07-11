# AI Agent Products — Comparison Matrix

**Source:** Product Hunt search for "AI agent" — Top 3 products as of July 2026
**Generated:** 2026-07-10

> **Note:** Product Hunt was inaccessible via browser automation due to a Cloudflare security challenge. Product identification was done via web search, and official websites were visited using browser4-cli.

---

## Product Overview

| | Agent Builder by Thesys | ElevenAgents by ElevenLabs | Offsite (now Oasis) |
|---|---|---|---|
| **Product Hunt Tagline** | "Build AI agents that respond with UI instead of text" | "Build smart conversational AI voice agents" | "Build teams of humans and agents, watch them work." |
| **Category** | AI Agents, No-Code Development, Developer Tools | Voice AI, Conversational AI, AI Agents | AI Agents, Team Collaboration, Productivity |
| **Official Website** | [thesys.dev](https://www.thesys.dev) | [elevenlabs.io](https://elevenlabs.io) | [joinoasis.com](https://joinoasis.com) |
| **Product Hunt Page** | [Agent Builder by Thesys](https://www.producthunt.com/products/thesys) | [ElevenAgents](https://www.producthunt.com/products/elevenagents) | [Offsite](https://www.producthunt.com/products/offsite-2) |
| **Product Hunt Ranking** | #8 Weekly (Feb 2026) | 5.0★ / 14 followers | ~693 followers |

---

## Pricing Comparison

| Plan | Agent Builder by Thesys | ElevenAgents | Offsite (Oasis) |
|---|---|---|---|
| **Free Tier** | $0/mo — 3,000 API calls/mo, 3 agents max | $0/mo — 15 min calls included | Alpha Access — 100% Free |
| **Starter/Build** | $49/mo — 25K calls, unlimited agents, custom branding | $5/mo — 75 min calls | TBD |
| **Creator/Grow** | $499/mo — 500K calls, RBAC, audit logs, SSO | $22/mo (1st month 50% off) — 275 min calls | TBD |
| **Pro/Scale** | Custom — Self-hosting, VPC, HIPAA | $99/mo — 1,238 min calls | TBD |
| **Business** | — | $330/mo (Scale), $1,320/mo (Business) | TBD |
| **Enterprise** | Custom — Dedicated infra, SLA | Custom — Custom minutes, dedicated support | TBD |
| **Overage** | $0.002/call (Build), $0.001/call (Grow) | ~$0.08/min beyond included minutes | N/A |

### Key Pricing Notes

- **Thesys:** Annual billing saves ~20%. Free models have zero LLM cost (data may be used for training, ~100 calls/day limit). LLM tokens billed separately at provider rates.
- **ElevenLabs:** Prices shown are monthly billing. Annual billing offers additional savings. LLM costs billed separately. Telephony provider fees passed through at cost.
- **Offsite/Oasis:** Currently in alpha — completely free. No paid tiers announced. Expected to be per-seat or usage-based after general availability.

---

## Feature Focus Comparison

| Dimension | Agent Builder by Thesys | ElevenAgents | Offsite (Oasis) |
|---|---|---|---|
| **Core Capability** | Build AI agents that respond with interactive UI (charts, forms, cards, slides, reports) instead of plain text | Build and deploy voice AI agents for real-time conversations (STT + LLM + TTS) | Collaborative workspace where humans and AI agents work together as a persistent team |
| **Interaction Mode** | Visual / UI-based responses | Voice / conversational | Chat / collaborative workspace |
| **Target User** | Developers & product teams building AI apps | Businesses needing voice agents (support, sales, reception) | Teams wanting to integrate AI agents into daily workflows |
| **No-Code** | Yes — Agent Builder is fully no-code | Yes — Pre-built templates available | Yes — "Spin up a team without writing code" |
| **API Access** | Yes — C1 Generative UI API (2 lines of code) | Yes — ElevenAPI for full programmatic control | Not yet (alpha stage) |
| **Integrations** | Snowflake, BigQuery, PostgreSQL, Salesforce, HubSpot, Notion, Slack, Jira, Stripe, Google Drive | CRM, calendar, knowledge base integrations | Claude Code, OpenClaw, MCP-compatible agents, Slack, Notion |
| **Multi-Agent** | Yes — Multiple agents per workspace | Yes — Multi-agent setups with turn-taking | Yes — Team-based multi-agent collaboration with org chart |
| **Human-in-the-Loop** | Implicit (agents respond with UI for human interaction) | Yes — Guardrails, policy enforcement, human handoff | Yes — Core feature: every action requires human approval |
| **Languages** | LLM-dependent (model support via OpenRouter) | 31 languages supported | Not specified |
| **Security/Compliance** | SOC 2 Type II, ISO 27001, GDPR | Enterprise guardrails, prompt injection protection | Built-in behavioral guardrails, token cost caps |
| **Maturity** | Generally Available (GA) | Generally Available (GA) | Alpha (waitlist) |

---

## Positioning Analysis

### Agent Builder by Thesys
**Positioning:** "The Generative UI Company" — making AI agents respond with interactive visual interfaces instead of text. Targets product teams who want AI-powered features without building complex UI from scratch.

**Key Differentiator:** The only platform specifically focused on generative UI for agents — agents output charts, forms, dashboards, and reports instead of text.

**Best For:** Teams building customer-facing AI products that need rich, interactive responses.

---

### ElevenAgents by ElevenLabs
**Positioning:** "Build smart conversational AI voice agents" — leveraging ElevenLabs' industry-leading voice synthesis to create human-like voice agents. Part of a broader ecosystem (ElevenCreative for content, ElevenAPI for developers).

**Key Differentiator:** Best-in-class voice quality and expressiveness, with 31-language support and enterprise-grade guardrails. Strongest in voice-first use cases.

**Best For:** Businesses needing voice AI agents for customer support, sales development, reception, or internal enablement.

---

### Offsite (Oasis)
**Positioning:** "Where humans and agents come to work" — a collaborative workspace that puts AI agents on the same organizational plane as human team members. Backed by a16z Speedrun.

**Key Differentiator:** The only tool that treats agents as persistent team members with an org chart, not as one-shot tools. Human-in-the-loop is a core design principle, not an afterthought.

**Best For:** Teams already using multiple AI agents (Claude Code, OpenClaw, etc.) who want to coordinate them in a single workspace with full visibility and control.

---

## Quick Comparison Summary

```
                    UI Agents    Voice Agents    Team Workspace
Thesys              ★★★★★          ★              ★
ElevenLabs          ★              ★★★★★           ★★
Offsite/Oasis       ★★             ★               ★★★★★

                    No-Code       API Access      Maturity
Thesys              ★★★★★          ★★★★★           ★★★★★
ElevenLabs          ★★★★           ★★★★★           ★★★★★
Offsite/Oasis       ★★★★★          ☆ (alpha)        ★★
```

---

## Methodology

1. **Product Hunt Search:** Attempted to navigate to producthunt.com and search for "AI agent" using browser4-cli. The site was blocked by Cloudflare bot protection (see Issues below).
2. **Product Identification:** Used web search to identify top AI agent products on Product Hunt, cross-referencing multiple sources.
3. **Official Website Visits:** Used browser4-cli to navigate to each product's official website.
4. **Pricing Extraction:** Used `snapshot -v 0`, `snapshot grep`, and `htmlsnapshot` commands to extract pricing information from each website's pricing page.
5. **Feature Analysis:** Compared feature sets, positioning, and target audiences from official websites, documentation, and Product Hunt listings.

---

## Issues Encountered (browser4-cli Evaluation)

### Issue 1: Cloudflare Challenge Blocked Product Hunt Access

**Severity:** High
**Category:** Reliability

The primary task step — navigating to Product Hunt and searching for "AI agent" — was blocked by a Cloudflare security challenge. The page showed "请稍候…" (Please wait...) with a Cloudflare Turnstile widget that could not be bypassed through browser automation.

**Workaround:** Used web search (WebSearch tool) to identify top AI agent products on Product Hunt, then visited individual product websites directly. This worked for the non-Cloudflare-protected sites but added friction to the workflow.

### Issue 2: Ref Navigation Click Did Not Change Page URL

**Severity:** Medium
**Category:** Reliability / UX

When clicking the "Pricing" link (ref=e200) on thesys.dev, the click was reported as successful but the page URL did not change. Required a direct `goto` to the pricing URL (`/pricing`) as a workaround.

**Expected:** Clicking a navigation link should navigate to the target page.
**Actual:** Click succeeded but the page stayed on the same URL. The pricing page was accessible via direct URL navigation.

### Issue 3: SPA Content Not Visible in Accessibility Tree

**Severity:** Medium
**Category:** Reliability / Product

The Oasis/Offsite website (joinoasis.com) is a JavaScript Single Page Application. The accessibility tree snapshot showed only generic containers with no text content. `htmlsnapshot get` returned "No elements matched" for basic selectors. `eval "document.body.innerText"` returned an empty string.

**Expected:** Snapshot should capture visible text content from SPAs.
**Actual:** Both `snapshot` and `htmlsnapshot` failed to extract content from the SPA. A visual screenshot was required to understand the page content.

### Issue 4: Snapshot Output Verbose but Key Details Hard to Find

**Severity:** Low
**Category:** UX

The `snapshot -v 0 --stdout` output is very verbose (500+ lines for a pricing page), mixing structural nodes with content. While `snapshot grep` helps narrow down, filtering pricing-specific information requires multiple passes. A table-extraction or structured pricing view would improve the workflow.

### Issue 5: Background Task for Simple Click Operations

**Severity:** Low
**Category:** UX

Several `click` commands ran as background tasks (e.g., clicking the Agent Builder tab, Cloudflare widget) requiring additional `TaskOutput` polling. For simple, fast interactions, the synchronous behavior would be preferable.

### Issue 6: Page Title Mismatch on ElevenAgents Pricing Page

**Severity:** Low
**Category:** Discoverability

The ElevenAgents pricing page at `/pricing/agents` showed the title "ElevenAgents Pricing for creators and businesses of all sizes", but the general pricing page at `/pricing` also showed similar tabs. The relationship between ElevenCreative, ElevenAgents, and ElevenAPI pricing pages was discoverable through the tab interface, but not immediately obvious from the URL structure alone.
