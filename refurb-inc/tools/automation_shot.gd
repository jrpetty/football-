extends Node
## Lays out conveyor runs in a real level and renders them for visual QA of how pieces join:
##   1) straights, flexible bends (45°, 90°, 135°), a merger with a side feed (automation_0..3)
##   2) the line kit: Fast main line → stop gate → splitter → right: bend → people's hatch; left: Express bend →
##      goods-in scanner → conveyor → robot arm → test rack; a line monitor watching it all (automation_4..9)
## Kit rides along (the line is pre-run so the monitor has real numbers). Saves tools/out/automation_<n>.png.
##   godot --path . res://tools/automation_shot.tscn [-- --level warehouse --shots 5,8]

var _cam: Camera3D
var _kit := {}              # fid -> world position of the showcase pieces (for aiming the camera)


func _ready() -> void:
	get_window().size = Vector2i(1600, 900)
	var args := OS.get_cmdline_user_args()
	var level_id := args[args.find("--level") + 1] if args.find("--level") >= 0 else "megaplant"
	Game.new_game(777)
	Game.state.building_id = level_id
	var level: Node = load(load("res://scripts/game_root.gd").level_path(level_id)).instantiate()
	add_child(level)
	await get_tree().create_timer(0.6).timeout
	var s := Game.state
	for id in s.fixtures.keys():
		s.fixtures[id].placed = false
		s.mark("fixture", id)
	await get_tree().process_frame
	var o := Vector3(-14, 0, 2)
	# Chain: c1 → bend 45 R → c2 → bend 90 L (r 1.5) → c3 → merge (side feed) → c4 → bend 135 R → c5
	var chain := [["conveyor", {}], ["conveyor_bend", {"angle": 45, "radius": 1.25, "turn": "right"}], ["conveyor", {}],
		["conveyor_bend", {"angle": 90, "radius": 1.5, "turn": "left"}], ["conveyor", {}], ["conveyor_merge", {}],
		["conveyor", {}], ["conveyor_bend", {"angle": 135, "radius": 1.0, "turn": "right"}], ["conveyor", {}]]
	var pos := o
	var heading := 0.0
	var ids: Array = []
	for i in chain.size():
		var type: String = chain[i][0]
		var bend: Dictionary = chain[i][1]
		var fid := "k%d" % i
		var outs := _add(s, fid, type, pos, heading, bend)
		ids.append(fid)
		pos = outs.out.p
		heading = outs.out.h
	# Side feed into the merger (k5): a straight ending at its side input.
	var mf: Dictionary = s.fixtures.k5
	var mxf := Automation.fixture_xf(mf)
	var mlb := Automation.belt_local(Automation.def_of("conveyor_merge"))
	var sp: Vector3 = mxf * (mlb.in[1].p as Vector3)
	var sd: Vector3 = mxf.basis * (mlb.in[1].d as Vector3)
	var side_o := sp - sd * 1.0
	s.fixtures["side"] = {"type": "conveyor", "label": "side", "placed": true, "p": [side_o.x, 0.0, side_o.z], "r": rad_to_deg(atan2(sd.x, sd.z))}
	s.mark("fixture", "side")
	# The line kit, on the nearest clear stretch of floor.
	var lr := LevelRoot.current
	var kit_o := Vector3.INF
	var cands: Array = []
	for cx in range(-30, 31, 2):
		for cz in range(-40, 41, 2):
			cands.append(Vector3(cx, 0, cz))
	cands.sort_custom(func(a, b): return a.distance_to(Vector3(4, 0, -4)) < b.distance_to(Vector3(4, 0, -4)))
	for c: Vector3 in cands:
		if _kit_layout(c, false, lr):
			kit_o = c
			break
	if kit_o != Vector3.INF:
		_kit_layout(kit_o, true, lr)
	print("  line kit at ", kit_o)
	await get_tree().process_frame
	await get_tree().process_frame
	# Kit riding the first run.
	var models := ["kestrel_swift_14", "corvex_rampart_2200", "northstar_vanguard_g17", "corvex_prospect_5420", "halden_stratus_600_sff"]
	for i in 10:
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = Game.sim.lots.roll_unit(models[i % models.size()], 0.6)
		Game.sim.auto.dirty = true
		Game.sim.tick_automation(0.01)
		Game.sim.auto.put_on_belt(it, "k%d" % (i % 9) if i < 9 else "side")
	# Run the line kit for a while (fed with laptops) so the monitor has real load, rates and a queue.
	if kit_o != Vector3.INF:
		for pid in Content.parts.keys():
			s.parts[pid] = 10
		# Slow game clock while pre-running so the test rack is still mid-batch (busy, with a queue) when shot.
		for step in 100:
			_feed(s, step)
			Game.sim.advance_minutes(0.2)
			Game.sim.tick_automation(1.0)
	# Freeze the moment: a software renderer can take minutes a frame, which would otherwise run the day on.
	Game.sim.paused = true
	_cam = Camera3D.new()
	_cam.fov = 50
	level.add_child(_cam)
	_cam.current = true
	var views := [[o + Vector3(-3.0, 6.5, -3.0), o + Vector3(1.5, 0.6, 4.0)], [o + Vector3(3.5, 2.2, 0.5), o + Vector3(0.6, 0.8, 2.6)],
		[pos + Vector3(-3.5, 3.2, 3.5), pos + Vector3(0, 0.8, 0)], [o + Vector3(6.0, 9.0, 8.0), o + Vector3(1.0, 0.5, 6.0)]]
	if kit_o != Vector3.INF:
		var k := kit_o
		views.append([k + Vector3(-7.5, 8.0, -3.5), k + Vector3(0.5, 0.4, 6.0)])                       # 4 overview
		views.append([k + Vector3(2.6, 2.3, 3.4), k + Vector3(0, 0.9, 5.9)])                          # 5 splitter
		views.append([k + Vector3(2.0, 1.75, 0.6), k + Vector3(0, 0.95, 2.7)])                        # 6 gate + Fast livery
		var h: Vector3 = _kit.get("l1", k)
		views.append([h + Vector3(-1.9, 1.75, -1.7), h + Vector3(0, 0.95, 0.1)])                      # 7 people's hatch
		var m: Vector3 = _kit.get("mon", k)
		views.append([m + Vector3(2.3, 1.75, 0.0), m + Vector3(0, 1.95, 0)])                          # 8 line monitor
		var ra: Vector3 = _kit.get("r1", k)
		views.append([ra + Vector3(3.2, 2.6, -1.6), ra + Vector3(-0.2, 0.9, 1.6)])                    # 9 Express run
	var only: Array = []
	if args.find("--shots") >= 0:
		only = Array(args[args.find("--shots") + 1].split(",")).map(func(x): return int(x))
	for i in views.size():
		if not only.is_empty() and not i in only:
			continue
		_cam.global_position = views[i][0]
		_cam.look_at(views[i][1])
		await get_tree().create_timer(2.0).timeout
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png("res://tools/out/automation_%d.png" % i)
	print("SHOT conveyor run: ", ids.size(), " pieces + line kit: ", _kit.size(), " pieces")
	get_tree().quit()


