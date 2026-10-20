class_name BusinessReport
extends RefCounted
## Turns GameState.stats + the live world into one plain Dictionary for the Insights app.
## Host only (it reads hidden history); clients receive the result over RPC (Game.request_report).

const GRADES := BusinessStats.GRADES
const GRADE_PTS := {"A": 4.0, "B": 3.0, "C": 2.0, "D": 1.0, "S": 0.0}
const PRICE_BANDS := [[0.0, 85.0, "Under 85% of guide"], [85.0, 95.0, "85–95%"], [95.0, 105.0, "95–105%"], [105.0, 9999.0, "Over 105%"]]
const DEAL_BANDS := [[0.0, 85.0, "Bargain (<85)"], [85.0, 110.0, "Typical (85–110)"], [110.0, 9999.0, "Premium (>110)"]]
const STAGES := [["test", "Delivery → tested"], ["repair", "Tested → repaired"], ["image", "→ wiped & imaged"],
	["grade", "→ graded"], ["list", "Graded → listed"], ["sell", "Listed → sold"], ["ship", "Sold → shipped"]]


static func compute(st: GameState) -> Dictionary:
	var r := {"at": st.minute, "day": st.day_index(), "company": st.company_name}
	r.sales = _sales(st)
	r.pricing = _pricing(st)
	r.customers = _customers(st)
	r.purchasing = _purchasing(st)
	r.quality = _quality(st)
	r.stock = _stock(st, r)
	r.operations = _operations(st, r)
	r.orders = _orders(st)
	r.finance = _finance(st, r)
	r.people = _people(st, r)
	r.premises = _premises(st)
	r.overview = _overview(st, r)
	r.records = _records(st, r)
	r.goals = _goals(st, r)
	r.timeline = _timeline(st)
	r.insights = _insights(st, r)
	return r


# ================================================================= maths helpers

static func _avg(total: float, n: int) -> float:
	return total / n if n > 0 else 0.0


static func _pct(part: float, whole: float) -> float:
	return 100.0 * part / whole if whole > 0.0 else 0.0


static func _sum(arr: Array) -> float:
	var t := 0.0
	for v in arr:
		t += float(v)
	return t


static func _median(vals: Array) -> float:
	if vals.is_empty():
		return 0.0
	var v := vals.duplicate()
	v.sort()
	var n := v.size()
	return float(v[n / 2]) if n % 2 == 1 else (float(v[n / 2 - 1]) + float(v[n / 2])) / 2.0


static func _grade_letter(avg: float) -> String:
	return "A" if avg >= 3.5 else ("B" if avg >= 2.5 else ("C" if avg >= 1.5 else ("D" if avg >= 0.5 else "S")))


static func _avg_grade(grades: Dictionary) -> Array:
	## [average points, units graded]
	var pts := 0.0
	var n := 0
	for g in grades:
		n += int(grades[g])
		pts += int(grades[g]) * float(GRADE_PTS.get(g, 0.0))
	return [_avg(pts, n), n]


static func _recent_days(st: GameState, n: int) -> Array:
	var days: Array = st.stats.days
	return days.slice(maxi(days.size() - n, 0))


static func _sold_per_day(st: GameState) -> float:
	var recent := _recent_days(st, 5)
	var t := 0.0
	for d: Dictionary in recent:
		t += int(d.get("sold", 0))
	return _avg(t, recent.size())


# ================================================================= sales

static func _group(groups: Dictionary, key: String, rec: Dictionary) -> void:
	if key == "":
		key = "—"
	if not groups.has(key):
		groups[key] = {"key": key, "count": 0, "price": 0, "cost": 0, "parts": 0, "profit": 0, "days": 0.0, "days_n": 0}
	var g: Dictionary = groups[key]
	g.count += 1
	g.price += int(rec.price)
	g.cost += int(rec.get("cost", 0))
	g.parts += int(rec.get("parts", 0))
	g.profit += int(rec.profit)
	if float(rec.get("days_in_stock", -1)) >= 0.0:
		g.days += float(rec.days_in_stock)
		g.days_n += 1


static func _rows(groups: Dictionary) -> Array:
	var out: Array = []
	for g: Dictionary in groups.values():
		var n := int(g.count)
		out.append({"key": g.key, "count": n, "revenue": int(g.price), "profit": int(g.profit),
			"avg_price": _avg(g.price, n), "avg_cost": _avg(g.cost + g.parts, n), "avg_profit": _avg(g.profit, n),
			"margin_pct": _pct(g.profit, g.price), "avg_days": _avg(g.days, int(g.days_n))})
	return out


static func _brief(rec: Dictionary) -> Dictionary:
	if rec.is_empty():
		return {}
	return {"day": int(rec.day), "model": rec.model, "grade": rec.grade, "price": int(rec.price),
		"cost": int(rec.get("cost", 0)) + int(rec.get("parts", 0)), "profit": int(rec.profit), "seller": rec.get("seller", ""),
		"buyer": rec.get("buyer", ""), "on_time": rec.get("on_time", true), "days_in_stock": float(rec.get("days_in_stock", -1))}


static func _sales(st: GameState) -> Dictionary:
	var sales: Array = st.stats.sales
	var n := sales.size()
	var t := {"price": 0.0, "cost": 0.0, "parts": 0.0, "fee": 0.0, "post": 0.0, "pack": 0.0, "profit": 0.0, "guide": 0.0}
	var prices: Array = []
	var stock_days: Array = []
	var proc_days: Array = []
	var listed_days: Array = []
	var by_grade := {}
	var by_model := {}
	var by_channel := {}
	var by_maker := {}
	var hi: Dictionary = {}
	var lo: Dictionary = {}
	var best: Dictionary = {}
	for rec: Dictionary in sales:
		for k in t:
			t[k] = float(t[k]) + float(rec.get(k, rec.price if k == "guide" else 0))
		prices.append(int(rec.price))
		if float(rec.get("days_in_stock", -1)) >= 0.0:
			stock_days.append(float(rec.days_in_stock))
		if float(rec.get("process_days", -1)) >= 0.0:
			proc_days.append(float(rec.process_days))
		if float(rec.get("listed_days", -1)) >= 0.0:
			listed_days.append(float(rec.listed_days))
		if hi.is_empty() or int(rec.price) > int(hi.price):
			hi = rec
		if lo.is_empty() or int(rec.price) < int(lo.price):
			lo = rec
		if best.is_empty() or int(rec.profit) > int(best.profit):
			best = rec
		_group(by_grade, String(rec.grade), rec)
		_group(by_model, String(rec.model), rec)
		_group(by_channel, String(rec.channel), rec)
		_group(by_maker, String(rec.get("maker", "")), rec)
	var out := {
		"count": n, "revenue": int(t.price), "profit": int(t.profit),
		"avg_price": _avg(t.price, n), "median_price": _median(prices), "avg_cost": _avg(t.cost, n),
		"avg_parts": _avg(t.parts, n), "avg_fee": _avg(t.fee, n), "avg_post": _avg(t.post, n), "avg_pack": _avg(t.pack, n),
		"avg_profit": _avg(t.profit, n), "margin_pct": _pct(t.profit, t.price),
		"markup": t.price / t.cost if t.cost > 0.0 else 0.0, "price_vs_guide_pct": _pct(t.price, t.guide),
		"avg_days_in_stock": _avg(_sum(stock_days), stock_days.size()),
		"avg_process_days": _avg(_sum(proc_days), proc_days.size()),
		"avg_listed_days": _avg(_sum(listed_days), listed_days.size()),
		"highest": _brief(hi), "lowest": _brief(lo), "best_profit": _brief(best),
		"by_grade": _rows(by_grade), "by_model": _rows(by_model), "by_channel": _rows(by_channel), "by_maker": _rows(by_maker),
		"recent": [], "per_day": _sold_per_day(st),
	}
	out.by_model.sort_custom(func(a, b): return int(a.profit) > int(b.profit))
	out.by_maker.sort_custom(func(a, b): return int(a.profit) > int(b.profit))
	out.by_grade.sort_custom(func(a, b): return GRADES.find(a.key) < GRADES.find(b.key))
	for i in range(n - 1, maxi(n - 30, 0) - 1, -1):
		out.recent.append(_brief(sales[i]))
	return out


