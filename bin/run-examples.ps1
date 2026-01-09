# Find the first parent directory containing the VERSION file
$AppHome=(Get-Item -Path $MyInvocation.MyCommand.Path).Directory
while ($AppHome -ne $null -and !(Test-Path "$AppHome/VERSION")) {
    $AppHome = Split-Path -Parent $AppHome
}
Set-Location $AppHome

# 强制 PowerShell 使用 UTF-8 输出
$OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8

# 强制 Maven 使用 UTF-8（尤其是 exec:java）
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"

# 运行
./mvnw.cmd `
  -D"file.encoding=UTF-8" `
  -D"exec.mainClass=ai.platon.pulsar.examples.agent.Browser4AgentKt" `
  -pl examples/browser4-examples `
  exec:java
