extends TestCase
## Challenges and the daily run: sane data, starts that use only the existing systems, goals that finish the run
## (won with a medal, failed at the day limit, scored when time's up), the no-staff rule, the daily run being the
## same for everyone on a date, local records (CHALLENGE_<ID> / DAILY_<yyyymmdd> boards), save/load and co-op sync.
## Records go to a throwaway file, never the real user://challenge_records.json.

const TEST_RECORDS := "user://test_challenge_records.json"
const GOAL_KINDS := ["process", "building", "profit", "cash", "ship", "score_profit", "score_ship"]


func _sim_for(id: String, seed_value: int = 0) -> Sim:
	var def := Challenges.get_def(id)
	var sim := fresh_sim(seed_value if seed_value != 0 else Challenges.seed_of(def))
	Challenges.apply_start(sim, def)
	return sim


func _lot_units(s: GameState) -> Array:
	var lots: Array = s.challenge().get("lots", []).map(func(x): return int(x))
	return s.items.values().filter(func(i): return i.kind == Item.KIND_UNIT and i.unit and lots.has(i.unit.lot_id))


func _check_start(id: String, s: Dictionary) -> void:
	if s.has("building"):
		check(Content.buildings.has(str(s.building)), "%s: premises %s exists" % [id, s.building])
	for t in s.get("fixtures", []):
		check(not Content.fixture(str(t)).is_empty(), "%s: fixture %s exists" % [id, t])
	for st: Dictionary in s.get("staff", []):
		check(Staff.data().roles.any(func(r): return r.id == str(st.get("role", ""))), "%s: staff role %s exists" % [id, st.get("role")])
	for stock: Dictionary in s.get("stock", []):
		check(Content.sellers.has(str(stock.get("seller", ""))), "%s: seller %s exists" % [id, stock.get("seller")])
		for m in stock.get("models", []):
			check(not Content.model(str(m)).is_empty(), "%s: model %s exists" % [id, m])
	for p in s.get("parts", {}):
		check(not Content.part(str(p)).is_empty(), "%s: part %s exists" % [id, p])


func test_challenge_data_is_sane() -> void:
	var defs := Challenges.defs()
	check(defs.size() >= 4, "a handful of challenges (%d)" % defs.size())
	var ids := {}
	for d: Dictionary in defs:
		check(not ids.has(d.id), "unique id %s" % d.id)
		ids[d.id] = true
		check(str(d.get("name", "")) != "" and str(d.get("desc", "")) != "", "%s has a name and description" % d.id)
		var g: Dictionary = d.get("goal", {})
		check(str(g.get("kind", "")) in GOAL_KINDS, "%s: known goal %s" % [d.id, g.get("kind")])
		check(int(d.get("days", 0)) > 0, "%s: a day limit" % d.id)
		var m: Dictionary = d.get("medals", {})
		check(m.has("gold") and m.has("silver") and m.has("bronze"), "%s: three medals" % d.id)
		if not Challenges.is_score(g):
			check(int(m.gold) <= int(m.silver) and int(m.silver) <= int(m.bronze) and int(m.bronze) <= int(d.days), "%s: medal days in order, within the limit" % d.id)
		check(Challenges.goal_text(d) != "", "%s: describes its goal" % d.id)
		eq(Challenges.board(d), "CHALLENGE_" + str(d.id).to_upper(), "%s: leaderboard name" % d.id)
		_check_start(str(d.id), d.get("start", {}))
		if str(g.get("kind", "")) == "process":
			var n := 0
			for stock: Dictionary in d.get("start", {}).get("stock", []):
				n += int(stock.get("count", 0))
			eq(n, int(g.get("count", 0)), "%s: processes all its starting stock" % d.id)


func test_the_daily_run_is_the_same_for_everyone_on_a_date() -> void:
	var a := Challenges.daily_def("20261021")
	var b := Challenges.daily_def("20261021")
	eq(JSON.stringify(a), JSON.stringify(b), "same date, same run")
	eq(Challenges.board(a), "DAILY_20261021", "leaderboard DAILY_<yyyymmdd>")
	eq(Challenges.seed_of(a), Challenges.seed_of(b), "same seed")
	var others := 0
	for key in ["20261022", "20261023", "20261024", "20261025"]:
		if JSON.stringify(Challenges.daily_def(key)) != JSON.stringify(a):
			others += 1
	check(others >= 3, "other days are different runs")
	check(Challenges.is_score(a.goal), "the daily is a score attack")
	check(int(a.medals.gold) > int(a.medals.bronze), "medals by score")
	eq(Challenges.today_key().length(), 8, "today as yyyymmdd")
	_check_start("daily", a.start)
	# Two players starting today's run get identical companies.
	var s1 := fresh_sim(Challenges.seed_of(a))
	Challenges.apply_start(s1, a)
	var s2 := fresh_sim(Challenges.seed_of(a))
	Challenges.apply_start(s2, a)
	eq(s1.state.cash, s2.state.cash, "same cash")
	eq(s1.state.building_id, s2.state.building_id, "same premises")
	eq(_lot_units(s1.state).map(func(i): return i.unit.serial), _lot_units(s2.state).map(func(i): return i.unit.serial), "the very same starting stock")
	eq(s1.state.lots.values().filter(func(l): return l.status == "open").map(func(l): return l.total_price),
		s2.state.lots.values().filter(func(l): return l.status == "open").map(func(l): return l.total_price), "and the same lots for sale")


