// Trees and bushes: the whole plant leans with the wind, leaves flutter, and light comes through them.
shader_type spatial;
render_mode cull_disabled, depth_draw_opaque;

uniform sampler2D albedo_tex : source_color, filter_linear_mipmap_anisotropic, repeat_enable;
uniform sampler2D normal_tex : hint_normal, filter_linear_mipmap_anisotropic, repeat_enable;
uniform bool use_normal = false;
uniform vec3 tint : source_color = vec3(1.0);
uniform float alpha_cut = 0.5;
uniform float leaf = 1.0;          // 1 for leaves and needles, 0 for trunks and branches
uniform float wind = 0.3;
uniform float snow = 0.0;
uniform float roughness = 0.85;

void vertex() {
	vec3 origin = MODEL_MATRIX[3].xyz;
	float ph = origin.x * 0.21 + origin.z * 0.17;
	float hgt = max(VERTEX.y, 0.0);
	float k = 0.25 + wind;
	// the trunk bends more the higher you go; everything on it rides along
	vec2 lean = vec2(sin(TIME * 0.9 + ph) + 0.4 * sin(TIME * 2.1 + ph * 1.7), cos(TIME * 0.7 + ph * 0.8)) * hgt * hgt * 0.0035 * k;
	VERTEX.xz += lean;
	// leaves shiver on top of that
	float f = leaf * k;
	float s = sin(TIME * 3.3 + VERTEX.x * 2.3 + VERTEX.y * 1.9 + VERTEX.z * 2.7 + ph);
	VERTEX += vec3(s * 0.035, cos(TIME * 2.7 + VERTEX.x * 3.1 + ph) * 0.02, s * 0.03) * f;
}

void fragment() {
	vec4 t = texture(albedo_tex, UV);
	if (leaf > 0.5) {
		ALPHA = t.a;
		ALPHA_SCISSOR_THRESHOLD = alpha_cut;
	}
	vec3 c = t.rgb * tint;
	c = mix(c, vec3(0.92, 0.94, 0.97), snow * smoothstep(0.2, 0.7, NORMAL.y) * 0.0);
	ALBEDO = c;
	ROUGHNESS = roughness;
	SPECULAR = 0.25;
	if (use_normal) { NORMAL_MAP = texture(normal_tex, UV).xyz; }
	if (!FRONT_FACING) { NORMAL = -NORMAL; }
	BACKLIGHT = vec3(0.32, 0.38, 0.14) * leaf;
}
