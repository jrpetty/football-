extends TestCase
## Staff morale: what drops it (no breaks, a flat-out week, lagging pay, late orders, overtime), what lifts it
## (tea — more when someone made it for them — pay rises, a daily thank-you, good shifts), what it does
## (speed ±20%, longer or shorter pauses between jobs), and the warning email → notice → leaving chain.
## Also: staff tea breaks through the real kitchen commands, brews handed both ways, save/load and co-op sync.


func _hire(sim: Sim, role: String, level: int, id: int) -> int:
	var c := Staff.make_candidate(sim.state.rng, id)
	c.role = role
	for sk in c.stats:
		c.stats[sk] = level
	c.wage = Staff.wage_for(c.stats)
	sim.state.candidates.append(c)
	check(sim.execute(1, "hire", {"candidate": id}).ok, "hired a level-%d %s" % [level, role])
	return id


func _kitchen(s: GameState) -> void:
	s.register_station("kitchen", "tea")
	for i in 6:
		s.register_slot("kitchen:mug%d" % i, ["mug"], "kitchen")
	s.register_slot("kitchen:sink", ["mug"], "kitchen")


func _brew(sim: Sim, peer: int, mugs: int) -> void:
	## A round made by `peer` (mugs out, boil, wait for it, pour, milk).
	for i in mugs:
		check(sim.execute(peer, "tea", {"station": "kitchen", "act": "mug"}).ok, "mug out")
	check(sim.execute(peer, "tea", {"station": "kitchen", "act": "boil"}).ok, "kettle on")
	sim.state.belt_clock += 20.0
	check(sim.execute(peer, "tea", {"station": "kitchen", "act": "pour"}).ok, "pour")
	check(sim.execute(peer, "tea", {"station": "kitchen", "act": "milk"}).ok, "milk")


func test_new_starters_are_keen() -> void:
	var sim := fresh_sim(301)
	var id := _hire(sim, "tester", 5, 501)
	var d: Dictionary = sim.state.staff[id]
	near(StaffMorale.value(d), float(StaffMorale.c("start", 70)), 0.01, "starting morale")
	eq(StaffMorale.mood(StaffMorale.value(d)), "Happy", "a happy new starter")


func test_morale_scales_speed_and_reliability() -> void:
	var sim := fresh_sim(302)
	var id := _hire(sim, "packer", 5, 502)
	var d: Dictionary = sim.state.staff[id]
	var peer := StaffView.peer_of(id)
	d["morale"] = 0.0
	var slow := sim._skill_speed(peer, "packing")
	var idle_low := StaffMorale.idle_mult(d)
	d["morale"] = 50.0
	var mid := sim._skill_speed(peer, "packing")
	d["morale"] = 100.0
	var fast := sim._skill_speed(peer, "packing")
	var idle_high := StaffMorale.idle_mult(d)
	near(mid, pow(sim.state.skill_speed(Sim.player_key(peer), "packing"), 1.5), 0.001, "morale 50 = their normal pace")
	near(fast / mid, 1.2, 0.001, "a buzzing team is 20% quicker")
	near(slow / mid, 0.8, 0.001, "a miserable one 20% slower")
	check(idle_low > 1.5 and idle_high < 1.0, "and fed-up people dawdle between jobs (×%.2f vs ×%.2f)" % [idle_low, idle_high])
	# Players aren't affected by staff morale.
	eq(sim._skill_speed(1, "packing"), sim.state.skill_speed("p1", "packing"), "players unaffected")


func test_no_break_drains_and_a_tea_break_restores() -> void:
	var sim := fresh_sim(303)
	var s := sim.state
	_kitchen(s)
	var id := _hire(sim, "packer", 4, 503)
	var d: Dictionary = s.staff[id]
	var peer := StaffView.peer_of(id)
	sim.advance_minutes(StaffMorale.break_due(d) + 90.0)
	check(StaffMorale.wants_break(d), "after a couple of hours they want a brew")
	var before := StaffMorale.value(d)
	check(before < float(StaffMorale.c("start", 70)) - 3.0, "going without one wears them down (%.1f)" % before)
	# Their own tea break, through the real kitchen commands.
	_brew(sim, peer, 1)
	var mug: Item = sim._tea_mugs("kitchen")[0]
	check(sim.execute(peer, "pick_up", {"item": mug.id}).ok, "picks up their brew")
	check(sim.execute(peer, "drink_mug", {}).ok, "drinks it")
	near(StaffMorale.value(d), before + float(StaffMorale.c("tea_boost", 8)), 0.01, "a brew perks them up")
	eq(float(d.since_break), 0.0, "break taken")
	check(not StaffMorale.wants_break(d), "and they're good for a while")
	check(sim.execute(peer, "place", {"slot": "kitchen:sink"}).ok, "and they wash up")
	eq(mug.loc.get("t"), "gone", "mug washed")


