extends Node3D
## First-person arms: the player's own sleeves and hands on whatever is held, and fists when nothing is.
##
## The arms are the character's arms: the same mesh and the same shader as every body in the world (so skin,
## sleeves, jacket and lighting match the people you meet), cut down to the two arms and posed here, every
## frame, by two-bone IK from a shoulder below the view to a grip on the held model. The pose goes to the shader
## in a little clip texture of its own (see `clip_size` in characters/body.gdshader).
##
## A game module (see MODULES in game.gd): `game` is set by the loader; everything else is found from it.
## Public knobs, all in camera space (metres): `shoulder_r`, `shoulder_l`, `hand_scale`, and `show` to hide them.

const BodiesScript := preload("res://characters/bodies.gd")
const UAL := 5; const FAL := 6; const HNL := 7; const UAR := 8; const FAR := 9; const HNR := 10
const L_UP := 0.29           # shoulder to elbow, elbow to wrist: the body's own bone lengths
const L_FORE := 0.255
const HOOK := 0.108          # from the wrist to the hook of the curled fingers, where a handle sits
const PALM := 0.03           # and that hook lies this far to the palm side of the wrist
# what is worn on the torso -> [sleeve colour, outfit]; anything else is a plain long-sleeved top
const TORSO := {"hoodie": [0x56606b, 0], "leather_jacket": [0x2b211b, 1], "police_vest": [0x27324a, 0], "raincoat": [0xb99a2c, 1], "tshirt": [0x6d7a86, 3], "flannel": [0x7a2e2a, 0], "jacket": [0x3d4a3a, 1]}

var game
var show := true
var debug := false
var shoulder_r := Vector3(0.21, -0.36, 0.02)
var shoulder_l := Vector3(-0.21, -0.36, 0.02)
var hand_scale := 1.0
var bodies
var skin := 1
var seed := 4.2

var _mmi: MultiMeshInstance3D
var _mat: ShaderMaterial
var _clip: Image
var _clip_tex: ImageTexture
var _row: Image
var _row_tex: ImageTexture
var _own_bodies := false
var _torso := "?"
var _t := 0.0
# eased state
var _two := 0.0          # 0 one hand on it, 1 both
var _kind := 0           # 0 empty, 1 firearm, 2 melee, 3 other thing
var _blend := 0.0
var _rw := Vector3.ZERO; var _lw := Vector3.ZERO
var _rb := Basis.IDENTITY; var _lb := Basis.IDENTITY
var _have := false
var _punch_side := 1.0
var _pose_r := -1
var _pose_l := -1
var _last_swing := 0.0

func mod_started(_new_game: bool) -> void:
	_torso = "?"

func _ready() -> void:
	set_process(true)

