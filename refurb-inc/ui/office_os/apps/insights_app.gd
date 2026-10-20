extends InsightsUI
## Insights: everything about the business, in 13 sections. Highlights turns the numbers into plain-English
## findings, goals and records. Data comes from BusinessReport on the host (Game.request_report).

const NAV := [["highlights", "Highlights"], ["overview", "Overview"], ["sales", "Sales"], ["pricing", "Pricing & demand"],
	["customers", "Customers"], ["purchasing", "Purchasing"], ["quality", "Products & quality"], ["stock", "Stock"],
	["operations", "Operations"], ["orders", "Orders & service"], ["finance", "Finance"], ["people", "People & premises"],
	["timeline", "Timeline"]]
const AUTO_REFRESH_S := 5.0

var _page := 0
var _report: Dictionary = {}
var _scroll: ScrollContainer
var _title: Label
var _stamp: Label
var _nav_buttons: Array = []
var _last_request := -100.0


func build() -> void:
	var root := HBoxContainer.new()
	root.add_theme_constant_override("separation", 14)
	root.size_flags_vertical = Control.SIZE_EXPAND_FILL
	add_child(root)
	# Sidebar
	var side := VBoxContainer.new()
	side.custom_minimum_size.x = 172
	side.add_theme_constant_override("separation", 3)
	root.add_child(side)
	for i in NAV.size():
		var b := Button.new()
		b.text = "  " + NAV[i][1]
		b.alignment = HORIZONTAL_ALIGNMENT_LEFT
		b.add_theme_font_size_override("font_size", 17)
		b.custom_minimum_size.y = 40
		b.pressed.connect(func():
			Sfx.play_ui("ui_click", -10.0)
			select(i))
		side.add_child(b)
		_nav_buttons.append(b)
	# Content
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
	top.add_child(button("Refresh", _request))
	var sl := scroll_list()
	_scroll = sl[0]
	_body = sl[1]
	main.add_child(_scroll)
	_style_nav()
	Game.report_ready.connect(_on_report)
	_request()


func select(i: int) -> void:
	_page = i
	_style_nav()
	_render(false)


func _style_nav() -> void:
	for i in _nav_buttons.size():
		var b: Button = _nav_buttons[i]
		var on := i == _page
		var bg := BLUE if on else Color(0, 0, 0, 0)
		b.add_theme_stylebox_override("normal", UiStyle.box(bg, 8, 8))
		b.add_theme_stylebox_override("hover", UiStyle.box(BLUE.lightened(0.1) if on else Color(0.88, 0.9, 0.93), 8, 8))
		b.add_theme_stylebox_override("pressed", UiStyle.box(BLUE.darkened(0.1), 8, 8))
		for k in ["font_color", "font_hover_color"]:
			b.add_theme_color_override(k, Color.WHITE if on else INK)


func refresh() -> void:
	# Game state changes constantly; re-pull the report at most every few seconds.
	if _now() - _last_request > 2.0:
		_request()


func _process(_d: float) -> void:
	if is_visible_in_tree() and _now() - _last_request > AUTO_REFRESH_S:
		_request()


func _now() -> float:
	return Time.get_ticks_msec() / 1000.0


func _request() -> void:
	_last_request = _now()
	Game.request_report()


func _on_report(r: Dictionary) -> void:
	_report = r
	_render(true)


func _render(keep_scroll: bool) -> void:
	if _report.is_empty() or not is_instance_valid(_body):
		return
	var scroll := _scroll.scroll_vertical if keep_scroll else 0
	clear(_body)
	_title.text = NAV[_page][1]
	_stamp.text = "Live · %s   " % GameState.format_minute(float(_report.at))
	call("_page_" + NAV[_page][0])
	(func(): _scroll.scroll_vertical = scroll).call_deferred()


# ================================================================= Highlights

func _page_highlights() -> void:
	var ins: Array = _report.insights
	var v := _section("What the numbers say", "Spotted automatically from everything you've done. Updates as you play.")
	if ins.is_empty():
		_note(v, "Nothing to report yet. Keep trading and patterns will show up here.")
	var g := GridContainer.new()
	g.columns = 2
	g.add_theme_constant_override("h_separation", 10)
	g.add_theme_constant_override("v_separation", 10)
	v.add_child(g)
	for i: Dictionary in ins:
		_insight_card(g, i)
	v = _section("Goals", "What's next, and roughly when you'll get there at your current pace.")
	for goal: Dictionary in _report.goals:
		_goal(v, goal)
	_body.add_child(l("Records", 20))
	_tiles(_report.records, 3)


func _goal(parent: VBoxContainer, g: Dictionary) -> void:
	var frac := clampf(float(g.current) / maxf(float(g.target), 1.0), 0.0, 1.0)
	var cur: String = _m(g.current) if g.money else "%d" % int(g.current)
	var tgt: String = _m(g.target) if g.money else "%d" % int(g.target)
	var eta := float(g.eta)
	var when := "Ready now" if eta == 0.0 or frac >= 1.0 else ("about %.0f working day%s" % [ceilf(eta), "" if ceilf(eta) == 1.0 else "s"] if eta > 0.0 else "not on current pace")
	_meter_row(parent, g.title, frac, "%s of %s · %s" % [cur, tgt, when], GREEN if frac >= 1.0 else BLUE)
	var d := l("      " + String(g.detail), 13, SUB)
	parent.add_child(d)


# ================================================================= Overview

