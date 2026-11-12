extends InsightsUI
## Staff: your team (role, what they're doing, mood, skills, weekly wage) and this week's candidates to hire.
## Wages go out with the rent every Friday. The right person in the right role is quick; anyone can do anything.
## Morale matters: keep them in tea, say thanks, give rises when they've earned them, send them on courses.

var _tab := 0
var _course_pick := {}         # staff id -> skill chosen in the course picker


func build() -> void:
	add_child(para("Hire people to share the work. Each candidate has a skill card (1–10); pay follows their best skills and is due with the rent on Friday. Staff use the same benches, parts and rules you do. Happy staff work faster and steadier: tea breaks, a thank-you, a fair wage and the odd course keep them that way.", 17, SUB, 1000))
	add_child(tabs(["Your team", "Candidates"], func(i):
		_tab = i
		refresh()))
	var sl := scroll_list()
	add_child(sl[0])
	_body = sl[1]


func refresh() -> void:
	clear(_body)
	if _tab == 0:
		_team()
	else:
		_candidates()


func _team() -> void:
	var s := Game.state
	var wages := 0
	var morale := 0.0
	var here := 0
	var away := 0
	for d in s.staff.values():
		wages += int(d.get("wage", 0))
		if StaffMorale.is_away(d):
			away += 1
		else:
			morale += StaffMorale.value(d)
			here += 1
	var avg := morale / here if here > 0 else 0.0
	_kpis([
		["Staff", str(s.staff.size()), ("%d away on courses" % away) if away > 0 else "on the books", BLUE],
		["Weekly wages", GameState.format_money(wages), "paid Friday with the rent", AMBER],
		["Working now", str(s.staff.values().filter(func(d): return str(d.get("status", "")).begins_with("Working")).size()), "at a station this minute", GREEN],
		["Team morale", ("%s (%d)" % [StaffMorale.mood(avg), int(round(avg))]) if here > 0 else "—", "speed ×%.2f on average" % lerpf(0.8, 1.2, avg / 100.0) if here > 0 else "", StaffMorale.mood_colour(avg) if here > 0 else SUB],
	], 4)
	if s.staff.is_empty():
		_empty("No staff yet. Look in Candidates — a tester or a packer frees you up for the jobs that need you.")
		return
	var ids: Array = s.staff.keys()
	ids.sort()
	for id in ids:
		_staff_card(s.staff[id], true)


func _candidates() -> void:
	var s := Game.state
	_note(_body, "New faces every Monday. Office roles need a bit of reputation first.")
	if bool(s.challenge().get("rules", {}).get("no_staff", false)) and str(s.challenge().get("status", "")) == "running":
		_note(_body, "This challenge doesn't allow staff — it's all on you.")
	if s.candidates.is_empty():
		_empty("No candidates this week — check back on Monday.")
		return
	for c in s.candidates:
		_staff_card(c, false)


func _staff_card(d: Dictionary, hired: bool) -> void:
	var c := card()
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 6)
	c.add_child(v)
	var top := HBoxContainer.new()
	top.add_theme_constant_override("separation", 10)
	v.add_child(top)
	var look: Dictionary = d.get("look", {})
	top.add_child(pill("  ", Color(str(look.get("shirt", "#3359a0"))), Color.WHITE, 18))
	top.add_child(l(str(d.get("name", "")), 22))
	var role := str(d.get("role", ""))
	top.add_child(pill(" %s " % StaffView._role_name(role), BLUE, Color.WHITE, 14))
	if hired:
		var m := StaffMorale.value(d)
		top.add_child(pill(" %s %d " % [StaffMorale.mood(m), int(round(m))], StaffMorale.mood_colour(m), Color.WHITE, 14))
	var spacer := Control.new()
	spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(spacer)
	top.add_child(l("%s / week" % GameState.format_money(int(d.get("wage", 0))), 20, AMBER))
	var away: Dictionary = d.get("away", {})
	if hired:
		if not away.is_empty():
			v.add_child(l("On a course: %s — back %s morning, %s level %d" % [str(away.get("course", "")), GameState.WEEKDAYS[int(away.get("back", 0)) % 7],
				str(away.get("skill", "")).capitalize(), int(away.get("level", 0))], 15, BLUE))
		else:
			v.add_child(l(str(d.get("status", "Starting their shift")), 15, GREEN if str(d.get("status", "")).begins_with("Working") else SUB))
		_mood_row(v, d)
	var main := Staff.main_skill(role)
	var lvl_main := int(d.get("stats", {}).get(main, 1))
	if hired:
		lvl_main = Game.state.skill_level(Sim.player_key(StaffView.peer_of(int(d.get("id", 0)))), main)
	var upd := Staff.units_per_day(role, lvl_main)
	var val := Staff.value_per_week(role, lvl_main)
	var est := ("≈ %.0f units a day at their main job · " % upd) if upd > 0.0 else ""
	v.add_child(l("%sworth roughly %s a week to the business" % [est, GameState.format_money(val)], 15, GREEN if val > int(d.get("wage", 0)) else AMBER))
	for r: Dictionary in Staff.data().roles:
		if r.id == role:
			v.add_child(para(str(r.desc), 14, SUB, 900))
	var grid := GridContainer.new()
	grid.columns = 4
	grid.add_theme_constant_override("h_separation", 18)
	v.add_child(grid)
	var key := Sim.player_key(StaffView.peer_of(int(d.get("id", 0))))
	for sk: Dictionary in Content.skill_defs():
		var lvl := int(d.get("stats", {}).get(sk.id, 1))
		if hired:
			lvl = Game.state.skill_level(key, sk.id)
		var h := HBoxContainer.new()
		h.add_child(l("%s %s" % [str(sk.get("icon", "•")), str(sk.name)], 14, SUB))
		var m := _Meter.new()
		m.frac = lvl / 10.0
		m.colour = Color(str(sk.get("colour", "#2e7dd1")))
		m.custom_minimum_size = Vector2(90, 10)
		m.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		h.add_child(m)
		h.add_child(l(str(lvl), 14))
		grid.add_child(h)
	var actions := HBoxContainer.new()
	actions.add_theme_constant_override("separation", 10)
	v.add_child(actions)
	if hired:
		var id := int(d.id)
		var ob := OptionButton.new()
		var roles: Array = Staff.data().roles
		for i in roles.size():
			ob.add_item(str(roles[i].name), i)
			if roles[i].id == role:
				ob.selected = i
		ob.item_selected.connect(func(i): Game.request("staff_role", {"staff": id, "role": roles[i].id}))
		actions.add_child(l("Role:", 15, SUB))
		actions.add_child(ob)
		var thanked := int(d.get("thanked", -1)) == Game.state.shift_day()
		actions.add_child(button("Thanked today ✔" if thanked else "Say thanks", func(): Game.request("thank_staff", {"staff": id}), GREEN, thanked or not away.is_empty()))
		var fair := StaffMorale.fair_wage(Game.state, d)
		var wage := int(d.get("wage", 0))
		var rise_to := fair if fair > wage else wage + maxi(int(round(wage * float(StaffMorale.c("pay_rise_pct", 0.05)) / 5.0) * 5), int(StaffMorale.c("pay_rise_min", 10)))
		actions.add_child(button("Give a pay rise (→ %s)" % GameState.format_money(rise_to), func(): Game.request("pay_rise", {"staff": id}), AMBER if fair > wage else BLUE))
		actions.add_child(button("Let go", func(): Game.request("fire", {"staff": id}), Color(0.75, 0.3, 0.25)))
		if away.is_empty():
			_course_row(v, d, key)
	else:
		var need := int(Staff.data().get("hire_rep_min", {}).get(role, 0))
		var ok := Game.state.reputation >= need
		actions.add_child(button("Hire" if ok else "Needs reputation %d" % need, func(): Game.request("hire", {"candidate": int(d.id)}), GREEN, not ok))
	_body.add_child(c)


