class_name UiHealth
extends RefCounted
## The health window: a figure coloured by the state of each part, the wounds on each part with what you can
## do about them, how you are overall, and what you are good at. Numbers update in place; the wound list is
## rebuilt only when a wound or your medical supplies change.

const VITALS := [["health", "Overall health"], ["blood", "Blood loss"], ["pain", "Pain"], ["panic", "Panic"], ["temp", "Body temperature"], ["wet", "Wetness"], ["sick", "Sickness"], ["mood", "Unhappiness"], ["insul", "Insulation"], ["carry", "Carry"], ["pk", "Painkillers"], ["ab", "Antibiotics"]]
const WOUND_NAMES := {"scratch": "Scratch", "laceration": "Laceration", "bite": "Bite", "bullet": "Bullet wound", "fracture": "Fracture", "burn": "Burn"}

var hud: Hud
var _built := false
var figure: Figure = null
var _cards: VBoxContainer
var _vals: Dictionary = {}
var _fever: Label
var _fever_box: Control
var _skills: Dictionary = {}
var _sig := ""
var _t := 0.0
var _data: Dictionary = {}

func _init(h: Hud) -> void:
	hud = h

func closed() -> void:
	_built = false
	figure = null

func build(v: VBoxContainer) -> void:
	_built = true
	_sig = "\u0001"; _vals.clear(); _skills.clear(); _t = 0.0
	if _data.is_empty():
		var d = World.read_json("res://data/skills.json")
		if d is Dictionary: _data = d
	var p: Player = hud.game.player
	var cols := HBoxContainer.new(); cols.add_theme_constant_override("separation", 22); cols.size_flags_vertical = Control.SIZE_EXPAND_FILL
	v.add_child(cols)

	# left: the figure
	var left := VBoxContainer.new(); left.custom_minimum_size = Vector2(300, 0); left.add_theme_constant_override("separation", 8)
	left.add_child(UiKit.heading("Body", 17))
	figure = Figure.new(self); left.add_child(figure)
	var key := HBoxContainer.new(); key.alignment = BoxContainer.ALIGNMENT_CENTER; key.add_theme_constant_override("separation", 14)
	for k in [["Sound", UiTheme.GOOD], ["Hurt", UiTheme.WARN], ["Failing", UiTheme.BAD]]:
		var sw := ColorRect.new(); sw.color = k[1]; sw.custom_minimum_size = Vector2(10, 10); sw.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		var kh := HBoxContainer.new(); kh.add_theme_constant_override("separation", 6)
		kh.add_child(sw); kh.add_child(UiKit.caption(String(k[0]), UiTheme.DIM, 11)); key.add_child(kh)
	left.add_child(key)
	cols.add_child(left)

	# middle: wounds, part by part
	var mid := VBoxContainer.new(); mid.size_flags_horizontal = Control.SIZE_EXPAND_FILL; mid.add_theme_constant_override("separation", 8)
	mid.add_child(UiKit.heading("Wounds", 17))
	var sc := ScrollContainer.new(); sc.size_flags_vertical = Control.SIZE_EXPAND_FILL; sc.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	_cards = VBoxContainer.new(); _cards.size_flags_horizontal = Control.SIZE_EXPAND_FILL; _cards.add_theme_constant_override("separation", 6)
	sc.add_child(_cards); mid.add_child(sc)
	var tip := UiKit.body("Bandage bleeding wounds immediately. Rags stop bleeding but get dirty — disinfect and re-bandage. Fractures need a splint to heal.", 13, UiTheme.FAINT, true)
	mid.add_child(tip)
	var foot := HBoxContainer.new(); foot.add_theme_constant_override("separation", 6)
	for hnt in [["Q", "Bandage the worst wound"], ["Tab", "Inventory"], ["H", "Close"]]:
		foot.add_child(UiKit.KeyCap.new(String(hnt[0]), 12))
		var hl := UiKit.body(String(hnt[1]), 13, UiTheme.DIM); hl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		foot.add_child(hl); foot.add_child(UiKit.spacer(0, 8))
	mid.add_child(foot)
	cols.add_child(mid)

	# right: condition and skills
	var right := VBoxContainer.new(); right.custom_minimum_size = Vector2(372, 0); right.add_theme_constant_override("separation", 8)
	right.add_child(UiKit.heading("Condition", 17))
	var grid := GridContainer.new(); grid.columns = 4; grid.add_theme_constant_override("h_separation", 10); grid.add_theme_constant_override("v_separation", 5)
	for row in VITALS:
		var kl := UiKit.body(String(row[1]), 13, UiTheme.DIM); kl.custom_minimum_size = Vector2(112, 0)
		var vl := UiKit.body("", 13, UiTheme.INK); vl.custom_minimum_size = Vector2(54, 0); vl.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
		vl.add_theme_font_override("font", UiTheme.font("num"))
		grid.add_child(kl); grid.add_child(vl); _vals[row[0]] = vl
	right.add_child(grid)
	var fb := UiKit.Frame.new(Color(UiTheme.BAD, 0.12), 10.0, Color(UiTheme.BAD, 0.6)); fb.marks = false; fb.grain = 0.0
	_fever = UiKit.body("", 13, Color(1.0, 0.78, 0.72), true); _fever.custom_minimum_size = Vector2(330, 0)
	fb.add_child(_fever); fb.visible = false; _fever_box = fb
	right.add_child(fb)
	right.add_child(UiKit.spacer(2))
	right.add_child(UiKit.heading("Skills", 17))
	var ssc := ScrollContainer.new(); ssc.size_flags_vertical = Control.SIZE_EXPAND_FILL; ssc.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	var sv := VBoxContainer.new(); sv.size_flags_horizontal = Control.SIZE_EXPAND_FILL; sv.add_theme_constant_override("separation", 4)
	var group := ""
	for sk in _data.get("skills", []):
		var id := String(sk.id)
		if not p.xp.has(id):
			continue
		if String(sk.group) != group:
			group = String(sk.group)
			if sv.get_child_count() > 0: sv.add_child(UiKit.spacer(3))
			sv.add_child(UiKit.caption(group, UiTheme.FAINT, 11))
		var row2 := HBoxContainer.new(); row2.add_theme_constant_override("separation", 8)
		row2.tooltip_text = String(sk.desc); row2.mouse_filter = Control.MOUSE_FILTER_PASS
		var ic := TextureRect.new(); ic.texture = hud.icon("skill_" + id); ic.custom_minimum_size = Vector2(18, 18)
		ic.expand_mode = TextureRect.EXPAND_IGNORE_SIZE; ic.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED; ic.mouse_filter = Control.MOUSE_FILTER_IGNORE
		var nl := UiKit.body(String(sk.name), 14, UiTheme.INK); nl.custom_minimum_size = Vector2(104, 0)
		var pips := UiKit.Pips.new(0, 0.0, 150.0, 8.0); pips.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		var lv := UiKit.body("0", 14, UiTheme.INK); lv.custom_minimum_size = Vector2(22, 0); lv.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
		lv.add_theme_font_override("font", UiTheme.font("num"))
		var boost := UiKit.caption("×3", UiTheme.BLUE, 12); boost.custom_minimum_size = Vector2(20, 0); boost.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		row2.add_child(ic); row2.add_child(nl); row2.add_child(pips); row2.add_child(boost); row2.add_child(lv)
		sv.add_child(row2); _skills[id] = [pips, lv, boost]
	ssc.add_child(sv); right.add_child(ssc)
	cols.add_child(right)
	refresh()

