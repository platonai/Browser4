# Open Source Browser Automation Project Health Report

**Date:** 2026-07-10
**Search query:** `browser automation stars:>1000` on GitHub
**Top 3 results (by Best match):**

---

## Overall Health Scores

| Rank | Repository | Health Score | Stars | Status |
|------|-----------|-------------|-------|--------|
| 1 | SeleniumHQ/selenium | **92/100** 🟢 | 34,277 | Excellent |
| 2 | lightpanda-io/browser | **78/100** 🟢 | 31,720 | Good |
| 3 | vercel-labs/agent-browser | **62/100** 🟡 | 38,222 | Moderate |

---

## 1. SeleniumHQ/selenium — Score: 92/100 🟢 Excellent

### Overview
- **URL:** https://github.com/SeleniumHQ/selenium
- **Description:** A browser automation framework and ecosystem
- **Language:** Java (multi-language: Python, Ruby, JavaScript, .NET, Rust)
- **License:** Apache-2.0
- **Created:** 2013-01-14 (13+ years)
- **Default Branch:** trunk

### Metrics
| Metric | Value | Assessment |
|--------|-------|------------|
| Stars | 34,277 | 🔥 Exceptional community trust |
| Forks | 8,704 | 🔥 Massive derivative ecosystem |
| Commits | 34,642 | 🔥 Deep development history |
| Contributors | 405 | 🔥 Very large, diverse contributor base |
| Branches | 185 | Active multi-stream development |
| Tags/Releases | 260 | Regular, structured releases |
| Open Issues | 91 | Healthy for a project this size |
| Closed Issues | 11,108 | 🔥 99.2% resolution rate |
| Open PRs | 86 | Active contribution pipeline |
| Last Push | 2026-07-10 (~17 min ago) | 🔥 Extremely active |

### Activity Level: **Very High** 🔥
- Daily commits from multiple authors
- 405 contributors across its history
- Issues are resolved at a 99.2% rate
- 13+ years of sustained development
- Backed by the Selenium project (major open source foundation)

### Health Assessment
Selenium is the gold standard for browser automation OSS. Its massive contributor base, near-perfect issue resolution rate, and continuous daily development over 13+ years demonstrate exceptional project health. The multi-language ecosystem (Java, Python, JavaScript, Ruby, .NET, Rust) ensures broad applicability. The project is highly mature and stable.

### Risk Factors
- Very large codebase — steep learning curve for new contributors
- Legacy code accumulation over 13 years may create maintenance burden
- Multi-language support increases maintenance surface area

---

## 2. lightpanda-io/browser — Score: 78/100 🟢 Good

### Overview
- **URL:** https://github.com/lightpanda-io/browser
- **Description:** Lightpanda: the headless browser designed for AI and automation
- **Language:** Zig
- **License:** AGPL-3.0
- **Created:** 2023-02-07 (3+ years)
- **Default Branch:** main

### Metrics
| Metric | Value | Assessment |
|--------|-------|------------|
| Stars | 31,720 | 🟢 Very strong community interest |
| Forks | 1,406 | 🟢 Healthy ecosystem growth |
| Commits | 7,855 | 🟢 Substantial development |
| Contributors | 52 | 🟢 Growing, moderately sized team |
| Branches | 42 | Active feature development |
| Tags/Releases | 19 | Regular release cadence |
| Open Issues | 77 | Manageable |
| Closed Issues | 395 | 🟢 83.7% resolution rate |
| Last Push | 2026-07-10 (~1 hour ago) | 🔥 Actively maintained |

### Activity Level: **High** 🟢
- Frequent commits (hourly as of this report)
- 52 contributors — healthy for a 3-year-old project
- 83.7% issue resolution rate
- Active community engagement (issues opened daily)

### Health Assessment
Lightpanda is a strong, actively maintained project with an innovative approach (Zig-based headless browser for AI agents). It has substantial community interest (31.7k stars in ~3 years) and regular development activity. The AGPL-3.0 license may limit adoption in some commercial contexts, but signals strong commitment to open source values.

### Risk Factors
- Smaller contributor base (52) concentrated risk if key maintainers leave
- Written in Zig — smaller talent pool for contributions
- AGPL-3.0 license may deter some commercial users
- Relatively new (3 years) compared to established alternatives

---

## 3. vercel-labs/agent-browser — Score: 62/100 🟡 Moderate

