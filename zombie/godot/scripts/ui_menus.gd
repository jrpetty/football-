class_name UiMenus
extends RefCounted
## The screens around the game: main menu, character creation, settings and controls, pause, death, and the
## loading card. One page at a time lives inside the HUD's window; Esc steps back out of a sub-page.

const KEYART := "res://assets/ui/keyart.png"
const NAMES := ["Sam Rivers", "Jo Hart", "Casey Doyle", "Riley Stone", "Morgan Price", "Alex Mercer", "Dana Whitlock", "Robin Vance"]
const TIPS := [
	"The dead hear sprinting, gunfire, chopping and breaking glass. Crouch to move quietly.",
	"A bite carries the grey fever. Clothing can stop teeth before they break the skin.",
	"Bandage a bleeding wound at once — Q treats the worst one.",
	"Rags stop bleeding but are not clean. Disinfect, then use a proper bandage.",
	"A fracture will not heal until it is splinted.",
	"Lake water can turn your stomach. Boil it when you can.",
	"At night a flashlight shows you the way — and shows the dead where you are.",
	"Duct tape repairs the weapon in your hands.",
	"A skill book triples what you learn in that skill for a day.",
	"You can carry more than you should. You pay for it in speed and breath.",
	"A locked door can be forced. A crowbar is quicker; either way it is loud.",
	"A gunshot carries for blocks. A bat does not.",
	"Press M for the map. Buildings you have gone through are marked off.",
]

var hud: Hud
var kind := ""
var page := ""
var _win: Control = null
var _holder: Control = null
var _backdrop: UiHudParts.Backdrop = null
var _text := ""
var _busy := false
var _data: Dictionary = {}
var _art: Texture2D = null
var _art_checked := false
var _name := ""
var _occ := "unemployed"
var _traits: Dictionary = {}
var _c_points: Label
var _c_summary: VBoxContainer
var _c_begin: Button
var _c_note: Label
var _c_trait_btns: Dictionary = {}

func _init(h: Hud) -> void:
	hud = h

func closed() -> void:
	kind = ""; page = ""; _win = null; _holder = null; _backdrop = null

func data() -> Dictionary:
	if _data.is_empty():
		var d = World.read_json("res://data/skills.json")
		if d is Dictionary: _data = d
	return _data

func key_art() -> Texture2D:
	if not _art_checked:
		_art_checked = true
		if ResourceLoader.exists(KEYART):
			var t = load(KEYART)
			if t is Texture2D: _art = t
		elif FileAccess.file_exists(KEYART):
			var img := Image.load_from_file(ProjectSettings.globalize_path(KEYART))
			if img != null and not img.is_empty():
				_art = ImageTexture.create_from_image(img)
	return _art

# ------------------------------------------------------------------ frame for every menu
func show(kind_: String, text := "") -> void:
	hud.close_window()
	kind = kind_; _text = text; page = ""
	hud.window_kind = kind_
	_win = Control.new(); _win.set_anchors_preset(Control.PRESET_FULL_RECT)
	hud.window = _win
	_backdrop = UiHudParts.Backdrop.new()
	match kind:
		"main":
			_backdrop.art = key_art()
			_backdrop.left = 0.86
			if _backdrop.art == null:
				_backdrop.shade = 0.34          # the live world can be midday-bright; the menu still has to read
		"pause":
			_backdrop.shade = 0.5; _backdrop.left = 0.7
		"dead":
			_backdrop.shade = 0.86; _backdrop.left = 0.3
			hud.play.visible = false; hud.fx.visible = false
	_win.add_child(_backdrop)
	_holder = Control.new(); _holder.set_anchors_preset(Control.PRESET_FULL_RECT); _holder.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_win.add_child(_holder)
	hud.root.add_child(_win)
	hud._layout()
	if hud.loading != null:
		hud.root.move_child(hud.loading, hud.root.get_child_count() - 1)
	_show_page("")
	_win.modulate.a = 0.0
	_win.create_tween().tween_property(_win, "modulate:a", 1.0, 1.1 if kind == "dead" else 0.25)

func _show_page(p: String) -> void:
	if _holder == null:
		return
	if page == "settings" and p != "settings":
		hud.settings.closed()
	page = p
	for c in _holder.get_children():
		c.queue_free()
	match p:
		"settings": _page_settings()
		"create": _page_create()
		_:
			match kind:
				"main": _page_main()
				"pause": _page_pause()
				"dead": _page_dead()

