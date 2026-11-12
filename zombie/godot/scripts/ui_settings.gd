class_name UiSettings
extends RefCounted
## The settings pages. One implementation with three ways in: the main menu, the pause menu and the Settings tab
## of the in-game window — each hands `build()` a column to fill. Every control writes straight to Settings and
## takes effect at once; changes to the window itself ask to be kept and go back on their own if they are not.

const TABS := [["game", "Game"], ["interface", "Interface"], ["display", "Display"], ["audio", "Sound"], ["controls", "Controls"], ["comfort", "Comfort"]]
const CROSS_COLORS := {"white": Color(1, 1, 1), "amber": Color(1.0, 0.78, 0.36), "green": Color(0.45, 1.0, 0.5), "cyan": Color(0.4, 0.95, 1.0), "pink": Color(1.0, 0.5, 0.85)}
const KEEP_SECONDS := 12.0

var hud: Hud
var tab := "game"
var _built := false
var _body: VBoxContainer = null
var _scroll: ScrollContainer = null
var _tabs: Dictionary = {}
var _host: Control = null
var _capture: Dictionary = {}       # {action, slot} while the next key press is being waited for
var _pending: Dictionary = {}       # {action, slot, code, clash} while a clash waits for an answer
var _confirm: Control = null
var _confirm_lbl: Label = null
var _confirm_t := 0.0
var _confirm_prev: Dictionary = {}
var _forward := true

func _init(h: Hud) -> void:
	hud = h
	Settings.bindings_changed.connect(func() -> void:
		if _built and tab == "controls" and _capture.is_empty(): _fill.call_deferred())

func closed() -> void:
	if _confirm != null:
		_revert_display()
	_built = false
	_body = null; _scroll = null; _host = null; _capture = {}; _pending = {}; _confirm = null

## fills `v` with the tab strip, the page and its foot. `host` is the full-screen control the "keep these
## display settings?" card is laid over; `back`, if given, adds a Back button.
func build(v: VBoxContainer, host: Control, back := Callable()) -> void:
	_built = true
	_host = host
	_forward = RenderingServer.get_current_rendering_method() == "forward_plus"
	_capture = {}; _pending = {}
	v.add_theme_constant_override("separation", 12)
	var strip := HBoxContainer.new(); strip.add_theme_constant_override("separation", 0)
	_tabs.clear()
	for t in TABS:
		var b := Button.new(); b.text = String(t[1]).to_upper(); b.toggle_mode = true; b.custom_minimum_size = Vector2(150, 38)
		b.focus_mode = Control.FOCUS_NONE
		b.button_pressed = String(t[0]) == tab
		var id := String(t[0])
		b.pressed.connect(func() -> void:
			hud.click()
			show_tab(id))
		_tabs[id] = b; strip.add_child(b)
	v.add_child(strip)
	_scroll = ScrollContainer.new(); _scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL; _scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	_body = VBoxContainer.new(); _body.size_flags_horizontal = Control.SIZE_EXPAND_FILL; _body.add_theme_constant_override("separation", 2)
	_scroll.add_child(UiKit.margin(_body, 0, 0, 14, 0))
	_scroll.get_child(0).size_flags_horizontal = Control.SIZE_EXPAND_FILL
	v.add_child(_scroll)
	var foot := HBoxContainer.new()
	var reset := func() -> void:
		Settings.reset_page(tab)
		_fill()
	foot.add_child(hud.button("Reset this page", reset, 200.0))
	foot.add_child(UiKit.spacer(0, 0, true))
	if back.is_valid():
		var bb := hud.button("Back", back, 160.0)
		bb.add_theme_stylebox_override("normal", UiTheme.flat_m(Color(UiTheme.ACCENT, 0.12), UiTheme.ACCENT, 1, 16, 7))
		bb.add_theme_color_override("font_color", UiTheme.ACCENT2)
		foot.add_child(bb)
	v.add_child(foot)
	_fill()

func show_tab(id: String) -> void:
	tab = id
	_capture = {}; _pending = {}
	for k in _tabs: _tabs[k].set_pressed_no_signal(k == id)
	_fill()
	if _scroll != null: _scroll.scroll_vertical = 0

# ------------------------------------------------------------------ the pages
func _fill() -> void:
	if not _built or _body == null or not is_instance_valid(_body):
		return
	for c in _body.get_children():
		c.queue_free()
	match tab:
		"game": _page_game()
		"interface": _page_interface()
		"display": _page_display()
		"audio": _page_audio()
		"controls": _page_controls()
		"comfort": _page_comfort()

