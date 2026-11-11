class_name Zombies
extends Node3D
## The dead. A port of the web game's zombie director and AI (src/ai/zombies.ts), drawn through Bodies.
##
## Senses (a sight cone scaled by daylight and by how visible the quarry is, hearing, the smell of blood), a
## day / night rhythm (many stand or lie dormant by day), loose packs, a life cycle (fresh -> shambler -> husk),
## six strains, and hunting of every living thing: the player, and any Prey registered with add_prey().
##
##   var zombies := Zombies.new(); add_child(zombies)      # makes its own Bodies child: zombies.bodies (share it)
##   zombies.populate()                                     # the starting population (town rooms, streets, wilds)
##   zombies.update(delta, game_minutes_passed, host)       # once per frame; host keys are listed at update()
##
##   signals   player_attacked(z, dmg)  human_attacked(prey, z, dmg)  killed(z, headshot)  howl(pos, radius, z)
##             burst(pos, radius, z)  structure_hit(ref, dmg)  horde_spawned(n, to_base)
##   the living   add_prey(owner, name, body_id) -> Prey (keep prey.pos / move / quiet / visible / alive current),
##                remove_prey(prey), add_carrion(pos, seconds), noise(pos, radius)
##   the dead     spawn(x, z, type, horde, opts), spawn_turned(prey_or_dict), spawn_horde(...), remove(z), clear()
##   combat       raycast(origin, dir, max) -> {zombie, t, part, head, side} or {},  damage(z, amount, dir, opts),
##                kill(z, dir), in_arc(...), nearest(pos, r), corpse_near(pos, r)
##   words        describe(z), stage(z), pick_type(day)
##   saving       to_rows(), load_rows(rows)
##   each Zombie  pos, yaw, type, hp, max_hp, state (a StringName: idle wander investigate chase attack bash stagger
##                dead dormant feed search scream down rise), name, turned, decay, age, crawler, prey, body, loot
##
## Built for GDScript speed: a zombie *thinks* a few times a second (staggered; most often when hunting close by)
## and between thoughts only walks along the heading it chose. Beyond 230 m of the player it is frozen, and it
## has a body in the shared Bodies renderer only within about 190 m.
## Tests: scripts/zombie_test.gd (behaviour), scripts/zombie_perf.gd (cost), scripts/zombie_scene.tscn (to look at).

signal player_attacked(z, dmg)
signal human_attacked(prey, z, dmg)
signal burst(pos, radius, z)
signal howl(pos, radius, z)
signal killed(z, headshot)
signal structure_hit(ref, dmg)
## a horde has set out: (count, heading_for_the_base)
signal horde_spawned(n, to_base)

const WALKER := 0
const RUNNER := 1
const BRUTE := 2
const HOWLER := 3
const BLOAT := 4
const BREAKER := 5
const TYPE_NAMES := ["Walker", "Runner", "Brute", "Howler", "Bloat", "Breaker"]
const TYPE_SCALE := [1.0, 1.0, 1.24, 1.13, 0.98, 1.08]
## per type: hp range, walk range, run range, damage to people, damage to structures, turn rate, attack wind-up, period
const STATS := [
	{"hp": [90.0, 130.0], "walk": [0.7, 1.3], "run": [1.7, 2.6], "dmg": 9.0, "wreck": 9.0, "turn": 2.6, "wind": 0.42, "period": 1.3},
	{"hp": [80.0, 92.0], "walk": [1.0, 1.4], "run": [4.6, 5.6], "dmg": 8.0, "wreck": 6.0, "turn": 4.4, "wind": 0.3, "period": 1.1},
	{"hp": [410.0, 440.0], "walk": [0.95, 1.05], "run": [2.5, 2.7], "dmg": 25.0, "wreck": 45.0, "turn": 2.0, "wind": 0.62, "period": 1.6},
	{"hp": [50.0, 62.0], "walk": [1.0, 1.2], "run": [2.9, 3.3], "dmg": 6.0, "wreck": 4.0, "turn": 3.2, "wind": 0.4, "period": 1.4},
	{"hp": [160.0, 190.0], "walk": [0.55, 0.7], "run": [1.2, 1.4], "dmg": 8.0, "wreck": 6.0, "turn": 1.5, "wind": 0.5, "period": 1.7},
	{"hp": [250.0, 280.0], "walk": [0.85, 1.0], "run": [2.2, 2.5], "dmg": 22.0, "wreck": 85.0, "turn": 2.0, "wind": 0.75, "period": 1.9},
]
const MAX := 700
const FREEZE_DIST := 230.0
const BODY_IN := 190.0
const BODY_OUT := 212.0
const FIELD := 72           # flow-field window, cells (1 m)
const NAV_N := 800
const HALF := 400.0

# states
const S_IDLE := &"idle"
const S_WANDER := &"wander"
const S_INVESTIGATE := &"investigate"
const S_CHASE := &"chase"
const S_ATTACK := &"attack"
const S_BASH := &"bash"
const S_STAGGER := &"stagger"
const S_DEAD := &"dead"
const S_DORMANT := &"dormant"
const S_FEED := &"feed"
const S_SEARCH := &"search"
const S_SCREAM := &"scream"
const S_DOWN := &"down"
const S_RISE := &"rise"

class Zombie:
	var id := 0
	var type := 0
	var pos := Vector3.ZERO
	var vel := Vector3.ZERO
	var yaw := 0.0
	var hp := 1.0
	var max_hp := 1.0
	var state: StringName = &"idle"
	var state_t := 0.0
	var target := Vector3.ZERO
	var seed := 0.0
	var scale := 1.0
	var speed := 1.0
	var run_speed := 2.0
	var alerted := 0.0
	var last_seen := Vector3.ZERO
	var horde := 0
	var bash_ref = null
	## game minutes it has been dead
	var corpse_age := 0.0
	var died_at := 0.0
	var frozen := false
	var knock := Vector3.ZERO
	var crawler := false
	## in-game days since it turned, and 0 fresh .. 1 husk
	var age := 0.0
	var decay := 0.0
	var name := ""
	var turned := false
	## who struck last (and so who killed it); free for the game: whatever was put on the corpse to be found
	var by_player := false
	var loot = null
	## the Prey it is after; null while hunting = the player
	var prey: Prey = null
	## Bodies id, or -1 while it is too far away to be drawn
	var body := -1
	var look := {}
	var arm_loss_l := false
	var arm_loss_r := false
	var gait := 0
	# ---- internals
	var think_t := 0.0
	var sense_t := 0.0
	var atk_t := 0.0
	var atk_period := 1.3
	var struck := false
	var pivot := false
	var avoid := 0
	var avoid_t := 0.0
	var down_face := 1
	var down_stage := 0
	var lie_t := 0.0
	var rise_dur := 2.0
	var after: StringName = &"idle"
	var lying := false
	var sated := 0.0
	var feed_at: Carrion = null
	var enraged := 0.0
	var scream_cd := 0.0
	var screamed := false
	var scan_t := 0.0
	var age_at := 0.0
	var t_last := 0.0
	var base_hp := 100.0
	var base_speed := 1.0
	var base_run := 2.0
	var dmg_mul := 1.0
	var burst := false
	var idle_act: StringName = &""
	var pause := 5.0
	var wake_t := 0.0
	var ignore: Prey = null
	var ignore_t := 0.0
	var stuck_t := 0.0
	var last_pos := Vector3.ZERO
	var death_style := 0
	# heading chosen at the last thought, walked along every frame
	var want := 0.0
	var dir := Vector3.ZERO
	var face := NAN
	var turn_rate := 2.6
	var ground := 0.0
	var moving := false
	var anim: StringName = &""
	var anim_v := -1.0
	var dp := 0.0
	var push := Vector3.ZERO
	var res_n := 0
	var res_d := 0.0
	var gone := false

## Something the dead can see and attack: a person, or the player's avatar. Keep pos / yaw / move up to date.
class Prey:
	var pos := Vector3.ZERO
	var yaw := 0.0
	## 0 standing, 1 walking, 2 running
	var move := 0.0
	## cowering, sitting or lying: much harder to notice
	var quiet := false
	var visible := true
	## false once dead or out of the world; a dead body left lying (corpse = true) draws feeders
	var alive := true
	var corpse := false
	var name := ""
	## Bodies id of the person, if they have one (lets a turned zombie keep their look)
	var body := -1
	var seed := 0.0
	var look := {}
	var owner = null
	var hunters := 0

class Carrion:
	var pos := Vector3.ZERO
	var until := 0.0
	var feeders := 0
	var prey: Prey = null

class Horde:
	var id := 0
	var target := Vector3.ZERO
	var members := 0
	var t := 0.0
	var to_base := false

## the shared character renderer (people use it too)
var bodies: Bodies
var list: Array[Zombie] = []
var prey_list: Array[Prey] = []
var hordes: Array[Horde] = []
var carrion: Array[Carrion] = []
var kills := 0
## hordes at night and stragglers wandering in from the wilds; turn off for staged scenes and tests
var director_enabled := true
## optional: called as on_structure_hit(ref, dmg) whenever the dead strike something breakable — the same
## moment as the structure_hit signal. `ref` is the WorldData.nav_ref entry (a door, a built piece).
var on_structure_hit := Callable()
## what update() reported last: zombies chasing the player, near the player, and with eyes on the player
var chasing := 0
var near_count := 0
var seen_count := 0

var _rng := RandomNumberGenerator.new()
var _next_id := 1
var _next_horde := 1
var _time := 0.0
var _game_min := 0.0
var _day := 1
var _horde_t := 0.0
var _respawn_t := 0.0
var _carrion_t := 0.0
var _noise: Array = []          # [pos, radius]
var _packs := {}
var _grid := {}
var _grid_t := 0.0
var _last_ray := {}
var _active: Array[Zombie] = []
var _scan := 0
var _W: Node
var _nav: PackedByteArray
# host values, cached for the frame
var _pp := Vector3.ZERO
var _pe := Vector3.ZERO
var _p_noise := 0.0
var _p_vis := 1.0
var _p_alive := false
var _p_inside := false
var _p_bleed := false
var _daylight := 1.0
var _night := 0.0
var _hours := 12.0
var _base_center = null
var _base_heat := 0.0
var _cam := Vector3.ZERO
var _c_chase := 0
var _c_seen := 0
# flow field toward the player (built a slice per frame)
var _fd := PackedInt32Array()
var _fd_build := PackedInt32Array()
var _f_ox := 0
var _f_oz := 0
var _fb_ox := 0
var _fb_oz := 0
var _f_valid := false
var _f_queue := PackedInt32Array()
var _f_head := 0
var _f_late: Array = []
var _f_building := false
var _f_wait := 0.0
var _f_need := 0.0              # how far out the field is wanted (the farthest zombie using it)
var _f_limit := 36
# per nav cell: 1 = something static stands within reach, 2 = a building floor is overhead/underfoot
var _obst := PackedByteArray()
var _n_floors := -1
var _made := 0
var _alerts := 0
var _walking := false
var _gone := false

func _init() -> void:
	_rng.seed = 20261111 + 31

func _ready() -> void:
	_W = get_node_or_null("/root/WorldData")
	if _W != null:
		_nav = _W.nav
	_build_static()
	if bodies == null:
		bodies = Bodies.new()
		bodies.name = "Bodies"
		add_child(bodies)
	_fd.resize(FIELD * FIELD); _fd_build.resize(FIELD * FIELD)

## Use a world other than the WorldData autoload (tests).
func set_world(w: Node) -> void:
	_W = w
	_nav = w.nav
	_build_static()

## Where the world's fixed things are, so that a zombie out in the open can skip the collision pass.
func _build_static() -> void:
	_obst = PackedByteArray(); _obst.resize(NAV_N * NAV_N)
	if _W == null or not (_W.info is Dictionary) or _W.info.is_empty():
		_obst.fill(3)
		return
	for b in _W.info.get("boxes", []):
		_mark(float(b[0]) - 0.7, float(b[2]) - 0.7, float(b[3]) + 0.7, float(b[5]) + 0.7, 1)
	for c in _W.info.get("circles", []):
		var r := float(c[2]) + 0.7
		_mark(float(c[0]) - r, float(c[1]) - r, float(c[0]) + r, float(c[1]) + r, 1)
	_mark_floors()

