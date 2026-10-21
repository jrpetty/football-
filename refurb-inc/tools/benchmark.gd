extends Node
## Smoothness benchmark: every premises with its equipment laid out, every shelf bay full of stock and pallets of
## kit on the floor, then the camera pans round inside and outside while frame times are recorded (v-sync off).
## Prints avg FPS, 1% low, worst frame and draw calls per premises, and writes tools/out/benchmark.txt.
##   Godot --path . res://tools/benchmark.tscn [-- --levels garage,warehouse] [-- --pallets 16]

const SHOT := preload("res://tools/screenshot.gd")
const LAPTOPS := ["corvex_prospect_5420", "halden_folio_840", "northstar_meridian_t14", "kestrel_swift_14", "brightline_vantage_3510"]
const BIG := ["corvex_rampart_2200", "corvex_rampart_1100", "corvex_fulcrum_7_tower"]
const SECS_PER_POSE := 5.0

var _cam: Camera3D
var _lines: PackedStringArray = []


func _ready() -> void:
	get_window().size = Vector2i(1600, 900)
	DisplayServer.window_set_vsync_mode(DisplayServer.VSYNC_DISABLED)
	Engine.max_fps = 0
	var args := OS.get_cmdline_user_args()
	var levels: Array = SHOT.LEVEL_POSES.keys()
	if args.find("--levels") >= 0:
		levels = args[args.find("--levels") + 1].split(",")
	var pallets := 12
	if args.find("--pallets") >= 0:
		pallets = int(args[args.find("--pallets") + 1])
	_lines.append("REFURB INC benchmark · %s · %s" % [Time.get_datetime_string_from_system(), RenderingServer.get_video_adapter_name()])
	_lines.append("%-13s %-7s %7s %7s %8s %7s %9s %6s" % ["premises", "view", "avg fps", "1% low", "worst ms", "draws", "prims(k)", "items"])
	for level_id in levels:
		await _bench_level(str(level_id), pallets)
	var out := "\n".join(_lines)
	print(out)
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	var f := FileAccess.open("res://tools/out/benchmark.txt", FileAccess.WRITE)
	f.store_string(out + "\n")
	f.close()
	get_tree().quit()


func _bench_level(level_id: String, pallets: int) -> void:
	Game.new_game(777)
	Game.state.building_id = level_id
	Game.state.cash = 10000000
	# Stock the place like a busy business would: shelving and racking in proportion to the floor.
	var floor_m2 := float(Content.building(level_id).get("floor_m2", 40))
	for i in clampi(int(floor_m2 / 70.0), 1, 30):
		Game.sim.execute(1, "buy_fixture", {"type": "metal_shelf"})
	for i in clampi(int(floor_m2 / 200.0), 0, 12):
		Game.sim.execute(1, "buy_fixture", {"type": "heavy_rack"})
	var level: Node = load(load("res://scripts/game_root.gd").level_path(level_id)).instantiate()
	add_child(level)
	for i in 30:
		await get_tree().physics_frame
	var units := _fill_shelves()
	units += _drop_pallets(level, pallets)
	_cam = Camera3D.new()
	_cam.fov = 75.0
	add_child(_cam)
	_cam.make_current()
	for i in 60:
		await get_tree().process_frame
	# Experiments: --lod <px> overrides the mesh LOD threshold; --stock-shadows-off stops stock casting shadows.
	var args := OS.get_cmdline_user_args()
	if args.find("--lod") >= 0:
		get_viewport().mesh_lod_threshold = float(args[args.find("--lod") + 1])
	if "--stock-shadows-off" in args:
		for gi: GeometryInstance3D in level.find_children("*", "GeometryInstance3D", true, false):
			if gi.get_parent() and gi.find_parent("Item_*"):
				gi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	var lights := level.find_children("*", "Light3D", true, false).filter(func(x): return x.shadow_enabled and x.visible)
	print("%s: %d shadowed lights, LOD threshold %.1f px" % [level_id, lights.size(), get_viewport().mesh_lod_threshold])
	var poses: Dictionary = SHOT.LEVEL_POSES.get(level_id, {})
	for view in ["int", "ext"]:
		if not poses.has(view):
			continue
		var pose: Array = poses[view]
		var r := await _measure(pose[0], pose[1])
		_lines.append("%-13s %-7s %7.0f %7.0f %8.1f %7d %9.0f %6d" % [level_id, view, r.avg, r.low, r.worst, r.draws, r.prims / 1000.0, units])
		print(_lines[-1])
	_cam.queue_free()
	level.queue_free()
	await get_tree().process_frame
	await get_tree().process_frame


