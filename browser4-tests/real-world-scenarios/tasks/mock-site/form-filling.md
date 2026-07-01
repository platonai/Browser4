# form-filling

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

1. Go to `http://localhost:18080/generated/form-filling.html`.
2. Take an interactive snapshot (`-i`) to discover the form fields and their reference labels.
3. Fill in each text input field with realistic values (e.g., name, email, address).
4. Select an option from any dropdown menus on the form.
5. Check at least two checkboxes.
6. Press Enter or click the submit button to submit the form.
7. Wait for the page to fully load after submission.
8. Use get text or get attr to verify the submission was successful — check for a confirmation message or check that submitted values appear in the result.
9. Take a full-viewport snapshot (`-v 0`) of the result page to document the outcome.
