extends TestCase
## Monitors, Linkwell switches + Wi-Fi APs, docks, tablets and phones: each kind goes bench → (wipe) → grade →
## Flogit like the laptops do, with the right steps for what it is (no wipe for kit without data, a secure
## wipe + reset for phones/tablets/network kit, battery health on mobile kit, activation locks as spares only).

const P := 1
const NEW_TYPES := ["monitor", "switch", "wifi_ap", "dock", "tablet", "phone"]


func _setup(sim: Sim) -> void:
	var s := sim.state
	s.register_station("bench_1", "bench")
	s.register_slot("bench_1:unit", ["unit", "unit_large"], "bench_1")
	s.register_slot("bench_1:usb", ["usb_test", "usb_image"], "bench_1")
	s.register_station("repair_1", "repair")
	s.register_slot("repair_1:unit", ["unit", "unit_large"], "repair_1")
	s.register_station("grade_1", "grade")
	s.register_slot("grade_1:unit", ["unit", "unit_large"], "grade_1")
	s.register_slot("desk:usb_a", ["usb_test", "usb_image"], "")
	s.register_slot("desk:usb_b", ["usb_test", "usb_image"], "")
	s.set_loc(s.new_item(Item.KIND_USB_TEST), {"t": "slot", "slot": "desk:usb_a"})
	s.set_loc(s.new_item(Item.KIND_USB_IMAGE), {"t": "slot", "slot": "desk:usb_b"})


func _models_of(t: String) -> Array:
	return Content.models.values().filter(func(m): return m.type == t)


func _run_job(sim: Sim) -> void:
	sim.try_start_bench("bench_1", P)
	var job: Dictionary = sim.state.stations.bench_1.get("job", {})
	check(not job.is_empty(), "bench job started: %s" % sim.state.stations.bench_1.get("msg", ""))
	if not job.is_empty():
		sim.advance_minutes(float(job.start) + float(job.dur) - sim.state.minute + 1.0)


func _swap_usb(sim: Sim, kind: String) -> void:
	var s := sim.state
	var cur := s.item_in_slot("bench_1:usb")
	if cur:
		s.set_loc(cur, {"t": "slot", "slot": "desk:usb_a" if s.item_in_slot("desk:usb_a") == null else "desk:usb_b"})
	for slot in ["desk:usb_a", "desk:usb_b"]:
		var u := s.item_in_slot(slot)
		if u and u.kind == kind:
			s.set_loc(u, {"t": "slot", "slot": "bench_1:usb"})
			return


func test_every_new_type_has_models_sellers_and_parts() -> void:
	var sim := fresh_sim(91)
	for t in NEW_TYPES:
		var ms := _models_of(t)
		check(not ms.is_empty(), "%s: at least one model" % t)
		for m: Dictionary in ms:
			check(ResourceLoader.exists(m.get("visual", {}).get("model", "")), "%s visual" % m.id)
			check(Content.sellers.values().any(func(sel): return sel.get("model_pool", []).any(func(e): return (e.get("model", "") if e is Dictionary else str(e)) == m.id)), "%s: someone sells it" % m.id)
			var u := sim.lots.roll_unit(m.id, 0.4)
			check(u.spec_line() != "", "%s spec line" % m.id)
			check(Valuation.market_value(u, "B") > 0, "%s has a value" % m.id)
			if not u.has_battery():
				eq(u.battery_pct, 100, "%s has no battery to wear" % m.id)
			for f in u.faults:
				check(Content.applies_to(Content.fault(f.id), m), "%s / %s applies" % [m.id, f.id])
				check(Content.parts.has(Valuation.repair_part_for(u, f)), "%s / %s has a part" % [m.id, f.id])


