extends SceneTree
## Interface capture: loads the game, walks it through the states a player sees outside the 3D world and saves
## a picture of each.
##   godot --path . -s res://scripts/test_ui.gd -- --out=/abs/dir [--only=menu,inventory,…] [--3d] [--plates]
##   godot --headless --path . -s res://scripts/test_ui.gd -- --check      behaviour checks, prints UI CHECK OK
## Without --3d the world is not rendered (fast); a previously captured frame ("plates", made with --plates)
## is shown behind the interface instead so legibility over the world can still be judged.

var game
var out_dir := "user://ui_shots"
var only: Array = []
var use_3d := false
var plates := false
var checks := false
var fails := 0
var plate_rect: TextureRect = null
var taken: Array = []
var map_lib
var items_lib

func _initialize() -> void:
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--out="): out_dir = a.substr(6)
		elif a.begins_with("--only="): only = Array(a.substr(7).split(","))
		elif a == "--3d": use_3d = true
		elif a == "--check": checks = true
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
	var img: Image = Image.load_from_file(path) if FileAccess.file_exists(path) else null
	if img != null:
		var small: Image = img.duplicate(); small.resize(8, 8, Image.INTERPOLATE_BILINEAR)
		if small.get_pixel(4, 4).get_luminance() + small.get_pixel(2, 6).get_luminance() < 0.02:
			img = null                        # a black frame says nothing about legibility
	if img == null:
		img = load("res://assets/ui/keyart.png").get_image() if ResourceLoader.exists("res://assets/ui/keyart.png") else null
	plate_rect.texture = ImageTexture.create_from_image(img) if img != null else null

func wait(sec: float) -> void:
	await create_timer(sec).timeout
	await process_frame
	if use_3d:
		# the sky and its light settle over several frames; a software renderer takes its time over each
		for i in 9:
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
	if checks:
		await _checks(hud, settings)
		return
	if plates:
		# the world on its own, to put behind the interface in the quick runs
		hud.visible = false
		await shot("plate_menu", 0.2)
		game.new_game("police")
		hud.visible = false
		game.zombies.clear()
		await shot("plate_day", 0.2)
		var pp := Vector3(-6, 0, -40); pp.y = WorldData_().ground_y(pp.x, pp.z)
		game.player.pos = pp; game.player.yaw = PI; game.game_min = 22.5 * 60.0
		await shot("plate_night", 0.2)
		game.game_min = 17.2 * 60.0; game.player.yaw = PI * 0.5
		await shot("plate_town", 0.2)
		DirAccess.remove_absolute(ProjectSettings.globalize_path(game.SAVE_PATH))
		quit()
		return

	plate("menu")
	if want("menu"):
		await shot("menu_main", 0.8)
		# the same menu when there is no key art: the live world shows through
		hud.menus._art = null; hud.menus._art_checked = true
		hud.show_menu("main")
		await shot("menu_world", 0.6)
		hud.menus._art_checked = false
		hud.show_menu("main")
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
			game.zombies.spawn(p.pos.x + fw.x * (9.0 + i * 2.0) + i - 1.0, p.pos.z + fw.z * (9.0 + i * 2.0), 0)
		game.make_noise(p.pos, 30.0)
		await shot("hud_night", 2.6)
		game.zombies.clear()
		# the hunted state, staged: the stealth read-out is set by hand and the HUD's own poll held off
		hud._slow = 30.0
		hud.stealth.set_state(1.05, 14.0, 2, 3, false); hud.stealth.modulate.a = 1.0
		hud.compass.threats = PackedFloat32Array([200.0, 0.9, 214.0, 0.6, 236.0, 0.8]); hud.compass.touch()
		await shot("hud_hunted", 0.3)
		hud._slow = 0.0
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

# ------------------------------------------------------------------ behaviour checks (--check)
func ok(name: String, cond: bool, extra := "") -> void:
	print(("PASS " if cond else "FAIL ") + name + ("  " + extra if extra != "" else ""))
	if not cond: fails += 1

