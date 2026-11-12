extends SceneTree
## Headless test of sleeping, crafting, building, barricading and cooking, driving the real game scene on the
## real world data:
##   godot --headless --fixed-fps 60 --path zombie/godot -s res://scripts/test_systems.gd
## Prints PASS / FAIL lines and a final count; exits non-zero on any failure.

const GRID := 2.0

var W: Node                        # the WorldData autoload (not a known name while this script compiles)
var game
var build
var placer
var craft
var cook
var sleep
var p                              # game.player
var passes := 0
var fails := 0
var site := Vector3.ZERO           # the centre of a flat, empty stretch of ground
var hits: Array = []               # structure_hit events seen

func _initialize() -> void:
	_run()

func ok(name: String, cond: bool, extra := "") -> void:
	if cond: passes += 1
	else: fails += 1
	print(("PASS  " if cond else "FAIL  ") + name + ("   [" + extra + "]" if extra != "" else ""))

func frames(n: int) -> void:
	for i in n:
		await process_frame

## let `seconds` of game-loop time go by (or stop early when `until` turns true)
func wait(seconds: float, until := Callable()) -> void:
	var t0: float = game.time
	var guard := 0
	while game.time - t0 < seconds and guard < 200000:
		guard += 1
		if until.is_valid() and until.call():
			return
		await process_frame

func wait_action(limit := 30.0) -> void:
	await wait(limit, func() -> bool: return game.action == null)
	await frames(2)

func key(k: int) -> void:
	var ev := InputEventKey.new()
	ev.physical_keycode = k; ev.keycode = k; ev.pressed = true
	game._unhandled_input(ev)

func stand(x: float, z: float, feet := INF) -> void:
	p.pos = Vector3(x, W.ground_y(x, z, feet), z)
	p.vel = Vector3.ZERO; p.fall_vel = 0.0

## walk the player in a straight line for a while through the real movement code
func walk(dir: Vector3, seconds: float, speed := 3.3) -> void:
	var t0: float = game.time
	while game.time - t0 < seconds:
		p.vel = Vector3(dir.x * speed, 0, dir.z * speed)
		await process_frame

## a clean bill of health, fed and watered
func heal() -> void:
	p.stats = load("res://scripts/stats.gd").new()
	p.stats.hunger = 95.0; p.stats.thirst = 95.0

func tile_centre(tx: int, tz: int) -> Vector3:
	var x := (tx + 0.5) * GRID; var z := (tz + 0.5) * GRID
	return Vector3(x, W.height_at(x, z), z)

func find_site() -> Vector3:
	var sp: Dictionary = W.info.spawn
	var best := Vector3.ZERO; var bd := INF
	for ix in range(-300, 301, 6):
		for iz in range(-300, 301, 6):
			var x := float(ix); var z := float(iz)
			var d := Vector2(x - float(sp.x), z - float(sp.z)).length()
			if d >= bd or placer._near_building(x, z, 16.0) or W.water_depth(x, z) > 0.0:
				continue
			var good := true
			var lo := INF; var hi := -INF
			for i in range(-13, 14):
				for j in range(-13, 14):
					if W.nav_at(x + i, z + j) != 0: good = false
					var h = W.height_at(x + i, z + j)
					lo = minf(lo, h); hi = maxf(hi, h)
				if not good: break
			if not good or hi - lo > 0.5:
				continue
			if not W.query(x - 14, z - 14, x + 14, z + 14).is_empty():
				continue
			best = Vector3(x, W.height_at(x, z), z); bd = d
	return best

func nav_sum() -> int:
	return hash(W.nav)

func stock() -> void:
	for it in [["plank", 400], ["nails", 600], ["log", 20], ["stone", 30], ["rag", 40], ["sheet_metal", 30], ["scrap_metal", 40], ["concrete", 20], ["rope", 6]]:
		p.inv.add(String(it[0]), int(it[1]))

func put(id: String, tx: int, tz: int, rot := 0, edge := -1):
	var c := tile_centre(tx, tz)
	if edge == 0: c.z += 0.9          # aim near the +z edge of the tile
	elif edge == 1: c.x += 0.9        # near the +x edge
	elif edge == 2: c.z -= 0.9
	elif edge == 3: c.x -= 0.9
	return placer.build_at(id, c, rot)

func box_alive(b) -> bool:
	return b != null and W.query(b.min.x, b.min.z, b.max.x, b.max.z).has(b)

func _run() -> void:
	await process_frame
	W = root.get_node("WorldData")
	game = load("res://main.tscn").instantiate()
	root.add_child(game)
	await frames(3)
	game.new_game("police")
	Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
	await frames(5)
	p = game.player
	build = game.modules.get("sys_build"); craft = game.modules.get("sys_craft")
	cook = game.modules.get("sys_cook"); sleep = game.modules.get("sys_sleep")
	ok("the four systems are loaded", build != null and craft != null and cook != null and sleep != null)
	if build == null or craft == null or cook == null or sleep == null:
		_finish(); return
	placer = build.placer
	game.zombies.director_enabled = false
	game.zombies.clear()
	game.zombies.structure_hit.connect(func(ref, dmg) -> void: hits.append([ref, dmg]))
	site = find_site()
	ok("found open ground to build on", site != Vector3.ZERO, "%s" % site)
	stand(site.x, site.z)
	p.stats.hunger = 95.0; p.stats.thirst = 95.0
	await frames(3)
	await test_crafting()
	await test_building()
	await test_zombies_and_walls()
	await test_barricades()
	await test_cooking()
	await test_sleeping()
	await test_save_load()
	_finish()

func _finish() -> void:
	print("\n==== %d passed, %d failed ====" % [passes, fails])
	print("SYSTEMS TEST ", "OK" if fails == 0 else "FAILED (%d)" % fails)
	if game != null:
		if game.has_save():
			DirAccess.remove_absolute(ProjectSettings.globalize_path(game.SAVE_PATH))
		root.remove_child(game)
		game.free()
		game = null; build = null; placer = null; craft = null; cook = null; sleep = null; p = null; hits.clear()
	await process_frame
	quit(1 if fails > 0 else 0)