# ================================================================= pricing & demand

static func _pricing(st: GameState) -> Dictionary:
	var bands: Array = []
	for b in PRICE_BANDS:
		bands.append({"key": b[2], "lo": b[0], "hi": b[1], "count": 0, "profit": 0.0, "days": 0.0, "days_n": 0, "on_shelf": 0, "shelf_days": 0.0})
	var ram := {}
	var storage := {}
	var cpu := {}
	for rec: Dictionary in st.stats.sales:
		if rec.has("ram"):
			BusinessReport._group(ram, "%dGB RAM" % int(rec.ram), rec)
			BusinessReport._group(storage, ("%dGB" % int(rec.storage)) if int(rec.storage) < 1000 else ("%dTB" % int(int(rec.storage) / 1000.0)), rec)
			BusinessReport._group(cpu, String(rec.cpu), rec)
		var g := float(rec.get("guide", 0))
		if g <= 0.0 or rec.get("grade", "") == "S":
			continue
		var ratio := 100.0 * float(rec.price) / g
		for b: Dictionary in bands:
			if ratio >= float(b.lo) and ratio < float(b.hi):
				b.count += 1
				b.profit += int(rec.profit)
				if float(rec.get("listed_days", -1)) >= 0.0:
					b.days += float(rec.listed_days)
					b.days_n += 1
	# Units on the shelf right now, by how they're priced.
	for it: Item in st.units_in_stock():
		var u := it.unit
		if not u.listed or u.grade == "S":
			continue
		var ratio := 100.0 * float(u.list_price) / maxf(float(Valuation.market_value(u, u.grade)), 1.0)
		for b: Dictionary in bands:
			if ratio >= float(b.lo) and ratio < float(b.hi):
				b.on_shelf += 1
				b.shelf_days += BusinessStats.working_days(u.listed_at, st.minute) if u.listed_at >= 0.0 else 0.0
	var band_rows: Array = []
	for b: Dictionary in bands:
		var days := _avg(b.days, int(b.days_n))
		var avg_profit := _avg(b.profit, int(b.count))
		band_rows.append({"key": b.key, "count": int(b.count), "avg_profit": avg_profit, "days_to_sell": days,
			"profit_per_day": avg_profit / maxf(days, 0.25) if int(b.count) > 0 else 0.0,
			"on_shelf": int(b.on_shelf), "shelf_days": _avg(b.shelf_days, int(b.on_shelf))})
	# Demand heatmap: weekday × opening hour.
	var open_h := int(Content.econ("day_start_hour", 9))
	var close_h := int(Content.econ("day_end_hour", 17)) + 1      # the closing-time market tick lands on the hour
	var heat: Array = []
	var by_hour: Array = []
	by_hour.resize(close_h - open_h)
	by_hour.fill(0)
	var by_day: Array = [0, 0, 0, 0, 0]
	var peak := 0
	for wd in 5:
		var row: Array = []
		for h in range(open_h, close_h):
			var c := int(st.stats.demand.get("%d_%d" % [wd, h], 0))
			row.append(c)
			by_hour[h - open_h] += c
			by_day[wd] += c
			peak = maxi(peak, c)
		heat.append(row)
	var best_h := by_hour.find(by_hour.max()) if not by_hour.is_empty() else 0
	var best_d := by_day.find(by_day.max())
	var specs := {"ram": _rows(ram), "storage": _rows(storage), "cpu": _rows(cpu)}
	for k in specs:
		specs[k].sort_custom(func(a, b): return float(a.avg_price) < float(b.avg_price))
	return {
		"bands": band_rows, "heat": heat, "peak": peak, "open_h": open_h, "by_hour": by_hour, "by_day": by_day,
		"busiest_hour": "%02d:00–%02d:00" % [open_h + best_h, open_h + best_h + 1], "busiest_day": GameState.WEEKDAYS[best_d],
		"orders_total": int(_sum(by_day)), "specs": specs,
	}


# ================================================================= customers

static func _customers(st: GameState) -> Dictionary:
	var buyers := {}
	for rec: Dictionary in st.stats.sales:
		var b := String(rec.get("buyer", ""))
		if b == "":
			continue
		if not buyers.has(b):
			buyers[b] = {"key": b, "orders": 0, "spent": 0, "profit": 0, "first": int(rec.day), "last": int(rec.day), "late": 0}
		var c: Dictionary = buyers[b]
		c.orders += 1
		c.spent += int(rec.price)
		c.profit += int(rec.profit)
		c.last = maxi(int(c.last), int(rec.day))
		if not rec.get("on_time", true):
			c.late += 1
	var rows: Array = buyers.values()
	rows.sort_custom(func(a, b): return int(a.spent) > int(b.spent))
	var repeat := 0
	var repeat_orders := 0
	var total_orders := 0
	var spent := 0
	for c: Dictionary in rows:
		total_orders += int(c.orders)
		spent += int(c.spent)
		if int(c.orders) > 1:
			repeat += 1
			repeat_orders += int(c.orders)
	return {
		"unique": rows.size(), "repeat": repeat, "repeat_pct": _pct(repeat, rows.size()),
		"repeat_orders_pct": _pct(repeat_orders, total_orders), "avg_spend": _avg(spent, rows.size()),
		"avg_orders": _avg(total_orders, rows.size()), "top": rows.slice(0, 12),
		"top_share_pct": _pct(int(rows[0].spent), spent) if not rows.is_empty() else 0.0,
	}


# ================================================================= purchasing

