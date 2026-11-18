extends SceneTree
## Turns raw AI-generated .glb models (Meshy: one mesh, 0.2–3.7M tris, 4K textures, normalised to a 1.9 m box)
## into game-ready assets under assets/models/<category>/<name>/:
##   <name>.tscn   wrapper scene (what hardware JSON / ModelSlots point at)
##   *.res         ArrayMeshes with Godot LODs (LOD0 = the `tris` budget in models.json)
##   material.tres + albedo/orm/normal .jpg (2048 px)
## Laptops are split into a base and a hinged "Lid" (closed by default, pivot on the hinge) with a "Screen" quad,
## per docs/MODEL_CONTRACT.md. Simplification keeps the source normals/UVs (meshoptimizer via ImporterMesh);
## the lid is cut off along a plane with interpolated attributes so the hinge edge stays clean.
##
## Run it in the tiny helper project next to this script so the main project doesn't need importing:
##   godot --headless --path tools/model_pipeline/runner -s res://../process_models.gd -- "<source dir>" [name_filter]
## (tools/model_pipeline/run.sh wraps this.) Re-running overwrites the outputs.

const TEX_SIZE := 2048

var project_dir := ""
var cfg: Array = []


func _init() -> void:
	var args := OS.get_cmdline_user_args()
	if args.is_empty():
		push_error("usage: -- <source_dir> [filter]")
		quit(1)
		return
	var here: String = ProjectSettings.globalize_path((get_script() as Script).resource_path).get_base_dir()
	project_dir = here.path_join("../..").simplify_path()
	cfg = JSON.parse_string(FileAccess.get_file_as_string(here.path_join("models.json")))["models"]
	var src_dir: String = args[0]
	var filter: String = args[1] if args.size() > 1 else ""
	var files := DirAccess.get_files_at(src_dir)
	for entry: Dictionary in cfg:
		if filter != "" and not filter in entry["name"]:
			continue
		var found := ""
		for f in files:
			# Chrome sometimes leaves finished downloads as "Unconfirmed NNNN.crdownload"; they're complete GLBs.
			if (f.ends_with(".glb") or f.ends_with(".crdownload")) and entry["match"] in f:
				found = src_dir.path_join(f)
		if found == "":
			print("MISSING source for ", entry["name"])
			continue
		process(found, entry)
	quit(0)


func process(path: String, e: Dictionary) -> void:
	var t0 := Time.get_ticks_msec()
	var doc := GLTFDocument.new()
	var st := GLTFState.new()
	if path.ends_with(".crdownload"):
		# GLTFDocument picks the parser by extension: read the bytes and parse them as GLB.
		if doc.append_from_buffer(FileAccess.get_file_as_bytes(path), path.get_base_dir(), st) != OK:
			print("FAIL load ", path)
			return
	elif doc.append_from_file(path, st) != OK:
		print("FAIL load ", path)
		return
	var scene: Node = doc.generate_scene(st)
	var mi: MeshInstance3D = _first_mesh(scene)
	var arrays: Array = mi.mesh.surface_get_arrays(0)
	var src_mat: Material = mi.get_active_material(0)
	scene.free()
	var src_tris: int = (arrays[Mesh.ARRAY_INDEX] as PackedInt32Array).size() / 3

	var out_rel := "assets/models/%s/%s" % [e["category"], e["name"]]
	var out_dir := project_dir.path_join(out_rel)
	DirAccess.make_dir_recursive_absolute(out_dir)
	var mat_res := "res://%s/material.tres" % out_rel
	_write_material(src_mat, e, out_dir, out_rel)

	# Simplify first (on the raw normalised mesh, which is closed so no open edges get chewed), then drop
	# unused vertices and move it into game space. Flat/boxy meshes can stall the simplifier's error limit,
	# so run it again on its own output (up to 4 rounds) until the budget is met.
	var budget := int(e.get("tris", 40000))
	for round_i in 4:
		var before := (arrays[Mesh.ARRAY_INDEX] as PackedInt32Array).size() / 3
		# Later rounds let more normals merge: hard-edged boxy props tolerate it and it unblocks the simplifier.
		arrays = _compact(arrays, _simplify(arrays, budget, [25.0, 45.0, 70.0, 89.0][round_i]))
		var after := (arrays[Mesh.ARRAY_INDEX] as PackedInt32Array).size() / 3
		if after <= budget * 1.3:
			break
	_transform(arrays, e)
	if e.has("widen"):
		_widen(arrays, e["widen"])
	if e.has("bend"):
		_bend(arrays, e["bend"], e)
	if e.has("crop"):
		arrays = _crop(arrays, e["crop"])
	var tris := (arrays[Mesh.ARRAY_INDEX] as PackedInt32Array).size() / 3
	if e.get("kind", "") == "laptop":
		_build_laptop(arrays, e, out_dir, out_rel, mat_res)
	elif e.get("kind", "") == "articulated":
		_build_articulated(arrays, e, out_dir, out_rel, mat_res)
	else:
		_build_prop(arrays, e, out_dir, out_rel, mat_res)
	print("%s: %d -> %d tris (%.1fs)" % [e["name"], src_tris, tris, (Time.get_ticks_msec() - t0) / 1000.0])


