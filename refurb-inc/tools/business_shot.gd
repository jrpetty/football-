extends Node
## Visual QA for business buyers, custom builds and Flogit reviews: renders the server build bench mid-build (its
## monitor shows the customer's spec ticking green) and the Business + Flogit Reviews apps on the office PC.
##   godot --path . res://tools/business_shot.tscn [-- --shots bench,business,build,reviews,mail]
## Software renderer (no GPU): add  --rendering-driver d3d12 --gpu-index 1  before --path.

var _cam: Camera3D
var _build_id := 0


func _ready() -> void:
	var args := OS.get_cmdline_user_args()
	var shots: Array = ["bench", "business", "build", "reviews"]
	var i := args.find("--shots")
	if i >= 0 and i + 1 < args.size():
		shots = Array(args[i + 1].split(","))
	get_window().size = Vector2i(1600, 900)
	Game.new_game(4242)
	var s := Game.state
	var sim := Game.sim
	s.reputation = 58.0
	s.cash = 9000
	sim.execute(1, "buy_fixture", {"type": "build_bench"})
	_stage(sim)
	var level: Node = load(load("res://scripts/game_root.gd").level_path("garage")).instantiate()
	add_child(level)
	await get_tree().create_timer(0.8).timeout
	_build_up(sim)
	await get_tree().create_timer(0.5).timeout
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	if "bench" in shots:
		await _bench_shot()
	if shots.any(func(n): return n in ["business", "build", "reviews", "mail"]):
		await _pc_shots(shots)
	get_tree().quit()


func _stage(sim: Sim) -> void:
	## Enquiries in the inbox, a bulk order on the go, a custom build accepted, and a run of Flogit reviews.
	var s := sim.state
	sim.advance_minutes(330.0)      # mid-afternoon, so the staged reviews have real dispatch times behind them
	for k in 3:
		sim._new_offer()
	var bulk := {}
	for c: Dictionary in s.contracts.values():
		if c.kind == "bulk" and bulk.is_empty():
			bulk = c
	if not bulk.is_empty():
		sim.execute(1, "biz_accept", {"contract": bulk.id})
		var model := ""
		for m: Dictionary in Content.models.values():
			if m.get("type", "") == str(bulk.type):
				model = m.id
				break
		for n in mini(int(bulk.qty) - 1, 5):
			var it := s.new_item(Item.KIND_UNIT)
			var u := UnitData.new()
			u.model_id = model
			u.serial = "QA%05d" % it.id
			u.cpu_id = str(Content.model(model).get("ref_cpu", ""))
			u.ram_gb = maxi(int(Content.model(model).get("ref_ram", 0)), int(bulk.get("min_ram", 0)))
			u.storage_gb = maxi(int(Content.model(model).get("ref_storage", 0)), int(bulk.get("min_storage", 0)))
			u.tested = true
			u.imaged = true
			u.grade = str(bulk.grade)
			it.unit = u
			s.set_loc(it, {"t": "loose", "p": [0.5 + n * 0.4, 0.05, 6.5], "r": [0, 0, 0, 1]})
		sim.execute(1, "biz_allocate", {"contract": bulk.id, "auto": true})
	var spec := {"chassis": ["corvex_rampart_2200"], "cpus": 2, "cpu_ids": ["vortex_x9_6248", "vortex_x9_6338"], "cpu_label": "Vortex X9",
		"ram_gb": 256, "drives": 8, "drive_gb": 1200, "psus": 2, "raid": true}
	var build := {"kind": "build", "buyer": "Pennine MSP Ltd", "buyer_id": "pennine_msp", "sector": "IT services", "status": "offer",
		"orders": [], "shipped": 0, "revenue": 0, "late": false, "offered": s.minute, "hours": 24, "name": "Virtualisation host",
		"spec": spec, "parts_estimate": Business.build_parts_cost(spec), "unit": 0,
		"expires": Business.add_working_minutes(s, s.minute, 360.0), "due": ceilf(Business.add_working_minutes(s, s.minute, 24 * 60.0) / 60.0) * 60.0}
	build.price = int(round(float(build.parts_estimate) * 2.05 / 10.0)) * 10
	build.subject = Business.fill("Custom build: {name}", build, s.company_name)
	build.body = Business.fill(str(Content.biz_requests.virt_host.body), build, s.company_name, {"sign": "Tasha Brennan\nTechnical Director"})
	build.id = s.make_id()
	_build_id = build.id
	s.contracts[build.id] = build
	sim.execute(1, "biz_accept", {"contract": build.id})
	for pid in ["chassis_rampart_2200", "cpu_vortex_x9_6248", "cpu_vortex_x9_6248", "ram_rdimm_64", "ram_rdimm_64", "ram_rdimm_64", "ram_rdimm_64"]:
		s.parts[pid] = int(s.parts.get(pid, 0)) + 1
	s.parts["hdd_sas_1200"] = 3
	# A run of Flogit reviews with different stories.
	var stories := [["fast", "A", 0.82, 0.5, 0.0, []], ["fast", "B", 1.0, 1.0, 0.0, []], ["ok", "B", 1.0, 5.0, 0.0, []],
		["overgraded", "A", 1.0, 3.0, 0.0, ["cracked_screen"]], ["late", "B", 1.0, 9.0, 3.0, []], ["pricey", "C", 1.35, 4.0, 0.0, []],
		["bargain", "B", 0.8, 6.0, 0.0, []], ["battery", "B", 1.0, 4.0, 0.0, []], ["great", "A", 1.0, 2.0, 0.0, []]]
	var names := ["techmum", "gadgetgaz", "priya_leeds", "bargainhunter", "studentlife", "nan_on_the_net", "tom_swansea", "retrogamer", "zoe_buys"]
	var models := ["corvex_prospect_5420", "halden_folio_840", "northstar_meridian_t14", "brightline_aura_13", "corvex_vantage_3510"]
	for k in stories.size():
		var st: Array = stories[k]
		var u := UnitData.new()
		u.model_id = models[k % models.size()]
		u.cpu_id = str(Content.model(u.model_id).get("ref_cpu", ""))
		u.ram_gb = int(Content.model(u.model_id).get("ref_ram", 8))
		u.storage_gb = int(Content.model(u.model_id).get("ref_storage", 256))
		u.tested = true
		u.grade = st[1]
		u.cosmetic = st[5]
		u.battery_pct = 52 if st[0] == "battery" else 93
		if st[0] == "overgraded":
			u.missed_flaw = "cracked_screen"
		var price := int(Valuation.market_value(u, u.grade) * float(st[2]))
		var oid := s.make_id()
		var o := {"id": oid, "channel": "flogit", "unit": 0, "model": u.display_name(), "grade": u.grade, "price": price, "buyer": names[k],
			"message": "", "placed": s.minute - float(st[3]) * 60.0, "due": s.minute - float(st[4]) * 60.0 if float(st[4]) > 0.0 else s.minute + 300.0,
			"status": "shipped", "shipped": s.minute, "late_flagged": false}
		o.rv = Reviews.facts(s, o, u, float(st[4]) <= 0.0, s.minute)
		o.review = Reviews.make(s, o)
		o.review.at = s.minute - (stories.size() - k) * 20.0
		s.orders[oid] = o
		BusinessStats.on_review(s, o, o.review)
		s.mark("order", oid)