func _build() -> bool:
	var vm = game.vm if game != null else null
	if vm == null:
		return false
	bodies = game.zombies.bodies if (game.get("zombies") != null and game.zombies != null and game.zombies.get("bodies") != null) else null
	if bodies == null:
		bodies = BodiesScript.new(); bodies.name = "ArmBodies"; add_child(bodies); _own_bodies = true
	bodies._load()
	# the two arms out of the body mesh: every triangle that hangs on an arm bone
	var meta: Dictionary = JSON.parse_string(FileAccess.get_file_as_string(BodiesScript.DATA_DIR + "body.json"))
	_piv = []
	for k in range(0, meta.piv.size(), 3): _piv.append(Vector3(meta.piv[k], meta.piv[k + 1], meta.piv[k + 2]))
	var nv := int(meta.mesh.verts); var ni := int(meta.mesh.indices)
	var buf := FileAccess.get_file_as_bytes(BodiesScript.DATA_DIR + "body_mesh.bin")
	var pos := buf.slice(0, nv * 12).to_float32_array()
	var nrm := buf.slice(nv * 12, nv * 24).to_float32_array()
	var cus := buf.slice(nv * 24, nv * 40).to_float32_array()
	var idx := buf.slice(nv * 40, nv * 40 + ni * 4).to_int32_array()
	var remap := {}
	var vp := PackedVector3Array(); var vn := PackedVector3Array(); var vc := PackedFloat32Array(); var vi := PackedInt32Array()
	var hand_tag := [0.0, 0.0]            # the mitt's region + feature word, per side: the new hands carry the same
	for t in range(0, ni, 3):
		var ok := true
		for k in 3:
			var v0 := idx[t + k]
			var b := int(cus[v0 * 4] + 0.5)
			if b < UAL or b > HNR: ok = false
			# the body's hand is a mitt, fine at ten paces; in the player's own view the hands are built below
			if b == HNL or b == HNR:
				ok = false
				if (int(cus[v0 * 4 + 3] + 0.5) & 63) == 3: hand_tag[0 if b == HNL else 1] = cus[v0 * 4 + 3]
		if not ok:
			continue
		for k in 3:
			var v := idx[t + k]
			if not remap.has(v):
				remap[v] = vp.size()
				vp.append(Vector3(pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2])); vn.append(Vector3(nrm[v * 3], nrm[v * 3 + 1], nrm[v * 3 + 2]))
				for c in 4: vc.append(cus[v * 4 + c])
			vi.append(remap[v])
	for side in 2:
		for pose in POSES.size():
			_hand(vp, vn, vc, vi, side == 1, pose, hand_tag[1 if side == 1 else 0])
	var arrays := []
	arrays.resize(Mesh.ARRAY_MAX)
	arrays[Mesh.ARRAY_VERTEX] = vp; arrays[Mesh.ARRAY_NORMAL] = vn; arrays[Mesh.ARRAY_CUSTOM0] = vc; arrays[Mesh.ARRAY_INDEX] = vi
	var mesh := ArrayMesh.new()
	mesh.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, arrays, [], {}, Mesh.ARRAY_CUSTOM_RGBA_FLOAT << Mesh.ARRAY_FORMAT_CUSTOM0_SHIFT)
	mesh.custom_aabb = AABB(Vector3(-3, -3, -3), Vector3(6, 6, 6))
	# the body's material with a pose texture of our own: 36 texels a row, one row pair per arm
	_clip = Image.create(BodiesScript.TEX_W, 4, false, Image.FORMAT_RGBAH)          # half floats: filterable on every renderer
	_clip_tex = ImageTexture.create_from_image(_clip)
	_row = Image.create(BodiesScript.TEX_W, 2, false, Image.FORMAT_RGBAF)
	_row_tex = ImageTexture.create_from_image(_row)
	_mat = bodies.material.duplicate()
	_mat.set_shader_parameter(&"clip_tex", _clip_tex)
	_mat.set_shader_parameter(&"clip_size", Vector2(BodiesScript.TEX_W, 4))
	_mat.set_shader_parameter(&"body_tex", _row_tex)
	mesh.surface_set_material(0, _mat)
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D; mm.use_custom_data = true
	mm.mesh = mesh; mm.instance_count = 2
	for i in 2:
		mm.set_instance_transform(i, Transform3D.IDENTITY)
		mm.set_instance_custom_data(i, Color(float(i), seed, 0.0, 0.0))
	mm.custom_aabb = AABB(Vector3(-3, -3, -3), Vector3(6, 6, 6))
	_mmi = MultiMeshInstance3D.new()
	_mmi.multimesh = mm
	_mmi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF          # no shadow of floating arms in the world
	_mmi.name = "Arms"
	vm.add_child(_mmi)
	if debug and OS.get_cmdline_user_args().has("--plain"):
		var mt := StandardMaterial3D.new(); mt.albedo_color = Color(1, 0.5, 0.2); mt.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED; mt.cull_mode = BaseMaterial3D.CULL_DISABLED
		_mmi.material_override = mt
		for i in 2: mm.set_instance_transform(i, Transform3D(Basis.IDENTITY, Vector3(0, -1.25, -0.7)))
	return true

