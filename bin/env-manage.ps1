# ==============================================================================
# Browser4 Environment Variable Manager (PowerShell)
# ==============================================================================
# Usage:
#   .\bin\env-manage.ps1 show              Show all Browser4 env vars (secrets masked)
#   .\bin\env-manage.ps1 show -Reveal      Show all with secrets UNMASKED
#   .\bin\env-manage.ps1 show -Detailed    Show all with descriptions and defaults
#   .\bin\env-manage.ps1 show BROWSER4_CLI Show vars matching a prefix
#   .\bin\env-manage.ps1 get <VAR>         Print the raw value of a single env var
#   .\bin\env-manage.ps1 set <VAR> <VALUE> Set an env var (process, user, or machine)
#   .\bin\env-manage.ps1 unset <VAR>       Unset an env var in this process
#   .\bin\env-manage.ps1 export            Print set commands to save in a profile
#   .\bin\env-manage.ps1 export -Reveal    Print set commands with secrets UNMASKED
#   .\bin\env-manage.ps1 list              List all known Browser4 env var names
#   .\bin\env-manage.ps1 list -Sensitive   List only sensitive (secret-bearing) vars
#   .\bin\env-manage.ps1 help              Show this help
# ==============================================================================

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('show', 'get', 'set', 'unset', 'export', 'list', 'help')]
    [string]$Command = 'help',

    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

