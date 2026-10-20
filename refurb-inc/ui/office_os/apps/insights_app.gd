extends PaneApp
## Insights: every number about the business in one place — sales, purchasing, stock, operations, service,
## finance, people and premises. Data comes from BusinessStats.compute() on the host (Game.request_report).

const TABS := ["Overview", "Sales", "Purchasing", "Stock", "Operations", "Orders & Service", "Finance", "People & Premises"]
const AUTO_REFRESH_S := 5.0
const REVENUE_COL := Color(0.2, 0.45, 0.85)
const PROFIT_COL := Color(0.42, 0.28, 0.72)
const CASH_COL := Color(0.1, 0.52, 0.55)
const NUM_W := 112.0

var _tab := 0
var _report: Dictionary = {}
var _scroll: ScrollContainer
var _body: VBoxContainer
var _stamp: Label
var _tabbar: TabBar
var _last_request := -100.0


func build() -> void:
	add_theme_constant_override("separation", 10)
	var top := HBoxContainer.new()
	add_child(top)
	var tb := tabs(TABS, func(i):
		_tab = i
		_render(false))
	tb.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(tb)
	_tabbar = tb
	_stamp = l("", 15, SUB)
	top.add_child(_stamp)
	top.add_child(button("Refresh", _request))
	var sl := scroll_list()
	_scroll = sl[0]
	_body = sl[1]
	add_child(_scroll)
	Game.report_ready.connect(_on_report)
	_request()


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
	_stamp.text = "Live · %s   " % GameState.format_minute(float(_report.at))
	match _tab:
		0: _overview()
		1: _sales()
		2: _purchasing()
		3: _stock()
		4: _operations()
		5: _orders()
		6: _finance()
		7: _people()
	(func(): _scroll.scroll_vertical = scroll).call_deferred()


# ================================================================= tabs

