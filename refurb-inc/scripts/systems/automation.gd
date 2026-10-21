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
##   Line kit   splitter (alternate / overflow to two side exits), stop gate (open / hold), people's hatch (kit
##              waits at a pick face for a person; whatever a person puts on the send point rides on), speed
##              tiers per belt piece (fixture "tier"), line monitors (host publishes rates + machine load).
## Every machine event is counted with BusinessStats.on_auto (kinds: belt_in, scan, sort, split, merge, gate,
## hatch_in, hatch_send, arm, tower_in, tower_out, recycle, grader; Sim adds rack_test/rack_image/robot_*).

const PORT_SNAP := 0.35       # out-port and in-port closer than this (m) are joined
const EPS := 0.002
const SORTER_SIDE := 0.78     # side exits sit on the sorter's footprint edge
const LOAD_S := 0.38          # loading point: far enough in that a rack server sits wholly on the belt
const BEND_MAX_LEN := 4.0     # longest flexible bend (metres of belt)
const TOWER_SLOTS := 24       # trays in an automated stock tower
const SPLIT_TAIL := 0.35      # splitter: kit stops this far short of the front, in the middle of the cross-transfer
const UTIL_TAU := 45.0        # machine load is a moving average over roughly this many real seconds
## Speed tiers for any belt piece (data: economy.json "belt_speed_tiers"; these are the fallback).
const DEFAULT_TIERS := [
	{"id": "standard", "name": "Standard", "speed_mult": 1.0, "power_mult": 1.0, "cost": 0},
	{"id": "fast", "name": "Fast", "speed_mult": 1.7, "power_mult": 1.4, "cost": 350},
	{"id": "express", "name": "Express", "speed_mult": 2.5, "power_mult": 1.9, "cost": 800},
]
## Belt devices with a mode a person sets in-world ([E]); the first is the default.
const BELT_MODES := {"splitter": ["alternate", "overflow"], "gate": ["open", "hold"]}
## Belt devices the line monitor lists as machines (plain straights, curves and mergers are just transport).
const MACHINE_DEVICES := ["scanner", "grader", "sorter", "splitter", "gate", "hatch"]

var sim: Sim
var state: GameState
var belts := {}               # fid -> {length, xf, kind, ports_in:[{p,d}], ports_out:[{p,d,name}], next:{outname: fid}, speed, dev}
var arms := {}                # fid -> {xf, reach, cycle}
var towers := {}              # fid -> {xf, in_p, in_d, out_p, out_d, out_belt} (automated stock towers)
var machines := {}            # fid -> "arm" / "rack_test" / "rack_image" / "robot_repair" / "robot_pack" (load tracked)
var monitors: Array = []      # line monitor fixtures (host publishes line rates into their station state)
var dirty := true
var _think := 0.0
var _util := {}               # machine fid -> moving-average busy fraction
var _bays := {}               # rack fid -> bay count
var _arm_q := {}              # arm fid -> things in reach waiting to be moved (last look)
var _util_pub := 0.0
var _mon_pub := 0.0
var _last_cands := 0


func _init(p_sim: Sim) -> void:
	sim = p_sim
	state = p_sim.state


# ================================================================= topology

static func def_of(fid_type: String) -> Dictionary:
	return Content.fixture(fid_type).get("auto", {})


static func auto_for(f: Dictionary) -> Dictionary:
	## The fixture's automation definition with its own settings merged in (a flexible bend's angle/radius/turn,
	## a belt's speed tier).
	var a := def_of(f.get("type", ""))
	if f.has("bend"):
		a = a.duplicate()
		a.merge(f.bend, true)
	if int(f.get("tier", 0)) > 0 and a.get("kind", "") == "belt":
		if not f.has("bend"):
			a = a.duplicate()
		a["tier"] = tier_of(f)
		a["speed"] = float(a.get("speed", 0.6)) * float(tier_info(a.tier).get("speed_mult", 1.0))
	return a


# ================================================================= speed tiers

static func belt_tiers() -> Array:
	var t: Array = Content.econ("belt_speed_tiers", [])
	return t if not t.is_empty() else DEFAULT_TIERS


static func tier_of(f: Dictionary) -> int:
	return clampi(int(f.get("tier", 0)), 0, belt_tiers().size() - 1)


static func tier_info(tier: int) -> Dictionary:
	var t := belt_tiers()
	return t[clampi(tier, 0, t.size() - 1)]


static func power_mult(f: Dictionary) -> float:
	## Faster belts draw more (Sim.power_breakdown multiplies a belt piece's rating by this).
	if def_of(str(f.get("type", ""))).get("kind", "") != "belt":
		return 1.0
	return float(tier_info(tier_of(f)).get("power_mult", 1.0))


static func belt_links(st: GameState) -> Dictionary:
	## fid -> {joined fid: true} for every placed belt piece, both directions, from the ports alone (works on
	## clients too). Ports are bucketed by metre so a big factory stays cheap.
	var ports: Array = []
	for fid in st.fixtures:
		var f: Dictionary = st.fixtures[fid]
		if not f.get("placed", false) or def_of(str(f.get("type", ""))).get("kind", "") != "belt":
			continue
		for prt in ports_of(f.type, fixture_xf(f), f.get("bend", {})):
			prt["fid"] = fid
			ports.append(prt)
	var grid := {}
	for i in ports.size():
		var p: Vector3 = ports[i].p
		var key := Vector2i(floori(p.x), floori(p.z))
		if not grid.has(key):
			grid[key] = []
		grid[key].append(i)
	var links := {}
	for a in ports:
		if not a["in"]:
			continue
		var p: Vector3 = a.p
		var c := Vector2i(floori(p.x), floori(p.z))
		for dx in [-1, 0, 1]:
			for dz in [-1, 0, 1]:
				for j in grid.get(c + Vector2i(dx, dz), []):
					var b: Dictionary = ports[j]
					if b["in"] or b.fid == a.fid:
						continue
					if (b.p as Vector3).distance_to(p) < PORT_SNAP and (b.d as Vector3).dot(a.d) > 0.7:
						if not links.has(a.fid):
							links[a.fid] = {}
						if not links.has(b.fid):
							links[b.fid] = {}
						links[a.fid][b.fid] = true
						links[b.fid][a.fid] = true
	return links


static func belt_run(st: GameState, fid: String) -> Array:
	## This belt piece and every piece joined to it (either way, through devices) that runs at the same speed.
	var f: Dictionary = st.fixtures.get(fid, {})
	if f.is_empty():
		return []
	var tier := tier_of(f)
	var links := belt_links(st)
	var seen := {fid: true}
	var todo: Array = [fid]
	while not todo.is_empty():
		var cur: String = todo.pop_back()
		for nb in links.get(cur, {}):
			if not seen.has(nb) and tier_of(st.fixtures.get(nb, {})) == tier:
				seen[nb] = true
				todo.append(nb)
	var out: Array = seen.keys()
	out.sort()
	return out