# ------------------------------------------------------------------ hands with fingers
# Each hand is built once per pose and hung on a bone the arms do not use; the pose in use is the one whose bone
# is given the wrist's transform, the rest are shrunk to nothing.
#   pose: [index, middle, ring, little] each [knuckle, middle joint, end joint] in radians, then the thumb's
#   three directions (palm side x, toward fingers -y, toward the index side z)
const POSES := [
	# grip: closed round a handle that lies across the palm
	[[1.05, 1.45, 0.75], [1.1, 1.5, 0.8], [1.15, 1.5, 0.8], [1.2, 1.5, 0.85], [Vector3(0.75, -0.45, 0.5), Vector3(0.9, -0.3, -0.3), Vector3(0.6, -0.2, -0.75)]],
	# pistol: the same, the index finger laid forward along the frame, off the trigger
	[[0.25, 0.3, 0.2], [1.1, 1.5, 0.8], [1.15, 1.5, 0.8], [1.2, 1.5, 0.85], [Vector3(0.6, -0.55, 0.55), Vector3(0.75, -0.6, -0.1), Vector3(0.5, -0.8, -0.3)]],
	# fist
	[[1.4, 1.75, 1.0], [1.45, 1.75, 1.0], [1.45, 1.75, 1.0], [1.5, 1.75, 1.0], [Vector3(0.8, -0.5, 0.35), Vector3(0.95, -0.25, -0.2), Vector3(0.7, -0.5, -0.55)]],
	# open: a flat hand, fingers a little spread, to shove with
	[[0.12, 0.15, 0.1], [0.08, 0.12, 0.1], [0.1, 0.14, 0.1], [0.16, 0.18, 0.12], [Vector3(0.25, -0.45, 0.85), Vector3(0.15, -0.6, 0.78), Vector3(0.1, -0.75, 0.65)]],
	# support: cupped under a fore-end
	[[0.7, 0.95, 0.5], [0.75, 1.0, 0.5], [0.8, 1.0, 0.55], [0.85, 1.0, 0.6], [Vector3(0.55, -0.4, 0.72), Vector3(0.75, -0.55, 0.3), Vector3(0.7, -0.7, -0.1)]],
]
const POSE_BONE_R := [11, 12, 13, 14, 15]
const POSE_BONE_L := [16, 0, 1, 3, 4]
func _pivot_of(b: int) -> Vector3:
	return _piv[b]

var _piv: Array = []

