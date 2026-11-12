extends Node
## Compiles every script in the project and reports errors.  godot --headless --path . res://tools/check_scripts.tscn

func _ready() -> void:
	var bad := 0
	var files: Array = []
	_walk("res://", files)
	for f in files:
		if f.ends_with("check_scripts.gd"):
			continue
		var s: GDScript = load(f)
		if s == null or not s.can_instantiate():
			print("FAILED: ", f)
			bad += 1
	print("%d scripts checked, %d failed" % [files.size(), bad])
	get_tree().quit(1 if bad > 0 else 0)


func _walk(dir: String, out: Array) -> void:
	var d := DirAccess.open(dir)
	if d == null:
		return
	for f in d.get_files():
		if f.ends_with(".gd"):
			out.append(dir.path_join(f))
	for sub in d.get_directories():
		if sub.begins_with(".") or sub == "addons":
			continue
		_walk(dir.path_join(sub), out)
