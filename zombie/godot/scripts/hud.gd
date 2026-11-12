class_name Hud
extends CanvasLayer
## Everything drawn over the world: the play HUD (vitals, compass, clock, hotbar, prompts, notifications,
## minimap), the inventory and health windows, and the menus. Built in code so the project has no scene files
## to keep in sync. The look lives in UiTheme; the larger screens live in ui_inventory.gd, ui_health.gd and
## ui_menus.gd; this file is the surface the rest of the game talks to.

signal use_item(uid: int)
signal drop_item(uid: int)
signal move_item(uid: int, to_container: bool)
signal assign_hotbar(uid: int, slot: int)
signal treat_wound(part: String, index: int, item_id: String)
signal take_all
signal menu_action(action: String)

const INK := UiTheme.INK
const DIM := UiTheme.DIM
const PANEL := UiTheme.PANEL
const ACCENT := UiTheme.ACCENT
const BAD := UiTheme.BAD
const GOOD := UiTheme.GOOD
const EDGE := 28.0                    # the margin every HUD element keeps from the screen edge
const TABS := [["inv", "Inventory", "Tab"], ["health", "Health", "H"], ["map", "Map", "M"]]

var game
var root: Control
var icons: Dictionary = {}
var _icon_cache: Dictionary = {}
var window: Control = null
var window_kind := ""
var container = null               # the open container: {label, inv}
var hover_uid := -1
var hover_in_container := false
## what the player chose on the character screen: {name, occupation, traits: Array}. Set just before
## menu_action("new") is emitted.
var new_game_setup: Dictionary = {}

var play: Control                  # everything that only shows while playing
var inventory: UiInventory
var health: UiHealth
var menus: UiMenus
var loading: Control = null
var clock_lbl: Label
var sub_lbl: Label
var temp_lbl: Label
var sky_glyph: UiKit.Glyph
var compass: UiHudParts.Compass
var stealth: UiHudParts.Stealth
var vitals: UiHudParts.Vitals
var hotbar: UiHudParts.Hotbar
var weapon: UiHudParts.Weapon
var crosshair: UiHudParts.Crosshair
var ring: UiHudParts.DamageRing
var prompt: UiHudParts.Prompt
var action_box: UiHudParts.ActionBar
var fx: UiHudParts.ScreenFx
var notes: VBoxContainer
var side: VBoxContainer
var minimap: UiMap.View = null
var minimap_frame: Control
var place_lbl: Label
var obj_box: VBoxContainer
var obj_title: Label
var obj_desc: Label
var moodle_box: VBoxContainer
var held_lbl: Label

var hit_t := 0.0
var hit_kill := false
var flash_a := 0.0
var _flash_frame := -1
var _ring_frame := -1
var _wired_zombies = null
var _clock_min := -1
var _sub_key := -1
var _temp_key := -999
var _day_part := -1
var _slow := 0.0
var _slower := 0.0
var _time := 0.0
var _prompt_raw := "\u0001"
var _prompt_target = null
var _prompt_key := ""
var _obj_text := "\u0001"
var _obj_age := 0.0
var _moodle_sig := ""
var _moodle_level: Dictionary = {}
var _moodle_tell: Dictionary = {}
var _spread := 0.0
var _kick := 0.0
var _ammo_uid := -1
var _ammo_n := -1
var _reload_peak := 0.0
var _held_uid := -2
var _held_t := 0.0
var _threats := PackedFloat32Array()
var _chasers := PackedVector2Array()
var _marks_n := -1
var _load_tip := 0
var _load_t := 0.0

func _ready() -> void:
	layer = 5
	var idx = World.read_json("res://assets/icons/index.json")
	if idx is Dictionary:
		icons = idx
	var th := UiTheme.theme()
	get_tree().root.theme = th          # pop-ups and tooltips live outside this layer; they follow the same look
	root = Control.new(); root.set_anchors_preset(Control.PRESET_FULL_RECT); root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.theme = th
	root.texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
	add_child(root)
	inventory = UiInventory.new(self)
	health = UiHealth.new(self)
	menus = UiMenus.new(self)
	_build_hud()
	Settings.changed.connect(_on_setting)

# ------------------------------------------------------------------ small builders
## an icon from assets/icons (item_<id>, m_<moodle>, ui_*, skill_<id>), toned down to sit with the palette
func icon(key: String) -> Texture2D:
	if _icon_cache.has(key):
		return _icon_cache[key]
	var tex: Texture2D = null
	if icons.has(key):
		var src = load("res://assets/icons/" + String(icons[key]))
		if src is Texture2D:
			tex = src
			var img: Image = src.get_image()
			if img != null and not img.is_empty():
				if img.is_compressed():
					img.decompress()
				img.convert(Image.FORMAT_RGBA8)
				img.adjust_bcs(0.94, 1.04, 0.62)
				img.generate_mipmaps()
				tex = ImageTexture.create_from_image(img)
	_icon_cache[key] = tex
	return tex

static func box(col: Color, radius := 6, border := Color(0, 0, 0, 0), bw := 0) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = col; s.set_corner_radius_all(radius)
	s.border_color = border; s.set_border_width_all(bw)
	s.set_content_margin_all(8)
	return s

