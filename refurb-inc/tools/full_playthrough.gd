extends Node
## Full start-to-finish playthrough of the real game, the way a player does it: title screen → new game →
## office PC apps (clicking their buttons) → lorry → carry, test, repair, image, grade, shelve, list, sell,
## pack, courier → parcels, kettle, roller door, moving equipment → desktops → save/quit/continue →
## every premises. Keys are pressed through the input system; app buttons are clicked.
## Writes screenshots to tools/out/full/ and prints a ✔/✖ list.
##   xvfb-run -a -s "-screen 0 1600x900x24" godot --path . --rendering-driver vulkan res://tools/full_playthrough.tscn

const OUT := "res://tools/out/full/"
const EXT_POSES := {
	"garage": [Vector3(1.8, 1.9, 14.6), Vector3(-0.6, 1.2, 2.0)], "lockup": [Vector3(7.5, 1.9, 19.5), Vector3(-1.0, 2.2, 6.0)],
	"industrial": [Vector3(14.0, 2.4, 30.0), Vector3(0.0, 3.0, 9.0)], "warehouse": [Vector3(7.0, 1.9, 58.0), Vector3(-4.0, 3.8, 24.0)],
	"distribution": [Vector3(55.0, 4.0, 98.0), Vector3(0.0, 6.0, 40.0)], "facility": [Vector3(-26.0, 2.0, 80.0), Vector3(-7.0, 7.0, 44.0)],
	"megaplant": [Vector3(78.0, 5.0, 168.0), Vector3(25.0, 12.0, 100.0)],
}

var _results: Array = []
var _shot_n := 0
var _lr: LevelRoot
var _player: Player


func _ok(cond: bool, area: String, msg: String) -> bool:
	_results.append([cond, area, msg])
	print(("  ✔ " if cond else "  ✖ ") + "[%s] %s" % [area, msg])
	return cond


func _note(area: String, msg: String) -> void:
	_results.append([null, area, msg])
	print("  • [%s] %s" % [area, msg])


func _ready() -> void:
	DirAccess.make_dir_recursive_absolute(OUT)
	for f in DirAccess.get_files_at(OUT):
		DirAccess.remove_absolute(OUT + f)
	var big := "--hd" in OS.get_cmdline_user_args()
	get_window().size = Vector2i(1600, 900) if big else Vector2i(1024, 576)
	if not big:
		Settings.values["graphics_preset"] = "low"      # software-rendered test boxes; pass -- --hd for full quality
	_backup_saves(true)        # this run saves and continues; keep the developer's own saves safe
	await _title_and_new_game()
	await _movement()
	await _office_pc_day1()
	await _first_delivery()
	await _laptop_loop()
	await _sell_and_ship()
	await _parcel_kettle_door()
	await _move_equipment()
	await _desktops()
	await _fix_checks()
	await _save_quit_continue()
	await _every_building()
	_report()


# ================================================================= helpers

func _wait(s: float) -> void:
	await get_tree().create_timer(s).timeout


func _frames(n: int) -> void:
	for i in n:
		await get_tree().process_frame


func _shot(name: String) -> void:
	await _frames(4)
	await RenderingServer.frame_post_draw
	get_viewport().get_texture().get_image().save_png(OUT + "%02d_%s.png" % [_shot_n, name])
	_shot_n += 1


func _act(action: String, secs: float = 0.12) -> void:
	var ev := InputEventAction.new()
	ev.action = action
	ev.pressed = true
	Input.parse_input_event(ev)
	Input.action_press(action)
	await _wait(secs)
	var up := InputEventAction.new()
	up.action = action
	up.pressed = false
	Input.parse_input_event(up)
	Input.action_release(action)
	await _frames(5)


func _buttons(root: Node, text: String) -> Array:
	var out: Array = []
	if root is Button and (root as Button).text.strip_edges().begins_with(text) and (root as Button).is_visible_in_tree():
		out.append(root)
	for c in root.get_children():
		out.append_array(_buttons(c, text))
	return out


func _click(root: Node, text: String) -> bool:
	for b: Button in _buttons(root, text):
		if not b.disabled:
			b.pressed.emit()
			await _frames(4)
			return true
	return false


func _labels(root: Node, text: String) -> Array:
	var out: Array = []
	if root is Label and (root as Label).text.contains(text) and (root as Label).is_visible_in_tree():
		out.append(root)
	for c in root.get_children():
		out.append_array(_labels(c, text))
	return out


func _aim(target: Vector3) -> void:
	var dir := (target - _player.camera.global_position).normalized()
	_player._yaw = atan2(-dir.x, -dir.z)
	_player._pitch = asin(clampf(dir.y, -1.0, 1.0))
	_player._apply_look()
	await _frames(4)


func _stand(node: Node3D, dist: float, side := 0.0) -> void:
	## Put the player in front of a station (its +Z is its front), on the floor.
	var front := node.global_basis.z
	front.y = 0
	front = front.normalized()
	var right := front.cross(Vector3.UP)
	var p := node.global_position + front * dist + right * side
	_player.global_position = Vector3(p.x, node.global_position.y + 0.05, p.z)
	_player.velocity = Vector3.ZERO
	for i in 6:
		await get_tree().physics_frame


