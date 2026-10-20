class_name GameState
extends RefCounted
## The whole company + world as plain data. The host owns the authoritative copy and mutates it through Sim;
## clients hold a replica that is overwritten by deltas from the host. Views listen to `changed`.

signal changed(kind: String, id: Variant)
signal notify(text: String, level: String, peer: int)   # level: info|good|warn|bad ; peer 0 = everyone
signal day_ended(summary: Dictionary)
signal vehicle(kind: String, payload: Dictionary)

const MIN_PER_DAY := 1440.0
const WEEKDAYS := ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

var rng := RandomNumberGenerator.new()
var seed_value: int = 0
var next_id: int = 1

# --- time
var minute: float = 0.0              # absolute game minutes since day 0 (a Monday) 00:00
var day_running: bool = false        # false between "day over" and "start next day"
var last_flogit_hour: int = -1

# --- company
var company_name: String = "Refurb Inc."
var cash: int = 0
var reputation: float = 0.0
var ledger: Array = []               # [{ "m": minute, "amt": int, "cat": String, "desc": String }]
var parts: Dictionary = {}           # part_id -> count (in the parts bins)
var consumables: Dictionary = {}     # boxes/labels/tape -> count
var players: Dictionary = {}         # player key -> { "name": String, "xp": {skill: int} }

# --- premises & equipment
var building_id: String = "garage"
var fixtures: Dictionary = {}        # fixture/station id -> { "type": String, "label": String, "placed": bool, "p": [x,y,z], "r": yaw_deg }

# --- world
var items: Dictionary = {}           # id -> Item
var slots: Dictionary = {}           # slot_id -> item id (occupied slots only)
var slot_defs: Dictionary = {}       # slot_id -> { "accepts": Array[String], "station": String } (from scenes; not saved)
var stations: Dictionary = {}        # station_id -> { "type": String, "job": Dictionary, "msg": String }
var lots: Dictionary = {}            # id -> lot Dictionary (see LotGenerator)
var orders: Dictionary = {}          # id -> order Dictionary
var schedule: Array = []             # [{ "at": minute, "type": String, "data": Dictionary }]
var seller_state: Dictionary = {}    # seller_id -> { "next_refresh_day": int }

# --- stats
var day_stats: Dictionary = {}
var totals: Dictionary = {"units_bought": 0, "units_sold": 0, "revenue": 0, "spent": 0}
var stats: Dictionary = BusinessStats.blank()   # long-term business history for the Insights app (host; saved, not replicated)

# --- replication bookkeeping (host)
var dirty: Dictionary = {}           # "kind|id" -> [kind, id]
var suppress_signals := false


# ================================================================= ids / dirty

func make_id() -> int:
	next_id += 1
	return next_id - 1


func mark(kind: String, id: Variant = 0) -> void:
	dirty["%s|%s" % [kind, str(id)]] = [kind, id]
	if not suppress_signals:
		changed.emit(kind, id)


func say(text: String, level: String = "info", peer: int = 0) -> void:
	notify.emit(text, level, peer)


# ================================================================= time helpers

func day_index() -> int:
	return int(floor(minute / MIN_PER_DAY))


func minute_of_day() -> float:
	return fmod(minute, MIN_PER_DAY)


func weekday() -> int:
	return day_index() % 7


func weekday_name() -> String:
	return WEEKDAYS[weekday()]


func clock_text() -> String:
	var m := int(minute_of_day())
	return "%02d:%02d" % [m / 60, m % 60]


func day_start_minute(day: int) -> float:
	return day * MIN_PER_DAY + float(Content.econ("day_start_hour", 9)) * 60.0


func day_end_minute(day: int) -> float:
	return day * MIN_PER_DAY + float(Content.econ("day_end_hour", 17)) * 60.0


static func format_money(amount: int) -> String:
	var neg := amount < 0
	var s := str(absi(amount))
	var out := ""
	while s.length() > 3:
		out = "," + s.substr(s.length() - 3) + out
		s = s.substr(0, s.length() - 3)
	return ("-£" if neg else "£") + s + out


