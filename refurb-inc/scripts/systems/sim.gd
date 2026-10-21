class_name Sim
extends RefCounted
## Host-only game logic. Every player action arrives here as a command (peer id + args), is validated,
## and mutates GameState. Clients never run this — they receive the resulting state deltas.
## All commands return {"ok": bool, "msg": String} so UI can show feedback.

const CARRY_CAPACITY := 3

var state: GameState
var lots: LotGenerator
var paused := false
var visual_vehicles := false   # true when a level's VehicleDirector animates lorries/vans and calls deliver_* itself
var active_peers: Array = []   # players currently in the session (Game sets this each frame) — counted as on shift


var auto: Automation
var _fixture_sig := 0


func _init(s: GameState) -> void:
	state = s
	lots = LotGenerator.new(s)
	auto = Automation.new(self)


# ================================================================= new game

func new_game(seed_value: int = 0) -> void:
	state.seed_value = seed_value if seed_value != 0 else randi()
	state.rng.seed = state.seed_value
	state.cash = int(Content.econ("start_cash", 2500))
	state.reputation = float(Content.econ("start_reputation", 8))
	state.consumables = Content.start_consumables.duplicate()
	state.parts = {}
	for pid in Content.econ("start_parts", {}):
		state.parts[pid] = int(Content.econ("start_parts", {})[pid])
	state.minute = state.day_start_minute(0)
	state.day_running = true
	state.last_flogit_hour = int(state.minute / 60.0)
	state.day_stats = _blank_day_stats()
	state.add_money(0, "opening", "Opening balance")
	state.building_id = String(Content.econ("start_building", "garage"))
	for f in Content.econ("start_fixtures", []):
		state.fixtures[f.id] = {"type": f.type, "label": f.get("label", ""), "placed": false, "p": [0, 0, 0], "r": 0.0}
		state.mark("fixture", f.id)
	for si in Content.econ("start_items", []):
		var it := state.new_item(si.kind)
		if si.has("p"):
			state.set_loc(it, {"t": "loose", "p": si.p, "r": si.get("r", [0, 0, 0, 1])})
		else:
			state.set_loc(it, {"t": "slot", "slot": si.slot})
	state.totals["run_id"] = "%d-%d" % [state.seed_value, int(Time.get_unix_time_from_system())]
	state.totals["goals"] = {}
	state.totals["days_worked"] = 0
	refresh_candidates()
	_init_seller_cycles()
	refresh_lot_board(true)
	state.say("Day 1. The garage is yours. Check the office PC for lots.", "info")
	state.mark("clock")


func _blank_day_stats() -> Dictionary:
	return {"tested": 0, "repaired": 0, "imaged": 0, "graded": 0, "sold": 0, "shipped": 0, "late": 0,
		"missed": 0, "income": 0, "spend": 0, "revenue": 0, "bought": 0, "rep_start": state.reputation}


# ================================================================= command dispatch

func execute(peer: int, cmd: String, args: Dictionary) -> Dictionary:
	var method := "c_" + cmd
	if not has_method(method):
		return _fail("Unknown command: " + cmd)
	var res: Dictionary = call(method, peer, args)
	if not res.get("ok", false) and res.get("msg", "") != "":
		state.say(res.msg, "warn", peer)
	return res


static func _ok(msg: String = "") -> Dictionary:
	return {"ok": true, "msg": msg}


static func _fail(msg: String) -> Dictionary:
	return {"ok": false, "msg": msg}


static func player_key(peer: int) -> String:
	return "p%d" % peer


# ================================================================= company

const DEFAULT_COMPANY := "Refurb Inc."
const COMPANY_NAME_MAX := 32


static func clean_company_name(raw: String) -> String:
	## Trimmed, single-spaced, printable, at most COMPANY_NAME_MAX characters; blank → Refurb Inc.
	var out := ""
	for ch in raw.strip_edges():
		if ch.unicode_at(0) >= 32 and ch.unicode_at(0) != 127:
			out += ch
	while "  " in out:
		out = out.replace("  ", " ")
	out = out.substr(0, COMPANY_NAME_MAX).strip_edges()
	return out if out != "" else DEFAULT_COMPANY


func c_rename_company(_peer: int, a: Dictionary) -> Dictionary:
	var name := clean_company_name(str(a.get("name", "")))
	if name == state.company_name:
		return _ok()
	var old := state.company_name
	state.company_name = name
	state.mark("company")
	state.say("The business is now called %s." % name, "good")
	BusinessStats.event(state, "milestone", "Renamed from %s to %s" % [old, name])
	return _ok()


# ================================================================= carrying

func c_pick_up(peer: int, a: Dictionary) -> Dictionary:
	var it := state.get_item(int(a.get("item", 0)))
	if it == null or it.loc.get("t") in ["held", "in", "gone", "arm"]:
		return _fail("" if it == null or it.loc.get("t") != "arm" else "The robot arm has got that.")
	if not it.is_carryable():
		return _fail("Too heavy to lift — you'll need a pallet truck." if it.kind == Item.KIND_PALLET else "")
	if pushed_truck(peer) != null:
		return _fail("Let go of the pallet truck first (Q).")
	var held := state.held_by(peer)
	if not held.is_empty():
		# Stacks are one kind and size only (a pile of laptops), and small things (USBs) are carried alone.
		if held[0].slot_kind() != it.slot_kind() or it.weight() == 0:
			return _fail("Hands full — put that down first.")
		var carried := 0
		for h: Item in held:
			carried += h.weight()
		if carried + it.weight() > carry_capacity(peer):
			if it.weight() > 1:
				return _fail("That's a two-hander — put the other one down first.")
			return _fail("You can only carry %d at once." % carry_capacity(peer))
	_leave_slot_side_effects(it)
	state.set_loc(it, {"t": "held", "peer": peer, "i": held.size()})
	if it.kind == Item.KIND_UNIT:
		state.add_xp(player_key(peer), "hauling", int(Content.econ("xp", {}).get("haul", 1)))
		BusinessStats.act(state, peer, "hauled")
	return _ok()


# ================================================================= pallet truck

func pushed_truck(peer: int) -> Item:
	for it: Item in state.items.values():
		if it.kind == Item.KIND_PALLET_TRUCK and it.loc.get("t") == "pushed" and int(it.loc.get("peer", 0)) == peer:
			return it
	return null


func truck_load(truck: Item) -> Item:
	for it: Item in state.items.values():
		if it.kind == Item.KIND_PALLET and it.loc.get("t") == "on_truck" and int(it.loc.get("truck", 0)) == truck.id:
			return it
	return null


func c_take_truck(peer: int, a: Dictionary) -> Dictionary:
	var it := state.get_item(int(a.get("item", 0)))
	if it == null or it.kind != Item.KIND_PALLET_TRUCK:
		return _fail("")
	if it.loc.get("t") != "loose":
		return _fail("Someone's already using that pallet truck.")
	if not state.held_by(peer).is_empty():
		return _fail("Put down what you're carrying first.")
	if pushed_truck(peer) != null:
		return _fail("You've already got a pallet truck.")
	state.set_loc(it, {"t": "pushed", "peer": peer})
	return _ok()


func c_truck_lift(peer: int, a: Dictionary) -> Dictionary:
	var truck := pushed_truck(peer)
	var pallet := state.get_item(int(a.get("pallet", 0)))
	if truck == null or pallet == null or pallet.kind != Item.KIND_PALLET:
		return _fail("")
	if truck_load(truck) != null:
		return _fail("There's already a pallet on the forks.")
	if pallet.loc.get("t") != "loose":
		return _fail("That pallet's busy.")
	# yaw = the pallet's turn relative to the truck (snapped to 90°), so it keeps the way it was lying.
	state.set_loc(pallet, {"t": "on_truck", "truck": truck.id, "yaw": snappedf(float(a.get("yaw", 90.0)), 90.0)})
	state.add_xp(player_key(peer), "hauling", int(Content.econ("xp", {}).get("pallet_move", 3)))
	return _ok()


func c_truck_lower(peer: int, a: Dictionary) -> Dictionary:
	var truck := pushed_truck(peer)
	if truck == null:
		return _fail("")
	var pallet := truck_load(truck)
	if pallet == null:
		return _fail("")
	state.set_loc(pallet, {"t": "loose", "p": a.get("p", [0, 0, 0]), "r": a.get("r", [0, 0, 0, 1])})
	return _ok()


func c_release_truck(peer: int, a: Dictionary) -> Dictionary:
	var truck := pushed_truck(peer)
	if truck == null:
		return _fail("")
	var pallet := truck_load(truck)
	if pallet:
		state.set_loc(pallet, {"t": "loose", "p": a.get("pallet_p", a.get("p", [0, 0, 0])), "r": a.get("pallet_r", [0, 0, 0, 1])})
	state.set_loc(truck, {"t": "loose", "p": a.get("p", [0, 0, 0]), "r": a.get("r", [0, 0, 0, 1])})
	return _ok()


# ================================================================= tea round

const MUG_SLOTS := 6


func _tea_mugs(sid: String) -> Array:
	var out: Array = []
	for i in MUG_SLOTS:
		var it := state.station_slot_item(sid, "mug%d" % i)
		if it and it.kind == Item.KIND_MUG:
			out.append(it)
	return out


func kettle_boiled(st: Dictionary) -> bool:
	var k: Dictionary = st.get("kettle", {})
	return not k.is_empty() and state.belt_clock >= float(k.t0) + float(k.dur)


func c_tea(peer: int, a: Dictionary) -> Dictionary:
	## The kitchen: get mugs out, boil the kettle, pour the round, splash of milk.
	var sid: String = a.get("station", "")
	if not state.stations.has(sid) or state.stations[sid].get("type", "") != "tea":
		return _fail("")
	var st: Dictionary = state.stations[sid]
	var now := state.belt_clock
	match str(a.get("act", "")):
		"mug":
			for i in MUG_SLOTS:
				var slot := "%s:mug%d" % [sid, i]
				if state.item_in_slot(slot) == null:
					var mug := state.new_item(Item.KIND_MUG)
					mug.data = {"c": randi() % TeaMug.COLOURS.size(), "s": randi() % TeaMug.SLOGANS.size(), "fill": 0.0}
					state.set_loc(mug, {"t": "slot", "slot": slot})
					return _ok()
			return _fail("The worktop's full of mugs — pour this round first.")
		"boil":
			var k: Dictionary = st.get("kettle", {})
			if not k.is_empty() and now < float(k.t0) + float(k.dur):
				return _fail("It's already on.")
			st["kettle"] = {"t0": now, "dur": float(Content.econ("kettle_boil_secs", 16.0)), "by": peer}
			add_kwh(float(Content.econ("kettle_kwh", 0.11)))
			state.mark("station", sid)
			return _ok()
		"pour":
			if not kettle_boiled(st):
				return _fail("Boil the kettle first." if st.get("kettle", {}).is_empty() else "Wait for it to boil.")
			if now - (float(st.kettle.t0) + float(st.kettle.dur)) > float(Content.econ("kettle_cold_secs", 300.0)):
				st["kettle"] = {}
				state.mark("station", sid)
				return _fail("The kettle's gone cold — boil it again.")
			var empty := _tea_mugs(sid).filter(func(m): return float(m.data.get("fill", 0.0)) <= 0.05)
			if empty.is_empty():
				return _fail("Get some mugs out of the cupboard first.")
			var ids: Array = []
			for i in empty.size():
				var m: Item = empty[i]
				m.data["fill"] = 0.86
				m.data["brew"] = "tea"
				m.data["milk"] = false
				m.data["drunk"] = false
				m.data["t"] = now + 1.35 + i * 0.95       # when the kettle reaches this mug (the animation)
				m.data["by"] = peer
				m.data.erase("mt")
				state.mark("item", m.id)
				ids.append(m.id)
			st["kettle"] = {}
			st["pour"] = {"t0": now, "ids": ids}
			state.bump_stat("brews", empty.size())
			state.mark("station", sid)
			return _ok()
		"milk":
			var mugs := _tea_mugs(sid).filter(func(m): return float(m.data.get("fill", 0.0)) > 0.05 and str(m.data.get("brew", "")) != "" and not m.data.get("milk", false))
			if mugs.is_empty():
				return _fail("Nothing to put milk in yet.")
			var ids: Array = []
			for i in mugs.size():
				var m: Item = mugs[i]
				m.data["milk"] = true
				m.data["mt"] = maxf(now + 0.9 + i * 0.45, float(m.data.get("t", now)) + 0.6)
				state.mark("item", m.id)
				ids.append(m.id)
			st["milk"] = {"t0": now, "ids": ids}
			state.mark("station", sid)
			return _ok()
	return _fail("")


