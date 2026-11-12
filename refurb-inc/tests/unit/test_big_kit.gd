extends TestCase
## Desktops and servers: size classes, heavy-duty racking, carrying, packing big boxes, postage, pallets, specs.

const P := 1


func _setup(sim: Sim) -> void:
	var s := sim.state
	s.register_station("bench_1", "bench")
	s.register_slot("bench_1:unit", ["unit", "unit_large"], "bench_1")
	s.register_station("pack_1", "pack")
	s.register_slot("pack_1:unit", ["unit", "unit_large", "package", "package_large"], "pack_1")
	s.register_station("courier_1", "courier")
	s.register_slot("courier_1:0", ["package_large", "package"], "courier_1")
	s.register_slot("courier_1:2", ["package"], "courier_1")
	s.register_slot("shelf_a:0", ["unit"], "")
	s.register_slot("shelf_a:1", ["unit"], "")
	s.register_slot("rack_1:0", ["unit_large", "unit"], "")


func _spawn(sim: Sim, model_id: String, slot: String) -> Item:
	var it := sim.state.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit(model_id, 0.6)
	sim.state.set_loc(it, {"t": "slot", "slot": slot})
	return it


func test_every_model_has_a_type_and_visual() -> void:
	for m: Dictionary in Content.models.values():
		check(m.get("type", "") in Content.HW_TYPES, "%s type" % m.id)
		check(ResourceLoader.exists(m.get("visual", {}).get("model", "")), "%s visual.model" % m.id)
	var servers := Content.models.values().filter(func(m): return m.type == "server")
	var desktops := Content.models.values().filter(func(m): return m.type == "desktop")
	check(servers.size() >= 5, "5 server models")
	check(desktops.size() >= 3, "3 desktop models")


func test_big_kit_needs_heavy_rack() -> void:
	var sim := fresh_sim(31)
	_setup(sim)
	var srv := _spawn(sim, "corvex_rampart_2200", "bench_1:unit")
	check(srv.is_large() and srv.slot_kind() == "unit_large")
	eq(sim.execute(P, "pick_up", {"item": srv.id}).ok, true)
	var r := sim.execute(P, "place", {"slot": "shelf_a:0"})
	check(not r.ok and "heavy-duty" in r.msg, "standard shelf refuses a server: %s" % r.msg)
	check(sim.execute(P, "place", {"slot": "rack_1:0"}).ok, "heavy rack takes it")
	# Small desktops still fit normal shelving; laptops fit the heavy rack too.
	var mini := _spawn(sim, "corvex_fulcrum_5_micro", "bench_1:unit")
	check(not mini.is_large())
	sim.execute(P, "pick_up", {"item": mini.id})
	check(sim.execute(P, "place", {"slot": "shelf_a:0"}).ok, "mini PC on a standard shelf")


func test_servers_are_carried_one_at_a_time() -> void:
	var sim := fresh_sim(32)
	_setup(sim)
	var a := _spawn(sim, "corvex_rampart_1100", "rack_1:0")
	var b := _spawn(sim, "corvex_rampart_1100", "bench_1:unit")
	check(sim.execute(P, "pick_up", {"item": a.id}).ok)
	var r := sim.execute(P, "pick_up", {"item": b.id})
	check(not r.ok, "second server refused while carrying one")
	# Laptops still stack.
	sim.execute(P, "place", {"slot": "rack_1:0"})
	var l1 := _spawn(sim, "kestrel_swift_14", "shelf_a:0")
	var l2 := _spawn(sim, "kestrel_swift_14", "shelf_a:1")
	check(sim.execute(P, "pick_up", {"item": l1.id}).ok and sim.execute(P, "pick_up", {"item": l2.id}).ok, "two laptops stack")


