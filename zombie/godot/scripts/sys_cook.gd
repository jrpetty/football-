extends Node3D
## Campfires and stoves: fuel, lighting, burning down, light, flames and smoke, the crackle, warmth
## (is_near_fire) and the cooking window. The fire's own state ({fuel, lit, pos}) lives on the built piece in
## sys_build.gd, which saves it; the recipes are made through sys_craft.gd.

const U := preload("res://scripts/sys_util.gd")
const WARM_RADIUS := 4.0
const LIGHT_COL := Color(1.0, 0.54, 0.23)
const NO_FUEL := ["coal", "gas_can"]          # machine fuels; they do not go on a cooking fire

var game
var fires: Array = []                         # [{piece, light, flames, smoke, sparks, sound, on, seed}]
var open_piece = null

var _wired := false
var _loop: AudioStream = null
var _status: Label = null
var _fuel_lbl: Label = null
var _fuel_bar: ProgressBar = null
var _bar: ProgressBar = null
var _bar_lbl: Label = null
var _lit_shown := false
var _tick := 0.0

func _ready() -> void:
	_wire()

func _wire() -> void:
	if _wired:
		return
	var b = U.mod(game, "sys_build")
	if b == null:
		return
	b.piece_placed.connect(_on_placed)
	b.piece_removed.connect(_on_removed)
	var c = U.mod(game, "sys_craft")
	if c != null:
		c.made.connect(func(_r) -> void: _rebuild())
	_wired = true
	for p in b.list:
		_on_placed(p)

# ------------------------------------------------------------------ for the other systems
## is there a burning fire within arm's warmth of this spot?
func is_near_fire(pos: Vector3) -> bool:
	for f in fires:
		var p: Dictionary = f.piece
		if p.fire.lit and absf(float(p.y) - pos.y) < 3.0 and Vector2(float(p.cx) - pos.x, float(p.cz) - pos.z).length() < WARM_RADIUS:
			return true
	return false

func fire_name(p: Dictionary) -> String:
	return "Wood stove" if String(p.station) == "stove" else "Campfire"

func fire_prompt(p: Dictionary) -> String:
	if p.fire.lit:
		return "%s  ·  burning, %d min of fuel left" % [fire_name(p), int(ceil(float(p.fire.fuel)))]
	if float(p.fire.fuel) > 0.0:
		return "%s  ·  laid with %d min of fuel, not lit" % [fire_name(p), int(ceil(float(p.fire.fuel)))]
	return "%s  ·  cold" % fire_name(p)

func fuels() -> Array:
	var out: Array = []
	var seen := {}
	for s in game.player.inv.stacks:
		var id := String(s.id)
		if seen.has(id) or NO_FUEL.has(id) or not Items.def(id).has("fuel"):
			continue
		seen[id] = true
		out.append(id)
	return out

func add_fuel(p: Dictionary, id: String) -> bool:
	var d := Items.def(id)
	if not d.has("fuel") or NO_FUEL.has(id) or not game.player.inv.remove(id, 1):
		return false
	p.fire.fuel = float(p.fire.fuel) + float(d.fuel) * 3.0
	Sfx.play("wood_break", {"volume": 0.3})
	return true

func light(p: Dictionary) -> bool:
	if p.fire.lit:
		return true
	if not game.player.inv.has_tool("fire"):
		game.hud.notify("You need a lighter.", "bad"); Sfx.play("ui_error")
		return false
	if float(p.fire.fuel) <= 0.0:
		game.hud.notify("Add fuel first: logs, planks or biomass.", "bad"); Sfx.play("ui_error")
		return false
	p.fire.lit = true
	Sfx.play("fire_ignite", {"pos": p.fire.pos})
	return true

# ------------------------------------------------------------------ module interface
func mod_started(_new_game: bool) -> void:
	_wire()

