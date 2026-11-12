extends TestCase
## Overtime and night shifts: the night shift licence (a one-off Shop purchase) gates it; at 17:00 the host is
## asked whether to keep going; overtime extends the working day to 20:00, 22:00 or 06:00; staff earn
## time-and-a-half (paid with Friday's wages); electricity keeps metering (floodlights at night); morale drops
## faster; players flag after midnight without a brew; rent still lands on Friday's shift. Saves and syncs.


func _hire(sim: Sim, role: String, level: int, id: int) -> int:
	var c := Staff.make_candidate(sim.state.rng, id)
	c.role = role
	for sk in c.stats:
		c.stats[sk] = level
	c.wage = Staff.wage_for(c.stats)
	sim.state.candidates.append(c)
	check(sim.execute(1, "hire", {"candidate": id}).ok, "hired")
	return id


func _licensed(seed_value: int) -> Sim:
	var sim := fresh_sim(seed_value)
	sim.state.cash = 20000
	check(sim.execute(1, "shop_buy", {"id": "night_shift_licence"}).ok, "licence bought")
	return sim


func _hour(s: GameState) -> float:
	return s.minute_of_day() / 60.0


func test_licence_is_a_one_off_shop_purchase() -> void:
	var sim := fresh_sim(501)
	var s := sim.state
	var item: Dictionary = Content.shop_items.get("night_shift_licence", {})
	check(not item.is_empty(), "in the Shop")
	var price := int(item.get("price", 0))
	check(price >= 6000 and price <= 8000, "a mid-game goal (£%d)" % price)
	check(not sim.execute(1, "shop_buy", {"id": "night_shift_licence"}).ok, "can't afford it on day one")
	check(not s.has_licence("night_shift"), "not owned")
	s.cash = 20000
	check(sim.execute(1, "shop_buy", {"id": "night_shift_licence", "qty": 5}).ok, "bought")
	eq(s.cash, 20000 - price, "paid once, whatever the quantity")
	check(s.has_licence("night_shift"), "owned straight away (no parcel)")
	check(not s.schedule.any(func(e): return e.type == "parcel"), "nothing to deliver")
	check(not sim.execute(1, "shop_buy", {"id": "night_shift_licence"}).ok, "only once")


func test_without_the_licence_the_day_ends_at_five() -> void:
	var sim := fresh_sim(502)
	var s := sim.state
	sim.advance_minutes(GameState.MIN_PER_DAY)
	check(not s.day_running, "day over")
	near(_hour(s), 17.0, 0.01, "at 17:00")
	check(not s.overtime_asking(), "nobody asked about overtime")


func test_keep_going_question_holds_the_clock() -> void:
	var sim := _licensed(503)
	var s := sim.state
	sim.advance_minutes(GameState.MIN_PER_DAY)
	check(s.day_running, "the day hasn't ended")
	check(s.overtime_asking(), "the host is asked: keep going?")
	near(_hour(s), 17.0, 0.01, "at 17:00")
	sim.tick(30.0)
	sim.advance_minutes(60.0)
	near(_hour(s), 17.0, 0.01, "the clock waits for the answer")
	check(not sim.execute(2, "overtime", {"choice": "22"}).ok, "guests can't decide")
	check(s.overtime_asking(), "still asking")
	check(sim.execute(1, "overtime", {"choice": "close"}).ok, "the host calls it a day")
	check(not s.day_running, "day over")
	near(_hour(s), 17.0, 0.01, "at 17:00 as normal")


