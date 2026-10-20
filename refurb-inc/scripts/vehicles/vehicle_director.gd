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
		if "open_frac" in rear_door:
			tw.tween_property(rear_door, "open_frac", 1.0, 1.4).set_trans(Tween.TRANS_SINE)   # roller shutter
		else:
			tw.tween_property(rear_door, "position:y", rear_door.position.y + 2.2, 1.0)
		await tw.finished
	var drop_node: Node3D = dock.get_node_or_null("Drop")
	var drop: Vector3 = drop_node.global_position if drop_node else (pallet_drop.global_position if pallet_drop else park.origin - fwd * 4.5)
	# The host makes the pallets real (at their final spots); everyone then watches them come off the lorry.
	var lot_id := int(job.get("lot", 0))
	var rear_dir := -fwd
	if not is_dock:
		# Land clear of the lowered tail lift, then line further pallets up going away from the lorry.
		var clear := absf(CARGO_REAR) + 1.45 + 0.55
		var along := (drop - park.origin).dot(rear_dir)
		if along < clear:
			drop += rear_dir * (clear - along)
	var pallets: Array = []
	if Game.is_host() and Game.sim:
		pallets = Game.sim.deliver_lot(lot_id, drop, Vector3(rear_dir.x, 0, rear_dir.z).normalized() * 0.95)
	else:
		pallets = await _wait_for_lot_pallets(lot_id)
	await _unload(pallets, _lorry.global_transform, lift if not is_dock else null)
	if rear_door:
		var tw4 := create_tween()
		if "open_frac" in rear_door:
			tw4.tween_property(rear_door, "open_frac", 0.0, 1.2).set_trans(Tween.TRANS_SINE)
		else:
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


# ================================================================= unloading (visual)

const CARGO_FLOOR := 1.0      # lorry-local height of the cargo floor
const CARGO_REAR := -3.66     # lorry-local z of the rear sill
const LIFT_REST := 0.85
var _driver: Node3D


func _wait_for_lot_pallets(lot_id: int) -> Array:
	## Clients: the host's pallets arrive by replication a moment after the doors open.
	for i in 40:
		var found: Array = Game.state.items.values().filter(func(it): return it.kind == Item.KIND_PALLET and int(it.data.get("lot", 0)) == lot_id)
		if not found.is_empty():
			return found
		await get_tree().create_timer(0.1).timeout
	return []


func _make_driver() -> Node3D:
	## The lorry driver with a hand pallet truck (Jon's model), pulling pallets out of the back.
	var d := Node3D.new()
	d.name = "UnloadDriver"
	add_child(d)
	var truck_path := "res://assets/models/vehicles/pallet_truck/pallet_truck.tscn"
	if ResourceLoader.exists(truck_path):
		var truck: Node3D = load(truck_path).instantiate()
		truck.name = "PalletTruck"
		truck.position = Vector3(0, 0, -0.22)        # forks under the pallet, handle sticking out the back
		d.add_child(truck)
	var person := CharacterRig.new()
	person.name = "Driver"
	person.shirt = Color(0.12, 0.2, 0.42)
	person.trousers = Color(0.1, 0.1, 0.12)
	person.hi_vis = true
	person.cap = true
	person.anim = "push"
	person.position = Vector3(0, 0, -1.2)
	d.add_child(person)
	d.visible = false
	return d


func _cargo_xf(lorry_xf: Transform3D, k: int, n: int) -> Transform3D:
	# Pallets sit nose to tail down the middle of the box, long side along the lorry.
	var z := CARGO_REAR + 0.75 + float(n - 1 - k) * 1.28
	return lorry_xf * Transform3D(Basis(Vector3.UP, PI * 0.5), Vector3(0, CARGO_FLOOR, z))


