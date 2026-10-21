class_name BusinessStats
extends RefCounted
## Long-term business history for the Insights app. Sim calls the on_* hooks as things happen; they append to
## GameState.stats (host only, saved, never replicated). BusinessReport turns it into the numbers on screen.

const MAX_SALES := 3000
const MAX_PURCHASES := 1500
const MAX_DAYS := 500
const MAX_EVENTS := 400
const GRADES := ["A", "B", "C", "D", "S"]
const ACTS := ["hauled", "tested", "repaired", "imaged", "graded", "listed", "packed", "lots_bought", "units_bought"]
const SOLD_MARKS := [1, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000]
const REVENUE_MARKS := [1000, 5000, 10000, 25000, 50000, 100000, 250000, 500000, 1000000]
const REP_MARKS := [25, 50, 75, 100]
const BATTERY_BANDS := [[0, 50, "Under 50%"], [50, 70, "50–69%"], [70, 80, "70–79%"], [80, 90, "80–89%"], [90, 101, "90%+"]]


static func blank() -> Dictionary:
	return {
		"sales": [],          # one record per shipped unit (see on_ship)
		"purchases": [],      # one record per lot bought
		"days": [],           # end-of-day summaries
		"events": [],         # company timeline: milestones, records, moves, unlocks
		"money": {},          # ledger category -> all-time total (the ledger itself is trimmed)
		"lots": {},           # str(lot id) -> what became of that lot
		"models": {},         # model id -> test outcomes (reliability)
		"cosmetic": {},       # condition id -> units seen with it
		"battery": {},        # band label -> units
		"faults_found": {},   # fault id -> count
		"repairs": {},        # fault id -> count
		"parts_used": {},     # part id -> count
		"parts_value": 0,
		"tests": {"run": 0, "passed": 0},
		"grades": {},         # grade -> count
		"orders": {"placed": 0, "on_time": 0, "late": 0, "cancelled": 0},
		"demand": {},         # "weekday_hour" -> orders placed
		"stations": {},       # station id -> { busy, jobs, test, image, since }
		"fixture_since": {},  # fixture id -> open_minutes when bought
		"open_minutes": 0.0,  # working minutes the business has been open
		"people": {},         # player key -> { name, minutes, days_worked, last_day, acts{} }
		"streak": {"on_time": 0, "best_on_time": 0},
		"flags": {},          # one-off event bookkeeping
		"peak_cash": -2147483648, "low_cash": 2147483647,
		"auto": {"kinds": {}, "fixtures": {}, "today": {}, "hours": {}},   # automation events (see on_auto)
		"energy": {"kwh": 0.0, "cost": 0},   # all-time electricity (daily figures live in `days`)
		"reviews": [],        # one record per Flogit review (see on_review)
		"business": {"offers": 0, "accepted": 0, "declined": 0, "expired": 0, "complete": 0, "on_time": 0, "late": 0,
			"cancelled": 0, "revenue": 0, "units": 0, "builds": 0, "build_parts": 0},
		"upgrades": {"count": 0, "ram": 0, "storage": 0, "spent": 0, "gain": 0},
	}


static func migrate(d: Dictionary) -> Dictionary:
	var out := blank()
	for k in d:
		out[k] = d[k]
	return out.duplicate(true)


static func compute(st: GameState) -> Dictionary:
	return BusinessReport.compute(st)


# ================================================================= general hooks

static func event(st: GameState, kind: String, text: String) -> void:
	_push(st.stats.events, {"m": st.minute, "day": st.day_index(), "kind": kind, "text": text}, MAX_EVENTS)


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


static func on_clock(st: GameState, peers: Array, mins: float) -> void:
	## Called by the clock while the working day runs: the business is open and everyone connected is on shift.
	st.stats.open_minutes = float(st.stats.open_minutes) + mins
	var day := st.day_index()
	for peer in peers:
		var p := person(st, Sim.player_key(int(peer)))
		p.minutes = float(p.minutes) + mins
		if int(p.last_day) != day:
			p.last_day = day
			p.days_worked = int(p.days_worked) + 1


static func on_level_up(st: GameState, key: String, skill: String, level: int) -> void:
	event(st, "skill", "%s reached %s level %d" % [st.players.get(key, {}).get("name", key), skill.capitalize(), level])


static func on_fixture_bought(st: GameState, id: String, name: String, price: int) -> void:
	st.stats.fixture_since[id] = float(st.stats.open_minutes)
	event(st, "equipment", "Bought %s (%s)" % [name, GameState.format_money(price)])


static func on_moved(st: GameState, building: Dictionary) -> void:
	event(st, "premises", "Moved to %s, %s" % [building.get("name", ""), building.get("address", "")])


