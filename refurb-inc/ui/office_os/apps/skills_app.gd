extends PaneApp
## Per-player skills (data/skills.json). Doing the work earns XP; levels make you faster and unlock perks.
## Everyone can do every job; in co-op each player levels separately while money and stock are shared.

var _list: VBoxContainer


func build() -> void:
	add_child(para("Do the work, earn the XP. New starters are slow; every level speeds that job up, and perks unlock new abilities. In co-op each player has their own skills — sales traffic uses the team's best seller.", 17, SUB, 900))
	var sl := scroll_list()
	add_child(sl[0])
	_list = sl[1]


func refresh() -> void:
	clear(_list)
	var keys: Array = Game.state.players.keys().filter(func(k): return not Game.state.players[k].get("staff", false))
	var me := Game.local_player_key()
	keys.sort_custom(func(a, b): return a == me and b != me)
	if keys.is_empty():
		keys = [me]
	for key in keys:
		_player_block(key, key == me)


func _player_block(key: String, is_me: bool) -> void:
	var p: Dictionary = Game.state.player(key)
	var head := HBoxContainer.new()
	head.add_theme_constant_override("separation", 12)
	head.add_child(l(str(p.get("name", key)) + ("  (you)" if is_me else ""), 26))
	var total := 0
	for sk: Dictionary in Content.skill_defs():
		total += Game.state.skill_level(key, sk.id)
	head.add_child(pill(" Total level %d " % total, BLUE, Color.WHITE, 15))
	_list.add_child(head)
	var grid := GridContainer.new()
	grid.columns = 2
	grid.add_theme_constant_override("h_separation", 14)
	grid.add_theme_constant_override("v_separation", 14)
	_list.add_child(grid)
	for sk: Dictionary in Content.skill_defs():
		grid.add_child(_skill_card(key, sk))


func _skill_card(key: String, sk: Dictionary) -> Control:
	var c := card()
	c.custom_minimum_size.x = 440
	c.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 6)
	c.add_child(v)
	var col := Color(str(sk.get("colour", "#2e7dd1")))
	var lvl := Game.state.skill_level(key, sk.id)
	var xp := int(Game.state.player(key).get("xp", {}).get(sk.id, 0))
	var table: Array = Content.skills_data.get("xp_levels", [])
	var maxl := int(Content.skills_data.get("max_level", 10))
	var top := HBoxContainer.new()
	top.add_theme_constant_override("separation", 10)
	top.add_child(pill(" %s " % sk.get("icon", "•"), col, Color.WHITE, 20))
	var name_l := l(sk.name, 21)
	name_l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(name_l)
	top.add_child(l("Lv %d" % lvl, 21, col))
	v.add_child(top)
	# XP bar to the next level.
	var bar := ProgressBar.new()
	bar.custom_minimum_size = Vector2(0, 12)
	bar.show_percentage = false
	var lo := int(table[lvl - 1]) if lvl - 1 < table.size() else 0
	var hi := int(table[lvl]) if lvl < table.size() else lo
	bar.max_value = maxf(hi - lo, 1)
	bar.value = (xp - lo) if lvl < maxl else bar.max_value
	var fill := StyleBoxFlat.new()
	fill.bg_color = col
	fill.set_corner_radius_all(6)
	var bg := StyleBoxFlat.new()
	bg.bg_color = Color(0.9, 0.91, 0.93)
	bg.set_corner_radius_all(6)
	bar.add_theme_stylebox_override("fill", fill)
	bar.add_theme_stylebox_override("background", bg)
	v.add_child(bar)
	var speed := Content.skill_table("speed", lvl, 1.0)
	var status := "%d XP · next level at %d" % [xp, hi] if lvl < maxl else "%d XP · max level" % xp
	var effect := "Works at %d%% speed" % int(round(speed * 100))
	match sk.id:
		"hauling":
			effect = "Carries %d at once" % int(Content.skill_table("carry", lvl, 3))
		"purchasing":
			effect = "%d%% off every lot" % int(round(float(Content.skills_data.get("purchase_discount_per_level", 0.01)) * (lvl - 1) * 100))
		"sales":
			effect = "+%d%% buyer traffic (team best counts)" % int(round(float(Content.skills_data.get("sales_traffic_per_level", 0.04)) * (lvl - 1) * 100))
		"repair":
			var tiers: Dictionary = Content.skills_data.get("repair_tiers", {})
			var can := 1
			for t in tiers:
				if lvl >= int(tiers[t]):
					can = maxi(can, int(t))
			effect = "Works at %d%% speed · fixes level %d faults" % [int(round(speed * 100)), can]
	v.add_child(l(effect, 16, INK))
	v.add_child(l(status, 14, SUB))
	v.add_child(para(sk.get("desc", ""), 14, SUB, 400))
	var perks: Array = Content.perks_for(sk.id)
	perks.sort_custom(func(a, b): return int(a.level) < int(b.level))
	for pk: Dictionary in perks:
		var got := lvl >= int(pk.level)
		var row := HBoxContainer.new()
		row.add_theme_constant_override("separation", 8)
		row.add_child(pill(" ✔ " if got else " Lv %d " % int(pk.level), GREEN if got else Color(0.8, 0.82, 0.85), Color.WHITE if got else SUB, 13))
		var t := para("%s — %s" % [pk.name, pk.desc], 14, INK if got else SUB, 360)
		t.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		row.add_child(t)
		v.add_child(row)
	return c
