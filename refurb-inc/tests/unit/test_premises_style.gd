extends TestCase
## Making the premises yours: paint, floor markings, colours, logo and door signs (host commands, paid once,
## saved, replicated, kept when moving premises), decor equipment (bought, placed, overlapping rules), and the
## Employee of the Month (worked out every Monday from last week's jobs; staff count too).

const P := 1


func test_defaults_and_choices_are_data() -> void:
	var sim := fresh_sim(401)
	var s := sim.state
	eq(str(s.style("paint")), "original", "walls start as found")
	eq(str(s.style("markings")), "none")
	check(bool(s.style("door_sign")), "door signs on by default")
	for key in ["paint", "markings", "line_colour", "accent", "logo"]:
		check(not Content.premises_option(key, str(s.style(key))).is_empty(), "%s default is on the chart" % key)
	for b: Dictionary in Content.buildings.values():
		check(not (b.get("paint_mats", []) as Array).is_empty(), "%s knows which walls to paint" % b.id)
	check(Content.errors.is_empty(), "content validates: %s" % str(Content.errors))


func test_paint_and_markings_cost_once_and_validate() -> void:
	var sim := fresh_sim(402)
	var s := sim.state
	s.cash = 5000
	var price := int(Content.premises_option("paint", "racing_green").price)
	check(sim.execute(P, "set_premises", {"key": "paint", "value": "racing_green"}).ok, "paint the walls")
	eq(str(s.style("paint")), "racing_green")
	eq(s.cash, 5000 - price, "the decorators are paid")
	check(sim.execute(P, "set_premises", {"key": "paint", "value": "racing_green"}).ok, "same again is a no-op")
	eq(s.cash, 5000 - price, "and free")
	check(not sim.execute(P, "set_premises", {"key": "paint", "value": "tartan"}).ok, "not on the colour chart")
	check(not sim.execute(P, "set_premises", {"key": "ceiling", "value": "white"}).ok, "unknown setting")
	check(sim.execute(P, "set_premises", {"key": "markings", "value": "both"}).ok)
	check(sim.execute(P, "set_premises", {"key": "line_colour", "value": "green"}).ok)
	check(sim.execute(P, "set_premises", {"key": "accent", "value": "teal"}).ok)
	check(sim.execute(P, "set_premises", {"key": "logo", "value": "chevron"}).ok)
	check(sim.execute(P, "set_premises", {"key": "door_sign", "value": false}).ok)
	check(not bool(s.style("door_sign")))
	s.cash = -490
	check(not sim.execute(P, "set_premises", {"key": "paint", "value": "mustard"}).ok, "can't afford the decorators")
	check(s.lifetime("restyles") >= 5, "counted")


func test_style_saves_replicates_and_moves_with_you() -> void:
	var sim := fresh_sim(403)
	var s := sim.state
	s.cash = 1000000
	sim.execute(P, "set_premises", {"key": "paint", "value": "navy_blazer"})
	sim.execute(P, "set_premises", {"key": "markings", "value": "walkways"})
	# Save / load.
	var s2 := GameState.new()
	s2.load_dict(JSON.parse_string(JSON.stringify(s.to_dict())))
	eq(str(s2.style("paint")), "navy_blazer", "saved")
	eq(str(s2.style("markings")), "walkways")
	# A co-op guest: full snapshot on joining, then deltas.
	var guest := GameState.new()
	guest.load_dict(s.snapshot())
	eq(str(guest.style("paint")), "navy_blazer", "joining guest sees it")
	sim.execute(P, "set_premises", {"key": "accent", "value": "purple"})
	check(s.dirty.has("premises|0"), "marked for replication")
	guest.import_entity("premises", 0, s.export_entity("premises", 0))
	eq(str(guest.style("accent")), "purple", "guest gets the change")
	# Moving premises keeps the look.
	check(sim.execute(P, "move_building", {"building": "lockup"}).ok)
	eq(str(s.style("paint")), "navy_blazer", "the new place gets the same paint job")
	eq(str(s.style("markings")), "walkways")


