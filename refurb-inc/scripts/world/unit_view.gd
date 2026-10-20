class_name UnitView
extends Node3D
## Visual for one hardware unit (laptop, tower, small-form-factor or mini PC).
## Uses, in order: visual.model (explicit path) → a model file found in assets/ by visual.match keywords
## (ModelLibrary, auto-fitted to the data size) → a procedural placeholder built from the data.
## Model contract for final art: origin bottom-centre, front toward +Z. Laptops: closed by default, a node
## named "Lid" pivoting at the hinge (rotates about local X, negative = opens) and a MeshInstance3D named
## "Screen". Models without them still work: they just don't open or show the live screen.

const OPEN_ANGLE := -108.0

static var _keys_tex: ImageTexture
static var _crack_tex: ImageTexture

var lid_pivot: Node3D
var screen: MeshInstance3D
var size := Vector3(0.32, 0.019, 0.21)
var _lid_open := false
var _tween: Tween
var _grade_tag: Node3D
var _crack: MeshInstance3D
var _model_id := ""
var imported_path := ""           # the model file in use, "" = procedural placeholder


func configure(u: UnitData) -> void:
	if u.model_id == _model_id:
		refresh(u)
		return
	_model_id = u.model_id
	for c in get_children():
		c.queue_free()
	var vis: Dictionary = Content.model(u.model_id).get("visual", {})
	size = Vector3(float(vis.get("w", 0.32)), float(vis.get("h", 0.019)), float(vis.get("d", 0.21)))
	lid_pivot = null
	screen = null
	var form := u.form()
	var path: String = vis.get("model", "")
	if path == "" or not ResourceLoader.exists(path):
		path = ModelLibrary.find(vis.get("match", []))
	var inst := ModelLibrary.instantiate_fitted(path, size, "x" if form == "laptop" else "y", float(vis.get("model_rot", 0.0)))
	if inst:
		add_child(inst)
		imported_path = path
		size = inst.get_meta("fitted_size", size)
		lid_pivot = inst.find_child("Lid", true, false)
		screen = inst.find_child("Screen", true, false) as MeshInstance3D
	elif form == "laptop":
		_build_placeholder(vis)
	else:
		_build_desktop(vis, form)
	_build_overlays()
	set_lid_open(false, false)
	refresh(u)