func _overview() -> void:
	var o: Dictionary = _report.overview
	_kpis([
		["Cash", _m(o.cash), "", UiStyle.money_colour(int(o.cash))],
		["Net worth", _m(o.net_worth), "cash + stock + parts + equipment", INK],
		["Net profit (all time)", _m(o.net), "every pound in minus every pound out", _sign(o.net)],
		["Reputation", "%.0f / %.0f" % [o.reputation, _report.orders.rep_max], _signed(_report.orders.rep_week, "%+.1f this week"), INK],
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


func _sales() -> void:
	var s: Dictionary = _report.sales
	_kpis([
		["Units sold", str(s.count), "%.1f per day" % s.per_day, INK],
		["Revenue", _m(s.revenue), "", INK],
		["Avg sale price", _m(s.avg_price), "", INK],
		["Median sale price", _m(s.median_price), "", INK],
		["Avg purchase price", _m(s.avg_cost), "of units sold", INK],
		["Avg profit per unit", _m(s.avg_profit), "", _sign(s.avg_profit)],
		["Profit margin", _p(s.margin_pct), "", _sign(s.margin_pct)],
		["Markup", "%.2f×" % s.markup, "sale price ÷ purchase price", INK],
		["Price vs guide", _p(s.price_vs_guide_pct), "100% = market value", INK],
		["Avg days in stock", _days(s.avg_days_in_stock), "delivery → sold", INK],
		["Avg processing time", _days(s.avg_process_days), "delivery → graded", INK],
		["Total profit on sales", _m(s.profit), "", _sign(s.profit)],
	])
	if int(s.count) == 0:
		_empty("No sales yet. List graded units on Flogit — every sale shows up here with its full cost breakdown.")
		return
	_unit_economics()
	var v := _section("By grade", "What each grade actually earns you.")
	_table(v, ["Grade", "Sold", "Avg price", "Avg cost", "Avg profit", "Margin", "Days in stock"],
		s.by_grade.map(func(g): return [[UnitData.grade_label(g.key), grade_colour(g.key)], str(g.count), _m(g.avg_price),
			_m(g.avg_cost), _mc(g.avg_profit), _p(g.margin_pct), _days(g.avg_days)]))
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
	v = _section("Records")
	_table(v, ["", "Model", "Grade", "Sold for", "Cost", "Profit"], [
		_record_row("Highest sale", s.highest), _record_row("Lowest sale", s.lowest), _record_row("Most profitable", s.best_profit)])
	v = _section("Recent sales", "Last 30 shipped orders. Cost = purchase price + parts.")
	_table(v, ["Day", "Model", "Grade", "Bought", "Sold", "Profit", "Days in stock", "Dispatch"],
		s.recent.map(func(x): return ["Day %d" % (x.day + 1), x.model, [UnitData.grade_label(x.grade), grade_colour(x.grade)],
			_m(x.cost), _m(x.price), _mc(x.profit), _days(x.days_in_stock), ["On time", GREEN] if x.on_time else ["Late", RED]]), true, 1)


func _purchasing() -> void:
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
	var v := _section("Sellers", "How each seller's kit really turns out once tested, graded and sold. Avg grade: A = 4 … Spares = 0.")
	_table(v, ["Seller", "Lots", "Units", "Avg cost", "Pass rate", "Faults/unit", "Avg grade", "Sold", "Profit/unit"],
		p.by_seller.map(func(g): return _seller_row(g)))
	v = _section("Sectors")
	_table(v, ["Sector", "Lots", "Units", "Avg cost", "Pass rate", "Faults/unit", "Avg grade", "Sold", "Profit/unit"],
		p.by_sector.map(func(g): return _seller_row(g)))
	v = _section("Recent lots")
	_table(v, ["Day", "Seller", "Units", "Paid", "Per unit", "Tested", "Passed", "Sold", "Profit so far"],
		p.recent.map(func(x): return ["Day %d" % (x.day + 1), x.seller, str(x.count), _m(x.paid), _m(x.unit_price),
			str(x.tested), str(x.passed), str(x.sold), _mc(x.profit)]), true, 1)
	if not p.incoming.is_empty():
		v = _section("Awaiting delivery")
		_table(v, ["Seller", "Units", "Paid"], p.incoming.map(func(x): return [x.seller, str(x.count), _m(x.paid)]))


func _stock() -> void:
	var s: Dictionary = _report.stock
	_kpis([
		["Units in stock", str(s.units), "", INK],
		["Stock at cost", _m(s.cost_value), "what you paid (+ parts)", INK],
		["Stock at market", _m(s.market_value), "once refurbished & graded", INK],
		["Potential profit", _m(s.potential_profit), "before fees & postage", _sign(s.potential_profit)],
		["Listed on Flogit", str(s.listed_count), "worth %s" % _m(s.listed_value), INK],
		["Avg list price", _m(s.listed_avg_price), "", INK],
		["Avg guide price", _m(s.listed_avg_guide), "listed at %s of guide" % _p(s.listed_vs_guide_pct), INK],
		["Avg expected profit", _m(s.listed_avg_profit), "per listed unit, after fees", _sign(s.listed_avg_profit)],
		["Avg stock age", _days(s.avg_age_days), "since delivery", INK],
		["Parts in bins", str(s.parts_units), "worth %s" % _m(s.parts_value), INK],
		["Pallets on site", str(s.pallets_unopened), "", INK],
	])
	var v := _section("Pipeline", "Where every unit is right now.")
	var max_n := 1
	for st: Dictionary in s.stages:
		max_n = maxi(max_n, int(st.count))
	for st: Dictionary in s.stages:
		_meter_row(v, _stage_name(st.key), float(st.count) / max_n, "%d   ·   cost %s   ·   market %s" % [st.count, _m(st.cost), _m(st.market)], BLUE)
	v = _section("Listed units", "Purchase price vs your price vs market guide. Longest-listed first.")
	if s.listed.is_empty():
		v.add_child(para("Nothing listed right now.", 16))
	else:
		_table(v, ["Model", "Grade", "Bought for", "Listed at", "Guide", "Exp. profit", "Days listed"],
			s.listed.map(func(x): return [x.model, [UnitData.grade_label(x.grade), grade_colour(x.grade)], _m(x.cost),
				_m(x.price), _m(x.guide), _mc(x.exp_profit), _days(x.listed_days)]))
	v = _section("Slow movers", "Oldest unsold stock.")
	if s.oldest.is_empty():
		v.add_child(para("No stock on hand.", 16))
	else:
		_table(v, ["Model", "Where it is", "Age", "Cost"], s.oldest.map(func(x): return [x.model, x.stage, _days(x.age), _m(x.cost)]), true, 0, [1])
	v = _section("Parts bins")
	if s.parts.is_empty():
		v.add_child(para("Bins are empty — order parts in PartsHub.", 16))
	else:
		_table(v, ["Part", "Qty", "Value"], s.parts.map(func(x): return [x.name, str(x.count), _m(x.value)]))
	v = _section("Packing supplies", "Days left at your recent shipping rate.")
	_table(v, ["Supply", "In stock", "Days left"], s.consumables.map(func(x): return [String(x.key).capitalize(), str(x.count),
		"—" if float(x.days_left) < 0.0 else ["%.0f days" % x.days_left, RED if float(x.days_left) < 2.0 else INK]]))


func _operations() -> void:
	var o: Dictionary = _report.operations
	var t: Dictionary = o.throughput
	_kpis([
		["Units tested", str(o.tests), "", INK],
		["Test pass rate", _p(o.pass_pct), "%d passed first time" % o.passed, INK],
		["Faults found", str(o.faults_total), "%.2f per unit tested" % o.faults_per_unit, INK],
		["Repairs done", str(o.repairs), "", INK],
		["Parts fitted", str(o.parts_used), "worth %s" % _m(o.parts_value), INK],
		["Avg part cost per repair", _m(o.parts_per_repair), "", INK],
		["Units graded", str(o.graded), "", INK],
		["Shipped per day", "%.1f" % t.shipped, "average", INK],
	])
	var v := _section("Daily throughput", "Average per working day.")
	_table(v, ["Tested", "Repaired", "Imaged", "Graded", "Shipped"],
		[["%.1f" % t.tested, "%.1f" % t.repaired, "%.1f" % t.imaged, "%.1f" % t.graded, "%.1f" % t.shipped]], false)
	v = _section("Grades", "Share of everything graded so far.")
	if int(o.graded) == 0:
		v.add_child(para("Nothing graded yet.", 16))
	else:
		var split := _Split.new()
		split.custom_minimum_size = Vector2(0, 26)
		for g: Dictionary in o.grades:
			split.parts.append([float(g.count), grade_colour(g.key)])
		v.add_child(split)
		_table(v, ["Grade", "Units", "Share"], o.grades.map(func(g): return [[UnitData.grade_label(g.key), grade_colour(g.key)], str(g.count), _p(g.pct)]))
	v = _section("Faults found", "Every fault diagnosed, most common first.")
	if o.faults.is_empty():
		v.add_child(para("No faults found yet.", 16))
	else:
		_table(v, ["Fault", "Found", "Share", "Repaired", "Repair level"],
			o.faults.map(func(f): return [f.key, str(f.count), _p(f.pct), str(f.repaired), "L%d" % f.level]))
	v = _section("Parts used")
	if o.parts.is_empty():
		v.add_child(para("No parts fitted yet.", 16))
	else:
		_table(v, ["Part", "Fitted", "Value"], o.parts.map(func(x): return [x.key, str(x.count), _m(x.value)]))
	_today(o.today)


func _orders() -> void:
	var o: Dictionary = _report.orders
	_kpis([
		["Orders received", str(o.placed), "", INK],
		["Orders shipped", str(o.shipped), "", INK],
		["On-time dispatch", _p(o.on_time_pct), "%d on time" % o.on_time, GREEN if float(o.on_time_pct) >= 90.0 else (AMBER if float(o.on_time_pct) >= 70.0 else INK)],
		["Shipped late", str(o.late), "", RED if int(o.late) > 0 else INK],
		["Cancelled by buyer", str(o.cancelled), "", RED if int(o.cancelled) > 0 else INK],
		["Waiting to pack", str(o.to_pack), "%d overdue" % o.overdue, RED if int(o.overdue) > 0 else INK],
		["Packed, awaiting courier", str(o.packed), "", INK],
		["Avg order value", _m(o.avg_order_value), "", INK],
		["Avg dispatch time", _hours(o.avg_dispatch_h), "working hours, order → courier", INK],
		["Median dispatch time", _hours(o.median_dispatch_h), "", INK],
		["Reputation", "%.1f" % o.reputation, _signed(o.rep_week, "%+.1f over the last 5 days"), INK],
		["Avg shift rating", "%.1f ★" % o.avg_rating, "", INK],
	])
	var series: Array = o.rep_series
	var v := _section("Reputation", "At the end of each working day.")
	if series.size() < 2:
		v.add_child(para("Builds up after a couple of working days.", 16))
	else:
		v.add_child(_chart(series, [], _report.finance.series_days, PROFIT_COL, false))


func _finance() -> void:
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
		["Break-even", "—" if float(f.breakeven_units_week) < 0.0 else "%.1f units/wk" % f.breakeven_units_week, "to cover fixed costs", INK],
		["Cash runway", "Profitable" if float(f.runway_days) < 0.0 else "%.0f days" % f.runway_days, "at the current rate", GREEN if float(f.runway_days) < 0.0 else RED],
		["Peak cash", _m(f.peak_cash), "", INK],
		["Lowest cash", _m(f.low_cash), "", UiStyle.money_colour(int(f.low_cash))],
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
	v.add_child(para("Gross profit counts every lot you've bought, including stock not yet sold. On units actually sold, you've made %s after purchase price, parts, fees, postage and packaging (cost of goods sold: %s)." % [_m(f.gross_on_sold), _m(f.cogs)], 15, SUB, 900))
	_daily_chart()
	v = _section("Closing cash", "Balance at the end of each working day.")
	if f.series_cash.size() < 2:
		v.add_child(para("Builds up after a couple of working days.", 16))
	else:
		v.add_child(_chart(f.series_cash, [], f.series_days, CASH_COL, true))
	if not f.best_day.is_empty():
		v = _section("Best and worst days")
		_table(v, ["", "Day", "Revenue", "Net", "Shipped"], [
			["Best", "%s, day %d" % [f.best_day.weekday, int(f.best_day.day) + 1], _m(f.best_day.get("revenue", 0)), _mc(f.best_day.profit), str(f.best_day.get("shipped", 0))],
			["Worst", "%s, day %d" % [f.worst_day.weekday, int(f.worst_day.day) + 1], _m(f.worst_day.get("revenue", 0)), _mc(f.worst_day.profit), str(f.worst_day.get("shipped", 0))]])
	v = _section("By ledger category (all time)")
	_table(v, ["Category", "Amount"], f.categories.map(func(c): return [String(c.key).capitalize(), _mc(c.amount)]))


func _people() -> void:
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
	var v := _section("Staff & wages", "Hired staff, their wages and labour cost per unit.")
	_table(v, ["Staff employed", "Weekly wage bill", "Avg wage", "Avg hourly wage", "Labour cost per unit"],
		[[str(p.staff), _m(p.wage_bill_weekly), _m(p.avg_wage_weekly) + "/wk", _m(p.avg_wage_hourly) + "/h", _m(p.labour_per_unit)]], false)
	v.add_child(para("No staff yet — it's just the owners, unpaid. Hiring arrives with the Staff app; wages then land here, in Finance and in the Friday bills.", 15, SUB, 900))
	v = _section("Hours", "Game hours on shift (09:00–17:00) while in the session.")
	_table(v, ["Name", "Hours", "Days worked", "Avg hours/day", "Total XP", "Strongest skill"],
		p.team.map(func(x): return [x.name, _hours(x.hours), str(x.days), _hours(x.avg_hours_day), str(x.xp), x.top_skill]))
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


# ================================================================= shared blocks

func _unit_economics() -> void:
	var s: Dictionary = _report.sales
	var v := _section("Where the money goes on an average sale", "Average across every unit sold.")
	if int(s.count) == 0:
		v.add_child(para("Appears after your first sale.", 16))
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
	var v := _section("Daily sales revenue and net profit", "Each working day. Net = all money in minus all money out.")
	if f.series_revenue.is_empty():
		v.add_child(para("Fills in at the end of your first working day.", 16))
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


func _seller_row(g: Dictionary) -> Array:
	var grade := "—"
	if int(g.graded) > 0:
		var a := float(g.avg_grade)
		grade = "%s (%.1f)" % ["A" if a >= 3.5 else ("B" if a >= 2.5 else ("C" if a >= 1.5 else ("D" if a >= 0.5 else "S"))), a]
	return [g.key, str(g.lots), str(g.units), _m(g.avg_cost), "—" if int(g.tested) == 0 else _p(g.pass_pct),
		"—" if int(g.tested) == 0 else "%.2f" % g.faults_per_unit, grade, str(g.sold),
		"—" if int(g.sold) == 0 else _mc(g.profit_per_unit)]


func _record_row(title: String, x: Dictionary) -> Array:
	if x.is_empty():
		return [title, "—", "", "", "", ""]
	return [title, x.model, [UnitData.grade_label(x.grade), grade_colour(x.grade)], _m(x.price), _m(x.cost), _mc(x.profit)]


func _stage_name(stage: String) -> String:
	return {"untested": "Untested", "faulty": "Faulty — awaiting repair", "to_image": "Needs wipe & image",
		"to_grade": "Ready to grade", "graded": "Graded, not listed", "listed": "Listed on Flogit", "sold": "Sold, to ship"}.get(stage, stage)


# ================================================================= widgets

func _kpis(items: Array) -> void:
	var g := GridContainer.new()
	g.columns = 4
	g.add_theme_constant_override("h_separation", 10)
	g.add_theme_constant_override("v_separation", 10)
	for it in items:
		var c := card()
		c.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		c.custom_minimum_size.x = 200
		var v := VBoxContainer.new()
		v.add_theme_constant_override("separation", 2)
		c.add_child(v)
		v.add_child(l(it[0], 15, SUB))
		var val := l(it[1], 27, it[3])
		val.clip_text = true
		v.add_child(val)
		if it[2] != "":
			var sub := l(it[2], 13, SUB)
			sub.clip_text = true
			v.add_child(sub)
		g.add_child(c)
	_body.add_child(g)


func _section(title: String, note: String = "") -> VBoxContainer:
	var c := card()
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 8)
	c.add_child(v)
	v.add_child(l(title, 20))
	if note != "":
		v.add_child(l(note, 14, SUB))
	_body.add_child(c)
	return v


func _empty(text: String) -> void:
	var c := card(CARD_ALT)
	c.add_child(para(text, 18, SUB, 900))
	_body.add_child(c)


func _table(parent: VBoxContainer, headers: Array, rows: Array, first_wide: bool = true, wide: int = 0, text_cols: Array = []) -> void:
	## Striped rows; the `wide` column stretches, `text_cols` are left-aligned text, the rest right-aligned numbers.
	## A cell is text or [text, colour].
	var t := VBoxContainer.new()
	t.add_theme_constant_override("separation", 0)
	parent.add_child(t)
	var all := [headers]
	all.append_array(rows)
	for r in all.size():
		var row := PanelContainer.new()
		var bg := Color(0, 0, 0, 0) if r == 0 else (CARD_ALT if r % 2 == 1 else CARD)
		row.add_theme_stylebox_override("panel", UiStyle.box(bg, 4, 5))
		var h := HBoxContainer.new()
		h.add_theme_constant_override("separation", 8)
		row.add_child(h)
		var cells: Array = all[r]
		for i in cells.size():
			var cell: Variant = cells[i]
			var txt: String = str(cell[0]) if cell is Array else str(cell)
			var col: Color = (cell[1] if cell is Array else INK) if r > 0 else SUB
			var lab := l(txt, 15 if r == 0 else 16, col)
			lab.clip_text = true
			if i == wide and first_wide:
				lab.size_flags_horizontal = Control.SIZE_EXPAND_FILL
				lab.custom_minimum_size.x = 160
			elif i in text_cols:
				lab.custom_minimum_size.x = 250
			else:
				lab.custom_minimum_size.x = NUM_W
				lab.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
				if not first_wide:
					lab.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			h.add_child(lab)
		t.add_child(row)


func _meter_row(parent: VBoxContainer, title: String, frac: float, value: String, col: Color) -> void:
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 12)
	var tl := l(title, 16)
	tl.custom_minimum_size.x = 240
	h.add_child(tl)
	var m := _Meter.new()
	m.frac = clampf(frac, 0.0, 1.0)
	m.colour = col
	m.custom_minimum_size = Vector2(260, 18)
	m.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	h.add_child(m)
	h.add_child(l(value, 16, SUB))
	parent.add_child(h)


