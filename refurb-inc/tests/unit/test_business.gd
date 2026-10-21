extends TestCase
## Business buyers (bulk orders + custom server builds), upgrade-to-sell and Flogit reviews — all through host
## commands with no scene tree, plus replication to a co-op guest and a save/load round trip.

const P := 1
const VIRT_SPEC := {"chassis": ["corvex_rampart_2200"], "cpus": 2, "cpu_ids": ["vortex_x9_6248", "vortex_x9_6338"],
	"cpu_label": "Vortex X9", "ram_gb": 256, "drives": 8, "drive_gb": 1200, "psus": 2, "raid": true}


# ================================================================= helpers

func _setup(sim: Sim) -> void:
	var s := sim.state
	s.register_station("repair_1", "repair")
	s.register_slot("repair_1:unit", ["unit", "unit_large"], "repair_1")
	s.register_station("build_1", "build")
	s.register_slot("build_1:unit", ["unit", "unit_large"], "build_1")
	s.register_station("grade_1", "grade")
	s.register_slot("grade_1:unit", ["unit", "unit_large"], "grade_1")
	s.register_station("pack_1", "pack")
	s.register_slot("pack_1:unit", ["unit", "unit_large", "package", "package_large"], "pack_1")
	s.register_station("courier_1", "courier")
	s.register_slot("courier_1:0", ["package", "package_large"], "courier_1")
	for i in 30:
		s.register_slot("shelf_1:%d" % i, ["unit", "unit_large"], "")


func _cmd(sim: Sim, cmd: String, args: Dictionary = {}) -> Dictionary:
	var r := sim.execute(P, cmd, args)
	check(r.ok, "%s failed: %s" % [cmd, r.msg])
	return r


func _free_shelf(s: GameState) -> String:
	for i in 30:
		if s.item_in_slot("shelf_1:%d" % i) == null:
			return "shelf_1:%d" % i
	return ""


func _unit(s: GameState, model_id: String, grade: String = "B", ram: int = -1, storage: int = -1, cpu: String = "") -> Item:
	## A tested, imaged unit on the shelf (graded if `grade` is given).
	var m := Content.model(model_id)
	var it := s.new_item(Item.KIND_UNIT)
	var u := UnitData.new()
	u.model_id = model_id
	u.serial = "TST%05d" % it.id
	u.cpu_id = cpu if cpu != "" else str(m.get("ref_cpu", ""))
	u.ram_gb = ram if ram >= 0 else int(m.get("ref_ram", 0))
	u.storage_gb = storage if storage >= 0 else int(m.get("ref_storage", 0))
	u.battery_pct = 95
	u.tested = true
	u.imaged = true
	u.grade = grade
	if grade != "":
		u.list_price = Valuation.market_value(u, grade)
	it.unit = u
	s.set_loc(it, {"t": "slot", "slot": _free_shelf(s)})
	return it


func _offer(sim: Sim, kind: String, extra: Dictionary) -> Dictionary:
	var s := sim.state
	var c := {"kind": kind, "buyer": "St Wilfrid's C of E School", "buyer_id": "st_wilfrids", "sector": "School", "status": "offer",
		"orders": [], "shipped": 0, "revenue": 0, "late": false, "offered": s.minute, "hours": 16,
		"expires": Business.add_working_minutes(s, s.minute, 360.0), "subject": "Test order", "body": "Hello"}
	c.merge(extra, true)
	if not c.has("due"):
		c["due"] = Business.add_working_minutes(s, s.minute, 16.0 * 60.0)
	c["id"] = s.make_id()
	s.contracts[c.id] = c
	s.mark("contract", c.id)
	return c


func _bulk(sim: Sim, qty: int, unit_price: int = 200, grade: String = "B", min_ram: int = 8) -> Dictionary:
	return _offer(sim, "bulk", {"type": "laptop", "qty": qty, "grade": grade, "min_ram": min_ram, "min_storage": 0,
		"unit_price": unit_price, "total": unit_price * qty})


func _build(sim: Sim, price: int = 2200) -> Dictionary:
	return _offer(sim, "build", {"name": "Virtualisation host", "spec": VIRT_SPEC.duplicate(true), "price": price,
		"parts_estimate": Business.build_parts_cost(VIRT_SPEC), "unit": 0})


