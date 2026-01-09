#!/usr/bin/env bash

set -e

# Default parameters
SEEDS_FILE="seeds.txt"
BATCH_SIZE=5
MAX_ATTEMPTS=30
DELAY_SECONDS=5
MAX_URLS=20
BASE_URL="http://localhost:8182/api/x/s"
STATUS_URL_TEMPLATE="http://localhost:8182/api/x/%s/status"
MAX_POLLING_TASKS=10

# Helper to format timestamps consistently
_ts() { date +%Y%m%dT%H%M%S; }

SQL_TEMPLATE="select
  dom_base_uri(dom) as url,
  dom_first_text(dom, '#productTitle') as title,
  str_substring_after(dom_first_href(dom, '#wayfinding-breadcrumbs_container ul li:last-child a'), 'node=') as category,
  dom_first_slim_html(dom, '#bylineInfo') as brand,
  cast(dom_all_slim_htmls(dom, '#imageBlock img') as varchar) as gallery,
  dom_first_slim_html(dom, '#landingImage, #imgTagWrapperId img, #imageBlock img:expr(width > 400)') as img,
  dom_first_text(dom, '#price tr td:contains(List Price) ~ td') as listprice,
  dom_first_text(dom, '#price tr td:matches(^Price) ~ td') as price,
  str_first_float(dom_first_text(dom, '#reviewsMedley .AverageCustomerReviews span:contains(out of)'), 0.0) as score
from load_and_select('{url} -i 20s -njr 3', 'body');
"

parse_args() {
  while getopts "f:b:a:d:m:" opt; do
    case $opt in
      f) SEEDS_FILE="$OPTARG" ;;
      b) BATCH_SIZE="$OPTARG" ;;
      a) MAX_ATTEMPTS="$OPTARG" ;;
      d) DELAY_SECONDS="$OPTARG" ;;
      m) MAX_URLS="$OPTARG" ;;
      *) echo "Invalid option"; exit 1 ;;
    esac
  done
}

extract_urls() {
  if [[ ! -f "$SEEDS_FILE" ]]; then
    echo "[ERROR] $SEEDS_FILE does not exist."
    exit 1
  fi
  mapfile -t URLS < <(grep -Eo 'https?://[^[:space:]/$.?#][^[:space:]]*' "$SEEDS_FILE" | awk '!seen[$0]++' | head -n "$MAX_URLS")
  if [[ ${#URLS[@]} -eq 0 ]]; then
    echo "[ERROR] No valid HTTP(S) links found in $SEEDS_FILE."
    exit 1
  fi
  echo "[INFO] Extracted ${#URLS[@]} valid links."
}

submit_task() {
  local url="$1"
  local sql="${SQL_TEMPLATE//\{url\}/$url}"
  # Avoid exiting the script on curl network errors due to set -e
  curl -s -X POST -H "Content-Type: text/plain" --data "$sql" "$BASE_URL" || true
}

poll_tasks() {
  local ids=("${@}")
  # Remove noisy debugging and guard against empty input
  if [[ ${#ids[@]} -eq 0 ]]; then
    echo "[WARN] No task IDs to poll. Skipping."
    return 0
  fi

  declare -A TASK_MAP
  declare -a PENDING_TASKS=("${ids[@]}")

  for id in "${PENDING_TASKS[@]}"; do
    TASK_MAP["$id"]=0
  done

  for ((attempt=0; attempt<MAX_ATTEMPTS; attempt++)); do
    sleep "$DELAY_SECONDS"

    local new_pending=()

    # Process all pending tasks this attempt, but in chunks of MAX_POLLING_TASKS
    local total_pending=${#PENDING_TASKS[@]}
    local offset=0
    while (( offset < total_pending )); do
      local chunk_size=$(( total_pending - offset ))
      if (( chunk_size > MAX_POLLING_TASKS )); then
        chunk_size=$MAX_POLLING_TASKS
      fi
      # slice: from offset take chunk_size
      local batch_ids=("${PENDING_TASKS[@]:offset:chunk_size}")

      for id in "${batch_ids[@]}"; do
        # skip empty IDs defensively
        if [[ -z "$id" ]]; then
          continue
        fi
        echo "[INFO] $(_ts) Polling for task $id..."

        local status_url
        status_url=$(printf "$STATUS_URL_TEMPLATE" "$id")
        local response
        response=$(curl -s --max-time 10 "$status_url" || echo "error")

        if [[ "$attempt" -eq $((MAX_ATTEMPTS - 1)) ]]; then
          echo "[DEBUG] Final attempt raw response for task $id: '$response'"
        fi

        # if there is a line contains "isDone" and "true", then the task is completed
        if [[ "$response" =~ isDone.*true ]] || [[ "$response" =~ done.*true ]]; then
          TASK_MAP["$id"]=0
          echo "[SUCCESS] Task $id completed."
        else
          TASK_MAP["$id"]=$((TASK_MAP["$id"]+1))
          echo "[PENDING] $(_ts) Task $id still in progress (Attempt ${TASK_MAP["$id"]})."
          new_pending+=("$id")
        fi
      done

      offset=$(( offset + chunk_size ))
    done

    PENDING_TASKS=("${new_pending[@]}")
    if [[ ${#PENDING_TASKS[@]} -eq 0 ]]; then
      echo "[INFO] All tasks in this batch completed."
      break
    fi
    echo "[INFO] $(_ts) Still waiting on ${#PENDING_TASKS[@]} tasks in this batch..."
  done

  if [[ ${#PENDING_TASKS[@]} -gt 0 ]]; then
    echo "[WARNING] $(_ts) Timeout reached. The following tasks are still pending:"
    for id in "${PENDING_TASKS[@]}"; do
      echo "$id"
    done
  fi
}

process_batch() {
  local batch=("${@}")
  local ids=()
  for url in "${batch[@]}"; do
    if [[ ! "$url" =~ ^https?:// ]]; then
      echo "[WARNING] Invalid URL: $url"
      continue
    fi
    local id
    id=$(submit_task "$url")
    # Trim whitespace
    id="${id//$'\r'/}"
    id="${id//$'\n'/}"
    if [[ -z "$id" ]]; then
      echo "[ERROR] Failed to submit task for: $url"
      continue
    fi
    echo "Submitted: $url -> Task ID: $id"
    ids+=("$id")
  done
  if [[ ${#ids[@]} -eq 0 ]]; then
    echo "[WARN] No tasks were submitted for this batch. Skipping polling."
    return 0
  fi
  echo "[INFO] Started polling for ${#ids[@]} tasks..."
  poll_tasks "${ids[@]}"
}

main() {
  parse_args "$@"
  extract_urls
  local total=${#URLS[@]}
  for ((i=0; i<total; i+=BATCH_SIZE)); do
    local batch=("${URLS[@]:i:BATCH_SIZE}")
    local batch_num=$((i/BATCH_SIZE+1))
    echo "[INFO] Processing batch $batch_num with ${#batch[@]} URLs..."
    process_batch "${batch[@]}"
  done
}

main "$@"

