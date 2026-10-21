class_name StaffMorale
extends RefCounted
## Staff morale, 0–100 per member of staff: what makes people happier or fed up, and what it does to their work.
## Host rules only — Sim calls tick() every clock step and day_end() at closing time, and the staff commands
## (tea, pay rise, thank, course) call the boosts. The numbers live on the staff card in GameState.staff[id]
## ("morale", "since_break", "busy_min"/"shift_min", "wants_rise", "low_days", "notice", "why"), so they save and
## replicate with everything else. Tuning: data/staff.json → "morale".
##
## Effect: speed ×0.8 at 0 … ×1.2 at 100 (×1.0 at 50) on every timed job, and a longer or shorter pause between
## jobs (reliability). A few days in the doldrums and they write to you; a few more and they hand in their notice.

const MOODS := [[80.0, "Buzzing"], [60.0, "Happy"], [40.0, "Fine"], [25.0, "Fed up"], [0.0, "Miserable"]]


static func cfg() -> Dictionary:
	return Staff.data().get("morale", {})


static func c(key: String, default: Variant) -> Variant:
	return cfg().get(key, default)


static func value(d: Dictionary) -> float:
	return float(d.get("morale", c("start", 70)))


static func init_card(d: Dictionary) -> void:
	## A new starter: keen, fresh from a cuppa.
	d["morale"] = float(c("start", 70))
	d["since_break"] = 0.0
	d["busy_min"] = 0.0
	d["shift_min"] = 0.0
	d["low_days"] = 0


static func speed_mult(d: Dictionary) -> float:
	if d.is_empty():
		return 1.0
	var r: Array = c("speed_range", [0.8, 1.2])
	return lerpf(float(r[0]), float(r[1]), value(d) / 100.0)


static func idle_mult(d: Dictionary) -> float:
	## How much longer (or shorter) they take to get going on the next job.
	var r: Array = c("idle_mult_range", [2.2, 0.7])
	return lerpf(float(r[0]), float(r[1]), value(d) / 100.0)


static func mood(m: float) -> String:
	for band in MOODS:
		if m >= float(band[0]):
			return str(band[1])
	return "Miserable"


static func mood_colour(m: float) -> Color:
	if m >= 60.0:
		return Color(0.12, 0.6, 0.3)
	if m >= 40.0:
		return Color(0.2, 0.45, 0.85)
	if m >= 25.0:
		return Color(0.85, 0.55, 0.05)
	return Color(0.82, 0.22, 0.18)


static func mood_line(d: Dictionary) -> String:
	## "Happy (72) — loved that brew" for hover cards and the Staff app.
	var m := value(d)
	var why := str(d.get("why", ""))
	return "%s (%d)%s" % [mood(m), int(round(m)), (" — " + why) if why != "" else ""]


static func is_away(d: Dictionary) -> bool:
	return d.has("away")


static func break_due(d: Dictionary) -> float:
	## Working minutes before they want a brew (staggered per person so the whole crew doesn't down tools at once).
	var jitter := int(c("break_jitter_minutes", 30))
	return float(c("break_due_minutes", 140)) + float((int(d.get("id", 0)) * 37) % maxi(jitter + 1, 1))


static func wants_break(d: Dictionary) -> bool:
	return not is_away(d) and float(d.get("since_break", 0.0)) >= break_due(d)


static func levels_of(st: GameState, d: Dictionary) -> Dictionary:
	var key := Sim.player_key(StaffView.peer_of(int(d.get("id", 0))))
	var levels := {}
	for sk: Dictionary in Content.skill_defs():
		levels[sk.id] = st.skill_level(key, sk.id)
	return levels


static func fair_wage(st: GameState, d: Dictionary) -> int:
	## What their skills are worth on the open market (the same formula that priced them when hired).
	return Staff.wage_for(levels_of(st, d))


static func change(st: GameState, d: Dictionary, delta: float, why: String = "") -> void:
	var before := value(d)
	var after := clampf(before + delta, 0.0, 100.0)
	d["morale"] = after
	if why != "":
		d["why"] = why
	if int(round(before)) != int(round(after)) or why != "":
		st.mark("staff", int(d.get("id", 0)))


static func busy(d: Dictionary) -> bool:
	var s := str(d.get("status", ""))
	return s.begins_with("Working") or s.begins_with("Walking")


# ================================================================= clock

static func tick(st: GameState, mins: float, overtime: bool, late_night: bool) -> void:
	## Every clock step while the shift runs: breaks come due, a busy week adds up, overtime wears people down.
	for d: Dictionary in st.staff.values():
		if is_away(d):
			continue
		d["since_break"] = float(d.get("since_break", 0.0)) + mins
		d["shift_min"] = float(d.get("shift_min", 0.0)) + mins
		if busy(d):
			d["busy_min"] = float(d.get("busy_min", 0.0)) + mins
		var drain := 0.0
		var why := ""
		if float(d.since_break) > break_due(d):
			drain += float(c("no_break_drain_per_hour", 4.0)) / 60.0 * mins
			why = "gasping for a brew"
		if overtime:
			drain += float(c("overtime_drain_per_hour", 3.0)) / 60.0 * mins * (float(c("overtime_late_mult", 2.0)) if late_night else 1.0)
			why = "shattered — it's late" if late_night else "working late"
		if drain > 0.0:
			var before := value(d)
			change(st, d, -drain)
			# Only re-label the reason when it ticks over a whole point (keeps replication quiet).
			if int(round(before)) != int(round(value(d))):
				d["why"] = why


# ================================================================= boosts

static func on_tea(st: GameState, d: Dictionary, made_by_player: bool, by_name: String = "") -> void:
	d["since_break"] = 0.0
	if made_by_player:
		change(st, d, float(c("tea_gift_boost", 14)), "%s made them a brew" % by_name if by_name != "" else "someone made them a brew")
	else:
		change(st, d, float(c("tea_boost", 8)), "had a tea break")


