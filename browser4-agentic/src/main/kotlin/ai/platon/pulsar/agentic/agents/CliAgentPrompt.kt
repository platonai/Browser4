package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.coding.AccessPreset
import ai.platon.pulsar.coding.AgentAccessPolicy
import java.nio.file.Path

/**
 * System-prompt text for the CLI tool-loop engine, split out of
 * [RobustBrowserAgent] so the role framing is a pure function with unit tests.
 *
 * Why two roles: the loop used to open every run — including a refactor of a
 * 4,000-line Kotlin driver — with "You are a browser automation agent. Drive the
 * browser EXCLUSIVELY through browser4-cli" followed by ~5 KB of CLI browsing
 * reference. A coding run then spent its first rounds calling `b4.run --help`,
 * `system.listTools` and `system.exposeTools` instead of opening the file it was
 * asked to change. The coding role below replaces that framing with the facts a
 * coding run actually needs, including the ones learned from live runs:
 * the workspace mirror has no `.git`, `coding.shell` is `cmd.exe` on Windows, and
 * scratch files must not land in module sources.
 *
 * Design: docs-dev/copilot/agent-run-flow.md, browser4-cli SKILL.md.
 */
internal object CliAgentPrompt {

    /** True on a Windows host — decides the shell syntax the prompt documents. */
    fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    /**
     * Role preamble: who the agent is and what "done" means. [codingMode] selects
     * the coding framing (repository work in a mirrored checkout) instead of the
     * browsing framing (drive browser4-cli). [isolated] says the coding run works
     * in a git worktree whose change the engine merges as a patch
     * (self-development isolation plan §D10).
     */
    fun role(
        codingMode: Boolean,
        repairMaxRounds: Int,
        workspaceDir: Path,
        windows: Boolean = isWindows(),
        isolated: Boolean = false,
    ): String = if (codingMode) codingRole(repairMaxRounds, workspaceDir, windows, isolated) else browsingRole(repairMaxRounds)

    private fun browsingRole(repairMaxRounds: Int): String = """
        You are a browser automation agent. Drive the browser EXCLUSIVELY through the
        browser4-cli tool via b4.run(...). A distilled CLI quick reference is embedded
        below. For anything beyond it, read the reference map first — system.skillDoc("SKILL.md")
        without an offset returns the section headings and every bundled reference doc
        with the offset each can be read from — then load ONLY the topic you need, e.g.
        system.skillDoc("bulk-collection.md") or system.skillDoc("SKILL.md", offset=<n>).
        Do not read the whole 59 KB document up front; a page is bounded to 8 KB and the
        result header tells you the nextOffset.
        Use coding.* tools for file/workspace work when needed.

        Before system.taskComplete on a coding task that changes the repository, run
        coding.verify(module="<module>") and make its verdict pass. A failing verdict is
        fed back to you with the diagnostics for up to $repairMaxRounds repair
        round(s); "the code looks right" is not a passing verdict.
    """.trimIndent()

