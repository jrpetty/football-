class_name UiTheme
## The look of everything outside the 3D world: one palette, one set of type styles, and the Theme that every
## control inherits. Restrained and a little worn — dark slabs, warm hairlines, condensed capitals, amber
## kept for the things that matter.

const INK := Color(0.9, 0.875, 0.815)
const DIM := Color(0.63, 0.61, 0.555)
const FAINT := Color(0.45, 0.435, 0.4)
const PANEL := Color(0.05, 0.053, 0.05, 0.93)
const SLAB := Color(0.03, 0.032, 0.03, 0.6)
const RAISED := Color(1, 1, 1, 0.045)
const SUNK := Color(0, 0, 0, 0.32)
const LINE := Color(0.86, 0.78, 0.63, 0.14)
const LINE2 := Color(0.86, 0.78, 0.63, 0.3)
const ACCENT := Color(0.91, 0.64, 0.23)
const ACCENT2 := Color(1.0, 0.81, 0.48)
const BAD := Color(0.86, 0.32, 0.24)
const WARN := Color(0.92, 0.73, 0.3)
const GOOD := Color(0.56, 0.75, 0.38)
const BLUE := Color(0.45, 0.67, 0.86)
const SHADOW := Color(0, 0, 0, 0.6)

## status colours that do not lean on red against green (the "colour-blind-safe" option): blue, yellow, orange
const CB_GOOD := Color(0.4, 0.7, 0.95)
const CB_WARN := Color(0.96, 0.84, 0.32)
const CB_BAD := Color(0.95, 0.5, 0.12)
static var cb := false

static func good() -> Color:
	return CB_GOOD if cb else GOOD

static func warn() -> Color:
	return CB_WARN if cb else WARN

static func bad() -> Color:
	return CB_BAD if cb else BAD

static var _fonts: Dictionary = {}
static var _theme: Theme = null
static var _tex: Dictionary = {}

# ------------------------------------------------------------------ type
## body: running text.  head: condensed, tracked capitals for titles and buttons.  num: condensed numerals.
## cap: tiny tracked captions.  title: the logo.  The engine's own font is the only one that is certain to be
## there, so every style is a variation of it.
static func font(kind: String) -> Font:
	if _fonts.is_empty():
		var base: Font = ThemeDB.fallback_font
		_fonts["body"] = base
		_fonts["head"] = _variation(base, 0.84, 0.3, 2)
		_fonts["num"] = _variation(base, 0.84, 0.25, 1)
		_fonts["cap"] = _variation(base, 0.9, 0.1, 2)
		_fonts["title"] = _variation(base, 0.74, 0.7, 12)
		_fonts["logo"] = _variation(base, 0.72, 0.9, 22)
	return _fonts.get(kind, _fonts["body"])

static func _variation(base: Font, squeeze: float, bold: float, track: int) -> FontVariation:
	var f := FontVariation.new()
	f.base_font = base
	f.variation_transform = Transform2D(Vector2(squeeze, 0), Vector2(0, 1), Vector2.ZERO)
	f.variation_embolden = bold
	f.spacing_glyph = track
	return f

# ------------------------------------------------------------------ boxes
static func flat(col: Color, radius := 2, border := Color(0, 0, 0, 0), bw := 0, margin := 8.0) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = col
	s.set_corner_radius_all(radius)
	s.border_color = border
	s.set_border_width_all(bw)
	s.set_content_margin_all(margin)
	s.anti_aliasing = radius > 0
	return s

static func flat_m(col: Color, border: Color, bw: int, mx: float, my: float, radius := 2) -> StyleBoxFlat:
	var s := flat(col, radius, border, bw, 0.0)
	s.content_margin_left = mx; s.content_margin_right = mx
	s.content_margin_top = my; s.content_margin_bottom = my
	return s

static func empty(mx := 0.0, my := 0.0) -> StyleBoxEmpty:
	var s := StyleBoxEmpty.new()
	s.content_margin_left = mx; s.content_margin_right = mx
	s.content_margin_top = my; s.content_margin_bottom = my
	return s

## the colour of a 0–100 condition: good, worn, failing
static func condition(pct: float) -> Color:
	if pct > 60.0: return good()
	if pct > 30.0: return warn()
	return bad()

static func level_color(level: int) -> Color:
	if cb:
		return [DIM, Color(0.75, 0.86, 0.95), CB_WARN, CB_BAD, Color(1.0, 0.36, 0.5)][clampi(level, 0, 4)]
	return [DIM, ACCENT2, WARN, Color(0.95, 0.52, 0.27), BAD][clampi(level, 0, 4)]

