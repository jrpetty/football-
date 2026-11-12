extends PaneApp
## Flogit — the marketplace. List graded stock, set prices (cheaper sells faster), watch orders roll in.

const FLOGIT_PINK := Color(0.93, 0.28, 0.5)
const FLOGIT_YEL := Color(1.0, 0.78, 0.15)

var _tab := 0
var _list: VBoxContainer
var _prices := {}


func build() -> void:
	var banner := PanelContainer.new()
	banner.add_theme_stylebox_override("panel", UiStyle.box(FLOGIT_PINK, 10, 14))
	add_child(banner)
	var bh := HBoxContainer.new()
	banner.add_child(bh)
	var logo := l("flogit", 34, Color.WHITE)
	bh.add_child(logo)
	var tag := l("   Sell it. Ship it. Sorted.", 18, Color(1, 1, 1, 0.85))
	tag.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	bh.add_child(tag)
	_badge = l("", 20, FLOGIT_YEL)
	bh.add_child(_badge)
	add_child(tabs(["Ready to list", "Live listings", "Orders", "Reviews"], func(i):
		_tab = i
		refresh()))
	var sl := scroll_list()
	add_child(sl[0])
	_list = sl[1]


var _badge: Label


func refresh() -> void:
	clear(_list)
	# Seller badge: average review stars (the reputation-based rating until the first review lands).
	var rs := Reviews.summary(Game.state)
	if int(rs.count) > 0:
		_badge.text = "★ %.1f  ·  %d review%s" % [float(rs.avg), int(rs.count), "" if int(rs.count) == 1 else "s"]
	else:
		var stars := clampi(int(round(Game.state.reputation / 20.0)), 0, 5)
		_badge.text = "Seller rating " + "★".repeat(stars) + "☆".repeat(5 - stars)
	match _tab:
		0: _ready_to_list()
		1: _live()
		2: _orders()
		3: _reviews(rs)


func _ready_to_list() -> void:
	var units := Game.state.units_in_stock().filter(func(i): return i.unit.grade != "" and not i.unit.listed and i.unit.order_id == 0 and i.unit.contract == 0)
	var top := HBoxContainer.new()
	_list.add_child(top)
	var info := para("Prices start at the market guide for the grade. Under-price to sell fast; over-price and it sits. Fee %d%% + £%d courier per sale." % [int(float(Content.econ("flogit_fee_pct", 0.1)) * 100), int(Content.econ("flogit_postage", 9))], 16, SUB, 800)
	info.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(info)
	top.add_child(button("List all (%d)" % units.size(), func(): Game.request("list_all"), FLOGIT_PINK, units.is_empty()))
	if units.is_empty():
		_list.add_child(para("Nothing graded and unlisted. Test → (repair) → image → grade, then list here.", 19, SUB, 800))
	for it: Item in units:
		_list.add_child(_unit_row(it, false))


func _live() -> void:
	var units := Game.state.units_in_stock().filter(func(i): return i.unit.listed)
	if units.is_empty():
		_list.add_child(para("No live listings.", 19))
	var base := float(Content.econ("flogit_base_rate_per_hour", 0.09)) + Game.state.reputation * float(Content.econ("flogit_rep_rate_bonus", 0.003))
	_list.add_child(l("Buyer traffic: ~%.0f%% chance per listing per hour at guide price (grows with reputation). Price below guide to sell faster." % (base * 100), 15, SUB))
	for it: Item in units:
		_list.add_child(_unit_row(it, true))


func _unit_row(it: Item, live: bool) -> Control:
	var u := it.unit
	var c := card()
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 14)
	c.add_child(h)
	h.add_child(pill(" %s " % ("D" if u.grade == "S" else u.grade), grade_colour(u.grade), Color.WHITE, 18))
	var v := VBoxContainer.new()
	v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(v)
	v.add_child(l(u.display_name() + ("  — Spares/Repairs" if u.grade == "S" else ""), 19))
	v.add_child(l(u.spec_line() + " · " + u.cosmetic_text(), 14, SUB))
	var guide := Valuation.market_value(u, u.grade)
	var ratio := float(guide) / maxf(float(u.list_price), 1.0)
	var speed := "Fast" if ratio > 1.1 else ("Normal" if ratio > 0.92 else ("Slow" if ratio > 0.75 else "Very slow"))
	var p := Sim.sale_chance_per_hour(Game.state, u)
	var eta := "likely to sell in ~%s" % ("%d min" % int(60.0 / maxf(p, 0.01)) if p >= 1.0 else ("%.0f working hours" % (1.0 / maxf(p, 0.01))))
	v.add_child(l("Guide £%d · %s · %s" % [guide, speed, eta], 14, GREEN if ratio > 1.0 else (AMBER if ratio > 0.8 else RED)))
	var price := SpinBox.new()
	price.min_value = 1
	price.max_value = 5000
	price.prefix = "£"
	price.value = u.list_price
	price.custom_minimum_size.x = 130
	price.value_changed.connect(func(val): Game.request("set_price", {"item": it.id, "price": int(val)}))
	h.add_child(price)
	if live:
		h.add_child(button("Withdraw", func(): Game.request("list", {"item": it.id, "listed": false}), Color(0.55, 0.57, 0.6)))
	else:
		h.add_child(button("List", func(): Game.request("list", {"item": it.id, "listed": true}), FLOGIT_PINK))
	return c