func _pct(x: float) -> String:
	return "%d%%" % int(round(x * 100.0))

func _page_game() -> void:
	_section("Difficulty")
	var seg := UiKit.Segmented.new([["easy", "Easy"], ["normal", "Normal"], ["hard", "Hard"]], String(Settings.v("difficulty")), 100.0)
	seg.picked.connect(func(val) -> void:
		hud.click()
		Settings.apply_preset(String(val), "difficulty")
		_fill())
	_row("Difficulty", "Sets the four dials below." if Settings.matches_preset("difficulty") else "Changed by hand since the preset was chosen.", _right(seg))
	_slider("Damage from the dead", "How much a scratch, a cut or a bite takes out of you.", "zombie_damage", 0.5, 2.0, 0.05, _pct, true, true)
	_slider("Hunger and thirst", "How fast you need to eat and drink.", "needs_rate", 0.5, 2.0, 0.05, _pct, true, true)
	_slider("How many dead", "The valley's population. Takes effect when a new game starts.", "zombie_count", 0.5, 1.5, 0.05, _pct, true, true)
	_choice("Length of a day", "Real minutes for one day and night.", "day_len", [[16.0, "16"], [24.0, "24"], [36.0, "36"], [48.0, "48"], [72.0, "72"]], 62.0, true)
	_section("Saving")
	_check("Autosave", "F5 — or whatever you bound to Save — always works.".replace("F5", Settings.key_name("save")), "autosave", true)
	_choice("Autosave every", "Hours of game time.", "autosave_hours", [[1.0, "1 h"], [2.0, "2 h"], [3.0, "3 h"], [6.0, "6 h"]], 78.0, false, not bool(Settings.v("autosave")))
	_section("Help")
	_check("Hints", "What a new status icon means, the objective's description, tips while loading.", "tips")
	_choice("Temperature", "", "temp_unit", [["c", "°C"], ["f", "°F"]], 78.0)

func _page_interface() -> void:
	_section("Size")
	_slider("Interface size", "Scales the HUD; windows and menus follow as far as they fit the screen.", "hud_scale", 0.8, 1.4, 0.05, _pct, false)
	_slider("HUD opacity", "", "hud_opacity", 0.3, 1.0, 0.05, _pct)
	_section("On screen")
	_check("Objective", "The current objective, under the minimap.", "show_objective")
	_check("Compass", "", "show_compass")
	_check("Minimap", "", "minimap", true)
	_check("Minimap turns with you", "Off keeps north at the top.", "minimap_rotate", false, not bool(Settings.v("minimap")))
	_section("Crosshair")
	_choice("Crosshair", "Full opens with movement and recoil; dot is a dot.", "crosshair", [["full", "Full"], ["dot", "Dot"], ["off", "Off"]], 86.0, true)
	var sw := HBoxContainer.new(); sw.add_theme_constant_override("separation", 6)
	for id in CROSS_COLORS:
		sw.add_child(_swatch(String(id), CROSS_COLORS[id], String(Settings.v("crosshair")) == "off" and not bool(Settings.v("center_dot"))))
	_row("Crosshair colour", "", _right(sw))
	_check("Hit markers", "The tick when a blow or a shot lands, red when it kills.", "hit_markers")

