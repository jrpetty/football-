extends Node3D
## REKINDLE — the main scene. Builds the world and runs the game: clock, player, interaction, combat,
## item use, the dead, saving.

const SAVE_PATH := "user://rekindle_save.json"
const SEASONS := ["Autumn", "Winter", "Spring", "Summer"]
const SEASON_TEMP := [11.0, -3.0, 12.0, 22.0]
const OBJECTIVES := [
	["Search the cabin", "Loot the containers in the cabin you woke up in. Tab opens your inventory."],
	["Arm yourself", "Put a weapon on the hotbar (1–8) and keep it in hand."],
	["Reach the town", "Follow the road east. The town has food, medicine and tools — and the dead."],
	["Survive", "Eat, drink, treat your wounds, and stay alive."],
]

var world: World
var sky: SkyRig
var cam: Camera3D
var hud: Hud
var player: Player
var vm: ViewModel
var flashlight: SpotLight3D
var zombies = null                 # Zombies (scripts/zombies.gd), when present
var state := "menu"                # menu | play | dead
var game_min := 8.0 * 60.0 + 30.0
var day_len := 24.0                # real minutes per game day
var time := 0.0
var rng := RandomNumberGenerator.new()
var action = null                  # {label, dur, t, cancel_on_move, done}
var target = null                  # what E would act on
var swing_timer := 0.0
var swing_hit_at := 0.0
var swing_hit_done := true
var swing_stack = null
var fire_cooldown := 0.0
var reloading := 0.0
var reload_stack = null
var shove_cd := 0.0
var moving_doors: Array = []
var objective := 0
var autosave_at := 0.0
var _mouse := Vector2.ZERO
var _food_tick := 0.0
var loud_t := 0.0
var shots_mode := false
## Optional systems, each a script in this list if it exists. A module is a Node with `var game` and any of:
##   mod_started(new_game: bool)                      a game began or was loaded
##   mod_input(ev: InputEvent) -> bool                true = consumed (called before the game's own keys)
##   mod_process(dt, dt_game_min, ui_open)            every frame of play
##   mod_interact(eye, fwd, reach, wall_t) -> Dictionary   {} or {t, prompt, act: Callable}
##   mod_blocks_control() -> bool                     true while the player may not move (asleep, placing…)
##   mod_save() -> Variant / mod_load(data)           persisted under the module's file name
const MODULES := ["sys_build", "sys_craft", "sys_sleep", "sys_cook", "sys_map", "arms", "music", "weather"]
var modules: Dictionary = {}
var fx: Fx
var _groan_t := 3.0

func _ready() -> void:
	rng.randomize()
	sky = SkyRig.new(); add_child(sky)
	world = World.new(); world.name = "World"; add_child(world)
	world.build()
	cam = Camera3D.new(); cam.far = 1400.0; cam.near = 0.06; cam.fov = 75.0; add_child(cam)
	cam.make_current()
	vm = ViewModel.new(); cam.add_child(vm)
	flashlight = SpotLight3D.new(); flashlight.spot_range = 55.0; flashlight.spot_angle = 30.0; flashlight.spot_attenuation = 0.9; flashlight.spot_angle_attenuation = 0.6
	flashlight.light_color = Color(1.0, 0.96, 0.88); flashlight.light_energy = 0.0; flashlight.shadow_enabled = true
	cam.add_child(flashlight)
	fx = Fx.new(); add_child(fx)
	hud = Hud.new(); hud.game = self; add_child(hud)
	hud.use_item.connect(use_item)
	hud.drop_item.connect(func(uid: int) -> void: drop_stack(uid, 1 << 20))
	hud.move_item.connect(_on_move_item)
	hud.treat_wound.connect(treat)
	hud.menu_action.connect(_on_menu)
	player = Player.new()
	player.level_up.connect(func(sk: String, lv: int) -> void: hud.notify("%s is now level %d." % [sk.capitalize(), lv], "xp"))
	if ResourceLoader.exists("res://scripts/zombies.gd"):
		var zs = load("res://scripts/zombies.gd")
		if zs != null and zs.can_instantiate():
			zombies = zs.new(); zombies.name = "Zombies"; add_child(zombies)
			_wire_zombies()
	for m in MODULES:
		var path := "res://scripts/%s.gd" % m
		if ResourceLoader.exists(path):
			var sc = load(path)
			if sc != null and sc.can_instantiate():
				var node = sc.new(); node.name = m.to_pascal_case()
				node.set("game", self)
				add_child(node); modules[m] = node
	shots_mode = OS.get_cmdline_user_args().has("--shots")
	_to_menu()
	if shots_mode:
		_debug_shots()
	if OS.get_cmdline_user_args().has("--selftest"):
		_selftest()

# ------------------------------------------------------------------ clock
func hours() -> float:
	return fmod(game_min / 60.0, 24.0)

func day() -> int:
	return int(game_min / 1440.0) + 1

func season() -> int:
	return int((day() - 1) / 7) % 4

func season_name() -> String:
	return SEASONS[season()]

func ambient_temp() -> float:
	return SEASON_TEMP[season()] + 5.0 * sin((hours() - 9.0) / 24.0 * TAU) + (modules.weather.temp_offset() if modules.has("weather") else 0.0)

func _rain_protect() -> float:
	var r := 0.0
	for st in player.worn.values():
		if st != null:
			var cl = Items.def(st.id).get("cloth")
			if cl != null: r = maxf(r, float(cl.get("rain", 0.0)))
	return r

func _near_fire() -> bool:
	return modules.has("sys_cook") and modules.sys_cook.has_method("is_near_fire") and modules.sys_cook.is_near_fire(player.pos)

func objective_text() -> String:
	if state != "play" or objective >= OBJECTIVES.size():
		return ""
	return "%s\n%s" % [OBJECTIVES[objective][0], OBJECTIVES[objective][1]]

# ------------------------------------------------------------------ menus and game flow
func has_save() -> bool:
	return FileAccess.file_exists(SAVE_PATH)

func _to_menu() -> void:
	state = "menu"
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	hud.set_playing(false)
	hud.show_menu("main")

func _on_menu(a: String) -> void:
	match a:
		"new": new_game()
		"continue": continue_game()
		"quit": get_tree().quit()
		"resume": _resume()
		"save":
			save_game(false); _resume()
		"menu":
			if state == "play": save_game(false)
			get_tree().reload_current_scene()

func _resume() -> void:
	hud.close_window()
	Input.mouse_mode = Input.MOUSE_MODE_CAPTURED

func _mods(method: String, args: Array = []) -> void:
	for k in modules:
		if modules[k].has_method(method):
			modules[k].callv(method, args)

func make_noise(pos: Vector3, radius: float) -> void:
	_noise(pos, radius)
	loud_t = maxf(loud_t, radius) if pos.distance_to(player.pos) < 2.0 else loud_t

func _start_play() -> void:
	state = "play"
	hud.close_window(); hud.set_playing(true)
	Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
	autosave_at = game_min + 180.0

func new_game(occupation := "unemployed") -> void:
	if has_save():
		DirAccess.remove_absolute(ProjectSettings.globalize_path(SAVE_PATH))
	player = Player.new()
	player.level_up.connect(func(sk: String, lv: int) -> void: hud.notify("%s is now level %d." % [sk.capitalize(), lv], "xp"))
	var p := player
	p.occupation = occupation
	var occs: Array = World.read_json("res://data/skills.json").occupations
	for o in occs:
		if String(o.id) == occupation:
			for k in o.skills: p.bonus[k] = int(o.skills[k])
			for it in o.items: p.inv.add(String(it[0]), int(it[1]))
	p.inv.add("water_bottle", 1); p.inv.add("chips", 1); p.inv.add("flashlight", 1); p.inv.add("battery", 2); p.inv.add("rag", 2)
	for id in ["jeans", "hoodie", "sneakers"]:
		if Items.exists(id) and Items.def(id).has("cloth"):
			p.worn[String(Items.def(id).cloth.slot)] = Inventory.make_stack(id)
	var hi := 0
	for s in p.inv.stacks:
		if hi < 8 and (Items.def(s.id).has("weapon") or s.id == "flashlight"):
			p.hotbar[hi] = int(s.uid); hi += 1
	# a few essentials in the cabin you wake up in
	var cabin: Dictionary = WorldData.info.cabin
	var cab_id := 0
	for id in world.buildings:
		var b: Dictionary = world.buildings[id].data
		if Vector2(float(b.x) - float(cabin.x), float(b.z) - float(cabin.z)).length() < 3.0:
			cab_id = id
	var cs: Array = []
	for c in world.containers:
		if int(c.building) == cab_id and cab_id != 0:
			cs.append(c)
	if not cs.is_empty():
		var r := RandomNumberGenerator.new(); r.seed = 5
		for give in [["bat", 1], ["canned_beans", 2], ["water_bottle", 1], ["saw", 1], ["hammer", 1], ["nails", 24], ["plank", 6], ["lighter", 1], ["apple", 2], ["bandage", 1], ["school_bag", 1]]:
			var inv := world.container_inv(cs[r.randi_range(0, cs.size() - 1)])
			var keep := inv.capacity; inv.capacity = INF
			inv.add(String(give[0]), int(give[1])); inv.capacity = keep
	var sp: Dictionary = WorldData.info.spawn
	p.pos = Vector3(sp.x, WorldData.ground_y(sp.x, sp.z) + 0.1, sp.z)
	p.yaw = PI * 0.75
	game_min = 8.0 * 60.0 + 30.0
	objective = 0
	if zombies != null and zombies.has_method("populate"):
		zombies.clear()
		zombies.populate(world.buildings)
	_start_play()
	_mods("mod_started", [true])
	hud.notify("Day 1. The outbreak was three weeks ago. The radio went silent yesterday.", "good")