static func on_rest(st: GameState, d: Dictionary) -> void:
	d["since_break"] = 0.0
	change(st, d, float(c("rest_boost", 2)), "had a sit-down (no kettle free)")


# ================================================================= closing time

static func day_end(sim: Sim, summary: Dictionary, overnight: bool) -> void:
	## Closing time: the week so far, their pay, how the day went, and whether they've had enough.
	var st := sim.state
	var day := int(summary.get("day", st.day_index()))
	for id in st.staff.keys():
		var d: Dictionary = st.staff[id]
		if is_away(d):
			continue
		var delta := 0.0
		var reasons: Array = []
		# A week of flat-out work.
		var shift_min := float(d.get("shift_min", 0.0))
		if shift_min >= float(c("overwork_min_minutes", 600)) and float(d.get("busy_min", 0.0)) / maxf(shift_min, 1.0) > float(c("overwork_util", 0.85)):
			delta -= float(c("overwork_drain_day", 5))
			reasons.append("overworked this week")
		# Pay that lags their skills: they ask for a rise, and stew until they get one.
		var fair := fair_wage(st, d)
		var wage := int(d.get("wage", 0))
		if fair > wage:
			var gap := float(fair - wage) / maxf(float(fair), 1.0)
			delta -= minf(float(c("pay_lag_drain_day", 3)) + gap * 20.0, float(c("pay_lag_drain_max", 9)))
			reasons.append("wants a pay rise (%s)" % GameState.format_money(fair))
			if int(d.get("wants_rise", 0)) == 0:
				st.say("%s has asked for a pay rise: %s → %s a week (Staff app)." % [d.name, GameState.format_money(wage), GameState.format_money(fair)], "warn")
				sim.send_mail(str(d.name), "Pay review?", "Hi,\n\nI've been here a while now and I'm doing a lot more than when I started — my %s has come on loads. Similar jobs are paying around %s a week. Could we talk about a rise from %s?\n\nThanks,\n%s" % [
					Staff.main_skill(str(d.get("role", ""))), GameState.format_money(fair), GameState.format_money(wage), d.name])
			d["wants_rise"] = fair
		else:
			d.erase("wants_rise")
		# Late and cancelled orders make for a grim atmosphere.
		var orders_hit := int(summary.get("late", 0)) * float(c("late_order_drain", 1.5)) + int(summary.get("missed", 0)) * float(c("missed_order_drain", 3.0))
		if orders_hit > 0.0:
			delta -= minf(orders_hit, float(c("orders_drain_cap", 10)))
			reasons.append("late orders are stressing everyone")
		# A good shift is a good feeling.
		var rc: Dictionary = c("rating_change", {})
		var r := float(rc.get(str(int(summary.get("rating", 3))), 0))
		delta += r
		if r >= 3.0:
			reasons.push_front("great shift today")
		# A night's sleep takes the edge off (not after an all-nighter).
		if not overnight and value(d) + delta < 60.0:
			delta += float(c("overnight_recovery", 3))
		var why := str(reasons[0]) if not reasons.is_empty() else ("knackered" if overnight else str(d.get("why", "")))
		change(st, d, delta, why)
		_check_notice(sim, d, day)


static func _check_notice(sim: Sim, d: Dictionary, day: int) -> void:
	var st := sim.state
	var m := value(d)
	if d.has("notice"):
		if m >= float(c("stay_threshold", 40)):
			d.erase("notice")
			d["low_days"] = 0
			d["warned"] = false
			st.say("%s has withdrawn their notice — they're staying!" % d.name, "good")
			sim.send_mail(str(d.name), "Staying on", "Hi,\n\nThings feel a lot better round here lately. If it's all right with you I'd like to tear up that letter and stay.\n\n%s" % d.name)
		elif day >= int(d.notice.get("leave_day", day)):
			st.say("%s has worked their notice and left the company." % d.name, "bad")
			BusinessStats.event(st, "staff", "%s resigned (morale %d)" % [d.name, int(m)])
			sim.staff_leaves(int(d.id))
		st.mark("staff", int(d.id))
		return
	if m < float(c("notice_threshold", 20)):
		d["low_days"] = int(d.get("low_days", 0)) + 1
		if int(d.low_days) >= int(c("notice_days", 3)):
			var leave := day + int(c("notice_period_days", 2))
			d["notice"] = {"given": day, "leave_day": leave}
			st.say("%s has handed in their notice. Cheer them up (tea, a thank-you, a pay rise) or they leave on %s." % [d.name, GameState.WEEKDAYS[leave % 7]], "bad")
			sim.send_mail(str(d.name), "Resignation", "Hi,\n\nPlease take this as my notice. I'm just not happy here at the moment. My last day will be %s.\n\n%s" % [GameState.WEEKDAYS[leave % 7], d.name])
		elif int(d.low_days) >= int(c("warn_days", 2)) and not d.get("warned", false):
			d["warned"] = true
			st.say("%s is really fed up (%s). Look after them or they'll walk." % [d.name, mood_line(d)], "warn")
			sim.send_mail(str(d.name), "Can we talk?", "Hi,\n\nI don't want to make a fuss, but I'm not enjoying it here right now — %s. If nothing changes I'll have to start looking elsewhere.\n\n%s" % [str(d.get("why", "it's all a bit much")), d.name])
	else:
		d["low_days"] = 0
		d["warned"] = false
	st.mark("staff", int(d.id))


static func week_reset(st: GameState) -> void:
	## Friday close: the utilisation week starts again.
	for d: Dictionary in st.staff.values():
		d["busy_min"] = 0.0
		d["shift_min"] = 0.0
