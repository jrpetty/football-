class_name UiInventory
extends RefCounted
## The inventory window: what you are searching (or what you are wearing) on the left, the item in question in
## the middle, your pack on the right. The lists draw themselves as one control each, so a change redraws rows
## instead of rebuilding nodes — the window never blinks, and the scroll position and selection stay put.

const FILTERS := [["all", "All"], ["weapons", "Weapons"], ["food", "Food"], ["medical", "Medical"], ["clothing", "Clothing"], ["tools", "Tools"], ["materials", "Materials"], ["other", "Other"]]
const SORTS := [["type", "Type"], ["name", "Name"], ["weight", "Weight"], ["cond", "Condition"]]
const CAT_FILTER := {"weapon": "weapons", "ammo": "weapons", "food": "food", "drink": "food", "medical": "medical", "clothing": "clothing", "tool": "tools", "book": "tools", "material": "materials", "resource": "materials", "part": "materials"}
const CAT_NAME := {"weapon": "Weapon", "ammo": "Ammunition", "food": "Food", "drink": "Drink", "medical": "Medical", "clothing": "Clothing", "tool": "Tool", "book": "Book", "material": "Material", "resource": "Resource", "part": "Component", "seed": "Seed", "misc": "Item"}
const CAT_ORDER := ["weapon", "ammo", "medical", "food", "drink", "clothing", "tool", "book", "material", "part", "resource", "seed", "misc"]
const SLOTS := [["head", "Head"], ["hands", "Hands"], ["torso", "Torso"], ["back", "Back"], ["legs", "Legs"], ["feet", "Feet"]]
const MED_USE := {"bandage": "Stops bleeding and keeps the wound clean.", "rag": "Stops bleeding, but it is not clean — disinfect and re-bandage.", "disinfect": "Cleans a wound and clears its infection.", "splint": "Lets a fracture start to heal.", "suture": "Closes a deep wound; it heals three times faster.", "painkiller": "Dulls pain for four hours.", "antibiotic": "Fights infection and sickness for twelve hours.", "serum": "Drives back the grey fever for a day."}

var hud: Hud
var filter := "all"
var sort_key := "type"
var sort_desc := false
var sel_uid := -1
var sel_side := "p"                # p: your pack · c: the container · w: something you are wearing
var sel_slot := ""
var sel_index := 0
var list_c: ItemsView = null
var list_p: ItemsView = null
var equip: EquipView = null
var strip: HotStrip = null
var _built := false
var _filter_btns: Dictionary = {}
var _sort_btns: Dictionary = {}
var _d_icon: TextureRect
var _d_name: Label
var _d_kind: Label
var _d_desc: Label
var _d_stats: GridContainer
var _d_btns: HFlowContainer
var _d_empty: Label
var _d_body: VBoxContainer
var _w_meter: UiKit.Meter
var _w_lbl: Label
var _c_lbl: Label
var _a_lbl: Label
var _a_meter: UiKit.Meter
var _sum: Dictionary = {}
var _weight := -1.0
var _cweight := -1.0
var _detail_sig := ""
var _occ_names: Dictionary = {}

func _init(h: Hud) -> void:
	hud = h

func closed() -> void:
	_built = false
	list_c = null; list_p = null; equip = null; strip = null

