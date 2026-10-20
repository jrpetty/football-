class_name VehicleDirector
extends Node3D
## Animates the delivery lorry and courier van in response to Game.vehicle_event.
## The HOST performs the real state change (pallet appears / parcels collected) at the right moment in the
## animation; clients only play the animation. Visits queue up so deliveries never overlap.
##
## Route markers (children of this node, all optional except Docks):
##   LorryIn/<points…>   driven forward, last point = where it starts reversing
##   Docks/<Marker3D…>   parked lorry transform (cab = +Z, rear = −Z). Meta: dock (bool) = level dock (no tail lift),
##                       door (NodePath) = RollerDoor to open. Child "Drop" = where the pallet lands.
##   LorryOut/<points…>  driven forward after pulling out
##   VanIn/<points…>, VanPark (Marker3D), VanOut/<points…>

@export var pallet_drop: Node3D
@export var parcel_drop: Node3D
@export var lorry_speed := 7.0
@export var van_speed := 8.0

var lorry_model: ModelSlot
var van_model: ModelSlot
var _lorry_queue: Array = []
var _van_queue: Array = []
var _lorry_busy := false
var _van_busy := false
var _lorry: Node3D
var _van: Node3D
var _lorry_engine: AudioStreamPlayer3D
var _lorry_beep: AudioStreamPlayer3D
var _van_engine: AudioStreamPlayer3D
var _dock_i := 0


func _ready() -> void:
	if has_node("Lorry/Model"):
		lorry_model = get_node("Lorry/Model")
	if has_node("CourierVan/Model"):
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


func _points(group: String) -> Array:
	var out: Array = []
	var n := get_node_or_null(group)
	if n:
		for c in n.get_children():
			if c is Node3D:
				out.append((c as Node3D).global_position)
	return out


func _next_dock() -> Marker3D:
	var docks := get_node_or_null("Docks")
	if docks == null or docks.get_child_count() == 0:
		return null
	var d: Marker3D = docks.get_child(_dock_i % docks.get_child_count())
	_dock_i += 1
	return d


# ================================================================= lorry

func _run_lorry() -> void:
	if _lorry_queue.is_empty() or _lorry == null:
		_lorry_busy = false
		return
	_lorry_busy = true
	var job: Dictionary = _lorry_queue.pop_front()
	var dock := _next_dock()
	var route_in := _points("LorryIn")
	if dock == null or route_in.size() < 2:
		# No routes in this level: deliver instantly.
		if Game.is_host() and Game.sim:
			Game.sim.deliver_lot(int(job.get("lot", 0)), pallet_drop.global_position if pallet_drop else Vector3.ZERO)
		_run_lorry()
		return
	var is_dock: bool = dock.get_meta("dock", false)
	var door: Node = get_node_or_null(dock.get_meta("door", NodePath(""))) if dock.has_meta("door") else null
	_lorry.visible = true
	_lorry.global_position = route_in[0]
	_lorry.rotation.y = atan2(route_in[1].x - route_in[0].x, route_in[1].z - route_in[0].z)
	if _lorry_engine.stream:
		_lorry_engine.play()
	await _drive_path(_lorry, route_in, lorry_speed, true)
	await get_tree().create_timer(0.6).timeout
	if _lorry_beep.stream:
		_lorry_beep.play()
	if door and door.has_method("set_open"):
		door.set_open(true)
	var park := dock.global_transform
	var fwd := park.basis.z.normalized()
	var start: Vector3 = route_in.back()
	var ctrl := park.origin + fwd * maxf(5.0, start.distance_to(park.origin) * 0.55)
	await _drive_curve(_lorry, start, ctrl, park.origin, start.distance_to(park.origin) / (lorry_speed * 0.45), false)
	_lorry.global_transform = Transform3D(park.basis, park.origin)
	_lorry_beep.stop()
	Sfx.play_at("air_brake", _lorry.global_position, -2.0)
	await get_tree().create_timer(0.8).timeout
	var lift: Node3D = lorry_model.find_part("TailLift") as Node3D
	var rear_door: Node3D = lorry_model.find_part("RearDoor") as Node3D
	if rear_door:
		var tw := create_tween()
		tw.tween_property(rear_door, "position:y", rear_door.position.y + 2.2, 1.0)
		await tw.finished
	if lift and not is_dock:
		Sfx.play_at("hydraulic", lift.global_position)
		var tw2 := create_tween()
		tw2.tween_property(lift, "position:y", 0.05, 2.2).set_trans(Tween.TRANS_SINE)
		await tw2.finished
	var drop_node: Node3D = dock.get_node_or_null("Drop")
	var drop: Vector3 = drop_node.global_position if drop_node else (pallet_drop.global_position if pallet_drop else park.origin - fwd * 4.5)
	if Game.is_host() and Game.sim:
		Game.sim.deliver_lot(int(job.get("lot", 0)), drop)
	Sfx.play_at("pallet_drop", drop)
	await get_tree().create_timer(1.6).timeout
	if lift and not is_dock:
		var tw3 := create_tween()
		tw3.tween_property(lift, "position:y", 0.85, 2.0)
		await tw3.finished
	if rear_door:
		var tw4 := create_tween()
		tw4.tween_property(rear_door, "position:y", rear_door.position.y - 2.2, 0.8)
		await tw4.finished
	await get_tree().create_timer(0.5).timeout
	var out := _points("LorryOut")
	if out.is_empty():
		out = [route_in.back(), route_in.front()]
	var pull_ctrl := park.origin + fwd * maxf(5.0, park.origin.distance_to(out[0]) * 0.55)
	await _drive_curve(_lorry, park.origin, pull_ctrl, out[0], park.origin.distance_to(out[0]) / (lorry_speed * 0.6), true)
	if door and door.has_method("set_open"):
		door.set_open(false)
	await _drive_path(_lorry, out, lorry_speed, true)
	_lorry_engine.stop()
	_lorry.visible = false
	_run_lorry()


