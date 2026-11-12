extends Node
## The map (M): the valley from above — terrain, forest, the lake, roads, every building and whether you have
## been through it, where you are and which way you face, and your own markers. Loaded by game.gd as a module.
## The drawing lives in ui_map.gd; this owns the window and the saved markers.

var game
var view: UiMap.View = null
var _t := 0.0

func _ready() -> void:
	if game != null:
		UiMap.prepare(game.get("world"))

func mod_started(new_game: bool) -> void:
	if new_game:
		UiMap.markers.clear()
	UiMap.prepare(game.get("world"))
	UiMap.count_searched()

func mod_input(ev: InputEvent) -> bool:
	if ev is InputEventKey and ev.pressed and not ev.echo and ev.physical_keycode == KEY_M:
		var hud = game.hud
		if hud.window_kind == "map":
			hud.close_window()
			Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
			return true
		if not hud.is_open() or hud.window_kind == "inv" or hud.window_kind == "health":
			open_map()
			return true
	return false

func open_map() -> void:
	var hud = game.hud
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	UiMap.prepare(game.get("world"))
	UiMap.count_searched()
	var v: VBoxContainer = hud.open_window("map", 1240.0, 720.0, "Map")
	view = UiMap.View.new(game, false)
	view.track(game.player.pos, game.player.yaw, 0.0)
	var frame := UiKit.Frame.new(UiMap.C_VOID, 1.0, UiTheme.LINE2); frame.marks = false; frame.grain = 0.0
	frame.size_flags_vertical = Control.SIZE_EXPAND_FILL
	frame.add_child(view)
	v.add_child(frame)
	var foot := HBoxContainer.new(); foot.add_theme_constant_override("separation", 6)
	for e in [["house", UiTheme.ACCENT2, "Your cabin"], ["pin", UiTheme.BLUE, "Your marker"], ["check", UiTheme.DIM, "Searched"]]:
		foot.add_child(UiKit.Glyph.new(String(e[0]), 14.0, e[1]))
		var l := UiKit.body(String(e[2]), 13, UiTheme.DIM); l.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		foot.add_child(l); foot.add_child(UiKit.spacer(0, 10))
	foot.add_child(UiKit.spacer(0, 0, true))
	for e in [["LMB", "Drag"], ["Wheel", "Zoom"], ["RMB", "Marker"], ["M", "Close"]]:
		foot.add_child(UiKit.KeyCap.new(String(e[0]), 12))
		var l2 := UiKit.body(String(e[1]), 13, UiTheme.DIM); l2.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		foot.add_child(l2); foot.add_child(UiKit.spacer(0, 8))
	foot.add_child(hud.button("Find me", func() -> void:
		if view != null and is_instance_valid(view): view.recenter(), 110.0))
	v.add_child(foot)

func mod_process(dt: float, _dt_game_min: float, _ui_open: bool) -> void:
	if view == null:
		return
	if not is_instance_valid(view):
		view = null
		return
	view.track(game.player.pos, game.player.yaw, dt)
	_t -= dt
	if _t <= 0.0:
		_t = 1.0
		UiMap.count_searched()

func mod_save() -> Variant:
	return {"markers": UiMap.markers.duplicate(true)}

func mod_load(data) -> void:
	UiMap.markers.clear()
	if data is Dictionary:
		for m in data.get("markers", []):
			if m is Dictionary and m.has("x") and m.has("z"):
				UiMap.markers.append({"x": float(m.x), "z": float(m.z)})