func _die() -> void:
	state = "dead"
	Sfx.play("player_death")
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	var st := player.stats
	hud.show_menu("dead", "%s\nYou lasted %d days and %d hours, and put down %d of the dead." % [st.death_cause, int(st.hours_survived / 24.0), int(fmod(st.hours_survived, 24.0)), player.kills])
	if has_save():
		DirAccess.remove_absolute(ProjectSettings.globalize_path(SAVE_PATH))

# ------------------------------------------------------------------ input
func _unhandled_input(ev: InputEvent) -> void:
	if state != "play":
		return
	if ev is InputEventMouseMotion and Input.mouse_mode == Input.MOUSE_MODE_CAPTURED:
		var sens := 0.0022 * float(Settings.v("mouse_sens"))
		player.yaw -= ev.relative.x * sens
		player.pitch = clampf(player.pitch - ev.relative.y * sens * (-1.0 if Settings.v("invert_y") else 1.0), -1.5, 1.5)
		_mouse += ev.relative
	for mk in modules:
		if modules[mk].has_method("mod_input") and modules[mk].mod_input(ev):
			return
	if ev is InputEventKey and ev.pressed and not ev.echo:
		var k: int = ev.physical_keycode
		if hud.is_open():
			if k == KEY_ESCAPE or k == KEY_TAB or k == KEY_I or (k == KEY_H and hud.window_kind == "health") or (k == KEY_E and hud.window_kind == "inv"):
				_resume()
			elif k == KEY_R and hud.window_kind == "inv" and hud.container != null:
				_take_all()
			elif k >= KEY_1 and k <= KEY_8 and hud.window_kind == "inv" and not hud.hover_in_container:
				_assign_hotbar(hud.hover_uid, k - KEY_1)
			return
		match k:
			KEY_ESCAPE:
				Input.mouse_mode = Input.MOUSE_MODE_VISIBLE; hud.show_menu("pause")
			KEY_TAB, KEY_I: _open_inventory(null)
			KEY_H:
				Input.mouse_mode = Input.MOUSE_MODE_VISIBLE; hud.open_health()
			KEY_E: _interact()
			KEY_F: _toggle_flashlight()
			KEY_Q: quick_heal()
			KEY_R: _start_reload()
			KEY_C, KEY_CTRL: player.crouch = not player.crouch
			KEY_SPACE: player.jump()
			KEY_F5:
				save_game(false)
			_:
				if k >= KEY_1 and k <= KEY_8:
					select_hotbar(k - KEY_1)
	if ev is InputEventMouseButton and ev.pressed and Input.mouse_mode != Input.MOUSE_MODE_CAPTURED and not hud.is_open():
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED

func _open_inventory(cont) -> void:
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	hud.open_inventory(cont)

func _assign_hotbar(uid: int, slot: int) -> void:
	var s = player.inv.by_uid(uid)
	if s == null:
		return
	for i in 8:
		if player.hotbar[i] != null and int(player.hotbar[i]) == uid:
			player.hotbar[i] = null
	player.hotbar[slot] = uid
	hud.refresh()

# ------------------------------------------------------------------ frame
func _process(dt: float) -> void:
	dt = minf(dt, 0.1)
	time += dt
	if state == "menu":
		_menu_frame(dt)
		return
	if state == "dead":
		sky.set_time(hours())
		return
	var p := player
	var ui_open := hud.is_open() or Input.mouse_mode != Input.MOUSE_MODE_CAPTURED
	var blocked := false
	for mk in modules:
		if modules[mk].has_method("mod_blocks_control") and modules[mk].mod_blocks_control(): blocked = true
	day_len = float(Settings.v("day_len"))
	var dt_min := dt * 1440.0 / (day_len * 60.0)
	if p.sleeping:
		dt_min *= 40.0
	game_min += dt_min
	p.update(dt, not ui_open and not blocked, game_min)
	_mods("mod_process", [dt, dt_min, ui_open])
	if p.stepped:
		Sfx.play("footstep_wood" if p.inside else ("footstep_road" if _on_road(p.pos) else "footstep_grass"), {"volume": (0.35 if p.crouch else (1.0 if p.sprinting else 0.65)) * (1.2 if p.landed > 0.0 else 1.0)})
	var exertion := 1.0 if p.sprinting else (0.6 if swing_timer > 0.0 else (0.2 if p.moving else 0.0))
	var seen := 0
	if zombies != null:
		seen = int(zombies.get("seen_count")) if zombies.get("seen_count") != null else 0
	var ev: Array = p.stats.update(dt, dt_min / 60.0, {"moving": p.moving, "sprinting": p.sprinting, "exertion": exertion, "ambient_temp": ambient_temp(), "insulation": p.insulation(), "near_fire": _near_fire(), "indoors": p.inside, "raining": (modules.weather.rain_on_player() if modules.has("weather") else 0.0), "rain_protect": _rain_protect(), "sleeping": p.sleeping, "fitness": float(p.level("fitness")), "traits": p.traits, "weight_ratio": p.weight_ratio(), "zombies_seen": seen, "in_light": sky.daylight > 0.4, "bloody_hands": p.bloody > 0.5})
	for e in ev:
		if e == "dead":
			_die(); return
		elif e == "cured": hud.notify("The fever has broken. The serum worked.", "good")
		elif String(e).begins_with("healed:"): hud.notify("Your %s has healed." % String(e).substr(7).to_lower(), "good")
	_update_combat(dt, ui_open)
	_update_action(dt)
	_update_doors(dt)
	_age_food(dt_min)
	if not ui_open:
		target = _find_interact()
		hud.set_prompt(_prompt(target))
	else:
		hud.set_prompt("")
	if zombies != null:
		loud_t = maxf(0.0, loud_t - dt * 8.0)
		_groan_t -= dt
		if _groan_t <= 0.0:
			_groan_t = rng.randf_range(2.5, 7.0)
			var near: Array = []
			for z in zombies.list:
				if String(z.state) != "dead" and String(z.state) != "dormant" and z.pos.distance_squared_to(p.pos) < 1300.0: near.append(z)
			if not near.is_empty():
				var gz = near[rng.randi() % near.size()]
				Sfx.play("zombie_alert" if String(gz.state) in ["chase", "attack"] else "zombie_groan", {"pos": gz.pos + Vector3(0, 1.5, 0), "pitch": 0.7 if int(gz.type) in [2, 5] else 1.0})
		zombies.update(dt, dt_min, {"player_pos": p.pos, "player_eye": p.eye(), "player_noise": maxf(p.noise, loud_t), "player_visibility": p.visibility, "player_alive": true, "player_inside": p.inside, "player_bleeding": p.stats.bleeding() > 0.3, "base_center": null, "base_heat": 0.0, "daylight": sky.daylight, "hours": hours(), "day": day(), "luck": 0.0, "camera_pos": cam.global_position})
	# camera
	var shake := Vector3(rng.randf_range(-1, 1), rng.randf_range(-1, 1), 0.0) * p.cam_shake * 0.03
	cam.position = p.eye() + Basis(Vector3.UP, p.yaw) * p.head_bob + shake
	cam.rotation = Vector3(p.pitch, p.yaw, 0.0)
	cam.fov = lerpf(cam.fov, (float(Settings.v("fov")) + (6.0 if p.sprinting else 0.0)) * (1.0 - 0.22 * vm.ads), 1.0 - exp(-dt * 8.0))
	var es = p.equipped_stack()
	vm.set_item(String(es.id) if es != null else "")
	vm.want_ads = not ui_open and Input.is_mouse_button_pressed(MOUSE_BUTTON_RIGHT)
	vm.update(dt, _mouse, p.speed, p.on_ground)
	_mouse = Vector2.ZERO
	_update_flashlight(dt)
	Sfx.indoor = p.inside
	sky.indoor = move_toward(sky.indoor, 1.0 if p.inside else 0.0, dt * 1.5)
	_apply_view_settings()
	sky.set_time(hours(), [0.0, 1.0, 0.2, -0.3][season()])
	if world.grass_mat != null:
		world.grass_mat.set_shader_parameter("player_pos", p.pos)
		world.grass_mat.set_shader_parameter("flowers", [0.3, 0.0, 1.0, 0.75][season()])
	_objectives()
	hud.update_hud(dt)
	if game_min > autosave_at:
		autosave_at = game_min + 180.0
		save_game(true)

