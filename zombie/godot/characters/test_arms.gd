extends Node3D
## The first-person arms on their own: a camera, the game's ViewModel, and scripts/arms.gd driven as the game
## drives it. Keys: 1-6 change what is held, right mouse aims, left mouse swings / fires, R reloads.
## Screenshots:  godot --path zombie/godot res://characters/test_arms.tscn -- --shots=/some/dir [--torso=hoodie] [--night]

class FakePlayer:
	var worn := {"torso": null}
class FakeGame extends Node:
	var vm
	var player
	var zombies = null
	var state := "play"

var cam: Camera3D
var vm
var arms
var fake: FakeGame
var shots_dir := ""
var plan: Array = []
var _wait := 0
var _t := 0.0
var items := ["pistol", "rifle", "bat", "", "shotgun", "knife", "axe", "flashlight"]
var mouse := Vector2.ZERO

func _ready() -> void:
	var torso := ""
	var night := false
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--shots="): shots_dir = a.substr(8)
		if a.begins_with("--torso="): torso = a.substr(8)
		if a == "--night": night = true
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-48, -32, 0); sun.light_energy = 0.2 if night else 1.25; sun.light_color = Color(1.0, 0.96, 0.9); sun.shadow_enabled = true
	add_child(sun)
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color(0.32, 0.47, 0.72); sky_mat.sky_horizon_color = Color(0.68, 0.74, 0.8)
	sky_mat.ground_bottom_color = Color(0.2, 0.19, 0.17); sky_mat.ground_horizon_color = Color(0.62, 0.66, 0.68)
	var sky: Resource = ClassDB.instantiate(&"Sky")
	sky.set(&"sky_material", sky_mat)
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY; env.set(&"sky", sky)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY; env.ambient_light_energy = 0.3 if night else 0.9
	env.background_energy_multiplier = 0.1 if night else 1.0
	env.tonemap_mode = Environment.TONE_MAPPER_ACES; env.tonemap_white = 6.0
	var we := WorldEnvironment.new(); we.environment = env; add_child(we)
	var ground := MeshInstance3D.new()
	var pm := PlaneMesh.new(); pm.size = Vector2(80, 80); ground.mesh = pm
	var gm := StandardMaterial3D.new(); gm.albedo_color = Color(0.3, 0.33, 0.24); gm.roughness = 1.0; ground.material_override = gm
	add_child(ground)
	for i in 5:
		var post := MeshInstance3D.new()
		var bm := BoxMesh.new(); bm.size = Vector3(0.6, 1.8, 0.6); post.mesh = bm
		post.position = Vector3(-4.0 + i * 2.0, 0.9, -6.0 - (i % 2) * 2.0)
		var mm := StandardMaterial3D.new(); mm.albedo_color = Color(0.45, 0.4, 0.36); post.material_override = mm
		add_child(post)
	cam = Camera3D.new(); cam.far = 400.0; cam.near = 0.06; cam.fov = 75.0
	cam.position = Vector3(0, 1.62, 0)
	add_child(cam); cam.current = true
	vm = load("res://scripts/viewmodel.gd").new()
	cam.add_child(vm)
	fake = FakeGame.new(); fake.vm = vm; fake.player = FakePlayer.new()
	if torso != "": fake.player.worn.torso = {"id": torso}
	add_child(fake)
	arms = load("res://scripts/arms.gd").new()
	arms.game = fake
	arms.debug = OS.get_cmdline_user_args().has("--debug")
	add_child(arms)
	if night:
		arms.set_meta("night", 1.0)
	if shots_dir != "" and OS.get_cmdline_user_args().has("--one"):
		plan = [["pistol", 0.5, "ads", "pistol_aim"], ["shotgun", 0.7, "", "shotgun"]]
		_begin()
	elif shots_dir != "":
		# [item, seconds to settle, what to do, name]
		plan = [["", 0.6, "", "a0"], ["pistol", 0.6, "", "pistol"], ["pistol", 0.5, "ads", "pistol_aim"], ["rifle", 0.7, "", "rifle"], ["rifle", 0.5, "ads", "rifle_aim"], ["shotgun", 0.7, "", "shotgun"],
			["bat", 0.7, "", "bat"], ["bat", 0.22, "swing", "bat_swing"], ["knife", 0.7, "", "knife"], ["axe", 0.7, "", "axe"], ["", 0.7, "", "hands"], ["", 0.14, "swing", "hands_punch"], ["", 0.12, "shove", "hands_shove"],
			["pistol", 0.6, "down", "pistol_look_down"], ["flashlight", 0.6, "", "flashlight"]]
		_begin()

func _begin() -> void:
	if plan.is_empty():
		get_tree().quit(); return
	var e: Array = plan[0]
	vm.set_item(String(e[0]))
	vm.want_ads = false
	cam.rotation = Vector3.ZERO
	_t = 0.0; _wait = 0
	if e[2] == "down": cam.rotation.x = -0.75

func _process(delta: float) -> void:
	var dt := 1.0 / 30.0 if shots_dir != "" else delta
	if shots_dir == "":
		vm.want_ads = Input.is_mouse_button_pressed(MOUSE_BUTTON_RIGHT)
		vm.update(dt, mouse, 0.0, true); mouse = Vector2.ZERO
		return
	var e: Array = plan[0]
	if _wait > 0:
		_wait -= 1
		if _wait == 0:
			var img := get_viewport().get_texture().get_image()
			img.save_png(shots_dir.path_join(String(e[3]) + ".png"))
			# and the lower middle of the view enlarged, to look at the hands
			var sz := img.get_size()
			var crop := img.get_region(Rect2i(int(sz.x * 0.2), int(sz.y * 0.45), int(sz.x * 0.6), int(sz.y * 0.55)))
			crop.resize(crop.get_width() * 2, crop.get_height() * 2, Image.INTERPOLATE_LANCZOS)
			crop.save_png(shots_dir.path_join(String(e[3]) + "_zoom.png"))
			print("shot ", e[3])
			plan.pop_front()
			_begin()
		return
	_t += dt
	var settle := 0.6
	if e[2] == "ads": vm.want_ads = true
	if (e[2] == "swing" or e[2] == "shove") and _t >= settle and _t - dt < settle:
		vm.swing(0.5 if e[2] == "swing" else 0.35)
	vm.update(dt, Vector2.ZERO, 0.0, true)
	if _t >= settle + (float(e[1]) if (e[2] == "swing" or e[2] == "shove" or e[2] == "ads") else 0.2):
		_wait = 3

func _unhandled_input(ev: InputEvent) -> void:
	if ev is InputEventMouseMotion: mouse += ev.relative
	if ev is InputEventMouseButton and ev.pressed and ev.button_index == MOUSE_BUTTON_LEFT:
		if vm.firearm: vm.recoil(0.6)
		else: vm.swing(0.5)
	if ev is InputEventKey and ev.pressed:
		if ev.keycode >= KEY_1 and ev.keycode <= KEY_8: vm.set_item(items[ev.keycode - KEY_1])
		if ev.keycode == KEY_R: vm.reload(1.6)
		if ev.keycode == KEY_F: vm.swing(0.35)
		if ev.keycode == KEY_ESCAPE: get_tree().quit()
