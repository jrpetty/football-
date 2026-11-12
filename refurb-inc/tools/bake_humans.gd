extends SceneTree
## Bakes the human characters (players, AI staff, drivers) from sculpted signed-distance shapes:
## one seamless skinned body per build (male/female) with real anatomy blended smoothly, a modelled head
## (skull, jaw, cheekbones, brow, eye sockets, nose, lips, ears), hands with fingers and a thumb, shoes
## with soles, and several hairstyles. Surfaces are extracted with surface nets (smooth quads, normals from
## the SDF gradient); body vertices get skin weights from the bone segments. Output: assets/models/people/.
##   godot --headless --path . -s tools/bake_humans.gd

const OUT := "res://assets/models/people/"

var _prims: Array = []      # current SDF program (see _eval)


func _init() -> void:
	DirAccess.make_dir_recursive_absolute(ProjectSettings.globalize_path(OUT))
	var skel := {}
	for body in ["m", "f"]:
		var j := joints(body)
		skel[body] = skeleton_def(j)
		bake_body(body, j)
		bake_head(body)
	bake_hand()
	bake_shoe()
	for style in ["short", "side", "long", "bun", "buzz", "curly"]:
		bake_hair(style)
	var f := FileAccess.open(OUT + "skeleton.json", FileAccess.WRITE)
	f.store_string(JSON.stringify(skel, " "))
	print("BAKED humans → ", OUT)
	quit()


# ================================================================= skeleton

func joints(body: String) -> Dictionary:
	## Joint positions (metres, feet on y = 0, facing +Z), arms in an A-pose 35° from vertical.
	var f := body == "f"
	var s := 0.945 if f else 1.0
	var sh := 0.172 if f else 0.19          # shoulder joint x
	var arm := deg_to_rad(35.0)
	var dir := Vector3(sin(arm), -cos(arm), 0.0)
	var j := {}
	j.hips = Vector3(0, 0.96, 0) * s
	j.spine = Vector3(0, 1.06, 0.0) * s
	j.chest = Vector3(0, 1.24, 0.0) * s
	j.neck = Vector3(0, 1.45, -0.012) * s
	j.head = Vector3(0, 1.545, -0.002) * s
	for side in ["L", "R"]:
		var sx := 1.0 if side == "L" else -1.0
		j["clav" + side] = Vector3(0.025 * sx, 1.42, -0.012) * s
		j["shoulder" + side] = Vector3(sh * sx, 1.415 * s, -0.012 * s)
		var sd := Vector3(dir.x * sx, dir.y, 0)
		j["elbow" + side] = j["shoulder" + side] + sd * 0.29 * s
		j["wrist" + side] = j["elbow" + side] + sd * 0.255 * s
		j["tip" + side] = j["wrist" + side] + sd * 0.185 * s
		j["hip" + side] = Vector3(0.09 * sx * (1.06 if f else 1.0), 0.925, 0.0) * s
		j["knee" + side] = Vector3(0.098 * sx, 0.505, 0.012) * s
		j["ankle" + side] = Vector3(0.102 * sx, 0.085, -0.012) * s
	return j


func skeleton_def(j: Dictionary) -> Array:
	## [name, parent, joint key] — rests are pure translations (identity rotation), so local axes = model axes.
	var bones := [["Hips", "", "hips"], ["Spine", "Hips", "spine"], ["Chest", "Spine", "chest"], ["Neck", "Chest", "neck"], ["Head", "Neck", "head"]]
	for side in ["L", "R"]:
		bones.append(["Clavicle" + side, "Chest", "clav" + side])
		bones.append(["UpperArm" + side, "Clavicle" + side, "shoulder" + side])
		bones.append(["ForeArm" + side, "UpperArm" + side, "elbow" + side])
		bones.append(["Hand" + side, "ForeArm" + side, "wrist" + side])
		bones.append(["Thigh" + side, "Hips", "hip" + side])
		bones.append(["Shin" + side, "Thigh" + side, "knee" + side])
		bones.append(["Foot" + side, "Shin" + side, "ankle" + side])
	var out: Array = []
	for b in bones:
		var p: Vector3 = j[b[2]]
		out.append({"name": b[0], "parent": b[1], "pos": [p.x, p.y, p.z]})
	return out


func weight_segments(j: Dictionary) -> Array:
	## Bone index (order of skeleton_def) and the segment it owns, for skin weights.
	var segs := [[0, j.hips + Vector3(0, -0.06, 0), j.spine], [1, j.spine, j.chest], [2, j.chest, j.neck + Vector3(0, -0.04, 0)],
		[3, j.neck, j.head], [4, j.head, j.head + Vector3(0, 0.2, 0)]]
	var i := 5
	for side in ["L", "R"]:
		segs.append([i, j["clav" + side], j["shoulder" + side]])
		segs.append([i + 1, j["shoulder" + side], j["elbow" + side]])
		segs.append([i + 2, j["elbow" + side], j["wrist" + side]])
		segs.append([i + 3, j["wrist" + side], j["tip" + side]])
		segs.append([i + 4, j["hip" + side], j["knee" + side]])
		segs.append([i + 5, j["knee" + side], j["ankle" + side]])
		segs.append([i + 6, j["ankle" + side], j["ankle" + side] + Vector3(0, -0.06, 0.12)])
		i += 7
	return segs


# ================================================================= SDF primitives

static func sd_round_cone(p: Vector3, a: Vector3, b: Vector3, r1: float, r2: float) -> float:
	var ba := b - a
	var l2 := ba.dot(ba)
	var rr := r1 - r2
	var a2 := l2 - rr * rr
	var il2 := 1.0 / l2
	var pa := p - a
	var y := pa.dot(ba)
	var z := y - l2
	var xv := pa * l2 - ba * y
	var x2 := xv.dot(xv)
	var y2 := y * y * l2
	var z2 := z * z * l2
	var k := signf(rr) * rr * rr * x2
	if signf(z) * a2 * z2 > k:
		return sqrt(x2 + z2) * il2 - r2
	if signf(y) * a2 * y2 < k:
		return sqrt(x2 + y2) * il2 - r1
	return (sqrt(x2 * a2 * il2) + y * rr) * il2 - r1


