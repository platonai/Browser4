# AI Coder Guide (v2025-10-14)

Goal: Help AI work efficiently, stably, and predictably across code, review, docs, scripts, and tests—favoring stability and verifiability.

---

## 1. Overview & Environment Setup

- Repository: Multi‑module Maven; always use the Maven wrapper from the project root
    - Unix/macOS: `./mvnw`
    - Windows (cmd.exe): `mvnw.cmd`
- Primary language: Kotlin (Java interop exists; prefer Kotlin for new public APIs)
- Java compatibility: Determine versions from the root `pom.xml`
- Domain: Browser Agent and Browser Automation
- System adaptation: Detect OS first before suggesting/using shell commands; prefer cross‑platform commands
- Priority order: Stability > Maintainability > Performance > Feature expansion > Aesthetics

Notes for Windows (cmd.exe):
- Quote `-D` properties, e.g. `-D"dot.sperated.parameter=quoted"`
- When running tests with the `-am` parameter, add `-D"surefire.failIfNoSpecifiedTests=false"`
- For test glob examples on Windows, see the [Testing Guide](./devdocs/copilot/test-guide.md)

---

## 2. General Guidelines & Coding Patterns

- Prefer immutable data models (Kotlin `data class`, `val` fields)
- Small, single‑responsibility functions; explicit return types for public APIs
- Null-safety: Prefer non‑null; when needed use `?`, `?:`, `require`, `check`
- Extension functions: OK for local enrichment; don’t hide core business flow in extensions
- Exceptions: Model expected branches with types/returns; throw runtime only for unrecoverable errors
- Performance basics: Avoid boxing in hot paths; lazy init expensive objects; avoid deep decorator chains
- Do not mass‑reformat unrelated code; preserve license headers and existing style
- Avoid adding dependencies; if unavoidable, justify in PR and align with parent BOM
- Prefer single-line if under 120 chars in source files

---

## 3. AI Agent Behavior

Provide consistent, minimal, verifiable contributions:
- Environment detection: Choose commands per OS; always use Maven wrapper
- Editing rules: Restrict diffs to purposeful changes; keep style/license headers
- Code generation: Favor pure functions and immutability; see the [Testing Guide](./devdocs/copilot/test-guide.md)
- Abstractions: When adding an interface/strategy, include concise KDoc rationale
- Testing: see the [Testing Guide](./devdocs/copilot/test-guide.md)
- External/LLM code: Inject strategy/provider to enable determinism; see the [Testing Guide](./devdocs/copilot/test-guide.md)
- Prompts/LLM: Keep prompts short, structured, versioned constants; centralize output parsing
- Documentation: Update the closest `README-*` when changing developer-facing concepts
- Security & safety: Never log secrets; validate untrusted input (selectors, URLs)
- Performance: Defer heavy init (LLM clients, large caches) until first use
- PR hygiene: Keep commits logically grouped; reference tickets when available

---

## 4. AI Workflow

| Phase | Action | Notes |
| ---- | ---- | ---- |
| 1. Discover | Clarify scope | Inputs, expected outputs, constraints |
| 2. Gather | grep / read code | Locate modules, packages, owners |
| 3. Design | Draft | Interfaces, dataflow, state, error strategy |
| 4. Implement | Minimal edits | Avoid broad reformatting/reordering |
| 5. Verify | Build | For testing, see the [Testing Guide](./devdocs/copilot/test-guide.md) |
| 6. Summarize | Output notes | Coverage, risks, next steps |

Definition of Done:
1. Minimal, self‑consistent change; no behavior break unless marked “Breaking Change”
2. Build passes: `./mvnw -q -DskipTests` compiles; for testing expectations, see the [Testing Guide](./devdocs/copilot/test-guide.md)
3. Testing expectations: see the [Testing Guide](./devdocs/copilot/test-guide.md)
4. Logs exclude sensitive information
5. Dependencies: No drift/duplication; respect BOM
6. Security: No hardcoded credentials; validate inputs
7. Docs: Public API/config/behavior changes documented
8. Commit msg: Motivation + key changes + risks + rollback
9. Performance: If impact >5%, include assessment/mitigation

---

## 5. Project Structure

Root layout (abridged):
```
project-root/
├── pulsar-core/            # Core modules
│    ├── pulsar-skeleton/   # WebDriver core
│    ├── pulsar-agentic/    # Agentic engine (sessions/agents/TextToAction)
│    ├── pulsar-common/     # Shared utilities
│    ├── pulsar-dom/        # DOM manipulation & parsing
│    ├── pulsar-persist/    # Data persistence
│    ├── pulsar-plugins/    # Plugin system
│    ├── pulsar-ql/         # Query language implementation
│    ├── pulsar-ql-common/  # QL common components
│    ├── pulsar-resources/  # Resource management
│    ├── pulsar-spring-support/ # Spring integration
│    ├── pulsar-third/      # Third-party integrations (LLM etc.)
│    └── pulsar-tools/      # Dev & utility tools
├── pulsar-rest/            # REST API & services
├── pulsar-client/          # Client SDKs
├── pulsar-tests/           # Centralized tests
├── pulsar-examples/        # Examples & tutorials
├── browser4/               # Product-facing modules
│    ├── browser4-crawler/  # Web crawling
│    └── browser4-spa/      # SPA UI (React/TypeScript)
├── pulsar-bom/             # Bill of Materials (versions)
└── pom.xml                 # Root Maven config
```

