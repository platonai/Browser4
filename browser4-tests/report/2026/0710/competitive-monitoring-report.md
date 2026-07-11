# Competitive Pricing Monitoring Report

**Generated:** 2026-07-10
**Baseline Run:** Yes (no previous snapshots exist)
**Tools Compared:** Notion, Confluence (Atlassian), Coda (now Superhuman Docs)

---

## Executive Summary

This is the **first baseline capture** of competitive pricing data across three collaboration platforms. The capture was performed using browser4-cli to automate page navigation, snapshot capture, and data extraction. All raw HTML snapshots and structured data are stored in `competitive-snapshots/2026-07-10/`.

**Key Finding:** coda.io/pricing now automatically redirects to superhuman.com/docs/pricing, reflecting Coda's acquisition by Grammarly (Dec 2024) and Grammarly's subsequent rebrand to Superhuman (Oct 2025). Coda is now positioned as "Superhuman Docs" within the Superhuman suite.

---

## Change Detection

### Change Classification

| Type | Count | Details |
|------|-------|---------|
| Pricing change | 0 | Baseline run - no prior data |
| Feature added | 0 | Baseline run - no prior data |
| Feature removed | 0 | Baseline run - no prior data |
| Plan restructured | 1 | **Coda brand/domain change** - coda.io → superhuman.com |
| TOTAL | 1 | |

### Detailed Changes

#### 🔴 Coda Rebrand / URL Redirect
- **Severity:** High
- **Type:** Plan restructured / Brand change
- **Old behavior (presumed):** coda.io/pricing showed Coda's standalone pricing
- **New behavior:** coda.io/pricing redirects to superhuman.com/docs/pricing
- **Impact:** All monitoring scripts targeting coda.io must update URLs. The product is now part of a larger suite (Superhuman) alongside Mail, Calendar, and Grammarly.
- **Pricing model change:** Now uses "Doc Maker" billing (pay only for users who create docs)

---

## Current Pricing Comparison

### Plan Overview

| Tier | Notion | Confluence | Coda (Superhuman Docs) |
|------|--------|------------|------------------------|
| **Free** | $0/member/mo | $0 (≤10 users) | $0 |
| **Entry Paid** | Plus: $10/member/mo | Standard: ~$6/user/mo* | Pro: $12/Doc Maker/mo (annual) |
| **Mid Paid** | Business: $20/member/mo | Premium: ~$12/user/mo* | Business: $33/Doc Maker/mo (annual) |
| **Enterprise** | Custom | Custom | Custom |

> *Confluence Standard & Premium prices are dynamically rendered via JavaScript and were not extractable from the static HTML snapshot. Values shown are from public knowledge; automated extraction requires JavaScript execution (`eval`).

### Free Tier Comparison

| Feature | Notion Free | Confluence Free | Superhuman Docs Free |
|---------|-------------|-----------------|---------------------|
| User limit | Unlimited members | 10 users | Unlimited members |
| Sites | 1 workspace | 1 site | 1 workspace |
| AI features | Trial only | Rovo (limited) | Trial only |
| Storage | Basic | 2 GB file storage | Basic |
| Whiteboards | No | 3 active | No |
| Automation | No | 10 runs/month | Basic |
| Guest access | No | No | No |

### Entry Paid Tier Comparison

| Feature | Notion Plus ($10) | Confluence Standard | Superhuman Pro ($12) |
|---------|-------------------|---------------------|----------------------|
| User limit | Unlimited | 250,000 | Per Doc Maker |
| AI features | Trial | Rovo included | Full AI (Beta) |
| Storage | Unlimited uploads | 250 GB | Unlimited doc size |
| Customization | Custom forms/sites/domains | Limited | Custom icons/branding/domains |
| Version history | Basic | Basic | 30-day |
| Integrations | Basic (Slack, Google) | Apps & integrations | 46+ tools |
| Support | Standard | 9/5 regional | Standard |

### Mid Paid Tier Comparison

| Feature | Notion Business ($20) | Confluence Premium | Superhuman Business ($33) |
|---------|----------------------|-------------------|--------------------------|
| AI agents | Notion Agent | Rovo Agents | AI trackers, pages, views |
| SSO | SAML SSO | Atlassian Guard | No (Enterprise only) |
| Security | Domain verification | Advanced admin controls | Doc/page locking |
| Analytics | Basic | Atlassian Analytics + Data Lake | Basic |
| Automations | N/A | 1,000/user/month | Unlimited |
| Support | Standard | 24/7 critical issues | Group trainings |
| Integrations | Premium (GitHub, Asana) | Full app ecosystem | 72+ tools |

