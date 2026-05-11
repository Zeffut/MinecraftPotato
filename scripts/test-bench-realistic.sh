#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PMH="$ROOT/scripts/pmh"
cleanup() { "$PMH" stop >/dev/null 2>&1 || true; }
trap cleanup EXIT

pkill -9 -f "minecraft\|KnotServer" 2>/dev/null || true
rm -f "$ROOT/run/world/session.lock" "$ROOT/.pmh/server.pid" "$ROOT/.pmh/server.log"

"$PMH" start
"$PMH" cmd 'forceload add -8 -8 8 8' >/dev/null
sleep 3
"$PMH" cmd 'gamerule maxCommandChainLength 1000000' >/dev/null 2>&1 || true

for wl in gameplay_player_pace gameplay_exploration explosion_burst worldgen_streaming; do
    echo "[realistic] $wl x 50 (seed=42)"
    R=$("$PMH" bench "$wl" 50 42)
    echo "$R" | jq -c '{workload, p:.potato.ops_per_sec, v:.vanilla.ops_per_sec, speedup:.speedup_potato_over_vanilla, p_p50_us:(.potato.p50_ns/1000), v_p50_us:(.vanilla.p50_ns/1000)}'
done

echo "[realistic] PASS"
