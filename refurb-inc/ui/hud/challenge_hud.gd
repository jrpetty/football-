class_name ChallengeHud
extends CanvasLayer
## Challenges on screen: a banner with the goal, progress and days left while one runs, and the results card when
## it ends (medal, time or score, this PC's board). On the host the finish is posted to local records — and to the
## Steam leaderboard (CHALLENGE_<ID> / DAILY_<yyyymmdd>) when Steam is running. Created by the HUD.

const GOLD := Color(0.98, 0.78, 0.26)

var _banner: PanelContainer
var _b_title: Label
var _b_goal: Label
var _b_days: Label
var _b_bar: ProgressBar
var _results: Control
var _r_body: VBoxContainer
var _t := 0.0
var _status := ""
var _place := -1


func _ready() -> void:
	layer = 41
	process_mode = Node.PROCESS_MODE_ALWAYS
	var root := Control.new()
	root.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.theme = UiStyle.theme()
	add_child(root)
	# Banner: top centre.
	_banner = PanelContainer.new()
	_banner.add_theme_stylebox_override("panel", UiStyle.box(UiStyle.BG, 12, 14, Color(GOLD.r, GOLD.g, GOLD.b, 0.45)))
	_banner.set_anchors_preset(Control.PRESET_CENTER_TOP)
	_banner.position = Vector2(-260, 18)
	_banner.custom_minimum_size = Vector2(520, 0)
	_banner.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_banner.visible = false
	root.add_child(_banner)
	var bv := VBoxContainer.new()
	bv.add_theme_constant_override("separation", 3)
	bv.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_banner.add_child(bv)
	var top := HBoxContainer.new()
	bv.add_child(top)
	_b_title = UiStyle.label("", 18, GOLD)
	_b_title.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_b_title.clip_text = true
	top.add_child(_b_title)
	_b_days = UiStyle.label("", 16, UiStyle.TEXT)
	top.add_child(_b_days)
	_b_goal = UiStyle.label("", 15, UiStyle.MUTED)
	bv.add_child(_b_goal)
	_b_bar = ProgressBar.new()
	_b_bar.show_percentage = false
	_b_bar.custom_minimum_size = Vector2(0, 8)
	var fill := StyleBoxFlat.new()
	fill.bg_color = GOLD
	fill.set_corner_radius_all(4)
	var bg := StyleBoxFlat.new()
	bg.bg_color = Color(1, 1, 1, 0.12)
	bg.set_corner_radius_all(4)
	_b_bar.add_theme_stylebox_override("fill", fill)
	_b_bar.add_theme_stylebox_override("background", bg)
	bv.add_child(_b_bar)
	# Results card.
	_results = Control.new()
	_results.set_anchors_preset(Control.PRESET_FULL_RECT)
	_results.visible = false
	root.add_child(_results)
	var dim := ColorRect.new()
	dim.color = Color(0.02, 0.03, 0.05, 0.72)
	dim.set_anchors_preset(Control.PRESET_FULL_RECT)
	_results.add_child(dim)
	var center := CenterContainer.new()
	center.set_anchors_preset(Control.PRESET_FULL_RECT)
	_results.add_child(center)
	var panel := PanelContainer.new()
	var sb := UiStyle.box(Color(0.1, 0.085, 0.05, 0.97), 18, 34, GOLD)
	sb.set_border_width_all(2)
	sb.shadow_color = Color(GOLD.r, GOLD.g, GOLD.b, 0.25)
	sb.shadow_size = 20
	panel.add_theme_stylebox_override("panel", sb)
	panel.custom_minimum_size = Vector2(700, 0)
	center.add_child(panel)
	_r_body = VBoxContainer.new()
	_r_body.add_theme_constant_override("separation", 10)
	panel.add_child(_r_body)
	Game.state_reset.connect(_on_reset)
	_on_reset()


func _on_reset() -> void:
	_status = str(Game.state.challenge().get("status", ""))
	_place = -1
	if not Game.state.challenge_finished.is_connected(_on_finished_host):
		Game.state.challenge_finished.connect(_on_finished_host)


