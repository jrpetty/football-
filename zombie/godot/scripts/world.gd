class_name World
extends Node3D
## Everything you can see of the world: the ground, the lake, the town, its clutter, the forest and the ore
## deposits. All of it is built from the exporter's files, so it is the same place as in the web version.

var town_root: Node3D
var town_data: Dictionary = {}
var veg_data: Dictionary = {}
var veg_inst := PackedFloat32Array()
var doors: Dictionary = {}        # id -> {node, data}
var windows: Dictionary = {}
var buildings: Dictionary = {}    # id -> {node, interior, data}
var containers: Array = []
var terrain_mat: ShaderMaterial
var water: MeshInstance3D
var water_mat: ShaderMaterial
var veg_map: Dictionary = {}      # vegetation index -> [[MultiMesh, instance], ...]
var veg_circle: Dictionary = {}   # vegetation index -> its collision cylinder
var veg_hp: Dictionary = {}       # vegetation index -> hit points left (only once it has been hit)
var veg_dead: Dictionary = {}
var container_caps: Dictionary = {}
var drops: Array = []             # bags on the ground: {pos, inv, node, label}

static func read_json(path: String) -> Variant:
	var f := FileAccess.open(path, FileAccess.READ)
	if f == null:
		return {}
	return JSON.parse_string(f.get_as_text())

static func xform(e: Array) -> Transform3D:
	return Transform3D(Basis(Vector3(e[0], e[1], e[2]), Vector3(e[4], e[5], e[6]), Vector3(e[8], e[9], e[10])), Vector3(e[12], e[13], e[14]))

func build() -> void:
	_build_terrain()
	_build_grass()
	_build_water()
	_build_town()
	_build_props()
	_build_veg()
	_build_ores()

# ------------------------------------------------------------------ ground
func _build_terrain() -> void:
	var info: Dictionary = WorldData.info.terrain
	terrain_mat = ShaderMaterial.new()
	terrain_mat.shader = load("res://shaders/terrain.gdshader")
	var sn := int(info.splatN)
	var simg := Image.create_from_data(sn, sn, false, Image.FORMAT_RGBA8, FileAccess.get_file_as_bytes("res://data/splat.bin"))
	simg.generate_mipmaps()
	terrain_mat.set_shader_parameter("splat", ImageTexture.create_from_image(simg))
	terrain_mat.set_shader_parameter("noise_tex", Mats.tex(info.noise))
	var names := {"grass": "grass", "dirt": "dirt", "rock": "rock", "floor": "forestFloor", "asphalt": "asphalt", "sand": "sand"}
	var photo := {"grass": "grass", "dirt": "dirt", "rock": "rock", "floor": "forest", "asphalt": "asphalt", "sand": "sand"}
	var photos := true
	for k in names:
		var s: Dictionary = info.sets[names[k]]
		var base := "res://assets/tex/hf_%s" % photo[k]
		if ResourceLoader.exists(base + "_a.webp"):
			terrain_mat.set_shader_parameter("t_" + k, load(base + "_a.webp"))
			terrain_mat.set_shader_parameter("n_" + k, load(base + "_n.webp"))
			terrain_mat.set_shader_parameter("r_" + k, load(base + "_r.webp"))
		else:
			photos = false
			terrain_mat.set_shader_parameter("t_" + k, Mats.tex(s.map))
			terrain_mat.set_shader_parameter("n_" + k, Mats.tex(s.normal))
			terrain_mat.set_shader_parameter("r_" + k, Mats.tex(s.rough))
	# photographic tiles cover less ground each than the painted ones did
	terrain_mat.set_shader_parameter("uv_scale", 0.45 if photos else 0.25)
	terrain_mat.set_shader_parameter("road_scale", 0.3 if photos else 0.18)
	terrain_mat.set_shader_parameter("grass_tint", Color(0.8, 0.8, 0.6) if photos else Color.WHITE)
	var n := WorldData.hn
	var res := WorldData.hres
	var hs := WorldData.heights
	const CH := 16
	var per := (n - 1) / CH
	var root := Node3D.new(); root.name = "Terrain"; add_child(root)
	for cz in CH:
		for cx in CH:
			var pos := PackedVector3Array(); var nor := PackedVector3Array(); var idx := PackedInt32Array()
			for j in per + 1:
				for i in per + 1:
					var gi := cx * per + i; var gj := cz * per + j
					var h := hs[gj * n + gi]
					pos.append(Vector3(-WorldData.HALF + gi * res, h, -WorldData.HALF + gj * res))
					var hl := hs[gj * n + maxi(0, gi - 1)]; var hr := hs[gj * n + mini(n - 1, gi + 1)]
					var hd := hs[maxi(0, gj - 1) * n + gi]; var hu := hs[mini(n - 1, gj + 1) * n + gi]
					nor.append(Vector3(hl - hr, 2.0 * res, hd - hu).normalized())
			for j in per:
				for i in per:
					var a := j * (per + 1) + i; var b := a + 1; var c := a + per + 1; var d := c + 1
					idx.append_array(PackedInt32Array([a, b, c, b, d, c]))
			var arr := []; arr.resize(Mesh.ARRAY_MAX)
			arr[Mesh.ARRAY_VERTEX] = pos; arr[Mesh.ARRAY_NORMAL] = nor; arr[Mesh.ARRAY_INDEX] = idx
			var mesh := ArrayMesh.new()
			mesh.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, arr)
			mesh.surface_set_material(0, terrain_mat)
			var mi := MeshInstance3D.new(); mi.mesh = mesh
			mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON
			root.add_child(mi)