static func _purchasing(st: GameState) -> Dictionary:
	var buys: Array = st.stats.purchases
	var units := 0
	var paid := 0
	var listed := 0
	var by_seller := {}
	var by_sector := {}
	var deals: Array = []
	for b in DEAL_BANDS:
		deals.append({"key": b[2], "lo": b[0], "hi": b[1], "lots": 0, "units": 0, "paid": 0, "idx": 0.0, "tested": 0, "passed": 0,
			"faults": 0, "grades": {}, "sold": 0, "net": 0, "profit": 0})
	for p: Dictionary in buys:
		units += int(p.count)
		paid += int(p.paid)
		listed += int(p.list_total)
	var lot_rows: Array = []
	for lot_id in st.stats.lots:
		var lot: Dictionary = st.stats.lots[lot_id]
		for key_group in [[by_seller, String(lot.seller)], [by_sector, String(lot.sector)]]:
			_add_lot(key_group[0], key_group[1], lot)
		for d: Dictionary in deals:
			if lot.has("idx") and float(lot.idx) >= float(d.lo) and float(lot.idx) < float(d.hi):
				_add_lot_into(d, lot)
				d.idx += float(lot.idx)
		var sold := int(lot.sold)
		var count := int(lot.count)
		var lpaid := maxf(float(lot.paid), 1.0)
		var net := float(lot.get("net", 0))
		lot_rows.append({"id": int(lot_id), "day": int(lot.get("day", 0)), "seller": lot.seller, "units": count, "paid": int(lot.paid),
			"idx": float(lot.get("idx", 100.0)), "tested": int(lot.tested), "passed": int(lot.passed), "sold": sold,
			"pass_pct": _pct(int(lot.passed), int(lot.tested)), "recovered_pct": 100.0 * net / lpaid,
			"roi_pct": 100.0 * (net - lpaid) / lpaid,
			"projected_roi_pct": 100.0 * (net + (count - sold) * net / sold - lpaid) / lpaid if sold > 0 else -999.0,
			"profit": int(lot.profit)})
	lot_rows.sort_custom(func(a, b): return int(a.id) > int(b.id))
	var deal_rows: Array = []
	for d: Dictionary in deals:
		var row := _lot_row(d)
		row["avg_idx"] = _avg(d.idx, int(d.lots))
		row["roi_pct"] = _pct(float(d.net) - float(d.paid) * float(d.sold) / maxf(float(d.units), 1.0), float(d.paid) * float(d.sold) / maxf(float(d.units), 1.0)) if int(d.sold) > 0 else 0.0
		deal_rows.append(row)
	var recent: Array = []
	for i in range(buys.size() - 1, maxi(buys.size() - 20, 0) - 1, -1):
		var p: Dictionary = buys[i]
		var lot: Dictionary = st.stats.lots.get(str(p.lot), {})
		recent.append({"day": int(p.day), "seller": p.seller, "summary": p.summary, "count": int(p.count), "paid": int(p.paid),
			"unit_price": int(p.unit_price), "idx": float(p.get("idx", 100.0)), "tested": int(lot.get("tested", 0)),
			"passed": int(lot.get("passed", 0)), "sold": int(lot.get("sold", 0)), "profit": int(lot.get("profit", 0))})
	var incoming: Array = []
	for lot: Dictionary in st.lots.values():
		if lot.status == "bought":
			incoming.append({"seller": lot.seller_name, "count": int(lot.count), "paid": int(lot.get("paid", lot.total_price))})
	return {
		"lots": buys.size(), "units": units, "spent": paid, "avg_lot_units": _avg(units, buys.size()),
		"avg_lot_price": _avg(paid, buys.size()), "avg_unit_cost": _avg(paid, units),
		"discount": listed - paid, "discount_pct": _pct(listed - paid, listed),
		"by_seller": _seller_rows(by_seller), "by_sector": _seller_rows(by_sector), "recent": recent, "incoming": incoming,
		"lot_rows": lot_rows.slice(0, 25), "deals": deal_rows,
	}


static func _add_lot(groups: Dictionary, key: String, lot: Dictionary) -> void:
	if not groups.has(key):
		groups[key] = {"key": key, "lots": 0, "units": 0, "paid": 0, "tested": 0, "passed": 0, "faults": 0, "grades": {},
			"sold": 0, "net": 0, "profit": 0}
	_add_lot_into(groups[key], lot)


static func _add_lot_into(g: Dictionary, lot: Dictionary) -> void:
	g.lots += 1
	g.units += int(lot.count)
	g.paid += int(lot.paid)
	g.tested += int(lot.tested)
	g.passed += int(lot.passed)
	g.faults += int(lot.faults)
	g.sold += int(lot.sold)
	g.net += int(lot.get("net", 0))
	g.profit += int(lot.profit)
	for gr in lot.grades:
		g.grades[gr] = int(g.grades.get(gr, 0)) + int(lot.grades[gr])


static func _lot_row(g: Dictionary) -> Dictionary:
	var ag := _avg_grade(g.grades)
	return {"key": g.key, "lots": int(g.lots), "units": int(g.units), "avg_cost": _avg(g.paid, int(g.units)),
		"pass_pct": _pct(g.passed, g.tested), "tested": int(g.tested), "faults_per_unit": _avg(g.faults, int(g.tested)),
		"avg_grade": ag[0], "graded": ag[1], "sold": int(g.sold), "profit": int(g.profit),
		"profit_per_unit": _avg(g.profit, int(g.sold)), "paid": int(g.paid)}


static func _seller_rows(groups: Dictionary) -> Array:
	var out: Array = []
	for g: Dictionary in groups.values():
		out.append(_lot_row(g))
	out.sort_custom(func(a, b): return int(a.units) > int(b.units))
	return out


# ================================================================= products & quality

static func _quality(st: GameState) -> Dictionary:
	var s := st.stats
	var sold_by_model := {}
	for rec: Dictionary in s.sales:
		var mid := String(rec.get("model_id", ""))
		if mid == "":
			continue
		if not sold_by_model.has(mid):
			sold_by_model[mid] = [0, 0]
		sold_by_model[mid][0] += 1
		sold_by_model[mid][1] += int(rec.profit)
	var models: Array = []
	var tested_total := 0
	for mid in s.models:
		var m: Dictionary = s.models[mid]
		var n := int(m.tested)
		tested_total += n
		var top := ""
		var top_n := 0
		for f in m.fault_ids:
			if int(m.fault_ids[f]) > top_n:
				top_n = int(m.fault_ids[f])
				top = Content.fault(f).get("name", f)
		var ag := _avg_grade(m.grades)
		var sold: Array = sold_by_model.get(mid, [0, 0])
		models.append({"key": Content.model_display_name(mid), "id": mid, "tested": n, "pass_pct": _pct(int(m.passed), n),
			"faults_per_unit": _avg(m.faults, n), "top_fault": top if top != "" else "—", "battery": _avg(m.battery, n),
			"cosmetic": _avg(m.cosmetic, n), "avg_grade": ag[0], "graded": ag[1], "sold": int(sold[0]),
			"avg_profit": _avg(sold[1], int(sold[0])), "release": int(Content.model(mid).get("release_year", 0))})
	models.sort_custom(func(a, b): return float(a.pass_pct) > float(b.pass_pct))
	var cosmetic: Array = []
	for c in s.cosmetic:
		var name := "No visible wear" if c == "_pristine" else String(Content.condition(c).get("text", c))
		cosmetic.append({"key": name, "count": int(s.cosmetic[c]), "pct": _pct(int(s.cosmetic[c]), tested_total)})
	cosmetic.sort_custom(func(a, b): return int(a.count) > int(b.count))
	var battery: Array = []
	for b in BusinessStats.BATTERY_BANDS:
		battery.append({"key": b[2], "count": int(s.battery.get(b[2], 0)), "pct": _pct(int(s.battery.get(b[2], 0)), tested_total)})
	var batt_sum := 0.0
	var cos_sum := 0.0
	for m: Dictionary in s.models.values():
		batt_sum += float(m.battery)
		cos_sum += float(m.cosmetic)
	return {"models": models, "cosmetic": cosmetic, "battery": battery, "tested": tested_total,
		"avg_battery": _avg(batt_sum, tested_total), "avg_cosmetic": _avg(cos_sum, tested_total)}


# ================================================================= stock

