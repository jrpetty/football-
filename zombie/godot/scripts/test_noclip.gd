extends SceneTree
## The no-clip audit: nothing walks through anything.
##   godot --headless --path zombie/godot -s res://scripts/test_noclip.gd [-- --only=a,d,p --quick --data]
## Runs zombies and the player through the real world data and counts every frame in which a body crosses a
## wall, stands inside geometry, floats or sinks, overlaps another body, or is stuck. It also checks the data:
## everything you can see that is big enough to matter must have something in WorldData that blocks it.
## Exit code 1 when any check counts a violation.
##
## Tolerances (metres):
##   wall crossing     the step from last frame's position to this one may not cut through a solid box that
##                     rises more than STEP above the feet (shrunk by 3 cm, so sliding along a face is fine)
##   penetration       body radius 0.3 x scale (zombies), 0.32 (player): at most TOL_PEN into a wall or a trunk
##   ground            upright bodies: feet within TOL_Y of the ground; lying bodies: within TOL_LIE along their length
##   overlap           two upright zombies never closer than MIN_PAIR; no zombie closer than MIN_PLAYER to the player
##   stuck             wants to move (chase / investigate) and covers under 0.35 m in STUCK_T seconds

const ZScript := preload("res://scripts/zombies.gd")
const STEP := 0.5
const TOL_PEN := 0.05
const TOL_Y := 0.06
const TOL_LIE := 0.10
const MIN_PAIR := 0.40
const MIN_PLAYER := 0.50
const STUCK_T := 4.0

var W: Node
var zs
var P
var host := {}
var town := {}
var doors := {}
var windows := {}
var bld := {}
var res := {}                 # check -> {n, max, by: {scenario: n}, ex: {scenario: [..]}}
var scen := ""
var zf := {}                  # scenario -> zombie-frames audited
var note := {}                # scenario -> free-form facts
var last := {}
var stuck := {}
var stuck_sec := {}
var nn_hist := {}             # scenario -> [counts by nearest-neighbour distance bin]
var t_now := 0.0
var frame := 0
var only: Array = []
var quick := false
var started := false
var rng := RandomNumberGenerator.new()

func _process(_d: float) -> bool:
	if started:
		return true
	started = true
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--only="): only = Array(a.substr(7).split(","))
		if a == "--quick": quick = true
	W = root.get_node("WorldData")
	P = load("res://scripts/player.gd").new()
	rng.seed = 4242
	town = JSON.parse_string(FileAccess.get_file_as_string("res://assets/world/town.json"))
	for b in town.buildings: bld[int(b.id)] = b
	setup_structures()
	var t0 := Time.get_ticks_msec()
	if want("data"): audit_data()
	if want("p"): s_player()
	if want("d"): s_crowd()
	if want("a"): s_horde_town()
	if want("b"): s_houses()
	if want("c"): s_forest()
	if want("e"): s_populate()
	if want("f"): s_turned()
	var bad := report()
	print("\n(%.0f s)" % ((Time.get_ticks_msec() - t0) / 1000.0))
	if zs != null:
		zs.clear(); root.remove_child(zs); zs.free()
	quit(1 if bad > 0 else 0)
	return true

func want(k: String) -> bool:
	return only.is_empty() or only.has(k)

# ------------------------------------------------------------------ bookkeeping
func viol(check: String, v: float, at: Vector3, what := "") -> void:
	var r = res.get(check)
	if r == null:
		r = {"n": 0, "max": 0.0, "by": {}, "ex": {}}
		res[check] = r
	r.n += 1
	r.by[scen] = int(r.by.get(scen, 0)) + 1
	var ex: Array = r.ex.get(scen, [])
	if ex.size() < 3 or absf(v) > absf(r.max):
		if ex.size() >= 5: ex.pop_front()
		ex.append("(%.2f, %.2f, %.2f) %.2f %s" % [at.x, at.y, at.z, v, what])
		r.ex[scen] = ex
	if absf(v) > absf(r.max): r.max = v

func fact(k: String, v) -> void:
	if not note.has(scen): note[scen] = {}
	note[scen][k] = v

func bump(k: String, n := 1) -> void:
	if not note.has(scen): note[scen] = {}
	note[scen][k] = int(note[scen].get(k, 0)) + n

func report() -> int:
	print("\n================ NO-CLIP AUDIT ================")
	for s in note:
		print("  %-10s %s  (%d zombie-frames)" % [s, note[s], int(zf.get(s, 0))])
	for s in nn_hist:
		var h: Array = nn_hist[s]
		var tot := 0
		for v in h: tot += v
		if tot > 0:
			print("  %-10s nearest-neighbour distance in the press: <0.3 m %.2f%%  0.3-0.4 %.2f%%  0.4-0.5 %.1f%%  0.5-0.6 %.1f%%  0.6-0.8 %.1f%%  (of %d samples)" % [s, h[0] * 100.0 / tot, h[1] * 100.0 / tot, h[2] * 100.0 / tot, h[3] * 100.0 / tot, h[4] * 100.0 / tot, tot])
	var keys: Array = res.keys(); keys.sort()
	var bad := 0
	print("")
	for k in keys:
		var r: Dictionary = res[k]
		var soft: bool = String(k).begins_with("data~")
		if not soft: bad += r.n
		print("%s %-46s %7d   worst %.2f   %s" % ["note" if soft else "FAIL", k, r.n, r.max, r.by])
		for s in r.ex:
			for e in r.ex[s]: print("        %s %s" % [s, e])
	if bad == 0: print("ALL CLEAR: no violations")
	else: print("\nVIOLATIONS: %d" % bad)
	return bad

# ------------------------------------------------------------------ the town's doors and windows, as World and the game treat them
func setup_structures() -> void:
	for d in town.doors:
		var box = W.export_box.get(int(d.box))
		if box == null: continue
		var ref := {"kind": "door", "id": int(d.id), "data": d, "box": box, "open": bool(d.open), "hp": float(d.hp)}
		box.ref = ref; box.tag = "door"
		doors[int(d.id)] = ref
	for w in town.windows:
		var box = W.export_box.get(int(w.box))
		if box == null: continue
		var ref := {"kind": "window", "id": int(w.id), "data": w, "box": box, "broken": false}
		box.ref = ref
		windows[int(w.id)] = ref
	reset_structures()

func reset_structures() -> void:
	for id in doors:
		var d: Dictionary = doors[id]
		d.open = bool(d.data.open); d.hp = float(d.data.hp)
		W.set_solid(d.box, not d.open, 30)
	for id in windows:
		var w: Dictionary = windows[id]
		w.broken = false
		W.set_solid(w.box, true)
		W.mark_breakable(w.box, 45, w)

func on_structure(ref, dmg) -> void:
	if not (ref is Dictionary): return
	if ref.get("kind", "") == "door" and not ref.open:
		ref.hp -= dmg
		if ref.hp <= 0.0:
			ref.open = true; W.set_solid(ref.box, false, 30); bump("doors broken")
	elif ref.get("kind", "") == "window" and not ref.broken:
		ref.broken = true; W.set_solid(ref.box, false); bump("windows broken")

func fresh(seed := 5) -> void:
	if zs != null:
		zs.clear(); root.remove_child(zs); zs.free()
	zs = ZScript.new()
	root.add_child(zs)
	zs.seed_rng(seed)
	zs.director_enabled = false
	zs.structure_hit.connect(on_structure)
	zs.player_attacked.connect(func(_z, _d): bump("blows on the player"))
	last.clear(); stuck.clear()
	host = {"player_pos": Vector3.ZERO, "player_noise": 0.0, "player_visibility": 1.0, "player_alive": true, "player_inside": false,
		"player_bleeding": false, "base_center": null, "base_heat": 0.0, "daylight": 1.0, "hours": 12.0, "day": 9, "luck": 0.0}
	reset_structures()