func _pack_and_ship(sim: Sim, it: Item) -> void:
	var s := sim.state
	s.set_loc(it, {"t": "slot", "slot": "pack_1:unit"})
	_cmd(sim, "pack", {"station": "pack_1"})
	var pkg := s.item_in_slot("pack_1:unit")
	check(pkg != null and pkg.kind == Item.KIND_PACKAGE, "packed into a box")
	if pkg:
		s.set_loc(pkg, {"t": "slot", "slot": "courier_1:0"})
	sim.courier_collect()


func _advance_working(sim: Sim, mins: float) -> void:
	## Run the clock for `mins` working minutes, through evenings and weekends.
	var left := mins
	var guard := 0
	while left > 0.01 and guard < 50:
		guard += 1
		var s := sim.state
		if not s.day_running:
			sim.start_next_day()
		var room := s.day_end_minute(s.day_index()) - s.minute
		var step := minf(left, room)
		sim.advance_minutes(step)
		left -= step


# ================================================================= business buyers

func test_business_buyers_unlock_with_reputation() -> void:
	var sim := fresh_sim(501)
	var s := sim.state
	sim.business_hourly()
	check(s.contracts.is_empty(), "no enquiries at reputation %d" % int(s.reputation))
	s.reputation = Business.unlock_rep() + 5.0
	sim.business_hourly()
	check(bool(s.totals.get("biz_unlocked", false)), "unlocked once reputation is high enough")
	eq(s.contracts.size(), 1, "the first enquiry arrives straight away")
	var c: Dictionary = s.contracts.values()[0]
	eq(c.status, "offer")
	check(c.kind in ["bulk", "build"], "a known kind")
	check(float(c.due) > s.minute and float(c.expires) > s.minute and float(c.due) >= float(c.expires), "deadline after the reply-by time")
	check(str(c.subject) != "" and not "{" in str(c.body), "email text is filled in: %s" % c.body)
	check(Business.value(c) > 0, "it pays something")
	if c.kind == "bulk":
		check(int(c.qty) > 0 and int(c.unit_price) > 0, "bulk spec: %s" % str(c))
	# Plenty of hours at high reputation fill the inbox up to the cap, never beyond.
	s.reputation = 90.0
	for i in 60:
		s.minute += 60.0
		sim.business_hourly()
	var offers := s.contracts.values().filter(func(x): return x.status == "offer").size()
	check(offers >= 2 and offers <= int(Business.cfg("offers_max", 3)), "inbox capped: %d" % offers)
	var buyers := {}
	for x: Dictionary in s.contracts.values():
		check(not buyers.has(x.buyer_id), "one open enquiry per buyer")
		buyers[x.buyer_id] = true


func test_offers_can_be_declined_or_lapse() -> void:
	var sim := fresh_sim(502)
	var s := sim.state
	var a := _bulk(sim, 4)
	var b := _bulk(sim, 4)
	_cmd(sim, "biz_decline", {"contract": a.id})
	eq(s.contracts[a.id].status, "declined")
	check(not sim.execute(P, "biz_accept", {"contract": a.id}).ok, "can't accept a declined enquiry")
	_advance_working(sim, 7 * 60.0)
	eq(s.contracts[b.id].status, "expired", "an unanswered enquiry lapses")


func test_bulk_price_is_fair() -> void:
	var p := Business.bulk_unit_price("laptop", "B", 8, 0, 0.4)
	var lo := 99999
	var hi := 0
	for m: Dictionary in Content.models.values():
		if m.get("type", "") == "laptop":
			var g := Valuation.guide_price(m.id, "B")
			lo = mini(lo, g)
			hi = maxi(hi, g)
	check(p > lo and p < hi, "B-grade laptop price %d sits between the cheapest (%d) and dearest (%d) guide" % [p, lo, hi])
	check(Business.bulk_unit_price("laptop", "B", 64, 0, 0.4) == 0, "no laptop has 64GB: nothing fits")
	check(Business.bulk_unit_price("monitor", "C", 0, 0, 0.4) > 0, "monitors have a price too")