func _first_mesh(n: Node) -> MeshInstance3D:
	if n is MeshInstance3D:
		return n
	for c in n.get_children():
		var m := _first_mesh(c)
		if m:
			return m
	return null


# ------------------------------------------------------------------ geometry

func _simplify(arrays: Array, target: int, merge_angle: float = 25.0) -> PackedInt32Array:
	var src: PackedInt32Array = arrays[Mesh.ARRAY_INDEX]
	if src.size() / 3 <= target:
		return src
	var im := ImporterMesh.new()
	im.add_surface(Mesh.PRIMITIVE_TRIANGLES, arrays)
	im.generate_lods(merge_angle, 60.0, [])
	# Smallest LOD that still has at least ~85% of the budget; else the closest one.
	var best := src
	var best_n := src.size() / 3
	for l in im.get_surface_lod_count(0):
		var lod := im.get_surface_lod_indices(0, l)
		var n := lod.size() / 3
		if n >= target * 0.85 and n < best_n:
			best = lod
			best_n = n
	if best_n > target * 1.6:
		print("  note: simplifier stopped at %d tris (budget %d)" % [best_n, target])
	return best


func _compact(arrays: Array, idx: PackedInt32Array) -> Array:
	var verts: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var remap := PackedInt32Array()
	remap.resize(verts.size())
	remap.fill(-1)
	var order := PackedInt32Array()
	var new_idx := PackedInt32Array()
	new_idx.resize(idx.size())
	for i in idx.size():
		var v := idx[i]
		if remap[v] < 0:
			remap[v] = order.size()
			order.append(v)
		new_idx[i] = remap[v]
	var out := []
	out.resize(Mesh.ARRAY_MAX)
	for a in Mesh.ARRAY_MAX:
		var src = arrays[a]
		if src == null or a == Mesh.ARRAY_INDEX:
			continue
		out[a] = _gather(src, order, a == Mesh.ARRAY_TANGENT)
	out[Mesh.ARRAY_INDEX] = new_idx
	return out


func _gather(src, order: PackedInt32Array, tangent: bool):
	if tangent:
		var t: PackedFloat32Array = src
		var o := PackedFloat32Array()
		o.resize(order.size() * 4)
		for i in order.size():
			for k in 4:
				o[i * 4 + k] = t[order[i] * 4 + k]
		return o
	var o = src.duplicate()
	o.resize(order.size())
	for i in order.size():
		o[i] = src[order[i]]
	return o


func _transform(arrays: Array, e: Dictionary) -> void:
	## Rotate so the front faces +Z, scale to real-world metres, origin bottom-centre.
	var basis := Basis(Vector3.UP, deg_to_rad(float(e.get("rot_y", 0))))
	var v: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var n: PackedVector3Array = arrays[Mesh.ARRAY_NORMAL]
	var tg: PackedFloat32Array = arrays[Mesh.ARRAY_TANGENT] if arrays[Mesh.ARRAY_TANGENT] != null else PackedFloat32Array()
	for i in v.size():
		v[i] = basis * v[i]
		if n.size() > i:
			n[i] = basis * n[i]
		if tg.size() > i * 4:
			var t := basis * Vector3(tg[i * 4], tg[i * 4 + 1], tg[i * 4 + 2])
			tg[i * 4] = t.x
			tg[i * 4 + 1] = t.y
			tg[i * 4 + 2] = t.z
	var box := _aabb(v)
	var want: Dictionary = e["size"]
	var s := Vector3.ONE
	if want.has("max"):
		# Uniform scale so the longest side is this many metres.
		s = Vector3.ONE * (float(want["max"]) / maxf(box.size.x, maxf(box.size.y, box.size.z)))
	elif want.size() == 1:
		var axis: String = want.keys()[0]
		var cur: float = {"x": box.size.x, "y": box.size.y, "z": box.size.z}[axis]
		s = Vector3.ONE * (float(want[axis]) / cur)
	else:
		s = Vector3(float(want["x"]) / box.size.x, float(want["y"]) / box.size.y, float(want["z"]) / box.size.z)
	var origin := Vector3(box.get_center().x, box.position.y, box.get_center().z)
	var inv := Vector3(1.0 / s.x, 1.0 / s.y, 1.0 / s.z)
	for i in v.size():
		v[i] = (v[i] - origin) * s
		if n.size() > i:
			n[i] = (n[i] * inv).normalized()
		if tg.size() > i * 4:
			var t := (Vector3(tg[i * 4], tg[i * 4 + 1], tg[i * 4 + 2]) * s).normalized()
			tg[i * 4] = t.x
			tg[i * 4 + 1] = t.y
			tg[i * 4 + 2] = t.z
	arrays[Mesh.ARRAY_VERTEX] = v
	arrays[Mesh.ARRAY_NORMAL] = n
	if tg.size() > 0:
		arrays[Mesh.ARRAY_TANGENT] = tg