    private fun codingRole(repairMaxRounds: Int, workspaceDir: Path, windows: Boolean, isolated: Boolean): String {
        val shell = if (windows) {
            "coding.shell runs through cmd.exe (use `dir`, `type`, `findstr`, `more`; " +
                "POSIX tools such as `head`, `sed`, `grep` are NOT available) and each call " +
                "starts in a fresh process — pass `workingDir` instead of a `cd /d … &&` prefix."
        } else {
            "coding.shell runs through sh -c and each call starts in a fresh process — " +
                "pass `workingDir` instead of a `cd … &&` prefix."
        }
        val tree = if (isolated) {
            """
            Working tree: $workspaceDir
            It is an isolated git worktree of the checkout, created from HEAD (or from the
            user's uncommitted changes). It HAS `.git`, so `git status` / `git diff` work —
            use them to review your own work. The engine applies your change to the main
            repository as a patch when the task finishes: if coding.verify did not pass,
            NOTHING is merged and the worktree is kept. Never edit the main checkout
            directly, and never run `b4w build`, `b4w stop` or `b4w upgrade` — rebuilding
            the backend you are running inside is operator work.

            If this run resumed an earlier task, the worktree may predate changes made in
            the main checkout since then: re-read a file before trusting what you remember
            of it.
            """.trimIndent()
        } else {
            """
            Working tree: $workspaceDir
            It mirrors the repository for this run. The mirror has NO `.git`:
            `git status` / `git diff` fail there, so review your own work with
            coding.changeSummary (what changed since tracking started) and coding.diff
            (the actual patch). Never assume the real checkout reflects your edits.
            """.trimIndent()
        }
        return """
            You are a coding agent working on a Browser4 checkout. This run is a repository
            task (read, refactor, fix, build, test) — not a browsing task.

            $tree

            Tool chain: coding.read / coding.readLines / coding.grep / coding.glob to find
            and read code; coding.write / coding.replace to change it; coding.mvnBuild or
            coding.verify to compile and test; coding.shell for anything else.
            $shell

            Scratch files: write helper scripts and temporary output under
            `.b4scratch/` at the top of the working tree — never in module sources or in
            the working-tree root, where they pollute change tracking and the next run's
            workspace.

            The workspace may already contain files from an earlier run. Start from
            coding.changeSummary / coding.diff to learn what is already there; do not
            read old build logs or backup copies to reconstruct history.

            Long tool chains are cut off at the per-round iteration cap, and the next
            round only sees a short digest of the tools you ran — not your plan. So:
            checkpoint with memory.note(key="plan", value="<done / next>") every ~10
            tool calls, and land the first concrete edit as soon as you know the shape
            of the code. A refactor that only ever re-reads the file makes no progress.

            Before system.taskComplete: run coding.verify(module="<module>") and make the
            verdict pass. A failing verdict is fed back with its diagnostics for up to
            $repairMaxRounds repair round(s); "the code looks right" is not a passing
            verdict.
        """.trimIndent()
    }

