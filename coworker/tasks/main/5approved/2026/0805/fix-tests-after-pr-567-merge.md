Title: Fix failing tests after PR merge into 4.13.x
Description: Tests failed after merging PRs into 4.13.x. No direct merges. Resolved: #567.
Prompt: |
  The following PRs were just merged into $BaseBranch:
  - Direct merges: none
  - Conflict-resolved merges: 567

  Tests failed with exit code 1 when running ./bin/test.ps1 fast.
  Investigate the test failures and fix them. Read the test output below,
  identify the root cause(s), and apply fixes. Run the tests again to verify.

  Test command: ./bin/test.ps1 fast
  Test output (last lines):
  [INFO] Browser4 Agent Tools ............................... SUCCESS [ 33.273 s]
[INFO] Browser4 Boot ...................................... SUCCESS [03:04 min]
[INFO] Browser4 PDK BOM ................................... SUCCESS [  0.002 s]
[INFO] Browser4 Plugin Archetype .......................... SUCCESS [  1.990 s]
[INFO] Browser4 PDK Test Plugin ........................... SUCCESS [  2.441 s]
[INFO] Browser4 Rest ...................................... FAILURE [01:45 min]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  20:07 min
[INFO] Finished at: 2026-08-05T14:18:59+08:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.4:test (default-test) on project browser4-rest: There are test failures.
[ERROR] 
[ERROR] See /home/vincent/workspace/Browser4-4.13/browser4-rest/target/surefire-reports for the individual test results.
[ERROR] See dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
[ERROR] 
[ERROR] After correcting the problems, you can resume the build with the command
[ERROR]   mvn <args> -rf :browser4-rest

==============================================
[FAIL] Maven tests: fast failed with exit code 1
==============================================

  #auto-approve