# ================================================================= purchasing

static func price_index(lot: Dictionary) -> float:
	## Lot price vs typical trade price for its models (100 = typical; public info, no hidden quality).
	var ref := 0.0
	var models: Array = lot.get("models", [])
	for mid in models:
		ref += Valuation.guide_price(mid, "A")
	ref = ref / maxf(models.size(), 1) * float(Content.econ("lot_trade_ratio", 0.4)) * 0.95
	return 100.0 * float(lot.unit_price) / maxf(ref, 1.0)


static func on_lot_bought(st: GameState, lot: Dictionary, paid: int, peer: int) -> void:
	var idx := price_index(lot)
	var rec := {
		"day": st.day_index(), "m": st.minute, "lot": int(lot.id), "seller_id": lot.get("seller_id", ""),
		"seller": lot.get("seller_name", ""), "sector": lot.get("sector", ""), "count": int(lot.count),
		"paid": paid, "list_total": int(lot.total_price), "unit_price": int(round(paid / maxf(float(lot.count), 1.0))),
		"summary": lot.get("summary", ""), "note": lot.get("note", ""), "idx": idx, "age": int(lot.get("age_years", 0)),
	}
	_push(st.stats.purchases, rec, MAX_PURCHASES)
	st.stats.lots[str(lot.id)] = {"seller_id": rec.seller_id, "seller": rec.seller, "sector": rec.sector, "count": rec.count,
		"paid": paid, "idx": idx, "day": rec.day, "summary": rec.summary, "tested": 0, "passed": 0, "faults": 0,
		"grades": {}, "sold": 0, "revenue": 0, "net": 0, "profit": 0}
	act(st, peer, "lots_bought")
	act(st, peer, "units_bought", int(lot.count))
	event(st, "lot", "Bought %d units from %s for %s" % [rec.count, rec.seller, GameState.format_money(paid)])


static func _lot(st: GameState, u: UnitData) -> Dictionary:
	return st.stats.lots.get(str(u.lot_id), {})


# ================================================================= workshop

static func on_test(st: GameState, u: UnitData, by: int) -> void:
	var s := st.stats
	u.tested_at = st.minute
	s.tests.run = int(s.tests.run) + 1
	var nf := u.faults.size()
	if nf == 0:
		s.tests.passed = int(s.tests.passed) + 1
	for f in u.faults:
		s.faults_found[f.id] = int(s.faults_found.get(f.id, 0)) + 1
	# Reliability per model.
	if not s.models.has(u.model_id):
		s.models[u.model_id] = {"tested": 0, "passed": 0, "faults": 0, "fault_ids": {}, "battery": 0, "cosmetic": 0, "grades": {}}
	var m: Dictionary = s.models[u.model_id]
	m.tested += 1
	m.passed += 1 if nf == 0 else 0
	m.faults += nf
	m.battery += u.battery_pct
	m.cosmetic += u.cosmetic_score()
	for f in u.faults:
		m.fault_ids[f.id] = int(m.fault_ids.get(f.id, 0)) + 1
	for c in u.cosmetic:
		s.cosmetic[c] = int(s.cosmetic.get(c, 0)) + 1
	if u.cosmetic.is_empty():
		s.cosmetic["_pristine"] = int(s.cosmetic.get("_pristine", 0)) + 1
	for b in BATTERY_BANDS:
		if u.has_battery() and u.battery_pct >= int(b[0]) and u.battery_pct < int(b[1]):
			s.battery[b[2]] = int(s.battery.get(b[2], 0)) + 1
	var lot := _lot(st, u)
	if not lot.is_empty():
		lot.tested = int(lot.tested) + 1
		lot.faults = int(lot.faults) + nf
		if nf == 0:
			lot.passed = int(lot.passed) + 1
	act(st, by, "tested")


static func on_job(st: GameState, station_id: String, kind: String, minutes: float) -> void:
	var s := st.stats
	if not s.stations.has(station_id):
		s.stations[station_id] = {"busy": 0.0, "jobs": 0, "test": 0, "image": 0,
			"since": float(s.fixture_since.get(station_id, 0.0))}
	var r: Dictionary = s.stations[station_id]
	r.busy = float(r.busy) + minutes
	r.jobs = int(r.jobs) + 1
	r[kind] = int(r.get(kind, 0)) + 1


static func on_image(st: GameState, u: UnitData, by: int) -> void:
	u.imaged_at = st.minute
	act(st, by, "imaged")


