class_name DaySummary
extends CanvasLayer
## End-of-day shift card (shared in co-op). Host presses "Start next day".

var _root: Control
var _body: VBoxContainer
var _button: Button
var _summary := {}


func _ready() -> void:
	layer = 25
	process_mode = Node.PROCESS_MODE_ALWAYS
	_root = Control.new()
	_root.set_anchors_preset(Control.PRESET_FULL_RECT)
	_root.theme = UiStyle.theme()
	add_child(_root)
	var dim := ColorRect.new()
	dim.color = Color(0.02, 0.03, 0.05, 0.7)
	dim.set_anchors_preset(Control.PRESET_FULL_RECT)
	_root.add_child(dim)
	var center := CenterContainer.new()
	center.set_anchors_preset(Control.PRESET_FULL_RECT)
	_root.add_child(center)
	var panel := PanelContainer.new()
	panel.add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG_SOLID, 16, 32, UiStyle.LINE))
	panel.custom_minimum_size = Vector2(620, 0)
	center.add_child(panel)
	_body = VBoxContainer.new()
	_body.add_theme_constant_override("separation", 10)
	panel.add_child(_body)
	_root.visible = false
	Game.day_summary.connect(show_summary)
	Game.state_reset.connect(_on_reset)
	_on_reset()


func _on_reset() -> void:
	if not Game.state.changed.is_connected(_on_changed):
		Game.state.changed.connect(_on_changed)


func _on_changed(kind: String, _id: Variant) -> void:
	if kind == "clock" and Game.state.day_running and _root.visible:
		hide_summary()


func show_summary(s: Dictionary) -> void:
	_summary = s
	for c in _body.get_children():
		c.queue_free()
	_body.add_child(UiStyle.label("End of shift — %s, day %d" % [s.get("weekday", ""), int(s.get("day", 0)) + 1], 28, UiStyle.TEXT))
	var stars := int(s.get("rating", 3))
	var st := UiStyle.label("★".repeat(stars) + "☆".repeat(5 - stars), 44, UiStyle.ACCENT)
	_body.add_child(st)
	_body.add_child(UiStyle.label(["Rough day.", "Could be better.", "Solid shift.", "Great work!", "Absolute machine!"][clampi(stars - 1, 0, 4)], 18, UiStyle.MUTED))
	var grid := GridContainer.new()
	grid.columns = 2
	grid.add_theme_constant_override("h_separation", 40)
	_body.add_child(grid)
	var rows := [
		["Units tested", str(int(s.get("tested", 0)))], ["Faults repaired", str(int(s.get("repaired", 0)))],
		["Units imaged", str(int(s.get("imaged", 0)))], ["Units graded", str(int(s.get("graded", 0)))],
		["Orders received", str(int(s.get("sold", 0)))], ["Parcels shipped", str(int(s.get("shipped", 0)))],
		["Late orders", str(int(s.get("late", 0)))], ["Cancelled orders", str(int(s.get("missed", 0)))],
		["Money in", GameState.format_money(int(s.get("income", 0)))], ["Money out", GameState.format_money(-int(s.get("spend", 0)))],
	]
	for r in rows:
		grid.add_child(UiStyle.label(r[0], 17, UiStyle.MUTED))
		grid.add_child(UiStyle.label(r[1], 17, UiStyle.TEXT))
	var profit := int(s.get("profit", 0))
	_body.add_child(UiStyle.label("Day result: %s   ·   Balance: %s" % [GameState.format_money(profit), GameState.format_money(int(s.get("cash", 0)))], 20, UiStyle.money_colour(profit)))
	if int(s.get("electric", 0)) > 0:
		_body.add_child(UiStyle.label("Electricity: %d kWh — %s" % [int(round(float(s.get("kwh", 0.0)))), GameState.format_money(-int(s.electric))], 17, UiStyle.WARN))
	if s.has("rent_paid"):
		_body.add_child(UiStyle.label("Weekly rent & bills paid: %s" % GameState.format_money(-int(s.rent_paid)), 17, UiStyle.WARN))
	if s.has("overdraft_fee"):
		_body.add_child(UiStyle.label("Overdraft fee charged — you're in the red!", 17, UiStyle.BAD))
	var rep := float(s.get("rep_delta", 0.0))
	_body.add_child(UiStyle.label("Reputation %s%.1f" % ["+" if rep >= 0 else "", rep], 17, UiStyle.GOOD if rep >= 0 else UiStyle.BAD))
	_body.add_child(UiStyle.label("Tests left running finish overnight. Game saved.", 14, UiStyle.MUTED))
	_button = Button.new()
	_button.custom_minimum_size.y = 50
	if Game.is_host():
		_button.text = "Start next day"
		_button.pressed.connect(func(): Game.request("next_day"))
	else:
		_button.text = "Waiting for the host…"
		_button.disabled = true
	_body.add_child(_button)
	_root.visible = true
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	Sfx.play_ui("day_end", -4.0)


func hide_summary() -> void:
	_root.visible = false
	var p: Node = LevelRoot.current.local_player() if LevelRoot.current else null
	if p and not p.ui_mode:
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
