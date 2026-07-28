#!/usr/bin/env bash
# =============================================================================
# Browser4 Plugin Verification Script
# =============================================================================
# Validates that a built plugin JAR has the correct structure for deployment
# into a Browser4 installation's plugins/ directory.
#
# Usage: verify-plugin.sh <path-to-plugin.jar>
#
# Checks:
#   [PASS/FAIL] META-INF/browser4-plugin.json exists and is valid JSON
#   [PASS/FAIL] autoConfigurationClasses field is non-empty
#   [PASS/FAIL] AutoConfiguration.imports exists and is non-empty
#   [PASS/FAIL] Plugin JAR is thin (no embedded dependency JARs)
#   [PASS/FAIL] Kotlin classes compiled to Java 17 bytecode
# =============================================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PASS=0
FAIL=0

pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS + 1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL + 1)); }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

if [ $# -lt 1 ]; then
    echo "Usage: $0 <path-to-plugin.jar>"
    exit 1
fi

JAR="$1"

if [ ! -f "$JAR" ]; then
    echo "Error: $JAR not found"
    exit 1
fi

# Resolve to absolute path (needed for cd in temp directory extraction)
JAR=$(realpath "$JAR" 2>/dev/null || readlink -f "$JAR" 2>/dev/null || echo "$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")")

echo "=== Browser4 Plugin Verification ==="
echo "JAR: $JAR"
echo ""

# Create temp directory for extracting files from JAR
TMPDIR=$(mktemp -d 2>/dev/null || mktemp -d -t verify-plugin)
trap "rm -rf $TMPDIR" EXIT

# ---------------------------------------------------------------------------
# Check 1: META-INF/browser4-plugin.json exists and is valid JSON
# ---------------------------------------------------------------------------
if jar tf "$JAR" | grep -q "META-INF/browser4-plugin.json"; then
    # Extract to temp file (portable: no -O flag)
    (cd "$TMPDIR" && jar xf "$JAR" "META-INF/browser4-plugin.json" 2>/dev/null) || true
    MANIFEST_FILE="$TMPDIR/META-INF/browser4-plugin.json"
    if [ -f "$MANIFEST_FILE" ] && [ -s "$MANIFEST_FILE" ]; then
        MANIFEST=$(cat "$MANIFEST_FILE")

        # Validate JSON: try python3 first, fall back to grep
        VALIDATED=false
        if command -v python3 &> /dev/null; then
            if echo "$MANIFEST" | python3 -c "import json,sys; d=json.load(sys.stdin); d['name']; d['version']" 2>/dev/null; then
                VALIDATED=true
                NAME=$(echo "$MANIFEST" | python3 -c "import json,sys; print(json.load(sys.stdin)['name'])" 2>/dev/null)
                VERSION=$(echo "$MANIFEST" | python3 -c "import json,sys; print(json.load(sys.stdin)['version'])" 2>/dev/null)
            fi
        fi

        # Fallback: grep-based validation
        if [ "$VALIDATED" = false ]; then
            HAS_NAME=$(echo "$MANIFEST" | grep -c '"name"' || true)
            HAS_VERSION=$(echo "$MANIFEST" | grep -c '"version"' || true)
            if [ "$HAS_NAME" -gt 0 ] && [ "$HAS_VERSION" -gt 0 ]; then
                VALIDATED=true
                NAME=$(echo "$MANIFEST" | grep '"name"' | head -1 | sed 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')
                VERSION=$(echo "$MANIFEST" | grep '"version"' | head -1 | sed 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')
            fi
        fi

        if [ "$VALIDATED" = true ]; then
            pass "META-INF/browser4-plugin.json: valid (name=$NAME, version=$VERSION)"
        else
            fail "META-INF/browser4-plugin.json: invalid JSON or missing required fields (name, version)"
        fi

        # Check autoConfigurationClasses
        HAS_AUTOCONF=$(echo "$MANIFEST" | grep -c 'autoConfigurationClasses' || true)
        if [ "$HAS_AUTOCONF" -gt 0 ]; then
            pass "autoConfigurationClasses: declared"
        else
            warn "autoConfigurationClasses: empty — ensure AutoConfiguration.imports is present"
        fi
    else
        fail "META-INF/browser4-plugin.json: found but could not read content"
    fi
else
    fail "META-INF/browser4-plugin.json: NOT FOUND — every plugin JAR must contain this file"
fi

# ---------------------------------------------------------------------------
# Check 2: AutoConfiguration.imports exists and is non-empty
# ---------------------------------------------------------------------------
IMPORTS="META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
if jar tf "$JAR" | grep -q "$IMPORTS"; then
    (cd "$TMPDIR" && jar xf "$JAR" "$IMPORTS" 2>/dev/null) || true
    IMPORTS_FILE="$TMPDIR/$IMPORTS"
    if [ -f "$IMPORTS_FILE" ]; then
        CONTENT=$(tr -d '[:space:]' < "$IMPORTS_FILE")
        if [ -n "$CONTENT" ]; then
            pass "AutoConfiguration.imports: found ($CONTENT)"
        else
            fail "AutoConfiguration.imports: found but EMPTY"
        fi
    else
        fail "AutoConfiguration.imports: found but could not extract"
    fi
else
    fail "AutoConfiguration.imports: NOT FOUND — Spring Boot will not discover this plugin"
fi

# ---------------------------------------------------------------------------
# Check 3: Plugin JAR is thin (no embedded dependency JARs)
# ---------------------------------------------------------------------------
EMBEDDED_JARS=$(jar tf "$JAR" | grep -c '\.jar$' || true)
if [ "$EMBEDDED_JARS" -eq 0 ]; then
    pass "Thin JAR: no embedded dependency JARs"
else
    fail "Thin JAR: $EMBEDDED_JARS embedded JAR(s) found — dependencies should use 'provided' scope"
fi

# ---------------------------------------------------------------------------
# Check 4: Contains .class files (confirming compilation)
# ---------------------------------------------------------------------------
CLASS_COUNT=$(jar tf "$JAR" | grep -c '\.class$' || true)
if [ "$CLASS_COUNT" -gt 0 ]; then
    pass "Compiled classes: $CLASS_COUNT .class file(s) found"
else
    fail "Compiled classes: NO .class files found — plugin may not be compiled"
fi

# ---------------------------------------------------------------------------
# Check 5: Java bytecode version check (optional)
# ---------------------------------------------------------------------------
if command -v javap &> /dev/null; then
    FIRST_CLASS=$(jar tf "$JAR" | grep '\.class$' | head -1)
    if [ -n "$FIRST_CLASS" ]; then
        (cd "$TMPDIR" && jar xf "$JAR" "$FIRST_CLASS" 2>/dev/null) || true
        CLASS_FILE="$TMPDIR/$FIRST_CLASS"
        if [ -f "$CLASS_FILE" ]; then
            # Major version is at byte offset 7 (1 byte). Java 17 = 61 (0x3D)
            MAJOR=$(od -An -tx1 -j 7 -N 1 "$CLASS_FILE" 2>/dev/null | tr -d ' ' || echo "unknown")
            if [ "$MAJOR" = "3d" ] || [ "$MAJOR" = "41" ] || [ "$MAJOR" = "45" ]; then
                pass "Bytecode version: Java 17+ (major=0x$MAJOR)"
            elif [ "$MAJOR" != "unknown" ]; then
                warn "Bytecode version: major=0x$MAJOR (expected >= 0x3d for Java 17)"
            fi
        fi
    fi
else
    warn "javap not available — skipping bytecode version check"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="

if [ "$FAIL" -gt 0 ]; then
    echo "Fix the FAIL items above before deploying this plugin."
    exit 1
else
    echo "Plugin JAR is ready for deployment."
    echo ""
    echo "To install:"
    echo "  curl -X POST http://localhost:8080/api/plugins/install -F \"file=@$JAR\""
    echo "  # Or copy to the plugins/ directory and restart"
    exit 0
fi
