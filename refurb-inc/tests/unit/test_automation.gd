extends TestCase
## Automation, headless: belts (spacing, hand-over, end stops, devices), the sorter, and a complete
## lights-out line: pallet → arm → test rack → repair robot / imaging rack → conveyor → vision grader →
## arm → stock racking → (sold) packing robot → OUTGOING → courier. Nobody touches anything.


func _fixture(s: GameState, fid: String, type: String, p: Vector3, r: float = 0.0) -> void:
	s.fixtures[fid] = {"type": type, "label": fid, "placed": true, "p": [p.x, p.y, p.z], "r": r}


func _slots(s: GameState, fid: String, stype: String, names: Array, accepts: Array) -> void:
	if stype != "":
		s.register_station(fid, stype)
	for n in names:
		s.register_slot("%s:%s" % [fid, n], accepts, fid if stype != "" else "")


func _run(sim: Sim, seconds: float) -> void:
	var t := 0.0
	while t < seconds:
		sim.advance_minutes(0.53)
		sim.tick_automation(1.0)
		t += 1.0


func test_belts_carry_space_and_hand_over() -> void:
	var sim := fresh_sim(61)
	var s := sim.state
	_fixture(s, "c1", "conveyor", Vector3(0, 0, 0))
	_fixture(s, "c2", "conveyor", Vector3(0, 0, 2.0))
	sim.tick_automation(0.1)
	eq(sim.auto.belts.c1.next.get("out", ""), "c2", "touching ends join up")
	var a := s.new_item(Item.KIND_UNIT)
	a.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	var b := s.new_item(Item.KIND_UNIT)
	b.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	sim.auto.put_on_belt(a, "c1", 0.0)
	_run(sim, 1.0)
	check(sim.auto.belt_has_room("c1", b), "room behind the first unit after a second")
	sim.auto.put_on_belt(b, "c1", 0.0)
	_run(sim, 10.0)
	eq(a.loc.belt, "c2", "front unit handed over to the next conveyor")
	check(sim.auto.pos_of(a) >= sim.auto.end_stop("c2", a) - 0.01 and sim.auto.pos_of(a) <= 2.0 - Automation.item_len(a) * 0.5, "and stopped wholly on the belt at the open end")
	var gap := sim.auto.pos_of(a) - sim.auto.pos_of(b)
	check(b.loc.belt == "c2" and gap > 0.25, "second queues behind with spacing (gap %.2f)" % gap)
	eq(float(a.loc.v), 0.0, "waiting units stop (no network chatter)")


func test_curve_and_sorter_route_by_stage() -> void:
	var sim := fresh_sim(62)
	var s := sim.state
	_fixture(s, "sort", "conveyor_sorter", Vector3(0, 0, 0))
	_fixture(s, "left", "conveyor", Vector3(-1.78, 0, 0.4), 90.0 * -1 + 0)     # runs towards -X
	_fixture(s, "right", "conveyor", Vector3(1.78, 0, 0.4), 90.0)
	s.fixtures["left"].r = -90.0
	sim.tick_automation(0.1)
	var ok_left: bool = sim.auto.belts.sort.next.get("left", "") == "left"
	var ok_right: bool = sim.auto.belts.sort.next.get("right", "") == "right"
	check(ok_left and ok_right, "side exits join: %s" % str(sim.auto.belts.sort.next))
	var bad := s.new_item(Item.KIND_UNIT)
	bad.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	bad.unit.tested = true
	bad.unit.faults = [{"id": "storage_dead", "detail": {}, "fixed": false}]
	var good := s.new_item(Item.KIND_UNIT)
	good.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	good.unit.tested = true
	good.unit.faults = []
	sim.auto.put_on_belt(bad, "sort")
	_run(sim, 3.0)
	sim.auto.put_on_belt(good, "sort")
	_run(sim, 6.0)
	eq(bad.loc.get("belt", ""), "left", "faulty kit goes left")
	eq(good.loc.get("belt", ""), "right", "passed kit goes right")
	check(sim.execute(1, "set_sorter_rule", {"sorter": "sort", "out": "left", "rule": "passed"}).ok)
	eq(sim.auto.sorter_rules("sort").left, "passed")


