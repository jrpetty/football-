class_name SimpleInteractable
extends StaticBody3D
## Small one-off interactables (kettle, roller door switch, posters…). Set `kind` in the inspector.

signal used(player: Node)

@export var kind: String = "kettle"     # kettle | door_switch | info
@export var title: String = "Kettle"
@export_multiline var info_text: String = ""
@export var target: Node3D              # e.g. the roller door

var _busy := false


func _ready() -> void:
	collision_layer = 1 | (1 << 3)


func hover_info(_player: Node) -> Dictionary:
	match kind:
		"kettle":
			var buff: bool = _player and _player.has_method("has_tea") and _player.has_tea()
			if _busy:
				return {"title": title, "lines": ["Boiling…"], "prompt": ""}
			return {"title": title, "lines": ["A brew puts a spring in your step for an hour." if not buff else "You've already got a cuppa on the go."],
				"hold": {"label": "Make a brew", "secs": 2.0, "action": "tea"}}
		"door_switch":
			var open: bool = target and target.has_method("is_open") and target.is_open()
			return {"title": title, "lines": [], "prompt": "[E] %s roller door" % ("Close" if open else "Open")}
	return {"title": title, "lines": [info_text], "prompt": ""}


func interact(player: Node) -> void:
	if kind == "door_switch" and target and target.has_method("request_toggle"):
		target.request_toggle()
	used.emit(player)


func hold_complete(player: Node, action: String) -> void:
	if action == "tea" and not _busy:
		_busy = true
		Sfx.play_at("kettle", global_position)
		await get_tree().create_timer(3.0).timeout
		Sfx.play_at("tea_pour", global_position, -3.0)
		await get_tree().create_timer(0.9).timeout
		_busy = false
		if is_instance_valid(player) and player.has_method("drink_tea"):
			player.drink_tea()
		elif is_instance_valid(player) and player.has_method("give_tea"):
			player.give_tea()
