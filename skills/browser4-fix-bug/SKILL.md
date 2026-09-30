---
name: browser4-fix-bug
title: "browser4-fix-bug"
tier: procedure
description: "Fix bugs in code using a compile-test-fix loop: run the build/tests, read the error, locate the faulty lines, edit, and re-verify until green. Use when the user reports a build failure, test failure, crash, or asks to fix an error in Browser4 plugins, Kotlin, TS/JS, Python, or scripts."
allowed-tools: coding.shell coding.read coding.readLines coding.replace coding.replaceRegex coding.editLines coding.insertAfter coding.diff coding.changeSummary coding.revert coding.diagnostics coding.references coding.symbols coding.lspServers tab.eval tab.console
---

# browser4-fix-bug

## Quick Start

```text
coding.shell(command="mvn -pl <module> -am compile -DskipTests")     # 1. reproduce the error
coding.readLines(path=<file>, startLine=<line-8>, endLine=<line+8>)  # 2. read the failing code
coding.replace(path=<file>, old="...", new="...")                    # 3. smallest possible edit
coding.shell(command="mvn -pl <module> -am compile -DskipTests")     # 4. re-verify until green
```

Drive a bug from "it fails" to "it passes" with a disciplined compile-test-fix loop.
Never guess — read the actual error, make the smallest edit, and re-verify.

## When to Use

- User reports a build/compile failure or a failing test
- A plugin, skill script, JS, or shell script errors at runtime
- User says "fix this error", "the build is broken", "tests fail", "it crashes"
- LSP diagnostics (`coding.diagnostics`) report errors in a file

## How It Works

The compile-test-fix loop is error-driven: reproduce the failure, read the exact error, locate the faulty lines with minimal context, make the smallest possible edit, and re-verify. Never guess — every iteration starts from the actual error output.

## Patterns

### 1. Build/compile failure

```text
coding.shell(command="mvn -pl <module> -am compile -DskipTests")
coding.readLines(path=<file>, startLine=<line-8>, endLine=<line+8>)
coding.replace(path=<file>, old="...", new="...")
coding.shell(command="mvn -pl <module> -am compile -DskipTests")
```

### 2. Failing test

```text
coding.shell(command="mvn test -pl <module> -Dtest=<TestClass>")
coding.readLines(path=<test-file>, startLine=<line-8>, endLine=<line+8>)
coding.replace(path=<file>, old="...", new="...")
```

### 3. Runtime error in plugin/script

```text
coding.diagnostics(path=<file>)
coding.readLines(path=<file>, startLine=<line-8>, endLine=<line+8>)
coding.replace(path=<file>, old="...", new="...")
```

## Flags

`coding` tools take structured arguments, not CLI flags — see Workflow below for each tool's parameters.

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| Build still fails after an edit | Edit didn't address the real error | Re-read the error output; check the exact line numbers |
| Edit had no effect | Wrong file or path | Verify the path with `coding.readLines` before editing |
| Loop never goes green | Fix introduced a new error | Revert (`coding.revert`), then make a smaller edit |

## Workflow

### 1. Reproduce — get the concrete error

```
coding.shell(command="mvn -pl <module> -am compile -DskipTests")   # Kotlin/Maven
coding.shell(command="tsc --noEmit")                               # TS
coding.shell(command="python -m py_compile <file>.py")             # Python
coding.shell(command="bash -n <file>.sh")                          # Bash
tab.eval(expression=<read the JS file>)  +  tab.console()          # Browser JS
```

If LSP servers are available, prefer instant pre-checks first:
`coding.diagnostics(path=<file>)` — structured errors with line numbers.

### 2. Locate — read the failing code, not the whole file

```
coding.readLines(path=<file>, startLine=<line-8>, endLine=<line+8>)
coding.references(path=<file>, symbol=<suspected-symbol>)   # impact check before edits
coding.diff(path=<file>)                                     # what changed recently
```

### 3. Fix — smallest possible edit

| Symptom | Tool |
|---------|------|
| Wrong string | `coding.replace(path, oldStr, newStr)` |
| Pattern-based | `coding.replaceRegex(path, regex, replacement)` |
| Whole block | `coding.editLines(path, startLine, endLine, content)` |
| Add a line | `coding.insertAfter(path, anchor, content)` |
| Edit went wrong | `coding.revert(path)` then redo |

### 4. Verify — rerun the exact command from step 1

Loop 1→4 until green. **Do not** make multiple unrelated edits between verifications —
one fix, one check, so you always know which edit worked.

### 5. Report

Summarize: root cause (one line), fix (file:line), verification result
(build/test output tail). If the fix took more than 3 attempts, re-read the
error output carefully before continuing — do not fire-and-forget edits.

## Rules

1. **Read before you write.** Never edit a line you have not seen.
2. **One change per verify cycle.** Batch edits only after each is proven.
3. **Use diagnostics when available.** `coding.lspServers` tells you which
   language servers are installed; prefer `coding.diagnostics` over a full build
   for TS/JS/Python.
4. **Prefer editLines/insertAfter over replace** for block edits — less chance
   of matching the wrong occurrence.
5. **Revert is cheap.** If an edit makes things worse, `coding.revert` restores
   the pre-edit snapshot, then retry with a different approach.
6. **Stop and escalate** after 5 failed attempts — report the exact error and
   your attempted fixes rather than degrading the code.

## Examples

```
User: 编译报错了，browser4-seo 插件 mvn compile 失败
Agent: 1. coding.shell(command="mvn -pl browser4-plugins/browser4-seo -am compile -DskipTests")
       2. → error: SeoToolExecutor.kt:42 Unresolved reference 'foo'
       3. coding.readLines(path="browser4-plugins/browser4-seo/src/main/kotlin/ai/platon/pulsar/seo/tools/SeoToolExecutor.kt",
                           startLine=34, endLine=50)
       4. coding.replace(path=..., oldStr="foo", newStr="bar")
       5. coding.shell(command="mvn -pl browser4-plugins/browser4-seo -am compile -DskipTests") → BUILD SUCCESS
       6. 报告：根因 import 拼写；修复 SeoToolExecutor.kt:42；mvn compile 通过
```
