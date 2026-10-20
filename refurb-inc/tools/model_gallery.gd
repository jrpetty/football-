extends Node
## Visual QA for every processed model (assets/models/<category>/<name>/<name>.tscn): one row per category,
## each model on a grid square with a +Z arrow (front) and its name. Saves tools/out/gallery_<category>.png.
##   godot --path . res://tools/model_gallery.tscn [-- --only <category>]

const ROOT := "res://assets/models"
const SKIP := ["laptops", "desktops", "servers"]   # covered by model_showcase.tscn

var _cam: Camera3D


func _ready() -> void:
	get_window().size = Vector2i(1600, 900)
	var args := OS.get_cmdline_user_args()
	var only := args[args.find("--only") + 1] if args.find("--only") >= 0 else ""
	var env := WorldEnvironment.new()
	var e := Environment.new()
	e.background_mode = Environment.BG_COLOR
	e.background_color = Color(0.36, 0.38, 0.41)
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color(0.8, 0.8, 0.82)
	e.ambient_light_energy = 0.55
	e.tonemap_mode = Environment.TONE_MAPPER_AGX
	e.ssao_enabled = true
	env.environment = e
	add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-48, 35, 0)
	sun.light_energy = 1.5
	sun.shadow_enabled = true
	add_child(sun)
	var floor_mi := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(200, 200)
	floor_mi.mesh = pm
	floor_mi.material_override = Mats.color(Color(0.55, 0.56, 0.58), 0.9)
	add_child(floor_mi)
	_cam = Camera3D.new()
	_cam.fov = 40
	add_child(_cam)
	_cam.current = true
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	for cat in DirAccess.get_directories_at(ROOT):
		if cat in SKIP or (only != "" and cat != only):
			continue
		await _category(cat)
	get_tree().quit()


func _category(cat: String) -> void:
	var stage := Node3D.new()
	add_child(stage)
	var paths: Array[String] = []
	for d in DirAccess.get_directories_at(ROOT.path_join(cat)):
		var p := ROOT.path_join(cat).path_join(d).path_join(d + ".tscn")
		if ResourceLoader.exists(p):
			paths.append(p)
	var x := 0.0
	var max_h := 0.3
	var total_w := 0.0
	var sizes: Array = []
	for p in paths:
		var inst: Node3D = load(p).instantiate()
		var fp: Vector3 = inst.get_meta("footprint", Vector3(0.5, 0.5, 0.5))
		sizes.append(fp)
		total_w += maxf(fp.x, 0.25) + 0.35
		max_h = maxf(max_h, fp.y)
		inst.queue_free()
	x = -total_w / 2.0
	for i in paths.size():
		var fp: Vector3 = sizes[i]
		var inst: Node3D = load(paths[i]).instantiate()
		stage.add_child(inst)
		var w := maxf(fp.x, 0.25)
		inst.position = Vector3(x + w / 2.0, 0, 0)
		# Front marker: a small orange arrow on the floor pointing +Z.
		var arrow := MeshInstance3D.new()
		var pr := PrismMesh.new()
		pr.size = Vector3(minf(w * 0.4, 0.3), minf(w * 0.4, 0.3), 0.005)
		arrow.mesh = pr
		arrow.material_override = Mats.color(Color(1, 0.5, 0.1), 0.5)
		arrow.rotation_degrees = Vector3(-90, 0, 0)
		arrow.position = Vector3(x + w / 2.0, 0.003, fp.z / 2.0 + 0.2)
		stage.add_child(arrow)
		var lab := Label3D.new()
		lab.text = paths[i].get_file().get_basename()
		lab.pixel_size = clampf(total_w / 900.0, 0.0012, 0.02)
		lab.font_size = 32
		lab.outline_size = 8
		lab.position = Vector3(x + w / 2.0, 0.01, fp.z / 2.0 + 0.45 + total_w * 0.02)
		lab.rotation_degrees = Vector3(-60, 0, 0)
		stage.add_child(lab)
		x += w + 0.35
	var dist := maxf(total_w * 0.75, max_h * 2.2) + 1.0
	_cam.position = Vector3(0, max_h * 0.6 + dist * 0.45, dist)
	_cam.look_at(Vector3(0, max_h * 0.35, 0))
	for k in 10:
		await get_tree().process_frame
	await RenderingServer.frame_post_draw
	get_viewport().get_texture().get_image().save_png("res://tools/out/gallery_%s.png" % cat)
	print("saved ", cat)
	stage.queue_free()
	await get_tree().process_frame
