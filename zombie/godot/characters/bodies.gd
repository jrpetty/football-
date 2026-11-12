class_name Bodies
extends Node3D
## Every zombie and person in the game, drawn as one MultiMesh.
##
## The body mesh, its baked animation clips and its shader are exported from the web version
## (zombie/scripts/godot-export/characters.mjs). Nothing is posed on the CPU: each body owns one row of a small
## data texture that says how it is built, what it wears and which clip it is playing; the shader does the rest.
## Per frame this node only advances a clock, so cost does not grow with the number of bodies.
##
##   var id := bodies.add_body(Bodies.KIND_HUMAN, 12.5, {"shirt": 0x3f6b8a})
##   bodies.set_pose(id, pos, yaw)
##   bodies.set_anim(id, &"walk", {"speed": 1.4})      # looping gaits take a ground speed: feet do not slide
##   bodies.set_anim(id, &"attack", {"restart": true})  # see clip_names(id) for what a body can play
##   bodies.set_state(id, {"decay": 0.8, "arm_loss_l": true})

const KIND_WALKER := 0
const KIND_RUNNER := 1
const KIND_BRUTE := 2
const KIND_HOWLER := 3
const KIND_BLOAT := 4
const KIND_BREAKER := 5
const KIND_HUMAN := 10
const TOOL := {"none": 0, "gun": 1, "axe": 2, "hoe": 3, "wrench": 4, "bat": 5, "medbag": 6, "ladle": 7}
const OUTFIT := {"plain": 0, "jacket": 1, "vest": 2, "rolled": 3}
## how tall each kind stands relative to its look's height
const KIND_SCALE := {0: 1.0, 1: 1.0, 2: 1.24, 3: 1.13, 4: 0.98, 5: 1.08, 10: 1.0}

const DATA_DIR := "res://characters/data/"
const TEX_W := 36            # texels per body row: 10 look, 18 bone scales, 6 animation, 2 spare
const SCALE0 := 10
const ANIM0 := 28
const NB := 18
const SHX := 0.168
const HPX := 0.088

const FEAT := {"hair": 1, "hairLong": 2, "beanie": 3, "bun": 4, "beard": 5, "foreL": 6, "foreR": 7, "stumpL": 8, "stumpR": 9, "tool0": 9,
	"bloat": 17, "hump": 18, "club": 19, "throat": 20, "brim": 21, "jacket": 22, "pack": 23, "belt": 24, "paunch": 25}
const H_SKIN := [0xf0c9a5, 0xe4b48c, 0xd29b72, 0xb97f55, 0x94603c, 0x6f4429, 0x513220]
const H_HAIR := [0x1c1611, 0x2e2118, 0x4a3220, 0x6b4a2a, 0x8f6a3a, 0xb8925a, 0x7a2f1a, 0x9a9690, 0xc4c0b8]
const H_PANTS := [0x2f3b55, 0x3a4660, 0x4a4234, 0x2a2a2a, 0x5c5340, 0x39473a, 0x4f4f57]
const H_SHIRT := [0x8a3f33, 0x3f6b8a, 0x5b7a4a, 0xb89a4a, 0x7a5a8a, 0x8a8a84, 0x3a5a5a, 0xb86a3a, 0x4a4a6a, 0xa85a6a, 0xc9c2b0, 0x2f2f33]
const HAIR_W := [0, 0, 0, 1, 2, 2, 3, 4, 5, 5]
const J_COL := [0x3a3f2e, 0x4a3828, 0x2a3442, 0x55524a, 0x6a4a2a, 0x1f1f22, 0x6a2f25, 0x35453a]
const BOOT_COL := [0x2a2420, 0x1a1a1c, 0x4a3a28, 0x33302c]
const KIT_COL := [0x4a5a3a, 0x2a3a5a, 0x5a4a3a, 0x4a4a4a, 0x7a2a2a, 0x8a6a3a]
const DECAY_TINT := [[0.74, 0.72, 0.64], [0.56, 0.61, 0.5], [0.4, 0.36, 0.3]]
const TYPE_TINT := {1: [0.66, 0.5, 0.47, 0.3], 2: [0.5, 0.4, 0.37, 0.5], 3: [0.8, 0.83, 0.86, 0.8], 4: [0.7, 0.72, 0.4, 0.7], 5: [0.55, 0.5, 0.6, 0.55]}
## resting stances a person with empty hands drifts between when told to play "idle"
const STANCES := [&"idle", &"idle_hips", &"idle_cross", &"idle_belt", &"idle_onehip", &"idle", &"idle_belt", &"idle_cross", &"idle_hipr", &"idle"]

## bodies beyond this many are refused (the data texture has one row each)
@export var max_bodies := 768
## 0 by day .. 1 at night (dead eyes and gas sacs glow in the dark)
@export_range(0.0, 1.0) var night := 0.0: set = set_night
## overall albedo scale; tune once to the world's lighting
@export var albedo_gain := 0.8: set = set_albedo_gain
## soft bounce light on everybody, so shaded faces never fall to black
@export var fill_light := 0.1: set = set_fill_light