func _page_overview() -> void:
	var o: Dictionary = _report.overview
	_kpis([
		["Cash", _m(o.cash), "", UiStyle.money_colour(int(o.cash))],
		["Net worth", _m(o.net_worth), "cash + stock + parts + equipment", INK],
		["Net profit (all time)", _m(o.net), "every pound in minus every pound out", _sign(o.net)],
		["Reputation", "%.0f / %.0f" % [o.reputation, _report.orders.rep_max], "%+.1f over 5 days" % float(_report.orders.rep_week), INK],
		["Units bought", str(o.units_bought), "", INK],
		["Units sold", str(o.units_sold), "", INK],
		["In stock", str(o.in_stock), "worth %s at market" % _m(o.stock_market), INK],
		["Sell-through", _p(o.sell_through_pct), "sold ÷ bought", INK],
		["Avg sale price", _m(o.avg_sale), "", INK],
		["Avg purchase price", _m(o.avg_buy), "per unit, all lots", INK],
		["Avg profit per unit", _m(o.avg_profit), "after costs, fees, postage", _sign(o.avg_profit)],
		["Profit margin", _p(o.margin_pct), "profit ÷ sale price", _sign(o.margin_pct)],
		["On-time dispatch", _p(o.on_time_pct), "", INK],
		["Test pass rate", _p(o.pass_pct), "", INK],
		["Stock at cost", _m(o.stock_cost), "", INK],
		["Days trading", str(o.days), "", INK],
	])
	_unit_economics()
	_daily_chart()
	_today(o.today)


# ================================================================= Sales

func _page_sales() -> void:
	var s: Dictionary = _report.sales
	_kpis([
		["Units sold", str(s.count), "%.1f per day lately" % s.per_day, INK],
		["Revenue", _m(s.revenue), "", INK],
		["Avg sale price", _m(s.avg_price), "", INK],
		["Median sale price", _m(s.median_price), "", INK],
		["Avg purchase price", _m(s.avg_cost), "of units sold", INK],
		["Avg profit per unit", _m(s.avg_profit), "", _sign(s.avg_profit)],
		["Profit margin", _p(s.margin_pct), "", _sign(s.margin_pct)],
		["Markup", "%.2f×" % s.markup, "sale price ÷ purchase price", INK],
		["Price vs guide", _p(s.price_vs_guide_pct), "100% = market value", INK],
		["Avg days in stock", _days(s.avg_days_in_stock), "delivery → sold", INK],
		["Avg time to sell", _days(s.avg_listed_days), "listed → sold", INK],
		["Total profit on sales", _m(s.profit), "", _sign(s.profit)],
	])
	if int(s.count) == 0:
		_empty("No sales yet. List graded units on Flogit and every sale shows up here with its full cost breakdown.")
		return
	_unit_economics()
	var v := _section("By grade", "What each grade actually earns you.")
	_table(v, ["Grade", "Sold", "Avg price", "Avg cost", "Avg profit", "Margin", "Days in stock"],
		s.by_grade.map(func(g): return [_grade_cell(g.key), str(g.count), _m(g.avg_price), _m(g.avg_cost), _mc(g.avg_profit),
			_p(g.margin_pct), _days(g.avg_days)]))
	v = _section("By model", "Most profitable first. Avg cost includes repair parts.")
	_table(v, ["Model", "Sold", "Revenue", "Avg price", "Avg cost", "Avg profit", "Total profit", "Margin"],
		s.by_model.map(func(g): return [g.key, str(g.count), _m(g.revenue), _m(g.avg_price), _m(g.avg_cost),
			_mc(g.avg_profit), _mc(g.profit), _p(g.margin_pct)]))
	v = _section("By maker")
	_table(v, ["Maker", "Sold", "Revenue", "Avg price", "Avg profit", "Total profit"],
		s.by_maker.map(func(g): return [g.key, str(g.count), _m(g.revenue), _m(g.avg_price), _mc(g.avg_profit), _mc(g.profit)]))
	v = _section("By channel")
	_table(v, ["Channel", "Sold", "Revenue", "Avg price", "Avg profit", "Margin"],
		s.by_channel.map(func(g): return [String(g.key).capitalize(), str(g.count), _m(g.revenue), _m(g.avg_price),
			_mc(g.avg_profit), _p(g.margin_pct)]))
	v = _section("Recent sales", "Last 30 shipped orders. Cost = purchase price + parts.")
	_table(v, ["Day", "Model", "Grade", "Bought", "Sold", "Profit", "In stock", "Dispatch"],
		s.recent.map(func(x): return ["Day %d" % (x.day + 1), x.model, _grade_cell(x.grade), _m(x.cost), _m(x.price),
			_mc(x.profit), _days(x.days_in_stock), ["On time", GREEN] if x.on_time else ["Late", RED]]), true, 1)


# ================================================================= Pricing & demand

