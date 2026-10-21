class_name DayNight
extends Node
## Night falls outside when you work late (night shift licence): the sky, sun and ambient light ease down to
## night from about 18:30 and back up at dawn, while the premises' own lights stay on. Owning the licence also
## puts floodlights over the yard and loading area. Cheap: it only re-applies when the darkness changes by a few
## per cent, and copies the level's Environment the first time it gets dark so the shared scene resource is never
## touched. Created by LevelRoot (not on the title screen).

const UPDATE_SECS := 0.25
const STEP := 0.03
const NIGHT_TOP := Color(0.012, 0.018, 0.045)
const NIGHT_HORIZON := Color(0.045, 0.055, 0.1)
const NIGHT_GROUND := Color(0.025, 0.028, 0.035)
const MOON := Color(0.55, 0.65, 0.95)
const FLOOD_COLOUR := Color(1.0, 0.93, 0.82)

var level: LevelRoot
var darkness_now := 0.0
var _base := {}
var _owned_env := false
var _sky: ProceduralSkyMaterial
var _t := 0.0
var _floods: Array = []


static func darkness(minute_of_day: float) -> float:
	## 0 = daylight, 1 = full night. Dusk 18:30–20:30, dawn 05:00–06:30.
	var h := minute_of_day / 60.0
	if h >= 12.0:
		return smoothstep(18.5, 20.5, h)
	return 1.0 - smoothstep(5.0, 6.5, h)


static func flood_count(building: Dictionary) -> int:
	return 2 + int(building.get("loading_bays", 0))


func setup(lr: LevelRoot) -> void:
	level = lr
	name = "DayNight"
	if lr.sun:
		_base["sun_energy"] = lr.sun.light_energy
		_base["sun_colour"] = lr.sun.light_color


func _process(delta: float) -> void:
	_t -= delta
	if _t > 0.0 or level == null:
		return
	_t = UPDATE_SECS
	var d := darkness(Game.state.minute_of_day()) if Game.in_game else 0.0
	if absf(d - darkness_now) >= STEP or (d == 0.0 and darkness_now > 0.0) or (d == 1.0 and darkness_now < 1.0):
		apply(d)
	_update_floods()


func apply(d: float) -> void:
	darkness_now = d
	if d > 0.0 and not _owned_env:
		_own_environment()
	var sun := level.sun
	if sun and _base.has("sun_energy"):
		sun.light_energy = lerpf(float(_base.sun_energy), 0.05, d)
		sun.light_color = (_base.sun_colour as Color).lerp(MOON, d)
		sun.visible = d < 0.98            # full night: skip the directional shadow pass altogether
	if not _owned_env:
		return
	var env: Environment = level.environment.environment
	env.ambient_light_energy = lerpf(float(_base.ambient), float(_base.ambient) * 0.3, d)
	env.fog_light_color = (_base.fog as Color).lerp(NIGHT_HORIZON, d)
	if _sky:
		_sky.sky_top_color = (_base.top as Color).lerp(NIGHT_TOP, d)
		_sky.sky_horizon_color = (_base.horizon as Color).lerp(NIGHT_HORIZON, d)
		_sky.ground_horizon_color = (_base.g_horizon as Color).lerp(NIGHT_HORIZON, d)
		_sky.ground_bottom_color = (_base.g_bottom as Color).lerp(NIGHT_GROUND, d)
		_sky.sky_energy_multiplier = lerpf(float(_base.sky_energy), 0.6, d)


func _own_environment() -> void:
	## A private copy of the level's Environment (+ sky) so night never leaks into the shared scene resource.
	if level.environment == null or level.environment.environment == null:
		return
	var env: Environment = level.environment.environment.duplicate(true)
	level.environment.environment = env
	_owned_env = true
	_base["ambient"] = env.ambient_light_energy
	_base["fog"] = env.fog_light_color
	if env.sky and env.sky.sky_material is ProceduralSkyMaterial:
		_sky = env.sky.sky_material
		_base["top"] = _sky.sky_top_color
		_base["horizon"] = _sky.sky_horizon_color
		_base["g_horizon"] = _sky.ground_horizon_color
		_base["g_bottom"] = _sky.ground_bottom_color
		_base["sky_energy"] = _sky.sky_energy_multiplier


func _update_floods() -> void:
	## Yard floodlights come with the night shift licence and light up as it gets dark.
	if _floods.is_empty() and Game.state.has_licence("night_shift"):
		_build_floods()
	var on := darkness_now > 0.15
	for f: SpotLight3D in _floods:
		f.visible = on
		f.light_energy = 3.2 * darkness_now


func _build_floods() -> void:
	var spots: Array = []
	for n in [level.lorry_drop, level.goods_in, level.parcel_drop, level.safe_drop]:
		if n and is_instance_valid(n):
			spots.append(n.global_position)
	if spots.is_empty():
		spots.append(Vector3(0, 0, 6))
	var want := flood_count(Game.state.building())
	for i in want:
		var base: Vector3 = spots[i % spots.size()]
		var off := Vector3((i / spots.size()) * 3.0 - 1.5 if i >= spots.size() else 0.0, 0.0, 0.0)
		var l := SpotLight3D.new()
		l.name = "Floodlight_%d" % i
		l.light_color = FLOOD_COLOUR
		l.spot_range = 16.0
		l.spot_angle = 58.0
		l.spot_attenuation = 0.8
		l.shadow_enabled = false
		l.light_energy = 0.0
		l.visible = false
		level.add_child(l)
		l.global_position = base + off + Vector3(0, 6.5, -1.2)
		l.look_at(base + off + Vector3(0, 0, 0.8), Vector3.FORWARD)
		_floods.append(l)
