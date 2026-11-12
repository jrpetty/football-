extends Node3D
## Building and barricading: the pieces the player puts up (foundations, walls, doors, windows, stairs, roofs,
## gates, towers and the stations — workbench, campfire, stove, crate, bedroll, rain collector, smoking rack,
## compost bin), their collision, their hit points, and the boards nailed over the town's doors and windows.
## A port of the web game's Base; the ghost, the build bar and the menu are in sys_placer.gd.
##
## A piece is a Dictionary, and it is also what stands in WorldData.Box.ref / WorldData.nav_ref for the dead to
## find: {kind = "piece", built = true, id, def_id, pkind, station, name, tx, tz, axis, rot, y, cx, cz, hp,
## max_hp, destroyed, open (doors), node, boxes, floors, inv (containers), fire {fuel, lit, pos}, rain, proc}.

signal piece_placed(piece: Dictionary)
signal piece_removed(piece: Dictionary)
signal barricade_changed(target: Dictionary)

const U := preload("res://scripts/sys_util.gd")
const Placer := preload("res://scripts/sys_placer.gd")

const GRID := 2.0
const EDGE_KINDS := ["wall", "door_wall", "window_wall", "gate"]
const LOAD_ORDER := ["foundation", "stairs", "tower", "wall", "window_wall", "door_wall", "gate", "station", "roof"]
## pieces that belong to systems this port does not have yet (farming, power)
const NOT_YET := ["farm_plot", "generator", "chicken_coop"]
const STAIR_STEPS := 16
const STAIR_RISE := 3.15
const BARRICADE_COST := {"plank": 2, "nails": 3}
const DOOR_SHIELD := 1.0e9          # a boarded door's own hit points are out of reach until the boards are gone
const NAV_WALL := 25
const NAV_DOOR := 30
const NAV_STATION := 20
const NAV_BARRICADE := 45
const CONTAINER_LABELS := {"crate": "Wooden Crate", "compost": "Compost Bin", "rack": "Smoking Rack"}
const ORGANIC := ["rotten_food", "leaves", "berries", "mushroom_odd", "raw_meat", "wheat"]

var game
var defs: Dictionary = {}             # piece id -> definition (data/factory.json)
var milestones: Array = []
var prop_sizes: Dictionary = {}
var list: Array = []                  # every standing piece
var placer                            # sys_placer.gd
## Settlement level, for pieces and recipes that unlock with it. The settlement itself is not ported yet, so
## nothing is held back until `tier_gating` is switched on by the system that will own it.
var tier := 0
var tier_gating := false
var free_build := false               # no costs (tests, a future creative mode)

var _by_id: Dictionary = {}
var _edges: Dictionary = {}           # Vector3i(tx, tz, axis) -> piece
var _tiles: Dictionary = {}           # Vector2i(tx, tz) -> pieces standing on that tile
var _next_id := 1
var _scenes: Dictionary = {}
var _moving: Array = []               # doors swinging
var _owners: Dictionary = {}          # nav index -> [[box id, cost, piece], ...]
var _under: Dictionary = {}           # nav index -> [value, ref] as it was before anything was built there
var _barricaded: Dictionary = {}      # "d12" / "w40" -> the town door or window
var _slow_t := 0.0
var _bang_t := 0.0
var _splat_img: Image = null
var _splat_dirty := false
var _wired := false

func _ready() -> void:
	var f = World.read_json("res://data/factory.json")
	if f is Dictionary:
		for id in f.get("pieces", {}):
			if not NOT_YET.has(String(id)):
				defs[String(id)] = f.pieces[id]
		milestones = f.get("milestones", [])
	var ps = World.read_json("res://assets/models/props/index.json")
	if ps is Dictionary:
		prop_sizes = ps
	placer = Placer.new()
	placer.name = "Placer"
	placer.build = self
	add_child(placer)

func _exit_tree() -> void:
	# WorldData outlives the scene: take every box, floor and board back out of it
	clear()

# ------------------------------------------------------------------ module interface
func mod_started(new_game: bool) -> void:
	if new_game:
		clear()
	placer.set_active(false)
	if not _wired and game.zombies != null and game.zombies.has_signal("structure_hit"):
		game.zombies.structure_hit.connect(_on_structure_hit)
		_wired = true

func mod_input(ev: InputEvent) -> bool:
	return placer.input(ev)

func mod_process(dt: float, dt_min: float, ui_open: bool) -> void:
	if not _moving.is_empty():
		_update_doors(dt)
	_bang_t = maxf(0.0, _bang_t - dt)
	_slow_t += dt
	if _slow_t >= 0.25:
		_poll_barricades()
		_slow_t = 0.0
	_update_stations(dt_min)
	if _splat_dirty:
		_push_splat()
	placer.frame(dt, ui_open)

func mod_save() -> Variant:
	var rows: Array = []
	for p in list:
		var r := {"id": p.def_id, "tx": p.tx, "tz": p.tz, "axis": p.axis, "rot": p.rot, "y": p.y, "hp": p.hp, "max_hp": p.max_hp, "open": bool(p.get("open", false))}
		if p.has("inv"): r["inv"] = p.inv.to_rows()
		if p.has("fire"): r["fire"] = [p.fire.fuel, p.fire.lit]
		if p.has("rain"): r["rain"] = p.rain
		if p.has("proc"): r["proc"] = p.proc
		rows.append(r)
	var bars: Array = []
	for key in _barricaded:
		var t: Dictionary = _barricaded[key]
		bars.append([String(t.kind), int(t.id), float(t.barricade), float(t.get("hp_real", 0.0)), float(t.get("bar_step", 60.0))])
	return {"v": 1, "pieces": rows, "barricades": bars, "tier": tier}

func mod_load(data) -> void:
	clear()
	if not (data is Dictionary):
		return
	tier = int(data.get("tier", 0))
	var rows: Array = data.get("pieces", []).duplicate()
	rows.sort_custom(func(a, b) -> bool:
		return LOAD_ORDER.find(String(defs.get(String(a.id), {}).get("kind", "roof"))) < LOAD_ORDER.find(String(defs.get(String(b.id), {}).get("kind", "roof"))))
	for r in rows:
		if not defs.has(String(r.id)):
			continue
		var p := place(String(r.id), int(r.tx), int(r.tz), int(r.axis), int(r.rot), float(r.y), {"hp": float(r.hp), "max_hp": float(r.get("max_hp", r.hp))})
		if bool(r.get("open", false)) and p.has("door_box"):
			set_door(p, true, true)
		if r.has("inv") and p.has("inv"):
			p.inv.load_rows(r.inv)
		if r.has("fire") and p.has("fire"):
			p.fire.fuel = float(r.fire[0]); p.fire.lit = bool(r.fire[1])
		if r.has("rain") and p.has("rain"): p.rain = float(r.rain)
		if r.has("proc") and p.has("proc"): p.proc = float(r.proc)
	var world = game.world
	for b in data.get("barricades", []):
		var t = world.doors.get(int(b[1])) if String(b[0]) == "door" else world.windows.get(int(b[1]))
		if t == null:
			continue
		if String(b[0]) == "door" and float(b[3]) > 0.0:
			t.hp = float(b[3])              # the game restored the shield value; put the real one back first
		barricade(t, 0, float(b[2]), float(b[4]))
	# a door that still carries a shield but no boards (a save from a different build): give it its own strength back
	for id in world.doors:
		var d: Dictionary = world.doors[id]
		if float(d.hp) > DOOR_SHIELD * 0.5 and float(d.get("barricade", 0.0)) <= 0.0:
			d.hp = float(d.data.hp)

