// Splat-mapped ground: grass, forest floor, dirt, sand, rock on slopes, asphalt on the roads.
shader_type spatial;
render_mode cull_back;

uniform sampler2D splat : filter_linear_mipmap, repeat_disable;       // R road, G dirt, B forest floor, A sand
uniform sampler2D noise_tex : filter_linear_mipmap, repeat_enable;
uniform sampler2D t_grass : source_color, filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D t_dirt : source_color, filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D t_rock : source_color, filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D t_floor : source_color, filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D t_asphalt : source_color, filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D t_sand : source_color, filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D n_grass : filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D n_dirt : filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D n_rock : filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D n_floor : filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D n_asphalt : filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D n_sand : filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D r_grass : filter_linear_mipmap, repeat_enable;
uniform sampler2D r_dirt : filter_linear_mipmap, repeat_enable;
uniform sampler2D r_rock : filter_linear_mipmap, repeat_enable;
uniform sampler2D r_floor : filter_linear_mipmap, repeat_enable;
uniform sampler2D r_asphalt : filter_linear_mipmap, repeat_enable;
uniform sampler2D r_sand : filter_linear_mipmap, repeat_enable;
uniform float wet = 0.0;
uniform float snow = 0.0;
uniform float bright = 1.0;
uniform float uv_scale = 0.25;
uniform float road_scale = 0.18;
uniform vec3 grass_tint : source_color = vec3(1.0);

varying vec3 wpos;
varying vec3 wnor;

void vertex() {
	wpos = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
	wnor = normalize(mat3(MODEL_MATRIX) * NORMAL);
	// uv runs with world x / z: tangent along +x, binormal along -z
	TANGENT = normalize(vec3(1.0, 0.0, 0.0) - NORMAL * NORMAL.x);
	BINORMAL = normalize(cross(NORMAL, TANGENT));
}

vec3 alb(sampler2D t, vec2 a, vec2 b) { return mix(texture(t, a).rgb, texture(t, b).rgb, 0.35); }

void fragment() {
	vec2 suv = (wpos.xz + 400.0) / 800.0;
	vec4 sp = texture(splat, suv);
	float macro = texture(noise_tex, wpos.xz * 0.004).r;
	float macro2 = texture(noise_tex, wpos.xz * 0.021 + 0.37).r;
	float slope = 1.0 - clamp(wnor.y, 0.0, 1.0);
	float rock_w = smoothstep(0.22, 0.42, slope + (macro2 - 0.5) * 0.15);
	vec2 uv1 = wpos.xz * uv_scale;
	vec2 uvr = wpos.xz * road_scale;
	vec2 uv2 = wpos.xz * 0.061 + vec2(0.31, 0.77);
	float dirt_w = clamp(sp.g + smoothstep(0.62, 0.8, macro2) * 0.5, 0.0, 1.0);
	float floor_w = sp.b;
	float asph_w = sp.r;
	float sand_w = sp.a;

	vec3 a = alb(t_grass, uv1, uv2) * grass_tint;
	a *= mix(vec3(0.85, 0.95, 0.75), vec3(1.08, 1.0, 0.85), macro);
	a = mix(a, alb(t_floor, uv1, uv2), floor_w);
	a = mix(a, alb(t_dirt, uv1, uv2), dirt_w * (1.0 - floor_w * 0.5));
	a = mix(a, alb(t_sand, uv1, uv2), sand_w);
	a = mix(a, alb(t_rock, uv1, uv2), rock_w);
	vec3 asph = min(texture(t_asphalt, uvr).rgb, vec3(0.36)) * 0.8;
	a = mix(a, asph, asph_w);
	a *= mix(0.86, 1.1, macro);
	a *= mix(1.0, 0.55, wet * (1.0 - rock_w * 0.3));
	a = mix(a, vec3(0.92, 0.94, 0.97), snow * smoothstep(0.3, 0.0, slope));
	ALBEDO = a * bright;

	float r = texture(r_grass, uv1).g;
	r = mix(r, texture(r_floor, uv1).g, floor_w);
	r = mix(r, texture(r_dirt, uv1).g, dirt_w);
	r = mix(r, texture(r_sand, uv1).g, sand_w);
	r = mix(r, texture(r_rock, uv1).g, rock_w);
	r = mix(r, max(0.62, texture(r_asphalt, uvr).g), asph_w);
	ROUGHNESS = mix(mix(r, r * 0.35, wet), 0.95, asph_w * (1.0 - wet));
	METALLIC = 0.0;

	vec3 n = texture(n_grass, uv1).xyz;
	n = mix(n, texture(n_floor, uv1).xyz, floor_w);
	n = mix(n, texture(n_dirt, uv1).xyz, dirt_w);
	n = mix(n, texture(n_sand, uv1).xyz, sand_w);
	n = mix(n, texture(n_rock, uv1).xyz, rock_w);
	n = mix(n, texture(n_asphalt, uvr).xyz, asph_w);
	NORMAL_MAP = n;
	NORMAL_MAP_DEPTH = mix(1.0, 0.55, asph_w);
}
