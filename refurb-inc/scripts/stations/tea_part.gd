class_name TeaPart
extends StaticBody3D
## One thing you can look at in the kitchen (kettle, cupboard, fridge, sink, worktop); forwards to TeaStation.

var part := "kettle"


func _ready() -> void:
	collision_layer = 1 | (1 << 3)


func _station() -> TeaStation:
	return get_parent() as TeaStation


func hover_info(player: Node) -> Dictionary:
	return _station().part_hover(part, player) if _station() else {}


func interact(player: Node) -> void:
	if _station():
		_station().part_interact(part, player)


func hold_complete(player: Node, action: String) -> void:
	if _station():
		_station().part_hold(part, player, action)


func slot_host_for_placing() -> Node:
	return _station() if part in ["tray", "sink"] else null
