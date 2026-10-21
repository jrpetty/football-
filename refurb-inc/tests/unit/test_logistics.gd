extends TestCase
## Whole-pallet logistics, headless: the forklift (get in / out, one driver, lift, carry, set down), pallet racking
## (bays per level, only the forklift reaches the upper levels, the Stock app's "Racking R1 · level 2"), and the
## pallet → line hand-off: a delivered pallet set down in an unload bay is emptied onto the goods-in conveyor by a
## robot arm. Slots are registered by hand, as the rack views do in a level.

const P := 1
const P2 := 2


func _fixture(s: GameState, fid: String, type: String, p: Vector3, r: float = 0.0) -> void:
	var label := Logistics.fixture_number_label(s, type)
	s.fixtures[fid] = {"type": type, "label": label if label != "" else fid, "placed": true, "p": [p.x, p.y, p.z], "r": r}
	for n in Logistics.slot_names(type):
		s.register_slot("%s:%s" % [fid, n], [Item.KIND_PALLET], "")


func _forklift(s: GameState, p: Vector3) -> Item:
	var fl := s.new_item(Item.KIND_FORKLIFT)
	fl.data = {"h": 0.0}
	s.set_loc(fl, {"t": "loose", "p": [p.x, p.y, p.z], "r": [0, 0, 0, 1]})
	return fl


func _pallet_with_units(sim: Sim, p: Vector3, n: int = 6) -> Item:
	var s := sim.state
	var pallet := s.new_item(Item.KIND_PALLET)
	var lay := Sim.pallet_layout("kestrel_swift_14")
	pallet.data = {"cell": lay.cell, "grid": lay.grid}
	s.set_loc(pallet, {"t": "loose", "p": [p.x, p.y, p.z], "r": [0, 0, 0, 1]})
	for k in n:
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
		s.set_loc(it, {"t": "slot", "slot": "i%d:%d" % [pallet.id, k]})
	return pallet


func _v(a: Variant) -> Array:
	var v: Vector3 = a
	return [v.x, v.y, v.z]


func _run(sim: Sim, seconds: float) -> void:
	var t := 0.0
	while t < seconds:
		sim.advance_minutes(0.53)
		sim.tick_automation(1.0)
		t += 1.0


# ---------------------------------------------------------------- the forklift

func test_forklift_get_in_and_out() -> void:
	var sim := fresh_sim(301)
	var s := sim.state
	var fl := _forklift(s, Vector3(2, 0, 2))
	var laptop := s.new_item(Item.KIND_UNIT)
	laptop.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	s.set_loc(laptop, {"t": "loose", "p": [1, 0, 1], "r": [0, 0, 0, 1]})
	check(sim.execute(P2, "pick_up", {"item": laptop.id}).ok, "player 2 carries a laptop")
	check(not sim.execute(P2, "forklift_enter", {"item": fl.id}).ok, "can't climb in with your hands full")
	check(sim.execute(P, "forklift_enter", {"item": fl.id}).ok, "get in")
	eq(fl.loc.t, "driven")
	eq(sim.forklift_of(P), fl)
	check(not sim.execute(P2, "forklift_enter", {"item": fl.id}).ok, "one driver at a time")
	var truck: Item = s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET_TRUCK)[0]
	check(not sim.execute(P, "take_truck", {"item": truck.id}).ok, "can't push a pallet truck from the seat")
	check(sim.execute(P2, "drop", {"p": [1, 0, 1]}).ok)
	check(not sim.execute(P, "pick_up", {"item": laptop.id}).ok, "can't pick things up while driving")
	check(sim.execute(P, "forklift_exit", {"p": [5, 0, 6], "r": [0, 0.7071, 0, 0.7071], "h": 0.8}).ok, "get out")
	eq(fl.loc.t, "loose")
	eq(fl.loc.p, [5.0, 0.0, 6.0], "parked where it was driven to")
	near(float(fl.data.h), 0.8, 0.001, "forks stay where they were")
	check(sim.forklift_of(P) == null)
	check(sim.execute(P, "pick_up", {"item": laptop.id}).ok, "on foot again")


