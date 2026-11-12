class_name UiHudParts
## The pieces of the in-play HUD that draw themselves: compass, stealth eye, crosshair, damage direction,
## the hurt/bleeding screen edge, vitals, hotbar, weapon read-out, prompt and action bar. Each one redraws
## only when what it shows has changed.

# ------------------------------------------------------------------ compass
class Compass extends Control:
	const PX := 3.3                      # pixels per degree
	var heading := 0.0                   # degrees, 0 = north, 90 = east
	var marks: Array = []                # [{bearing, glyph, col, label}]
	var threats := PackedFloat32Array()  # bearing, strength, bearing, strength…
	var _drawn := -999.0
	var _dirty := true

	func _init() -> void:
		mouse_filter = Control.MOUSE_FILTER_IGNORE
		custom_minimum_size = Vector2(560, 56)

	func set_heading(deg: float) -> void:
		if absf(deg - _drawn) > 0.05 or _dirty:
			heading = deg; queue_redraw()

	func touch() -> void:
		_dirty = true

	func _draw() -> void:
		_drawn = heading; _dirty = false
		var half := size.x * 0.5
		var base_y := 30.0
		draw_polyline_colors(PackedVector2Array([Vector2(0, base_y), Vector2(half, base_y), Vector2(size.x, base_y)]), PackedColorArray([Color(1, 1, 1, 0.0), Color(1, 1, 1, 0.35), Color(1, 1, 1, 0.0)]), 1.0)
		var first := int(floor((heading - half / PX) / 5.0)) * 5
		var d := first
		while true:
			var x := half + (d - heading) * PX
			if x > size.x: break
			if x >= 0.0:
				var a := 1.0 - pow(absf(x - half) / half, 3.0)
				var dn := posmod(d, 360)
				if dn % 90 == 0:
					var north := dn == 0
					draw_line(Vector2(x, base_y - 7), Vector2(x, base_y), Color(UiTheme.ACCENT2 if north else UiTheme.INK, a), 2.0)
					UiKit.text(self, "head", Vector2(x - 20, 0), ["N", "E", "S", "W"][dn / 90], 18, Color(UiTheme.ACCENT2 if north else UiTheme.INK, a), HORIZONTAL_ALIGNMENT_CENTER, 40)
				elif dn % 45 == 0:
					draw_line(Vector2(x, base_y - 6), Vector2(x, base_y), Color(UiTheme.INK, a * 0.75), 1.0)
					UiKit.text(self, "cap", Vector2(x - 20, 6), ["NE", "SE", "SW", "NW"][(dn - 45) / 90], 11, Color(UiTheme.DIM, a), HORIZONTAL_ALIGNMENT_CENTER, 40)
				elif dn % 15 == 0:
					draw_line(Vector2(x, base_y - 5), Vector2(x, base_y), Color(UiTheme.INK, a * 0.6), 1.0)
				else:
					draw_line(Vector2(x, base_y - 2), Vector2(x, base_y), Color(UiTheme.INK, a * 0.3), 1.0)
			d += 5
		# the dead, close by: red under the line
		var i := 0
		while i + 1 < threats.size():
			var rel := wrapf(threats[i] - heading, -180.0, 180.0)
			var x2 := half + rel * PX
			if x2 > 4.0 and x2 < size.x - 4.0:
				var a2 := threats[i + 1] * (1.0 - pow(absf(x2 - half) / half, 3.0))
				draw_rect(Rect2(x2 - 2.0, base_y + 3.0, 4.0, 5.0), Color(UiTheme.BAD, a2))
			i += 2
		# places: on the line, or pinned to the end you should turn towards
		for m in marks:
			if m.get("hide", false):
				continue
			var rel2 := wrapf(float(m.bearing) - heading, -180.0, 180.0)
			var x3 := clampf(half + rel2 * PX, 10.0, size.x - 10.0)
			var off := absf(rel2 * PX) > half - 10.0
			var col: Color = m.col
			col.a = 0.45 if off else 1.0
			draw_rect(Rect2(x3 - 9.0, base_y + 3.0, 18.0, 18.0), Color(0, 0, 0, 0.35 * col.a))
			UiKit.glyph(self, String(m.glyph), Rect2(x3 - 6.0, base_y + 6.0, 12.0, 12.0), col)
			if not off and absf(rel2) < 9.0 and String(m.label) != "":
				UiKit.text(self, "cap", Vector2(x3 - 90.0, base_y + 24.0), String(m.label), 11, UiTheme.INK, HORIZONTAL_ALIGNMENT_CENTER, 180)
		# where you are looking
		draw_line(Vector2(half, base_y - 9), Vector2(half, base_y + 2), UiTheme.ACCENT, 2.0)

