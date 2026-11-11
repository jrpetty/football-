class_name Stats
extends RefCounted
## Survival simulation: needs, body parts and wounds, bleeding, infection, the grey fever, temperature, moods.
## A port of the web game's Stats.

const PARTS := [["head", "Head", 0.08], ["torso", "Torso", 0.3], ["armL", "Left Arm", 0.17], ["armR", "Right Arm", 0.17], ["legL", "Left Leg", 0.14], ["legR", "Right Leg", 0.14]]
const HEAL_HOURS := {"scratch": 20.0, "laceration": 60.0, "bite": 90.0, "bullet": 80.0, "fracture": 140.0, "burn": 50.0}
const BLEED := {"scratch": 0.6, "laceration": 1.8, "bite": 2.4, "bullet": 2.2, "fracture": 0.0, "burn": 0.0}

var hunger := 85.0
var thirst := 85.0
var fatigue := 10.0
var stamina := 100.0
var max_stamina := 100.0
var body_temp := 37.0
var wetness := 0.0
var pain := 0.0
var panic := 0.0
var unhappy := 10.0
var sickness := 0.0
var blood_loss := 0.0
var parts := {}
var knox := 0.0
var knox_infected := false
var painkiller_hrs := 0.0
var antibiotic_hrs := 0.0
var serum_hrs := 0.0
var food_poison := 0.0
var dead := false
var death_cause := ""
var hours_survived := 0.0

func _init() -> void:
	for p in PARTS:
		parts[p[0]] = {"hp": 100.0, "wounds": []}

func health() -> float:
	var h := 0.0
	for p in PARTS:
		h += float(parts[p[0]].hp) * float(p[2])
	h -= blood_loss * 0.6 + sickness * 0.25
	if knox_infected:
		h -= maxf(0.0, knox - 0.6) * 150.0
	return clampf(h, 0.0, 100.0)

func bleeding() -> float:
	var b := 0.0
	for p in PARTS:
		for w in parts[p[0]].wounds:
			if not w.bandaged:
				b += float(w.bleed)
	return b

func has_fracture(part: String) -> bool:
	for w in parts[part].wounds:
		if w.type == "fracture" and not w.splinted:
			return true
	return false

func wound_infection() -> float:
	var m := 0.0
	for p in PARTS:
		for w in parts[p[0]].wounds:
			m = maxf(m, float(w.infection))
	return m

func wound_count() -> int:
	var n := 0
	for p in PARTS:
		n += parts[p[0]].wounds.size()
	return n

func add_wound(part: String, type: String, damage: float) -> Dictionary:
	var w := {"type": type, "heal": 1.0, "bleed": float(BLEED[type]) * (0.7 + randf() * 0.6), "bandaged": false, "dirty": false, "infection": 0.0, "stitched": false, "splinted": false, "bandageAge": 0.0}
	parts[part].wounds.append(w)
	parts[part].hp = maxf(0.0, float(parts[part].hp) - damage)
	pain = minf(100.0, pain + damage * 1.5)
	return w

func _dmg_all(amt: float) -> void:
	for p in PARTS:
		parts[p[0]].hp = maxf(0.0, float(parts[p[0]].hp) - amt)

