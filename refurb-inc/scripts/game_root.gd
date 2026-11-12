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
var _lobby_info := ""        # last name|day sent to the Steam lobby


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
	# Steam (lobbies, invites, achievements) for real play only — never in headless tools and tests.
	if DisplayServer.get_name() != "headless" and not "--no-steam" in args and Net.start_steam():
		Net.invite_received.connect(_on_invite)
		Game.day_summary.connect(func(_s): _update_lobby_info())
	var launch_lobby := Net.steam.launch_lobby() if Net.steam.available else 0
	if launch_lobby != 0:
		# Steam started the game from a friend's invite.
		_show_menu()
		_start("join_steam", str(launch_lobby))
	elif "--autostart" in args:
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
			if not await _wait_for(Game.state_reset, 20.0):
				Net.go_offline()
				if _menu:
					_menu.set_status("Couldn't reach a game at %s." % arg)
				return
			_clear_menu()
			_enter_level()
		"challenge":
			# Challenges and the daily run (arg = id, "daily", or "resume" = the last challenge save). Open it to
			# friends from the pause menu for co-op.
			if arg == "daily" and not Challenges.daily_attempt(Challenges.today_key()).is_empty():
				if _menu:
					_menu.set_status("You've had today's daily run. A new one tomorrow.")
				return
			Net.go_offline()
			_clear_menu()
			if arg == "resume":
				if not SaveManager.load_game(SaveManager.CHALLENGE):
					Game.new_game()
			elif not Challenges.begin(arg, str(Settings.values.get("company_name", ""))):
				Game.new_game()
			_enter_level()
		"host_steam", "host_steam_public":
			if _menu:
				_menu.set_status("Opening your game on Steam…")
			if Net.host_steam(mode == "host_steam") != OK or not await _wait_for(Net.hosting_ready, 20.0):
				Net.go_offline()
				if _menu:
					_menu.set_status("Couldn't open a Steam game. Is Steam running and online?")
				return
			if _menu:
				_menu.set_status("")
			_clear_menu()
			Game.new_game()
			_name_company(arg)
			_enter_level()
			_update_lobby_info()
			Game.toast.emit("Your game is open on Steam. Press Esc → Invite friends.", "good")
		"join_steam":
			if _menu:
				_menu.set_status("Joining your friend's game…")
			if Net.join_steam(int(arg)) != OK or not await _wait_for(Game.state_reset, 30.0):
				Net.go_offline()
				if _menu:
					_menu.set_status("Couldn't join that game. It may have closed or be full.")
				return
			if _menu:
				_menu.set_status("")
			_clear_menu()
			_enter_level()


func _wait_for(sig: Signal, timeout: float) -> bool:
	## Wait for `sig`; false if the connection fails first or it takes longer than `timeout` seconds.
	var done := [false, false]
	var ok := func(_a = null): done[0] = true; done[1] = true
	var fail := func(_r = ""): done[0] = true
	sig.connect(ok, CONNECT_ONE_SHOT)
	Net.connection_failed.connect(fail, CONNECT_ONE_SHOT)
	var t := 0.0
	while not done[0] and t < timeout:
		await get_tree().process_frame
		t += get_process_delta_time()
	if sig.is_connected(ok):
		sig.disconnect(ok)
	if Net.connection_failed.is_connected(fail):
		Net.connection_failed.disconnect(fail)
	return done[1]


func _on_invite(lobby_id: int, from_name: String) -> void:
	## A Steam invite accepted (or "Join game" pressed). At the title screen we just go; mid-game the pause menu
	## offers it, so nobody gets yanked out of their own business.
	if _menu and not Game.in_game:
		_start("join_steam", str(lobby_id))
	else:
		Game.toast.emit("%s invited you to their game. Press Esc → Join %s's game." % [from_name, from_name], "info")


func open_to_friends() -> void:
	## Pause menu: turn the running solo game into a Steam game without restarting (solo already runs as host).
	if Net.is_online() or not Net.steam.available:
		return
	if Net.host_steam(true) != OK or not await _wait_for(Net.hosting_ready, 20.0):
		Net.go_offline()
		Game.toast.emit("Couldn't open the game on Steam. Is Steam running and online?", "warn")
		return
	_lobby_info = ""
	_update_lobby_info()
	Net.invite_friends()
	Game.toast.emit("Your game is open to friends. Invite them from the Steam overlay.", "good")


func _update_lobby_info() -> void:
	if not Net.is_steam() or not Game.is_host():
		return
	var key := "%s|%d" % [Game.state.company_name, Game.state.day_index() + 1]
	if key != _lobby_info:
		_lobby_info = key
		Net.steam.set_lobby_info(Game.state.company_name, Game.state.day_index() + 1)


func _name_company(name: String) -> void:
	## A new company starts with the name chosen on the title screen (before anyone joins, so no rename event).
	if name != "":
		Game.state.company_name = Sim.clean_company_name(name)
		Game.state.mark("company")


func _watch_state() -> void:
	if not Game.state.changed.is_connected(_on_state_changed):
		Game.state.changed.connect(_on_state_changed)


func _on_state_changed(kind: String, _id: Variant) -> void:
	if kind == "company" and Net.is_steam():
		_update_lobby_info()
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
	_pause.open_to_friends.connect(open_to_friends)
	_pause.join_invite.connect(func():
		var inv: Dictionary = Net.pending_invite
		if inv.is_empty():
			return
		SaveManager.save_game("manual")
		await _back_to_menu()
		_start("join_steam", str(int(inv.lobby_id))))
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