func _view(it: Item) -> ItemView:
	return _lr.item_views.get(it.id) if _lr else null


func _centre(v: ItemView) -> Vector3:
	return v.global_position + Vector3(0, v.visual_size.y * 0.5, 0)


func _pick(it: Item, press := 0.15) -> bool:
	var v := _view(it)
	if v == null:
		return false
	await _aim(_centre(v))
	await _act("interact", press)
	return it.loc.get("t") == "held"


func _place(slot_id: String, it: Item) -> bool:
	var s: SnapSlot = _lr.slot_nodes.get(slot_id)
	if s == null:
		return false
	await _aim(s.global_position + Vector3(0, 0.03, 0))
	await _act("interact", 0.12)
	return it.loc.get("t") == "slot"


func _hold_on(target: Vector3, secs: float) -> void:
	await _aim(target)
	await _act("interact", secs)


func _until(cond: Callable, timeout: float) -> bool:
	var t := 0.0
	while not cond.call() and t < timeout:
		await get_tree().process_frame
		t += get_process_delta_time()
	return cond.call()


func _fixture(id: String) -> Node3D:
	return _lr.fixture_nodes.get(id)


func _first_fixture(type: String) -> String:
	var ids: Array = Game.state.fixtures.keys()
	ids.sort()
	for id in ids:
		if Game.state.fixtures[id].type == type:
			return id
	return ""


func _pc() -> OfficePC:
	return _lr.find_child("OfficePC", true, false) as OfficePC


func _use_pc() -> PaneOS:
	var pc := _pc()
	var desk: Node3D = _fixture(_first_fixture("office_desk"))
	await _stand(desk, 1.0)
	await _aim(pc.global_position + Vector3(0, 0.25, 0))
	await _act("interact", 0.12)
	await _wait(1.4)
	return pc._os if _player.ui_mode else null


func _leave_pc() -> void:
	var ev := InputEventKey.new()
	ev.physical_keycode = KEY_ESCAPE
	ev.keycode = KEY_ESCAPE
	ev.pressed = true
	Input.parse_input_event(ev)
	await _frames(2)
	ev = ev.duplicate()
	ev.pressed = false
	Input.parse_input_event(ev)
	await _wait(1.2)


func _game_root() -> Node:
	return get_child(0)


# ================================================================= 1. title → new game

func _title_and_new_game() -> void:
	print("\n== Title screen & new game")
	var main: Node = load("res://scenes/main.tscn").instantiate()
	add_child(main)
	await _wait(1.5)
	await _shot("title")
	_ok(not _labels(main, "B4").is_empty() or not _labels(main, "Build").is_empty(), "menu", "build stamp visible on the title screen")
	_ok(await _click(main, "Settings"), "menu", "Settings opens")
	await _shot("settings")
	_ok(await _click(main, "Back"), "menu", "Settings closes with Back")
	_ok(await _click(main, "New game"), "menu", "New game opens the name-your-business box")
	await _wait(0.5)
	for le in main.find_children("*", "LineEdit", true, false):
		if (le as LineEdit).is_visible_in_tree():
			(le as LineEdit).text = "Playtest Trading Co"
	await _shot("name_business")
	_ok(await _click(main, "Start trading"), "menu", "Start trading")
	await _wait(2.5)
	_lr = LevelRoot.current
	_player = _lr.local_player() if _lr else null
	_ok(_player != null, "start", "player spawned in the garage")
	var s := Game.state
	_ok(s.day_index() == 0 and s.clock_text() == "09:00" or s.clock_text() == "09:01", "start", "starts Day 1 09:00 (%s)" % s.clock_text())
	_ok(s.cash == int(Content.econ("start_cash", 2500)), "start", "starting cash £%d" % s.cash)
	_ok(s.company_name == "Playtest Trading Co", "start", "company named \"%s\"" % s.company_name)
	await _shot("hud_day1")


# ================================================================= 2. movement

func _top_speed(sprint: bool) -> float:
	_player._yaw = PI if sprint else 0.0      # turn round so we don't walk into the same wall
	_player._apply_look()
	if sprint:
		Input.action_press("sprint")
	Input.action_press("move_forward")
	var top := 0.0
	for i in 40:
		await get_tree().physics_frame
		top = maxf(top, Vector2(_player.velocity.x, _player.velocity.z).length())
	Input.action_release("move_forward")
	Input.action_release("sprint")
	for i in 20:
		await get_tree().physics_frame
	return top


