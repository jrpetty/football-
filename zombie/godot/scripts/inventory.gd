class_name Inventory
extends RefCounted
## A pile of item stacks with an optional weight limit. Stacks are dictionaries: {uid, id, n, dur?, ammo?, age?}.

static var _uid := 1
var stacks: Array = []
var capacity := INF

func _init(cap := INF) -> void:
	capacity = cap

static func new_uid() -> int:
	_uid += 1
	return _uid - 1

static func bump_uid(minimum: int) -> void:
	if _uid <= minimum:
		_uid = minimum + 1

static func make_stack(id: String, n := 1) -> Dictionary:
	var d := Items.def(id)
	var s := {"uid": new_uid(), "id": id, "n": n}
	if d.has("weapon"):
		s["dur"] = float(d.weapon.durability)
		if d.weapon.has("ammo"):
			s["ammo"] = 0
	if d.has("spoilDays"):
		s["age"] = 0.0
	if d.get("light", false):
		s["dur"] = 100.0
	return s

func weight() -> float:
	var w := 0.0
	for s in stacks:
		w += float(Items.def(s.id).weight) * int(s.n)
	return w

func count(id: String) -> int:
	var c := 0
	for s in stacks:
		if s.id == id:
			c += int(s.n)
	return c

func find(id: String):
	for s in stacks:
		if s.id == id:
			return s
	return null

func by_uid(uid) -> Variant:
	if uid == null:
		return null
	for s in stacks:
		if int(s.uid) == int(uid):
			return s
	return null

func has_tool(tag: String) -> bool:
	for s in stacks:
		var t = Items.def(s.id).get("tools")
		if t != null and tag in t:
			return true
	return false

## adds items; returns how many did not fit
func add(id: String, n := 1, proto: Dictionary = {}) -> int:
	var d := Items.def(id)
	if capacity != INF:
		var free := capacity - weight()
		var fit := maxi(0, int(floor(free / maxf(0.001, float(d.weight)))))
		if fit < n:
			var left_over := n - fit
			if fit > 0:
				add(id, fit, proto)
			return left_over
	var left := n
	var stackable: bool = int(d.stack) > 1 and not d.has("weapon") and not d.get("light", false)
	if stackable:
		for s in stacks:
			if s.id != id or int(s.n) >= int(d.stack):
				continue
			if d.has("spoilDays") and absf(float(s.get("age", 0.0)) - float(proto.get("age", 0.0))) > 0.5:
				continue
			var k := mini(left, int(d.stack) - int(s.n))
			s.n = int(s.n) + k; left -= k
			if left == 0:
				return 0
	while left > 0:
		var k2 := mini(left, maxi(1, int(d.stack)))
		var st := make_stack(id, k2)
		for key in ["dur", "ammo", "age"]:
			if proto.has(key):
				st[key] = proto[key]
		stacks.append(st); left -= k2
	return 0

func add_stack(st: Dictionary) -> bool:
	var d := Items.def(st.id)
	if capacity != INF and weight() + float(d.weight) * int(st.n) > capacity + 1e-6:
		return false
	if int(d.stack) > 1 and not d.has("weapon") and not d.get("light", false):
		return add(st.id, int(st.n), st) == 0
	if not st.has("uid"):
		st["uid"] = new_uid()
	stacks.append(st)
	return true

func remove(id: String, n := 1) -> bool:
	if count(id) < n:
		return false
	var left := n
	for i in range(stacks.size() - 1, -1, -1):
		var s: Dictionary = stacks[i]
		if s.id != id:
			continue
		var k := mini(left, int(s.n))
		s.n = int(s.n) - k; left -= k
		if int(s.n) <= 0:
			stacks.remove_at(i)
		if left == 0:
			break
	return true

func remove_uid(uid: int, n := 1) -> Variant:
	for i in stacks.size():
		var s: Dictionary = stacks[i]
		if int(s.uid) != uid:
			continue
		if int(s.n) <= n:
			stacks.remove_at(i)
			return s
		s.n = int(s.n) - n
		var part := s.duplicate(); part.n = n; part.uid = new_uid()
		return part
	return null

func to_rows() -> Array:
	return stacks.duplicate(true)

func load_rows(rows: Array) -> void:
	stacks = []
	for r in rows:
		var s: Dictionary = r.duplicate()
		if not s.has("uid"):
			s["uid"] = new_uid()
		s.uid = int(s.uid); s.n = int(s.n)
		bump_uid(s.uid)
		stacks.append(s)