## everything built and boarded goes, and the world is as it was
func clear() -> void:
	for p in list.duplicate():
		remove(p)
	for key in _barricaded.keys():
		_unbarricade(_barricaded[key])
	list.clear(); _by_id.clear(); _edges.clear(); _tiles.clear(); _moving.clear()
	_next_id = 1

# ------------------------------------------------------------------ the grid
static func tile_of(v: float) -> int:
	return int(floor(v / GRID))

## the tile edge nearest a point. axis 0: the edge runs along X at z = (tz + 1) * GRID; axis 1: along Z at x = (tx + 1) * GRID
static func nearest_edge(x: float, z: float) -> Dictionary:
	var tx := tile_of(x); var tz := tile_of(z)
	var fx := x / GRID - tx; var fz := z / GRID - tz
	var d := [1.0 - fz, 1.0 - fx, fz, fx]
	var s := 0
	for i in range(1, 4):
		if d[i] < d[s]: s = i
	match s:
		0: return {"tx": tx, "tz": tz, "axis": 0, "cx": (tx + 0.5) * GRID, "cz": (tz + 1) * GRID}
		1: return {"tx": tx, "tz": tz, "axis": 1, "cx": (tx + 1) * GRID, "cz": (tz + 0.5) * GRID}
		2: return {"tx": tx, "tz": tz - 1, "axis": 0, "cx": (tx + 0.5) * GRID, "cz": tz * GRID}
	return {"tx": tx - 1, "tz": tz, "axis": 1, "cx": tx * GRID, "cz": (tz + 0.5) * GRID}

func edge_taken(tx: int, tz: int, axis: int) -> bool:
	return _edges.has(Vector3i(tx, tz, axis))

func edge_piece(tx: int, tz: int, axis: int):
	return _edges.get(Vector3i(tx, tz, axis))

func tile_pieces(tx: int, tz: int) -> Array:
	return _tiles.get(Vector2i(tx, tz), [])

## a tile holds one foundation, one roof, and one other thing (a station, stairs or a tower)
func tile_taken(tx: int, tz: int, kind: String) -> bool:
	for p in tile_pieces(tx, tz):
		var k := String(p.pkind)
		if k == kind or (kind != "foundation" and kind != "roof" and k != "foundation" and k != "roof"):
			return true
	return false

## where a wall on this edge stands: the higher of the ground either side (floors within reach of `feet` count)
func edge_base_y(cx: float, cz: float, axis: int, feet := INF) -> float:
	var ax := 0.5 if axis == 1 else 0.0; var az := 0.5 if axis == 0 else 0.0
	return maxf(maxf(WorldData.ground_y(cx + ax, cz + az, feet), WorldData.ground_y(cx - ax, cz - az, feet)), WorldData.height_at(cx, cz))

func tile_base_y(tx: int, tz: int, include_floors := true, feet := INF) -> float:
	var cx := (tx + 0.5) * GRID; var cz := (tz + 0.5) * GRID
	var m := -INF
	for s in [[-0.9, -0.9], [0.9, -0.9], [-0.9, 0.9], [0.9, 0.9], [0.0, 0.0]]:
		var x: float = cx + s[0]; var z: float = cz + s[1]
		m = maxf(m, WorldData.ground_y(x, z, feet) if include_floors else WorldData.height_at(x, z))
	return m

## a roof sits on the walls around its tile (their base + 3 m); with no walls, 3 m over the floor
func roof_height(tx: int, tz: int, feet := INF) -> float:
	var best := -INF
	for e in [[tx, tz, 0], [tx, tz - 1, 0], [tx, tz, 1], [tx - 1, tz, 1]]:
		var w = _edges.get(Vector3i(e[0], e[1], e[2]))
		if w != null:
			best = maxf(best, float(w.y) + 3.0)
	if best == -INF:
		best = tile_base_y(tx, tz, true, feet) + 3.0
	return best

func terrain_spread(cx: float, cz: float, hx: float, hz: float) -> float:
	var lo := INF; var hi := -INF
	for s in [[-1, -1], [1, -1], [-1, 1], [1, 1], [0, 0]]:
		var h := WorldData.height_at(cx + s[0] * hx, cz + s[1] * hz)
		lo = minf(lo, h); hi = maxf(hi, h)
	return hi - lo

# ------------------------------------------------------------------ what is available
func unlocked(id: String) -> bool:
	if not tier_gating:
		return true
	return int(defs[id].get("tier", 0)) <= 0 or unlock_milestone(id) < tier

## index of the milestone that unlocks a piece, or -1 when it is there from the start
func unlock_milestone(id: String) -> int:
	for i in milestones.size():
		if id in milestones[i].get("unlocks", []):
			return i
	return -1

func tier_ok(t: int) -> bool:
	return not tier_gating or tier >= t

func skill_missing(id: String) -> String:
	var def: Dictionary = defs[id]
	if def.has("skill") and game.player.level(String(def.skill[0])) < int(def.skill[1]):
		return "Requires %s %d" % [U.skill_name(String(def.skill[0])), int(def.skill[1])]
	return ""

func build_time(id: String) -> float:
	return clampf(0.8 + float(defs[id].hp) / 500.0, 1.0, 4.0) * (1.0 - game.player.level("carpentry") * 0.06)

# ------------------------------------------------------------------ models
func make_model(id: String) -> Node3D:
	var def: Dictionary = defs[id]
	return _instance("props/" + String(def.prop) if def.has("prop") else "pieces/" + String(def.model))

func _instance(path: String) -> Node3D:
	if not _scenes.has(path):
		_scenes[path] = load("res://assets/models/%s.glb" % path)
	var sc: PackedScene = _scenes[path]
	if sc == null:
		return Node3D.new()
	var n: Node3D = sc.instantiate()
	Mats.apply(n)
	return n

func _support_mat(id: String) -> Material:
	var m := Mats.get_mat("metalRust" if id.begins_with("metal") else ("concrete" if id.begins_with("concrete") else "woodDark"))
	if m == null:
		var sm := StandardMaterial3D.new(); sm.albedo_color = Color(0.24, 0.17, 0.11); sm.roughness = 0.9
		m = sm
	return m

func _support(holder: Node3D, id: String, size: Vector3, centre: Vector3) -> void:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new(); bm.size = size
	mi.mesh = bm; mi.material_override = _support_mat(id); mi.position = centre
	holder.add_child(mi)