var _view_sig := ""
## graphics options that live on the viewport rather than in the environment
func _apply_view_settings() -> void:
	var sig := "%s|%s|%s|%s" % [Settings.v("quality"), Settings.v("render_scale"), Settings.v("grass"), Settings.v("vsync")]
	if sig == _view_sig:
		return
	_view_sig = sig
	var lvl := ["low", "medium", "high", "ultra"].find(String(Settings.v("quality")))
	if lvl < 0: lvl = 2
	var vp := get_viewport()
	vp.msaa_3d = [Viewport.MSAA_DISABLED, Viewport.MSAA_2X, Viewport.MSAA_2X, Viewport.MSAA_4X][lvl]
	vp.screen_space_aa = Viewport.SCREEN_SPACE_AA_FXAA if lvl == 0 else Viewport.SCREEN_SPACE_AA_DISABLED
	vp.scaling_3d_scale = clampf(float(Settings.v("render_scale")), 0.5, 1.0)
	if world.grass != null:
		var mm: MultiMesh = world.grass.multimesh
		mm.visible_instance_count = int(mm.instance_count * clampf([0.3, 0.6, 1.0, 1.0][lvl] * float(Settings.v("grass")), 0.0, 1.0))

func _menu_frame(_dt: float) -> void:
	_apply_view_settings()
	var a := time * 0.03
	var c := Vector3(0.0, 0.0, 0.0); c.y = WorldData.height_at(0.0, 0.0)
	var pos := c + Vector3(cos(a) * 150.0, 46.0, sin(a) * 150.0)
	cam.look_at_from_position(pos, c + Vector3(0, 6, 0), Vector3.UP)
	sky.set_time(17.6)

func _objectives() -> void:
	var p := player
	if objective == 0 and p.inv.count("bat") + p.inv.count("canned_beans") > 0: objective = 1
	elif objective == 1 and p.equipped_stack() != null and Items.def(p.equipped_stack().id).has("weapon"): objective = 2
	elif objective == 2 and p.pos.x > float(WorldData.info.town.x0) - 10.0: objective = 3

func _on_road(pos: Vector3) -> bool:
	for r in WorldData.info.roads:
		var a := Vector2(float(r.ax), float(r.az)); var b := Vector2(float(r.bx), float(r.bz)); var q := Vector2(pos.x, pos.z)
		var ab := b - a
		var t := clampf((q - a).dot(ab) / maxf(0.001, ab.length_squared()), 0.0, 1.0)
		if q.distance_to(a + ab * t) < float(r.w) * 0.5:
			return true
	return false

func _age_food(dt_min: float) -> void:
	_food_tick += dt_min
	if _food_tick < 30.0:
		return
	var days := _food_tick / 1440.0
	_food_tick = 0.0
	for s in player.inv.stacks:
		if s.has("age"):
			s.age = float(s.age) + days

# ------------------------------------------------------------------ timed actions
func start_action(label: String, dur: float, done: Callable, cancel_on_move := false) -> void:
	action = {"label": label, "dur": dur, "t": 0.0, "cancel_on_move": cancel_on_move, "done": done}

func cancel_action(why := "") -> void:
	if action != null:
		action = null; hud.set_action("", 0.0)
		if why != "": hud.notify(why, "bad")

func _update_action(dt: float) -> void:
	if action == null:
		return
	if action.cancel_on_move and player.moving and player.speed > 1.0:
		cancel_action(); return
	action.t = float(action.t) + dt
	hud.set_action(String(action.label), float(action.t) / float(action.dur))
	if float(action.t) >= float(action.dur):
		var done: Callable = action.done
		action = null; hud.set_action("", 0.0)
		done.call()
		hud.refresh()

# ------------------------------------------------------------------ interaction
func _box_ray(origin: Vector3, dir: Vector3, mn: Vector3, mx: Vector3, max_t: float) -> float:
	var tmin := 0.0; var tmax := max_t
	for a in 3:
		if absf(dir[a]) < 1e-9:
			if origin[a] < mn[a] or origin[a] > mx[a]: return INF
			continue
		var t1 := (mn[a] - origin[a]) / dir[a]; var t2 := (mx[a] - origin[a]) / dir[a]
		if t1 > t2:
			var tt := t1; t1 = t2; t2 = tt
		tmin = maxf(tmin, t1); tmax = minf(tmax, t2)
		if tmin > tmax: return INF
	return tmin

func _find_interact():
	var p := player
	var eye := p.eye(); var fwd := p.forward()
	const RANGE := 2.6
	var hit: Dictionary = WorldData.raycast(eye, fwd, RANGE + 0.5)
	var wall_t: float = hit.t
	var best = null
	var best_t := RANGE
	if hit.obj != null and hit.obj is WorldData.Box and hit.obj.ref is Dictionary and wall_t < RANGE:
		var ref: Dictionary = hit.obj.ref
		if ref.get("kind", "") in ["door", "window"]:
			best = ref; best_t = wall_t
	# open doors have no solid box: test their frame directly
	for id in world.doors:
		var d: Dictionary = world.doors[id]
		if not d.open or d.box == null: continue
		if absf(float(d.data.cx) - p.pos.x) > 4.0 or absf(float(d.data.cz) - p.pos.z) > 4.0: continue
		var t := _box_ray(eye, fwd, d.box.min - Vector3(0.25, 0, 0.25), d.box.max + Vector3(0.25, 0, 0.25), RANGE)
		if t < best_t:
			best = d; best_t = t
	for c in world.containers + world.drops:
		var cp: Vector3 = c.pos_v
		if absf(cp.x - p.pos.x) > 3.5 or absf(cp.z - p.pos.z) > 3.5 or absf(cp.y - eye.y) > 2.5: continue
		var h: Vector3 = c.half_v + Vector3(0.12, 0.12, 0.12)
		var t2 := _box_ray(eye, fwd, cp - h, cp + h, RANGE)
		if t2 < best_t and t2 <= wall_t + 0.6:
			best = c; best_t = t2
	for mk in modules:
		if modules[mk].has_method("mod_interact"):
			var cand: Dictionary = modules[mk].mod_interact(eye, fwd, RANGE, wall_t)
			if not cand.is_empty() and float(cand.get("t", INF)) < best_t:
				best = {"kind": "module", "prompt": String(cand.get("prompt", "")), "act": cand.get("act")}; best_t = float(cand.t)
	if best == null and p.pitch < -0.25 and WorldData.near_water(p.pos.x + fwd.x * 1.5, p.pos.z + fwd.z * 1.5):
		best = {"kind": "water"}
	return best

func _prompt(t) -> String:
	if t == null:
		return ""
	match String(t.get("kind", "")):
		"door":
			if t.locked and not t.open: return "[E]  Force the locked door"
			return "[E]  Close door" if t.open else "[E]  Open door"
		"window": return "" if t.broken else "Window"
		"container":
			return "[E]  Search %s" % String(t.label) if not t.get("searched", false) else "[E]  Open %s" % String(t.label)
		"drop": return "[E]  Pick through the %s" % String(t.label).to_lower()
		"water": return "[E]  Drink from the lake"
		"module": return String(t.prompt)
	return ""

func _interact() -> void:
	var t = target
	if t == null or action != null:
		return
	match String(t.get("kind", "")):
		"module":
			if t.act is Callable: t.act.call()
		"door":
			if t.locked and not t.open:
				var crowbar: bool = player.inv.has_tool("pry") or player.inv.count("crowbar") > 0
				start_action("Forcing the door…", 2.5 if crowbar else 6.0, func() -> void:
					t.locked = false; world.set_door(t, true); moving_doors.append(t); Sfx.play("wood_break", {"volume": 0.7})
					_noise(player.pos, 14.0)
					hud.notify("The lock gives way.", ""), true)
			else:
				world.set_door(t, not t.open)
				Sfx.play("door_open" if t.open else "door_close", {"pos": Vector3(float(t.data.cx), float(t.data.y) + 1.0, float(t.data.cz))})
				if not moving_doors.has(t): moving_doors.append(t)
				_noise(player.pos, 4.0)
		"container":
			var inv := world.container_inv(t)
			if not t.get("searched", false):
				var dur := 1.6 * (1.0 - player.level("scavenging") * 0.06)
				Sfx.play("search")
				start_action("Searching %s…" % String(t.label), dur, func() -> void:
					t["searched"] = true
					player.gain_xp("scavenging", 2.0, game_min)
					_open_inventory({"label": String(t.label), "inv": inv, "ref": t}), true)
			else:
				_open_inventory({"label": String(t.label), "inv": inv, "ref": t})
		"drop":
			_open_inventory({"label": String(t.label), "inv": t.inv, "ref": t})
		"water":
			Sfx.play("drink")
			start_action("Drinking…", 2.5, func() -> void:
				player.stats.thirst = clampf(player.stats.thirst + 35.0, 0.0, 100.0)
				if rng.randf() < 0.25:
					player.stats.food_poison = minf(1.0, player.stats.food_poison + 0.3); hud.notify("The water tastes of mud. Boil it next time.", "bad")
				var eb = player.inv.find("empty_bottle")
				if eb != null:
					player.inv.remove("empty_bottle", 1); player.inv.add("water_bottle", 1); hud.notify("Filled a bottle.", ""), true)