static func _stock(st: GameState, r: Dictionary) -> Dictionary:
	var stages := {}
	for s in [UnitData.STAGE_UNTESTED, UnitData.STAGE_FAULTY, UnitData.STAGE_TO_IMAGE, UnitData.STAGE_TO_GRADE,
			UnitData.STAGE_GRADED, UnitData.STAGE_LISTED, UnitData.STAGE_SOLD]:
		stages[s] = {"key": s, "count": 0, "cost": 0, "market": 0, "age": 0.0}
	var listed: Array = []
	var ages: Array = []
	var oldest: Array = []
	var by_grade := {}
	var total_cost := 0
	var total_market := 0
	for it: Item in st.units_in_stock():
		var u := it.unit
		var stage := u.stage()
		if not stages.has(stage):
			continue
		var market := Valuation.market_value(u)
		var g: Dictionary = stages[stage]
		var age := BusinessStats.working_days(u.received, st.minute) if u.received >= 0.0 else 0.0
		g.count += 1
		g.cost += u.cost + u.parts_cost
		g.market += market
		g.age += age
		if stage == UnitData.STAGE_SOLD:
			continue
		total_cost += u.cost + u.parts_cost
		total_market += market
		ages.append(age)
		oldest.append({"model": u.display_name(), "stage": u.stage_text(), "age": age, "cost": u.cost + u.parts_cost, "grade": u.grade})
		if u.grade != "":
			if not by_grade.has(u.grade):
				by_grade[u.grade] = {"key": u.grade, "count": 0, "cost": 0, "value": 0}
			by_grade[u.grade].count += 1
			by_grade[u.grade].cost += u.cost + u.parts_cost
			by_grade[u.grade].value += u.list_price
		if stage == UnitData.STAGE_LISTED:
			var fee := float(u.list_price) * float(Content.econ("flogit_fee_pct", 0.1))
			var net := float(u.list_price) - fee - float(Content.econ("flogit_postage", 9)) - BusinessStats.packaging_cost()
			var guide := Valuation.market_value(u, u.grade)
			listed.append({"model": u.display_name(), "grade": u.grade, "cost": u.cost + u.parts_cost,
				"price": u.list_price, "guide": guide, "vs_guide": _pct(u.list_price, guide),
				"exp_profit": int(round(net)) - u.cost - u.parts_cost,
				"listed_days": BusinessStats.working_days(u.listed_at, st.minute) if u.listed_at >= 0.0 else 0.0})
	oldest.sort_custom(func(a, b): return float(a.age) > float(b.age))
	listed.sort_custom(func(a, b): return float(a.listed_days) > float(b.listed_days))
	var l := {"cost": 0.0, "price": 0.0, "guide": 0.0, "profit": 0.0}
	for x: Dictionary in listed:
		l.cost += x.cost
		l.price += x.price
		l.guide += x.guide
		l.profit += x.exp_profit
	var parts_rows: Array = []
	var parts_value := 0
	var parts_units := 0
	for pid in st.parts:
		var pdef := Content.part(pid)
		var v := int(st.parts[pid]) * int(pdef.get("price", 0))
		parts_value += v
		parts_units += int(st.parts[pid])
		parts_rows.append({"name": pdef.get("name", pid), "count": int(st.parts[pid]), "value": v})
	parts_rows.sort_custom(func(a, b): return int(a.value) > int(b.value))
	var per_day := 0.0
	var recent := _recent_days(st, 5)
	for d: Dictionary in recent:
		per_day += int(d.get("shipped", 0))
	per_day = _avg(per_day, recent.size())
	var consumables: Array = []
	for k in ["boxes", "labels", "tape"]:
		var have := int(st.consumables.get(k, 0))
		consumables.append({"key": k, "count": have, "days_left": have / per_day if per_day > 0.0 else -1.0})
	var stage_rows: Array = []
	for g: Dictionary in stages.values():
		g["avg_age"] = _avg(g.age, int(g.count))
		stage_rows.append(g)
	var grade_rows: Array = by_grade.values()
	grade_rows.sort_custom(func(a, b): return GRADES.find(a.key) < GRADES.find(b.key))
	var n_listed := listed.size()
	var units: int = ages.size()
	var sold_day: float = r.sales.per_day
	# Stock turn: how many times a year (250 working days) the stock is sold through, at cost.
	var cogs_day := 0.0
	if not recent.is_empty():
		var from_day := int(recent[0].day)
		for rec: Dictionary in st.stats.sales:
			if int(rec.day) >= from_day and int(rec.day) <= int(recent[-1].day):
				cogs_day += int(rec.get("cost", 0)) + int(rec.get("parts", 0))
		cogs_day /= recent.size()
	return {
		"units": units, "cost_value": total_cost, "market_value": total_market,
		"potential_profit": total_market - total_cost, "stages": stage_rows, "by_grade": grade_rows,
		"avg_age_days": _avg(_sum(ages), ages.size()), "oldest": oldest.slice(0, 8),
		"listed": listed.slice(0, 40), "listed_count": n_listed, "listed_avg_cost": _avg(l.cost, n_listed),
		"listed_avg_price": _avg(l.price, n_listed), "listed_avg_guide": _avg(l.guide, n_listed),
		"listed_avg_profit": _avg(l.profit, n_listed), "listed_vs_guide_pct": _pct(l.price, l.guide),
		"listed_value": int(l.price), "stale_listed": listed.filter(func(x): return float(x.listed_days) > 3.0).size(),
		"parts": parts_rows, "parts_value": parts_value, "parts_units": parts_units, "consumables": consumables,
		"pallets_unopened": st.items.values().filter(func(i): return i.kind == Item.KIND_PALLET).size(),
		"days_cover": units / sold_day if sold_day > 0.0 else -1.0,
		"turns_year": cogs_day * 250.0 / float(total_cost) if total_cost > 0 else 0.0,
	}


# ================================================================= operations