func test_bulk_order_allocated_packed_shipped_on_time() -> void:
	var sim := fresh_sim(503)
	var s := sim.state
	_setup(sim)
	var good: Array = []
	for i in 3:
		good.append(_unit(s, "corvex_prospect_5420", "B", 16, 256))
	var wrong_grade := _unit(s, "corvex_prospect_5420", "C", 16, 256)
	var too_little_ram := _unit(s, "halden_folio_650", "A", 4, 256)
	var a_grade := _unit(s, "corvex_prospect_5420", "A", 16, 256)
	var c := _bulk(sim, 3, 210)
	check(not Business.unit_fits(c, wrong_grade.unit), "C grade doesn't meet B or better")
	check(not Business.unit_fits(c, too_little_ram.unit), "4GB doesn't meet 8GB+")
	check(Business.unit_fits(c, a_grade.unit), "A grade is better than B")
	check(not sim.execute(P, "biz_allocate", {"contract": c.id, "auto": true}).ok, "can't allocate before accepting")
	_cmd(sim, "biz_accept", {"contract": c.id})
	eq(s.contracts[c.id].status, "active")
	eq(s.lifetime("contracts_accepted"), 1)
	_cmd(sim, "biz_allocate", {"contract": c.id, "auto": true})
	eq(Business.allocated(s, c), 3, "fully allocated")
	check(a_grade.unit.order_id == 0, "the auto-pick spends the cheaper B grades, not the A")
	for it: Item in good:
		check(it.unit.order_id != 0 and it.unit.contract == c.id, "allocated unit reserved")
		var o: Dictionary = s.orders[it.unit.order_id]
		eq(o.channel, "business")
		eq(int(o.price), 210)
		eq(o.status, "to_pack")
	check(not sim.execute(P, "biz_allocate", {"contract": c.id, "items": [a_grade.id]}).ok, "no room left")
	check(not sim.execute(P, "list", {"item": good[0].id, "listed": true}).ok, "allocated units stay off Flogit")
	var cash0 := s.cash
	var rep0 := s.reputation
	for it: Item in good:
		_pack_and_ship(sim, it)
	var post := int(Content.econ("flogit_postage", 9))
	eq(s.cash - cash0, 3 * (210 - post), "paid per unit with no marketplace fee, less postage")
	eq(s.contracts[c.id].status, "complete")
	eq(int(s.contracts[c.id].shipped), 3)
	eq(int(s.contracts[c.id].revenue), 630)
	near(s.reputation - rep0, float(Business.cfg("rep_complete", {}).get("bulk", 3.0)), 0.01, "on-time bonus")
	eq(s.lifetime("contracts_done"), 1)
	eq(int(s.stats.business.complete), 1)
	eq(int(s.stats.business.on_time), 1)
	check(s.stats.sales.any(func(r): return r.channel == "business" and int(r.fee) == 0), "sale records kept with no fee")
	var rep := BusinessReport.compute(s)
	eq(int(rep.customers.business.complete), 1, "Insights sees the contract")
	eq(int(rep.customers.business.revenue), 630)


func test_bulk_order_late_then_cancelled() -> void:
	var sim := fresh_sim(504)
	var s := sim.state
	_setup(sim)
	var it := _unit(s, "corvex_prospect_5420", "B", 16, 256)
	var c := _bulk(sim, 2, 200)
	_cmd(sim, "biz_accept", {"contract": c.id})
	_cmd(sim, "biz_allocate", {"contract": c.id, "items": [it.id]})
	eq(Business.allocated(s, c), 1)
	# A second, mistaken allocation can be taken back before it's packed.
	var spare := _unit(s, "corvex_prospect_5420", "B", 16, 256)
	_cmd(sim, "biz_allocate", {"contract": c.id, "items": [spare.id]})
	_cmd(sim, "biz_release", {"contract": c.id, "item": spare.id})
	check(spare.unit.order_id == 0 and spare.unit.contract == 0, "released back to stock")
	eq(Business.allocated(s, c), 1)
	s.reputation = 50.0           # room for both hits (reputation never drops below 0)
	var rep0 := s.reputation
	_advance_working(sim, 17 * 60.0)
	check(s.contracts[c.id].late, "past the deadline")
	near(s.reputation - rep0, float(Business.cfg("rep_late", -5.0)), 0.01, "one reputation hit for being late")
	check(s.orders[it.unit.order_id].late_flagged, "its orders show as late")
	_advance_working(sim, (float(Business.cfg("cancel_after_hours", 16)) + 1.0) * 60.0)
	eq(s.contracts[c.id].status, "cancelled", "two working days late: cancelled")
	check(it.unit.order_id == 0 and it.unit.contract == 0, "unpacked units go back into stock")
	near(s.reputation - rep0, float(Business.cfg("rep_late", -5.0)) + float(Business.cfg("rep_cancel", -8.0)), 0.01)