# ================================================================= courier van

func _run_van() -> void:
	if _van_queue.is_empty() or _van == null:
		_van_busy = false
		return
	_van_busy = true
	var route_in := _points("VanIn")
	var park_node: Node3D = get_node_or_null("VanPark")
	if route_in.size() < 2 or park_node == null:
		while not _van_queue.is_empty():
			_van_job(_van_queue.pop_front())
		_van_busy = false
		return
	_van.visible = true
	_van.global_position = route_in[0]
	_van.rotation.y = atan2(route_in[1].x - route_in[0].x, route_in[1].z - route_in[0].z)
	if _van_engine.stream:
		_van_engine.play()
	var path := route_in.duplicate()
	path.append(park_node.global_position)
	await _drive_path(_van, path, van_speed, true)
	_van.global_transform = park_node.global_transform
	_van_engine.volume_db = -14.0
	Sfx.play_at("van_door", _van.global_position)
	await get_tree().create_timer(2.5).timeout
	while not _van_queue.is_empty():
		_van_job(_van_queue.pop_front())
		await get_tree().create_timer(1.0).timeout
	Sfx.play_at("van_door", _van.global_position)
	await get_tree().create_timer(1.0).timeout
	_van_engine.volume_db = -6.0
	var outs := _points("VanOut")
	if outs.is_empty():
		outs = [route_in[0]]
	var out := [park_node.global_position]
	if park_node.get_meta("reverse_out", false):
		# Back out of the bay first, then drive off forwards.
		var fwd := park_node.global_basis.z.normalized()
		var p0 := park_node.global_position
		await _drive_curve(_van, p0, p0 - fwd * 4.0, outs[0], p0.distance_to(outs[0]) / (van_speed * 0.4), false)
		out = []
	out.append_array(outs)
	await _drive_path(_van, out, van_speed, true)
	_van_engine.stop()
	_van.visible = false
	_run_van()


func _van_job(job: Dictionary) -> void:
	if not (Game.is_host() and Game.sim):
		return
	if job.kind == "parcel":
		var p := parcel_drop.global_position if parcel_drop else Vector3(1.5, 0.3, 4.3)
		Game.sim.deliver_parcel(job.data, p)
	elif job.kind == "courier":
		Game.sim.courier_collect()


# ================================================================= motion helpers

func _drive_path(v: Node3D, pts: Array, speed: float, forward: bool) -> void:
	## Smooth path through points: straight between corner midpoints, quadratic Bezier around corners.
	if pts.size() < 2:
		return
	var segs: Array = []
	var cur: Vector3 = pts[0]
	for i in range(1, pts.size() - 1):
		var a: Vector3 = (pts[i - 1] + pts[i]) * 0.5 if i > 1 else pts[0]
		var b: Vector3 = (pts[i] + pts[i + 1]) * 0.5
		segs.append([cur, (cur + a) * 0.5, a])
		segs.append([a, pts[i], b])
		cur = b
	segs.append([cur, (cur + pts.back()) * 0.5, pts.back()])
	for s in segs:
		var length := (s[0] as Vector3).distance_to(s[1]) + (s[1] as Vector3).distance_to(s[2])
		if length < 0.01:
			continue
		await _drive_curve(v, s[0], s[1], s[2], length / speed, forward, false)


func _drive_curve(v: Node3D, a: Vector3, c: Vector3, b: Vector3, secs: float, forward: bool, ease_ends: bool = true) -> void:
	var t := 0.0
	secs = maxf(secs, 0.2)
	while t < 1.0:
		var dt := get_process_delta_time()
		t = minf(t + dt / secs, 1.0)
		var e := t * t * (3.0 - 2.0 * t) if ease_ends else t
		var p := a.lerp(c, e).lerp(c.lerp(b, e), e)
		var e2 := minf(e + 0.01, 1.0)
		var p2 := a.lerp(c, e2).lerp(c.lerp(b, e2), e2)
		var dir := p2 - p
		dir.y = 0.0
		if dir.length() > 0.0001:
			var yaw := atan2(dir.x, dir.z)
			if not forward:
				yaw += PI
			v.rotation.y = lerp_angle(v.rotation.y, yaw, 0.35)
		v.global_position = p
		await get_tree().process_frame
