# fix: LLM config loading from ~/.browser4 + doctor UTF-8 encoding

Two bugs fixed in this commit — both discovered via `b4w.ps1 doctor` output after `agent run` returned null.

## Bug 1: LLM not configured → agent noops 5× → `agent result` returns null

**Symptom:** `.\b4w.ps1 agent run "介绍数学上的Li群"` completes with `status: OK` but `agent result <id>` returns `null`. Backend logs show 5 consecutive noop steps (`toolCall=null`) and a final `Summary generation failed - The LLM is not configured`.

**Root cause:** `application-private.properties` with valid DeepSeek API keys at `~/.browser4/config/conf-enabled/application-private.properties` was never loaded by Spring Boot. The JVM classpath is `lib/*` (JARs only), and no `spring.config.additional-location` pointed at the user's config directory.

**Fix:** `cli/browser4-cli/src/daemon.rs:3923-3934` — Before launching the backend JVM, pass `--spring.config.additional-location=file:<home>/.browser4/config/conf-enabled/` so Spring Boot scans the user's private config directory. Uses `resolve_default_state_dir()` which respects `BROWSER4_CLI_STATE_DIR`.

## Bug 2: Garbled Chinese/emoji in `doctor` command log output

**Symptom:** `doctor` shows `â–¶ï¸` instead of `▶️`, `ä»‹ç»æ•°å­¦ä¸Šçš„Liç¾¤` instead of `介绍数学上的Li群`.

**Root cause:** `DoctorController.tailLines()` used `RandomAccessFile.readLine()` which decodes bytes with the platform default charset (GBK on Chinese Windows), corrupting UTF-8 log content.

**Fix:** `browser4-rest/.../DoctorController.kt:253-310` — Replaced charset-dependent `readLine()` with explicit UTF-8 byte reading via `readUtf8Line()` and `skipPartialLine()` helper methods. Handles CR, LF, and CR+LF line endings.

## Files changed

| File | Change |
|---|---|
| `cli/browser4-cli/src/daemon.rs` | +11 lines: pass `--spring.config.additional-location` to JVM |
| `browser4-rest/.../controller/DoctorController.kt` | +50/-15 lines: UTF-8-aware log tail reader |

## Verification

- [x] `cargo check --bin browser4-cli` — clean
- [x] `cargo test --bin browser4-cli -- daemon` — 115 passed
- [x] `mvn test-compile -pl browser4-rest -am` — clean
- [ ] Rebuild runtime bundle + restart backend, then `b4w.ps1 agent run "test"` with LLM
- [ ] `b4w.ps1 doctor` shows correct Chinese/emoji in log output

#auto-approve
