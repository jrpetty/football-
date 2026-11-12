class_name Campaign
extends RefCounted
## The long game: milestones timed in working days (data/goals.json), the end goal (mega plant AND £20M
## lifetime revenue), and local records — the fastest run to each milestone on this PC, kept across saves.
## Progress lives in GameState.totals ("goals": {id: day}, "days_worked"), so it saves and replicates.

const RECORDS_PATH := "user://records.json"
const TOP_N := 10

static var _data := {}


static func data() -> Dictionary:
	if _data.is_empty():
		_data = JSON.parse_string(FileAccess.get_file_as_string("res://data/goals.json"))
	return _data


static func milestones() -> Array:
	return data().get("milestones", [])


static func milestone(id: String) -> Dictionary:
	for m: Dictionary in milestones():
		if m.id == id:
			return m
	return {}


static func day_number(state: GameState) -> int:
	## Working days into the run (day 1 = the first day of trading).
	return int(state.totals.get("days_worked", 0)) + 1


static func reached(state: GameState) -> Dictionary:
	return state.totals.get("goals", {})


static func progress(state: GameState, m: Dictionary) -> Array:
	## [current, target] for a progress bar.
	match str(m.kind):
		"revenue":
			return [float(state.totals.get("revenue", 0)), float(m.value)]
		"sold":
			return [float(state.totals.get("units_sold", 0)), float(m.value)]
		"reputation":
			return [state.reputation, float(m.value)]
		"brews":
			return [float(state.totals.get("lifetime", {}).get("brews", 0)), float(m.value)]
		"building":
			var have := tier_of(state.building_id)
			return [float(mini(have, tier_of(str(m.value)))), float(tier_of(str(m.value)))]
		"fixture":
			for f in state.fixtures.values():
				if f.type == str(m.value):
					return [1.0, 1.0]
			return [0.0, 1.0]
	return [0.0, 1.0]


static func tier_of(building_id: String) -> int:
	return int(Content.buildings.get(building_id, {}).get("tier", 1))


static func check(state: GameState) -> Array:
	## Host: mark newly reached milestones (and the end goal). Returns the ids reached just now.
	var got: Dictionary = state.totals.get("goals", {})
	var new_ids: Array = []
	for m: Dictionary in milestones():
		if got.has(m.id):
			continue
		var pr := progress(state, m)
		if float(pr[0]) >= float(pr[1]):
			got[m.id] = day_number(state)
			new_ids.append(m.id)
	var end: Dictionary = data().get("end_goal", {})
	if not end.is_empty() and not got.has(end.id) and (end.get("all", []) as Array).all(func(i): return got.has(i)):
		got[end.id] = day_number(state)
		new_ids.append(end.id)
	if not new_ids.is_empty():
		state.totals["goals"] = got
		state.mark("company")
	return new_ids


static func steam_board(id: String) -> String:
	## Steam leaderboard for a milestone (ascending, working days). Register these in Steamworks: docs/STEAM.md.
	return "DAYS_" + id.to_upper()


static func name_of(id: String) -> String:
	var end: Dictionary = data().get("end_goal", {})
	if id == end.get("id", ""):
		return str(end.get("name", id))
	return str(milestone(id).get("name", id))


static func next_milestone(state: GameState) -> Dictionary:
	var got := reached(state)
	for m: Dictionary in milestones():
		if not got.has(m.id) and m.kind != "brews":
			return m
	return {}


# ================================================================= local records

static func load_records() -> Dictionary:
	if not FileAccess.file_exists(RECORDS_PATH):
		return {}
	var d = JSON.parse_string(FileAccess.get_file_as_string(RECORDS_PATH))
	return d if d is Dictionary else {}


static func submit(goal_id: String, days: int, company: String, run_id: String) -> int:
	## Add a time to this PC's leaderboard for a goal. Returns the place (1 = new record), 0 if outside the top N.
	var recs := load_records()
	var list: Array = recs.get(goal_id, [])
	for e in list:
		if str(e.get("run", "")) == run_id:
			return 0          # this run already has a time for it
	list.append({"days": days, "company": company, "run": run_id, "date": Time.get_date_string_from_system()})
	list.sort_custom(func(a, b): return int(a.days) < int(b.days))
	var place := 0
	for i in list.size():
		if str(list[i].run) == run_id:
			place = i + 1
	recs[goal_id] = list.slice(0, TOP_N)
	var f := FileAccess.open(RECORDS_PATH, FileAccess.WRITE)
	if f:
		f.store_string(JSON.stringify(recs, " "))
	return place if place <= TOP_N else 0


static func best(goal_id: String) -> Dictionary:
	var list: Array = load_records().get(goal_id, [])
	return list[0] if not list.is_empty() else {}
