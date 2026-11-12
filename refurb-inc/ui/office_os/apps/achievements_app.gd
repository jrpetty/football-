extends InsightsUI
## Achievements: everything you can unlock, by group. Locked ones show progress where it can be measured;
## hidden ones stay "???" until you find them. Unlocks belong to this PC (user://achievements.json), so in co-op
## everyone collects their own — and they'll map straight onto Steam achievements later.

const RENDER_EVERY_S := 1.0
const GOLD := Color(0.86, 0.58, 0.08)
const LOCKED_TILE := Color(0.84, 0.86, 0.89)
const SECRET_TILE := Color(0.62, 0.6, 0.72)
const PURPLE := Color(0.42, 0.28, 0.72)
const MONEY_STATS := ["cash", "totals.revenue", "totals.spent", "lifetime.revenue"]
const MONTHS := ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

var _page := 0               # 0 = overview, then one page per group
var _nav: Array = []         # page titles
var _nav_buttons: Array = []
var _scroll: ScrollContainer
var _title: Label
var _stamp: Label
var _last_render := -100.0
var _last_count := -1


func build() -> void:
	_nav = ["Overview"] + Achievements.groups()
	var root := HBoxContainer.new()
	root.add_theme_constant_override("separation", 14)
	root.size_flags_vertical = Control.SIZE_EXPAND_FILL
	add_child(root)
	var side := VBoxContainer.new()
	side.custom_minimum_size.x = 200
	side.add_theme_constant_override("separation", 3)
	root.add_child(side)
	for i in _nav.size():
		var b := Button.new()
		b.alignment = HORIZONTAL_ALIGNMENT_LEFT
		b.add_theme_font_size_override("font_size", 17)
		b.custom_minimum_size.y = 40
		b.pressed.connect(func():
			Sfx.play_ui("ui_click", -10.0)
			select(i))
		side.add_child(b)
		_nav_buttons.append(b)
	var main := VBoxContainer.new()
	main.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	main.add_theme_constant_override("separation", 10)
	root.add_child(main)
	var top := HBoxContainer.new()
	main.add_child(top)
	_title = l("", 26)
	_title.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(_title)
	_stamp = l("", 15, SUB)
	_stamp.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	top.add_child(_stamp)
	var sl := scroll_list()
	_scroll = sl[0]
	_body = sl[1]
	main.add_child(_scroll)
	Game.achievement_unlocked.connect(_on_unlocked)


func select(i: int) -> void:
	_page = i
	_render(false)


func refresh() -> void:
	# Game state changes constantly; progress bars only need redrawing about once a second.
	var now := Time.get_ticks_msec() / 1000.0
	if now - _last_render > RENDER_EVERY_S or Game.achievements.unlocked_count() != _last_count:
		_render(true)


func _on_unlocked(_def: Dictionary) -> void:
	if is_instance_valid(_body):
		_render(true)


func _render(keep_scroll: bool) -> void:
	if not is_instance_valid(_body):
		return
	_last_render = Time.get_ticks_msec() / 1000.0
	var ach := Game.achievements
	_last_count = ach.unlocked_count()
	var scroll := _scroll.scroll_vertical if keep_scroll else 0
	_style_nav()
	clear(_body)
	_title.text = str(_nav[_page])
	_stamp.text = "%d of %d unlocked   " % [_last_count, Achievements.defs().size()]
	if _page == 0:
		_overview()
	else:
		_group_page(str(_nav[_page]))
	_restore_scroll.call_deferred(scroll)


func _restore_scroll(v: int) -> void:
	if is_instance_valid(_scroll):
		_scroll.scroll_vertical = v


func _style_nav() -> void:
	var ach := Game.achievements
	for i in _nav_buttons.size():
		var b: Button = _nav_buttons[i]
		var on := i == _page
		if i == 0:
			b.text = "  Overview"
		else:
			var defs := Achievements.in_group(str(_nav[i]))
			var have := defs.filter(func(d): return ach.is_unlocked(d.id)).size()
			b.text = "  %s   %d/%d" % [_nav[i], have, defs.size()]
		b.add_theme_stylebox_override("normal", UiStyle.box(GOLD if on else Color(0, 0, 0, 0), 8, 8))
		b.add_theme_stylebox_override("hover", UiStyle.box(GOLD.lightened(0.1) if on else Color(0.88, 0.9, 0.93), 8, 8))
		b.add_theme_stylebox_override("pressed", UiStyle.box(GOLD.darkened(0.1), 8, 8))
		for k in ["font_color", "font_hover_color"]:
			b.add_theme_color_override(k, Color.WHITE if on else INK)


# ================================================================= pages