# ---- Env var registry ----
$KnownVars = @(
    # ---- CLI: Runtime & Paths ----
    @{Name='BROWSER4_EXTENSION_TOKEN';                Category='cli';           Default='';   Desc='Chrome extension auth token for auto-approval of attach requests'; Sensitive=$true}
    @{Name='BROWSER4_CLI_SESSION';                     Category='cli';           Default='';   Desc='Default session ID for the CLI'}
    @{Name='BROWSER4_CLI_STATE_DIR';                   Category='cli';           Default='~/.browser4'; Desc='Override CLI session state directory'}
    @{Name='BROWSER4_RUNTIME_DIR';                     Category='cli';           Default='platform-dependent'; Desc='Override runtime bundle & download cache directory'}
    @{Name='BROWSER4_SKILLS_DIR';                      Category='cli';           Default='versioned-install-dir'; Desc='Override skills directory path'}
    @{Name='BROWSER4_BROWSER_PATH';                    Category='cli';           Default='(auto-detect)'; Desc='Custom Chromium/Chrome browser executable path'}
    @{Name='BROWSER4_SERVER_OPTS';                     Category='cli';           Default='';   Desc='JVM options injected into backend server launch (space-separated)'}
    @{Name='BROWSER4_SERVER_LOG_DIR';                  Category='cli';           Default='(RUNTIME_DIR)/logs'; Desc='Directory for server startup logs'}

    # ---- CLI: Download & Mirror ----
    @{Name='BROWSER4_RELEASES_BASE_URL';               Category='cli';           Default='https://github.com/platonAI/Browser4/releases'; Desc='Override releases download base URL'}
    @{Name='BROWSER4_MIRRORS_CONFIG';                  Category='cli';           Default='runtime/mirrors.json'; Desc='Override mirror configuration file path'}
    @{Name='BROWSER4_CLI_MIRROR_CHECK_TIMEOUT_SECS';   Category='cli';           Default='5';  Desc='Mirror reachability check timeout (seconds)'}
    @{Name='BROWSER4_CLI_MIRROR_SPEED_TEST_TIMEOUT_SECS'; Category='cli';        Default='30'; Desc='Per-mirror speed test download timeout (seconds)'}
    @{Name='BROWSER4_CLI_MIRROR_PREFERENCE_TTL_SECS';  Category='cli';           Default='86400'; Desc='Cached mirror preference TTL (seconds, 86400=24h)'}
    @{Name='BROWSER4_CLI_DOWNLOAD_TIMEOUT_SECS';       Category='cli';           Default='1800'; Desc='Download timeout (seconds, 1800=30min)'}
    @{Name='BROWSER4_CLI_DISABLE_MIRROR_SPEED_TEST';   Category='cli';           Default='';   Desc='Set to 1 to disable speed testing (TCP-only fallback)'}
    @{Name='BROWSER4_CLI_INVOKE_DIR';                  Category='cli';           Default='pwd'; Desc='Root search start directory for relative paths'}

    # ---- CLI: Build & Bundle ----
    @{Name='BROWSER4_CLI_FORCE_REMOTE_BUNDLE';         Category='cli';           Default='';   Desc='Set to 1/true/yes/on to force download from remote releases'}
    @{Name='BROWSER4_CLI_FORCE_REBUILD_BUNDLE';        Category='cli';           Default='';   Desc='Set to 1/true/yes/on to force rebuild from source'}

    # ---- CLI: Timeouts ----
    @{Name='BROWSER4_CLI_HTTP_TIMEOUT_SECS';           Category='cli';           Default='30';  Desc='Default HTTP request timeout (seconds)'}
    @{Name='BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS';     Category='cli';           Default='120'; Desc='Navigation request timeout (seconds)'}
    @{Name='BROWSER4_CLI_INPUT_TIMEOUT_SECS';          Category='cli';           Default='90';  Desc='Text input request timeout (seconds)'}
    @{Name='BROWSER4_CLI_SNAPSHOT_TIMEOUT_SECS';       Category='cli';           Default='60';  Desc='Snapshot request timeout (seconds)'}
    @{Name='BROWSER4_CLI_AGENT_TIMEOUT_SECS';          Category='cli';           Default='180'; Desc='Agent tool request timeout (seconds)'}
    @{Name='BROWSER4_CLI_ACT_TIMEOUT_SECS';            Category='cli';           Default='60';  Desc='Act tool request timeout (seconds)'}
    @{Name='BROWSER4_CLI_CRAWL_TIMEOUT_SECS';          Category='cli';           Default='600'; Desc='Crawl request timeout (seconds)'}

    # ---- CLI: Proxy ----
    @{Name='BROWSER4_CLI_PROXY';                       Category='cli';           Default='';   Desc='Explicit CLI download proxy URL (set by --proxy <url>)'}
    @{Name='BROWSER4_CLI_TEST_TEMPORARY_PROFILE';      Category='cli';           Default='';   Desc='Internal test flag for temporary profile'}

    # ---- LLM / AI Provider Keys ----
    @{Name='LLM_API_KEY';                              Category='llm';           Default='';   Desc='Generic LLM API key (fallback for all providers)'; Sensitive=$true}
    @{Name='OPENAI_API_KEY';                           Category='llm';           Default='';   Desc='OpenAI API key'; Sensitive=$true}
    @{Name='OPENROUTER_API_KEY';                       Category='llm';           Default='';   Desc='OpenRouter API key'; Sensitive=$true}
    @{Name='DEEPSEEK_API_KEY';                         Category='llm';           Default='';   Desc='DeepSeek API key'; Sensitive=$true}
    @{Name='VOLCENGINE_API_KEY';                       Category='llm';           Default='';   Desc='Volcengine (ByteDance) API key'; Sensitive=$true}

    # ---- CAPTCHA Solving ----
    @{Name='CAPSOLVER_KEY';                            Category='captcha';       Default='';   Desc='Capsolver API key for CAPTCHA solving'; Sensitive=$true}
    @{Name='TWOCAPTCHA_KEY';                           Category='captcha';       Default='';   Desc='2Captcha API key for CAPTCHA solving'; Sensitive=$true}
    @{Name='ANTICAPTCHA_KEY';                          Category='captcha';       Default='';   Desc='Anti-Captcha API key for CAPTCHA solving'; Sensitive=$true}

    # ---- Observability (OpenTelemetry + Metrics) ----
    @{Name='OTEL_TRACES_ENABLED';                      Category='observability'; Default='true'; Desc='Enable OpenTelemetry tracing (true/false)'}
    @{Name='OTEL_EXPORTER_OTLP_ENDPOINT';              Category='observability'; Default='http://localhost:4317'; Desc='OTLP gRPC collector endpoint'}
    @{Name='OTEL_SERVICE_NAME';                        Category='observability'; Default='browser4-agentic'; Desc='OpenTelemetry service name'}
    @{Name='OTEL_SERVICE_VERSION';                     Category='observability'; Default='4.8.1-SNAPSHOT'; Desc='OpenTelemetry service version'}
    @{Name='METRICS_ENABLED';                          Category='observability'; Default='true'; Desc='Enable metrics collection (true/false)'}
    @{Name='METRICS_PREFIX';                           Category='observability'; Default='pulsar_agentic'; Desc='Metrics name prefix'}
    @{Name='METRICS_COMMON_TAGS';                      Category='observability'; Default='';   Desc='Comma-separated key=value pairs for metrics common tags'}

    # ---- Backend (Kotlin/Spring) ----
    @{Name='BROWSER4_TAB_READ_ACTIONS_WHITELIST';      Category='backend';       Default='';   Desc='Comma-separated list of read actions (e.g. read_snapshot,read_state)'}
    @{Name='PLAYWRIGHT_BROWSERS_PATH';                 Category='backend';       Default='';   Desc='Custom path to Playwright browser binaries'}

    # ---- E2E Testing ----
    @{Name='BROWSER4_E2E_SERVICE_URL';                 Category='test';          Default='';   Desc='External Browser4 service URL for E2E tests'}
    @{Name='BROWSER4_E2E_SERVER_URL';                  Category='test';          Default='';   Desc='Alias for BROWSER4_E2E_SERVICE_URL'}
    @{Name='BROWSER4_E2E_FIXTURE_HOST';                Category='test';          Default='127.0.0.1'; Desc='Host the Browser4 container uses to reach fixture server'}
    @{Name='BROWSER4_E2E_CLI_TIMEOUT_SECS';            Category='test';          Default='';   Desc='Override per-command timeout in E2E tests (seconds)'}
    @{Name='BROWSER4_E2E_USE_MAVEN_STARTUP';           Category='test';          Default='';   Desc='Set to 1/true/yes/on for Maven-based startup in E2E'}
    @{Name='BROWSER4_E2E_FORCE_REMOTE_BUNDLE';         Category='test';          Default='';   Desc='Force remote bundle in E2E tests'}

    # ---- Mock Site Testing ----
    @{Name='MOCK_SITE_PORT';                           Category='test';          Default='8182'; Desc='Port for mock site test server'}
    @{Name='MOCK_SITE_WAIT_SEC';                       Category='test';          Default='';   Desc='Wait seconds for mock site startup'}

    # ---- Example Crawlers (credentials) ----
    @{Name='PULSAR_TAOBAO_USERNAME';                   Category='example';       Default='';   Desc='Taobao login username (example crawlers)'; Sensitive=$true}
    @{Name='PULSAR_TAOBAO_PASSWORD';                   Category='example';       Default='';   Desc='Taobao login password (example crawlers)'; Sensitive=$true}
    @{Name='PULSAR_SIMUWANG_USERNAME';                 Category='example';       Default='';   Desc='Simuwang login username (example crawlers)'; Sensitive=$true}
    @{Name='PULSAR_SIMUWANG_PASSWORD';                 Category='example';       Default='';   Desc='Simuwang login password (example crawlers)'; Sensitive=$true}

    # ---- System / Generic (read by Browser4) ----
    @{Name='http_proxy';                               Category='proxy';         Default='';   Desc='HTTP proxy (lowercase)'}
    @{Name='HTTP_PROXY';                               Category='proxy';         Default='';   Desc='HTTP proxy (uppercase)'}
    @{Name='https_proxy';                              Category='proxy';         Default='';   Desc='HTTPS proxy (lowercase)'}
    @{Name='HTTPS_PROXY';                              Category='proxy';         Default='';   Desc='HTTPS proxy (uppercase)'}
    @{Name='all_proxy';                                Category='proxy';         Default='';   Desc='All-protocols proxy (lowercase)'}
    @{Name='ALL_PROXY';                                Category='proxy';         Default='';   Desc='All-protocols proxy (uppercase)'}
    @{Name='TZ';                                       Category='generic';       Default='';   Desc='System timezone'}
    @{Name='LC_ALL';                                   Category='generic';       Default='';   Desc='Locale override (all categories)'}
    @{Name='LANG';                                     Category='generic';       Default='';   Desc='Locale (language)'}
    @{Name='LC_CTYPE';                                 Category='generic';       Default='';   Desc='Locale (character classification)'}
    @{Name='LC_MESSAGES';                              Category='generic';       Default='';   Desc='Locale (messages)'}
)

