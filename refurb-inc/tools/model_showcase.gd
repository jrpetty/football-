extends Node
## Visual QA for imported hardware models: lines every scene under assets/models/ up in the garage and saves
## renders to tools/out/models_*.png. Laptops are shown open with a test card on the "Screen" quad (it should
## exactly cover the panel, upright) and one closed (lid should sit flat on the base).
##   godot --path . res://tools/model_showcase.tscn

const ROOT := "res://assets/models"

var _cam: Camera3D


func _ready() -> void:
	get_window().size = Vector2i(1600, 900)
	Game.new_game(4242)
	var level: Node = load(load("res://scripts/game_root.gd").level_path("garage")).instantiate()
	add_child(level)
	_cam = Camera3D.new()
	_cam.fov = 50
	level.add_child(_cam)
	_cam.current = true
	var stage := Node3D.new()
	level.add_child(stage)
	var card := _test_card()
	var laptops := _scenes("laptops")
	var x := -(laptops.size() - 1) * 0.25
	for p in laptops:
		var inst: Node3D = load(p).instantiate()
		stage.add_child(inst)
		inst.position = Vector3(x, 0.0, 1.4)
		var lid: Node3D = inst.find_child("Lid", true, false)
		lid.rotation_degrees.x = float(inst.get_meta("lid_open_deg", -108.0))
		(inst.find_child("Screen", true, false) as MeshInstance3D).material_override = Mats.screen_texture(card)
		x += 0.5
	# One closed copy of each laptop in front, to check the lid closes flat and covers the base.
	x = -(laptops.size() - 1) * 0.25
	for p in laptops:
		var inst: Node3D = load(p).instantiate()
		stage.add_child(inst)
		inst.position = Vector3(x, 0.0, 2.2)
		x += 0.5
	var others := _scenes("desktops") + _scenes("servers")
	x = -1.9
	for p in others:
		var inst: Node3D = load(p).instantiate()
		stage.add_child(inst)
		var fp: Vector3 = inst.get_meta("footprint", Vector3(0.4, 0.4, 0.4))
		inst.position = Vector3(x + fp.x / 2, 0.0, 0.2)
		x += fp.x + 0.12
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	await _shot("models_laptops_front", Vector3(0, 0.75, 3.4), Vector3(0, 0.1, 1.8))
	await _shot("models_laptops_side", Vector3(1.9, 0.45, 1.6), Vector3(0, 0.08, 1.4))
	await _shot("models_laptops_back", Vector3(0.2, 0.55, 0.75), Vector3(0, 0.12, 1.4))
	await _shot("models_laptops_closed", Vector3(0, 1.0, 3.0), Vector3(0, 0.0, 2.2))
	await _shot("models_others", Vector3(0, 0.9, 2.0), Vector3(0, 0.15, 0.2))
	var i := 0
	for p in laptops:
		var px := -(laptops.size() - 1) * 0.25 + i * 0.5
		await _shot("models_close_%s" % p.get_file().get_basename(), Vector3(px + 0.18, 0.42, 1.95), Vector3(px, 0.1, 1.35))
		i += 1
	get_tree().quit()


func _scenes(category: String) -> Array[String]:
	var out: Array[String] = []
	var base := ROOT.path_join(category)
	for d in DirAccess.get_directories_at(base):
		var p := base.path_join(d).path_join(d + ".tscn")
		if ResourceLoader.exists(p):
			out.append(p)
	return out


func _shot(n: String, from: Vector3, at: Vector3) -> void:
	_cam.global_position = from
	_cam.look_at(at)
	for k in 10:
		await get_tree().process_frame
	await RenderingServer.frame_post_draw
	get_viewport().get_texture().get_image().save_png("res://tools/out/%s.png" % n)
	print("saved ", n)


func _test_card() -> ImageTexture:
	## Bright border + "TOP" bar so coverage and orientation are obvious.
	var img := Image.create(320, 200, false, Image.FORMAT_RGB8)
	img.fill(Color(0.1, 0.35, 0.8))
	img.fill_rect(Rect2i(0, 0, 320, 6), Color(1, 0.2, 0.2))
	img.fill_rect(Rect2i(0, 194, 320, 6), Color(1, 0.2, 0.2))
	img.fill_rect(Rect2i(0, 0, 6, 200), Color(1, 0.2, 0.2))
	img.fill_rect(Rect2i(314, 0, 6, 200), Color(1, 0.2, 0.2))
	img.fill_rect(Rect2i(110, 20, 100, 24), Color(1, 1, 0.2))   # top marker
	img.fill_rect(Rect2i(20, 150, 40, 30), Color(0.2, 1, 0.3))   # bottom-left marker
	return ImageTexture.create_from_image(img)
