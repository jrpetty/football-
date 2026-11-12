extends SceneTree
## Interface capture: loads the game, walks it through the states a player sees outside the 3D world and saves
## a picture of each.
##   godot --path . -s res://scripts/test_ui.gd -- --out=/abs/dir [--only=menu,inventory,…] [--3d] [--plates]
## Without --3d the world is not rendered (fast); a previously captured frame ("plates", made with --plates)
## is shown behind the interface instead so legibility over the world can still be judged.

var game
var out_dir := "user://ui_shots"
var only: Array = []
var use_3d := false
var plates := false
var plate_rect: TextureRect = null
var taken: Array = []
var map_lib
var items_lib

func _initialize() -> void:
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--out="): out_dir = a.substr(6)
		elif a.begins_with("--only="): only = Array(a.substr(7).split(","))
		elif a == "--3d": use_3d = true
		elif a == "--plates":
			plates = true; use_3d = true
	DirAccess.make_dir_recursive_absolute(ProjectSettings.globalize_path(out_dir))
	if not use_3d:
		root.disable_3d = true
		var layer := CanvasLayer.new(); layer.layer = -1
		plate_rect = TextureRect.new(); plate_rect.set_anchors_preset(Control.PRESET_FULL_RECT)
		plate_rect.expand_mode = TextureRect.EXPAND_IGNORE_SIZE; plate_rect.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_COVERED
		layer.add_child(plate_rect); root.add_child(layer)
	game = load("res://main.tscn").instantiate()
	root.add_child(game)
	_run.call_deferred()

func want(name: String) -> bool:
	return only.is_empty() or only.has(name)

func plate(name: String) -> void:
	if plate_rect == null:
		return
	var path := ProjectSettings.globalize_path(out_dir.path_join("plate_%s.png" % name))
	if FileAccess.file_exists(path):
		plate_rect.texture = ImageTexture.create_from_image(Image.load_from_file(path))
	else:
		plate_rect.texture = null

func wait(sec: float) -> void:
	await create_timer(sec).timeout
	await process_frame

func shot(name: String, sec := 0.4) -> void:
	await wait(sec)
	await RenderingServer.frame_post_draw
	var img := root.get_texture().get_image()
	img.save_png(out_dir.path_join(name + ".png"))
	taken.append(name)
	print("shot ", name)

func face(tx: float, ty: float, tz: float) -> void:
	var e: Vector3 = game.player.eye()
	game.player.yaw = atan2(-(tx - e.x), -(tz - e.z))
	game.player.pitch = atan2(ty - e.y, Vector2(tx - e.x, tz - e.z).length())

func stand_at_container(c: Dictionary) -> void:
	var cp: Vector3 = c.pos_v
	for a in 16:
		var ang := a * TAU / 16.0
		var pos := Vector3(cp.x + cos(ang) * 1.3, 0, cp.z + sin(ang) * 1.3)
		pos.y = WorldData_().ground_y(pos.x, pos.z, cp.y + 1.0)
		game.player.pos = pos; game.player.vel = Vector3.ZERO
		face(cp.x, cp.y, cp.z)
		var t = game._find_interact()
		if t != null and is_same(t, c):
			return

func WorldData_() -> Node:
	return root.get_node("WorldData")

