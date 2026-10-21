extends InsightsUI
## Staff: your team (role, what they're doing, skills, weekly wage) and this week's candidates to hire.
## Wages go out with the rent every Friday. The right person in the right role is quick; anyone can do anything.

var _tab := 0


func build() -> void:
	add_child(para("Hire people to share the work. Each candidate has a skill card (1–10); pay follows their best skills and is due with the rent on Friday. Staff use the same benches, parts and rules you do — give them the equipment and they'll keep the line moving.", 17, SUB, 1000))
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
	for d in s.staff.values():
		wages += int(d.get("wage", 0))
	_kpis([
		["Staff", str(s.staff.size()), "on the books", BLUE],
		["Weekly wages", GameState.format_money(wages), "paid Friday with the rent", AMBER],
		["Working now", str(s.staff.values().filter(func(d): return str(d.get("status", "")).begins_with("Working")).size()), "at a station this minute", GREEN],
	], 3)
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
	var spacer := Control.new()
	spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(spacer)
	top.add_child(l("%s / week" % GameState.format_money(int(d.get("wage", 0))), 20, AMBER))
	if hired:
		v.add_child(l(str(d.get("status", "Starting their shift")), 15, GREEN if str(d.get("status", "")).begins_with("Working") else SUB))
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
		var ob := OptionButton.new()
		var roles: Array = Staff.data().roles
		for i in roles.size():
			ob.add_item(str(roles[i].name), i)
			if roles[i].id == role:
				ob.selected = i
		ob.item_selected.connect(func(i): Game.request("staff_role", {"staff": int(d.id), "role": roles[i].id}))
		actions.add_child(l("Role:", 15, SUB))
		actions.add_child(ob)
		actions.add_child(button("Let go", func(): Game.request("fire", {"staff": int(d.id)}), Color(0.75, 0.3, 0.25)))
	else:
		var need := int(Staff.data().get("hire_rep_min", {}).get(role, 0))
		var ok := Game.state.reputation >= need
		actions.add_child(button("Hire" if ok else "Needs reputation %d" % need, func(): Game.request("hire", {"candidate": int(d.id)}), GREEN, not ok))
	_body.add_child(c)