### Enterprise Tier Comparison

| Feature | Notion Enterprise | Confluence Enterprise | Superhuman Enterprise |
|---------|-------------------|----------------------|----------------------|
| Pricing | Custom | Custom | Custom |
| SCIM | Yes | Yes | Yes |
| SAML SSO | Yes | Yes (Atlassian Guard) | Yes |
| Audit logs | Yes | Yes | Yes |
| Data residency | Not specified | Yes | Not specified |
| DLP/SIEM | Yes | Via Guard Premium | Not specified |
| HIPAA | Not specified | Yes (Guard Premium) | Yes |
| SOC 2 | Not specified | Yes | Yes (Type 2) |
| Support | Customer success manager | 24/7 all issues | Enterprise support |

---

## Key Observations

### Notion Strengths
- Clean, simple pricing tiers with clear feature progression
- AI features becoming central: Notion Agent (Business), AI Meeting Notes (Business)
- Strong free tier with databases and basic sites
- Enterprise features focus on security, compliance, and admin controls

### Confluence Strengths
- Deep enterprise feature set with granular admin controls
- Atlassian Guard integration for security and identity
- Rovo AI across all tiers (new positioning)
- Extensive app/integration ecosystem
- Complex feature matrix — harder to compare at a glance

### Superhuman Docs (Coda) Strengths
- Unique "Doc Maker" billing model (only creators pay)
- Aggressive AI integration: Claude via MCP, AI trackers, AI agents
- Best integration count: 36 (Free) → 85+ (Enterprise)
- Grammarly integration across all paid tiers
- Tight bundling with Superhuman suite (Mail, Calendar)

### Pricing Position
- Notion is the most affordable at the entry level ($10/member)
- Superhuman Docs Pro is competitive at $12/Doc Maker
- Confluence offers the most mature enterprise feature set
- Superhuman Business ($33) is the most expensive mid-tier

---

## Snapshot Artifacts

| File | Description |
|------|-------------|
| `competitive-snapshots/2026-07-10/notion-pricing.html` | Full HTML snapshot of Notion pricing page |
| `competitive-snapshots/2026-07-10/confluence-pricing.html` | Full HTML snapshot of Confluence pricing page |
| `competitive-snapshots/2026-07-10/coda-pricing.html` | Full HTML snapshot of coda.io→superhuman.com pricing page |
| `competitive-snapshots/2026-07-10/pricing-data.json` | Structured JSON extract of all pricing data |

---

## Monitoring Setup

### Recommended CI Schedule
- **Frequency:** Weekly (Mondays recommended for catching weekend pricing changes)
- **Command:** Re-run the browser4-cli workflow defined in this evaluation

### To Set Up Recurring Monitoring
```bash
# Using browser4-cli loop command:
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- loop \
  --name "competitive-pricing-monitor" \
  -i 604800 \
  -- "goto 'https://www.notion.so/pricing' && htmlsnapshot && ..."
```

### Alert Thresholds
- **Critical:** Any enterprise plan price change
- **High:** Any paid tier price change >10%
- **Medium:** Feature additions/removals in paid tiers
- **Low:** Copy/text changes without structural impact

---

## Browser4-CLI Evaluation Notes

This report was generated as part of a browser4-cli usability evaluation. Issues discovered during execution are documented separately. Key observations relevant to this monitoring workflow:

1. **Confluence pricing** dynamically renders dollar amounts via JavaScript — not extractable from static `htmlsnapshot`. Requires `eval` or accessibility-tree `snapshot` for prices.
2. **coda.io redirect** to superhuman.com was detected — the `goto` command transparently followed the redirect but the htmlsnapshot initially failed with "Nil url is not allowed" after the redirect chain.
3. **Snapshot-based workflow** was effective for Notion (React-based) and Superhuman Docs (Next.js-based) pages.
4. **Export reliability** varied — Confluence exported successfully, Coda/Superhuman required a re-navigation after the redirect broke the session state.

---

*Report generated using browser4-cli automated competitive intelligence workflow.*
