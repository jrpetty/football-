extends PaneApp
## Stock: every unit the company holds, live — what, where, stage, grade, value. Click a row for the full record.

var _filter := 0
var _list: VBoxContainer
var _detail: VBoxContainer
var _selected := 0

const FILTERS := ["All", "Untested", "Faulty", "To image", "To grade", "In stock", "Listed", "Sold"]


func build() -> void:
	add_child(tabs(FILTERS, func(i):
		_filter = i
		refresh()))
	var split := HBoxContainer.new()
	split.size_flags_vertical = Control.SIZE_EXPAND_FILL
	split.add_theme_constant_override("separation", 16)
	add_child(split)
	var sl := scroll_list()
	sl[0].size_flags_horizontal = Control.SIZE_EXPAND_FILL
	sl[0].size_flags_stretch_ratio = 1.6
	split.add_child(sl[0])
	_list = sl[1]
	_list.add_theme_constant_override("separation", 4)
	var dsc := ScrollContainer.new()
	dsc.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	dsc.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	split.add_child(dsc)
	_detail = VBoxContainer.new()
	_detail.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	dsc.add_child(_detail)


func _matches(u: UnitData) -> bool:
	match _filter:
		1: return u.stage() == UnitData.STAGE_UNTESTED
		2: return u.stage() == UnitData.STAGE_FAULTY
		3: return u.stage() == UnitData.STAGE_TO_IMAGE
		4: return u.stage() == UnitData.STAGE_TO_GRADE
		5: return u.stage() == UnitData.STAGE_GRADED
		6: return u.stage() == UnitData.STAGE_LISTED
		7: return u.stage() == UnitData.STAGE_SOLD
	return true


func refresh() -> void:
	clear(_list)
	var units := Game.state.units_in_stock()
	var counts := {}
	var total_value := 0
	for it: Item in units:
		counts[it.unit.stage()] = int(counts.get(it.unit.stage(), 0)) + 1
		total_value += Valuation.market_value(it.unit, it.unit.grade if it.unit.grade != "" else "")
	var summary := l("%d units · est. retail value %s · %s" % [units.size(), GameState.format_money(total_value),
		", ".join(PackedStringArray(counts.keys().map(func(k): return "%s %d" % [k.replace("_", " "), counts[k]])))], 16, SUB)
	_list.add_child(summary)
	units.sort_custom(func(a: Item, b: Item): return a.id < b.id)
	var alt := false
	for it: Item in units:
		if not _matches(it.unit):
			continue
		_list.add_child(_row(it, alt))
		alt = not alt
	_show_detail()


func _location(it: Item) -> String:
	match it.loc.get("t"):
		"slot":
			var sid: String = it.loc.slot
			if sid.begins_with("i"):
				# On a pallet: wherever the pallet is (racking bay, forklift, truck, floor).
				return Logistics.unit_location(Game.state, it)
			return LevelRoot.current.slot_label(sid) if LevelRoot.current else sid
		"held":
			return "Carried by " + Net.display_name(int(it.loc.peer))
		"loose":
			return "On the floor"
		"in":
			return "Packed"
	return "—"


func _row(it: Item, alt: bool) -> Control:
	var u := it.unit
	var b := Button.new()
	b.flat = false
	b.add_theme_stylebox_override("normal", UiStyle.box(CARD_ALT if alt else CARD, 6, 10, BORDER if it.id == _selected else Color(0, 0, 0, 0)))
	b.add_theme_stylebox_override("hover", UiStyle.box(Color(0.92, 0.95, 1.0), 6, 10))
	b.add_theme_stylebox_override("pressed", UiStyle.box(Color(0.88, 0.92, 1.0), 6, 10))
	b.custom_minimum_size.y = 52
	var h := HBoxContainer.new()
	h.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.set_anchors_preset(Control.PRESET_FULL_RECT)
	h.offset_left = 12
	h.offset_right = -12
	b.add_child(h)
	var g := u.grade if u.grade != "" else "–"
	var gp := pill(" %s " % ("D" if g == "S" else g), grade_colour(u.grade) if u.grade != "" else Color(0.75, 0.77, 0.8), Color.WHITE, 16)
	gp.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_child(gp)
	var name_l := l(u.display_name(), 18)
	name_l.custom_minimum_size.x = 300
	h.add_child(name_l)
	var st := l(u.stage_text(), 15, RED if u.stage() == UnitData.STAGE_FAULTY else SUB)
	st.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(st)
	h.add_child(l(_location(it), 15, SUB))
	for c in h.get_children():
		c.mouse_filter = Control.MOUSE_FILTER_IGNORE
	b.pressed.connect(func():
		_selected = it.id
		refresh())
	return b


func _show_detail() -> void:
	clear(_detail)
	var it := Game.state.get_item(_selected)
	if it == null or it.unit == null:
		_detail.add_child(para("Select a unit to see its full record.", 18))
		return
	var u := it.unit
	var c := card()
	var v := VBoxContainer.new()
	c.add_child(v)
	v.add_child(l(u.display_name(), 24))
	v.add_child(l("Serial %s · from %s · paid £%d" % [u.serial, u.seller_name, u.cost], 15, SUB))
	v.add_child(l("Location: " + _location(it), 16))
	v.add_child(l("Condition: " + u.cosmetic_text(), 16))
	if u.tested:
		v.add_child(l("Spec: " + u.spec_line(), 16))
		v.add_child(l("Battery: %d%%" % u.battery_pct, 16))
		if u.faults.is_empty():
			v.add_child(l("Test: PASS", 16, GREEN))
		for f in u.faults:
			v.add_child(l(("✔ " if f.fixed else "✖ ") + LotGenerator.fault_text(f), 16, GREEN if f.fixed else RED))
	else:
		v.add_child(l("Internal condition unknown — not tested yet.", 16, AMBER))
	if u.grade != "":
		v.add_child(l("Grade %s · list price £%d · guide £%d" % [UnitData.grade_label(u.grade), u.list_price, Valuation.market_value(u, u.grade)], 17))
	v.add_child(HSeparator.new())
	v.add_child(l("History", 18))
	for h in u.history:
		v.add_child(para("%s — %s" % [GameState.format_minute(float(h[0])), h[1]], 14, SUB, 380))
	_detail.add_child(c)