static func _operations(st: GameState, r: Dictionary) -> Dictionary:
	var s := st.stats
	var faults: Array = []
	var total_faults := 0
	for f in s.faults_found:
		total_faults += int(s.faults_found[f])
	for f in s.faults_found:
		faults.append({"key": Content.fault(f).get("name", String(f).capitalize()), "count": int(s.faults_found[f]),
			"pct": _pct(int(s.faults_found[f]), total_faults), "repaired": int(s.repairs.get(f, 0)),
			"level": int(Content.fault(f).get("repair_level", 1))})
	faults.sort_custom(func(a, b): return int(a.count) > int(b.count))
	var parts: Array = []
	var parts_n := 0
	for p in s.parts_used:
		parts_n += int(s.parts_used[p])
		parts.append({"key": Content.part(p).get("name", p), "count": int(s.parts_used[p]),
			"value": int(s.parts_used[p]) * int(Content.part(p).get("price", 0))})
	parts.sort_custom(func(a, b): return int(a.count) > int(b.count))
	var graded := 0
	for g in s.grades:
		graded += int(s.grades[g])
	var grades: Array = []
	for g in GRADES:
		grades.append({"key": g, "count": int(s.grades.get(g, 0)), "pct": _pct(int(s.grades.get(g, 0)), graded)})
	var repaired := 0
	for f in s.repairs:
		repaired += int(s.repairs[f])
	var days: Array = s.days
	var thr := {}
	for k in ["tested", "repaired", "imaged", "graded", "shipped"]:
		var t := 0
		for d: Dictionary in days:
			t += int(d.get(k, 0))
		thr[k] = _avg(t, days.size())
	var tests := int(s.tests.run)
	# Funnel: what became of everything bought.
	var sellable := graded - int(s.grades.get("S", 0))
	var bought := int(r.purchasing.units)
	var funnel := [
		{"key": "Bought", "count": bought}, {"key": "Tested", "count": tests},
		{"key": "Passed first time", "count": int(s.tests.passed)}, {"key": "Graded sellable (A–D)", "count": sellable},
		{"key": "Graded spares/repairs", "count": int(s.grades.get("S", 0))},
		{"key": "Sold", "count": int(s.orders.placed)}, {"key": "Shipped", "count": int(r.sales.count)},
	]
	# Pipeline timings: finished steps from sold units, plus what's waiting right now.
	var stage_sum := {}
	var stage_n := {}
	for rec: Dictionary in s.sales:
		var sh: Dictionary = rec.get("stages", {})
		for k in sh:
			stage_sum[k] = float(stage_sum.get(k, 0.0)) + float(sh[k])
			stage_n[k] = int(stage_n.get(k, 0)) + 1
	var waiting := {}
	var wait_sum := {}
	var stage_of := {UnitData.STAGE_UNTESTED: "test", UnitData.STAGE_FAULTY: "repair", UnitData.STAGE_TO_IMAGE: "image",
		UnitData.STAGE_TO_GRADE: "grade", UnitData.STAGE_GRADED: "list", UnitData.STAGE_LISTED: "sell", UnitData.STAGE_SOLD: "ship"}
	for it: Item in st.units_in_stock():
		var u := it.unit
		var k: String = stage_of.get(u.stage(), "")
		if k == "":
			continue
		var since := maxf(maxf(maxf(u.received, u.tested_at), maxf(u.repaired_at, u.imaged_at)), maxf(maxf(u.graded_at, u.listed_at), u.sold_at))
		waiting[k] = int(waiting.get(k, 0)) + 1
		if since >= 0.0:
			wait_sum[k] = float(wait_sum.get(k, 0.0)) + BusinessStats.working_minutes(since, st.minute) / 60.0
	var stage_rows: Array = []
	var bottleneck := ""
	var worst := 0.0
	for sd in STAGES:
		var k: String = sd[0]
		var avg := _avg(float(stage_sum.get(k, 0.0)), int(stage_n.get(k, 0)))
		stage_rows.append({"key": k, "label": sd[1], "avg_h": avg, "n": int(stage_n.get(k, 0)),
			"waiting": int(waiting.get(k, 0)), "wait_h": _avg(float(wait_sum.get(k, 0.0)), int(waiting.get(k, 0)))})
		if k != "sell" and avg > worst and int(stage_n.get(k, 0)) >= 3:
			worst = avg
			bottleneck = k
	var total_h := 0.0
	for row: Dictionary in stage_rows:
		total_h += float(row.avg_h)
	# Station utilisation and capacity.
	var open := float(s.open_minutes)
	var benches: Array = []
	var bench_count := 0
	for fid in st.fixtures:
		var f: Dictionary = st.fixtures[fid]
		if f.type != "test_bench":
			continue
		bench_count += 1
		var rec: Dictionary = s.stations.get(fid, {})
		var avail := maxf(open - float(rec.get("since", s.fixture_since.get(fid, 0.0))), 1.0)
		benches.append({"key": f.get("label", fid), "jobs": int(rec.get("jobs", 0)), "tests": int(rec.get("test", 0)),
			"images": int(rec.get("image", 0)), "busy_h": float(rec.get("busy", 0.0)) / 60.0,
			"util_pct": minf(_pct(float(rec.get("busy", 0.0)), avail), 100.0)})
	var per_unit_min := float(Content.econ("test_minutes", 30)) + float(Content.econ("image_minutes", 45))
	var capacity := bench_count * BusinessStats.day_minutes() / per_unit_min
	# What repairs are worth: sale price vs selling the same unit as spares.
	var rep_units := 0
	var rep_extra := 0.0
	var rep_parts := 0.0
	for rec: Dictionary in s.sales:
		if int(rec.get("repaired", 0)) > 0:
			rep_units += 1
			rep_extra += float(rec.price) - float(rec.get("spares_guide", rec.price))
			rep_parts += float(rec.get("parts", 0))
	var avg_util := 0.0
	for b: Dictionary in benches:
		avg_util += float(b.util_pct)
	return {
		"tests": tests, "passed": int(s.tests.passed), "pass_pct": _pct(int(s.tests.passed), tests),
		"faults_total": total_faults, "faults_per_unit": _avg(total_faults, tests), "faults": faults,
		"repairs": repaired, "parts": parts, "parts_used": parts_n, "parts_value": int(s.parts_value),
		"parts_per_repair": _avg(int(s.parts_value), repaired),
		"graded": graded, "grades": grades, "throughput": thr, "today": st.day_stats.duplicate(),
		"funnel": funnel, "yield_pct": _pct(sellable, graded),
		"stages": stage_rows, "bottleneck": bottleneck, "bottleneck_h": worst, "lead_time_h": total_h,
		"benches": benches, "bench_util_pct": _avg(avg_util, benches.size()), "capacity_day": capacity,
		"open_hours": open / 60.0,
		"repair_units": rep_units, "repair_extra": rep_extra, "repair_parts": rep_parts,
		"repair_return": rep_extra / rep_parts if rep_parts > 0.0 else 0.0,
	}


# ================================================================= orders & service

static func _orders(st: GameState) -> Dictionary:
	var s := st.stats
	var open_to_pack := 0
	var open_packed := 0
	var overdue := 0
	for o: Dictionary in st.orders.values():
		if o.status == "to_pack":
			open_to_pack += 1
		elif o.status == "packed":
			open_packed += 1
		if o.status in ["to_pack", "packed"] and st.minute > float(o.due):
			overdue += 1
	var dispatch: Array = []
	var late_h: Array = []
	var value := 0.0
	for rec: Dictionary in s.sales:
		dispatch.append(float(rec.get("dispatch_h", 0.0)))
		value += int(rec.price)
		if not rec.get("on_time", true):
			late_h.append(float(rec.get("late_h", 0.0)))
	var shipped := int(s.orders.on_time) + int(s.orders.late)
	var days: Array = s.days
	var rep_week := st.reputation - (float(days[maxi(days.size() - 5, 0)].get("rep", st.reputation)) if not days.is_empty() else st.reputation)
	var ratings := 0.0
	var stars: Array = [0, 0, 0, 0, 0]
	for d: Dictionary in days:
		var rt := clampi(int(d.get("rating", 0)), 1, 5)
		ratings += rt
		stars[rt - 1] += 1
	return {
		"placed": int(s.orders.placed), "shipped": shipped, "on_time": int(s.orders.on_time), "late": int(s.orders.late),
		"cancelled": int(s.orders.cancelled), "on_time_pct": _pct(int(s.orders.on_time), shipped),
		"to_pack": open_to_pack, "packed": open_packed, "overdue": overdue,
		"avg_dispatch_h": _avg(_sum(dispatch), dispatch.size()), "median_dispatch_h": _median(dispatch),
		"fastest_dispatch_h": dispatch.min() if not dispatch.is_empty() else 0.0,
		"avg_late_h": _avg(_sum(late_h), late_h.size()),
		"avg_order_value": _avg(value, s.sales.size()), "reputation": st.reputation,
		"rep_max": float(Content.econ("rep_max", 100)), "rep_week": rep_week,
		"avg_rating": _avg(ratings, days.size()), "stars": stars,
		"streak": int(s.streak.on_time), "best_streak": int(s.streak.best_on_time),
		"rep_series": days.map(func(d): return float(d.get("rep", 0.0))),
	}


# ================================================================= finance