func label(text: String, size := 16, col := INK) -> Label:
	var l := Label.new(); l.text = text
	l.add_theme_font_size_override("font_size", size); l.add_theme_color_override("font_color", col)
	l.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.4)); l.add_theme_constant_override("outline_size", 4)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return l

func _bar(col: Color, w := 220.0, h := 8.0) -> ProgressBar:
	var b := ProgressBar.new(); b.show_percentage = false; b.custom_minimum_size = Vector2(w, h); b.max_value = 100.0
	b.add_theme_stylebox_override("background", UiTheme.flat(Color(0, 0, 0, 0.55), 0, Color(0, 0, 0, 0), 0, 0.0))
	b.add_theme_stylebox_override("fill", UiTheme.flat(col, 0, Color(0, 0, 0, 0), 0, 0.0))
	b.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return b

func button(text: String, cb: Callable, w := 260.0) -> Button:
	var b := Button.new(); b.text = text.to_upper(); b.custom_minimum_size = Vector2(w, 38)
	b.focus_mode = Control.FOCUS_NONE      # so Tab, Esc and Space stay with the game while a window is open
	b.pressed.connect(cb)
	b.pressed.connect(click)
	return b

func click() -> void:
	Sfx.play("ui_click", {"volume": 0.5})

static func place(c: Control, ax: float, ay: float, x: float, y: float, w := 0.0, h := 0.0) -> void:
	c.anchor_left = ax; c.anchor_right = ax; c.anchor_top = ay; c.anchor_bottom = ay
	c.offset_left = x; c.offset_top = y; c.offset_right = x + w; c.offset_bottom = y + h

# ------------------------------------------------------------------ always-on HUD
func _build_hud() -> void:
	play = Control.new(); play.set_anchors_preset(Control.PRESET_FULL_RECT); play.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(play)

	# top left: the time, the day, the weather on your skin
	var tl := VBoxContainer.new(); tl.mouse_filter = Control.MOUSE_FILTER_IGNORE; tl.add_theme_constant_override("separation", -2)
	place(tl, 0, 0, EDGE, EDGE - 10.0)
	var trow := HBoxContainer.new(); trow.mouse_filter = Control.MOUSE_FILTER_IGNORE; trow.add_theme_constant_override("separation", 10)
	clock_lbl = label("08:30", 40); clock_lbl.add_theme_font_override("font", UiTheme.font("num"))
	sky_glyph = UiKit.Glyph.new("sun", 20.0, UiTheme.ACCENT2); sky_glyph.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	trow.add_child(clock_lbl); trow.add_child(sky_glyph)
	var srow := HBoxContainer.new(); srow.mouse_filter = Control.MOUSE_FILTER_IGNORE; srow.add_theme_constant_override("separation", 10)
	sub_lbl = label("", 14, INK); sub_lbl.add_theme_font_override("font", UiTheme.font("head"))
	temp_lbl = label("", 14, DIM); temp_lbl.add_theme_font_override("font", UiTheme.font("head"))
	srow.add_child(sub_lbl); srow.add_child(temp_lbl)
	tl.add_child(trow); tl.add_child(srow); play.add_child(tl)

	# top centre: compass, and under it how noticeable you are
	compass = UiHudParts.Compass.new(); place(compass, 0.5, 0, -280.0, EDGE - 12.0, 560.0, 56.0); play.add_child(compass)
	stealth = UiHudParts.Stealth.new(); place(stealth, 0.5, 0, -120.0, EDGE + 52.0, 240.0, 24.0); play.add_child(stealth)

	# top right: minimap, where you are, what to do next, how you feel
	side = VBoxContainer.new(); side.mouse_filter = Control.MOUSE_FILTER_IGNORE; side.add_theme_constant_override("separation", 6)
	side.grow_horizontal = Control.GROW_DIRECTION_BEGIN
	place(side, 1, 0, -EDGE - 250.0, EDGE, 250.0, 0.0)
	minimap_frame = Control.new(); minimap_frame.custom_minimum_size = Vector2(178, 178); minimap_frame.size_flags_horizontal = Control.SIZE_SHRINK_END
	minimap_frame.mouse_filter = Control.MOUSE_FILTER_IGNORE
	minimap = UiMap.View.new(game, true); minimap.position = Vector2(1, 1); minimap.size = Vector2(176, 176); minimap.zoom = 0.85
	minimap.turn = bool(Settings.v("minimap_rotate"))
	minimap_frame.add_child(minimap)
	minimap_frame.draw.connect(func() -> void:
		minimap_frame.draw_rect(Rect2(0, 0, 178, 178), Color(0, 0, 0, 0.7), false, 1.0)
		UiKit.corners(minimap_frame, Rect2(-3, -3, 184, 184), Color(UiTheme.INK, 0.6), 10.0))
	side.add_child(minimap_frame)
	place_lbl = UiKit.caption("", UiTheme.INK, 11); place_lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	place_lbl.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.45)); place_lbl.add_theme_constant_override("outline_size", 4)
	side.add_child(place_lbl)
	minimap_frame.visible = bool(Settings.v("minimap")); place_lbl.visible = minimap_frame.visible
	obj_box = VBoxContainer.new(); obj_box.mouse_filter = Control.MOUSE_FILTER_IGNORE; obj_box.add_theme_constant_override("separation", 0)
	var oc := UiKit.caption("Objective", UiTheme.ACCENT2, 11); oc.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	oc.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.45)); oc.add_theme_constant_override("outline_size", 4)
	obj_title = label("", 16); obj_title.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	obj_desc = label("", 13, DIM); obj_desc.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT; obj_desc.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	obj_desc.custom_minimum_size = Vector2(250, 0)
	obj_box.add_child(UiKit.spacer(6)); obj_box.add_child(oc); obj_box.add_child(obj_title); obj_box.add_child(obj_desc)
	obj_box.visible = false
	side.add_child(obj_box)
	side.add_child(UiKit.spacer(6))
	moodle_box = VBoxContainer.new(); moodle_box.mouse_filter = Control.MOUSE_FILTER_IGNORE; moodle_box.add_theme_constant_override("separation", 5)
	side.add_child(moodle_box)
	play.add_child(side)

	# bottom left: vitals, and above them what just happened
	vitals = UiHudParts.Vitals.new(); place(vitals, 0, 1, EDGE, -EDGE - 94.0, 286.0, 96.0); play.add_child(vitals)
	notes = VBoxContainer.new(); notes.mouse_filter = Control.MOUSE_FILTER_IGNORE; notes.add_theme_constant_override("separation", 4)
	notes.alignment = BoxContainer.ALIGNMENT_END; notes.grow_vertical = Control.GROW_DIRECTION_BEGIN
	place(notes, 0, 1, EDGE, -EDGE - 124.0, 440.0, 0.0)
	play.add_child(notes)

	# bottom centre: hotbar
	hotbar = UiHudParts.Hotbar.new(self)
	var hw := hotbar.custom_minimum_size.x
	place(hotbar, 0.5, 1, -hw * 0.5, -EDGE - 62.0, hw, 62.0); play.add_child(hotbar)
	held_lbl = label("", 14); held_lbl.add_theme_font_override("font", UiTheme.font("head")); held_lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	place(held_lbl, 0.5, 1, -200.0, -EDGE - 92.0, 400.0, 22.0); held_lbl.modulate.a = 0.0; play.add_child(held_lbl)

	# bottom right: what is in your hands
	weapon = UiHudParts.Weapon.new(); place(weapon, 1, 1, -EDGE - 260.0, -EDGE - 82.0, 260.0, 84.0); play.add_child(weapon)

	# centre
	ring = UiHudParts.DamageRing.new(); place(ring, 0.5, 0.5, 0, 0); play.add_child(ring)
	crosshair = UiHudParts.Crosshair.new(); place(crosshair, 0.5, 0.5, 0, 0); play.add_child(crosshair)
	prompt = UiHudParts.Prompt.new(); place(prompt, 0.5, 0.5, 0, 46.0); play.add_child(prompt)
	action_box = UiHudParts.ActionBar.new(); place(action_box, 0.5, 0.5, 0, 118.0); play.add_child(action_box)

	fx = UiHudParts.ScreenFx.new(); fx.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.add_child(fx)

