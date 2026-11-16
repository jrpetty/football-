extends TestCase
## Regression tests for issues found in the release playthrough and code audit (Nov 2026).

const P := 1
const SHOP := preload("res://ui/office_os/apps/shop_app.gd")


func test_every_repair_part_is_on_sale() -> void:
	# Every part any fault can need must appear in one of PartsHub's tabs, or that fault can never be fixed.
	var missing: PackedStringArray = []
	for m: Dictionary in Content.models.values():
		var u := UnitData.new()
		u.model_id = m.id
		u.ram_gb = int(m.get("ref_ram", 8))
		u.storage_gb = int(m.get("ref_storage", 256))
		for f: Dictionary in Content.faults.values():
			if not Content.applies_to(f, m) or (f.get("requires_ram_slots", false) and int(m.get("ram_slots", 0)) <= 0):
				continue
			var pid := Valuation.repair_part_for(u, {"id": f.id})
			if pid == "" or pid == "none" or not Content.parts.has(pid):
				continue
			if SHOP.part_tab(Content.part(pid)) == "" and not pid in missing:
				missing.append(pid)
	check(missing.is_empty(), "repair parts nobody can buy: %s" % ", ".join(missing))


func test_lorry_on_the_road_survives_save_and_load() -> void:
	var sim := fresh_sim(21)
	var s := sim.state
	sim.visual_vehicles = true
	var lot: Dictionary = s.lots.values().filter(func(l): return l.status == "open")[0]
	sim.c_buy_lot(P, {"lot": lot.id})
	var guard := 0
	while s.schedule.any(func(e): return e.type == "lorry") and guard < 40:
		if not s.day_running:
			sim.start_next_day()
		sim.advance_minutes(30)
		guard += 1
	eq(s.in_transit.size(), 1, "the lorry is on the road (nobody animated the drop)")
	var s2 := GameState.new()
	s2.load_dict(JSON.parse_string(JSON.stringify(s.to_dict())))
	var sim2 := Sim.new(s2)
	sim2.migrate_save()
	check(s2.schedule.any(func(e): return e.type == "lorry" and int(e.data.lot) == int(lot.id)), "the lorry sets off again after loading")
	sim2.deliver_lot(int(lot.id))
	eq(s2.in_transit.size(), 0, "delivered: nothing left on the road")


func test_pallets_never_land_inside_each_other() -> void:
	var sim := fresh_sim(22)
	var s := sim.state
	s.cash = 1000000
	var drops: Array = []
	for i in 3:
		var lot := sim.lots.create_lot(Content.sellers.values()[i])
		sim.c_buy_lot(P, {"lot": lot.id})
		drops.append_array(sim.deliver_lot(lot.id, Vector3(0, 0, 5)))
	for a in drops.size():
		for b in range(a + 1, drops.size()):
			var pa: Array = drops[a].loc.p
			var pb: Array = drops[b].loc.p
			check(Vector2(float(pa[0]) - float(pb[0]), float(pa[2]) - float(pb[2])).length() > 1.0, "pallets %d and %d overlap" % [a, b])


func test_overdrawn_you_can_still_buy_packing_supplies_and_move_for_free() -> void:
	var sim := fresh_sim(24)
	var s := sim.state
	s.cash = -1200
	var box_id := ""
	for sid in Content.shop_items:
		if Content.shop_items[sid].get("kind", "") == "consumable":
			box_id = sid
			break
	check(sim.execute(P, "shop_buy", {"id": box_id}).ok, "packing supplies allowed when overdrawn")
	check(not sim.execute(P, "shop_buy", {"id": "ram_ddr4_8"}).ok, "parts still need funds")
	s.building_id = "lockup"
	check(sim.execute(P, "move_building", {"building": "garage"}).ok, "moving back to the free garage is always allowed")


func test_cannot_store_the_only_office_pc() -> void:
	var sim := fresh_sim(25)
	var s := sim.state
	for id in s.fixtures:
		s.fixtures[id].placed = true
	var desk := ""
	for id in s.fixtures:
		if s.fixtures[id].type == "office_desk":
			desk = id
	check(desk != "", "start kit has an office PC")
	check(not sim.execute(P, "store_fixture", {"id": desk}).ok, "the last office PC can't be stored")


