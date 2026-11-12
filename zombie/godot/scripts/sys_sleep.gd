extends Node
## Sleeping: in the town's beds and on a bedroll you have laid out. Time runs fast while you sleep (the game
## loop does that for player.sleeping), the screen goes dark, fatigue falls and wounds heal faster (Stats).
## You wake when rested, in the morning once you have had enough, when hunger or thirst gets too bad, with a
## start when the dead come close, strike you, or something crashes nearby — or when you press E or Space.

signal fell_asleep
signal woke(by_danger: bool)

const U := preload("res://scripts/sys_util.gd")
const TIRED := 30.0                # fatigue below this: not tired enough
const SAFE_RADIUS := 15.0          # no sleeping with the dead this close
const WAKE_RADIUS := 6.0           # one this close wakes you
const NOISE_RADIUS := 25.0         # a blow on a door or wall this close wakes you
const CELL := 8.0

var game
var beds: Dictionary = {}          # Vector2i cell -> [{min, max, hospital}]
var slept_from := 0.0

var _asleep := false
var _scan_t := 0.0
var _wired := false
var _layer: CanvasLayer
var _fade: ColorRect
var _box: VBoxContainer
var _title: Label
var _sub: Label

func _ready() -> void:
	_index_beds()
	_layer = CanvasLayer.new(); _layer.layer = 4
	add_child(_layer)
	_fade = ColorRect.new(); _fade.set_anchors_preset(Control.PRESET_FULL_RECT); _fade.color = Color(0.01, 0.012, 0.02, 0.0)
	_fade.mouse_filter = Control.MOUSE_FILTER_IGNORE; _fade.visible = false
	_layer.add_child(_fade)
	_box = VBoxContainer.new(); _box.set_anchors_preset(Control.PRESET_FULL_RECT); _box.alignment = BoxContainer.ALIGNMENT_CENTER
	_box.mouse_filter = Control.MOUSE_FILTER_IGNORE; _box.visible = false
	_title = _text("Sleeping…", 40, Color(0.91, 0.88, 0.8))
	_sub = _text("", 16, Color(0.62, 0.6, 0.54))
	_box.add_child(_title); _box.add_child(_sub)
	_layer.add_child(_box)

func _text(t: String, size: int, col: Color) -> Label:
	var l := Label.new(); l.text = t; l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	l.add_theme_font_size_override("font_size", size); l.add_theme_color_override("font_color", col)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return l

## the beds the town was furnished with (the exporter's prop list); each becomes a box to look at
func _index_beds() -> void:
	var data = World.read_json("res://assets/world/props.json")
	var sizes = World.read_json("res://assets/models/props/index.json")
	if not (data is Dictionary) or not (sizes is Dictionary):
		return
	for e in data.get("entries", []):
		var kind := String(e.t).trim_prefix("p_")
		if kind != "bed" and kind != "hospital_bed":
			continue
		var m: Array = e.m
		var sz: Array = sizes[kind].size
		var hx := (absf(float(m[0])) * float(sz[0]) + absf(float(m[8])) * float(sz[2])) * 0.5
		var hz := (absf(float(m[2])) * float(sz[0]) + absf(float(m[10])) * float(sz[2])) * 0.5
		var pos := Vector3(float(m[12]), float(m[13]), float(m[14]))
		var key := Vector2i(int(floor(pos.x / CELL)), int(floor(pos.z / CELL)))
		if not beds.has(key):
			beds[key] = []
		beds[key].append({"min": Vector3(pos.x - hx, pos.y, pos.z - hz), "max": Vector3(pos.x + hx, pos.y + float(sz[1]), pos.z + hz), "hospital": kind == "hospital_bed"})

func bed_count() -> int:
	var n := 0
	for k in beds:
		n += beds[k].size()
	return n

# ------------------------------------------------------------------ rules
## Why you cannot sleep now ("" when you can).
func why_not() -> String:
	var st = game.player.stats
	if st.bleeding() > 0.05:
		return "You can't sleep while you are bleeding. Bandage the wound first."
	if game.zombies != null and game.zombies.nearest(game.player.pos, SAFE_RADIUS) != null:
		return "You can't sleep with zombies nearby!"
	if st.fatigue < TIRED:
		return "You're not tired enough to sleep."
	if st.hunger < 10.0:
		return "You are too hungry to sleep. Eat something first."
	if st.thirst < 10.0:
		return "You are too thirsty to sleep. Drink something first."
	return ""

func bed_prompt(what := "bed") -> String:
	var st = game.player.stats
	var why := ""
	if st.bleeding() > 0.05: why = "you are bleeding"
	elif game.zombies != null and game.zombies.nearest(game.player.pos, SAFE_RADIUS) != null: why = "zombies nearby"
	elif st.fatigue < TIRED: why = "you are not tired"
	elif st.hunger < 10.0: why = "you are too hungry"
	elif st.thirst < 10.0: why = "you are too thirsty"
	if why != "":
		return "%s  ·  %s" % [what.substr(0, 1).to_upper() + what.substr(1), why]
	return "[E]  Sleep on the %s  ·  until rested" % what if what == "bedroll" else "[E]  Sleep in the %s  ·  until rested" % what

func try_sleep() -> bool:
	var p = game.player
	if p.sleeping:
		return true
	var why := why_not()
	if why != "":
		game.hud.notify(why, "bad" if why.contains("zombies") or why.contains("bleeding") else "")
		return false
	if game.hud.is_open():
		game.hud.close_window(); Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
	game.cancel_action()
	p.sleeping = true; p.vel = Vector3.ZERO
	_asleep = true; slept_from = game.game_min
	Sfx.play("sleep")
	game.save_game(true)
	fell_asleep.emit()
	return true