func _build_placeholder(vis: Dictionary) -> void:
	var body := Color(vis.get("body", "#2b2e33"))
	var deck := Color(vis.get("deck", "#1f2124"))
	var accent := Color(vis.get("accent", "#9aa3ad"))
	var base_h := size.y * 0.6
	var lid_h := size.y * 0.4
	var shell := Mats.color(body, 0.45, 0.35)
	# Base with slightly inset bottom so it reads as a chamfer
	Placeholders.box(self, Vector3(size.x - 0.006, base_h * 0.4, size.z - 0.006), Vector3(0, base_h * 0.2, 0), shell)
	Placeholders.box(self, Vector3(size.x, base_h * 0.6, size.z), Vector3(0, base_h * 0.7, 0), shell, Vector3.ZERO, "Base")
	# Deck: keyboard well + keys + touchpad
	var kb := MeshInstance3D.new()
	var kq := QuadMesh.new()
	kq.size = Vector2(size.x * 0.86, size.z * 0.42)
	kb.mesh = kq
	kb.rotation_degrees = Vector3(-90, 0, 0)
	kb.position = Vector3(0, base_h + 0.0006, -size.z * 0.12)
	var km := StandardMaterial3D.new()
	km.albedo_texture = _keys_texture()
	km.albedo_color = deck.lightened(0.15)
	km.roughness = 0.75
	kb.material_override = km
	add_child(kb)
	Placeholders.box(self, Vector3(size.x * 0.32, 0.0008, size.z * 0.26), Vector3(0, base_h + 0.0004, size.z * 0.27), Mats.color(deck.lightened(0.06), 0.35))
	# Ports on the sides
	for i in 3:
		Placeholders.box(self, Vector3(0.002, 0.004, 0.012), Vector3(size.x / 2 + 0.0005, base_h * 0.5, -size.z * 0.2 + i * 0.022), Mats.color(Color(0.05, 0.05, 0.05)))
	# Lid
	lid_pivot = Node3D.new()
	lid_pivot.name = "Lid"
	lid_pivot.position = Vector3(0, base_h, -size.z / 2)
	add_child(lid_pivot)
	Placeholders.box(lid_pivot, Vector3(size.x, lid_h, size.z), Vector3(0, lid_h / 2, size.z / 2), shell, Vector3.ZERO, "LidShell")
	# Bezel (inner face) + screen
	Placeholders.box(lid_pivot, Vector3(size.x - 0.004, 0.0006, size.z - 0.004), Vector3(0, -0.0002, size.z / 2), Mats.color(Color(0.04, 0.04, 0.045), 0.3))
	screen = MeshInstance3D.new()
	screen.name = "Screen"
	var sq := QuadMesh.new()
	sq.size = Vector2(size.x * 0.92, size.z * 0.84)
	screen.mesh = sq
	screen.rotation_degrees = Vector3(90, 0, 0)
	screen.position = Vector3(0, -0.0008, size.z / 2 + size.z * 0.02)
	screen.material_override = Mats.screen_off()
	lid_pivot.add_child(screen)
	# Logo on the outside of the lid
	var logo := Label3D.new()
	logo.text = str(vis.get("logo", ""))
	logo.font_size = 48
	logo.pixel_size = 0.0006
	logo.modulate = accent
	logo.shaded = true
	logo.rotation_degrees = Vector3(-90, 180, 0)
	logo.position = Vector3(0, lid_h + 0.0004, size.z * 0.5)
	lid_pivot.add_child(logo)