func test_forklift_comes_from_the_shop_and_uses_power() -> void:
	var sim := fresh_sim(302)
	var s := sim.state
	eq(Content.shop_items.get("forklift", {}).get("item_kind", ""), Item.KIND_FORKLIFT, "in the Shop")
	sim.deliver_parcel({"items": [Item.KIND_FORKLIFT], "parts": {}, "consumables": {}}, Vector3(0, 0, 3))
	var fls := s.items.values().filter(func(i): return i.kind == Item.KIND_FORKLIFT)
	eq(fls.size(), 1, "delivered without a box")
	check(fls[0].loc.get("relocate", false), "left in goods-in for the level to find it room")
	check(Sim.power_breakdown(s).has("Electric forklift"), "on charge")
	var idle := float(Sim.power_breakdown(s)["Electric forklift"].kw)
	s.set_loc(fls[0], {"t": "loose", "p": [0, 0, 3], "r": [0, 0, 0, 1]})
	sim.execute(P, "forklift_enter", {"item": fls[0].id})
	check(float(Sim.power_breakdown(s)["Electric forklift"].kw) > idle * 3.0, "draws more while driving")


func test_forklift_lifts_carries_and_sets_down_on_the_floor() -> void:
	var sim := fresh_sim(303)
	var s := sim.state
	var fl := _forklift(s, Vector3(0, 0, 0))
	var pallet := _pallet_with_units(sim, Vector3(0, 0, 3), 4)
	check(not sim.execute(P, "fork_lift", {"pallet": pallet.id, "at": [0, 0, 3]}).ok, "not without getting in")
	sim.execute(P, "forklift_enter", {"item": fl.id})
	check(not sim.execute(P, "fork_lift", {"pallet": pallet.id, "at": [4, 0, 3]}).ok, "forks have to be under it")
	check(sim.execute(P, "fork_lift", {"pallet": pallet.id, "at": [0.1, 0.0, 3.05], "yaw": 88.0, "h": 0.05}).ok, "lift it")
	eq(pallet.loc.t, "on_forks")
	eq(int(pallet.loc.fork), fl.id)
	eq(float(pallet.loc.yaw), 90.0, "turn snapped to 90°")
	var other := _pallet_with_units(sim, Vector3(3, 0, 3), 1)
	check(not sim.execute(P, "fork_lift", {"pallet": other.id, "at": [3, 0, 3]}).ok, "one pallet at a time")
	eq(Logistics.unit_location(s, Logistics.units_on(s, pallet.id)[0]), "On the forklift")
	check(sim.execute(P, "fork_set", {"p": [6, 0, 6], "r": [0, 0, 0, 1], "h": 0.0}).ok, "set it down")
	eq(pallet.loc.t, "loose")
	eq(pallet.loc.p, [6.0, 0.0, 6.0])
	eq(Logistics.units_on(s, pallet.id).size(), 4, "the stock came along")
	check(s.lifetime("pallets_forked") >= 1, "counted")


# ---------------------------------------------------------------- racking

func test_racking_bays_and_levels() -> void:
	var sim := fresh_sim(304)
	var s := sim.state
	_fixture(s, "rack_1", "pallet_rack", Vector3(0, 0, 0))
	eq(Logistics.slot_names("pallet_rack").size(), 9, "3 bays × 3 levels")
	eq(Logistics.slot_names("pallet_rack_low").size(), 4, "2 × 2")
	eq(s.fixtures.rack_1.label, "Racking R1")
	var top = Logistics.slot_world(s, "rack_1:b1l2")
	check(top != null, "bays have a place in the world")
	near((top as Transform3D).origin.y, 2.9, 0.001, "top beam")
	near((top as Transform3D).origin.x, 0.0, 0.001, "middle bay is in the middle")
	var floor_left = Logistics.slot_world(s, "rack_1:b0l0")
	near((floor_left as Transform3D).origin.x, -1.0, 0.001, "left bay")
	# Turned racking turns its bays.
	s.fixtures.rack_1.r = 90.0
	var turned = Logistics.slot_world(s, "rack_1:b0l0")
	near((turned as Transform3D).origin.z, 1.0, 0.001, "rotates with the racking")
	s.fixtures.rack_1.r = 0.0
	eq(Logistics.slot_label(s, "rack_1:b1l1"), "Racking R1 · level 2 · bay 2")
	# Lowering a pallet that's lined up with a free bay finds the bay; one over a beam but off-line is stopped.
	var f := Logistics.find_set_down(s, Vector3(0.1, 1.6, 0.05))
	eq(f.get("slot", ""), "rack_1:b1l1", "drops onto the level-2 beam it's lined up with")
	var g := Logistics.find_set_down(s, Vector3(0.5, 1.6, 0.0))
	check(g.has("blocked") and not g.has("slot"), "off-line over a beam: rests on it, can't go through (%s)" % str(g))
	var h := Logistics.find_set_down(s, Vector3(0.0, 0.3, 4.0))
	check(h.is_empty(), "nowhere near the racking: the floor")


