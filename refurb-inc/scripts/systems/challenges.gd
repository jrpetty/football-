class_name Challenges
extends RefCounted
## Challenge scenarios and the daily run (data/challenges.json).
##   • A challenge is a new company with its own start (cash, premises, equipment, staff, stock), a goal, a
##     working-day limit and medals. It plays with the normal systems — only the start and the scoring differ.
##   • The daily run is generated from the (UTC) date: the same seed and start for everyone that day, one attempt.
## The live challenge sits in GameState.totals["challenge"], so it saves and replicates to co-op guests. Sim calls
## check() as the clock runs and on_day_end() at close; the finish fires GameState.challenge_finished on the host,
## which the HUD turns into a local record (submit) — one board per challenge, CHALLENGE_<ID> / DAILY_<yyyymmdd>,
## named for Steam leaderboards, the same hook style as Campaign.submit.

static var records_path := "user://challenge_records.json"
static var _data := {}

const MEDALS := ["gold", "silver", "bronze"]
const MEDAL_COLOURS := {"gold": Color(0.98, 0.78, 0.26), "silver": Color(0.8, 0.83, 0.88), "bronze": Color(0.85, 0.55, 0.3)}
const MEDAL_ICONS := {"gold": "🥇", "silver": "🥈", "bronze": "🥉"}
const TOP_N := 10


static func data() -> Dictionary:
	if _data.is_empty():
		_data = JSON.parse_string(FileAccess.get_file_as_string("res://data/challenges.json"))
	return _data


static func defs() -> Array:
	return data().get("challenges", [])


static func get_def(id: String) -> Dictionary:
	if id == "daily":
		return daily_def(today_key())
	for d: Dictionary in defs():
		if str(d.id) == id:
			return d
	return {}


static func board(def: Dictionary) -> String:
	## Leaderboard name (Steam later): CHALLENGE_COUNCIL_REFRESH, DAILY_20261021.
	return str(def.get("board", "CHALLENGE_" + str(def.get("id", "")).to_upper()))


static func seed_of(def: Dictionary) -> int:
	if def.has("seed"):
		return int(def.seed)
	return absi(hash("REFURB_CHALLENGE_" + str(def.get("id", "")))) % 2147483646 + 1


static func is_score(goal: Dictionary) -> bool:
	return str(goal.get("kind", "")).begins_with("score_")


# ================================================================= the daily run

static func today_key() -> String:
	var d := Time.get_datetime_dict_from_system(true)       # UTC: the same day for everyone
	return "%04d%02d%02d" % [int(d.year), int(d.month), int(d.day)]


static func date_label(key: String) -> String:
	if key.length() != 8:
		return key
	var y := int(key.substr(0, 4))
	var m := int(key.substr(4, 2))
	var dd := int(key.substr(6, 2))
	var unix := Time.get_unix_time_from_datetime_dict({"year": y, "month": m, "day": dd, "hour": 12, "minute": 0, "second": 0})
	var wd := int(Time.get_datetime_dict_from_unix_time(unix).weekday)     # 0 = Sunday
	var names := ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
	var months := ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
	return "%s %d %s %d" % [names[wd], dd, months[clampi(m - 1, 0, 11)], y]


static func daily_def(key: String) -> Dictionary:
	## Deterministic from the date alone: everyone who plays on `key` gets this exact start and seed.
	var dd: Dictionary = data().get("daily", {})
	var rng := RandomNumberGenerator.new()
	rng.seed = hash("REFURB_DAILY_" + key)
	var variants: Array = dd.get("variants", [])
	var variant: Dictionary = variants[rng.randi() % variants.size()]
	var buildings: Array = dd.get("buildings", [{"id": "garage", "medal_mult": 1.0}])
	var b: Dictionary = buildings[rng.randi() % buildings.size()]
	var cash_r: Array = dd.get("cash", [3000, 9000])
	var cash := int(snappedf(rng.randi_range(int(cash_r[0]), int(cash_r[1])), 500.0))
	var rep_r: Array = dd.get("reputation", [8, 30])
	var rep := rng.randi_range(int(rep_r[0]), int(rep_r[1]))
	var count_r: Array = dd.get("stock_count", [8, 24])
	var count := rng.randi_range(int(count_r[0]), int(count_r[1]))
	var sellers: Array = dd.get("stock_sellers", ["northgate_bank"])
	var seller := str(sellers[rng.randi() % sellers.size()])
	var quality := snappedf(rng.randf_range(0.4, 0.8), 0.01)
	var start := {"cash": cash, "reputation": rep, "building": str(b.id),
		"stock": [{"seller": seller, "count": count, "quality": quality, "state": "untested"}]}
	if b.has("fixtures"):
		start["fixtures"] = b.fixtures
	if b.has("items"):
		start["items"] = b.items
	var mult := float(b.get("medal_mult", 1.0))
	var medals := {}
	for k in MEDALS:
		medals[k] = int(round(float(variant.get("medals", {}).get(k, 0)) * mult))
	var bname := str(Content.building(str(b.id)).get("name", b.id))
	var seller_name := str(Content.sellers.get(seller, {}).get("name", seller))
	return {
		"id": "daily", "daily": true, "date": key, "board": "DAILY_" + key,
		"seed": absi(hash("REFURB_DAILY_SEED_" + key)) % 2147483646 + 1,
		"name": "Daily run: %s" % str(variant.get("name", "")), "icon": str(variant.get("icon", "📅")), "variant": str(variant.get("id", "")),
		"desc": "%s %s, %s in the bank, reputation %d and %d untested units from %s on the drive." % [
			str(variant.get("desc", "")), bname, GameState.format_money(cash), rep, count, seller_name],
		"start": start, "goal": variant.get("goal", {"kind": "score_profit"}), "days": int(dd.get("days", 5)), "medals": medals,
		"difficulty": "Daily",
	}


