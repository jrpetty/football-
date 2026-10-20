class_name VehicleDirector
extends Node3D
## Animates the delivery lorry and courier van in response to Game.vehicle_event.
## The HOST performs the real state change (pallet appears / parcels collected) at the right moment in the
## animation; clients only play the animation. Visits queue up so two deliveries never overlap.

@export var lorry_model: ModelSlot
@export var van_model: ModelSlot
@export var road_z := 15.0
@export var lorry_park := Vector3(0, 0, 9.5)
@export var van_park := Vector3(1.5, 0, 13.4)
@export var pallet_drop: Node3D
@export var parcel_drop: Node3D

var _lorry_queue: Array = []
var _van_queue: Array = []
var _lorry_busy := false
var _van_busy := false
var _lorry: Node3D
var _van: Node3D
var _lorry_engine: AudioStreamPlayer3D
var _lorry_beep: AudioStreamPlayer3D
var _van_engine: AudioStreamPlayer3D


func _ready() -> void:
	if lorry_model == null and has_node("Lorry/Model"):
		lorry_model = get_node("Lorry/Model")
	if van_model == null and has_node("CourierVan/Model"):
		van_model = get_node("CourierVan/Model")
	_lorry = lorry_model.get_parent() if lorry_model else null
	_van = van_model.get_parent() if van_model else null
	if _lorry:
		_lorry.visible = false
		_lorry_engine = Sfx.make_loop("diesel_idle", _lorry, -2.0)
		_lorry_beep = Sfx.make_loop("reverse_beep", _lorry, -4.0)
		_lorry_beep.position = Vector3(0, 1.0, -3.5)
	if _van:
		_van.visible = false
		_van_engine = Sfx.make_loop("van_engine", _van, -6.0)
	Game.vehicle_event.connect(_on_event)


func _on_event(kind: String, payload: Dictionary) -> void:
	match kind:
		"lorry":
			_lorry_queue.append(payload)
			if not _lorry_busy:
				_run_lorry()
		"parcel", "courier":
			_van_queue.append({"kind": kind, "data": payload})
			if not _van_busy:
				_run_van()


# ================================================================= lorry

func _run_lorry() -> void:
	if _lorry_queue.is_empty() or _lorry == null:
		_lorry_busy = false
		return
	_lorry_busy = true
	var job: Dictionary = _lorry_queue.pop_front()
	var start := Vector3(48, 0, road_z)
	var hold := Vector3(-7, 0, road_z)
	_lorry.visible = true
	_lorry.global_position = start
	_lorry.rotation.y = -PI / 2
	if _lorry_engine.stream:
		_lorry_engine.play()
	await _drive_line(_lorry, start, hold, 11.0, true)
	await get_tree().create_timer(0.6).timeout
	if _lorry_beep.stream:
		_lorry_beep.play()
	await _drive_curve(_lorry, hold, Vector3(lorry_park.x, 0, road_z), lorry_park, 7.5, false)
	_lorry_beep.stop()
	Sfx.play_at("air_brake", _lorry.global_position, -2.0)
	await get_tree().create_timer(0.8).timeout
	# Tail lift down
	var lift: Node3D = lorry_model.find_part("TailLift") as Node3D
	var door: Node3D = lorry_model.find_part("RearDoor") as Node3D
	if door:
		var tw := create_tween()
		tw.tween_property(door, "position:y", door.position.y + 2.2, 1.0)
		await tw.finished
	if lift:
		Sfx.play_at("hydraulic", lift.global_position)
		var tw2 := create_tween()
		tw2.tween_property(lift, "position:y", 0.05, 2.2).set_trans(Tween.TRANS_SINE)
		await tw2.finished
	# The pallet "rolls off" the lift — host creates it in the world now.
	if Game.is_host() and Game.sim:
		var drop := pallet_drop.global_position if pallet_drop else lorry_park + Vector3(0, 0, -4.5)
		Game.sim.deliver_lot(int(job.get("lot", 0)), drop)
	Sfx.play_at("pallet_drop", pallet_drop.global_position if pallet_drop else _lorry.global_position)
	await get_tree().create_timer(1.6).timeout
	if lift:
		var tw3 := create_tween()
		tw3.tween_property(lift, "position:y", 0.85, 2.0)
		await tw3.finished
	if door:
		var tw4 := create_tween()
		tw4.tween_property(door, "position:y", door.position.y - 2.2, 0.8)
		await tw4.finished
	await get_tree().create_timer(0.5).timeout
	await _drive_curve(_lorry, lorry_park, Vector3(lorry_park.x, 0, road_z), Vector3(-10, 0, road_z), 6.0, true)
	await _drive_line(_lorry, Vector3(-10, 0, road_z), Vector3(-55, 0, road_z), 6.0, true)
	_lorry_engine.stop()
	_lorry.visible = false
	_run_lorry()


# ================================================================= courier van

func _run_van() -> void:
	if _van_queue.is_empty() or _van == null:
		_van_busy = false
		return
	_van_busy = true
	var start := Vector3(48, 0, road_z - 1.4)
	_van.visible = true
	_van.global_position = start
	_van.rotation.y = -PI / 2
	if _van_engine.stream:
		_van_engine.play()
	await _drive_line(_van, start, van_park, 6.0, true)
	_van_engine.volume_db = -14.0
	Sfx.play_at("van_door", _van.global_position)
	await get_tree().create_timer(2.5).timeout
	# Handle everything that queued up while the van was on its way.
	while not _van_queue.is_empty():
		var job: Dictionary = _van_queue.pop_front()
		if Game.is_host() and Game.sim:
			if job.kind == "parcel":
				var p := parcel_drop.global_position if parcel_drop else Vector3(1.5, 0.3, 4.3)
				Game.sim.deliver_parcel(job.data, p)
			elif job.kind == "courier":
				Game.sim.courier_collect()
		await get_tree().create_timer(1.0).timeout
	Sfx.play_at("van_door", _van.global_position)
	await get_tree().create_timer(1.0).timeout
	_van_engine.volume_db = -6.0
	await _drive_line(_van, van_park, Vector3(-55, 0, road_z - 1.4), 4.5, true)
	_van_engine.stop()
	_van.visible = false
	_run_van()


# ================================================================= motion helpers

func _drive_line(v: Node3D, a: Vector3, b: Vector3, secs: float, forward: bool) -> void:
	await _drive_curve(v, a, (a + b) * 0.5, b, secs, forward)


func _drive_curve(v: Node3D, a: Vector3, c: Vector3, b: Vector3, secs: float, forward: bool) -> void:
	## Quadratic Bezier; vehicle faces along travel (or away from it when reversing). Eases in/out.
	var t := 0.0
	while t < 1.0:
		var dt := get_process_delta_time()
		t = minf(t + dt / secs, 1.0)
		var e := t * t * (3.0 - 2.0 * t)
		var p := a.lerp(c, e).lerp(c.lerp(b, e), e)
		var e2 := minf(e + 0.01, 1.0)
		var p2 := a.lerp(c, e2).lerp(c.lerp(b, e2), e2)
		var dir := p2 - p
		if dir.length() > 0.0001:
			var yaw := atan2(dir.x, dir.z)
			if not forward:
				yaw += PI
			v.rotation.y = lerp_angle(v.rotation.y, yaw, 0.35)
		v.global_position = p
		await get_tree().process_frame
