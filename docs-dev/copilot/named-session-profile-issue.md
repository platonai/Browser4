---
title: "feat: named sessions should use dedicated named profiles instead of SEQUENTIAL rotation"
labels: enhancement
---

## Summary

Named sessions (`open --name team-a`, `--sessionId team-a`, …) are currently forced
into `SEQUENTIAL` browser profile mode. SEQUENTIAL rotates through a **bounded** pool
of profile directories, so once the pool is exhausted a new named session silently
reuses (and effectively overwrites) the profile of a *previous* named session —
wiping its cookies / localStorage / login state. Proposal: give each named session a
**dedicated, stable profile** keyed by its session identity, and address the resulting
disk-growth concern with a retention policy instead of profile rotation.

## Background

1. `PulsarSessionManager.normalizeCapabilities()` (`browser4-rest/.../session/PulsarSessionManager.kt`, ~L1020-1040)
   forces `profileMode = SEQUENTIAL` for **every** named session (any id that is not
   `DEFAULT` or the SWARM session). A requested `TEMPORARY` is even silently coerced
   back to `SEQUENTIAL` on this path.
2. `SEQUENTIAL` maps to `BrowserId.NEXT_SEQUENTIAL` (`AbstractPulsarSession.browserIdFor`,
   ~L79-85), which is a *getter*: every access computes a **fresh** context dir via
   `BrowserFiles.computeNextSequentialContextDir()` — the next slot of a rotating pool
   bounded by `MAX_SEQUENTIAL_PRIVACY_AGENT_NUMBER` (default ≥ 10,
   `SequentialBrowserProfileGenerator.computeMaxProfileCount`).
3. Each launch creates a brand-new Chrome process on that dir
   (`AbstractPulsarSession.createBoundDriver` → `browserManager.launch(mode)` →
   `PulsarBrowserLauncher.launch`). Driver pools are keyed by `BrowserId` and cache the
   browser, so same id → same instance, different id → different instance.
4. Session identity **is** stable: the display name → UUID mapping is persisted in the
   session registry (`PulsarSessionManager` registry file), so reopening `team-a`
   reliably resolves to the same session UUID — but the *profile underneath* is
   rotationally assigned and does not follow the session name.

## Problem

- **Silent state loss across sessions**: with a bounded pool, session `team-a` and
  session `team-b` will eventually land on the same profile directory at different
  times. Each new launch re-initializes/reuses that directory, so one session's
  cookies and login state clobber the other's. This is invisible to users until a
  login silently disappears.
- **Session identity is misleading**: the same named session can get *different*
  profiles over time (rotation advances between launches), while two *different*
  names can share one profile. The persistence guarantees of named sessions
  (per AGENTS.md/docs: named sessions keep their browser state) are not actually
  backed by a stable profile.

## Proposal

Introduce a dedicated **named profile** per session:

- A new profile mode (e.g. `NAMED` / `SESSION`) or a deterministic per-session
  `BrowserId` derived from the resolved session UUID (e.g. context dir
  `cx.<sessionUuid>`), set in `normalizeCapabilities` for named sessions.
- `AbstractPulsarSession.browserIdFor` maps it to that deterministic id, so the same
  named session always gets the same profile — across reopens and across server
  restarts (the name→UUID registry already persists).
- Different named sessions never collide; each keeps its own cookies/login state.
- Reuse the existing PROTOTYPE inheritance so profiles start from the same base
  (SEQUENTIAL/TEMPORARY already do this), avoiding per-profile Chrome defaults cost.

## Consideration: disk growth

One profile per named session, never reclaimed by rotation, will grow the on-disk
profile store. Mitigations to evaluate in the design:

1. **Retention cap with LRU eviction**: configurable `maxNamedSessions`; when
   exceeded, evict the least-recently-used profile (warn before reuse; archive
   instead of delete if feasible).
2. **Lazy materialization**: only create the profile directory on first browser
   launch of that session (Chrome creates the dir on demand); an unused named
   session costs nothing on disk.
3. **Prune/cleanup command**: a `browser4-cli` command (or REST endpoint) listing
   named-session profiles with sizes and a prune action — similar to the existing
   idle-reap machinery in `PulsarSessionManager`.
4. **Extend idle reaping**: sessions already reap after idle timeout; optionally
   also close + (configurably) archive the profile, keeping the last N.
5. **Disk accounting**: report per-session profile size in the session status output
   so growth is visible.

## Alternatives considered

- **Deterministic rotation (same name → same slot)**: keeps disk bounded but still
  lets two different names collide on one slot — does not fix the core problem.
- **TEMPORARY for named sessions**: fixes overwrite but discards state on close,
  defeating the purpose of a named/persistent session.
- **Keep SEQUENTIAL as default, make NAMED opt-in** via capability: reasonable
  rollout; named sessions that opt in get isolation, others keep current behavior.

## Related code

- `browser4-rest/.../rest/session/PulsarSessionManager.kt` — `normalizeCapabilities` (mode coercion), session registry (name → UUID).
- `browser4-core/.../skeleton/session/AbstractPulsarSession.kt` — `browserIdFor`, `createBoundDriver`.
- `browser4-core/.../skeleton/browser/privacy/BrowserProfileGenerator.kt` — `SequentialBrowserProfileGenerator` (bounded rotating pool).
- `browser4-core/.../protocol/browser/driver/WebDriverPoolManager.kt` + `LoadingWebDriverPool.kt` — pools keyed by `BrowserId`, one browser per pool.
- `docs/config.md` — `browser.profile.mode` semantics (DEFAULT shared / SEQUENTIAL rotating / TEMPORARY isolated).
