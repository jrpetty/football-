class_name DecorProp
extends StationBase
## Decor you buy to make the premises yours (Shop → Fit-out / Building app): plants, a rug, a site radio, a wall
## clock that keeps game time, framed posters, and the Employee of the Month frame. Equipment like any other
## (placed, moved with [F], saved, travels when you move premises). Wall pieces ("mount": "wall") hang on the
## wall behind where they're placed. Live bits — clock hands, the radio, accent colours, the company name, this
## week's Employee of the Month and their photo — follow the replicated state, so everyone sees the same.

@export var kind := "plant_tall"

const WALL_Y := 1.55          # centre height of wall pieces
const FRAME_BLACK := Color(0.06, 0.06, 0.07)

var _clock_t := 0.0
var _hour: Node3D
var _minute: Node3D
var _music: AudioStreamPlayer3D
var _radio_led: MeshInstance3D
var _dyn: Node3D              # rebuilt when the accent colour / name / Employee of the Month changes
var _dyn_sig := ""


func _ready() -> void:
	if Engine.is_editor_hint():
		return
	_build_body()
	super._ready()


func _station_ready() -> void:
	_hour = find_child("HourHand", true, false)
	_minute = find_child("MinuteHand", true, false)
	_radio_led = find_child("RadioLED", true, false)
	if kind == "site_radio":
		_music = Sfx.make_loop("radio_music", self, -13.0)
		_music.position = Vector3(0, 0.25, 0.1)
		_music.unit_size = 2.5
		_music.max_distance = 22.0
	if Content.fixture(kind).get("mount", "") == "wall":
		_hug_wall.call_deferred()
	if not Game.state.changed.is_connected(_on_style_changed):
		Game.state.changed.connect(_on_style_changed)
	if kind == "eotm_frame":
		Game.looks_changed.connect(_on_looks_changed)
	_refresh_dynamic(true)
	set_process(kind == "wall_clock" or kind == "site_radio")


func _exit_tree() -> void:
	if Game.state and Game.state.changed.is_connected(_on_style_changed):
		Game.state.changed.disconnect(_on_style_changed)


func _on_looks_changed(_peer: int) -> void:
	_refresh_dynamic(false)


func _on_style_changed(k: String, id: Variant) -> void:
	if k in ["premises", "company"] or (k == "station" and str(id) == station_id) or (k == "player" and kind == "eotm_frame"):
		_refresh_dynamic(false)


func _build_body() -> void:
	var body := StaticBody3D.new()
	body.name = "Body"
	var wall: bool = Content.fixture(kind).get("mount", "") == "wall"
	# Wall pieces and the rug are things you look at, not walk into.
	body.collision_layer = (1 << 3) if (wall or kind == "rug") else (1 | (1 << 3))
	var cs := CollisionShape3D.new()
	var bs := BoxShape3D.new()
	var size := _body_size()
	bs.size = size
	cs.shape = bs
	cs.position = Vector3(0, WALL_Y, size.z * 0.5) if wall else Vector3(0, size.y * 0.5, 0)
	body.add_child(cs)
	add_child(body)


func _body_size() -> Vector3:
	match kind:
		"plant_tall": return Vector3(0.45, 1.5, 0.45)
		"plant_small": return Vector3(0.3, 0.75, 0.3)
		"rug": return Vector3(1.8, 0.012, 1.2)
		"site_radio": return Vector3(0.46, 0.36, 0.28)
		"wall_clock": return Vector3(0.38, 0.38, 0.06)
		"eotm_frame": return Vector3(0.52, 0.7, 0.05)
	return Vector3(0.52, 0.72, 0.04)          # posters


func _hug_wall() -> void:
	## Placed against the wall's floor line: find the actual wall surface behind and sit flush on it.
	await get_tree().physics_frame
	await get_tree().physics_frame
	if not is_inside_tree():
		return
	var q := PhysicsRayQueryParameters3D.new()
	var origin := global_transform * Vector3(0, WALL_Y, 0.15)
	q.from = origin
	q.to = global_transform * Vector3(0, WALL_Y, -0.7)
	q.collision_mask = 1
	var hit := get_world_3d().direct_space_state.intersect_ray(q)
	var back := 0.0
	if not hit.is_empty():
		back = (global_transform.affine_inverse() * (hit.position as Vector3)).z
	var off := Vector3(0, 0, back + 0.005)
	if model:
		model.position = off
	if _dyn:
		_dyn.position = off
	var body: Node3D = get_node_or_null("Body")
	if body:
		body.position = off


