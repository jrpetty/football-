extends Node
## Settings (autoload): the player's options and key bindings, kept in user://settings.json.
##   Settings.v("fov")                      read an option
##   Settings.set_value("fov", 80)          change one — `changed(key)` fires and the file is saved
##   Settings.key_down("forward")           is the key (or mouse button) bound to an action held?
##   Settings.is_action(event, "interact")  does this key / mouse-button event belong to an action?
##   Settings.key_name("interact")          what to print on a key cap: "E", "LMB", "Space"…
## What the window, the engine and the sound mixer can take straight away (window mode and size, v-sync, frame
## cap, master volume, output device, mute when unfocused) is applied here; everything else is read by the
## system that owns it.

signal changed(key: String)
signal bindings_changed

const DEFAULTS := {
	# game
	"difficulty": "normal",     # easy | normal | hard — sets the three dials below and the length of a day
	"zombie_damage": 1.0,       # how hard the dead hit you
	"needs_rate": 1.0,          # how fast hunger and thirst run down
	"zombie_count": 1.0,        # how many are in the valley when a game starts
	"day_len": 24.0,            # real minutes per game day
	"autosave": true,
	"autosave_hours": 3.0,      # game hours between autosaves
	"tips": true,               # explanations beside new status icons, the objective's description
	"temp_unit": "c",           # c | f
	# interface
	"hud_scale": 1.0,
	"hud_opacity": 1.0,
	"show_objective": true,
	"minimap": true,            # the small map in the corner of the HUD
	"minimap_rotate": true,     # it turns with you; off = north stays up
	"show_compass": true,
	"crosshair": "full",        # full | dot | off
	"crosshair_color": "white", # white | amber | green | cyan | pink
	"hit_markers": true,
	# display and graphics
	"window_mode": "windowed",  # windowed | borderless | fullscreen
	"window_size": "1600x900",  # the window's size when windowed
	"vsync": true,
	"fps_cap": 0.0,             # 0 = no cap
	"quality": "high",          # low | medium | high | ultra — sets the options from here to "aniso"
	"render_scale": 1.0,
	"upscaler": "bilinear",     # bilinear | fsr | fsr2
	"aa": "msaa2",              # off | fxaa | msaa2 | msaa4 | taa
	"shadow_quality": 3.0,      # 0 off … 4 ultra
	"ssao": true,
	"gi": false,                # global illumination (Forward+ only)
	"volumetric_fog": true,     # (Forward+ only)
	"bloom": true,
	"grass": 1.0,               # share of the grass that is drawn
	"veg_dist": 1.0,            # how far trees, bushes and clutter are drawn
	"view_dist": 1.0,
	"aniso": 8.0,               # anisotropic texture filtering: 1 (off), 2, 4, 8, 16
	"fov": 75.0,
	"brightness": 1.0,
	"film_grain": 0.5,
	"vignette": 0.5,
	# sound
	"master_vol": 0.9,
	"music_vol": 0.6,
	"sfx_vol": 1.0,
	"ambience_vol": 1.0,
	"ui_vol": 1.0,
	"mute_unfocused": true,
	"audio_device": "Default",
	# controls
	"mouse_sens": 1.0,
	"ads_sens": 0.7,            # share of the mouse sensitivity kept while aiming down sights
	"invert_y": false,
	"toggle_crouch": true,
	"toggle_sprint": false,
	"toggle_aim": false,
	# comfort and accessibility
	"head_bob": true,
	"cam_shake": 1.0,
	"center_dot": false,        # a steady dot in the middle of the screen at all times
	"reduce_flash": false,      # lightning, muzzle flash, the red flash when struck
	"colorblind": false,        # status colours that do not lean on red against green
}
## what a graphics preset sets besides "quality" itself
const PRESETS := {
	"low": {"render_scale": 0.7, "aa": "fxaa", "shadow_quality": 1.0, "ssao": false, "gi": false, "volumetric_fog": false, "bloom": false, "grass": 0.3, "veg_dist": 0.55, "view_dist": 0.6, "aniso": 2.0},
	"medium": {"render_scale": 0.85, "aa": "msaa2", "shadow_quality": 2.0, "ssao": true, "gi": false, "volumetric_fog": false, "bloom": true, "grass": 0.6, "veg_dist": 0.8, "view_dist": 0.8, "aniso": 4.0},
	"high": {"render_scale": 1.0, "aa": "msaa2", "shadow_quality": 3.0, "ssao": true, "gi": false, "volumetric_fog": true, "bloom": true, "grass": 1.0, "veg_dist": 1.0, "view_dist": 1.0, "aniso": 8.0},
	"ultra": {"render_scale": 1.0, "aa": "msaa4", "shadow_quality": 4.0, "ssao": true, "gi": true, "volumetric_fog": true, "bloom": true, "grass": 1.0, "veg_dist": 1.0, "view_dist": 1.0, "aniso": 16.0},
}
const DIFFICULTY := {
	"easy": {"zombie_damage": 0.6, "needs_rate": 0.7, "zombie_count": 0.7, "day_len": 36.0},
	"normal": {"zombie_damage": 1.0, "needs_rate": 1.0, "zombie_count": 1.0, "day_len": 24.0},
	"hard": {"zombie_damage": 1.5, "needs_rate": 1.35, "zombie_count": 1.3, "day_len": 24.0},
}
const GROUPS := {"quality": PRESETS, "difficulty": DIFFICULTY}
## which options each settings page owns (its "reset to defaults" restores exactly these)
const PAGES := {
	"game": ["difficulty", "zombie_damage", "needs_rate", "zombie_count", "day_len", "autosave", "autosave_hours", "tips", "temp_unit"],
	"interface": ["hud_scale", "hud_opacity", "show_objective", "minimap", "minimap_rotate", "show_compass", "crosshair", "crosshair_color", "hit_markers"],
	"display": ["window_mode", "window_size", "vsync", "fps_cap", "quality", "render_scale", "upscaler", "aa", "shadow_quality", "ssao", "gi", "volumetric_fog", "bloom", "grass", "veg_dist", "view_dist", "aniso", "fov", "brightness", "film_grain", "vignette"],
	"audio": ["master_vol", "music_vol", "sfx_vol", "ambience_vol", "ui_vol", "mute_unfocused", "audio_device"],
	"controls": ["mouse_sens", "ads_sens", "invert_y", "toggle_crouch", "toggle_sprint", "toggle_aim"],
	"comfort": ["head_bob", "cam_shake", "center_dot", "reduce_flash", "colorblind"],
}

