class_name ChallengesPanel
extends PanelContainer
## Title screen → Challenges: today's daily run (the same start for everyone, one go a day) and the scenario list,
## each with its goal, day limit, medals and this PC's best. Emits play(arg): a challenge id, "daily" or "resume".

signal closed
signal play(arg: String)

const GOLD := Color(0.98, 0.78, 0.26)
const DIFF_COLOURS := {"Easy": Color(0.25, 0.65, 0.4), "Medium": Color(0.85, 0.55, 0.1), "Hard": Color(0.8, 0.3, 0.25), "Daily": Color(0.3, 0.5, 0.9)}

var _list: VBoxContainer


func _ready() -> void:
	add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG_SOLID, 14, 24))
	custom_minimum_size = Vector2(980, 0)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 12)
	add_child(v)
	v.add_child(UiStyle.label("Challenges", 30, UiStyle.TEXT))
	v.add_child(UiStyle.label("Set-piece runs with their own start, a goal, a day limit and medals. Your times go on this PC's boards (and Steam's, when you're playing through Steam).", 15, UiStyle.MUTED))
	var sc := ScrollContainer.new()
	sc.custom_minimum_size = Vector2(0, 480)
	sc.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	v.add_child(sc)
	_list = VBoxContainer.new()
	_list.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_list.add_theme_constant_override("separation", 10)
	sc.add_child(_list)
	var back := Button.new()
	back.text = "Back"
	back.custom_minimum_size.y = 44
	back.pressed.connect(func(): closed.emit())
	v.add_child(back)
	visibility_changed.connect(func():
		if visible:
			refresh())
	refresh()


func refresh() -> void:
	for c in _list.get_children():
		_list.remove_child(c)
		c.queue_free()
	if SaveManager.has_save(SaveManager.CHALLENGE):
		var d := SaveManager.read_save(SaveManager.CHALLENGE)
		var ch: Dictionary = d.get("totals", {}).get("challenge", {})
		if not ch.is_empty() and str(ch.get("status", "")) == "running" and not bool(ch.get("daily", false)):
			var row := HBoxContainer.new()
			row.add_theme_constant_override("separation", 12)
			var lab := UiStyle.label("Carry on: %s  ·  %s" % [str(ch.get("name", "")), str(d.get("label", ""))], 17, UiStyle.TEXT)
			lab.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			lab.clip_text = true
			row.add_child(lab)
			row.add_child(_play_button("Resume", "resume", false))
			var c := _card(Color(0.12, 0.16, 0.12))
			c.add_child(row)
			_list.add_child(c)
	_daily_card()
	for def: Dictionary in Challenges.defs():
		_challenge_card(def)


func _card(bg: Color = UiStyle.PANEL) -> PanelContainer:
	var p := PanelContainer.new()
	p.add_theme_stylebox_override("panel", UiStyle.box(bg, 12, 18))
	return p


func _play_button(text: String, arg: String, disabled: bool) -> Button:
	var b := Button.new()
	b.text = "  %s  " % text
	b.custom_minimum_size = Vector2(170, 46)
	b.add_theme_font_size_override("font_size", 18)
	b.disabled = disabled
	if not disabled:
		b.add_theme_stylebox_override("normal", UiStyle.box(UiStyle.ACCENT.darkened(0.25), 8, 10))
		b.add_theme_stylebox_override("hover", UiStyle.box(UiStyle.ACCENT.darkened(0.1), 8, 10))
	b.pressed.connect(func():
		Sfx.play_ui("ui_click", -6.0)
		play.emit(arg))
	return b


