#!/usr/bin/env pwsh
<#
.SYNOPSIS
    b4w — browser4-cli wrapper with additional development tools.

.DESCRIPTION
    b4w (browser4-cli wrapper) is a development-only launcher that builds and
    runs the browser4-cli Rust binary directly from the current codebase.  It
    also bundles extra dev-tool subcommands (coworker, sc, test, build) that are
    not part of the published browser4-cli release.

    This script is not shipped to end users — it lives in the repo root and
    expects the Cargo workspace at ../cli/browser4-cli relative to itself.
#>

# Use $args directly instead of PowerShell's param() block so that CLI flags
# like -o, -i, and -v are NOT intercepted by PowerShell's common parameter
# binder (where -o is ambiguous between -OutVariable/-OutBuffer, -i matches
# -InformationAction, and -v matches -Verbose).
#
# With manual args parsing, all tokens pass through to the $RemainingArgs
# array without PowerShell trying to bind them to script parameters.

# ── Global invocation bootstrap ────────────────────────────────────────────
# When b4w is invoked via PATH (global command), search upward from the
# calling directory to locate the correct b4w.ps1 for this repository.
# This ensures that if you have multiple Browser4 checkouts, typing "b4w"
# always uses the one corresponding to your current directory.
#
# If no b4w.ps1 is found in any parent directory, show an error — b4w is a
# development-only tool and must be called from within a Browser4 source
# code repository.

$B4wMyPath = $MyInvocation.MyCommand.Path
$B4wCallingDir = Get-Location
$B4wFoundScript = $null
$B4wSearchDir = $B4wCallingDir

while ($B4wSearchDir) {
    $B4wCandidate = Join-Path $B4wSearchDir 'b4w.ps1'
    if (Test-Path -Path $B4wCandidate -PathType Leaf) {
        $B4wFoundScript = (Resolve-Path $B4wCandidate).Path
        break
    }
    $B4wParent = Split-Path $B4wSearchDir -Parent
    if ($B4wParent -eq $B4wSearchDir) { break }
    $B4wSearchDir = $B4wParent
}

if (-not $B4wFoundScript) {
    Write-Host 'Error: b4w is a development-only tool.' -ForegroundColor Red
    Write-Host ''
    Write-Host 'b4w must be called from within a Browser4 source code repository.' -ForegroundColor Yellow
    Write-Host 'Navigate to a Browser4 checkout and try again.' -ForegroundColor Yellow
    exit 1
}

# If the found script is different from ourselves, delegate to it.
$B4wMyPathNormalized = if (Test-Path $B4wMyPath) { (Resolve-Path $B4wMyPath).Path } else { $B4wMyPath }
if ($B4wFoundScript -ne $B4wMyPathNormalized) {
    & $B4wFoundScript @args
    exit $LASTEXITCODE
}
# Otherwise, we ARE the correct b4w.ps1 — continue below.

$Rebuild = $false
$NoBuild = $false
$RemainingArgs = @()

for ($i = 0; $i -lt $args.Count; $i++) {
    if ($args[$i] -eq '-Rebuild') {
        $Rebuild = $true
    } elseif ($args[$i] -eq '-NoBuild' -or $args[$i] -eq '--no-build') {
        $NoBuild = $true
    } else {
        $RemainingArgs += $args[$i]
    }
}

# Short flags (-o, -i, -v) are safe in b4w.ps1 because manual $args
# parsing prevents PowerShell from intercepting them.  No warning is
# emitted here — users on other shells (b4w.sh, plain pwsh) should
# use long-form equivalents (--output, --interactive, --viewport) or
# b4w.bat (cmd.exe) for full compatibility.

# ── Git-Bash (MSYS) mangled-argument guard ──────────────────────────────
# Git Bash rewrites '/'-leading arguments into Windows paths rooted at the
# Git installation directory when spawning pwsh ('/ec/dp/' arrives here as
# 'C:/Program Files/Git/ec/dp/'; a bare '/' arrives as the Git root itself,
# 'C:/Program Files/Git/').  The original token is lost before this
# script runs — a mangled argument would be forwarded to the CLI and
# silently produce wrong results (e.g. a snapshot grep pattern reporting
# '0 matches found', or '--path /' arriving as a path on C:).  Detect the
# rewritten form and fail fast with guidance that includes the de-converted
# command to re-run; the conversion-free route from Git Bash is ./b4w.sh,
# which exports MSYS2_ARG_CONV_EXCL='*'.  Legit '/d/...'-style conversions
# to other drives are never under the Git root, so they pass through.
#
# MSYS rewrites only the part *after* '=' in a name=value token, and — as
# observed with Git for Windows' MSYS2 — the rewritten value is then split
# into two argv tokens, because the drive path it builds is not re-quoted:
#   '--path=/'    arrives as '[--path=C]' + '[/Program Files/Git/]'
#   '-olp=/x'     arrives as '[-olp=C]'   + '[/Program Files/Git/x]'
#   '--path=/c/f' arrives as '[--path=C]' + '[/f]'
# Four spellings are therefore checked:
#   1. whole token      '/ec/dp/'      → 'C:/Program Files/Git/ec/dp/'
#   2. value after '='  '--path=/'     → '--path=C:/Program Files/Git/'
#      (when the value survives as one token)
#   3. split value      '--path=C' + '/…' — the name=value form as it really
#      arrives; the pair is re-joined into '--path=/…'
#   4. drive signature  any '--name=<drive>:/' token under MSYSTEM, which
#      also catches rewritten values when the Git root cannot be resolved
#      (git.exe not on PATH): MSYS maps a '/c/…' value to 'C:/…'.

function Format-B4wBashArgument {
    <#
    .SYNOPSIS
        Quote one argv token for a copy-pasteable Git Bash command line.
    .PARAMETER Value
        The token to render.
    .OUTPUTS
        String — the token, single-quoted when it needs shell quoting.
    #>
    param([string]$Value = '')

    if ([string]::IsNullOrEmpty($Value)) { return "''" }
    if ($Value -match "^[A-Za-z0-9_@%+=:,./\\-]+$") { return $Value }
    return "'" + ($Value -replace "'", "'\''") + "'"
}

