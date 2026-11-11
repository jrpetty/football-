extends Node
## WorldData (autoload): everything about the world that is not a picture — terrain heights, walkable floors,
## the collision boxes and cylinders, and the 1 m nav grid. A straight port of the web game's CollisionWorld,
## fed from the files the exporter writes (data/world.json, height.bin, nav.bin).

const HALF := 400.0
const SIZE := 800.0
const CELL := 8.0
const NAV_N := 800
const NAV_FREE := 0
const NAV_SOLID := 255

var info: Dictionary = {}
var heights := PackedFloat32Array()
var hn := 321
var hres := 2.5
var nav := PackedByteArray()
var nav_ref: Dictionary = {}          # nav index -> object standing there (doors, built pieces)
var forest := PackedByteArray()
var forest_n := 201
var floors: Array = []                # [x0, z0, x1, z1, y, ref]
var lake := {"x": -235.0, "z": 205.0, "r": 62.0, "level": -0.8}

# collision shapes, by id
var _boxes: Dictionary = {}           # id -> Box
var _circles: Dictionary = {}         # id -> Circle
var _cells: Dictionary = {}           # cell key -> PackedInt32Array of ids
var _next_id := 1
var export_box: Dictionary = {}       # the exporter's box id -> Box (doors and windows refer to these)

class Box:
	var id: int
	var min: Vector3
	var max: Vector3
	var solid := true
	var tag := ""
	var ref = null

class Circle:
	var id: int
	var x: float
	var z: float
	var r: float
	var y0: float
	var y1: float
	var ref = null
	var kind := ""

func _ready() -> void:
	load_world()

func load_world() -> void:
	var f := FileAccess.open("res://data/world.json", FileAccess.READ)
	if f == null:
		push_error("data/world.json is missing — run the exporter")
		return
	info = JSON.parse_string(f.get_as_text())
	hn = int(info.terrain.n); hres = float(info.terrain.res)
	heights = FileAccess.get_file_as_bytes("res://data/height.bin").to_float32_array()
	nav = FileAccess.get_file_as_bytes("res://data/nav.bin")
	forest = FileAccess.get_file_as_bytes("res://data/forest.bin")
	forest_n = int(info.terrain.get("forestN", 201))
	lake = {"x": float(info.water.x), "z": float(info.water.z), "r": float(info.water.r), "level": float(info.water.level)}
	for fl in info.floors:
		floors.append([float(fl.x0), float(fl.z0), float(fl.x1), float(fl.z1), float(fl.y), int(fl.b)])
	for b in info.boxes:
		var box := add_box(Vector3(b[0], b[1], b[2]), Vector3(b[3], b[4], b[5]), String(b[7]), null, false)
		box.solid = int(b[6]) == 1
		export_box[int(b[8])] = box
	for c in info.circles:
		var ci := add_circle(float(c[0]), float(c[1]), float(c[2]), float(c[3]), null, false)
		ci.kind = String(c[4]); ci.ref = int(c[5])

# ------------------------------------------------------------------ terrain
func height_at(x: float, z: float) -> float:
	var n := hn - 1
	var fx := clampf((x + HALF) / hres, 0.0, n - 0.0001)
	var fz := clampf((z + HALF) / hres, 0.0, n - 0.0001)
	var i := int(fx); var j := int(fz)
	var u := fx - i; var v := fz - j
	var o := j * hn + i
	var h00 := heights[o]; var h10 := heights[o + 1]; var h01 := heights[o + hn]; var h11 := heights[o + hn + 1]
	if u + v < 1.0:
		return h00 + (h10 - h00) * u + (h01 - h00) * v
	return h11 + (h01 - h11) * (1.0 - u) + (h10 - h11) * (1.0 - v)

func normal_at(x: float, z: float) -> Vector3:
	return Vector3(height_at(x - 1.0, z) - height_at(x + 1.0, z), 2.0, height_at(x, z - 1.0) - height_at(x, z + 1.0)).normalized()

func slope_at(x: float, z: float) -> float:
	return 1.0 - normal_at(x, z).y

func forest_at(x: float, z: float) -> float:
	var i := clampi(int(round((x + HALF) / 4.0)), 0, forest_n - 1)
	var j := clampi(int(round((z + HALF) / 4.0)), 0, forest_n - 1)
	return forest[j * forest_n + i] / 255.0

## walkable ground height: terrain, or a building floor / foundation the feet can reach
func ground_y(x: float, z: float, feet_y: float = INF) -> float:
	var y := height_at(x, z)
	for f in floors:
		if x > f[0] and x < f[2] and z > f[1] and z < f[3]:
			var fy: float = f[4]
			if fy > y and fy <= feet_y + 0.55:
				y = fy
	return y

