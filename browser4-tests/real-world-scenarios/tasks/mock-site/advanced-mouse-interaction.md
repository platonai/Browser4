# advanced-mouse-interaction

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

1. Go to `http://localhost:18080/generated/interactive-5.html`.
2. Take an interactive snapshot to discover all the interactive elements on the page (tooltips, cards, draggable items, buttons, double-click zones).
3. Hover over the underlined tooltip terms ("Accessibility Tree", "DOM Snapshot") to trigger the hover tooltips. Take a snapshot to verify the tooltip content is visible.
4. Hover over the product card ("Wireless Headphones") to trigger the hover card expansion. Verify the card detail text appears.
5. Use drag to reorder the priority list items — drag "High Priority" to the bottom of the list or "Backlog" to the top.
6. Double-click the "Double-click this area" zone to activate it. Verify the status changes to "ACTIVATED" and the double-click counter increments.
7. Double-click the "Double-click here to reset all counters" zone to reset the counters.
8. Use generate-locator on one of the dialog trigger buttons to produce a resilient CSS selector.
9. Use the generated selector with get text to retrieve the button's label.
10. Click the "Show Alert" button to trigger a browser alert dialog. Use dialog-accept to dismiss it and verify the dialog result area updates.
11. Click the "Show Confirm" button. Use dialog-accept or dialog-dismiss to handle it, then check the result in the dialog output area.
12. Click the "Show Prompt" button. Handle the prompt dialog (accept with input or dismiss), then check the result.
13. Take a final screenshot to capture the page state, including the interaction log at the bottom.