func _swatch(col: Color, text: String) -> Control:
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 6)
	var sw := ColorRect.new()
	sw.color = col
	sw.custom_minimum_size = Vector2(14, 14)
	sw.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	h.add_child(sw)
	h.add_child(l(text, 15, INK))
	return h


func _chart(bars: Array, line: Array, labels: Array, bar_col: Color, money: bool) -> Control:
	## Bars when there's a line too (revenue + profit share one £ axis); otherwise a single line.
	var c := _Chart.new()
	c.custom_minimum_size = Vector2(0, 230)
	c.money = money
	c.labels = labels
	if line.is_empty():
		c.line = bars
		c.line_col = bar_col
	else:
		c.bars = bars
		c.bar_col = bar_col
		c.line = line
		c.line_col = PROFIT_COL
	return c


# ================================================================= formatting

static func _m(v: Variant) -> String:
	return GameState.format_money(int(round(float(v))))


static func _mc(v: Variant) -> Array:
	return [_m(v), GREEN if float(v) > 0.0 else (RED if float(v) < 0.0 else INK)]


static func _m2(v: Variant) -> String:
	return "£%.2f" % float(v)


static func _p(v: Variant) -> String:
	return "%.0f%%" % float(v)


static func _days(v: Variant) -> String:
	return "—" if float(v) < 0.0 else "%.1f days" % float(v)


