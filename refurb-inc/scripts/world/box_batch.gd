class_name BoxBatch
extends RefCounted
## Builds lots of boxes / prisms / cylinders into ONE mesh per material (racking frames, decor, floor lines):
## a 3-bay rack is ~80 boxes but only two draw calls. Add shapes, then commit() under a parent.

var _tools := {}          # Material -> SurfaceTool
var _order: Array = []    # materials in first-use order (stable node names)


func _st(mat: Material) -> SurfaceTool:
	if not _tools.has(mat):
		var st := SurfaceTool.new()
		st.begin(Mesh.PRIMITIVE_TRIANGLES)
		_tools[mat] = st
		_order.append(mat)
	return _tools[mat]


func _quad(st: SurfaceTool, c: Array, n: Vector3, uv_w: float, uv_h: float) -> void:
	## Two triangles for corners c0..c3 (in order round the face), front side facing `n` (Godot: clockwise).
	var a: Vector3 = c[0]
	var b: Vector3 = c[1]
	var d: Vector3 = c[2]
	var flip := (b - a).cross(d - a).dot(n) > 0.0
	var uvs := [Vector2(0, 0), Vector2(uv_w, 0), Vector2(uv_w, uv_h), Vector2(0, uv_h)]
	var tris := [[0, 2, 1], [0, 3, 2]] if flip else [[0, 1, 2], [0, 2, 3]]
	for t in tris:
		for k in t:
			st.set_normal(n)
			st.set_uv(uvs[k])
			st.add_vertex(c[k])


func box_xf(size: Vector3, xf: Transform3D, mat: Material) -> void:
	var st := _st(mat)
	var h := size * 0.5
	var b := xf.basis
	for axis in 3:
		for sgn in [-1.0, 1.0]:
			var n := Vector3.ZERO
			n[axis] = sgn
			var u := Vector3.ZERO
			var v := Vector3.ZERO
			u[(axis + 1) % 3] = 1.0
			v[(axis + 2) % 3] = 1.0
			var cn := n * h[axis]
			var cu := u * h[(axis + 1) % 3]
			var cv := v * h[(axis + 2) % 3]
			var corners := [cn - cu - cv, cn + cu - cv, cn + cu + cv, cn - cu + cv]
			for i in 4:
				corners[i] = xf * (corners[i] as Vector3)
			_quad(st, corners, (b * n).normalized(), size[(axis + 1) % 3], size[(axis + 2) % 3])


func box(size: Vector3, pos: Vector3, mat: Material, rot_deg: Vector3 = Vector3.ZERO) -> void:
	box_xf(size, Transform3D(Basis.from_euler(rot_deg * (PI / 180.0)), pos), mat)


func beam(a: Vector3, b: Vector3, thick: Vector2, mat: Material) -> void:
	## A box running from a to b (thick = across, up).
	var d := b - a
	var length := d.length()
	if length < 0.0001:
		return
	var fwd := d / length
	var up := Vector3.UP if absf(fwd.dot(Vector3.UP)) < 0.95 else Vector3.BACK
	var right := up.cross(fwd).normalized()
	up = fwd.cross(right).normalized()
	box_xf(Vector3(thick.x, thick.y, length), Transform3D(Basis(right, up, fwd), (a + b) * 0.5), mat)


func cyl(radius: float, height: float, xf: Transform3D, mat: Material, sides: int = 12, top_r: float = -1.0) -> void:
	## Cylinder along local Y, centred on xf.origin.
	var st := _st(mat)
	var tr := radius if top_r < 0.0 else top_r
	var hh := height * 0.5
	for i in sides:
		var a0 := TAU * i / sides
		var a1 := TAU * (i + 1) / sides
		var p0 := Vector3(cos(a0), 0, sin(a0))
		var p1 := Vector3(cos(a1), 0, sin(a1))
		var n := xf.basis * ((p0 + p1) * 0.5).normalized()
		_quad(st, [xf * (p0 * radius - Vector3(0, hh, 0)), xf * (p1 * radius - Vector3(0, hh, 0)),
			xf * (p1 * tr + Vector3(0, hh, 0)), xf * (p0 * tr + Vector3(0, hh, 0))], n.normalized(), radius * TAU / sides, height)
		for cap in [-1.0, 1.0]:
			var r := radius if cap < 0.0 else tr
			var cy := Vector3(0, hh * cap, 0)
			var cn := (xf.basis * Vector3(0, cap, 0)).normalized()
			var tri := [xf * cy, xf * (cy + p0 * r), xf * (cy + p1 * r)]
			var flip: bool = ((tri[1] - tri[0]) as Vector3).cross(tri[2] - tri[0]).dot(cn) > 0.0
			for k in ([0, 2, 1] if flip else [0, 1, 2]):
				st.set_normal(cn)
				st.set_uv(Vector2((tri[k] as Vector3).x, (tri[k] as Vector3).z))
				st.add_vertex(tri[k])


func is_empty() -> bool:
	return _tools.is_empty()


func commit(parent: Node3D, base_name: String = "Batch", shadows: bool = true) -> Array:
	var out: Array = []
	var i := 0
	for mat: Material in _order:
		var st: SurfaceTool = _tools[mat]
		st.generate_tangents()
		var mi := MeshInstance3D.new()
		mi.name = "%s%d" % [base_name, i]
		mi.mesh = st.commit()
		mi.material_override = mat
		if not shadows:
			mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		parent.add_child(mi)
		out.append(mi)
		i += 1
	_tools.clear()
	_order.clear()
	return out
