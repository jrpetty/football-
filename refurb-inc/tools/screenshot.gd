extends Node
## Renders the garage from a set of camera poses and saves PNGs to tools/out/ (for visual QA / Steam shots).
##   xvfb-run -s "-screen 0 1920x1080x24" godot --path . res://tools/screenshot.tscn -- [--poses name,name] [--setup demo]

## Per-premises hero poses: [camera position, look-at target]
const LEVEL_POSES := {
	"garage": {"ext": [Vector3(1.8, 1.9, 14.6), Vector3(-0.6, 1.2, 2.0)], "int": [Vector3(0.6, 1.62, 3.0), Vector3(-0.6, 1.0, -3.0)], "aerial": [Vector3(18, 14, 26), Vector3(-2, 0, 0)]},
	"lockup": {"ext": [Vector3(7.5, 1.9, 19.5), Vector3(-1.0, 2.2, 6.0)], "int": [Vector3(1.0, 1.65, 6.2), Vector3(-0.5, 1.0, -6.0)], "aerial": [Vector3(30, 22, 40), Vector3(-4, 0, 0)]},
	"industrial": {"ext": [Vector3(14.0, 2.4, 30.0), Vector3(0.0, 3.0, 9.0)], "int": [Vector3(3.0, 1.7, 8.5), Vector3(-2.0, 2.0, -8.0)], "aerial": [Vector3(40, 26, 52), Vector3(-4, 0, 0)]},
	"warehouse": {"ext": [Vector3(7.0, 1.9, 58.0), Vector3(-4.0, 3.8, 24.0)], "int": [Vector3(6.0, 1.7, 20.0), Vector3(-6.0, 3.0, -20.0)], "aerial": [Vector3(70, 45, 95), Vector3(0, 0, 10)]},
	"distribution": {"ext": [Vector3(55.0, 4.0, 98.0), Vector3(0.0, 6.0, 40.0)], "int": [Vector3(10.0, 1.7, 40.0), Vector3(-10.0, 4.0, -40.0)], "aerial": [Vector3(130, 80, 170), Vector3(0, 0, 20)]},
	"facility": {"ext": [Vector3(-26.0, 2.0, 80.0), Vector3(-7.0, 7.0, 44.0)], "int": [Vector3(8.0, 1.7, 26.0), Vector3(-6.0, 4.0, -30.0)], "aerial": [Vector3(100, 70, 140), Vector3(0, 0, 10)]},
	"megaplant": {"ext": [Vector3(78.0, 5.0, 168.0), Vector3(25.0, 12.0, 100.0)], "int": [Vector3(10.0, 1.7, 84.0), Vector3(-10.0, 6.0, -60.0)], "aerial": [Vector3(250, 160, 330), Vector3(0, 0, 30)]},
}

const POSES := {
	"door_in": [Vector3(0.6, 1.62, 3.0), Vector3(-0.6, 1.0, -3.0)],
	"benches": [Vector3(0.2, 1.55, -0.9), Vector3(0.0, 0.95, -3.2)],
	"outside": [Vector3(2.4, 1.7, 12.6), Vector3(-0.4, 1.0, 1.0)],
	"office": [Vector3(-1.2, 1.5, -1.4), Vector3(-2.9, 1.0, -2.6)],
	"right_wall": [Vector3(-0.8, 1.6, 0.4), Vector3(2.8, 0.9, -0.2)],
	"drive": [Vector3(-0.8, 1.6, 0.8), Vector3(0.6, 0.6, 8.0)],
	"kettle": [Vector3(-0.6, 1.55, 1.4), Vector3(-2.7, 0.95, 2.6)],
	"pc_screen": [Vector3(-2.15, 1.07, -2.69), Vector3(-2.9, 1.07, -2.69)],
}

var _cam: Camera3D
var _level: Node


