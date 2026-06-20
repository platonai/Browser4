# ── Coworker configuration loader ─────────────────────────────────────────
# Dot-source this file to get all coworker utilities in scope.

$configDataPath = Join-Path $PSScriptRoot 'config.psd1'
$utilScriptPath = Join-Path $PSScriptRoot 'common\Util.ps1'
if (Test-Path -LiteralPath $utilScriptPath) {
    . $utilScriptPath
    Fix-Encoding-UTF8
}

if (-not (Test-Path $configDataPath)) {
    throw "Config data file not found: $configDataPath"
}

$script:configData = Import-PowerShellDataFile -Path $configDataPath

# Scripts directory (used by Paths.ps1 for relative path resolution)
$script:__CoworkerScriptsRoot = $PSScriptRoot

# ── Dot-source shared modules ─────────────────────────────────────────────
. (Join-Path $PSScriptRoot 'common\Paths.ps1')
. (Join-Path $PSScriptRoot 'common\Watchers.ps1')
. (Join-Path $PSScriptRoot 'common\Logging.ps1')
. (Join-Path $PSScriptRoot 'common\Locks.ps1')

# ── Ensure common tool directories are on PATH ────────────────────────────
# Scheduled tasks run with -NoProfile, so user-profile tool shims (scoop, etc.)
# are not automatically available. Prepend known tool directories to PATH.
$knownToolPaths = @(
    Join-Path $env:USERPROFILE 'scoop\shims'
    Join-Path $env:USERPROFILE 'AppData\Roaming\npm'
    'C:\Program Files\Git\cmd'
)
foreach ($toolPath in $knownToolPaths) {
    if ((Test-Path -LiteralPath $toolPath) -and ($env:PATH -notlike "*$toolPath*")) {
        $env:PATH = "$toolPath;$env:PATH"
    }
}

$COPILOT = @($script:configData['COPILOT'])
if ($script:configData.ContainsKey('CLAUDE')) {
    $CLAUDE = @($script:configData['CLAUDE'])
}