# ------------------------------------------------------------------ layout
func build(v: VBoxContainer, cont) -> void:
	_built = true
	sel_uid = -1; sel_side = "p"; sel_slot = ""; sel_index = 0
	_weight = -1.0; _cweight = -1.0; _detail_sig = "\u0001"
	_filter_btns.clear(); _sort_btns.clear(); _sum.clear()
	var p: Player = hud.game.player
	v.add_theme_constant_override("separation", 12)

	var bar := HBoxContainer.new(); bar.add_theme_constant_override("separation", 2)
	for f in FILTERS:
		var b := _pill(String(f[1]), String(f[0]) == filter)
		var id := String(f[0])
		b.pressed.connect(func() -> void: _set_filter(id))
		_filter_btns[id] = b; bar.add_child(b)
	bar.add_child(UiKit.spacer(0, 0, true))
	var sl := UiKit.caption("Sort by", UiTheme.FAINT); sl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	bar.add_child(sl); bar.add_child(UiKit.spacer(0, 6))
	for s in SORTS:
		var b2 := _pill(String(s[1]), String(s[0]) == sort_key)
		var sid := String(s[0])
		b2.pressed.connect(func() -> void: _set_sort(sid))
		_sort_btns[sid] = b2; bar.add_child(b2)
	v.add_child(bar)

	var cols := HBoxContainer.new(); cols.add_theme_constant_override("separation", 18)
	cols.size_flags_vertical = Control.SIZE_EXPAND_FILL
	v.add_child(cols)

	# left: the container, or what you have on
	var left := VBoxContainer.new(); left.custom_minimum_size = Vector2(410, 0); left.add_theme_constant_override("separation", 8)
	if cont != null:
		var head := HBoxContainer.new()
		head.add_child(UiKit.heading("Inside", 17, UiTheme.INK))
		_c_lbl = UiKit.body("", 14, UiTheme.DIM); _c_lbl.size_flags_horizontal = Control.SIZE_EXPAND_FILL; _c_lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
		_c_lbl.add_theme_font_override("font", UiTheme.font("num"))
		head.add_child(_c_lbl); left.add_child(head)
		left.add_child(UiKit.spacer(6))
		list_c = ItemsView.new(self, "c", cont.inv)
		left.add_child(_list_block(list_c))
		var take := hud.button("Take all", func() -> void: _take_all(), 150.0)
		var trow := HBoxContainer.new(); trow.add_child(take); trow.add_child(UiKit.spacer(0, 4))
		trow.add_child(UiKit.KeyCap.new("R")); left.add_child(trow)
	else:
		_c_lbl = null
		left.add_child(UiKit.heading("Wearing", 17, UiTheme.INK))
		equip = EquipView.new(self); left.add_child(equip)
		left.add_child(UiKit.spacer(4))
		left.add_child(UiKit.heading("Survivor", 17, UiTheme.INK))
		var grid := GridContainer.new(); grid.columns = 2; grid.add_theme_constant_override("h_separation", 18); grid.add_theme_constant_override("v_separation", 5)
		for row in [["name", "Name"], ["occ", "Occupation"], ["days", "Survived"], ["kills", "Kills"], ["warm", "Warmth from clothing"], ["carry", "Can carry"]]:
			var k := UiKit.body(String(row[1]), 14, UiTheme.DIM); k.custom_minimum_size = Vector2(190, 0)
			var val := UiKit.body("", 14, UiTheme.INK); val.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			grid.add_child(k); grid.add_child(val); _sum[row[0]] = val
		left.add_child(grid)
		left.add_child(UiKit.spacer(0, 0, true))
	cols.add_child(left)

	# middle: the item in question
	var mid := UiKit.Frame.new(Color(1, 1, 1, 0.025), 16.0); mid.marks = false; mid.grain = 0.0
	mid.custom_minimum_size = Vector2(336, 0)
	var mv := VBoxContainer.new(); mv.add_theme_constant_override("separation", 10)
	_d_empty = UiKit.body("Point at an item to see what it is and what you can do with it.", 14, UiTheme.FAINT, true)
	_d_empty.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER; _d_empty.size_flags_vertical = Control.SIZE_EXPAND_FILL
	_d_empty.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	mv.add_child(_d_empty)
	_d_body = VBoxContainer.new(); _d_body.add_theme_constant_override("separation", 10); _d_body.size_flags_vertical = Control.SIZE_EXPAND_FILL
	var dh := HBoxContainer.new(); dh.add_theme_constant_override("separation", 14)
	var tile := UiKit.Frame.new(UiTheme.SUNK, 8.0, UiTheme.LINE2); tile.marks = false; tile.grain = 0.0
	_d_icon = TextureRect.new(); _d_icon.custom_minimum_size = Vector2(60, 60); _d_icon.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	_d_icon.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	tile.add_child(_d_icon); tile.size_flags_vertical = Control.SIZE_SHRINK_BEGIN
	dh.add_child(tile)
	var dn := VBoxContainer.new(); dn.add_theme_constant_override("separation", 2); dn.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_d_name = UiKit.heading("", 20, UiTheme.INK); _d_name.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_d_kind = UiKit.caption("", UiTheme.ACCENT2, 12)
	dn.add_child(_d_name); dn.add_child(_d_kind)
	dh.add_child(dn); _d_body.add_child(dh)
	_d_desc = UiKit.body("", 14, UiTheme.DIM, true); _d_desc.custom_minimum_size = Vector2(300, 0)
	_d_body.add_child(_d_desc)
	_d_body.add_child(_rule())
	_d_stats = GridContainer.new(); _d_stats.columns = 2; _d_stats.add_theme_constant_override("h_separation", 12); _d_stats.add_theme_constant_override("v_separation", 5)
	_d_body.add_child(_d_stats)
	_d_body.add_child(UiKit.spacer(0, 0, true))
	_d_btns = HFlowContainer.new(); _d_btns.add_theme_constant_override("h_separation", 6); _d_btns.add_theme_constant_override("v_separation", 6)
	_d_body.add_child(_d_btns)
	mv.add_child(_d_body)
	mv.add_child(_rule())
	var hc := HBoxContainer.new()
	hc.add_child(UiKit.caption("Hotbar", UiTheme.DIM, 12)); hc.add_child(UiKit.spacer(0, 0, true))
	hc.add_child(UiKit.caption("click a slot, or press 1–8", UiTheme.FAINT, 11))
	mv.add_child(hc)
	strip = HotStrip.new(self); mv.add_child(strip)
	mid.add_child(mv); cols.add_child(mid)

	# right: your pack
	var right := VBoxContainer.new(); right.size_flags_horizontal = Control.SIZE_EXPAND_FILL; right.add_theme_constant_override("separation", 8)
	var rh := HBoxContainer.new()
	rh.add_child(UiKit.heading("Carrying", 17, UiTheme.INK))
	_w_lbl = UiKit.body("", 14, UiTheme.DIM); _w_lbl.size_flags_horizontal = Control.SIZE_EXPAND_FILL; _w_lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	_w_lbl.add_theme_font_override("font", UiTheme.font("num"))
	rh.add_child(_w_lbl); right.add_child(rh)
	_w_meter = UiKit.Meter.new(100.0, 4.0, UiTheme.GOOD); _w_meter.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	right.add_child(_w_meter)
	list_p = ItemsView.new(self, "p", p.inv)
	right.add_child(_list_block(list_p))
	cols.add_child(right)

	# foot: what the keys do, and whatever you are busy doing
	var foot := HBoxContainer.new(); foot.add_theme_constant_override("separation", 6)
	var hints: Array = [["LMB", "Take / store"], ["R", "Take all"], ["1-8", "Hotbar slot"], ["Arrows", "Select"], ["Enter", "Move"], ["Tab", "Close"]] if cont != null else [["LMB", "Use / equip / wear"], ["RMB", "Drop"], ["1-8", "Hotbar slot"], ["Arrows", "Select"], ["Enter", "Use"], ["H", "Health"], ["Tab", "Close"]]
	for hnt in hints:
		foot.add_child(UiKit.KeyCap.new(String(hnt[0]), 12))
		var hl := UiKit.body(String(hnt[1]), 13, UiTheme.DIM); hl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		foot.add_child(hl); foot.add_child(UiKit.spacer(0, 8))
	foot.add_child(UiKit.spacer(0, 0, true))
	_a_lbl = UiKit.body("", 14, UiTheme.ACCENT2); _a_lbl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	_a_meter = UiKit.Meter.new(140.0, 4.0, UiTheme.ACCENT); _a_meter.visible = false
	foot.add_child(_a_lbl); foot.add_child(_a_meter)
	v.add_child(foot)

	refresh()

