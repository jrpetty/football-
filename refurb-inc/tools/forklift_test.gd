extends Node
## Drives the real forklift with the real player controls (simulated keys) in a real level, headless:
## get in, drive up to a pallet, slide the forks under it, raise it, carry it to pallet racking, lift it to level 2,
## drive the load into the bay (walls / racking block the truck), lower it onto the beam, back out and get out.
##   godot --headless --path . res://tools/forklift_test.tscn

var _fails: PackedStringArray = []
var _level: LevelRoot


func _check(c: bool, m: String) -> void:
	print("  %s %s" % ["✔" if c else "✖", m])
	if not c:
		_fails.append(m)


func _frames(n: int) -> void:
	for i in n:
		await get_tree().physics_frame


func _hold(action: String, until: Callable, max_secs: float) -> float:
	## Hold a control until a condition holds (or time runs out); returns the seconds it took.
	Input.action_press(action)
	var t := 0.0
	while t < max_secs and not until.call():
		await get_tree().physics_frame
		t += get_physics_process_delta_time()
	Input.action_release(action)
	await _frames(2)
	return t


func _ready() -> void:
	Engine.max_fps = 0
	Game.new_game(4242)
	var s := Game.state
	s.building_id = "industrial"
	_level = load(load("res://scripts/game_root.gd").level_path("industrial")).instantiate()
	add_child(_level)
	await _frames(10)
	for id in s.fixtures.keys():
		s.fixtures[id].placed = false
		s.mark("fixture", id)
	for it: Item in s.items.values():
		if it.loc.get("t") == "loose":
			s.set_loc(it, {"t": "loose", "p": [5.0, 0.0, -8.0], "r": [0, 0, 0, 1]})
	# Racking at the back, facing into the unit; a pallet of laptops on the floor; the forklift facing them.
	s.fixtures["rack"] = {"type": "pallet_rack", "label": "Racking R1", "placed": true, "p": [0.0, 0.0, -7.0], "r": 0.0}
	s.mark("fixture", "rack")
	await _frames(4)
	var face := Basis(Vector3.UP, PI)                         # forks (model +Z) point to world -Z
	var pallet := s.new_item(Item.KIND_PALLET)
	var lay := Sim.pallet_layout("kestrel_swift_14")
	pallet.data = {"cell": lay.cell, "grid": lay.grid}
	var pq := (face * Basis(Vector3.UP, PI * 0.5)).get_rotation_quaternion()   # long side along the forks
	s.set_loc(pallet, {"t": "loose", "p": [0.0, 0.0, -2.0], "r": [pq.x, pq.y, pq.z, pq.w]})
	for k in 6:
		var u := s.new_item(Item.KIND_UNIT)
		u.unit = Game.sim.lots.roll_unit("kestrel_swift_14", 0.5)
		s.set_loc(u, {"t": "slot", "slot": "i%d:%d" % [pallet.id, k]})
	var fl := s.new_item(Item.KIND_FORKLIFT)
	fl.data = {"h": 0.0}
	var fq := face.get_rotation_quaternion()
	s.set_loc(fl, {"t": "loose", "p": [0.0, 0.0, 2.6], "r": [fq.x, fq.y, fq.z, fq.w]})
	await _frames(6)
	var p: Player = _level.local_player()
	_check(p != null, "local player spawned")
	if p == null:
		_finish()
		return
	# Get in.
	Game.request("forklift_enter", {"item": fl.id})
	await _frames(12)
	_check(p.is_driving(), "sat in the forklift")
	_check(p.global_position.distance_to(Vector3(0, 0, 2.6 + absf(Logistics.FL_SEAT.z))) < 0.3, "in the seat (%s)" % str(p.global_position))
	# Drive at the pallet until the forks are under it.
	var model := func() -> Transform3D: return p.global_transform * Logistics.drive_xf()
	# Drive the forks in until the pallet stops the truck against the backrest.
	var t := await _hold("move_forward", func(): return (model.call() as Transform3D).origin.z < 0.0 and absf(p._drive_v) < 0.02, 8.0)
	_check(not p._fork_target_pallet(model.call()).is_empty(), "forks under the pallet after %.1f s" % t)
	# Raise: it comes up on the forks.
	await _hold("fork_up", func(): return pallet.loc.get("t") == "on_forks" and p.fork_h > 1.62, 10.0)
	_check(pallet.loc.get("t") == "on_forks", "pallet lifted onto the forks")
	_check(p.fork_h > 1.55, "forks raised above level 2 (%.2f m)" % p.fork_h)
	var pv: ItemView = _level.item_views.get(pallet.id)
	var fork_point_y: float = (model.call() as Transform3D).origin.y + p.fork_h
	_check(pv != null and absf(pv.global_position.y - fork_point_y) < 0.08, "pallet rides the forks (y %.2f)" % (pv.global_position.y if pv else -1.0))
	# Drive into the racking: the truck stops against the beams, the load over the bay.
	t = await _hold("move_forward", func(): return Logistics.find_set_down(s, (model.call() * Logistics.pallet_on_forks(p.fork_h, float(pallet.loc.get("yaw", 0.0)))).origin).has("slot") and absf(p._drive_v) < 0.01 and (model.call() as Transform3D).origin.z < -3.0, 12.0)
	var z_stop := (model.call() as Transform3D).origin.z
	_check(z_stop > -6.2 and z_stop < -5.0, "stopped by the racking with the load in the bay (truck at z %.2f, %.1f s)" % [z_stop, t])
	if not (z_stop > -6.2 and z_stop < -5.0):
		_debug_block(p, model.call(), pallet)
	_check(p.fork_h < 1.7, "forks stopped when R was let go (%.2f m)" % p.fork_h)
	# Lower it onto the level-2 beam.
	await _hold("fork_down", func(): return pallet.loc.get("t") == "slot", 8.0)
	_check(str(pallet.loc.get("slot", "")) == "rack:b1l1", "set down in Racking R1 · level 2 · bay 2 (%s)" % str(pallet.loc))
	var u0: Item = Logistics.units_on(s, pallet.id)[0]
	_check(Logistics.unit_location(s, u0) == "Racking R1 · level 2 · bay 2", "Stock app location: %s" % Logistics.unit_location(s, u0))
	await _hold("fork_down", func(): return p.fork_h < 1.2, 6.0)
	_check(pallet.loc.get("t") == "slot", "forks come away, the pallet stays on the beam")
	# Back out and get out.
	await _hold("move_back", func(): return (model.call() as Transform3D).origin.z > -2.0, 8.0)
	await _hold("move_forward", func(): return absf(p._drive_v) < 0.05, 2.0)
	_check((model.call() as Transform3D).origin.z > -2.6, "reversed out of the racking (z %.2f)" % (model.call() as Transform3D).origin.z)
	p._climb_out()
	await _frames(10)
	_check(not p.is_driving() and fl.loc.get("t") == "loose", "climbed out; forklift parked")
	_check(absf(float(fl.data.get("h", -1.0)) - p.fork_h) < 0.05 or float(fl.data.get("h", 0.0)) < 1.25, "fork height kept (%.2f)" % float(fl.data.get("h", -1.0)))
	# Walls stop it: drive into the side wall.
	Game.request("forklift_enter", {"item": fl.id})
	await _frames(12)
	var before := p.global_position
	var yaw0 := p.rotation.y
	Input.action_press("move_left")
	await _hold("move_forward", func(): return false, 1.1)
	Input.action_release("move_left")
	var turned := rad_to_deg(wrapf(p.rotation.y - yaw0, -PI, PI))
	await _hold("move_forward", func(): return false, 6.0)
	var x := p.global_position.x
	_check(turned > 45.0, "rear-wheel steering turns it left (%.0f°)" % turned)
	if turned <= 45.0:
		print("     driving=%s v=%.2f pos=%s fl=%s" % [p.is_driving(), p._drive_v, str(p.global_position), str(fl.loc)])
		_debug_block(p, model.call(), pallet)
	_check(p.is_driving() and x > -5.9 and p.global_position.distance_to(before) > 1.0, "drove off, and the wall stopped it (x %.2f)" % x)
	var tips: Vector3 = model.call() * Vector3(Logistics.FL_FORK_X, 0, Logistics.FL_HEEL_Z + Logistics.FL_FORK_LEN)
	_check(tips.x > -6.05, "fork tips stop at the wall, not through it (tips x %.2f)" % tips.x)
	_finish()


