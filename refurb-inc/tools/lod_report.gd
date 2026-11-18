extends SceneTree
## Lists every model mesh under assets/models with its triangle count and how many LODs it carries:
##   godot --headless --path . -s tools/lod_report.gd


func _init() -> void:
	var rows: Array = []
	_scan("res://assets/models", rows)
	rows.sort_custom(func(a, b): return int(a[1]) > int(b[1]))
	for r in rows:
		print("%-70s %8d tris  %2d LODs" % r)
	quit()


func _scan(dir: String, rows: Array) -> void:
	for d in DirAccess.get_directories_at(dir):
		_scan(dir + "/" + d, rows)
	for f in DirAccess.get_files_at(dir):
		if not f.ends_with(".res"):
			continue
		var m = load(dir + "/" + f)
		if not m is ArrayMesh:
			continue
		var tris := 0
		var lods := 0
		for si in (m as ArrayMesh).get_surface_count():
			var sd: Dictionary = RenderingServer.mesh_get_surface(m.get_rid(), si)
			lods = maxi(lods, (sd.get("lods", []) as Array).size())
			var idx = m.surface_get_arrays(si)[Mesh.ARRAY_INDEX]
			tris += (idx as PackedInt32Array).size() / 3 if idx != null else 0
		rows.append([(dir + "/" + f).trim_prefix("res://assets/models/"), tris, lods])