func _page_display() -> void:
	_section("Window")
	var mode := UiKit.Segmented.new([["windowed", "Windowed"], ["borderless", "Borderless"], ["fullscreen", "Fullscreen"]], String(Settings.v("window_mode")), 120.0)
	mode.picked.connect(func(val) -> void:
		hud.click()
		_display_change("window_mode", val))
	_row("Window mode", "Borderless fills the screen and keeps other windows a click away.", _right(mode))
	var sizes: Array = []
	for s in Settings.WINDOW_SIZES: sizes.append([s, String(s).replace("x", " × ")])
	var size := UiKit.Segmented.new(sizes, String(Settings.v("window_size")), 110.0)
	size.picked.connect(func(val) -> void:
		hud.click()
		_display_change("window_size", val))
	_row("Window size", "For windowed mode. Full screen uses the display's own resolution.", _right(size), null, String(Settings.v("window_mode")) != "windowed")
	_check("V-sync", "Matches frames to the screen; removes tearing.", "vsync")
	_choice("Frame-rate cap", "", "fps_cap", [[0.0, "Off"], [30.0, "30"], [60.0, "60"], [90.0, "90"], [120.0, "120"], [144.0, "144"]], 60.0)
	_section("Quality")
	var seg := UiKit.Segmented.new([["low", "Low"], ["medium", "Medium"], ["high", "High"], ["ultra", "Ultra"]], String(Settings.v("quality")), 90.0)
	seg.picked.connect(func(val) -> void:
		hud.click()
		Settings.apply_preset(String(val))
		_fill())
	_row("Preset", "Sets the options from here down to the vegetation distance." if Settings.matches_preset() else "Changed by hand since the preset was chosen.", _right(seg))
	_slider("Render scale", "Draws the world at a lower resolution to gain speed. The interface stays sharp.", "render_scale", 0.5, 1.0, 0.05, _pct, true, true)
	_choice("Upscaling", "How a lower render scale is brought back up to size." if _forward else "FSR needs the Forward+ renderer.", "upscaler", [["bilinear", "Plain"], ["fsr", "FSR 1"], ["fsr2", "FSR 2"]], 90.0, false, not _forward)
	var aa: Array = [["off", "Off"], ["fxaa", "FXAA"], ["msaa2", "MSAA 2×"], ["msaa4", "MSAA 4×"], ["taa", "TAA"]] if _forward else [["off", "Off"], ["msaa2", "MSAA 2×"], ["msaa4", "MSAA 4×"]]
	_choice("Anti-aliasing", "Smooths jagged edges." if _forward else "Smooths jagged edges. FXAA and TAA need the Forward+ renderer.", "aa", aa, 84.0, true)
	_choice("Shadows", "", "shadow_quality", [[0.0, "Off"], [1.0, "Low"], [2.0, "Medium"], [3.0, "High"], [4.0, "Ultra"]], 76.0, true)
	_check("Ambient occlusion", "Soft shade in corners and under things." if _forward else "Needs the Forward+ renderer.", "ssao", true, not _forward)
	_check("Global illumination", "Bounced light. The costly one." if _forward else "Needs the Forward+ renderer.", "gi", true, not _forward)
	_check("Volumetric fog", "Light shafts and mist with depth." if _forward else "Needs the Forward+ renderer.", "volumetric_fog", true, not _forward)
	_check("Bloom", "Bright things glow.", "bloom", true)
	_choice("Texture filtering", "Keeps the ground sharp at a distance.", "aniso", [[1.0, "Off"], [2.0, "2×"], [4.0, "4×"], [8.0, "8×"], [16.0, "16×"]], 68.0, true)
	_slider("View distance", "", "view_dist", 0.4, 1.0, 0.05, _pct, true, true)
	_slider("Vegetation distance", "How far away trees, bushes and clutter are drawn.", "veg_dist", 0.5, 1.0, 0.05, _pct, true, true)
	_slider("Grass density", "", "grass", 0.0, 1.0, 0.05, _pct, true, true)
	_section("Picture")
	_slider("Field of view", "How wide you see. Wider shows more and distorts the edges.", "fov", 60.0, 100.0, 1.0, func(x: float) -> String: return "%d°" % int(x))
	_slider("Brightness", "", "brightness", 0.7, 1.4, 0.02, _pct)
	_slider("Film grain", "", "film_grain", 0.0, 1.0, 0.05, _pct)
	_slider("Vignette", "Darkening towards the corners.", "vignette", 0.0, 1.0, 0.05, _pct)

func _page_audio() -> void:
	_section("Volume")
	_slider("Master", "", "master_vol", 0.0, 1.0, 0.05, _pct)
	_slider("Music", "", "music_vol", 0.0, 1.0, 0.05, _pct)
	_slider("Effects", "Footsteps, weapons, doors, the dead.", "sfx_vol", 0.0, 1.0, 0.05, _pct)
	_slider("Ambience", "Wind, rain, the night, thunder, far-off sounds.", "ambience_vol", 0.0, 1.0, 0.05, _pct)
	_slider("Interface", "Clicks and window sounds.", "ui_vol", 0.0, 1.0, 0.05, _pct, true, false, func() -> void: Sfx.play("ui_click"))
	_section("Output")
	_check("Mute when the window is not in focus", "", "mute_unfocused")
	var devices := AudioServer.get_output_device_list()
	if devices.size() > 1:
		var cur := String(Settings.v("audio_device"))
		var b := hud.button(cur, _noop, 360.0)
		b.pressed.connect(func() -> void:
			var i := (devices.find(String(Settings.v("audio_device"))) + 1) % devices.size()
			Settings.set_value("audio_device", devices[i])
			b.text = String(devices[i]).to_upper())
		b.clip_text = true
		_row("Output device", "Click to step through the devices.", b)