func _hand(vp: PackedVector3Array, vn: PackedVector3Array, vc: PackedFloat32Array, vi: PackedInt32Array, right: bool, pose: int, tag: float) -> void:
	var bone: int = POSE_BONE_R[pose] if right else POSE_BONE_L[pose]
	var org := _pivot_of(bone)
	var P: Array = POSES[pose]
	var base := vp.size()
	var pts := PackedVector3Array(); var nrs := PackedVector3Array(); var tri := PackedInt32Array()
	# palm: a rounded slab from inside the cuff down to the knuckles
	var ring_n := 16
	var rows := [[0.04, 0.02, 0.025, 0.0], [0.018, 0.017, 0.0255, 0.0], [0.0, 0.0145, 0.027, -0.001], [-0.016, 0.0148, 0.032, -0.002], [-0.034, 0.015, 0.037, -0.003], [-0.052, 0.0145, 0.0405, -0.004], [-0.07, 0.0135, 0.0412, -0.004], [-0.086, 0.0115, 0.04, -0.004], [-0.094, 0.007, 0.034, -0.004]]
	for r in rows.size():
		var e: Array = rows[r]
		for k in ring_n:
			var a := TAU * k / ring_n
			var cx := cos(a); var sz := sin(a)
			# a squarish section: flat palm and back, round edges
			var ex := signf(cx) * pow(absf(cx), 0.7) * float(e[1]); var ez := signf(sz) * pow(absf(sz), 0.8) * float(e[2])
			pts.append(Vector3(ex, float(e[0]), ez + float(e[3])))
			nrs.append(Vector3(cx / float(e[1]), 0.0, sz / float(e[2])).normalized())
	for r in rows.size() - 1:
		for k in ring_n:
			var a0 := r * ring_n + k; var a1 := r * ring_n + (k + 1) % ring_n
			var b0 := a0 + ring_n; var b1 := a1 + ring_n
			tri.append_array([a0, a1, b0, a1, b1, b0])
	var cap := pts.size()
	pts.append(Vector3(0.0, -0.096, -0.004)); nrs.append(Vector3(0, -1, 0))
	for k in ring_n:
		tri.append_array([(rows.size() - 1) * ring_n + k, (rows.size() - 1) * ring_n + (k + 1) % ring_n, cap])
	# fingers
	var fz := [0.0275, 0.0095, -0.0085, -0.0255]
	var fl := [[0.043, 0.026, 0.021], [0.047, 0.029, 0.022], [0.043, 0.027, 0.021], [0.034, 0.02, 0.019]]
	var fr := [0.0088, 0.0092, 0.0086, 0.0076]
	for f in 4:
		var ang := 0.0
		var p := Vector3(0.001, -0.086 + absf(f - 1.2) * 0.003, float(fz[f]))
		var path: Array = [p]
		var spread := (1.5 - f) * (0.05 if pose == 3 else 0.012)
		for j in 3:
			ang += float(P[f][j])
			p += Vector3(sin(ang), -cos(ang), spread).normalized() * float(fl[f][j])
			path.append(p)
		_tube(pts, nrs, tri, path, [float(fr[f]), float(fr[f]) * 0.95, float(fr[f]) * 0.88, float(fr[f]) * 0.74], Vector3(0, 0, 1))
	# thumb
	var tp := Vector3(0.006, -0.022, 0.03)
	var tpath: Array = [tp]
	var tl := [0.04, 0.032, 0.026]
	for j in 3:
		tp += (P[4][j] as Vector3).normalized() * float(tl[j])
		tpath.append(tp)
	_tube(pts, nrs, tri, tpath, [0.013, 0.0108, 0.0098, 0.0082], Vector3(1, 0, 0))
	# into the body's own space, hung on this pose's bone
	var sx := 1.0 if right else -1.0
	for i in pts.size():
		var q := pts[i] * hand_scale
		vp.append(org + Vector3(q.x * sx, q.y, q.z))
		vn.append(Vector3(nrs[i].x * sx, nrs[i].y, nrs[i].z))
		vc.append(float(bone)); vc.append(float(bone)); vc.append(0.0); vc.append(tag)
	for t in range(0, tri.size(), 3):
		if right: vi.append_array([base + tri[t], base + tri[t + 2], base + tri[t + 1]])
		else: vi.append_array([base + tri[t], base + tri[t + 1], base + tri[t + 2]])

## a finger: a tube along `path`, rounded at the tip; `side` is any direction across it
func _tube(pts: PackedVector3Array, nrs: PackedVector3Array, tri: PackedInt32Array, path: Array, radii: Array, side: Vector3) -> void:
	var n := 7
	var first := pts.size()
	var rings := 0
	for i in path.size():
		var here: Vector3 = path[i]
		var d_in: Vector3 = (here - path[i - 1]).normalized() if i > 0 else (path[1] - here).normalized()
		var d_out: Vector3 = (path[i + 1] - here).normalized() if i < path.size() - 1 else d_in
		# two rings at each joint, so the knuckle is a bend and not a pinch
		for half in ([0] if (i == 0 or i == path.size() - 1) else [-1, 1]):
			var t: Vector3 = d_in if half < 0 else (d_out if half > 0 else (d_in + d_out).normalized())
			var c: Vector3 = here + (d_in * -0.35 if half < 0 else (d_out * 0.35 if half > 0 else Vector3.ZERO)) * float(radii[i])
			var u := side - t * side.dot(t)
			u = u.normalized() if u.length() > 1e-4 else t.cross(Vector3.UP).normalized()
			var w := t.cross(u)
			var rr := float(radii[i]) * (1.06 if half != 0 else 1.0)
			for k in n:
				var a := TAU * k / n
				var nr := u * cos(a) + w * sin(a)
				pts.append(c + nr * rr); nrs.append(nr)
			rings += 1
	for r in rings - 1:
		for k in n:
			var a0 := first + r * n + k; var a1 := first + r * n + (k + 1) % n
			tri.append_array([a0, a1, a0 + n, a1, a1 + n, a0 + n])
	var last: Vector3 = path[path.size() - 1]
	var dir: Vector3 = (last - path[path.size() - 2]).normalized()
	var tip := pts.size()
	pts.append(last + dir * float(radii[radii.size() - 1]) * 0.9); nrs.append(dir)
	for k in n:
		tri.append_array([first + (rings - 1) * n + k, first + (rings - 1) * n + (k + 1) % n, tip])

