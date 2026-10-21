class_name MainMenu
extends Control
## Title screen: Continue / New game / Host co-op / Join co-op / Settings / Quit.

signal start_requested(mode: String, arg: String)   # "new" | "continue" | "host" | "join"

var _panel: PanelContainer
var _settings: SettingsPanel
var _character: CharacterPanel
var _achievements: AchievementsPanel
var _join_box: HBoxContainer
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
	_btn(bv, "Host co-op (LAN / direct IP)", func(): _ask_name("host"))
	_btn(bv, "Join co-op…", func(): _join_box.visible = not _join_box.visible)
	_join_box = HBoxContainer.new()
	_join_box.visible = false
	bv.add_child(_join_box)
	var ip := LineEdit.new()
	ip.text = "127.0.0.1"
	ip.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_join_box.add_child(ip)
	var go := Button.new()
	go.text = "Join"
	go.pressed.connect(func(): start_requested.emit("join", ip.text))
	_join_box.add_child(go)
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
	start_requested.emit(_name_mode, name)


func set_status(t: String) -> void:
	_status.text = t


func _btn(parent: Control, t: String, cb: Callable) -> void:
	var b := Button.new()
	b.text = t
	b.custom_minimum_size.y = 48
	b.add_theme_font_size_override("font_size", 19)
	b.pressed.connect(func():
		Sfx.play_ui("ui_click", -6.0)
		cb.call())
	parent.add_child(b)
