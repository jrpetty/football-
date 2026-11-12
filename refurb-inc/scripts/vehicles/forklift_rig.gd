class_name ForkliftRig
extends Node3D
## Jon's counterbalance forklift, made to work: the single model is split (once, cached) into the truck and its
## fork carriage — backrest and tines — which rides up and down the mast; an inner mast stage extends when the
## forks go above free lift. Scaled to a realistic 1.5 t truck (Logistics.FL_SCALE). Origin = the model's origin
## on the ground, front (+Z) = the forks. Engine whine, hydraulics and reversing beeps come from how it moves,
## so it sounds right for the driver and for everyone watching. ForkPoint follows the forks (pallets ride it).

const MODEL_MESH := "res://assets/models/vehicles/forklift/mesh.res"
const MODEL_MAT := "res://assets/models/vehicles/forklift/material.tres"
const CUT_Z := 0.585          # model space: everything in front of the mast is the fork carriage
const FREE_LIFT := 0.85       # the carriage reaches the top of the fixed mast at this fork height
const MAST_X := 0.245

static var _body_mesh: Mesh
static var _fork_mesh: Mesh

var fork_h := 0.0
var driven := false
var _model: Node3D
var _carriage: Node3D
var _inner: Node3D
var _beacon: MeshInstance3D
var _fork_point: Node3D
var _motor: AudioStreamPlayer3D
var _lift: AudioStreamPlayer3D
var _beep: AudioStreamPlayer3D
var _last_pos := Vector3.INF
var _speed := 0.0
var _last_h := 0.0
var _lift_rate := 0.0
var _t := 0.0


func _ready() -> void:
	_split_model()
	_model = Node3D.new()
	_model.name = "Model"
	_model.scale = Vector3.ONE * Logistics.FL_SCALE
	add_child(_model)
	var mat: Material = load(MODEL_MAT) if ResourceLoader.exists(MODEL_MAT) else Mats.color(Color(0.9, 0.55, 0.1), 0.5)
	var body := MeshInstance3D.new()
	body.name = "Body"
	body.mesh = _body_mesh
	body.material_override = mat
	_model.add_child(body)
	_carriage = Node3D.new()
	_carriage.name = "Carriage"
	_model.add_child(_carriage)
	var forks := MeshInstance3D.new()
	forks.name = "Forks"
	forks.mesh = _fork_mesh
	forks.material_override = mat
	_carriage.add_child(forks)
	_build_inner_mast()
	_build_beacon()
	for mi: MeshInstance3D in find_children("*", "MeshInstance3D", true, false):
		mi.visibility_range_end = 70.0
	_fork_point = Node3D.new()
	_fork_point.name = "ForkPoint"
	add_child(_fork_point)
	_motor = Sfx.make_loop("forklift_motor", self, -14.0)
	_motor.position = Vector3(0, 0.6, -0.6)
	_lift = Sfx.make_loop("forklift_hydraulic", self, -12.0)
	_lift.position = Vector3(0, 1.0, 0.5)
	_beep = Sfx.make_loop("reverse_beep", self, -12.0)
	_beep.position = Vector3(0, 2.0, -1.4)
	_beep.max_distance = 30.0
	set_fork_height(0.0)


func fork_point() -> Node3D:
	return _fork_point


static func _split_model() -> void:
	## Cut the model in two by the mast: in front of it are the carriage and tines (they move), the rest is the truck.
	if _body_mesh != null:
		return
	if not ResourceLoader.exists(MODEL_MESH):
		var bm := BoxMesh.new()
		bm.size = Vector3(1.0, 1.9, 2.1)
		_body_mesh = bm
		var fm := BoxMesh.new()
		fm.size = Vector3(0.6, 0.05, 0.9)
		_fork_mesh = fm
		return
	var src: ArrayMesh = load(MODEL_MESH)
	var arr := src.surface_get_arrays(0)
	var verts: PackedVector3Array = arr[Mesh.ARRAY_VERTEX]
	var idx: PackedInt32Array = arr[Mesh.ARRAY_INDEX]
	var parts := [PackedInt32Array(), PackedInt32Array()]       # triangle indices: body, forks
	for t in range(0, idx.size(), 3):
		var cz := (verts[idx[t]].z + verts[idx[t + 1]].z + verts[idx[t + 2]].z) / 3.0
		var dst: int = 1 if cz > CUT_Z else 0
		parts[dst].append(idx[t])
		parts[dst].append(idx[t + 1])
		parts[dst].append(idx[t + 2])
	_body_mesh = _sub_mesh(arr, parts[0])
	_fork_mesh = _sub_mesh(arr, parts[1])