func test_council_refresh_start() -> void:
	var sim := _sim_for("council_refresh")
	var s := sim.state
	eq(s.building_id, "industrial", "industrial unit")
	eq(s.cash, 12000, "starting cash")
	near(s.reputation, 35.0, 0.01, "reputation")
	eq(s.staff.size(), 4, "a crew of four")
	check(s.fixtures.values().any(func(f): return f.type == "rack_test"), "a network test rack")
	check(s.fixtures.values().any(func(f): return f.type == "rack_image"), "and an imaging rack")
	eq(_lot_units(s).size(), 200, "200 laptops")
	check(_lot_units(s).all(func(i): return i.loc.t == "slot" and str(i.loc.slot).begins_with("i")), "on pallets")
	check(s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET).all(func(p): return p.loc.get("relocate", false)), "pallets waiting for goods-in")
	var usb := s.item_in_slot("test_bench_1:park_a")
	check(usb != null and usb.kind == Item.KIND_USB_TEST, "a test USB parked on the extra bench")
	var ch := s.challenge()
	eq(str(ch.status), "running", "running")
	eq(Challenges.progress(s, ch), [0.0, 200.0], "nothing processed yet")
	eq(Challenges.days_left(s, ch), 5, "five days")
	eq(int(s.day_stats.get("income", 0)), 0, "the opening balance isn't today's income")


func test_process_goal_wins_with_a_medal() -> void:
	var sim := _sim_for("deadline_night")
	var s := sim.state
	check(s.has_licence("night_shift"), "comes with the night shift licence")
	var finished := []
	s.challenge_finished.connect(func(ch): finished.append(ch))
	var units := _lot_units(s)
	eq(units.size(), 40, "40 laptops")
	for i in units.size():
		var u: UnitData = units[i].unit
		if i % 10 == 0:
			sim.state.set_loc(units[i], {"t": "gone"})          # recycled counts as dealt with
		else:
			u.tested = true
			u.imaged = true
			u.grade = "B"
	sim.advance_minutes(30.0)
	var ch := s.challenge()
	eq(str(ch.status), "won", "challenge complete")
	eq(int(ch.result.days), 1, "on day 1")
	eq(str(ch.result.medal), "gold", "gold medal")
	eq(finished.size(), 1, "the finish fires once for local records")
	sim.advance_minutes(60.0)
	eq(finished.size(), 1, "and only once")


func test_target_goal_fails_at_the_day_limit() -> void:
	var sim := _sim_for("overdraft_escape")
	var s := sim.state
	eq(s.cash, -300, "overdrawn")
	var limit := int(s.challenge().days)
	for d in limit:
		check(str(s.challenge().status) == "running", "still running on day %d" % (d + 1))
		sim.advance_minutes(GameState.MIN_PER_DAY)
		sim.start_next_day()
	eq(str(s.challenge().status), "failed", "out of time")
	eq(str(s.challenge().result.medal), "", "no medal")


func test_score_goal_is_scored_when_time_is_up() -> void:
	var def := Challenges.daily_def("20261021")
	def = def.duplicate(true)
	def["goal"] = {"kind": "score_profit"}
	def["medals"] = {"gold": 2000, "silver": 1000, "bronze": 300}
	var sim := fresh_sim(Challenges.seed_of(def))
	Challenges.apply_start(sim, def)
	var s := sim.state
	for d in int(def.days):
		if d == 2:
			s.add_money(5000, "sales", "A very good day")
		sim.advance_minutes(GameState.MIN_PER_DAY)
		sim.start_next_day()
	var ch := s.challenge()
	eq(str(ch.status), "done", "scored")
	check(int(ch.result.score) > 2000, "profit counted (£%d)" % int(ch.result.score))
	eq(str(ch.result.medal), "gold", "gold")


func test_ship_goal_counts_shipments_since_the_start() -> void:
	var sim := _sim_for("flogit_frenzy")
	var s := sim.state
	var units := _lot_units(s)
	eq(units.size(), 30, "30 laptops ready to sell")
	check(units.all(func(i): return i.unit.grade != "" and i.unit.listed), "graded and listed")
	s.bump_stat("shipped", 39)
	Challenges.check(sim)
	eq(str(s.challenge().status), "running", "39 isn't 40")
	s.bump_stat("shipped", 1)
	Challenges.check(sim)
	eq(str(s.challenge().status), "won", "40 shipped")


