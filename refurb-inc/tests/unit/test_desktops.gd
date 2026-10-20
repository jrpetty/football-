extends TestCase
## Desktops (tower / SFF / mini), typed faults and conditions, per-form packing and carrying, model matching.

const P := 1


func _roll_many(sim: Sim, model_id: String, n: int) -> Array:
	var out: Array = []
	for i in n:
		out.append(sim.lots.roll_unit(model_id, sim.state.rng.randf()))
	return out


func test_faults_and_conditions_match_the_hardware_type() -> void:
	var sim := fresh_sim(77)
	for u: UnitData in _roll_many(sim, "northstar_bastion_t560", 300):
		eq(u.battery_pct, 100, "desktops have no battery to wear")
		for f in u.faults:
			check(Content.allowed_for(Content.fault(f.id), u.model_id), "desktop rolled laptop fault %s" % f.id)
		for c in u.cosmetic:
			check(Content.allowed_for(Content.condition(c), u.model_id), "desktop rolled laptop condition %s" % c)
	for u: UnitData in _roll_many(sim, "kestrel_swift_14", 300):
		for f in u.faults:
			check(f.id != "psu_dead" and f.id != "cmos_flat", "laptop rolled desktop fault %s" % f.id)


func test_desktop_faults_resolve_to_desktop_parts() -> void:
	var sim := fresh_sim(3)
	var u := sim.lots.roll_unit("corvex_compact_7090", 0.5)
	eq(Valuation.repair_part_for(u, {"id": "psu_dead"}), "psu_sff_200")
	eq(Valuation.repair_part_for(u, {"id": "fan_noisy"}), "fan_case_92")
	eq(Valuation.repair_part_for(u, {"id": "board_no_post"}), "mobo_desktop_generic")
	u.ram_gb = 16
	eq(Valuation.repair_part_for(u, {"id": "ram_faulty"}), "ram_dimm_8", "2 slots of 8GB DIMMs")
	var h := sim.lots.roll_unit("halden_slimline_600", 0.5)
	h.storage_gb = 1000
	eq(Valuation.repair_part_for(h, {"id": "storage_dead"}), "hdd_sata_1000")


func test_desktops_are_never_capped_by_battery() -> void:
	var sim := fresh_sim(5)
	var u := sim.lots.roll_unit("kestrel_nano_q3", 1.0)
	u.cosmetic = []
	u.faults = []
	u.tested = true
	u.battery_pct = 10
	eq(Valuation.grade_for(u), "A", "a pristine mini PC is grade A whatever battery_pct says")


func test_lots_hold_one_form() -> void:
	var sim := fresh_sim(9)
	for i in 200:
		var seller: Dictionary = Content.sellers.values()[i % Content.sellers.size()]
		var lot := sim.lots.create_lot(seller)
		var forms := {}
		for ud in lot.units:
			forms[Content.model_form(ud.model_id)] = true
		eq(forms.size(), 1, "lot from %s mixes forms %s" % [seller.id, str(forms.keys())])