func place_player(x: float, z: float, y := INF) -> void:
	P.pos = Vector3(x, W.ground_y(x, z, y if y != INF else W.height_at(x, z) + 0.6), z)
	P.vel = Vector3.ZERO; P.fall_vel = 0.0; P.on_ground = true; P.auto_dir = Vector3.ZERO; P._stand_y = P.pos.y
	pl_prev = null
	sync_host()

func sync_host() -> void:
	host["player_pos"] = P.pos; host["player_eye"] = P.pos + Vector3(0, 1.6, 0); host["camera_pos"] = P.pos + Vector3(0, 1.6, 0)
	var fl: Array = W.floor_at(P.pos.x, P.pos.z)
	host["player_inside"] = not fl.is_empty() and absf(float(fl[4]) - P.pos.y) < 1.0

# ------------------------------------------------------------------ geometry, independent of the movement code
func is_box(o) -> bool:
	return o.get("tag") != null

## does this thing block a body whose feet are at `feet`?
func blocks(o, feet: float) -> bool:
	if is_box(o):
		if o.max.y <= feet + (0.57 if o.tag == "foundation" else STEP) or o.min.y >= feet + 1.5:
			return false
		var lk = o.get("link")                      # the sill under a window is open once the window is broken
		if lk != null and not lk.solid:
			return false
		return true
	return o.y1 > feet + STEP and o.y0 < feet + 1.5

func what(o) -> String:
	if o == null: return ""
	if is_box(o): return "%s box (%.2f..%.2f, %.2f..%.2f)" % [o.tag, o.min.x, o.max.x, o.min.z, o.max.z]
	return "%s cylinder r %.2f at (%.2f, %.2f)" % [o.kind if o.kind != "" else "post", o.r, o.x, o.z]

## how far a body of radius r at p reaches into the worst blocker: [depth, object]. Depth > r: the centre is inside.
func pen_at(p: Vector3, r: float) -> Array:
	var worst := -1.0; var wo = null
	for o in W.query(p.x - r, p.z - r, p.x + r, p.z + r):
		if not blocks(o, p.y):
			continue
		var d := 0.0
		if is_box(o):
			var cx := clampf(p.x, o.min.x, o.max.x); var cz := clampf(p.z, o.min.z, o.max.z)
			var dx := p.x - cx; var dz := p.z - cz
			if dx == 0.0 and dz == 0.0:
				d = -minf(minf(p.x - o.min.x, o.max.x - p.x), minf(p.z - o.min.z, o.max.z - p.z))
			else:
				d = sqrt(dx * dx + dz * dz)
			d = r - d
		else:
			d = r + o.r - Vector2(p.x - o.x, p.z - o.z).length()
		if d > worst:
			worst = d; wo = o
	return [worst, wo]

## the blocker a straight step from a to b cuts through, or null
func crossing(a: Vector3, b: Vector3):
	var dx := b.x - a.x; var dz := b.z - a.z
	for o in W.query(minf(a.x, b.x) - 0.05, minf(a.z, b.z) - 0.05, maxf(a.x, b.x) + 0.05, maxf(a.z, b.z) + 0.05):
		if not blocks(o, minf(a.y, b.y)) and not blocks(o, maxf(a.y, b.y)):
			continue
		if is_box(o):
			var e := 0.03
			var x0: float = o.min.x + e; var x1: float = o.max.x - e; var z0: float = o.min.z + e; var z1: float = o.max.z - e
			if x1 <= x0:
				x0 = (o.min.x + o.max.x) * 0.5; x1 = x0
			if z1 <= z0:
				z0 = (o.min.z + o.max.z) * 0.5; z1 = z0
			var t0 := 0.0; var t1 := 1.0; var ok := true
			for k in 2:
				var d := dx if k == 0 else dz; var s := a.x if k == 0 else a.z
				var lo := x0 if k == 0 else z0; var hi := x1 if k == 0 else z1
				if absf(d) < 1e-9:
					if s < lo or s > hi: ok = false
				else:
					var ta := (lo - s) / d; var tb := (hi - s) / d
					if ta > tb:
						var tt := ta; ta = tb; tb = tt
					t0 = maxf(t0, ta); t1 = minf(t1, tb)
					if t0 > t1: ok = false
				if not ok: break
			if ok: return o
		elif o.r >= 0.12:
			# through the core of a trunk or a post
			var l2 := dx * dx + dz * dz
			var t := 0.0 if l2 < 1e-9 else clampf(((o.x - a.x) * dx + (o.z - a.z) * dz) / l2, 0.0, 1.0)
			if Vector2(a.x + dx * t - o.x, a.z + dz * t - o.z).length() < o.r * 0.5:
				return o
	return null

## where the feet of a body at p should be: [height, what it stands on]
func ground_expected(p: Vector3, r: float) -> float:
	var g: float = W.height_at(p.x, p.z)
	for f in W.floors + (W.decks if W.get("decks") != null else []):
		if p.x > f[0] and p.x < f[2] and p.z > f[1] and p.z < f[3] and float(f[4]) > g and p.y > float(f[4]) - 0.6:
			g = f[4]
	var top: float = W.resolve(p, r, 1.7, 0.45).ground_y          # a low thing it may stand on (a pallet, a step)
	if top > g and absf(p.y - top) < absf(p.y - g):
		g = top
	return g

## may the feet be at height y here? Yes within TOL of the ground; and within 0.6 m of a step (a floor edge, a
## porch, a low thing) anywhere between the two levels, since a step is taken over a few frames rather than in one.
func feet_error(p: Vector3, r: float) -> float:
	var g := ground_expected(p, r)
	var lo := g; var hi := g
	var tp: float = W.height_at(p.x, p.z)
	for d in [Vector3(0.3, 0, 0), Vector3(-0.3, 0, 0), Vector3(0, 0, 0.3), Vector3(0, 0, -0.3), Vector3(0.6, 0, 0), Vector3(-0.6, 0, 0), Vector3(0, 0, 0.6), Vector3(0, 0, -0.6)]:
		var q: Vector3 = p + d
		var gq := ground_expected(q, r)
		if absf((gq - g) - (W.height_at(q.x, q.z) - tp)) > 0.1:       # a step, not the lie of the land
			lo = minf(lo, gq); hi = maxf(hi, gq)
	if p.y < lo: return p.y - lo
	if p.y > hi: return p.y - hi
	return 0.0

func under_floor(p: Vector3) -> float:
	for f in W.floors:
		if p.x > f[0] + 0.15 and p.x < f[2] - 0.15 and p.z > f[1] + 0.15 and p.z < f[3] - 0.15 and p.y < float(f[4]) - 0.6:
			return float(f[4]) - p.y
	return 0.0