# ------------------------------------------------------------------ collision and the nav grid
func _cells(b: WorldData.Box) -> PackedInt32Array:
	var out := PackedInt32Array()
	var n := WorldData.NAV_N
	var x0 := maxi(0, int(floor(b.min.x + WorldData.HALF))); var x1 := mini(n - 1, int(floor(b.max.x - 0.001 + WorldData.HALF)))
	var z0 := maxi(0, int(floor(b.min.z + WorldData.HALF))); var z1 := mini(n - 1, int(floor(b.max.z - 0.001 + WorldData.HALF)))
	for x in range(x0, x1 + 1):
		for z in range(z0, z1 + 1):
			out.append(z * n + x)
	return out

## does a box at this height stand in the way of someone walking at ground level here? (WorldData's own test
## stops at head height over bare terrain; this one also covers a wall on a raised foundation)
func _at_ground(mn: Vector3, mx: Vector3) -> bool:
	var gy := WorldData.height_at((mn.x + mx.x) * 0.5, (mn.z + mx.z) * 0.5)
	return mx.y > gy + 0.6 and mn.y < gy + 2.2

func _apply_cell(idx: int) -> void:
	var u: Array = _under[idx]
	if int(u[0]) == WorldData.NAV_SOLID:
		return                                   # something unbreakable was here first
	var best: Array = []
	for o in _owners[idx]:
		if best.is_empty() or int(o[1]) > int(best[1]):
			best = o
	WorldData.nav[idx] = int(best[1])
	WorldData.nav_ref[idx] = best[2]

func _raster(b: WorldData.Box, cost: int, p: Dictionary) -> void:
	for idx in _cells(b):
		var own: Array = _owners.get(idx, [])
		if own.is_empty():
			_under[idx] = [WorldData.nav[idx], WorldData.nav_ref.get(idx)]
		own.append([b.id, cost, p])
		_owners[idx] = own
		_apply_cell(idx)

func _unraster(b: WorldData.Box) -> void:
	for idx in _cells(b):
		if not _owners.has(idx):
			continue
		var own: Array = _owners[idx]
		for i in range(own.size() - 1, -1, -1):
			if int(own[i][0]) == b.id:
				own.remove_at(i)
		if own.is_empty():
			var u: Array = _under[idx]
			WorldData.nav[idx] = int(u[0])
			if u[1] != null: WorldData.nav_ref[idx] = u[1]
			else: WorldData.nav_ref.erase(idx)
			_owners.erase(idx); _under.erase(idx)
		else:
			_apply_cell(idx)

func _add_box(p: Dictionary, mn: Vector3, mx: Vector3, tag: String, cost: int) -> WorldData.Box:
	var b: WorldData.Box = WorldData.add_box(mn, mx, tag, p, false)
	if cost > 0 and _at_ground(mn, mx):
		_raster(b, cost, p)
	p.boxes.append(b)
	return b

func _remove_box(b: WorldData.Box) -> void:
	# WorldData clears every cell under a box it removes; keep what else stands there
	var cells := _cells(b)
	var keep_v := PackedInt32Array(); var keep_r: Array = []
	for idx in cells:
		keep_v.append(WorldData.nav[idx]); keep_r.append(WorldData.nav_ref.get(idx))
	WorldData.remove_box(b)
	for i in cells.size():
		WorldData.nav[cells[i]] = keep_v[i]
		if keep_r[i] != null: WorldData.nav_ref[cells[i]] = keep_r[i]
	_unraster(b)
	b.ref = null

func _add_floor(p: Dictionary, x0: float, z0: float, x1: float, z1: float, y: float) -> void:
	var entry := [x0, z0, x1, z1, y, int(p.id)]
	WorldData.floors.append(entry)
	p.floors.append(entry)

func _remove_floor(entry: Array) -> void:
	for i in range(WorldData.floors.size() - 1, -1, -1):
		if is_same(WorldData.floors[i], entry):
			WorldData.floors.remove_at(i)
			return