func _debug_block(p: Player, model: Transform3D, load_it: Item) -> void:
	## What (if anything) is in the way of the truck one step further on — printed for diagnosis.
	var space := p.get_world_3d().direct_space_state
	var step := model.translated(model.basis.z * 0.05)
	for what in ["body", "load"]:
		var q := PhysicsShapeQueryParameters3D.new()
		var b := BoxShape3D.new()
		if what == "body":
			b.size = Vector3(Logistics.FL_HALF_W * 2.0 - 0.04, 1.9, Logistics.FL_FRONT_Z - Logistics.FL_BACK_Z - 0.04)
			q.transform = step * Transform3D(Basis.IDENTITY, Vector3(0, 1.05, (Logistics.FL_FRONT_Z + Logistics.FL_BACK_Z) * 0.5))
			q.collision_mask = 1 | (1 << 2)
		else:
			b.size = Vector3(Logistics.PALLET.x - 0.1, 0.9, Logistics.PALLET.z - 0.1)
			q.transform = step * Logistics.pallet_on_forks(p.fork_h, float(load_it.loc.get("yaw", 0.0))) * Transform3D(Basis.IDENTITY, Vector3(0, 0.51, 0))
			q.collision_mask = 1
		q.shape = b
		q.exclude = [p.get_rid()]
		print("     %s query box %s at %s" % [what, str(b.size), str(q.transform.origin)])
		for hit in space.intersect_shape(q, 8):
			var n: Node = hit.collider
			var detail := ""
			if n is CollisionObject3D:
				var owner_node = (n as CollisionObject3D).shape_owner_get_owner((n as CollisionObject3D).shape_find_owner(int(hit.shape)))
				if owner_node is CollisionShape3D:
					detail = "shape at %s size %s" % [str((owner_node as CollisionShape3D).global_position), str(((owner_node as CollisionShape3D).shape as BoxShape3D).size if (owner_node as CollisionShape3D).shape is BoxShape3D else "")]
			print("     %s blocked by %s %s" % [what, n.get_path() if n else "?", detail])


func _finish() -> void:
	print("\nFORKLIFT TEST %s (%d failures)" % ["PASSED" if _fails.is_empty() else "FAILED", _fails.size()])
	get_tree().quit(0 if _fails.is_empty() else 1)