static func sd_ellipsoid(p: Vector3, c: Vector3, r: Vector3) -> float:
	var q := (p - c) / r
	var k0 := q.length()
	var k1 := (q / r).length()
	return k0 * (k0 - 1.0) / maxf(k1, 1e-6)


static func sd_round_box(p: Vector3, c: Vector3, half: Vector3, rad: float) -> float:
	var q := (p - c).abs() - half + Vector3.ONE * rad
	return Vector3(maxf(q.x, 0), maxf(q.y, 0), maxf(q.z, 0)).length() + minf(maxf(q.x, maxf(q.y, q.z)), 0.0) - rad


static func smin(a: float, b: float, k: float) -> float:
	if k <= 0.0:
		return minf(a, b)
	var h := maxf(k - absf(a - b), 0.0) / k
	return minf(a, b) - h * h * k * 0.25


static func smax(a: float, b: float, k: float) -> float:
	return -smin(-a, -b, k)


# A tiny SDF "program": a list of ops evaluated in order into named groups.
#   ["prim", group, blend_k, kind, args...]  — smooth-union a primitive into group
#   ["sub", group, k, kind, args...]         — smooth-subtract a primitive from group
#   ["join", into, from, k]                  — smooth-union group `from` into `into`
# Final distance = min over the groups listed in `_final`.
var _final: Array = []


const OP_PRIM := 0
const OP_SUB := 1
const OP_JOIN := 2
const K_CONE := 0
const K_CAP := 1
const K_ELL := 2
const K_BOX := 3
const K_SPH := 4

var _ops: Array = []
var _g := PackedFloat32Array()
var _final_idx := PackedInt32Array()
var _hair_mode := false
var _hair_curl := false
var _hair_thick := 0.01
var _hairline: Callable


func _compile() -> void:
	## Turn the readable program (_prims/_final) into int opcodes for speed.
	var names := {}
	var gi := func(n: String) -> int:
		if not names.has(n):
			names[n] = names.size()
		return names[n]
	_ops = []
	for op: Array in _prims:
		var o := op.duplicate()
		match str(op[0]):
			"prim":
				o[0] = OP_PRIM
			"sub":
				o[0] = OP_SUB
			"join":
				o[0] = OP_JOIN
				o[2] = gi.call(str(op[2]))
		o[1] = gi.call(str(op[1]))
		if int(o[0]) != OP_JOIN:
			o[3] = {"cone": K_CONE, "cap": K_CAP, "ell": K_ELL, "box": K_BOX, "sph": K_SPH}[str(op[3])]
		_ops.append(o)
	_g.resize(names.size())
	_final_idx = PackedInt32Array()
	for n in _final:
		_final_idx.append(gi.call(str(n)))
	_g.resize(names.size())


func _prim_d(p: Vector3, op: Array) -> float:
	match int(op[3]):
		K_CONE:
			return sd_round_cone(p, op[4], op[5], op[6], op[7])
		K_CAP:
			return sd_round_cone(p, op[4], op[5], op[6], op[6])
		K_ELL:
			return sd_ellipsoid(p, op[4], op[5])
		K_BOX:
			return sd_round_box(p, op[4], op[5], op[6])
		K_SPH:
			return (p - (op[4] as Vector3)).length() - float(op[5])
	return 1e9


func _eval(p: Vector3) -> float:
	_g.fill(1e9)
	for op: Array in _ops:
		var code: int = op[0]
		var gi: int = op[1]
		if code == OP_PRIM:
			_g[gi] = smin(_g[gi], _prim_d(p, op), op[2])
		elif code == OP_SUB:
			_g[gi] = smax(_g[gi], -_prim_d(p, op), op[2])
		else:
			_g[gi] = smin(_g[gi], _g[op[2]], op[3])
	var out := 1e9
	for k in _final_idx:
		out = minf(out, _g[k])
	if _hair_mode:
		var line := float(_hairline.call(p))
		out = smax(out, line - p.y, 0.006)
		out += maxf(0.0, 1.0 - (p.y - line) / 0.04) * _hair_thick * 0.7
		if _hair_curl:
			out += 0.0035 * sin(p.x * 260.0) * sin(p.y * 260.0) * sin(p.z * 260.0)
	return out


func _grad(p: Vector3) -> Vector3:
	var e := 0.0008
	return Vector3(_eval(p + Vector3(e, 0, 0)) - _eval(p - Vector3(e, 0, 0)),
		_eval(p + Vector3(0, e, 0)) - _eval(p - Vector3(0, e, 0)),
		_eval(p + Vector3(0, 0, e)) - _eval(p - Vector3(0, 0, e))).normalized()


# ================================================================= surface nets