func _page_controls() -> void:
	_section("Mouse")
	_slider("Mouse sensitivity", "", "mouse_sens", 0.3, 2.5, 0.05, func(x: float) -> String: return "×%.2f" % x)
	_slider("Sensitivity while aiming", "A share of the above, for steadier sights.", "ads_sens", 0.3, 1.0, 0.05, _pct)
	_check("Invert vertical look", "", "invert_y")
	_section("Hold or toggle")
	_choice("Crouch", "", "toggle_crouch", [[true, "Toggle"], [false, "Hold"]], 96.0)
	_choice("Sprint", "", "toggle_sprint", [[false, "Hold"], [true, "Toggle"]], 96.0)
	_choice("Aim", "", "toggle_aim", [[false, "Hold"], [true, "Toggle"]], 96.0)
	var group := ""
	for a in Settings.ACTIONS:
		if String(a[2]) != group:
			group = String(a[2])
			_section("Keys — " + group if group != "Movement" else "Keys — movement", "Click a key, then press the new key or mouse button.   Esc cancels   ·   Backspace clears" if group == "Movement" else "")
		_bind_row(String(a[0]))

func _page_comfort() -> void:
	_section("Motion")
	_check("Head bob", "The camera moves with your steps.", "head_bob")
	_slider("Camera shake", "From blows, recoil and hard landings. Zero turns it off.", "cam_shake", 0.0, 1.5, 0.05, _pct)
	_check("Centre dot", "A steady dot in the middle of the screen at all times — a fixed point for the eye.", "center_dot")
	_section("Light and colour")
	_check("Reduce flashing", "Softens lightning, muzzle flash and the red flash when you are struck.", "reduce_flash")
	_check("Colour-blind-safe status colours", "Blue, yellow and orange in place of green, amber and red.", "colorblind")
	_section("Elsewhere")
	var note := UiKit.body("Interface size and opacity are on the Interface page. Field of view and brightness are on the Display page. Hold-or-toggle and every key are on the Controls page.", 13, UiTheme.FAINT, true)
	note.custom_minimum_size = Vector2(700, 0)
	_body.add_child(UiKit.margin(note, 0, 8, 0, 0))

# ------------------------------------------------------------------ rows
func _section(title: String, hint := "") -> void:
	var h := HBoxContainer.new(); h.custom_minimum_size = Vector2(0, 34)
	var l := UiKit.caption(title, UiTheme.ACCENT2, 12); l.size_flags_vertical = Control.SIZE_SHRINK_END
	h.add_child(l)
	if hint != "":
		h.add_child(UiKit.spacer(0, 0, true))
		var hl := UiKit.body(hint, 12, UiTheme.FAINT); hl.size_flags_vertical = Control.SIZE_SHRINK_END
		h.add_child(hl)
	_body.add_child(h)
	var line := ColorRect.new(); line.color = UiTheme.LINE; line.custom_minimum_size = Vector2(0, 1); line.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_body.add_child(line)

func _right(c: Control, w := 360.0) -> HBoxContainer:
	var holder := HBoxContainer.new(); holder.custom_minimum_size = Vector2(w, 0); holder.alignment = BoxContainer.ALIGNMENT_END
	holder.add_child(c)
	return holder

func _row(title: String, hint: String, control: Control, value: Label = null, off := false) -> void:
	var r := HBoxContainer.new(); r.custom_minimum_size = Vector2(0, 50); r.add_theme_constant_override("separation", 14)
	var tv := VBoxContainer.new(); tv.size_flags_horizontal = Control.SIZE_EXPAND_FILL; tv.alignment = BoxContainer.ALIGNMENT_CENTER
	tv.add_theme_constant_override("separation", 0)
	tv.add_child(UiKit.body(title, 16, UiTheme.FAINT if off else UiTheme.INK))
	if hint != "":
		var hl := UiKit.body(hint, 13, UiTheme.FAINT); hl.clip_text = true; hl.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
		hl.tooltip_text = hint; hl.mouse_filter = Control.MOUSE_FILTER_PASS
		tv.add_child(hl)
	r.add_child(tv)
	control.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	if off:
		_disable(control)
	r.add_child(control)
	if value != null:
		value.custom_minimum_size = Vector2(64, 0); value.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
		value.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		value.add_theme_font_override("font", UiTheme.font("num"))
		r.add_child(value)
	else:
		r.add_child(UiKit.spacer(0, 64))
	_body.add_child(r)
	var line := ColorRect.new(); line.color = Color(1, 1, 1, 0.05); line.custom_minimum_size = Vector2(0, 1); line.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_body.add_child(line)

