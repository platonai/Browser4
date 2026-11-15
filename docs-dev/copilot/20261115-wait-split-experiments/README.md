# Wait-split experiments — raw evidence (2026-11-15)

The data behind
[the parallel-work evaluation](20261115-agent-parallel-work-evaluation.md) §11 (C1 A/B) and
§12 (B1 browser buckets). The tables in that document are the summary; these are the files
they were computed from, so anyone can recompute them instead of trusting a table.

Everything here was produced by the two scripts in this directory against a real backend
built from source (`b4w.ps1`), a real model (DeepSeek), a real Maven, and headless Chrome.
No mocks.

## Layout

```
ab-c1/    the C1 speculative-build A/B: 5 baseline + 5 speculative, result JSON + run log each
b1/       the B1 browser bucket split: 3 browser tasks, result JSON + run log + the task prompt each,
          plus task4-after-shell-bypass-* (the same task re-run to check a later change; see the
          evaluation §12.7 for why its wall clock is NOT a before/after comparison)
ab-fixture/  the throwaway two-module Maven project the A/B ran against (seed state)
ab-run.ps1   runner for ab-c1 (arm switch, workspace reset, result extraction)
b1-run.ps1   runner for b1
```

`ab-fixture/` is the seed the A/B workspace was created from, copied verbatim (it was a git
repo with exactly one commit, `seed: two-module maven project`, which is the state every
attempt was reset to). To rebuild the workspace:

```powershell
$ws = 'D:\tmp\b4-ab'
Copy-Item -Recurse docs-dev\copilot\20261115-wait-split-experiments\ab-fixture\* $ws
git -C $ws init; git -C $ws add -A
git -C $ws -c user.name=ab -c user.email=ab@example.com commit -m "seed: two-module maven project"
mvn -q -pl app -am compile   # warm the plugin downloads once, so both arms see the same cache
```

`*-result.json` is `browser4-cli agent result <id> --json --allow-failed`, i.e. the CLI
envelope whose `output.raw` field holds the backend's own JSON. The numbers read in the
document are `output.raw | fromjson | .result.timings`. `*-run.log` is the `--follow`
transcript of the same task (tool calls, per-call durations, the final answer).

## How the two experiments differ

**C1 A/B (`ab-c1/`)** — one fixed prompt, run five times per arm:

> Run coding.mvnBuild with module "app" and goals "compile" … then edit
> app/src/main/java/demo/App.java … build again … then coding.verify.

- Workspace: a throwaway two-module Maven project (`demo:ab-parent` → `lib` + `app`, Java 17)
  outside this repository, reset to its seed commit before every attempt, so each run starts
  from the same cold worktree.
- The only variable is `-Dbrowser4.agent.build.speculative=<false|true>` in
  `BROWSER4_SERVER_OPTS`. One backend JVM per arm: it is a system property, not hot-reloadable.
- Arms were interleaved (B1 B2 S1 S2, then S3 B3 S4 B4 S5 B5) so a daytime slowdown cannot
  land on one arm only.

**B1 (`b1/`)** — three *different* browser tasks (single-page extract, multi-step navigation,
form fill+submit), one run each, against `example.com`, `browser4.io` and the Selenium form
fixture. No arm switch; the browser buckets had simply never been measured.

## Reproducing

```powershell
# C1 A/B — one arm per JVM
$env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User')
$env:BROWSER4_SERVER_OPTS = "-Dbrowser4.agent.workspace=<throwaway-repo> " +
    "-Dbrowser4.agent.finishGateCheck=warn -Dbrowser4.agent.build.speculative=false"
pwsh -File docs-dev/copilot/20261115-wait-split-experiments/ab-run.ps1 -Arm baseline -Attempt 1

# B1
pwsh -File docs-dev/copilot/20261115-wait-split-experiments/b1-run.ps1 -Index 1 `
    -TaskFile docs-dev/copilot/20261115-wait-split-experiments/b1/b1-task1.txt
```

The scripts are the ones that produced the archived files, edited only to point at the
relative task files (they originally read them from `D:\tmp`).

## Four traps this cost, all of them re-usable knowledge

1. **`-D` with a dotted key needs quoting on Windows PowerShell.** `-Djacoco.skip=true`
   reaches Maven as `-Djacoco` + `.skip=true` (`Unknown lifecycle phase ".skip=true"`). Write
   `-D"jacoco.skip=true"`.
2. **`-Dtest` takes commas, not `+`.** `-Dtest=A+B+C` matches nothing and surefire fails with
   "No tests matching pattern".
3. **The backend inherits the caller's stdout handle.** A script that runs several attempts in
   a loop deadlocks on the first pipe, because the JVM keeps it open after the run finishes.
   Both runners therefore stop the server when they are done (which also does the arm switch).
4. **The first real run found a unit bug in the code under test** — `timings.phases` reported
   nanoseconds while every field is named `*Ms` (389 days for a 33 s task). Fixed in
   `38987ce990`; §11.4 of the evaluation records what the tests had missed.

## Environment

Windows 11, 20 cores, JDK 25 (GraalVM), Maven 3.9.16 via `mvnw`, headless Chrome, backend
`4.16.8-SNAPSHOT` built from the checkout at the time of the runs (`b5da0ac4eb`..`38987ce990`).
