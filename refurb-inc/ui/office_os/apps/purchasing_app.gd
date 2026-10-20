extends PaneApp
## LotFinder: browse bulk lots from organisations refreshing their kit. Fixed prices — no haggling.

var _tab := 0
var _list: VBoxContainer
var _confirm_lot := 0


func build() -> void:
	var head := HBoxContainer.new()
	add_child(head)
	var tb := tabs(["Available lots", "Refresh calendar", "Purchases"], func(i):
		_tab = i
		refresh())
	tb.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	head.add_child(tb)
	var disc := 0.01 * Game.state.skill_level(Game.local_player_key(), "purchasing")
	head.add_child(l("Your purchasing discount: %d%%" % int(disc * 100), 17, SUB))
	var sl := scroll_list()
	add_child(sl[0])
	_list = sl[1]


func refresh() -> void:
	clear(_list)
	match _tab:
		0: _lots()
		1: _calendar()
		2: _purchases()


static func guide_price(model_id: String, grade: String = "B") -> int:
	var m := Content.model(model_id)
	var u := UnitData.new()
	u.model_id = model_id
	u.cpu_id = m.get("ref_cpu", "")
	u.ram_gb = int(m.get("ref_ram", 8))
	u.storage_gb = int(m.get("ref_storage", 256))
	return Valuation.market_value(u, grade)


func _lots() -> void:
	var lots := Game.state.lots.values().filter(func(x): return x.status == "open")
	lots.sort_custom(func(a, b): return float(a.expires_min) < float(b.expires_min))
	if lots.is_empty():
		_list.add_child(para("No lots right now. New disposals are posted every morning.", 20))
		return
	_list.add_child(para("Prices are per unit and fixed. Cheaper lots are cheaper for a reason — read the seller's notes. Deliveries arrive by lorry the next working morning.", 17, SUB, 900))
	for lot in lots:
		_list.add_child(_lot_card(lot))


func _lot_card(lot: Dictionary) -> Control:
	var c := card()
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 24)
	c.add_child(h)
	var left := VBoxContainer.new()
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(left)
	var title := HBoxContainer.new()
	left.add_child(title)
	title.add_child(l(lot.seller_name, 24, INK))
	title.add_child(pill(lot.sector, Color(0.9, 0.92, 0.95), SUB))
	if lot.get("refresh", false):
		title.add_child(pill("SCHEDULED REFRESH", AMBER))
	left.add_child(l("%d laptops · %s · ~%d years old" % [int(lot.count), lot.summary, int(lot.age_years)], 18, INK))
	left.add_child(para("“%s”" % lot.note, 18, SUB, 600))
	var guides: PackedStringArray = []
	for mid in lot.models:
		guides.append("%s: B-grade sells ~£%d" % [Content.model_display_name(mid), guide_price(mid)])
	left.add_child(para("Market guide — " + "; ".join(guides), 15, Color(0.35, 0.45, 0.6), 600))
	var left_min := float(lot.expires_min) - Game.state.minute
	left.add_child(l("Listing closes %s" % GameState.format_minute(float(lot.expires_min)), 15, RED if left_min < 120 else SUB))
	var right := VBoxContainer.new()
	right.alignment = BoxContainer.ALIGNMENT_CENTER
	h.add_child(right)
	right.add_child(l("£%d / unit" % int(lot.unit_price), 28, INK))
	var disc := 0.01 * Game.state.skill_level(Game.local_player_key(), "purchasing")
	var total := int(round(int(lot.total_price) * (1.0 - disc)))
	right.add_child(l("Total %s" % GameState.format_money(total), 20, SUB))
	var afford := Game.state.cash - total >= int(Content.econ("overdraft_warning", -500))
	if _confirm_lot == int(lot.id):
		var row := HBoxContainer.new()
		right.add_child(row)
		row.add_child(button("Confirm purchase", func():
			_confirm_lot = 0
			Game.request("buy_lot", {"lot": int(lot.id)})
			Sfx.play_ui("cash", -6.0), GREEN))
		row.add_child(button("Cancel", func():
			_confirm_lot = 0
			refresh(), Color(0.55, 0.57, 0.6)))
	else:
		right.add_child(button("Buy lot" if afford else "Can't afford", func():
			_confirm_lot = int(lot.id)
			refresh(), BLUE, not afford))
	return c


func _calendar() -> void:
	_list.add_child(para("Organisations refresh their IT on predictable cycles. Plan your cash for the big ones.", 18, SUB, 900))
	var sim: Sim = Game.sim if Game.sim else Sim.new(Game.state)
	var today := Game.state.day_index()
	for r in sim.upcoming_refreshes():
		var c := card(CARD_ALT if r.locked else CARD)
		var h := HBoxContainer.new()
		c.add_child(h)
		var name_l := l(r.seller, 20, SUB if r.locked else INK)
		name_l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		h.add_child(name_l)
		var days := int(r.day) - today
		var when := "today" if days <= 0 else ("tomorrow" if days == 1 else "in %d days (%s)" % [days, GameState.WEEKDAYS[int(r.day) % 7].substr(0, 3)])
		h.add_child(l(when, 18, INK))
		if r.locked:
			h.add_child(pill("Needs reputation %d" % int(r.min_rep), Color(0.6, 0.6, 0.65)))
		_list.add_child(c)


func _purchases() -> void:
	var mine := Game.state.lots.values().filter(func(x): return x.status in ["bought", "delivered"])
	if mine.is_empty():
		_list.add_child(para("Nothing bought yet.", 20))
		return
	for lot in mine:
		var c := card()
		var v := VBoxContainer.new()
		c.add_child(v)
		v.add_child(l("%s — %d units — paid %s" % [lot.seller_name, int(lot.count), GameState.format_money(int(lot.get("paid", lot.total_price)))], 20))
		if lot.status == "bought":
			var eta := ""
			for ev in Game.state.schedule:
				if ev.type == "lorry" and int(ev.data.get("lot", 0)) == int(lot.id):
					eta = GameState.format_minute(float(ev.at))
			v.add_child(l("Lorry booked: %s" % eta, 17, AMBER))
		else:
			v.add_child(l("Delivered", 17, GREEN))
		_list.add_child(c)