func _process(delta: float) -> void:
	_clock_t -= delta
	if _clock_t > 0.0:
		return
	_clock_t = 0.25
	if kind == "wall_clock" and _hour and _minute:
		var m := Game.state.minute_of_day()
		_minute.rotation.z = -TAU * fmod(m, 60.0) / 60.0
		_hour.rotation.z = -TAU * fmod(m / 60.0, 12.0) / 12.0
	elif kind == "site_radio" and _music:
		var on := bool(Game.state.stations.get(station_id, {}).get("on", false))
		if on and not _music.playing and _music.stream:
			_music.play()
		elif not on and _music.playing:
			_music.stop()
		if _radio_led:
			(_radio_led.material_override as StandardMaterial3D).emission_energy_multiplier = 2.5 if on else 0.0


# ---------------------------------------------------------------- interaction

func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	info.title = str(Content.fixture(kind).get("name", display_name))
	info.lines = []
	match kind:
		"site_radio":
			var on := bool(Game.state.stations.get(station_id, {}).get("on", false))
			info.lines = ["Playing Refurb FM" if on else "Off"]
			info.prompt = "[E] Switch %s  ·  [F] Move" % ("off" if on else "on")
		"wall_clock":
			info.lines = [Game.state.clock_text() + " · " + Game.state.weekday_name()]
		"eotm_frame":
			var e: Dictionary = Game.state.premises.get("eotm", {})
			if str(e.get("key", "")) == "":
				info.lines = ["Nominations open — whoever does the most work this week goes up on Monday."]
			else:
				info.lines = ["%s — %d jobs last week" % [str(e.name), int(e.get("score", 0))]]
				for row in (e.get("board", []) as Array).slice(1, 3):
					info.lines.append("Runner-up: %s (%d)" % [str(row.name), int(row.score)])
	return info


func interact(_player: Node) -> void:
	if kind == "site_radio":
		Game.request("decor_toggle", {"id": station_id})
		Sfx.play_at("ui_click", global_position + Vector3(0, 0.3, 0), -4.0)


# ---------------------------------------------------------------- live parts

func _refresh_dynamic(force: bool) -> void:
	var accent := PremisesStyle.accent_colour(Game.state)
	var sig := "%s|%s" % [accent.to_html(), Game.state.company_name]
	if kind == "eotm_frame":
		var e: Dictionary = Game.state.premises.get("eotm", {})
		var face: PackedByteArray = _face_for(str(e.get("key", "")))
		sig += "|%s|%d|%d" % [str(e), face.size(), hash(face) if face.size() < 400000 else 0]
	elif kind == "site_radio":
		return
	if not force and sig == _dyn_sig:
		return
	_dyn_sig = sig
	if _dyn:
		_dyn.queue_free()
	_dyn = Node3D.new()
	_dyn.name = "Live"
	_dyn.position = model.position if model else Vector3.ZERO
	add_child(_dyn)
	match kind:
		"rug":
			_live_rug(_dyn, accent)
		"poster_motto":
			_live_motto(_dyn, accent)
		"eotm_frame":
			_live_eotm(_dyn, accent)


static func _face_for(key: String) -> PackedByteArray:
	## A player's uploaded photo face, if they're here and have one (staff and absent players: none).
	if not key.begins_with("p") or key.begins_with("p-"):
		return PackedByteArray()
	var peer := int(key.substr(1))
	return Game.looks.get(peer, {}).get("face", PackedByteArray())


static func _initials(name: String) -> String:
	var out := ""
	for w in name.strip_edges().split(" ", false):
		if out.length() < 2 and w.length() > 0:
			out += w.substr(0, 1).to_upper()
	return out if out != "" else "?"


