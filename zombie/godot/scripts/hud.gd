class_name Hud
extends CanvasLayer
## Everything drawn over the world: status bars, clock, hotbar, prompts, notifications, the inventory and
## health windows, and the menus. Built in code so the project has no scene files to keep in sync.

signal use_item(uid: int)
signal drop_item(uid: int)
signal move_item(uid: int, to_container: bool)
signal assign_hotbar(uid: int, slot: int)
signal treat_wound(part: String, index: int, item_id: String)
signal take_all
signal menu_action(action: String)

const INK := Color(0.91, 0.88, 0.8)
const DIM := Color(0.62, 0.6, 0.54)
const PANEL := Color(0.06, 0.065, 0.06, 0.86)
const ACCENT := Color(0.93, 0.62, 0.25)
const BAD := Color(0.85, 0.3, 0.25)
const GOOD := Color(0.55, 0.78, 0.4)

var game
var root: Control
var icons: Dictionary = {}
var _icon_cache: Dictionary = {}
var clock_lbl: Label
var sub_lbl: Label
var bars := {}
var hot_slots: Array = []
var prompt_lbl: Label
var action_box: Control
var action_bar: ProgressBar
var action_lbl: Label
var notes: VBoxContainer
var moodle_box: VBoxContainer
var ammo_lbl: Label
var crosshair: Control
var hit_t := 0.0
var hit_kill := false
var flash: ColorRect
var flash_a := 0.0
var window: Control = null
var window_kind := ""
var container = null               # the open container: {label, inv}
var hover_uid := -1
var hover_in_container := false
var info_lbl: Label
var obj_lbl: Label

func _ready() -> void:
	layer = 5
	var idx = World.read_json("res://assets/icons/index.json")
	if idx is Dictionary:
		icons = idx
	root = Control.new(); root.set_anchors_preset(Control.PRESET_FULL_RECT); root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(root)
	_build_hud()

# ------------------------------------------------------------------ small builders
func icon(key: String) -> Texture2D:
	if not icons.has(key):
		return null
	if not _icon_cache.has(key):
		_icon_cache[key] = load("res://assets/icons/" + String(icons[key]))
	return _icon_cache[key]

static func box(col: Color, radius := 6, border := Color(0, 0, 0, 0), bw := 0) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = col; s.set_corner_radius_all(radius)
	s.border_color = border; s.set_border_width_all(bw)
	s.set_content_margin_all(8)
	return s

func label(text: String, size := 16, col := INK) -> Label:
	var l := Label.new(); l.text = text
	l.add_theme_font_size_override("font_size", size); l.add_theme_color_override("font_color", col)
	l.add_theme_color_override("font_shadow_color", Color(0, 0, 0, 0.7)); l.add_theme_constant_override("shadow_offset_x", 1); l.add_theme_constant_override("shadow_offset_y", 1)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return l

func _bar(col: Color, w := 220.0, h := 8.0) -> ProgressBar:
	var b := ProgressBar.new(); b.show_percentage = false; b.custom_minimum_size = Vector2(w, h); b.max_value = 100.0
	b.add_theme_stylebox_override("background", box(Color(0, 0, 0, 0.55), 3))
	var f := box(col, 3); f.set_content_margin_all(0)
	b.add_theme_stylebox_override("fill", f)
	b.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return b

func button(text: String, cb: Callable, w := 260.0) -> Button:
	var b := Button.new(); b.text = text; b.custom_minimum_size = Vector2(w, 40)
	b.add_theme_font_size_override("font_size", 18)
	b.add_theme_stylebox_override("normal", box(Color(0.14, 0.14, 0.13, 0.95), 5, Color(0.3, 0.29, 0.26), 1))
	b.add_theme_stylebox_override("hover", box(Color(0.24, 0.2, 0.14, 0.98), 5, ACCENT, 1))
	b.add_theme_stylebox_override("pressed", box(Color(0.3, 0.22, 0.12, 1.0), 5, ACCENT, 1))
	b.add_theme_color_override("font_color", INK)
	b.pressed.connect(cb)
	return b

