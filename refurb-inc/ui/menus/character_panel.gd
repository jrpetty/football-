class_name CharacterPanel
extends PanelContainer
## Character menu (main menu + pause menu): colours, hi-vis, cap, height, big-head mode, and a photo face —
## pick any picture and it becomes your character's face (a cut-out stuck on the front of the head).
## A live 3D preview turns on the left. Co-op players see your look and face.

signal closed

var _rig: CharacterRig
var _pivot: Node3D
var _status: Label
var _dialog: FileDialog


func _ready() -> void:
	add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG_SOLID, 14, 24))
	custom_minimum_size = Vector2(980, 0)
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 24)
	add_child(h)
	h.add_child(_preview())
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 10)
	v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(v)
	v.add_child(UiStyle.label("Your character", 30, UiStyle.TEXT))
	var name_row := _row(v, "Name")
	var le := LineEdit.new()
	le.text = Net.local_name
	le.max_length = 24
	le.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	le.text_changed.connect(func(t):
		Net.local_name = t
		Settings.set_value("player_name", t))
	name_row.add_child(le)
	_choice(v, "Build", "char_body", ["m", "f"], ["Male", "Female"])
	_choice(v, "Hair", "char_hairstyle", Looks.HAIR_STYLES, ["Short", "Side part", "Long", "Bun", "Buzz cut", "Curly", "Bald"])
	_swatches(v, "Shirt", "char_shirt", Looks.SHIRTS)
	_swatches(v, "Trousers", "char_trousers", Looks.TROUSERS)
	_swatches(v, "Skin", "char_skin", Looks.SKINS)
	_swatches(v, "Hair colour", "char_hair", Looks.HAIRS)
	_swatches(v, "Shoes", "char_shoes", Looks.SHOES)
	_toggle(v, "Hi-vis vest", "char_hivis")
	_toggle(v, "Cap", "char_cap")
	_toggle(v, "Big head mode", "char_bighead")
	_toggle(v, "Steam picture as my face (if no photo)", "char_steamface")
	var hs := _row(v, "Height")
	var s := HSlider.new()
	s.min_value = 1.55
	s.max_value = 1.95
	s.step = 0.01
	s.value = float(Settings.get_value("char_height"))
	s.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var hl := UiStyle.label("%.2f m" % s.value, 16, UiStyle.MUTED)
	s.value_changed.connect(func(x):
		hl.text = "%.2f m" % x
		Settings.set_value("char_height", x)
		_refresh())
	hs.add_child(s)
	hs.add_child(hl)
	v.add_child(UiStyle.label("Face photo", 20, UiStyle.ACCENT))
	v.add_child(UiStyle.label("Pick any picture — yours, a mate's, your boss's — and it goes on your character's face. Co-op players will see it.", 15, UiStyle.MUTED))
	var fr := HBoxContainer.new()
	fr.add_theme_constant_override("separation", 10)
	v.add_child(fr)
	var up := Button.new()
	up.text = "Choose a photo…"
	up.pressed.connect(_choose_photo)
	fr.add_child(up)
	var clr := Button.new()
	clr.text = "Remove photo"
	clr.pressed.connect(func():
		Looks.clear_photo()
		_status.text = "Back to the default face."
		_refresh())
	fr.add_child(clr)
	_status = UiStyle.label("", 15, UiStyle.GOOD)
	v.add_child(_status)
	var back := Button.new()
	back.text = "Done"
	back.pressed.connect(func():
		Settings.save_settings()
		if Game.in_game:
			Game.share_look()
		closed.emit())
	v.add_child(back)
	_dialog = FileDialog.new()
	_dialog.file_mode = FileDialog.FILE_MODE_OPEN_FILE
	_dialog.access = FileDialog.ACCESS_FILESYSTEM
	_dialog.use_native_dialog = true
	_dialog.filters = PackedStringArray(["*.png, *.jpg, *.jpeg, *.webp ; Pictures"])
	_dialog.title = "Choose a face photo"
	_dialog.file_selected.connect(func(path: String):
		var err := Looks.set_photo(path)
		_status.text = err if err != "" else "Lovely. That's your face now."
		_status.add_theme_color_override("font_color", UiStyle.BAD if err != "" else UiStyle.GOOD)
		_refresh())
	add_child(_dialog)
	_refresh()