# ------------------------------------------------------------------ stealth
class Stealth extends Control:
	var vis := 0.5
	var noise := 0.0
	var state := 0                       # 0 unnoticed · 1 seen · 2 hunted
	var count := 0
	var crouched := false
	var _sig := -1

	func _init() -> void:
		mouse_filter = Control.MOUSE_FILTER_IGNORE
		custom_minimum_size = Vector2(240, 24)

	func set_state(v: float, n: float, st: int, cnt: int, cr: bool) -> void:
		var sig := int(v * 20.0) + int(clampf(n, 0.0, 20.0) * 2.0) * 100 + st * 10000 + mini(cnt, 99) * 100000 + (10000000 if cr else 0)
		if sig != _sig:
			_sig = sig; vis = v; noise = n; state = st; count = cnt; crouched = cr
			queue_redraw()

	func _draw() -> void:
		var col: Color = [UiTheme.INK, UiTheme.WARN, UiTheme.BAD][state]
		var cx := size.x * 0.5
		var open := clampf((vis - 0.3) / 0.75, 0.12, 1.0)
		UiKit.eye(self, Rect2(cx - 15.0, 2.0, 30.0, 18.0), open if state == 0 else 1.0, col)
		# how loud you are: five bars to the right of the eye
		var lvl := int(ceil(clampf(noise / 16.0, 0.0, 1.0) * 5.0))
		for i in 5:
			var h := 4.0 + i * 2.5
			draw_rect(Rect2(cx + 24.0 + i * 5.0, 19.0 - h, 3.0, h), Color(col, 0.9) if i < lvl else Color(1, 1, 1, 0.16))
		var word := "HUNTED" if state == 2 else ("SEEN" if state == 1 else ("HIDDEN" if vis < 0.5 else ("VISIBLE" if vis < 1.0 else "EXPOSED")))
		if state == 2 and count > 1:
			word = "HUNTED  ×%d" % count
		UiKit.text(self, "cap", Vector2(cx - 24.0 - 120.0, 4.0), word, 11, col, HORIZONTAL_ALIGNMENT_RIGHT, 120)

# ------------------------------------------------------------------ crosshair, hit marker, damage direction
class Crosshair extends Control:
	var spread := 0.0                    # pixels the arms sit away from the centre
	var mode := 0                        # 0 dot · 1 firearm · 2 interact
	var ads := 0.0
	var hit_t := 0.0
	var hit_kill := false
	var reload := -1.0                   # 0–1 while reloading
	var _sig := -1

	func _init() -> void:
		mouse_filter = Control.MOUSE_FILTER_IGNORE

	func set_state(sp: float, md: int, aim: float, rl: float) -> void:
		var sig := int(sp * 4.0) + md * 1000 + int(aim * 20.0) * 10000 + int(rl * 100.0) * 1000000
		if sig != _sig or hit_t > 0.0:
			_sig = sig; spread = sp; mode = md; ads = aim; reload = rl
			queue_redraw()

	func _draw() -> void:
		var ink := Color(1, 1, 1, 0.88)
		var edge := Color(0, 0, 0, 0.45)
		if mode == 2:
			draw_arc(Vector2.ZERO, 6.0, 0.0, TAU, 24, edge, 3.0, true)
			draw_arc(Vector2.ZERO, 6.0, 0.0, TAU, 24, ink, 1.4, true)
			draw_circle(Vector2.ZERO, 1.4, ink, true, -1.0, true)
		else:
			var a := 1.0 - ads * 0.85
			if mode == 1 and a > 0.03:
				var g := 6.0 + spread
				for dir in [Vector2.RIGHT, Vector2.LEFT, Vector2.UP, Vector2.DOWN]:
					draw_line(dir * (g - 1.0), dir * (g + 8.0), Color(edge, edge.a * a), 3.6)
					draw_line(dir * g, dir * (g + 7.0), Color(ink, ink.a * a), 1.6)
			draw_circle(Vector2.ZERO, 2.4, edge, true, -1.0, true)
			draw_circle(Vector2.ZERO, 1.5, ink, true, -1.0, true)
		if reload >= 0.0:
			draw_arc(Vector2.ZERO, 16.0, -PI * 0.5, -PI * 0.5 + TAU, 40, Color(0, 0, 0, 0.3), 3.0, true)
			draw_arc(Vector2.ZERO, 16.0, -PI * 0.5, -PI * 0.5 + TAU * clampf(reload, 0.0, 1.0), 40, UiTheme.ACCENT2, 2.0, true)
		if hit_t > 0.0:
			var life := 0.45 if hit_kill else 0.22
			var k := 1.0 - hit_t / life
			var c := Color(UiTheme.BAD if hit_kill else Color.WHITE, minf(1.0, hit_t / life * 2.0))
			var r := (9.0 if hit_kill else 7.0) + k * 6.0
			var l := 9.0 if hit_kill else 6.0
			for a2 in [PI * 0.25, PI * 0.75, PI * 1.25, PI * 1.75]:
				var dv := Vector2(cos(a2), sin(a2))
				draw_line(dv * r, dv * (r + l), Color(0, 0, 0, c.a * 0.5), 4.0, true)
				draw_line(dv * r, dv * (r + l), c, 2.2 if hit_kill else 1.8, true)

