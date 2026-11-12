class_name UiKit
## Small shared pieces for the interface: outlined text, vector pictograms drawn in code, and a handful of
## widgets (framed panel, key cap, level pips, meter, segmented choice, menu entry).

static var _unit: Dictionary = {}
static var _boxes: Dictionary = {}

# ------------------------------------------------------------------ text
## draws a line of text with its top-left at `pos` (not the baseline), with a soft dark edge so it reads over
## the world
static func text(ci: CanvasItem, kind: String, pos: Vector2, s: String, size: int, col: Color, align := HORIZONTAL_ALIGNMENT_LEFT, width := -1.0, edge := true) -> void:
	var f := UiTheme.font(kind)
	var p := Vector2(pos.x, pos.y + f.get_ascent(size))
	if edge:
		ci.draw_string_outline(f, p, s, align, width, size, 5, Color(0, 0, 0, 0.6 * col.a))
	ci.draw_string(f, p, s, align, width, size, col)

static func text_w(kind: String, s: String, size: int) -> float:
	return UiTheme.font(kind).get_string_size(s, HORIZONTAL_ALIGNMENT_LEFT, -1, size).x

## the text, or as much of it as fits in `width` with an ellipsis
static func fit(kind: String, s: String, size: int, width: float) -> String:
	var f := UiTheme.font(kind)
	if f.get_string_size(s, HORIZONTAL_ALIGNMENT_LEFT, -1, size).x <= width:
		return s
	var lo := 0; var hi := s.length()
	while lo < hi:
		var mid := (lo + hi + 1) / 2
		if f.get_string_size(s.substr(0, mid).strip_edges() + "…", HORIZONTAL_ALIGNMENT_LEFT, -1, size).x <= width: lo = mid
		else: hi = mid - 1
	return s.substr(0, lo).strip_edges() + "…"

# ------------------------------------------------------------------ pictograms
static func _arc(c: Vector2, r: float, a0: float, a1: float, n: int) -> PackedVector2Array:
	var pts := PackedVector2Array()
	for i in n + 1:
		var a := deg_to_rad(lerpf(a0, a1, float(i) / n))
		pts.append(c + Vector2(cos(a), sin(a)) * r)
	return pts

static func _shape(name: String) -> PackedVector2Array:
	if _unit.has(name):
		return _unit[name]
	var p := PackedVector2Array()
	match name:
		"heart":
			for i in 40:
				var t := TAU * i / 40.0
				var x := 16.0 * pow(sin(t), 3.0)
				var y := -(13.0 * cos(t) - 5.0 * cos(2.0 * t) - 2.0 * cos(3.0 * t) - cos(4.0 * t))
				p.append(Vector2(0.5 + x / 34.0, 0.5 + (y - 2.5) / 32.0))
		"bolt": p = PackedVector2Array([Vector2(0.6, 0.0), Vector2(0.14, 0.56), Vector2(0.44, 0.56), Vector2(0.34, 1.0), Vector2(0.86, 0.4), Vector2(0.55, 0.4)])
		"drop":
			p = _arc(Vector2(0.5, 0.64), 0.34, -38.0, 218.0, 22)
			p.append(Vector2(0.5, 0.02))
		"house": p = PackedVector2Array([Vector2(0.5, 0.06), Vector2(0.98, 0.5), Vector2(0.84, 0.5), Vector2(0.84, 0.94), Vector2(0.16, 0.94), Vector2(0.16, 0.5), Vector2(0.02, 0.5)])
		"diamond": p = PackedVector2Array([Vector2(0.5, 0.04), Vector2(0.94, 0.5), Vector2(0.5, 0.96), Vector2(0.06, 0.5)])
		"pin":
			p = _arc(Vector2(0.5, 0.36), 0.32, 150.0, 390.0, 18)
			p.append(Vector2(0.5, 0.98))
		"moon":
			p = _arc(Vector2(0.44, 0.5), 0.42, 69.0, 291.0, 20)
			p.append_array(_arc(Vector2(0.74, 0.5), 0.42, 249.0, 111.0, 14))
		"arrow": p = PackedVector2Array([Vector2(0.5, 0.0), Vector2(0.9, 1.0), Vector2(0.5, 0.74), Vector2(0.1, 1.0)])
		"tri_down": p = PackedVector2Array([Vector2(0.0, 0.0), Vector2(1.0, 0.0), Vector2(0.5, 1.0)])
		"leaf": p = PackedVector2Array([Vector2(0.55, 0.3), Vector2(0.72, 0.1), Vector2(0.92, 0.12), Vector2(0.78, 0.3)])
		"skull":
			p = _arc(Vector2(0.5, 0.42), 0.4, 150.0, 390.0, 18)
			p.append_array(PackedVector2Array([Vector2(0.72, 0.72), Vector2(0.72, 0.96), Vector2(0.28, 0.96), Vector2(0.28, 0.72)]))
	_unit[name] = p
	return p