# ------------------------------------------------------------------ always-on HUD
func _build_hud() -> void:
	var tl := VBoxContainer.new(); tl.position = Vector2(22, 16); tl.mouse_filter = Control.MOUSE_FILTER_IGNORE
	clock_lbl = label("08:30", 30); sub_lbl = label("", 13, DIM)
	tl.add_child(clock_lbl); tl.add_child(sub_lbl); root.add_child(tl)

	var bl := VBoxContainer.new(); bl.set_anchors_preset(Control.PRESET_BOTTOM_LEFT); bl.position = Vector2(22, -112); bl.mouse_filter = Control.MOUSE_FILTER_IGNORE
	bl.add_theme_constant_override("separation", 5)
	for row in [["health", "HEALTH", Color(0.78, 0.25, 0.22)], ["stamina", "STAMINA", Color(0.6, 0.76, 0.32)], ["hunger", "FOOD", Color(0.85, 0.6, 0.25)], ["thirst", "WATER", Color(0.35, 0.62, 0.85)]]:
		var h := HBoxContainer.new(); h.mouse_filter = Control.MOUSE_FILTER_IGNORE
		var l := label(row[1], 11, DIM); l.custom_minimum_size = Vector2(64, 0)
		var b := _bar(row[2])
		h.add_child(l); h.add_child(b); bl.add_child(h); bars[row[0]] = b
	root.add_child(bl)

	var hb := HBoxContainer.new(); hb.set_anchors_preset(Control.PRESET_CENTER_BOTTOM); hb.position = Vector2(-8 * 31, -74); hb.add_theme_constant_override("separation", 6); hb.mouse_filter = Control.MOUSE_FILTER_IGNORE
	for i in 8:
		var p := Panel.new(); p.custom_minimum_size = Vector2(56, 56); p.mouse_filter = Control.MOUSE_FILTER_IGNORE
		p.add_theme_stylebox_override("panel", box(Color(0, 0, 0, 0.5), 5, Color(1, 1, 1, 0.12), 1))
		var num := label(str(i + 1), 11, DIM); num.position = Vector2(5, 2); p.add_child(num)
		var tex := TextureRect.new(); tex.position = Vector2(8, 8); tex.size = Vector2(40, 40); tex.expand_mode = TextureRect.EXPAND_IGNORE_SIZE; tex.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED; tex.mouse_filter = Control.MOUSE_FILTER_IGNORE
		p.add_child(tex)
		var dur := _bar(GOOD, 44.0, 3.0); dur.position = Vector2(6, 50); p.add_child(dur)
		hb.add_child(p); hot_slots.append({"panel": p, "tex": tex, "dur": dur})
	root.add_child(hb)
	ammo_lbl = label("", 22); ammo_lbl.set_anchors_preset(Control.PRESET_BOTTOM_RIGHT); ammo_lbl.position = Vector2(-190, -70); ammo_lbl.custom_minimum_size = Vector2(170, 0); ammo_lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	root.add_child(ammo_lbl)

	crosshair = Control.new(); crosshair.set_anchors_preset(Control.PRESET_CENTER); crosshair.mouse_filter = Control.MOUSE_FILTER_IGNORE
	crosshair.draw.connect(_draw_crosshair); root.add_child(crosshair)
	prompt_lbl = label("", 18); prompt_lbl.set_anchors_preset(Control.PRESET_CENTER); prompt_lbl.position = Vector2(-300, 34); prompt_lbl.custom_minimum_size = Vector2(600, 0); prompt_lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	root.add_child(prompt_lbl)

	action_box = VBoxContainer.new(); action_box.set_anchors_preset(Control.PRESET_CENTER); action_box.position = Vector2(-150, 90); action_box.visible = false; action_box.mouse_filter = Control.MOUSE_FILTER_IGNORE
	action_lbl = label("", 15); action_lbl.custom_minimum_size = Vector2(300, 0); action_lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	action_bar = _bar(ACCENT, 300.0, 7.0)
	action_box.add_child(action_lbl); action_box.add_child(action_bar); root.add_child(action_box)

	notes = VBoxContainer.new(); notes.set_anchors_preset(Control.PRESET_CENTER_TOP); notes.position = Vector2(-330, 70); notes.custom_minimum_size = Vector2(660, 0); notes.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(notes)
	moodle_box = VBoxContainer.new(); moodle_box.set_anchors_preset(Control.PRESET_TOP_RIGHT); moodle_box.position = Vector2(-212, 110); moodle_box.custom_minimum_size = Vector2(190, 0); moodle_box.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(moodle_box)
	obj_lbl = label("", 14); obj_lbl.set_anchors_preset(Control.PRESET_TOP_RIGHT); obj_lbl.position = Vector2(-442, 18); obj_lbl.custom_minimum_size = Vector2(420, 0); obj_lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT; obj_lbl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	root.add_child(obj_lbl)

	flash = ColorRect.new(); flash.set_anchors_preset(Control.PRESET_FULL_RECT); flash.color = Color(0.6, 0.02, 0.02, 0.0); flash.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(flash)