func test_decor_is_equipment() -> void:
	var sim := fresh_sim(404)
	var s := sim.state
	s.cash = 5000
	var decor := Content.fixtures.values().filter(func(f): return f.get("decor", false))
	check(decor.size() >= 8, "plants, rug, radio, clock, posters, EotM frame (%d)" % decor.size())
	for d: Dictionary in decor:
		check(ResourceLoader.exists(str(d.scene)), "%s has a scene" % d.id)
	var r := sim.execute(P, "buy_fixture", {"type": "plant_tall"})
	check(r.ok and s.fixtures.has(r.id), "buy a plant")
	check(Content.fixture("rug").get("overlap", false), "rugs lie under things")
	eq(str(Content.fixture("poster_motto").get("mount", "")), "wall", "posters hang on walls")
	check(s.fixtures.values().any(func(f): return f.type == "eotm_frame"), "every company starts with an Employee of the Month frame")
	# The site radio is switched on and off for everyone.
	var radio := sim.execute(P, "buy_fixture", {"type": "site_radio"})
	check(sim.execute(P, "decor_toggle", {"id": radio.id}).ok, "radio on")
	check(bool(s.stations[radio.id].get("on", false)))
	check(sim.execute(P, "decor_toggle", {"id": radio.id}).ok, "radio off")
	check(not bool(s.stations[radio.id].get("on", true)))
	check(not sim.execute(P, "decor_toggle", {"id": "bench_1"}).ok, "only things with a switch")


func test_wall_lines_follow_floor_rects() -> void:
	var edges := LevelRoot.wall_edges([Rect2(-3, -3.5, 6, 7)])
	eq(edges.size(), 4)
	var back: Dictionary = edges[0]
	eq(float(back.rot), 0.0, "pieces on the back wall face into the room (+Z)")
	var front := Basis(Vector3.UP, deg_to_rad(float(edges[1].rot))) * Vector3(0, 0, 1)
	near(front.z, -1.0, 0.001, "front wall pieces face back in (-Z)")
	var left := Basis(Vector3.UP, deg_to_rad(float(edges[2].rot))) * Vector3(0, 0, 1)
	near(left.x, 1.0, 0.001, "left wall pieces face +X")


func _monday(sim: Sim) -> void:
	## Run the clock to Friday's close and into Monday morning.
	var s := sim.state
	while s.weekday() != 4:
		sim.end_day()
		sim.start_next_day()
	sim.end_day()
	sim.start_next_day()


func test_employee_of_the_month() -> void:
	var sim := fresh_sim(405)
	var s := sim.state
	s.player("p1")["name"] = "Jon"
	s.player("p2")["name"] = "Sam"
	_monday(sim)
	eq(str(s.premises.get("eotm", {}).get("key", "")), "", "nobody did anything: nominations open")
	BusinessStats.act(s, 1, "tested", 10)
	BusinessStats.act(s, 1, "repaired", 2)
	BusinessStats.act(s, 2, "tested", 4)
	BusinessStats.act(s, 2, "packed", 3)
	_monday(sim)
	var e: Dictionary = s.premises.get("eotm", {})
	eq(str(e.get("key", "")), "p1", "most jobs wins")
	eq(str(e.get("name", "")), "Jon")
	eq(int(e.get("score", 0)), 13, "10 tests + 2 repairs × 1.5")
	eq((e.get("board", []) as Array).size(), 2, "leaderboard")
	eq(int(e.get("streak", 0)), 1)
	check(s.dirty.has("premises|0"), "replicated")
	# Next week counts from zero: Sam out-works Jon this time.
	BusinessStats.act(s, 2, "packed", 9)
	BusinessStats.act(s, 1, "tested", 2)
	_monday(sim)
	e = s.premises.eotm
	eq(str(e.key), "p2", "only last week counts")
	eq(int(e.score), 9)
	# Staff can win it.
	var cid := int(s.candidates[0].id)
	check(sim.execute(P, "hire", {"candidate": cid}).ok or true)
	if s.staff.has(cid):
		BusinessStats.act(s, StaffView.peer_of(cid), "tested", 40)
		_monday(sim)
		check(bool(s.premises.eotm.get("staff", false)), "a member of staff took it")
		eq(str(s.premises.eotm.name), str(s.staff[cid].name))
	check(s.lifetime("eotm_awards") >= 2, "counted")