func floor_at(x: float, z: float) -> Array:
	for f in floors:
		if x > f[0] and x < f[2] and z > f[1] and z < f[3]:
			return f
	return []

func water_depth(x: float, z: float) -> float:
	if Vector2(x - lake.x, z - lake.z).length() < lake.r + 12.0:
		return maxf(0.0, lake.level - height_at(x, z))
	return 0.0

func near_water(x: float, z: float) -> bool:
	return Vector2(x - lake.x, z - lake.z).length() < lake.r + 6.0 and height_at(x, z) < lake.level + 0.6

func in_bounds(x: float, z: float) -> bool:
	return absf(x) < HALF - 30.0 and absf(z) < HALF - 30.0

# ------------------------------------------------------------------ nav grid
func nav_index(x: float, z: float) -> int:
	var ix := int(floor(x + HALF)); var iz := int(floor(z + HALF))
	if ix < 0 or iz < 0 or ix >= NAV_N or iz >= NAV_N:
		return -1
	return iz * NAV_N + ix

func nav_at(x: float, z: float) -> int:
	var i := nav_index(x, z)
	return NAV_SOLID if i < 0 else nav[i]

# ------------------------------------------------------------------ shapes
func _key(cx: int, cz: int) -> int:
	return (cx + 1000) * 4096 + (cz + 1000)

func _each_cell(min_x: float, min_z: float, max_x: float, max_z: float, id: int, add: bool) -> void:
	var x0 := int(floor(min_x / CELL)); var x1 := int(floor(max_x / CELL))
	var z0 := int(floor(min_z / CELL)); var z1 := int(floor(max_z / CELL))
	for x in range(x0, x1 + 1):
		for z in range(z0, z1 + 1):
			var k := _key(x, z)
			if add:
				if not _cells.has(k):
					_cells[k] = PackedInt32Array()
				var a: PackedInt32Array = _cells[k]
				a.append(id); _cells[k] = a
			elif _cells.has(k):
				var a2: PackedInt32Array = _cells[k]
				var i := a2.find(id)
				if i >= 0:
					a2.remove_at(i); _cells[k] = a2

func add_box(mn: Vector3, mx: Vector3, tag := "", ref = null, raster := true, nav_cost := NAV_SOLID) -> Box:
	var b := Box.new()
	b.id = _next_id; _next_id += 1
	b.min = mn; b.max = mx; b.tag = tag; b.ref = ref
	_boxes[b.id] = b
	_each_cell(mn.x, mn.z, mx.x, mx.z, b.id, true)
	# anything standing between knee and head height (above the ground it stands on) blocks the nav grid
	var gy := height_at((mn.x + mx.x) * 0.5, (mn.z + mx.z) * 0.5)
	if raster and mx.y > gy + 0.6 and mn.y < gy + 1.6:
		_raster_box(b, nav_cost, ref, false)
	return b

func remove_box(b: Box) -> void:
	if not _boxes.has(b.id):
		return
	_boxes.erase(b.id)
	_each_cell(b.min.x, b.min.z, b.max.x, b.max.z, b.id, false)
	_raster_box(b, NAV_FREE, null, true)

func add_circle(x: float, z: float, r: float, h: float, ref = null, raster := true) -> Circle:
	var c := Circle.new()
	c.id = _next_id; _next_id += 1
	c.x = x; c.z = z; c.r = r; c.ref = ref
	c.y0 = height_at(x, z) - 0.5; c.y1 = c.y0 + 0.5 + maxf(h, 1.0)
	_circles[c.id] = c
	_each_cell(x - r, z - r, x + r, z + r, c.id, true)
	if raster and r > 0.25:
		_raster_circle(c, NAV_SOLID)
	return c

func remove_circle(c: Circle) -> void:
	if not _circles.has(c.id):
		return
	_circles.erase(c.id)
	_each_cell(c.x - c.r, c.z - c.r, c.x + c.r, c.z + c.r, c.id, false)
	if c.r > 0.25:
		_raster_circle(c, NAV_FREE)

func set_solid(b: Box, solid: bool, nav_cost := NAV_SOLID) -> void:
	b.solid = solid
	_raster_box(b, nav_cost if solid else NAV_FREE, b.ref if solid else null, not solid)

