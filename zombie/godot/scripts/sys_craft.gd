extends Node
## Crafting: the web game's recipes (data/recipes.json) made by hand, at a workbench, or — through sys_cook.gd —
## over a fire. N opens the window by hand (or at a workbench you are standing next to); E on a workbench opens
## it there. Each recipe wants its ingredients, its tools, sometimes a skill level and a station; making it
## takes time, and teaches the skill the web game says it teaches.

signal made(recipe: Dictionary)

const U := preload("res://scripts/sys_util.gd")
const CATS := [["all", "All", []], ["weapon", "Weapons", ["weapon", "ammo"]], ["tool", "Tools", ["tool"]], ["medical", "Medical", ["medical"]],
	["food", "Food", ["food", "drink"]], ["clothing", "Clothing", ["clothing"]], ["material", "Materials", ["material", "part", "resource", "misc", "seed", "book"]]]
const STATION_NAMES := {"hand": "by hand", "workbench": "a workbench", "campfire": "a campfire", "stove": "a stove"}

var game
var recipes: Array = []
var by_id: Dictionary = {}
var station := "hand"              # what the open window is working at
var piece = null                   # the workbench it was opened on
var cat := "all"
var crafted := 0

var _scroll: ScrollContainer = null
var _detail: VBoxContainer = null
var _bar: ProgressBar = null
var _bar_lbl: Label = null
var _shown := ""

func _ready() -> void:
	var data = World.read_json("res://data/recipes.json")
	if data is Array:
		recipes = data
	for r in recipes:
		by_id[String(r.id)] = r

# ------------------------------------------------------------------ rules
func _tier_ok(t: int) -> bool:
	var b = U.mod(game, "sys_build")
	return b == null or b.tier_ok(t)

func _tier_name(t: int) -> String:
	var b = U.mod(game, "sys_build")
	if b != null and t - 1 < b.milestones.size() and t >= 1:
		return String(b.milestones[t - 1].name)
	return "bigger town"

static func _a(word: String) -> String:
	return ("an " if word.substr(0, 1) in ["a", "e", "i", "o", "u"] else "a ") + word

## Why a recipe cannot be made at `at` ("" when it can). `fire` is the campfire or stove piece being cooked on.
func can_craft(r: Dictionary, at := "hand", fire = null) -> String:
	var p = game.player
	var st := String(r.station)
	if st != "hand" and st != at and not (at == "stove" and st == "campfire"):
		return "Needs %s" % String(STATION_NAMES.get(st, st))
	if r.has("tier") and not _tier_ok(int(r.tier)):
		return "Unlocks when your settlement becomes a %s" % _tier_name(int(r.tier))
	if r.has("skill") and p.level(String(r.skill[0])) < int(r.skill[1]):
		return "Requires %s %d" % [U.skill_name(String(r.skill[0])), int(r.skill[1])]
	for t in r.get("tools", []):
		if not p.inv.has_tool(String(t)):
			return "Needs %s" % _a(String(t))
	if st == "campfire" or st == "stove":
		if fire == null or fire.get("destroyed", false) or not fire.has("fire") or not fire.fire.lit:
			return "Light the stove first" if at == "stove" else "Light the campfire first"
	if st == "workbench" and piece != null and piece.get("destroyed", false):
		return "The workbench is gone"
	if not U.has_all(p.inv, U.cost_of(r.inp)):
		return "Missing ingredients"
	return ""

func craft_time(r: Dictionary) -> float:
	var sk: int = game.player.level(String(r.xp[0])) if r.has("xp") else 0
	return float(r.time) * (1.0 - sk * 0.05)

## Start making a recipe. Returns false (and says why) when it cannot be made.
func craft(r: Dictionary, at := "hand", fire = null) -> bool:
	if game.action != null:
		return false
	var why := can_craft(r, at, fire)
	if why != "":
		game.hud.notify(why + ".", "bad"); Sfx.play("ui_error")
		return false
	var p = game.player
	var out_id := String(r.out[0]); var out_n := int(r.out[1])
	var out_name := String(Items.def(out_id).name)
	var verb := "Cooking" if at in ["campfire", "stove"] else "Crafting"
	Sfx.play("craft")
	game.start_action("%s %s…" % [verb, out_name], craft_time(r), func() -> void:
		if not U.take_all(p.inv, U.cost_of(r.inp)):
			return
		p.inv.add(out_id, out_n)
		if r.has("xp"):
			p.gain_xp(String(r.xp[0]), float(r.xp[1]), game.game_min)
		crafted += 1
		Sfx.play("pickup", {"volume": 0.5})
		game.hud.notify("%s %d× %s" % ["Cooked" if verb == "Cooking" else "Crafted", out_n, out_name], "good")
		made.emit(r)
		_rebuild(), true)
	_rebuild()
	return true