function Get-B4wMsysMangledArgument {
    <#
    .SYNOPSIS
        Find the first argv token that MSYS path conversion rewrote, and
        rebuild the command line the user probably typed.

    .DESCRIPTION
        A token is mangled when the whole token, the part after the first '='
        (the name=value form), or the re-joined split form of that value
        ('--path=C' followed by '/…') equals the Git/MSYS root or lives under
        it — or, regardless of the Git root, when a name=value token carries
        the drive-path signature of a converted value ('--name=C:/…').

    .PARAMETER Argv
        The argv as received by this script ($args).

    .PARAMETER RootFwd
        Normalized MSYS root (forward slashes, no trailing slash), or '' when
        it could not be resolved.

    .OUTPUTS
        $null when nothing was rewritten; otherwise a hashtable with Raw,
        Probable, Kind ('whole' | 'equals' | 'equals-split' | 'signature')
        and DeconvertedArgv (the argv with every mangled token replaced by
        its probable original; a split pair collapses back into one token).
    #>
    param(
        [string[]]$Argv = @(),
        [string]$RootFwd = ''
    )

    $boundary = if ($RootFwd) { $RootFwd + '/' } else { '' }
    # POSIX spelling of the Git root ('C:/Program Files/Git' → '/Program Files/Git').
    # The second half of a split pair is '/'-rooted and can start with the
    # Git root's path part, which is how '--path=/' and '-olp=/x' are detected.
    $rootPosix = ''
    if ($RootFwd -match '^[A-Za-z]:(/.*)$') { $rootPosix = $Matches[1].TrimEnd('/') }

    $deconverted = New-Object System.Collections.Generic.List[string]
    $finding = $null

    for ($index = 0; $index -lt $Argv.Count; $index++) {
        $arg = $Argv[$index]
        # Normalize separators so 'C:\Program Files\Git\x' and
        # 'C:/Program Files/Git/x' compare equal.
        $forward = $arg -replace '\\', '/'
        $name = ''
        $separator = ''
        $value = $forward
        $eqIndex = $forward.IndexOf('=')
        if ($eqIndex -gt 0) {
            $name = $forward.Substring(0, $eqIndex)
            $separator = '='
            $value = $forward.Substring($eqIndex + 1)
        }

        $probableValue = $null
        $kind = ''
        $consumedNext = $false

        if ($RootFwd) {
            # A bare '/' argument is rewritten to the Git root ITSELF
            # ('C:/Program Files/Git' with or without a trailing slash), so
            # the comparison must catch equality, not just a longer prefix.
            # Sub-paths must clear the '/' boundary so sibling directories
            # (e.g. 'C:/Program Files/GitHub/...') are never mistaken for
            # the Git root.
            $valueIsRoot = $value.TrimEnd('/').Equals($RootFwd, [StringComparison]::OrdinalIgnoreCase)
            $valueUnderRoot = $value.Length -gt $boundary.Length -and
                $value.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)
            if ($valueIsRoot) {
                $probableValue = '/'
            } elseif ($valueUnderRoot) {
                $probableValue = '/' + $value.Substring($boundary.Length).TrimStart('/')
            }
            if ($null -ne $probableValue) {
                $kind = if ($separator) { 'equals' } else { 'whole' }
            }
        }

        # The split form: '--path=/' arrives as '--path=<drive>' followed by a
        # '/'-rooted remainder.  The pair is one mangled argument; the value is
        # rebuilt so the printed command can be re-run as typed.
        if (-not $kind -and $separator -and $value -match '^[A-Za-z]$' -and ($index + 1) -lt $Argv.Count) {
            $nextToken = $Argv[$index + 1]
            if ($nextToken.StartsWith('/')) {
                $nextFwd = $nextToken -replace '\\', '/'
                if ($rootPosix -and $nextFwd.TrimEnd('/').Equals($rootPosix, [StringComparison]::OrdinalIgnoreCase)) {
                    # '/Program Files/Git/' is the Git root's own POSIX path: the
                    # value was the bare '/'.
                    $probableValue = '/'
                } elseif ($rootPosix -and $nextFwd.StartsWith($rootPosix + '/', [StringComparison]::OrdinalIgnoreCase)) {
                    $probableValue = '/' + $nextFwd.Substring($rootPosix.Length + 1).TrimStart('/')
                } else {
                    # Not under the Git root: MSYS resolved a POSIX path
                    # ('/c/foo', '/tmp') onto a drive.  The drive-mapped
                    # spelling below names the same Windows location, so the
                    # recovery command still works even when it is not the
                    # exact original text.
                    $probableValue = '/' + $value.ToLowerInvariant() + '/' + $nextFwd.TrimStart('/')
                }
                $kind = 'equals-split'
                $consumedNext = $true
            }
        }

        # Backstop for a converted value whose Git root is unknown: MSYS maps
        # POSIX '/c/…' onto the Windows drive path 'C:/…', so a '--name=' token
        # whose value starts with a drive path is the signature of a rewrite.
        if (-not $kind -and $forward -match '^--?[A-Za-z][A-Za-z0-9-]*=([A-Za-z]):/(.*)$') {
            $probableValue = '/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2]
            $kind = 'signature'
        }

        if ($kind) {
            $probableArg = if ($separator) { $name + $separator + $probableValue } else { $probableValue }
            $deconverted.Add($probableArg)
            if ($consumedNext) { $index++ }
            if (-not $finding) {
                $finding = @{ Raw = $arg; Probable = $probableArg; Kind = $kind }
            }
        } else {
            $deconverted.Add($arg)
        }
    }

    if (-not $finding) { return $null }

    $finding['DeconvertedArgv'] = $deconverted.ToArray()
    return $finding
}

if ($env:MSYSTEM) {
    $MsysRoot = $null
    $MsysGit = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($MsysGit -and $MsysGit.Source) {
        # <root>\cmd\git.exe | <root>\bin\git.exe | <root>\usr\bin\git.exe | <root>\mingw64\bin\git.exe
        $MsysGitDir = Split-Path $MsysGit.Source -Parent
        $MsysGitLeaf = Split-Path $MsysGitDir -Leaf
        if ($MsysGitLeaf -eq 'cmd') {
            $MsysRoot = Split-Path $MsysGitDir -Parent
        } elseif ($MsysGitLeaf -eq 'bin') {
            $MsysGitParent = Split-Path $MsysGitDir -Parent
            if ((Split-Path $MsysGitParent -Leaf) -in @('usr', 'mingw64', 'msys64')) {
                $MsysRoot = Split-Path $MsysGitParent -Parent
            } else {
                $MsysRoot = $MsysGitParent
            }
        }
    }

    $RootFwd = if ($MsysRoot) { ($MsysRoot -replace '\\', '/').TrimEnd('/') } else { '' }
    $MsysMangled = Get-B4wMsysMangledArgument -Argv $args -RootFwd $RootFwd

    if ($MsysMangled) {
        # Rebuild the exact command to re-run: every mangled token replaced by
        # its probable original, quoted for Git Bash, with the conversion
        # disabled for the child process.
        $RecoveryCommand = "MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1"
        foreach ($RecoveryArg in $MsysMangled.DeconvertedArgv) {
            $RecoveryCommand += ' ' + (Format-B4wBashArgument -Value $RecoveryArg)
        }

        Write-Host "Error: argument '$($MsysMangled.Raw)' (probably typed as '$($MsysMangled.Probable)') was rewritten by" -ForegroundColor Red
        Write-Host "Git Bash's MSYS path conversion before PowerShell started.  The original value" -ForegroundColor Red
        Write-Host 'is unrecoverable, and using the rewritten path would silently produce wrong' -ForegroundColor Red
        Write-Host "results (e.g. a snapshot grep pattern reporting '0 matches found')." -ForegroundColor Red
        if ($MsysMangled.Kind -eq 'signature') {
            Write-Host "A '--name=<drive>:/…' value is the signature of that rewrite (MSYS maps the" -ForegroundColor Red
            Write-Host "POSIX form '/c/…' onto 'C:/…').  If you really meant a Windows path, disable" -ForegroundColor Red
            Write-Host 'the conversion with the prefix shown below.' -ForegroundColor Red
        } elseif ($MsysMangled.Kind -ne 'whole') {
            Write-Host "The value after '=' was converted and split off into its own argument; the" -ForegroundColor Red
            Write-Host 'command below restores it.' -ForegroundColor Red
        }
        Write-Host ''
        Write-Host 'Re-run with the conversion disabled (de-converted command):' -ForegroundColor Cyan
        Write-Host "  $RecoveryCommand" -ForegroundColor Cyan
        Write-Host ''
        Write-Host "Or run this command via ./b4w.sh instead (it disables the conversion)." -ForegroundColor Cyan
        exit 1
    }
}

# Save the original working directory so we can restore it on exit.
# Some operations (cargo build, cargo run) may change the process CWD,
# and restoring it prevents shell session CWD drift when b4w.ps1 is
# invoked from a wrapper that tracks CWD across invocations.
$OriginalCwd = Get-Location

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Manifest = "$ScriptDir/cli/browser4-cli/Cargo.toml"
$ExeName = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'browser4-cli.exe' } else { 'browser4-cli' }
$Exe = Join-Path $ScriptDir "cli/browser4-cli/target/debug/$ExeName"

