extends InsightsUI
## Business: companies email in with bulk orders and custom server builds once your reputation is high enough.
## Enquiries: read the email, accept or decline (one click, the price is the price). On the go: allocate graded stock
## to bulk orders (the cheapest matching units first) and follow custom builds through the build bench. History: how
## every contract went. Everything here is a host command, so co-op partners see the same inbox.

const BIZ := Color(0.16, 0.36, 0.58)

var _tab := 0
var _bar: TabBar


func build() -> void:
	var banner := PanelContainer.new()
	banner.add_theme_stylebox_override("panel", UiStyle.box(BIZ, 10, 14))
	add_child(banner)
	var bh := HBoxContainer.new()
	banner.add_child(bh)
	bh.add_child(l("◆  Business buyers", 28, Color.WHITE))
	var tag := l("   Schools, councils, firms and IT companies — fixed prices, real deadlines.", 17, Color(1, 1, 1, 0.82))
	tag.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	tag.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	bh.add_child(tag)
	_bar = tabs(["Enquiries", "On the go", "History"], func(i):
		_tab = i
		refresh())
	add_child(_bar)
	var sl := scroll_list()
	add_child(sl[0])
	_body = sl[1]


func _contracts(statuses: Array) -> Array:
	var out: Array = Game.state.contracts.values().filter(func(c): return str(c.get("status", "")) in statuses)
	return out


func refresh() -> void:
	clear(_body)
	var offers := _contracts(["offer"])
	var active := _contracts(["active"])
	_bar.set_tab_title(0, "Enquiries (%d)" % offers.size())
	_bar.set_tab_title(1, "On the go (%d)" % active.size())
	var s := Game.state
	var life: Dictionary = s.totals.get("lifetime", {})
	var unlocked := Business.unlocked(s)
	var soonest := -1.0
	for c in active:
		if soonest < 0.0 or float(c.due) < soonest:
			soonest = float(c.due)
	_kpis([
		["Status", "Open for business" if unlocked else "Not yet", ("reputation %d" % int(s.reputation)) if unlocked else
			"needs reputation %d (you're %d)" % [int(Business.unlock_rep()), int(s.reputation)], GREEN if unlocked else AMBER],
		["Enquiries waiting", str(offers.size()), "answer before they lapse", BLUE],
		["Orders on the go", str(active.size()), ("next due %s" % GameState.format_minute(soonest)) if soonest >= 0.0 else "nothing due", INK],
		["Business revenue", _m(int(life.get("biz_revenue", 0))), "%d units · %d builds shipped" % [int(life.get("biz_units", 0)), int(life.get("builds_shipped", 0))], GREEN],
	], 4)
	if not unlocked:
		_empty("Business buyers start emailing once your reputation reaches %d (you're on %d). Ship Flogit orders on time and grade honestly to get there.\n\nThen: bulk orders (\"20 laptops, grade B or better, by Friday\") and custom server builds — fixed prices, paid on delivery, and the best margins in the trade." % [int(Business.unlock_rep()), int(s.reputation)])
		return
	match _tab:
		0: _enquiries(offers)
		1: _on_the_go(active)
		2: _history()


# ================================================================= enquiries

