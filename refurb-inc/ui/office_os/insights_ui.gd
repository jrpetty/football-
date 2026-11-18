class_name InsightsUI
extends PaneApp
## Widgets and charts for the Insights app: KPI tiles, sections, striped tables, meters, insight cards,
## and drawn charts (daily bars + line, split bar, heatmap). Pages live in apps/insights_app.gd.

const REVENUE_COL := Color(0.2, 0.45, 0.85)
const PROFIT_COL := Color(0.42, 0.28, 0.72)
const CASH_COL := Color(0.1, 0.52, 0.55)
const NUM_W := 96.0
const TEXT_W := 200.0
const TONES := {"good": [Color(0.12, 0.6, 0.3), "Good news"], "warn": [Color(0.85, 0.45, 0.05), "Heads up"],
	"tip": [Color(0.2, 0.45, 0.85), "Tip"], "info": [Color(0.42, 0.28, 0.72), "Insight"]}

var _body: VBoxContainer


# ================================================================= layout blocks

func _kpis(items: Array, columns: int = 4) -> void:
	## items: [title, value, sub, colour]
	var g := GridContainer.new()
	g.columns = columns
	g.add_theme_constant_override("h_separation", 10)
	g.add_theme_constant_override("v_separation", 10)
	for it in items:
		var c := card()
		c.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		c.custom_minimum_size.x = 180
		var v := VBoxContainer.new()
		v.add_theme_constant_override("separation", 2)
		c.add_child(v)
		v.add_child(l(it[0], 15, SUB))
		var val := l(it[1], 26, it[3])
		val.clip_text = true
		v.add_child(val)
		if it[2] != "":
			var sub := l(it[2], 13, SUB)
			sub.clip_text = true
			v.add_child(sub)
		g.add_child(c)
	_body.add_child(g)


func _section(title: String, note: String = "") -> VBoxContainer:
	var c := card()
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 8)
	c.add_child(v)
	v.add_child(l(title, 20))
	if note != "":
		v.add_child(para(note, 14, SUB, 700))
	_body.add_child(c)
	return v


func _empty(text: String) -> void:
	var c := card(CARD_ALT)
	c.add_child(para(text, 18, SUB, 800))
	_body.add_child(c)


func _note(parent: VBoxContainer, text: String) -> void:
	parent.add_child(para(text, 15, SUB, 800))


func _table(parent: VBoxContainer, headers: Array, rows: Array, first_wide: bool = true, wide: int = 0, text_cols: Array = []) -> void:
	## Striped rows; the `wide` column stretches, `text_cols` are left-aligned text, the rest right-aligned numbers.
	## A cell is text or [text, colour].
	var t := VBoxContainer.new()
	t.add_theme_constant_override("separation", 0)
	parent.add_child(t)
	var all := [headers]
	all.append_array(rows)
	for r in all.size():
		var row := PanelContainer.new()
		var bg := Color(0, 0, 0, 0) if r == 0 else (CARD_ALT if r % 2 == 1 else CARD)
		row.add_theme_stylebox_override("panel", UiStyle.box(bg, 4, 5))
		var h := HBoxContainer.new()
		h.add_theme_constant_override("separation", 8)
		row.add_child(h)
		var cells: Array = all[r]
		for i in cells.size():
			var cell: Variant = cells[i]
			var txt: String = str(cell[0]) if cell is Array else str(cell)
			var col: Color = (cell[1] if cell is Array else INK) if r > 0 else SUB
			var lab := l(txt, 14 if r == 0 else 16, col)
			lab.clip_text = true
			if i == wide and first_wide:
				lab.size_flags_horizontal = Control.SIZE_EXPAND_FILL
				lab.custom_minimum_size.x = 150
			elif i in text_cols:
				lab.custom_minimum_size.x = TEXT_W
			else:
				lab.custom_minimum_size.x = NUM_W
				lab.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
				if not first_wide:
					lab.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			h.add_child(lab)
		t.add_child(row)


func _meter_row(parent: VBoxContainer, title: String, frac: float, value: String, col: Color, tag: String = "") -> void:
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 12)
	var tl := l(title, 16)
	tl.custom_minimum_size.x = 230
	tl.clip_text = true
	h.add_child(tl)
	var m := _Meter.new()
	m.frac = clampf(frac, 0.0, 1.0)
	m.colour = col
	m.custom_minimum_size = Vector2(280, 16)
	m.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	h.add_child(m)
	h.add_child(l(value, 15, SUB))
	if tag != "":
		h.add_child(pill(tag, col, Color.WHITE, 13))
	parent.add_child(h)