func test_big_kit_fits_and_rides() -> void:
	## A 2U rack server (0.48 m wide, 0.72 m long) loads, rides round a curve and stops wholly on the belt.
	var sim := fresh_sim(64)
	var s := sim.state
	_fixture(s, "c1", "conveyor", Vector3(0, 0, 0))
	_fixture(s, "k1", "conveyor_curve_r", Vector3(-0.254, 0, 1.746))
	sim.tick_automation(0.1)
	eq(sim.auto.belts.c1.next.get("out", ""), "k1", "straight feeds the curve")
	var srv := s.new_item(Item.KIND_UNIT)
	srv.unit = sim.lots.roll_unit("corvex_rampart_2200", 0.5)
	var w := float(srv.unit.model().visual.w)
	check(w < 0.6, "server width %.2f fits the 0.6 m between the rails" % w)
	s.register_slot("c1:load", ["unit", "unit_large"], "")
	s.set_loc(srv, {"t": "slot", "slot": "c1:load"})
	sim.tick_automation(0.1)
	eq(srv.loc.get("t"), "belt", "loading point sends it off")
	check(sim.auto.pos_of(srv) >= Automation.item_len(srv) * 0.5, "set down wholly on the belt")
	_run(sim, 12.0)
	eq(srv.loc.get("belt", ""), "k1", "rode on round the curve")
	check(absf(sim.auto.pos_of(srv) - sim.auto.end_stop("k1", srv)) < 0.02, "and waits wholly on the curve at its open end")