# ================================================================= text

static func goal_text(def: Dictionary) -> String:
	var g: Dictionary = def.get("goal", {})
	var days := int(def.get("days", 5))
	match str(g.get("kind", "")):
		"process":
			return "Test, wipe and grade all %d units (or sell / recycle them) within %d working days" % [int(g.get("count", 0)), days]
		"building":
			return "Move into the %s within %d working days" % [str(Content.building(str(g.value)).get("name", g.value)).to_lower(), days]
		"profit":
			return "Make %s profit within %d working days" % [GameState.format_money(int(g.value)), days]
		"cash":
			return "Get %s in the bank within %d working days" % [GameState.format_money(int(g.value)), days]
		"ship":
			return "Ship %d orders within %d working days" % [int(g.value), days]
		"score_profit":
			return "Most profit in %d working days" % days
		"score_ship":
			return "Most orders shipped in %d working days" % days
	return ""


static func short_goal(ch: Dictionary) -> String:
	var g: Dictionary = ch.get("goal", {})
	match str(g.get("kind", "")):
		"process":
			return "Process %d units" % int(g.get("count", 0))
		"building":
			return "Move to the %s" % str(Content.building(str(g.value)).get("name", g.value)).to_lower()
		"profit":
			return "%s profit" % GameState.format_money(int(g.value))
		"cash":
			return "%s in the bank" % GameState.format_money(int(g.value))
		"ship":
			return "Ship %d orders" % int(g.value)
		"score_profit":
			return "Most profit"
		"score_ship":
			return "Most orders shipped"
	return ""


static func medal_text(def: Dictionary) -> String:
	var m: Dictionary = def.get("medals", {})
	var g: Dictionary = def.get("goal", {})
	var parts: PackedStringArray = []
	for k in MEDALS:
		if not m.has(k):
			continue
		var v := int(m[k])
		var t := ""
		if is_score(g):
			t = GameState.format_money(v) if str(g.kind) == "score_profit" else "%d shipped" % v
		else:
			t = "by day %d" % v
		parts.append("%s %s" % [str(k).capitalize(), t])
	return "  ·  ".join(parts)


static func value_text(ch: Dictionary, result: Dictionary) -> String:
	var g: Dictionary = ch.get("goal", {})
	if is_score(g):
		return GameState.format_money(int(result.get("score", 0))) if str(g.kind) == "score_profit" else "%d shipped" % int(result.get("score", 0))
	return "Day %d, %s" % [int(result.get("days", 0)), str(result.get("clock", ""))]


static func board_value_text(def: Dictionary, e: Dictionary) -> String:
	var g: Dictionary = def.get("goal", {})
	if is_score(g):
		return GameState.format_money(int(e.value)) if str(g.kind) == "score_profit" else "%d shipped" % int(e.value)
	return "%.2f days" % float(e.value)


# ================================================================= progress (host and guests)

static func progress(st: GameState, ch: Dictionary) -> Array:
	## [current, target] (target 0 for score goals).
	var g: Dictionary = ch.get("goal", {})
	match str(g.get("kind", "")):
		"process":
			return [float(processed(st, ch)), float(g.get("count", 1))]
		"building":
			return [float(mini(Campaign.tier_of(st.building_id), Campaign.tier_of(str(g.value)))), float(Campaign.tier_of(str(g.value)))]
		"profit", "score_profit":
			return [float(st.cash - int(ch.get("start_cash", 0))), float(g.get("value", 0))]
		"cash":
			return [float(st.cash), float(g.get("value", 0))]
		"ship", "score_ship":
			return [float(st.lifetime("shipped") - int(ch.get("start_shipped", 0))), float(g.get("value", 0))]
	return [0.0, 1.0]


