#!/usr/bin/env bash
set -euo pipefail

browser4-cli open
browser4-cli swarm create

output="$(browser4-cli swarm submit "https://example.com" 2>&1)"
output="${output%$'\n'}"
submitted_line="$(printf '%s\n' "$output" | grep -E 'Task ID:[[:space:]]*[^[:space:]]+' | head -n 1 || true)"
if [[ -z "$submitted_line" ]]; then
    submitted_line="$output"
fi
printf 'Submitted swarm task: %s\n' "$submitted_line"

task_id="$(printf '%s\n' "$output" | sed -nE 's/.*Task ID:[[:space:]]*([^[:space:]]+).*/\1/p' | head -n 1)"
if [[ -z "$task_id" ]]; then
    printf 'Unable to parse Task ID from swarm submit output:\n%s\n' "$output" >&2
    exit 1
fi

printf 'Waiting for agent task %s to finish...\n' "$task_id"

for attempt in 1 2 3; do
    status="$(browser4-cli swarm status "$task_id" 2>&1)"
    status="${status%$'\n'}"
    printf '%s\n' "$status"

    if [[ "$status" =~ \"done\"[[:space:]]*:[[:space:]]*true ]] || [[ "$status" =~ \"isDone\"[[:space:]]*:[[:space:]]*true ]] || [[ "$status" =~ status:[[:space:]]*done ]]; then
        printf 'Task %s is done.\n' "$task_id"
        break
    fi

    sleep 3
done
