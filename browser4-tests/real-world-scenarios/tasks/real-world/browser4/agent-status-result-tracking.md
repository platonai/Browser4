# agent-status-result-tracking

This scenario verifies the full `agent run` → `agent status` → `agent result` →
`agent list` lifecycle. It confirms that task tracking, status labels, result
retrieval, and task pruning all work correctly end-to-end.

If no LLM API key is configured, agent tasks will fail with a status like
"failed (417)" or similar — that is expected and part of what we are testing.
The key requirement is that every command produces correct, non-null output
with the standard lifecycle vocabulary.

1. Run `agent run "Navigate to https://en.wikipedia.org/wiki/Li_group and describe what a Lie group is"`.
   Note the task ID printed after "Task submitted:".

2. Immediately run `agent list`. Verify:
   - The task you just submitted appears in the list.
   - The TASK ID column matches the task ID from step 1.
   - The COMMAND column says "agent".
   - The STATUS column uses a standard lifecycle label: `queued`, `processing`,
     `completed`, or `failed (<code>)` — but NOT the old label `done`.

3. Run `agent status <task-id>` for the task from step 1. Verify:
   - The command exits with code 0.
   - The output is valid JSON.
   - `"statusCode"` appears as an integer (e.g. `200`, `417`, `102`), not a string.
   - `"processState"` is present (should be `"done"` once the task finishes).
   - `"isDone"` is a boolean.

4. Poll `agent status <task-id>` once every 3-5 seconds until `"isDone": true`
   or a `"failed"` status label appears. Wait at most 30 seconds.

5. Run `agent result <task-id>`. Verify:
   - The command exits with code 0.
   - The output is NOT the literal string `null` and is NOT empty.
   - If the task succeeded, the output contains a summary or page content.
   - If the task failed (e.g. no LLM key), the output contains a status message
     or failure reason — still not `null`.

6. Run `agent list` again. Since the task status should now be terminal
   (`completed` or `failed (...)`), verify:
   - The task appears in this `agent list` call with the terminal label.
   - The STATUS column shows `completed` or `failed (<code>)` — NOT `done`.
   - Terminal tasks persist across multiple `agent list` calls (no auto-prune).

7. Run `agent list` a third time. Verify:
   - The terminal task from step 1 still appears.
   - Tasks are NOT auto-pruned — they remain visible until explicitly cleared.

8. Submit two new agent tasks:
   ```
   agent run "summarize the key features of Rust"
   agent run "explain monads in functional programming"
   ```

9. Run `agent list`. Verify all three tasks appear (the original terminal
   task + two new ones).

10. Run `agent list --clear`. Verify:
    - The output confirms tasks were cleared (e.g. "Cleared N tracked agent task(s).").
    - The command exits with code 0.

11. Run `agent list` one final time. Verify:
    - "No tracked async tasks." is displayed (all tasks cleared).