static func _sub_mesh(arr: Array, tri_idx: PackedInt32Array) -> ArrayMesh:
	## A mesh of just these triangles (vertices re-indexed, every attribute kept).
	var remap := {}
	var order := PackedInt32Array()
	var new_idx := PackedInt32Array()
	new_idx.resize(tri_idx.size())
	for i in tri_idx.size():
		var old := tri_idx[i]
		if not remap.has(old):
			remap[old] = order.size()
			order.append(old)
		new_idx[i] = remap[old]
	var out := []
	out.resize(Mesh.ARRAY_MAX)
	for a in [Mesh.ARRAY_VERTEX, Mesh.ARRAY_NORMAL, Mesh.ARRAY_TEX_UV, Mesh.ARRAY_TEX_UV2, Mesh.ARRAY_COLOR]:
		var src = arr[a]
		if src == null or src.size() == 0:
			continue
		var dst = src.duplicate()
		dst.resize(order.size())
		for i in order.size():
			dst[i] = src[order[i]]
		out[a] = dst
	var tan = arr[Mesh.ARRAY_TANGENT]
	if tan != null and tan.size() > 0:
		var t2 := PackedFloat32Array()
		t2.resize(order.size() * 4)
		for i in order.size():
			for k in 4:
				t2[i * 4 + k] = tan[order[i] * 4 + k]
		out[Mesh.ARRAY_TANGENT] = t2
	out[Mesh.ARRAY_INDEX] = new_idx
	var m := ArrayMesh.new()
	m.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, out)
	return m


func _build_inner_mast() -> void:
	## The inner stage that telescopes up out of the fixed mast for high lifts (model units).
	_inner = Node3D.new()
	_inner.name = "InnerMast"
	_model.add_child(_inner)
	var steel := Mats.color(Color(0.16, 0.16, 0.17), 0.45, 0.6)
	var bb := BoxBatch.new()
	for sx in [-1.0, 1.0]:
		bb.box(Vector3(0.055, 1.82, 0.075), Vector3(sx * MAST_X, 0.95, 0.575), steel)
		bb.cyl(0.045, 0.06, Transform3D(Basis(Vector3.BACK, PI * 0.5), Vector3(sx * (MAST_X - 0.05), 1.82, 0.6)), Mats.color(Color(0.5, 0.5, 0.52), 0.4, 0.8), 12)
	bb.box(Vector3(MAST_X * 2.0 + 0.055, 0.07, 0.07), Vector3(0, 1.84, 0.575), steel)
	bb.box(Vector3(MAST_X * 2.0 + 0.055, 0.05, 0.06), Vector3(0, 0.12, 0.575), steel)
	# Lift chains down to the carriage.
	for sx in [-1.0, 1.0]:
		bb.box(Vector3(0.025, 1.7, 0.012), Vector3(sx * (MAST_X - 0.05), 0.95, 0.64), Mats.color(Color(0.25, 0.25, 0.26), 0.5, 0.7))
	bb.commit(_inner, "Inner")


func _build_beacon() -> void:
	_beacon = MeshInstance3D.new()
	_beacon.name = "Beacon"
	var cm := CylinderMesh.new()
	cm.top_radius = 0.045
	cm.bottom_radius = 0.055
	cm.height = 0.09
	cm.radial_segments = 14
	_beacon.mesh = cm
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(1.0, 0.6, 0.1, 0.85)
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.emission_enabled = true
	m.emission = Color(1.0, 0.55, 0.08)
	m.emission_energy_multiplier = 0.0
	m.roughness = 0.2
	_beacon.material_override = m
	_beacon.position = Vector3(-0.32, 1.92, -0.95)
	_model.add_child(_beacon)


func set_fork_height(h: float) -> void:
	fork_h = clampf(h, 0.0, Logistics.FORK_MAX)
	var mh := fork_h / Logistics.FL_SCALE
	_carriage.position.y = mh
	_inner.position.y = maxf(0.0, mh - FREE_LIFT)
	_fork_point.position = Vector3(0, fork_h, 0)


func _process(delta: float) -> void:
	_t += delta
	# How it's moving (works the same for the driver and for spectators).
	var p := global_position
	if _last_pos != Vector3.INF and delta > 0.0:
		var v := (p - _last_pos) / delta
		var fwd := global_basis.z
		var along := v.dot(fwd)
		_speed = lerpf(_speed, along, clampf(delta * 6.0, 0.0, 1.0))
	_last_pos = p
	_lift_rate = lerpf(_lift_rate, absf(fork_h - _last_h) / maxf(delta, 0.001), clampf(delta * 10.0, 0.0, 1.0))
	_last_h = fork_h
	var on := driven
	(_beacon.material_override as StandardMaterial3D).emission_energy_multiplier = (3.0 * maxf(0.0, sin(_t * 9.0))) if on else 0.0
	if _motor.stream:
		if on and not _motor.playing:
			_motor.play()
		elif not on and _motor.playing:
			_motor.stop()
		_motor.pitch_scale = 0.75 + clampf(absf(_speed) / 3.2, 0.0, 1.0) * 0.7
		_motor.volume_db = -16.0 + clampf(absf(_speed) / 3.2, 0.0, 1.0) * 8.0
	var lifting := on and _lift_rate > 0.05
	if _lift.stream:
		if lifting and not _lift.playing:
			_lift.play()
		elif not lifting and _lift.playing:
			_lift.stop()
	var reversing := on and _speed < -0.12
	if _beep.stream:
		if reversing and not _beep.playing:
			_beep.play()
		elif not reversing and _beep.playing:
			_beep.stop()