func _run() -> void:
	await process_frame
	await process_frame
	map_lib = load("res://scripts/ui_map.gd"); items_lib = load("res://scripts/items.gd")
	var hud = game.hud
	var settings: Node = root.get_node("Settings")
	if plates:
		# the world on its own, to put behind the interface in the quick runs
		hud.visible = false
		await wait(1.0)
		await shot("plate_menu", 0.5)
		game.new_game("police")
		hud.visible = false
		game.zombies.clear()
		await shot("plate_day", 1.0)
		var pp := Vector3(-6, 0, -40); pp.y = WorldData_().ground_y(pp.x, pp.z)
		game.player.pos = pp; game.player.yaw = PI; game.game_min = 22.5 * 60.0
		await shot("plate_night", 1.5)
		game.game_min = 17.2 * 60.0; game.player.yaw = PI * 0.5
		await shot("plate_town", 1.5)
		DirAccess.remove_absolute(ProjectSettings.globalize_path(game.SAVE_PATH))
		quit()
		return

	plate("menu")
	if want("menu"):
		await shot("menu_main", 0.8)
	if want("loading"):
		hud.show_loading(0.42, "Raising the town")
		await shot("loading", 0.3)
		hud.hide_loading()
		await wait(0.6)
	if want("create"):
		hud.menus._name = "Casey Doyle"; hud.menus._occ = "police"; hud.menus._traits = {"athletic": true, "keen_hearing": true, "clumsy": true, "smoker": true, "prone_ill": true}
		hud.menus._show_page("create")
		await shot("create", 0.5)
		hud.menus._traits = {"athletic": true, "strong": true, "thick_skin": true}
		hud.menus._show_page("create")
		await shot("create_overspent", 0.4)
		hud.menus._traits = {}
		hud.menus._show_page("")
	if want("settings"):
		for t in ["game", "graphics", "audio", "controls"]:
			hud.menus._tab = t
			hud.menus._show_page("settings")
			await shot("settings_" + t, 0.4)
		hud.menus._tab = "game"
		hud.menus._show_page("")

	# into play
	hud.new_game_setup = {"name": "Casey Doyle", "occupation": "police", "traits": ["athletic", "keen_hearing", "clumsy"]}
	game.new_game("police")
	hud.menus.apply_setup()
	Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
	plate("day")
	var p = game.player
	var cab = null
	for c in game.world.containers:
		if game.world.container_inv(c).count("bat") > 0: cab = c
	if want("hud"):
		if cab != null:
			stand_at_container(cab)
		hud.notify("Autosaved.", "")
		hud.notify("Scavenging is now level 1.", "xp")
		await shot("hud_day", 1.2)
		# a gun in hand, hungry, loaded down
		p.equipped = int(p.inv.find("pistol").uid); p.inv.find("pistol").ammo = 11
		p.stats.hunger = 20.0; p.stats.thirst = 9.0; p.stats.fatigue = 74.0
		p.pitch = 0.2; p.yaw += 1.0
		await shot("hud_gun", 1.0)
		# hurt: bleeding, low, hit from the left
		p.stats.add_wound("armL", "laceration", 30.0); p.stats.add_wound("torso", "bite", 40.0)
		p.stats.parts.head.hp = 40.0; p.stats.parts.legR.hp = 35.0; p.stats.blood_loss = 45.0
		hud.notify("BITTEN on the torso! That is not good.", "bad")
		hud.notify("Lacerated on the left arm!", "bad")
		await wait(0.5)
		hud.damage_flash(0.6)
		hud.damage_from(p.pos + Basis(Vector3.UP, p.yaw) * Vector3(-3, 0, -1))
		hud.hitmark(true)
		await shot("hud_hurt", 0.25)
	if want("night"):
		plate("night")
		game.game_min = 22.5 * 60.0
		p.crouch = true; p.flashlight_on = false
		p.equipped = int(p.inv.find("pistol").uid) if p.inv.find("pistol") != null else null
		var fw: Vector3 = p.forward(); fw.y = 0.0; fw = fw.normalized()
		for i in 3:
			game.zombies.spawn(p.pos.x + fw.x * (7.0 + i * 2.0) + i - 1.0, p.pos.z + fw.z * (7.0 + i * 2.0), 0)
		game.make_noise(p.pos, 30.0)
		await shot("hud_night", 1.6)
		p.crouch = false
		game.game_min = 9.0 * 60.0
		plate("day")
	if want("inventory"):
		for give in [["canned_beans", 3], ["bread", 1], ["bandage", 3], ["disinfectant", 1], ["bat", 1], ["axe", 1], ["leather_jacket", 1], ["plank", 8], ["nails", 30], ["duct_tape", 1], ["book_firstaid", 1], ["ammo_9mm", 24], ["apple", 2], ["school_bag", 1], ["hammer", 1]]:
			if items_lib.exists(String(give[0])): p.inv.add(String(give[0]), int(give[1]))
		var bat = p.inv.find("bat")
		if bat != null:
			bat.dur = 61.0; p.hotbar[2] = int(bat.uid)
		game._open_inventory(null)
		await wait(0.3)
		var rows: Array = hud.inventory.list_p.rows
		for i in rows.size():
			if String(rows[i].id) == "pistol": hud.inventory.select("p", int(rows[i].uid), i)
		await shot("inventory", 0.4)
		for i in rows.size():
			if String(rows[i].id) == "canned_beans": hud.inventory.select("p", int(rows[i].uid), i)
		hud.inventory._set_filter("food")
		await shot("inventory_food", 0.3)
		hud.inventory._set_filter("all")
		game._resume()
	if want("loot"):
		var fridge = null
		for c in game.world.containers:
			if String(c.label) == "Fridge" and game.world.container_inv(c).stacks.size() >= 2: fridge = c
		if fridge == null: fridge = cab
		if fridge != null:
			var inv = game.world.container_inv(fridge)
			var keep = inv.capacity; inv.capacity = INF
			for give in [["canned_soup", 2], ["water_bottle", 2], ["chocolate", 1], ["knife", 1]]:
				if items_lib.exists(String(give[0])): inv.add(String(give[0]), int(give[1]))
			inv.capacity = keep
			fridge["searched"] = true
			game._open_inventory({"label": String(fridge.label), "inv": inv, "ref": fridge})
			await wait(0.3)
			var crow: Array = hud.inventory.list_c.rows
			if not crow.is_empty(): hud.inventory.select("c", int(crow[mini(1, crow.size() - 1)].uid), mini(1, crow.size() - 1))
			await shot("loot", 0.4)
			game._resume()
	if want("health"):
		if p.stats.wound_count() == 0:
			p.stats.add_wound("armL", "laceration", 30.0); p.stats.add_wound("torso", "bite", 40.0)
			p.stats.parts.head.hp = 40.0; p.stats.blood_loss = 45.0
		var fr: Dictionary = p.stats.add_wound("legR", "fracture", 45.0)
		var w2: Dictionary = p.stats.parts.torso.wounds[0]
		w2.bandaged = true; w2.dirty = true; w2.infection = 0.42
		p.stats.knox_infected = true; p.stats.knox = 0.3
		p.inv.add("splint", 1); p.inv.add("suture_kit", 1); p.inv.add("bandage", 2); p.inv.add("disinfectant", 1)
		p.xp["blunt"] = 520.0; p.xp["scavenging"] = 260.0; p.book_until["firstaid"] = game.game_min + 600.0
		game._on_menu("resume")
		Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
		hud.open_health()
		await shot("health", 0.5)
		game._resume()
		fr.splinted = true
	if want("map"):
		var m = game.modules.get("sys_map")
		if m != null:
			for c in game.world.containers:
				if int(c.building) in [1, 28]: c["searched"] = true
			map_lib.markers.append({"x": -60.0, "z": 30.0})
			for i in 80:
				if map_lib.ready(): break
				await wait(0.1)
			m.open_map()
			await shot("map", 0.5)
			m.view.zoom_at(m.view.size * 0.5, 0.5)
			m.view.center = Vector2(-30, 0); m.view.queue_redraw()
			await shot("map_wide", 0.3)
			m.view.center = Vector2(-70, -40); m.view.zoom = 3.4; m.view.queue_redraw()
			await shot("map_close", 0.3)
			game._resume()
	if want("north"):
		settings.values["minimap_rotate"] = false; settings.changed.emit("minimap_rotate")
		await shot("hud_north_up", 0.5)
		settings.values["minimap_rotate"] = true; settings.changed.emit("minimap_rotate")
	if want("pause"):
		Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
		hud.show_menu("pause")
		await shot("pause", 0.5)
		hud.menus._tab = "graphics"; hud.menus._show_page("settings")
		await shot("pause_settings", 0.4)
		hud.menus._show_page("")
		game._resume()
	if want("continue"):
		game.save_game(false)
		game._to_menu()
		plate("menu")
		await shot("menu_continue", 0.6)
		game.continue_game()
		plate("day")
	if want("death"):
		game.player.stats.death_cause = "Bled out."
		game.player.stats.hours_survived = 53.0; game.player.kills = 7
		game._die()
		await shot("death", 1.6)
	DirAccess.remove_absolute(ProjectSettings.globalize_path(game.SAVE_PATH))
	print("UI SHOTS DONE ", taken.size())
	quit()