func _on_setting(key: String) -> void:
	if key == "minimap" or key == "minimap_rotate":
		minimap_frame.visible = bool(Settings.v("minimap")); place_lbl.visible = minimap_frame.visible
		minimap.turn = bool(Settings.v("minimap_rotate")); minimap.queue_redraw()

func hitmark(killed: bool) -> void:
	hit_t = 0.45 if killed else 0.22; hit_kill = killed
	crosshair.hit_t = hit_t; crosshair.hit_kill = killed; crosshair.queue_redraw()

func damage_flash(amount := 0.6) -> void:
	flash_a = minf(0.75, flash_a + amount)
	_flash_frame = Engine.get_process_frames()

## shows which way a hit came from: a red arc on that side of the crosshair
func damage_from(world_pos: Vector3) -> void:
	if game == null or game.player == null:
		return
	var p: Player = game.player
	_ring_frame = Engine.get_process_frames()
	ring.add(rad_to_deg(atan2(world_pos.x - p.pos.x, -(world_pos.z - p.pos.z))))

func _zombie_attacked(z, _dmg = 0.0) -> void:
	# the game flashes the screen when the blow lands; point at where it came from unless it already did
	if _flash_frame == Engine.get_process_frames() and _ring_frame != _flash_frame and z != null:
		damage_from(z.pos)

