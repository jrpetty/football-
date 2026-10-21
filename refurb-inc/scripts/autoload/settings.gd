extends Node
## Player settings (controls, camera, audio, graphics) + the input map.
## Input actions are registered here in code so they can be rebound and saved.

signal settings_changed

const PATH := "user://settings.cfg"

const DEFAULT_BINDINGS := {
	"move_forward": [KEY_W, KEY_UP],
	"move_back": [KEY_S, KEY_DOWN],
	"move_left": [KEY_A, KEY_LEFT],
	"move_right": [KEY_D, KEY_RIGHT],
	"jump": [KEY_SPACE],
	"sprint": [KEY_SHIFT],
	"crouch": [KEY_CTRL, KEY_C],
	"interact": [KEY_E],
	"move_fixture": [KEY_F],
	"rotate": [KEY_R],
	"drop": [KEY_Q],
	"fork_up": [KEY_R],          # forklift forks (R/F or the mouse wheel)
	"fork_down": [KEY_F],
	"horn": [KEY_H],
	"pause": [KEY_ESCAPE],
	"toggle_hud": [KEY_F1],
	"screenshot": [KEY_F12],
}
const MOUSE_BINDINGS := {
	"interact": MOUSE_BUTTON_LEFT,
	"drop": MOUSE_BUTTON_RIGHT,
}

var values := {
	"mouse_sensitivity": 0.12,
	"invert_y": false,
	"fov": 75.0,
	"head_bob": true,
	"head_bob_strength": 1.0,
	"master_volume": 0.9,
	"sfx_volume": 1.0,
	"music_volume": 0.5,
	"ambience_volume": 0.8,
	"graphics_preset": "high",   # low | medium | high | ultra
	"fullscreen": false,
	"vsync": true,
	"show_fps": true,
	"settings_version": 2,
	"player_name": "",
	"company_name": "Refurb Inc.",   # last business name used for a new game
	"char_shirt": "#3359a0",
	"char_trousers": "#292b33",
	"char_skin": "#dbb08f",
	"char_hair": "#33241a",
	"char_hivis": false,
	"char_cap": false,
	"char_height": 1.78,
	"char_bighead": false,
	"char_body": "m",
	"char_hairstyle": "short",
	"char_shoes": "#1a1a1c",
	"char_steamface": false,     # no photo uploaded → use my Steam profile picture as my face
}


func _ready() -> void:
	_register_inputs()
	load_settings()
	apply()


func _register_inputs() -> void:
	for action in DEFAULT_BINDINGS:
		if not InputMap.has_action(action):
			InputMap.add_action(action)
		for key in DEFAULT_BINDINGS[action]:
			var ev := InputEventKey.new()
			ev.physical_keycode = key
			InputMap.action_add_event(action, ev)
	for action in MOUSE_BINDINGS:
		var mb := InputEventMouseButton.new()
		mb.button_index = MOUSE_BINDINGS[action]
		InputMap.action_add_event(action, mb)


func get_value(key: String) -> Variant:
	return values.get(key)


func set_value(key: String, v: Variant) -> void:
	values[key] = v
	apply()
	save_settings()


func load_settings() -> void:
	var cfg := ConfigFile.new()
	if cfg.load(PATH) != OK:
		return
	var saved_version := int(cfg.get_value("settings", "settings_version", 1))
	for k in values.keys():
		if k == "settings_version":
			continue
		values[k] = cfg.get_value("settings", k, values[k])
	if saved_version < 2:
		values.show_fps = true      # v2: the FPS readout is on by default (F3 toggles)
	if str(values.player_name) != "":
		Net.local_name = values.player_name


func save_settings() -> void:
	var cfg := ConfigFile.new()
	for k in values:
		cfg.set_value("settings", k, values[k])
	cfg.save(PATH)


func apply() -> void:
	_set_bus("Master", float(values.master_volume))
	_set_bus("SFX", float(values.sfx_volume))
	_set_bus("Music", float(values.music_volume))
	_set_bus("Ambience", float(values.ambience_volume))
	if DisplayServer.get_name() != "headless":
		DisplayServer.window_set_mode(DisplayServer.WINDOW_MODE_FULLSCREEN if values.fullscreen else DisplayServer.WINDOW_MODE_WINDOWED)
		DisplayServer.window_set_vsync_mode(DisplayServer.VSYNC_ENABLED if values.vsync else DisplayServer.VSYNC_DISABLED)
	settings_changed.emit()


func _set_bus(bus_name: String, linear: float) -> void:
	var idx := AudioServer.get_bus_index(bus_name)
	if idx >= 0:
		AudioServer.set_bus_volume_db(idx, linear_to_db(maxf(linear, 0.0001)))


func apply_graphics(env: Environment, sun: DirectionalLight3D = null) -> void:
	## Called by levels on their WorldEnvironment. Presets trade quality for frame rate on modest PCs.
	if env == null:
		return
	var p: String = values.graphics_preset
	env.ssao_enabled = p != "low"
	env.ssil_enabled = p in ["high", "ultra"]
	env.sdfgi_enabled = false
	env.glow_enabled = p != "low"
	env.volumetric_fog_enabled = p in ["high", "ultra"]
	env.ssr_enabled = p == "ultra"
	var vp := get_viewport()
	vp.msaa_3d = Viewport.MSAA_DISABLED if p == "low" else (Viewport.MSAA_4X if p == "ultra" else Viewport.MSAA_2X)
	vp.screen_space_aa = Viewport.SCREEN_SPACE_AA_FXAA if p != "ultra" else Viewport.SCREEN_SPACE_AA_DISABLED
	vp.use_taa = p == "ultra"
	# Detailed hardware models drop to their lighter LODs sooner on the lower presets (pixels of error allowed).
	vp.mesh_lod_threshold = {"low": 4.0, "medium": 2.5, "high": 2.0, "ultra": 1.0}.get(p, 2.0)
	if sun:
		sun.shadow_enabled = true
		sun.directional_shadow_max_distance = 40.0 if p == "low" else 80.0
	RenderingServer.directional_soft_shadow_filter_set_quality(
		RenderingServer.SHADOW_QUALITY_SOFT_LOW if p in ["low", "medium"] else RenderingServer.SHADOW_QUALITY_SOFT_HIGH)
	RenderingServer.positional_soft_shadow_filter_set_quality(
		RenderingServer.SHADOW_QUALITY_SOFT_LOW if p in ["low", "medium"] else RenderingServer.SHADOW_QUALITY_SOFT_MEDIUM)
