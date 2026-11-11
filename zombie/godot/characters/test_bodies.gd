extends Node3D
## Line-ups of people and the six zombie kinds, playing the baked clips.
##
## Open characters/test_bodies.tscn and press F6. Left / Right change page, Space pauses, N toggles night.
## Screenshots for review (software or hardware renderer):
##   godot --path zombie/godot res://characters/test_bodies.tscn -- --shots=/some/dir [--pages=0,3] [--crowd=200]

const BodiesScript := preload("res://characters/bodies.gd")

var bodies: Node3D
var cam: Camera3D
var sun: DirectionalLight3D
var env: Environment
var page := 0
var ids: Array[int] = []
var movers: Array = []          # [id, start, dir, speed]
var shots_dir := ""
var shot_pages: Array = []
var _t := 0.0
var _frame := 0
var _shot_at := -1
var _crowd := 0
var _gain := -1.0
var _noshadow := false
var _fill := -1.0

const PAGES := ["people", "portraits", "acts", "tools", "walk", "zombies", "zombie_walk", "zombie_attack", "zombie_floor", "decay", "night", "crowd"]

func _ready() -> void:
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--shots="): shots_dir = a.substr(8)
		if a.begins_with("--pages="):
			for p in a.substr(8).split(","): shot_pages.append(int(p))
		if a.begins_with("--crowd="): _crowd = int(a.substr(8))
		if a.begins_with("--gain="): _gain = float(a.substr(7))
		if a == "--noshadow": _noshadow = true
		if a.begins_with("--fill="): _fill = float(a.substr(7))
	# a plain outdoor set: sun, sky, ground
	sun = DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-48, -32, 0)
	sun.light_energy = 1.25
	sun.light_color = Color(1.0, 0.96, 0.9)
	sun.shadow_enabled = true
	sun.directional_shadow_max_distance = 40.0
	if _noshadow: sun.shadow_enabled = false
	add_child(sun)
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color(0.32, 0.47, 0.72); sky_mat.sky_horizon_color = Color(0.68, 0.74, 0.8)
	sky_mat.ground_bottom_color = Color(0.2, 0.19, 0.17); sky_mat.ground_horizon_color = Color(0.62, 0.66, 0.68)
	var sky: Resource = ClassDB.instantiate(&"Sky")      # by name: a project script may shadow the built-in class
	sky.set(&"sky_material", sky_mat)
	env = Environment.new()
	env.background_mode = Environment.BG_SKY; env.set(&"sky", sky)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY; env.ambient_light_energy = 0.9
	env.tonemap_mode = Environment.TONE_MAPPER_ACES; env.tonemap_white = 6.0      # the same light the game's SkyRig sets at noon
	var we := WorldEnvironment.new(); we.environment = env; add_child(we)
	var ground := MeshInstance3D.new()
	var pm := PlaneMesh.new(); pm.size = Vector2(120, 120); ground.mesh = pm
	var gm := StandardMaterial3D.new(); gm.albedo_color = Color(0.3, 0.33, 0.24); gm.roughness = 1.0; ground.material_override = gm
	add_child(ground)
	cam = Camera3D.new(); cam.fov = 30.0; add_child(cam); cam.current = true
	bodies = BodiesScript.new()
	add_child(bodies)
	if _gain > 0.0: bodies.albedo_gain = _gain
	if _fill >= 0.0: bodies.fill_light = _fill
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--debug="): bodies.material.set_shader_parameter(&"debug_view", int(a.substr(8)))
		if a == "--linear": env.tonemap_mode = Environment.TONE_MAPPER_LINEAR
	if shots_dir != "":
		if shot_pages.is_empty():
			for i in PAGES.size() - 1: shot_pages.append(i)
		page = shot_pages[0]
	show_page(page)

func look(from: Vector3, to: Vector3, fov := 30.0) -> void:
	cam.fov = fov
	cam.look_at_from_position(from, to, Vector3.UP)

func clear() -> void:
	for id in ids: bodies.remove_body(id)
	ids.clear(); movers.clear()
	bodies.night = 0.0
	sun.light_energy = 1.25; env.ambient_light_energy = 0.9; env.background_energy_multiplier = 1.0

func put(kind: int, seed: float, x: float, z: float, yaw: float, clip: StringName, opts := {}, state := {}, lk := {}) -> int:
	var id: int = bodies.add_body(kind, seed, lk)
	ids.append(id)
	if not state.is_empty(): bodies.set_state(id, state)
	bodies.set_pose(id, Vector3(x, 0, z), yaw)
	bodies.set_anim(id, clip, opts)
	return id

