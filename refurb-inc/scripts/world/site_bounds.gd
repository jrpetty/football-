class_name SiteBounds
extends Node3D
## Keeps players on the premises: an invisible fence around the walkable site and solid ground under every
## part of it. The scenery beyond (street, fields, hills) stays visible but out of reach.
##   • Bounds come from buildings.json "play_bounds" [x, z, w, d] if set, otherwise they're derived from the level:
##     the building floor, its markers (spawns, goods-in, drops), dock spots and van park, padded by `margin`,
##     and stopped at the kerb of the road the lorry arrives on.
##   • Ground: a grid of downward probes; any cell with a bare spot (nothing to stand on within 3 m) gets a slab
##     whose top is y = 0, flush with the drive/yard. Sunken dock aprons are found by the probes and left alone.

const WALL_H := 6.0
const CELL := 1.5

var bounds := Rect2()
var filled_cells := 0
var slabs := 0


static func compute_bounds(level: LevelRoot) -> Rect2:
	var bdef := Content.building(level.building_id)
	var pb: Array = bdef.get("play_bounds", [])
	if pb.size() == 4:
		return Rect2(float(pb[0]), float(pb[1]), float(pb[2]), float(pb[3]))
	var r := Rect2()
	var have := false
	for fr: Rect2 in level.floor_rects:
		r = fr if not have else r.merge(fr)
		have = true
	var pts: Array[Vector3] = []
	for n in [level.safe_drop, level.lorry_drop, level.parcel_drop, level.goods_in]:
		if n:
			pts.append(n.global_position)
	for sp in level.spawn_points:
		if sp:
			pts.append(sp.global_position)
	var vd := level.get_node_or_null("VehicleDirector")
	var road_z := INF
	if vd:
		var docks := vd.get_node_or_null("Docks")
		if docks:
			for spot in docks.get_children():
				if spot is Node3D:
					pts.append(spot.global_position)
		var vp := vd.get_node_or_null("VanPark")
		if vp:
			pts.append(vp.global_position)
		var lin := vd.get_node_or_null("LorryIn")
		if lin and lin.get_child_count() > 0:
			road_z = (lin.get_child(0) as Node3D).global_position.z
	for p in pts:
		var pr := Rect2(p.x, p.z, 0, 0)
		r = pr if not have else r.merge(pr)
		have = true
	var size := maxf(r.size.x, r.size.y)
	var margin := clampf(size * 0.35, 6.0, 30.0)
	r = r.grow(margin)
	if road_z < INF and road_z > r.position.y:
		# Stand on the pavement, not in the road: stop ~3 m short of the lane the lorry uses.
		r.size.y = minf(r.end.y, road_z - 3.0) - r.position.y
	return r


func build(level: LevelRoot) -> void:
	name = "SiteBounds"
	bounds = compute_bounds(level)
	var body := StaticBody3D.new()
	body.name = "Fence"
	body.collision_layer = 1        # world only: blocks players and loose items, not the interaction ray
	body.collision_mask = 0
	add_child(body)
	var b := bounds
	var cx := b.get_center().x
	var cz := b.get_center().y
	_wall(body, Vector3(b.size.x + 1.0, WALL_H, 0.5), Vector3(cx, WALL_H / 2 - 1.0, b.position.y - 0.25))
	_wall(body, Vector3(b.size.x + 1.0, WALL_H, 0.5), Vector3(cx, WALL_H / 2 - 1.0, b.end.y + 0.25))
	_wall(body, Vector3(0.5, WALL_H, b.size.y + 1.0), Vector3(b.position.x - 0.25, WALL_H / 2 - 1.0, cz))
	_wall(body, Vector3(0.5, WALL_H, b.size.y + 1.0), Vector3(b.end.x + 0.25, WALL_H / 2 - 1.0, cz))


func fill_ground(space: PhysicsDirectSpaceState3D) -> void:
	## Run once physics has the level's static bodies: lay slabs wherever nothing is underfoot.
	var body := StaticBody3D.new()
	body.name = "Ground"
	body.collision_layer = 1
	body.collision_mask = 0
	add_child(body)
	var nx := int(ceil(bounds.size.x / CELL))
	var nz := int(ceil(bounds.size.y / CELL))
	var q := PhysicsRayQueryParameters3D.new()
	q.collision_mask = 1
	for ix in nx:
		var x := bounds.position.x + (ix + 0.5) * CELL
		var run_start := -1
		for iz in nz + 1:
			var bare := false
			if iz < nz:
				bare = not _has_ground(space, q, x, bounds.position.y + (iz + 0.5) * CELL)
			if bare:
				filled_cells += 1
				if run_start < 0:
					run_start = iz
			elif run_start >= 0:
				# One long slab per run of bare cells keeps the shape count (and physics cost) low.
				_slab(body, x, run_start, iz - 1)
				run_start = -1


func _has_ground(space: PhysicsDirectSpaceState3D, q: PhysicsRayQueryParameters3D, x: float, z: float) -> bool:
	# Probe the cell centre and four corners: any bare spot gets the cell a slab (a slab under existing
	# ground at y = 0 is harmless; a gap is not).
	var h := CELL * 0.48
	for o in [Vector2.ZERO, Vector2(-h, -h), Vector2(h, -h), Vector2(-h, h), Vector2(h, h)]:
		q.from = Vector3(x + o.x, 0.6, z + o.y)
		q.to = Vector3(x + o.x, -3.0, z + o.y)
		if space.intersect_ray(q).is_empty():
			return false
	return true


func _slab(body: StaticBody3D, x: float, iz0: int, iz1: int) -> void:
	var z0 := bounds.position.y + iz0 * CELL
	var z1 := bounds.position.y + (iz1 + 1) * CELL
	var cs := CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = Vector3(CELL + 0.02, 0.4, z1 - z0 + 0.02)
	cs.shape = bs
	cs.position = Vector3(x, -0.2, (z0 + z1) * 0.5)
	body.add_child(cs)
	slabs += 1


func contains(p: Vector3) -> bool:
	return bounds.grow(0.5).has_point(Vector2(p.x, p.z))


func _wall(body: StaticBody3D, size: Vector3, pos: Vector3) -> void:
	var cs := CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = size
	cs.shape = bs
	cs.position = pos
	body.add_child(cs)
