#!/usr/bin/env pwsh

for ($attempt = 1; $attempt -le 60; $attempt++) {
    . ./sessions.ps1
    . ./agent-run-page-visit.ps1
    . ./agent-run-page-visit-interact.ps1
    . ./swarm-agents.ps1
}
