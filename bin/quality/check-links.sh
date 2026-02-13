#!/usr/bin/env bash
#
# Shell wrapper for link checker
# Ensures Python 3 is available and runs the link checker with proper error handling
#

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
PYTHON_SCRIPT="$SCRIPT_DIR/fix-links.py"

# Check if Python 3 is available
if ! command -v python3 &> /dev/null; then
    echo "❌ Error: Python 3 is not installed or not in PATH" >&2
    exit 1
fi

# Check if requests library is available
if ! python3 -c "import requests" 2>/dev/null; then
    echo "⚠️  Warning: 'requests' library not found." >&2
    echo "   This script requires the 'requests' library to function." >&2
    echo "" >&2

    # Try to install if pip is available
    if command -v pip3 &> /dev/null; then
        echo "   Attempting automatic installation with pip3..." >&2
        echo "   (This is safe - requests is a well-known HTTP library)" >&2
        pip3 install requests --user || {
            echo "❌ Failed to install requests. Please install manually:" >&2
            echo "   pip3 install requests" >&2
            exit 1
        }
    else
        echo "❌ pip3 not found. Please install manually:" >&2
        echo "   pip3 install requests" >&2
        exit 1
    fi
fi

# Change to root directory
cd "$ROOT_DIR"

# Run the Python script with all arguments passed through
extra_args=()
if [[ "${BROWSER4_CHECK_LOCALHOST:-}" == "1" ]]; then
    extra_args+=("--check-localhost")
fi

python3 "$PYTHON_SCRIPT" "${extra_args[@]}" "$@"