func _swatch(col: Color, text: String) -> Control:
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 6)
	var sw := ColorRect.new()
	sw.color = col
	sw.custom_minimum_size = Vector2(14, 14)
	sw.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	h.add_child(sw)
	h.add_child(l(text, 15, INK))
	return h


func _insight_card(parent: Container, ins: Dictionary) -> void:
	var tone: Array = TONES.get(ins.tone, TONES.info)
	var c := card()
	c.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 14)
	c.add_child(h)
	var stripe := ColorRect.new()
	stripe.color = tone[0]
	stripe.custom_minimum_size = Vector2(5, 0)
	h.add_child(stripe)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 4)
	v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(v)
	var top := HBoxContainer.new()
	top.add_theme_constant_override("separation", 10)
	top.add_child(pill(tone[1], tone[0], Color.WHITE, 13))
	var t := l(ins.title, 19)
	t.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	t.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	top.add_child(t)
	v.add_child(top)
	v.add_child(para(ins.text, 15, SUB, 400))
	parent.add_child(c)


func _tiles(items: Array, columns: int = 3) -> void:
	## Small record/award tiles: {title, value, detail}
	var g := GridContainer.new()
	g.columns = columns
	g.add_theme_constant_override("h_separation", 10)
	g.add_theme_constant_override("v_separation", 10)
	for it: Dictionary in items:
		var c := card(CARD_ALT)
		c.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		var v := VBoxContainer.new()
		v.add_theme_constant_override("separation", 1)
		c.add_child(v)
		v.add_child(l(it.title, 14, SUB))
		var val := l(it.value, 22)
		val.clip_text = true
		v.add_child(val)
		var d := l(it.get("detail", ""), 13, SUB)
		d.clip_text = true
		v.add_child(d)
		g.add_child(c)
	_body.add_child(g)


func _chart(bars: Array, line: Array, labels: Array, bar_col: Color, money: bool) -> Control:
	## Bars when there's a line too (revenue + profit share one £ axis); otherwise a single line.
	var c := _Chart.new()
	c.custom_minimum_size = Vector2(0, 230)
	c.money = money
	c.labels = labels
	if line.is_empty():
		c.line = bars
		c.line_col = bar_col
	else:
		c.bars = bars
		c.bar_col = bar_col
		c.line = line
		c.line_col = PROFIT_COL
	return c


# ================================================================= formatting

static func _m(v: Variant) -> String:
	return GameState.format_money(int(round(float(v))))


static func _mc(v: Variant) -> Array:
	return [_m(v), GREEN if float(v) > 0.0 else (RED if float(v) < 0.0 else INK)]


static func _m2(v: Variant) -> String:
	return "£%.2f" % float(v)


static func _p(v: Variant) -> String:
	return "%.0f%%" % float(v)


static func _pc(v: Variant) -> Array:
	return ["%+.0f%%" % float(v), GREEN if float(v) > 0.0 else (RED if float(v) < 0.0 else INK)]


static func _days(v: Variant) -> String:
	return "—" if float(v) < 0.0 else "%.1f days" % float(v)


static func _hours(v: Variant) -> String:
	return "%.1f h" % float(v)


static func _sign(v: Variant) -> Color:
	return GREEN if float(v) > 0.0 else (RED if float(v) < 0.0 else INK)


static func _grade_cell(g: String) -> Array:
	return [UnitData.grade_label(g), grade_colour(g)]


# ================================================================= drawing

class _Meter extends Control:
	var frac := 0.0
	var colour := Color.BLUE

	func _draw() -> void:
		var h := size.y
		draw_style_box(_pill(Color(0.9, 0.91, 0.93), h), Rect2(Vector2.ZERO, size))
		if frac > 0.0:
			draw_style_box(_pill(colour, h), Rect2(Vector2.ZERO, Vector2(maxf(size.x * frac, h), h)))

	static func _pill(c: Color, h: float) -> StyleBoxFlat:
		var sb := StyleBoxFlat.new()
		sb.bg_color = c
		sb.set_corner_radius_all(int(h / 2))
		return sb