func _overview() -> void:
	var ach := Game.achievements
	var defs := Achievements.defs()
	var total := defs.size()
	var have := ach.unlocked_count()
	var hidden := defs.filter(func(d): return d.get("hidden", false))
	var hidden_found := hidden.filter(func(d): return ach.is_unlocked(d.id)).size()
	var recent := _recent(6)
	var latest := "—"
	var latest_sub := "nothing yet — get stuck in"
	if not recent.is_empty():
		latest = str(recent[0].name)
		latest_sub = _nice_date(str(ach.unlock_info(recent[0].id).get("date", "")))
	_kpis([
		["Unlocked", "%d / %d" % [have, total], "on this PC", GOLD],
		["Complete", "%d%%" % int(round(100.0 * have / maxf(total, 1))), "of every achievement", GREEN],
		["Secrets found", "%d / %d" % [hidden_found, hidden.size()], "hidden achievements", PURPLE],
		["Latest", latest, latest_sub, BLUE],
	])
	var ov := _section("Your collection", "Unlocks are saved on this PC and kept across every run. In co-op, everyone earns their own.")
	_meter_row(ov, "Overall", float(have) / maxf(total, 1), "%d of %d" % [have, total], GOLD)
	for g in Achievements.groups():
		var gd := Achievements.in_group(str(g))
		var n := gd.filter(func(d): return ach.is_unlocked(d.id)).size()
		_meter_row(ov, str(g), float(n) / maxf(gd.size(), 1), "%d / %d" % [n, gd.size()], GREEN if n == gd.size() else BLUE,
			"Complete" if n == gd.size() and n > 0 else "")
	var near := _nearly_there(6)
	if not near.is_empty():
		var nv := _section("Nearly there", "The locked achievements you're closest to.")
		for d: Dictionary in near:
			_row(nv, d)
	if not recent.is_empty():
		var rv := _section("Recently unlocked")
		for d: Dictionary in recent:
			_row(rv, d)
	_note(_body, "Steam achievements arrive with the Steam build — these unlock there too.")


func _group_page(group: String) -> void:
	var defs := Achievements.in_group(group)
	var ach := Game.achievements
	var n := defs.filter(func(d): return ach.is_unlocked(d.id)).size()
	var sec := _section("%s — %d of %d" % [group, n, defs.size()])
	for i in defs.size():
		_row(sec, defs[i])
		if i < defs.size() - 1:
			sec.add_child(HSeparator.new())


# ================================================================= rows

func _row(parent: VBoxContainer, def: Dictionary) -> void:
	var ach := Game.achievements
	var unlocked := ach.is_unlocked(def.id)
	var disp := Achievements.display(def, unlocked)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 14)
	var tile := PanelContainer.new()
	tile.add_theme_stylebox_override("panel", UiStyle.box(GOLD if unlocked else (SECRET_TILE if disp.hidden else LOCKED_TILE), 12, 6))
	tile.custom_minimum_size = Vector2(60, 60)
	tile.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var g := l(str(disp.icon), 30, Color.WHITE)
	g.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	g.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	if not unlocked and not disp.hidden:
		g.modulate = Color(1, 1, 1, 0.45)
	tile.add_child(g)
	row.add_child(tile)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 2)
	v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	v.add_child(l(str(disp.name), 18, INK if unlocked else SUB))
	v.add_child(para(str(disp.desc), 14, SUB, 380))
	if not unlocked and not disp.hidden:
		var cond: Dictionary = def.get("condition", {})
		var p := ach.progress(cond, Game.state, Game.achievement_context())
		if p.size() == 2 and float(p[1]) > 1.0:
			var h := HBoxContainer.new()
			h.add_theme_constant_override("separation", 10)
			var m := _Meter.new()
			m.frac = clampf(float(p[0]) / maxf(float(p[1]), 0.0001), 0.0, 1.0)
			m.colour = GOLD
			m.custom_minimum_size = Vector2(300, 10)
			m.size_flags_vertical = Control.SIZE_SHRINK_CENTER
			h.add_child(m)
			var money := str(cond.get("stat", "")) in MONEY_STATS
			h.add_child(l("%s / %s" % [_num(minf(float(p[0]), float(p[1])), money), _num(float(p[1]), money)], 13, SUB))
			v.add_child(h)
	row.add_child(v)
	var right := VBoxContainer.new()
	right.custom_minimum_size.x = 170
	right.alignment = BoxContainer.ALIGNMENT_CENTER
	if unlocked:
		right.add_child(pill(" ✓ Unlocked ", GREEN, Color.WHITE, 14))
		right.add_child(l(_nice_date(str(ach.unlock_info(def.id).get("date", ""))), 13, SUB))
	else:
		right.add_child(l("Secret" if disp.hidden else "Locked", 15, SUB))
	row.add_child(right)
	parent.add_child(row)


func _recent(n: int) -> Array:
	var ach := Game.achievements
	var got := Achievements.defs().filter(func(d): return ach.is_unlocked(d.id))
	got.sort_custom(func(a, b): return int(ach.unlock_info(a.id).get("unix", 0)) > int(ach.unlock_info(b.id).get("unix", 0)))
	return got.slice(0, n)


func _nearly_there(n: int) -> Array:
	var ach := Game.achievements
	var ctx := Game.achievement_context()
	var scored: Array = []
	for d: Dictionary in Achievements.defs():
		if ach.is_unlocked(d.id) or d.get("hidden", false):
			continue
		var p := ach.progress(d.get("condition", {}), Game.state, ctx)
		if p.size() != 2 or float(p[1]) <= 1.0:
			continue
		var f := float(p[0]) / maxf(float(p[1]), 0.0001)
		if f > 0.0 and f < 1.0:
			scored.append([f, d])
	scored.sort_custom(func(a, b): return a[0] > b[0])
	return scored.slice(0, n).map(func(e): return e[1])


static func _num(v: float, money: bool) -> String:
	var s := GameState.format_money(int(round(v)))
	return s if money else s.replace("£", "")


static func _nice_date(iso: String) -> String:
	var p := iso.split("-")
	if p.size() != 3:
		return iso
	var mi := clampi(int(p[1]) - 1, 0, 11)
	return "%d %s %s" % [int(p[2]), MONTHS[mi], p[0]]