func wake(by_danger: bool, why := "") -> void:
	var p = game.player
	var was: bool = p.sleeping or _asleep
	p.sleeping = false; _asleep = false
	if not was:
		return
	if by_danger:
		p.stats.panic = maxf(p.stats.panic, 70.0)
		game.hud.notify(why if why != "" else "You wake with a start — something is here!", "bad")
	else:
		var hrs: float = game.hours()
		var slept: float = (game.game_min - slept_from) / 60.0
		var lead := why if why != "" else ("You wake up rested." if p.stats.fatigue < 10.0 else "You wake up.")
		game.hud.notify("%s  %02d:%02d, day %d — you slept %s." % [lead, int(hrs), int(fmod(hrs, 1.0) * 60.0), game.day(), _span(slept)], "")
	woke.emit(by_danger)

func _span(hours: float) -> String:
	if hours < 1.0:
		return "%d minutes" % int(round(hours * 60.0))
	return "%.1f hours" % hours

# ------------------------------------------------------------------ module interface
func mod_started(_new_game: bool) -> void:
	_asleep = false
	if not _wired and game.zombies != null:
		if game.zombies.has_signal("structure_hit"): game.zombies.structure_hit.connect(_on_structure_hit)
		if game.zombies.has_signal("howl"): game.zombies.howl.connect(_on_howl)
		_wired = true

func mod_blocks_control() -> bool:
	return game.player != null and game.player.sleeping

func mod_input(ev: InputEvent) -> bool:
	if game.state != "play" or not game.player.sleeping:
		return false
	if ev is InputEventKey and ev.pressed and not ev.echo:
		var k: int = ev.physical_keycode
		if k == KEY_ESCAPE or k == KEY_F5:
			return false                       # pausing and saving still work
		if k == KEY_E or k == KEY_SPACE:
			wake(false)
		return true                            # nothing else reaches a sleeper
	return ev is InputEventMouseButton

func mod_process(dt: float, _dt_min: float, _ui_open: bool) -> void:
	var p = game.player
	if _asleep and not p.sleeping:
		wake(true)                             # the game woke you: you were struck
	elif p.sleeping:
		_asleep = true
		var st = p.stats
		var hrs: float = game.hours()
		if st.fatigue <= 2.0:
			wake(false)
		elif hrs > 7.0 and hrs < 8.0 and st.fatigue < TIRED:
			wake(false, "Morning.")
		elif st.hunger < 5.0 or st.thirst < 5.0:
			wake(false, "Hunger wakes you." if st.hunger < 5.0 else "Thirst wakes you.")
		else:
			_scan_t -= dt
			if _scan_t <= 0.0:
				_scan_t = 0.2
				if game.zombies != null and game.zombies.nearest(p.pos, WAKE_RADIUS) != null:
					wake(true)
	var target := 0.92 if p.sleeping else 0.0
	if _fade.color.a != target or _fade.visible:
		_fade.color.a = lerpf(_fade.color.a, target, 1.0 - exp(-dt * 2.0)) if absf(_fade.color.a - target) > 0.004 else target
		_fade.visible = _fade.color.a > 0.0
		_box.visible = p.sleeping
		_box.modulate.a = clampf(_fade.color.a / 0.92, 0.0, 1.0)
		if p.sleeping:
			_sub.text = "Fatigue %d%%   ·   E or Space to get up" % int(round(p.stats.fatigue))

func _process(_dt: float) -> void:
	# mod_process stops when the game leaves play (death, the menu): do not leave the dark behind
	if _fade.visible and game != null and game.state != "play":
		_fade.visible = false; _fade.color.a = 0.0; _box.visible = false; _asleep = false

func mod_interact(eye: Vector3, fwd: Vector3, reach: float, wall_t: float) -> Dictionary:
	if game.player.sleeping:
		return {"t": 0.0, "prompt": "", "act": func() -> void: wake(false)}
	var pp: Vector3 = game.player.pos
	var cx := int(floor(pp.x / CELL)); var cz := int(floor(pp.z / CELL))
	var best_t := reach
	var found := false
	var hospital := false
	for i in range(cx - 1, cx + 2):
		for j in range(cz - 1, cz + 2):
			for b in beds.get(Vector2i(i, j), []):
				var t := U.ray_box(eye, fwd, b.min - Vector3(0.05, 0, 0.05), b.max + Vector3(0.05, 0.05, 0.05), reach)
				if t < best_t and t <= wall_t + 0.6:
					best_t = t; found = true; hospital = b.hospital
	if not found:
		return {}
	return {"t": best_t, "prompt": bed_prompt("hospital bed" if hospital else "bed"), "act": func() -> void: try_sleep()}

# ------------------------------------------------------------------ noises in the night
func _on_structure_hit(ref, _dmg) -> void:
	if game.state != "play" or not game.player.sleeping or not (ref is Dictionary):
		return
	var d = ref.get("data", ref)
	var x = d.get("cx"); var z = d.get("cz")
	if x == null or z == null:
		return
	if Vector2(float(x) - game.player.pos.x, float(z) - game.player.pos.z).length() < NOISE_RADIUS:
		wake(true, "A crash close by jolts you awake!")

func _on_howl(pos: Vector3, radius: float, _z = null) -> void:
	if game.state == "play" and game.player.sleeping and pos.distance_to(game.player.pos) < maxf(radius, 40.0):
		wake(true, "A howl tears you out of sleep!")
