extends Label3D
## Signage that always shows the player's company name.

@export var caps := true
@export var suffix := ""


func _ready() -> void:
	Game.state_reset.connect(_update)
	_update()


func _on_changed(kind: String, _id: Variant) -> void:
	if kind == "company":
		_update()


func _update() -> void:
	# The state object is replaced on a new game / load / join, so (re)connect to whichever one is live.
	if Game.state and not Game.state.changed.is_connected(_on_changed):
		Game.state.changed.connect(_on_changed)
	var n: String = Game.state.company_name if Game.state else "Refurb Inc."
	text = (n.to_upper() if caps else n) + suffix