func _daily_card() -> void:
	var key := Challenges.today_key()
	var def := Challenges.daily_def(key)
	var attempt := Challenges.daily_attempt(key)
	var c := _card(Color(0.1, 0.13, 0.22))
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 6)
	c.add_child(v)
	var top := HBoxContainer.new()
	top.add_theme_constant_override("separation", 12)
	v.add_child(top)
	top.add_child(UiStyle.label("📅", 34, Color.WHITE))
	var tv := VBoxContainer.new()
	tv.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	tv.add_child(UiStyle.label("DAILY RUN · %s" % Challenges.date_label(key), 14, Color(0.55, 0.7, 1.0)))
	tv.add_child(UiStyle.label("%s  %s" % [str(def.get("icon", "")), str(def.get("name", ""))], 24, Color.WHITE))
	top.add_child(tv)
	var played := not attempt.is_empty()
	top.add_child(_play_button("Played today" if played else "Play today's run", "daily", played))
	var desc := UiStyle.label(str(def.get("desc", "")), 15, UiStyle.MUTED)
	desc.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	desc.custom_minimum_size.x = 880
	v.add_child(desc)
	v.add_child(UiStyle.label("Goal: %s  ·  Medals: %s" % [Challenges.goal_text(def), Challenges.medal_text(def)], 15, UiStyle.TEXT))
	if played:
		var res: Dictionary = attempt.get("result", {})
		var status := str(attempt.get("status", ""))
		var t := "You've had today's go — a new run tomorrow (UTC)."
		if status in ["done", "won"]:
			t = "Today: %s %s%s — a new run tomorrow." % [Challenges.MEDAL_ICONS.get(str(res.get("medal", "")), ""), Challenges.value_text(def, res),
				("  (#%d on this PC)" % int(attempt.get("place", 0))) if int(attempt.get("place", 0)) > 0 else ""]
		elif status == "":
			t = "Started today and not finished — one attempt per day. A new run tomorrow (UTC)."
		v.add_child(UiStyle.label(t, 15, GOLD))
	var entries := Challenges.board_entries(Challenges.board(def))
	if not entries.is_empty():
		var parts: PackedStringArray = []
		for i in mini(entries.size(), 3):
			parts.append("#%d %s %s" % [i + 1, str(entries[i].get("company", "")), Challenges.board_value_text(def, entries[i])])
		v.add_child(UiStyle.label("Board %s:  %s" % [Challenges.board(def), "   ".join(parts)], 14, UiStyle.MUTED))
	_list.add_child(c)


func _challenge_card(def: Dictionary) -> void:
	var c := _card()
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 6)
	c.add_child(v)
	var top := HBoxContainer.new()
	top.add_theme_constant_override("separation", 12)
	v.add_child(top)
	top.add_child(UiStyle.label(str(def.get("icon", "🏁")), 30, Color.WHITE))
	var name_l := UiStyle.label(str(def.get("name", "")), 24, Color.WHITE)
	top.add_child(name_l)
	var diff := str(def.get("difficulty", ""))
	if diff != "":
		var pill := PanelContainer.new()
		pill.add_theme_stylebox_override("panel", UiStyle.box(DIFF_COLOURS.get(diff, UiStyle.PANEL), 10, 8))
		pill.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		pill.add_child(UiStyle.label(diff, 14, Color.WHITE))
		top.add_child(pill)
	var spacer := Control.new()
	spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(spacer)
	var medal := Challenges.best_medal(str(def.id))
	if medal != "":
		top.add_child(UiStyle.label("Best: %s %s" % [Challenges.MEDAL_ICONS.get(medal, ""), medal.capitalize()], 17, Challenges.MEDAL_COLOURS.get(medal, GOLD)))
	top.add_child(_play_button("Play", str(def.id), false))
	var desc := UiStyle.label(str(def.get("desc", "")), 15, UiStyle.MUTED)
	desc.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	desc.custom_minimum_size.x = 880
	v.add_child(desc)
	v.add_child(UiStyle.label("Goal: %s" % Challenges.goal_text(def), 16, UiStyle.TEXT))
	var extra := ""
	if bool(def.get("rules", {}).get("no_staff", false)):
		extra = "  ·  No staff allowed"
	v.add_child(UiStyle.label("Medals: %s%s" % [Challenges.medal_text(def), extra], 15, GOLD.darkened(0.1)))
	var entries := Challenges.board_entries(Challenges.board(def))
	if not entries.is_empty():
		v.add_child(UiStyle.label("Your best: %s (%s)" % [Challenges.board_value_text(def, entries[0]), str(entries[0].get("company", ""))], 14, UiStyle.MUTED))
	_list.add_child(c)
