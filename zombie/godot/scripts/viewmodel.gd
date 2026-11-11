class_name ViewModel
extends Node3D
## What you hold in first person: the weapon model, with sway, bob, swings, recoil, reload dips and aiming.

var holder: Node3D
var model: Node3D
var current := ""
var swing_t := 0.0
var swing_dur := 0.0
var recoil_k := 0.0
var reload_t := 0.0
var reload_dur := 0.0
var use_t := 0.0
var ads := 0.0
var want_ads := false
var raise := 0.0
var _sway := Vector2.ZERO
var _bob := 0.0
var muzzle := Vector3.ZERO
var flash: OmniLight3D
var _cache: Dictionary = {}
var two_handed := false
var firearm := false

func _ready() -> void:
	holder = Node3D.new(); add_child(holder)
	flash = OmniLight3D.new(); flash.light_color = Color(1.0, 0.72, 0.35); flash.omni_range = 14.0; flash.light_energy = 0.0
	add_child(flash)

func set_item(id: String) -> void:
	if id == current:
		return
	current = id
	if model != null:
		model.queue_free(); model = null
	raise = 0.0
	if id == "":
		return
	var d := Items.def(id)
	var mid := "generic"
	firearm = false; two_handed = false
	if d.has("weapon"):
		mid = String(d.weapon.model); firearm = String(d.weapon.kind) == "firearm"; two_handed = bool(d.weapon.get("twoHanded", false))
	elif id == "flashlight":
		mid = "flashlight"
	var path := "res://assets/models/held/%s.glb" % mid
	if not _cache.has(path):
		_cache[path] = load(path) if ResourceLoader.exists(path) else null
	if _cache[path] == null:
		return
	model = _cache[path].instantiate()
	Mats.apply(model)
	_no_shadows(model)
	holder.add_child(model)

func _no_shadows(n: Node) -> void:
	if n is GeometryInstance3D:
		n.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	for c in n.get_children():
		_no_shadows(c)

func swing(dur: float) -> void:
	swing_dur = dur; swing_t = dur

func recoil(k: float) -> void:
	recoil_k = minf(1.5, recoil_k + k)
	flash.light_energy = 6.0

func reload(dur: float) -> void:
	reload_dur = dur; reload_t = dur

func use(dur: float) -> void:
	use_t = dur

func update(dt: float, mouse: Vector2, speed: float, on_ground: bool) -> void:
	raise = minf(1.0, raise + dt * 5.0)
	_sway = _sway.lerp(Vector2(clampf(-mouse.x * 0.0006, -0.05, 0.05), clampf(mouse.y * 0.0006, -0.05, 0.05)), 1.0 - exp(-dt * 10.0))
	_bob += dt * speed * 2.0
	var amp := minf(1.0, speed / 5.0) * (1.0 if on_ground else 0.3)
	ads = lerpf(ads, 1.0 if (want_ads and firearm and reload_t <= 0.0) else 0.0, 1.0 - exp(-dt * 14.0))
	swing_t = maxf(0.0, swing_t - dt); reload_t = maxf(0.0, reload_t - dt); use_t = maxf(0.0, use_t - dt)
	recoil_k = lerpf(recoil_k, 0.0, 1.0 - exp(-dt * 12.0))
	flash.light_energy = lerpf(flash.light_energy, 0.0, 1.0 - exp(-dt * 40.0))
	# rest pose: low right; aimed: centred
	var rest := Vector3(0.24, -0.27, -0.5) if not firearm else Vector3(0.17, -0.15, -0.42)
	var aim := Vector3(0.0, -0.085, -0.3)
	var p := rest.lerp(aim, ads)
	var r := Vector3(0.02, 0.06, 0.0).lerp(Vector3.ZERO, ads) if firearm else Vector3(-0.55, 0.3, 0.12)
	p += Vector3(sin(_bob) * 0.012, absf(cos(_bob)) * -0.014, 0.0) * amp * (1.0 - ads * 0.8)
	p += Vector3(_sway.x, _sway.y, 0.0)
	r += Vector3(_sway.y * 1.5, _sway.x * 2.0, _sway.x * 1.5)
	p.y -= (1.0 - raise) * 0.35
	if swing_dur > 0.0 and swing_t > 0.0:
		var u := 1.0 - swing_t / swing_dur
		var wind := sin(clampf(u / 0.3, 0.0, 1.0) * PI * 0.5)
		var strike := smoothstep(0.3, 0.55, u)
		var back := smoothstep(0.6, 1.0, u)
		r.x += -0.9 * wind + 2.0 * strike - 1.1 * back
		r.z += 0.5 * strike - 0.5 * back
		p += Vector3(-0.16 * strike + 0.16 * back, 0.14 * wind - 0.2 * strike + 0.06 * back, -0.22 * strike + 0.22 * back)
	if reload_dur > 0.0 and reload_t > 0.0:
		var u2 := 1.0 - reload_t / reload_dur
		var dip := sin(u2 * PI)
		p.y -= 0.16 * dip; r.x += 0.5 * dip; r.z += 0.35 * dip
	if use_t > 0.0:
		p.y -= 0.3
	p.z += recoil_k * 0.07; r.x += recoil_k * 0.16
	holder.position = p
	holder.rotation = r
	flash.position = p + Vector3(0.0, 0.05, -0.5)
	holder.visible = model != null and use_t <= 0.0