# ------------------------------------------------------------------ crafting
func test_crafting() -> void:
	print("\n-- crafting")
	var inv = p.inv
	ok("recipes read", craft.recipes.size() >= 70, "%d recipes" % craft.recipes.size())
	# by hand
	inv.add("hoodie", 1)
	var rags0 = inv.count("rag")
	ok("a hand recipe is allowed", craft.can_craft(craft.by_id.rip_rags, "hand") == "")
	ok("crafting starts a timed action", craft.craft(craft.by_id.rip_rags, "hand") and game.action != null and absf(float(game.action.dur) - 3.0) < 0.01, "dur %s" % (game.action.dur if game.action != null else "-"))
	await wait_action()
	ok("hand craft: hoodie becomes 3 rags", inv.count("rag") == rags0 + 3 and inv.count("hoodie") == 0, "%d rags" % inv.count("rag"))
	# refusals
	var nails: Dictionary = craft.by_id.nails
	inv.add("scrap_metal", 10)
	ok("refused away from a workbench", craft.can_craft(nails, "hand") == "Needs a workbench", craft.can_craft(nails, "hand"))
	ok("refused without the tool", craft.can_craft(nails, "workbench") == "Needs a hammer", craft.can_craft(nails, "workbench"))
	inv.add("hammer", 1)
	ok("allowed with tool, station and metal", craft.can_craft(nails, "workbench") == "")
	p.bonus["engineering"] = 0; p.xp["engineering"] = 0.0
	ok("refused below the skill level", craft.can_craft(craft.by_id.sheet_metal, "workbench") == "Requires Engineering 1", craft.can_craft(craft.by_id.sheet_metal, "workbench"))
	inv.remove("plank", inv.count("plank"))
	ok("refused without ingredients", craft.can_craft(craft.by_id.splint, "hand") == "Missing ingredients", craft.can_craft(craft.by_id.splint, "hand"))
	ok("cooking refused without a fire", craft.can_craft(craft.by_id.cook_meat, "campfire", null) == "Light the campfire first")
	var n0 = inv.count("nails")
	ok("a refused craft starts nothing", not craft.craft(nails, "hand") and game.action == null and inv.count("nails") == n0)
	# the window and its key
	key(KEY_N)
	ok("N opens the crafting window", game.hud.is_open() and game.hud.window_kind == "craft" and craft.station == "hand")
	await frames(2)
	key(KEY_N)
	ok("N closes it again", not game.hud.is_open())
	# at a real workbench, reached through the real interaction
	stock()
	var bench = put("workbench", tile_of(site.x) + 4, tile_of(site.z) - 4)
	ok("workbench built", bench != null)
	if bench != null:
		stand(float(bench.cx), float(bench.cz) + 1.6)
		game._face(float(bench.cx), float(bench.y) + 0.8, float(bench.cz))
		var t = game._find_interact()
		ok("looking at the workbench offers it", t != null and String(t.get("kind", "")) == "module" and String(t.prompt).contains("workbench"), str(t.get("prompt", "")) if t != null else "nothing")
		game.target = t; game._interact()
		ok("E on the workbench opens crafting there", game.hud.window_kind == "craft" and craft.station == "workbench")
		var xp0: float = p.xp.engineering
		n0 = inv.count("nails"); var scrap0 = inv.count("scrap_metal")
		ok("bench craft starts", craft.craft(nails, craft.station))
		await wait_action()
		ok("bench craft: 2 scrap become 12 nails, with engineering XP", inv.count("nails") == n0 + 12 and inv.count("scrap_metal") == scrap0 - 2 and p.xp.engineering == xp0 + 3.0, "%d nails, xp %.0f" % [inv.count("nails"), p.xp.engineering])
		game._resume()
		key(KEY_N)
		ok("N beside a workbench opens bench crafting", craft.station == "workbench" and game.hud.window_kind == "craft")
		game._resume()
		build.remove(bench)

func tile_of(v: float) -> int:
	return int(floor(v / GRID))