func test_overtime_extends_the_working_day() -> void:
	var sim := _licensed(504)
	var s := sim.state
	var day := s.day_index()
	var got := {}
	s.day_ended.connect(func(sum): got.merge(sum))
	sim.advance_minutes(GameState.MIN_PER_DAY)
	check(sim.execute(1, "overtime", {"choice": "22"}).ok, "keep going until 22:00")
	check(s.day_running, "still working")
	sim.advance_minutes(60.0)
	check(s.overtime_active(), "on overtime")
	near(_hour(s), 18.0, 0.01, "time runs past five")
	sim.advance_minutes(GameState.MIN_PER_DAY)
	check(not s.day_running, "the shift ends")
	near(_hour(s), 22.0, 0.01, "at 22:00")
	eq(s.day_index(), day, "same day")
	near(float(got.get("overtime_h", 0.0)), 5.0, 0.05, "5 hours of overtime on the day card")
	sim.start_next_day()
	eq(s.day_index(), day + 1, "next morning")
	near(_hour(s), 9.0, 0.01, "09:00")
	check(s.shift.is_empty(), "back to normal hours")
	check(s.overtime_asking() == false and not s.overtime_active(), "no overtime")


func test_all_nighter_on_friday_still_pays_friday_rent() -> void:
	var sim := _licensed(505)
	var s := sim.state
	while s.weekday() != int(Content.econ("rent_due_weekday", 4)):
		sim.advance_minutes(GameState.MIN_PER_DAY)
		if s.overtime_asking():
			sim.execute(1, "overtime", {"choice": "close"})
		sim.start_next_day()
	var friday := s.day_index()
	var got := {}
	s.day_ended.connect(func(sum): got.merge(sum))
	sim.advance_minutes(GameState.MIN_PER_DAY)
	check(sim.execute(1, "overtime", {"choice": "night"}).ok, "through the night")
	sim.advance_minutes(GameState.MIN_PER_DAY * 2)
	check(not s.day_running, "shift over")
	eq(s.day_index(), friday + 1, "the calendar says Saturday")
	near(_hour(s), 6.0, 0.01, "at 06:00")
	eq(int(got.get("day", -1)), friday, "but it was Friday's shift")
	check(got.has("rent_paid"), "so Friday's rent was paid")
	near(float(got.get("overtime_h", 0.0)), 13.0, 0.05, "13 hours of overtime")
	eq(s.lifetime("all_nighters"), 1, "an all-nighter")
	sim.start_next_day()
	eq(s.weekday(), 0, "next shift is Monday")
	eq(s.day_index(), friday + 3, "after the weekend")


func test_staff_get_time_and_a_half_paid_on_friday() -> void:
	var sim := _licensed(506)
	var s := sim.state
	var a := _hire(sim, "tester", 5, 701)
	var b := _hire(sim, "packer", 3, 702)
	var hourly := (float(s.staff[a].wage) + float(s.staff[b].wage)) / 40.0
	var got := {}
	s.day_ended.connect(func(sum): got.merge(sum, true))
	sim.advance_minutes(GameState.MIN_PER_DAY)
	check(sim.execute(1, "overtime", {"choice": "20"}).ok, "three hours' overtime")
	sim.advance_minutes(GameState.MIN_PER_DAY)
	near(float(got.get("overtime_h", 0.0)), 3.0, 0.05, "3 hours")
	near(float(got.get("overtime_pay", 0)), hourly * 1.5 * 3.0, 1.0, "at time-and-a-half (£%.0f)" % (hourly * 4.5))
	near(float(s.totals.get("ot_owed", 0.0)), hourly * 1.5 * 3.0, 0.5, "owed until Friday")
	eq(s.ledger.filter(func(e): return str(e.cat) == "overtime").size(), 0, "not paid yet")
	sim.start_next_day()
	while s.weekday() != int(Content.econ("rent_due_weekday", 4)):
		sim.advance_minutes(GameState.MIN_PER_DAY)
		sim.execute(1, "overtime", {"choice": "close"})
		sim.start_next_day()
	sim.advance_minutes(GameState.MIN_PER_DAY)
	sim.execute(1, "overtime", {"choice": "close"})
	var paid := s.ledger.filter(func(e): return str(e.cat) == "overtime")
	eq(paid.size(), 1, "overtime paid with Friday's wages")
	near(-float(paid[0].amt), hourly * 4.5, 1.0, "the full amount")
	check(s.ledger.any(func(e): return str(e.cat) == "wages"), "alongside the normal wages")
	eq(float(s.totals.get("ot_owed", 0.0)), 0.0, "nothing left owing")