func mod_process(dt: float, dt_min: float, _ui_open: bool) -> void:
	if fires.is_empty():
		return
	var pp: Vector3 = game.player.pos
	var b = U.mod(game, "sys_build")
	var rain: float = b.raining() if b != null else 0.0
	var now: float = game.time
	for f in fires:
		var p: Dictionary = f.piece
		var fire: Dictionary = p.fire
		if fire.lit:
			fire.fuel = float(fire.fuel) - dt_min
			var near: bool = pp.distance_squared_to(fire.pos) < 900.0
			if float(fire.fuel) <= 0.0:
				fire.fuel = 0.0; fire.lit = false
				if near: game.hud.notify("The %s has burned out." % fire_name(p).to_lower(), "")
			elif rain > 0.6 and String(p.station) == "campfire" and randf() < dt_min / 60.0 * 0.5 and not (b != null and b.under_roof(fire.pos)):
				fire.lit = false
				if near: game.hud.notify("The rain put out your campfire.", "bad")
		_show_fire(f, bool(fire.lit), pp, now)
	if game.hud.is_open() and game.hud.window_kind == "cook":
		_refresh_window(dt)

func _show_fire(f: Dictionary, lit: bool, pp: Vector3, now: float) -> void:
	var near := lit and pp.distance_squared_to(f.piece.fire.pos) < 6400.0
	if near != f.on:
		f.on = near
		f.flames.emitting = near; f.smoke.emitting = near; f.sparks.emitting = near
		if near: f.sound.play()
		else: f.sound.stop()
	var light: OmniLight3D = f.light
	if not lit:
		if light.visible:
			light.light_energy = move_toward(light.light_energy, 0.0, 0.2)
			light.visible = light.light_energy > 0.01
		return
	var s: float = f.seed
	var flick := 1.0 - 0.35 * (0.5 + 0.28 * sin(now * 11.0 + s) + 0.17 * sin(now * 23.7 + s * 2.3) + 0.05 * sin(now * 47.0 + s * 5.1))
	light.visible = true
	light.light_energy = float(f.energy) * flick
	light.shadow_enabled = pp.distance_squared_to(light.position) < 625.0

# ------------------------------------------------------------------ the look of a fire
func _on_placed(p: Dictionary) -> void:
	if not p.has("fire"):
		return
	var stove := String(p.station) == "stove"
	var at: Vector3 = p.fire.pos
	var light := OmniLight3D.new()
	light.light_color = LIGHT_COL; light.omni_range = 9.0 if stove else 14.0; light.omni_attenuation = 1.3
	light.light_energy = 0.0; light.visible = false
	light.position = at + Vector3(0, 0.25 if stove else 0.6, 0)
	if stove:
		# the firebox door faces the front of the stove
		light.position += Basis(Vector3.UP, int(p.rot) * PI / 2.0) * Vector3(0, 0, 0.45)
	add_child(light)
	var flames := _particles(28 if not stove else 10, 0.7, at + Vector3(0, 0.08, 0), 0.16 if not stove else 0.08)
	flames.direction = Vector3.UP; flames.spread = 14.0
	flames.initial_velocity_min = 0.6; flames.initial_velocity_max = 1.25
	flames.gravity = Vector3(0, 1.4, 0)
	flames.scale_amount_min = 0.2 if not stove else 0.1; flames.scale_amount_max = 0.36 if not stove else 0.16
	flames.scale_amount_curve = _curve([[0.0, 0.5], [0.2, 1.0], [1.0, 0.0]])
	flames.color_ramp = _ramp([[0.0, Color(1.0, 0.88, 0.5, 0.0)], [0.12, Color(1.0, 0.78, 0.32, 0.9)], [0.5, Color(1.0, 0.42, 0.1, 0.75)], [1.0, Color(0.6, 0.1, 0.02, 0.0)]])
	flames.mesh = _quad(true)
	var smoke := _particles(18, 3.4, at + Vector3(0, 0.55 if not stove else 0.62, 0), 0.1)
	smoke.direction = Vector3.UP; smoke.spread = 10.0
	smoke.initial_velocity_min = 0.45; smoke.initial_velocity_max = 0.8
	smoke.gravity = Vector3(0.12, 0.25, 0.05)
	smoke.scale_amount_min = 0.35; smoke.scale_amount_max = 0.6
	smoke.scale_amount_curve = _curve([[0.0, 0.35], [1.0, 1.6]])
	smoke.color_ramp = _ramp([[0.0, Color(0.32, 0.3, 0.28, 0.0)], [0.15, Color(0.32, 0.3, 0.28, 0.3)], [1.0, Color(0.45, 0.45, 0.45, 0.0)]])
	smoke.mesh = _quad(false)
	var sparks := _particles(7, 1.3, at + Vector3(0, 0.2, 0), 0.12)
	sparks.direction = Vector3.UP; sparks.spread = 30.0
	sparks.initial_velocity_min = 1.0; sparks.initial_velocity_max = 2.4
	sparks.gravity = Vector3(0, 0.3, 0)
	sparks.scale_amount_min = 0.02; sparks.scale_amount_max = 0.04
	sparks.color_ramp = _ramp([[0.0, Color(1.0, 0.8, 0.4, 1.0)], [1.0, Color(1.0, 0.3, 0.05, 0.0)]])
	sparks.mesh = _quad(true)
	sparks.visible = not stove
	var sound := AudioStreamPlayer3D.new()
	sound.stream = _crackle()
	sound.volume_db = -5.0; sound.unit_size = 3.0; sound.max_distance = 28.0
	sound.attenuation_model = AudioStreamPlayer3D.ATTENUATION_INVERSE_DISTANCE
	sound.position = at
	add_child(sound)
	fires.append({"piece": p, "light": light, "flames": flames, "smoke": smoke, "sparks": sparks, "sound": sound, "on": false, "seed": randf() * 20.0, "energy": 1.4 if stove else 3.2})