# ------------------------------------------------------------------ the audit of one frame
func audit(dt: float) -> void:
	t_now += dt; frame += 1
	var pp: Vector3 = host.player_pos
	var press: Array = []
	var n := 0
	for z in zs.list:
		if z.frozen:
			continue
		n += 1
		var p: Vector3 = z.pos
		var id: int = z.id
		var prev = last.get(id)
		last[id] = p
		var st: StringName = z.state
		var climbing: bool = float(z.get("climb") if z.get("climb") != null else 0.0) > 0.0
		var rb: float = 0.3 * z.scale
		var lying: bool = z.crawler or st == &"down" or st == &"dead" or (st == &"dormant" and z.lying) or (st == &"rise" and z.down_stage < 2)
		var moved: bool = prev == null or prev != p
		if moved:
			if prev != null:
				var hd := Vector2(p.x - prev.x, p.z - prev.z).length()
				if hd > 1e-4:
					var o = crossing(prev, p)
					if o != null: viol("1 zombie crossed a wall", hd, p, "%s %s from (%.2f, %.2f) through %s" % [st, zs.TYPE_NAMES[z.type], prev.x, prev.z, what(o)])
				if hd > 12.0 * dt + 0.45: viol("1 zombie teleported", hd, p, "%s in one frame" % st)
			var pn := pen_at(p, rb)
			if true:
				if pn[0] > rb + 0.02: viol("2 zombie centre inside geometry", pn[0] - rb, p, "%s %s in %s" % [st, zs.TYPE_NAMES[z.type], what(pn[1])])
				elif pn[0] > TOL_PEN: viol("2 zombie pressed into geometry", pn[0], p, "%s %s into %s" % [st, zs.TYPE_NAMES[z.type], what(pn[1])])
			var uf := under_floor(p)
			if uf > 0.0: viol("3 zombie under a floor", uf, p, String(st))
			elif climbing:
				# over a window sill: there must be a broken window right here
				var near_win := false
				for wid in windows:
					var w: Dictionary = windows[wid]
					if w.broken and absf(float(w.data.cx) - p.x) < 1.6 and absf(float(w.data.cz) - p.z) < 1.6: near_win = true
				if not near_win: viol("3 zombie climbing where there is no broken window", z.climb, p, String(st))
			else:
				if not lying:
					var err := feet_error(p, rb)
					if absf(err) > TOL_Y: viol("3 zombie feet off the ground" if err > 0.0 else "3 zombie feet in the ground", err, p, "%s speed %.1f" % [st, z.vel.length()])
					# a jump in height that the lie of the land does not explain
					if prev != null and absf(p.y - prev.y) > 5.0 * dt + 0.02 and absf(p.y - prev.y) > 1.5 * absf(W.height_at(p.x, p.z) - W.height_at(prev.x, prev.z)) + 0.05:
						viol("3 zombie vertical snap", p.y - prev.y, p, String(st))
				else:
					lie_check(z, p)
		# stuck
		var wants: bool = (st == &"chase" or st == &"investigate") and z.want > 0.0 and not climbing
		if not wants:
			stuck.erase(id)
		else:
			var s = stuck.get(id)
			if s == null or p.distance_to(s[0]) > 0.35:
				stuck[id] = [p, t_now, false]
			elif t_now - float(s[1]) > STUCK_T:
				stuck_sec[scen] = float(stuck_sec.get(scen, 0.0)) + dt
				if not s[2]:
					s[2] = true
					viol("5 zombie stuck", t_now - float(s[1]), p, "%s %s, quarry %.1f m away" % [st, zs.TYPE_NAMES[z.type], Vector2(p.x - pp.x, p.z - pp.z).length()])
		if st != &"dead":
			var dpl := Vector2(p.x - pp.x, p.z - pp.z).length()
			if dpl < MIN_PLAYER and absf(p.y - pp.y) < 1.5 and bool(host.player_alive) and (z.crawler or not lying): viol("4 zombie inside the player", dpl, p, String(st))
			if dpl < 14.0 and not lying and frame % 6 == 0: press.append(z)
	zf[scen] = int(zf.get(scen, 0)) + n
	if press.size() > 1:
		if not nn_hist.has(scen): nn_hist[scen] = [0, 0, 0, 0, 0]
		var h: Array = nn_hist[scen]
		for i in press.size():
			var a = press[i]
			var best := 9.0
			for j in press.size():
				if i == j: continue
				var b = press[j]
				var d: float = Vector2(a.pos.x - b.pos.x, a.pos.z - b.pos.z).length()
				if d < best and absf(a.pos.y - b.pos.y) < 1.0: best = d
				if j > i and d < MIN_PAIR and absf(a.pos.y - b.pos.y) < 1.0:
					viol("4 zombies inside each other", d, a.pos, "%s + %s" % [a.state, b.state])
			if best < 0.8:
				h[0 if best < 0.3 else 1 if best < 0.4 else 2 if best < 0.5 else 3 if best < 0.6 else 4] += 1

## a body on the ground: its length must follow the surface
func lie_check(z, p: Vector3) -> void:
	var nrm: Vector3 = z.get("normal") if z.get("normal") != null else Vector3.UP
	var f := Vector3(sin(z.yaw), 0, cos(z.yaw))
	var off := 0.0
	if z.state == &"down" or (z.state == &"dead" and (z.anim == &"lie_back" or z.anim == &"lie_front")):
		off = -0.55 if z.down_face >= 0 else 0.55
	var worst := 0.0
	for s in [-0.8, 0.0, 0.8]:
		var q: Vector3 = p + f * ((off + s) * z.scale)
		var plane: float = p.y - (nrm.x * (q.x - p.x) + nrm.z * (q.z - p.z)) / maxf(nrm.y, 0.2)
		var g: float = W.height_at(q.x, q.z)
		for fl in W.floors + (W.decks if W.get("decks") != null else []):
			if q.x > fl[0] and q.x < fl[2] and q.z > fl[1] and q.z < fl[3] and float(fl[4]) > g and p.y > float(fl[4]) - 0.6: g = fl[4]
		if absf(plane - g) > absf(worst): worst = plane - g
	# (on ground steeper than 35 degrees nothing can lie flat to it; and across a floor's edge a body bridges the step)
	if W.normal_at(p.x, p.z).y < 0.82 or absf(feet_error(p, 0.3)) > 0.0 or feet_step(p):
		return
	if absf(worst) > TOL_LIE:
		viol("3 lying body floats over the slope" if worst > 0.0 else "3 lying body sinks into the slope", worst, p, "%s%s" % [z.state, " crawler" if z.crawler else ""])

func feet_step(p: Vector3) -> bool:
	var g := ground_expected(p, 0.3)
	var tp: float = W.height_at(p.x, p.z)
	for d in [Vector3(0.9, 0, 0), Vector3(-0.9, 0, 0), Vector3(0, 0, 0.9), Vector3(0, 0, -0.9)]:
		var q: Vector3 = p + d
		if absf((ground_expected(q, 0.3) - g) - (W.height_at(q.x, q.z) - tp)) > 0.1: return true
	return false

# ------------------------------------------------------------------ the player
var pl_prev = null
var pl_frames := 0

