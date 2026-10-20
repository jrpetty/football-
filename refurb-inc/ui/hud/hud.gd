class_name Hud
extends CanvasLayer
## Minimal in-game HUD: crosshair + hover card + hold ring, clock/cash/reputation, toasts, open orders.

var crosshair: Control
var hover_panel: PanelContainer
var hover_title: Label
var hover_sub: Label
var hover_lines: VBoxContainer
var hover_prompt: Label
var hold_label: Label
var clock_label: Label
var day_label: Label
var cash_label: Label
var rep_label: Label
var next_label: Label
var toasts: VBoxContainer
var orders_box: VBoxContainer
var hint_label: Label
var fps_label: Label

var _hold_p := 0.0
var _player: Player
var _hint_t := 14.0
var _last_cash := 0


func _ready() -> void:
	layer = 10
	var root := Control.new()
	root.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.theme = UiStyle.theme()
	add_child(root)

	crosshair = Control.new()
	crosshair.set_anchors_preset(Control.PRESET_CENTER)
	crosshair.mouse_filter = Control.MOUSE_FILTER_IGNORE
	crosshair.draw.connect(_draw_crosshair)
	root.add_child(crosshair)

	# Hover card (just right of and below the crosshair)
	hover_panel = PanelContainer.new()
	hover_panel.add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG, 10, 14))
	hover_panel.set_anchors_preset(Control.PRESET_CENTER)
	hover_panel.position = Vector2(36, 18)
	hover_panel.custom_minimum_size = Vector2(320, 0)
	hover_panel.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(hover_panel)
	var hv := VBoxContainer.new()
	hover_panel.add_child(hv)
	hover_title = UiStyle.label("", 19, UiStyle.TEXT)
	hv.add_child(hover_title)
	hover_sub = UiStyle.label("", 13, UiStyle.MUTED)
	hv.add_child(hover_sub)
	hover_lines = VBoxContainer.new()
	hover_lines.add_theme_constant_override("separation", 2)
	hv.add_child(hover_lines)
	hover_prompt = UiStyle.label("", 16, UiStyle.ACCENT)
	hv.add_child(hover_prompt)
	hold_label = UiStyle.label("", 15, UiStyle.INFO)
	hold_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	hv.add_child(hold_label)
	hover_panel.visible = false

	# Top-right status
	var status := PanelContainer.new()
	status.add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG, 12, 16))
	status.set_anchors_preset(Control.PRESET_TOP_RIGHT)
	status.position = Vector2(-300, 20)
	status.custom_minimum_size = Vector2(280, 0)
	status.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(status)
	var sv := VBoxContainer.new()
	status.add_child(sv)
	var row := HBoxContainer.new()
	sv.add_child(row)
	clock_label = UiStyle.label("09:00", 34, UiStyle.TEXT)
	row.add_child(clock_label)
	day_label = UiStyle.label("Mon · Day 1", 15, UiStyle.MUTED)
	day_label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	day_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	day_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	row.add_child(day_label)
	cash_label = UiStyle.label("£0", 24, UiStyle.GOOD)
	sv.add_child(cash_label)
	rep_label = UiStyle.label("", 15, UiStyle.ACCENT)
	sv.add_child(rep_label)
	next_label = UiStyle.label("", 14, UiStyle.MUTED)
	next_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	sv.add_child(next_label)

	# Orders strip under status
	orders_box = VBoxContainer.new()
	orders_box.set_anchors_preset(Control.PRESET_TOP_RIGHT)
	orders_box.position = Vector2(-300, 210)
	orders_box.custom_minimum_size = Vector2(280, 0)
	orders_box.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(orders_box)

	# Toasts top-left
	toasts = VBoxContainer.new()
	toasts.position = Vector2(24, 24)
	toasts.custom_minimum_size = Vector2(460, 0)
	toasts.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(toasts)

	# Controls hint bottom-left
	hint_label = UiStyle.label("WASD move · Shift sprint · Ctrl crouch · E interact (hold for actions) · Q put down · Esc menu", 14, UiStyle.MUTED)
	hint_label.set_anchors_preset(Control.PRESET_BOTTOM_LEFT)
	hint_label.position = Vector2(24, -40)
	root.add_child(hint_label)

	fps_label = UiStyle.label("", 13, UiStyle.MUTED)
	fps_label.set_anchors_preset(Control.PRESET_BOTTOM_RIGHT)
	fps_label.position = Vector2(-90, -32)
	root.add_child(fps_label)

	Game.toast.connect(show_toast)
	Game.state_reset.connect(_on_reset)
	_on_reset()