## a monochrome pictogram inside `rect`: heart, bolt, drop, food, house, diamond, pin, moon, sun, arrow, check,
## cross, tri_down, skull
static func glyph(ci: CanvasItem, name: String, rect: Rect2, col: Color, rot := 0.0) -> void:
	if rot == 0.0:
		ci.draw_set_transform(rect.position, 0.0, rect.size)
	else:
		ci.draw_set_transform_matrix(Transform2D(rot, rect.get_center()) * Transform2D(0.0, rect.size, 0.0, -rect.size * 0.5))
	var lw := 1.0 / maxf(1.0, rect.size.x)
	match name:
		"food":
			# a leg of meat: the joint, the bone, its knuckle
			ci.draw_circle(Vector2(0.62, 0.38), 0.3, col, true, -1.0, true)
			ci.draw_circle(Vector2(0.48, 0.52), 0.24, col, true, -1.0, true)
			ci.draw_line(Vector2(0.44, 0.56), Vector2(0.16, 0.84), col, 0.13, true)
			ci.draw_circle(Vector2(0.1, 0.8), 0.09, col, true, -1.0, true)
			ci.draw_circle(Vector2(0.2, 0.9), 0.09, col, true, -1.0, true)
		"sun":
			ci.draw_circle(Vector2(0.5, 0.5), 0.22, col, true, -1.0, true)
			for i in 8:
				var d := Vector2.RIGHT.rotated(TAU * i / 8.0)
				ci.draw_line(Vector2(0.5, 0.5) + d * 0.32, Vector2(0.5, 0.5) + d * 0.47, col, 0.08, true)
		"check":
			ci.draw_polyline(PackedVector2Array([Vector2(0.12, 0.55), Vector2(0.4, 0.82), Vector2(0.9, 0.2)]), col, 0.15, true)
		"cross":
			ci.draw_line(Vector2(0.16, 0.16), Vector2(0.84, 0.84), col, 0.12, true)
			ci.draw_line(Vector2(0.84, 0.16), Vector2(0.16, 0.84), col, 0.12, true)
		"skull":
			ci.draw_colored_polygon(_shape("skull"), col)
			ci.draw_circle(Vector2(0.34, 0.46), 0.1, Color(0, 0, 0, 0.75 * col.a), true, -1.0, true)
			ci.draw_circle(Vector2(0.66, 0.46), 0.1, Color(0, 0, 0, 0.75 * col.a), true, -1.0, true)
		_:
			var pts := _shape(name)
			if pts.size() >= 3:
				ci.draw_colored_polygon(pts, col)
				ci.draw_polyline(pts, col, lw, true)
				ci.draw_line(pts[pts.size() - 1], pts[0], col, lw, true)
	ci.draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)

## an eye that opens with `open` (0 shut … 1 wide)
static func eye(ci: CanvasItem, rect: Rect2, open: float, col: Color, width := 1.6) -> void:
	var c := rect.get_center(); var hw := rect.size.x * 0.5; var hh := rect.size.y * 0.5 * clampf(open, 0.08, 1.0)
	var top := PackedVector2Array(); var bot := PackedVector2Array()
	for i in 13:
		var t := i / 12.0
		var x := c.x - hw + t * hw * 2.0
		var k := sin(t * PI)
		top.append(Vector2(x, c.y - k * hh)); bot.append(Vector2(x, c.y + k * hh))
	ci.draw_polyline(top, col, width, true)
	ci.draw_polyline(bot, col, width, true)
	if open > 0.2:
		ci.draw_circle(c, minf(hh * 0.72, rect.size.y * 0.3), col, true, -1.0, true)

static func _cap_box(light: bool) -> StyleBoxFlat:
	var k := "cap_l" if light else "cap_d"
	if not _boxes.has(k):
		var s: StyleBoxFlat
		if light:
			s = UiTheme.flat(Color(0.9, 0.875, 0.815, 0.94), 3, Color(0, 0, 0, 0.55), 0, 0.0)
			s.border_width_bottom = 2
		else:
			s = UiTheme.flat(Color(1, 1, 1, 0.05), 3, UiTheme.LINE2, 1, 0.0)
			s.border_width_bottom = 2
		_boxes[k] = s
	return _boxes[k]

static func keycap_size(key: String, fs: int) -> Vector2:
	return Vector2(maxf(text_w("head", key, fs) + 14.0, fs + 12.0), fs + 11.0)