func _polygonise(lo: Vector3, hi: Vector3, h: float) -> Dictionary:
	## Naive surface nets over [lo, hi] at spacing h. Returns {v: PackedVector3Array, n: normals, i: indices}.
	var nx := int(ceil((hi.x - lo.x) / h)) + 1
	var ny := int(ceil((hi.y - lo.y) / h)) + 1
	var nz := int(ceil((hi.z - lo.z) / h)) + 1
	var s := PackedFloat32Array()
	s.resize(nx * ny * nz)
	for k in nz:
		for j in ny:
			for i in nx:
				s[i + nx * (j + ny * k)] = _eval(lo + Vector3(i, j, k) * h)
	var cell := PackedInt32Array()
	cell.resize(nx * ny * nz)
	cell.fill(-1)
	var verts := PackedVector3Array()
	var norms := PackedVector3Array()
	var corners := [Vector3i(0, 0, 0), Vector3i(1, 0, 0), Vector3i(0, 1, 0), Vector3i(1, 1, 0), Vector3i(0, 0, 1), Vector3i(1, 0, 1), Vector3i(0, 1, 1), Vector3i(1, 1, 1)]
	var edges := [[0, 1], [2, 3], [4, 5], [6, 7], [0, 2], [1, 3], [4, 6], [5, 7], [0, 4], [1, 5], [2, 6], [3, 7]]
	for k in nz - 1:
		for j in ny - 1:
			for i in nx - 1:
				var vals := PackedFloat32Array()
				vals.resize(8)
				var neg := 0
				for c in 8:
					var cv: Vector3i = corners[c]
					vals[c] = s[(i + cv.x) + nx * ((j + cv.y) + ny * (k + cv.z))]
					if vals[c] < 0.0:
						neg += 1
				if neg == 0 or neg == 8:
					continue
				var acc := Vector3.ZERO
				var cnt := 0
				for e in edges:
					var a: float = vals[e[0]]
					var b: float = vals[e[1]]
					if (a < 0.0) != (b < 0.0):
						var t := a / (a - b)
						acc += Vector3(corners[e[0]]).lerp(Vector3(corners[e[1]]), t)
						cnt += 1
				var p := lo + (Vector3(i, j, k) + acc / cnt) * h
				# One projection step onto the true surface sharpens creases (lips, ears, knuckles).
				var gn := _grad(p)
				p -= gn * _eval(p)
				cell[i + nx * (j + ny * k)] = verts.size()
				verts.append(p)
				norms.append(_grad(p))
	var idx := PackedInt32Array()
	for k in range(1, nz - 1):
		for j in range(1, ny - 1):
			for i in range(1, nx - 1):
				var a0 := s[i + nx * (j + ny * k)]
				for axis in 3:
					var o := Vector3i(1, 0, 0) if axis == 0 else (Vector3i(0, 1, 0) if axis == 1 else Vector3i(0, 0, 1))
					var b0 := s[(i + o.x) + nx * ((j + o.y) + ny * (k + o.z))]
					if (a0 < 0.0) == (b0 < 0.0):
						continue
					var q: Array
					match axis:
						0:
							q = [Vector3i(i, j, k), Vector3i(i, j - 1, k), Vector3i(i, j - 1, k - 1), Vector3i(i, j, k - 1)]
						1:
							q = [Vector3i(i, j, k), Vector3i(i, j, k - 1), Vector3i(i - 1, j, k - 1), Vector3i(i - 1, j, k)]
						_:
							q = [Vector3i(i, j, k), Vector3i(i - 1, j, k), Vector3i(i - 1, j - 1, k), Vector3i(i, j - 1, k)]
					var ids := PackedInt32Array()
					var ok := true
					for c: Vector3i in q:
						var vi := cell[c.x + nx * (c.y + ny * c.z)]
						if vi < 0:
							ok = false
							break
						ids.append(vi)
					if not ok:
						continue
					for tri in [[ids[0], ids[1], ids[2]], [ids[0], ids[2], ids[3]]]:
						var va: Vector3 = verts[tri[0]]
						var vb: Vector3 = verts[tri[1]]
						var vc: Vector3 = verts[tri[2]]
						var fnrm := (vb - va).cross(vc - va)
						var outward: Vector3 = norms[tri[0]] + norms[tri[1]] + norms[tri[2]]
						# Godot's front faces wind clockwise as seen from outside.
						if fnrm.dot(outward) > 0.0:
							idx.append_array([tri[0], tri[2], tri[1]])
						else:
							idx.append_array([tri[0], tri[1], tri[2]])
	return {"v": verts, "n": norms, "i": idx}


func _cut(mesh: Dictionary, f: Callable) -> Dictionary:
	## Split every triangle that straddles f(p) = 0, putting new vertices exactly on the boundary (snapped to
	## the surface), so clothing edges are smooth curves instead of grid staircases.
	var v: PackedVector3Array = mesh.v.duplicate()
	var n: PackedVector3Array = mesh.n.duplicate()
	var idx: PackedInt32Array = mesh.i
	var fv := PackedFloat32Array()
	fv.resize(v.size())
	for i in v.size():
		fv[i] = f.call(v[i])
	var edge_cache := {}
	var out := PackedInt32Array()
	for t in idx.size() / 3:
		var tri := [idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2]]
		var sg := [fv[tri[0]] >= 0.0, fv[tri[1]] >= 0.0, fv[tri[2]] >= 0.0]
		if sg[0] == sg[1] and sg[1] == sg[2]:
			out.append_array(tri)
			continue
		# Rotate so the first vertex is the odd one out.
		var k := 0
		if sg[1] != sg[0] and sg[1] != sg[2]:
			k = 1
		elif sg[2] != sg[0] and sg[2] != sg[1]:
			k = 2
		var a: int = tri[k]
		var b: int = tri[(k + 1) % 3]
		var c: int = tri[(k + 2) % 3]
		var mids: Array = []
		for e in [[a, b], [a, c]]:
			var key := Vector2i(mini(e[0], e[1]), maxi(e[0], e[1]))
			if not edge_cache.has(key):
				var tt := fv[e[0]] / (fv[e[0]] - fv[e[1]])
				var p := v[e[0]].lerp(v[e[1]], tt)
				var g := _grad(p)
				p -= g * _eval(p)
				edge_cache[key] = v.size()
				v.append(p)
				n.append(g)
				fv.append(0.0)
			mids.append(edge_cache[key])
		var ab: int = mids[0]
		var ac: int = mids[1]
		out.append_array([a, ab, ac])
		out.append_array([ab, b, c])
		out.append_array([ab, c, ac])
	return {"v": v, "n": n, "i": out}


func _split(mesh: Dictionary, region_of: Callable, regions: Array) -> Array:
	## Split triangles by region (by centroid) into per-surface vertex/index arrays. Returns [{v, n, i, src}].
	var out: Array = []
	for r in regions:
		out.append({"v": PackedVector3Array(), "n": PackedVector3Array(), "i": PackedInt32Array(), "map": {}, "src": PackedInt32Array()})
	var v: PackedVector3Array = mesh.v
	var n: PackedVector3Array = mesh.n
	var idx: PackedInt32Array = mesh.i
	for t in idx.size() / 3:
		var a := idx[t * 3]
		var b := idx[t * 3 + 1]
		var c := idx[t * 3 + 2]
		var r: int = region_of.call((v[a] + v[b] + v[c]) / 3.0)
		var s: Dictionary = out[r]
		for vi in [a, b, c]:
			if not s.map.has(vi):
				s.map[vi] = s.v.size()
				s.v.append(v[vi])
				s.n.append(n[vi])
				s.src.append(vi)
			s.i.append(int(s.map[vi]))
	return out


