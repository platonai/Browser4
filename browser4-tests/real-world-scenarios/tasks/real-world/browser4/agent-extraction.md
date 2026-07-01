# agent-extraction

This scenario requires a configured LLM API key (DeepSeek, OpenAI, or another supported provider). If no key is configured, attempt the commands anyway and document the error as a usability finding.

1. Go to `https://en.wikipedia.org/wiki/Python_(programming_language)`.
2. Use the extract command to pull structured data from the page. Request the following fields as a JSON schema: programming language name, first release year, developer, typing discipline, and license.
3. Use the extract command again, this time with a custom JSON schema file specifying exactly the fields and types you want. Save the extracted results.
4. Use the summarize command to get a concise summary of the entire page.
5. Use summarize with `--selector` to summarize only the "History" section of the article.
6. Submit an autonomous agent task: ask the agent to navigate to `https://en.wikipedia.org/wiki/Guido_van_Rossum`, extract key biographical details, and return them. Note the task ID.
7. Poll the agent task status periodically until it completes or fails.
8. Retrieve and review the agent task results.
9. Compare the synchronous extract/summarize approach with the asynchronous agent approach — which is more suitable for which type of task?
