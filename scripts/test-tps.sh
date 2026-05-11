#!/usr/bin/env bash
# Measure TPS under entity load: vanilla (everything off) vs default PotatoMC.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PMH="$ROOT/scripts/pmh"

cleanup() {
    "$PMH" stop >/dev/null 2>&1 || true
    pkill -9 -f "KnotServer\|fabric-loader" 2>/dev/null || true
}
trap cleanup EXIT

cleanup
rm -f "$ROOT/run/world/session.lock" "$ROOT/.pmh/server.pid" "$ROOT/.pmh/server.log"

run_with_args() {
    local label="$1"; shift
    echo
    echo "=== ${label} ==="
    export PMH_EXTRA_VM_ARGS="$*"
    rm -f "$ROOT/.pmh/server.pid" "$ROOT/.pmh/server.log"
    pkill -9 -f "KnotServer\|fabric-loader" 2>/dev/null || true
    "$PMH" start >/dev/null
    sleep 5
    "$PMH" cmd 'forceload add 192 96 256 320' >/dev/null || true
    local x z
    for i in $(seq 1 50); do
        x=$((200 + RANDOM % 50))
        z=$((200 + RANDOM % 50))
        "$PMH" cmd "summon sheep $x 100 $z" >/dev/null || true
        "$PMH" cmd "summon zombie $x 100 $z" >/dev/null || true
        "$PMH" cmd "summon skeleton $x 100 $z" >/dev/null || true
        "$PMH" cmd "summon spider $x 100 $z" >/dev/null || true
    done
    echo "spawned ~200 mobs at (200..250, 100, 200..250); sleeping 30s to stabilize..."
    sleep 30
    "$PMH" tps
    "$PMH" stop >/dev/null
    sleep 3
}

run_with_args "vanilla (potatomc.disabled=true)" "-Dpotatomc.disabled=true"
run_with_args "potatomc default (memory + entity tick skip)" ""

echo
echo "done."