func _arrays(s: Dictionary, weights: Callable = Callable()) -> Array:
	var a := []
	a.resize(Mesh.ARRAY_MAX)
	a[Mesh.ARRAY_VERTEX] = s.v
	a[Mesh.ARRAY_NORMAL] = s.n
	a[Mesh.ARRAY_INDEX] = s.i
	if weights.is_valid():
		var bones := PackedInt32Array()
		var ws := PackedFloat32Array()
		for p: Vector3 in s.v:
			var bw: Array = weights.call(p)
			for q in 4:
				bones.append(int(bw[q][0]))
				ws.append(float(bw[q][1]))
		a[Mesh.ARRAY_BONES] = bones
		a[Mesh.ARRAY_WEIGHTS] = ws
	return a


func _save(surfaces: Array, path: String, names: Array, budget: int = 0) -> void:
	## Decimate to the triangle budget (meshoptimizer via ImporterMesh, skin weights kept), add distance LODs,
	## and save. Each surface keeps its share of the budget.
	var total := 0
	for sf in surfaces:
		total += (sf[Mesh.ARRAY_INDEX] as PackedInt32Array).size() / 3
	var im := ImporterMesh.new()
	var tris := 0
	for i in surfaces.size():
		var sf: Array = surfaces[i]
		var n := (sf[Mesh.ARRAY_INDEX] as PackedInt32Array).size() / 3
		if n == 0:
			continue
		if budget > 0 and total > budget:
			sf = _decimate(sf, maxi(int(float(budget) * n / total), 60))
		tris += (sf[Mesh.ARRAY_INDEX] as PackedInt32Array).size() / 3
		im.add_surface(Mesh.PRIMITIVE_TRIANGLES, sf, [], {}, null, names[i])
	im.generate_lods(30.0, 60.0, [])
	var m := im.get_mesh()
	ResourceSaver.save(m, path, ResourceSaver.FLAG_COMPRESS)
	print("  %s: %d tris (from %d)" % [path.get_file(), tris, total])


static func _area(v: PackedVector3Array, idx: PackedInt32Array) -> float:
	var a := 0.0
	for t in idx.size() / 3:
		a += (v[idx[t * 3 + 1]] - v[idx[t * 3]]).cross(v[idx[t * 3 + 2]] - v[idx[t * 3]]).length()
	return a * 0.5


func _decimate(arrays: Array, target: int) -> Array:
	var im := ImporterMesh.new()
	im.add_surface(Mesh.PRIMITIVE_TRIANGLES, arrays)
	im.generate_lods(30.0, 60.0, [])
	var best: PackedInt32Array = arrays[Mesh.ARRAY_INDEX]
	var best_n := best.size() / 3
	var verts0: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var area0 := _area(verts0, best)
	for l in im.get_surface_lod_count(0):
		var lod := im.get_surface_lod_indices(0, l)
		var n := lod.size() / 3
		# Skip LODs that lost surface (the simplifier's sloppy fallback can drop whole pieces).
		if n >= target * 0.8 and n < best_n and _area(verts0, lod) > area0 * 0.975:
			best = lod
			best_n = n
	# Compact: keep only used vertices (with their normals and 4 bone weights each).
	var verts: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var remap := PackedInt32Array()
	remap.resize(verts.size())
	remap.fill(-1)
	var order := PackedInt32Array()
	var idx := PackedInt32Array()
	idx.resize(best.size())
	for i in best.size():
		var v := best[i]
		if remap[v] < 0:
			remap[v] = order.size()
			order.append(v)
		idx[i] = remap[v]
	var out := _new_arrays()
	var nv: PackedVector3Array = arrays[Mesh.ARRAY_NORMAL]
	for o in order:
		out[Mesh.ARRAY_VERTEX].append(verts[o])
		out[Mesh.ARRAY_NORMAL].append(nv[o])
	if arrays[Mesh.ARRAY_BONES] != null:
		var bs: PackedInt32Array = arrays[Mesh.ARRAY_BONES]
		var ws: PackedFloat32Array = arrays[Mesh.ARRAY_WEIGHTS]
		var ob := PackedInt32Array()
		var ow := PackedFloat32Array()
		for o in order:
			for q in 4:
				ob.append(bs[o * 4 + q])
				ow.append(ws[o * 4 + q])
		out[Mesh.ARRAY_BONES] = ob
		out[Mesh.ARRAY_WEIGHTS] = ow
	out[Mesh.ARRAY_INDEX] = idx
	return out


# ================================================================= body

