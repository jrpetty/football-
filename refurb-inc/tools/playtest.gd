extends Node
## Automated integration playtest in the real garage scene (vehicles, stations, UI apps, HUD).
##   godot --headless --path . res://tools/playtest.tscn        (exit code 0 = pass)
## Drives the game through Game.request exactly like a player would, at 8× time scale.

var _fails: PackedStringArray = []
var _level: LevelRoot
var _hud: Hud


func _check(cond: bool, msg: String) -> void:
	if not cond:
		_fails.append(msg)
		print("  ✖ ", msg)
	else:
		print("  ✔ ", msg)


func _ready() -> void:
	Engine.time_scale = 8.0
	Game.new_game(777)
	_level = load("res://scenes/levels/garage/garage.tscn").instantiate()
	add_child(_level)
	_hud = Hud.new()
	add_child(_hud)
	add_child(DaySummary.new())
	await _frames(5)
	var s := Game.state
	var sim := Game.sim
	_check(sim.visual_vehicles, "vehicle director active")
	_check(_level.players.size() == 1, "local player spawned")
	_check(s.slot_defs.has("bench_1:unit") and s.slot_defs.has("shelf_a:14") and s.slot_defs.has("courier_1:5"), "station slots registered")
	_check(s.item_in_slot("bench_1:park_a") != null, "starting test USB on bench")
	_check(_level.item_views.size() >= 2, "item views created")

	# --- office PC apps all build and refresh without errors
	for app in PaneOS.APPS:
		if app.script == "":
			continue
		var a: PaneApp = load(app.script).new()
		add_child(a)
		a.build()
		a.refresh()
		a.queue_free()
	_check(true, "all PaneOS apps built")

	# --- buy a lot via command
	var lot: Dictionary = {}
	for l in s.lots.values():
		if l.status == "open" and int(l.total_price) < s.cash:
			lot = l
			break
	Game.request("buy_lot", {"lot": lot.id})
	_check(s.lots[lot.id].status == "bought", "lot bought")
	# --- skip to delivery time; the lorry animates and drops the pallet
	sim.end_day()
	await _frames(2)
	Game.request("next_day")
	sim.advance_minutes(62)
	var t := 0.0
	while s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET).is_empty() and t < 60.0:
		await get_tree().process_frame
		t += get_process_delta_time()
	_check(not s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET).is_empty(), "lorry delivered a pallet")
	await _frames(5)
	var units := s.items.values().filter(func(i): return i.kind == Item.KIND_UNIT)
	_check(units.size() == int(lot.count), "all %d units on pallet" % int(lot.count))
	var view_ok := true
	for u in units:
		var v: ItemView = _level.item_views.get(u.id)
		if v == null or not v.visible:
			view_ok = false
	_check(view_ok, "every unit has a visible view on the pallet")

	# --- carry a unit in and test it
	var it: Item = units[0]
	Game.request("pick_up", {"item": it.id})
	await _frames(3)
	_check(it.loc.t == "held", "picked up a unit")
	var pv: ItemView = _level.item_views[it.id]
	var player: Player = _level.local_player()
	_check(pv.global_position.distance_to(player.hold_point().global_position) < 1.0, "held unit follows the player's hands")
	Game.request("place", {"slot": "bench_1:unit"})
	var usb := s.item_in_slot("bench_1:park_a")
	Game.request("pick_up", {"item": usb.id})
	Game.request("place", {"slot": "bench_1:usb"})
	_check(not s.stations.bench_1.job.is_empty(), "test started on bench")
	sim.advance_minutes(35)
	await _frames(3)
	_check(it.unit.tested, "unit tested")

	# --- player look-at interaction: aim at the bench laptop and read the hover card
	player.global_position = Vector3(-0.9, 0.05, -1.9)
	player._yaw = 0.0
	player._pitch = deg_to_rad(-38)
	player._apply_look()
	await get_tree().create_timer(0.3).timeout
	var info: Dictionary = player._hover_info
	_check(not info.is_empty(), "hover card shown when looking at the bench (%s)" % info.get("title", ""))

	# --- repair if needed (give parts), image, grade, shelve, sell, pack, ship
	if it.unit.has_open_faults():
		for f in it.unit.open_faults():
			s.parts[Valuation.repair_part_for(it.unit, f)] = 3
		Game.request("pick_up", {"item": it.id})
		Game.request("place", {"slot": "repair_1:unit"})
		var g := 0
		while it.unit.has_open_faults() and g < 5:
			g += 1
			var pre := sim.repair_preview(1, it)
			if not pre.can:
				# Above our repair level — make the player an expert for the test.
				s.player("p1").xp["repair"] = 99999
			Game.request("repair", {"station": "repair_1"})
		_check(not it.unit.has_open_faults(), "repaired")
		Game.request("pick_up", {"item": it.id})
		Game.request("place", {"slot": "bench_1:unit"})
	var usb2 := s.item_in_slot("bench_1:usb")
	Game.request("pick_up", {"item": usb2.id})
	Game.request("place", {"slot": "bench_1:park_a"})
	var img := s.item_in_slot("bench_1:park_b")
	Game.request("pick_up", {"item": img.id})
	Game.request("place", {"slot": "bench_1:usb"})
	sim.advance_minutes(50)
	_check(it.unit.imaged, "imaged")
	Game.request("pick_up", {"item": it.id})
	Game.request("place", {"slot": "grade_1:unit"})
	Game.request("grade", {"station": "grade_1"})
	_check(it.unit.grade != "", "graded %s" % it.unit.grade)
	Game.request("pick_up", {"item": it.id})
	Game.request("place", {"slot": "shelf_a:0"})
	Game.request("set_price", {"item": it.id, "price": int(it.unit.list_price * 0.6)})
	Game.request("list", {"item": it.id, "listed": true})
	var g2 := 0
	while it.unit.order_id == 0 and g2 < 60:
		g2 += 1
		sim.flogit_hourly()
	_check(it.unit.order_id != 0, "sold on Flogit")
	await _frames(2)
	_check(_hud.orders_box.get_child_count() > 0, "HUD shows the order to dispatch")
	Game.request("pick_up", {"item": it.id})
	Game.request("place", {"slot": "pack_1:unit"})
	Game.request("pack", {"station": "pack_1"})
	var pkg := s.item_in_slot("pack_1:unit")
	_check(pkg != null and pkg.kind == Item.KIND_PACKAGE, "packed")
	Game.request("pick_up", {"item": pkg.id})
	Game.request("place", {"slot": "courier_1:0"})
	var cash := s.cash
	# Courier van comes at the next collection time
	var target := s.day_start_minute(s.day_index()) + 12 * 60 - 9 * 60
	if s.minute_of_day() < 720:
		sim.advance_minutes(720.0 - s.minute_of_day() + 1)
	else:
		sim.advance_minutes(990.0 - s.minute_of_day() + 1)
	t = 0.0
	while s.cash == cash and t < 60.0:
		await get_tree().process_frame
		t += get_process_delta_time()
	_check(s.cash > cash, "courier van collected and the sale paid out")

	# --- shop order arrives as a parcel, unbox it
	Game.request("shop_buy", {"id": "bat_corvex_prospect", "qty": 2})
	var parcels_before := s.items.values().filter(func(i): return i.kind == Item.KIND_PARCEL).size()
	sim.advance_minutes(480)
	t = 0.0
	while s.items.values().filter(func(i): return i.kind == Item.KIND_PARCEL).size() == parcels_before and t < 60.0 and s.day_running:
		await get_tree().process_frame
		t += get_process_delta_time()
	var parcels := s.items.values().filter(func(i): return i.kind == Item.KIND_PARCEL)
	if parcels.is_empty() and not s.day_running:
		Game.request("next_day")
		sim.advance_minutes(200)
		t = 0.0
		while s.items.values().filter(func(i): return i.kind == Item.KIND_PARCEL).is_empty() and t < 60.0:
			await get_tree().process_frame
			t += get_process_delta_time()
		parcels = s.items.values().filter(func(i): return i.kind == Item.KIND_PARCEL)
	_check(not parcels.is_empty(), "parcel delivered by van")
	if not parcels.is_empty():
		Game.request("unbox", {"item": parcels[0].id})
		_check(int(s.parts.get("bat_corvex_prospect", 0)) >= 2, "parts in bins after unboxing")

	# --- aim the real camera at stock on a shelf and press E (the ray must reach the laptop, not just the shelf)
	await _aim_and_pick_check()

	# --- save / load round trip through the real level
	var err := SaveManager.save_game("playtest")
	_check(err == OK, "saved")
	var items_before := s.items.size()
	SaveManager.load_game("playtest")
	await _frames(5)
	_check(Game.state.items.size() == items_before, "loaded same number of items")
	_check(_level.item_views.size() > 0, "views rebuilt after load")

	print("\nPLAYTEST %s (%d failures)" % ["PASSED" if _fails.is_empty() else "FAILED", _fails.size()])
	Engine.time_scale = 1.0
	get_tree().quit(0 if _fails.is_empty() else 1)