# ================================================================= custom server builds

func test_build_from_a_barebones_chassis() -> void:
	var sim := fresh_sim(505)
	var s := sim.state
	_setup(sim)
	var c := _build(sim, 2300)
	check(int(c.parts_estimate) > 800 and int(c.parts_estimate) < 1500, "parts estimate %d" % int(c.parts_estimate))
	_cmd(sim, "biz_accept", {"contract": c.id})
	check(not sim.execute(P, "build", {"station": "build_1", "act": "chassis"}).ok, "no chassis in the bins yet")
	for pid in ["chassis_rampart_2200", "cpu_vortex_x9_6248", "cpu_vortex_x9_6248", "ram_rdimm_64", "ram_rdimm_64", "ram_rdimm_64",
			"ram_rdimm_64", "psu_server_750", "psu_server_750", "raid_card_12g"]:
		s.parts[pid] = int(s.parts.get(pid, 0)) + 1
	s.parts["hdd_sas_1200"] = 8
	eq(Business.shopping_list(s, c), [], "everything needed is in the bins")
	_cmd(sim, "build", {"station": "build_1", "act": "chassis"})
	var it := s.station_slot_item("build_1", "unit")
	check(it != null and it.unit != null, "a server is on the bench")
	var u := it.unit
	eq(u.model_id, "corvex_rampart_2200")
	eq(u.contract, c.id, "reserved for the build")
	eq(int(s.contracts[c.id].unit), it.id)
	eq(u.ram_gb, 0)
	check(not ServerBuild.complete(u, c.spec), "empty chassis isn't the spec")
	check("No CPU" in u.spec_line(), "spec line: %s" % u.spec_line())
	var steps := 0
	while not ServerBuild.complete(u, c.spec) and steps < 40:
		steps += 1
		_cmd(sim, "build", {"station": "build_1", "act": "step"})
	eq(steps, 17, "2 CPUs + 4 DIMMs + 8 drives + RAID + 2 PSUs")
	check(ServerBuild.complete(u, c.spec), "all green")
	eq(u.spec_line(), "2× Vortex X9-6248 · 256GB RAM · 8× 1.2TB SAS")
	check(s.parts.is_empty() or s.parts.keys().all(func(k): return int(s.parts[k]) > 0), "bins tidy")
	for pid in ["chassis_rampart_2200", "cpu_vortex_x9_6248", "ram_rdimm_64", "hdd_sas_1200", "psu_server_750", "raid_card_12g"]:
		eq(int(s.parts.get(pid, 0)), 0, "%s used up" % pid)
	check(u.parts_cost > 800, "build parts costed into the unit: £%d" % u.parts_cost)
	check(not sim.execute(P, "build", {"station": "build_1", "act": "step"}).ok, "nothing left to fit")
	check(not sim.execute(P, "grade", {"station": "build_1"}).ok, "not a grading desk")
	# No order until it has passed a test and been imaged.
	eq(u.order_id, 0)
	sim._complete_test(it, 0)
	eq(u.order_id, 0, "tested but not imaged yet")
	u.imaged = true
	sim._check_build_ready(it)
	check(u.order_id != 0, "built + tested + imaged = an order to pack")
	var o: Dictionary = s.orders[u.order_id]
	eq(int(o.price), 2300)
	eq(int(o.contract), c.id)
	var rep0 := s.reputation
	var cash0 := s.cash
	_pack_and_ship(sim, it)
	eq(s.cash - cash0, 2300 - int(Content.econ("flogit_postage_large", 26)))
	eq(s.contracts[c.id].status, "complete")
	near(s.reputation - rep0, float(Business.cfg("rep_complete", {}).get("build", 5.0)), 0.01)
	eq(s.lifetime("builds_shipped"), 1)


