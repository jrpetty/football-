class_name RollerDoor
extends Node3D
## Roller shutter. Open/close is cosmetic world state, host-owned and mirrored to clients.

@export var width: float = 4.2
@export var height: float = 2.25
@export var start_open: bool = true

var _open := true
var _curtain: Node3D
var _blocker: CollisionShape3D
var _tween: Tween


func _ready() -> void:
	_open = start_open
	var steel := Mats.painted_metal(Color(0.78, 0.8, 0.82))
	# Drum housing above the opening
	Placeholders.box(self, Vector3(width + 0.2, 0.36, 0.42), Vector3(0, height + 0.18, -0.12), Mats.painted_metal(Color(0.6, 0.62, 0.64)))
	for sx in [-1, 1]:
		Placeholders.box(self, Vector3(0.08, height, 0.1), Vector3(sx * (width / 2 + 0.04), height / 2, 0), Mats.painted_metal(Color(0.55, 0.57, 0.6)))
	_curtain = Node3D.new()
	add_child(_curtain)
	var slats := int(height / 0.075)
	for i in slats:
		Placeholders.box(_curtain, Vector3(width, 0.07, 0.025), Vector3(0, -0.04 - i * 0.075, 0), steel)
	Placeholders.box(_curtain, Vector3(width, 0.05, 0.05), Vector3(0, -height + 0.02, 0), Mats.color(Color(0.1, 0.1, 0.1), 0.7))
	var body := StaticBody3D.new()
	body.collision_layer = 1
	_blocker = CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = Vector3(width, height, 0.1)
	_blocker.shape = bs
	_blocker.position = Vector3(0, height / 2, 0)
	body.add_child(_blocker)
	add_child(body)
	_apply(false)


func is_open() -> bool:
	return _open


func request_toggle() -> void:
	if Game.is_host():
		_set_open(not _open)
	else:
		_rpc_request_toggle.rpc_id(1)


@rpc("any_peer", "call_remote", "reliable")
func _rpc_request_toggle() -> void:
	if Game.is_host():
		_set_open(not _open)


func set_open(v: bool) -> void:
	## Host-side programmatic open/close (e.g. a lorry docking).
	if Game.is_host() and v != _open:
		_set_open(v)


func _set_open(v: bool) -> void:
	_open = v
	_apply(true)
	if Net.is_online():
		_rpc_state.rpc(v)


@rpc("authority", "call_remote", "reliable")
func _rpc_state(v: bool) -> void:
	_open = v
	_apply(true)


func _apply(animate: bool) -> void:
	# Curtain hangs from the top of the opening; "rolling up" squashes it into the drum.
	_curtain.position = Vector3(0, height, 0)
	var sy := 0.04 if _open else 1.0
	if _tween:
		_tween.kill()
	_blocker.disabled = _open
	if animate:
		Sfx.play_at("roller_door", global_position + Vector3(0, height, 0))
		_tween = create_tween().set_trans(Tween.TRANS_SINE).set_ease(Tween.EASE_IN_OUT)
		_tween.tween_property(_curtain, "scale:y", sy, 3.2)
	else:
		_curtain.scale.y = sy