func test_overtime_wears_staff_down_faster() -> void:
	var late := _licensed(507)
	var early := _licensed(507)
	var la := _hire(late, "tester", 5, 703)
	var ea := _hire(early, "tester", 5, 703)
	late.advance_minutes(GameState.MIN_PER_DAY)
	late.execute(1, "overtime", {"choice": "22"})
	early.advance_minutes(GameState.MIN_PER_DAY)
	early.execute(1, "overtime", {"choice": "close"})
	var before := StaffMorale.value(late.state.staff[la])
	late.advance_minutes(5.0 * 60.0)
	check(StaffMorale.value(late.state.staff[la]) < before - float(StaffMorale.c("overtime_drain_per_hour", 3.0)) * 4.0, "overtime drains morale (%.1f → %.1f)" % [before, StaffMorale.value(late.state.staff[la])])
	check(StaffMorale.value(late.state.staff[la]) < StaffMorale.value(early.state.staff[ea]), "lower than the crew who went home")


func test_the_meter_keeps_running_and_floodlights_draw_power() -> void:
	var sim := _licensed(508)
	var s := sim.state
	sim.advance_minutes(GameState.MIN_PER_DAY)
	sim.execute(1, "overtime", {"choice": "22"})
	var kwh0 := float(s.day_stats.get("kwh", 0.0))
	check(not Sim.power_breakdown(s).has("Floodlights"), "no floodlights in daylight")
	sim.advance_minutes(4.0 * 60.0)
	check(float(s.day_stats.get("kwh", 0.0)) > kwh0, "electricity keeps metering in overtime")
	check(DayNight.darkness(s.minute_of_day()) > 0.9, "it's dark at 21:00")
	check(Sim.power_breakdown(s).has("Floodlights"), "the yard floodlights are on")
	eq(DayNight.darkness(12.0 * 60.0), 0.0, "and broad daylight at noon")


func test_players_flag_after_midnight_until_they_have_a_brew() -> void:
	var sim := _licensed(509)
	var s := sim.state
	sim.advance_minutes(GameState.MIN_PER_DAY)
	sim.execute(1, "overtime", {"choice": "night"})
	sim.advance_minutes(4.0 * 60.0)
	check(not Player.tired_now(s, false), "21:00 — still going strong")
	sim.advance_minutes(4.0 * 60.0)
	near(_hour(s), 1.0, 0.01, "01:00")
	check(Player.tired_now(s, false), "after midnight you flag")
	check(not Player.tired_now(s, true), "a brew fixes it")


func test_overtime_state_saves_and_syncs() -> void:
	var sim := _licensed(510)
	var s := sim.state
	var guest := GameState.new()
	guest.load_dict(s.snapshot())
	check(guest.has_licence("night_shift"), "guests know about the licence")
	s.dirty.clear()
	sim.advance_minutes(GameState.MIN_PER_DAY)
	for key in s.dirty:
		var kv: Array = s.dirty[key]
		guest.import_entity(kv[0], kv[1], s.export_entity(kv[0], kv[1]))
	s.dirty.clear()
	check(guest.overtime_asking(), "guests see the host is deciding")
	sim.execute(1, "overtime", {"choice": "22"})
	sim.advance_minutes(90.0)
	for key in s.dirty:
		var kv: Array = s.dirty[key]
		guest.import_entity(kv[0], kv[1], s.export_entity(kv[0], kv[1]))
	check(guest.overtime_active(), "and that they're on overtime")
	near(guest.shift_end_minute(), s.shift_end_minute(), 0.01, "until the same time")
	var back := GameState.new()
	back.load_dict(JSON.parse_string(JSON.stringify(s.to_dict())))
	check(back.overtime_active(), "overtime survives a save")
	near(back.shift_end_minute(), s.shift_end_minute(), 0.01, "with the same end")