func _mark_floors() -> void:
	_n_floors = _W.floors.size()
	for f in _W.floors:
		_mark(float(f[0]) - 0.5, float(f[1]) - 0.5, float(f[2]) + 0.5, float(f[3]) + 0.5, 2)

func _mark(x0: float, z0: float, x1: float, z1: float, bit: int) -> void:
	var ax := maxi(0, int(floor(x0 + HALF))); var bx := mini(NAV_N - 1, int(floor(x1 + HALF)))
	var az := maxi(0, int(floor(z0 + HALF))); var bz := mini(NAV_N - 1, int(floor(z1 + HALF)))
	for iz in range(az, bz + 1):
		var row := iz * NAV_N
		for ix in range(ax, bx + 1):
			_obst[row + ix] |= bit

func seed_rng(s: int) -> void:
	_rng.seed = s + 31

# ------------------------------------------------------------------ the living they hunt

## Register something the dead can perceive and attack. Returns the Prey; keep its pos (and move / quiet / visible)
## current, set alive = false when it dies (and corpse = true if a body is left lying), and call remove_prey()
## when it leaves the world.
func add_prey(owner = null, name := "", body := -1) -> Prey:
	var p := Prey.new()
	p.owner = owner; p.name = name; p.body = body
	prey_list.append(p)
	return p

func remove_prey(p: Prey) -> void:
	prey_list.erase(p)
	for z in list:
		if z.prey == p:
			z.prey = null
		if z.ignore == p:
			z.ignore = null

## Mark something the dead will gather round and eat for a while (a kill, a dropped carcass).
func add_carrion(pos: Vector3, seconds := 90.0) -> void:
	var c := Carrion.new()
	c.pos = pos; c.until = _time + seconds
	carrion.append(c)

## A sound the dead may hear.
func noise(pos: Vector3, radius: float) -> void:
	if radius > 0.5:
		_noise.append([pos, radius])

# ------------------------------------------------------------------ spawning

## The starting population: the town's rooms and streets, and stragglers in the wilds. `buildings` is
## World.buildings (id -> {data}) for the indoor ones; left empty, the town's own file is read instead.
func populate(buildings: Dictionary = {}) -> void:
	var town: Dictionary = _W.info.town
	var spawn_pt: Dictionary = _W.info.spawn
	var datas: Array = []
	for id in buildings:
		datas.append(buildings[id].data)
	if datas.is_empty() and FileAccess.file_exists("res://assets/world/town.json"):
		var town_data = JSON.parse_string(FileAccess.get_file_as_string("res://assets/world/town.json"))
		if town_data is Dictionary:
			datas = town_data.get("buildings", [])
	for b: Dictionary in datas:
		var in_town: bool = b.x > town.x0 and b.x < town.x1 and b.z > town.z0 and b.z < town.z1
		if String(b.get("kind", "")) == "cabin":
			continue
		for s in b.get("zombieSpawns", []):
			var sx := 0.0; var sz := 0.0; var sy := INF
			if s is Array:
				sx = s[0]; sz = s[2] if s.size() > 2 else s[1]
				if s.size() > 2: sy = s[1]
			else:
				sx = s.x; sz = s.z; sy = float(s.get("y", INF))
			if _rng.randf() < (0.55 if in_town else 0.3):
				spawn(sx + _rng.randf_range(-1, 1), sz + _rng.randf_range(-1, 1), RUNNER if _rng.randf() < 0.08 else WALKER, 0, {"y": sy})
	for i in 170:
		var x := _rng.randf_range(town.x0, town.x1); var z := _rng.randf_range(town.z0, town.z1)
		if _W.nav_at(x, z) != 0:
			continue
		spawn(x, z, RUNNER if _rng.randf() < 0.1 else BRUTE if _rng.randf() < 0.03 else WALKER)
	for i in 110:
		var x := _rng.randf_range(-HALF + 40, HALF - 40); var z := _rng.randf_range(-HALF + 40, HALF - 40)
		if Vector2(x - spawn_pt.x, z - spawn_pt.z).length() < 60.0 or _W.nav_at(x, z) != 0 or _W.water_depth(x, z) > 0.3:
			continue
		spawn(x, z, RUNNER if _rng.randf() < 0.15 else WALKER)

## Which strain appears: the special ones creep in as the days pass.
func pick_type(day: int, siege := false) -> int:
	var r := _rng.randf()
	var a := minf(0.2, 0.09 + day * 0.008)
	if r < a: return RUNNER
	a += minf(0.08, 0.025 + day * 0.004)
	if r < a: return BRUTE
	if day >= 3: a += minf(0.07, 0.02 + (day - 3) * 0.006)
	if r < a: return HOWLER
	if day >= 5: a += minf(0.08, 0.02 + (day - 5) * 0.006)
	if r < a: return BLOAT
	if day >= 8: a += minf(0.06, 0.015 + (day - 8) * 0.005) * (2.0 if siege else 1.0)
	if r < a: return BREAKER
	return WALKER

## Spawn a zombie. opts: decay (how far gone; default mostly shamblers, some husks, a few fresh), seed, look
## (HumanLook keys) and name pin who it was; y = the floor it stands on (for rooms above the ground).
func spawn(x: float, z: float, type: int, horde := 0, opts := {}) -> Zombie:
	if list.size() >= MAX - 5:
		return null
	type = clampi(type, 0, 5)
	var st: Dictionary = STATS[type]
	var zb := Zombie.new()
	zb.id = _next_id; _next_id += 1
	zb.type = type
	# everything about who it is follows from the seed, so a save only needs that (kept to 3 decimals)
	zb.seed = snappedf(float(opts.get("seed", _rng.randf() * 100.0)), 0.001)
	var r0 := _rng.randf()
	var decay := 0.0
	if opts.has("decay"):
		decay = float(opts.decay)
	elif type >= 2:
		decay = _rng.randf_range(0.3, 0.6)
	elif type == 1:
		decay = _rng.randf_range(0.05, 0.4)
	else:
		decay = _rng.randf_range(0.0, 0.15) if r0 < 0.05 else _rng.randf_range(0.78, 1.0) if r0 < 0.24 else _rng.randf_range(0.25, 0.72)
	zb.decay = decay; zb.age = _decay_to_age(decay)
	zb.look = opts.get("look", {})
	zb.name = String(opts.get("name", ""))
	zb.gait = Bodies.gait_for(type, zb.seed)
	if type == 0 and decay > 0.4 and _rng.randf() < 0.07:
		if _rng.randf() < 0.5: zb.arm_loss_l = true
		else: zb.arm_loss_r = true
	var base_look: Dictionary = Bodies.look_from_seed(zb.seed)
	zb.scale = clampf(float(zb.look.get("height", base_look.height)), 0.8, 1.25) * float(TYPE_SCALE[type])
	# without a floor given, stand on the ground (or a ground floor), never on the storeys above it
	var y: float = _W.ground_y(x, z, float(opts.y) if opts.has("y") else _W.height_at(x, z) + 0.6)
	zb.pos = Vector3(x, y, z); zb.ground = y; zb.last_pos = zb.pos
	zb.target = zb.pos; zb.last_seen = zb.pos
	zb.yaw = _rng.randf_range(0.0, TAU)
	zb.state_t = _rng.randf_range(0.0, 5.0)
	zb.horde = horde
	zb.base_hp = lerpf(st.hp[0], st.hp[1], Bodies.hash1(zb.seed * 7.31 + 1.1)); zb.base_speed = lerpf(st.walk[0], st.walk[1], Bodies.hash1(zb.seed * 3.77 + 2.3)); zb.base_run = lerpf(st.run[0], st.run[1], Bodies.hash1(zb.seed * 5.19 + 4.7))
	zb.atk_period = st.period; zb.turn_rate = st.turn
	zb.scream_cd = _rng.randf_range(0.0, 6.0); zb.scan_t = _rng.randf_range(0.0, 2.0)
	zb.pause = 4.0 + _rng.randf_range(0.0, 6.0)
	zb.think_t = _rng.randf_range(0.0, 0.5); zb.sense_t = _rng.randf_range(0.0, 0.5)
	zb.age_at = _game_min; zb.t_last = _time
	if _time > 0.0 and Vector2(x - _pp.x, z - _pp.z).length() < 70.0:
		zb.think_t = 0.0                 # dropped in close by: show up at once
	_apply_decay(zb, true)
	zb.hp = zb.max_hp
	list.append(zb)
	_active.append(zb)
	return zb

## Someone who died infected gets back up where they fell: same face, clothes and build, skin gone grey. Pass the
## Prey (its body's look is copied) or a Dictionary {pos, yaw, seed, look, name}. Starts flat on its back and
## rises over a few seconds — fresh, fast and strong.
func spawn_turned(who) -> Zombie:
	var pos := Vector3.ZERO; var yaw := 0.0; var seed := _rng.randf() * 100.0; var look := {}; var nm := ""
	if who is Prey:
		pos = who.pos; yaw = who.yaw; nm = who.name; seed = who.seed; look = who.look.duplicate()
		if who.body >= 0 and bodies.has_body(who.body):
			var hb = bodies.get_body(who.body)
			seed = hb.seed
			look = {"skin": hb.skin, "shirt": hb.shirt, "pants": hb.pants, "hair": hb.hair, "build": hb.build, "height": hb.height, "hairStyle": hb.hair_style, "outfit": hb.outfit, "jacket": hb.jacket, "gear": hb.gear}
	else:
		pos = who.get("pos", pos); yaw = float(who.get("yaw", 0.0)); seed = float(who.get("seed", seed)); look = who.get("look", {}); nm = String(who.get("name", ""))
	var z := spawn(pos.x, pos.z, WALKER, 0, {"decay": 0.0, "seed": seed, "look": look, "name": nm})
	if z == null:
		return null
	z.turned = true; z.yaw = yaw; z.pos.y = pos.y; z.ground = pos.y
	z.gait = 0 if Bodies.hash1(seed * 6.13) < 0.5 else 1
	z.arm_loss_l = false; z.arm_loss_r = false
	z.state = S_RISE; z.state_t = 0.0; z.down_face = 1; z.down_stage = 0; z.lying = true
	z.lie_t = _rng.randf_range(0.6, 1.4); z.rise_dur = _rng.randf_range(2.4, 3.2); z.after = S_IDLE
	z.pause = 0.5; z.think_t = 0.0
	z.anim = &"lie_back0"; z.anim_v = 1.0
	if _time > 0.0 and Vector2(pos.x - _pp.x, pos.z - _pp.z).length() < BODY_IN:
		_make_body(z)                    # no gap between the person's body and what gets up
	return z

func spawn_horde(cx: float, cz: float, n: int, target: Vector3, to_base := false) -> Horde:
	var h := Horde.new()
	h.id = _next_horde; _next_horde += 1
	h.target = target; h.to_base = to_base
	for i in n:
		var a := _rng.randf_range(0.0, TAU); var r := sqrt(_rng.randf()) * (6.0 + n * 0.12)
		var x := cx + cos(a) * r; var z := cz + sin(a) * r
		if not _W.in_bounds(x, z):
			continue
		var zb := spawn(x, z, pick_type(_day, to_base), h.id)
		if zb != null:
			zb.state = S_INVESTIGATE
			zb.target = target + Vector3(_rng.randf_range(-8, 8), 0, _rng.randf_range(-8, 8))
			h.members += 1
	hordes.append(h)
	return h

func stage(z: Zombie) -> String:
	return "fresh" if z.decay < 0.2 else "shambler" if z.decay < 0.75 else "husk"

## short description for UI: "Howler", "Husk", "what's left of Marcus"
func describe(z: Zombie) -> String:
	if z.name != "":
		return "what's left of %s" % z.name
	if z.type != 0:
		return TYPE_NAMES[z.type]
	return "Fresh walker" if z.decay < 0.2 else "Walker" if z.decay < 0.75 else "Husk"

static func _smooth(a: float, b: float, x: float) -> float:
	var t := 0.0 if x <= a else 1.0 if x >= b else (x - a) / (b - a)
	return t * t * (3.0 - 2.0 * t)

static func _age_to_decay(days: float) -> float:
	return days * 0.11 if days < 2.0 else 0.22 + (days - 2.0) * 0.055 if days < 12.0 else minf(1.0, 0.77 + (days - 12.0) * 0.02)

static func _decay_to_age(d: float) -> float:
	return d / 0.11 if d < 0.22 else 2.0 + (d - 0.22) / 0.055 if d < 0.77 else 12.0 + (d - 0.77) / 0.02

