extends TestCase
## Achievements: the data is sane, conditions read the game state correctly, unlocking is idempotent and kept in
## this PC's profile, hidden ones stay hidden, and the state watcher turns tea, repairs, lots, pallets and
## shipments into events — on the host AND on a co-op guest fed only replicated deltas.
## Every test uses an in-memory profile (or a throwaway file), never the real user://achievements.json.

const KNOWN := ["all", "any", "stat", "day_stat", "summary", "day_end", "building", "fixture", "fixture_count", "goal",
	"skill_level", "players", "event", "flag", "achievements"]


func _ach(peer: int = 1) -> Achievements:
	var a := Achievements.new("", false)
	a.active = true
	a.local_peer = peer
	return a


func _ctx(peers: int = 1) -> Dictionary:
	return {"peers": peers, "player_key": "p1", "flags": {}}


func _laptop_model() -> String:
	for m: Dictionary in Content.models.values():
		if m.get("type", "laptop") == "laptop":
			return m.id
	return ""


func _unit(s: GameState, faults: Array) -> Item:
	var it := s.new_item(Item.KIND_UNIT)
	var u := UnitData.new()
	u.model_id = _laptop_model()
	for f in faults:
		u.faults.append({"id": f, "detail": {}, "fixed": false})
	it.unit = u
	s.set_loc(it, {"t": "loose", "p": [0, 0, 0], "r": [0, 0, 0, 1]})
	return it


func _ship(s: GameState, it: Item, packer: int, price: int) -> void:
	## Pack (by `packer`, 0 = robot) and ship one unit the way Sim does, signal for signal.
	var oid := s.make_id()
	s.orders[oid] = {"id": oid, "status": "to_pack", "price": price, "due": s.minute + 600.0}
	s.mark("order", oid)
	var pkg := s.new_item(Item.KIND_PACKAGE)
	pkg.data = {"order_id": oid, "unit": it.id, "packer": packer}
	s.set_loc(it, {"t": "in", "item": 0})
	it.loc = {"t": "in", "item": pkg.id}
	s.mark("item", it.id)
	s.set_loc(pkg, {"t": "slot", "slot": "test_out:%d" % pkg.id})
	s.orders[oid].status = "packed"
	s.orders[oid]["package"] = pkg.id
	s.mark("order", oid)
	s.orders[oid].status = "shipped"
	s.orders[oid]["shipped"] = s.minute
	s.mark("order", oid)
	s.remove_item(it.id)
	s.remove_item(pkg.id)


func _kitchen(s: GameState) -> void:
	s.register_station("kitchen", "tea")
	for i in 6:
		s.register_slot("kitchen:mug%d" % i, ["mug"], "kitchen")


func _replicate(host: GameState, guest: GameState) -> void:
	## What Game._flush_replication + _rpc_apply do, minus the network.
	for key in host.dirty:
		var kv: Array = host.dirty[key]
		guest.import_entity(kv[0], kv[1], host.export_entity(kv[0], kv[1]))
	host.dirty.clear()


func _refs(id: String, c: Dictionary) -> void:
	check(c.keys().any(func(k): return k in KNOWN), "%s: known condition type %s" % [id, str(c.keys())])
	for sub in c.get("all", []) + c.get("any", []):
		_refs(id, sub)
	if c.has("building"):
		check(Content.buildings.has(str(c.building)), "%s: building %s exists" % [id, c.building])
	if c.has("fixture") or c.has("fixture_count"):
		var types: Variant = c.fixture if c.has("fixture") else c.fixture_count.get("type", "any")
		for t in Achievements.fixture_types(types):
			check(Content.fixtures.has(t), "%s: fixture type %s exists" % [id, t])
	if c.has("goal"):
		var end_id := str(Campaign.data().get("end_goal", {}).get("id", ""))
		check(not Campaign.milestone(str(c.goal)).is_empty() or str(c.goal) == end_id, "%s: goal %s exists" % [id, c.goal])
	if c.has("skill_level") and c.skill_level.has("skill"):
		check(Content.skill_defs().any(func(s): return s.id == c.skill_level.skill), "%s: skill exists" % id)
	if c.has("stat"):
		check(str(c.stat).split(".")[0] in ["totals", "lifetime", "day", "reputation", "cash"], "%s: stat root" % id)


