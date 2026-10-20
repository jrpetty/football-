class_name GradeStation
extends StationBase
## Grading desk: hold E on an imaged (or faulty, for spares) unit → grade A/B/C/D + a printed sticker.


func _station_ready() -> void:
	if station_type == "":
		station_type = "grade"


func _secs(player: Node) -> float:
	var peer: int = player.peer_id if player and "peer_id" in player else Net.local_peer()
	return float(Content.econ("grade_secs", 4.0)) / Game.state.skill_speed(Sim.player_key(peer), "grading")


func hold_action(player: Node, it: Item) -> Dictionary:
	if it.kind != Item.KIND_UNIT:
		return {}
	var u := it.unit
	if u.grade != "":
		return {}
	if not u.tested:
		return {"blocked": "Test it first."}
	if u.has_open_faults():
		return {"label": "Grade as D — Spares/Repairs", "secs": _secs(player), "action": "grade",
			"sub": "Unrepaired faults: sells cheap, but sells."}
	if not u.imaged:
		return {"blocked": "Wipe & image it before grading."}
	return {"label": "Grade unit", "secs": _secs(player), "action": "grade",
		"sub": "Expected: %s · ~£%d" % [UnitData.grade_label(Valuation.grade_for(u)), Valuation.market_value(u, Valuation.grade_for(u))]}


func hold_complete(_player: Node, action: String) -> void:
	if action == "grade":
		Game.request("grade", {"station": station_id})
		Sfx.play_at("label_print", global_position + Vector3(0.4, 1.0, 0))


func extra_lines(it: Item) -> Array:
	if it.unit and it.unit.grade != "":
		return ["Graded %s — put it on a shelf and list it in Flogit." % UnitData.grade_label(it.unit.grade)]
	return []


func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	info.lines = ["Place an imaged unit here, then hold [E] to grade it."]
	return info