## stats and look follow decay: fresh are fast and strong, husks slow and brittle
func _apply_decay(z: Zombie, init := false) -> void:
	var d := z.decay
	var fresh := 1.0 - _smooth(0.05, 0.3, d); var husk := _smooth(0.62, 1.0, d)
	var k := 0.4 if z.type >= 2 else 0.25 if z.type == 1 else 1.0
	var sp := 1.0 + (0.4 * fresh - 0.38 * husk) * k; var hp_m := 1.0 + (0.25 * fresh - 0.5 * husk) * k
	var ratio := 1.0 if init else z.hp / maxf(1.0, z.max_hp)
	z.max_hp = round(z.base_hp * hp_m); z.hp = z.max_hp if init else maxf(1.0, z.max_hp * ratio)
	z.speed = z.base_speed * (1.0 + (0.15 * fresh - 0.3 * husk) * k)
	z.run_speed = z.base_run * sp + (fresh * 1.5 if z.type == 0 else 0.0)
	z.dmg_mul = (1.0 + (0.3 * fresh - 0.3 * husk) * k) * (0.65 if z.arm_loss_l else 1.0) * (0.65 if z.arm_loss_r else 1.0)
	if z.crawler:
		z.speed = 0.45; z.run_speed = 0.9 + fresh * 0.4
	if z.body >= 0:
		bodies.set_state(z.body, {"decay": d, "arm_loss_l": z.arm_loss_l, "arm_loss_r": z.arm_loss_r})

# ------------------------------------------------------------------ nav helpers

func _nav_at(x: float, z: float) -> int:
	var ix := int(floor(x + HALF)); var iz := int(floor(z + HALF))
	if ix < 0 or iz < 0 or ix >= NAV_N or iz >= NAV_N:
		return 255
	return _nav[iz * NAV_N + ix]

func _ref_at(x: float, z: float):
	return _W.nav_ref.get(_W.nav_index(x, z))

static func _rget(ref, key: String, def):
	if ref is Dictionary:
		return ref.get(key, def)
	if ref is Object:
		var v = ref.get(key)
		return def if v == null else v
	return def

func _bashable(ref) -> bool:
	if ref == null:
		return false
	if _rget(ref, "destroyed", false) or _rget(ref, "open", false):
		return false
	if _rget(ref, "broken", false) and float(_rget(ref, "barricade", 0.0)) <= 0.0 and _rget(ref, "hp", null) == null:
		return false
	return true

static func _ref_pos(ref, fallback: Vector3) -> Vector3:
	var d = ref
	if ref is Dictionary and ref.has("data"):
		d = ref.data
	var x = _rget(d, "cx", _rget(d, "x", null)); var z = _rget(d, "cz", _rget(d, "z", null))
	if x == null or z == null:
		var bx = _rget(ref, "box", null)
		if bx != null:
			return (bx.min + bx.max) * 0.5
		return fallback
	return Vector3(float(x), fallback.y, float(z))

## Straight-line pursuit with local avoidance: probe the nav grid ahead and fan out to whichever side is clear.
## Sets z.dir; returns a destructible squarely in the way, if any.
func _steer(z: Zombie, tx: float, tz: float):
	var dx := tx - z.pos.x; var dz := tz - z.pos.z
	var d := sqrt(dx * dx + dz * dz)
	if d < 1e-4:
		z.dir = Vector3.ZERO
		return null
	dx /= d; dz /= d
	var near := minf(d, 0.8); var reach := minf(d, 1.5)
	var px := z.pos.x; var pz := z.pos.z
	var n0 := _nav_at(px + dx * near, pz + dz * near); var n1 := _nav_at(px + dx * reach, pz + dz * reach)
	if n0 == 0 and n1 == 0:
		z.dir = Vector3(dx, 0, dz)
		if z.avoid_t <= 0.0: z.avoid = 0
		return null
	var block = null
	if n0 != 255 and n0 != 0: block = _ref_at(px + dx * near, pz + dz * near)
	elif n0 == 0 and n1 != 255: block = _ref_at(px + dx * reach, pz + dz * reach)
	var a0 := atan2(dx, dz)
	var first := z.avoid if z.avoid != 0 else (1 if Bodies.hash1(z.seed + floor(_time * 0.2)) < 0.5 else -1)
	for k in range(1, 5):
		for s in 2:
			var sg := first if s == 0 else -first
			if s == 1 and z.avoid != 0 and k < 3:
				continue
			var a := a0 + sg * k * 0.55; var ax := sin(a); var az := cos(a)
			if _nav_at(px + ax * 0.8, pz + az * 0.8) == 0 and _nav_at(px + ax * 1.5, pz + az * 1.5) == 0:
				z.dir = Vector3(ax, 0, az); z.avoid = sg; z.avoid_t = 1.6
				return block
	z.dir = Vector3(dx, 0, dz)
	return block

# ---- flow field toward the player, built a slice per frame so it never costs a spike
func _field_begin() -> void:
	_fb_ox = int(floor(_pp.x + HALF)) - FIELD / 2; _fb_oz = int(floor(_pp.z + HALF)) - FIELD / 2
	_fd_build.fill(1 << 30)
	_f_queue.clear(); _f_head = 0; _f_late.clear()
	var c := (FIELD / 2) * FIELD + FIELD / 2
	_fd_build[c] = 0
	_f_queue.append(c)
	_f_building = true
	_f_limit = clampi(int(_f_need) + 6, 10, FIELD / 2)
	_f_need = 0.0

func _field_step(budget: int) -> void:
	var dist := _fd_build
	var q := _f_queue
	var head := _f_head
	var nav := _nav
	var ox := _fb_ox; var oz := _fb_oz
	var lo := FIELD / 2 - _f_limit; var hi := FIELD / 2 + _f_limit - 1
	while budget > 0:
		if head >= q.size():
			if _f_late.is_empty():
				# done: publish
				var t := _fd; _fd = _fd_build; _fd_build = t
				_f_ox = _fb_ox; _f_oz = _fb_oz; _f_valid = true; _f_building = false; _f_wait = 0.25
				_f_head = head
				return
			# doors and walls cost extra: their cells are only expanded once the open ground has been
			_f_late.sort()
			for e in _f_late:
				q.append(e & 0xffff)
			_f_late.clear()
			continue
		var i := q[head]; head += 1; budget -= 1
		var d := dist[i]
		var cx := i % FIELD; var cz := i / FIELD
		for k in 4:
			var nx := cx + (1 if k == 0 else -1 if k == 1 else 0); var nz := cz + (1 if k == 2 else -1 if k == 3 else 0)
			if nx < lo or nz < lo or nx > hi or nz > hi:
				continue
			var gx := ox + nx; var gz := oz + nz
			if gx < 0 or gz < 0 or gx >= NAV_N or gz >= NAV_N:
				continue
			var nv := nav[gz * NAV_N + gx]
			if nv == 255:
				continue
			var ni := nz * FIELD + nx
			var nd := d + (1 if nv == 0 else 6)
			if nd < dist[ni]:
				dist[ni] = nd
				if nv == 0: q.append(ni)
				else: _f_late.append((nd << 16) | ni)
	_f_head = head

## Desired direction from the flow field into z.dir; false when the field does not reach this far. A breakable
## thing squarely in the way is left in _flow_block.
var _flow_block = null
func _flow_dir(z: Zombie) -> bool:
	_flow_block = null
	if z.dp > _f_need: _f_need = z.dp
	if not _f_valid:
		return false
	var gx := int(floor(z.pos.x + HALF)); var gz := int(floor(z.pos.z + HALF))
	var cx := gx - _f_ox; var cz := gz - _f_oz
	if cx < 1 or cz < 1 or cx >= FIELD - 1 or cz >= FIELD - 1:
		return false
	var here := _fd[cz * FIELD + cx]
	if here >= (1 << 30):
		return false
	var best := here; var bx := 0; var bz := 0
	for j in range(-1, 2):
		var row := (cz + j) * FIELD + cx
		for k in range(-1, 2):
			var d := _fd[row + k]
			if d < best or (d == best and k * j == 0 and bx * bz != 0):
				# no cutting corners through walls
				if k != 0 and j != 0 and (_nav[(gz + j) * NAV_N + gx] != 0 or _nav[gz * NAV_N + gx + k] != 0):
					continue
				best = d; bx = k; bz = j
	if bx == 0 and bz == 0:
		return false
	z.dir = Vector3(bx, 0, bz) * (0.70710678 if bx != 0 and bz != 0 else 1.0)
	var ng := (gz + bz) * NAV_N + gx + bx
	var v := _nav[ng]
	if v != 0 and v != 255:
		_flow_block = _W.nav_ref.get(ng)
	return true

# ------------------------------------------------------------------ queries for combat

static func _ray_sphere(o: Vector3, d: Vector3, c: Vector3, r: float) -> float:
	var oc := o - c
	var b := oc.dot(d); var cc := oc.dot(oc) - r * r
	var disc := b * b - cc
	if disc < 0.0:
		return -1.0
	return -b - sqrt(disc)

## Hitscan against the dead. Returns {zombie, t, part, head} (part: 0 head, 1 torso, 2 arm, 3 legs), or {} on a miss.
func raycast(origin: Vector3, dir: Vector3, max_t: float) -> Dictionary:
	var best: Zombie = null; var bt := max_t; var bpart := 1
	var reach2 := (max_t + 3.0) * (max_t + 3.0)
	for z in _active:
		if z.state == S_DEAD or z.frozen:
			continue
		var ddx := z.pos.x - origin.x; var ddz := z.pos.z - origin.z
		if ddx * ddx + ddz * ddz > reach2:
			continue
		# quick reject: the ray must pass within 1.2 m of the body's axis (in plan)
		var along := ddx * dir.x + ddz * dir.z
		var px := ddx - dir.x * along; var pz := ddz - dir.z * along
		if absf(dir.y) < 0.9 and px * px + pz * pz > 2.2:
			continue
		var s := z.scale
		var f := Vector3(sin(z.yaw), 0, cos(z.yaw))
		if z.crawler or z.state == S_DOWN or (z.state == S_RISE and z.down_stage < 2) or (z.state == S_DORMANT and z.lying):
			var ds := 1.0 if z.crawler else (-1.0 if z.down_face >= 0 else 1.0)
			var off := 0.0 if (z.crawler or z.lying) else (-0.55 if z.down_face >= 0 else 0.55)
			var th := _ray_sphere(origin, dir, z.pos + f * ((off + ds * 0.75) * s) + Vector3(0, 0.26 * s, 0), 0.17 * s)
			if th > 0.0 and th < bt:
				best = z; bt = th; bpart = 0
			for kk in [0.3, -0.1, -0.5]:
				var t := _ray_sphere(origin, dir, z.pos + f * ((off + ds * kk) * s) + Vector3(0, 0.22 * s, 0), 0.25 * s)
				if t > 0.0 and t < bt - 0.05:
					best = z; bt = t; bpart = 3 if kk < -0.2 else 1
			continue
		var th2 := _ray_sphere(origin, dir, z.pos + f * (0.1 * s) + Vector3(0, 1.64 * s, 0), 0.15 * s)
		if th2 > 0.0 and th2 < bt:
			best = z; bt = th2; bpart = 0
		var wide := 1.25 if z.type == 2 else 1.45 if z.type == 4 else 1.0
		for i in 4:
			var yy: float = [1.25, 0.95, 0.55, 0.25][i]; var rr: float = [0.26, 0.22, 0.18, 0.16][i] * (wide if i < 2 else 1.0)
			var t2 := _ray_sphere(origin, dir, z.pos + f * (0.08 * s) + Vector3(0, yy * s, 0), rr * s)
			if t2 > 0.0 and t2 < bt - 0.05:
				best = z; bt = t2; bpart = 1 if i < 2 else 3
	if best == null:
		return {}
	var side := 0
	if bpart == 1:
		# a torso hit well off the centre line is an arm
		var hx := origin.x + dir.x * bt - best.pos.x; var hz := origin.z + dir.z * bt - best.pos.z
		var lat := hx * cos(best.yaw) - hz * sin(best.yaw)
		if absf(lat) > 0.15 * best.scale:
			bpart = 2; side = 1 if lat > 0.0 else -1
	_last_ray = {"z": best, "part": bpart, "side": side, "at": _time}
	return {"zombie": best, "t": bt, "part": bpart, "head": bpart == 0, "side": side}

