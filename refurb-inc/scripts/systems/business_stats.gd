class_name BusinessStats
extends RefCounted
## Long-term business history and the analytics behind the Insights app.
##
## Recording: Sim calls the on_* hooks as things happen; they append to GameState.stats (host only, saved).
## Reporting: compute() turns stats + the live world into one plain Dictionary report. The host computes it
## (it alone has the full history) and sends it to clients on request (Game.request_report).

const MAX_SALES := 3000
const MAX_PURCHASES := 1500
const MAX_DAYS := 500
const GRADES := ["A", "B", "C", "D", "S"]
const ACTS := ["hauled", "tested", "repaired", "imaged", "graded", "listed", "packed", "lots_bought", "units_bought"]


static func blank() -> Dictionary:
	return {
		"sales": [],          # one record per shipped unit (see on_ship)
		"purchases": [],      # one record per lot bought
		"days": [],           # end-of-day summaries
		"money": {},          # ledger category -> all-time total (the ledger itself is trimmed)
		"lots": {},           # str(lot id) -> outcome of that lot (tested / faults / grades / sold / profit)
		"faults_found": {},   # fault id -> count
		"repairs": {},        # fault id -> count
		"parts_used": {},     # part id -> count
		"parts_value": 0,
		"tests": {"run": 0, "passed": 0},
		"grades": {},         # grade -> count
		"orders": {"placed": 0, "on_time": 0, "late": 0, "cancelled": 0},
		"people": {},         # player key -> { name, minutes, days_worked, last_day, acts{} }
		"peak_cash": -2147483648, "low_cash": 2147483647,
	}


static func migrate(d: Dictionary) -> Dictionary:
	var out := blank()
	for k in d:
		out[k] = d[k]
	return out.duplicate(true)


# ================================================================= recording hooks

static func on_money(st: GameState, amount: int, category: String) -> void:
	var s := st.stats
	if amount != 0:
		s.money[category] = int(s.money.get(category, 0)) + amount
	s.peak_cash = maxi(int(s.peak_cash), st.cash)
	s.low_cash = mini(int(s.low_cash), st.cash)


static func person(st: GameState, key: String) -> Dictionary:
	var people: Dictionary = st.stats.people
	if not people.has(key):
		people[key] = {"name": key, "minutes": 0.0, "days_worked": 0, "last_day": -1, "acts": {}}
	var p: Dictionary = people[key]
	p.name = st.players.get(key, {}).get("name", p.name)
	return p


static func act(st: GameState, peer: int, what: String, n: int = 1) -> void:
	if peer == 0:
		return
	var p := person(st, Sim.player_key(peer))
	p.acts[what] = int(p.acts.get(what, 0)) + n


static func on_shift_minutes(st: GameState, peers: Array, mins: float) -> void:
	## Called by the clock while the working day runs: everyone connected is on shift.
	var day := st.day_index()
	for peer in peers:
		var p := person(st, Sim.player_key(int(peer)))
		p.minutes = float(p.minutes) + mins
		if int(p.last_day) != day:
			p.last_day = day
			p.days_worked = int(p.days_worked) + 1


static func on_lot_bought(st: GameState, lot: Dictionary, paid: int, peer: int) -> void:
	var rec := {
		"day": st.day_index(), "m": st.minute, "lot": int(lot.id), "seller_id": lot.get("seller_id", ""),
		"seller": lot.get("seller_name", ""), "sector": lot.get("sector", ""), "count": int(lot.count),
		"paid": paid, "list_total": int(lot.total_price), "unit_price": int(round(paid / maxf(float(lot.count), 1.0))),
		"summary": lot.get("summary", ""), "note": lot.get("note", ""),
	}
	_push(st.stats.purchases, rec, MAX_PURCHASES)
	st.stats.lots[str(lot.id)] = {"seller_id": rec.seller_id, "seller": rec.seller, "sector": rec.sector, "count": rec.count,
		"paid": paid, "tested": 0, "passed": 0, "faults": 0, "grades": {}, "sold": 0, "revenue": 0, "profit": 0}
	act(st, peer, "lots_bought")
	act(st, peer, "units_bought", int(lot.count))