## Every action a key can be bound to: [id, what it is called, group, where it applies, first key, second key].
## A binding is a physical key code, or a mouse button as a negative number (-1 left, -2 right, -3 middle,
## -8 / -9 the side buttons); 0 is "nothing". Two actions may share a key when they never apply at once:
## "build" actions only while placing, "loot" only with a container open; "play" covers the rest of play, and
## "all" applies everywhere.
const ACTIONS := [
	["forward", "Move forward", "Movement", "all", KEY_W, 0],
	["back", "Move back", "Movement", "all", KEY_S, 0],
	["left", "Move left", "Movement", "all", KEY_A, 0],
	["right", "Move right", "Movement", "all", KEY_D, 0],
	["sprint", "Sprint", "Movement", "all", KEY_SHIFT, 0],
	["crouch", "Crouch / sneak", "Movement", "all", KEY_C, KEY_CTRL],
	["jump", "Jump", "Movement", "all", KEY_SPACE, 0],
	["attack", "Attack / fire / place", "Hands", "all", -1, 0],
	["aim", "Aim a gun / shove", "Hands", "all", -2, 0],
	["reload", "Reload", "Hands", "play", KEY_R, 0],
	["interact", "Interact, search, open", "Hands", "all", KEY_E, 0],
	["flashlight", "Flashlight", "Hands", "all", KEY_F, 0],
	["bandage", "Bandage the worst wound", "Hands", "all", KEY_Q, 0],
	["inventory", "Inventory", "Windows", "all", KEY_TAB, KEY_I],
	["health", "Health and skills", "Windows", "all", KEY_H, 0],
	["map", "Map", "Windows", "all", KEY_M, 0],
	["craft", "Crafting", "Windows", "all", KEY_N, 0],
	["build", "Build menu", "Windows", "all", KEY_B, 0],
	["pause", "Pause", "Windows", "all", KEY_ESCAPE, 0],
	["save", "Save", "Windows", "all", KEY_F5, 0],
	["rotate", "Rotate the piece", "Building", "build", KEY_R, 0],
	["dismantle", "Dismantle mode", "Building", "build", KEY_X, 0],
	["take_all", "Take everything", "Searching", "loot", KEY_R, 0],
	["hotbar1", "Hotbar slot 1", "Hotbar", "all", KEY_1, 0],
	["hotbar2", "Hotbar slot 2", "Hotbar", "all", KEY_2, 0],
	["hotbar3", "Hotbar slot 3", "Hotbar", "all", KEY_3, 0],
	["hotbar4", "Hotbar slot 4", "Hotbar", "all", KEY_4, 0],
	["hotbar5", "Hotbar slot 5", "Hotbar", "all", KEY_5, 0],
	["hotbar6", "Hotbar slot 6", "Hotbar", "all", KEY_6, 0],
	["hotbar7", "Hotbar slot 7", "Hotbar", "all", KEY_7, 0],
	["hotbar8", "Hotbar slot 8", "Hotbar", "all", KEY_8, 0],
]
const MOUSE_NAMES := {1: "LMB", 2: "RMB", 3: "MMB", 4: "Wheel up", 5: "Wheel down", 8: "Mouse 4", 9: "Mouse 5"}
const KEY_SHORT := {"Escape": "Esc", "Control": "Ctrl", "Delete": "Del", "Insert": "Ins", "PageUp": "PgUp", "PageDown": "PgDn", "QuoteLeft": "`", "BackSlash": "\\", "BracketLeft": "[", "BracketRight": "]", "Semicolon": ";", "Apostrophe": "'", "Comma": ",", "Period": ".", "Slash": "/", "Minus": "-", "Equal": "=", "CapsLock": "Caps", "Backspace": "Bksp"}
const WINDOW_SIZES := ["1280x720", "1600x900", "1920x1080", "2560x1440"]
const PATH := "user://settings.json"

