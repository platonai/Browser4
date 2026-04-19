# Monthly Memory – 2026-04

## Summary of daily memories (excluding 2026-04-15)

### 2026-04-14

**Task 1: Split Long E2E Tests in e2e.rs**
Split 6 slow e2e scenario functions (>30s each) into 12 smaller ones. Splits: `test_session_lifecycle` → open+list / close; `test_navigation_and_storage` → navigation / storage; `test_interaction_commands` → typing / keyboard+pointer; `test_form_controls_and_exports` → controls / exports; `test_mouse_and_dialog` → mouse / dialog; `test_tab_commands` → list+new / select+close. `SCENARIOS` const grew from 9 to 15 entries. First-half scenarios leave sessions open; second-halves continue them with `restart_browser4: false`.

**Task 2: Fix E2E Kill Warnings and test_storage Failure**
- Added `.stderr(Stdio::null())` to `force_stop`'s `kill -KILL` call so "No such process" noise is suppressed.
- Removed per-scenario `kill_all_browsers()` from the main loop; it now only fires inside `run_named_scenario` when `restart_browser4 == true`, preventing Chrome from being killed between linked split-tests that share an open session.

---

*This file will be updated with summaries from each day as the month progresses.*
