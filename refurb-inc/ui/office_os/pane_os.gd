class_name PaneOS
extends Control
## Fake desktop OS on the office PC. Apps open in a window; taskbar shows the live clock and balance.

const APPS := [
	{"id": "purchasing", "title": "LotFinder — Purchasing", "short": "Purchasing", "colour": Color(0.2, 0.55, 0.95), "glyph": "£", "script": "res://ui/office_os/apps/purchasing_app.gd"},
	{"id": "flogit", "title": "Flogit Seller Hub", "short": "Flogit", "colour": Color(0.95, 0.35, 0.55), "glyph": "F", "script": "res://ui/office_os/apps/flogit_app.gd"},
	{"id": "business", "title": "Business Buyers", "short": "Business", "colour": Color(0.16, 0.36, 0.58), "glyph": "◆", "script": "res://ui/office_os/apps/business_app.gd"},
	{"id": "stock", "title": "Stock", "short": "Stock", "colour": Color(0.3, 0.75, 0.45), "glyph": "▤", "script": "res://ui/office_os/apps/stock_app.gd"},
	{"id": "shop", "title": "PartsHub — Shop", "short": "Shop", "colour": Color(0.95, 0.6, 0.15), "glyph": "⚙", "script": "res://ui/office_os/apps/shop_app.gd"},
	{"id": "finance", "title": "Wages & Finance", "short": "Finance", "colour": Color(0.55, 0.45, 0.9), "glyph": "%", "script": "res://ui/office_os/apps/finance_app.gd"},
	{"id": "insights", "title": "Business Insights", "short": "Insights", "colour": Color(0.88, 0.68, 0.12), "glyph": "▲", "script": "res://ui/office_os/apps/insights_app.gd"},
	{"id": "mail", "title": "Mail", "short": "Mail", "colour": Color(0.25, 0.7, 0.85), "glyph": "@", "script": "res://ui/office_os/apps/mail_app.gd"},
	{"id": "skills", "title": "My Skills", "short": "Skills", "colour": Color(0.9, 0.4, 0.3), "glyph": "★", "script": "res://ui/office_os/apps/skills_app.gd"},
	{"id": "goals", "title": "Goals & Records", "short": "Goals", "colour": Color(0.85, 0.65, 0.1), "glyph": "♛", "script": "res://ui/office_os/apps/goals_app.gd"},
	{"id": "achievements", "title": "Achievements", "short": "Achievements", "colour": Color(0.78, 0.36, 0.16), "glyph": "✪", "script": "res://ui/office_os/apps/achievements_app.gd"},
	{"id": "staff", "title": "Staff", "short": "Staff", "colour": Color(0.3, 0.62, 0.45), "glyph": "☺", "script": "res://ui/office_os/apps/staff_app.gd"},
	{"id": "building", "title": "Premises & Equipment", "short": "Building", "colour": Color(0.2, 0.6, 0.6), "glyph": "⌂", "script": "res://ui/office_os/apps/building_app.gd"},
]

var pc: OfficePC
var _window: PanelContainer
var _window_title: Label
var _window_body: Control
var _current: PaneApp = null
var _current_id := ""
var _clock: Label
var _cash: Label
var _task_buttons := {}
var _refresh_pending := false


