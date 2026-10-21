class_name Achievements
extends RefCounted
## Achievements: defined in data/achievements.json, evaluated from (replicated) GameState on EVERY machine — the
## host and co-op guests alike — and unlocked into a LOCAL profile on this PC (user://achievements.json) with the
## date. Ids map 1:1 onto Steam achievements later (steam_name); `unlocked` is where Steam's setAchievement goes.
##
## Two kinds of condition:
##   • state conditions, re-checked about once a second: lifetime counters (GameState.totals.lifetime, filled by
##     bump_stat), money, reputation, premises, equipment, campaign goals, skill levels, players in the session…
##   • event counters kept in the profile: things you can only see as they happen (a brew handed over, a cold tea
##     drunk, a level 3 repair, a lights-out shipment). The state watcher below turns replicated item / order /
##     lot / station changes into events, so guests see exactly what the host sees.
## Game drives it (tick, day summaries, record events) and only switches it on for real play (records_enabled).
##
## Conditions (compose with "all" / "any"; comparators gte, lte, gt, lt, eq — default ">= 1"):
##   {"stat": "totals.units_sold", "gte": 100}   totals.* / lifetime.* / day.* / reputation / cash
##   {"day_stat": "shipped", "gte": 20}           today's counter
##   {"summary": "rating", "gte": 5}              end-of-day summary, only when a day ends; {"day_end": true}
##   {"building": "warehouse"}                    premises tier reached (or passed)
##   {"fixture": "robot_arm"}                     own one (a type, a list, or a fixture_sets name)
##   {"fixture_count": {"type": "belts", "gte": 10, "placed": true}}
##   {"goal": "building_lockup", "within_days": 40}   campaign milestone (data/goals.json)
##   {"skill_level": {"skill": "repair", "gte": 7}} / {"skill_level": {"all": true, "gte": 5}}   the local player
##   {"players": {"gte": 2}}                      people in the session right now
##   {"event": "gave_brew", "gte": 10}            profile counter (events seen on this PC)
##   {"flag": "photo_face"}                       context flag supplied by the game
##   {"achievements": "all"}                      every other achievement

signal unlocked(def: Dictionary)

const STORE_PATH := "user://achievements.json"
const DATA_PATH := "res://data/achievements.json"
const EVAL_INTERVAL := 1.0
const SAVE_INTERVAL := 4.0
const COLD_TEA_SECS := 420.0        # same "stone cold" threshold as the drinking animation
const BIG_ROUND := 4
const BIG_LOT := 20
const BIG_SALE := 750
const LEMON_FAULTS := 3
const SHIPPED := ["shipped", "shipped_late"]

static var _data := {}

var store_path := STORE_PATH
var persist := true                 # false = in-memory profile only (tests)
var active := false                 # Game switches this on for real play; nothing unlocks or counts while off
var local_peer := 0                 # 0 = ask Net
var profile := {"version": 1, "unlocked": {}, "counters": {}}

var _dirty := false
var _pending := false
var _eval_t := 0.0
var _save_t := 0.0
var _state: GameState = null
# State-watcher caches (what each entity looked like last time we saw it).
var _units := {}                    # item id -> {tested, grade, fixed: [bool], born, touched}
var _mugs := {}                     # item id -> {holder, drunk}
var _pkgs := {}                     # package id -> {packer, touched, unit_ok}
var _pallets := {}                  # item id -> on a pallet truck
var _orders := {}                   # order id -> status
var _lots := {}                     # lot id -> status
var _pours := {}                    # tea station id -> pour start (belt clock)


func _init(path: String = STORE_PATH, persistent: bool = true) -> void:
	store_path = path
	persist = persistent
	load_profile()


# ================================================================= definitions

static func data() -> Dictionary:
	if _data.is_empty():
		var d = JSON.parse_string(FileAccess.get_file_as_string(DATA_PATH))
		_data = d if d is Dictionary else {"achievements": [], "groups": []}
	return _data


static func defs() -> Array:
	return data().get("achievements", [])


static func get_def(id: String) -> Dictionary:
	for d: Dictionary in defs():
		if d.id == id:
			return d
	return {}


static func groups() -> Array:
	return data().get("groups", [])


