extends Node
## Visual QA for whole-pallet logistics and the premises fit-out, rendered in real levels. Saves tools/out/*.png.
##   rack    — a forklift with a pallet of laptops raised at the top level of pallet racking, the other bays
##             stocked, a low rack and an unload bay feeding a robot arm and the goods-in conveyor (industrial unit)
##   garage  — a customised garage: painted walls, walkway lines and bay boxes, door sign, plants, posters, a
##             clock, a rug, the site radio and an Employee of the Month on the wall
##   godot --rendering-driver d3d12 --gpu-index 1 --path . res://tools/logistics_shot.tscn -- --shot rack

var _cam: Camera3D


func _ready() -> void:
	get_window().size = Vector2i(1600, 900)
	var args := OS.get_cmdline_user_args()
	var shot := args[args.find("--shot") + 1] if args.find("--shot") >= 0 else "rack"
	match shot:
		"garage":
			await _garage()
		_:
			await _rack()
	get_tree().quit()


func _load(level_id: String, seed_value: int) -> LevelRoot:
	Game.new_game(seed_value)
	Game.state.building_id = level_id
	var level: LevelRoot = load(load("res://scripts/game_root.gd").level_path(level_id)).instantiate()
	add_child(level)
	await get_tree().create_timer(0.8).timeout
	return level


func _pallet(p: Vector3, models: Array, n: int, loc: Dictionary = {}) -> Item:
	var s := Game.state
	var pallet := s.new_item(Item.KIND_PALLET)
	var lay := Sim.pallet_layout(models[0])
	pallet.data = {"cell": lay.cell, "grid": lay.grid, "seller_name": "Northgate Bank"}
	s.set_loc(pallet, loc if not loc.is_empty() else {"t": "loose", "p": [p.x, p.y, p.z], "r": [0, 0, 0, 1]})
	for k in n:
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = Game.sim.lots.roll_unit(models[k % models.size()], 0.6)
		s.set_loc(it, {"t": "slot", "slot": "i%d:%d" % [pallet.id, k]})
	return pallet


func _place(fid: String, type: String, p: Vector3, r: float = 0.0) -> void:
	var s := Game.state
	var label := Logistics.fixture_number_label(s, type)
	s.fixtures[fid] = {"type": type, "label": label if label != "" else Sim.fixture_label(s, type), "placed": true, "p": [p.x, p.y, p.z], "r": r}
	s.mark("fixture", fid)


func _shoot(name: String, eye: Vector3, at: Vector3, fov: float = 60.0) -> void:
	if _cam == null:
		_cam = Camera3D.new()
		add_child(_cam)
	_cam.current = true
	_cam.fov = fov
	_cam.position = eye
	_cam.look_at(at)
	for k in 12:
		await get_tree().process_frame
	await RenderingServer.frame_post_draw
	get_viewport().get_texture().get_image().save_png("res://tools/out/%s.png" % name)
	print("saved ", name)