func _ready() -> void:
	theme = _os_theme()
	# Wallpaper
	var wall := ColorRect.new()
	wall.set_anchors_preset(Control.PRESET_FULL_RECT)
	wall.color = Color(0.1, 0.16, 0.24)
	add_child(wall)
	var glow := _Wallpaper.new()
	glow.set_anchors_preset(Control.PRESET_FULL_RECT)
	add_child(glow)
	# Desktop icons
	var grid := GridContainer.new()
	grid.columns = 2
	grid.position = Vector2(20, 30)
	grid.add_theme_constant_override("h_separation", 6)
	grid.add_theme_constant_override("v_separation", 14)
	add_child(grid)
	for a in APPS:
		grid.add_child(_desktop_icon(a))
	# Window
	_window = PanelContainer.new()
	_window.add_theme_stylebox_override("panel", UiStyle.box(Color(0.96, 0.97, 0.98), 10, 0))
	_window.position = Vector2(270, 40)
	_window.size = Vector2(size.x - 310, size.y - 130)
	_window.visible = false
	add_child(_window)
	var wv := VBoxContainer.new()
	wv.add_theme_constant_override("separation", 0)
	_window.add_child(wv)
	var bar := PanelContainer.new()
	var bar_style := UiStyle.box(Color(0.9, 0.91, 0.93), 10, 10)
	bar_style.corner_radius_bottom_left = 0
	bar_style.corner_radius_bottom_right = 0
	bar.add_theme_stylebox_override("panel", bar_style)
	wv.add_child(bar)
	var bh := HBoxContainer.new()
	bar.add_child(bh)
	_window_title = _l("", 22, Color(0.15, 0.17, 0.2))
	_window_title.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	bh.add_child(_window_title)
	var close := Button.new()
	close.text = "  ✕  "
	close.pressed.connect(close_app)
	bh.add_child(close)
	_window_body = MarginContainer.new()
	_window_body.size_flags_vertical = Control.SIZE_EXPAND_FILL
	for side in ["left", "right", "top", "bottom"]:
		_window_body.add_theme_constant_override("margin_" + side, 18)
	wv.add_child(_window_body)
	# Taskbar
	var tb := PanelContainer.new()
	tb.add_theme_stylebox_override("panel", UiStyle.box(Color(0.06, 0.07, 0.09, 0.92), 0, 10))
	tb.set_anchors_preset(Control.PRESET_BOTTOM_WIDE)
	tb.offset_top = -64
	add_child(tb)
	var th := HBoxContainer.new()
	th.add_theme_constant_override("separation", 10)
	tb.add_child(th)
	var logo := _PaneLogo.new()
	logo.custom_minimum_size = Vector2(44, 44)
	th.add_child(logo)
	for a in APPS:
		if a.script == "":
			continue
		# Icon-only taskbar buttons (the name is in the tooltip and on the desktop), so every app fits.
		var b := Button.new()
		b.text = a.glyph
		b.tooltip_text = a.title
		b.custom_minimum_size = Vector2(52, 44)
		b.add_theme_font_size_override("font_size", 22)
		for st in ["normal", "hover", "pressed"]:
			var col: Color = a.colour.lightened(0.12 if st == "hover" else 0.0).darkened(0.15 if st == "pressed" else 0.0)
			b.add_theme_stylebox_override(st, UiStyle.box(col, 10, 6))
		b.pressed.connect(open_app.bind(a.id))
		th.add_child(b)
		_task_buttons[a.id] = b
	var spacer := Control.new()
	spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	th.add_child(spacer)
	var exit := Button.new()
	exit.text = "  Step away (Esc)  "
	exit.pressed.connect(func(): if pc: pc.leave())
	th.add_child(exit)
	_cash = _l("", 22, UiStyle.GOOD)
	th.add_child(_cash)
	_clock = _l("", 22, Color.WHITE)
	_clock.custom_minimum_size.x = 150
	_clock.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	th.add_child(_clock)
	Game.state_reset.connect(_on_reset)
	_on_reset()


func _on_reset() -> void:
	if not Game.state.changed.is_connected(_on_state_changed):
		Game.state.changed.connect(_on_state_changed)
	_queue_refresh()


func _on_state_changed(kind: String, _id: Variant) -> void:
	if kind == "clock":
		return
	_queue_refresh()


func _queue_refresh() -> void:
	if _refresh_pending:
		return
	_refresh_pending = true
	(func():
		_refresh_pending = false
		if _current and is_instance_valid(_current) and is_visible_in_tree():
			_current.refresh()).call_deferred()