static func in_group(group: String) -> Array:
	return defs().filter(func(d): return str(d.get("group", "")) == group)


static func steam_name(id: String) -> String:
	## API name to use when these are registered on Steamworks.
	return "ACH_" + id.to_upper()


static func display(def: Dictionary, is_unlocked: bool) -> Dictionary:
	## What a list shows: hidden achievements stay a mystery until they're unlocked.
	if def.get("hidden", false) and not is_unlocked:
		return {"name": "???", "desc": "Hidden achievement — keep playing to find it.", "icon": "?", "hidden": true}
	return {"name": str(def.get("name", "")), "desc": str(def.get("desc", "")), "icon": str(def.get("icon", "★")), "hidden": false}


# ================================================================= profile

func load_profile() -> void:
	profile = {"version": 1, "unlocked": {}, "counters": {}}
	if not persist or not FileAccess.file_exists(store_path):
		return
	var d = JSON.parse_string(FileAccess.get_file_as_string(store_path))
	if not d is Dictionary:
		return
	if d.get("unlocked") is Dictionary:
		profile.unlocked = d.unlocked
	if d.get("counters") is Dictionary:
		for k in d.counters:
			profile.counters[k] = int(d.counters[k])


func save_profile() -> void:
	_dirty = false
	_save_t = SAVE_INTERVAL
	if not persist:
		return
	var f := FileAccess.open(store_path, FileAccess.WRITE)
	if f:
		f.store_string(JSON.stringify(profile, " "))


func flush() -> void:
	if _dirty:
		save_profile()


func is_unlocked(id: String) -> bool:
	return profile.unlocked.has(id)


func unlock_info(id: String) -> Dictionary:
	return profile.unlocked.get(id, {})


func unlocked_count() -> int:
	var n := 0
	for d: Dictionary in defs():
		if is_unlocked(d.id):
			n += 1
	return n


func counter(event_name: String) -> int:
	return int(profile.counters.get(event_name, 0))


func unlock(id: String, state: GameState = null) -> bool:
	## Idempotent: false if already unlocked (or unknown).
	if is_unlocked(id):
		return false
	var def := get_def(id)
	if def.is_empty():
		return false
	var rec := {"date": Time.get_date_string_from_system(), "unix": int(Time.get_unix_time_from_system())}
	if state:
		rec["company"] = state.company_name
		rec["day"] = Campaign.day_number(state)
	profile.unlocked[id] = rec
	save_profile()
	unlocked.emit(def)
	return true


func record_event(event_name: String, n: int = 1) -> void:
	## Count something that happened (only during real play). Achievements re-check on the next tick.
	if not active or event_name == "":
		return
	profile.counters[event_name] = counter(event_name) + n
	_dirty = true
	_pending = true


# ================================================================= evaluation

func tick(delta: float, state: GameState, ctx_fn: Callable) -> void:
	## Called every frame by Game while in a real game. Evaluates once a second (or right after an event).
	if not active or state == null:
		return
	if _style.is_empty() and state == _state:
		_style = state.premises.duplicate(true)      # baseline for fit-out / Employee of the Month events
		_style["_seen"] = true
	_eval_t -= delta
	_save_t -= delta
	if _pending or _eval_t <= 0.0:
		_pending = false
		_eval_t = EVAL_INTERVAL
		evaluate(state, ctx_fn.call())
	if _dirty and _save_t <= 0.0:
		save_profile()


func on_day_end(state: GameState, summary: Dictionary, ctx: Dictionary = {}) -> Array:
	if not active:
		return []
	var c := ctx.duplicate()
	c["summary"] = summary
	return evaluate(state, c)


func evaluate(state: GameState, ctx: Dictionary = {}) -> Array:
	## Unlock everything whose condition now holds. Returns the ids unlocked by this call.
	var got: Array = []
	if not active or state == null:
		return got
	for _pass in 3:          # a second pass lets "every other achievement" land in the same evaluation
		var new_now := 0
		for d: Dictionary in defs():
			if is_unlocked(d.id):
				continue
			if check(d.get("condition", {}), state, ctx) and unlock(d.id, state):
				got.append(d.id)
				new_now += 1
		if new_now == 0:
			break
	return got


