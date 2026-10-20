extends Node
## Save / load. The host's GameState serialises to JSON; the world rebuilds from it.

const DIR := "user://saves/"
const AUTOSAVE := "autosave"

signal saved(slot: String)


func _ready() -> void:
	DirAccess.make_dir_recursive_absolute(DIR)


func path_for(slot: String) -> String:
	return DIR + slot + ".json"


func save_game(slot: String) -> Error:
	if not Game.is_host() or not Game.in_game:
		return ERR_UNAVAILABLE
	var d := Game.state.to_dict()
	d["saved_at"] = Time.get_datetime_string_from_system()
	d["label"] = "%s — Day %d, %s" % [Game.state.company_name, Game.state.day_index() + 1, GameState.format_money(Game.state.cash)]
	var f := FileAccess.open(path_for(slot), FileAccess.WRITE)
	if f == null:
		return FileAccess.get_open_error()
	f.store_string(JSON.stringify(d))
	f.close()
	saved.emit(slot)
	return OK


func autosave() -> void:
	if Game.is_host():
		save_game(AUTOSAVE)


func has_save(slot: String = AUTOSAVE) -> bool:
	return FileAccess.file_exists(path_for(slot))


func read_save(slot: String) -> Dictionary:
	if not has_save(slot):
		return {}
	var parsed: Variant = JSON.parse_string(FileAccess.get_file_as_string(path_for(slot)))
	return parsed if parsed is Dictionary else {}


func load_game(slot: String) -> bool:
	var d := read_save(slot)
	if d.is_empty():
		return false
	Game.load_from_dict(d)
	return true


func list_saves() -> Array:
	var out: Array = []
	var da := DirAccess.open(DIR)
	if da == null:
		return out
	for f in da.get_files():
		if f.ends_with(".json"):
			var slot := f.trim_suffix(".json")
			var d := read_save(slot)
			out.append({"slot": slot, "label": d.get("label", slot), "saved_at": d.get("saved_at", "")})
	return out