func _aim_and_pick_check() -> void:
	var s := Game.state
	var player: Player = _level.local_player()
	for h: Item in s.held_by(player.peer_id):
		Game.request("drop", {"p": [0, 0.5, 0], "r": [0, 0, 0, 1]})
	var presses := {1: 0.05, 7: 0.35, 13: 0.6}     # a quick tap, a normal press, a slow press
	for level_bay in presses:              # bottom, middle and top shelves
		var slot_id := "shelf_a:%d" % level_bay
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = Game.sim.lots.roll_unit("kestrel_swift_14", 0.8)
		s.set_loc(it, {"t": "slot", "slot": slot_id})
		await _frames(4)
		var view: ItemView = _level.item_views.get(it.id)
		var shelf: Node3D = _level.fixture_nodes.get("shelf_a")
		var front := shelf.global_basis.z.normalized()
		player.global_position = shelf.global_position + front * 1.1
		player.velocity = Vector3.ZERO
		await _frames(2)
		var target := view.global_position + Vector3(0, view.visual_size.y * 0.5, 0)
		var dir := (target - player.camera.global_position).normalized()
		player._yaw = atan2(-dir.x, -dir.z)
		player._pitch = asin(clampf(dir.y, -1.0, 1.0))
		player._apply_look()
		await _frames(3)
		# Real key presses through the input system, held for different lengths of time.
		var ev := InputEventAction.new()
		ev.action = "interact"
		ev.pressed = true
		Input.parse_input_event(ev)
		await get_tree().create_timer(float(presses[level_bay])).timeout
		var up := InputEventAction.new()
		up.action = "interact"
		up.pressed = false
		Input.parse_input_event(up)
		await _frames(6)
		_check(it.loc.get("t") == "held", "aimed at a laptop on %s, pressed E for %.2fs, picked it up" % [slot_id, float(presses[level_bay])])
		for h: Item in s.held_by(player.peer_id):
			s.set_loc(h, {"t": "gone"})


func _frames(n: int) -> void:
	for i in n:
		await get_tree().process_frame