func _rule() -> ColorRect:
	var r := ColorRect.new(); r.color = UiTheme.LINE; r.custom_minimum_size = Vector2(0, 1); r.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return r

func _pill(text: String, on: bool) -> Button:
	var b := Button.new(); b.text = text.to_upper(); b.toggle_mode = true; b.button_pressed = on
	b.focus_mode = Control.FOCUS_NONE
	b.add_theme_font_size_override("font_size", 13)
	b.custom_minimum_size = Vector2(0, 30)
	var n := UiTheme.flat_m(Color(0, 0, 0, 0), Color(0, 0, 0, 0), 0, 11, 4, 0)
	var h := UiTheme.flat_m(UiTheme.RAISED, Color(0, 0, 0, 0), 0, 11, 4, 0)
	var o := UiTheme.flat_m(Color(UiTheme.ACCENT, 0.16), UiTheme.ACCENT, 0, 11, 4, 0); o.border_width_bottom = 2
	b.add_theme_stylebox_override("normal", n); b.add_theme_stylebox_override("hover", h)
	b.add_theme_stylebox_override("pressed", o); b.add_theme_stylebox_override("hover_pressed", o)
	b.add_theme_color_override("font_color", UiTheme.DIM)
	b.pressed.connect(hud.click)
	return b

func _list_block(list: ItemsView) -> Control:
	var v := VBoxContainer.new(); v.add_theme_constant_override("separation", 0); v.size_flags_vertical = Control.SIZE_EXPAND_FILL
	var head := ListHead.new(); v.add_child(head)
	var sc := ScrollContainer.new(); sc.size_flags_vertical = Control.SIZE_EXPAND_FILL
	sc.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	list.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	list.scroll = sc
	sc.add_child(list)
	sc.get_v_scroll_bar().value_changed.connect(func(_x: float) -> void: list.queue_redraw())
	v.add_child(sc)
	return v

# ------------------------------------------------------------------ what the lists show
static func cond_of(s: Dictionary, d: Dictionary) -> float:
	if d.get("light", false): return clampf(float(s.get("dur", 0.0)) / 100.0, 0.0, 1.0)
	if d.has("weapon") and s.has("dur"): return clampf(float(s.dur) / maxf(1.0, float(d.weapon.durability)), 0.0, 1.0)
	if d.has("spoilDays"): return clampf(1.0 - float(s.get("age", 0.0)) / maxf(0.01, float(d.spoilDays)), 0.0, 1.0)
	return -1.0

func rows_for(inv: Inventory) -> Array:
	var out: Array = []
	for s in inv.stacks:
		if filter == "all" or String(CAT_FILTER.get(String(Items.def(s.id).cat), "other")) == filter:
			out.append(s)
	var key := sort_key; var desc := sort_desc
	out.sort_custom(func(a, b) -> bool:
		var da := Items.def(a.id); var db := Items.def(b.id)
		var cmp := 0.0
		match key:
			"name": cmp = float(String(da.name).naturalnocasecmp_to(String(db.name)))
			"weight": cmp = float(db.weight) * int(b.n) - float(da.weight) * int(a.n)
			"cond":
				var ca := UiInventory.cond_of(a, da); var cb := UiInventory.cond_of(b, db)
				cmp = (ca if ca >= 0.0 else 2.0) - (cb if cb >= 0.0 else 2.0)
			_: cmp = float(CAT_ORDER.find(String(da.cat)) - CAT_ORDER.find(String(db.cat)))
		if cmp == 0.0:
			cmp = float(String(da.name).naturalnocasecmp_to(String(db.name)))
		if cmp == 0.0:
			return int(a.uid) < int(b.uid)
		return cmp > 0.0 if desc else cmp < 0.0)
	return out

func _set_filter(id: String) -> void:
	filter = id
	for k in _filter_btns: _filter_btns[k].set_pressed_no_signal(k == id)
	refresh()

func _set_sort(id: String) -> void:
	sort_desc = (not sort_desc) if id == sort_key else false
	sort_key = id
	for k in _sort_btns: _sort_btns[k].set_pressed_no_signal(k == id)
	refresh()

func _list(side_: String) -> ItemsView:
	return list_c if side_ == "c" else (list_p if side_ == "p" else null)

func _inv(side_: String) -> Inventory:
	if side_ == "c":
		return hud.container.inv if hud.container != null else null
	return hud.game.player.inv

func selected():
	if sel_side == "w":
		return hud.game.player.worn.get(sel_slot)
	var inv := _inv(sel_side)
	return inv.by_uid(sel_uid) if inv != null else null

## the lists and the equipment call this when the pointer or the keys pick something
func select(side_: String, uid: int, index: int, slot := "") -> void:
	if side_ == sel_side and uid == sel_uid and slot == sel_slot:
		return
	sel_side = side_; sel_uid = uid; sel_index = index; sel_slot = slot
	hud.hover_uid = uid if side_ != "w" else -1
	hud.hover_in_container = side_ == "c"
	_redraw_all()
	_detail()

func _redraw_all() -> void:
	if list_c != null: list_c.queue_redraw()
	if list_p != null: list_p.queue_redraw()
	if equip != null: equip.queue_redraw()
	if strip != null: strip.queue_redraw()

