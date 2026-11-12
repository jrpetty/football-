class_name Training
extends RefCounted
## Training courses (data/training.json): staff go away on a course and come back a level up in one skill;
## players buy online courses for themselves for XP (each repeat of the same course is worth less).
## Pricing and durations live here; Sim's c_send_course / c_buy_course apply them.

static var _data := {}


static func data() -> Dictionary:
	if _data.is_empty():
		_data = JSON.parse_string(FileAccess.get_file_as_string("res://data/training.json"))
	return _data


static func max_level() -> int:
	return int(Content.skills_data.get("max_level", 10))


# ================================================================= staff courses

static func staff_course(skill: String) -> Dictionary:
	for c: Dictionary in data().get("staff", {}).get("courses", []):
		if str(c.skill) == skill:
			return c
	return {}


static func staff_cost(level: int) -> int:
	var s: Dictionary = data().get("staff", {})
	return int(s.get("cost_base", 250)) + int(s.get("cost_per_level", 140)) * level


static func staff_days(level: int) -> int:
	## Working days away: longer courses for the higher levels.
	var s: Dictionary = data().get("staff", {})
	return int(s.get("days_base", 1)) + int(level / maxi(int(s.get("days_per_levels", 4)), 1))


static func return_day(from_day: int, days: int) -> int:
	## The morning they're back: `days` working days after `from_day` (weekends don't count).
	var d := from_day
	var left := days
	while left > 0:
		d += 1
		if d % 7 < 5:
			left -= 1
	return d


# ================================================================= online courses (players)

static func online_course(skill: String) -> Dictionary:
	for c: Dictionary in data().get("online", {}).get("courses", []):
		if str(c.skill) == skill:
			return c
	return {}


static func online_xp(course: Dictionary, times_taken: int) -> int:
	## Diminishing returns: each repeat of the same skill's course teaches you a bit less.
	var o: Dictionary = data().get("online", {})
	var xp := float(course.get("xp", 200)) * pow(float(o.get("repeat_factor", 0.65)), times_taken)
	return maxi(int(round(xp)), int(o.get("min_xp", 40)))