func test_forklift_racks_a_pallet_on_the_top_level() -> void:
	var sim := fresh_sim(305)
	var s := sim.state
	_fixture(s, "rack_1", "pallet_rack", Vector3(0, 0, 0))
	var fl := _forklift(s, Vector3(0, 0, 5))
	var pallet := _pallet_with_units(sim, Vector3(0, 0, 3), 6)
	sim.execute(P, "forklift_enter", {"item": fl.id})
	check(sim.execute(P, "fork_lift", {"pallet": pallet.id, "at": [0, 0, 3], "yaw": 90.0}).ok)
	var top: Transform3D = Logistics.slot_world(s, "rack_1:b2l2")
	check(not sim.execute(P, "fork_set", {"slot": "rack_1:b2l2", "p": [5, 2.9, 5], "h": 2.9}).ok, "has to be lined up with it")
	check(sim.execute(P, "fork_set", {"slot": "rack_1:b2l2", "p": _v(top.origin), "h": 2.9}).ok, "into the top bay")
	eq(pallet.loc.get("slot", ""), "rack_1:b2l2")
	var unit: Item = Logistics.units_on(s, pallet.id)[0]
	eq(Logistics.unit_location(s, unit), "Racking R1 · level 3 · bay 3", "the Stock app knows where it is")
	check(s.units_in_stock().has(unit), "still counted as stock")
	sim.execute(P, "forklift_exit", {"p": [0, 0, 5], "h": 2.9})
	check(not sim.execute(P, "pick_up", {"item": unit.id}).ok, "out of reach on foot")
	check(s.lifetime("pallets_racked_high") >= 1, "counted")
	# And back down again: lift from the bay, lower to the floor.
	sim.execute(P, "forklift_enter", {"item": fl.id})
	var other := _pallet_with_units(sim, Vector3(-3, 0, 3), 1)
	check(sim.execute(P, "fork_lift", {"pallet": other.id, "at": [-3, 0, 3]}).ok)
	check(not sim.execute(P, "fork_set", {"slot": "rack_1:b2l2", "p": _v(top.origin), "h": 2.9}).ok, "that bay's taken")
	sim.execute(P, "fork_set", {"p": [-3, 0, 3], "h": 0.0})
	check(sim.execute(P, "fork_lift", {"pallet": pallet.id, "at": _v(top.origin), "h": 2.9}).ok, "lift it out of the racking")
	sim.execute(P, "fork_set", {"p": [0, 0, 3], "h": 0.0})
	sim.execute(P, "forklift_exit", {"p": [0, 0, 5]})
	check(sim.execute(P, "pick_up", {"item": unit.id}).ok, "back within reach")