func _widen(arrays: Array, w: Dictionary) -> void:
	## Make a piece wider without fattening its sides: the middle (|x| < inner) is stretched, everything
	## outside it (side frames, rails, motors) just moves out by `by`. Used to fit 19-inch servers on conveyors.
	var inner := float(w.get("inner", 0.2))
	var by := float(w.get("by", 0.1))
	var v: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	for i in v.size():
		var x := v[i].x
		if absf(x) <= inner:
			v[i].x = x * (inner + by) / inner
		else:
			v[i].x = x + signf(x) * by
	arrays[Mesh.ARRAY_VERTEX] = v


func _bend(arrays: Array, b: Dictionary, e: Dictionary) -> void:
	## Wrap a straight piece (running along Z, already scaled so its length = the arc's centreline length)
	## round a vertical axis: it enters at -Z heading +Z and leaves heading +X (a left-hand turn as the kit sees it;
	## the right-hand curve is this mirrored).
	## Used to make the 90-degree conveyor curve from the straight conveyor so both match exactly.
	var deg := float(b.get("deg", 90.0))
	var r := float(b.get("radius", 1.0))
	var v: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var n: PackedVector3Array = arrays[Mesh.ARRAY_NORMAL]
	var tg: PackedFloat32Array = arrays[Mesh.ARRAY_TANGENT] if arrays[Mesh.ARRAY_TANGENT] != null else PackedFloat32Array()
	var box := _aabb(v)
	var length := box.size.z
	var z0 := box.position.z
	var turn := deg_to_rad(deg)
	for i in v.size():
		var p := v[i]
		var th := (p.z - z0) / length * turn
		var right := Vector3(cos(th), 0, -sin(th))
		var fwd := Vector3(sin(th), 0, cos(th))
		var rad := r - p.x
		v[i] = Vector3(r - rad * cos(th), p.y, rad * sin(th))
		if n.size() > i:
			var q := n[i]
			n[i] = (right * q.x + Vector3.UP * q.y + fwd * q.z).normalized()
		if tg.size() > i * 4:
			var t := right * tg[i * 4] + Vector3.UP * tg[i * 4 + 1] + fwd * tg[i * 4 + 2]
			tg[i * 4] = t.x
			tg[i * 4 + 1] = t.y
			tg[i * 4 + 2] = t.z
	# Re-centre on the footprint (bottom-centre origin) and record where belts join.
	var nb := _aabb(v)
	var shift := Vector3(-nb.get_center().x, -nb.position.y, -nb.get_center().z)
	for i in v.size():
		v[i] += shift
	arrays[Mesh.ARRAY_VERTEX] = v
	arrays[Mesh.ARRAY_NORMAL] = n
	if tg.size() > 0:
		arrays[Mesh.ARRAY_TANGENT] = tg
	var entry := Vector3(0, 0, 0) + shift
	var exit := Vector3(r - r * cos(turn), 0, r * sin(turn)) + shift
	e["_ports"] = "[[%s, Vector3(0, 0, 1)], [%s, Vector3(%.4f, 0, %.4f)]]" % [_v3(entry), _v3(exit), sin(turn), cos(turn)]


func _crop(arrays: Array, boxes: Array) -> Array:
	## Remove triangles whose centre falls in any [xmin, ymin, zmin, xmax, ymax, zmax] box (game-space metres,
	## after scaling), then re-centre the footprint. Used to cut stray AI geometry or panels we replace.
	var v: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var idx: PackedInt32Array = arrays[Mesh.ARRAY_INDEX]
	var mask := PackedByteArray()
	mask.resize(idx.size() / 3)
	for t in mask.size():
		var c := (v[idx[t * 3]] + v[idx[t * 3 + 1]] + v[idx[t * 3 + 2]]) / 3.0
		for b in boxes:
			if c.x >= b[0] and c.y >= b[1] and c.z >= b[2] and c.x <= b[3] and c.y <= b[4] and c.z <= b[5]:
				mask[t] = 1
				break
	var out := _subset(arrays, mask, 0)
	var ov: PackedVector3Array = out[Mesh.ARRAY_VERTEX]
	var box := _aabb(ov)
	var shift := Vector3(-box.get_center().x, -box.position.y, -box.get_center().z)
	for i in ov.size():
		ov[i] += shift
	out[Mesh.ARRAY_VERTEX] = ov
	print("  cropped; re-centred by ", shift)
	return out


func _aabb(v: PackedVector3Array) -> AABB:
	var lo := Vector3(INF, INF, INF)
	var hi := -lo
	for p in v:
		lo = lo.min(p)
		hi = hi.max(p)
	return AABB(lo, hi - lo)


func _subset(arrays: Array, tri_mask: PackedByteArray, want: int) -> Array:
	var idx: PackedInt32Array = arrays[Mesh.ARRAY_INDEX]
	var keep := PackedInt32Array()
	for t in tri_mask.size():
		if tri_mask[t] == want:
			keep.append(idx[t * 3])
			keep.append(idx[t * 3 + 1])
			keep.append(idx[t * 3 + 2])
	return _compact(arrays, keep)