func _on_removed(p: Dictionary) -> void:
	for i in range(fires.size() - 1, -1, -1):
		var f: Dictionary = fires[i]
		if is_same(f.piece, p):
			for k in ["light", "flames", "smoke", "sparks", "sound"]:
				f[k].queue_free()
			fires.remove_at(i)
	if open_piece != null and is_same(open_piece, p):
		open_piece = null
		if game.hud.is_open() and game.hud.window_kind == "cook":
			U.close(game)

func _particles(amount: int, life: float, at: Vector3, radius: float) -> CPUParticles3D:
	var c := CPUParticles3D.new()
	c.amount = amount; c.lifetime = life; c.emitting = false
	c.emission_shape = CPUParticles3D.EMISSION_SHAPE_SPHERE; c.emission_sphere_radius = radius
	c.position = at
	c.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(c)
	return c

func _quad(additive: bool) -> QuadMesh:
	var q := QuadMesh.new(); q.size = Vector2.ONE
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.vertex_color_use_as_albedo = true
	m.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.albedo_texture = _soft_dot()
	if additive:
		m.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	q.material = m
	return q

static var _dot: GradientTexture2D = null
static func _soft_dot() -> GradientTexture2D:
	if _dot == null:
		var g := Gradient.new()
		g.set_color(0, Color(1, 1, 1, 1)); g.set_color(1, Color(1, 1, 1, 0))
		_dot = GradientTexture2D.new()
		_dot.gradient = g; _dot.width = 48; _dot.height = 48
		_dot.fill = GradientTexture2D.FILL_RADIAL; _dot.fill_from = Vector2(0.5, 0.5); _dot.fill_to = Vector2(1.0, 0.5)
	return _dot

func _curve(points: Array) -> Curve:
	var c := Curve.new()
	c.max_value = 2.0
	for pt in points:
		c.add_point(Vector2(pt[0], pt[1]))
	return c

func _ramp(stops: Array) -> Gradient:
	var g := Gradient.new()
	var offs := PackedFloat32Array(); var cols := PackedColorArray()
	for s in stops:
		offs.append(s[0]); cols.append(s[1])
	g.offsets = offs; g.colors = cols
	return g

func _crackle() -> AudioStream:
	if _loop == null:
		var path := "res://assets/sfx/loop_campfire.wav"
		if ResourceLoader.exists(path):
			var st = load(path)
			if st is AudioStreamWAV:
				st = st.duplicate()
				st.loop_mode = AudioStreamWAV.LOOP_FORWARD
				st.loop_begin = 0
				st.loop_end = int(st.get_length() * st.mix_rate)
			_loop = st
	return _loop