static func _lot(st: GameState, u: UnitData) -> Dictionary:
	return st.stats.lots.get(str(u.lot_id), {})


static func on_test(st: GameState, u: UnitData, by: int) -> void:
	var s := st.stats
	s.tests.run = int(s.tests.run) + 1
	var nf := u.faults.size()
	if nf == 0:
		s.tests.passed = int(s.tests.passed) + 1
	for f in u.faults:
		s.faults_found[f.id] = int(s.faults_found.get(f.id, 0)) + 1
	var lot := _lot(st, u)
	if not lot.is_empty():
		lot.tested = int(lot.tested) + 1
		lot.faults = int(lot.faults) + nf
		if nf == 0:
			lot.passed = int(lot.passed) + 1
	act(st, by, "tested")


static func on_repair(st: GameState, u: UnitData, fault_id: String, part_id: String, part_cost: int, peer: int) -> void:
	var s := st.stats
	s.repairs[fault_id] = int(s.repairs.get(fault_id, 0)) + 1
	s.parts_used[part_id] = int(s.parts_used.get(part_id, 0)) + 1
	s.parts_value = int(s.parts_value) + part_cost
	u.parts_cost += part_cost
	act(st, peer, "repaired")


static func on_grade(st: GameState, u: UnitData, peer: int) -> void:
	st.stats.grades[u.grade] = int(st.stats.grades.get(u.grade, 0)) + 1
	var lot := _lot(st, u)
	if not lot.is_empty():
		lot.grades[u.grade] = int(lot.grades.get(u.grade, 0)) + 1
	act(st, peer, "graded")


static func packaging_cost() -> float:
	## One box, one label and some tape per parcel, at Shop prices.
	var total := 0.0
	for s: Dictionary in Content.shop_items.values():
		if s.get("kind") == "consumable" and s.get("consumable") in ["boxes", "labels", "tape"]:
			total += float(s.price) / maxf(float(s.qty), 1.0)
	return total


static func on_ship(st: GameState, order: Dictionary, u: UnitData, fee: int, post: int, on_time: bool) -> void:
	var price := int(order.price)
	var cost := u.cost if u else 0
	var parts := u.parts_cost if u else 0
	var pack := int(round(packaging_cost()))
	var profit := price - fee - post - pack - cost - parts
	var rec := {
		"day": st.day_index(), "m": st.minute, "order": int(order.id), "channel": order.get("channel", "flogit"),
		"model_id": u.model_id if u else "", "model": order.get("model", ""),
		"maker": Content.maker_name(u.model().get("maker", "")) if u else "",
		"grade": order.get("grade", ""), "price": price, "cost": cost, "parts": parts, "fee": fee, "post": post,
		"pack": pack, "profit": profit, "guide": Valuation.market_value(u, u.grade) if u else price,
		"seller": u.seller_name if u else "", "lot": u.lot_id if u else 0, "on_time": on_time,
		"days_in_stock": working_days(u.received, u.sold_at) if u and u.received >= 0.0 and u.sold_at >= 0.0 else -1.0,
		"process_days": working_days(u.received, u.graded_at) if u and u.received >= 0.0 and u.graded_at >= 0.0 else -1.0,
		"dispatch_h": working_minutes(float(order.placed), st.minute) / 60.0,
	}
	_push(st.stats.sales, rec, MAX_SALES)
	var s := st.stats
	if on_time:
		s.orders.on_time = int(s.orders.on_time) + 1
	else:
		s.orders.late = int(s.orders.late) + 1
	if u:
		var lot := _lot(st, u)
		if not lot.is_empty():
			lot.sold = int(lot.sold) + 1
			lot.revenue = int(lot.revenue) + price
			lot.profit = int(lot.profit) + profit


static func on_order(st: GameState) -> void:
	st.stats.orders.placed = int(st.stats.orders.placed) + 1


