extends Node
## Builds a lights-out factory in a real level and films it running, start to finish:
##   pallet → arm A0 → goods-in conveyor → scanner → arm A1 → test rack → repair robot / imaging rack →
##   conveyor → vision grader → arm A2 → stock racking → (sold) packing robot → OUTGOING shelf → courier.
## Nobody touches anything. Frames: tools/out/factory_<n>.png + factory_sheet.png; prints a run report.
##   godot --path . res://tools/factory_film.tscn [-- --level megaplant --speed 4 --frames 48]

const LAYOUT := [
	# id, type, local position, rotation (factory space: flow runs along +Z)
	["a0", "robot_arm", Vector3(0, 0, -6.0), 0.0],
	["c0", "conveyor", Vector3(0, 0, -4.0), 0.0],
	["s0", "conveyor_scanner", Vector3(0, 0, -2.5), 0.0],
	["a1", "robot_arm", Vector3(0, 0, -0.4), 0.0],
	["rt", "rack_test", Vector3(-1.6, 0, -0.4), 90.0],
	["ri", "rack_image", Vector3(1.6, 0, -0.4), -90.0],
	["rr", "robot_repair", Vector3(1.4, 0, 1.4), -90.0],
	["c1", "conveyor", Vector3(0, 0, 1.8), 0.0],
	["g1", "conveyor_grader", Vector3(0, 0, 3.5), 0.0],
	["c2", "conveyor", Vector3(0, 0, 5.2), 0.0],
	["a2", "robot_arm", Vector3(0, 0, 7.2), 0.0],
	["h1", "heavy_rack", Vector3(-1.6, 0, 7.2), 90.0],
	["rp", "robot_pack", Vector3(1.6, 0, 7.2), -90.0],
	["out1", "courier_shelf", Vector3(0, 0, 8.75), 180.0],
]

var _cam: Camera3D
var _origin := Transform3D()
var _units: Array = []


