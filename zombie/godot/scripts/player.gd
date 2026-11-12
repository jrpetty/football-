class_name Player
extends RefCounted
## First-person movement and collision, inventory and equipment, survival stats, skills and traits.

const XP_SKILLS := ["fitness", "strength", "sprinting", "lightfoot", "blunt", "blade", "firearms", "carpentry", "engineering", "firstaid", "scavenging", "cooking", "farming"]

var pos := Vector3.ZERO
var vel := Vector3.ZERO
var yaw := 0.0
var pitch := 0.0
var on_ground := true
var crouch := false
var crouch_t := 0.0
var sprinting := false
var moving := false
var speed := 0.0
var inv := Inventory.new()
var equipped = null                 # uid of the stack in hand
var hotbar: Array = [null, null, null, null, null, null, null, null]
var worn := {"head": null, "torso": null, "legs": null, "feet": null, "hands": null, "back": null}
var stats := Stats.new()
var xp := {}
var bonus := {}
var book_until := {}
var traits := {}
var occupation := "unemployed"
var name := "Survivor"
var flashlight_on := false
var noise := 0.0                    # how far away movement can be heard
var visibility := 0.5
var head_bob := Vector3.ZERO
var fall_vel := 0.0
var sleeping := false
var kills := 0
var inside := false
var bloody := 0.0
var recoil_pitch := 0.0
var cam_shake := 0.0
var landed := 0.0
var jumped := false
var stepped := false
var step_surface := "grass"
var _step_dist := 0.0
var _stand_y := INF
## scripted walking (tests, cut-scenes): a world-space direction used instead of the keys while it is not zero
var auto_dir := Vector3.ZERO
var auto_sprint := false
var _bob := 0.0
signal level_up(skill: String, level: int)

func _init() -> void:
	for s in XP_SKILLS:
		xp[s] = 0.0; bonus[s] = 0

static func xp_for_level(l: int) -> float:
	return 75.0 * l * (l + 1)

func raw_level(id: String) -> int:
	var l := 0
	while l < 10 and float(xp.get(id, 0.0)) >= xp_for_level(l + 1):
		l += 1
	return l

func level(id: String) -> int:
	return mini(10, raw_level(id) + int(bonus.get(id, 0)))

func skill_progress(id: String) -> float:
	var l := raw_level(id)
	if l >= 10:
		return 1.0
	return (float(xp[id]) - xp_for_level(l)) / (xp_for_level(l + 1) - xp_for_level(l))

func gain_xp(id: String, amt: float, now: float) -> void:
	var before := level(id)
	var mult := 3.0 if float(book_until.get(id, 0.0)) > now else 1.0
	xp[id] = float(xp.get(id, 0.0)) + amt * mult
	var after := level(id)
	if after > before:
		level_up.emit(id, after)

func eye() -> Vector3:
	return Vector3(pos.x, pos.y + (1.62 - crouch_t * 0.6), pos.z)

func forward() -> Vector3:
	return Vector3(-sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch))

func capacity() -> float:
	var c := 18.0 + level("strength") * 1.6
	if traits.has("strong"): c += 3.0
	if traits.has("weak"): c -= 3.0
	for s in worn.values():
		if s != null:
			var cl = Items.def(s.id).get("cloth")
			if cl != null:
				c += float(cl.get("capacity", 0.0))
	return c

func weight() -> float:
	var w := inv.weight()
	for s in worn.values():
		if s != null:
			w += float(Items.def(s.id).weight) * 0.3
	return w

func weight_ratio() -> float:
	return weight() / maxf(1.0, capacity())

func equipped_stack():
	return inv.by_uid(equipped)

func insulation() -> float:
	var i := 0.0
	for s in worn.values():
		if s != null:
			var cl = Items.def(s.id).get("cloth")
			if cl != null:
				i += float(cl.get("insul", 0.0))
	return clampf(i, 0.0, 1.5)