Module guidelines:
- Each module defines its own `pom.xml`
- No cyclic dependencies
- Shared utilities → `pulsar-core/pulsar-common`
- Agentic/LLM-facing APIs → `pulsar-core/pulsar-agentic`
- AI client/utilities → `pulsar-third/pulsar-llm`

**Architecture Documentation:**
- [Agent Action and Tool Call Architecture](./docs/agent-action-architecture.md) - Unified action execution system

---

## 6. Kotlin & Java Interop

- Kotlin‑first for new public APIs; keep Java style when editing existing Java
- Prefer small, reversible migrations; centralize shared constants in Kotlin `object` or Java `final class`
- Keep public APIs documented with KDoc (what/why/constraints)

Kotlin code guidelines (highlights):
- Data classes + immutability; computed properties for derived data
- Explicit public return types; clear naming (bools with `is/has/can`)
- Use `require`/`check` for argument/state validation
- Avoid string interpolation in logs; placeholders only

---

---

## 7. Testing

For strategy, tags, base classes, coverage targets, and commands, see the detailed guide:
- [Testing Guide](./devdocs/copilot/test-guide.md)

---

## 8. Build & Run

Windows (cmd.exe):
```
mvnw.cmd -v
mvnw.cmd -q -DskipTests
```

Unix/macOS:
```
./mvnw -q -DskipTests
```

Multi‑module (build selected modules without tests):
```
./mvnw -pl pulsar-core -am -DskipTests package
```

If you fail to use a tool or run a command (such as `mvn`), refer to tool guide.

- [tool-guide.md](docs/copilot/tool-guide.md)

## 9. Security & Compliance

- No secrets/tokens/private endpoints in code or logs; use `${ENV_VAR}` or `<REDACTED>` placeholders
- Validate inputs: length/format/enums/ranges; avoid unbounded collections
- Serialization: Reuse project serializer settings; disallow dynamic reflection on external input
- External calls: Add timeouts/retries (if idempotent)/rate limiting as needed

---

## 10. Performance Notes

- Hot paths: Avoid deep decorator chains; inline or cache prudently
- I/O: Batch operations; reuse connections; reduce blocking (coroutines/Reactor as needed)
- Memory: Stream/process incrementally; avoid huge collections
- Metrics: Collect asynchronously; avoid blocking main flows

---

## 11. Benchmarks

Module: `pulsar-benchmarks` (excluded from deploy/release profiles)

Run:
```
./mvnw -pl pulsar-benchmarks -am package -DskipTests
java -jar pulsar-benchmarks/target/pulsar-benchmarks-*-shaded.jar -f 1 -wi 3 -i 5
```

Guidelines:
- Name: `<Domain><Operation>Benchmark`
- Avoid I/O; prepare data in `@Setup`
- Brief comment: goal + metrics + regression triggers
- No logging/I/O inside timed loop

---

## 12. Templates & Resources

Directory: `docs/copilot/templates/`
- `response-template.md` – Standard AI agent reply format
- `pr-description-template.md` – PR description scaffold
- For tag usage examples, see the [Testing Guide](./devdocs/copilot/test-guide.md)

Usage:
- New/changed PRs: Fill Summary / Motivation / Risk / Rollback
- Perf-sensitive changes: Attach temporary benchmark or justify skipping
- Flaky triage: Refer to the [Testing Guide](./devdocs/copilot/test-guide.md)

---

## 13. Quick Reference

| Item | Convention |
|----|------|
| Build | `./mvnw` entry point (Windows: `mvnw.cmd`) |
| Language | Kotlin-first; maintain Java interop |
| Logging | Placeholders, low-noise, no sensitive data |
| Dependency mgmt | BOM-unified; no duplicate versions |
| Config layering | `application*.properties` overrides |
| Docs | Public APIs need KDoc / README entries |
| Benchmarks | JMH in `pulsar-benchmarks` |
| Testing | See `devdocs/copilot/test-guide.md` |

---

## 14. Future Improvements (Tracking)

- Aggregate JaCoCo across modules → Automate delta report
- More domain benchmarks: DOM diff / selector matching / QL parsing / scoring heuristics
- Optional CI performance guard with thresholds
- Auto-generate test gap report (class vs test coverage skeleton)
- Flaky dashboard (frequency / first-seen)

---

Sync policy: This unified guide supersedes the two prior documents as of 2025‑10‑14. If adding new categories (e.g., Load/Stress), update this file and keep the per-module docs in sync.