func test_brews_handed_both_ways() -> void:
	var sim := fresh_sim(304)
	var s := sim.state
	sim.active_peers = [1]
	_kitchen(s)
	var id := _hire(sim, "tester", 5, 504)
	var d: Dictionary = s.staff[id]
	var peer := StaffView.peer_of(id)
	# A player makes the round and hands one to the member of staff — that's worth more than their own.
	_brew(sim, 1, 2)
	var mugs := sim._tea_mugs("kitchen")
	check(sim.execute(1, "pick_up", {"item": mugs[0].id}).ok, "player picks up a brew")
	check(sim.execute(1, "give_mug", {"to": peer}).ok, "and gives it to the member of staff")
	eq(s.held_by(peer).size(), 1, "it's in their hands")
	eq(s.lifetime("brews_for_staff"), 1, "counted")
	var before := StaffMorale.value(d)
	check(sim.execute(peer, "drink_mug", {}).ok, "they drink it")
	near(StaffMorale.value(d), minf(before + float(StaffMorale.c("tea_gift_boost", 14)), 100.0), 0.01, "a brew made for them counts for more")
	check("made them a brew" in str(d.get("why", "")), "and they know who made it (%s)" % str(d.get("why", "")))
	# …and the other way: the member of staff brings the player one.
	check(sim.execute(peer, "place", {"slot": "kitchen:sink"}).ok, "washed up")
	check(sim.execute(peer, "pick_up", {"item": mugs[1].id}).ok, "staff picks up the other brew")
	check(sim.execute(peer, "give_mug", {"to": 1}).ok, "and brings it to the player")
	eq(s.held_by(1).size(), 1, "the player has it")
	eq(s.lifetime("staff_brews"), 1, "a brew from the staff, counted")
	eq(Sim.peer_name(s, peer), str(d.name), "brews are signed with the staff member's name")


func test_away_staff_cant_be_handed_a_brew() -> void:
	var sim := fresh_sim(305)
	var s := sim.state
	_kitchen(s)
	var id := _hire(sim, "tester", 5, 505)
	s.staff[id]["away"] = {"skill": "testing", "back": 3}
	_brew(sim, 1, 1)
	check(sim.execute(1, "pick_up", {"item": sim._tea_mugs("kitchen")[0].id}).ok, "picked up")
	check(not sim.execute(1, "give_mug", {"to": StaffView.peer_of(id)}).ok, "they're not here")


func test_pay_that_lags_hurts_and_a_rise_fixes_it() -> void:
	var sim := fresh_sim(306)
	var s := sim.state
	var id := _hire(sim, "repair_tech", 3, 506)
	var d: Dictionary = s.staff[id]
	var key := Sim.player_key(StaffView.peer_of(id))
	s.player(key).xp["repair"] = Staff.xp_for_level(8)
	d["morale"] = 70.0
	sim.advance_minutes(GameState.MIN_PER_DAY)
	check(int(d.get("wants_rise", 0)) > int(d.wage), "they want a rise")
	check(StaffMorale.value(d) < 70.0, "and it's getting to them (%.1f)" % StaffMorale.value(d))
	check("pay rise" in str(d.get("why", "")), "the reason is the pay (%s)" % str(d.get("why", "")))
	var low := StaffMorale.value(d)
	check(sim.execute(1, "pay_rise", {"staff": id}).ok, "rise given")
	near(StaffMorale.value(d), minf(low + float(StaffMorale.c("pay_rise_boost", 18)), 100.0), 0.01, "chuffed")
	eq(s.lifetime("pay_rises"), 1, "counted")
	# Once they're paid fairly, another rise is a goodwill gesture — smaller, and not every day.
	var w := int(d.wage)
	check(not sim.execute(1, "pay_rise", {"staff": id}).ok, "not two in a row")
	eq(int(d.wage), w, "pay unchanged")
	d["last_rise"] = s.shift_day() - 10
	check(sim.execute(1, "pay_rise", {"staff": id}).ok, "a goodwill rise a week later")
	check(int(d.wage) > w, "a bit more (£%d → £%d)" % [w, int(d.wage)])


func test_thank_you_once_a_day() -> void:
	var sim := fresh_sim(307)
	var s := sim.state
	var id := _hire(sim, "packer", 4, 507)
	var d: Dictionary = s.staff[id]
	d["morale"] = 50.0
	check(sim.execute(1, "thank_staff", {"staff": id}).ok, "thanked")
	near(StaffMorale.value(d), 50.0 + float(StaffMorale.c("thank_boost", 6)), 0.01, "it lifts them")
	check(not sim.execute(1, "thank_staff", {"staff": id}).ok, "but only once a day")
	sim.advance_minutes(GameState.MIN_PER_DAY)
	sim.start_next_day()
	check(sim.execute(1, "thank_staff", {"staff": id}).ok, "tomorrow again")
	eq(s.lifetime("thanks"), 2, "counted")


