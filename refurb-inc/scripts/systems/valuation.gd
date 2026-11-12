class_name Valuation
extends RefCounted
## Market value maths. Every model has a hidden value driven by spec + age; grade scales it.


static func a_grade_value(u: UnitData) -> float:
	var m := u.model()
	if m.is_empty():
		return 0.0
	var v := float(m.get("refurb_value_a", 100))
	v += float(Content.cpu(u.cpu_id).get("value_adj", 0)) - float(Content.cpu(m.get("ref_cpu", "")).get("value_adj", 0))
	# Spec adjustments only for kit that has them (monitors/docks/network kit have no RAM; only tablets/phones roll storage).
	if m.has("ref_ram"):
		v += (u.ram_gb - int(m.ref_ram)) / float(m.get("ram_step_gb", 8)) * 14.0
	if m.has("ref_storage"):
		v += (u.storage_gb - int(m.ref_storage)) / float(m.get("storage_step_gb", 256)) * 12.0
	if not u.hw.is_empty():
		# Part-built kit: empty CPU sockets, missing PSUs and no RAID card all knock the value down.
		var sockets := maxi(int(m.get("cpu_count", 1)), 1)
		var cpus := mini(int(u.hw.get("cpus", sockets)), sockets)
		if cpus < sockets:
			v *= 0.35 + 0.65 * float(cpus) / sockets
		var psu_gap := int(m.get("psu_bays", 1)) - int(u.hw.get("psus", int(m.get("psu_bays", 1))))
		if m.get("type", "") == "server" and psu_gap > 0:
			v -= psu_gap * float(Content.part(str(m.get("psu_part", ""))).get("price", 40))
		if m.get("raid_slot", false) and not u.hw.get("raid", true):
			v -= float(Content.part("raid_card_12g").get("price", 45))
	return maxf(v, 25.0)


static func market_value(u: UnitData, grade: String = "") -> int:
	var g := grade if grade != "" else u.grade
	if g == "":
		g = predicted_grade(u)
	return int(round(a_grade_value(u) * Content.grade_mult(g) * (1.02 if u.data_cert else 1.0)))


static func guide_price(model_id: String, grade: String = "B") -> int:
	## Public market guide for a model at its reference spec (what LotFinder shows).
	var m := Content.model(model_id)
	var u := UnitData.new()
	u.model_id = model_id
	u.cpu_id = m.get("ref_cpu", "")
	u.ram_gb = int(m.get("ref_ram", 0))
	u.storage_gb = int(m.get("ref_storage", 0))
	return market_value(u, grade)


static func trade_value(u: UnitData) -> float:
	## What a refurbisher would expect to pay for this exact unit, untested, knowing its true state.
	## Faults and wear reduce it; this is the "fair" anchor that lot prices swing around.
	var expected := a_grade_value(u) * float(Content.econ("lot_trade_ratio", 0.4))
	expected *= lerpf(0.7, 1.08, u.cosmetic_score() / 100.0)
	for f in u.faults:
		var lvl := int(Content.fault(f.id).get("repair_level", 1))
		expected *= 0.86 if lvl == 1 else (0.7 if lvl == 2 else 0.55)
	return expected


static func grade_for(u: UnitData) -> String:
	## Grade from external condition + spec health. Units with open faults are Spares/Repairs.
	if u.has_open_faults() or not u.tested:
		return "S"
	var score := u.cosmetic_score()
	var th := Content.grade_thresholds
	var g := "D"
	if score >= int(th.get("A", 90)):
		g = "A"
	elif score >= int(th.get("B", 74)):
		g = "B"
	elif score >= int(th.get("C", 50)):
		g = "C"
	# A worn battery caps the grade — honest grading protects reputation.
	if u.has_battery() and u.battery_pct < 80 and g == "A":
		g = "B"
	return g


static func predicted_grade(u: UnitData) -> String:
	var copy := UnitData.from_dict(u.to_dict())
	for f in copy.faults:
		f["fixed"] = true
	copy.tested = true
	return grade_for(copy)


static func repair_part_for(u: UnitData, fault: Dictionary) -> String:
	## Resolve the generic part key of a fault to a concrete part id for this unit's model.
	var fdef := Content.fault(fault.get("id", ""))
	var key: String = fdef.get("part", "")
	var m := u.model()
	match key:
		"battery":
			return m.get("battery_part", "")
		"keys":
			return m.get("keycap_part", "")
		"ram":
			var slots := maxi(int(m.get("ram_slots", 1)), 1)
			var dimm := maxi(u.ram_gb / slots, 8)
			return _closest_part("ram", "fits_ram_type", m.get("ram_type", ""), dimm)
		"storage":
			var per_drive := u.storage_gb / maxi(int(m.get("drive_count", 1)), 1)
			return _closest_part("storage", "fits_storage_type", m.get("storage_type", ""), per_drive)
		"psu", "fan", "board", "server_ear":
			var field: String = {"psu": "psu_part", "fan": "fan_part", "board": "board_part", "server_ear": "ear_part"}[key]
			var fallback: String = {"psu": "psu_atx_500", "fan": "fan_laptop_generic", "board": "mobo_laptop_generic", "server_ear": "server_ear_kit"}[key]
			var pid: String = m.get(field, "")
			return pid if pid != "" else fallback
		"screen":
			if String(m.get("screen_part", "")) != "":
				return m.screen_part      # tablets/phones: their own digitiser + panel assembly
			var size := float(m.get("screen_in", 14.0))
			if size < 13.8:
				return "lcd_133_fhd"
			elif size < 15.0:
				return "lcd_14_fhd"
			elif size < 16.5:
				return "lcd_156_fhd"
			return "lcd_173_fhd_144"
	return key


static func _closest_part(category: String, fit_key: String, fit_val: String, size: int) -> String:
	var best := ""
	var best_diff := 1 << 30
	for p: Dictionary in Content.parts.values():
		if p.get("category") != category or p.get(fit_key, "") != fit_val:
			continue
		var diff := absi(int(p.get("size_gb", 0)) - size)
		if diff < best_diff:
			best_diff = diff
			best = p.id
	return best
