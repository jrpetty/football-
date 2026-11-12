extends Node3D
## Rotates continuously about a local axis (wind turbine rotor, extractor fans, roundabout sign…).

@export var axis := Vector3.FORWARD
@export var rpm := 14.0


func _process(delta: float) -> void:
	rotate_object_local(axis.normalized(), delta * rpm / 60.0 * TAU)