class _Split extends Control:
	## One horizontal bar split into proportional segments with 2 px gaps.
	var parts: Array = []      # [[value, colour], …]

	func _draw() -> void:
		var total := 0.0
		for p in parts:
			total += maxf(float(p[0]), 0.0)
		if total <= 0.0:
			return
		var x := 0.0
		for p in parts:
			var w := maxf(float(p[0]), 0.0) / total * size.x
			if w <= 0.5:
				continue
			var sb := StyleBoxFlat.new()
			sb.bg_color = p[1]
			sb.set_corner_radius_all(4)
			draw_style_box(sb, Rect2(x, 0, maxf(w - 2.0, 1.0), size.y))
			x += w


class _Heat extends Control:
	## Weekday × hour grid, one sequential hue (light → dark) with the count in each cell.
	var rows: Array = []        # [[count per hour], …] per weekday
	var row_labels: Array = []
	var col_labels: Array = []
	var peak := 1
	const LEFT := 54.0
	const TOP := 22.0

	func _draw() -> void:
		var font := ThemeDB.fallback_font
		if rows.is_empty():
			return
		var nc: int = rows[0].size()
		var cw := (size.x - LEFT) / nc
		var ch := (size.y - TOP) / rows.size()
		var lo := Color(0.93, 0.95, 0.99)
		var hi := Color(0.12, 0.3, 0.68)
		for c in nc:
			draw_string(font, Vector2(LEFT + c * cw, 15), str(col_labels[c]), HORIZONTAL_ALIGNMENT_CENTER, cw, 13, Color(0.45, 0.48, 0.52))
		for r in rows.size():
			draw_string(font, Vector2(0, TOP + r * ch + ch / 2 + 5), str(row_labels[r]), HORIZONTAL_ALIGNMENT_LEFT, LEFT - 6, 14, Color(0.3, 0.32, 0.36))
			for c in nc:
				var v := int(rows[r][c])
				var t := float(v) / maxf(float(peak), 1.0)
				var rect := Rect2(LEFT + c * cw + 1, TOP + r * ch + 1, cw - 2, ch - 2)
				var sb := StyleBoxFlat.new()
				sb.bg_color = lo.lerp(hi, t)
				sb.set_corner_radius_all(4)
				draw_style_box(sb, rect)
				if v > 0:
					draw_string(font, rect.position + Vector2(0, rect.size.y / 2 + 6), str(v), HORIZONTAL_ALIGNMENT_CENTER, rect.size.x, 15,
						Color.WHITE if t > 0.55 else Color(0.15, 0.17, 0.2))