## red arcs around the centre that point at what just hurt you
class DamageRing extends Control:
	var hits: Array = []                 # [bearing_deg, time_left]
	var heading := 0.0
	const LIFE := 1.6

	func _init() -> void:
		mouse_filter = Control.MOUSE_FILTER_IGNORE

	func add(bearing: float) -> void:
		for h in hits:
			if absf(wrapf(float(h[0]) - bearing, -180.0, 180.0)) < 20.0:
				h[1] = LIFE; h[0] = bearing
				return
		hits.append([bearing, LIFE])
		queue_redraw()

	func tick(dt: float, head: float) -> void:
		if hits.is_empty():
			return
		heading = head
		for i in range(hits.size() - 1, -1, -1):
			hits[i][1] = float(hits[i][1]) - dt
			if float(hits[i][1]) <= 0.0:
				hits.remove_at(i)
		queue_redraw()

	func _draw() -> void:
		for h in hits:
			var rel := deg_to_rad(wrapf(float(h[0]) - heading, -180.0, 180.0)) - PI * 0.5
			var a := clampf(float(h[1]) / LIFE * 1.6, 0.0, 1.0)
			var r := 118.0 + (1.0 - float(h[1]) / LIFE) * 10.0
			draw_arc(Vector2.ZERO, r, rel - 0.34, rel + 0.34, 20, Color(0.75, 0.08, 0.05, 0.55 * a), 12.0, true)
			draw_arc(Vector2.ZERO, r + 5.0, rel - 0.2, rel + 0.2, 14, Color(0.95, 0.25, 0.15, 0.9 * a), 3.0, true)
			var tip := Vector2(cos(rel), sin(rel))
			var side := Vector2(-tip.y, tip.x)
			draw_colored_polygon(PackedVector2Array([tip * (r + 22.0), tip * (r + 9.0) + side * 9.0, tip * (r + 9.0) - side * 9.0]), Color(0.95, 0.25, 0.15, 0.9 * a))

