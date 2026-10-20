class_name TestCase
extends RefCounted
## Minimal assertion helpers for the headless test runner (tests/run_tests.tscn).

var failures: PackedStringArray = []
var current := ""


func check(cond: bool, msg: String = "") -> void:
	if not cond:
		failures.append("%s: %s" % [current, msg if msg != "" else "assertion failed"])


func eq(a: Variant, b: Variant, msg: String = "") -> void:
	if a != b:
		failures.append("%s: expected %s == %s %s" % [current, str(a), str(b), msg])


func near(a: float, b: float, tol: float, msg: String = "") -> void:
	if absf(a - b) > tol:
		failures.append("%s: expected %f ≈ %f (±%f) %s" % [current, a, b, tol, msg])


func fresh_sim(seed_value: int = 12345) -> Sim:
	var s := GameState.new()
	var sim := Sim.new(s)
	sim.new_game(seed_value)
	return sim
