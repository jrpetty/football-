class_name CharacterRig
extends Node3D
## A person: players' avatars, AI staff, the lorry driver. Baked sculpted meshes (tools/bake_humans.gd) on a
## real skeleton — one seamless skinned body (male/female build), a modelled head with eyes that blink and
## look about, hands with fingers, soled shoes, six hairstyles, optional hi-vis vest and cap, and a photo
## face from the character menu. Animation is procedural: walk (driven by `move_speed`, m/s), idle
## breathing and weight shifts, carry, push, wave and a tea-drinking gesture (play_drink()).
## Faces +Z. Origin at the feet.

const DIR := "res://assets/models/people/"
const BASE_H := {"m": 1.78, "f": 1.68}

@export var body := "m"                 # "m" | "f"
@export var hair_style := "short"       # short | side | long | bun | buzz | curly | bald
@export var shirt := Color(0.2, 0.35, 0.6)
@export var trousers := Color(0.16, 0.17, 0.2)
@export var skin := Color(0.86, 0.67, 0.55)
@export var hair := Color(0.2, 0.14, 0.1)
@export var shoes := Color(0.1, 0.1, 0.11)
@export var eye_colour := Color(0.28, 0.4, 0.55)
@export var hi_vis := false
@export var cap := false
@export var big_head := false
@export var height := 1.78

var anim := "idle"
var move_speed := 0.0

var skeleton: Skeleton3D
var _bone := {}
var _rest := {}
var _head_root: Node3D
var _face: MeshInstance3D
var _lids: Array = []
var _eyes: Array = []
var _mug: Node3D
var _phase := 0.0
var _t := 0.0
var _drink_t := -1.0
var _blink_t := 2.0
var _look := Vector2.ZERO
var _look_target := Vector2.ZERO
var _look_t := 1.0
var _built := false
var _photo := false
var _face_tex: Texture2D = null

static var _skel_def := {}
static var _mesh_cache := {}
static var _default_face: ImageTexture


func _ready() -> void:
	_build()


func _mesh(name: String) -> Mesh:
	if not _mesh_cache.has(name):
		var path := DIR + name + ".res"
		_mesh_cache[name] = load(path) if ResourceLoader.exists(path) else null
	return _mesh_cache[name]


static func _skeleton_json() -> Dictionary:
	if _skel_def.is_empty() and FileAccess.file_exists(DIR + "skeleton.json"):
		_skel_def = JSON.parse_string(FileAccess.get_file_as_string(DIR + "skeleton.json"))
	return _skel_def


# ================================================================= build

func rebuild() -> void:
	## Re-make everything after the look changed.
	for c in get_children():
		c.queue_free()
		remove_child(c)
	_bone.clear()
	_rest.clear()
	_lids.clear()
	_eyes.clear()
	_mug = null
	_built = false
	_build()
	if _face_tex or _photo:
		set_face_texture(_face_tex, _photo)


