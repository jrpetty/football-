extends SceneTree
## Cross-section profile of a model scene (for lining up surfaces, rails, joints):
##   godot --headless --path . -s tools/model_profile.gd -- res://path/to/model.tscn [x|z]
func _init() -> void:
	var a := OS.get_cmdline_user_args()
	var sc: Node3D = load(a[0]).instantiate()
	var axis := a[1] if a.size() > 1 else "x"
	var pts: Array = []
	for n: MeshInstance3D in sc.find_children("*", "MeshInstance3D", true, false):
		var xf := Transform3D.IDENTITY
		var cur: Node = n
		while cur != sc:
			xf = (cur as Node3D).transform * xf
			cur = cur.get_parent()
		for v: Vector3 in n.mesh.surface_get_arrays(0)[Mesh.ARRAY_VERTEX]:
			pts.append(xf * v)
	for b in range(-12, 13):
		var c0 := b * 0.05
		var ys := []
		for p: Vector3 in pts:
			var h: float = p.x if axis == "x" else p.z
			if h >= c0 and h < c0 + 0.05:
				ys.append(p.y)
		if ys.is_empty():
			continue
		ys.sort()
		var cl := []
		var s: float = ys[0]
		var prev: float = ys[0]
		for y: float in ys:
			if y - prev > 0.04:
				cl.append("%.2f..%.2f" % [s, prev])
				s = y
			prev = y
		cl.append("%.2f..%.2f" % [s, prev])
		print("%s %.2f: %s" % [axis, c0, "  ".join(cl)])
	quit()
