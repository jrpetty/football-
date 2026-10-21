class_name Business
extends RefCounted
## Business buyers — sales channel three, unlocked by reputation (data/business.json).
## Companies email in with a bulk order ("20 laptops, grade B or better, by Friday 16:00") or a custom server
## build. One click accepts or declines: the price is the price (no haggling).
##   • Bulk: the player allocates graded stock to it (Business app). Every allocated unit becomes an ordinary order
##     (channel "business", `contract` = this id) that is packed and couriered like a Flogit sale; the buyer pays per
##     unit as it ships, with no marketplace fee.
##   • Build: one server is reserved for it — from stock, or a barebones chassis from PartsHub — and built up at the
##     build bench (ServerBuild). Once it meets the spec, passes its test and is imaged, it gets its order and ships.
## On time = reputation and a happy customer; late = one reputation hit and a late-delivery deduction; very late =
## cancelled. Sim owns every state change; this class is the shared maths the host and the UI both read.
##
## Contract: {id, kind: bulk|build, status: offer|active|complete|complete_late|cancelled|declined|expired,
##   buyer, buyer_id, sector, subject, body, offered, expires, due, hours, accepted,
##   bulk: type, qty, grade, min_ram, min_storage, unit_price, total
##   build: name, spec {chassis[], cpus, cpu_ids[], cpu_label, ram_gb, drives, drive_gb, psus, raid}, price, parts_estimate, unit
##   progress: orders [child order ids], shipped, revenue, late, completed}

const GRADE_RANK := {"A": 4, "B": 3, "C": 2, "D": 1, "S": 0}
const FINISHED := ["complete", "complete_late", "cancelled", "declined", "expired"]
const STORAGE_NAMES := {"sas_caddy": "SAS", "sata_hdd": "SATA HDD", "nvme_m2": "NVMe SSD", "sata_ssd": "SATA SSD"}


static func cfg(key: String, default: Variant = null) -> Variant:
	return Content.business.get(key, default)


static func unlock_rep() -> float:
	return float(cfg("unlock_rep", 20))


static func unlocked(st: GameState) -> bool:
	return st.reputation >= unlock_rep()


static func type_name(t: String, plural: bool = true) -> String:
	var names: Array = cfg("type_names", {}).get(t, [t, t + "s"])
	return str(names[1 if plural else 0])


static func is_open(c: Dictionary) -> bool:
	return str(c.get("status", "")) == "active"


static func is_offer(c: Dictionary) -> bool:
	return str(c.get("status", "")) == "offer"


static func title(c: Dictionary) -> String:
	## Short name for lists and the HUD: "20 laptops (B+)" or "Virtualisation host".
	if c.get("kind", "") == "build":
		return str(c.get("name", "Custom build"))
	return "%d %s (%s+)" % [int(c.get("qty", 0)), type_name(str(c.get("type", "")), int(c.get("qty", 0)) != 1), str(c.get("grade", "C"))]


static func value(c: Dictionary) -> int:
	return int(c.get("price", 0)) if c.get("kind", "") == "build" else int(c.get("unit_price", 0)) * int(c.get("qty", 0))


static func status_text(c: Dictionary) -> String:
	match str(c.get("status", "")):
		"offer": return "Waiting for your answer"
		"active": return "In progress"
		"complete": return "Delivered on time"
		"complete_late": return "Delivered late"
		"cancelled": return "Cancelled by the buyer"
		"declined": return "Declined"
		"expired": return "Offer lapsed"
	return str(c.get("status", ""))


# ================================================================= bulk orders

static func meets_grade(g: String, min_grade: String) -> bool:
	return g != "" and g != "S" and int(GRADE_RANK.get(g, 0)) >= int(GRADE_RANK.get(min_grade, 9))


static func spec_text(c: Dictionary) -> String:
	## The extra spec words in a bulk order: ", with 8GB+ RAM and 256GB+ storage" (or "").
	var bits: PackedStringArray = []
	if int(c.get("min_ram", 0)) > 0:
		bits.append("%dGB+ RAM" % int(c.min_ram))
	if int(c.get("min_storage", 0)) > 0:
		bits.append("%s+ storage" % UnitData.storage_text(int(c.min_storage)))
	return "" if bits.is_empty() else ", with " + " and ".join(bits)