## dt_h: game hours elapsed. c: context dictionary (see Player). Returns notable events.
func update(dt_real: float, dt_h: float, c: Dictionary) -> Array:
	var ev: Array = []
	if dead:
		return ev
	var tr: Dictionary = c.get("traits", {})
	var exertion: float = c.get("exertion", 0.0)
	var sleeping: bool = c.get("sleeping", false)
	var indoors: bool = c.get("indoors", false)
	var near_fire: bool = c.get("near_fire", false)
	var fitness: float = c.get("fitness", 0.0)
	hours_survived += dt_h
	var sleep_mul := 0.4 if sleeping else 1.0
	hunger -= dt_h * 3.2 * sleep_mul * (1.0 + exertion * 0.6)
	thirst -= dt_h * 4.6 * sleep_mul * (1.0 + exertion * 0.8) * (1.5 if body_temp > 37.6 else 1.0)
	if sleeping:
		fatigue -= dt_h * (9.0 if tr.has("insomniac") else 13.0)
	else:
		fatigue += dt_h * (3.0 if tr.has("night_owl") else 3.9) * (1.0 + exertion * 0.5)
	hunger = clampf(hunger, 0.0, 100.0); thirst = clampf(thirst, 0.0, 100.0); fatigue = clampf(fatigue, 0.0, 100.0)

	var max_s := 60.0 + fitness * 6.0
	if tr.has("obese"): max_s *= 0.8
	if tr.has("athletic"): max_s *= 1.1
	max_s *= 1.0 - maxf(0.0, fatigue - 60.0) / 100.0
	max_stamina = max_s
	var wr: float = c.get("weight_ratio", 0.0)
	var heavy := maxf(0.0, wr - 1.0)
	if c.get("sprinting", false):
		stamina -= dt_real * (9.0 - fitness * 0.35) * (1.0 + heavy)
	else:
		var regen := (5.0 if c.get("moving", false) else 11.0) * (1.0 + fitness * 0.08)
		if tr.has("smoker"): regen *= 0.7
		if hunger < 15.0: regen *= 0.5
		stamina += dt_real * regen * (0.5 if heavy > 0.0 else 1.0)
	stamina = clampf(stamina, 0.0, max_stamina)

	var eq: float = float(c.get("ambient_temp", 14.0)) + 18.0 + float(c.get("insulation", 0.0)) * 14.0 + (14.0 if near_fire else 0.0) + (6.0 if indoors else 0.0) + exertion * 6.0 - wetness * 12.0
	if sleeping and indoors: eq += 4.0
	var target := clampf(36.4 + (eq - 37.0) * 0.06, 33.0, 39.5)
	body_temp += (target - body_temp) * clampf(dt_h * 0.6, 0.0, 1.0)
	if knox_infected and knox > 0.3: body_temp = maxf(body_temp, 37.6 + knox * 1.4)
	if wound_infection() > 0.5: body_temp = maxf(body_temp, 38.0)
	var raining: float = c.get("raining", 0.0)
	if raining > 0.05 and not indoors:
		wetness = clampf(wetness + dt_h * raining * 1.6 * (1.0 - float(c.get("rain_protect", 0.0))), 0.0, 1.0)
	else:
		wetness = clampf(wetness - dt_h * (1.5 if near_fire else (0.4 if indoors else 0.2)), 0.0, 1.0)

	var seen: int = c.get("zombies_seen", 0)
	if seen > 0: panic = minf(100.0, panic + dt_real * seen * (1.2 if tr.has("veteran") else 3.5))
	else: panic = maxf(0.0, panic - dt_real * 4.0)
	if tr.has("hemophobic") and (bleeding() > 0.0 or c.get("bloody_hands", false)): panic = minf(100.0, panic + dt_real * 2.0)
	if wetness > 0.5: unhappy += dt_h * 1.5
	if indoors and c.get("in_light", false): unhappy -= dt_h * 0.5
	unhappy = clampf(unhappy - dt_h * 0.2, 0.0, 100.0)

	var heal_mul := (1.5 if tr.has("fast_healer") else (0.6 if tr.has("slow_healer") else 1.0)) * (1.0 if hunger > 30.0 and thirst > 30.0 else 0.3) * (2.0 if sleeping else 1.0)
	var pain_target := 0.0
	for p in PARTS:
		var part: Dictionary = parts[p[0]]
		var ws: Array = part.wounds
		for i in range(ws.size() - 1, -1, -1):
			var w: Dictionary = ws[i]
			if not w.bandaged and float(w.bleed) > 0.0:
				blood_loss = minf(100.0, blood_loss + float(w.bleed) * dt_h * 6.0)
				w.bleed = maxf(0.0, float(w.bleed) - dt_h * 0.08)
			if w.bandaged:
				w.bandageAge = float(w.bandageAge) + dt_h
				if float(w.bandageAge) > 18.0: w.dirty = true
			var exposed: bool = (not w.bandaged) or w.dirty
			if exposed and w.type != "fracture" and randf() < dt_h * (0.04 if tr.has("prone_ill") else 0.02):
				w.infection = maxf(float(w.infection), 0.05)
			if float(w.infection) > 0.0:
				w.infection = clampf(float(w.infection) + dt_h * (-0.08 if antibiotic_hrs > 0.0 else 0.02 * (1.6 if tr.has("prone_ill") else 1.0)), 0.0, 1.0)
				if float(w.infection) > 0.7: part.hp = maxf(0.0, float(part.hp) - dt_h * 0.6)
			var can_heal: bool = w.type != "fracture" or w.splinted
			if can_heal and float(w.infection) < 0.3:
				w.heal = float(w.heal) - dt_h / float(HEAL_HOURS[w.type]) * heal_mul * (3.0 if w.stitched else 1.0) * (1.3 if w.bandaged else 1.0)
			pain_target += (45.0 if w.type == "fracture" else (25.0 if w.type == "bite" else (18.0 if w.type == "laceration" else 8.0))) * maxf(0.2, float(w.heal)) + float(w.infection) * 30.0
			if float(w.heal) <= 0.0:
				ws.remove_at(i); ev.append("healed:" + String(p[1]))
		if ws.is_empty() and hunger > 25.0 and thirst > 25.0:
			part.hp = minf(100.0, float(part.hp) + dt_h * 2.5 * heal_mul)
	if bleeding() < 0.05:
		blood_loss = maxf(0.0, blood_loss - dt_h * (2.5 if hunger > 30.0 and thirst > 30.0 else 0.5))
	if painkiller_hrs > 0.0: pain_target *= 0.25
	pain += (clampf(pain_target, 0.0, 100.0) - pain) * clampf(dt_h * 2.0, 0.0, 1.0)
	painkiller_hrs = maxf(0.0, painkiller_hrs - dt_h)
	antibiotic_hrs = maxf(0.0, antibiotic_hrs - dt_h)

	if food_poison > 0.0:
		sickness = clampf(sickness + dt_h * food_poison * 6.0, 0.0, 100.0)
		food_poison = maxf(0.0, food_poison - dt_h * (0.3 if antibiotic_hrs > 0.0 else 0.08))
	else:
		sickness = maxf(0.0, sickness - dt_h * (6.0 if antibiotic_hrs > 0.0 else 2.5))

	if knox_infected:
		if serum_hrs > 0.0:
			knox = maxf(0.0, knox - dt_h * 0.05)
			if knox <= 0.0:
				knox_infected = false; ev.append("cured")
		else:
			knox = minf(1.0, knox + dt_h / 60.0)
	serum_hrs = maxf(0.0, serum_hrs - dt_h)

	if hunger <= 0.0: _dmg_all(dt_h * 2.2)
	if thirst <= 0.0: _dmg_all(dt_h * 4.0)
	if body_temp < 34.5: _dmg_all(dt_h * 3.0 * (34.5 - body_temp))
	if body_temp > 39.8: _dmg_all(dt_h * 2.0)

	if health() <= 0.0:
		dead = true
		if knox_infected and knox > 0.9: death_cause = "Succumbed to the grey fever."
		elif blood_loss > 60.0: death_cause = "Bled out."
		elif thirst <= 0.0: death_cause = "Died of dehydration."
		elif hunger <= 0.0: death_cause = "Starved to death."
		elif body_temp < 34.5: death_cause = "Froze to death."
		elif sickness > 60.0: death_cause = "Died of sickness."
		else: death_cause = "Died of injuries."
		ev.append("dead")
	return ev

