#!/usr/bin/env pwsh

# Function to kill browser processes: Chrome, Chromium
# Only kill processes with user defined data directories to avoid killing system browsers
function Kill-BrowserProcesses {
    param(
        [string[]]$BrowserNames,
        [string[]]$UserDataDirPatterns
    )
    foreach ($browser in $BrowserNames) {
        $processes = Get-Process -Name $browser -ErrorAction SilentlyContinue
        foreach ($proc in $processes) {
            try {
                $cmdLine = (Get-CimInstance Win32_Process -Filter "ProcessId = $($proc.Id)").CommandLine
                if ($cmdLine) {
                    foreach ($pattern in $UserDataDirPatterns) {
                        if ($cmdLine -match $pattern) {
                            Write-Host "Process $($proc.Id) $cmdLine" -ForegroundColor Yellow
                            break
                        }
                    }
                }
            }
            catch {
                Write-Warning "Could not retrieve command line for process $($proc.Id): $($_.Exception.Message)"
            }
        }
    }
}
# Define browsers and user data directory patterns
$browserNames = @("chrome", "chromium")
$userDataDirPatterns = @("--user-data-dir=", "--profile-directory=")
# Kill browser processes
Kill-BrowserProcesses -BrowserNames $browserNames -UserDataDirPatterns $userDataDirPatterns
Write-Host "Browser processes cleanup completed." -ForegroundColor Green