func key(ev: InputEventKey) -> bool:
	if kind == "":
		return false
	var k := ev.physical_keycode
	if k == KEY_ESCAPE:
		if ev.echo:
			return true
		if page != "":
			hud.click(); _show_page("")
			return true
		return kind != "pause"            # the game itself resumes from the pause menu
	if page != "" and k == KEY_TAB:
		return true
	return false

func _column() -> VBoxContainer:
	var col := VBoxContainer.new(); col.add_theme_constant_override("separation", 2)
	col.anchor_left = 0.075; col.anchor_right = 0.075; col.anchor_top = 0.0; col.anchor_bottom = 1.0
	col.alignment = BoxContainer.ALIGNMENT_CENTER
	col.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_holder.add_child(col)
	return col

func _focus_first(col: Control) -> void:
	for c in col.get_children():
		if c is UiKit.MenuItem and not c.disabled:
			c.grab_focus.call_deferred()
			return

func _item(text: String, cb: Callable, size := 27) -> UiKit.MenuItem:
	var press := func() -> void:
		hud.click()
		cb.call()
	return UiKit.MenuItem.new(text, press, size)

func _tagline(text: String) -> Label:
	var l := UiKit.caption(text, UiTheme.DIM, 14)
	l.add_theme_font_override("font", UiTheme.font("head"))
	l.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.4)); l.add_theme_constant_override("outline_size", 4)
	return l

func _foot(left: String, hints: Array) -> void:
	var l := UiKit.caption(left, UiTheme.FAINT, 12)
	l.anchor_left = 0.075; l.anchor_right = 0.075; l.anchor_top = 1.0; l.anchor_bottom = 1.0
	l.offset_left = 18.0; l.offset_top = -52.0; l.offset_bottom = -34.0
	_holder.add_child(l)
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 6); h.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.anchor_left = 1.0; h.anchor_right = 1.0; h.anchor_top = 1.0; h.anchor_bottom = 1.0
	h.grow_horizontal = Control.GROW_DIRECTION_BEGIN
	h.offset_right = -48.0; h.offset_left = -48.0; h.offset_top = -58.0; h.offset_bottom = -34.0
	for e in hints:
		h.add_child(UiKit.KeyCap.new(String(e[0]), 12))
		var t := UiKit.body(String(e[1]), 13, UiTheme.DIM); t.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		h.add_child(t); h.add_child(UiKit.spacer(0, 10))
	_holder.add_child(h)

# ------------------------------------------------------------------ main menu
func _page_main() -> void:
	var col := _column()
	col.add_child(Logo.new(104))
	col.add_child(UiKit.margin(_tagline("Survive the dead  ·  Rebuild the world"), 20, 0, 0, 0))
	col.add_child(UiKit.spacer(44))
	var has: bool = hud.game != null and hud.game.has_save()
	var cont := _item("Continue", func() -> void: launch("continue"))
	cont.disabled = not has
	col.add_child(cont)
	if has:
		var info := _save_line()
		if info != "":
			col.add_child(UiKit.margin(UiKit.caption(info, UiTheme.DIM, 12), 20, -2, 0, 10))
	col.add_child(_item("New game", func() -> void: _begin_create()))
	col.add_child(_item("Settings", func() -> void: _show_page("settings")))
	col.add_child(_item("Quit", func() -> void: hud.menu_action.emit("quit")))
	col.add_child(UiKit.spacer(30))
	var blurb := UiKit.body("Scavenge a ruined valley town, outlast the infected and the weather, and bring the lights back on. Death is permanent.", 14, UiTheme.DIM, true)
	blurb.custom_minimum_size = Vector2(430, 0)
	blurb.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.4)); blurb.add_theme_constant_override("outline_size", 4)
	col.add_child(UiKit.margin(blurb, 20, 0, 0, 0))
	var ver := String(ProjectSettings.get_setting("application/config/version", ""))
	var eng: Dictionary = Engine.get_version_info()
	_foot("Rekindle %s  ·  Godot %d.%d.%d" % [("v" + ver) if ver != "" else "stage A build", int(eng.major), int(eng.minor), int(eng.patch)], [["Arrows", "Select"], ["Enter", "Choose"]])
	_focus_first(col)

func _save_line() -> String:
	var f := FileAccess.open(String(hud.game.SAVE_PATH), FileAccess.READ)
	if f == null:
		return ""
	var o = JSON.parse_string(f.get_as_text())
	if not (o is Dictionary) or not o.has("game_min"):
		return ""
	var gm := float(o.game_min)
	var who := ""
	if o.get("player") is Dictionary: who = String(o.player.get("name", ""))
	var hrs := fmod(gm / 60.0, 24.0)
	return "%sDay %d  ·  %02d:%02d" % [(who + "  ·  ") if who != "" else "", int(gm / 1440.0) + 1, int(hrs), int(fmod(hrs, 1.0) * 60.0)]