static func _finance(st: GameState, r: Dictionary) -> Dictionary:
	var m: Dictionary = st.stats.money
	var income := 0
	var costs := 0
	var cats: Array = []
	for k in m:
		var v := int(m[k])
		if v >= 0:
			income += v
		else:
			costs += -v
		cats.append({"key": k, "amount": v})
	cats.sort_custom(func(a, b): return int(a.amount) > int(b.amount))
	var days: Array = st.stats.days
	var best: Dictionary = {}
	var worst: Dictionary = {}
	var rev := 0.0
	var prof := 0.0
	var profitable_run := 0
	var best_run := 0
	for d: Dictionary in days:
		rev += int(d.get("revenue", 0))
		prof += int(d.get("profit", 0))
		if best.is_empty() or int(d.profit) > int(best.profit):
			best = d
		if worst.is_empty() or int(d.profit) < int(worst.profit):
			worst = d
		profitable_run = profitable_run + 1 if int(d.profit) > 0 else 0
		best_run = maxi(best_run, profitable_run)
	var last := _recent_days(st, 5)
	var week_net := 0.0
	for d: Dictionary in last:
		week_net += int(d.get("profit", 0))
	var b := st.building()
	var wages := 0       # staff arrive in Milestone 4; their wages will land in the "wages" ledger category
	var fixed := int(b.get("rent_weekly", 0)) + int(b.get("utilities_weekly", 0)) + wages
	var avg_unit_profit: float = r.sales.avg_profit
	var daily_net := _avg(week_net, last.size())
	# Week-by-week history.
	var weeks := {}
	for d: Dictionary in days:
		var w := int(d.day) / 7
		if not weeks.has(w):
			weeks[w] = {"week": w + 1, "revenue": 0, "net": 0, "shipped": 0, "bought": 0, "tested": 0, "rating": 0.0, "days": 0}
		var row: Dictionary = weeks[w]
		row.revenue += int(d.get("revenue", 0))
		row.net += int(d.get("profit", 0))
		row.shipped += int(d.get("shipped", 0))
		row.bought += int(d.get("bought", 0))
		row.tested += int(d.get("tested", 0))
		row.rating += int(d.get("rating", 0))
		row.days += 1
	var week_rows: Array = []
	for w in weeks:
		var row: Dictionary = weeks[w]
		row.rating = _avg(row.rating, int(row.days))
		week_rows.append(row)
	week_rows.sort_custom(func(a, c): return int(a.week) > int(c.week))
	return {
		"income": income, "costs": costs, "net": income - costs, "categories": cats,
		"revenue": int(m.get("sales", 0)), "fees": -int(m.get("fees", 0)), "postage": -int(m.get("postage", 0)),
		"stock_bought": -int(m.get("purchasing", 0)), "shop": -int(m.get("shop", 0)), "rent": -int(m.get("rent", 0)),
		"utilities": -int(m.get("utilities", 0)), "wages": -int(m.get("wages", 0)), "equipment": -int(m.get("equipment", 0)),
		"premises": -int(m.get("premises", 0)), "bank": -int(m.get("bank", 0)),
		"cogs": int((float(r.sales.avg_cost) + float(r.sales.avg_parts)) * int(r.sales.count)),
		"gross_on_sold": int(r.sales.profit),
		"days": days.size(), "avg_rev_day": _avg(rev, days.size()), "avg_profit_day": _avg(prof, days.size()),
		"avg_profit_week": daily_net * 5.0, "daily_net": daily_net, "best_day": best, "worst_day": worst,
		"profitable_run": profitable_run, "best_run": best_run,
		"fixed_weekly": fixed, "wages_weekly": wages,
		"breakeven_units_week": float(fixed) / avg_unit_profit if avg_unit_profit > 0.0 else -1.0,
		"runway_days": float(st.cash) / -daily_net if daily_net < 0.0 and st.cash > 0 else -1.0,
		"forecast_5": st.cash + daily_net * 5.0, "forecast_20": st.cash + daily_net * 20.0,
		"cash": st.cash, "peak_cash": int(st.stats.peak_cash), "low_cash": int(st.stats.low_cash),
		"weeks": week_rows,
		"series_revenue": days.map(func(d): return float(d.get("revenue", 0))),
		"series_profit": days.map(func(d): return float(d.get("profit", 0))),
		"series_cash": days.map(func(d): return float(d.get("cash", 0))),
		"series_days": days.map(func(d): return "%s %d" % [String(d.get("weekday", "")).substr(0, 3), int(d.get("day", 0)) + 1]),
	}


# ================================================================= people & premises

const AWARDS := [["tested", "Test Pilot", "units tested"], ["repaired", "Spanner Hand", "repairs"],
	["imaged", "Clean Slate", "units imaged"], ["graded", "Eagle Eye", "units graded"], ["packed", "Packing Machine", "parcels packed"],
	["hauled", "Pack Mule", "items carried"], ["listed", "Shopkeeper", "listings"], ["units_bought", "Deal Hunter", "units bought"]]


static func _people(st: GameState, r: Dictionary) -> Dictionary:
	var rows: Array = []
	var minutes := 0.0
	for key in st.players:
		BusinessStats.person(st, key)
	for key in st.stats.people:
		var p: Dictionary = st.stats.people[key]
		var xp := 0
		var top := ""
		var top_xp := -1
		var pxp: Dictionary = st.players.get(key, {}).get("xp", {})
		for sk in pxp:
			xp += int(pxp[sk])
			if int(pxp[sk]) > top_xp:
				top_xp = int(pxp[sk])
				top = sk
		var hrs := float(p.minutes) / 60.0
		minutes += float(p.minutes)
		var row := {"key": key, "name": p.name, "hours": hrs, "days": int(p.days_worked),
			"avg_hours_day": _avg(hrs, int(p.days_worked)), "xp": xp, "top_skill": String(top).capitalize()}
		var work := 0
		for a in BusinessStats.ACTS:
			row[a] = int(p.acts.get(a, 0))
			if a in ["tested", "repaired", "imaged", "graded", "packed", "listed"]:
				work += int(row[a])
		row["jobs_per_hour"] = work / hrs if hrs >= 0.5 else 0.0
		rows.append(row)
	rows.sort_custom(func(a, b): return float(a.hours) > float(b.hours))
	var awards: Array = []
	for a in AWARDS:
		var winner: Dictionary = {}
		for row: Dictionary in rows:
			if int(row[a[0]]) > 0 and (winner.is_empty() or int(row[a[0]]) > int(winner[a[0]])):
				winner = row
		if not winner.is_empty():
			awards.append({"title": a[1], "name": winner.name, "value": "%d %s" % [int(winner[a[0]]), a[2]]})
	var hours := minutes / 60.0
	var person_days := 0
	for row: Dictionary in rows:
		person_days += int(row.days)
	var revenue := float(r.sales.revenue)
	var net := float(r.finance.net)
	return {
		"team": rows, "team_size": rows.size(), "hours": hours, "awards": awards,
		"avg_hours_person": _avg(hours, rows.size()), "avg_hours_day": _avg(hours, person_days),
		"revenue_per_hour": revenue / hours if hours >= 1.0 else 0.0,
		"profit_per_hour": net / hours if hours >= 1.0 else 0.0,
		"units_per_hour": float(r.sales.count) / hours if hours >= 1.0 else 0.0,
		"staff": 0, "wage_bill_weekly": 0, "avg_wage_weekly": 0.0, "avg_wage_hourly": 0.0, "labour_per_unit": 0.0,
		"wages_paid": int(r.finance.wages),
	}


static func _premises(st: GameState) -> Dictionary:
	var b := st.building()
	var kw := 0.0
	var floor_used := 0.0
	var value := 0
	var placed := 0
	var by_type := {}
	for f: Dictionary in st.fixtures.values():
		var def := Content.fixture(f.type)
		kw += float(def.get("power_kw", 0.0))
		value += int(def.get("price", 0))
		var fp: Array = def.get("footprint", [1, 1])
		if f.placed:
			placed += 1
			floor_used += float(fp[0]) * float(fp[1])
		var name: String = def.get("name", f.type)
		by_type[name] = int(by_type.get(name, 0)) + 1
	var types: Array = []
	for k in by_type:
		types.append({"key": k, "count": int(by_type[k])})
	var floor_m2 := float(b.get("floor_m2", 1))
	var rent := int(b.get("rent_weekly", 0))
	return {
		"name": b.get("name", ""), "address": b.get("address", ""), "tier": int(b.get("tier", 1)),
		"rent": rent, "utilities": int(b.get("utilities_weekly", 0)), "rent_m2": rent / maxf(floor_m2, 1.0),
		"floor_m2": floor_m2, "floor_used": floor_used, "floor_pct": _pct(floor_used, floor_m2),
		"power_kw": float(b.get("power_kw", 0)), "power_used": kw, "power_pct": _pct(kw, float(b.get("power_kw", 1))),
		"bays": int(b.get("loading_bays", 0)), "equipment": st.fixtures.size(), "placed": placed,
		"equipment_value": value, "types": types,
	}


# ================================================================= overview, records, goals, timeline