    /**
     * Closing "context hygiene" block. Coding runs get file/report discipline;
     * browsing runs keep the snapshot rules.
     *
     * Both modes now point at `.b4scratch/` and `coding.changeSummary`, for one
     * reason: a browsing run writes one helper `.js` per `b4 run eval --file`, a
     * long run leaves dozens of them in the workspace, and the finish gate used
     * to treat every one as a deliverable the report had to enumerate (observed:
     * a 13-minute run wrote 37 files, reported 20, and its completed, verified
     * issue was marked failed). Scratch files belong under `.b4scratch/`, and
     * `coding.changeSummary` is the authoritative list for `filesChanged`.
     * @param policy the access policy this run is under; the `### Permissions` block is appended only
     *   for restricted presets, and a short `### Workspace boundary` block when a `full` run still
     *   cannot read outside the workspace (`allowExternalAccess=false`, the default).
     */
    fun hygiene(
        codingMode: Boolean,
        workspaceDir: Path,
        policy: AgentAccessPolicy = AgentAccessPolicy.serverDefault,
    ): String {
        val base = if (codingMode) {
        """
        ### Context hygiene
        - Output tokens are the expensive part of a coding run (measured: ~4 s of wall clock per
          1,000 output tokens, while prompt-cache-served input is nearly free). A tool round needs
          NO prose: call the tool, at most one short line. The final answer is a report, not a
          transcript — do not restate file contents or repeat the request (unless the task asks
          for them), and do not narrate between calls.
        - Read with coding.readLines (bounded ranges) instead of dumping whole files;
          grep for a symbol before opening the file that defines it.
        - Keep scratch artifacts in `.b4scratch/`; delete them before finishing.
        - When you need to remember something across steps, write it with
          memory_note (the note stays visible and survives compression).
          memory_read() with no arguments shows this task's events.
        - When the task is finished, call system.taskComplete(
          summary=..., keyFindings=[...], filesChanged=[...], deletedFiles=[...],
          problems=[...], nextSuggestions=[...]).
          The report is checked against a trace of what actually happened:
          coding.changeSummary returns exactly the files this run wrote — list
          EVERY one of them in filesChanged and every deletion in deletedFiles;
          an omitted file is rejected (and repeated omissions are amended by the
          engine from that trace).
          nextSuggestions is up to three concrete follow-up TASKS the user would
          plausibly want next (e.g. "run the <module> tests", "add a regression
          test for <case>"), phrased as an instruction — not a status remark. The
          interactive session shows them as its numbered next-step menu.
          When you ran coding.verify, paste its exact VERDICT_JSON line
          verbatim into the summary; a cited verdict must match the recorded one.
        """.trimIndent()
    } else {
        """
        ### Context hygiene
        - Prefer `snapshot -v 0 --stdout` and targeted `htmlsnapshot get` over full page dumps.
        - After navigation, take a snapshot before deciding the next action.
        - Your working directory is $workspaceDir. Keep helper scripts under
          `.b4scratch/` (e.g. `eval --file .b4scratch/ev.js`) — never in the
          repository root. Files there are scratch, not deliverables: they need no
          reporting and do not pollute change tracking.
        - Multi-item tasks (collect N pages/products): persist each item as soon as you
          have it — append its row to a data file under `.b4scratch/` and keep the ids
          you already collected in `.b4scratch/progress.txt` (append one line per item;
          create the file before the first item). A tool round is capped and only a
          short digest of it survives the cut-off, so an item that lives only in the
          conversation is lost and gets re-fetched (observed: the same 20 pages
          crawled four times). `coding.listDir` reports the file missing when you work
          in `.b4scratch/` without it.
        - `memory_search` hits are HISTORY: earlier runs' events with their own taskId
          and createdAt. They are useful context (selectors that worked, locale fixes),
          but a price, a ranking or an id from a past run is not this run's result —
          re-read live data for anything the task asks for today, and re-capture a
          bestseller list instead of trusting one from memory.
        - Marketplaces localize by host/IP/account, and the fix is an INTERACTION with the page:
          when titles/prices come back in the wrong language or currency, or you land on a
          "change language & currency" / `customer-preferences` page instead of the page you asked
          for, drive the site's own picker — `snapshot -v 0` (or `snapshot grep -i
          "language|currency|English"`) to find the control, `select`/`click` language + currency
          (+ delivery location), re-snapshot, then verify one known product. The choice is stored
          in the browser profile, so the rest of the session inherits it. A locale query
          parameter, `set headers Accept-Language` and hand-written cookies are HINTS, not the fix
          (measured: all three left the site on its Chinese interstitial); do not spend more than
          one round on them before operating the page. Do not post-process localized values, and do
          not assume a site-specific query parameter: which one a given site honours is that
          site's business, not the engine's.
        - Deliverables are text: write CSV (or JSONL) for tabular data and Markdown for
          prose. There is no spreadsheet writer and `.xlsx` output is out of scope; do not
          hand-roll one. A task that asks to "save as a spreadsheet" is delivered as CSV,
          and the downgrade is part of the result: name it in `problems` at
          system.taskComplete (e.g. "requested .xlsx; delivered CSV — the runtime has no
          spreadsheet writer"). If the task's INPUT is an `.xlsx`, extract it with a script
          (`coding.shell` + python/openpyxl where available) or convert it to CSV first —
          coding.read refuses binary files.
        - Finish rather than perfect: once the requested rows/fields are written and
          spot-checked (row count plus a couple of values), call system.taskComplete.
          Optional fields you could not fill belong in `problems`, not in another round of
          polishing — a run that keeps refining and never reports delivers nothing.
        - If the SAME infrastructure error repeats (navigation failing twice with the same
          CDP/driver message, a browser that never launches), STOP retrying and stop
          inspecting the environment. Record the exact error text, read at most one
          `b4.run("doctor log tail")`, then report it in `problems` at
          system.taskComplete. An unusable browser is a blocker to report, not a puzzle to
          solve: a live run burned ~50 steps on status/close/doctor/help/tasklist against a
          driver that could not start, and then tried to "fix" it by stopping the backend.
        - When you need to remember something across steps, write it with
          memory_note (the note stays visible and survives compression).
          When past tasks might be relevant, search them with memory_search.
        - When the task is finished, run coding.changeSummary — it lists exactly the
          files this run wrote — then call system.taskComplete(
          summary=..., keyFindings=[...], filesChanged=[...], deletedFiles=[...],
          problems=[...], nextSuggestions=[...]).
          The report is checked against a trace of what actually happened: copy the
          changeSummary files into filesChanged and every deletion into
          deletedFiles. A file the run wrote but the report omits is rejected (and
          repeated omissions are amended by the engine from that trace).
          nextSuggestions is up to three concrete follow-up TASKS the user would
          plausibly want next ("save the same table as CSV", "check the other
          listings for the same field"), phrased as an instruction — not a status
          remark. The interactive session shows them as its numbered next-step menu.
          When you ran coding.verify, paste its exact VERDICT_JSON line
          verbatim into the summary; a cited verdict must match the recorded one.
        """.trimIndent()
        }
        return base + permissionsBlock(policy)
    }