# ------------------------------------------------------------------ pause
func _page_pause() -> void:
	var col := _column()
	col.add_child(Logo.new(64, "PAUSED", ""))
	var g = hud.game
	var line := ""
	if g != null and g.player != null:
		var hrs: float = g.hours()
		line = "Day %d  ·  %02d:%02d  ·  %d %s" % [g.day(), int(hrs), int(fmod(hrs, 1.0) * 60.0), g.player.kills, "kill" if g.player.kills == 1 else "kills"]
	col.add_child(UiKit.margin(_tagline(line), 20, 0, 0, 0))
	col.add_child(UiKit.spacer(36))
	col.add_child(_item("Resume", func() -> void: hud.menu_action.emit("resume")))
	col.add_child(_item("Save game", func() -> void: hud.menu_action.emit("save")))
	col.add_child(_item("Settings", func() -> void: _show_page("settings")))
	col.add_child(_item("Save and quit to menu", func() -> void: hud.menu_action.emit("menu")))
	_foot("Paused", [["Arrows", "Select"], ["Enter", "Choose"], ["Esc", "Resume"]])
	_focus_first(col)

# ------------------------------------------------------------------ death
func _page_dead() -> void:
	var g = hud.game
	var cc := CenterContainer.new(); cc.set_anchors_preset(Control.PRESET_FULL_RECT); cc.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var v := VBoxContainer.new(); v.add_theme_constant_override("separation", 8); v.alignment = BoxContainer.ALIGNMENT_CENTER
	var cause := _text; var who := "Survivor"; var hours := 0.0; var kills := 0
	if g != null and g.player != null:
		var p: Player = g.player
		who = p.name; hours = p.stats.hours_survived; kills = p.kills
		if p.stats.death_cause != "": cause = p.stats.death_cause
	if cause.find("\n") >= 0: cause = cause.substr(0, cause.find("\n"))
	var kicker := UiKit.caption("Day %d  ·  %s" % [g.day(), g.season_name()] if g != null else "", UiTheme.DIM, 13)
	kicker.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(kicker)
	var title := Logo.new(62, "THIS IS HOW YOU DIED", "", Color(0.8, 0.24, 0.17), false)
	title.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	v.add_child(title)
	var cl := UiKit.body("%s  —  %s" % [who, cause], 20, UiTheme.INK); cl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(cl)
	v.add_child(UiKit.spacer(26))
	var stats := HBoxContainer.new(); stats.alignment = BoxContainer.ALIGNMENT_CENTER; stats.add_theme_constant_override("separation", 0)
	var first := true
	for e in [["%dd %dh" % [int(hours / 24.0), int(fmod(hours, 24.0))], "Survived"], [str(kills), "Zombies killed"], [str(g.day() if g != null else 1), "Days seen"]]:
		if not first:
			var sep := ColorRect.new(); sep.color = UiTheme.LINE2; sep.custom_minimum_size = Vector2(1, 64); sep.size_flags_vertical = Control.SIZE_SHRINK_CENTER
			stats.add_child(sep)
		first = false
		var b := VBoxContainer.new(); b.custom_minimum_size = Vector2(220, 0); b.add_theme_constant_override("separation", 0)
		var n := UiKit.heading(String(e[0]), 44, UiTheme.INK); n.add_theme_font_override("font", UiTheme.font("num")); n.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		var c := UiKit.caption(String(e[1]), UiTheme.DIM, 12); c.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		b.add_child(n); b.add_child(c); stats.add_child(b)
	v.add_child(stats)
	v.add_child(UiKit.spacer(34))
	var row := HBoxContainer.new(); row.alignment = BoxContainer.ALIGNMENT_CENTER; row.add_theme_constant_override("separation", 12)
	var again := _big_button("New survivor", func() -> void: _begin_create(), true)
	row.add_child(again)
	row.add_child(_big_button("Main menu", func() -> void: hud.menu_action.emit("menu")))
	row.add_child(_big_button("Quit", func() -> void: hud.menu_action.emit("quit")))
	v.add_child(row)
	cc.add_child(v); _holder.add_child(cc)
	again.grab_focus.call_deferred()