## the player's look: skin from the character, sleeves from what is worn
func _relook() -> void:
	var worn = null
	var p = game.get("player")
	if p != null and p.get("worn") != null and p.worn.get("torso") != null:
		worn = p.worn.torso
	var id := String(worn.id) if worn != null else ""
	if id == _torso:
		return
	_torso = id
	var look := {"skin": BodiesScript.H_SKIN[skin % BodiesScript.H_SKIN.size()], "build": 1.0, "height": 1.0, "gear": 0}
	if id == "":
		look["shirt"] = 0x6d7a86; look["outfit"] = 3            # nothing over it: a tee, bare forearms
	else:
		var e: Array = TORSO.get(id, [0x5b6470, 0])
		look["shirt"] = int(e[0]); look["outfit"] = int(e[1]); look["jacket"] = int(e[0])
	var tmp: int = bodies.add_body(BodiesScript.KIND_HUMAN, seed, look)
	if tmp < 0:
		return
	var px: Array = bodies.row_pixels(tmp)
	bodies.remove_body(tmp)
	for arm in 2:
		for k in BodiesScript.TEX_W:
			_row.set_pixel(k, arm, px[k])
		# this instance draws one arm; the other one's bones are shrunk to nothing
		for b in ([UAL, FAL, HNL] if arm == 0 else [UAR, FAR, HNR]) + POSE_BONE_R + POSE_BONE_L:
			_row.set_pixel(BodiesScript.SCALE0 + b, arm, Color(0, 0, 0, 1))
	_pose_r = -1; _pose_l = -1
	for arm in 2:
		var a0 := BodiesScript.ANIM0
		_row.set_pixel(a0, arm, Color(0.0, float(arm * 2), 1.0, 0.0))          # clip: column 0, row 0 or 2, one frame, still
		_row.set_pixel(a0 + 1, arm, Color(0, 0, 0, 0))
		_row.set_pixel(a0 + 2, arm, Color(0.0, float(arm * 2), 1.0, 0.0))
		_row.set_pixel(a0 + 3, arm, Color(0, 0, 0, 0))
		_row.set_pixel(a0 + 4, arm, Color(0, 0, 0, 0))
		_row.set_pixel(a0 + 5, arm, Color(0, 0, 0, 0))
	_row_tex.update(_row)