var grass: MultiMeshInstance3D
var grass_mat: ShaderMaterial
func _build_grass(count := 260000) -> void:
	const S := 70.0
	var bw := 0.03
	var pos := PackedVector3Array([Vector3(-bw / 2, 0, 0), Vector3(bw / 2, 0, 0), Vector3(-bw * 0.38, 0.4, 0), Vector3(bw * 0.38, 0.4, 0), Vector3(-bw * 0.2, 0.75, 0), Vector3(bw * 0.2, 0.75, 0), Vector3(0, 1, 0)])
	var uv := PackedVector2Array([Vector2(0, 0), Vector2(1, 0), Vector2(0, 0.4), Vector2(1, 0.4), Vector2(0, 0.75), Vector2(1, 0.75), Vector2(0.5, 1)])
	var nor := PackedVector3Array(); nor.resize(7); nor.fill(Vector3.UP)
	var arr := []; arr.resize(Mesh.ARRAY_MAX)
	arr[Mesh.ARRAY_VERTEX] = pos; arr[Mesh.ARRAY_NORMAL] = nor; arr[Mesh.ARRAY_TEX_UV] = uv
	arr[Mesh.ARRAY_INDEX] = PackedInt32Array([0, 1, 2, 1, 3, 2, 2, 3, 4, 3, 5, 4, 4, 5, 6])
	var blade := ArrayMesh.new(); blade.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, arr)
	grass_mat = ShaderMaterial.new(); grass_mat.shader = load("res://shaders/grass.gdshader")
	var himg := Image.create_from_data(WorldData.hn, WorldData.hn, false, Image.FORMAT_RF, WorldData.heights.to_byte_array())
	grass_mat.set_shader_parameter("height_tex", ImageTexture.create_from_image(himg))
	grass_mat.set_shader_parameter("splat", terrain_mat.get_shader_parameter("splat"))
	grass_mat.set_shader_parameter("patch_size", S)
	grass_mat.set_shader_parameter("height_n", float(WorldData.hn))
	grass_mat.set_shader_parameter("water_level", float(WorldData.lake.level))
	blade.surface_set_material(0, grass_mat)
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D; mm.use_custom_data = true; mm.mesh = blade
	mm.instance_count = count
	var r := RandomNumberGenerator.new(); r.seed = 77
	var ident := Transform3D.IDENTITY
	for i in count:
		mm.set_instance_transform(i, ident)
		mm.set_instance_custom_data(i, Color(r.randf(), r.randf(), r.randf(), r.randf()))
	mm.custom_aabb = AABB(Vector3(-420, -60, -420), Vector3(840, 240, 840))
	grass = MultiMeshInstance3D.new(); grass.name = "Grass"; grass.multimesh = mm
	grass.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(grass)

func _build_water() -> void:
	var lake: Dictionary = WorldData.lake
	var m := PlaneMesh.new(); m.size = Vector2.ONE * (lake.r + 14.0) * 2.0; m.subdivide_width = 24; m.subdivide_depth = 24
	water = MeshInstance3D.new(); water.name = "Water"; water.mesh = m
	water.position = Vector3(lake.x, lake.level, lake.z)
	var mat := ShaderMaterial.new(); mat.shader = load("res://shaders/water.gdshader")
	mat.set_shader_parameter("normal_tex", Mats.tex(WorldData.info.water.normal))
	mat.set_shader_parameter("radius", lake.r + 14.0)
	water_mat = mat
	water.material_override = mat
	water.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(water)

