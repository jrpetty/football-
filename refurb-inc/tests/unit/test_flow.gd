extends TestCase
## End-to-end loop with no scene tree: buy → deliver → test → repair → image → grade → list → sell → pack → ship.

const P := 1


func _setup_garage(sim: Sim) -> void:
	var s := sim.state
	s.register_station("bench_1", "bench")
	s.register_slot("bench_1:unit", ["unit"], "bench_1")
	s.register_slot("bench_1:usb", ["usb_test", "usb_image"], "bench_1")
	s.register_station("repair_1", "repair")
	s.register_slot("repair_1:unit", ["unit"], "repair_1")
	s.register_station("grade_1", "grade")
	s.register_slot("grade_1:unit", ["unit"], "grade_1")
	s.register_station("pack_1", "pack")
	s.register_slot("pack_1:unit", ["unit", "package"], "pack_1")
	s.register_station("courier_1", "courier")
	s.register_slot("courier_1:0", ["package"], "courier_1")
	s.register_slot("shelf_1:0", ["unit"], "")
	s.register_slot("desk:usb_a", ["usb_test", "usb_image"], "")
	s.register_slot("desk:usb_b", ["usb_test", "usb_image"], "")
	var t := s.new_item(Item.KIND_USB_TEST)
	s.set_loc(t, {"t": "slot", "slot": "desk:usb_a"})
	var im := s.new_item(Item.KIND_USB_IMAGE)
	s.set_loc(im, {"t": "slot", "slot": "desk:usb_b"})


func _cmd(sim: Sim, cmd: String, args: Dictionary = {}) -> Dictionary:
	var r := sim.execute(P, cmd, args)
	check(r.ok, "%s failed: %s" % [cmd, r.msg])
	return r


func _first_lot(sim: Sim) -> Dictionary:
	for l in sim.state.lots.values():
		if l.status == "open":
			return l
	return {}


func test_full_loop() -> void:
	var sim := fresh_sim(2024)
	var s := sim.state
	_setup_garage(sim)
	var cash0 := s.cash
	var lot := _first_lot(sim)
	_cmd(sim, "buy_lot", {"lot": lot.id})
	check(s.cash < cash0, "cash should drop")
	check(s.schedule.any(func(e): return e.type == "lorry"), "lorry scheduled")

	# Skip to the delivery.
	sim.end_day()
	sim.start_next_day()
	sim.advance_minutes(90)
	var pallets := s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET)
	eq(pallets.size(), 1 if lot.count <= 20 else 2, "pallets delivered")
	var units := s.items.values().filter(func(i): return i.kind == Item.KIND_UNIT)
	eq(units.size(), int(lot.count))

	# Pick a unit with a Level 1 fault if we can (to exercise repair); otherwise any.
	var target: Item = units[0]
	for u: Item in units:
		if u.unit.faults.size() == 1 and int(Content.fault(u.unit.faults[0].id).repair_level) == 1:
			target = u
			break
	_cmd(sim, "pick_up", {"item": target.id})
	eq(target.loc.t, "held")
	_cmd(sim, "place", {"slot": "bench_1:unit"})
	var usb_t := s.item_in_slot("desk:usb_a")
	_cmd(sim, "pick_up", {"item": usb_t.id})
	_cmd(sim, "place", {"slot": "bench_1:usb"})
	check(not s.stations.bench_1.job.is_empty(), "test should auto-start")
	check(not target.unit.tested)
	sim.advance_minutes(35)
	check(target.unit.tested, "test complete after 30 min")
	check(target.unit.test_report.has("faults"))

	if target.unit.has_open_faults():
		_cmd(sim, "pick_up", {"item": target.id})
		_cmd(sim, "place", {"slot": "repair_1:unit"})
		# No part → repair refused.
		var pv := sim.repair_preview(P, target)
		if not pv.can:
			for f in target.unit.open_faults():
				var pid := Valuation.repair_part_for(target.unit, f)
				s.parts[pid] = 2
		var guard := 0
		while target.unit.has_open_faults() and guard < 5:
			guard += 1
			var r := sim.execute(P, "repair", {"station": "repair_1"})
			if not r.ok:
				break
		check(not target.unit.has_open_faults(), "repaired: %s" % str(target.unit.open_faults()))
		_cmd(sim, "pick_up", {"item": target.id})
		_cmd(sim, "place", {"slot": "bench_1:unit"})
	else:
		_cmd(sim, "pick_up", {"item": target.id})
		_cmd(sim, "place", {"slot": "bench_1:unit"})

	# Swap USB to imaging.
	_cmd(sim, "pick_up", {"item": usb_t.id})
	_cmd(sim, "place", {"slot": "desk:usb_a"})
	var usb_i := s.item_in_slot("desk:usb_b")
	_cmd(sim, "pick_up", {"item": usb_i.id})
	_cmd(sim, "place", {"slot": "bench_1:usb"})
	check(not s.stations.bench_1.job.is_empty(), "imaging should auto-start")
	sim.advance_minutes(50)
	check(target.unit.imaged, "imaged")

	_cmd(sim, "pick_up", {"item": target.id})
	_cmd(sim, "place", {"slot": "grade_1:unit"})
	_cmd(sim, "grade", {"station": "grade_1"})
	check(target.unit.grade in ["A", "B", "C", "D"], "graded: " + target.unit.grade)
	check(target.unit.list_price > 0)

	_cmd(sim, "pick_up", {"item": target.id})
	_cmd(sim, "place", {"slot": "shelf_1:0"})
	_cmd(sim, "set_price", {"item": target.id, "price": int(target.unit.list_price * 0.7)})
	_cmd(sim, "list", {"item": target.id, "listed": true})
	var guard2 := 0
	while target.unit.order_id == 0 and guard2 < 40:
		guard2 += 1
		sim.flogit_hourly()
	check(target.unit.order_id != 0, "a cheap listing should sell")

	_cmd(sim, "pick_up", {"item": target.id})
	_cmd(sim, "place", {"slot": "pack_1:unit"})
	var cash_before_ship := s.cash
	_cmd(sim, "pack", {"station": "pack_1"})
	var pkg := s.item_in_slot("pack_1:unit")
	check(pkg != null and pkg.kind == Item.KIND_PACKAGE, "package created")
	_cmd(sim, "pick_up", {"item": pkg.id})
	_cmd(sim, "place", {"slot": "courier_1:0"})
	sim.courier_collect()
	check(s.cash > cash_before_ship, "sale credited on dispatch")
	check(not s.items.has(target.id), "unit shipped and removed")
	var order: Dictionary = s.orders.values()[0]
	check(order.status in ["shipped", "shipped_late"])