func refresh() -> void:
	if not _built:
		return
	_wounds()
	_numbers()
	if figure != null: figure.queue_redraw()

func tick(dt: float) -> void:
	if not _built:
		return
	_t -= dt
	if _t <= 0.0:
		_t = 0.25
		_wounds(); _numbers()
	if figure != null and figure.bleeding:
		figure.queue_redraw()

func _put(key: String, text: String, col := UiTheme.INK) -> void:
	var l: Label = _vals[key]
	if l.text != text:
		l.text = text
		l.add_theme_color_override("font_color", col)

func _numbers() -> void:
	var p: Player = hud.game.player
	var st := p.stats
	var hp := st.health()
	_put("health", "%d%%" % int(round(hp)), UiTheme.condition(hp))
	_put("blood", "%d%%" % int(round(st.blood_loss)), UiTheme.BAD if st.blood_loss > 30.0 else (UiTheme.WARN if st.blood_loss > 8.0 else UiTheme.INK))
	_put("pain", str(int(round(st.pain))), UiTheme.WARN if st.pain > 35.0 else UiTheme.INK)
	_put("panic", str(int(round(st.panic))), UiTheme.WARN if st.panic > 40.0 else UiTheme.INK)
	_put("temp", "%.1f°C" % st.body_temp, UiTheme.BLUE if st.body_temp < 36.2 else (UiTheme.BAD if st.body_temp > 37.6 else UiTheme.INK))
	_put("wet", "%d%%" % int(round(st.wetness * 100.0)))
	_put("sick", str(int(round(st.sickness))), UiTheme.WARN if st.sickness > 15.0 else UiTheme.INK)
	_put("mood", str(int(round(st.unhappy))))
	_put("insul", str(int(round(p.insulation() * 100.0))))
	_put("carry", "%.1f / %.0f kg" % [p.weight(), p.capacity()], UiTheme.BAD if p.weight_ratio() > 1.0 else UiTheme.INK)
	_put("pk", ("%.1f h" % st.painkiller_hrs) if st.painkiller_hrs > 0.0 else "—")
	_put("ab", ("%.1f h" % st.antibiotic_hrs) if st.antibiotic_hrs > 0.0 else "—")
	var fever := st.knox_infected and st.knox >= 0.15
	if fever != _fever_box.visible: _fever_box.visible = fever
	if fever:
		var txt := "You feel feverish and weak. The bite… it's spreading. %sOnly the Antiviral Serum can stop it." % ("You do not have long. " if st.knox > 0.6 else "")
		if _fever.text != txt: _fever.text = txt
	var now: float = hud.game.game_min
	for id in _skills:
		var e: Array = _skills[id]
		(e[0] as UiKit.Pips).set_level(p.level(id), p.skill_progress(id) if p.level(id) < 10 else 0.0)
		var lt := str(p.level(id))
		if (e[1] as Label).text != lt: (e[1] as Label).text = lt
		var on: bool = float(p.book_until.get(id, 0.0)) > now
		if (e[2] as Label).modulate.a != (1.0 if on else 0.0): (e[2] as Label).modulate.a = 1.0 if on else 0.0