func _raster_box(b: Box, v: int, ref, clear: bool) -> void:
	var x0 := maxi(0, int(floor(b.min.x + HALF))); var x1 := mini(NAV_N - 1, int(floor(b.max.x - 0.001 + HALF)))
	var z0 := maxi(0, int(floor(b.min.z + HALF))); var z1 := mini(NAV_N - 1, int(floor(b.max.z - 0.001 + HALF)))
	for x in range(x0, x1 + 1):
		for z in range(z0, z1 + 1):
			var i := z * NAV_N + x
			if clear:
				nav[i] = NAV_FREE; nav_ref.erase(i)
			else:
				if nav[i] == NAV_SOLID and v != NAV_SOLID:
					continue
				nav[i] = v
				if ref != null and v != NAV_SOLID:
					nav_ref[i] = ref

## mark a solid box's cells as something that can be broken through (a window, say), whatever was there
func mark_breakable(b: Box, cost: int, ref) -> void:
	var x0 := maxi(0, int(floor(b.min.x + HALF))); var x1 := mini(NAV_N - 1, int(floor(b.max.x - 0.001 + HALF)))
	var z0 := maxi(0, int(floor(b.min.z + HALF))); var z1 := mini(NAV_N - 1, int(floor(b.max.z - 0.001 + HALF)))
	for x in range(x0, x1 + 1):
		for z in range(z0, z1 + 1):
			nav[z * NAV_N + x] = cost; nav_ref[z * NAV_N + x] = ref

func _raster_circle(c: Circle, v: int) -> void:
	var x0 := maxi(0, int(floor(c.x - c.r * 0.7 + HALF))); var x1 := mini(NAV_N - 1, int(floor(c.x + c.r * 0.7 + HALF)))
	var z0 := maxi(0, int(floor(c.z - c.r * 0.7 + HALF))); var z1 := mini(NAV_N - 1, int(floor(c.z + c.r * 0.7 + HALF)))
	for x in range(x0, x1 + 1):
		for z in range(z0, z1 + 1):
			nav[z * NAV_N + x] = v

var _stamp := 0
var _seen: Dictionary = {}
## every solid box and every cylinder overlapping the rectangle
func query(min_x: float, min_z: float, max_x: float, max_z: float) -> Array:
	var out: Array = []
	_stamp += 1
	var x0 := int(floor(min_x / CELL)); var x1 := int(floor(max_x / CELL))
	var z0 := int(floor(min_z / CELL)); var z1 := int(floor(max_z / CELL))
	for cx in range(x0, x1 + 1):
		for cz in range(z0, z1 + 1):
			var a = _cells.get(_key(cx, cz))
			if a == null:
				continue
			for id in a:
				if _seen.get(id, 0) == _stamp:
					continue
				_seen[id] = _stamp
				var b = _boxes.get(id)
				if b != null:
					if b.solid and b.max.x > min_x and b.min.x < max_x and b.max.z > min_z and b.min.z < max_z:
						out.append(b)
					continue
				var c = _circles.get(id)
				if c != null and c.x + c.r > min_x and c.x - c.r < max_x and c.z + c.r > min_z and c.z - c.r < max_z:
					out.append(c)
	if _stamp > 1000000:
		_stamp = 0; _seen.clear()
	return out

## Push a standing cylinder (feet at pos.y) out of the world. Returns {pos, hit, ground_y}: ground_y is the top
## of anything low enough to step onto (-INF if nothing).
func resolve(pos: Vector3, r: float, h: float, step := 0.45, ignore_tag := "", ignore_tag2 := "") -> Dictionary:
	var hit := false
	var gy := -INF
	var list := query(pos.x - r - 0.1, pos.z - r - 0.1, pos.x + r + 0.1, pos.z + r + 0.1)
	for _iter in 2:
		for o in list:
			if o is Box:
				if ignore_tag != "" and (o.tag == ignore_tag or o.tag == ignore_tag2):
					continue
				if pos.y + h <= o.min.y or pos.y >= o.max.y:
					continue
				if o.max.y - pos.y <= step and pos.x > o.min.x - r * 0.5 and pos.x < o.max.x + r * 0.5 and pos.z > o.min.z - r * 0.5 and pos.z < o.max.z + r * 0.5:
					if o.max.y > gy:
						gy = o.max.y
					continue
				var cx := clampf(pos.x, o.min.x, o.max.x); var cz := clampf(pos.z, o.min.z, o.max.z)
				var dx := pos.x - cx; var dz := pos.z - cz
				var d2 := dx * dx + dz * dz
				if d2 >= r * r:
					continue
				if d2 < 1e-8:
					var pl: float = pos.x - o.min.x; var pr: float = o.max.x - pos.x; var pb: float = pos.z - o.min.z; var pf: float = o.max.z - pos.z
					var m := minf(minf(pl, pr), minf(pb, pf))
					if m == pl: pos.x = o.min.x - r
					elif m == pr: pos.x = o.max.x + r
					elif m == pb: pos.z = o.min.z - r
					else: pos.z = o.max.z + r
				else:
					var d := sqrt(d2)
					pos.x += dx / d * (r - d); pos.z += dz / d * (r - d)
				hit = true
			else:
				if pos.y > o.y1 or pos.y + h < o.y0:
					continue
				var ex: float = pos.x - o.x; var ez: float = pos.z - o.z
				var e2 := ex * ex + ez * ez; var rr: float = r + o.r
				if e2 >= rr * rr or e2 < 1e-8:
					continue
				var e := sqrt(e2)
				pos.x += ex / e * (rr - e); pos.z += ez / e * (rr - e)
				hit = true
	return {"pos": pos, "hit": hit, "ground_y": gy}

