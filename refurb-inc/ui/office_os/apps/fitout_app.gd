extends InsightsUI
## Fit-out: make the premises yours. Wall paint, floor markings, line and accent colours, the logo and door signs,
## decor to buy (plants, posters, a clock, a rug, a site radio) and the Employee of the Month. Every choice is a
## host command (set_premises), saved and synced, and follows the company to every premises it moves to.

const TABS := ["Walls & colours", "Floor markings", "Signage", "Decor", "Employee of the Month"]

var _tab := 0
var _sig := ""


func build() -> void:
	add_child(para("Make the place yours. The decorators work overnight (changes are instant), you pay once, and the look comes with you when you move premises.", 17, SUB, 1000))
	add_child(tabs(TABS, func(i):
		_tab = i
		_sig = ""
		refresh()))
	var sl := scroll_list()
	add_child(sl[0])
	_body = sl[1]


func refresh() -> void:
	var s := Game.state
	var sig := "%d|%s|%s|%d|%s" % [_tab, str(s.premises), s.company_name, s.cash / 50, str(s.fixtures.size())]
	if sig == _sig:
		return
	_sig = sig
	clear(_body)
	match _tab:
		0: _walls()
		1: _floor()
		2: _signage()
		3: _decor()
		4: _eotm()


# ---------------------------------------------------------------- pages

func _walls() -> void:
	var s := Game.state
	var paint := Content.premises_option("paint", str(s.style("paint")))
	var accent := Content.premises_option("accent", str(s.style("accent")))
	_kpis([
		["Walls", str(paint.get("name", "")), "inside walls, every premises", _col(paint, Color(0.6, 0.6, 0.6))],
		["Accent colour", str(accent.get("name", "")), "signs, rug, motto poster", _col(accent, BLUE)],
		["Premises", str(s.building().get("name", "")), "where you are now", INK],
	], 3)
	var sec := _section("Wall paint", "One coat of emulsion over the block or cladding inside. Pick \"As found\" to strip it back.")
	_swatches(sec, "paint", Content.premises_data.get("paints", []), true)
	var sec2 := _section("Accent colour", "Your brand colour: the door signs, the logo on the shutter, the rug and the motto poster.")
	_swatches(sec2, "accent", Content.premises_data.get("accents", []), false)


func _floor() -> void:
	var s := Game.state
	var sec := _section("Floor markings", "Painted lines like a proper warehouse. Bay boxes follow your equipment when you move it.")
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	for m: Dictionary in Content.premises_data.get("markings", []):
		var chosen := str(s.style("markings")) == str(m.id)
		var c := card(Color(0.93, 0.97, 1.0) if chosen else CARD)
		c.custom_minimum_size.x = 230
		var v := VBoxContainer.new()
		c.add_child(v)
		v.add_child(l(str(m.name), 19))
		v.add_child(para(str(m.get("desc", "")), 14, SUB, 200))
		var price := int(m.get("price", 0))
		if chosen:
			v.add_child(pill(" CURRENT ", GREEN))
		else:
			v.add_child(button("Paint it — %s" % GameState.format_money(price) if price > 0 else "Choose", _choose.bind("markings", str(m.id)), BLUE, not _afford(price)))
		row.add_child(c)
	sec.add_child(row)
	var sec2 := _section("Line colour", "Free to change once the floor's marked.")
	_swatches(sec2, "line_colour", Content.premises_data.get("line_colours", []), false)


func _signage() -> void:
	var s := Game.state
	var sec := _section("Company name", "On the signs, the doors, the rug, your parcels and the leaderboards.")
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 10)
	var edit := LineEdit.new()
	edit.text = s.company_name
	edit.max_length = Sim.COMPANY_NAME_MAX
	edit.custom_minimum_size.x = 420
	h.add_child(edit)
	h.add_child(button("Rename", func(): Game.request("rename_company", {"name": edit.text})))
	sec.add_child(h)
	var sec2 := _section("Logo", "Goes on the door signs and the roller shutters, in your accent colour.")
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	for lg: Dictionary in Content.premises_data.get("logos", []):
		var chosen := str(s.style("logo")) == str(lg.id)
		var c := card(Color(0.93, 0.97, 1.0) if chosen else CARD)
		c.custom_minimum_size.x = 210
		var v := VBoxContainer.new()
		c.add_child(v)
		v.add_child(_LogoPreview.make(str(lg.id), PremisesStyle.accent_colour(s), DecorProp._initials(s.company_name)))
		v.add_child(l(str(lg.name), 18))
		v.add_child(para(str(lg.get("desc", "")), 13, SUB, 180))
		if chosen:
			v.add_child(pill(" CURRENT ", GREEN))
		else:
			v.add_child(button("Use this", _choose.bind("logo", str(lg.id))))
		row.add_child(c)
	sec2.add_child(row)
	var sec3 := _section("Door signs", "A sign above every roller door and the logo on the shutters.")
	var on := bool(s.style("door_sign"))
	sec3.add_child(button("Take the signs down" if on else "Put the signs up", _choose.bind("door_sign", not on), Color(0.55, 0.57, 0.6) if on else GREEN))


func _decor() -> void:
	var s := Game.state
	_note(_body, "Decor is equipment like anything else: it's delivered straight away, you place it, [F] moves it, and it comes with you when you move. Wall pieces hang on the nearest wall.")
	for type in Content.fixtures:
		var def: Dictionary = Content.fixture(type)
		if not def.get("decor", false):
			continue
		var owned := s.fixtures.values().filter(func(f): return str(f.get("type", "")) == type).size()
		var c := card()
		var h := HBoxContainer.new()
		h.add_theme_constant_override("separation", 16)
		c.add_child(h)
		var v := VBoxContainer.new()
		v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		h.add_child(v)
		v.add_child(l(str(def.name), 20))
		v.add_child(para(str(def.get("desc", "")), 15, SUB, 700))
		h.add_child(l("%d owned" % owned if owned > 0 else "", 15, SUB))
		h.add_child(l(GameState.format_money(int(def.price)), 22))
		h.add_child(button("Buy & place", _buy.bind(type), GREEN, not _afford(int(def.price))))
		_body.add_child(c)


