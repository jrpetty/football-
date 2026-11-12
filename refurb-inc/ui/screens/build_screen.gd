class_name BuildScreen
extends Control
## The build bench monitor: the customer's spec as a checklist that goes green as parts are fitted, the next step,
## and (with no build on the bench) the upgrade options or the builds waiting for a server. Drawn procedurally into
## a small SubViewport, like the test bench screens.

const OK := Color(0.36, 0.92, 0.5)
const PART := Color(1.0, 0.78, 0.3)
const BAD := Color(1.0, 0.42, 0.38)
const DIM := Color(0.55, 0.62, 0.72)
const INK := Color(0.92, 0.95, 1.0)
const HEAD := Color(0.09, 0.27, 0.55)

var station_id := ""
var _font: Font


func _ready() -> void:
	_font = ThemeDB.fallback_font
	set_anchors_preset(Control.PRESET_FULL_RECT)


func _process(_d: float) -> void:
	queue_redraw()


func _t(pos: Vector2, s: String, size: int, col: Color, w: float = -1.0) -> void:
	draw_string(_font, pos, s, HORIZONTAL_ALIGNMENT_LEFT, w if w > 0.0 else maxf(size_px().x - pos.x - 12.0, 10.0), size, col)


func size_px() -> Vector2:
	return get_rect().size


func _header(title: String, right: String) -> void:
	var w := size_px().x
	draw_rect(Rect2(Vector2.ZERO, size_px()), Color(0.035, 0.05, 0.09))
	draw_rect(Rect2(0, 0, w, 46), HEAD)
	_t(Vector2(14, 31), title, 20, Color.WHITE, w * 0.62)
	if right != "":
		var rw := _font.get_string_size(right, HORIZONTAL_ALIGNMENT_LEFT, -1, 15).x
		_t(Vector2(w - rw - 14, 30), right, 15, Color(0.75, 0.85, 1.0), rw + 4)


func _draw() -> void:
	var it := Game.state.station_slot_item(station_id, "unit")
	if it == null or it.unit == null:
		_draw_idle()
		return
	var u := it.unit
	var c: Dictionary = Game.state.contracts.get(u.contract, {}) if u.contract != 0 else {}
	if not c.is_empty() and c.get("kind", "") == "build" and c.get("status", "") == "active":
		_draw_build(u, c)
	else:
		_draw_upgrades(u)


func _draw_build(u: UnitData, c: Dictionary) -> void:
	_header("BUILD SHEET · %s" % c.get("name", ""), "%s · due %s" % [c.get("buyer", ""), GameState.format_minute(float(c.get("due", 0)))])
	var rows := ServerBuild.checklist(u, c.spec)
	var y := 78.0
	var done := 0
	for r: Dictionary in rows:
		var col := OK if r.ok else (PART if int(r.have) > 0 else BAD)
		var mark := "✓" if r.ok else ("◐" if int(r.have) > 0 else "×")    # glyphs the fallback font has
		draw_rect(Rect2(12, y - 22, size_px().x - 24, 34), Color(1, 1, 1, 0.045 if r.ok else 0.02))
		_t(Vector2(22, y), mark, 20, col, 26)
		_t(Vector2(52, y), str(r.label), 17, INK if r.ok else Color(0.85, 0.88, 0.95), 300)
		var have_txt := ""
		match str(r.key):
			"memory": have_txt = "%d / %d GB" % [int(r.have), int(r.need)]
			"chassis", "raid": have_txt = "✓" if r.ok else "missing"
			_: have_txt = "%d / %d" % [int(r.have), int(r.need)]
		_t(Vector2(362, y), have_txt, 16, col, 100)
		_t(Vector2(462, y), str(r.get("detail", "")), 13, DIM, 168)
		if r.ok:
			done += 1
		y += 40
	var h := size_px().y
	draw_rect(Rect2(12, h - 58, size_px().x - 24, 12), Color(1, 1, 1, 0.1))
	draw_rect(Rect2(12, h - 58, (size_px().x - 24) * float(done) / maxf(rows.size(), 1), 12), OK if done == rows.size() else Color(0.3, 0.6, 1.0))
	var step := ServerBuild.next_step(Game.state, Net.local_peer(), u, c.spec)
	var foot := ""
	var fcol := INK
	if step.has("act"):
		foot = "Next: %s  —  hold [E]" % step.text
	elif step.get("done", false):
		if not u.tested:
			foot = "ALL GREEN · test it on a test bench next"
		elif u.has_open_faults():
			foot = "Faults found · repair, then wipe & image"
			fcol = BAD
		elif not u.imaged:
			foot = "PASSED · wipe & image with PaneOS next"
		else:
			foot = "READY · pack it for %s" % c.get("buyer", "")
		fcol = OK if fcol == INK else fcol
	else:
		foot = str(step.get("blocked", ""))
		fcol = PART
	_t(Vector2(14, h - 18), foot, 15, fcol)