func _process(dt: float) -> void:
	if game == null or game.get("vm") == null or game.vm == null:
		return
	if _mmi == null:
		if not _build():
			return
	var vm = game.vm
	var playing: bool = String(game.get("state")) == "play" if game.get("state") != null else true
	_mmi.visible = show and playing and vm.use_t <= 0.0
	if not _mmi.visible:
		_have = false
		return
	_relook()
	_t += dt
	_mat.set_shader_parameter(&"night", bodies.night)
	_mat.set_shader_parameter(&"u_time", _t)
	# ---- where the hands go, in camera space
	var H: Transform3D = vm.holder.transform
	var kind := 0
	if vm.model != null:
		kind = 1 if vm.firearm else (2 if vm.current != "flashlight" and Items.def(vm.current).has("weapon") else 3)
	var long_gun: bool = kind == 1 and vm.current != "pistol" and String(Items.def(vm.current).weapon.get("model", "")) != "pistol"
	var two: bool = long_gun or (kind == 2 and vm.two_handed)
	_two = lerpf(_two, 1.0 if two else 0.0, 1.0 - exp(-dt * 10.0))
	var rw := Vector3.ZERO; var lw := Vector3.ZERO
	var rb := Basis.IDENTITY; var lb := Basis.IDENTITY
	var pose_r := 0; var pose_l := 0           # indices into POSES: 0 grip, 1 pistol, 2 fist, 3 open, 4 support
	# a right hand closed round something upright in front of it: fingers forward, thumb up, palm to the left
	var grip_r := Basis(Vector3(-1, 0, 0), Vector3(0, 0, 1), Vector3(0, 1, 0))
	var grip_l := Basis(Vector3(-1, 0, 0), Vector3(0, 0, 1), Vector3(0, 1, 0)).scaled(Vector3(1, 1, 1))
	if kind == 0:
		# fists, low in the corners of the view; a swing throws one forward, a shove both
		var guard_r := Vector3(0.2, -0.27, -0.4); var guard_l := Vector3(-0.2, -0.28, -0.38)
		var bob := sin(_t * 1.7) * 0.004
		var sw := 0.0
		if vm.swing_dur > 0.0 and vm.swing_t > 0.0:
			if vm.swing_t > _last_swing: _punch_side = -_punch_side
			var u: float = 1.0 - vm.swing_t / vm.swing_dur
			sw = sin(clampf(u / 0.45, 0.0, 1.0) * PI * 0.5) * (1.0 - smoothstep(0.5, 1.0, u))
		_last_swing = vm.swing_t
		var both: bool = vm.swing_dur > 0.0 and vm.swing_dur < 0.4            # the quick one is a shove
		var jr := sw if (both or _punch_side > 0.0) else -0.15 * sw
		var jl := sw if (both or _punch_side < 0.0) else -0.15 * sw
		rw = guard_r + Vector3(-0.1 * jr, 0.1 * jr + bob, -0.3 * jr)
		lw = guard_l + Vector3(0.1 * jl, 0.1 * jl - bob, -0.3 * jl)
		# fists: knuckles forward and up a little, backs of the hands turned up and out, thumbs inside
		rb = Basis(Vector3(0, 1, 0), 0.3) * Basis(Vector3(1, 0, 0), 0.45 - 0.4 * jr) * Basis(Vector3(0, 0, 1), 1.0 + 0.35 * jr) * grip_r
		lb = Basis(Vector3(0, 1, 0), -0.3) * Basis(Vector3(1, 0, 0), 0.45 - 0.4 * jl) * Basis(Vector3(0, 0, 1), -1.0 - 0.35 * jl) * grip_r
		pose_r = 3 if both else 2; pose_l = pose_r
		if both:
			# a shove: open hands, palms forward, fingers up
			rb = Basis(Vector3(0, 1, 0), 0.15) * Basis(Vector3(1, 0, 0), 1.25 + 0.2 * sw) * Basis(Vector3(0, 0, 1), 1.45) * grip_r
			lb = Basis(Vector3(0, 1, 0), -0.15) * Basis(Vector3(1, 0, 0), 1.25 + 0.2 * sw) * Basis(Vector3(0, 0, 1), -1.45) * grip_r
			rw += Vector3(-0.04, 0.05, 0.0) * sw; lw += Vector3(0.04, 0.05, 0.0) * sw
	else:
		var grip := Vector3(0.0, 0.0, 0.0)             # where the right hand closes, in the held model's space
		var fore := Vector3(0.0, 0.0, -0.3)
		var hb := H.basis
		if kind == 1:
			grip = Vector3(0.0, -0.022, 0.012) if not long_gun else Vector3(0.0, -0.045, 0.05)
			var an = vm._anchors.get(String(Items.def(vm.current).weapon.model), {}).get("anchors", {}).get("foreGrip")
			fore = Vector3(an[0], an[1], an[2]) + Vector3(0, -0.02, 0.04) if an != null else Vector3(0, -0.02, -0.3)
			rb = hb * Basis(Vector3(1, 0, 0), -0.22) * grip_r                 # the wrist drops a little behind a pistol grip
			pose_r = 1; pose_l = 4
			# the left hand under the fore-end: palm up, fingers round it from below, thumb along the barrel
			lb = hb * Basis(Vector3(0, -1, 0), Vector3(-1, 0, 0), Vector3(0, 0, -1)) * Basis(Vector3(0, 0, 1), 0.35)
		else:
			# a handle standing up through the fist; the second hand below the first
			grip = Vector3(0.0, 0.03 if kind == 2 else 0.0, 0.0)
			fore = Vector3(0.0, -0.075, 0.0)
			rb = hb * grip_r
			lb = hb * _mirror(grip_r)
			if kind == 3:
				rb = hb * Basis(Vector3(0, 0, 1), 0.2) * grip_r
		rw = H * grip - rb * Vector3(PALM, -HOOK, 0.0)
		if two:
			lw = H * fore - lb * Vector3(-PALM, -HOOK, 0.0)
		else:
			pose_l = 2
			# the free hand hangs low and out of the way, just in view when you look down
			lw = Vector3(-0.24, -0.42, -0.3) + Vector3(0, sin(_t * 1.3) * 0.004, 0)
			lb = Basis(Vector3(0, 1, 0), 0.3) * Basis(Vector3(1, 0, 0), 0.7) * Basis(Vector3(0, 0, 1), -0.4) * _mirror(grip_r)
	# ---- ease between grips when the thing in hand changes (never while it is simply moving)
	if not _have or kind != _kind:
		if not _have:
			_rw = rw; _lw = lw; _rb = rb; _lb = lb
		_blend = 0.0; _kind = kind; _have = true
	_blend = minf(1.0, _blend + dt * 6.0)
	if _blend < 1.0:
		var k := 1.0 - exp(-dt * 14.0)
		_rw = _rw.lerp(rw, k); _lw = _lw.lerp(lw, k)
		_rb = Basis(_rb.get_rotation_quaternion().slerp(rb.get_rotation_quaternion(), k)); _lb = Basis(_lb.get_rotation_quaternion().slerp(lb.get_rotation_quaternion(), k))
	else:
		# the left hand glides to and from a second grip
		_rw = rw; _rb = rb
		var kl := 1.0 if two and _two > 0.98 else 1.0 - exp(-dt * 12.0)
		_lw = _lw.lerp(lw, kl); _lb = Basis(_lb.get_rotation_quaternion().slerp(lb.get_rotation_quaternion(), kl))
	if debug and Engine.get_process_frames() % 20 == 0:
		print("arms: tris ", _mmi.multimesh.mesh.surface_get_arrays(0)[Mesh.ARRAY_INDEX].size() / 3, " kind ", kind, " rw ", _rw, " lw ", _lw, " holder ", H.origin, " row0 ", _row.get_pixel(0, 0), " scaleUAR ", _row.get_pixel(18, 0), " vis ", _mmi.is_visible_in_tree())
	_solve(1, shoulder_r, _rw, _rb, Vector3(0.55, -0.8, 0.25))
	_solve(0, shoulder_l, _lw, _lb, Vector3(-0.55, -0.8, 0.25))
	# which hands: the pose in use gets the wrist's transform, the others stay shrunk away
	if pose_r != _pose_r or pose_l != _pose_l:
		for k in POSES.size():
			_row.set_pixel(BodiesScript.SCALE0 + int(POSE_BONE_R[k]), 0, Color(1, 1, 1, 1) if k == pose_r else Color(0, 0, 0, 1))
			_row.set_pixel(BodiesScript.SCALE0 + int(POSE_BONE_L[k]), 1, Color(1, 1, 1, 1) if k == pose_l else Color(0, 0, 0, 1))
		_pose_r = pose_r; _pose_l = pose_l
		_row_tex.update(_row)
	_bone(1, int(POSE_BONE_R[pose_r]), _rb.orthonormalized(), _rw)
	_bone(0, int(POSE_BONE_L[pose_l]), _lb.orthonormalized(), _lw)
	_clip_tex.update(_clip)