func test_build_reuses_a_stock_server_and_returns_old_parts() -> void:
	var sim := fresh_sim(506)
	var s := sim.state
	_setup(sim)
	var c := _build(sim)
	_cmd(sim, "biz_accept", {"contract": c.id})
	# A lot server: Arcturo CPUs (wrong for an X9 spec), 128GB, eight 600GB drives — tested and graded already.
	var it := _unit(s, "corvex_rampart_2200", "B", 128, 4800, "arcturo_a9_titan_7543")
	eq(it.unit.layout().dimms, [64, 64])
	eq(it.unit.layout().drives.size(), 8)
	s.set_loc(it, {"t": "slot", "slot": "build_1:unit"})
	sim._on_entered_slot(it, "build_1:unit", P)
	eq(it.unit.contract, c.id, "a matching server put on the bench is reserved for the build")
	var step := ServerBuild.next_step(s, P, it.unit, c.spec)
	check(step.has("blocked") or step.get("act", "") == "cpu_out", "wrong CPUs come out first: %s" % str(step))
	s.parts["cpu_vortex_x9_6248"] = 2
	s.parts["ram_rdimm_32"] = 4
	s.parts["hdd_sas_1200"] = 8
	var steps := 0
	while not ServerBuild.complete(it.unit, c.spec) and steps < 40:
		steps += 1
		_cmd(sim, "build", {"station": "build_1", "act": "step"})
	check(ServerBuild.complete(it.unit, c.spec), "built from stock in %d steps" % steps)
	eq(steps, 1 + 2 + 4 + 8, "CPUs out, 2 in, 4 DIMMs into free slots, 8 drive swaps")
	eq(int(s.parts.get("cpu_arcturo_titan_7543", 0)), 2, "the old CPUs went back in the bins")
	eq(int(s.parts.get("hdd_sas_600", 0)), 8, "the old drives went back in the bins")
	eq(it.unit.ram_gb, 256)
	check(not it.unit.tested and it.unit.grade == "" and not it.unit.imaged, "new parts mean a fresh test and image")


func test_build_blocks_without_parts_and_wrong_chassis() -> void:
	var sim := fresh_sim(507)
	var s := sim.state
	_setup(sim)
	var c := _build(sim)
	_cmd(sim, "biz_accept", {"contract": c.id})
	var wrong := _unit(s, "corvex_rampart_1100", "B")
	var pv := ServerBuild.next_step(s, P, wrong.unit, c.spec)
	check("Wrong chassis" in str(pv.get("blocked", "")), str(pv))
	var it := _unit(s, "corvex_rampart_2200", "B", 128, 4800, "vortex_x9_6248")
	it.unit.contract = c.id
	var need := Business.shopping_list(s, c)
	check(not need.is_empty(), "the shopping list names what's missing")
	pv = ServerBuild.next_step(s, P, it.unit, c.spec)
	check(str(pv.get("blocked", "")).begins_with("Need memory"), "first gap is memory: %s" % str(pv))
	s.set_loc(it, {"t": "slot", "slot": "build_1:unit"})
	check(not sim.execute(P, "build", {"station": "build_1", "act": "step"}).ok, "the host refuses too")


# ================================================================= upgrade to sell

