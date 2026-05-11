#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PMH="$ROOT/scripts/pmh"

cleanup() { "$PMH" stop >/dev/null 2>&1 || true; }
trap cleanup EXIT

"$PMH" start
"$PMH" cmd 'forceload add -2 -2 2 2' >/dev/null
sleep 1

echo "[bench-test] single_block_update x 200"
R=$("$PMH" bench single_block_update 200 42)
echo "$R" | jq -c '{workload, iterations, potato_ops:.potato.ops_per_sec, vanilla_ops:.vanilla.ops_per_sec, speedup:.speedup_potato_over_vanilla}'
echo "$R" | jq -e '.potato.ops_per_sec > 0' >/dev/null
echo "$R" | jq -e '.vanilla.ops_per_sec > 0' >/dev/null

echo "[bench-test] bulk_random_updates x 100"
R=$("$PMH" bench bulk_random_updates 100 42)
echo "$R" | jq -c '{workload, iterations, potato_ops:.potato.ops_per_sec, vanilla_ops:.vanilla.ops_per_sec, speedup:.speedup_potato_over_vanilla}'
echo "$R" | jq -e '.potato.ops_per_sec > 0' >/dev/null

echo "[bench-test] full_chunk_relight x 50"
R=$("$PMH" bench full_chunk_relight 50 42)
echo "$R" | jq -c '{workload, iterations, potato_ops:.potato.ops_per_sec, vanilla_ops:.vanilla.ops_per_sec, speedup:.speedup_potato_over_vanilla}'
echo "$R" | jq -e '.potato.ops_per_sec > 0' >/dev/null

echo "[bench-test] PASS"
