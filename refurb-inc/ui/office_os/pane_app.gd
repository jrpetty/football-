class_name PaneApp
extends VBoxContainer
## Base for PaneOS apps. build() once, refresh() whenever game state changes.

const INK := Color(0.13, 0.15, 0.18)
const SUB := Color(0.42, 0.45, 0.5)
const CARD := Color(1, 1, 1)
const CARD_ALT := Color(0.97, 0.975, 0.98)
const BORDER := Color(0.85, 0.87, 0.9)
const GREEN := Color(0.12, 0.6, 0.3)
const RED := Color(0.82, 0.22, 0.18)
const AMBER := Color(0.85, 0.55, 0.05)
const BLUE := Color(0.2, 0.45, 0.85)

var os: PaneOS


func build() -> void:
	pass


func refresh() -> void:
	pass


static func l(txt: String, size: int = 20, col: Color = INK) -> Label:
	var lab := Label.new()
	lab.text = txt
	lab.add_theme_font_size_override("font_size", size)
	lab.add_theme_color_override("font_color", col)
	return lab


static func para(txt: String, size: int = 18, col: Color = SUB, min_w: float = 300) -> Label:
	var lab := l(txt, size, col)
	lab.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	lab.custom_minimum_size.x = min_w
	return lab


static func card(bg: Color = CARD) -> PanelContainer:
	var p := PanelContainer.new()
	p.add_theme_stylebox_override("panel", UiStyle.box(bg, 10, 16, BORDER))
	return p


static func pill(txt: String, bg: Color, fg: Color = Color.WHITE, size: int = 15) -> PanelContainer:
	var p := PanelContainer.new()
	p.add_theme_stylebox_override("panel", UiStyle.box(bg, 12, 8))
	p.size_flags_horizontal = Control.SIZE_SHRINK_BEGIN
	p.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	p.add_child(l(txt, size, fg))
	return p


static func button(txt: String, cb: Callable, colour: Color = BLUE, disabled: bool = false) -> Button:
	var b := Button.new()
	b.text = "  %s  " % txt
	b.disabled = disabled
	if colour != BLUE:
		b.add_theme_stylebox_override("normal", UiStyle.box(colour, 8, 12))
		b.add_theme_stylebox_override("hover", UiStyle.box(colour.lightened(0.12), 8, 12))
		b.add_theme_stylebox_override("pressed", UiStyle.box(colour.darkened(0.15), 8, 12))
	b.pressed.connect(func():
		Sfx.play_ui("ui_click", -8.0)
		cb.call())
	return b


static func scroll_list() -> Array:
	## Returns [ScrollContainer, VBoxContainer]
	var sc := ScrollContainer.new()
	sc.size_flags_vertical = Control.SIZE_EXPAND_FILL
	sc.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	var v := VBoxContainer.new()
	v.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	v.add_theme_constant_override("separation", 10)
	sc.add_child(v)
	return [sc, v]


static func clear(n: Node) -> void:
	for c in n.get_children():
		n.remove_child(c)
		c.queue_free()


static func grade_colour(g: String) -> Color:
	return {"A": GREEN, "B": BLUE, "C": AMBER, "D": RED, "S": Color(0.45, 0.45, 0.5)}.get(g, SUB)


func tabs(names: Array, on_select: Callable) -> TabBar:
	var tb := TabBar.new()
	for n in names:
		tb.add_tab(n)
	tb.add_theme_font_size_override("font_size", 19)
	tb.tab_changed.connect(func(i):
		Sfx.play_ui("ui_click", -10.0)
		on_select.call(i))
	return tb
