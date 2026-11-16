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