## the same grip for the other hand: mirrored left to right
func _mirror(b: Basis) -> Basis:
	return Basis(Vector3(-b.x.x, b.x.y, b.x.z) * -1.0, Vector3(-b.y.x, b.y.y, b.y.z), Vector3(-b.z.x, b.z.y, b.z.z))

## Two-bone IK for one arm. `hand` is the hand bone's frame (columns: across the palm, from fingers to wrist,
## along the thumb) and `wrist` its position; the elbow swings toward `pole`. arm 1 = right, 0 = left.
func _solve(arm: int, shoulder: Vector3, wrist: Vector3, hand: Basis, pole: Vector3) -> void:
	var ua := UAR if arm == 1 else UAL; var fa := ua + 1; var hn := ua + 2
	var to := wrist - shoulder
	var d := to.length()
	var reach := L_UP + L_FORE - 0.004
	if d > reach:
		# farther than an arm reaches: the shoulder comes forward with it (it is out of sight below the view)
		shoulder = wrist - to / d * reach
		to = wrist - shoulder; d = reach
	d = maxf(d, 0.08)
	var dir := to / d
	var a := (L_UP * L_UP - L_FORE * L_FORE + d * d) / (2.0 * d)
	var h := sqrt(maxf(0.0, L_UP * L_UP - a * a))
	var side := (pole - dir * pole.dot(dir))
	side = side.normalized() if side.length() > 1e-4 else Vector3.DOWN
	var elbow := shoulder + dir * a + side * h
	# upper arm: its length from elbow up to shoulder is the bone's +Y; it bends toward the forearm (+Z)
	var uy := (shoulder - elbow).normalized()
	var fy := (elbow - wrist).normalized()
	var bend := (wrist - elbow) - uy * (wrist - elbow).dot(uy)
	var uz := bend.normalized() if bend.length() > 1e-4 else hand.z
	var ub := Basis(uy.cross(uz).normalized(), uy, uz).orthonormalized()
	# forearm: twisted half way to the hand, so the wrist does not candy-wrap
	var hz := hand.z - fy * hand.z.dot(fy)
	var fz0 := uz - fy * uz.dot(fy)
	var fz := (fz0.normalized() * 0.4 + (hz.normalized() if hz.length() > 1e-4 else fz0.normalized()) * 0.6).normalized()
	var fb := Basis(fy.cross(fz).normalized(), fy, fz).orthonormalized()
	if debug:
		for k in 3:
			var nm := "dbg%d%d" % [arm, k]
			var m: MeshInstance3D = game.vm.get_node_or_null(nm)
			if m == null:
				m = MeshInstance3D.new(); m.name = nm
				var sp := SphereMesh.new(); sp.radius = 0.02; sp.height = 0.04; m.mesh = sp
				var mt := StandardMaterial3D.new(); mt.albedo_color = Color(1, 0, 0) if k == 0 else Color(0, 1, 0) if k == 1 else Color(0, 0, 1); mt.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
				m.material_override = mt
				game.vm.add_child(m)
			m.position = wrist if k == 0 else elbow if k == 1 else shoulder
	_bone(arm, ua, ub, shoulder)
	_bone(arm, fa, fb, elbow)
	_bone(arm, hn, hand.orthonormalized(), wrist)
	# (the chest carries the top ring of the sleeve: it rides with the upper arm)
	_bone(arm, 2, ub, shoulder + ub * (Vector3(0.0, 1.27, -0.04) - Vector3(0.168 if arm == 0 else -0.168, 1.45, 0.0)))

func _bone(arm: int, b: int, basis: Basis, at: Vector3) -> void:
	var q := basis.get_rotation_quaternion()
	# (instance 0 draws the right arm from rows 0-1, instance 1 the left from rows 2-3)
	for r in 2:
		_clip.set_pixel(b * 2, (1 - arm) * 2 + r, Color(q.x, q.y, q.z, q.w))
		_clip.set_pixel(b * 2 + 1, (1 - arm) * 2 + r, Color(at.x, at.y, at.z, 1.0))
