class_name StripDown
## Strip-down station (brief §9): a tested unit that isn't worth fixing is taken apart and its working parts go
## into the parts bins as donor parts. Testing first matters: you only keep what you know works.
##
## The candidate parts are the same parts a repair would fit (Valuation.repair_part_for), one per component the
## unit's model has — so donor stock always matches what your repairs need. A component with an open fault (dead
## SSD, cracked screen, worn battery…) isn't saved. Careful removal: each part comes out whole with a chance
## that rises with Repair level.

## Fault part keys that are real components inside the unit (not tools or kits used to fix things).
const COMPONENTS := ["battery", "keys", "ram", "storage", "psu", "fan", "screen", "board", "server_ear", "kbd_deck_generic",
	"raid_bbu", "charge_port_usbc", "monitor_stand"]


static func donor_parts(u: UnitData) -> Array:
	## Part ids this unit can give, before the removal roll (duplicates for multi-slot RAM / multi-bay drives).
	var out: Array = []
	if u == null:
		return out
	var m := u.model()
	var dead := {}             # part key -> true for components that are broken
	for f in u.open_faults():
		dead[str(Content.fault(str(f.id)).get("part", ""))] = true
	if u.battery_pct > 0 and u.battery_pct < 80:
		dead["battery"] = true
	for c in u.cosmetic:
		if str(Content.condition(c).get("fixable_by_fault", "")) in ["screen_cracked", "backlight_dead"]:
			dead["screen"] = true
	var seen := {}
	for f: Dictionary in Content.faults.values():
		var key := str(f.get("part", ""))
		if not key in COMPONENTS or seen.has(key) or dead.has(key) or not Content.applies_to(f, m):
			continue
		if f.get("requires_ram_slots", false) and int(m.get("ram_slots", 0)) <= 0:
			continue
		var pid := Valuation.repair_part_for(u, {"id": f.id})
		if pid == "" or not Content.parts.has(pid) or Content.part(pid).get("reusable", false):
			continue
		seen[key] = true
		var n := 1
		if key == "ram":
			n = clampi(int(m.get("ram_slots", 1)), 1, 4)
			if u.ram_gb <= 0:
				continue
		elif key == "storage":
			n = clampi(int(m.get("drive_count", 1)), 1, 8)
			if u.storage_gb <= 0:
				continue
		for i in n:
			out.append(pid)
	return out


static func keep_chance(repair_level: int) -> float:
	return clampf(0.72 + 0.03 * repair_level, 0.0, 0.98)


static func strip_secs(u: UnitData) -> float:
	return 8.0 * float(u.model().get("test_mult", 1.0)) if u else 8.0


static func summary(parts: Array) -> String:
	## "2× 8GB DDR4 SODIMM, 256GB SSD, battery" style list for prompts and toasts.
	var counts := {}
	var order: Array = []
	for p in parts:
		if not counts.has(p):
			order.append(p)
		counts[p] = int(counts.get(p, 0)) + 1
	var bits: PackedStringArray = []
	for p in order:
		var name := str(Content.part(p).get("name", p))
		bits.append(("%d× %s" % [counts[p], name]) if int(counts[p]) > 1 else name)
	return ", ".join(bits)