func _mood_row(v: VBoxContainer, d: Dictionary) -> void:
	## Morale meter + why, and anything that needs the boss's attention.
	var m := StaffMorale.value(d)
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 10)
	h.add_child(l("Morale", 15, SUB))
	var bar := _Meter.new()
	bar.frac = m / 100.0
	bar.colour = StaffMorale.mood_colour(m)
	bar.custom_minimum_size = Vector2(220, 12)
	bar.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	h.add_child(bar)
	h.add_child(l(StaffMorale.mood_line(d), 15, StaffMorale.mood_colour(m)))
	h.add_child(l("· works at ×%.2f" % StaffMorale.speed_mult(d), 14, SUB))
	v.add_child(h)
	if int(d.get("wants_rise", 0)) > int(d.get("wage", 0)):
		v.add_child(l("⚠ Has asked for a pay rise to %s a week — their skills are worth it now." % GameState.format_money(int(d.wants_rise)), 15, AMBER))
	if d.has("notice"):
		v.add_child(l("⚠ Has handed in their notice — leaves at close on %s unless their morale gets back above %d." % [
			GameState.WEEKDAYS[int(d.notice.get("leave_day", 0)) % 7], int(StaffMorale.c("stay_threshold", 40))], 15, RED))
	elif int(d.get("low_days", 0)) > 0:
		v.add_child(l("⚠ Fed up %d day%s running. A brew, a thank-you or a rise would help." % [int(d.low_days), "" if int(d.low_days) == 1 else "s"], 15, AMBER))


func _course_row(v: VBoxContainer, d: Dictionary, key: String) -> void:
	## Send on a course: pick a skill, see the price and how long they'll be away.
	var id := int(d.id)
	var defs: Array = Content.skill_defs()
	var pick := str(_course_pick.get(id, Staff.main_skill(str(d.get("role", "")))))
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 10)
	h.add_child(l("Training:", 15, SUB))
	var ob := OptionButton.new()
	for i in defs.size():
		var lv := Game.state.skill_level(key, defs[i].id)
		ob.add_item("%s (level %d)" % [str(defs[i].name), lv], i)
		ob.set_item_disabled(i, lv >= Training.max_level())
		if defs[i].id == pick:
			ob.selected = i
	ob.item_selected.connect(func(i):
		_course_pick[id] = str(defs[i].id)
		refresh())
	h.add_child(ob)
	var level := Game.state.skill_level(key, pick)
	var course := Training.staff_course(pick)
	var cost := Training.staff_cost(level)
	var days := Training.staff_days(level)
	var maxed := level >= Training.max_level()
	h.add_child(l("Top level already" if maxed else "%s · %s · %s · away %d working day%s (still paid) → level %d" % [str(course.get("name", "")),
		str(course.get("provider", "")), GameState.format_money(cost), days, "" if days == 1 else "s", level + 1], 14, SUB))
	h.add_child(button("Send on course", func(): Game.request("send_course", {"staff": id, "skill": pick}), BLUE,
		maxed or Game.state.cash - cost < int(Content.econ("overdraft_warning", -500))))
	v.add_child(h)
