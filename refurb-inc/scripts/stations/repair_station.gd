class_name RepairStation
extends StationBase
## Repair bench: hold E on the unit to fix its next fault. Needs the right part in the parts bins and the
## right Repair level. No mini-games — a progress bar fills while you hold.
## Once a unit has no faults left, the same hold fits an upgrade from the bins (more RAM, a bigger SSD) — the prompt
## shows the part's cost against what it adds to the value, and [R] picks between the options.

var _lamp: SpotLight3D
var _screwdriver_loop: AudioStreamPlayer3D
var _opt := 0                 # this PC's chosen upgrade option
var _upgrade_key := ""


func _station_ready() -> void:
	if station_type == "":
		station_type = "repair"
	_lamp = SpotLight3D.new()
	_lamp.position = Vector3(-0.55, 1.42, 0.08)
	_lamp.rotation_degrees = Vector3(-80, 0, 0)
	_lamp.light_color = Color(1.0, 0.92, 0.8)
	_lamp.light_energy = 2.2
	_lamp.spot_range = 1.4
	_lamp.spot_angle = 38
	_lamp.shadow_enabled = false
	add_child(_lamp)
	_screwdriver_loop = Sfx.make_loop("repair_loop", self, -8.0)
	_screwdriver_loop.position = Vector3(0, 1.0, 0.2)


func hold_action(player: Node, it: Item) -> Dictionary:
	if it.kind != Item.KIND_UNIT:
		return {}
	var peer: int = player.peer_id if player and "peer_id" in player else Net.local_peer()
	var pv: Dictionary = Game.sim.repair_preview(peer, it) if Game.sim else _client_preview(peer, it)
	if pv.get("can", false):
		return {"label": "Repair: %s" % pv.text, "secs": float(pv.secs), "action": "repair",
			"sub": "Uses %s (%d in bins)" % [pv.part_name, int(pv.have)]}
	if pv.get("done", false):
		var up := Upgrades.hold(Game.state, peer, it.unit, _opt)
		if not up.is_empty():
			_upgrade_key = str(up.key)
			return up
		return {}
	return {"label": "", "blocked": pv.get("why", "")}


func cycle_option() -> void:
	_opt += 1
	Sfx.play_ui("ui_click", -10.0)


func _client_preview(peer: int, it: Item) -> Dictionary:
	# Clients have no Sim; build a throwaway one over the replicated state for UI preview only.
	var tmp := Sim.new(Game.state)
	return tmp.repair_preview(peer, it)


func extra_lines(it: Item) -> Array:
	var out: Array = []
	if it.unit and it.unit.tested and not it.unit.has_open_faults():
		if not it.unit.imaged:
			out.append("All faults fixed — take it to a test bench with the PaneOS USB.")
		if Upgrades.blocked_reason(it.unit) == "" and Upgrades.options(Game.state, Net.local_peer(), it.unit).is_empty():
			out.append("No upgrades possible with the parts in the bins.")
	return out


func hold_progress(active: bool) -> void:
	if active and _screwdriver_loop.stream and not _screwdriver_loop.playing:
		_screwdriver_loop.play()
	elif not active:
		_screwdriver_loop.stop()


func hold_complete(_player: Node, action: String) -> void:
	if action == "repair":
		Game.request("repair", {"station": station_id})
		Sfx.play_at("repair_done", global_position + Vector3(0, 1, 0))
	elif action == "upgrade":
		Game.request("upgrade", {"station": station_id, "key": _upgrade_key})
		Sfx.play_at("repair_done", global_position + Vector3(0, 1, 0))


func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	var it := slot_item("unit")
	info.lines = ["Place a tested, faulty unit here.", "Hold [E] on the unit to repair (parts come from the bins).",
		"Fault-free units can have RAM or SSD upgrades fitted here."]
	if it:
		info.lines = ["On bench: " + it.display_name()]
	return info
