extends PaneApp
## Premises & Equipment: browse the 7-rung property ladder and move; buy, place, move and store equipment.
## Nothing is locked by building size — only cost (and floor space).

const THUMB_DIR := "res://assets/textures/ui/buildings/"

var _tab := 0
var _list: VBoxContainer
var _confirm := ""


func build() -> void:
	add_child(tabs(["Premises", "Equipment", "Buy equipment"], func(i):
		_tab = i
		_confirm = ""
		refresh()))
	var sl := scroll_list()
	add_child(sl[0])
	_list = sl[1]


func refresh() -> void:
	clear(_list)
	match _tab:
		0: _premises()
		1: _equipment()
		2: _shop()


func _premises() -> void:
	var cur := Game.state.building()
	_list.add_child(para("You're at %s — %s. Bigger premises = more floor, power and loading bays, and more rent. Moving takes your stock and equipment with you; you can rearrange afterwards." % [cur.get("name", ""), cur.get("address", "")], 17, SUB, 1000))
	for id in Content.building_order:
		var b: Dictionary = Content.building(id)
		var here: bool = id == Game.state.building_id
		var c := card(Color(0.93, 0.97, 1.0) if here else CARD)
		var h := HBoxContainer.new()
		h.add_theme_constant_override("separation", 20)
		c.add_child(h)
		var thumb_path: String = THUMB_DIR + id + ".png"
		if ResourceLoader.exists(thumb_path):
			var tr := TextureRect.new()
			tr.texture = load(thumb_path)
			tr.custom_minimum_size = Vector2(300, 170)
			tr.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
			tr.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_COVERED
			h.add_child(tr)
		else:
			h.add_child(pill(" Tier %d " % int(b.tier), Color(0.2, 0.6, 0.6), Color.WHITE, 22))
		var v := VBoxContainer.new()
		v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		h.add_child(v)
		var t := HBoxContainer.new()
		v.add_child(t)
		t.add_child(l("%d · %s" % [int(b.tier), b.name], 26))
		if here:
			t.add_child(pill("YOU ARE HERE", GREEN))
		v.add_child(l(b.get("address", ""), 16, SUB))
		v.add_child(para(b.get("blurb", ""), 17, INK, 600))
		v.add_child(l("%d m² floor · %d kW power · %d loading bay%s · %d door%s" % [int(b.floor_m2), int(b.power_kw), int(b.loading_bays), "" if int(b.loading_bays) == 1 else "s", int(b.doors), "" if int(b.doors) == 1 else "s"], 16, SUB))
		var right := VBoxContainer.new()
		right.alignment = BoxContainer.ALIGNMENT_CENTER
		right.custom_minimum_size.x = 260
		h.add_child(right)
		right.add_child(l("%s / week" % GameState.format_money(int(b.rent_weekly)), 24))
		right.add_child(l("+ %s utilities" % GameState.format_money(int(b.utilities_weekly)), 16, SUB))
		if not here:
			var cost := int(b.get("move_fee", 0))
			right.add_child(l("Move cost %s" % GameState.format_money(cost), 16, AMBER))
			var afford := Game.state.cash - cost >= int(Content.econ("overdraft_warning", -500))
			if _confirm == id:
				right.add_child(button("Confirm move", func():
					_confirm = ""
					Game.request("move_building", {"building": id})
					if os and os.pc:
						os.pc.leave(), GREEN))
				right.add_child(button("Cancel", func():
					_confirm = ""
					refresh(), Color(0.55, 0.57, 0.6)))
			else:
				right.add_child(button("Move here" if afford else "Can't afford", func():
					_confirm = id
					refresh(), BLUE, not afford))
		_list.add_child(c)


func _equipment() -> void:
	_list.add_child(para("Tip: look at any equipment and press [F] to pick it up and move it. Stored equipment keeps its contents.", 17, SUB, 1000))
	var ids: Array = Game.state.fixtures.keys()
	ids.sort()
	for id in ids:
		var f: Dictionary = Game.state.fixtures[id]
		var def := Content.fixture(f.type)
		var c := card()
		var h := HBoxContainer.new()
		c.add_child(h)
		var v := VBoxContainer.new()
		v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		h.add_child(v)
		v.add_child(l(str(f.get("label", id)), 20))
		var n_items := Game.state.fixture_items(id).size()
		v.add_child(l("%s · %s%s" % [def.get("name", f.type), "Placed" if f.placed else "In storage", ("  ·  %d item(s) on it" % n_items) if n_items > 0 else ""], 15, GREEN if f.placed else AMBER))
		h.add_child(button("Move" if f.placed else "Place", func(): _start_place(id)))
		if f.placed:
			h.add_child(button("Store", func(): Game.request("store_fixture", {"id": id}), Color(0.55, 0.57, 0.6), n_items > 0))
		_list.add_child(c)


func _shop() -> void:
	_list.add_child(para("Equipment is delivered straight away and placed by you. Floor space is the only limit.", 17, SUB, 1000))
	for type in Content.fixtures:
		var def: Dictionary = Content.fixture(type)
		var c := card()
		var h := HBoxContainer.new()
		h.add_theme_constant_override("separation", 16)
		c.add_child(h)
		var v := VBoxContainer.new()
		v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		h.add_child(v)
		v.add_child(l(def.name, 20))
		v.add_child(para(def.get("desc", ""), 15, SUB, 700))
		var fp: Array = def.get("footprint", [1, 1])
		h.add_child(l("%.1f × %.1f m" % [float(fp[0]), float(fp[1])], 15, SUB))
		h.add_child(l("£%d" % int(def.price), 22))
		var afford: bool = Game.state.cash - int(def.price) >= int(Content.econ("overdraft_warning", -500))
		h.add_child(button("Buy & place", func(): _buy(type), GREEN, not afford))
		_list.add_child(c)


func _buy(type: String) -> void:
	var before := Game.state.fixtures.keys()
	Game.request("buy_fixture", {"type": type})
	await get_tree().create_timer(0.15).timeout
	for id in Game.state.fixtures:
		if not id in before:
			_start_place(id)
			return


func _start_place(id: String) -> void:
	if os and os.pc:
		os.pc.leave_then(func(p): p.start_placement(id))