func test_pallet_truck_only_reaches_the_floor_level() -> void:
	var sim := fresh_sim(306)
	var s := sim.state
	_fixture(s, "rack_1", "pallet_rack_low", Vector3(0, 0, 0))
	var truck: Item = s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET_TRUCK)[0]
	var pallet := _pallet_with_units(sim, Vector3(0, 0, 3), 2)
	sim.execute(P, "take_truck", {"item": truck.id})
	sim.execute(P, "truck_lift", {"pallet": pallet.id})
	var up: Transform3D = Logistics.slot_world(s, "rack_1:b0l1")
	var down: Transform3D = Logistics.slot_world(s, "rack_1:b0l0")
	check(not sim.execute(P, "truck_lower", {"slot": "rack_1:b0l1", "p": _v(up.origin)}).ok, "can't reach level 2")
	check(sim.execute(P, "truck_lower", {"slot": "rack_1:b0l0", "p": _v(down.origin)}).ok, "floor level is fine")
	eq(pallet.loc.get("slot", ""), "rack_1:b0l0")
	eq(Logistics.unit_location(s, Logistics.units_on(s, pallet.id)[0]), "Racking R1 · level 1 · bay 1")
	check(sim.execute(P, "truck_lift", {"pallet": pallet.id}).ok, "and back out")
	sim.execute(P, "truck_lower", {"p": [0, 0, 3]})
	# A pallet the forklift put up high is out of the truck's reach.
	sim.execute(P, "release_truck", {"p": [3, 0, 3]})
	var fl := _forklift(s, Vector3(0, 0, 5))
	sim.execute(P, "forklift_enter", {"item": fl.id})
	sim.execute(P, "fork_lift", {"pallet": pallet.id, "at": [0, 0, 3]})
	check(sim.execute(P, "fork_set", {"slot": "rack_1:b0l1", "p": _v(up.origin), "h": 1.3}).ok)
	sim.execute(P, "forklift_exit", {"p": [0, 0, 5]})
	sim.execute(P, "take_truck", {"item": truck.id})
	check(not sim.execute(P, "truck_lift", {"pallet": pallet.id}).ok, "needs a forklift up there")


# ---------------------------------------------------------------- lorry → pallet bay → arm → goods-in line

func test_lorry_pallet_to_the_line_end_to_end() -> void:
	var sim := fresh_sim(307)
	var s := sim.state
	# A delivered lot (trimmed to one pallet of laptops so the belts can hold it all).
	var lot: Dictionary = s.lots.values().filter(func(l): return l.status == "open")[0]
	s.cash = 100000
	check(sim.execute(P, "buy_lot", {"lot": lot.id}).ok, "buy a lot")
	var units: Array = []
	for i in 6:
		units.append(sim.lots.roll_unit("kestrel_swift_14", 0.5).to_dict())
	s.lots[lot.id]["units"] = units
	s.lots[lot.id]["count"] = 6
	var pallets := sim.deliver_lot(lot.id, Vector3(0, 0, 6))
	check(pallets.size() >= 1, "lorry dropped a pallet")
	var pallet: Item = pallets[0]
	var n := Logistics.units_on(s, pallet.id).size()
	check(n == 6, "six laptops on it (%d)" % n)
	# Goods-in: an unload bay, a robot arm beside it and the start of a conveyor line.
	_fixture(s, "bay_1", "pallet_bay", Vector3(0, 0, 0))
	eq(s.fixtures.bay_1.label, "Unload bay 1")
	s.fixtures["arm_1"] = {"type": "robot_arm", "label": "Arm", "placed": true, "p": [1.2, 0, 0], "r": 0.0}
	for i in 4:
		s.fixtures["c%d" % i] = {"type": "conveyor", "label": "c%d" % i, "placed": true, "p": [2.4, 0, 1.0 + 2.0 * i], "r": 0.0}
	_run(sim, 6.0)
	eq(Logistics.units_on(s, pallet.id).size(), 6, "the arm leaves a pallet that's still on the drive alone")
	# Forklift it from the drive into the bay.
	var fl := _forklift(s, Vector3(0, 0, 9))
	check(sim.execute(P, "forklift_enter", {"item": fl.id}).ok)
	check(sim.execute(P, "fork_lift", {"pallet": pallet.id, "at": [0, 0, 6], "yaw": 0.0}).ok, "lift it off the drive")
	var bay: Transform3D = Logistics.slot_world(s, "bay_1:b0l0")
	check(sim.execute(P, "fork_set", {"slot": "bay_1:b0l0", "p": _v(bay.origin), "h": 0.0}).ok, "set it down in the unload bay")
	sim.execute(P, "forklift_exit", {"p": [0, 0, 9]})
	# The arm takes over: every laptop goes from the pallet onto the goods-in belt.
	_run(sim, 60.0)
	eq(Logistics.units_on(s, pallet.id).size(), 0, "pallet emptied")
	var on_belts := s.items.values().filter(func(i): return i.kind == Item.KIND_UNIT and i.loc.get("t") == "belt").size()
	eq(on_belts, 6, "all six on the conveyor")
	check(s.lifetime("unloaded") >= 6, "counted as unloaded (%d)" % s.lifetime("unloaded"))
	check(s.lifetime("pallets_to_line") >= 1, "pallet to the line counted")