func c_drink_mug(peer: int, _a: Dictionary) -> Dictionary:
	var held := state.held_by(peer)
	if held.is_empty() or held.back().kind != Item.KIND_MUG:
		return _fail("")
	var mug: Item = held.back()
	if float(mug.data.get("fill", 0.0)) <= 0.05:
		return _fail("It's empty. Pop it in the sink.")
	mug.data["fill"] = 0.0
	mug.data["drunk"] = true
	state.mark("item", mug.id)
	if peer <= -1000 and state.staff.has(-peer - 1000):
		# Staff tea break: a brew someone on the team made them counts for more.
		var by := int(mug.data.get("by", 0))
		StaffMorale.on_tea(state, state.staff[-peer - 1000], by != peer and by != 0, peer_name(state, by) if by != peer and by != 0 else "")
	return _ok()


func _staff_peer_here(peer: int) -> bool:
	return peer <= -1000 and state.staff.has(-peer - 1000) and not StaffMorale.is_away(state.staff[-peer - 1000])


func c_give_mug(peer: int, a: Dictionary) -> Dictionary:
	## Hand a brew to a colleague (a player or a member of staff): it goes straight into their hands.
	var to := int(a.get("to", 0))
	var held := state.held_by(peer)
	if held.is_empty() or held.back().kind != Item.KIND_MUG or to == peer or not (active_peers.has(to) or _staff_peer_here(to)):
		return _fail("")
	var theirs := state.held_by(to)
	if not theirs.is_empty() and theirs[0].kind != Item.KIND_MUG:
		return _fail("Their hands are full.")
	if theirs.size() >= carry_capacity(to):
		return _fail("They can't carry any more.")
	var mug: Item = held.back()
	state.set_loc(mug, {"t": "held", "peer": to, "i": theirs.size()})
	if to <= -1000:
		state.bump_stat("brews_for_staff")
		state.say("%s: \"Oh, you star — thanks!\"" % peer_name(state, to), "good", peer)
	else:
		if peer <= -1000:
			state.bump_stat("staff_brews")
		state.say("%s brought you a brew! [E] to drink it." % peer_name(state, peer), "good", to)
	return _ok()


# ================================================================= staff

func refresh_candidates() -> void:
	var n := int(Staff.data().get("candidates_per_week", 6))
	var next := int(state.totals.get("next_staff_id", 1))
	state.candidates = []
	for i in n:
		state.candidates.append(Staff.make_candidate(state.rng, next + i))
	state.totals["next_staff_id"] = next + n
	state.mark("candidates", 0)


func c_hire(_peer: int, a: Dictionary) -> Dictionary:
	var cid := int(a.get("candidate", 0))
	var pick := {}
	for c in state.candidates:
		if int(c.id) == cid:
			pick = c
	if pick.is_empty():
		return _fail("They've taken another job.")
	if bool(state.challenge().get("rules", {}).get("no_staff", false)) and str(state.challenge().get("status", "")) == "running":
		return _fail("No staff allowed in this challenge — it's all on you.")
	var need := int(Staff.data().get("hire_rep_min", {}).get(pick.role, 0))
	if state.reputation < need:
		return _fail("Office staff want a company with reputation %d+." % need)
	state.candidates.erase(pick)
	employ(pick)
	return _ok()


func employ(pick: Dictionary) -> int:
	## Put a candidate on the books (hiring, or a challenge's starting crew).
	var cid := int(pick.id)
	var d: Dictionary = pick.duplicate(true)
	d["hired"] = state.day_index()
	d["status"] = "Starting their shift"
	StaffMorale.init_card(d)
	state.staff[cid] = d
	# Their skills go through the same system as players' (so they level up by doing the work).
	var key := player_key(StaffView.peer_of(cid))
	var p := state.player(key)
	p["name"] = d.name
	p["staff"] = true
	var xp := {}
	for sk in d.stats:
		xp[sk] = Staff.xp_for_level(int(d.stats[sk]))
	p["xp"] = xp
	state.mark("player", key)
	state.mark("staff", cid)
	state.mark("candidates", 0)
	state.say("%s starts today as %s. Wages £%d a week." % [d.name, StaffView._role_name(d.role).to_lower(), int(d.wage)], "good")
	BusinessStats.event(state, "staff", "Hired %s (%s)" % [d.name, StaffView._role_name(d.role)])
	return cid


func c_fire(peer: int, a: Dictionary) -> Dictionary:
	var sid := int(a.get("staff", 0))
	if not state.staff.has(sid):
		return _fail("")
	var d: Dictionary = state.staff[sid]
	staff_leaves(sid)
	state.say("%s has left the company." % d.name, "info", peer)
	return _ok()


func staff_leaves(sid: int) -> void:
	## Off the books (let go, or resigned): whatever they were carrying is put down where they stood.
	var d: Dictionary = state.staff.get(sid, {})
	if d.is_empty():
		return
	c_drop_all_held(StaffView.peer_of(sid), {"p": d.get("pos", [0, 1, 0])})
	state.staff.erase(sid)
	state.mark("staff", sid)


static func peer_name(st: GameState, peer: int) -> String:
	## Players by their name, staff by theirs.
	if peer <= -1000:
		return str(st.staff.get(-peer - 1000, {}).get("name", "A colleague"))
	return Net.display_name(peer)


func send_mail(from: String, subject: String, body: String) -> void:
	## A message in the office PC's Mail app (kept with the company, so co-op guests see it too).
	var inbox: Array = state.totals.get("inbox", [])
	inbox.append({"day": state.shift_day(), "m": int(state.minute), "from": from, "subject": subject, "body": body})
	if inbox.size() > 40:
		inbox = inbox.slice(inbox.size() - 40)
	state.totals["inbox"] = inbox
	state.mark("company")


# ================================================================= staff morale & training

func _staff_card(a: Dictionary) -> Dictionary:
	return state.staff.get(int(a.get("staff", 0)), {})


func c_pay_rise(_peer: int, a: Dictionary) -> Dictionary:
	## Give a rise: up to what their skills are worth if they've asked, otherwise a goodwill 5% (once a week).
	var d := _staff_card(a)
	if d.is_empty():
		return _fail("")
	var wage := int(d.get("wage", 0))
	var fair := StaffMorale.fair_wage(state, d)
	var new_wage := 0
	if fair > wage:
		new_wage = fair
		StaffMorale.change(state, d, float(StaffMorale.c("pay_rise_boost", 18)), "got the rise they asked for")
	else:
		var cooldown := int(StaffMorale.c("pay_rise_cooldown_days", 5))
		if d.has("last_rise") and state.shift_day() - int(d.last_rise) < cooldown:
			return _fail("%s had a rise %s — wait a week before the next." % [d.name, "today" if state.shift_day() == int(d.last_rise) else "recently"])
		var step := maxi(int(round(wage * float(StaffMorale.c("pay_rise_pct", 0.05)) / 5.0) * 5), int(StaffMorale.c("pay_rise_min", 10)))
		new_wage = wage + step
		StaffMorale.change(state, d, float(StaffMorale.c("pay_rise_small_boost", 8)), "a pay rise out of the blue")
	d["wage"] = new_wage
	d["last_rise"] = state.shift_day()
	d.erase("wants_rise")
	state.mark("staff", int(d.id))
	state.bump_stat("pay_rises")
	state.say("%s's pay is now %s a week (was %s). They're chuffed." % [d.name, GameState.format_money(new_wage), GameState.format_money(wage)], "good")
	BusinessStats.event(state, "staff", "Pay rise for %s: %s a week" % [d.name, GameState.format_money(new_wage)])
	return _ok()


func c_thank_staff(peer: int, a: Dictionary) -> Dictionary:
	## A cheap thank-you, once a day each: it matters more than you'd think.
	var d := _staff_card(a)
	if d.is_empty():
		return _fail("")
	if StaffMorale.is_away(d):
		return _fail("%s is away on a course." % d.name)
	if int(d.get("thanked", -1)) == state.shift_day():
		return _fail("You've already thanked %s today." % d.name)
	d["thanked"] = state.shift_day()
	StaffMorale.change(state, d, float(StaffMorale.c("thank_boost", 6)), "%s said thanks" % peer_name(state, peer))
	state.bump_stat("thanks")
	var lines := ["\"Cheers! Means a lot.\"", "\"Ah, no worries.\"", "\"Thanks — nice to be noticed.\"", "\"Any time, boss.\"", "\"Aw, ta.\""]
	state.say("%s: %s" % [d.name, lines[(int(d.id) + state.shift_day()) % lines.size()]], "good", peer)
	return _ok()


func c_staff_rest(peer: int, _a: Dictionary) -> Dictionary:
	## Staff only: a sit-down when there's no kettle to be had.
	var d: Dictionary = state.staff.get(-peer - 1000, {}) if peer <= -1000 else {}
	if d.is_empty():
		return _fail("")
	StaffMorale.on_rest(state, d)
	return _ok()


func c_send_course(_peer: int, a: Dictionary) -> Dictionary:
	## Off on a training course: away (and still paid) for a few working days, back one level up in that skill.
	var d := _staff_card(a)
	var skill := str(a.get("skill", ""))
	if d.is_empty() or Training.staff_course(skill).is_empty():
		return _fail("")
	if StaffMorale.is_away(d):
		return _fail("%s is already on a course." % d.name)
	var key := player_key(StaffView.peer_of(int(d.id)))
	var level := state.skill_level(key, skill)
	if level >= Training.max_level():
		return _fail("%s is already top level at %s." % [d.name, skill.capitalize()])
	var cost := Training.staff_cost(level)
	if state.cash - cost < int(Content.econ("overdraft_warning", -500)):
		return _fail("The course costs %s." % GameState.format_money(cost))
	var days := Training.staff_days(level)
	var back := Training.return_day(state.shift_day(), days)
	var course := Training.staff_course(skill)
	c_drop_all_held(StaffView.peer_of(int(d.id)), {"p": d.get("pos", [0, 1, 0])})
	d["away"] = {"skill": skill, "course": str(course.get("name", "")), "from": state.shift_day(), "back": back, "level": level + 1}
	d["status"] = "On a course"
	state.mark("staff", int(d.id))
	state.add_money(-cost, "training", "%s — %s (%s)" % [course.get("name", "Course"), d.name, course.get("provider", "")])
	state.bump_stat("courses")
	state.say("%s is off to %s (%s) — back %s morning, %s level %d." % [d.name, course.get("name", ""), course.get("provider", ""),
		GameState.WEEKDAYS[back % 7], skill.capitalize(), level + 1], "good")
	BusinessStats.event(state, "staff", "%s sent on %s" % [d.name, course.get("name", "a course")])
	return _ok()


func _course_returns() -> void:
	## Morning: anyone due back from a course walks in a level up.
	for d: Dictionary in state.staff.values():
		var away: Dictionary = d.get("away", {})
		if away.is_empty() or state.day_index() < int(away.get("back", 0)):
			continue
		var skill := str(away.skill)
		var key := player_key(StaffView.peer_of(int(d.id)))
		var target := mini(int(away.get("level", state.skill_level(key, skill) + 1)), Training.max_level())
		var p := state.player(key)
		p.xp[skill] = maxi(int(p.xp.get(skill, 0)), Staff.xp_for_level(target))
		state.mark("player", key)
		d.erase("away")
		d["status"] = "Back from the course"
		d["since_break"] = 0.0
		StaffMorale.change(state, d, float(StaffMorale.c("course_boost", 10)), "back from a course, full of ideas")
		state.say("%s is back from %s — %s level %d now." % [d.name, str(away.get("course", "the course")), skill.capitalize(), state.skill_level(key, skill)], "good")
		BusinessStats.event(state, "skill", "%s qualified: %s level %d" % [d.name, skill.capitalize(), state.skill_level(key, skill)])


func c_buy_course(peer: int, a: Dictionary) -> Dictionary:
	## Online course for a player: instant XP in one skill (each repeat teaches a bit less).
	var skill := str(a.get("skill", ""))
	var course := Training.online_course(skill)
	if course.is_empty() or peer <= -1000:
		return _fail("")
	var key := player_key(peer)
	if state.skill_level(key, skill) >= Training.max_level():
		return _fail("You're already top level at %s." % skill.capitalize())
	var price := int(course.get("price", 100))
	if state.cash - price < int(Content.econ("overdraft_warning", -500)):
		return _fail("That course costs %s." % GameState.format_money(price))
	var p := state.player(key)
	var taken: Dictionary = p.get("courses", {})
	var n := int(taken.get(skill, 0))
	var xp := Training.online_xp(course, n)
	taken[skill] = n + 1
	p["courses"] = taken
	state.add_money(-price, "training", "%s — %s" % [course.get("name", "Online course"), p.get("name", key)])
	state.add_xp(key, skill, xp)
	state.bump_stat("online_courses")
	state.say("Course complete: %s. +%d %s XP." % [course.get("name", ""), xp, skill.capitalize()], "good", peer)
	return _ok()


