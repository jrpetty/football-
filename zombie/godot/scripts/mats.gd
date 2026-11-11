class_name Mats
## Material library. Meshes come out of the exporter carrying only a material *name*; the look of each name
## (colour, maps, roughness…) is in assets/materials.json, and is turned into one shared Godot material here.

static var _defs: Dictionary = {}
static var _cache: Dictionary = {}
static var _tex: Dictionary = {}

static func defs() -> Dictionary:
	if _defs.is_empty():
		var f := FileAccess.open("res://assets/materials.json", FileAccess.READ)
		if f != null:
			_defs = JSON.parse_string(f.get_as_text()).materials
	return _defs

static func tex(file) -> Texture2D:
	if file == null or String(file) == "":
		return null
	if not _tex.has(file):
		_tex[file] = load("res://assets/tex/" + String(file))
	return _tex[file]

## the Godot material for an exported material id; `tint` makes a variant that takes per-instance colour
static func get_mat(id: String, tint := false) -> Material:
	var key := id + ("#t" if tint else "")
	if _cache.has(key):
		return _cache[key]
	var d = defs().get(id)
	if d == null:
		return null
	var m := StandardMaterial3D.new()
	m.resource_name = id
	m.albedo_color = Color(String(d.color))
	m.roughness = float(d.roughness)
	m.metallic = float(d.metalness)
	var scale := Vector3(float(d.repeat[0]), float(d.repeat[1]), 1.0)
	m.uv1_scale = scale
	if d.map != null:
		m.albedo_texture = tex(d.map)
	if d.normalMap != null:
		m.normal_enabled = true
		m.normal_texture = tex(d.normalMap)
		m.normal_scale = float(d.normalScale)
	if d.roughnessMap != null:
		m.roughness_texture = tex(d.roughnessMap)
		m.roughness_texture_channel = BaseMaterial3D.TEXTURE_CHANNEL_GREEN
		m.roughness = 1.0
	if d.metalnessMap != null:
		m.metallic_texture = tex(d.metalnessMap)
		m.metallic_texture_channel = BaseMaterial3D.TEXTURE_CHANNEL_BLUE
		m.metallic = 1.0
	if d.aoMap != null:
		m.ao_enabled = true
		m.ao_texture = tex(d.aoMap)
		m.ao_texture_channel = BaseMaterial3D.TEXTURE_CHANNEL_RED
		m.ao_light_affect = 0.25
	var em := Color(String(d.emissive))
	if (em.r + em.g + em.b) > 0.001 and float(d.emissiveIntensity) > 0.0:
		m.emission_enabled = true
		m.emission = em
		m.emission_energy_multiplier = float(d.emissiveIntensity)
		if d.emissiveMap != null:
			m.emission_texture = tex(d.emissiveMap)
	if float(d.alphaTest) > 0.0:
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA_SCISSOR
		m.alpha_scissor_threshold = float(d.alphaTest)
	elif bool(d.transparent):
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		m.albedo_color.a = float(d.opacity)
	if int(d.side) == 2:
		m.cull_mode = BaseMaterial3D.CULL_DISABLED
	elif int(d.side) == 1:
		m.cull_mode = BaseMaterial3D.CULL_FRONT
	if bool(d.vertexColors) or tint:
		m.vertex_color_use_as_albedo = true
	if float(d.clearcoat) > 0.0:
		m.clearcoat_enabled = true
		m.clearcoat = float(d.clearcoat)
		m.clearcoat_roughness = 0.45
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS_ANISOTROPIC
	_cache[key] = m
	return m

## swap the placeholder materials on every mesh under `root` for the real ones (by name)
static func apply(root: Node) -> void:
	if root is MeshInstance3D and root.mesh != null:
		fix_mesh(root.mesh)
	for c in root.get_children():
		apply(c)

static var _fixed: Dictionary = {}
static func fix_mesh(mesh: Mesh, tint := false) -> void:
	if _fixed.has(mesh):
		return
	_fixed[mesh] = true
	for s in mesh.get_surface_count():
		var cur := mesh.surface_get_material(s)
		if cur == null:
			continue
		var real := get_mat(cur.resource_name, tint)
		if real != null:
			mesh.surface_set_material(s, real)
