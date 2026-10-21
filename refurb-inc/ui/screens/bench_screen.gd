class_name BenchScreen
extends Control
## What the laptop on a test bench (and the bench's status monitor) displays. Drawn procedurally so it is
## cheap to render into a small SubViewport. Reads the station + unit state every frame it is visible.

var station_id := ""
var _font: Font
var _blink := 0.0


func _ready() -> void:
	_font = ThemeDB.fallback_font
	set_anchors_preset(Control.PRESET_FULL_RECT)


func _process(delta: float) -> void:
	_blink += delta
	queue_redraw()


func _draw() -> void:
	var st: Dictionary = Game.state.stations.get(station_id, {})
	var unit_it := Game.state.station_slot_item(station_id, "unit")
	var usb_it := Game.state.station_slot_item(station_id, "usb")
	if unit_it == null or unit_it.unit == null:
		_bg(Color(0.02, 0.02, 0.025))
		return
	var u := unit_it.unit
	var job: Dictionary = st.get("job", {})
	if not job.is_empty():
		var p := clampf((Game.state.minute - float(job.start)) / maxf(float(job.dur), 0.01), 0.0, 1.0)
		if job.kind == "test":
			_draw_diag(u, p)
		else:
			_draw_image(u, p)
		return
	if usb_it == null:
		_bg(Color(0.0, 0.0, 0.0))
		_t(Vector2(20, 40), "%s BIOS" % Content.maker_name(u.model().get("maker", "")).to_upper(), 18, Color(0.7, 0.7, 0.7))
		_t(Vector2(20, 80), "No bootable device found.", 22, Color(0.85, 0.85, 0.85))
		if fmod(_blink, 1.0) < 0.5:
			_t(Vector2(20, 112), "_", 22, Color(0.85, 0.85, 0.85))
		return
	var msg: String = st.get("msg", "")
	if u.tested and not u.test_report.is_empty() and not u.imaged and (msg in ["PASS", "FAULTS FOUND"] or usb_it.kind == Item.KIND_USB_TEST):
		_draw_report(u)
		return
	if u.imaged:
		_draw_os_ready(u)
		return
	_bg(Color(0.12, 0.1, 0.02))
	_t(Vector2(20, 60), msg if msg != "" else "Waiting…", 20, Color(1.0, 0.85, 0.3))


func _bg(c: Color) -> void:
	draw_rect(get_rect(), c)


func _t(pos: Vector2, s: String, size: int, col: Color) -> void:
	draw_string(_font, pos, s, HORIZONTAL_ALIGNMENT_LEFT, maxf(get_rect().size.x - pos.x - 10, 10), size, col)


func _bar(pos: Vector2, w: float, h: float, p: float, col: Color) -> void:
	draw_rect(Rect2(pos, Vector2(w, h)), Color(1, 1, 1, 0.12))
	draw_rect(Rect2(pos, Vector2(w * p, h)), col)


func _draw_diag(u: UnitData, p: float) -> void:
	var w := get_rect().size.x
	_bg(Color(0.04, 0.07, 0.13))
	draw_rect(Rect2(0, 0, w, 44), Color(0.07, 0.24, 0.48))
	_t(Vector2(14, 30), "%s %s" % [Content.diag_info.get("name", "Diag"), Content.diag_info.get("version", "")], 20, Color.WHITE)
	_t(Vector2(w - 170, 30), u.serial, 16, Color(0.75, 0.85, 1.0))
	var stages: Array = Content.diag_stages(u.hw_type())
	var cur := int(p * stages.size())
	var y := 74.0
	for i in stages.size():
		var done := i < cur
		var active := i == cur
		var mark := "✔" if done else ("›" if active else "·")
		var col := Color(0.4, 0.95, 0.55) if done else (Color(1, 1, 1) if active else Color(0.45, 0.5, 0.6))
		_t(Vector2(18, y), "%s  %s" % [mark, stages[i]], 16, col)
		y += 24
	# Hardware inventory appears as it is detected
	var x2 := w * 0.56
	_t(Vector2(x2, 74), "Hardware inventory", 15, Color(0.6, 0.75, 1.0))
	var m := u.model()
	if m.has("inventory"):
		var inv: Array = m.inventory.duplicate()
		if m.has("storage_options"):
			inv.append("Storage: %s" % UnitData.storage_text(u.storage_gb))
		if u.has_battery():
			inv.append("Battery: %d%% design capacity" % u.battery_pct)
		for i in inv.size():
			if p > 0.1 + 0.6 * float(i) / inv.size():
				_t(Vector2(x2, 98 + 22 * i), str(inv[i]), 14, Color.WHITE)
		_footer_bar(p)
		return
	var cpus := int(m.get("cpu_count", 1))
	var drives := int(m.get("drive_count", 1))
	if p > 0.12:
		_t(Vector2(x2, 98), ("%d× " % cpus if cpus > 1 else "") + u.cpu_name(), 14, Color.WHITE)
	if p > 0.25:
		_t(Vector2(x2, 120), "%d GB DDR4%s" % [u.ram_gb, " ECC" if m.get("ram_type") == "ddr4_rdimm" else ""], 14, Color.WHITE)
	if p > 0.4:
		var per := UnitData.storage_text(u.storage_gb / maxi(drives, 1))
		_t(Vector2(x2, 142), ("%d× %s %s" % [drives, per, u.storage_label()]) if drives > 1 else ("%s %s" % [per, u.storage_label()]), 14, Color.WHITE)
	if p > 0.55:
		var extra := "Battery: %d%% design capacity" % u.battery_pct
		if u.hw_type() == "server":
			extra = "BMC: online · PSUs: 2 detected" if "rack" in m.get("tags", []) else "BMC: online · PSU: 1 detected"
		elif u.hw_type() == "desktop":
			extra = "PSU: %s" % Content.part(m.get("psu_part", "")).get("name", "detected")
		_t(Vector2(x2, 164), extra, 14, Color.WHITE)
	_footer_bar(p)


