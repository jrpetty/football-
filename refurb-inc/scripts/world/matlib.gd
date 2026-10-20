class_name MatLib
extends RefCounted
## Named architectural materials. Built from the texture sets + parameters below and saved as
## res://assets/materials/<key>.tres so every building shares them and they can be tweaked in the editor.

const DIR := "res://assets/materials/"

const DEFS := {
	# --- floors
	"concrete_dirty": {"set": "concrete_floor", "tile": 2.0, "tint": Color(0.95, 0.94, 0.92)},
	"concrete_yard": {"set": "polished_concrete", "tile": 4.0, "tint": Color(0.8, 0.8, 0.78), "rough": 1.7, "spec": 0.3},
	"concrete_stained": {"set": "concrete_floor", "tile": 3.0, "tint": Color(0.86, 0.86, 0.84), "spec": 0.3},
	"concrete_sealed": {"set": "polished_concrete", "tile": 2.5, "tint": Color(0.82, 0.82, 0.8), "rough": 1.4},
	"polished": {"set": "polished_concrete", "tile": 3.0, "tint": Color(0.92, 0.92, 0.9), "rough": 0.9},
	"epoxy_grey": {"set": "epoxy_floor", "tile": 3.0, "tint": Color(0.6, 0.6, 0.59), "rough": 1.3},
	"epoxy_light": {"set": "epoxy_floor", "tile": 3.0, "tint": Color(0.84, 0.86, 0.88)},
	"epoxy_blue": {"set": "epoxy_floor", "tile": 3.0, "tint": Color(0.3, 0.48, 0.72)},
	"epoxy_orange": {"set": "epoxy_floor", "tile": 3.0, "tint": Color(0.92, 0.55, 0.22)},
	"epoxy_purple": {"set": "epoxy_floor", "tile": 3.0, "tint": Color(0.52, 0.4, 0.75)},
	"epoxy_green": {"set": "epoxy_floor", "tile": 3.0, "tint": Color(0.3, 0.65, 0.42)},
	"grating": {"set": "metal_grating", "tile": 1.0, "metal": 0.6},
	# --- walls
	"block_cream": {"set": "blockwork_painted", "tile": 1.8, "tint": Color(0.93, 0.9, 0.83)},
	"block_grey": {"set": "blockwork_painted", "tile": 1.8, "tint": Color(0.72, 0.72, 0.71)},
	"block_white": {"set": "blockwork_painted", "tile": 1.8, "tint": Color(0.97, 0.97, 0.96)},
	"brick": {"set": "brick_red", "tile": 0.9, "spec": 0.3},
	"brick_dark": {"set": "brick_red", "tile": 0.9, "tint": Color(0.62, 0.56, 0.55), "spec": 0.3},
	"brick_buff": {"set": "brick_red", "tile": 0.9, "tint": Color(1.25, 1.15, 1.25), "spec": 0.3},
	"clad_goosewing": {"set": "cladding_profiled", "tile": 1.0, "tint": Color(0.6, 0.63, 0.65), "metal": 0.35},
	"clad_anthracite": {"set": "cladding_profiled", "tile": 1.0, "tint": Color(0.2, 0.21, 0.23), "metal": 0.35},
	"clad_white": {"set": "cladding_profiled", "tile": 1.0, "tint": Color(0.93, 0.94, 0.95), "metal": 0.3},
	"clad_silver": {"set": "cladding_profiled", "tile": 1.0, "tint": Color(0.74, 0.76, 0.78), "metal": 0.6},
	"clad_blue": {"set": "cladding_profiled", "tile": 1.0, "tint": Color(0.14, 0.28, 0.52), "metal": 0.35},
	"clad_green": {"set": "cladding_profiled", "tile": 1.0, "tint": Color(0.22, 0.36, 0.3), "metal": 0.35},
	"clad_amber": {"set": "cladding_profiled", "tile": 1.0, "tint": Color(0.96, 0.62, 0.14), "metal": 0.3},
	"panel_silver": {"set": "composite_panel", "tile": 2.0, "tint": Color(0.78, 0.8, 0.82), "metal": 0.55},
	"panel_anthracite": {"set": "composite_panel", "tile": 2.0, "tint": Color(0.17, 0.18, 0.2), "metal": 0.5},
	"panel_white": {"set": "composite_panel", "tile": 2.0, "tint": Color(0.94, 0.95, 0.96), "metal": 0.3},
	"panel_amber": {"set": "composite_panel", "tile": 2.0, "tint": Color(0.96, 0.6, 0.12), "metal": 0.3},
	"liner_white": {"set": "cladding_profiled", "tile": 1.0, "tint": Color(0.9, 0.91, 0.92), "metal": 0.1},
	"corrugated": {"set": "corrugated", "tile": 1.2},
	"corrugated_grey": {"set": "corrugated", "tile": 1.2, "tint": Color(0.7, 0.72, 0.72)},
	"roof_sheet": {"set": "cladding_profiled", "tile": 1.0, "tint": Color(0.42, 0.44, 0.45), "metal": 0.4},
	"roof_felt": {"color": Color(0.11, 0.11, 0.12), "rough": 0.9},
	"timber": {"set": "timber_cladding", "tile": 1.6},
	"plywood": {"set": "plywood", "tile": 1.0},
	"plaster_white": {"color": Color(0.93, 0.93, 0.91), "rough": 0.85},
	"paint_white": {"color": Color(0.95, 0.95, 0.95), "rough": 0.5},
	"paint_dark": {"color": Color(0.16, 0.17, 0.19), "rough": 0.5},
	"dado": {"set": "painted_metal", "tile": 0.8, "tint": Color(0.36, 0.38, 0.4)},
	# --- structure & metal
	"steel_blue": {"set": "painted_metal", "tile": 0.6, "tint": Color(0.17, 0.3, 0.5), "metal": 0.4, "rough": 0.9},
	"steel_grey": {"set": "painted_metal", "tile": 0.6, "tint": Color(0.45, 0.47, 0.5), "metal": 0.4, "rough": 0.9},
	"steel_white": {"set": "painted_metal", "tile": 0.6, "tint": Color(0.9, 0.9, 0.9), "metal": 0.3, "rough": 0.9},
	"steel_yellow": {"set": "painted_metal", "tile": 0.6, "tint": Color(0.95, 0.72, 0.08), "metal": 0.3, "rough": 0.9},
	"steel_green": {"set": "painted_metal", "tile": 0.6, "tint": Color(0.12, 0.32, 0.2), "metal": 0.35, "rough": 0.9},
	"steel_black": {"set": "painted_metal", "tile": 0.6, "tint": Color(0.1, 0.1, 0.11), "metal": 0.4, "rough": 0.9},
	"steel_orange": {"set": "painted_metal", "tile": 0.6, "tint": Color(0.9, 0.42, 0.1), "metal": 0.3, "rough": 0.9},
	"galv": {"color": Color(0.68, 0.7, 0.72), "metal": 0.75, "rough": 0.45},
	"alu": {"color": Color(0.8, 0.82, 0.84), "metal": 0.8, "rough": 0.3},
	"alu_dark": {"color": Color(0.17, 0.18, 0.19), "metal": 0.6, "rough": 0.35},
	"rubber": {"set": "rubber", "tile": 1.0},
	"tyre": {"color": Color(0.04, 0.04, 0.04), "rough": 0.85},
	# --- glass & light
	"glass": {"color": Color(0.55, 0.65, 0.72, 0.28), "rough": 0.03, "metal": 0.2, "glass": true},
	"glass_blue": {"color": Color(0.25, 0.4, 0.55, 0.55), "rough": 0.02, "metal": 0.35, "glass": true},
	"glass_dark": {"color": Color(0.08, 0.1, 0.13, 0.88), "rough": 0.02, "metal": 0.3, "glass": true},
	"rooflight": {"color": Color(0.92, 0.94, 0.95, 0.55), "rough": 0.4, "glass": true, "emit": Color(0.8, 0.85, 0.9), "emit_e": 0.6},
	"led_panel": {"color": Color(0.95, 0.97, 1.0), "emit": Color(0.95, 0.97, 1.0), "emit_e": 5.0},
	"led_warm": {"color": Color(1.0, 0.92, 0.8), "emit": Color(1.0, 0.9, 0.75), "emit_e": 4.0},
	"emit_green": {"color": Color(0.1, 0.8, 0.3), "emit": Color(0.1, 0.9, 0.35), "emit_e": 3.0},
	"emit_red": {"color": Color(0.9, 0.1, 0.08), "emit": Color(1.0, 0.1, 0.05), "emit_e": 3.0},
	"emit_amber": {"color": Color(1.0, 0.65, 0.1), "emit": Color(1.0, 0.6, 0.1), "emit_e": 3.0},
	"emit_brand": {"color": Color(1.0, 0.72, 0.25), "emit": Color(1.0, 0.68, 0.2), "emit_e": 2.2},
	# --- ground
	"tarmac": {"set": "tarmac", "tile": 3.0, "spec": 0.3},
	"tarmac_new": {"set": "tarmac", "tile": 3.0, "tint": Color(0.7, 0.7, 0.72), "spec": 0.3},
	"paving": {"set": "block_paving", "tile": 1.0, "spec": 0.3},
	"paving_light": {"set": "block_paving", "tile": 1.0, "tint": Color(1.25, 1.25, 1.25), "spec": 0.3},
	"gravel": {"set": "gravel", "tile": 1.5, "spec": 0.2},
	"grass": {"set": "grass", "tile": 2.5, "spec": 0.2},
	"kerb": {"set": "concrete_floor", "tile": 1.0, "tint": Color(0.78, 0.78, 0.76), "spec": 0.3},
	"line_white": {"color": Color(0.93, 0.93, 0.9), "rough": 0.6},
	"line_yellow": {"color": Color(0.97, 0.78, 0.1), "rough": 0.6},
	"line_blue": {"color": Color(0.15, 0.4, 0.8), "rough": 0.6},
	"line_green": {"color": Color(0.15, 0.6, 0.3), "rough": 0.6},
	"line_red": {"color": Color(0.8, 0.15, 0.12), "rough": 0.6},
	"chainlink": {"color": Color(0.55, 0.58, 0.6, 0.35), "rough": 0.5, "metal": 0.6, "glass": true},
	"water": {"color": Color(0.08, 0.16, 0.2, 0.85), "rough": 0.02, "metal": 0.2, "glass": true},
	"soil": {"color": Color(0.22, 0.17, 0.12), "rough": 0.95},
	"hills": {"set": "grass", "tile": 12.0, "tint": Color(0.75, 0.85, 0.7), "spec": 0.1},
	# --- nature
	"bark": {"color": Color(0.27, 0.2, 0.14), "rough": 0.95},
	"foliage": {"color": Color(0.2, 0.36, 0.13), "rough": 0.92},
	"foliage_dark": {"color": Color(0.12, 0.25, 0.1), "rough": 0.92},
	"foliage_light": {"color": Color(0.36, 0.5, 0.18), "rough": 0.92},
	"hedge": {"color": Color(0.14, 0.28, 0.11), "rough": 0.95},
	# --- vehicles & misc
	"car_red": {"color": Color(0.6, 0.06, 0.06), "metal": 0.6, "rough": 0.25},
	"car_blue": {"color": Color(0.08, 0.18, 0.45), "metal": 0.6, "rough": 0.25},
	"car_silver": {"color": Color(0.7, 0.72, 0.74), "metal": 0.75, "rough": 0.25},
	"car_black": {"color": Color(0.03, 0.03, 0.04), "metal": 0.6, "rough": 0.2},
	"car_white": {"color": Color(0.92, 0.92, 0.92), "metal": 0.3, "rough": 0.25},
	"car_grey": {"color": Color(0.32, 0.34, 0.36), "metal": 0.6, "rough": 0.25},
	"solar": {"color": Color(0.05, 0.08, 0.18), "metal": 0.4, "rough": 0.08},
	"bin_green": {"color": Color(0.12, 0.35, 0.18), "rough": 0.6},
	"bin_blue": {"color": Color(0.1, 0.25, 0.6), "rough": 0.6},
	"bin_black": {"color": Color(0.06, 0.06, 0.06), "rough": 0.6},
	"cardboard": {"set": "cardboard", "tile": 0.5},
	"pallet_wood": {"set": "plywood", "tile": 0.5, "tint": Color(0.85, 0.75, 0.6)},
	"brand": {"color": Color(0.96, 0.66, 0.16), "rough": 0.45},
	"brand_dark": {"color": Color(0.11, 0.13, 0.17), "rough": 0.45},
}