func c_staff_role(_peer: int, a: Dictionary) -> Dictionary:
	var sid := int(a.get("staff", 0))
	var role := str(a.get("role", ""))
	if not state.staff.has(sid) or not Staff.data().roles.any(func(r): return r.id == role):
		return _fail("")
	state.staff[sid]["role"] = role
	state.mark("staff", sid)
	return _ok()


func weekly_wages() -> int:
	## Friday: everyone on the books is paid (people away on a course too). Rises aren't automatic any more —
	## staff whose skills outgrow their pay ask for one (StaffMorale) and you give it in the Staff app.
	var total := 0
	for d in state.staff.values():
		total += int(d.get("wage", 0))
	return total


func carry_capacity(peer: int) -> int:
	## Hauling skill sets how many carry slots you have (2 at level 1 → 5 at level 8+; see skills.json).
	if peer == 0:
		return CARRY_CAPACITY
	return int(Content.skill_table("carry", state.skill_level(player_key(peer), "hauling"), CARRY_CAPACITY))


func c_place(peer: int, a: Dictionary) -> Dictionary:
	var slot_id: String = a.get("slot", "")
	var held := state.held_by(peer)
	if held.is_empty():
		return _fail("")
	var it: Item = held.back()
	if state.item_in_slot(slot_id) != null:
		return _fail("That spot is taken.")
	if not state.slot_accepts(slot_id, it.slot_kind()):
		if it.is_large() and state.slot_accepts(slot_id, it.kind):
			return _fail("Too big for this shelf — big kit needs heavy-duty racking.")
		return _fail("That doesn't go there.")
	var station_id := state.slot_station(slot_id)
	var check := _station_accepts(station_id, slot_id, it)
	if not check.ok:
		return check
	if slot_id.begins_with("i") and it.kind == Item.KIND_UNIT:
		_adapt_pallet(int(slot_id.substr(1).get_slice(":", 0)), it)
	state.set_loc(it, {"t": "slot", "slot": slot_id})
	_on_entered_slot(it, slot_id, peer)
	return _ok()


func _adapt_pallet(pallet_id: int, unit_it: Item) -> void:
	## An empty pallet takes the stacking grid of the first unit put on it (laptops 3×2, 2U servers 2×1…).
	var pallet := state.get_item(pallet_id)
	if pallet == null or pallet.data.has("cell"):
		return
	for it: Item in state.items.values():
		if it.loc.get("t") == "slot" and str(it.loc.get("slot", "")).begins_with("i%d:" % pallet_id):
			return
	var lay := pallet_layout(unit_it.unit.model_id)
	pallet.data["cell"] = lay.cell
	pallet.data["grid"] = lay.grid
	state.mark("item", pallet_id)


func c_drop(peer: int, a: Dictionary) -> Dictionary:
	var held := state.held_by(peer)
	if held.is_empty():
		return _fail("")
	var it: Item = held.back()
	var p: Array = a.get("p", [0, 0, 0])
	var r: Array = a.get("r", [0, 0, 0, 1])
	state.set_loc(it, {"t": "loose", "p": p, "r": r})
	return _ok()


func c_settle(_peer: int, a: Dictionary) -> Dictionary:
	## Host physics reports a loose item came to rest (keeps saves/new joiners accurate).
	var it := state.get_item(int(a.get("item", 0)))
	if it and it.loc.get("t") == "loose":
		state.set_loc(it, {"t": "loose", "p": a.get("p", [0, 0, 0]), "r": a.get("r", [0, 0, 0, 1])})
	return _ok()


func c_drop_all_held(peer: int, a: Dictionary) -> Dictionary:
	## Player disconnected / left: drop everything where they stood (and let go of any pallet truck).
	var p: Array = a.get("p", [0, 1, 0])
	if pushed_truck(peer) != null:
		c_release_truck(peer, {"p": [p[0], 0.02, p[2]], "pallet_p": [p[0], 0.02, float(p[2]) + 1.3]})
	var k := 0
	for it: Item in state.held_by(peer):
		state.set_loc(it, {"t": "loose", "p": [p[0], p[1] + 0.1 * k, p[2]], "r": [0, 0, 0, 1]})
		k += 1
	return _ok()


func _leave_slot_side_effects(it: Item) -> void:
	if it.loc.get("t") != "slot":
		return
	var sid := state.slot_station(it.loc.slot)
	if sid == "" or not state.stations.has(sid):
		return
	var st: Dictionary = state.stations[sid]
	if not st.job.is_empty():
		var job_item := int(st.job.get("unit", 0))
		var job_usb := int(st.job.get("usb", 0))
		if it.id == job_item or it.id == job_usb:
			st.job = {}
			st.msg = "Job cancelled — item removed."
			state.mark("station", sid)
			state.say("Job cancelled at %s." % sid.capitalize(), "warn")


# ================================================================= station rules

func _station_accepts(station_id: String, slot_id: String, it: Item) -> Dictionary:
	if station_id == "" or not state.stations.has(station_id):
		return _ok()
	var t: String = state.stations[station_id].type
	if it.kind != Item.KIND_UNIT:
		return _ok()
	var u := it.unit
	match t:
		"pack":
			if u.order_id == 0:
				return _fail("Not sold yet — only sold units get packed.")
		"repair":
			if not u.tested:
				return _fail("Test it first so you know what's wrong.")
		"grade":
			if not u.tested:
				return _fail("Test it before grading.")
	return _ok()


func _on_entered_slot(it: Item, slot_id: String, peer: int) -> void:
	var sid := state.slot_station(slot_id)
	if sid != "" and state.stations.get(sid, {}).get("type", "") == "recycle":
		var scrap := int(Content.econ("scrap_value", {}).get(it.unit.hw_type() if it.unit else "laptop", 5))
		var name := it.display_name()
		state.set_loc(it, {"t": "gone"})
		state.add_money(scrap, "scrap", "Recycled: %s" % name)
		state.bump_stat("recycled")
		state.say("Recycled %s — £%d scrap." % [name, scrap], "info", peer)
		return
	if it.kind == Item.KIND_MUG and slot_id.ends_with(":sink"):
		var poured := float(it.data.get("fill", 0.0)) > 0.05
		state.set_loc(it, {"t": "gone"})
		if state.stations.has(sid):
			state.stations[sid]["wash"] = state.belt_clock
			state.mark("station", sid)
		state.say("Poured away and washed up." if poured else "Washed up. Your colleagues salute you.", "info", peer)
		return
	if sid != "" and state.stations.has(sid):
		var t: String = state.stations[sid].get("type", "")
		if t in ["rack_test", "rack_image", "robot_repair", "robot_pack"]:
			try_start_machine(sid, slot_id.get_slice(":", 1))
		else:
			try_start_bench(sid, peer)


# ================================================================= machines (racks + robots)

func machine_minutes(sid: String, base: float, u: UnitData) -> float:
	var auto_def := Automation.def_of(state.fixtures.get(sid, {}).get("type", ""))
	return base * (float(u.model().get("test_mult", 1.0)) if u else 1.0) / float(auto_def.get("speed", 1.0))


func try_start_machine(sid: String, bay: String) -> void:
	## Network test/imaging racks and robots work on their own (no USB, no player): one job per bay.
	var st: Dictionary = state.stations.get(sid, {})
	var it := state.station_slot_item(sid, bay)
	if it == null or st.is_empty():
		return
	if not st.has("jobs"):
		st["jobs"] = {}
	if st.jobs.has(bay):
		return
	var t: String = st.type
	var u := it.unit
	var job := ""
	var dur := 0.0
	match t:
		"rack_test":
			if u and not u.tested:
				job = "test"
				dur = machine_minutes(sid, float(Content.econ("test_minutes", 30)), u)
		"rack_image":
			if u and u.tested and not u.has_open_faults() and not u.imaged:
				job = "image"
				dur = machine_minutes(sid, float(Content.econ("image_minutes", 45)), u)
		"robot_repair":
			if u and u.tested and u.has_open_faults():
				var n := _robot_fixable(sid, u).size()
				if n > 0:
					job = "repair"
					dur = machine_minutes(sid, 6.0 * n, null)
		"robot_pack":
			if u and u.order_id != 0 and state.station_slot_item(sid, "out") == null:
				job = "pack"
				dur = machine_minutes(sid, 4.0, null)
	if job == "":
		return
	st.jobs[bay] = {"kind": job, "unit": it.id, "start": state.minute, "dur": dur}
	state.mark("station", sid)


func _robot_fixable(sid: String, u: UnitData) -> Array:
	var max_tier := int(Automation.def_of(state.fixtures.get(sid, {}).get("type", "")).get("max_tier", 1))
	var out: Array = []
	for f in u.open_faults():
		if Content.fault(f.id).get("unrepairable", false) or repair_tier(u, f) > max_tier:
			continue
		var pid := Valuation.repair_part_for(u, f)
		if int(state.parts.get(pid, 0)) > 0 or Content.part(pid).get("reusable", false):
			out.append(f)
	return out


func _process_machines() -> void:
	for sid in state.stations:
		var st: Dictionary = state.stations[sid]
		var jobs: Dictionary = st.get("jobs", {})
		if jobs.is_empty():
			continue
		for bay in jobs.keys():
			var job: Dictionary = jobs[bay]
			if state.minute < float(job.start) + float(job.dur):
				continue
			jobs.erase(bay)
			state.mark("station", sid)
			var it := state.get_item(int(job.unit))
			if it == null or it.unit == null:
				continue
			var u := it.unit
			if job.kind != "pack":
				BusinessStats.on_auto(state, {"test": "rack_test", "image": "rack_image", "repair": "robot_repair"}.get(job.kind, str(job.kind)), sid)
			match job.kind:
				"test":
					_complete_test(it, 0)
				"image":
					u.imaged = true
					var wipe := u.wipe_info()
					u.log_event(state.minute, ("%s on the network rack" % wipe.get("title", "Wiped")) if not wipe.is_empty() else "Wiped and imaged on the network rack")
					state.bump_stat("imaged")
					BusinessStats.on_image(state, u, 0)
				"repair":
					for f in _robot_fixable(sid, u):
						var pid := Valuation.repair_part_for(u, f)
						f.fixed = true
						var pdef := Content.part(pid)
						if not pdef.get("reusable", false):
							state.parts[pid] = int(state.parts.get(pid, 0)) - 1
							if state.parts[pid] <= 0:
								state.parts.erase(pid)
						BusinessStats.on_repair(state, u, f.id, pid, 0 if pdef.get("reusable", false) else int(pdef.get("price", 0)), 0)
						for c in u.cosmetic.duplicate():
							if Content.condition(c).get("fixable_by_fault", "") == f.id:
								u.cosmetic.erase(c)
						if f.id == "battery_worn":
							u.battery_pct = 100
						u.log_event(state.minute, "Repaired by robot: %s" % LotGenerator.fault_text(f))
						state.bump_stat("repaired")
					state.mark("company")
				"pack":
					_robot_pack(sid, it)
					if it.loc.get("t") == "in":
						BusinessStats.on_auto(state, "robot_pack", sid)
			state.mark("item", it.id)
			try_start_machine(sid, bay)


func _robot_pack(sid: String, it: Item) -> void:
	var u := it.unit
	if u.order_id == 0 or not state.orders.has(u.order_id) or state.station_slot_item(sid, "out") != null:
		return
	var box := "big_boxes" if u.is_large() else "boxes"
	for need in [box, "labels", "tape"]:
		if int(state.consumables.get(need, 0)) <= 0:
			state.stations[sid].msg = "Out of %s" % need
			return
	for need in [box, "labels", "tape"]:
		state.consumables[need] = int(state.consumables[need]) - 1
	var order: Dictionary = state.orders[u.order_id]
	state.set_loc(it, {"t": "in", "item": 0})
	var pkg := state.new_item(Item.KIND_PACKAGE)
	pkg.data = {"order_id": order.id, "unit": it.id, "buyer": order.buyer, "model": order.model, "packer": 0}
	if u.is_large():
		var vis: Dictionary = u.model().get("visual", {})
		pkg.data["large"] = true
		pkg.data["carry"] = int(u.model().get("carry", 3))
		pkg.data["size"] = [snappedf(float(vis.get("w", 0.5)) + 0.1, 0.01), snappedf(float(vis.get("h", 0.2)) + 0.08, 0.01), snappedf(float(vis.get("d", 0.7)) + 0.1, 0.01)]
	it.loc = {"t": "in", "item": pkg.id}
	state.mark("item", it.id)
	state.set_loc(pkg, {"t": "slot", "slot": "%s:out" % sid})
	order.status = "packed"
	order["package"] = pkg.id
	state.mark("order", order.id)
	state.mark("company")
	BusinessStats.act(state, 0, "packed")


