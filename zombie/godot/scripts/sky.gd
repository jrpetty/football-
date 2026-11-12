class_name SkyRig
extends Node3D
## Sun, moon, sky, fog and exposure through the day. `hours` is the game clock, 0..24.

var sun: DirectionalLight3D
var moon: DirectionalLight3D
var env: Environment
var sky_mat: ShaderMaterial
var cloud_time := 0.0
var wind := 0.3
var fog_extra := 0.0          # weather adds to the haze
var view_fog := 0.0           # a shorter view distance closes in with haze rather than a hard edge
var quality := ""
var cam_attr: CameraAttributesPractical
var world_env: WorldEnvironment
var daylight := 1.0          # 0 at night .. 1 in full day
var overcast := 0.0
var indoor := 0.0

func _ready() -> void:
	sky_mat = ShaderMaterial.new()
	sky_mat.shader = load("res://shaders/sky.gdshader")
	if WorldData.info.has("terrain"):
		sky_mat.set_shader_parameter("noise_tex", Mats.tex(WorldData.info.terrain.noise))
	var s := Sky.new()
	s.sky_material = sky_mat
	s.radiance_size = Sky.RADIANCE_SIZE_128
	s.process_mode = Sky.PROCESS_MODE_REALTIME      # the sky changes all day
	env = Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = s
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.reflected_light_source = Environment.REFLECTION_SOURCE_SKY
	env.tonemap_mode = Environment.TONE_MAPPER_ACES
	env.tonemap_white = 7.0
	# Forward+ and Mobile come out darker and harder than Compatibility for the same lights
	env.tonemap_exposure = 1.0 if RenderingServer.get_current_rendering_method() == "gl_compatibility" else 1.38
	env.fog_enabled = true
	env.fog_sky_affect = 0.0
	env.fog_aerial_perspective = 0.6
	env.ssao_enabled = true
	env.ssao_radius = 1.2
	env.ssao_intensity = 1.2
	env.ssil_enabled = false
	env.glow_enabled = true
	env.glow_intensity = 0.35
	env.glow_bloom = 0.05
	env.glow_hdr_threshold = 1.1
	env.adjustment_enabled = true
	env.adjustment_contrast = 1.06
	env.adjustment_saturation = 1.05
	world_env = WorldEnvironment.new(); world_env.environment = env; add_child(world_env)

	sun = DirectionalLight3D.new(); sun.name = "Sun"
	sun.shadow_enabled = true
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_PARALLEL_4_SPLITS
	sun.directional_shadow_max_distance = 160.0
	sun.directional_shadow_blend_splits = true
	sun.shadow_bias = 0.04
	sun.shadow_normal_bias = 1.4
	sun.light_angular_distance = 0.6
	add_child(sun)
	moon = DirectionalLight3D.new(); moon.name = "Moon"
	moon.shadow_enabled = true
	moon.directional_shadow_max_distance = 90.0
	moon.light_color = Color(0.55, 0.65, 0.95)
	moon.sky_mode = DirectionalLight3D.SKY_MODE_LIGHT_ONLY
	add_child(moon)
	set_time(12.0)

static func _lerp_col(a: Color, b: Color, t: float) -> Color:
	return a.lerp(b, clampf(t, 0.0, 1.0))

