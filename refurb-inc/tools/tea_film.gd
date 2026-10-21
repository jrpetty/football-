extends Node
## Films the first-person tea break from the local player's camera. Saves tools/out/tea_sheet.png.

func _ready() -> void:
	get_window().size = Vector2i(1280, 720)
	Game.new_game(77)
	var level: Node = load(load("res://scripts/game_root.gd").level_path("garage")).instantiate()
	add_child(level)
	await get_tree().create_timer(0.8).timeout
	var p: Player = LevelRoot.current.local_player()
	p.global_position = Vector3(-1.6, 0.05, 2.2)
	p._yaw = deg_to_rad(120)
	p._pitch = deg_to_rad(-12)
	p._apply_look()
	await get_tree().create_timer(0.3).timeout
	p.drink_tea()
	var shots: Array[Image] = []
	for i in 12:
		await get_tree().create_timer(0.33).timeout
		await RenderingServer.frame_post_draw
		var img := get_viewport().get_texture().get_image()
		img.resize(426, 240)
		shots.append(img)
	var sheet := Image.create(426 * 4, 240 * 3, false, shots[0].get_format())
	for i in shots.size():
		sheet.blit_rect(shots[i], Rect2i(0, 0, 426, 240), Vector2i((i % 4) * 426, (i / 4) * 240))
	sheet.save_png("res://tools/out/tea_sheet.png")
	print("FILMED tea, buff=", p.has_tea())
	get_tree().quit()
