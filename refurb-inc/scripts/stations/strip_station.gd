class_name StripStation
extends StationBase
## Strip-down bench: hold E on a tested unit to take it apart. Working parts (RAM, drives, battery, screen, PSU,
## board…) go into the parts bins as donor stock; the empty shell goes for scrap. For units that cost more to fix
## than they'd sell for, or to feed repairs and server builds.

var _loop: AudioStreamPlayer3D


func _station_ready() -> void:
	if station_type == "":
		station_type = "strip"
	_loop = Sfx.make_loop("repair_loop", self, -8.0)
	_loop.position = Vector3(0, 1.0, 0.2)
	var sign := Placeholders.text(self, "STRIP-DOWN", Vector3(0, 1.62, -0.3), 44, Color(1.0, 0.72, 0.25), Vector3.ZERO, 0.0013)
	sign.shaded = false
	sign.outline_size = 8
	sign.outline_modulate = Color(0.12, 0.06, 0.0)
	# Sorting trays along the back of the bench for the parts that come out.
	var tray_mat := Mats.color(Color(0.85, 0.42, 0.12), 0.6)
	for i in 4:
		var tray := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = Vector3(0.3, 0.07, 0.2)
		tray.mesh = bm
		tray.material_override = tray_mat
		tray.position = Vector3(-0.5 + i * 0.33, 0.97, -0.25)
		add_child(tray)


func hold_action(player: Node, it: Item) -> Dictionary:
	if it.kind != Item.KIND_UNIT:
		return {}
	var peer: int = player.peer_id if player and "peer_id" in player else Net.local_peer()
	var pv: Dictionary = (Game.sim if Game.sim else Sim.new(Game.state)).strip_preview(peer, it)
	if not pv.get("can", false):
		return {"label": "", "blocked": pv.get("why", "")}
	return {"label": "Strip for parts", "secs": float(pv.secs), "action": "strip", "sub": "Saves: %s" % pv.text}


func extra_lines(it: Item) -> Array:
	if it.unit == null or not it.unit.tested:
		return []
	var n := StripDown.donor_parts(it.unit).size()
	return ["%d working part%s inside — hold [E] to strip it (the unit is gone for good)." % [n, "" if n == 1 else "s"]]


func hold_progress(active: bool) -> void:
	if active and _loop.stream and not _loop.playing:
		_loop.play()
	elif not active:
		_loop.stop()


func hold_complete(_player: Node, action: String) -> void:
	if action == "strip":
		Game.request("strip", {"station": station_id})
		Sfx.play_at("repair_done", global_position + Vector3(0, 1, 0))


func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	var it := slot_item("unit")
	info.lines = ["Put a tested unit here that isn't worth fixing.", "Hold [E] to strip it: working parts go to the bins."]
	if it:
		info.lines = ["On bench: " + it.display_name()]
	return info