func test_no_staff_rule() -> void:
	var sim := _sim_for("lone_wolf")
	var s := sim.state
	var c: Dictionary = s.candidates[0]
	s.reputation = 90.0
	check(not sim.execute(1, "hire", {"candidate": int(c.id)}).ok, "no hiring in Lone wolf")
	eq(s.staff.size(), 0, "nobody hired")
	eq(s.fixtures.values().filter(func(f): return f.type == "test_bench").size(), 2, "two test benches")


func test_building_goal() -> void:
	var sim := _sim_for("garage_to_lockup")
	var s := sim.state
	eq(s.cash, 7500, "a bit more in the bank")
	s.cash = 20000
	check(sim.execute(1, "move_building", {"building": "lockup"}).ok, "moved")
	sim.advance_minutes(10.0)
	eq(str(s.challenge().status), "won", "out of the garage")
	eq(str(s.challenge().result.medal), "gold", "on day 1 — gold")


func test_local_records_and_the_daily_attempt() -> void:
	var real := Challenges.records_path
	Challenges.records_path = TEST_RECORDS
	DirAccess.remove_absolute(ProjectSettings.globalize_path(TEST_RECORDS))
	eq(Challenges.submit("CHALLENGE_X", 3.4, true, "Alpha Ltd", "r1"), 1, "first time on the board")
	eq(Challenges.submit("CHALLENGE_X", 2.1, true, "Beta Ltd", "r2"), 1, "faster beats it")
	eq(Challenges.submit("CHALLENGE_X", 4.0, true, "Gamma Ltd", "r3"), 3, "slower is third")
	eq(Challenges.submit("CHALLENGE_X", 1.0, true, "Alpha Ltd", "r1"), 0, "one entry per run")
	eq(Challenges.board_entries("CHALLENGE_X").map(func(e): return str(e.company)), ["Beta Ltd", "Alpha Ltd", "Gamma Ltd"], "fastest first")
	eq(Challenges.submit("DAILY_20261021", 900.0, false, "Alpha Ltd", "d1"), 1, "score board")
	eq(Challenges.submit("DAILY_20261021", 1500.0, false, "Beta Ltd", "d2"), 1, "higher score wins")
	check(Challenges.daily_attempt("20261021").is_empty(), "not played yet")
	Challenges.mark_daily("20261021", {"started": 1})
	check(not Challenges.daily_attempt("20261021").is_empty(), "today's attempt is used up")
	var ch := {"id": "council_refresh", "board": "CHALLENGE_COUNCIL_REFRESH", "status": "won", "goal": {"kind": "process"},
		"result": {"value": 2.5, "medal": "silver", "days": 3}}
	eq(Challenges.submit_result(ch, "Alpha Ltd", "c1"), 1, "a finished challenge goes on its board")
	eq(Challenges.best_medal("council_refresh"), "silver", "best medal remembered")
	ch.result = {"value": 4.6, "medal": "bronze", "days": 5}
	Challenges.submit_result(ch, "Alpha Ltd", "c2")
	eq(Challenges.best_medal("council_refresh"), "silver", "a worse medal doesn't replace it")
	ch.status = "failed"
	eq(Challenges.submit_result(ch, "Alpha Ltd", "c3"), 0, "failed runs don't make the board")
	DirAccess.remove_absolute(ProjectSettings.globalize_path(TEST_RECORDS))
	Challenges.records_path = real


func test_challenge_saves_and_reaches_co_op_guests() -> void:
	var sim := _sim_for("council_refresh")
	var s := sim.state
	var guest := GameState.new()
	guest.load_dict(s.snapshot())
	eq(str(guest.challenge().get("name", "")), str(s.challenge().name), "a guest joining sees the challenge")
	eq(Challenges.progress(guest, guest.challenge()), Challenges.progress(s, s.challenge()), "and the same progress")
	s.dirty.clear()
	var units := _lot_units(s)
	for i in 10:
		units[i].unit.grade = "A"
		s.mark("item", units[i].id)
	sim.advance_minutes(5.0)
	for key in s.dirty:
		var kv: Array = s.dirty[key]
		guest.import_entity(kv[0], kv[1], s.export_entity(kv[0], kv[1]))
	eq(Challenges.progress(guest, guest.challenge()), [10.0, 200.0], "progress replicates")
	var back := GameState.new()
	back.load_dict(JSON.parse_string(JSON.stringify(s.to_dict())))
	eq(str(back.challenge().status), "running", "the challenge survives save/load")
	eq(Challenges.progress(back, back.challenge()), [10.0, 200.0], "with its progress")
