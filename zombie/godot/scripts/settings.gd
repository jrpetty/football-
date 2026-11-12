extends Node
## Settings (autoload): the player's options, kept in user://settings.json. Read with Settings.v("fov");
## change with Settings.set_value("fov", 80) — `changed(key)` fires and the file is saved.
## What the window and the renderer can take straight away (window mode, v-sync, render scale, master
## volume) is applied here; everything else is read by the system that owns it.

signal changed(key: String)

const DEFAULTS := {
	"quality": "high",          # low | medium | high | ultra
	"render_scale": 1.0,
	"fov": 75.0,
	"mouse_sens": 1.0,
	"invert_y": false,
	"master_vol": 0.9,
	"music_vol": 0.6,
	"sfx_vol": 1.0,
	"shadows": true,
	"gi": true,                 # global illumination (Forward+ only)
	"volumetric_fog": true,
	"grass": 1.0,               # density multiplier
	"vsync": true,
	"fullscreen": false,
	"day_len": 24.0,            # real minutes per game day
	"head_bob": true,
	"minimap": true,            # the small map in the corner of the HUD
	"minimap_rotate": true,     # it turns with you; off = north stays up
}
## what a graphics preset sets besides "quality" itself
const PRESETS := {
	"low": {"render_scale": 0.7, "shadows": false, "gi": false, "volumetric_fog": false, "grass": 0.4},
	"medium": {"render_scale": 0.85, "shadows": true, "gi": false, "volumetric_fog": false, "grass": 0.7},
	"high": {"render_scale": 1.0, "shadows": true, "gi": true, "volumetric_fog": true, "grass": 1.0},
	"ultra": {"render_scale": 1.0, "shadows": true, "gi": true, "volumetric_fog": true, "grass": 1.5},
}
const PATH := "user://settings.json"
var values: Dictionary = {}
var _dirty := false
var _save_in := 0.0

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	values = DEFAULTS.duplicate()
	var f := FileAccess.open(PATH, FileAccess.READ)
	if f != null:
		var o = JSON.parse_string(f.get_as_text())
		if o is Dictionary:
			for k in o:
				if DEFAULTS.has(k) and _same_kind(o[k], DEFAULTS[k]):
					values[k] = o[k]
	set_process(false)
	apply_all.call_deferred()

static func _same_kind(a: Variant, b: Variant) -> bool:
	if b is bool:
		return a is bool
	if b is float or b is int:
		return a is float or a is int
	return typeof(a) == typeof(b)

func v(key: String) -> Variant:
	return values.get(key, DEFAULTS.get(key))

func set_value(key: String, value: Variant) -> void:
	if values.get(key) == value:
		return
	values[key] = value
	_apply(key)
	_dirty = true; _save_in = 0.4      # a dragged slider writes the file once, not sixty times a second
	set_process(true)
	changed.emit(key)

func _process(dt: float) -> void:
	_save_in -= dt
	if _save_in <= 0.0:
		save()

func _notification(what: int) -> void:
	if what == NOTIFICATION_WM_CLOSE_REQUEST or what == NOTIFICATION_PREDELETE:
		if _dirty:
			save()

func save() -> void:
	_dirty = false
	set_process(false)
	var f := FileAccess.open(PATH, FileAccess.WRITE)
	if f != null:
		f.store_string(JSON.stringify(values))

## sets the quality level and everything that level implies
func apply_preset(name: String) -> void:
	if not PRESETS.has(name):
		return
	set_value("quality", name)
	var p: Dictionary = PRESETS[name]
	for k in p:
		set_value(k, p[k])

## true while the individual graphics options are still what the chosen preset set them to
func matches_preset() -> bool:
	var p: Dictionary = PRESETS.get(String(v("quality")), {})
	for k in p:
		if p[k] is float:
			if absf(float(v(k)) - float(p[k])) > 0.001: return false
		elif v(k) != p[k]:
			return false
	return true

func reset(keys: Array = []) -> void:
	for k in (DEFAULTS.keys() if keys.is_empty() else keys):
		if DEFAULTS.has(k):
			set_value(k, DEFAULTS[k])

# ------------------------------------------------------------------ what can be applied from here
func apply_all() -> void:
	for k in ["fullscreen", "vsync", "render_scale", "master_vol"]:
		_apply(k, true)

func _apply(key: String, boot := false) -> void:
	var headless := DisplayServer.get_name() == "headless"
	match key:
		"fullscreen":
			if headless: return
			var want: bool = bool(v(key))
			var is_full := DisplayServer.window_get_mode() in [DisplayServer.WINDOW_MODE_FULLSCREEN, DisplayServer.WINDOW_MODE_EXCLUSIVE_FULLSCREEN]
			if want != is_full and (want or not boot):
				DisplayServer.window_set_mode(DisplayServer.WINDOW_MODE_FULLSCREEN if want else DisplayServer.WINDOW_MODE_WINDOWED)
		"vsync":
			if headless or (boot and bool(v(key))): return
			DisplayServer.window_set_vsync_mode(DisplayServer.VSYNC_ENABLED if bool(v(key)) else DisplayServer.VSYNC_DISABLED)
		"render_scale":
			if is_inside_tree():
				get_tree().root.scaling_3d_scale = clampf(float(v(key)), 0.5, 1.0)
		"master_vol":
			var i := AudioServer.get_bus_index("Master")
			if i >= 0:
				var lin := clampf(float(v(key)), 0.0, 1.5)
				AudioServer.set_bus_mute(i, lin <= 0.001)
				AudioServer.set_bus_volume_db(i, linear_to_db(maxf(lin, 0.0001)))