func _page_pricing() -> void:
	var p: Dictionary = _report.pricing
	var s: Dictionary = _report.sales
	_kpis([
		["Avg price vs guide", _p(s.price_vs_guide_pct), "what you charge vs market value", INK],
		["Avg time to sell", _days(s.avg_listed_days), "once listed", INK],
		["Orders received", str(p.orders_total), "", INK],
		["Busiest time", p.busiest_day.substr(0, 3) + " " + String(p.busiest_hour).substr(0, 5), "for new orders", INK],
	])
	var v := _section("Price vs speed", "Cheaper sells faster; dearer earns more per unit. 'Profit per shelf-day' balances the two. Spares excluded.")
	_table(v, ["Priced at", "Sold", "Days to sell", "Avg profit", "£/shelf-day", "Listed now", "Listed for"],
		p.bands.map(func(b): return [b.key, str(b.count), "—" if int(b.count) == 0 else _days(b.days_to_sell),
			"—" if int(b.count) == 0 else _mc(b.avg_profit), "—" if int(b.count) == 0 else _m(b.profit_per_day),
			str(b.on_shelf), "—" if int(b.on_shelf) == 0 else _days(b.shelf_days)]))
	v = _section("When orders come in", "Orders placed by weekday and hour, all time. Darker = busier.")
	if int(p.orders_total) == 0:
		_note(v, "No orders yet.")
	else:
		var heat := _Heat.new()
		heat.custom_minimum_size = Vector2(0, 220)
		heat.rows = p.heat
		heat.peak = int(p.peak)
		heat.row_labels = ["Mon", "Tue", "Wed", "Thu", "Fri"]
		for h in p.by_hour.size():
			heat.col_labels.append("%02d:00" % (int(p.open_h) + h))
		v.add_child(heat)
		_note(v, "Busiest day: %s. Busiest hour: %s." % [p.busiest_day, p.busiest_hour])
	for spec in [["ram", "Memory"], ["storage", "Storage"], ["cpu", "Processor"]]:
		var rows: Array = p.specs[spec[0]]
		if rows.is_empty():
			continue
		v = _section("What %s is worth" % String(spec[1]).to_lower(), "Average sale price by %s, cheapest first." % String(spec[1]).to_lower())
		_table(v, [spec[1], "Sold", "Avg price", "Avg cost", "Avg profit", "Margin", "Days in stock"],
			rows.map(func(g): return [g.key, str(g.count), _m(g.avg_price), _m(g.avg_cost), _mc(g.avg_profit), _p(g.margin_pct), _days(g.avg_days)]))


# ================================================================= Customers

func _page_customers() -> void:
	var c: Dictionary = _report.customers
	_kpis([
		["Customers", str(c.unique), "unique Flogit buyers", INK],
		["Repeat customers", str(c.repeat), "%s of customers" % _p(c.repeat_pct), INK],
		["Orders from repeat buyers", _p(c.repeat_orders_pct), "", INK],
		["Avg spend per customer", _m(c.avg_spend), "%.1f orders each" % float(c.avg_orders), INK],
		["Avg order value", _m(_report.orders.avg_order_value), "", INK],
		["Top customer's share", _p(c.top_share_pct), "of all revenue", INK],
	], 3)
	if int(c.unique) == 0:
		_empty("No customers yet.")
		return
	var v := _section("Top customers", "By total spend.")
	_table(v, ["Buyer", "Orders", "Spent", "Your profit", "First order", "Last order", "Late"],
		c.top.map(func(x): return [x.key, str(x.orders), _m(x.spent), _mc(x.profit), "Day %d" % (int(x.first) + 1),
			"Day %d" % (int(x.last) + 1), [str(x.late), RED] if int(x.late) > 0 else "0"]))


# ================================================================= Purchasing

func _page_purchasing() -> void:
	var p: Dictionary = _report.purchasing
	_kpis([
		["Lots bought", str(p.lots), "", INK],
		["Units bought", str(p.units), "", INK],
		["Total spent on stock", _m(p.spent), "", INK],
		["Avg purchase price", _m(p.avg_unit_cost), "per unit", INK],
		["Avg lot size", "%.1f units" % p.avg_lot_units, "", INK],
		["Avg lot price", _m(p.avg_lot_price), "", INK],
		["Saved by Purchasing skill", _m(p.discount), _p(p.discount_pct) + " off list", GREEN if int(p.discount) > 0 else INK],
		["Lots on the way", str(p.incoming.size()), "", INK],
	])
	if int(p.lots) == 0:
		_empty("No lots bought yet. Buy one in LotFinder and you'll see how each seller's kit really performs.")
		return
	var v := _section("Deal quality", "Price index = what you paid vs the typical trade price for those models (100 = typical). Is a bargain really a bargain?")
	_table(v, ["Price paid", "Lots", "Units", "Avg cost", "Pass rate", "Faults/unit", "Avg grade", "Profit/unit", "ROI on sold"],
		p.deals.map(func(d): return [d.key, str(d.lots), str(d.units), _m(d.avg_cost), "—" if int(d.tested) == 0 else _p(d.pass_pct),
			"—" if int(d.tested) == 0 else "%.2f" % d.faults_per_unit, _grade_avg(d), "—" if int(d.sold) == 0 else _mc(d.profit_per_unit),
			"—" if int(d.sold) == 0 else _pc(d.roi_pct)]))
	v = _section("Lot returns", "Recovered = money back so far (after fees, postage, parts) ÷ price paid. Projected assumes the rest sell like the ones that have.")
	_table(v, ["Day", "Seller", "Units", "Paid", "Index", "Sold", "Recovered", "ROI so far", "Projected"],
		p.lot_rows.map(func(x): return ["Day %d" % (x.day + 1), x.seller, str(x.units), _m(x.paid), "%.0f" % x.idx, "%d/%d" % [x.sold, x.units],
			_p(x.recovered_pct), _pc(x.roi_pct), "—" if float(x.projected_roi_pct) < -900.0 else _pc(x.projected_roi_pct)]), true, 1)
	v = _section("Sellers", "How each seller's kit really turns out once tested, graded and sold.")
	_table(v, ["Seller", "Lots", "Units", "Avg cost", "Pass rate", "Faults/unit", "Avg grade", "Sold", "Profit/unit"],
		p.by_seller.map(func(g): return _seller_row(g)))
	v = _section("Sectors")
	_table(v, ["Sector", "Lots", "Units", "Avg cost", "Pass rate", "Faults/unit", "Avg grade", "Sold", "Profit/unit"],
		p.by_sector.map(func(g): return _seller_row(g)))
	if not p.incoming.is_empty():
		v = _section("Awaiting delivery")
		_table(v, ["Seller", "Units", "Paid"], p.incoming.map(func(x): return [x.seller, str(x.count), _m(x.paid)]))