func _big_button(text: String, cb: Callable, primary := false) -> Button:
	var b := Button.new(); b.text = text.to_upper(); b.custom_minimum_size = Vector2(200, 46)
	b.add_theme_font_size_override("font_size", 17)
	if primary:
		b.add_theme_stylebox_override("normal", UiTheme.flat_m(Color(UiTheme.ACCENT, 0.12), UiTheme.ACCENT, 1, 16, 7))
		b.add_theme_color_override("font_color", UiTheme.ACCENT2)
	b.pressed.connect(func() -> void:
		hud.click()
		cb.call())
	return b

# ------------------------------------------------------------------ a framed page (settings, creation)
func _framed(title: String, w: float, h: float) -> VBoxContainer:
	var cc := CenterContainer.new(); cc.set_anchors_preset(Control.PRESET_FULL_RECT); cc.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var frame := UiKit.Frame.new(); frame.custom_minimum_size = Vector2(w, h)
	var col := VBoxContainer.new(); col.add_theme_constant_override("separation", 0)
	var head := HBoxContainer.new(); head.custom_minimum_size = Vector2(0, 54)
	var tl := UiKit.heading(title, 22, UiTheme.ACCENT2); tl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	head.add_child(UiKit.spacer(0, 22)); head.add_child(tl); head.add_child(UiKit.spacer(0, 0, true))
	head.add_child(UiKit.KeyCap.new("Esc", 12))
	var bl := UiKit.body("Back", 13, UiTheme.DIM); bl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	head.add_child(bl); head.add_child(UiKit.spacer(0, 18))
	col.add_child(head)
	var rule := ColorRect.new(); rule.color = UiTheme.LINE; rule.custom_minimum_size = Vector2(0, 1)
	col.add_child(rule)
	var v := VBoxContainer.new(); v.add_theme_constant_override("separation", 12); v.size_flags_vertical = Control.SIZE_EXPAND_FILL
	var m := UiKit.margin(v, 22, 18, 22, 18); m.size_flags_vertical = Control.SIZE_EXPAND_FILL
	col.add_child(m)
	frame.add_child(col); cc.add_child(frame); _holder.add_child(cc)
	return v

# ------------------------------------------------------------------ settings
## the same pages the in-game Settings tab shows (ui_settings.gd), framed for the menus
func _page_settings() -> void:
	var v := _framed("Settings", 1240.0, 720.0)
	hud.settings.build(v, _win, func() -> void: _show_page(""))

# ------------------------------------------------------------------ character creation
func _begin_create() -> void:
	if _name == "":
		_name = NAMES[randi() % NAMES.size()]
	_show_page("create")

func points_left() -> int:
	var pts := 0
	for o in data().get("occupations", []):
		if String(o.id) == _occ: pts = int(o.points)
	for t in data().get("traits", []):
		if _traits.has(String(t.id)): pts -= int(t.cost)
	return pts

