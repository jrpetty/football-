extends Node3D
## A small staged scene for looking at the dead: a patch of the real map's ground, a stand-in for the player,
## two registered people, and a mixed crowd (all six strains, a dormant group, a feeding group, a crawler).
##
## Open scripts/zombie_scene.tscn and press F6 (Space pauses, N toggles night, 1-5 change the camera).
## Screenshots for review:
##   godot --path zombie/godot res://scripts/zombie_scene.tscn -- --shots=/some/dir [--night]

const ZombiesScript := preload("res://scripts/zombies.gd")
const C := Vector3(-126.0, 0.0, -78.0)          # open ground north-west of the town

var W: Node
var zs
var cam: Camera3D
var sun: DirectionalLight3D
var env: Environment
var host := {}
var shots_dir := ""
var night := false
var paused := false
var t := 0.0
var view := 0
var people: Array = []          # [prey, body id, kind of behaviour]
var player_body := -1
var counts := {"player_attacked": 0, "human_attacked": 0, "howl": 0, "burst": 0, "killed": 0}
var cast := {}
# screenshot plan: [sim time, view, file name]
var plan: Array = [[0.6, 0, "01_start_overview"], [0.6, 2, "02_dormant_group"], [0.6, 3, "03_feeding_group"], [2.2, 1, "04_they_notice"],
	[-1.0, 4, "05_howler"], [5.5, 1, "06_closing_in"], [7.4, 0, "07_overview_knockdown"], [9.0, 1, "08_at_the_player"], [9.6, 5, "09_person_attacked"], [14.0, 0, "10_overview_late"], [14.0, 1, "11_melee"]]
var _wait := 0

func at(dx: float, dz: float) -> Vector3:
	return Vector3(C.x + dx, W.ground_y(C.x + dx, C.z + dz), C.z + dz)

func _ready() -> void:
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--shots="): shots_dir = a.substr(8)
		if a == "--night": night = true
	W = get_node("/root/WorldData")
	sun = DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-48, -32, 0)
	sun.light_color = Color(1.0, 0.96, 0.9)
	sun.shadow_enabled = true
	sun.directional_shadow_max_distance = 70.0
	add_child(sun)
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color(0.32, 0.47, 0.72); sky_mat.sky_horizon_color = Color(0.68, 0.74, 0.8)
	sky_mat.ground_bottom_color = Color(0.2, 0.19, 0.17); sky_mat.ground_horizon_color = Color(0.62, 0.66, 0.68)
	var sky: Resource = ClassDB.instantiate(&"Sky")
	sky.set(&"sky_material", sky_mat)
	env = Environment.new()
	env.background_mode = Environment.BG_SKY; env.set(&"sky", sky)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.tonemap_mode = Environment.TONE_MAPPER_ACES; env.tonemap_white = 6.0
	var we := WorldEnvironment.new(); we.environment = env; add_child(we)
	_ground()
	cam = Camera3D.new(); cam.fov = 50.0; cam.far = 400.0; add_child(cam); cam.current = true
	zs = ZombiesScript.new()
	add_child(zs)
	zs.director_enabled = false
	zs.seed_rng(11)
	zs.player_attacked.connect(func(_z, _d): counts.player_attacked += 1)
	zs.human_attacked.connect(_on_human_attacked)
	zs.howl.connect(func(_p, _r, _z): counts.howl += 1)
	zs.burst.connect(func(_p, _r, _z): counts.burst += 1)
	zs.killed.connect(func(_z, _h): counts.killed += 1)
	_light()
	_stage()
	if shots_dir != "":
		zs.bodies.set_process(false)          # stepped by hand below, in fixed steps

func _light() -> void:
	sun.light_energy = 0.22 if night else 1.25
	sun.light_color = Color(0.62, 0.72, 1.0) if night else Color(1.0, 0.96, 0.9)
	env.ambient_light_energy = 0.3 if night else 0.9
	env.background_energy_multiplier = 0.08 if night else 1.0
	host["daylight"] = 0.0 if night else 1.0
	host["hours"] = 1.0 if night else 12.0

## a patch of the real terrain
func _ground() -> void:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var n := 60; var size := 3.0
	for j in n:
		for i in n:
			var x0 := C.x + (i - n / 2) * size; var z0 := C.z + (j - n / 2) * size
			var p := [Vector3(x0, 0, z0), Vector3(x0 + size, 0, z0), Vector3(x0 + size, 0, z0 + size), Vector3(x0, 0, z0 + size)]
			for k in 4: p[k].y = W.height_at(p[k].x, p[k].z)
			for k in [0, 1, 2, 0, 2, 3]:
				st.set_color(Color(0.3, 0.33, 0.24).lerp(Color(0.36, 0.33, 0.22), W.forest_at(p[k].x, p[k].z)))
				st.add_vertex(p[k])
	st.generate_normals()
	var mi := MeshInstance3D.new()
	mi.mesh = st.commit()
	var gm := StandardMaterial3D.new(); gm.vertex_color_use_as_albedo = true; gm.roughness = 1.0
	mi.material_override = gm
	add_child(mi)

