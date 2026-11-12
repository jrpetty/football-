class_name SlotHost
extends RefCounted
## Shared helper for anything that owns SnapSlots (stations, shelves, pallets).


static func collect(root: Node) -> Array:
	var out: Array = []
	_walk(root, out)
	return out


static func _walk(n: Node, out: Array) -> void:
	for c in n.get_children():
		if c is SnapSlot:
			out.append(c)
		_walk(c, out)


static func best_slot(slots: Array, kind: String, hit_pos: Vector3) -> SnapSlot:
	## Nearest free slot (to where the player is looking) that accepts this kind.
	var best: SnapSlot = null
	var best_d := INF
	for s: SnapSlot in slots:
		if not s.can_take(kind):
			continue
		var d := s.global_position.distance_squared_to(hit_pos)
		if d < best_d:
			best_d = d
			best = s
	return best