func _grade_avg(g: Dictionary) -> String:
	if int(g.graded) == 0:
		return "—"
	var a := float(g.avg_grade)
	return "%s (%.1f)" % ["A" if a >= 3.5 else ("B" if a >= 2.5 else ("C" if a >= 1.5 else ("D" if a >= 0.5 else "S"))), a]


func _seller_row(g: Dictionary) -> Array:
	return [g.key, str(g.lots), str(g.units), _m(g.avg_cost), "—" if int(g.tested) == 0 else _p(g.pass_pct),
		"—" if int(g.tested) == 0 else "%.2f" % g.faults_per_unit, _grade_avg(g), str(g.sold),
		"—" if int(g.sold) == 0 else _mc(g.profit_per_unit)]


# ================================================================= Products & quality

func _page_quality() -> void:
	var q: Dictionary = _report.quality
	_kpis([
		["Units inspected", str(q.tested), "tested on a bench", INK],
		["Avg battery health", _p(q.avg_battery), "", INK],
		["Avg cosmetic score", "%.0f / 100" % float(q.avg_cosmetic), "100 = pristine", INK],
		["Models handled", str(q.models.size()), "", INK],
	])
	if int(q.tested) == 0:
		_empty("Test some units and you'll see which models are reliable, which are lemons, and what state kit usually arrives in.")
		return
	var v := _section("Model reliability", "Most reliable first. Grade: A = 4 … spares = 0.")
	_table(v, ["Model", "Tested", "Pass rate", "Faults/unit", "Most common fault", "Battery", "Avg grade", "Avg profit"],
		q.models.map(func(m): return [m.key, str(m.tested), [_p(m.pass_pct), RED if float(m.pass_pct) < 60.0 else INK],
			"%.2f" % m.faults_per_unit, m.top_fault, _p(m.battery), _grade_avg(m), "—" if int(m.sold) == 0 else _mc(m.avg_profit)]), true, 0, [4])
	v = _section("How kit arrives", "Share of tested units with each visible condition.")
	var top := 1.0
	for c: Dictionary in q.cosmetic:
		top = maxf(top, float(c.pct))
	for c: Dictionary in q.cosmetic:
		_meter_row(v, c.key, float(c.pct) / top, "%s  ·  %d units" % [_p(c.pct), c.count], BLUE)
	v = _section("Battery health on arrival")
	var bt := 1.0
	for b: Dictionary in q.battery:
		bt = maxf(bt, float(b.pct))
	for b: Dictionary in q.battery:
		_meter_row(v, b.key, float(b.pct) / bt, "%s  ·  %d units" % [_p(b.pct), b.count], CASH_COL)
	_note(v, "Below 80% caps a unit at grade B; a new battery restores it to 100%.")


# ================================================================= Stock

func _page_stock() -> void:
	var s: Dictionary = _report.stock
	_kpis([
		["Units in stock", str(s.units), "", INK],
		["Stock at cost", _m(s.cost_value), "what you paid (+ parts)", INK],
		["Stock at market", _m(s.market_value), "once refurbished & graded", INK],
		["Potential profit", _m(s.potential_profit), "before fees & postage", _sign(s.potential_profit)],
		["Days of cover", _days(s.days_cover), "stock ÷ daily sales", INK],
		["Stock turns", "%.0f× a year" % float(s.turns_year), "how fast stock sells through", INK],
		["Avg stock age", _days(s.avg_age_days), "since delivery", INK],
		["Pallets on site", str(s.pallets_unopened), "", INK],
		["Listed on Flogit", str(s.listed_count), "worth %s" % _m(s.listed_value), INK],
		["Avg list price", _m(s.listed_avg_price), "", INK],
		["Avg guide price", _m(s.listed_avg_guide), "listed at %s of guide" % _p(s.listed_vs_guide_pct), INK],
		["Avg expected profit", _m(s.listed_avg_profit), "per listed unit, after fees", _sign(s.listed_avg_profit)],
	])
	var v := _section("Pipeline", "Where every unit is right now, and how long they've been in the building.")
	var max_n := 1
	for st: Dictionary in s.stages:
		max_n = maxi(max_n, int(st.count))
	for st: Dictionary in s.stages:
		_meter_row(v, _stage_name(st.key), float(st.count) / max_n, "%d  ·  cost %s  ·  market %s  ·  avg age %s" % [st.count, _m(st.cost), _m(st.market), _days(st.avg_age)], BLUE)
	if not s.by_grade.is_empty():
		v = _section("Graded stock by grade")
		_table(v, ["Grade", "Units", "Cost", "Priced at"], s.by_grade.map(func(g): return [_grade_cell(g.key), str(g.count), _m(g.cost), _m(g.value)]))
	v = _section("Listed units", "Purchase price vs your price vs market guide. Longest-listed first.")
	if s.listed.is_empty():
		_note(v, "Nothing listed right now.")
	else:
		_table(v, ["Model", "Grade", "Bought for", "Listed at", "Guide", "vs guide", "Exp. profit", "Listed for"],
			s.listed.map(func(x): return [x.model, _grade_cell(x.grade), _m(x.cost), _m(x.price), _m(x.guide), _p(x.vs_guide),
				_mc(x.exp_profit), _days(x.listed_days)]))
	v = _section("Slow movers", "Oldest unsold stock.")
	if s.oldest.is_empty():
		_note(v, "No stock on hand.")
	else:
		_table(v, ["Model", "Where it is", "Age", "Cost"], s.oldest.map(func(x): return [x.model, x.stage, _days(x.age), _m(x.cost)]), true, 0, [1])
	v = _section("Parts bins")
	if s.parts.is_empty():
		_note(v, "Bins are empty. Order parts in PartsHub.")
	else:
		_table(v, ["Part", "Qty", "Value"], s.parts.map(func(x): return [x.name, str(x.count), _m(x.value)]))
	v = _section("Packing supplies", "Days left at your recent shipping rate.")
	_table(v, ["Supply", "In stock", "Days left"], s.consumables.map(func(x): return [String(x.key).capitalize(), str(x.count),
		"—" if float(x.days_left) < 0.0 else ["%.1f days" % x.days_left, RED if float(x.days_left) < 2.0 else INK]]))