func step_player(dt: float) -> void:
	var was_ground: bool = P.on_ground
	P.update(dt, false, 0.0)
	pl_frames += 1
	var p: Vector3 = P.pos
	if pl_prev != null:
		var o = crossing(pl_prev, p)
		if o != null: viol("6 player crossed a wall", Vector2(p.x - pl_prev.x, p.z - pl_prev.z).length(), p, "from (%.2f, %.2f) through %s" % [pl_prev.x, pl_prev.z, what(o)])
		if was_ground and P.on_ground and absf(p.y - pl_prev.y) > 6.0 * dt + 0.02 and absf(p.y - pl_prev.y) > 1.5 * absf(W.height_at(p.x, p.z) - W.height_at(pl_prev.x, pl_prev.z)) + 0.05:
			viol("6 player vertical pop", p.y - pl_prev.y, p, "in one frame")
	var pn := pen_at(p, 0.32)
	if pn[0] > 0.32 + 0.02: viol("6 player centre inside geometry", pn[0] - 0.32, p, what(pn[1]))
	elif pn[0] > TOL_PEN: viol("6 player pressed into geometry", pn[0], p, what(pn[1]))
	var uf := under_floor(p)
	if uf > 0.0: viol("6 player under a floor", uf, p)
	var err := feet_error(p, 0.32)
	# (the player is a camera with no feet to see: its height is eased when it walks down a slope or off a step,
	# so it may ride up to 0.3 m high for a moment; it may never be below the ground)
	if err < -TOL_Y: viol("6 player sunk in the ground", err, p)
	elif P.on_ground and err > 0.3: viol("6 player floating", err, p)
	pl_prev = p
	sync_host()

func sim(dt: float) -> void:
	step_player(dt)
	zs.update(dt, dt, host)
	# as the game does: the dead the player walks into hold it back
	if zs.get("player_blocking") != null:
		zs.player_blocking = true
		P.pos += zs.player_push
		sync_host()
	audit(dt)

# ------------------------------------------------------------------ helpers for the scenarios
func free_cell(x: float, z: float, r := 0) -> bool:
	for i in range(-r, r + 1):
		for j in range(-r, r + 1):
			if W.nav_at(x + i, z + j) != 0: return false
	return true

## walkable route between two points over free nav cells (breadth first, with a metre of clearance where it can)
func route(a: Vector3, b: Vector3) -> Array:
	var N := 800
	var x0 := clampi(int(floor(minf(a.x, b.x) + 400.0)) - 40, 0, N - 1); var x1 := clampi(int(floor(maxf(a.x, b.x) + 400.0)) + 40, 0, N - 1)
	var z0 := clampi(int(floor(minf(a.z, b.z) + 400.0)) - 40, 0, N - 1); var z1 := clampi(int(floor(maxf(a.z, b.z) + 400.0)) + 40, 0, N - 1)
	var w := x1 - x0 + 1; var h := z1 - z0 + 1
	var from := PackedInt32Array(); from.resize(w * h); from.fill(-1)
	var si := (int(floor(a.z + 400.0)) - z0) * w + int(floor(a.x + 400.0)) - x0
	var gi := (int(floor(b.z + 400.0)) - z0) * w + int(floor(b.x + 400.0)) - x0
	var q := PackedInt32Array([si]); var head := 0
	from[si] = si
	var nav: PackedByteArray = W.nav
	while head < q.size():
		var i := q[head]; head += 1
		if i == gi: break
		var cx := i % w; var cz := i / w
		for k in 4:
			var nx := cx + (1 if k == 0 else -1 if k == 1 else 0); var nz := cz + (1 if k == 2 else -1 if k == 3 else 0)
			if nx < 0 or nz < 0 or nx >= w or nz >= h: continue
			var ni := nz * w + nx
			if from[ni] >= 0 or nav[(nz + z0) * N + nx + x0] != 0: continue
			from[ni] = i; q.append(ni)
	var out: Array = []
	if from[gi] < 0: return out
	var c := gi
	while c != si:
		out.append(Vector3(c % w + x0 - 400.0 + 0.5, 0, c / w + z0 - 400.0 + 0.5))
		c = from[c]
	out.reverse()
	return out

var path: Array = []
func walk_path(sprint := false) -> bool:
	while not path.is_empty() and Vector2(path[0].x - P.pos.x, path[0].z - P.pos.z).length() < 0.6:
		path.pop_front()
	if path.is_empty():
		P.auto_dir = Vector3.ZERO
		return false
	P.auto_dir = Vector3(path[0].x - P.pos.x, 0, path[0].z - P.pos.z); P.auto_sprint = sprint
	return true

func scatter(n: int, cx: float, cz: float, r0: float, r1: float, type := -1, opts := {}) -> int:
	var made := 0; var tries := 0
	while made < n and tries < n * 40:
		tries += 1
		var a := rng.randf() * TAU; var r := lerpf(r0, r1, sqrt(rng.randf()))
		var x := cx + cos(a) * r; var z := cz + sin(a) * r
		if not free_cell(x, z) or W.water_depth(x, z) > 0.2 or not W.floor_at(x, z).is_empty():
			continue
		zs.spawn(x, z, type if type >= 0 else zs.pick_type(9), 0, opts)
		made += 1
	return made

func counts() -> Dictionary:
	var c := {}
	for z in zs.list: c[z.state] = int(c.get(z.state, 0)) + 1
	return c

# ------------------------------------------------------------------ (d) a crowd around the player in the open
func s_crowd() -> void:
	scen = "d crowd"
	print("-- (d) a crowd of 150 pressing on the player in the open")
	fresh()
	place_player(-126.0, -78.0)
	zs.update(0.016, 0.016, host)
	fact("zombies", scatter(150, -126.0, -78.0, 8.0, 34.0))
	var dt := 1.0 / 60.0
	for i in int((30.0 if quick else 70.0) / dt): sim(dt)
	# then the player pushes out through them and they follow
	path = route(P.pos, Vector3(-150.0, 0, -100.0))
	for i in int((10.0 if quick else 25.0) / dt):
		walk_path()
		sim(dt)
	fact("states", counts())

# ------------------------------------------------------------------ (a) a horde pulled through the town
func s_horde_town() -> void:
	for mode in ([60] if quick else [60, 30]):
		scen = "a town %d" % mode
		print("-- (a) 160 zombies pulled across the streets and between the houses by a moving player (%d fps)" % mode)
		fresh(7 + mode)
		place_player(-64.0, -60.0)
		zs.update(0.016, 0.016, host)
		var n := scatter(110, -64.0, -60.0, 15.0, 70.0)
		n += scatter(50, 0.0, -30.0, 5.0, 60.0)
		fact("zombies", n)
		# a round trip through the town: along streets, across yards, between houses
		var stops := [Vector3(-90, 0, -60), Vector3(-90, 0, -90), Vector3(-40, 0, -93), Vector3(-20, 0, -60), Vector3(15, 0, -40), Vector3(-5, 0, -8), Vector3(-45, 0, 20), Vector3(-88, 0, 40), Vector3(-64, 0, -20)]
		var dt: float = 1.0 / float(mode)
		var si := 0
		path = []
		var dur := 60.0 if quick else (180.0 if mode == 60 else 100.0)
		var travelled := 0.0
		for i in int(dur / dt):
			if path.is_empty() and si < stops.size() * 3:
				var tgt: Vector3 = stops[si % stops.size()]; si += 1
				for k in 12:
					if free_cell(tgt.x, tgt.z): break
					tgt += Vector3(rng.randf_range(-3, 3), 0, rng.randf_range(-3, 3))
				path = route(P.pos, tgt)
			walk_path()
			host.player_noise = 45.0 if (i % int(4.0 / dt)) < 3 else 6.5          # a shot every four seconds
			var before: Vector3 = P.pos
			sim(dt)
			travelled += P.pos.distance_to(before)
		fact("player walked m", snappedf(travelled, 1.0)); fact("states", counts())