func _on_reset() -> void:
	if not Game.state.changed.is_connected(_on_state_changed):
		Game.state.changed.connect(_on_state_changed)
	_last_cash = Game.state.cash
	_refresh_status()
	_refresh_orders()


func _on_state_changed(kind: String, _id: Variant) -> void:
	match kind:
		"company", "clock", "schedule":
			_refresh_status()
		"order":
			_refresh_orders()


func bind_player(p: Player) -> void:
	_player = p
	p.hover_changed.connect(_on_hover)
	p.hold_changed.connect(_on_hold)


func _process(delta: float) -> void:
	if _player == null and LevelRoot.current:
		var lp: Node = LevelRoot.current.local_player()
		if lp is Player:
			bind_player(lp)
	clock_label.text = Game.state.clock_text()
	if _hint_t > 0:
		_hint_t -= delta
		hint_label.modulate.a = clampf(_hint_t / 2.0, 0.0, 1.0)
	fps_label.visible = Settings.get_value("show_fps")
	if fps_label.visible:
		fps_label.text = "%d fps" % Engine.get_frames_per_second()
	visible = not (_player and _player.ui_mode and not _keep_visible_in_ui)


var _keep_visible_in_ui := false


# ================================================================= hover / hold

func _on_hover(info: Dictionary) -> void:
	if info.is_empty():
		hover_panel.visible = false
		crosshair.queue_redraw()
		return
	hover_panel.visible = true
	hover_title.text = str(info.get("title", ""))
	hover_sub.text = str(info.get("subtitle", ""))
	hover_sub.visible = hover_sub.text != ""
	for c in hover_lines.get_children():
		c.queue_free()
	for line in info.get("lines", []):
		var col := UiStyle.TEXT
		var s := str(line)
		if s.begins_with("⚠") or s.begins_with("✖"):
			col = UiStyle.BAD
		elif s.begins_with("✔"):
			col = UiStyle.GOOD
		var l := UiStyle.label(s, 15, col)
		l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		l.custom_minimum_size.x = 300
		hover_lines.add_child(l)
	var prompt := str(info.get("prompt", ""))
	var hold: Dictionary = info.get("hold", {})
	hold_label.text = ""
	if not hold.is_empty():
		if hold.has("blocked"):
			hold_label.text = "✖ " + str(hold.blocked)
			hold_label.add_theme_color_override("font_color", UiStyle.BAD)
		else:
			hold_label.text = "[Hold E] %s%s" % [hold.get("label", ""), ("\n" + str(hold.sub)) if hold.has("sub") else ""]
			hold_label.add_theme_color_override("font_color", UiStyle.INFO)
	hover_prompt.text = prompt
	hover_prompt.visible = prompt != ""
	hold_label.visible = hold_label.text != ""
	crosshair.queue_redraw()


func _on_hold(p: float, _label: String) -> void:
	_hold_p = p
	crosshair.queue_redraw()


func _draw_crosshair() -> void:
	var active := hover_panel.visible
	crosshair.draw_circle(Vector2.ZERO, 3.0 if active else 2.0, Color(1, 1, 1, 0.95 if active else 0.6))
	if active:
		crosshair.draw_arc(Vector2.ZERO, 11, 0, TAU, 32, Color(1, 1, 1, 0.18), 2.0, true)
	if _hold_p > 0.0:
		crosshair.draw_arc(Vector2.ZERO, 16, -PI / 2, -PI / 2 + TAU * _hold_p, 48, UiStyle.ACCENT, 4.0, true)


# ================================================================= status

