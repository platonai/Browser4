$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configPath = Join-Path (Split-Path -Parent $ScriptDir) 'config.ps1'
. $configPath
$PythonScript = Join-Path $ScriptDir "count-total-token-usage.py"
$LogDir = Get-LogDirectory

if (Get-Command python -ErrorAction SilentlyContinue) {
    python $PythonScript $LogDir
} else {
    Write-Host "Python not found. Please install Python 3."
}