## a keyboard key: light (over the world) or dark (inside a panel)
static func keycap(ci: CanvasItem, pos: Vector2, key: String, fs := 14, light := true) -> Vector2:
	var sz := keycap_size(key, fs)
	ci.draw_style_box(_cap_box(light), Rect2(pos, sz))
	var f := UiTheme.font("head")
	var col := Color(0.08, 0.08, 0.07) if light else UiTheme.INK
	ci.draw_string(f, Vector2(pos.x + 1.0, pos.y + (sz.y - 2.0 - f.get_height(fs)) * 0.5 + f.get_ascent(fs)), key, HORIZONTAL_ALIGNMENT_CENTER, sz.x, fs, col)
	return sz

static func bar(ci: CanvasItem, rect: Rect2, frac: float, col: Color, back := Color(0, 0, 0, 0.5)) -> void:
	ci.draw_rect(rect, back)
	if frac > 0.0:
		ci.draw_rect(Rect2(rect.position, Vector2(rect.size.x * clampf(frac, 0.0, 1.0), rect.size.y)), col)

## the corner marks every framed surface carries
static func corners(ci: CanvasItem, rect: Rect2, col: Color, l := 9.0) -> void:
	var a := rect.position; var b := rect.end - Vector2(1, 1)
	for c in [[a, Vector2(1, 1)], [Vector2(b.x, a.y), Vector2(-1, 1)], [b, Vector2(-1, -1)], [Vector2(a.x, b.y), Vector2(1, -1)]]:
		var o: Vector2 = c[0]; var d: Vector2 = c[1]
		ci.draw_line(o, o + Vector2(d.x * l, 0), col, 1.0)
		ci.draw_line(o, o + Vector2(0, d.y * l), col, 1.0)

static func caption(text_: String, col := UiTheme.DIM, size := 12) -> Label:
	var l := Label.new(); l.text = text_.to_upper()
	l.add_theme_font_override("font", UiTheme.font("cap")); l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", col)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return l

static func heading(text_: String, size := 20, col := UiTheme.INK) -> Label:
	var l := Label.new(); l.text = text_.to_upper()
	l.add_theme_font_override("font", UiTheme.font("head")); l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", col)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return l

static func body(text_: String, size := 15, col := UiTheme.INK, wrap := false) -> Label:
	var l := Label.new(); l.text = text_
	l.add_theme_font_size_override("font_size", size); l.add_theme_color_override("font_color", col)
	if wrap:
		l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return l

static func spacer(h := 0.0, w := 0.0, expand := false) -> Control:
	var c := Control.new(); c.custom_minimum_size = Vector2(w, h); c.mouse_filter = Control.MOUSE_FILTER_IGNORE
	if expand:
		c.size_flags_horizontal = Control.SIZE_EXPAND_FILL; c.size_flags_vertical = Control.SIZE_EXPAND_FILL
	return c

static func margin(c: Control, l: float, t: float, r: float, b: float) -> MarginContainer:
	var m := MarginContainer.new()
	m.add_theme_constant_override("margin_left", int(l)); m.add_theme_constant_override("margin_top", int(t))
	m.add_theme_constant_override("margin_right", int(r)); m.add_theme_constant_override("margin_bottom", int(b))
	m.add_child(c)
	return m

# ------------------------------------------------------------------ widgets
## a dark slab with a hairline edge, corner marks and a trace of grain
class Frame extends PanelContainer:
	var marks := true
	var grain := 0.045
	func _init(col := UiTheme.PANEL, pad := 0.0, edge := UiTheme.LINE) -> void:
		add_theme_stylebox_override("panel", UiTheme.flat(col, 0, edge, 1, pad))
	func _draw() -> void:
		var r := Rect2(Vector2.ZERO, size)
		if grain > 0.0:
			draw_texture_rect(UiTheme.tex("grain"), r, true, Color(1, 1, 1, grain))
		if marks:
			UiKit.corners(self, r, Color(UiTheme.ACCENT, 0.55))

class Glyph extends Control:
	var kind := "heart"
	var color := UiTheme.INK
	func _init(k: String, sz := 16.0, col := UiTheme.INK) -> void:
		kind = k; color = col; custom_minimum_size = Vector2(sz, sz); mouse_filter = Control.MOUSE_FILTER_IGNORE
	func _draw() -> void:
		var s := minf(size.x, size.y)
		UiKit.glyph(self, kind, Rect2((size - Vector2(s, s)) * 0.5, Vector2(s, s)), color)

class KeyCap extends Control:
	var key := "E"
	var fs := 13
	var light := false
	func _init(k: String, font_size := 13, on_world := false) -> void:
		key = k; fs = font_size; light = on_world
		custom_minimum_size = UiKit.keycap_size(k, font_size); mouse_filter = Control.MOUSE_FILTER_IGNORE
		size_flags_vertical = Control.SIZE_SHRINK_CENTER
	func _draw() -> void:
		UiKit.keycap(self, Vector2.ZERO, key, fs, light)