# ------------------------------------------------------------------ (b) hiding in houses with the doors shut
func s_houses() -> void:
	var picks := [1, 28, 49] if quick else [1, 28, 49, 77, 303, 151]
	var ids: Array = []
	for want_id in picks:
		if bld.has(want_id): ids.append(want_id)
	var kinds := {}
	for id in bld:
		var k := String(bld[id].kind)
		if ids.size() < picks.size() + (0 if quick else 3) and not kinds.has(k) and not ids.has(id) and k != "house" and k != "house_l" and k != "cabin":
			kinds[k] = true; ids.append(id)
	for id in ids:
		var b: Dictionary = bld[id]
		scen = "b house %d" % id
		print("-- (b) hiding in %s %d at (%d, %d), doors shut, a horde outside" % [b.kind, id, b.x, b.z])
		fresh(11 + id)
		# somewhere free inside
		var spot := Vector3(b.x, b.floorY, b.z)
		for k in 200:
			var x: float = b.x + rng.randf_range(-b.w * 0.35, b.w * 0.35); var z: float = b.z + rng.randf_range(-b.d * 0.35, b.d * 0.35)
			if free_cell(x, z) and pen_at(Vector3(x, b.floorY, z), 0.4)[0] < 0.0:
				spot = Vector3(x, b.floorY, z); break
		place_player(spot.x, spot.z, b.floorY)
		host.base_center = P.pos; host.base_heat = 60.0; host.daylight = 0.3; host.hours = 21.0
		zs.update(0.016, 0.016, host)
		var ang := rng.randf() * TAU
		var hx: float = b.x + cos(ang) * (maxf(b.w, b.d) * 0.5 + 22.0); var hz: float = b.z + sin(ang) * (maxf(b.w, b.d) * 0.5 + 22.0)
		zs.spawn_horde(hx, hz, 26, P.pos, true)
		scatter(16, b.x, b.z, maxf(b.w, b.d) * 0.5 + 6.0, maxf(b.w, b.d) * 0.5 + 20.0)
		fact("zombies", zs.list.size())
		var dt := 1.0 / 60.0
		var inside_before := {}
		for i in int((60.0 if quick else 150.0) / dt):
			host.player_noise = 40.0 if (i % 300) < 3 else 0.0
			sim(dt)
			if i % 4 == 0:
				for z in zs.list:
					var ins: bool = z.pos.x > b.x - b.w * 0.5 + 0.2 and z.pos.x < b.x + b.w * 0.5 - 0.2 and z.pos.z > b.z - b.d * 0.5 + 0.2 and z.pos.z < b.z + b.d * 0.5 - 0.2
					if ins and not inside_before.has(z.id):
						inside_before[z.id] = true
						bump("zombies that got in")
						entry_check(z.pos, b)
		fact("states", counts())

## a body that has just come inside must be next to a door that is open or a window that is broken
func entry_check(p: Vector3, b: Dictionary) -> void:
	var best := 9.0
	for id in doors:
		var d: Dictionary = doors[id]
		if int(d.data.building) == int(b.id) and d.open: best = minf(best, Vector2(float(d.data.cx) - p.x, float(d.data.cz) - p.z).length())
	for id in windows:
		var w: Dictionary = windows[id]
		if int(w.data.building) == int(b.id) and w.broken: best = minf(best, Vector2(float(w.data.cx) - p.x, float(w.data.cz) - p.z).length())
	if best > 2.2: viol("1 zombie came in where there is no opening", best, p, "nearest open door / broken window of building %d" % int(b.id))

# ------------------------------------------------------------------ (c) forest and slopes
func s_forest() -> void:
	for place in [["c forest", -340.0, -220.0, -200.0, -260.0], ["c slopes", 300.0, 150.0, 345.0, 200.0], ["c rocks", -300.0, 380.0, -240.0, 330.0]]:
		scen = place[0]
		print("-- (c) 90 zombies chasing a running player: %s" % scen)
		fresh(23)
		var a := Vector3(place[1], 0, place[2]); var b := Vector3(place[3], 0, place[4])
		for k in 60:
			if free_cell(a.x, a.z, 1) and W.water_depth(a.x, a.z) <= 0.0: break
			a += Vector3(rng.randf_range(-4, 4), 0, rng.randf_range(-4, 4))
		for k in 60:
			if free_cell(b.x, b.z, 1) and W.water_depth(b.x, b.z) <= 0.0: break
			b += Vector3(rng.randf_range(-4, 4), 0, rng.randf_range(-4, 4))
		place_player(a.x, a.z)
		zs.update(0.016, 0.016, host)
		fact("zombies", scatter(90, a.x, a.z, 10.0, 45.0))
		var trees := 0; var slope := 0.0
		for c in W.query(minf(a.x, b.x) - 30, minf(a.z, b.z) - 30, maxf(a.x, b.x) + 30, maxf(a.z, b.z) + 30):
			if not is_box(c): trees += 1
		for k in 40:
			var t := k / 39.0
			slope = maxf(slope, W.slope_at(lerpf(a.x, b.x, t), lerpf(a.z, b.z, t)))
		fact("trunks and rocks around", trees); fact("steepest (1 - ny)", snappedf(slope, 0.01))
		var dt := 1.0 / 60.0
		var legs := 0
		path = route(P.pos, b)
		for i in int((40.0 if quick else 110.0) / dt):
			if path.is_empty() and legs < 4:
				legs += 1
				path = route(P.pos, a if legs % 2 == 1 else b)
			walk_path(i % 600 < 240)             # jog, with bursts of sprinting
			host.player_noise = 16.0
			sim(dt)
		fact("states", counts())

# ------------------------------------------------------------------ (e) the starting population leaving its buildings
func s_populate() -> void:
	scen = "e populate"
	print("-- (e) the starting population: the player walks the streets making noise, the dead come out of the houses")
	fresh(3)
	place_player(-120.0, -60.0)
	zs.populate()
	var indoors := 0
	for z in zs.list:
		if not W.floor_at(z.pos.x, z.pos.z).is_empty(): indoors += 1
	fact("zombies", zs.list.size()); fact("of them indoors", indoors)
	var stops := [Vector3(-120, 0, -95), Vector3(-62, 0, -95), Vector3(-60, 0, -40), Vector3(-2, 0, -40), Vector3(0, 0, 30), Vector3(-60, 0, 32), Vector3(-120, 0, 30), Vector3(-120, 0, -60)]
	var dt := 1.0 / 60.0
	var si := 0
	path = []
	var start_in := {}
	for z in zs.list:
		if not W.floor_at(z.pos.x, z.pos.z).is_empty(): start_in[z.id] = true
	for i in int((60.0 if quick else 200.0) / dt):
		if path.is_empty() and si < stops.size():
			var tgt: Vector3 = stops[si]; si += 1
			for k in 12:
				if free_cell(tgt.x, tgt.z): break
				tgt += Vector3(rng.randf_range(-3, 3), 0, rng.randf_range(-3, 3))
			path = route(P.pos, tgt)
		walk_path()
		host.player_noise = 50.0 if (i % 240) < 3 else 6.5
		sim(dt)
	var out := 0
	for z in zs.list:
		if start_in.has(z.id) and W.floor_at(z.pos.x, z.pos.z).is_empty(): out += 1
	fact("came out of their buildings", out); fact("states", counts())

