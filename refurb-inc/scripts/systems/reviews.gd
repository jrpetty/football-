class_name Reviews
extends RefCounted
## Flogit reviews (data/reviews.json). When the courier takes a Flogit parcel, Sim notes the facts the buyer will
## judge (`facts`); a few working hours later the buyer leaves 1–5 stars and a comment (`make`). What counts:
##   • grade accuracy — a flaw the grader missed means the listing promised a better grade than the unit (1★ territory)
##   • dispatch — next to no wait earns praise, late and very late parcels don't
##   • price fairness against the guide for the unit's true grade, and a tired battery on mobile kit
## Stars nudge reputation. Reviews ride on their order (order.review), so they save and replicate with it.
## Randomness comes from the order's own seed, never the shared game RNG.


static func cfg(key: String, default: Variant = null) -> Variant:
	return Content.reviews.get(key, default)


static func stars_text(n: int) -> String:
	n = clampi(n, 0, 5)
	return "★".repeat(n) + "☆".repeat(5 - n)


static func true_flaw(u: UnitData) -> String:
	## The flaw a buyer would complain about: the one the grader missed, else the worst visible one.
	if u.missed_flaw != "" and u.missed_flaw in u.cosmetic:
		return u.missed_flaw
	var worst := ""
	var worst_pen := -1
	for c in u.cosmetic:
		var pen := int(Content.condition(c).get("penalty", 0))
		if pen > worst_pen:
			worst_pen = pen
			worst = c
	return worst


static func facts(st: GameState, order: Dictionary, u: UnitData, on_time: bool, shipped_at: float) -> Dictionary:
	## Captured as the parcel leaves (the unit itself is gone by the time the buyer writes).
	var f := {"price": int(order.get("price", 0)), "grade": str(order.get("grade", "")), "model": str(order.get("model", "")),
		"type": "laptop", "true_grade": str(order.get("grade", "")), "flaw": "", "battery": -1, "ratio": 1.0,
		"dispatch_h": BusinessStats.working_minutes(float(order.get("placed", shipped_at)), shipped_at) / 60.0,
		"late_h": 0.0 if on_time else BusinessStats.working_minutes(float(order.get("due", shipped_at)), shipped_at) / 60.0}
	if u:
		f.type = u.hw_type()
		f.true_grade = Valuation.grade_for(u) if u.grade != "S" else "S"
		var fl := true_flaw(u)
		f.flaw = Content.condition_text(fl) if fl != "" else ""
		f.battery = u.battery_pct if u.has_battery() else -1
		f.ratio = float(f.price) / maxf(float(Valuation.market_value(u, f.true_grade)), 1.0)
	return f


static func overgraded(f: Dictionary) -> bool:
	var g := str(f.get("grade", ""))
	var t := str(f.get("true_grade", g))
	return g != "S" and int(Business.GRADE_RANK.get(g, 0)) > int(Business.GRADE_RANK.get(t, 0))


static func make(st: GameState, order: Dictionary) -> Dictionary:
	## The buyer's verdict: {stars, text, reason, buyer, model, grade, at}.
	var f: Dictionary = order.get("rv", {})
	var rng := RandomNumberGenerator.new()
	rng.seed = hash([st.seed_value, int(order.get("id", 0)), "review"])
	var score := float(cfg("base", 4.4)) + rng.randf_range(-1.0, 1.0) * float(cfg("noise", 0.45))
	var neg: Array = []        # reasons, worst first
	var pos: Array = []
	if overgraded(f):
		score += float(cfg("overgraded", -3.4))
		neg.append("overgraded")
	var late_h := float(f.get("late_h", 0.0))
	if late_h >= float(cfg("very_late_hours", 8)):
		score += float(cfg("very_late", -2.8))
		neg.append("very_late")
	elif late_h > 0.0:
		score += float(cfg("late", -1.6))
		neg.append("late")
	elif float(f.get("dispatch_h", 99.0)) <= float(cfg("fast_hours", 3)):
		score += float(cfg("fast", 0.7))
		pos.append("fast")
	var bat := int(f.get("battery", -1))
	if bat >= 0 and bat < int(cfg("battery_below", 65)):
		score += float(cfg("battery", -0.9))
		neg.append("battery")
	var ratio := float(f.get("ratio", 1.0))
	if ratio >= float(cfg("pricey_ratio", 1.18)):
		score += float(cfg("pricey", -0.9))
		neg.append("pricey")
	elif ratio <= float(cfg("bargain_ratio", 0.88)):
		score += float(cfg("bargain", 0.5))
		pos.append("bargain")
	score += float(cfg("worn_grades", {}).get(str(f.get("grade", "")), 0.0))
	var stars := clampi(int(round(score)), 1, 5)
	if "overgraded" in neg:
		stars = mini(stars, 2)
	var reason := ""
	if stars <= 3:
		reason = neg[0] if not neg.is_empty() else ("ok" if stars == 3 else "poor")
	else:
		reason = pos[0] if not pos.is_empty() else ("great" if stars == 5 else "good")
	var cat := reason
	if str(f.get("grade", "")) == "S" and stars >= 3:
		cat = "spares"
	var pool: Array = cfg("comments", {}).get(cat, cfg("comments", {}).get("ok", ["Fine."]))
	var text := str(pool[rng.randi() % pool.size()]) if not pool.is_empty() else ""
	var flaw := str(f.get("flaw", ""))
	var subs := {"{thing}": str(cfg("things", {}).get(str(f.get("type", "")), "kit")), "{grade}": UnitData.grade_label(str(f.get("grade", ""))),
		"{flaw}": flaw if flaw != "" else "A mark nobody mentioned", "{flaw_lc}": flaw.to_lower() if flaw != "" else "a mark nobody mentioned",
		"{model}": str(f.get("model", "")), "{price}": GameState.format_money(int(f.get("price", 0)))}
	for k in subs:
		text = text.replace(k, subs[k])
	return {"stars": stars, "text": text, "reason": reason, "buyer": str(order.get("buyer", "")), "model": str(order.get("model", "")),
		"grade": str(f.get("grade", "")), "at": st.minute}


static func reason_label(reason: String) -> String:
	return str(cfg("reasons", {}).get(reason, reason.capitalize()))


static func rep_for(stars: int) -> float:
	return float(cfg("rep", {}).get(str(clampi(stars, 1, 5)), 0.0))


static func summary(st: GameState) -> Dictionary:
	## Average stars + count over every reviewed Flogit order (replicated, so guests see the same badge).
	var n := 0
	var total := 0
	var dist := [0, 0, 0, 0, 0]
	for o: Dictionary in st.orders.values():
		var r: Dictionary = o.get("review", {})
		if r.is_empty():
			continue
		n += 1
		total += int(r.stars)
		dist[clampi(int(r.stars), 1, 5) - 1] += 1
	return {"count": n, "avg": float(total) / n if n > 0 else 0.0, "dist": dist}
