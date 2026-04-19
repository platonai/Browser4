# Fix bugs

```
     Running tests/e2e.rs (target/debug/deps/e2e-db4b30daf3cdf46f)
running 15 tests
test test_e2e_command_coverage ... ok (0.00s)
test test_e2e_session_lifecycle ... ok (11.47s)
test test_e2e_navigation_and_storage ... ok (54.91s)
test test_e2e_interaction_commands ... ok (42.94s)
test test_e2e_batch_commands ... ok (39.65s)
kill: (194139): No such process
kill: (194154): No such process
kill: (194155): No such process
kill: (194177): No such process
kill: (194179): No such process
kill: (194191): No such process
kill: (194231): No such process
kill: (194251): No such process
test test_e2e_batch_form_submission ... ok (35.65s)
kill: (194830): No such process
kill: (194845): No such process
kill: (194846): No such process
kill: (194868): No such process
kill: (194870): No such process
kill: (194881): No such process
kill: (194923): No such process
kill: (194943): No such process
test test_e2e_batch_multi_interaction ... ok (37.01s)
kill: (195480): No such process
kill: (195495): No such process
kill: (195496): No such process
kill: (195518): No such process
kill: (195520): No such process
kill: (195531): No such process
kill: (195571): No such process
kill: (195592): No such process
test test_e2e_batch_error_handling ... 
thread 'main' (191675) panicked at tests/e2e.rs:1292:5:
Timed out waiting for interactive state. Last state:
Object {
    "checkbox": Bool(false),
    "clickCount": Number(0),
    "confirmResult": String(""),
    "doubleClickCount": Number(0),
    "dragDropped": String(""),
    "dragStarted": Bool(false),
    "fillValue": String("after error"),
    "hovered": Bool(false),
    "keyEvents": Array [],
    "lastMouse": Null,
    "lastWheel": Null,
    "mouseDownCount": Number(0),
    "mouseUpCount": Number(0),
    "promptResult": String(""),
    "selectValue": String(""),
    "submitCount": Number(0),
    "typeValue": String("before errorbail test"),
    "uploadCount": Number(0),
    "uploadName": String(""),
}
note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace
error: test failed, to rerun pass `--test e2e`
```