func _update_doors(dt: float) -> void:
	for i in range(moving_doors.size() - 1, -1, -1):
		var d: Dictionary = moving_doors[i]
		d.angle = lerpf(float(d.angle), float(d.target), 1.0 - exp(-dt * 12.0))
		world.apply_door(d)
		if absf(float(d.target) - float(d.angle)) < 0.01:
			moving_doors.remove_at(i)

func _noise(pos: Vector3, radius: float) -> void:
	if zombies != null and zombies.has_method("noise"):
		zombies.noise(pos, radius)

# ------------------------------------------------------------------ inventory actions
func _on_move_item(uid: int, to_container: bool) -> void:
	var cont = hud.container
	if cont == null:
		return
	if to_container: transfer(player.inv, cont.inv, uid, 1 << 20)
	else: transfer(cont.inv, player.inv, uid, 1 << 20)

func _take_all() -> void:
	var cont = hud.container
	if cont == null:
		return
	for s in cont.inv.stacks.duplicate():
		transfer(cont.inv, player.inv, int(s.uid), 1 << 20, true)
	hud.refresh()

func transfer(from: Inventory, to: Inventory, uid: int, n: int, quiet := false) -> bool:
	var s = from.by_uid(uid)
	if s == null:
		return false
	var d := Items.def(s.id)
	n = mini(n, int(s.n))
	var cap := to.capacity
	if to == player.inv:
		cap = player.capacity() * 1.8      # you may overload yourself, at a price
	if cap != INF:
		var free := cap - to.weight()
		n = mini(n, int(floor(free / maxf(0.001, float(d.weight)))))
		if n <= 0:
			if not quiet: hud.notify("Not enough room.", "bad")
			return false
	var part = from.remove_uid(uid, n)
	if part == null:
		return false
	var keep := to.capacity; to.capacity = INF
	to.add_stack(part); to.capacity = keep
	if not quiet: Sfx.play("pickup", {"volume": 0.4})
	if from == player.inv and player.equipped != null and int(player.equipped) == uid and from.by_uid(uid) == null:
		player.equipped = null
	if to == player.inv and (d.has("weapon") or String(s.id) == "flashlight"):
		var free_slot := -1
		for i in 8:
			if player.hotbar[i] == null or player.inv.by_uid(player.hotbar[i]) == null:
				free_slot = i; break
		if free_slot >= 0 and not player.hotbar.has(int(part.uid)):
			player.hotbar[free_slot] = int(part.uid)
	var cont = hud.container
	if cont != null and cont.get("ref") != null and String(cont.ref.get("kind", "")) == "drop" and cont.inv.stacks.is_empty():
		world.remove_drop(cont.ref)
		hud.close_window(); Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
		return true
	if not quiet: hud.refresh()
	return true

func drop_stack(uid: int, n: int) -> void:
	var p := player
	var s = p.inv.remove_uid(uid, n)
	if s == null:
		return
	if p.equipped != null and int(p.equipped) == uid and p.inv.by_uid(uid) == null:
		p.equipped = null
	var inv := Inventory.new(); inv.add_stack(s)
	var f := p.forward(); f.y = 0.0
	world.drop_items(p.pos + f.normalized() * 0.8, inv)
	Sfx.play("drop")
	hud.refresh()

func give_or_drop(id: String, n: int) -> void:
	player.inv.add(id, n)
	hud.notify("+%d %s" % [n, String(Items.def(id).name)], "")

func select_hotbar(i: int) -> void:
	var p := player
	var s = p.inv.by_uid(p.hotbar[i])
	if s == null:
		p.hotbar[i] = null
		return
	var d := Items.def(s.id)
	if d.has("weapon") or String(s.id) == "flashlight":
		p.equipped = null if (p.equipped != null and int(p.equipped) == int(s.uid)) else int(s.uid)
		Sfx.play("zipper", {"volume": 0.4})
		_cancel_reload()
	else:
		use_item(int(s.uid))

func use_item(uid: int) -> void:
	var p := player
	var s = p.inv.by_uid(uid)
	if s == null or action != null:
		return
	var d := Items.def(s.id)
	var id := String(s.id)
	if d.has("weapon") or id == "flashlight":
		p.equipped = null if (p.equipped != null and int(p.equipped) == uid) else uid
		hud.refresh(); return
	if d.has("cloth"):
		var slot := String(d.cloth.slot)
		var old = p.worn.get(slot)
		p.inv.remove_uid(uid, 1); p.worn[slot] = s
		if old != null: p.inv.add_stack(old)
		hud.refresh(); return
	var cat := String(d.cat)
	if cat == "food" or cat == "drink":
		var dur := 2.0 if cat == "drink" else 3.5
		vm.use(dur)
		Sfx.play("drink" if cat == "drink" else "eat")
		start_action("%s %s…" % ["Drinking" if cat == "drink" else "Eating", String(d.name)], dur, func() -> void:
			var st = p.inv.by_uid(uid)
			if st == null: return
			p.inv.remove_uid(uid, 1)
			var spoiled: bool = d.has("spoilDays") and float(st.get("age", 0.0)) > float(d.spoilDays)
			p.stats.hunger = clampf(p.stats.hunger + float(d.get("hunger", 0.0)) * (0.5 if spoiled else 1.0), 0.0, 100.0)
			p.stats.thirst = clampf(p.stats.thirst + float(d.get("thirst", 0.0)), 0.0, 100.0)
			p.stats.unhappy = clampf(p.stats.unhappy - float(d.get("mood", 0.0)) + (15.0 if spoiled else 0.0), 0.0, 100.0)
			var sick := float(d.get("sick", 0.0)) + (0.6 if spoiled else 0.0)
			if sick > 0.0 and rng.randf() < sick * (0.35 if p.traits.has("iron_gut") else 1.0):
				p.stats.food_poison = minf(1.0, p.stats.food_poison + 0.45); hud.notify("Your stomach turns…", "bad")
			if d.has("empties"): p.inv.add(String(d.empties)))
		return
	if d.has("med"):
		var med := String(d.med)
		if med == "painkiller":
			start_action("Taking painkillers…", 1.5, func() -> void: p.inv.remove_uid(uid, 1); p.stats.painkiller_hrs = 4.0); return
		if med == "antibiotic":
			start_action("Taking antibiotics…", 1.5, func() -> void: p.inv.remove_uid(uid, 1); p.stats.antibiotic_hrs = 12.0); return
		if med == "serum":
			start_action("Injecting serum…", 3.0, func() -> void:
				p.inv.remove_uid(uid, 1); p.stats.serum_hrs = 24.0
				if not p.stats.knox_infected: hud.notify("You are not infected. The serum is wasted on you.", "")); return
		var tgt := _auto_wound(med)
		if tgt.is_empty():
			hud.notify("No wound needs that right now. (H for the health panel)", ""); return
		treat(String(tgt[0]), int(tgt[1]), id); return
	if d.has("skillBook"):
		start_action("Reading %s…" % String(d.name), 8.0, func() -> void:
			p.book_until[String(d.skillBook)] = game_min + 1440.0
			p.stats.unhappy = maxf(0.0, p.stats.unhappy - 15.0)
			hud.notify("%s XP ×3 for the next day." % String(d.skillBook).capitalize(), "xp"); p.inv.remove_uid(uid, 1), true)
		return
	if id == "battery":
		var fl = p.inv.find("flashlight")
		if fl != null:
			fl.dur = 100.0; p.inv.remove_uid(uid, 1); hud.notify("Fresh batteries in the flashlight.", "good"); hud.refresh()
		return
	if id == "duct_tape":
		var es = p.equipped_stack()
		if es != null and Items.def(es.id).has("weapon") and es.has("dur"):
			var mx := float(Items.def(es.id).weapon.durability)
			es.dur = minf(mx, float(es.dur) + mx * 0.3); p.inv.remove_uid(uid, 1); hud.notify("Repaired your %s." % String(Items.def(es.id).name), "good"); hud.refresh()
		else:
			hud.notify("Equip a weapon to repair it with tape.", "")
		return
	hud.notify("You can't use %s directly." % String(d.name), "")