func _draw_crosshair() -> void:
	var c := Color(1, 1, 1, 0.8)
	if hit_t > 0.0:
		c = BAD if hit_kill else Color(1, 1, 1, 1)
		var r := 9.0 + (0.25 - hit_t) * 14.0
		for a in [PI * 0.25, PI * 0.75, PI * 1.25, PI * 1.75]:
			crosshair.draw_line(Vector2(cos(a), sin(a)) * r, Vector2(cos(a), sin(a)) * (r + 7.0), c, 2.0)
	crosshair.draw_circle(Vector2.ZERO, 1.6, Color(1, 1, 1, 0.85))

func hitmark(killed: bool) -> void:
	hit_t = 0.25; hit_kill = killed

func damage_flash(amount := 0.6) -> void:
	flash_a = minf(0.75, flash_a + amount)

func notify(text: String, kind := "") -> void:
	var col := INK
	if kind == "bad": col = Color(1.0, 0.55, 0.45)
	elif kind == "good": col = Color(0.72, 0.9, 0.55)
	elif kind == "xp": col = Color(0.95, 0.8, 0.4)
	var p := PanelContainer.new(); p.add_theme_stylebox_override("panel", box(Color(0, 0, 0, 0.62), 5)); p.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var l := label(text, 16, col); l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; l.custom_minimum_size = Vector2(640, 0); l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	p.add_child(l); notes.add_child(p)
	if notes.get_child_count() > 5:
		notes.get_child(0).queue_free()
	var tw := p.create_tween()
	tw.tween_interval(5.0); tw.tween_property(p, "modulate:a", 0.0, 0.8); tw.tween_callback(p.queue_free)

func set_prompt(text: String) -> void:
	prompt_lbl.text = text

func set_action(text: String, progress: float) -> void:
	action_box.visible = text != ""
	action_lbl.text = text; action_bar.value = progress * 100.0

func is_open() -> bool:
	return window != null