func refresh() -> void:
	if not _built:
		return
	if list_c != null: list_c.rebuild()
	list_p.rebuild()
	# keep the selection if its stack is still there; otherwise take what is now under the pointer, or the
	# row that slid into its place
	if sel_side != "w":
		var lst := _list(sel_side)
		if lst != null and lst.index_of(sel_uid) < 0:
			var keep_side := sel_side; var keep_index := sel_index
			sel_uid = -1
			var picked := false
			for l in [list_c, list_p]:
				if l != null:
					var m: int = l.mouse_row()
					if m >= 0:
						select(l.side, int(l.rows[m].uid), m); picked = true
						break
			if not picked and not lst.rows.is_empty() and hud.hover_uid != -1:
				var i := clampi(keep_index, 0, lst.rows.size() - 1)
				select(keep_side, int(lst.rows[i].uid), i)
			if sel_uid == -1:
				hud.hover_uid = -1
	_redraw_all()
	_detail()
	tick(0.0)
	_summary()

func tick(_dt: float) -> void:
	if not _built:
		return
	var p: Player = hud.game.player
	var w := p.weight()
	if absf(w - _weight) > 0.005:
		_weight = w
		var cap := p.capacity(); var r := w / maxf(1.0, cap)
		_w_lbl.text = ("%.1f / %.0f kg" % [w, cap]) + ("   OVERLOADED" if r > 1.0 else "")
		_w_lbl.add_theme_color_override("font_color", UiTheme.BAD if r > 1.0 else (UiTheme.WARN if r > 0.85 else UiTheme.DIM))
		_w_meter.set_value(minf(1.0, r), UiTheme.BAD if r > 1.0 else (UiTheme.WARN if r > 0.85 else UiTheme.GOOD))
		_summary()
	if _c_lbl != null and hud.container != null:
		var inv: Inventory = hud.container.inv
		var cw := inv.weight()
		if absf(cw - _cweight) > 0.005:
			_cweight = cw
			_c_lbl.text = ("%.1f kg" % cw) if inv.capacity == INF else ("%.1f / %.0f kg" % [cw, inv.capacity])

func _summary() -> void:
	if _sum.is_empty():
		return
	var p: Player = hud.game.player
	if _occ_names.is_empty():
		var data = World.read_json("res://data/skills.json")
		if data is Dictionary:
			for o in data.get("occupations", []): _occ_names[String(o.id)] = String(o.name)
	_sum["name"].text = p.name
	_sum["occ"].text = String(_occ_names.get(p.occupation, p.occupation.capitalize()))
	_sum["days"].text = "%d days, %d hours" % [int(p.stats.hours_survived / 24.0), int(fmod(p.stats.hours_survived, 24.0))]
	_sum["kills"].text = str(p.kills)
	_sum["warm"].text = "%d%%" % int(round(p.insulation() * 100.0))
	_sum["carry"].text = "%.0f kg" % p.capacity()

func show_action(text: String, progress: float) -> void:
	if not _built or _a_lbl == null or not is_instance_valid(_a_lbl):
		return
	if _a_lbl.text != text: _a_lbl.text = text
	if _a_meter.visible != (text != ""): _a_meter.visible = text != ""
	_a_meter.set_value(progress)

# ------------------------------------------------------------------ the middle pane
func _detail() -> void:
	if not _built:
		return
	var s = selected()
	var p: Player = hud.game.player
	var sig := "-"
	if s != null:
		sig = "%s|%d|%d|%d|%s|%s|%s|%s" % [sel_side, int(s.uid), int(s.n), int(float(s.get("dur", 0.0))), str(s.get("ammo", 0)), str(snappedf(float(s.get("age", 0.0)), 0.1)), str(p.equipped), str(hud.container != null)]
	if sig == _detail_sig:
		return
	_detail_sig = sig
	_d_empty.visible = s == null; _d_body.visible = s != null
	if s == null:
		return
	var d := Items.def(s.id)
	_d_icon.texture = hud.icon("item_" + String(s.id))
	_d_name.text = String(d.name).to_upper()
	var held: bool = sel_side == "p" and p.equipped != null and int(p.equipped) == int(s.uid)
	_d_kind.text = (String(CAT_NAME.get(String(d.cat), "Item")) + ("  ·  in hand" if held else ("  ·  worn" if sel_side == "w" else ""))).to_upper()
	_d_desc.text = String(d.get("desc", ""))
	_d_desc.visible = _d_desc.text != ""
	for c in _d_stats.get_children(): c.queue_free()
	for row in _stats(s, d, p):
		var k := UiKit.body(String(row[0]), 13, UiTheme.DIM); k.custom_minimum_size = Vector2(118, 0)
		var val := UiKit.body(String(row[1]), 13, row[2] if row.size() > 2 else UiTheme.INK, true); val.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		val.custom_minimum_size = Vector2(170, 0)
		_d_stats.add_child(k); _d_stats.add_child(val)
	for c in _d_btns.get_children(): c.queue_free()
	var uid := int(s.uid)
	var n := int(s.n)
	if sel_side == "c":
		_act("Take", func() -> void: hud.move_item.emit(uid, false), true)
		if n > 1: _act("Take 1", func() -> void: _move_one(uid, false))
	elif sel_side == "w":
		var slot := sel_slot
		_act("Take off", func() -> void: take_off(slot), true)
	else:
		var verb := use_verb(s, d, p)
		if hud.container != null:
			_act("Store", func() -> void: hud.move_item.emit(uid, true), true)
			if n > 1: _act("Store 1", func() -> void: _move_one(uid, true))
		if verb != "":
			_act(verb, func() -> void: hud.use_item.emit(uid), hud.container == null)
		if n > 1:
			_act("Drop 1", func() -> void: _drop_one(uid))
			_act("Drop all", func() -> void: hud.drop_item.emit(uid))
		else:
			_act("Drop", func() -> void: hud.drop_item.emit(uid))