# ------------------------------------------------------------------ module interface
func mod_input(ev: InputEvent) -> bool:
	if game.state != "play" or game.player.sleeping:
		return false
	if (ev is InputEventKey or ev is InputEventMouseButton) and ev.pressed and not ev.is_echo() and Settings.is_action(ev, "craft"):
		var hud = game.hud
		if hud.is_open():
			if hud.window_kind == "craft":
				U.close(game)
				return true
			if hud.window_kind in ["main", "pause", "dead"]:
				return false
		var bench = null
		var b = U.mod(game, "sys_build")
		if b != null:
			bench = b.station_near(game.player.pos, 3.0, "workbench")
		open("workbench" if bench != null else "hand", bench)
		return true
	return false

func mod_process(_dt: float, _dt_min: float, _ui_open: bool) -> void:
	if _bar == null or not is_instance_valid(_bar):
		return
	var a = game.action
	var busy: bool = a != null and (String(a.label).begins_with("Crafting") or String(a.label).begins_with("Cooking"))
	_bar.visible = busy; _bar_lbl.visible = busy
	if busy:
		_bar.value = float(a.t) / maxf(0.01, float(a.dur)) * 100.0
		_bar_lbl.text = String(a.label)

# ------------------------------------------------------------------ the window
func open(at := "hand", bench = null) -> void:
	station = at; piece = bench
	_shown = ""
	_build_window(true)

func _rebuild() -> void:
	if game.hud.is_open() and game.hud.window_kind == "craft":
		_build_window(false)

func _listed() -> Array:
	var filter: Array = []
	for c in CATS:
		if c[0] == cat: filter = c[2]
	var out: Array = []
	for r in recipes:
		var st := String(r.station)
		if st != "hand" and st != "workbench":
			continue
		if not filter.is_empty() and not (String(Items.def(String(r.out[0])).cat) in filter):
			continue
		out.append(r)
	return out

func _build_window(sound: bool) -> void:
	var hud = game.hud
	var keep := 0
	if _scroll != null and is_instance_valid(_scroll):
		keep = _scroll.scroll_vertical
	var v: VBoxContainer = U.open(game, "craft", 1000.0, 620.0, "Crafting — at the workbench" if station == "workbench" else "Crafting", sound)
	var head := HBoxContainer.new(); head.add_theme_constant_override("separation", 6)
	for c in CATS:
		var key := String(c[0])
		head.add_child(U.tab_button(hud, String(c[1]), cat == key, func() -> void:
			cat = key; Sfx.play("ui_click"); _build_window(false)))
	v.add_child(head)
	var where := "At the workbench: everything you can make by hand, and the bench work besides." if station == "workbench" else "By hand. A workbench makes tools, metalwork and ammunition; a campfire or stove cooks."
	v.add_child(hud.label(where, 13, Hud.DIM))
	var cols := HBoxContainer.new(); cols.add_theme_constant_override("separation", 14); cols.size_flags_vertical = Control.SIZE_EXPAND_FILL
	v.add_child(cols)
	_scroll = ScrollContainer.new(); _scroll.size_flags_horizontal = Control.SIZE_EXPAND_FILL; _scroll.custom_minimum_size = Vector2(590, 420)
	_scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	var rows := VBoxContainer.new(); rows.size_flags_horizontal = Control.SIZE_EXPAND_FILL; rows.add_theme_constant_override("separation", 3)
	_scroll.add_child(rows); cols.add_child(_scroll)
	# what can be made now, then what is short of something, then what wants a station you are not at
	var ready: Array = []; var short: Array = []; var elsewhere: Array = []
	for r in _listed():
		var why := can_craft(r, station)
		if why == "": ready.append([r, why])
		elif why.begins_with("Needs a workbench"): elsewhere.append([r, why])
		else: short.append([r, why])
	var first = null
	for group in [["Ready to make", ready], ["Missing something", short], ["At a workbench", elsewhere]]:
		if group[1].is_empty():
			continue
		rows.add_child(U.caption(hud, "%s  ·  %d" % [group[0], group[1].size()]))
		for e in group[1]:
			if first == null: first = e[0]
			rows.add_child(_row(e[0], String(e[1])))
	if ready.is_empty() and short.is_empty() and elsewhere.is_empty():
		rows.add_child(hud.label("Nothing of that kind can be made here.", 15, Hud.DIM))
	var side := PanelContainer.new(); side.custom_minimum_size = Vector2(340, 0)
	side.add_theme_stylebox_override("panel", Hud.box(Color(1, 1, 1, 0.03), 6))
	_detail = VBoxContainer.new(); _detail.add_theme_constant_override("separation", 6)
	side.add_child(_detail); cols.add_child(side)
	var show = by_id.get(_shown, first)
	if show != null:
		_show(show)
	var foot := HBoxContainer.new(); foot.add_theme_constant_override("separation", 12)
	var hint: Label = hud.label("Click a recipe to make it   ·   %s / Esc: close" % Settings.key_name("craft"), 12, Hud.DIM); hint.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	foot.add_child(hint)
	_bar_lbl = hud.label("", 13); _bar_lbl.visible = false
	_bar = hud._bar(Hud.ACCENT, 240.0, 8.0); _bar.visible = false; _bar.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	foot.add_child(_bar_lbl); foot.add_child(_bar)
	v.add_child(foot)
	if keep > 0:
		_scroll.set_deferred("scroll_vertical", keep)

