class_name Automation
extends RefCounted
## Host-side automation (M5). Owned by Sim; runs in real seconds from Sim.tick. Everything is derived from
## placed fixtures + their data (fixtures.json "auto"), so it works headless and is unit-testable.
##
##   Belts      conveyor / curves / devices. Items ride a path: loc {"t":"belt","belt":id,"s":s0,"at":t0,"v":v}
##              (s at time t0, moving at v m/s; clients extrapolate, so the network only hears about starts/stops).
##              Items queue with real spacing, hand over to the belt whose in-port meets this belt's out-port,
##              or wait at the end ("end stop") where arms can pick them up.
##   Devices    scanner (books units in), grader (grades + lists imaged units), sorter (routes by stage rules).
##   Arms       pick from belt end stops, pallets and finished bays within reach; place each item where it needs
##              to go next (test rack → repair robot / imaging rack → grader belt → stock / packer → OUTGOING).
##              While carried: loc {"t":"arm","arm":id}. A pick-and-place takes `cycle` seconds.
##   Stations   rack_test / rack_image (bays, no USB), robot_repair, robot_pack — processed by Sim on game time.

const PORT_SNAP := 0.35       # out-port and in-port closer than this (m) are joined
const EPS := 0.002
const SORTER_SIDE := 0.78     # side exits sit on the sorter's footprint edge
const LOAD_S := 0.38          # loading point: far enough in that a rack server sits wholly on the belt

var sim: Sim
var state: GameState
var belts := {}               # fid -> {length, xf, kind, ports_in:[{p,d}], ports_out:[{p,d,name}], next:{outname: fid}, speed, dev}
var arms := {}                # fid -> {xf, reach, cycle}
var dirty := true
var _think := 0.0


func _init(p_sim: Sim) -> void:
	sim = p_sim
	state = p_sim.state


# ================================================================= topology

static func def_of(fid_type: String) -> Dictionary:
	return Content.fixture(fid_type).get("auto", {})


static func fixture_xf(f: Dictionary) -> Transform3D:
	var p: Array = f.get("p", [0, 0, 0])
	return Transform3D(Basis(Vector3.UP, deg_to_rad(float(f.get("r", 0.0)))), Vector3(p[0], p[1], p[2]))


static func belt_local(auto: Dictionary) -> Dictionary:
	## Local path + ports for a belt definition. Paths start at the in-port (s = 0).
	var kind: String = auto.get("path", "line")
	if kind == "arc":
		var r := float(auto.get("radius", 1.0))
		var right: bool = auto.get("turn", "right") == "right"
		# Turning right (as the kit travels along +Z) means bending towards -X.
		var sx := -1.0 if right else 1.0
		# The curve's footprint is centred: its square is (radius + half width) on a side.
		var half := (r + float(auto.get("half_width", 0.5))) * 0.5
		var centre := Vector3(half * sx, 0, -half)
		var p_in := centre - Vector3(r * sx, 0, 0)
		var p_out := centre + Vector3(0, 0, r)
		return {"kind": "arc", "length": r * PI * 0.5, "radius": r, "centre": centre, "sx": sx,
			"in": [{"p": p_in, "d": Vector3(0, 0, 1)}],
			"out": [{"p": p_out, "d": Vector3(sx, 0, 0), "name": "out"}]}
	var length := float(auto.get("length", 2.0))
	var outs: Array = [{"p": Vector3(0, 0, length * 0.5), "d": Vector3(0, 0, 1), "name": "out"}]
	if auto.get("device", "") == "sorter":
		outs.append({"p": Vector3(-SORTER_SIDE, 0, length * 0.5 - 0.3), "d": Vector3(-1, 0, 0), "name": "left"})
		outs.append({"p": Vector3(SORTER_SIDE, 0, length * 0.5 - 0.3), "d": Vector3(1, 0, 0), "name": "right"})
	return {"kind": "line", "length": length, "in": [{"p": Vector3(0, 0, -length * 0.5), "d": Vector3(0, 0, 1)}], "out": outs}


static func path_point(lb: Dictionary, s: float) -> Transform3D:
	## Local transform of a point s metres along a belt (item faces the direction of travel).
	var length: float = lb.length
	s = clampf(s, 0.0, length)
	if lb.kind == "arc":
		var th := s / float(lb.radius)
		var sx: float = lb.sx
		var c: Vector3 = lb.centre
		var r: float = lb.radius
		var pos := c + Vector3(-cos(th) * r * sx, 0, sin(th) * r)
		var fwd := Vector3(sin(th) * sx, 0, cos(th)).normalized()
		return Transform3D(Basis.looking_at(-fwd, Vector3.UP), pos)
	return Transform3D(Basis.IDENTITY, Vector3(0, 0, -length * 0.5 + s))