func c_set_sorter_rule(_peer: int, a: Dictionary) -> Dictionary:
	var fid: String = a.get("sorter", "")
	var out: String = a.get("out", "")
	var rule: String = a.get("rule", "")
	if not state.fixtures.has(fid) or not out in ["left", "right", "out"] or not rule in Automation.SORTER_RULES:
		return _fail("")
	if not state.stations.has(fid):
		state.register_station(fid, "sorter")
	var rules: Dictionary = auto.sorter_rules(fid).duplicate()
	rules[out] = rule
	state.stations[fid]["rules"] = rules
	state.mark("station", fid)
	return _ok()


func c_set_belt_mode(_peer: int, a: Dictionary) -> Dictionary:
	## Splitter: alternate / overflow. Stop gate: open / hold. (Set in-world with [E] on the piece.)
	var fid: String = a.get("id", "")
	var mode: String = a.get("mode", "")
	var dev := str(Automation.def_of(str(state.fixtures.get(fid, {}).get("type", ""))).get("device", ""))
	if not Automation.BELT_MODES.has(dev) or not mode in Automation.BELT_MODES[dev]:
		return _fail("")
	if not state.stations.has(fid):
		state.register_station(fid, dev)
	state.stations[fid]["mode"] = mode
	state.mark("station", fid)
	return _ok()


func c_upgrade_belt(peer: int, a: Dictionary) -> Dictionary:
	## Speed up a belt piece one tier (Standard → Fast → Express) — the whole run at that speed if affordable.
	var plan := Automation.upgrade_plan(state, str(a.get("id", "")))
	if plan.is_empty():
		return _fail("Only conveyors can be sped up.")
	if plan.get("max", false):
		return _fail("Already at top speed (%s)." % plan.name)
	if plan.short:
		return _fail("Upgrading to %s costs %s a piece." % [plan.name, GameState.format_money(int(plan.step))])
	for id in plan.ids:
		state.fixtures[id]["tier"] = int(plan.to)
		state.mark("fixture", id)
	var n: int = plan.ids.size()
	state.add_money(-int(plan.cost), "equipment", "Conveyor upgrade to %s (%d piece%s)" % [plan.name, n, "" if n == 1 else "s"])
	state.say("%d conveyor piece%s upgraded to %s — %.1f m/s." % [n, "" if n == 1 else "s", plan.name, float(plan.speed)], "good", peer)
	return {"ok": true, "msg": "", "ids": plan.ids}


func try_start_bench(station_id: String, peer: int = 0) -> void:
	var st: Dictionary = state.stations.get(station_id, {})
	if st.get("type") != "bench" or not st.job.is_empty():
		return
	var unit_it := state.station_slot_item(station_id, "unit")
	var usb_it := state.station_slot_item(station_id, "usb")
	if unit_it == null or usb_it == null or unit_it.kind != Item.KIND_UNIT:
		st.msg = "Place a unit and plug in a USB." if unit_it == null else "Plug in a test or imaging USB."
		state.mark("station", station_id)
		return
	var u := unit_it.unit
	var job := ""
	var dur := 0.0
	var speed := _skill_speed(peer, "testing")
	if usb_it.kind == Item.KIND_USB_TEST:
		if u.tested:
			st.msg = "Already tested. Swap to the PaneOS USB to wipe & image." if u.needs_wipe() else "Already tested — nothing to wipe. Take it to grading."
			state.mark("station", station_id)
			return
		job = "test"
		dur = float(Content.econ("test_minutes", 30)) * float(u.model().get("test_mult", 1.0)) / speed
	elif usb_it.kind == Item.KIND_USB_IMAGE:
		if not u.tested:
			st.msg = "Untested unit — run DiagnostiKit first."
			state.mark("station", station_id)
			return
		if u.has_open_faults():
			st.msg = "Faults outstanding — repair before imaging."
			state.mark("station", station_id)
			return
		if u.imaged:
			st.msg = "Already imaged. Take it to grading." if u.needs_wipe() else "No data to wipe on this. Take it to grading."
			state.mark("station", station_id)
			return
		job = "image"
		dur = float(Content.econ("image_minutes", 45)) * float(u.model().get("test_mult", 1.0)) / _skill_speed(peer, "imaging")
	if job == "":
		return
	st.job = {"kind": job, "unit": unit_it.id, "usb": usb_it.id, "start": state.minute, "dur": dur, "by": peer}
	st.msg = ""
	state.mark("station", station_id)


func _skill_speed(peer: int, skill: String) -> float:
	if peer == 0:
		return 1.0     # machines / staff run at standard speed
	var key := player_key(peer)
	var sp := state.skill_speed(key, skill)
	if peer <= -1000:
		# AI staff: skill matters even more (×0.41 at level 1 … ×2.3 at level 10), and so does morale (±20%).
		sp = pow(sp, 1.5) * StaffMorale.speed_mult(state.staff.get(-peer - 1000, {}))
	if (skill == "testing" and state.has_perk(key, "bench_whisper")) or (skill == "imaging" and state.has_perk(key, "fast_wipe")):
		sp *= 1.15
	return sp


func _process_jobs() -> void:
	for sid in state.stations:
		var st: Dictionary = state.stations[sid]
		if st.get("job", {}).is_empty() or st.get("type", "") == "arm":
			continue      # robot arms run their own jobs on the automation clock
		if state.minute < float(st.job.start) + float(st.job.dur):
			continue
		var it := state.get_item(int(st.job.unit))
		var by := int(st.job.get("by", 0))
		var kind: String = st.job.kind
		BusinessStats.on_job(state, sid, kind, float(st.job.dur))
		st.job = {}
		if it == null or it.unit == null:
			state.mark("station", sid)
			continue
		if kind == "test":
			_complete_test(it, by)
			st.msg = "PASS" if not it.unit.has_open_faults() else "FAULTS FOUND"
			if by != 0 and not it.unit.has_open_faults() and not it.unit.imaged and state.has_perk(player_key(by), "auto_chain"):
				_auto_chain(sid, by)
		elif kind == "image":
			it.unit.imaged = true
			if by != 0 and state.has_perk(player_key(by), "data_cert"):
				it.unit.data_cert = true
			var wipe := it.unit.wipe_info()
			if wipe.is_empty():
				it.unit.log_event(state.minute, "Wiped (%s) and imaged with %s" % [Content.os_info.get("wipe_standard", ""), Content.os_info.get("name", "PaneOS")])
			else:
				it.unit.log_event(state.minute, "%s (%s)" % [wipe.get("title", "Secure wipe"), Content.os_info.get("wipe_standard", "")])
			state.bump_stat("imaged")
			BusinessStats.on_image(state, it.unit, by)
			if by != 0:
				state.add_xp(player_key(by), "imaging", int(Content.econ("xp", {}).get("image", 4)))
			st.msg = "IMAGED"
			state.say("%s %s — ready to grade." % [it.unit.display_name(), "imaged" if wipe.is_empty() else "wiped and reset"], "good")
		state.mark("item", it.id)
		state.mark("station", sid)


func _auto_chain(sid: String, by: int) -> void:
	## Testing perk: swap the bench's USB for a parked PaneOS stick and start imaging straight away.
	var usb := state.station_slot_item(sid, "usb")
	for park in ["park_a", "park_b"]:
		var parked := state.station_slot_item(sid, park)
		if parked and parked.kind == Item.KIND_USB_IMAGE:
			if usb:
				state.set_loc(usb, {"t": "slot", "slot": "%s:%s" % [sid, park]})
				state.set_loc(parked, {"t": "slot", "slot": "%s:usb" % sid})
			else:
				state.set_loc(parked, {"t": "slot", "slot": "%s:usb" % sid})
			try_start_bench(sid, by)
			return


func _complete_test(it: Item, by: int) -> void:
	var u := it.unit
	u.tested = true
	var lines: Array = []
	for f in u.faults:
		lines.append(LotGenerator.fault_text(f))
	u.test_report = {
		"at": state.minute, "tool": "%s %s" % [Content.diag_info.get("name", ""), Content.diag_info.get("version", "")],
		"serial": u.serial, "model": u.display_name(), "cpu": u.cpu_name(), "ram_gb": u.ram_gb,
		"storage_gb": u.storage_gb, "battery_pct": u.battery_pct if u.has_battery() else -1, "faults": lines, "pass": lines.is_empty(),
		"type": u.hw_type(), "spec": u.spec_line(),
	}
	u.log_event(state.minute, "Tested: " + ("PASS" if lines.is_empty() else "%d fault(s)" % lines.size()))
	if not u.needs_wipe() and not u.imaged:
		# Monitors and docks hold no data: no wipe step, straight to grading once any faults are fixed.
		u.imaged = true
		u.log_event(state.minute, "No data to wipe")
	state.bump_stat("tested")
	BusinessStats.on_test(state, u, by)
	if by != 0:
		state.add_xp(player_key(by), "testing", int(Content.econ("xp", {}).get("test", 6)))
	if lines.is_empty():
		state.say("%s — PASS%s" % [u.display_name(), "" if u.needs_wipe() else " (no data to wipe — ready to grade)"], "good")
	else:
		state.say("%s — %s" % [u.display_name(), lines[0]], "warn")


# ================================================================= repair

func repair_preview(peer: int, it: Item) -> Dictionary:
	## What the next hold-to-repair would do. Used by the hover text and validated again on commit.
	if it == null or it.unit == null:
		return {"can": false, "why": "No unit on the bench."}
	var u := it.unit
	if not u.tested:
		return {"can": false, "why": "Untested — faults unknown."}
	var open := u.open_faults()
	if open.is_empty():
		return {"can": false, "why": "No faults to repair.", "done": true}
	var level := state.skill_level(player_key(peer), "repair")
	var tiers: Dictionary = Content.skills_data.get("repair_tiers", {"1": 1, "2": 4, "3": 7})
	var blocked_reason := ""
	var unfixable := ""
	for f in open:
		var fdef := Content.fault(f.id)
		if fdef.get("unrepairable", false):
			# e.g. an activation lock: no part or skill clears it — the unit can only go as spares/repairs.
			unfixable = "%s: can't be fixed — sell it as spares/repairs." % LotGenerator.fault_text(f)
			continue
		var tier := repair_tier(u, f)
		var need := int(tiers.get(str(tier), tier))
		var part_id := Valuation.repair_part_for(u, f)
		var pdef := Content.part(part_id)
		if level < need:
			if blocked_reason == "":
				blocked_reason = "%s is a level %d repair: needs Repair skill %d (you're %d)." % [LotGenerator.fault_text(f), tier, need, level]
			continue
		var have := int(state.parts.get(part_id, 0))
		if have <= 0:
			if blocked_reason == "":
				blocked_reason = "Need part: %s (0 in bins)." % pdef.get("name", part_id)
			continue
		var secs := float(pdef.get("repair_secs", 4.0)) / _skill_speed(peer, "repair")
		if tier == 3 and state.has_perk(player_key(peer), "board_hands"):
			secs *= 0.5
		return {"can": true, "fault": f.id, "part": part_id, "part_name": pdef.get("name", part_id),
			"have": have, "secs": secs, "text": LotGenerator.fault_text(f)}
	return {"can": false, "why": unfixable if unfixable != "" else blocked_reason, "unfixable": unfixable != ""}


static func repair_tier(u: UnitData, f: Dictionary) -> int:
	## Repair level a fault needs: the fault's own level, raised by a fiddlier part (glued-in tablet/phone batteries).
	var tier := int(Content.fault(f.get("id", "")).get("repair_level", 1))
	return maxi(tier, int(Content.part(Valuation.repair_part_for(u, f)).get("repair_level", 1)))


func c_repair(peer: int, a: Dictionary) -> Dictionary:
	var sid: String = a.get("station", "")
	if state.stations.get(sid, {}).get("type") != "repair":
		return _fail("")
	var it := state.station_slot_item(sid, "unit")
	var pv := repair_preview(peer, it)
	if not pv.get("can", false):
		return _fail(pv.get("why", ""))
	var u := it.unit
	for f in u.faults:
		if f.id == pv.fault and not f.fixed:
			f.fixed = true
			var pdef := Content.part(pv.part)
			BusinessStats.on_repair(state, u, f.id, pv.part, 0 if pdef.get("reusable", false) else int(pdef.get("price", 0)), peer)
			var salvaged := state.has_perk(player_key(peer), "salvage") and state.rng.randf() < 0.25
			if salvaged:
				state.say("Salvage: careful work — the %s wasn't used up." % pv.part_name, "good", peer)
			if not pdef.get("reusable", false) and not salvaged:
				state.parts[pv.part] = int(state.parts.get(pv.part, 0)) - 1
				if state.parts[pv.part] <= 0:
					state.parts.erase(pv.part)
			# Visible damage linked to this fault disappears with the repair.
			for c in u.cosmetic.duplicate():
				if Content.condition(c).get("fixable_by_fault", "") == f.id:
					u.cosmetic.erase(c)
			if f.id == "battery_worn":
				u.battery_pct = 100
			u.log_event(state.minute, "Repaired: %s (%s)" % [pv.text, pv.part_name])
			var lvl := int(Content.fault(f.id).get("repair_level", 1))
			state.add_xp(player_key(peer), "repair", int(Content.econ("xp", {}).get("repair_l%d" % lvl, 12)))
			state.bump_stat("repaired")
			state.mark("item", it.id)
			state.mark("company")
			var left := u.open_faults().size()
			state.say("Fixed: %s.%s" % [pv.text, "" if left == 0 else " %d more to go." % left], "good", peer)
			return _ok()
	return _fail("Nothing to repair.")