class _Chart extends Control:
	## Daily chart on one axis: optional bars + optional line, recessive grid, hover readout.
	var bars: Array = []
	var line: Array = []
	var labels: Array = []
	var bar_col := Color.BLUE
	var line_col := Color.PURPLE
	var money := true
	var _hover := -1
	const PAD_L := 78.0
	const PAD_B := 26.0
	const PAD_T := 10.0

	func _ready() -> void:
		mouse_filter = Control.MOUSE_FILTER_STOP
		mouse_exited.connect(func():
			_hover = -1
			queue_redraw())

	func _count() -> int:
		return maxi(bars.size(), line.size())

	func _gui_input(e: InputEvent) -> void:
		if e is InputEventMouseMotion:
			var n := _count()
			var w := (size.x - PAD_L) / maxf(n, 1)
			var i := int(floor((e.position.x - PAD_L) / w))
			var h := i if i >= 0 and i < n else -1
			if h != _hover:
				_hover = h
				queue_redraw()

	func _fmt(v: float) -> String:
		if money:
			return GameState.format_money(int(round(v)))
		return "%.1f" % v

	func _draw() -> void:
		var font := ThemeDB.fallback_font
		var n := _count()
		if n == 0:
			return
		var lo := 0.0
		var hi := 0.0
		for v in bars + line:
			lo = minf(lo, float(v))
			hi = maxf(hi, float(v))
		if not money and line.size() > 0 and bars.is_empty():
			lo = float(line.min()) - 1.0     # a reputation line reads better without a zero baseline
		if hi - lo < 1.0:
			hi = lo + 1.0
		var step := _nice((hi - lo) / 4.0)
		lo = floor(lo / step) * step
		hi = ceil(hi / step) * step
		var plot := Rect2(PAD_L, PAD_T, size.x - PAD_L - 6.0, size.y - PAD_T - PAD_B)
		var y_of := func(v: float) -> float: return plot.end.y - (v - lo) / (hi - lo) * plot.size.y
		var g := lo
		while g <= hi + 0.001:
			var y: float = y_of.call(g)
			var zero := absf(g) < 0.001
			draw_line(Vector2(plot.position.x, y), Vector2(plot.end.x, y), Color(0.55, 0.58, 0.62, 0.55 if zero else 0.18), 1.0)
			draw_string(font, Vector2(4, y + 5), _fmt(g), HORIZONTAL_ALIGNMENT_RIGHT, PAD_L - 12, 13, Color(0.45, 0.48, 0.52))
			g += step
		var slot := plot.size.x / n
		if _hover >= 0:
			draw_rect(Rect2(plot.position.x + _hover * slot, plot.position.y, slot, plot.size.y), Color(0.2, 0.3, 0.5, 0.06))
		var zero_y: float = y_of.call(clampf(0.0, lo, hi))
		var bw := minf(slot * 0.62, 30.0)
		for i in bars.size():
			var v := float(bars[i])
			if absf(v) < 0.001:
				continue
			var y: float = y_of.call(v)
			var x := plot.position.x + i * slot + (slot - bw) / 2.0
			var sb := StyleBoxFlat.new()
			sb.bg_color = bar_col if (_hover == -1 or _hover == i) else bar_col.lerp(Color.WHITE, 0.35)
			if v >= 0.0:
				sb.corner_radius_top_left = 4
				sb.corner_radius_top_right = 4
				draw_style_box(sb, Rect2(x, y, bw, zero_y - y))
			else:
				sb.corner_radius_bottom_left = 4
				sb.corner_radius_bottom_right = 4
				draw_style_box(sb, Rect2(x, zero_y, bw, y - zero_y))
		if line.size() > 0:
			var pts := PackedVector2Array()
			for i in line.size():
				pts.append(Vector2(plot.position.x + (i + 0.5) * slot, y_of.call(float(line[i]))))
			if pts.size() > 1:
				draw_polyline(pts, line_col, 2.0, true)
			for i in pts.size():
				if pts.size() <= 31 or i == _hover:
					draw_circle(pts[i], 5.0 if i == _hover else 3.5, Color.WHITE)
					draw_circle(pts[i], 3.5 if i == _hover else 2.5, line_col)
		var every := maxi(1, int(ceil(n / 10.0)))
		for i in range(0, n, every):
			if i < labels.size():
				draw_string(font, Vector2(plot.position.x + i * slot, size.y - 6), str(labels[i]), HORIZONTAL_ALIGNMENT_CENTER, slot * every, 12, Color(0.45, 0.48, 0.52))
		if _hover >= 0:
			var lines: Array = [str(labels[_hover]) if _hover < labels.size() else ""]
			if _hover < bars.size():
				lines.append("Revenue " + _fmt(float(bars[_hover])))
			if _hover < line.size():
				lines.append(("Net profit " if not bars.is_empty() else "") + _fmt(float(line[_hover])))
			var w := 0.0
			for t in lines:
				w = maxf(w, font.get_string_size(t, HORIZONTAL_ALIGNMENT_LEFT, -1, 14).x)
			var box := Rect2(0, 0, w + 20, lines.size() * 19 + 12)
			box.position.x = clampf(plot.position.x + (_hover + 0.5) * slot + 12, 0, size.x - box.size.x)
			box.position.y = PAD_T
			var sb := StyleBoxFlat.new()
			sb.bg_color = Color(0.1, 0.12, 0.15, 0.94)
			sb.set_corner_radius_all(6)
			draw_style_box(sb, box)
			for k in lines.size():
				draw_string(font, box.position + Vector2(10, 22 + k * 19), lines[k], HORIZONTAL_ALIGNMENT_LEFT, -1, 14, Color.WHITE if k > 0 else Color(0.75, 0.78, 0.82))

	static func _nice(raw: float) -> float:
		var mag := pow(10.0, floor(log(maxf(raw, 0.0001)) / log(10.0)))
		for m in [1.0, 2.0, 2.5, 5.0, 10.0]:
			if raw <= m * mag:
				return m * mag
		return 10.0 * mag
