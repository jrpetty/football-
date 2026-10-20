class_name CharacterRig
extends Node3D
## A jointed, procedurally animated person for remote players, lorry drivers and (later) staff.
## Feet at y = 0, facing +Z. Built from smooth primitives so it is cheap and needs no imported rig; swap the
## look by colours/options, or give it a face texture (players can upload a photo — see set_face_texture()).
##
## Animations (set `anim`): idle · walk · push (pallet truck / trolley) · carry (arms out holding a stack) ·
## drink (lifts the mug to the face) · wave. `move_speed` (m/s) drives the stride; 0 = standing.

@export var shirt := Color(0.2, 0.35, 0.6)
@export var trousers := Color(0.16, 0.17, 0.2)
@export var skin := Color(0.86, 0.67, 0.55)
@export var hair := Color(0.2, 0.14, 0.1)
@export var shoes := Color(0.08, 0.08, 0.09)
@export var hi_vis := false
@export var cap := false
@export var height := 1.78

var anim := "idle"
var move_speed := 0.0

var _hips: Node3D
var _torso: Node3D
var _head: Node3D
var _face: MeshInstance3D
var _arm := {}        # "L"/"R" -> {shoulder, elbow}
var _leg := {}        # "L"/"R" -> {hip, knee}
var _mug: Node3D
var _phase := 0.0
var _t := 0.0
var _drink_t := -1.0
var _built := false

static var _default_face: ImageTexture


func _ready() -> void:
	_build()


func set_face_texture(tex: Texture2D) -> void:
	if _face == null:
		return
	var m := StandardMaterial3D.new()
	m.albedo_texture = tex if tex else _face_tex()
	m.roughness = 0.75
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA_SCISSOR
	_face.material_override = m


func play_drink() -> void:
	_drink_t = 0.0


# ---------------------------------------------------------------- build

