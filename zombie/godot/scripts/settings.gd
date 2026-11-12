extends Node
## Settings (autoload): the player's options, kept in user://settings.json. Read with Settings.v("fov");
## change with Settings.set_value("fov", 80) — `changed(key)` fires and the file is saved.

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
}
const PATH := "user://settings.json"
var values: Dictionary = {}

func _ready() -> void:
	values = DEFAULTS.duplicate()
	var f := FileAccess.open(PATH, FileAccess.READ)
	if f != null:
		var o = JSON.parse_string(f.get_as_text())
		if o is Dictionary:
			for k in o:
				if DEFAULTS.has(k):
					values[k] = o[k]

func v(key: String) -> Variant:
	return values.get(key, DEFAULTS.get(key))

func set_value(key: String, value: Variant) -> void:
	if values.get(key) == value:
		return
	values[key] = value
	save()
	changed.emit(key)

func save() -> void:
	var f := FileAccess.open(PATH, FileAccess.WRITE)
	if f != null:
		f.store_string(JSON.stringify(values))