if ($Rebuild) {
    Write-Host "Rebuilding browser4-cli..." -ForegroundColor Yellow
    cargo build --manifest-path $Manifest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

# Auto-detect stale sources and rebuild if needed.
# Uses a source-content hash cache to avoid redundant cargo build
# invocations when running multiple b4w.ps1 commands in parallel.
# Without the cache, each concurrent invocation detects stale sources
# independently and all try to acquire the cargo build lock, causing
# "Blocking waiting for file lock on build directory" delays.
if (!$Rebuild -and !$NoBuild -and (Test-Path $Exe)) {
    $ExeTime = (Get-Item $Exe).LastWriteTime
    $CrateDir = Join-Path $ScriptDir "cli/browser4-cli"
    $SrcDir = Join-Path $CrateDir "src"
    $HashFile = Join-Path $CrateDir "target/.source-hash"

    # Compute a content hash of all source files to detect real changes.
    # Timestamp comparison alone triggers rebuilds when multiple instances
    # start before any one finishes — the exe is still old so all instances
    # see "stale" sources.  A content hash cached after successful build
    # lets subsequent instances skip the build when nothing actually changed.
    $CurrentHash = $null
    try {
        $Hasher = [System.Security.Cryptography.SHA256]::Create()
        $Files = @(Get-ChildItem -Path $SrcDir -Recurse -File -Filter "*.rs" -ErrorAction SilentlyContinue | Sort-Object FullName)
        $Files += @(Get-Item "$CrateDir/Cargo.toml", "$CrateDir/Cargo.lock" -ErrorAction SilentlyContinue | Where-Object { $_ })
        foreach ($f in $Files) {
            $Content = [System.IO.File]::ReadAllBytes($f.FullName)
            $Hasher.TransformBlock($Content, 0, $Content.Length, $Content, 0) > $null
        }
        $Hasher.TransformFinalBlock(@(), 0, 0) > $null
        $CurrentHash = [BitConverter]::ToString($Hasher.Hash) -replace '-'
        $Hasher.Dispose()
    } catch {
        # If hashing fails (e.g. permission), fall back to timestamp check.
    }

    $CachedHash = $null
    if ($CurrentHash -and (Test-Path $HashFile)) {
        $CachedHash = (Get-Content $HashFile -ErrorAction SilentlyContinue).Trim()
    }

    if ($CachedHash -and $CurrentHash -and $CachedHash -eq $CurrentHash) {
        # Sources haven't changed since last build — skip rebuild.
    } else {
        # Fall back to timestamp-based detection when hash cache is
        # unavailable or sources have genuinely changed.
        $Stale = @(Get-ChildItem -Path $SrcDir -Recurse -File -Filter "*.rs" -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -gt $ExeTime })
        if (-not $Stale) {
            foreach ($f in @("$CrateDir/Cargo.toml", "$CrateDir/Cargo.lock")) {
                if ((Test-Path $f) -and ((Get-Item $f).LastWriteTime -gt $ExeTime)) { $Stale = @($true); break }
            }
        }
        if ($Stale) {
            Write-Host "Rust sources changed, rebuilding browser4-cli..." -ForegroundColor Yellow
            cargo build --manifest-path $Manifest
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            # Persist the new source hash so the next invocation can skip
            # the build.  Always write after a successful build so parallel
            # invocations benefit from the cache.
            if ($CurrentHash) {
                try {
                    New-Item -Path (Split-Path $HashFile -Parent) -ItemType Directory -Force -ErrorAction SilentlyContinue > $null
                    $CurrentHash | Out-File -FilePath $HashFile -Encoding ascii -NoNewline
                } catch { }
            }
        }
    }
}

function Get-B4wBackendBundleStampPath {
    <#
    .SYNOPSIS
        Resolve the runtime bundle's build stamp.
    .DESCRIPTION
        The stamp lives under the platform-specific work directory
        (browser4-bundle-runtime-<os>-<arch>).  Resolving it here keeps the
        staleness check and the post-rebuild verification on the same path on
        Windows, Linux and macOS.  Returns $null when no bundle has been built.
    #>
    $BundleWorkRoot = Join-Path $ScriptDir 'browser4-apps\browser4-bundle\target\runtime-bundle\_work'
    Get-ChildItem -Path $BundleWorkRoot -Directory -Filter 'browser4-bundle-runtime-*' -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName (Join-Path $_.Name 'runtime-bundle.json') } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Sort-Object { (Get-Item -LiteralPath $_).LastWriteTime } -Descending |
        Select-Object -First 1
}

function Test-B4wMayStartBackend {
    <#
    .SYNOPSIS
        True when this invocation can start or talk to the Browser4 backend.
    .DESCRIPTION
        The backend staleness notice only makes sense for commands that will
        actually launch the backend: dev mode detects "sources newer than the
        bundle" and rebuilds before serving, so the notice warns about a
        multi-minute wait.  Printing it for `help`, `config get`,
        `--version`, `status` or the wrapper's own subcommands
        (coworker/sc/test/build/b4w) warns about a rebuild those commands never
        trigger.

        The list names only commands that are certain to stay local; anything
        unrecognized keeps the notice, because hiding a heads-up about a slow
        rebuild is worse than printing one extra line.  Kept in sync with the
        exclusions of `should_ensure_server_running()` in
        cli/browser4-cli/src/main.rs.
    .PARAMETER CliArgs
        Arguments as received by the wrapper, before subcommand routing.
    #>
    param([string[]] $CliArgs)

    if (-not $CliArgs -or $CliArgs.Count -eq 0) { return $false }

    $i = 0
    # `b4w -- <cmd>` and `b4w cli <cmd>` both name the command after the prefix.
    if ($CliArgs[$i] -eq '--' -or $CliArgs[$i] -eq '--%') { $i++ }
    if ($i -lt $CliArgs.Count -and $CliArgs[$i] -eq 'cli') { $i++ }
    # `b4w` (bare) or `b4w cli` (bare) print help instead of running anything.
    if ($i -ge $CliArgs.Count) { return $false }

    $LocalCommands = @(
        # Wrapper-level help and version.
        'help', '--help', '-h', 'version', '--version', '-V',
        # Wrapper subcommands — routed before the CLI binary runs.
        'coworker', 'sc', 'test', 'build', 'b4w',
        # CLI commands that never start the backend.
        'config', 'config-list', 'config-get', 'config-set', 'config-delete',
        'status', 'doctor', 'doctor-log', 'doctor-metrics', 'doctor-status',
        'stop', 'install', 'uninstall', 'upgrade',
        'list', 'close', 'close-all', 'disconnect', 'kill-all', 'session-default',
        'skills', 'skills-list', 'skills-get', 'skills-path', 'skills-unpack',
        'snapshot-list', 'snapshot-clean',
        'agent-list', 'agent-cancel', 'crawl-list', 'swarm-list',
        'webminer', 'webminer-install', 'webminer-update', 'webminer-version',
        'webminer-uninstall', 'webminer-run-example', 'webminer-all', 'webminer-views'
    )
    return -not ($LocalCommands -contains $CliArgs[$i])
}

# ── Backend staleness check ──────────────────────────────────────────────
# The CLI binary is rebuilt from source above, but the Browser4 backend runs
# from a runtime bundle (browser4-apps/browser4-bundle/target/runtime-bundle)
# whose jars are only refreshed on demand via
# BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1. Detect changed Kotlin sources and
# warn instead of silently talking to a stale backend. `-Rebuild` also
# forces the bundle rebuild and caches the new hash afterwards.
$ForcedBundleRebuild = $false
$BackendHashToCache = $null
$BackendBundleStampPath = $null
$BackendBundleStampBefore = $null
# The force flag is only for this invocation's child process.  Save the
# caller's value so it can be restored afterwards; otherwise running
# `./b4w.ps1 -Rebuild status` in PowerShell leaves the flag set and every later
# command force-rebuilds the backend too.
$OriginalForceRebuildBundle = $env:BROWSER4_CLI_FORCE_REBUILD_BUNDLE