func test_lights_out_factory_ships_a_lot() -> void:
	var sim := fresh_sim(63)
	var s := sim.state
	s.fixtures.clear()
	s.parts["ssd_nvme_256"] = 20
	s.parts["ram_ddr4_8"] = 20
	s.parts["bat_kestrel_swift"] = 20
	s.parts["fan_laptop_generic"] = 20
	s.parts["keys_kestrel"] = 20
	s.consumables["boxes"] = 50
	# Cell 1 around arm A1 (0,0,0): pallet, test rack, repair robot, imaging rack, conveyor in.
	_fixture(s, "a1", "robot_arm", Vector3(0, 0, 0))
	_fixture(s, "rt", "rack_test", Vector3(-1.6, 0, 0), 90.0)
	_slots(s, "rt", "rack_test", range(6).map(func(i): return "bay%d" % i), ["unit", "unit_large"])
	_fixture(s, "rr", "robot_repair", Vector3(0, 0, -1.5))
	_slots(s, "rr", "robot_repair", ["unit"], ["unit", "unit_large"])
	_fixture(s, "ri", "rack_image", Vector3(1.6, 0, 0), -90.0)
	_slots(s, "ri", "rack_image", range(6).map(func(i): return "bay%d" % i), ["unit", "unit_large"])
	# Conveyor from beside A1 through the vision grader to an end stop beside A2.
	_fixture(s, "c1", "conveyor", Vector3(0, 0, 2.2))          # in-port at z 1.2 (in reach of A1)
	_fixture(s, "g1", "conveyor_grader", Vector3(0, 0, 3.9))
	_fixture(s, "c2", "conveyor", Vector3(0, 0, 5.6))          # end stop at z 6.6
	# Cell 2 around arm A2 (0,0,7.6): stock racking, packing robot, OUTGOING shelf.
	_fixture(s, "a2", "robot_arm", Vector3(0, 0, 7.6))
	_fixture(s, "h1", "heavy_rack", Vector3(-1.6, 0, 7.6), 90.0)
	_slots(s, "h1", "", range(6).map(func(i): return str(i)), ["unit_large", "unit"])
	_fixture(s, "rp", "robot_pack", Vector3(1.6, 0, 7.6), -90.0)
	s.register_station("rp", "robot_pack")
	s.register_slot("rp:unit", ["unit", "unit_large"], "rp")
	s.register_slot("rp:out", ["package", "package_large"], "rp")
	_fixture(s, "courier_1", "courier_shelf", Vector3(0, 0, 9.1), 180.0)
	_slots(s, "courier_1", "courier", range(4).map(func(i): return str(i)), ["package", "package_large"])
	# A pallet of six laptops beside A1.
	var pallet := s.new_item(Item.KIND_PALLET)
	pallet.data = {"cell": [0.4, 0.03, 0.4], "grid": [3, 2]}
	s.set_loc(pallet, {"t": "loose", "p": [0.0, 0.0, -0.1 + 1.2], "r": [0, 0, 0, 1]})
	s.set_loc(pallet, {"t": "loose", "p": [1.2, 0.0, 1.0], "r": [0, 0, 0, 1]})
	var units: Array = []
	for k in 6:
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = sim.lots.roll_unit("kestrel_swift_14", 0.4)
		# Keep faults to ones a level 1 robot can fix (and a couple of passes).
		it.unit.faults = [] if k % 2 == 0 else [{"id": "storage_dead", "detail": {}, "fixed": false}]
		if k % 2 == 1:
			s.parts[Valuation.repair_part_for(it.unit, it.unit.faults[0])] = 10
		it.unit.cosmetic = ["light_scuffs_lid"]
		s.set_loc(it, {"t": "slot", "slot": "i%d:%d" % [pallet.id, k]})
		units.append(it)
	# Run the line for 40 real minutes; sell whatever gets listed; courier at the end.
	var shipped := 0
	for minute in 40:
		_run(sim, 60.0)
		for it: Item in units:
			if s.items.has(it.id) and it.unit.listed and it.unit.order_id == 0:
				sim._create_order(it, "flogit")
		if minute % 10 == 9:
			sim.courier_collect()
	shipped = units.filter(func(it): return not s.items.has(it.id)).size()
	var where: Array = []
	for it: Item in units:
		if s.items.has(it.id):
			where.append("%s %s/%s faults=%s" % [it.loc.get("t"), str(it.loc.get("slot", it.loc.get("belt", ""))), it.unit.stage(), str(it.unit.open_faults().map(func(f): return f.id))])
	eq(shipped, 6, "all six shipped with no human (stuck: %s)" % str(where))
	check(int(s.totals.get("units_sold", 0)) >= 6, "sales booked")
	# Every machine event was counted for the Insights app.
	var kinds: Dictionary = s.stats.get("auto", {}).get("kinds", {})
	for k in ["rack_test", "rack_image", "robot_pack", "grader", "arm", "belt_in"]:
		check(int(kinds.get(k, 0)) > 0, "automation stats counted %s (%s)" % [k, str(kinds)])
	check(int(kinds.get("rack_test", 0)) >= 6 and int(kinds.get("robot_pack", 0)) >= 6, "one test and one pack per unit: %s" % str(kinds))
	check(int(s.stats.auto.fixtures.get("a1", {}).get("arm", 0)) > 0, "per-machine counts kept")


func test_flexible_bend_chains_any_angle() -> void:
	## straight → 45° bend → straight, placed with the same snapping maths the player uses.
	var sim := fresh_sim(65)
	var s := sim.state
	_fixture(s, "c1", "conveyor", Vector3(0, 0, 0))
	var bend := Automation.clamp_bend({"angle": 45, "radius": 1.5, "turn": "right"})
	s.fixtures["b1"] = {"type": "conveyor_bend", "label": "b1", "placed": true, "p": [0.0, 0.0, 1.0], "r": 0.0, "bend": bend}
	var geo := Automation.belt_world(s.fixtures.b1)
	var out_p: Vector3 = (geo.xf as Transform3D) * (geo.lb.out[0].p as Vector3)
	var out_d: Vector3 = (geo.xf as Transform3D).basis * (geo.lb.out[0].d as Vector3)
	# The next straight starts where the bend ends, facing along it.
	var rot := rad_to_deg(atan2(out_d.x, out_d.z))
	var c2 := out_p + out_d * 1.0
	_fixture(s, "c2", "conveyor", c2, rot)
	sim.tick_automation(0.1)
	eq(sim.auto.belts.c1.next.get("out", ""), "b1", "straight feeds the bend")
	eq(sim.auto.belts.b1.next.get("out", ""), "c2", "bend feeds the next straight (%.0f°)" % rot)
	check(absf(float(sim.auto.belts.b1.length) - 1.5 * deg_to_rad(45.0)) < 0.01, "bend length follows radius × angle")
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	sim.auto.put_on_belt(it, "c1")
	_run(sim, 12.0)
	eq(it.loc.get("belt", ""), "c2", "rode round the bend onto the next straight")
	var long := Automation.clamp_bend({"angle": 180, "radius": 2.5})
	check(float(long.radius) * deg_to_rad(180.0) <= Automation.BEND_MAX_LEN + 0.001, "bends are capped at %.1f m" % Automation.BEND_MAX_LEN)