var values: Dictionary = {}
var binds: Dictionary = {}          # action -> [code, code]
var _info: Dictionary = {}          # action -> its ACTIONS row
var _latched := {"sprint": false, "aim": false}
var _dirty := false
var _save_in := 0.0
var _focused := true
var persist := true                 # tests switch this off so their changes never reach the player's file

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	values = DEFAULTS.duplicate()
	for a in ACTIONS:
		_info[a[0]] = a
		binds[a[0]] = [int(a[4]), int(a[5])]
	var f := FileAccess.open(PATH, FileAccess.READ)
	if f != null:
		var o = JSON.parse_string(f.get_as_text())
		if o is Dictionary:
			for k in o:
				if DEFAULTS.has(k) and _same_kind(o[k], DEFAULTS[k]):
					values[k] = o[k]
			# options that changed shape since earlier builds
			if o.get("fullscreen") == true and not o.has("window_mode"): values["window_mode"] = "fullscreen"
			if o.get("shadows") == false and not o.has("shadow_quality"): values["shadow_quality"] = 0.0
			if not o.has("shadow_quality"): values["gi"] = DEFAULTS["gi"]      # it used to need the top preset as well
			var b = o.get("binds")
			if b is Dictionary:
				for k in b:
					if binds.has(k) and b[k] is Array and b[k].size() == 2:
						binds[k] = [int(b[k][0]), int(b[k][1])]
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
	_touch()
	changed.emit(key)

func _touch() -> void:
	_dirty = true; _save_in = 0.4      # a dragged slider writes the file once, not sixty times a second
	set_process(true)

func _process(dt: float) -> void:
	_save_in -= dt
	if _save_in <= 0.0:
		save()

func _notification(what: int) -> void:
	match what:
		NOTIFICATION_WM_CLOSE_REQUEST, NOTIFICATION_PREDELETE:
			if _dirty:
				save()
		NOTIFICATION_APPLICATION_FOCUS_OUT:
			_focused = false; _apply("master_vol")
		NOTIFICATION_APPLICATION_FOCUS_IN:
			_focused = true; _apply("master_vol")

func save() -> void:
	_dirty = false
	set_process(false)
	if not persist:
		return
	var out := values.duplicate()
	out["binds"] = binds
	var f := FileAccess.open(PATH, FileAccess.WRITE)
	if f != null:
		f.store_string(JSON.stringify(out))

