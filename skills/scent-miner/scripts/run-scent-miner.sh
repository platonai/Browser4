#!/usr/bin/env bash
# =============================================================================
# run-scent-miner.sh — Run scent-miner over a directory of HTML files
# =============================================================================
# Downloads scent-miner.jar from GitHub releases if needed, resolves a Java
# runtime, and runs the WebMiner to extract structured data from local HTML.
#
# Java resolution (in order):
#   1. $JAVA_HOME/bin/java
#   2. java on system PATH
#   3. Browser4 runtime bundle JRE
#
# Usage:
#   ./skills/scent-miner/scripts/run-scent-miner.sh --input /data/pages
#   ./skills/scent-miner/scripts/run-scent-miner.sh --input /data/pages --component-selector "#main"
#   ./skills/scent-miner/scripts/run-scent-miner.sh --input /data/pages --limit 50
#
# Scent-miner is part of platonai/web-miner: https://github.com/platonai/web-miner
# =============================================================================

set -euo pipefail

# -----------------------------------------------------------------------------
# Resolve repository root (3 levels up from skills/scent-miner/scripts/)
# -----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# -----------------------------------------------------------------------------
# Defaults
# -----------------------------------------------------------------------------
INPUT_DIR=""
OUTPUT_DIR=""
SCENT_MINER_JAR=""
SCENT_MINER_VERSION="${SCENT_MINER_VERSION:-}"
COMPONENT_SELECTOR=""
LIMIT=0
REQUIRE_SIZE=500000
NO_TRUST_SAMPLES=false
EXTRA_ARGS=()

# -----------------------------------------------------------------------------
# Parse arguments
# -----------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --input|-i)
            INPUT_DIR="$2"; shift 2 ;;
        --output|-o)
            OUTPUT_DIR="$2"; shift 2 ;;
        --jar)
            SCENT_MINER_JAR="$2"; shift 2 ;;
        --version)
            SCENT_MINER_VERSION="$2"; shift 2 ;;
        --component-selector|-c)
            COMPONENT_SELECTOR="$2"; shift 2 ;;
        --limit|-l)
            LIMIT="$2"; shift 2 ;;
        --require-size)
            REQUIRE_SIZE="$2"; shift 2 ;;
        --no-trust-samples)
            NO_TRUST_SAMPLES=true; shift ;;
        --help|-h)
            echo "Usage: $0 --input <dir> [options]"
            echo ""
            echo "Options:"
            echo "  --input, -i <path>          Directory with *.html/*.htm files (required)"
            echo "  --output, -o <path>         Output directory (default: <input>-views)"
            echo "  --jar <path>                Path to scent-miner.jar"
            echo "  --version <ver>             Scent-miner version to download"
            echo "  --component-selector, -c    CSS selector for content area"
            echo "  --limit, -l <N>             Max pages to process"
            echo "  --require-size <bytes>      Min page size (default: 500000)"
            echo "  --no-trust-samples          Validate samples instead of trusting"
            echo "  --help, -h                  Show this help"
            exit 0 ;;
        *)
            EXTRA_ARGS+=("$1"); shift ;;
    esac
done

# -----------------------------------------------------------------------------
# Validate
# -----------------------------------------------------------------------------
if [[ -z "$INPUT_DIR" ]]; then
    echo "ERROR: --input <dir> is required."
    echo "Usage: $0 --input <dir> [options]"
    exit 1
fi

if [[ ! -d "$INPUT_DIR" ]]; then
    echo "ERROR: Input directory not found: $INPUT_DIR"
    exit 1
fi

if [[ -z "$OUTPUT_DIR" ]]; then
    OUTPUT_DIR="${INPUT_DIR}-views"
fi

if [[ -z "$SCENT_MINER_JAR" ]]; then
    SCENT_MINER_JAR="$REPO_ROOT/skills/scent-miner/scripts/scent-miner.jar"
fi

# -----------------------------------------------------------------------------
# 1. Resolve Java
# -----------------------------------------------------------------------------
resolve_java() {
    # 1a. JAVA_HOME
    if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_HOME/bin/java" ]]; then
        echo "[Java] Using JAVA_HOME: $JAVA_HOME/bin/java" >&2
        echo "$JAVA_HOME/bin/java"
        return
    fi

    # 1b. System PATH
    if command -v java &>/dev/null; then
        local java_path
        java_path="$(command -v java)"
        echo "[Java] Using system PATH: $java_path" >&2
        echo "$java_path"
        return
    fi

    # 1c. Browser4 runtime bundle
    local bundle_base="$REPO_ROOT/browser4-apps/browser4-bundle/target/runtime-bundle/_work"
    if [[ -d "$bundle_base" ]]; then
        local bundle
        bundle=$(find "$bundle_base" -maxdepth 2 -name "browser4-bundle-runtime-*" -type d 2>/dev/null | sort -r | head -1)
        if [[ -n "$bundle" ]]; then
            local rt="$bundle/runtime/bin/java"
            if [[ -x "$rt" ]]; then
                echo "[Java] Using runtime bundle: $rt" >&2
                echo "$rt"
                return
            fi
        fi
    fi

    echo "ERROR: No Java runtime found. Tried:" >&2
    echo "  - \$JAVA_HOME/bin/java" >&2
    echo "  - java on system PATH" >&2
    echo "  - Browser4 runtime bundle JRE" >&2
    echo "" >&2
    echo "Install Java 17+ or set JAVA_HOME." >&2
    exit 1
}

