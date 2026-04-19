# Fix bug

```
     Running tests/e2e.rs (target/debug/deps/e2e-db4b30daf3cdf46f)
running 16 tests
test test_e2e_command_coverage ... ok (0.00s)
test test_e2e_session_open_and_list ... ok (18.43s)
kill: (167563): No such process
kill: (167578): No such process
kill: (167579): No such process
kill: (167601): No such process
kill: (167603): No such process
kill: (167614): No such process
kill: (167657): No such process
kill: (167658): No such process
test test_e2e_session_close ... ok (3.54s)
test test_e2e_navigation ... ok (35.57s)
kill: (168043): No such process
kill: (168058): No such process
kill: (168059): No such process
kill: (168082): No such process
kill: (168084): No such process
kill: (168097): No such process
kill: (168135): No such process
kill: (168136): No such process
kill: (168164): No such process
test test_e2e_storage ... 
thread 'main' (167116) panicked at tests/e2e.rs:933:5:
assertion `left == right` failed: Command ["delete-data"] failed (exit=1):
stdout:

stderr:
Error: ERROR: delete_session_data failed: Channel was cancelled

  left: 1
 right: 0
note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace
error: test failed, to rerun pass `--test e2e`

```