# ------------------------------------------------------------------ the cooking window
func open(p: Dictionary) -> void:
	open_piece = p
	_build_window(true)

func _rebuild() -> void:
	if open_piece != null and game.hud.is_open() and game.hud.window_kind == "cook":
		_build_window(false)

func recipes_for(p: Dictionary) -> Array:
	var craft = U.mod(game, "sys_craft")
	var out: Array = []
	if craft == null:
		return out
	var stove := String(p.station) == "stove"
	for r in craft.recipes:
		var st := String(r.station)
		if st == ("stove" if stove else "campfire") or (stove and st == "campfire" and String(r.id) != "charcoal"):
			out.append(r)
	return out

func _build_window(sound: bool) -> void:
	var hud = game.hud
	var p: Dictionary = open_piece
	var craft = U.mod(game, "sys_craft")
	var at := String(p.station)
	var v: VBoxContainer = U.open(game, "cook", 940.0, 600.0, fire_name(p), sound)
	var cols := HBoxContainer.new(); cols.add_theme_constant_override("separation", 18); cols.size_flags_vertical = Control.SIZE_EXPAND_FILL
	v.add_child(cols)
	# ---- the fire
	var left := VBoxContainer.new(); left.custom_minimum_size = Vector2(300, 0); left.add_theme_constant_override("separation", 8)
	cols.add_child(left)
	left.add_child(U.caption(hud, "Fire"))
	_status = hud.label("", 20)
	left.add_child(_status)
	_fuel_bar = hud._bar(Hud.ACCENT, 300.0, 8.0)
	left.add_child(_fuel_bar)
	_fuel_lbl = hud.label("", 14, Hud.DIM)
	left.add_child(_fuel_lbl)
	_lit_shown = bool(p.fire.lit)
	if not p.fire.lit:
		var lb: Button = hud.button("Light it", func() -> void:
			light(p); _build_window(false), 300.0)
		lb.focus_mode = Control.FOCUS_NONE
		left.add_child(lb)
		var need := ""
		if not game.player.inv.has_tool("fire"): need = "You need a lighter."
		elif float(p.fire.fuel) <= 0.0: need = "Lay some fuel first."
		if need != "":
			left.add_child(hud.label(need, 13, Hud.BAD))
	left.add_child(U.caption(hud, "Add fuel"))
	var any := false
	for id in fuels():
		any = true
		var d := Items.def(String(id))
		var fid := String(id)
		var fb: Button = U.row_button(hud, 40.0)
		var h := HBoxContainer.new(); h.set_anchors_preset(Control.PRESET_FULL_RECT); h.offset_left = 8; h.offset_right = -10
		h.add_theme_constant_override("separation", 8); h.mouse_filter = Control.MOUSE_FILTER_IGNORE
		h.add_child(U.item_icon(hud, fid, 28.0))
		var nl: Label = hud.label("%s ×%d" % [String(d.name), game.player.inv.count(fid)], 15); nl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		h.add_child(nl)
		h.add_child(hud.label("+%d min" % int(float(d.fuel) * 3.0), 13, Hud.DIM))
		fb.add_child(h)
		fb.pressed.connect(func() -> void:
			if add_fuel(p, fid): _build_window(false))
		left.add_child(fb)
	if not any:
		left.add_child(hud.label("Nothing to burn: logs, planks or biomass.", 14, Hud.DIM))
	var gap := Control.new(); gap.size_flags_vertical = Control.SIZE_EXPAND_FILL
	left.add_child(gap)
	var note: Label = hud.label("A fire keeps you warm and dries you off, cooks food and boils dirty water. Rain may put an open fire out." if at == "campfire" else "A stove cooks proper meals and keeps a room warm. It burns wood like a campfire and is safe indoors.", 12, Hud.DIM)
	note.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; note.custom_minimum_size = Vector2(300, 0)
	left.add_child(note)
	# ---- what can be cooked
	var right := VBoxContainer.new(); right.size_flags_horizontal = Control.SIZE_EXPAND_FILL; right.add_theme_constant_override("separation", 6)
	cols.add_child(right)
	right.add_child(U.caption(hud, "Cook"))
	var sc := ScrollContainer.new(); sc.size_flags_vertical = Control.SIZE_EXPAND_FILL; sc.custom_minimum_size = Vector2(540, 400)
	sc.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	var rows := VBoxContainer.new(); rows.size_flags_horizontal = Control.SIZE_EXPAND_FILL; rows.add_theme_constant_override("separation", 3)
	sc.add_child(rows); right.add_child(sc)
	var ready: Array = []; var rest: Array = []
	for r in recipes_for(p):
		var why: String = craft.can_craft(r, at, p)
		if why == "": ready.append([r, why])
		else: rest.append([r, why])
	for e in ready + rest:
		rows.add_child(_row(e[0], String(e[1]), at, p))
	var foot := HBoxContainer.new(); foot.add_theme_constant_override("separation", 12)
	var hint: Label = hud.label("Click a dish to cook it   ·   Esc: close", 12, Hud.DIM); hint.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	foot.add_child(hint)
	_bar_lbl = hud.label("", 13); _bar_lbl.visible = false
	_bar = hud._bar(Hud.ACCENT, 240.0, 8.0); _bar.visible = false; _bar.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	foot.add_child(_bar_lbl); foot.add_child(_bar)
	v.add_child(foot)
	_refresh_window(1.0)