class Body:
	var kind := 0
	var human := false
	var seed := 0.0
	var skin := 0; var shirt := 0; var pants := 0; var hair := 0
	var build := 1.0; var height := 1.0; var hair_style := 0; var outfit := 0; var jacket := 0; var gear := 0
	var decay := 0.0; var sick := 0.0; var tool := 0; var gait := 0
	var arm_loss_l := false; var arm_loss_r := false; var burst := false
	var rig := ""
	var scale := 1.0
	var shape := PackedFloat32Array()
	var look := PackedFloat32Array()
	# animation
	var clip := &""; var asked := &""
	var n := 1.0; var rate := 0.0; var t0 := 0.0; var f0 := 0.0; var loop := true
	var auto_idle := false; var next_stance := 0.0
	var look_yaw := 0.0; var look_pitch := 0.0
	var pos := Vector3.ZERO; var yaw := 0.0; var placed := false
	# the slope it lies on: the body's up axis leans from n_from to n_to over n_dur seconds
	var normal := Vector3.UP; var n_from := Vector3.UP; var n_to := Vector3.UP; var n_t := 0.0; var n_dur := 0.0

var multimesh: MultiMesh
var material: ShaderMaterial
var _mmi: MultiMeshInstance3D
var _meta := {}
var _rigs := {}
var _img: Image
var _tex: ImageTexture
var _dirty := false
var _bodies: Array[Body] = []
var _free: Array[int] = []
var _hi := 0
var _time := 0.0
var _stance_t := 0.0
var _tilting := {}

func _ready() -> void:
	_load()

func _process(delta: float) -> void:
	_time += delta
	if _time > 3600.0:
		_rebase()
	material.set_shader_parameter(&"u_time", _time)
	_stance_t -= delta
	if _stance_t <= 0.0:
		_stance_t = 0.5
		_drift_stances()
	if _dirty:
		_tex.update(_img)
		_dirty = false
	if not _tilting.is_empty():
		for id in _tilting.keys():
			var b: Body = _bodies[id] if id < _bodies.size() else null
			if b == null:
				_tilting.erase(id); continue
			b.n_t += delta
			var k := 1.0 if b.n_dur <= 0.0 else clampf(b.n_t / b.n_dur, 0.0, 1.0)
			k = k * k * (3.0 - 2.0 * k)
			b.normal = b.n_from.lerp(b.n_to, k).normalized()
			if k >= 1.0:
				b.normal = b.n_to; _tilting.erase(id)
			_place(id, b)

# ------------------------------------------------------------------ public API

## Add a body. `kind` is one of the KIND_* constants; `look` may hold any of skin, shirt, pants, hair (0xRRGGBB),
## build (0.85 slight .. 1.25 heavy), height (0.9 .. 1.1), hairStyle (0 short, 1 bald, 2 long, 3 beanie / cap,
## 4 tied back, 5 beard), outfit (0 shirt, 1 open jacket, 2 gilet, 3 rolled sleeves), jacket (colour), gear
## (bit 1 backpack, bit 2 tool belt). Whatever is missing is chosen from the seed. Returns the body id, or -1.
func add_body(kind: int, seed: float, look: Dictionary = {}) -> int:
	if multimesh == null:
		_load()
	var id := -1
	if _free.size() > 0:
		id = _free.pop_back()
	elif _bodies.size() < max_bodies:
		id = _bodies.size()
		_bodies.append(null)
	else:
		push_warning("Bodies: max_bodies reached")
		return -1
	var b := Body.new()
	b.kind = kind; b.human = kind == KIND_HUMAN; b.seed = seed
	var base := look_from_seed(seed)
	base.merge(look, true)
	b.skin = int(base.skin); b.shirt = int(base.shirt); b.pants = int(base.pants); b.hair = int(base.hair)
	b.build = clampf(float(base.build), 0.7, 1.4); b.height = clampf(float(base.height), 0.8, 1.25); b.hair_style = int(base.hairStyle)
	var ho := hash1(seed * 41.3)
	b.outfit = int(look.outfit) if look.has("outfit") else (1 if ho < 0.22 else 2 if ho < 0.32 else 3 if ho < 0.46 else 0)
	b.jacket = int(look.jacket) if look.has("jacket") else J_COL[int(floor(hash1(seed * 42.7) * J_COL.size())) % J_COL.size()]
	b.gear = int(look.gear) if look.has("gear") else ((1 if hash1(seed * 43.1) < 0.18 else 0) | (2 if hash1(seed * 44.7) < 0.15 else 0))
	b.gait = gait_for(kind, seed)
	b.scale = b.height * float(KIND_SCALE.get(kind, 1.0))
	_bodies[id] = b
	if id >= multimesh.instance_count:
		_grow()
	_hi = maxi(_hi, id + 1)
	multimesh.visible_instance_count = _hi
	multimesh.set_instance_custom_data(id, Color(float(id), seed, 0.0, 0.0))
	multimesh.set_instance_transform(id, Transform3D(Basis.from_scale(Vector3.ZERO), Vector3.ZERO))
	_refresh(id)
	b.clip = &""
	set_anim(id, &"idle", {"fade": 0.0, "phase": hash1(seed * 7.9)})
	return id

func remove_body(id: int) -> void:
	if not _valid(id):
		return
	_bodies[id] = null
	_tilting.erase(id)
	_free.append(id)
	multimesh.set_instance_transform(id, Transform3D(Basis.from_scale(Vector3.ZERO), Vector3.ZERO))
	while _hi > 0 and _bodies[_hi - 1] == null:
		_hi -= 1
	multimesh.visible_instance_count = _hi

func has_body(id: int) -> bool:
	return _valid(id)

func body_count() -> int:
	return _bodies.size() - _free.size()

## Place a body: feet at `pos`, facing (sin yaw, 0, cos yaw).
func set_pose(id: int, pos: Vector3, yaw: float) -> void:
	var b := _bodies[id]
	if b.placed and b.yaw == yaw and b.pos == pos:
		return
	b.pos = pos; b.yaw = yaw; b.placed = true
	_place(id, b)