func _disable(c: Node) -> void:
	if c is BaseButton: c.disabled = true
	if c is Slider: c.editable = false
	if c is Control: c.modulate.a = 0.45
	for k in c.get_children():
		if k is BaseButton: k.disabled = true
		if k is Slider: k.editable = false
		_disable_children(k)

func _disable_children(c: Node) -> void:
	for k in c.get_children():
		if k is BaseButton: k.disabled = true
		if k is Slider: k.editable = false
		_disable_children(k)

## `live`: the option follows the handle as it is dragged; otherwise it is set when the handle is let go
## (for options that would move the slider itself). `refill`: the page is redrawn after a change, so a preset
## row can say it has been changed by hand.
func _slider(title: String, hint: String, key: String, lo: float, hi: float, step: float, fmt: Callable, live := true, refill := false, heard := Callable()) -> void:
	var s := HSlider.new(); s.min_value = lo; s.max_value = hi; s.step = step; s.custom_minimum_size = Vector2(360, 24)
	s.value = float(Settings.v(key))
	var val := UiKit.body(String(fmt.call(s.value)), 15, UiTheme.INK)
	var state := {"drag": false}
	s.drag_started.connect(func() -> void: state.drag = true)
	s.value_changed.connect(func(x: float) -> void:
		val.text = String(fmt.call(x))
		if live or not state.drag:
			Settings.set_value(key, x)
			if heard.is_valid(): heard.call()
		if refill and not state.drag: _fill.call_deferred())
	s.drag_ended.connect(func(_changed: bool) -> void:
		state.drag = false
		Settings.set_value(key, s.value)
		if refill: _fill.call_deferred())
	_row(title, hint, s, val)

func _noop() -> void:
	pass

func _check(title: String, hint: String, key: String, refill := false, off := false) -> void:
	var c := CheckBox.new(); c.button_pressed = bool(Settings.v(key))
	c.toggled.connect(func(on: bool) -> void:
		hud.click()
		Settings.set_value(key, on)
		if refill: _fill.call_deferred())
	_row(title, hint, _right(c), null, off)

func _choice(title: String, hint: String, key: String, options: Array, w: float, refill := false, off := false) -> void:
	var seg := UiKit.Segmented.new(options, Settings.v(key), w)
	seg.picked.connect(func(val) -> void:
		hud.click()
		Settings.set_value(key, val)
		if refill: _fill.call_deferred())
	_row(title, hint, _right(seg), null, off)

func _swatch(id: String, col: Color, off: bool) -> Button:
	var b := Button.new(); b.custom_minimum_size = Vector2(44, 34); b.toggle_mode = true; b.focus_mode = Control.FOCUS_NONE
	b.button_pressed = String(Settings.v("crosshair_color")) == id
	b.tooltip_text = id.capitalize()
	b.disabled = off
	b.draw.connect(func() -> void:
		b.draw_circle(b.size * 0.5, 7.0, Color(0, 0, 0, 0.6), true, -1.0, true)
		b.draw_circle(b.size * 0.5, 5.0, Color(col, 0.4 if off else 1.0), true, -1.0, true))
	b.pressed.connect(func() -> void:
		hud.click()
		Settings.set_value("crosshair_color", id)
		_fill.call_deferred())
	return b