func _stage() -> void:
	var B = zs.bodies
	host.merge({"player_pos": at(0, 0), "player_eye": at(0, 0) + Vector3(0, 1.6, 0), "player_noise": 0.0, "player_visibility": 1.0, "player_alive": true,
		"player_inside": false, "player_bleeding": false, "base_center": null, "base_heat": 0.0, "day": 9, "luck": 0.0, "camera_pos": at(0, 0)})
	zs.update(0.001, 0.0, host)
	# the player's stand-in, aiming north at what is coming
	player_body = B.add_body(B.KIND_HUMAN, 3.3, {"shirt": 2, "outfit": 1})
	B.set_state(player_body, {"tool": 1})
	B.set_pose(player_body, at(0, 0), PI)
	B.set_anim(player_body, &"aim")
	# two people: one holding her ground to the east, one running for it
	for e in [[9.0, 4.0, 7.7, "Ana", "stand"], [-7.0, 9.0, 21.4, "Marcus", "run"]]:
		var id: int = B.add_body(B.KIND_HUMAN, e[2])
		var p = zs.add_prey(self, e[3], id)
		p.pos = at(e[0], e[1]); p.yaw = PI
		B.set_pose(id, p.pos, p.yaw)
		B.set_anim(id, &"idle")
		people.append({"prey": p, "body": id, "how": e[4], "hp": 60.0, "t": 0.0})
	# the pack coming down from the north: walkers of every age, runners, and the special strains
	var decays := [0.02, 0.1, 0.3, 0.4, 0.5, 0.6, 0.7, 0.85, 0.95, 0.45]
	for i in 10:
		var z = zs.spawn(C.x - 9.0 + i * 2.1, C.z - 24.0 - (i % 3) * 2.5, 0, 0, {"decay": decays[i]})
		z.yaw = 0.0 + (i - 5) * 0.08
	cast["runner"] = zs.spawn(C.x + 14.0, C.z - 30.0, 1); cast.runner.yaw = 0.0
	zs.spawn(C.x - 15.0, C.z - 31.0, 1).yaw = 0.0
	cast["brute"] = zs.spawn(C.x + 4.0, C.z - 30.0, 2); cast.brute.yaw = 0.0
	cast["howler"] = zs.spawn(C.x - 4.0, C.z - 20.0, 3); cast.howler.yaw = 0.0
	cast["bloat"] = zs.spawn(C.x + 9.0, C.z - 26.0, 4); cast.bloat.yaw = 0.0
	cast["breaker"] = zs.spawn(C.x - 11.0, C.z - 27.0, 5); cast.breaker.yaw = 0.0
	# a crawler already close
	var cr = zs.spawn(C.x + 3.0, C.z - 9.0, 0, 0, {"decay": 0.6}); cr.crawler = true; cr.yaw = 0.0; zs._apply_decay(cr)
	# west: a dormant group, backs turned, two of them lying
	for i in 7:
		var z = zs.spawn(C.x - 27.0 - (i % 3) * 1.8, C.z - 2.0 + i * 1.5, 0, 0, {"decay": 0.5 + 0.06 * i})
		z.yaw = -PI * 0.5 + (i - 3) * 0.2
		z.state = &"dormant"; z.wake_t = 30.0
		if i == 2 or i == 5:
			z.lying = true; z.down_face = 1 if i == 2 else -1; z.anim = &"lie_back0" if i == 2 else &"lie_front0"
		else:
			z.anim = &"slump"
	# south-east: three feeding on a carcass
	zs.add_carrion(at(17.0, 15.0), 300.0)
	for i in 3:
		var a := i * 2.1 + 0.4
		var z = zs.spawn(C.x + 17.0 + cos(a) * 0.9, C.z + 15.0 + sin(a) * 0.9, 0, 0, {"decay": 0.45 + i * 0.15})
		z.yaw = atan2(-cos(a), -sin(a))
		z.state = &"feed"; z.feed_at = zs.carrion[0]; zs.carrion[0].feeders += 1
	# and one already dead
	var dead = zs.spawn(C.x - 3.0, C.z - 5.0, 0, 0, {"decay": 0.7})
	dead.yaw = 0.4
	zs.damage(dead, 999.0, Vector3(0, 0, -1), {"head": true})

func _on_human_attacked(p, _z, dmg: float) -> void:
	counts.human_attacked += 1
	for h in people:
		if h.prey == p and p.alive:
			h.hp -= dmg
			zs.bodies.set_anim(h.body, &"hit", {"restart": true})
			if h.hp <= 0.0:
				# she goes down, and is left where she fell
				p.alive = false; p.corpse = true; p.move = 0.0
				zs.bodies.set_anim(h.body, &"lie", {"fade": 0.4})