func body_program(body: String, j: Dictionary) -> void:
	var f := body == "f"
	var s := 0.945 if f else 1.0
	var P := []
	var y := func(v: float) -> float: return v * s
	# Torso: pelvis, belly, ribcage, chest, shoulder girdle, back, glutes — all blended softly.
	P.append(["prim", "t", 0.0, "ell", Vector3(0, y.call(0.955), -0.008), Vector3(0.178 if f else 0.162, 0.112, 0.118)])
	P.append(["prim", "t", 0.07, "ell", Vector3(0, y.call(1.08), 0.008), Vector3(0.128 if f else 0.143, 0.13, 0.102)])
	P.append(["prim", "t", 0.07, "ell", Vector3(0, y.call(1.24), -0.002), Vector3(0.146 if f else 0.162, 0.158, 0.112)])
	P.append(["prim", "t", 0.06, "ell", Vector3(0, y.call(1.315), 0.018), Vector3(0.15 if f else 0.168, 0.105, 0.106)])
	P.append(["prim", "t", 0.05, "cap", Vector3(-0.145 if f else -0.16, y.call(1.405), -0.016), Vector3(0.145 if f else 0.16, y.call(1.405), -0.016), 0.05 if f else 0.054])
	P.append(["prim", "t", 0.05, "cap", Vector3(-0.07, y.call(1.445), -0.03), Vector3(0.07, y.call(1.445), -0.03), 0.046])
	P.append(["prim", "t", 0.05, "ell", Vector3(0, y.call(1.27), -0.05), Vector3(0.13 if f else 0.15, 0.14, 0.07)])
	for sx in [-1.0, 1.0]:
		P.append(["prim", "t", 0.05, "sph", Vector3(0.072 * sx, y.call(0.915), -0.07), 0.085 if f else 0.08])
		if f:
			P.append(["prim", "t", 0.035, "ell", Vector3(0.056 * sx, y.call(1.275), 0.07), Vector3(0.062, 0.058, 0.055)])
		else:
			P.append(["prim", "t", 0.04, "ell", Vector3(0.07 * sx, y.call(1.315), 0.058), Vector3(0.07, 0.055, 0.04)])
	# Neck (rises into the head; the head mesh covers the top).
	P.append(["prim", "t", 0.045, "cone", Vector3(0, y.call(1.4), -0.016), Vector3(0, y.call(1.6), -0.004), 0.058 if f else 0.064, 0.05 if f else 0.056])
	var arm_k := 0.6 if f else 1.0
	for side in ["L", "R"]:
		var sx := 1.0 if side == "L" else -1.0
		var g: String = "arm" + side
		var sj: Vector3 = j["shoulder" + side]
		var el: Vector3 = j["elbow" + side]
		var wr: Vector3 = j["wrist" + side]
		var ax := (el - sj).normalized()
		var fa := (wr - el).normalized()
		var r := 0.88 if f else 1.0
		P.append(["prim", g, 0.0, "sph", sj + Vector3(0.006 * sx, 0.0, 0), 0.054 * r])                     # deltoid
		P.append(["prim", g, 0.03, "cone", sj - ax * 0.02, el, 0.05 * r, 0.037 * r])
		P.append(["prim", g, 0.02, "ell", sj.lerp(el, 0.45) + Vector3(0, 0, 0.012), Vector3(0.045, 0.06, 0.045) * r])   # biceps
		P.append(["prim", g, 0.02, "cone", el, wr, 0.04 * r, 0.027 * r])
		P.append(["prim", g, 0.02, "ell", el.lerp(wr, 0.25), Vector3(0.042, 0.055, 0.04) * r])               # forearm muscle
		P.append(["prim", g, 0.015, "cap", wr - fa * 0.01, wr + fa * 0.03, 0.026 * r])
		var lg: String = "leg" + side
		var hp: Vector3 = j["hip" + side]
		var kn: Vector3 = j["knee" + side]
		var an: Vector3 = j["ankle" + side]
		P.append(["prim", lg, 0.0, "cone", hp + Vector3(0, 0.05, 0), kn, 0.096 if f else 0.094, 0.062])
		P.append(["prim", lg, 0.03, "ell", hp.lerp(kn, 0.35) + Vector3(0.005 * sx, 0, 0.01), Vector3(0.078, 0.14, 0.08) * (1.02 if f else 1.0)])
		P.append(["prim", lg, 0.025, "sph", kn + Vector3(0, 0, 0.008), 0.05])
		P.append(["prim", lg, 0.03, "cone", kn, an, 0.058, 0.046])
		P.append(["prim", lg, 0.03, "ell", kn.lerp(an, 0.3) + Vector3(0, 0, -0.018), Vector3(0.05, 0.11, 0.055) * (0.95 if f else 1.0)])
		P.append(["prim", lg, 0.02, "cap", an, an + Vector3(0, -0.03, 0.0), 0.046])
		# Each limb blends into the torso only (never into the other limb).
		P.append(["join", "j" + g, "t", 0.0])
		P.append(["join", "j" + g, g, 0.035 * arm_k + 0.01])
		P.append(["join", "j" + lg, "t", 0.0])
		P.append(["join", "j" + lg, lg, 0.04])
	_prims = P
	_final = ["jarmL", "jarmR", "jlegL", "jlegR"]
	_compile()


func bake_body(body: String, j: Dictionary) -> void:
	body_program(body, j)
	var s := 0.945 if body == "f" else 1.0
	var mesh := _polygonise(Vector3(-0.66, 0.06, -0.22) * Vector3(1, 1, 1), Vector3(0.66, 1.66 * s, 0.2), 0.011)
	var segs := weight_segments(j)
	var waist := 1.0 * s
	var collar := 1.455 * s
	var neckline := func(p: Vector3) -> float:
		# Crew neck: a touch lower at the front.
		return collar - 0.022 * clampf(p.z / 0.06, 0.0, 1.0)
	var arm_t := func(p: Vector3, side: String) -> float:
		var sj: Vector3 = j["shoulder" + side]
		var el: Vector3 = j["elbow" + side]
		return (p - sj).dot(el - sj) / (el - sj).length_squared()
	# Cut the mesh exactly along each garment edge first.
	mesh = _cut(mesh, func(p: Vector3) -> float: return p.y - waist)
	var neck_r := func(p: Vector3) -> float: return Vector2(p.x, p.z + 0.016).length()
	mesh = _cut(mesh, func(p: Vector3) -> float: return (p.y - float(neckline.call(p))) if float(neck_r.call(p)) < 0.085 and p.y > collar - 0.08 else -1.0)
	mesh = _cut(mesh, func(p: Vector3) -> float: return (0.085 - float(neck_r.call(p))) if p.y > float(neckline.call(p)) and p.y < collar + 0.06 else -1.0)
	mesh = _cut(mesh, func(p: Vector3) -> float: return (float(arm_t.call(p, "L")) - 0.6) if p.x > 0.1 else -1.0)
	mesh = _cut(mesh, func(p: Vector3) -> float: return (float(arm_t.call(p, "R")) - 0.6) if p.x < -0.1 else -1.0)
	# Region per point: 0 skin · 1 shirt · 2 trousers
	var region := func(p: Vector3) -> int:
		var bone := _nearest_bone(p, segs)
		if bone in [6, 7, 8]:
			return 0 if float(arm_t.call(p, "L")) > 0.6 else 1
		if bone in [13, 14, 15]:
			return 0 if float(arm_t.call(p, "R")) > 0.6 else 1
		if float(neck_r.call(p)) < 0.085 and p.y > float(neckline.call(p)):
			return 0
		return 2 if p.y < waist else 1
	var parts := _split(mesh, region, [0, 1, 2])
	# Clothes sit on the body: push the shirt and trousers out a few millimetres (hems cover the steps).
	for pi in [1, 2]:
		var pv: PackedVector3Array = parts[pi].v
		var pn: PackedVector3Array = parts[pi].n
		for vi in pv.size():
			pv[vi] += pn[vi] * (0.0035 if pi == 1 else 0.0025)
		parts[pi].v = pv
	var wfn := func(p: Vector3) -> Array: return _weights(p, segs)
	var surfaces := [_arrays(parts[0], wfn), _arrays(parts[1], wfn), _arrays(parts[2], wfn)]
	# Hems: collar, sleeve ends, belt — rings fitted to the actual surface so clothes read as clothes.
	var trim := _new_arrays()
	var belt := _new_arrays()
	_ring_on_surface(trim, Vector3(0, collar - 0.01, -0.016), Vector3.UP, 0.0075, 0.0045, false, neckline, 0.087)
	for side in ["L", "R"]:
		var sj: Vector3 = j["shoulder" + side]
		var el: Vector3 = j["elbow" + side]
		_ring_on_surface(trim, sj.lerp(el, 0.6), (el - sj).normalized(), 0.0075, 0.0045)
	_ring_on_surface(belt, Vector3(0, waist + 0.004, 0.0), Vector3.UP, 0.026, 0.0045, true)
	_add_weights(trim, segs)
	_add_weights(belt, segs)
	_append(surfaces[1], trim)
	surfaces.append(belt)
	_save(surfaces, OUT + "body_%s.res" % body, ["skin", "shirt", "trousers", "belt"], 11000)
	# Hi-vis vest: the torso part of the shirt, pushed out a little, plus two reflective bands.
	var vest := _new_arrays()
	var shirt: Dictionary = parts[1]
	var vmap := {}
	var sv: PackedVector3Array = shirt.v
	var sn: PackedVector3Array = shirt.n
	var si: PackedInt32Array = shirt.i
	for t in si.size() / 3:
		var a := sv[si[t * 3]]
		var b2 := sv[si[t * 3 + 1]]
		var c := sv[si[t * 3 + 2]]
		var cen := (a + b2 + c) / 3.0
		var nb := _nearest_bone(cen, segs)
		if not nb in [0, 1, 2] or cen.y < waist + 0.03 or cen.y > collar - 0.04 or absf(cen.x) > 0.17 * s:
			continue
		for k in 3:
			var vi := si[t * 3 + k]
			if not vmap.has(vi):
				vmap[vi] = (vest[Mesh.ARRAY_VERTEX] as PackedVector3Array).size()
				vest[Mesh.ARRAY_VERTEX].append(sv[vi] + sn[vi] * 0.006)
				vest[Mesh.ARRAY_NORMAL].append(sn[vi])
			vest[Mesh.ARRAY_INDEX].append(int(vmap[vi]))
	_add_weights(vest, segs)
	var bands := _new_arrays()
	for by in [1.13, 1.28]:
		_ring_on_surface(bands, Vector3(0, by * s, 0.0), Vector3.UP, 0.02, 0.0085, true)
	_add_weights(bands, segs)
	_save([vest, bands], OUT + "vest_%s.res" % body, ["vest", "bands"], 2400)