func _movement() -> void:
	print("\n== Movement")
	var start := _player.global_position
	_player._yaw = 0.0
	_player._pitch = 0.0
	_player._apply_look()
	await _act("move_forward", 0.8)
	var d := start.distance_to(_player.global_position)
	_ok(d > 0.4, "move", "W moves the player (%.2f m)" % d)
	await _act("move_back", 0.8)
	await _wait(0.3)
	# Compare top speeds over a fixed number of physics frames (real seconds mean little at 1–2 fps software GL).
	var walk_v := await _top_speed(false)
	var sprint_v := await _top_speed(true)
	_ok(sprint_v > walk_v * 1.15, "move", "Shift sprints (%.2f m/s vs %.2f m/s walking)" % [sprint_v, walk_v])
	await _wait(0.4)
	var y0 := _player.global_position.y
	var peak := y0
	var ev := InputEventAction.new()
	ev.action = "jump"
	ev.pressed = true
	Input.parse_input_event(ev)
	for i in 40:
		await get_tree().physics_frame
		peak = maxf(peak, _player.global_position.y)
	var up := InputEventAction.new()
	up.action = "jump"
	up.pressed = false
	Input.parse_input_event(up)
	_ok(peak > y0 + 0.2, "move", "Space jumps (%.2f m high)" % (peak - y0))
	await _wait(1.0)
	_ok(_player.global_position.y > -0.5, "move", "landed on the floor, not fallen through (y=%.2f)" % _player.global_position.y)


# ================================================================= 3. office PC (day 1)

func _office_pc_day1() -> void:
	print("\n== Office PC")
	var os := await _use_pc()
	if not _ok(os != null, "pc", "looking at the PC and pressing E sits you at it"):
		return
	await _shot("pc_desktop")
	for a in PaneOS.APPS:
		os.open_app(a.id)
		await _wait(0.5)
		await _shot("app_" + a.id)
	_ok(true, "pc", "all %d apps open without crashing" % PaneOS.APPS.size())
	# Buy a lot through LotFinder's buttons.
	os.open_app("purchasing")
	await _wait(0.4)
	var bought_before := Game.state.lots.values().filter(func(l): return l.status == "bought").size()
	var clicked := await _click(os, "Buy lot")
	await _wait(0.3)
	var confirmed := await _click(os, "Confirm purchase")
	await _wait(0.3)
	var bought := Game.state.lots.values().filter(func(l): return l.status == "bought").size() > bought_before
	_ok(clicked and confirmed and bought, "pc", "bought a lot with LotFinder's Buy lot → Confirm buttons")
	await _shot("app_purchasing_after_buy")
	# Order supplies through PartsHub.
	os.open_app("shop")
	await _wait(0.4)
	var parcels_before := Game.state.schedule.filter(func(e): return e.type == "parcel").size()
	var ordered := await _click(os, "Order")
	_ok(ordered and Game.state.schedule.filter(func(e): return e.type == "parcel").size() > parcels_before, "pc", "ordered from PartsHub (parcel booked)")
	await _leave_pc()
	_ok(not _player.ui_mode, "pc", "Esc leaves the PC")


# ================================================================= 4. end of day → lorry

func _first_delivery() -> void:
	print("\n== End of day & delivery")
	var s := Game.state
	var sim := Game.sim
	# Day one: a lot bought on the first day arrives the same day.
	var pallets0 := s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET).size()
	_player.global_position = Vector3(0.6, 0.05, 3.2)
	await _aim(Vector3(0.0, 1.0, 9.0))
	var seen := false
	for i in 30:
		if s.items.values().filter(func(it): return it.kind == Item.KIND_PALLET).size() > pallets0:
			break
		sim.advance_minutes(5)
		await _wait(2.0)
		if not seen and i == 6:
			await _shot("lorry_day1")
			seen = true
	await _until(func(): return s.items.values().filter(func(it): return it.kind == Item.KIND_PALLET).size() > pallets0, 120.0)
	_ok(s.items.values().filter(func(it): return it.kind == Item.KIND_PALLET).size() > pallets0, "delivery", "a lot bought on day one arrives the same day (%s)" % s.clock_text())
	await _wait(25.0)      # let the driver and lorry clear off
	await _shot("pallet_delivered")
	sim.advance_minutes(600)
	await _wait(1.0)
	_ok(not s.day_running, "day", "the day ends at 17:00")
	await _shot("day_summary")
	_ok(await _click(_game_root(), "Start next day"), "day", "Start next day button works")
	await _wait(0.5)
	_ok(s.day_running and s.clock_text() == "09:00", "day", "next morning starts at 09:00 (%s %s)" % [s.weekday_name(), s.clock_text()])
	var units := s.items.values().filter(func(i): return i.kind == Item.KIND_UNIT)
	_ok(not units.is_empty(), "delivery", "%d units on the pallet" % units.size())
	var outside := 0
	for p: Item in s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET):
		var v := _view(p)
		if v and v.global_position.y < -0.3:
			outside += 1
	_ok(outside == 0, "delivery", "pallets sit on the ground (none fell through)")


# ================================================================= 5. laptop loop with real input