static func on_repair(st: GameState, u: UnitData, fault_id: String, part_id: String, part_cost: int, peer: int) -> void:
	var s := st.stats
	if s.repairs.is_empty():
		event(st, "first", "First repair: %s" % Content.fault(fault_id).get("name", fault_id))
	s.repairs[fault_id] = int(s.repairs.get(fault_id, 0)) + 1
	s.parts_used[part_id] = int(s.parts_used.get(part_id, 0)) + 1
	s.parts_value = int(s.parts_value) + part_cost
	u.parts_cost += part_cost
	u.repaired_at = st.minute
	act(st, peer, "repaired")


static func on_grade(st: GameState, u: UnitData, peer: int) -> void:
	u.graded_at = st.minute
	st.stats.grades[u.grade] = int(st.stats.grades.get(u.grade, 0)) + 1
	var m: Dictionary = st.stats.models.get(u.model_id, {})
	if not m.is_empty():
		m.grades[u.grade] = int(m.grades.get(u.grade, 0)) + 1
	var lot := _lot(st, u)
	if not lot.is_empty():
		lot.grades[u.grade] = int(lot.grades.get(u.grade, 0)) + 1
	act(st, peer, "graded")


static func on_listed(st: GameState, u: UnitData, peer: int) -> void:
	if u.listed_at < 0.0:
		u.listed_at = st.minute
	act(st, peer, "listed")


# ================================================================= sales

static func packaging_cost() -> float:
	## One box, one label and some tape per parcel, at Shop prices.
	var total := 0.0
	for s: Dictionary in Content.shop_items.values():
		if s.get("kind") == "consumable" and s.get("consumable") in ["boxes", "labels", "tape"]:
			total += float(s.price) / maxf(float(s.qty), 1.0)
	return total


static func on_order(st: GameState, u: UnitData) -> void:
	u.sold_at = st.minute
	st.stats.orders.placed = int(st.stats.orders.placed) + 1
	var key := "%d_%d" % [st.weekday(), int(st.minute_of_day() / 60.0)]
	st.stats.demand[key] = int(st.stats.demand.get(key, 0)) + 1


static func on_cancel(st: GameState) -> void:
	st.stats.orders.cancelled = int(st.stats.orders.cancelled) + 1
	st.stats.streak.on_time = 0


static func stage_hours(u: UnitData, end_m: float) -> Dictionary:
	## Working hours spent between each step of the refurb pipeline (only steps that happened).
	var out := {}
	var steps := [["test", u.tested_at], ["repair", u.repaired_at], ["image", u.imaged_at], ["grade", u.graded_at],
		["list", u.listed_at], ["sell", u.sold_at], ["ship", end_m]]
	var prev := u.received
	for s in steps:
		var t := float(s[1])
		if t < 0.0:
			continue
		if prev >= 0.0:
			out[s[0]] = working_minutes(prev, t) / 60.0
		prev = t
	return out


static func on_ship(st: GameState, order: Dictionary, u: UnitData, fee: int, post: int, on_time: bool) -> void:
	var s := st.stats
	var price := int(order.price)
	var cost := u.cost if u else 0
	var parts := u.parts_cost if u else 0
	var pack := int(round(packaging_cost()))
	var profit := price - fee - post - pack - cost - parts
	var rec := {
		"day": st.day_index(), "m": st.minute, "order": int(order.id), "channel": order.get("channel", "flogit"),
		"model": order.get("model", ""), "grade": order.get("grade", ""), "buyer": order.get("buyer", ""),
		"placed": float(order.get("placed", st.minute)), "price": price, "cost": cost, "parts": parts, "fee": fee,
		"post": post, "pack": pack, "profit": profit, "on_time": on_time,
		"late_h": 0.0 if on_time else working_minutes(float(order.due), st.minute) / 60.0,
		"dispatch_h": working_minutes(float(order.get("placed", st.minute)), st.minute) / 60.0,
	}
	if u:
		rec.merge({
			"model_id": u.model_id, "type": u.hw_type(), "maker": Content.maker_name(u.model().get("maker", "")),
			"guide": Valuation.market_value(u, u.grade), "spares_guide": Valuation.market_value(u, "S"),
			"seller": u.seller_name, "lot": u.lot_id, "ram": u.ram_gb, "storage": u.storage_gb, "cpu": u.cpu_name(),
			"battery": u.battery_pct, "faults": u.faults.size(),
			"repaired": u.faults.filter(func(f): return f.get("fixed", false)).size(),
			"days_in_stock": working_days(u.received, u.sold_at) if u.received >= 0.0 and u.sold_at >= 0.0 else -1.0,
			"process_days": working_days(u.received, u.graded_at) if u.received >= 0.0 and u.graded_at >= 0.0 else -1.0,
			"listed_days": working_days(u.listed_at, u.sold_at) if u.listed_at >= 0.0 and u.sold_at >= 0.0 else -1.0,
			"stages": stage_hours(u, st.minute),
		})
	_push(s.sales, rec, MAX_SALES)
	if on_time:
		s.orders.on_time = int(s.orders.on_time) + 1
		s.streak.on_time = int(s.streak.on_time) + 1
		s.streak.best_on_time = maxi(int(s.streak.best_on_time), int(s.streak.on_time))
	else:
		s.orders.late = int(s.orders.late) + 1
		s.streak.on_time = 0
	if u:
		var lot := _lot(st, u)
		if not lot.is_empty():
			lot.sold = int(lot.sold) + 1
			lot.revenue = int(lot.revenue) + price
			lot.net = int(lot.net) + price - fee - post - pack - parts
			lot.profit = int(lot.profit) + profit
	# Milestones
	var sold := int(s.orders.on_time) + int(s.orders.late)
	if sold in SOLD_MARKS:
		event(st, "milestone", "First sale! %s to %s for £%d" % [rec.model, rec.buyer, price] if sold == 1 else "%d units sold" % sold)
	var revenue := int(s.money.get("sales", 0))
	for mark in REVENUE_MARKS:
		if revenue >= mark and revenue - price < mark:
			event(st, "milestone", "%s in total sales" % GameState.format_money(mark))