## The dead within a melee arc in front of `origin` (nearest first).
func in_arc(origin: Vector3, dir: Vector3, reach: float, cos_half: float) -> Array[Zombie]:
	var out: Array[Zombie] = []
	for z in _active:
		if z.state == S_DEAD:
			continue
		var v := Vector3(z.pos.x - origin.x, 0, z.pos.z - origin.z)
		var d := v.length()
		if d > reach + 0.35 * z.scale or absf(z.pos.y + 1.0 - origin.y) > 1.6:
			continue
		if d > 0.6 and (v.x * dir.x + v.z * dir.z) / d < cos_half:
			continue
		out.append(z)
	out.sort_custom(func(a, b): return a.pos.distance_squared_to(origin) < b.pos.distance_squared_to(origin))
	return out

func nearest(p: Vector3, r: float) -> Zombie:
	var best: Zombie = null; var bd := r * r
	for z in _active:
		if z.state == S_DEAD:
			continue
		var d := z.pos.distance_squared_to(p)
		if d < bd:
			bd = d; best = z
	return best

func corpse_near(p: Vector3, r: float) -> Zombie:
	var best: Zombie = null; var bd := r * r
	for z in list:
		if z.state != S_DEAD:
			continue
		var d := z.pos.distance_squared_to(p)
		if d < bd:
			bd = d; best = z
	return best

## Hurt a zombie. opts: head (x3 damage), knock (shove strength; hard knocks stagger it or put it on the ground),
## by_player (default true), part (0 head, 1 torso, 2 arm, 3 legs; taken from the last raycast() if omitted).
## Leg hits can cripple it into a crawler, arm hits can take the arm off. Returns true if this killed it.
func damage(z: Zombie, amount: float, dir: Vector3, opts := {}) -> bool:
	if z == null or z.state == S_DEAD:
		return false
	var head := bool(opts.get("head", false))
	var knock := float(opts.get("knock", 0.5))
	var by_player := bool(opts.get("by_player", true))
	var part := 0 if head else 1
	var side := 0
	if opts.has("part"):
		part = int(opts.part); side = int(opts.get("side", 0))
	elif not _last_ray.is_empty() and _last_ray.z == z and _time - float(_last_ray.at) < 0.05:
		part = 0 if head else int(_last_ray.part); side = int(_last_ray.side)
	if part == 0:
		head = true
	_last_ray = {}
	z.hp -= amount * (3.0 if head else 1.0)
	var dl := sqrt(dir.x * dir.x + dir.z * dir.z)
	var nx := dir.x / dl if dl > 1e-5 else 0.0; var nz := dir.z / dl if dl > 1e-5 else 1.0
	var hit_side := signf(nx * cos(z.yaw) - nz * sin(z.yaw))
	var hit_fwd := 1.0 if -(nx * sin(z.yaw) + nz * cos(z.yaw)) >= -0.2 else -1.0
	var heavy := z.type == BRUTE or z.type == BREAKER
	var crippled := false
	if z.hp > 0.0 and not z.crawler and not heavy and z.type != BLOAT:
		var frac := amount / z.max_hp
		if (part == 3 and _rng.randf() < minf(0.8, frac * 2.4)) or (z.hp < z.max_hp * 0.32 and _rng.randf() < 0.3):
			crippled = true
	if z.hp > 0.0 and part == 2 and z.type != BRUTE and z.type != BREAKER and _rng.randf() < minf(0.7, amount / z.max_hp * 1.8):
		var left := side > 0 if side != 0 else _rng.randf() < 0.5
		if left and not z.arm_loss_l:
			z.arm_loss_l = true; _apply_decay(z)
		elif not left and not z.arm_loss_r:
			z.arm_loss_r = true; _apply_decay(z)
	z.knock = Vector3(nx, 0, nz) * (knock * (0.35 if heavy else 0.6 if z.type == BLOAT else 1.0))
	var hunting := z.state == S_CHASE or z.state == S_ATTACK or z.state == S_BASH
	if by_player:
		z.alerted = 8.0; z.prey = null; z.last_seen = _pp
	elif not hunting:
		z.target = Vector3(z.pos.x - nx * 14.0, z.pos.y, z.pos.z - nz * 14.0)
	z.think_t = 0.0
	z.by_player = by_player
	if z.hp <= 0.0:
		if z.type == BLOAT and not head:
			z.burst = true
		kill(z, dir, by_player, head)
		return true
	var was_running := z.vel.length() > 3.4
	var next: StringName = S_CHASE if (by_player or z.prey != null) else (z.state if hunting else S_INVESTIGATE)
	var nxt2: StringName = S_INVESTIGATE if next == S_INVESTIGATE else S_CHASE
	if crippled:
		# the legs go: it folds to the ground and drags itself on
		z.crawler = true; z.pivot = false; _apply_decay(z)
		_set_state(z, S_STAGGER); z.after = nxt2
		_play_crawl(z, 0.5, 0.4)
		return false
	if z.crawler:
		if not hunting:
			_set_state(z, nxt2)
		return false
	if z.state == S_DOWN or z.state == S_RISE:
		z.after = nxt2
		return false
	if z.state == S_DORMANT and z.lying:
		_get_up(z, nxt2, 0.1)
		return false
	var floor_it := not heavy and not z.crawler and (knock >= 2.6 or (knock > 1.5 and _rng.randf() < (0.15 if z.type == BLOAT else 0.35)) or (was_running and _rng.randf() < 0.4))
	if floor_it:
		_knock_down(z, -1 if (was_running and knock < 1.5) else (1 if hit_fwd >= 0.0 else -1), nxt2)
		return false
	if knock > 1.2 and not heavy:
		_set_state(z, S_STAGGER); z.state_t = -minf(0.9, knock * 0.15); z.after = nxt2
		_play(z, &"hit_l" if hit_side > 0.5 and absf(hit_fwd) < 2.0 and _rng.randf() < 0.4 else &"hit_r" if hit_side < -0.5 and _rng.randf() < 0.4 else (&"hit_f" if hit_fwd > 0.0 else &"hit_b"), 1.0, true)
		return false
	if not hunting:
		_set_state(z, nxt2)
	elif z.state == S_BASH and by_player:
		z.after = S_CHASE
	return false

func _knock_down(z: Zombie, face: int, after: StringName) -> void:
	_set_state(z, S_DOWN)
	z.down_face = face; z.down_stage = 0
	z.lie_t = _rng.randf_range(0.5, 1.5) * (1.0 + z.decay * 0.6); z.rise_dur = _rng.randf_range(1.5, 2.1) * (0.85 + z.decay * 0.6); z.after = after
	z.lying = false; z.want = 0.0
	_play(z, &"fall_back" if face >= 0 else &"fall_front", 1.0, true)

func _get_up(z: Zombie, after: StringName, delay: float) -> void:
	_set_state(z, S_RISE)
	z.down_stage = 0; z.lie_t = delay; z.rise_dur = _rng.randf_range(1.7, 2.5) * (0.85 + z.decay * 0.5); z.after = after
	z.think_t = minf(z.think_t, delay)

func kill(z: Zombie, dir: Vector3, by_player := true, headshot := false) -> void:
	if z.state == S_DEAD:
		return
	var fwd := sin(z.yaw) * dir.x + cos(z.yaw) * dir.z       # > 0: struck from behind
	var side := absf(dir.x * cos(z.yaw) - dir.z * sin(z.yaw))
	var lying := z.crawler or z.state == S_DOWN or (z.state == S_RISE and z.down_stage < 2) or (z.state == S_DORMANT and z.lying)
	var was_down := z.state == S_DOWN
	if lying: z.death_style = 5
	elif z.type == BLOAT or z.type == BRUTE: z.death_style = 0 if fwd > 0.0 else (1 if _rng.randf() < 0.5 else 4)
	elif side > 0.75: z.death_style = 2
	elif fwd > 0.0: z.death_style = 0 if _rng.randf() < 0.5 else 3
	else:
		var r := _rng.randf() * 10.0
		z.death_style = 1 if r < 4.0 else 4 if r < 7.0 else 0 if r < 9.0 else 2
	if z.feed_at != null:
		z.feed_at.feeders = maxi(0, z.feed_at.feeders - 1); z.feed_at = null
	z.died_at = _game_min
	z.state = S_DEAD; z.state_t = 0.0; z.hp = 0.0; z.prey = null; z.bash_ref = null; z.want = 0.0; z.vel = Vector3.ZERO; z.moving = false
	z.by_player = by_player
	if z.death_style == 5:
		# already on the ground: it sinks back and lies still
		if z.crawler: _play(z, &"death_crawl", 1.0, true)
		elif was_down: _play(z, &"lie_back" if z.down_face >= 0 else &"lie_front", 1.0, true)
		else: _play(z, &"lie_back0" if z.down_face >= 0 else &"lie_front0", 1.0, true)
	else:
		_play(z, StringName("death_%d" % z.death_style), 1.0, true)
	if by_player:
		kills += 1
	_drop_active(z)
	killed.emit(z, headshot)
	if z.burst:
		_rupture(z)
	# cap corpses
	var corpses := 0; var oldest: Zombie = null
	for q in list:
		if q.state == S_DEAD:
			corpses += 1
			if oldest == null or q.died_at < oldest.died_at: oldest = q
	if corpses > 70 and oldest != null and oldest != z:
		remove(oldest)

## a bloat going off: gore and gas over everything close
func _rupture(z: Zombie) -> void:
	var R := 4.6
	z.burst = false
	if z.body >= 0:
		bodies.set_state(z.body, {"burst": true})
	noise(z.pos, 42.0)
	var hits: Array[Zombie] = []
	for o in _active:
		if o != z and o.state != S_DEAD and not o.frozen and o.pos.distance_squared_to(z.pos) < R * R:
			hits.append(o)
	for o in hits:
		var d := o.pos.distance_to(z.pos); var k := 1.0 - d / R
		damage(o, 70.0 * k + 10.0, Vector3(o.pos.x - z.pos.x, 0, o.pos.z - z.pos.z), {"knock": 3.2 * k, "by_player": false, "part": 1})
	# it eats at walls and doors too
	var seen := []
	for i in range(-3, 4):
		for j in range(-3, 4):
			if i * i + j * j > 10 or _nav_at(z.pos.x + i, z.pos.z + j) == 0:
				continue
			var ref = _ref_at(z.pos.x + i, z.pos.z + j)
			if ref != null and not seen.has(ref):
				seen.append(ref)
				_hit_structure(ref, 55.0)
	burst.emit(z.pos, R, z)

func _hit_structure(ref, dmg: float) -> void:
	structure_hit.emit(ref, dmg)
	if on_structure_hit.is_valid():
		on_structure_hit.call(ref, dmg)

## take a zombie off the thinking list (put off while that list is being walked)
func _drop_active(z: Zombie) -> void:
	if _walking:
		z.gone = true; _gone = true
	else:
		_active.erase(z)

func remove(z: Zombie) -> void:
	if z.body >= 0:
		bodies.remove_body(z.body); z.body = -1
	if z.feed_at != null:
		z.feed_at.feeders = maxi(0, z.feed_at.feeders - 1)
	list.erase(z)
	_drop_active(z)

func clear() -> void:
	for z in list:
		if z.body >= 0:
			bodies.remove_body(z.body)
	list.clear(); _active.clear(); carrion.clear(); _packs.clear(); hordes.clear(); _noise.clear()

# ------------------------------------------------------------------ state & animation plumbing

func _set_state(z: Zombie, s: StringName) -> void:
	if z.state == s:
		return
	if z.state == S_FEED and z.feed_at != null:
		z.feed_at.feeders = maxi(0, z.feed_at.feeders - 1); z.feed_at = null
	# (the next thought comes soon, but a crowd roused by one sound does not all think in the same frame)
	z.state = s; z.state_t = 0.0; z.pivot = false; z.think_t = minf(z.think_t, _rng.randf() * 0.1)
	if s != S_BASH:
		z.bash_ref = null
	if s == S_ATTACK or s == S_BASH:
		z.atk_t = 0.0; z.struck = false; z.atk_period = float(STATS[z.type].period)
		if not z.crawler:
			_play(z, &"attack", 1.0, true)
	if s == S_IDLE:
		z.pause = (4.0 + _rng.randf_range(0.0, 6.0)) * (0.5 + 0.5 * _daylight)
		# fetched up against a wall: paw at it for a while; otherwise maybe scent the air
		var wall := not z.crawler and _nav_at(z.pos.x + sin(z.yaw) * 0.9, z.pos.z + cos(z.yaw) * 0.9) != 0
		z.idle_act = &"paw" if (wall and _rng.randf() < 0.65) else &"sniff" if _rng.randf() < 0.12 else &""
		if z.idle_act == &"paw":
			z.pause += 4.0