static var _cache: Dictionary = {}


static func mat(key: String) -> Material:
	if _cache.has(key):
		return _cache[key]
	var path := DIR + key + ".tres"
	var m: Material = null
	if ResourceLoader.exists(path):
		m = load(path)
	else:
		m = build(key)
	_cache[key] = m
	return m


static func build(key: String) -> StandardMaterial3D:
	var d: Dictionary = DEFS.get(key, {"color": Color.MAGENTA})
	var m: StandardMaterial3D
	if d.has("set"):
		m = Mats.surface(d.set, float(d.get("tile", 1.0)), d.get("tint", Color.WHITE), float(d.get("rough", 1.0))).duplicate()
	else:
		m = StandardMaterial3D.new()
		m.albedo_color = d.get("color", Color.WHITE)
		m.roughness = float(d.get("rough", 0.6))
	if d.has("metal"):
		m.metallic = float(d.metal)
	if d.has("spec"):
		m.metallic_specular = float(d.spec)
	if d.get("glass", false):
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		m.cull_mode = BaseMaterial3D.CULL_DISABLED
	if d.has("emit"):
		m.emission_enabled = true
		m.emission = d.emit
		m.emission_energy_multiplier = float(d.get("emit_e", 2.0))
	m.resource_name = key
	return m


static func save_all() -> void:
	DirAccess.make_dir_recursive_absolute(DIR)
	for key in DEFS:
		var m := build(key)
		ResourceSaver.save(m, DIR + key + ".tres")
		_cache[key] = load(DIR + key + ".tres")