func test_worn_battery_can_be_wiped_and_sold_at_c() -> void:
	var sim := fresh_sim(26)
	var u := sim.lots.roll_unit("corvex_prospect_5420", 1.0)
	u.cosmetic = []
	u.faults = [{"id": "battery_worn", "detail": {"pct": 45}, "fixed": false}]
	u.battery_pct = 45
	u.tested = true
	check(not u.has_blocking_faults(), "a worn battery doesn't block imaging")
	u.imaged = true
	eq(Valuation.grade_for(u), "C", "sold with a worn battery: capped at C")
	u.faults[0].fixed = true
	u.battery_pct = 100
	eq(Valuation.grade_for(u), "A", "new battery: back to A")


func test_closed_lots_cannot_be_bought() -> void:
	var sim := fresh_sim(27)
	var s := sim.state
	var lot: Dictionary = s.lots.values().filter(func(l): return l.status == "open")[0]
	s.minute = float(lot.expires_min) + 10.0
	check(not sim.execute(P, "buy_lot", {"lot": lot.id}).ok, "bidding has closed")


func test_reputation_gains_taper_near_the_top() -> void:
	var s := GameState.new()
	s.reputation = 95.0
	s.add_reputation(1.0, true)
	check(s.reputation - 95.0 < 0.2, "an on-time order near the top adds little (%.2f)" % (s.reputation - 95.0))
	s.reputation = 10.0
	s.add_reputation(1.0, true)
	check(s.reputation - 10.0 > 0.85, "early on it's nearly the full point")


func test_company_decisions_are_the_hosts() -> void:
	var sim := fresh_sim(30)
	var s := sim.state
	s.cash = 5000000
	check(not sim.execute(7, "move_building", {"building": "lockup"}).ok, "a guest can't move the company")
	check(not sim.execute(7, "rename_company", {"name": "Griefers Ltd"}).ok, "a guest can't rename it")
	eq(s.building_id, "garage")
	check(sim.execute(1, "move_building", {"building": "lockup"}).ok, "the host can")


func test_old_orders_are_pruned_but_reviews_still_count() -> void:
	var sim := fresh_sim(31)
	var s := sim.state
	for i in 200:
		var oid := s.make_id()
		s.orders[oid] = {"id": oid, "status": "shipped", "shipped": float(i), "price": 100, "model": "x",
			"review": {"stars": 5 if i % 2 == 0 else 3, "at": float(i), "buyer": "b", "text": ""}}
	var before := Reviews.summary(s)
	s.minute = 30.0 * GameState.MIN_PER_DAY + 9 * 60
	sim._tidy_records()
	check(s.orders.size() <= 150, "old shipped orders pruned (%d left)" % s.orders.size())
	var after := Reviews.summary(s)
	eq(int(after.count), int(before.count), "review count unchanged")
	check(absf(float(after.avg) - float(before.avg)) < 0.001, "review average unchanged")


func test_gone_items_are_purged_at_day_end() -> void:
	var sim := fresh_sim(32)
	var s := sim.state
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	s.set_loc(it, {"t": "gone"})
	sim.end_day()
	check(not s.items.has(it.id), "recycled/gone items don't stay in the save")


func test_a_departed_guests_items_are_put_down_on_load() -> void:
	var sim := fresh_sim(33)
	var s := sim.state
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	s.set_loc(it, {"t": "held", "peer": 123456, "i": 0})
	var s2 := GameState.new()
	s2.load_dict(JSON.parse_string(JSON.stringify(s.to_dict())))
	Sim.new(s2).migrate_save()
	eq(String(s2.items[it.id].loc.t), "loose", "no longer held by a guest who isn't here")


func test_rng_state_survives_a_save() -> void:
	var s := GameState.new()
	s.rng.seed = 987654321987654321
	s.rng.randf()
	var want := s.rng.state
	var s2 := GameState.new()
	s2.load_dict(JSON.parse_string(JSON.stringify(s.to_dict())))
	eq(s2.rng.state, want, "64-bit RNG state round-trips through JSON")
