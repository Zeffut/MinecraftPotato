#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PMH="$ROOT/scripts/pmh"

export PMH_GRADLE_TASK=":potatomc:runServerLimited"
export PMH_RUN_DIR="run-limited"

cleanup() { "$PMH" stop >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "[bench-limited] starting CONSTRAINED server (1 core, 1GB heap, SerialGC)"
"$PMH" start
"$PMH" cmd 'forceload add -2 -2 2 2' >/dev/null
sleep 1

for workload in single_block_update bulk_random_updates full_chunk_relight bulk_writes_no_read chunk_load_cold; do
    iters=100
    if [[ "$workload" == "chunk_load_cold" ]]; then iters=30; fi
    echo "[bench-limited] $workload x $iters"
    R=$("$PMH" bench "$workload" "$iters" 42)
    echo "$R" | jq -c '{workload, potato_ops:.potato.ops_per_sec, vanilla_ops:.vanilla.ops_per_sec, speedup:.speedup_potato_over_vanilla}'
    echo "$R" | jq -e '.potato.ops_per_sec > 0' >/dev/null
done

echo "[bench-limited] flush_stats after benches:"
"$PMH" stats | jq '.flush_stats'

echo "[bench-limited] PASS"