# ------------------------------------------------------------------ placing
## Put a piece up. No checks and no cost: that is the placer's business (see try_build).
func place(id: String, tx: int, tz: int, axis: int, rot: int, y: float, opts: Dictionary = {}) -> Dictionary:
	var def: Dictionary = defs[id]
	var kind := String(def.kind)
	var carp: int = game.player.level("carpentry") if game != null and game.player != null else 0
	var max_hp := float(opts.get("max_hp", float(def.hp) * (1.0 + carp * 0.08)))
	var cx := (tx + 0.5) * GRID; var cz := (tz + 0.5) * GRID
	var p := {"kind": "piece", "built": true, "id": _next_id, "def_id": id, "pkind": kind, "station": String(def.get("station", "")), "name": String(def.name),
		"tx": tx, "tz": tz, "axis": axis, "rot": rot, "y": y, "cx": cx, "cz": cz, "hp": float(opts.get("hp", max_hp)), "max_hp": max_hp, "destroyed": false,
		"node": null, "model": null, "boxes": [], "floors": []}
	_next_id += 1
	var holder := Node3D.new(); holder.name = "%s_%d" % [id, int(p.id)]
	add_child(holder)
	var model := make_model(id)
	holder.add_child(model)
	p.node = holder; p.model = model
	if EDGE_KINDS.has(kind):
		cx = (tx + 0.5) * GRID if axis == 0 else (tx + 1) * GRID
		cz = (tz + 1) * GRID if axis == 0 else (tz + 0.5) * GRID
		var wide := 4.0 if kind == "gate" else 2.0
		if kind == "gate":
			if axis == 0: cx += GRID / 2.0
			else: cz += GRID / 2.0
		model.position = Vector3(cx, y, cz); model.rotation.y = PI / 2.0 if axis == 1 else 0.0
		# on a slope the wall carries on down to the lower ground
		var lo := y - 0.3
		var g0 := WorldData.height_at(cx - (1.0 if axis == 0 else 0.0), cz - (1.0 if axis == 1 else 0.0))
		var g1 := WorldData.height_at(cx + (1.0 if axis == 0 else 0.0), cz + (1.0 if axis == 1 else 0.0))
		var ground := minf(g0, g1)
		if y - ground > 0.1 and y - ground < 2.6:
			var h := y - ground + 0.2
			_support(holder, id, Vector3(wide if axis == 0 else 0.24, h, 0.24 if axis == 0 else wide), Vector3(cx, y - h / 2.0 + 0.05, cz))
			lo = minf(lo, ground - 0.15)
		var hw := wide / 2.0 if axis == 0 else 0.12; var hd := 0.12 if axis == 0 else wide / 2.0
		if kind == "wall" or kind == "window_wall":
			_add_box(p, Vector3(cx - hw, lo, cz - hd), Vector3(cx + hw, y + 3.0, cz + hd), "pwall", NAV_WALL)
		else:
			# a doorway: solid posts either side, and a leaf that is there only while it is shut
			var leaf_w := 3.8 if kind == "gate" else 1.1
			var side := (wide - leaf_w) / 2.0
			for s in [-1.0, 1.0]:
				var ox: float = s * (wide / 2.0 - side / 2.0) if axis == 0 else 0.0
				var oz: float = s * (wide / 2.0 - side / 2.0) if axis == 1 else 0.0
				var shw := side / 2.0 if axis == 0 else 0.12; var shd := 0.12 if axis == 0 else side / 2.0
				if side > 0.05:
					# the posts share their nav cells with the doorway: solid, but the cells belong to the leaf
					_add_box(p, Vector3(cx + ox - shw, lo, cz + oz - shd), Vector3(cx + ox + shw, y + 3.0, cz + oz + shd), "pwall", 0)
			var lhw := leaf_w / 2.0 if axis == 0 else 0.1; var lhd := 0.1 if axis == 0 else leaf_w / 2.0
			var door_box: WorldData.Box = WorldData.add_box(Vector3(cx - lhw, y, cz - lhd), Vector3(cx + lhw, y + 2.3, cz + lhd), "pdoor", p, false)
			p["door_box"] = door_box; p["door_nav"] = _at_ground(door_box.min, door_box.max)
			if p.door_nav:
				_raster(door_box, NAV_DOOR, p)
			p["open"] = false; p["angle"] = 0.0
			if kind == "gate":
				p["leaf_l"] = model.find_child("gateLeafL", true, false); p["leaf_r"] = model.find_child("gateLeafR", true, false)
			else:
				var pivot := Node3D.new()
				pivot.add_child(_instance("pieces/metal_door" if id.begins_with("metal") else "pieces/wood_door"))
				pivot.position = Vector3(cx - 0.55 if axis == 0 else cx, y, cz - 0.55 if axis == 1 else cz)
				pivot.rotation.y = -PI / 2.0 if axis == 1 else 0.0
				holder.add_child(pivot)
				p["pivot"] = pivot
		_edges[Vector3i(tx, tz, axis)] = p
		if kind == "gate":
			_edges[Vector3i(tx + 1 if axis == 0 else tx, tz + 1 if axis == 1 else tz, axis)] = p
	elif kind == "foundation" or kind == "roof":
		model.position = Vector3(cx, y, cz)
		var top := y + 0.3 if kind == "foundation" else y + 0.15
		if kind == "foundation":
			var ground := INF
			for s in [[-0.95, -0.95], [0.95, -0.95], [-0.95, 0.95], [0.95, 0.95]]:
				ground = minf(ground, WorldData.height_at(cx + s[0], cz + s[1]))
			if y - ground > 0.02:
				var h := y - ground + 0.3
				_support(holder, id, Vector3(1.96, h, 1.96), Vector3(cx, y - h / 2.0 + 0.01, cz))
				# where the floor stands too high to step onto, its underside is solid
				if top - ground > 0.6:
					_add_box(p, Vector3(cx - 0.98, ground - 0.3, cz - 0.98), Vector3(cx + 0.98, top - 0.6, cz + 0.98), "pfloor", 0)
			_paint_dirt(cx, cz, 1.6)
		_add_floor(p, cx - 1.0, cz - 1.0, cx + 1.0, cz + 1.0, top)
	elif kind == "stairs":
		model.position = Vector3(cx, y, cz); model.rotation.y = rot * PI / 2.0
		_stairs(p, cx, cz, y, rot)
	elif kind == "tower":
		model.position = Vector3(cx, y, cz); model.rotation.y = rot * PI / 2.0
		for s in [[-0.9, -0.9], [0.9, -0.9], [-0.9, 0.9], [0.9, 0.9]]:
			_add_box(p, Vector3(cx + s[0] - 0.12, y, cz + s[1] - 0.12), Vector3(cx + s[0] + 0.12, y + 5.0, cz + s[1] + 0.12), "pwall", NAV_WALL)
		_add_floor(p, cx - 1.0, cz - 1.0, cx + 1.0, cz + 1.0, y + 5.0)
		# the platform's rail: the ladder (E) is the way down
		for s in [[-1.0, -1.0, 1.0, -0.92], [-1.0, 0.92, 1.0, 1.0], [-1.0, -1.0, -0.92, 1.0], [0.92, -1.0, 1.0, 1.0]]:
			_add_box(p, Vector3(cx + s[0], y + 5.0, cz + s[1]), Vector3(cx + s[2], y + 6.3, cz + s[3]), "pwall", 0)
	else:
		_station(p, def, model, cx, cz, y, rot)
	if not EDGE_KINDS.has(kind):
		var key := Vector2i(tx, tz)
		var arr: Array = _tiles.get(key, [])
		arr.append(p); _tiles[key] = arr
	p.cx = cx; p.cz = cz
	list.append(p); _by_id[int(p.id)] = p
	piece_placed.emit(p)
	return p

func _stairs(p: Dictionary, cx: float, cz: float, y: float, rot: int) -> void:
	var dir: Array = [[0, 1], [1, 0], [0, -1], [-1, 0]][rot & 3]
	var along_x: bool = dir[0] != 0
	var up: int = dir[0] + dir[1]
	var rise := STAIR_RISE / STAIR_STEPS
	# the web game's ramp, as a flight of steps the feet can take one at a time
	for i in STAIR_STEPS:
		var s0 := -1.0 + 2.0 * float(i) / STAIR_STEPS; var s1 := -1.0 + 2.0 * float(i + 1) / STAIR_STEPS + 0.002
		var a0 := minf(s0 * up, s1 * up); var a1 := maxf(s0 * up, s1 * up)
		var top := y + rise * (i + 1)
		if along_x: _add_floor(p, cx + a0, cz - 1.0, cx + a1, cz + 1.0, top)
		else: _add_floor(p, cx - 1.0, cz + a0, cx + 1.0, cz + a1, top)
	# an open flight: its sides are closed along the lower three quarters (no stepping in or off sideways), there
	# is no walking in under the treads from behind, and the two posts at the head are solid. Each top is either
	# out of stepping reach or under the feet of anyone on the treads beside it.
	for k in 3:
		for side in [-1.0, 1.0]:
			_stair_box(p, cx, cz, y, along_x, up, -1.0 + 0.5 * k, -0.5 + 0.5 * k, side * 0.94, side * 1.0, 0.0, rise * (4 * k + 7) + 0.5)
	_stair_box(p, cx, cz, y, along_x, up, 0.1, 0.2, -0.94, 0.94, 0.0, rise * 7 - 0.02)
	for side in [-1.0, 1.0]:
		_stair_box(p, cx, cz, y, along_x, up, 0.86, 0.98, side * 0.82, side * 0.94, 0.0, STAIR_RISE - 0.02)

## a box in the flight's own frame: s runs from -1 at the foot to +1 at the head, w across it
func _stair_box(p: Dictionary, cx: float, cz: float, y: float, along_x: bool, up: int, s0: float, s1: float, w0: float, w1: float, h0: float, h1: float) -> void:
	var a0 := minf(s0 * up, s1 * up); var a1 := maxf(s0 * up, s1 * up)
	var b0 := minf(w0, w1); var b1 := maxf(w0, w1)
	if along_x: _add_box(p, Vector3(cx + a0, y + h0, cz + b0), Vector3(cx + a1, y + h1, cz + b1), "pstairs", 0)
	else: _add_box(p, Vector3(cx + b0, y + h0, cz + a0), Vector3(cx + b1, y + h1, cz + a1), "pstairs", 0)