# ------------------------------------------------------------------ presets and resets
## sets a preset ("quality": low…ultra, "difficulty": easy…hard) and everything it implies
func apply_preset(name: String, group := "quality") -> void:
	var table: Dictionary = GROUPS.get(group, {})
	if not table.has(name):
		return
	set_value(group, name)
	var p: Dictionary = table[name]
	for k in p:
		set_value(k, p[k])

## true while the individual options are still what the chosen preset set them to
func matches_preset(group := "quality") -> bool:
	var p: Dictionary = GROUPS.get(group, {}).get(String(v(group)), {})
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

func reset_page(page: String) -> void:
	reset(PAGES.get(page, []))
	if page == "controls":
		reset_bindings()

# ------------------------------------------------------------------ key bindings
static func event_code(ev: InputEvent) -> int:
	if ev is InputEventKey:
		return int(ev.physical_keycode) if ev.physical_keycode != 0 else int(ev.keycode)
	if ev is InputEventMouseButton:
		return -int(ev.button_index)
	return 0

static func code_name(code: int) -> String:
	if code == 0:
		return ""
	if code < 0:
		return String(MOUSE_NAMES.get(-code, "Mouse %d" % -code))
	var s := OS.get_keycode_string(code)
	return String(KEY_SHORT.get(s, s))

func binding(action: String) -> Array:
	return binds.get(action, [0, 0])

## is this key or mouse-button event one of the action's bindings? (Check `pressed` yourself.)
func is_action(ev: InputEvent, action: String) -> bool:
	var b = binds.get(action)
	if b == null:
		return false
	var code := 0
	if ev is InputEventKey:
		code = int(ev.physical_keycode) if ev.physical_keycode != 0 else int(ev.keycode)
	elif ev is InputEventMouseButton:
		code = -int(ev.button_index)
	return code != 0 and (code == b[0] or code == b[1])

static func _code_down(code: int) -> bool:
	if code > 0:
		return Input.is_physical_key_pressed(code)
	if code < 0 and code > -4 or code < -7:
		return Input.is_mouse_button_pressed(-code)
	return false

func _held(action: String) -> bool:
	var b = binds.get(action)
	return b != null and (_code_down(b[0]) or _code_down(b[1]))

## is the action's key (or mouse button) down right now? Sprint and aim follow their "toggle" options: with
## the option on, one press switches them on and the next switches them off.
func key_down(action: String) -> bool:
	if action == "sprint" and values.get("toggle_sprint", false):
		if _latched.sprint and not _held("forward"): _latched.sprint = false
		return _latched.sprint
	if action == "aim" and values.get("toggle_aim", false):
		if _latched.aim and Input.mouse_mode != Input.MOUSE_MODE_CAPTURED: _latched.aim = false
		return _latched.aim
	return _held(action)

func _input(ev: InputEvent) -> void:
	if not (ev is InputEventKey or ev is InputEventMouseButton) or not ev.pressed or ev.is_echo():
		return
	if values.get("toggle_sprint", false) and is_action(ev, "sprint"):
		_latched.sprint = not _latched.sprint
	if values.get("toggle_aim", false) and is_action(ev, "aim") and Input.mouse_mode == Input.MOUSE_MODE_CAPTURED:
		_latched.aim = not _latched.aim

## the name to print on a key cap for an action: its first binding, or the second if the first is empty
func key_name(action: String) -> String:
	var b: Array = binds.get(action, [0, 0])
	return code_name(int(b[0]) if int(b[0]) != 0 else int(b[1]))

## 0–7 if the event is one of the hotbar keys, else -1
func hotbar_index(ev: InputEvent) -> int:
	for i in 8:
		if is_action(ev, "hotbar%d" % (i + 1)):
			return i
	return -1

func action_label(action: String) -> String:
	return String(_info[action][1]) if _info.has(action) else action

## the other actions already on this key that could fire at the same moment as `action`
func conflicts(action: String, code: int) -> Array:
	var out: Array = []
	if code == 0 or not _info.has(action):
		return out
	var ctx := String(_info[action][3])
	for a in ACTIONS:
		var other := String(a[0])
		if other == action:
			continue
		var b: Array = binds[other]
		if int(b[0]) != code and int(b[1]) != code:
			continue
		var octx := String(a[3])
		if ctx == "all" or octx == "all" or ctx == octx:
			out.append(other)
	return out