# ------------------------------------------------------------------ key bindings
func _bind_row(action: String) -> void:
	var r := HBoxContainer.new(); r.custom_minimum_size = Vector2(0, 42); r.add_theme_constant_override("separation", 8)
	var l := UiKit.body(Settings.action_label(action), 15, UiTheme.INK); l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	r.add_child(l)
	if not Settings.is_default_binding(action):
		var undo := func() -> void:
			_capture = {}; _pending = {}
			Settings.reset_binding(action)
		var rb := hud.button("Reset", undo, 0.0)
		rb.custom_minimum_size = Vector2(0, 28); rb.add_theme_font_size_override("font_size", 12)
		rb.tooltip_text = "Back to %s" % " / ".join(PackedStringArray(_default_names(action)))
		r.add_child(rb)
	var b: Array = Settings.binding(action)
	for slot in 2:
		var waiting: bool = _capture.get("action", "") == action and int(_capture.get("slot", -1)) == slot
		var kb := Button.new(); kb.custom_minimum_size = Vector2(150, 32); kb.focus_mode = Control.FOCUS_NONE
		kb.add_theme_font_size_override("font_size", 14)
		var code := int(b[slot])
		kb.set_meta("bind", [action, slot])
		# the key is drawn as the same key cap the prompts and hints use
		kb.add_theme_stylebox_override("normal", UiTheme.flat_m(Color(UiTheme.ACCENT, 0.26), UiTheme.ACCENT2, 1, 16, 7) if waiting else UiTheme.flat_m(Color(1, 1, 1, 0.02), UiTheme.LINE, 1, 16, 7))
		kb.draw.connect(func() -> void:
			if waiting:
				UiKit.text(kb, "head", Vector2(0, kb.size.y * 0.5 - 10.0), "PRESS A KEY", 13, UiTheme.ACCENT2, HORIZONTAL_ALIGNMENT_CENTER, kb.size.x, false)
			elif code == 0:
				UiKit.text(kb, "body", Vector2(0, kb.size.y * 0.5 - 11.0), "—", 14, UiTheme.FAINT, HORIZONTAL_ALIGNMENT_CENTER, kb.size.x, false)
			else:
				var nm := Settings.code_name(code)
				UiKit.keycap(kb, ((kb.size - UiKit.keycap_size(nm, 13)) * 0.5).floor(), nm, 13, false))
		kb.tooltip_text = ("Second key (optional). " if slot == 1 else "") + "Click, then press a key or mouse button."
		var sl := slot
		kb.pressed.connect(func() -> void:
			hud.click()
			_pending = {}
			_capture = {"action": action, "slot": sl}
			_fill.call_deferred())
		r.add_child(kb)
	r.add_child(UiKit.spacer(0, 2))
	_body.add_child(r)
	if _pending.get("action", "") == action:
		var names: Array = []
		for o in _pending.clash: names.append(Settings.action_label(String(o)))
		var warn := HBoxContainer.new(); warn.add_theme_constant_override("separation", 8); warn.custom_minimum_size = Vector2(0, 40)
		var code2 := int(_pending.code)
		var wl := UiKit.body("%s is already %s." % [Settings.code_name(code2), " and ".join(PackedStringArray(names))], 14, UiTheme.warn())
		wl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		warn.add_child(UiKit.KeyCap.new(Settings.code_name(code2), 13)); warn.add_child(wl)
		var take_it := func() -> void:
			var pd := _pending
			_pending = {}
			Settings.set_binding(String(pd.action), int(pd.slot), int(pd.code), true)
			_fill.call_deferred()
		var take := hud.button("Use it here instead", take_it, 0.0)
		take.custom_minimum_size = Vector2(0, 30); take.add_theme_font_size_override("font_size", 13)
		var leave_it := func() -> void:
			_pending = {}
			_fill.call_deferred()
		var keep := hud.button("Leave it", leave_it, 0.0)
		keep.custom_minimum_size = Vector2(0, 30); keep.add_theme_font_size_override("font_size", 13)
		warn.add_child(take); warn.add_child(keep); warn.add_child(UiKit.spacer(0, 2))
		var box := UiKit.Frame.new(Color(UiTheme.warn(), 0.08), 8.0, Color(UiTheme.warn(), 0.5)); box.marks = false; box.grain = 0.0
		box.add_child(warn)
		_body.add_child(box)
	var line := ColorRect.new(); line.color = Color(1, 1, 1, 0.05); line.custom_minimum_size = Vector2(0, 1); line.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_body.add_child(line)

func _default_names(action: String) -> Array:
	var out: Array = []
	for a in Settings.ACTIONS:
		if String(a[0]) == action:
			for i in [4, 5]:
				if int(a[i]) != 0: out.append(Settings.code_name(int(a[i])))
	return out

func capturing() -> bool:
	return _built and not _capture.is_empty()

