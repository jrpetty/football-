class_name LotGenerator
extends RefCounted
## Creates bulk lots from sellers, rolling every unit's hidden truth up-front.
## Price swings 60–130% around the nominal trade value; hidden quality correlates with the swing (with noise),
## so bargains are usually cheaper for a reason — but not always.

const GAME_START_YEAR := 2025
const DAYS_PER_YEAR := 120          # in-game calendar compression for refresh cycles / ageing
const KEYS := ["Q", "W", "E", "R", "T", "A", "S", "D", "F", "Z", "X", "C", "N", "M", "Enter", "Space", "Shift", "Backspace", "@", "Tab"]
const SERIAL_PREFIX := {"corvex": "CVX", "halden": "HLD", "northstar": "NST", "brightline": "BRL", "kestrel": "KST"}

var state: GameState
var rng: RandomNumberGenerator


func _init(s: GameState) -> void:
	state = s
	rng = s.rng


static func game_year(day: int) -> int:
	return GAME_START_YEAR + int(day / DAYS_PER_YEAR)


func eligible_sellers() -> Array:
	return Content.sellers.values().filter(func(s): return state.reputation >= float(s.get("min_rep", 0)))


func create_lot(seller: Dictionary, refresh: bool = false) -> Dictionary:
	var day := state.day_index()
	var pool: Array = seller.get("model_pool", [])
	var shuffled := pool.duplicate()
	_shuffle(shuffled)
	# A lot is one form (all laptops, or all towers…) so it packs onto pallets cleanly.
	var chosen: Array = [shuffled[0]]
	var same_form := shuffled.filter(func(m): return m != shuffled[0] and Content.model_form(m) == Content.model_form(shuffled[0]))
	if not same_form.is_empty() and rng.randf() >= 0.55:
		chosen.append(same_form[0])
	var size_range: Array = seller.get("lot_size", [4, 10])
	var count := rng.randi_range(int(size_range[0]), int(size_range[1]))
	if refresh:
		count = mini(int(round(count * 1.5)) + 4, 30)

	var swing_range: Array = Content.econ("lot_price_swing", [0.6, 1.3])
	var swing := _triangular(float(swing_range[0]), float(swing_range[1]), 0.95)
	var q := 0.5 + (swing - 0.95) / 0.7 + rng.randfn(0.0, 0.18) + float(seller.get("quality_bias", 0.0))
	q = clampf(q, 0.0, 1.0)

	var units: Array = []
	var nominal := 0.0
	for i in count:
		var u := roll_unit(chosen[rng.randi() % chosen.size()], q, seller)
		units.append(u.to_dict())
		nominal += Valuation.a_grade_value(u) * float(Content.econ("lot_trade_ratio", 0.4)) * 0.95
	nominal /= maxf(count, 1)
	var unit_price := maxi(int(round(nominal * swing)), 5)

	var model_counts := {}
	var oldest := 9999
	for ud in units:
		model_counts[ud.model_id] = int(model_counts.get(ud.model_id, 0)) + 1
		oldest = mini(oldest, int(Content.model(ud.model_id).get("release_year", GAME_START_YEAR)))
	var summary: PackedStringArray = []
	for mid in model_counts:
		summary.append("%d× %s" % [model_counts[mid], Content.model_display_name(mid)])

	var listing_days: Array = Content.econ("lot_listing_days", [1, 3])
	var expires_day := day + rng.randi_range(int(listing_days[0]), int(listing_days[1])) - 1
	var lot := {
		"id": state.make_id(),
		"seller_id": seller.id, "seller_name": seller.name, "sector": seller.get("sector", ""),
		"models": model_counts.keys(), "summary": ", ".join(summary),
		"count": count, "age_years": maxi(game_year(day) - oldest, 1),
		"unit_price": unit_price, "total_price": unit_price * count,
		"note": _note_for(q), "refresh": refresh,
		"listed_min": state.minute, "expires_min": state.day_end_minute(expires_day) - 120.0,
		"status": "open",          # open | bought | expired
		"quality": q, "units": units,   # HIDDEN — never replicated to clients
	}
	state.lots[lot.id] = lot
	state.mark("lot", lot.id)
	return lot