func _wounds() -> void:
	var p: Player = hud.game.player
	var st := p.stats
	var sig := ""
	for part in Stats.PARTS:
		var pd: Dictionary = st.parts[part[0]]
		sig += "%d:" % int(float(pd.hp))
		for w in pd.wounds:
			sig += "%s%d%d%d%d%d%d%d;" % [w.type, int(float(w.bleed) > 0.05), int(w.bandaged), int(w.dirty), int(w.stitched), int(w.splinted), int(float(w.infection) * 100.0), int((1.0 - float(w.heal)) * 20.0)]
	for id in ["bandage", "rag", "disinfectant", "poultice", "splint", "suture_kit"]:
		sig += "1" if p.inv.count(id) > 0 else "0"
	if sig == _sig:
		return
	_sig = sig
	for c in _cards.get_children(): c.queue_free()
	for part in Stats.PARTS:
		var pd2: Dictionary = st.parts[part[0]]
		var hp := float(pd2.hp)
		var card := UiKit.Frame.new(Color(1, 1, 1, 0.025), 10.0); card.marks = false; card.grain = 0.0
		var cv := VBoxContainer.new(); cv.add_theme_constant_override("separation", 5)
		var head := HBoxContainer.new(); head.add_theme_constant_override("separation", 10)
		var nl := UiKit.heading(String(part[1]), 15); nl.custom_minimum_size = Vector2(110, 0)
		var m := UiKit.Meter.new(120.0, 4.0, UiTheme.condition(hp)); m.value = hp / 100.0; m.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		var hl := UiKit.body(str(int(round(hp))), 14, UiTheme.condition(hp)); hl.add_theme_font_override("font", UiTheme.font("num"))
		hl.custom_minimum_size = Vector2(30, 0); hl.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
		head.add_child(nl); head.add_child(m); head.add_child(hl); cv.add_child(head)
		var wi := 0
		for w in pd2.wounds:
			var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 6)
			var bad: bool = float(w.infection) > 0.3 or (float(w.bleed) > 0.05 and not w.bandaged)
			var desc := String(WOUND_NAMES.get(String(w.type), String(w.type).capitalize()))
			if float(w.bleed) > 0.05 and not w.bandaged: desc += " · bleeding"
			if w.bandaged: desc += " · dirty bandage" if w.dirty else " · bandaged"
			if w.stitched: desc += " · stitched"
			if w.splinted: desc += " · splinted"
			if float(w.infection) > 0.05: desc += " · infected %d%%" % int(float(w.infection) * 100.0)
			var dot := ColorRect.new(); dot.color = UiTheme.BAD if bad else UiTheme.DIM; dot.custom_minimum_size = Vector2(4, 16); dot.size_flags_vertical = Control.SIZE_SHRINK_CENTER
			var dl := UiKit.body(desc, 14, Color(1.0, 0.72, 0.64) if bad else UiTheme.INK); dl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			dl.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS; dl.clip_text = true
			dl.tooltip_text = desc; dl.mouse_filter = Control.MOUSE_FILTER_PASS
			var healed := UiKit.body("%d%% healed" % int(round((1.0 - clampf(float(w.heal), 0.0, 1.0)) * 100.0)), 12, UiTheme.FAINT)
			healed.size_flags_vertical = Control.SIZE_SHRINK_CENTER
			row.add_child(dot); row.add_child(dl); row.add_child(healed)
			cv.add_child(row)
			var opts: Array = hud._treatments(w)
			if not opts.is_empty():
				var brow := HBoxContainer.new(); brow.add_theme_constant_override("separation", 6)
				brow.add_child(UiKit.spacer(0, 4))
				var tl := UiKit.caption("Treat with", UiTheme.FAINT, 11); tl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
				brow.add_child(tl)
				for opt in opts:
					var pid := String(part[0]); var idx := wi; var item_id := String(opt)
					var b := hud.button(String(Items.def(item_id).name), func() -> void: hud.treat_wound.emit(pid, idx, item_id), 0.0)
					b.custom_minimum_size = Vector2(0, 28); b.add_theme_font_size_override("font_size", 12)
					b.tooltip_text = String(UiInventory.MED_USE.get(String(Items.def(item_id).get("med", "")), ""))
					brow.add_child(b)
				cv.add_child(brow)
			wi += 1
		if pd2.wounds.is_empty():
			cv.add_child(UiKit.body("No wounds", 13, UiTheme.FAINT))
		card.add_child(cv); _cards.add_child(card)

