extends Node
## Renders the morale / training / overtime / challenge UI over a plain backdrop (no level, so it's quick even on the
## software renderer): the Staff app (mood, pay rise, thanks, courses), Insights → Staff, the Skills app's online
## courses, the Keep going? card, the end-of-shift card with overtime, the title screen's Challenges panel and the
## challenge results card. Saves tools/out/feature_<name>.png.
##   Godot --rendering-driver d3d12 --gpu-index 1 --path . res://tools/feature_shots.tscn [-- --only staff,results]

const SHOT_RECORDS := "user://shot_challenge_records.json"

var _only: Array = []


func _ready() -> void:
	get_window().size = Vector2i(1600, 900)
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	var args := OS.get_cmdline_user_args()
	if args.find("--only") >= 0 and args.size() > args.find("--only") + 1:
		_only = Array(args[args.find("--only") + 1].split(","))
	var real := Challenges.records_path
	Challenges.records_path = SHOT_RECORDS
	DirAccess.remove_absolute(ProjectSettings.globalize_path(SHOT_RECORDS))
	var bg := ColorRect.new()
	bg.color = Color(0.16, 0.18, 0.2)
	bg.set_anchors_preset(Control.PRESET_FULL_RECT)
	add_child(bg)
	_company()
	if _want("staff") or _want("insights") or _want("skills"):
		await _office_shots()
	if _want("overtime"):
		await _overtime_shot()
	if _want("summary"):
		await _summary_shot()
	if _want("challenges"):
		await _challenges_shot()
	if _want("results"):
		await _results_shot()
	DirAccess.remove_absolute(ProjectSettings.globalize_path(SHOT_RECORDS))
	Challenges.records_path = real
	get_tree().quit()


func _want(n: String) -> bool:
	return _only.is_empty() or _only.has(n)


func _save(n: String) -> void:
	for i in 8:
		await get_tree().process_frame
	if DisplayServer.get_name() == "headless":
		print("built ", n, " (headless: no image)")
		return
	await RenderingServer.frame_post_draw
	var img := get_viewport().get_texture().get_image()
	if img:
		img.save_png("res://tools/out/feature_%s.png" % n)
	print("saved ", n)


func _hire(role: String, level: int, id: int) -> int:
	var c := Staff.make_candidate(Game.state.rng, id)
	c.role = role
	for sk in c.stats:
		c.stats[sk] = clampi(level - 3, 1, 10)
	for r: Dictionary in Staff.data().roles:
		if r.id == role:
			for sk in r.skills:
				c.stats[sk] = level
	c.wage = Staff.wage_for(c.stats)
	Game.state.candidates.append(c)
	Game.sim.execute(1, "hire", {"candidate": id})
	return id


func _company() -> void:
	## A small team a fortnight in: a happy tester, a repair tech who wants a rise, a fed-up grader who has handed
	## in their notice, and a packer away on a course.
	Game.new_game(5150)
	var s := Game.state
	s.company_name = "Sycamore Refurbs"
	s.cash = 14000
	var tester := _hire("tester", 6, 901)
	var tech := _hire("repair_tech", 4, 902)
	var grader := _hire("grader", 5, 903)
	var packer := _hire("packer", 3, 904)
	for i in 5:
		for d in s.staff.values():
			d["since_break"] = -600.0       # (no bodies here to take tea breaks — pretend they did)
		s.day_stats["shipped"] = 4 + i * 2
		s.day_stats["tested"] = 6 + i
		Game.sim.advance_minutes(GameState.MIN_PER_DAY)
		Game.sim.start_next_day()
	Game.sim.advance_minutes(200.0)
	var key := Sim.player_key(StaffView.peer_of(tech))
	s.player(key).xp["repair"] = Staff.xp_for_level(7)
	s.staff[tester].merge({"morale": 86.0, "why": "had a tea break", "status": "Working: test", "low_days": 0, "warned": false}, true)
	s.staff[tester].erase("notice")
	s.staff[tester].erase("wants_rise")
	Game.sim.execute(1, "thank_staff", {"staff": tester})
	s.staff[tester]["morale"] = 88.0
	s.staff[tech].merge({"morale": 51.0, "why": "wants a pay rise (%s)" % GameState.format_money(StaffMorale.fair_wage(s, s.staff[tech])),
		"wants_rise": StaffMorale.fair_wage(s, s.staff[tech]), "status": "Working: repair", "low_days": 0}, true)
	s.staff[tech].erase("notice")
	s.staff[grader].merge({"morale": 16.0, "why": "late orders are stressing everyone", "status": "Waiting for imaged units", "low_days": 3,
		"notice": {"given": s.day_index(), "leave_day": s.day_index() + 2}}, true)
	s.staff[packer].erase("notice")
	Game.sim.execute(1, "send_course", {"staff": packer, "skill": "packing"})
	s.staff[packer]["morale"] = 72.0
	Game.sim.execute(1, "buy_course", {"skill": "repair"})
	Game.sim.execute(1, "buy_course", {"skill": "testing"})