static func processed(st: GameState, ch: Dictionary) -> int:
	## Starting-stock units that are done: graded, sold, shipped or recycled. Until they're all in, count what's left.
	var lots: Array = ch.get("lots", []).map(func(x): return int(x))     # (ints again after a JSON save)
	var total := int(ch.get("goal", {}).get("count", 0))
	var open := 0
	for it: Item in st.items.values():
		if it.kind != Item.KIND_UNIT or it.unit == null or not lots.has(it.unit.lot_id):
			continue
		if it.unit.shipped or it.loc.get("t") == "gone" or it.unit.grade != "" or it.unit.order_id != 0:
			continue
		open += 1
	# Units still on a lorry (not yet delivered) aren't items yet: they count as open.
	for lid in lots:
		var lot: Dictionary = st.lots.get(int(lid), {})
		if not lot.is_empty() and str(lot.get("status", "")) != "delivered":
			open += int(lot.get("count", 0))
	return clampi(total - open, 0, total)


static func reached(st: GameState, ch: Dictionary) -> bool:
	if is_score(ch.get("goal", {})):
		return false
	var pr := progress(st, ch)
	return float(pr[0]) >= float(pr[1])


static func days_left(st: GameState, ch: Dictionary) -> int:
	## Working days left including today.
	return maxi(int(ch.get("days", 0)) - Campaign.day_number(st) + 1, 0)


static func medal_for(ch: Dictionary, value: float) -> String:
	var m: Dictionary = ch.get("medals", {})
	var score := is_score(ch.get("goal", {}))
	for k in MEDALS:
		if not m.has(k):
			continue
		if (score and value >= float(m[k])) or (not score and value <= float(m[k])):
			return k
	return ""


# ================================================================= running (host)

static func check(sim: Sim) -> void:
	## As the clock runs: finish the moment a target goal is reached.
	var st := sim.state
	var ch := st.challenge()
	if ch.is_empty() or str(ch.get("status", "")) != "running" or not reached(st, ch):
		return
	var day := Campaign.day_number(st)
	var into := clampf((st.minute_of_day() - float(Content.econ("day_start_hour", 9)) * 60.0) / BusinessStats.day_minutes(), 0.0, 1.0)
	var t := float(day - 1) + into
	var medal := medal_for(ch, float(day))
	_finish(sim, "won", {"days": day, "time": snappedf(t, 0.001), "clock": st.clock_text(), "medal": medal, "value": snappedf(t, 0.001)})


static func on_day_end(sim: Sim, summary: Dictionary) -> void:
	## At close on the last day: a score goal is scored; a target goal not reached has failed.
	var st := sim.state
	var ch := st.challenge()
	if ch.is_empty() or str(ch.get("status", "")) != "running":
		return
	var day := int(summary.get("run_day", Campaign.day_number(st)))
	if day < int(ch.get("days", 0)):
		return
	if is_score(ch.get("goal", {})):
		var score := float(progress(st, ch)[0])
		_finish(sim, "done", {"days": day, "score": int(score), "medal": medal_for(ch, score), "value": int(score)})
	else:
		var pr := progress(st, ch)
		_finish(sim, "failed", {"days": day, "medal": "", "progress": [float(pr[0]), float(pr[1])]})


static func _finish(sim: Sim, status: String, result: Dictionary) -> void:
	var st := sim.state
	var ch: Dictionary = st.totals.get("challenge", {})
	ch["status"] = status
	ch["result"] = result
	st.totals["challenge"] = ch
	st.mark("company")
	var medal := str(result.get("medal", ""))
	match status:
		"won":
			st.say("🏁 CHALLENGE COMPLETE — %s on %s%s" % [ch.get("name", ""), value_text(ch, result), (", %s medal!" % medal) if medal != "" else " (too slow for a medal)"], "good")
		"done":
			st.say("🏁 TIME'S UP — %s: %s%s" % [ch.get("name", ""), value_text(ch, result), (", %s medal!" % medal) if medal != "" else ""], "good" if medal != "" else "info")
		"failed":
			st.say("🏁 Out of time — %s not completed." % ch.get("name", ""), "bad")
	BusinessStats.event(st, "milestone", "Challenge %s: %s" % [status, ch.get("name", "")])
	st.challenge_finished.emit(ch.duplicate(true))