func _build() -> void:
	if _built:
		return
	_built = true
	var b := body if body in ["m", "f"] else "m"
	scale = Vector3.ONE * (height / float(BASE_H[b]))
	var defs: Array = _skeleton_json().get(b, [])
	if defs.is_empty():
		push_warning("CharacterRig: run tools/bake_humans.gd — no skeleton baked")
		return
	skeleton = Skeleton3D.new()
	skeleton.name = "Skeleton"
	add_child(skeleton)
	var skin_res := Skin.new()
	var global_pos := {}
	for i in defs.size():
		var d: Dictionary = defs[i]
		var p := Vector3(d.pos[0], d.pos[1], d.pos[2])
		global_pos[d.name] = p
		skeleton.add_bone(d.name)
		var parent := str(d.parent)
		var local := p - (global_pos[parent] as Vector3) if parent != "" else p
		if parent != "":
			skeleton.set_bone_parent(i, _bone[parent])
		skeleton.set_bone_rest(i, Transform3D(Basis.IDENTITY, local))
		skeleton.set_bone_pose_position(i, local)
		_bone[d.name] = i
		_rest[d.name] = local
	skin_res.set_bind_count(defs.size())
	for i in defs.size():
		skin_res.set_bind_bone(i, i)
		skin_res.set_bind_pose(i, Transform3D(Basis.IDENTITY, -(global_pos[defs[i].name] as Vector3)))
	var mats := _materials()
	# Body (skinned) and the optional vest.
	var bm := MeshInstance3D.new()
	bm.mesh = _mesh("body_" + b)
	bm.skin = skin_res
	skeleton.add_child(bm)
	bm.skeleton = NodePath("..")
	_apply_surface_mats(bm, {"skin": mats.skin, "shirt": mats.shirt, "trousers": mats.trousers, "belt": mats.belt})
	if hi_vis:
		var vm := MeshInstance3D.new()
		vm.mesh = _mesh("vest_" + b)
		vm.skin = skin_res
		skeleton.add_child(vm)
		vm.skeleton = NodePath("..")
		_apply_surface_mats(vm, {"vest": mats.vest, "bands": mats.bands})
	# Head, hands and shoes ride their bones.
	var head := _attach("Head")
	_head_root = Node3D.new()
	head.add_child(_head_root)
	if big_head:
		_head_root.scale = Vector3.ONE * 1.45
	var hm := MeshInstance3D.new()
	hm.mesh = _mesh("head_" + b)
	_head_root.add_child(hm)
	_apply_surface_mats(hm, {"skin": mats.skin, "lips": mats.lips})
	_build_face(mats)
	if hair_style != "bald" and _mesh("hair_" + hair_style):
		var hr := MeshInstance3D.new()
		hr.mesh = _mesh("hair_" + hair_style)
		hr.material_override = mats.hair
		_head_root.add_child(hr)
	if cap:
		_build_cap()
	var arm := deg_to_rad(35.0)
	for side in ["L", "R"]:
		var sx := 1.0 if side == "L" else -1.0
		var ha := _attach("Hand" + side)
		var hand := MeshInstance3D.new()
		hand.mesh = _mesh("hand")
		hand.material_override = mats.skin
		hand.rotation.z = arm * sx
		hand.scale = Vector3(sx, 1, 1) * (0.94 if b == "f" else 1.0)
		ha.add_child(hand)
		var fa := _attach("Foot" + side)
		var shoe := MeshInstance3D.new()
		shoe.mesh = _mesh("shoe")
		shoe.scale = Vector3(sx, 1, 1) * (0.92 if b == "f" else 1.0)
		fa.add_child(shoe)
		_apply_surface_mats(shoe, {"upper": mats.shoe, "sole": mats.sole})
		if side == "R":
			_mug = _make_mug()
			_mug.rotation.z = -arm
			_mug.position = Vector3(-0.045, -0.085, 0.03).rotated(Vector3.BACK, -arm)
			_mug.visible = false
			ha.add_child(_mug)
	for mi: MeshInstance3D in find_children("*", "MeshInstance3D", true, false):
		mi.visibility_range_end = 60.0


func _attach(bone: String) -> BoneAttachment3D:
	var a := BoneAttachment3D.new()
	a.bone_name = bone
	skeleton.add_child(a)
	return a


func _apply_surface_mats(mi: MeshInstance3D, by_name: Dictionary) -> void:
	if mi.mesh == null:
		return
	for i in mi.mesh.get_surface_count():
		var nm := ""
		if mi.mesh is ArrayMesh:
			nm = (mi.mesh as ArrayMesh).surface_get_name(i)
		if by_name.has(nm):
			mi.set_surface_override_material(i, by_name[nm])


static var _fabric_normal: Texture2D
static var _hair_normal: Texture2D
static var _hair_albedo: Texture2D


static func _noise_normal(freq: float, stretch: Vector2) -> Texture2D:
	var n := FastNoiseLite.new()
	n.noise_type = FastNoiseLite.TYPE_SIMPLEX_SMOOTH
	n.frequency = freq
	n.fractal_octaves = 3
	var t := NoiseTexture2D.new()
	t.width = 256
	t.height = 256
	t.seamless = true
	t.as_normal_map = true
	t.bump_strength = 6.0
	t.noise = n
	if stretch != Vector2.ONE:
		n.frequency = freq
		t.width = int(256 * stretch.x)
		t.height = int(256 * stretch.y)
	return t