func _footer_bar(p: float) -> void:
	var w := get_rect().size.x
	_bar(Vector2(18, get_rect().size.y - 34), w - 36, 14, p, Color(0.25, 0.6, 1.0))
	_t(Vector2(18, get_rect().size.y - 42), "%d%%" % int(p * 100), 14, Color(0.8, 0.85, 0.95))


func _draw_image(u: UnitData, p: float) -> void:
	var r := get_rect().size
	var wipe := u.wipe_info()
	if not wipe.is_empty():
		_draw_wipe(u, wipe, p)
		return
	draw_rect(Rect2(Vector2.ZERO, r), Color(0.09, 0.05, 0.16))
	draw_rect(Rect2(0, r.y * 0.55, r.x, r.y * 0.45), Color(0.05, 0.18, 0.2, 0.6))
	# PaneOS logo: four panes
	var cx := r.x / 2
	for i in 4:
		var ox := (i % 2) * 26 - 26
		var oy := (i / 2) * 26 - 60
		draw_rect(Rect2(cx + ox, r.y * 0.3 + oy, 22, 22), Color(0.35, 0.85, 0.9) if i != 3 else Color(0.95, 0.55, 0.25))
	_t(Vector2(cx - 60, r.y * 0.3 + 18), Content.os_info.get("name", "PaneOS") + " Deploy", 20, Color.WHITE)
	var lines: Array = Content.os_info.get("boot_text", [])
	var idx := clampi(int(p * lines.size()), 0, lines.size() - 1)
	var line: String = lines[idx] if not lines.is_empty() else ""
	line = line.replace("{p}", str(clampi(int(p * 3 / 0.6) + 1, 1, 3)))
	_t(Vector2(30, r.y * 0.62), line, 16, Color(0.85, 0.9, 1.0))
	_bar(Vector2(30, r.y * 0.7), r.x - 60, 10, p, Color(0.35, 0.85, 0.9))
	_t(Vector2(30, r.y * 0.82), "%s · %s" % [u.serial, Content.os_info.get("wipe_standard", "")], 13, Color(0.6, 0.65, 0.8))


func _draw_wipe(u: UnitData, wipe: Dictionary, p: float) -> void:
	var r := get_rect().size
	var network := u.hw_type() in ["switch", "wifi_ap"]
	_bg(Color(0.02, 0.03, 0.03) if network else Color(0.06, 0.06, 0.08))
	var accent := Color(0.35, 1.0, 0.55) if network else Color(0.95, 0.6, 0.3)
	draw_rect(Rect2(0, 0, r.x, 44), accent.darkened(0.65))
	_t(Vector2(14, 30), str(wipe.get("title", "Secure wipe")), 20, Color.WHITE)
	_t(Vector2(r.x - 170, 30), u.serial, 16, accent.lightened(0.3))
	var lines: Array = wipe.get("lines", [])
	var cur := int(p * lines.size())
	var y := 80.0
	for i in lines.size():
		if i > cur:
			break
		var done := i < cur
		_t(Vector2(18, y), ("%s %s" % ["[ OK ]" if done else "[ .. ]", lines[i]]), 16, accent if done else Color.WHITE)
		y += 26
	if fmod(_blink, 1.0) < 0.5 and cur < lines.size():
		_t(Vector2(18, y), "_", 16, Color.WHITE)
	_bar(Vector2(18, r.y - 34), r.x - 36, 14, p, accent)
	_t(Vector2(18, r.y - 42), "%d%% · %s" % [int(p * 100), Content.os_info.get("wipe_standard", "")], 14, Color(0.8, 0.85, 0.9))