static func apply_start(sim: Sim, def: Dictionary) -> void:
	## Host, straight after Sim.new_game: turn the fresh company into the challenge's starting position.
	var st := sim.state
	var s: Dictionary = def.get("start", {})
	if s.has("cash"):
		st.cash = int(s.cash)        # the opening balance (not income), like start_cash
	if s.has("reputation"):
		st.reputation = float(s.reputation)
	if s.has("building") and Content.buildings.has(str(s.building)):
		st.building_id = str(s.building)
	var first_of := {}
	for t in s.get("fixtures", []):
		var fid := sim.add_fixture(str(t))
		if fid != "" and not first_of.has(str(t)):
			first_of[str(t)] = fid
	for i in s.get("staff", []).size():
		var spec: Dictionary = s.staff[i]
		var cid := int(st.totals.get("next_staff_id", 1))
		st.totals["next_staff_id"] = cid + 1
		var c := Staff.make_candidate(st.rng, cid)
		c.role = str(spec.get("role", "all_rounder"))
		for sk in c.stats:
			c.stats[sk] = clampi(int(c.stats[sk]), 1, 4)
		for sk in Staff.data().roles.filter(func(r): return r.id == c.role).front().skills:
			c.stats[sk] = int(spec.get("level", 5))
		c.wage = Staff.wage_for(c.stats)
		sim.employ(c)
	for p in s.get("parts", {}):
		st.parts[p] = int(st.parts.get(p, 0)) + int(s.parts[p])
	for k in s.get("consumables", {}):
		st.consumables[k] = int(s.consumables[k])
	for spec: Dictionary in s.get("items", []):
		var slot := str(spec.get("slot", ""))
		for t in first_of:
			slot = slot.replace("{%s}" % t, str(first_of[t]))
		if "{" in slot:
			continue      # that fixture wasn't added
		var it := st.new_item(str(spec.kind))
		st.set_loc(it, {"t": "slot", "slot": slot})
	var lic: Dictionary = st.totals.get("licences", {})
	for l in s.get("licences", []):
		lic[str(l)] = true
	st.totals["licences"] = lic
	var lot_ids: Array = []
	for spec: Dictionary in s.get("stock", []):
		lot_ids.append(_starting_stock(sim, spec))
	st.totals["challenge"] = {
		"id": str(def.get("id", "")), "name": str(def.get("name", "")), "icon": str(def.get("icon", "🏁")),
		"daily": bool(def.get("daily", false)), "date": str(def.get("date", "")), "board": board(def),
		"goal": def.get("goal", {}).duplicate(true), "days": int(def.get("days", 5)), "medals": def.get("medals", {}).duplicate(true),
		"rules": def.get("rules", {}).duplicate(true), "start_cash": st.cash, "start_shipped": st.lifetime("shipped"),
		"lots": lot_ids, "status": "running", "result": {}, "desc": str(def.get("desc", "")),
	}
	st.day_stats = sim._blank_day_stats()
	st.mark("company")
	st.mark("building")
	st.say("%s %s — %s." % [str(def.get("icon", "🏁")), str(def.get("name", "")), goal_text(def)], "info")


static func _starting_stock(sim: Sim, spec: Dictionary) -> int:
	## A lot that's already been paid for and dropped off: pallets in goods-in (the level lays them out).
	var st := sim.state
	var seller: Dictionary = Content.sellers.get(str(spec.get("seller", "")), {})
	var models: Array = spec.get("models", [])
	if models.is_empty():
		models = seller.get("model_pool", []).filter(func(m): return str(Content.model(m).get("type", "laptop")) == "laptop")
	if models.is_empty():
		models = seller.get("model_pool", ["corvex_prospect_5420"])
	var q := float(spec.get("quality", 0.6))
	var count := int(spec.get("count", 10))
	var units: Array = []
	for i in count:
		units.append(sim.lots.roll_unit(str(models[i % models.size()]), q, seller).to_dict())
	var lot := {"id": st.make_id(), "seller_id": str(seller.get("id", "")), "seller_name": str(seller.get("name", "A seller")),
		"sector": str(seller.get("sector", "")), "models": models.duplicate(), "count": count, "unit_price": 0, "total_price": 0,
		"paid": 0, "status": "bought", "units": units, "listed_min": st.minute, "expires_min": st.minute, "note": "Challenge stock"}
	st.lots[lot.id] = lot
	var pallets := sim.deliver_lot(int(lot.id))
	for p: Item in pallets:
		st.set_loc(p, {"t": "loose", "relocate": true, "p": [0, 1, 0], "r": [0, 0, 0, 1]})
	if str(spec.get("state", "untested")) == "graded":
		for it: Item in st.items.values():
			if it.kind != Item.KIND_UNIT or it.unit == null or it.unit.lot_id != int(lot.id):
				continue
			var u := it.unit
			u.tested = true
			u.imaged = true
			for f in u.faults:
				f.fixed = true
			u.grade = Valuation.grade_for(u)
			u.list_price = int(round(Valuation.market_value(u, u.grade)))
			u.listed = true
			u.graded_at = st.minute
			u.listed_at = st.minute
			u.log_event(st.minute, "Graded %s and listed on Flogit (ready stock)" % UnitData.grade_label(u.grade))
			st.mark("item", it.id)
	return int(lot.id)