func _live_rug(root: Node3D, accent: Color) -> void:
	var bb := BoxBatch.new()
	var dark := Mats.color(accent.darkened(0.55), 0.95)
	bb.box(Vector3(1.8, 0.012, 1.2), Vector3(0, 0.006, 0), dark)
	bb.box(Vector3(1.6, 0.013, 1.0), Vector3(0, 0.0065, 0), Mats.color(accent, 0.95))
	for k in 3:
		bb.box(Vector3(1.4, 0.014, 0.04), Vector3(0, 0.007, -0.24 + k * 0.24), Mats.color(accent.lightened(0.45), 0.95))
	bb.commit(root, "Rug")
	var t := Label3D.new()
	t.text = Game.state.company_name.to_upper()
	t.font_size = 72
	t.pixel_size = 0.0022
	t.modulate = Color(0.97, 0.96, 0.92)
	t.rotation_degrees = Vector3(-90, 0, 0)
	t.position = Vector3(0, 0.016, 0.36)
	t.shaded = true
	root.add_child(t)


func _live_motto(root: Node3D, accent: Color) -> void:
	var bb := BoxBatch.new()
	bb.box(Vector3(0.44, 0.62, 0.004), Vector3(0, WALL_Y, 0.024), Mats.color(accent, 0.8))
	bb.box(Vector3(0.36, 0.006, 0.002), Vector3(0, WALL_Y - 0.17, 0.027), Mats.color(Color(1, 1, 1, 0.9), 0.6))
	bb.commit(root, "Motto")
	var big := Label3D.new()
	big.text = "BUY IT.\nFIX IT.\nFLIP IT.\nSCALE IT."
	big.font_size = 96
	big.pixel_size = 0.00085
	big.modulate = Color(1, 1, 1)
	big.outline_size = 0
	big.line_spacing = -8.0
	big.position = Vector3(0, WALL_Y + 0.06, 0.028)
	big.horizontal_alignment = HORIZONTAL_ALIGNMENT_LEFT
	big.shaded = true
	root.add_child(big)
	var small := Label3D.new()
	small.text = Game.state.company_name.to_upper()
	small.font_size = 40
	small.pixel_size = 0.0008
	small.modulate = Color(1, 1, 1, 0.92)
	small.position = Vector3(0, WALL_Y - 0.225, 0.028)
	small.shaded = true
	root.add_child(small)


func _live_eotm(root: Node3D, accent: Color) -> void:
	var e: Dictionary = Game.state.premises.get("eotm", {})
	var who := str(e.get("name", ""))
	var face := _face_for(str(e.get("key", "")))
	var photo_c := Vector3(0, WALL_Y + 0.04, 0.03)
	if who == "":
		_l3(root, "THIS COULD\nBE YOU", photo_c, 64, Color(0.35, 0.33, 0.3), 0.0011)
		_l3(root, "Nominations open — most jobs done this week wins", Vector3(0, WALL_Y - 0.22, 0.03), 26, Color(0.3, 0.3, 0.3), 0.0008)
		return
	var shown := false
	if not face.is_empty():
		var img := Image.new()
		if img.load_png_from_buffer(face) == OK:
			var spr := Sprite3D.new()
			spr.texture = ImageTexture.create_from_image(img)
			spr.pixel_size = 0.26 / maxf(float(img.get_width()), 1.0)
			spr.position = photo_c
			spr.shaded = true
			spr.double_sided = false
			root.add_child(spr)
			shown = true
	if not shown:
		# Styled name card: initials in a disc of the accent colour.
		var bb := BoxBatch.new()
		bb.cyl(0.11, 0.004, Transform3D(Basis(Vector3.RIGHT, PI * 0.5), photo_c), Mats.color(accent, 0.6), 32)
		bb.cyl(0.118, 0.003, Transform3D(Basis(Vector3.RIGHT, PI * 0.5), photo_c - Vector3(0, 0, 0.001)), Mats.color(Color(0.85, 0.7, 0.3), 0.35, 0.8), 32)
		bb.commit(root, "Card")
		_l3(root, _initials(who), photo_c + Vector3(0, 0, 0.004), 150, Color.WHITE, 0.0011)
		if bool(e.get("staff", false)):
			_l3(root, str(e.get("role", "Staff")).to_upper(), photo_c + Vector3(0, -0.08, 0.004), 22, Color(1, 1, 1, 0.85), 0.0008)
	_l3(root, who, Vector3(0, WALL_Y - 0.17, 0.03), 52, Color(0.12, 0.1, 0.08), 0.0009)
	var sub := "%d jobs last week" % int(e.get("score", 0))
	if int(e.get("streak", 1)) > 1:
		sub += " · %d weeks running" % int(e.streak)
	_l3(root, sub, Vector3(0, WALL_Y - 0.225, 0.03), 26, Color(0.35, 0.3, 0.25), 0.0008)


