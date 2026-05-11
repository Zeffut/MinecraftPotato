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
    # Kill any zombie gradle/java processes still holding the harness/MC port.
    pkill -9 -f "KnotServer" >/dev/null 2>&1 || true
    pkill -9 -f "runServer" >/dev/null 2>&1 || true
    sleep 1
}
trap cleanup EXIT

mkdir -p "$ROOT/run/mods"

declare -a RESULTS

# Fresh-world reset: deletes run/world entirely so every config starts from
# the same vanilla generation. Reliable cleanup — wipes session lock too.
reset_world() {
    rm -rf "$ROOT/run/world" 2>/dev/null || true
    rm -f "$ROOT/.pmh/server.pid" "$ROOT/.pmh/server.log" 2>/dev/null || true
}

run_config() {
    local label="$1"
    local with_ferrite="$2"
    local vm_args="$3"

    echo ""
    echo "=== Config: $label (ferrite=$with_ferrite, vm_args='$vm_args') ==="
    cleanup
    reset_world

    if [ "$with_ferrite" = "yes" ]; then
        cp "$FERRITE" "$ROOT/run/mods/"
    fi

    if [ -n "$vm_args" ]; then
        export PMH_EXTRA_VM_ARGS="$vm_args"
    else
        unset PMH_EXTRA_VM_ARGS || true
    fi

    "$PMH" start >/dev/null

    # Smaller region than before for faster iteration: 17x17 = 289 chunks.
    # Still ~6900 sections — plenty of memory pressure to see the lighting delta.
    "$PMH" cmd 'forceload add -8 -8 8 8' >/dev/null || true
    sleep 10

    "$PMH" gc >/dev/null
    sleep 1
    "$PMH" gc >/dev/null
    sleep 1

    local mem stats
    mem=$("$PMH" memory)
    stats=$("$PMH" stats)
    local lighting_active memory_active
    lighting_active=$(echo "$stats" | jq -r '.lighting_active // .engine_active // false')
    memory_active=$(echo "$stats" | jq -r '.memory_active // false')

    local line
    line=$(echo "$mem" | jq -c --arg label "$label" \
        --argjson la "$lighting_active" --argjson ma "$memory_active" \
        '{label: $label, lighting: $la, memory: $ma, heap_used_mb: .heap_used_mb, heap_total_mb: .heap_total_mb, gc_collections: ([.gc[].collections] | add), gc_time_ms: ([.gc[].time_ms] | add)}')
    echo "RESULT: $line"
    RESULTS+=("$line")

    "$PMH" stop >/dev/null
    sleep 2
}

# 6-config matrix
run_config "vanilla"             "no"  "-Dpotatomc.disabled=true"
run_config "ferritecore"         "yes" "-Dpotatomc.disabled=true"
run_config "memory-only"         "no"  "-Dpotatomc.lighting.disabled=true"
run_config "lighting-only"       "no"  "-Dpotatomc.memory.disabled=true"
run_config "potato"              "no"  ""
run_config "potato+ferritecore"  "yes" ""

echo ""
echo "=== SUMMARY ==="
for r in "${RESULTS[@]}"; do
    echo "$r"
done

echo "[memory-test] done"