# ================================================================= data

func test_data_is_sane() -> void:
	var defs := Achievements.defs()
	check(defs.size() >= 50 and defs.size() <= 100, "50–100 achievements (got %d)" % defs.size())
	var groups := Achievements.groups()
	var ids := {}
	var hidden := 0
	for d: Dictionary in defs:
		check(not ids.has(d.id), "unique id %s" % d.id)
		ids[d.id] = true
		check(str(d.get("name", "")) != "" and str(d.get("desc", "")) != "", "%s has a name and description" % d.id)
		var icon := str(d.get("icon", ""))
		check(icon.length() >= 1 and icon.length() <= 2, "%s icon is a single glyph (%s)" % [d.id, icon])
		check(str(d.get("group", "")) in groups, "%s is in a known group" % d.id)
		check(not (d.get("condition", {}) as Dictionary).is_empty(), "%s has a condition" % d.id)
		eq(Achievements.steam_name(d.id), "ACH_" + str(d.id).to_upper(), "Steam API name")
		if d.get("hidden", false):
			hidden += 1
		_refs(d.id, d.get("condition", {}))
	check(hidden >= 3, "a few secret ones (%d)" % hidden)
	for g in groups:
		check(not Achievements.in_group(g).is_empty(), "group %s has achievements" % g)


# ================================================================= conditions

func test_a_new_company_has_earned_nothing() -> void:
	var sim := fresh_sim(901)
	eq(_ach().evaluate(sim.state, _ctx()), [], "day one, nothing unlocked yet")


func test_conditions_read_the_game_state() -> void:
	var sim := fresh_sim(902)
	var s := sim.state
	var a := _ach()
	check(not a.check({"stat": "totals.units_bought", "gte": 1}, s), "nothing bought yet")
	s.totals["units_bought"] = 12
	check(a.check({"stat": "totals.units_bought", "gte": 10}, s), "bought 12")
	# Lifetime counters ride on bump_stat.
	s.dirty.clear()
	s.bump_stat("tested", 3)
	s.bump_stat("tested")
	eq(s.lifetime("tested"), 4, "lifetime counter accumulates")
	eq(int(s.day_stats.get("tested", 0)), 4, "the day counter still counts")
	check(s.dirty.has("company|0"), "lifetime counters replicate with the company")
	s.day_stats = {}
	check(a.check({"stat": "lifetime.tested", "gte": 4}, s) and not a.check({"stat": "lifetime.tested", "gte": 5}, s), "lifetime outlives the day")
	var saved := GameState.new()
	saved.load_dict(s.to_dict())
	eq(saved.lifetime("tested"), 4, "and survives a save")
	# Money, reputation, today's counters.
	s.cash = -50
	check(a.check({"stat": "cash", "lte": -1}, s), "overdrawn")
	s.reputation = 100.0
	check(a.check({"stat": "reputation", "gte": 100}, s), "max reputation")
	s.day_stats["shipped"] = 12
	check(a.check({"day_stat": "shipped", "gte": 10}, s), "busy day")
	# End-of-day summaries only count when a day ends.
	var five := {"summary": "rating", "gte": 5}
	check(not a.check(five, s, _ctx()), "summary conditions wait for the end of the day")
	var end := _ctx()
	end["summary"] = {"rating": 5, "shipped": 6, "late": 0, "missed": 0}
	check(a.check(five, s, end), "five-star shift")
	var clean: Dictionary = Achievements.get_def("clean_sheet").condition
	check(a.check(clean, s, end), "clean sheet")
	end.summary.late = 1
	check(not a.check(clean, s, end), "one late order spoils it")
	# Premises tier reached or passed.
	check(not a.check({"building": "lockup"}, s), "still in the garage")
	s.building_id = "warehouse"
	check(a.check({"building": "lockup"}, s) and a.check({"building": "warehouse"}, s), "tier reached or passed")
	check(not a.check({"building": "distribution"}, s), "not there yet")
	# Equipment.
	check(a.check({"fixture": "test_bench"}, s), "the start kit has a test bench")
	check(not a.check({"fixture": "belts"}, s), "no conveyors yet")
	for i in 10:
		s.fixtures["belt_%d" % i] = {"type": "conveyor", "label": "", "placed": i < 9, "p": [0, 0, 0], "r": 0.0}
	check(a.check({"fixture": "belts"}, s), "a conveyor (fixture set)")
	var line: Dictionary = Achievements.get_def("production_line").condition
	check(not a.check(line, s), "only 9 laid out")
	s.fixtures["belt_9"].placed = true
	check(a.check(line, s), "a 10-belt line")
	# Campaign goals, optionally against the clock.
	s.totals["goals"] = {"building_lockup": 52}
	check(a.check({"goal": "building_lockup"}, s), "goal reached")
	check(not a.check(Achievements.get_def("fast_mover").condition, s), "but not within 40 days")
	# The local player's skills.
	check(not a.check({"skill_level": {"gte": 5}}, s, _ctx()), "level 1 to start")
	s.add_xp("p1", "repair", 99999)
	check(a.check({"skill_level": {"gte": 10}}, s, _ctx()), "max level in one skill")
	check(a.check({"skill_level": {"skill": "repair", "gte": 7}}, s, _ctx()), "repair 7")
	check(not a.check({"skill_level": {"all": true, "gte": 5}}, s, _ctx()), "not every skill")
	check(not a.check({"skill_level": {"gte": 5}}, s, {"player_key": "p2"}), "someone else's skills aren't yours")
	# Session, events, flags.
	check(not a.check({"players": {"gte": 2}}, s, _ctx(1)) and a.check({"players": {"gte": 2}}, s, _ctx(2)), "co-op")
	check(not a.check({"event": "gave_brew"}, s), "no brew handed over yet")
	a.record_event("gave_brew")
	check(a.check({"event": "gave_brew"}, s), "brew handed over")
	check(a.check({"flag": "photo_face"}, s, {"flags": {"photo_face": true}}), "photo face")
	check(a.check({"any": [{"flag": "nope"}, {"event": "gave_brew"}]}, s), "any-of")
	# Progress bars.
	eq(a.progress({"stat": "lifetime.tested", "gte": 100}, s), [4.0, 100.0], "stat progress")
	var house: Dictionary = Achievements.get_def("full_house").condition
	eq(a.progress(house, s), [0.0, 9.0], "a checklist of nine kinds")
	a.record_event("tested_laptop")
	eq(a.progress(house, s), [1.0, 9.0], "one kind down")
	eq(a.progress({"stat": "cash", "lte": -1}, s), [], "not measurable")
	eq(a.progress({"event": "gave_brew", "gte": 25}, null), [1.0, 25.0], "event progress works on the main menu (no state)")


