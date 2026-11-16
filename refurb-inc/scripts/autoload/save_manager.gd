extends Node
## Save / load. The host's GameState serialises to JSON; the world rebuilds from it.

const DIR := "user://saves/"
const AUTOSAVE := "autosave"
const CHALLENGE := "challenge"     # challenges and daily runs save here, never over your main company

signal saved(slot: String)


func _ready() -> void:
	DirAccess.make_dir_recursive_absolute(DIR)


func _notification(what: int) -> void:
	# Closing the window mid-day keeps your progress.
	if what == NOTIFICATION_WM_CLOSE_REQUEST and Game.in_game and Game.is_host():
		save_game("manual")


func path_for(slot: String) -> String:
	return DIR + slot + ".json"


func save_game(slot: String) -> Error:
	if not Game.is_host() or not Game.in_game:
		return ERR_UNAVAILABLE
	if slot in [AUTOSAVE, "manual"] and not Game.state.challenge().is_empty():
		slot = CHALLENGE
	var d := Game.state.to_dict()
	d["saved_at"] = Time.get_datetime_string_from_system()
	d["label"] = "%s — Day %d, %s" % [Game.state.company_name, Game.state.day_index() + 1, GameState.format_money(Game.state.cash)]
	# Write to a temp file, keep the previous save as .bak, then swap it in: a crash mid-write can't corrupt a save.
	var path := path_for(slot)
	var f := FileAccess.open(path + ".tmp", FileAccess.WRITE)
	if f == null:
		return FileAccess.get_open_error()
	f.store_string(JSON.stringify(d))
	f.close()
	if FileAccess.file_exists(path):
		DirAccess.rename_absolute(path, path + ".bak")
	var err := DirAccess.rename_absolute(path + ".tmp", path)
	if err != OK:
		return err
	saved.emit(slot)
	return OK


func autosave() -> void:
	if Game.is_host():
		save_game(AUTOSAVE)


func has_save(slot: String = AUTOSAVE) -> bool:
	return FileAccess.file_exists(path_for(slot))


func read_save(slot: String) -> Dictionary:
	## Falls back to the previous copy (.bak) if the save is missing or damaged.
	for path in [path_for(slot), path_for(slot) + ".bak"]:
		if FileAccess.file_exists(path):
			var parsed: Variant = JSON.parse_string(FileAccess.get_file_as_string(path))
			if parsed is Dictionary and parsed.has("items"):
				return parsed
	return {}


func newest_slot() -> String:
	## The company to continue: whichever of the manual save and the autosave was written last.
	var best := ""
	var best_t := -1
	for slot in ["manual", AUTOSAVE]:
		if has_save(slot):
			var t := FileAccess.get_modified_time(path_for(slot))
			if t > best_t:
				best_t = t
				best = slot
	return best


func archive_current() -> void:
	## Starting a new company: keep the old saves as "previous_*" instead of Continue reopening them later.
	for slot in ["manual", AUTOSAVE]:
		if has_save(slot):
			DirAccess.rename_absolute(path_for(slot), path_for("previous_" + slot))
			if FileAccess.file_exists(path_for(slot) + ".bak"):
				DirAccess.rename_absolute(path_for(slot) + ".bak", path_for("previous_" + slot) + ".bak")


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