func _laptop_loop() -> void:
	print("\n== Full refurb loop (real input)")
	var s := Game.state
	var sim := Game.sim
	var pallet: Item = s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET)[0]
	var pv := _view(pallet)
	var units: Array = s.items.values().filter(func(i): return i.kind == Item.KIND_UNIT and String(i.loc.get("slot", "")).begins_with("i%d:" % pallet.id))
	if not _ok(not units.is_empty(), "loop", "units to work on"):
		return
	# Stand by the pallet on the side away from the hand pallet truck (its handle would be in the way) and pick
	# up three, one press each.
	if _player.my_truck():
		await _act("drop", 0.12)
	var truck_p := Vector3(1e6, 0, 1e6)
	for t: Item in s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET_TRUCK):
		var tv := _view(t)
		if tv and tv.global_position.distance_to(pv.global_position) < truck_p.distance_to(pv.global_position):
			truck_p = tv.global_position
	var towards := Vector3(-pv.global_position.x, 0, -pv.global_position.z).normalized()
	var best := -1.0
	for k in 4:
		var d := towards.rotated(Vector3.UP, k * PI / 2)
		var spot := pv.global_position + d * 1.3
		var score := spot.distance_to(truck_p) - (0.0 if k == 0 else 0.4)     # prefer the garage side
		if score > best:
			best = score
			_player.global_position = spot + Vector3(0, 0.05, 0)
	for i in 6:
		await get_tree().physics_frame
	var picked := 0
	for i in mini(3, units.size()):
		if await _pick(units[units.size() - 1 - i]):
			picked += 1
	_ok(picked == mini(3, units.size()), "carry", "picked up %d laptops from the pallet with E (stack)" % picked)
	await _shot("carrying_stack")
	# Bench + two on a shelf.
	var bench := _first_fixture("test_bench")
	await _stand(_fixture(bench), 0.9)
	var held := s.held_by(_player.peer_id)
	var target: Item = held.back()
	_ok(await _place("%s:unit" % bench, target), "bench", "placed a laptop on the test bench")
	var shelf_id := _first_fixture("metal_shelf")
	await _stand(_fixture(shelf_id), 1.0)
	var shelved := 0
	for k in [4, 5]:
		var top: Item = s.held_by(_player.peer_id).back() if not s.held_by(_player.peer_id).is_empty() else null
		if top and await _place("%s:%d" % [shelf_id, k], top):
			shelved += 1
	_ok(shelved == 2, "shelf", "put the other two on the shelf")
	# Plug in the diagnostics USB.
	await _stand(_fixture(bench), 0.9)
	var usb := s.item_in_slot("%s:park_a" % bench)
	if usb == null or usb.kind != Item.KIND_USB_TEST:
		usb = s.item_in_slot("%s:park_b" % bench)
	_ok(usb != null and await _pick(usb), "bench", "picked up the DiagnostiKit USB")
	_ok(await _place("%s:usb" % bench, usb), "bench", "plugged it into the bench")
	_ok(not s.stations[bench].job.is_empty(), "bench", "the test starts by itself")
	sim.advance_minutes(8)
	await _wait(0.8)
	await _aim(_centre(_view(target)))
	await _shot("bench_testing")
	sim.advance_minutes(30)
	await _wait(0.5)
	_ok(target.unit.tested, "bench", "test finished: %s" % ("PASS" if not target.unit.has_open_faults() else ", ".join(target.unit.test_report.get("faults", []))))
	await _shot("bench_result")
	# Repair if needed.
	if target.unit.has_open_faults():
		for f in target.unit.open_faults():
			var pid := Valuation.repair_part_for(target.unit, f)
			if pid != "none" and int(s.parts.get(pid, 0)) <= 0:
				s.parts[pid] = 1
				_note("repair", "gave 1× %s for the test (you'd order it in PartsHub)" % Content.part(pid).get("name", pid))
			if int(Content.fault(f.id).get("repair_level", 1)) > s.skill_level(Sim.player_key(_player.peer_id), "repair"):
				s.players[Sim.player_key(_player.peer_id)].xp["repair"] = 99999
				_note("repair", "raised Repair skill to fix a level-%d fault" % int(Content.fault(f.id).repair_level))
		_ok(await _pick(target), "repair", "took the faulty unit off the bench")
		var rep := _first_fixture("repair_bench")
		await _stand(_fixture(rep), 0.9)
		_ok(await _place("%s:unit" % rep, target), "repair", "onto the repair bench")
		var guard := 0
		while target.unit.has_open_faults() and guard < 4:
			guard += 1
			var pv2 := sim.repair_preview(_player.peer_id, target)
			await _hold_on(_centre(_view(target)), float(pv2.get("secs", 4.0)) + 0.6)
		_ok(not target.unit.has_open_faults(), "repair", "held E until every fault was fixed")
		await _shot("repair_done")
		_ok(await _pick(target), "repair", "picked it back up")
		await _stand(_fixture(bench), 0.9)
		_ok(await _place("%s:unit" % bench, target), "bench", "back on the test bench")
	# Swap to the imaging USB.
	var test_usb := s.item_in_slot("%s:usb" % bench)
	if test_usb:
		await _pick(test_usb)
		var parked := await _place("%s:park_a" % bench, test_usb) or await _place("%s:park_b" % bench, test_usb)
		_ok(parked, "image", "unplugged the test USB and parked it")
	var img := s.item_in_slot("%s:park_b" % bench)
	if img == null or img.kind != Item.KIND_USB_IMAGE:
		img = s.item_in_slot("%s:park_a" % bench)
	_ok(img != null and img.kind == Item.KIND_USB_IMAGE and await _pick(img), "image", "picked up the PaneOS USB")
	_ok(await _place("%s:usb" % bench, img), "image", "plugged it in; imaging starts")
	sim.advance_minutes(50)
	await _wait(0.5)
	_ok(target.unit.imaged, "image", "wiped & imaged")
	# Grade.
	_ok(await _pick(target), "grade", "took it to grading")
	var gd := _first_fixture("grading_desk")
	await _stand(_fixture(gd), 0.9)
	_ok(await _place("%s:unit" % gd, target), "grade", "on the grading desk")
	await _hold_on(_centre(_view(target)), float(Content.econ("grade_secs", 1.5)) + 0.6)
	_ok(target.unit.grade != "", "grade", "held E: graded %s, suggested £%d" % [UnitData.grade_label(target.unit.grade), target.unit.list_price])
	await _shot("graded_sticker")
	_ok(await _pick(target), "grade", "picked up the graded unit")
	await _stand(_fixture(shelf_id), 1.0)
	_ok(await _place("%s:7" % shelf_id, target), "shelf", "shelved it")
	set_meta("target", target)