func _process(_d: float) -> void:
	_clock.text = "%s  %s" % [Game.state.weekday_name().substr(0, 3), Game.state.clock_text()]
	_cash.text = GameState.format_money(Game.state.cash) + "   "
	_cash.add_theme_color_override("font_color", UiStyle.money_colour(Game.state.cash))
	if _window.visible:
		_window.size = Vector2(size.x - 310, size.y - 130)


func on_opened() -> void:
	if _current == null:
		open_app("mail" if not _seen_mail else "purchasing")
	else:
		_current.refresh()


var _seen_mail := false


func open_app(id: String) -> void:
	var def: Dictionary = {}
	for a in APPS:
		if a.id == id:
			def = a
	if def.is_empty():
		return
	if def.script == "":
		_show_coming_soon(def)
		return
	if id == "mail":
		_seen_mail = true
	if _current:
		_current.queue_free()
		_current = null
	_current = load(def.script).new()
	_current.os = self
	_current.size_flags_vertical = Control.SIZE_EXPAND_FILL
	_window_body.add_child(_current)
	_current_id = id
	_window_title.text = "  " + def.title
	_window.visible = true
	_window.scale = Vector2(0.97, 0.97)
	_window.pivot_offset = _window.size / 2
	_window.modulate.a = 0.0
	var tw := create_tween().set_parallel(true)
	tw.tween_property(_window, "scale", Vector2.ONE, 0.15)
	tw.tween_property(_window, "modulate:a", 1.0, 0.12)
	_current.build()
	_current.refresh()
	_mark_taskbar()
	Sfx.play_ui("ui_click", -8.0)


func _mark_taskbar() -> void:
	for id in _task_buttons:
		_task_buttons[id].modulate = Color.WHITE if id == _current_id or _current_id == "" else Color(1, 1, 1, 0.55)


func _show_coming_soon(def: Dictionary) -> void:
	if _current:
		_current.queue_free()
	_current = PaneApp.new()
	_current.os = self
	_window_body.add_child(_current)
	_window_title.text = "  " + def.title
	_window.visible = true
	var v := VBoxContainer.new()
	_current.add_child(v)
	v.add_child(_l("%s isn't set up yet." % def.title, 28, Color(0.2, 0.22, 0.26)))
	v.add_child(_l("Hiring staff and moving premises arrive as your business grows (coming in a later update).", 20, Color(0.4, 0.42, 0.46)))


func close_app() -> void:
	if _current:
		_current.queue_free()
		_current = null
	_window.visible = false
	_current_id = ""
	_mark_taskbar()


func consume_escape() -> bool:
	if _current and _current.has_method("consume_escape") and _current.consume_escape():
		return true
	return false


func _desktop_icon(a: Dictionary) -> Control:
	var b := Button.new()
	b.flat = true
	b.custom_minimum_size = Vector2(110, 100)
	b.tooltip_text = a.title
	var v := VBoxContainer.new()
	v.alignment = BoxContainer.ALIGNMENT_CENTER
	v.mouse_filter = Control.MOUSE_FILTER_IGNORE
	v.set_anchors_preset(Control.PRESET_FULL_RECT)
	b.add_child(v)
	var tile := PanelContainer.new()
	tile.mouse_filter = Control.MOUSE_FILTER_IGNORE
	tile.add_theme_stylebox_override("panel", UiStyle.box(a.colour, 14, 8))
	tile.custom_minimum_size = Vector2(64, 64)
	tile.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	var g := _l(a.glyph, 32, Color.WHITE)
	g.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	g.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	tile.add_child(g)
	v.add_child(tile)
	var t := _l(a.short, 17, Color.WHITE)
	t.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(t)
	if a.script == "":
		b.modulate = Color(1, 1, 1, 0.55)
	b.pressed.connect(open_app.bind(a.id))
	return b


func _l(t: String, s: int, c: Color) -> Label:
	return UiStyle.label(t, s, c)