func _stage_name(stage: String) -> String:
	return {"untested": "Untested", "faulty": "Faulty, awaiting repair", "to_image": "Needs wipe & image",
		"to_grade": "Ready to grade", "graded": "Graded, not listed", "listed": "Listed on Flogit", "sold": "Sold, to ship"}.get(stage, stage)


# ================================================================= Operations

func _page_operations() -> void:
	var o: Dictionary = _report.operations
	var t: Dictionary = o.throughput
	_kpis([
		["Units tested", str(o.tests), "", INK],
		["Test pass rate", _p(o.pass_pct), "%d passed first time" % o.passed, INK],
		["Faults found", str(o.faults_total), "%.2f per unit tested" % o.faults_per_unit, INK],
		["Sellable yield", _p(o.yield_pct), "graded A–D, not spares", INK],
		["Repairs done", str(o.repairs), "", INK],
		["Parts fitted", str(o.parts_used), "worth %s" % _m(o.parts_value), INK],
		["Return on parts", "£%.2f per £1" % float(o.repair_return), "vs selling as spares", GREEN if float(o.repair_return) > 1.0 else INK],
		["Avg lead time", _hours(o.lead_time_h), "working hours, delivery → shipped", INK],
		["Test bench use", _p(o.bench_util_pct), "of opening hours", INK],
		["Bench capacity", "%.1f units/day" % float(o.capacity_day), "test + image, skill level 0", INK],
		["Shipped per day", "%.1f" % t.shipped, "average", INK],
		["Hours open", _hours(o.open_hours), "working time so far", INK],
	])
	var v := _section("Where the time goes", "Average working hours for each step, from units you've sold. The slowest step is your bottleneck. 'Listed → sold' depends on demand, so it isn't counted as one.")
	var top := 0.1
	for s: Dictionary in o.stages:
		top = maxf(top, float(s.avg_h))
	for s: Dictionary in o.stages:
		var is_b: bool = s.key == o.bottleneck
		_meter_row(v, s.label, float(s.avg_h) / top, "%s avg  ·  %d waiting now%s" % [_hours(s.avg_h), s.waiting,
			"" if int(s.waiting) == 0 else " (%s so far)" % _hours(s.wait_h)], AMBER if is_b else BLUE, "Bottleneck" if is_b else "")
	v = _section("Funnel", "What became of everything you bought.")
	var bought := maxf(float(o.funnel[0].count), 1.0)
	for f: Dictionary in o.funnel:
		_meter_row(v, f.key, float(f.count) / bought, "%d  ·  %s of bought" % [f.count, _p(100.0 * f.count / bought)], CASH_COL)
	v = _section("Test benches", "Busy = a test or image running. Jobs keep running while you do other things.")
	_table(v, ["Bench", "Jobs", "Tests", "Images", "Busy", "Utilisation"],
		o.benches.map(func(b): return [b.key, str(b.jobs), str(b.tests), str(b.images), _hours(b.busy_h), _p(b.util_pct)]))
	v = _section("Daily throughput", "Average per working day.")
	_table(v, ["Tested", "Repaired", "Imaged", "Graded", "Shipped"],
		[["%.1f" % t.tested, "%.1f" % t.repaired, "%.1f" % t.imaged, "%.1f" % t.graded, "%.1f" % t.shipped]], false)
	v = _section("Grades", "Share of everything graded so far.")
	if int(o.graded) == 0:
		_note(v, "Nothing graded yet.")
	else:
		var split := _Split.new()
		split.custom_minimum_size = Vector2(0, 26)
		for g: Dictionary in o.grades:
			split.parts.append([float(g.count), grade_colour(g.key)])
		v.add_child(split)
		_table(v, ["Grade", "Units", "Share"], o.grades.map(func(g): return [_grade_cell(g.key), str(g.count), _p(g.pct)]))
	v = _section("Repairs pay?", "Repaired units vs what the same units would fetch sold as spares/repairs.")
	_table(v, ["Units repaired & sold", "Extra sale value", "Parts cost", "Net gain", "Per £1 of parts"],
		[[str(o.repair_units), _m(o.repair_extra), _m(o.repair_parts), _mc(float(o.repair_extra) - float(o.repair_parts)), "£%.2f" % float(o.repair_return)]], false)
	v = _section("Faults found", "Every fault diagnosed, most common first.")
	if o.faults.is_empty():
		_note(v, "No faults found yet.")
	else:
		_table(v, ["Fault", "Found", "Share", "Repaired", "Repair level"],
			o.faults.map(func(f): return [f.key, str(f.count), _p(f.pct), str(f.repaired), "L%d" % f.level]))
	v = _section("Parts used")
	if o.parts.is_empty():
		_note(v, "No parts fitted yet.")
	else:
		_table(v, ["Part", "Fitted", "Value"], o.parts.map(func(x): return [x.key, str(x.count), _m(x.value)]))
	_today(o.today)