func test_unlocking_is_idempotent() -> void:
	var s := fresh_sim(903).state
	var a := _ach()
	var fired := [0]
	a.unlocked.connect(func(_d): fired[0] += 1)
	s.totals["units_bought"] = 1
	var got := a.evaluate(s, _ctx())
	check("open_for_business" in got, "first lot unlocks Open for business: %s" % str(got))
	eq(a.evaluate(s, _ctx()), [], "nothing new on a second pass")
	check(not a.unlock("open_for_business", s), "unlocking twice is a no-op")
	eq(fired[0], got.size(), "one signal per unlock")
	check(a.unlock_info("open_for_business").has("date"), "unlock date recorded")
	eq(int(a.unlock_info("open_for_business").get("day", 0)), 1, "and the run day")
	check(not a.unlock("no_such_thing"), "unknown ids are ignored")
	# Inactive (tools, tests, menus): nothing unlocks and nothing counts.
	var off := Achievements.new("", false)
	s.totals["units_sold"] = 500
	eq(off.evaluate(s, _ctx()), [], "inactive never unlocks")
	off.record_event("gave_brew")
	eq(off.counter("gave_brew"), 0, "and never counts")


func test_completionist_needs_everything_else() -> void:
	var s := fresh_sim(904).state
	var a := _ach()
	for d: Dictionary in Achievements.defs():
		if d.id != "completionist" and d.id != "lemon":
			a.unlock(d.id, s)
	eq(a.evaluate(s, _ctx()), [], "one short")
	a.unlock("lemon", s)
	eq(a.evaluate(s, _ctx()), ["completionist"], "every other achievement → Completionist")