func _nearest_bone(p: Vector3, segs: Array) -> int:
	var best := 0
	var bd := 1e9
	for sg in segs:
		var d := _seg_dist(p, sg[1], sg[2])
		if d < bd:
			bd = d
			best = int(sg[0])
	return best


static func _seg_dist(p: Vector3, a: Vector3, b: Vector3) -> float:
	var ab := b - a
	var t := clampf((p - a).dot(ab) / maxf(ab.length_squared(), 1e-9), 0.0, 1.0)
	return p.distance_to(a + ab * t)


func _weights(p: Vector3, segs: Array) -> Array:
	## Inverse-distance^4 to the bone segments, best four, normalised — smooth bends at every joint.
	var cand: Array = []
	for sg in segs:
		var d := maxf(_seg_dist(p, sg[1], sg[2]), 0.004)
		cand.append([int(sg[0]), 1.0 / pow(d, 4.0)])
	cand.sort_custom(func(a, b): return a[1] > b[1])
	cand = cand.slice(0, 4)
	var tot := 0.0
	for c in cand:
		tot += float(c[1])
	for c in cand:
		c[1] = float(c[1]) / tot
	return cand


func _new_arrays() -> Array:
	var a := []
	a.resize(Mesh.ARRAY_MAX)
	a[Mesh.ARRAY_VERTEX] = PackedVector3Array()
	a[Mesh.ARRAY_NORMAL] = PackedVector3Array()
	a[Mesh.ARRAY_INDEX] = PackedInt32Array()
	return a


func _append(dst: Array, src: Array) -> void:
	var base := (dst[Mesh.ARRAY_VERTEX] as PackedVector3Array).size()
	dst[Mesh.ARRAY_VERTEX].append_array(src[Mesh.ARRAY_VERTEX])
	dst[Mesh.ARRAY_NORMAL].append_array(src[Mesh.ARRAY_NORMAL])
	for i: int in src[Mesh.ARRAY_INDEX]:
		dst[Mesh.ARRAY_INDEX].append(i + base)
	if src[Mesh.ARRAY_BONES] != null:
		dst[Mesh.ARRAY_BONES].append_array(src[Mesh.ARRAY_BONES])
		dst[Mesh.ARRAY_WEIGHTS].append_array(src[Mesh.ARRAY_WEIGHTS])


func _add_weights(a: Array, segs: Array) -> void:
	var bones := PackedInt32Array()
	var ws := PackedFloat32Array()
	for p: Vector3 in a[Mesh.ARRAY_VERTEX]:
		for bw in _weights(p, segs):
			bones.append(int(bw[0]))
			ws.append(float(bw[1]))
	a[Mesh.ARRAY_BONES] = bones
	a[Mesh.ARRAY_WEIGHTS] = ws