static func upgrade_plan(st: GameState, fid: String) -> Dictionary:
	## What upgrading this belt piece would do: the whole run at its speed if the company can afford it, else
	## just this piece. {} = not a belt. {max: true} = already top speed. {ids, to, name, cost, speed, short}.
	var f: Dictionary = st.fixtures.get(fid, {})
	if f.is_empty() or def_of(str(f.get("type", ""))).get("kind", "") != "belt":
		return {}
	var tiers := belt_tiers()
	var tier := tier_of(f)
	if tier >= tiers.size() - 1:
		return {"max": true, "tier": tier, "name": str(tiers[tier].get("name", ""))}
	var nxt: Dictionary = tiers[tier + 1]
	var step := int(nxt.get("cost", 0))
	var floor_cash := int(Content.econ("overdraft_warning", -500))
	var ids := belt_run(st, fid)
	var plan := {"tier": tier, "to": tier + 1, "name": str(nxt.get("name", "")), "step": step,
		"speed": float(def_of(f.type).get("speed", 0.6)) * float(nxt.get("speed_mult", 1.0)), "run": ids.size()}
	if ids.size() > 1 and st.cash - step * ids.size() >= floor_cash:
		plan["ids"] = ids
	else:
		plan["ids"] = [fid]
	plan["cost"] = step * plan.ids.size()
	plan["short"] = st.cash - int(plan.cost) < floor_cash
	return plan


static func belt_mode(st: GameState, fid: String, dev: String) -> String:
	var modes: Array = BELT_MODES.get(dev, [""])
	return str(st.stations.get(fid, {}).get("mode", modes[0]))


static func load_slot_s(auto: Dictionary) -> float:
	## Where a belt's loading point sits along it (the people's hatch sends on from just past its pick face).
	if str(auto.get("device", "")) == "hatch":
		return float(auto.get("length", 1.6)) * 0.5 + 0.3
	return LOAD_S


static func clamp_bend(b: Dictionary) -> Dictionary:
	## Keep a flexible bend realistic: 15–180° in 15° steps, radius 0.75–2.5 m, at most 4 m of belt.
	var ang := clampf(snappedf(float(b.get("angle", 90.0)), 15.0), 15.0, 180.0)
	var r := clampf(snappedf(float(b.get("radius", 1.0)), 0.25), 0.75, 2.5)
	while r > 0.75 and r * deg_to_rad(ang) > BEND_MAX_LEN:
		r -= 0.25
	return {"angle": ang, "radius": r, "turn": "left" if str(b.get("turn", "right")) == "left" else "right"}


static func fixture_xf(f: Dictionary) -> Transform3D:
	var p: Array = f.get("p", [0, 0, 0])
	return Transform3D(Basis(Vector3.UP, deg_to_rad(float(f.get("r", 0.0)))), Vector3(p[0], p[1], p[2]))


static func belt_local(auto: Dictionary) -> Dictionary:
	## Local path + ports for a belt definition. Paths start at the in-port (s = 0).
	var kind: String = auto.get("path", "line")
	if kind == "arc":
		var r := float(auto.get("radius", 1.0))
		var ang := deg_to_rad(float(auto.get("angle", 90.0)))
		var right: bool = auto.get("turn", "right") == "right"
		# Turning right (as the kit travels along +Z) means bending towards -X.
		var sx := -1.0 if right else 1.0
		var centre: Vector3
		if str(auto.get("origin", "centre")) == "in":
			# Flexible bends: the fixture's origin is its in-port.
			centre = Vector3(r * sx, 0, 0)
		else:
			# The fixed 90° curves: footprint centred, (radius + half width) on a side.
			var half := (r + float(auto.get("half_width", 0.5))) * 0.5
			centre = Vector3(half * sx, 0, -half)
		var p_in := centre - Vector3(r * sx, 0, 0)
		var p_out := centre + Vector3(-cos(ang) * r * sx, 0, sin(ang) * r)
		return {"kind": "arc", "length": r * ang, "radius": r, "centre": centre, "sx": sx, "angle": ang,
			"in": [{"p": p_in, "d": Vector3(0, 0, 1), "s": 0.0, "name": "in"}],
			"out": [{"p": p_out, "d": Vector3(sin(ang) * sx, 0, cos(ang)), "name": "out"}]}
	var length := float(auto.get("length", 2.0))
	var z0 := -length * 0.5
	var outs: Array = [{"p": Vector3(0, 0, length * 0.5), "d": Vector3(0, 0, 1), "name": "out"}]
	if auto.get("device", "") == "sorter":
		outs.append({"p": Vector3(-SORTER_SIDE, 0, length * 0.5 - 0.3), "d": Vector3(-1, 0, 0), "name": "left"})
		outs.append({"p": Vector3(SORTER_SIDE, 0, length * 0.5 - 0.3), "d": Vector3(1, 0, 0), "name": "right"})
	elif auto.get("device", "") == "splitter":
		# The path runs to the middle of the cross-transfer; kit leaves sideways from there, never straight on.
		# Exits are named from the kit's point of view, like the curves (travelling +Z, left is +X).
		# (The sorter's and merger's internal names are the other way round — kept for old saves; ConveyorView
		# and fixtures.json show them to players from the kit's point of view too.)
		length -= SPLIT_TAIL
		outs = [{"p": Vector3(SORTER_SIDE, 0, z0 + length), "d": Vector3(1, 0, 0), "name": "left"},
			{"p": Vector3(-SORTER_SIDE, 0, z0 + length), "d": Vector3(-1, 0, 0), "name": "right"}]
	var ins: Array = [{"p": Vector3(0, 0, z0), "d": Vector3(0, 0, 1), "s": 0.0, "name": "in"}]
	if auto.get("device", "") == "merge":
		# A side conveyor joins here, a little before the middle. (side "left" = -X, which is the kit's right:
		# the Shop names it "side input right".)
		var ms := 1.0 if str(auto.get("side", "left")) == "left" else -1.0
		ins.append({"p": Vector3(-SORTER_SIDE * ms, 0, -0.1), "d": Vector3(ms, 0, 0), "s": length * 0.5 - 0.1, "name": "side"})
	return {"kind": "line", "length": length, "z0": z0, "in": ins, "out": outs}


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
	return Transform3D(Basis.IDENTITY, Vector3(0, 0, float(lb.get("z0", -length * 0.5)) + s))


static func belt_world(f: Dictionary) -> Dictionary:
	## {xf, lb, height} for a placed belt fixture — enough for anyone (clients too) to place things on it.
	var auto := auto_for(f)
	return {"xf": fixture_xf(f), "lb": belt_local(auto), "height": float(auto.get("height", 0.84))}


static func belt_point(geo: Dictionary, s: float) -> Transform3D:
	var local := path_point(geo.lb, s)
	local.origin.y += float(geo.height)
	return (geo.xf as Transform3D) * local


