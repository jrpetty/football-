extends Node
## Renders the garage from a set of camera poses and saves PNGs to tools/out/ (for visual QA / Steam shots).
##   xvfb-run -s "-screen 0 1920x1080x24" godot --path . res://tools/screenshot.tscn -- [--poses name,name] [--setup demo]

const POSES := {
	"door_in": [Vector3(0.6, 1.62, 3.0), Vector3(-0.6, 1.0, -3.0)],
	"benches": [Vector3(0.2, 1.55, -0.9), Vector3(0.0, 0.95, -3.2)],
	"outside": [Vector3(3.8, 1.7, 11.5), Vector3(-0.3, 1.2, 1.0)],
	"office": [Vector3(-1.2, 1.5, -1.4), Vector3(-2.9, 1.0, -2.6)],
	"right_wall": [Vector3(-0.8, 1.6, 0.4), Vector3(2.8, 0.9, -0.2)],
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
	_level = load("res://scenes/levels/garage/garage.tscn").instantiate()
	add_child(_level)
	if "--setup" in args:
		_demo_setup()
	await get_tree().create_timer(0.5).timeout
	# Remove the player camera influence
	_cam = Camera3D.new()
	_cam.fov = 70
	_level.add_child(_cam)
	_cam.current = true
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	for n in names:
		var p: Array = POSES[n]
		_cam.global_position = p[0]
		_cam.look_at(p[1])
		for i in 12:
			await get_tree().process_frame
		await RenderingServer.frame_post_draw
		var img := get_viewport().get_texture().get_image()
		img.save_png("res://tools/out/%s.png" % n)
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
	for usb in s.items.values().filter(func(i): return i.kind == Item.KIND_USB_TEST):
		s.set_loc(usb, {"t": "slot", "slot": "bench_1:usb"})
	sim.try_start_bench("bench_1", 1)
	sim.advance_minutes(10)