func test_upgrade_ram_into_a_free_slot() -> void:
	var sim := fresh_sim(508)
	var s := sim.state
	_setup(sim)
	var it := _unit(s, "corvex_prospect_5420", "B", 8, 256)
	it.unit.listed = true
	eq(it.unit.layout().dimms, [8], "8GB is one stick, so the second slot is free")
	s.parts = {"ram_ddr4_8": 1, "ssd_nvme_512": 1}
	s.set_loc(it, {"t": "slot", "slot": "repair_1:unit"})
	var opts := Upgrades.options(s, P, it.unit)
	var keys := opts.map(func(o): return o.key)
	check("ram_add:ram_ddr4_8" in keys and "drive_swap:ssd_nvme_512" in keys, "options: %s" % str(keys))
	var ram_opt: Dictionary = opts.filter(func(o): return o.key == "ram_add:ram_ddr4_8")[0]
	check(int(ram_opt.gain) > 0 and int(ram_opt.cost) == 14, "cost vs gain known up front: %s" % str(ram_opt))
	var hold := Upgrades.hold(s, P, it.unit, 0)
	check(hold.has("secs") and "Part £" in str(hold.sub) and "value" in str(hold.sub), "hold prompt shows cost against gain: %s" % str(hold))
	var price0 := it.unit.list_price
	var guide0 := Valuation.market_value(it.unit, "B")
	_cmd(sim, "upgrade", {"station": "repair_1", "key": "ram_add:ram_ddr4_8"})
	eq(it.unit.ram_gb, 16)
	eq(int(s.parts.get("ram_ddr4_8", 0)), 0, "stick used")
	eq(it.unit.list_price - price0, Valuation.market_value(it.unit, "B") - guide0, "listing follows the value")
	check(it.unit.listed and it.unit.grade == "B" and it.unit.imaged, "still graded, imaged and listed")
	check("16GB RAM" in it.unit.spec_line(), it.unit.spec_line())
	_cmd(sim, "upgrade", {"station": "repair_1", "key": "drive_swap:ssd_nvme_512"})
	eq(it.unit.storage_gb, 512)
	eq(int(s.parts.get("ssd_nvme_256", 0)), 1, "the old SSD went back in the bins")
	eq(it.unit.upgrades, 2)
	eq(s.lifetime("upgrades"), 2)
	eq(int(s.stats.upgrades.count), 2)
	check(not sim.execute(P, "upgrade", {"station": "repair_1", "key": "ram_add:ram_ddr4_8"}).ok, "can't fit a part that isn't there")


func test_upgrades_respect_slots_maximums_and_rules() -> void:
	var sim := fresh_sim(509)
	var s := sim.state
	_setup(sim)
	s.parts = {"ram_ddr4_16": 4, "ram_ddr4_8": 4, "ssd_nvme_1000": 1}
	var full := _unit(s, "corvex_prospect_5420", "B", 32, 256)
	eq(full.unit.layout().dimms, [16, 16])
	check(Upgrades.options(s, P, full.unit).all(func(o): return o.kind != "ram"), "32GB is the most it takes")
	var soldered := _unit(s, "corvex_prospect_7390", "B", 8, 256)
	check(Upgrades.options(s, P, soldered.unit).all(func(o): return o.kind != "ram"), "soldered memory can't be upgraded")
	var one_slot := _unit(s, "northstar_meridian_t14", "B", 8, 256)
	var opts := Upgrades.options(s, P, one_slot.unit).filter(func(o): return o.kind == "ram")
	check(opts.size() == 1 and str(opts[0].key) == "ram_swap:ram_ddr4_16" and str(opts[0].pull) == "ram_ddr4_8", "one slot: swap 8 for 16 (%s)" % str(opts))
	var phone := _unit(s, "kestrel_pulse_6", "B")
	check(Upgrades.options(s, P, phone.unit).is_empty(), "phones have nothing to upgrade")
	var untested := _unit(s, "corvex_prospect_5420", "", 8, 256)
	untested.unit.tested = false
	check(Upgrades.options(s, P, untested.unit).is_empty() and Upgrades.blocked_reason(untested.unit) != "", "test first")
	var sold := _unit(s, "corvex_prospect_5420", "B", 8, 256)
	sold.unit.order_id = 999
	check(Upgrades.blocked_reason(sold.unit) != "", "sold units ship as described")


# ================================================================= reviews + grading

func _review_for(sim: Sim, u: UnitData, oid: int, price: int, placed_ago_h: float, late_h: float) -> Dictionary:
	var s := sim.state
	var o := {"id": oid, "buyer": "techmum", "model": u.display_name(), "price": price, "grade": u.grade,
		"placed": s.minute - placed_ago_h * 60.0, "due": s.minute - late_h * 60.0 if late_h > 0.0 else s.minute + 600.0}
	o["rv"] = Reviews.facts(s, o, u, late_h <= 0.0, s.minute)
	return Reviews.make(s, o)