## ask the body for a clip (cheap when nothing changes)
func _play(z: Zombie, clip: StringName, v := 1.0, restart := false) -> void:
	if z.body < 0:
		z.anim = clip; z.anim_v = v
		return
	if not restart and z.anim == clip and absf(v - z.anim_v) < 0.08 * maxf(z.anim_v, 0.5):
		return
	z.anim = clip; z.anim_v = v
	if restart:
		bodies.set_anim(z.body, clip, {"speed": v, "restart": true, "fade": 0.18})
	else:
		bodies.set_anim(z.body, clip, {"speed": v})

func _play_crawl(z: Zombie, v: float, fade: float) -> void:
	if z.anim == &"crawl" and absf(v - z.anim_v) < 0.08 * maxf(z.anim_v, 0.1):
		return
	var was := z.anim
	z.anim = &"crawl"; z.anim_v = v
	if z.body >= 0:
		if was == &"crawl": bodies.set_anim(z.body, &"crawl", {"speed": v})
		else: bodies.set_anim(z.body, &"crawl", {"speed": v, "fade": fade})

func _make_body(z: Zombie) -> void:
	z.body = bodies.add_body(z.type, z.seed, z.look)
	if z.body < 0:
		return
	var b = bodies.get_body(z.body)
	b.gait = z.gait
	bodies.set_state(z.body, {"decay": z.decay, "arm_loss_l": z.arm_loss_l, "arm_loss_r": z.arm_loss_r})
	bodies.set_pose(z.body, z.pos, z.yaw)
	var clip := z.anim; var v := z.anim_v
	z.anim = &""
	if z.state == S_DEAD:
		# a corpse come back into view: lying as it fell
		var dc: StringName = StringName("death_%d" % z.death_style)
		if z.death_style == 5:
			dc = &"death_crawl" if z.crawler else (clip if String(clip).begins_with("lie_") else &"lie_back0")
		bodies.set_anim(z.body, dc, {"phase": 1.0, "speed": 0.0, "fade": 0.0})
		z.anim = dc
	elif clip != &"":
		# something that was already on the ground is found lying, not falling again
		var cs := String(clip)
		if cs.begins_with("drop_"): clip = StringName("lie_" + cs.substr(5))
		elif cs.begins_with("fall_"): clip = StringName("lie_" + cs.substr(5))
		_play(z, clip, v if v >= 0.0 else 1.0, true)

## start hunting: p = a Prey, or null for the player
func _engage(z: Zombie, p: Prey, tp: Vector3, quiet := false) -> void:
	var was := z.state
	z.prey = p; z.last_seen = tp; z.alerted = 6.0
	if was == S_CHASE or was == S_ATTACK:
		return
	if was == S_BASH or was == S_DOWN or was == S_RISE or was == S_STAGGER:
		z.after = S_CHASE
		if was == S_RISE and z.down_stage == 0: z.lie_t = minf(z.lie_t, 0.3)
		return
	if was == S_DORMANT and z.lying:
		_get_up(z, S_CHASE, 0.05 + _rng.randf() * 0.3)
		return
	if z.type == HOWLER and z.scream_cd <= 0.0:
		_set_state(z, S_SCREAM); z.screamed = false
		_play(z, &"scream", 1.0, true)
		return
	_set_state(z, S_CHASE)
	if not quiet:
		_alert_neighbours(z, p, tp)

func _alert_neighbours(z: Zombie, p: Prey, tp: Vector3) -> void:
	_alerts += 1
	if _alerts > 3:
		return                        # the others noticing in this same instant have roused them already
	var R := 12.0 if p != null else 18.0
	var n := 0
	for o in _active:
		if o == z or o.frozen:
			continue
		var s := o.state
		if s == S_CHASE or s == S_ATTACK or s == S_DOWN or s == S_RISE or s == S_SCREAM or s == S_DEAD:
			continue
		var d2 := o.pos.distance_squared_to(z.pos)
		if d2 > R * R:
			continue
		if s == S_DORMANT:
			if d2 > 36.0:
				continue
			if o.lying:
				_get_up(o, S_INVESTIGATE, _rng.randf() * 0.8); o.target = tp
				continue
		if p != null:
			n += 1
			if n > 4:
				break
		_set_state(o, S_INVESTIGATE); o.target = tp

## the howler's call: everything in earshot comes, and comes angry
func _howl(z: Zombie) -> void:
	var R := 46.0
	var tp := z.last_seen
	for o in _active:
		if o == z or o.state == S_DEAD or o.frozen or o.pos.distance_squared_to(z.pos) > R * R:
			continue
		o.enraged = 10.0
		var s := o.state
		if s == S_CHASE or s == S_ATTACK or s == S_BASH or s == S_DOWN or s == S_SCREAM:
			continue
		if s == S_RISE:
			o.after = S_INVESTIGATE; o.target = tp
			continue
		if s == S_DORMANT and o.lying:
			_get_up(o, S_INVESTIGATE, _rng.randf() * 1.2); o.target = tp
			continue
		_set_state(o, S_INVESTIGATE)
		o.target = Vector3(tp.x + _rng.randf_range(-4, 4), tp.y, tp.z + _rng.randf_range(-4, 4))
	howl.emit(z.pos, R, z)

# ------------------------------------------------------------------ update

## Advance the dead by one frame. host keys (all optional but player_pos):
##   player_pos, player_eye (Vector3), player_noise (m), player_visibility (0..1.4), player_alive, player_inside,
##   player_bleeding (bool), base_center (Vector3 or null), base_heat, daylight (0..1), hours (0..24), day, luck,
##   camera_pos. Results are left in `chasing`, `near_count`, `seen_count`.
func update(dt: float, dt_game_min: float, host: Dictionary) -> void:
	_time += dt
	_game_min += dt_game_min
	_nav = _W.nav
	if _W.floors.size() != _n_floors:
		_mark_floors()
	_pp = host.get("player_pos", Vector3.ZERO)
	_pe = host.get("player_eye", _pp + Vector3(0, 1.6, 0))
	_p_noise = float(host.get("player_noise", 0.0)); _p_vis = float(host.get("player_visibility", 1.0))
	_p_alive = bool(host.get("player_alive", true)); _p_inside = bool(host.get("player_inside", false)); _p_bleed = bool(host.get("player_bleeding", false))
	_daylight = float(host.get("daylight", 1.0)); _night = 1.0 - _daylight; _hours = float(host.get("hours", 12.0)); _day = int(host.get("day", 1))
	_base_center = host.get("base_center", null); _base_heat = float(host.get("base_heat", 0.0))
	_cam = host.get("camera_pos", _pe)
	if bodies.night != _night:
		bodies.night = _night
	# the flow field toward the player: only while someone is using it
	if _f_building:
		_field_step(200)
	elif chasing > 0:
		_f_wait -= dt
		if _f_wait <= 0.0:
			_field_begin()
	else:
		_f_valid = false
	_update_carrion(dt)
	# sounds: the dormant are harder to rouse
	if not _noise.is_empty():
		for ev in _noise:
			var ep: Vector3 = ev[0]; var er: float = ev[1]
			for z in _active:
				var s := z.state
				if z.frozen or s == S_CHASE or s == S_ATTACK or s == S_DOWN or s == S_SCREAM or s == S_STAGGER:
					continue
				var r := er * 0.55 if s == S_DORMANT else er * 0.7 if s == S_FEED else er
				var dx := z.pos.x - ep.x; var dz := z.pos.z - ep.z
				if dx * dx + dz * dz >= r * r:
					continue
				z.target = Vector3(ep.x + _rng.randf_range(-3, 3), ep.y, ep.z + _rng.randf_range(-3, 3)); z.alerted = maxf(z.alerted, 2.0)
				if s == S_RISE:
					z.after = S_INVESTIGATE
				elif s == S_DORMANT and z.lying:
					_get_up(z, S_INVESTIGATE, _rng.randf() * 1.5)
				else:
					_set_state(z, S_INVESTIGATE)
		_noise.clear()
	# separation grid, refreshed a few times a second
	_grid_t -= dt
	if _grid_t <= 0.0:
		_grid_t = 0.12
		_grid.clear()
		# ... and how many are already on each of the living
		for p in prey_list:
			p.hunters = 0
		for z in _active:
			if z.frozen:
				continue
			if z.prey != null and (z.state == S_CHASE or z.state == S_ATTACK):
				z.prey.hunters += 1
			var k := (int(floor(z.pos.x)) + 1000) * 4096 + int(floor(z.pos.z)) + 1000
			var cell = _grid.get(k)
			if cell == null: _grid[k] = [z]
			else: cell.append(z)

	_c_chase = 0; _c_seen = 0
	var near := 0
	var px := _pp.x; var pz := _pp.z
	var damp := exp(-dt * 6.0); var ease_y := minf(1.0, dt * 12.0)
	_made = 0; _alerts = 0
	# thoughts are spread over the frames; should too many fall due at once, the rest wait a frame or two
	var budget := maxi(30, _active.size() / 5)
	_walking = true
	for z in _active:
		if z.gone:
			continue
		z.think_t -= dt
		if z.think_t <= 0.0:
			if budget > 0:
				budget -= 1
				_think(z, dt_game_min)
			else:
				z.think_t = _rng.randf() * 0.05
		if z.frozen or z.gone:
			continue
		if z.state == S_CHASE:
			if z.prey == null:
				_c_chase += 1
				if z.alerted > 0.0 and z.dp < 40.0: _c_seen += 1
		if z.dp < 25.0:
			near += 1
		# ---- walk the heading chosen at the last thought
		if z.moving:
			var want := z.want
			if want > 0.0:
				var df := wrapf(atan2(z.dir.x, z.dir.z) - z.yaw, -PI, PI)
				var ad := absf(df)
				if ad > 1.15: z.pivot = true
				elif ad < 0.35: z.pivot = false
				var stp := clampf(df, -z.turn_rate * dt, z.turn_rate * dt)
				z.yaw += stp
				var fwd := 0.0 if z.pivot else maxf(0.0, cos(df))
				var sp := want * fwd * fwd
				var acc := minf(1.0, dt * (5.0 if z.type == 1 else 3.2))
				z.vel.x += (sin(z.yaw) * sp - z.vel.x) * acc; z.vel.z += (cos(z.yaw) * sp - z.vel.z) * acc
			else:
				z.vel.x *= damp; z.vel.z *= damp
				if not is_nan(z.face):
					z.yaw += clampf(wrapf(z.face - z.yaw, -PI, PI), -z.turn_rate * 1.6 * dt, z.turn_rate * 1.6 * dt)
			var mx := (z.vel.x + z.knock.x + z.push.x) * dt; var mz := (z.vel.z + z.knock.z + z.push.z) * dt
			var nxp := z.pos.x + mx; var nzp := z.pos.z + mz
			var ix := int(floor(nxp + HALF)); var iz := int(floor(nzp + HALF))
			if ix >= 0 and iz >= 0 and ix < NAV_N and iz < NAV_N:
				z.pos.x = nxp; z.pos.z = nzp
				# beside a wall, a door or a post the collision pass cannot wait for the next thought: it runs
				# again every few hand-widths of travel, so nothing is ever walked through
				var ci := iz * NAV_N + ix
				if _nav[ci] != 0 or (_obst[ci] & 1) != 0:
					z.res_d += absf(mx) + absf(mz)
					if z.res_d > (0.15 if _nav[ci] != 0 else 0.25):
						z.res_d = 0.0
						z.pos = _W.resolve(z.pos, 0.3 * z.scale, 1.7, 0.45, "window_broken").pos
			z.knock *= damp
			z.pos.y += (z.ground - z.pos.y) * ease_y
			if z.body >= 0:
				bodies.set_pose(z.body, z.pos, z.yaw)
	_walking = false
	if _gone:
		_gone = false
		var keep: Array[Zombie] = []
		for z in _active:
			if z.gone: z.gone = false
			else: keep.append(z)
		_active = keep
	# the frozen: a few per frame are checked to see if the player has come near
	var n_all := list.size()
	if n_all > 0:
		for _i in mini(24, n_all):
			if list.is_empty():
				break
			_scan = (_scan + 1) % list.size()
			var z := list[_scan]
			if z.state == S_DEAD:
				_think_corpse(z)
			elif z.frozen:
				var dx := z.pos.x - px; var dz := z.pos.z - pz
				_rot(z)
				if dx * dx + dz * dz < FREEZE_DIST * FREEZE_DIST:
					z.frozen = false; z.think_t = 0.0; z.t_last = _time
					if not _active.has(z): _active.append(z)
	chasing = _c_chase; near_count = near; seen_count = _c_seen
	if director_enabled:
		_director(dt_game_min)