func _process(delta: float) -> void:
	_t -= delta
	if _t > 0.0:
		return
	_t = 0.4
	var s := Game.state
	var ch := s.challenge()
	_banner.visible = Game.in_game and not ch.is_empty()
	if ch.is_empty():
		return
	var status := str(ch.get("status", ""))
	_b_title.text = "%s  %s" % [str(ch.get("icon", "🏁")), str(ch.get("name", ""))]
	var pr := Challenges.progress(s, ch)
	var g: Dictionary = ch.get("goal", {})
	if status == "running":
		var left := Challenges.days_left(s, ch)
		_b_days.text = "Day %d of %d · %s" % [Campaign.day_number(s), int(ch.days), "last day!" if left <= 1 else "%d days left" % left]
		_b_days.add_theme_color_override("font_color", UiStyle.BAD if left <= 1 else UiStyle.TEXT)
		_b_goal.text = "%s  —  %s" % [Challenges.short_goal(ch), _progress_text(g, pr)]
	else:
		var res: Dictionary = ch.get("result", {})
		var medal := str(res.get("medal", ""))
		_b_days.text = ("%s %s" % [Challenges.MEDAL_ICONS.get(medal, ""), medal.capitalize()]) if medal != "" else ("Not completed" if status == "failed" else "No medal")
		_b_days.add_theme_color_override("font_color", Challenges.MEDAL_COLOURS.get(medal, UiStyle.MUTED))
		_b_goal.text = "%s — %s. Keep playing as long as you like." % ["Finished" if status != "failed" else "Out of time", Challenges.value_text(ch, res) if status != "failed" else _progress_text(g, pr)]
	_b_bar.max_value = maxf(float(pr[1]), 1.0) if not Challenges.is_score(g) else 1.0
	_b_bar.value = float(pr[0]) if not Challenges.is_score(g) else 1.0 - float(Challenges.days_left(s, ch)) / maxf(float(ch.days), 1.0)
	# Every machine (host and guests) notices the finish and shows the results card.
	if status != _status:
		var was := _status
		_status = status
		if was == "running" and status in ["won", "failed", "done"]:
			_on_finished_everyone(ch)


static func _progress_text(g: Dictionary, pr: Array) -> String:
	match str(g.get("kind", "")):
		"profit", "cash", "score_profit":
			return "%s%s" % [GameState.format_money(int(pr[0])), (" of " + GameState.format_money(int(pr[1]))) if float(pr[1]) > 0.0 else " so far"]
		"building":
			return "tier %d of %d" % [int(pr[0]), int(pr[1])]
		"ship", "score_ship":
			return "%d%s shipped" % [int(pr[0]), (" of %d" % int(pr[1])) if float(pr[1]) > 0.0 else ""]
	return "%d of %d done" % [int(pr[0]), int(pr[1])]


func _on_finished_host(ch: Dictionary) -> void:
	## Host: local records (and Steam) — only for real runs started from the title screen.
	if not Game.records_enabled or not Game.is_host():
		return
	var run_id := str(Game.state.totals.get("run_id", ""))
	_place = Challenges.submit_result(ch, Game.state.company_name, run_id)
	var res: Dictionary = ch.get("result", {})
	if str(ch.get("status", "")) in ["won", "done"]:
		var score := Challenges.is_score(ch.get("goal", {}))
		# Steam scores are whole numbers: working minutes for timed runs (fewer wins), the score itself otherwise.
		var v := int(res.get("value", 0)) if score else int(round(float(res.get("value", 0.0)) * BusinessStats.day_minutes()))
		Net.steam.submit_score(str(ch.get("board", "")), v, not score)


func _on_finished_everyone(ch: Dictionary) -> void:
	var res: Dictionary = ch.get("result", {})
	var medal := str(res.get("medal", ""))
	if medal != "":
		Game.achievement_event("challenge_medal")
		if medal == "gold" and not bool(ch.get("daily", false)):
			Game.achievement_event("challenge_gold")
	if bool(ch.get("daily", false)) and str(ch.get("status", "")) != "failed":
		Game.achievement_event("daily_run")
	# The host's record is written in the same frame (signal) — give it a moment, then show the card.
	await get_tree().create_timer(0.6).timeout
	show_results(ch, _place)