# ------------------------------------------------------------------ notifications
func notify(text: String, kind := "") -> void:
	var n := notes.get_child_count()
	if n > 0:
		var last: Control = notes.get_child(n - 1)
		if String(last.get_meta("text", "")) == text and float(last.get_meta("ttl", 0.0)) > 0.8:
			var c := int(last.get_meta("count", 1)) + 1
			last.set_meta("count", c); last.set_meta("ttl", 6.0)
			(last.get_meta("label") as Label).text = "%s  ×%d" % [text, c]
			return
	var col := UiTheme.INK; var bar_col := Color(UiTheme.INK, 0.35)
	if kind == "bad": col = Color(1.0, 0.72, 0.64); bar_col = UiTheme.BAD
	elif kind == "good": col = Color(0.8, 0.92, 0.66); bar_col = UiTheme.GOOD
	elif kind == "xp": col = UiTheme.ACCENT2; bar_col = UiTheme.ACCENT
	var sb := UiTheme.flat_m(Color(0.02, 0.022, 0.02, 0.62), bar_col, 0, 12, 6, 0)
	sb.border_width_left = 3
	var p := PanelContainer.new(); p.add_theme_stylebox_override("panel", sb); p.mouse_filter = Control.MOUSE_FILTER_IGNORE
	p.size_flags_horizontal = Control.SIZE_SHRINK_BEGIN
	var l := Label.new(); l.text = text; l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	l.add_theme_font_size_override("font_size", 15); l.add_theme_color_override("font_color", col)
	if UiKit.text_w("body", text, 15) > 400.0:
		l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; l.custom_minimum_size = Vector2(400, 0)
	p.add_child(l); notes.add_child(p)
	p.set_meta("text", text); p.set_meta("label", l); p.set_meta("count", 1)
	p.set_meta("ttl", 5.5 + minf(4.0, text.length() * 0.035)); p.set_meta("age", 0.0)
	p.modulate.a = 0.0
	if notes.get_child_count() > 5:
		var old: Control = notes.get_child(0)
		old.set_meta("ttl", minf(float(old.get_meta("ttl", 0.0)), 0.25))

func _tick_notes(dt: float) -> void:
	var n := notes.get_child_count()
	for i in range(n - 1, -1, -1):
		var c: Control = notes.get_child(i)
		var ttl := float(c.get_meta("ttl", 0.0)) - dt
		var age := float(c.get_meta("age", 0.0)) + dt
		c.set_meta("ttl", ttl); c.set_meta("age", age)
		if ttl <= 0.0:
			c.queue_free()
			continue
		# newest at full strength; older lines step back
		var want := (1.0 if i == n - 1 else 0.66) * minf(1.0, age * 7.0) * minf(1.0, ttl / 0.7)
		if absf(c.modulate.a - want) > 0.01:
			c.modulate.a = move_toward(c.modulate.a, want, dt * 5.0)

# ------------------------------------------------------------------ prompt and action
func set_prompt(text: String) -> void:
	var tgt = game.get("target") if game != null else null
	if text == _prompt_raw and is_same(tgt, _prompt_target):
		return
	_prompt_raw = text; _prompt_target = tgt
	_prompt_key = ""
	if text == "":
		prompt.set_prompt("", "", "")
		return
	var key := ""; var verb := text
	if text.begins_with("["):
		var e := text.find("]")
		if e > 0:
			key = text.substr(1, e - 1); verb = text.substr(e + 1).strip_edges()
	var nm := ""; var sub := ""
	if tgt is Dictionary:
		match String(tgt.get("kind", "")):
			"container":
				nm = String(tgt.get("label", ""))
				if tgt.get("searched", false) and tgt.has("inv") and tgt.inv.stacks.is_empty(): sub = "Empty"
			"drop": nm = String(tgt.get("label", ""))
			"door": nm = "Locked door" if (tgt.get("locked", false) and not tgt.get("open", false)) else "Door"
			"window": nm = "Window"
			"water": nm = "Lake"
	if nm != "":
		var low := verb.to_lower()
		if low == nm.to_lower():
			verb = ""
		elif String(tgt.get("kind", "")) == "water":
			verb = "Drink"
		elif low.ends_with(" " + nm.to_lower()):
			verb = verb.substr(0, verb.length() - nm.length()).strip_edges()
			if verb.to_lower().ends_with(" the"):
				verb = verb.substr(0, verb.length() - 4)
		elif String(tgt.get("kind", "")) == "door" and low.ends_with(" door"):
			verb = verb.substr(0, verb.length() - 5)
	_prompt_key = key
	prompt.set_prompt(key, verb, nm, sub)

func set_action(text: String, progress: float) -> void:
	action_box.set_action(text, progress)
	if window != null and window_kind == "inv":
		inventory.show_action(text, progress)

func is_open() -> bool:
	return window != null

# ------------------------------------------------------------------ per-frame refresh
func _process(dt: float) -> void:
	_time += dt
	if notes != null and notes.get_child_count() > 0:
		_tick_notes(dt)
	if loading != null and loading.visible:
		_load_t += dt
		if _load_t > 6.0:
			_load_t = 0.0
			menus.next_tip(loading)