func _place(id: int, b: Body) -> void:
	var s := b.scale
	var c := cos(b.yaw) * s
	var sn := sin(b.yaw) * s
	if b.normal.y > 0.9999:
		multimesh.set_instance_transform(id, Transform3D(Vector3(c, 0.0, -sn), Vector3(0.0, s, 0.0), Vector3(sn, 0.0, c), b.pos))
	else:
		# lean the whole body so that its up axis is the ground's normal (it keeps facing the same way)
		var lean := Basis(Quaternion(Vector3.UP, b.normal))
		multimesh.set_instance_transform(id, Transform3D(lean * Basis(Vector3(c, 0.0, -sn), Vector3(0.0, s, 0.0), Vector3(sn, 0.0, c)), b.pos))

## Lay a body along a slope: its up axis eases to `normal` over `seconds` (Vector3.UP stands it straight again).
## For bodies on the ground — the dead, the knocked down, crawlers — so they neither float over a hillside nor
## sink into it.
func set_tilt(id: int, normal: Vector3, seconds := 0.4) -> void:
	var b := _bodies[id]
	if b.n_to.distance_squared_to(normal) < 1e-5 and (seconds > 0.0 or b.normal.distance_squared_to(normal) < 1e-5):
		return
	b.n_from = b.normal; b.n_to = normal; b.n_t = 0.0; b.n_dur = seconds
	if seconds <= 0.0:
		b.normal = normal
		_tilting.erase(id)
		if b.placed: _place(id, b)
	else:
		_tilting[id] = true

## Play a clip. Options (all optional):
##   speed       gaits ("walk", "chase", "run", "crawl", ...): ground speed in m/s, so the feet match the ground;
##               other clips: playback rate multiplier (default 1)
##   phase       0..1 position to start from
##   fade        cross-fade seconds from what was playing (default 0.25)
##   restart     true to start over even if this clip is already playing
##   loop        override the clip's own looping flag
##   look_yaw, look_pitch   where the head turns, radians relative to the body
##   stances     people only, "idle": false keeps the plain idle instead of drifting between resting stances
func set_anim(id: int, clip: StringName, opts: Dictionary = {}) -> void:
	var b := _bodies[id]
	if opts.has("look_yaw") or opts.has("look_pitch"):
		b.look_yaw = float(opts.get("look_yaw", b.look_yaw)); b.look_pitch = float(opts.get("look_pitch", b.look_pitch))
	b.auto_idle = b.human and clip == &"idle" and bool(opts.get("stances", true)) and b.rig == "h0" and b.sick < 0.5
	var name := _resolve(b, clip)
	var info: Dictionary = _rigs[b.rig].clips[name]
	var n := float(info.n)
	var rate := float(info.fps) * float(opts.get("speed", 1.0))
	if info.has("stride"):
		# one cycle covers `stride` metres: frames per second = cycles per second * frames
		rate = n * float(opts.get("speed", 0.0)) / (float(info.stride) * b.scale)
	if b.asked == clip and b.clip == name and not bool(opts.get("restart", false)):
		if absf(rate - b.rate) > 0.02 * maxf(absf(b.rate), 1.0):
			b.f0 = _frame(b); b.t0 = _time; b.rate = rate
			_write_a(id, b)
		elif opts.has("look_yaw") or opts.has("look_pitch"):
			_write_fade(id, b, -1.0, 0.0)
		return
	var fade := float(opts.get("fade", 0.25))
	var had := b.clip != &""
	if had and fade > 0.0:
		# what was playing keeps running in the second slot while it fades out
		for k in 2:
			_img.set_pixel(ANIM0 + 2 + k, id, _img.get_pixel(ANIM0 + k, id))
	b.asked = clip; b.clip = name
	b.n = n; b.rate = rate; b.loop = bool(opts.get("loop", info.loop))
	b.t0 = _time; b.f0 = float(opts.get("phase", 0.0)) * (n if b.loop else n - 1.0)
	_img.set_pixel(ANIM0, id, Color(float(info.x), float(info.y), n, 0.0))
	_write_a(id, b)
	_write_fade(id, b, _time, fade if had else 0.0)
	if b.auto_idle:
		b.next_stance = _time + 4.0 + hash1(b.seed * 3.3 + _time) * 10.0

## Turn the head (radians relative to the body) without touching the animation.
func set_look(id: int, yaw: float, pitch: float = 0.0) -> void:
	var b := _bodies[id]
	if absf(yaw - b.look_yaw) < 0.02 and absf(pitch - b.look_pitch) < 0.02:
		return
	b.look_yaw = yaw; b.look_pitch = pitch
	_write_fade(id, b, -1.0, 0.0)

## Change what a body is: decay (0 fresh .. 1 husk), sick (people, 0..1), tool (TOOL.*), arm_loss_l, arm_loss_r,
## burst (a bloat that has gone off), and any look key (skin, shirt, pants, hair, hairStyle, outfit, jacket, gear).
func set_state(id: int, state: Dictionary) -> void:
	var b := _bodies[id]
	var was_rig := b.rig
	for k in state:
		match k:
			"decay": b.decay = clampf(float(state[k]), 0.0, 1.0)
			"sick": b.sick = clampf(float(state[k]), 0.0, 1.0)
			"tool": b.tool = int(state[k])
			"arm_loss_l": b.arm_loss_l = bool(state[k])
			"arm_loss_r": b.arm_loss_r = bool(state[k])
			"burst": b.burst = bool(state[k])
			"skin": b.skin = int(state[k])
			"shirt": b.shirt = int(state[k])
			"pants": b.pants = int(state[k])
			"hair": b.hair = int(state[k])
			"hairStyle": b.hair_style = int(state[k])
			"outfit": b.outfit = int(state[k])
			"jacket": b.jacket = int(state[k])
			"gear": b.gear = int(state[k])
	_refresh(id)
	if b.rig != was_rig and b.asked != &"":
		# a different tool is carried differently: carry on with the same clip in the new set
		var asked := b.asked
		var ph := _frame(b) / maxf(b.n, 1.0)
		b.asked = &""
		set_anim(id, asked, {"phase": ph, "fade": 0.25})