func _build_desktop(vis: Dictionary, form: String) -> void:
	## Procedural desktop: case, front bezel with power button, drive/USB details, vents and a badge.
	var body := Color(vis.get("body", "#26282c"))
	var bezel := Color(vis.get("deck", "#17181b"))
	var accent := Color(vis.get("accent", "#9aa3ad"))
	var shell := Mats.color(body, 0.5, 0.3)
	var front := Mats.color(bezel, 0.4, 0.1)
	var dark := Mats.color(Color(0.04, 0.04, 0.045), 0.6)
	var metal := Mats.color(accent.darkened(0.2), 0.35, 0.8)
	var w := size.x
	var h := size.y
	var d := size.z
	var fz := d / 2
	if form == "mini":
		# Square slab: rounded-ish body, top badge, front ports and a vent strip at the back.
		Placeholders.box(self, Vector3(w, h * 0.92, d), Vector3(0, h * 0.46, 0), shell)
		Placeholders.box(self, Vector3(w - 0.01, h * 0.08, d - 0.01), Vector3(0, h * 0.96, 0), Mats.color(body.lightened(0.08), 0.35, 0.3))
		Placeholders.box(self, Vector3(w * 0.9, h * 0.5, 0.002), Vector3(0, h * 0.45, fz + 0.001), front)
		for i in 2:
			Placeholders.box(self, Vector3(0.012, 0.0045, 0.002), Vector3(-w * 0.3 + i * 0.018, h * 0.45, fz + 0.002), dark)
		Placeholders.box(self, Vector3(0.006, 0.006, 0.002), Vector3(w * 0.36, h * 0.45, fz + 0.002), Mats.color(Color(0.2, 0.55, 1.0), 0.3))
		Placeholders.box(self, Vector3(w * 0.8, h * 0.4, 0.002), Vector3(0, h * 0.5, -fz - 0.001), dark)
		_badge(vis, accent, Vector3(0, h + 0.0006, 0), Vector3(-90, 0, 0), 0.0005)
		return
	# Tower / SFF: upright case.
	Placeholders.box(self, Vector3(w, h, d), Vector3(0, h / 2, 0), shell, Vector3.ZERO, "Case")
	Placeholders.box(self, Vector3(w + 0.002, h, 0.012), Vector3(0, h / 2, fz + 0.005), front, Vector3.ZERO, "Bezel")
	# Feet
	for x in [-1, 1]:
		for z in [-1, 1]:
			Placeholders.box(self, Vector3(0.02, 0.008, 0.03), Vector3(x * (w / 2 - 0.015), -0.002, z * (d / 2 - 0.03)), dark)
	# Optical / drive bay, power button, front USB, vent slots
	if form == "tower":
		Placeholders.box(self, Vector3(w * 0.78, 0.042, 0.003), Vector3(0, h * 0.86, fz + 0.012), Mats.color(bezel.lightened(0.05), 0.35))
		Placeholders.box(self, Vector3(w * 0.5, 0.004, 0.002), Vector3(0, h * 0.86, fz + 0.0135), dark)
	else:
		Placeholders.box(self, Vector3(0.004, h * 0.32, 0.003), Vector3(-w * 0.25, h * 0.72, fz + 0.012), dark)
	Placeholders.box(self, Vector3(0.016, 0.016, 0.004), Vector3(w * 0.2, h * 0.94, fz + 0.012), metal, Vector3.ZERO, "PowerButton")
	Placeholders.box(self, Vector3(0.004, 0.004, 0.001), Vector3(w * 0.2, h * 0.905, fz + 0.0125), Mats.color(Color(0.3, 0.75, 1.0), 0.2))
	for i in 2:
		Placeholders.box(self, Vector3(0.013, 0.005, 0.002), Vector3(-w * 0.15, h * 0.62 - i * 0.012, fz + 0.012), dark)
	for i in 6:
		Placeholders.box(self, Vector3(w * 0.6, 0.003, 0.002), Vector3(0, h * 0.12 + i * 0.012, fz + 0.012), dark)
	# Side vent and rear I/O
	Placeholders.box(self, Vector3(0.002, h * 0.25, d * 0.3), Vector3(w / 2 + 0.001, h * 0.6, -d * 0.15), dark)
	Placeholders.box(self, Vector3(w * 0.7, h * 0.35, 0.002), Vector3(0, h * 0.7, -fz - 0.001), dark)
	_badge(vis, accent, Vector3(0, h * 0.42, fz + 0.012), Vector3.ZERO, 0.0004)


func _badge(vis: Dictionary, col: Color, pos: Vector3, rot: Vector3, px: float) -> void:
	var logo := Label3D.new()
	logo.text = str(vis.get("logo", ""))
	logo.font_size = 48
	logo.pixel_size = px
	logo.modulate = col
	logo.shaded = true
	logo.rotation_degrees = rot
	logo.position = pos
	add_child(logo)


func _build_overlays() -> void:
	# Grade sticker (top of lid, front-right corner)
	_grade_tag = Node3D.new()
	_grade_tag.name = "GradeSticker"
	var parent: Node3D = lid_pivot if lid_pivot else self
	parent.add_child(_grade_tag)
	_grade_tag.position = Vector3(size.x * 0.38, size.y * 0.4 + 0.0006, size.z * 0.85) if lid_pivot else Vector3(size.x * 0.25, size.y + 0.001, size.z * 0.25)
	_grade_tag.visible = false
	# Crack overlay on the screen
	if screen:
		_crack = MeshInstance3D.new()
		var cq := QuadMesh.new()
		cq.size = (screen.mesh as QuadMesh).size if screen.mesh is QuadMesh else Vector2(size.x * 0.9, size.z * 0.8)
		_crack.mesh = cq
		var cm := StandardMaterial3D.new()
		cm.albedo_texture = _crack_texture()
		cm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		cm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		_crack.material_override = cm
		_crack.position = Vector3(0, 0, 0.0005)
		_crack.visible = false
		screen.add_child(_crack)