static func ports_of(type: String, xf: Transform3D, settings: Dictionary = {}) -> Array:
	## World ports of a belt (or stock tower) at `xf`: [{p, d, in: bool, name}] (used for placement snapping).
	var auto := def_of(type)
	if not settings.is_empty():
		auto = auto.duplicate()
		auto.merge(settings, true)
	if auto.get("kind", "") == "store":
		var lt := store_local(auto)
		return [{"p": xf * (lt.in_p as Vector3), "d": xf.basis * (lt.in_d as Vector3), "in": true, "name": "in"},
			{"p": xf * (lt.out_p as Vector3), "d": xf.basis * (lt.out_d as Vector3), "in": false, "name": "out"}]
	if auto.get("kind", "") != "belt":
		return []
	var lb := belt_local(auto)
	var out: Array = []
	for prt in lb.in:
		out.append({"p": xf * (prt.p as Vector3), "d": xf.basis * (prt.d as Vector3), "in": true, "name": str(prt.get("name", "in"))})
	for prt in lb.out:
		out.append({"p": xf * (prt.p as Vector3), "d": xf.basis * (prt.d as Vector3), "in": false, "name": prt.name})
	return out


static func store_local(auto: Dictionary) -> Dictionary:
	## Stock tower conveyor hatches: kit goes in on the front-left, sold units come out front-right.
	return {"in_p": Vector3(-0.35, 0, 0.62), "in_d": Vector3(0, 0, -1), "out_p": Vector3(0.35, 0, 0.62), "out_d": Vector3(0, 0, 1)}


static func local_rect(type: String, settings: Dictionary = {}) -> Rect2:
	## A fixture's floor rectangle in its own space (flexible bends trace their arc; others use footprint).
	var auto := def_of(type)
	if not settings.is_empty():
		auto = auto.duplicate()
		auto.merge(settings, true)
	if auto.get("path", "") == "arc" and str(auto.get("origin", "")) == "in":
		var lb := belt_local(auto)
		var hw := float(auto.get("half_width", 0.5))
		var r := Rect2(Vector2.ZERO, Vector2.ZERO)
		var first := true
		for i in 17:
			var pp := path_point(lb, float(lb.length) * i / 16.0)
			for off in [-hw, hw]:
				var q: Vector3 = pp.origin + pp.basis.x * off
				if first:
					r = Rect2(q.x, q.z, 0, 0)
					first = false
				else:
					r = r.expand(Vector2(q.x, q.z))
		return r
	var def := Content.fixture(type)
	var fp: Array = def.get("footprint", [1.0, 1.0])
	var back := float(def.get("back", float(fp[1]) * 0.5))
	var front := float(def.get("front", float(fp[1]) * 0.5))
	return Rect2(-float(fp[0]) * 0.5, -back, float(fp[0]), back + front)


func rebuild() -> void:
	belts.clear()
	towers.clear()
	arms.clear()
	machines.clear()
	monitors.clear()
	for fid in state.fixtures:
		var f: Dictionary = state.fixtures[fid]
		if not f.get("placed", false):
			continue
		var auto := auto_for(f)
		if auto.is_empty():
			continue
		var xf := fixture_xf(f)
		match auto.get("kind", ""):
			"store":
				var lt := store_local(auto)
				towers[fid] = {"xf": xf, "in_p": xf * (lt.in_p as Vector3), "in_d": xf.basis * (lt.in_d as Vector3),
					"out_p": xf * (lt.out_p as Vector3), "out_d": xf.basis * (lt.out_d as Vector3), "out_belt": ""}
			"belt":
				var lb := belt_local(auto)
				var ins: Array = []
				for prt in lb.in:
					ins.append({"p": xf * (prt.p as Vector3), "d": xf.basis * (prt.d as Vector3), "s": float(prt.get("s", 0.0)), "name": str(prt.get("name", "in"))})
				var outs: Array = []
				for prt in lb.out:
					outs.append({"p": xf * (prt.p as Vector3), "d": xf.basis * (prt.d as Vector3), "name": prt.name})
				belts[fid] = {"lb": lb, "xf": xf, "length": float(lb.length), "in": ins, "out": outs, "next": {}, "next_s": {},
					"speed": float(auto.get("speed", 0.6)), "dev": auto.get("device", ""), "height": float(auto.get("height", 0.84))}
			"arm":
				arms[fid] = {"xf": xf, "reach": float(auto.get("reach", 1.8)), "cycle": float(auto.get("cycle", 3.6))}
				state.register_station(fid, "arm")
				machines[fid] = "arm"
			"rack", "robot":
				machines[fid] = str(f.type)
			"monitor":
				monitors.append(fid)
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
	# Join out-ports to the in-ports they touch (main or side inputs), and conveyors to stock towers.
	var taken := {}
	for a in belts:
		for o in belts[a].out:
			for b in belts:
				if b == a or belts[a].next.has(o.name):
					continue
				for ip in belts[b].in:
					var key := "%s/%s" % [b, ip.name]
					if taken.has(key):
						continue
					if (ip.p as Vector3).distance_to(o.p) < PORT_SNAP and (ip.d as Vector3).dot(o.d) > 0.7:
						belts[a].next[o.name] = b
						belts[a].next_s[o.name] = float(ip.s)
						taken[key] = true
						break
			for t in towers:
				if not belts[a].next.has(o.name) and (towers[t].in_p as Vector3).distance_to(o.p) < PORT_SNAP and (towers[t].in_d as Vector3).dot(o.d) > 0.7:
					belts[a].next[o.name] = "tower:" + t
	for t in towers:
		for b in belts:
			var ip: Dictionary = belts[b].in[0]
			if (ip.p as Vector3).distance_to(towers[t].out_p) < PORT_SNAP and (ip.d as Vector3).dot(towers[t].out_d) > 0.7:
				towers[t].out_belt = b
	dirty = false


func free_ports() -> Array:
	## Open belt ports in world space (for placement snapping): [{p, d, in: bool, fid}]
	if dirty:
		rebuild()
	var used_in := {}
	for a in belts:
		for o in belts[a].next:
			used_in["%s/%s" % [belts[a].next[o], str(belts[a].next_s.get(o, 0.0))]] = true
	var out: Array = []
	for a in belts:
		for ip in belts[a].in:
			if not used_in.has("%s/%s" % [a, str(ip.s)]):
				out.append({"p": ip.p, "d": ip.d, "in": true, "fid": a})
		for o in belts[a].out:
			if not belts[a].next.has(o.name):
				out.append({"p": o.p, "d": o.d, "in": false, "fid": a})
	return out


# ================================================================= belt items

var _on_belt := {}            # belt fid -> [Item], rebuilt once per tick
var _on_belt_tick := -1
var _tick_no := 0


