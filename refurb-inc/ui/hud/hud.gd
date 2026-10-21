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
var objective_label: Label
var goal_label: Label
var shift_label: Label          # overtime / tiredness
var overtime_prompt: OvertimePrompt
var challenge_hud: ChallengeHud
var _obj_t := 0.0

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
	shift_label = UiStyle.label("", 15, UiStyle.INFO)
	shift_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	shift_label.custom_minimum_size.x = 250
	shift_label.visible = false
	sv.add_child(shift_label)
	next_label = UiStyle.label("", 14, UiStyle.MUTED)
	next_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	sv.add_child(next_label)
	objective_label = UiStyle.label("", 15, UiStyle.INFO)
	objective_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	objective_label.custom_minimum_size.x = 250
	sv.add_child(objective_label)
	goal_label = UiStyle.label("", 13, UiStyle.MUTED)
	goal_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	goal_label.custom_minimum_size.x = 250
	sv.add_child(goal_label)

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
	_build_achievement_layer()
	Game.achievement_unlocked.connect(show_achievement)
	# Their own canvas layers (they stay up while the HUD hides for the office PC).
	overtime_prompt = OvertimePrompt.new()
	add_child(overtime_prompt)
	challenge_hud = ChallengeHud.new()
	add_child(challenge_hud)
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
	if (_player == null or not is_instance_valid(_player)) and LevelRoot.current:
		_player = null
		var lp: Node = LevelRoot.current.local_player() if LevelRoot.current else null
		if lp is Player:
			bind_player(lp)
	clock_label.text = Game.state.clock_text()
	if _hint_t > 0:
		_hint_t -= delta
		hint_label.modulate.a = clampf(_hint_t / 2.0, 0.0, 1.0)
	_obj_t -= delta
	if _obj_t <= 0.0:
		_obj_t = 0.5
		objective_label.text = "▸ " + next_objective()
		var nm := Campaign.next_milestone(Game.state)
		if not nm.is_empty():
			var pr := Campaign.progress(Game.state, nm)
			goal_label.text = "🏆 Next goal: %s (%d%%)" % [str(nm.name), int(clampf(float(pr[0]) / maxf(float(pr[1]), 0.001), 0.0, 1.0) * 100.0)]
		else:
			goal_label.text = "🏆 Every milestone reached"
		shift_label.text = shift_text(Game.state, _player.is_tired() if _player and is_instance_valid(_player) else false)
		shift_label.visible = shift_label.text != ""
	fps_label.visible = false     # the always-on PerfOverlay autoload shows FPS now
	visible = not (_player and is_instance_valid(_player) and _player.ui_mode and not _keep_visible_in_ui)


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
	day_label.text = "%s · Day %d" % [s.weekday_name().substr(0, 3), Campaign.day_number(s)]
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


# ================================================================= achievement unlocks

const ACH_GOLD := Color(0.98, 0.78, 0.26)
const ACH_HOLD_S := 5.2

var _ach_root: Control
var _ach_queue: Array = []
var _ach_busy := false


func _build_achievement_layer() -> void:
	# Its own canvas layer above the day summary, pause menu and office PC: the HUD hides in UI mode, unlocks don't.
	var layer := CanvasLayer.new()
	layer.layer = 40
	add_child(layer)
	_ach_root = Control.new()
	_ach_root.set_anchors_preset(Control.PRESET_FULL_RECT)
	_ach_root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_ach_root.theme = UiStyle.theme()
	layer.add_child(_ach_root)


func show_achievement(def: Dictionary) -> void:
	_ach_queue.append(def)
	if not _ach_busy:
		_next_achievement()


