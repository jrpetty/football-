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