func _add(s: GameState, fid: String, type: String, port: Vector3, heading: float, settings: Dictionary = {}, tier: int = 0, label: String = "", commit: bool = true) -> Dictionary:
	## Place a belt piece so its in-port sits on `port`, facing `heading`; returns its out-ports {name: {p, h}}.
	var auto := Automation.def_of(type).duplicate()
	auto.merge(settings, true)
	var lb := Automation.belt_local(auto)
	var basis := Basis(Vector3.UP, deg_to_rad(heading))
	var origin: Vector3 = port - basis * (lb.in[0].p as Vector3)
	if commit:
		var f := {"type": type, "label": label if label != "" else fid, "placed": true, "p": [origin.x, 0.0, origin.z], "r": heading}
		if not settings.is_empty():
			f["bend"] = Automation.clamp_bend(settings)
		if tier > 0:
			f["tier"] = tier
		s.fixtures[fid] = f
		s.mark("fixture", fid)
	_kit[fid] = origin
	var outs := {"_origin": {"p": origin, "h": heading}}
	for prt in lb.out:
		var d: Vector3 = basis * (prt.d as Vector3)
		outs[prt.name] = {"p": origin + basis * (prt.p as Vector3), "h": rad_to_deg(atan2(d.x, d.z))}
	return outs


