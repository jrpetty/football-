extends PaneApp
## Mail: onboarding emails + business messages. Content comes from data/mail.json.

var _list: VBoxContainer
var _view: VBoxContainer
var _open := 0


func build() -> void:
	var h := HBoxContainer.new()
	h.size_flags_vertical = Control.SIZE_EXPAND_FILL
	h.add_theme_constant_override("separation", 16)
	add_child(h)
	_list = VBoxContainer.new()
	_list.custom_minimum_size.x = 420
	h.add_child(_list)
	var sc := ScrollContainer.new()
	sc.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	sc.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	h.add_child(sc)
	_view = VBoxContainer.new()
	_view.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	sc.add_child(_view)


func _mails() -> Array:
	var path := "res://data/mail.json"
	if not FileAccess.file_exists(path):
		return []
	var d: Variant = JSON.parse_string(FileAccess.get_file_as_string(path))
	var out: Array = d.get("mail", []) if d is Dictionary else []
	return out.filter(func(m): return int(m.get("day", 0)) <= Game.state.day_index())


func refresh() -> void:
	clear(_list)
	var mails := _mails()
	for i in mails.size():
		var m: Dictionary = mails[i]
		var b := Button.new()
		b.add_theme_stylebox_override("normal", UiStyle.box(Color(0.9, 0.94, 1.0) if i == _open else CARD, 8, 12, BORDER))
		b.add_theme_stylebox_override("hover", UiStyle.box(Color(0.94, 0.96, 1.0), 8, 12, BORDER))
		b.custom_minimum_size.y = 70
		var v := VBoxContainer.new()
		v.mouse_filter = Control.MOUSE_FILTER_IGNORE
		v.set_anchors_preset(Control.PRESET_FULL_RECT)
		v.offset_left = 12
		v.offset_top = 8
		b.add_child(v)
		var f := l(m.get("from", ""), 15, SUB)
		f.mouse_filter = Control.MOUSE_FILTER_IGNORE
		v.add_child(f)
		var s := l(m.get("subject", ""), 18)
		s.mouse_filter = Control.MOUSE_FILTER_IGNORE
		v.add_child(s)
		b.pressed.connect(func():
			_open = i
			refresh())
		_list.add_child(b)
	clear(_view)
	if mails.is_empty():
		return
	var m: Dictionary = mails[clampi(_open, 0, mails.size() - 1)]
	_view.add_child(l(m.get("subject", ""), 26))
	_view.add_child(l("From: " + m.get("from", ""), 16, SUB))
	_view.add_child(HSeparator.new())
	_view.add_child(para(String(m.get("body", "")).replace("{company}", Game.state.company_name), 19, INK, 760))