func _page_create() -> void:
	var v := _framed("Create your survivor", 1420.0, 760.0)
	var cols := HBoxContainer.new(); cols.add_theme_constant_override("separation", 24); cols.size_flags_vertical = Control.SIZE_EXPAND_FILL
	v.add_child(cols)
	_c_trait_btns.clear()

	# who you were
	var left := VBoxContainer.new(); left.custom_minimum_size = Vector2(430, 0); left.add_theme_constant_override("separation", 6)
	left.add_child(UiKit.caption("Name", UiTheme.DIM, 12))
	var nrow := HBoxContainer.new()
	var le := LineEdit.new(); le.text = _name; le.max_length = 24; le.size_flags_horizontal = Control.SIZE_EXPAND_FILL; le.placeholder_text = "Survivor"
	le.text_changed.connect(func(t: String) -> void: _name = t)
	nrow.add_child(le)
	var another := func() -> void:
		_name = NAMES[(NAMES.find(_name) + 1 + randi() % (NAMES.size() - 1)) % NAMES.size()]
		le.text = _name
	nrow.add_child(hud.button("Another", another, 96.0))
	left.add_child(nrow)
	left.add_child(UiKit.spacer(8))
	left.add_child(UiKit.caption("Occupation — what you did before", UiTheme.DIM, 12))
	var group := ButtonGroup.new()
	for o in data().get("occupations", []):
		var card := Card.new(String(o.name), String(o.desc), "%+d" % int(o.points) if int(o.points) != 0 else "0", UiTheme.good() if int(o.points) > 0 else (UiTheme.bad() if int(o.points) < 0 else UiTheme.DIM), 58.0)
		card.button_group = group; card.button_pressed = String(o.id) == _occ
		var oid := String(o.id)
		card.pressed.connect(func() -> void:
			hud.click()
			_occ = oid
			_create_update())
		left.add_child(card)
	cols.add_child(left)

	# what you are like
	var mid := VBoxContainer.new(); mid.size_flags_horizontal = Control.SIZE_EXPAND_FILL; mid.add_theme_constant_override("separation", 6)
	var th := HBoxContainer.new()
	th.add_child(UiKit.caption("Traits — good ones cost points, flaws pay for them", UiTheme.DIM, 12))
	th.add_child(UiKit.spacer(0, 0, true))
	th.add_child(UiKit.caption("Points left", UiTheme.DIM, 12))
	_c_points = UiKit.heading("0", 26, UiTheme.good()); _c_points.add_theme_font_override("font", UiTheme.font("num"))
	_c_points.custom_minimum_size = Vector2(48, 0); _c_points.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	th.add_child(_c_points)
	mid.add_child(th)
	var tcols := HBoxContainer.new(); tcols.add_theme_constant_override("separation", 10); tcols.size_flags_vertical = Control.SIZE_EXPAND_FILL
	var good := VBoxContainer.new(); good.size_flags_horizontal = Control.SIZE_EXPAND_FILL; good.add_theme_constant_override("separation", 4)
	var bad := VBoxContainer.new(); bad.size_flags_horizontal = Control.SIZE_EXPAND_FILL; bad.add_theme_constant_override("separation", 4)
	for t in data().get("traits", []):
		var cost := int(t.cost)
		var tc := Card.new(String(t.name), String(t.desc), ("-%d" % cost) if cost > 0 else ("+%d" % -cost), UiTheme.warn() if cost > 0 else UiTheme.good(), 50.0)
		tc.button_pressed = _traits.has(String(t.id))
		var tid := String(t.id)
		tc.toggled.connect(func(on: bool) -> void:
			hud.click()
			if on: _traits[tid] = true
			else: _traits.erase(tid)
			_create_update())
		_c_trait_btns[tid] = tc
		(good if cost > 0 else bad).add_child(tc)
	tcols.add_child(good); tcols.add_child(bad)
	mid.add_child(tcols)
	cols.add_child(mid)

	# what that adds up to
	var right := VBoxContainer.new(); right.custom_minimum_size = Vector2(330, 0); right.add_theme_constant_override("separation", 8)
	var box := UiKit.Frame.new(Color(1, 1, 1, 0.025), 16.0); box.marks = false; box.grain = 0.0; box.size_flags_vertical = Control.SIZE_EXPAND_FILL
	_c_summary = VBoxContainer.new(); _c_summary.add_theme_constant_override("separation", 6)
	box.add_child(_c_summary); right.add_child(box)
	_c_note = UiKit.body("", 13, UiTheme.bad(), true); _c_note.custom_minimum_size = Vector2(320, 0)
	right.add_child(_c_note)
	_c_begin = _big_button("Begin — day 1", func() -> void: _begin_game(), true)
	_c_begin.custom_minimum_size = Vector2(0, 52); _c_begin.add_theme_font_size_override("font_size", 19)
	right.add_child(_c_begin)
	var back := _big_button("Back", func() -> void: _show_page(""))
	back.custom_minimum_size = Vector2(0, 38)
	right.add_child(back)
	cols.add_child(right)
	_create_update()
	_c_begin.grab_focus.call_deferred()

