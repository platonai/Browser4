# `extract` command silently returns metadata-only response on complex prompts

## Summary
The `extract` command can return a `success: true` response containing only a `metadata` object with no `content` field, despite the prompt requesting detailed extraction. This silent partial failure gives the user no indication that the AI agent produced no data.

## Steps to Reproduce
1. Navigate to a search results page (e.g., Amazon)
2. Run `browser4-cli extract "get the first 4 search results with title, price, rating, and link"` — succeeds with full `content` and `links` fields
3. Run `browser4-cli extract "For each of the first 4 search results, extract: title, price, star rating, number of reviews, product link, and a brief description"` — returns `{metadata: {...}}` with no `content`
4. Inspect the output file and note `success: true` but no actual data

## Expected Behavior
If the AI agent cannot produce extracted content, the command should return an error, a warning, or at minimum an empty `content` array — not silently omit the field. The user should be able to distinguish "no data found" from "the agent failed to produce output."

## Actual Behavior
The response file contains `success: true` but lacks the `content` field entirely. The CLI reports success with a file path, and the user only discovers the missing data after opening the file. This is a **silent partial failure**.

## Suggested Fix
Add server-side validation that the `content` field is present in the AI agent's response before returning success. If `content` is missing or empty, surface a warning to the user and investigate why longer or more detailed prompts cause the agent to produce no extraction.

Labels: bug, reliability, high
