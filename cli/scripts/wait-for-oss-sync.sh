#!/bin/bash
# ---------------------------------------------------------------------------
# wait-for-oss-sync.sh -- trigger the Aliyun OSS sync for a release tag and
# wait for that exact run to finish
# ---------------------------------------------------------------------------
# Usage:
#   wait-for-oss-sync.sh --tag <tag> [--repo <owner/name>] [--workflow <name>]
#                        [--timeout <seconds>] [--interval <seconds>]
#                        [--discovery-timeout <seconds>]
#
# Defaults: --workflow sync-to-oss.yml, --timeout 2700 s, --interval 15 s,
#           --discovery-timeout 120 s.
#
# release.yml and release-cli.yml used to inline this whole dance:
#
#     gh workflow run sync-to-oss.yml -f tag_name="$TAG"
#     sleep 3
#     RUN_ID=$(gh run list --workflow=sync-to-oss.yml --limit 1 ...)  # nothing but air for 20 tries
#     gh run watch "$RUN_ID" --exit-status
#
# under a step `timeout-minutes: 15`.  Three things were wrong with it:
#
#   1. The run it waited on was not necessarily the run it triggered.  `gh run
#      list` answers newest-first, so while the freshly dispatched run was not
#      indexed yet the loop's first non-empty answer was the PREVIOUS release's
#      sync run -- already completed, conclusion "success".  The step then
#      reported the CDN as updated without having waited for the current sync
#      at all.  Same defect class as bd18d2b7d8, which fixed the identical
#      run-discovery race in monitor-release.ps1.
#   2. The wait had no budget of its own.  The only bound was the step timeout,
#      which kills the step and reports
#        "The action 'Sync to Aliyun OSS CDN' has timed out after 15 minutes."
#      -- no run id, no run URL, and no statement of what the sync was doing.
#   3. `gh run watch` re-renders the entire run every 3 s: a wall of text that
#      still never says which step the sync is stuck on.
#
# v4.13.21 (release.yml run 35860129386) hit all three at once.  The sync run's
# "Upload to OSS" step stalled -- 18+ minutes and still counting, against ~2
# minutes for the same 11 assets in every earlier release -- the 15-minute cap
# fired, and the release job went red after everything had in fact been
# published to GitHub, npm and the container registries.
#
# So this script:
#
#   1. records the sync runs that already exist BEFORE dispatching, and only
#      ever accepts a run that is not one of them (or, when that listing
#      failed, one created after the newest of them) -- a stale run can never
#      be mistaken for ours,
#   2. waits under an explicit budget of its own, never sleeping past the
#      deadline, and
#   3. reports the run id, the run URL, the elapsed time and the step the run
#      is currently on -- on success, on failure, and on timeout alike.
#
# It deliberately does not retry the sync: a re-triggered sync re-uploads every
# asset.  The verdict belongs to the caller; re-run the workflow (or this
# script) once the run it names has been looked at.
#
# Exit codes:
#   0  the sync run this script triggered completed successfully
#   1  that run failed, was cancelled, never appeared, or was still running
#      when the budget expired -- or bad usage
#
# Designed for CI (GitHub Actions) but also runs on developer machines.
# Requires `gh`, authenticated via GH_TOKEN/GITHUB_TOKEN or `gh auth login`.
# ---------------------------------------------------------------------------
set -euo pipefail

TAG=''
REPO=''
WORKFLOW='sync-to-oss.yml'
TIMEOUT_SECONDS=2700
POLL_INTERVAL_SECONDS=15
DISCOVERY_TIMEOUT_SECONDS=120

usage() {
    local code="${1:-1}"
    {
        echo "Usage: $0 --tag <tag> [--repo <owner/name>] [--workflow <name>]"
        echo "          [--timeout <seconds>] [--interval <seconds>]"
        echo "          [--discovery-timeout <seconds>]"
    } >&2
    exit "$code"
}

while [ $# -gt 0 ]; do
    case "${1:-}" in
        --tag)
            TAG="${2:-}"
            shift 2 || usage
            ;;
        --repo)
            REPO="${2:-}"
            shift 2 || usage
            ;;
        --workflow)
            WORKFLOW="${2:-}"
            shift 2 || usage
            ;;
        --timeout)
            TIMEOUT_SECONDS="${2:-}"
            shift 2 || usage
            ;;
        --interval)
            POLL_INTERVAL_SECONDS="${2:-}"
            shift 2 || usage
            ;;
        --discovery-timeout)
            DISCOVERY_TIMEOUT_SECONDS="${2:-}"
            shift 2 || usage
            ;;
        -h|--help)
            usage 0
            ;;
        *)
            echo "ERROR: unknown argument '$1'" >&2
            usage
            ;;
    esac