static func on_cancel(st: GameState) -> void:
	st.stats.orders.cancelled = int(st.stats.orders.cancelled) + 1


static func on_day_end(st: GameState, summary: Dictionary) -> void:
	var rec := {}
	for k in ["day", "weekday", "income", "spend", "profit", "revenue", "tested", "repaired", "imaged", "graded",
			"sold", "shipped", "late", "missed", "bought", "rating", "cash"]:
		rec[k] = summary.get(k, 0)
	rec["rep"] = st.reputation
	_push(st.stats.days, rec, MAX_DAYS)


static func _push(arr: Array, rec: Dictionary, cap: int) -> void:
	arr.append(rec)
	if arr.size() > cap:
		arr.pop_front()


# ================================================================= time maths

static func working_minutes(a: float, b: float) -> float:
	## Minutes of working time (09:00–17:00, Mon–Fri) between two absolute game minutes.
	if b <= a:
		return 0.0
	var open := float(Content.econ("day_start_hour", 9)) * 60.0
	var close := float(Content.econ("day_end_hour", 17)) * 60.0
	var total := 0.0
	for d in range(int(floor(a / GameState.MIN_PER_DAY)), int(floor(b / GameState.MIN_PER_DAY)) + 1):
		if d % 7 >= 5:
			continue
		var lo := maxf(a, d * GameState.MIN_PER_DAY + open)
		var hi := minf(b, d * GameState.MIN_PER_DAY + close)
		if hi > lo:
			total += hi - lo
	return total


static func working_days(a: float, b: float) -> float:
	var day_len := (float(Content.econ("day_end_hour", 17)) - float(Content.econ("day_start_hour", 9))) * 60.0
	return working_minutes(a, b) / day_len


# ================================================================= report

static func _avg(total: float, n: int) -> float:
	return total / n if n > 0 else 0.0


static func _pct(part: float, whole: float) -> float:
	return 100.0 * part / whole if whole > 0.0 else 0.0


static func _median(vals: Array) -> float:
	if vals.is_empty():
		return 0.0
	var v := vals.duplicate()
	v.sort()
	var n := v.size()
	return float(v[n / 2]) if n % 2 == 1 else (float(v[n / 2 - 1]) + float(v[n / 2])) / 2.0


static func compute(st: GameState) -> Dictionary:
	var r := {"at": st.minute, "day": st.day_index(), "company": st.company_name}
	r.sales = _sales(st)
	r.purchasing = _purchasing(st)
	r.stock = _stock(st)
	r.operations = _operations(st)
	r.orders = _orders(st)
	r.finance = _finance(st, r)
	r.people = _people(st, r)
	r.premises = _premises(st)
	r.overview = _overview(st, r)
	return r


