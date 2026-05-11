#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PMH="$ROOT/scripts/pmh"

cleanup() {
  "$PMH" stop >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "[test] starting server"
"$PMH" start

echo "[test] /health"
HEALTH=$("$PMH" health)
echo "$HEALTH" | jq -e '.status == "ok"' >/dev/null
echo "$HEALTH" | jq -e '.mc_version' >/dev/null

echo "[test] /cmd"
RES=$("$PMH" cmd 'say hello-from-test')
echo "$RES" | jq -e '.success == true' >/dev/null

echo "[test] forceload chunk near spawn so block ops have effect"
"$PMH" cmd 'forceload add 0 0' >/dev/null
sleep 1

echo "[test] /light at spawn (Y=100, sky-exposed)"
LIGHT=$("$PMH" light 0 100 0)
echo "$LIGHT" | jq -e '.vanilla_sky | numbers' >/dev/null
echo "$LIGHT" | jq -e '.match == true' >/dev/null

echo "[test] place glowstone + read light at neighbor"
"$PMH" cmd 'setblock 0 100 0 air' >/dev/null
"$PMH" place glowstone 0 100 0 >/dev/null
sleep 1
LIGHT2=$("$PMH" light 1 100 0)
echo "$LIGHT2" | jq -c '{vanilla_block, vanilla_sky, match}'
echo "$LIGHT2" | jq -e '.vanilla_block >= 14' >/dev/null

echo "[test] PASS"