done

[ -n "$TAG" ] || { echo "ERROR: --tag is required" >&2; usage; }
[ -n "$WORKFLOW" ] || { echo "ERROR: --workflow must not be empty" >&2; usage; }
case "$TIMEOUT_SECONDS" in ''|*[!0-9]*) echo "ERROR: --timeout must be a whole number of seconds" >&2; usage ;; esac
case "$POLL_INTERVAL_SECONDS" in ''|*[!0-9]*) echo "ERROR: --interval must be a whole number of seconds" >&2; usage ;; esac
case "$DISCOVERY_TIMEOUT_SECONDS" in ''|*[!0-9]*) echo "ERROR: --discovery-timeout must be a whole number of seconds" >&2; usage ;; esac
[ "$TIMEOUT_SECONDS" -gt 0 ] || { echo "ERROR: --timeout must be at least 1 s" >&2; usage; }
[ "$POLL_INTERVAL_SECONDS" -gt 0 ] || { echo "ERROR: --interval must be at least 1 s" >&2; usage; }

repo_label="${REPO:-<current repo>}"

# gh reads --repo per subcommand, so append it rather than building an argument
# list: an empty array under `set -u` is a portability trap older bash and
# GitHub's runners disagree about.
gh_ws() {
    if [ -n "$REPO" ]; then
        gh "$@" --repo "$REPO"
    else
        gh "$@"
    fi
}

# The most recent runs of the sync workflow, newest first, as TSV.
# `limit` is a parameter because the pre-dispatch listing wants a long memory
# (every run that can be mistaken for ours) while the discovery poll only ever
# looks at the newest few.  Fails (non-zero) when gh cannot answer, so the
# caller can tell "no runs yet" from "the listing is unavailable".
list_runs_tsv() {
    gh_ws run list --workflow="$WORKFLOW" --limit "$1" \
        --json databaseId,createdAt -q '.[] | [.databaseId, .createdAt] | @tsv' 2>/dev/null
}

# status, conclusion and the step the run is currently on, as TSV.
#
# "--" stands for "no value": a bare empty tab-separated field is not
# survivable here, because tab is IFS whitespace, so `read` collapses a run of
# tabs and an empty conclusion would silently swallow the step name (which is
# exactly the diagnostic this script exists to report).
view_run_tsv() {
    local run_id="$1"
    gh_ws run view "$run_id" --json status,conclusion,jobs \
        -q '[.status, (.conclusion // "--"), ([.jobs[].steps[]? | select(.status == "in_progress") | .name] | join(",") | if . == "" then "--" else . end)] | @tsv' 2>/dev/null || true
}

# ---- 1. what the sync workflow looks like before we add to it --------------

baseline_ok=true
baseline_tsv=''
if ! baseline_tsv="$(list_runs_tsv 50)"; then
    baseline_ok=false
    baseline_tsv=''
fi

baseline_ids=' '
watermark_created=''
while IFS=$'\t' read -r _id _created; do
    [ -n "${_id:-}" ] || continue
    baseline_ids="${baseline_ids}${_id} "
done <<< "$baseline_tsv"

if [ "$baseline_ok" != true ]; then
    # No id baseline to compare against, so our run is identified by creation
    # time alone: anything created after this dispatch watermark is a
    # candidate.  The watermark is local, so a stale run could only slip
    # through by being created in the same second as our dispatch -- which
    # would be a sync of the same release anyway.
    watermark_created="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "WARN: could not list existing ${WORKFLOW} runs; identifying our run by creation time alone (after ${watermark_created})"
fi
echo "Existing ${WORKFLOW} runs to exclude: $(printf '%s' "$baseline_ids" | wc -w)"

# ---- 2. dispatch ----------------------------------------------------------

echo "Triggering ${WORKFLOW} for ${TAG}"
if ! gh_ws workflow run "$WORKFLOW" -f tag_name="$TAG"; then
    echo "::error::Could not dispatch ${WORKFLOW} for ${TAG} in ${repo_label} -- check that the workflow exists on the default branch and that the token may run it."
    exit 1
fi

# ---- 3. find OUR run, never an older one ----------------------------------

RUN_ID=''
RUN_URL=''
discovery_started=$SECONDS
attempt=0

