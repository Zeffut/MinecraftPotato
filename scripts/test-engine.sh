#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PMH="$ROOT/scripts/pmh"

cleanup() { "$PMH" stop >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "[engine-test] start"
"$PMH" start

echo "[engine-test] stats"
STATS=$("$PMH" stats)
echo "$STATS" | jq -e '.engine_active == true' >/dev/null

echo "[engine-test] forceload + glowstone"
"$PMH" cmd 'forceload add 0 0' >/dev/null
"$PMH" cmd 'setblock 0 100 0 air' >/dev/null
"$PMH" place glowstone 0 100 0 >/dev/null
sleep 2

echo "[engine-test] light at neighbor"
LIGHT=$("$PMH" light 1 100 0)
echo "$LIGHT" | jq -c .
echo "$LIGHT" | jq -e '.potato_block >= 14' >/dev/null
echo "$LIGHT" | jq -e '.vanilla_block >= 14' >/dev/null

echo "[engine-test] validate radius=1 around glowstone"
VAL=$("$PMH" validate 1 0 100 0)
echo "$VAL" | jq -c .
echo "$VAL" | jq -e '.max_delta <= 1' >/dev/null
echo "$VAL" | jq -e '.pass == true' >/dev/null

echo "[engine-test] stats after"
STATS2=$("$PMH" stats)
echo "$STATS2" | jq -e '.sections_tracked >= 1' >/dev/null

echo "[engine-test] PASS"
