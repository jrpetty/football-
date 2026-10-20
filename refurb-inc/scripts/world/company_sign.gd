extends Label3D
## Signage that always shows the player's company name.

@export var caps := true
@export var suffix := ""


func _ready() -> void:
	Game.state_reset.connect(_update)
	_update()


func _update() -> void:
	var n: String = Game.state.company_name if Game.state else "Refurb Inc."
	text = (n.to_upper() if caps else n) + suffix