func _create_update() -> void:
	var d := data()
	# a trait rules out its opposites
	var blocked := {}
	for t in d.get("traits", []):
		if _traits.has(String(t.id)):
			for x in t.get("excl", []): blocked[String(x)] = true
		for x in t.get("excl", []):
			if _traits.has(String(x)): blocked[String(t.id)] = true
	for id in _c_trait_btns:
		var b: Button = _c_trait_btns[id]
		var off: bool = blocked.has(id) and not _traits.has(id)
		if b.disabled != off:
			b.disabled = off; b.queue_redraw()
	var pts := points_left()
	_c_points.text = "%+d" % pts if pts != 0 else "0"
	_c_points.add_theme_color_override("font_color", UiTheme.bad() if pts < 0 else UiTheme.good())
	_c_begin.disabled = pts < 0
	_c_note.text = "You have spent %d more %s than you have. Drop a trait or take a flaw." % [-pts, "point" if pts == -1 else "points"] if pts < 0 else ""
	_c_note.visible = pts < 0
	for c in _c_summary.get_children():
		c.queue_free()
	var occ: Dictionary = {}
	for o in d.get("occupations", []):
		if String(o.id) == _occ: occ = o
	_c_summary.add_child(UiKit.caption("You were", UiTheme.DIM, 12))
	_c_summary.add_child(UiKit.heading(String(occ.get("name", "")), 20, UiTheme.INK))
	_c_summary.add_child(UiKit.spacer(6))
	_c_summary.add_child(UiKit.caption("Skills you start with", UiTheme.DIM, 12))
	var bonus := skill_bonus(_occ, _traits.keys())
	var any := false
	for sk in d.get("skills", []):
		var lv := int(bonus.get(String(sk.id), 0))
		if lv == 0:
			continue
		any = true
		var r := HBoxContainer.new(); r.add_theme_constant_override("separation", 8)
		var ic := TextureRect.new(); ic.texture = hud.icon("skill_" + String(sk.id)); ic.custom_minimum_size = Vector2(18, 18)
		ic.expand_mode = TextureRect.EXPAND_IGNORE_SIZE; ic.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		var nl := UiKit.body(String(sk.name), 14, UiTheme.INK); nl.custom_minimum_size = Vector2(98, 0)
		var pips := UiKit.Pips.new(maxi(lv, 0), 0.0, 120.0, 8.0); pips.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		var num := UiKit.body("%+d" % lv, 14, UiTheme.good() if lv > 0 else UiTheme.bad()); num.custom_minimum_size = Vector2(26, 0); num.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
		r.add_child(ic); r.add_child(nl); r.add_child(pips); r.add_child(num)
		_c_summary.add_child(r)
	if not any:
		_c_summary.add_child(UiKit.body("None yet — everything is learned by doing.", 13, UiTheme.FAINT, true))
	_c_summary.add_child(UiKit.spacer(6))
	_c_summary.add_child(UiKit.caption("You carry", UiTheme.DIM, 12))
	for it in occ.get("items", []):
		var r2 := HBoxContainer.new(); r2.add_theme_constant_override("separation", 8)
		var ic2 := TextureRect.new(); ic2.texture = hud.icon("item_" + String(it[0])); ic2.custom_minimum_size = Vector2(22, 22)
		ic2.expand_mode = TextureRect.EXPAND_IGNORE_SIZE; ic2.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		r2.add_child(ic2)
		r2.add_child(UiKit.body(String(Items.def(String(it[0])).name) + (("  ×%d" % int(it[1])) if int(it[1]) > 1 else ""), 14, UiTheme.INK))
		_c_summary.add_child(r2)
	var rest := UiKit.body("The clothes you stand in, a flashlight, a little food and water.", 13, UiTheme.FAINT, true)
	rest.custom_minimum_size = Vector2(280, 0)
	_c_summary.add_child(rest)
	if not _traits.is_empty():
		_c_summary.add_child(UiKit.spacer(6))
		_c_summary.add_child(UiKit.caption("Traits", UiTheme.DIM, 12))
		var names: Array = []
		for t in d.get("traits", []):
			if _traits.has(String(t.id)): names.append(String(t.name))
		var tl := UiKit.body(", ".join(PackedStringArray(names)), 14, UiTheme.INK, true); tl.custom_minimum_size = Vector2(280, 0)
		_c_summary.add_child(tl)

## skill levels an occupation and a set of traits start you with (the web game's rules)
func skill_bonus(occupation: String, traits: Array) -> Dictionary:
	var out := {}
	for o in data().get("occupations", []):
		if String(o.id) == occupation:
			for k in o.skills: out[String(k)] = int(o.skills[k])
	for pair in [["athletic", "fitness", 2], ["unfit", "fitness", -2], ["strong", "strength", 2], ["weak", "strength", -2], ["handy", "carpentry", 1], ["handy", "engineering", 1]]:
		if traits.has(pair[0]):
			out[pair[1]] = int(out.get(pair[1], 0)) + int(pair[2])
	return out

func _begin_game() -> void:
	if points_left() < 0:
		return
	hud.new_game_setup = {"name": _name.strip_edges() if _name.strip_edges() != "" else "Survivor", "occupation": _occ, "traits": _traits.keys()}
	launch("new")

## the HUD's own transition into play: loading card up, hand over to the game, card away
func launch(action: String) -> void:
	if _busy:
		return
	_busy = true
	var tree := hud.get_tree()
	hud.show_loading(0.15, "Reading your save" if action == "continue" else "Setting out")
	await tree.process_frame
	await tree.process_frame
	hud.show_loading(0.6, "Waking the valley")
	await tree.process_frame
	hud.menu_action.emit(action)
	if action == "new":
		apply_setup()
	hud.show_loading(1.0, "Ready")
	await tree.create_timer(0.35).timeout
	hud.hide_loading()
	_busy = false