func set_time(hours: float, season_tilt := 0.0) -> void:
	# sun up at 6, down at 20; highest at 13
	var day_t := (hours - 6.0) / 14.0                      # 0 sunrise .. 1 sunset
	var ang := day_t * PI
	var elev := sin(ang) * (1.05 - season_tilt * 0.35)     # ~60 degrees at noon
	var up := clampf(elev, -1.0, 1.0)
	var az := lerpf(-1.9, 1.9, clampf(day_t, -0.3, 1.3))
	var dir := Vector3(sin(az) * cos(up), sin(up), -cos(az) * cos(up) * 0.6 - 0.35).normalized()
	if hours < 6.0 or hours > 20.0:
		dir.y = -absf(dir.y) - 0.05
	sun.look_at_from_position(dir * 100.0, Vector3.ZERO, Vector3.UP)
	var h := dir.y                                          # sun height, -1..1
	daylight = smoothstep(-0.06, 0.22, h)
	var twilight := smoothstep(-0.2, 0.0, h) * (1.0 - smoothstep(0.0, 0.3, h))
	var warm := 1.0 - smoothstep(0.0, 0.45, h)
	var oc := overcast
	sun.light_color = _lerp_col(Color(1.0, 0.95, 0.87), Color(1.0, 0.56, 0.3), warm)
	sun.light_energy = daylight * lerpf(1.35, 0.25, oc)
	sun.visible = sun.light_energy > 0.01
	# moon: opposite the sun
	var mdir := Vector3(-dir.x, maxf(0.25, -dir.y), -dir.z).normalized()
	moon.look_at_from_position(mdir * 100.0, Vector3.ZERO, Vector3.UP)
	moon.light_energy = (1.0 - daylight) * 0.4
	moon.visible = moon.light_energy > 0.005

	var top_day := _lerp_col(Color(0.3, 0.48, 0.74), Color(0.46, 0.5, 0.54), oc)
	var hor_day := _lerp_col(Color(0.74, 0.82, 0.9), Color(0.64, 0.66, 0.68), oc)
	var top_dusk := Color(0.16, 0.2, 0.38); var hor_dusk := Color(0.92, 0.52, 0.3)
	var top_night := Color(0.018, 0.026, 0.055); var hor_night := Color(0.04, 0.055, 0.09)
	var top := _lerp_col(top_night, top_day, daylight); top = _lerp_col(top, top_dusk, twilight * 0.7)
	var hor := _lerp_col(hor_night, hor_day, daylight); hor = _lerp_col(hor, hor_dusk, twilight * 0.85 * (1.0 - oc))
	sky_mat.set_shader_parameter("top_color", top)
	sky_mat.set_shader_parameter("horizon_color", hor)
	sky_mat.set_shader_parameter("ground_color", _lerp_col(Color(0.02, 0.02, 0.025), Color(0.2, 0.2, 0.18), daylight))
	sky_mat.set_shader_parameter("energy", lerpf(0.7, 1.15, daylight))
	sky_mat.set_shader_parameter("night", 1.0 - daylight)
	sky_mat.set_shader_parameter("moon_dir", mdir)
	sky_mat.set_shader_parameter("cloud_cover", clampf(0.32 + oc * 0.68, 0.0, 1.0))
	sky_mat.set_shader_parameter("cloud_time", cloud_time)
	var c_day := _lerp_col(Color(1.0, 0.99, 0.96), Color(0.62, 0.64, 0.67), oc)
	var c_lit := _lerp_col(Color(0.07, 0.085, 0.13), c_day, daylight)
	c_lit = _lerp_col(c_lit, Color(1.0, 0.62, 0.4), twilight * 0.75 * (1.0 - oc))
	sky_mat.set_shader_parameter("cloud_light", c_lit)
	sky_mat.set_shader_parameter("cloud_dark", _lerp_col(Color(0.02, 0.025, 0.04), _lerp_col(Color(0.5, 0.54, 0.62), Color(0.3, 0.32, 0.35), oc), daylight))
	# shade is lit by the sky plus a little that never goes away (bounce by day, starlight by night)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_color = _lerp_col(Color(0.2, 0.26, 0.42), Color(0.62, 0.64, 0.6), daylight)
	env.ambient_light_sky_contribution = lerpf(0.3, 0.72, daylight)
	env.ambient_light_energy = lerpf(1.1, lerpf(2.1, 2.2, oc), daylight)
	env.fog_light_color = hor.lerp(top, 0.25)
	env.fog_light_energy = lerpf(0.4, 0.8, daylight)
	env.fog_density = lerpf(0.0011, 0.004, oc) + fog_extra + view_fog
	env.volumetric_fog_density = (0.004 + oc * 0.012 + fog_extra * 6.0) * lerpf(0.5, 1.0, daylight)
	env.volumetric_fog_albedo = hor.lerp(Color.WHITE, 0.4)
	# indoors the sky reaches you only through windows
	env.ambient_light_energy *= lerpf(1.0, 0.42, indoor)
	env.fog_sun_scatter = 0.15 * daylight

func _process(dt: float) -> void:
	cloud_time += dt * (0.6 + wind * 2.4)
	var q := "%s|%s|%s|%s|%s|%s" % [Settings.v("shadow_quality"), Settings.v("ssao"), Settings.v("gi"), Settings.v("volumetric_fog"), Settings.v("bloom"), Settings.v("brightness")]
	if q != quality:
		quality = q
		apply_quality()

## graphics options -> renderer features. Each option is its own switch; the quality preset only sets them.
func apply_quality() -> void:
	var lvl := clampi(int(float(Settings.v("shadow_quality"))) - 1, 0, 3)       # shadow detail, 0 low … 3 ultra
	var forward := RenderingServer.get_current_rendering_method() == "forward_plus"
	var gi := forward and bool(Settings.v("gi"))
	env.ssao_enabled = forward and bool(Settings.v("ssao"))
	env.ssil_enabled = gi
	env.sdfgi_enabled = gi
	env.adjustment_brightness = clampf(float(Settings.v("brightness")), 0.5, 1.6)
	env.sdfgi_cascades = 4
	env.sdfgi_min_cell_size = 0.4
	env.sdfgi_use_occlusion = true
	env.sdfgi_energy = 1.0
	env.volumetric_fog_enabled = forward and bool(Settings.v("volumetric_fog"))
	env.volumetric_fog_length = 160.0
	env.volumetric_fog_gi_inject = 0.6
	env.volumetric_fog_anisotropy = 0.55
	env.volumetric_fog_sky_affect = 0.0
	env.glow_enabled = bool(Settings.v("bloom"))
	var shadows := float(Settings.v("shadow_quality")) >= 1.0
	sun.shadow_enabled = shadows
	moon.shadow_enabled = shadows and lvl >= 2
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_PARALLEL_4_SPLITS if lvl >= 2 else DirectionalLight3D.SHADOW_PARALLEL_2_SPLITS
	sun.directional_shadow_max_distance = [70.0, 110.0, 160.0, 220.0][lvl]
	RenderingServer.directional_shadow_atlas_set_size([1024, 2048, 4096, 8192][lvl], true)
	RenderingServer.directional_soft_shadow_filter_set_quality([RenderingServer.SHADOW_QUALITY_SOFT_VERY_LOW, RenderingServer.SHADOW_QUALITY_SOFT_LOW, RenderingServer.SHADOW_QUALITY_SOFT_MEDIUM, RenderingServer.SHADOW_QUALITY_SOFT_HIGH][lvl])
