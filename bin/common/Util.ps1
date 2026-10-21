# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

function Fix-Encoding-UTF8 {
    $Encoding = "UTF8"
    # 设置错误处理
    $ErrorActionPreference = "Stop"

    # Windows PowerShell 5.1 uses the active console code page for host rendering.
    # Switch it to UTF-8 to avoid mojibake when printing Chinese text.
    try {
        if ($IsWindows -or $env:OS -eq "Windows_NT") {
            # Check current code page first
            $currentCP = [Console]::OutputEncoding.CodePage
            if ($currentCP -ne 65001) {
                # Attempt to change to UTF-8
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                if ($PSVersionTable.PSVersion.Major -le 5) {
                    # Only change chcp for older PowerShell to avoid side effects
                    cmd /c chcp 65001 > $null
                }
            }
        }
    } catch {
        # Best-effort only; continue even if code page can't be changed.
    }

    # Force PowerShell to use UTF-8 for IO
    $OutputEncoding = [System.Text.Encoding]::UTF8
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    [Console]::InputEncoding  = [System.Text.Encoding]::UTF8

    # 强制 Maven 使用 UTF-8（尤其是 exec:java）
    $env:MAVEN_OPTS = "-Dfile.encoding=$Encoding"
    $env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=$Encoding"
}

function Set-Utf8ConsoleEncoding {
    <#
    .SYNOPSIS
        Pin this process' console/stream encoding to UTF-8.
    .DESCRIPTION
        A redirected stream starts on the Windows OEM code page (CP936 on a
        Chinese host), while everything that consumes these scripts reads
        UTF-8: the relay in bin/test.ps1, the per-run logs, CI and the capture
        used for issue tracking.  Under CP936 the decoration this tree prints
        turns into line noise — "·" and "—" become "??" (A1 A4 / A1 AA read as
        UTF-8) and "═" becomes "?T" (A8 54, whose 0x54 is a literal 'T').

        Cheaper than Fix-Encoding-UTF8: it touches the console/stream encoding
        only, with no MAVEN_OPTS / JAVA_TOOL_OPTIONS side effects.
    #>
    try {
        if ([Console]::OutputEncoding.CodePage -ne 65001) {
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
        }
    } catch {
        # A host without a usable console must not fail the caller.
    }
}

# Pinning here covers every script that dot-sources this file: the whole
# maintenance tree (MaintenanceUtil.ps1 dot-sources Util.ps1), the build
# helpers, and the bin entry points below — no need to repeat the snippet.
Set-Utf8ConsoleEncoding