# ------------------------------------------------------------------ building
func test_building() -> void:
	print("\n-- building")
	p.bonus["carpentry"] = 0; p.xp["carpentry"] = 0.0
	var tx0 := tile_of(site.x); var tz0 := tile_of(site.z)
	var nav0 := nav_sum(); var floors0 = W.floors.size(); var boxes0: int = W._boxes.size(); var refs0 = W.nav_ref.size()
	stand(site.x - 7.0, site.z - 7.0)
	# refusals
	var keep = p.inv.to_rows()
	p.inv.stacks = []
	ok("refused without materials", put("wood_wall", tx0, tz0, 0, 0) == null and placer.reason == "Missing materials", placer.reason)
	p.inv.load_rows(keep)
	ok("refused below the carpentry level", put("wood_stairs", tx0, tz0) == null and placer.reason == "Requires Carpentry 2", placer.reason)
	var plot: Dictionary = W.info.plots[0]
	ok("refused next to a town building", placer.check(placer.target_for("wood_foundation", Vector3(float(plot.x), 0, float(plot.z) + float(plot.d) / 2.0 + 0.5), 0)) == "Too close to a building")
	var lake: Dictionary = W.lake
	ok("refused in the lake", placer.check(placer.target_for("wood_foundation", Vector3(float(lake.x), 0, float(lake.z)), 0)) == "In water", placer.check(placer.target_for("wood_foundation", Vector3(float(lake.x), 0, float(lake.z)), 0)))
	p.bonus["carpentry"] = 4
	# the real click: ghost, validity, the hammering, the cost
	var planks0 = p.inv.count("plank"); var xp0: float = p.xp.carpentry
	stand(site.x, site.z - 5.0)
	var aim := tile_centre(tx0, tz0 - 1)
	game._face(aim.x, aim.y, aim.z)
	placer.select("wood_foundation")
	placer._update_aim()
	ok("the ghost finds a valid tile", placer.active and placer.valid and int(placer.target.tx) == tx0 and int(placer.target.tz) == tz0 - 1, "%s %s" % [placer.reason, placer.target])
	placer.click()
	ok("clicking starts the build action", game.action != null and String(game.action.label).begins_with("Building"))
	await wait_action()
	ok("the foundation is built, paid for, and teaches carpentry", build.list.size() == 1 and p.inv.count("plank") == planks0 - 4 and p.xp.carpentry == xp0 + 4.0, "%d pieces, %d planks" % [build.list.size(), p.inv.count("plank")])
	placer._update_aim()
	ok("the same tile is now refused", not placer.valid and placer.reason == "Tile occupied", placer.reason)
	placer.set_active(false)
	var f0: Dictionary = build.list[0]
	var top := float(f0.y) + 0.3
	ok("foundation: a walkable top", W.floors.size() == floors0 + 1 and absf(W.ground_y(float(f0.cx), float(f0.cz), top) - top) < 0.001)
	stand(float(f0.cx), float(f0.cz), top + 0.2)
	await frames(20)
	ok("the player stands on it", absf(p.pos.y - top) < 0.05, "y %.2f top %.2f" % [p.pos.y, top])
	# a wall: box, nav, ray, player
	var wall = put("wood_wall", tx0 + 1, tz0 - 3, 0, 0)
	ok("wall built", wall != null)
	var wz := float(wall.cz); var wx := float(wall.cx)
	ok("wall: collision box in the world", wall.boxes.size() == 1 and box_alive(wall.boxes[0]) and wall.boxes[0].tag == "pwall" and is_same(wall.boxes[0].ref, wall))
	ok("wall: nav cells blocked and breakable", W.nav_at(wx - 0.5, wz - 0.5) == 25 and W.nav_at(wx + 0.5, wz + 0.5) == 25 and is_same(W.nav_ref.get(W.nav_index(wx - 0.5, wz - 0.5)), wall))
	ok("wall: a ray across it stops", W.raycast(Vector3(wx, float(wall.y) + 1.0, wz - 2.0), Vector3(0, 0, 1), 4.0).t < 2.0 and W.blocked(Vector3(wx, float(wall.y) + 1.5, wz - 2.0), Vector3(wx, float(wall.y) + 1.5, wz + 2.0)))
	ok("wall: a body is pushed out of it", absf(W.resolve(Vector3(wx, float(wall.y), wz), 0.32, 1.8).pos.z - wz) > 0.3)
	stand(wx, wz - 1.5)
	await walk(Vector3(0, 0, 1), 2.0)
	ok("wall: the player cannot walk through", p.pos.z < wz - 0.3, "z %.2f wall %.2f" % [p.pos.z, wz])
	ok("refused on a taken edge", put("wood_wall", tx0 + 1, tz0 - 3, 0, 0) == null and placer.reason == "Edge occupied", placer.reason)
	# a window wall
	var win = put("wood_wall_window", tx0 + 2, tz0 - 3, 0, 0)
	ok("window wall: box and nav", win != null and box_alive(win.boxes[0]) and W.nav_at(float(win.cx), float(win.cz) - 0.5) == 25)
	# a door wall with a door that works
	var door = put("wood_wall_door", tx0 + 3, tz0 - 3, 0, 0)
	ok("door wall built: two posts and a leaf", door != null and door.boxes.size() == 2 and door.has("door_box") and box_alive(door.door_box))
	var dx := float(door.cx); var dz := float(door.cz)
	ok("door shut: doorway blocked in the nav grid (cost 30)", W.nav_at(dx - 0.3, dz - 0.5) == 30 and W.nav_at(dx + 0.3, dz + 0.5) == 30)
	stand(dx, dz - 1.5)
	await walk(Vector3(0, 0, 1), 1.5)
	ok("door shut: the player cannot pass", p.pos.z < dz - 0.3, "z %.2f" % p.pos.z)
	stand(dx, dz - 1.4)
	game._face(dx, float(door.y) + 1.2, dz)
	var t = game._find_interact()
	ok("door: looking at it offers to open it", t != null and String(t.get("prompt", "")) == "[E]  Open door", str(t.get("prompt", "")) if t != null else "nothing")
	game.target = t; game._interact()
	await wait(0.8)
	ok("door open: leaf gone from collision and nav, angle swung", door.open and not door.door_box.solid and W.nav_at(dx - 0.3, dz - 0.5) == 0 and W.nav_at(dx + 0.3, dz + 0.5) == 0 and absf(float(door.angle) - PI / 2.0) < 0.05)
	stand(dx, dz - 1.4)
	await walk(Vector3(0, 0, 1), 1.6)
	ok("door open: the player walks through", p.pos.z > dz + 0.5, "z %.2f door %.2f" % [p.pos.z, dz])
	stand(dx, dz - 1.4)
	game._face(dx, float(door.y) + 1.2, dz)
	t = game._find_interact()
	ok("door: an open door can be closed again", t != null and String(t.get("prompt", "")) == "[E]  Close door", str(t.get("prompt", "")) if t != null else "nothing")
	if t != null:
		game.target = t; game._interact()
	await wait(0.6)
	ok("door shut again", not door.open and door.door_box.solid and W.nav_at(dx - 0.3, dz - 0.5) == 30)
	# a gate across two edges
	var gate = put("gate", tx0 - 4, tz0 - 4, 0, 0)
	ok("gate built across two edges", gate != null and build.edge_taken(tx0 - 4, tz0 - 4, 0) and build.edge_taken(tx0 - 3, tz0 - 4, 0) and box_alive(gate.door_box) and gate.door_box.max.x - gate.door_box.min.x > 3.7)
	if gate != null:
		ok("gate shut: all four cells blocked", W.nav_at(float(gate.cx) - 1.5, float(gate.cz) - 0.5) == 30 and W.nav_at(float(gate.cx) + 1.5, float(gate.cz) + 0.5) == 30)
		build.toggle_door(gate)
		await wait(0.8)
		stand(float(gate.cx), float(gate.cz) - 1.5)
		await walk(Vector3(0, 0, 1), 1.6)
		ok("gate open: the way is clear", gate.open and W.nav_at(float(gate.cx), float(gate.cz) - 0.5) == 0 and p.pos.z > float(gate.cz) + 0.5, "z %.2f" % p.pos.z)
	# stairs and a roof to climb to
	var st = put("wood_stairs", tx0, tz0 + 2, 0)
	ok("stairs built", st != null and st.floors.size() == build.STAIR_STEPS)
	if st != null:
		var sy := float(st.y)
		ok("stairs: the head is 3.15 m up", absf(W.ground_y(float(st.cx), float(st.cz) + 0.95, sy + 3.0) - (sy + 3.15)) < 0.01)
		stand(float(st.cx), float(st.cz) - 1.6)
		await walk(Vector3(0, 0, 1), 1.15, 2.4)
		ok("stairs: the player climbs them", p.pos.y > sy + 2.2, "rose %.2f m" % (p.pos.y - sy))
		stand(float(st.cx) - 1.7, float(st.cz) - 0.5)
		await walk(Vector3(1, 0, 0), 1.2)
		ok("stairs: no walking in through the side", p.pos.x < float(st.cx) - 0.9 and absf(p.pos.y - sy) < 0.3, "x %.2f y %.2f" % [p.pos.x - float(st.cx), p.pos.y - sy])
	var roof = put("wood_roof", tx0, tz0 + 3)
	ok("roof built 3 m over the ground", roof != null and absf(float(roof.y) - (W.height_at(float(roof.cx), float(roof.cz)) + 3.0)) < 0.6, "y %.2f" % (float(roof.y) if roof != null else 0.0))
	if roof != null:
		var ry := float(roof.y) + 0.15
		ok("roof: walkable on top, open underneath", absf(W.ground_y(float(roof.cx), float(roof.cz), ry + 0.1) - ry) < 0.001 and W.ground_y(float(roof.cx), float(roof.cz), ry - 2.5) < ry - 2.0)
		ok("roof: shelter for what is under it", build.under_roof(Vector3(float(roof.cx), ry - 3.0, float(roof.cz))))
	# a watchtower and its ladder
	var tower = put("watchtower", tx0 - 4, tz0)
	ok("watchtower built: four posts in the nav grid, a platform", tower != null and W.nav_at(float(tower.cx) - 0.9, float(tower.cz) - 0.9) == 25 and tower.floors.size() == 1)
	if tower != null:
		stand(float(tower.cx), float(tower.cz) - 2.2)
		game._face(float(tower.cx), float(tower.y) + 1.5, float(tower.cz))
		t = game._find_interact()
		ok("tower: the ladder is offered", t != null and String(t.get("prompt", "")).contains("Climb the ladder"), str(t.get("prompt", "")) if t != null else "nothing")
		if t != null:
			game.target = t; game._interact()
		await frames(10)
		ok("tower: the player is on the platform", absf(p.pos.y - (float(tower.y) + 5.0)) < 0.1, "y %.2f" % (p.pos.y - float(tower.y)))
		await walk(Vector3(1, 0, 0), 1.5)
		ok("tower: the rail keeps you on it", absf(p.pos.y - (float(tower.y) + 5.0)) < 0.1 and p.pos.x < float(tower.cx) + 1.0)
		t = game._find_interact()
		ok("tower: and the ladder leads down", t != null and String(t.get("prompt", "")).contains("Climb down"))
		if t != null:
			game.target = t; game._interact()
		await frames(10)
		ok("tower: back on the ground", p.pos.y < float(tower.y) + 0.5)
	# the stations
	var crate = put("crate", tx0 - 2, tz0 + 2)
	ok("crate built: a box, nav cost 20, 60 kg of room", crate != null and box_alive(crate.boxes[0]) and W.nav_at(float(crate.cx) - 0.3, float(crate.cz)) == 20 and crate.inv.capacity == 60.0)
	if crate != null:
		stand(float(crate.cx), float(crate.cz) - 1.5)
		await walk(Vector3(0, 0, 1), 1.5)
		ok("crate: solid to the player", p.pos.z < float(crate.cz) - 0.5)
		stand(float(crate.cx), float(crate.cz) - 1.3)
		game._face(float(crate.cx), float(crate.y) + 0.5, float(crate.cz))
		t = game._find_interact()
		ok("crate: offered as a container", t != null and String(t.get("prompt", "")).contains("Open the wooden crate"), str(t.get("prompt", "")) if t != null else "nothing")
		if t != null:
			game.target = t; game._interact()
		ok("crate: opens as an inventory", game.hud.window_kind == "inv" and game.hud.container != null and game.hud.container.inv == crate.inv)
		game.transfer(p.inv, crate.inv, int(p.inv.find("rope").uid), 3, true)
		ok("crate: holds what is put in", crate.inv.count("rope") == 3)
		game._resume()
	var bed = put("bed", tx0 - 2, tz0 + 4)
	ok("bedroll built (nothing to bump into)", bed != null and bed.boxes.is_empty() and String(bed.station) == "bed")
	var fire = put("campfire", tx0 - 4, tz0 + 4)
	ok("campfire built with a fire to light", fire != null and fire.has("fire") and not fire.fire.lit and cook.fires.size() == 1)
	var stove = put("stove", tx0 + 3, tz0 + 3)
	ok("stove built: solid, with a firebox", stove != null and stove.has("fire") and box_alive(stove.boxes[0]) and cook.fires.size() == 2)
	var rain = put("rain_collector", tx0 + 3, tz0 + 1)
	ok("rain collector built with water in it", rain != null and float(rain.rain) == 2.0)
	if rain != null:
		p.inv.add("empty_bottle", 1)
		var w0 = p.inv.count("water_bottle")
		build._use_rain(rain)
		ok("rain collector fills a bottle", p.inv.count("water_bottle") == w0 + 1 and float(rain.rain) == 1.0)
	var rack = put("drying_rack", tx0 - 4, tz0 + 2)
	if rack != null:
		rack.inv.add("raw_meat", 2)
		build._update_stations(8.1 * 60.0)
		ok("smoking rack turns meat to jerky in 8 hours", rack.inv.count("jerky") == 4 and rack.inv.count("raw_meat") == 0)
	var on_floor = put("bed", tx0, tz0 - 1)
	ok("a station on a foundation stands on its top", on_floor != null and absf(float(on_floor.y) - top) < 0.001)
	ok("every kind of piece stands", build.list.size() == 15, "%d pieces" % build.list.size())
	# hit points and mending
	hits.clear()
	var hp0 := float(wall.hp)
	game.zombies.structure_hit.emit(wall, 100.0)
	ok("structure_hit wears a wall down", absf(float(wall.hp) - (hp0 - 100.0)) < 0.01 and not wall.destroyed)
	p.equipped = int(p.inv.find("hammer").uid)
	stand(wx, wz - 1.4)
	game._face(wx, float(wall.y) + 1.2, wz)
	t = game._find_interact()
	ok("a hammer in hand offers to mend it", t != null and String(t.get("prompt", "")).contains("Mend the wooden wall"), str(t.get("prompt", "")) if t != null else "nothing")
	if t != null:
		game.target = t; game._interact()
	await wait_action()
	ok("mending restores hit points for a share of the cost", float(wall.hp) > hp0 - 100.0 + 1.0, "%.0f -> %.0f" % [hp0 - 100.0, float(wall.hp)])
	p.equipped = null
	game.zombies.structure_hit.emit(win, 1.0e6)
	ok("enough damage destroys a piece: box, nav and edge freed", win.destroyed and not build.edge_taken(tx0 + 2, tz0 - 3, 0) and W.nav_at(float(win.cx) + 0.5, float(win.cz) - 0.5) == 0 and build.list.size() == 14)
	ok("the wall next to it still stands in the nav grid", W.nav_at(wx + 0.5, wz - 0.5) == 25)
	# dismantling gives three quarters back, and the crate's contents
	stand(site.x - 8.0, site.z - 8.0)
	var stash: Array = p.inv.to_rows()
	p.inv.stacks = []                               # empty hands, so the refund can be carried
	build.dismantle(wall)
	ok("dismantling refunds 75% (3 of 3 planks rounded up, 3 of 4 nails)", p.inv.count("plank") == 3 and p.inv.count("nails") == 3 and wall.destroyed, "%d planks, %d nails" % [p.inv.count("plank"), p.inv.count("nails")])
	p.inv.load_rows(stash)
	var rope_before: int = p.inv.count("rope")
	ok("a foundation with something on it stays until that is gone", not build.dismantle(f0) and not f0.destroyed and build.dismantle_blocked(f0) != "")
	var drops0: int = game.world.drops.size()
	build.dismantle(crate)
	ok("a dismantled crate leaves its contents in a bag", game.world.drops.size() == drops0 + 1 and game.world.drops[drops0].inv.count("rope") == 3 and p.inv.count("rope") == rope_before)
	for _pass in 2:                                 # the foundation goes once the bedroll on it has gone
		for q in build.list.duplicate():
			build.dismantle(q)
	await frames(3)
	ok("dismantling everything puts the world back exactly", build.list.is_empty() and nav_sum() == nav0 and W.floors.size() == floors0 and W._boxes.size() == boxes0 and W.nav_ref.size() == refs0 and cook.fires.is_empty(),
		"nav %s floors %d/%d boxes %d/%d refs %d/%d" % [nav_sum() == nav0, W.floors.size(), floors0, W._boxes.size(), boxes0, W.nav_ref.size(), refs0])
	for d in game.world.drops.duplicate():
		game.world.remove_drop(d)
	# the whole catalogue, one of each: every model loads, every piece comes down clean
	stock()
	var i := 0
	var bad: Array = []
	for id in build.defs:
		var kind := String(build.defs[id].kind)
		var q = put(String(id), tx0 - 5 + (i % 5) * 2, tz0 - 5 + int(i / 5.0) * 2, 0, 0 if build.EDGE_KINDS.has(kind) else -1)
		i += 1
		if q == null or q.node == null or q.node.find_children("*", "MeshInstance3D", true, false).is_empty():
			bad.append("%s (%s)" % [id, placer.reason])
	ok("every piece in the catalogue can be built", bad.is_empty() and build.list.size() == build.defs.size(), "%d pieces%s" % [build.list.size(), "" if bad.is_empty() else ", not " + ", ".join(bad)])
	for q in build.list.duplicate():
		build.remove(q)
	await frames(3)
	ok("and removed without a trace", build.list.is_empty() and nav_sum() == nav0 and W.floors.size() == floors0 and W._boxes.size() == boxes0 and W.nav_ref.size() == refs0)