func _auto_wound(med: String) -> Array:
	var best: Array = []
	var score := 0.0
	for part in Stats.PARTS:
		var ws: Array = player.stats.parts[part[0]].wounds
		for i in ws.size():
			var w: Dictionary = ws[i]
			var s := 0.0
			if (med == "bandage" or med == "rag") and (not w.bandaged or w.dirty): s = float(w.bleed) + 0.1 + (0.5 if w.dirty else 0.0)
			if med == "disinfect" and float(w.infection) > 0.0: s = float(w.infection) + 0.2
			if med == "disinfect" and not w.bandaged and w.type != "fracture": s = maxf(s, 0.05)
			if med == "splint" and w.type == "fracture" and not w.splinted: s = 1.0
			if med == "suture" and w.type in ["laceration", "bite", "bullet"] and not w.stitched: s = 0.8
			if s > score:
				score = s; best = [part[0], i]
	return best

func treat(part: String, wi: int, item_id: String) -> void:
	var p := player
	var d := Items.def(item_id)
	var ws: Array = p.stats.parts[part].wounds
	if wi >= ws.size() or p.inv.count(item_id) <= 0 or action != null:
		return
	var w: Dictionary = ws[wi]
	var med := String(d.get("med", ""))
	var dur := (6.0 if med == "suture" else (5.0 if med == "splint" else 3.0)) * (1.0 - p.level("firstaid") * 0.06)
	vm.use(dur)
	Sfx.play("bandage")
	start_action("Applying %s…" % String(d.name), dur, func() -> void:
		if not p.inv.remove(item_id, 1): return
		if med == "bandage" or med == "rag":
			w.bandaged = true; w.dirty = med == "rag"; w.bandageAge = 0.0
			if med == "bandage": w.infection = maxf(0.0, float(w.infection) - 0.05)
		if med == "disinfect":
			w.infection = 0.0; w.dirty = false
		if med == "splint": w.splinted = true
		if med == "suture":
			w.stitched = true; w.bleed = float(w.bleed) * 0.2
		p.gain_xp("firstaid", 5.0, game_min), true)

func quick_heal() -> void:
	var p := player
	var id := "bandage" if p.inv.count("bandage") > 0 else ("rag" if p.inv.count("rag") > 0 else "")
	var t := _auto_wound("bandage")
	if id != "" and not t.is_empty() and float(p.stats.parts[t[0]].wounds[t[1]].bleed) > 0.01:
		treat(String(t[0]), int(t[1]), id)
	else:
		hud.notify("Nothing to bandage." if id != "" else "No bandages or rags.", "")

# ------------------------------------------------------------------ light
func _toggle_flashlight() -> void:
	var fl = player.inv.find("flashlight")
	if fl == null:
		hud.notify("You have no flashlight.", ""); return
	if float(fl.get("dur", 0.0)) <= 0.0:
		hud.notify("The flashlight is dead. Use a battery.", "bad"); return
	player.flashlight_on = not player.flashlight_on
	Sfx.play("ui_click")

func _update_flashlight(dt: float) -> void:
	var fl = player.inv.find("flashlight")
	if fl == null or float(fl.get("dur", 0.0)) <= 0.0:
		player.flashlight_on = false
	if player.flashlight_on and fl != null:
		fl.dur = maxf(0.0, float(fl.dur) - dt * 0.12)
	flashlight.light_energy = lerpf(flashlight.light_energy, 36.0 if player.flashlight_on else 0.0, 1.0 - exp(-dt * 20.0))
	flashlight.visible = flashlight.light_energy > 0.05

# ------------------------------------------------------------------ combat
func _weapon(st):
	if st == null:
		return null
	return Items.def(st.id).get("weapon")

func _update_combat(dt: float, ui_open: bool) -> void:
	var p := player
	fire_cooldown = maxf(0.0, fire_cooldown - dt); shove_cd = maxf(0.0, shove_cd - dt)
	if swing_timer > 0.0:
		swing_timer -= dt
		if not swing_hit_done and swing_timer <= swing_hit_at:
			swing_hit_done = true; _melee_hit()
	if reloading > 0.0:
		reloading -= dt
		if reloading <= 0.0: _finish_reload()
	if ui_open or action != null or p.sleeping:
		return
	var st = p.equipped_stack()
	var w = _weapon(st)
	var lmb := Input.is_mouse_button_pressed(MOUSE_BUTTON_LEFT)
	if lmb:
		if w != null and String(w.kind) == "firearm":
			if fire_cooldown <= 0.0 and reloading <= 0.0 and (bool(w.get("auto", false)) or not _lmb_was):
				_fire(st)
		elif swing_timer <= 0.0 and fire_cooldown <= 0.0:
			_start_swing(st)
	if Input.is_mouse_button_pressed(MOUSE_BUTTON_RIGHT) and not _rmb_was and (w == null or String(w.kind) != "firearm") and shove_cd <= 0.0:
		_shove()
	_lmb_was = lmb; _rmb_was = Input.is_mouse_button_pressed(MOUSE_BUTTON_RIGHT)
var _lmb_was := false
var _rmb_was := false

func _start_swing(st) -> void:
	var p := player
	var w = _weapon(st)
	var cost: float = float(w.stamina) if w != null else 2.0
	if p.stats.stamina < cost * 0.5:
		return
	var skill := p.level("blade" if (w != null and String(w.kind) == "blade") else "blunt") if w != null else 0
	var spd: float = (float(w.speed) if w != null else 2.2) * (1.0 + skill * 0.04) * (0.6 if p.stats.stamina < 10.0 else 1.0)
	var dur := 1.0 / spd
	swing_timer = dur; swing_hit_at = dur * 0.58; swing_hit_done = false; swing_stack = st
	p.stats.stamina -= cost
	vm.swing(dur)
	Sfx.play("swing_heavy" if (w != null and float(w.speed) < 0.9) else "swing", {"volume": 0.7})

func zombies_in_arc(origin: Vector3, dir: Vector3, reach: float, min_cos: float) -> Array:
	var out: Array = []
	if zombies == null:
		return out
	for z in zombies.list:
		if String(z.state) == "dead":
			continue
		var v := Vector3(z.pos.x - origin.x, 0.0, z.pos.z - origin.z)
		var dist := v.length()
		if dist > reach + 0.35 or absf(z.pos.y + 1.0 - origin.y) > 1.6:
			continue
		if dist > 0.6 and (v / dist).dot(dir) < min_cos:
			continue
		out.append([dist, z])
	out.sort_custom(func(a, b) -> bool: return a[0] < b[0])
	return out.map(func(e): return e[1])

func _melee_hit() -> void:
	var p := player
	var st = swing_stack
	var w = _weapon(st)
	var eye := p.eye(); var fwd := p.forward()
	var flat := Vector3(fwd.x, 0.0, fwd.z).normalized()
	var reach: float = float(w.range) if w != null else 1.4
	var skill_id := "blade" if (w != null and String(w.kind) == "blade") else "blunt"
	var skill := p.level(skill_id) if w != null else 0
	var targets := zombies_in_arc(eye, flat, reach, 0.55)
	if not targets.is_empty():
		var max_hits := 2 if (w != null and String(w.model) in ["machete", "sledgehammer", "bat", "bat_nails"]) else 1
		for i in mini(max_hits, targets.size()):
			var z = targets[i]
			var ray = zombies.raycast(eye, fwd, reach + 0.6)
			var head: bool = (ray != null and ray.get("zombie") == z and int(ray.get("part", 1)) == 0) or rng.randf() < 0.08 + skill * 0.015
			var dmg: float = (float(w.damage) if w != null else 9.0) * (1.0 + skill * 0.07) * (1.0 + p.level("strength") * 0.04) * p.stats.melee_mult() * rng.randf_range(0.85, 1.15) * (0.5 if i > 0 else 1.0)
			if w != null and String(w.kind) == "blade" and head: dmg *= 1.3
			var dir := Vector3(z.pos.x - p.pos.x, 0.0, z.pos.z - p.pos.z).normalized()
			var killed: bool = zombies.damage(z, dmg, dir, {"head": head, "knock": (float(w.knock) if w != null else 0.5) * 1.5, "by_player": true})
			hud.hitmark(killed)
			Sfx.play("headshot" if head else "hit_flesh", {"pos": z.pos + Vector3(0, 1.3, 0)})
			fx.blood(z.pos + Vector3(0, 1.6 if head else 1.2, 0), dir)
			p.gain_xp(skill_id, 8.0 if killed else 3.0, game_min); p.gain_xp("strength", 1.0, game_min)
			p.bloody = minf(1.0, p.bloody + 0.1)
		_noise(p.pos, float(w.noise) if w != null else 4.0)
		if st != null and w != null: _wear(st, 1.0)
		p.cam_shake = maxf(p.cam_shake, 0.15)
		return
	var hit: Dictionary = WorldData.raycast(eye, fwd, reach + 0.3)
	if hit.obj != null:
		if hit.obj is WorldData.Circle:
			var c: WorldData.Circle = hit.obj
			if c.kind == "ore": _hit_ore(int(c.ref), w)
			elif c.kind != "": _hit_veg(int(c.ref), w)
			if st != null and w != null: _wear(st, 1.0)
			return
		p.cam_shake = maxf(p.cam_shake, 0.08)
		Sfx.play("hit_wood", {"volume": 0.7}); fx.dust(eye + fwd * float(hit.t))
		_noise(p.pos, 5.0)
		var ref = hit.obj.ref
		if ref is Dictionary and String(ref.get("kind", "")) == "window" and not ref.broken:
			_break_window(ref)