func _row(r: Dictionary, why: String, at: String, p: Dictionary) -> Control:
	var hud = game.hud
	var craft = U.mod(game, "sys_craft")
	var out_id := String(r.out[0])
	var b: Button = U.row_button(hud, 50.0)
	var h := HBoxContainer.new(); h.set_anchors_preset(Control.PRESET_FULL_RECT); h.offset_left = 8; h.offset_right = -10
	h.add_theme_constant_override("separation", 10); h.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_child(U.item_icon(hud, out_id, 34.0))
	var mid := VBoxContainer.new(); mid.size_flags_horizontal = Control.SIZE_EXPAND_FILL; mid.alignment = BoxContainer.ALIGNMENT_CENTER
	mid.add_theme_constant_override("separation", 0); mid.mouse_filter = Control.MOUSE_FILTER_IGNORE
	mid.add_child(hud.label(String(Items.def(out_id).name) + (" ×%d" % int(r.out[1]) if int(r.out[1]) > 1 else ""), 16, Hud.INK if why == "" else Hud.DIM))
	mid.add_child(U.cost_row(hud, game.player.inv, U.cost_of(r.inp), 12))
	h.add_child(mid)
	h.add_child(hud.label("%d s" % int(ceil(craft.craft_time(r))) if why == "" else why, 12, Hud.DIM if why == "" else Hud.BAD))
	b.add_child(h)
	b.pressed.connect(func() -> void: craft.craft(r, at, p))
	return b

func _refresh_window(dt: float) -> void:
	if open_piece == null or _status == null or not is_instance_valid(_status):
		return
	var p: Dictionary = open_piece
	if bool(p.fire.lit) != _lit_shown:
		_build_window(false)
		return
	var a = game.action
	var busy: bool = a != null and String(a.label).begins_with("Cooking")
	_bar.visible = busy; _bar_lbl.visible = busy
	if busy:
		_bar.value = float(a.t) / maxf(0.01, float(a.dur)) * 100.0
		_bar_lbl.text = String(a.label)
	_tick += dt
	if _tick < 0.25:
		return
	_tick = 0.0
	_status.text = "Burning" if p.fire.lit else "Cold"
	_status.add_theme_color_override("font_color", Hud.GOOD if p.fire.lit else Hud.INK)
	_fuel_bar.value = clampf(float(p.fire.fuel) / 180.0, 0.0, 1.0) * 100.0
	_fuel_lbl.text = "%d min of fuel" % int(ceil(float(p.fire.fuel))) if float(p.fire.fuel) > 0.0 else "No fuel"