static func _overview(st: GameState, r: Dictionary) -> Dictionary:
	var bought := int(st.totals.get("units_bought", 0))
	var net_worth := st.cash + int(r.stock.cost_value) + int(r.stock.parts_value) + int(r.premises.equipment_value)
	return {
		"cash": st.cash, "net_worth": net_worth, "reputation": st.reputation, "days": r.finance.days,
		"units_bought": bought, "units_sold": int(r.sales.count), "in_stock": int(r.stock.units),
		"sell_through_pct": _pct(int(r.sales.count), bought),
		"revenue": int(r.sales.revenue), "net": int(r.finance.net),
		"avg_sale": r.sales.avg_price, "avg_buy": r.purchasing.avg_unit_cost, "avg_profit": r.sales.avg_profit,
		"margin_pct": r.sales.margin_pct, "on_time_pct": r.orders.on_time_pct, "pass_pct": r.operations.pass_pct,
		"stock_market": int(r.stock.market_value), "stock_cost": int(r.stock.cost_value),
		"today": st.day_stats.duplicate(),
	}


static func _records(st: GameState, r: Dictionary) -> Array:
	var out: Array = []
	var s: Dictionary = r.sales
	if not s.highest.is_empty():
		out.append({"title": "Biggest sale", "value": GameState.format_money(int(s.highest.price)), "detail": "%s, day %d" % [s.highest.model, int(s.highest.day) + 1]})
		out.append({"title": "Most profitable sale", "value": GameState.format_money(int(s.best_profit.profit)), "detail": "%s, day %d" % [s.best_profit.model, int(s.best_profit.day) + 1]})
	var fastest: Dictionary = {}
	for rec: Dictionary in st.stats.sales:
		if float(rec.get("days_in_stock", -1)) >= 0.0 and (fastest.is_empty() or float(rec.days_in_stock) < float(fastest.days_in_stock)):
			fastest = rec
	if not fastest.is_empty():
		out.append({"title": "Fastest flip", "value": "%.1f days" % float(fastest.days_in_stock), "detail": "%s, delivery to sold" % fastest.model})
	var f: Dictionary = r.finance
	var best_rev: Dictionary = {}
	var best_ship: Dictionary = {}
	var best_rep := 0.0
	for d: Dictionary in st.stats.days:
		if best_rev.is_empty() or int(d.get("revenue", 0)) > int(best_rev.get("revenue", 0)):
			best_rev = d
		if best_ship.is_empty() or int(d.get("shipped", 0)) > int(best_ship.get("shipped", 0)):
			best_ship = d
		best_rep = maxf(best_rep, float(d.get("rep", 0)))
	if not best_rev.is_empty():
		out.append({"title": "Best sales day", "value": GameState.format_money(int(best_rev.revenue)), "detail": "%s, day %d" % [best_rev.weekday, int(best_rev.day) + 1]})
		out.append({"title": "Most shipped in a day", "value": str(int(best_ship.shipped)), "detail": "%s, day %d" % [best_ship.weekday, int(best_ship.day) + 1]})
	var best_lot: Dictionary = {}
	for lot: Dictionary in r.purchasing.lot_rows:
		if int(lot.sold) >= maxi(2, int(lot.units / 2)) and (best_lot.is_empty() or float(lot.projected_roi_pct) > float(best_lot.projected_roi_pct)):
			best_lot = lot
	if not best_lot.is_empty():
		out.append({"title": "Best lot", "value": "%+.0f%% ROI" % float(best_lot.projected_roi_pct), "detail": "%d units from %s" % [best_lot.units, best_lot.seller]})
	out.append({"title": "On-time streak", "value": str(r.orders.streak), "detail": "best ever %d in a row" % int(r.orders.best_streak)})
	out.append({"title": "Profitable days in a row", "value": str(f.profitable_run), "detail": "best ever %d" % int(f.best_run)})
	out.append({"title": "Highest balance", "value": GameState.format_money(int(f.peak_cash)), "detail": "lowest %s" % GameState.format_money(int(f.low_cash))})
	out.append({"title": "Best reputation", "value": "%.0f" % maxf(best_rep, st.reputation), "detail": "now %.0f" % st.reputation})
	return out


static func _next_mark(marks: Array, v: float) -> float:
	for m in marks:
		if float(m) > v:
			return float(m)
	return -1.0


static func _goals(st: GameState, r: Dictionary) -> Array:
	var out: Array = []
	var daily: float = r.finance.daily_net
	var order: Array = Content.building_order
	var i := order.find(st.building_id)
	if i >= 0 and i + 1 < order.size():
		var nb := Content.building(order[i + 1])
		var target := int(nb.get("move_fee", 0)) + 2 * (int(nb.get("rent_weekly", 0)) + int(nb.get("utilities_weekly", 0)))
		out.append({"title": "Move to the %s" % String(nb.name).to_lower(), "current": float(st.cash), "target": float(target), "money": true,
			"detail": "move fee + two weeks' rent and bills", "eta": (target - st.cash) / daily if daily > 0.0 and st.cash < target else (0.0 if st.cash >= target else -1.0)})
	var sold := float(r.sales.count)
	var ns := _next_mark(BusinessStats.SOLD_MARKS, sold)
	if ns > 0.0:
		var per_day: float = r.sales.per_day
		out.append({"title": "%d units sold" % int(ns), "current": sold, "target": ns, "money": false, "detail": "milestone",
			"eta": (ns - sold) / per_day if per_day > 0.0 else -1.0})
	var rev := float(r.sales.revenue)
	var nr := _next_mark(BusinessStats.REVENUE_MARKS, rev)
	if nr > 0.0:
		out.append({"title": "%s in sales" % GameState.format_money(int(nr)), "current": rev, "target": nr, "money": true, "detail": "milestone",
			"eta": (nr - rev) / float(r.finance.avg_rev_day) if float(r.finance.avg_rev_day) > 0.0 else -1.0})
	var next_seller: Dictionary = {}
	for seller: Dictionary in Content.sellers.values():
		if float(seller.get("min_rep", 0)) > st.reputation and (next_seller.is_empty() or float(seller.min_rep) < float(next_seller.min_rep)):
			next_seller = seller
	if not next_seller.is_empty():
		var rep_day := float(r.orders.rep_week) / 5.0
		out.append({"title": "Unlock %s" % next_seller.name, "current": st.reputation, "target": float(next_seller.min_rep), "money": false,
			"detail": "needs reputation %d" % int(next_seller.min_rep),
			"eta": (float(next_seller.min_rep) - st.reputation) / rep_day if rep_day > 0.0 else -1.0})
	return out


static func _timeline(st: GameState) -> Array:
	var ev: Array = st.stats.events.duplicate()
	ev.reverse()
	return ev.slice(0, 120)


# ================================================================= auto insights

