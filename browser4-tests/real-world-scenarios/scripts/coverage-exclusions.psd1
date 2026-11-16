# Commands that deliberately have NO real-world scenario.
#
# Keys are command names; a trailing '*' matches a whole family.  Every entry
# needs a reason — the coverage guard refuses to excuse a command silently, and
# `coverage.ps1` fails when a key matches no command at all (typo guard) or
# warns when a scenario starts covering one.
#
# The guard itself lives in coverage.ps1; the plan behind these decisions is
# docs-dev/copilot/real-world-scenario-cli-coverage-plan.md (§0, §8, §9).
@{
    # 23 hidden commands that form the agent-facing coding toolbox.  Review
    # decision (2026-08, §8 item 9): they stay an internal agent surface, are
    # documented only in the generated help, and are NOT required to appear in a
    # real-world scenario.  Functional coverage lives in the CLI e2e
    # "coding tool family" mock scenario.
    'code-*' = 'agent-internal coding toolbox (hidden); no real-world scenario required'

    # `star` needs a real, already-signed-in browser and waits for a HUMAN click
    # — a scenario cannot perform it.  This is the same decision the command
    # carries in cli/browser4-cli/src/commands.rs (E2eCoverage::Excluded); its
    # detection half is covered by Rust unit tests plus the
    # browser4-tests/.../static/b4/star-fixture.html fixture (BROWSER4_STAR_URL).
    'star' = 'opens a real signed-in browser and waits for a human click; detection covered by unit tests + b4/star-fixture.html'
}