static func _l3(root: Node3D, txt: String, pos: Vector3, size: int, col: Color, pixel: float) -> Label3D:
	var l := Label3D.new()
	l.text = txt
	l.font_size = size
	l.pixel_size = pixel
	l.modulate = col
	l.outline_size = 0
	l.position = pos
	l.shaded = true
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	root.add_child(l)
	return l


# ---------------------------------------------------------------- the models (static: also placement ghost / menu)

static func build_visual(root: Node3D, k: String) -> void:
	var bb := BoxBatch.new()
	match k:
		"plant_tall":
			_plant_tall(bb)
		"plant_small":
			_plant_small(bb)
		"rug":
			pass                    # the rug is all accent colour (live)
		"site_radio":
			_radio(bb, root)
		"wall_clock":
			_clock(bb, root)
		"poster_safety":
			_frame(bb, Vector2(0.48, 0.66))
			_poster_safety(bb, root)
		"poster_motto":
			_frame(bb, Vector2(0.48, 0.66))
		"poster_retro":
			_frame(bb, Vector2(0.48, 0.66))
			_poster_retro(bb, root)
		"eotm_frame":
			_eotm_frame(bb, root)
	if not bb.is_empty():
		bb.commit(root, "Decor")


static func _frame(bb: BoxBatch, s: Vector2) -> void:
	var black := Mats.color(FRAME_BLACK, 0.4)
	var t := 0.025
	bb.box(Vector3(s.x, t, 0.025), Vector3(0, WALL_Y + s.y * 0.5 - t * 0.5, 0.0125), black)
	bb.box(Vector3(s.x, t, 0.025), Vector3(0, WALL_Y - s.y * 0.5 + t * 0.5, 0.0125), black)
	bb.box(Vector3(t, s.y, 0.025), Vector3(-s.x * 0.5 + t * 0.5, WALL_Y, 0.0125), black)
	bb.box(Vector3(t, s.y, 0.025), Vector3(s.x * 0.5 - t * 0.5, WALL_Y, 0.0125), black)
	bb.box(Vector3(s.x - 0.02, s.y - 0.02, 0.01), Vector3(0, WALL_Y, 0.008), Mats.color(Color(0.95, 0.94, 0.9), 0.85))


static func _plant_tall(bb: BoxBatch) -> void:
	var pot := Mats.color(Color(0.13, 0.32, 0.36), 0.25, 0.1)
	bb.cyl(0.2, 0.42, Transform3D(Basis.IDENTITY, Vector3(0, 0.21, 0)), pot, 20, 0.23)
	bb.cyl(0.235, 0.03, Transform3D(Basis.IDENTITY, Vector3(0, 0.425, 0)), pot, 20)
	bb.cyl(0.2, 0.02, Transform3D(Basis.IDENTITY, Vector3(0, 0.41, 0)), Mats.color(Color(0.18, 0.13, 0.09), 0.95), 16)
	var bark := Mats.color(Color(0.42, 0.33, 0.24), 0.9)
	var dark := Mats.color(Color(0.13, 0.36, 0.14), 0.6)
	var light := Mats.color(Color(0.3, 0.55, 0.2), 0.6)
	var rng := RandomNumberGenerator.new()
	rng.seed = 7
	var tops := [Vector3(-0.05, 1.2, 0.02), Vector3(0.07, 1.42, -0.04), Vector3(0.0, 0.95, 0.07)]
	for top: Vector3 in tops:
		bb.beam(Vector3(top.x * 0.3, 0.4, top.z * 0.3), top, Vector2(0.05, 0.05), bark)
		for i in 16:
			var a := TAU * i / 16.0 + rng.randf() * 0.3
			var droop := rng.randf_range(-0.25, 0.55)
			var dir := Vector3(cos(a), droop, sin(a)).normalized()
			var length := rng.randf_range(0.32, 0.46)
			bb.beam(top, top + dir * length, Vector2(0.045, 0.006), dark if i % 3 else light)


