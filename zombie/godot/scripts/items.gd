class_name Items
## Item definitions, straight from the web game's tables (data/items.json).

static var _defs: Dictionary = {}

static func all() -> Dictionary:
	if _defs.is_empty():
		var f := FileAccess.open("res://data/items.json", FileAccess.READ)
		if f != null:
			_defs = JSON.parse_string(f.get_as_text())
	return _defs

static func def(id: String) -> Dictionary:
	var d = all().get(id)
	if d == null:
		return {"id": id, "name": id, "icon": "?", "cat": "misc", "weight": 0.1, "stack": 1, "desc": ""}
	return d

static func exists(id: String) -> bool:
	return all().has(id)