## the slow rot: fresh -> shambler -> husk over the days
func _rot(z: Zombie) -> void:
	var gm := _game_min - z.age_at
	if gm < 30.0:
		return
	z.age_at = _game_min
	z.age += gm / 1440.0
	var nd := _age_to_decay(z.age)
	if nd - z.decay > 0.012:
		z.decay = nd; _apply_decay(z)

func _think_corpse(z: Zombie) -> void:
	z.corpse_age = _game_min - z.died_at
	var dx := z.pos.x - _pp.x; var dz := z.pos.z - _pp.z
	var d2 := dx * dx + dz * dz
	if z.body < 0 and d2 < BODY_IN * BODY_IN:
		if _made < 6:
			_made += 1; _make_body(z)
	elif z.body >= 0 and d2 > BODY_OUT * BODY_OUT:
		bodies.remove_body(z.body); z.body = -1
	if z.corpse_age > 60.0 * 24.0 * 2.0:
		remove(z)

## One thought: senses, decisions, where to walk and how fast. Runs every 0.1 - 1 s per zombie.
func _think(z: Zombie, dt_game_min: float) -> void:
	var px := _pp.x; var pz := _pp.z
	var ddx := z.pos.x - px; var ddz := z.pos.z - pz
	var dp := sqrt(ddx * ddx + ddz * ddz)
	z.dp = dp
	if dp > FREEZE_DIST:
		# too far to matter: stop thinking, drop the body
		z.frozen = true; z.moving = false; z.think_t = 1.0
		if z.body >= 0:
			bodies.remove_body(z.body); z.body = -1
		_drop_active(z)
		return
	z.frozen = false
	if z.body < 0 and dp < BODY_IN:
		if _made < 6 or (dp < 30.0 and _made < 20):          # a few a frame: a crowd coming into range must not cost a hitch
			_made += 1; _make_body(z)
	elif z.body >= 0 and dp > BODY_OUT:
		bodies.remove_body(z.body); z.body = -1
	var hunting := z.state == S_CHASE or z.state == S_ATTACK or z.state == S_BASH
	# how long until the next thought (and so how long this one covers)
	var e := 0.1
	if z.state == S_ATTACK or z.state == S_BASH or z.state == S_STAGGER:
		e = 0.1 if dp < 40.0 else 0.2
	elif z.state == S_CHASE:
		e = 0.1 if dp < 14.0 else 0.14 if dp < 45.0 else 0.22
	elif z.state == S_DOWN or z.state == S_RISE or z.state == S_SCREAM:
		e = 0.15 if dp < 60.0 else 0.3
	elif z.state == S_IDLE or z.state == S_DORMANT:
		e = 0.4 if dp < 90.0 else 0.8
	else:
		e = 0.2 if dp < 35.0 else 0.3 if dp < 100.0 else 0.5
	z.think_t = e * (0.85 + 0.3 * _rng.randf())
	# ... and how long it has been since the last one: everything below advances by that much
	e = clampf(_time - z.t_last, 0.0, 1.2)
	z.t_last = _time
	var st: Dictionary = STATS[z.type]
	_rot(z)
	z.state_t += e
	z.alerted = maxf(0.0, z.alerted - e); z.enraged = maxf(0.0, z.enraged - e); z.scream_cd = maxf(0.0, z.scream_cd - e)
	z.sated = maxf(0.0, z.sated - e); z.avoid_t = maxf(0.0, z.avoid_t - e); z.ignore_t = maxf(0.0, z.ignore_t - e)
	var h := z.prey
	if h != null and not (h.alive and h.visible):
		# the quarry is gone (hidden, or dead on the ground)
		z.prey = null
		if z.state == S_CHASE or z.state == S_ATTACK:
			# brought down right here: fall on it; otherwise cast about for it
			var c: Carrion = _carrion_of(h) if (not h.alive and h.corpse and h.visible and z.type != HOWLER and z.sated <= 0.0) else null
			if c != null and c.feeders < 4 and c.pos.distance_squared_to(z.pos) < 64.0:
				_set_state(z, S_FEED); z.feed_at = c; c.feeders += 1
			else:
				_set_state(z, S_SEARCH)
			z.alerted = 0.0
			hunting = false
		h = null
	# ---- senses
	z.sense_t -= e
	if z.sense_t <= 0.0:
		var dormant := z.state == S_DORMANT; var feeding := z.state == S_FEED and z.anim == &"feed"
		z.sense_t = (0.25 + _rng.randf() * 0.35) * (2.0 if dormant else 1.0)
		if z.state != S_DOWN and z.state != S_SCREAM:
			_sense(z, dp, dormant, feeding, hunting)
		h = z.prey
		hunting = z.state == S_CHASE or z.state == S_ATTACK or z.state == S_BASH
	var T := h.pos if h != null else _pp
	var dT := dp
	if h != null:
		dT = Vector2(z.pos.x - T.x, z.pos.z - T.z).length()
	# ---- head tracking
	if z.body >= 0 and dp < 60.0:
		var track := (hunting or z.state == S_INVESTIGATE or z.state == S_SEARCH or (dp < 8.0 and z.state != S_DORMANT and z.state != S_FEED)) and (h != null or _p_alive)
		var lx := T if (hunting and h != null) else _pp
		var ld := dT if (hunting and h != null) else dp
		if track and ld < 22.0 and z.state != S_DOWN and z.state != S_RISE:
			bodies.set_look(z.body, clampf(wrapf(atan2(lx.x - z.pos.x, lx.z - z.pos.z) - z.yaw, -PI, PI), -1.2, 1.2), clampf(atan2((lx.y + 1.5) - (z.pos.y + 1.6 * z.scale), maxf(0.5, ld)), -0.6, 0.6))
		else:
			bodies.set_look(z.body, 0.0, 0.0)

	# ---- state machine
	var want := 0.0
	var face := NAN
	var clip: StringName = &"idle"
	var clip_v := 1.0
	var pace_day := 0.78 + _night * 0.4          # sluggish under the sun, restless in the dark
	var rage := 1.18 if z.enraged > 0.0 else 1.0
	z.dir = Vector3.ZERO
	match z.state:
		S_IDLE:
			if z.idle_act != &"": clip = z.idle_act
			if z.state_t > z.pause:
				var went := false
				if z.horde != 0:
					var hd := _horde(z.horde)
					if hd != null:
						_set_state(z, S_INVESTIGATE); z.target = hd.target + Vector3(_rng.randf_range(-10, 10), 0, _rng.randf_range(-10, 10)); went = true
					else:
						z.horde = 0
				if not went:
					# by day many simply stop; some lie down among the dead
					var sleepy := Bodies.hash1(z.seed * 4.7) < 0.62 and not z.crawler and z.type != 1 and z.decay > 0.2
					var at_feet: Carrion = _find_carrion(z, 6.0) if (z.sated <= 0.0 and z.type != HOWLER) else null      # a fresh kill at its feet
					if at_feet != null:
						_set_state(z, S_FEED); z.feed_at = at_feet; at_feet.feeders += 1
					elif _daylight > 0.6 and sleepy and z.enraged <= 0.0 and _rng.randf() < 0.5:
						_set_state(z, S_DORMANT); z.wake_t = _rng.randf_range(0.0, 40.0)
						z.lying = z.type == 0 and Bodies.hash1(z.seed * 9.9) < 0.3 and _nav_at(z.pos.x, z.pos.z) == 0
						if z.lying:
							z.down_face = 1 if Bodies.hash1(z.seed * 2.3) < 0.5 else -1
							_play(z, &"drop_back0" if z.down_face >= 0 else &"drop_front0", 1.0, true)
					else:
						var c: Carrion = _find_carrion(z, 14.0 + _night * 8.0) if (z.sated <= 0.0 and z.type != HOWLER) else null
						if c != null:
							_set_state(z, S_FEED); z.feed_at = c; c.feeders += 1
						else:
							_set_state(z, S_WANDER)
							# drift with the pack: loose groups share a slowly wandering anchor
							var pk: Vector3 = _pack(z)
							var a := _rng.randf_range(0.0, TAU)
							if _rng.randf() < 0.7:
								var r := _rng.randf_range(1.0, 8.0)
								z.target = Vector3(pk.x + cos(a) * r, 0, pk.z + sin(a) * r)
							else:
								var far := _rng.randf_range(6.0, 26.0) if _night > 0.5 else _rng.randf_range(3.0, 11.0)
								z.target = Vector3(z.pos.x + cos(a) * far, 0, z.pos.z + sin(a) * far)
		S_DORMANT:
			if not z.lying: clip = &"slump"
			elif z.anim == &"lie_back0" or z.anim == &"lie_front0": clip = z.anim          # found already lying (a save, a staged scene)
			else: clip = &"drop_back0" if z.down_face >= 0 else &"drop_front0"
			if _daylight < 0.4 or z.enraged > 0.0:
				z.wake_t -= e * (30.0 if z.enraged > 0.0 else 1.0)
				if z.wake_t <= 0.0:
					if z.lying: _get_up(z, S_IDLE, 0.0)
					else: _set_state(z, S_IDLE)
		S_WANDER, S_INVESTIGATE:
			var d := Vector2(z.target.x - z.pos.x, z.target.z - z.pos.z).length()
			if d < 1.2 or z.state_t > 40.0:
				if z.state == S_INVESTIGATE and z.alerted > 0.0 and z.horde == 0:
					z.last_seen = z.target; _set_state(z, S_SEARCH)
				else:
					_set_state(z, S_IDLE)
			else:
				var blk = _steer(z, z.target.x, z.target.z)
				if blk != null and z.state == S_INVESTIGATE and (z.horde != 0 or z.type == BREAKER) and _bashable(blk):
					_set_state(z, S_BASH); z.bash_ref = blk; z.after = S_INVESTIGATE
				else:
					want = z.speed * (1.25 if z.state == S_INVESTIGATE else 0.7 * pace_day) * (1.0 + _night * 0.1) * rage
					clip = &"walk"
					if z.state == S_INVESTIGATE and z.type == BREAKER and z.horde != 0:
						_seek_structure(z, e)
		S_FEED:
			var c := z.feed_at
			if c == null or c.until < _time:
				z.sated = 60.0; _set_state(z, S_IDLE)
			else:
				var d := Vector2(c.pos.x - z.pos.x, c.pos.z - z.pos.z).length()
				if d > 1.0:
					_steer(z, c.pos.x, c.pos.z); want = z.speed * 1.1; clip = &"walk"
					if z.state_t >= 30.0:
						z.sated = 30.0; _set_state(z, S_IDLE)
				else:
					clip = &"feed"; face = atan2(c.pos.x - z.pos.x, c.pos.z - z.pos.z)
					if z.state_t > 75.0 + fmod(z.seed, 40.0):
						z.sated = 120.0; _set_state(z, S_IDLE)
		S_SEARCH:
			# lost it: go to where it was last seen, cast about, then give up
			if z.state_t > 9.0 + fmod(z.seed, 5.0):
				_set_state(z, S_IDLE)
			else:
				var dl := Vector2(z.last_seen.x - z.pos.x, z.last_seen.z - z.pos.z).length()
				var leg := int(z.state_t / 2.6)
				clip = &"sniff"
				if leg % 2 == 0 or dl > 9.0:
					var a := Bodies.hash1(z.seed + leg * 3.1) * TAU
					var r := 0.0 if leg == 0 else 2.0 + Bodies.hash1(z.seed * 2.0 + leg) * 4.0
					var tx := z.last_seen.x + cos(a) * r; var tz := z.last_seen.z + sin(a) * r
					if Vector2(tx - z.pos.x, tz - z.pos.z).length() > 0.9:
						_steer(z, tx, tz); want = z.speed * 1.2; clip = &"walk"
		S_SCREAM:
			clip = &"scream"; face = atan2(z.last_seen.x - z.pos.x, z.last_seen.z - z.pos.z)
			if not z.screamed and z.state_t > 0.55:
				z.screamed = true; z.scream_cd = 24.0 + _rng.randf_range(0.0, 8.0); _howl(z)
			if z.state_t > 1.9:
				_set_state(z, S_CHASE)
		S_CHASE:
			clip = &"chase"
			if h == null and not _p_alive:
				_set_state(z, S_WANDER)
			else:
				var seen := z.alerted > 4.5            # knows exactly where it is only while it can see it
				var tgt := T if seen else z.last_seen
				var d := Vector2(tgt.x - z.pos.x, tgt.z - z.pos.z).length()
				if not seen and (d < 1.6 or z.alerted <= 0.0):
					_set_state(z, S_SEARCH)
				else:
					var blk = null
					if h == null and seen and d >= 2.2 and dp >= 3.0:
						if _flow_dir(z): blk = _flow_block
						else: blk = _steer(z, tgt.x, tgt.z)
					else:
						blk = _steer(z, tgt.x, tgt.z)
						if d < 2.2: blk = null
					if blk != null and _bashable(blk) and (h == null or d < 16.0):
						_set_state(z, S_BASH); z.bash_ref = blk; z.after = S_CHASE
					elif h != null and seen and dT < 2.2 and absf(T.y - z.pos.y) >= 1.4:
						# quarry is up on something: tear at whatever it stands on, or give up on it for a while
						var ref = null
						for i in range(-1, 2):
							for j in range(-1, 2):
								if ref == null and _nav_at(T.x + i, T.z + j) != 0:
									var r2 = _ref_at(T.x + i, T.z + j)
									if _bashable(r2): ref = r2
						if ref != null:
							_set_state(z, S_BASH); z.bash_ref = ref; z.after = S_CHASE
						else:
							z.ignore = h; z.ignore_t = 30.0; z.prey = null; z.alerted = 0.0; _set_state(z, S_SEARCH)
					else:
						want = (z.speed * 1.4 if dT > 30.0 else z.run_speed * (0.94 + _night * 0.2)) * rage
						if z.type == BREAKER: _seek_structure(z, e)
						if z.type == HOWLER and seen and z.state == S_CHASE:
							# howlers hang back and keep calling
							if z.scream_cd <= 0.0 and dT < 30.0:
								_set_state(z, S_SCREAM); z.screamed = false; z.last_seen = T
								_play(z, &"scream", 1.0, true); clip = &"scream"; want = 0.0
							elif dT < 6.0 and dT > 2.4:
								z.dir = -z.dir; want = z.run_speed * 0.8
							elif dT < 11.0 and dT >= 6.0:
								want = 0.0; face = atan2(T.x - z.pos.x, T.z - z.pos.z); clip = &"idle"
						# sprinting dead trip over their own feet now and then
						if z.state == S_CHASE and (z.type == 1 or z.decay < 0.2) and not z.crawler and z.vel.length() > 3.6 and _rng.randf() < e * 0.035:
							_knock_down(z, -1, S_CHASE); z.lie_t *= 0.5
						elif z.state == S_CHASE and seen and dT < 1.25 + 0.3 * z.scale and absf(z.pos.y - T.y) < 1.4:
							_set_state(z, S_ATTACK)
		S_ATTACK:
			clip = &"attack"
			if h == null and not _p_alive:
				_set_state(z, S_WANDER)
			else:
				face = atan2(T.x - z.pos.x, T.z - z.pos.z)
				z.atk_t += e
				if not z.struck and z.atk_t >= float(st.wind) + 0.07:
					z.struck = true
					if dT < 1.65 * z.scale + (0.3 if z.type == BREAKER else 0.0) and absf(wrapf(face - z.yaw, -PI, PI)) < 1.0:
						if h != null: human_attacked.emit(h, z, float(st.dmg) * z.dmg_mul)
						elif _p_alive: player_attacked.emit(z, float(st.dmg) * z.dmg_mul)
				if z.atk_t >= z.atk_period:
					z.atk_t -= z.atk_period; z.struck = false
					if dT > 1.9 * z.scale: _set_state(z, S_CHASE)
				elif dT > 2.6 * z.scale:
					_set_state(z, S_CHASE)
		S_BASH:
			clip = &"attack"
			var ref = z.bash_ref
			if not _bashable(ref) or z.state_t > 20.0:
				_set_state(z, S_INVESTIGATE if z.after == S_INVESTIGATE else S_CHASE)
			else:
				var rp := _ref_pos(ref, z.pos)
				face = atan2(rp.x - z.pos.x, rp.z - z.pos.z)
				z.atk_t += e
				if not z.struck and z.atk_t >= float(st.wind) + 0.07:
					z.struck = true
					_hit_structure(ref, float(st.wreck) * (z.dmg_mul if z.type <= 1 else 1.0))
				if z.after != S_INVESTIGATE and dT < 1.5 and (h != null or _p_alive):
					_set_state(z, S_ATTACK)
				elif z.atk_t >= z.atk_period:
					# one blow, then think again about whether it is still in the way
					var tg := z.target
					_set_state(z, S_INVESTIGATE if z.after == S_INVESTIGATE else S_CHASE)
					z.target = tg
		S_STAGGER:
			clip = z.anim if String(z.anim).begins_with("hit") else &"hit_f"
			if z.state_t > 0.7:
				_set_state(z, S_INVESTIGATE if z.after == S_INVESTIGATE else S_CHASE)
		S_DOWN:
			var back := z.down_face >= 0
			clip = z.anim
			if z.down_stage == 0 and z.state_t > 0.55 + z.lie_t:
				z.down_stage = 1; z.state_t = 0.0
				_play(z, &"rise_back" if back else &"rise_front", 2.0 / z.rise_dur, true); clip = z.anim
			elif z.down_stage == 1 and z.state_t > z.rise_dur:
				_set_state(z, S_INVESTIGATE if z.after == S_INVESTIGATE else S_IDLE if z.after == S_IDLE else S_CHASE)
		S_RISE:
			var back2 := z.down_face >= 0
			clip = z.anim
			if z.down_stage == 0:
				clip = &"lie_back0" if back2 else &"lie_front0"
				if z.state_t >= z.lie_t:
					z.down_stage = 1; z.state_t = 0.0
					_play(z, &"rise_back0" if back2 else &"rise_front0", 2.0 / z.rise_dur, true); clip = z.anim
			elif z.state_t > z.rise_dur:
				z.lying = false; z.down_stage = 2
				var nx: StringName = S_INVESTIGATE if z.after == S_INVESTIGATE else S_CHASE if z.after == S_CHASE else S_IDLE
				_set_state(z, nx)
				if nx == S_IDLE: z.pause = 0.6

	# ---- collisions, ground and neighbours (only for bodies that are actually moving)
	var sp_now := sqrt(z.vel.x * z.vel.x + z.vel.z * z.vel.z)
	var moved := sp_now > 0.02 or z.knock.length_squared() > 0.01 or want > 0.0
	if not (moved or z.moving):
		z.push = Vector3.ZERO
	else:
		# shoulder past the others (eased in over the frames until the next thought, so nobody pops sideways)
		var gx := int(floor(z.pos.x)); var gz := int(floor(z.pos.z))
		var my_r := (0.46 if z.type == BLOAT else 0.32) * z.scale
		var pxs := 0.0; var pzs := 0.0
		# (those standing and striking in a press only need this every other thought)
		var i0 := -1 if (z.state != S_ATTACK or (z.res_n & 1) == 0) else 2
		for i in range(i0, 2):
			var row := (gx + i + 1000) * 4096 + gz + 1000
			for j in range(-1, 2):
				var cell = _grid.get(row + j)
				if cell == null:
					continue
				for o: Zombie in cell:
					if o == z:
						continue
					var sx := z.pos.x - o.pos.x; var sz := z.pos.z - o.pos.z
					var d2 := sx * sx + sz * sz
					var rr := my_r + (0.46 if o.type == BLOAT else 0.32) * o.scale
					if d2 < rr * rr and d2 > 1e-6:
						var d := sqrt(d2); var push := (rr - d) * 0.5 / d
						pxs += sx * push; pzs += sz * push
		if pxs != 0.0 or pzs != 0.0:
			var pl := sqrt(pxs * pxs + pzs * pzs)
			var k := minf(pl, 0.35) / pl / maxf(z.think_t, 0.08)
			z.push = Vector3(pxs * k, 0.0, pzs * k)
		else:
			z.push = Vector3.ZERO
		# the living are solid too
		if dp < 0.6 and dp > 1e-4:
			z.pos.x = px + ddx / dp * 0.6; z.pos.z = pz + ddz / dp * 0.6
		if h != null and dT < 0.55 and dT > 1e-4:
			z.pos.x = T.x + (z.pos.x - T.x) / dT * 0.55; z.pos.z = T.z + (z.pos.z - T.z) / dT * 0.55
		# walls and things: the full pass only where something stands close by (and now and then regardless)
		var ix := clampi(int(floor(z.pos.x + HALF)), 1, NAV_N - 2); var iz := clampi(int(floor(z.pos.z + HALF)), 1, NAV_N - 2)
		var ci := iz * NAV_N + ix
		var ob := _obst[ci]
		z.res_n -= 1
		if (ob & 1) != 0 or z.res_n <= 0 or _nav[ci] != 0 or _nav[ci - 1] != 0 or _nav[ci + 1] != 0 or _nav[ci - NAV_N] != 0 or _nav[ci + NAV_N] != 0 \
				or _nav[ci - NAV_N - 1] != 0 or _nav[ci - NAV_N + 1] != 0 or _nav[ci + NAV_N - 1] != 0 or _nav[ci + NAV_N + 1] != 0:
			z.res_n = 4
			var res: Dictionary = _W.resolve(z.pos, 0.3 * z.scale, 1.7, 0.45, "window_broken")
			z.pos.x = res.pos.x; z.pos.z = res.pos.z
			z.ground = maxf(_W.ground_y(z.pos.x, z.pos.z, z.pos.y), minf(float(res.ground_y), z.pos.y + 0.5))
		elif (ob & 2) != 0:
			z.ground = _W.ground_y(z.pos.x, z.pos.z, z.pos.y)
		else:
			z.ground = _W.height_at(z.pos.x, z.pos.z)
		# stuck against something while wandering: give up on that spot
		if (z.state == S_WANDER or z.state == S_INVESTIGATE or z.state == S_FEED) and want > 0.0 and not z.pivot:
			if z.pos.distance_squared_to(z.last_pos) < 0.0144 * e * e:
				z.stuck_t += e
				if z.stuck_t > 2.0:
					z.stuck_t = 0.0
					if z.state == S_FEED: z.sated = 20.0
					_set_state(z, S_IDLE); want = 0.0
			else:
				z.stuck_t = 0.0
		z.last_pos = z.pos
	if z.state == S_DEAD:
		return
	z.want = want
	z.face = face
	z.turn_rate = float(st.turn) * (1.5 if hunting else 1.0) * (1.15 - z.decay * 0.4) * (0.6 if z.crawler else 1.0)
	z.moving = want > 0.0 or sp_now > 0.02 or z.knock.length_squared() > 0.01 or not is_nan(face) or z.push != Vector3.ZERO
	# ---- which clip that is
	if z.crawler:
		# prone whatever it is doing: dragging itself along, or clawing from the ground
		var busy := z.state == S_ATTACK or z.state == S_BASH or z.state == S_STAGGER
		_play_crawl(z, maxf(sp_now, 0.5 if busy else 0.012), 0.25)
		return
	elif clip == &"walk" or clip == &"chase":
		if z.pivot or sp_now < 0.08:
			clip = &"turn" if z.pivot else (&"idle" if want <= 0.0 else clip)
			clip_v = 1.0 if clip != &"walk" and clip != &"chase" else maxf(sp_now, 0.15)
		else:
			clip_v = sp_now
			if clip == &"chase" and z.type == 0 and sp_now > 3.4: clip = &"run"
			elif clip == &"chase" and sp_now < float(st.walk[1]) * 0.9 and z.type != 1: clip = &"walk"
	if z.state == S_ATTACK or z.state == S_BASH or z.state == S_DOWN or z.state == S_RISE or z.state == S_SCREAM or z.state == S_STAGGER:
		if z.state == S_RISE and z.down_stage == 0 and z.anim != clip: _play(z, clip, 1.0, false)
		return          # those states started their clip themselves
	_play(z, clip, clip_v)