# ---- helpers ----

function Mask-Value {
    param([string]$Value)
    if ($Value.Length -le 7) {
        return '****'
    }
    return '{0}****{1}' -f $Value.Substring(0, 4), $Value.Substring($Value.Length - 3)
}

function Write-CategoryColor {
    param([string]$Category)
    switch ($Category) {
        'cli'           { Write-Host -NoNewline -ForegroundColor Cyan       ('{0,-12}' -f $Category) }
        'llm'           { Write-Host -NoNewline -ForegroundColor Magenta    ('{0,-12}' -f $Category) }
        'captcha'       { Write-Host -NoNewline -ForegroundColor Red        ('{0,-12}' -f $Category) }
        'observability' { Write-Host -NoNewline -ForegroundColor Yellow     ('{0,-12}' -f $Category) }
        'backend'       { Write-Host -NoNewline -ForegroundColor Blue       ('{0,-12}' -f $Category) }
        'test'          { Write-Host -NoNewline -ForegroundColor DarkMagenta('{0,-12}' -f $Category) }
        'example'       { Write-Host -NoNewline -ForegroundColor Gray       ('{0,-12}' -f $Category) }
        'proxy'         { Write-Host -NoNewline -ForegroundColor DarkYellow ('{0,-12}' -f $Category) }
        'generic'       { Write-Host -NoNewline -ForegroundColor DarkGray   ('{0,-12}' -f $Category) }
    }
}