func station_size(def: Dictionary) -> Vector3:
	if def.has("prop") and prop_sizes.has(String(def.prop)):
		var s: Array = prop_sizes[String(def.prop)].size
		return Vector3(float(s[0]), float(s[1]), float(s[2]))
	return Vector3(2.0, 0.4, 2.0)

func _station(p: Dictionary, def: Dictionary, model: Node3D, cx: float, cz: float, y: float, rot: int) -> void:
	model.position = Vector3(cx, y, cz); model.rotation.y = rot * PI / 2.0
	var st := String(def.get("station", ""))
	var sz := station_size(def)
	var r90 := rot % 2 == 1
	var hw := (sz.z if r90 else sz.x) / 2.0; var hd := (sz.x if r90 else sz.z) / 2.0
	p["half"] = Vector3(hw, sz.y / 2.0, hd)
	if st == "campfire":
		_add_box(p, Vector3(cx - 0.36, y, cz - 0.36), Vector3(cx + 0.36, y + 0.4, cz + 0.36), "station", 0)     # the ring of stones: walk round it
	elif st != "bed" and st != "farm":
		_add_box(p, Vector3(cx - hw, y, cz - hd), Vector3(cx + hw, y + sz.y, cz + hd), "station", NAV_STATION)
	if st == "crate":
		p["inv"] = Inventory.new(60.0)
	elif st == "compost" or st == "rack":
		p["inv"] = Inventory.new(40.0); p["proc"] = 0.0
	if st == "campfire" or st == "stove":
		var fp := Vector3(cx, y + (0.6 if st == "stove" else 0.15), cz)
		var anchor := model.find_child("fireAnchor", true, false) as Node3D
		if anchor != null:
			fp = Vector3(cx, y, cz) + Basis(Vector3.UP, model.rotation.y) * anchor.position
		p["fire"] = {"fuel": 0.0, "lit": false, "pos": fp}
	if st == "rain":
		p["rain"] = 2.0

# ------------------------------------------------------------------ removing and damage
func remove(p: Dictionary) -> void:
	if p.destroyed:
		return
	p.destroyed = true
	piece_removed.emit(p)
	for b in p.boxes:
		_remove_box(b)
	p.boxes = []
	if p.has("door_box"):
		_remove_box(p.door_box)
		p.erase("door_box")
	for f in p.floors:
		_remove_floor(f)
	p.floors = []
	for k in _edges.keys():
		if is_same(_edges[k], p):
			_edges.erase(k)
	var key := Vector2i(int(p.tx), int(p.tz))
	if _tiles.has(key):
		var arr: Array = _tiles[key]
		for i in range(arr.size() - 1, -1, -1):
			if is_same(arr[i], p): arr.remove_at(i)
		if arr.is_empty(): _tiles.erase(key)
	for i in range(list.size() - 1, -1, -1):
		if is_same(list[i], p): list.remove_at(i)
	for i in range(_moving.size() - 1, -1, -1):
		if is_same(_moving[i], p): _moving.remove_at(i)
	_by_id.erase(int(p.id))
	if p.node != null and is_instance_valid(p.node):
		p.node.queue_free()
	p.node = null; p.model = null
	for k in ["pivot", "leaf_l", "leaf_r"]:
		p.erase(k)

func damage(p: Dictionary, dmg: float) -> void:
	if p.destroyed:
		return
	p.hp = float(p.hp) - dmg
	var pos := Vector3(float(p.cx), float(p.y) + 1.0, float(p.cz))
	if _bang_t <= 0.0 and game.player != null and pos.distance_squared_to(game.player.pos) < 2500.0:
		_bang_t = 0.12
		Sfx.play("zombie_bang", {"pos": pos})
		if p.node != null and is_instance_valid(p.node):
			var n: Node3D = p.node
			n.position = Vector3(randf_range(-0.03, 0.03), 0.0, randf_range(-0.03, 0.03))
			var tw := n.create_tween()
			tw.tween_property(n, "position", Vector3.ZERO, 0.18)
	if float(p.hp) <= 0.0:
		Sfx.play("wood_break", {"pos": pos})
		if p.has("inv") and not p.inv.stacks.is_empty():
			game.world.drop_items(Vector3(float(p.cx), float(p.y), float(p.cz)), p.inv, String(p.name))
		if game.get("fx") != null:
			game.fx.dust(pos)
		remove(p)
		if game.state == "play":
			game.hud.notify("%s destroyed!" % String(p.name), "bad")

func _on_structure_hit(ref, dmg) -> void:
	if not (ref is Dictionary):
		return
	if ref.get("built", false):
		damage(ref, float(dmg))
	elif float(ref.get("barricade", 0.0)) > 0.0:
		_hit_barricade(ref, float(dmg))

## what a piece gives back when it is taken down: three quarters of its cost, rounded up
func refund_of(p: Dictionary) -> Dictionary:
	var out := {}
	var cost := U.cost_of(defs[String(p.def_id)].cost)
	for k in cost:
		out[k] = int(ceil(int(cost[k]) * 0.75))
	return out

## why a piece cannot be taken down yet ("" when it can)
func dismantle_blocked(p: Dictionary) -> String:
	if String(p.pkind) != "foundation":
		return ""
	var top := float(p.y) + 0.3
	for q in tile_pieces(int(p.tx), int(p.tz)):
		if not is_same(q, p) and String(q.pkind) != "roof" and absf(float(q.y) - top) < 0.2:
			return "Take down what stands on it first"
	return ""

func dismantle(p: Dictionary) -> bool:
	if p.destroyed:
		return false
	var why := dismantle_blocked(p)
	if why != "":
		game.hud.notify(why + ".", "bad"); Sfx.play("ui_error")
		return false
	var pl = game.player
	var spill := Inventory.new()
	var got: Array = []
	if not free_build:
		var refund := refund_of(p)
		for k in refund:
			var n := int(refund[k])
			var room := int(floor((pl.capacity() * 1.8 - pl.weight()) / maxf(0.001, float(Items.def(k).weight))))
			var take := clampi(room, 0, n)
			if take > 0: pl.inv.add(k, take)
			if take < n: spill.add(k, n - take)
			got.append("%d %s" % [n, String(Items.def(k).name)])
	if p.has("inv"):
		for s in p.inv.stacks:
			spill.add_stack(s)
	var pos := Vector3(float(p.cx), float(p.y), float(p.cz))
	if not spill.stacks.is_empty():
		game.world.drop_items(pos, spill, String(p.name))
	remove(p)
	Sfx.play("deconstruct", {"pos": pos + Vector3(0, 1, 0)})
	if game.get("fx") != null:
		game.fx.dust(pos + Vector3(0, 0.5, 0))
	var msg := "Took down the %s." % String(p.name).to_lower()
	if not got.is_empty():
		msg += " Recovered " + ", ".join(got) + "."
	if not spill.stacks.is_empty():
		msg += " What you could not carry is on the ground."
	game.hud.notify(msg, "")
	return true