# ------------------------------------------------------------------ small generated images
static func tex(kind: String) -> Texture2D:
	if _tex.has(kind):
		return _tex[kind]
	var img: Image
	match kind:
		"grain":
			img = Image.create(128, 128, false, Image.FORMAT_RGBA8)
			var rng := RandomNumberGenerator.new(); rng.seed = 7
			for y in 128:
				for x in 128:
					var n := rng.randf()
					img.set_pixel(x, y, Color(n, n, n, 0.5 + rng.randf() * 0.5))
		"check_on", "check_off", "check_on_dis", "check_off_dis":
			img = Image.create(20, 20, false, Image.FORMAT_RGBA8)
			var on := kind.begins_with("check_on")
			var dis := kind.ends_with("_dis")
			var edge := (LINE if dis else LINE2) if not on else (FAINT if dis else ACCENT)
			for y in 20:
				for x in 20:
					var border := x < 1 or y < 1 or x > 18 or y > 18
					var inner := x >= 5 and y >= 5 and x <= 14 and y <= 14
					var c := Color(0, 0, 0, 0.35)
					if border: c = edge
					elif on and inner: c = FAINT if dis else ACCENT
					img.set_pixel(x, y, c)
		"grab", "grab_hi", "grab_dis":
			img = Image.create(10, 20, false, Image.FORMAT_RGBA8)
			var gc := INK if kind == "grab" else (ACCENT2 if kind == "grab_hi" else FAINT)
			for y in 20:
				for x in 10:
					var corner := (x == 0 or x == 9) and (y == 0 or y == 19)
					img.set_pixel(x, y, Color(0, 0, 0, 0) if corner else (Color(0, 0, 0, 0.7) if (x == 0 or x == 9 or y == 0 or y == 19) else gc))
		_:
			img = Image.create(2, 2, false, Image.FORMAT_RGBA8)
			img.fill(Color.WHITE)
	var t := ImageTexture.create_from_image(img)
	_tex[kind] = t
	return t

