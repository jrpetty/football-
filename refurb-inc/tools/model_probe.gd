extends SceneTree
## Height profile of a processed model: for a grid of XZ cells, the highest vertex in each.
##   godot --headless --path . -s res://tools/model_probe.gd -- res://assets/models/<cat>/<name>/mesh.res [cells]


func _init() -> void:
	var a := OS.get_cmdline_user_args()
	var mesh: Mesh = load(a[0])
	var n := int(a[1]) if a.size() > 1 else 9
	var v: PackedVector3Array = mesh.surface_get_arrays(0)[Mesh.ARRAY_VERTEX]
	var lo := Vector3(INF, INF, INF)
	var hi := -lo
	for p in v:
		lo = lo.min(p)
		hi = hi.max(p)
	print("bbox ", lo, " .. ", hi)
	var grid := []
	for i in n * n:
		grid.append(-INF)
	for p in v:
		var ix := clampi(int((p.x - lo.x) / (hi.x - lo.x) * n), 0, n - 1)
		var iz := clampi(int((p.z - lo.z) / (hi.z - lo.z) * n), 0, n - 1)
		grid[iz * n + ix] = maxf(grid[iz * n + ix], p.y)
	for iz in n:
		var row := PackedStringArray()
		for ix in n:
			row.append("%.2f" % grid[iz * n + ix])
		print("z%+.2f  " % (lo.z + (iz + 0.5) * (hi.z - lo.z) / n), " ".join(row))
	quit()