## the interface the character code uses: just the corrected position
func resolve_cylinder(pos: Vector3, r: float, h: float) -> Vector3:
	return resolve(pos, r, h).pos

## Ray against boxes and cylinders. Returns {t, obj, normal}; t is INF on a miss. `tall_only` keeps just the
## boxes that would block a line of sight.
func raycast(origin: Vector3, dir: Vector3, max_dist: float, tall_only := false, skip = null) -> Dictionary:
	var end := origin + dir * max_dist
	var list := query(minf(origin.x, end.x) - 0.5, minf(origin.z, end.z) - 0.5, maxf(origin.x, end.x) + 0.5, maxf(origin.z, end.z) + 0.5)
	var best := INF
	var best_obj = null
	var n := Vector3.ZERO
	for o in list:
		if o == skip:
			continue
		if o is Box:
			if tall_only and o.max.y - o.min.y <= 1.2:
				continue
			var tmin := 0.0; var tmax := max_dist; var axis := -1; var sgn := 0.0; var ok := true
			for a in 3:
				var da: float = dir[a]; var oa: float = origin[a]; var mn: float = o.min[a]; var mx: float = o.max[a]
				if absf(da) < 1e-9:
					if oa < mn or oa > mx:
						ok = false; break
					continue
				var t1 := (mn - oa) / da; var t2 := (mx - oa) / da; var s := -1.0
				if t1 > t2:
					var tt := t1; t1 = t2; t2 = tt; s = 1.0
				if t1 > tmin:
					tmin = t1; axis = a; sgn = s
				if t2 < tmax:
					tmax = t2
				if tmin > tmax:
					ok = false; break
			if ok and tmin < best and tmin > 0.0:
				best = tmin; best_obj = o; n = Vector3.ZERO
				if axis >= 0:
					n[axis] = sgn
		elif not tall_only:
			var ox: float = origin.x - o.x; var oz: float = origin.z - o.z
			var qa := dir.x * dir.x + dir.z * dir.z
			if qa < 1e-9:
				continue
			var qb := 2.0 * (ox * dir.x + oz * dir.z); var qc: float = ox * ox + oz * oz - o.r * o.r
			var disc := qb * qb - 4.0 * qa * qc
			if disc < 0.0:
				continue
			var t := (-qb - sqrt(disc)) / (2.0 * qa)
			var hy := origin.y + dir.y * t
			if t > 0.0 and t < best and hy < o.y1 and hy > o.y0:
				best = t; best_obj = o; n = Vector3(ox + dir.x * t, 0.0, oz + dir.z * t).normalized()
	return {"t": best, "obj": best_obj, "normal": n}

## is the straight line from a to b blocked by a wall?
func blocked(a: Vector3, b: Vector3) -> bool:
	var d := b - a
	var l := d.length()
	if l < 0.01:
		return false
	return raycast(a, d / l, l, true).t < l

## ray against the ground (terrain and floors); returns distance or INF
func ray_ground(origin: Vector3, dir: Vector3, max_dist: float) -> float:
	var t := 0.0
	var step := 0.5
	var prev := 0.0
	while t < max_dist:
		var p := origin + dir * t
		if p.y < ground_y(p.x, p.z, p.y + 0.6):
			var lo := prev; var hi := t
			for _i in 6:
				var mid := (lo + hi) * 0.5
				var q := origin + dir * mid
				if q.y < ground_y(q.x, q.z, q.y + 0.6): hi = mid
				else: lo = mid
			return hi
		prev = t
		t += step
		step = minf(2.0, step * 1.15)
	return INF
