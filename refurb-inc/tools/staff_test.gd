extends Node
## AI staff in every premises: hire a crew, give them stock and parts, run the day fast, and check they walk
## the level, use the equipment and move units through the pipeline. Prints a report per level and saves a
## screenshot of the crew at work (tools/out/staff_<level>.png).
##   godot --path . res://tools/staff_test.tscn [-- --levels garage,warehouse --secs 60]

const LEVELS := ["garage", "lockup", "industrial", "warehouse", "distribution", "facility", "megaplant"]

var _cam: Camera3D
var _report: Array = []


func _ready() -> void:
	get_window().size = Vector2i(1280, 720)
	var args := OS.get_cmdline_user_args()
	var levels: Array = LEVELS
	if args.find("--levels") >= 0:
		levels = Array(args[args.find("--levels") + 1].split(","))
	var secs := float(args[args.find("--secs") + 1]) if args.find("--secs") >= 0 else 70.0
	for level_id in levels:
		await _run_level(level_id, secs)
	print("STAFF REPORT")
	for line in _report:
		print("  ", line)
	get_tree().quit()


func _run_level(level_id: String, secs: float) -> void:
	Engine.time_scale = 1.0
	Game.new_game(900 + LEVELS.find(level_id))
	Game.state.building_id = level_id
	var level: Node = load(load("res://scripts/game_root.gd").level_path(level_id)).instantiate()
	add_child(level)
	await get_tree().create_timer(1.0).timeout
	var s := Game.state
	var sim := Game.sim
	for pid in Content.parts.keys():
		s.parts[pid] = 6
	# Six untested laptops on the shelving.
	var shelf_slots: Array = []
	for sid in s.slot_defs:
		var host := str(sid).get_slice(":", 0)
		if s.fixtures.has(host) and s.stations.get(host, {}).get("type", "") == "" and s.item_in_slot(sid) == null and s.slot_accepts(sid, "unit"):
			shelf_slots.append(sid)
	var units: Array = []
	for k in mini(6, shelf_slots.size()):
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = sim.lots.roll_unit(["kestrel_swift_14", "corvex_prospect_5420", "halden_folio_840"][k % 3], 0.5)
		it.unit.faults = it.unit.faults.filter(func(f): return int(Content.fault(f.id).get("repair_level", 1)) <= 1)
		s.set_loc(it, {"t": "slot", "slot": shelf_slots[k]})
		units.append(it)
	# The crew.
	var roles := ["tester", "repair_tech", "grader"]
	for ri in roles.size():
		var role: String = roles[ri]
		var c := Staff.make_candidate(s.rng, 700 + ri)
		c.role = role
		for sk in c.stats:
			c.stats[sk] = 7
		s.candidates.append(c)
		sim.execute(1, "hire", {"candidate": int(c.id)})
	await get_tree().create_timer(1.5).timeout
	var lr := LevelRoot.current
	print("  [%s] staff %d views %d nav %s candidates %d" % [level_id, s.staff.size(), lr.staff_views.size(), str(lr.staff_nav != null), s.candidates.size()])
	var start_pos := {}
	for id in lr.staff_views:
		start_pos[id] = lr.staff_views[id].global_position
	var walked := {}
	var stuck := 0
	Engine.time_scale = 6.0
	var t := 0.0
	var shot_done := false
	_cam = Camera3D.new()
	_cam.fov = 60
	level.add_child(_cam)
	_cam.current = true
	while t < secs:
		await get_tree().create_timer(0.5 * Engine.time_scale).timeout
		t += 0.5
		for id in lr.staff_views:
			var v: StaffView = lr.staff_views[id]
			walked[id] = float(walked.get(id, 0.0)) + v.global_position.distance_to(start_pos.get(id, v.global_position))
			start_pos[id] = v.global_position
			if v.status == "No way through":
				stuck += 1
			if int(t * 2) % 40 == 0:
				print("    [%s] t=%.0f %s: %s" % [level_id, t, str(s.staff[id].role), v.status])
		# Frame the crew mid-shift.
		if not shot_done and t > secs * 0.45:
			var centre := Vector3.ZERO
			for id in lr.staff_views:
				centre += lr.staff_views[id].global_position
			centre /= maxf(lr.staff_views.size(), 1)
			var small := level_id in ["garage", "lockup"]
			_cam.global_position = centre + (Vector3(1.2, 1.7, 1.4) if small else Vector3(3.2, 3.0, 3.6))
			_cam.look_at(centre + Vector3(0, 0.9, 0))
			await RenderingServer.frame_post_draw
			get_viewport().get_texture().get_image().save_png("res://tools/out/staff_%s.png" % level_id)
			shot_done = true
	Engine.time_scale = 1.0
	var tested := units.filter(func(i): return i.unit.tested).size()
	var imaged := units.filter(func(i): return i.unit.imaged).size()
	var graded := units.filter(func(i): return i.unit.grade != "").size()
	var faulty := units.filter(func(i): return i.unit.tested and i.unit.has_open_faults()).size()
	var moves: Array = []
	for id in walked:
		moves.append("%s %.0fm" % [str(s.staff[id].role), walked[id]])
	_report.append("%-12s units %d · tested %d · imaged %d · graded %d · still faulty %d · walked: %s · route failures %d · %s" % [
		level_id, units.size(), tested, imaged, graded, faulty, ", ".join(moves), stuck, "OK" if tested > 0 and walked.values().any(func(w): return w > 2.0) and stuck == 0 else "CHECK"])
	level.queue_free()
	await get_tree().process_frame
	await get_tree().process_frame
