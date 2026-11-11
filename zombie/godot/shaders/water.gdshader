// The lake: dark, glossy, two layers of ripples sliding over each other.
shader_type spatial;
render_mode blend_mix, depth_draw_opaque, cull_back;
uniform sampler2D normal_tex : filter_linear_mipmap, repeat_enable;
uniform float radius = 76.0;
uniform vec3 deep : source_color = vec3(0.03, 0.09, 0.1);
uniform vec3 shallow : source_color = vec3(0.07, 0.17, 0.17);
varying vec3 wpos;
void vertex() { wpos = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz; }
void fragment() {
	float d = length(VERTEX.xz) ;
	vec2 uv = wpos.xz * 0.06;
	vec3 n1 = texture(normal_tex, uv + TIME * vec2(0.012, 0.008)).xyz;
	vec3 n2 = texture(normal_tex, uv * 2.3 - TIME * vec2(0.01, 0.014)).xyz;
	NORMAL_MAP = normalize(n1 + n2 - 1.0) * 0.5 + 0.5;
	NORMAL_MAP_DEPTH = 0.6;
	float fres = pow(1.0 - clamp(dot(NORMAL, VIEW), 0.0, 1.0), 3.0);
	ALBEDO = mix(deep, shallow, fres);
	ROUGHNESS = 0.06;
	METALLIC = 0.0;
	SPECULAR = 0.7;
	ALPHA = mix(0.82, 0.97, fres);
}
