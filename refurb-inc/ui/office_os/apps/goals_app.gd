extends InsightsUI
## Goals & Records: the end goal (mega plant AND £20M lifetime revenue), every campaign milestone with the
## day you reached it, and this PC's leaderboards — fastest runs (in working days) to each milestone.

var _tab := 0


func build() -> void:
	add_child(para("The long game. Every milestone is timed in working days from your first day of trading — beat your own records, or your mates'. Reach the mega plant with £20,000,000 in lifetime sales to build the Refurb empire.", 17, SUB, 1000))
	add_child(tabs(["Progress", "Leaderboards"], func(i):
		_tab = i
		refresh()))
	var sl := scroll_list()
	add_child(sl[0])
	_body = sl[1]


func refresh() -> void:
	clear(_body)
	if _tab == 0:
		_progress()
	else:
		_boards()


func _progress() -> void:
	var s := Game.state
	var got := Campaign.reached(s)
	var end: Dictionary = Campaign.data().get("end_goal", {})
	var day := Campaign.day_number(s)
	var revenue := float(s.totals.get("revenue", 0))
	var tier := Campaign.tier_of(s.building_id)
	var top_tier := Campaign.tier_of("megaplant")
	_kpis([
		["Run time", "Day %d" % day, "working days since you opened", BLUE],
		["Lifetime revenue", GameState.format_money(int(revenue)), "%.2f%% of £20M" % (revenue / 200000.0), GREEN],
		["Premises", str(Content.buildings.get(s.building_id, {}).get("name", s.building_id)), "tier %d of %d" % [tier, top_tier], AMBER],
		["Milestones", "%d / %d" % [got.size() - (1 if got.has(end.get("id", "")) else 0), Campaign.milestones().size()], "reached", Color(0.42, 0.28, 0.72)],
	])
	var ev := _section("🏆 End goal — %s" % str(end.get("name", "")), str(end.get("desc", "")))
	if got.has(end.get("id", "")):
		var best := Campaign.best(str(end.id))
		ev.add_child(l("Complete in %d working days!%s" % [int(got[end.id]), ("  Your fastest ever: %d days." % int(best.days)) if not best.is_empty() else ""], 20, GREEN))
	_meter_row(ev, "Premises: %s" % str(Content.buildings.get(s.building_id, {}).get("name", "")), float(tier) / float(top_tier), "tier %d / %d" % [tier, top_tier], AMBER)
	_meter_row(ev, "Lifetime revenue", minf(revenue / 20000000.0, 1.0), "%s / £20M" % GameState.format_money(int(revenue)), GREEN)
	var mv := _section("Milestones", "Timed in working days. Your best time and this PC's record are shown for each.")
	for m: Dictionary in Campaign.milestones():
		var row := HBoxContainer.new()
		row.add_theme_constant_override("separation", 12)
		var done := got.has(m.id)
		row.add_child(pill(" ✓ " if done else " · ", GREEN if done else Color(0.75, 0.77, 0.8), Color.WHITE, 16))
		var v := VBoxContainer.new()
		v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		v.add_child(l(str(m.name), 18, INK if done else SUB))
		v.add_child(l(str(m.desc), 13, SUB))
		if not done:
			var pr := Campaign.progress(s, m)
			var frac := clampf(float(pr[0]) / maxf(float(pr[1]), 0.001), 0.0, 1.0)
			var bar := _Meter.new()
			bar.frac = frac
			bar.colour = BLUE
			bar.custom_minimum_size = Vector2(320, 10)
			v.add_child(bar)
		row.add_child(v)
		var rec := Campaign.best(str(m.id))
		var right := VBoxContainer.new()
		right.custom_minimum_size.x = 230
		right.add_child(l("Day %d" % int(got[m.id]) if done else "—", 18, GREEN if done else SUB))
		right.add_child(l(("Record: %d days (%s)" % [int(rec.days), str(rec.company)]) if not rec.is_empty() else "No record yet", 13, SUB))
		row.add_child(right)
		mv.add_child(row)
		mv.add_child(HSeparator.new())


func _boards() -> void:
	var recs := Campaign.load_records()
	if recs.is_empty():
		_empty("No records yet. Reach a milestone in a run started from the main menu and your time lands here.")
		return
	var end: Dictionary = Campaign.data().get("end_goal", {})
	var ids: Array = [str(end.get("id", ""))]
	for m: Dictionary in Campaign.milestones():
		ids.append(str(m.id))
	for id in ids:
		var list: Array = recs.get(id, [])
		if list.is_empty():
			continue
		var sec := _section("Fastest to %s" % Campaign.name_of(id))
		var rows: Array = []
		for i in list.size():
			var e: Dictionary = list[i]
			var mine := str(e.get("run", "")) == str(Game.state.totals.get("run_id", ""))
			rows.append(["#%d%s" % [i + 1, "  (this run)" if mine else ""], str(e.get("company", "")), "%d days" % int(e.days), str(e.get("date", ""))])
		_table(sec, ["Place", "Company", "Time", "Date"], rows, true, 0, [1, 3])
	_note(_body, "Steam leaderboards (friends and worldwide) arrive with the Steam build; these are this PC's runs.")
