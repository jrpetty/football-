extends TestCase
## AI staff: candidates, hiring, skills through the normal XP system, speed spread by level, Friday wages
## and pay rises, and pay that climbs steeply with ability.


func _hire(sim: Sim, role: String, level: int, id: int) -> int:
	var c := Staff.make_candidate(sim.state.rng, id)
	c.role = role
	for sk in c.stats:
		c.stats[sk] = level
	c.wage = Staff.wage_for(c.stats)
	sim.state.candidates.append(c)
	check(sim.execute(1, "hire", {"candidate": id}).ok, "hired a level-%d %s" % [level, role])
	return id


func test_candidates_and_hiring() -> void:
	var sim := fresh_sim(81)
	var s := sim.state
	check(s.candidates.size() >= 4, "a new game has candidates to hire")
	var c: Dictionary = s.candidates[0]
	check(sim.execute(1, "hire", {"candidate": int(c.id)}).ok, "hire one")
	check(s.staff.has(int(c.id)), "on the books")
	var key := Sim.player_key(StaffView.peer_of(int(c.id)))
	eq(s.skill_level(key, Staff.main_skill(str(c.role))), int(c.stats[Staff.main_skill(str(c.role))]), "their stat card becomes real skill levels")
	check(sim.execute(1, "fire", {"staff": int(c.id)}).ok, "let them go")
	check(not s.staff.has(int(c.id)), "gone")


func test_level_decides_speed_and_pay() -> void:
	var sim := fresh_sim(82)
	var slow := _hire(sim, "tester", 1, 901)
	var fast := _hire(sim, "tester", 10, 902)
	var sp_slow := sim._skill_speed(StaffView.peer_of(slow), "testing")
	var sp_fast := sim._skill_speed(StaffView.peer_of(fast), "testing")
	check(sp_slow < 0.5, "a level-1 tester is very slow (×%.2f)" % sp_slow)
	check(sp_fast > 2.0, "a level-10 tester is quick (×%.2f)" % sp_fast)
	check(sp_fast / sp_slow > 4.0, "the gap is big (%.1f×)" % (sp_fast / sp_slow))
	var w_slow := int(sim.state.staff[slow].wage)
	var w_fast := int(sim.state.staff[fast].wage)
	check(w_fast > w_slow * 2.5, "and the expert costs far more (£%d vs £%d a week)" % [w_fast, w_slow])
	check(Staff.value_per_week("tester", 10) > Staff.value_per_week("tester", 1) * 3, "and is worth far more")


func test_wages_and_pay_rises() -> void:
	var sim := fresh_sim(83)
	var s := sim.state
	var id := _hire(sim, "packer", 3, 903)
	var before := int(s.staff[id].wage)
	# They get much better at packing (and everything else) over the week…
	var key := Sim.player_key(StaffView.peer_of(id))
	for sk in s.player(key).xp:
		s.player(key).xp[sk] = Staff.xp_for_level(8)
	# …so at close they ask for a rise (rises aren't automatic — you give them in the Staff app),
	# and Friday's wages leave the account.
	sim.advance_minutes(GameState.MIN_PER_DAY)
	check(int(s.staff[id].get("wants_rise", 0)) > before, "they ask for a rise once their skills outgrow their pay")
	check(s.totals.get("inbox", []).any(func(m): return str(m.subject) == "Pay review?"), "by email")
	sim.start_next_day()
	while s.weekday() != int(Content.econ("rent_due_weekday", 4)):
		sim.advance_minutes(GameState.MIN_PER_DAY)
		sim.start_next_day()
	sim.advance_minutes(GameState.MIN_PER_DAY)
	var paid := s.ledger.filter(func(e): return str(e.cat) == "wages")
	check(not paid.is_empty(), "wages paid on Friday")
	eq(int(s.staff[id].wage), before, "no automatic rise")
	check(sim.execute(1, "pay_rise", {"staff": id}).ok, "give them the rise")
	check(int(s.staff[id].wage) > before, "pay rise after levelling up (£%d → £%d)" % [before, int(s.staff[id].wage)])
	eq(int(s.staff[id].wage), StaffMorale.fair_wage(s, s.staff[id]), "up to what their skills are worth")
	check(not s.staff[id].has("wants_rise"), "and they stop asking")