static func _plant_small(bb: BoxBatch) -> void:
	var white := Mats.color(Color(0.93, 0.92, 0.88), 0.35)
	bb.box(Vector3(0.24, 0.24, 0.24), Vector3(0, 0.12, 0), white)
	bb.box(Vector3(0.22, 0.01, 0.22), Vector3(0, 0.235, 0), Mats.color(Color(0.18, 0.13, 0.09), 0.95))
	var leaf := Mats.color(Color(0.12, 0.3, 0.16), 0.55)
	var edge := Mats.color(Color(0.75, 0.72, 0.3), 0.55)
	var rng := RandomNumberGenerator.new()
	rng.seed = 11
	for i in 11:
		var a := TAU * i / 11.0
		var base := Vector3(cos(a) * 0.05, 0.24, sin(a) * 0.05)
		var tip := base + Vector3(cos(a) * rng.randf_range(0.04, 0.12), rng.randf_range(0.38, 0.6), sin(a) * rng.randf_range(0.04, 0.12))
		bb.beam(base, tip, Vector2(0.05, 0.008), leaf)
		bb.beam(base + Vector3(0, 0.02, 0), tip - Vector3(0, 0.03, 0), Vector2(0.056, 0.006), edge)


static func _radio(bb: BoxBatch, root: Node3D) -> void:
	var yellow := Mats.color(Color(0.95, 0.72, 0.08), 0.45)
	var black := Mats.color(Color(0.07, 0.07, 0.08), 0.5)
	var cage := Mats.color(Color(0.12, 0.12, 0.13), 0.35, 0.6)
	bb.box(Vector3(0.38, 0.24, 0.2), Vector3(0, 0.17, 0), yellow)
	bb.box(Vector3(0.36, 0.2, 0.01), Vector3(0, 0.17, 0.103), black)
	bb.cyl(0.075, 0.01, Transform3D(Basis(Vector3.RIGHT, PI * 0.5), Vector3(-0.08, 0.16, 0.11)), Mats.color(Color(0.2, 0.2, 0.22), 0.8), 20)
	bb.cyl(0.075, 0.01, Transform3D(Basis(Vector3.RIGHT, PI * 0.5), Vector3(0.08, 0.16, 0.11)), Mats.color(Color(0.2, 0.2, 0.22), 0.8), 20)
	bb.box(Vector3(0.1, 0.03, 0.006), Vector3(0, 0.255, 0.108), Mats.color(Color(0.3, 0.4, 0.3), 0.3))
	for k in 3:
		bb.cyl(0.012, 0.012, Transform3D(Basis(Vector3.RIGHT, PI * 0.5), Vector3(-0.1 + k * 0.1, 0.075, 0.11)), black, 10)
	# Roll cage: four corner loops joined across the top (the handle).
	for sx in [-1.0, 1.0]:
		for sz in [-1.0, 1.0]:
			bb.beam(Vector3(sx * 0.21, 0.02, sz * 0.12), Vector3(sx * 0.21, 0.33, sz * 0.12), Vector2(0.022, 0.022), cage)
		bb.beam(Vector3(sx * 0.21, 0.33, -0.12), Vector3(sx * 0.21, 0.33, 0.12), Vector2(0.022, 0.022), cage)
		bb.beam(Vector3(sx * 0.21, 0.02, -0.12), Vector3(sx * 0.21, 0.02, 0.12), Vector2(0.022, 0.022), cage)
	bb.beam(Vector3(-0.21, 0.36, 0), Vector3(0.21, 0.36, 0), Vector2(0.026, 0.026), cage)
	bb.beam(Vector3(-0.21, 0.33, 0), Vector3(-0.21, 0.36, 0), Vector2(0.022, 0.022), cage)
	bb.beam(Vector3(0.21, 0.33, 0), Vector3(0.21, 0.36, 0), Vector2(0.022, 0.022), cage)
	bb.beam(Vector3(0.16, 0.29, -0.06), Vector3(0.12, 0.62, -0.1), Vector2(0.006, 0.006), Mats.color(Color(0.8, 0.8, 0.82), 0.25, 0.9))
	var led := MeshInstance3D.new()
	led.name = "RadioLED"
	var bm := BoxMesh.new()
	bm.size = Vector3(0.098, 0.028, 0.002)
	led.mesh = bm
	var lm := StandardMaterial3D.new()
	lm.albedo_color = Color(0.25, 0.45, 0.3)
	lm.emission_enabled = true
	lm.emission = Color(0.5, 1.0, 0.6)
	lm.emission_energy_multiplier = 0.0
	led.material_override = lm
	led.position = Vector3(0, 0.255, 0.112)
	root.add_child(led)