func _clip(arrays: Array, dist: Callable) -> void:
	## Cuts every triangle that straddles dist(p) = 0 into pieces wholly on one side. New vertices interpolate
	## all per-vertex attributes. Mutates `arrays` in place.
	var v: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var idx: PackedInt32Array = arrays[Mesh.ARRAY_INDEX]
	var d := PackedFloat32Array()
	d.resize(v.size())
	for i in v.size():
		d[i] = dist.call(v[i])
	var out := PackedInt32Array()
	var splits: Array = []      # [a, b, t] per new vertex
	var cache := {}
	var split := func(a: int, b: int) -> int:
		var key := Vector2i(mini(a, b), maxi(a, b))
		if not cache.has(key):
			cache[key] = v.size() + splits.size()
			splits.append([a, b, d[a] / (d[a] - d[b])])
		return cache[key]
	for t in idx.size() / 3:
		var tri := [idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2]]
		var pos := 0
		for k in 3:
			if d[tri[k]] > 0.0:
				pos += 1
		if pos == 0 or pos == 3:
			out.append_array(PackedInt32Array(tri))
			continue
		# Rotate (keeping winding) so tri[0] is the one alone on its side.
		var alone_pos := pos == 1
		while (d[tri[0]] > 0.0) != alone_pos:
			tri = [tri[1], tri[2], tri[0]]
		var ab: int = split.call(tri[0], tri[1])
		var ac: int = split.call(tri[0], tri[2])
		out.append_array(PackedInt32Array([tri[0], ab, ac, ab, tri[1], tri[2], ab, tri[2], ac]))
	for at in Mesh.ARRAY_MAX:
		var arr = arrays[at]
		if arr == null or at == Mesh.ARRAY_INDEX:
			continue
		for sp in splits:
			var a: int = sp[0]
			var b: int = sp[1]
			var f: float = sp[2]
			if at == Mesh.ARRAY_TANGENT:
				var tn := Vector3(arr[a * 4], arr[a * 4 + 1], arr[a * 4 + 2]).lerp(Vector3(arr[b * 4], arr[b * 4 + 1], arr[b * 4 + 2]), f).normalized()
				arr.append_array(PackedFloat32Array([tn.x, tn.y, tn.z, arr[a * 4 + 3]]))
			elif at == Mesh.ARRAY_NORMAL:
				arr.append((arr[a] as Vector3).lerp(arr[b], f).normalized())
			else:
				arr.append(arr[a].lerp(arr[b], f))
		arrays[at] = arr
	arrays[Mesh.ARRAY_INDEX] = out

func _save_mesh(arrays: Array, path: String) -> void:
	var im := ImporterMesh.new()
	im.add_surface(Mesh.PRIMITIVE_TRIANGLES, arrays)
	im.generate_lods(25.0, 60.0, [])
	ResourceSaver.save(im.get_mesh(), path, ResourceSaver.FLAG_COMPRESS)


# ------------------------------------------------------------------ laptops