## True once a one-shot clip has reached its last frame (always false for loops).
func anim_done(id: int) -> bool:
	var b := _bodies[id]
	return not b.loop and b.f0 + (_time - b.t0) * b.rate >= b.n - 1.0

## Seconds a clip takes at normal speed for this body (one cycle for loops; gaits: at their baked pace).
func clip_length(id: int, clip: StringName) -> float:
	var info := clip_info(id, clip)
	if info.is_empty():
		return 0.0
	return (float(info.n) if info.loop else float(info.n) - 1.0) / float(info.fps)

## The exporter's record for a clip: n, fps, loop, and for gaits `stride` (metres per cycle, before body scale),
## for attacks `hit` (seconds from the start of a cycle to the blow landing).
func clip_info(id: int, clip: StringName) -> Dictionary:
	var b := _bodies[id]
	var c: Dictionary = _rigs[b.rig].clips
	return c.get(String(clip), {})

func clip_names(id: int) -> PackedStringArray:
	return PackedStringArray(_rigs[_bodies[id].rig].clips.keys())

func has_clip(id: int, clip: StringName) -> bool:
	return _rigs[_bodies[id].rig].clips.has(String(clip))

func current_clip(id: int) -> StringName:
	return _bodies[id].asked

## The 36 texels that describe a body to the shader (look, bone scales, animation), for anything that draws the
## body mesh on its own account with the same material — the first-person arms do.
func row_pixels(id: int) -> Array:
	var out: Array = []
	for k in TEX_W: out.append(_img.get_pixel(k, id))
	return out

## Uniform scale the body is drawn at (height x kind).
func body_scale(id: int) -> float:
	return _bodies[id].scale

func get_body(id: int) -> Body:
	return _bodies[id]

func set_night(v: float) -> void:
	night = v
	if material:
		material.set_shader_parameter(&"night", v)

func set_albedo_gain(v: float) -> void:
	albedo_gain = v
	if material:
		material.set_shader_parameter(&"albedo_gain", v)

func set_fill_light(v: float) -> void:
	fill_light = v
	if material:
		material.set_shader_parameter(&"fill_light", v)

# ------------------------------------------------------------------ looks (a port of bodyRig.ts makeDef / refreshDef)

static func hash1(n: float) -> float:
	var v := sin(n * 127.1 + 311.7) * 43758.5453
	return v - floor(v)

static func _sst(a: float, b: float, x: float) -> float:
	var t := 0.0 if x <= a else 1.0 if x >= b else (x - a) / (b - a)
	return t * t * (3.0 - 2.0 * t)

static func _lin(hex: int) -> Array:
	return [pow(float((hex >> 16) & 255) / 255.0, 2.2), pow(float((hex >> 8) & 255) / 255.0, 2.2), pow(float(hex & 255) / 255.0, 2.2)]

static func _pick(a: Array, r: float) -> int:
	return a[int(floor(r * a.size())) % a.size()]

## The stable appearance a seed implies (the same people.ts / bodyRig.ts would give it).
static func look_from_seed(seed: float) -> Dictionary:
	var r := []
	for k in 8:
		r.append(hash1(seed * 3.7 + float(k) * 17.13 + 0.5))
	var b: float = r[0]
	var si := int(floor(r[1] * H_SKIN.size())) % H_SKIN.size()
	var hr: float = r[2]
	var hi := 0
	if si >= 3:
		hi = int(floor(hr / 0.82 * 3.0)) if hr < 0.82 else 7 + int(floor((hr - 0.82) / 0.18 * 2.0))
	else:
		hi = int(floor(hr * H_HAIR.size()))
	return {"skin": H_SKIN[si], "shirt": _pick(H_SHIRT, r[3]), "pants": _pick(H_PANTS, r[4]), "hair": H_HAIR[mini(hi, H_HAIR.size() - 1)],
		"build": 0.85 + b * b * 0.4 + r[5] * 0.08, "height": 0.92 + r[6] * 0.16, "hairStyle": HAIR_W[int(floor(r[7] * HAIR_W.size())) % HAIR_W.size()]}

## A random living person's look from any float generator (Callable returning 0..1).
static func random_look(next: Callable) -> Dictionary:
	var r := []
	for k in 8:
		r.append(float(next.call()))
	var b: float = r[0]
	var si := int(floor(r[1] * H_SKIN.size())) % H_SKIN.size()
	var hr: float = r[2]
	var hi := int(floor(hr * H_HAIR.size()))
	if si >= 3:
		hi = int(floor(hr / 0.82 * 3.0)) if hr < 0.82 else 7 + int(floor((hr - 0.82) / 0.18 * 2.0))
	return {"skin": H_SKIN[si], "shirt": _pick(H_SHIRT, r[3]), "pants": _pick(H_PANTS, r[4]), "hair": H_HAIR[mini(hi, H_HAIR.size() - 1)],
		"build": 0.85 + b * b * 0.4 + r[5] * 0.08, "height": 0.92 + r[6] * 0.16, "hairStyle": HAIR_W[int(floor(r[7] * HAIR_W.size())) % HAIR_W.size()]}