func update_hud(dt: float) -> void:
	if game == null or game.player == null:
		return
	var p: Player = game.player
	var st := p.stats
	var hp := st.health()
	var open := window != null

	# screen edge: the blow, then how badly off you are
	flash_a = maxf(0.0, flash_a - dt * 0.9)
	var bleed := st.bleeding()
	fx.update(dt, flash_a, clampf((38.0 - hp) / 38.0, 0.0, 1.0), clampf(bleed / 3.0, 0.0, 1.0) * 0.85)

	# clock: text only when the minute turns
	var hrs: float = game.hours()
	var minute := int(hrs * 60.0)
	if minute != _clock_min:
		_clock_min = minute
		clock_lbl.text = "%02d:%02d" % [minute / 60, minute % 60]
		var part := 1 if (hrs >= 6.0 and hrs < 20.0) else 0
		if part != _day_part:
			_day_part = part
			sky_glyph.kind = "sun" if part == 1 else "moon"
			sky_glyph.color = UiTheme.ACCENT2 if part == 1 else Color(0.75, 0.82, 0.92)
			sky_glyph.queue_redraw()
		var key: int = game.day() * 8 + game.season()
		if key != _sub_key:
			_sub_key = key
			sub_lbl.text = "DAY %d  ·  %s" % [game.day(), game.season_name().to_upper()]
		var temp := int(round(game.ambient_temp()))
		if temp != _temp_key:
			_temp_key = temp
			temp_lbl.text = "%d°C" % temp
			temp_lbl.add_theme_color_override("font_color", UiTheme.BLUE if temp <= 2 else (UiTheme.WARN if temp >= 28 else DIM))

	vitals.set_values(hp, st.stamina / maxf(1.0, st.max_stamina) * 100.0, st.hunger, st.thirst, clampf(st.max_stamina / 100.0, 0.2, 1.0), 0.5 + 0.5 * sin(_time * 6.0))
	hotbar.refresh(p)
	var rl = game.get("reloading")
	var reloading: bool = rl != null and float(rl) > 0.0
	weapon.refresh(p, reloading)

	# the name of what you just took in hand, briefly
	var eq_uid := -1 if p.equipped == null else int(p.equipped)
	if eq_uid != _held_uid:
		var es0 = p.equipped_stack()
		if _held_uid != -2 and es0 != null:
			held_lbl.text = String(Items.def(es0.id).name).to_upper(); _held_t = 2.2
		elif es0 == null:
			_held_t = 0.0
		_held_uid = eq_uid
	if _held_t > 0.0 or held_lbl.modulate.a > 0.0:
		_held_t = maxf(0.0, _held_t - dt)
		held_lbl.modulate.a = move_toward(held_lbl.modulate.a, 1.0 if _held_t > 0.4 else 0.0, dt * 4.0)

	# compass and what rides on it
	var heading := fposmod(rad_to_deg(-p.yaw), 360.0)
	compass.set_heading(heading)
	ring.tick(dt, heading)
	if minimap_frame.visible:
		minimap.track(p.pos, p.yaw, dt)

	# crosshair
	hit_t = maxf(0.0, hit_t - dt)
	crosshair.hit_t = hit_t
	if crosshair.visible == open:
		crosshair.visible = not open
	if not open:
		var es = p.equipped_stack()
		var w = Items.def(es.id).get("weapon") if es != null else null
		var gun: bool = w != null and w.has("ammo")
		var ads := 0.0
		var vm = game.get("vm")
		if vm != null and vm.get("ads") != null: ads = float(vm.ads)
		var want := 0.0
		if gun:
			if int(es.uid) == _ammo_uid and int(es.get("ammo", 0)) < _ammo_n:
				_kick = minf(18.0, _kick + 9.0)
			_ammo_uid = int(es.uid); _ammo_n = int(es.get("ammo", 0))
			want = float(w.get("spread", 0.02)) * 260.0 * (1.8 if p.speed > 1.0 else 1.0) + st.aim_sway() * 220.0 + (5.0 if p.sprinting else 0.0) + (4.0 if not p.on_ground else 0.0)
			want *= lerpf(1.0, 0.35, ads)
		_kick = maxf(0.0, _kick - dt * 40.0)
		_spread = lerpf(_spread, want + _kick, 1.0 - exp(-dt * 14.0))
		var reload_frac := -1.0
		if reloading:
			_reload_peak = maxf(_reload_peak, float(rl))
			reload_frac = 1.0 - float(rl) / maxf(0.01, _reload_peak)
		else:
			_reload_peak = 0.0
		crosshair.set_state(_spread, 2 if (_prompt_key != "" and ads < 0.3) else (1 if gun else 0), ads, reload_frac)
	prompt.tick(dt)

	# five times a second: the things that do not need to be exact to the frame
	_slow -= dt
	if _slow <= 0.0:
		_slow = 0.2
		_slow_tick(p, st)
	_slower -= dt
	if _slower <= 0.0:
		_slower = 0.5
		_objective(game.objective_text())
		if place_lbl.visible:
			var pn := UiMap.place_name(p.pos.x, p.pos.z)
			if pn != place_lbl.text: place_lbl.text = pn
	if obj_box.visible:
		_obj_age += dt
		var da := 1.0 if _obj_age < 16.0 else 0.0
		if obj_desc.modulate.a != da:
			obj_desc.modulate.a = move_toward(obj_desc.modulate.a, da, dt * 0.8)
			if obj_desc.modulate.a <= 0.0 and obj_desc.visible: obj_desc.visible = false
	if open:
		if window_kind == "inv": inventory.tick(dt)
		elif window_kind == "health": health.tick(dt)