func _act(text: String, cb: Callable, primary := false) -> void:
	var b := hud.button(text, cb, 0.0)
	b.custom_minimum_size = Vector2(0, 34)
	if primary:
		b.add_theme_stylebox_override("normal", UiTheme.flat_m(Color(UiTheme.ACCENT, 0.1), UiTheme.ACCENT, 1, 16, 7))
		b.add_theme_color_override("font_color", UiTheme.ACCENT2)
	_d_btns.add_child(b)

static func use_verb(s: Dictionary, d: Dictionary, p: Player) -> String:
	var id := String(s.id)
	if d.has("weapon") or id == "flashlight":
		return "Put away" if (p.equipped != null and int(p.equipped) == int(s.uid)) else "Equip"
	if d.has("cloth"): return "Wear"
	match String(d.cat):
		"food": return "Eat"
		"drink": return "Drink"
		"medical": return "Use"
		"book": return "Read"
	if id == "battery" or id == "duct_tape": return "Use"
	return ""

func _stats(s: Dictionary, d: Dictionary, p: Player) -> Array:
	var out: Array = []
	var w = d.get("weapon")
	if w != null:
		var gun: bool = w.has("ammo")
		out.append(["Damage", ("%d × %d" % [int(w.damage), int(w.pellets)]) if w.has("pellets") else str(int(w.damage))])
		if gun:
			out.append(["Range", "%d m" % int(w.range)])
			out.append(["Loaded", "%d of %d" % [int(s.get("ammo", 0)), int(w.mag)], UiTheme.BAD if int(s.get("ammo", 0)) == 0 else UiTheme.INK])
			out.append(["Takes", "%s (you carry %d)" % [String(Items.def(String(w.ammo)).name), p.inv.count(String(w.ammo))]])
			out.append(["Heard from", "%d m" % int(w.noise), UiTheme.WARN])
		else:
			out.append(["Reach", "%.1f m" % float(w.range)])
			out.append(["Speed", "%.1f swings a second" % float(w.speed)])
			out.append(["Tires you", "%d stamina a swing" % int(w.stamina)])
			if w.get("twoHanded", false): out.append(["Grip", "Two hands"])
			if w.has("tree"): out.append(["Also", "Fells trees"])
			if w.has("mine"): out.append(["Also", "Breaks rock and ore"])
		if s.has("dur") and not d.get("light", false):
			var pct := float(s.dur) / maxf(1.0, float(w.durability)) * 100.0
			out.append(["Condition", "%d%%" % int(round(pct)), UiTheme.condition(pct)])
	if d.get("light", false):
		out.append(["Battery", "%d%%" % int(round(float(s.get("dur", 0.0)))), UiTheme.condition(float(s.get("dur", 0.0)))])
		out.append(["Use", "F switches it on. It makes you easy to see."])
	if d.has("cloth"):
		var c: Dictionary = d.cloth
		out.append(["Worn on", String(c.slot).capitalize()])
		out.append(["Warmth", "%d%%" % int(round(float(c.get("insul", 0.0)) * 100.0))])
		out.append(["Stops bites", "%d%%" % int(round(float(c.get("bite", 0.0)) * 100.0))])
		out.append(["Stops scratches", "%d%%" % int(round(float(c.get("scratch", 0.0)) * 100.0))])
		if float(c.get("capacity", 0.0)) > 0.0: out.append(["Carry", "+%d kg" % int(c.capacity), UiTheme.GOOD])
	if d.has("hunger"): out.append(["Food", "%+d" % int(d.hunger), UiTheme.GOOD if float(d.hunger) > 0.0 else UiTheme.BAD])
	if d.has("thirst"): out.append(["Water", "%+d" % int(d.thirst), UiTheme.GOOD if float(d.thirst) > 0.0 else UiTheme.BAD])
	if float(d.get("mood", 0.0)) != 0.0: out.append(["Mood", "%+d" % int(d.mood)])
	if d.has("spoilDays"):
		var left := float(d.spoilDays) - float(s.get("age", 0.0))
		out.append(["Keeps", ("%.1f more days" % left) if left > 0.0 else "Rotten — it will make you ill", UiTheme.condition(left / float(d.spoilDays) * 100.0)])
	elif String(d.cat) == "food" or String(d.cat) == "drink":
		out.append(["Keeps", "Does not spoil"])
	if float(d.get("sick", 0.0)) > 0.0: out.append(["Risk", "May make you ill", UiTheme.WARN])
	if d.has("med"): out.append(["Use", String(MED_USE.get(String(d.med), "Medical supply."))])
	if d.has("skillBook"): out.append(["Use", "Triples what you learn in %s for a day." % String(d.skillBook).capitalize()])
	if d.has("tools"): out.append(["Works as", ", ".join(PackedStringArray(d.tools)).capitalize()])
	if d.has("fuel"): out.append(["Burns", "Fuel value %s" % str(d.fuel)])
	if d.has("cookTo"): out.append(["Cooking", "Can be cooked"])
	if d.has("crop"): out.append(["Use", "Plant it in tilled soil."])
	if String(s.id) == "battery": out.append(["Use", "Recharges your flashlight."])
	if String(s.id) == "duct_tape": out.append(["Use", "Repairs the weapon in your hands."])
	var each := float(d.weight)
	out.append(["Weight", ("%.2f kg" % each) if int(s.n) <= 1 else ("%.2f kg  ×%d  =  %.1f kg" % [each, int(s.n), each * int(s.n)])])
	return out