static func _hours(v: Variant) -> String:
	return "%.1f h" % float(v)


static func _sign(v: Variant) -> Color:
	return GREEN if float(v) > 0.0 else (RED if float(v) < 0.0 else INK)


static func _signed(v: Variant, fmt: String) -> String:
	return fmt % float(v)


# ================================================================= drawing

class _Meter extends Control:
	var frac := 0.0
	var colour := Color.BLUE

	func _draw() -> void:
		var h := size.y
		draw_style_box(_pill(Color(0.9, 0.91, 0.93), h), Rect2(Vector2.ZERO, size))
		if frac > 0.0:
			draw_style_box(_pill(colour, h), Rect2(Vector2.ZERO, Vector2(maxf(size.x * frac, h), h)))

	static func _pill(c: Color, h: float) -> StyleBoxFlat:
		var sb := StyleBoxFlat.new()
		sb.bg_color = c
		sb.set_corner_radius_all(int(h / 2))
		return sb


class _Split extends Control:
	## One horizontal bar split into proportional segments with 2 px gaps.
	var parts: Array = []      # [[value, colour], …]

	func _draw() -> void:
		var total := 0.0
		for p in parts:
			total += maxf(float(p[0]), 0.0)
		if total <= 0.0:
			return
		var x := 0.0
		var gap := 2.0
		for p in parts:
			var w := maxf(float(p[0]), 0.0) / total * size.x
			if w <= 0.5:
				continue
			var sb := StyleBoxFlat.new()
			sb.bg_color = p[1]
			sb.set_corner_radius_all(4)
			draw_style_box(sb, Rect2(x, 0, maxf(w - gap, 1.0), size.y))
			x += w


