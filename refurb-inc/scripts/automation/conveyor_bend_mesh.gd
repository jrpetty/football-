class_name ConveyorBendMesh
extends RefCounted
## Builds a roller-conveyor bend of any angle/radius in the same style and dimensions as Jon's straight
## conveyor (dark side channels with yellow top flanges at |x| 0.30–0.46, steel rollers topping out at
## 0.85 m, black legs with silver feet), so bends and straights line up seamlessly at either end.
## Rollers fan out radially and taper towards the inside, like a real curved conveyor.

const FRAME_IN := 0.305       # inner face of each side channel (from the belt centreline)
const FRAME_OUT := 0.455
const FRAME_LO := 0.74
const FRAME_HI := 0.882
const CAP := 0.022
const ROLLER_Y := 0.822
const ROLLER_R := 0.028
const PITCH := 0.078

static var _mats := {}


static func _mat(key: String) -> Material:
	if _mats.has(key):
		return _mats[key]
	var m: StandardMaterial3D
	match key:
		"frame":
			m = Mats.painted_metal(Color(0.2, 0.22, 0.26))
		"yellow":
			m = Mats.color(Color(1.0, 0.78, 0.06), 0.45, 0.2)
		"roller":
			m = Mats.color(Color(0.56, 0.59, 0.63), 0.3, 0.9)
		"leg":
			m = Mats.color(Color(0.08, 0.08, 0.09), 0.5, 0.4)
		"foot":
			m = Mats.color(Color(0.75, 0.76, 0.78), 0.3, 0.9)
		"motor":
			m = Mats.painted_metal(Color(0.12, 0.28, 0.55))
		_:
			m = Mats.color(Color(0.05, 0.05, 0.06), 0.6)
	_mats[key] = m
	return m