func _preview() -> Control:
	var svc := SubViewportContainer.new()
	svc.custom_minimum_size = Vector2(360, 520)
	svc.stretch = true
	var vp := SubViewport.new()
	vp.size = Vector2i(360, 520)
	vp.own_world_3d = true
	vp.transparent_bg = true
	vp.msaa_3d = Viewport.MSAA_4X
	svc.add_child(vp)
	var env := WorldEnvironment.new()
	var e := Environment.new()
	e.background_mode = Environment.BG_CLEAR_COLOR
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color(0.75, 0.78, 0.85)
	e.ambient_light_energy = 0.7
	e.tonemap_mode = Environment.TONE_MAPPER_AGX
	env.environment = e
	vp.add_child(env)
	var key := DirectionalLight3D.new()
	key.rotation_degrees = Vector3(-30, 35, 0)
	key.light_energy = 1.4
	vp.add_child(key)
	var cam := Camera3D.new()
	cam.position = Vector3(0, 1.15, 2.6)
	cam.fov = 38
	vp.add_child(cam)
	cam.look_at(Vector3(0, 1.0, 0))
	_pivot = Node3D.new()
	vp.add_child(_pivot)
	_rig = CharacterRig.new()
	_rig.anim = "idle"
	_pivot.add_child(_rig)
	var floor_disc := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = 0.55
	cm.bottom_radius = 0.55
	cm.height = 0.02
	floor_disc.mesh = cm
	floor_disc.material_override = Mats.color(Color(0.18, 0.2, 0.24), 0.5)
	floor_disc.position.y = -0.01
	vp.add_child(floor_disc)
	return svc


func _process(delta: float) -> void:
	if visible and _pivot:
		_pivot.rotation.y += delta * 0.6


func _refresh() -> void:
	if _rig and _rig.is_inside_tree():
		Looks.apply(_rig, Looks.local_look(), Looks.face_png())


func _choose_photo() -> void:
	_dialog.popup_centered(Vector2i(900, 600))


func _row(v: VBoxContainer, label: String) -> HBoxContainer:
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 8)
	v.add_child(h)
	var l := UiStyle.label(label, 17)
	l.custom_minimum_size.x = 150
	h.add_child(l)
	return h


func _swatches(v: VBoxContainer, label: String, key: String, cols: Array) -> void:
	var h := _row(v, label)
	for c in cols:
		var b := Button.new()
		b.custom_minimum_size = Vector2(34, 34)
		var sb := UiStyle.box(Color(str(c)), 6, 0, Color(1, 1, 1, 0.9) if str(Settings.get_value(key)) == str(c) else Color(0, 0, 0, 0))
		sb.set_border_width_all(3 if str(Settings.get_value(key)) == str(c) else 0)
		b.add_theme_stylebox_override("normal", sb)
		b.add_theme_stylebox_override("hover", sb)
		b.add_theme_stylebox_override("pressed", sb)
		b.pressed.connect(func():
			Settings.set_value(key, str(c))
			_restyle(h, key, cols)
			_refresh())
		h.add_child(b)


func _restyle(h: HBoxContainer, key: String, cols: Array) -> void:
	var i := 0
	for b in h.get_children():
		if not b is Button:
			continue
		var c: String = cols[i]
		var on := str(Settings.get_value(key)) == c
		var sb := UiStyle.box(Color(c), 6, 0, Color(1, 1, 1, 0.9) if on else Color(0, 0, 0, 0))
		sb.set_border_width_all(3 if on else 0)
		for st in ["normal", "hover", "pressed"]:
			(b as Button).add_theme_stylebox_override(st, sb)
		i += 1


func _choice(v: VBoxContainer, label: String, key: String, values: Array, names: Array) -> void:
	var h := _row(v, label)
	var ob := OptionButton.new()
	for i in names.size():
		ob.add_item(names[i], i)
	ob.selected = maxi(values.find(str(Settings.get_value(key))), 0)
	ob.item_selected.connect(func(i):
		Settings.set_value(key, values[i])
		_refresh())
	h.add_child(ob)


func _toggle(v: VBoxContainer, label: String, key: String) -> void:
	var h := _row(v, label)
	var cb := CheckBox.new()
	cb.button_pressed = bool(Settings.get_value(key))
	cb.toggled.connect(func(on):
		Settings.set_value(key, on)
		_refresh())
	h.add_child(cb)
