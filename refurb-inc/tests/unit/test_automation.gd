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
	check(sim.auto.pos_of(a) >= 1.99, "and stopped at the open end")
	var gap := sim.auto.pos_of(a) - sim.auto.pos_of(b)
	check(b.loc.belt == "c2" and gap > 0.25, "second queues behind with spacing (gap %.2f)" % gap)
	eq(float(a.loc.v), 0.0, "waiting units stop (no network chatter)")


func test_curve_and_sorter_route_by_stage() -> void:
	var sim := fresh_sim(62)
	var s := sim.state
	_fixture(s, "sort", "conveyor_sorter", Vector3(0, 0, 0))
	_fixture(s, "left", "conveyor", Vector3(-1.5, 0, 0.4), 90.0 * -1 + 0)     # runs towards -X
	_fixture(s, "right", "conveyor", Vector3(1.5, 0, 0.4), 90.0)
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