static func format_minute(abs_minute: float) -> String:
	var d := int(floor(abs_minute / MIN_PER_DAY))
	var m := int(fmod(abs_minute, MIN_PER_DAY))
	return "%s %02d:%02d" % [WEEKDAYS[d % 7].substr(0, 3), m / 60, m % 60]


# ================================================================= items & slots

func new_item(kind: String, loc: Dictionary = {"t": "gone"}) -> Item:
	var it := Item.new()
	it.id = make_id()
	it.kind = kind
	items[it.id] = it
	set_loc(it, loc)
	return it


func get_item(id: int) -> Item:
	return items.get(id)


func set_loc(it: Item, loc: Dictionary) -> void:
	var old: Dictionary = it.loc
	if old.get("t") == "slot":
		var sid: String = old.get("slot", "")
		if slots.get(sid) == it.id:
			slots.erase(sid)
			mark("slot", sid)
	it.loc = loc.duplicate(true)
	if loc.get("t") == "slot":
		slots[loc["slot"]] = it.id
		mark("slot", loc["slot"])
	mark("item", it.id)


func remove_item(id: int) -> void:
	var it: Item = items.get(id)
	if it == null:
		return
	set_loc(it, {"t": "gone"})
	items.erase(id)
	mark("item", id)


func item_in_slot(slot_id: String) -> Item:
	var id: int = slots.get(slot_id, 0)
	return items.get(id) if id != 0 else null


func held_by(peer: int) -> Array:
	## Items carried by `peer`, bottom of the stack first.
	var out: Array = []
	for it: Item in items.values():
		if it.loc.get("t") == "held" and int(it.loc.get("peer", 0)) == peer:
			out.append(it)
	out.sort_custom(func(a: Item, b: Item): return int(a.loc.get("i", 0)) < int(b.loc.get("i", 0)))
	return out


func slot_accepts(slot_id: String, kind: String) -> bool:
	if slot_id.begins_with("i"):      # dynamic slot on an item (e.g. pallet "i12:3")
		var host_id := int(slot_id.substr(1).get_slice(":", 0))
		var host: Item = items.get(host_id)
		return host != null and host.kind == Item.KIND_PALLET and kind in [Item.KIND_UNIT, "unit_large"]
	var def: Dictionary = slot_defs.get(slot_id, {})
	if def.is_empty():
		return false
	var accepts: Array = def.get("accepts", [])
	return accepts.is_empty() or kind in accepts


func slot_station(slot_id: String) -> String:
	return slot_defs.get(slot_id, {}).get("station", "")


func register_slot(slot_id: String, accepts: Array, station_id: String = "") -> void:
	slot_defs[slot_id] = {"accepts": accepts, "station": station_id}


func register_station(station_id: String, type: String) -> void:
	if not stations.has(station_id):
		stations[station_id] = {"type": type, "job": {}, "msg": ""}
		mark("station", station_id)
	else:
		stations[station_id]["type"] = type


func station_slot_item(station_id: String, slot_name: String) -> Item:
	return item_in_slot("%s:%s" % [station_id, slot_name])


func units_in_stock() -> Array:
	var out: Array = []
	for it: Item in items.values():
		if it.kind == Item.KIND_UNIT and not it.unit.shipped and it.loc.get("t") != "gone":
			out.append(it)
	return out


# ================================================================= money

func add_money(amount: int, category: String, desc: String) -> void:
	cash += amount
	ledger.append({"m": int(minute), "amt": amount, "cat": category, "desc": desc})
	if ledger.size() > 600:
		ledger = ledger.slice(ledger.size() - 600)
	if amount > 0:
		day_stats["income"] = int(day_stats.get("income", 0)) + amount
	else:
		day_stats["spend"] = int(day_stats.get("spend", 0)) - amount
	BusinessStats.on_money(self, amount, category)
	mark("company")


func add_reputation(delta: float) -> void:
	reputation = clampf(reputation + delta, 0.0, float(Content.econ("rep_max", 100)))
	mark("company")