# ================================================================= grading

func c_grade(peer: int, a: Dictionary) -> Dictionary:
	var sid: String = a.get("station", "")
	if state.stations.get(sid, {}).get("type") != "grade":
		return _fail("")
	var it := state.station_slot_item(sid, "unit")
	if it == null or it.unit == null:
		return _fail("Nothing on the grading desk.")
	var u := it.unit
	if u.grade != "":
		return _fail("Already graded %s." % UnitData.grade_label(u.grade))
	if not u.tested:
		return _fail("Test it before grading.")
	if not u.has_open_faults() and not u.imaged:
		return _fail("Wipe & image it first.")
	var gkey := player_key(peer)
	if state.has_perk(gkey, "touch_up"):
		# Light refurb before grading: wipe off the cosmetic stuff a cloth and a bit of IPA can fix.
		var cleaned := 0
		for c in ["light_scuffs_lid", "sticker_residue", "asset_tag", "dusty_intake", "shiny_keys"]:
			if c in u.cosmetic:
				u.cosmetic.erase(c)
				cleaned += 1
		if cleaned > 0:
			u.log_event(state.minute, "Touched up: %d light cosmetic issue(s) cleaned" % cleaned)
	if state.has_perk(gkey, "photo_pro"):
		u.list_quality = 1.05
	u.grade = Valuation.grade_for(u)
	u.list_price = int(round(Valuation.market_value(u, u.grade) * u.list_quality))
	BusinessStats.on_grade(state, u, peer)
	u.log_event(state.minute, "Graded %s — suggested price £%d" % [UnitData.grade_label(u.grade), u.list_price])
	state.bump_stat("graded")
	state.add_xp(player_key(peer), "grading", int(Content.econ("xp", {}).get("grade", 3)))
	if Content.econ("flogit_auto_list_on_grade", true):
		# Straight onto Flogit at the guide price; reprice or withdraw any time in the Flogit app.
		BusinessStats.on_listed(state, u, peer)
		u.listed = true
		u.log_event(state.minute, "Listed on Flogit at £%d" % u.list_price)
	state.mark("item", it.id)
	if u.listed:
		state.say("Grade %s — %s. Listed on Flogit at £%d (change it in the Flogit app)." % [UnitData.grade_label(u.grade), u.display_name(), u.list_price], "good", peer)
	else:
		state.say("Grade %s — %s. Suggested £%d." % [UnitData.grade_label(u.grade), u.display_name(), u.list_price], "good", peer)
	return _ok()


# ================================================================= sales / listings

func c_set_price(_peer: int, a: Dictionary) -> Dictionary:
	var it := state.get_item(int(a.get("item", 0)))
	if it == null or it.unit == null or it.unit.grade == "":
		return _fail("Grade it before pricing.")
	it.unit.list_price = clampi(int(a.get("price", it.unit.list_price)), 1, 99999)
	state.mark("item", it.id)
	return _ok()


func c_list(peer: int, a: Dictionary) -> Dictionary:
	var it := state.get_item(int(a.get("item", 0)))
	if it == null or it.unit == null:
		return _fail("")
	var u := it.unit
	var want := bool(a.get("listed", true))
	if want and u.grade == "":
		return _fail("Only graded units can be listed.")
	if u.order_id != 0:
		return _fail("Already sold.")
	if want and not u.listed:
		BusinessStats.on_listed(state, u, peer)
	u.listed = want
	u.log_event(state.minute, ("Listed on Flogit at £%d" % u.list_price) if want else "Listing withdrawn")
	state.mark("item", it.id)
	return _ok()


func c_list_all(peer: int, _a: Dictionary) -> Dictionary:
	var n := 0
	for it: Item in state.units_in_stock():
		if it.unit.grade != "" and not it.unit.listed and it.unit.order_id == 0:
			c_list(peer, {"item": it.id, "listed": true})
			n += 1
	state.say("Listed %d unit(s) on Flogit." % n, "info", peer)
	return _ok()


static func sale_chance_per_hour(st: GameState, u: UnitData) -> float:
	## Chance a Flogit buyer orders this listing in the next working hour: reputation-driven traffic,
	## scaled hard by price vs guide (cheaper sells faster, pricier slower).
	var base := float(Content.econ("flogit_base_rate_per_hour", 0.09)) + st.reputation * float(Content.econ("flogit_rep_rate_bonus", 0.003))
	base *= 1.0 + float(Content.skills_data.get("sales_traffic_per_level", 0.04)) * (st.best_skill_level("sales") - 1)
	if st.team_has_perk("repeat_buyers"):
		base *= 1.1
	# Salespeople in the office bring more buyers in.
	for d in st.staff.values():
		if str(d.get("role", "")) == "salesperson" and not d.has("away"):
			base *= 1.0 + 0.012 * st.skill_level(player_key(StaffView.peer_of(int(d.id))), "sales")
	var market := float(Valuation.market_value(u, u.grade)) * u.list_quality
	var ratio := market / maxf(float(u.list_price), 1.0)
	var p := base * clampf(pow(ratio, float(Content.econ("flogit_price_elasticity", 3.0))), 0.05, 4.0)
	if u.grade == "S":
		p *= float(Content.econ("flogit_spares_rate_mult", 1.5))
	return clampf(p, 0.0, 0.95)


func flogit_hourly() -> void:
	var listed: Array = state.units_in_stock().filter(func(i): return i.unit.listed and i.unit.order_id == 0)
	if listed.is_empty():
		return
	# Your very first sensibly priced listing always finds a buyer within the hour (teaches the loop).
	if int(state.totals.get("units_sold", 0)) == 0 and state.orders.is_empty():
		for it: Item in listed:
			var ratio := float(it.unit.list_price) / maxf(float(Valuation.market_value(it.unit, it.unit.grade)), 1.0)
			if ratio <= float(Content.econ("flogit_first_sale_max_ratio", 1.15)):
				_create_order(it, "flogit")
				return
	for it: Item in listed:
		if it.unit.order_id != 0:
			continue
		if state.rng.randf() < sale_chance_per_hour(state, it.unit):
			var order := _create_order(it, "flogit")
			if state.team_has_perk("trade_buyers") and state.rng.randf() < 0.2:
				# A trade buyer takes a second of the same model if one is listed.
				for other: Item in listed:
					if other != it and other.unit.order_id == 0 and other.unit.model_id == it.unit.model_id:
						var o2 := _create_order(other, "flogit")
						o2.buyer = order.buyer
						break


func _create_order(it: Item, channel: String) -> Dictionary:
	var u := it.unit
	var deadline_h := float(Content.econ("dispatch_deadline_hours", 24))
	var order := {
		"id": state.make_id(), "channel": channel, "unit": it.id, "model": u.display_name(),
		"grade": u.grade, "price": u.list_price, "buyer": _buyer_name(), "message": _pick(Content.names.get("buyer_messages", [""])),
		"placed": state.minute, "due": _add_working_minutes(state.minute, deadline_h * 60.0),
		"status": "to_pack", "late_flagged": false,
	}
	state.orders[order.id] = order
	u.listed = false
	u.order_id = order.id
	BusinessStats.on_order(state, u)
	u.log_event(state.minute, "Sold on Flogit to %s for £%d" % [order.buyer, order.price])
	state.bump_stat("sold")
	state.mark("order", order.id)
	state.mark("item", it.id)
	state.say("Flogit sale! %s (%s) — £%d to %s. Pack it before %s." % [order.model, UnitData.grade_label(order.grade), order.price, order.buyer, GameState.format_minute(order.due)], "good")
	return order


func _add_working_minutes(from: float, mins: float) -> float:
	## Deadlines count working hours only (09:00–17:00, Mon–Fri).
	var t := from
	var left := mins
	var guard := 0
	while left > 0.0 and guard < 100:
		guard += 1
		var day := int(floor(t / GameState.MIN_PER_DAY))
		if day % 7 >= 5:
			t = state.day_start_minute(day + (7 - day % 7))
			continue
		var start := state.day_start_minute(day)
		var end := state.day_end_minute(day)
		if t < start:
			t = start
		if t >= end:
			t = state.day_start_minute(day + 1)
			continue
		var step := minf(left, end - t)
		t += step
		left -= step
	return t


func _buyer_name() -> String:
	var n: Dictionary = Content.names
	var first: String = _pick(n.get("first", ["Sam"]))
	var town: String = _pick(n.get("towns", ["Leeds"]))
	var handle: String = _pick(n.get("flogit_handles", ["{first}{n}"]))
	return handle.replace("{first}", first.to_lower()).replace("{town}", town.to_lower()).replace("{n}", str(state.rng.randi_range(1, 99)))


func _pick(arr: Array) -> Variant:
	return arr[state.rng.randi() % arr.size()] if not arr.is_empty() else ""


# ================================================================= packing & courier

func c_pack(peer: int, a: Dictionary) -> Dictionary:
	var sid: String = a.get("station", "")
	if state.stations.get(sid, {}).get("type") != "pack":
		return _fail("")
	var it := state.station_slot_item(sid, "unit")
	if it == null or it.kind != Item.KIND_UNIT:
		return _fail("Put a sold unit on the packing table.")
	var u := it.unit
	if u.order_id == 0 or not state.orders.has(u.order_id):
		return _fail("No order for this unit.")
	var box := "big_boxes" if u.is_large() else "boxes"
	for need in [box, "labels", "tape"]:
		if int(state.consumables.get(need, 0)) <= 0:
			return _fail("Out of %s — order more from the Shop." % ("large boxes" if need == "big_boxes" else need))
	state.consumables[box] -= 1
	state.consumables["labels"] -= 1
	state.consumables["tape"] -= 1
	var order: Dictionary = state.orders[u.order_id]
	var slot_id: String = it.loc.slot
	state.set_loc(it, {"t": "in", "item": 0})
	var pkg := state.new_item(Item.KIND_PACKAGE)
	pkg.data = {"order_id": order.id, "unit": it.id, "buyer": order.buyer, "model": order.model, "packer": peer}
	if u.is_large():
		# Box sized to the kit: unit footprint + foam corner blocks.
		var vis: Dictionary = u.model().get("visual", {})
		pkg.data["large"] = true
		pkg.data["carry"] = int(u.model().get("carry", 3))
		pkg.data["size"] = [snappedf(float(vis.get("w", 0.5)) + 0.1, 0.01), snappedf(float(vis.get("h", 0.2)) + 0.08, 0.01), snappedf(float(vis.get("d", 0.7)) + 0.1, 0.01)]
	it.loc = {"t": "in", "item": pkg.id}
	state.mark("item", it.id)
	state.set_loc(pkg, {"t": "slot", "slot": slot_id})
	order.status = "packed"
	order["package"] = pkg.id
	state.mark("order", order.id)
	state.mark("company")
	state.add_xp(player_key(peer), "packing", int(Content.econ("xp", {}).get("pack", 6)))
	BusinessStats.act(state, peer, "packed")
	state.say("Packed order #%d. Take it to the courier shelf." % order.id, "good", peer)
	return _ok()


func courier_collect() -> void:
	var collected := 0
	for slot_id in state.slots.keys():
		if state.slot_defs.get(slot_id, {}).get("station", "") == "":
			continue
		var sid := state.slot_station(slot_id)
		if state.stations.get(sid, {}).get("type") != "courier":
			continue
		var pkg := state.item_in_slot(slot_id)
		if pkg == null or pkg.kind != Item.KIND_PACKAGE:
			continue
		_ship_package(pkg)
		collected += 1
	state.vehicle.emit("courier_collect", {"count": collected})
	if collected > 0:
		state.say("Courier collected %d parcel(s)." % collected, "good")
	else:
		state.say("Courier called — nothing to collect.", "info")