static func _clock(bb: BoxBatch, root: Node3D) -> void:
	var c := Vector3(0, WALL_Y + 0.25, 0)
	var face_xf := Transform3D(Basis(Vector3.RIGHT, PI * 0.5), c + Vector3(0, 0, 0.02))
	bb.cyl(0.18, 0.04, face_xf, Mats.color(FRAME_BLACK, 0.35), 40)
	bb.cyl(0.165, 0.042, face_xf.translated(Vector3(0, 0, 0.0)), Mats.color(Color(0.97, 0.97, 0.95), 0.5), 40)
	var black := Mats.color(Color(0.08, 0.08, 0.09), 0.4)
	for i in 12:
		var a := TAU * i / 12.0
		var r := 0.145
		var long := i % 3 == 0
		bb.box(Vector3(0.008 if not long else 0.012, 0.022 if not long else 0.034, 0.003), c + Vector3(sin(a) * r, cos(a) * r, 0.043), black, Vector3(0, 0, -rad_to_deg(a)))
	for hand in [["HourHand", 0.085, 0.011], ["MinuteHand", 0.13, 0.007]]:
		var pivot := Node3D.new()
		pivot.name = hand[0]
		pivot.position = c + Vector3(0, 0, 0.046 + (0.002 if hand[0] == "MinuteHand" else 0.0))
		root.add_child(pivot)
		var mi := MeshInstance3D.new()
		var m := BoxMesh.new()
		m.size = Vector3(hand[2], hand[1], 0.002)
		mi.mesh = m
		mi.material_override = black
		mi.position = Vector3(0, float(hand[1]) * 0.42, 0)
		pivot.add_child(mi)
	bb.cyl(0.01, 0.006, Transform3D(Basis(Vector3.RIGHT, PI * 0.5), c + Vector3(0, 0, 0.05)), Mats.color(Color(0.8, 0.2, 0.15), 0.4), 12)
	var brand := Label3D.new()
	brand.text = "PENNINE TIME"
	brand.font_size = 22
	brand.pixel_size = 0.0007
	brand.modulate = Color(0.25, 0.25, 0.27)
	brand.position = c + Vector3(0, 0.05, 0.043)
	root.add_child(brand)


static func _poster_safety(bb: BoxBatch, root: Node3D) -> void:
	var y := WALL_Y
	bb.box(Vector3(0.44, 0.12, 0.002), Vector3(0, y + 0.25, 0.0145), Mats.color(Color(0.98, 0.78, 0.08), 0.7))
	bb.box(Vector3(0.44, 0.01, 0.002), Vector3(0, y + 0.185, 0.0146), Mats.color(FRAME_BLACK, 0.7))
	# Pictogram: a figure squatting to lift a box (green = right way).
	var ink := Mats.color(Color(0.1, 0.45, 0.2), 0.7)
	var p := Vector3(-0.02, y + 0.02, 0.0148)
	bb.cyl(0.03, 0.002, Transform3D(Basis(Vector3.RIGHT, PI * 0.5), p + Vector3(0, 0.12, 0)), ink, 16)
	bb.beam(p + Vector3(0, 0.09, 0), p + Vector3(0.0, -0.01, 0), Vector2(0.022, 0.002), ink)
	bb.beam(p + Vector3(0, -0.01, 0), p + Vector3(-0.06, -0.05, 0), Vector2(0.02, 0.002), ink)
	bb.beam(p + Vector3(-0.06, -0.05, 0), p + Vector3(-0.03, -0.11, 0), Vector2(0.02, 0.002), ink)
	bb.beam(p + Vector3(0, -0.01, 0), p + Vector3(0.05, -0.05, 0), Vector2(0.02, 0.002), ink)
	bb.beam(p + Vector3(0.05, -0.05, 0), p + Vector3(0.04, -0.11, 0), Vector2(0.02, 0.002), ink)
	bb.beam(p + Vector3(0, 0.07, 0), p + Vector3(0.08, 0.0, 0), Vector2(0.016, 0.002), ink)
	bb.box(Vector3(0.09, 0.07, 0.002), p + Vector3(0.12, -0.02, 0), Mats.color(Color(0.62, 0.45, 0.25), 0.8))
	var t := _l3(root, "HEALTH & SAFETY", Vector3(0, y + 0.25, 0.017), 44, FRAME_BLACK, 0.0009)
	t.modulate = FRAME_BLACK
	_l3(root, "LIFT WITH YOUR LEGS\nNOT YOUR BACK", Vector3(0, y - 0.17, 0.016), 40, FRAME_BLACK, 0.0008)
	_l3(root, "Heavy kit? Use the pallet truck or forklift.", Vector3(0, y - 0.27, 0.016), 22, Color(0.3, 0.3, 0.3), 0.0007)


