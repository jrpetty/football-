extends Node3D
## Weather: clear, overcast, rain, storm and fog drift into one another over the hours. It drives the clouds,
## the haze, the wind in the grass and trees, wet ground, falling rain, lightning and thunder.

var game
var kind := "clear"
var next_change := 0.0             # game minutes
var cover := 0.1                   # 0 clear .. 1 overcast (what the sky uses)
var rain := 0.0                    # 0..1
var wind := 0.3
var fog := 0.0
var wet := 0.0
var _target := {"cover": 0.1, "rain": 0.0, "wind": 0.3, "fog": 0.0}
var _rain: GPUParticles3D
var _rain_mat: ParticleProcessMaterial
var _flash: DirectionalLight3D
var _flash_t := 0.0
var _thunder_t := 0.0
var _rng := RandomNumberGenerator.new()

const KINDS := {
	"clear": {"cover": 0.08, "rain": 0.0, "wind": 0.25, "fog": 0.0, "hours": [5.0, 14.0]},
	"cloudy": {"cover": 0.55, "rain": 0.0, "wind": 0.4, "fog": 0.0, "hours": [3.0, 9.0]},
	"overcast": {"cover": 0.95, "rain": 0.0, "wind": 0.45, "fog": 0.0008, "hours": [2.0, 7.0]},
	"rain": {"cover": 1.0, "rain": 0.6, "wind": 0.55, "fog": 0.0022, "hours": [1.5, 5.0]},
	"storm": {"cover": 1.0, "rain": 1.0, "wind": 0.95, "fog": 0.003, "hours": [1.0, 2.5]},
	"fog": {"cover": 0.7, "rain": 0.0, "wind": 0.1, "fog": 0.016, "hours": [1.5, 4.0]},
}
# what tends to follow what
const NEXT := {
	"clear": [["clear", 3], ["cloudy", 5], ["fog", 1]],
	"cloudy": [["clear", 4], ["overcast", 4], ["rain", 1]],
	"overcast": [["cloudy", 3], ["rain", 5], ["storm", 1], ["fog", 1]],
	"rain": [["overcast", 4], ["storm", 2], ["cloudy", 2]],
	"storm": [["rain", 5], ["overcast", 2]],
	"fog": [["clear", 3], ["cloudy", 3]],
}

func _ready() -> void:
	_rng.seed = 90211
	_rain = GPUParticles3D.new()
	_rain.amount = 2600; _rain.lifetime = 0.9; _rain.preprocess = 0.9; _rain.emitting = false
	_rain.visibility_aabb = AABB(Vector3(-22, -16, -22), Vector3(44, 32, 44))
	_rain.local_coords = false
	_rain_mat = ParticleProcessMaterial.new()
	_rain_mat.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_BOX
	_rain_mat.emission_box_extents = Vector3(18, 0.5, 18)
	_rain_mat.direction = Vector3(0.12, -1, 0.05); _rain_mat.spread = 2.0
	_rain_mat.initial_velocity_min = 17.0; _rain_mat.initial_velocity_max = 21.0
	_rain_mat.gravity = Vector3(0, -6, 0)
	_rain.process_material = _rain_mat
	var q := QuadMesh.new(); q.size = Vector2(0.012, 0.55)
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED; m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.albedo_color = Color(0.75, 0.8, 0.86, 0.3); m.billboard_mode = BaseMaterial3D.BILLBOARD_FIXED_Y
	m.billboard_keep_scale = true
	q.material = m
	_rain.draw_pass_1 = q
	_rain.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(_rain)
	_flash = DirectionalLight3D.new(); _flash.light_color = Color(0.8, 0.86, 1.0); _flash.light_energy = 0.0
	_flash.rotation = Vector3(-1.0, 0.7, 0.0); _flash.shadow_enabled = false
	add_child(_flash)

func set_kind(k: String, instant := false) -> void:
	if not KINDS.has(k):
		return
	kind = k
	var d: Dictionary = KINDS[k]
	_target = {"cover": d.cover, "rain": d.rain, "wind": d.wind, "fog": d.fog}
	if game != null:
		next_change = game.game_min + _rng.randf_range(d.hours[0], d.hours[1]) * 60.0
	if instant:
		cover = d.cover; rain = d.rain; wind = d.wind; fog = d.fog
		wet = 1.0 if d.rain > 0.0 else 0.0

func mod_started(new_game: bool) -> void:
	if new_game:
		set_kind("clear", true)
		next_change = game.game_min + 6.0 * 60.0

func mod_process(dt: float, dt_game_min: float, _ui_open: bool) -> void:
	if game.game_min >= next_change:
		var opts: Array = NEXT[kind]
		var total := 0.0
		for o in opts: total += float(o[1])
		var r := _rng.randf() * total
		var pick := String(opts[0][0])
		for o in opts:
			r -= float(o[1])
			if r <= 0.0:
				pick = String(o[0]); break
		# winter turns rain to heavy overcast (snow is not in yet)
		set_kind(pick)
	# drift toward the target over a few game minutes
	var k := clampf(dt_game_min / 20.0, 0.0, 1.0)
	cover = lerpf(cover, float(_target.cover), k)
	rain = lerpf(rain, float(_target.rain), k)
	wind = lerpf(wind, float(_target.wind), k)
	fog = lerpf(fog, float(_target.fog), k)
	wet = clampf(wet + (rain * 0.08 - (0.012 if rain < 0.05 else 0.0)) * dt_game_min, 0.0, 1.0)
	var sky: SkyRig = game.sky
	sky.overcast = cover; sky.wind = wind; sky.fog_extra = fog
	game.world.set_weather(wind, wet, 0.0)
	# rain follows the camera; none of it falls indoors
	var cam: Camera3D = game.cam
	var p: Player = game.player
	var falling := rain > 0.04 and not p.inside
	_rain.emitting = falling
	if falling:
		_rain.global_position = cam.global_position + Vector3(0, 9, 0) + Basis(Vector3.UP, p.yaw) * Vector3(0, 0, -5)
		_rain.amount_ratio = clampf(rain, 0.15, 1.0)
		_rain_mat.direction = Vector3(0.1 + wind * 0.35, -1, 0.05 + wind * 0.2)
	# lightning
	_flash_t = maxf(0.0, _flash_t - dt)
	_flash.light_energy = (0.35 if Settings.v("reduce_flash") else 2.2) * pow(_flash_t / 0.22, 2.0) if _flash_t > 0.0 else 0.0
	_flash.visible = _flash_t > 0.0
	if kind == "storm":
		_thunder_t -= dt
		if _thunder_t <= 0.0:
			_thunder_t = _rng.randf_range(7.0, 24.0)
			_flash_t = 0.22
			var delay := _rng.randf_range(0.4, 3.5)
			get_tree().create_timer(delay).timeout.connect(func() -> void: Sfx.play("thunder", {"volume": clampf(1.4 - delay * 0.25, 0.4, 1.4)}))

## 0..1 how hard it is raining on the player right now
func rain_on_player() -> float:
	return rain if (game != null and not game.player.inside) else 0.0

func temp_offset() -> float:
	return -3.0 * cover - 2.0 * rain

func mod_save() -> Variant:
	return {"kind": kind, "next": next_change, "cover": cover, "rain": rain, "wind": wind, "fog": fog, "wet": wet}

func mod_load(d) -> void:
	if not (d is Dictionary):
		return
	set_kind(String(d.get("kind", "clear")), true)
	next_change = float(d.get("next", 0.0))
	wet = float(d.get("wet", 0.0))
