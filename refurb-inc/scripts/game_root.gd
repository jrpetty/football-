extends Node
## Top-level flow: title screen (with the garage as a live backdrop) → game (solo / host / join) → back.

const DEFAULT_LEVEL := "res://scenes/levels/garage/garage.tscn"

var _menu: MainMenu
var _level: LevelRoot
var _hud: Hud
var _pause: PauseMenu
var _summary: DaySummary
var _menu_cam: Camera3D
var _menu_t := 0.0
var _fade: ColorRect
var _switching := false


static func level_path(building_id: String) -> String:
	var p: String = Content.building(building_id).get("scene", DEFAULT_LEVEL)
	return p if ResourceLoader.exists(p) else DEFAULT_LEVEL


func _ready() -> void:
	var fl := CanvasLayer.new()
	fl.layer = 100
	add_child(fl)
	_fade = ColorRect.new()
	_fade.color = Color.BLACK
	_fade.set_anchors_preset(Control.PRESET_FULL_RECT)
	_fade.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_fade.modulate.a = 0.0
	fl.add_child(_fade)
	Game.state_reset.connect(_watch_state)
	Net.connection_failed.connect(func(r):
		if _menu:
			_menu.set_status(r))
	Net.server_closed.connect(func():
		_back_to_menu()
		if _menu:
			_menu.set_status("The host closed the game."))
	var args := OS.get_cmdline_user_args()
	if "--autostart" in args:
		_start("new", "")
	elif "--host" in args:
		_start("host", "")
	elif "--join" in args:
		_start("join", args[args.find("--join") + 1] if args.size() > args.find("--join") + 1 else "127.0.0.1")
	else:
		_show_menu()


func _show_menu() -> void:
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	LevelRoot.menu_mode = true
	_level = load(DEFAULT_LEVEL).instantiate()
	add_child(_level)
	_menu_cam = Camera3D.new()
	_menu_cam.fov = 58
	_level.add_child(_menu_cam)
	_menu_cam.current = true
	_menu = MainMenu.new()
	var cl := CanvasLayer.new()
	cl.name = "MenuLayer"
	add_child(cl)
	cl.add_child(_menu)
	_menu.start_requested.connect(_start)


func _process(delta: float) -> void:
	if _menu_cam and is_instance_valid(_menu_cam):
		# Slow dolly around the garage interior for the title screen.
		_menu_t += delta * 0.05
		var a := sin(_menu_t) * 0.35
		_menu_cam.global_position = Vector3(1.4 + sin(_menu_t * 0.7) * 0.6, 1.55, 3.2)
		_menu_cam.look_at(Vector3(-0.6 + a, 1.0, -2.6))


func _clear_menu() -> void:
	if _menu:
		_menu.get_parent().queue_free()
		_menu = null
	if _level:
		_level.queue_free()
		_level = null
	_menu_cam = null
	LevelRoot.menu_mode = false


func _start(mode: String, arg: String) -> void:
	Game.records_enabled = true      # real runs post milestone times to this PC's leaderboard
	match mode:
		"new":
			Net.go_offline()
			_clear_menu()
			Game.new_game()
			_name_company(arg)
			_enter_level()
		"continue":
			Net.go_offline()
			var slot := "manual" if SaveManager.has_save("manual") else "autosave"
			_clear_menu()
			if not SaveManager.load_game(slot):
				Game.new_game()
			_enter_level()
		"host":
			if Net.host_enet() != OK:
				if _menu:
					_menu.set_status("Couldn't open port %d." % Net.DEFAULT_PORT)
				return
			_clear_menu()
			Game.new_game()
			_name_company(arg)
			_enter_level()
			Game.toast.emit("Hosting on port %d. Friends can join with your IP address." % Net.DEFAULT_PORT, "info")
		"join":
			if _menu:
				_menu.set_status("Connecting to %s…" % arg)
			if Net.join_enet(arg) != OK:
				if _menu:
					_menu.set_status("Couldn't connect.")
				return
			await Game.state_reset
			_clear_menu()
			_enter_level()


func _name_company(name: String) -> void:
	## A new company starts with the name chosen on the title screen (before anyone joins, so no rename event).
	if name != "":
		Game.state.company_name = Sim.clean_company_name(name)
		Game.state.mark("company")


func _watch_state() -> void:
	if not Game.state.changed.is_connected(_on_state_changed):
		Game.state.changed.connect(_on_state_changed)


func _on_state_changed(kind: String, _id: Variant) -> void:
	if kind == "building" and _level and is_instance_valid(_level) and not LevelRoot.menu_mode:
		if _level.building_id != Game.state.building_id and not _switching:
			_switch_level()


func _switch_level() -> void:
	## Moving premises: fade out, swap the level scene, fade in. Time keeps ticking.
	_switching = true
	var tw := create_tween()
	tw.tween_property(_fade, "modulate:a", 1.0, 0.6)
	await tw.finished
	var old := _level
	remove_child(old)
	old.queue_free()
	await get_tree().process_frame
	_level = load(level_path(Game.state.building_id)).instantiate()
	add_child(_level)
	move_child(_level, 0)
	await get_tree().create_timer(0.4).timeout
	var tw2 := create_tween()
	tw2.tween_property(_fade, "modulate:a", 0.0, 0.9)
	_switching = false
	Input.mouse_mode = Input.MOUSE_MODE_CAPTURED


func _enter_level() -> void:
	Game.share_look()
	_level = load(level_path(Game.state.building_id)).instantiate()
	add_child(_level)
	_hud = Hud.new()
	add_child(_hud)
	_summary = DaySummary.new()
	add_child(_summary)
	_pause = PauseMenu.new()
	add_child(_pause)
	_pause.quit_to_menu.connect(_back_to_menu)
	Input.mouse_mode = Input.MOUSE_MODE_CAPTURED


func _back_to_menu() -> void:
	get_tree().paused = false
	Game.leave_game()
	for n in [_level, _hud, _summary, _pause]:
		if n and is_instance_valid(n):
			n.queue_free()
	_level = null
	_hud = null
	_summary = null
	_pause = null
	await get_tree().process_frame
	_show_menu()
