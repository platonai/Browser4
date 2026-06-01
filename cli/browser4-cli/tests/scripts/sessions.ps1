#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

# Prefer the pre-built binary (set by multi-scenarios.ps1) over `cargo run`.
$cli = if ($env:BROWSER4_CLI_BIN) {
    { & $env:BROWSER4_CLI_BIN $args }
} else {
    { cargo run --quiet -- $args }
}

& $cli open https://www.amazon.com/
sleep 5
& $cli snapshot

& $cli list
& $cli goto https://www.amazon.com/s?k=pens
sleep 5
& $cli snapshot
& $cli close
& $cli list

sleep 5
& $cli goto https://www.amazon.com/s?k=shoes
& $cli snapshot
& $cli close
& $cli list

& $cli open https://www.amazon.com/
sleep 5
& $cli list