# ------------------------------------------------------------------ the theme
static func theme() -> Theme:
	if _theme != null:
		return _theme
	var t := Theme.new()
	t.default_font = font("body")
	t.default_font_size = 16

	t.set_color("font_color", "Label", INK)
	t.set_constant("line_spacing", "Label", 3)

	# buttons: a hairline box that warms up under the pointer
	var bn := flat_m(RAISED, LINE2, 1, 16, 7)
	var bh := flat_m(Color(ACCENT, 0.13), ACCENT, 1, 16, 7)
	var bp := flat_m(Color(ACCENT, 0.26), ACCENT2, 1, 16, 7)
	var bd := flat_m(Color(1, 1, 1, 0.015), LINE, 1, 16, 7)
	var bf := flat_m(Color(0, 0, 0, 0), ACCENT2, 1, 16, 7)
	bf.draw_center = false
	for type in ["Button", "OptionButton"]:
		t.set_stylebox("normal", type, bn); t.set_stylebox("hover", type, bh); t.set_stylebox("pressed", type, bp)
		t.set_stylebox("hover_pressed", type, bp)
		t.set_stylebox("disabled", type, bd); t.set_stylebox("focus", type, bf)
		t.set_font("font", type, font("head")); t.set_font_size("font_size", type, 15)
		t.set_color("font_color", type, INK); t.set_color("font_hover_color", type, ACCENT2)
		t.set_color("font_pressed_color", type, ACCENT2); t.set_color("font_hover_pressed_color", type, ACCENT2)
		t.set_color("font_focus_color", type, INK); t.set_color("font_disabled_color", type, FAINT)
		t.set_constant("h_separation", type, 8)

	# text entry
	var le := flat_m(SUNK, LINE2, 1, 10, 7)
	var lef := flat_m(SUNK, ACCENT, 1, 10, 7)
	t.set_stylebox("normal", "LineEdit", le); t.set_stylebox("focus", "LineEdit", lef); t.set_stylebox("read_only", "LineEdit", le)
	t.set_color("font_color", "LineEdit", INK); t.set_color("caret_color", "LineEdit", ACCENT2)
	t.set_color("selection_color", "LineEdit", Color(ACCENT, 0.35)); t.set_color("font_placeholder_color", "LineEdit", FAINT)
	t.set_font_size("font_size", "LineEdit", 17)

	# sliders: a thin track, the travelled part in amber, a plain block to hold
	var track := flat(Color(1, 1, 1, 0.1), 0, Color(0, 0, 0, 0), 0, 0.0)
	track.content_margin_top = 2; track.content_margin_bottom = 2
	var fill := flat(ACCENT, 0, Color(0, 0, 0, 0), 0, 0.0)
	fill.content_margin_top = 2; fill.content_margin_bottom = 2
	var fill_hi := fill.duplicate(); fill_hi.bg_color = ACCENT2
	t.set_stylebox("slider", "HSlider", track); t.set_stylebox("grabber_area", "HSlider", fill); t.set_stylebox("grabber_area_highlight", "HSlider", fill_hi)
	t.set_icon("grabber", "HSlider", tex("grab")); t.set_icon("grabber_highlight", "HSlider", tex("grab_hi")); t.set_icon("grabber_disabled", "HSlider", tex("grab_dis"))

	# check boxes
	for type in ["CheckBox", "CheckButton"]:
		t.set_icon("checked", type, tex("check_on")); t.set_icon("unchecked", type, tex("check_off"))
		t.set_icon("checked_disabled", type, tex("check_on_dis")); t.set_icon("unchecked_disabled", type, tex("check_off_dis"))
		for sb in ["normal", "hover", "pressed", "hover_pressed", "disabled"]:
			t.set_stylebox(sb, type, empty(2, 4))
		var cf := flat_m(Color(0, 0, 0, 0), Color(ACCENT2, 0.7), 1, 2, 4); cf.draw_center = false
		t.set_stylebox("focus", type, cf)
		t.set_font("font", type, font("body")); t.set_font_size("font_size", type, 16)
		t.set_color("font_color", type, INK); t.set_color("font_hover_color", type, ACCENT2); t.set_color("font_pressed_color", type, INK)
		t.set_color("font_hover_pressed_color", type, ACCENT2)

	# scroll bars: out of the way until you want them
	for type in ["VScrollBar", "HScrollBar"]:
		var s_track := flat(Color(1, 1, 1, 0.03), 0, Color(0, 0, 0, 0), 0, 3.0)
		t.set_stylebox("scroll", type, s_track); t.set_stylebox("scroll_focus", type, s_track)
		t.set_stylebox("grabber", type, flat(Color(1, 1, 1, 0.16), 0, Color(0, 0, 0, 0), 0, 3.0))
		t.set_stylebox("grabber_highlight", type, flat(Color(1, 1, 1, 0.3), 0, Color(0, 0, 0, 0), 0, 3.0))
		t.set_stylebox("grabber_pressed", type, flat(Color(ACCENT, 0.8), 0, Color(0, 0, 0, 0), 0, 3.0))
	t.set_stylebox("panel", "ScrollContainer", empty())

	# tooltips
	t.set_stylebox("panel", "TooltipPanel", flat_m(Color(0.035, 0.036, 0.034, 0.97), LINE2, 1, 11, 8))
	t.set_color("font_color", "TooltipLabel", INK)
	t.set_font_size("font_size", "TooltipLabel", 14)
	t.set_color("font_shadow_color", "TooltipLabel", Color(0, 0, 0, 0))

	# plain panels and bars, for anything built without a builder
	t.set_stylebox("panel", "PanelContainer", flat(PANEL, 2, LINE, 1, 12.0))
	t.set_stylebox("panel", "Panel", flat(PANEL, 2, LINE, 1, 12.0))
	t.set_stylebox("background", "ProgressBar", flat(Color(0, 0, 0, 0.5), 0, Color(0, 0, 0, 0), 0, 0.0))
	t.set_stylebox("fill", "ProgressBar", flat(ACCENT, 0, Color(0, 0, 0, 0), 0, 0.0))
	var sep := StyleBoxLine.new(); sep.color = LINE; sep.thickness = 1
	t.set_stylebox("separator", "HSeparator", sep)
	var vsep := StyleBoxLine.new(); vsep.color = LINE; vsep.thickness = 1; vsep.vertical = true
	t.set_stylebox("separator", "VSeparator", vsep)
	t.set_constant("separation", "HSeparator", 9); t.set_constant("separation", "VSeparator", 9)
	t.set_color("default_color", "RichTextLabel", INK)
	t.set_font_size("normal_font_size", "RichTextLabel", 15)
	t.set_constant("separation", "VBoxContainer", 6)
	t.set_constant("separation", "HBoxContainer", 8)
	t.set_constant("h_separation", "GridContainer", 14); t.set_constant("v_separation", "GridContainer", 6)
	_theme = t
	return t