func bump_stat(key: String, amount: int = 1) -> void:
	day_stats[key] = int(day_stats.get(key, 0)) + amount


# ================================================================= players / xp

func player(key: String) -> Dictionary:
	if not players.has(key):
		players[key] = {"name": key, "xp": {}}
		mark("player", key)
	return players[key]


func add_xp(key: String, skill: String, amount: int) -> void:
	var p := player(key)
	var before := skill_level(key, skill)
	p.xp[skill] = int(p.xp.get(skill, 0)) + amount
	mark("player", key)
	var after := skill_level(key, skill)
	if after > before:
		BusinessStats.on_level_up(self, key, skill, after)
		say("%s reached %s level %d!" % [p.name, skill.capitalize(), after], "good")


func skill_level(key: String, skill: String) -> int:
	var xp := int(players.get(key, {}).get("xp", {}).get(skill, 0))
	var table: Array = Content.econ("xp_per_level", [0, 200, 650, 1500])
	var lvl := 0
	for i in table.size():
		if xp >= int(table[i]):
			lvl = i + 1
	# Everyone can do Level 1 repairs from the start.
	return maxi(lvl, 1) if skill == "repair" else lvl


# ================================================================= serialisation

func to_dict() -> Dictionary:
	var it_out := {}
	for id in items:
		it_out[str(id)] = items[id].to_dict()
	return {
		"version": 1, "seed": seed_value, "rng_state": rng.state, "next_id": next_id,
		"minute": minute, "day_running": day_running, "last_flogit_hour": last_flogit_hour,
		"company_name": company_name, "cash": cash, "reputation": reputation, "ledger": ledger.duplicate(true),
		"parts": parts.duplicate(), "consumables": consumables.duplicate(), "players": players.duplicate(true),
		"items": it_out, "stations": stations.duplicate(true), "building_id": building_id, "fixtures": fixtures.duplicate(true), "lots": _int_keyed_out(lots),
		"orders": _int_keyed_out(orders), "schedule": schedule.duplicate(true),
		"seller_state": seller_state.duplicate(true), "day_stats": day_stats.duplicate(true), "totals": totals.duplicate(true),
		"stats": stats.duplicate(true),
	}


func load_dict(d: Dictionary) -> void:
	seed_value = int(d.get("seed", 0))
	rng.seed = seed_value
	if d.has("rng_state"):
		rng.state = int(d["rng_state"])
	next_id = int(d.get("next_id", 1))
	minute = float(d.get("minute", 0.0))
	day_running = bool(d.get("day_running", false))
	last_flogit_hour = int(d.get("last_flogit_hour", -1))
	company_name = d.get("company_name", "Refurb Inc.")
	cash = int(d.get("cash", 0))
	reputation = float(d.get("reputation", 0.0))
	ledger = d.get("ledger", []).duplicate(true)
	parts = _int_values(d.get("parts", {}))
	consumables = _int_values(d.get("consumables", {}))
	players = d.get("players", {}).duplicate(true)
	items.clear()
	slots.clear()
	for k in d.get("items", {}):
		var it := Item.from_dict(d["items"][k])
		items[it.id] = it
		if it.loc.get("t") == "slot":
			slots[it.loc["slot"]] = it.id
	stations = d.get("stations", {}).duplicate(true)
	building_id = d.get("building_id", "garage")
	fixtures = d.get("fixtures", {}).duplicate(true)
	lots = _int_keyed_in(d.get("lots", {}))
	orders = _int_keyed_in(d.get("orders", {}))
	schedule = d.get("schedule", []).duplicate(true)
	seller_state = d.get("seller_state", {}).duplicate(true)
	day_stats = d.get("day_stats", {}).duplicate(true)
	totals = d.get("totals", {}).duplicate(true)
	stats = BusinessStats.migrate(d.get("stats", {}))
	dirty.clear()


static func _int_keyed_out(src: Dictionary) -> Dictionary:
	var out := {}
	for k in src:
		out[str(k)] = src[k].duplicate(true)
	return out


static func _int_keyed_in(src: Dictionary) -> Dictionary:
	var out := {}
	for k in src:
		out[int(k)] = src[k].duplicate(true)
	return out