## makes sure the new survivor is who the player made, whether or not the game read new_game_setup itself
func apply_setup() -> void:
	var s := hud.new_game_setup
	var g = hud.game
	if s.is_empty() or g == null or g.player == null or String(g.get("state")) != "play":
		return
	var p: Player = g.player
	p.name = String(s.get("name", p.name))
	var occ := String(s.get("occupation", "unemployed"))
	var traits: Array = s.get("traits", [])
	var took_traits := false
	if p.traits.is_empty() and not traits.is_empty():
		for t in traits: p.traits[String(t)] = true
		took_traits = true
	if p.occupation != occ:
		p.occupation = occ
		for o in data().get("occupations", []):
			if String(o.id) == occ:
				for k in o.skills: p.bonus[String(k)] = int(o.skills[k])
				for it in o.items: p.inv.add(String(it[0]), int(it[1]))
		for st in p.inv.stacks:
			if (Items.def(st.id).has("weapon") or String(st.id) == "flashlight") and not p.hotbar.has(int(st.uid)):
				var free := p.hotbar.find(null)
				if free >= 0: p.hotbar[free] = int(st.uid)
	if took_traits:
		for pair in [["athletic", "fitness", 2], ["unfit", "fitness", -2], ["strong", "strength", 2], ["weak", "strength", -2], ["handy", "carpentry", 1], ["handy", "engineering", 1]]:
			if traits.has(pair[0]):
				p.bonus[pair[1]] = int(p.bonus.get(pair[1], 0)) + int(pair[2])

# ------------------------------------------------------------------ loading card
func build_loading() -> Control:
	var c := Control.new(); c.set_anchors_preset(Control.PRESET_FULL_RECT); c.mouse_filter = Control.MOUSE_FILTER_STOP
	var bg := ColorRect.new(); bg.set_anchors_preset(Control.PRESET_FULL_RECT); bg.color = Color(0.025, 0.027, 0.025)
	c.add_child(bg)
	var bd := UiHudParts.Backdrop.new(); bd.art = key_art(); bd.shade = 0.72 if bd.art != null else 0.0; bd.left = 0.55
	bd.mouse_filter = Control.MOUSE_FILTER_IGNORE
	c.add_child(bd)
	var col := VBoxContainer.new(); col.add_theme_constant_override("separation", 6)
	col.anchor_left = 0.075; col.anchor_right = 0.075; col.anchor_top = 1.0; col.anchor_bottom = 1.0
	col.grow_vertical = Control.GROW_DIRECTION_BEGIN
	col.offset_bottom = -64.0; col.offset_top = -64.0
	col.add_child(Logo.new(64))
	col.add_child(UiKit.spacer(18))
	var tipc := UiKit.caption("Worth knowing", UiTheme.ACCENT2, 12)
	var tip := UiKit.body("", 17, UiTheme.INK, true); tip.custom_minimum_size = Vector2(620, 54)
	col.add_child(UiKit.margin(tipc, 20, 0, 0, 0)); col.add_child(UiKit.margin(tip, 20, 0, 0, 0))
	col.add_child(UiKit.spacer(14))
	var bar := UiKit.Meter.new(620.0, 3.0, UiTheme.ACCENT); bar.back = Color(1, 1, 1, 0.1)
	col.add_child(UiKit.margin(bar, 20, 0, 0, 0))
	var row := HBoxContainer.new(); row.custom_minimum_size = Vector2(640, 0)
	var what := UiKit.caption("", UiTheme.DIM, 12); what.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var pct := UiKit.caption("", UiTheme.DIM, 12)
	row.add_child(UiKit.spacer(0, 12)); row.add_child(what); row.add_child(pct)
	col.add_child(row)
	c.add_child(col)
	c.set_meta("bar", bar); c.set_meta("what", what); c.set_meta("pct", pct); c.set_meta("tip", tip)
	c.set_meta("i", randi() % TIPS.size())
	tip.text = tip_text(int(c.get_meta("i")))
	c.set_meta("tipc", tipc)
	c.visible = false
	return c

## a tip, with the keys it mentions named as they are bound now
func tip_text(i: int) -> String:
	return String(TIPS[i % TIPS.size()]).replace("Q treats", Settings.key_name("bandage") + " treats").replace("Press M ", "Press %s " % Settings.key_name("map"))