func test_server_spec_test_time_and_faults() -> void:
	var sim := fresh_sim(33)
	var u := sim.lots.roll_unit("corvex_rampart_2200", 0.3)
	var line := u.spec_line()
	check("2× " in line and "8× " in line and "SAS" in line, line)
	eq(u.battery_pct, 100, "no battery on servers")
	for f in u.faults:
		check(Content.applies_to(Content.fault(f.id), u.model()), "fault %s applies to servers" % f.id)
		check(Content.parts.has(Valuation.repair_part_for(u, f)), "part for %s" % f.id)
	for c in u.cosmetic:
		check(Content.applies_to(Content.condition(c), u.model()), "condition %s applies to servers" % c)
	# Every fault that can hit each model resolves to a real part.
	for m: Dictionary in Content.models.values():
		var x := sim.lots.roll_unit(m.id, 0.5)
		for f: Dictionary in Content.faults.values():
			if not Content.applies_to(f, m):
				continue
			if f.get("requires_ram_slots", false) and int(m.get("ram_slots", 0)) == 0:
				continue
			check(Content.parts.has(Valuation.repair_part_for(x, {"id": f.id})), "%s / %s" % [m.id, f.id])
	# Servers take longer on the bench.
	var s := sim.state
	_setup(sim)
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = u
	s.set_loc(it, {"t": "slot", "slot": "bench_1:unit"})
	var usb := s.new_item(Item.KIND_USB_TEST)
	s.register_slot("bench_1:usb", ["usb_test", "usb_image"], "bench_1")
	s.set_loc(usb, {"t": "slot", "slot": "bench_1:usb"})
	sim.try_start_bench("bench_1", 0)
	check(float(s.stations.bench_1.job.get("dur", 0)) > float(Content.econ("test_minutes", 30)) * 1.5, "server test takes longer")


func test_pack_big_box_floor_bay_and_postage() -> void:
	var sim := fresh_sim(34)
	_setup(sim)
	var s := sim.state
	var it := _spawn(sim, "corvex_fulcrum_7_tower", "pack_1:unit")
	it.unit.tested = true
	it.unit.imaged = true
	it.unit.grade = "B"
	it.unit.list_price = 200
	it.unit.listed = true
	var order := sim._create_order(it, "flogit")
	check(not order.is_empty(), "order created")
	var big0 := int(s.consumables.get("big_boxes", 0))
	var small0 := int(s.consumables.get("boxes", 0))
	check(big0 > 0, "start with some large boxes")
	check(sim.execute(P, "pack", {"station": "pack_1"}).ok, "pack a tower")
	eq(int(s.consumables.big_boxes), big0 - 1, "uses a large box")
	eq(int(s.consumables.boxes), small0, "not a laptop box")
	var pkg := s.item_in_slot("pack_1:unit")
	check(pkg.is_large() and pkg.slot_kind() == "package_large" and pkg.weight() == 3)
	sim.execute(P, "pick_up", {"item": pkg.id})
	check(not sim.execute(P, "place", {"slot": "courier_1:2"}).ok, "big box won't fit a small bay")
	check(sim.execute(P, "place", {"slot": "courier_1:0"}).ok, "floor bay takes it")
	var postage0 := int(s.ledger_total("postage")) if s.has_method("ledger_total") else 0
	var cash0 := s.cash
	sim.courier_collect()
	var expected: int = int(order.price) - int(round(int(order.price) * float(Content.econ("flogit_fee_pct", 0.1)))) - int(Content.econ("flogit_postage_large", 26))
	eq(s.cash - cash0, expected, "large postage charged")
	check(postage0 >= 0)


func test_pallets_follow_unit_size() -> void:
	var lay := Sim.pallet_layout("corvex_rampart_2200")
	eq(lay.grid, [2, 1], "two 2U servers per layer")
	check(int(lay.capacity) <= 20)
	var lap := Sim.pallet_layout("kestrel_swift_14")
	eq(lap.grid, [3, 2], "laptops 3 × 2 as before")
	# A mixed lot splits by footprint onto separate pallets.
	var sim := fresh_sim(35)
	var s := sim.state
	var lot := {"id": s.make_id(), "seller_name": "Test", "count": 6, "total_price": 600, "status": "bought", "units": []}
	for i in 3:
		lot.units.append(sim.lots.roll_unit("corvex_rampart_2200", 0.5).to_dict())
		lot.units.append(sim.lots.roll_unit("kestrel_swift_14", 0.5).to_dict())
	s.lots[lot.id] = lot
	var pallets := sim.deliver_lot(lot.id)
	eq(pallets.size(), 2, "servers and laptops on separate pallets")
	for p: Item in pallets:
		var models := {}
		for it: Item in s.items.values():
			if it.kind == Item.KIND_UNIT and str(it.loc.get("slot", "")).begins_with("i%d:" % p.id):
				models[it.unit.model_id] = true
		eq(models.size(), 1, "one size of kit per pallet")
