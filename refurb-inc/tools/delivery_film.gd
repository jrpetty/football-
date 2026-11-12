extends Node
## Films a real lorry delivery in a level (arrive → shutter → tail lift → pallets wheeled off) as a sequence of
## frames, for checking the animation. Saves tools/out/delivery_<n>.png and a contact sheet.
##   godot --path . res://tools/delivery_film.tscn [-- --level garage --model corvex_rampart_2200]

var _cam: Camera3D


func _ready() -> void:
	get_window().size = Vector2i(1280, 720)
	var args := OS.get_cmdline_user_args()
	var level_id := args[args.find("--level") + 1] if args.find("--level") >= 0 else "garage"
	var model_id := args[args.find("--model") + 1] if args.find("--model") >= 0 else ""
	Game.new_game(1234)
	Game.state.building_id = level_id
	var level: Node = load(load("res://scripts/game_root.gd").level_path(level_id)).instantiate()
	add_child(level)
	await get_tree().create_timer(0.5).timeout
	var s := Game.state
	var sim := Game.sim
	# A two-pallet lot (laptops + servers if asked) arriving right now.
	var lot := {"id": s.make_id(), "seller_name": "Film Test", "count": 0, "total_price": 0, "status": "bought", "units": []}
	var models := [model_id] if model_id != "" else ["corvex_prospect_5420", "corvex_rampart_2200"]
	for m in models:
		for i in (6 if Content.model(m).get("size", "small") == "large" else 14):
			lot.units.append(sim.lots.roll_unit(m, 0.6).to_dict())
	lot.count = lot.units.size()
	s.lots[lot.id] = lot
	s.schedule.append({"at": s.minute + 0.5, "type": "lorry", "data": {"lot": lot.id}})
	_cam = Camera3D.new()
	_cam.fov = 58
	level.add_child(_cam)
	_cam.current = true
	var vd := level.get_node_or_null("VehicleDirector")
	var dock: Node3D = vd.get_node("Docks").get_child(0) if vd and vd.has_node("Docks") else null
	var spot := dock.global_transform if dock else Transform3D()
	var rear := -spot.basis.z.normalized()
	var side := spot.basis.x.normalized()
	var drop_node: Node3D = dock.get_node_or_null("Drop") if dock else null
	var drop := drop_node.global_position if drop_node else spot.origin + rear * 4.3
	# Stand where a player would: just beyond the drop spot, looking back at the lorry's open rear.
	_cam.global_position = drop + rear * 3.2 + side * 1.3 + Vector3(0, 2.0, 0)
	_cam.look_at(spot.origin + rear * 3.2 + Vector3(0, 1.1, 0))
	var shots: Array[Image] = []
	for i in 30:
		await get_tree().create_timer(1.5).timeout
		await RenderingServer.frame_post_draw
		var img := get_viewport().get_texture().get_image()
		img.save_png("res://tools/out/delivery_%02d.png" % i)
		img.resize(426, 240)
		shots.append(img)
	var sheet := Image.create(426 * 6, 240 * 5, false, shots[0].get_format())
	for i in shots.size():
		sheet.blit_rect(shots[i], Rect2i(0, 0, 426, 240), Vector2i((i % 6) * 426, (i / 6) * 240))
	sheet.save_png("res://tools/out/delivery_sheet.png")
	print("FILMED pallets: ", s.items.values().filter(func(it): return it.kind == Item.KIND_PALLET).size())
	get_tree().quit()