static func _sales(st: GameState) -> Dictionary:
	var sales: Array = st.stats.sales
	var n := sales.size()
	var t := {"price": 0.0, "cost": 0.0, "parts": 0.0, "fee": 0.0, "post": 0.0, "pack": 0.0, "profit": 0.0, "guide": 0.0}
	var prices: Array = []
	var stock_days: Array = []
	var proc_days: Array = []
	var by_grade := {}
	var by_model := {}
	var by_channel := {}
	var by_maker := {}
	var hi: Dictionary = {}
	var lo: Dictionary = {}
	var best: Dictionary = {}
	for rec: Dictionary in sales:
		for k in t:
			t[k] = float(t[k]) + float(rec.get(k, 0))
		prices.append(int(rec.price))
		if float(rec.get("days_in_stock", -1)) >= 0.0:
			stock_days.append(float(rec.days_in_stock))
		if float(rec.get("process_days", -1)) >= 0.0:
			proc_days.append(float(rec.process_days))
		if hi.is_empty() or int(rec.price) > int(hi.price):
			hi = rec
		if lo.is_empty() or int(rec.price) < int(lo.price):
			lo = rec
		if best.is_empty() or int(rec.profit) > int(best.profit):
			best = rec
		_group(by_grade, String(rec.grade), rec)
		_group(by_model, String(rec.model), rec)
		_group(by_channel, String(rec.channel), rec)
		_group(by_maker, String(rec.maker), rec)
	var out := {
		"count": n, "revenue": int(t.price), "profit": int(t.profit),
		"avg_price": _avg(t.price, n), "median_price": _median(prices), "avg_cost": _avg(t.cost, n),
		"avg_parts": _avg(t.parts, n), "avg_fee": _avg(t.fee, n), "avg_post": _avg(t.post, n), "avg_pack": _avg(t.pack, n),
		"avg_profit": _avg(t.profit, n), "margin_pct": _pct(t.profit, t.price),
		"markup": t.price / t.cost if t.cost > 0.0 else 0.0,
		"price_vs_guide_pct": _pct(t.price, t.guide),
		"avg_days_in_stock": _avg(_sum(stock_days), stock_days.size()),
		"avg_process_days": _avg(_sum(proc_days), proc_days.size()),
		"highest": _brief(hi), "lowest": _brief(lo), "best_profit": _brief(best),
		"by_grade": _rows(by_grade), "by_model": _rows(by_model), "by_channel": _rows(by_channel), "by_maker": _rows(by_maker),
		"recent": [],
	}
	out.by_model.sort_custom(func(a, b): return int(a.profit) > int(b.profit))
	out.by_grade.sort_custom(func(a, b): return GRADES.find(a.key) < GRADES.find(b.key))
	for i in range(n - 1, maxi(n - 30, 0) - 1, -1):
		out.recent.append(_brief(sales[i]))
	# Units sold per trading day (orders placed), from the day log + today.
	var days: Array = st.stats.days
	var sold_days := 0
	for d: Dictionary in days:
		sold_days += int(d.get("sold", 0))
	out["per_day"] = _avg(float(sold_days), days.size())
	return out


static func _sum(arr: Array) -> float:
	var t := 0.0
	for v in arr:
		t += float(v)
	return t


static func _group(groups: Dictionary, key: String, rec: Dictionary) -> void:
	if key == "":
		key = "—"
	if not groups.has(key):
		groups[key] = {"key": key, "count": 0, "price": 0, "cost": 0, "parts": 0, "profit": 0, "days": 0.0, "days_n": 0}
	var g: Dictionary = groups[key]
	g.count += 1
	g.price += int(rec.price)
	g.cost += int(rec.cost)
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
		"cost": int(rec.cost) + int(rec.get("parts", 0)), "profit": int(rec.profit), "seller": rec.get("seller", ""),
		"on_time": rec.get("on_time", true), "days_in_stock": float(rec.get("days_in_stock", -1))}


