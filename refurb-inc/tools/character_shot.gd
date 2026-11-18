extends Node
## Renders a line-up of characters (builds, hairstyles, poses, hi-vis, photo face) for visual QA, plus a
## close-up of the faces. Saves tools/out/characters.png and characters_faces.png.
##   godot --path . res://tools/character_shot.tscn [-- <photo.jpg>]
func _ready() -> void:
	get_window().size = Vector2i(1920, 1080)
	var a := OS.get_cmdline_user_args()
	var env := WorldEnvironment.new()
	var e := Environment.new()
	e.background_mode = Environment.BG_COLOR
	e.background_color = Color(0.5, 0.55, 0.62)
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color(0.8, 0.82, 0.88)
	e.ambient_light_energy = 0.55
	e.tonemap_mode = Environment.TONE_MAPPER_AGX
	e.ssao_enabled = true
	env.environment = e
	add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-35, 30, 0)
	sun.light_energy = 1.5
	sun.shadow_enabled = true
	add_child(sun)
	var fill := DirectionalLight3D.new()
	fill.rotation_degrees = Vector3(-15, -140, 0)
	fill.light_energy = 0.4
	add_child(fill)
	var floor_mi := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(30, 30)
	floor_mi.mesh = pm
	floor_mi.material_override = Mats.color(Color(0.35, 0.36, 0.38), 0.8)
	add_child(floor_mi)
	var face := PackedByteArray()
	if not a.is_empty() and Looks.set_photo(a[0]) == "":
		face = Looks.face_png()
	var base := {"shirt": "#3359a0", "trousers": "#292b33", "skin": "#dbb08f", "hair": "#33241a", "hivis": false, "cap": false, "height": 1.78, "bighead": false, "body": "m", "hairstyle": "short", "shoes": "#1a1a1c"}
	var cast := [
		[{"shirt": "#3359a0", "hairstyle": "short"}, "idle", 0.0, false],
		[{"body": "f", "shirt": "#c0392b", "skin": "#f2d3bd", "hair": "#8c6233", "hairstyle": "long", "height": 1.68, "trousers": "#3d4b63"}, "walk", 1.4, false],
		[{"shirt": "#27ae60", "skin": "#7b4a2e", "hair": "#141211", "hairstyle": "buzz", "shoes": "#e8e6e1"}, "carry", 0.0, false],
		[{"body": "f", "shirt": "#8e44ad", "skin": "#c68e6a", "hair": "#141211", "hairstyle": "bun", "height": 1.65}, "wave", 0.0, false],
		[{"shirt": "#2c3e50", "hivis": true, "cap": true, "hairstyle": "side", "skin": "#a26a48", "height": 1.85}, "push", 0.0, false],
		[{"shirt": "#f39c12", "hairstyle": "curly", "hair": "#c4462b", "skin": "#f2d3bd", "trousers": "#5b4632"}, "idle", 0.0, true],
	]
	var rigs: Array = []
	for i in cast.size():
		var look: Dictionary = base.duplicate()
		look.merge(cast[i][0], true)
		var rig := CharacterRig.new()
		add_child(rig)
		rig.position = Vector3(-3.0 + i * 1.2, 0, 0)
		Looks.apply(rig, look, face if cast[i][3] else PackedByteArray())
		rig.anim = "idle" if cast[i][1] == "walk" else cast[i][1]
		rig.move_speed = float(cast[i][2])
		rigs.append(rig)
	var cam := Camera3D.new()
	add_child(cam)
	cam.position = Vector3(0, 1.25, 5.6)
	cam.fov = 42
	cam.look_at(Vector3(0, 0.95, 0))
	for k in 40:
		await get_tree().process_frame
	await RenderingServer.frame_post_draw
	get_viewport().get_texture().get_image().save_png("res://tools/out/characters.png")
	# Faces close up.
	cam.fov = 30
	cam.position = Vector3(-1.8, 1.62, 1.6)
	cam.look_at(Vector3(-1.8, 1.58, 0))
	for k in 6:
		await get_tree().process_frame
	await RenderingServer.frame_post_draw
	get_viewport().get_texture().get_image().save_png("res://tools/out/characters_faces.png")
	# Three-quarter view of the line-up.
	cam.fov = 40
	cam.position = Vector3(4.5, 1.6, 4.2)
	cam.look_at(Vector3(0, 0.9, 0))
	for k in 6:
		await get_tree().process_frame
	await RenderingServer.frame_post_draw
	get_viewport().get_texture().get_image().save_png("res://tools/out/characters_side.png")
	if not face.is_empty():
		Looks.clear_photo()
	get_tree().quit()