func test_each_type_flows_bench_to_flogit() -> void:
	for t in NEW_TYPES:
		var m: Dictionary = _models_of(t)[0]
		var sim := fresh_sim(92)
		var s := sim.state
		_setup(sim)
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = sim.lots.roll_unit(m.id, 0.95)
		it.unit.faults.clear()
		it.unit.battery_pct = maxi(it.unit.battery_pct, 90)
		var u := it.unit
		s.set_loc(it, {"t": "slot", "slot": "bench_1:unit"})
		_swap_usb(sim, Item.KIND_USB_TEST)
		_run_job(sim)
		check(u.tested, "%s tested" % m.id)
		eq(str(u.test_report.get("spec", "")), u.spec_line(), "%s report carries its spec" % m.id)
		eq(int(u.test_report.get("battery_pct", -1)) >= 0, u.has_battery(), "%s battery on report only if it has one" % m.id)
		if u.needs_wipe():
			eq(u.stage(), UnitData.STAGE_TO_IMAGE, "%s needs a wipe" % m.id)
			_swap_usb(sim, Item.KIND_USB_IMAGE)
			_run_job(sim)
			check(u.imaged, "%s wiped" % m.id)
			if not u.wipe_info().is_empty():
				var logged := u.history.any(func(e): return str(u.wipe_info().title) in str(e[1]))
				check(logged, "%s log says %s" % [m.id, u.wipe_info().title])
		else:
			check(t in ["monitor", "dock"], "only data-free kit skips the wipe")
			eq(u.stage(), UnitData.STAGE_TO_GRADE, "%s goes straight to grading" % m.id)
		s.set_loc(it, {"t": "slot", "slot": "grade_1:unit"})
		var r := sim.execute(P, "grade", {"station": "grade_1"})
		check(r.ok, "%s graded: %s" % [m.id, r.msg])
		check(u.grade != "" and u.list_price > 0, "%s priced (£%d)" % [m.id, u.list_price])


func test_wipe_steps_match_the_kit() -> void:
	var sim := fresh_sim(93)
	for t in ["tablet", "phone", "switch", "wifi_ap"]:
		var u := sim.lots.roll_unit(_models_of(t)[0].id, 0.8)
		check(not u.wipe_info().is_empty(), "%s gets a secure wipe + reset" % t)
		check(u.wipe_info().get("lines", []).size() >= 3, "%s wipe screen lines" % t)
		check(not Content.diag_stages(t).is_empty() and Content.diag_stages(t) != Content.diag_info.get("stages", []), "%s has its own diagnostics" % t)
	for t in ["laptop", "desktop", "server"]:
		var m: Dictionary = _models_of(t)[0]
		check(sim.lots.roll_unit(m.id, 0.8).wipe_info().is_empty(), "%s gets a PaneOS image" % t)


func test_activation_lock_is_spares_only() -> void:
	var sim := fresh_sim(94)
	var s := sim.state
	_setup(sim)
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit(_models_of("phone")[0].id, 0.9)
	it.unit.faults = [{"id": "account_locked", "fixed": false, "detail": {}}]
	it.unit.tested = true
	s.set_loc(it, {"t": "slot", "slot": "repair_1:unit"})
	s.player(Sim.player_key(P)).xp["repair"] = 999999
	var pv := sim.repair_preview(P, it)
	check(not pv.get("can", true), "no part or skill clears an activation lock")
	check(pv.get("unfixable", false), "flagged as unfixable")
	s.set_loc(it, {"t": "slot", "slot": "grade_1:unit"})
	check(sim.execute(P, "grade", {"station": "grade_1"}).ok, "can still be graded")
	eq(it.unit.grade, "S", "sold as spares/repairs")
	eq(UnitData.grade_label(it.unit.grade), "D (Spares/Repairs)")


func test_battery_caps_mobile_grades() -> void:
	var sim := fresh_sim(95)
	var u := sim.lots.roll_unit(_models_of("tablet")[0].id, 1.0)
	u.cosmetic.clear()
	u.faults.clear()
	u.tested = true
	u.imaged = true
	u.battery_pct = 70
	check(Valuation.grade_for(u) != "A", "a 70% battery tablet can't be A grade")


func test_lots_of_new_kit_deliver_on_pallets() -> void:
	var sim := fresh_sim(96)
	var s := sim.state
	for t in NEW_TYPES:
		var m: Dictionary = _models_of(t)[0]
		var lay := Sim.pallet_layout(m.id)
		check(int(lay.capacity) >= 1, "%s fits a pallet (%d)" % [m.id, int(lay.capacity)])
	var lot := {"id": s.make_id(), "seller_name": "Test", "count": 4, "total_price": 400, "status": "bought", "units": []}
	for t in ["monitor", "phone"]:
		for i in 2:
			lot.units.append(sim.lots.roll_unit(_models_of(t)[0].id, 0.5).to_dict())
	s.lots[lot.id] = lot
	var pallets := sim.deliver_lot(lot.id)
	eq(pallets.size(), 2, "monitors and phones on separate pallets")