func items_on(fid: String) -> Array:
	if _on_belt_tick != _tick_no:
		_on_belt_tick = _tick_no
		_on_belt = {}
		for it: Item in state.items.values():
			if it.loc.get("t") == "belt":
				var k := str(it.loc.get("belt", ""))
				if not _on_belt.has(k):
					_on_belt[k] = []
				_on_belt[k].append(it)
	var out: Array = []
	for it: Item in _on_belt.get(fid, []):
		# Hand-overs during this tick may have moved it on.
		if it.loc.get("t") == "belt" and str(it.loc.get("belt", "")) == fid:
			out.append(it)
	for it: Item in _moved_on:
		if it.loc.get("t") == "belt" and str(it.loc.get("belt", "")) == fid and not out.has(it):
			out.append(it)
	out.sort_custom(func(a, b): return pos_of(a) > pos_of(b))
	return out


var _moved_on: Array = []     # items put on a belt during this tick (not in the index yet)


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


func put_on_belt(it: Item, fid: String, s: float = -1.0, sent_on: bool = false) -> void:
	## s = where the item's centre goes; by default it's set down wholly on the belt at the start.
	## sent_on: a person sent it on from a people's hatch (it rides past the pick face).
	_moved_on.append(it)
	var b: Dictionary = belts.get(fid, {})
	if s < 0.0:
		s = item_len(it) * 0.5 + 0.02
	var loc := {"t": "belt", "belt": fid, "s": s, "at": state.belt_clock, "v": float(b.get("speed", 0.6))}
	if sent_on:
		loc["sent"] = true
	state.set_loc(it, loc)


func belt_has_room(fid: String, it: Item, s0: float = -1.0) -> bool:
	## Is there room for `it` centred at s0 (default: wholly on the belt at its start)?
	if fid.begins_with("tower:"):
		return tower_free_slot(fid.substr(6), it) != ""
	if s0 < 0.0:
		s0 = item_len(it) * 0.5 + 0.02
	for other: Item in items_on(fid):
		var gap := (item_len(other) + item_len(it)) * 0.5 + 0.06
		var d := pos_of(other) - s0
		if d < gap and d > -gap * (1.0 if s0 > 0.05 else 0.0) - 0.0001:
			return false
	return true


func end_stop(fid: String, it: Item) -> float:
	## Where the front item waits at a closed end: wholly on the belt, front edge at the end.
	return float(belts[fid].length) - item_len(it) * 0.5 - 0.01


func load_point(fid: String, it: Item) -> float:
	## Where kit put on a belt's loading point is set down (wholly on the belt; on a people's hatch, wholly past
	## the pick face so it rides on instead of joining the queue for people).
	var b: Dictionary = belts.get(fid, {})
	if str(b.get("dev", "")) == "hatch":
		return float(b.length) * 0.5 + item_len(it) * 0.5 + 0.08
	return maxf(LOAD_S, item_len(it) * 0.5 + 0.02)


func pick_face(fid: String) -> float:
	## People's hatch: kit coming in stops with its front edge here and waits for a person (-1 = not a hatch).
	var b: Dictionary = belts.get(fid, {})
	return float(b.length) * 0.5 if str(b.get("dev", "")) == "hatch" else -1.0


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
	var face := pick_face(fid)
	for i in list.size():
		var it: Item = list[i]
		var s := pos_of(it)
		var ln := item_len(it)
		var limit := front_s - (front_len + ln) * 0.5 - 0.06
		var for_people: bool = face > 0.0 and not it.loc.get("sent", false)
		if for_people:
			# People's hatch: kit that came in waits at the pick face; only kit a person sends on rides out.
			limit = minf(limit, face - ln * 0.5 - 0.01)
		elif i == 0:
			# Run on to the end only if the next conveyor can take it; otherwise stop wholly on this belt.
			limit = length if _can_hand_over(fid, it) else end_stop(fid, it)
		if (b.dev == "scanner" or b.dev == "grader") and s >= length * 0.5 and not it.data.get("fx_" + fid, false):
			_device_effect(fid, b.dev, it)
		if i == 0 and not for_people and s >= length - EPS:
			if _hand_over(fid, it):
				continue
		if s >= limit - EPS:
			_set_motion(it, maxf(minf(s, limit), 0.0), 0.0)
		else:
			_set_motion(it, s, float(b.speed))
		front_s = minf(s, limit)
		front_len = ln


func _outs_for(fid: String, it: Item) -> Array:
	## Which exits this item may take, in order of preference.
	var b: Dictionary = belts[fid]
	match b.dev:
		"sorter":
			return _sorter_choices(fid, it)
		"splitter":
			if belt_mode(state, fid, "splitter") == "overflow":
				return ["left", "right"]       # fill the left until it backs up, then the right
			var first := str(state.stations.get(fid, {}).get("flip", "left"))
			return [first, "right" if first == "left" else "left"]
	return ["out"]


func _can_hand_over(fid: String, it: Item) -> bool:
	var b: Dictionary = belts[fid]
	if b.dev == "gate" and belt_mode(state, fid, "gate") == "hold":
		return false
	for o in _outs_for(fid, it):
		var nxt: String = b.next.get(o, "")
		if nxt != "" and (belts.has(nxt) or nxt.begins_with("tower:")) and belt_has_room(nxt, it, float(b.next_s.get(o, 0.0))):
			if b.dev == "splitter":
				_splitter_aim(fid, o)
			return true
	return false


func _hand_over(fid: String, it: Item) -> bool:
	var b: Dictionary = belts[fid]
	if b.dev == "gate" and belt_mode(state, fid, "gate") == "hold":
		return false
	for o in _outs_for(fid, it):
		var nxt: String = b.next.get(o, "")
		if nxt.begins_with("tower:"):
			var slot := tower_free_slot(nxt.substr(6), it)
			if slot != "":
				state.set_loc(it, {"t": "slot", "slot": slot})
				state.bump_stat("stored")
				BusinessStats.on_auto(state, "tower_in", nxt.substr(6))
				_handed_on(fid, o)
				return true
			continue
		if nxt != "" and belts.has(nxt) and belt_has_room(nxt, it, float(b.next_s.get(o, 0.0))):
			put_on_belt(it, nxt, float(b.next_s.get(o, 0.0)))
			if float(b.next_s.get(o, 0.0)) > 0.0:
				BusinessStats.on_auto(state, "merge", nxt)
			elif belts[nxt].dev == "hatch":
				BusinessStats.on_auto(state, "hatch_in", nxt)
			_handed_on(fid, o)
			return true
	return false


func _handed_on(fid: String, out: String) -> void:
	## Count what a device did with the item it just passed on (and flip a balancer to its other side).
	match str(belts[fid].dev):
		"sorter":
			BusinessStats.on_auto(state, "sort", fid)
		"gate":
			BusinessStats.on_auto(state, "gate", fid)
		"splitter":
			BusinessStats.on_auto(state, "split", fid)
			if belt_mode(state, fid, "splitter") == "alternate":
				var st := _station(fid, "splitter")
				st["flip"] = "right" if out == "left" else "left"
				st["aim"] = st.flip
				state.mark("station", fid)


