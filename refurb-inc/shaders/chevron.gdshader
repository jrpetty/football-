shader_type spatial;
render_mode cull_disabled, shadows_disabled;
// Flow chevrons on a conveyor's flanges, pointing the way kit travels. Dark on a Standard belt; tinted and lit
// on Fast / Express belts, with a pulse that runs along the belt at belt speed while it is moving (the script
// advances `chase` in metres, so a faster belt visibly chases faster).

uniform vec3 tint : source_color = vec3(0.06, 0.06, 0.07);
uniform float glow = 0.0;
uniform float chase = 0.0;
uniform float wave = 2.2;            // metres between pulses
instance uniform float chev_s = 0.0; // where this chevron sits along the belt (metres)

void fragment() {
	float ph = fract((chev_s - chase) / wave);
	float pulse = smoothstep(0.0, 0.18, ph) * (1.0 - smoothstep(0.22, 0.5, ph));
	ALBEDO = tint;
	ROUGHNESS = 0.55;
	EMISSION = tint * glow * (0.3 + pulse * 2.2);
}