while :; do
    attempt=$((attempt + 1))
    rows="$(list_runs_tsv 20)" || rows=''

    while IFS=$'\t' read -r run_id created_at; do
        [ -n "${run_id:-}" ] || continue
        # Never accept a run that existed before we dispatched.  With the id
        # baseline in hand that test is exact and involves no clock at all;
        # only when that listing failed do we fall back to the creation-time
        # watermark taken just before the dispatch.
        if [ "$baseline_ok" = true ]; then
            if [[ "$baseline_ids" == *" ${run_id} "* ]]; then
                continue
            fi
        elif [[ "$created_at" < "$watermark_created" ]]; then
            continue
        fi
        RUN_ID="$run_id"
        RUN_URL="https://github.com/${REPO:-platonai/Browser4}/actions/runs/${run_id} (created ${created_at})"
        break
    done <<< "$rows"

    if [ -n "$RUN_ID" ]; then
        break
    fi

    elapsed=$((SECONDS - discovery_started))
    remaining=$((DISCOVERY_TIMEOUT_SECONDS - elapsed))
    if [ "$remaining" -le 0 ]; then
        break
    fi
    echo "Waiting for the ${WORKFLOW} run to appear (attempt ${attempt}, ${remaining}s left)"
    if [ "$POLL_INTERVAL_SECONDS" -gt "$remaining" ]; then
        sleep "$remaining"
    else
        sleep "$POLL_INTERVAL_SECONDS"
    fi
done

if [ -z "$RUN_ID" ]; then
    echo "::error::${WORKFLOW} was dispatched for ${TAG} but no new run appeared within ${DISCOVERY_TIMEOUT_SECONDS}s."
    echo "::error::Check https://github.com/${REPO:-platonai/Browser4}/actions/workflows/${WORKFLOW} -- the run may have been refused before it was queued."
    exit 1
fi

RUN_URL="https://github.com/${REPO:-platonai/Browser4}/actions/runs/${RUN_ID}"
echo "Watching ${WORKFLOW} run ${RUN_ID} for ${TAG}: ${RUN_URL}"

# ---- 4. wait for it, under our own budget ---------------------------------

status='unknown'
conclusion=''
stuck_on=''
last_state=''
last_reported_at=-60
wait_started=$SECONDS

while :; do
    now=$((SECONDS - wait_started))

    line="$(view_run_tsv "$RUN_ID")"
    if [ -n "$line" ]; then
        status=''; conclusion=''; stuck_on=''
        IFS=$'\t' read -r status conclusion stuck_on <<< "$line" || true
        [ "$conclusion" != '--' ] || conclusion=''
        [ "$stuck_on" != '--' ] || stuck_on=''
    fi
    # An unreadable poll (rate limit, network blip) keeps the last known state
    # and simply counts against the budget -- it must not abort the wait.

    state="${status}|${stuck_on}"
    remaining=$((TIMEOUT_SECONDS - now))
    if [ "$state" != "$last_state" ] || [ $((now - last_reported_at)) -ge 60 ]; then
        echo "run ${RUN_ID}: status=${status:-unknown}${stuck_on:+, step=${stuck_on}} (${now}s elapsed, ${remaining}s left)"
        last_state="$state"
        last_reported_at="$now"
    fi

    if [ "$status" = 'completed' ]; then
        if [ "$conclusion" = 'success' ]; then
            echo "Aliyun OSS CDN sync for ${TAG} completed successfully"
            echo "Run URL: ${RUN_URL}"
            exit 0
        fi
        echo "::error::${WORKFLOW} run ${RUN_ID} concluded '${conclusion:-unknown}' -- the OSS CDN may still serve an older release."
        echo "::error::Run URL: ${RUN_URL}"
        echo "::error::Re-check it, then re-trigger with: gh workflow run ${WORKFLOW} -f tag_name=${TAG}"
        exit 1
    fi

    if [ "$remaining" -le 0 ]; then
        break
    fi
    if [ "$POLL_INTERVAL_SECONDS" -gt "$remaining" ]; then
        sleep "$remaining"
    else
        sleep "$POLL_INTERVAL_SECONDS"
    fi
done

echo "::error::${WORKFLOW} run ${RUN_ID} was still ${status:-unknown}${stuck_on:+ (step: ${stuck_on})} after ${TIMEOUT_SECONDS}s -- the OSS CDN was not confirmed updated for ${TAG}."
echo "::error::Run URL: ${RUN_URL}"
echo "::error::Re-check it with: gh run view ${RUN_ID}${REPO:+ --repo $REPO}"
echo "::error::Then re-trigger with: gh workflow run ${WORKFLOW}${REPO:+ --repo $REPO} -f tag_name=${TAG}"
exit 1