## ten boxes: filled up to the level, the next one filling with progress
class Pips extends Control:
	var level := 0
	var progress := 0.0
	var count := 10
	var color := UiTheme.ACCENT
	func _init(lv := 0, prog := 0.0, w := 150.0, h := 9.0) -> void:
		level = lv; progress = prog; custom_minimum_size = Vector2(w, h); mouse_filter = Control.MOUSE_FILTER_IGNORE
		size_flags_vertical = Control.SIZE_SHRINK_CENTER
	func set_level(lv: int, prog: float) -> void:
		if lv != level or absf(prog - progress) > 0.01:
			level = lv; progress = prog; queue_redraw()
	func _draw() -> void:
		var gap := 3.0
		var w := (size.x - gap * (count - 1)) / count
		for i in count:
			var r := Rect2(i * (w + gap), 0, w, size.y)
			draw_rect(r, Color(1, 1, 1, 0.08))
			if i < level:
				draw_rect(r, color)
			elif i == level and progress > 0.0:
				draw_rect(Rect2(r.position, Vector2(w * clampf(progress, 0.0, 1.0), size.y)), Color(color, 0.45))

class Meter extends Control:
	var value := 0.0
	var color := UiTheme.ACCENT
	var back := Color(1, 1, 1, 0.08)
	func _init(w := 120.0, h := 5.0, col := UiTheme.ACCENT) -> void:
		custom_minimum_size = Vector2(w, h); color = col; mouse_filter = Control.MOUSE_FILTER_IGNORE
		size_flags_vertical = Control.SIZE_SHRINK_CENTER
	func set_value(v: float, col = null) -> void:
		var c: Color = color if col == null else col
		if absf(v - value) > 0.002 or c != color:
			value = v; color = c; queue_redraw()
	func _draw() -> void:
		UiKit.bar(self, Rect2(Vector2.ZERO, size), value, color, back)

## one choice out of a few, as a row of joined buttons
class Segmented extends HBoxContainer:
	signal picked(value)
	var _btns: Array = []
	var _values: Array = []
	func _init(options: Array, current: Variant, w := 86.0) -> void:
		add_theme_constant_override("separation", 0)
		for o in options:
			var b := Button.new(); b.text = String(o[1]).to_upper(); b.toggle_mode = true
			b.custom_minimum_size = Vector2(w, 34); b.focus_mode = Control.FOCUS_ALL
			b.add_theme_font_size_override("font_size", 14)
			var val = o[0]
			b.pressed.connect(func() -> void:
				select(val)
				picked.emit(val))
			add_child(b); _btns.append(b); _values.append(val)
		select(current)
	func select(value: Variant) -> void:
		for i in _btns.size():
			var same: bool = typeof(_values[i]) == typeof(value) and _values[i] == value
			if (_values[i] is float or _values[i] is int) and (value is float or value is int):
				same = absf(float(_values[i]) - float(value)) < 0.001
			_btns[i].set_pressed_no_signal(same)

## a line of the main menu: plain capitals that take an amber rule and slide when chosen
class MenuItem extends Button:
	var label := ""
	var _t := 0.0
	var fs := 27
	func _init(text_: String, cb: Callable, font_size := 27) -> void:
		label = text_.to_upper(); fs = font_size
		custom_minimum_size = Vector2(360, font_size + 22)
		focus_mode = Control.FOCUS_ALL
		for sb in ["normal", "hover", "pressed", "disabled", "focus", "hover_pressed"]:
			add_theme_stylebox_override(sb, StyleBoxEmpty.new())
		pressed.connect(cb)
		mouse_entered.connect(func() -> void:
			if not disabled: grab_focus())
	func _process(dt: float) -> void:
		var want := 1.0 if (has_focus() and not disabled) else 0.0
		if _t != want:
			_t = move_toward(_t, want, dt * 9.0)
			queue_redraw()
	func _draw() -> void:
		var e := _t * _t * (3.0 - 2.0 * _t)
		if e > 0.0:
			var a := Color(UiTheme.ACCENT, 0.2 * e); var z := Color(UiTheme.ACCENT, 0.0)
			draw_polygon(PackedVector2Array([Vector2(0, 0), Vector2(size.x, 0), Vector2(size.x, size.y), Vector2(0, size.y)]), PackedColorArray([a, z, z, a]))
			draw_rect(Rect2(0, 0, 3, size.y), Color(UiTheme.ACCENT, e))
		var col := UiTheme.FAINT if disabled else UiTheme.INK.lerp(UiTheme.ACCENT2, e)
		var f := UiTheme.font("head")
		var y := (size.y - f.get_height(fs)) * 0.5 + f.get_ascent(fs)
		draw_string_outline(f, Vector2(18.0 + 10.0 * e, y), label, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, 5, Color(0, 0, 0, 0.35))
		draw_string(f, Vector2(18.0 + 10.0 * e, y), label, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, col)
