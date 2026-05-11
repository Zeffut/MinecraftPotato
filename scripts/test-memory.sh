#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PMH="$ROOT/scripts/pmh"

mkdir -p "$ROOT/.pmh/external-mods"
FERRITE="$ROOT/.pmh/external-mods/ferritecore-8.2.0-fabric.jar"

if [ ! -f "$FERRITE" ]; then
    echo "[memory-test] downloading FerriteCore..."
    URL=$(curl -s "https://api.modrinth.com/v2/project/ferrite-core/version?game_versions=%5B%221.21.11%22%5D&loaders=%5B%22fabric%22%5D" \
        | jq -r '.[0].files[0].url')
    if [ -z "$URL" ] || [ "$URL" = "null" ]; then
        echo "[memory-test] ERROR: could not resolve FerriteCore URL from Modrinth"
        exit 1
    fi
    curl -fsSL "$URL" -o "$FERRITE"
fi

cleanup() {
    "$PMH" stop >/dev/null 2>&1 || true
    rm -f "$ROOT/run/mods/ferritecore-8.2.0-fabric.jar" 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$ROOT/run/mods"

declare -a RESULTS

run_config() {
    local label="$1"
    local with_ferrite="$2"
    local potato_disabled="$3"

    echo ""
    echo "=== Config: $label (ferrite=$with_ferrite, potato_disabled=$potato_disabled) ==="
    cleanup
    rm -f "$ROOT/.pmh/server.pid" "$ROOT/.pmh/server.log"
    rm -f "$ROOT/run/world/session.lock" 2>/dev/null || true

    if [ "$with_ferrite" = "yes" ]; then
        cp "$FERRITE" "$ROOT/run/mods/"
    fi

    if [ "$potato_disabled" = "yes" ]; then
        export PMH_EXTRA_VM_ARGS='-Dpotatomc.disabled=true'
    else
        unset PMH_EXTRA_VM_ARGS || true
    fi

    "$PMH" start >/dev/null

    # forceload limit is 256 chunks. Use a 15x15 region = 225 chunks.
    "$PMH" cmd 'forceload add -112 -112 112 112' >/dev/null || true
    sleep 10

    "$PMH" gc >/dev/null
    sleep 1

    local mem
    mem=$("$PMH" memory)
    local line
    line=$(echo "$mem" | jq -c --arg label "$label" \
        '{label: $label, heap_used_mb: .heap_used_mb, heap_total_mb: .heap_total_mb, gc_collections: ([.gc[].collections] | add), gc_time_ms: ([.gc[].time_ms] | add)}')
    echo "RESULT: $line"
    RESULTS+=("$line")

    "$PMH" stop >/dev/null
    sleep 2
}

run_config "vanilla"             "no"  "yes"
run_config "ferritecore"         "yes" "yes"
run_config "potato"              "no"  "no"
run_config "potato+ferritecore"  "yes" "no"

echo ""
echo "=== SUMMARY ==="
for r in "${RESULTS[@]}"; do
    echo "$r"
done

echo "[memory-test] done"
