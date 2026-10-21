class_name MainMenu
extends Control
## Title screen: Continue / New game / Host on Steam / Join a game / Character / Achievements / Settings / Quit.

## "new" | "continue" | "host" (LAN) | "join" (LAN, arg = address) | "host_steam" / "host_steam_public" (arg = business
## name) | "join_steam" (arg = lobby id)
signal start_requested(mode: String, arg: String)

var _panel: PanelContainer
var _settings: SettingsPanel
var _character: CharacterPanel
var _achievements: AchievementsPanel
var _join_panel: PanelContainer
var _lobby_list: VBoxContainer
var _lobby_status: Label
var _public_cb: CheckBox
var _name_box: PanelContainer
var _name_edit: LineEdit
var _name_mode := "new"
var _status: Label


func _ready() -> void:
	set_anchors_preset(Control.PRESET_FULL_RECT)
	theme = UiStyle.theme()
	var shade := ColorRect.new()
	shade.set_anchors_preset(Control.PRESET_FULL_RECT)
	shade.color = Color(0.02, 0.03, 0.05, 0.35)
	shade.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(shade)
	var left := MarginContainer.new()
	left.set_anchors_preset(Control.PRESET_LEFT_WIDE)
	left.add_theme_constant_override("margin_left", 90)
	left.add_theme_constant_override("margin_top", 120)
	add_child(left)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 12)
	left.add_child(v)
	var title := UiStyle.label("REFURB INC.", 76, Color.WHITE)
	title.add_theme_constant_override("outline_size", 8)
	title.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.5))
	v.add_child(title)
	v.add_child(UiStyle.label("Buy it. Fix it. Flip it. Scale it.", 24, UiStyle.ACCENT))
	var sp := Control.new()
	sp.custom_minimum_size.y = 30
	v.add_child(sp)
	_panel = PanelContainer.new()
	_panel.add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG, 14, 22))
	v.add_child(_panel)
	var bv := VBoxContainer.new()
	bv.custom_minimum_size.x = 360
	bv.add_theme_constant_override("separation", 10)
	_panel.add_child(bv)
	if SaveManager.has_save("manual") or SaveManager.has_save("autosave"):
		_btn(bv, "Continue", func(): start_requested.emit("continue", ""))
	_btn(bv, "New game", func(): _ask_name("new"))
	var steam_btn := _btn(bv, "Host on Steam", func(): _ask_name("host_steam"))
	if not Net.steam.available:
		steam_btn.disabled = true
		steam_btn.tooltip_text = Net.steam.status_text + " LAN co-op is under Join a game."
	_btn(bv, "Join a game…", _open_join)
	_btn(bv, "Character", func():
		_panel.visible = false
		_character.visible = true)
	var have := Game.achievements.unlocked_count()
	var ach_label := "Achievements"
	if have > 0:
		ach_label = "Achievements  (%d/%d)" % [have, Achievements.defs().size()]
	_btn(bv, ach_label, func():
		_panel.visible = false
		_achievements.visible = true)
	_btn(bv, "Settings", func():
		_panel.visible = false
		_settings.visible = true)
	_btn(bv, "Quit", func(): get_tree().quit())
	_status = UiStyle.label("", 16, UiStyle.WARN)
	v.add_child(_status)
	_settings = SettingsPanel.new()
	_settings.visible = false
	_settings.closed.connect(func():
		_settings.visible = false
		_panel.visible = true)
	v.add_child(_settings)
	_character = CharacterPanel.new()
	_character.visible = false
	_character.closed.connect(func():
		_character.visible = false
		_panel.visible = true)
	v.add_child(_character)
	_build_name_box(v)
	_build_join_panel(v)
	_achievements = AchievementsPanel.new()
	_achievements.visible = false
	_achievements.closed.connect(func():
		_achievements.visible = false
		_panel.visible = true)
	v.add_child(_achievements)
	var ver := UiStyle.label("v%s · %s · early development build" % [ProjectSettings.get_setting("application/config/version", "0.1"), BuildInfo.STAMP], 15, UiStyle.MUTED)
	ver.set_anchors_preset(Control.PRESET_BOTTOM_LEFT)
	ver.position = Vector2(24, -36)
	add_child(ver)


func _build_name_box(parent: Control) -> void:
	## "Name your business" step before a new game (or a hosted one). Defaults to the last name used.
	_name_box = PanelContainer.new()
	_name_box.add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG, 14, 22))
	_name_box.visible = false
	parent.add_child(_name_box)
	var v := VBoxContainer.new()
	v.custom_minimum_size.x = 460
	v.add_theme_constant_override("separation", 12)
	_name_box.add_child(v)
	v.add_child(UiStyle.label("Name your business", 28, Color.WHITE))
	v.add_child(UiStyle.label("It goes on the sign outside, your parcels and your leaderboard times.", 16, UiStyle.MUTED))
	_name_edit = LineEdit.new()
	_name_edit.max_length = Sim.COMPANY_NAME_MAX
	_name_edit.placeholder_text = Sim.DEFAULT_COMPANY
	_name_edit.custom_minimum_size.y = 52
	_name_edit.add_theme_font_size_override("font_size", 24)
	_name_edit.text_submitted.connect(func(_t): _confirm_name())
	v.add_child(_name_edit)
	v.add_child(UiStyle.label("You can rename it any time on the office PC (Finance).", 15, UiStyle.MUTED))
	_public_cb = CheckBox.new()
	_public_cb.text = "Public game: anyone can find it in Join a game (friends can always join)"
	_public_cb.add_theme_font_size_override("font_size", 16)
	v.add_child(_public_cb)
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 10)
	v.add_child(h)
	var start := Button.new()
	start.text = "Start trading"
	start.custom_minimum_size = Vector2(200, 48)
	start.add_theme_font_size_override("font_size", 19)
	start.pressed.connect(_confirm_name)
	h.add_child(start)
	var reset := Button.new()
	reset.text = "Use Refurb Inc."
	reset.custom_minimum_size.y = 48
	reset.pressed.connect(func(): _name_edit.text = Sim.DEFAULT_COMPANY)
	h.add_child(reset)
	var back := Button.new()
	back.text = "Back"
	back.custom_minimum_size.y = 48
	back.pressed.connect(func():
		_name_box.visible = false
		_panel.visible = true)
	h.add_child(back)


