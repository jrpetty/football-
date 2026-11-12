extends Node3D
## Visual QA for shadow stand-ins (tools/shadow_proxies.gd): a row of heavy props in low sun, shadows cast by the
## detailed meshes (left) vs by the stand-ins as shipped (right), saved side by side to tools/out/shadow_<dist>m.png.
##   Godot --path . res://tools/shadow_compare.tscn

const MODELS := ["res://assets/models/stations/heavy_rack/heavy_rack.tscn", "res://assets/models/automation/conveyor_straight/conveyor_straight.tscn",
	"res://assets/models/stations/metal_shelf/metal_shelf.tscn", "res://assets/models/stations/test_bench_esd/test_bench_esd.tscn",
	"res://assets/models/servers/server_tower/server_tower.tscn", "res://assets/models/stations/office_desk/office_desk.tscn"]


func _ready() -> void:
	get_window().size = Vector2i(1280, 720)
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	var env := WorldEnvironment.new()
	env.environment = Environment.new()
	env.environment.background_mode = Environment.BG_COLOR
	env.environment.background_color = Color(0.55, 0.6, 0.68)
	env.environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.environment.ambient_light_color = Color(0.6, 0.62, 0.65)
	env.environment.ambient_light_energy = 0.6
	env.environment.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-28, -35, 0)
	sun.shadow_enabled = true
	add_child(sun)
	var floor_mi := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(80, 80)
	floor_mi.mesh = pm
	add_child(floor_mi)
	var x := -5.5
	for p in MODELS:
		if not ResourceLoader.exists(p):
			continue
		var inst: Node3D = load(p).instantiate()
		add_child(inst)
		inst.position = Vector3(x, 0, 0)
		x += 2.3
	var cam := Camera3D.new()
	cam.fov = 60.0
	add_child(cam)
	cam.make_current()
	for dist in [3.0, 10.0]:
		var target := Vector3(0, 0.8, 0) if dist > 3.0 else Vector3(-5.5, 0.9, 0)
		cam.global_position = target + Vector3(0, 0.6 + dist * 0.25, dist)
		cam.look_at(target)
		cam.fov = 60.0 if dist > 3.0 else 50.0
		var imgs: Array = []
		for use_proxy in [false, true]:
			for p: MeshInstance3D in find_children("ShadowProxy", "MeshInstance3D", true, false):
				p.visible = use_proxy
				for mi: MeshInstance3D in p.get_parent().get_children().filter(func(c): return c is MeshInstance3D and c.name != "ShadowProxy"):
					mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF if use_proxy else GeometryInstance3D.SHADOW_CASTING_SETTING_ON
			for i in 8:
				await get_tree().process_frame
			await RenderingServer.frame_post_draw
			imgs.append(get_viewport().get_texture().get_image())
		var w: int = imgs[0].get_width()
		var h: int = imgs[0].get_height()
		var out := Image.create(w * 2, h, false, imgs[0].get_format())
		out.blit_rect(imgs[0], Rect2i(0, 0, w, h), Vector2i(0, 0))
		out.blit_rect(imgs[1], Rect2i(0, 0, w, h), Vector2i(w, 0))
		out.save_png("res://tools/out/shadow_%dm.png" % int(dist))
		print("saved shadow_%dm (left: detailed meshes cast, right: stand-ins)" % int(dist))
	get_tree().quit()
