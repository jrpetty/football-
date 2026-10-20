extends TestCase


func test_content_loads_without_errors() -> void:
	eq(Content.errors.size(), 0, str(Content.errors))
	check(Content.models.size() >= 10, "expected 10+ laptop models")
	check(Content.cpus.size() >= 10, "expected 10+ CPUs")
	check(Content.sellers.size() >= 8)


func test_no_real_brands_in_data() -> void:
	var banned := ["dell", "hp ", "lenovo", "apple", "cisco", "supermicro", "intel", "amd", "nvidia", "ebay", "microsoft", "windows", "thinkpad", "latitude", "elitebook", "macbook"]
	var dir := DirAccess.open("res://data")
	for f in dir.get_files():
		if not f.ends_with(".json"):
			continue
		var text := FileAccess.get_file_as_string("res://data/" + f).to_lower()
		for b in banned:
			check(not (" %s" % b) in text and not ("\"%s" % b) in text, "%s mentions banned brand '%s'" % [f, b])


func test_every_model_fault_has_a_resolvable_part() -> void:
	for m in Content.models.values():
		var u := UnitData.new()
		u.model_id = m.id
		u.cpu_id = m.ref_cpu
		u.ram_gb = int(m.ref_ram)
		u.storage_gb = int(m.ref_storage)
		for f in Content.faults.values():
			if f.get("requires_ram_slots", false) and int(m.get("ram_slots", 0)) == 0:
				continue
			var pid := Valuation.repair_part_for(u, {"id": f.id})
			check(Content.parts.has(pid), "%s / %s -> missing part '%s'" % [m.id, f.id, pid])


func test_gaming_laptop_is_premium_and_specced() -> void:
	var g := Valuation.guide_price("northstar_vanguard_g17", "B")
	check(g > Valuation.guide_price("northstar_meridian_x13", "B"), "gaming laptop should out-value the X13 (got £%d)" % g)
	var u := UnitData.new()
	u.model_id = "northstar_vanguard_g17"
	u.cpu_id = "vortex_v7_12700h"
	u.ram_gb = 32
	u.storage_gb = 1000
	check("Prism" in u.spec_line() and "1TB" in u.spec_line(), u.spec_line())
	eq(Valuation.repair_part_for(u, {"id": "screen_cracked"}), "lcd_173_fhd_144")
	eq(Valuation.repair_part_for(u, {"id": "storage_dead"}), "ssd_nvme_1000")
	print("    Vanguard G17 guide: A £%d · B £%d · C £%d" % [Valuation.guide_price(u.model_id, "A"), g, Valuation.guide_price(u.model_id, "C")])