static func _int_values(src: Dictionary) -> Dictionary:
	var out := {}
	for k in src:
		out[k] = int(src[k])
	return out


# ================================================================= replication (entity granularity)

func export_entity(kind: String, id: Variant) -> Variant:
	## Serialised form of one entity for network deltas. null = deleted.
	match kind:
		"item":
			var it: Item = items.get(int(id))
			return it.to_dict() if it else null
		"slot":
			return slots.get(str(id), 0)
		"station":
			return stations.get(str(id), {}).duplicate(true) if stations.has(str(id)) else null
		"lot":
			return _public_lot(int(id))
		"order":
			return orders[int(id)].duplicate(true) if orders.has(int(id)) else null
		"player":
			return players[str(id)].duplicate(true) if players.has(str(id)) else null
		"company":
			return {"cash": cash, "reputation": reputation, "parts": parts.duplicate(), "consumables": consumables.duplicate(),
				"ledger_tail": ledger.slice(maxi(0, ledger.size() - 200)), "company_name": company_name,
				"totals": totals.duplicate(true), "day_stats": day_stats.duplicate(true)}
		"clock":
			return {"minute": minute, "day_running": day_running}
		"fixture":
			return fixtures[str(id)].duplicate(true) if fixtures.has(str(id)) else null
		"building":
			return building_id
		"schedule":
			return schedule.duplicate(true)
	return null


func import_entity(kind: String, id: Variant, v: Variant) -> void:
	## Client side: overwrite local replica and emit the same signals the host emits.
	match kind:
		"item":
			var iid := int(id)
			var old: Item = items.get(iid)
			if old and old.loc.get("t") == "slot" and slots.get(old.loc["slot"]) == iid:
				slots.erase(old.loc["slot"])
			if v == null:
				items.erase(iid)
			else:
				var it := Item.from_dict(v)
				items[iid] = it
				if it.loc.get("t") == "slot":
					slots[it.loc["slot"]] = iid
		"slot":
			if int(v) == 0:
				slots.erase(str(id))
			else:
				slots[str(id)] = int(v)
		"station":
			if v == null:
				stations.erase(str(id))
			else:
				stations[str(id)] = v
		"lot":
			if v == null:
				lots.erase(int(id))
			else:
				lots[int(id)] = v
		"order":
			if v == null:
				orders.erase(int(id))
			else:
				orders[int(id)] = v
		"player":
			if v != null:
				players[str(id)] = v
		"company":
			cash = int(v.cash)
			reputation = float(v.reputation)
			parts = _int_values(v.parts)
			consumables = _int_values(v.consumables)
			ledger = v.ledger_tail
			company_name = v.company_name
			totals = v.totals
			day_stats = v.day_stats
		"clock":
			minute = float(v.minute)
			day_running = bool(v.day_running)
		"fixture":
			if v == null:
				fixtures.erase(str(id))
			else:
				fixtures[str(id)] = v
		"building":
			building_id = str(v)
		"schedule":
			schedule = v
	changed.emit(kind, id)


func _public_lot(id: int) -> Variant:
	## Lots are replicated WITHOUT their hidden unit rolls / quality.
	if not lots.has(id):
		return null
	var l: Dictionary = lots[id].duplicate(true)
	l.erase("units")
	l.erase("quality")
	return l


func snapshot() -> Dictionary:
	## Full public state for a joining client.
	var d := to_dict()
	d.erase("stats")              # the host computes Insights reports and sends them on request
	var pub_lots := {}
	for id in lots:
		pub_lots[str(id)] = _public_lot(id)
	d["lots"] = pub_lots
	return d


func building() -> Dictionary:
	return Content.building(building_id)


func fixture_items(fixture_id: String) -> Array:
	## Items sitting in any slot of this fixture.
	var out: Array = []
	var prefix := fixture_id + ":"
	for sid in slots:
		if String(sid).begins_with(prefix):
			var it: Item = items.get(slots[sid])
			if it:
				out.append(it)
	return out