func _ship_package(pkg: Item) -> void:
	var oid := int(pkg.data.get("order_id", 0))
	var order: Dictionary = state.orders.get(oid, {})
	var unit_it := state.get_item(int(pkg.data.get("unit", 0)))
	if not order.is_empty():
		var price := int(order.price)
		var fee := int(round(price * float(Content.econ("flogit_fee_pct", 0.1))))
		var post := int(Content.econ("flogit_postage_large" if pkg.data.get("large", false) else "flogit_postage", 9))
		state.add_money(price, "sales", "Flogit order #%d — %s" % [oid, order.model])
		state.add_money(-fee, "fees", "Flogit fee #%d" % oid)
		state.add_money(-post, "postage", "Courier #%d" % oid)
		var on_time := state.minute <= float(order.due)
		order.status = "shipped" if on_time else "shipped_late"
		order["shipped"] = state.minute
		var rep_gain := float(Content.econ("rep_on_time", 1.0)) if on_time else 0.0
		var packer := int(pkg.data.get("packer", 0))
		if on_time and packer != 0 and state.has_perk(player_key(packer), "careful_packer"):
			rep_gain *= 1.5
		state.add_reputation(rep_gain)
		if packer != 0:
			state.add_xp(player_key(packer), "sales", int(Content.econ("xp", {}).get("ship", 5)))
		state.mark("order", oid)
		state.totals["units_sold"] = int(state.totals.get("units_sold", 0)) + 1
		state.totals["revenue"] = int(state.totals.get("revenue", 0)) + price
		state.bump_stat("shipped")
		state.bump_stat("revenue", price)
		BusinessStats.on_ship(state, order, unit_it.unit if unit_it else null, fee, post, on_time)
	if unit_it:
		unit_it.unit.shipped = true
		unit_it.unit.log_event(state.minute, "Shipped to customer")
		state.remove_item(unit_it.id)
	state.remove_item(pkg.id)


func _check_deadlines() -> void:
	for oid in state.orders:
		var o: Dictionary = state.orders[oid]
		if o.status in ["shipped", "shipped_late", "cancelled"]:
			continue
		if state.minute > float(o.due) and not o.late_flagged:
			o.late_flagged = true
			state.add_reputation(float(Content.econ("rep_late", -3.0)))
			state.bump_stat("late")
			state.say("Order #%d is LATE — %s is not impressed." % [oid, o.buyer], "bad")
			state.mark("order", oid)
		elif state.minute > _add_working_minutes(float(o.due), 16 * 60.0) and o.status == "to_pack":
			# Two working days late and not even packed → buyer cancels.
			o.status = "cancelled"
			state.add_reputation(float(Content.econ("rep_missed", -6.0)))
			state.bump_stat("missed")
			BusinessStats.on_cancel(state)
			var it := state.get_item(int(o.unit))
			if it and it.unit:
				it.unit.order_id = 0
				it.unit.log_event(state.minute, "Order #%d cancelled by buyer" % oid)
				state.mark("item", it.id)
			state.say("Order #%d cancelled — buyer gave up. Reputation hit." % oid, "bad")
			state.mark("order", oid)


# ================================================================= purchasing

func c_buy_lot(peer: int, a: Dictionary) -> Dictionary:
	var lot: Dictionary = state.lots.get(int(a.get("lot", 0)), {})
	if lot.is_empty() or lot.status != "open":
		return _fail("That lot is no longer available.")
	var discount := purchase_discount(peer, lot)
	var total := int(round(int(lot.total_price) * (1.0 - discount)))
	if state.cash - total < int(Content.econ("overdraft_warning", -500)):
		return _fail("The bank won't extend that far. You need %s." % GameState.format_money(total))
	lot.status = "bought"
	lot["bought_by"] = peer
	lot["paid"] = total
	state.add_money(-total, "purchasing", "Lot: %s (%d units)" % [lot.seller_name, lot.count])
	state.totals["units_bought"] = int(state.totals.get("units_bought", 0)) + int(lot.count)
	state.totals["spent"] = int(state.totals.get("spent", 0)) + total
	state.bump_stat("bought", int(lot.count))
	BusinessStats.on_lot_bought(state, lot, total, peer)
	state.add_xp(player_key(peer), "purchasing", int(Content.econ("xp", {}).get("buy_unit", 2)) * int(lot.count))
	var arrive := _next_lorry_slot()
	state.schedule.append({"at": arrive, "type": "lorry", "data": {"lot": lot.id}})
	state.mark("schedule")
	state.mark("lot", lot.id)
	var express := " (first-day express)" if state.day_index_of(arrive) == state.day_index() else ""
	state.say("Bought %s — %d units for %s. Lorry due %s%s." % [lot.seller_name, lot.count, GameState.format_money(total), GameState.format_minute(arrive), express], "good")
	return _ok()


func purchase_discount(peer: int, lot: Dictionary) -> float:
	var key := player_key(peer)
	var d := float(Content.skills_data.get("purchase_discount_per_level", 0.01)) * (state.skill_level(key, "purchasing") - 1)
	if int(lot.get("count", 0)) >= 15 and state.has_perk(key, "bulk_deal"):
		d += 0.05
	return d


func first_day_express() -> bool:
	## Day one would otherwise be spent waiting for tomorrow's lorry: anything bought on the first day comes same-day.
	return state.day_index() == 0 and bool(Content.econ("first_day_express", true))


func _next_lorry_slot() -> float:
	if first_day_express():
		var soon := ceilf((state.minute + float(Content.econ("first_day_lorry_minutes", 30))) / 5.0) * 5.0
		for ev in state.schedule:
			if ev.type == "lorry" and absf(float(ev.at) - soon) < 25.0:
				soon = float(ev.at) + 25.0
		if soon <= state.day_end_minute(0) - 20.0:
			return soon
	# Tomorrow's morning lorry (or this morning's, when buying in the small hours of an all-nighter).
	var day := state.day_index() + (1 if state.minute_of_day() >= float(Content.econ("day_start_hour", 9)) * 60.0 else 0)
	while day % 7 >= 5:
		day += 1
	# Space multiple deliveries out by 40 minutes on the same morning.
	var t := state.day_start_minute(day) + (float(Content.econ("lorry_arrival_hour", 10)) - float(Content.econ("day_start_hour", 9))) * 60.0
	for ev in state.schedule:
		if ev.type == "lorry" and absf(float(ev.at) - t) < 1.0:
			t += 40.0
	return t


func deliver_lot(lot_id: int, drop_pos: Vector3 = Vector3.ZERO, step: Vector3 = Vector3(1.35, 0, 0)) -> Array:
	## Spawns pallet(s) holding the lot's units. Called when the lorry tail-lift drops (or instantly in tests).
	var lot: Dictionary = state.lots.get(lot_id, {})
	if lot.is_empty():
		return []
	var pallets: Array = []
	var units: Array = lot.get("units", []).duplicate()
	# Same-size kit shares a pallet: sort biggest first so servers and towers get their own pallets.
	units.sort_custom(func(a, b): return _unit_volume(a.model_id) > _unit_volume(b.model_id))
	var unit_price := int(round(float(lot.get("paid", lot.total_price)) / maxf(float(lot.count), 1.0)))
	var idx := 0
	while idx < units.size():
		# Fill a pallet with units that share a footprint grid; layer height = the tallest of them.
		var first := pallet_layout(units[idx].model_id)
		var batch: Array = []
		var cell_h := 0.0
		while idx + batch.size() < units.size():
			var lay := pallet_layout(units[idx + batch.size()].model_id)
			var h := maxf(cell_h, float(lay.cell[1]))
			var cap := mini(int(first.grid[0]) * int(first.grid[1]) * clampi(int(1.1 / h), 1, 12), 20)
			if lay.grid != first.grid or batch.size() >= cap:
				break
			cell_h = h
			batch.append(units[idx + batch.size()])
		var pallet := state.new_item(Item.KIND_PALLET)
		pallet.data = {"lot": lot_id, "seller_name": lot.seller_name, "cell": [first.cell[0], cell_h, first.cell[2]], "grid": first.grid}
		var off := step * pallets.size()
		state.set_loc(pallet, {"t": "loose", "p": [drop_pos.x + off.x, drop_pos.y, drop_pos.z + off.z], "r": [0, 0, 0, 1]})
		for k in batch.size():
			var it := state.new_item(Item.KIND_UNIT)
			it.unit = UnitData.from_dict(units[idx])
			it.unit.lot_id = lot_id
			it.unit.cost = unit_price
			it.unit.received = state.minute
			it.unit.log_event(state.minute, "Received from %s" % lot.seller_name)
			state.set_loc(it, {"t": "slot", "slot": "i%d:%d" % [pallet.id, k]})
			idx += 1
		pallets.append(pallet)
	lot.status = "delivered"
	lot.erase("units")
	state.mark("lot", lot_id)
	state.say("Delivery from %s on the drive — %d units." % [lot.seller_name, units.size()], "info")
	return pallets


static func _unit_volume(model_id: String) -> float:
	var v: Dictionary = Content.model(model_id).get("visual", {})
	return snappedf(float(v.get("w", 0.32)) * float(v.get("h", 0.02)) * float(v.get("d", 0.22)), 0.00001)


static func pallet_layout(model_id: String) -> Dictionary:
	## How units of this model stack on a 1.2 × 0.8 m EUR pallet: a grid of cells, layers up to ~1.1 m.
	var v: Dictionary = Content.model(model_id).get("visual", {})
	var w := float(v.get("w", 0.32)) + 0.03
	var d := float(v.get("d", 0.22)) + 0.03
	var h := float(v.get("h", 0.02)) + 0.004
	var cols := clampi(int(1.2 / w), 1, 6)
	var rows := clampi(int(0.8 / d), 1, 4)
	var layers := clampi(int(1.1 / h), 1, 12)
	return {"cell": [snappedf(1.2 / cols, 0.001), snappedf(h, 0.001), snappedf(0.8 / rows, 0.001)], "grid": [cols, rows], "capacity": mini(cols * rows * layers, 20)}


func refresh_lot_board(first_day: bool = false) -> void:
	var day := state.day_index()
	for id in state.lots.keys():
		var l: Dictionary = state.lots[id]
		if l.status == "open" and float(l.expires_min) <= state.minute + 1.0:
			l.status = "expired"
			state.mark("lot", id)
		if l.status in ["expired", "delivered"] and state.minute - float(l.listed_min) > GameState.MIN_PER_DAY * 4:
			state.lots.erase(id)
			state.mark("lot", id)
	# Predictable refresh cycles: big lots on the advertised day.
	for sid in state.seller_state:
		var ss: Dictionary = state.seller_state[sid]
		var seller: Dictionary = Content.sellers.get(sid, {})
		if seller.is_empty():
			continue
		if day >= int(ss.next_refresh_day):
			if state.reputation >= float(seller.get("min_rep", 0)):
				lots.create_lot(seller, true)
				state.say("%s has released its scheduled hardware refresh!" % seller.name, "info")
			ss.next_refresh_day = int(ss.next_refresh_day) + _cycle_days(seller)
	var open := state.lots.values().filter(func(l): return l.status == "open").size()
	var board: Array = Content.econ("lots_on_board", [4, 7])
	var target := state.rng.randi_range(int(board[0]), int(board[1]))
	if first_day:
		target = maxi(target, 5)
	var eligible := lots.eligible_sellers()
	while open < target and not eligible.is_empty():
		var s: Dictionary = eligible[state.rng.randi() % eligible.size()]
		lots.create_lot(s, false)
		open += 1
	state.mark("schedule")


func _cycle_days(seller: Dictionary) -> int:
	# 1 in-game "year" of refresh cycle ≈ 10 working days, so cycles are visible within a play session.
	return int(seller.get("refresh_years", 3)) * 10


func _init_seller_cycles() -> void:
	for sid in Content.sellers:
		var s: Dictionary = Content.sellers[sid]
		state.seller_state[sid] = {"next_refresh_day": 2 + int(s.get("refresh_day_offset", 0)) + int(s.get("refresh_years", 3)) * 3}


func upcoming_refreshes() -> Array:
	var out: Array = []
	for sid in state.seller_state:
		var s: Dictionary = Content.sellers.get(sid, {})
		out.append({"seller": s.get("name", sid), "day": int(state.seller_state[sid].next_refresh_day),
			"locked": state.reputation < float(s.get("min_rep", 0)), "min_rep": s.get("min_rep", 0)})
	out.sort_custom(func(a, b): return a.day < b.day)
	return out


# ================================================================= shop

func shop_price(id: String) -> int:
	if Content.shop_items.has(id):
		return int(Content.shop_items[id].price)
	return int(Content.part(id).get("price", 0))