func _slow_tick(p: Player, st: Stats) -> void:
	var zs = game.get("zombies")
	var chasing := 0; var seen := 0
	_threats.resize(0); _chasers.resize(0)
	UiMap.prepare(game.get("world"))
	if zs != null:
		if not is_same(zs, _wired_zombies):
			_wired_zombies = zs
			if zs.has_signal("player_attacked") and not zs.is_connected("player_attacked", _zombie_attacked):
				zs.connect("player_attacked", _zombie_attacked)
		var c = zs.get("chasing"); var s = zs.get("seen_count")
		if c != null: chasing = int(c)
		if s != null: seen = int(s)
		var lst = zs.get("list")
		if lst is Array:
			var near := 18.0 if p.traits.has("keen_hearing") else 10.0
			for z in lst:
				var dx: float = z.pos.x - p.pos.x; var dz: float = z.pos.z - p.pos.z
				if absf(dx) > 70.0 or absf(dz) > 70.0:
					continue
				var stt = z.state
				if stt == &"dead" or stt == &"dormant":
					continue
				var d := sqrt(dx * dx + dz * dz)
				if d < near and absf(z.pos.y - p.pos.y) < 4.0:
					_threats.append(rad_to_deg(atan2(dx, -dz))); _threats.append(1.0 - d / near * 0.7)
				if stt == &"chase" or stt == &"attack":
					_chasers.append(Vector2(z.pos.x, z.pos.z))
	compass.threats = _threats
	_compass_marks(p)
	compass.touch()
	minimap.chasers = _chasers
	var loud: float = p.noise
	var lt = game.get("loud_t")
	if lt != null: loud = maxf(loud, minf(16.0, float(lt)))
	var state := 2 if chasing > 0 else (1 if seen > 0 else 0)
	stealth.set_state(p.visibility, loud, state, chasing, p.crouch)
	var sa := 0.95 if (state > 0 or p.crouch) else 0.5
	if stealth.modulate.a != sa: stealth.modulate.a = sa
	_moodles(st.moodles(p.weight_ratio()))

func _compass_marks(p: Player) -> void:
	var n := 2 + UiMap.markers.size()
	if n != _marks_n:
		_marks_n = n
		compass.marks = []
		for i in n:
			compass.marks.append({"bearing": 0.0, "glyph": "house" if i == 0 else ("diamond" if i == 1 else "pin"), "col": UiTheme.ACCENT2 if i == 0 else (UiTheme.INK if i == 1 else UiTheme.BLUE), "label": "", "dist": -1, "hide": false})
	for i in n:
		var m: Dictionary = compass.marks[i]
		var w: Vector2
		var nm := "HOME"
		if i == 0: w = UiMap.home
		elif i == 1:
			w = UiMap.town.get_center(); nm = "TOWN"
		else:
			var pin: Dictionary = UiMap.markers[i - 2]
			w = Vector2(float(pin.x), float(pin.z)); nm = "MARKER"
		var d := Vector2(w.x - p.pos.x, w.y - p.pos.z)
		m.bearing = rad_to_deg(atan2(d.x, -d.y))
		var dist := int(d.length() / 10.0) * 10
		if dist != int(m.dist):
			m.dist = dist
			m.label = "" if (i == 1 and UiMap.town.has_point(Vector2(p.pos.x, p.pos.z))) else ("%s  ·  %d M" % [nm, dist])
		m.hide = i == 1 and UiMap.town.has_point(Vector2(p.pos.x, p.pos.z))

func _objective(text: String) -> void:
	if text == _obj_text:
		return
	var first := _obj_text == "\u0001"
	_obj_text = text
	obj_box.visible = text != ""
	if text == "":
		return
	var nl := text.find("\n")
	obj_title.text = text if nl < 0 else text.substr(0, nl)
	obj_desc.text = "" if nl < 0 else text.substr(nl + 1)
	obj_desc.visible = obj_desc.text != ""; obj_desc.modulate.a = 1.0
	_obj_age = 0.0
	if not first:
		obj_title.add_theme_color_override("font_color", UiTheme.ACCENT2)
		var tw := obj_title.create_tween()
		tw.tween_interval(1.2)
		tw.tween_method(func(k: float) -> void: obj_title.add_theme_color_override("font_color", UiTheme.ACCENT2.lerp(INK, k)), 0.0, 1.0, 1.0)