# Everything below runs inside try/finally so the caller's force flag is
# restored on every path, including the many early `exit`s (help, subcommands,
# install/uninstall) and a failed CLI run.
try {

# `-Rebuild` must always ask for a bundle rebuild, whatever the cached hash
# says: the hash only records what the sources looked like, not whether a
# rebuild ever ran.  A `-Rebuild status` never starts the daemon, yet it used to
# cache the new hash, so every later `-Rebuild <command>` saw a matching hash,
# never set the force flag, and silently kept the stale backend.
if ($Rebuild) {
    $env:BROWSER4_CLI_FORCE_REBUILD_BUNDLE = '1'
    $ForcedBundleRebuild = $true
}
if ((!$NoBuild -or $Rebuild) -and (Test-Path (Join-Path $ScriptDir 'browser4-apps\browser4-bundle'))) {
    $BackendHashFile = Join-Path $ScriptDir 'cli\browser4-cli\target\.backend-source-hash'
    $BackendCurrentHash = $null
    try {
        $Hasher2 = [System.Security.Cryptography.SHA256]::Create()
        $BackendDirs = @(
            'browser4-core', 'browser4-agentic', 'browser4-coding',
            'browser4-rest', 'browser4-boot', 'browser4-agent-tools'
        )
        # Only `src/main` is backend code: test sources are not compiled into the
        # runtime bundle, so hashing them announced a rebuild that never came
        # (the CLI's own staleness check walks `src/main` only).
        #
        # `browser4-plugins` is deliberately absent: plugin jars are assembled
        # into the bundle's `plugins/` directory — never `lib/` — and plugins are
        # optional, installed on demand, so a plugin edit must not advertise a
        # backend rebuild the launcher never performs.  Keep this list in sync
        # with BUNDLED_TOP_LEVEL_MODULES in cli/browser4-cli/src/daemon.rs.
        $BackendFiles = foreach ($d in $BackendDirs) {
            @(Get-ChildItem -Path (Join-Path $ScriptDir $d) -Recurse -File -Filter '*.kt' -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -match '[\\/]src[\\/]main[\\/]' })
        }
        $BackendFiles += @(Get-Item (Join-Path $ScriptDir 'pom.xml'), (Join-Path $ScriptDir 'VERSION') -ErrorAction SilentlyContinue | Where-Object { $_ })
        $BackendFiles = @($BackendFiles | Sort-Object FullName -Unique)
        foreach ($f in $BackendFiles) {
            $Content = [System.IO.File]::ReadAllBytes($f.FullName)
            $Hasher2.TransformBlock($Content, 0, $Content.Length, $Content, 0) > $null
        }
        $Hasher2.TransformFinalBlock(@(), 0, 0) > $null
        $BackendCurrentHash = [BitConverter]::ToString($Hasher2.Hash) -replace '-'
        $Hasher2.Dispose()
    } catch {
        # If hashing fails (e.g. permission), skip the staleness check.
    }
    if ($BackendCurrentHash) {
        # Resolve the stamp before the hash comparison: the post-rebuild check
        # needs the path even when the hash already matches, and a first build
        # has no stamp yet (the post-check resolves it again after the build).
        $BundleStamp = Get-B4wBackendBundleStampPath
        if ($BundleStamp) {
            $BackendBundleStampPath = $BundleStamp
            $BackendBundleStampBefore = (Get-Item -LiteralPath $BundleStamp).LastWriteTime
        }
        $BackendCachedHash = if (Test-Path $BackendHashFile) { (Get-Content $BackendHashFile -ErrorAction SilentlyContinue).Trim() } else { $null }
        if ($BackendCachedHash -and $BackendCachedHash -eq $BackendCurrentHash) {
            # Backend sources unchanged since the last bundle build — skip.
        } elseif ($BackendFiles) {
            # Hash cache differs — but that may just mean the bundle was rebuilt
            # manually (e.g. via build-runtime-bundle.ps1) and the cache wasn't
            # refreshed. Verify against the bundle's own build stamp: when the
            # bundle artifact is NEWER than every backend source file, the
            # running code matches the sources — refresh the cache silently
            # instead of warning.
            $BackendNewestSource = ($BackendFiles | ForEach-Object { $_.LastWriteTime } | Measure-Object -Maximum).Maximum
            $BundleFresh = $false
            if ($BackendBundleStampPath -and $BackendBundleStampBefore -gt $BackendNewestSource) {
                $BundleFresh = $true
            }
            if ($BundleFresh) {
                try {
                    New-Item -Path (Split-Path $BackendHashFile -Parent) -ItemType Directory -Force -ErrorAction SilentlyContinue > $null
                    $BackendCurrentHash | Out-File -FilePath $BackendHashFile -Encoding ascii -NoNewline
                } catch { }
            } elseif (-not $Rebuild -and (Test-B4wMayStartBackend -CliArgs $RemainingArgs)) {
                # Dev mode rebuilds by itself: the next command that needs the
                # backend sees sources newer than the bundle and runs Maven
                # before serving.  Demanding a manual rebuild here contradicted
                # both that behaviour and `status`, and saying it for `help` /
                # `config` advertised a rebuild those commands never trigger.
                Write-Host 'Backend sources are newer than the runtime bundle; the next backend command rebuilds it automatically (a few minutes).' -ForegroundColor DarkGray
            }
        }
        if ($Rebuild) { $BackendHashToCache = $BackendCurrentHash }
    }
}

# Support explicit passthrough: everything after '--' bypasses the
# script's own param() block and goes directly to the CLI binary.
# This allows flags like -s <session> to work without PowerShell
# interpreting them as parameter names (e.g., -s → -ScriptArgs).
$CliArgs = $RemainingArgs
if ($RemainingArgs -and ($RemainingArgs[0] -eq '--' -or $RemainingArgs[0] -eq '--%')) {
    # Strip the passthrough / stop-parsing token so it doesn't interfere
    # with subcommand routing below.  -- is the conventional passthrough
    # separator.  --% (PowerShell stop-parsing symbol) may still arrive as
    # a literal argument when callers pass it explicitly.
    $CliArgs = $RemainingArgs[1..($RemainingArgs.Count - 1)]
}

# ── Subcommand: coworker ──────────────────────────────────────────────────
# Delegates to coworker/coworker.ps1, forwarding all remaining arguments.
# Special cases:
#   coworker start   — run coworker/start.ps1 (both by default) in the background
#   coworker stop    — stop the background scheduler/GUI
#   coworker restart — stop and restart the background scheduler/GUI
if ($CliArgs -and $CliArgs[0] -eq 'coworker') {
    $CoworkerArgs = if ($CliArgs.Count -gt 1) { ,$CliArgs[1..($CliArgs.Count - 1)] } else { @() }
    $CoworkerPidFile = Join-Path $ScriptDir '.coworker\scheduler.pid'

    # ── coworker start ───────────────────────────────────────────────────
    # Thin wrapper around coworker/start.ps1 so both entry points share the
    # same behavior.  Arguments are forwarded verbatim; b4w only supplies a
    # default when none are given:
    #   b4w coworker start                    → start.ps1 both -Background
    #   b4w coworker start sched|gui|both …   → start.ps1 sched|gui|both …
    # Legacy b4w-only aliases still map onto start.ps1 subcommands (with
    # -Background and PID tracking so stop/restart can manage them):
    #   --sched-only → sched   --gui-only → gui
    if ($CoworkerArgs -and $CoworkerArgs[0] -eq 'start') {
        $StartScript = Join-Path $ScriptDir 'coworker\start.ps1'
        $StartArgs = @()
        if ($CoworkerArgs.Count -gt 1) { $StartArgs = @($CoworkerArgs[1..($CoworkerArgs.Count - 1)]) }

        $legacySchedOnly = $StartArgs -contains '--sched-only'
        $legacyGuiOnly = $StartArgs -contains '--gui-only'

        if ($legacySchedOnly -or $legacyGuiOnly) {
            $LegacySub = if ($legacySchedOnly) { 'sched' } else { 'gui' }
            $LegacyArgs = @($StartArgs | Where-Object { $_ -ne '--sched-only' -and $_ -ne '--gui-only' })
            $ForwardArgs = @($LegacySub) + $LegacyArgs
            if ($ForwardArgs -notcontains '-Background') { $ForwardArgs += '-Background' }
            if ($ForwardArgs -notcontains '-PidFile') { $ForwardArgs += '-PidFile'; $ForwardArgs += $CoworkerPidFile }
            & $StartScript @ForwardArgs
        } elseif ($StartArgs.Count -eq 0) {
            # Default: scheduler + GUI, both detached, PID(s) recorded so
            # `b4w coworker stop` / `restart` can manage them.
            & $StartScript both -Background -PidFile $CoworkerPidFile
        } else {
            # Explicit start.ps1 subcommand and options — behave exactly like
            # running coworker/start.ps1 directly.
            & $StartScript @StartArgs
        }
        exit $LASTEXITCODE
    }

    # ── coworker stop ────────────────────────────────────────────────────
    if ($CoworkerArgs -and $CoworkerArgs[0] -eq 'stop') {
        if (-not (Test-Path $CoworkerPidFile)) {
            Write-Host 'No running Coworker scheduler found.' -ForegroundColor Yellow
            exit 0
        }
        $pidRaw = Get-Content $CoworkerPidFile -Raw
        $pidsToStop = @()
        $pidJson = $null
        try {
            # Newer format: JSON { "scheduler": <pid>, "gui": <pid> } when both
            # scheduler and GUI were started together.
            $pidJson = $pidRaw | ConvertFrom-Json -ErrorAction Stop
        } catch {
            $pidJson = $null
        }
        if ($pidJson -is [System.Management.Automation.PSCustomObject]) {
            if ($pidJson.scheduler) { $pidsToStop += [int]$pidJson.scheduler }
            if ($pidJson.gui) { $pidsToStop += [int]$pidJson.gui }
        } elseif ($pidRaw.Trim() -match '^\d+$') {
            # Legacy format: a single plain PID (note: a bare number is also
            # valid JSON, so only treat PSCustomObject payloads as JSON records)
            $pidsToStop += [int]$pidRaw.Trim()
        }
        if ($pidsToStop.Count -eq 0) {
            Write-Host "No running Coworker process found in PID file: $CoworkerPidFile" -ForegroundColor Yellow
            Remove-Item $CoworkerPidFile -Force -ErrorAction SilentlyContinue
            exit 0
        }
        $stoppedAny = $false
        foreach ($savedPid in $pidsToStop) {
            $proc = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
            if ($proc) {
                Write-Host "Stopping Coworker process (PID $savedPid)..." -ForegroundColor Cyan
                $proc.Kill()
                $stoppedAny = $true
            } else {
                Write-Host "Coworker process (PID $savedPid) is no longer running." -ForegroundColor Yellow
            }
        }
        if ($stoppedAny) {
            Write-Host 'Coworker stopped.' -ForegroundColor Green
        }
        Remove-Item $CoworkerPidFile -Force -ErrorAction SilentlyContinue
        exit 0
    }

    # ── coworker restart ─────────────────────────────────────────────────
    if ($CoworkerArgs -and $CoworkerArgs[0] -eq 'restart') {
        # Stop any recorded background processes first
        if (Test-Path $CoworkerPidFile) {
            $pidRaw = Get-Content $CoworkerPidFile -Raw
            $pidsToStop = @()
            $pidJson = $null
            try {
                $pidJson = $pidRaw | ConvertFrom-Json -ErrorAction Stop
            } catch {
                $pidJson = $null
            }
            if ($pidJson -is [System.Management.Automation.PSCustomObject]) {
                if ($pidJson.scheduler) { $pidsToStop += [int]$pidJson.scheduler }
                if ($pidJson.gui) { $pidsToStop += [int]$pidJson.gui }
            } elseif ($pidRaw.Trim() -match '^\d+$') {
                $pidsToStop += [int]$pidRaw.Trim()
            }
            foreach ($savedPid in $pidsToStop) {
                $proc = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
                if ($proc) {
                    Write-Host "Stopping Coworker process (PID $savedPid)..." -ForegroundColor Cyan
                    $proc.Kill()
                }
            }
            Remove-Item $CoworkerPidFile -Force -ErrorAction SilentlyContinue
        }
        # Start fresh (same default as `b4w coworker start`)
        $StartScript = Join-Path $ScriptDir 'coworker\start.ps1'
        & $StartScript both -Background -PidFile $CoworkerPidFile
        exit $LASTEXITCODE
    }

    $CoworkerScript = Join-Path $ScriptDir 'coworker\coworker.ps1'
    if ($CoworkerArgs) {
        $CoworkerCommand = $CoworkerArgs[0]
        $CoworkerRemaining = @()
        if ($CoworkerArgs.Count -gt 1) {
            $CoworkerRemaining = @($CoworkerArgs[1..($CoworkerArgs.Count - 1)])
        }
        & $CoworkerScript -Command $CoworkerCommand -Remaining $CoworkerRemaining
    } else {
        & $CoworkerScript
    }
    exit $LASTEXITCODE
}