func test_merge_joins_a_side_belt() -> void:
	var sim := fresh_sim(66)
	var s := sim.state
	_fixture(s, "m1", "conveyor_merge", Vector3(0, 0, 0))
	# Side conveyor coming in from the left (running +X), ending at the merge's side input.
	_fixture(s, "side", "conveyor", Vector3(-Automation.SORTER_SIDE - 1.0, 0, -0.1), 90.0)
	sim.tick_automation(0.1)
	eq(sim.auto.belts.side.next.get("out", ""), "m1", "side belt joins the merger")
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	sim.auto.put_on_belt(it, "side")
	_run(sim, 6.0)
	eq(it.loc.get("belt", ""), "m1", "kit merged onto the main line")
	check(sim.auto.pos_of(it) > 0.6, "and carried on along it")


func test_stock_tower_stores_and_dispatches() -> void:
	var sim := fresh_sim(67)
	var s := sim.state
	_fixture(s, "tw", "stock_tower", Vector3(0, 0, 0))
	s.register_station("tw", "stock_tower")
	# In-conveyor ends at the tower's left hatch (running -Z into it); out-conveyor leaves the right hatch.
	_fixture(s, "cin", "conveyor", Vector3(-0.35, 0, 1.62), 180.0)
	_fixture(s, "cout", "conveyor", Vector3(0.35, 0, 1.62), 0.0)
	sim.tick_automation(0.1)
	eq(sim.auto.belts.cin.next.get("out", ""), "tower:tw", "conveyor feeds the tower")
	eq(sim.auto.towers.tw.out_belt, "cout", "tower dispatches onto its out conveyor")
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	it.unit.tested = true
	it.unit.imaged = true
	it.unit.grade = "A"
	sim.auto.put_on_belt(it, "cin")
	_run(sim, 6.0)
	eq(it.loc.get("t"), "slot", "stored on a tray")
	check(str(it.loc.get("slot", "")).begins_with("tw:t"), "in the tower")
	_run(sim, 3.0)
	eq(it.loc.get("t"), "slot", "stays put until it sells")
	it.unit.order_id = 99
	_run(sim, 3.0)
	eq(it.loc.get("belt", ""), "cout", "sold: out it comes")


func test_arm_uses_a_persons_test_bench() -> void:
	## No test rack: the arm loads a USB test bench a person has set up, then takes the unit on.
	var sim := fresh_sim(68)
	var s := sim.state
	s.fixtures.clear()
	_fixture(s, "a1", "robot_arm", Vector3(0, 0, 0))
	_fixture(s, "tb", "test_bench", Vector3(-1.4, 0, 0), 90.0)
	s.register_station("tb", "bench")
	s.register_slot("tb:unit", ["unit", "unit_large"], "tb")
	s.register_slot("tb:usb", ["usb_test", "usb_image"], "tb")
	var usb := s.new_item(Item.KIND_USB_TEST)
	s.set_loc(usb, {"t": "slot", "slot": "tb:usb"})
	_fixture(s, "c0", "conveyor", Vector3(0, 0, -2.0))      # end stop at z -1.0, in reach
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	sim.tick_automation(0.1)
	sim.auto.put_on_belt(it, "c0")
	_run(sim, 10.0)
	eq(str(it.loc.get("slot", "")), "tb:unit", "arm put it on the person's test bench")
	_run(sim, 120.0)
	check(it.unit.tested, "the bench tested it")


