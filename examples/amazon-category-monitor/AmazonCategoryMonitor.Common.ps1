#Requires -Version 7.0

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-DefaultMonitorDataRoot {
    if ($env:BROWSER4_AMAZON_MONITOR_HOME) {
        return $env:BROWSER4_AMAZON_MONITOR_HOME
    }
    return (Join-Path $HOME ".browser4/amazon-category-monitor")
}

function ConvertTo-NormalizedAmazonUrl {
    param([Parameter(Mandatory)][string]$Url)

    $value = $Url.Trim()
    $uri = $null
    if (-not [Uri]::TryCreate($value, [UriKind]::Absolute, [ref]$uri) -or
        ($uri.Scheme -ne [Uri]::UriSchemeHttp -and $uri.Scheme -ne [Uri]::UriSchemeHttps)) {
        throw "Expected an absolute Amazon http(s) URL: $value"
    }

    $hostName = $uri.IdnHost.ToLowerInvariant()
    if ($hostName -notmatch '(^|\.)amazon\.[a-z0-9.-]+$') {
        throw "Not an Amazon host '$hostName': $value"
    }

    $builder = [UriBuilder]::new($uri)
    $builder.Host = $hostName
    $builder.Fragment = ""
    if ($uri.IsDefaultPort) {
        $builder.Port = -1
    }

    $kept = [System.Collections.Generic.List[object]]::new()
    $trackingKey = '^(utm_.+|ref|ref_|tag|ascsubtag|linkcode|linkid|qid|sr|sprefix|crid|dib|dib_tag|pf_rd_.+|pd_rd_.+|smid)$'
    if ($uri.Query.Length -gt 1) {
        foreach ($part in $uri.Query.Substring(1).Split('&', [StringSplitOptions]::RemoveEmptyEntries)) {
            $pieces = $part.Split('=', 2)
            $key = [Net.WebUtility]::UrlDecode($pieces[0])
            $itemValue = if ($pieces.Count -eq 2) { [Net.WebUtility]::UrlDecode($pieces[1]) } else { "" }
            if ($key -match $trackingKey) {
                continue
            }
            $kept.Add([pscustomobject]@{ Key = $key; Value = $itemValue })
        }
    }

    $queryParts = $kept |
        Sort-Object -Property Key, Value |
        ForEach-Object {
            $encodedKey = [Net.WebUtility]::UrlEncode($_.Key)
            $encodedValue = [Net.WebUtility]::UrlEncode($_.Value)
            if ($encodedValue) { "$encodedKey=$encodedValue" } else { $encodedKey }
        }
    $builder.Query = ($queryParts -join '&')
    return $builder.Uri.AbsoluteUri
}

function Get-CategoryIdFromUrl {
    param([Parameter(Mandatory)][string]$Url)

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Url)
        $hash = $sha.ComputeHash($bytes)
        $hex = [Convert]::ToHexString($hash).ToLowerInvariant()
        return "cat_$($hex.Substring(0, 16))"
    }
    finally {
        $sha.Dispose()
    }
}

function Get-JsonValue {
    param(
        [Parameter(Mandatory)][object]$InputObject,
        [Parameter(Mandatory)][string]$Name,
        [object]$Default = $null
    )

    $property = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $Default
    }
    return $property.Value
}

function Write-CsvRow {
    param(
        [Parameter(Mandatory)][IO.StreamWriter]$Writer,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Values
    )

    $encoded = foreach ($item in $Values) {
        $text = if ($null -eq $item) { "" } else { [string]$item }
        if ($text.Contains('"') -or $text.Contains(',') -or $text.Contains("`r") -or $text.Contains("`n")) {
            '"' + $text.Replace('"', '""') + '"'
        }
        else {
            $text
        }
    }
    $Writer.WriteLine(($encoded -join ','))
}

