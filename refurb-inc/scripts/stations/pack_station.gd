class_name PackStation
extends StationBase
## Packing table: put a SOLD unit here, hold E — box, foam, tape, label. Uses consumables.


func _station_ready() -> void:
	if station_type == "":
		station_type = "pack"


func hold_action(player: Node, it: Item) -> Dictionary:
	if it.kind != Item.KIND_UNIT:
		return {}
	var u := it.unit
	if u.order_id == 0:
		return {"blocked": "Not sold — nothing to pack."}
	for need in ["big_boxes" if u.is_large() else "boxes", "labels", "tape"]:
		if int(Game.state.consumables.get(need, 0)) <= 0:
			return {"blocked": "Out of %s — order more in the Shop." % ("large boxes" if need == "big_boxes" else need)}
	var o: Dictionary = Game.state.orders.get(u.order_id, {})
	var peer: int = player.peer_id if player and "peer_id" in player else Net.local_peer()
	var secs := float(Content.econ("pack_secs", 5.0)) / Game.state.skill_speed(Sim.player_key(peer), "packing")
	return {"label": "Pack order #%d" % u.order_id, "secs": secs, "action": "pack",
		"sub": "For %s · due %s" % [o.get("buyer", "?"), GameState.format_minute(float(o.get("due", 0)))]}


func hold_progress(active: bool) -> void:
	if active:
		Sfx.play_at("tape_gun", global_position + Vector3(0.4, 1.0, 0.2), -4.0, 0.15)


func hold_complete(_player: Node, action: String) -> void:
	if action == "pack":
		Game.request("pack", {"station": station_id})
		Sfx.play_at("label_print", global_position + Vector3(-0.6, 1.0, -0.2))


func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	var c := Game.state.consumables
	info.lines = ["Boxes %d · Large boxes %d · Labels %d · Tape %d" % [int(c.get("boxes", 0)), int(c.get("big_boxes", 0)), int(c.get("labels", 0)), int(c.get("tape", 0))],
		"Place a SOLD unit here, then hold [E] to pack it."]
	return info
