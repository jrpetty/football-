extends Node
## Headless test runner:  godot --headless --path . res://tests/run_tests.tscn
## Runs every tests/unit/test_*.gd; each public method starting with test_ is one test. Exit code 1 on failure.

func _ready() -> void:
	await get_tree().process_frame
	var total := 0
	var failed := 0
	var dir := DirAccess.open("res://tests/unit")
	var files: Array = []
	for f in dir.get_files():
		if f.begins_with("test_") and f.ends_with(".gd"):
			files.append(f)
	files.sort()
	for f in files:
		var script: GDScript = load("res://tests/unit/" + f)
		if script == null or not script.can_instantiate():
			total += 1
			failed += 1
			print("  FAIL ", f, " (script failed to load)")
			continue
		var inst: TestCase = script.new()
		for m in script.get_script_method_list():
			var mname: String = m.name
			if not mname.begins_with("test_"):
				continue
			total += 1
			inst.current = "%s::%s" % [f.get_basename(), mname]
			var before := inst.failures.size()
			var r: Variant = inst.call(mname)
			if r is Object and r.has_method("is_valid"):
				await r
			if inst.failures.size() > before:
				failed += 1
				print("  FAIL ", inst.current)
				for i in range(before, inst.failures.size()):
					print("       ", inst.failures[i])
			else:
				print("  ok   ", inst.current)
	print("\n%d tests, %d failed" % [total, failed])
	get_tree().quit(1 if failed > 0 else 0)