func _build() -> void:
	if _built:
		return
	_built = true
	var k := height / 1.78
	var m_shirt := Mats.color(shirt, 0.8)
	var m_trousers := Mats.color(trousers, 0.85)
	var m_skin := Mats.color(skin, 0.6)
	var m_hair := Mats.color(hair, 0.9)
	var m_shoes := Mats.color(shoes, 0.5)
	_hips = Node3D.new()
	_hips.position = Vector3(0, 0.94 * k, 0)
	add_child(_hips)
	_cap_mesh(_hips, 0.16 * k, 0.24 * k, Vector3(0, 0.02 * k, 0), m_trousers, Vector3(0, 0, 90))   # pelvis
	_torso = Node3D.new()
	_torso.position = Vector3(0, 0.08 * k, 0)
	_hips.add_child(_torso)
	_cap_mesh(_torso, 0.17 * k, 0.56 * k, Vector3(0, 0.26 * k, 0), m_shirt, Vector3.ZERO, Vector3(1.15, 1, 0.78))
	if hi_vis:
		var vest := Mats.color(Color(0.95, 0.9, 0.1), 0.6)
		_cap_mesh(_torso, 0.178 * k, 0.42 * k, Vector3(0, 0.3 * k, 0), vest, Vector3.ZERO, Vector3(1.16, 1, 0.8))
		for y in [0.2, 0.34]:
			var strip := MeshInstance3D.new()
			var cm := CylinderMesh.new()
			cm.top_radius = 0.181 * k
			cm.bottom_radius = 0.181 * k
			cm.height = 0.025 * k
			strip.mesh = cm
			strip.scale = Vector3(1.16, 1, 0.8)
			strip.position = Vector3(0, y * k, 0)
			strip.material_override = Mats.color(Color(0.85, 0.87, 0.9), 0.2, 0.6)
			_torso.add_child(strip)
	# Neck + head
	var neck := _cap_mesh(_torso, 0.055 * k, 0.12 * k, Vector3(0, 0.6 * k, 0), m_skin)
	_head = Node3D.new()
	_head.position = Vector3(0, 0.66 * k, 0)
	_torso.add_child(_head)
	var skull := MeshInstance3D.new()
	var sm := SphereMesh.new()
	sm.radius = 0.11 * k
	sm.height = 0.25 * k
	skull.mesh = sm
	skull.scale = Vector3(0.92, 1, 1)
	skull.position = Vector3(0, 0.11 * k, 0)
	skull.material_override = m_skin
	_head.add_child(skull)
	var hair_mi := MeshInstance3D.new()
	var hm := SphereMesh.new()
	hm.radius = 0.112 * k
	hm.height = 0.18 * k
	hm.is_hemisphere = true
	hair_mi.mesh = hm
	hair_mi.scale = Vector3(0.95, 1, 1.02)
	hair_mi.position = Vector3(0, 0.15 * k, -0.008 * k)
	hair_mi.material_override = m_hair
	_head.add_child(hair_mi)
	if cap:
		var capm := Mats.color(shirt.darkened(0.2), 0.7)
		_cap_mesh(_head, 0.118 * k, 0.07 * k, Vector3(0, 0.2 * k, 0), capm, Vector3.ZERO, Vector3(1, 0.6, 1.05))
		var peak := MeshInstance3D.new()
		var pb := BoxMesh.new()
		pb.size = Vector3(0.16 * k, 0.012 * k, 0.09 * k)
		peak.mesh = pb
		peak.position = Vector3(0, 0.185 * k, 0.12 * k)
		peak.material_override = capm
		_head.add_child(peak)
	# Ears + nose give the head a readable front.
	for sx in [-1, 1]:
		var ear := MeshInstance3D.new()
		var em := SphereMesh.new()
		em.radius = 0.022 * k
		em.height = 0.05 * k
		ear.mesh = em
		ear.position = Vector3(sx * 0.1 * k, 0.11 * k, 0)
		ear.material_override = m_skin
		_head.add_child(ear)
	_face = MeshInstance3D.new()
	var fq := QuadMesh.new()
	fq.size = Vector2(0.15 * k, 0.17 * k)
	_face.mesh = fq
	_face.position = Vector3(0, 0.105 * k, 0.1 * k)
	_head.add_child(_face)
	set_face_texture(null)
	# Arms
	for side in ["L", "R"]:
		var sx := 1.0 if side == "L" else -1.0
		var shoulder := Node3D.new()
		shoulder.position = Vector3(sx * 0.21 * k, 0.5 * k, 0)
		_torso.add_child(shoulder)
		_cap_mesh(shoulder, 0.055 * k, 0.3 * k, Vector3(0, -0.13 * k, 0), m_shirt)
		var elbow := Node3D.new()
		elbow.position = Vector3(0, -0.28 * k, 0)
		shoulder.add_child(elbow)
		_cap_mesh(elbow, 0.045 * k, 0.27 * k, Vector3(0, -0.12 * k, 0), m_skin)
		var hand := MeshInstance3D.new()
		var hs := SphereMesh.new()
		hs.radius = 0.045 * k
		hs.height = 0.1 * k
		hand.mesh = hs
		hand.position = Vector3(0, -0.28 * k, 0.01 * k)
		hand.material_override = m_skin
		elbow.add_child(hand)
		_arm[side] = {"shoulder": shoulder, "elbow": elbow, "hand": hand}
	# Legs
	for side in ["L", "R"]:
		var sx := 1.0 if side == "L" else -1.0
		var hip := Node3D.new()
		hip.position = Vector3(sx * 0.095 * k, -0.02 * k, 0)
		_hips.add_child(hip)
		_cap_mesh(hip, 0.075 * k, 0.46 * k, Vector3(0, -0.21 * k, 0), m_trousers)
		var knee := Node3D.new()
		knee.position = Vector3(0, -0.44 * k, 0)
		hip.add_child(knee)
		_cap_mesh(knee, 0.06 * k, 0.44 * k, Vector3(0, -0.2 * k, 0), m_trousers)
		var foot := MeshInstance3D.new()
		var fb := BoxMesh.new()
		fb.size = Vector3(0.1 * k, 0.07 * k, 0.26 * k)
		foot.mesh = fb
		foot.position = Vector3(0, -0.445 * k, 0.05 * k)
		foot.material_override = m_shoes
		knee.add_child(foot)
		_leg[side] = {"hip": hip, "knee": knee}
	# Mug (for the tea break), hidden until drinking.
	_mug = Node3D.new()
	_mug.position = Vector3(0, -0.31 * k, 0.06 * k)
	_arm.R.elbow.add_child(_mug)
	var mugm := MeshInstance3D.new()
	var mc := CylinderMesh.new()
	mc.top_radius = 0.04
	mc.bottom_radius = 0.036
	mc.height = 0.1
	mugm.mesh = mc
	mugm.material_override = Mats.color(Color(0.95, 0.95, 0.93), 0.3)
	_mug.add_child(mugm)
	_mug.visible = false


func _cap_mesh(parent: Node3D, radius: float, length: float, pos: Vector3, mat: Material, rot_deg := Vector3.ZERO, scl := Vector3.ONE) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var cm := CapsuleMesh.new()
	cm.radius = radius
	cm.height = maxf(length, radius * 2.0)
	cm.radial_segments = 12
	cm.rings = 4
	mi.mesh = cm
	mi.position = pos
	mi.rotation_degrees = rot_deg
	mi.scale = scl
	mi.material_override = mat
	parent.add_child(mi)
	return mi


# ---------------------------------------------------------------- animation