static func build(lb: Dictionary, parent: Node3D) -> void:
	var length: float = lb.length
	var segs := maxi(int(ceil(length / 0.08)), 4)
	# Side channels and their yellow flanges, swept along the arc.
	for side in [-1.0, 1.0]:
		var ch := SurfaceTool.new()
		ch.begin(Mesh.PRIMITIVE_TRIANGLES)
		_sweep(ch, lb, segs, side * FRAME_IN, side * FRAME_OUT, FRAME_LO, FRAME_HI)
		_add(parent, ch, _mat("frame"))
		var cap := SurfaceTool.new()
		cap.begin(Mesh.PRIMITIVE_TRIANGLES)
		_sweep(cap, lb, segs, side * (FRAME_IN - 0.006), side * (FRAME_OUT + 0.004), FRAME_HI, FRAME_HI + CAP)
		_add(parent, cap, _mat("yellow"))
	# Rollers: one MultiMesh of tapered cylinders laid radially.
	var n := maxi(int(floor((length - 0.04) / PITCH)), 1)
	var cm := CylinderMesh.new()
	cm.height = (FRAME_IN - 0.01) * 2.0
	cm.radial_segments = 12
	cm.rings = 1
	var r_mid := float(lb.get("radius", 0.0))
	if lb.kind == "arc" and r_mid > 0.0:
		# Narrower at the inside of the bend.
		var ratio := (r_mid - FRAME_IN) / (r_mid + FRAME_IN)
		var outer_is_top := float(lb.sx) > 0.0
		cm.top_radius = ROLLER_R if outer_is_top else ROLLER_R * (0.75 + 0.25 * ratio)
		cm.bottom_radius = ROLLER_R * (0.75 + 0.25 * ratio) if outer_is_top else ROLLER_R
	else:
		cm.top_radius = ROLLER_R
		cm.bottom_radius = ROLLER_R
	cm.material = _mat("roller")
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.mesh = cm
	mm.instance_count = n
	for i in n:
		var s := (i + 0.5) * length / n
		var pp := Automation.path_point(lb, s)
		# Cylinder axis (+Y) across the belt (+X of the path frame).
		var b := Basis(pp.basis.y, -pp.basis.x, pp.basis.z)
		mm.set_instance_transform(i, Transform3D(b, pp.origin + Vector3(0, ROLLER_Y, 0)))
	var mmi := MultiMeshInstance3D.new()
	mmi.multimesh = mm
	parent.add_child(mmi)
	# Legs: a pair at each end and every ~1.2 m between, with a cross brace and adjustable feet.
	var legs := maxi(int(round(length / 1.2)), 1) + 1
	for i in legs:
		var s := clampf(length * i / (legs - 1), 0.12, length - 0.12)
		var pp := Automation.path_point(lb, s)
		for side in [-1.0, 1.0]:
			var leg := MeshInstance3D.new()
			var bm := BoxMesh.new()
			bm.size = Vector3(0.065, FRAME_LO - 0.1, 0.065)
			leg.mesh = bm
			leg.material_override = _mat("leg")
			leg.transform = Transform3D(pp.basis, pp.origin + pp.basis.x * side * 0.38 + Vector3(0, 0.1 + (FRAME_LO - 0.1) * 0.5, 0))
			# Adjustable foot: threaded stem and a pad.
			var stem := MeshInstance3D.new()
			var sm := CylinderMesh.new()
			sm.top_radius = 0.014
			sm.bottom_radius = 0.014
			sm.height = 0.07
			stem.mesh = sm
			stem.material_override = _mat("foot")
			stem.position = pp.origin + pp.basis.x * side * 0.38 + Vector3(0, 0.065, 0)
			parent.add_child(stem)
			parent.add_child(leg)
			var foot := MeshInstance3D.new()
			var fm := CylinderMesh.new()
			fm.top_radius = 0.03
			fm.bottom_radius = 0.045
			fm.height = 0.03
			foot.mesh = fm
			foot.material_override = _mat("foot")
			foot.position = pp.origin + pp.basis.x * side * 0.38 + Vector3(0, 0.015, 0)
			parent.add_child(foot)
		var brace := MeshInstance3D.new()
		var br := BoxMesh.new()
		br.size = Vector3(0.76, 0.035, 0.035)
		brace.mesh = br
		brace.material_override = _mat("leg")
		brace.transform = Transform3D(pp.basis, pp.origin + Vector3(0, 0.3, 0))
		parent.add_child(brace)
	# Drive unit under the middle.
	var mid := Automation.path_point(lb, length * 0.5)
	var motor := MeshInstance3D.new()
	var mb := BoxMesh.new()
	mb.size = Vector3(0.16, 0.14, 0.2)
	motor.mesh = mb
	motor.material_override = _mat("motor")
	motor.transform = Transform3D(mid.basis, mid.origin + mid.basis.x * 0.2 + Vector3(0, 0.48, 0))
	parent.add_child(motor)


static func _sweep(st: SurfaceTool, lb: Dictionary, segs: int, x0: float, x1: float, y0: float, y1: float) -> void:
	## A closed rectangular section [x0..x1] × [y0..y1] swept along the path (with end caps).
	var length: float = lb.length
	var rings: Array = []
	for i in segs + 1:
		var pp := Automation.path_point(lb, length * i / segs)
		var a := pp.origin + pp.basis.x * x0
		var b := pp.origin + pp.basis.x * x1
		rings.append([a + Vector3(0, y0, 0), b + Vector3(0, y0, 0), b + Vector3(0, y1, 0), a + Vector3(0, y1, 0), pp.basis])
	for i in segs:
		var r0: Array = rings[i]
		var r1: Array = rings[i + 1]
		for k in 4:
			var k2 := (k + 1) % 4
			_quad(st, r0[k], r0[k2], r1[k2], r1[k])
	_quad(st, rings[0][0], rings[0][3], rings[0][2], rings[0][1])
	var last: Array = rings[segs]
	_quad(st, last[0], last[1], last[2], last[3])


