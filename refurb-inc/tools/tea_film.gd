extends Node
## Films a whole tea round from the player's eyes in the garage kitchen: mugs out, kettle on (boils and
## clicks off), the round poured (the kettle lifts and fills each mug in turn), milk in, then pick one up
## and drink it. Saves tools/out/tea_<n>.png and tools/out/tea_sheet.png.

var _shots: Array[Image] = []
var _n := 0


func _ready() -> void:
	get_window().size = Vector2i(1280, 720)
	Game.new_game(77)
	var level: Node = load(load("res://scripts/game_root.gd").level_path("garage")).instantiate()
	add_child(level)
	await get_tree().create_timer(0.8).timeout
	var p: Player = LevelRoot.current.local_player()
	var kitchen: Node3D = level.find_child("KettleCorner", true, false)
	var front := kitchen.global_basis.z.normalized()
	p.global_position = kitchen.global_position + front * 0.95 + kitchen.global_basis.x * 0.05
	p._yaw = atan2(front.x, front.z)
	p._pitch = deg_to_rad(-38)
	p._apply_look()
	await get_tree().create_timer(0.4).timeout
	for i in 3:
		Game.request("tea", {"station": "kitchen", "act": "mug"})
		await _shot(0.35)
	Game.request("tea", {"station": "kitchen", "act": "boil"})
	Engine.time_scale = 4.0
	for i in 4:
		await _shot(4.0 * 1.2)
	Engine.time_scale = 1.0
	await _shot(1.0)
	Game.request("tea", {"station": "kitchen", "act": "pour"})
	for i in 14:
		await _shot(0.33)
	Game.request("tea", {"station": "kitchen", "act": "milk"})
	for i in 7:
		await _shot(0.35)
	await _shot(1.5)
	# Pick one up and drink it.
	var mugs: Array = Game.sim._tea_mugs("kitchen")
	Game.request("pick_up", {"item": mugs[0].id})
	p._pitch = deg_to_rad(-5)
	p._apply_look()
	await _shot(0.6)
	p.drink_tea(Game.state.get_item(mugs[0].id))
	for i in 12:
		await _shot(0.33)
	var cols := 8
	var sheet := Image.create(320 * cols, 180 * int(ceil(_shots.size() / float(cols))), false, _shots[0].get_format())
	for i in _shots.size():
		sheet.blit_rect(_shots[i], Rect2i(0, 0, 320, 180), Vector2i((i % cols) * 320, (i / cols) * 180))
	sheet.save_png("res://tools/out/tea_sheet.png")
	print("FILMED tea round: mugs=%d brews=%d buff=%s kwh=%.2f" % [mugs.size(), int(Game.state.day_stats.get("brews", 0)), str(p.has_tea()), float(Game.state.day_stats.get("kwh", 0.0))])
	get_tree().quit()


func _shot(wait: float) -> void:
	await get_tree().create_timer(wait).timeout
	await RenderingServer.frame_post_draw
	var img := get_viewport().get_texture().get_image()
	img.save_png("res://tools/out/tea_%02d.png" % _n)
	_n += 1
	img.resize(320, 180)
	_shots.append(img)
