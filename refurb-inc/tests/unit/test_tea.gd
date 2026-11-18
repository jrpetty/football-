extends TestCase
## The tea round: mugs out, boil, pour, milk, carry, hand to a colleague, drink, wash up — and the kettle
## goes on the electricity bill.


func _kitchen(s: GameState) -> void:
	s.register_station("kitchen", "tea")
	for i in 6:
		s.register_slot("kitchen:mug%d" % i, ["mug"], "kitchen")
	s.register_slot("kitchen:sink", ["mug"], "kitchen")


func _wait(sim: Sim, secs: float) -> void:
	var t := 0.0
	while t < secs:
		sim.tick_automation(0.5)
		t += 0.5


func test_a_round_for_the_team() -> void:
	var sim := fresh_sim(71)
	var s := sim.state
	_kitchen(s)
	sim.active_peers = [1, 2]
	for i in 3:
		check(sim.execute(1, "tea", {"station": "kitchen", "act": "mug"}).ok, "mug %d out" % i)
	eq(sim._tea_mugs("kitchen").size(), 3, "three mugs on the worktop")
	check(not sim.execute(1, "tea", {"station": "kitchen", "act": "pour"}).ok, "can't pour before boiling")
	var kwh0 := float(s.day_stats.get("kwh", 0.0))
	check(sim.execute(1, "tea", {"station": "kitchen", "act": "boil"}).ok, "kettle on")
	check(float(s.day_stats.get("kwh", 0.0)) > kwh0, "the kettle uses electricity")
	check(not sim.execute(1, "tea", {"station": "kitchen", "act": "pour"}).ok, "not boiled yet")
	_wait(sim, 17.0)
	check(sim.execute(1, "tea", {"station": "kitchen", "act": "pour"}).ok, "pour the round")
	for m: Item in sim._tea_mugs("kitchen"):
		check(float(m.data.fill) > 0.8 and m.data.brew == "tea", "mug %d filled" % m.id)
	check(sim.execute(1, "tea", {"station": "kitchen", "act": "milk"}).ok, "milk in")
	check(sim._tea_mugs("kitchen").all(func(m): return m.data.get("milk", false)), "all milky")
	eq(int(s.day_stats.get("brews", 0)), 3, "three brews counted")
	# Carry two (one each hand), hand one to player 2, drink the other.
	var mugs := sim._tea_mugs("kitchen")
	check(sim.execute(1, "pick_up", {"item": mugs[0].id}).ok and sim.execute(1, "pick_up", {"item": mugs[1].id}).ok, "carry two mugs")
	check(sim.execute(1, "give_mug", {"to": 2}).ok, "hand a brew to a colleague")
	eq(s.held_by(2).size(), 1, "it's in their hands")
	check(sim.execute(2, "drink_mug", {}).ok, "they drink it")
	check(float(s.held_by(2)[0].data.fill) <= 0.05, "and it's empty")
	check(not sim.execute(2, "drink_mug", {}).ok, "can't drink an empty mug")
	check(sim.execute(2, "place", {"slot": "kitchen:sink"}).ok, "into the sink")
	eq(s.held_by(2).size(), 0, "hands free")
	var gone: bool = s.get_item(mugs[1].id) == null or s.get_item(mugs[1].id).loc.get("t") == "gone"
	check(gone, "washed up")
	check(sim.execute(1, "drink_mug", {}).ok, "drink my own")


func test_electricity_is_metered_and_billed() -> void:
	var sim := fresh_sim(72)
	var s := sim.state
	check(sim.power_draw_kw() > 0.0, "lights and kit draw power")
	sim.advance_minutes(60.0)
	var kwh := float(s.day_stats.get("kwh", 0.0))
	check(kwh > 0.0, "an hour of the working day used %.2f kWh" % kwh)
	sim.advance_minutes(GameState.MIN_PER_DAY)
	for d in 3:
		sim.start_next_day()
		sim.advance_minutes(GameState.MIN_PER_DAY)
	var bills := s.ledger.filter(func(e): return str(e.desc).begins_with("Electricity:"))
	check(not bills.is_empty() and bills.all(func(e): return int(e.amt) < 0), "electricity billed at close (pennies carry over): %s" % str(bills))
	check(float(s.totals.get("kwh", 0.0)) > kwh, "lifetime kWh tracked")


func test_goals_are_timed_in_working_days() -> void:
	var sim := fresh_sim(73)
	var s := sim.state
	eq(Campaign.day_number(s), 1, "day 1 of the run")
	sim.advance_minutes(GameState.MIN_PER_DAY)
	sim.start_next_day()
	eq(Campaign.day_number(s), 2, "a working day later it's day 2")
	s.totals["revenue"] = 12000
	s.totals["units_sold"] = 3
	sim.advance_minutes(5.0)
	var got := Campaign.reached(s)
	check(got.has("first_sale") and got.has("revenue_10k"), "milestones reached: %s" % str(got))
	eq(int(got.revenue_10k), 2, "timed on the day they happened")
	check(not got.has("empire"), "no empire yet")
	s.totals["revenue"] = 20000001
	s.building_id = "megaplant"
	sim.advance_minutes(5.0)
	check(Campaign.reached(s).has("empire"), "mega plant + £20M = end goal")


func test_hundred_brews_milestone_counts_real_brews() -> void:
	var sim := fresh_sim(66)
	var s := sim.state
	check(not Campaign.check(s).has("brews_100"), "not yet")
	s.bump_stat("brews", 100)
	check(Campaign.check(s).has("brews_100"), "100 brews made → Chief tea maker milestone")