# ------------------------------------------------------------------ actions
func row_clicked(side_: String, uid: int, button: int) -> void:
	hud.click()
	if side_ == "c":
		hud.move_item.emit(uid, false)
	elif button == MOUSE_BUTTON_RIGHT:
		if hud.container != null: hud.move_item.emit(uid, true)
		else: hud.drop_item.emit(uid)
	else:
		if hud.container != null: hud.move_item.emit(uid, true)
		else: hud.use_item.emit(uid)

func _move_one(uid: int, to_container: bool) -> void:
	var g = hud.game
	if hud.container == null or not g.has_method("transfer"):
		return
	if to_container: g.transfer(g.player.inv, hud.container.inv, uid, 1)
	else: g.transfer(hud.container.inv, g.player.inv, uid, 1)

func _drop_one(uid: int) -> void:
	if hud.game.has_method("drop_stack"): hud.game.drop_stack(uid, 1)
	else: hud.drop_item.emit(uid)

func _take_all() -> void:
	if hud.take_all.get_connections().is_empty() and hud.game.has_method("_take_all"):
		hud.game._take_all()
	else:
		hud.take_all.emit()

func assign(uid: int, slot: int) -> void:
	if hud.game.player.inv.by_uid(uid) == null:
		return
	hud.click()
	if hud.assign_hotbar.get_connections().is_empty() and hud.game.has_method("_assign_hotbar"):
		hud.game._assign_hotbar(uid, slot)
	else:
		hud.assign_hotbar.emit(uid, slot)
		hud.refresh()

func take_off(slot: String) -> void:
	var p: Player = hud.game.player
	var s = p.worn.get(slot)
	if s == null:
		return
	p.worn[slot] = null
	s.uid = int(s.get("uid", Inventory.new_uid()))
	p.inv.stacks.append(s)
	Sfx.play("zipper", {"volume": 0.4})
	sel_side = "p"; sel_uid = -1; sel_slot = ""
	hud.refresh()

func key(ev: InputEventKey) -> bool:
	if not _built:
		return false
	match ev.physical_keycode:
		KEY_UP: _step(-1); return true
		KEY_DOWN: _step(1); return true
		KEY_LEFT, KEY_RIGHT:
			if list_c != null and not ev.echo:
				var to := "c" if ev.physical_keycode == KEY_LEFT else "p"
				var l := _list(to)
				if not l.rows.is_empty():
					var i := clampi(sel_index, 0, l.rows.size() - 1)
					select(to, int(l.rows[i].uid), i); l.reveal(i)
			return true
		KEY_ENTER, KEY_KP_ENTER:
			if not ev.echo and sel_side != "w" and selected() != null:
				row_clicked(sel_side, sel_uid, MOUSE_BUTTON_LEFT)
			elif not ev.echo and sel_side == "w":
				take_off(sel_slot)
			return true
		KEY_DELETE, KEY_BACKSPACE:
			if not ev.echo and sel_side == "p" and selected() != null:
				row_clicked("p", sel_uid, MOUSE_BUTTON_RIGHT)
			return true
	return false

func _step(dir: int) -> void:
	var side_ := sel_side if sel_side != "w" else "p"
	var l := _list(side_)
	if l == null or l.rows.is_empty():
		l = list_p if l == list_c else list_c
		if l == null or l.rows.is_empty():
			return
	var cur := l.index_of(sel_uid)
	var i := clampi((cur + dir) if cur >= 0 else (0 if dir > 0 else l.rows.size() - 1), 0, l.rows.size() - 1)
	select(l.side, int(l.rows[i].uid), i)
	l.reveal(i)

# ------------------------------------------------------------------ the pieces that draw themselves
class ListHead extends Control:
	func _init() -> void:
		custom_minimum_size = Vector2(0, 22); mouse_filter = Control.MOUSE_FILTER_IGNORE
	func _draw() -> void:
		var w := size.x - 6.0
		UiKit.text(self, "cap", Vector2(46, 2), "ITEM", 11, UiTheme.FAINT, HORIZONTAL_ALIGNMENT_LEFT, -1, false)
		UiKit.text(self, "cap", Vector2(w - 160, 2), "QTY", 11, UiTheme.FAINT, HORIZONTAL_ALIGNMENT_RIGHT, 36, false)
		UiKit.text(self, "cap", Vector2(w - 116, 2), "STATE", 11, UiTheme.FAINT, HORIZONTAL_ALIGNMENT_LEFT, -1, false)
		UiKit.text(self, "cap", Vector2(w - 50, 2), "KG", 11, UiTheme.FAINT, HORIZONTAL_ALIGNMENT_RIGHT, 42, false)
		draw_rect(Rect2(0, size.y - 1.0, size.x, 1.0), UiTheme.LINE)