# ------------------------------------------------------------------ the hurt / bleeding screen edge
class ScreenFx extends ColorRect:
	const CODE := """
shader_type canvas_item;
uniform float flash = 0.0;
uniform float low = 0.0;
uniform float bleed = 0.0;
uniform float beat = 0.0;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
	vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
void fragment() {
	vec2 uv = UV - 0.5;
	float d = length(uv * vec2(1.0, 0.82)) * 1.62;
	float n = vnoise(UV * vec2(7.0, 4.0)) * 0.6 + vnoise(UV * vec2(19.0, 11.0)) * 0.4;
	float edge = smoothstep(0.5, 1.12, d + (n - 0.5) * 0.4);
	float rim = smoothstep(0.78, 1.2, d + (n - 0.5) * 0.55);
	float a_flash = flash * (0.14 + edge * 0.8);
	float a_low = low * edge * (0.6 + beat * 0.3);
	float a_bleed = bleed * rim * (0.45 + 0.4 * beat);
	float a = clamp(max(a_flash, max(a_low, a_bleed)), 0.0, 0.92);
	vec3 red = vec3(0.5, 0.02, 0.015);
	vec3 dark = vec3(0.07, 0.0, 0.0);
	vec3 col = mix(red, dark, clamp(low * 0.75 - flash, 0.0, 1.0) * edge);
	COLOR = vec4(col, a);
}
"""
	var _flash := 0.0
	var _low := 0.0
	var _bleed := 0.0
	var _beat := 0.0
	var _phase := 0.0

	func _init() -> void:
		mouse_filter = Control.MOUSE_FILTER_IGNORE
		var sh := Shader.new(); sh.code = CODE
		var m := ShaderMaterial.new(); m.shader = sh
		material = m
		visible = false

	func update(dt: float, flash: float, low: float, bleed: float) -> void:
		var on := flash > 0.004 or low > 0.004 or bleed > 0.004
		if on != visible:
			visible = on
		if not on:
			return
		# a heartbeat: two quick thumps, a rest; quicker the worse things are
		_phase = fmod(_phase + dt * (1.1 + low * 0.9), 1.0)
		var b := maxf(exp(-pow((_phase - 0.08) * 14.0, 2.0)), 0.7 * exp(-pow((_phase - 0.3) * 14.0, 2.0)))
		var m: ShaderMaterial = material
		if absf(flash - _flash) > 0.003: _flash = flash; m.set_shader_parameter("flash", flash)
		if absf(low - _low) > 0.003: _low = low; m.set_shader_parameter("low", low)
		if absf(bleed - _bleed) > 0.003: _bleed = bleed; m.set_shader_parameter("bleed", bleed)
		if absf(b - _beat) > 0.01: _beat = b; m.set_shader_parameter("beat", b)

## a soft dark edge and a left-hand shade behind menus
class Backdrop extends Control:
	var art: Texture2D = null
	var shade := 0.0                     # extra flat darkening (pause over the world)
	var left := 0.85                     # how dark the left third is, behind the menu text
	func _init() -> void:
		mouse_filter = Control.MOUSE_FILTER_STOP
		set_anchors_preset(Control.PRESET_FULL_RECT)
	func _draw() -> void:
		var r := Rect2(Vector2.ZERO, size)
		if art != null:
			var ts := art.get_size()
			var k := maxf(size.x / ts.x, size.y / ts.y)
			draw_texture_rect(art, Rect2((size - ts * k) * 0.5, ts * k), false)
		if shade > 0.0:
			draw_rect(r, Color(0.02, 0.02, 0.02, shade))
		var d := Color(0.015, 0.016, 0.015, left); var z := Color(0.015, 0.016, 0.015, 0.0)
		var mid := Color(0.015, 0.016, 0.015, left * 0.55)
		var x1 := size.x * 0.34; var x2 := size.x * 0.72
		draw_polygon(PackedVector2Array([Vector2(0, 0), Vector2(x1, 0), Vector2(x1, size.y), Vector2(0, size.y)]), PackedColorArray([d, mid, mid, d]))
		draw_polygon(PackedVector2Array([Vector2(x1, 0), Vector2(x2, 0), Vector2(x2, size.y), Vector2(x1, size.y)]), PackedColorArray([mid, z, z, mid]))
		# vignette: darker towards the top and bottom edges
		var e := Color(0, 0, 0, 0.6); var n := Color(0, 0, 0, 0.0)
		var h := size.y * 0.24
		draw_polygon(PackedVector2Array([Vector2(0, 0), Vector2(size.x, 0), Vector2(size.x, h), Vector2(0, h)]), PackedColorArray([e, e, n, n]))
		draw_polygon(PackedVector2Array([Vector2(0, size.y - h), Vector2(size.x, size.y - h), Vector2(size.x, size.y), Vector2(0, size.y)]), PackedColorArray([n, n, e, e]))
		draw_texture_rect(UiTheme.tex("grain"), r, true, Color(1, 1, 1, 0.035))