func key(code: int) -> void:
	var ev := InputEventKey.new(); ev.physical_keycode = code; ev.keycode = code; ev.pressed = true
	root.push_input(ev)
	var up := InputEventKey.new(); up.physical_keycode = code; up.keycode = code; up.pressed = false
	root.push_input(up)

func mouse(c: Control, pos: Vector2, button := 0) -> void:
	var mv := InputEventMouseMotion.new(); mv.position = pos
	c._gui_input(mv)
	if button != 0:
		var ev := InputEventMouseButton.new(); ev.position = pos; ev.button_index = button; ev.pressed = true
		c._gui_input(ev)

func _checks(hud, settings) -> void:
	var before: Dictionary = settings.values.duplicate()
	ok("main menu is up", hud.window_kind == "main" and game.state == "menu")
	hud.menus._show_page("settings")
	await wait(0.1)
	ok("settings page opens", hud.menus.page == "settings" and hud.menus._set_body.get_child_count() > 4)
	settings.set_value("fov", 88.0)
	ok("a setting is stored", float(settings.v("fov")) == 88.0)
	settings.apply_preset("low")
	ok("preset sets its options", String(settings.v("quality")) == "low" and not bool(settings.v("shadows")) and settings.matches_preset())
	settings.set_value("shadows", true)
	ok("a hand change is noticed", not settings.matches_preset())
	settings.reset()
	ok("reset restores defaults", float(settings.v("fov")) == 75.0 and String(settings.v("quality")) == "high" and settings.matches_preset())
	key(KEY_ESCAPE)
	ok("Esc steps back to the menu", hud.menus.page == "" and hud.window_kind == "main")
	# character creation → play
	hud.menus._begin_create()
	hud.menus._name = "Test Person"; hud.menus._occ = "nurse"; hud.menus._traits = {"strong": true, "clumsy": true}
	hud.menus._create_update()
	ok("points add up", hud.menus.points_left() == 2 - 6 + 3, str(hud.menus.points_left()))
	hud.menus._traits = {"strong": true, "clumsy": true, "prone_ill": true}
	hud.menus._create_update()
	ok("begin is allowed at zero or more", hud.menus.points_left() == 2 and not hud.menus._c_begin.disabled)
	hud.menus._begin_game()
	ok("loading card is up", hud.loading != null and hud.loading.visible)
	for i in 60:
		if game.state == "play" and not hud.loading.visible: break
		await wait(0.1)
	var p = game.player
	ok("new game starts", game.state == "play" and hud.window == null and not hud.loading.visible)
	ok("the survivor is who was made", p.name == "Test Person" and p.occupation == "nurse" and p.traits.has("strong") and p.traits.has("clumsy") and p.inv.count("bandage") >= 4 and int(p.bonus.get("firstaid", 0)) == 3 and int(p.bonus.get("strength", 0)) == 2, "%s %s %s" % [p.name, p.occupation, str(p.traits.keys())])
	game.zombies.clear()
	Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
	await wait(0.3)
	ok("HUD is showing", hud.play.visible and hud.clock_lbl.text.length() == 5 and hud.obj_title.text != "", hud.clock_lbl.text)
	# prompt
	game.target = {"kind": "container", "label": "Fridge", "searched": false}
	hud.set_prompt("[E]  Search Fridge")
	ok("prompt splits key, verb and name", hud.prompt.key == "E" and hud.prompt.verb == "Search" and hud.prompt.name_ == "Fridge", "%s|%s|%s" % [hud.prompt.key, hud.prompt.verb, hud.prompt.name_])
	game.target = {"kind": "door", "locked": true, "open": false}
	hud.set_prompt("[E]  Force the locked door")
	ok("locked door prompt", hud.prompt.verb == "Force" and hud.prompt.name_ == "Locked door", hud.prompt.verb)
	game.target = null
	hud.set_prompt("[E]  Sleep until morning")
	ok("module prompt passes through", hud.prompt.key == "E" and hud.prompt.verb == "Sleep until morning" and hud.prompt.name_ == "")
	# notifications
	var n0: int = hud.notes.get_child_count()
	hud.notify("Same thing.", ""); hud.notify("Same thing.", "")
	ok("repeated notes merge", hud.notes.get_child_count() == n0 + 1)
	hud.damage_flash(0.6); hud.damage_from(p.pos + Vector3(3, 0, 0)); hud.hitmark(true)
	ok("damage direction is shown", hud.ring.hits.size() == 1)
	# inventory by keys
	p.inv.add("bat", 1); p.inv.add("canned_beans", 2)
	key(KEY_TAB)
	await wait(0.2)
	ok("Tab opens the inventory", hud.window_kind == "inv" and hud.inventory.list_p != null)
	var lp = hud.inventory.list_p
	mouse(lp, Vector2(40, 10))
	ok("hover selects a row", hud.hover_uid == int(lp.rows[0].uid) and not hud.hover_in_container)
	key(KEY_DOWN)
	ok("Down moves the selection", hud.hover_uid == int(lp.rows[1].uid))
	var bat_i: int = lp.index_of(int(p.inv.find("bat").uid))
	hud.inventory.select("p", int(p.inv.find("bat").uid), bat_i)
	key(KEY_4)
	ok("a number key sets the hotbar slot", p.hotbar[3] != null and int(p.hotbar[3]) == int(p.inv.find("bat").uid))
	var strip = hud.inventory.strip
	mouse(strip, Vector2(6 * (strip.S + strip.G) + 5.0, 10), MOUSE_BUTTON_LEFT)
	ok("clicking the strip sets the slot", p.hotbar[6] != null and int(p.hotbar[6]) == int(p.inv.find("bat").uid) and p.hotbar[3] == null)
	var scroll0: int = lp.scroll.scroll_vertical
	var win0 = hud.window
	mouse(lp, Vector2(40, bat_i * lp.ROW + 10.0), MOUSE_BUTTON_LEFT)
	ok("a click equips", p.equipped != null and int(p.equipped) == int(p.inv.find("bat").uid))
	ok("the window is not rebuilt by a change", is_same(win0, hud.window) and lp.scroll.scroll_vertical == scroll0 and is_same(lp, hud.inventory.list_p))
	var worn_before = p.worn.get("torso")
	hud.inventory.take_off("torso")
	ok("taking off clothing puts it in the pack", worn_before == null or (p.worn.get("torso") == null and p.inv.by_uid(int(worn_before.uid)) != null))
	if worn_before != null:
		game.use_item(int(worn_before.uid))
		ok("and it can be worn again", p.worn.get("torso") != null)
	var n_beans: int = p.inv.count("canned_beans")
	hud.inventory._drop_one(int(p.inv.find("canned_beans").uid))
	ok("drop one drops one", p.inv.count("canned_beans") == n_beans - 1)
	hud.inventory._set_filter("food")
	var only_food := true
	for r in hud.inventory.list_p.rows:
		if not (String(Items_def(r.id).cat) in ["food", "drink"]): only_food = false
	ok("the filter filters", only_food and hud.inventory.list_p.rows.size() > 0)
	hud.inventory._set_filter("all")
	key(KEY_H)
	await wait(0.1)
	ok("H switches to health", hud.window_kind == "health")
	key(KEY_TAB)
	await wait(0.1)
	ok("Tab switches back to the inventory", hud.window_kind == "inv")
	key(KEY_TAB)
	ok("Tab closes it", hud.window == null)
	# a container
	var c = null
	for cc in game.world.containers:
		if game.world.container_inv(cc).stacks.size() >= 2: c = cc
	if c != null:
		var inv = game.world.container_inv(c)
		game._open_inventory({"label": String(c.label), "inv": inv, "ref": c})
		await wait(0.1)
		var lc = hud.inventory.list_c
		var n_before: int = inv.stacks.size()
		var first_id := String(lc.rows[0].id)
		var had: int = p.inv.count(first_id)
		mouse(lc, Vector2(40, 10), MOUSE_BUTTON_LEFT)
		ok("a click takes from the container", p.inv.count(first_id) > had and inv.stacks.size() == n_before - 1)
		ok("the container list follows", hud.inventory.list_c.rows.size() == inv.stacks.size())
		hud.inventory._take_all()
		ok("take all empties it", inv.stacks.is_empty())
		key(KEY_ESCAPE)
		ok("Esc closes the container", hud.window == null)
	# health and treatment
	p.stats.add_wound("armL", "laceration", 12.0)
	key(KEY_H)
	await wait(0.2)
	ok("H opens health", hud.window_kind == "health" and hud.health._cards.get_child_count() == 6)
	var treat_btn: Button = null
	for b in hud.window.find_children("*", "Button", true, false):
		if (b as Button).text == "BANDAGE": treat_btn = b
	ok("a wound offers a treatment", treat_btn != null)
	if treat_btn != null:
		treat_btn.pressed.emit()
		ok("the treatment starts", game.action != null)
		for i in 100:
			if game.action == null: break
			await wait(0.1)
		ok("and the wound is bandaged", p.stats.bleeding() < 0.01)
	key(KEY_H)
	ok("H closes health", hud.window == null)
	# map
	if game.modules.has("sys_map"):
		key(KEY_M)
		await wait(0.2)
		ok("M opens the map", hud.window_kind == "map")
		for i in 100:
			if map_lib.ready(): break
			await wait(0.1)
		ok("the ground is baked", map_lib.ready())
		var view = game.modules.sys_map.view
		await wait(0.2)
		var z0: float = view.zoom
		var wheel := InputEventMouseButton.new(); wheel.position = view.size * 0.5; wheel.button_index = MOUSE_BUTTON_WHEEL_UP; wheel.pressed = true
		view._gui_input(wheel)
		ok("the wheel zooms", view.zoom > z0)
		mouse(view, view.size * 0.5 + Vector2(60, 40), MOUSE_BUTTON_RIGHT)
		ok("right click places a marker", map_lib.markers.size() == 1)
		var saved = game.modules.sys_map.mod_save()
		mouse(view, view.size * 0.5 + Vector2(60, 40), MOUSE_BUTTON_RIGHT)
		ok("and removes it again", map_lib.markers.is_empty())
		game.modules.sys_map.mod_load(saved)
		ok("markers survive save and load", map_lib.markers.size() == 1)
		key(KEY_M)
		ok("M closes the map", hud.window == null)
	# pause
	key(KEY_ESCAPE)
	await wait(0.1)
	ok("Esc pauses", hud.window_kind == "pause")
	hud.menus._show_page("settings")
	key(KEY_ESCAPE)
	ok("Esc leaves settings, not the pause menu", hud.window_kind == "pause" and hud.menus.page == "")
	key(KEY_ESCAPE)
	ok("Esc resumes", hud.window == null)
	# a minute of play with everything live, for errors
	p.equipped = int(p.inv.find("bat").uid)
	for i in 3: game.zombies.spawn(p.pos.x + 6.0 + i, p.pos.z + 2.0, 0)
	await wait(2.0)
	ok("HUD runs with the dead about", hud.play.visible)
	# death
	p.stats.death_cause = "Bled out."
	game._die()
	await wait(0.2)
	ok("death screen", hud.window_kind == "dead" and not hud.play.visible)
	hud.menus._begin_create()
	hud.menus._occ = "unemployed"; hud.menus._traits = {}
	hud.menus._begin_game()
	for i in 60:
		if game.state == "play" and not hud.loading.visible: break
		await wait(0.1)
	ok("a new survivor can start from the death screen", game.state == "play" and hud.play.visible and game.player.occupation == "unemployed")
	for k in before: settings.values[k] = before[k]
	settings.save()
	DirAccess.remove_absolute(ProjectSettings.globalize_path(game.SAVE_PATH))
	print("UI CHECK ", "OK" if fails == 0 else "FAILED (%d)" % fails)
	quit(0 if fails == 0 else 1)

func Items_def(id) -> Dictionary:
	return items_lib.def(String(id))