class ItemsView extends Control:
	const ROW := 38.0
	var ui: UiInventory
	var side := "p"
	var inv: Inventory
	var rows: Array = []
	var scroll: ScrollContainer = null

	func _init(u: UiInventory, side_: String, inv_: Inventory) -> void:
		ui = u; side = side_; inv = inv_
		mouse_filter = Control.MOUSE_FILTER_STOP
		custom_minimum_size = Vector2(0, ROW)

	func rebuild() -> void:
		rows = ui.rows_for(inv)
		custom_minimum_size.y = maxf(ROW * rows.size(), ROW * 2.0)
		queue_redraw()

	func index_of(uid: int) -> int:
		for i in rows.size():
			if int(rows[i].uid) == uid: return i
		return -1

	func mouse_row() -> int:
		if scroll == null or not is_inside_tree() or not scroll.get_global_rect().has_point(scroll.get_global_mouse_position()):
			return -1
		var m := get_local_mouse_position()
		if m.x < 0.0 or m.x > size.x:
			return -1
		var i := int(floor(m.y / ROW))
		return i if (i >= 0 and i < rows.size()) else -1

	func reveal(i: int) -> void:
		if scroll == null:
			return
		var top := i * ROW; var view := scroll.size.y
		if top < scroll.scroll_vertical: scroll.scroll_vertical = int(top)
		elif top + ROW > scroll.scroll_vertical + view: scroll.scroll_vertical = int(top + ROW - view)

	func _gui_input(ev: InputEvent) -> void:
		if ev is InputEventMouseMotion:
			var i := int(floor(ev.position.y / ROW))
			if i >= 0 and i < rows.size():
				ui.select(side, int(rows[i].uid), i)
		elif ev is InputEventMouseButton and ev.pressed and (ev.button_index == MOUSE_BUTTON_LEFT or ev.button_index == MOUSE_BUTTON_RIGHT):
			var i2 := int(floor(ev.position.y / ROW))
			if i2 >= 0 and i2 < rows.size():
				var uid := int(rows[i2].uid)
				ui.select(side, uid, i2)
				accept_event()
				ui.row_clicked(side, uid, ev.button_index)

	func _draw() -> void:
		var w := size.x - 6.0
		if rows.is_empty():
			var msg := "Nothing of that kind." if ui.filter != "all" and not inv.stacks.is_empty() else ("Empty." if side == "c" else "You are carrying nothing.")
			UiKit.text(self, "body", Vector2(0, 22), msg, 14, UiTheme.FAINT, HORIZONTAL_ALIGNMENT_CENTER, size.x, false)
			return
		var p: Player = ui.hud.game.player
		var top := 0.0; var bottom := INF
		if scroll != null:
			top = scroll.scroll_vertical - ROW; bottom = scroll.scroll_vertical + scroll.size.y
		for i in rows.size():
			var y := i * ROW
			if y < top or y > bottom:
				continue
			var s: Dictionary = rows[i]
			var d := Items.def(s.id)
			var sel: bool = ui.sel_side == side and ui.sel_uid == int(s.uid)
			var held: bool = side == "p" and p.equipped != null and int(p.equipped) == int(s.uid)
			if sel:
				draw_rect(Rect2(0, y, size.x, ROW - 2.0), Color(UiTheme.ACCENT, 0.15))
				draw_rect(Rect2(0, y, 2.0, ROW - 2.0), UiTheme.ACCENT)
			else:
				draw_rect(Rect2(0, y, size.x, ROW - 2.0), Color(1, 1, 1, 0.03 if i % 2 == 0 else 0.012))
			var tex: Texture2D = ui.hud.icon("item_" + String(s.id))
			if tex != null:
				draw_texture_rect(tex, Rect2(9, y + 4.0, 28, 28), false)
			var name_w := w - 166.0 - 46.0
			var hot := p.hotbar.find(int(s.uid)) if side == "p" else -1
			if hot < 0 and side == "p":
				# saves hold the hotbar as numbers that may not be ints
				for hi in p.hotbar.size():
					if p.hotbar[hi] != null and int(p.hotbar[hi]) == int(s.uid): hot = hi
			if hot >= 0: name_w -= 24.0
			if held: name_w -= 44.0
			var nm := String(d.name)
			var f := UiTheme.font("body")
			draw_string(f, Vector2(46, y + 9.0 + f.get_ascent(15)), nm, HORIZONTAL_ALIGNMENT_LEFT, name_w, 15, UiTheme.ACCENT2 if held else UiTheme.INK)
			var tx := 46.0 + minf(name_w, f.get_string_size(nm, HORIZONTAL_ALIGNMENT_LEFT, -1, 15).x) + 8.0
			if held:
				draw_rect(Rect2(tx, y + 10.0, 38.0, 17.0), Color(UiTheme.ACCENT, 0.9), false, 1.0)
				UiKit.text(self, "cap", Vector2(tx, y + 11.0), "HELD", 10, UiTheme.ACCENT2, HORIZONTAL_ALIGNMENT_CENTER, 38, false)
				tx += 44.0
			if hot >= 0:
				draw_rect(Rect2(tx, y + 10.0, 18.0, 17.0), Color(1, 1, 1, 0.1))
				UiKit.text(self, "num", Vector2(tx, y + 10.0), str(hot + 1), 12, UiTheme.INK, HORIZONTAL_ALIGNMENT_CENTER, 18, false)
			if int(s.n) > 1:
				UiKit.text(self, "num", Vector2(w - 160, y + 9.0), "×%d" % int(s.n), 14, UiTheme.ACCENT2, HORIZONTAL_ALIGNMENT_RIGHT, 36, false)
			var c := UiInventory.cond_of(s, d)
			if c >= 0.0:
				if d.has("spoilDays"):
					var word := "fresh" if c > 0.5 else ("stale" if c > 0.0 else "rotten")
					UiKit.text(self, "body", Vector2(w - 116, y + 10.0), word, 13, UiTheme.condition(c * 100.0 if c > 0.0 else 0.0), HORIZONTAL_ALIGNMENT_LEFT, -1, false)
				else:
					UiKit.bar(self, Rect2(w - 116, y + 17.0, 30, 4), c, UiTheme.condition(c * 100.0), Color(1, 1, 1, 0.1))
					UiKit.text(self, "num", Vector2(w - 82, y + 10.0), "%d%%" % int(round(c * 100.0)), 12, UiTheme.DIM, HORIZONTAL_ALIGNMENT_LEFT, -1, false)
			UiKit.text(self, "num", Vector2(w - 50, y + 10.0), "%.1f" % (float(d.weight) * int(s.n)), 13, UiTheme.DIM, HORIZONTAL_ALIGNMENT_RIGHT, 42, false)