func protection(slot: String) -> Dictionary:
	var s = worn.get(slot)
	if s == null:
		return {"bite": 0.0, "scratch": 0.0}
	var cl = Items.def(s.id).get("cloth", {})
	return {"bite": float(cl.get("bite", 0.0)), "scratch": float(cl.get("scratch", 0.0))}

static func damp(a: float, b: float, k: float, dt: float) -> float:
	return lerpf(a, b, 1.0 - exp(-k * dt))

func update(dt: float, control: bool, now: float) -> void:
	var st := stats
	var wr := weight_ratio()
	landed = 0.0; jumped = false; stepped = false
	pitch += recoil_pitch * dt * 12.0
	recoil_pitch = damp(recoil_pitch, 0.0, 10.0, dt)
	pitch = clampf(pitch, -1.5, 1.5)
	var fx := 0.0; var fz := 0.0
	if control and not sleeping:
		if Input.is_physical_key_pressed(KEY_W): fz -= 1.0
		if Input.is_physical_key_pressed(KEY_S): fz += 1.0
		if Input.is_physical_key_pressed(KEY_A): fx -= 1.0
		if Input.is_physical_key_pressed(KEY_D): fx += 1.0
	var l := sqrt(fx * fx + fz * fz)
	if l > 0.0:
		fx /= l; fz /= l
	var want_sprint := control and Input.is_physical_key_pressed(KEY_SHIFT) and fz < 0.0 and not crouch and st.can_sprint(wr)
	var scripted := auto_dir.x != 0.0 or auto_dir.z != 0.0
	if scripted:
		l = 1.0; want_sprint = auto_sprint and not crouch
	sprinting = want_sprint and l > 0.0
	var base := 1.6 if crouch else (6.0 + level("sprinting") * 0.12 + (0.4 if traits.has("athletic") else 0.0) if sprinting else 3.3)
	if traits.has("obese"): base *= 0.93
	base *= st.speed_mult(wr)
	var cs := cos(yaw); var sn := sin(yaw)
	var wx := fx * cs + fz * sn; var wz := -fx * sn + fz * cs
	if scripted:
		var al := sqrt(auto_dir.x * auto_dir.x + auto_dir.z * auto_dir.z)
		wx = auto_dir.x / al; wz = auto_dir.z / al
	var accel := 12.0 if on_ground else 2.0
	vel.x = damp(vel.x, wx * base, accel, dt)
	vel.z = damp(vel.z, wz * base, accel, dt)
	fall_vel -= 16.0 * dt
	var prev := pos
	pos.y += fall_vel * dt
	var h := 1.8 - crouch_t * 0.6
	# a long step is taken in pieces, each one collided, so that a thin wall is never jumped at a low frame rate
	var mvx := vel.x * dt; var mvz := vel.z * dt
	var pieces := maxi(1, int(ceil(sqrt(mvx * mvx + mvz * mvz) / 0.2)))
	var top := -INF
	# what is a wall and what is a step is judged from the ground the player stands on (or last stood on): a jump
	# does not turn a waist-high wall into something to walk onto
	var feet := minf(pos.y, _stand_y)
	for _i in pieces:
		pos.x = clampf(pos.x + mvx / pieces, -380.0, 380.0); pos.z = clampf(pos.z + mvz / pieces, -380.0, 380.0)
		var res := WorldData.resolve(Vector3(pos.x, feet, pos.z), 0.32, h + pos.y - feet, 0.45)
		pos.x = res.pos.x; pos.z = res.pos.z
		top = float(res.ground_y)
	var gy := WorldData.ground_y(pos.x, pos.z, pos.y)
	if top > gy:
		gy = top
	var wd := WorldData.water_depth(pos.x, pos.z)
	if wd > 0.4:
		vel *= exp(-dt * 3.0)
		gy = maxf(gy, WorldData.height_at(pos.x, pos.z))
	if pos.y <= gy + 0.02:
		if not on_ground and fall_vel < -9.0:
			var dmg := (-fall_vel - 9.0) * 6.0
			stats.add_wound("legL" if randf() < 0.5 else "legR", "fracture" if dmg > 25.0 else "scratch", dmg)
			cam_shake = 0.5
		if not on_ground and fall_vel < -3.0:
			landed = -fall_vel; stepped = true
		# up a step (a doorstep, a porch, a crate) the view rises quickly instead of jumping
		pos.y = gy if gy - pos.y < 0.1 else minf(gy, pos.y + 6.0 * dt)
		fall_vel = maxf(0.0, fall_vel); on_ground = true
		_stand_y = gy
	elif pos.y > gy + 0.35:
		on_ground = false
	elif fall_vel <= 0.0:
		pos.y = damp(pos.y, gy, 20.0, dt); on_ground = true; fall_vel = 0.0
		_stand_y = gy
	var moved := Vector2(pos.x - prev.x, pos.z - prev.z).length()
	speed = moved / maxf(dt, 1e-4)
	moving = speed > 0.3
	crouch_t = damp(crouch_t, 1.0 if crouch else 0.0, 10.0, dt)
	if on_ground and moving:
		_step_dist += moved
		var stride := 1.9 if sprinting else (0.9 if crouch else 1.45)
		if _step_dist > stride:
			_step_dist = 0.0; stepped = true
	var n := 0.0
	if moving:
		n = 16.0 if sprinting else (2.0 if crouch else 6.5)
	n *= 1.0 - level("lightfoot") * 0.06
	if traits.has("clumsy"): n *= 1.5
	noise = n
	visibility = clampf((0.45 if crouch else 0.85) + (0.6 if flashlight_on else 0.0) + (0.2 if sprinting else 0.0), 0.0, 1.4)
	_bob += moved * (1.6 if sprinting else 2.2)
	var amp := minf(1.0, speed / 5.0) * (1.0 if on_ground else 0.0)
	head_bob = Vector3(sin(_bob) * 0.03 * amp, absf(cos(_bob)) * 0.05 * amp, 0.0)
	cam_shake = maxf(0.0, cam_shake - dt * 2.0)
	var fl := WorldData.floor_at(pos.x, pos.z)
	inside = not fl.is_empty() and absf(float(fl[4]) - pos.y) < 1.0
	step_surface = "wood" if inside else "grass"
	if sprinting:
		gain_xp("sprinting", dt * 0.8, now); gain_xp("fitness", dt * 0.5, now)
	if crouch and moving: gain_xp("lightfoot", dt * 0.5, now)
	if wr > 1.0 and moving: gain_xp("strength", dt * 0.3, now)