### Overview
- **URL:** https://github.com/vercel-labs/agent-browser
- **Description:** Browser automation CLI for AI agents
- **Language:** Rust
- **License:** Apache-2.0
- **Created:** 2026-01-11 (~6 months ago)
- **Default Branch:** main

### Metrics
| Metric | Value | Assessment |
|--------|-------|------------|
| Stars | 38,222 | 🔥 Viral growth (38k in 6 months!) |
| Forks | 2,465 | 🔥 Rapid ecosystem formation |
| Commits | 603 | 🟡 Early-stage volume |
| Contributors | 109 | 🟢 Rapid contributor acquisition |
| Branches | 313 | 🟡 Very high — possible branch sprawl |
| Tags/Releases | 92 | 🟢 Frequent releases (92 in 6 months) |
| Open Issues | 288 | 🔴 High for project age |
| Closed Issues | 232 | 44.6% resolution rate |
| Open PRs | 275 | 🔴 Very high — bottleneck risk |
| Last Push | 2026-07-08 (2 days ago) | 🟢 Recently active |

### Activity Level: **Very High but Chaotic** 🟡
- Explosive growth: 38k stars, 2.5k forks in 6 months
- 109 contributors acquired rapidly
- 92 releases in 6 months (release frequency ≈ every 2 days)
- BUT: issue resolution at only 44.6%
- 275 open PRs + 288 open issues signals a backlog problem

### Health Assessment
Agent-browser is experiencing hypergrowth driven by the AI agent boom. The project has incredible momentum (Vercel-backed, Apache-2.0, Rust) but shows signs of growing pains: a mounting issue/PR backlog, high branch count suggesting fragmented development, and a resolution rate below 50%. The project needs to scale its triage and review processes to match its growth rate.

### Risk Factors
- Very young project (6 months) — unproven long-term sustainability
- Issue/PR backlog growing faster than resolution capacity
- 313 branches suggests possible coordination challenges
- Dependent on Vercel's continued support and community momentum
- Rapid release cadence (every 2 days) may indicate insufficient testing

---

## Comparative Analysis

### Issue Health
```
Selenium:     ████████████████████████████████████████████ 99.2% resolved
Lightpanda:   ██████████████████████████████████████       83.7% resolved  
Agent-Browser:██████████████████                           44.6% resolved
```

### Community Size (Contributors)
```
Selenium:     405 ████████████████████████████████████████████████████████
Agent-Browser:109 ██████████████
Lightpanda:    52 ██████
```

### Maturity vs. Momentum
```
                  Maturity →   
              Low              High
Momentum  ┌──────────────────────────┐
↑    High │ agent-browser  │ selenium │
│         │ (new but hot)  │ (veteran)│
│    Low  │                │lightpanda│
│         │                │(growing) │
└──────────────────────────┘
```

---

## Recommendations

### For Selenium
- **Maintain:** Continue current governance and maintenance practices
- **Watch:** Legacy code accumulation — consider periodic cleanup sprints
- **Opportunity:** Leverage AI-assisted testing/docs tooling to reduce maintenance burden

### For Lightpanda
- **Grow:** Invest in contributor onboarding to expand the 52-person contributor base
- **Document:** Ensure thorough documentation to offset Zig's smaller talent pool
- **License clarity:** Consider offering a commercial license option alongside AGPL-3.0

### For Agent-Browser
- **Triage urgently:** Address the 288/275 open issue/PR backlog before it becomes unmanageable
- **Governance:** Establish clearer contribution guidelines and review SLAs
- **Stabilize:** Slow the release cadence from ~2 days to a weekly cycle with proper QA
- **Branch cleanup:** Consolidate the 313 branches to reduce fragmentation

---

## Methodology

Data collected on 2026-07-10 using:
- **browser4-cli** for navigating GitHub search, repository pages, and issue trackers
- `snapshot grep` for extracting open/closed issue counts from accessibility tree snapshots
- `htmlsnapshot` for capturing structured page metadata (stars, forks, commits)
- GitHub API (`gh api`) for contributor counts and precise repository metadata

### Scoring Rubric
| Dimension | Weight | Criteria |
|-----------|--------|----------|
| Issue Resolution | 25% | Closed/Total ratio |
| Contributor Diversity | 20% | Number of contributors |
| Update Recency | 20% | Time since last commit |
| Community Engagement | 15% | Stars, forks growth rate |
| Maintainability | 10% | Release cadence, PR backlog, branch health |
| Project Maturity | 10% | Age, license, governance signals |