func _draw_upgrades(u: UnitData) -> void:
	_header("UPGRADE · %s" % u.display_name(), u.serial)
	_t(Vector2(16, 74), u.spec_line(), 15, INK)
	var l := u.layout()
	var m := u.model()
	var lines: Array = []
	if UnitData.ram_upgradable(m):
		lines.append("Memory: %dGB in %d of %d slots (max %dGB)" % [u.ram_gb, (l.dimms as Array).size(), u.ram_slots(), u.ram_max()])
	elif m.has("ram_type"):
		lines.append("Memory: %dGB soldered — can't be upgraded" % u.ram_gb)
	if UnitData.storage_swappable(m):
		lines.append("Storage: %s %s in %d bay%s" % [UnitData.drives_text(l.drives) if not (l.drives as Array).is_empty() else "none", u.storage_label(), u.drive_bays(), "" if u.drive_bays() == 1 else "s"])
	var y := 104.0
	for s in lines:
		_t(Vector2(16, y), str(s), 14, DIM)
		y += 22
	y += 10
	var why := Upgrades.blocked_reason(u)
	if why != "":
		_t(Vector2(16, y + 10), why, 17, PART)
		return
	var opts := Upgrades.options(Game.state, Net.local_peer(), u)
	if opts.is_empty():
		_t(Vector2(16, y + 10), "No upgrades possible with the parts in the bins.", 16, DIM)
		return
	_t(Vector2(16, y), "From the bins", 14, Color(0.6, 0.75, 1.0))
	y += 26
	for o: Dictionary in opts.slice(0, 5):
		var net := int(o.net)
		_t(Vector2(16, y), str(o.text), 15, INK, 400)
		_t(Vector2(420, y), "£%d → %s£%d" % [int(o.cost), "+" if int(o.gain) >= 0 else "-", absi(int(o.gain))], 15, OK if net > 0 else (PART if net == 0 else BAD), 210)
		y += 26
	_t(Vector2(16, size_px().y - 18), "Hold [E] on the unit to fit · [R] to choose", 14, DIM)


func _draw_idle() -> void:
	_header("SERVER BUILD BENCH", Game.state.clock_text())
	var waiting: Array = Game.state.contracts.values().filter(func(c): return c.get("kind", "") == "build" and c.get("status", "") == "active")
	waiting.sort_custom(func(a, b): return float(a.due) < float(b.due))
	var y := 80.0
	if waiting.is_empty():
		_t(Vector2(16, y), "No custom builds on order.", 18, INK)
		_t(Vector2(16, y + 28), "Business buyers send build requests once your reputation", 15, DIM)
		_t(Vector2(16, y + 50), "grows — see the Business app on the office PC.", 15, DIM)
		_t(Vector2(16, y + 92), "Any unit placed here can have RAM or SSD upgrades fitted.", 15, DIM)
		return
	_t(Vector2(16, y), "Builds on order", 16, Color(0.6, 0.75, 1.0))
	y += 30
	for c: Dictionary in waiting.slice(0, 4):
		_t(Vector2(16, y), "%s — %s" % [c.get("name", ""), c.get("buyer", "")], 16, INK, 420)
		_t(Vector2(440, y), "due %s" % GameState.format_minute(float(c.due)), 15, PART, 190)
		y += 22
		_t(Vector2(30, y), "%s · %s · %s" % [Business.chassis_name(c.spec), Business.ram_label(c.spec), Business.drive_label(c.spec)], 13, DIM)
		y += 30
	var plan := ServerBuild.chassis_plan(Game.state)
	_t(Vector2(16, size_px().y - 18), "Hold [E] on the bench to unbox a barebones %s" % Content.model_display_name(str(plan.model)) if not plan.is_empty()
		else "Bring a matching server from stock, or order a barebones chassis (PartsHub)", 14, OK if not plan.is_empty() else DIM)