func show_results(ch: Dictionary, place: int = -1) -> void:
	for c in _r_body.get_children():
		c.queue_free()
	var res: Dictionary = ch.get("result", {})
	var status := str(ch.get("status", ""))
	var medal := str(res.get("medal", ""))
	var head := str({"won": "CHALLENGE COMPLETE", "done": "TIME'S UP", "failed": "OUT OF TIME"}.get(status, "CHALLENGE"))
	var h := UiStyle.label(head, 15, GOLD)
	_r_body.add_child(h)
	_r_body.add_child(UiStyle.label("%s  %s" % [str(ch.get("icon", "🏁")), str(ch.get("name", ""))], 32, Color.WHITE))
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 18)
	_r_body.add_child(row)
	var medal_box := PanelContainer.new()
	var msb := UiStyle.box(Challenges.MEDAL_COLOURS.get(medal, Color(0.3, 0.3, 0.33)).darkened(0.55), 48, 10, Challenges.MEDAL_COLOURS.get(medal, Color(0.5, 0.5, 0.55)))
	msb.set_border_width_all(3)
	medal_box.add_theme_stylebox_override("panel", msb)
	medal_box.custom_minimum_size = Vector2(96, 96)
	var glyph := UiStyle.label(str(Challenges.MEDAL_ICONS.get(medal, "✖" if status == "failed" else "–")), 44, Color.WHITE)
	glyph.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	glyph.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	medal_box.add_child(glyph)
	row.add_child(medal_box)
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 4)
	row.add_child(col)
	var mname := ("%s medal" % medal.capitalize()) if medal != "" else ("No medal" if status != "failed" else "Not completed")
	col.add_child(UiStyle.label(mname, 26, Challenges.MEDAL_COLOURS.get(medal, UiStyle.MUTED)))
	if status == "failed":
		var pr: Array = res.get("progress", [0, 0])
		col.add_child(UiStyle.label("Ran out of working days at %s." % _progress_text(ch.get("goal", {}), pr), 18, UiStyle.TEXT))
	else:
		col.add_child(UiStyle.label(("Finished on " if status == "won" else "Score: ") + Challenges.value_text(ch, res), 20, UiStyle.TEXT))
	col.add_child(UiStyle.label(Challenges.goal_text(ch), 15, UiStyle.MUTED))
	var medals := UiStyle.label("Medals: " + Challenges.medal_text(ch), 15, UiStyle.MUTED)
	medals.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	medals.custom_minimum_size.x = 600
	_r_body.add_child(medals)
	if place > 0:
		_r_body.add_child(UiStyle.label("New personal best on this PC!" if place == 1 else "#%d on this PC's board." % place, 19, GOLD))
	var entries := Challenges.board_entries(str(ch.get("board", "")))
	if not entries.is_empty():
		_r_body.add_child(UiStyle.label("This PC's best — %s" % str(ch.get("board", "")), 15, UiStyle.MUTED))
		for i in mini(entries.size(), 5):
			var e: Dictionary = entries[i]
			var mine := str(e.get("run", "")) == str(Game.state.totals.get("run_id", ""))
			_r_body.add_child(UiStyle.label("#%d  %s  —  %s  %s%s" % [i + 1, str(e.get("company", "")), Challenges.board_value_text(ch, e),
				str(Challenges.MEDAL_ICONS.get(str(e.get("medal", "")), "")), "   ← this run" if mine else ""], 16, GOLD if mine else UiStyle.TEXT))
	var note := "Posted to the Steam leaderboard too." if Net.steam.available and Game.is_host() and status != "failed" else "Results are kept on this PC (Steam leaderboards when you play through Steam)."
	if bool(ch.get("daily", false)):
		note += "  One daily run per day — a new one tomorrow."
	_r_body.add_child(UiStyle.label(note, 14, UiStyle.MUTED))
	var buttons := HBoxContainer.new()
	buttons.add_theme_constant_override("separation", 12)
	_r_body.add_child(buttons)
	var keep := Button.new()
	keep.text = "Keep playing"
	keep.custom_minimum_size = Vector2(200, 50)
	keep.pressed.connect(func():
		_results.visible = false
		var p: Node = LevelRoot.current.local_player() if LevelRoot.current else null
		if p and "ui_mode" in p and not p.ui_mode:
			Input.mouse_mode = Input.MOUSE_MODE_CAPTURED)
	buttons.add_child(keep)
	var title := Button.new()
	title.text = "Back to the title screen"
	title.custom_minimum_size = Vector2(260, 50)
	title.pressed.connect(_back_to_title)
	buttons.add_child(title)
	_results.visible = true
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	Sfx.play_ui("achievement" if medal != "" else "day_end", -3.0)


func _back_to_title() -> void:
	_results.visible = false
	SaveManager.save_game(SaveManager.CHALLENGE)
	var hud := get_parent()
	var root := hud.get_parent() if hud else null
	if root:
		for n in root.get_children():
			if n is PauseMenu:
				n.quit_to_menu.emit()
				return