## sight (cone, daylight, cover), hearing (footsteps) and smell (blood) for one zombie
func _sense(z: Zombie, dp: float, dormant: bool, feeding: bool, hunting: bool) -> void:
	var light := 0.35 + 0.65 * _daylight
	var keen := (1.3 if z.type == HOWLER else 1.0) * (1.0 - maxf(0.0, z.decay - 0.7) * 0.8) * (0.4 if dormant else 0.55 if feeding else 1.0) * (0.8 if z.crawler else 1.0)
	var cone := 3.2 if hunting else 0.8 if dormant else 0.7 if feeding else 1.1
	var close := 1.6 if dormant else 3.0
	var low := z.crawler or z.state == S_DOWN or z.state == S_RISE or (dormant and z.lying)
	var eye := Vector3(z.pos.x, z.pos.y + (0.4 if low else 1.6 * z.scale), z.pos.z)
	var cur := z.prey
	# ---- the player
	var see_p := false
	if _p_alive:
		var sight := (12.0 if _p_inside else 26.0) * light * (0.5 + _p_vis) * (1.6 if (hunting and cur == null) else 1.0) * keen * (1.2 if _p_bleed else 1.0)
		if dp < sight:
			var to_p := atan2(_pp.x - z.pos.x, _pp.z - z.pos.z)
			if (absf(wrapf(to_p - z.yaw, -PI, PI)) < cone or dp < close) and not _W.blocked(eye, _pe):
				see_p = true
	# ---- other people: the best of those in view
	var best: Prey = null; var best_s := 1e9
	if z.state != S_ATTACK and not prey_list.is_empty():
		for hp in prey_list:
			if not (hp.alive and hp.visible) or (hp == z.ignore and z.ignore_t > 0.0):
				continue
			var dx := hp.pos.x - z.pos.x; var dz := hp.pos.z - z.pos.z
			var d2 := dx * dx + dz * dz
			if d2 > 900.0:
				continue
			var d := sqrt(d2)
			var sight2 := 24.0 * light * (0.55 if hp.quiet else 1.15 if hp.move > 1.2 else 1.0) * (1.6 if (hunting and cur == hp) else 1.0) * keen
			var hears := not dormant and hp.move > 0.5 and d < 5.0
			if d > sight2 and not hears:
				continue
			if not (absf(wrapf(atan2(dx, dz) - z.yaw, -PI, PI)) < cone or d < close or hears):
				continue
			var n := hp.hunters
			if cur != hp and n >= 6 and d > 7.0:
				continue                       # enough on that one already
			var s := d * (1.0 + 0.22 * (maxi(0, n - 1) if cur == hp else n)) * (0.6 if cur == hp else 1.0)
			if s < best_s:
				best_s = s; best = hp
		if best != null and _W.blocked(eye, best.pos + Vector3(0, 1.4, 0)):
			best = null; best_s = 1e9
	# ---- decide
	var s_p := dp * 0.85 * (0.6 if (hunting and cur == null) else 1.0) if see_p else 1e9
	if see_p and s_p <= best_s:
		if not (hunting and cur != null and z.alerted > 2.0 and s_p > best_s * 0.6):
			_engage(z, null, _pp)
			return
	if best != null:
		if hunting and cur == null and z.alerted > 2.0 and not (best_s < dp * 0.5):
			if see_p:
				z.last_seen = _pp; z.alerted = 6.0
			return
		_engage(z, best, best.pos)
		return
	if see_p:
		_engage(z, null, _pp)
		return
	if hunting:
		return
	# ---- no sight: sound and smell only draw them in
	if _p_alive and z.state != S_RISE and z.state != S_STAGGER:
		var hear := dp < _p_noise * (0.55 if dormant else 0.7 if feeding else 1.0)
		var smell := _p_bleed and dp < (16.0 + _night * 8.0) * (0.7 if dormant else 1.0)
		if hear or smell:
			var j := 4.0 if (smell and not hear) else 0.0
			z.target = Vector3(_pp.x + _rng.randf_range(-j, j), _pp.y, _pp.z + _rng.randf_range(-j, j)); z.alerted = maxf(z.alerted, 2.0)
			if dormant and z.lying: _get_up(z, S_INVESTIGATE, _rng.randf())
			else: _set_state(z, S_INVESTIGATE)

