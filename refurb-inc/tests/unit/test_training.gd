extends TestCase
## Training: staff courses (cost, days away but still paid, back one level up, capped at 10) and players'
## online courses (instant XP, diminishing returns), all from data/training.json.


func _hire(sim: Sim, role: String, level: int, id: int) -> int:
	var c := Staff.make_candidate(sim.state.rng, id)
	c.role = role
	for sk in c.stats:
		c.stats[sk] = level
	c.wage = Staff.wage_for(c.stats)
	sim.state.candidates.append(c)
	check(sim.execute(1, "hire", {"candidate": id}).ok, "hired")
	return id


func test_course_data_covers_every_skill() -> void:
	for sk: Dictionary in Content.skill_defs():
		check(not Training.staff_course(sk.id).is_empty(), "a staff course for %s" % sk.id)
		var oc := Training.online_course(sk.id)
		check(not oc.is_empty(), "an online course for %s" % sk.id)
		check(int(oc.get("price", 0)) > 0 and int(oc.get("xp", 0)) > 0, "%s online course has a price and XP" % sk.id)
	check(Training.staff_cost(8) > Training.staff_cost(2), "higher levels cost more to train")
	check(Training.staff_days(8) >= Training.staff_days(2), "and take at least as long")


func test_staff_course_away_paid_and_back_a_level_up() -> void:
	var sim := fresh_sim(401)
	var s := sim.state
	s.cash = 20000
	var id := _hire(sim, "tester", 4, 601)
	var d: Dictionary = s.staff[id]
	var key := Sim.player_key(StaffView.peer_of(id))
	eq(s.skill_level(key, "testing"), 4, "level 4 tester")
	var cash0 := s.cash
	check(sim.execute(1, "send_course", {"staff": id, "skill": "testing"}).ok, "sent on a course")
	eq(cash0 - s.cash, Training.staff_cost(4), "paid for the course")
	check(s.ledger.back().cat == "training", "as training")
	check(StaffMorale.is_away(d), "away")
	var back := int(d.away.back)
	eq(back, Training.return_day(0, Training.staff_days(4)), "back after the course's working days")
	check(not sim.execute(1, "send_course", {"staff": id, "skill": "repair"}).ok, "can't double-book")
	check(not sim.execute(1, "thank_staff", {"staff": id}).ok, "not here to thank")
	eq(sim.overtime_rate_per_minute(), 0.0, "no overtime pay while they're away")
	eq(sim.weekly_wages(), int(d.wage), "still on the payroll")
	eq(s.lifetime("courses"), 1, "counted")
	var guard := 0
	while StaffMorale.is_away(d) and guard < 10:
		guard += 1
		sim.advance_minutes(GameState.MIN_PER_DAY)
		sim.start_next_day()
	check(not StaffMorale.is_away(d), "back at work")
	eq(s.day_index(), back, "on the day they were due")
	eq(s.skill_level(key, "testing"), 5, "a level up in testing")
	check("course" in str(d.get("why", "")), "and full of it (%s)" % str(d.get("why", "")))


func test_course_level_cap() -> void:
	var sim := fresh_sim(402)
	var s := sim.state
	s.cash = 50000
	var id := _hire(sim, "repair_tech", 10, 602)
	check(not sim.execute(1, "send_course", {"staff": id, "skill": "repair"}).ok, "nothing left to learn at level 10")
	check(not StaffMorale.is_away(s.staff[id]), "still at work")


func test_course_needs_the_money() -> void:
	var sim := fresh_sim(403)
	var s := sim.state
	var id := _hire(sim, "packer", 6, 603)
	s.cash = -400
	check(not sim.execute(1, "send_course", {"staff": id, "skill": "packing"}).ok, "the bank says no")


func test_online_courses_give_xp_with_diminishing_returns() -> void:
	var sim := fresh_sim(404)
	var s := sim.state
	var cash0 := s.cash
	var gains: Array = []
	for i in 3:
		var before := int(s.player("p1").xp.get("repair", 0))
		check(sim.execute(1, "buy_course", {"skill": "repair"}).ok, "course %d bought" % (i + 1))
		gains.append(int(s.player("p1").xp.get("repair", 0)) - before)
	check(gains[0] > gains[1] and gains[1] > gains[2], "each repeat teaches less (%s)" % str(gains))
	eq(gains[0], int(Training.online_course("repair").xp), "the first one in full")
	eq(cash0 - s.cash, 3 * int(Training.online_course("repair").price), "paid for three")
	check(s.skill_level("p1", "repair") > 1, "and levelled up")
	eq(int(s.player("p1").courses.repair), 3, "courses remembered per player")
	eq(int(s.player("p2").get("courses", {}).get("repair", 0)), 0, "each player has their own")
	check(not sim.execute(StaffView.peer_of(1), "buy_course", {"skill": "repair"}).ok, "staff use the Staff app, not this")


func test_training_survives_save_and_sync() -> void:
	var sim := fresh_sim(405)
	var s := sim.state
	s.cash = 20000
	var guest := GameState.new()
	guest.load_dict(s.snapshot())
	s.dirty.clear()
	var id := _hire(sim, "grader", 3, 604)
	sim.execute(1, "send_course", {"staff": id, "skill": "grading"})
	sim.execute(1, "buy_course", {"skill": "sales"})
	for key in s.dirty:
		var kv: Array = s.dirty[key]
		guest.import_entity(kv[0], kv[1], s.export_entity(kv[0], kv[1]))
	s.dirty.clear()
	check(StaffMorale.is_away(guest.staff[id]), "guests see they're on a course")
	eq(int(guest.player("p1").courses.sales), 1, "and the course I took")
	var back := GameState.new()
	back.load_dict(JSON.parse_string(JSON.stringify(s.to_dict())))
	eq(int(back.staff[id].away.back), int(s.staff[id].away.back), "the course survives save/load")