func c_shop_buy(peer: int, a: Dictionary) -> Dictionary:
	var id: String = a.get("id", "")
	var qty := clampi(int(a.get("qty", 1)), 1, 50)
	if Content.shop_items.get(id, {}).get("kind", "") == "licence":
		return _buy_licence(peer, Content.shop_items[id])
	var price := shop_price(id) * qty
	if price <= 0:
		return _fail("Unknown product.")
	if Content.shop_items.get(id, {}).get("kind", "") == "consumable" and state.has_perk(player_key(peer), "bulk_supplies"):
		price = int(round(price * 0.75))
	if state.cash - price < int(Content.econ("overdraft_warning", -500)):
		return _fail("Not enough funds.")
	var parcel_contents := {"parts": {}, "items": [], "consumables": {}}
	var name := id
	if Content.shop_items.has(id):
		var s: Dictionary = Content.shop_items[id]
		name = s.name
		match s.kind:
			"item":
				for i in qty:
					parcel_contents.items.append(s.item_kind)
			"consumable":
				parcel_contents.consumables[s.consumable] = int(s.qty) * qty
	else:
		name = Content.part(id).get("name", id)
		parcel_contents.parts[id] = qty
	state.add_money(-price, "shop", "%d× %s" % [qty, name])
	# Shop orders arrive with the next courier visit that is at least an hour away (day one: within ~20 minutes).
	var arrive := _next_courier_after(state.minute + 60.0)
	var window := 1.0
	if first_day_express() and state.minute + 20.0 <= state.day_end_minute(0) - 10.0:
		arrive = ceilf((state.minute + float(Content.econ("first_day_parcel_minutes", 20))) / 5.0) * 5.0
		window = 15.0
	var merged := false
	for ev in state.schedule:
		if ev.type == "parcel" and absf(float(ev.at) - arrive) < window:
			_merge_contents(ev.data, parcel_contents)
			merged = true
			break
	if not merged:
		state.schedule.append({"at": arrive, "type": "parcel", "data": parcel_contents})
	state.mark("schedule")
	state.say("Ordered %d× %s — arrives %s." % [qty, name, GameState.format_minute(arrive)], "info", peer)
	return _ok()


func _buy_licence(peer: int, s: Dictionary) -> Dictionary:
	## One-off licences (night shift): no parcel, it's yours as soon as it's paid for.
	var lic := str(s.get("licence", s.id))
	if state.has_licence(lic):
		return _fail("You already have the %s." % str(s.name).to_lower())
	var price := int(s.price)
	if state.cash - price < int(Content.econ("overdraft_warning", -500)):
		return _fail("The %s costs %s." % [str(s.name).to_lower(), GameState.format_money(price)])
	state.add_money(-price, "equipment", str(s.name))
	var all: Dictionary = state.totals.get("licences", {})
	all[lic] = true
	state.totals["licences"] = all
	state.mark("company")
	BusinessStats.event(state, "equipment", "Bought the %s (%s)" % [str(s.name).to_lower(), GameState.format_money(price)])
	if lic == "night_shift":
		state.say("Night shift licence granted and the yard floodlights are up. At 17:00 you can keep going into overtime.", "good")
	else:
		state.say("%s bought." % s.name, "good", peer)
	return _ok()


func _merge_contents(dst: Dictionary, src: Dictionary) -> void:
	for p in src.parts:
		dst.parts[p] = int(dst.parts.get(p, 0)) + int(src.parts[p])
	dst.items.append_array(src.items)
	for c in src.consumables:
		dst.consumables[c] = int(dst.consumables.get(c, 0)) + int(src.consumables[c])


func _next_courier_after(t: float) -> float:
	var times: Array = Content.econ("courier_collections", [720, 990])
	var day := int(floor(t / GameState.MIN_PER_DAY))
	for i in 14:
		var d := day + i
		if d % 7 >= 5:
			continue
		for m in times:
			var at := d * GameState.MIN_PER_DAY + float(m)
			if at >= t:
				return at
	return t + 60.0


func deliver_parcel(contents: Dictionary, pos: Vector3 = Vector3.ZERO) -> Item:
	# Pallets and pallet trucks don't come in a box: the courier leaves them by the parcel.
	var items: Array = contents.get("items", []).duplicate()
	var bulky := items.filter(func(k): return k in [Item.KIND_PALLET, Item.KIND_PALLET_TRUCK])
	var n := 0
	for k in bulky:
		var big := state.new_item(k)
		state.set_loc(big, {"t": "loose", "p": [pos.x + 1.4 + n * 1.3, pos.y, pos.z], "r": [0, 0, 0, 1]})
		items.erase(k)
		n += 1
	contents = contents.duplicate(true)
	contents["items"] = items
	if items.is_empty() and contents.get("parts", {}).is_empty() and contents.get("consumables", {}).is_empty():
		state.say("Your delivery has been left by the door.", "info")
		return null
	var parcel := state.new_item(Item.KIND_PARCEL)
	parcel.data = contents.duplicate(true)
	state.set_loc(parcel, {"t": "loose", "p": [pos.x, pos.y, pos.z], "r": [0, 0, 0, 1]})
	state.say("A parcel has been left by the door.", "info")
	return parcel


func c_unbox(peer: int, a: Dictionary) -> Dictionary:
	var it := state.get_item(int(a.get("item", 0)))
	if it == null or it.kind != Item.KIND_PARCEL:
		return _fail("")
	var c: Dictionary = it.data
	var desc: PackedStringArray = []
	for p in c.get("parts", {}):
		state.parts[p] = int(state.parts.get(p, 0)) + int(c.parts[p])
		desc.append("%d× %s" % [int(c.parts[p]), Content.part(p).get("name", p)])
	for k in c.get("consumables", {}):
		state.consumables[k] = int(state.consumables.get(k, 0)) + int(c.consumables[k])
		desc.append("%d %s" % [int(c.consumables[k]), k])
	var base_pos: Array = a.get("p", it.loc.get("p", [0, 1, 0]))
	var n := 0
	for kind in c.get("items", []):
		var usb := state.new_item(kind)
		state.set_loc(usb, {"t": "loose", "p": [float(base_pos[0]) + 0.08 * n, float(base_pos[1]) + 0.15, float(base_pos[2])], "r": [0, 0, 0, 1]})
		desc.append(usb.display_name())
		n += 1
	state.remove_item(it.id)
	state.mark("company")
	state.say("Unboxed: " + ", ".join(desc), "info", peer)
	return _ok()


# ================================================================= day / time

func tick(real_delta: float) -> void:
	if paused or not state.day_running or state.overtime_asking():
		return
	var rate := (float(Content.econ("day_end_hour", 17)) - float(Content.econ("day_start_hour", 9))) * 60.0 / float(Content.econ("real_seconds_per_day", 900))
	advance_minutes(real_delta * rate)
	tick_automation(real_delta)


func tick_automation(real_delta: float) -> void:
	## Belts and arms run in real seconds; the layout is re-read whenever equipment is placed or moved.
	var sig := state.fixtures.hash()
	if sig != _fixture_sig:
		_fixture_sig = sig
		auto.dirty = true
	auto.tick(real_delta)
	state.mark("clock")


func advance_minutes(mins: float) -> void:
	if state.overtime_asking():
		return          # closing time: waiting for the host to choose overtime or home time
	var end := state.shift_end_minute()
	var target := minf(state.minute + mins, end)
	# Step in ≤5-minute chunks so hourly/scheduled events fire in order even on big jumps.
	while state.minute < target - 0.0001:
		var step := minf(5.0, target - state.minute)
		state.minute += step
		_meter_power(step)
		BusinessStats.on_clock(state, active_peers, step)
		_shift_clock(step)
		_process_minute_events()
	state.mark("clock")
	_check_goals()
	if state.minute >= end - 0.0001:
		if _offer_overtime():
			return
		end_day()


# ================================================================= overtime (night shift licence)

static func overtime_cfg() -> Dictionary:
	return Content.econ("overtime", {})


static func overtime_options() -> Array:
	return overtime_cfg().get("options", [])


func _offer_overtime() -> bool:
	## Closing time with the night shift licence: hold the clock at 17:00 while the host decides.
	if not state.has_licence("night_shift") or float(state.shift.get("until", 0.0)) > 0.0 or state.shift.get("asked", false):
		return false
	state.shift = {"ask": true, "day": state.day_index()}
	state.mark("clock")
	state.say("Closing time. Keep going into overtime, or call it a day?", "info")
	return true


func c_overtime(peer: int, a: Dictionary) -> Dictionary:
	## The host's answer to "Keep going?": an overtime option id, or "close" for home time.
	if not state.overtime_asking():
		return _fail("")
	if peer != 1:
		return _fail("The host decides whether to keep going.")
	var day := int(state.shift.get("day", state.day_index()))
	var choice := str(a.get("choice", "close"))
	if choice == "close":
		state.shift = {"asked": true, "day": day}
		state.mark("clock")
		end_day()
		return _ok()
	var opt: Dictionary = {}
	for o: Dictionary in overtime_options():
		if str(o.id) == choice:
			opt = o
	if opt.is_empty():
		return _fail("")
	var until := day * GameState.MIN_PER_DAY + float(opt.end_hour) * 60.0
	if until <= state.minute:
		return _fail("")
	state.shift = {"until": until, "day": day, "asked": true, "choice": choice}
	state.mark("clock")
	state.bump_stat("overtime_shifts")
	BusinessStats.event(state, "premises", "Overtime: %s" % str(opt.name).to_lower())
	var paid := "" if state.staff.is_empty() else " Staff are on time-and-a-half."
	state.say("Keep going! Overtime %s.%s Brew up after midnight or you'll flag." % [str(opt.name).to_lower(), paid], "info")
	return _ok()


func overtime_rate_per_minute() -> float:
	## £ per overtime minute for everyone on shift (time-and-a-half of their hourly rate).
	var cfg := overtime_cfg()
	var per := float(cfg.get("pay_mult", 1.5)) / (float(cfg.get("hours_per_week", 40)) * 60.0)
	var total := 0.0
	for d in state.staff.values():
		if not StaffMorale.is_away(d):
			total += float(d.get("wage", 0)) * per
	return total


func _shift_clock(step: float) -> void:
	## Per clock step: overtime hours and pay, and staff morale (breaks, a long week, late nights).
	var ot := state.overtime_active()
	if ot:
		state.day_stats["ot_min"] = float(state.day_stats.get("ot_min", 0.0)) + step
		var pay := overtime_rate_per_minute() * step
		if pay > 0.0:
			state.day_stats["ot_pay"] = float(state.day_stats.get("ot_pay", 0.0)) + pay
	if not state.staff.is_empty():
		var mod := state.minute_of_day()
		StaffMorale.tick(state, step, ot, ot and (mod >= 22.0 * 60.0 or mod < 6.0 * 60.0))


func _check_goals() -> void:
	Challenges.check(self)
	for id in Campaign.check(state):
		var day := int(Campaign.reached(state).get(id, Campaign.day_number(state)))
		var end_id := str(Campaign.data().get("end_goal", {}).get("id", ""))
		var text := "🏆 %s — reached on day %d" % [Campaign.name_of(id), day]
		if id == end_id:
			text = "🏆 REFURB EMPIRE COMPLETE — mega plant and £20M in %d working days!" % day
		state.say(text, "good")
		BusinessStats.event(state, "milestone", text)
		if state.challenge().is_empty():
			state.goal_reached.emit(id, day)     # challenge starts aren't fair campaign times: no milestone records


# ================================================================= electricity

var _power_acc := 0.0
var _power_kw := -1.0


func power_draw_kw() -> float:
	var kw := 0.0
	for k in power_breakdown(state).values():
		kw += float(k.kw)
	return kw


static func power_breakdown(st: GameState) -> Dictionary:
	## What the premises is pulling right now, by equipment name: lighting by floor area, plus every powered
	## fixture at its rating while it works (bench job, rack bays, arm move, belt running) and a standby
	## fraction otherwise. {name: {kw, count, active}}
	var out := {"Lighting": {"kw": float(st.building().get("floor_m2", 40)) * float(Content.econ("lighting_w_per_m2", 5.0)) / 1000.0, "count": 1, "active": 1}}
	if st.has_licence("night_shift") and DayNight.darkness(st.minute_of_day()) > 0.15:
		var nf := DayNight.flood_count(st.building())
		out["Floodlights"] = {"kw": nf * float(overtime_cfg().get("flood_kw", 0.15)), "count": nf, "active": nf}
	var running_belts := {}
	for it: Item in st.items.values():
		if it.loc.get("t") == "belt" and float(it.loc.get("v", 0.0)) > 0.0:
			running_belts[str(it.loc.get("belt", ""))] = true
	for fid in st.fixtures:
		var f: Dictionary = st.fixtures[fid]
		if not f.get("placed", false):
			continue
		var def := Content.fixture(f.type)
		var rated := float(def.get("power_kw", 0.0)) * Automation.power_mult(f)
		if rated <= 0.0:
			continue
		var sd: Dictionary = st.stations.get(fid, {})
		var active: bool = not sd.get("job", {}).is_empty() or not sd.get("jobs", {}).is_empty() or running_belts.has(fid)
		var name := str(def.get("name", f.type))
		var e: Dictionary = out.get(name, {"kw": 0.0, "count": 0, "active": 0})
		e.kw = float(e.kw) + rated * (1.0 if active else float(def.get("idle_frac", Content.econ("power_idle_frac", 0.15))))
		e.count = int(e.count) + 1
		e.active = int(e.active) + (1 if active else 0)
		out[name] = e
	return out