func _eotm() -> void:
	var s := Game.state
	var e: Dictionary = s.premises.get("eotm", {})
	_note(_body, "Every Monday morning, whoever got the most done last week — testing, repairs, imaging, grading, packing, hauling, buying — goes up in the frame on the wall. Staff count too. Upload a photo face in the character menu and it's your face on the wall.")
	if str(e.get("key", "")) == "":
		_empty("Nominations open. The first Employee of the Month is announced on Monday.")
		return
	var weeks := int(e.get("streak", 1))
	_kpis([
		["Employee of the Month", str(e.name), ("%s · " % str(e.get("role", ""))) if e.get("staff", false) else "", Color(0.78, 0.6, 0.2)],
		["Jobs last week", str(int(e.get("score", 0))), "weighted: repairs count 1.5", GREEN],
		["Weeks running", str(weeks), "in a row" if weeks > 1 else "first time", BLUE],
	], 3)
	var sec := _section("Last week's leaderboard")
	var rows: Array = []
	var i := 1
	for row in e.get("board", []):
		rows.append(["#%d" % i, str(row.name) + ("  (staff)" if row.get("staff", false) else ""), str(int(row.score))])
		i += 1
	_table(sec, ["Place", "Name", "Jobs"], rows, false, 1, [1])


# ---------------------------------------------------------------- helpers

func _swatches(parent: VBoxContainer, key: String, opts: Array, priced: bool) -> void:
	var grid := GridContainer.new()
	grid.columns = 4
	grid.add_theme_constant_override("h_separation", 10)
	grid.add_theme_constant_override("v_separation", 10)
	var cur := str(Game.state.style(key))
	for o: Dictionary in opts:
		var chosen := cur == str(o.id)
		var b := Button.new()
		b.custom_minimum_size = Vector2(230, 64)
		var col := _col(o, Color(0.8, 0.8, 0.78))
		var price := int(o.get("price", 0)) if priced else 0
		b.text = "%s%s" % [str(o.name), ("\n" + GameState.format_money(price)) if price > 0 and not chosen else ("\n✓ current" if chosen else "")]
		var fg := Color.WHITE if col.get_luminance() < 0.55 else INK
		b.add_theme_color_override("font_color", fg)
		b.add_theme_color_override("font_hover_color", fg)
		b.add_theme_color_override("font_pressed_color", fg)
		b.add_theme_font_size_override("font_size", 16)
		b.add_theme_stylebox_override("normal", UiStyle.box(col, 8, 10, Color(0.1, 0.12, 0.15) if chosen else BORDER))
		b.add_theme_stylebox_override("hover", UiStyle.box(col.lightened(0.08), 8, 10, Color(0.1, 0.12, 0.15)))
		b.add_theme_stylebox_override("pressed", UiStyle.box(col.darkened(0.1), 8, 10, Color(0.1, 0.12, 0.15)))
		b.disabled = chosen or not _afford(price)
		b.pressed.connect(_choose.bind(key, str(o.id)))
		grid.add_child(b)
	parent.add_child(grid)


static func _col(o: Dictionary, fallback: Color) -> Color:
	var c := str(o.get("colour", ""))
	return Color.html(c) if c != "" and Color.html_is_valid(c) else fallback


func _afford(price: int) -> bool:
	return Game.state.cash - price >= int(Content.econ("overdraft_warning", -500))


func _choose(key: String, value: Variant) -> void:
	Sfx.play_ui("ui_click", -8.0)
	Game.request("set_premises", {"key": key, "value": value})


func _buy(type: String) -> void:
	var before := Game.state.fixtures.keys()
	Game.request("buy_fixture", {"type": type})
	await get_tree().create_timer(0.15).timeout
	for id in Game.state.fixtures:
		if not id in before:
			if os and os.pc:
				os.pc.leave_then(func(p): p.start_placement(id))
			return


class _LogoPreview extends Control:
	## A flat drawing of a logo style for the picker.
	var style := "badge"
	var col := Color.ORANGE
	var initials := "RI"

	static func make(s: String, c: Color, ini: String) -> Control:
		var p := _LogoPreview.new()
		p.style = s
		p.col = c
		p.initials = ini
		p.custom_minimum_size = Vector2(180, 70)
		return p

	func _draw() -> void:
		var c := Vector2(36, 35)
		var r := 28.0
		match style:
			"badge":
				draw_rect(Rect2(c - Vector2(r, r), Vector2(r, r) * 2), col)
			"ring":
				draw_circle(c, r, col)
				draw_circle(c, r * 0.78, Color.WHITE)
			"chevron":
				for i in 3:
					var x := c.x - 16 + i * 16
					draw_polyline([Vector2(x - 6, c.y - 16), Vector2(x + 6, c.y), Vector2(x - 6, c.y + 16)], col, 6.0)
		if style in ["badge", "ring"]:
			var f := get_theme_default_font()
			var tc := Color.WHITE if style == "badge" else col
			draw_string(f, Vector2(c.x - 20, c.y + 9), initials, HORIZONTAL_ALIGNMENT_CENTER, 40, 24, tc)
		draw_string(get_theme_default_font(), Vector2(76, 42), Game.state.company_name.to_upper(), HORIZONTAL_ALIGNMENT_LEFT, 104, 13, Color(0.2, 0.22, 0.25))