func _rack() -> void:
	var level := await _load("industrial", 811)
	var s := Game.state
	for id in s.fixtures.keys():
		s.fixtures[id].placed = false
		s.mark("fixture", id)
	await get_tree().process_frame
	# Racking along the back wall, a low rack beside it, an unload bay with an arm and the goods-in line.
	_place("rack_1", "pallet_rack", Vector3(-1.6, 0, -8.9))
	_place("rack_2", "pallet_rack_low", Vector3(2.4, 0, -8.9))
	_place("bay_1", "pallet_bay", Vector3(3.6, 0, -3.2), 90.0)
	_place("arm_1", "robot_arm", Vector3(3.6, 0, -1.6))
	_place("c0", "conveyor", Vector3(2.2, 0, -0.6), 0.0)
	_place("c1", "conveyor", Vector3(2.2, 0, 1.4), 0.0)
	_place("plant", "plant_tall", Vector3(-5.2, 0, -9.3))
	await get_tree().process_frame
	await get_tree().process_frame
	var laptops := ["corvex_prospect_5420", "kestrel_swift_14", "halden_folio_840"]
	var servers := ["corvex_rampart_2200"]
	for sid in ["rack_1:b0l0", "rack_1:b1l0", "rack_1:b2l0", "rack_1:b0l1", "rack_1:b2l1", "rack_1:b0l2", "rack_2:b0l0", "rack_2:b1l1"]:
		_pallet(Vector3.ZERO, servers if sid.ends_with("l0") and sid.begins_with("rack_1:b1") else laptops, 12, {"t": "slot", "slot": sid})
	_pallet(Vector3.ZERO, laptops, 9, {"t": "slot", "slot": "bay_1:b0l0"})
	# The forklift: parked with its forks up at the top level of the middle bay, a pallet of laptops on them.
	var top: Transform3D = Logistics.slot_world(s, "rack_1:b1l2")
	var fl := s.new_item(Item.KIND_FORKLIFT)
	fl.data = {"h": 2.98}
	# Forklift faces -Z (forks towards the rack): model +Z = forks, so turn it 180°.
	var yaw := PI
	var basis := Basis(Vector3.UP, yaw)
	var pal_local := Logistics.pallet_on_forks(0.0, 90.0)
	var origin := top.origin - basis * Vector3(pal_local.origin.x, 0, pal_local.origin.z) + basis * Vector3(0, 0, -0.35)
	origin.y = 0.0
	var q := basis.get_rotation_quaternion()
	s.set_loc(fl, {"t": "loose", "p": [origin.x, 0.0, origin.z], "r": [q.x, q.y, q.z, q.w]})
	var carried := _pallet(Vector3.ZERO, laptops, 12)
	s.set_loc(carried, {"t": "on_forks", "fork": fl.id, "yaw": 90.0})
	await get_tree().create_timer(1.2).timeout
	await _shoot("logistics_rack", origin + Vector3(3.6, 2.4, 3.8), origin + Vector3(-0.3, 1.9, -1.8), 62.0)
	await _shoot("logistics_bay", Vector3(-0.6, 2.3, 2.8), Vector3(3.2, 0.6, -2.2), 62.0)


func _garage() -> void:
	var level := await _load("garage", 812)
	var s := Game.state
	s.cash = 100000
	var sim := Game.sim
	sim.execute(1, "set_premises", {"key": "paint", "value": "navy_blazer"})
	sim.execute(1, "set_premises", {"key": "markings", "value": "both"})
	sim.execute(1, "set_premises", {"key": "line_colour", "value": "yellow"})
	sim.execute(1, "set_premises", {"key": "accent", "value": "orange"})
	s.player("p1")["name"] = "Jon"
	s.premises["eotm"] = {"day": 7, "key": "p1", "name": "Jon Petty", "score": 142, "staff": false, "streak": 3,
		"board": [{"key": "p1", "name": "Jon Petty", "score": 142}, {"key": "p2", "name": "Sam", "score": 97}]}
	s.mark("premises")
	for type in ["plant_tall", "plant_small", "wall_clock", "poster_motto", "poster_safety", "poster_retro", "rug", "site_radio"]:
		sim.execute(1, "buy_fixture", {"type": type})
	level._auto_layout()
	await get_tree().create_timer(1.5).timeout
	for id in s.fixtures:
		var f: Dictionary = s.fixtures[id]
		print("  ", id, " ", f.type, " placed=", f.placed, " p=", f.p, " r=", f.r)
	var radio := ""
	for id in s.fixtures:
		if s.fixtures[id].type == "site_radio":
			radio = id
	if radio != "":
		sim.execute(1, "decor_toggle", {"id": radio})
	await _shoot("premises_garage", Vector3(1.6, 1.65, 3.0), Vector3(-0.6, 1.2, -3.2), 70.0)
	await _shoot("premises_garage_door", Vector3(1.2, 1.6, 9.5), Vector3(0, 2.2, 3.5), 58.0)