func _laptop(sim: Sim) -> Item:
	var it := sim.state.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	return it


func test_splitter_alternates_then_overflows() -> void:
	var sim := fresh_sim(70)
	var s := sim.state
	s.fixtures.clear()
	_fixture(s, "sp", "conveyor_splitter", Vector3(0, 0, 0))
	# Side exits sit where the path stops, in the middle of the cross-transfer; a conveyor leaves each side.
	# Named from the kit's point of view (travelling +Z, its left is +X — the same way the curves are named).
	var z_exit := -0.7 + 1.4 - Automation.SPLIT_TAIL
	_fixture(s, "l", "conveyor", Vector3(Automation.SORTER_SIDE + 1.0, 0, z_exit), 90.0)
	_fixture(s, "r", "conveyor", Vector3(-Automation.SORTER_SIDE - 1.0, 0, z_exit), -90.0)
	sim.tick_automation(0.1)
	var nx: Dictionary = sim.auto.belts.sp.next
	check(nx.get("left", "") == "l" and nx.get("right", "") == "r" and not nx.has("out"), "both side exits join, nothing straight on: %s" % str(nx))
	# Alternate (default): left, right, left, right.
	var sent: Array = []
	for k in 4:
		var it := _laptop(sim)
		sim.auto.put_on_belt(it, "sp")
		_run(sim, 3.0)
		sent.append(it.loc.get("belt", ""))
	eq(sent, ["l", "r", "l", "r"], "alternates between the two lines")
	eq(int(s.stats.auto.kinds.get("split", 0)), 4, "every split counted")
	# Overflow: fill the left line until it backs up, only then use the right.
	check(sim.execute(1, "set_belt_mode", {"id": "sp", "mode": "overflow"}).ok)
	eq(Automation.belt_mode(s, "sp", "splitter"), "overflow")
	check(not sim.execute(1, "set_belt_mode", {"id": "sp", "mode": "hold"}).ok, "a splitter has no hold mode")
	var seq: Array = []
	var left_full_when_right := true
	for k in 7:
		var it := _laptop(sim)
		sim.auto.put_on_belt(it, "sp")
		_run(sim, 3.0)
		var where := str(it.loc.get("belt", ""))
		seq.append(where)
		if where == "r" and sim.auto.belt_has_room("l", it, 0.0):
			left_full_when_right = false
	var first_r := seq.find("r")
	check(first_r > 0, "overflow went left first: %s" % str(seq))
	check(seq.slice(0, first_r).all(func(w): return w == "l"), "only left until it backed up: %s" % str(seq))
	check(left_full_when_right, "right only used once the left line was full")
	# Back to alternate with the left still full: it skips the full side instead of stalling.
	check(sim.execute(1, "set_belt_mode", {"id": "sp", "mode": "alternate"}).ok)
	var it2 := _laptop(sim)
	sim.auto.put_on_belt(it2, "sp")
	_run(sim, 3.0)
	eq(it2.loc.get("belt", ""), "r", "a full side is skipped (overflow went %s)" % str(seq))


