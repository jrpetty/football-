extends Node
## ContentDB — loads every data/*.json definition at startup and exposes typed lookups.
## Nothing in the game hard-codes hardware, parts, faults, sellers or tuning: it all comes from here.

const DATA_DIR := "res://data/"

var cpus: Dictionary = {}          # id -> Dictionary
var models: Dictionary = {}        # id -> Dictionary
var makers: Dictionary = {}
var parts: Dictionary = {}
var faults: Dictionary = {}
var conditions: Dictionary = {}
var sellers: Dictionary = {}
var seller_notes: Array = []
var shop_items: Dictionary = {}
var start_consumables: Dictionary = {}
var grade_thresholds: Dictionary = {}
var grade_value_mult: Dictionary = {}
var economy: Dictionary = {}
var names: Dictionary = {}
var os_info: Dictionary = {}
var diag_info: Dictionary = {}
var buildings: Dictionary = {}     # id -> Dictionary
var building_order: Array = []     # ids by tier
var fixtures: Dictionary = {}      # id -> Dictionary

var errors: PackedStringArray = []


func _init() -> void:
	reload()


func reload() -> void:
	errors.clear()
	cpus = _index(_load("cpus.json").get("cpus", []))
	models = _index(_load("hardware_models.json").get("models", []))
	makers = _index(_load("makers.json").get("makers", []))
	parts = _index(_load("parts.json").get("parts", []))
	faults = _index(_load("faults.json").get("faults", []))
	var cond := _load("conditions.json")
	conditions = _index(cond.get("conditions", []))
	grade_thresholds = cond.get("grade_thresholds", {})
	grade_value_mult = cond.get("grade_value_mult", {})
	var sel := _load("sellers.json")
	sellers = _index(sel.get("sellers", []))
	seller_notes = sel.get("notes", [])
	var shop := _load("shop.json")
	shop_items = _index(shop.get("items", []))
	start_consumables = shop.get("start_consumables", {})
	economy = _load("economy.json")
	names = _load("names.json")
	var os_data := _load("os.json")
	os_info = os_data.get("os", {})
	diag_info = os_data.get("diag", {})
	buildings = _index(_load("buildings.json").get("buildings", []))
	building_order = buildings.keys()
	building_order.sort_custom(func(a, b): return int(buildings[a].tier) < int(buildings[b].tier))
	fixtures = _index(_load("fixtures.json").get("fixtures", []))
	_validate()
	for e in errors:
		push_error("[Content] " + e)


func _load(file: String) -> Dictionary:
	var path := DATA_DIR + file
	if not FileAccess.file_exists(path):
		errors.append("Missing data file: " + path)
		return {}
	var parsed: Variant = JSON.parse_string(FileAccess.get_file_as_string(path))
	if parsed == null or not parsed is Dictionary:
		errors.append("Invalid JSON: " + path)
		return {}
	return parsed


func _index(arr: Array) -> Dictionary:
	var out := {}
	for entry in arr:
		if entry is Dictionary and entry.has("id"):
			out[entry["id"]] = entry
	return out


func _validate() -> void:
	for f in economy.get("start_fixtures", []):
		if not fixtures.has(f.get("type", "")):
			errors.append("start_fixtures references unknown fixture type %s" % f.get("type", ""))
	if not buildings.has(economy.get("start_building", "garage")):
		errors.append("Unknown start_building")
	for m: Dictionary in models.values():
		for c in m.get("cpu_options", []):
			if not cpus.has(c):
				errors.append("Model %s references unknown CPU %s" % [m.id, c])
		if not cpus.has(m.get("ref_cpu", "")):
			errors.append("Model %s has unknown ref_cpu" % m.id)
		var laptop: bool = m.get("type", "laptop") == "laptop"
		for field in ["battery_part", "keycap_part", "psu_part", "fan_part", "board_part", "ear_part"]:
			var p: String = m.get(field, "")
			if (p == "" and laptop and field in ["battery_part", "keycap_part"]) or (p != "" and not parts.has(p)):
				errors.append("Model %s references unknown part %s (%s)" % [m.id, p, field])
		if not m.get("type", "laptop") in ["laptop", "desktop", "server"]:
			errors.append("Model %s has unknown type %s" % [m.id, m.get("type")])
		var vis_model: String = m.get("visual", {}).get("model", "")
		if vis_model != "" and not ResourceLoader.exists(vis_model):
			errors.append("Model %s visual.model missing: %s" % [m.id, vis_model])
		if not makers.has(m.get("maker", "")):
			errors.append("Model %s has unknown maker" % m.id)
	for s: Dictionary in sellers.values():
		for mid in s.get("model_pool", []):
			if not models.has(mid):
				errors.append("Seller %s references unknown model %s" % [s.id, mid])
	for f: Dictionary in faults.values():
		var vc: String = f.get("visible_condition", "")
		if vc != "" and not conditions.has(vc):
			errors.append("Fault %s links unknown condition %s" % [f.id, vc])
		var p: String = f.get("part", "")
		if not p in ["battery", "keys", "ram", "storage", "screen", "psu", "fan", "board", "server_ear"] and not parts.has(p):
			errors.append("Fault %s references unknown part %s" % [f.id, p])


# ---------------------------------------------------------------- lookups

func econ(key: String, default: Variant = null) -> Variant:
	return economy.get(key, default)


func model(id: String) -> Dictionary:
	return models.get(id, {})


func cpu(id: String) -> Dictionary:
	return cpus.get(id, {})


func part(id: String) -> Dictionary:
	return parts.get(id, {})


func fault(id: String) -> Dictionary:
	return faults.get(id, {})


func condition(id: String) -> Dictionary:
	return conditions.get(id, {})


func maker_name(id: String) -> String:
	return makers.get(id, {}).get("name", id.capitalize())


static func applies_to(def: Dictionary, m: Dictionary) -> bool:
	## Fault/condition `types` filter: matches the model's type (laptop/desktop/server) or any of its tags (rack…).
	var types: Array = def.get("types", [])
	if types.is_empty():
		return true
	if m.get("type", "laptop") in types:
		return true
	for t in m.get("tags", []):
		if t in types:
			return true
	return false


func diag_stages(hw_type: String) -> Array:
	return diag_info.get("stages_" + hw_type, diag_info.get("stages", []))


func model_display_name(model_id: String) -> String:
	var m := model(model_id)
	if m.is_empty():
		return model_id
	return "%s %s" % [maker_name(m.maker), m.name]


func grade_mult(grade: String) -> float:
	return float(grade_value_mult.get(grade, 1.0))


func building(id: String) -> Dictionary:
	return buildings.get(id, {})


func fixture(id: String) -> Dictionary:
	return fixtures.get(id, {})
