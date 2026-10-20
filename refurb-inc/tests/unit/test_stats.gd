extends TestCase
## Business Insights: history is recorded as the loop runs and the report adds up.

const P := 1
const FLOW := preload("res://tests/unit/test_flow.gd")


func _cmd(sim: Sim, cmd: String, args: Dictionary = {}) -> void:
	var r := sim.execute(P, cmd, args)
	check(r.ok, "%s failed: %s" % [cmd, r.msg])


func _sell_one(sim: Sim) -> Item:
	## Buy a lot, take one unit all the way to the courier, return it.
	var s := sim.state
	FLOW.new()._setup_garage(sim)
	var lot: Dictionary = s.lots.values().filter(func(l): return l.status == "open")[0]
	_cmd(sim, "buy_lot", {"lot": lot.id})
	sim.end_day()
	sim.start_next_day()
	sim.advance_minutes(90)
	var target: Item = s.items.values().filter(func(i): return i.kind == Item.KIND_UNIT)[0]
	_cmd(sim, "pick_up", {"item": target.id})
	_cmd(sim, "place", {"slot": "bench_1:unit"})
	var usb_t := s.item_in_slot("desk:usb_a")
	_cmd(sim, "pick_up", {"item": usb_t.id})
	_cmd(sim, "place", {"slot": "bench_1:usb"})
	sim.advance_minutes(_job_minutes(sim, "bench_1") + 2.0)
	if target.unit.has_open_faults():
		_cmd(sim, "pick_up", {"item": target.id})
		_cmd(sim, "place", {"slot": "repair_1:unit"})
		for f in target.unit.open_faults():
			s.parts[Valuation.repair_part_for(target.unit, f)] = 2
		s.players[Sim.player_key(P)].xp["repair"] = 99999
		var guard := 0
		while target.unit.has_open_faults() and guard < 6:
			guard += 1
			sim.execute(P, "repair", {"station": "repair_1"})
		_cmd(sim, "pick_up", {"item": target.id})
		_cmd(sim, "place", {"slot": "bench_1:unit"})
	_cmd(sim, "pick_up", {"item": usb_t.id})
	_cmd(sim, "place", {"slot": "desk:usb_a"})
	var usb_i := s.item_in_slot("desk:usb_b")
	_cmd(sim, "pick_up", {"item": usb_i.id})
	_cmd(sim, "place", {"slot": "bench_1:usb"})
	sim.advance_minutes(_job_minutes(sim, "bench_1") + 2.0)
	_cmd(sim, "pick_up", {"item": target.id})
	_cmd(sim, "place", {"slot": "grade_1:unit"})
	_cmd(sim, "grade", {"station": "grade_1"})
	_cmd(sim, "pick_up", {"item": target.id})
	_cmd(sim, "place", {"slot": "shelf_1:0"})
	_cmd(sim, "set_price", {"item": target.id, "price": int(target.unit.list_price * 0.7)})
	_cmd(sim, "list", {"item": target.id, "listed": true})
	var g := 0
	while target.unit.order_id == 0 and g < 60:
		g += 1
		sim.flogit_hourly()
	_cmd(sim, "pick_up", {"item": target.id})
	_cmd(sim, "place", {"slot": "pack_1:unit"})
	_cmd(sim, "pack", {"station": "pack_1"})
	var pkg := s.item_in_slot("pack_1:unit")
	_cmd(sim, "pick_up", {"item": pkg.id})
	_cmd(sim, "place", {"slot": "courier_1:0"})
	sim.courier_collect()
	return target


func test_sale_is_recorded_with_full_costs() -> void:
	var sim := fresh_sim(2024)
	var s := sim.state
	var u := _sell_one(sim).unit
	eq(s.stats.sales.size(), 1, "one sale recorded")
	var rec: Dictionary = s.stats.sales[0]
	eq(int(rec.cost), u.cost, "purchase price carried to the sale")
	eq(int(rec.parts), u.parts_cost, "parts cost carried to the sale")
	check(int(rec.cost) > 0, "unit had a purchase cost")
	eq(int(rec.profit), int(rec.price) - int(rec.fee) - int(rec.post) - int(rec.pack) - int(rec.cost) - int(rec.parts), "profit maths")
	check(float(rec.days_in_stock) >= 0.0 and float(rec.process_days) >= 0.0, "timeline recorded")
	eq(s.stats.purchases.size(), 1)
	eq(int(s.stats.tests.run), 1)
	eq(int(s.stats.orders.placed), 1)
	var r := BusinessStats.compute(s)
	eq(int(r.sales.count), 1)
	eq(int(r.sales.revenue), int(rec.price))
	eq(int(round(r.sales.avg_cost)), u.cost)
	eq(int(r.purchasing.units), int(s.stats.purchases[0].count))
	eq(int(r.stock.units), int(s.stats.purchases[0].count) - 1, "rest of the lot still in stock")
	eq(int(r.orders.shipped), 1)
	# The money dictionary is the un-trimmed ledger.
	var net := 0
	for e in s.ledger:
		net += int(e.amt)
	eq(int(r.finance.net), net, "all-time net matches the ledger")
	eq(int(r.overview.cash), s.cash)
	var acts: Dictionary = s.stats.people[Sim.player_key(P)].acts
	check(int(acts.tested) == 1 and int(acts.graded) == 1 and int(acts.packed) == 1 and int(acts.lots_bought) == 1, "per-player work counted: %s" % str(acts))
	eq(r.purchasing.by_seller.size(), 1)
	eq(int(r.purchasing.by_seller[0].sold), 1, "lot outcome tracks the sale")