# ================================================================= Orders & service

func _page_orders() -> void:
	var o: Dictionary = _report.orders
	_kpis([
		["Orders received", str(o.placed), "", INK],
		["Orders shipped", str(o.shipped), "", INK],
		["On-time dispatch", _p(o.on_time_pct), "%d on time" % o.on_time, GREEN if float(o.on_time_pct) >= 90.0 else (AMBER if float(o.on_time_pct) >= 70.0 else INK)],
		["On-time streak", str(o.streak), "best ever %d" % int(o.best_streak), INK],
		["Shipped late", str(o.late), "avg %s late" % _hours(o.avg_late_h) if int(o.late) > 0 else "", RED if int(o.late) > 0 else INK],
		["Cancelled by buyer", str(o.cancelled), "", RED if int(o.cancelled) > 0 else INK],
		["Waiting to pack", str(o.to_pack), "%d overdue" % o.overdue, RED if int(o.overdue) > 0 else INK],
		["Packed, awaiting courier", str(o.packed), "", INK],
		["Avg dispatch time", _hours(o.avg_dispatch_h), "working hours, order → courier", INK],
		["Fastest dispatch", _hours(o.fastest_dispatch_h), "median %s" % _hours(o.median_dispatch_h), INK],
		["Avg order value", _m(o.avg_order_value), "", INK],
		["Reputation", "%.1f" % o.reputation, "%+.1f over the last 5 days" % float(o.rep_week), INK],
	])
	var series: Array = o.rep_series
	var v := _section("Reputation", "At the end of each working day.")
	if series.size() < 2:
		_note(v, "Builds up after a couple of working days.")
	else:
		v.add_child(_chart(series, [], _report.finance.series_days, PROFIT_COL, false))
	v = _section("Shift ratings", "Average %.1f ★ across %d shifts." % [float(o.avg_rating), int(_sum_arr(o.stars))])
	var top := maxf(float(o.stars.max()), 1.0)
	for i in range(4, -1, -1):
		_meter_row(v, "%s" % "★".repeat(i + 1), float(o.stars[i]) / top, "%d shifts" % int(o.stars[i]), AMBER)


func _sum_arr(a: Array) -> float:
	var t := 0.0
	for x in a:
		t += float(x)
	return t


# ================================================================= Finance