# ------------------------------------------------------------------ exported scenes
## instance an exported .glb: real materials, hidden library, and its instanced meshes as MultiMeshes
func spawn_export(path: String, parent: Node3D, cell := 96.0, range_end := 0.0) -> Node3D:
	var scene: PackedScene = load(path + ".glb")
	if scene == null:
		push_error("missing " + path)
		return null
	var node: Node3D = scene.instantiate()
	parent.add_child(node)
	Mats.apply(node)
	var lib := node.get_node_or_null("__lib")
	if lib != null:
		lib.visible = false
		if FileAccess.file_exists(path + ".json"):
			var data = read_json(path + ".json")
			var by_mesh: Dictionary = {}
			for inst in data.get("instances", []):
				var list: Array = by_mesh.get(inst.mesh, [])
				for i in inst.m.size():
					list.append([xform(inst.m[i]), null if not inst.has("c") else Color(inst.c[i][0], inst.c[i][1], inst.c[i][2])])
				by_mesh[inst.mesh] = list
			build_multimeshes(node, lib, by_mesh, cell, range_end)
	return node

## one MultiMesh per (library mesh, map cell), so far-off clutter is culled as a block
func build_multimeshes(parent: Node3D, lib: Node, by_mesh: Dictionary, cell := 96.0, range_end := 0.0, shadows: Dictionary = {}, tints: Dictionary = {}, tag_map = null) -> void:
	for mesh_name in by_mesh:
		var src := lib.get_node_or_null(String(mesh_name)) as MeshInstance3D
		if src == null or src.mesh == null:
			continue
		var tint: bool = tints.get(mesh_name, false)
		if tint:
			# a tinted copy of the mesh: per-instance colour multiplies the paint
			var dup: Mesh = src.mesh.duplicate()
			for s in dup.get_surface_count():
				var cur := dup.surface_get_material(s)
				if cur != null:
					dup.surface_set_material(s, Mats.get_mat(cur.resource_name, true))
			src = src.duplicate(); src.mesh = dup
		var local := src.transform
		var cells: Dictionary = {}
		for it in by_mesh[mesh_name]:
			var t: Transform3D = it[0] * local
			var key := Vector2i(int(floor(t.origin.x / cell)), int(floor(t.origin.z / cell)))
			if not cells.has(key):
				cells[key] = []
			cells[key].append([t, it[1], it[2]] if it.size() > 2 else [t, it[1]])
		for key in cells:
			var list: Array = cells[key]
			var centre := Vector3((key.x + 0.5) * cell, 0.0, (key.y + 0.5) * cell)
			centre.y = WorldData.height_at(centre.x, centre.z)
			var mm := MultiMesh.new()
			mm.transform_format = MultiMesh.TRANSFORM_3D
			mm.use_colors = tint
			mm.mesh = src.mesh
			mm.instance_count = list.size()
			for i in list.size():
				var t2: Transform3D = list[i][0]
				t2.origin -= centre
				mm.set_instance_transform(i, t2)
				if tint:
					mm.set_instance_color(i, list[i][1] if list[i][1] != null else Color.WHITE)
				if list[i].size() > 2 and tag_map != null:
					if not tag_map.has(list[i][2]):
						tag_map[list[i][2]] = []
					tag_map[list[i][2]].append([mm, i])
			var mmi := MultiMeshInstance3D.new()
			mmi.multimesh = mm
			mmi.position = centre
			if range_end > 0.0:
				mmi.visibility_range_end = range_end + cell * 0.75
				mmi.visibility_range_end_margin = 12.0
				mmi.visibility_range_fade_mode = GeometryInstance3D.VISIBILITY_RANGE_FADE_DISABLED
			if shadows.has(mesh_name) and not shadows[mesh_name]:
				mmi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
			parent.add_child(mmi)