func _splitter_aim(fid: String, out: String) -> void:
	## Which way the balancer's flap points: the side the waiting item is about to take (replicated so every
	## player sees the flap swing — only when it changes).
	var st := _station(fid, "splitter")
	if str(st.get("aim", "")) != out:
		st["aim"] = out
		state.mark("station", fid)


func _station(fid: String, type: String) -> Dictionary:
	if not state.stations.has(fid):
		state.register_station(fid, type)
	return state.stations[fid]


# ================================================================= stock towers

func tower_free_slot(tid: String, it: Item) -> String:
	## Towers keep flat kit (laptops, desktops, rack servers, mobiles…) on lift trays; not towers or monitors.
	if it.kind != Item.KIND_UNIT or it.unit == null:
		return ""
	if float(it.unit.model().get("visual", {}).get("h", 0.1)) > 0.16:
		return ""
	for k in TOWER_SLOTS:
		var sid := "%s:t%d" % [tid, k]
		if state.item_in_slot(sid) == null:
			return sid
	return ""


func _tick_towers() -> void:
	## Sold kit leaves the tower onto its out conveyor, oldest order first.
	for t in towers:
		var ob: String = towers[t].out_belt
		if ob == "" or not belts.has(ob):
			continue
		for k in TOWER_SLOTS:
			var it := state.item_in_slot("%s:t%d" % [t, k])
			if it and it.unit and it.unit.order_id != 0 and belt_has_room(ob, it):
				put_on_belt(it, ob)
				state.bump_stat("dispensed")
				BusinessStats.on_auto(state, "tower_out", t)
				break


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
				BusinessStats.on_auto(state, "scan", fid)
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
				BusinessStats.on_auto(state, "grader", fid)
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
	_arm_q[fid] = maxi(_last_cands - (0 if pick.is_empty() else 1), 0)
	if pick.is_empty():
		# Why it's standing still, for the line monitor: kit in reach but nowhere to put it (waiting on the next
		# machine) or nothing in reach at all (starved). Only sent when it changes.
		var why := "waiting" if _last_cands > 0 else "starved"
		if str(st.get("idle", "")) != why:
			st["idle"] = why
			state.mark("station", fid)
		return
	st["idle"] = ""
	var it := state.get_item(int(pick.item))
	sim._leave_slot_side_effects(it)
	var from_slot: String = it.loc.get("slot", "") if it.loc.get("t") == "slot" else ""
	state.set_loc(it, {"t": "arm", "arm": fid})
	if pick.has("from_pallet"):
		state.bump_stat("unloaded")      # pallet → line, no hands (lifetime counter)
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
	BusinessStats.on_auto(state, "arm", fid)
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
			var host := state.slot_station(sid)
			var stype: String = state.stations.get(host, {}).get("type", "")
			state.set_loc(it, {"t": "slot", "slot": sid})
			sim._on_entered_slot(it, sid, 0)
			if stype == "recycle":
				BusinessStats.on_auto(state, "recycle", host)
			elif stype == "stock_tower":
				BusinessStats.on_auto(state, "tower_in", host)
			return true
		"belt":
			if not belts.has(to.belt) or not belt_has_room(to.belt, it):
				return false
			put_on_belt(it, to.belt)
			BusinessStats.on_auto(state, "belt_in", to.belt)
			return true
	return false


func _find_pick(fid: String, arm: Dictionary) -> Dictionary:
	## The first thing in reach that wants moving, and where it should go.
	var candidates: Array = []     # [{item, from_belt}]
	# 1) Items waiting at the end of a belt that goes nowhere.
	for b in belts:
		var bd: Dictionary = belts[b]
		if bd.dev == "gate" and belt_mode(state, b, "gate") == "hold":
			continue          # a held gate holds against arms too
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
		elif it.unit and it.unit.order_id != 0 and state.stations.get(host, {}).get("type", "") in ["", "stock_tower"]:
			candidates.append({"item": it.id})      # sold stock on racking / in a tower → packing
	for it: Item in state.items.values():
		var pp = unload_pos(state, it)
		if pp == null or not _in_reach(arm, pp, 0.5):
			continue
		var top := _pallet_top_unit(it.id)
		if top:
			candidates.append({"item": top.id, "from_pallet": it.id})
	_last_cands = candidates.size()
	for c in candidates:
		var it := state.get_item(int(c.item))
		if it == null:
			continue
		var to := _choose_target(fid, arm, it, str(c.get("from_belt", "")))
		if not to.is_empty():
			c["to"] = to
			return c
	return {}


static func unload_pos(st: GameState, it: Item) -> Variant:
	## Where an arm can unload this pallet from: loose on the floor, or set down in a pallet unload bay
	## (not pallets stored in racking, on a truck or on the forks). null = not available to arms.
	if it.kind != Item.KIND_PALLET:
		return null
	match str(it.loc.get("t", "")):
		"loose":
			if it.loc.get("relocate", false):
				return null
			var p: Array = it.loc.get("p", [0, 0, 0])
			return Vector3(float(p[0]), 0.0, float(p[2]))
		"slot":
			var sid := str(it.loc.get("slot", ""))
			if Logistics.bay_station(st, sid) != "pallet_bay":
				return null
			var xf = Logistics.slot_world(st, sid)
			return (xf as Transform3D).origin if xf != null else null
	return null


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
	var job: Dictionary = state.stations.get(host, {}).get("job", {})
	if not job.is_empty() and int(job.get("unit", job.get("item", -1))) == it.id:
		return false     # a bench is still running on it
	if it.kind == Item.KIND_PACKAGE:
		return t in ["robot_pack", "pack"]
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
		# People in the loop: a person tests/images at a USB bench, repairs at the repair bench.
		"bench":
			var usb := state.station_slot_item(host, "usb")
			if usb and usb.kind == Item.KIND_USB_IMAGE:
				return u.imaged
			return u.tested
		"repair":
			return u.tested and not u.has_open_faults()
	return false


func _choose_target(fid: String, arm: Dictionary, it: Item, from_belt: String) -> Dictionary:
	## Where should this item go next? Specific stations first, then a belt in reach, then stock racking.
	var wants: Array = []
	if it.kind == Item.KIND_PACKAGE:
		wants = ["courier"]
	elif it.unit:
		var u := it.unit
		if u.order_id != 0:
			wants = ["robot_pack", "pack"]
		elif not u.tested:
			wants = ["rack_test", "bench_diag"]
		elif u.has_open_faults():
			# Only to the robot if it can fix something now (tier + part in the bins); else set aside on racking.
			if _robot_can_fix(u):
				# Wait in place for the repair robot rather than sending it away while the robot's busy.
				var rs := _free_slot_of_type(arm, "robot_repair", it)
				return {"kind": "slot", "slot": rs} if rs != "" else {}
			# Beyond the robot: a technician's repair bench, the recycling cage if it can never be fixed,
			# else set aside on racking for a person.
			wants = ["repair", "recycle", "stock"] if _unrepairable(u) else ["repair", "stock"]
		elif not u.imaged:
			wants = ["rack_image", "bench_image"]
		elif u.grade == "":
			wants = []          # → a belt (through the vision grader)
		else:
			wants = ["stock_tower", "stock"]
	for want in wants:
		var slot := _free_slot_of_type(arm, want, it)
		if slot != "":
			return {"kind": "slot", "slot": slot}
	# Its next station is right here but busy: leave it where it is (the queue backs up) rather than sending
	# it off down the line where nothing can do that job.
	for want in wants:
		if want in ["rack_test", "rack_image", "robot_pack", "courier"] and _type_in_reach(arm, want):
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


