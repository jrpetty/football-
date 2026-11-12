extends TestCase


func test_all_buildings_defined_with_scenes() -> void:
	eq(Content.building_order.size(), 7)
	var tiers: Array = []
	for id in Content.building_order:
		var b := Content.building(id)
		tiers.append(int(b.tier))
		check(ResourceLoader.exists(b.scene), "%s scene exists" % id)
	eq(tiers, [1, 2, 3, 4, 5, 6, 7])
	# Rent climbs with every rung
	var last := 0
	for id in Content.building_order:
		check(int(Content.building(id).rent_weekly) > last, "rent rises at %s" % id)
		last = int(Content.building(id).rent_weekly)


func test_move_charges_fee_and_unplaces_equipment() -> void:
	var sim := fresh_sim(5)
	var s := sim.state
	for id in s.fixtures:
		s.fixtures[id].placed = true
	s.cash = 500000
	var loose := s.new_item(Item.KIND_PARCEL)
	s.set_loc(loose, {"t": "loose", "p": [1, 1, 1], "r": [0, 0, 0, 1]})
	var r := sim.execute(1, "move_building", {"building": "warehouse"})
	check(r.ok, r.msg)
	eq(s.building_id, "warehouse")
	eq(s.cash, 500000 - int(Content.building("warehouse").move_fee))
	check(s.fixtures.values().all(func(f): return not f.placed), "equipment waits for the new layout")
	check(loose.loc.get("relocate", false), "loose stock flagged for relocation")
	check(not sim.execute(1, "move_building", {"building": "warehouse"}).ok, "can't move to where you are")


func test_cannot_afford_move() -> void:
	var sim := fresh_sim(6)
	sim.state.cash = 1000
	check(not sim.execute(1, "move_building", {"building": "megaplant"}).ok)
	eq(sim.state.building_id, "garage")


func test_rent_follows_building() -> void:
	var sim := fresh_sim(7)
	var s := sim.state
	s.cash = 1_000_000
	sim.execute(1, "move_building", {"building": "distribution"})
	var cash_before := s.cash
	for d in 5:
		sim.advance_minutes(10000)
		sim.start_next_day()
	var rent := s.ledger.filter(func(e): return e.cat == "rent")
	eq(rent.size(), 1)
	eq(int(rent[0].amt), -int(Content.building("distribution").rent_weekly))


func test_buy_place_store_fixture() -> void:
	var sim := fresh_sim(8)
	var s := sim.state
	var n := s.fixtures.size()
	var r := sim.execute(1, "buy_fixture", {"type": "metal_shelf"})
	check(r.ok)
	eq(s.fixtures.size(), n + 1)
	eq(s.fixtures[r.id].label, "Shelf C")
	sim.execute(1, "place_fixture", {"id": r.id, "p": [1, 0, 2], "r": 90})
	check(s.fixtures[r.id].placed)
	# Can't store equipment with stock on it
	s.register_slot(r.id + ":0", ["unit"], "")
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	s.set_loc(it, {"t": "slot", "slot": r.id + ":0"})
	check(not sim.execute(1, "store_fixture", {"id": r.id}).ok, "occupied shelf can't be stored")
	s.set_loc(it, {"t": "loose", "p": [0, 0, 0], "r": [0, 0, 0, 1]})
	check(sim.execute(1, "store_fixture", {"id": r.id}).ok)
	check(not s.fixtures[r.id].placed)