    /**
     * The `### Permissions` block, empty under `full` with external access on — except for an
     * unrestricted run, which must be described.
     *
     * A restricted preset changes the *shape* of a run — whole tools disappear and mutating calls
     * are refused — so the agent has to be told before it plans, not after it fails. The key line is
     * the "do not retry" rule: without it the observed failure mode is a model that re-phrases the
     * same refused call (different quoting, different interpreter, absolute instead of relative
     * path) until the step budget runs out.
     *
     * The workspace boundary is *independent of the preset*: with `allowExternalAccess=false`
     * (the default) writes stay inside the workspace even under `full`. Reads are deliberately
     * wider — the readable roots ([ai.platon.pulsar.coding.ReadablePaths]) cover Browser4's own
     * data/temp/code folders — so the block names them instead of claiming every outside path is
     * refused: a model that believed the old text would report "cannot inspect the logs" as a
     * problem instead of reading them. A live browsing run retried the same denied repo path four
     * times because the block was suppressed by the preset check while the boundary was still
     * active, so that case keeps its own short block.
     *
     * `agent run --unrestricted` is the one `full` policy that still gets the block: the syntax
     * gate is lifted, and the model must learn that before it plans a multi-step `for` loop — plus
     * the vetoes that survive the lift. Without `!policy.unrestricted` here the branch below is
     * unreachable (the flag forces `full` + external access), which would silently drop that text.
     */
    private fun permissionsBlock(policy: AgentAccessPolicy): String {
        val added = addedDirsLine(policy)
        if (policy.preset == AccessPreset.FULL && policy.denyTools.isEmpty() && !policy.unrestricted) {
            if (policy.allowExternalAccess) return ""
            val addedSuffix = if (added.isEmpty()) "" else " or an added folder named below"
            return buildString {
                appendLine()
                appendLine()
                appendLine("### Workspace boundary")
                appendLine(
                    "- Writes and shell work stay INSIDE the workspace$addedSuffix: absolute paths, " +
                        "`..` traversal and repo-relative paths outside it are refused with " +
                        "`PERMISSION_DENIED | code=PATH_DENIED` (allowExternalAccess=false)."
                )
                appendLine(
                    "- Reading is wider on purpose: Browser4's own data, temp and code folders are " +
                        "readable even outside the workspace, and they are READ-ONLY. Run " +
                        "coding.permissions for the exact roots; the operator adds more with " +
                        "-Dbrowser4.agent.readPaths=…. A write there is refused — do not retry it " +
                        "against a readable root."
                )
                if (added.isNotEmpty()) appendLine(added)
                appendLine(
                    "- A refusal is structural: do NOT retry it with different quoting, a `..` prefix, " +
                        "or an interpreter. Keep task inputs under the workspace" +
                        (if (added.isEmpty()) "" else " or an added folder") +
                        "; an input that is blocked belongs in `problems` at system.taskComplete."
                )
            }.trimEnd()
        }
        val abilities = buildList {
            add("read the workspace")
            if (policy.allowsFileMutation()) add("write workspace files")
            if (policy.allowExternalAccess) add("reach paths outside the workspace")
            // Ask the policy, not the preset: an operator grant can unlock the build under `read`.
            if (policy.allowsTool("coding", "mvnBuild")) add("run the project build (coding.mvnBuild/coding.verify)")
            if (policy.allowsExec()) add("run shell commands and arbitrary code")
        }
        val approval = "- Operator approval: if a step needs an effect this preset refuses, call " +
            "coding.requestAccess(effect=\"...\", target=\"...\") once. It returns immediately with a " +
            "pending requestId and never blocks the task; continue with allowed steps and retry that " +
            "call later. Guardrails (denied tools, protected/credential paths, the workspace " +
            "boundary) can never be approved away — never route around them."
        val boundary = if (policy.allowExternalAccess) "" else
            "Paths outside the workspace are refused for writes (`PERMISSION_DENIED | code=PATH_DENIED`); " +
                "absolute paths, `..` traversal and interpreters do not get around it. Browser4's own " +
                "data/temp/code folders are the exception, and are READ-ONLY — coding.permissions " +
                "lists them."
        return buildString {
            appendLine()
            appendLine()
            appendLine("### Permissions")
            appendLine("- This run is under access preset '${policy.preset.id}': you may ${abilities.joinToString(", ")}.")
            if (boundary.isNotEmpty()) appendLine("- $boundary")
            appendLine(approval)
            if (added.isNotEmpty()) appendLine(added)
            appendLine(
                "- Tools the preset does not grant are not in your tool list at all, and a call that " +
                    "slips through anyway is refused with `PERMISSION_DENIED | code=...`. A policy " +
                    "refusal is structural: do NOT retry it, re-quote it, or reach for an interpreter " +
                    "to do the same thing — its `hint=` names the route that is still open. " +
                    (if (policy.unrestricted)
                        "This run is unrestricted, so the shell's command allow-list no longer applies: " +
                            "programs the list never knew (a PowerShell cmdlet such as Get-Content, or " +
                            "`wmic`) run as written — use them when one command per call would take " +
                            "several steps. Every in-process rail is off as well (workspace boundary, " +
                            "protected dirs), so the OS user's own permissions are the only limit: be " +
                            "deliberate about what you delete or overwrite. Two families still refuse, and " +
                            "both are permanent: the OS credential stores (`~/.ssh`, `~/.aws`, `~/.gnupg`, " +
                            "`~/.config/gcloud`) — never read them into your context — and destroying the " +
                            "runtime you run inside (`taskkill` on the backend, `b4w build/stop`, " +
                            "`bin/build.ps1`, `mklink`/`ln`/`mount`)."
                    else
                        "A command shape the checker cannot decompose (`for` loops, `else if`, nested blocks, " +
                            "goto/call payloads) is handed to the platform shell verbatim — the shell's own " +
                            "parse error is the feedback, so rewrite the step if it fails. The command tier, " +
                            "blocked patterns, credentials/protected paths and the runtime vetoes still refuse.")
            )
            appendLine(
                "- Run coding.permissions to see exactly what is allowed before planning a step that " +
                    "might be outside it. Work you cannot do under this preset belongs in `problems` " +
                    "at system.taskComplete — say what was blocked and how it should be run, rather " +
                    "than looping."
            )
        }.trimEnd()
    }

    /**
     * The line naming the folders `agent run --add-dir` granted, or "" when none were.
     *
     * An added folder is read+write scope, so a model that kept reading "everything outside the
     * workspace is refused" would never try the folder the operator just granted. The preset still
     * bounds what may be done there, so the line says that too instead of promising writes.
     */
    private fun addedDirsLine(policy: AgentAccessPolicy): String {
        if (policy.additionalDirs.isEmpty()) return ""
        val folders = policy.additionalDirs.map { it.toString() }.sorted().joinToString(", ")
        val write = if (policy.allowsFileMutation()) "you may write there" else "writes there are refused"
        return "- Extra folders granted for this task (use these ABSOLUTE paths as given): $folders — " +
            "readable, writable and usable as a shell working directory in addition to the " +
            "workspace ($write under this preset). They are not copies: the task's own workspace " +
            "stays the place for deliverables and for `coding.write`."
    }
}