func _unrepairable(u: UnitData) -> bool:
	for f in u.open_faults():
		if Content.fault(f.id).get("unrepairable", false):
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
		var nm := str(sid).get_slice(":", 1)
		var ok := false
		match want:
			"courier":
				ok = stype == "courier"
			"bench_diag", "bench_image":
				# A USB test bench a person has set up: the right stick in its USB port.
				var usb := state.station_slot_item(host, "usb")
				ok = stype == "bench" and nm == "unit" and usb != null and usb.kind == (Item.KIND_USB_TEST if want == "bench_diag" else Item.KIND_USB_IMAGE)
			"repair", "pack":
				ok = stype == want and nm == "unit"
			"recycle":
				ok = stype == "recycle"
			"stock_tower":
				ok = stype == "stock_tower" and float(it.unit.model().get("visual", {}).get("h", 0.1)) <= 0.16 if it.unit else false
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
		if stype not in ["", "recycle", "stock_tower"] and not sim._station_accepts(stype, sid, it).ok:
			continue
		return sid
	return ""


# ================================================================= tick

func tick(dt: float) -> void:
	state.belt_clock += dt
	_tick_no += 1
	_moved_on.clear()
	if dirty:
		rebuild()
	_tick_load(dt)
	_tick_monitors(dt)
	if belts.is_empty() and arms.is_empty() and towers.is_empty():
		return
	_tick_towers()
	for fid in belts:
		_step_belt(fid)
		var waiting := state.item_in_slot(fid + ":load")
		if waiting:
			var s0 := load_point(fid, waiting)
			if belt_has_room(fid, waiting, s0):
				sim._leave_slot_side_effects(waiting)
				var hatch: bool = belts[fid].dev == "hatch"
				put_on_belt(waiting, fid, s0, hatch)
				BusinessStats.on_auto(state, "hatch_send" if hatch else "belt_in", fid)
	_think -= dt
	if _think <= 0.0:
		_think = 0.25
		for fid in arms:
			_tick_arm(fid)


# ================================================================= line monitoring (host publishes, anyone reads)

func _tick_load(dt: float) -> void:
	## Moving average of how busy each machine is, published to its station state every few seconds (only when
	## it has moved) so line monitors on every machine in the session can show load and pick the bottleneck.
	if machines.is_empty():
		return
	var k := 1.0 - exp(-dt / UTIL_TAU)
	for fid in machines:
		var st: Dictionary = state.stations.get(fid, {})
		var busy := 0.0
		match str(machines[fid]):
			"arm":
				busy = 0.0 if st.get("job", {}).is_empty() else 1.0
			"rack_test", "rack_image":
				busy = float(st.get("jobs", {}).size()) / float(maxi(int(_bays.get(fid, 6)), 1))
			_:
				busy = 0.0 if st.get("jobs", {}).is_empty() else 1.0
		_util[fid] = lerpf(float(_util.get(fid, 0.0)), clampf(busy, 0.0, 1.0), k)
	_util_pub -= dt
	if _util_pub > 0.0:
		return
	_util_pub = 3.0
	for fid in machines:
		if not state.stations.has(fid):
			continue
		var st: Dictionary = state.stations[fid]
		if str(machines[fid]).begins_with("rack"):
			var n := 0
			for sid in state.slot_defs:
				if str(sid).begins_with(fid + ":"):
					n += 1
			_bays[fid] = maxi(n, 1)
		var u := int(round(float(_util.get(fid, 0.0)) * 100.0))
		var q := int(_arm_q.get(fid, 0))
		if absi(int(st.get("util", -100)) - u) >= 2 or (u == 0 and int(st.get("util", 0)) != 0) or int(st.get("q", 0)) != q:
			st["util"] = u
			st["q"] = q
			state.mark("station", fid)


func _tick_monitors(dt: float) -> void:
	if monitors.is_empty():
		return
	_mon_pub -= dt
	if _mon_pub > 0.0:
		return
	_mon_pub = 4.0
	var rates := line_rates(state)
	for mid in monitors:
		var st := _station(mid, "monitor")
		if st.get("line", {}) != rates:
			st["line"] = rates
			state.mark("station", mid)


static func line_rates(st: GameState) -> Dictionary:
	## Machine moves per hour (rolling), the last eight working hours, and today's counts — from BusinessStats
	## (host only; clients read the copy published into each line monitor's station state).
	var a: Dictionary = st.stats.get("auto", {})
	var hours: Dictionary = a.get("hours", {})
	var day := st.day_index()
	var mod := st.minute_of_day()
	var h := int(mod / 60.0)
	var frac := fmod(mod, 60.0) / 60.0
	var cur := int(hours.get("%d_%d" % [day, h], 0))
	var prev := int(hours.get("%d_%d" % [day, h - 1], 0))
	var spark: Array = []
	for k in range(7, -1, -1):
		spark.append(int(hours.get("%d_%d" % [day, h - k], 0)))
	return {"mph": int(round(cur + prev * (1.0 - frac))), "spark": spark, "today": (a.get("today", {}) as Dictionary).duplicate()}


const STAGE_OF := {"rack_test": "test", "rack_image": "image", "robot_repair": "repair", "robot_pack": "pack", "grader": "grade"}
const STAGE_NAMES := {"test": "testing", "image": "wipe & imaging", "repair": "repair", "pack": "packing", "grade": "grading"}


static func _need_of(it: Item) -> String:
	## What an item on the line needs next ("" = nothing a machine does: stock, parcels for the courier).
	if it.unit == null:
		return ""
	match it.unit.stage():
		UnitData.STAGE_UNTESTED:
			return "test"
		UnitData.STAGE_FAULTY:
			return "repair"
		UnitData.STAGE_TO_IMAGE:
			return "image"
		UnitData.STAGE_TO_GRADE:
			return "grade"
		UnitData.STAGE_SOLD:
			return "pack"
	return ""