# ------------------------------------------------------------------ the dead against walls
## keep every horde member heading for a spot (a horde scatters its members' goals around the target)
func send_at(c: Vector3) -> void:
	var zs = game.zombies
	for zz in zs.list:
		if zz.horde == 0 or zz.state == zs.S_DEAD:
			continue
		zz.target = c
		if zz.state == zs.S_IDLE or zz.state == zs.S_WANDER or zz.state == zs.S_SEARCH:
			zs._set_state(zz, zs.S_INVESTIGATE)

func test_zombies_and_walls() -> void:
	print("\n-- zombies and walls")
	p.bonus["carpentry"] = 4
	stock()
	var tx0 := tile_of(site.x); var tz0 := tile_of(site.z)
	# a one-tile hut with the player shut inside
	var walls: Array = []
	walls.append(put("wood_wall", tx0, tz0, 0, 0))        # +z side
	walls.append(put("wood_wall", tx0, tz0, 0, 2))        # -z side
	walls.append(put("wood_wall", tx0, tz0, 0, 1))        # +x side
	walls.append(put("wood_wall_door", tx0, tz0, 0, 3))   # -x side, door shut
	ok("a hut of three walls and a door", not walls.has(null) and build.list.size() == 4)
	if walls.has(null):
		return
	var c := tile_centre(tx0, tz0)
	stand(c.x, c.z)
	p.stats.hunger = 95.0; p.stats.thirst = 95.0
	for w in walls:
		w.hp = 1.0e7
	# one of a horde outside, sent at the player: it must not get in
	game.zombies.spawn_horde(c.x + 10.0, c.z, 1, p.pos)
	var z = game.zombies.list[game.zombies.list.size() - 1]
	hits.clear()
	var inside := false
	var t0: float = game.time
	while game.time - t0 < 18.0:
		send_at(c)
		p.vel = Vector3.ZERO; p.pos.x = c.x; p.pos.z = c.z
		if absf(z.pos.x - c.x) < 0.85 and absf(z.pos.z - c.z) < 0.85: inside = true
		await process_frame
	var hit_ours := false
	for h in hits:
		if h[0] is Dictionary and h[0].get("built", false): hit_ours = true
	ok("a zombie walking at the hut cannot get in", not inside and String(z.state) != "dead", "ended %.1f m away, state %s" % [Vector2(z.pos.x - c.x, z.pos.z - c.z).length(), z.state])
	ok("it bashes what is in its way (structure_hit on a built piece)", hit_ours, "%d hits" % hits.size())
	# now a horde, and walls of ordinary strength
	for w in walls:
		w.hp = 120.0
	game.zombies.spawn_horde(c.x + 12.0, c.z + 1.0, 7, p.pos)
	var broken = null
	t0 = game.time
	while game.time - t0 < 60.0 and broken == null:
		send_at(c)
		p.vel = Vector3.ZERO; p.pos.x = c.x; p.pos.z = c.z
		p.stats.parts.torso.hp = 100.0
		for w in walls:
			if w.destroyed: broken = w
		await process_frame
	ok("a horde bashes a wall down", broken != null, "after %.1f s" % (game.time - t0))
	if broken != null:
		var bx := float(broken.cx); var bz := float(broken.cz)
		var free := true
		for off in [[-0.5, -0.5], [0.5, 0.5], [-0.5, 0.5], [0.5, -0.5]]:
			var v = W.nav_at(bx + off[0], bz + off[1])
			var ref = W.nav_ref.get(W.nav_index(bx + off[0], bz + off[1]))
			if ref != null and is_same(ref, broken): free = false
			if v != 0 and ref == null: free = false
		ok("the destroyed wall is out of the nav grid and the collision world", free and broken.boxes.is_empty() and build.list.size() == 3)
	game.zombies.clear()
	heal()
	for q in build.list.duplicate():
		build.remove(q)
	await frames(2)