function Write-SecretBadge {
    param([bool]$Sensitive)
    if ($Sensitive) {
        Write-Host -NoNewline -ForegroundColor Red ' [secret]'
    }
}

function Write-ValueColor {
    param([string]$Value, [bool]$Sensitive = $false, [bool]$Reveal = $false)

    if (-not $Value) {
        Write-Host -NoNewline -ForegroundColor DarkGray '<not set>'
        return
    }

    if ($Sensitive -and -not $Reveal) {
        Write-Host -NoNewline -ForegroundColor Yellow (Mask-Value $Value)
        return
    }

    # Non-sensitive or revealed: show full value (truncate if very long)
    if ($Value.Length -gt 60) {
        Write-Host -NoNewline -ForegroundColor Green ('{0}...' -f $Value.Substring(0, 60))
    } else {
        Write-Host -NoNewline -ForegroundColor Green $Value
    }
}

function Show-All {
    param([switch]$Detailed, [string]$Filter, [switch]$Reveal, [switch]$SensitiveOnly)

    if ($Reveal) {
        Write-Host "Browser4 Environment Variables  *** SECRETS VISIBLE ***" -ForegroundColor White
    } else {
        Write-Host "Browser4 Environment Variables" -ForegroundColor White
    }
    Write-Host "==============================" -ForegroundColor White
    Write-Host ""

    $count = 0; $setCount = 0

    foreach ($entry in $KnownVars) {
        if ($Filter -and -not $entry.Name.StartsWith($Filter)) { continue }
        if ($SensitiveOnly -and -not $entry.Sensitive) { continue }

        $val = [Environment]::GetEnvironmentVariable($entry.Name, 'Process')
        if ($val) { $setCount++ }
        $count++

        $isSensitive = [bool]$entry.Sensitive

        if ($Detailed) {
            Write-Host "──────────────────────────────────────────────────────────────────"
            Write-Host -NoNewline ('  Variable:    {0}' -f $entry.Name)
            Write-SecretBadge $isSensitive
            Write-Host ''
            Write-Host -NoNewline '  Category:    '; Write-CategoryColor $entry.Category; Write-Host ''
            Write-Host -NoNewline '  Value:       '; Write-ValueColor $val -Sensitive:$isSensitive -Reveal:$Reveal; Write-Host ''
            if ($isSensitive -and -not $Reveal -and $val) {
                Write-Host -ForegroundColor DarkGray ('  (masked:     use -Reveal or ''get {0}'' for full value)' -f $entry.Name)
            }
            Write-Host ('  Default:     {0}' -f $(if ($entry.Default) { $entry.Default } else { '<none>' }))
            Write-Host ('  Description: {0}' -f $entry.Desc)
            Write-Host ''
        } else {
            Write-Host -NoNewline ('  {0,-40}' -f $entry.Name)
            Write-SecretBadge $isSensitive
            Write-Host -NoNewline '  '
            Write-ValueColor $val -Sensitive:$isSensitive -Reveal:$Reveal
            Write-Host -NoNewline ('  {0,-12}  ' -f $(if ($entry.Default) { $entry.Default } else { '-' }))
            Write-Host $entry.Desc
        }
    }

    Write-Host ''
    Write-Host "──────────────────────────────────────────────────────────────────"
    Write-Host ('  {0}/{1} variables configured' -f $setCount, $count)
    if (-not $Reveal) {
        Write-Host -ForegroundColor DarkGray '  Secrets masked — use -Reveal to show full values'
    }
    if ($Filter) {
        Write-Host ('  Filter: names starting with "{0}"' -f $Filter)
    }
    if ($SensitiveOnly) {
        Write-Host -ForegroundColor DarkGray '  Showing only sensitive (secret-bearing) variables'
    }
}