# ================================================================= 6. list, sell, pack, courier

func _sell_and_ship() -> void:
	print("\n== Selling & shipping")
	var s := Game.state
	var sim := Game.sim
	var target: Item = get_meta("target", null)
	if target == null or target.unit.grade == "":
		_ok(false, "sell", "no graded unit to sell")
		return
	var os := await _use_pc()
	if os:
		os.open_app("flogit")
		await _wait(0.4)
		await _shot("app_flogit_ready")
		if target.unit.listed:
			_ok(true, "sell", "grading listed it on Flogit automatically")
		else:
			var listed := await _click(os, "List")
			_ok(listed and target.unit.listed, "sell", "listed it with Flogit's List button")
		await _leave_pc()
	# Wait for a buyer (real demand, hour by hour).
	var hours := 0
	while target.unit.order_id == 0 and hours < 40:
		if not s.day_running:
			sim.start_next_day()
		sim.advance_minutes(60)
		hours += 1
	_ok(target.unit.order_id != 0, "sell", "a Flogit buyer ordered it after %d game hours at the suggested price" % hours)
	if target.unit.order_id == 0:
		return
	if not s.day_running:
		sim.start_next_day()
	await _wait(0.3)
	_ok(await _pick(target), "pack", "picked the sold unit off the shelf")
	var pk := _first_fixture("packing_table")
	await _stand(_fixture(pk), 0.9)
	_ok(await _place("%s:unit" % pk, target), "pack", "onto the packing table")
	await _hold_on(_centre(_view(target)), float(Content.econ("pack_secs", 4.0)) + 0.6)
	var pkg := s.item_in_slot("%s:unit" % pk)
	_ok(pkg != null and pkg.kind == Item.KIND_PACKAGE, "pack", "held E: boxed and labelled")
	await _shot("packed")
	if pkg == null:
		return
	_ok(await _pick(pkg), "pack", "picked up the parcel")
	var cs := _first_fixture("courier_shelf")
	await _stand(_fixture(cs), 0.9)
	_ok(await _place("%s:0" % cs, pkg), "courier", "put it on the OUTGOING shelf")
	var cash := s.cash
	var m := s.minute_of_day()
	var to := 720.0 - m if m < 720.0 else 990.0 - m
	if to < 0:
		sim.end_day()
		sim.start_next_day()
		to = 720.0 - s.minute_of_day()
	sim.advance_minutes(to - 2)
	_player.global_position = Vector3(0.6, 0.05, 3.2)
	await _aim(Vector3(0.0, 1.0, 9.0))
	sim.advance_minutes(3)
	await _wait(5.0)
	await _shot("courier_van")
	_ok(await _until(func(): return s.cash > cash, 80.0), "courier", "the courier van collected and the sale paid out (+%s)" % GameState.format_money(s.cash - cash))


# ================================================================= 7. parcel, kettle, door