func _ring_on_surface(a: Array, centre: Vector3, axis: Vector3, width: float, lift: float, flat: bool = false, follow: Callable = Callable(), max_r: float = 9.0) -> void:
	## A band hugging the current SDF surface around `centre` (perpendicular to `axis`), raised by `lift`.
	var segs := 48
	var x := axis.cross(Vector3.FORWARD if absf(axis.dot(Vector3.FORWARD)) < 0.9 else Vector3.RIGHT).normalized()
	var z := x.cross(axis).normalized()
	var base := (a[Mesh.ARRAY_VERTEX] as PackedVector3Array).size()
	var rings := [-width * 0.5, width * 0.5] if flat else [-width * 0.5, 0.0, width * 0.5]
	for i in segs:
		var th := TAU * i / segs
		var dir := x * cos(th) + z * sin(th)
		# March out from the centre to the surface.
		var r := 0.0
		for step in 120:
			if _eval(centre + dir * r) > 0.0:
				break
			r += 0.004
		var c2 := centre
		if follow.is_valid():
			# Follow a curved edge (the neckline dips at the front).
			c2.y = float(follow.call(centre + dir * r))
			r = 0.0
			for step in 120:
				if _eval(c2 + dir * r) > 0.0:
					break
				r += 0.004
		for bi in rings.size():
			var off: float = rings[bi]
			var bulge := lift if flat else lift * (1.0 - absf(off) / (width * 0.5) * 0.7)
			var p := c2 + axis * off + dir * (minf(r, max_r) + bulge)
			a[Mesh.ARRAY_VERTEX].append(p)
			a[Mesh.ARRAY_NORMAL].append((dir + axis * (off / width) * (0.0 if flat else 1.2)).normalized())
	var per := rings.size()
	for i in segs:
		var ni := (i + 1) % segs
		for bi in per - 1:
			var p0 := base + i * per + bi
			var p1 := base + ni * per + bi
			var p2 := base + ni * per + bi + 1
			var p3 := base + i * per + bi + 1
			for tri in [[p0, p1, p2], [p0, p2, p3]]:
				var va: Vector3 = a[Mesh.ARRAY_VERTEX][tri[0]]
				var vb: Vector3 = a[Mesh.ARRAY_VERTEX][tri[1]]
				var vc: Vector3 = a[Mesh.ARRAY_VERTEX][tri[2]]
				var out: Vector3 = a[Mesh.ARRAY_NORMAL][tri[0]]
				if (vb - va).cross(vc - va).dot(out) > 0.0:
					a[Mesh.ARRAY_INDEX].append_array([tri[0], tri[2], tri[1]])
				else:
					a[Mesh.ARRAY_INDEX].append_array([tri[0], tri[1], tri[2]])


# ================================================================= head

func head_program(body: String) -> void:
	## Head-bone space: origin at the top of the neck, +Z forward. ~23 cm chin to crown.
	var f := body == "f"
	var P := []
	P.append(["prim", "h", 0.0, "ell", Vector3(0, 0.122, -0.008), Vector3(0.074 if f else 0.078, 0.1, 0.096)])           # cranium
	P.append(["prim", "h", 0.035, "ell", Vector3(0, 0.07, 0.024), Vector3(0.054 if f else 0.061, 0.07, 0.07)])         # face mass
	P.append(["prim", "h", 0.03, "ell", Vector3(0, 0.03, 0.04), Vector3(0.042 if f else 0.05, 0.03, 0.045)])           # jaw
	P.append(["prim", "h", 0.02, "sph", Vector3(0, 0.017, 0.064 if f else 0.066), 0.02 if f else 0.024])               # chin
	for sx in [-1.0, 1.0]:
		P.append(["prim", "h", 0.022, "sph", Vector3(0.042 * sx, 0.085, 0.058), 0.022])                                # cheekbones
		P.append(["prim", "h", 0.02, "ell", Vector3(0.045 * sx, 0.04, 0.03), Vector3(0.016, 0.022, 0.03)])             # jaw angle
		P.append(["sub", "h", 0.012, "sph", Vector3(0.031 * sx, 0.107, 0.093), 0.0185])                                # eye sockets
		P.append(["prim", "ear", 0.0, "ell", Vector3(0.079 * sx, 0.097, -0.006), Vector3(0.012, 0.03 if f else 0.031, 0.02)])
		P.append(["sub", "ear", 0.004, "ell", Vector3(0.088 * sx, 0.097, -0.002), Vector3(0.006, 0.019, 0.011)])
	P.append(["prim", "h", 0.016 if f else 0.02, "cap", Vector3(-0.036, 0.127, 0.082), Vector3(0.036, 0.127, 0.082), 0.011 if f else 0.014])   # brow
	P.append(["prim", "h", 0.008, "cone", Vector3(0, 0.111, 0.088), Vector3(0, 0.072, 0.106 if f else 0.11), 0.008, 0.012 if f else 0.014])   # nose
	for sx in [-1.0, 1.0]:
		P.append(["prim", "h", 0.007, "sph", Vector3(0.012 * sx, 0.069, 0.096), 0.009])                                # nostril wings
	P.append(["sub", "h", 0.004, "sph", Vector3(0, 0.061, 0.104), 0.006])                                              # under the nose
	P.append(["prim", "lip", 0.0, "ell", Vector3(0, 0.048, 0.086), Vector3(0.0195, 0.006 if f else 0.0052, 0.0085)])
	P.append(["prim", "lip", 0.006, "ell", Vector3(0, 0.0365, 0.084), Vector3(0.018, 0.0078 if f else 0.0068, 0.0088)])
	P.append(["sub", "lip", 0.002, "ell", Vector3(0, 0.0425, 0.097), Vector3(0.023, 0.0012, 0.012)])                   # the mouth line
	P.append(["prim", "h", 0.03, "cone", Vector3(0, -0.05, -0.012), Vector3(0, 0.05, -0.006), 0.058 if f else 0.064, 0.055])  # neck top
	P.append(["join", "all", "h", 0.0])
	P.append(["join", "all", "ear", 0.006])
	P.append(["join", "all", "lip", 0.006])
	_prims = P
	_final = ["all"]
	_compile()


func bake_head(body: String) -> void:
	head_program(body)
	var mesh := _polygonise(Vector3(-0.1, -0.03, -0.12), Vector3(0.1, 0.235, 0.13), 0.0032)
	var lips := func(p: Vector3) -> int:
		var dl := minf(sd_ellipsoid(p, Vector3(0, 0.048, 0.086), Vector3(0.0215, 0.0075, 0.0105)), sd_ellipsoid(p, Vector3(0, 0.0365, 0.084), Vector3(0.0198, 0.0093, 0.0108)))
		return 1 if dl < 0.0012 and p.z > 0.079 else 0
	var parts := _split(mesh, lips, [0, 1])
	_save([_arrays(parts[0]), _arrays(parts[1])], OUT + "head_%s.res" % body, ["skin", "lips"], 7000)


# ================================================================= hands, shoes, hair