func _next_achievement() -> void:
	if _ach_queue.is_empty() or not is_inside_tree():
		_ach_busy = false
		return
	_ach_busy = true
	var def: Dictionary = _ach_queue.pop_front()
	var extra := 0
	if _ach_queue.size() >= 3:
		# A flood (an old save catching up): show this one and roll the rest into its footer.
		extra = _ach_queue.size()
		_ach_queue.clear()
	var parts := _achievement_card(def, extra)
	var card: PanelContainer = parts[0]
	var medal: Control = parts[1]
	var sparks: CPUParticles2D = parts[2]
	var shine: _AchShine = parts[3]
	_ach_root.add_child(card)
	var sz := card.get_combined_minimum_size()
	var vw := get_viewport().get_visible_rect().size.x
	card.size = sz
	card.position = Vector2((vw - sz.x) * 0.5, -sz.y - 40.0)
	card.modulate.a = 0.0
	medal.pivot_offset = medal.custom_minimum_size * 0.5
	medal.scale = Vector2(0.2, 0.2)
	Sfx.play_ui("achievement", -3.0)
	var tw := create_tween().set_parallel(true)
	tw.tween_property(card, "position:y", 30.0, 0.55).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
	tw.tween_property(card, "modulate:a", 1.0, 0.25)
	tw.tween_property(medal, "scale", Vector2.ONE, 0.9).set_delay(0.18).set_trans(Tween.TRANS_ELASTIC).set_ease(Tween.EASE_OUT)
	tw.tween_callback(func(): sparks.emitting = true).set_delay(0.3)
	tw.tween_method(shine.set_t, 0.0, 1.0, 0.85).set_delay(0.5).set_trans(Tween.TRANS_SINE)
	tw.tween_method(shine.set_t, 0.0, 1.0, 0.85).set_delay(2.6).set_trans(Tween.TRANS_SINE)
	tw.chain().tween_interval(ACH_HOLD_S - 2.6)
	tw.chain().tween_property(card, "position:y", -sz.y - 40.0, 0.45).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_IN)
	tw.parallel().tween_property(card, "modulate:a", 0.0, 0.4)
	tw.chain().tween_callback(card.queue_free)
	tw.chain().tween_interval(0.25)
	tw.chain().tween_callback(_next_achievement)


func _achievement_card(def: Dictionary, extra: int) -> Array:
	## Gold-trimmed card: medallion with the glyph, "ACHIEVEMENT UNLOCKED", name, description, progress footer.
	var card := PanelContainer.new()
	card.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.1, 0.085, 0.05, 0.96)
	sb.border_color = ACH_GOLD
	sb.set_border_width_all(2)
	sb.set_corner_radius_all(16)
	sb.shadow_color = Color(ACH_GOLD.r, ACH_GOLD.g, ACH_GOLD.b, 0.32)
	sb.shadow_size = 18
	sb.content_margin_left = 18
	sb.content_margin_right = 26
	sb.content_margin_top = 14
	sb.content_margin_bottom = 14
	sb.anti_aliasing = true
	card.add_theme_stylebox_override("panel", sb)
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 18)
	h.mouse_filter = Control.MOUSE_FILTER_IGNORE
	card.add_child(h)
	var medal := PanelContainer.new()
	var msb := StyleBoxFlat.new()
	msb.bg_color = Color(0.32, 0.22, 0.05)
	msb.border_color = ACH_GOLD
	msb.set_border_width_all(3)
	msb.set_corner_radius_all(40)
	msb.anti_aliasing = true
	medal.add_theme_stylebox_override("panel", msb)
	medal.custom_minimum_size = Vector2(80, 80)
	medal.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var glyph := UiStyle.label(str(def.get("icon", "★")), 40, Color.WHITE)
	glyph.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	glyph.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	medal.add_child(glyph)
	var sparks := CPUParticles2D.new()
	sparks.position = Vector2(40, 40)
	sparks.emitting = false
	sparks.one_shot = true
	sparks.amount = 34
	sparks.lifetime = 0.9
	sparks.explosiveness = 0.95
	sparks.direction = Vector2(0, -1)
	sparks.spread = 180.0
	sparks.gravity = Vector2(0, 40)
	sparks.initial_velocity_min = 55.0
	sparks.initial_velocity_max = 125.0
	sparks.damping_min = 40.0
	sparks.damping_max = 80.0
	sparks.scale_amount_min = 2.0
	sparks.scale_amount_max = 4.0
	var ramp := Gradient.new()
	ramp.set_color(0, Color(1.0, 0.95, 0.7, 1.0))
	ramp.set_color(1, Color(ACH_GOLD.r, ACH_GOLD.g, ACH_GOLD.b, 0.0))
	sparks.color_ramp = ramp
	medal.add_child(sparks)
	h.add_child(medal)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 1)
	v.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_child(v)
	var top := UiStyle.label("ACHIEVEMENT UNLOCKED", 13, ACH_GOLD)
	top.add_theme_constant_override("outline_size", 0)
	v.add_child(top)
	v.add_child(UiStyle.label(str(def.get("name", "")), 27, Color.WHITE))
	var desc := UiStyle.label(str(def.get("desc", "")), 15, UiStyle.MUTED)
	desc.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	desc.custom_minimum_size.x = 400
	v.add_child(desc)
	var foot := "%s  ·  %d of %d unlocked" % [str(def.get("group", "")), Game.achievements.unlocked_count(), Achievements.defs().size()]
	if extra > 0:
		foot += "  ·  +%d more — see the Achievements app" % extra
	v.add_child(UiStyle.label(foot, 13, ACH_GOLD.darkened(0.2)))
	var shine := _AchShine.new()
	shine.mouse_filter = Control.MOUSE_FILTER_IGNORE
	shine.clip_contents = true
	card.add_child(shine)
	return [card, medal, sparks, shine]