# ------------------------------------------------------------------ per-frame refresh
func update_hud(dt: float) -> void:
	if game == null or game.player == null:
		return
	var p: Player = game.player
	var st := p.stats
	hit_t = maxf(0.0, hit_t - dt); crosshair.queue_redraw()
	flash_a = maxf(0.0, flash_a - dt * 0.9)
	var low := clampf((35.0 - st.health()) / 35.0, 0.0, 1.0) * 0.25
	flash.color.a = maxf(flash_a, low)
	var hrs: float = game.hours()
	clock_lbl.text = "%02d:%02d" % [int(hrs), int(fmod(hrs, 1.0) * 60.0)]
	sub_lbl.text = "DAY %d · %s · %d°C" % [game.day(), game.season_name().to_upper(), int(round(game.ambient_temp()))]
	bars.health.value = st.health(); bars.stamina.value = st.stamina / maxf(1.0, st.max_stamina) * 100.0
	bars.hunger.value = st.hunger; bars.thirst.value = st.thirst
	for i in 8:
		var slot: Dictionary = hot_slots[i]
		var s = p.inv.by_uid(p.hotbar[i])
		var sel: bool = s != null and p.equipped != null and int(s.uid) == int(p.equipped)
		slot.panel.add_theme_stylebox_override("panel", box(Color(0.2, 0.15, 0.08, 0.75) if sel else Color(0, 0, 0, 0.5), 5, ACCENT if sel else Color(1, 1, 1, 0.12), 2 if sel else 1))
		slot.tex.texture = icon("item_" + String(s.id)) if s != null else null
		var d := Items.def(s.id) if s != null else {}
		slot.dur.visible = s != null and s.has("dur") and d.has("weapon")
		if slot.dur.visible:
			slot.dur.value = float(s.dur) / maxf(1.0, float(d.weapon.durability)) * 100.0
	var es = p.equipped_stack()
	ammo_lbl.text = ""
	if es != null:
		var ed := Items.def(es.id)
		if ed.has("weapon") and ed.weapon.has("ammo"):
			ammo_lbl.text = "%d / %d" % [int(es.get("ammo", 0)), p.inv.count(String(ed.weapon.ammo))]
		else:
			ammo_lbl.text = String(ed.name)
	crosshair.visible = window == null
	_moodles(st.moodles(p.weight_ratio()))
	obj_lbl.text = game.objective_text()
	if window != null and window_kind == "inv":
		_refresh_info()

var _moodle_sig := ""
func _moodles(list: Array) -> void:
	var sig := ""
	for m in list:
		sig += "%s%d," % [m.id, m.level]
	if sig == _moodle_sig:
		return
	_moodle_sig = sig
	for c in moodle_box.get_children():
		c.queue_free()
	for m in list:
		var h := HBoxContainer.new(); h.mouse_filter = Control.MOUSE_FILTER_IGNORE
		var t := TextureRect.new(); t.texture = icon("m_" + String(m.id)); t.custom_minimum_size = Vector2(26, 26); t.expand_mode = TextureRect.EXPAND_IGNORE_SIZE; t.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		var col: Color = [INK, Color(0.95, 0.8, 0.45), Color(0.98, 0.6, 0.35), BAD][int(m.level) - 1]
		h.add_child(t); h.add_child(label(String(m.label), 14, col)); moodle_box.add_child(h)

# ------------------------------------------------------------------ windows
func close_window() -> void:
	if window != null:
		window.queue_free()
	window = null; window_kind = ""; container = null; hover_uid = -1

func _open(kind: String, w: float, h: float, title: String) -> VBoxContainer:
	close_window()
	window_kind = kind
	window = Control.new(); window.set_anchors_preset(Control.PRESET_FULL_RECT)
	var shade := ColorRect.new(); shade.set_anchors_preset(Control.PRESET_FULL_RECT); shade.color = Color(0, 0, 0, 0.45); window.add_child(shade)
	var pc := PanelContainer.new(); pc.set_anchors_preset(Control.PRESET_CENTER); pc.custom_minimum_size = Vector2(w, h); pc.position = Vector2(-w / 2.0, -h / 2.0)
	pc.add_theme_stylebox_override("panel", box(PANEL, 10, Color(1, 1, 1, 0.08), 1))
	var v := VBoxContainer.new(); v.add_theme_constant_override("separation", 10)
	if title != "":
		v.add_child(label(title, 24))
	pc.add_child(v); window.add_child(pc); root.add_child(window)
	return v