func _build_laptop(arrays: Array, e: Dictionary, out_dir: String, out_rel: String, mat_res: String) -> void:
	var v: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var idx: PackedInt32Array = arrays[Mesh.ARRAY_INDEX]
	var box := _aabb(v)
	# Top of the base = the palm rest: high percentile of heights across the front half.
	var front_y := PackedFloat32Array()
	for p in v:
		if p.z > box.get_center().z + box.size.z * 0.05:
			front_y.append(p.y)
	front_y.sort()
	var base_top := front_y[int(front_y.size() * 0.98)]
	# Fit the open lid as a line in the YZ plane (points clearly above the base).
	var lid_pts: Array[Vector2] = []   # (z, y)
	var cut := base_top + (box.end.y - base_top) * 0.3
	for p in v:
		if p.y > cut:
			lid_pts.append(Vector2(p.z, p.y))
	var c := Vector2.ZERO
	for q in lid_pts:
		c += q
	c /= lid_pts.size()
	var szz := 0.0
	var syy := 0.0
	var szy := 0.0
	for q in lid_pts:
		var d := q - c
		szz += d.x * d.x
		syy += d.y * d.y
		szy += d.x * d.y
	var ang := 0.5 * atan2(2.0 * szy, szz - syy)
	var dir := Vector2(cos(ang), sin(ang))
	if dir.y < 0:
		dir = -dir
	# Hinge line: where the lid line meets the top of the base.
	var hinge := c + dir * ((base_top - c.y) / dir.y)       # (z, y)
	var phi := atan2(dir.y, dir.x)                          # opening angle from +Z towards +Y
	# Split: the lid is everything past a cutting plane across the bottom of the lid (perpendicular to it, just
	# above the hinge). Triangles that straddle it are cut exactly, so the hinge edge is a clean straight line.
	var cut_at := box.size.x * 0.012
	# How far from the lid's centre line still counts as lid: its measured half-thickness plus a margin (keeps
	# vents/spines on the back of the base from being swept into the lid).
	var perps := PackedFloat32Array()
	for q in lid_pts:
		var r := q - c
		perps.append(absf(r.x * dir.y - r.y * dir.x))
	perps.sort()
	var lid_thick_tol := perps[int(perps.size() * 0.97)] * 1.3 + box.size.x * 0.004
	var dist := func(p: Vector3) -> float:
		var rel := Vector2(p.z, p.y) - hinge
		if absf(rel.x * dir.y - rel.y * dir.x) > lid_thick_tol:
			return -1.0   # far from the lid line: always base
		return rel.dot(dir) - cut_at
	_clip(arrays, dist)
	v = arrays[Mesh.ARRAY_VERTEX]
	idx = arrays[Mesh.ARRAY_INDEX]
	var mask := PackedByteArray()
	mask.resize(idx.size() / 3)
	for t in mask.size():
		var p := (v[idx[t * 3]] + v[idx[t * 3 + 1]] + v[idx[t * 3 + 2]]) / 3.0
		mask[t] = 1 if dist.call(p) > 0.0 else 0
	var base := _subset(arrays, mask, 0)
	var lid := _subset(arrays, mask, 1)
	var src_base_end_z := _aabb(base[Mesh.ARRAY_VERTEX]).end.z
	# Close the lid: rotate +phi about X through the hinge; express it relative to the hinge.
	var rot := Basis(Vector3.RIGHT, phi)
	var h3 := Vector3(0, hinge.y, hinge.x)
	var lv: PackedVector3Array = lid[Mesh.ARRAY_VERTEX]
	var ln: PackedVector3Array = lid[Mesh.ARRAY_NORMAL]
	var lt: PackedFloat32Array = lid[Mesh.ARRAY_TANGENT] if lid[Mesh.ARRAY_TANGENT] != null else PackedFloat32Array()
	for i in lv.size():
		lv[i] = rot * (lv[i] - h3)
		ln[i] = rot * ln[i]
		if lt.size() > i * 4:
			var tv := rot * Vector3(lt[i * 4], lt[i * 4 + 1], lt[i * 4 + 2])
			lt[i * 4] = tv.x
			lt[i * 4 + 1] = tv.y
			lt[i * 4 + 2] = tv.z
	# Rest the closed lid just on top of the base, and stretch it to the base's depth (the AI models' lids
	# come out shorter than their bases, which leaves a strip of palm rest showing when closed).
	var lbox := _aabb(lv)
	var lift := 0.0006 - lbox.position.y
	var k := clampf((src_base_end_z - h3.z - 0.002) / lbox.end.z, 1.0, 1.25)
	for i in lv.size():
		lv[i].y += lift
		lv[i].z *= k
		ln[i] = Vector3(ln[i].x, ln[i].y, ln[i].z / k).normalized()
		if lt.size() > i * 4:
			var tz := Vector3(lt[i * 4], lt[i * 4 + 1], lt[i * 4 + 2] * k).normalized()
			lt[i * 4] = tz.x
			lt[i * 4 + 1] = tz.y
			lt[i * 4 + 2] = tz.z
	lid[Mesh.ARRAY_VERTEX] = lv
	lid[Mesh.ARRAY_NORMAL] = ln
	if lt.size() > 0:
		lid[Mesh.ARRAY_TANGENT] = lt
	lbox = _aabb(lv)
	# Re-centre everything on the base footprint.
	var bv: PackedVector3Array = base[Mesh.ARRAY_VERTEX]
	var bbox := _aabb(bv)
	var shift := Vector3(-bbox.get_center().x, -bbox.position.y, -bbox.get_center().z)
	for i in bv.size():
		bv[i] += shift
	base[Mesh.ARRAY_VERTEX] = bv
	bbox = _aabb(bv)
	var pivot := h3 + shift
	pivot.x = 0.0
	_save_mesh(base, out_dir.path_join("base.res"))
	_save_mesh(lid, out_dir.path_join("lid.res"))
	# Closed footprint (what collision and stacking use).
	var closed := AABB(bbox.position, bbox.size).merge(AABB(lbox.position + pivot, lbox.size))
	var footprint := Vector3(maxf(absf(closed.position.x), absf(closed.end.x)) * 2.0, closed.end.y, closed.size.z)
	# Screen quad on the underside of the closed lid: [width frac, z start frac, z end frac] of the lid.
	var sc: Array = e.get("screen", [0.92, 0.08, 0.95])
	var sw := lbox.size.x * float(sc[0])
	var z0 := lbox.position.z + lbox.size.z * float(sc[1])
	var z1 := lbox.position.z + lbox.size.z * float(sc[2])
	var screen_pos := Vector3(lbox.get_center().x, lbox.position.y - 0.0004, (z0 + z1) * 0.5)
	var sticker := Vector3(lbox.size.x * 0.36, lbox.end.y + 0.0004, lbox.position.z + lbox.size.z * 0.85)
	var scn := _scene_header(e, 5, footprint)
	scn += "metadata/lid_open_deg = %.2f\nmetadata/sticker_pos = %s\n\n" % [-rad_to_deg(phi), _v3(sticker)]
	scn = scn.replace("[ext_resources]", _ext("ArrayMesh", "res://%s/base.res" % out_rel, "1") + _ext("ArrayMesh", "res://%s/lid.res" % out_rel, "2") + _ext("Material", mat_res, "3")) \
		.replace("[sub_resources]", _quad("screen_quad", Vector2(sw, z1 - z0)) + _quad("cap_quad", Vector2(bbox.size.x * 0.94, bbox.size.z * 0.92)) + _cap_mat())
	scn += _node("Base", "MeshInstance3D", ".", "", 'mesh = ExtResource("1")\nsurface_material_override/0 = ExtResource("3")\n')
	# Meshy shells are open underneath: close the bottom so a carried laptop doesn't show its insides.
	scn += _node("BottomCap", "MeshInstance3D", ".", _xf_rot_x90(Vector3(0, 0.0012, bbox.get_center().z)), 'mesh = SubResource("cap_quad")\nsurface_material_override/0 = SubResource("cap_mat")\n')
	scn += _node("Lid", "Node3D", ".", "transform = Transform3D(1, 0, 0, 0, 1, 0, 0, 0, 1, %s)\n" % _v3raw(pivot), "")
	scn += _node("LidMesh", "MeshInstance3D", "Lid", "", 'mesh = ExtResource("2")\nsurface_material_override/0 = ExtResource("3")\n')
	scn += _node("Screen", "MeshInstance3D", "Lid", _xf_rot_x90(screen_pos), 'mesh = SubResource("screen_quad")\n')
	_write(out_dir.path_join(e["name"] + ".tscn"), scn)
	print("  lid stretched x%.2f, opens %.0f deg, lid tolerance %.1f mm" % [k, rad_to_deg(phi), lid_thick_tol * 1000.0])