static func _purchasing(st: GameState) -> Dictionary:
	var buys: Array = st.stats.purchases
	var units := 0
	var paid := 0
	var listed := 0
	var by_seller := {}
	var by_sector := {}
	for p: Dictionary in buys:
		units += int(p.count)
		paid += int(p.paid)
		listed += int(p.list_total)
	for lot: Dictionary in st.stats.lots.values():
		for key_group in [[by_seller, String(lot.seller)], [by_sector, String(lot.sector)]]:
			var groups: Dictionary = key_group[0]
			var key: String = key_group[1]
			if not groups.has(key):
				groups[key] = {"key": key, "lots": 0, "units": 0, "paid": 0, "tested": 0, "passed": 0, "faults": 0,
					"graded": 0, "grade_pts": 0.0, "sold": 0, "revenue": 0, "profit": 0}
			var g: Dictionary = groups[key]
			g.lots += 1
			g.units += int(lot.count)
			g.paid += int(lot.paid)
			g.tested += int(lot.tested)
			g.passed += int(lot.passed)
			g.faults += int(lot.faults)
			g.sold += int(lot.sold)
			g.revenue += int(lot.revenue)
			g.profit += int(lot.profit)
			for gr in lot.grades:
				g.graded += int(lot.grades[gr])
				g.grade_pts += int(lot.grades[gr]) * {"A": 4.0, "B": 3.0, "C": 2.0, "D": 1.0, "S": 0.0}.get(gr, 0.0)
	var recent: Array = []
	for i in range(buys.size() - 1, maxi(buys.size() - 20, 0) - 1, -1):
		var p: Dictionary = buys[i]
		var lot: Dictionary = st.stats.lots.get(str(p.lot), {})
		recent.append({"day": int(p.day), "seller": p.seller, "summary": p.summary, "count": int(p.count), "paid": int(p.paid),
			"unit_price": int(p.unit_price), "tested": int(lot.get("tested", 0)), "passed": int(lot.get("passed", 0)),
			"sold": int(lot.get("sold", 0)), "profit": int(lot.get("profit", 0))})
	var incoming: Array = []
	for lot: Dictionary in st.lots.values():
		if lot.status == "bought":
			incoming.append({"seller": lot.seller_name, "count": int(lot.count), "paid": int(lot.get("paid", lot.total_price))})
	return {
		"lots": buys.size(), "units": units, "spent": paid, "avg_lot_units": _avg(units, buys.size()),
		"avg_lot_price": _avg(paid, buys.size()), "avg_unit_cost": _avg(paid, units),
		"discount": listed - paid, "discount_pct": _pct(listed - paid, listed),
		"by_seller": _seller_rows(by_seller), "by_sector": _seller_rows(by_sector), "recent": recent, "incoming": incoming,
	}


static func _seller_rows(groups: Dictionary) -> Array:
	var out: Array = []
	for g: Dictionary in groups.values():
		out.append({"key": g.key, "lots": int(g.lots), "units": int(g.units), "avg_cost": _avg(g.paid, int(g.units)),
			"pass_pct": _pct(g.passed, g.tested), "tested": int(g.tested), "faults_per_unit": _avg(g.faults, int(g.tested)),
			"avg_grade": _avg(g.grade_pts, int(g.graded)), "graded": int(g.graded), "sold": int(g.sold),
			"profit": int(g.profit), "profit_per_unit": _avg(g.profit, int(g.sold)), "paid": int(g.paid)})
	out.sort_custom(func(a, b): return int(a.units) > int(b.units))
	return out