func test_towers_carry_one_at_a_time_and_pack_in_large_boxes() -> void:
	var sim := fresh_sim(11)
	var s := sim.state
	var towers: Array = []
	for i in 2:
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = sim.lots.roll_unit("northstar_bastion_t560", 0.9)
		s.set_loc(it, {"t": "loose", "p": [0, 0, 0], "r": [0, 0, 0, 1]})
		towers.append(it)
	var laptop := s.new_item(Item.KIND_UNIT)
	laptop.unit = sim.lots.roll_unit("kestrel_swift_14", 0.9)
	s.set_loc(laptop, {"t": "loose", "p": [0, 0, 0], "r": [0, 0, 0, 1]})
	check(sim.execute(P, "pick_up", {"item": towers[0].id}).ok, "pick up a tower")
	check(not sim.execute(P, "pick_up", {"item": towers[1].id}).ok, "a second tower doesn't fit in your arms")
	check(not sim.execute(P, "pick_up", {"item": laptop.id}).ok, "can't balance a laptop on a tower")
	# Pack it: needs a large box.
	s.register_station("pack_1", "pack")
	s.register_slot("pack_1:unit", ["unit", "package"], "pack_1")
	var t: Item = towers[0]
	t.unit.grade = "B"
	sim._create_order(t, "flogit")
	check(sim.execute(P, "place", {"slot": "pack_1:unit"}).ok, "sold tower onto the packing table")
	var large := int(s.consumables.get("boxes_large", 0))
	var small := int(s.consumables.get("boxes", 0))
	check(sim.execute(P, "pack", {"station": "pack_1"}).ok, "pack the tower")
	eq(int(s.consumables.boxes_large), large - 1, "used a large box")
	eq(int(s.consumables.boxes), small, "laptop boxes untouched")
	eq(String(s.item_in_slot("pack_1:unit").data.form), "tower", "parcel remembers its size")


func test_pallets_follow_the_form_layout() -> void:
	var sim := fresh_sim(13)
	var s := sim.state
	var seller: Dictionary = Content.sellers.grafton_contact
	var lot := sim.lots.create_lot(seller)
	while Content.model_form(lot.units[0].model_id) != "sff":
		lot = sim.lots.create_lot(seller)
	lot.count = lot.units.size()
	s.cash = 100000
	sim.c_buy_lot(P, {"lot": lot.id})
	var pallets := sim.deliver_lot(lot.id)
	var per := int(Content.form("sff").pallet.per_pallet)
	eq(pallets.size(), int(ceil(float(lot.count) / per)), "pallets for %d SFFs" % lot.count)
	eq(String(pallets[0].data.form), "sff")


func test_model_library_matches_by_name() -> void:
	# Typical exported/generated file names: extra words and numbers are fine.
	var names := ["export_mid_tower_pc_v3_0042.glb", "mini_pc_final.glb", "sff_desktop_2.glb",
		"laptop_tier1_student_a.glb", "laptop_tier2_office_b.glb", "laptop_tier3_ultralight.glb",
		"laptop_tier4_mainstream.glb", "laptop_tier6_gaming_v2.glb", "office_chair.glb"]
	var expect := {"northstar_bastion_t560": 0, "kestrel_nano_q3": 1, "brightline_cube_m1": 1, "corvex_compact_7090": 2,
		"halden_slimline_600": 2, "kestrel_swift_14": 3, "corvex_vantage_3510": 4, "halden_folio_650": 4,
		"brightline_aura_13": 5, "northstar_meridian_t14": 6, "kestrel_talon_16": 7}
	for mid in expect:
		var i := ModelLibrary.best_match(Content.model(mid).visual.match, names)
		eq(i, int(expect[mid]), "%s matched %s" % [mid, names[i] if i >= 0 else "nothing"])
	eq(ModelLibrary.best_match([["laptop", "tier5"]], names), -1, "no false matches")


func test_model_fit_scales_grounds_and_turns() -> void:
	# A tower-shaped mesh modelled 10x too big, sideways and floating: fitted by height, stood on the floor.
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(4.0, 3.8, 1.85)
	mi.mesh = bm
	mi.position = Vector3(5, 2, -1)
	var root := Node3D.new()
	root.add_child(mi)
	var target := Vector3(0.185, 0.38, 0.40)
	var w := ModelLibrary.fit(root, target, "y")
	var got: Vector3 = w.get_meta("fitted_size")
	near(got.y, 0.38, 0.001, "height fitted")
	check(got.z > got.x, "turned so its depth runs front to back (%s)" % str(got))
	var box := ModelLibrary.mesh_aabb(w)
	near(box.position.y, 0.0, 0.001, "sits on the floor")
	near(box.get_center().x, 0.0, 0.001, "centred")
	near(box.get_center().z, 0.0, 0.001, "centred")
	w.free()