## the HUD hands every key and mouse press here first; true means it was meant for the page
func input(ev: InputEvent) -> bool:
	if not _built:
		return false
	if _confirm != null and ev is InputEventKey and ev.pressed and not ev.echo:
		if ev.physical_keycode == KEY_ESCAPE: _revert_display()
		elif ev.physical_keycode == KEY_ENTER or ev.physical_keycode == KEY_KP_ENTER: _keep_display()
		return true
	if _capture.is_empty():
		return false
	if not ((ev is InputEventKey or ev is InputEventMouseButton) and ev.pressed) or ev.is_echo():
		return ev is InputEventKey or ev is InputEventMouseButton
	var action := String(_capture.action); var slot := int(_capture.slot)
	var code := Settings.event_code(ev)
	_capture = {}
	if ev is InputEventKey and ev.physical_keycode == KEY_ESCAPE and action != "pause":
		pass                                               # cancelled
	elif ev is InputEventKey and (ev.physical_keycode == KEY_BACKSPACE or ev.physical_keycode == KEY_DELETE):
		Settings.set_binding(action, slot, 0)
	elif code == -4 or code == -5 or code == -6 or code == -7:
		pass                                               # the wheel cannot be held, so it is not offered
	else:
		var clash: Array = Settings.conflicts(action, code)
		if clash.is_empty():
			Settings.set_binding(action, slot, code)
		else:
			_pending = {"action": action, "slot": slot, "code": code, "clash": clash}
	_fill.call_deferred()
	return true

# ------------------------------------------------------------------ "keep these display settings?"
func _display_change(key: String, value: Variant) -> void:
	if Settings.v(key) == value:
		return
	if _confirm == null:
		_confirm_prev = {"window_mode": Settings.v("window_mode"), "window_size": Settings.v("window_size")}
	Settings.set_value(key, value)
	_fill.call_deferred()
	_confirm_t = KEEP_SECONDS
	if _confirm != null or _host == null or not is_instance_valid(_host):
		return
	_confirm = Control.new(); _confirm.set_anchors_preset(Control.PRESET_FULL_RECT)
	var shade := ColorRect.new(); shade.set_anchors_preset(Control.PRESET_FULL_RECT); shade.color = Color(0, 0, 0, 0.7)
	_confirm.add_child(shade)
	var cc := CenterContainer.new(); cc.set_anchors_preset(Control.PRESET_FULL_RECT)
	var frame := UiKit.Frame.new(UiTheme.PANEL, 26.0, UiTheme.LINE2)
	var v := VBoxContainer.new(); v.add_theme_constant_override("separation", 12); v.custom_minimum_size = Vector2(460, 0)
	v.add_child(UiKit.heading("Keep these display settings?", 20, UiTheme.ACCENT2))
	_confirm_lbl = UiKit.body("", 15, UiTheme.INK, true); _confirm_lbl.custom_minimum_size = Vector2(460, 0)
	v.add_child(_confirm_lbl)
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 10); row.alignment = BoxContainer.ALIGNMENT_END
	var keep := hud.button("Keep", func() -> void: _keep_display(), 150.0)
	keep.add_theme_stylebox_override("normal", UiTheme.flat_m(Color(UiTheme.ACCENT, 0.12), UiTheme.ACCENT, 1, 16, 7))
	row.add_child(keep)
	row.add_child(hud.button("Go back", func() -> void: _revert_display(), 150.0))
	v.add_child(UiKit.spacer(4)); v.add_child(row)
	frame.add_child(v); cc.add_child(frame); _confirm.add_child(cc)
	_host.add_child(_confirm)
	tick(0.0)

func _keep_display() -> void:
	if _confirm != null and is_instance_valid(_confirm):
		_confirm.queue_free()
	_confirm = null; _confirm_prev = {}

func _revert_display() -> void:
	var prev := _confirm_prev
	if _confirm != null and is_instance_valid(_confirm):
		_confirm.queue_free()
	_confirm = null; _confirm_prev = {}
	for k in prev:
		Settings.set_value(k, prev[k])
	_fill.call_deferred()

## called every frame by the HUD while the pages are up
func tick(dt: float) -> void:
	if _confirm == null:
		return
	_confirm_t -= dt
	if _confirm_t <= 0.0:
		_revert_display()
		return
	var txt := "If the picture is wrong or you cannot see this, do nothing: the old settings come back in %d seconds." % int(ceil(_confirm_t))
	if _confirm_lbl != null and _confirm_lbl.text != txt: _confirm_lbl.text = txt