func _next_step(u: UnitData) -> String:
	if not u.needs_wipe():
		return "No data to wipe — take it to grading."
	if u.hw_type() in ["switch", "wifi_ap"]:
		return "Swap to the PaneOS USB to wipe the config & update firmware."
	if not u.wipe_info().is_empty():
		return "Swap to the PaneOS USB for a secure wipe & factory reset."
	return "Swap to the PaneOS USB to wipe & image."


func _draw_report(u: UnitData) -> void:
	var r := get_rect().size
	var rep := u.test_report
	var ok: bool = rep.get("pass", false)
	_bg(Color(0.04, 0.07, 0.13))
	draw_rect(Rect2(0, 0, r.x, 54), Color(0.1, 0.55, 0.25) if ok else Color(0.7, 0.15, 0.12))
	_t(Vector2(16, 38), "PASS — all tests completed" if ok else "FAULTS FOUND", 24, Color.WHITE)
	var y := 82.0
	_t(Vector2(16, y), "%s  ·  %s" % [rep.get("model", ""), rep.get("serial", "")], 15, Color(0.75, 0.85, 1.0))
	y += 26
	var spec_text: String = rep.get("spec", "%s · %d GB · %d GB" % [rep.get("cpu", ""), int(rep.get("ram_gb", 0)), int(rep.get("storage_gb", 0))])
	if int(rep.get("battery_pct", -1)) >= 0:
		spec_text += " · Battery %d%%" % int(rep.battery_pct)
	_t(Vector2(16, y), spec_text, 14, Color(0.8, 0.8, 0.85))
	y += 30
	if ok:
		_t(Vector2(16, y), _next_step(u), 16, Color(0.6, 1.0, 0.7))
	else:
		for f in rep.get("faults", []):
			var fixed := false
			for uf in u.faults:
				if LotGenerator.fault_text(uf) == f and uf.fixed:
					fixed = true
			_t(Vector2(16, y), ("✔ " if fixed else "✖ ") + str(f), 16, Color(0.6, 1.0, 0.7) if fixed else Color(1.0, 0.55, 0.5))
			y += 24


func _draw_os_ready(u: UnitData) -> void:
	var r := get_rect().size
	match u.hw_type():
		"monitor", "dock":
			if u.has_open_faults():
				_draw_report(u)
				return
			_bg(Color(0.04, 0.07, 0.13))
			draw_rect(Rect2(0, 0, r.x, 54), Color(0.1, 0.55, 0.25))
			_t(Vector2(16, 38), "TESTED — ready to grade", 24, Color.WHITE)
			_t(Vector2(16, 86), u.spec_line(), 14, Color(0.8, 0.85, 0.95))
			_t(Vector2(16, 118), "No user data on this kit — nothing to wipe.", 15, Color(0.6, 1.0, 0.7))
			return
		"switch", "wifi_ap":
			_bg(Color(0.02, 0.03, 0.03))
			var g := Color(0.35, 1.0, 0.55)
			_t(Vector2(16, 40), "%s login: _" % ("lwos" if u.hw_type() == "switch" else "air"), 18, g)
			_t(Vector2(16, 72), "Factory defaults · config erased · firmware current", 15, g.darkened(0.2))
			_t(Vector2(16, 98), u.spec_line(), 13, g.darkened(0.35))
			_t(Vector2(16, r.y - 16), u.serial, 12, Color(1, 1, 1, 0.6))
			return
		"tablet", "phone":
			draw_rect(Rect2(Vector2.ZERO, r), Color(0.08, 0.09, 0.12))
			_t(Vector2(r.x / 2 - 60, r.y * 0.42), "Hello", 40, Color.WHITE)
			_t(Vector2(r.x / 2 - 120, r.y * 0.42 + 34), "Factory reset — no accounts, ready to set up.", 14, Color(0.85, 0.9, 1.0))
			_t(Vector2(16, r.y - 16), u.serial, 12, Color(1, 1, 1, 0.6))
			return
	draw_rect(Rect2(Vector2.ZERO, r), Color(0.12, 0.32, 0.45))
	draw_rect(Rect2(0, r.y * 0.6, r.x, r.y * 0.4), Color(0.18, 0.45, 0.55))
	_t(Vector2(r.x / 2 - 110, r.y * 0.42), "Hello.", 40, Color.WHITE)
	_t(Vector2(r.x / 2 - 110, r.y * 0.42 + 34), "%s is ready for its new owner." % Content.os_info.get("name", "PaneOS"), 15, Color(0.9, 0.95, 1.0))
	_t(Vector2(16, r.y - 16), u.serial, 12, Color(1, 1, 1, 0.6))