# ------------------------------------------------------------------ barricades
func _front_of(d: Dictionary, dist: float) -> Vector3:
	# a spot outside the opening, on whichever side is not a building's floor
	var n := Vector3(0, 0, 1) if String(d.axis) == "x" else Vector3(1, 0, 0)
	for s in [1.0, -1.0]:
		var q: Vector3 = Vector3(float(d.cx), 0, float(d.cz)) + n * float(s) * dist
		if W.floor_at(q.x, q.z).is_empty():
			return Vector3(q.x, W.ground_y(q.x, q.z, float(d.y) + 1.0), q.z)
	var q2 := Vector3(float(d.cx), 0, float(d.cz)) + n * dist
	return Vector3(q2.x, W.ground_y(q2.x, q2.z, float(d.y) + 1.0), q2.z)

func test_barricades() -> void:
	print("\n-- barricades")
	var world = game.world
	var door = null
	for id in world.doors:
		var d: Dictionary = world.doors[id]
		if not d.open and not d.locked and d.box != null and d.node != null:
			door = d; break
	var win = null
	for id in world.windows:
		var w: Dictionary = world.windows[id]
		if not w.broken and w.box != null and w.box.solid and w.node != null and int(w.data.building) == int(door.data.building):
			win = w; break
	ok("a town door and window to work on", door != null and win != null)
	if door == null or win == null:
		return
	p.inv.add("plank", 20); p.inv.add("nails", 40)
	if p.inv.count("hammer") == 0: p.inv.add("hammer", 1)
	var hp0 := float(door.hp)
	var dd: Dictionary = door.data
	var at := _front_of(dd, 1.2)
	p.pos = at; p.vel = Vector3.ZERO
	game._face(float(dd.cx), float(dd.y) + 1.1, float(dd.cz))
	p.equipped = null
	var t = game._find_interact()
	ok("without a hammer it is just a door", t != null and String(t.get("kind", "")) == "door")
	p.equipped = int(p.inv.find("hammer").uid)
	t = game._find_interact()
	ok("hammer in hand: the door can be barricaded", t != null and String(t.get("prompt", "")).begins_with("[E]  Barricade the door"), str(t.get("prompt", "")) if t != null else "nothing")
	var planks0 = p.inv.count("plank"); var xp0: float = p.xp.carpentry
	if t != null:
		game.target = t; game._interact()
	ok("barricading is a timed action", game.action != null and String(game.action.label) == "Barricading…")
	await wait_action()
	var step: float = build.barricade_step()
	ok("door barricaded: boards up, 2 planks and 3 nails spent, carpentry XP", float(door.get("barricade", 0.0)) == step and door.get("bar_node") != null and p.inv.count("plank") == planks0 - 2 and p.xp.carpentry == xp0 + 6.0, "%.0f" % float(door.get("barricade", 0.0)))
	ok("door barricaded: solid, and marked for the dead to break (cost 45)", door.box.solid and W.nav_at(float(dd.cx), float(dd.cz)) == 45 and is_same(W.nav_ref.get(W.nav_index(float(dd.cx), float(dd.cz))), door))
	t = game._find_interact()
	ok("the same door can be reinforced", t != null and String(t.get("prompt", "")).begins_with("[E]  Reinforce the barricade"))
	if t != null:
		game.target = t; game._interact()
	await wait_action()
	ok("a second layer doubles it", absf(float(door.barricade) - step * 2.0) < 0.01)
	p.equipped = null
	t = game._find_interact()
	ok("no hammer: it reads as barricaded and will not open", t != null and String(t.get("kind", "")) == "module" and String(t.prompt).contains("Barricaded door"), str(t.get("prompt", "")) if t != null else "nothing")
	if t != null:
		game.target = t; game._interact()
	world.set_door(door, true)                      # something else swings it open
	await wait(0.6)
	ok("a boarded door stays shut whatever opens it", not door.open and door.box.solid)
	game.zombies.structure_hit.emit(door, 40.0)
	ok("the dead strike the boards first, not the door", absf(float(door.barricade) - (step * 2.0 - 40.0)) < 0.01 and float(door.get("hp_real", 0.0)) == hp0 and not door.open)
	game.zombies.structure_hit.emit(door, step * 2.0)
	ok("boards torn down: the door is itself again", float(door.barricade) <= 0.0 and door.get("bar_node") == null and float(door.hp) == hp0 and door.box.solid and W.nav_at(float(dd.cx), float(dd.cz)) == 30, "hp %.0f nav %d" % [float(door.hp), W.nav_at(float(dd.cx), float(dd.cz))])
	# the window
	var wd: Dictionary = win.data
	at = _front_of(wd, 1.1)
	p.pos = at; p.vel = Vector3.ZERO
	game._face(float(wd.cx), float(wd.y) + 0.6, float(wd.cz))
	p.equipped = int(p.inv.find("hammer").uid)
	t = game._find_interact()
	ok("hammer in hand: the window can be barricaded", t != null and String(t.get("prompt", "")).begins_with("[E]  Barricade the window"), str(t.get("prompt", "")) if t != null else "nothing")
	if t != null:
		game.target = t; game._interact()
	await wait_action()
	ok("window barricaded", float(win.get("barricade", 0.0)) == step and win.get("bar_node") != null and win.box.solid and W.nav_at(float(wd.cx), float(wd.cz)) == 45)
	game.zombies.structure_hit.emit(win, 20.0)
	await wait(0.5)
	ok("a blow breaks the glass but the boards hold the opening", win.box.solid and float(win.barricade) == step - 20.0 and W.nav_at(float(wd.cx), float(wd.cz)) == 45)
	p.equipped = null
	p.inv.add("crowbar", 1)
	t = game._find_interact()
	ok("a crowbar offers to pry the boards off", t != null and String(t.get("prompt", "")).contains("Pry the boards off the window"), str(t.get("prompt", "")) if t != null else "nothing")
	game.zombies.structure_hit.emit(win, step)
	ok("boards torn off a broken window leave it open", float(win.barricade) <= 0.0 and not win.box.solid and win.get("bar_node") == null)
	p.inv.remove("crowbar", 1)

