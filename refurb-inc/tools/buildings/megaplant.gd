extends RefCounted
## Tier 7 — Refurb Inc. Campus, North Valley. A 120 x 180 m sawtooth-roofed super-hall with north-light glazing,
## 16 docks, automation gantries, a glass control pod; HQ tower with skybridge and crown sign; solar car ports,
## lake with fountain, roundabout, gatehouses and a wind turbine on the ridge.

const OUT := "res://assets/generated/megaplant/"


static func build() -> void:
	var root := Site.make_root("megaplant")
	Site.environment(root, "epic", 0.22)
	var k := BuildKit.new(root, OUT, 707)
	var w := 120.0
	var d := 180.0
	var eave := 14.0
	var hw := w * 0.5
	var hd := d * 0.5
	var docks_x: Array = []
	for i in 16:
		docks_x.append(-56.0 + i * 4.2)
	var apron := Rect2(-58.6, hd, 68.2, 22.0 + 16.0)
	Site.ground(k, 650.0, 0.0, -0.12, [apron])
	k.hills(520.0, 22, -3.0)
	# ---------------------------------------------------------------- hall walls
	k.box(Vector3(w, 0.2, d), Vector3(0, -0.1, 0), "epoxy_light")
	var t := 0.25
	var fops := []
	for x in docks_x:
		fops.append({"at": hw - float(x), "w": 3.0, "h": 3.2})
	for x in [16.0, 23.0]:
		fops.append({"at": hw - x, "w": 5.0, "h": 6.0})
	fops.append({"at": hw - 30.0, "w": 1.2, "h": 2.3})
	var sides := {
		"front": [Vector2(hw, hd), Vector2(-hw, hd), fops],
		"back": [Vector2(-hw, -hd), Vector2(hw, -hd), [{"at": 20.0, "w": 1.0, "h": 2.1}, {"at": 60.0, "w": 1.0, "h": 2.1}, {"at": 100.0, "w": 1.0, "h": 2.1}]],
		"left": [Vector2(-hw, hd), Vector2(-hw, -hd), [{"at": 45.0, "w": 1.0, "h": 2.1}, {"at": 135.0, "w": 1.0, "h": 2.1}]],
		"right": [Vector2(hw, -hd), Vector2(hw, hd), [{"at": 45.0, "w": 1.0, "h": 2.1}, {"at": 135.0, "w": 1.0, "h": 2.1}]],
	}
	for s in sides:
		var sd: Array = sides[s]
		var lows: Array = []
		for o in sd[2]:
			lows.append(o)
		k.wall(sd[0], sd[1], 12.0, t, "panel_silver", lows, 0.0, "liner_white")
		k.wall(sd[0], sd[1], 2.0, t, "panel_anthracite", [], 12.0, "liner_white")
	for side in [[Vector3(w + 0.4, 0.9, 0.06), Vector3(0, 11.5, hd + 0.16)], [Vector3(w + 0.4, 0.9, 0.06), Vector3(0, 11.5, -hd - 0.16)],
			[Vector3(0.06, 0.9, d + 0.4), Vector3(-hw - 0.16, 11.5, 0)], [Vector3(0.06, 0.9, d + 0.4), Vector3(hw + 0.16, 11.5, 0)]]:
		k.box(side[0], side[1], "clad_amber", Vector3.ZERO, false)
	# ---------------------------------------------------------------- sawtooth roof with north-light glazing
	var tooth := 12.0
	var rise := 4.5
	var n_teeth := int(d / tooth)
	var slope_len := sqrt(tooth * tooth + rise * rise)
	var ang := rad_to_deg(atan2(rise, tooth))
	for i in n_teeth:
		var z0 := -hd + i * tooth
		k.box(Vector3(w + 0.6, 0.12, slope_len), Vector3(0, eave + rise * 0.5 + 0.06, z0 + tooth * 0.5), "roof_sheet", Vector3(-ang, 0, 0), false)
		k.box(Vector3(w - 0.2, 0.02, slope_len - 0.3), Vector3(0, eave + rise * 0.5 - 0.04, z0 + tooth * 0.5), "liner_white", Vector3(-ang, 0, 0), false)
		k.box(Vector3(w, rise - 0.2, 0.04), Vector3(0, eave + rise * 0.5, z0 + tooth - 0.05), "glass", Vector3.ZERO, false)
		for m in 21:
			k.box(Vector3(0.12, rise, 0.14), Vector3(-hw + m * (w / 20.0), eave + rise * 0.5, z0 + tooth - 0.05), "alu_dark", Vector3.ZERO, false)
		for sx in [-hw - 0.12, hw + 0.12]:
			k.rprism(Vector3(tooth, rise, 0.25), Vector3(sx, eave + rise * 0.5, z0 + tooth * 0.5), "panel_anthracite", Vector3(0, 90, 0))
		# valley truss + gutter
		k.i_beam(Vector3(-hw, eave - 0.3, z0), Vector3(hw, eave - 0.3, z0), 0.9, 0.35, "steel_white")
		k.box(Vector3(w, 0.3, 0.5), Vector3(0, eave + 0.05, z0), "alu_dark", Vector3.ZERO, false)
		for x in [-36.0, -12.0, 12.0, 36.0]:
			k.i_beam(Vector3(x, 0, z0 if i > 0 else z0 + 0.5), Vector3(x, eave - 0.3, z0 if i > 0 else z0 + 0.5), 0.6, 0.35, "steel_white")
			k.box(Vector3(1.0, 1.2, 1.0), Vector3(x, 0.6, z0 if i > 0 else z0 + 0.5), "steel_yellow")
		# rafters following the slope
		for x in [-48.0, -24.0, 0.0, 24.0, 48.0]:
			k.beam(Vector3(x, eave - 0.1, z0), Vector3(x, eave + rise - 0.2, z0 + tooth - 0.1), Vector2(0.25, 0.45), "steel_white")
	# Solar on the sloped faces (they face the sun)
	for i in n_teeth:
		var z0 := -hd + i * tooth
		k.box(Vector3(w - 4.0, 0.06, slope_len - 2.0), Vector3(0, eave + rise * 0.5 + 0.2, z0 + tooth * 0.5), "solar", Vector3(-ang, 0, 0), false)
	# ---------------------------------------------------------------- automation gantries (robot-ready spines)
	for gx in [-24.0, 24.0]:
		for i in 14:
			var gz := -hd + 12.0 + i * 12.0
			k.box(Vector3(0.4, 6.0, 0.4), Vector3(gx - 1.2, 3.0, gz), "steel_yellow")
			k.box(Vector3(0.4, 6.0, 0.4), Vector3(gx + 1.2, 3.0, gz), "steel_yellow")
			k.box(Vector3(2.8, 0.3, 0.3), Vector3(gx, 6.0, gz), "steel_yellow", Vector3.ZERO, false)
		for s in [-1, 1]:
			k.i_beam(Vector3(gx + s * 1.2, 6.2, -hd + 12.0), Vector3(gx + s * 1.2, 6.2, hd - 24.0), 0.5, 0.3, "steel_grey")
		k.box(Vector3(2.0, 0.08, d - 36.0), Vector3(gx, 6.5, -6.0), "grating", Vector3.ZERO, false)
		k.box(Vector3(1.6, 0.04, d - 36.0), Vector3(gx, 6.0, -6.0), "rubber", Vector3.ZERO, false)
	# Floor: walkways and zones
	for x in [-6.0, -4.0, 4.0, 6.0]:
		k.box(Vector3(0.15, 0.01, d - 2.0), Vector3(x, 0.006, 0), "line_green" if absf(x) < 5 else "line_yellow", Vector3.ZERO, false)
	_zone(k, Vector3(-24.0, 0, hd - 9.0), Vector2(68.0, 10.0), "GOODS IN", "line_yellow")
	_zone(k, Vector3(36.0, 0, hd - 9.0), Vector2(30.0, 10.0), "DISPATCH", "line_green")
	_zone(k, Vector3(-36.0, 0, -40.0), Vector2(36.0, 60.0), "AUTOMATED TEST & IMAGING", "line_yellow")
	_zone(k, Vector3(36.0, 0, -40.0), Vector2(36.0, 60.0), "REPAIR CELLS", "line_yellow")
	_zone(k, Vector3(0, 0, -80.0), Vector2(110.0, 12.0), "HIGH-BAY STOCK", "line_red")
	# Lights (high-bays batched; a handful of real lights) + fire exits
	for i in 14:
		for j in 9:
			var p := Vector3(-48.0 + j * 12.0, eave - 0.8, -hd + 6.0 + i * 12.0)
			k.cyl(0.45, 0.18, p, "alu_dark", Vector3.ZERO, 14)
			k.cyl(0.4, 0.02, p + Vector3(0, -0.1, 0), "led_panel", Vector3.ZERO, 14)
	for i in 4:
		for x in [-30.0, 30.0]:
			k.omni(Vector3(x, eave - 2.0, -hd + 25.0 + i * 45.0), 6.0, 46.0, Color(0.96, 0.98, 1.0))
		k.omni(Vector3(0, eave - 2.0, -hd + 25.0 + i * 45.0), 4.0, 36.0, Color(0.96, 0.98, 1.0))
	for z in [-60.0, 0.0, 60.0]:
		for sx in [-hw + 0.3, hw - 0.3]:
			k.box(Vector3(0.06, 0.35, 0.8), Vector3(sx, 2.8, z), "emit_green", Vector3.ZERO, false)
	# Glass control pod inside the front-right corner (two storeys)
	var pc := Vector3(48.0, 0, hd - 18.0)
	k.box(Vector3(20.0, 0.25, 14.0), pc + Vector3(0, 3.8, 0), "plaster_white")
	for s in [-1, 1]:
		k.box(Vector3(20.0, 7.4, 0.04), pc + Vector3(0, 3.7, s * 7.0), "glass", Vector3.ZERO, false)
	k.box(Vector3(0.04, 7.4, 14.0), pc + Vector3(-10.0, 3.7, 0), "glass", Vector3.ZERO, false)
	k.wall(Vector2(pc.x - 10.0, pc.z + 7.0), Vector2(pc.x - 10.0, pc.z - 7.0), 3.6, 0.1, "glass", [{"at": 3.0, "w": 1.6, "h": 2.4}])
	k.box(Vector3(20.2, 0.3, 14.2), pc + Vector3(0, 7.6, 0), "panel_anthracite")
	for x in range(-8, 9, 4):
		k.box(Vector3(0.12, 7.4, 0.12), pc + Vector3(x, 3.7, 7.02), "alu_dark", Vector3.ZERO, false)
	k.box(Vector3(18.0, 0.04, 0.4), pc + Vector3(0, 3.6, 0), "led_panel", Vector3.ZERO, false)
	k.box(Vector3(18.0, 0.04, 0.4), pc + Vector3(0, 7.4, 0), "led_panel", Vector3.ZERO, false)
	for i in 3:
		k.box(Vector3(4.0, 2.2, 0.08), pc + Vector3(-6.0 + i * 6.0, 6.0, -6.9), "glass_dark", Vector3.ZERO, false)   # wall screens
	k.omni(pc + Vector3(0, 3.0, 0), 1.8, 14.0, Color(1.0, 0.97, 0.93))
	k.company_sign(pc + Vector3(0, 8.4, 7.1), Vector3.ZERO, 160, Color(1.0, 0.76, 0.3), 0.01)
	# Façade branding
	k.box(Vector3(9.0, 9.0, 0.2), Vector3(-40.0, 7.0, hd + 0.3), "panel_amber", Vector3.ZERO, false)
	k.label("R", Vector3(-40.0, 7.0, hd + 0.42), Vector3.ZERO, 900, Color.WHITE, 0.008)
	k.company_sign(Vector3(-4.0, 7.0, hd + 0.32), Vector3.ZERO, 600, Color(0.12, 0.14, 0.2), 0.016)
	k.company_sign(Vector3(-hw - 0.32, 7.0, 0), Vector3(0, -90, 0), 600, Color(0.12, 0.14, 0.2), 0.016)
	# Docks apron + level doors
	k.dock_apron(apron.position.x, apron.end.x, hd, 22.0, 16.0)
	for i in 2:
		var x: float = [16.0, 23.0][i]
		var lv := k.roller_door("LevelDoor%d" % (i + 1), Vector3(x, 0, hd + 0.14), 0, 5.0, 6.0, i == 0)
		k.door_switch(Vector3(x + 3.0, 1.35, hd - 0.14), 180, lv)
	# ---------------------------------------------------------------- HQ tower + skybridge
	var tc := Vector3(46.0, 0, hd + 22.0)
	var floors := 7
	var fh := 3.8
	var th := floors * fh
	k.box(Vector3(24.0, 0.2, 24.0), tc + Vector3(0, -0.1, 0), "polished")
	for f in floors:
		var y := f * fh
		for s in [[Vector3(24.0, fh - 0.9, 0.04), Vector3(0, y + 0.45 + (fh - 0.9) * 0.5, 12.0)], [Vector3(24.0, fh - 0.9, 0.04), Vector3(0, y + 0.45 + (fh - 0.9) * 0.5, -12.0)],
				[Vector3(0.04, fh - 0.9, 24.0), Vector3(12.0, y + 0.45 + (fh - 0.9) * 0.5, 0)], [Vector3(0.04, fh - 0.9, 24.0), Vector3(-12.0, y + 0.45 + (fh - 0.9) * 0.5, 0)]]:
			k.box(s[0], tc + s[1], "glass_blue", Vector3.ZERO, f == 0)
		k.box(Vector3(24.3, 0.9, 24.3), tc + Vector3(0, y + fh - 0.45 + 0.45, 0), "panel_white")
		if f > 0:
			k.omni(tc + Vector3(0, y + fh - 1.0, 0), 1.2, 16.0, Color(1.0, 0.97, 0.93))
			for r in 3:
				k.box(Vector3(18.0, 0.04, 0.4), tc + Vector3(0, y + fh - 0.05, -6.0 + r * 6.0), "led_panel", Vector3.ZERO, false)
	for cx in [-12.0, 12.0]:
		for cz in [-12.0, 12.0]:
			k.box(Vector3(0.6, th + 1.5, 0.6), tc + Vector3(cx, (th + 1.5) * 0.5, cz), "panel_anthracite")
	for i in 12:
		k.box(Vector3(0.12, th, 0.5), tc + Vector3(-11.0 + i * 2.0, th * 0.5, 12.3), "panel_amber", Vector3.ZERO, false)
	k.box(Vector3(24.6, 2.5, 24.6), tc + Vector3(0, th + 1.25, 0), "panel_anthracite")
	k.company_sign(tc + Vector3(0, th + 1.25, 12.35), Vector3.ZERO, 300, Color(1.0, 0.76, 0.3), 0.012)
	k.company_sign(tc + Vector3(-12.35, th + 1.25, 0), Vector3(0, -90, 0), 300, Color(1.0, 0.76, 0.3), 0.012)
	k.box(Vector3(8.0, 0.25, 5.0), tc + Vector3(0, 4.0, 15.0), "panel_white", Vector3.ZERO, false)
	k.box(Vector3(8.0, 0.04, 5.0), tc + Vector3(0, 3.86, 15.0), "led_warm", Vector3.ZERO, false)
	k.box(Vector3(6.0, 0.04, 0.4), tc + Vector3(-3.0, 0.5, 10.0), "brand", Vector3.ZERO, false)
	# skybridge (glass tube on columns) from tower back to the hall front at first-floor level
	var bz0 := hd + 0.3
	var bz1 := tc.z - 12.0
	var bx := 46.0
	k.box(Vector3(4.0, 0.3, bz1 - bz0), Vector3(bx, 6.0, (bz0 + bz1) * 0.5), "panel_white")
	k.box(Vector3(4.0, 0.3, bz1 - bz0), Vector3(bx, 9.6, (bz0 + bz1) * 0.5), "panel_white", Vector3.ZERO, false)
	for s in [-1, 1]:
		k.box(Vector3(0.04, 3.3, bz1 - bz0), Vector3(bx + s * 2.0, 7.8, (bz0 + bz1) * 0.5), "glass", Vector3.ZERO, false)
	for z in [bz0 + 3.0, bz1 - 3.0]:
		k.cyl(0.25, 6.0, Vector3(bx, 3.0, z), "steel_white", Vector3.ZERO, 12, true)
	# ---------------------------------------------------------------- yard, car parks, landscape
	k.box(Vector3(22.0, 0.1, 80.0), Vector3(-71.0, -0.05, hd + 40.0), "concrete_yard", Vector3.ZERO, false)
	k.box(Vector3(68.2, 0.1, 42.0), Vector3(apron.get_center().x, -0.05, hd + 38.0 + 21.0), "concrete_yard", Vector3.ZERO, false)
	k.box(Vector3(20.0, 0.1, 80.0), Vector3(20.0, -0.05, hd + 40.0), "concrete_yard", Vector3.ZERO, false)
	for i in 8:
		k.hgv_trailer(Vector3(-72.0, 0, hd + 16.0 + i * 7.0), 90 + 0.0, ["paint_white", "clad_amber", "paint_white", "clad_blue"][i % 4], "REFURB INC." if i % 4 == 1 else "")
	for i in 3:
		k.tractor_unit(Vector3(-40.0 + i * 8.0, 0, hd + 70.0), 0, ["car_red", "car_blue", "car_white"][i])
	# Tower plaza: paving, water feature, trees, flags
	k.box(Vector3(46.0, 0.1, 36.0), Vector3(tc.x, -0.05, tc.z + 28.0), "paving_light", Vector3.ZERO, false)
	k.box(Vector3(20.0, 0.3, 8.0), Vector3(tc.x, 0.1, tc.z + 26.0), "kerb")
	k.box(Vector3(19.2, 0.05, 7.2), Vector3(tc.x, 0.24, tc.z + 26.0), "water", Vector3.ZERO, false)
	for i in 7:
		k.cyl(0.05, 2.0, Vector3(tc.x - 9.0 + i * 3.0, 1.2, tc.z + 26.0), "paint_white", Vector3.ZERO, 6)
	for i in 6:
		k.planter(Vector3(tc.x - 18.0 + i * 7.2, 0, tc.z + 36.0), Vector3(3.0, 0.6, 3.0), true)
	for i in 7:
		k.flagpole(Vector3(tc.x - 18.0 + i * 3.0, 0, tc.z + 43.0), ["brand", "paint_white"][i % 2], 12.0)
	# Staff car park with solar car ports (east)
	k.box(Vector3(90.0, 0.1, 150.0), Vector3(125.0, -0.05, 15.0), "tarmac_new", Vector3.ZERO, false)
	for r in 6:
		var rz := -45.0 + r * 22.0
		for half in 2:
			var ox := 84.0 + half * 44.0
			k.parking_bays(Vector3(ox, 0, rz), 15, Vector3.RIGHT, 2.5, 5.0, "line_white", {13: "ev", 14: "ev"})
			k.solar_carport(Vector3(ox, 0, rz + 2.5), 37.5, 0)
			for i in 15:
				if k.rng.randf() < 0.5:
					k.car(Vector3(ox + 1.25 + i * 2.5, 0, rz + 2.6), 180 if k.rng.randf() < 0.5 else 0)
	# Lake with reeds and a fountain (west)
	k.box(Vector3(70.0, 0.04, 40.0), Vector3(-140.0, -0.1, 40.0), "water", Vector3.ZERO, false)
	k.box(Vector3(72.0, 0.02, 42.0), Vector3(-140.0, -0.14, 40.0), "soil", Vector3.ZERO, false)
	for i in 40:
		var a := k.rng.randf() * TAU
		k.shrub(Vector3(-140.0 + cos(a) * 36.0, 0, 40.0 + sin(a) * 21.0), 0.6)
	k.cyl(0.15, 6.0, Vector3(-140.0, 3.0, 40.0), "glass", Vector3.ZERO, 8)
	k.sphere(1.2, Vector3(-140.0, 6.0, 40.0), "glass", Vector3(1, 0.6, 1), 10)
	# Wind turbine on the ridge (rotor animated)
	var wt := Vector3(-170.0, 0, -170.0)
	k.cyl(2.2, 80.0, wt + Vector3(0, 40.0, 0), "paint_white", Vector3.ZERO, 16, true, 0.5)
	k.box(Vector3(3.0, 3.2, 10.0), wt + Vector3(0, 81.6, 1.5), "paint_white", Vector3.ZERO, false)
	# Roundabout on the main road with landscaped island + entrance totem
	var road_z := hd + 100.0
	k.road_x(road_z, -400, 400, 9.0, 2.5, 3.0, false)
	k.cyl(22.0, 0.1, Vector3(90.0, -0.05, road_z), "tarmac", Vector3.ZERO, 40)
	k.cyl(12.0, 0.35, Vector3(90.0, 0.1, road_z), "kerb", Vector3.ZERO, 40)
	k.cyl(11.5, 0.3, Vector3(90.0, 0.2, road_z), "grass", Vector3.ZERO, 40)
	k.planter(Vector3(90.0, 0.35, road_z), Vector3(4.0, 0.4, 4.0), true)
	k.box(Vector3(2.0, 8.0, 2.0), Vector3(-10.0, 4.0, road_z - 10.0), "panel_anthracite")
	k.box(Vector3(2.02, 2.0, 2.02), Vector3(-10.0, 6.6, road_z - 10.0), "panel_amber", Vector3.ZERO, false)
	k.company_sign(Vector3(-10.0, 3.4, road_z - 8.98), Vector3(0, 0, 90), 180, Color.WHITE, 0.012)
	# Perimeter fence, gatehouses, lamp columns, tree belts
	k.palisade(Vector2(-120, hd + 90), Vector2(-48, hd + 90))
	k.palisade(Vector2(-30, hd + 90), Vector2(10, hd + 90))
	k.palisade(Vector2(28, hd + 90), Vector2(170, hd + 90))
	k.palisade(Vector2(-120, -hd - 40), Vector2(-120, hd + 90))
	k.palisade(Vector2(170, hd + 90), Vector2(170, -hd - 40))
	k.palisade(Vector2(-120, -hd - 40), Vector2(170, -hd - 40))
	k.gatehouse(Vector3(-52.0, 0, hd + 88.0), 0, 10.0)
	k.gatehouse(Vector3(32.0, 0, hd + 88.0), 0, 10.0)
	k.box(Vector3(20.0, 0.1, 12.0), Vector3(-39.0, -0.05, hd + 94.0), "tarmac_new", Vector3.ZERO, false)
	k.box(Vector3(20.0, 0.1, 12.0), Vector3(19.0, -0.05, hd + 94.0), "tarmac_new", Vector3.ZERO, false)
	k.box(Vector3(14.0, 0.1, 60.0), Vector3(19.0, -0.05, hd + 62.0), "tarmac_new", Vector3.ZERO, false)
	for i in 16:
		k.lamp_column(Vector3(-110.0 + i * 18.0, 0, hd + 86.0), 180, 12.0)
	for x in range(-300, 301, 14):
		if absf(x + 39) < 14 or absf(x - 19) < 14:
			continue
		k.tree(Vector3(x + k.rng.randf_range(-3, 3), 0, hd + 93.0 + k.rng.randf_range(-1.5, 1.5)), k.rng.randf_range(8.0, 13.0))
	k.treeline(Vector3(-260, 0, -hd - 70), Vector3(260, 0, -hd - 70), 10.0, 11.0, 19.0)
	k.treeline(Vector3(-250, 0, -hd - 60), Vector3(-250, 0, hd + 80), 11.0, 10.0, 18.0)
	k.treeline(Vector3(220, 0, -hd - 60), Vector3(220, 0, hd + 80), 11.0, 10.0, 18.0)
	k.blocker(Vector3(300, 6, 0.5), Vector3(25, 3, hd + 90.5))
	k.finish()
	# ---------------------------------------------------------------- animated turbine rotor (node)
	var rotor := Node3D.new()
	rotor.name = "TurbineRotor"
	rotor.position = wt + Vector3(0, 81.6, 6.8)
	rotor.set_script(load("res://scripts/world/spinner.gd"))
	rotor.set("axis", Vector3.FORWARD)
	rotor.set("rpm", 11.0)
	k.nodes.add_child(rotor)
	k.node_box(rotor, Vector3(1.6, 1.6, 2.4), Vector3.ZERO, "paint_white")
	for i in 3:
		var arm := Node3D.new()
		arm.rotation_degrees.z = i * 120.0
		rotor.add_child(arm)
		k.node_box(arm, Vector3(1.6, 34.0, 0.35), Vector3(0, 17.5, 0), "paint_white", Vector3(0, 8, 0))
	# ---------------------------------------------------------------- docks & wiring
	var vd := Site.vehicles(root,
		[Vector3(400, 0, road_z - 2.2), Vector3(-30, 0, road_z - 2.2), Vector3(-39, 0, hd + 86.0), Vector3(-39, 0, hd + 70.0), Vector3(-58, 0, hd + 52.0), Vector3(-74, 0, hd + 50.0), Vector3(-78, 0, hd + 50.0)],
		[],
		[Vector3(-26.0, 0, hd + 56.0), Vector3(-34.0, 0, hd + 72.0), Vector3(-39.0, 0, hd + 88.0), Vector3(-50.0, 0, road_z - 2.2), Vector3(-500, 0, road_z - 2.2)],
		[Vector3(400, 0, road_z + 2.2), Vector3(30, 0, road_z + 2.2), Vector3(19, 0, hd + 88.0), Vector3(19, 0, hd + 60.0)],
		[Vector3(19.0, 0, hd + 36.0), 180, true], [Vector3(19.0, 0, hd + 60.0), Vector3(19.0, 0, hd + 90.0), Vector3(8.0, 0, road_z + 2.2), Vector3(-500, 0, road_z + 2.2)],
		null)
	var dk: Node3D = vd.get_node("Docks")
	for i in docks_x.size():
		k.dock(dk, "Dock%d" % (i + 1), float(docks_x[i]), hd, "DockDoor%d" % (i + 1), -1.2, i + 1)
	Site.dressing_scene(root, "res://scenes/stations/kettle_corner.tscn", "KettleCorner", Vector3(pc.x + 8.8, 0, pc.z - 5.0), -90)
	var mk := Site.markers(root, Vector3(20.0, 0, 70.0), 0, Vector3(20.0, 1.0, 68.0), Vector3(-24.0, 0.02, 72.0), Vector3(30.0, 0.3, hd + 1.8))
	vd.set("parcel_drop", mk.ParcelDrop)
	var lay := {}
	for i in 4:
		lay["office_desk" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(pc.x - 6.0 + (i % 2) * 5.0, 0, pc.z - 3.5 + (i / 2) * 3.4), 0]
	for i in 12:
		lay["test_bench" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(-48.0 + (i % 6) * 2.0, 0, -18.0 + (i / 6) * 3.0), 0 if i < 6 else 180]
	for i in 6:
		lay["repair_bench" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(26.0 + (i % 3) * 2.4, 0, -18.0 + (i / 3) * 3.0), 0 if i < 3 else 180]
	for i in 4:
		lay["parts_shelf" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(35.0 + i * 1.4, 0, -18.0), 0]
	for i in 4:
		lay["grading_desk" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(10.0 + i * 1.8, 0, 20.0), 0]
	for i in 6:
		lay["packing_table" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(26.0 + (i % 3) * 2.4, 0, 70.0 + (i / 3) * 3.0), 180 if i < 3 else 0]
	for i in 4:
		lay["courier_shelf" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(34.0 + i * 1.4, 0, 82.0), 180]
	for i in 16:
		lay["metal_shelf" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(-14.0 + (i % 8) * 1.5, 0, 40.0 + (i / 8) * 1.6), 0 if i < 8 else 180]
	Site.layout(root, lay)
	var keep := [Rect2(-58.6, hd - 5.0, 68.2, 5.0), Rect2(13.0, hd - 4.0, 13.0, 4.0), Rect2(-7.0, -hd, 14.0, d), Rect2(pc.x - 10.4, pc.z - 7.4, 0.8, 14.8), Rect2(pc.x + 8.0, pc.z - 6.5, 2.0, 3.0)]
	for i in n_teeth:
		for x in [-36.0, -12.0, 12.0, 36.0]:
			keep.append(Rect2(x - 0.7, -hd + i * tooth - 0.7 + (0.5 if i == 0 else 0.0), 1.4, 1.4))
	for gx in [-24.0, 24.0]:
		for i in 14:
			keep.append(Rect2(gx - 1.6, -hd + 12.0 + i * 12.0 - 0.4, 3.2, 0.8))
	Site.area(root, [Rect2(-hw + 0.5, -hd + 0.5, w - 1.0, d - 1.0)], keep, Vector3(0, 2.0, hd + 80.0), Vector3(0, 10.0, 0))
	Site.save(root, "res://scenes/levels/megaplant/megaplant.tscn")


static func _zone(k: BuildKit, c: Vector3, size: Vector2, text: String, key: String) -> void:
	for s in [-1, 1]:
		k.box(Vector3(size.x, 0.01, 0.18), c + Vector3(0, 0.007, s * size.y * 0.5), key, Vector3.ZERO, false)
		k.box(Vector3(0.18, 0.01, size.y), c + Vector3(s * size.x * 0.5, 0.007, 0), key, Vector3.ZERO, false)
	var col: Color = {"line_yellow": Color(0.97, 0.8, 0.12), "line_green": Color(0.3, 0.75, 0.4), "line_red": Color(0.9, 0.25, 0.2)}.get(key, Color.WHITE)
	k.label(text, c + Vector3(0, 0.012, size.y * 0.5 - 1.2), Vector3(-90, 0, 0), 300, col, 0.012)
