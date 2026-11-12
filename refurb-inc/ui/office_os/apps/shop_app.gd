extends PaneApp
## PartsHub: USB tools, consumables and spare parts. Orders arrive with the next courier (≥1 hour away).

var _tab := 0
var _list: VBoxContainer
var _filter := ""
var _qty := {}

const PART_TABS := [["Equipment", ""], ["Decor", "decor"], ["Batteries", "battery"], ["Memory & storage", "ramstorage"], ["Keyboards & small parts", "small"], ["Screens & L2", "l2"], ["Board-level (L3)", "l3"], ["Server build", "build"]]


func build() -> void:
	var names: Array = []
	for t in PART_TABS:
		names.append(t[0])
	add_child(tabs(names, func(i):
		_tab = i
		refresh()))
	var row := HBoxContainer.new()
	add_child(row)
	var search := LineEdit.new()
	search.placeholder_text = "Filter by name or model (e.g. Prospect, 16GB)…"
	search.custom_minimum_size.x = 520
	search.text_changed.connect(func(t):
		_filter = t.to_lower()
		refresh())
	row.add_child(search)
	row.add_child(l("   Opening day: orders arrive in about 20 minutes." if Game.state.day_index() == 0 else "   Deliveries come with the courier — order before ~11:00 for the midday run.", 16, SUB))
	var sl := scroll_list()
	add_child(sl[0])
	_list = sl[1]


func refresh() -> void:
	clear(_list)
	var cat: String = PART_TABS[_tab][1]
	if cat == "":
		for s: Dictionary in Content.shop_items.values():
			if str(s.get("kind", "")) == "licence":
				_licence_row(s)
			else:
				_row(s.id, s.name, int(s.price), s.get("desc", ""), _stock_text_shop(s))
		return
	if cat == "decor":
		# Decor is equipment: delivered straight away and placed by you (paint and signs: Fit-out app).
		for type in Content.fixtures:
			var def: Dictionary = Content.fixture(type)
			if def.get("decor", false):
				_decor_row(type, def)
		return
	for p: Dictionary in Content.parts.values():
		var c: String = p.category
		var lvl := int(p.get("repair_level", 1))
		var ok := false
		match cat:
			"battery": ok = c == "battery"
			"ramstorage": ok = c in ["ram", "storage"]
			"small": ok = c in ["keys", "fan"]
			"l2": ok = lvl == 2
			"l3": ok = lvl == 3
			"build": ok = c in ["cpu", "raid", "chassis"] or str(p.get("fits_ram_type", "")) == "ddr4_rdimm" 				or str(p.get("fits_storage_type", "")) in ["sas_caddy", "sata_hdd"] or String(p.id).begins_with("psu_server")
		if not ok:
			continue
		if _filter != "" and not _filter in String(p.name).to_lower() and not _fits_filter(p):
			continue
		var desc := ("For custom builds · %s" % _fits_text(p)) if c in ["cpu", "raid", "chassis"] else "Repair level %d · %s" % [lvl, _fits_text(p)]
		_row(p.id, p.name, int(p.price), desc, "%d in bins" % int(Game.state.parts.get(p.id, 0)))


func _fits_filter(p: Dictionary) -> bool:
	return _filter in _fits_text(p).to_lower()


func _fits_text(p: Dictionary) -> String:
	var fits: PackedStringArray = []
	for m: Dictionary in Content.models.values():
		var match_ := false
		match p.category:
			"battery": match_ = m.get("battery_part", "") == p.id
			"keys": match_ = m.get("keycap_part", "") == p.id
			"ram": match_ = m.get("ram_type", "") == p.get("fits_ram_type", "")
			"storage": match_ = m.get("storage_type", "") == p.get("fits_storage_type", "")
			"cpu": match_ = str(p.get("cpu", "")) in m.get("cpu_options", [])
			"raid": match_ = bool(m.get("raid_slot", false))
			_: match_ = false
		if match_:
			fits.append(Content.model_display_name(m.id))
	if p.category == "chassis":
		return "New and empty: CPUs, memory, drives, PSUs and RAID card fitted at the build bench"
	if fits.is_empty():
		return "Universal / see listing"
	return "Fits " + ", ".join(fits)


func _stock_text_shop(s: Dictionary) -> String:
	match s.kind:
		"consumable":
			return "%d in stock" % int(Game.state.consumables.get(s.consumable, 0))
		"item":
			var n := Game.state.items.values().filter(func(i): return i.kind == s.item_kind).size()
			return "%d owned" % n
	return ""


func _licence_row(s: Dictionary) -> void:
	## One-off licences (night shift): bought once, yours straight away.
	var owned := Game.state.has_licence(str(s.get("licence", s.id)))
	var c := card(Color(0.97, 0.96, 1.0))
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 18)
	c.add_child(h)
	h.add_child(pill(" 🌙 ", Color(0.2, 0.22, 0.45), Color.WHITE, 22))
	var v := VBoxContainer.new()
	v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(v)
	v.add_child(l(str(s.name), 20))
	v.add_child(para(str(s.get("desc", "")), 15, SUB, 700))
	h.add_child(l("Owned ✔" if owned else "One-off", 17, GREEN if owned else SUB))
	h.add_child(l(GameState.format_money(int(s.price)), 22))
	var price := int(s.price)
	h.add_child(button("Licensed" if owned else "Buy licence", func(): Game.request("shop_buy", {"id": str(s.id)}), GREEN,
		owned or Game.state.cash - price < int(Content.econ("overdraft_warning", -500))))
	_list.add_child(c)


func _decor_row(type: String, def: Dictionary) -> void:
	var c := card()
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 18)
	c.add_child(h)
	var v := VBoxContainer.new()
	v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(v)
	v.add_child(l(str(def.name), 20))
	v.add_child(para(str(def.get("desc", "")), 15, SUB, 700))
	var owned := Game.state.fixtures.values().filter(func(f): return str(f.get("type", "")) == type).size()
	h.add_child(l("%d owned" % owned, 17, SUB))
	h.add_child(l("£%d" % int(def.price), 22))
	h.add_child(button("Buy & place", _buy_decor.bind(type), GREEN, Game.state.cash - int(def.price) < int(Content.econ("overdraft_warning", -500))))
	_list.add_child(c)


func _buy_decor(type: String) -> void:
	var before := Game.state.fixtures.keys()
	Game.request("buy_fixture", {"type": type})
	await get_tree().create_timer(0.15).timeout
	for id in Game.state.fixtures:
		if not id in before and os and os.pc:
			os.pc.leave_then(func(p): p.start_placement(id))
			return


func _row(id: String, name: String, price: int, desc: String, stock: String) -> void:
	var c := card()
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 18)
	c.add_child(h)
	var v := VBoxContainer.new()
	v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(v)
	v.add_child(l(name, 20))
	v.add_child(para(desc, 15, SUB, 700))
	h.add_child(l(stock, 17, SUB))
	h.add_child(l("£%d" % price, 22))
	var q := SpinBox.new()
	q.min_value = 1
	q.max_value = 50
	q.value = _qty.get(id, 1)
	q.value_changed.connect(func(v2): _qty[id] = int(v2))
	h.add_child(q)
	h.add_child(button("Order", func():
		Game.request("shop_buy", {"id": id, "qty": int(_qty.get(id, 1))}), GREEN, Game.state.cash - price < int(Content.econ("overdraft_warning", -500))))
	_list.add_child(c)
