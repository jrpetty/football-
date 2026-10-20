extends PaneApp
## Per-player skills, earned by doing the work. Higher levels = faster jobs, bigger carries, discounts,
## and (for Repair) the ability to fix level 2 and 3 faults.

const SKILLS := [
	["repair", "Repair", "Level 1: keys, batteries, RAM, drives · Level 2: screens, decks, hinges · Level 3: boards"],
	["testing", "Testing", "Faster diagnostics runs on benches you start (+8% per level)"],
	["imaging", "Imaging", "Faster wipe & image runs (+8% per level)"],
	["hauling", "Hauling", "Carry bigger stacks (+1 every 2 levels)"],
	["packing", "Packing", "Quicker packing (+10% per level)"],
	["grading", "Grading", "Quicker grading (+10% per level)"],
	["purchasing", "Purchasing", "1% discount on lots per level"],
]

var _list: VBoxContainer


func build() -> void:
	add_child(para("Everyone can do every job. Doing the work earns XP in that skill. In co-op each player has their own skills; the company's money and stock are shared.", 17, SUB, 900))
	var sl := scroll_list()
	add_child(sl[0])
	_list = sl[1]


func refresh() -> void:
	clear(_list)
	var table: Array = Content.econ("xp_per_level", [0, 200, 650, 1500])
	for key in Game.state.players:
		var p: Dictionary = Game.state.players[key]
		var c := card()
		var v := VBoxContainer.new()
		c.add_child(v)
		v.add_child(l(str(p.get("name", key)) + ("  (you)" if key == Game.local_player_key() else ""), 24))
		for sk in SKILLS:
			var xp := int(p.get("xp", {}).get(sk[0], 0))
			var lvl := Game.state.skill_level(key, sk[0])
			var nxt := -1
			for t in table:
				if int(t) > xp:
					nxt = int(t)
					break
			var row := HBoxContainer.new()
			var name_l := l("%s  Lv %d" % [sk[1], lvl], 19)
			name_l.custom_minimum_size.x = 220
			row.add_child(name_l)
			var bar := ProgressBar.new()
			bar.custom_minimum_size = Vector2(260, 18)
			bar.show_percentage = false
			bar.max_value = maxf(nxt, 1) if nxt > 0 else 1
			bar.value = xp if nxt > 0 else 1
			row.add_child(bar)
			row.add_child(l("  %d XP%s" % [xp, ("  ·  next level at %d" % nxt) if nxt > 0 else "  ·  max"], 15, SUB))
			v.add_child(row)
			v.add_child(para(sk[2], 14, SUB, 700))
		_list.add_child(c)