func _break_window(w: Dictionary) -> void:
	w.broken = true
	if w.node != null: w.node.visible = false
	if w.box != null: WorldData.set_solid(w.box, false)
	_noise(player.pos, 22.0)
	Sfx.play("glass_break")
	hud.notify("Glass shatters.", "")

func _hit_veg(i: int, w) -> void:
	var p := player
	var kind := world.veg_kind(i)
	var hp: float = world.veg_hp.get(i, {"tree": 100.0, "bush": 20.0, "rock": 200.0, "log": 60.0}[kind])
	var s := world.veg_inst[i * 6 + 3]
	if kind == "tree":
		if w == null or not w.has("tree"):
			hud.notify("You need an axe to fell trees.", ""); return
		hp -= 20.0 * (1.0 + p.level("strength") * 0.05)
		Sfx.play("chop")
		_noise(p.pos, 14.0)
		if hp <= 0.0:
			var pos := world.veg_pos(i)
			var dir := Vector3(pos.x - p.pos.x, 0.0, pos.z - p.pos.z).normalized()
			world.kill_veg(i); Sfx.play("wood_break")
			var logs := 2 + int(floor(s * 2.0))
			var inv := Inventory.new(); inv.add("log", logs); inv.add("leaves", 6 + rng.randi_range(0, 6))
			world.drop_items(pos + dir * 2.0, inv, "Felled timber")
			p.gain_xp("strength", 4.0, game_min)
			hud.notify("Timber! %d logs where it fell." % logs, "")
	elif kind == "bush":
		hp -= 10.0
		if hp <= 0.0:
			world.kill_veg(i); give_or_drop("leaves", 4 + rng.randi_range(0, 4))
			if rng.randf() < 0.25: give_or_drop("apple", 1)
	elif kind == "rock":
		if w == null or not w.has("mine"):
			return
		var mul := int(w.mine)
		Sfx.play("mine_hit")
		var ore := world.veg_ore(i)
		if ore != "":
			give_or_drop(ore, rng.randi_range(1, 2) * mul)
			hp -= 20.0
			if hp <= 0.0:
				world.kill_veg(i); hud.notify("The vein is exhausted.", "")
		else:
			give_or_drop("stone", rng.randi_range(1, 2) * mul)
		p.gain_xp("strength", 1.0, game_min)
	elif kind == "log":
		if w == null or not w.has("tree"): return
		hp -= 25.0
		if hp <= 0.0:
			world.kill_veg(i); give_or_drop("log", 2)
	world.veg_hp[i] = hp

func _hit_ore(id: int, w) -> void:
	var o = null
	for e in WorldData.info.ores:
		pass
	var data = World.read_json("res://assets/world/ores.json") if _ores.is_empty() else null
	if data != null:
		for e in data.ores: _ores[int(e.id)] = e
	o = _ores.get(id)
	if o == null: return
	if String(o.type) == "f_crude":
		hud.notify("Oil seeps from the ground here.", ""); return
	if w == null or not w.has("mine"):
		hud.notify("You need a pickaxe to mine ore.", ""); return
	_noise(player.pos, 12.0)
	Sfx.play("mine_hit")
	var n := maxi(1, int(round((1.0 + (1.0 if rng.randf() < 0.3 + player.level("strength") * 0.03 else 0.0)) * float(w.mine) * maxf(0.5, float(o.purity)))))
	give_or_drop(String(o.type), n)
	player.gain_xp("strength", 1.5, game_min)
var _ores: Dictionary = {}

func _fire(st) -> void:
	var p := player
	var w: Dictionary = Items.def(st.id).weapon
	if int(st.get("ammo", 0)) <= 0:
		fire_cooldown = 0.3
		Sfx.play("empty_click")
		if p.inv.count(String(w.ammo)) > 0: _start_reload()
		else: hud.notify("Out of ammunition.", "bad")
		return
	st.ammo = int(st.ammo) - 1
	fire_cooldown = 1.0 / float(w.speed)
	var eye := p.eye(); var fwd := p.forward()
	var skill := p.level("firearms")
	var ads := 0.35 if vm.ads > 0.5 else 1.0
	var spread: float = float(w.get("spread", 0.02)) * (1.0 - skill * 0.06) * (1.8 if p.speed > 1.0 else 1.0) * ads + p.stats.aim_sway() * 0.6 * ads
	var hit_any := false; var killed_any := false
	for i in int(w.get("pellets", 1)):
		var d := (fwd + Vector3(rng.randf_range(-1, 1), rng.randf_range(-1, 1), rng.randf_range(-1, 1)) * spread).normalized()
		var wh: Dictionary = WorldData.raycast(eye, d, float(w.range))
		var gt := WorldData.ray_ground(eye, d, minf(float(w.range), 120.0))
		var lim := minf(minf(float(w.range), float(wh.t)), gt)
		var zh = zombies.raycast(eye, d, lim) if zombies != null else null
		if zh != null and zh.get("zombie") != null:
			var falloff := clampf(1.2 - float(zh.t) / float(w.range), 0.3, 1.0) if w.has("pellets") else 1.0
			var head := int(zh.get("part", 1)) == 0
			var killed: bool = zombies.damage(zh.zombie, float(w.damage) * falloff * (1.0 + skill * 0.05) * rng.randf_range(0.9, 1.1), d, {"head": head, "knock": float(w.knock), "by_player": true})
			hit_any = true; killed_any = killed_any or killed
			fx.blood(eye + d * float(zh.t), d)
			if head and killed: Sfx.play("headshot", {"pos": eye + d * float(zh.t), "volume": 0.8})
			if killed: p.gain_xp("firearms", 6.0, game_min)
		elif wh.obj != null and wh.obj is WorldData.Box and wh.obj.ref is Dictionary and String(wh.obj.ref.get("kind", "")) == "window" and float(wh.t) < lim + 0.01 and not wh.obj.ref.broken:
			_break_window(wh.obj.ref)
	if hit_any: hud.hitmark(killed_any)
	p.gain_xp("firearms", 1.0, game_min)
	var rc := float(w.get("recoil", 0.02))
	vm.recoil(rc * 8.0)
	p.recoil_pitch += rc * (1.0 - skill * 0.05) * (0.6 if vm.ads > 0.5 else 1.0)
	p.yaw += rng.randf_range(-0.5, 0.5) * rc * 0.5
	p.cam_shake = maxf(p.cam_shake, rc * 3.0)
	Sfx.play("gunshot_shotgun" if String(w.ammo) == "ammo_shells" else ("gunshot_rifle" if String(w.ammo) == "ammo_rifle" else "gunshot_pistol"))
	_noise(p.pos, float(w.noise)); loud_t = maxf(loud_t, float(w.noise))
	_wear(st, 1.0)

func _start_reload() -> void:
	var st = player.equipped_stack()
	var w = _weapon(st)
	if w == null or String(w.kind) != "firearm" or reloading > 0.0:
		return
	if int(st.get("ammo", 0)) >= int(w.mag): return
	if player.inv.count(String(w.ammo)) <= 0:
		hud.notify("No %s." % String(Items.def(String(w.ammo)).name), "bad"); return
	var t := float(w.reload) * (1.0 - player.level("firearms") * 0.05)
	reloading = t; reload_stack = st; vm.reload(t)
	Sfx.play("reload")

func _finish_reload() -> void:
	var st = reload_stack
	reload_stack = null
	if st == null or player.inv.by_uid(st.uid) == null: return
	var w: Dictionary = Items.def(st.id).weapon
	var k := mini(int(w.mag) - int(st.get("ammo", 0)), player.inv.count(String(w.ammo)))
	player.inv.remove(String(w.ammo), k); st.ammo = int(st.get("ammo", 0)) + k

func _cancel_reload() -> void:
	reloading = 0.0; reload_stack = null

func _shove() -> void:
	var p := player
	if p.stats.stamina < 4.0: return
	shove_cd = 0.6; p.stats.stamina -= 4.0; vm.swing(0.35)
	Sfx.play("swing", {"volume": 0.4})
	var eye := p.eye(); var fwd := p.forward(); var flat := Vector3(fwd.x, 0.0, fwd.z).normalized()
	var ts := zombies_in_arc(eye, flat, 1.7, 0.4)
	for i in mini(3, ts.size()):
		var z = ts[i]
		zombies.damage(z, 2.0, Vector3(z.pos.x - p.pos.x, 0.0, z.pos.z - p.pos.z).normalized(), {"head": false, "knock": 0.8 if int(z.type) == 2 else 3.2, "by_player": true})

