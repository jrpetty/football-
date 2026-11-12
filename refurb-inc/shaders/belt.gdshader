shader_type spatial;
render_mode unshaded, cull_disabled, depth_draw_never, shadows_disabled;
// Running roller conveyor: faint marks sweep over the roller tops in the direction of travel while the
// conveyor runs (Jon's rollers stay visible underneath). UV.y is metres along the conveyor; the script
// advances belt_offset only while running and fades belt_run in and out, so nothing ever jumps.

instance uniform float belt_offset = 0.0;
instance uniform float belt_run = 0.0;
uniform float pitch = 0.0425;

void fragment() {
	float f = fract((UV.y - belt_offset) / pitch);
	float mark = smoothstep(0.0, 0.12, f) * (1.0 - smoothstep(0.24, 0.36, f));
	float edge = smoothstep(0.02, 0.1, UV.x) * (1.0 - smoothstep(0.9, 0.98, UV.x));
	ALBEDO = vec3(0.92, 0.95, 1.0);
	ALPHA = mark * edge * 0.22 * belt_run;
}