function Show-Usage {
    Get-Help $PSCommandPath -Detailed
}

switch ($Command) {
    'show' {
        $Detailed = $false
        $Filter = ''
        $Reveal = $false
        $SensitiveOnly = $false
        if ($Arguments) {
            foreach ($a in $Arguments) {
                switch ($a) {
                    '-Detailed'   { $Detailed = $true }
                    '-d'          { $Detailed = $true }
                    '-Reveal'     { $Reveal = $true }
                    '-r'          { $Reveal = $true }
                    '-Sensitive'  { $SensitiveOnly = $true }
                    '-h'          { Show-Usage; return }
                    '--help'      { Show-Usage; return }
                    default       { $Filter = $a }
                }
            }
        }
        Show-All -Detailed:$Detailed -Filter:$Filter -Reveal:$Reveal -SensitiveOnly:$SensitiveOnly
    }
    'get' {
        $name = $Arguments[0]
        $val = [Environment]::GetEnvironmentVariable($name, 'Process')
        if (-not $val) {
            Write-Host '<not set>'
            exit 1
        }
        # get always returns the raw value — it's an explicit request
        Write-Host $val
    }
    'set' {
        $name = $Arguments[0]
        $value = $Arguments[1]
        $scope = if ($Arguments.Count -gt 2) { $Arguments[2] } else { 'Process' }
        [Environment]::SetEnvironmentVariable($name, $value, $scope)
        Write-Host -NoNewline ('Set {0} ({1}) = ' -f $name, $scope)
        # Check if this is a known sensitive var for masked display
        $isSensitive = ($KnownVars | Where-Object { $_.Name -eq $name } | ForEach-Object { [bool]$_.Sensitive }) -or $false
        Write-ValueColor $value -Sensitive:$isSensitive
        Write-Host ''
    }
    'unset' {
        $name = $Arguments[0]
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        Write-Host ('Unset {0}' -f $name)
    }
    'export' {
        $Reveal = $false
        if ($Arguments) {
            foreach ($a in $Arguments) {
                if ($a -eq '-Reveal' -or $a -eq '-r') { $Reveal = $true }
            }
        }
        Write-Host '# Browser4 environment variables — source this file:'
        Write-Host '# Run: Invoke-Expression (. .\bin\env-manage.ps1 export | Out-String)'
        if (-not $Reveal) {
            Write-Host '# Secrets are MASKED — use ''export -Reveal'' to write full values.'
            Write-Host '# Run ''get <VAR>'' to retrieve individual raw values.'
        }
        Write-Host ''
        foreach ($entry in $KnownVars) {
            $val = [Environment]::GetEnvironmentVariable($entry.Name, 'Process')
            if ($val) {
                $isSensitive = [bool]$entry.Sensitive
                if ($isSensitive -and -not $Reveal) {
                    Write-Host ('# $env:{0} = "{1}"   # [secret] masked — use ''export -Reveal'' or ''get {0}''' -f $entry.Name, (Mask-Value $val))
                } else {
                    Write-Host ('$env:{0} = "{1}"' -f $entry.Name, $val.Replace('"', '`"'))
                }
            }
        }
    }
    'list' {
        $Filter = ''
        $SensitiveOnly = $false
        if ($Arguments) {
            foreach ($a in $Arguments) {
                if ($a -eq '-Sensitive') { $SensitiveOnly = $true }
                else { $Filter = $a }
            }
        }
        foreach ($entry in $KnownVars) {
            if ($Filter -and -not $entry.Name.StartsWith($Filter)) { continue }
            if ($SensitiveOnly -and -not $entry.Sensitive) { continue }
            Write-Host -NoNewline ('  {0,-45}  [{1}]  {2}' -f $entry.Name, $entry.Category, $entry.Desc)
            if ($entry.Sensitive) {
                Write-Host -ForegroundColor Red ' [secret]'
            } else {
                Write-Host ''
            }
        }
    }
    'help' {
        Show-Usage
    }
    default {
        Write-Host ('Unknown command: {0}' -f $Command)
        Show-Usage
        exit 1
    }
}