func test_cannot_pack_unsold_or_image_untested() -> void:
	var sim := fresh_sim(1)
	var s := sim.state
	_setup_garage(sim)
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	s.set_loc(it, {"t": "slot", "slot": "shelf_1:0"})
	sim.execute(P, "pick_up", {"item": it.id})
	var r := sim.execute(P, "place", {"slot": "pack_1:unit"})
	check(not r.ok, "packing an unsold unit must be refused")
	_cmd(sim, "place", {"slot": "bench_1:unit"})
	var usb_i := s.item_in_slot("desk:usb_b")
	_cmd(sim, "pick_up", {"item": usb_i.id})
	_cmd(sim, "place", {"slot": "bench_1:usb"})
	check(s.stations.bench_1.job.is_empty(), "imaging untested unit must not start")


func test_carry_rules() -> void:
	var sim := fresh_sim(8)
	var s := sim.state
	var ids: Array = []
	for i in 5:
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
		s.set_loc(it, {"t": "loose", "p": [0, 0, 0], "r": [0, 0, 0, 1]})
		ids.append(it.id)
	for i in 3:
		_cmd(sim, "pick_up", {"item": ids[i]})
	var r := sim.execute(P, "pick_up", {"item": ids[3]})
	check(not r.ok, "capacity 3 at hauling level 0")
	eq(s.held_by(P).size(), 3)
	_cmd(sim, "drop", {"p": [1, 0, 1]})
	eq(s.held_by(P).size(), 2)


func test_rent_and_day_cycle() -> void:
	var sim := fresh_sim(4)
	var s := sim.state
	var days := 0
	var summaries: Array = []
	s.day_ended.connect(func(sum): summaries.append(sum))
	while days < 5:
		sim.advance_minutes(10000)
		check(not s.day_running)
		sim.start_next_day()
		days += 1
	eq(summaries.size(), 5)
	check(summaries[4].has("rent_paid"), "rent due on Friday")
	eq(s.weekday(), 0, "weekend skipped to Monday")
	var rent_entries := s.ledger.filter(func(e): return e.cat == "rent")
	eq(rent_entries.size(), 1)


func test_shop_parcel_and_unbox() -> void:
	var sim := fresh_sim(6)
	var s := sim.state
	_cmd(sim, "shop_buy", {"id": "ram_ddr4_8", "qty": 3})
	_cmd(sim, "shop_buy", {"id": "usb_test", "qty": 1})
	_cmd(sim, "shop_buy", {"id": "boxes_laptop", "qty": 1})
	eq(s.schedule.filter(func(e): return e.type == "parcel").size(), 1, "orders merge into one parcel per courier run")
	sim.advance_minutes(400)
	var parcels := s.items.values().filter(func(i): return i.kind == Item.KIND_PARCEL)
	eq(parcels.size(), 1)
	var boxes_before := int(s.consumables.boxes)
	_cmd(sim, "unbox", {"item": parcels[0].id})
	eq(int(s.parts.get("ram_ddr4_8", 0)), 3)
	eq(int(s.consumables.boxes), boxes_before + 10)
	eq(s.items.values().filter(func(i): return i.kind == Item.KIND_USB_TEST).size(), 1)


func test_late_orders_hurt_reputation() -> void:
	var sim := fresh_sim(10)
	var s := sim.state
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit("halden_folio_840", 0.9)
	it.unit.tested = true
	it.unit.imaged = true
	it.unit.faults.clear()
	it.unit.grade = "B"
	it.unit.list_price = 100
	s.set_loc(it, {"t": "loose", "p": [0, 0, 0], "r": [0, 0, 0, 1]})
	sim._create_order(it, "flogit")
	var rep := s.reputation
	for d in 7:
		sim.advance_minutes(10000)
		sim.start_next_day()
	check(s.reputation < rep, "late + cancelled should cost reputation")
	var o: Dictionary = s.orders.values()[0]
	eq(o.status, "cancelled")
	eq(it.unit.order_id, 0, "unit returns to stock after cancellation")