func _ask_name(mode: String) -> void:
	_name_mode = mode
	_public_cb.visible = mode == "host_steam"
	_public_cb.button_pressed = false
	_name_edit.text = str(Settings.values.get("company_name", Sim.DEFAULT_COMPANY))
	_panel.visible = false
	_name_box.visible = true
	_name_edit.grab_focus()
	_name_edit.select_all()


func _confirm_name() -> void:
	Sfx.play_ui("ui_click", -6.0)
	var name := Sim.clean_company_name(_name_edit.text)
	Settings.values["company_name"] = name
	Settings.save_settings()
	var mode := _name_mode
	if mode == "host_steam" and _public_cb.button_pressed:
		mode = "host_steam_public"
	start_requested.emit(mode, name)


func _build_join_panel(parent: Control) -> void:
	## Public Steam games, how to join a friend, and LAN / direct IP.
	_join_panel = PanelContainer.new()
	_join_panel.add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG, 14, 22))
	_join_panel.visible = false
	parent.add_child(_join_panel)
	var v := VBoxContainer.new()
	v.custom_minimum_size.x = 560
	v.add_theme_constant_override("separation", 10)
	_join_panel.add_child(v)
	v.add_child(UiStyle.label("Join a game", 28, Color.WHITE))
	if Net.steam.available:
		v.add_child(UiStyle.label("Friends' games: accept their invite in Steam, or right-click them in your friends list → Join game.", 16, UiStyle.MUTED))
		var head := HBoxContainer.new()
		v.add_child(head)
		var t := UiStyle.label("Public games", 20, Color.WHITE)
		t.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		head.add_child(t)
		var refresh := Button.new()
		refresh.text = "Refresh"
		refresh.pressed.connect(_refresh_lobbies)
		head.add_child(refresh)
		_lobby_list = VBoxContainer.new()
		_lobby_list.add_theme_constant_override("separation", 6)
		v.add_child(_lobby_list)
		_lobby_status = UiStyle.label("", 16, UiStyle.MUTED)
		v.add_child(_lobby_status)
		Net.steam.lobbies_found.connect(_show_lobbies)
	else:
		v.add_child(UiStyle.label(Net.steam.status_text + " Start Steam and restart the game to play with friends online.", 16, UiStyle.WARN))
	v.add_child(UiStyle.label("LAN / direct IP", 20, Color.WHITE))
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	v.add_child(row)
	var ip := LineEdit.new()
	ip.text = "127.0.0.1"
	ip.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(ip)
	var go := Button.new()
	go.text = "Join"
	go.pressed.connect(func(): start_requested.emit("join", ip.text))
	row.add_child(go)
	var host := Button.new()
	host.text = "Host on LAN"
	host.pressed.connect(func():
		_join_panel.visible = false
		_ask_name("host"))
	row.add_child(host)
	var back := Button.new()
	back.text = "Back"
	back.custom_minimum_size.y = 44
	back.pressed.connect(func():
		_join_panel.visible = false
		_panel.visible = true)
	v.add_child(back)


func _open_join() -> void:
	_panel.visible = false
	_join_panel.visible = true
	_refresh_lobbies()


func _refresh_lobbies() -> void:
	if _lobby_list == null:
		return
	for c in _lobby_list.get_children():
		c.queue_free()
	_lobby_status.text = "Looking for games…"
	Net.steam.find_lobbies()


func _show_lobbies(lobbies: Array) -> void:
	if _lobby_list == null:
		return
	for c in _lobby_list.get_children():
		c.queue_free()
	_lobby_status.text = "" if not lobbies.is_empty() else "No public games right now. Host one, or join a friend through Steam."
	for l: Dictionary in lobbies:
		var row := HBoxContainer.new()
		row.add_theme_constant_override("separation", 10)
		var lab := UiStyle.label("%s  ·  %s  ·  day %d  ·  %d/%d" % [str(l.name) if str(l.name) != "" else "Refurb business", l.host, maxi(int(l.day), 1), int(l.members), int(l.max)], 17, Color.WHITE)
		lab.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		lab.clip_text = true
		row.add_child(lab)
		var join := Button.new()
		join.text = "Join"
		join.disabled = int(l.members) >= int(l.max)
		var id := int(l.id)
		join.pressed.connect(func(): start_requested.emit("join_steam", str(id)))
		row.add_child(join)
		_lobby_list.add_child(row)


func set_status(t: String) -> void:
	_status.text = t


func _btn(parent: Control, t: String, cb: Callable) -> Button:
	var b := Button.new()
	b.text = t
	b.custom_minimum_size.y = 48
	b.add_theme_font_size_override("font_size", 19)
	b.pressed.connect(func():
		Sfx.play_ui("ui_click", -6.0)
		cb.call())
	parent.add_child(b)
	return b
