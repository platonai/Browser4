#!/usr/bin/env bash
# Delete local and optionally remote git tags matching the given patterns.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: cleanup-tags.sh [OPTIONS] [PATTERN...]

Delete local (and optionally remote) git tags whose names contain any of the
given case-insensitive patterns.

OPTIONS
  -r, --remote REMOTE   Remote to delete tags from (default: origin).
                         Remote deletion only happens when --delete-remote
                         is also passed.
  -n, --dry-run         List matching tags without deleting anything.
  --delete-remote       Also delete matching tags from the remote.
  -y, --yes             Skip the confirmation prompt.
  -h, --help            Show this help message.

PATTERN
  One or more substrings to match against tag names (case-insensitive grep).
  Defaults: "dry_run" "ci"

EXAMPLES
  cleanup-tags.sh -n                         # dry-run with default patterns
  cleanup-tags.sh --delete-remote ci         # delete local + remote tags matching 'ci'
  cleanup-tags.sh -r upstream --delete-remote dry_run ci
EOF
}

# --------------- argument parsing ---------------
PATTERNS=()
REMOTE="origin"
DRY_RUN=false
DELETE_REMOTE=false
SKIP_CONFIRM=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    -r|--remote)
      REMOTE="$2"
      shift 2
      ;;
    -n|--dry-run)
      DRY_RUN=true
      shift
      ;;
    --delete-remote)
      DELETE_REMOTE=true
      shift
      ;;
    -y|--yes)
      SKIP_CONFIRM=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      PATTERNS+=("$@")
      break
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      PATTERNS+=("$1")
      shift
      ;;
  esac
done

if [[ ${#PATTERNS[@]} -eq 0 ]]; then
  PATTERNS=("dry_run" "ci")
fi

# --------------- safety checks ---------------
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || true)
if [[ -z "$REPO_ROOT" ]]; then
  echo "Error: not inside a git repository." >&2
  exit 1
fi
cd "$REPO_ROOT"

# --------------- collect matching tags ---------------
declare -a TAGS_TO_DELETE=()

for pattern in "${PATTERNS[@]}"; do
  while IFS= read -r tag; do
    TAGS_TO_DELETE+=("$tag")
  done < <(git tag | grep -i "$pattern" || true)
done

# Deduplicate while preserving order
declare -a UNIQ_TAGS=()
for tag in "${TAGS_TO_DELETE[@]}"; do
  already=false
  for u in "${UNIQ_TAGS[@]}"; do
    [[ "$u" == "$tag" ]] && already=true && break
  done
  if ! $already; then
    UNIQ_TAGS+=("$tag")
  fi
done

if [[ ${#UNIQ_TAGS[@]} -eq 0 ]]; then
  echo "No matching local tags found."
  exit 0
fi

# --------------- display & dry-run ---------------
echo ""
echo "Patterns:      ${PATTERNS[*]}"
echo "Matching tags (${#UNIQ_TAGS[@]}):"
for tag in "${UNIQ_TAGS[@]}"; do
  echo "  $tag"
done
echo ""

if $DRY_RUN; then
  echo "Dry-run mode — no tags were deleted."
  exit 0
fi

# --------------- confirmation ---------------
TARGET="local"
$DELETE_REMOTE && TARGET="local + remote '$REMOTE'"

if ! $SKIP_CONFIRM; then
  read -r -p "Delete these ${#UNIQ_TAGS[@]} tags from $TARGET? (y/N) " CONFIRM
  if [[ ! "$CONFIRM" =~ ^[Yy]([Ee][Ss])?$ ]]; then
    echo "Cancelled. No tags were deleted."
    exit 0
  fi
fi

# --------------- fetch remote tags (needed for remote deletion) ---------------
REMOTE_TAG_SET=()
if $DELETE_REMOTE; then
  echo "Fetching tags from remote '$REMOTE'..."
  git fetch --tags "$REMOTE" 2>/dev/null || true

  while IFS= read -r ref; do
    tag_name="${ref#refs/tags/}"
    tag_name="${tag_name%\^\{\}}"
    REMOTE_TAG_SET+=("$tag_name")
  done < <(git ls-remote --tags "$REMOTE" 2>/dev/null | awk '{print $2}' || true)
fi

# --------------- delete local (all in one shot) ---------------
echo "Deleting ${#UNIQ_TAGS[@]} local tags..."
git tag -d "${UNIQ_TAGS[@]}"
DELETED_LOCAL=${#UNIQ_TAGS[@]}

# --------------- delete remote (all in one shot) ---------------
DELETED_REMOTE=0
if $DELETE_REMOTE; then
  # Filter to tags that actually exist on the remote
  declare -a REMOTE_TO_DELETE=()
  for tag in "${UNIQ_TAGS[@]}"; do
    exists_on_remote=false
    for rt in "${REMOTE_TAG_SET[@]}"; do
      [[ "$rt" == "$tag" ]] && exists_on_remote=true && break
    done
    if $exists_on_remote; then
      REMOTE_TO_DELETE+=("$tag")
    else
      echo "Skipping remote tag '$tag' (not found on '$REMOTE')"
    fi
  done

  if [[ ${#REMOTE_TO_DELETE[@]} -gt 0 ]]; then
    echo "Deleting ${#REMOTE_TO_DELETE[@]} remote tags from '$REMOTE'..."
    git push "$REMOTE" --delete "${REMOTE_TO_DELETE[@]}"
    DELETED_REMOTE=${#REMOTE_TO_DELETE[@]}
  else
    echo "No matching tags to delete on remote '$REMOTE'."
  fi
fi

# --------------- summary ---------------
echo ""
echo "Done. Deleted local tags:  $DELETED_LOCAL"
if $DELETE_REMOTE; then
  echo "       Deleted remote tags: $DELETED_REMOTE"
fi