func _meter_power(step: float) -> void:
	if not state.day_running:
		return
	if _power_kw < 0.0:
		_power_kw = power_draw_kw()
	_power_acc += step
	if _power_acc >= 1.0:
		add_kwh(_power_kw * _power_acc / 60.0)
		_power_acc = 0.0
		_power_kw = power_draw_kw()
		state.day_stats["kw_now"] = snappedf(_power_kw, 0.01)


func add_kwh(kwh: float) -> void:
	state.day_stats["kwh"] = float(state.day_stats.get("kwh", 0.0)) + kwh


func electricity_cost(kwh: float) -> int:
	return int(round(kwh * float(Content.econ("electricity_per_kwh", 0.28))))


func _process_minute_events() -> void:
	# Scheduled deliveries / parcels.
	var due: Array = []
	for ev in state.schedule:
		if float(ev.at) <= state.minute:
			due.append(ev)
	for ev in due:
		state.schedule.erase(ev)
		state.mark("schedule")
		match ev.type:
			"lorry":
				state.vehicle.emit("lorry", ev.data)     # visual layer calls deliver_lot at the drop moment
				if not visual_vehicles:
					deliver_lot(int(ev.data.lot))
			"parcel":
				state.vehicle.emit("parcel", ev.data)
				if not visual_vehicles:
					deliver_parcel(ev.data)
	# Courier collections.
	var mod := state.minute_of_day()
	for m in Content.econ("courier_collections", [720, 990]):
		var key := "courier_%d_%d" % [state.day_index(), int(m)]
		if mod >= float(m) and not state.day_stats.has(key):
			state.day_stats[key] = 1
			if visual_vehicles:
				state.vehicle.emit("courier", {"collect": true})
			else:
				courier_collect()
	# Hourly market.
	var hour := int(state.minute / 60.0)
	if hour != state.last_flogit_hour:
		state.last_flogit_hour = hour
		flogit_hourly()
	_process_jobs()
	_process_machines()
	_check_deadlines()


func end_day() -> void:
	if not state.day_running:
		return
	state.day_running = false
	# Overtime past midnight: the calendar has moved on, but this is still that day's shift (rent on Friday's).
	var day := state.shift_day()
	var overnight := state.day_index() > day
	state.shift.erase("ask")
	var ds := state.day_stats
	var summary := ds.duplicate()
	summary["day"] = day
	summary["weekday"] = GameState.WEEKDAYS[day % 7]
	summary["rep_delta"] = state.reputation - float(ds.get("rep_start", state.reputation))
	# Metered electricity for the day (lights, machines, kettle).
	var kwh := float(ds.get("kwh", 0.0))
	# Pennies carry over, so a garage's few kWh a day still adds up on the bill.
	var owed := float(state.totals.get("power_owed", 0.0)) + kwh * float(Content.econ("electricity_per_kwh", 0.28))
	var bill := int(floor(owed))
	state.totals["power_owed"] = owed - bill
	state.totals["kwh"] = float(state.totals.get("kwh", 0.0)) + kwh
	if bill > 0:
		state.add_money(-bill, "utilities", "Electricity: %d kWh @ %dp" % [int(round(kwh)), int(round(float(Content.econ("electricity_per_kwh", 0.28)) * 100.0))])
	summary["kwh"] = kwh
	summary["electric"] = bill
	# Overtime: hours worked late, and staff time-and-a-half owed (paid with Friday's wages).
	var ot_min := float(ds.get("ot_min", 0.0))
	if ot_min > 0.0:
		var ot_pay := float(ds.get("ot_pay", 0.0))
		summary["overtime_h"] = snappedf(ot_min / 60.0, 0.1)
		summary["overtime_pay"] = int(round(ot_pay))
		summary["overtime_until"] = str(state.shift.get("choice", ""))
		state.totals["ot_owed"] = float(state.totals.get("ot_owed", 0.0)) + ot_pay
		state.totals["ot_hours_week"] = float(state.totals.get("ot_hours_week", 0.0)) + ot_min / 60.0
		state.bump_stat("overtime_minutes", int(round(ot_min)))
		if overnight:
			state.bump_stat("all_nighters")
	summary["profit"] = int(state.day_stats.get("income", 0)) - int(state.day_stats.get("spend", 0))
	summary["cash"] = state.cash
	# Weekly rent & bills on the due weekday.
	if day % 7 == int(Content.econ("rent_due_weekday", 4)):
		var b := state.building()
		var rent := int(b.get("rent_weekly", 150))
		var util := int(b.get("utilities_weekly", 25))
		state.add_money(-rent, "rent", "Weekly rent — %s" % b.get("name", "premises"))
		state.add_money(-util, "utilities", "Water, broadband & standing charges")
		summary["rent_paid"] = rent + util
		var wages := weekly_wages()
		if wages > 0:
			state.add_money(-wages, "wages", "Wages — %d staff" % state.staff.size())
			summary["wages"] = wages
		var ot_owed := int(round(float(state.totals.get("ot_owed", 0.0))))
		if ot_owed > 0:
			state.add_money(-ot_owed, "overtime", "Staff overtime — %.1f h at time-and-a-half" % float(state.totals.get("ot_hours_week", 0.0)))
			summary["overtime_paid"] = ot_owed
		state.totals["ot_owed"] = 0.0
		state.totals["ot_hours_week"] = 0.0
		StaffMorale.week_reset(state)
		if state.cash < 0:
			var fee := int(Content.econ("overdraft_fee_weekly", 40))
			state.add_money(-fee, "bank", "Overdraft fee")
			state.add_reputation(-float(Content.econ("overdraft_rep_penalty", 3)))
			summary["overdraft_fee"] = fee
			state.say("You're overdrawn. The bank has charged a fee — get selling!", "bad")
		summary["cash"] = state.cash
	summary["run_day"] = Campaign.day_number(state)
	state.totals["days_worked"] = int(state.totals.get("days_worked", 0)) + 1
	summary["rating"] = shift_rating(summary)
	if not state.staff.is_empty():
		StaffMorale.day_end(self, summary, overnight)
		summary["morale"] = _team_morale()
	Challenges.on_day_end(self, summary)
	BusinessStats.on_day_end(state, summary)
	state.mark("clock")
	state.mark("company")
	state.day_ended.emit(summary)


static func shift_rating(s: Dictionary) -> int:
	## 1–5 stars from throughput, sales and mistakes.
	var score := 0.0
	score += int(s.get("tested", 0)) * 0.5 + int(s.get("repaired", 0)) * 0.6 + int(s.get("imaged", 0)) * 0.4
	score += int(s.get("graded", 0)) * 0.3 + int(s.get("shipped", 0)) * 1.2
	score -= int(s.get("late", 0)) * 2.0 + int(s.get("missed", 0)) * 4.0
	if s.get("profit", 0) > 0:
		score += 2.0
	return clampi(int(round(1.0 + score / 4.0)), 1, 5)


func _team_morale() -> float:
	var total := 0.0
	var n := 0
	for d in state.staff.values():
		if not StaffMorale.is_away(d):
			total += StaffMorale.value(d)
			n += 1
	return snappedf(total / n, 0.1) if n > 0 else -1.0


func start_next_day() -> void:
	if state.day_running:
		return
	var day := state.shift_day() + 1      # after an all-nighter the calendar already reads tomorrow
	while day % 7 >= 5:
		day += 1                          # weekends off — the lorry company doesn't work Saturdays either
	var start := state.day_start_minute(day)
	state.shift = {}
	# Overnight: jobs that finish before morning complete; deliveries for the morning still come in daytime.
	state.minute = start
	_course_returns()
	_process_jobs()
	_check_deadlines()
	state.day_stats = _blank_day_stats()
	state.last_flogit_hour = int(state.minute / 60.0)
	state.day_running = true
	refresh_lot_board()
	if state.weekday() == 0 or state.candidates.is_empty():
		refresh_candidates()
	state.mark("clock")
	state.mark("company")
	state.say("%s, day %d. Kettle's on." % [state.weekday_name(), state.day_index() + 1], "info")


func c_next_day(_peer: int, _a: Dictionary) -> Dictionary:
	start_next_day()
	return _ok()


# ================================================================= equipment (fixtures) & premises

static func fixture_label(st: GameState, type: String) -> String:
	var n := 0
	for f in st.fixtures.values():
		if f.type == type:
			n += 1
	if type == "metal_shelf":
		return "Shelf %s" % char(65 + n)
	return "%s %d" % [Content.fixture(type).get("name", type), n + 1]


func c_buy_fixture(peer: int, a: Dictionary) -> Dictionary:
	var type: String = a.get("type", "")
	var def := Content.fixture(type)
	if def.is_empty():
		return _fail("Unknown equipment.")
	var price := int(def.get("price", 0))
	if state.cash - price < int(Content.econ("overdraft_warning", -500)):
		return _fail("Not enough funds for %s." % def.name)
	var id := add_fixture(type)
	state.add_money(-price, "equipment", "Bought %s" % def.name)
	BusinessStats.on_fixture_bought(state, id, def.name, price)
	state.mark("fixture", id)
	state.say("Bought %s — place it from the Building app (or it'll be set up for you)." % def.name, "good", peer)
	return {"ok": true, "msg": "", "id": id}


func add_fixture(type: String) -> String:
	## A new piece of equipment in storage (the level lays it out). Used by the Shop and challenge starts.
	var def := Content.fixture(type)
	if def.is_empty():
		return ""
	var n := 1
	while state.fixtures.has("%s_%d" % [type, n]):
		n += 1
	var id := "%s_%d" % [type, n]
	state.fixtures[id] = {"type": type, "label": fixture_label(state, type), "placed": false, "p": [0, 0, 0], "r": 0.0}
	if str(def.get("auto", {}).get("origin", "")) == "in":
		state.fixtures[id]["bend"] = Automation.clamp_bend(def.auto)
	state.mark("fixture", id)
	return id


func c_place_fixture(_peer: int, a: Dictionary) -> Dictionary:
	var id: String = a.get("id", "")
	if not state.fixtures.has(id):
		return _fail("Unknown equipment.")
	var f: Dictionary = state.fixtures[id]
	var p: Array = a.get("p", [0, 0, 0])
	f.placed = true
	f.p = [float(p[0]), float(p[1]), float(p[2])]
	f.r = float(a.get("r", 0.0))
	if a.has("bend") and str(Automation.def_of(f.type).get("origin", "")) == "in":
		f["bend"] = Automation.clamp_bend(a.bend)
	state.mark("fixture", id)
	return _ok()


func c_store_fixture(peer: int, a: Dictionary) -> Dictionary:
	var id: String = a.get("id", "")
	if not state.fixtures.has(id):
		return _fail("")
	if not state.fixture_items(id).is_empty():
		return _fail("Empty it first — there are items on it.")
	var st: Dictionary = state.stations.get(id, {})
	if not st.get("job", {}).is_empty():
		return _fail("A job is running on it.")
	state.fixtures[id].placed = false
	state.mark("fixture", id)
	state.say("%s packed away into storage." % state.fixtures[id].label, "info", peer)
	return _ok()


func move_cost(building_id: String) -> int:
	return int(Content.building(building_id).get("move_fee", 0))


func c_move_building(peer: int, a: Dictionary) -> Dictionary:
	var to: String = a.get("building", "")
	var b := Content.building(to)
	if b.is_empty():
		return _fail("Unknown premises.")
	if to == state.building_id:
		return _fail("You're already here.")
	var cost := move_cost(to)
	if state.cash - cost < int(Content.econ("overdraft_warning", -500)):
		return _fail("Moving to %s costs %s (removals + deposit)." % [b.name, GameState.format_money(cost)])
	if cost > 0:
		state.add_money(-cost, "premises", "Move to %s (removals + deposit)" % b.name)
	state.building_id = to
	# Equipment is re-laid out by the new level; whatever is on it travels with it.
	for id in state.fixtures:
		state.fixtures[id].placed = false
		state.mark("fixture", id)
	# Loose stock, pallets and parcels are dropped off in the new goods-in area.
	for it: Item in state.items.values():
		if it.loc.get("t") == "loose":
			state.set_loc(it, {"t": "loose", "relocate": true, "p": [0, 1, 0], "r": [0, 0, 0, 1]})
	state.mark("building")
	BusinessStats.on_moved(state, b)
	state.say("Moving day! Welcome to %s, %s." % [b.name, b.get("address", "")], "good")
	return _ok()


func migrate_save() -> void:
	if not state.totals.has("run_id"):
		state.totals["run_id"] = "%d-save" % state.seed_value
		state.totals["days_worked"] = int(state.day_index() * 5 / 7)
		state.totals["goals"] = {}
	## Older saves (before movable equipment / premises) get the starting kit; the level lays it out.
	if state.fixtures.is_empty():
		for f in Content.econ("start_fixtures", []):
			state.fixtures[f.id] = {"type": f.type, "label": f.get("label", ""), "placed": false, "p": [0, 0, 0], "r": 0.0}
	if not Content.buildings.has(state.building_id):
		state.building_id = String(Content.econ("start_building", "garage"))