func _wear(st, amt: float) -> void:
	if not st.has("dur"): return
	if rng.randf() < 0.5: st.dur = float(st.dur) - amt
	if float(st.dur) <= 0.0:
		player.inv.remove_uid(int(st.uid), 1)
		if player.equipped != null and int(player.equipped) == int(st.uid): player.equipped = null
		hud.notify("Your %s broke!" % String(Items.def(st.id).name), "bad"); Sfx.play("wood_break", {"volume": 0.6})

# ------------------------------------------------------------------ the dead
func _wire_zombies() -> void:
	if zombies.has_signal("player_attacked"): zombies.player_attacked.connect(_player_attacked)
	if zombies.has_signal("killed"): zombies.killed.connect(_on_kill)
	if zombies.has_signal("structure_hit"): zombies.structure_hit.connect(_structure_hit)
	if zombies.has_signal("burst"): zombies.burst.connect(_on_burst)
	if zombies.has_signal("howl"): zombies.howl.connect(func(pos: Vector3, _r: float, _z) -> void:
		if state == "play" and pos.distance_to(player.pos) < 90.0: hud.notify("A howl carries across the valley. They are coming.", "bad"))

func _player_attacked(z, _dmg := 0.0) -> void:
	if state != "play": return
	Sfx.play("zombie_attack", {"pos": z.pos + Vector3(0, 1.4, 0)})
	var p := player
	var to_z := atan2(z.pos.x - p.pos.x, z.pos.z - p.pos.z)
	var facing := absf(angle_difference(p.yaw + PI, to_z)) < 0.8
	var brute := int(z.type) == 2
	var hit_chance := 0.72 + (0.1 if brute else 0.0) - (0.15 if facing else 0.0) + (-0.2 if p.sprinting else 0.0)
	if swing_timer > 0.0 and facing: hit_chance -= 0.15
	if rng.randf() > hit_chance: return
	cancel_action("You were attacked!")
	p.sleeping = false
	var parts := [["armL", 22], ["armR", 22], ["torso", 26], ["head", 10], ["legL", 10], ["legR", 10]]
	var part := String(_weighted(parts))
	var slot := "head" if part == "head" else ("torso" if part == "torso" or part.begins_with("arm") else "legs")
	var prot := p.protection(slot)
	var type := String(_weighted([["scratch", 55], ["laceration", 30], ["bite", 25 if brute else 15]]))
	var block: float = prot.bite if type == "bite" else prot.scratch
	hud.damage_flash(0.6); p.cam_shake = 0.4
	Sfx.play("player_hurt")
	var pname := ""
	for q in Stats.PARTS:
		if q[0] == part: pname = String(q[1]).to_lower()
	if rng.randf() < block + (0.3 if p.traits.has("thick_skin") else 0.0) * (1.0 - block):
		p.stats.parts[part].hp = maxf(0.0, float(p.stats.parts[part].hp) - 3.0)
		hud.notify("Your clothing stopped a %s to the %s." % [type, pname], ""); return
	var dmg := rng.randf_range(16, 26) if type == "bite" else (rng.randf_range(10, 18) if type == "laceration" else rng.randf_range(5, 9))
	p.stats.add_wound(part, type, dmg * (1.6 if brute else 1.0))
	var infect := 1.0 if type == "bite" else (0.25 if type == "laceration" else 0.07)
	if rng.randf() < infect: p.stats.knox_infected = true
	hud.notify("%s on the %s!%s" % ["BITTEN" if type == "bite" else ("Lacerated" if type == "laceration" else "Scratched"), pname, " That is not good." if type == "bite" else ""], "bad")
	var push := Vector3(p.pos.x - z.pos.x, 0.0, p.pos.z - z.pos.z).normalized() * 2.0
	p.vel += push

func _weighted(list: Array):
	var total := 0.0
	for e in list: total += float(e[1])
	var r := rng.randf() * total
	for e in list:
		r -= float(e[1])
		if r <= 0.0: return e[0]
	return list[0][0]

func _on_kill(z, _headshot := false) -> void:
	Sfx.play("zombie_death", {"pos": z.pos + Vector3(0, 1.2, 0), "pitch": 0.7 if int(z.type) in [2, 5] else (1.4 if int(z.type) == 3 else 1.0)})
	var by_player: bool = z.get("by_player") == true
	if by_player: player.kills += 1
	var nm = z.get("name")
	if z.get("turned") and nm != null and String(nm) != "":
		hud.notify(("You put down what was left of %s." if by_player else "What was left of %s has been put down.") % String(nm), "")

func _on_burst(pos: Vector3, radius: float, _z = null) -> void:
	if state != "play": return
	var d := pos.distance_to(player.pos)
	if d < radius:
		player.stats.add_wound("torso" if rng.randf() < 0.5 else "armL", "burn", 8.0 * (1.0 - d / radius) + 4.0)
		player.stats.food_poison = minf(1.0, player.stats.food_poison + 0.25)
		hud.damage_flash(0.5); hud.notify("A bloat ruptures over you.", "bad")

func _structure_hit(ref, dmg: float) -> void:
	if not (ref is Dictionary): return
	if String(ref.get("kind", "")) == "door" and not ref.open:
		ref.hp = float(ref.hp) - dmg
		if float(ref.hp) <= 0.0:
			ref.locked = false; world.set_door(ref, true); ref.angle = PI / 2.0 * 0.95; world.apply_door(ref)
			if state == "play" and Vector2(float(ref.data.cx) - player.pos.x, float(ref.data.cz) - player.pos.z).length() < 30.0:
				hud.notify("A door gives way nearby.", "bad")
	elif String(ref.get("kind", "")) == "window" and not ref.broken:
		ref.broken = true
		if ref.node != null: ref.node.visible = false
		if ref.box != null: WorldData.set_solid(ref.box, false)

# ------------------------------------------------------------------ saving
func save_game(auto: bool) -> void:
	if state != "play": return
	var door_rows: Array = []
	for id in world.doors:
		var d: Dictionary = world.doors[id]
		door_rows.append([id, d.open, d.locked, d.hp])
	var cont_rows: Array = []
	for c in world.containers:
		if c.has("inv"):
			cont_rows.append([int(c.id), c.get("searched", false), c.inv.to_rows()])
	var drop_rows: Array = []
	for d in world.drops:
		drop_rows.append([[d.pos.x, d.pos.y, d.pos.z], String(d.label), d.inv.to_rows()])
	var broken: Array = []
	for id in world.windows:
		if world.windows[id].broken: broken.append(id)
	var o := {"v": 1, "game_min": game_min, "objective": objective, "player": player.to_dict(), "doors": door_rows, "containers": cont_rows, "drops": drop_rows, "windows": broken, "veg_dead": world.veg_dead.keys(), "veg_hp": world.veg_hp}
	if zombies != null and zombies.has_method("to_rows"): o["zombies"] = zombies.to_rows()
	var md := {}
	for k in modules:
		if modules[k].has_method("mod_save"): md[k] = modules[k].mod_save()
	o["modules"] = md
	var f := FileAccess.open(SAVE_PATH, FileAccess.WRITE)
	if f != null:
		f.store_string(JSON.stringify(o)); f.close()
		hud.notify("Autosaved." if auto else "Game saved.", "")

func continue_game() -> void:
	var f := FileAccess.open(SAVE_PATH, FileAccess.READ)
	if f == null:
		new_game(); return
	var o = JSON.parse_string(f.get_as_text())
	if not (o is Dictionary):
		new_game(); return
	player = Player.new()
	player.level_up.connect(func(sk: String, lv: int) -> void: hud.notify("%s is now level %d." % [sk.capitalize(), lv], "xp"))
	player.from_dict(o.player)
	game_min = float(o.game_min); objective = int(o.get("objective", 0))
	for r in o.get("doors", []):
		var d = world.doors.get(int(r[0]))
		if d == null: continue
		d.locked = bool(r[2]); d.hp = float(r[3])
		if bool(d.open) != bool(r[1]): world.set_door(d, bool(r[1]))
		d.angle = d.target; world.apply_door(d)
	var by_id := {}
	for c in world.containers: by_id[int(c.id)] = c
	for r in o.get("containers", []):
		var c = by_id.get(int(r[0]))
		if c == null: continue
		c["searched"] = bool(r[1])
		world.container_inv(c).load_rows(r[2])
	for r in o.get("drops", []):
		var inv := Inventory.new(); inv.load_rows(r[2])
		world.drop_items(Vector3(r[0][0], r[0][1], r[0][2]), inv, String(r[1]))
	for id in o.get("windows", []):
		var w = world.windows.get(int(id))
		if w != null: _structure_hit(w, 1.0)
	for i in o.get("veg_dead", []): world.kill_veg(int(i))
	var vh: Dictionary = o.get("veg_hp", {})
	for k in vh: world.veg_hp[int(k)] = float(vh[k])
	if zombies != null and zombies.has_method("load_rows") and o.has("zombies"): zombies.load_rows(o.zombies)
	var md: Dictionary = o.get("modules", {})
	for k in modules:
		if md.has(k) and modules[k].has_method("mod_load"): modules[k].mod_load(md[k])
	_start_play()
	_mods("mod_started", [false])