func _materials() -> Dictionary:
	if _fabric_normal == null:
		_fabric_normal = _noise_normal(0.25, Vector2.ONE)
		_hair_normal = _noise_normal(0.08, Vector2(4, 0.25))
		var hn := FastNoiseLite.new()
		hn.noise_type = FastNoiseLite.TYPE_SIMPLEX
		hn.frequency = 0.09
		hn.fractal_octaves = 4
		var ht := NoiseTexture2D.new()
		ht.width = 512
		ht.height = 64
		ht.seamless = true
		ht.noise = hn
		var ramp := Gradient.new()
		ramp.set_color(0, Color(0.62, 0.62, 0.62))
		ramp.set_color(1, Color(1.25, 1.25, 1.25))
		ht.color_ramp = ramp
		_hair_albedo = ht
	var m := {}
	var sk := StandardMaterial3D.new()
	sk.albedo_color = skin
	sk.roughness = 0.52
	sk.subsurf_scatter_enabled = true
	sk.subsurf_scatter_strength = 0.18
	sk.rim_enabled = true
	sk.rim = 0.05
	sk.rim_tint = 0.7
	m.skin = sk
	var lips := sk.duplicate() as StandardMaterial3D
	lips.albedo_color = skin.lerp(Color(0.62, 0.3, 0.3), 0.45)
	lips.roughness = 0.38
	m.lips = lips
	m.shirt = _fabric(shirt, 0.88, 26.0)
	m.trousers = _fabric(trousers, 0.82, 32.0)
	var belt := StandardMaterial3D.new()
	belt.albedo_color = Color(0.09, 0.07, 0.06)
	belt.roughness = 0.42
	m.belt = belt
	var vest := _fabric(Color(0.98, 0.85, 0.05), 0.7, 30.0)
	vest.emission_enabled = true
	vest.emission = Color(0.35, 0.3, 0.0)
	m.vest = vest
	var bands := StandardMaterial3D.new()
	bands.albedo_color = Color(0.82, 0.84, 0.86)
	bands.metallic = 0.6
	bands.roughness = 0.25
	m.bands = bands
	var hr := StandardMaterial3D.new()
	hr.albedo_color = hair
	hr.albedo_texture = _hair_albedo
	hr.roughness = 0.55
	hr.metallic_specular = 0.6
	hr.normal_enabled = true
	hr.normal_texture = _hair_normal
	hr.normal_scale = 0.8
	hr.uv1_triplanar = true
	hr.uv1_scale = Vector3.ONE * 40.0
	m.hair = hr
	var sh := StandardMaterial3D.new()
	sh.albedo_color = shoes
	sh.roughness = 0.55
	m.shoe = sh
	var so := StandardMaterial3D.new()
	so.albedo_color = Color(0.86, 0.85, 0.82) if shoes.get_luminance() < 0.3 else Color(0.12, 0.12, 0.13)
	so.roughness = 0.8
	m.sole = so
	return m


func _fabric(col: Color, rough: float, uv: float) -> StandardMaterial3D:
	var f := StandardMaterial3D.new()
	f.albedo_color = col
	f.roughness = rough
	f.normal_enabled = true
	f.normal_texture = _fabric_normal
	f.normal_scale = 0.35
	f.uv1_triplanar = true
	f.uv1_scale = Vector3.ONE * uv
	return f