## binds a key to one of an action's two slots. With `steal`, any action that would clash loses the key;
## without it nothing changes when there is a clash, and the clashing actions are returned.
func set_binding(action: String, slot: int, code: int, steal := true) -> Array:
	if not binds.has(action) or slot < 0 or slot > 1:
		return []
	var clash := conflicts(action, code)
	if not clash.is_empty() and not steal:
		return clash
	for other in clash:
		var ob: Array = binds[other]
		for i in 2:
			if int(ob[i]) == code: ob[i] = 0
		if int(ob[0]) == 0 and int(ob[1]) != 0:
			ob[0] = ob[1]; ob[1] = 0
	var b: Array = binds[action]
	if code != 0 and int(b[1 - slot]) == code:
		b[1 - slot] = 0
	b[slot] = code
	if int(b[0]) == 0 and int(b[1]) != 0:
		b[0] = b[1]; b[1] = 0
	_touch()
	bindings_changed.emit()
	return clash

func is_default_binding(action: String) -> bool:
	var b: Array = binds.get(action, [0, 0])
	return _info.has(action) and int(b[0]) == int(_info[action][4]) and int(b[1]) == int(_info[action][5])

## puts one action back on its original keys, taking them from whatever holds them now
func reset_binding(action: String) -> void:
	if not _info.has(action):
		return
	binds[action] = [0, 0]
	for slot in 2:
		var code := int(_info[action][4 + slot])
		if code != 0:
			set_binding(action, slot, code, true)
	_touch()
	bindings_changed.emit()

func reset_bindings() -> void:
	for a in ACTIONS:
		binds[a[0]] = [int(a[4]), int(a[5])]
	_touch()
	bindings_changed.emit()

# ------------------------------------------------------------------ what can be applied from here
func apply_all() -> void:
	for k in ["window_mode", "vsync", "fps_cap", "master_vol", "audio_device"]:
		_apply(k, true)

static func parse_size(s: String) -> Vector2i:
	var p := s.split("x")
	if p.size() != 2:
		return Vector2i(1600, 900)
	return Vector2i(maxi(640, int(p[0])), maxi(360, int(p[1])))

func _apply(key: String, boot := false) -> void:
	var headless := DisplayServer.get_name() == "headless"
	match key:
		"window_mode", "window_size":
			if headless: return
			var mode := String(v("window_mode"))
			if boot and mode == "windowed":
				return                       # the window is as the project (or the command line) made it
			if mode == "fullscreen":
				DisplayServer.window_set_mode(DisplayServer.WINDOW_MODE_EXCLUSIVE_FULLSCREEN)
			elif mode == "borderless":
				DisplayServer.window_set_mode(DisplayServer.WINDOW_MODE_FULLSCREEN)
			else:
				DisplayServer.window_set_mode(DisplayServer.WINDOW_MODE_WINDOWED)
				DisplayServer.window_set_flag(DisplayServer.WINDOW_FLAG_BORDERLESS, false)
				var want := parse_size(String(v("window_size")))
				var scr := DisplayServer.screen_get_size()
				if scr.x > 0:
					want = Vector2i(mini(want.x, scr.x), mini(want.y, scr.y))
					DisplayServer.window_set_size(want)
					DisplayServer.window_set_position(DisplayServer.screen_get_position() + (scr - want) / 2)
		"vsync":
			if headless or (boot and bool(v(key))): return
			DisplayServer.window_set_vsync_mode(DisplayServer.VSYNC_ENABLED if bool(v(key)) else DisplayServer.VSYNC_DISABLED)
		"fps_cap":
			Engine.max_fps = int(float(v(key)))
		"master_vol", "mute_unfocused":
			var i := AudioServer.get_bus_index("Master")
			if i >= 0:
				var lin := clampf(float(v("master_vol")), 0.0, 1.5)
				AudioServer.set_bus_mute(i, lin <= 0.001 or (not _focused and bool(v("mute_unfocused"))))
				AudioServer.set_bus_volume_db(i, linear_to_db(maxf(lin, 0.0001)))
		"audio_device":
			var want_dev := String(v(key))
			if want_dev in AudioServer.get_output_device_list() and AudioServer.output_device != want_dev:
				AudioServer.output_device = want_dev