# ------------------------------------------------------------------ props (desktops, servers)

func _build_prop(arrays: Array, e: Dictionary, out_dir: String, out_rel: String, mat_res: String) -> void:
	_save_mesh(arrays, out_dir.path_join("mesh.res"))
	var box := _aabb(arrays[Mesh.ARRAY_VERTEX])
	var scn := _scene_header(e, 2, box.size)
	scn += "metadata/surfaces = %s\n" % _surfaces(arrays)
	if e.has("_ports"):
		scn += "metadata/ports = %s\n" % e["_ports"]
	scn += "\n"
	scn = scn.replace("[ext_resources]", _ext("ArrayMesh", "res://%s/mesh.res" % out_rel, "1") + _ext("Material", mat_res, "2")).replace("[sub_resources]", "")
	scn += _node("Model", "MeshInstance3D", ".", "", 'mesh = ExtResource("1")\nsurface_material_override/0 = ExtResource("2")\n')
	_write(out_dir.path_join(e["name"] + ".tscn"), scn)


func _surfaces(arrays: Array) -> String:
	## Heights of the big upward-facing surfaces (bench tops, shelf decks, pallet deck…) so wrapper scenes can put
	## their snap points exactly on the model: [[y, share_of_biggest, x_min, x_max, z_min, z_max], …] ascending by y.
	var v: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var idx: PackedInt32Array = arrays[Mesh.ARRAY_INDEX]
	var bins := {}
	for t in idx.size() / 3:
		var a := v[idx[t * 3]]
		var b := v[idx[t * 3 + 1]]
		var c := v[idx[t * 3 + 2]]
		var n := (b - a).cross(c - a)
		var area := n.length() * 0.5
		if area <= 0.0 or n.normalized().y < 0.92:
			continue
		var key := int(round((a.y + b.y + c.y) / 3.0 * 100.0))   # 1 cm bins
		if not bins.has(key):
			bins[key] = [0.0, INF, -INF, INF, -INF]
		var bi: Array = bins[key]
		bi[0] += area
		for q in [a, b, c]:
			bi[1] = minf(bi[1], q.x)
			bi[2] = maxf(bi[2], q.x)
			bi[3] = minf(bi[3], q.z)
			bi[4] = maxf(bi[4], q.z)
	# Merge neighbouring 1 cm bins; keep levels with at least 12% of the biggest level's area.
	var keys := bins.keys()
	keys.sort()
	var levels: Array = []
	for k in keys:
		var bi: Array = bins[k]
		if not levels.is_empty() and k - int(levels.back()[6]) <= 2:
			var L: Array = levels.back()
			if bi[0] > L[1]:
				L[0] = k / 100.0
			L[1] += bi[0]
			L[2] = minf(L[2], bi[1])
			L[3] = maxf(L[3], bi[2])
			L[4] = minf(L[4], bi[3])
			L[5] = maxf(L[5], bi[4])
			L[6] = k
		else:
			levels.append([k / 100.0, bi[0], bi[1], bi[2], bi[3], bi[4], k])
	var biggest := 0.0
	for L in levels:
		biggest = maxf(biggest, L[1])
	var out: PackedStringArray = []
	for L in levels:
		if L[1] >= biggest * 0.12 and L[0] > 0.02:
			out.append("[%.3f, %.2f, %.3f, %.3f, %.3f, %.3f]" % [L[0], L[1] / biggest, L[2], L[3], L[4], L[5]])
	return "[" + ", ".join(out) + "]"


# ------------------------------------------------------------------ articulated (robot arm)