# ── Subcommand: sc ─────────────────────────────────────────────────────────
# Shortcut for the real-world scenario interactive picker.
# `b4w sc`          → test.ps1 rws sc --interactive (launch picker)
# `b4w sc <args>`   → test.ps1 rws sc <args>        (passthrough)
if ($CliArgs -and $CliArgs[0] -eq 'sc') {
    $TestScript = Join-Path $ScriptDir 'bin\test.ps1'
    $ScArgs = if ($CliArgs.Count -gt 1) { @($CliArgs[1..($CliArgs.Count - 1)]) } else { @() }
    if ($ScArgs) {
        # bin/test.ps1 HAS a param() block, so a bare `-i` would be bound to
        # -InformationAction before the script sees it.  Passing each token as a
        # single-quoted literal keeps it a positional string value.  This is safe
        # for the data: a single-quoted PowerShell string expands nothing — `$`,
        # backticks and `$(...)` stay literal — and doubling the internal `'`
        # round-trips the exact text.
        $ScLiterals = ($ScArgs | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ' '
        Invoke-Expression "& '$TestScript' -- rws sc $ScLiterals"
    } else {
        # No args → launch the interactive scenario picker
        & $TestScript rws sc --interactive
    }
    exit $LASTEXITCODE
}

# ── Subcommand: test ──────────────────────────────────────────────────────
# Delegates to bin/test.ps1, forwarding all remaining arguments.
# Same reason as `sc`: the target declares CmdletBinding, so flags must arrive
# as positional string values (see the comment there).
if ($CliArgs -and $CliArgs[0] -eq 'test') {
    $TestScript = Join-Path $ScriptDir 'bin\test.ps1'
    $TestArgs = if ($CliArgs.Count -gt 1) { ,$CliArgs[1..($CliArgs.Count - 1)] } else { @() }
    if ($TestArgs) {
        $TestLiterals = ($TestArgs | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ' '
        Invoke-Expression "& '$TestScript' -- $TestLiterals"
    } else {
        & $TestScript
    }
    exit $LASTEXITCODE
}

# ── Subcommand: build ─────────────────────────────────────────────────────
# Delegates to bin/build.ps1, forwarding all remaining arguments.
if ($CliArgs -and $CliArgs[0] -eq 'build') {
    $BuildScript = Join-Path $ScriptDir 'bin\build.ps1'
    $BuildArgs = if ($CliArgs.Count -gt 1) { ,$CliArgs[1..($CliArgs.Count - 1)] } else { @() }
    if ($BuildArgs) {
        $BuildLiterals = ($BuildArgs | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ' '
        Invoke-Expression "& '$BuildScript' -- $BuildLiterals"
    } else {
        & $BuildScript
    }
    exit $LASTEXITCODE
}

# ── Top-level help ────────────────────────────────────────────────────────
$TopHelp = @'
b4w — browser4-cli wrapper with additional development tools.

b4w is a development-only launcher that builds and runs browser4-cli
directly from the current codebase.  It also bundles extra dev-tool
subcommands (coworker, sc, test, build) that are not part of the published
browser4-cli release.

Usage: b4w [command] [options]

Commands:
  cli [args]       Run browser4-cli (default — can be omitted)
  coworker <cmd>   Manage Coworker tasks (delegates to coworker/coworker.ps1)
  sc [args]        Real-world scenario picker (delegates to bin/test.ps1 rws sc)
  test [args]      Run tests (delegates to bin/test.ps1)
  build [args]     Build the project (delegates to bin/build.ps1)
  b4w install [-WithLaunchers]  Install b4w as a global command (adds to PATH)
  b4w uninstall                Remove b4w from PATH and delete generated launcher

Pass -WithLaunchers to b4w install to also create shortcut launchers so
you can type subcommands directly:
  b4w-coworker       → b4w coworker
  b4w-sc             → b4w sc
  b4w-coworker-fix   → b4w coworker fix
  b4w-test           → b4w test
  b4w-build          → b4w build
  (and all coworker & sc sub-subcommands: b4w-coworker-draft, b4w-sc-add, etc.)

Examples:
  # cli (default — "cli" keyword is optional)
  b4w                                   show this help
  b4w -s my-session                     start a named session
  b4w -s my-session snapshot -i         snapshot — interactive elements only
  b4w --version                         print version
  b4w --help                            CLI help
  b4w -- -s my-session -i               passthrough via --

  # subcommands
  b4w coworker list                     list Coworker tasks
  b4w coworker start                    start the Coworker scheduler + GUI (background)
  b4w coworker start --sched-only       start only the scheduler (background)
  b4w coworker start --gui-only         start only the GUI server (background)
  b4w coworker start sched              run only the scheduler (foreground — Ctrl+C to stop)
  b4w coworker start gui -OpenBrowser   run only the GUI server (foreground — Ctrl+C to stop)
  b4w coworker start both -Port 8091    scheduler + GUI, custom GUI port (background)
  b4w coworker stop                     stop the background scheduler/GUI
  b4w coworker restart                  restart the background scheduler/GUI
  b4w test --e2e                        run E2E tests
  b4w sc                                interactive scenario picker
  b4w sc add my-test https://example.com  create a new scenario
  b4w build                             build browser4-cli
  b4w b4w install                       install b4w globally (one-time setup)
  b4w b4w install -WithLaunchers         install with shortcut launchers
  b4w b4w uninstall                     remove b4w from PATH and launcher

  # subcommand launchers (after b4w install -WithLaunchers)
  b4w-coworker list                     b4w coworker list
  b4w-coworker-fix                      pick and fix a task from 1ready/
  b4w-test fast                         run fast tests
  b4w-build                             build browser4-cli

Wrapper:
  b4w.sh          Git Bash / Linux / macOS wrapper — preferred for bash
                  environments when PowerShell flag interception occurs.

Tip: Use long-form flags (--output, --interactive, --viewport) for
cross-shell compatibility.  Short flags (-o, -i, -v) are now safe with
b4w.ps1 thanks to manual argument parsing that avoids PowerShell's
parameter binder.
'@

# ── Known subcommand launchers ──────────────────────────────────────────────
# When b4w install -WithLaunchers runs, it creates global wrapper scripts so
# subcommands can be invoked directly from the shell without typing "b4w" first:
#   b4w-coworker              → b4w coworker
#   b4w-sc                    → b4w sc
#   b4w-coworker-fix          → b4w coworker fix
#   b4w-test                  → b4w test
#   b4w-build                 → b4w build
#   (and all sc/coworker sub-subcommands: b4w-sc-add, b4w-coworker-draft, etc.)
#
# Each entry maps the launcher script name (without extension) to the
# b4w argument string it delegates to.
# These are NOT created by default — pass -WithLaunchers to b4w install.
$LauncherSubcommands = @(
    @{ Name = 'b4w-coworker';          Args = 'coworker' },
    @{ Name = 'b4w-coworker-draft';    Args = 'coworker draft' },
    @{ Name = 'b4w-coworker-refine';   Args = 'coworker refine' },
    @{ Name = 'b4w-coworker-assign';   Args = 'coworker assign' },
    @{ Name = 'b4w-coworker-list';     Args = 'coworker list' },
    @{ Name = 'b4w-coworker-view';     Args = 'coworker view' },
    @{ Name = 'b4w-coworker-cancel';   Args = 'coworker cancel' },
    @{ Name = 'b4w-coworker-commit';   Args = 'coworker commit' },
    @{ Name = 'b4w-coworker-push';     Args = 'coworker push' },
    @{ Name = 'b4w-coworker-fix';      Args = 'coworker fix' },
    @{ Name = 'b4w-coworker-review';   Args = 'coworker review' },
    @{ Name = 'b4w-coworker-start';    Args = 'coworker start' },
    @{ Name = 'b4w-sc';              Args = 'sc' },
    @{ Name = 'b4w-sc-add';         Args = 'sc add' },
    @{ Name = 'b4w-test';              Args = 'test' },
    @{ Name = 'b4w-build';             Args = 'build' }
)

# ── Subcommand: b4w install ────────────────────────────────────────────────
# Installs the b4w command globally so you can type `b4w <subcommand>` from
# any directory without the .ps1 / .bat / .sh extension.
#
# On non-Windows platforms, installation places a bash launcher script at
#   $HOME/.local/bin/b4w
# and ensures that directory is on the user PATH.  The launcher delegates
# to the repo's b4w.sh via an absolute path, so the repo can be moved or
# deleted independently — just re-run install from the new location.
#
# On Windows, installation adds the repo directory ($ScriptDir) to the user
# PATH so b4w.ps1 / b4w.bat are discoverable from any shell.
#
# Subcommand shortcut launchers (b4w-coworker, b4w-test, etc.) are only
# created when the -WithLaunchers flag is passed.  These are optional
# convenience wrappers; without them, subcommands are invoked as
# `b4w coworker`, `b4w test`, etc.
if ($CliArgs -and $CliArgs[0] -eq 'b4w' -and $CliArgs[1] -eq 'install') {
    $WithLaunchers = ($CliArgs.Count -gt 2 -and $CliArgs[2] -eq '-WithLaunchers')
    Write-Host "Installing b4w command..." -ForegroundColor Cyan

    # ── Non-Windows: install to ~/.local/bin ─────────────────────────────
    if (-not ($IsWindows -or $env:OS -eq 'Windows_NT')) {
        $globalBin = Join-Path $HOME '.local/bin'
        $globalLauncher = Join-Path $globalBin 'b4w'

        # 1. Ensure ~/.local/bin exists.
        if (-not (Test-Path $globalBin)) {
            New-Item -ItemType Directory -Path $globalBin -Force | Out-Null
            Write-Host "  + Created directory: $globalBin" -ForegroundColor Green
        }

        # 2. Create/update the global `b4w` bash launcher that delegates
        #    to this repo's b4w.sh via an absolute path.
        #    We build the content with explicit Unix line endings ("`n")
        #    to avoid CRLF from the .ps1 file leaking into the bash script.
        #    "$ScriptDir" is interpolated; "`$@" produces the literal
        #    bash $@ so arguments are forwarded correctly.
        $launcherExisted = Test-Path $globalLauncher
        $launcherContent = "#!/bin/bash`n" +
            "# b4w — global launcher for browser4-cli.`n" +
            "# Installed by: $ScriptDir/b4w.ps1`n" +
            "# Delegates to: $ScriptDir/b4w.sh`n" +
            "exec `"$ScriptDir/b4w.sh`" `"`$@`"`n"
        # Use ASCII encoding (no BOM) — WriteAllText writes LF on Linux.
        [System.IO.File]::WriteAllText($globalLauncher, $launcherContent, [System.Text.Encoding]::ASCII)

        # Make it executable.
        chmod +x $globalLauncher 2>$null
        if ($launcherExisted) {
            Write-Host "  + Updated global launcher: $globalLauncher" -ForegroundColor Green
        } else {
            Write-Host "  + Created global launcher: $globalLauncher" -ForegroundColor Green
        }

        # 3. Ensure ~/.local/bin is on the user PATH.
        $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
        $entries = @($userPath -split ';' -ne '')
        $alreadyOnPath = $entries | Where-Object {
            $_.TrimEnd('\', '/') -eq $globalBin.TrimEnd('\', '/')
        }
        if (-not $alreadyOnPath) {
            $entries += $globalBin
            $newPath = $entries -join ';'
            [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
            Write-Host "  + Added to user PATH: $globalBin" -ForegroundColor Green
        } else {
            Write-Host "  - Already on PATH: $globalBin" -ForegroundColor DarkGray
        }

        # Also refresh the current process PATH.
        $procEntries = @($env:Path -split ';' -ne '')
        $alreadyInProc = $procEntries | Where-Object {
            $_.TrimEnd('\', '/') -eq $globalBin.TrimEnd('\', '/')
        }
        if (-not $alreadyInProc) {
            $procEntries += $globalBin
            $env:Path = $procEntries -join ';'
        }

        Write-Host ""
        Write-Host "b4w installed successfully!" -ForegroundColor Green
        Write-Host "  bash launcher: $globalLauncher"
        Write-Host "  Repo     : $ScriptDir"

        # ── Create subcommand launchers (only with -WithLaunchers) ──────────
        # Creates b4w-coworker, b4w-test, b4w-coworker-fix, etc. in ~/.local/bin/
        # so subcommands can be invoked directly without typing "b4w" first.
        if ($WithLaunchers) {
            Write-Host ""
            $launcherCount = 0
            foreach ($entry in $LauncherSubcommands) {
                $subLauncherPath = Join-Path $globalBin $entry.Name
                $subExisted = Test-Path $subLauncherPath
                $subContent = "#!/bin/bash`n" +
                    "# $($entry.Name) — global launcher for browser4-cli.`n" +
                    "# Installed by: $ScriptDir/b4w.ps1`n" +
                    "# Delegates to: $ScriptDir/b4w.sh $($entry.Args)`n" +
                    "exec `"$ScriptDir/b4w.sh`" $($entry.Args) `"`$@`"`n"
                [System.IO.File]::WriteAllText($subLauncherPath, $subContent, [System.Text.Encoding]::ASCII)
                chmod +x $subLauncherPath 2>$null
                if ($subExisted) { Write-Host "  + Updated: $subLauncherPath" -ForegroundColor Green }
                else             { Write-Host "  + Created: $subLauncherPath" -ForegroundColor Green }
                $launcherCount++
            }
            Write-Host "  + $launcherCount subcommand launchers installed." -ForegroundColor Green
        }

        Write-Host ""
        Write-Host "Restart your shell (or run 'hash -r' / reopen the terminal)"
        Write-Host "and then you can type just:  b4w <subcommand>" -ForegroundColor Yellow
        Write-Host ""
        Write-Host "Examples:" -ForegroundColor White
        Write-Host "  b4w --help"
        if ($WithLaunchers) {
            Write-Host "  b4w-coworker list"
            Write-Host "  b4w-test --e2e"
            Write-Host "  b4w-build"
        } else {
            Write-Host "  b4w coworker list"
            Write-Host "  b4w test --e2e"
            Write-Host "  b4w build"
        }
        Write-Host "  b4w -- snapshot -i"
        if ($WithLaunchers) {
            Write-Host ""
            Write-Host "Or use subcommand launchers directly:" -ForegroundColor DarkGray
            Write-Host "  b4w-coworker-fix        # → b4w coworker fix" -ForegroundColor DarkGray
            Write-Host "  b4w-coworker-review     # → b4w coworker review" -ForegroundColor DarkGray
            Write-Host "  b4w-test fast           # → b4w test fast" -ForegroundColor DarkGray
        } else {
            Write-Host ""
            Write-Host "Tip: re-run with -WithLaunchers to create b4w-coworker, b4w-test, etc." -ForegroundColor DarkGray
        }

        Set-Location $OriginalCwd
        exit 0
    }

    # ── Windows: add the repo directory to PATH ──────────────────────────
    $normalizedCurrent = $ScriptDir.TrimEnd('\', '/')
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $entries = $userPath -split ';' -ne ''
    $cleaned = $entries | Where-Object {
        $entry = $_.TrimEnd('\', '/')
        -not (Test-Path (Join-Path $entry 'b4w.ps1')) -or $entry -eq $normalizedCurrent
    }
    $alreadyThere = $cleaned | Where-Object { $_.TrimEnd('\', '/') -eq $normalizedCurrent }
    if (-not $alreadyThere) {
        $cleaned += $ScriptDir
        Write-Host "  + Added to user PATH: $ScriptDir" -ForegroundColor Green
    } else {
        Write-Host "  - Already on PATH: $ScriptDir" -ForegroundColor DarkGray
    }
    $newPath = $cleaned -join ';'
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")

    # Also refresh the current process PATH (de-dup + add current).
    $procEntries = $env:Path -split ';' -ne ''
    $procCleaned = $procEntries | Where-Object {
        $entry = $_.TrimEnd('\', '/')
        -not (Test-Path (Join-Path $entry 'b4w.ps1')) -or $entry -eq $normalizedCurrent
    }
    if (-not ($procCleaned | Where-Object { $_.TrimEnd('\', '/') -eq $normalizedCurrent })) {
        $procCleaned += $ScriptDir
    }
    $env:Path = $procCleaned -join ';'

    # ── Create subcommand .bat launchers in the repo root ─────────────────
    # Only created when -WithLaunchers is passed. These are optional
    # convenience wrappers; without them, subcommands are invoked as
    # `b4w coworker`, `b4w test`, etc.
    if ($WithLaunchers) {
        $launcherCount = 0
        foreach ($entry in $LauncherSubcommands) {
            $batPath = Join-Path $ScriptDir "$($entry.Name).bat"
            $batExisted = Test-Path $batPath
            $batContent = "@echo off`r`n" +
                "REM $($entry.Name).bat — Delegates to b4w $($entry.Args)`r`n" +
                "REM Generated by b4w install. Do not edit.`r`n" +
                "setlocal`r`n" +
                "set `"SCRIPT_DIR=%~dp0`"`r`n" +
                "call `"%SCRIPT_DIR%b4w.bat`" $($entry.Args) %*`r`n" +
                "exit /b %ERRORLEVEL%`r`n"
            [System.IO.File]::WriteAllText($batPath, $batContent, [System.Text.Encoding]::ASCII)
            if ($batExisted) { Write-Host "  + Updated: $batPath" -ForegroundColor Green }
            else             { Write-Host "  + Created: $batPath" -ForegroundColor Green }
            $launcherCount++
        }
        Write-Host "  + $launcherCount subcommand launchers installed." -ForegroundColor Green
    }

    Write-Host ""
    Write-Host "b4w installed successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Restart your shell (or run 'refreshenv' / reopen the terminal)"
    Write-Host "and then you can type just:  b4w <subcommand>" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Examples:" -ForegroundColor White
    Write-Host "  b4w --help"
    if ($WithLaunchers) {
        Write-Host "  b4w-coworker list"
        Write-Host "  b4w-test --e2e"
        Write-Host "  b4w-build"
    } else {
        Write-Host "  b4w coworker list"
        Write-Host "  b4w test --e2e"
        Write-Host "  b4w build"
    }
    Write-Host "  b4w -- snapshot -i"
    if ($WithLaunchers) {
        Write-Host ""
        Write-Host "Or use subcommand launchers directly:" -ForegroundColor DarkGray
        Write-Host "  b4w-coworker-fix        # → b4w coworker fix" -ForegroundColor DarkGray
        Write-Host "  b4w-coworker-review     # → b4w coworker review" -ForegroundColor DarkGray
        Write-Host "  b4w-test fast           # → b4w test fast" -ForegroundColor DarkGray
    } else {
        Write-Host ""
        Write-Host "Tip: re-run with -WithLaunchers to create b4w-coworker, b4w-test, etc." -ForegroundColor DarkGray
    }

    Set-Location $OriginalCwd
    exit 0
}

# ── Subcommand: b4w uninstall ──────────────────────────────────────────────
# Removes the globally-installed b4w launcher and cleans up PATH entries.
#
# On non-Windows: removes ~/.local/bin/b4w (the global launcher).
# On Windows: removes the repo directory from the user PATH.
if ($CliArgs -and $CliArgs[0] -eq 'b4w' -and $CliArgs[1] -eq 'uninstall') {
    Write-Host "Uninstalling b4w command..." -ForegroundColor Cyan

    # ── Non-Windows: remove the global launcher ──────────────────────────
    if (-not ($IsWindows -or $env:OS -eq 'Windows_NT')) {
        $globalBin = Join-Path $HOME '.local/bin'
        $globalLauncher = Join-Path $globalBin 'b4w'

        # 1. Remove the global launcher file.
        if (Test-Path $globalLauncher) {
            Remove-Item $globalLauncher -Force
            Write-Host "  + Removed global launcher: $globalLauncher" -ForegroundColor Green
        } else {
            Write-Host "  - Global launcher not found: $globalLauncher" -ForegroundColor DarkGray
        }

        # 2. Remove subcommand launchers (b4w-coworker, b4w-test, etc.)
        #    Only remove files that look like generated launchers (contain
        #    the "global launcher for browser4-cli" marker).
        $subLaunchers = Get-ChildItem -Path $globalBin -Filter 'b4w-*' -File -ErrorAction SilentlyContinue
        $removedCount = 0
        foreach ($s in $subLaunchers) {
            $content = Get-Content $s.FullName -Raw -ErrorAction SilentlyContinue
            if ($content -match 'global launcher for browser4-cli') {
                Remove-Item $s.FullName -Force
                $removedCount++
            }
        }
        if ($removedCount -gt 0) {
            Write-Host "  + Removed $removedCount subcommand launcher(s) from $globalBin" -ForegroundColor Green
        } else {
            Write-Host "  - No subcommand launchers to remove" -ForegroundColor DarkGray
        }

        # 3. Also clean up the legacy repo-local bash launcher if it exists
        #    (from b4w installs prior to the global-install change).
        $legacyLauncher = Join-Path $ScriptDir 'b4w'
        if (Test-Path $legacyLauncher) {
            # Only remove if it looks like a generated launcher (not a
            # hand-maintained script).
            $content = Get-Content $legacyLauncher -Raw -ErrorAction SilentlyContinue
            if ($content -match 'b4w — short-form launcher') {
                Remove-Item $legacyLauncher -Force
                Write-Host "  + Removed legacy repo launcher: $legacyLauncher" -ForegroundColor Green
            }
        }

        # 4. Clean any b4w-repo PATH entries from the user PATH (legacy).
        $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
        $normalizedRepo = $ScriptDir.TrimEnd('\', '/')
        $entries = @($userPath -split ';' -ne '')
        $cleaned = $entries | Where-Object {
            $entry = $_.TrimEnd('\', '/')
            # Remove entries that point to this repo (legacy install style).
            -not (Test-Path (Join-Path $entry 'b4w.ps1') -and $entry -eq $normalizedRepo)
        }
        if ($cleaned.Count -ne $entries.Count) {
            $newPath = $cleaned -join ';'
            [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
            Write-Host "  + Removed repo from user PATH: $ScriptDir" -ForegroundColor Green

            # Also refresh current process PATH.
            $procEntries = @($env:Path -split ';' -ne '')
            $procCleaned = $procEntries | Where-Object {
                $e = $_.TrimEnd('\', '/')
                -not (Test-Path (Join-Path $e 'b4w.ps1') -and $e -eq $normalizedRepo)
            }
            if ($procCleaned.Count -ne $procEntries.Count) {
                $env:Path = $procCleaned -join ';'
            }
        } else {
            Write-Host "  - Repo not on user PATH (nothing to clean)" -ForegroundColor DarkGray
        }

        Write-Host ""
        Write-Host "b4w uninstalled successfully!" -ForegroundColor Green
        Write-Host ""
        Write-Host "Note: ~/.local/bin remains on your PATH (used by other tools)." -ForegroundColor DarkGray
        Write-Host "To reinstall later:  ./b4w.ps1 b4w install"

        Set-Location $OriginalCwd
        exit 0
    }

    # ── Windows: remove the repo directory from PATH ─────────────────────
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($userPath -like "*$ScriptDir*") {
        $entries = $userPath -split ';' -ne ''
        $cleaned = $entries | Where-Object {
            $normalized = $_.TrimEnd('\', '/')
            $normalized -ne $ScriptDir.TrimEnd('\', '/')
        }
        $newPath = $cleaned -join ';'
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
        Write-Host "  + Removed from user PATH: $ScriptDir" -ForegroundColor Green
    } else {
        Write-Host "  - Not on user PATH: $ScriptDir" -ForegroundColor DarkGray
    }

    # Also remove from the current process PATH.
    if ($env:Path -like "*$ScriptDir*") {
        $entries = $env:Path -split ';' -ne ''
        $cleaned = $entries | Where-Object {
            $normalized = $_.TrimEnd('\', '/')
            $normalized -ne $ScriptDir.TrimEnd('\', '/')
        }
        $env:Path = $cleaned -join ';'
        Write-Host "  + Removed from current session PATH" -ForegroundColor Green
    }

    # ── Remove subcommand .bat launchers from the repo root ─────────────
    $batLaunchers = Get-ChildItem -Path $ScriptDir -Filter 'b4w-*.bat' -File -ErrorAction SilentlyContinue
    $batCount = 0
    foreach ($b in $batLaunchers) {
        $c = Get-Content $b.FullName -Raw -ErrorAction SilentlyContinue
        if ($c -match 'Generated by b4w install') {
            Remove-Item $b.FullName -Force
            $batCount++
        }
    }
    if ($batCount -gt 0) {
        Write-Host "  + Removed $batCount subcommand .bat launcher(s) from $ScriptDir" -ForegroundColor Green
    } else {
        Write-Host "  - No subcommand .bat launchers to remove" -ForegroundColor DarkGray
    }

    Write-Host ""
    Write-Host "b4w uninstalled successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Restart your shell for the PATH change to take full effect." -ForegroundColor Yellow
    Write-Host "To reinstall later:  ./b4w.ps1 b4w install"

    Set-Location $OriginalCwd
    exit 0
}

# ── b4w subcommand help ────────────────────────────────────────────────────
$B4wHelp = @'
Usage: b4w b4w <subcommand>

Manage the b4w launcher itself.

Subcommands:
  b4w install [-WithLaunchers]  Install b4w as a global command (adds to PATH)
  b4w uninstall                 Remove b4w from PATH and delete all generated launchers

With -WithLaunchers, subcommand shortcut launchers are created so you can type:
  b4w-coworker       → b4w coworker
  b4w-sc             → b4w sc
  b4w-coworker-fix   → b4w coworker fix
  b4w-test           → b4w test
  b4w-build          → b4w build
  (and all coworker & sc sub-subcommands: b4w-coworker-draft, b4w-sc-add, etc.)

Examples:
  ./b4w.ps1 b4w install                 install b4w globally
  ./b4w.ps1 b4w install -WithLaunchers   install with shortcut launchers
  ./b4w.ps1 b4w uninstall               remove b4w from PATH
'@

# ── Subcommand: b4w (bare / unknown subcommand) ───────────────────────────
# Running `b4w b4w` (bare) or `b4w b4w <unknown>` prints b4w-specific help.
if ($CliArgs -and $CliArgs[0] -eq 'b4w') {
    Write-Host $B4wHelp
    Set-Location $OriginalCwd
    exit 0
}

# No arguments: show help
if (-not $CliArgs) {
    Write-Host $TopHelp
    exit 0
}

# ── Subcommand: cli ───────────────────────────────────────────────────────
# "cli" is explicit; when omitted, any unrecognized first token is treated
# as the start of CLI arguments (implicit cli).  Strip an explicit "cli"
# so the remaining args pass straight through to the binary.
if ($CliArgs[0] -eq 'cli') {
    $CliArgs = $CliArgs[1..$CliArgs.Length]
}

# Forward the arguments as argv.
#
# This used to build a quoted string and run it through Invoke-Expression,
# which sent every argument through a SECOND PowerShell parse: `$VAR` was
# expanded, `$(...)` was EXECUTED, and a backtick escape was interpreted — so
# `agent run 'first run `browser4-cli list`'` reached the CLI with a U+0008
# backspace in place of the backtick+b, and a task containing $(...) ran shell
# code.  Splatting an array of already-parsed tokens cannot do that: the
# dash-prefixed CLI flags below are plain arguments of a native command (no
# parameter binder involved, because this script declares no param() block),
# and pwsh 7.3+'s standard quoting keeps spaces, quotes, `$`, backticks and
# empty strings intact.
#
# BROWSER4_CLI_INVOKE_HINT tells the CLI how the user actually invoked it, so
# hints print `./b4w.ps1 list` instead of the resolved `browser4-cli.exe list`.
if (-not $env:BROWSER4_CLI_INVOKE_HINT) {
    $env:BROWSER4_CLI_INVOKE_HINT = './b4w.ps1'
}

if (Test-Path $Exe) {
    if ($CliArgs) {
        & $Exe @CliArgs
    } else {
        & $Exe
    }
} else {
    if ($CliArgs) {
        & cargo run --manifest-path $Manifest -- @CliArgs
    } else {
        cargo run --manifest-path $Manifest
    }
}

# After a -Rebuild-forced bundle rebuild, persist the backend source hash so
# subsequent invocations skip the staleness warning.  The hash is written only
# when the rebuild demonstrably happened — i.e. the bundle's build stamp
# advanced during this invocation.  Writing it unconditionally after -Rebuild
# made commands that never start the daemon (help, status, doctor) look like
# completed rebuilds and silently suppressed every later one.
if ($ForcedBundleRebuild -and $BackendHashToCache) {
    # A first build has no stamp before the CLI runs, so resolve it again now.
    $stampPath = $BackendBundleStampPath
    if (-not $stampPath) {
        $stampPath = Get-B4wBackendBundleStampPath
    }
    $stampAfter = if ($stampPath -and (Test-Path -LiteralPath $stampPath)) {
        (Get-Item -LiteralPath $stampPath).LastWriteTime
    } else {
        $null
    }
    $rebuilt = $stampAfter -and (!$BackendBundleStampBefore -or $stampAfter -gt $BackendBundleStampBefore)
    if ($rebuilt) {
        try {
            New-Item -Path (Split-Path $BackendHashFile -Parent) -ItemType Directory -Force -ErrorAction SilentlyContinue > $null
            $BackendHashToCache | Out-File -FilePath $BackendHashFile -Encoding ascii -NoNewline
        } catch { }
    } else {
        Write-Host 'The backend bundle was not rebuilt by this command; the staleness marker is unchanged.' -ForegroundColor DarkGray
    }
}

# Capture the CLI's exit status BEFORE restoring the working directory.
# All four invocation branches above (direct exe / cargo run fallback, with
# or without args) run a native command whose status lands in $LASTEXITCODE.
$CliExitCode = $LASTEXITCODE

# Restore the original working directory so the caller's shell session
# (e.g., Git Bash) sees a consistent CWD after b4w.ps1 exits.
Set-Location $OriginalCwd

# Propagate the CLI's exit code so scripts, CI, and &&-chains can detect
# failure.  Without this, pwsh -File / Git Bash / b4w.bat invocations of
# this script always report success even when the CLI printed an error
# (usage errors exit 2, tool failures exit 1, ...).  cmdlets like
# Set-Location above do not modify $LASTEXITCODE, so it still holds the
# CLI's status here.
exit $CliExitCode

} finally {
    # Restore the caller's force flag: it belongs to this invocation only.
    if ($null -eq $OriginalForceRebuildBundle) {
        Remove-Item Env:BROWSER4_CLI_FORCE_REBUILD_BUNDLE -ErrorAction SilentlyContinue
    } else {
        $env:BROWSER4_CLI_FORCE_REBUILD_BUNDLE = $OriginalForceRebuildBundle
    }
}