# ================================================================= reviews, business buyers, upgrades

const MAX_REVIEWS := 1500


static func on_review(st: GameState, order: Dictionary, r: Dictionary) -> void:
	_push(st.stats.reviews, {"day": st.day_index(), "m": st.minute, "order": int(order.get("id", 0)), "stars": int(r.stars),
		"reason": str(r.get("reason", "")), "buyer": str(r.get("buyer", "")), "model": str(r.get("model", "")),
		"grade": str(r.get("grade", "")), "text": str(r.get("text", ""))}, MAX_REVIEWS)
	var n: Array = st.stats.reviews
	if n.size() == 1:
		event(st, "first", "First Flogit review: %s \"%s\"" % [Reviews.stars_text(int(r.stars)), r.get("text", "")])


static func business(st: GameState) -> Dictionary:
	var b: Dictionary = st.stats.get("business", {})
	if b.is_empty():
		b = blank().business
		st.stats["business"] = b
	return b


static func on_contract(st: GameState, c: Dictionary, what: String) -> void:
	## what: offer, accepted, declined, expired, complete, late, cancelled
	var b := business(st)
	b[what] = int(b.get(what, 0)) + 1
	var name := "%s — %s" % [c.get("buyer", ""), Business.title(c)]
	match what:
		"accepted":
			if int(b.accepted) == 1:
				event(st, "first", "First business contract: %s" % name)
			else:
				event(st, "business", "Accepted %s (%s)" % [name, GameState.format_money(Business.value(c))])
		"complete":
			b.on_time = int(b.get("on_time", 0)) + (0 if c.get("late", false) else 1)
			b.revenue = int(b.get("revenue", 0)) + int(c.get("revenue", 0))
			b.units = int(b.get("units", 0)) + int(c.get("shipped", 0))
			if c.get("kind", "") == "build":
				b.builds = int(b.get("builds", 0)) + 1
			event(st, "business", "Delivered %s%s — %s" % [name, " (late)" if c.get("late", false) else "", GameState.format_money(int(c.get("revenue", 0)))])
		"cancelled":
			event(st, "warning", "%s cancelled their order (%s)" % [c.get("buyer", ""), Business.title(c)])


static func on_build_part(st: GameState, u: UnitData, spent: int, back: int, peer: int) -> void:
	var b := business(st)
	b.build_parts = int(b.get("build_parts", 0)) + spent - back
	u.parts_cost += spent - back
	act(st, peer, "built")


static func on_upgrade(st: GameState, u: UnitData, o: Dictionary, peer: int) -> void:
	var up: Dictionary = st.stats.get("upgrades", {})
	if up.is_empty():
		up = blank().upgrades
		st.stats["upgrades"] = up
	up.count = int(up.get("count", 0)) + 1
	up[str(o.kind)] = int(up.get(str(o.kind), 0)) + 1
	up.spent = int(up.get("spent", 0)) + int(o.cost) - int(o.back)
	up.gain = int(up.get("gain", 0)) + int(o.gain)
	u.parts_cost += int(o.cost) - int(o.back)
	act(st, peer, "upgraded")


# ================================================================= automation

