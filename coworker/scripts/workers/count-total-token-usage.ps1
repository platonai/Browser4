$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PythonScript = Join-Path $ScriptDir "count-total-token-usage.py"
$LogDir = Join-Path "$HOME\.browser4\development" "logs"

if (Get-Command python -ErrorAction SilentlyContinue) {
    python $PythonScript $LogDir
} else {
    Write-Host "Python not found. Please install Python 3."
}