func check(cond: Dictionary, state: GameState, ctx: Dictionary = {}) -> bool:
	if cond.is_empty():
		return false
	if cond.has("all"):
		for c: Dictionary in cond.all:
			if not check(c, state, ctx):
				return false
		return true
	if cond.has("any"):
		for c: Dictionary in cond.any:
			if check(c, state, ctx):
				return true
		return false
	if cond.has("event"):
		return _cmp(float(counter(str(cond.event))), cond)
	if cond.has("flag"):
		return bool(ctx.get("flags", {}).get(str(cond.flag), false))
	if cond.has("players"):
		return _cmp(float(ctx.get("peers", 1)), cond.players)
	if cond.has("summary"):
		return ctx.has("summary") and _cmp(float(ctx.summary.get(str(cond.summary), 0)), cond)
	if cond.has("day_end"):
		return ctx.has("summary") == bool(cond.day_end)
	if cond.has("achievements"):
		var prog := _meta_progress()
		if str(cond.achievements) == "all":
			return prog[0] >= prog[1]
		return cond.achievements is Dictionary and _cmp(prog[0], cond.achievements)
	if state == null:
		return false
	if cond.has("stat"):
		return _cmp(stat_value(state, str(cond.stat)), cond)
	if cond.has("day_stat"):
		return _cmp(float(state.day_stats.get(str(cond.day_stat), 0)), cond)
	if cond.has("building"):
		return Campaign.tier_of(state.building_id) >= Campaign.tier_of(str(cond.building))
	if cond.has("fixture"):
		return fixture_count(state, cond.fixture, false) >= 1
	if cond.has("fixture_count"):
		var fc: Dictionary = cond.fixture_count
		return _cmp(float(fixture_count(state, fc.get("type", "any"), bool(fc.get("placed", false)))), fc)
	if cond.has("goal"):
		var got := Campaign.reached(state)
		if not got.has(str(cond.goal)):
			return false
		return not cond.has("within_days") or int(got[str(cond.goal)]) <= int(cond.within_days)
	if cond.has("skill_level"):
		return _cmp(float(skill_value(state, ctx, cond.skill_level)), cond.skill_level)
	return false


func progress(cond: Dictionary, state: GameState, ctx: Dictionary = {}) -> Array:
	## [current, target] for a progress bar, or [] when it isn't measurable. state may be null (main menu).
	if cond.has("all") or cond.has("any"):
		var best: Array = []
		var best_f := -1.0
		var is_all := cond.has("all")
		var counted := 0
		var done := 0
		for c: Dictionary in (cond.all if is_all else cond.any):
			var p := progress(c, state, ctx)
			if p.is_empty():
				continue
			counted += 1
			var f := clampf(float(p[0]) / maxf(float(p[1]), 0.0001), 0.0, 1.0)
			if f >= 1.0:
				done += 1
			if best.is_empty() or (is_all and f < best_f) or (not is_all and f > best_f):
				best = p
				best_f = f
		if is_all and counted > 1 and best.size() == 2 and float(best[1]) <= 1.0:
			return [float(done), float(counted)]        # a checklist: "6 / 9 kinds tested"
		return best
	var target := float(cond.get("gte", 0))
	if cond.has("event"):
		return [float(counter(str(cond.event))), maxf(target, 1.0)]
	if cond.has("achievements"):
		return _meta_progress()
	if state == null:
		return []
	if cond.has("stat") and cond.has("gte"):
		return [stat_value(state, str(cond.stat)), target]
	if cond.has("day_stat") and cond.has("gte"):
		return [float(state.day_stats.get(str(cond.day_stat), 0)), target]
	if cond.has("building"):
		var need := Campaign.tier_of(str(cond.building))
		return [float(mini(Campaign.tier_of(state.building_id), need)), float(need)]
	if cond.has("fixture"):
		return [float(mini(fixture_count(state, cond.fixture, false), 1)), 1.0]
	if cond.has("fixture_count"):
		var fc: Dictionary = cond.fixture_count
		return [float(fixture_count(state, fc.get("type", "any"), bool(fc.get("placed", false)))), float(fc.get("gte", 1))]
	if cond.has("skill_level"):
		return [float(skill_value(state, ctx, cond.skill_level)), float(cond.skill_level.get("gte", 1))]
	if cond.has("goal"):
		var m := Campaign.milestone(str(cond.goal))
		if not m.is_empty() and not cond.has("within_days"):
			return Campaign.progress(state, m)
	return []