static func _insights(st: GameState, r: Dictionary) -> Array:
	## Plain-English findings from the numbers. tone: good | warn | tip | info
	var out: Array = []
	var s: Dictionary = r.sales
	var ops: Dictionary = r.operations
	var stock: Dictionary = r.stock
	var fin: Dictionary = r.finance
	var pr: Dictionary = r.pricing
	# Urgent first.
	if int(r.orders.overdue) > 0:
		out.append(_ins("warn", "%d order(s) overdue" % int(r.orders.overdue), "Late orders cost reputation and buyers cancel after two working days. Pack and ship them first."))
	for c: Dictionary in stock.consumables:
		if float(c.days_left) >= 0.0 and float(c.days_left) < 2.0:
			out.append(_ins("warn", "Running low on %s" % c.key, "%d left, about %.1f days at your shipping rate. Order more in PartsHub (it arrives with the next courier)." % [int(c.count), float(c.days_left)]))
	if float(fin.runway_days) >= 0.0:
		out.append(_ins("warn", "Cash runway: %.0f working days" % float(fin.runway_days), "You've lost an average of %s a day this week. Sell stock or slow down buying." % GameState.format_money(int(-float(fin.daily_net)))))
	if int(s.count) < 3 and int(r.purchasing.lots) == 0:
		out.append(_ins("tip", "Getting started", "Buy a lot in LotFinder. Insights fill in as you test, grade and sell, and this page starts spotting patterns after a handful of sales."))
	# Bottleneck and capacity.
	if String(ops.bottleneck) != "":
		var label := ""
		for row: Dictionary in ops.stages:
			if row.key == ops.bottleneck:
				label = row.label
		out.append(_ins("tip", "Bottleneck: %s" % label.to_lower().trim_prefix("→ "), "Units spend %.1f working hours here on average, longer than any other step. Speed this up and everything flows faster." % float(ops.bottleneck_h)))
	if float(ops.bench_util_pct) > 80.0:
		out.append(_ins("tip", "Test benches are %.0f%% busy" % float(ops.bench_util_pct), "Another test bench (and a USB for it) would raise capacity above %.1f units a day." % float(ops.capacity_day)))
	elif float(ops.bench_util_pct) > 0.0 and float(ops.bench_util_pct) < 35.0 and int(stock.units) > 5:
		out.append(_ins("tip", "Test benches idle %.0f%% of the day" % (100.0 - float(ops.bench_util_pct)), "Keep a unit on the bench at all times: a test runs by itself while you do other jobs."))
	# Pricing.
	var fast: Dictionary = {}
	var rich: Dictionary = {}
	for b: Dictionary in pr.bands:
		if int(b.count) < 3:
			continue
		if fast.is_empty() or float(b.days_to_sell) < float(fast.days_to_sell):
			fast = b
		if rich.is_empty() or float(b.profit_per_day) > float(rich.profit_per_day):
			rich = b
	if not fast.is_empty() and not rich.is_empty():
		out.append(_ins("info", "Pricing sweet spot: %s" % String(rich.key).to_lower(), "That band earns the most per day on the shelf (%s profit per unit, sells in %.1f days). Fastest sellers: %s at %.1f days." % [
			GameState.format_money(int(rich.avg_profit)), float(rich.days_to_sell), String(fast.key).to_lower(), float(fast.days_to_sell)]))
	if int(stock.stale_listed) > 0:
		out.append(_ins("tip", "%d listing(s) over 3 days old" % int(stock.stale_listed), "Cheaper sells faster. Try trimming 5–10% off the slowest units in the Flogit app."))
	# Products.
	if s.by_model.size() >= 2:
		var top: Dictionary = s.by_model[0]
		out.append(_ins("good", "Best earner: %s" % top.key, "%d sold for %s total profit (%s each, %.0f%% margin)." % [int(top.count), GameState.format_money(int(top.profit)), GameState.format_money(int(top.avg_profit)), float(top.margin_pct)]))
	var lemon: Dictionary = {}
	for m: Dictionary in r.quality.models:
		if int(m.tested) >= 5 and (lemon.is_empty() or float(m.pass_pct) < float(lemon.pass_pct)):
			lemon = m
	if not lemon.is_empty() and float(lemon.pass_pct) < 65.0:
		out.append(_ins("warn", "Watch out for the %s" % lemon.key, "Only %.0f%% pass first time (most common: %s). Factor repairs into what you pay." % [float(lemon.pass_pct), lemon.top_fault]))
	var by_grade := {}
	for g: Dictionary in s.by_grade:
		by_grade[g.key] = g
	if by_grade.has("A") and by_grade.has("S") and int(by_grade.S.count) >= 2:
		out.append(_ins("info", "Grade A earns %s more than spares" % GameState.format_money(int(float(by_grade.A.avg_profit) - float(by_grade.S.avg_profit))),
			"Average profit: A %s vs spares/repairs %s. Fixing faults before grading pays." % [GameState.format_money(int(by_grade.A.avg_profit)), GameState.format_money(int(by_grade.S.avg_profit))]))
	if float(ops.repair_parts) > 0.0 and int(ops.repair_units) >= 2:
		out.append(_ins("good", "Every £1 of parts returns £%.2f" % float(ops.repair_return), "Repaired units sold for %s more than they'd fetch as spares, from %s of parts." % [GameState.format_money(int(ops.repair_extra)), GameState.format_money(int(ops.repair_parts))]))
	var ram: Array = pr.specs.ram
	if ram.size() >= 2 and int(ram[0].count) >= 2 and int(ram[-1].count) >= 2:
		out.append(_ins("info", "%s sells for %s more than %s" % [ram[-1].key, GameState.format_money(int(float(ram[-1].avg_price) - float(ram[0].avg_price))), ram[0].key],
			"Average sale price %s vs %s. Worth knowing when choosing lots." % [GameState.format_money(int(ram[-1].avg_price)), GameState.format_money(int(ram[0].avg_price))]))
	# Purchasing.
	var best_seller: Dictionary = {}
	var worst_seller: Dictionary = {}
	for sel: Dictionary in r.purchasing.by_seller:
		if int(sel.sold) >= 3 and (best_seller.is_empty() or float(sel.profit_per_unit) > float(best_seller.profit_per_unit)):
			best_seller = sel
		if int(sel.tested) >= 5 and (worst_seller.is_empty() or float(sel.pass_pct) < float(worst_seller.pass_pct)):
			worst_seller = sel
	if not best_seller.is_empty():
		out.append(_ins("good", "Best supplier: %s" % best_seller.key, "%s profit per unit sold, %.0f%% pass rate." % [GameState.format_money(int(best_seller.profit_per_unit)), float(best_seller.pass_pct)]))
	if not worst_seller.is_empty() and worst_seller.key != best_seller.get("key", "") and float(worst_seller.pass_pct) < 65.0:
		out.append(_ins("warn", "%s kit is hit and miss" % worst_seller.key, "%.0f%% pass rate, %.2f faults per unit. Only buy cheap." % [float(worst_seller.pass_pct), float(worst_seller.faults_per_unit)]))
	var bargain: Dictionary = r.purchasing.deals[0]
	var premium: Dictionary = r.purchasing.deals[2]
	if int(bargain.tested) >= 5 and int(premium.tested) >= 5:
		out.append(_ins("info", "Bargain lots: %.0f%% pass vs %.0f%% for premium" % [float(bargain.pass_pct), float(premium.pass_pct)],
			"Cheap lots are cheap for a reason. Profit per unit sold: bargain %s, premium %s." % [GameState.format_money(int(bargain.profit_per_unit)), GameState.format_money(int(premium.profit_per_unit))]))
	# Demand.
	if int(pr.orders_total) >= 10:
		out.append(_ins("info", "Busiest for orders: %ss, %s" % [pr.busiest_day, pr.busiest_hour], "Have stock listed and packing supplies ready before the rush."))
	# Goals.
	for g: Dictionary in r.goals:
		if String(g.title).begins_with("Move to") and float(g.eta) > 0.0:
			out.append(_ins("good", "%s in about %.0f working days" % [g.title, float(g.eta)], "At this week's rate (%s a day). Bigger premises mean more space and more loading bays." % GameState.format_money(int(fin.daily_net))))
		elif String(g.title).begins_with("Move to") and float(g.eta) == 0.0:
			out.append(_ins("good", "You can afford to %s" % String(g.title).to_lower(), "You have the move fee and two weeks' rent in the bank. See the Building app."))
	if int(r.orders.streak) >= 10:
		out.append(_ins("good", "%d orders on time in a row" % int(r.orders.streak), "Buyers notice: every on-time order adds reputation."))
	return out


static func _ins(tone: String, title: String, text: String) -> Dictionary:
	return {"tone": tone, "title": title, "text": text}
