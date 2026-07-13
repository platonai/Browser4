#!/usr/bin/env pwsh

param(
    [string[]]$ScriptArgs
)

cargo run --manifest-path cli/browser4-cli/Cargo.toml -- $ScriptArgs
