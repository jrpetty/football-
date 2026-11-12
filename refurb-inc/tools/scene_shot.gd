extends Node
## Renders any scene from a few angles on a neutral studio floor, for visual QA.
##   godot --path . res://tools/scene_shot.tscn -- <res://scene.tscn> <out_name> [pose]
## pose "lorry_open": raises "RearDoor" 2.2 m and lowers "TailLift" to the ground (the delivery moment).

var _cam: Camera3D


func _ready() -> void:
	get_window().size = Vector2i(1600, 900)
	var a := OS.get_cmdline_user_args()
	var scene_path: String = a[0]
	var out: String = a[1] if a.size() > 1 else "scene"
	var pose: String = a[2] if a.size() > 2 else ""
	var env := WorldEnvironment.new()
	var e := Environment.new()
	e.background_mode = Environment.BG_COLOR
	e.background_color = Color(0.62, 0.7, 0.8)
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color(0.85, 0.87, 0.9)
	e.ambient_light_energy = 0.6
	e.tonemap_mode = Environment.TONE_MAPPER_AGX
	e.ssao_enabled = true
	env.environment = e
	add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-42, 40, 0)
	sun.light_energy = 1.6
	sun.shadow_enabled = true
	add_child(sun)
	var fl := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(200, 200)
	fl.mesh = pm
	fl.material_override = Mats.surface("concrete_floor", 2.0)
	add_child(fl)
	var inst: Node3D = load(scene_path).instantiate()
	add_child(inst)
	await get_tree().process_frame
	match pose:
		"lorry_open":
			var door := inst.find_child("RearDoor", true, false) as Node3D
			var lift := inst.find_child("TailLift", true, false) as Node3D
			if door and "open_frac" in door:
				door.open_frac = 0.85
			elif door:
				door.position.y += 2.2
			if lift:
				lift.position.y = 0.05
	var box := _bounds(inst)
	var c := box.get_center()
	var r := box.size.length() * 0.75 + 0.5
	_cam = Camera3D.new()
	_cam.fov = 45
	add_child(_cam)
	_cam.current = true
	var views := {"34front": Vector3(0.8, 0.55, 1.0), "34rear": Vector3(-0.8, 0.5, -1.0), "side": Vector3(1, 0.25, 0.0)}
	for vn in views:
		var d: Vector3 = views[vn].normalized()
		_cam.position = c + d * r
		_cam.look_at(c)
		for k in 8:
			await get_tree().process_frame
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png("res://tools/out/%s_%s.png" % [out, vn])
		print("saved ", out, "_", vn)
	get_tree().quit()


func _bounds(n: Node) -> AABB:
	var have := false
	var box := AABB()
	for mi in n.find_children("*", "MeshInstance3D", true, false):
		var m := mi as MeshInstance3D
		if m.mesh == null:
			continue
		var b: AABB = m.global_transform * m.mesh.get_aabb()
		box = b if not have else box.merge(b)
		have = true
	return box