func _people(dt: float) -> void:
	var B = zs.bodies
	for h in people:
		var p = h.prey
		if not p.alive:
			continue
		h.t += dt
		if h.how == "run":
			# jogs a wide circle around the player
			var a: float = 2.2 + h.t * 0.33
			var np := at(cos(a) * 11.5, sin(a) * 11.5)
			p.yaw = atan2(np.x - p.pos.x, np.z - p.pos.z); p.pos = np; p.move = 2.0
			B.set_pose(h.body, p.pos, p.yaw)
			B.set_anim(h.body, &"run", {"speed": 3.8})
		else:
			var zn = zs.nearest(p.pos, 30.0)
			if zn != null:
				p.yaw = atan2(zn.pos.x - p.pos.x, zn.pos.z - p.pos.z)
				B.set_pose(h.body, p.pos, p.yaw)
				if B.current_clip(h.body) != &"hit" or B.anim_done(h.body):
					B.set_anim(h.body, &"melee" if zn.pos.distance_to(p.pos) < 2.2 else &"cower")
			p.move = 0.0; p.quiet = false

func _script(dt: float) -> void:
	# a few scripted blows so that knock-downs, a burst and a kill are in the pictures
	if t < 6.4 and t + dt >= 6.4:
		var zn = zs.nearest(at(0, -4), 12.0)
		if zn != null: zs.damage(zn, 12.0, Vector3(0, 0, -1), {"knock": 3.2})
		zs.noise(at(0, 0), 60.0)                  # the shot
	if t < 6.9 and t + dt >= 6.9 and cast.bloat.state != &"dead":
		zs.damage(cast.bloat, 999.0, Vector3(0, 0, -1), {"part": 1})
	if t < 8.0 and t + dt >= 8.0:
		var zn = zs.nearest(at(0, -2), 10.0)
		if zn != null: zs.damage(zn, 999.0, Vector3(0, 0, -1), {"head": true})

func _view(v: int) -> void:
	var pp: Vector3 = host.player_pos
	match v:
		0: cam.fov = 50.0; cam.look_at_from_position(pp + Vector3(16, 17, 26), pp + Vector3(0, 0.5, -6))
		1: cam.fov = 60.0; cam.look_at_from_position(pp + Vector3(1.6, 1.9, 4.2), pp + Vector3(-0.5, 1.1, -8))
		2: cam.fov = 35.0; cam.look_at_from_position(at(-17, 6) + Vector3(0, 2.2, 0), at(-28.5, 2.5) + Vector3(0, 0.8, 0))
		3: cam.fov = 35.0; cam.look_at_from_position(at(11, 20) + Vector3(0, 1.7, 0), at(17, 15) + Vector3(0, 0.5, 0))
		4:
			var hz = cast.howler
			cam.fov = 30.0; cam.look_at_from_position(hz.pos + Vector3(sin(hz.yaw) * 5.0 + 1.5, 1.7, cos(hz.yaw) * 5.0), hz.pos + Vector3(0, 1.4, 0))
		5:
			var ap: Vector3 = people[0].prey.pos
			cam.fov = 38.0; cam.look_at_from_position(ap + Vector3(5.5, 2.0, 5.0), ap + Vector3(0, 1.0, -0.5))
	host["camera_pos"] = cam.global_position

func _process(delta: float) -> void:
	if shots_dir == "":
		if not paused:
			_step(minf(delta, 0.05))
		_view(view)
		return
	# screenshot run: fixed steps; stop at each planned moment, let the frame draw, save it
	if _wait > 0:
		_wait -= 1
		if _wait == 0:
			var e: Array = plan.pop_front()
			var img := get_viewport().get_texture().get_image()
			img.save_png(shots_dir.path_join(String(e[2]) + ("_night" if night else "") + ".png"))
			var st := {}
			for z in zs.list: st[z.state] = int(st.get(z.state, 0)) + 1
			print("shot %s at %.1f s  states %s  signals %s  bodies %d" % [e[2], t, st, counts, zs.bodies.body_count()])
			if plan.is_empty():
				get_tree().quit()
		return
	if plan.is_empty():
		return
	# (a time below zero: when the howler is in full cry)
	var due: bool = t >= float(plan[0][0]) if float(plan[0][0]) >= 0.0 else ((cast.howler.state == &"scream" and cast.howler.state_t > 0.7) or t > 12.0)
	if due:
		_view(int(plan[0][1]))
		_wait = 3
		return
	_step(1.0 / 30.0)
	zs.bodies._process(1.0 / 30.0)

func _step(dt: float) -> void:
	_script(dt)
	_people(dt)
	zs.update(dt, dt, host)
	t += dt

func _unhandled_key_input(ev: InputEvent) -> void:
	if ev is InputEventKey and ev.pressed:
		match ev.keycode:
			KEY_SPACE: paused = not paused
			KEY_N:
				night = not night; _light()
			KEY_1, KEY_2, KEY_3, KEY_4, KEY_5: view = ev.keycode - KEY_1
			KEY_ESCAPE: get_tree().quit()