## what it takes to mend a damaged piece: a quarter of its cost, rounded up
func repair_cost(p: Dictionary) -> Dictionary:
	var out := {}
	var cost := U.cost_of(defs[String(p.def_id)].cost)
	for k in cost:
		out[k] = int(ceil(int(cost[k]) * 0.25))
	return out

func repair(p: Dictionary) -> void:
	var cost := repair_cost(p)
	if not free_build and not U.has_all(game.player.inv, cost):
		game.hud.notify("Mending it needs %s." % U.cost_text(cost), "bad"); Sfx.play("ui_error")
		return
	var pos := Vector3(float(p.cx), float(p.y) + 1.0, float(p.cz))
	Sfx.play("build_hammer", {"pos": pos})
	game.start_action("Mending the %s…" % String(p.name).to_lower(), 2.5 * (1.0 - game.player.level("carpentry") * 0.06), func() -> void:
		if p.destroyed or (not free_build and not U.take_all(game.player.inv, cost)):
			return
		p.hp = minf(float(p.max_hp), float(p.hp) + float(p.max_hp) * 0.4)
		Sfx.play("build_hammer", {"pos": pos})
		game.make_noise(game.player.pos, 10.0)
		game.player.gain_xp("carpentry", 2.0, game.game_min), true)

# ------------------------------------------------------------------ doors
func set_door(p: Dictionary, open: bool, instant := false) -> void:
	if not p.has("door_box") or bool(p.open) == open:
		return
	p.open = open
	var b: WorldData.Box = p.door_box
	b.solid = not open
	if p.door_nav:
		if open: _unraster(b)
		else: _raster(b, NAV_DOOR, p)
	if instant:
		p.angle = PI / 2.0 if open else 0.0
		_pose_door(p)
	else:
		var known := false
		for q in _moving:
			if is_same(q, p): known = true
		if not known: _moving.append(p)

func toggle_door(p: Dictionary) -> void:
	set_door(p, not bool(p.open))
	Sfx.play("door_open" if p.open else "door_close", {"pos": Vector3(float(p.cx), float(p.y) + 1.0, float(p.cz))})
	game.make_noise(game.player.pos, 4.0)

func _pose_door(p: Dictionary) -> void:
	var a := float(p.angle)
	if String(p.pkind) == "gate":
		if p.get("leaf_l") != null: p.leaf_l.rotation.y = -a
		if p.get("leaf_r") != null: p.leaf_r.rotation.y = a
	elif p.get("pivot") != null:
		p.pivot.rotation.y = (-PI / 2.0 if int(p.axis) == 1 else 0.0) + a

func _update_doors(dt: float) -> void:
	for i in range(_moving.size() - 1, -1, -1):
		var p: Dictionary = _moving[i]
		var target := PI / 2.0 if p.open else 0.0
		p.angle = lerpf(float(p.angle), target, 1.0 - exp(-dt * 8.0))
		if absf(target - float(p.angle)) < 0.01:
			p.angle = target
			_moving.remove_at(i)
		_pose_door(p)

# ------------------------------------------------------------------ stations that work on their own
func _update_stations(dt_min: float) -> void:
	var dt_h := dt_min / 60.0
	var rain := raining()
	for p in list:
		if p.has("rain") and rain > 0.0:
			p.rain = minf(10.0, float(p.rain) + dt_h * rain * 2.0)
		elif p.has("proc"):
			p.proc = float(p.proc) + dt_h
			if float(p.proc) >= 8.0:
				p.proc = 0.0
				_process_station(p)

func _process_station(p: Dictionary) -> void:
	var inv: Inventory = p.inv
	var keep := inv.capacity; inv.capacity = INF
	if String(p.station) == "rack":
		var n := mini(inv.count("raw_meat"), 4)
		if n > 0:
			inv.remove("raw_meat", n); inv.add("jerky", n * 2)
	elif String(p.station) == "compost":
		var units := 0
		for id in ORGANIC:
			var n2 := mini(inv.count(id), 12 - units)
			if n2 > 0:
				inv.remove(id, n2); units += n2
		if units >= 3:
			inv.add("fertilizer", int(units / 3.0))
	inv.capacity = keep

## how hard it is raining, 0..1 (from the weather module when there is one)
func raining() -> float:
	var w = U.mod(game, "weather")
	if w != null:
		var r = w.get("rain")
		if r != null:
			return clampf(float(r), 0.0, 1.0)
	return 0.0

# ------------------------------------------------------------------ lookups for the other systems
func piece_by_id(id: int):
	return _by_id.get(id)

## the nearest standing station of that kind within r metres, or null
func station_near(pos: Vector3, r: float, station: String):
	var best = null; var bd := r * r
	var n := int(ceil(r / GRID))
	var tx := tile_of(pos.x); var tz := tile_of(pos.z)
	for i in range(tx - n, tx + n + 1):
		for j in range(tz - n, tz + n + 1):
			for p in _tiles.get(Vector2i(i, j), []):
				if String(p.station) != station or absf(float(p.y) - pos.y) > 2.5:
					continue
				var d := Vector2(float(p.cx) - pos.x, float(p.cz) - pos.z).length_squared()
				if d < bd:
					bd = d; best = p
	return best

## is there a roof of yours over this spot?
func under_roof(pos: Vector3) -> bool:
	for p in tile_pieces(tile_of(pos.x), tile_of(pos.z)):
		if String(p.pkind) == "roof" and float(p.y) > pos.y:
			return true
	return false

# ------------------------------------------------------------------ looking at things
func mod_interact(eye: Vector3, fwd: Vector3, reach: float, wall_t: float) -> Dictionary:
	var best := {}
	var best_t := reach
	var hit: Dictionary = WorldData.raycast(eye, fwd, reach + 0.5)
	if hit.obj != null and hit.obj is WorldData.Box and hit.obj.ref is Dictionary and float(hit.t) < reach:
		var ref: Dictionary = hit.obj.ref
		if ref.get("built", false):
			var c := _piece_interact(ref, float(hit.t))
			if not c.is_empty():
				best = c; best_t = float(c.t)
		elif String(ref.get("kind", "")) in ["door", "window"]:
			var c2 := _opening_interact(ref, maxf(0.0, float(hit.t) - 0.02))
			if not c2.is_empty():
				return c2
	# things with no solid shape in the way of the ray: open doors, bedrolls, anything looked at over its top
	var pp: Vector3 = game.player.pos
	var tx := tile_of(pp.x + fwd.x); var tz := tile_of(pp.z + fwd.z)
	for i in range(tx - 1, tx + 2):
		for j in range(tz - 1, tz + 2):
			for p in _tiles.get(Vector2i(i, j), []):
				var k := String(p.pkind)
				var t := INF
				if k == "station":
					var hf: Vector3 = p.half
					t = U.ray_box(eye, fwd, Vector3(float(p.cx) - hf.x - 0.05, float(p.y), float(p.cz) - hf.z - 0.05), Vector3(float(p.cx) + hf.x + 0.05, float(p.y) + maxf(0.3, hf.y * 2.0) + 0.1, float(p.cz) + hf.z + 0.05), reach)
				elif k == "tower":
					t = U.ray_box(eye, fwd, Vector3(float(p.cx) - 1.05, float(p.y), float(p.cz) - 1.05), Vector3(float(p.cx) + 1.05, float(p.y) + 5.0, float(p.cz) + 1.05), reach)
					if pp.y > float(p.y) + 4.0 and Vector2(pp.x - float(p.cx), pp.z - float(p.cz)).length() < 1.3:
						t = minf(t, 1.0)             # on the platform: the way down is always at hand
				if t < best_t and t <= wall_t + 0.6:
					var c3 := _piece_interact(p, t)
					if not c3.is_empty():
						best = c3; best_t = t
			for axis in 2:
				var d = _edges.get(Vector3i(i, j, axis))
				if d == null or not d.has("door_box") or not d.open:
					continue
				var b: WorldData.Box = d.door_box
				var t2 := U.ray_box(eye, fwd, b.min - Vector3(0.25, 0, 0.25), b.max + Vector3(0.25, 0, 0.25), reach)
				if t2 < best_t:
					var c4 := _piece_interact(d, t2)
					if not c4.is_empty():
						best = c4; best_t = t2
	return best