## what you are wearing, slot by slot
class EquipView extends Control:
	const CELL := Vector2(200, 62)
	var ui: UiInventory
	var hover := -1

	func _init(u: UiInventory) -> void:
		ui = u
		custom_minimum_size = Vector2(410, CELL.y * 3 + 12)
		mouse_filter = Control.MOUSE_FILTER_STOP

	func _cell(i: int) -> Rect2:
		return Rect2((i % 2) * (CELL.x + 10.0), (i / 2) * (CELL.y + 6.0), CELL.x, CELL.y)

	func _at(pos: Vector2) -> int:
		for i in 6:
			if _cell(i).has_point(pos): return i
		return -1

	func _gui_input(ev: InputEvent) -> void:
		if ev is InputEventMouseMotion:
			var i := _at(ev.position)
			if i != hover:
				hover = i; queue_redraw()
			if i >= 0:
				var slot := String(UiInventory.SLOTS[i][0])
				var s = ui.hud.game.player.worn.get(slot)
				if s != null:
					ui.select("w", int(s.get("uid", -1)), i, slot)
		elif ev is InputEventMouseButton and ev.pressed and ev.button_index == MOUSE_BUTTON_LEFT:
			var i2 := _at(ev.position)
			if i2 >= 0:
				accept_event()
				ui.take_off(String(UiInventory.SLOTS[i2][0]))

	func _notification(what: int) -> void:
		if what == NOTIFICATION_MOUSE_EXIT and hover != -1:
			hover = -1; queue_redraw()

	func _draw() -> void:
		var p: Player = ui.hud.game.player
		for i in 6:
			var r := _cell(i)
			var slot := String(UiInventory.SLOTS[i][0])
			var s = p.worn.get(slot)
			var sel: bool = ui.sel_side == "w" and ui.sel_slot == slot and s != null
			var tile := Rect2(r.position, Vector2(CELL.y, CELL.y))
			draw_rect(tile, UiTheme.SUNK)
			draw_rect(tile, UiTheme.ACCENT if sel else (UiTheme.LINE2 if s != null else UiTheme.LINE), false, 1.0)
			if sel:
				draw_rect(Rect2(r.position + Vector2(CELL.y, 0), Vector2(r.size.x - CELL.y, r.size.y)), Color(UiTheme.ACCENT, 0.1))
			UiKit.text(self, "cap", r.position + Vector2(CELL.y + 12.0, 10.0), String(UiInventory.SLOTS[i][1]).to_upper(), 11, UiTheme.FAINT if s == null else UiTheme.DIM, HORIZONTAL_ALIGNMENT_LEFT, -1, false)
			if s != null:
				var tex: Texture2D = ui.hud.icon("item_" + String(s.id))
				if tex != null:
					draw_texture_rect(tex, Rect2(tile.position + Vector2(9, 9), tile.size - Vector2(18, 18)), false)
				var f := UiTheme.font("body")
				draw_string(f, r.position + Vector2(CELL.y + 12.0, 29.0 + f.get_ascent(15)), String(Items.def(s.id).name), HORIZONTAL_ALIGNMENT_LEFT, r.size.x - CELL.y - 16.0, 15, UiTheme.INK)
			else:
				UiKit.corners(self, tile.grow(-10.0), Color(1, 1, 1, 0.12), 6.0)
				UiKit.text(self, "body", r.position + Vector2(CELL.y + 12.0, 29.0), "Nothing", 14, UiTheme.FAINT, HORIZONTAL_ALIGNMENT_LEFT, -1, false)

## the eight hotbar slots, as they are; click one to put the selected item there
class HotStrip extends Control:
	const S := 33.0
	const G := 5.0
	var ui: UiInventory
	var hover := -1

	func _init(u: UiInventory) -> void:
		ui = u
		custom_minimum_size = Vector2(S * 8 + G * 7, S)
		mouse_filter = Control.MOUSE_FILTER_STOP
		tooltip_text = "Click a slot to put the selected item there."

	func _gui_input(ev: InputEvent) -> void:
		if ev is InputEventMouseMotion:
			var i := int(floor(ev.position.x / (S + G)))
			i = i if (i >= 0 and i < 8 and ev.position.x - i * (S + G) <= S) else -1
			if i != hover:
				hover = i; queue_redraw()
		elif ev is InputEventMouseButton and ev.pressed and ev.button_index == MOUSE_BUTTON_LEFT and hover >= 0:
			accept_event()
			if ui.sel_side == "p" and ui.sel_uid >= 0:
				ui.assign(ui.sel_uid, hover)

	func _notification(what: int) -> void:
		if what == NOTIFICATION_MOUSE_EXIT and hover != -1:
			hover = -1; queue_redraw()

	func _draw() -> void:
		var p: Player = ui.hud.game.player
		for i in 8:
			var r := Rect2(i * (S + G), 0, S, S)
			var s = p.inv.by_uid(p.hotbar[i])
			var mine: bool = s != null and ui.sel_side == "p" and int(s.uid) == ui.sel_uid
			draw_rect(r, Color(UiTheme.ACCENT, 0.18) if mine else UiTheme.SUNK)
			draw_rect(r, UiTheme.ACCENT if mine else (UiTheme.ACCENT2 if i == hover else UiTheme.LINE2), false, 1.0)
			if s != null:
				var tex: Texture2D = ui.hud.icon("item_" + String(s.id))
				if tex != null:
					draw_texture_rect(tex, Rect2(r.position + Vector2(5, 6), Vector2(S - 10, S - 10)), false)
			UiKit.text(self, "num", r.position + Vector2(3, -1), str(i + 1), 10, UiTheme.DIM, HORIZONTAL_ALIGNMENT_LEFT, -1, false)