func _refresh_status() -> void:
	var s := Game.state
	day_label.text = "%s · Day %d" % [s.weekday_name().substr(0, 3), s.day_index() + 1]
	cash_label.text = GameState.format_money(s.cash)
	cash_label.add_theme_color_override("font_color", UiStyle.money_colour(s.cash))
	if s.cash != _last_cash:
		var tw := create_tween()
		cash_label.scale = Vector2(1.08, 1.08)
		tw.tween_property(cash_label, "scale", Vector2.ONE, 0.25)
		if s.cash > _last_cash:
			Sfx.play_ui("cash", -6.0)
		_last_cash = s.cash
	var stars := clampi(int(round(s.reputation / 20.0)), 0, 5)
	rep_label.text = "Reputation " + "★".repeat(stars) + "☆".repeat(5 - stars) + "  (%d)" % int(s.reputation)
	var nxt: Array = []
	var evs := s.schedule.duplicate()
	evs.sort_custom(func(a, b): return float(a.at) < float(b.at))
	for ev in evs.slice(0, 2):
		var what: String = {"lorry": "Lorry delivery", "parcel": "Parcel delivery"}.get(ev.type, ev.type)
		nxt.append("%s — %s" % [what, GameState.format_minute(float(ev.at))])
	var mod := s.minute_of_day()
	for m in Content.econ("courier_collections", [720, 990]):
		if float(m) > mod:
			nxt.append("Courier collection — %02d:%02d" % [int(m) / 60, int(m) % 60])
			break
	next_label.text = "\n".join(nxt)


func _refresh_orders() -> void:
	for c in orders_box.get_children():
		c.queue_free()
	var open := Game.state.orders.values().filter(func(o): return o.status in ["to_pack", "packed"])
	open.sort_custom(func(a, b): return float(a.due) < float(b.due))
	if open.is_empty():
		return
	var title := UiStyle.label("ORDERS TO DISPATCH (%d)" % open.size(), 13, UiStyle.MUTED)
	orders_box.add_child(title)
	for o in open.slice(0, 5):
		var p := PanelContainer.new()
		var late: bool = Game.state.minute > float(o.due)
		var soon: bool = float(o.due) - Game.state.minute < 120
		p.add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG, 8, 10, UiStyle.BAD if late else (UiStyle.WARN if soon else Color(0, 0, 0, 0))))
		var v := VBoxContainer.new()
		v.add_theme_constant_override("separation", 0)
		p.add_child(v)
		v.add_child(UiStyle.label("#%d %s (%s)" % [int(o.id), o.model, UnitData.grade_label(o.grade)], 14, UiStyle.TEXT))
		var st := "PACKED — to courier shelf" if o.status == "packed" else "To pack"
		v.add_child(UiStyle.label("%s · due %s" % [st, GameState.format_minute(float(o.due))], 13, UiStyle.BAD if late else UiStyle.MUTED))
		orders_box.add_child(p)


# ================================================================= toasts

func show_toast(text: String, level: String = "info") -> void:
	var p := PanelContainer.new()
	p.add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG, 8, 12))
	var h := HBoxContainer.new()
	p.add_child(h)
	var bar := ColorRect.new()
	bar.color = UiStyle.level_colour(level)
	bar.custom_minimum_size = Vector2(4, 0)
	h.add_child(bar)
	var l := UiStyle.label(text, 15)
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	l.custom_minimum_size = Vector2(420, 0)
	h.add_child(l)
	p.modulate.a = 0.0
	toasts.add_child(p)
	toasts.move_child(p, 0)
	while toasts.get_child_count() > 5:
		var last := toasts.get_child(toasts.get_child_count() - 1)
		toasts.remove_child(last)
		last.queue_free()
	var tw := create_tween()
	tw.tween_property(p, "modulate:a", 1.0, 0.2)
	tw.tween_interval(5.5 if level in ["bad", "warn"] else 4.0)
	tw.tween_property(p, "modulate:a", 0.0, 0.6)
	tw.tween_callback(p.queue_free)
	match level:
		"good": Sfx.play_ui("notify_good", -8.0)
		"bad": Sfx.play_ui("notify_bad", -6.0)
		"warn": Sfx.play_ui("notify_warn", -10.0)