func test_pallets_in_racking_are_left_alone_by_arms() -> void:
	var sim := fresh_sim(308)
	var s := sim.state
	_fixture(s, "rack_1", "pallet_rack_low", Vector3(0, 0, 0))
	var pallet := _pallet_with_units(sim, Vector3(0, 0, 3), 3)
	s.set_loc(pallet, {"t": "slot", "slot": "rack_1:b1l0"})
	s.fixtures["arm_1"] = {"type": "robot_arm", "label": "Arm", "placed": true, "p": [0.5, 0, 1.4], "r": 0.0}
	s.fixtures["c0"] = {"type": "conveyor", "label": "c0", "placed": true, "p": [1.9, 0, 2.4], "r": 0.0}
	_run(sim, 10.0)
	eq(Logistics.units_on(s, pallet.id).size(), 3, "stored stock stays stored")


# ---------------------------------------------------------------- saves, moves, co-op

func test_save_load_and_replicate() -> void:
	var sim := fresh_sim(309)
	var s := sim.state
	_fixture(s, "rack_1", "pallet_rack", Vector3(0, 0, 0))
	var fl := _forklift(s, Vector3(0, 0, 5))
	var racked := _pallet_with_units(sim, Vector3(0, 0, 3), 2)
	s.set_loc(racked, {"t": "slot", "slot": "rack_1:b0l1"})
	var carried := _pallet_with_units(sim, Vector3(2, 0, 3), 2)
	sim.execute(P, "forklift_enter", {"item": fl.id})
	sim.execute(P, "fork_lift", {"pallet": carried.id, "at": [2, 0, 3], "h": 0.0})
	# The level keeps the driven forklift's last pose in its loc, so a save has it.
	fl.loc["p"] = [4.0, 0.0, 4.0]
	var d := s.to_dict()
	var s2 := GameState.new()
	s2.load_dict(JSON.parse_string(JSON.stringify(d)))
	var sim2 := Sim.new(s2)
	sim2.migrate_save()
	var fl2 := s2.get_item(fl.id)
	eq(fl2.loc.t, "loose", "nobody's in the seat after loading")
	eq(fl2.loc.p, [4.0, 0.0, 4.0], "parked where it was last seen")
	eq(s2.get_item(carried.id).loc.t, "on_forks", "the load is still on the forks")
	eq(s2.get_item(racked.id).loc.slot, "rack_1:b0l1", "racked pallet stays racked")
	eq(Logistics.units_on(s2, racked.id).size(), 2)
	# A co-op guest sees the same thing (entity replication).
	var guest := GameState.new()
	guest.load_dict(s.snapshot())
	eq(guest.get_item(fl.id).loc.t, "driven")
	guest.import_entity("item", carried.id, s.export_entity("item", carried.id))
	eq(guest.get_item(carried.id).loc.fork, fl.id)


func test_moving_premises_brings_the_forklift() -> void:
	var sim := fresh_sim(310)
	var s := sim.state
	s.cash = 1000000
	var fl := _forklift(s, Vector3(0, 0, 3))
	var pallet := _pallet_with_units(sim, Vector3(0, 0, 5), 1)
	sim.execute(P, "forklift_enter", {"item": fl.id})
	sim.execute(P, "fork_lift", {"pallet": pallet.id, "at": [0, 0, 5]})
	check(sim.execute(P, "move_building", {"building": "lockup"}).ok)
	eq(fl.loc.t, "loose")
	check(fl.loc.get("relocate", false), "dropped in the new goods-in")
	eq(pallet.loc.t, "on_forks", "load stays on the forks")
	check(sim.forklift_of(P) == null, "driver hopped out")


func test_disconnect_parks_the_forklift() -> void:
	var sim := fresh_sim(311)
	var s := sim.state
	var fl := _forklift(s, Vector3(0, 0, 3))
	sim.execute(P2, "forklift_enter", {"item": fl.id})
	sim.execute(P2, "drop_all_held", {"p": [1, 1, 1], "fork_p": [7, 0, 7], "fork_r": [0, 0, 0, 1], "fork_h": 1.1})
	eq(fl.loc.t, "loose")
	eq(fl.loc.p, [7.0, 0.0, 7.0])
	near(float(fl.data.h), 1.1, 0.001)
