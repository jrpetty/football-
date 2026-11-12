extends RefCounted
## Small things the sys_* modules share: costs against an inventory, cost chips for the windows, opening and
## closing a HUD window with the mouse handled, and a ray against a box.

const SKILL_NAMES := {"firstaid": "First Aid", "lightfoot": "Lightfooted"}

static func skill_name(id: String) -> String:
	return String(SKILL_NAMES.get(id, id.capitalize()))

## a JSON cost table ({item: n}) with whole numbers
static func cost_of(table) -> Dictionary:
	var out := {}
	if table is Dictionary:
		for k in table:
			out[String(k)] = int(table[k])
	return out

static func has_all(inv: Inventory, cost: Dictionary) -> bool:
	for k in cost:
		if inv.count(String(k)) < int(cost[k]):
			return false
	return true

static func take_all(inv: Inventory, cost: Dictionary) -> bool:
	if not has_all(inv, cost):
		return false
	for k in cost:
		inv.remove(String(k), int(cost[k]))
	return true

static func cost_text(cost: Dictionary) -> String:
	var bits: Array = []
	for k in cost:
		bits.append("%d %s" % [int(cost[k]), String(Items.def(String(k)).name)])
	return " · ".join(bits)

## a row of "icon have/need" chips; what is short shows red
static func cost_row(hud, inv: Inventory, cost: Dictionary, size := 13, names := false) -> HBoxContainer:
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	row.mouse_filter = Control.MOUSE_FILTER_IGNORE
	for k in cost:
		var id := String(k)
		var need := int(cost[k]); var have := inv.count(id)
		var chip := HBoxContainer.new()
		chip.add_theme_constant_override("separation", 3)
		chip.mouse_filter = Control.MOUSE_FILTER_IGNORE
		var t := TextureRect.new()
		t.texture = hud.icon("item_" + id)
		t.custom_minimum_size = Vector2(size + 7, size + 7)
		t.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		t.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		t.mouse_filter = Control.MOUSE_FILTER_IGNORE
		chip.add_child(t)
		var text := "%d/%d" % [have, need]
		if names:
			text = "%s  %d/%d" % [String(Items.def(id).name), have, need]
		chip.add_child(hud.label(text, size, hud.INK if have >= need else hud.BAD))
		row.add_child(chip)
	return row

static func open(game, kind: String, w: float, h: float, title: String, sound := true) -> VBoxContainer:
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	if sound:
		Sfx.play("ui_open")
	return game.hud.open_window(kind, w, h, title)

static func close(game, sound := true) -> void:
	game.hud.close_window()
	Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
	if sound:
		Sfx.play("ui_close")

## a flat list row that behaves like the inventory's rows
static func row_button(hud, height := 44.0) -> Button:
	var b := Button.new()
	b.custom_minimum_size = Vector2(0, height)
	b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	b.focus_mode = Control.FOCUS_NONE
	b.add_theme_stylebox_override("normal", hud.box(Color(1, 1, 1, 0.04), 4))
	b.add_theme_stylebox_override("hover", hud.box(Color(0.93, 0.62, 0.25, 0.22), 4))
	b.add_theme_stylebox_override("pressed", hud.box(Color(0.93, 0.62, 0.25, 0.35), 4))
	b.add_theme_stylebox_override("disabled", hud.box(Color(1, 1, 1, 0.02), 4))
	return b

## a small toggle used for tabs and filters
static func tab_button(hud, text: String, on: bool, cb: Callable, w := 0.0) -> Button:
	var b := Button.new()
	b.text = text
	b.focus_mode = Control.FOCUS_NONE
	b.custom_minimum_size = Vector2(w, 32)
	b.add_theme_font_size_override("font_size", 15)
	var normal: StyleBoxFlat = hud.box(Color(0.3, 0.22, 0.12, 1.0) if on else Color(0.14, 0.14, 0.13, 0.95), 5, hud.ACCENT if on else Color(0.3, 0.29, 0.26), 1)
	normal.content_margin_left = 14; normal.content_margin_right = 14
	var hover: StyleBoxFlat = hud.box(Color(0.24, 0.2, 0.14, 0.98), 5, hud.ACCENT, 1)
	hover.content_margin_left = 14; hover.content_margin_right = 14
	b.add_theme_stylebox_override("normal", normal)
	b.add_theme_stylebox_override("hover", hover)
	b.add_theme_stylebox_override("pressed", hover)
	b.add_theme_color_override("font_color", hud.INK)
	b.pressed.connect(cb)
	return b

static func item_icon(hud, id: String, px: float) -> TextureRect:
	var t := TextureRect.new()
	t.texture = hud.icon("item_" + id)
	t.custom_minimum_size = Vector2(px, px)
	t.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	t.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	t.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return t

## distance along a ray to an axis-aligned box, INF on a miss
static func ray_box(origin: Vector3, dir: Vector3, mn: Vector3, mx: Vector3, max_t: float) -> float:
	var tmin := 0.0; var tmax := max_t
	for a in 3:
		if absf(dir[a]) < 1e-9:
			if origin[a] < mn[a] or origin[a] > mx[a]:
				return INF
			continue
		var t1 := (mn[a] - origin[a]) / dir[a]; var t2 := (mx[a] - origin[a]) / dir[a]
		if t1 > t2:
			var tt := t1; t1 = t2; t2 = tt
		tmin = maxf(tmin, t1); tmax = minf(tmax, t2)
		if tmin > tmax:
			return INF
	return tmin

## the module of that name, or null
static func mod(game, name: String):
	if game == null:
		return null
	return game.modules.get(name)