class _Chart extends Control:
	## Daily chart on one £ axis: optional bars + optional line, recessive grid, hover readout.
	var bars: Array = []
	var line: Array = []
	var labels: Array = []
	var bar_col := Color.BLUE
	var line_col := Color.PURPLE
	var money := true
	var _hover := -1
	const PAD_L := 78.0
	const PAD_B := 26.0
	const PAD_T := 10.0

	func _ready() -> void:
		mouse_filter = Control.MOUSE_FILTER_STOP
		mouse_exited.connect(func():
			_hover = -1
			queue_redraw())

	func _count() -> int:
		return maxi(bars.size(), line.size())

	func _gui_input(e: InputEvent) -> void:
		if e is InputEventMouseMotion:
			var n := _count()
			var w := (size.x - PAD_L) / maxf(n, 1)
			var i := int(floor((e.position.x - PAD_L) / w))
			var h := i if i >= 0 and i < n else -1
			if h != _hover:
				_hover = h
				queue_redraw()

	func _fmt(v: float) -> String:
		if money:
			return GameState.format_money(int(round(v)))
		return "%.1f" % v

	func _draw() -> void:
		var font := ThemeDB.fallback_font
		var n := _count()
		if n == 0:
			return
		var lo := 0.0
		var hi := 0.0
		for v in bars + line:
			lo = minf(lo, float(v))
			hi = maxf(hi, float(v))
		if not money and line.size() > 0 and bars.is_empty():
			lo = float(line.min()) - 1.0     # a reputation line reads better without a zero baseline
		if hi - lo < 1.0:
			hi = lo + 1.0
		var step := _nice((hi - lo) / 4.0)
		lo = floor(lo / step) * step
		hi = ceil(hi / step) * step
		var plot := Rect2(PAD_L, PAD_T, size.x - PAD_L - 6.0, size.y - PAD_T - PAD_B)
		var y_of := func(v: float) -> float: return plot.end.y - (v - lo) / (hi - lo) * plot.size.y
		# grid + axis labels
		var g := lo
		while g <= hi + 0.001:
			var y: float = y_of.call(g)
			var zero := absf(g) < 0.001
			draw_line(Vector2(plot.position.x, y), Vector2(plot.end.x, y), Color(0.55, 0.58, 0.62, 0.55 if zero else 0.18), 1.0)
			draw_string(font, Vector2(4, y + 5), _fmt(g), HORIZONTAL_ALIGNMENT_RIGHT, PAD_L - 12, 13, Color(0.45, 0.48, 0.52))
			g += step
		var slot := plot.size.x / n
		if _hover >= 0:
			draw_rect(Rect2(plot.position.x + _hover * slot, plot.position.y, slot, plot.size.y), Color(0.2, 0.3, 0.5, 0.06))
		# bars (rounded at the data end, anchored at zero)
		var zero_y: float = y_of.call(clampf(0.0, lo, hi))
		var bw := minf(slot * 0.62, 30.0)
		for i in bars.size():
			var v := float(bars[i])
			if absf(v) < 0.001:
				continue
			var y: float = y_of.call(v)
			var x := plot.position.x + i * slot + (slot - bw) / 2.0
			var sb := StyleBoxFlat.new()
			sb.bg_color = bar_col if (_hover == -1 or _hover == i) else bar_col.lerp(Color.WHITE, 0.35)
			if v >= 0.0:
				sb.corner_radius_top_left = 4
				sb.corner_radius_top_right = 4
				draw_style_box(sb, Rect2(x, y, bw, zero_y - y))
			else:
				sb.corner_radius_bottom_left = 4
				sb.corner_radius_bottom_right = 4
				draw_style_box(sb, Rect2(x, zero_y, bw, y - zero_y))
		# line
		if line.size() > 0:
			var pts := PackedVector2Array()
			for i in line.size():
				pts.append(Vector2(plot.position.x + (i + 0.5) * slot, y_of.call(float(line[i]))))
			if pts.size() > 1:
				draw_polyline(pts, line_col, 2.0, true)
			for i in pts.size():
				if pts.size() <= 31 or i == _hover:
					draw_circle(pts[i], 5.0 if i == _hover else 3.5, Color.WHITE)
					draw_circle(pts[i], 3.5 if i == _hover else 2.5, line_col)
		# x labels (thinned)
		var every := maxi(1, int(ceil(n / 10.0)))
		for i in range(0, n, every):
			if i < labels.size():
				draw_string(font, Vector2(plot.position.x + i * slot, size.y - 6), str(labels[i]), HORIZONTAL_ALIGNMENT_CENTER, slot * every, 12, Color(0.45, 0.48, 0.52))
		# hover readout
		if _hover >= 0:
			var lines: Array = [str(labels[_hover]) if _hover < labels.size() else ""]
			if _hover < bars.size():
				lines.append("Revenue " + _fmt(float(bars[_hover])))
			if _hover < line.size():
				lines.append(("Net profit " if not bars.is_empty() else "") + _fmt(float(line[_hover])))
			var w := 0.0
			for t in lines:
				w = maxf(w, font.get_string_size(t, HORIZONTAL_ALIGNMENT_LEFT, -1, 14).x)
			var box := Rect2(0, 0, w + 20, lines.size() * 19 + 12)
			box.position.x = clampf(plot.position.x + (_hover + 0.5) * slot + 12, 0, size.x - box.size.x)
			box.position.y = PAD_T
			var sb := StyleBoxFlat.new()
			sb.bg_color = Color(0.1, 0.12, 0.15, 0.94)
			sb.set_corner_radius_all(6)
			draw_style_box(sb, box)
			for k in lines.size():
				draw_string(font, box.position + Vector2(10, 22 + k * 19), lines[k], HORIZONTAL_ALIGNMENT_LEFT, -1, 14, Color.WHITE if k > 0 else Color(0.75, 0.78, 0.82))

	static func _nice(raw: float) -> float:
		var mag := pow(10.0, floor(log(maxf(raw, 0.0001)) / log(10.0)))
		for m in [1.0, 2.0, 2.5, 5.0, 10.0]:
			if raw <= m * mag:
				return m * mag
		return 10.0 * mag