func test_hidden_ones_stay_hidden_until_unlocked() -> void:
	var a := _ach()
	var def := Achievements.get_def("stone_cold")
	check(def.get("hidden", false), "Stone cold is a secret")
	var locked := Achievements.display(def, a.is_unlocked("stone_cold"))
	eq(locked.name, "???", "name hidden")
	eq(locked.icon, "?", "icon hidden")
	check(not str(locked.desc).to_lower().contains("cold"), "description hidden")
	a.unlock("stone_cold")
	var shown := Achievements.display(def, a.is_unlocked("stone_cold"))
	eq(shown.name, "Stone cold", "revealed once unlocked")
	check(not shown.hidden, "no longer hidden")
	eq(Achievements.display(Achievements.get_def("kettles_on"), false).name, "Kettle's on", "normal ones show while locked")


func test_profile_persists_on_this_pc() -> void:
	var path := "user://test_achievements_tmp.json"
	if FileAccess.file_exists(path):
		DirAccess.remove_absolute(ProjectSettings.globalize_path(path))
	var a := Achievements.new(path, true)
	a.active = true
	a.unlock("kettles_on")
	a.record_event("gave_brew", 2)
	a.flush()
	var b := Achievements.new(path, true)
	check(b.is_unlocked("kettles_on"), "unlock survives a restart")
	eq(b.counter("gave_brew"), 2, "counters survive too")
	DirAccess.remove_absolute(ProjectSettings.globalize_path(path))
	check(Achievements.new("", false).profile.unlocked.is_empty(), "in-memory profiles start empty")


# ================================================================= event watcher

func test_tea_events() -> void:
	var sim := fresh_sim(905)
	var s := sim.state
	var a := _ach()
	a.watch(s)
	_kitchen(s)
	sim.active_peers = [1, 2]
	for i in 4:
		sim.execute(1, "tea", {"station": "kitchen", "act": "mug"})
	sim.execute(1, "tea", {"station": "kitchen", "act": "boil"})
	s.belt_clock += 20.0
	check(sim.execute(1, "tea", {"station": "kitchen", "act": "pour"}).ok, "pour a round of four")
	eq(a.counter("big_round"), 1, "a round of four")
	var mugs := sim._tea_mugs("kitchen")
	check(sim.execute(1, "pick_up", {"item": mugs[0].id}).ok and sim.execute(1, "pick_up", {"item": mugs[1].id}).ok, "carry two")
	check(sim.execute(1, "give_mug", {"to": 2}).ok, "hand one over")
	eq(a.counter("gave_brew"), 1, "handed a brew to a colleague")
	check(sim.execute(2, "drink_mug", {}).ok, "they drink theirs")
	eq(a.counter("drank_brew"), 0, "their drink isn't mine")
	s.belt_clock += 600.0
	check(sim.execute(1, "drink_mug", {}).ok, "drink my own, ten minutes later")
	eq(a.counter("drank_brew"), 1, "drank one")
	eq(a.counter("drank_cold"), 1, "stone cold")
	eq(a.counter("drank_black"), 1, "no milk")
	eq(a.counter("drank_gift"), 0, "I made it myself")
	var got := a.evaluate(s, _ctx(2))
	for id in ["kettles_on", "whose_round", "brew_buddy", "stone_cold", "two_pairs_of_hands"]:
		check(id in got, "%s unlocked: %s" % [id, str(got)])
	check(not "proper_teamwork" in got, "nobody made me one")