static func belt_world(f: Dictionary) -> Dictionary:
	## {xf, lb, height} for a placed belt fixture — enough for anyone (clients too) to place things on it.
	var auto := def_of(f.get("type", ""))
	return {"xf": fixture_xf(f), "lb": belt_local(auto), "height": float(auto.get("height", 0.84))}


static func belt_point(geo: Dictionary, s: float) -> Transform3D:
	var local := path_point(geo.lb, s)
	local.origin.y += float(geo.height)
	return (geo.xf as Transform3D) * local


static func ports_of(type: String, xf: Transform3D) -> Array:
	## World ports of a belt fixture at `xf`: [{p, d, in: bool, name}] (used for placement snapping).
	var auto := def_of(type)
	if auto.get("kind", "") != "belt":
		return []
	var lb := belt_local(auto)
	var out: Array = []
	for prt in lb.in:
		out.append({"p": xf * (prt.p as Vector3), "d": xf.basis * (prt.d as Vector3), "in": true, "name": "in"})
	for prt in lb.out:
		out.append({"p": xf * (prt.p as Vector3), "d": xf.basis * (prt.d as Vector3), "in": false, "name": prt.name})
	return out


func rebuild() -> void:
	belts.clear()
	arms.clear()
	for fid in state.fixtures:
		var f: Dictionary = state.fixtures[fid]
		if not f.get("placed", false):
			continue
		var auto := def_of(f.type)
		if auto.is_empty():
			continue
		var xf := fixture_xf(f)
		match auto.get("kind", ""):
			"belt":
				var lb := belt_local(auto)
				var ins: Array = []
				for prt in lb.in:
					ins.append({"p": xf * (prt.p as Vector3), "d": xf.basis * (prt.d as Vector3)})
				var outs: Array = []
				for prt in lb.out:
					outs.append({"p": xf * (prt.p as Vector3), "d": xf.basis * (prt.d as Vector3), "name": prt.name})
				belts[fid] = {"lb": lb, "xf": xf, "length": float(lb.length), "in": ins, "out": outs, "next": {},
					"speed": float(auto.get("speed", 0.6)), "dev": auto.get("device", ""), "height": float(auto.get("height", 0.84))}
			"arm":
				arms[fid] = {"xf": xf, "reach": float(auto.get("reach", 1.8)), "cycle": float(auto.get("cycle", 3.6))}
				state.register_station(fid, "arm")
	# Anything left riding a conveyor that has been taken away drops to the floor where it was.
	for it: Item in state.items.values():
		if it.loc.get("t") == "belt" and not belts.has(str(it.loc.get("belt", ""))):
			var f: Dictionary = state.fixtures.get(str(it.loc.get("belt", "")), {})
			var p := Vector3(0, 0.05, 0)
			if not f.is_empty():
				p = fixture_xf(f).origin + Vector3(0, 0.05, 0)
			state.set_loc(it, {"t": "loose", "p": [p.x, p.y, p.z], "r": [0, 0, 0, 1]})
		elif it.loc.get("t") == "arm" and not arms.has(str(it.loc.get("arm", ""))):
			var fa: Dictionary = state.fixtures.get(str(it.loc.get("arm", "")), {})
			var pa := fixture_xf(fa).origin + Vector3(0.8, 0.05, 0) if not fa.is_empty() else Vector3(0, 0.05, 0)
			state.set_loc(it, {"t": "loose", "p": [pa.x, pa.y, pa.z], "r": [0, 0, 0, 1]})
	# Join out-ports to the in-ports they touch.
	for a in belts:
		for o in belts[a].out:
			for b in belts:
				if b == a:
					continue
				var ip: Dictionary = belts[b].in[0]
				if (ip.p as Vector3).distance_to(o.p) < PORT_SNAP and (ip.d as Vector3).dot(o.d) > 0.7:
					belts[a].next[o.name] = b
	dirty = false


func free_ports() -> Array:
	## Open belt ports in world space (for placement snapping): [{p, d, in: bool, fid}]
	if dirty:
		rebuild()
	var used_in := {}
	for a in belts:
		for n in belts[a].next.values():
			used_in[n] = true
	var out: Array = []
	for a in belts:
		if not used_in.has(a):
			out.append({"p": belts[a].in[0].p, "d": belts[a].in[0].d, "in": true, "fid": a})
		for o in belts[a].out:
			if not belts[a].next.has(o.name):
				out.append({"p": o.p, "d": o.d, "in": false, "fid": a})
	return out