func _ready() -> void:
	var args := OS.get_cmdline_user_args()
	var names: Array = POSES.keys()
	var idx := args.find("--poses")
	if idx >= 0 and idx + 1 < args.size():
		names = args[idx + 1].split(",")
	var w := 1600
	var h := 900
	get_window().size = Vector2i(w, h)
	Game.new_game(4242)
	var level_id := "garage"
	if args.find("--level") >= 0:
		level_id = args[args.find("--level") + 1]
		Game.state.building_id = level_id
		var poses: Dictionary = LEVEL_POSES.get(level_id, {})
		if idx < 0:
			names = poses.keys()
	if "--insights" in args:
		# A few weeks of real trading so the Insights app has history to show.
		var bot := BusinessBot.new(Game.sim, 4242)
		bot.run_days(20)
		bot.finish_for_level()
		Game.sim.start_next_day()
		Game.sim.advance_minutes(150)
	if "--bigkit" in args:
		Game.state.cash += 5000
		Game.sim.execute(1, "buy_fixture", {"type": "heavy_rack"})
	_level = load(load("res://scripts/game_root.gd").level_path(level_id)).instantiate()
	add_child(_level)
	if "--setup" in args:
		_demo_setup()
	if "--bigkit" in args:
		await _bigkit_shots()
		get_tree().quit()
		return
	await get_tree().create_timer(0.5).timeout
	if "--insights" in args:
		await _insights_shots()
		get_tree().quit()
		return
	if "--ui" in args:
		await _ui_shots()
		get_tree().quit()
		return
	# Remove the player camera influence
	_cam = Camera3D.new()
	_cam.fov = 70
	_level.add_child(_cam)
	_cam.current = true
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	var all_poses: Dictionary = POSES.duplicate()
	all_poses.merge(LEVEL_POSES.get(level_id, {}), true)
	for n in names:
		var p: Array = all_poses[n]
		_cam.global_position = p[0]
		_cam.look_at(p[1])
		for i in 12:
			await get_tree().process_frame
		await RenderingServer.frame_post_draw
		var img := get_viewport().get_texture().get_image()
		img.save_png("res://tools/out/%s_%s.png" % [level_id, n] if args.find("--level") >= 0 else "res://tools/out/%s.png" % n)
		print("saved ", n)
	get_tree().quit()


func _demo_setup() -> void:
	## Put the world into a "busy day" state for screenshots: a delivered pallet, units on benches and shelves.
	var sim := Game.sim
	var s := Game.state
	var lot: Dictionary = s.lots.values()[0]
	sim.c_buy_lot(1, {"lot": lot.id})
	sim.deliver_lot(lot.id, Vector3(0, 0.02, 5.2))
	await get_tree().process_frame
	var units := s.items.values().filter(func(i): return i.kind == Item.KIND_UNIT)
	var targets := ["bench_1:unit", "repair_1:unit", "grade_1:unit", "shelf_a:0", "shelf_a:1", "shelf_a:4", "shelf_b:2", "shelf_b:5"]
	for i in mini(targets.size(), units.size()):
		var it: Item = units[i]
		s.set_loc(it, {"t": "slot", "slot": targets[i]})
		if i >= 2:
			it.unit.tested = true
			it.unit.imaged = true
			for f in it.unit.faults:
				f.fixed = true
			it.unit.grade = Valuation.grade_for(it.unit)
			it.unit.list_price = Valuation.market_value(it.unit, it.unit.grade)
	for usb in s.items.values().filter(func(i): return i.kind == Item.KIND_USB_TEST):
		s.set_loc(usb, {"t": "slot", "slot": "bench_1:usb"})
	sim.try_start_bench("bench_1", 1)
	sim.advance_minutes(10)