func open_inventory(cont = null) -> void:
	var v := _open("inv", 980.0, 560.0, "")
	container = cont
	var cols := HBoxContainer.new(); cols.add_theme_constant_override("separation", 14); cols.size_flags_vertical = Control.SIZE_EXPAND_FILL
	v.add_child(cols)
	if cont != null:
		cols.add_child(_inv_column(String(cont.label), cont.inv, true))
	cols.add_child(_inv_column("You", game.player.inv, false))
	if cont == null:
		cols.add_child(_worn_column())
	info_lbl = label("", 14, DIM); info_lbl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; info_lbl.custom_minimum_size = Vector2(940, 44)
	v.add_child(info_lbl)
	var hint := "Click: take   ·   Right-click your item: store   ·   R: take all   ·   Tab / Esc: close" if cont != null else "Click: use / equip / wear   ·   Right-click: drop   ·   1–8 over a weapon: set hotbar slot   ·   Tab / Esc: close"
	v.add_child(label(hint, 12, DIM))

func refresh() -> void:
	if window != null and window_kind == "inv":
		open_inventory(container)
	elif window != null and window_kind == "health":
		open_health()

func _inv_column(title: String, inv: Inventory, is_container: bool) -> Control:
	var v := VBoxContainer.new(); v.size_flags_horizontal = Control.SIZE_EXPAND_FILL; v.custom_minimum_size = Vector2(400, 0)
	var head := HBoxContainer.new()
	head.add_child(label(title, 20))
	var wl := label("", 14, DIM); wl.size_flags_horizontal = Control.SIZE_EXPAND_FILL; wl.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	if is_container:
		wl.text = "%.1f kg" % inv.weight() if inv.capacity == INF else "%.1f / %.0f kg" % [inv.weight(), inv.capacity]
	else:
		var p: Player = game.player
		wl.text = "%.1f / %.0f kg" % [p.weight(), p.capacity()]
		if p.weight_ratio() > 1.0: wl.add_theme_color_override("font_color", BAD)
	head.add_child(wl); v.add_child(head)
	var sc := ScrollContainer.new(); sc.size_flags_vertical = Control.SIZE_EXPAND_FILL; sc.custom_minimum_size = Vector2(0, 380)
	var list := VBoxContainer.new(); list.size_flags_horizontal = Control.SIZE_EXPAND_FILL; list.add_theme_constant_override("separation", 3)
	sc.add_child(list); v.add_child(sc)
	if inv.stacks.is_empty():
		list.add_child(label("Empty." if is_container else "You are carrying nothing.", 15, DIM))
	for s in inv.stacks:
		list.add_child(_item_row(s, is_container))
	return v

func _item_row(s: Dictionary, is_container: bool) -> Control:
	var d := Items.def(s.id)
	var b := Button.new(); b.custom_minimum_size = Vector2(0, 38); b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	b.add_theme_stylebox_override("normal", box(Color(1, 1, 1, 0.04), 4)); b.add_theme_stylebox_override("hover", box(Color(0.93, 0.62, 0.25, 0.22), 4)); b.add_theme_stylebox_override("pressed", box(Color(0.93, 0.62, 0.25, 0.35), 4))
	b.button_mask = MOUSE_BUTTON_MASK_LEFT | MOUSE_BUTTON_MASK_RIGHT
	var h := HBoxContainer.new(); h.set_anchors_preset(Control.PRESET_FULL_RECT); h.offset_left = 6; h.offset_right = -8; h.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var t := TextureRect.new(); t.texture = icon("item_" + String(s.id)); t.custom_minimum_size = Vector2(30, 30); t.expand_mode = TextureRect.EXPAND_IGNORE_SIZE; t.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED; t.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_child(t)
	var p: Player = game.player
	var eq: bool = not is_container and p.equipped != null and int(p.equipped) == int(s.uid)
	var nm := String(d.name) + (" ×%d" % int(s.n) if int(s.n) > 1 else "")
	var nl := label(nm, 16, ACCENT if eq else INK); nl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(nl)
	var extra := ""
	if d.has("weapon"):
		extra = "%d%%" % int(round(float(s.get("dur", 0.0)) / maxf(1.0, float(d.weapon.durability)) * 100.0))
		if d.weapon.has("ammo"): extra = "%d rds · " % int(s.get("ammo", 0)) + extra
	elif d.has("spoilDays"):
		var fr := 1.0 - float(s.get("age", 0.0)) / float(d.spoilDays)
		extra = "fresh" if fr > 0.5 else ("stale" if fr > 0.0 else "rotten")
	var hk := p.hotbar.find(int(s.uid)) if not is_container else -1
	if hk >= 0: extra += "   [%d]" % (hk + 1)
	h.add_child(label(extra, 13, DIM))
	var wl := label("%.1f" % (float(d.weight) * int(s.n)), 13, DIM); wl.custom_minimum_size = Vector2(44, 0); wl.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	h.add_child(wl)
	b.add_child(h)
	var uid := int(s.uid)
	b.mouse_entered.connect(func() -> void: hover_uid = uid; hover_in_container = is_container)
	b.gui_input.connect(func(ev: InputEvent) -> void:
		if ev is InputEventMouseButton and ev.pressed:
			if is_container:
				move_item.emit(uid, false)
			elif ev.button_index == MOUSE_BUTTON_RIGHT:
				if container != null: move_item.emit(uid, true)
				else: drop_item.emit(uid)
			elif ev.button_index == MOUSE_BUTTON_LEFT:
				if container != null: move_item.emit(uid, true)
				else: use_item.emit(uid))
	return b

