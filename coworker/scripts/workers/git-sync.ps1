#!/usr/bin/env pwsh

$agentHelper = Join-Path $PSScriptRoot "agent.ps1"
. $agentHelper

# ── Script-level mutex: only one git-sync.ps1 instance at a time
$script:__CoworkerLock = New-CoworkerScriptLock -ScriptPath $MyInvocation.MyCommand.Path -SkipIfHeld
if ($null -eq $script:__CoworkerLock) {
    Write-Host "Another git-sync.ps1 instance is already running. Exiting."
    exit 0
}

$repoRoot = Get-WorkspaceRoot
$agentCommand = Get-AgentCommand -RepoRoot $repoRoot

$prompt = @"
You are committing and pushing changes in "$repoRoot". Follow these steps:

## Step 1: Examine the changes
Run `git diff --stat` to see which files changed.
Run `git diff` to see the actual changes (limit to ~200 lines if the diff is huge).

## Step 2: Write a good commit message

This repo follows Conventional Commits. Study the diff and write a message in this format:

```
type(scope): concise summary in present tense, max ~72 chars

- Brief bullet describing what changed and why (include file paths)
- Another bullet for a separate logical change
- Root cause if this is a bug fix
```

### Type
Choose ONE: `fix` (bug fix), `feat` (new feature), `chore` (maintenance/refactoring), `refactor` (code restructuring), `test` (test changes), `ci` (CI/CD), `docs` (documentation).

### Scope
Use the top-level module that best captures the change area. Common scopes in this repo:
`cli`, `test`, `coworker`, `driver`, `extension`, `service`, `services`, `agent`, `daemon`, `build`, `docs`, `pom`, `cdp`, `scripts`, `batch`.

Use multiple scopes separated by commas if the change spans areas (e.g., `fix(cli, docs): ...`).

### Body
- List each logical change as a bullet point with the file path
- For fixes, include what was broken and how it was fixed
- Group related file changes under one bullet
- Skip trivial/obvious details

### Examples from this repo

Good example 1:
```
fix(test): move test log dir from target/ to .test/<timestamp>

- Uses .test/yyyy-MM-dd-HHmmss per invocation so logs survive mvn clean
- Added .test/ to .gitignore
- All test types (maven, cli, rws) share the same timestamped log dir
```

Good example 2:
```
fix(cli, docs): tab-list GUID truncation and missing --guid in docs

- CODE: Remove GUID truncation in human-readable tab-list output
  (cli/browser4-cli/src/main.rs). Previously GUIDs >10 chars were
  displayed as first 9 chars + …, making them unusable with --guid.
- DOCS: Add --guid and --json mentions consistently across all tab
  documentation (README.md, cli/README.md, skills/browser4-cli/SKILL.md)
```

## Step 3: Commit
Stage all changes with `git add -A`, then commit using the message you wrote:
```
git commit -m "type(scope): summary" -m "body line 1" -m "body line 2"
```

## Step 4: Pull and push
Run `git pull --rebase origin` and then `git push origin`.
If conflicts occur during pull, resolve them automatically and continue.
"@

$agentArguments = New-AgentArguments -BaseArgs $agentCommand.BaseArgs -Prompt $prompt -AdditionalArguments @('--allow-all-tools')

Write-Host "Running:"
Write-Host (Format-AgentCommand -Executable $agentCommand.Executable -Arguments $agentArguments)

Invoke-Agent -Prompt $prompt -AdditionalArguments @('--allow-all-tools') -RepoRoot $repoRoot -WorkingDirectory $repoRoot
$exitCode = $LASTEXITCODE
Remove-CoworkerScriptLock -Lock $script:__CoworkerLock
exit $exitCode