func jump() -> void:
	if on_ground and stats.stamina > 8.0 and not crouch and not sleeping:
		fall_vel = 4.4; on_ground = false; stats.stamina -= 6.0; jumped = true

func to_dict() -> Dictionary:
	return {"pos": [pos.x, pos.y, pos.z], "yaw": yaw, "pitch": pitch, "inv": inv.to_rows(), "equipped": equipped, "hotbar": hotbar, "worn": worn.duplicate(true), "stats": stats.to_dict(), "xp": xp, "bonus": bonus, "books": book_until, "traits": traits.keys(), "occupation": occupation, "name": name, "kills": kills}

func from_dict(o: Dictionary) -> void:
	pos = Vector3(o.pos[0], o.pos[1], o.pos[2]); yaw = float(o.yaw); pitch = float(o.pitch)
	inv.load_rows(o.inv)
	equipped = null if o.equipped == null else int(o.equipped)
	hotbar = []
	for h in o.hotbar:
		hotbar.append(null if h == null else int(h))
	worn = o.worn.duplicate(true)
	stats.from_dict(o.stats)
	for k in o.xp: xp[k] = float(o.xp[k])
	for k in o.bonus: bonus[k] = int(o.bonus[k])
	book_until = o.get("books", {})
	traits = {}
	for t in o.traits: traits[t] = true
	occupation = String(o.occupation); name = String(o.name); kills = int(o.get("kills", 0))
