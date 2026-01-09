#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT_DIR"

EXEMPT_MODULES=(pulsar-benchmarks examples/browser4-examples pulsar-bom pulsar-all browser4)

function is_exempt() {
  local target=$1
  for m in "${EXEMPT_MODULES[@]}"; do
    [[ "$m" == "$target" ]] && return 0
  done
  return 1
}

printf "\n== Pulsar Quality Quick Scan ==\n"
modules=$(grep -oP '(?<=<module>)[^<]+' pom.xml | sort -u)

printf "%-28s | %-6s | %-6s | %-6s | %-9s | %s\n" Module Tests ITs E2E Status Notes
printf '%0.s-' {1..90}; echo

FAIL=0
for m in $modules; do
  [[ ! -d $m ]] && continue
  testRoot="$m/src/test"
  countTests=0; countIT=0; countE2E=0
  if [[ -d "$testRoot" ]]; then
    countTests=$(find "$testRoot" -type f -name '*Test.kt' -o -name '*Test.java' 2>/dev/null | wc -l | xargs)
    countIT=$(find "$testRoot" -type f -name '*IT.kt' -o -name '*IT.java' 2>/dev/null | wc -l | xargs)
    countE2E=$(grep -R "@Tag(\"E2ETest\")" "$testRoot" 2>/dev/null | wc -l | xargs)
  fi
  status="OK"; notes=""
  if [[ $countTests -eq 0 && $countIT -eq 0 && $countE2E -eq 0 ]]; then
    if is_exempt "$m"; then
      status="SKIP"; notes="exempt"
    else
      status="MISSING"; notes="no tests"; FAIL=1
    fi
  fi
  printf "%-28s | %-6s | %-6s | %-6s | %-9s | %s\n" "$m" "$countTests" "$countIT" "$countE2E" "$status" "$notes"

done

printf '\nSummary: '\n
if [[ $FAIL -ne 0 ]]; then
  echo "One or more non-exempt modules lack tests." >&2
  exit 2
else
  echo "No blocking issues detected."
fi

