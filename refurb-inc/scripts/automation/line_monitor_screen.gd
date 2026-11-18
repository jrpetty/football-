class_name LineMonitorScreen
extends Control
## What the line monitor shows: machine moves per hour, kit on the belts, every machine's state (busy, waiting,
## starved, idle) with its load, and the bottleneck outlined in red with a plain-English line at the bottom.
## Drawn procedurally from Automation.line_status() into a small SubViewport; the view decides when to redraw.

const BG := Color(0.035, 0.045, 0.07)
const HEAD := Color(0.07, 0.2, 0.42)
const CARD := Color(0.075, 0.095, 0.135)
const TEXT := Color(0.93, 0.95, 0.98)
const MUTED := Color(0.6, 0.66, 0.74)
const RED := Color(1.0, 0.3, 0.25)
const STATE_COLOURS := {"busy": Color(0.3, 0.86, 0.46), "waiting": Color(1.0, 0.68, 0.2), "starved": Color(0.47, 0.64, 0.98), "idle": Color(0.5, 0.53, 0.58)}
const FLOW := {"flowing": ["FLOWING", Color(0.3, 0.86, 0.46)], "waiting": ["BACKING UP", Color(1.0, 0.68, 0.2)], "stopped": ["STOPPED", Color(1.0, 0.3, 0.25)], "idle": ["IDLE", Color(0.5, 0.53, 0.58)]}
const ORDER := {"waiting": 1, "busy": 2, "starved": 3, "idle": 4}
const COLS := 4
const ROWS := 3

var data := {}
var title := "LINE MONITOR"
var _font: Font


func _ready() -> void:
	_font = ThemeDB.fallback_font
	set_anchors_preset(Control.PRESET_FULL_RECT)


func _draw() -> void:
	var w := size.x
	var h := size.y
	draw_rect(Rect2(0, 0, w, h), BG)
	if _font == null:
		_font = ThemeDB.fallback_font
	# Header: title, overall flow, premises clock.
	draw_rect(Rect2(0, 0, w, 54), HEAD)
	_t(Vector2(20, 37), title, 26, TEXT)
	var flow: Array = FLOW.get(str(data.get("flow", "idle")), FLOW.idle)
	var tw := _font.get_string_size(title, HORIZONTAL_ALIGNMENT_LEFT, -1, 26).x
	_pill(Vector2(36 + tw, 15), str(flow[0]), flow[1])
	var clock := "%s  %s" % [Game.state.weekday_name().substr(0, 3), Game.state.clock_text()] if Game.state else ""
	_t(Vector2(w - 20 - _font.get_string_size(clock, HORIZONTAL_ALIGNMENT_LEFT, -1, 20).x, 35), clock, 20, Color(0.8, 0.88, 1.0))
	# KPI tiles.
	var gap := 12.0
	var tile_w := (w - 40.0 - gap * 3.0) / 4.0
	var y := 66.0
	var today: Dictionary = data.get("today", {})
	_tile(Rect2(20, y, tile_w, 100), "MOVES / HOUR", str(int(data.get("mph", 0))), "", data.get("spark", []))
	_tile(Rect2(20 + (tile_w + gap), y, tile_w, 100), "ON THE BELTS", str(int(data.get("on_belts", 0))), "%d moving · %d stopped" % [int(data.get("moving", 0)), int(data.get("stopped", 0))])
	var states := {}
	for m in data.get("machines", []):
		states[m.state] = int(states.get(m.state, 0)) + 1
	_tile(Rect2(20 + (tile_w + gap) * 2.0, y, tile_w, 100), "MACHINES BUSY", "%d / %d" % [int(data.get("busy", 0)), int(data.get("total", 0))], "%d waiting · %d starved" % [int(states.get("waiting", 0)), int(states.get("starved", 0))])
	_tile(Rect2(20 + (tile_w + gap) * 3.0, y, tile_w, 100), "TODAY", "%d" % int(today.get("arm", 0) + today.get("belt_in", 0) + today.get("split", 0) + today.get("sort", 0)),
		"Tested %d · Imaged %d · Packed %d" % [int(today.get("rack_test", 0)), int(today.get("rack_image", 0)), int(today.get("robot_pack", 0))])
	# Machine cards: the bottleneck first, then whatever needs attention.
	var rows: Array = (data.get("machines", []) as Array).duplicate()
	var bn := str(data.get("bottleneck", ""))
	rows.sort_custom(func(a, b):
		var ka := 0 if a.fid == bn else int(ORDER.get(a.state, 5))
		var kb := 0 if b.fid == bn else int(ORDER.get(b.state, 5))
		return ka < kb if ka != kb else str(a.label) < str(b.label))
	var card_w := tile_w
	var card_h := 84.0
	var top := y + 112.0
	if rows.is_empty():
		_t(Vector2(20, top + 40), "No machines on the line yet — place conveyors, robot arms and racks.", 20, MUTED)
	var max_cards := COLS * ROWS
	for i in mini(rows.size(), max_cards):
		var r := Rect2(20 + (card_w + gap) * (i % COLS), top + (card_h + 10.0) * float(i / COLS), card_w, card_h)
		if i == max_cards - 1 and rows.size() > max_cards:
			draw_rect(r, CARD)
			_t(r.position + Vector2(16, 50), "+%d more machines" % (rows.size() - max_cards + 1), 20, MUTED)
			break
		_card(r, rows[i], rows[i].fid == bn)
	# Footer: the bottleneck in plain English.
	draw_rect(Rect2(0, h - 46, w, 46), Color(0.055, 0.065, 0.1))
	var txt := str(data.get("bottleneck_text", ""))
	var bad := bn != "" or txt.begins_with("Nothing on the line") or txt.begins_with("Kit is stopped")
	var warn := not bad and txt.contains("waiting for a person")
	var col := RED if bad else (STATE_COLOURS.waiting if warn else STATE_COLOURS.busy)
	draw_rect(Rect2(0, h - 46, 8, 46), col)
	_t(Vector2(22, h - 16), ("BOTTLENECK  " if bn != "" else "") + txt, 19, col.lerp(TEXT, 0.35), w - 44)