# ================================================================= belt items

func items_on(fid: String) -> Array:
	var out: Array = []
	for it: Item in state.items.values():
		if it.loc.get("t") == "belt" and it.loc.get("belt", "") == fid:
			out.append(it)
	out.sort_custom(func(a, b): return pos_of(a) > pos_of(b))
	return out


func pos_of(it: Item) -> float:
	var loc := it.loc
	return float(loc.get("s", 0.0)) + float(loc.get("v", 0.0)) * (state.belt_clock - float(loc.get("at", 0.0)))


static func item_len(it: Item) -> float:
	## Length along the belt (items ride front-first).
	if it.kind == Item.KIND_UNIT and it.unit:
		return float(it.unit.model().get("visual", {}).get("d", 0.3))
	if it.kind == Item.KIND_PACKAGE:
		var sz: Array = it.data.get("size", [0.44, 0.12, 0.34])
		return float(sz[2])
	return 0.3


func put_on_belt(it: Item, fid: String, s: float = -1.0) -> void:
	## s = where the item's centre goes; by default it's set down wholly on the belt at the start.
	var b: Dictionary = belts.get(fid, {})
	if s < 0.0:
		s = item_len(it) * 0.5 + 0.02
	state.set_loc(it, {"t": "belt", "belt": fid, "s": s, "at": state.belt_clock, "v": float(b.get("speed", 0.6))})


func belt_has_room(fid: String, it: Item, s0: float = -1.0) -> bool:
	## Is the start of this belt clear enough for `it` (centred at s0)?
	if s0 < 0.0:
		s0 = item_len(it) * 0.5 + 0.02
	for other: Item in items_on(fid):
		if pos_of(other) - s0 < (item_len(other) + item_len(it)) * 0.5 + 0.06:
			return false
	return true


func end_stop(fid: String, it: Item) -> float:
	## Where the front item waits at a closed end: wholly on the belt, front edge at the end.
	return float(belts[fid].length) - item_len(it) * 0.5 - 0.01


func _set_motion(it: Item, s: float, v: float) -> void:
	if absf(float(it.loc.get("v", 0.0)) - v) < 0.0001 and v != 0.0:
		return
	if v == 0.0 and float(it.loc.get("v", 0.0)) == 0.0 and absf(float(it.loc.get("s", 0.0)) - s) < 0.0005:
		return
	var loc := it.loc.duplicate()
	loc.s = s
	loc.at = state.belt_clock
	loc.v = v
	state.set_loc(it, loc)


func _step_belt(fid: String) -> void:
	var b: Dictionary = belts[fid]
	var length: float = b.length
	var list := items_on(fid)
	var front_s := INF
	var front_len := 0.0
	for i in list.size():
		var it: Item = list[i]
		var s := pos_of(it)
		var limit := front_s - (front_len + item_len(it)) * 0.5 - 0.06
		if i == 0:
			# Run on to the end only if the next conveyor can take it; otherwise stop wholly on this belt.
			limit = length if _can_hand_over(fid, it) else end_stop(fid, it)
		if b.dev != "" and s >= length * 0.5 and not it.data.get("fx_" + fid, false):
			_device_effect(fid, b.dev, it)
		if i == 0 and s >= length - EPS:
			if _hand_over(fid, it):
				continue
		if s >= limit - EPS:
			_set_motion(it, maxf(minf(s, limit), 0.0), 0.0)
		else:
			_set_motion(it, s, float(b.speed))
		front_s = minf(s, limit)
		front_len = item_len(it)


func _can_hand_over(fid: String, it: Item) -> bool:
	var b: Dictionary = belts[fid]
	var outs: Array = ["out"]
	if b.dev == "sorter":
		outs = _sorter_choices(fid, it)
	for o in outs:
		var nxt: String = b.next.get(o, "")
		if nxt != "" and belts.has(nxt) and belt_has_room(nxt, it, 0.0):
			return true
	return false


func _hand_over(fid: String, it: Item) -> bool:
	var b: Dictionary = belts[fid]
	var outs: Array = ["out"]
	if b.dev == "sorter":
		outs = _sorter_choices(fid, it)
	for o in outs:
		var nxt: String = b.next.get(o, "")
		if nxt != "" and belts.has(nxt) and belt_has_room(nxt, it, 0.0):
			put_on_belt(it, nxt, 0.0)
			return true
	return false