func _reviews(rs: Dictionary) -> void:
	## Every delivered order gets stars and a comment a few working hours after the courier takes it.
	var top := HBoxContainer.new()
	top.add_theme_constant_override("separation", 18)
	_list.add_child(top)
	var badge := card()
	var bv := VBoxContainer.new()
	badge.add_child(bv)
	bv.add_child(l("%.1f" % float(rs.avg) if int(rs.count) > 0 else "—", 44, FLOGIT_PINK))
	bv.add_child(l(Reviews.stars_text(int(round(float(rs.avg)))), 22, FLOGIT_YEL.darkened(0.15)))
	bv.add_child(l("%d review%s" % [int(rs.count), "" if int(rs.count) == 1 else "s"], 15, SUB))
	top.add_child(badge)
	var dist := VBoxContainer.new()
	dist.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	dist.add_theme_constant_override("separation", 4)
	top.add_child(dist)
	for s in range(5, 0, -1):
		var n := int(rs.dist[s - 1])
		var h := HBoxContainer.new()
		h.add_theme_constant_override("separation", 10)
		h.add_child(l("%d ★" % s, 16, INK))
		var m := InsightsUI._Meter.new()
		m.frac = float(n) / maxf(float(rs.count), 1.0)
		m.colour = FLOGIT_YEL.darkened(0.1) if s >= 4 else (AMBER if s == 3 else RED)
		m.custom_minimum_size = Vector2(320, 14)
		m.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		h.add_child(m)
		h.add_child(l(str(n), 15, SUB))
		dist.add_child(h)
	dist.add_child(para("Buyers judge the grade (a flaw the listing didn't mention is a one-star job), how fast it was dispatched, the price against the guide, and tired batteries. Good reviews nudge your reputation up; bad ones knock it down.", 14, SUB, 600))
	var reviewed: Array = Game.state.orders.values().filter(func(o): return not o.get("review", {}).is_empty())
	reviewed.sort_custom(func(a, b): return float(a.review.at) > float(b.review.at))
	if reviewed.is_empty():
		_list.add_child(para("No reviews yet — they arrive a few working hours after the courier collects an order.", 18, SUB, 800))
		return
	for o in reviewed.slice(0, 40):
		var r: Dictionary = o.review
		var c := card()
		var h := HBoxContainer.new()
		h.add_theme_constant_override("separation", 14)
		c.add_child(h)
		var stars := l(Reviews.stars_text(int(r.stars)), 22, FLOGIT_YEL.darkened(0.15) if int(r.stars) >= 4 else (AMBER if int(r.stars) == 3 else RED))
		stars.custom_minimum_size.x = 130
		h.add_child(stars)
		var v := VBoxContainer.new()
		v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		h.add_child(v)
		var quote := para("“%s”" % r.text, 18, INK, 600)
		v.add_child(quote)
		v.add_child(l("%s · %s (%s) · £%d · %s" % [r.buyer, o.model, UnitData.grade_label(str(r.grade)), int(o.price), GameState.format_minute(float(r.at))], 14, SUB))
		h.add_child(pill(" %s " % Reviews.reason_label(str(r.reason)), GREEN if int(r.stars) >= 4 else (AMBER if int(r.stars) == 3 else RED), Color.WHITE, 13))
		_list.add_child(c)


func _orders() -> void:
	var orders := Game.state.orders.values().filter(func(o): return o.get("channel", "flogit") == "flogit")
	orders.sort_custom(func(a, b): return float(a.placed) > float(b.placed))
	if orders.is_empty():
		_list.add_child(para("No orders yet. List some stock!", 19))
	for o in orders.slice(0, 40):
		var c := card()
		var h := HBoxContainer.new()
		c.add_child(h)
		var v := VBoxContainer.new()
		v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		h.add_child(v)
		v.add_child(l("#%d  %s (%s) — £%d" % [int(o.id), o.model, UnitData.grade_label(o.grade), int(o.price)], 19))
		var msg: String = o.get("message", "")
		v.add_child(l("Buyer: %s%s" % [o.buyer, ("  ·  “%s”" % msg) if msg != "" else ""], 15, SUB))
		var status: String = o.status
		var col := SUB
		var txt := status
		match status:
			"to_pack":
				txt = "Awaiting packing · dispatch by %s" % GameState.format_minute(float(o.due))
				col = RED if Game.state.minute > float(o.due) else AMBER
			"packed":
				txt = "Packed · waiting for courier"
				col = BLUE
			"shipped":
				txt = "Shipped on time ✔"
				col = GREEN
			"shipped_late":
				txt = "Shipped late"
				col = AMBER
			"cancelled":
				txt = "Cancelled by buyer"
				col = RED
		if not o.get("review", {}).is_empty():
			txt += "  ·  " + Reviews.stars_text(int(o.review.stars))
		elif o.has("review_due"):
			txt += "  ·  review pending"
		h.add_child(l(txt, 17, col))
		_list.add_child(c)