func _bigkit_shots() -> void:
	## Desktops/servers in the garage: heavy rack stocked, a big box in the OUTGOING floor bay, a server pallet.
	await get_tree().create_timer(0.3).timeout
	var s := Game.state
	var sim := Game.sim
	var models := ["corvex_rampart_2200", "corvex_rampart_1100", "northstar_vault_s316", "northstar_bastion_t5", "corvex_fulcrum_7_tower", "halden_keystone_micro_4"]
	for k in models.size():
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = sim.lots.roll_unit(models[k], 0.7)
		s.set_loc(it, {"t": "slot", "slot": "heavy_rack_1:%d" % k})
	for k in 2:
		var mid: String = ["halden_stratus_600_sff", "corvex_fulcrum_5_micro"][k]
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = sim.lots.roll_unit(mid, 0.7)
		s.set_loc(it, {"t": "slot", "slot": "shelf_a:%d" % (k + 1)})
	var srv := s.new_item(Item.KIND_UNIT)
	srv.unit = sim.lots.roll_unit("corvex_rampart_2200", 0.7)
	s.set_loc(srv, {"t": "slot", "slot": "bench_1:unit"})
	var pkg := s.new_item(Item.KIND_PACKAGE)
	pkg.data = {"order_id": 77, "buyer": "J. Okafor", "model": "Corvex Fulcrum 7 Tower", "large": true, "carry": 3, "size": [0.31, 0.5, 0.54]}
	s.set_loc(pkg, {"t": "slot", "slot": "courier_1:0"})
	var lot := {"id": s.make_id(), "seller_name": "Brackenfield Data Centre", "count": 8, "total_price": 2400, "status": "bought", "units": []}
	for i in 8:
		lot.units.append(sim.lots.roll_unit(["corvex_rampart_1100", "corvex_rampart_2200"][i % 2], 0.6).to_dict())
	s.lots[lot.id] = lot
	sim.deliver_lot(lot.id, Vector3(0.2, 0.02, 1.4))
	await get_tree().create_timer(0.6).timeout
	_cam = Camera3D.new()
	_cam.fov = 62
	_level.add_child(_cam)
	_cam.current = true
	var rack: Node3D = LevelRoot.current.fixture_nodes.get("heavy_rack_1")
	var shots := []
	if rack:
		var fwd := rack.global_transform.basis.z
		shots.append(["bigkit_rack", rack.global_position + fwd * 2.3 + Vector3(0.4, 1.35, 0), rack.global_position + Vector3(0, 0.8, 0)])
		print("heavy rack at ", rack.global_position)
	var cour: Node3D = LevelRoot.current.fixture_nodes.get("courier_1")
	if cour:
		shots.append(["bigkit_outgoing", cour.global_position + cour.global_transform.basis.z * 1.7 + Vector3(0, 1.1, 0), cour.global_position + Vector3(0, 0.45, 0)])
	shots.append(["bigkit_pallet", Vector3(1.6, 1.5, 3.1), Vector3(0.2, 0.4, 1.4)])
	shots.append(["bigkit_bench", Vector3(-0.6, 1.5, -1.6), Vector3(-0.85, 0.95, -3.0)])
	shots.append(["bigkit_overview", Vector3(2.4, 2.2, 3.2), Vector3(-0.8, 0.6, -1.0)])
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	for sh in shots:
		_cam.global_position = sh[1]
		_cam.look_at(sh[2])
		for i in 12:
			await get_tree().process_frame
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png("res://tools/out/%s.png" % sh[0])
		print("saved ", sh[0])


func _ui_shots() -> void:
	var hud := Hud.new()
	add_child(hud)
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	var player: Player = LevelRoot.current.local_player()
	player.global_position = Vector3(-0.75, 0.05, -2.0)
	player._yaw = 0.0
	player._pitch = deg_to_rad(-40)
	player._apply_look()
	await get_tree().create_timer(1.0).timeout
	await _save("hud_bench")
	# Carry a laptop from the shelf
	var on_shelf := Game.state.item_in_slot("shelf_a:0")
	if on_shelf:
		Game.request("pick_up", {"item": on_shelf.id})
	player.global_position = Vector3(1.6, 0.05, 0.5)
	player._yaw = deg_to_rad(-90)
	player._pitch = deg_to_rad(-25)
	player._apply_look()
	await get_tree().create_timer(1.0).timeout
	await _save("hud_carry")
	# Office PC
	var pc: OfficePC = LevelRoot.current.find_child("OfficePC", true, false)
	player.global_position = Vector3(-1.9, 0.05, -2.6)
	player._yaw = deg_to_rad(90)
	player._pitch = deg_to_rad(-10)
	player._apply_look()
	await get_tree().create_timer(0.3).timeout
	pc.interact(player)
	await get_tree().create_timer(1.2).timeout
	pc._os.open_app("purchasing")
	await get_tree().create_timer(0.6).timeout
	await _save("pc_purchasing")
	pc._os.open_app("flogit")
	await get_tree().create_timer(0.6).timeout
	await _save("pc_flogit")
	pc._os.open_app("stock")
	await get_tree().create_timer(0.6).timeout
	await _save("pc_stock")
	pc._os.open_app("building")
	await get_tree().create_timer(0.8).timeout
	await _save("pc_building")


func _insights_shots() -> void:
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	var player: Player = LevelRoot.current.local_player()
	var pc: OfficePC = LevelRoot.current.find_child("OfficePC", true, false)
	player.global_position = pc.global_position + pc.global_basis.z * 0.9
	await get_tree().create_timer(0.3).timeout
	pc.interact(player)
	await get_tree().create_timer(1.2).timeout
	pc._os.open_app("insights")
	var app: Node = pc._os._current
	for i in app.NAV.size():
		app.select(i)
		await get_tree().create_timer(0.5).timeout
		await _save("insights_%d" % i)
		# Second page of the longer tabs
		app._scroll.scroll_vertical = 640
		await get_tree().create_timer(0.3).timeout
		await _save("insights_%db" % i)


func _save(n: String) -> void:
	for i in 6:
		await get_tree().process_frame
	await RenderingServer.frame_post_draw
	get_viewport().get_texture().get_image().save_png("res://tools/out/%s.png" % n)
	print("saved ", n)