func speed_mult(weight_ratio: float) -> float:
	var m := 1.0
	var legs := (float(parts.legL.hp) + float(parts.legR.hp)) / 200.0
	m *= 0.55 + 0.45 * legs
	if has_fracture("legL") or has_fracture("legR"): m *= 0.55
	if fatigue > 70.0: m *= 0.85
	if weight_ratio > 1.0: m *= maxf(0.45, 1.0 - (weight_ratio - 1.0) * 0.8)
	if pain > 60.0: m *= 0.85
	return m

func melee_mult() -> float:
	var arms := (float(parts.armL.hp) + float(parts.armR.hp)) / 200.0
	var m := 0.5 + 0.5 * arms
	if has_fracture("armL") or has_fracture("armR"): m *= 0.5
	if fatigue > 70.0: m *= 0.8
	if stamina < 10.0: m *= 0.6
	if hunger < 15.0: m *= 0.85
	return m

func aim_sway() -> float:
	return (panic / 100.0) * 0.03 + (pain / 100.0) * 0.02 + maxf(0.0, fatigue - 60.0) / 100.0 * 0.03 + (0.015 if stamina < 20.0 else 0.0) + (100.0 - float(parts.armR.hp)) / 100.0 * 0.02

func can_sprint(weight_ratio: float) -> bool:
	return stamina > 3.0 and weight_ratio < 1.5 and not has_fracture("legL") and not has_fracture("legR")