func bake_hand() -> void:
	## Hand-bone space for the LEFT hand hanging down: fingers along -Y, palm facing -X (inwards), thumb +Z.
	var P := []
	P.append(["prim", "h", 0.0, "box", Vector3(-0.002, -0.05, 0.0), Vector3(0.0135, 0.04, 0.038), 0.011])        # palm
	P.append(["prim", "h", 0.015, "ell", Vector3(0, 0.0, 0), Vector3(0.021, 0.025, 0.03)])                      # wrist
	var zs := [0.027, 0.009, -0.009, -0.026]
	var lens := [0.074, 0.082, 0.077, 0.061]
	for i in 4:
		var k0 := Vector3(-0.001, -0.086, zs[i])
		var k1 := k0 + Vector3(-0.006, -lens[i] * 0.55, 0)
		var k2 := k1 + Vector3(-0.012, -lens[i] * 0.45, 0)
		P.append(["prim", "h", 0.006, "cone", k0, k1, 0.0092, 0.0082])
		P.append(["prim", "h", 0.004, "cone", k1, k2, 0.0082, 0.0068])
	P.append(["prim", "h", 0.008, "cone", Vector3(-0.004, -0.028, 0.03), Vector3(-0.016, -0.062, 0.05), 0.0125, 0.0098])
	P.append(["prim", "h", 0.004, "cone", Vector3(-0.016, -0.062, 0.05), Vector3(-0.025, -0.088, 0.047), 0.0098, 0.0078])
	_prims = P
	_final = ["h"]
	_compile()
	var mesh := _polygonise(Vector3(-0.045, -0.2, -0.06), Vector3(0.03, 0.03, 0.075), 0.0022)
	_save([_arrays({"v": mesh.v, "n": mesh.n, "i": mesh.i})], OUT + "hand.res", ["skin"], 1800)


func bake_shoe() -> void:
	## Foot-bone space: origin at the ankle, toes towards +Z, sole on the floor at y = -0.085.
	var P := []
	P.append(["prim", "s", 0.0, "box", Vector3(0, -0.073, 0.045), Vector3(0.047, 0.012, 0.132), 0.012])               # sole
	P.append(["prim", "u", 0.0, "ell", Vector3(0, -0.04, -0.03), Vector3(0.045, 0.05, 0.052)])                         # heel
	P.append(["prim", "u", 0.03, "ell", Vector3(0, -0.047, 0.07), Vector3(0.044, 0.034, 0.1)])                         # vamp
	P.append(["prim", "u", 0.02, "cone", Vector3(0, -0.03, -0.012), Vector3(0, 0.035, -0.012), 0.046, 0.041])          # collar
	P.append(["prim", "u", 0.02, "ell", Vector3(0, -0.056, 0.135), Vector3(0.043, 0.03, 0.05)])                        # toe
	P.append(["prim", "u", 0.012, "ell", Vector3(0, -0.012, 0.045), Vector3(0.026, 0.02, 0.06)])                       # tongue + laces
	P.append(["sub", "u", 0.006, "cap", Vector3(0, 0.05, -0.012), Vector3(0, 0.0, -0.012), 0.034])                     # opening
	P.append(["join", "all", "u", 0.0])
	P.append(["join", "all", "s", 0.004])
	_prims = P
	_final = ["all"]
	_compile()
	var mesh := _polygonise(Vector3(-0.065, -0.092, -0.1), Vector3(0.065, 0.06, 0.2), 0.003)
	var sole := func(p: Vector3) -> int: return 1 if p.y < -0.068 else 0
	var parts := _split(mesh, sole, [0, 1])
	_save([_arrays(parts[0]), _arrays(parts[1])], OUT + "shoe.res", ["upper", "sole"], 2200)


func bake_hair(style: String) -> void:
	## Hair volumes in head-bone space: a shell over the cranium cut at a natural hairline, plus style extras.
	var P := []
	var thick: float = {"short": 0.011, "side": 0.012, "long": 0.012, "bun": 0.01, "buzz": 0.0035, "curly": 0.02}[style]
	P.append(["prim", "hair", 0.0, "ell", Vector3(0, 0.124, -0.008), Vector3(0.078, 0.1, 0.096) + Vector3.ONE * float(thick)])
	if style == "side":
		P.append(["prim", "hair", 0.03, "ell", Vector3(0.018, 0.2, 0.03), Vector3(0.062, 0.03, 0.065)])
	if style == "short":
		P.append(["prim", "hair", 0.025, "ell", Vector3(0, 0.205, 0.045), Vector3(0.06, 0.025, 0.05)])
	if style == "long":
		P.append(["prim", "hair", 0.04, "cone", Vector3(0, 0.12, -0.05), Vector3(0, -0.07, -0.06), 0.085, 0.07])
		for sx in [-1.0, 1.0]:
			P.append(["prim", "hair", 0.03, "cone", Vector3(0.075 * sx, 0.12, -0.02), Vector3(0.088 * sx, -0.03, -0.03), 0.02, 0.016])
	if style == "bun":
		P.append(["prim", "hair", 0.02, "sph", Vector3(0, 0.205, -0.085), 0.036])
	_prims = P
	_final = ["hair"]
	_compile()
	var line := func(p: Vector3) -> float:
		# Hairline height by angle around the head: forehead high, temples dip, sides above the ears, nape low.
		var th := absf(atan2(p.x, p.z))
		var front := 0.152 - 0.012 * exp(-pow((th - 0.55) / 0.18, 2.0))
		var y0 := lerpf(front, 0.098, smoothstep(0.75, 1.5, th))
		y0 = lerpf(y0, 0.035 if style != "long" else -0.08, smoothstep(1.9, 2.9, th))
		if style == "long" and th < 1.4 and th > 0.9:
			y0 = minf(y0, lerpf(y0, 0.075, smoothstep(0.9, 1.3, th)))
		return y0
	_hairline = line
	_hair_thick = thick
	_hair_mode = true
	_hair_curl = style == "curly"
	var mesh := _polygonise(Vector3(-0.11, -0.1, -0.15), Vector3(0.11, 0.26, 0.14), 0.0033)
	_hair_mode = false
	_hair_curl = false
	_save([_arrays({"v": mesh.v, "n": mesh.n, "i": mesh.i})], OUT + "hair_%s.res" % style, ["hair"], 5000)

