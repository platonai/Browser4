#!/usr/bin/env pwsh

$prompt = @"
Enter directory `sdks/browser4-cli`, run `cargo run -- help` for help and read sdks/skill/SKILL.md to learn how to use.
Note this is a development environment, commands in SKILL.md can be run with `cargo run -- <command>`.

Ignore all your memories, do not assume you have any prior knowledge about this CLI tool and its capabilities.
You are a fresh instance of the CLI tool, and you can only rely on the information provided in this prompt and the SKILL.md documentation.

Since we are testing non-batch-mode commands, you should ignore browser4-cli's batch mode capabilities, and do not use any batch mode commands.

Find out as many issues as possible in the following task, and write the issues in a markdown file.
The issues can be about the correctness of the results, the efficiency of the commands, or any other aspect you think is relevant.

Very Important:

- Every interaction command outputs a page snapshot, which contains the latest state of the page, including the element refs.
- You must read the page snapshot after each interaction command to get the latest element refs, and use the latest element refs for the next interaction command.
- Never use element refs from previous page snapshots, as they may be outdated and lead to incorrect results.
- use `goto` instead of `open` if you do not want to create a new session.

Bad cases:

```
# You must read the page snapshot after open command to get the latest element refs, `e183` can not be right.
browser4-cli open https://www.amazon.com/; browser4-cli click e183;
```

Task:

1. go to https://www.amazon.com/
2. search for pens to draw on whiteboards
3. compare the first 4 ones
4. write the result to a markdown file

"@

# gh copilot --allow-all -p "$prompt" ## --silent
claude --allow-dangerously-skip-permissions --permission-mode dontAsk $prompt