func _tile(r: Rect2, label: String, big: String, sub: String, spark: Array = []) -> void:
	draw_rect(r, CARD)
	_t(r.position + Vector2(14, 24), label, 14, MUTED)
	_t(r.position + Vector2(14, 66), big, 38, TEXT)
	if sub != "":
		_t(r.position + Vector2(14, 89), sub, 14, MUTED, r.size.x - 24)
	if not spark.is_empty():
		var mx := 1
		for v in spark:
			mx = maxi(mx, int(v))
		var bw := 9.0
		var x0 := r.position.x + r.size.x - 14.0 - spark.size() * (bw + 3.0)
		for i in spark.size():
			var bh := maxf(2.0, 52.0 * float(spark[i]) / float(mx))
			var c := Color(0.35, 0.65, 1.0) if i == spark.size() - 1 else Color(0.3, 0.45, 0.7)
			draw_rect(Rect2(x0 + i * (bw + 3.0), r.position.y + 84.0 - bh, bw, bh), c)


func _card(r: Rect2, m: Dictionary, bottleneck: bool) -> void:
	var col: Color = STATE_COLOURS.get(str(m.state), STATE_COLOURS.idle)
	draw_rect(r, CARD)
	draw_rect(Rect2(r.position, Vector2(6, r.size.y)), col)
	var chip := str(m.state).to_upper()
	var cw := _font.get_string_size(chip, HORIZONTAL_ALIGNMENT_LEFT, -1, 13).x + 14.0
	_t(r.position + Vector2(16, 24), str(m.label), 17, TEXT, r.size.x - cw - 30)
	draw_rect(Rect2(r.position.x + r.size.x - cw - 10, r.position.y + 9, cw, 20), col.darkened(0.55))
	_t(Vector2(r.position.x + r.size.x - cw - 3, r.position.y + 24), chip, 13, col.lightened(0.2))
	_t(r.position + Vector2(16, 47), str(m.detail), 13, MUTED, r.size.x - 26)
	var util := int(m.get("util", -1))
	if util >= 0:
		var bx := r.position.x + 16.0
		var bw := r.size.x - 74.0
		draw_rect(Rect2(bx, r.position.y + 62, bw, 9), Color(1, 1, 1, 0.08))
		draw_rect(Rect2(bx, r.position.y + 62, bw * clampf(util / 100.0, 0.0, 1.0), 9), RED if bottleneck else col)
		_t(Vector2(bx + bw + 8, r.position.y + 72), "%d%%" % util, 13, TEXT)
	if int(m.get("queue", 0)) > 0:
		_t(r.position + Vector2(16, 80), "%d queued for it" % int(m.queue), 11, MUTED)
	if bottleneck:
		draw_rect(r.grow(1.5), RED, false, 3.0)
		var tag := "BOTTLENECK"
		var tw := _font.get_string_size(tag, HORIZONTAL_ALIGNMENT_LEFT, -1, 12).x + 12.0
		draw_rect(Rect2(r.position.x + r.size.x - tw - 10, r.position.y + r.size.y - 22, tw, 17), RED)
		_t(Vector2(r.position.x + r.size.x - tw - 4, r.position.y + r.size.y - 9), tag, 12, Color.WHITE)


func _pill(pos: Vector2, text: String, col: Color) -> void:
	var tw := _font.get_string_size(text, HORIZONTAL_ALIGNMENT_LEFT, -1, 15).x + 22.0
	draw_rect(Rect2(pos, Vector2(tw, 24)), col.darkened(0.45))
	draw_circle(pos + Vector2(11, 12), 4.0, col)
	_t(pos + Vector2(18, 18), text, 15, col.lightened(0.25))


func _t(pos: Vector2, s: String, sz: int, col: Color, max_w: float = -1.0) -> void:
	if max_w > 0.0 and _font.get_string_size(s, HORIZONTAL_ALIGNMENT_LEFT, -1, sz).x > max_w:
		while s.length() > 1 and _font.get_string_size(s + "…", HORIZONTAL_ALIGNMENT_LEFT, -1, sz).x > max_w:
			s = s.substr(0, s.length() - 1)
		s += "…"
	draw_string(_font, pos, s, HORIZONTAL_ALIGNMENT_LEFT, -1, sz, col)
