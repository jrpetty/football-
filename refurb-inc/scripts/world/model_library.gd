class_name ModelLibrary
extends RefCounted
## Finds 3D models dropped anywhere under res://assets by their file name, and fits them to the game's sizes.
##
## Drop "Meshy_AI_laptop_tier1_student_1018…glb" into assets/ and every model whose data says
## "match": [["laptop", "tier1"], ["laptop", "student"]] uses it: no wiring, no exact names needed.
## Imported models are scaled to the data size (laptops by width, desktops by height), stood on the floor,
## centred, and turned so their long side matches. visual.model_rot fixes a model that faces backwards.

const ROOTS := ["res://assets"]
const SKIP_DIRS := ["generated", "materials", "textures", "audio"]
const EXTENSIONS := ["glb", "gltf", "fbx", "obj", "blend", "tscn", "scn"]

static var _files: Array = []        # [{"path": String, "key": String}]
static var _scanned := false
static var _resolved := {}           # str(match) -> path ("" = no match)


static func rescan() -> void:
	_files.clear()
	_resolved.clear()
	for root in ROOTS:
		_scan(root)
	_scanned = true


static func files() -> Array:
	if not _scanned:
		rescan()
	return _files


static func find(match_groups: Array) -> String:
	## Best model file for these keyword groups, or "" if none is present.
	if match_groups.is_empty():
		return ""
	var cache_key := str(match_groups)
	if _resolved.has(cache_key):
		return _resolved[cache_key]
	var names: Array = files().map(func(f): return f.key)
	var i := best_match(match_groups, names)
	var path: String = files()[i].path if i >= 0 else ""
	_resolved[cache_key] = path
	return path


static func best_match(match_groups: Array, names: Array) -> int:
	## Index of the name that satisfies the most specific keyword group (all words present), or -1.
	## Ties go to the shorter name (fewer extra words = closer match).
	var best := -1
	var best_score := 0
	for i in names.size():
		var name: String = normalise(String(names[i]))
		for group in match_groups:
			var words: Array = group if group is Array else [group]
			if words.all(func(w): return name.contains(normalise(String(w)))):
				var score := words.size() * 1000 - name.length()
				if score > best_score:
					best_score = score
					best = i
	return best


static func normalise(s: String) -> String:
	var out := ""
	for ch in s.to_lower():
		out += ch if (ch >= "a" and ch <= "z") or (ch >= "0" and ch <= "9") else "_"
	return out


static func _scan(dir: String) -> void:
	for entry in _list(dir):
		if entry.ends_with("/"):
			var sub := entry.trim_suffix("/")
			if not sub in SKIP_DIRS and not sub.begins_with("."):
				_scan(dir.path_join(sub))
			continue
		var file := entry.trim_suffix(".import").trim_suffix(".remap")
		if file.get_extension().to_lower() in EXTENSIONS:
			var path := dir.path_join(file)
			if not _files.any(func(f): return f.path == path):
				_files.append({"path": path, "key": normalise(file.get_basename())})


static func _list(dir: String) -> PackedStringArray:
	# ResourceLoader.list_directory understands exported (remapped) projects; DirAccess is the fallback.
	if ResourceLoader.has_method("list_directory"):
		return ResourceLoader.call("list_directory", dir)
	var out := PackedStringArray()
	var d := DirAccess.open(dir)
	if d == null:
		return out
	for sub in d.get_directories():
		out.append(sub + "/")
	out.append_array(d.get_files())
	return out


# ================================================================= fitting

static func instantiate_fitted(path: String, target: Vector3, fit_axis: String, rot_deg: float = 0.0) -> Node3D:
	## Load a model and return it wrapped in a node that is target-sized, bottom-centred and facing +Z.
	if path == "" or not ResourceLoader.exists(path):
		return null
	var res := load(path)
	var inst: Node3D = null
	if res is PackedScene:
		inst = (res as PackedScene).instantiate() as Node3D
	elif res is Mesh:
		var mi := MeshInstance3D.new()
		mi.mesh = res
		inst = mi
	if inst == null:
		return null
	return fit(inst, target, fit_axis, rot_deg)


static func fit(model: Node3D, target: Vector3, fit_axis: String, rot_deg: float = 0.0) -> Node3D:
	## Wraps `model` so its bounds are scaled to `target` along `fit_axis` ("x" width or "y" height),
	## stood on y = 0 and centred. If the model's long horizontal side doesn't match the target's, it is
	## turned 90°. The wrapper's meta "fitted_size" holds the resulting size.
	var wrapper := Node3D.new()
	wrapper.name = "Fitted"
	var turn := Node3D.new()
	turn.name = "Turn"
	wrapper.add_child(turn)
	turn.add_child(model)
	var box := mesh_aabb(model)
	if box.size == Vector3.ZERO:
		wrapper.set_meta("fitted_size", target)
		return wrapper
	var yaw := rot_deg
	var long_x_model := box.size.x >= box.size.z
	var long_x_target := target.x >= target.z
	if long_x_model != long_x_target and absf(box.size.x - box.size.z) > 0.08 * maxf(box.size.x, box.size.z):
		yaw += 90.0
	var swapped := int(round(yaw / 90.0)) % 2 != 0
	var size := Vector3(box.size.z, box.size.y, box.size.x) if swapped else box.size
	var s := 1.0
	match fit_axis:
		"y":
			s = target.y / maxf(size.y, 0.0001)
		"z":
			s = target.z / maxf(size.z, 0.0001)
		_:
			s = target.x / maxf(size.x, 0.0001)
	# Centre the model's bounds on the origin, sat on the floor.
	model.position = -Vector3(box.get_center().x, box.position.y, box.get_center().z)
	turn.rotation_degrees.y = yaw
	turn.scale = Vector3.ONE * s
	wrapper.set_meta("fitted_size", size * s)
	return wrapper


static func mesh_aabb(root: Node3D) -> AABB:
	## Combined bounds of every mesh under `root`, in root's own space (works before entering the tree).
	var have := false
	var box := AABB()
	var stack: Array = [[root, Transform3D.IDENTITY]]
	while not stack.is_empty():
		var e: Array = stack.pop_back()
		var n: Node = e[0]
		var xf: Transform3D = e[1]
		if n is MeshInstance3D and (n as MeshInstance3D).mesh:
			var a: AABB = xf * (n as MeshInstance3D).mesh.get_aabb()
			box = a if not have else box.merge(a)
			have = true
		for c in n.get_children():
			var cxf := xf * (c as Node3D).transform if c is Node3D else xf
			stack.append([c, cxf])
	return box