static func gait_for(kind: int, seed: float) -> int:
	match kind:
		KIND_RUNNER: return 5
		KIND_BRUTE: return 6
		KIND_HOWLER: return 7
		KIND_BLOAT: return 8
		KIND_BREAKER: return 9
	var h := hash1(seed * 6.13)
	return 0 if h < 0.34 else 1 if h < 0.54 else 2 if h < 0.7 else 3 if h < 0.84 else 4

static func _set_s(S: PackedFloat32Array, b: int, x: float, y: float, z: float) -> void:
	S[b * 3] = x; S[b * 3 + 1] = y; S[b * 3 + 2] = z

## Bone scales (silhouette) and the 40-float look block for a body, exactly as the web version computes them.
static func compute_def(b: Body) -> void:
	var S := PackedFloat32Array(); S.resize(NB * 3)
	var K := b.kind
	var sd := b.seed
	var h1 := hash1(sd * 2.17); var h2 := hash1(sd * 4.91); var h3 := hash1(sd * 7.77); var h4 := hash1(sd * 9.3)
	var husk := 0.0 if b.human else _sst(0.6, 1.0, b.decay)
	var bw := b.build * (1.0 - husk * 0.14)
	var limb := pow(bw, 0.8) * (0.97 + h2 * 0.07)
	var sh := 0.97 + h1 * 0.08
	var heavy := maxf(0.0, bw - 1.04)
	var jacketed := 1.07 if b.outfit == 1 else 1.0
	for i in NB:
		_set_s(S, i, 1.0, 1.0, 1.0)
	_set_s(S, 0, bw * (0.98 + h3 * 0.05), 1.0, 0.92 + bw * 0.08 + heavy * 0.5)
	_set_s(S, 1, bw * (1.0 + heavy * 0.5), 1.0, bw * (1.0 + heavy * 2.4))
	_set_s(S, 2, (0.6 + bw * 0.4) * sh, 1.0, 0.9 + bw * 0.1 + heavy * 0.3)
	_set_s(S, 3, (0.97 + h4 * 0.06) * (1.0 + heavy * 0.25), 0.97 + h1 * 0.06, 0.97 + h2 * 0.06)
	if b.human:
		S[9] *= 1.06; S[10] *= 1.06; S[11] *= 1.06
		_set_s(S, 4, S[9], S[10], S[11])
	for i in [5, 8, 6, 9]:
		_set_s(S, i, limb * jacketed, 1.0, limb * jacketed)
	for i in [11, 12, 14, 15]:
		_set_s(S, i, limb * (1.0 + heavy * 0.4), 1.0, limb * (1.0 + heavy * 0.4))
	var paunch := 0.0
	_set_s(S, 17, 0.0, 0.0, 0.0)
	if K == KIND_RUNNER:
		_set_s(S, 1, bw * 0.92, 1.0, bw * 0.92); _set_s(S, 2, (0.6 + bw * 0.4) * sh * 0.96, 1.0, 0.94)
		for i in [5, 6, 8, 9, 11, 12, 14, 15]:
			_set_s(S, i, limb * 0.92, 1.0, limb * 0.92)
	elif K == KIND_BRUTE:
		_set_s(S, 0, 1.3, 1.05, 1.3); _set_s(S, 1, 1.45, 1.1, 1.5); _set_s(S, 2, 1.55, 1.16, 1.5); _set_s(S, 3, 1.05, 1.0, 1.06); _set_s(S, 4, 1.2, 1.15, 1.12)
		_set_s(S, 5, 1.75, 1.14, 1.75); _set_s(S, 8, 1.75, 1.14, 1.75); _set_s(S, 6, 1.85, 1.16, 1.85); _set_s(S, 9, 1.85, 1.16, 1.85); _set_s(S, 7, 1.6, 1.35, 1.6); _set_s(S, 10, 1.6, 1.35, 1.6)
		for i in [11, 14, 12, 15]:
			_set_s(S, i, 1.4, 0.98, 1.4)
		_set_s(S, 13, 1.3, 1.2, 1.2); _set_s(S, 16, 1.3, 1.2, 1.2)
		paunch = 0.9; _set_s(S, 17, 1.2, 0.95, 0.9)
	elif K == KIND_HOWLER:
		_set_s(S, 0, 0.84, 1.0, 0.84); _set_s(S, 1, 0.78, 1.1, 0.76); _set_s(S, 2, 0.9, 1.08, 0.82); _set_s(S, 3, 0.92, 1.14, 0.97); _set_s(S, 4, 1.0, 1.45, 1.1)
		for i in [5, 8]:
			_set_s(S, i, 0.76, 1.16, 0.76)
		for i in [6, 9]:
			_set_s(S, i, 0.74, 1.24, 0.74)
		_set_s(S, 7, 0.9, 1.4, 0.9); _set_s(S, 10, 0.9, 1.4, 0.9)
		for i in [11, 14, 12, 15]:
			_set_s(S, i, 0.78, 1.06, 0.78)
		_set_s(S, 17, 1.0, 1.0, 1.0)
	elif K == KIND_BLOAT:
		_set_s(S, 0, 1.45, 1.06, 1.5); _set_s(S, 1, 1.6, 1.06, 1.7); _set_s(S, 2, 1.36, 1.02, 1.45); _set_s(S, 3, 1.08, 0.98, 1.1)
		for i in [5, 8]:
			_set_s(S, i, 1.35, 0.96, 1.35)
		for i in [6, 9]:
			_set_s(S, i, 1.2, 0.94, 1.2)
		for i in [11, 14]:
			_set_s(S, i, 1.42, 0.94, 1.42)
		for i in [12, 15]:
			_set_s(S, i, 1.25, 0.94, 1.25)
		paunch = 1.0; _set_s(S, 17, 2.0, 2.0, 2.3)
	elif K == KIND_BREAKER:
		_set_s(S, 0, 1.12, 1.0, 1.12); _set_s(S, 1, 1.15, 1.02, 1.15); _set_s(S, 2, 1.3, 1.06, 1.25); _set_s(S, 3, 1.0, 0.98, 1.0)
		_set_s(S, 5, 0.74, 0.92, 0.74); _set_s(S, 6, 0.68, 0.9, 0.68); _set_s(S, 7, 0.85, 0.9, 0.85)
		_set_s(S, 8, 1.6, 1.12, 1.6); _set_s(S, 9, 2.5, 1.3, 2.5); _set_s(S, 10, 2.2, 1.6, 2.2)
		for i in [11, 14, 12, 15]:
			_set_s(S, i, 1.15, 1.0, 1.15)
	b.shape = S
	# ---- look block
	var c3 := _lin(b.skin)
	var shirt := _lin(b.shirt); var pants := _lin(b.pants); var hair := _lin(b.hair); var c5 := _lin(b.jacket)
	var c6 := _lin(_pick(BOOT_COL, hash1(sd * 47.3)))
	var grime := 0.0; var blood := 0.0; var rag := 0.0; var wound := 0.0
	var hs0 := hash1(sd * 45.9)
	var style := 0 if hs0 < 0.3 else 1 if hs0 < 0.55 else 2 if hs0 < 0.8 else 3
	var outfit := b.outfit
	var long_sleeve := (hash1(sd * 11.3) < 0.15) if style == 0 else true
	var shorts := hash1(sd * 13.7) < 0.1
	var sl_l := 0.9 if outfit == 1 else 1.125 if outfit == 3 else 0.9 if long_sleeve else 1.31
	var sl_r := sl_l
	var lg_l := 0.56 if shorts else 0.05
	var lg_r := lg_l
	var bare_torso := false; var bare_feet := false
	var gear := b.gear
	if not b.human:
		var dc := b.decay
		var ta: Array = DECAY_TINT[0] if dc < 0.5 else DECAY_TINT[1]
		var tb: Array = DECAY_TINT[1] if dc < 0.5 else DECAY_TINT[2]
		var t := dc * 2.0 if dc < 0.5 else dc * 2.0 - 1.0
		var keep := 0.6 * (1.0 - _sst(0.0, 0.5, dc)) + 0.2
		for i in 3:
			var tint: float = ta[i] + (tb[i] - ta[i]) * t
			c3[i] = c3[i] * keep + pow(tint, 2.2) * (1.0 - keep) * (0.85 + h1 * 0.3)
		if TYPE_TINT.has(K):
			var tt: Array = TYPE_TINT[K]
			for i in 3:
				c3[i] = c3[i] * (1.0 - tt[3]) + pow(tt[i], 2.2) * tt[3]
		var dirt := 0.15 + dc * 0.5
		for i in 3:
			shirt[i] = shirt[i] * (1.0 - dirt * 0.6) + 0.03 * dirt
			pants[i] = pants[i] * (1.0 - dirt * 0.55) + 0.028 * dirt
			c5[i] = c5[i] * (1.0 - dirt * 0.5) + 0.028 * dirt
			hair[i] = hair[i] * (1.0 - dc * 0.5) + 0.03 * dc
		grime = 0.08 + dc * 0.9; blood = 0.25 + h3 * 0.5; rag = 0.03 + dc * 0.1; wound = 0.4 + dc * 0.6
		if hash1(sd * 21.1) < 0.25 + dc * 0.6: sl_l = maxf(sl_l, 1.05 + hash1(sd * 22.2) * 0.4)
		if hash1(sd * 23.3) < 0.25 + dc * 0.6: sl_r = maxf(sl_r, 1.05 + hash1(sd * 24.4) * 0.4)
		if hash1(sd * 25.5) < 0.15 + dc * 0.65: lg_l = maxf(lg_l, 0.2 + hash1(sd * 26.6) * 0.45)
		if hash1(sd * 27.7) < 0.15 + dc * 0.65: lg_r = maxf(lg_r, 0.2 + hash1(sd * 28.8) * 0.45)
		if hash1(sd * 29.9) < dc * dc * 0.75 - 0.04 or K == KIND_HOWLER or K == KIND_BLOAT or (K == KIND_BRUTE and hash1(sd * 30.3) < 0.6): bare_torso = true
		if hash1(sd * 31.1) < 0.1 + dc * 0.7: bare_feet = true
		if dc > 0.7 or K >= 2: gear = 0
		if bare_torso or K >= 2 or hash1(sd * 32.5) < dc - 0.35: outfit = 0
		if K == KIND_BLOAT:
			sl_l = 1.6; sl_r = 1.6
		if K == KIND_HOWLER:
			sl_l = 1.6; sl_r = 1.6; lg_l = maxf(lg_l, 0.45); lg_r = maxf(lg_r, 0.4); bare_feet = true
	else:
		var s := b.sick
		var pale := [0.5, 0.52, 0.42]
		for i in 3:
			c3[i] = c3[i] * (1.0 - s * 0.45) + pale[i] * s * 0.45
		grime = 0.05 + s * 0.1
	var mask := 0
	var hs := b.hair_style
	var bald := (not b.human) and (hash1(sd * 33.3) < b.decay * 0.9 - 0.15 or K == KIND_BLOAT or K == KIND_HOWLER)
	if hs != 1 and not bald: mask |= 1 << FEAT.hair
	if hs == 2 and not bald: mask |= 1 << FEAT.hairLong
	if hs == 3 and (b.human or b.decay < 0.45):
		mask |= 1 << FEAT.beanie
		if hash1(sd * 35.1) < 0.45: mask |= 1 << FEAT.brim
	if hs == 4 and not bald: mask |= 1 << FEAT.bun
	if hs == 5 and not bald: mask |= 1 << FEAT.beard
	mask |= (1 << FEAT.stumpL) if b.arm_loss_l else (1 << FEAT.foreL)
	mask |= (1 << FEAT.stumpR) if b.arm_loss_r else (1 << FEAT.foreR)
	if b.tool > 0 and b.tool <= 7: mask |= 1 << (FEAT.tool0 + b.tool)
	if K == KIND_BLOAT and not b.burst: mask |= 1 << FEAT.bloat
	if K == KIND_BRUTE: mask |= 1 << FEAT.hump
	if K == KIND_BREAKER: mask |= 1 << FEAT.club
	if K == KIND_HOWLER: mask |= 1 << FEAT.throat
	if outfit == 1 or outfit == 2: mask |= 1 << FEAT.jacket
	if gear & 1: mask |= 1 << FEAT.pack
	if gear & 2: mask |= 1 << FEAT.belt
	if paunch > 0.0 and K != KIND_HOWLER and not b.burst: mask |= 1 << FEAT.paunch
	var flags := (1 if outfit == 1 else 0) | (2 if outfit == 2 else 0) | (4 if outfit == 3 else 0) | (8 if gear & 1 else 0) | (16 if gear & 2 else 0) \
		| (32 if bare_torso else 0) | (64 if bare_feet else 0) | (128 if hs != 5 and hash1(sd * 39.7) < 0.3 else 0) | (256 if hs == 5 and not bald else 0)
	var c4 := _lin(_pick(KIT_COL, hash1(sd * 37.9)))
	var sdn := sd * 0.137
	b.look = PackedFloat32Array([
		c3[0], c3[1], c3[2], float(mask % 65536),
		shirt[0], shirt[1], shirt[2], sl_l,
		pants[0], pants[1], pants[2], sl_r,
		hair[0], hair[1], hair[2], lg_l,
		b.sick * 0.25 if b.human else b.decay, (sdn - floor(sdn)) * 40.0, float(K), lg_r,
		blood, grime, rag, wound,
		c5[0], c5[1], c5[2], float(flags),
		c4[0], c4[1], c4[2], floor(hash1(sd * 49.1) * 10.0),
		c6[0], c6[1], c6[2], float(style),
		float(mask >> 16), 0.0, 0.0, 0.0])

