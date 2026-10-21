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


func _init(s: GameState) -> void:
	state = s
	lots = LotGenerator.new(s)


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


# ================================================================= carrying

func c_pick_up(peer: int, a: Dictionary) -> Dictionary:
	var it := state.get_item(int(a.get("item", 0)))
	if it == null or it.loc.get("t") in ["held", "in", "gone"]:
		return _fail("")
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
	if sid != "" and state.stations.has(sid):
		try_start_bench(sid, peer)


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
			st.msg = "Already tested. Swap to the PaneOS USB to wipe & image."
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
			st.msg = "Already imaged. Take it to grading."
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
	if (skill == "testing" and state.has_perk(key, "bench_whisper")) or (skill == "imaging" and state.has_perk(key, "fast_wipe")):
		sp *= 1.15
	return sp


func _process_jobs() -> void:
	for sid in state.stations:
		var st: Dictionary = state.stations[sid]
		if st.job.is_empty():
			continue
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
			if by != 0 and not it.unit.has_open_faults() and state.has_perk(player_key(by), "auto_chain"):
				_auto_chain(sid, by)
		elif kind == "image":
			it.unit.imaged = true
			if by != 0 and state.has_perk(player_key(by), "data_cert"):
				it.unit.data_cert = true
			it.unit.log_event(state.minute, "Wiped (%s) and imaged with %s" % [Content.os_info.get("wipe_standard", ""), Content.os_info.get("name", "PaneOS")])
			state.bump_stat("imaged")
			BusinessStats.on_image(state, it.unit, by)
			if by != 0:
				state.add_xp(player_key(by), "imaging", int(Content.econ("xp", {}).get("image", 4)))
			st.msg = "IMAGED"
			state.say("%s imaged — ready to grade." % it.unit.display_name(), "good")
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
		"storage_gb": u.storage_gb, "battery_pct": u.battery_pct if u.is_laptop() else -1, "faults": lines, "pass": lines.is_empty(),
		"type": u.hw_type(), "spec": u.spec_line(),
	}
	u.log_event(state.minute, "Tested: " + ("PASS" if lines.is_empty() else "%d fault(s)" % lines.size()))
	state.bump_stat("tested")
	BusinessStats.on_test(state, u, by)
	if by != 0:
		state.add_xp(player_key(by), "testing", int(Content.econ("xp", {}).get("test", 6)))
	if lines.is_empty():
		state.say("%s — PASS" % u.display_name(), "good")
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
	for f in open:
		var fdef := Content.fault(f.id)
		var tier := int(fdef.get("repair_level", 1))
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
	return {"can": false, "why": blocked_reason}


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
	state.say("Bought %s — %d units for %s. Lorry due %s." % [lot.seller_name, lot.count, GameState.format_money(total), GameState.format_minute(arrive)], "good")
	return _ok()


func purchase_discount(peer: int, lot: Dictionary) -> float:
	var key := player_key(peer)
	var d := float(Content.skills_data.get("purchase_discount_per_level", 0.01)) * (state.skill_level(key, "purchasing") - 1)
	if int(lot.get("count", 0)) >= 15 and state.has_perk(key, "bulk_deal"):
		d += 0.05
	return d


func _next_lorry_slot() -> float:
	var day := state.day_index() + 1
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
	# Shop orders arrive with the next courier visit that is at least an hour away.
	var arrive := _next_courier_after(state.minute + 60.0)
	var merged := false
	for ev in state.schedule:
		if ev.type == "parcel" and absf(float(ev.at) - arrive) < 1.0:
			_merge_contents(ev.data, parcel_contents)
			merged = true
			break
	if not merged:
		state.schedule.append({"at": arrive, "type": "parcel", "data": parcel_contents})
	state.mark("schedule")
	state.say("Ordered %d× %s — arrives %s." % [qty, name, GameState.format_minute(arrive)], "info", peer)
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
	if paused or not state.day_running:
		return
	var rate := (float(Content.econ("day_end_hour", 17)) - float(Content.econ("day_start_hour", 9))) * 60.0 / float(Content.econ("real_seconds_per_day", 900))
	advance_minutes(real_delta * rate)