func _unload(pallets: Array, lorry_xf: Transform3D, lift: Node3D) -> void:
	if pallets.is_empty():
		return
	if _driver == null:
		_driver = _make_driver()
	var lr := LevelRoot.current
	var views: Array = []
	for i in pallets.size():
		var it: Item = pallets[i]
		var v: ItemView = lr.item_views.get(it.id) if lr else null
		if v == null:
			await get_tree().process_frame
			v = lr.item_views.get(it.id) if lr else null
		views.append(v)
		if v:
			v.cinematic = true
			v.global_transform = _cargo_xf(lorry_xf, i, pallets.size())
	if lift:
		Sfx.play_at("hydraulic", lift.global_position)
		await _tween(lift, "position:y", CARGO_FLOOR - 0.03, 1.0)
	_driver.visible = true
	for i in pallets.size():
		var v: ItemView = views[i]
		var it: Item = pallets[i]
		if v == null or not is_instance_valid(v):
			continue
		var final_xf := v.state_transform(it)
		var sill := lorry_xf * Transform3D(Basis(Vector3.UP, PI * 0.5), Vector3(0, CARGO_FLOOR, CARGO_REAR - 0.72))
		if lift == null:
			sill = lorry_xf * Transform3D(Basis(Vector3.UP, PI * 0.5), Vector3(0, CARGO_FLOOR, CARGO_REAR - 0.6))
		# Driver walks in, slides the pallet back onto the lift…
		await _carry(v, v.global_transform, sill, lorry_xf, 1.1)
		if lift:
			Sfx.play_at("hydraulic", lift.global_position)
			var start_y := lift.position.y
			await _ride_lift(v, lift, start_y, 0.05, 1.8, lorry_xf)
		# …and wheels it to its spot.
		await _carry(v, v.global_transform, final_xf, lorry_xf, 1.0)
		Sfx.play_at("pallet_drop", final_xf.origin, -6.0)
		v.cinematic = false
		v.apply_state(it)
		if lift and i < pallets.size() - 1:
			_tween(lift, "position:y", CARGO_FLOOR - 0.03, 1.2)
	_driver.visible = false
	if lift:
		await _tween(lift, "position:y", LIFT_REST, 1.4)


func _carry(v: ItemView, from: Transform3D, to: Transform3D, lorry_xf: Transform3D, speed: float) -> void:
	## Move a pallet (and the driver pulling it) smoothly from one transform to another.
	var dist := from.origin.distance_to(to.origin)
	var dur := clampf(dist / speed, 0.6, 6.0)
	var t := 0.0
	var from_q := from.basis.get_rotation_quaternion()
	var to_q := to.basis.get_rotation_quaternion()
	while t < dur and is_instance_valid(v):
		t += get_process_delta_time()
		var f := smoothstep(0.0, 1.0, clampf(t / dur, 0.0, 1.0))
		var xf := Transform3D(Basis(from_q.slerp(to_q, f)), from.origin.lerp(to.origin, f))
		v.global_transform = xf
		_place_driver(xf, (to.origin - from.origin), lorry_xf)
		await get_tree().process_frame


func _ride_lift(v: ItemView, lift: Node3D, from_y: float, to_y: float, dur: float, lorry_xf: Transform3D) -> void:
	var t := 0.0
	var base := v.global_transform
	while t < dur and is_instance_valid(v):
		t += get_process_delta_time()
		var f := smoothstep(0.0, 1.0, clampf(t / dur, 0.0, 1.0))
		lift.position.y = lerpf(from_y, to_y, f)
		var xf := base
		xf.origin.y = lift.global_position.y + 0.035
		v.global_transform = xf
		_place_driver(xf, Vector3.ZERO, lorry_xf)
		await get_tree().process_frame


func _place_driver(pallet_xf: Transform3D, travel: Vector3, lorry_xf: Transform3D) -> void:
	# The driver stands behind the pallet (away from the lorry), feet on whatever the pallet is on.
	var back := -lorry_xf.basis.z.normalized()
	if travel.length() > 0.05:
		back = Vector3(travel.x, 0, travel.z).normalized()
	var basis := Basis.looking_at(back, Vector3.UP)     # driver's -Z side (handle end) points away from the pallet
	var prev := _driver.global_position
	_driver.global_transform = Transform3D(basis, pallet_xf.origin + back * 0.05)
	var rig := _driver.get_node_or_null("Driver") as CharacterRig
	if rig:
		var dt := maxf(get_process_delta_time(), 0.001)
		rig.move_speed = lerpf(rig.move_speed, prev.distance_to(_driver.global_position) / dt, 0.25)


func _tween(n: Object, prop: String, to: Variant, dur: float) -> void:
	var tw := create_tween()
	tw.tween_property(n, prop, to, dur).set_trans(Tween.TRANS_SINE)
	await tw.finished


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