# -----------------------------------------------------------------------------
# 2. Download scent-miner.jar if not found
# -----------------------------------------------------------------------------
ensure_scent_miner_jar() {
    local jar_path="$1"

    if [[ -f "$jar_path" ]]; then
        echo "[Jar] Found: $jar_path" >&2
        echo "$jar_path"
        return
    fi

    local jar_dir
    jar_dir="$(dirname "$jar_path")"
    mkdir -p "$jar_dir"

    local download_url
    if [[ -n "$SCENT_MINER_VERSION" ]]; then
        download_url="https://github.com/platonai/web-miner/releases/download/v${SCENT_MINER_VERSION}/scent-miner.jar"
    else
        download_url="https://github.com/platonai/web-miner/releases/latest/download/scent-miner.jar"
    fi

    echo "[Download] Fetching scent-miner.jar from GitHub releases..." >&2
    echo "[Download] $download_url" >&2

    if command -v curl &>/dev/null; then
        curl -L --progress-bar -o "$jar_path" "$download_url"
    elif command -v wget &>/dev/null; then
        wget -q --show-progress -O "$jar_path" "$download_url"
    else
        echo "ERROR: Neither curl nor wget found. Cannot download scent-miner.jar." >&2
        echo "" >&2
        echo "Download manually:" >&2
        echo "  1. Visit https://github.com/platonai/web-miner/releases" >&2
        echo "  2. Download scent-miner.jar from the latest release" >&2
        echo "  3. Place it at: $jar_path" >&2
        exit 1
    fi

    if [[ -f "$jar_path" ]]; then
        local size
        size=$(stat -c%s "$jar_path" 2>/dev/null || stat -f%z "$jar_path" 2>/dev/null || echo "unknown")
        echo "[Download] Done: $jar_path (${size} bytes)" >&2
        echo "$jar_path"
    else
        echo "ERROR: Failed to download scent-miner.jar." >&2
        echo "Download manually from https://github.com/platonai/web-miner/releases" >&2
        echo "Place it at: $jar_path" >&2
        exit 1
    fi
}

# -----------------------------------------------------------------------------
# 3. Build and run
# -----------------------------------------------------------------------------
JAVA_EXE=$(resolve_java)
JAR_PATH=$(ensure_scent_miner_jar "$SCENT_MINER_JAR")

echo "============================================================" >&2
echo "         Scent-Miner (WebMiner)                             " >&2
echo "============================================================" >&2
echo "" >&2
echo "  Input  : $INPUT_DIR" >&2
echo "  Output : $OUTPUT_DIR" >&2
echo "  Java   : $JAVA_EXE" >&2
echo "  Jar    : $JAR_PATH" >&2
[[ -n "$COMPONENT_SELECTOR" ]] && echo "  CSS    : $COMPONENT_SELECTOR" >&2
[[ "$LIMIT" -gt 0 ]] && echo "  Limit  : $LIMIT pages" >&2
echo "" >&2

# Build argument list
JAVA_ARGS=(-jar "$JAR_PATH" --input "$INPUT_DIR")

if [[ "$OUTPUT_DIR" != "${INPUT_DIR}-views" ]]; then
    JAVA_ARGS+=(--output "$OUTPUT_DIR")
fi
if [[ -n "$COMPONENT_SELECTOR" ]]; then
    JAVA_ARGS+=(--component-selector "$COMPONENT_SELECTOR")
fi
if [[ "$LIMIT" -gt 0 ]]; then
    JAVA_ARGS+=(--limit "$LIMIT")
fi
if [[ "$REQUIRE_SIZE" -ne 500000 ]]; then
    JAVA_ARGS+=(--require-size "$REQUIRE_SIZE")
fi
if [[ "$NO_TRUST_SAMPLES" == true ]]; then
    JAVA_ARGS+=(--no-trust-samples)
fi
if [[ ${#EXTRA_ARGS[@]} -gt 0 ]]; then
    JAVA_ARGS+=("${EXTRA_ARGS[@]}")
fi

echo "Running scent-miner..." >&2
echo "" >&2

set +e
"$JAVA_EXE" "${JAVA_ARGS[@]}"
EXIT_CODE=$?
set -e

echo "" >&2

if [[ $EXIT_CODE -eq 0 ]]; then
    echo "Scent-miner completed successfully." >&2
    echo "" >&2
    echo "  Output views: $OUTPUT_DIR" >&2
    if [[ -f "$OUTPUT_DIR/views/index.html" ]]; then
        echo "  HTML report : $OUTPUT_DIR/views/index.html" >&2
    fi
    if [[ -d "$OUTPUT_DIR/views" ]]; then
        local xlsx_count
        xlsx_count=$(find "$OUTPUT_DIR/views" -maxdepth 1 -name "*.xlsx" 2>/dev/null | wc -l)
        if [[ "$xlsx_count" -gt 0 ]]; then
            echo "  Excel files : ${xlsx_count} file(s)" >&2
            find "$OUTPUT_DIR/views" -maxdepth 1 -name "*.xlsx" -exec basename {} \; | while read -r f; do
                echo "    $f" >&2
            done
        fi
    fi
else
    echo "ERROR: Scent-miner exited with code: $EXIT_CODE" >&2
fi

exit $EXIT_CODE