static func rig_for(b: Body) -> String:
	if b.human:
		return "h1" if (b.tool == 4 or b.tool == 7) else "h2" if (b.tool == 2 or b.tool == 5) else "h3" if b.tool == 3 else "h0"
	match b.kind:
		KIND_RUNNER: return "runner"
		KIND_BRUTE: return "brute"
		KIND_HOWLER: return "howler"
		KIND_BLOAT: return "bloat"
		KIND_BREAKER: return "breaker"
	return "w%d" % b.gait

# ------------------------------------------------------------------ internals

func _valid(id: int) -> bool:
	return id >= 0 and id < _bodies.size() and _bodies[id] != null

func _frame(b: Body) -> float:
	var f := b.f0 + (_time - b.t0) * b.rate
	return fposmod(f, b.n) if b.loop else clampf(f, 0.0, b.n - 1.0)

func _resolve(b: Body, clip: StringName) -> String:
	var c: Dictionary = _rigs[b.rig].clips
	var name := String(clip)
	if b.human and b.sick >= 0.5 and c.has(name + "_sick"):
		name += "_sick"
	if c.has(name):
		return name
	# people holding a long tool have no resting stances; zombies other than walkers have no separate run
	if name.begins_with("idle"):
		return "idle"
	if name == "run" and c.has("chase"):
		return "chase"
	if name == "chase" and c.has("run"):
		return "run"
	if name.begins_with("walk") and c.has("walk"):
		return "walk"
	push_warning("Bodies: rig '%s' has no clip '%s'" % [b.rig, name])
	return "idle"