func _build_town() -> void:
	town_root = spawn_export("res://assets/world/town", self)
	if town_root == null:
		return
	town_root.name = "Town"
	town_data = read_json("res://assets/world/town.json")
	var named: Dictionary = {}
	_index(town_root, named)
	for b in town_data.buildings:
		var id := int(b.id)
		buildings[id] = {"data": b, "node": named.get("b%d" % id), "interior": named.get("b%d_int" % id), "roof": named.get("b%d_roof" % id)}
	for d in town_data.doors:
		doors[int(d.id)] = {"data": d, "node": named.get("door%d" % int(d.id)), "box": WorldData.export_box.get(int(d.box)), "open": bool(d.open), "angle": 0.0, "hp": float(d.hp), "locked": bool(d.locked)}
	for w in town_data.windows:
		windows[int(w.id)] = {"data": w, "node": named.get("win%d" % int(w.id)), "box": WorldData.export_box.get(int(w.box)), "broken": false}
	containers = town_data.containers
	container_caps = read_json("res://data/containers.json")
	for id in doors:
		var d: Dictionary = doors[id]
		d["kind"] = "door"; d["id"] = id; d["target"] = (PI / 2.0 * 0.9) if d.open else 0.0; d["angle"] = d.target
		if d.box != null:
			d.box.ref = d; d.box.tag = "door"
			if not d.open:
				WorldData._raster_box(d.box, 30, d, false)
	for id in windows:
		var w: Dictionary = windows[id]
		w["kind"] = "window"; w["id"] = id
		if w.box != null:
			w.box.ref = w
			WorldData.mark_breakable(w.box, 45, w)      # the dead can come through a window
	for c in containers:
		c["kind_id"] = String(c.kind); c["kind"] = "container"
		c["pos_v"] = Vector3(c.pos[0], c.pos[1], c.pos[2]); c["half_v"] = Vector3(c.half[0], c.half[1], c.half[2])

# ------------------------------------------------------------------ things that change
func apply_door(d: Dictionary) -> void:
	var node: Node3D = d.node
	if node != null:
		node.rotation.y = (0.0 if String(d.data.axis) == "x" else -PI / 2.0) + float(d.angle) * float(d.data.dir)

func set_door(d: Dictionary, open: bool) -> void:
	d.open = open
	d.target = PI / 2.0 if open else 0.0
	if d.box != null:
		WorldData.set_solid(d.box, not open, 30)

func container_inv(c: Dictionary) -> Inventory:
	if not c.has("inv"):
		var info = container_caps.get(c.get("kind_id", ""), {})
		var inv := Inventory.new(float(info.get("cap", 30.0)) if info is Dictionary else 30.0)
		for s in c.get("stacks", []):
			var proto := {}
			for k in ["dur", "ammo", "age"]:
				if s.has(k): proto[k] = s[k]
			var keep := inv.capacity; inv.capacity = INF
			inv.add(String(s.id), int(s.n), proto)
			inv.capacity = keep
		c["inv"] = inv
	return c.inv

func veg_kind(i: int) -> String:
	return String(veg_data.variants[int(veg_inst[i * 6 + 5])].kind)

func veg_pos(i: int) -> Vector3:
	return Vector3(veg_inst[i * 6], veg_inst[i * 6 + 1], veg_inst[i * 6 + 2])

func veg_ore(i: int) -> String:
	var k := String(veg_data.kinds[i])
	return k.substr(2) if k.length() > 2 else ""

func kill_veg(i: int) -> void:
	if veg_dead.has(i):
		return
	veg_dead[i] = true
	for it in veg_map.get(i, []):
		var mm: MultiMesh = it[0]
		mm.set_instance_transform(int(it[1]), Transform3D(Basis.from_scale(Vector3.ONE * 0.0001), Vector3(0, -500, 0)))
	var c = veg_circle.get(i)
	if c != null:
		WorldData.remove_circle(c)

func drop_items(pos: Vector3, inv: Inventory, label := "Bag") -> Dictionary:
	for d in drops:
		if d.pos.distance_to(pos) < 1.2:
			for s in inv.stacks:
				d.inv.add_stack(s)
			return d
	var mi := MeshInstance3D.new()
	var sm := SphereMesh.new(); sm.radius = 0.22; sm.height = 0.3; sm.radial_segments = 10; sm.rings = 5
	mi.mesh = sm
	var mat := StandardMaterial3D.new(); mat.albedo_color = Color(0.2, 0.17, 0.13); mat.roughness = 0.95
	mi.material_override = mat
	var y := WorldData.ground_y(pos.x, pos.z, pos.y + 1.0)
	mi.position = Vector3(pos.x, y + 0.12, pos.z)
	add_child(mi)
	var d2 := {"kind": "drop", "label": label, "pos": Vector3(pos.x, y, pos.z), "inv": inv, "node": mi, "pos_v": Vector3(pos.x, y + 0.15, pos.z), "half_v": Vector3(0.3, 0.2, 0.3)}
	drops.append(d2)
	return d2