# ------------------------------------------------------------------ vitals
class Vitals extends Control:
	const ROWS := [["heart", Color(0.8, 0.27, 0.22)], ["bolt", Color(0.66, 0.76, 0.36)], ["food", Color(0.88, 0.62, 0.26)], ["drop", Color(0.4, 0.64, 0.86)]]
	var vals := PackedFloat32Array([100, 100, 100, 100])
	var cap := 1.0                       # how much of the stamina bar tiredness leaves you
	var _txt: Array = ["100", "100", "100", "100"]
	var pulse := 0.0

	func _init() -> void:
		mouse_filter = Control.MOUSE_FILTER_IGNORE
		custom_minimum_size = Vector2(286, 96)

	func _put(i: int, v: float) -> bool:
		if absf(vals[i] - v) <= 0.25:
			return false
		if int(round(vals[i])) != int(round(v)):
			_txt[i] = str(int(round(v)))
		vals[i] = v
		return true

	func set_values(health: float, stamina: float, food: float, water: float, stamina_cap: float, pulse_: float) -> void:
		var changed := _put(0, health)
		changed = _put(1, stamina) or changed
		changed = _put(2, food) or changed
		changed = _put(3, water) or changed
		if absf(stamina_cap - cap) > 0.01:
			cap = stamina_cap; changed = true
		var low := vals[0] < 30.0 or vals[2] < 12.0 or vals[3] < 12.0
		if low and absf(pulse_ - pulse) > 0.04:
			pulse = pulse_; changed = true
		if changed:
			queue_redraw()

	func _draw() -> void:
		for i in 4:
			var y := i * 24.0 + (0.0 if i == 0 else 4.0)
			var h := 8.0 if i == 0 else 5.0
			var v := vals[i]
			var crit := (i == 0 and v < 30.0) or (i >= 2 and v < 12.0)
			var col: Color = ROWS[i][1]
			var ink := UiTheme.INK
			if crit:
				ink = UiTheme.BAD.lerp(UiTheme.INK, pulse * 0.6)
				col = col.lerp(UiTheme.BAD, 0.5)
			var gy := y + (2.0 if i == 0 else 0.0)
			UiKit.glyph(self, "food" if i == 2 else String(ROWS[i][0]), Rect2(1.0, gy + 1.0, 16.0, 16.0), Color(0, 0, 0, 0.5))
			UiKit.glyph(self, "food" if i == 2 else String(ROWS[i][0]), Rect2(0.0, gy, 16.0, 16.0), ink)
			var bx := 28.0; var bw := 200.0
			var by := gy + 8.0 - h * 0.5
			draw_rect(Rect2(bx - 1.0, by - 1.0, bw + 2.0, h + 2.0), Color(0, 0, 0, 0.55))
			draw_rect(Rect2(bx, by, bw, h), Color(1, 1, 1, 0.07))
			if i == 1 and cap < 0.995:
				draw_rect(Rect2(bx + bw * cap, by, bw * (1.0 - cap), h), Color(0, 0, 0, 0.45))
			draw_rect(Rect2(bx, by, bw * clampf(v / 100.0, 0.0, 1.0) * (cap if i == 1 else 1.0), h), col)
			UiKit.text(self, "num", Vector2(bx + bw + 10.0, gy - 2.0), _txt[i], 15 if i == 0 else 13, ink if (crit or i == 0) else UiTheme.DIM)