# ------------------------------------------------------------------ the figure
class Figure extends Control:
	var ui: UiHealth
	var bleeding := false
	var shapes: Dictionary = {}

	func _init(u: UiHealth) -> void:
		ui = u
		custom_minimum_size = Vector2(300, 470)
		mouse_filter = Control.MOUSE_FILTER_IGNORE
		var mirror := func(pts: PackedVector2Array) -> PackedVector2Array:
			var out := PackedVector2Array()
			for i in range(pts.size() - 1, -1, -1):
				out.append(Vector2(300.0 - pts[i].x, pts[i].y))
			return out
		shapes["torso"] = PackedVector2Array([Vector2(138, 86), Vector2(162, 86), Vector2(166, 100), Vector2(198, 108), Vector2(202, 150), Vector2(190, 252), Vector2(110, 252), Vector2(98, 150), Vector2(102, 108), Vector2(134, 100)])
		shapes["armL"] = PackedVector2Array([Vector2(96, 110), Vector2(78, 122), Vector2(60, 196), Vector2(46, 268), Vector2(42, 292), Vector2(58, 298), Vector2(66, 272), Vector2(82, 204), Vector2(94, 156)])
		shapes["armR"] = mirror.call(shapes["armL"])
		shapes["legL"] = PackedVector2Array([Vector2(108, 258), Vector2(148, 258), Vector2(146, 350), Vector2(140, 436), Vector2(142, 458), Vector2(100, 458), Vector2(112, 436), Vector2(110, 350)])
		shapes["legR"] = mirror.call(shapes["legL"])

	func _draw() -> void:
		var st: Stats = ui.hud.game.player.stats
		bleeding = false
		var t := Time.get_ticks_msec() * 0.001
		for id in ["legL", "legR", "armL", "armR", "torso", "head"]:
			var pd: Dictionary = st.parts[id]
			var hp := float(pd.hp)
			var col := UiTheme.GOOD.lerp(UiTheme.WARN, clampf((100.0 - hp) / 45.0, 0.0, 1.0)).lerp(UiTheme.BAD, clampf((60.0 - hp) / 40.0, 0.0, 1.0))
			var fill := Color(col.r * 0.5, col.g * 0.5, col.b * 0.5, 0.55 + (100.0 - hp) * 0.003)
			var centre := Vector2(150, 50)
			if id == "head":
				draw_circle(centre, 34.0, fill, true, -1.0, true)
				draw_arc(centre, 34.0, 0.0, TAU, 40, col, 1.5, true)
			else:
				var pts: PackedVector2Array = shapes[id]
				draw_colored_polygon(pts, fill)
				draw_polyline(pts, col, 1.5, true)
				draw_line(pts[pts.size() - 1], pts[0], col, 1.5, true)
				centre = Vector2.ZERO
				for q in pts: centre += q
				centre /= pts.size()
			# wounds: a mark each, pulsing while it bleeds
			var n := 0
			for w in pd.wounds:
				var open: bool = float(w.bleed) > 0.05 and not w.bandaged
				var pos := centre + Vector2((n % 2) * 16.0 - 8.0 * minf(1.0, pd.wounds.size() - 1.0), (n / 2) * 16.0 - 4.0)
				if open:
					bleeding = true
					var k := 0.5 + 0.5 * sin(t * 5.0)
					draw_circle(pos, 7.0 + k * 3.0, Color(UiTheme.BAD, 0.3 * (1.0 - k)), true, -1.0, true)
					draw_circle(pos, 5.0, UiTheme.BAD, true, -1.0, true)
				elif w.bandaged:
					draw_rect(Rect2(pos - Vector2(6, 3), Vector2(12, 6)), Color(0.86, 0.84, 0.78) if not w.dirty else Color(0.6, 0.5, 0.36))
					draw_rect(Rect2(pos - Vector2(6, 3), Vector2(12, 6)), Color(0, 0, 0, 0.6), false, 1.0)
				else:
					draw_circle(pos, 4.0, Color(0, 0, 0, 0.55), true, -1.0, true)
					draw_arc(pos, 4.0, 0.0, TAU, 16, UiTheme.WARN, 1.5, true)
				if float(w.infection) > 0.05:
					draw_arc(pos, 8.5, 0.0, TAU, 20, Color(0.62, 0.78, 0.3), 1.5, true)
				n += 1
		# part names with their numbers, out to the sides
		for e in [["head", Vector2(196, 34), false], ["torso", Vector2(214, 176), false], ["armL", Vector2(0, 150), true], ["armR", Vector2(248, 150), false], ["legL", Vector2(34, 400), true], ["legR", Vector2(212, 400), false]]:
			var hp2 := float(st.parts[e[0]].hp)
			var nm := ""
			for q in Stats.PARTS:
				if q[0] == e[0]: nm = String(q[1]).to_upper()
			var o: Vector2 = e[1]
			if e[0] == "torso":
				o = Vector2(150 - 40, 160)
				UiKit.text(self, "cap", o, nm, 11, UiTheme.INK, HORIZONTAL_ALIGNMENT_CENTER, 80, false)
				UiKit.text(self, "num", o + Vector2(0, 14), str(int(round(hp2))), 17, UiTheme.INK, HORIZONTAL_ALIGNMENT_CENTER, 80, false)
			else:
				var al := HORIZONTAL_ALIGNMENT_RIGHT if e[2] else HORIZONTAL_ALIGNMENT_LEFT
				var w2 := 52.0 if e[2] else 100.0
				UiKit.text(self, "cap", o, nm.replace("LEFT ", "L. ").replace("RIGHT ", "R. "), 11, UiTheme.DIM, al, w2, false)
				UiKit.text(self, "num", o + Vector2(0, 14), str(int(round(hp2))), 17, UiTheme.condition(hp2), al, w2, false)