func show_page(p: int) -> void:
	clear()
	page = posmod(p, PAGES.size())
	_t = 0.0; _frame = 0
	var H := BodiesScript.KIND_HUMAN
	match PAGES[page]:
		"people":
			for k in 8: put(H, 3.0 + k * 5.1, (k - 3.5) * 0.95, 0, 0.3, &"idle", {"phase": k * 0.13})
			look(Vector3(0, 1.4, 7.4), Vector3(0, 0.95, 0))
		"portraits":
			var seeds := [3.0, 13.2, 23.4, 8.1, 38.7]
			for k in 5: put(H, seeds[k], (k - 2) * 0.5, 0, 0.25, &"idle", {"stances": false})
			look(Vector3(0.2, 1.62, 2.2), Vector3(0, 1.6, 0))
		"acts":
			var acts := [&"idle", &"swing", &"tend", &"sit", &"cower", &"talk", &"carry", &"lie", &"wave", &"stir", &"eat"]
			for k in acts.size(): put(H, 3.0 + k * 5.1, (k - 5) * 1.05, 0, 0.6, acts[k], {"phase": 0.3, "stances": false})
			look(Vector3(0, 1.6, 11.5), Vector3(0, 0.8, 0))
		"tools":
			var acts2 := [&"idle", &"aim", &"swing", &"tend", &"swing", &"melee", &"tend", &"stir"]
			for k in 8:
				put(H, 3.0 + k * 5.1, (k - 3.5) * 1.05, 0.9, 0.7, &"aim" if k == 1 else &"idle", {}, {"tool": k})
				put(H, 3.0 + k * 5.1, (k - 3.5) * 1.05, -1.3, PI / 2 - 0.5, acts2[k], {"phase": 0.45, "loop": true}, {"tool": k})
			look(Vector3(0, 2.6, 10.5), Vector3(0, 0.75, -0.2))
		"walk":
			for k in 8:
				var id := put(H, 3.0 + k * 5.1, -4.0 + k * 0.2, (k - 3.5) * 1.0, PI / 2, &"run" if k >= 6 else &"walk_carry" if k == 4 else &"walk", {"speed": 4.6 if k >= 6 else 1.4, "phase": k * 0.17}, {"tool": 2 if k == 2 else 3 if k == 3 else 0})
				movers.append([id, Vector3(-4.0 + k * 0.2, 0, (k - 3.5) * 1.0), Vector3(1, 0, 0), 4.6 if k >= 6 else 1.4])
			look(Vector3(0, 3.2, 11.5), Vector3(0, 0.8, 0))
		"zombies":
			for k in 6: put(k, 10.0 + k * 3.7, (k - 2.5) * 1.3, 0, 0.45, &"idle", {"phase": k * 0.2}, {"decay": 0.45 if k == 0 else 0.4})
			look(Vector3(0, 1.5, 8.2), Vector3(0, 1.0, 0))
		"zombie_walk":
			var vs := [0.9, 5.0, 2.6, 3.0, 1.3, 2.3]
			for k in 6:
				var id := put(k, 10.0 + k * 3.7, -3.0, (k - 2.5) * 1.4, PI / 2, &"walk" if k == 0 else &"chase", {"speed": vs[k], "phase": k * 0.2}, {"decay": 0.4})
				movers.append([id, Vector3(-3.0, 0, (k - 2.5) * 1.4), Vector3(1, 0, 0), vs[k]])
			for g in 5:
				var id2 := put(0, 40.0 + g * 3.3, -3.0, 5.0 + g * 1.2, PI / 2, &"walk", {"speed": 0.9, "phase": g * 0.3}, {"decay": 0.3 + g * 0.15})
				bodies.get_body(id2).gait = g; bodies.set_state(id2, {})
				bodies.set_anim(id2, &"walk", {"speed": 0.9, "restart": true})
				movers.append([id2, Vector3(-3.0, 0, 5.0 + g * 1.2), Vector3(1, 0, 0), 0.9])
			look(Vector3(1.5, 4.5, 16.0), Vector3(0.5, 0.6, 2.5))
		"zombie_attack":
			for k in 6:
				put(k, 10.0 + k * 3.7, (k - 2.5) * 2.1 - 0.45, 0, PI / 2 - 0.5, &"scream" if k == 3 else &"attack", {"phase": 0.24 if k != 3 else 0.9, "speed": 0.0}, {"decay": 0.4})
				put(k, 10.0 + k * 3.7, (k - 2.5) * 2.1 + 0.55, 0, PI / 2 - 0.5, &"attack", {"phase": 0.42, "speed": 0.0}, {"decay": 0.4})
			look(Vector3(0, 1.6, 13.0), Vector3(0, 1.0, 0))
		"zombie_floor":
			var row := [&"feed", &"crawl", &"lie_back0", &"rise_back0", &"rise_back0", &"lie_front0", &"rise_front0", &"slump", &"sniff", &"paw"]
			var ph := [0.2, 0.3, 0.0, 0.35, 0.7, 0.0, 0.55, 0.3, 0.3, 0.3]
			for k in row.size(): put(0, 12.3 + k * 3.0, (k - 4.5) * 1.45, 1.2, PI / 2 - 0.5, row[k], {"phase": ph[k], "speed": 0.0 if String(row[k]).begins_with("rise") else 1.0}, {"decay": 0.2 + (k % 4) * 0.2})
			for st in 5:
				for j in 2: put(0, 15.3 + st * 3.0, (st - 2) * 2.6 + j * 1.1 - 0.5, -1.6, PI / 2, StringName("death_%d" % st), {"phase": 0.45 if j == 0 else 1.0, "speed": 0.0}, {"decay": 0.4})
			look(Vector3(0, 5.5, 12.5), Vector3(0, 0.3, -0.2))
		"decay":
			var dcs := [0.0, 0.15, 0.35, 0.55, 0.75, 0.9, 1.0]
			for k in dcs.size(): put(0, 7.7, (k - 3) * 0.95, 0, 0.2, &"idle", {"phase": k * 0.1}, {"decay": dcs[k]})
			for k in 9: put(0, 40.0 + k * 3.3, (k - 4) * 0.9, -2.2, 0.2, &"idle", {"phase": k * 0.1}, {"decay": 0.3 + (k % 4) * 0.2, "arm_loss_l": k == 3, "arm_loss_r": k == 6})
			look(Vector3(0, 2.6, 9.5), Vector3(0, 0.9, -0.8))
		"night":
			for k in 6: put(k, 10.0 + k * 3.7, (k - 2.5) * 1.3, 0, 0.2, &"idle", {}, {"decay": 0.4})
			bodies.night = 1.0; sun.light_energy = 0.06; env.ambient_light_energy = 0.12; env.background_energy_multiplier = 0.05
			look(Vector3(0, 1.5, 8.2), Vector3(0, 1.0, 0))
		"crowd":
			var n := _crowd if _crowd > 0 else 200
			for i in n:
				var kind := H if i % 3 == 0 else (i % 7) % 6
				var x := (i % 20 - 9.5) * 1.3; var z := -(i / 20) * 1.6
				var id := put(kind, 1.7 + i * 0.91, x, z, 0.0, &"walk", {"speed": 1.0, "phase": i * 0.07}, {"decay": 0.2 + (i % 5) * 0.18})
				movers.append([id, Vector3(x, 0, z), Vector3(0, 0, 1), 1.0])
			look(Vector3(0, 9.0, 16.0), Vector3(0, 0.5, -5.0), 45.0)

func _process(delta: float) -> void:
	_t += delta; _frame += 1
	for m in movers:
		var p: Vector3 = m[1] + m[2] * fposmod(_t * m[3], 7.0)
		bodies.set_pose(m[0], p, atan2(m[2].x, m[2].z))
	if shots_dir != "":
		if _shot_at < 0 and _frame >= 4 and _t > 1.2:
			_shot_at = _frame + 1
		elif _shot_at >= 0 and _frame >= _shot_at:
			var img := get_viewport().get_texture().get_image()
			img.save_png(shots_dir.path_join("%s.png" % PAGES[page]))
			print("saved ", PAGES[page])
			shot_pages.pop_front()
			_shot_at = -1
			if shot_pages.is_empty(): get_tree().quit()
			else: show_page(shot_pages[0])

func _unhandled_input(e: InputEvent) -> void:
	if e is InputEventKey and e.pressed:
		match e.keycode:
			KEY_RIGHT: show_page(page + 1)
			KEY_LEFT: show_page(page - 1)
			KEY_SPACE: get_tree().paused = not get_tree().paused
			KEY_N: bodies.night = 1.0 - bodies.night
