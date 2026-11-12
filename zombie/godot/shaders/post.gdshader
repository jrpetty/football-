// The last touch on the picture, laid over it (it never reads the screen, so it costs almost nothing and
// works on every renderer): a soft vignette, a little film grain, and red closing in when you are hurt.
shader_type canvas_item;

uniform float vignette = 0.3;
uniform float grain = 0.03;
uniform float hurt = 0.0;          // 0 healthy .. 1 nearly dead
uniform float hit = 0.0;           // spikes to 1 when struck, decays
uniform float night = 0.0;

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void fragment() {
	vec2 c = UV - 0.5;
	float r = length(c * vec2(1.0, 0.82));
	float v = smoothstep(0.36, 0.95, r);
	float dark = v * (vignette + hurt * 0.4 + night * 0.12);
	float red = v * clamp(hit * 0.6 + max(0.0, hurt - 0.5) * 0.7 * (0.75 + 0.25 * sin(TIME * 5.0)), 0.0, 0.75);
	float g = (hash(UV * vec2(1920.0, 1080.0) + fract(TIME) * 61.0) - 0.5) * grain;
	vec3 col = mix(vec3(0.0), vec3(0.42, 0.0, 0.0), clamp(red / max(dark + red, 0.001), 0.0, 1.0));
	col += vec3(max(g, 0.0)) * 4.0;
	COLOR = vec4(col, clamp(dark + red + abs(g), 0.0, 0.95));
}
