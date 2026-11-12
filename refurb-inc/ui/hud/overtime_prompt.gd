class_name OvertimePrompt
extends CanvasLayer
## "Keep going?" at closing time (night shift licence). The clock waits at 17:00 while the host chooses home
## time or overtime until 20:00, 22:00 or right through to 06:00; guests see who's deciding. Created by the HUD.

var _root: Control
var _body: VBoxContainer
var _shown := false


func _ready() -> void:
	layer = 26
	process_mode = Node.PROCESS_MODE_ALWAYS
	_root = Control.new()
	_root.set_anchors_preset(Control.PRESET_FULL_RECT)
	_root.theme = UiStyle.theme()
	add_child(_root)
	var dim := ColorRect.new()
	dim.color = Color(0.02, 0.03, 0.06, 0.55)
	dim.set_anchors_preset(Control.PRESET_FULL_RECT)
	_root.add_child(dim)
	var center := CenterContainer.new()
	center.set_anchors_preset(Control.PRESET_FULL_RECT)
	_root.add_child(center)
	var panel := PanelContainer.new()
	var sb := UiStyle.box(UiStyle.BG_SOLID, 16, 30, Color(0.45, 0.55, 0.95, 0.6))
	sb.set_border_width_all(2)
	panel.add_theme_stylebox_override("panel", sb)
	panel.custom_minimum_size = Vector2(640, 0)
	center.add_child(panel)
	_body = VBoxContainer.new()
	_body.add_theme_constant_override("separation", 12)
	panel.add_child(_body)
	_root.visible = false


func _process(_d: float) -> void:
	var want := Game.in_game and Game.state.overtime_asking()
	if want != _shown:
		_shown = want
		if want:
			_build()
			_root.visible = true
			Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
			Sfx.play_ui("notify_warn", -6.0)
		else:
			_root.visible = false
			var p: Node = LevelRoot.current.local_player() if LevelRoot.current else null
			if p and "ui_mode" in p and not p.ui_mode and Game.state.day_running:
				Input.mouse_mode = Input.MOUSE_MODE_CAPTURED


static func hourly_cost(st: GameState) -> float:
	## Staff time-and-a-half per overtime hour (everyone on the books who isn't away on a course).
	var cfg := Sim.overtime_cfg()
	var total := 0.0
	for d in st.staff.values():
		if not StaffMorale.is_away(d):
			total += float(d.get("wage", 0))
	return total / float(cfg.get("hours_per_week", 40)) * float(cfg.get("pay_mult", 1.5))


func _build() -> void:
	for c in _body.get_children():
		c.queue_free()
	var s := Game.state
	_body.add_child(UiStyle.label("🌙  It's 17:00. Keep going?", 30, UiStyle.TEXT))
	var hourly := hourly_cost(s)
	var lines: PackedStringArray = []
	if s.staff.is_empty():
		lines.append("No staff on the books, so overtime only costs electricity.")
	else:
		lines.append("%d staff on shift: time-and-a-half is about %s an hour, paid with Friday's wages, and late nights wear them down." % [
			s.staff.values().filter(func(d): return not StaffMorale.is_away(d)).size(), GameState.format_money(int(round(hourly)))])
	lines.append("Lorries and couriers keep their usual times. The meter keeps running, and after midnight you'll flag without a brew.")
	var info := UiStyle.label("\n".join(lines), 16, UiStyle.MUTED)
	info.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	info.custom_minimum_size.x = 580
	_body.add_child(info)
	if not Game.is_host():
		_body.add_child(UiStyle.label("The host is deciding…", 20, UiStyle.INFO))
		return
	var grid := VBoxContainer.new()
	grid.add_theme_constant_override("separation", 8)
	_body.add_child(grid)
	for o: Dictionary in Sim.overtime_options():
		var hours := float(o.end_hour) - float(Content.econ("day_end_hour", 17))
		var b := Button.new()
		b.custom_minimum_size.y = 50
		b.add_theme_font_size_override("font_size", 19)
		b.text = "%s   ·   %d h%s" % [str(o.name), int(hours), ("   ·   ≈ %s staff overtime" % GameState.format_money(int(round(hourly * hours)))) if hourly > 0.0 else ""]
		var id := str(o.id)
		b.pressed.connect(func():
			Sfx.play_ui("ui_click", -6.0)
			Game.request("overtime", {"choice": id}))
		grid.add_child(b)
	var close := Button.new()
	close.custom_minimum_size.y = 50
	close.add_theme_font_size_override("font_size", 19)
	close.text = "Call it a day (close at 17:00)"
	close.add_theme_stylebox_override("normal", UiStyle.box(Color(0.2, 0.4, 0.25), 8, 10))
	close.add_theme_stylebox_override("hover", UiStyle.box(Color(0.25, 0.5, 0.3), 8, 10))
	close.pressed.connect(func():
		Sfx.play_ui("ui_click", -6.0)
		Game.request("overtime", {"choice": "close"}))
	grid.add_child(close)