func _row(r: Dictionary, why: String) -> Control:
	var hud = game.hud
	var out_id := String(r.out[0])
	var b: Button = U.row_button(hud, 50.0)
	var h := HBoxContainer.new(); h.set_anchors_preset(Control.PRESET_FULL_RECT); h.offset_left = 8; h.offset_right = -10
	h.add_theme_constant_override("separation", 10); h.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_child(U.item_icon(hud, out_id, 34.0))
	var mid := VBoxContainer.new(); mid.size_flags_horizontal = Control.SIZE_EXPAND_FILL; mid.alignment = BoxContainer.ALIGNMENT_CENTER
	mid.add_theme_constant_override("separation", 0); mid.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var nm := String(Items.def(out_id).name) + (" ×%d" % int(r.out[1]) if int(r.out[1]) > 1 else "")
	mid.add_child(hud.label(nm, 16, Hud.INK if why == "" else Hud.DIM))
	mid.add_child(U.cost_row(hud, game.player.inv, U.cost_of(r.inp), 12))
	h.add_child(mid)
	var note: Label = hud.label("%d s" % int(ceil(craft_time(r))) if why == "" else why, 12, Hud.DIM if why == "" else Hud.BAD)
	note.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	h.add_child(note)
	b.add_child(h)
	b.mouse_entered.connect(func() -> void: _show(r))
	b.pressed.connect(func() -> void:
		_shown = String(r.id)
		craft(r, station))
	return b

func _show(r: Dictionary) -> void:
	if _detail == null or not is_instance_valid(_detail):
		return
	_shown = String(r.id)
	for c in _detail.get_children():
		c.queue_free()
	var hud = game.hud
	var p = game.player
	var d := Items.def(String(r.out[0]))
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 12)
	top.add_child(U.item_icon(hud, String(r.out[0]), 56.0))
	var tv := VBoxContainer.new(); tv.alignment = BoxContainer.ALIGNMENT_CENTER
	tv.add_child(hud.label(String(d.name), 20))
	tv.add_child(hud.label("Makes %d   ·   %s" % [int(r.out[1]), String(STATION_NAMES.get(String(r.station), "")).trim_prefix("a ")], 13, Hud.DIM))
	top.add_child(tv); _detail.add_child(top)
	var desc: Label = hud.label(String(d.desc), 14, Hud.DIM); desc.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; desc.custom_minimum_size = Vector2(316, 0)
	_detail.add_child(desc)
	_detail.add_child(U.caption(hud, "Ingredients"))
	for k in r.inp:
		var have: int = p.inv.count(String(k)); var need := int(r.inp[k])
		var line := HBoxContainer.new(); line.add_theme_constant_override("separation", 8)
		line.add_child(U.item_icon(hud, String(k), 22.0))
		var nl: Label = hud.label(String(Items.def(String(k)).name), 15); nl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		line.add_child(nl)
		line.add_child(hud.label("%d / %d" % [have, need], 15, Hud.GOOD if have >= need else Hud.BAD))
		_detail.add_child(line)
	if r.has("tools"):
		_detail.add_child(U.caption(hud, "Tools"))
		for t in r.tools:
			var has: bool = p.inv.has_tool(String(t))
			_detail.add_child(hud.label("%s   %s" % [String(t).capitalize(), "in your pack" if has else "you have none"], 15, Hud.INK if has else Hud.BAD))
	if r.has("skill"):
		var ok: bool = p.level(String(r.skill[0])) >= int(r.skill[1])
		_detail.add_child(hud.label("Requires %s %d   ·   yours is %d" % [U.skill_name(String(r.skill[0])), int(r.skill[1]), p.level(String(r.skill[0]))], 14, Hud.INK if ok else Hud.BAD))
	var tail := "Takes %d s" % int(ceil(craft_time(r)))
	if r.has("xp"):
		tail += "   ·   +%d %s" % [int(r.xp[1]), U.skill_name(String(r.xp[0]))]
	_detail.add_child(hud.label(tail, 13, Hud.DIM))
	var why := can_craft(r, station)
	if why != "":
		var wl: Label = hud.label(why, 14, Hud.BAD); wl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; wl.custom_minimum_size = Vector2(316, 0)
		_detail.add_child(wl)