func _moodles(list: Array) -> void:
	var sig := ""
	for m in list:
		var id := String(m.id); var lv := int(m.level)
		if lv > int(_moodle_level.get(id, 0)):
			_moodle_tell[id] = _time + 7.0          # new, or worse: say what it means for a while
		_moodle_level[id] = lv
		sig += "%s%d%s," % [id, lv, "+" if float(_moodle_tell.get(id, 0.0)) > _time else ""]
	if list.size() < _moodle_level.size():
		var keep := {}
		for m in list: keep[String(m.id)] = true
		for k in _moodle_level.keys():
			if not keep.has(k): _moodle_level.erase(k)
	if sig == _moodle_sig:
		return
	_moodle_sig = sig
	for c in moodle_box.get_children():
		c.queue_free()
	for m in list:
		var lv2 := int(m.level)
		var col := UiTheme.level_color(lv2)
		var h := HBoxContainer.new(); h.mouse_filter = Control.MOUSE_FILTER_IGNORE; h.alignment = BoxContainer.ALIGNMENT_END
		h.add_theme_constant_override("separation", 9)
		var tv := VBoxContainer.new(); tv.mouse_filter = Control.MOUSE_FILTER_IGNORE; tv.add_theme_constant_override("separation", -2)
		tv.alignment = BoxContainer.ALIGNMENT_CENTER
		var nl := label(String(m.label), 14, col); nl.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
		tv.add_child(nl)
		if float(_moodle_tell.get(String(m.id), 0.0)) > _time:
			var dl := label(String(m.desc), 12, UiTheme.INK); dl.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
			dl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; dl.custom_minimum_size = Vector2(196, 0)
			tv.add_child(dl)
		var tile := Control.new(); tile.custom_minimum_size = Vector2(36, 36); tile.mouse_filter = Control.MOUSE_FILTER_IGNORE
		tile.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		var tex := icon("m_" + String(m.id))
		tile.draw.connect(func() -> void:
			tile.draw_rect(Rect2(0, 0, 36, 36), Color(0.02, 0.022, 0.02, 0.6))
			tile.draw_rect(Rect2(0, 0, 36, 36), Color(col, 0.85), false, 1.0)
			if tex != null: tile.draw_texture_rect(tex, Rect2(5, 3, 26, 26), false)
			for i in 4:
				tile.draw_rect(Rect2(4.0 + i * 7.5, 31.0, 5.5, 2.0), col if i < lv2 else Color(1, 1, 1, 0.14)))
		h.add_child(tv); h.add_child(tile); moodle_box.add_child(h)

# ------------------------------------------------------------------ windows
func close_window() -> void:
	if window != null:
		window.queue_free()
	window = null; window_kind = ""; container = null; hover_uid = -1
	inventory.closed(); health.closed(); menus.closed()

func _open(kind: String, w: float, h: float, title: String, tabs := true) -> VBoxContainer:
	var same := window != null and window_kind == kind
	close_window()
	window_kind = kind
	window = Control.new(); window.set_anchors_preset(Control.PRESET_FULL_RECT)
	var shade := ColorRect.new(); shade.set_anchors_preset(Control.PRESET_FULL_RECT); shade.color = Color(0.01, 0.012, 0.01, 0.55); window.add_child(shade)
	var cc := CenterContainer.new(); cc.set_anchors_preset(Control.PRESET_FULL_RECT); cc.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var frame := UiKit.Frame.new(); frame.custom_minimum_size = Vector2(w, h)
	var col := VBoxContainer.new(); col.add_theme_constant_override("separation", 0)
	var tabbed := false
	for t in TABS:
		if t[0] == kind and tabs: tabbed = true
	if title != "" or tabbed:
		var head := HBoxContainer.new(); head.add_theme_constant_override("separation", 4)
		head.custom_minimum_size = Vector2(0, 50)
		head.add_child(UiKit.spacer(0, 14))
		if tabbed and game != null and String(game.get("state")) == "play":
			for t in TABS:
				if t[0] == "map" and not (game.modules is Dictionary and game.modules.has("sys_map")):
					continue
				head.add_child(_tab(String(t[0]), String(t[1]), String(t[2]), t[0] == kind))
		else:
			var tl := UiKit.heading(title, 21, UiTheme.ACCENT2); tl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
			head.add_child(UiKit.spacer(0, 6)); head.add_child(tl)
		head.add_child(UiKit.spacer(0, 0, true))
		if game != null and String(game.get("state")) == "play":
			head.add_child(_close_button())
		head.add_child(UiKit.spacer(0, 10))
		col.add_child(head)
		var rule := ColorRect.new(); rule.color = UiTheme.LINE; rule.custom_minimum_size = Vector2(0, 1); rule.mouse_filter = Control.MOUSE_FILTER_IGNORE
		col.add_child(rule)
	var v := VBoxContainer.new(); v.add_theme_constant_override("separation", 10)
	v.size_flags_vertical = Control.SIZE_EXPAND_FILL
	col.add_child(UiKit.margin(v, 20, 16, 20, 16))
	col.get_child(col.get_child_count() - 1).size_flags_vertical = Control.SIZE_EXPAND_FILL
	frame.add_child(col); cc.add_child(frame); window.add_child(cc)
	root.add_child(window)
	if loading != null:
		root.move_child(loading, root.get_child_count() - 1)
	if not same:
		window.modulate.a = 0.0
		window.create_tween().tween_property(window, "modulate:a", 1.0, 0.12)
	return v