static func _cmp(v: float, c: Dictionary) -> bool:
	var any_op := false
	if c.has("gte"):
		any_op = true
		if v < float(c.gte):
			return false
	if c.has("gt"):
		any_op = true
		if v <= float(c.gt):
			return false
	if c.has("lte"):
		any_op = true
		if v > float(c.lte):
			return false
	if c.has("lt"):
		any_op = true
		if v >= float(c.lt):
			return false
	if c.has("eq"):
		any_op = true
		if not is_equal_approx(v, float(c.eq)):
			return false
	return v >= 1.0 if not any_op else true


static func stat_value(state: GameState, path: String) -> float:
	var parts := path.split(".")
	var node: Variant
	match parts[0]:
		"reputation":
			return state.reputation
		"cash":
			return float(state.cash)
		"totals":
			node = state.totals
		"lifetime":
			node = state.totals.get("lifetime", {})
		"day":
			node = state.day_stats
		_:
			return 0.0
	for i in range(1, parts.size()):
		if not node is Dictionary:
			return 0.0
		node = node.get(parts[i], 0)
	return float(node) if (node is int or node is float) else 0.0


static func fixture_types(types: Variant) -> Array:
	## A type id, a list of them, a fixture_sets name, or "any" (empty list).
	if types is Array:
		return types
	var s := str(types)
	if s == "any":
		return []
	var sets: Dictionary = data().get("fixture_sets", {})
	return sets.get(s, [s])


static func fixture_count(state: GameState, types: Variant, placed_only: bool) -> int:
	var want := fixture_types(types)
	var n := 0
	for f: Dictionary in state.fixtures.values():
		if placed_only and not f.get("placed", false):
			continue
		if want.is_empty() or str(f.get("type", "")) in want:
			n += 1
	return n


static func skill_value(state: GameState, ctx: Dictionary, sk: Dictionary) -> int:
	var key := str(ctx.get("player_key", "p1"))
	if sk.has("skill"):
		return state.skill_level(key, str(sk.skill))
	var ids: Array = Content.skill_defs().map(func(s): return str(s.get("id", "")))
	if ids.is_empty():
		return 1
	var lo := 999
	var hi := 1
	for id in ids:
		var lvl := state.skill_level(key, id)
		lo = mini(lo, lvl)
		hi = maxi(hi, lvl)
	return lo if sk.get("all", false) else hi


func _meta_progress() -> Array:
	## Unlocked vs total, not counting meta achievements ("every other achievement").
	var total := 0
	var have := 0
	for d: Dictionary in defs():
		if d.get("condition", {}).has("achievements"):
			continue
		total += 1
		if is_unlocked(d.id):
			have += 1
	return [float(have), float(total)]


# ================================================================= state watcher (event achievements)

func watch(state: GameState) -> void:
	## Follow a GameState's change signals. Works the same on the host (live mutations) and on guests
	## (replicated deltas), so everyone earns the same event achievements.
	if _state and _state.changed.is_connected(_on_changed):
		_state.changed.disconnect(_on_changed)
	_state = state
	for c: Dictionary in [_units, _mugs, _pkgs, _pallets, _orders, _lots, _pours, _style]:
		c.clear()
	if state:
		state.changed.connect(_on_changed)


func _me() -> int:
	return local_peer if local_peer != 0 else Net.local_peer()


func _on_changed(kind: String, id: Variant) -> void:
	if not active or _state == null:
		return
	match kind:
		"item":
			_watch_item(int(id))
		"order":
			_watch_order(int(id))
		"lot":
			_watch_lot(int(id))
		"station":
			_watch_station(str(id))
		"premises":
			_watch_premises()


