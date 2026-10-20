class_name Mats
extends RefCounted
## Shared PBR material library. Surface sets load from assets/textures/surfaces/<name>/{albedo,normal,roughness}.png,
## mapped with world-space triplanar so placeholder boxes of any size never stretch. Swap the PNGs to upgrade the look.

const SURF_DIR := "res://assets/textures/surfaces/"

static var _cache: Dictionary = {}


static func surface(set_name: String, tile_m: float = 1.0, tint: Color = Color.WHITE, rough_mult: float = 1.0) -> StandardMaterial3D:
	var key := "s|%s|%f|%s|%f" % [set_name, tile_m, tint.to_html(), rough_mult]
	if _cache.has(key):
		return _cache[key]
	var m := StandardMaterial3D.new()
	var base := SURF_DIR + set_name + "/"
	if ResourceLoader.exists(base + "albedo.png"):
		m.albedo_texture = load(base + "albedo.png")
	if ResourceLoader.exists(base + "normal.png"):
		m.normal_enabled = true
		m.normal_texture = load(base + "normal.png")
		m.normal_scale = 0.8
	if ResourceLoader.exists(base + "roughness.png"):
		m.roughness_texture = load(base + "roughness.png")
		m.roughness_texture_channel = BaseMaterial3D.TEXTURE_CHANNEL_RED
	m.roughness = rough_mult
	m.albedo_color = tint
	m.uv1_triplanar = true
	m.uv1_world_triplanar = true
	m.uv1_triplanar_sharpness = 4.0
	m.uv1_scale = Vector3.ONE / maxf(tile_m, 0.01)
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS_ANISOTROPIC
	_cache[key] = m
	return m


static func color(c: Color, roughness: float = 0.6, metallic: float = 0.0) -> StandardMaterial3D:
	var key := "c|%s|%f|%f" % [c.to_html(), roughness, metallic]
	if _cache.has(key):
		return _cache[key]
	var m := StandardMaterial3D.new()
	m.albedo_color = c
	m.roughness = roughness
	m.metallic = metallic
	if c.a < 1.0:
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	_cache[key] = m
	return m


static func painted_metal(c: Color) -> StandardMaterial3D:
	var key := "pm|%s" % c.to_html()
	if _cache.has(key):
		return _cache[key]
	var m: StandardMaterial3D = surface("painted_metal", 0.6).duplicate()
	m.albedo_color = c
	m.metallic = 0.35
	m.roughness = 0.9
	_cache[key] = m
	return m


static func emissive(c: Color, energy: float = 4.0) -> StandardMaterial3D:
	var key := "e|%s|%f" % [c.to_html(), energy]
	if _cache.has(key):
		return _cache[key]
	var m := StandardMaterial3D.new()
	m.albedo_color = c
	m.emission_enabled = true
	m.emission = c
	m.emission_energy_multiplier = energy
	_cache[key] = m
	return m


static func glass(tint: Color = Color(0.7, 0.8, 0.85, 0.25)) -> StandardMaterial3D:
	var key := "g|%s" % tint.to_html()
	if _cache.has(key):
		return _cache[key]
	var m := StandardMaterial3D.new()
	m.albedo_color = tint
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.roughness = 0.05
	m.metallic = 0.2
	_cache[key] = m
	return m


static func screen_off() -> StandardMaterial3D:
	return color(Color(0.03, 0.035, 0.04), 0.15, 0.1)


static func screen_texture(tex: Texture2D, energy: float = 1.2) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.albedo_texture = tex
	m.emission_enabled = true
	m.emission_texture = tex
	m.emission = Color.WHITE
	m.emission_energy_multiplier = energy
	return m