# ------------------------------------------------------------------ fire and food
func test_cooking() -> void:
	print("\n-- campfire and cooking")
	stock()
	var tx0 := tile_of(site.x); var tz0 := tile_of(site.z)
	var fire = put("campfire", tx0, tz0)
	ok("campfire built", fire != null and cook.fires.size() == 1)
	if fire == null:
		return
	var fx := float(fire.cx); var fz := float(fire.cz)
	stand(fx, fz - 1.4)
	game._face(fx, float(fire.y) + 0.2, fz)
	var t = game._find_interact()
	ok("looking at the campfire offers it, cold", t != null and String(t.get("prompt", "")) == "[E]  Tend the campfire  ·  cold", str(t.get("prompt", "")) if t != null else "nothing")
	if t != null:
		game.target = t; game._interact()
	ok("E opens the cooking window", game.hud.window_kind == "cook" and is_same(cook.open_piece, fire))
	game._resume()
	while p.inv.count("lighter") > 0: p.inv.remove("lighter", 1)
	ok("no lighting without fuel", not cook.light(fire) and not fire.fire.lit)
	var logs = p.inv.count("log")
	ok("a log is 54 minutes of fuel", cook.add_fuel(fire, "log") and float(fire.fire.fuel) == 54.0 and p.inv.count("log") == logs - 1)
	ok("coal and petrol do not go on a cooking fire", not cook.add_fuel(fire, "coal") and not cook.fuels().has("gas_can"))
	ok("no lighting without a lighter", not cook.light(fire) and not fire.fire.lit)
	p.inv.add("lighter", 1)
	ok("lit with a lighter", cook.light(fire) and fire.fire.lit)
	await frames(5)
	var f: Dictionary = cook.fires[0]
	ok("a lit fire gives light, flame and smoke", f.light.visible and f.light.light_energy > 1.0 and f.flames.emitting and f.smoke.emitting, "energy %.2f" % f.light.light_energy)
	var e0: float = f.light.light_energy
	await frames(7)
	ok("the light flickers", absf(f.light.light_energy - e0) > 0.001)
	ok("warmth within 4 m only", cook.is_near_fire(Vector3(fx + 2.0, float(fire.y), fz)) and not cook.is_near_fire(Vector3(fx + 6.0, float(fire.y), fz)))
	ok("the game feels the fire", game._near_fire() if game.has_method("_near_fire") else true)
	# cooking
	p.inv.add("raw_meat", 2); p.inv.add("dirty_water", 1)
	var cooked0 = p.inv.count("cooked_meat"); var water0 = p.inv.count("water_bottle")
	ok("raw meat can be cooked on the lit fire", craft.can_craft(craft.by_id.cook_meat, "campfire", fire) == "")
	craft.craft(craft.by_id.cook_meat, "campfire", fire)
	ok("cooking takes time", game.action != null and String(game.action.label) == "Cooking Cooked Meat…")
	await wait_action()
	ok("raw meat becomes cooked meat", p.inv.count("cooked_meat") == cooked0 + 1 and p.inv.count("raw_meat") == 1)
	craft.craft(craft.by_id.boil_water, "campfire", fire)
	await wait_action()
	ok("dirty water boils clean", p.inv.count("water_bottle") == water0 + 1 and p.inv.count("dirty_water") == 0)
	ok("a stove meal cannot be made on a campfire", craft.can_craft(craft.by_id.stew, "campfire", fire) == "Needs a stove", craft.can_craft(craft.by_id.stew, "campfire", fire))
	# burning down
	var fuel0 := float(fire.fire.fuel)
	await wait(1.5)
	ok("fuel burns away with game time", float(fire.fire.fuel) < fuel0, "%.2f -> %.2f" % [fuel0, float(fire.fire.fuel)])
	fire.fire.fuel = 0.02
	await wait(1.0)
	ok("out of fuel, the fire dies and the light goes", not fire.fire.lit and f.light.light_energy < 0.5 and not f.flames.emitting and not cook.is_near_fire(Vector3(fx, float(fire.y), fz)))
	ok("and nothing cooks on a dead fire", craft.can_craft(craft.by_id.cook_meat, "campfire", fire) == "Light the campfire first")
	# a stove takes the campfire's recipes and its own
	var stove = put("stove", tx0 + 2, tz0)
	if stove != null:
		stove.fire.fuel = 30.0; stove.fire.lit = true
		ok("a lit stove cooks campfire food and stove meals", craft.can_craft(craft.by_id.cook_meat, "stove", stove) == "" and cook.recipes_for(stove).size() > cook.recipes_for(fire).size() and not cook.recipes_for(stove).has(craft.by_id.charcoal))
		build.remove(stove)
	build.remove(fire)
	await frames(2)
	ok("removing the fire removes its light", cook.fires.is_empty())