func _watch_item(iid: int) -> void:
	var it: Item = _state.items.get(iid)
	if it == null or it.loc.get("t") == "gone":
		_units.erase(iid)
		_mugs.erase(iid)
		_pallets.erase(iid)
		return             # packages stay cached until their order ships
	match it.kind:
		Item.KIND_UNIT:
			_watch_unit(it)
		Item.KIND_MUG:
			_watch_mug(it)
		Item.KIND_PACKAGE:
			_watch_package(it)
		Item.KIND_PALLET:
			_watch_pallet(it)


func _watch_unit(it: Item) -> void:
	var u := it.unit
	if u == null:
		return
	var held: bool = it.loc.get("t") == "held"
	var fixed: Array = u.faults.map(func(f): return bool(f.get("fixed", false)))
	var c: Dictionary = _units.get(it.id, {})
	if c.is_empty():
		# First sighting. "born" = we've watched it since before it was tested, so we know its whole story.
		_units[it.id] = {"tested": u.tested, "grade": u.grade, "fixed": fixed, "born": not u.tested and not held, "touched": held}
		_link_package(it, _units[it.id])
		return
	if held:
		c.touched = true
	if u.tested and not c.tested:
		c.tested = true
		record_event("tested_" + u.hw_type())
		if u.faults.size() >= LEMON_FAULTS:
			record_event("lemon")
	if u.grade != c.grade:
		c.grade = u.grade
		if u.grade != "":
			record_event("grade_" + u.grade)
	var old: Array = c.fixed
	if old.size() == fixed.size():
		for i in fixed.size():
			if fixed[i] and not old[i]:
				var fid := str(u.faults[i].get("id", ""))
				record_event("repair_l%d" % int(Content.fault(fid).get("repair_level", 1)))
				record_event("fix_" + fid)
	c.fixed = fixed
	_link_package(it, c)


func _link_package(it: Item, c: Dictionary) -> void:
	## A unit boxed into a package: remember whether it went all the way without a human touching it.
	if it.loc.get("t") != "in" or int(it.loc.get("item", 0)) == 0:
		return
	var p := _pkg(int(it.loc.item))
	p.unit_ok = bool(c.born) and not bool(c.touched) and it.unit.booked


func _pkg(pid: int) -> Dictionary:
	if not _pkgs.has(pid):
		if _pkgs.size() > 4000:
			_pkgs.clear()          # safety valve; only loses a pending lights-out check
		_pkgs[pid] = {"packer": -1, "touched": false, "unit_ok": false}
	return _pkgs[pid]


func _watch_package(it: Item) -> void:
	var p := _pkg(it.id)
	if int(p.packer) == -1 and it.data.has("packer"):
		p.packer = int(it.data.packer)
		if p.packer == _me():
			record_event("packed")
		elif p.packer == 0:
			record_event("robot_packed")
	if it.loc.get("t") == "held":
		p.touched = true


func _watch_mug(it: Item) -> void:
	var holder: int = int(it.loc.get("peer", 0)) if it.loc.get("t") == "held" else 0
	var drunk := bool(it.data.get("drunk", false))
	var m: Dictionary = _mugs.get(it.id, {})
	_mugs[it.id] = {"holder": holder, "drunk": drunk}
	if m.is_empty():
		return
	var me := _me()
	if int(m.holder) == me and holder != 0 and holder != me:
		record_event("gave_brew")
	if drunk and not m.drunk and holder == me:
		record_event("drank_brew")
		var now := _state.belt_now(Net.is_host())
		if now - float(it.data.get("t", now)) > COLD_TEA_SECS:
			record_event("drank_cold")
		var by := int(it.data.get("by", 0))
		if by != 0 and by != me:
			record_event("drank_gift")
		if not it.data.get("milk", false):
			record_event("drank_black")


