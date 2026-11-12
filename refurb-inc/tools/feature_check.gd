extends Node
## Runtime checks for staff morale/tea, the night shift and challenges in REAL levels (bodies, navigation, views):
##   A. Garage: a crew that's due a brew walks to the kitchen, makes it with the real tea commands, drinks it,
##      washes up — and one brings the player a brew (give_mug). Morale rises; nobody gets stuck.
##   B. Garage + night shift licence: "keep going?" at 17:00, overtime through the night, the sky goes dark and the
##      yard floodlights come on, the HUD shows the overtime line and tiredness after midnight.
##   C. The council refresh challenge starts in the industrial unit: equipment laid out, 200 laptops on pallets in
##      goods-in, the crew on the floor, the challenge banner up.
##   godot --headless --path . res://tools/feature_check.tscn            (prints FEATURES OK / FEATURES FAILED)

var _fails: PackedStringArray = []
var _level: Node


func _ready() -> void:
	await _tea_breaks()
	await _night_shift()
	await _challenge_start()
	Engine.time_scale = 1.0
	if _fails.is_empty():
		print("FEATURES OK")
	else:
		print("FEATURES FAILED: ", ", ".join(_fails))
	get_tree().quit(0 if _fails.is_empty() else 1)


func _check(c: bool, m: String) -> void:
	print("  %s %s" % ["✔" if c else "✖", m])
	if not c:
		_fails.append(m)


func _free_level() -> void:
	## Before a new company: the old level must not lay the new company's equipment out in its own building.
	if _level:
		_level.queue_free()
		_level = null
		await get_tree().process_frame
		await get_tree().process_frame


func _load_level(building: String) -> void:
	await _free_level()
	_level = load(load("res://scripts/game_root.gd").level_path(building)).instantiate()
	add_child(_level)
	await get_tree().create_timer(1.0).timeout


func _hire(role: String, level: int, id: int) -> int:
	var c := Staff.make_candidate(Game.state.rng, id)
	c.role = role
	for sk in c.stats:
		c.stats[sk] = level
	c.wage = Staff.wage_for(c.stats)
	Game.state.candidates.append(c)
	Game.sim.execute(1, "hire", {"candidate": id})
	return id


# ================================================================= A. tea breaks

func _tea_breaks() -> void:
	print("== A. staff tea breaks (garage)")
	Game.new_game(7101)
	await _load_level("garage")
	StaffPlanner.mate_chance = 1.0
	var s := Game.state
	var a := _hire("packer", 5, 801)
	var b := _hire("grader", 5, 802)
	await get_tree().create_timer(1.0).timeout
	for id in [a, b]:
		s.staff[id]["since_break"] = 400.0       # well overdue
		s.staff[id]["morale"] = 40.0
	var brews0 := s.lifetime("brews")
	Engine.time_scale = 3.0
	var t := 0.0
	var seen := {}
	while t < 90.0:
		await get_tree().create_timer(0.5 * Engine.time_scale).timeout
		t += 0.5
		for id in [a, b]:
			var st := str(s.staff[id].get("status", ""))
			if str(seen.get(id, "")) != st:
				seen[id] = st
				print("    [%.1fs] %s: %s (holding %d)" % [t, s.staff[id].name, st, s.held_by(StaffView.peer_of(id)).size()])
		var mugs_in_hand := s.items.values().filter(func(i): return i.kind == Item.KIND_MUG and i.loc.get("t") == "held" and int(i.loc.get("peer", 0)) <= -1000).size()
		if [a, b].all(func(i): return float(s.staff[i].get("since_break", 999.0)) < 100.0) and s.lifetime("staff_brews") > 0 and mugs_in_hand == 0:
			break
	Engine.time_scale = 1.0
	check_tea(a, b, brews0)
	StaffPlanner.mate_chance = 0.35


func check_tea(a: int, b: int, brews0: int) -> void:
	var s := Game.state
	_check(s.lifetime("brews") > brews0, "the kettle was used (%d brews)" % (s.lifetime("brews") - brews0))
	for id in [a, b]:
		_check(float(s.staff[id].get("since_break", 999.0)) < 100.0, "%s had a tea break" % s.staff[id].name)
		_check(StaffMorale.value(s.staff[id]) > 40.0, "%s cheered up (%.0f)" % [s.staff[id].name, StaffMorale.value(s.staff[id])])
	_check(s.lifetime("staff_brews") > 0, "a member of staff brought the player a brew")
	var mugs_left := s.items.values().filter(func(i): return i.kind == Item.KIND_MUG and i.loc.get("t") == "held" and int(i.loc.get("peer", 0)) <= -1000).size()
	_check(mugs_left == 0, "staff washed their mugs up (%d still in hand)" % mugs_left)