# ================================================================= devices

func _device_effect(fid: String, dev: String, it: Item) -> void:
	it.data["fx_" + fid] = true
	if it.kind != Item.KIND_UNIT or it.unit == null:
		return
	var u := it.unit
	match dev:
		"scanner":
			if not u.booked:
				u.booked = true
				u.log_event(state.minute, "Booked in by the goods-in scanner")
				state.bump_stat("booked")
				state.mark("item", it.id)
		"grader":
			if u.imaged and u.grade == "" and not u.has_open_faults():
				u.grade = Valuation.grade_for(u)
				u.list_price = Valuation.market_value(u, u.grade)
				BusinessStats.on_grade(state, u, 0)
				u.log_event(state.minute, "Graded %s by the vision grader" % UnitData.grade_label(u.grade))
				if Content.econ("flogit_auto_list_on_grade", true):
					BusinessStats.on_listed(state, u, 0)
					u.listed = true
				state.bump_stat("graded")
				state.mark("item", it.id)


const SORTER_RULES := ["any", "untested", "faulty", "passed", "imaged", "graded", "sold", "large", "none"]


func sorter_rules(fid: String) -> Dictionary:
	var st: Dictionary = state.stations.get(fid, {})
	return st.get("rules", {"left": "faulty", "right": "passed", "out": "any"})


static func rule_matches(rule: String, it: Item) -> bool:
	var u := it.unit
	match rule:
		"any":
			return true
		"none":
			return false
		"large":
			return it.is_large()
	if u == null:
		return rule == "sold" and it.kind == Item.KIND_PACKAGE
	match rule:
		"untested":
			return not u.tested
		"faulty":
			return u.tested and u.has_open_faults()
		"passed":
			return u.tested and not u.has_open_faults() and not u.imaged
		"imaged":
			return u.imaged and u.grade == ""
		"graded":
			return u.grade != "" and u.order_id == 0
		"sold":
			return u.order_id != 0
	return false


func _sorter_choices(fid: String, it: Item) -> Array:
	var rules := sorter_rules(fid)
	var out: Array = []
	for o in ["left", "right", "out"]:
		if rule_matches(str(rules.get(o, "any")), it):
			out.append(o)
	return out


# ================================================================= arms

func _arm_station(fid: String) -> Dictionary:
	if not state.stations.has(fid):
		state.register_station(fid, "arm")
	return state.stations[fid]


func _in_reach(arm: Dictionary, p: Vector3, slack: float = 0.0) -> bool:
	var base: Vector3 = (arm.xf as Transform3D).origin
	return Vector2(p.x - base.x, p.z - base.z).length() <= float(arm.reach) + slack


func _fixture_pos(fid: String) -> Vector3:
	var f: Dictionary = state.fixtures.get(fid, {})
	var p: Array = f.get("p", [0, 0, 0])
	return Vector3(p[0], p[1], p[2])


func _fixture_slack(fid: String) -> float:
	var fp: Array = Content.fixture(state.fixtures.get(fid, {}).get("type", "")).get("footprint", [1, 1])
	return minf(float(fp[0]), float(fp[1])) * 0.5


func _tick_arm(fid: String) -> void:
	var arm: Dictionary = arms[fid]
	var st := _arm_station(fid)
	var job: Dictionary = st.get("job", {})
	if not job.is_empty():
		if state.belt_clock >= float(job.t0) + float(job.dur):
			_finish_arm_job(fid, st, job)
		return
	var pick := _find_pick(fid, arm)
	if pick.is_empty():
		return
	var it := state.get_item(int(pick.item))
	sim._leave_slot_side_effects(it)
	var from_slot: String = it.loc.get("slot", "") if it.loc.get("t") == "slot" else ""
	state.set_loc(it, {"t": "arm", "arm": fid})
	if from_slot != "":
		# A machine that just had its output cleared may be able to start on what's waiting.
		var host := from_slot.get_slice(":", 0)
		var t: String = state.stations.get(host, {}).get("type", "")
		if t in ["rack_test", "rack_image", "robot_repair", "robot_pack"]:
			for sid in state.slot_defs:
				if str(sid).begins_with(host + ":"):
					sim.try_start_machine(host, str(sid).get_slice(":", 1))
	st.job = {"item": it.id, "to": pick.to, "t0": state.belt_clock, "dur": float(arm.cycle), "from_belt": pick.get("from_belt", "")}
	state.mark("station", fid)