func _enquiries(offers: Array) -> void:
	if offers.is_empty():
		_empty("No enquiries right now. New ones arrive through the day — more often as your reputation grows.")
		return
	offers.sort_custom(func(a, b): return float(a.offered) > float(b.offered))
	for c: Dictionary in offers:
		var card_v := _card()
		var top := HBoxContainer.new()
		top.add_theme_constant_override("separation", 10)
		card_v.add_child(top)
		top.add_child(l(str(c.buyer), 22))
		top.add_child(pill(" %s " % c.get("sector", ""), BIZ, Color.WHITE, 14))
		top.add_child(pill(" %s " % ("Custom build" if c.kind == "build" else "Bulk order"), PROFIT_COL if c.kind == "build" else BLUE, Color.WHITE, 14))
		var sp := Control.new()
		sp.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		top.add_child(sp)
		top.add_child(l(_m(Business.value(c)), 26, GREEN))
		var left := BusinessStats.working_minutes(Game.state.minute, float(c.expires)) / 60.0
		card_v.add_child(l("“%s”  ·  reply within %.1f working hours" % [c.subject, left], 17, AMBER if left < 2.0 else SUB))
		card_v.add_child(para(str(c.body), 15, INK, 900))
		card_v.add_child(HSeparator.new())
		if c.kind == "bulk":
			var n := Business.eligible_units(Game.state, c).size()
			card_v.add_child(l("%s · %s each · due %s (%d working hours)" % [Business.title(c), _m(c.unit_price), GameState.format_minute(float(c.due)), int(c.hours)], 16))
			var col := GREEN if n >= int(c.qty) else (AMBER if n > 0 else RED)
			card_v.add_child(l("Matching graded stock right now: %d of %d" % [n, int(c.qty)], 15, col))
		else:
			card_v.add_child(l("%s · due %s (%d working hours) · new parts at PartsHub ≈ %s" % [c.name, GameState.format_minute(float(c.due)), int(c.hours), _m(c.parts_estimate)], 16))
			var servers := Game.state.units_in_stock().filter(func(it): return it.unit.model_id in c.spec.chassis and it.unit.order_id == 0 and it.unit.contract == 0).size()
			var chassis := int(Game.state.parts.get(Business.chassis_part(str(c.spec.chassis[0])), 0))
			card_v.add_child(l("Matching servers in stock: %d · barebones chassis in the bins: %d" % [servers, chassis], 15, GREEN if servers + chassis > 0 else SUB))
		var row := HBoxContainer.new()
		row.add_theme_constant_override("separation", 10)
		card_v.add_child(row)
		row.add_child(button("Accept", func(): Game.request("biz_accept", {"contract": int(c.id)}), GREEN))
		row.add_child(button("Decline", func(): Game.request("biz_decline", {"contract": int(c.id)}), Color(0.55, 0.57, 0.6)))


func _card() -> VBoxContainer:
	var c := card()
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 6)
	c.add_child(v)
	_body.add_child(c)
	return v


# ================================================================= on the go

func _on_the_go(active: Array) -> void:
	if active.is_empty():
		_empty("Nothing on the go. Accept an enquiry and it'll appear here.")
		return
	active.sort_custom(func(a, b): return float(a.due) < float(b.due))
	for c: Dictionary in active:
		var v := _card()
		var top := HBoxContainer.new()
		top.add_theme_constant_override("separation", 10)
		v.add_child(top)
		top.add_child(l("%s — %s" % [c.buyer, Business.title(c)], 21))
		var sp := Control.new()
		sp.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		top.add_child(sp)
		var late: bool = c.get("late", false) or Game.state.minute > float(c.due)
		var left := BusinessStats.working_minutes(Game.state.minute, float(c.due)) / 60.0
		top.add_child(pill(" LATE " if late else " %.1f working h left " % left, RED if late else (AMBER if left < 4.0 else GREEN), Color.WHITE, 15))
		top.add_child(l(_m(Business.value(c)), 22, GREEN))
		v.add_child(l("Due %s%s" % [GameState.format_minute(float(c.due)), " — late deliveries lose %d%% of the price" % int(float(Business.cfg("late_pay_cut", 0.15)) * 100) if late else ""], 15, RED if late else SUB))
		if c.kind == "bulk":
			_bulk_progress(v, c)
		else:
			_build_progress(v, c)