# ================================================================= B. night shift

func _night_shift() -> void:
	print("== B. night shift (garage)")
	await _free_level()
	Game.new_game(7102)
	Game.state.cash = 30000
	Game.sim.execute(1, "shop_buy", {"id": "night_shift_licence"})
	await _load_level("garage")
	var hud := Hud.new()
	add_child(hud)
	var s := Game.state
	_hire("tester", 5, 811)
	Game.sim.advance_minutes(GameState.MIN_PER_DAY)
	await get_tree().create_timer(0.5).timeout
	_check(s.overtime_asking(), "17:00: keep going?")
	_check(hud.overtime_prompt._root.visible, "the Keep going? card is up")
	Game.request("overtime", {"choice": "night"})
	await get_tree().create_timer(0.5).timeout
	_check(not hud.overtime_prompt._root.visible, "card closes once the host decides")
	Game.sim.advance_minutes(4.5 * 60.0)
	await get_tree().create_timer(0.8).timeout
	var dn: DayNight = _level.get_node_or_null("DayNight")
	_check(dn != null and dn.darkness_now > 0.9, "dark outside at 21:30 (%.2f)" % (dn.darkness_now if dn else -1.0))
	_check(dn != null and dn._floods.size() >= 2 and dn._floods.all(func(f): return f.visible), "yard floodlights on (%d)" % (dn._floods.size() if dn else 0))
	_check(_level.sun == null or not _level.sun.visible or _level.sun.light_energy < 0.3, "the sun is down")
	_check("OVERTIME" in hud.shift_label.text, "HUD: %s" % hud.shift_label.text.replace("\n", " / "))
	Game.sim.advance_minutes(4.0 * 60.0)
	await get_tree().create_timer(0.8).timeout
	_check("flagging" in hud.shift_label.text, "after midnight the HUD says you're flagging")
	var p: Player = _level.local_player()
	if p:
		p.give_tea(60.0, "")
		await get_tree().create_timer(0.8).timeout
		_check(not p.is_tired(), "a brew fixes it")
	Game.sim.advance_minutes(GameState.MIN_PER_DAY)
	await get_tree().create_timer(0.3).timeout
	_check(not s.day_running and is_equal_approx(s.minute_of_day(), 360.0), "the shift ends at 06:00")
	Game.sim.start_next_day()
	await get_tree().create_timer(0.8).timeout
	_check(dn != null and dn.darkness_now == 0.0, "daylight again in the morning")
	hud.queue_free()


# ================================================================= C. challenge start

func _challenge_start() -> void:
	print("== C. council refresh challenge (industrial unit)")
	await _free_level()
	Challenges.begin("council_refresh", "Feature Check Ltd")
	await _load_level(Game.state.building_id)
	var hud := Hud.new()
	add_child(hud)
	await get_tree().create_timer(1.5).timeout
	var s := Game.state
	_check(s.building_id == "industrial", "in the industrial unit")
	var placed := s.fixtures.values().filter(func(f): return f.get("placed", false)).size()
	_check(placed == s.fixtures.size(), "all equipment laid out (%d/%d)" % [placed, s.fixtures.size()])
	var units := s.items.values().filter(func(i): return i.kind == Item.KIND_UNIT)
	_check(units.size() == 200, "200 laptops (%d)" % units.size())
	var pallets := s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET)
	_check(pallets.all(func(p): return not p.loc.get("relocate", false)), "pallets dropped in goods-in (%d)" % pallets.size())
	_check(_level.staff_views.size() == 4, "the crew is on the floor (%d)" % _level.staff_views.size())
	_check(hud.challenge_hud._banner.visible, "challenge banner showing")
	await get_tree().create_timer(0.6).timeout
	print("    banner: %s | %s | %s" % [hud.challenge_hud._b_title.text, hud.challenge_hud._b_goal.text, hud.challenge_hud._b_days.text])
	_check("200" in hud.challenge_hud._b_goal.text, "banner shows the goal")
	hud.queue_free()
