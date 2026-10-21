# attach-remote-debug

This scenario requires a running Chrome or Edge browser instance with remote debugging enabled. To set this up:
- Start Chrome with `--remote-debugging-port=9222`
- Or start Edge with `--remote-debugging-port=9222`
If neither is available, attempt the commands and document the outcome as a usability finding.

1. Attempt to attach to a running Chrome browser via its CDP channel: use `attach --cdp chrome`. If Chrome is not available, try `attach --cdp msedge` for Edge, or specify a direct CDP URL like `http://localhost:9222`.
2. If the attach command supports an `--endpoint` flag to connect through a remote Browser4 server, attempt to use `attach --endpoint` with the server URL (combine with `--cdp` if the documentation indicates this is supported).
3. Once attached, list the tabs/sessions that the connected browser already has open.
4. Take a screenshot of the current page in the attached browser to verify the connection works.
5. Take a snapshot of the current page to see its accessibility tree.
6. Save the browser state from the attached session to preserve cookies and storage.
7. If multiple tabs are available, switch between them and take screenshots of each.
8. When done, close the attached session with `disconnect` — the alias of `close` that `attach.md` documents for attached sessions. It must resolve as a real command (not fail with "Unknown command"), and it must detach rather than kill the external browser: verify the browser is still running and re-attachable afterwards.
9. Record whether `disconnect` and `close` produced the same output and exit code for the attached session, and whether anything in `help` pointed at the alias.