static func unit_fits(c: Dictionary, u: UnitData) -> bool:
	## Does this unit meet the bulk order's spec (type, grade, RAM, storage)? Ignores availability.
	if u == null or u.hw_type() != str(c.get("type", "")):
		return false
	if not meets_grade(u.grade, str(c.get("grade", "C"))):
		return false
	if int(c.get("min_ram", 0)) > 0 and u.ram_gb < int(c.min_ram):
		return false
	if int(c.get("min_storage", 0)) > 0 and u.storage_gb < int(c.min_storage):
		return false
	return true


static func eligible_units(st: GameState, c: Dictionary) -> Array:
	## Graded stock that could go to this bulk order (not sold, not reserved), cheapest guide value first —
	## so the auto-pick spends your least valuable matching kit on the fixed contract price.
	var out: Array = []
	for it: Item in st.units_in_stock():
		var u := it.unit
		if u.order_id != 0 or u.contract != 0 or not it.loc.get("t") in ["slot", "loose", "belt"]:
			continue
		if unit_fits(c, u):
			out.append(it)
	out.sort_custom(func(a: Item, b: Item): return Valuation.market_value(a.unit, a.unit.grade) < Valuation.market_value(b.unit, b.unit.grade))
	return out


static func live_orders(st: GameState, c: Dictionary) -> Array:
	## Child orders still counting towards the contract (not cancelled).
	var out: Array = []
	for oid in c.get("orders", []):
		var o: Dictionary = st.orders.get(int(oid), {})
		if not o.is_empty() and o.get("status", "") != "cancelled":
			out.append(o)
	return out


static func allocated(st: GameState, c: Dictionary) -> int:
	return live_orders(st, c).size()


static func bulk_unit_price(type: String, grade: String, min_ram: int, min_storage: int, pct: float) -> int:
	## A fair per-unit price: the `pct` percentile (cheapest first) of what every model of this type is worth at
	## the minimum grade, in the cheapest configuration that meets the spec. 0 = nothing on the market fits.
	var vals: Array = []
	for m: Dictionary in Content.models.values():
		if m.get("type", "laptop") != type:
			continue
		var u := UnitData.new()
		u.model_id = m.id
		u.cpu_id = str(m.get("ref_cpu", ""))
		u.ram_gb = int(m.get("ref_ram", 0))
		u.storage_gb = int(m.get("ref_storage", 0))
		if min_ram > 0:
			var r := _smallest_at_least(m.get("ram_options", []), min_ram)
			if r < 0:
				continue
			u.ram_gb = r
		if min_storage > 0:
			var s := _smallest_at_least(m.get("storage_options", []), min_storage)
			if s < 0:
				continue
			u.storage_gb = s
		vals.append(Valuation.market_value(u, grade))
	if vals.is_empty():
		return 0
	vals.sort()
	return int(vals[clampi(int(floor(pct * (vals.size() - 1) + 0.5)), 0, vals.size() - 1)])


static func _smallest_at_least(options: Array, at_least: int) -> int:
	var best := -1
	for o in options:
		if int(o) >= at_least and (best < 0 or int(o) < best):
			best = int(o)
	return best


# ================================================================= custom builds

static func chassis_name(spec: Dictionary) -> String:
	var names: PackedStringArray = []
	for mid in spec.get("chassis", []):
		names.append(Content.model_display_name(mid))
	return " or ".join(names)


static func ram_label(spec: Dictionary) -> String:
	var m := Content.model(str(spec.get("chassis", [""])[0]))
	return "%dGB%s" % [int(spec.get("ram_gb", 0)), " ECC" if str(m.get("ram_type", "")) == "ddr4_rdimm" else ""]


static func drive_label(spec: Dictionary) -> String:
	var m := Content.model(str(spec.get("chassis", [""])[0]))
	return "%d× %s %s" % [int(spec.get("drives", 0)), UnitData.storage_text(int(spec.get("drive_gb", 0))),
		STORAGE_NAMES.get(str(m.get("storage_type", "")), "drives")]


static func spec_lines(spec: Dictionary) -> Array:
	## The customer's spec, line by line (email, Business app, build bench screen).
	var out: Array = ["Chassis: " + chassis_name(spec)]
	var n := int(spec.get("cpus", 1))
	out.append(("CPUs: %d× %s" % [n, spec.get("cpu_label", "")]) if n > 1 else "CPU: %s" % spec.get("cpu_label", ""))
	out.append("Memory: " + ram_label(spec))
	out.append("Drives: " + drive_label(spec))
	var p := int(spec.get("psus", 1))
	out.append("Power: %d PSUs (redundant)" % p if p > 1 else "Power: 1 PSU")
	if spec.get("raid", false):
		out.append("RAID controller with cache battery")
	return out


