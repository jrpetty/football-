// An endless field of grass blades: one MultiMesh that wraps around the camera. Each blade finds its own spot,
// height and colour in the vertex shader from the terrain's height and splat maps, sways in the wind, and
// leans away from your feet.
shader_type spatial;
render_mode cull_disabled, world_vertex_coords;

uniform sampler2D height_tex : filter_nearest, repeat_disable;
uniform sampler2D splat : filter_linear, repeat_disable;
uniform float patch_size = 70.0;
uniform float height_n = 321.0;
uniform float wind = 0.3;
uniform float snow = 0.0;
uniform float flowers = 0.5;
uniform vec3 player_pos = vec3(0.0);
uniform float bright = 1.0;

varying float v_h;
varying vec3 v_col;

float h_at(vec2 p) {
	vec2 f = (p + 400.0) / 2.5; vec2 i = floor(f); vec2 u = f - i;
	float h00 = textureLod(height_tex, (i + 0.5) / height_n, 0.0).r; float h10 = textureLod(height_tex, (i + vec2(1.5, 0.5)) / height_n, 0.0).r;
	float h01 = textureLod(height_tex, (i + vec2(0.5, 1.5)) / height_n, 0.0).r; float h11 = textureLod(height_tex, (i + 1.5) / height_n, 0.0).r;
	if (u.x + u.y < 1.0) return h00 + (h10 - h00) * u.x + (h01 - h00) * u.y;
	return h11 + (h01 - h11) * (1.0 - u.x) + (h10 - h11) * (1.0 - u.y);
}

void vertex() {
	vec4 off = INSTANCE_CUSTOM;
	vec2 cam = CAMERA_POSITION_WORLD.xz;
	float half_s = patch_size * 0.5;
	vec2 wp = mod(off.xy * patch_size - cam + half_s, patch_size) - half_s + cam;
	vec4 sp = textureLod(splat, (wp + 400.0) / 800.0, 0.0);
	float dist = length(wp - cam);
	float mask = (1.0 - sp.r) * (1.0 - smoothstep(0.25, 0.6, sp.g)) * (1.0 - sp.b * 0.75) * (1.0 - sp.a);
	float fade = 1.0 - smoothstep(patch_size * 0.3, patch_size * 0.5, dist);
	float hgt = (0.32 + off.z * 0.5) * mask * fade * step(0.15, mask) * smoothstep(0.6, 2.0, dist) * (1.0 - smoothstep(0.15, 0.55, snow));
	float ang = off.w * 6.2831;
	// the blade mesh is a flat strip in x/y, one metre tall
	vec3 local = vec3(VERTEX.x * (0.7 + off.z * 0.6), VERTEX.y * hgt, 0.0);
	local = vec3(local.x * cos(ang), local.y, local.x * sin(ang));
	float t = UV.y; float bend = t * t;
	float ph = wp.x * 0.35 + wp.y * 0.27;
	vec2 w = vec2(sin(TIME * 1.6 + ph) + 0.5 * sin(TIME * 3.7 + ph * 1.7), cos(TIME * 1.3 + ph * 0.8)) * (0.12 + wind * 0.35);
	vec2 away = wp - player_pos.xz; float ad = length(away);
	vec2 push = ad < 1.4 ? normalize(away + 0.001) * (1.4 - ad) * 0.6 : vec2(0.0);
	local.xz += (w + push + vec2(off.z - 0.5, off.w - 0.5) * 0.3) * bend * hgt;
	local.y -= length(push) * bend * hgt * 0.4;
	VERTEX = local + vec3(wp.x, h_at(wp), wp.y);
	NORMAL = vec3(0.0, 1.0, 0.0);
	v_h = t;
	v_col = mix(vec3(0.17, 0.25, 0.08), vec3(0.38, 0.38, 0.17), smoothstep(0.3, 0.9, sp.g + off.z * 0.5)) * (0.8 + off.w * 0.35);
	if (off.w > 1.0 - 0.045 * flowers) { v_col = off.z < 0.33 ? vec3(0.95, 0.93, 0.85) : (off.z < 0.66 ? vec3(0.95, 0.78, 0.2) : vec3(0.62, 0.4, 0.8)); }
}

void fragment() {
	vec3 c = mix(v_col * 0.35, v_col * 1.12 + vec3(0.03, 0.04, 0.0), v_h) * bright;
	// colours above are linear light; the Compatibility renderer wants them display-encoded
	ALBEDO = OUTPUT_IS_SRGB ? pow(c, vec3(1.0 / 2.2)) : c;
	ROUGHNESS = 0.85;
	SPECULAR = 0.2;
	// light the blade the same from both faces
	if (!FRONT_FACING) { NORMAL = -NORMAL; }
}
