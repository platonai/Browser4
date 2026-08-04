Title: 
Description: Task drafted via coworker CLI.
Prompt: PS D:\workspace\Browser4\Browser4> ./b4w test rws dir

Available scenario directories:
  mock-site
  mock-site\decision-tree
  real-world
  real-world\browser4
  real-world\generic
  workflow

Usage: test.ps1 rws dir <relative-or-absolute-path>
  test.ps1 rws dir tasks/real-world/generic
  test.ps1 rws dir tasks/real-world/browser4
  test.ps1 rws dir tasks/mock-site

PS D:\workspace\Browser4\Browser4> ./b4w test rws dir mock-site\decision-tree

==============================================
Running real-world scenario dir: D:\workspace\Browser4\Browser4\mock-site\decision-tree...
==============================================

==============================================
Child process output (live):
==============================================
Get-ChildItem: D:\workspace\Browser4\Browser4\browser4-tests\real-world-scenarios\scripts\run-tests.ps1:115
Line |
 115 |  … overedFiles = Get-ChildItem -Path $TasksDir -Filter '*.md' -Recurse `
     |                  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     | Cannot find path 'D:\workspace\Browser4\Browser4\mock-site\' because it does not exist.
==============================================
End of child process output
==============================================