func test_a_flat_out_week_and_late_orders_wear_people_down() -> void:
	var sim := fresh_sim(308)
	var s := sim.state
	var a := _hire(sim, "tester", 5, 508)
	var b := _hire(sim, "tester", 5, 509)
	s.staff[a]["morale"] = 70.0
	s.staff[b]["morale"] = 70.0
	s.staff[a]["shift_min"] = 900.0
	s.staff[a]["busy_min"] = 860.0          # 96% busy all week…
	s.staff[a]["status"] = "Working: test"  # …and flat out today too
	s.day_stats["late"] = 3
	sim.advance_minutes(GameState.MIN_PER_DAY)
	check(StaffMorale.value(s.staff[a]) < StaffMorale.value(s.staff[b]), "the overworked one is lower (%.1f vs %.1f)" % [StaffMorale.value(s.staff[a]), StaffMorale.value(s.staff[b])])
	check(StaffMorale.value(s.staff[b]) < 70.0, "late orders get to everyone (%.1f)" % StaffMorale.value(s.staff[b]))


func test_good_shifts_lift_the_team() -> void:
	var good := fresh_sim(309)
	var bad := fresh_sim(309)
	var gid := _hire(good, "grader", 5, 510)
	var bid := _hire(bad, "grader", 5, 510)
	good.state.day_stats["shipped"] = 20
	good.state.day_stats["tested"] = 20
	bad.state.day_stats["missed"] = 2
	good.advance_minutes(GameState.MIN_PER_DAY)
	bad.advance_minutes(GameState.MIN_PER_DAY)
	var g := StaffMorale.value(good.state.staff[gid])
	var b := StaffMorale.value(bad.state.staff[bid])
	check(g > b + 8.0, "a five-star shift feels a lot better than a rough one (%.1f vs %.1f)" % [g, b])
	check("great shift" in str(good.state.staff[gid].get("why", "")), "and they say so (%s)" % str(good.state.staff[gid].get("why", "")))


func test_fed_up_staff_warn_then_resign_then_leave() -> void:
	var sim := fresh_sim(310)
	var s := sim.state
	var id := _hire(sim, "hauler", 4, 511)
	var subjects := func() -> Array: return s.totals.get("inbox", []).map(func(m): return str(m.subject))
	var close_low := func() -> void:
		if s.staff.has(id):
			s.staff[id]["morale"] = 5.0
		sim.advance_minutes(GameState.MIN_PER_DAY)
		sim.start_next_day()
	close_low.call()
	check(not subjects.call().has("Can we talk?"), "one bad day is just a bad day")
	close_low.call()
	check(subjects.call().has("Can we talk?"), "two in a row: a warning email")
	check(not s.staff[id].has("notice"), "no notice yet")
	close_low.call()
	check(s.staff[id].has("notice"), "three: they hand in their notice")
	check(subjects.call().has("Resignation"), "by email")
	var leave := int(s.staff[id].notice.leave_day)
	var guard := 0
	while s.staff.has(id) and guard < 10:
		guard += 1
		close_low.call()
	check(not s.staff.has(id), "they leave once the notice is worked")
	check(s.shift_day() > leave, "after their last day")


func test_cheering_them_up_keeps_them() -> void:
	var sim := fresh_sim(311)
	var s := sim.state
	var id := _hire(sim, "tester", 5, 512)
	var d: Dictionary = s.staff[id]
	d["low_days"] = 5
	d["notice"] = {"given": 0, "leave_day": 2}
	d["morale"] = 85.0           # tea, thanks and a rise did the trick (a day without breaks still knocks some off)
	sim.advance_minutes(GameState.MIN_PER_DAY)
	check(s.staff.has(id), "still here")
	check(not d.has("notice"), "notice withdrawn")
	check(s.totals.get("inbox", []).any(func(m): return str(m.subject) == "Staying on"), "they write to say they're staying")


func test_morale_saves_and_reaches_co_op_guests() -> void:
	var sim := fresh_sim(312)
	var s := sim.state
	var guest := GameState.new()
	guest.load_dict(s.snapshot())
	s.dirty.clear()
	var id := _hire(sim, "packer", 4, 513)
	sim.execute(1, "thank_staff", {"staff": id})
	for key in s.dirty:
		var kv: Array = s.dirty[key]
		guest.import_entity(kv[0], kv[1], s.export_entity(kv[0], kv[1]))
	s.dirty.clear()
	near(StaffMorale.value(guest.staff[id]), StaffMorale.value(s.staff[id]), 0.001, "guests see the same morale")
	eq(str(guest.staff[id].get("why", "")), str(s.staff[id].get("why", "")), "and why")
	var back := GameState.new()
	back.load_dict(JSON.parse_string(JSON.stringify(s.to_dict())))
	near(StaffMorale.value(back.staff[id]), StaffMorale.value(s.staff[id]), 0.001, "morale survives save/load")
	eq(int(back.staff[id].get("thanked", -1)), s.shift_day(), "so does today's thank-you")