func test_hours_days_and_save() -> void:
	var sim := fresh_sim(5)
	var s := sim.state
	sim.active_peers = [1]
	sim.advance_minutes(120)
	sim.end_day()
	sim.start_next_day()
	sim.advance_minutes(60)
	var r := BusinessStats.compute(s)
	var me: Dictionary = r.people.team.filter(func(x): return x.key == "p1")[0]
	check(absf(float(me.hours) - 3.0) < 0.01, "3 hours on shift (%.2f)" % float(me.hours))
	eq(int(me.days), 2, "two days worked")
	eq(s.stats.days.size(), 1, "one day closed")
	eq(int(r.people.staff), 0)
	# Survives a save/load.
	var s2 := GameState.new()
	s2.load_dict(JSON.parse_string(JSON.stringify(s.to_dict())))
	eq(s2.stats.days.size(), 1)
	check(absf(float(s2.stats.people.p1.minutes) - 180.0) < 0.01)
	# Snapshot for joining clients leaves the history on the host.
	check(not s.snapshot().has("stats"))


func test_working_time_skips_nights_and_weekends() -> void:
	var fri_4pm := 4 * GameState.MIN_PER_DAY + 16 * 60.0
	var mon_10am := 7 * GameState.MIN_PER_DAY + 10 * 60.0
	eq(int(BusinessStats.working_minutes(fri_4pm, mon_10am)), 120, "Fri 16:00 → Mon 10:00 is two working hours")
	check(absf(BusinessStats.working_days(0.0, 7 * GameState.MIN_PER_DAY) - 5.0) < 0.001, "a week is five working days")


func test_report_reconciles_after_weeks_of_trading() -> void:
	var sim := fresh_sim(31)
	var bot := BusinessBot.new(sim, 31)
	bot.run_days(15)
	var s := sim.state
	var r := BusinessStats.compute(s)
	check(int(r.sales.count) > 20, "the bot sold plenty (%d)" % int(r.sales.count))
	eq(int(r.operations.funnel[0].count), int(r.purchasing.units), "funnel starts at units bought")
	var by_grade := 0
	for g: Dictionary in r.sales.by_grade:
		by_grade += int(g.count)
	eq(by_grade, int(r.sales.count), "grade breakdown covers every sale")
	var heat := 0
	for row in r.pricing.heat:
		for c in row:
			heat += int(c)
	eq(heat, int(r.orders.placed), "demand heatmap counts every order")
	var deal_units := 0
	for d: Dictionary in r.purchasing.deals:
		deal_units += int(d.units)
	eq(deal_units, int(r.purchasing.units), "every lot lands in a deal band")
	check(r.timeline.any(func(e): return String(e.text).begins_with("First sale")), "first sale is on the timeline")
	check(not r.insights.is_empty(), "insights generated")
	check(not r.records.is_empty() and not r.goals.is_empty(), "records and goals")
	check(r.operations.stages.any(func(x): return int(x.n) > 0), "pipeline timings recorded")
	check(float(r.operations.bench_util_pct) > 0.0 and float(r.operations.bench_util_pct) <= 100.0, "bench utilisation in range")
	var q_tested := 0
	for m: Dictionary in r.quality.models:
		q_tested += int(m.tested)
	eq(q_tested, int(r.operations.tests), "reliability covers every test")
	check(int(r.customers.unique) > 0, "customers recorded")
	check(r.people.awards.size() > 0, "awards handed out")


func _job_minutes(sim: Sim, sid: String) -> float:
	## Minutes left on a station job (job times now depend on skill level).
	var job: Dictionary = sim.state.stations.get(sid, {}).get("job", {})
	if job.is_empty():
		return 1.0
	return float(job.start) + float(job.dur) - sim.state.minute