func _hammer_in_hand() -> bool:
	var es = game.player.equipped_stack()
	if es == null:
		return false
	var tools = Items.def(String(es.id)).get("tools")
	return tools != null and "hammer" in tools

func _piece_interact(p: Dictionary, t: float) -> Dictionary:
	if p.destroyed:
		return {}
	var nm := String(p.name)
	if _hammer_in_hand() and float(p.hp) < float(p.max_hp) - 1.0:
		return {"t": t, "prompt": "[E]  Mend the %s  ·  %d%%  ·  %s" % [nm.to_lower(), int(round(float(p.hp) / float(p.max_hp) * 100.0)), U.cost_text(repair_cost(p))], "act": func() -> void: repair(p)}
	if p.has("door_box"):
		var what := "gate" if String(p.pkind) == "gate" else "door"
		return {"t": t, "prompt": "[E]  %s %s" % ["Close" if p.open else "Open", what], "act": func() -> void: toggle_door(p)}
	match String(p.station):
		"workbench":
			var craft = U.mod(game, "sys_craft")
			if craft == null: return {}
			return {"t": t, "prompt": "[E]  Use the workbench", "act": func() -> void: craft.open("workbench", p)}
		"campfire", "stove":
			var cook = U.mod(game, "sys_cook")
			if cook == null: return {}
			return {"t": t, "prompt": "[E]  " + String(cook.fire_prompt(p)), "act": func() -> void: cook.open(p)}
		"bed":
			var sleep = U.mod(game, "sys_sleep")
			if sleep == null: return {}
			return {"t": t, "prompt": String(sleep.bed_prompt("bedroll")), "act": func() -> void: sleep.try_sleep()}
		"crate", "compost", "rack":
			var label := String(CONTAINER_LABELS[String(p.station)])
			var sub := "%d kinds of thing inside" % p.inv.stacks.size() if not p.inv.stacks.is_empty() else "empty"
			if String(p.station) == "rack": sub = "raw meat becomes jerky  ·  %.1f h to go" % maxf(0.0, 8.0 - float(p.proc))
			elif String(p.station) == "compost": sub = "scraps and leaves become fertilizer  ·  %.1f h to go" % maxf(0.0, 8.0 - float(p.proc))
			return {"t": t, "prompt": "[E]  Open the %s  ·  %s" % [label.to_lower(), sub], "act": func() -> void:
				Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
				game.hud.open_inventory({"label": label, "inv": p.inv, "ref": p})}
		"rain":
			return {"t": t, "prompt": "[E]  %s  ·  %.1f of 10 litres" % ["Fill a bottle" if game.player.inv.count("empty_bottle") > 0 else "Drink from the rain collector", float(p.rain)], "act": func() -> void: _use_rain(p)}
	if String(p.pkind) == "tower":
		var up: bool = game.player.pos.y < float(p.y) + 4.0
		return {"t": t, "prompt": "[E]  Climb the ladder" if up else "[E]  Climb down", "act": func() -> void: _climb(p, up)}
	return {}

func _use_rain(p: Dictionary) -> void:
	var pl = game.player
	if float(p.rain) < 1.0:
		game.hud.notify("The collector is empty. Wait for rain.", ""); return
	p.rain = float(p.rain) - 1.0
	if pl.inv.count("empty_bottle") > 0:
		pl.inv.remove("empty_bottle", 1); pl.inv.add("water_bottle", 1)
		Sfx.play("splash", {"volume": 0.6}); game.hud.notify("Filled a bottle with clean rainwater.", "good")
	else:
		pl.stats.thirst = minf(100.0, pl.stats.thirst + 30.0); Sfx.play("drink")

func _climb(p: Dictionary, up: bool) -> void:
	var pl = game.player
	if up:
		pl.pos = Vector3(float(p.cx), float(p.y) + 5.05, float(p.cz))
	else:
		var x := float(p.cx) + 1.6
		pl.pos = Vector3(x, WorldData.ground_y(x, float(p.cz), float(p.y) + 0.6), float(p.cz))
	pl.vel = Vector3.ZERO; pl.fall_vel = 0.0
	Sfx.play("footstep_wood")

# ------------------------------------------------------------------ barricades on the town's doors and windows
func _bar_key(t: Dictionary) -> String:
	return ("d" if String(t.kind) == "door" else "w") + str(int(t.id))

func barricade_step() -> float:
	return 60.0 * (1.0 + game.player.level("carpentry") * 0.15)

## Board up a town door or window (one more layer, up to four). `amount` and `step` restore a saved one.
func barricade(t: Dictionary, _carpentry := 0, amount := -1.0, step := -1.0) -> void:
	var box: WorldData.Box = t.box
	if box == null:
		return
	var is_door := String(t.kind) == "door"
	var add := step if step > 0.0 else barricade_step()
	var cur := float(t.get("barricade", 0.0))
	if cur <= 0.0:
		t["bar_tag"] = box.tag; t["bar_solid"] = box.solid
		if is_door:
			t["hp_real"] = float(t.hp); t.hp = DOOR_SHIELD
	t["bar_step"] = add
	t["barricade"] = amount if amount > 0.0 else minf(add * 4.0, cur + add)
	if t.get("bar_node") == null or not is_instance_valid(t.bar_node):
		var m := _instance("pieces/barricade_door" if is_door else "pieces/barricade_window")
		var d: Dictionary = t.data
		m.position = Vector3(float(d.cx), float(d.y) if is_door else float(d.y) + 0.6 - 1.5, float(d.cz))
		m.rotation.y = PI / 2.0 if String(d.axis) == "z" else 0.0
		add_child(m)
		t["bar_node"] = m
	if is_door:
		if t.open:
			game.world.set_door(t, false)
		t.angle = 0.0; t.target = 0.0
		game.world.apply_door(t)
	_seal(t)
	_barricaded[_bar_key(t)] = t
	barricade_changed.emit(t)