func _office_shots() -> void:
	var cl := CanvasLayer.new()
	add_child(cl)
	var os := PaneOS.new()
	os.set_anchors_preset(Control.PRESET_FULL_RECT)
	cl.add_child(os)
	await get_tree().process_frame
	if _want("staff"):
		os.open_app("staff")
		await get_tree().create_timer(0.4).timeout
		await _save("staff_app")
		var sc := _scroll_of(os._current)
		if sc:
			sc.scroll_vertical = 560
			await _save("staff_app_2")
	if _want("insights"):
		os.open_app("insights")
		var app: Node = os._current
		await get_tree().create_timer(0.4).timeout
		for i in app.NAV.size():
			if app.NAV[i][0] == "staff":
				app.select(i)
		await get_tree().create_timer(0.6).timeout
		await _save("insights_staff")
	if _want("skills"):
		os.open_app("skills")
		await get_tree().create_timer(0.4).timeout
		var sc2 := _scroll_of(os._current)
		if sc2:
			sc2.scroll_vertical = 5000
		await _save("skills_courses")
	cl.queue_free()


func _scroll_of(n: Node) -> ScrollContainer:
	if n is ScrollContainer:
		return n
	for c in n.get_children():
		var f := _scroll_of(c)
		if f:
			return f
	return null


func _overtime_shot() -> void:
	var s := Game.state
	s.totals["licences"] = {"night_shift": true}
	s.day_running = true
	s.minute = s.day_end_minute(s.day_index())
	s.shift = {"ask": true, "day": s.day_index()}
	var p := OvertimePrompt.new()
	add_child(p)
	await get_tree().create_timer(0.5).timeout
	await _save("overtime_prompt")
	p.queue_free()
	s.shift = {}


func _summary_shot() -> void:
	Game.state.day_running = false          # the card shows between shifts (a running clock hides it)
	var ds := DaySummary.new()
	add_child(ds)
	await get_tree().process_frame
	ds.show_summary({"weekday": "Friday", "run_day": 10, "rating": 4, "tested": 14, "repaired": 6, "imaged": 13, "graded": 12,
		"sold": 11, "shipped": 10, "late": 0, "missed": 0, "income": 2140, "spend": 1630, "profit": 510, "cash": 14320,
		"kwh": 31.4, "electric": 9, "overtime_h": 5.0, "overtime_pay": 236, "rent_paid": 172, "wages": 1580, "overtime_paid": 471,
		"morale": 63.5, "rep_delta": 4.0})
	await _save("day_summary")
	ds.queue_free()


func _challenges_shot() -> void:
	# A couple of results on this PC's boards so the cards show bests.
	Challenges.submit("CHALLENGE_GARAGE_TO_LOCKUP", 10.6, true, "Sycamore Refurbs", "shot-1", {"medal": "silver"})
	Challenges.submit_result({"id": "flogit_frenzy", "board": "CHALLENGE_FLOGIT_FRENZY", "status": "won", "goal": {"kind": "ship"},
		"result": {"value": 2.4, "medal": "gold"}}, "Sycamore Refurbs", "shot-2")
	var cl := CanvasLayer.new()
	add_child(cl)
	var menu := MainMenu.new()
	cl.add_child(menu)
	await get_tree().process_frame
	menu._panel.visible = false
	menu._challenges.visible = true
	await get_tree().create_timer(0.4).timeout
	await _save("challenges_menu")
	cl.queue_free()


func _results_shot() -> void:
	var s := Game.state
	var def := Challenges.get_def("council_refresh")
	s.totals["run_id"] = "shot-run"
	for e in [[2.31, "Brookfield ITAD", "x1", "gold"], [3.48, "Pennine Recyclers", "x2", "silver"], [4.9, "Swiftfix", "x3", "bronze"]]:
		Challenges.submit("CHALLENGE_COUNCIL_REFRESH", float(e[0]), true, str(e[1]), str(e[2]), {"medal": str(e[3])})
	Challenges.submit("CHALLENGE_COUNCIL_REFRESH", 2.62, true, s.company_name, "shot-run", {"medal": "gold"})
	var ch := {"id": "council_refresh", "name": def.name, "icon": def.icon, "board": Challenges.board(def), "status": "won",
		"goal": def.goal, "days": def.days, "medals": def.medals, "daily": false,
		"result": {"days": 3, "time": 2.62, "clock": "14:58", "medal": "gold", "value": 2.62}, "lots": []}
	s.totals["challenge"] = ch
	var hud := ChallengeHud.new()
	add_child(hud)
	await get_tree().create_timer(0.6).timeout
	hud.show_results(ch, 2)
	await get_tree().create_timer(0.4).timeout
	await _save("challenge_results")
	hud.queue_free()
