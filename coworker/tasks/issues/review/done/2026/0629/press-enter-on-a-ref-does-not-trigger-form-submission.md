# `press Enter` on a ref does not trigger form submission

**Severity:** High  
**Category:** Bug / Reliability

## Summary
Using `press Enter` on a focused input element inside a `<form>` does not trigger form submission. The page URL remains unchanged and no navigation occurs.

## Steps to Reproduce
1. Navigate to a page with a search form (e.g., `browser4-cli goto "https://www.amazon.com/"`)
2. Fill the search box: `browser4-cli fill <searchbox-ref> "search query"`
3. Press Enter on the same ref: `browser4-cli press Enter <searchbox-ref>`

## Expected Behavior
Pressing Enter on a focused search input inside a `<form>` should submit the form and navigate to search results.

## Actual Behavior
The URL remains unchanged. No navigation occurs. The user must separately locate and `click` the submit button as a workaround.

## Context
This was discovered while testing an Amazon search workflow. The Go button (`click <go-button-ref>`) had to be used instead. This is a fundamental interaction gap — pressing Enter to submit a form is basic web behavior expected by all users.

## Suggested Improvement
`press Enter` on an element inside a `<form>` should trigger form submission. At minimum, the documentation should explicitly warn that `press Enter` does not submit forms.

---