func _finish_arm_job(fid: String, st: Dictionary, job: Dictionary) -> void:
	var it := state.get_item(int(job.item))
	st.job = {}
	state.mark("station", fid)
	if it == null:
		return
	var to: Dictionary = job.to
	if _deliver(it, to):
		return
	# The target filled up meanwhile: try anywhere else sensible, else put it down on the arm's own spot.
	var alt := _choose_target(fid, arms[fid], it, str(job.get("from_belt", "")))
	if not alt.is_empty() and _deliver(it, alt):
		return
	var o := (arms[fid].xf as Transform3D).origin + (arms[fid].xf as Transform3D).basis.z * 0.9
	state.set_loc(it, {"t": "loose", "p": [o.x, 0.05, o.z], "r": [0, 0, 0, 1]})


func _deliver(it: Item, to: Dictionary) -> bool:
	match to.get("kind", ""):
		"slot":
			var sid: String = to.slot
			if state.item_in_slot(sid) != null or not state.slot_accepts(sid, it.slot_kind()):
				return false
			if not sim._station_accepts(state.slot_station(sid), sid, it).ok:
				return false
			state.set_loc(it, {"t": "slot", "slot": sid})
			sim._on_entered_slot(it, sid, 0)
			return true
		"belt":
			if not belts.has(to.belt) or not belt_has_room(to.belt, it):
				return false
			put_on_belt(it, to.belt)
			return true
	return false


func _find_pick(fid: String, arm: Dictionary) -> Dictionary:
	## The first thing in reach that wants moving, and where it should go.
	var candidates: Array = []     # [{item, from_belt}]
	# 1) Items waiting at the end of a belt that goes nowhere.
	for b in belts:
		var bd: Dictionary = belts[b]
		var dead_end: bool = not bd.next.has("out")
		for o in bd.out:
			if o.name == "out" and dead_end and _in_reach(arm, o.p):
				var list := items_on(b)
				if not list.is_empty() and pos_of(list[0]) >= end_stop(b, list[0]) - 0.05:
					candidates.append({"item": list[0].id, "from_belt": b})
	# 2) Finished work in reachable stations (racks, robots) and stock on pallets.
	for sid in state.slot_defs:
		var it := state.item_in_slot(sid)
		if it == null:
			continue
		var host := str(sid).get_slice(":", 0)
		if not state.fixtures.has(host) or not _in_reach(arm, _fixture_pos(host), _fixture_slack(host)):
			continue
		if _finished_in_station(host, sid, it):
			candidates.append({"item": it.id})
		elif it.unit and it.unit.order_id != 0 and state.stations.get(host, {}).get("type", "") == "":
			candidates.append({"item": it.id})      # sold stock on racking → the packing robot
	for it: Item in state.items.values():
		if it.kind != Item.KIND_PALLET or it.loc.get("t") != "loose":
			continue
		var pp: Array = it.loc.get("p", [0, 0, 0])
		if not _in_reach(arm, Vector3(pp[0], 0, pp[2]), 0.5):
			continue
		var top := _pallet_top_unit(it.id)
		if top:
			candidates.append({"item": top.id})
	for c in candidates:
		var it := state.get_item(int(c.item))
		if it == null:
			continue
		var to := _choose_target(fid, arm, it, str(c.get("from_belt", "")))
		if not to.is_empty():
			c["to"] = to
			return c
	return {}


func _pallet_top_unit(pallet_id: int) -> Item:
	var best: Item = null
	var best_k := -1
	for it: Item in state.items.values():
		if it.loc.get("t") == "slot" and str(it.loc.slot).begins_with("i%d:" % pallet_id):
			var k := int(str(it.loc.slot).get_slice(":", 1))
			if k > best_k:
				best_k = k
				best = it
	return best


func _finished_in_station(host: String, sid: String, it: Item) -> bool:
	var t: String = state.stations.get(host, {}).get("type", "")
	var jobs: Dictionary = state.stations.get(host, {}).get("jobs", {})
	var slot_name := str(sid).get_slice(":", 1)
	if jobs.has(slot_name):
		return false     # still working on it
	if it.kind == Item.KIND_PACKAGE:
		return t == "robot_pack"
	if it.unit == null:
		return false
	var u := it.unit
	match t:
		"rack_test":
			return u.tested
		"rack_image":
			return u.imaged
		"robot_repair":
			return u.tested and sim._robot_fixable(host, u).is_empty()
		"robot_pack":
			return false
	return false