func _bulk_progress(v: VBoxContainer, c: Dictionary) -> void:
	var orders := Business.live_orders(Game.state, c)
	var packed := orders.filter(func(o): return o.status == "packed").size()
	var shipped := int(c.get("shipped", 0))
	var qty := int(c.qty)
	var parts := _Split.new()
	parts.parts = [[shipped, GREEN], [packed, BLUE], [orders.size() - shipped - packed, AMBER], [qty - orders.size(), Color(0.88, 0.89, 0.91)]]
	parts.custom_minimum_size = Vector2(0, 16)
	v.add_child(parts)
	var keys := HBoxContainer.new()
	keys.add_theme_constant_override("separation", 16)
	keys.add_child(_swatch(GREEN, "Shipped %d" % shipped))
	keys.add_child(_swatch(BLUE, "Packed %d" % packed))
	keys.add_child(_swatch(AMBER, "To pack %d" % (orders.size() - shipped - packed)))
	keys.add_child(_swatch(Color(0.75, 0.77, 0.8), "Still to allocate %d" % (qty - orders.size())))
	v.add_child(keys)
	var spec := "Grade %s or better%s · %s each" % [c.grade, Business.spec_text(c), _m(c.unit_price)]
	v.add_child(l(spec, 15, SUB))
	var room := qty - orders.size()
	var elig := Business.eligible_units(Game.state, c)
	if room > 0:
		var row := HBoxContainer.new()
		row.add_theme_constant_override("separation", 10)
		v.add_child(row)
		var n := mini(room, elig.size())
		row.add_child(button("Allocate %d cheapest matching" % n if n > 0 else "No matching stock yet", func(): Game.request("biz_allocate", {"contract": int(c.id), "auto": true}), GREEN, n == 0))
		row.add_child(l("%d matching unit%s in stock — allocated units become orders to pack and courier as usual." % [elig.size(), "" if elig.size() == 1 else "s"], 14, SUB))
	var rows: Array = []
	for o: Dictionary in orders:
		var st: Array = {"to_pack": ["To pack", AMBER], "packed": ["Packed", BLUE], "shipped": ["Shipped ✔", GREEN], "shipped_late": ["Shipped late", AMBER]}.get(str(o.status), [str(o.status), SUB])
		rows.append([str(o.model), _grade_cell(str(o.grade)), [st[0], st[1]], "#%d" % int(o.id)])
	if not rows.is_empty():
		var t := VBoxContainer.new()
		v.add_child(t)
		_table(t, ["Allocated unit", "Grade", "Status", "Order"], rows, true, 0, [2])
		for o: Dictionary in orders.filter(func(x): return x.status == "to_pack").slice(0, 6):
			var it := Game.state.get_item(int(o.unit))
			if it:
				var r := HBoxContainer.new()
				r.add_child(l("  %s (%s)" % [o.model, UnitData.grade_label(str(o.grade))], 14, SUB))
				r.add_child(button("Take off the order", func(): Game.request("biz_release", {"contract": int(c.id), "item": it.id}), Color(0.55, 0.57, 0.6)))
				v.add_child(r)
	if room > 0 and not elig.is_empty():
		v.add_child(l("Or pick units yourself (cheapest guide value first):", 15, SUB))
		for it: Item in elig.slice(0, 8):
			var r := HBoxContainer.new()
			r.add_theme_constant_override("separation", 10)
			var u := it.unit
			r.add_child(pill(" %s " % u.grade, grade_colour(u.grade), Color.WHITE, 14))
			var name := l("%s · %s" % [u.display_name(), u.spec_line()], 14)
			name.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			name.clip_text = true
			r.add_child(name)
			r.add_child(l("Flogit guide %s" % _m(Valuation.market_value(u, u.grade)), 14, GREEN if Valuation.market_value(u, u.grade) <= int(c.unit_price) else AMBER))
			r.add_child(button("Add", func(): Game.request("biz_allocate", {"contract": int(c.id), "items": [it.id]}), BLUE))
			v.add_child(r)