# ------------------------------------------------------------------ development: screenshots
func _debug_shots() -> void:
	var args := OS.get_cmdline_user_args()
	var only := ""
	for a in args:
		if a.begins_with("--only="): only = a.substr(7)
	await get_tree().process_frame
	new_game("police")
	var sp: Dictionary = WorldData.info.spawn
	var views := [
		["spawn", Vector3(sp.x, 0, sp.z), PI * 0.75, -0.05, 8.5, "clear"],
		["town_street", Vector3(-6, 0, -70), PI, -0.03, 11.5, "clear"],
		["town_main", Vector3(-100, 0, 3), -PI / 2.0, 0.0, 16.5, "cloudy"],
		["lake", Vector3(-178, 0, 162), 2.21, -0.08, 18.6, "clear"],
		["rain", Vector3(-30, 0, 4), -PI / 2.0, 0.0, 14.0, "rain"],
		["dusk", Vector3(-62, 0, -40), 1.9, 0.04, 19.4, "cloudy"],
		["night", Vector3(-6, 0, -40), PI, 0.0, 23.0, "clear"],
	]
	for v in views:
		if only != "" and only != String(v[0]): continue
		var pp: Vector3 = v[1]; pp.y = WorldData.ground_y(pp.x, pp.z)
		player.pos = pp; player.yaw = float(v[2]); player.pitch = float(v[3]); player.vel = Vector3.ZERO
		game_min = float(v[4]) * 60.0
		if modules.has("weather"): modules.weather.set_kind(String(v[5]), true)
		player.flashlight_on = String(v[0]) == "night"
		if String(v[0]) == "town_street":
			player.equipped = player.hotbar[0]
		for i in 12:
			await get_tree().process_frame
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png("user://shot_%s.png" % v[0])
		print("shot ", v[0])
	if only == "" or only == "inv":
		_open_inventory(null)
		for i in 4: await get_tree().process_frame
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png("user://shot_inv.png")
		print("shot inv")
	get_tree().quit()

# ------------------------------------------------------------------ development: scripted play-through
func _face(tx: float, ty: float, tz: float) -> void:
	var e := player.eye()
	player.yaw = atan2(-(tx - e.x), -(tz - e.z))
	player.pitch = atan2(ty - e.y, Vector2(tx - e.x, tz - e.z).length())

func _frames(n: int) -> void:
	for i in n:
		await get_tree().process_frame

func _selftest() -> void:
	var fails := [0]
	var check := func(name: String, cond: bool, extra := "") -> void:
		print(("PASS " if cond else "FAIL ") + name + ("  " + extra if extra != "" else ""))
		if not cond: fails[0] += 1
	await get_tree().process_frame
	new_game("police")
	Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
	await _frames(5)
	var p := player
	check.call("new game", state == "play" and p.inv.count("pistol") == 1 and p.pos.distance_to(Vector3(WorldData.info.spawn.x, p.pos.y, WorldData.info.spawn.z)) < 2.0, "pos %s" % p.pos)
	check.call("world loaded", world.buildings.size() > 40 and world.doors.size() > 100 and world.containers.size() > 400 and world.veg_map.size() > 3000, "%d buildings %d doors %d containers %d plants" % [world.buildings.size(), world.doors.size(), world.containers.size(), world.veg_map.size()])
	# walk: hold W by nudging velocity through the real update
	var start := p.pos
	for i in 60:
		p.vel = Basis(Vector3.UP, p.yaw) * Vector3(0, 0, -3.3)
		await get_tree().process_frame
	check.call("moves over terrain", p.pos.distance_to(start) > 0.5 and absf(p.pos.y - WorldData.ground_y(p.pos.x, p.pos.z, p.pos.y)) < 0.3, "moved %.1f m" % p.pos.distance_to(start))
	# a cabin container
	var cab = null
	for c in world.containers:
		if world.container_inv(c).count("bat") > 0: cab = c
	check.call("cabin kit placed", cab != null)
	if cab != null:
		var cp: Vector3 = cab.pos_v
		var found := false
		for a in 16:
			var ang := a * TAU / 16.0
			var pos := Vector3(cp.x + cos(ang) * 1.3, 0, cp.z + sin(ang) * 1.3)
			pos.y = WorldData.ground_y(pos.x, pos.z, cp.y + 1.0)
			p.pos = pos; p.vel = Vector3.ZERO; _face(cp.x, cp.y, cp.z)
			var t = _find_interact()
			if t != null and t == cab:
				found = true; break
		check.call("container can be targeted", found)
		target = cab
		_interact()
		check.call("search starts", action != null)
		cancel_action()
		cab["searched"] = true
		target = cab
		_interact()
		check.call("container opens", hud.window_kind == "inv" and hud.container != null)
		_take_all()
		check.call("loot taken", p.inv.count("bat") == 1, "carrying %.1f kg" % p.weight())
		_resume()
	# a door
	var door = null
	for id in world.doors:
		var d: Dictionary = world.doors[id]
		if not d.open and not d.locked and d.box != null and d.node != null: door = d; break
	check.call("door found", door != null)
	if door != null:
		var solid_before: bool = door.box.solid
		target = door; _interact()
		await _frames(30)
		check.call("door opens", door.open and not door.box.solid and solid_before and absf(float(door.angle) - PI / 2.0) < 0.2)
	# eating
	var h0 := p.stats.hunger
	var chips0 := p.inv.count("chips")
	use_item(int(p.inv.find("chips").uid))
	for i in 600:
		if action == null: break
		await get_tree().process_frame
	check.call("eating feeds", p.stats.hunger > h0 and p.inv.count("chips") == chips0 - 1, "%.1f -> %.1f" % [h0, p.stats.hunger])
	# wounds and bandage
	p.stats.add_wound("armL", "laceration", 12.0)
	check.call("wound bleeds", p.stats.bleeding() > 0.5)
	quick_heal()
	for i in 600:
		if action == null: break
		await get_tree().process_frame
	check.call("bandage stops bleeding", p.stats.bleeding() < 0.01)
	# the dead
	if zombies != null:
		var n0: int = zombies.list.size()
		check.call("zombies populated", n0 > 20, "%d" % n0)
		var f := p.forward(); f.y = 0.0; f = f.normalized()
		var open_pos := Vector3(-6, 0, -70); open_pos.y = WorldData.ground_y(open_pos.x, open_pos.z)
		p.pos = open_pos; p.yaw = 0.0; p.pitch = 0.0; p.vel = Vector3.ZERO
		var z = zombies.spawn(open_pos.x, open_pos.z - 1.3, 0)
		check.call("zombie spawns", z != null)
		if z != null:
			var hp0: float = z.hp
			for i in 8: p.hotbar[i] = null
			p.equipped = int(p.inv.find("bat").uid)
			await _frames(3)
			_start_swing(p.equipped_stack())
			for i in 120:
				if swing_timer <= 0.0: break
				await get_tree().process_frame
			check.call("melee hurts a zombie", z.hp < hp0 or String(z.state) == "dead", "%.0f -> %.0f" % [hp0, z.hp])
			z = zombies.spawn(open_pos.x, open_pos.z - 6.0, 0)
			p.equipped = int(p.inv.find("pistol").uid)
			p.equipped_stack().ammo = 12
			var kills0 := p.kills
			for shot in 40:
				if String(z.state) == "dead": break
				p.equipped_stack().ammo = 12
				_face(z.pos.x, z.pos.y + (1.2 if shot % 2 == 0 else 0.25), z.pos.z)
				fire_cooldown = 0.0
				_fire(p.equipped_stack())
				await _frames(2)
			check.call("pistol kills a zombie", String(z.state) == "dead" and p.kills > kills0, "state %s kills %d" % [z.state, p.kills])
			var hits := [0]
			var cb := func(_z, _d = 0.0) -> void: hits[0] += 1
			zombies.player_attacked.connect(cb)
			var z2 = zombies.spawn(open_pos.x + 3.0, open_pos.z, 0)
			p.equipped = null
			for i in 900:
				if hits[0] > 0: break
				await get_tree().process_frame
			check.call("a zombie reaches and attacks you", hits[0] > 0, "after spawn 3 m away, state %s" % (z2.state if z2 != null else "?"))
	# save / load
	var carried := p.inv.to_rows().size()
	var gm := game_min
	save_game(false)
	check.call("save written", has_save())
	continue_game()
	await _frames(3)
	check.call("save restores", player.inv.to_rows().size() == carried and absf(game_min - gm) < 2.0 and (door == null or door.open), "%d stacks" % player.inv.to_rows().size())
	var ok: bool = fails[0] == 0
	print("SELFTEST ", "OK" if ok else "FAILED (%d)" % fails[0])
	DirAccess.remove_absolute(ProjectSettings.globalize_path(SAVE_PATH))
	get_tree().quit(0 if ok else 1)
