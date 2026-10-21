class_name AchievementsPanel
extends PanelContainer
## Main-menu achievements: this PC's collection, by group. Hidden ones stay "???" until they're unlocked.
## (In a game, the office PC's Achievements app shows live progress bars too.)

signal closed

const GOLD := Color(0.98, 0.78, 0.26)
const TILE_LOCKED := Color(0.2, 0.21, 0.25)
const MONTHS := ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

var _list: VBoxContainer
var _summary: Label
var _bar: _Bar


func _ready() -> void:
	add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG_SOLID, 14, 24))
	custom_minimum_size = Vector2(900, 0)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 12)
	add_child(v)
	var head := HBoxContainer.new()
	v.add_child(head)
	var title := UiStyle.label("Achievements", 30, UiStyle.TEXT)
	title.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	head.add_child(title)
	_summary = UiStyle.label("", 18, GOLD)
	_summary.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	head.add_child(_summary)
	_bar = _Bar.new()
	_bar.custom_minimum_size = Vector2(0, 10)
	v.add_child(_bar)
	var sc := ScrollContainer.new()
	sc.custom_minimum_size = Vector2(0, 560)
	sc.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	v.add_child(sc)
	_list = VBoxContainer.new()
	_list.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_list.add_theme_constant_override("separation", 8)
	sc.add_child(_list)
	v.add_child(UiStyle.label("Saved on this PC across every run. Steam achievements arrive with the Steam build.", 14, UiStyle.MUTED))
	var back := Button.new()
	back.text = "Back"
	back.pressed.connect(func(): closed.emit())
	v.add_child(back)
	visibility_changed.connect(func():
		if visible:
			_refresh())
	_refresh()


func _refresh() -> void:
	for c in _list.get_children():
		_list.remove_child(c)
		c.queue_free()
	var ach := Game.achievements
	var total := Achievements.defs().size()
	var have := ach.unlocked_count()
	_summary.text = "%d / %d unlocked  ·  %d%%" % [have, total, int(round(100.0 * have / maxf(total, 1)))]
	_bar.frac = float(have) / maxf(total, 1)
	_bar.queue_redraw()
	for g in Achievements.groups():
		var defs := Achievements.in_group(str(g))
		var n := defs.filter(func(d): return ach.is_unlocked(d.id)).size()
		var gh := UiStyle.label("%s  ·  %d / %d" % [g, n, defs.size()], 19, GOLD if n == defs.size() else UiStyle.TEXT)
		_list.add_child(gh)
		var grid := GridContainer.new()
		grid.columns = 2
		grid.add_theme_constant_override("h_separation", 8)
		grid.add_theme_constant_override("v_separation", 8)
		_list.add_child(grid)
		for d: Dictionary in defs:
			grid.add_child(_card(d))


func _card(def: Dictionary) -> Control:
	var ach := Game.achievements
	var unlocked := ach.is_unlocked(def.id)
	var disp := Achievements.display(def, unlocked)
	var card := PanelContainer.new()
	card.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	card.custom_minimum_size.x = 410
	card.add_theme_stylebox_override("panel", UiStyle.box(UiStyle.PANEL, 10, 10, GOLD.darkened(0.35) if unlocked else Color(0, 0, 0, 0)))
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 12)
	card.add_child(h)
	var tile := PanelContainer.new()
	tile.add_theme_stylebox_override("panel", UiStyle.box(GOLD.darkened(0.15) if unlocked else TILE_LOCKED, 10, 4))
	tile.custom_minimum_size = Vector2(54, 54)
	tile.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var g := UiStyle.label(str(disp.icon), 28, Color.WHITE)
	g.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	g.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	if not unlocked:
		g.modulate = Color(1, 1, 1, 0.35)
	tile.add_child(g)
	h.add_child(tile)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 1)
	v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(v)
	v.add_child(UiStyle.label(str(disp.name), 17, UiStyle.TEXT if unlocked else UiStyle.MUTED))
	var desc := UiStyle.label(str(disp.desc), 13, UiStyle.MUTED)
	desc.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	desc.custom_minimum_size.x = 300
	v.add_child(desc)
	if unlocked:
		v.add_child(UiStyle.label("✓ Unlocked %s" % _nice_date(str(ach.unlock_info(def.id).get("date", ""))), 13, UiStyle.GOOD))
	elif not disp.hidden:
		# Out of a game only the counters kept on this PC can show progress.
		var p := ach.progress(def.get("condition", {}), null, {})
		if p.size() == 2 and float(p[1]) > 1.0:
			var b := _Bar.new()
			b.frac = clampf(float(p[0]) / float(p[1]), 0.0, 1.0)
			b.custom_minimum_size = Vector2(220, 6)
			v.add_child(b)
	return card


static func _nice_date(iso: String) -> String:
	var p := iso.split("-")
	if p.size() != 3:
		return iso
	return "%d %s %s" % [int(p[2]), MONTHS[clampi(int(p[1]) - 1, 0, 11)], p[0]]


class _Bar extends Control:
	var frac := 0.0

	func _draw() -> void:
		var r := size.y * 0.5
		var bg := StyleBoxFlat.new()
		bg.bg_color = Color(1, 1, 1, 0.08)
		bg.set_corner_radius_all(int(r))
		draw_style_box(bg, Rect2(Vector2.ZERO, size))
		if frac > 0.0:
			var fg := StyleBoxFlat.new()
			fg.bg_color = Color(0.98, 0.78, 0.26)
			fg.set_corner_radius_all(int(r))
			draw_style_box(fg, Rect2(Vector2.ZERO, Vector2(maxf(size.x * frac, size.y), size.y)))