# ------------------------------------------------------------------ packs, carrion, structures

func _horde(id: int) -> Horde:
	for h in hordes:
		if h.id == id:
			return h
	return null

func _pack(z: Zombie) -> Vector3:
	var id := 100000 + z.horde if z.horde != 0 else (int(floor(z.pos.x / 28.0)) + 50) * 100 + int(floor(z.pos.z / 28.0)) + 50
	var p = _packs.get(id)
	if p == null:
		p = [z.pos.x, z.pos.z, _time + _rng.randf_range(20.0, 60.0)]
		_packs[id] = p
		if _packs.size() > 600:
			_packs.erase(_packs.keys()[0])
	elif _time > p[2]:
		# the pack's centre of interest ambles on; further and more often at night
		var a := _rng.randf_range(0.0, TAU); var r := _rng.randf_range(4.0, 12.0) * (1.0 + _night * 1.6)
		p[0] = clampf(z.pos.x * 0.5 + p[0] * 0.5 + cos(a) * r, -HALF + 30.0, HALF - 30.0)
		p[1] = clampf(z.pos.z * 0.5 + p[1] * 0.5 + sin(a) * r, -HALF + 30.0, HALF - 30.0)
		p[2] = _time + _rng.randf_range(25.0, 70.0) * (1.0 - _night * 0.5)
	return Vector3(p[0], 0, p[1])

func _update_carrion(dt: float) -> void:
	_carrion_t -= dt
	if _carrion_t > 0.0:
		return
	_carrion_t = 1.0
	for i in range(carrion.size() - 1, -1, -1):
		var c := carrion[i]
		if c.prey != null:
			if c.prey.corpse and c.prey.visible and prey_list.has(c.prey): c.pos = c.prey.pos
			else: c.until = 0.0
		if c.until < _time:
			carrion.remove_at(i)
	# the bodies of people: a registered Prey that is dead and left lying
	for p in prey_list:
		if not p.alive and p.corpse and p.visible:
			_carrion_of(p)

func _carrion_of(p: Prey) -> Carrion:
	for c in carrion:
		if c.prey == p:
			return c
	var c := Carrion.new()
	c.pos = p.pos; c.until = _time + 600.0; c.prey = p
	carrion.append(c)
	return c

func _find_carrion(z: Zombie, r: float) -> Carrion:
	var best: Carrion = null; var bd := r * r
	for c in carrion:
		if c.feeders >= 4:
			continue
		var d := c.pos.distance_squared_to(z.pos)
		if d < bd:
			bd = d; best = c
	return best

## breakers go looking for something built to pull down
func _seek_structure(z: Zombie, e: float) -> void:
	z.scan_t -= e
	if z.scan_t > 0.0:
		return
	z.scan_t = 1.2
	var best = null; var bd := 1e9
	for i in range(-6, 7):
		for j in range(-6, 7):
			var v := _nav_at(z.pos.x + i, z.pos.z + j)
			if v == 0 or v == 255:
				continue
			var ref = _ref_at(z.pos.x + i, z.pos.z + j)
			if ref == null or not _bashable(ref) or not bool(_rget(ref, "built", _rget(ref, "def", null) != null)):
				continue                              # player-built things only
			var d := float(i * i + j * j)
			if d < bd:
				bd = d; best = ref
	if best == null:
		return
	if bd <= 2.5:
		var after: StringName = S_INVESTIGATE if z.state == S_INVESTIGATE else S_CHASE
		_set_state(z, S_BASH); z.bash_ref = best; z.after = after
	elif z.state == S_INVESTIGATE:
		z.target = _ref_pos(best, z.pos)

# hordes & respawn
func _director(dt_game_min: float) -> void:
	_horde_t -= dt_game_min
	_respawn_t -= dt_game_min
	if _horde_t > 0.0 and _respawn_t > 0.0:
		return
	var alive := 0
	for z in list:
		if z.state != S_DEAD: alive += 1
	var night := _hours >= 20.0 or _hours < 5.0
	if _horde_t <= 0.0:
		_horde_t = _rng.randf_range(60.0 * 5.0, 60.0 * 10.0) / (1.0 + _base_heat * 0.02)
		if (night or _base_heat > 30.0) and alive < 520:
			# a horde from the wilds heading for the player's base (or somewhere in town)
			var to_base: bool = _base_center != null and _base_heat > 10.0 and _rng.randf() < 0.4 + minf(0.5, _base_heat / 200.0)
			var target: Vector3 = _base_center if to_base else Vector3(_rng.randf_range(-120, 120), 0, _rng.randf_range(-110, 110))
			var a := _rng.randf_range(0.0, TAU)
			var cx := clampf(target.x + cos(a) * 170.0, -HALF + 40.0, HALF - 40.0); var cz := clampf(target.z + sin(a) * 170.0, -HALF + 40.0, HALF - 40.0)
			var n := int(clampf(18.0 + _day * 4.0 + _base_heat * 0.6, 18.0, 110.0))
			spawn_horde(cx, cz, n, target, to_base)
			horde_spawned.emit(n, to_base)
	if _respawn_t <= 0.0:
		_respawn_t = 30.0
		if alive < 300:
			for i in 6:
				var x := _rng.randf_range(-HALF + 40.0, HALF - 40.0); var z := _rng.randf_range(-HALF + 40.0, HALF - 40.0)
				if Vector2(x - _pp.x, z - _pp.z).length() < 120.0:
					continue
				if _base_center != null and Vector2(x - _base_center.x, z - _base_center.z).length() < 80.0:
					continue
				if _W.nav_at(x, z) != 0:
					continue
				spawn(x, z, pick_type(_day))
		for i in range(hordes.size() - 1, -1, -1):
			hordes[i].t += 30.0
			if hordes[i].t > 60.0 * 12.0:
				hordes.remove_at(i)

# ------------------------------------------------------------------ persistence

## Rows for a save file: [x, z, type, hp, decay, seed, flags, name, look] (the last two only for the turned).
func to_rows() -> Array:
	var out := []
	for z in list:
		if z.state == S_DEAD:
			continue
		var flags := (1 if z.crawler else 0) | (2 if z.arm_loss_l else 0) | (4 if z.arm_loss_r else 0) | (8 if z.turned else 0) | (16 if z.state == S_DORMANT else 0) | (32 if (z.state == S_DORMANT and z.lying) else 0)
		var row := [snappedf(z.pos.x, 0.1), snappedf(z.pos.z, 0.1), z.type, round(z.hp), snappedf(z.decay, 0.001), snappedf(z.seed, 0.001), flags]
		if z.turned or z.name != "":
			row.append(z.name)
			row.append(z.look)
		out.append(row)
	return out

## Accepts rows from to_rows() and the web game's old saves ([x, z, type, hp]).
func load_rows(rows: Array) -> void:
	clear()
	for a in rows:
		if not (a is Array) or a.size() < 3:
			continue
		var old: bool = a.size() < 6
		var opts := {}
		if not old:
			opts = {"decay": float(a[4]), "seed": float(a[5])}
			if a.size() > 7 and a[7] is String and a[7] != "": opts["name"] = a[7]
			if a.size() > 8 and a[8] is Dictionary: opts["look"] = a[8]
		var z := spawn(float(a[0]), float(a[1]), int(a[2]), 0, opts)
		if z == null:
			continue
		if not old:
			var f := int(a[6])
			z.arm_loss_l = (f & 2) != 0; z.arm_loss_r = (f & 4) != 0
			z.crawler = (f & 1) != 0; z.turned = (f & 8) != 0
			_apply_decay(z, true)
			if f & 16:
				z.state = S_DORMANT; z.wake_t = _rng.randf_range(0.0, 40.0)
				if f & 32:
					z.lying = true; z.down_face = 1 if Bodies.hash1(z.seed * 2.3) < 0.5 else -1
					z.anim = &"lie_back0" if z.down_face >= 0 else &"lie_front0"
		z.hp = minf(z.max_hp, maxf(1.0, float(a[3]) if a.size() > 3 else z.max_hp))
