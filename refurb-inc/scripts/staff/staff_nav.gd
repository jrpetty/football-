class_name StaffNav
extends RefCounted
## Walkable grid for AI staff, built from the level's real collision (walls, pillars, doors, equipment) by
## physics probes over the site — so it works in every premises without hand-made navmeshes — and searched
## with Godot's AStarGrid2D (jump point search). Paths are string-pulled so people walk straight lines.

const CELL := 0.4
const CLEAR := 0.26          # half-width of the probe (a person's shoulders)

var astar := AStarGrid2D.new()
var origin := Vector2.ZERO
var size := Vector2i.ZERO
var _world: World3D
var _built := false


func build(world: World3D, area: Rect2) -> void:
	_world = world
	origin = area.position
	size = Vector2i(int(ceil(area.size.x / CELL)), int(ceil(area.size.y / CELL)))
	astar.region = Rect2i(Vector2i.ZERO, size)
	astar.cell_size = Vector2.ONE * CELL
	astar.diagonal_mode = AStarGrid2D.DIAGONAL_MODE_ONLY_IF_NO_OBSTACLES
	astar.default_compute_heuristic = AStarGrid2D.HEURISTIC_OCTILE
	astar.default_estimate_heuristic = AStarGrid2D.HEURISTIC_OCTILE
	astar.jumping_enabled = true
	astar.update()
	refresh(Rect2(origin, area.size))
	_built = true


func refresh(area: Rect2) -> void:
	## Re-probe the cells inside `area` (after equipment moves).
	var space := _world.direct_space_state
	var shape := BoxShape3D.new()
	shape.size = Vector3(CLEAR * 2.0, 1.1, CLEAR * 2.0)
	var q := PhysicsShapeQueryParameters3D.new()
	q.shape = shape
	q.collision_mask = 1
	var a := _cell(area.position)
	var b := _cell(area.end)
	for cy in range(maxi(a.y, 0), mini(b.y + 1, size.y)):
		for cx in range(maxi(a.x, 0), mini(b.x + 1, size.x)):
			var c := _centre(Vector2i(cx, cy))
			q.transform = Transform3D(Basis.IDENTITY, Vector3(c.x, 0.75, c.y))
			var blocked := not space.intersect_shape(q, 1).is_empty()
			if not blocked:
				# Something to stand on (no walking off the edge of the site).
				var rq := PhysicsRayQueryParameters3D.create(Vector3(c.x, 1.0, c.y), Vector3(c.x, -0.5, c.y), 1)
				blocked = space.intersect_ray(rq).is_empty()
			astar.set_point_solid(Vector2i(cx, cy), blocked)


func _cell(p: Vector2) -> Vector2i:
	return Vector2i(int(floor((p.x - origin.x) / CELL)), int(floor((p.y - origin.y) / CELL)))


func _centre(c: Vector2i) -> Vector2:
	return origin + (Vector2(c) + Vector2(0.5, 0.5)) * CELL


func nearest_free(p: Vector3, max_r: int = 8) -> Vector2i:
	var c := _cell(Vector2(p.x, p.z))
	if astar.is_in_boundsv(c) and not astar.is_point_solid(c):
		return c
	for r in range(1, max_r + 1):
		var best := Vector2i(-1, -1)
		var bd := INF
		for dy in range(-r, r + 1):
			for dx in range(-r, r + 1):
				if maxi(absi(dx), absi(dy)) != r:
					continue
				var n := c + Vector2i(dx, dy)
				if astar.is_in_boundsv(n) and not astar.is_point_solid(n):
					var d := _centre(n).distance_squared_to(Vector2(p.x, p.z))
					if d < bd:
						bd = d
						best = n
		if best.x >= 0:
			return best
	return Vector2i(-1, -1)


func path(from: Vector3, to: Vector3) -> PackedVector3Array:
	## World-space waypoints (y = 0) from `from` to the walkable spot nearest `to`. Empty = no route.
	var out := PackedVector3Array()
	if not _built:
		return out
	var a := nearest_free(from)
	var b := nearest_free(to)
	if a.x < 0 or b.x < 0:
		return out
	var ids := astar.get_id_path(a, b)
	if ids.is_empty():
		return out
	# String-pull: keep a waypoint only where the straight line to the next would clip something.
	var pts: Array[Vector2i] = []
	pts.append(ids[0])
	var anchor := 0
	for i in range(2, ids.size()):
		if not _clear_line(ids[anchor], ids[i]):
			pts.append(ids[i - 1])
			anchor = i - 1
	if ids.size() > 1:
		pts.append(ids[ids.size() - 1])
	for c in pts:
		var w := _centre(c)
		out.append(Vector3(w.x, 0.0, w.y))
	# End exactly at the target if it's walkable.
	if not out.is_empty() and Vector2(to.x, to.z).distance_to(Vector2(out[out.size() - 1].x, out[out.size() - 1].z)) < CELL:
		out[out.size() - 1] = Vector3(to.x, 0.0, to.z)
	return out


func _clear_line(a: Vector2i, b: Vector2i) -> bool:
	var d := b - a
	var n := maxi(absi(d.x), absi(d.y)) * 2
	for i in range(n + 1):
		var t := float(i) / maxf(n, 1)
		var c := Vector2i(roundi(lerpf(a.x, b.x, t)), roundi(lerpf(a.y, b.y, t)))
		if astar.is_point_solid(c):
			return false
	return true