func _worn_column() -> Control:
	var v := VBoxContainer.new(); v.custom_minimum_size = Vector2(220, 0)
	v.add_child(label("Wearing", 20))
	var p: Player = game.player
	for slot in ["head", "torso", "legs", "feet", "hands", "back"]:
		var s = p.worn.get(slot)
		var h := HBoxContainer.new()
		var sl := label(String(slot).capitalize(), 13, DIM); sl.custom_minimum_size = Vector2(54, 0)
		h.add_child(sl)
		h.add_child(label(String(Items.def(s.id).name) if s != null else "—", 15))
		v.add_child(h)
	v.add_child(label("", 8))
	v.add_child(label("Skills", 20))
	for sk in ["fitness", "strength", "blunt", "blade", "firearms", "firstaid", "scavenging"]:
		var h2 := HBoxContainer.new()
		var l := label(String(sk).capitalize(), 13, DIM); l.custom_minimum_size = Vector2(90, 0)
		h2.add_child(l); h2.add_child(label(str(p.level(sk)), 15))
		var pb := _bar(ACCENT, 80.0, 5.0); pb.value = p.skill_progress(sk) * 100.0; pb.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		h2.add_child(pb); v.add_child(h2)
	return v

func _refresh_info() -> void:
	if info_lbl == null or not is_instance_valid(info_lbl):
		return
	var inv: Inventory = container.inv if hover_in_container and container != null else game.player.inv
	var s = inv.by_uid(hover_uid)
	if s == null:
		info_lbl.text = ""
		return
	var d := Items.def(s.id)
	var bits: Array = [String(d.desc)]
	if d.has("hunger"): bits.append("Food %+d" % int(d.hunger))
	if d.has("thirst"): bits.append("Water %+d" % int(d.thirst))
	if d.has("weapon"): bits.append("Damage %d · Range %.1f m" % [int(d.weapon.damage), float(d.weapon.range)])
	if d.has("cloth"): bits.append("Warmth %d%% · Bite %d%% · Scratch %d%%" % [int(float(d.cloth.insul) * 100), int(float(d.cloth.bite) * 100), int(float(d.cloth.scratch) * 100)])
	info_lbl.text = "   ·   ".join(bits)

