# Fix bugs

```
     Running tests/e2e.rs (target/debug/deps/e2e-db4b30daf3cdf46f)
running 15 tests
test test_e2e_command_coverage ... ok (0.00s)
test test_e2e_session_lifecycle ... ok (24.21s)
test test_e2e_navigation_and_storage ... ok (55.70s)
test test_e2e_interaction_commands ... ok (47.29s)
test test_e2e_batch_commands ... ok (55.91s)
test test_e2e_batch_form_submission ... 
thread 'main' (209156) panicked at tests/e2e.rs:1118:5:
assertion `left == right` failed: Command ["batch", "open http://127.0.0.1:36149/form", "fill #first-name 'Alice'", "fill #last-name 'Johnson'", "fill #email 'alice@example.com'", "select #country us", "check #agree-terms", "fill #comments 'batch test comment'", "click #submit-btn"] failed (exit=1):
stdout:
Session opened: b7276d33-3e5a-4dbd-9b61-a7a637b902ba
[us]

stderr:
Batch command 1 failed (open http://127.0.0.1:36149/form): Cannot find context with specified id
Error: 1 batch command(s) failed.

  left: 1
 right: 0
note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace
error: test failed, to rerun pass `--test e2e`
```
