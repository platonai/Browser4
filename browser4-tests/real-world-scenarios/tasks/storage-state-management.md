# storage-state-management

1. Go to `http://localhost:18080/generated/interactive-1.html` (MockSite interactive test page).
2. Set a cookie named "session_id" with a value of "abc123". Use `--domain localhost` and `--path /` to scope it correctly. Also set the `--httpOnly` and `--secure` flags.
3. Set a second cookie named "theme" with a value of "dark". Set `--sameSite Lax` and an `--expires` timestamp one week from now.
4. List all cookies to verify both were set.
5. Filter the cookie list to show only cookies for the domain "localhost".
6. Get the value of the "theme" cookie specifically to verify it is "dark".
7. Delete the "session_id" cookie.
8. List cookies again to confirm it was removed.
9. Clear all remaining cookies and verify the cookie jar is empty.
10. Set a localStorage item with key "user_prefs" and a JSON value like `{"lang":"en","tz":"UTC"}`.
11. List all localStorage items and get the value of "user_prefs" to verify it was stored correctly.
12. Delete the "user_prefs" key from localStorage, then clear all localStorage.
13. Set a sessionStorage item with key "visit_count" and value "1". List it, get it, then delete it, and finally clear all sessionStorage.
14. Set a test cookie again, then save the complete browser state to a file named `browser_state.json`.
15. Clear all cookies and localStorage.
16. Load the browser state from `browser_state.json` and verify the test cookie was restored.
17. Clean up by deleting `browser_state.json`.
