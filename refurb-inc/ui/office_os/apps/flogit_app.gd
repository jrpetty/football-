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
	var stars := clampi(int(round(Game.state.reputation / 20.0)), 0, 5)
	bh.add_child(l("Seller rating " + "★".repeat(stars) + "☆".repeat(5 - stars), 20, FLOGIT_YEL))
	add_child(tabs(["Ready to list", "Live listings", "Orders"], func(i):
		_tab = i
		refresh()))
	var sl := scroll_list()
	add_child(sl[0])
	_list = sl[1]


func refresh() -> void:
	clear(_list)
	match _tab:
		0: _ready_to_list()
		1: _live()
		2: _orders()


func _ready_to_list() -> void:
	var units := Game.state.units_in_stock().filter(func(i): return i.unit.grade != "" and not i.unit.listed and i.unit.order_id == 0)
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
	var base := float(Content.econ("flogit_base_rate_per_hour", 0.035)) + Game.state.reputation * float(Content.econ("flogit_rep_rate_bonus", 0.0025))
	_list.add_child(l("Buyer traffic: ~%.0f%% chance per listing per hour at guide price (grows with reputation)" % (base * 100), 15, SUB))
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
	v.add_child(l("Guide £%d · expected sale speed: %s" % [guide, speed], 14, GREEN if ratio > 1.0 else (AMBER if ratio > 0.8 else RED)))
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


func _orders() -> void:
	var orders := Game.state.orders.values()
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
		h.add_child(l(txt, 17, col))
		_list.add_child(c)
