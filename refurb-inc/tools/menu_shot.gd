extends Node
## Renders the title-screen panels over a plain backdrop (no level): main buttons, "Name your business" for a Steam
## host, and "Join a game". Saves tools/out/menu_*.png.   Godot --path . res://tools/menu_shot.tscn [-- --steam]


func _ready() -> void:
	get_window().size = Vector2i(1600, 900)
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	if "--steam" in OS.get_cmdline_user_args():
		Net.start_steam()
	var bg := ColorRect.new()
	bg.color = Color(0.16, 0.18, 0.2)
	bg.set_anchors_preset(Control.PRESET_FULL_RECT)
	add_child(bg)
	var cl := CanvasLayer.new()
	add_child(cl)
	var menu := MainMenu.new()
	cl.add_child(menu)
	await _save("menu_main")
	menu._ask_name("host_steam")
	await _save("menu_name_steam")
	menu._name_box.visible = false
	menu._open_join()
	for i in 90:
		await get_tree().process_frame     # give a lobby search time to come back
	await _save("menu_join")
	get_tree().quit()


func _save(n: String) -> void:
	for i in 6:
		await get_tree().process_frame
	await RenderingServer.frame_post_draw
	get_viewport().get_texture().get_image().save_png("res://tools/out/%s.png" % n)
	print("saved ", n)