static func _machine_queues(st: GameState, stages: Dictionary, host_slots: Dictionary) -> Dictionary:
	## Kit waiting for each machine ({fid: stage} in, {fid: count} out): units that need its stage and can actually
	## get to it — riding a conveyor network one of its feeding arms reaches, held by that arm, on a pallet in
	## that arm's reach, or finished in another machine the arm serves. A machine no arm feeds (people load it)
	## counts kit on the conveyor network it sits on (the vision grader), else nothing.
	var links := belt_links(st)
	var comp := {}
	var n := 0
	var belt_ports := {}
	for fid in st.fixtures:
		var f: Dictionary = st.fixtures[fid]
		if not f.get("placed", false) or def_of(str(f.get("type", ""))).get("kind", "") != "belt":
			continue
		belt_ports[fid] = ports_of(f.type, fixture_xf(f), f.get("bend", {}))
		if comp.has(fid):
			continue
		comp[fid] = n
		var todo: Array = [fid]
		while not todo.is_empty():
			var cur: String = todo.pop_back()
			for nb in links.get(cur, {}):
				if not comp.has(nb):
					comp[nb] = n
					todo.append(nb)
		n += 1
	# Who needs what, where.
	var comp_need := {}      # comp -> {stage: n}
	var arm_need := {}       # arm fid -> {stage: n}
	var pallet_need := {}    # pallet item id -> {stage: n}
	for it: Item in st.items.values():
		var need := _need_of(it)
		if need == "":
			continue
		var t: String = str(it.loc.get("t", ""))
		var bucket: Dictionary = {}
		if t == "belt" and comp.has(str(it.loc.get("belt", ""))):
			bucket = comp_need.get_or_add(comp[str(it.loc.belt)], {})
		elif t == "arm":
			bucket = arm_need.get_or_add(str(it.loc.get("arm", "")), {})
		elif t == "slot" and str(it.loc.get("slot", "")).begins_with("i"):
			bucket = pallet_need.get_or_add(int(str(it.loc.slot).substr(1).get_slice(":", 0)), {})
		else:
			continue
		bucket[need] = int(bucket.get(need, 0)) + 1
	var done_need := {}      # machine fid -> {stage: n} (finished there, waiting to move on)
	for m in stages:
		var jobs: Dictionary = st.stations.get(m, {}).get("jobs", {})
		for sid in host_slots.get(m, []):
			var it := st.item_in_slot(sid)
			if it and not jobs.has(str(sid).get_slice(":", 1)):
				var need := _need_of(it)
				if need != "" and need != stages[m]:
					var d: Dictionary = done_need.get_or_add(m, {})
					d[need] = int(d.get(need, 0)) + 1
	# What each arm reaches.
	var feeders := {}        # machine fid -> [arm reach info]
	for afid in st.fixtures:
		var af: Dictionary = st.fixtures[afid]
		if not af.get("placed", false) or def_of(str(af.get("type", ""))).get("kind", "") != "arm":
			continue
		var base := fixture_xf(af).origin
		var reach := float(def_of(af.type).get("reach", 1.8))
		var info := {"arm": afid, "comps": {}, "machines": [], "pallets": []}
		for b in belt_ports:
			for prt in belt_ports[b]:
				if Vector2(prt.p.x - base.x, prt.p.z - base.z).length() <= reach:
					info.comps[comp[b]] = true
		for m in stages:
			var mf: Dictionary = st.fixtures.get(m, {})
			var fp: Array = Content.fixture(str(mf.get("type", ""))).get("footprint", [1, 1])
			var mp := fixture_xf(mf).origin
			if Vector2(mp.x - base.x, mp.z - base.z).length() <= reach + minf(float(fp[0]), float(fp[1])) * 0.5:
				info.machines.append(m)
		for it: Item in st.items.values():
			var pp = unload_pos(st, it)
			if pp != null and Vector2((pp as Vector3).x - base.x, (pp as Vector3).z - base.z).length() <= reach + 0.5:
				info.pallets.append(it.id)
		for m in info.machines:
			feeders.get_or_add(m, []).append(info)
	var out := {}
	for m in stages:
		var stage: String = stages[m]
		var q := 0
		if not feeders.has(m):
			if comp.has(m):
				q = int(comp_need.get(comp[m], {}).get(stage, 0))
			out[m] = q
			continue
		var comps := {}
		var others := {}
		var pallets := {}
		for info in feeders[m]:
			comps.merge(info.comps)
			q += int(arm_need.get(info.arm, {}).get(stage, 0))
			for o in info.machines:
				if o != m:
					others[o] = true
			for p in info.pallets:
				pallets[p] = true
		for c in comps:
			q += int(comp_need.get(c, {}).get(stage, 0))
		for o in others:
			q += int(done_need.get(o, {}).get(stage, 0))
		for p in pallets:
			q += int(pallet_need.get(p, {}).get(stage, 0))
		out[m] = q
	return out