static func _os_theme() -> Theme:
	var t := Theme.new()
	t.default_font_size = 20
	t.set_color("font_color", "Label", Color(0.15, 0.17, 0.2))
	var btn := UiStyle.box(Color(0.2, 0.45, 0.85), 8, 12)
	var btn_h := UiStyle.box(Color(0.25, 0.52, 0.95), 8, 12)
	var btn_p := UiStyle.box(Color(0.15, 0.35, 0.7), 8, 12)
	var btn_d := UiStyle.box(Color(0.75, 0.77, 0.8), 8, 12)
	t.set_stylebox("normal", "Button", btn)
	t.set_stylebox("hover", "Button", btn_h)
	t.set_stylebox("pressed", "Button", btn_p)
	t.set_stylebox("disabled", "Button", btn_d)
	t.set_stylebox("focus", "Button", StyleBoxEmpty.new())
	t.set_color("font_color", "Button", Color.WHITE)
	t.set_color("font_hover_color", "Button", Color.WHITE)
	t.set_color("font_pressed_color", "Button", Color.WHITE)
	t.set_color("font_disabled_color", "Button", Color(0.45, 0.47, 0.5))
	t.set_stylebox("panel", "PanelContainer", UiStyle.box(Color(1, 1, 1), 8, 12))
	t.set_stylebox("normal", "LineEdit", UiStyle.box(Color(1, 1, 1), 6, 8, Color(0.75, 0.78, 0.82)))
	t.set_stylebox("focus", "LineEdit", UiStyle.box(Color(1, 1, 1), 6, 8, Color(0.2, 0.45, 0.85)))
	t.set_color("font_color", "LineEdit", Color(0.1, 0.1, 0.12))
	t.set_stylebox("panel", "ScrollContainer", StyleBoxEmpty.new())
	t.set_stylebox("tab_selected", "TabBar", UiStyle.box(Color(1, 1, 1), 6, 12))
	t.set_stylebox("tab_unselected", "TabBar", UiStyle.box(Color(0.88, 0.89, 0.91), 6, 12))
	t.set_stylebox("tab_hovered", "TabBar", UiStyle.box(Color(0.94, 0.95, 0.96), 6, 12))
	t.set_color("font_selected_color", "TabBar", Color(0.1, 0.1, 0.12))
	t.set_color("font_unselected_color", "TabBar", Color(0.35, 0.37, 0.4))
	t.set_color("font_hovered_color", "TabBar", Color(0.2, 0.2, 0.22))
	t.set_stylebox("normal", "SpinBox", UiStyle.box(Color(1, 1, 1), 6, 4))
	return t


class _Wallpaper extends Control:
	func _draw() -> void:
		var s := size
		for i in 18:
			var t := i / 18.0
			draw_circle(Vector2(s.x * 0.78, s.y * 0.32), s.x * (0.5 - t * 0.026) , Color(0.18 + t * 0.1, 0.32 + t * 0.12, 0.45 + t * 0.1, 0.05))
		# four panes logo watermark
		var c := Vector2(s.x * 0.78, s.y * 0.32)
		for i in 4:
			var o := Vector2((i % 2) * 74 - 74, (i / 2) * 74 - 74)
			draw_rect(Rect2(c + o, Vector2(66, 66)), Color(0.55, 0.85, 0.95, 0.12) if i != 3 else Color(0.98, 0.6, 0.3, 0.16))
		draw_string(ThemeDB.fallback_font, c + Vector2(-74, 110), "PaneOS", HORIZONTAL_ALIGNMENT_LEFT, -1, 40, Color(1, 1, 1, 0.18))


class _PaneLogo extends Control:
	func _draw() -> void:
		for i in 4:
			var o := Vector2((i % 2) * 20 + 3, (i / 2) * 20 + 3)
			draw_rect(Rect2(o, Vector2(17, 17)), Color(0.55, 0.85, 0.95) if i != 3 else Color(0.98, 0.6, 0.3))