func _write_a(id: int, b: Body) -> void:
	var px := _img.get_pixel(ANIM0, id)
	px.a = b.rate
	_img.set_pixel(ANIM0, id, px)
	var shut := 1.0 if (b.human and (b.clip == "lie" or b.sick > 0.9)) else 0.0
	_img.set_pixel(ANIM0 + 1, id, Color(b.t0, b.f0, 1.0 if b.loop else 0.0, shut))
	_dirty = true

func _write_fade(id: int, b: Body, t: float, dur: float) -> void:
	var px := _img.get_pixel(ANIM0 + 4, id)
	if t >= 0.0:
		px.r = t; px.g = dur
	px.b = b.look_yaw; px.a = b.look_pitch
	_img.set_pixel(ANIM0 + 4, id, px)
	_dirty = true

func _refresh(id: int) -> void:
	var b := _bodies[id]
	compute_def(b)
	b.rig = rig_for(b)
	b.scale = b.height * float(KIND_SCALE.get(b.kind, 1.0))
	for k in 10:
		_img.set_pixel(k, id, Color(b.look[k * 4], b.look[k * 4 + 1], b.look[k * 4 + 2], b.look[k * 4 + 3]))
	for k in NB:
		_img.set_pixel(SCALE0 + k, id, Color(b.shape[k * 3], b.shape[k * 3 + 1], b.shape[k * 3 + 2], 1.0))
	var rig: Dictionary = _rigs[b.rig]
	var tilt := 0.0 if b.human else (hash1(b.seed * 3.1) - 0.5) * 0.5
	_img.set_pixel(ANIM0 + 5, id, Color(SHX * (b.shape[6] - float(rig.chestSx)), HPX * (b.shape[0] - float(rig.hipSx)), tilt, 0.0))
	_dirty = true

