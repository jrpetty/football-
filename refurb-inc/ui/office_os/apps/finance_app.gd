extends PaneApp
## Wages & Finance: balance, today's and this week's P&L, upcoming bills, full ledger, and a cash chart.

var _body: VBoxContainer


func build() -> void:
	var sl := scroll_list()
	add_child(sl[0])
	_body = sl[1]


var _renaming := false
var _edit: LineEdit


func refresh() -> void:
	if _renaming and is_instance_valid(_edit) and _edit.has_focus():
		return            # don't rebuild under the player's typing
	_renaming = false
	clear(_body)
	var s := Game.state
	_company_row(s)
	var top := HBoxContainer.new()
	top.add_theme_constant_override("separation", 16)
	_body.add_child(top)
	top.add_child(_stat("Balance", GameState.format_money(s.cash), UiStyle.money_colour(s.cash)))
	var today := _sum_since(s.day_start_minute(s.day_index()))
	top.add_child(_stat("Today", GameState.format_money(today), GREEN if today >= 0 else RED))
	var week_start := s.day_start_minute(s.day_index() - s.weekday())
	var week := _sum_since(week_start)
	top.add_child(_stat("This week", GameState.format_money(week), GREEN if week >= 0 else RED))
	top.add_child(_stat("Units sold (all time)", str(int(s.totals.get("units_sold", 0))), INK))
	top.add_child(_stat("Revenue (all time)", GameState.format_money(int(s.totals.get("revenue", 0))), INK))
	# Bills
	var bills := card()
	var bv := VBoxContainer.new()
	bills.add_child(bv)
	var due_day := int(Content.econ("rent_due_weekday", 4))
	var days_to := (due_day - s.weekday() + 7) % 7
	bv.add_child(l("Upcoming", 20))
	var b := s.building()
	bv.add_child(l("Rent (%s) £%d + utilities £%d — due %s at close" % [b.get("name", ""), int(b.get("rent_weekly", 150)), int(b.get("utilities_weekly", 25)), "today" if days_to == 0 else GameState.WEEKDAYS[due_day]], 17, AMBER if days_to <= 1 else INK))
	var wages := 0
	for d in s.staff.values():
		wages += int(d.get("wage", 0))
	if wages > 0:
		bv.add_child(l("Staff wages £%d (%d staff) — due %s at close" % [wages, s.staff.size(), "today" if days_to == 0 else GameState.WEEKDAYS[due_day]], 17, AMBER if days_to <= 1 else INK))
	else:
		bv.add_child(l("Staff wages: none yet (hire staff in the Staff app once the business can carry them)", 17, SUB))
	if s.cash < 0:
		bv.add_child(para("You are overdrawn. The bank charges £%d a week and it dents your reputation. Sell stock to recover." % int(Content.econ("overdraft_fee_weekly", 40)), 17, RED, 800))
	_body.add_child(bills)
	# Chart: running balance over the ledger
	var chart := _Chart.new()
	chart.custom_minimum_size = Vector2(0, 220)
	chart.points = _balance_series()
	var cc := card()
	var cv := VBoxContainer.new()
	cc.add_child(cv)
	cv.add_child(l("Balance over time", 18))
	cv.add_child(chart)
	_body.add_child(cc)
	# Category breakdown this week
	var cats := {}
	for e in s.ledger:
		if float(e.m) >= week_start:
			cats[e.cat] = int(cats.get(e.cat, 0)) + int(e.amt)
	var bc := card()
	var bcv := VBoxContainer.new()
	bc.add_child(bcv)
	bcv.add_child(l("This week by category", 18))
	for k in cats:
		var row := HBoxContainer.new()
		var kl := l(String(k).capitalize(), 17)
		kl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		row.add_child(kl)
		row.add_child(l(GameState.format_money(int(cats[k])), 17, GREEN if int(cats[k]) >= 0 else RED))
		bcv.add_child(row)
	_body.add_child(bc)
	# Ledger
	var lc := card()
	var lv := VBoxContainer.new()
	lc.add_child(lv)
	lv.add_child(l("Ledger (latest first)", 18))
	var entries := s.ledger.duplicate()
	entries.reverse()
	for e in entries.slice(0, 60):
		if int(e.amt) == 0:
			continue
		var row := HBoxContainer.new()
		row.add_child(l(GameState.format_minute(float(e.m)), 15, SUB))
		var d := l("   " + str(e.desc), 15)
		d.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		row.add_child(d)
		row.add_child(l(GameState.format_money(int(e.amt)), 15, GREEN if int(e.amt) >= 0 else RED))
		lv.add_child(row)
	_body.add_child(lc)