func _build_face(mats: Dictionary) -> void:
	# Eyes (white, iris, pupil, a catchlight), lids that blink, and brows.
	var white := StandardMaterial3D.new()
	white.albedo_color = Color(0.95, 0.94, 0.92)
	white.roughness = 0.15
	var iris := StandardMaterial3D.new()
	iris.albedo_color = eye_colour
	iris.roughness = 0.2
	var pupil := StandardMaterial3D.new()
	pupil.albedo_color = Color(0.02, 0.02, 0.02)
	pupil.roughness = 0.1
	var glint := StandardMaterial3D.new()
	glint.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	glint.albedo_color = Color(1, 1, 1)
	var brow := StandardMaterial3D.new()
	brow.albedo_color = hair.darkened(0.15)
	brow.roughness = 0.8
	for sx in [-1.0, 1.0]:
		var eye := Node3D.new()
		eye.position = Vector3(0.031 * sx, 0.107, 0.0815)
		_head_root.add_child(eye)
		var ball := MeshInstance3D.new()
		var sm := SphereMesh.new()
		sm.radius = 0.0115
		sm.height = 0.023
		sm.radial_segments = 16
		sm.rings = 10
		ball.mesh = sm
		ball.material_override = white
		eye.add_child(ball)
		var ir := MeshInstance3D.new()
		var im := CylinderMesh.new()
		im.top_radius = 0.0052
		im.bottom_radius = 0.0052
		im.height = 0.0012
		im.radial_segments = 16
		ir.mesh = im
		ir.rotation.x = PI * 0.5
		ir.position.z = 0.0107
		ir.material_override = iris
		eye.add_child(ir)
		var pu := MeshInstance3D.new()
		var pm := CylinderMesh.new()
		pm.top_radius = 0.0026
		pm.bottom_radius = 0.0026
		pm.height = 0.0012
		pm.radial_segments = 12
		pu.mesh = pm
		pu.rotation.x = PI * 0.5
		pu.position.z = 0.0114
		pu.material_override = pupil
		eye.add_child(pu)
		var gl := MeshInstance3D.new()
		var gm := SphereMesh.new()
		gm.radius = 0.0011
		gm.height = 0.0022
		gl.mesh = gm
		gl.position = Vector3(0.002, 0.0022, 0.0116)
		gl.material_override = glint
		eye.add_child(gl)
		_eyes.append(eye)
		# Upper lid: a skin shell over the top of the eye that drops to blink.
		var lid := MeshInstance3D.new()
		var lm := SphereMesh.new()
		lm.radius = 0.0127
		lm.height = 0.0254
		lm.is_hemisphere = true
		lm.radial_segments = 16
		lm.rings = 6
		lid.mesh = lm
		lid.material_override = mats.skin
		lid.position = eye.position
		lid.rotation.x = deg_to_rad(-28.0)
		_head_root.add_child(lid)
		_lids.append(lid)
		var bw := MeshInstance3D.new()
		var bb := CapsuleMesh.new()
		bb.radius = 0.0028
		bb.height = 0.03
		bw.mesh = bb
		bw.material_override = brow
		bw.position = Vector3(0.032 * sx, 0.1265, 0.0935)
		bw.rotation = Vector3(deg_to_rad(-12.0), deg_to_rad(-14.0 * sx), deg_to_rad(90.0 + 8.0 * sx))
		_head_root.add_child(bw)
	# The face photo (character menu): a cut-out stuck on the front of the face.
	_face = MeshInstance3D.new()
	var fq := QuadMesh.new()
	fq.size = Vector2(0.15, 0.18)
	_face.mesh = fq
	_face.position = Vector3(0, 0.09, 0.129)
	_face.visible = false
	_head_root.add_child(_face)


func _build_cap() -> void:
	var cm := StandardMaterial3D.new()
	cm.albedo_color = shirt.darkened(0.25)
	cm.roughness = 0.8
	var dome := MeshInstance3D.new()
	var sm := SphereMesh.new()
	sm.radius = 0.093
	sm.height = 0.186
	sm.is_hemisphere = true
	dome.mesh = sm
	dome.scale = Vector3(0.9, 0.62, 1.04)
	dome.position = Vector3(0, 0.168, -0.004)
	dome.material_override = cm
	_head_root.add_child(dome)
	var peak := MeshInstance3D.new()
	var pb := CylinderMesh.new()
	pb.top_radius = 0.075
	pb.bottom_radius = 0.075
	pb.height = 0.006
	peak.mesh = pb
	peak.scale = Vector3(0.95, 1, 0.62)
	peak.position = Vector3(0, 0.168, 0.09)
	peak.rotation.x = deg_to_rad(8.0)
	peak.material_override = cm
	_head_root.add_child(peak)


func _make_mug() -> Node3D:
	var mug := TeaMug.make(0, 0)
	mug.scale = Vector3.ONE * 0.95
	mug.show_state(0.7, TeaMug.MILKY, true, false)
	return mug


func set_face_texture(tex: Texture2D, photo: bool = false) -> void:
	_face_tex = tex
	_photo = photo and tex != null
	if _face == null:
		return
	_face.visible = _photo
	if _photo:
		var m := StandardMaterial3D.new()
		m.albedo_texture = tex
		m.roughness = 0.7
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		_face.material_override = m


func play_drink() -> void:
	_drink_t = 0.0


func hip_height() -> float:
	## Height of the hip joint above the feet when standing (for sitting someone on a seat).
	if not _rest.has("Hips"):
		return 0.53 * height
	return (_rest.Hips as Vector3).y * scale.y


# ================================================================= animation

func _rot(bone: String, q: Quaternion) -> void:
	skeleton.set_bone_pose_rotation(_bone[bone], q)


static func _hinge(sx: float) -> Vector3:
	var arm := deg_to_rad(35.0)
	var d0 := Vector3(sin(arm) * sx, -cos(arm), 0.0)
	return d0.cross(Vector3.BACK).normalized()