# ------------------------------------------------------------------ hotbar
class Hotbar extends Control:
	const SLOT := 54.0
	const GAP := 6.0
	var hud
	var slots: Array = []                # per slot: {tex, sel, dur, n, ammo}
	var _sig := PackedInt64Array()

	func _init(h) -> void:
		hud = h
		mouse_filter = Control.MOUSE_FILTER_IGNORE
		custom_minimum_size = Vector2(SLOT * 8 + GAP * 7, SLOT + 8)
		_sig.resize(8)
		for i in 8:
			slots.append({"tex": null, "sel": false, "dur": -1.0, "n": 0, "ammo": -1}); _sig[i] = -1

	func refresh(p: Player) -> void:
		var changed := false
		for i in 8:
			var s = p.inv.by_uid(p.hotbar[i])
			var sig := 0
			if s != null:
				var sel: bool = p.equipped != null and int(s.uid) == int(p.equipped)
				sig = int(s.uid) * 1000003 + int(s.n) * 4099 + (1 if sel else 0) + int(float(s.get("dur", 0.0))) * 31 + int(s.get("ammo", 0)) * 524287 + 7
			if sig != _sig[i]:
				_sig[i] = sig; changed = true
				var sl: Dictionary = slots[i]
				if s == null:
					sl.tex = null; sl.sel = false; sl.dur = -1.0; sl.n = 0; sl.ammo = -1
				else:
					var d := Items.def(s.id)
					sl.tex = hud.icon("item_" + String(s.id))
					sl.sel = p.equipped != null and int(s.uid) == int(p.equipped)
					sl.n = int(s.n)
					sl.dur = -1.0; sl.ammo = -1
					if d.has("weapon") and s.has("dur"):
						sl.dur = clampf(float(s.dur) / maxf(1.0, float(d.weapon.durability)), 0.0, 1.0)
						if d.weapon.has("ammo"): sl.ammo = int(s.get("ammo", 0))
					if d.get("light", false):
						sl.dur = clampf(float(s.get("dur", 0.0)) / 100.0, 0.0, 1.0)
		if changed:
			queue_redraw()

	func _draw() -> void:
		for i in 8:
			var sl: Dictionary = slots[i]
			var sel: bool = sl.sel
			var r := Rect2(i * (SLOT + GAP), 8.0 - (5.0 if sel else 0.0), SLOT, SLOT)
			draw_rect(r, Color(0.16, 0.115, 0.05, 0.78) if sel else Color(0.02, 0.022, 0.02, 0.55))
			draw_rect(r, UiTheme.ACCENT if sel else Color(1, 1, 1, 0.14), false, 2.0 if sel else 1.0)
			if sel:
				draw_rect(Rect2(r.position.x + SLOT * 0.5 - 9.0, r.end.y + 3.0, 18.0, 2.0), UiTheme.ACCENT)
			if sl.tex != null:
				draw_texture_rect(sl.tex, Rect2(r.position + Vector2(9, 8), Vector2(36, 36)), false, Color(1, 1, 1, 1.0 if sel else 0.88))
			UiKit.text(self, "num", r.position + Vector2(5, 1), str(i + 1), 11, UiTheme.ACCENT2 if sel else UiTheme.DIM, HORIZONTAL_ALIGNMENT_LEFT, -1, false)
			if float(sl.dur) >= 0.0:
				UiKit.bar(self, Rect2(r.position + Vector2(5, SLOT - 7), Vector2(SLOT - 10, 3)), float(sl.dur), UiTheme.condition(float(sl.dur) * 100.0), Color(0, 0, 0, 0.6))
			if int(sl.ammo) >= 0:
				UiKit.text(self, "num", r.position + Vector2(0, SLOT - 26), str(int(sl.ammo)), 13, UiTheme.INK if int(sl.ammo) > 0 else UiTheme.BAD, HORIZONTAL_ALIGNMENT_RIGHT, SLOT - 5)
			elif int(sl.n) > 1:
				UiKit.text(self, "num", r.position + Vector2(0, SLOT - 24), str(int(sl.n)), 13, UiTheme.INK, HORIZONTAL_ALIGNMENT_RIGHT, SLOT - 5)

# ------------------------------------------------------------------ what is in your hands
class Weapon extends Control:
	var title := ""
	var big := ""
	var small := ""
	var frac := -1.0
	var big_col := UiTheme.INK
	var hint := ""
	var _sig := -1

	func _init() -> void:
		mouse_filter = Control.MOUSE_FILTER_IGNORE
		custom_minimum_size = Vector2(260, 84)

	func refresh(p: Player, reloading: bool) -> void:
		var es = p.equipped_stack()
		if es == null:
			if _sig != 0:
				_sig = 0; title = ""; big = ""; small = ""; frac = -1.0; hint = ""; queue_redraw()
			return
		var d := Items.def(es.id)
		var w = d.get("weapon")
		var sig := int(es.uid) * 131
		if w != null and w.has("ammo"):
			var mag := int(es.get("ammo", 0)); var res := p.inv.count(String(w.ammo))
			sig += mag * 7919 + res * 104729 + (1 if reloading else 0)
			if sig == _sig: return
			_sig = sig
			title = String(d.name).to_upper(); big = str(mag); small = "/ %d" % res; frac = -1.0
			big_col = UiTheme.BAD if mag == 0 else (UiTheme.WARN if mag * 4 <= int(w.mag) else UiTheme.INK)
			hint = "RELOADING" if reloading else ("R  RELOAD" if mag == 0 and res > 0 else ("NO AMMUNITION" if mag == 0 else ""))
		else:
			var pct := -1.0
			if d.get("light", false): pct = float(es.get("dur", 0.0))
			elif w != null and es.has("dur"): pct = float(es.dur) / maxf(1.0, float(w.durability)) * 100.0
			sig += int(pct) * 7919 + (500 if p.flashlight_on else 0)
			if sig == _sig: return
			_sig = sig
			title = String(d.name).to_upper(); big = ""; hint = ""
			frac = pct / 100.0
			small = ("BATTERY %d%%" % int(round(pct))) if d.get("light", false) else (("CONDITION %d%%" % int(round(pct))) if pct >= 0.0 else "")
		queue_redraw()

	func _draw() -> void:
		if title == "":
			return
		var w := size.x
		UiKit.text(self, "head", Vector2(0, 0), title, 14, UiTheme.DIM, HORIZONTAL_ALIGNMENT_RIGHT, w)
		if big != "":
			var sw := UiKit.text_w("num", small, 20) + 8.0
			UiKit.text(self, "num", Vector2(0, 18), big, 44, big_col, HORIZONTAL_ALIGNMENT_RIGHT, w - sw)
			UiKit.text(self, "num", Vector2(0, 40), small, 20, UiTheme.DIM, HORIZONTAL_ALIGNMENT_RIGHT, w)
			if hint != "":
				UiKit.text(self, "cap", Vector2(0, 70), hint, 11, UiTheme.ACCENT2, HORIZONTAL_ALIGNMENT_RIGHT, w)
		elif small != "":
			UiKit.text(self, "cap", Vector2(0, 24), small, 12, UiTheme.INK, HORIZONTAL_ALIGNMENT_RIGHT, w)
			if frac >= 0.0:
				var bw := 120.0
				draw_rect(Rect2(w - bw - 1.0, 43.0, bw + 2.0, 6.0), Color(0, 0, 0, 0.55))
				UiKit.bar(self, Rect2(w - bw, 44.0, bw, 4.0), frac, UiTheme.condition(frac * 100.0), Color(1, 1, 1, 0.08))

