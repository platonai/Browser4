# ── Logging and ANSI utilities ────────────────────────────────────────────

function Write-CoworkerLog {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message,
        [ValidateSet('DEBUG', 'INFO', 'WARN', 'ERROR')]
        [string]$Level = 'INFO',
        [string]$Component = 'coworker',
        [switch]$NoColor
    )

    $timestamp = Get-CoworkerTimestamp
    $formattedMessage = "[{0}] [{1}] [{2}] {3}" -f $timestamp, $Level, $Component, $Message
    $color = switch ($Level) {
        'DEBUG' { 'DarkGray' }
        'WARN' { 'Yellow' }
        'ERROR' { 'Red' }
        default { 'Gray' }
    }

    if ($NoColor) {
        Write-Host $formattedMessage
        return
    }

    Write-Host $formattedMessage -ForegroundColor $color
}

function Remove-AnsiEscapeSequences {
    param(
        [AllowNull()]
        [string]$Text
    )

    if ([string]::IsNullOrEmpty($Text)) {
        return $Text
    }

    $escapeCharacter = [string][char]27
    $ansiPattern = [regex]::Escape($escapeCharacter) + '\[[0-9;?]*[ -/]*[@-~]'
    return ($Text -replace $ansiPattern, '')
}

function Normalize-CoworkerLogFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    try {
        $bytes = [System.IO.File]::ReadAllBytes($Path)
        if ($null -eq $bytes -or $bytes.Length -eq 0) {
            return
        }

        $content = $null
        foreach ($encodingName in @('utf-8', [System.Text.Encoding]::Default, [System.Text.Encoding]::GetEncoding([Console]::OutputEncoding.CodePage), 'unicode')) {
            try {
                if ($encodingName -is [string]) {
                    $encoding = [System.Text.Encoding]::GetEncoding($encodingName, [System.Text.EncoderFallback]::ExceptionFallback, [System.Text.DecoderFallback]::ExceptionFallback)
                }
                else {
                    $encoding = [System.Text.Encoding]::GetEncoding($encodingName.WebName, [System.Text.EncoderFallback]::ExceptionFallback, [System.Text.DecoderFallback]::ExceptionFallback)
                }

                $content = $encoding.GetString($bytes)
                break
            }
            catch {
                continue
            }
        }

        if ($null -eq $content) {
            $content = [System.Text.Encoding]::UTF8.GetString($bytes)
        }

        $sanitizedContent = Remove-AnsiEscapeSequences -Text $content
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        [System.IO.File]::WriteAllText($Path, $sanitizedContent, $utf8NoBom)
    }
    catch {
        # Best-effort normalization: keep original log if conversion fails.
        return
    }
}