static func _poster_retro(bb: BoxBatch, root: Node3D) -> void:
	var y := WALL_Y
	bb.box(Vector3(0.44, 0.62, 0.002), Vector3(0, y, 0.0145), Mats.color(Color(0.1, 0.1, 0.16), 0.8))
	var stripes := [Color(0.9, 0.25, 0.2), Color(0.95, 0.55, 0.15), Color(0.95, 0.82, 0.2), Color(0.3, 0.7, 0.35), Color(0.25, 0.5, 0.9)]
	for i in stripes.size():
		bb.box(Vector3(0.44, 0.022, 0.002), Vector3(0, y - 0.04 - i * 0.024, 0.0147), Mats.color(stripes[i], 0.6))
	var beige := Mats.color(Color(0.86, 0.8, 0.68), 0.6)
	# The machine: a beige wedge keyboard and a chunky monitor with a glowing green screen.
	bb.box(Vector3(0.24, 0.05, 0.01), Vector3(0, y + 0.02, 0.0155), beige)
	bb.box(Vector3(0.17, 0.13, 0.01), Vector3(0, y + 0.13, 0.0155), beige)
	bb.box(Vector3(0.13, 0.09, 0.004), Vector3(0, y + 0.135, 0.0215), Mats.emissive(Color(0.25, 0.85, 0.35), 0.8))
	for r in 3:
		bb.box(Vector3(0.2, 0.006, 0.003), Vector3(0, y + 0.006 + r * 0.013, 0.0215), Mats.color(Color(0.45, 0.42, 0.38), 0.6))
	_l3(root, "HALDEN HC-64", Vector3(0, y + 0.255, 0.016), 54, Color(0.97, 0.9, 0.6), 0.0009)
	_l3(root, "The future is home.", Vector3(0, y - 0.19, 0.016), 30, Color(0.95, 0.95, 0.95), 0.0008)
	_l3(root, "64K RAM · COLOUR · £399 inc. VAT · 1987", Vector3(0, y - 0.25, 0.016), 18, Color(0.75, 0.75, 0.8), 0.0007)


static func _eotm_frame(bb: BoxBatch, root: Node3D) -> void:
	var gold := Mats.color(Color(0.78, 0.62, 0.28), 0.3, 0.75)
	var s := Vector2(0.5, 0.68)
	var t := 0.035
	bb.box(Vector3(s.x, t, 0.03), Vector3(0, WALL_Y + s.y * 0.5 - t * 0.5, 0.015), gold)
	bb.box(Vector3(s.x, t, 0.03), Vector3(0, WALL_Y - s.y * 0.5 + t * 0.5, 0.015), gold)
	bb.box(Vector3(t, s.y, 0.03), Vector3(-s.x * 0.5 + t * 0.5, WALL_Y, 0.015), gold)
	bb.box(Vector3(t, s.y, 0.03), Vector3(s.x * 0.5 - t * 0.5, WALL_Y, 0.015), gold)
	bb.box(Vector3(s.x - 0.03, s.y - 0.03, 0.012), Vector3(0, WALL_Y, 0.01), Mats.color(Color(0.95, 0.92, 0.84), 0.85))
	# Ribbon banner across the top.
	bb.box(Vector3(0.42, 0.07, 0.003), Vector3(0, WALL_Y + 0.255, 0.0175), Mats.color(Color(0.62, 0.12, 0.14), 0.6))
	_l3(root, "EMPLOYEE OF THE MONTH", Vector3(0, WALL_Y + 0.255, 0.02), 36, Color(1.0, 0.92, 0.7), 0.0008)
	# Little brass plaque under it.
	bb.box(Vector3(0.18, 0.035, 0.004), Vector3(0, WALL_Y - s.y * 0.5 - 0.04, 0.012), gold)