func roll_unit(model_id: String, q: float, seller: Dictionary = {}) -> UnitData:
	var m := Content.model(model_id)
	var u := UnitData.new()
	u.model_id = model_id
	u.serial = _serial(m.get("maker", ""))
	u.cpu_id = _pick(m.get("cpu_options", [m.get("ref_cpu", "")]))
	u.ram_gb = int(_pick(m.get("ram_options", [8])))
	u.storage_gb = int(_pick(m.get("storage_options", [256])))
	u.seller_name = seller.get("name", "")

	# --- external condition
	var lam := lerpf(float(Content.econ("cosmetic_issues_bad", 3.0)), float(Content.econ("cosmetic_issues_good", 0.6)), q)
	var n_cos := _poisson(lam)
	var cond_pool := Content.conditions.values().filter(func(c): return Content.allowed_for(c, model_id))
	for i in n_cos:
		var c: Dictionary = _weighted(cond_pool, "weight")
		if c.is_empty() or c.id in u.cosmetic:
			continue
		u.cosmetic.append(c.id)
		var linked: String = c.get("fixable_by_fault", "")
		if linked != "":
			u.faults.append(_make_fault(linked, u))

	# --- internal faults
	var p_fault := lerpf(float(Content.econ("fault_rate_bad", 0.45)), float(Content.econ("fault_rate_good", 0.1)), q)
	if rng.randf() < p_fault:
		_add_internal_fault(u)
		if rng.randf() < float(Content.econ("multi_fault_chance", 0.25)) * (1.0 - q):
			_add_internal_fault(u)

	var worn := u.faults.any(func(f): return f.id == "battery_worn")
	if not u.is_laptop():
		u.battery_pct = 100        # no battery to wear out (shown as n/a)
	elif worn:
		u.battery_pct = rng.randi_range(31, 64)
		for f in u.faults:
			if f.id == "battery_worn":
				f.detail["pct"] = u.battery_pct
	else:
		u.battery_pct = rng.randi_range(int(lerpf(70, 84, q)), 97)
	return u


func _add_internal_fault(u: UnitData) -> void:
	var pool := Content.faults.values().filter(func(f):
		if int(f.get("weight", 0)) <= 0 or not Content.allowed_for(f, u.model_id):
			return false
		if f.get("requires_ram_slots", false) and int(u.model().get("ram_slots", 0)) <= 0:
			return false
		return not u.faults.any(func(x): return x.id == f.id))
	if pool.is_empty():
		return
	var f: Dictionary = _weighted(pool, "weight")
	u.faults.append(_make_fault(f.id, u))


func _make_fault(fault_id: String, u: UnitData) -> Dictionary:
	var detail := {}
	match fault_id:
		"ram_faulty":
			var slots := maxi(int(u.model().get("ram_slots", 1)), 1)
			detail["slot"] = ["DIMM A", "DIMM B", "DIMM C", "DIMM D"][rng.randi() % mini(slots, 4)]
		"keycap_missing":
			detail["key"] = KEYS[rng.randi() % KEYS.size()]
		"keyboard_faulty":
			detail["n"] = rng.randi_range(2, 7)
		"battery_worn":
			detail["pct"] = 50
	return {"id": fault_id, "detail": detail, "fixed": false}


static func fault_text(f: Dictionary) -> String:
	var text: String = Content.fault(f.get("id", "")).get("report", f.get("id", ""))
	for k in f.get("detail", {}):
		text = text.replace("{%s}" % k, str(f.detail[k]))
	return text


func _note_for(q: float) -> String:
	var candidates: Array = []
	for band in Content.seller_notes:
		if q >= float(band.min_q) and q < float(band.max_q):
			candidates.append_array(band.texts)
	return _pick(candidates) if not candidates.is_empty() else "Bulk lot."


func _serial(maker: String) -> String:
	const CH := "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789"
	var s: String = SERIAL_PREFIX.get(maker, "UNK") + "-"
	for i in 7:
		s += CH[rng.randi() % CH.length()]
	return s


# ---------------------------------------------------------------- rng helpers

func _pick(arr: Array) -> Variant:
	return arr[rng.randi() % arr.size()] if not arr.is_empty() else null


func _shuffle(arr: Array) -> void:
	for i in range(arr.size() - 1, 0, -1):
		var j := rng.randi() % (i + 1)
		var t: Variant = arr[i]
		arr[i] = arr[j]
		arr[j] = t


func _weighted(pool: Array, key: String) -> Dictionary:
	var total := 0.0
	for e in pool:
		total += float(e.get(key, 0))
	if total <= 0.0:
		return {}
	var r := rng.randf() * total
	for e in pool:
		r -= float(e.get(key, 0))
		if r <= 0.0:
			return e
	return pool.back()


func _poisson(lam: float) -> int:
	var l := exp(-lam)
	var k := 0
	var p := 1.0
	while true:
		p *= rng.randf()
		if p <= l:
			break
		k += 1
	return k


func _triangular(lo: float, hi: float, mode: float) -> float:
	var u := rng.randf()
	var c := (mode - lo) / (hi - lo)
	if u < c:
		return lo + sqrt(u * (hi - lo) * (mode - lo))
	return hi - sqrt((1.0 - u) * (hi - lo) * (hi - mode))
