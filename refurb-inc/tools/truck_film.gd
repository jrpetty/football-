extends Node
## Films the pallet truck being used (take → forks under → jack up → push into the garage → lower) from a
## third-person camera. Saves tools/out/truck_sheet.png.

var _cam: Camera3D
var _shots: Array[Image] = []


func _ready() -> void:
	get_window().size = Vector2i(1280, 720)
	Game.new_game(55)
	var level: Node = load(load("res://scripts/game_root.gd").level_path("garage")).instantiate()
	add_child(level)
	await get_tree().create_timer(0.8).timeout
	var s := Game.state
	var sim := Game.sim
	# A pallet of servers on the drive.
	var pallet := s.new_item(Item.KIND_PALLET)
	var lay := Sim.pallet_layout("corvex_rampart_2200")
	pallet.data = {"cell": lay.cell, "grid": lay.grid, "seller_name": "Film"}
	s.set_loc(pallet, {"t": "loose", "p": [0.3, 0.0, 5.6], "r": [0, 0, 0, 1]})
	for k in 6:
		var u := s.new_item(Item.KIND_UNIT)
		u.unit = sim.lots.roll_unit(["corvex_rampart_2200", "corvex_rampart_1100"][k % 2], 0.6)
		s.set_loc(u, {"t": "slot", "slot": "i%d:%d" % [pallet.id, k]})
	await get_tree().create_timer(0.4).timeout
	var p: Player = LevelRoot.current.local_player()
	var truck: Item = s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET_TRUCK)[0]
	_cam = Camera3D.new()
	_cam.fov = 60
	level.add_child(_cam)
	_cam.current = true
	_cam.global_position = Vector3(2.4, 2.4, 6.8)
	_cam.look_at(Vector3(-0.2, 0.4, 3.2))
	# Walk to the truck and take it.
	await _walk(p, Vector3(-1.45, 0.05, 1.3), PI)
	await _snap(2)
	sim.execute(p.peer_id, "take_truck", {"item": truck.id})
	await _snap(2)
	_dbg("after take", truck, pallet)
	# Swing round and head out to the pallet (forks first, pallet's long side along the forks).
	await _walk(p, Vector3(0.3, 0.05, 3.3), PI)
	await _walk(p, Vector3(0.3, 0.05, 4.35), PI)
	await _snap(2)
	var target := p._truck_target_pallet()
	print("forks under pallet: ", target != null)
	if target:
		p._tap()
	await _snap(2)
	# Pull it back into the garage and set it down.
	_dbg("after lift", truck, pallet)
	await _walk(p, Vector3(0.3, 0.05, 1.0), PI, 2)
	_dbg("after push", truck, pallet)
	await _snap(2)
	p._tap()
	await _snap(2)
	print("pallet now: ", pallet.loc)
	var sheet := Image.create(426 * 4, 240 * 3, false, _shots[0].get_format())
	for i in mini(_shots.size(), 12):
		sheet.blit_rect(_shots[i], Rect2i(0, 0, 426, 240), Vector2i((i % 4) * 426, (i / 4) * 240))
	sheet.save_png("res://tools/out/truck_sheet.png")
	print("FILMED truck")
	get_tree().quit()


func _dbg(tag: String, truck: Item, pallet: Item) -> void:
	var lr := LevelRoot.current
	var tv: ItemView = lr.item_views.get(truck.id)
	var pv: ItemView = lr.item_views.get(pallet.id)
	print(tag, " | players=", lr.players.keys(), " truck loc=", truck.loc, " view vis=", tv.visible if tv else "none", " pos=", tv.global_position if tv else Vector3.ZERO, " following=", tv._following if tv else false)
	print("   pallet loc=", pallet.loc.t, " vis=", pv.visible if pv else "none", " pos=", pv.global_position if pv else Vector3.ZERO)
	for it: Item in Game.state.items.values():
		if it.loc.get("t") == "slot" and str(it.loc.slot).begins_with("i%d:" % pallet.id):
			var uv: ItemView = lr.item_views.get(it.id)
			print("   unit ", it.loc.slot, " vis=", uv.visible if uv else "none", " pos=", uv.global_position if uv else Vector3.ZERO)
			break


func _walk(p: Player, to: Vector3, yaw: float, snaps := 1) -> void:
	var from := p.global_position
	p._yaw = yaw
	p._apply_look()
	var steps := 30
	for i in steps:
		p.global_position = from.lerp(to, float(i + 1) / steps)
		await get_tree().physics_frame
	for k in snaps:
		await _snap(1)


func _snap(n: int) -> void:
	for i in n:
		await get_tree().create_timer(0.25).timeout
	await RenderingServer.frame_post_draw
	var img := get_viewport().get_texture().get_image()
	img.resize(426, 240)
	_shots.append(img)