static func _quad(st: SurfaceTool, a: Vector3, b: Vector3, c: Vector3, d: Vector3) -> void:
	var n := (b - a).cross(c - a).normalized()
	for v in [a, c, b, a, d, c]:
		st.set_normal(-n)
		st.add_vertex(v)


static func _add(parent: Node3D, st: SurfaceTool, mat: Material) -> void:
	var mi := MeshInstance3D.new()
	mi.mesh = st.commit()
	mi.material_override = mat
	# Winding depends on the sweep direction; draw both faces so the channel always reads solid.
	(mat as BaseMaterial3D).cull_mode = BaseMaterial3D.CULL_DISABLED
	parent.add_child(mi)


const CHEVRON_SHADER := preload("res://shaders/chevron.gdshader")

static var _arrow: ArrayMesh
static var _trim_mats := {}


static func chevrons(lb: Dictionary, parent: Node3D, spacing: float = 0.55) -> ShaderMaterial:
	## Flow arrows on both yellow flanges, pointing the way kit travels. Returns their material so the conveyor
	## can tint them for its speed tier and run a pulse along them while it moves.
	var length: float = lb.length
	var mat := ShaderMaterial.new()
	mat.shader = CHEVRON_SHADER
	if _arrow == null:
		_arrow = _arrow_mesh()
	var n := maxi(int(length / spacing), 1)
	for i in n:
		var s := (i + 0.5) * length / n
		var pp := Automation.path_point(lb, s)
		for side in [-1.0, 1.0]:
			var a := MeshInstance3D.new()
			a.mesh = _arrow
			a.material_override = mat
			a.transform = Transform3D(pp.basis, pp.origin + pp.basis.x * side * (FRAME_IN + FRAME_OUT) * 0.5 + Vector3(0, FRAME_HI + CAP + 0.0015, 0))
			a.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
			a.set_instance_shader_parameter("chev_s", s)
			parent.add_child(a)
	return mat


static func tier_trim(lb: Dictionary, parent: Node3D, col: Color) -> void:
	## Speed-tier livery: a coloured line along the outer edge of both flanges and a band down the outside of
	## each side channel, the full length of the piece (curves and bends included), so a run's tier reads at a
	## glance from anywhere.
	var key := col.to_html()
	if not _trim_mats.has(key):
		var m := StandardMaterial3D.new()
		m.albedo_color = col
		m.roughness = 0.4
		m.emission_enabled = true
		m.emission = col
		m.emission_energy_multiplier = 0.45
		m.cull_mode = BaseMaterial3D.CULL_DISABLED
		_trim_mats[key] = m
	var mat: Material = _trim_mats[key]
	var segs := maxi(int(ceil(float(lb.length) / 0.08)), 2) if lb.kind == "arc" else 1
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	for side in [-1.0, 1.0]:
		_sweep(st, lb, segs, side * (FRAME_OUT - 0.028), side * (FRAME_OUT + 0.006), FRAME_HI + CAP + 0.0004, FRAME_HI + CAP + 0.0026)
		_sweep(st, lb, segs, side * (FRAME_OUT + 0.006), side * (FRAME_OUT + 0.012), FRAME_LO + 0.05, FRAME_LO + 0.095)
	var mi := MeshInstance3D.new()
	mi.mesh = st.commit()
	mi.material_override = mat
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	parent.add_child(mi)


static func _arrow_mesh() -> ArrayMesh:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	# A flat chevron pointing +Z, 9 cm wide.
	var pts := [Vector3(-0.045, 0, -0.03), Vector3(0, 0, 0.03), Vector3(0.045, 0, -0.03), Vector3(0.045, 0, -0.005), Vector3(0, 0, 0.055), Vector3(-0.045, 0, -0.005)]
	for tri in [[0, 1, 5], [5, 1, 4], [1, 2, 3], [1, 3, 4]]:
		for k in [0, 2, 1]:
			st.set_normal(Vector3.UP)
			st.add_vertex(pts[tri[k]])
	return st.commit()
