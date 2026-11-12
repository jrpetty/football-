extends SceneTree
## Gives heavy model meshes a light shadow-only stand-in.
##
## The AI models have a UV seam at nearly every vertex, so Godot can't build LODs for them (a 176k-triangle rack was
## drawn in full in every shadow cascade). A shadow needs no UVs: this tool welds the mesh by position, simplifies it
## to ~1/12 of the triangles, saves it as shadow.res next to the mesh and adds it to the model's wrapper scene as a
## SHADOWS_ONLY MeshInstance3D; the detailed mesh stops casting. What you see is unchanged.
## (Simplified main-pass LODs were tried and rejected: remapping onto the seamed UVs smeared the textures.)
##   godot --headless --path . -s tools/shadow_proxies.gd [-- --dry]
## Re-run after tools/model_pipeline produces new models; meshes that already have a ShadowProxy are skipped.

const MIN_TRIS := 40000
const SKIP := ["robot_arm"]      # articulated (a mesh per joint)

var _dry := false


func _init() -> void:
	_dry = "--dry" in OS.get_cmdline_user_args()
	var n := [0]
	_scan("res://assets/models", n)
	print("shadow proxies made: %d" % n[0])
	quit()


func _scan(dir: String, n: Array) -> void:
	for d in DirAccess.get_directories_at(dir):
		_scan(dir + "/" + d, n)
	for f in DirAccess.get_files_at(dir):
		if not f.ends_with(".res") or f == "shadow.res" or SKIP.any(func(s): return s in dir):
			continue
		var path := dir + "/" + f
		var m = load(path)
		if m is ArrayMesh and _make(m as ArrayMesh, path):
			n[0] += 1


func _make(m: ArrayMesh, path: String) -> bool:
	var tris := 0
	for si in m.get_surface_count():
		var idx = m.surface_get_arrays(si)[Mesh.ARRAY_INDEX]
		tris += (idx as PackedInt32Array).size() / 3 if idx != null else 0
	if tris < MIN_TRIS or FileAccess.file_exists(path.get_base_dir() + "/shadow.res"):
		return false
	var proxy := ArrayMesh.new()
	var out_tris := 0
	for si in m.get_surface_count():
		var arrays: Array = m.surface_get_arrays(si)
		if arrays[Mesh.ARRAY_BONES] != null or arrays[Mesh.ARRAY_INDEX] == null:
			return false
		var parr := simplified(arrays, maxi(4000, tris / 12))
		if parr.is_empty():
			return false
		out_tris += (parr[Mesh.ARRAY_INDEX] as PackedInt32Array).size() / 3
		proxy.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, parr)
	print("%s: %d tris -> shadow %d" % [path, tris, out_tris])
	if _dry:
		return true
	var ppath := path.get_base_dir() + "/shadow.res"
	ResourceSaver.save(proxy, ppath)
	_add_to_scenes(path, ppath)
	return true


static func simplified(arrays: Array, target: int) -> Array:
	## Position-welded copy (positions + smooth normals, no UVs) simplified to about `target` triangles.
	var v: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
	var idx: PackedInt32Array = arrays[Mesh.ARRAY_INDEX]
	var key_to_w := {}
	var w_of := PackedInt32Array()
	w_of.resize(v.size())
	var wpos := PackedVector3Array()
	for i in v.size():
		var p := v[i]
		var key := Vector3i(roundi(p.x * 2e4), roundi(p.y * 2e4), roundi(p.z * 2e4))
		var w: int = key_to_w.get(key, -1)
		if w < 0:
			w = wpos.size()
			key_to_w[key] = w
			wpos.append(p)
		w_of[i] = w
	var widx := PackedInt32Array()
	for t in idx.size() / 3:
		var a := w_of[idx[t * 3]]
		var b := w_of[idx[t * 3 + 1]]
		var c := w_of[idx[t * 3 + 2]]
		if a != b and b != c and a != c:
			widx.append_array([a, b, c])
	var wn := PackedVector3Array()
	wn.resize(wpos.size())
	for t in widx.size() / 3:
		var a := wpos[widx[t * 3]]
		var fn := (wpos[widx[t * 3 + 2]] - a).cross(wpos[widx[t * 3 + 1]] - a)
		for k in 3:
			wn[widx[t * 3 + k]] += fn
	for i in wn.size():
		wn[i] = wn[i].normalized() if wn[i].length_squared() > 0.0 else Vector3.UP
	var warr := []
	warr.resize(Mesh.ARRAY_MAX)
	warr[Mesh.ARRAY_VERTEX] = wpos
	warr[Mesh.ARRAY_NORMAL] = wn
	warr[Mesh.ARRAY_INDEX] = widx
	var im := ImporterMesh.new()
	im.add_surface(Mesh.PRIMITIVE_TRIANGLES, warr)
	im.generate_lods(25.0, 60.0, [])
	var best := widx
	for l in im.get_surface_lod_count(0):
		var li := im.get_surface_lod_indices(0, l)
		if li.size() / 3 >= target or best == widx:
			best = li
	warr[Mesh.ARRAY_INDEX] = best
	return warr


func _add_to_scenes(mesh_path: String, proxy_path: String) -> void:
	## The model's wrapper scene(s) in the same folder: the detailed mesh stops casting shadows and the
	## shadow-only stand-in (same transform) casts them instead.
	var dir := mesh_path.get_base_dir()
	for f in DirAccess.get_files_at(dir):
		if not f.ends_with(".tscn"):
			continue
		var sp := dir + "/" + f
		var scene: Node = (load(sp) as PackedScene).instantiate()
		if scene.find_child("ShadowProxy", true, false):
			scene.free()
			continue
		var changed := false
		for mi: MeshInstance3D in scene.find_children("*", "MeshInstance3D", true, false):
			if mi.mesh == null or mi.mesh.resource_path != mesh_path:
				continue
			var proxy := MeshInstance3D.new()
			proxy.name = "ShadowProxy"
			proxy.mesh = load(proxy_path)
			proxy.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_SHADOWS_ONLY
			proxy.transform = mi.transform
			mi.get_parent().add_child(proxy)
			proxy.owner = scene
			mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
			changed = true
		if changed:
			var ps := PackedScene.new()
			ps.pack(scene)
			ResourceSaver.save(ps, sp)
			print("  added to ", sp)
		scene.free()
