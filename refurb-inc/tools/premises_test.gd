extends Node
## Moves one company through all seven premises and checks each level works:
## equipment auto-layout (no overlaps, inside the floor), stock travels with equipment, loose stock relocated,
## lorry delivers to that building's docks, player spawns inside.
##   godot --headless --path . res://tools/premises_test.tscn

var _fails: PackedStringArray = []
var _level: LevelRoot


func _check_site() -> void:
	## Nobody falls through the map: solid ground everywhere inside the site fence, and the fence encloses
	## the building, spawns, goods-in and docks while keeping players off the road.
	await get_tree().physics_frame
	await get_tree().physics_frame
	await get_tree().physics_frame
	var site := _level.site
	_check(site != null and site.bounds.size.x > 6.0 and site.bounds.size.y > 6.0, "site fence built (%s, %d bare cells filled with %d slabs)" % [str(site.bounds) if site else "-", site.filled_cells if site else 0, site.slabs if site else 0])
	if site == null:
		return
	var inside := true
	for fr: Rect2 in _level.floor_rects:
		inside = inside and site.bounds.encloses(fr)
	for n in [_level.goods_in, _level.lorry_drop, _level.safe_drop]:
		if n:
			inside = inside and site.contains(n.global_position)
	_check(inside, "fence encloses the building floor and goods-in")
	var space := _level.get_world_3d().direct_space_state
	var q := PhysicsRayQueryParameters3D.new()
	q.collision_mask = 1
	var holes := 0
	var step := 1.0
	var b := site.bounds
	var x := b.position.x + 0.3
	while x < b.end.x - 0.3:
		var z := b.position.y + 0.3
		while z < b.end.y - 0.3:
			q.from = Vector3(x, 0.6, z)
			q.to = Vector3(x, -3.0, z)
			if space.intersect_ray(q).is_empty():
				holes += 1
				if holes <= 3:
					print("     no ground at ", Vector2(x, z))
			z += step
		x += step
	_check(holes == 0, "ground under every metre of the site (%d holes)" % holes)
	# Walk straight out of the front door: the fence stops you before the road.
	var probe := PhysicsRayQueryParameters3D.new()
	probe.collision_mask = 1
	probe.from = Vector3(0, 1.0, b.end.y - 1.0)
	probe.to = Vector3(0, 1.0, b.end.y + 5.0)
	_check(not space.intersect_ray(probe).is_empty(), "site fence blocks walking into the road")


func _check(c: bool, m: String) -> void:
	print("  %s %s" % ["✔" if c else "✖", m])
	if not c:
		_fails.append(m)


func _ready() -> void:
	Engine.time_scale = 10.0
	Game.new_game(99)
	var s := Game.state
	var sim := Game.sim
	s.cash = 10_000_000
	await _load_level()
	# Put a unit on the test bench so we can check it moves with the equipment.
	var lot: Dictionary = s.lots.values().filter(func(l): return l.status == "open")[0]
	sim.c_buy_lot(1, {"lot": lot.id})
	sim.deliver_lot(lot.id, Vector3(0, 0.02, 5.6))
	await _frames(3)
	var unit: Item = s.items.values().filter(func(i): return i.kind == Item.KIND_UNIT)[0]
	s.set_loc(unit, {"t": "slot", "slot": "bench_1:unit"})
	# Extra equipment bought along the way should find space in bigger buildings.
	for id in Content.building_order:
		print("\n== %s" % id)
		if id != "garage":
			var r := sim.execute(1, "move_building", {"building": id})
			_check(r.ok, "move command accepted")
			await _load_level()
		_check(_level.building_id == id, "level scene is %s" % id)
		_check(s.building_id == id, "state building is %s" % id)
		var placed := 0
		var bad := 0
		for fid in s.fixtures:
			var f: Dictionary = s.fixtures[fid]
			if f.placed:
				placed += 1
				if not _level.can_place(f.type, Vector3(f.p[0], f.p[1], f.p[2]), float(f.r), fid):
					bad += 1
					print("     overlap/out-of-bounds: ", fid, " ", f.p, " r", f.r)
		_check(placed == s.fixtures.size(), "all %d pieces of equipment placed (%d)" % [s.fixtures.size(), placed])
		_check(bad == 0, "no equipment overlaps or outside the floor")
		_check(_level.fixture_nodes.size() == placed, "equipment nodes spawned")
		_check(s.item_in_slot("bench_1:unit") == unit and _level.item_views.has(unit.id) and _level.item_views[unit.id].visible, "unit on the test bench came along")
		var pallets := s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET)
		var relocated := pallets.all(func(p): return not p.loc.get("relocate", false))
		_check(relocated, "loose pallets placed in the goods-in area")
		await _check_site()
		var player: Node3D = _level.local_player()
		_check(player != null and _level.floor_rects.any(func(r): return r.has_point(Vector2(player.global_position.x, player.global_position.z))), "player spawned on the floor")
		# Lorry delivery to this building's dock
		var lot2: Dictionary = {}
		sim.refresh_lot_board(true)
		for l in s.lots.values():
			if l.status == "open":
				lot2 = l
				break
		sim.c_buy_lot(1, {"lot": lot2.id})
		var before := pallets.size()
		sim.end_day()
		sim.start_next_day()
		sim.advance_minutes(65)
		var t := 0.0
		while s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET).size() == before and t < 120.0:
			await get_tree().process_frame
			t += get_process_delta_time()
		var newest: Item = null
		for it: Item in s.items.values():
			if it.kind == Item.KIND_PALLET and (newest == null or it.id > newest.id):
				newest = it
		_check(newest != null and s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET).size() > before, "lorry delivered a pallet")
		if newest:
			var p := Vector3(newest.loc.p[0], newest.loc.p[1], newest.loc.p[2])
			print("     pallet dropped at ", p)
			_check(p.y > -0.5, "pallet landed at ground/floor level")
		# Buy one of each piece of equipment in tiers ≥ 3 and let it auto-place there.
		if int(Content.building(id).tier) >= 3:
			for type in ["test_bench", "metal_shelf"]:
				sim.c_buy_fixture(1, {"type": type})
			_level._auto_layout()
			var unplaced := s.fixtures.values().filter(func(f): return not f.placed).size()
			_check(unplaced == 0, "new equipment found space (%d unplaced)" % unplaced)
	print("\nPREMISES TEST %s (%d failures)" % ["PASSED" if _fails.is_empty() else "FAILED", _fails.size()])
	Engine.time_scale = 1.0
	get_tree().quit(0 if _fails.is_empty() else 1)


func _load_level() -> void:
	if _level:
		remove_child(_level)
		_level.queue_free()
		await get_tree().process_frame
	_level = load(load("res://scripts/game_root.gd").level_path(Game.state.building_id)).instantiate()
	add_child(_level)
	await _frames(5)


func _frames(n: int) -> void:
	for i in n:
		await get_tree().process_frame