# ------------------------------------------------------------------ (f) the turned rising indoors, bodies knocked down by walls
func s_turned() -> void:
	scen = "f turned"
	print("-- (f) the newly turned rising indoors, and bodies knocked down beside walls and furniture")
	fresh(31)
	var n := 0
	var ids: Array = bld.keys()
	for id in ids:
		var b: Dictionary = bld[id]
		if n >= (20 if quick else 60): break
		for s in b.get("zombieSpawns", []):
			if rng.randf() < 0.5: continue
			place_player(float(s[0]) + 30.0, float(s[2]))
			# beside whatever stands nearest: a wall or a piece of furniture
			var x: float = float(s[0]) + rng.randf_range(-1.2, 1.2); var z: float = float(s[2]) + rng.randf_range(-1.2, 1.2)
			var p := Vector3(x, float(s[1]), z)
			p = W.resolve(p, 0.3, 1.7).pos
			var zb = zs.spawn_turned({"pos": p, "yaw": rng.randf() * TAU, "seed": rng.randf() * 90.0, "name": "t%d" % n})
			if zb != null: n += 1
			break
	fact("turned", n)
	# they are far apart; move the 'player' out of sight so they rise and wander indoors
	place_player(-126.0, -78.0)
	host.player_alive = false
	var dt := 1.0 / 60.0
	for i in int((14.0 if quick else 30.0) / dt):
		zs.update(dt, dt, host)
		audit(dt)
		if i == int(8.0 / dt):
			# then every one of them is knocked flat where it stands, toward the nearest wall
			for z in zs.list:
				if z.state != &"dead" and z.state != &"rise":
					var pn := pen_at(z.pos + Vector3(0.6, 0, 0), 0.6)
					var dir := Vector3(1, 0, 0) if pn[0] > 0.0 else Vector3(rng.randf_range(-1, 1), 0, rng.randf_range(-1, 1)).normalized()
					zs.damage(z, 3.0, dir, {"knock": 3.4, "by_player": false, "part": 1})
		if i == int(20.0 / dt):
			for z in zs.list:
				if z.state != &"dead" and rng.randf() < 0.5:
					zs.damage(z, 9999.0, Vector3(rng.randf_range(-1, 1), 0, rng.randf_range(-1, 1)).normalized(), {"by_player": false, "part": 1})
	host.player_alive = true
	fact("states", counts())
	# corpses, crawlers and the dormant on a hillside
	scen = "f slope"
	fresh(37)
	place_player(300.0, 150.0)
	for k in 80:
		if free_cell(P.pos.x, P.pos.z, 1): break
		place_player(300.0 + rng.randf_range(-15, 15), 150.0 + rng.randf_range(-15, 15))
	host.player_alive = false
	zs.update(0.016, 0.016, host)
	fact("zombies", scatter(60, P.pos.x, P.pos.z, 3.0, 40.0, 0, {"decay": 0.5}))
	var k2 := 0
	for z in zs.list:
		k2 += 1
		if k2 % 3 == 0:
			z.crawler = true; zs._apply_decay(z)
		elif k2 % 3 == 1:
			z.state = &"dormant"; z.lying = true; z.down_face = 1 if k2 % 2 == 0 else -1; z.anim = &"lie_back0" if z.down_face >= 0 else &"lie_front0"; z.wake_t = 999.0
	for i in int(12.0 / dt):
		zs.update(dt, dt, host)
		audit(dt)
		if i == int(4.0 / dt):
			for z in zs.list:
				if not z.crawler and z.state != &"dormant":
					zs.damage(z, 9999.0, Vector3(rng.randf_range(-1, 1), 0, rng.randf_range(-1, 1)).normalized(), {"by_player": false, "part": 1})
	host.player_alive = true
	fact("states", counts())

# ------------------------------------------------------------------ the player alone
func s_player() -> void:
	scen = "p probes"
	print("-- (p) the player: run at walls, corners, furniture, doors, fences, cars, trees, porches, the lake")
	if zs == null: fresh()
	var dt := 1.0 / 60.0
	var targets: Array = []          # [kind, point to run at, feet height there]
	var by_tag := {}
	for o in W._boxes.values():
		if not o.solid: continue
		var k := String(o.tag)
		if not by_tag.has(k): by_tag[k] = []
		by_tag[k].append(o)
	for k in by_tag:
		var list: Array = by_tag[k]
		for i in mini(list.size(), 12 if quick else 45):
			var o = list[rng.randi() % list.size()]
			targets.append([k, Vector3((o.min.x + o.max.x) * 0.5, o.min.y, (o.min.z + o.max.z) * 0.5), o])
			# and at one of its corners
			targets.append([k + " corner", Vector3(o.min.x if rng.randf() < 0.5 else o.max.x, o.min.y, o.min.z if rng.randf() < 0.5 else o.max.z), o])
	var circ: Array = W._circles.values()
	for i in (30 if quick else 120):
		var c = circ[rng.randi() % circ.size()]
		targets.append([c.kind if c.kind != "" else "post", Vector3(c.x, c.y0 + 0.5, c.z), c])
	var runs := 0; var stopped := 0; var passed := 0
	for tg in targets:
		var c: Vector3 = tg[1]
		for attempt in 2:
			var a := rng.randf() * TAU
			var dist := rng.randf_range(2.5, 4.5)
			var s := Vector3(c.x + cos(a) * dist, 0, c.z + sin(a) * dist)
			var gy: float = W.ground_y(s.x, s.z, c.y + 0.3)
			s.y = gy
			if pen_at(s, 0.45)[0] > 0.0 or absf(gy - c.y) > 1.2:
				continue
			place_player(s.x, s.z, c.y + 0.3)
			var dir := Vector3(c.x - s.x, 0, c.z - s.z).normalized()
			# glancing blows as well as head-on ones
			dir = dir.rotated(Vector3.UP, rng.randf_range(-0.35, 0.35))
			P.auto_dir = dir; P.auto_sprint = attempt == 1
			for i in int(2.2 / dt): step_player(dt)
			runs += 1
			var far := Vector2(P.pos.x - s.x, P.pos.z - s.z).length()
			if far > dist + 1.0: passed += 1
			else: stopped += 1
			# wedged? some way out must be open
			var here: Vector3 = P.pos
			var best_out := 0.0
			for k in 8:
				P.pos = here; P.vel = Vector3.ZERO; pl_prev = null
				P.auto_dir = Vector3(cos(k * TAU / 8.0), 0, sin(k * TAU / 8.0)); P.auto_sprint = false
				for i in int(0.7 / dt): step_player(dt)
				best_out = maxf(best_out, Vector2(P.pos.x - here.x, P.pos.z - here.z).length())
				if best_out > 1.0: break
			if best_out < 1.0: viol("6 player wedged", best_out, here, "after running at a %s" % tg[0])
			break
	fact("runs at things", runs); fact("stopped by them", stopped); fact("slid past", passed)
	# open doors must let the player through, shut ones must not
	var through := 0; var tested := 0; var held := 0; var shut_tested := 0
	for id in doors:
		var d: Dictionary = doors[id]
		if tested + shut_tested >= (16 if quick else 80): break
		var ax := String(d.data.axis) == "x"
		var c := Vector3(float(d.data.cx), float(d.data.y), float(d.data.cz))
		var n := Vector3(0, 0, 1) if ax else Vector3(1, 0, 0)
		var s := c + n * 1.6
		if pen_at(Vector3(s.x, W.ground_y(s.x, s.z, c.y + 0.3), s.z), 0.4)[0] > 0.0:
			s = c - n * 1.6; n = -n
		var sy: float = W.ground_y(s.x, s.z, c.y + 0.3)
		if pen_at(Vector3(s.x, sy, s.z), 0.4)[0] > 0.0 or c.y - sy > 0.56:
			continue
		var was_open: bool = d.open
		for open in [true, false]:
			W.set_solid(d.box, not open, 30)
			place_player(s.x, s.z, c.y + 0.3)
			P.auto_dir = -n; P.auto_sprint = false
			for i in int(1.6 / dt): step_player(dt)
			var side: float = (P.pos - c).dot(n)
			if open:
				tested += 1
				if side < -0.5: through += 1
				else: viol("6 player cannot pass an open door", side, c, "door %d, stopped at (%.2f, %.2f, %.2f)" % [id, P.pos.x, P.pos.y, P.pos.z])
			else:
				shut_tested += 1
				if side > 0.0: held += 1
				else: viol("6 player walked through a shut door", side, c, "door %d" % id)
		W.set_solid(d.box, not was_open, 30)
	fact("open doors walked through", "%d of %d" % [through, tested]); fact("shut doors that held", "%d of %d" % [held, shut_tested])
	# random walks, indoors and out
	scen = "p wander"
	var walks := 0
	var spots: Array = []
	for id in bld:
		var b: Dictionary = bld[id]
		spots.append(Vector3(b.x + rng.randf_range(-2, 2), b.floorY, b.z + rng.randf_range(-2, 2)))
		spots.append(Vector3(b.x + b.w * 0.5 + 3.0, 0, b.z + rng.randf_range(-3, 3)))
	for sp in spots:
		if walks >= (16 if quick else 90): break
		var y: float = sp.y if sp.y != 0.0 else W.height_at(sp.x, sp.z) + 0.6
		var st := Vector3(sp.x, W.ground_y(sp.x, sp.z, y), sp.z)
		if pen_at(st, 0.4)[0] > 0.0: continue
		place_player(st.x, st.z, y)
		walks += 1
		var a := rng.randf() * TAU
		for i in int(22.0 / dt):
			if i % 50 == 0:
				a += rng.randf_range(-1.6, 1.6)
				P.auto_sprint = rng.randf() < 0.3
				if rng.randf() < 0.15 and P.on_ground: P.fall_vel = 4.4; P.on_ground = false          # a jump
			P.auto_dir = Vector3(sin(a), 0, cos(a))
			step_player(dt)
	fact("random walks of 22 s", walks)
	# into the lake and out the other side
	scen = "p lake"
	var lk: Dictionary = W.lake
	for k in 6:
		var a := k * TAU / 6.0
		place_player(lk.x + cos(a) * (lk.r + 14.0), lk.z + sin(a) * (lk.r + 14.0))
		P.auto_dir = Vector3(-cos(a), 0, -sin(a)); P.auto_sprint = true
		for i in int(18.0 / dt): step_player(dt)
	fact("deepest water walked m", snappedf(W.water_depth(lk.x, lk.z), 0.1))
	P.auto_dir = Vector3.ZERO
	fact("player frames", pl_frames)