func test_review_scores_follow_grade_speed_and_price() -> void:
	var sim := fresh_sim(510)
	var s := sim.state
	sim.advance_minutes(300)      # 14:00 on day one, so "placed hours ago" stays inside working time
	var clean := _unit(s, "corvex_prospect_5420", "A", 16, 256).unit
	for i in 6:
		var fast := _review_for(sim, clean, 9000 + i, int(Valuation.market_value(clean, "A") * 0.85), 0.5, 0.0)
		check(int(fast.stars) >= 4, "fast, cheap and as described: %d★" % int(fast.stars))
		check(str(fast.text) != "" and not "{" in str(fast.text), "comment filled in: %s" % fast.text)
	var cracked := _unit(s, "corvex_prospect_5420", "A", 16, 256).unit
	cracked.cosmetic = ["cracked_screen"]
	cracked.missed_flaw = "cracked_screen"
	for i in 6:
		var bad := _review_for(sim, cracked, 9100 + i, Valuation.market_value(cracked, "A"), 2.0, 0.0)
		check(int(bad.stars) <= 2, "said A, has a crack: %d★" % int(bad.stars))
		eq(bad.reason, "overgraded")
		check("A" in str(bad.text) or "racked" in str(bad.text) or "mentioned" in str(bad.text), "the comment says why: %s" % bad.text)
	for i in 6:
		var late := _review_for(sim, clean, 9200 + i, Valuation.market_value(clean, "A"), 4.0, 3.0)
		check(int(late.stars) <= 3 and late.reason == "late", "late: %d★ (%s)" % [int(late.stars), late.reason])
	var pricey := _review_for(sim, clean, 9300, int(Valuation.market_value(clean, "A") * 1.4), 4.0, 0.0)
	check(pricey.reason == "pricey" and int(pricey.stars) <= 4, "overpriced: %s" % str(pricey))


func test_reviews_arrive_after_delivery_and_nudge_reputation() -> void:
	var sim := fresh_sim(511)
	var s := sim.state
	_setup(sim)
	var it := _unit(s, "corvex_prospect_5420", "B", 16, 256)
	var order := sim._create_order(it, "flogit")
	_pack_and_ship(sim, it)
	var o: Dictionary = s.orders[order.id]
	check(o.has("review_due") and o.has("rv"), "the buyer will write a review")
	check(not o.has("review"), "not straight away")
	var rep0 := s.reputation
	_advance_working(sim, 7 * 60.0)
	check(o.has("review"), "review arrived within the working day")
	var r: Dictionary = o.get("review", {})
	check(int(r.get("stars", 0)) >= 1 and int(r.get("stars", 0)) <= 5, "1–5 stars")
	check(str(r.get("text", "")) != "" and not "{" in str(r.get("text", "")), "comment: %s" % r.get("text", ""))
	near(s.reputation - rep0, Reviews.rep_for(int(r.get("stars", 3))), 0.01, "stars nudge reputation")
	eq(s.lifetime("reviews"), 1)
	eq(s.stats.reviews.size(), 1)
	var sm := Reviews.summary(s)
	eq(int(sm.count), 1)
	near(float(sm.avg), float(r.stars), 0.01)
	var rep := BusinessReport.compute(s)
	eq(int(rep.customers.reviews.count), 1, "Insights Customers page sees it")


func test_green_graders_sometimes_miss_a_flaw() -> void:
	var sim := fresh_sim(512)
	var s := sim.state
	_setup(sim)
	var misses := 0
	for i in 250:
		var it := _unit(s, "corvex_prospect_5420", "", 16, 256)
		it.unit.cosmetic = ["scratches_lid"]      # B on its own; an A if the scratches are missed
		s.set_loc(it, {"t": "slot", "slot": "grade_1:unit"})
		_cmd(sim, "grade", {"station": "grade_1"})
		if it.unit.missed_flaw != "":
			misses += 1
			eq(it.unit.grade, "A", "missed flaw → better grade")
			eq(Valuation.grade_for(it.unit), "B", "the truth is B")
			check(Reviews.overgraded(Reviews.facts(s, {"price": 200, "grade": it.unit.grade}, it.unit, true, s.minute)), "a buyer would notice")
		else:
			eq(it.unit.grade, "B")
		s.remove_item(it.id)
	check(misses >= 5 and misses <= 60, "a few misses from a new grader: %d of 250" % misses)
	# An expert (grading level 10) never misses.
	s.player(Sim.player_key(P)).xp["grading"] = 999999
	for i in 60:
		var it := _unit(s, "corvex_prospect_5420", "", 16, 256)
		it.unit.cosmetic = ["scratches_lid"]
		s.set_loc(it, {"t": "slot", "slot": "grade_1:unit"})
		_cmd(sim, "grade", {"station": "grade_1"})
		eq(it.unit.missed_flaw, "", "expert grader")
		s.remove_item(it.id)