func _fill_shelves() -> int:
	## Every free shelf/rack bay gets a unit (laptops on shelving, big kit on heavy racking).
	var s := Game.state
	var n := 0
	for slot_id in s.slot_defs.keys():
		if s.slots.get(slot_id, 0) != 0:
			continue
		var host := str(slot_id).get_slice(":", 0)
		var ftype := str(s.fixtures.get(host, {}).get("type", ""))
		if not ftype in ["metal_shelf", "heavy_rack", "courier_shelf"]:
			continue
		var accepts: Array = s.slot_defs[slot_id].get("accepts", [])
		var model := ""
		if "unit" in accepts:
			model = LAPTOPS[n % LAPTOPS.size()]
		elif "unit_large" in accepts:
			model = BIG[n % BIG.size()]
		if model == "":
			continue
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = Game.sim.lots.roll_unit(model, 0.6)
		it.unit.tested = true
		it.unit.imaged = true
		it.unit.grade = "B"
		s.set_loc(it, {"t": "slot", "slot": slot_id})
		n += 1
	return n


func _drop_pallets(level: Node, count: int) -> int:
	## Full pallets of laptops in a free patch of floor near goods-in.
	var s := Game.state
	var lot := {"id": s.make_id(), "seller_name": "Benchmark", "count": 0, "total_price": 0, "status": "bought", "units": []}
	var cap := int(Sim.pallet_layout(LAPTOPS[0]).capacity)
	for i in count * cap:
		lot.units.append(Game.sim.lots.roll_unit(LAPTOPS[i % LAPTOPS.size()], 0.5).to_dict())
	lot.count = lot.units.size()
	s.lots[lot.id] = lot
	var base: Vector3 = level.goods_in.global_position if level.get("goods_in") else Vector3.ZERO
	var spots := _free_floor(level, base, count)
	var made: Array = Game.sim.deliver_lot(lot.id, base)
	for i in made.size():
		var p: Vector3 = spots[i] if i < spots.size() else base + Vector3(0, 0.02 + i * 0.2, 0)
		s.set_loc(made[i], {"t": "loose", "p": [p.x, p.y, p.z], "r": [0, 0, 0, 1]})
	return lot.count


func _free_floor(level: Node, base: Vector3, count: int) -> Array:
	var space: PhysicsDirectSpaceState3D = level.get_world_3d().direct_space_state
	var box := BoxShape3D.new()
	box.size = Vector3(1.3, 1.0, 0.9)
	var q := PhysicsShapeQueryParameters3D.new()
	q.shape = box
	q.collision_mask = 1 | (1 << 2)
	var out: Array = []
	for ring in range(0, 30):
		for dx in range(-ring, ring + 1):
			for dz in range(-ring, ring + 1):
				if maxi(absi(dx), absi(dz)) != ring:
					continue
				var p := base + Vector3(dx * 1.45, 0.0, dz * 1.05)
				var floor_ok: bool = level.floor_rects.is_empty() or level.floor_rects.any(func(fr: Rect2): return fr.has_point(Vector2(p.x, p.z)))
				q.transform = Transform3D(Basis(), p + Vector3(0, 0.6, 0))
				if floor_ok and space.intersect_shape(q, 1).is_empty():
					out.append(p + Vector3(0, 0.02, 0))
					if out.size() >= count:
						return out
	return out


func _measure(from: Vector3, look: Vector3) -> Dictionary:
	var times: Array = []
	var draws := 0
	var prims := 0
	var frames := 0
	var t := 0.0
	var dir := (look - from)
	var yaw0 := atan2(-dir.x, -dir.z)
	var pitch := atan2(dir.y, Vector2(dir.x, dir.z).length())
	while t < SECS_PER_POSE:
		var yaw := yaw0 + sin(t / SECS_PER_POSE * TAU) * 0.9
		_cam.global_position = from
		_cam.global_rotation = Vector3(pitch, yaw, 0)
		await get_tree().process_frame
		var dt := get_process_delta_time()
		t += dt
		if frames > 5:
			times.append(dt)
			draws += RenderingServer.get_rendering_info(RenderingServer.RENDERING_INFO_TOTAL_DRAW_CALLS_IN_FRAME)
			prims += RenderingServer.get_rendering_info(RenderingServer.RENDERING_INFO_TOTAL_PRIMITIVES_IN_FRAME)
		frames += 1
	times.sort()
	var n := times.size()
	var total := 0.0
	for x in times:
		total += x
	var worst_1pc := 0.0
	var k := maxi(int(n * 0.01), 1)
	for i in k:
		worst_1pc += times[n - 1 - i]
	worst_1pc /= k
	return {"avg": n / maxf(total, 0.0001), "low": 1.0 / maxf(worst_1pc, 0.0001), "worst": float(times[n - 1]) * 1000.0,
		"draws": draws / maxi(n, 1), "prims": float(prims) / maxi(n, 1)}
