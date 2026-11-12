class_name Staff
extends RefCounted
## AI staff rules shared by Sim, the Staff app and the bodies: data, candidate generation, wages, peers.

static var _data := {}


static func data() -> Dictionary:
	if _data.is_empty():
		_data = JSON.parse_string(FileAccess.get_file_as_string("res://data/staff.json"))
	return _data


static func office_role(role: String) -> bool:
	return role in ["buyer", "salesperson"]


static func wage_for(stats: Dictionary) -> int:
	## Pay climbs steeply with ability: experts in their trade cost several times a new starter.
	var vals: Array = stats.values()
	vals.sort()
	vals.reverse()
	var pay := float(Staff.data().get("wage_base", 180))
	for i in mini(3, vals.size()):
		pay += pow(float(vals[i]), 1.75) * float(Staff.data().get("wage_per_point", 6.0)) * (1.0 if i == 0 else 0.6)
	var avg := 0.0
	for v in vals:
		avg += float(v)
	pay += avg / maxf(vals.size(), 1) * 10.0
	return int(round(pay / 5.0) * 5.0)


static func main_skill(role: String) -> String:
	for r: Dictionary in data().roles:
		if r.id == role:
			return str(r.skills[0])
	return "hauling"


static func reliability(levels: Dictionary) -> float:
	## 0..1 — steadier people lose less time between jobs.
	var avg := 0.0
	for v in levels.values():
		avg += float(v)
	return clampf(avg / maxf(levels.size(), 1) / 10.0, 0.1, 1.0)


static func units_per_day(role: String, level: int) -> float:
	## Rough throughput at their main job for the Staff app (a full 8-hour day).
	var speeds: Array = Content.skills_data.get("speed", [0.55, 0.7, 0.82, 0.92, 1.0, 1.1, 1.22, 1.36, 1.55, 1.75])
	var sp := pow(float(speeds[clampi(level - 1, 0, speeds.size() - 1)]), 1.5)
	var minutes := 480.0
	match role:
		"tester":
			return minutes / ((float(Content.econ("test_minutes", 30)) + float(Content.econ("image_minutes", 45))) / sp + 6.0)
		"repair_tech":
			return minutes / (14.0 / sp + 4.0)
		"grader":
			return minutes / (6.0 / sp + 3.0)
		"packer":
			return minutes / (6.0 / sp + 4.0)
		"hauler":
			return minutes / (2.5 / sp)
		"all_rounder":
			return minutes / (25.0 / sp)
		"buyer":
			return 0.0
		"salesperson":
			return 0.0
	return 0.0


static func value_per_week(role: String, level: int) -> int:
	## What their work is worth to the business in a week (very roughly): units moved × typical margin share.
	var margin := {"tester": 22.0, "repair_tech": 34.0, "grader": 8.0, "packer": 7.0, "hauler": 1.6, "all_rounder": 24.0}
	if role == "salesperson":
		return int(140.0 * level)
	if role == "buyer":
		return int(110.0 * level)
	return int(units_per_day(role, level) * 5.0 * float(margin.get(role, 5.0)))


static func make_candidate(rng: RandomNumberGenerator, id: int) -> Dictionary:
	var d := data()
	var roles: Array = d.roles
	var role: Dictionary = roles[rng.randi() % roles.size()]
	var stats := {}
	for sk: Dictionary in Content.skill_defs():
		stats[sk.id] = rng.randi_range(1, 4)
	# Strong in what their background suits, with the odd all-round star.
	for sk in role.skills:
		stats[sk] = rng.randi_range(4, 9)
	if rng.randf() < 0.12:
		for k in stats:
			stats[k] = mini(int(stats[k]) + 2, 10)
	var first: Array = d.first_names
	var last: Array = d.last_names
	var look := {"body": "f" if rng.randf() < 0.5 else "m",
		"shirt": Looks.SHIRTS[rng.randi() % Looks.SHIRTS.size()], "trousers": Looks.TROUSERS[rng.randi() % Looks.TROUSERS.size()],
		"skin": Looks.SKINS[rng.randi() % Looks.SKINS.size()], "hair": Looks.HAIRS[rng.randi() % Looks.HAIRS.size()],
		"hairstyle": Looks.HAIR_STYLES[rng.randi() % (Looks.HAIR_STYLES.size() - 1)], "shoes": Looks.SHOES[rng.randi() % Looks.SHOES.size()],
		"hivis": role.id in ["hauler"] and rng.randf() < 0.6, "cap": rng.randf() < 0.15, "height": snappedf(rng.randf_range(1.6, 1.9), 0.01), "bighead": false}
	if look.body == "f":
		look.height = snappedf(rng.randf_range(1.56, 1.78), 0.01)
	return {"id": id, "name": "%s %s" % [first[rng.randi() % first.size()], last[rng.randi() % last.size()]],
		"role": role.id, "stats": stats, "wage": wage_for(stats), "look": look}


static func xp_for_level(level: int) -> int:
	var table: Array = Content.skills_data.get("xp_levels", [0, 150, 400, 800, 1400, 2300, 3600, 5400, 8000, 12000])
	return int(table[clampi(level - 1, 0, table.size() - 1)])