static func _stock(st: GameState) -> Dictionary:
	var stages := {}
	for s in [UnitData.STAGE_UNTESTED, UnitData.STAGE_FAULTY, UnitData.STAGE_TO_IMAGE, UnitData.STAGE_TO_GRADE,
			UnitData.STAGE_GRADED, UnitData.STAGE_LISTED, UnitData.STAGE_SOLD]:
		stages[s] = {"key": s, "count": 0, "cost": 0, "market": 0}
	var listed: Array = []
	var ages: Array = []
	var oldest: Array = []
	var total_cost := 0
	var total_market := 0
	for it: Item in st.units_in_stock():
		var u := it.unit
		var stage := u.stage()
		if not stages.has(stage):
			continue
		var market := Valuation.market_value(u)
		var g: Dictionary = stages[stage]
		g.count += 1
		g.cost += u.cost + u.parts_cost
		g.market += market
		if stage != UnitData.STAGE_SOLD:
			total_cost += u.cost + u.parts_cost
			total_market += market
		var age := working_days(u.received, st.minute) if u.received >= 0.0 else 0.0
		ages.append(age)
		if stage != UnitData.STAGE_SOLD:
			oldest.append({"model": u.display_name(), "stage": u.stage_text(), "age": age, "cost": u.cost + u.parts_cost,
				"grade": u.grade})
		if stage == UnitData.STAGE_LISTED:
			var fee := float(u.list_price) * float(Content.econ("flogit_fee_pct", 0.1))
			var net := float(u.list_price) - fee - float(Content.econ("flogit_postage", 9)) - packaging_cost()
			listed.append({"model": u.display_name(), "grade": u.grade, "cost": u.cost + u.parts_cost,
				"price": u.list_price, "guide": Valuation.market_value(u, u.grade),
				"exp_profit": int(round(net)) - u.cost - u.parts_cost,
				"listed_days": working_days(u.listed_at, st.minute) if u.listed_at >= 0.0 else 0.0})
	oldest.sort_custom(func(a, b): return float(a.age) > float(b.age))
	listed.sort_custom(func(a, b): return float(a.listed_days) > float(b.listed_days))
	var l_cost := 0.0
	var l_price := 0.0
	var l_guide := 0.0
	var l_profit := 0.0
	for x: Dictionary in listed:
		l_cost += x.cost
		l_price += x.price
		l_guide += x.guide
		l_profit += x.exp_profit
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
	var days: Array = st.stats.days
	if not days.is_empty():
		var recent := days.slice(maxi(days.size() - 5, 0))
		for d: Dictionary in recent:
			per_day += int(d.get("shipped", 0))
		per_day /= recent.size()
	var consumables: Array = []
	for k in ["boxes", "labels", "tape"]:
		var have := int(st.consumables.get(k, 0))
		consumables.append({"key": k, "count": have, "days_left": have / per_day if per_day > 0.0 else -1.0})
	var stage_rows: Array = stages.values()
	var n_listed := listed.size()
	return {
		"units": st.units_in_stock().size(), "cost_value": total_cost, "market_value": total_market,
		"potential_profit": total_market - total_cost, "stages": stage_rows,
		"avg_age_days": _avg(_sum(ages), ages.size()), "oldest": oldest.slice(0, 8),
		"listed": listed.slice(0, 40), "listed_count": n_listed, "listed_avg_cost": _avg(l_cost, n_listed),
		"listed_avg_price": _avg(l_price, n_listed), "listed_avg_guide": _avg(l_guide, n_listed),
		"listed_avg_profit": _avg(l_profit, n_listed), "listed_vs_guide_pct": _pct(l_price, l_guide),
		"listed_value": int(l_price),
		"parts": parts_rows, "parts_value": parts_value, "parts_units": parts_units, "consumables": consumables,
		"pallets_unopened": st.items.values().filter(func(i): return i.kind == Item.KIND_PALLET).size(),
	}