func _choose_target(fid: String, arm: Dictionary, it: Item, from_belt: String) -> Dictionary:
	## Where should this item go next? Specific stations first, then a belt in reach, then stock racking.
	var wants: Array = []
	if it.kind == Item.KIND_PACKAGE:
		wants = ["courier"]
	elif it.unit:
		var u := it.unit
		if u.order_id != 0:
			wants = ["robot_pack"]
		elif not u.tested:
			wants = ["rack_test"]
		elif u.has_open_faults():
			# Only to the robot if it can fix something now (tier + part in the bins); else set aside on racking.
			if _robot_can_fix(u):
				# Wait in place for the repair robot rather than sending it away while the robot's busy.
				var rs := _free_slot_of_type(arm, "robot_repair", it)
				return {"kind": "slot", "slot": rs} if rs != "" else {}
			wants = ["stock"]
		elif not u.imaged:
			wants = ["rack_image"]
		elif u.grade == "":
			wants = []          # → a belt (through the vision grader)
		else:
			wants = ["stock"]
	for want in wants:
		var slot := _free_slot_of_type(arm, want, it)
		if slot != "":
			return {"kind": "slot", "slot": slot}
	# Its next station is right here but busy: leave it where it is (the queue backs up) rather than sending
	# it off down the line where nothing can do that job.
	for want in wants:
		if want != "stock" and _type_in_reach(arm, want):
			return {}
	# Transport: a belt in-port within reach (never straight back onto the belt it came off).
	# Kit with nothing specific to do next, or whose next stop is full, may also be buffered on racking below.
	for b in belts:
		if b == from_belt:
			continue
		var ip: Dictionary = belts[b].in[0]
		var fed := false
		for a in belts:
			if b in belts[a].next.values():
				fed = true
		if not fed and _in_reach(arm, ip.p) and belt_has_room(b, it):
			return {"kind": "belt", "belt": b}
	# Never leave a conveyor end blocked: anything still without a home goes on stock racking as a buffer.
	if from_belt != "" and it.kind == Item.KIND_UNIT and not "stock" in wants:
		var slot := _free_slot_of_type(arm, "stock", it)
		if slot != "":
			return {"kind": "slot", "slot": slot}
	return {}


func _type_in_reach(arm: Dictionary, want: String) -> bool:
	for host in state.stations:
		if not state.fixtures.has(host) or not state.fixtures[host].get("placed", false):
			continue
		var t: String = state.stations[host].get("type", "")
		if t == want and _in_reach(arm, _fixture_pos(host), _fixture_slack(host)):
			return true
	return false


func _robot_can_fix(u: UnitData) -> bool:
	for host in state.stations:
		if state.stations[host].get("type", "") == "robot_repair" and not sim._robot_fixable(host, u).is_empty():
			return true
	return false


func _free_slot_of_type(arm: Dictionary, want: String, it: Item) -> String:
	for sid in state.slot_defs:
		var host := str(sid).get_slice(":", 0)
		if not state.fixtures.has(host):
			continue
		var stype: String = state.stations.get(host, {}).get("type", "")
		var ok := false
		match want:
			"courier":
				ok = stype == "courier"
			"stock":
				ok = stype == "" and not str(sid).begins_with("i") and not belts.has(host)
			_:
				ok = stype == want
		if not ok:
			continue
		if not _in_reach(arm, _fixture_pos(host), _fixture_slack(host)):
			continue
		if state.item_in_slot(sid) != null or not state.slot_accepts(sid, it.slot_kind()):
			continue
		if want in ["robot_pack"] and str(sid).get_slice(":", 1) != "unit":
			continue
		if not sim._station_accepts(stype if stype != "" else "", sid, it).ok and stype != "":
			continue
		return sid
	return ""


# ================================================================= tick

func tick(dt: float) -> void:
	state.belt_clock += dt
	if dirty:
		rebuild()
	if belts.is_empty() and arms.is_empty():
		return
	for fid in belts:
		_step_belt(fid)
		var waiting := state.item_in_slot(fid + ":load")
		if waiting and belt_has_room(fid, waiting, maxf(LOAD_S, item_len(waiting) * 0.5 + 0.02)):
			sim._leave_slot_side_effects(waiting)
			put_on_belt(waiting, fid, maxf(LOAD_S, item_len(waiting) * 0.5 + 0.02))
	_think -= dt
	if _think <= 0.0:
		_think = 0.25
		for fid in arms:
			_tick_arm(fid)