# ------------------------------------------------------------------ sleep
func test_sleeping() -> void:
	print("\n-- sleeping")
	var st = p.stats
	ok("the town's beds are known", sleep.bed_count() >= 70, "%d beds" % sleep.bed_count())
	# a town bed can be looked at
	var found := false
	var prompt := ""
	for key in sleep.beds:
		for b in sleep.beds[key]:
			var c: Vector3 = (b.min + b.max) * 0.5
			for a in 8:
				var ang := a * TAU / 8.0
				var pos := Vector3(c.x + cos(ang) * 1.6, 0, c.z + sin(ang) * 1.6)
				pos.y = W.ground_y(pos.x, pos.z, b.min.y + 0.5)
				if absf(pos.y - b.min.y) > 0.3: continue
				p.pos = pos; p.vel = Vector3.ZERO
				game._face(c.x, c.y, c.z)
				var t = game._find_interact()
				if t != null and String(t.get("kind", "")) == "module" and (String(t.prompt).contains("Sleep in the bed") or String(t.prompt).begins_with("Bed")):
					found = true; prompt = String(t.prompt)
					break
			if found: break
		if found: break
	ok("a bed in town offers sleep", found, prompt)
	# on a bedroll out in the open
	stock()
	var tx0 := tile_of(site.x); var tz0 := tile_of(site.z)
	var bed = put("bed", tx0, tz0)
	var wall = put("wood_wall", tx0 + 3, tz0, 0, 0)
	ok("bedroll laid out", bed != null)
	stand(float(bed.cx), float(bed.cz) - 1.2)
	game._face(float(bed.cx), float(bed.y) + 0.1, float(bed.cz))
	st.fatigue = 10.0
	var t2 = game._find_interact()
	ok("not tired: the bedroll says so", t2 != null and String(t2.get("prompt", "")) == "Bedroll  ·  you are not tired", str(t2.get("prompt", "")) if t2 != null else "nothing")
	ok("not tired: no sleep", not sleep.try_sleep() and not p.sleeping)
	st.fatigue = 80.0
	st.add_wound("armL", "laceration", 5.0)
	ok("bleeding: no sleep", not sleep.try_sleep() and sleep.why_not().contains("bleeding"))
	heal(); st = p.stats; st.fatigue = 80.0
	var z = game.zombies.spawn(p.pos.x + 9.0, p.pos.z, 0)
	await frames(3)
	ok("zombies within 15 m: no sleep", not sleep.try_sleep() and sleep.why_not() == "You can't sleep with zombies nearby!", sleep.why_not())
	game.zombies.clear()
	await frames(3)
	st.hunger = 5.0
	ok("starving: no sleep", not sleep.try_sleep())
	st.hunger = 95.0
	# through the night
	game.game_min = 22.0 * 60.0
	st.fatigue = 85.0
	t2 = game._find_interact()
	ok("tired: the bedroll offers sleep", t2 != null and String(t2.get("prompt", "")).begins_with("[E]  Sleep on the bedroll"), str(t2.get("prompt", "")) if t2 != null else "nothing")
	if t2 != null:
		game.target = t2; game._interact()
	ok("asleep", p.sleeping)
	var gm0: float = game.game_min
	await wait(1.0)
	var rate: float = (game.game_min - gm0) / 1.0
	ok("time runs fast and the screen goes dark", rate > 20.0 and sleep._fade.visible and sleep._fade.color.a > 0.5, "%.0f game minutes a second, fade %.2f" % [rate, sleep._fade.color.a])
	ok("a sleeper does not move", sleep.mod_blocks_control())
	await wait(40.0, func() -> bool: return not p.sleeping)
	var hrs: float = game.hours()
	ok("woke rested after the night", not p.sleeping and (st.fatigue <= 2.5 or (hrs > 7.0 and hrs < 8.5 and st.fatigue < 30.0)) and game.game_min - gm0 > 300.0, "fatigue %.1f at %02d:%02d after %.1f h" % [st.fatigue, int(hrs), int(fmod(hrs, 1.0) * 60.0), (game.game_min - gm0) / 60.0])
	await wait(1.5)
	ok("the dark lifts", not sleep._fade.visible or sleep._fade.color.a < 0.2)
	# E gets you up
	st.fatigue = 70.0
	sleep.try_sleep()
	await frames(5)
	key(KEY_E)
	ok("E wakes a sleeper", not p.sleeping)
	# a zombie coming close
	st.fatigue = 70.0; st.panic = 0.0
	ok("asleep again", sleep.try_sleep() and p.sleeping)
	var danger := [false]
	var on_wake := func(by: bool) -> void: danger[0] = by
	sleep.woke.connect(on_wake)
	game.zombies.spawn(p.pos.x + 4.0, p.pos.z, 0)
	await wait(4.0, func() -> bool: return not p.sleeping)
	ok("a zombie close by wakes you with a start", not p.sleeping and danger[0] and st.panic >= 60.0, "panic %.0f" % st.panic)
	game.zombies.clear()
	await frames(3)
	# struck in your sleep: the game itself ends the sleep, the module makes it a rude awakening
	st.fatigue = 70.0; st.panic = 0.0; danger[0] = false
	sleep.try_sleep()
	await frames(5)
	p.sleeping = false
	await frames(3)
	ok("being attacked wakes you abruptly", danger[0] and st.panic >= 60.0)
	# a crash nearby
	st.fatigue = 70.0; danger[0] = false
	sleep.try_sleep()
	await frames(5)
	game.zombies.structure_hit.emit(wall, 5.0)
	ok("a blow on a wall nearby wakes you", not p.sleeping and danger[0])
	sleep.woke.disconnect(on_wake)
	build.remove(bed); build.remove(wall)
	heal()