func test_speed_tiers_move_kit_faster_and_hand_over() -> void:
	var sim := fresh_sim(71)
	var s := sim.state
	s.fixtures.clear()
	s.cash = 100000
	for k in 3:
		_fixture(s, "a%d" % k, "conveyor", Vector3(0, 0, k * 2.0))
		_fixture(s, "b%d" % k, "conveyor", Vector3(3.0, 0, k * 2.0))
	sim.tick_automation(0.1)
	var plan := Automation.upgrade_plan(s, "b1")
	eq(plan.get("ids", []).size(), 3, "upgrading offers the whole run")
	var cash0 := s.cash
	check(sim.execute(1, "upgrade_belt", {"id": "b1"}).ok, "upgrade to Fast")
	eq(s.cash, cash0 - 3 * int(Automation.tier_info(1).cost), "paid once per piece for the tier")
	eq(Automation.tier_of(s.fixtures.b0), 1, "the whole run went Fast")
	eq(Automation.tier_of(s.fixtures.a0), 0, "the other line didn't")
	check(sim.execute(1, "upgrade_belt", {"id": "b2"}).ok, "and on to Express")
	eq(Automation.tier_of(s.fixtures.b0), 2)
	check(not sim.execute(1, "upgrade_belt", {"id": "b2"}).ok, "nothing above Express")
	sim.tick_automation(0.01)
	near(float(sim.auto.belts.b0.speed), 0.6 * float(Automation.tier_info(2).speed_mult), 0.001, "Express runs faster")
	# Race: same laptop on each line.
	var slow := _laptop(sim)
	var fast := _laptop(sim)
	sim.auto.put_on_belt(slow, "a0", 0.2)
	sim.auto.put_on_belt(fast, "b0", 0.2)
	_run(sim, 4.0)
	eq(fast.loc.get("belt", ""), "b2", "Express kit crossed two hand-overs in 4 s")
	eq(slow.loc.get("belt", ""), "a1", "Standard kit only reached the second belt")
	_run(sim, 2.0)
	check(absf(sim.auto.pos_of(fast) - sim.auto.end_stop("b2", fast)) < 0.02, "and stopped wholly on the belt at the open end")
	# Mixed speeds keep handing over and spacing: Express → Standard → Express.
	for k in 3:
		_fixture(s, "m%d" % k, "conveyor", Vector3(6.0, 0, k * 2.0))
	s.fixtures.m0["tier"] = 2
	s.fixtures.m2["tier"] = 2
	sim.tick_automation(0.01)
	var batch: Array = []
	for k in 4:
		var it := _laptop(sim)
		sim.auto.put_on_belt(it, "m0", 0.2)
		batch.append(it)
		_run(sim, 1.0)
	_run(sim, 20.0)
	check(batch.all(func(it): return it.loc.get("belt", "") == "m2"), "all four made it to the last belt: %s" % str(batch.map(func(it): return it.loc.get("belt", ""))))
	var ps: Array = batch.map(func(it): return sim.auto.pos_of(it))
	ps.sort()
	var ok_gap := true
	for i in ps.size() - 1:
		if ps[i + 1] - ps[i] < 0.25:
			ok_gap = false
	check(ok_gap, "queued with real spacing: %s" % str(ps))


func test_faster_belts_draw_more_power() -> void:
	var sim := fresh_sim(72)
	var s := sim.state
	s.fixtures.clear()
	_fixture(s, "c", "conveyor", Vector3.ZERO)
	sim.tick_automation(0.01)
	var it := _laptop(sim)
	sim.auto.put_on_belt(it, "c", 0.2)
	var name := str(Content.fixture("conveyor").name)
	var kw0 := float(Sim.power_breakdown(s)[name].kw)
	near(kw0, float(Content.fixture("conveyor").power_kw), 0.0001, "a running Standard belt draws its rating")
	s.fixtures.c["tier"] = 2
	var kw2 := float(Sim.power_breakdown(s)[name].kw)
	near(kw2, kw0 * float(Automation.tier_info(2).power_mult), 0.0001, "Express draws more (%.3f → %.3f kW)" % [kw0, kw2])