func _build_up(sim: Sim) -> void:
	## Unbox the chassis on the bench and fit CPUs, memory and a few drives — the checklist is part-way green.
	sim.execute(1, "build", {"station": "build_bench_1", "act": "chassis"})
	for k in 9:
		sim.execute(1, "build", {"station": "build_bench_1", "act": "step"})


func _bench_shot() -> void:
	var node: Node3D = LevelRoot.current.fixture_nodes.get("build_bench_1")
	if node == null:
		print("no build bench node")
		return
	print("build bench at ", node.global_position, " yaw ", node.rotation_degrees.y)
	_cam = Camera3D.new()
	_cam.fov = 62
	LevelRoot.current.add_child(_cam)
	var xf := node.global_transform
	_cam.global_position = xf * Vector3(0.55, 1.62, 1.55)
	_cam.look_at(xf * Vector3(-0.05, 1.02, -0.1), Vector3.UP)
	_cam.current = true
	await get_tree().create_timer(1.0).timeout
	await _save("business_bench")
	_cam.global_position = xf * Vector3(-0.3, 1.22, 0.62)
	_cam.look_at(xf * Vector3(-0.6, 1.13, 0.09), Vector3.UP)
	await get_tree().create_timer(0.5).timeout
	await _save("business_bench_screen")


func _pc_shots(shots: Array) -> void:
	var player: Player = LevelRoot.current.local_player()
	var pc: OfficePC = LevelRoot.current.find_child("OfficePC", true, false)
	player.global_position = pc.global_position + pc.global_basis.z * 0.9
	await get_tree().create_timer(0.3).timeout
	pc.interact(player)
	await get_tree().create_timer(1.2).timeout
	if "business" in shots:
		pc._os.open_app("business")
		await get_tree().create_timer(0.6).timeout
		await _save("business_app_enquiries")
	if "build" in shots:
		pc._os.open_app("business")
		pc._os._current._bar.current_tab = 1
		await get_tree().create_timer(0.4).timeout
		pc._os._current.show_contract(_build_id)
		await get_tree().create_timer(0.4).timeout
		await _save("business_app_on_the_go")
	if "reviews" in shots:
		pc._os.open_app("flogit")
		pc._os._current._tab = 3
		pc._os._current.refresh()
		await get_tree().create_timer(0.6).timeout
		await _save("business_flogit_reviews")
	if "mail" in shots:
		pc._os.open_app("mail")
		await get_tree().create_timer(0.6).timeout
		await _save("business_mail")


func _save(n: String) -> void:
	for k in 6:
		await get_tree().process_frame
	if DisplayServer.get_name() == "headless":
		print("dry run (headless): would save ", n)       # exercises the UI code paths without a renderer
		return
	await RenderingServer.frame_post_draw
	get_viewport().get_texture().get_image().save_png("res://tools/out/%s.png" % n)
	print("saved ", n)