# ------------------------------------------------------------------ interaction prompt and timed action
class Prompt extends Control:
	var key := ""
	var verb := ""
	var name_ := ""
	var sub := ""
	var _a := 0.0
	var _want := false

	func _init() -> void:
		mouse_filter = Control.MOUSE_FILTER_IGNORE
		modulate.a = 0.0

	func set_prompt(k: String, v: String, n: String, s := "") -> void:
		_want = v != "" or n != ""
		if _want and (k != key or v != verb or n != name_ or s != sub):
			key = k; verb = v; name_ = n; sub = s
			queue_redraw()

	func tick(dt: float) -> void:
		var t := 1.0 if _want else 0.0
		if _a != t:
			_a = move_toward(_a, t, dt * 9.0)
			modulate.a = _a

	func _draw() -> void:
		var y := 0.0
		if name_ != "":
			UiKit.text(self, "cap", Vector2(-200, y), name_.to_upper(), 12, UiTheme.ACCENT2, HORIZONTAL_ALIGNMENT_CENTER, 400)
			y += 20.0
		if verb != "":
			var fs := 17
			var tw := UiKit.text_w("body", verb, fs)
			var kw := UiKit.keycap_size(key, 14).x + 9.0 if key != "" else 0.0
			var x := -(tw + kw) * 0.5
			if key != "":
				UiKit.keycap(self, Vector2(x, y + 1.0), key, 14, true)
			UiKit.text(self, "body", Vector2(x + kw, y + 1.0), verb, fs, UiTheme.INK)
			y += 28.0
		if sub != "":
			UiKit.text(self, "body", Vector2(-200, y), sub, 13, UiTheme.DIM, HORIZONTAL_ALIGNMENT_CENTER, 400)

class ActionBar extends Control:
	var label := ""
	var progress := 0.0

	func _init() -> void:
		mouse_filter = Control.MOUSE_FILTER_IGNORE
		visible = false

	func set_action(text_: String, p: float) -> void:
		var on := text_ != ""
		if on != visible:
			visible = on
		if on and (text_ != label or absf(p - progress) > 0.002):
			label = text_; progress = p
			queue_redraw()

	func _draw() -> void:
		UiKit.text(self, "body", Vector2(-200, 0), label, 15, UiTheme.INK, HORIZONTAL_ALIGNMENT_CENTER, 400)
		var w := 240.0
		draw_rect(Rect2(-w * 0.5 - 1.0, 25.0, w + 2.0, 6.0), Color(0, 0, 0, 0.6))
		UiKit.bar(self, Rect2(-w * 0.5, 26.0, w, 4.0), progress, UiTheme.ACCENT, Color(1, 1, 1, 0.1))