func set_loading(c: Control, progress: float, text: String) -> void:
	var tips: bool = Settings.v("tips")
	(c.get_meta("tip") as Label).visible = tips; (c.get_meta("tipc") as Label).visible = tips
	(c.get_meta("bar") as UiKit.Meter).set_value(clampf(progress, 0.0, 1.0))
	(c.get_meta("what") as Label).text = text.to_upper()
	(c.get_meta("pct") as Label).text = "%d%%" % int(round(clampf(progress, 0.0, 1.0) * 100.0))

func next_tip(c: Control) -> void:
	var tip: Label = c.get_meta("tip")
	var i := (int(c.get_meta("i")) + 1) % TIPS.size()
	c.set_meta("i", i)
	var tw := tip.create_tween()
	tw.tween_property(tip, "modulate:a", 0.0, 0.3)
	tw.tween_callback(func() -> void: tip.text = tip_text(i))
	tw.tween_property(tip, "modulate:a", 1.0, 0.3)

# ------------------------------------------------------------------ pieces
## the title: wide-set capitals, the second part in amber, a short rule beneath
class Logo extends Control:
	var a := "RE"
	var b := "KINDLE"
	var fs := 104
	var col_a := UiTheme.INK
	var rule := true
	func _init(size_ := 104, first := "RE", second := "KINDLE", col := UiTheme.INK, with_rule := true) -> void:
		fs = size_; a = first; b = second; col_a = col; rule = with_rule
		mouse_filter = Control.MOUSE_FILTER_IGNORE
		var f := UiTheme.font("logo" if fs >= 90 else "title")
		custom_minimum_size = Vector2(f.get_string_size(a + b, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x + 24.0, f.get_height(fs) * 0.92 + 10.0)
	func _draw() -> void:
		var f := UiTheme.font("logo" if fs >= 90 else "title")
		var y := f.get_ascent(fs) - fs * 0.12
		var x := 18.0
		# the second part starts where it would in the whole word, so the letter spacing is even across the join
		var xb := x + f.get_string_size(a + b, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x - f.get_string_size(b, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
		var edge := maxi(4, fs / 10)
		draw_string_outline(f, Vector2(x, y), a, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, edge, Color(0, 0, 0, 0.3))
		if b != "":
			draw_string_outline(f, Vector2(xb, y), b, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, edge, Color(0, 0, 0, 0.3))
		draw_string(f, Vector2(x, y), a, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, col_a)
		if b != "":
			draw_string(f, Vector2(xb, y), b, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, UiTheme.ACCENT)
		if rule:
			draw_rect(Rect2(x + 2.0, size.y - 5.0, maxf(40.0, fs * 0.6), 2.0), UiTheme.ACCENT)

## a selectable card: a title, one line of description, a figure on the right
class Card extends Button:
	var title := ""
	var desc := ""
	var tag := ""
	var tag_col := UiTheme.DIM
	func _init(title_: String, desc_: String, tag_: String, tag_col_: Color, h := 56.0) -> void:
		title = title_; desc = desc_; tag = tag_; tag_col = tag_col_
		toggle_mode = true
		custom_minimum_size = Vector2(0, h)
		size_flags_horizontal = Control.SIZE_EXPAND_FILL
		tooltip_text = desc_
		add_theme_stylebox_override("normal", UiTheme.flat_m(Color(1, 1, 1, 0.03), UiTheme.LINE, 1, 12, 6))
		add_theme_stylebox_override("disabled", UiTheme.flat_m(Color(1, 1, 1, 0.01), Color(1, 1, 1, 0.04), 1, 12, 6))
	func _draw() -> void:
		var on := button_pressed
		var k := 0.4 if disabled else 1.0
		if on:
			draw_rect(Rect2(0, 0, 3, size.y), UiTheme.ACCENT)
		var tw := UiKit.text_w("num", tag, 16) + 14.0
		UiKit.text(self, "head", Vector2(14, size.y * 0.5 - 20.0), title.to_upper(), 15, Color(UiTheme.ACCENT2 if on else UiTheme.INK, k), HORIZONTAL_ALIGNMENT_LEFT, size.x - tw - 22.0, false)
		var f := UiTheme.font("body")
		draw_string(f, Vector2(14, size.y * 0.5 + 2.0 + f.get_ascent(13)), UiKit.fit("body", desc, 13, size.x - tw - 24.0), HORIZONTAL_ALIGNMENT_LEFT, -1, 13, Color(UiTheme.DIM, k))
		UiKit.text(self, "num", Vector2(size.x - tw, size.y * 0.5 - 11.0), tag, 16, Color(tag_col, k), HORIZONTAL_ALIGNMENT_RIGHT, tw - 12.0, false)
