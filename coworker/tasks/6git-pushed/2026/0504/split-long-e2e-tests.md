# Improve e2e.rs

Split tests that cost more than 30s into smaller tests:

```
     Running tests/e2e.rs (target/debug/deps/e2e-db4b30daf3cdf46f)
running 10 tests
test test_e2e_command_coverage ... ok (0.00s)
test test_e2e_session_lifecycle ... ok (33.33s)
test test_e2e_navigation_and_storage ... ok (56.25s)
test test_e2e_interaction_commands ... ok (52.80s)
test test_e2e_form_controls_and_exports ... ok (56.78s)
test test_e2e_mouse_and_dialog ... ok (89.56s)
test test_e2e_tab_commands ... ok (46.77s)
test test_e2e_collective_session_and_agent_tools ... ok (1.41s)
test test_e2e_agent_task_commands ... ok (1.35s)
test test_e2e_collective_submission_commands ... ok (2.14s)
test result: ok. 10 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
per-test timing:
  test_e2e_command_coverage: 0.00s
  test_e2e_session_lifecycle: 33.33s
  test_e2e_navigation_and_storage: 56.25s
  test_e2e_interaction_commands: 52.80s
  test_e2e_form_controls_and_exports: 56.78s
  test_e2e_mouse_and_dialog: 89.56s
  test_e2e_tab_commands: 46.77s
  test_e2e_collective_session_and_agent_tools: 1.41s
  test_e2e_agent_task_commands: 1.35s
  test_e2e_collective_submission_commands: 2.14s
```
