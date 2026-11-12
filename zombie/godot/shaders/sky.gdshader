// The sky: a gradient that follows the hour, the sun and its glow, a moon, stars, and a layer of drifting cloud.
shader_type sky;

uniform vec3 top_color : source_color = vec3(0.3, 0.48, 0.74);
uniform vec3 horizon_color : source_color = vec3(0.74, 0.82, 0.9);
uniform vec3 ground_color : source_color = vec3(0.2, 0.2, 0.18);
uniform sampler2D noise_tex : repeat_enable, filter_linear_mipmap;
uniform float cloud_cover = 0.4;
uniform float cloud_time = 0.0;
uniform vec3 cloud_light : source_color = vec3(1.0);
uniform vec3 cloud_dark : source_color = vec3(0.5, 0.54, 0.6);
uniform float night = 0.0;
uniform vec3 moon_dir = vec3(0.0, 1.0, 0.0);
uniform float energy = 1.0;

float hash3(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }

void sky() {
	vec3 d = normalize(EYEDIR);
	float h = d.y;
	vec3 col = mix(horizon_color, top_color, pow(clamp(h, 0.0, 1.0), 0.42));
	if (h < 0.0) { col = mix(horizon_color, ground_color, clamp(-h * 7.0, 0.0, 1.0)); }

	float sun_amount = 0.0;
	if (LIGHT0_ENABLED) {
		float sd = max(dot(d, LIGHT0_DIRECTION), 0.0);
		sun_amount = sd;
		float up = smoothstep(-0.03, 0.02, h);
		col += LIGHT0_COLOR * LIGHT0_ENERGY * up * (smoothstep(0.99955, 0.9998, sd) * 12.0 + pow(sd, 220.0) * 0.5 + pow(sd, 12.0) * 0.16);
	}
	// the moon and the stars come out as the light goes
	if (night > 0.01) {
		float md = max(dot(d, normalize(moon_dir)), 0.0);
		col += vec3(0.82, 0.88, 1.0) * night * (smoothstep(0.99925, 0.9996, md) * 1.5 + pow(md, 300.0) * 0.14 + pow(md, 20.0) * 0.02);
		if (h > 0.0) {
			vec3 g = floor(d * 240.0);
			float r = hash3(g);
			float s = step(0.9972, r) * (0.35 + 0.65 * hash3(g + 7.0));
			col += vec3(s) * night * smoothstep(0.0, 0.3, h) * (0.75 + 0.25 * sin(TIME * 2.5 + r * 90.0));
		}
	}
	// cloud: a sheet overhead, three octaves of drifting noise
	if (h > 0.012 && cloud_cover > 0.01) {
		vec2 uv = d.xz / (h + 0.14) * 0.085 + cloud_time * vec2(0.0045, 0.0018);
		float n = texture(noise_tex, uv).r * 0.55 + texture(noise_tex, uv * 2.3 + vec2(0.37, 0.11) + cloud_time * 0.002).r * 0.3 + texture(noise_tex, uv * 5.3 + 0.61).r * 0.15;
		float t = mix(0.86, 0.2, cloud_cover);
		float c = smoothstep(t - 0.1, t + 0.14, n);
		float thick = smoothstep(t, t + 0.34, n);
		vec3 lit = cloud_light * (1.0 + pow(sun_amount, 8.0) * 0.8);
		vec3 cc = mix(lit, cloud_dark, thick * 0.85);
		col = mix(col, cc, c * smoothstep(0.012, 0.2, h) * 0.94);
	}
	COLOR = col * energy;
}