func _page_finance() -> void:
	var f: Dictionary = _report.finance
	_kpis([
		["Cash", _m(f.cash), "", UiStyle.money_colour(int(f.cash))],
		["Net profit (all time)", _m(f.net), "", _sign(f.net)],
		["Money in", _m(f.income), "", INK],
		["Money out", _m(f.costs), "", INK],
		["Avg revenue per day", _m(f.avg_rev_day), "", INK],
		["Avg profit per day", _m(f.avg_profit_day), "", _sign(f.avg_profit_day)],
		["Profit per week", _m(f.avg_profit_week), "last 5 working days", _sign(f.avg_profit_week)],
		["Fixed costs per week", _m(f.fixed_weekly), "rent + utilities + wages", INK],
		["Cash in 5 days", _m(f.forecast_5), "forecast at this week's rate", UiStyle.money_colour(int(f.forecast_5))],
		["Cash in 20 days", _m(f.forecast_20), "forecast at this week's rate", UiStyle.money_colour(int(f.forecast_20))],
		["Break-even", "—" if float(f.breakeven_units_week) < 0.0 else "%.1f units/wk" % f.breakeven_units_week, "to cover fixed costs", INK],
		["Cash runway", "Profitable" if float(f.runway_days) < 0.0 else "%.0f days" % f.runway_days, "at the current rate", GREEN if float(f.runway_days) < 0.0 else RED],
		["Peak cash", _m(f.peak_cash), "", INK],
		["Lowest cash", _m(f.low_cash), "", UiStyle.money_colour(int(f.low_cash))],
		["Profitable days in a row", str(f.profitable_run), "best ever %d" % int(f.best_run), INK],
		["Days trading", str(f.days), "", INK],
	])
	var v := _section("Profit & loss (all time)")
	var net_sales: int = int(f.revenue) - int(f.fees) - int(f.postage)
	var gross: int = net_sales - int(f.stock_bought) - int(f.shop)
	var operating: int = gross - int(f.rent) - int(f.utilities) - int(f.wages) - int(f.bank)
	_table(v, ["", "Amount"], [
		["Sales revenue", _m(f.revenue)],
		["   less Flogit fees", _m(-int(f.fees))],
		["   less postage", _m(-int(f.postage))],
		[["Net sales", INK], [_m(net_sales), _sign(net_sales)]],
		["   less stock bought (lots)", _m(-int(f.stock_bought))],
		["   less parts, USBs & packing supplies", _m(-int(f.shop))],
		[["Gross profit", INK], [_m(gross), _sign(gross)]],
		["   less rent", _m(-int(f.rent))],
		["   less utilities", _m(-int(f.utilities))],
		["   less staff wages", _m(-int(f.wages))],
		["   less bank charges", _m(-int(f.bank))],
		[["Operating profit", INK], [_m(operating), _sign(operating)]],
		["   less equipment bought", _m(-int(f.equipment))],
		["   less moving costs", _m(-int(f.premises))],
		[["Net profit", INK], [_m(f.net), _sign(f.net)]],
	])
	_note(v, "Gross profit counts every lot you've bought, including stock not yet sold. On units actually sold, you've made %s after purchase price, parts, fees, postage and packaging (cost of goods sold: %s)." % [_m(f.gross_on_sold), _m(f.cogs)])
	_daily_chart()
	v = _section("Week by week")
	if f.weeks.is_empty():
		_note(v, "Fills in at the end of your first working day.")
	else:
		_table(v, ["Week", "Revenue", "Net", "Shipped", "Bought", "Tested", "Avg rating"],
			f.weeks.map(func(w): return ["Week %d" % int(w.week), _m(w.revenue), _mc(w.net), str(w.shipped), str(w.bought), str(w.tested), "%.1f ★" % float(w.rating)]))
	v = _section("Closing cash", "Balance at the end of each working day.")
	if f.series_cash.size() < 2:
		_note(v, "Builds up after a couple of working days.")
	else:
		v.add_child(_chart(f.series_cash, [], f.series_days, CASH_COL, true))
	if not f.best_day.is_empty():
		v = _section("Best and worst days")
		_table(v, ["", "Day", "Revenue", "Net", "Shipped"], [
			["Best", "%s, day %d" % [f.best_day.weekday, int(f.best_day.day) + 1], _m(f.best_day.get("revenue", 0)), _mc(f.best_day.profit), str(f.best_day.get("shipped", 0))],
			["Worst", "%s, day %d" % [f.worst_day.weekday, int(f.worst_day.day) + 1], _m(f.worst_day.get("revenue", 0)), _mc(f.worst_day.profit), str(f.worst_day.get("shipped", 0))]])
	v = _section("By ledger category (all time)")
	_table(v, ["Category", "Amount"], f.categories.map(func(c): return [String(c.key).capitalize(), _mc(c.amount)]))


# ================================================================= People & premises

func _page_people() -> void:
	var p: Dictionary = _report.people
	var pr: Dictionary = _report.premises
	_kpis([
		["Team", "%d %s" % [p.team_size, "person" if int(p.team_size) == 1 else "people"], "players on the books", INK],
		["Hours worked", _hours(p.hours), "all players, on shift", INK],
		["Avg hours per person", _hours(p.avg_hours_person), "", INK],
		["Avg hours per shift", _hours(p.avg_hours_day), "per person per day", INK],
		["Revenue per worker-hour", _m(p.revenue_per_hour), "", INK],
		["Profit per worker-hour", _m(p.profit_per_hour), "effectively your hourly wage", _sign(p.profit_per_hour)],
		["Units sold per worker-hour", "%.2f" % p.units_per_hour, "", INK],
		["Wages paid (all time)", _m(p.wages_paid), "", INK],
	])
	if not p.awards.is_empty():
		_body.add_child(l("Awards", 20))
		_tiles(p.awards.map(func(a): return {"title": a.title, "value": a.name, "detail": a.value}), 4)
	var v := _section("Staff & wages", "Hired staff, their wages and labour cost per unit.")
	_table(v, ["Staff employed", "Weekly wage bill", "Avg wage", "Avg hourly wage", "Labour cost per unit"],
		[[str(p.staff), _m(p.wage_bill_weekly), _m(p.avg_wage_weekly) + "/wk", _m(p.avg_wage_hourly) + "/h", _m(p.labour_per_unit)]], false)
	_note(v, "No staff yet: it's just the owners, unpaid. Hiring arrives with the Staff app; wages then land here, in Finance and in the Friday bills.")
	v = _section("Hours", "Game hours on shift (09:00–17:00) while in the session. Jobs/hour counts tests, repairs, images, grades, listings and parcels.")
	_table(v, ["Name", "Hours", "Days worked", "Avg hours/day", "Jobs/hour", "Total XP", "Best skill"],
		p.team.map(func(x): return [x.name, _hours(x.hours), str(x.days), _hours(x.avg_hours_day), "%.1f" % float(x.jobs_per_hour), str(x.xp), x.top_skill]))
	v = _section("Work done", "Everything each person has done, all time.")
	_table(v, ["Name", "Carried", "Tested", "Repaired", "Imaged", "Graded", "Listed", "Packed", "Lots (units)"],
		p.team.map(func(x): return [x.name, str(x.hauled), str(x.tested), str(x.repaired), str(x.imaged), str(x.graded),
			str(x.listed), str(x.packed), "%d (%d)" % [x.lots_bought, x.units_bought]]))
	_kpis([
		["Premises", pr.name, pr.address, INK],
		["Rent", _m(pr.rent) + "/wk", "%s per m² per week" % _m2(pr.rent_m2), INK],
		["Utilities", _m(pr.utilities) + "/wk", "", INK],
		["Loading bays", str(pr.bays), "", INK],
		["Equipment", "%d pieces" % pr.equipment, "%d placed" % pr.placed, INK],
		["Equipment value", _m(pr.equipment_value), "at Shop prices", INK],
	])
	v = _section("Capacity")
	_meter_row(v, "Floor space", float(pr.floor_pct) / 100.0, "%.0f of %.0f m² (%s) used by equipment" % [pr.floor_used, pr.floor_m2, _p(pr.floor_pct)], CASH_COL)
	_meter_row(v, "Power", float(pr.power_pct) / 100.0, "%.1f of %.0f kW (%s)" % [pr.power_used, pr.power_kw, _p(pr.power_pct)], RED if float(pr.power_pct) > 90.0 else CASH_COL)
	v = _section("Equipment owned")
	_table(v, ["Type", "Owned"], pr.types.map(func(x): return [x.key, str(x.count)]))