## Automation calls this whenever a machine does something worth counting. `kind` is one of:
## belt_in (item put on a belt), scan (goods-in booked), sort (sorter routed), arm (arm moved an item),
## rack_test / rack_image (rack bay finished), robot_repair / robot_pack, tower_in / tower_out, recycle, grader,
## or any new kind a machine reports. `fid` = the fixture that did it.
static func on_auto(st: GameState, kind: String, fid: String = "", n: int = 1) -> void:
	var a: Dictionary = st.stats.get("auto", {})
	if a.is_empty():
		a = {"kinds": {}, "fixtures": {}, "today": {}, "hours": {}}
		st.stats["auto"] = a
	a.kinds[kind] = int(a.kinds.get(kind, 0)) + n
	a.today[kind] = int(a.today.get(kind, 0)) + n
	if fid != "":
		var f: Dictionary = a.fixtures.get(fid, {})
		f[kind] = int(f.get(kind, 0)) + n
		a.fixtures[fid] = f
	# Machine moves per working hour, for the throughput chart (last ~2 weeks kept).
	var hk := "%d_%d" % [st.day_index(), int(st.minute_of_day() / 60.0)]
	a.hours[hk] = int(a.hours.get(hk, 0)) + n
	if a.hours.size() > 160:
		var keys: Array = a.hours.keys()
		keys.sort_custom(func(x, y): return _hour_key_num(x) < _hour_key_num(y))
		for i in keys.size() - 160:
			a.hours.erase(keys[i])


static func _hour_key_num(k: String) -> int:
	var parts := k.split("_")
	return int(parts[0]) * 100 + int(parts[1])


# ================================================================= day end

static func on_day_end(st: GameState, summary: Dictionary) -> void:
	var s := st.stats
	var prev: Array = s.days
	var rec := {}
	for k in ["day", "weekday", "income", "spend", "profit", "revenue", "tested", "repaired", "imaged", "graded",
			"sold", "shipped", "late", "missed", "bought", "rating", "cash"]:
		rec[k] = summary.get(k, 0)
	rec["rep"] = st.reputation
	rec["kwh"] = snappedf(float(st.day_stats.get("kwh", 0.0)), 0.01)
	rec["power_cost"] = int(summary.get("electric", 0))
	rec["wages"] = int(summary.get("wages", 0))
	var auto: Dictionary = s.get("auto", {})
	rec["auto"] = auto.get("today", {}).duplicate()
	if not auto.is_empty():
		auto["today"] = {}
	var en: Dictionary = s.get("energy", {"kwh": 0.0, "cost": 0})
	en["kwh"] = float(en.get("kwh", 0.0)) + float(rec.kwh)
	en["cost"] = int(en.get("cost", 0)) + int(rec.power_cost)
	s["energy"] = en
	# Records (only once there's a little history to beat).
	if prev.size() >= 3:
		var best_rev := 0
		var best_ship := 0
		for d: Dictionary in prev:
			best_rev = maxi(best_rev, int(d.get("revenue", 0)))
			best_ship = maxi(best_ship, int(d.get("shipped", 0)))
		if int(rec.revenue) > best_rev:
			event(st, "record", "Record day: %s in sales" % GameState.format_money(int(rec.revenue)))
		elif int(rec.shipped) > best_ship:
			event(st, "record", "Record day: %d orders shipped" % int(rec.shipped))
	_push(prev, rec, MAX_DAYS)
	# Reputation marks and sellers who now trust you.
	var flags: Dictionary = s.flags
	for mark in REP_MARKS:
		var k := "rep_%d" % mark
		if st.reputation >= mark and not flags.has(k):
			flags[k] = true
			event(st, "reputation", "Reputation reached %d" % mark)
	for seller: Dictionary in Content.sellers.values():
		var k := "seller_" + String(seller.id)
		if float(seller.get("min_rep", 0)) > 0.0 and st.reputation >= float(seller.min_rep) and not flags.has(k):
			flags[k] = true
			event(st, "unlock", "%s will now sell to you" % seller.name)
	if st.cash < 0 and not flags.has("overdrawn"):
		flags["overdrawn"] = true
		event(st, "warning", "Went overdrawn (%s)" % GameState.format_money(st.cash))
	elif st.cash >= 0:
		flags.erase("overdrawn")


# ================================================================= helpers

static func _push(arr: Array, rec: Dictionary, cap: int) -> void:
	arr.append(rec)
	if arr.size() > cap:
		arr.pop_front()


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


static func day_minutes() -> float:
	return (float(Content.econ("day_end_hour", 17)) - float(Content.econ("day_start_hour", 9))) * 60.0


static func working_days(a: float, b: float) -> float:
	return working_minutes(a, b) / day_minutes()