func _parcel_kettle_door() -> void:
	print("\n== Parcels, kettle, roller door")
	var s := Game.state
	var sim := Game.sim
	var parcels: Array = s.items.values().filter(func(i): return i.kind == Item.KIND_PARCEL)
	if parcels.is_empty():
		for i in 12:
			if not s.day_running:
				sim.start_next_day()
			sim.advance_minutes(60)
			await _wait(0.4)
			parcels = s.items.values().filter(func(i): return i.kind == Item.KIND_PARCEL)
			if not parcels.is_empty():
				break
		await _until(func(): return not s.items.values().filter(func(i): return i.kind == Item.KIND_PARCEL).is_empty(), 60.0)
		parcels = s.items.values().filter(func(i): return i.kind == Item.KIND_PARCEL)
	if _ok(not parcels.is_empty(), "parcel", "the shop order arrived as a parcel"):
		var p: Item = parcels[0]
		var v := _view(p)
		_player.global_position = v.global_position + Vector3(0, 0.05, 1.2)
		for i in 6:
			await get_tree().physics_frame
		var cons := s.consumables.duplicate()
		var parts := s.parts.duplicate()
		await _hold_on(_centre(v), 1.8)
		_ok(not s.items.has(p.id), "parcel", "held E on the parcel to unbox it (consumables %s → %s)" % [str(cons), str(s.consumables)])
	var kettle: Node3D = _lr.find_child("KettleCorner", true, false)
	if _ok(kettle != null, "kettle", "kettle corner present"):
		await _stand(kettle, 0.9)
		var k: Node = kettle.find_child("*", true, false)
		var ki: SimpleInteractable = null
		for n in kettle.find_children("*", "StaticBody3D", true, false):
			if n is SimpleInteractable:
				ki = n
		if ki:
			await _aim(ki.global_position + Vector3(0, 0.1, 0))
			_note("kettle", "looking at the kettle shows: %s / %s" % [str(_player._hover_info.get("title", "-")), str(_player._hover_info.get("prompt", _player._hover_info.get("hold", {}).get("label", "-")))])
			await _shot("kitchen")
	var sw: SimpleInteractable = _lr.find_child("Switch_RollerDoor", true, false) as SimpleInteractable
	if _ok(sw != null, "door", "roller door switch present"):
		var door: Node = sw.target
		var was: bool = door.is_open() if door and door.has_method("is_open") else false
		_player.global_position = sw.global_position + sw.global_basis.z * 0.9 + Vector3(0, -sw.global_position.y + 0.05, 0)
		for i in 6:
			await get_tree().physics_frame
		await _aim(sw.global_position)
		await _act("interact", 0.12)
		await _wait(3.0)
		var now: bool = door.is_open() if door and door.has_method("is_open") else was
		_ok(now != was, "door", "pressing E on the switch %s the roller door" % ("opened" if now else "closed"))
		await _shot("roller_door")


# ================================================================= 8. move equipment

func _move_equipment() -> void:
	print("\n== Moving equipment (F)")
	var s := Game.state
	var ids: Array = s.fixtures.keys().filter(func(i): return s.fixtures[i].type == "metal_shelf")
	var fid: String = ids.back()
	var node := _fixture(fid)
	await _stand(node, 1.4)
	await _aim(node.global_position + Vector3(0, 1.0, 0))
	await _act("move_fixture", 0.1)
	_ok(_player.placing_id == fid, "build", "looking at %s and pressing F picks it up for moving" % s.fixtures[fid].label)
	var before: Array = s.fixtures[fid].p.duplicate()
	await _act("rotate", 0.1)
	await _act("rotate", 0.1)
	await _act("rotate", 0.1)
	await _act("rotate", 0.1)        # four quarter turns: back to how it was
	await _aim(node.global_position)
	await _shot("placing_ghost")
	await _act("interact", 0.1)
	await _wait(0.5)
	_ok(_player.placing_id == "", "build", "E puts it down on a clear spot")
	if _player.placing_id != "":
		await _act("drop", 0.1)        # Q cancels placement
		_ok(_player.placing_id == "", "build", "Q cancels moving equipment")
	_note("build", "shelf %s: %s → %s" % [fid, str(before), str(s.fixtures[fid].p)])


# ================================================================= 9. desktops

