# loop-monitoring

1. Use the loop command to run a plain-text task that navigates to `https://httpbin.org/get`, takes a snapshot, and reports the page status. Run it `--count 2` times with `--interval 10` seconds. Give it a `--name health-check`.
2. While the first loop runs, list all active loops to see the named loop in the list.
3. Check the status of the "health-check" loop to see its progress.
4. Use the loop command with `--shell` mode to run a simple shell command (e.g., printing the current time) twice with a 5-second interval. Give it a `--name shell-test`.
5. Use the loop command with `--` followed by a browser4-cli subcommand to check the server status endpoint repeatedly. Run it twice with a 5-second interval. Give it a `--name cli-test`.
6. List all loops again — you should see three named loops (one of which may have already completed).
7. Create a new loop (named "paused-test") with `--pause` to start it in a paused state. List loops and check its status to verify it starts paused.
8. Resume the "paused-test" loop, then pause it again to verify pause/resume works.
9. Stop the "paused-test" loop to end it early.
10. Create a loop with `--timeout 30` (seconds) to test the maximum duration feature. Wait for it to complete and verify it stopped after the timeout.
11. Use `--stop-all` to ensure no orphaned loops remain running.
12. List loops one final time to confirm cleanup was successful.
