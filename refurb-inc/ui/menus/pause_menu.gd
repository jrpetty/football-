class_name PauseMenu
extends CanvasLayer
## Esc menu. In solo the simulation pauses; in co-op time keeps running (it's a shared business!).

signal quit_to_menu
signal open_to_friends
signal join_invite

var _root: Control
var _main: VBoxContainer
var _settings: SettingsPanel
var _character: CharacterPanel
var _open := false
var _invite_btn: Button
var _open_btn: Button
var _join_btn: Button


func _ready() -> void:
	layer = 30
	process_mode = Node.PROCESS_MODE_ALWAYS
	_root = Control.new()
	_root.set_anchors_preset(Control.PRESET_FULL_RECT)
	_root.theme = UiStyle.theme()
	add_child(_root)
	var dim := ColorRect.new()
	dim.color = Color(0, 0, 0, 0.55)
	dim.set_anchors_preset(Control.PRESET_FULL_RECT)
	_root.add_child(dim)
	var center := CenterContainer.new()
	center.set_anchors_preset(Control.PRESET_FULL_RECT)
	_root.add_child(center)
	var stack := VBoxContainer.new()
	center.add_child(stack)
	var panel := PanelContainer.new()
	panel.add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG_SOLID, 14, 28))
	stack.add_child(panel)
	_main = VBoxContainer.new()
	_main.custom_minimum_size = Vector2(380, 0)
	_main.add_theme_constant_override("separation", 10)
	panel.add_child(_main)
	_main.add_child(UiStyle.label("Paused", 32, UiStyle.TEXT))
	_main.add_child(UiStyle.label(BuildInfo.STAMP, 14, UiStyle.MUTED))
	_btn("Resume", close)
	_open_btn = _btn("Open to friends (Steam)", func():
		close()
		open_to_friends.emit())
	_invite_btn = _btn("Invite friends", func():
		if not Net.invite_friends():
			Game.toast.emit("Steam's overlay isn't available. Friends can join from your Steam profile.", "warn"))
	_join_btn = _btn("Join a friend's game", func():
		close()
		join_invite.emit())
	_btn("Settings", _show_settings)
	_btn("Character", func():
		panel.visible = false
		_character.visible = true)
	_btn("Save game", func():
		var err := SaveManager.save_game("manual")
		Game.toast.emit("Game saved." if err == OK else "Only the host can save.", "good" if err == OK else "warn"))
	_btn("Save & quit to menu", func():
		SaveManager.save_game("manual")
		close()
		quit_to_menu.emit())
	_btn("Quit to desktop", func():
		SaveManager.save_game("manual")
		get_tree().quit())
	_settings = SettingsPanel.new()
	_settings.visible = false
	_settings.closed.connect(func():
		_settings.visible = false
		panel.visible = true)
	stack.add_child(_settings)
	_settings.set_meta("panel", panel)
	_character = CharacterPanel.new()
	_character.visible = false
	_character.closed.connect(func():
		_character.visible = false
		panel.visible = true)
	stack.add_child(_character)
	_root.visible = false


func _btn(t: String, cb: Callable) -> Button:
	var b := Button.new()
	b.text = t
	b.custom_minimum_size.y = 46
	b.pressed.connect(cb)
	_main.add_child(b)
	return b


func _show_settings() -> void:
	(_settings.get_meta("panel") as Control).visible = false
	_settings.visible = true


func is_open() -> bool:
	return _open


func open() -> void:
	_open = true
	_root.visible = true
	# Steam co-op buttons: open a solo game to friends, invite more, or take up a friend's invite.
	_open_btn.visible = Net.steam.available and not Net.is_online()
	_invite_btn.visible = Net.is_steam()
	_join_btn.visible = not Net.pending_invite.is_empty()
	if _join_btn.visible:
		_join_btn.text = "Join %s's game (saves yours first)" % str(Net.pending_invite.get("from", "a friend"))
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	if not Net.is_online() and Game.sim:
		Game.sim.paused = true
		get_tree().paused = true


func close() -> void:
	_open = false
	_root.visible = false
	_settings.visible = false
	(_settings.get_meta("panel") as Control).visible = true
	if Game.sim:
		Game.sim.paused = false
	get_tree().paused = false
	var p: Node = LevelRoot.current.local_player() if LevelRoot.current else null
	if p and not p.ui_mode:
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED


func _unhandled_input(event: InputEvent) -> void:
	if event.is_action_pressed("pause"):
		if _open:
			close()
		else:
			var p: Node = LevelRoot.current.local_player() if LevelRoot.current else null
			if p and p.ui_mode:
				return     # office PC handles its own Esc
			if p and p.placing_id != "":
				p.cancel_placement()
				get_viewport().set_input_as_handled()
				return
			open()
		get_viewport().set_input_as_handled()