func _build_progress(v: VBoxContainer, c: Dictionary) -> void:
	var u := Business.build_unit(Game.state, c)
	if u == null:
		v.add_child(para("No server reserved yet. Put a %s from stock on a build bench, or unbox a barebones chassis there (PartsHub → Server build)." % Business.chassis_name(c.spec), 15, AMBER, 900))
		for s in Business.spec_lines(c.spec):
			v.add_child(l("  •  " + str(s), 15))
	else:
		var rows := ServerBuild.checklist(u, c.spec)
		var t := VBoxContainer.new()
		v.add_child(t)
		var table_rows: Array = []
		for r: Dictionary in rows:
			table_rows.append([str(r.label), ["✔ done" if r.ok else ("in progress" if int(r.have) > 0 else "to do"), GREEN if r.ok else (AMBER if int(r.have) > 0 else RED)],
				str(r.get("detail", ""))])
		_table(t, ["Spec line", "Status", "Fitted now"], table_rows, true, 0, [1, 2])
		var stage := "Build it up at the build bench (hold E)."
		if ServerBuild.complete(u, c.spec):
			stage = "Built. Next: test it." if not u.tested else ("Faulty — repair it." if u.has_open_faults() else ("Next: wipe & image it." if not u.imaged else "Ready to pack — it's an order now."))
		v.add_child(l("%s (%s)" % [stage, u.serial], 15, GREEN if ServerBuild.complete(u, c.spec) else SUB))
	var need := Business.shopping_list(Game.state, c)
	if need.is_empty():
		v.add_child(l("Everything it still needs is in the parts bins.", 15, GREEN))
	else:
		var cost := 0
		var names: PackedStringArray = []
		for n: Dictionary in need:
			cost += int(Content.part(n.part).get("price", 0)) * int(n.qty)
			names.append("%d× %s" % [int(n.qty), Content.part(n.part).get("name", n.part)])
		v.add_child(para("Still to get: " + ", ".join(names), 14, SUB, 900))
		var order_all := func() -> void:
			for n: Dictionary in need:
				Game.request("shop_buy", {"id": str(n.part), "qty": int(n.qty)})
		v.add_child(button("Order the missing parts (%s)" % _m(cost), order_all, BLUE, Game.state.cash - cost < int(Content.econ("overdraft_warning", -500))))
	if u != null and u.order_id == 0:
		var it := Game.state.get_item(int(c.unit))
		if it:
			v.add_child(button("Release this server", func(): Game.request("biz_release", {"contract": int(c.id), "item": it.id}), Color(0.55, 0.57, 0.6)))


# ================================================================= history

func _history() -> void:
	var done := _contracts(Business.FINISHED)
	if done.is_empty():
		_empty("No finished business yet.")
		return
	done.sort_custom(func(a, b): return float(a.get("closed", a.offered)) > float(b.get("closed", b.offered)))
	var won := done.filter(func(c): return c.status in ["complete", "complete_late"])
	var on_time := won.filter(func(c): return c.status == "complete").size()
	var revenue := 0
	for c in won:
		revenue += int(c.get("revenue", 0))
	_kpis([
		["Delivered", str(won.size()), "%d on time" % on_time, GREEN],
		["Revenue (shown)", _m(revenue), "from these contracts", INK],
		["Declined / lapsed", str(done.filter(func(c): return c.status in ["declined", "expired"]).size()), "", SUB],
		["Cancelled on you", str(done.filter(func(c): return c.status == "cancelled").size()), "too late", RED],
	], 4)
	var v := _section("Contracts", "The most recent 40.")
	var rows: Array = []
	for c: Dictionary in done:
		var col: Color = {"complete": GREEN, "complete_late": AMBER, "cancelled": RED}.get(str(c.status), SUB)
		rows.append([str(c.buyer), Business.title(c), _m(Business.value(c)), [Business.status_text(c), col],
			_m(int(c.get("revenue", 0))) if int(c.get("revenue", 0)) > 0 else "—"])
	_table(v, ["Buyer", "Order", "Value", "Outcome", "Paid"], rows, true, 0, [1, 3])