class _AchShine extends Control:
	## A light sweep across the card.
	var t := -1.0

	func set_t(v: float) -> void:
		t = v
		queue_redraw()

	func _draw() -> void:
		if t <= 0.0 or t >= 1.0:
			return
		var x := lerpf(-90.0, size.x + 90.0, t)
		draw_colored_polygon(PackedVector2Array([Vector2(x, 0), Vector2(x + 44, 0), Vector2(x + 4, size.y), Vector2(x - 40, size.y)]),
			Color(1.0, 0.95, 0.75, 0.13))


static func shift_text(s: GameState, tired: bool) -> String:
	## Overtime line under the clock: how late you're going, and whether you're flagging.
	if s.overtime_asking():
		return "⏸ Closing time — %s" % ("keep going?" if Game.is_host() else "the host is deciding")
	if not s.overtime_active():
		return ""
	var end := int(fmod(s.shift_end_minute(), GameState.MIN_PER_DAY))
	var t := "🌙 OVERTIME until %02d:%02d%s" % [end / 60, end % 60, " · staff on time-and-a-half" if not s.staff.is_empty() else ""]
	if tired:
		t += "\n😴 You're flagging — have a brew"
	return t


static func next_objective() -> String:
	## Contextual "what should I do next" hint, derived purely from game state.
	var s := Game.state
	var counts := {}
	var on_pallet := 0
	for it: Item in s.items.values():
		if it.kind != Item.KIND_UNIT or it.unit == null:
			continue
		counts[it.unit.stage()] = int(counts.get(it.unit.stage(), 0)) + 1
		if it.loc.get("t") == "slot" and String(it.loc.slot).begins_with("i"):
			on_pallet += 1
	var packed := s.orders.values().filter(func(o): return o.status == "packed").size()
	var to_pack := s.orders.values().filter(func(o): return o.status == "to_pack").size()
	if to_pack > 0:
		return "Pack %d sold order%s at the packing table." % [to_pack, "" if to_pack == 1 else "s"]
	if packed > 0:
		return "Put packed orders on the OUTGOING shelf by the door before the courier comes."
	if on_pallet > 0:
		return "Carry the %d laptop%s in from the pallet on the drive." % [on_pallet, "" if on_pallet == 1 else "s"]
	if int(counts.get(UnitData.STAGE_UNTESTED, 0)) > 0:
		return "Test units: laptop on the test bench + DiagnostiKit USB."
	if int(counts.get(UnitData.STAGE_FAULTY, 0)) > 0:
		return "Fix faulty units at the repair bench (hold E). Order parts in the Shop."
	if int(counts.get(UnitData.STAGE_TO_IMAGE, 0)) > 0:
		return "Swap the bench USB to PaneOS to wipe & image passed units."
	if int(counts.get(UnitData.STAGE_TO_GRADE, 0)) > 0:
		return "Grade imaged units at the grading desk."
	if int(counts.get(UnitData.STAGE_GRADED, 0)) > 0:
		return "List graded stock on Flogit (office PC)."
	if s.schedule.any(func(e): return e.type == "lorry"):
		return "Delivery booked. Stock up on parts and boxes in the Shop meanwhile."
	if int(counts.get(UnitData.STAGE_LISTED, 0)) > 0:
		return "Listings live — buyers trickle in each hour. Buy your next lot?"
	return "Buy a lot of laptops on the office PC (LotFinder)."