func _company_row(s: GameState) -> void:
	## The business name, with Rename (it changes the sign outside, parcels and leaderboard entries).
	var c := card()
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 12)
	c.add_child(h)
	h.add_child(l("Business name", 17, SUB))
	var name_l := l(s.company_name, 24)
	name_l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(name_l)
	var edit := LineEdit.new()
	edit.max_length = Sim.COMPANY_NAME_MAX
	edit.placeholder_text = Sim.DEFAULT_COMPANY
	edit.custom_minimum_size = Vector2(360, 40)
	edit.add_theme_font_size_override("font_size", 20)
	edit.visible = false
	h.add_child(edit)
	_edit = edit
	var save := button("Save", func(): pass, GREEN)
	save.visible = false
	var cancel := button("Cancel", func(): pass, Color(0.45, 0.47, 0.52))
	cancel.visible = false
	var rename := button("Rename", func(): pass)
	var done := func(apply: bool):
		if apply:
			Game.request("rename_company", {"name": edit.text})
		_renaming = false
		refresh.call_deferred()
	rename.pressed.connect(func():
		_renaming = true
		name_l.visible = false
		rename.visible = false
		edit.text = s.company_name
		edit.visible = true
		save.visible = true
		cancel.visible = true
		edit.grab_focus()
		edit.select_all())
	save.pressed.connect(func(): done.call(true))
	cancel.pressed.connect(func(): done.call(false))
	edit.text_submitted.connect(func(_t): done.call(true))
	h.add_child(rename)
	h.add_child(save)
	h.add_child(cancel)
	_body.add_child(c)


func _sum_since(m: float) -> int:
	var t := 0
	for e in Game.state.ledger:
		if float(e.m) >= m:
			t += int(e.amt)
	return t


func _balance_series() -> PackedFloat32Array:
	var out := PackedFloat32Array()
	var total := 0
	for e in Game.state.ledger:
		total += int(e.amt)
	# Walk backwards from the current balance so a trimmed ledger still ends at the right value.
	var bal := Game.state.cash
	var vals: Array = [bal]
	var entries := Game.state.ledger.duplicate()
	entries.reverse()
	for e in entries:
		bal -= int(e.amt)
		vals.append(bal)
	vals.reverse()
	for v in vals:
		out.append(float(v))
	return out


func _stat(title: String, value: String, col: Color) -> Control:
	var c := card()
	c.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var v := VBoxContainer.new()
	c.add_child(v)
	v.add_child(l(title, 15, SUB))
	v.add_child(l(value, 30, col))
	return c


class _Chart extends Control:
	var points := PackedFloat32Array()

	func _draw() -> void:
		var r := Rect2(Vector2.ZERO, size)
		draw_rect(r, Color(0.97, 0.98, 0.99))
		if points.size() < 2:
			return
		var lo := points[0]
		var hi := points[0]
		for p in points:
			lo = minf(lo, p)
			hi = maxf(hi, p)
		lo = minf(lo, 0.0)
		var span := maxf(hi - lo, 1.0)
		var zero_y := size.y - (0.0 - lo) / span * (size.y - 20) - 10
		draw_line(Vector2(0, zero_y), Vector2(size.x, zero_y), Color(0.8, 0.3, 0.3, 0.4), 1.0)
		var pts := PackedVector2Array()
		for i in points.size():
			var x := i / float(points.size() - 1) * size.x
			var y := size.y - (points[i] - lo) / span * (size.y - 20) - 10
			pts.append(Vector2(x, y))
		draw_polyline(pts, Color(0.35, 0.3, 0.85), 3.0, true)