# ------------------------------------------------------------------ the data: can you see something you can walk through?
func covered(q: Vector3) -> bool:
	for o in W.query(q.x - 0.4, q.z - 0.4, q.x + 0.4, q.z + 0.4):
		if is_box(o):
			if q.x > o.min.x - 0.08 and q.x < o.max.x + 0.08 and q.z > o.min.z - 0.08 and q.z < o.max.z + 0.08 and q.y > o.min.y - 0.1 and q.y < o.max.y + 0.1: return true
		elif Vector2(q.x - o.x, q.z - o.z).length() < o.r + 0.08 and q.y > o.y0 - 0.1 and q.y < o.y1 + 0.1: return true
	# a box that is not solid (an open door) still counts as known to the world
	for o in W._boxes.values():
		if not o.solid and q.x > o.min.x - 0.1 and q.x < o.max.x + 0.1 and q.z > o.min.z - 0.1 and q.z < o.max.z + 0.1: return true
	return false

func audit_data() -> void:
	scen = "data"
	print("-- the data: everything visible that is taller than 0.5 m and wider than 0.3 m needs a collider")
	var props: Dictionary = JSON.parse_string(FileAccess.get_file_as_string("res://assets/world/props.json"))
	var sizes: Dictionary = JSON.parse_string(FileAccess.get_file_as_string("res://assets/models/props/index.json"))
	var cars: Dictionary = JSON.parse_string(FileAccess.get_file_as_string("res://assets/models/cars/index.json"))
	var by := {}             # template -> [total, big, bare, example]
	for e in props.entries:
		var t := String(e.t)
		var sz = null
		if t.begins_with("car_"): sz = cars.get(t.substr(4), {}).get("size")
		else: sz = sizes.get(t.substr(2), {}).get("size")
		if not by.has(t): by[t] = [0, 0, 0, "", sz]
		by[t][0] += 1
		if sz == null: continue
		var m: Array = e.m
		var bx := Vector3(m[0], m[1], m[2]); var byv := Vector3(m[4], m[5], m[6]); var bz := Vector3(m[8], m[9], m[10]); var org := Vector3(m[12], m[13], m[14])
		var hx: float = float(sz[0]) * 0.5; var hy: float = float(sz[1]); var hz: float = float(sz[2]) * 0.5
		var tall := hy * byv.length()
		if tall < 0.5 or minf(hx * bx.length(), hz * bz.length()) * 2.0 < 0.3:
			continue
		by[t][1] += 1
		var hit := 0
		var hgt := minf(tall * 0.5, 0.9)
		for sx in [-0.55, 0.0, 0.55]:
			for szz in [-0.55, 0.0, 0.55]:
				if covered(org + bx * (hx * sx) + bz * (hz * szz) + Vector3(0, hgt, 0)): hit += 1
		if not covered(org + Vector3(0, hgt, 0)):
			by[t][2] += 1
			viol("data: prop without a collider", float(hit), org, "%s %.2f x %.2f x %.2f m" % [t, hx * 2.0, tall, hz * 2.0])
		elif hit < 5 and minf(hx, hz) > 0.4:
			viol("data~ prop only partly covered (a post with a wide top, or a collider too small)", float(hit), org, "%s %.2f x %.2f x %.2f m, %d of 9 points" % [t, hx * 2.0, tall, hz * 2.0, hit])
	var line := ""
	for t in by:
		if by[t][4] == null: viol("data~ prop of unknown size", 0.0, Vector3.ZERO, "%s x%d" % [t, by[t][0]])
		elif by[t][2] > 0: line += "%s %d/%d  " % [t, by[t][2], by[t][1]]
	fact("props placed", props.entries.size()); fact("props without collider (bare/big)", line if line != "" else "none")
	# containers
	var bare := 0
	for c in town.containers:
		var p := Vector3(c.pos[0], c.pos[1], c.pos[2])
		if float(c.half[1]) * 2.0 >= 0.5 and String(c.kind) != "car_trunk" and not covered(p):
			bare += 1; viol("data~ container whose centre is not inside a collider", 0.0, p, String(c.kind))
	fact("containers", town.containers.size())
	# vegetation
	var veg: Dictionary = JSON.parse_string(FileAccess.get_file_as_string("res://assets/world/veg.json"))
	var inst := FileAccess.get_file_as_bytes("res://data/veg.bin").to_float32_array()
	var vk := {}
	for i in int(veg.count):
		var o := i * 6
		var v: Dictionary = veg.variants[int(inst[o + 5])]
		var kind := String(v.kind)
		var s: float = inst[o + 3]
		var p := Vector3(inst[o], inst[o + 1], inst[o + 2])
		if not vk.has(kind): vk[kind] = [0, 0]
		vk[kind][0] += 1
		var tall: float = float(v.height) * s
		var wide: float = float(v.trunk) * s * 2.0
		if tall < 0.5 or wide < 0.3:
			continue
		if not covered(p + Vector3(0, 0.6, 0)):
			vk[kind][1] += 1
			viol("data: %s without a collider" % kind, wide, p, "trunk/width %.2f m, height %.1f m" % [wide, tall])
	fact("vegetation [placed, bare]", vk)
	# cars, lamps, ore
	for c in town.cars:
		var p := Vector3(c.pos[0], c.pos[1] + 0.6, c.pos[2])
		if not covered(p): viol("data: car without a collider", 0.0, p, String(c.variant))
	# every outside door must be reachable on foot: from the doorway outwards, some way down to open ground that a
	# body coming the other way could climb (no rise over 0.55 m)
	var doors_checked := 0
	for id in doors:
		var d: Dictionary = doors[id]
		var b: Dictionary = bld[int(d.data.building)]
		var ax := String(d.data.axis) == "x"
		var outside := false
		for sgn in [-1.0, 1.0]:
			if W.floor_at(float(d.data.cx) + (0.0 if ax else sgn * 1.3), float(d.data.cz) + (sgn * 1.3 if ax else 0.0)).is_empty(): outside = true
		if not outside: continue
		doors_checked += 1
		var start := Vector3(float(d.data.cx), float(b.floorY), float(d.data.cz))
		var seen := {Vector2i(0, 0): start.y}
		var q: Array = [Vector2i(0, 0)]
		var ok := false; var head := 0
		while head < q.size() and not ok:
			var c: Vector2i = q[head]; head += 1
			var cy: float = seen[c]
			for k in [Vector2i(1, 0), Vector2i(-1, 0), Vector2i(0, 1), Vector2i(0, -1)]:
				var n: Vector2i = c + k
				if seen.has(n) or absi(n.x) > 16 or absi(n.y) > 16: continue
				var np := Vector3(start.x + n.x * 0.5, 0, start.z + n.y * 0.5)
				var ny: float = W.ground_y(np.x, np.z, cy)
				if cy - ny > 0.55: continue                       # too high a step for someone coming up
				np.y = ny
				if n != Vector2i(0, 0) and pen_at(np, 0.25)[0] > 0.1 and Vector2(n).length() > 1.5: continue
				seen[n] = ny
				q.append(n)
				if Vector2(n).length() > 12.0 and W.floor_at(np.x, np.z).is_empty(): ok = true
		if not ok:
			viol("data: door that cannot be reached on foot (the ground outside is too far below)", float(b.floorY) - W.height_at(start.x, start.z), start, "door %d of %s %d" % [id, b.kind, int(b.id)])
	fact("outside doors checked", doors_checked)
	town_mesh_audit()