function Write-ManifestAtomic {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Rows
    )

    $tempPath = "$Path.tmp-$PID-$([Guid]::NewGuid().ToString('N'))"
    try {
        $Rows | Export-Csv -Path $tempPath -NoTypeInformation -Encoding utf8
        Move-Item -Path $tempPath -Destination $Path -Force
    }
    finally {
        if (Test-Path -LiteralPath $tempPath) {
            Remove-Item -LiteralPath $tempPath -Force
        }
    }
}

function Write-JsonAtomic {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][object]$Value,
        [int]$Depth = 8
    )

    $tempPath = "$Path.tmp-$PID-$([Guid]::NewGuid().ToString('N'))"
    try {
        $json = $Value | ConvertTo-Json -Depth $Depth
        [IO.File]::WriteAllText($tempPath, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
        Move-Item -Path $tempPath -Destination $Path -Force
    }
    finally {
        if (Test-Path -LiteralPath $tempPath) {
            Remove-Item -LiteralPath $tempPath -Force
        }
    }
}

function Invoke-MonitorCli {
    param(
        [Parameter(Mandatory)][string]$CliPath,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Arguments,
        [Parameter(Mandatory)][string]$StdoutPath,
        [Parameter(Mandatory)][string]$StderrPath
    )

    & $CliPath @Arguments 1> $StdoutPath 2> $StderrPath
    return $LASTEXITCODE
}

function Assert-MonitorDate {
    param([Parameter(Mandatory)][string]$Date)

    $parsed = [DateTime]::MinValue
    if (-not [DateTime]::TryParseExact(
            $Date, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::None, [ref]$parsed)) {
        throw "Date must use yyyy-MM-dd: $Date"
    }
}

function Ensure-ManifestRowSchema {
    param([Parameter(Mandatory)][object]$Row)

    $defaults = [ordered]@{
        category_id = ''; url = ''; marketplace = ''; enabled = 'true'; status = 'pending'
        last_attempt_at = ''; last_ok_at = ''; last_attempt_snapshot = ''; last_ok_snapshot = ''
        fail_count = '0'; last_error = ''; row_count = '0'
    }
    foreach ($entry in $defaults.GetEnumerator()) {
        if ($null -eq $Row.PSObject.Properties[$entry.Key]) {
            $Row | Add-Member -MemberType NoteProperty -Name $entry.Key -Value $entry.Value
        }
    }
    return $Row
}

function Test-ManifestSuccessOnDate {
    param(
        [Parameter(Mandatory)][object]$Row,
        [Parameter(Mandatory)][string]$Date
    )

    if ((Get-JsonValue -InputObject $Row -Name 'status' -Default '') -ne 'ok') {
        return $false
    }
    $snapshotDate = [string](Get-JsonValue -InputObject $Row -Name 'last_ok_snapshot' -Default '')
    if ($snapshotDate) {
        return $snapshotDate -eq $Date
    }
    $lastOk = [string](Get-JsonValue -InputObject $Row -Name 'last_ok_at' -Default '')
    if (-not $lastOk) {
        return $false
    }
    $parsed = [DateTimeOffset]::MinValue
    return [DateTimeOffset]::TryParse($lastOk, [ref]$parsed) -and $parsed.LocalDateTime.ToString('yyyy-MM-dd') -eq $Date
}

function Resolve-MonitorCliPath {
    param([string]$RequestedPath, [Parameter(Mandatory)][string]$ScriptDirectory)

    if ($RequestedPath) {
        if (Test-Path -LiteralPath $RequestedPath -PathType Leaf) {
            return (Resolve-Path -LiteralPath $RequestedPath).Path
        }
        $requestedCommand = Get-Command $RequestedPath -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($requestedCommand) {
            return $requestedCommand.Source
        }
        throw "Browser4 CLI not found: $RequestedPath"
    }

    $command = Get-Command browser4-cli -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) {
        return $command.Source
    }

    $wrapper = Join-Path $ScriptDirectory "../../b4w.ps1"
    if (Test-Path -LiteralPath $wrapper -PathType Leaf) {
        return (Resolve-Path -LiteralPath $wrapper).Path
    }
    throw "browser4-cli was not found on PATH and ../../b4w.ps1 does not exist. Pass -CliPath."
}