func _process(delta: float) -> void:
	if not _built or not is_visible_in_tree():
		return
	_t += delta
	var spd := clampf(move_speed, 0.0, 6.0)
	var striding := spd > 0.05
	_phase += delta * (1.6 + spd * 1.9) * (1.0 if striding else 0.0)
	var sw := sin(_phase * TAU * 0.5)
	var amp := clampf(spd / 1.6, 0.0, 1.0)
	# Legs: thigh swing with a knee bend on the forward leg.
	var hip_a := deg_to_rad(28.0) * amp
	_leg.L.hip.rotation.x = sw * hip_a
	_leg.R.hip.rotation.x = -sw * hip_a
	_leg.L.knee.rotation.x = maxf(0.0, -sw) * deg_to_rad(40.0) * amp
	_leg.R.knee.rotation.x = maxf(0.0, sw) * deg_to_rad(40.0) * amp
	_hips.position.y = 0.94 * height / 1.78 - absf(sw) * 0.025 * amp
	# Breathing.
	_torso.scale = Vector3(1, 1.0 + sin(_t * 1.8) * 0.006, 1)
	_head.rotation.y = sin(_t * 0.4) * 0.08 * (1.0 - amp)
	# Arms by pose.
	var l_sh := Vector3.ZERO
	var r_sh := Vector3.ZERO
	var l_el := 0.0
	var r_el := 0.0
	match anim:
		"push":
			l_sh = Vector3(deg_to_rad(-62), 0, deg_to_rad(-8))
			r_sh = Vector3(deg_to_rad(-62), 0, deg_to_rad(8))
			l_el = deg_to_rad(-25)
			r_el = deg_to_rad(-25)
		"carry":
			l_sh = Vector3(deg_to_rad(-40), 0, deg_to_rad(-6))
			r_sh = Vector3(deg_to_rad(-40), 0, deg_to_rad(6))
			l_el = deg_to_rad(-55)
			r_el = deg_to_rad(-55)
		"wave":
			r_sh = Vector3(deg_to_rad(-20), 0, deg_to_rad(150))
			r_el = deg_to_rad(-20) + sin(_t * 9.0) * 0.4
			l_sh.x = -sw * deg_to_rad(18) * amp
		_:
			l_sh.x = -sw * deg_to_rad(22) * amp
			r_sh.x = sw * deg_to_rad(22) * amp
			l_sh.z = deg_to_rad(-4)
			r_sh.z = deg_to_rad(4)
			l_el = deg_to_rad(-10) - amp * 0.2
			r_el = deg_to_rad(-10) - amp * 0.2
	# Drinking overrides the right arm: lift, sip, lower (≈3 s).
	if _drink_t >= 0.0:
		_drink_t += delta
		var f := 0.0
		if _drink_t < 0.8:
			f = smoothstep(0.0, 0.8, _drink_t)
		elif _drink_t < 2.2:
			f = 1.0
		else:
			f = 1.0 - smoothstep(2.2, 3.0, _drink_t)
		r_sh = r_sh.lerp(Vector3(deg_to_rad(-55), deg_to_rad(-15), deg_to_rad(10)), f)
		r_el = lerpf(r_el, deg_to_rad(-125), f)
		_head.rotation.x = -0.25 * (f if _drink_t > 0.7 and _drink_t < 2.3 else 0.0)
		_mug.visible = true
		if _drink_t > 3.0:
			_drink_t = -1.0
			_mug.visible = false
			_head.rotation.x = 0.0
	_arm.L.shoulder.rotation = _arm.L.shoulder.rotation.lerp(l_sh, clampf(delta * 10.0, 0, 1))
	_arm.R.shoulder.rotation = _arm.R.shoulder.rotation.lerp(r_sh, clampf(delta * 10.0, 0, 1))
	_arm.L.elbow.rotation.x = lerpf(_arm.L.elbow.rotation.x, l_el, clampf(delta * 10.0, 0, 1))
	_arm.R.elbow.rotation.x = lerpf(_arm.R.elbow.rotation.x, r_el, clampf(delta * 10.0, 0, 1))


static func _face_tex() -> ImageTexture:
	## A friendly default face (eyes, brows, smile) on a transparent background.
	if _default_face:
		return _default_face
	var s := 128
	var img := Image.create(s, s, false, Image.FORMAT_RGBA8)
	img.fill(Color(0, 0, 0, 0))
	var dark := Color(0.12, 0.1, 0.09)
	for sx in [-1, 1]:
		var cx: int = 64 + int(sx) * 22
		for y in range(48, 60):
			for x in range(cx - 6, cx + 6):
				if Vector2(x - cx, (y - 54) * 1.3).length() < 6.0:
					img.set_pixel(x, y, Color(1, 1, 1))
				if Vector2(x - cx, y - 54).length() < 3.5:
					img.set_pixel(x, y, dark)
		for x in range(cx - 9, cx + 9):
			img.set_pixel(x, 40 + int(absf(x - cx) * 0.15), dark)
			img.set_pixel(x, 41 + int(absf(x - cx) * 0.15), dark)
	for x in range(46, 82):
		var y := 92 + int(pow((x - 64) / 18.0, 2.0) * -7.0) + 7
		img.set_pixel(x, y, Color(0.55, 0.2, 0.2))
		img.set_pixel(x, y + 1, Color(0.55, 0.2, 0.2))
	_default_face = ImageTexture.create_from_image(img)
	return _default_face