static func _qx(a: float) -> Quaternion:
	return Quaternion(Vector3.RIGHT, a)


static func _qz(a: float) -> Quaternion:
	return Quaternion(Vector3.BACK, a)


static func _qy(a: float) -> Quaternion:
	return Quaternion(Vector3.UP, a)


func _process(delta: float) -> void:
	if skeleton == null or not is_visible_in_tree():
		return
	_t += delta
	var spd := clampf(move_speed, 0.0, 6.0)
	var amp := clampf(spd / 1.5, 0.0, 1.0)
	_phase += delta * (0.9 + spd * 0.95)
	var ph := _phase * TAU
	var sw := sin(ph)
	# ---- legs: swing, knee bend on the swing-through, foot kept flat; hips bob twice a stride.
	var thigh := deg_to_rad(26.0) * amp
	var lt := -sw * thigh
	var rt := sw * thigh
	var lk := maxf(0.0, sin(ph + 1.2)) * deg_to_rad(55.0) * amp + deg_to_rad(3.0)
	var rk := maxf(0.0, sin(ph + 1.2 + PI)) * deg_to_rad(55.0) * amp + deg_to_rad(3.0)
	_rot("ThighL", _qx(lt - deg_to_rad(1.5)))
	_rot("ThighR", _qx(rt - deg_to_rad(1.5)))
	_rot("ShinL", _qx(lk))
	_rot("ShinR", _qx(rk))
	_rot("FootL", _qx(-(lt + lk) * 0.85))
	_rot("FootR", _qx(-(rt + rk) * 0.85))
	var bob := -absf(sin(ph)) * 0.03 * amp
	var sway := sin(_t * 0.7) * 0.006 * (1.0 - amp)
	skeleton.set_bone_pose_position(_bone.Hips, (_rest.Hips as Vector3) + Vector3(sway + sin(ph) * 0.012 * amp, bob, 0))
	_rot("Hips", _qy(sw * deg_to_rad(6.0) * amp) * _qz(sin(_t * 0.7) * 0.012 * (1.0 - amp)))
	if anim == "sit":
		# Seated (forklift): thighs forward and level, shins down, feet flat on the floor plate.
		for s in ["L", "R"]:
			var spread := 0.05 if s == "L" else -0.05
			_rot("Thigh" + s, _qx(deg_to_rad(-84.0)) * _qz(spread))
			_rot("Shin" + s, _qx(deg_to_rad(80.0)))
			_rot("Foot" + s, _qx(deg_to_rad(4.0)))
		skeleton.set_bone_pose_position(_bone.Hips, _rest.Hips)
		_rot("Hips", Quaternion.IDENTITY)
	# ---- torso: counter-twist, breathing, a slight lean when walking.
	var breath := sin(_t * 1.7) * deg_to_rad(1.2)
	_rot("Spine", _qx(deg_to_rad(3.0) * amp + breath * 0.4) * _qy(-sw * deg_to_rad(4.0) * amp))
	_rot("Chest", _qx(-breath) * _qy(-sw * deg_to_rad(5.0) * amp))
	# ---- head: looks around when idle, steady when walking.
	_look_t -= delta
	if _look_t <= 0.0:
		_look_t = randf_range(1.5, 4.5)
		_look_target = Vector2(randf_range(-0.5, 0.5), randf_range(-0.12, 0.15)) * (1.0 - amp * 0.8)
	_look = _look.lerp(_look_target, clampf(delta * 3.0, 0.0, 1.0))
	_rot("Neck", _qy(_look.x * 0.4) * _qx(_look.y * 0.4))
	_rot("Head", _qy(_look.x * 0.6) * _qx(_look.y * 0.6 - sw * 0.02 * amp))
	for e: Node3D in _eyes:
		e.rotation = Vector3(-_look.y * 0.3, _look.x * 0.4, 0)
	# ---- blink every few seconds.
	_blink_t -= delta
	var blink := 0.0
	if _blink_t < 0.12:
		blink = sin(clampf(_blink_t / 0.12, 0.0, 1.0) * PI)
		if _blink_t <= 0.0:
			_blink_t = randf_range(2.2, 5.5)
	for lid: Node3D in _lids:
		lid.rotation.x = lerpf(deg_to_rad(-28.0), deg_to_rad(62.0), blink)
	# ---- arms: hang naturally from the A-pose, then pose by activity.
	var hang := deg_to_rad(27.5)
	var l_sw := 0.0
	var r_sw := 0.0
	var l_out := 0.0
	var r_out := 0.0
	var l_el := deg_to_rad(-9.0)
	var r_el := deg_to_rad(-9.0)
	match anim:
		"push":
			l_sw = deg_to_rad(-55.0)
			r_sw = deg_to_rad(-55.0)
			l_out = deg_to_rad(-6.0)
			r_out = deg_to_rad(-6.0)
			l_el = deg_to_rad(-28.0)
			r_el = deg_to_rad(-28.0)
		"carry":
			l_sw = deg_to_rad(-26.0)
			r_sw = deg_to_rad(-26.0)
			l_out = deg_to_rad(-9.0)
			r_out = deg_to_rad(-9.0)
			l_el = deg_to_rad(-72.0)
			r_el = deg_to_rad(-72.0)
		"work":
			# Hands busy at bench height, small movements.
			l_sw = deg_to_rad(-30.0) + sin(_t * 2.3) * 0.05
			r_sw = deg_to_rad(-34.0) + sin(_t * 3.1 + 1.0) * 0.08
			l_out = deg_to_rad(-6.0)
			r_out = deg_to_rad(-8.0)
			l_el = deg_to_rad(-58.0) + sin(_t * 2.7) * 0.08
			r_el = deg_to_rad(-62.0) + sin(_t * 3.4 + 0.5) * 0.12
		"sit":
			# Both hands on the steering wheel, a little turn of the wheel now and then.
			var turn := sin(_t * 0.9) * 0.06
			l_sw = deg_to_rad(-42.0) + turn
			r_sw = deg_to_rad(-42.0) - turn
			l_out = deg_to_rad(-12.0)
			r_out = deg_to_rad(-12.0)
			l_el = deg_to_rad(-48.0)
			r_el = deg_to_rad(-48.0)
		"wave":
			r_sw = deg_to_rad(-15.0)
			r_out = deg_to_rad(125.0)
			r_el = deg_to_rad(-25.0) + sin(_t * 9.0) * 0.35
			l_sw = sw * deg_to_rad(18.0) * amp
		_:
			l_sw = sw * deg_to_rad(22.0) * amp
			r_sw = -sw * deg_to_rad(22.0) * amp
			l_el -= amp * deg_to_rad(14.0) + maxf(0.0, -sw) * deg_to_rad(10.0) * amp
			r_el -= amp * deg_to_rad(14.0) + maxf(0.0, sw) * deg_to_rad(10.0) * amp
	# Tea: the right arm brings the mug up to the lips, a sip, back down (~3.2 s).
	if _drink_t >= 0.0:
		_drink_t += delta
		var f := 0.0
		if _drink_t < 0.8:
			f = smoothstep(0.0, 0.8, _drink_t)
		elif _drink_t < 2.3:
			f = 1.0
		elif _drink_t < 3.2:
			f = 1.0 - smoothstep(2.3, 3.2, _drink_t)
		else:
			_drink_t = -1.0
		r_sw = lerpf(r_sw, deg_to_rad(-38.0), f)
		r_out = lerpf(r_out, deg_to_rad(-6.0), f)
		r_el = lerpf(r_el, deg_to_rad(-128.0), f)
		_rot("Head", _qx(-f * deg_to_rad(14.0)) * _qy(_look.x * 0.3))
		if _mug:
			_mug.visible = f > 0.05
	elif _mug:
		_mug.visible = false
	# Positive "out" lifts an arm away from the body (sideways); negative brings it across.
	_rot("UpperArmL", _qx(l_sw) * _qz(-hang + l_out))
	_rot("UpperArmR", _qx(r_sw) * _qz(hang - r_out))
	# Elbows hinge about the axis across the forearm (not world X), so a bent arm comes forward, not out.
	_rot("ForeArmL", Quaternion(_hinge(1.0), -l_el))
	_rot("ForeArmR", Quaternion(_hinge(-1.0), -r_el))
	_rot("HandL", _qz(deg_to_rad(6.0)) * _qx(deg_to_rad(-6.0)))
	_rot("HandR", _qz(deg_to_rad(-6.0)) * _qx(deg_to_rad(-6.0)))
	_rot("ClavicleL", _qz(sin(_t * 1.7) * 0.01))
	_rot("ClavicleR", _qz(-sin(_t * 1.7) * 0.01))