# ================================================================= Timeline

const KIND_STYLE := {"milestone": [Color(0.12, 0.6, 0.3), "Milestone"], "record": [Color(0.85, 0.55, 0.05), "Record"],
	"lot": [Color(0.2, 0.45, 0.85), "Purchase"], "skill": [Color(0.9, 0.4, 0.3), "Skill"], "premises": [Color(0.1, 0.52, 0.55), "Premises"],
	"equipment": [Color(0.1, 0.52, 0.55), "Equipment"], "reputation": [Color(0.42, 0.28, 0.72), "Reputation"],
	"unlock": [Color(0.42, 0.28, 0.72), "New seller"], "first": [Color(0.12, 0.6, 0.3), "First"], "warning": [Color(0.82, 0.22, 0.18), "Warning"]}


func _page_timeline() -> void:
	var ev: Array = _report.timeline
	if ev.is_empty():
		_empty("Your company's story starts with its first lot. Milestones, records, moves and new suppliers will appear here.")
		return
	var v := _section("Company timeline", "Newest first.")
	var last_day := -1
	for e: Dictionary in ev:
		if int(e.day) != last_day:
			last_day = int(e.day)
			var d := l("%s · day %d" % [GameState.WEEKDAYS[last_day % 7], last_day + 1], 15, SUB)
			v.add_child(d)
		var st: Array = KIND_STYLE.get(e.kind, [SUB, String(e.kind).capitalize()])
		var h := HBoxContainer.new()
		h.add_theme_constant_override("separation", 12)
		var time := l(GameState.format_minute(float(e.m)).substr(4), 15, SUB)
		time.custom_minimum_size.x = 60
		h.add_child(time)
		var tag := pill(st[1], st[0], Color.WHITE, 13)
		tag.custom_minimum_size.x = 110
		h.add_child(tag)
		h.add_child(l(e.text, 17))
		v.add_child(h)


# ================================================================= shared blocks

func _unit_economics() -> void:
	var s: Dictionary = _report.sales
	var v := _section("Where the money goes on an average sale", "Average across every unit sold.")
	if int(s.count) == 0:
		_note(v, "Appears after your first sale.")
		return
	var split := _Split.new()
	split.custom_minimum_size = Vector2(0, 26)
	var segs := [[s.avg_cost, Color(0.55, 0.58, 0.63), "Purchase price"], [s.avg_parts, Color(0.72, 0.6, 0.4), "Repair parts"],
		[s.avg_fee, Color(0.85, 0.45, 0.6), "Flogit fee"], [s.avg_post, Color(0.4, 0.62, 0.8), "Postage"],
		[s.avg_pack, Color(0.7, 0.72, 0.76), "Packaging"], [maxf(float(s.avg_profit), 0.0), PROFIT_COL, "Profit"]]
	for sg in segs:
		split.parts.append([float(sg[0]), sg[1]])
	v.add_child(split)
	var legend := HFlowContainer.new()
	legend.add_theme_constant_override("h_separation", 22)
	for sg in segs:
		legend.add_child(_swatch(sg[1], "%s %s" % [sg[2], _m(sg[0])]))
	v.add_child(legend)
	v.add_child(l("Avg sale price %s  −  costs %s  =  profit %s" % [_m(s.avg_price),
		_m(float(s.avg_cost) + float(s.avg_parts) + float(s.avg_fee) + float(s.avg_post) + float(s.avg_pack)), _m(s.avg_profit)], 17))


func _daily_chart() -> void:
	var f: Dictionary = _report.finance
	var v := _section("Daily sales revenue and net profit", "Each working day. Net = all money in minus all money out. Hover for figures.")
	if f.series_revenue.is_empty():
		_note(v, "Fills in at the end of your first working day.")
		return
	var legend := HBoxContainer.new()
	legend.add_theme_constant_override("separation", 22)
	legend.add_child(_swatch(REVENUE_COL, "Sales revenue"))
	legend.add_child(_swatch(PROFIT_COL, "Net profit"))
	v.add_child(legend)
	v.add_child(_chart(f.series_revenue, f.series_profit, f.series_days, REVENUE_COL, true))


func _today(t: Dictionary) -> void:
	var v := _section("Today so far")
	_table(v, ["Tested", "Repaired", "Imaged", "Graded", "Sold", "Shipped", "Money in", "Money out"],
		[[str(t.get("tested", 0)), str(t.get("repaired", 0)), str(t.get("imaged", 0)), str(t.get("graded", 0)),
			str(t.get("sold", 0)), str(t.get("shipped", 0)), _m(t.get("income", 0)), _m(t.get("spend", 0))]], false)