static func _operations(st: GameState) -> Dictionary:
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
	# Average daily throughput from the day log.
	var days: Array = s.days
	var thr := {}
	for k in ["tested", "repaired", "imaged", "graded", "shipped"]:
		var t := 0
		for d: Dictionary in days:
			t += int(d.get(k, 0))
		thr[k] = _avg(t, days.size())
	var tests := int(s.tests.run)
	return {
		"tests": tests, "passed": int(s.tests.passed), "pass_pct": _pct(int(s.tests.passed), tests),
		"faults_total": total_faults, "faults_per_unit": _avg(total_faults, tests), "faults": faults,
		"repairs": repaired, "parts": parts, "parts_used": parts_n, "parts_value": int(s.parts_value),
		"parts_per_repair": _avg(int(s.parts_value), repaired),
		"graded": graded, "grades": grades, "throughput": thr,
		"today": st.day_stats.duplicate(),
	}


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
	var value := 0.0
	for rec: Dictionary in s.sales:
		dispatch.append(float(rec.get("dispatch_h", 0.0)))
		value += int(rec.price)
	var shipped := int(s.orders.on_time) + int(s.orders.late)
	var days: Array = s.days
	var rep_week := st.reputation - (float(days[maxi(days.size() - 5, 0)].get("rep", st.reputation)) if not days.is_empty() else st.reputation)
	var ratings := 0.0
	var best: Dictionary = {}
	for d: Dictionary in days:
		ratings += int(d.get("rating", 0))
		if best.is_empty() or int(d.get("rating", 0)) > int(best.get("rating", 0)):
			best = d
	return {
		"placed": int(s.orders.placed), "shipped": shipped, "on_time": int(s.orders.on_time), "late": int(s.orders.late),
		"cancelled": int(s.orders.cancelled), "on_time_pct": _pct(int(s.orders.on_time), shipped),
		"to_pack": open_to_pack, "packed": open_packed, "overdue": overdue,
		"avg_dispatch_h": _avg(_sum(dispatch), dispatch.size()), "median_dispatch_h": _median(dispatch),
		"avg_order_value": _avg(value, s.sales.size()), "reputation": st.reputation,
		"rep_max": float(Content.econ("rep_max", 100)), "rep_week": rep_week,
		"avg_rating": _avg(ratings, days.size()), "rep_series": days.map(func(d): return float(d.get("rep", 0.0))),
	}


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
	for d: Dictionary in days:
		rev += int(d.get("revenue", 0))
		prof += int(d.get("profit", 0))
		if best.is_empty() or int(d.profit) > int(best.profit):
			best = d
		if worst.is_empty() or int(d.profit) < int(worst.profit):
			worst = d
	var last := days.slice(maxi(days.size() - 5, 0))
	var week_net := 0.0
	for d: Dictionary in last:
		week_net += int(d.get("profit", 0))
	var b := st.building()
	var wages := 0       # staff arrive in Milestone 4; their wages will land in the "wages" ledger category
	var fixed := int(b.get("rent_weekly", 0)) + int(b.get("utilities_weekly", 0)) + wages
	var avg_unit_profit: float = r.sales.avg_profit
	var daily_net := _avg(week_net, last.size())
	return {
		"income": income, "costs": costs, "net": income - costs, "categories": cats,
		"revenue": int(m.get("sales", 0)), "fees": -int(m.get("fees", 0)), "postage": -int(m.get("postage", 0)),
		"stock_bought": -int(m.get("purchasing", 0)), "shop": -int(m.get("shop", 0)), "rent": -int(m.get("rent", 0)),
		"utilities": -int(m.get("utilities", 0)), "wages": -int(m.get("wages", 0)), "equipment": -int(m.get("equipment", 0)),
		"premises": -int(m.get("premises", 0)), "bank": -int(m.get("bank", 0)),
		"cogs": int(r.sales.avg_cost * r.sales.count + r.sales.avg_parts * r.sales.count),
		"gross_on_sold": int(r.sales.profit),
		"days": days.size(), "avg_rev_day": _avg(rev, days.size()), "avg_profit_day": _avg(prof, days.size()),
		"avg_profit_week": daily_net * 5.0, "best_day": best, "worst_day": worst,
		"fixed_weekly": fixed, "wages_weekly": wages,
		"breakeven_units_week": float(fixed) / avg_unit_profit if avg_unit_profit > 0.0 else -1.0,
		"runway_days": float(st.cash) / -daily_net if daily_net < 0.0 and st.cash > 0 else -1.0,
		"cash": st.cash, "peak_cash": int(st.stats.peak_cash), "low_cash": int(st.stats.low_cash),
		"series_revenue": days.map(func(d): return float(d.get("revenue", 0))),
		"series_profit": days.map(func(d): return float(d.get("profit", 0))),
		"series_cash": days.map(func(d): return float(d.get("cash", 0))),
		"series_days": days.map(func(d): return "%s %d" % [String(d.get("weekday", "")).substr(0, 3), int(d.get("day", 0)) + 1]),
	}


static func _people(st: GameState, r: Dictionary) -> Dictionary:
	var rows: Array = []
	var minutes := 0.0
	for key in st.players:
		person(st, key)
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
		for a in ACTS:
			row[a] = int(p.acts.get(a, 0))
		rows.append(row)
	rows.sort_custom(func(a, b): return float(a.hours) > float(b.hours))
	var hours := minutes / 60.0
	var person_days := 0
	for row: Dictionary in rows:
		person_days += int(row.days)
	var revenue := float(r.sales.revenue)
	var net := float(r.finance.net)
	return {
		"team": rows, "team_size": rows.size(), "hours": hours,
		"avg_hours_person": _avg(hours, rows.size()), "avg_hours_day": _avg(hours, person_days),
		"revenue_per_hour": _avg(revenue, int(ceil(hours))) if hours >= 1.0 else 0.0,
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