func _build_articulated(arrays: Array, e: Dictionary, out_dir: String, out_rel: String, mat_res: String) -> void:
	## Split one mesh into jointed parts by side-view (x, y) region rules, each saved relative to its pivot,
	## and write a scene with the joint hierarchy (Turret yaws; Upper/Fore/Grip pitch about local Z).
	## parts: [{name, parent, pivot:[x,y,z], when:[clauses…]}]; clauses: y_lt, y_ge, x_lt, x_gt, seg:[ax,ay,bx,by,r].
	var parts: Array = e["parts"]
	var v: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var idx: PackedInt32Array = arrays[Mesh.ARRAY_INDEX]
	var tri_part := PackedByteArray()
	tri_part.resize(idx.size() / 3)
	for t in tri_part.size():
		var c := (v[idx[t * 3]] + v[idx[t * 3 + 1]] + v[idx[t * 3 + 2]]) / 3.0
		var pick := parts.size() - 1
		for pi in parts.size():
			if _clauses_hold(parts[pi].get("when", []), c):
				pick = pi
				break
		tri_part[t] = pick
	var exts := _ext("Material", mat_res, "m")
	var nodes := ""
	for pi in parts.size():
		var part: Dictionary = parts[pi]
		var sub := _subset(arrays, tri_part, pi)
		var pv: PackedVector3Array = sub[Mesh.ARRAY_VERTEX]
		var piv := Vector3(part.pivot[0], part.pivot[1], part.pivot[2])
		for i in pv.size():
			pv[i] -= piv
		sub[Mesh.ARRAY_VERTEX] = pv
		_save_mesh(sub, out_dir.path_join("%s.res" % part.name))
		exts += _ext("ArrayMesh", "res://%s/%s.res" % [out_rel, part.name], "p%d" % pi)
		print("  part %s: %d tris" % [part.name, (sub[Mesh.ARRAY_INDEX] as PackedInt32Array).size() / 3])
	# Parents must come before children in a .tscn: emit the joints shallowest first.
	var order: Array = range(parts.size())
	order.sort_custom(func(a, b): return _node_path(parts, parts[a].name).count("/") < _node_path(parts, parts[b].name).count("/"))
	for pi in order:
		var part: Dictionary = parts[pi]
		var piv := Vector3(part.pivot[0], part.pivot[1], part.pivot[2])
		var parent_path := "."
		var parent_piv := Vector3.ZERO
		if part.get("parent", "") != "":
			parent_path = _node_path(parts, part.parent)
			var pp: Dictionary = parts.filter(func(x): return x.name == part.parent)[0]
			parent_piv = Vector3(pp.pivot[0], pp.pivot[1], pp.pivot[2])
		var rel := piv - parent_piv
		nodes += _node(part.name.capitalize().replace(" ", ""), "Node3D", parent_path, "transform = Transform3D(1, 0, 0, 0, 1, 0, 0, 0, 1, %s)\n" % _v3raw(rel), "")
		var me: String = parent_path + "/" + part.name.capitalize().replace(" ", "") if parent_path != "." else part.name.capitalize().replace(" ", "")
		nodes += _node("Mesh", "MeshInstance3D", me, "", 'mesh = ExtResource("p%d")\nsurface_material_override/0 = ExtResource("m")\n' % pi)
	for mk in e.get("markers", []):
		var host_piv := Vector3.ZERO
		var hp: Dictionary = parts.filter(func(x): return x.name == mk.parent)[0]
		host_piv = Vector3(hp.pivot[0], hp.pivot[1], hp.pivot[2])
		var mp := Vector3(mk.pos[0], mk.pos[1], mk.pos[2]) - host_piv
		nodes += _node(mk.name, "Marker3D", _node_path(parts, mk.parent), "transform = Transform3D(1, 0, 0, 0, 1, 0, 0, 0, 1, %s)\n" % _v3raw(mp), "")
	var box := _aabb(arrays[Mesh.ARRAY_VERTEX])
	var scn := _scene_header(e, parts.size() + 2, box.size)
	scn += "metadata/joints = %s\n\n" % JSON.stringify(e.get("joints", {}))
	scn = scn.replace("[ext_resources]", exts).replace("[sub_resources]", "")
	scn += nodes
	_write(out_dir.path_join(e["name"] + ".tscn"), scn)


func _node_path(parts: Array, name: String) -> String:
	var chain: Array = []
	var cur := name
	while cur != "":
		chain.push_front(cur.capitalize().replace(" ", ""))
		var p: Array = parts.filter(func(x): return x.name == cur)
		cur = p[0].get("parent", "") if not p.is_empty() else ""
	return "/".join(chain)


func _clauses_hold(clauses: Array, c: Vector3) -> bool:
	for cl: Dictionary in clauses:
		if cl.has("y_lt") and not c.y < float(cl.y_lt):
			return false
		if cl.has("y_ge") and not c.y >= float(cl.y_ge):
			return false
		if cl.has("x_lt") and not c.x < float(cl.x_lt):
			return false
		if cl.has("x_gt") and not c.x > float(cl.x_gt):
			return false
		if cl.has("seg"):
			var sg: Array = cl.seg
			var a := Vector2(sg[0], sg[1])
			var b := Vector2(sg[2], sg[3])
			var p := Vector2(c.x, c.y)
			var t := clampf((p - a).dot(b - a) / maxf((b - a).length_squared(), 1e-6), 0.0, 1.0)
			if p.distance_to(a + (b - a) * t) > float(sg[4]):
				return false
	return true