static func _lvl(v: float, a: float, b: float, c: float, d: float) -> int:
	return 4 if v >= d else (3 if v >= c else (2 if v >= b else (1 if v >= a else 0)))

## the status icons shown beside the screen: [{id, icon, level, bad, label, desc}]
func moodles(weight_ratio: float) -> Array:
	var m: Array = []
	var push := func(id: String, icon: String, level: int, labels: Array, desc: String) -> void:
		if level > 0:
			m.append({"id": id, "icon": icon, "level": level, "bad": true, "label": labels[level - 1], "desc": desc})
	push.call("hunger", "🍽", _lvl(100.0 - hunger, 60, 75, 88, 98), ["Peckish", "Hungry", "Very Hungry", "Starving"], "Eat something. Starvation damages health.")
	push.call("thirst", "💧", _lvl(100.0 - thirst, 55, 72, 86, 98), ["Slightly Thirsty", "Thirsty", "Parched", "Dying of Thirst"], "Drink clean water.")
	push.call("tired", "😴", _lvl(fatigue, 55, 70, 85, 96), ["Drowsy", "Tired", "Very Tired", "Exhausted"], "Sleep in a bed.")
	push.call("endurance", "😮", _lvl(100.0 - stamina / maxf(1.0, max_stamina) * 100.0, 50, 70, 85, 96), ["Winded", "Out of Breath", "Exhausted", "Collapsing"], "Rest to recover stamina.")
	push.call("bleeding", "🩸", _lvl(bleeding(), 0.05, 1.2, 2.5, 4), ["Bleeding", "Bleeding Heavily", "Hemorrhaging", "Bleeding Out"], "Bandage your wounds (H).")
	push.call("pain", "⚡", _lvl(pain, 15, 35, 60, 85), ["Minor Pain", "Pain", "Severe Pain", "Agony"], "Painkillers help.")
	push.call("injured", "🤕", _lvl(100.0 - health(), 15, 35, 60, 80), ["Scratched Up", "Injured", "Badly Injured", "Critical"], "Rest, eat and treat wounds.")
	push.call("panic", "😱", _lvl(panic, 15, 40, 65, 88), ["Anxious", "Panicked", "Strong Panic", "Extreme Panic"], "Panic ruins aim.")
	if body_temp < 36.2:
		push.call("cold", "🥶", _lvl(37.0 - body_temp, 0.8, 1.4, 2.1, 2.6), ["Chilly", "Cold", "Freezing", "Hypothermic"], "Get dry, warm clothes, a fire, or shelter.")
	if body_temp > 37.6:
		push.call("hot", "🥵", _lvl(body_temp - 37.0, 0.6, 1.2, 1.8, 2.4), ["Warm", "Feverish", "High Fever", "Burning Up"], "Fever. Infection, perhaps?")
	push.call("wet", "🌧", _lvl(wetness * 100.0, 15, 40, 65, 88), ["Damp", "Wet", "Soaking", "Drenched"], "Being wet chills you.")
	push.call("sick", "🤢", _lvl(maxf(sickness, knox * 100.0 if knox_infected and knox > 0.2 else 0.0), 15, 35, 60, 85), ["Queasy", "Nauseous", "Sick", "Fever"], "Something is wrong with you.")
	push.call("infection", "🦠", _lvl(wound_infection() * 100.0, 5, 30, 55, 80), ["Wound Infected", "Infected", "Badly Infected", "Sepsis"], "Disinfect wounds and take antibiotics.")
	push.call("heavy", "🎒", _lvl(weight_ratio * 100.0, 101, 125, 150, 175), ["Heavy Load", "Very Heavy Load", "Overloaded", "Crushing Load"], "Drop items or equip a bag.")
	return m

func to_dict() -> Dictionary:
	var o := {}
	for k in ["hunger", "thirst", "fatigue", "stamina", "max_stamina", "body_temp", "wetness", "pain", "panic", "unhappy", "sickness", "blood_loss", "knox", "knox_infected", "painkiller_hrs", "antibiotic_hrs", "serum_hrs", "food_poison", "dead", "death_cause", "hours_survived"]:
		o[k] = get(k)
	o["parts"] = parts.duplicate(true)
	return o

func from_dict(o: Dictionary) -> void:
	for k in o:
		if k == "parts":
			parts = o.parts.duplicate(true)
		else:
			set(k, o[k])