## Walk the town's own triangles: anything standing 0.5 - 1.6 m above the walkable ground with nothing in
## WorldData near it is something you can see and walk through.
func town_mesh_audit() -> void:
	var scene = load("res://assets/world/town.glb")
	if scene == null:
		viol("data~ town.glb could not be loaded headless", 0.0, Vector3.ZERO); return
	var node: Node = scene.instantiate()
	var cells := {}             # 1 m cell -> [count, example, node name]
	var tris := 0; var flat := {}
	var stack: Array = [node]
	while not stack.is_empty():
		var nd: Node = stack.pop_back()
		for c in nd.get_children(): stack.append(c)
		if not (nd is MeshInstance3D) or nd.mesh == null:
			continue
		var xf := Transform3D.IDENTITY
		var up: Node = nd
		while up != null and up is Node3D:
			xf = up.transform * xf; up = up.get_parent()
		var nm := String(nd.name)
		# (door leaves and panes move or break; an open leaf is not a wall)
		var leaf := false
		up = nd
		while up != null:
			if String(up.name).begins_with("door") or String(up.name).begins_with("win"): leaf = true
			up = up.get_parent()
		if leaf: continue
		for si in nd.mesh.get_surface_count():
			var arr: Array = nd.mesh.surface_get_arrays(si)
			if arr.is_empty() or arr[Mesh.ARRAY_VERTEX] == null: continue
			var vs: PackedVector3Array = arr[Mesh.ARRAY_VERTEX]
			var idx = arr[Mesh.ARRAY_INDEX]
			var n: int = idx.size() if idx != null and idx.size() > 0 else vs.size()
			for i in range(0, n - 2, 3):
				var a: Vector3 = xf * vs[idx[i] if idx != null and idx.size() > 0 else i]
				var b: Vector3 = xf * vs[idx[i + 1] if idx != null and idx.size() > 0 else i + 1]
				var c: Vector3 = xf * vs[idx[i + 2] if idx != null and idx.size() > 0 else i + 2]
				tris += 1
				var nrm := (b - a).cross(c - a)
				var area := nrm.length() * 0.5
				if area < 0.02: continue
				nrm /= area * 2.0
				for q in [(a + b + c) / 3.0, (a * 4.0 + b + c) / 6.0, (a + b * 4.0 + c) / 6.0, (a + b + c * 4.0) / 6.0]:
					var g: float = W.height_at(q.x, q.z)
					for f in W.floors:
						if q.x > f[0] and q.x < f[2] and q.z > f[1] and q.z < f[3] and float(f[4]) > g: g = f[4]
					var hgt: float = q.y - g
					if absf(nrm.y) < 0.5 and hgt > 0.5 and hgt < 1.6:
						# (fine if a body standing under this point would be inside something anyway)
						if not covered(q) and pen_at(Vector3(q.x, g, q.z), 0.1)[0] <= 0.0:
							var k := Vector2i(int(floor(q.x)), int(floor(q.z)))
							if not cells.has(k): cells[k] = [0, q, nm]
							cells[k][0] += 1
					elif absf(nrm.y) > 0.7 and hgt > 0.12 and hgt < 0.56 and area > 0.3:
						# something flat you would expect to stand on: is it ground to the world?
						if W.ground_y(q.x, q.z, q.y + 0.2) < q.y - 0.1 and W.resolve(Vector3(q.x, q.y - 0.2, q.z), 0.2, 1.7).ground_y < q.y - 0.1 and pen_at(Vector3(q.x, g, q.z), 0.1)[0] <= 0.0:
							var k2 := Vector2i(int(floor(q.x / 2.0)), int(floor(q.z / 2.0)))
							if not flat.has(k2): flat[k2] = [0, q, hgt]
							flat[k2][0] += 1
	node.free()
	fact("town triangles read", tris)
	if tris == 0:
		viol("data~ town mesh not readable headless", 0.0, Vector3.ZERO); return
	var groups := {}
	for k in cells:
		if cells[k][0] < 2: continue
		var nm: String = cells[k][2]
		viol("data: town geometry without a collider (1 m cells)", float(cells[k][0]), cells[k][1], nm)
		groups[nm.rstrip("0123456789")] = int(groups.get(nm.rstrip("0123456789"), 0)) + 1
	fact("bare town geometry cells by node kind", groups)
	if OS.get_cmdline_user_args().has("--dump"):
		var f := FileAccess.open("user://noclip_cells.json", FileAccess.WRITE)
		var rows := []
		for k in cells: rows.append([cells[k][1].x, cells[k][1].y, cells[k][1].z, cells[k][0], cells[k][2]])
		var rows2 := []
		for k in flat: rows2.append([flat[k][1].x, flat[k][1].y, flat[k][1].z, flat[k][0], flat[k][2]])
		f.store_string(JSON.stringify({"walls": rows, "decks": rows2}))
	for k in flat:
		if flat[k][0] < 2: continue
		viol("data: raised deck that is not ground (feet sink in)", float(flat[k][2]), flat[k][1])
