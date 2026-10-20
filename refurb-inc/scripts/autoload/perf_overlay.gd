extends CanvasLayer
## Always-on performance readout (top-right): FPS, frame time and the 1% low over the last ~2 s, colour-coded.
## Works everywhere (menus, office PC, in game). Toggle with F3 or Settings → Show FPS.

var _label: Label
var _times: PackedFloat32Array = []
var _accum := 0.0


func _ready() -> void:
	layer = 100
	process_mode = Node.PROCESS_MODE_ALWAYS
	_label = Label.new()
	_label.set_anchors_preset(Control.PRESET_TOP_RIGHT)
	_label.position = Vector2(-250, 8)
	_label.size = Vector2(240, 24)
	_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	_label.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_label.add_theme_font_size_override("font_size", 15)
	_label.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.85))
	_label.add_theme_constant_override("outline_size", 5)
	add_child(_label)


func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventKey and event.pressed and not event.echo and event.keycode == KEY_F3:
		Settings.set_value("show_fps", not bool(Settings.get_value("show_fps")))
		Settings.save_settings()


func _process(delta: float) -> void:
	var on := bool(Settings.get_value("show_fps"))
	_label.visible = on
	_times.append(delta)
	if _times.size() > 240:
		_times = _times.slice(_times.size() - 240)
	_accum += delta
	if not on or _accum < 0.25:
		return
	_accum = 0.0
	var sorted := _times.duplicate()
	sorted.sort()
	var worst := sorted[int(sorted.size() * 0.99) - 1] if sorted.size() > 10 else delta
	var fps := Engine.get_frames_per_second()
	var avg_ms := 0.0
	for t in _times:
		avg_ms += t
	avg_ms = avg_ms / maxf(_times.size(), 1) * 1000.0
	_label.text = "%d FPS · %.1f ms · 1%% low %d" % [fps, avg_ms, int(round(1.0 / maxf(worst, 0.0001)))]
	_label.add_theme_color_override("font_color", Color(0.45, 1.0, 0.55) if fps >= 55 else (Color(1.0, 0.8, 0.3) if fps >= 30 else Color(1.0, 0.4, 0.35)))
