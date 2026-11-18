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
	out = out.filter(func(m): return int(m.get("day", 0)) <= Game.state.day_index())
	# Messages from the business itself (staff asking for a rise, resignations…), newest first, at the top.
	var inbox: Array = Game.state.totals.get("inbox", []).duplicate()
	inbox.reverse()
	# Business buyers email in (newest first): enquiries to answer, then orders on the go and recent ones.
	var biz: Array = []
	var cs: Array = Game.state.contracts.values()
	cs.sort_custom(func(a, b): return float(a.get("offered", 0.0)) > float(b.get("offered", 0.0)))
	for c: Dictionary in cs.slice(0, 12):
		var tag: String = {"offer": "● NEW ENQUIRY  ", "active": "▶ ON THE GO  "}.get(str(c.get("status", "")), "")
		biz.append({"from": tag + str(c.get("buyer", "")), "subject": str(c.get("subject", "")), "body": str(c.get("body", "")), "contract": int(c.id)})
	if Game.state.totals.get("biz_unlocked", false):
		var w: Dictionary = Business.cfg("welcome", {})
		if not w.is_empty():
			biz.append(w)
	return inbox + biz + out


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
	if m.has("contract"):
		var c: Dictionary = Game.state.contracts.get(int(m.contract), {})
		if c.is_empty():
			return
		_view.add_child(HSeparator.new())
		_view.add_child(l("%s · %s · due %s" % [Business.title(c), GameState.format_money(Business.value(c)), GameState.format_minute(float(c.due))], 18, SUB))
		var row := HBoxContainer.new()
		row.add_theme_constant_override("separation", 10)
		_view.add_child(row)
		if c.status == "offer":
			row.add_child(button("Accept", func(): Game.request("biz_accept", {"contract": int(c.id)}), GREEN))
			row.add_child(button("Decline", func(): Game.request("biz_decline", {"contract": int(c.id)}), Color(0.55, 0.57, 0.6)))
		else:
			row.add_child(l(Business.status_text(c), 17, GREEN if c.status in ["active", "complete"] else AMBER))
		row.add_child(button("Open in Business", func(): if os: os.open_app("business")))
