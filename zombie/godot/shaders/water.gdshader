// The lake: clear at the shore, dark where it is deep, a line of foam where it meets the land,
// two layers of ripples sliding over each other, and the sky in it.
shader_type spatial;
render_mode blend_mix, depth_draw_opaque, cull_back;

uniform sampler2D normal_tex : filter_linear_mipmap, repeat_enable;
uniform sampler2D depth_tex : hint_depth_texture, filter_nearest, repeat_disable;
uniform float radius = 76.0;
uniform vec3 deep : source_color = vec3(0.03, 0.09, 0.1);
uniform vec3 shallow : source_color = vec3(0.1, 0.2, 0.17);
uniform float wind = 0.3;

varying vec3 wpos;

void vertex() { wpos = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz; }

void fragment() {
	float speed = 0.6 + wind;
	vec2 uv = wpos.xz * 0.06;
	vec3 n1 = texture(normal_tex, uv + TIME * vec2(0.012, 0.008) * speed).xyz;
	vec3 n2 = texture(normal_tex, uv * 2.3 - TIME * vec2(0.01, 0.014) * speed).xyz;
	vec3 n3 = texture(normal_tex, uv * 6.1 + TIME * vec2(0.02, -0.017) * speed).xyz;
	NORMAL_MAP = normalize(n1 + n2 + n3 * 0.5 - 1.25) * 0.5 + 0.5;
	NORMAL_MAP_DEPTH = 0.7 + wind * 0.6;

	// how much water lies between this pixel and what is behind it
	float d = texture(depth_tex, SCREEN_UV).r;
#if CURRENT_RENDERER == RENDERER_COMPATIBILITY
	vec3 ndc = vec3(SCREEN_UV, d) * 2.0 - 1.0;
#else
	vec3 ndc = vec3(SCREEN_UV * 2.0 - 1.0, d);
#endif
	vec4 view = INV_PROJECTION_MATRIX * vec4(ndc, 1.0);
	float thick = max(0.0, -view.z / view.w + VERTEX.z);

	float fres = pow(1.0 - clamp(dot(NORMAL, VIEW), 0.0, 1.0), 4.0);
	float murk = 1.0 - exp(-thick * 0.55);
	vec3 col = mix(shallow, deep, murk);
	float foam = (1.0 - smoothstep(0.0, 0.3, thick)) * smoothstep(0.55, 0.95, n3.r + n1.g * 0.5) * step(0.001, thick);
	ALBEDO = mix(col, vec3(0.8, 0.84, 0.82), foam * 0.75);
	ROUGHNESS = mix(0.12, 0.5, foam);
	METALLIC = 0.0;
	SPECULAR = 0.45;
	ALPHA = clamp(mix(mix(0.25, 0.94, murk), 0.98, fres) + foam * 0.5, 0.0, 1.0) * smoothstep(radius, radius - 6.0, length(wpos.xz - NODE_POSITION_WORLD.xz));
}