func advance_minutes(mins: float) -> void:
	var end := state.day_end_minute(state.day_index())
	var target := minf(state.minute + mins, end)
	# Step in ≤5-minute chunks so hourly/scheduled events fire in order even on big jumps.
	while state.minute < target - 0.0001:
		var step := minf(5.0, target - state.minute)
		state.minute += step
		BusinessStats.on_clock(state, active_peers, step)
		_process_minute_events()
	state.mark("clock")
	if state.minute >= end - 0.0001:
		end_day()


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
	_check_deadlines()


func end_day() -> void:
	if not state.day_running:
		return
	state.day_running = false
	var ds := state.day_stats
	var summary := ds.duplicate()
	summary["day"] = state.day_index()
	summary["weekday"] = state.weekday_name()
	summary["rep_delta"] = state.reputation - float(ds.get("rep_start", state.reputation))
	summary["profit"] = int(ds.get("income", 0)) - int(ds.get("spend", 0))
	summary["cash"] = state.cash
	# Weekly rent & bills on the due weekday.
	if state.weekday() == int(Content.econ("rent_due_weekday", 4)):
		var b := state.building()
		var rent := int(b.get("rent_weekly", 150))
		var util := int(b.get("utilities_weekly", 25))
		state.add_money(-rent, "rent", "Weekly rent — %s" % b.get("name", "premises"))
		state.add_money(-util, "utilities", "Electric & broadband")
		summary["rent_paid"] = rent + util
		if state.cash < 0:
			var fee := int(Content.econ("overdraft_fee_weekly", 40))
			state.add_money(-fee, "bank", "Overdraft fee")
			state.add_reputation(-float(Content.econ("overdraft_rep_penalty", 3)))
			summary["overdraft_fee"] = fee
			state.say("You're overdrawn. The bank has charged a fee — get selling!", "bad")
		summary["cash"] = state.cash
	summary["rating"] = shift_rating(summary)
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


func start_next_day() -> void:
	if state.day_running:
		return
	var day := state.day_index() + 1
	while day % 7 >= 5:
		day += 1                          # weekends off — the lorry company doesn't work Saturdays either
	var start := state.day_start_minute(day)
	# Overnight: jobs that finish before morning complete; deliveries for the morning still come in daytime.
	state.minute = start
	_process_jobs()
	_check_deadlines()
	state.day_stats = _blank_day_stats()
	state.last_flogit_hour = int(state.minute / 60.0)
	state.day_running = true
	refresh_lot_board()
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
	var n := 1
	while state.fixtures.has("%s_%d" % [type, n]):
		n += 1
	var id := "%s_%d" % [type, n]
	state.fixtures[id] = {"type": type, "label": fixture_label(state, type), "placed": false, "p": [0, 0, 0], "r": 0.0}
	state.add_money(-price, "equipment", "Bought %s" % def.name)
	BusinessStats.on_fixture_bought(state, id, def.name, price)
	state.mark("fixture", id)
	state.say("Bought %s — place it from the Building app (or it'll be set up for you)." % def.name, "good", peer)
	return {"ok": true, "msg": "", "id": id}


func c_place_fixture(_peer: int, a: Dictionary) -> Dictionary:
	var id: String = a.get("id", "")
	if not state.fixtures.has(id):
		return _fail("Unknown equipment.")
	var f: Dictionary = state.fixtures[id]
	var p: Array = a.get("p", [0, 0, 0])
	f.placed = true
	f.p = [float(p[0]), float(p[1]), float(p[2])]
	f.r = float(a.get("r", 0.0))
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
	## Older saves (before movable equipment / premises) get the starting kit; the level lays it out.
	if state.fixtures.is_empty():
		for f in Content.econ("start_fixtures", []):
			state.fixtures[f.id] = {"type": f.type, "label": f.get("label", ""), "placed": false, "p": [0, 0, 0], "r": 0.0}
	if not Content.buildings.has(state.building_id):
		state.building_id = String(Content.econ("start_building", "garage"))