# ------------------------------------------------------------------ saving
func test_save_load() -> void:
	print("\n-- save and load")
	stock()
	p.bonus["carpentry"] = 4
	var tx0 := tile_of(site.x); var tz0 := tile_of(site.z)
	stand(site.x - 6.0, site.z - 6.0)
	var nav_empty := nav_sum(); var boxes_empty: int = W._boxes.size(); var floors_empty = W.floors.size()
	var found = put("wood_foundation", tx0, tz0)
	var wall = put("wood_wall", tx0 + 3, tz0, 0, 0)
	var door = put("wood_wall_door", tx0 + 4, tz0, 0, 0)
	var crate = put("crate", tx0 + 2, tz0 + 3)
	var fire = put("campfire", tx0 - 3, tz0)
	var stairs = put("wood_stairs", tx0 - 3, tz0 + 3, 1)
	ok("a small camp to save", not [found, wall, door, crate, fire, stairs].has(null))
	build.set_door(door, true, true)
	crate.inv.add("canned_beans", 3)
	wall.hp = 123.0
	fire.fire.fuel = 200.0; fire.fire.lit = true
	var world = game.world
	var tdoor = null
	for id in world.doors:
		var d: Dictionary = world.doors[id]
		if not d.open and not d.locked and d.box != null and d.node != null:
			tdoor = d; break
	var twin = null
	for id in world.windows:
		var w: Dictionary = world.windows[id]
		if not w.broken and w.box != null and w.box.solid and float(w.get("barricade", 0.0)) <= 0.0:
			twin = w; break
	var door_hp := float(tdoor.hp)
	build.barricade(tdoor); build.barricade(tdoor); build.barricade(twin)
	var bar_amount := float(tdoor.barricade)
	var n_pieces: int = build.list.size()
	var nav_built := nav_sum(); var boxes_built: int = W._boxes.size(); var floors_built = W.floors.size()
	var wall_cell := Vector2(float(wall.cx) - 0.5, float(wall.cz) - 0.5)
	var door_tile := Vector3i(int(door.tx), int(door.tz), int(door.axis))
	game.save_game(false)
	ok("saved", game.has_save())
	game.continue_game()
	await frames(4)
	p = game.player
	ok("load: every piece is back, once", build.list.size() == n_pieces and W._boxes.size() == boxes_built and W.floors.size() == floors_built, "%d pieces, boxes %d/%d, floors %d/%d" % [build.list.size(), W._boxes.size(), boxes_built, W.floors.size(), floors_built])
	ok("load: the nav grid is the same as before saving", nav_sum() == nav_built and W.nav_at(wall_cell.x, wall_cell.y) == 25)
	var door2 = build.edge_piece(door_tile.x, door_tile.y, door_tile.z)
	ok("load: the open door is open", door2 != null and door2.open and not door2.door_box.solid and absf(float(door2.angle) - PI / 2.0) < 0.01)
	var wall2 = build.edge_piece(int(wall.tx), int(wall.tz), int(wall.axis))
	ok("load: damage is remembered", wall2 != null and absf(float(wall2.hp) - 123.0) < 0.01)
	var crate2 = build.station_near(Vector3(float(crate.cx), float(crate.y), float(crate.cz)), 1.0, "crate")
	ok("load: the crate still holds the beans", crate2 != null and crate2.inv.count("canned_beans") == 3)
	var fire2 = build.station_near(Vector3(float(fire.cx), float(fire.y), float(fire.cz)), 1.0, "campfire")
	await frames(3)
	ok("load: the fire still burns, with its fuel and its light", fire2 != null and fire2.fire.lit and float(fire2.fire.fuel) > 190.0 and cook.fires.size() == 1 and cook.fires[0].light.visible)
	ok("load: the stairs can be climbed", W.ground_y(float(stairs.cx) + 0.95, float(stairs.cz), float(stairs.y) + 3.0) > float(stairs.y) + 3.0)
	ok("load: the door's boards and its own strength", absf(float(tdoor.get("barricade", 0.0)) - bar_amount) < 0.01 and float(tdoor.get("hp_real", 0.0)) == door_hp and tdoor.get("bar_node") != null and is_instance_valid(tdoor.bar_node) and tdoor.box.solid, "boards %.0f/%.0f, door %.0f/%.0f" % [float(tdoor.get("barricade", 0.0)), bar_amount, float(tdoor.get("hp_real", 0.0)), door_hp])
	ok("load: the window's boards", float(twin.get("barricade", 0.0)) > 0.0 and twin.box.solid and W.nav_at(float(twin.data.cx), float(twin.data.cz)) == 45)
	# a new game starts clean
	game.new_game("police")
	await frames(4)
	p = game.player
	ok("a new game clears everything built and boarded", build.list.is_empty() and cook.fires.is_empty() and float(tdoor.get("barricade", 0.0)) <= 0.0 and float(tdoor.hp) == door_hp and W._boxes.size() == boxes_empty and W.floors.size() == floors_empty,
		"%d pieces, boxes %d/%d, door hp %.0f" % [build.list.size(), W._boxes.size(), boxes_empty, float(tdoor.hp)])
	ok("and the nav grid under the camp is as it was", W.nav_at(wall_cell.x, wall_cell.y) == 0)
	game.zombies.clear()