# ------------------------------------------------------------------ materials / textures

func _write_material(src: Material, e: Dictionary, out_dir: String, out_rel: String) -> void:
	var sm := src as StandardMaterial3D
	var exts := ""
	var props := ""
	var n := 0
	if sm and sm.albedo_texture:
		n += 1
		_save_tex(sm.albedo_texture, out_dir.path_join("albedo.jpg"), int(e.get("tex", TEX_SIZE)))
		exts += _ext("Texture2D", "res://%s/albedo.jpg" % out_rel, str(n))
		props += 'albedo_texture = ExtResource("%d")\n' % n
		if sm.metallic_texture or sm.roughness_texture:
			n += 1
			_save_tex(sm.roughness_texture if sm.roughness_texture else sm.metallic_texture, out_dir.path_join("orm.jpg"), int(e.get("tex", TEX_SIZE)))
			exts += _ext("Texture2D", "res://%s/orm.jpg" % out_rel, str(n))
			# glTF packs roughness in G and metallic in B.
			props += 'metallic = %.3f\nmetallic_texture = ExtResource("%d")\nmetallic_texture_channel = 2\nroughness = %.3f\nroughness_texture = ExtResource("%d")\nroughness_texture_channel = 1\n' % [sm.metallic, n, sm.roughness, n]
		if sm.normal_texture:
			n += 1
			_save_tex(sm.normal_texture, out_dir.path_join("normal.jpg"), int(e.get("tex", TEX_SIZE)))
			exts += _ext("Texture2D", "res://%s/normal.jpg" % out_rel, str(n))
			props += 'normal_enabled = true\nnormal_texture = ExtResource("%d")\n' % n
	else:
		# Untextured source: flat PBR colour from models.json.
		var col := Color(str(e.get("colour", "#2a2c30")))
		props += "albedo_color = Color(%.4f, %.4f, %.4f, 1)\nmetallic = %.2f\nroughness = %.2f\n" % [col.r, col.g, col.b, float(e.get("metallic", 0.2)), float(e.get("roughness", 0.5))]
	var txt := "[gd_resource type=\"StandardMaterial3D\" load_steps=%d format=3]\n\n%s\n[resource]\nresource_name = \"%s\"\n%s" % [n + 1, exts, e["name"], props]
	_write(out_dir.path_join("material.tres"), txt)


func _save_tex(tex: Texture2D, path: String, size: int = TEX_SIZE) -> void:
	var img := tex.get_image()
	if img.is_compressed():
		img.decompress()
	if maxi(img.get_width(), img.get_height()) > size:
		img.resize(size, size, Image.INTERPOLATE_LANCZOS)
	img.convert(Image.FORMAT_RGB8)
	img.save_jpg(path, 0.92)


# ------------------------------------------------------------------ scene text helpers

func _scene_header(e: Dictionary, steps: int, footprint: Vector3) -> String:
	var root_name: String = (e["name"] as String).to_pascal_case()
	return "[gd_scene load_steps=%d format=3]\n\n[ext_resources]\n[sub_resources]\n[node name=\"%s\" type=\"Node3D\"]\nmetadata/footprint = %s\nmetadata/source_tier = \"%s\"\n" % [steps, root_name, _v3(footprint), e.get("tier", "")]


func _ext(type: String, path: String, id: String) -> String:
	return "[ext_resource type=\"%s\" path=\"%s\" id=\"%s\"]\n" % [type, path, id]


func _quad(id: String, size: Vector2) -> String:
	return "[sub_resource type=\"QuadMesh\" id=\"%s\"]\nsize = Vector2(%.5f, %.5f)\n\n" % [id, size.x, size.y]


func _cap_mat() -> String:
	return "[sub_resource type=\"StandardMaterial3D\" id=\"cap_mat\"]\nalbedo_color = Color(0.07, 0.072, 0.078, 1)\nroughness = 0.8\n\n"


func _node(name: String, type: String, parent: String, xf: String, body: String) -> String:
	return "\n[node name=\"%s\" type=\"%s\" parent=\"%s\"]\n%s%s" % [name, type, parent, xf, body]


func _xf_rot_x90(pos: Vector3) -> String:
	# +90 deg about X (scene files store basis rows): the quad's +Z normal points down (-Y).
	return "transform = Transform3D(1, 0, 0, 0, 0, -1, 0, 1, 0, %s)\n" % _v3raw(pos)


func _v3(v: Vector3) -> String:
	return "Vector3(%s)" % _v3raw(v)


func _v3raw(v: Vector3) -> String:
	return "%.5f, %.5f, %.5f" % [v.x, v.y, v.z]


func _write(path: String, txt: String) -> void:
	var f := FileAccess.open(path, FileAccess.WRITE)
	f.store_string(txt)
	f.close()