func test_peoples_hatch_holds_kit_for_people_and_sends_it_on() -> void:
	var sim := fresh_sim(73)
	var s := sim.state
	s.fixtures.clear()
	_fixture(s, "c0", "conveyor", Vector3(0, 0, -1.8))       # ends at z -0.8, the hatch's in-port
	_fixture(s, "h", "work_hatch", Vector3(0, 0, 0))          # 1.6 m, pick face in the middle
	_fixture(s, "c1", "conveyor", Vector3(0, 0, 1.8))         # carries on from the hatch
	sim.tick_automation(0.1)
	eq(sim.auto.belts.c0.next.get("out", ""), "h", "chains on like a conveyor")
	eq(sim.auto.belts.h.next.get("out", ""), "c1", "and feeds the next one")
	var a := _laptop(sim)
	var b := _laptop(sim)
	sim.auto.put_on_belt(a, "c0", 0.2)
	_run(sim, 1.0)
	sim.auto.put_on_belt(b, "c0", 0.2)
	_run(sim, 12.0)
	eq(a.loc.get("belt", ""), "h", "kit for people stops at the hatch")
	var face := sim.auto.pick_face("h")
	check(sim.auto.pos_of(a) <= face - Automation.item_len(a) * 0.5 + 0.001 and float(a.loc.v) == 0.0, "front edge at the pick face, stopped (s %.2f)" % sim.auto.pos_of(a))
	eq(b.loc.get("belt", ""), "h", "the next one queues behind it")
	check(sim.auto.pos_of(a) - sim.auto.pos_of(b) > 0.25, "with spacing")
	_run(sim, 10.0)
	eq(a.loc.get("belt", ""), "h", "nothing rides past the pick face on its own")
	# A person takes it, and the next one moves up…
	check(sim.execute(1, "pick_up", {"item": a.id}).ok, "a person picks it off the hatch")
	_run(sim, 4.0)
	check(absf(sim.auto.pos_of(b) - (face - Automation.item_len(b) * 0.5 - 0.01)) < 0.02, "the next one moves up to the pick face")
	# …then they send it on from the far half.
	s.register_slot("h:load", ["unit", "unit_large", "package", "package_large"], "")
	check(sim.execute(1, "place", {"slot": "h:load"}).ok, "put on the send point")
	_run(sim, 1.0)
	eq(a.loc.get("belt", ""), "h", "set down on the hatch")
	check(sim.auto.pos_of(a) - Automation.item_len(a) * 0.5 > face, "wholly past the pick face")
	_run(sim, 6.0)
	eq(a.loc.get("belt", ""), "c1", "sent on down the line")
	eq(b.loc.get("belt", ""), "h", "while the next one still waits for a person")
	var kinds: Dictionary = s.stats.auto.kinds
	check(int(kinds.get("hatch_in", 0)) == 2 and int(kinds.get("hatch_send", 0)) == 1, "hatch events counted: %s" % str(kinds))


func test_stop_gate_holds_the_line() -> void:
	var sim := fresh_sim(74)
	var s := sim.state
	s.fixtures.clear()
	_fixture(s, "c0", "conveyor", Vector3(0, 0, 0))           # z -1 .. 1
	_fixture(s, "g", "conveyor_gate", Vector3(0, 0, 1.5))     # z  1 .. 2
	_fixture(s, "c1", "conveyor", Vector3(0, 0, 3.0))         # z  2 .. 4
	sim.tick_automation(0.1)
	eq(sim.auto.belts.g.next.get("out", ""), "c1")
	check(sim.execute(1, "set_belt_mode", {"id": "g", "mode": "hold"}).ok)
	var it := _laptop(sim)
	sim.auto.put_on_belt(it, "c0", 0.2)
	_run(sim, 8.0)
	eq(it.loc.get("belt", ""), "g", "held at the gate")
	check(float(it.loc.v) == 0.0 and absf(sim.auto.pos_of(it) - sim.auto.end_stop("g", it)) < 0.02, "waiting against the blade")
	check(sim.execute(1, "set_belt_mode", {"id": "g", "mode": "open"}).ok)
	_run(sim, 3.0)
	eq(it.loc.get("belt", ""), "c1", "open: through it goes")
	eq(int(s.stats.auto.kinds.get("gate", 0)), 1)