func _desktops() -> void:
	print("\n== Desktops & servers")
	var s := Game.state
	var sim := Game.sim
	s.cash += 50000
	s.reputation = maxf(s.reputation, 40.0)        # server sellers want a track record
	var kinds_seen := {}
	for seller_id in ["pennine_mutual", "kingsgate_uni", "ridgeway_hosting"]:
		if not Content.sellers.has(seller_id):
			continue
		var seller: Dictionary = Content.sellers[seller_id]
		for tries in 30:
			var lot := sim.lots.create_lot(seller)
			var big: Array = lot.units.filter(func(u): return Content.model(u.model_id).get("type", "laptop") in ["desktop", "server"])
			if not big.is_empty():
				var t: String = Content.model(big[0].model_id).type
				if not kinds_seen.has(t) or seller_id == "kingsgate_uni":
					kinds_seen[t] = lot.id
					sim.c_buy_lot(_player.peer_id, {"lot": lot.id})
					break
	_ok(not kinds_seen.is_empty(), "big kit", "bought lots with %s" % str(kinds_seen.keys()))
	var pallets_before := s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET).size()
	if s.day_running:
		sim.end_day()
	sim.start_next_day()
	for i in 12:
		sim.advance_minutes(15)
		await _wait(4.0)
	await _until(func(): return s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET).size() >= pallets_before + 2, 150.0)
	await _wait(20.0)
	var big_units: Array = s.items.values().filter(func(i): return i.kind == Item.KIND_UNIT and i.unit.hw_type() in ["desktop", "server"] and String(i.loc.get("slot", "")).begins_with("i"))
	_ok(not big_units.is_empty(), "big kit", "%d desktops/servers delivered on pallets" % big_units.size())
	var shot_pallets := {}
	for u: Item in big_units:
		var pid := int(String(u.loc.slot).substr(1).get_slice(":", 0))
		if shot_pallets.has(pid) or not s.items.has(pid):
			continue
		shot_pallets[pid] = true
		var v := _view(s.items[pid])
		var tw := Vector3(-v.global_position.x, 0, -v.global_position.z).normalized()
		_player.global_position = v.global_position + tw * 2.2 + Vector3(0, 0.05, 0)
		for i in 4:
			await get_tree().physics_frame
		await _aim(v.global_position + Vector3(0, 0.3, 0))
		await _shot("pallet_%s" % u.unit.hw_type())
	if big_units.is_empty():
		return
	var big: Item = null
	for u: Item in big_units:
		if big == null or int(u.unit.model().get("carry", 1)) > int(big.unit.model().get("carry", 1)):
			big = u
	var v2 := _view(big)
	var tw2 := Vector3(-v2.global_position.x, 0, -v2.global_position.z).normalized()
	_player.global_position = v2.global_position + tw2 * 1.3 + Vector3(0, 0.05, 0)
	for i in 6:
		await get_tree().physics_frame
	_ok(await _pick(big), "big kit", "picked up a %s (%s, carry %d)" % [big.unit.hw_type(), big.unit.display_name(), int(big.unit.model().get("carry", 1))])
	await _shot("carrying_%s" % big.unit.hw_type())
	if int(big.unit.model().get("carry", 1)) >= 3:
		var other: Item = null
		for u: Item in big_units:
			if u != big and u.loc.get("t") == "slot":
				other = u
		if other:
			_ok(not await _pick(other), "big kit", "can't carry a second one on top")
	var bench := _first_fixture("test_bench")
	await _stand(_fixture(bench), 0.9)
	var busy := s.item_in_slot("%s:unit" % bench)
	if busy:
		s.set_loc(busy, {"t": "loose", "p": [0, 0.5, 0], "r": [0, 0, 0, 1]})
	_ok(big.loc.get("t") == "held" and await _place("%s:unit" % bench, big) and big.loc.get("slot", "") == "%s:unit" % bench, "big kit", "the %s goes on the test bench" % big.unit.hw_type())
	await _shot("bench_%s" % big.unit.hw_type())
	_ok(await _pick(big), "big kit", "picked it up again")
	var rack := _first_fixture("heavy_rack")
	var shelf_id := rack if rack != "" else _first_fixture("metal_shelf")
	await _stand(_fixture(shelf_id), 1.2)
	var placed := false
	for k in 16:
		if s.item_in_slot("%s:%d" % [shelf_id, k]) == null and await _place("%s:%d" % [shelf_id, k], big):
			placed = String(big.loc.get("slot", "")).begins_with(shelf_id)
			if placed:
				break
	_ok(placed, "big kit", "the %s goes on the %s" % [big.unit.hw_type(), s.fixtures[shelf_id].label])
	if placed:
		await _aim(_centre(_view(big)))
		await _shot("stored_%s" % big.unit.hw_type())
	elif big.loc.get("t") == "held":
		await _act("drop", 0.1)


# ================================================================= 9b. release fixes

func _fix_checks() -> void:
	print("\n== Release fixes")
	var s := Game.state
	var os := await _use_pc()
	if os:
		os.open_app("shop")
		await _wait(0.3)
		var tb: TabBar = null
		for n in os.find_children("*", "TabBar", true, false):
			tb = n
		if tb:
			for i in tb.tab_count:
				if tb.get_tab_title(i).begins_with("Keyboards"):
					tb.current_tab = i
		await _wait(0.3)
		_ok(not _labels(os, "charge port").is_empty() and not _labels(os, "console cable").is_empty() and not _labels(os, "Monitor stand").is_empty(), "shop", "phone charge ports, console cables and monitor stands are on sale")
		await _shot("shop_small_parts")
		var cash := s.cash
		s.cash = -900
		os.open_app("shop")
		await _wait(0.3)
		var parcels := s.schedule.filter(func(e): return e.type == "parcel").size()
		var boxes_row := false
		for lab: Label in _labels(os, "shipping boxes"):
			var row: Node = lab
			while row and not (row is PanelContainer):
				row = row.get_parent()
			if row and await _click(row, "Order"):
				boxes_row = true
				break
		_ok(boxes_row and s.schedule.filter(func(e): return e.type == "parcel").size() >= parcels, "debt", "overdrawn (£-900) you can still order packing boxes")
		s.cash = cash
		await _leave_pc()
	var loose: Array = []
	for p: Item in s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET and i.loc.get("t") == "loose"):
		loose.append(Vector2(float(p.loc.p[0]), float(p.loc.p[2])))
	var overlap := 0
	for a in loose.size():
		for b in range(a + 1, loose.size()):
			if loose[a].distance_to(loose[b]) < 1.0:
				overlap += 1
	_ok(overlap == 0, "pallet", "%d pallets on site, none inside another" % loose.size())


# ================================================================= 10. save, quit, continue