func _drift_stances() -> void:
	for id in _hi:
		var b := _bodies[id]
		if b == null or not b.auto_idle or _time < b.next_stance:
			continue
		var slot: float = floor(_time / 23.0 + b.seed * 3.7)
		var want: StringName = STANCES[int(floor(hash1(slot * 12.9898 + b.seed * 4.1) * STANCES.size())) % STANCES.size()]
		b.next_stance = _time + 14.0 + hash1(b.seed + slot) * 14.0
		var name := _resolve(b, want)
		if name == b.clip:
			continue
		var asked := b.asked
		var next := b.next_stance
		b.asked = &""
		set_anim(id, want, {"fade": 1.6, "phase": hash1(b.seed + _time)})
		b.asked = asked; b.auto_idle = true; b.next_stance = next

func _rebase() -> void:
	# keep the shader clock small so float precision never shows as jitter
	var shift := _time
	_time = 0.0
	for id in _hi:
		var b := _bodies[id]
		if b == null:
			continue
		b.t0 -= shift; b.next_stance -= shift
		for k in [ANIM0 + 1, ANIM0 + 3, ANIM0 + 4]:
			var px := _img.get_pixel(k, id)
			px.r -= shift
			_img.set_pixel(k, id, px)
	_dirty = true

func _grow() -> void:
	var n := mini(max_bodies, maxi(64, multimesh.instance_count * 2))
	multimesh.instance_count = n
	for id in _bodies.size():
		var b := _bodies[id]
		if b == null:
			multimesh.set_instance_transform(id, Transform3D(Basis.from_scale(Vector3.ZERO), Vector3.ZERO))
			continue
		multimesh.set_instance_custom_data(id, Color(float(id), b.seed, 0.0, 0.0))
		b.placed = false
		set_pose(id, b.pos, b.yaw)
	multimesh.visible_instance_count = _hi

static func _vec3s(f: PackedFloat32Array) -> PackedVector3Array:
	var out := PackedVector3Array()
	out.resize(f.size() / 3)
	for i in out.size():
		out[i] = Vector3(f[i * 3], f[i * 3 + 1], f[i * 3 + 2])
	return out

func _load() -> void:
	if multimesh != null:
		return
	_meta = JSON.parse_string(FileAccess.get_file_as_string(DATA_DIR + "body.json"))
	_rigs = _meta.rigs
	# mesh: positions, normals, the per-vertex (bone A, bone B, weight, region + 64 * feature) and indices
	var nv := int(_meta.mesh.verts)
	var ni := int(_meta.mesh.indices)
	var buf := FileAccess.get_file_as_bytes(DATA_DIR + "body_mesh.bin")
	var o := 0
	var arrays := []
	arrays.resize(Mesh.ARRAY_MAX)
	arrays[Mesh.ARRAY_VERTEX] = _vec3s(buf.slice(o, o + nv * 12).to_float32_array()); o += nv * 12
	arrays[Mesh.ARRAY_NORMAL] = _vec3s(buf.slice(o, o + nv * 12).to_float32_array()); o += nv * 12
	arrays[Mesh.ARRAY_CUSTOM0] = buf.slice(o, o + nv * 16).to_float32_array(); o += nv * 16
	arrays[Mesh.ARRAY_INDEX] = buf.slice(o, o + ni * 4).to_int32_array()
	var mesh := ArrayMesh.new()
	mesh.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, arrays, [], {}, Mesh.ARRAY_CUSTOM_RGBA_FLOAT << Mesh.ARRAY_FORMAT_CUSTOM0_SHIFT)
	mesh.custom_aabb = AABB(Vector3(-2.2, -0.3, -2.2), Vector3(4.4, 2.8, 4.4))
	# clips: one row per frame, two texels per bone
	var cw := int(_meta.clipTex.w)
	var ch := int(_meta.clipTex.h)
	var cimg := Image.create_from_data(cw, ch, false, Image.FORMAT_RGBAF, FileAccess.get_file_as_bytes(DATA_DIR + "body_clips.bin"))
	cimg.convert(Image.FORMAT_RGBAH)
	var ctex := ImageTexture.create_from_image(cimg)
	_img = Image.create(TEX_W, max_bodies, false, Image.FORMAT_RGBAF)
	_tex = ImageTexture.create_from_image(_img)
	material = ShaderMaterial.new()
	material.shader = load("res://characters/body.gdshader")
	material.set_shader_parameter(&"clip_tex", ctex)
	material.set_shader_parameter(&"body_tex", _tex)
	material.set_shader_parameter(&"night", night)
	material.set_shader_parameter(&"albedo_gain", albedo_gain)
	material.set_shader_parameter(&"fill_light", fill_light)
	mesh.surface_set_material(0, material)
	multimesh = MultiMesh.new()
	multimesh.transform_format = MultiMesh.TRANSFORM_3D
	multimesh.use_custom_data = true
	multimesh.mesh = mesh
	multimesh.instance_count = 64
	multimesh.visible_instance_count = 0
	multimesh.custom_aabb = AABB(Vector3(-4096, -512, -4096), Vector3(8192, 1024, 8192))
	_mmi = MultiMeshInstance3D.new()
	_mmi.multimesh = multimesh
	_mmi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON
	add_child(_mmi)
