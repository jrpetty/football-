class_name UiStyle
extends RefCounted
## One place for UI colours/styles so everything looks like the same product.

const BG := Color(0.07, 0.08, 0.1, 0.86)
const BG_SOLID := Color(0.09, 0.1, 0.12)
const PANEL := Color(0.13, 0.14, 0.17)
const LINE := Color(1, 1, 1, 0.08)
const TEXT := Color(0.93, 0.94, 0.96)
const MUTED := Color(0.62, 0.66, 0.72)
const ACCENT := Color(0.96, 0.7, 0.2)        # Refurb Inc. amber
const GOOD := Color(0.36, 0.86, 0.52)
const WARN := Color(1.0, 0.76, 0.28)
const BAD := Color(1.0, 0.42, 0.38)
const INFO := Color(0.45, 0.72, 1.0)

static var _theme: Theme


static func level_colour(level: String) -> Color:
	match level:
		"good": return GOOD
		"warn": return WARN
		"bad": return BAD
	return INFO


static func box(bg: Color = BG, radius: int = 10, pad: int = 12, border: Color = Color(0, 0, 0, 0)) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = bg
	s.set_corner_radius_all(radius)
	s.content_margin_left = pad
	s.content_margin_right = pad
	s.content_margin_top = pad * 0.75
	s.content_margin_bottom = pad * 0.75
	if border.a > 0:
		s.border_color = border
		s.set_border_width_all(1)
	s.anti_aliasing = true
	return s


static func theme() -> Theme:
	if _theme:
		return _theme
	var t := Theme.new()
	t.default_font_size = 17
	t.set_color("font_color", "Label", TEXT)
	t.set_stylebox("panel", "PanelContainer", box())
	t.set_stylebox("panel", "Panel", box())
	var btn := box(Color(0.2, 0.22, 0.26), 8, 10)
	var btn_h := box(Color(0.27, 0.3, 0.35), 8, 10)
	var btn_p := box(ACCENT.darkened(0.15), 8, 10)
	var btn_d := box(Color(0.16, 0.17, 0.19), 8, 10)
	t.set_stylebox("normal", "Button", btn)
	t.set_stylebox("hover", "Button", btn_h)
	t.set_stylebox("pressed", "Button", btn_p)
	t.set_stylebox("disabled", "Button", btn_d)
	t.set_stylebox("focus", "Button", StyleBoxEmpty.new())
	t.set_color("font_color", "Button", TEXT)
	t.set_color("font_hover_color", "Button", Color.WHITE)
	t.set_color("font_pressed_color", "Button", Color(0.08, 0.08, 0.1))
	t.set_color("font_disabled_color", "Button", MUTED.darkened(0.3))
	t.set_stylebox("normal", "LineEdit", box(Color(0.1, 0.11, 0.13), 6, 8, LINE))
	t.set_stylebox("focus", "LineEdit", box(Color(0.1, 0.11, 0.13), 6, 8, ACCENT))
	t.set_stylebox("panel", "TabContainer", box(PANEL, 6, 8))
	t.set_stylebox("slider", "HSlider", box(Color(0.25, 0.27, 0.3), 4, 2))
	t.set_stylebox("grabber_area", "HSlider", box(ACCENT, 4, 2))
	t.set_stylebox("grabber_area_highlight", "HSlider", box(ACCENT.lightened(0.2), 4, 2))
	t.set_constant("separation", "VBoxContainer", 6)
	t.set_constant("separation", "HBoxContainer", 8)
	_theme = t
	return t


static func label(txt: String, size: int = 17, col: Color = TEXT, bold: bool = false) -> Label:
	var l := Label.new()
	l.text = txt
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", col)
	if bold:
		l.add_theme_constant_override("outline_size", 0)
	return l


static func money_colour(v: int) -> Color:
	return GOOD if v >= 0 else BAD