func test_line_monitor_reports_machines_and_bottleneck() -> void:
	var sim := fresh_sim(75)
	var s := sim.state
	s.fixtures.clear()
	_fixture(s, "a1", "robot_arm", Vector3(0, 0, 0))
	_fixture(s, "rt", "rack_test", Vector3(-1.6, 0, 0), 90.0)
	_slots(s, "rt", "rack_test", range(6).map(func(i): return "bay%d" % i), ["unit", "unit_large"])
	_fixture(s, "c0", "conveyor", Vector3(0, 0, -2.0))        # end stop at z -1.0, in the arm's reach
	_fixture(s, "sp", "conveyor_splitter", Vector3(4.0, 0, -6.0))
	_fixture(s, "mon", "line_monitor", Vector3(3.0, 0, 3.0))
	sim.tick_automation(0.1)
	var feed: Array = []
	for k in 9:
		feed.append(_laptop(sim))
	# Keep the conveyor fed until all six bays are running (the rest queue on the belt).
	var t := 0
	while t < 90 and s.stations.rt.get("jobs", {}).size() < 6:
		for it: Item in feed:
			if it.loc.get("t") == "gone" and sim.auto.belt_has_room("c0", it, 0.2):
				sim.auto.put_on_belt(it, "c0", 0.2)
				break
		_run(sim, 1.0)
		t += 1
	_run(sim, 5.0)
	var st := Automation.line_status(s, "mon")
	var rows := {}
	for r in st.machines:
		rows[r.fid] = r
	check(rows.has("a1") and rows.has("rt") and rows.has("sp"), "arm, rack and splitter listed: %s" % str(rows.keys()))
	check(not rows.has("mon") and not rows.has("c0"), "plain belts and the monitor aren't machines")
	eq(rows.get("rt", {}).get("state", ""), "busy", "the rack is testing")
	check(int(rows.get("rt", {}).get("jobs", 0)) == 6, "all six bays running (%d)" % int(rows.get("rt", {}).get("jobs", 0)))
	check(int(rows.get("rt", {}).get("queue", 0)) >= 1, "untested kit queued for it (%d)" % int(rows.get("rt", {}).get("queue", 0)))
	eq(rows.get("sp", {}).get("state", ""), "starved", "an empty splitter is starved while the line has work")
	check(int(st.on_belts) >= 1, "kit on the belts counted")
	check(int(s.stations.rt.get("util", 0)) > 0, "the host published the rack's load")
	check(int(st.mph) > 0 and int(s.stations.mon.get("line", {}).get("mph", 0)) > 0, "moves per hour published to the monitor (%d)" % int(st.mph))
	# Bottleneck: the busiest machine with work waiting for it.
	s.stations.rt["util"] = 97
	s.stations.a1["util"] = 35
	st = Automation.line_status(s, "mon")
	eq(st.bottleneck, "rt", "the full test rack is the bottleneck")
	check(str(st.bottleneck_text).contains(str(s.fixtures.rt.label)), "named in plain English: %s" % st.bottleneck_text)
	# Kit on a conveyor that no arm feeds the rack from isn't counted as queueing for it.
	var q_before := int(rows.rt.queue)
	_fixture(s, "c8", "conveyor", Vector3(-8.0, 0, 8.0))
	sim.tick_automation(0.01)
	var stray := _laptop(sim)
	sim.auto.put_on_belt(stray, "c8", 0.2)
	for r in Automation.line_status(s, "mon").machines:
		if r.fid == "rt":
			eq(int(r.queue), q_before, "a stray conveyor's kit isn't queued for the rack")
	# A stage with nothing to do it is called out.
	var passed := _laptop(sim)
	passed.unit.tested = true
	passed.unit.faults = []
	_fixture(s, "c9", "conveyor", Vector3(8.0, 0, 8.0))
	sim.tick_automation(0.01)
	sim.auto.put_on_belt(passed, "c9", 0.2)
	st = Automation.line_status(s, "mon")
	check(str(st.bottleneck_text).begins_with("Nothing on the line does wipe & imaging"), "missing imaging called out: %s" % st.bottleneck_text)
	# An empty premises is idle, not starved.
	var sim2 := fresh_sim(76)
	sim2.state.fixtures.clear()
	_fixture(sim2.state, "sp", "conveyor_splitter", Vector3.ZERO)
	var st2 := Automation.line_status(sim2.state)
	eq(st2.machines[0].state, "idle")
	eq(st2.flow, "idle")