static func chassis_part(model_id: String) -> String:
	for p: Dictionary in Content.parts.values():
		if p.get("category") == "chassis" and str(p.get("model", "")) == model_id:
			return p.id
	return ""


static func cpu_part(cpu_id: String) -> String:
	for p: Dictionary in Content.parts.values():
		if p.get("category") == "cpu" and str(p.get("cpu", "")) == cpu_id:
			return p.id
	return ""


static func build_parts_cost(spec: Dictionary) -> int:
	## The cheapest way to build the spec from new parts at PartsHub prices (barebones chassis included).
	var mid := str(spec.get("chassis", [""])[0])
	var m := Content.model(mid)
	var total := int(Content.part(chassis_part(mid)).get("price", 0))
	var cheapest_cpu := 0
	for cid in spec.get("cpu_ids", []):
		var pr := int(Content.part(cpu_part(cid)).get("price", 0))
		if pr > 0 and (cheapest_cpu == 0 or pr < cheapest_cpu):
			cheapest_cpu = pr
	total += cheapest_cpu * int(spec.get("cpus", 1))
	var slots := maxi(int(m.get("ram_slots", 1)), 1)
	var ram := int(spec.get("ram_gb", 0))
	var best_ram := 0
	for p: Dictionary in Content.parts.values():
		if p.get("category") != "ram" or str(p.get("fits_ram_type", "")) != str(m.get("ram_type", "")):
			continue
		var n := int(ceil(float(ram) / maxf(float(p.get("size_gb", 1)), 1.0)))
		if n <= slots and (best_ram == 0 or n * int(p.price) < best_ram):
			best_ram = n * int(p.price)
	total += best_ram
	var best_drive := 0
	for p: Dictionary in Content.parts.values():
		if p.get("category") == "storage" and str(p.get("fits_storage_type", "")) == str(m.get("storage_type", "")) \
				and int(p.get("size_gb", 0)) >= int(spec.get("drive_gb", 0)) and (best_drive == 0 or int(p.price) < best_drive):
			best_drive = int(p.price)
	total += best_drive * int(spec.get("drives", 0))
	total += int(Content.part(str(m.get("psu_part", ""))).get("price", 40)) * int(spec.get("psus", 1))
	if spec.get("raid", false):
		total += int(Content.part("raid_card_12g").get("price", 45))
	return total


static func build_unit(st: GameState, c: Dictionary) -> UnitData:
	## The server reserved for this build, if there is one.
	var it: Item = st.get_item(int(c.get("unit", 0)))
	if it and it.unit and int(it.unit.contract) == int(c.get("id", -1)) and it.loc.get("t") != "gone":
		return it.unit
	return null