func _seal(t: Dictionary) -> void:
	var box: WorldData.Box = t.box
	WorldData.set_solid(box, true, NAV_BARRICADE)
	if String(t.kind) == "window":
		WorldData.mark_breakable(box, NAV_BARRICADE, t)      # as the world does for every window: wall cells included
	box.tag = "door" if String(t.kind) == "door" else "window"

func _unbarricade(t: Dictionary) -> void:
	t["barricade"] = 0.0
	if t.get("bar_node") != null and is_instance_valid(t.bar_node):
		t.bar_node.queue_free()
	t.erase("bar_node")
	_barricaded.erase(_bar_key(t))
	var box: WorldData.Box = t.box
	box.tag = String(t.get("bar_tag", box.tag))
	if String(t.kind) == "door":
		if float(t.hp) > DOOR_SHIELD * 0.5:
			t.hp = float(t.get("hp_real", t.data.hp))
		WorldData.set_solid(box, not t.open, NAV_DOOR)
	elif t.broken or not bool(t.get("bar_solid", true)):
		WorldData.set_solid(box, false)
	else:
		WorldData.mark_breakable(box, NAV_BARRICADE, t)
	barricade_changed.emit(t)

func _hit_barricade(t: Dictionary, dmg: float) -> void:
	var d: Dictionary = t.data
	var pos := Vector3(float(d.cx), float(d.y) + 1.0, float(d.cz))
	t.barricade = float(t.barricade) - dmg
	if _bang_t <= 0.0:
		_bang_t = 0.12
		Sfx.play("zombie_bang", {"pos": pos})
	if float(t.barricade) <= 0.0:
		_unbarricade(t)
		Sfx.play("wood_break", {"pos": pos})
		if game.state == "play" and pos.distance_to(game.player.pos) < 40.0:
			game.hud.notify("A barricade has been torn down.", "bad")
	else:
		_seal(t)             # the game's own handler opens a struck window; the boards hold it shut

## the game opens doors and breaks glass without knowing about boards: put a boarded opening back as it should be
func _poll_barricades() -> void:
	for key in _barricaded:
		var t: Dictionary = _barricaded[key]
		var box: WorldData.Box = t.box
		if String(t.kind) == "door":
			if t.open:
				game.world.set_door(t, false)
				t.angle = 0.0; t.target = 0.0
				game.world.apply_door(t)
				_seal(t)
			if float(t.hp) < DOOR_SHIELD * 0.5:
				t["hp_real"] = float(t.hp); t.hp = DOOR_SHIELD
		elif not box.solid:
			_seal(t)

func _opening_interact(t: Dictionary, at: float) -> Dictionary:
	if t.get("box") == null:
		return {}
	var is_door := String(t.kind) == "door"
	var what := "door" if is_door else "window"
	var cur := float(t.get("barricade", 0.0))
	if _hammer_in_hand():
		if cur > 0.0 and cur >= float(t.get("bar_step", 60.0)) * 4.0 - 0.5:
			return {"t": at, "prompt": "The barricade is as strong as it will get", "act": func() -> void: pass}
		var label := "Reinforce the barricade" if cur > 0.0 else "Barricade the %s" % what
		return {"t": at, "prompt": "[E]  %s  ·  2 planks, 3 nails" % label, "act": func() -> void: _start_barricade(t)}
	if cur <= 0.0:
		return {}
	if game.player.inv.has_tool("pry"):
		return {"t": at, "prompt": "[E]  Pry the boards off the %s" % what, "act": func() -> void: _start_pry(t)}
	return {"t": at, "prompt": "Barricaded %s" % what, "act": func() -> void:
		game.hud.notify("It is boarded up. A crowbar would get the boards off.", "")}

func _start_barricade(t: Dictionary) -> void:
	var pl = game.player
	if not free_build and not U.has_all(pl.inv, BARRICADE_COST):
		game.hud.notify("Barricading needs 2 planks and 3 nails.", "bad"); Sfx.play("ui_error")
		return
	var d: Dictionary = t.data
	var pos := Vector3(float(d.cx), float(d.y) + 1.0, float(d.cz))
	Sfx.play("build_hammer", {"pos": pos})
	game.start_action("Barricading…", 3.5 * (1.0 - pl.level("carpentry") * 0.06), func() -> void:
		if not free_build and not U.take_all(pl.inv, BARRICADE_COST):
			return
		barricade(t)
		Sfx.play("build_hammer", {"pos": pos})
		game.make_noise(pl.pos, 10.0)
		pl.gain_xp("carpentry", 6.0, game.game_min), true)

func _start_pry(t: Dictionary) -> void:
	var d: Dictionary = t.data
	var pos := Vector3(float(d.cx), float(d.y) + 1.0, float(d.cz))
	game.start_action("Prying the boards off…", 3.0, func() -> void:
		if float(t.get("barricade", 0.0)) <= 0.0:
			return
		var layers := int(ceil(float(t.barricade) / maxf(1.0, float(t.get("bar_step", 60.0)))))
		_unbarricade(t)
		game.player.inv.add("plank", layers)
		Sfx.play("deconstruct", {"pos": pos})
		game.make_noise(game.player.pos, 8.0)
		game.hud.notify("The boards come away. Recovered %d plank%s." % [layers, "" if layers == 1 else "s"], ""), true)

# ------------------------------------------------------------------ bare earth under a foundation
func _paint_dirt(x: float, z: float, r: float) -> void:
	var world = game.world if game != null else null
	if world == null or world.get("terrain_mat") == null or world.terrain_mat == null:
		return
	var n := int(WorldData.info.terrain.get("splatN", 0))
	if n <= 0:
		return
	if _splat_img == null:
		var bytes := FileAccess.get_file_as_bytes("res://data/splat.bin")
		if bytes.size() != n * n * 4:
			return
		_splat_img = Image.create_from_data(n, n, false, Image.FORMAT_RGBA8, bytes)
	var px := WorldData.SIZE / n
	var ci := int(floor((x + WorldData.HALF) / px)); var cj := int(floor((z + WorldData.HALF) / px))
	var rr := int(ceil(r / px))
	for j in range(cj - rr, cj + rr + 1):
		for i in range(ci - rr, ci + rr + 1):
			if i < 0 or j < 0 or i >= n or j >= n:
				continue
			var dd := Vector2(i - ci, j - cj).length() / rr
			if dd > 1.0:
				continue
			var c := _splat_img.get_pixel(i, j)
			c.g = maxf(c.g, 1.0 - dd); c.b = 0.0
			_splat_img.set_pixel(i, j, c)
	_splat_dirty = true

func _push_splat() -> void:
	_splat_dirty = false
	var world = game.world
	var tex = world.terrain_mat.get_shader_parameter("splat")
	if tex is ImageTexture and _splat_img != null:
		var img: Image = _splat_img.duplicate()
		img.generate_mipmaps()
		tex.update(img)