func _kit_layout(o: Vector3, commit: bool, lr: LevelRoot) -> bool:
	## Fast main line → gate → splitter; right (-X): bend → people's hatch → conveyor; left (+X): Express bend → scanner →
	## conveyor → arm → test rack. A line monitor stands beside the splitter.
	var s := Game.state
	var plan: Array = []        # [fid, type, port, heading, settings, tier, label]
	var p := o
	plan.append(["n0", "conveyor", p, 0.0, {}, 1, "Conveyor 1"])
	plan.append(["n1", "conveyor_gate", p + Vector3(0, 0, 2.0), 0.0, {}, 1, "Stop gate 1"])
	plan.append(["n2", "conveyor", p + Vector3(0, 0, 3.0), 0.0, {}, 1, "Conveyor 2"])
	plan.append(["n3", "conveyor_splitter", p + Vector3(0, 0, 5.0), 0.0, {}, 1, "Splitter 1"])
	var z_exit := 5.0 + 1.4 - Automation.SPLIT_TAIL
	var ok := true
	var pieces: Array = []
	for row in plan:
		pieces.append(row)
	# Right-hand branch (the kit's right is -X).
	var lb_out := _add(s, "l0", "conveyor_bend", p + Vector3(-Automation.SORTER_SIDE, 0, z_exit), -90.0, {"angle": 90, "radius": 1.0, "turn": "left"}, 0, "", false)
	pieces.append(["l0", "conveyor_bend", p + Vector3(-Automation.SORTER_SIDE, 0, z_exit), -90.0, {"angle": 90, "radius": 1.0, "turn": "left"}, 0, "Bend 1"])
	var hatch_in: Vector3 = lb_out.out.p
	pieces.append(["l1", "work_hatch", hatch_in, 0.0, {}, 0, "People's hatch 1"])
	pieces.append(["l2", "conveyor", hatch_in + Vector3(0, 0, 1.6), 0.0, {}, 0, "Conveyor 3"])
	# Left-hand branch (Express).
	var rb_out := _add(s, "r0", "conveyor_bend", p + Vector3(Automation.SORTER_SIDE, 0, z_exit), 90.0, {"angle": 90, "radius": 1.0, "turn": "right"}, 2, "", false)
	pieces.append(["r0", "conveyor_bend", p + Vector3(Automation.SORTER_SIDE, 0, z_exit), 90.0, {"angle": 90, "radius": 1.0, "turn": "right"}, 2, "Bend 2"])
	var r_in: Vector3 = rb_out.out.p
	pieces.append(["r1", "conveyor_scanner", r_in, 0.0, {}, 2, "Goods-in scanner 1"])
	pieces.append(["r2", "conveyor", r_in + Vector3(0, 0, 1.0), 0.0, {}, 2, "Conveyor 4"])
	var end_p: Vector3 = r_in + Vector3(0, 0, 3.0)
	for row in pieces:
		var auto := Automation.def_of(row[1]).duplicate()
		auto.merge(row[4], true)
		var lbx := Automation.belt_local(auto)
		var basis := Basis(Vector3.UP, deg_to_rad(float(row[3])))
		var origin: Vector3 = (row[2] as Vector3) - basis * (lbx.in[0].p as Vector3)
		if not lr.can_place(row[1], origin, float(row[3]), "__none", Automation.clamp_bend(row[4]) if not (row[4] as Dictionary).is_empty() else {}):
			ok = false
			break
	var others := [["ra", "robot_arm", end_p + Vector3(0, 0, 0.9), 0.0, "Arm 1"], ["rt", "rack_test", end_p + Vector3(1.6, 0, 0.9), -90.0, "Test rack 1"],
		["mon", "line_monitor", p + Vector3(-3.0, 0, 3.2), 90.0, "Line monitor"]]
	if ok:
		for row in others:
			if not lr.can_place(row[1], row[2], float(row[3]), "__none"):
				ok = false
				break
	if not ok or not commit:
		return ok
	for row in pieces:
		_add(s, row[0], row[1], row[2], float(row[3]), row[4], int(row[5]), row[6])
	for row in others:
		var q: Vector3 = row[2]
		s.fixtures[row[0]] = {"type": row[1], "label": row[4], "placed": true, "p": [q.x, 0.0, q.z], "r": float(row[3])}
		s.mark("fixture", row[0])
		_kit[row[0]] = q
	return true


func _feed(s: GameState, step: int) -> void:
	## A laptop onto the start of the line every few seconds while there's room.
	if not s.fixtures.has("n0") or step % 6 != 0:
		return
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = Game.sim.lots.roll_unit(["kestrel_swift_14", "corvex_prospect_5420", "northstar_vanguard_g17"][(step / 3) % 3], 0.5)
	if Game.sim.auto.belt_has_room("n0", it, 0.2):
		Game.sim.auto.put_on_belt(it, "n0", 0.2)
	else:
		s.remove_item(it.id)