static func shopping_list(st: GameState, c: Dictionary) -> Array:
	## Parts still to get for a build, beyond what the reserved server already has and what's in the bins:
	## [{part, qty}] (cheapest parts that do the job; a barebones chassis if no server is reserved yet).
	var spec: Dictionary = c.get("spec", {})
	if spec.is_empty():
		return []
	var mid := str(spec.chassis[0])
	var m := Content.model(mid)
	var u := build_unit(st, c)
	var l := u.layout() if u else {"cpus": 0, "dimms": [], "drives": [], "psus": 0, "raid": false}
	var need := {}
	if u == null:
		need[chassis_part(mid)] = 1
	else:
		m = u.model()
	var cpu_pid := ""
	var have_cpu := 0
	if u and u.cpu_id in spec.cpu_ids:
		cpu_pid = cpu_part(u.cpu_id)
		have_cpu = int(l.cpus)
	if cpu_pid == "":
		for cid in spec.cpu_ids:
			var pid := cpu_part(cid)
			if pid != "" and (cpu_pid == "" or int(Content.part(pid).price) < int(Content.part(cpu_pid).price)):
				cpu_pid = pid
	need[cpu_pid] = int(need.get(cpu_pid, 0)) + maxi(int(spec.cpus) - have_cpu, 0)
	var short := int(spec.ram_gb) - (u.ram_gb if u else 0)
	if short > 0:
		var free := maxi(int(m.get("ram_slots", 1)) - (l.dimms as Array).size(), 1)
		var best_pid := ""
		var best_cost := 0
		var best_n := 0
		for p: Dictionary in Content.parts.values():
			if p.get("category") != "ram" or str(p.get("fits_ram_type", "")) != str(m.get("ram_type", "")):
				continue
			var n := int(ceil(float(short) / maxf(float(p.get("size_gb", 1)), 1.0)))
			if n <= free and (best_pid == "" or n * int(p.price) < best_cost):
				best_pid = p.id
				best_cost = n * int(p.price)
				best_n = n
		if best_pid != "":
			need[best_pid] = int(need.get(best_pid, 0)) + best_n
	var dgb := int(spec.drive_gb)
	var good := (l.drives as Array).filter(func(d): return int(d) >= dgb).size()
	var drive_pid := ""
	for p: Dictionary in Content.parts.values():
		if p.get("category") == "storage" and str(p.get("fits_storage_type", "")) == str(m.get("storage_type", "")) and int(p.get("size_gb", 0)) >= dgb \
				and (drive_pid == "" or int(p.price) < int(Content.part(drive_pid).price)):
			drive_pid = p.id
	if drive_pid != "":
		need[drive_pid] = int(need.get(drive_pid, 0)) + maxi(int(spec.drives) - good, 0)
	if spec.get("raid", false) and not l.raid:
		need["raid_card_12g"] = 1
	var psu := str(m.get("psu_part", ""))
	need[psu] = int(need.get(psu, 0)) + maxi(int(spec.psus) - int(l.psus), 0)
	var out: Array = []
	for pid in need:
		var q := int(need[pid]) - int(st.parts.get(pid, 0))
		if pid != "" and q > 0:
			out.append({"part": pid, "qty": q})
	return out


# ================================================================= offers

static func add_working_minutes(st: GameState, from: float, mins: float) -> float:
	## Deadlines count working hours only (09:00–17:00, Mon–Fri). Same rule as Flogit dispatch deadlines.
	var t := from
	var left := mins
	var guard := 0
	while left > 0.0 and guard < 200:
		guard += 1
		var day := int(floor(t / GameState.MIN_PER_DAY))
		if day % 7 >= 5:
			t = st.day_start_minute(day + (7 - day % 7))
			continue
		var start := st.day_start_minute(day)
		var end := st.day_end_minute(day)
		if t < start:
			t = start
		if t >= end:
			t = st.day_start_minute(day + 1)
			continue
		var step := minf(left, end - t)
		t += step
		left -= step
	return t


static func due_text(m: float) -> String:
	## "Friday 16:00" — how a customer writes a deadline.
	var d := int(floor(m / GameState.MIN_PER_DAY))
	var mod := int(fmod(m, GameState.MIN_PER_DAY))
	return "%s %02d:%02d" % [GameState.WEEKDAYS[d % 7], mod / 60, mod % 60]


static func available_types(st: GameState) -> Dictionary:
	## Kinds of kit the player can actually get hold of: what sellers who'll deal with them sell, plus stock.
	var out := {}
	for s: Dictionary in Content.sellers.values():
		if st.reputation >= float(s.get("min_rep", 0)):
			for mid in s.get("model_pool", []):
				out[str(Content.model(mid).get("type", "laptop"))] = true
	for it: Item in st.units_in_stock():
		out[it.unit.hw_type()] = true
	return out


