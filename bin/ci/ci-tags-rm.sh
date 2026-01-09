#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  ci-tags-rm.sh [--remote <name>] [--pattern <regex>] [--local-only]

Options:
  --remote <name>     Remote name to delete tags from (default: origin)
  --pattern <regex>   Regex for tags to remove (default: .*-ci.*)
  --local-only        Only delete matching tags locally (skip remote deletion)
  -h, --help          Show this help

Notes:
  - The script is interactive. For each matching tag, you can answer:
      y/yes  => delete this tag
      all    => delete this and all remaining tags
      (Enter)=> skip
  - Pattern is treated as a regex and matched against the full tag name.
EOF
}

remote="origin"
pattern=".*-ci.*"
localOnly=false

# Parse args
while [[ $# -gt 0 ]]; do
  case "$1" in
    --remote)
      remote="${2:-}"; shift 2 ;;
    --remote=*)
      remote="${1#*=}"; shift ;;

    --pattern)
      pattern="${2:-}"; shift 2 ;;
    --pattern=*)
      pattern="${1#*=}"; shift ;;

    --local-only|--localOnly|-localOnly|--local_only)
      localOnly=true; shift ;;

    -h|--help)
      usage; exit 0 ;;

    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${pattern}" ]]; then
  echo "[ERROR] --pattern must not be empty" >&2
  exit 1
fi

# Find the first parent directory containing the VERSION file
AppHome="$(dirname "$(realpath "$0")")"
while [[ -n "$AppHome" && ! -f "$AppHome/VERSION" ]]; do
  AppHome="$(dirname "$AppHome")"
  # dirname / => /
  if [[ "$AppHome" == "/" ]]; then
    break
  fi
done

if [[ ! -f "$AppHome/VERSION" ]]; then
  echo "[ERROR] Could not find VERSION file in any parent directory" >&2
  exit 1
fi

cd "$AppHome"

get_matching_tags() {
  # Match full tag name with the regex.
  # Use grep -E (ERE). Anchor to match the entire tag.
  git tag --list | grep -E "^(${pattern})$" || true
}

remove_tag() {
  local tag="$1"

  echo "Removing tag '$tag' locally."
  git tag -d "$tag" >/dev/null

  if [[ "$localOnly" == false ]]; then
    echo "Removing tag '$tag' from remote '$remote'."
    git push "$remote" --delete "$tag" >/dev/null
  else
    echo "Skipped removing tag '$tag' from remote (local-only)."
  fi
}

confirm_and_remove_ci_tags() {
  local tags
  tags="$(get_matching_tags)"

  if [[ -z "$tags" ]]; then
    echo "No matching tags found to remove."
    return 0
  fi

  # Print tags as a comma-separated list
  local tagsCsv
  tagsCsv="$(echo "$tags" | paste -sd ', ' -)"
  echo "Found CI tags to potentially remove: ${tagsCsv}"

  local removeAll=false

  # Iterate line-by-line
  # Use file descriptor 3 to avoid conflict with stdin
  while IFS= read -r -u 3 tag; do
    [[ -z "$tag" ]] && continue

    if [[ "$removeAll" == false ]]; then
      local prompt
      if [[ "$localOnly" == true ]]; then
        prompt="Do you want to remove tag '$tag' locally only? (y/N/all)"
      else
        prompt="Do you want to remove tag '$tag' from local and remote '$remote'? (y/N/all)"
      fi

      read -r -p "$prompt " confirm || true

      if [[ "$confirm" == "all" ]]; then
        if [[ "$localOnly" == true ]]; then
          echo "Removing all remaining CI tags locally only without further prompts."
        else
          echo "Removing all remaining CI tags without further prompts."
        fi
        removeAll=true
      elif [[ ! "$confirm" =~ ^(y|Y|yes|YES|Yes)$ ]]; then
        echo "Skipped removing tag '$tag'."
        continue
      fi
    fi

    remove_tag "$tag"
  done 3<<< "$tags"
}

confirm_and_remove_ci_tags