# ================================================================= co-op + saves

func _replicate(host: GameState, guest: GameState) -> void:
	for key in host.dirty:
		var kv: Array = host.dirty[key]
		guest.import_entity(kv[0], kv[1], host.export_entity(kv[0], kv[1]))
	host.dirty.clear()


func test_contracts_builds_and_reviews_replicate_and_save() -> void:
	var sim := fresh_sim(513)
	var host := sim.state
	_setup(sim)
	var guest := GameState.new()
	guest.load_dict(host.snapshot())
	host.dirty.clear()
	host.reputation = 60.0
	var offer := sim._new_offer()
	check(not offer.is_empty(), "an enquiry was made")
	_replicate(host, guest)
	check(guest.contracts.has(offer.id), "the guest sees the enquiry")
	eq(guest.contracts[offer.id].status, "offer")
	eq(guest.contracts[offer.id].body, offer.body)
	# The guest answers (a command routed to the host, as peer 2); the host's answer replicates back.
	var r := sim.execute(2, "biz_decline", {"contract": offer.id})
	check(r.ok, r.msg)
	var c := _build(sim, 2100)
	_cmd(sim, "biz_accept", {"contract": c.id})
	for pid in ["chassis_rampart_2200", "cpu_vortex_x9_6248", "cpu_vortex_x9_6248", "ram_rdimm_64", "ram_rdimm_64"]:
		host.parts[pid] = int(host.parts.get(pid, 0)) + 1
	_cmd(sim, "build", {"station": "build_1", "act": "chassis"})
	for i in 4:
		_cmd(sim, "build", {"station": "build_1", "act": "step"})
	var it := host.station_slot_item("build_1", "unit")
	var sold := _unit(host, "corvex_prospect_5420", "B", 16, 256)
	var order := sim._create_order(sold, "flogit")
	_pack_and_ship(sim, sold)
	_advance_working(sim, 7 * 60.0)
	_replicate(host, guest)
	eq(guest.contracts[offer.id].status, "declined", "declined on the guest too")
	eq(guest.contracts[c.id].status, "active", "the build is on the go for the guest too")
	var gi: Item = guest.items.get(it.id)
	check(gi != null and gi.unit != null, "the half-built server replicated")
	if gi:
		eq(gi.unit.spec_line(), it.unit.spec_line(), "same fitted hardware on the guest")
		eq(gi.unit.contract, c.id)
		eq(ServerBuild.progress(gi.unit, guest.contracts[c.id].spec), ServerBuild.progress(it.unit, c.spec), "same checklist on the guest")
	eq(guest.orders[order.id].get("review", {}), host.orders[order.id].get("review", {}), "the review replicated")
	eq(Reviews.summary(guest).count, Reviews.summary(host).count)
	# Save → load round trip.
	var back := GameState.new()
	back.load_dict(JSON.parse_string(JSON.stringify(host.to_dict())))
	eq(back.contracts.size(), host.contracts.size())
	eq(back.contracts[c.id].status, "active")
	eq(back.contracts[c.id].spec.cpu_ids, VIRT_SPEC.cpu_ids)
	var bi: Item = back.items[it.id]
	eq(bi.unit.spec_line(), it.unit.spec_line(), "built hardware survives a save")
	eq(bi.unit.layout(), it.unit.layout())
	eq(bi.unit.contract, c.id)
	eq(back.orders[order.id].get("review", {}).get("stars", -1), host.orders[order.id].get("review", {}).get("stars", -2))
	eq(back.stats.reviews.size(), host.stats.reviews.size())