func _ready() -> void:
	get_window().size = Vector2i(1280, 720)
	var args := OS.get_cmdline_user_args()
	var level_id := args[args.find("--level") + 1] if args.find("--level") >= 0 else "megaplant"
	var speed := float(args[args.find("--speed") + 1]) if args.find("--speed") >= 0 else 4.0
	var frames := int(args[args.find("--frames") + 1]) if args.find("--frames") >= 0 else 48
	Game.new_game(4242)
	Game.state.building_id = level_id
	var level: Node = load(load("res://scripts/game_root.gd").level_path(level_id)).instantiate()
	add_child(level)
	await get_tree().create_timer(0.6).timeout
	var s := Game.state
	var sim := Game.sim
	var lr := LevelRoot.current
	# Clear the floor and pick the biggest open rectangle for the line.
	for id in s.fixtures.keys():
		s.fixtures[id].placed = false
		s.mark("fixture", id)
	await get_tree().process_frame
	# Find open floor for the whole line (clear of aisles, pillars and doors), nearest the middle first.
	var rot := 0.0
	var found := false
	var cands: Array = []
	for cx in range(-50, 51):
		for cz in range(-70, 71, 2):
			cands.append(Vector2(cx, cz))
	cands.sort_custom(func(a, b): return a.length() < b.length())
	for c: Vector2 in cands:
		_origin = Transform3D(Basis.IDENTITY, Vector3(c.x, 0, c.y))
		var ok := true
		for row in LAYOUT:
			if not lr.can_place(row[1], _origin * (row[2] as Vector3), float(row[3]), "__none"):
				ok = false
				break
		if ok:
			found = true
			break
	print("  factory at ", _origin.origin, " (clear floor: %s)" % found)
	for row in LAYOUT:
		var p: Vector3 = _origin * (row[2] as Vector3)
		var fid: String = row[0]
		s.fixtures[fid] = {"type": row[1], "label": _label(row[1]), "placed": true, "p": [p.x, 0.0, p.z], "r": float(row[3]) + rot}
		s.mark("fixture", fid)
	await get_tree().process_frame
	await get_tree().process_frame
	# Stock up: parts for the repair robot, boxes for the packer, and a pallet of mixed kit beside A0.
	for pid in Content.parts.keys():
		s.parts[pid] = 10
	s.consumables["boxes"] = 60
	s.consumables["big_boxes"] = 20
	s.consumables["labels"] = 100
	s.consumables["tape"] = 100
	var pallet := s.new_item(Item.KIND_PALLET)
	pallet.data = {"cell": [0.4, 0.03, 0.4], "grid": [3, 2], "seller_name": "Film lot"}
	var pp := _origin * Vector3(-1.45, 0.0, -6.0)
	var pq := (_origin.basis * Basis(Vector3.UP, PI * 0.5)).get_rotation_quaternion()
	s.set_loc(pallet, {"t": "loose", "p": [pp.x, 0.0, pp.z], "r": [pq.x, pq.y, pq.z, pq.w]})
	var models := ["kestrel_swift_14", "corvex_prospect_5420", "northstar_vanguard_g17", "kestrel_swift_14", "corvex_prospect_5420", "kestrel_swift_14"]
	for k in models.size():
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = sim.lots.roll_unit(models[k], 0.45)
		it.unit.faults = it.unit.faults.filter(func(f): return int(Content.fault(f.id).get("repair_level", 1)) <= 1)
		if k % 2 == 1 and it.unit.faults.is_empty():
			it.unit.faults = [{"id": "storage_dead", "detail": {}, "fixed": false}]
		for f in it.unit.faults:
			s.parts[Valuation.repair_part_for(it.unit, f)] = 10
		s.set_loc(it, {"t": "slot", "slot": "i%d:%d" % [pallet.id, k]})
		_units.append(it)
	# A second pallet of big kit: rack servers and a tower server.
	var big := ["corvex_rampart_2200", "corvex_rampart_1100", "northstar_bastion_t5"]
	var lay := sim.pallet_layout(big[0])
	var pallet2 := s.new_item(Item.KIND_PALLET)
	pallet2.data = {"cell": lay.cell, "grid": lay.grid, "seller_name": "Film servers"}
	var pp2 := _origin * Vector3(1.45, 0.0, -6.0)
	s.set_loc(pallet2, {"t": "loose", "p": [pp2.x, 0.0, pp2.z], "r": [pq.x, pq.y, pq.z, pq.w]})
	for k in big.size():
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = sim.lots.roll_unit(big[k], 0.45)
		it.unit.faults = it.unit.faults.filter(func(f): return int(Content.fault(f.id).get("repair_level", 1)) <= 1)
		for f in it.unit.faults:
			s.parts[Valuation.repair_part_for(it.unit, f)] = 10
		s.set_loc(it, {"t": "slot", "slot": "i%d:%d" % [pallet2.id, k]})
		_units.append(it)
	s.mark("company")
	_cam = Camera3D.new()
	_cam.fov = 55
	level.add_child(_cam)
	_cam.current = true
	Engine.time_scale = speed
	s.day_running = true
	var shots: Array[Image] = []
	var shot_list := _shot_list(frames)
	for i in frames:
		_aim(shot_list[i])
		await get_tree().create_timer(2.5 * speed).timeout
		_sell_listed(sim)
		if i % 12 == 11:
			sim.courier_collect()
		await RenderingServer.frame_post_draw
		var img := get_viewport().get_texture().get_image()
		img.save_png("res://tools/out/factory_%02d.png" % i)
		img.resize(320, 180)
		shots.append(img)
	Engine.time_scale = 1.0
	var cols := 8
	var sheet := Image.create(320 * cols, 180 * int(ceil(shots.size() / float(cols))), false, shots[0].get_format())
	for i in shots.size():
		sheet.blit_rect(shots[i], Rect2i(0, 0, 320, 180), Vector2i((i % cols) * 320, (i / cols) * 180))
	sheet.save_png("res://tools/out/factory_sheet.png")
	var shipped := _units.filter(func(it): return not s.items.has(it.id) or it.loc.get("t") in ["gone", "in"]).size()
	print("FACTORY units=%d shipped_or_packed=%d sold=%d booked=%d" % [_units.size(), shipped, int(s.totals.get("units_sold", 0)), _units.filter(func(it): return it.unit.booked).size()])
	for it: Item in _units:
		print("  #%d %s  loc=%s  stage=%s" % [it.id, it.unit.model_id, str(it.loc.get("t")) + ":" + str(it.loc.get("slot", it.loc.get("belt", it.loc.get("arm", "")))), it.unit.stage()])
	get_tree().quit()


func _label(type: String) -> String:
	return str(Content.fixture(type).get("name", type))


func _sell_listed(sim: Sim) -> void:
	for it: Item in _units:
		if Game.state.items.has(it.id) and it.unit.listed and it.unit.order_id == 0:
			sim._create_order(it, "flogit")


func _shot_list(n: int) -> Array:
	# (eye, target) in factory space; cycles overview → close-ups so every stage gets seen.
	var shots := [
		[Vector3(-5.5, 6.5, -8.5), Vector3(0, 0.6, 0.5)],
		[Vector3(-2.6, 2.2, -7.6), Vector3(-0.6, 0.9, -5.6)],
		[Vector3(1.8, 1.9, -3.6), Vector3(0, 1.0, -2.4)],
		[Vector3(-0.6, 2.6, 2.4), Vector3(-0.6, 0.9, -0.8)],
		[Vector3(-1.0, 1.8, 3.4), Vector3(1.3, 1.0, 1.3)],
		[Vector3(1.6, 1.9, 5.4), Vector3(0, 1.1, 3.5)],
		[Vector3(-1.2, 2.4, 4.6), Vector3(0.3, 0.9, 7.6)],
		[Vector3(4.5, 5.0, 3.0), Vector3(0, 0.6, 1.5)],
	]
	var out: Array = []
	for i in n:
		out.append(shots[(i / 2) % shots.size()])
	return out


func _aim(shot: Array) -> void:
	_cam.global_position = _origin * (shot[0] as Vector3)
	_cam.look_at(_origin * (shot[1] as Vector3))
