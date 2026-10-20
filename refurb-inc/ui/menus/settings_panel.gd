class_name SettingsPanel
extends PanelContainer
## Settings UI used by both the main menu and the pause menu.

signal closed


func _ready() -> void:
	add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG_SOLID, 14, 24))
	custom_minimum_size = Vector2(620, 0)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 12)
	add_child(v)
	v.add_child(UiStyle.label("Settings", 30, UiStyle.TEXT))
	_slider(v, "Mouse sensitivity", "mouse_sensitivity", 0.02, 0.5, 0.01)
	_slider(v, "Field of view", "fov", 60, 100, 1)
	_check(v, "Invert mouse Y", "invert_y")
	_check(v, "Head bob", "head_bob")
	_slider(v, "Head bob strength", "head_bob_strength", 0.0, 1.5, 0.05)
	_slider(v, "Master volume", "master_volume", 0, 1, 0.05)
	_slider(v, "Effects volume", "sfx_volume", 0, 1, 0.05)
	_slider(v, "Ambience volume", "ambience_volume", 0, 1, 0.05)
	_slider(v, "Music volume", "music_volume", 0, 1, 0.05)
	_option(v, "Graphics quality", "graphics_preset", ["low", "medium", "high", "ultra"])
	_check(v, "Fullscreen", "fullscreen")
	_check(v, "V-Sync", "vsync")
	_check(v, "Show FPS", "show_fps")
	var name_row := HBoxContainer.new()
	v.add_child(name_row)
	var nl := UiStyle.label("Player name", 17)
	nl.custom_minimum_size.x = 240
	name_row.add_child(nl)
	var le := LineEdit.new()
	le.text = Net.local_name
	le.max_length = 24
	le.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	le.text_changed.connect(func(t):
		Net.local_name = t
		Settings.set_value("player_name", t))
	name_row.add_child(le)
	var back := Button.new()
	back.text = "Back"
	back.pressed.connect(func(): closed.emit())
	v.add_child(back)


func _row(v: VBoxContainer, label: String) -> HBoxContainer:
	var h := HBoxContainer.new()
	v.add_child(h)
	var l := UiStyle.label(label, 17)
	l.custom_minimum_size.x = 240
	h.add_child(l)
	return h


func _slider(v: VBoxContainer, label: String, key: String, lo: float, hi: float, step: float) -> void:
	var h := _row(v, label)
	var s := HSlider.new()
	s.min_value = lo
	s.max_value = hi
	s.step = step
	s.value = float(Settings.get_value(key))
	s.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	s.custom_minimum_size.x = 260
	var val := UiStyle.label(str(snappedf(s.value, step)), 15, UiStyle.MUTED)
	val.custom_minimum_size.x = 60
	s.value_changed.connect(func(x):
		val.text = str(snappedf(x, step))
		Settings.set_value(key, x))
	h.add_child(s)
	h.add_child(val)


func _check(v: VBoxContainer, label: String, key: String) -> void:
	var h := _row(v, label)
	var c := CheckButton.new()
	c.button_pressed = bool(Settings.get_value(key))
	c.toggled.connect(func(on): Settings.set_value(key, on))
	h.add_child(c)


func _option(v: VBoxContainer, label: String, key: String, opts: Array) -> void:
	var h := _row(v, label)
	var o := OptionButton.new()
	for i in opts.size():
		o.add_item(String(opts[i]).capitalize(), i)
		if opts[i] == Settings.get_value(key):
			o.select(i)
	o.item_selected.connect(func(i): Settings.set_value(key, opts[i]))
	h.add_child(o)