func refresh(u: UnitData) -> void:
	if _crack:
		_crack.visible = "cracked_screen" in u.cosmetic
	if lid_pivot and "broken_hinge" in u.cosmetic:
		lid_pivot.rotation_degrees.z = 4.0
	elif lid_pivot:
		lid_pivot.rotation_degrees.z = 0.0
	_set_grade(u.grade)


func _set_grade(g: String) -> void:
	if _grade_tag == null:
		return
	for c in _grade_tag.get_children():
		c.queue_free()
	_grade_tag.visible = g != ""
	if g == "":
		return
	var col: Color = {"A": Color(0.2, 0.75, 0.35), "B": Color(0.2, 0.5, 0.9), "C": Color(0.95, 0.65, 0.1), "D": Color(0.85, 0.25, 0.2), "S": Color(0.5, 0.5, 0.5)}.get(g, Color.WHITE)
	Placeholders.box(_grade_tag, Vector3(0.035, 0.0004, 0.035), Vector3.ZERO, Mats.color(col, 0.6))
	var l := Label3D.new()
	l.text = "D" if g == "S" else g
	l.font_size = 64
	l.pixel_size = 0.0004
	l.modulate = Color.WHITE
	l.rotation_degrees = Vector3(-90, 180, 0)
	l.position = Vector3(0, 0.0004, 0)
	l.outline_size = 0
	_grade_tag.add_child(l)


func set_lid_open(open: bool, animate: bool = true) -> void:
	if lid_pivot == null:
		return
	if _tween:
		_tween.kill()
	_lid_open = open
	var target := OPEN_ANGLE if open else 0.0
	if animate and is_inside_tree():
		_tween = create_tween().set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
		_tween.tween_property(lid_pivot, "rotation_degrees:x", target, 0.45)
	else:
		lid_pivot.rotation_degrees.x = target


func set_screen_texture(tex: Texture2D) -> void:
	if screen == null:
		return
	screen.material_override = Mats.screen_texture(tex) if tex else Mats.screen_off()


func footprint() -> Vector3:
	return size


# ---------------------------------------------------------------- generated textures

static func _keys_texture() -> ImageTexture:
	if _keys_tex:
		return _keys_tex
	var w := 512
	var h := 200
	var img := Image.create(w, h, false, Image.FORMAT_RGBA8)
	img.fill(Color(0.05, 0.05, 0.055))
	var rows := [14, 14, 13, 12, 10]
	var ky := 8
	for r in rows.size():
		var n: int = rows[r]
		var kw := (w - 16) / float(n)
		for i in n:
			var x0 := int(8 + i * kw + 2)
			var x1 := int(8 + (i + 1) * kw - 2)
			img.fill_rect(Rect2i(x0, ky, x1 - x0, 32), Color(0.16, 0.16, 0.17))
			img.fill_rect(Rect2i(x0 + 2, ky + 2, x1 - x0 - 4, 26), Color(0.19, 0.19, 0.2))
		ky += 38
	_keys_tex = ImageTexture.create_from_image(img)
	return _keys_tex


static func _crack_texture() -> ImageTexture:
	if _crack_tex:
		return _crack_tex
	var s := 256
	var img := Image.create(s, s, false, Image.FORMAT_RGBA8)
	img.fill(Color(0, 0, 0, 0))
	var rng := RandomNumberGenerator.new()
	rng.seed = 42
	var origin := Vector2(s * 0.62, s * 0.4)
	for b in 9:
		var p := origin
		var ang := rng.randf() * TAU
		for step in 60:
			ang += rng.randf_range(-0.35, 0.35)
			p += Vector2(cos(ang), sin(ang)) * 2.5
			if p.x < 0 or p.y < 0 or p.x >= s or p.y >= s:
				break
			img.set_pixel(int(p.x), int(p.y), Color(0.9, 0.95, 1.0, 0.85))
			if int(p.x) + 1 < s:
				img.set_pixel(int(p.x) + 1, int(p.y), Color(0.6, 0.65, 0.7, 0.5))
	_crack_tex = ImageTexture.create_from_image(img)
	return _crack_tex