func open_health() -> void:
	var v := _open("health", 720.0, 520.0, "Health")
	var p: Player = game.player
	var st := p.stats
	var summary := "Overall %d%%   ·   Blood loss %d%%   ·   Body %.1f°C" % [int(st.health()), int(st.blood_loss), st.body_temp]
	if st.knox_infected and st.knox > 0.15: summary += "   ·   Fever is rising"
	v.add_child(label(summary, 15, DIM))
	var sc := ScrollContainer.new(); sc.custom_minimum_size = Vector2(0, 390); sc.size_flags_vertical = Control.SIZE_EXPAND_FILL
	var list := VBoxContainer.new(); list.size_flags_horizontal = Control.SIZE_EXPAND_FILL; list.add_theme_constant_override("separation", 8)
	sc.add_child(list); v.add_child(sc)
	for part in Stats.PARTS:
		var pd: Dictionary = st.parts[part[0]]
		var h := HBoxContainer.new()
		var nl := label(String(part[1]), 17); nl.custom_minimum_size = Vector2(110, 0); h.add_child(nl)
		var b := _bar(Color(0.78, 0.25, 0.22), 160.0, 8.0); b.value = float(pd.hp); b.size_flags_vertical = Control.SIZE_SHRINK_CENTER; h.add_child(b)
		list.add_child(h)
		var wi := 0
		for w in pd.wounds:
			var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
			var desc := "   " + String(w.type).capitalize()
			if float(w.bleed) > 0.05 and not w.bandaged: desc += " · bleeding"
			if w.bandaged: desc += " · dirty bandage" if w.dirty else " · bandaged"
			if w.stitched: desc += " · stitched"
			if w.splinted: desc += " · splinted"
			if float(w.infection) > 0.05: desc += " · infected %d%%" % int(float(w.infection) * 100.0)
			var dl := label(desc, 14, BAD if float(w.infection) > 0.3 or (float(w.bleed) > 0.05 and not w.bandaged) else INK); dl.custom_minimum_size = Vector2(330, 0)
			row.add_child(dl)
			for opt in _treatments(w):
				var pid := String(part[0]); var idx := wi; var item_id := String(opt)
				row.add_child(button(String(Items.def(item_id).name), func() -> void: treat_wound.emit(pid, idx, item_id), 120.0))
			list.add_child(row)
			wi += 1
	v.add_child(label("H / Esc: close   ·   Q in play: bandage the worst bleeding wound", 12, DIM))

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

func show_menu(kind: String, text := "") -> void:
	var v := _open(kind, 460.0, 420.0, "")
	v.alignment = BoxContainer.ALIGNMENT_CENTER
	var title := label("REKINDLE", 54, ACCENT); title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER; v.add_child(title)
	if kind == "main":
		var sub := label("Build a town. Keep it alive.", 16, DIM); sub.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER; v.add_child(sub)
		v.add_child(label("", 6))
		if game.has_save(): v.add_child(_centered(button("Continue", func() -> void: menu_action.emit("continue"))))
		v.add_child(_centered(button("New game", func() -> void: menu_action.emit("new"))))
		v.add_child(_centered(button("Quit", func() -> void: menu_action.emit("quit"))))
		var help := label("WASD move · Shift sprint · C crouch · Space jump · E interact · Tab inventory · H health\nLMB attack · RMB aim / shove · R reload · F flashlight · Q bandage · 1–8 hotbar · F5 save", 12, DIM)
		help.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER; v.add_child(help)
	elif kind == "pause":
		v.add_child(_centered(button("Resume", func() -> void: menu_action.emit("resume"))))
		v.add_child(_centered(button("Save", func() -> void: menu_action.emit("save"))))
		v.add_child(_centered(button("Save and quit to menu", func() -> void: menu_action.emit("menu"))))
	elif kind == "dead":
		title.text = "YOU DIED"; title.add_theme_color_override("font_color", BAD)
		var t := label(text, 17); t.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER; t.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; t.custom_minimum_size = Vector2(420, 0); v.add_child(t)
		v.add_child(_centered(button("New game", func() -> void: menu_action.emit("new"))))
		v.add_child(_centered(button("Main menu", func() -> void: menu_action.emit("menu"))))

func _centered(c: Control) -> Control:
	var cc := CenterContainer.new(); cc.add_child(c); return cc

func set_playing(on: bool) -> void:
	for c in root.get_children():
		if c != window:
			c.visible = on
	if on:
		action_box.visible = false