func test_unit_repair_and_shipping_events() -> void:
	var sim := fresh_sim(906)
	var s := sim.state
	var a := _ach()
	a.watch(s)
	var it := _unit(s, ["board_no_post", "screen_cracked", "battery_worn"])
	it.unit.tested = true
	s.mark("item", it.id)
	eq(a.counter("tested_laptop"), 1, "tested a laptop")
	eq(a.counter("lemon"), 1, "three faults = a lemon")
	it.unit.faults[0].fixed = true
	s.mark("item", it.id)
	eq(a.counter("repair_l3"), 1, "board-level repair")
	eq(a.counter("fix_board_no_post"), 1, "the No POST fix")
	it.unit.faults[1].fixed = true
	s.mark("item", it.id)
	eq(a.counter("repair_l2"), 1, "level 2 repair")
	it.unit.grade = "A"
	s.mark("item", it.id)
	s.mark("item", it.id)
	eq(a.counter("grade_A"), 1, "graded A once, counted once")
	# Lights out: booked by the scanner, never in anyone's hands, packed by a robot, shipped.
	it.unit.booked = true
	_ship(s, it, 0, 950)
	eq(a.counter("robot_shipped"), 1, "robot-packed order shipped")
	eq(a.counter("lights_out"), 1, "lights out")
	eq(a.counter("big_sale"), 1, "£950 order")
	eq(a.counter("shipped_on_time"), 1, "on time")
	# Somebody carried this one: no lights-out.
	var carried := _unit(s, [])
	carried.unit.booked = true
	s.set_loc(carried, {"t": "held", "peer": 1, "i": 0})
	s.set_loc(carried, {"t": "loose", "p": [0, 0, 0], "r": [0, 0, 0, 1]})
	_ship(s, carried, 0, 100)
	eq(a.counter("robot_shipped"), 2, "robot-packed again")
	eq(a.counter("lights_out"), 1, "but a human carried it")
	# And one I packed myself.
	_ship(s, _unit(s, []), 1, 100)
	eq(a.counter("packed"), 1, "packed an order myself")
	var got := a.evaluate(s, _ctx())
	for id in ["lights_out", "high_roller", "board_wizard", "steady_hands", "lazarus", "mint_condition", "lemon", "thats_a_wrap"]:
		check(id in got, "%s unlocked: %s" % [id, str(got)])


func test_lot_and_pallet_truck_events() -> void:
	var sim := fresh_sim(907)
	var s := sim.state
	var a := _ach()
	a.watch(s)
	s.cash = 1000000
	var lot_id: int = s.lots.keys()[0]
	s.lots[lot_id].count = 24
	s.mark("lot", lot_id)
	check(sim.execute(1, "buy_lot", {"lot": lot_id}).ok, "buy a 24-unit lot")
	eq(a.counter("lot_bought"), 1, "bought a lot")
	eq(a.counter("big_lot"), 1, "a lorry-load")
	var truck: Item = null
	for it: Item in s.items.values():
		if it.kind == Item.KIND_PALLET_TRUCK:
			truck = it
	check(truck != null, "the start kit has a pallet truck")
	var pallet := s.new_item(Item.KIND_PALLET, {"t": "loose", "p": [1, 0, 1], "r": [0, 0, 0, 1]})
	check(sim.execute(1, "take_truck", {"item": truck.id}).ok, "take the truck")
	check(sim.execute(1, "truck_lift", {"pallet": pallet.id}).ok, "jack up the pallet")
	eq(a.counter("pallet_lift"), 1, "pallet lifted")
	var got := a.evaluate(s, _ctx())
	check("lorry_load" in got and "pump_it_up" in got and "open_for_business" in got, "unlocked: %s" % str(got))


func test_guests_earn_them_from_replicated_state() -> void:
	var sim := fresh_sim(908)
	var host := sim.state
	_kitchen(host)
	sim.active_peers = [1, 2]
	var guest := GameState.new()
	guest.load_dict(host.snapshot())
	host.dirty.clear()
	var a := _ach(2)          # the guest's PC: peer 2
	a.watch(guest)
	for i in 2:
		sim.execute(1, "tea", {"station": "kitchen", "act": "mug"})
	sim.execute(1, "tea", {"station": "kitchen", "act": "boil"})
	_replicate(host, guest)
	host.belt_clock += 20.0
	sim.execute(1, "tea", {"station": "kitchen", "act": "pour"})
	sim.execute(1, "tea", {"station": "kitchen", "act": "milk"})
	_replicate(host, guest)
	eq(guest.lifetime("brews"), 2, "lifetime counters reach the guest")
	var mugs := sim._tea_mugs("kitchen")
	check(sim.execute(2, "pick_up", {"item": mugs[0].id}).ok, "guest picks up a brew")
	_replicate(host, guest)
	check(sim.execute(2, "drink_mug", {}).ok, "and drinks it")
	_replicate(host, guest)
	eq(a.counter("drank_gift"), 1, "the guest saw a colleague's brew go down")
	eq(a.counter("drank_black"), 0, "it had milk in")
	var ctx := {"peers": 2, "player_key": "p2", "flags": {}}
	var got := a.evaluate(guest, ctx)
	for id in ["kettles_on", "proper_teamwork", "two_pairs_of_hands"]:
		check(id in got, "guest unlocked %s: %s" % [id, str(got)])