static func make_offer(st: GameState, rng: RandomNumberGenerator) -> Dictionary:
	## A new enquiry from a buyer who isn't already waiting on us, for kit we can get. {} = nobody this time.
	var rep := st.reputation
	var busy := {}
	for c: Dictionary in st.contracts.values():
		if c.get("status", "") in ["offer", "active"]:
			busy[str(c.get("buyer_id", ""))] = true
	var types := available_types(st)
	var choices: Array = []
	var weights: Array = []
	for b: Dictionary in cfg("buyers", []):
		if busy.has(str(b.id)):
			continue
		for rid in b.get("requests", []):
			var r: Dictionary = Content.biz_requests.get(rid, {})
			if r.is_empty() or rep < float(r.get("min_rep", unlock_rep())):
				continue
			if r.kind == "bulk" and not types.has(str(r.type)):
				continue
			choices.append([b, r])
			weights.append(0.6 if r.kind == "build" else 1.0)
	if choices.is_empty():
		return {}
	var total := 0.0
	for w in weights:
		total += float(w)
	var roll := rng.randf() * total
	var pick: Array = choices[0]
	for i in choices.size():
		roll -= float(weights[i])
		if roll <= 0.0:
			pick = choices[i]
			break
	var b: Dictionary = pick[0]
	var r: Dictionary = pick[1]
	var c := {"kind": r.kind, "request": r.id, "buyer": b.name, "buyer_id": b.id, "sector": b.get("sector", ""),
		"status": "offer", "orders": [], "shipped": 0, "revenue": 0, "late": false}
	var hours: Array = r.get("hours", [16, 32])
	var h := float(rng.randi_range(int(hours[0]), int(hours[1])))
	var mult: Array = r.get("price_mult", [1.0, 1.1])
	if r.kind == "bulk":
		var t := clampf((rep - unlock_rep()) / maxf(100.0 - unlock_rep(), 1.0), 0.0, 1.0)
		var q: Array = r.get("qty", [4, 20])
		var hi := int(round(lerpf(float(q[0]) + (float(q[1]) - float(q[0])) * 0.3, float(q[1]), t)))
		c.type = str(r.type)
		c.qty = rng.randi_range(int(q[0]), maxi(int(q[0]), hi))
		c.grade = str(_pick(rng, r.get("grades", ["B"])))
		c.min_ram = int(_pick(rng, r.get("min_ram", [0])))
		c.min_storage = int(_pick(rng, r.get("min_storage", [0])))
		var base := bulk_unit_price(c.type, c.grade, c.min_ram, c.min_storage, float(cfg("price_percentile", 0.4)))
		if base <= 0:
			return {}
		c.unit_price = maxi(5, int(round(base * rng.randf_range(float(mult[0]), float(mult[1])) / 5.0)) * 5)
		c.total = int(c.unit_price) * int(c.qty)
		h += float(c.qty) * float(r.get("hours_per_unit", 0.8))
	else:
		var spec := {"chassis": (r.get("chassis", []) as Array).duplicate(), "cpus": int(r.get("cpus", 1)),
			"cpu_ids": (r.get("cpu_ids", []) as Array).duplicate(), "cpu_label": str(r.get("cpu_label", "")),
			"ram_gb": int(_pick(rng, r.get("ram_gb", [64]))), "drives": int(_pick(rng, r.get("drives", [4]))),
			"drive_gb": int(_pick(rng, r.get("drive_gb", [1200]))), "psus": int(r.get("psus", 1)), "raid": bool(r.get("raid", false))}
		c.name = str(r.get("name", "Custom server"))
		c.spec = spec
		c.parts_estimate = build_parts_cost(spec)
		c.price = int(round(float(c.parts_estimate) * rng.randf_range(float(mult[0]), float(mult[1])) / 10.0)) * 10
		c.unit = 0
	c.hours = int(round(h))
	c.offered = st.minute
	c.expires = add_working_minutes(st, st.minute, float(cfg("offer_expiry_hours", 6)) * 60.0)
	# Deadlines land on the hour, the way people write them.
	c.due = ceilf(add_working_minutes(st, st.minute, h * 60.0) / 60.0) * 60.0
	c.subject = fill(str(r.get("subject", "")), c, st.company_name, b)
	c.body = fill(str(r.get("body", "")), c, st.company_name, b)
	return c


static func fill(text: String, c: Dictionary, company: String, buyer: Dictionary = {}) -> String:
	var lines: PackedStringArray = []
	if c.has("spec"):
		for s in spec_lines(c.spec):
			lines.append("• " + str(s))
	var subs := {
		"{company}": company, "{buyer}": str(c.get("buyer", "")), "{sign}": str(buyer.get("sign", c.get("buyer", ""))),
		"{qty}": str(int(c.get("qty", 0))), "{things}": type_name(str(c.get("type", "")), true), "{grade}": str(c.get("grade", "")),
		"{spec}": spec_text(c), "{due}": due_text(float(c.get("due", 0.0))),
		"{unit_price}": GameState.format_money(int(c.get("unit_price", 0))), "{total}": GameState.format_money(int(c.get("total", 0))),
		"{name}": str(c.get("name", "")), "{spec_list}": "\n".join(lines), "{price}": GameState.format_money(int(c.get("price", 0))),
	}
	for k in subs:
		text = text.replace(k, subs[k])
	return text


static func _pick(rng: RandomNumberGenerator, arr: Array) -> Variant:
	return arr[rng.randi() % arr.size()] if not arr.is_empty() else 0