func _tab(kind: String, text: String, key: String, on: bool) -> Button:
	var b := Button.new(); b.text = text.to_upper(); b.focus_mode = Control.FOCUS_NONE
	b.custom_minimum_size = Vector2(0, 50)
	b.add_theme_font_size_override("font_size", 18)
	var line := UiTheme.flat_m(Color(0, 0, 0, 0), UiTheme.ACCENT, 0, 16, 0, 0); line.border_width_bottom = 2; line.draw_center = false
	var none := UiTheme.empty(16, 0)
	b.add_theme_stylebox_override("normal", line if on else none); b.add_theme_stylebox_override("hover", line if on else none)
	b.add_theme_stylebox_override("pressed", line); b.add_theme_stylebox_override("focus", StyleBoxEmpty.new())
	b.add_theme_color_override("font_color", UiTheme.ACCENT2 if on else UiTheme.DIM)
	b.add_theme_color_override("font_hover_color", UiTheme.ACCENT2 if on else UiTheme.INK)
	b.tooltip_text = key
	if not on:
		b.pressed.connect(func() -> void:
			click()
			open_tab(kind))
	return b

func open_tab(kind: String) -> void:
	match kind:
		"inv": open_inventory(null)
		"health": open_health()
		"map":
			var m = game.modules.get("sys_map") if game.modules is Dictionary else null
			if m != null and m.has_method("open_map"): m.open_map()

func _close_button() -> Button:
	var b := Button.new(); b.custom_minimum_size = Vector2(32, 32); b.focus_mode = Control.FOCUS_NONE
	b.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	b.tooltip_text = "Close (Esc)"
	b.draw.connect(func() -> void: UiKit.glyph(b, "cross", Rect2(10, 10, 12, 12), UiTheme.ACCENT2 if b.is_hovered() else UiTheme.DIM))
	b.pressed.connect(func() -> void: menu_action.emit("resume"))
	return b

## a blank centred window for other systems to fill; returns its content column
func open_window(kind: String, w: float, h: float, title: String) -> VBoxContainer:
	return _open(kind, w, h, title)

func open_inventory(cont = null) -> void:
	var v := _open("inv", 1240.0, 700.0, String(cont.label) if cont != null else "", cont == null)
	container = cont
	inventory.build(v, cont)

func refresh() -> void:
	if window == null:
		return
	if window_kind == "inv":
		inventory.refresh()
	elif window_kind == "health":
		health.refresh()

func open_health() -> void:
	var v := _open("health", 1240.0, 700.0, "")
	health.build(v)

func _treatments(w: Dictionary) -> Array:
	var out: Array = []
	var inv: Inventory = game.player.inv
	if (not w.bandaged or w.dirty) and w.type != "fracture":
		if inv.count("bandage") > 0: out.append("bandage")
		elif inv.count("rag") > 0: out.append("rag")
	if float(w.infection) > 0.0 or (not w.bandaged and w.type != "fracture"):
		for id in ["disinfectant", "poultice"]:
			if inv.count(id) > 0:
				out.append(id); break
	if w.type == "fracture" and not w.splinted and inv.count("splint") > 0: out.append("splint")
	if w.type in ["laceration", "bite", "bullet"] and not w.stitched:
		if inv.count("suture_kit") > 0: out.append("suture_kit")
	return out

# ------------------------------------------------------------------ menus, loading
func show_menu(kind: String, text := "") -> void:
	menus.show(kind, text)

func set_playing(on: bool) -> void:
	play.visible = on
	if on:
		action_box.set_action("", 0.0)
		_prompt_raw = "\u0001"
	else:
		fx.visible = false

## the loading card: progress 0–1 and a line saying what is happening. Call hide_loading() when done.
func show_loading(progress: float, text := "") -> void:
	if loading == null:
		loading = menus.build_loading()
		root.add_child(loading)
	root.move_child(loading, root.get_child_count() - 1)
	if not loading.visible or loading.modulate.a < 1.0:
		var tw = loading.get_meta("fade", null)
		if tw is Tween and tw.is_valid(): tw.kill()
		loading.visible = true; loading.modulate.a = 1.0
		_load_t = 0.0
	menus.set_loading(loading, progress, text)

func hide_loading() -> void:
	if loading == null or not loading.visible:
		return
	var tw := loading.create_tween()
	loading.set_meta("fade", tw)
	tw.tween_property(loading, "modulate:a", 0.0, 0.45)
	tw.tween_callback(func() -> void: loading.visible = false)

# ------------------------------------------------------------------ keys the HUD keeps for itself
func _input(ev: InputEvent) -> void:
	if not (ev is InputEventKey) or not ev.pressed:
		return
	if loading != null and loading.visible and loading.modulate.a >= 1.0:
		get_viewport().set_input_as_handled()
		return
	if window == null:
		return
	if menus.key(ev):
		get_viewport().set_input_as_handled()
	elif window_kind == "inv" and inventory.key(ev):
		get_viewport().set_input_as_handled()
	elif not ev.echo and game != null and String(game.get("state")) == "play" and Input.mouse_mode != Input.MOUSE_MODE_CAPTURED:
		# hop between the character windows without closing them first
		var k: int = ev.physical_keycode
		if k == KEY_H and window_kind == "inv":
			click(); open_health(); get_viewport().set_input_as_handled()
		elif (k == KEY_TAB or k == KEY_I) and (window_kind == "health" or window_kind == "map"):
			click(); open_inventory(null); get_viewport().set_input_as_handled()
		elif k == KEY_H and window_kind == "map":
			click(); open_health(); get_viewport().set_input_as_handled()