func _save_quit_continue() -> void:
	print("\n== Save, quit to menu, continue")
	var cash := Game.state.cash
	var n := Game.state.items.size()
	var day := Game.state.day_index()
	await _act("pause", 0.1)
	await _wait(0.4)
	await _shot("pause_menu")
	var root := _game_root()
	_ok(await _click(root, "Save & quit to menu"), "save", "Pause → Save & quit to menu")
	await _wait(2.0)
	await _shot("title_with_continue")
	_ok(await _click(root, "Continue"), "save", "Continue appears on the title screen and works")
	await _wait(2.5)
	_lr = LevelRoot.current
	_player = _lr.local_player() if _lr else null
	_ok(_player != null, "save", "back in the game")
	_ok(Game.state.cash == cash and Game.state.items.size() == n, "save", "cash and every item restored (£%d, %d items)" % [Game.state.cash, Game.state.items.size()])
	_note("save", "saved on day %d, resumed on day %d (saves resume the next morning)" % [day + 1, Game.state.day_index() + 1])


# ================================================================= 11. every building

func _every_building() -> void:
	print("\n== Every premises")
	var s := Game.state
	s.cash = 5_000_000
	_note("premises", "cash set to £5,000,000 so every move is affordable")
	for bid in Content.building_order:
		if bid != s.building_id:
			var os := await _use_pc()
			if not _ok(os != null, bid, "sat at the office PC"):
				continue
			os.open_app("building")
			await _wait(0.5)
			var name: String = Content.building(bid).name
			var done := false
			for lab: Label in _labels(os, name):
				var card: Node = lab
				while card and not (card is PanelContainer):
					card = card.get_parent()
				if card and await _click(card, "Move here"):
					done = await _click(card, "Confirm move")
					break
			_ok(done, bid, "Building app → Move here → Confirm move")
			await _leave_pc()
			await _until(func(): return LevelRoot.current and LevelRoot.current.building_id == bid, 15.0)
			await _wait(2.5)
			_lr = LevelRoot.current
			_player = _lr.local_player()
		_ok(_lr.building_id == bid, bid, "in the %s" % Content.building(bid).name)
		# Interior from the player's eyes, a walk, and the exterior.
		var p := _player.global_position
		_ok(_lr.floor_rects.any(func(r): return r.has_point(Vector2(p.x, p.z))), bid, "player spawned inside on the floor")
		await _shot("%s_inside" % bid)
		var start := _player.global_position
		await _act("move_forward", 0.7)
		_ok(start.distance_to(_player.global_position) > 0.2 and _player.global_position.y > -0.5, bid, "can walk (%.2f m)" % start.distance_to(_player.global_position))
		var placed := s.fixtures.values().filter(func(f): return f.placed).size()
		_ok(placed == s.fixtures.size(), bid, "all %d pieces of equipment laid out" % s.fixtures.size())
		var units_visible := 0
		var units_total := 0
		for it: Item in s.items.values():
			if it.kind == Item.KIND_UNIT and it.loc.get("t") == "slot":
				units_total += 1
				var v := _view(it)
				if v and v.visible:
					units_visible += 1
		_ok(units_visible == units_total, bid, "all %d shelved/benched units came along and are visible" % units_total)
		var dc := Performance.get_monitor(Performance.RENDER_TOTAL_DRAW_CALLS_IN_FRAME)
		var prims := Performance.get_monitor(Performance.RENDER_TOTAL_PRIMITIVES_IN_FRAME)
		_note(bid, "inside view: %d draw calls, %d triangles" % [int(dc), int(prims)])
		var cam := Camera3D.new()
		cam.fov = 70
		_lr.add_child(cam)
		var pose: Array = EXT_POSES.get(bid, [Vector3(10, 5, 20), Vector3.ZERO])
		cam.global_position = pose[0]
		cam.look_at(pose[1])
		cam.current = true
		await _wait(0.6)
		await _shot("%s_outside" % bid)
		_note(bid, "outside view: %d draw calls, %d triangles" % [int(Performance.get_monitor(Performance.RENDER_TOTAL_DRAW_CALLS_IN_FRAME)), int(Performance.get_monitor(Performance.RENDER_TOTAL_PRIMITIVES_IN_FRAME))])
		cam.current = false
		cam.queue_free()
		_player.camera.current = true
		await _frames(3)


# ================================================================= report

func _backup_saves(stash: bool) -> void:
	for f in ["manual.json", "autosave.json", "manual.json.bak", "autosave.json.bak", "previous_manual.json", "previous_autosave.json"]:
		var real: String = SaveManager.DIR + f
		var kept: String = real + ".playthrough_backup"
		if stash and FileAccess.file_exists(real):
			DirAccess.rename_absolute(real, kept)
		elif not stash:
			if FileAccess.file_exists(real):
				DirAccess.remove_absolute(real)
			if FileAccess.file_exists(kept):
				DirAccess.rename_absolute(kept, real)


func _report() -> void:
	_backup_saves(false)
	var fails := _results.filter(func(r): return r[0] == false)
	var passes := _results.filter(func(r): return r[0] == true)
	print("\n================ FULL PLAYTHROUGH: %d passed, %d failed, %d screenshots ================" % [passes.size(), fails.size(), _shot_n])
	for r in fails:
		print("  FAILED [%s] %s" % [r[1], r[2]])
	get_tree().quit(0 if fails.is_empty() else 1)