func _watch_pallet(it: Item) -> void:
	var t := str(it.loc.get("t", ""))
	var was: Dictionary = _pallets.get(it.id, {})
	var mine_on_forks := false
	if t == "on_forks":
		var fork: Item = _state.items.get(int(it.loc.get("fork", 0)))
		mine_on_forks = fork != null and fork.loc.get("t") == "driven" and int(fork.loc.get("peer", 0)) == _me()
	_pallets[it.id] = {"t": t, "mine": mine_on_forks if t == "on_forks" else bool(was.get("mine", false)) and t == "slot"}
	if t == "on_truck" and was.get("t", "") != "on_truck":
		var truck: Item = _state.items.get(int(it.loc.get("truck", 0)))
		if truck and truck.loc.get("t") == "pushed" and int(truck.loc.get("peer", 0)) == _me():
			record_event("pallet_lift")
	elif mine_on_forks and was.get("t", "") != "on_forks":
		record_event("fork_lift")
	elif t == "slot" and was.get("t", "") == "on_forks" and bool(was.get("mine", false)):
		# Put away by my forklift: the top level of the racking?
		var sid := str(it.loc.get("slot", ""))
		var f: Dictionary = _state.fixtures.get(sid.get_slice(":", 0), {})
		var levels: Array = Logistics.rack_def(str(f.get("type", ""))).get("levels", [])
		if levels.size() > 1 and Logistics.parse_slot(sid.get_slice(":", 1)).y == levels.size() - 1:
			record_event("racked_top")


var _style := {}


func _watch_premises() -> void:
	## Fit-out changes and the Employee of the Month (replicated, so guests see them too).
	var p: Dictionary = _state.premises
	if _style.is_empty():
		_style = {"_seen": true}      # no baseline yet (tests drive the watcher directly): everything is new
	if true:
		if str(p.get("paint", "original")) != str(_style.get("paint", "original")) and str(p.get("paint", "original")) != "original":
			record_event("painted")
		if str(p.get("markings", "none")) != str(_style.get("markings", "none")) and str(p.get("markings", "none")) != "none":
			record_event("marked_out")
		var e: Dictionary = p.get("eotm", {})
		var old: Dictionary = _style.get("eotm", {})
		if str(e.get("key", "")) == Sim.player_key(_me()) and (int(e.get("day", -1)) != int(old.get("day", -1)) or str(old.get("key", "")) != str(e.key)):
			record_event("eotm_me")
	_style = p.duplicate(true)
	_style["_seen"] = true


func _watch_order(oid: int) -> void:
	var o: Dictionary = _state.orders.get(oid, {})
	if o.is_empty():
		return
	var st := str(o.get("status", ""))
	var old: String = _orders.get(oid, "")
	_orders[oid] = st
	if not st in SHIPPED or old in SHIPPED:
		return
	if old == "" and float(o.get("shipped", -1.0)) < _state.minute - 10.0:
		return              # an old order we've only just noticed, not a fresh shipment
	if st == "shipped":
		record_event("shipped_on_time")
	if int(o.get("price", 0)) >= BIG_SALE:
		record_event("big_sale")
	var pid := int(o.get("package", 0))
	var p: Dictionary = _pkgs.get(pid, {})
	if not p.is_empty() and int(p.packer) == 0:
		record_event("robot_shipped")
		if not p.touched and p.unit_ok:
			record_event("lights_out")
	_pkgs.erase(pid)


func _watch_lot(lid: int) -> void:
	var l: Dictionary = _state.lots.get(lid, {})
	if l.is_empty():
		return
	var st := str(l.get("status", ""))
	var old: String = _lots.get(lid, "")
	_lots[lid] = st
	if st != "bought" or old == "bought" or int(l.get("bought_by", 0)) != _me():
		return
	record_event("lot_bought")
	if int(l.get("count", 0)) >= BIG_LOT:
		record_event("big_lot")


func _watch_station(sid: String) -> void:
	var st: Dictionary = _state.stations.get(sid, {})
	if str(st.get("type", "")) != "tea":
		return
	var pour: Dictionary = st.get("pour", {})
	if pour.is_empty():
		return
	var t0 := float(pour.get("t0", -1.0))
	var seen := _pours.has(sid)
	if seen and is_equal_approx(t0, float(_pours[sid])):
		return
	_pours[sid] = t0
	if not seen and _state.belt_now(Net.is_host()) - t0 > 10.0:
		return              # an old round from before we were watching
	var ids: Array = pour.get("ids", [])
	for mid in ids:
		var m: Item = _state.items.get(int(mid))
		if m and int(m.data.get("by", 0)) == _me():
			record_event("poured_round")
			if ids.size() >= BIG_ROUND:
				record_event("big_round")
			return