static func line_status(st: GameState, monitor_fid: String = "") -> Dictionary:
	## Everything a line monitor shows, from replicated state only (works on every player's machine):
	##   machines: [{fid, label, kind, state: busy|waiting|starved|idle, detail, util (-1 = n/a), queue}]
	##   bottleneck (fid or ""), bottleneck_text, flow: flowing|waiting|stopped|idle,
	##   on_belts / moving / stopped, busy / total, mph, spark, today.
	var host_slots := {}
	for sid in st.slot_defs:
		var h := str(sid).get_slice(":", 0)
		if not host_slots.has(h):
			host_slots[h] = []
		host_slots[h].append(str(sid))
	var on_belt := {}
	var moving := 0
	var stopped := 0
	var need := {"test": 0, "image": 0, "repair": 0, "pack": 0, "grade": 0}
	var line_items := 0
	for it: Item in st.items.values():
		var t: String = str(it.loc.get("t", ""))
		if t == "belt":
			var k := str(it.loc.get("belt", ""))
			if not on_belt.has(k):
				on_belt[k] = []
			on_belt[k].append(it)
			if float(it.loc.get("v", 0.0)) > 0.0:
				moving += 1
			else:
				stopped += 1
		elif t != "arm":
			continue
		line_items += 1
		var n := _need_of(it)
		if n != "":
			need[n] += 1
	var rows: Array = []
	var have_stage := {}
	var hatch_wait := 0
	var hatch_label := ""
	for fid in st.fixtures:
		var f: Dictionary = st.fixtures[fid]
		if not f.get("placed", false):
			continue
		var auto := def_of(str(f.get("type", "")))
		var kind := str(auto.get("kind", ""))
		var dev := str(auto.get("device", ""))
		var sd: Dictionary = st.stations.get(fid, {})
		var label := str(f.get("label", Content.fixture(f.type).get("name", f.type)))
		var row := {"fid": fid, "label": label, "kind": kind if dev == "" else dev, "state": "idle", "detail": "", "util": int(sd.get("util", -1)), "queue": 0}
		match kind:
			"arm":
				if not sd.get("job", {}).is_empty():
					row.state = "busy"
					row.detail = "Moving kit"
				elif str(sd.get("idle", "")) == "waiting":
					row.state = "waiting"
					row.detail = "Kit in reach — next stop full"
				else:
					row.state = "starved"
					row.detail = "Nothing in reach to move"
				row.queue = int(sd.get("q", 0))
			"rack", "robot":
				var jobs: Dictionary = sd.get("jobs", {})
				var cap := 0
				var done := 0
				for sid in host_slots.get(fid, []):
					if str(sid).ends_with(":out"):
						if st.item_in_slot(sid) != null:
							done += 1          # a parcel nobody has collected yet
							line_items += 1
						continue
					cap += 1
					var it := st.item_in_slot(sid)
					if it and not jobs.has(str(sid).get_slice(":", 1)):
						done += 1
						line_items += 1
						var n2 := _need_of(it)
						if n2 != "" and n2 != STAGE_OF.get(f.type, ""):
							need[n2] += 1
					elif it:
						line_items += 1
				var stage := str(STAGE_OF.get(f.type, ""))
				have_stage[stage] = true
				row["stage"] = stage
				row["cap"] = maxi(cap, 1)
				row["jobs"] = jobs.size()
				if not jobs.is_empty():
					row.state = "busy"
					row.detail = ("%d/%d bays running" % [jobs.size(), cap]) if kind == "rack" else "Working"
					if done > 0:
						row.detail += " · %d done" % done
				elif done > 0:
					row.state = "waiting"
					row.detail = str(sd.get("msg", "")) if str(sd.get("msg", "")) != "" and kind == "robot" else "%d finished, waiting to be collected" % done
				else:
					row.state = "starved"
					row.detail = "Empty — waiting for kit"
			"store":
				var n3 := 0
				for sid in host_slots.get(fid, []):
					if st.item_in_slot(sid) != null:
						n3 += 1
				row.state = "waiting" if n3 >= TOWER_SLOTS else "idle"
				row.detail = "%d/%d trays" % [n3, TOWER_SLOTS] + (" — full" if n3 >= TOWER_SLOTS else "")
			"belt":
				if not dev in MACHINE_DEVICES:
					continue
				if dev == "grader":
					have_stage["grade"] = true
					row["stage"] = "grade"
				var list: Array = on_belt.get(fid, [])
				var mv := 0
				for it: Item in list:
					if float(it.loc.get("v", 0.0)) > 0.0:
						mv += 1
				if dev == "hatch":
					var waiting := 0
					for it: Item in list:
						if not it.loc.get("sent", false) and float(it.loc.get("v", 0.0)) == 0.0:
							waiting += 1
					if waiting > 0:
						row.state = "waiting"
						row.detail = "%d waiting for a person" % waiting
						hatch_wait += waiting
						hatch_label = label
					elif mv > 0:
						row.state = "busy"
						row.detail = "Sending kit on"
					else:
						row.state = "starved"
						row.detail = "Nothing for people yet"
				elif dev == "gate" and belt_mode(st, fid, "gate") == "hold":
					row.state = "waiting"
					row.detail = "Holding the line (%d held)" % list.size()
				elif mv > 0:
					row.state = "busy"
					row.detail = "%d passing" % mv
				elif not list.is_empty():
					row.state = "waiting"
					row.detail = "%d stopped — line backed up" % list.size()
				else:
					row.state = "starved"
					row.detail = "Nothing coming through"
				if dev == "splitter":
					row.detail += " · " + ("overflow, left first" if belt_mode(st, fid, "splitter") == "overflow" else "alternating")
			_:
				continue
		rows.append(row)
	# People can do the jobs too: benches with the right stick, repair benches, packing tables, grading desks.
	for sid in st.stations:
		if not st.fixtures.get(sid, {}).get("placed", false):
			continue
		match str(st.stations[sid].get("type", "")):
			"bench":
				var usb := st.station_slot_item(sid, "usb")
				if usb:
					have_stage["image" if usb.kind == Item.KIND_USB_IMAGE else "test"] = true
			"repair":
				have_stage["repair"] = true
			"pack":
				have_stage["pack"] = true
			"grade":
				have_stage["grade"] = true
	# Nothing waiting anywhere = the line is idle, not starved.
	if line_items == 0:
		for row in rows:
			if row.state == "starved":
				row.state = "idle"
	# Queues in front of each machine, then the bottleneck: the busiest machine with work waiting for it.
	var best := ""
	var best_score := -1.0
	var best_row := {}
	var busy := 0
	var waiting_n := 0
	var stages := {}
	for row in rows:
		if row.has("stage") and str(row.stage) != "":
			stages[row.fid] = row.stage
	var queues := _machine_queues(st, stages, host_slots) if not stages.is_empty() else {}
	for row in rows:
		if row.state == "busy":
			busy += 1
		elif row.state == "waiting":
			waiting_n += 1
		if stages.has(row.fid):
			row.queue = int(queues.get(row.fid, 0))
		var util := float(row.util)
		if util < 0.0:
			continue
		var score := util
		if row.queue > 0:
			score += 20.0 + minf(float(row.queue), 10.0) * 3.0
		if (util >= 55.0 or (row.state == "busy" and row.queue > 0)) and score > best_score:
			best_score = score
			best = row.fid
			best_row = row
	var text := ""
	for stage in ["test", "repair", "image", "grade", "pack"]:
		if int(need[stage]) > 0 and not have_stage.has(stage):
			text = "Nothing on the line does %s — %d unit%s waiting. Add a machine (or a person's bench) for it." % [STAGE_NAMES[stage], need[stage], "" if need[stage] == 1 else "s"]
			best = ""
			break
	if text == "" and best != "":
		text = "%s — %d%% busy" % [best_row.label, int(best_row.util)]
		if int(best_row.queue) > 0:
			text += ", %d waiting for it" % int(best_row.queue)
		text += ". " + ("Add another one or speed up what feeds it." if best_row.kind != "arm" else "Add an arm so it has less to do.")
	elif text == "" and hatch_wait > 0:
		text = "%d waiting for a person at %s." % [hatch_wait, hatch_label]
	elif text == "":
		text = "Line flowing — no bottleneck." if busy > 0 or moving > 0 else ("Line idle — nothing to do." if line_items == 0 else "Kit is stopped — check the machines marked WAITING.")
	var flow := "idle"
	if line_items > 0:
		flow = "flowing" if busy > 0 or moving > 0 else "stopped"
		if flow == "flowing" and (waiting_n > 0 or best_score >= 90.0):
			flow = "waiting"
	var rates: Dictionary = st.stations.get(monitor_fid, {}).get("line", {})
	if rates.is_empty() and not st.stats.is_empty():
		rates = line_rates(st)
	return {"machines": rows, "bottleneck": best, "bottleneck_text": text, "flow": flow,
		"on_belts": moving + stopped, "moving": moving, "stopped": stopped, "busy": busy, "total": rows.size(),
		"mph": int(rates.get("mph", 0)), "spark": rates.get("spark", []), "today": rates.get("today", {})}
