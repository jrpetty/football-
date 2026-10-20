#!/usr/bin/env bash
# Runs every automated check. Usage: tools/run_all_checks.sh [path-to-godot]
set -u
GODOT="${1:-godot}"
cd "$(dirname "$0")/.."
fail=0
echo "== compile all scripts";  $GODOT --headless --path . res://tools/check_scripts.tscn 2>&1 | grep -E "checked|FAILED" || fail=1
echo "== unit tests";           $GODOT --headless --path . res://tests/run_tests.tscn 2>&1 | grep -E "FAIL|tests," || fail=1
$GODOT --headless --path . res://tests/run_tests.tscn >/dev/null 2>&1 || fail=1
echo "== in-engine playtest";   $GODOT --headless --path . res://tools/playtest.tscn 2>&1 | grep -E "✖|PLAYTEST" || fail=1
echo "== premises (move through all 7 buildings)"; $GODOT --headless --path . res://tools/premises_test.tscn 2>&1 | grep -E "✖|PREMISES" || fail=1
echo "== economy sim (bot trades 20 days)"; $GODOT --headless --path . res://tools/economy_sim.tscn -- --days 20 2>&1 | grep -E "ECONOMY|After|cash" || fail=1
echo "== co-op (host+client)"
$GODOT --headless --path . res://tools/net_test.tscn -- --role host > /tmp/refurb_host.log 2>&1 &
HOST=$!
$GODOT --headless --path . res://tools/net_test.tscn -- --role client > /tmp/refurb_client.log 2>&1 || fail=1
wait $HOST || fail=1
grep -hE "NET|✖" /tmp/refurb_host.log /tmp/refurb_client.log
if [ $fail -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED"; fi
exit $fail