# ================================================================= starting a run (title screen)

static func begin(id: String, company: String = "") -> bool:
	## Game root: a new company for this challenge (or today's daily run). False if it doesn't exist.
	var def := get_def(id)
	if def.is_empty():
		return false
	Game.new_game(seed_of(def))
	if company != "":
		Game.state.company_name = Sim.clean_company_name(company)
	apply_start(Game.sim, def)
	if bool(def.get("daily", false)):
		mark_daily(str(def.date), {"started": int(Time.get_unix_time_from_system()), "company": Game.state.company_name,
			"run": str(Game.state.totals.get("run_id", ""))})
	return true


# ================================================================= local records (Steam leaderboards later)

static func load_records() -> Dictionary:
	if not FileAccess.file_exists(records_path):
		return {}
	var d = JSON.parse_string(FileAccess.get_file_as_string(records_path))
	return d if d is Dictionary else {}


static func _save_records(recs: Dictionary) -> void:
	var f := FileAccess.open(records_path, FileAccess.WRITE)
	if f:
		f.store_string(JSON.stringify(recs, " "))


static func submit(board_name: String, value: float, lower_is_better: bool, company: String, run_id: String, extra: Dictionary = {}) -> int:
	## Add a result to this PC's board. Returns the place (1 = new record), 0 if outside the top N or already in.
	var recs := load_records()
	var boards: Dictionary = recs.get("boards", {})
	var list: Array = boards.get(board_name, [])
	for e in list:
		if str(e.get("run", "")) == run_id:
			return 0
	var entry := {"value": value, "company": company, "run": run_id, "date": Time.get_date_string_from_system()}
	entry.merge(extra)
	list.append(entry)
	list.sort_custom(func(a, b): return float(a.value) < float(b.value) if lower_is_better else float(a.value) > float(b.value))
	var place := 0
	for i in list.size():
		if str(list[i].run) == run_id:
			place = i + 1
	boards[board_name] = list.slice(0, TOP_N)
	recs["boards"] = boards
	_save_records(recs)
	return place if place <= TOP_N else 0


static func submit_result(ch: Dictionary, company: String, run_id: String) -> int:
	## The host's finished challenge → its board. Failed runs don't make a board; a finished daily is noted.
	var res: Dictionary = ch.get("result", {})
	var place := 0
	if str(ch.get("status", "")) in ["won", "done"]:
		place = submit(str(ch.get("board", "")), float(res.get("value", 0)), not is_score(ch.get("goal", {})), company, run_id,
			{"medal": str(res.get("medal", ""))})
	if bool(ch.get("daily", false)):
		mark_daily(str(ch.get("date", "")), {"status": str(ch.get("status", "")), "result": res.duplicate(true), "place": place})
	var recs := load_records()
	var best: Dictionary = recs.get("best_medal", {})
	var medal := str(res.get("medal", ""))
	var id := str(ch.get("id", ""))
	if medal != "" and not bool(ch.get("daily", false)):
		var cur := str(best.get(id, ""))
		if cur == "" or MEDALS.find(medal) < MEDALS.find(cur):
			best[id] = medal
			recs["best_medal"] = best
			_save_records(recs)
	return place


static func board_entries(board_name: String) -> Array:
	return load_records().get("boards", {}).get(board_name, [])


static func best_medal(id: String) -> String:
	return str(load_records().get("best_medal", {}).get(id, ""))


static func daily_attempt(key: String) -> Dictionary:
	return load_records().get("daily", {}).get(key, {})


static func mark_daily(key: String, info: Dictionary) -> void:
	var recs := load_records()
	var daily: Dictionary = recs.get("daily", {})
	var cur: Dictionary = daily.get(key, {})
	cur.merge(info, true)
	daily[key] = cur
	recs["daily"] = daily
	_save_records(recs)