func remove_drop(d: Dictionary) -> void:
	if d.node != null:
		d.node.queue_free()
	drops.erase(d)

func _index(n: Node, out: Dictionary) -> void:
	out[String(n.name)] = n
	for c in n.get_children():
		_index(c, out)

func _build_props() -> void:
	var scene: PackedScene = load("res://assets/world/props.glb")
	if scene == null:
		return
	var node: Node3D = scene.instantiate(); node.name = "Props"; add_child(node)
	Mats.apply(node)
	var lib := node.get_node_or_null("__lib")
	if lib == null:
		return
	lib.visible = false
	var data: Dictionary = read_json("res://assets/world/props.json")
	var by_mesh: Dictionary = {}; var shadows: Dictionary = {}; var tints: Dictionary = {}
	for e in data.entries:
		var t := xform(e.m)
		var col = null if not e.has("c") or e.c == null else Color(e.c[0], e.c[1], e.c[2])
		for sub in data.templates[e.t]:
			var k := String(sub.mesh)
			if not by_mesh.has(k):
				by_mesh[k] = []; shadows[k] = bool(sub.shadow); tints[k] = bool(sub.tint)
			by_mesh[k].append([t, col])
	build_multimeshes(node, lib, by_mesh, 96.0, 170.0, shadows, tints)

func _build_veg() -> void:
	var scene: PackedScene = load("res://assets/world/veg.glb")
	if scene == null:
		return
	var node: Node3D = scene.instantiate(); node.name = "Vegetation"; add_child(node)
	Mats.apply(node)
	var lib := node.get_node_or_null("__lib")
	lib.visible = false
	veg_data = read_json("res://assets/world/veg.json")
	veg_inst = FileAccess.get_file_as_bytes("res://data/veg.bin").to_float32_array()
	# trees and bushes get the wind shader (their own copies of the meshes: bark is shared with other things)
	for variant in veg_data.variants:
		if String(variant.kind) in ["tree", "bush"]:
			for part in variant.parts:
				var mi := lib.get_node_or_null(String(part)) as MeshInstance3D
				if mi == null or mi.mesh == null or mi.has_meta("windy"):
					continue
				var dup: Mesh = mi.mesh.duplicate()
				for sfc in dup.get_surface_count():
					var cur := dup.surface_get_material(sfc)
					if cur != null:
						var fm := Mats.foliage(cur.resource_name)
						if fm != null:
							dup.surface_set_material(sfc, fm)
				mi.mesh = dup; mi.set_meta("windy", true)
	var groups := {"tree": {}, "bush": {}, "rock": {}, "log": {}}
	for i in int(veg_data.count):
		var o := i * 6
		var v := int(veg_inst[o + 5])
		var variant: Dictionary = veg_data.variants[v]
		var t := Transform3D(Basis(Vector3.UP, veg_inst[o + 4]).scaled(Vector3.ONE * veg_inst[o + 3]), Vector3(veg_inst[o], veg_inst[o + 1], veg_inst[o + 2]))
		var g: Dictionary = groups[String(variant.kind)]
		for part in variant.parts:
			if not g.has(part):
				g[part] = []
			g[part].append([t, null, i])
	build_multimeshes(node, lib, groups.tree, 80.0, 520.0, {}, {}, veg_map)
	build_multimeshes(node, lib, groups.bush, 80.0, 170.0, {}, {}, veg_map)
	build_multimeshes(node, lib, groups.rock, 80.0, 260.0, {}, {}, veg_map)
	build_multimeshes(node, lib, groups.log, 80.0, 170.0, {}, {}, veg_map)
	for c in WorldData._circles.values():
		if c.kind in ["tree", "bush", "rock", "log"]:
			veg_circle[int(c.ref)] = c

func _build_ores() -> void:
	var n := spawn_export("res://assets/world/ores", self, 96.0, 320.0)
	if n != null:
		n.name = "Ores"

## wind (0 calm .. 1 gale), wetness and snow reach every shader that cares
func set_weather(wind: float, wet: float, snow: float) -> void:
	if terrain_mat != null:
		terrain_mat.set_shader_parameter("wet", wet); terrain_mat.set_shader_parameter("snow", snow)
	if grass_mat != null:
		grass_mat.set_shader_parameter("wind", wind); grass_mat.set_shader_parameter("snow", snow)
	if water_mat != null:
		water_mat.set_shader_parameter("wind", wind)
	for m in Mats.foliage_mats:
		m.set_shader_parameter("wind", wind)
