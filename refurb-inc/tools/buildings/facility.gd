extends RefCounted
## Tier 6 — Refurb Inc. HQ, Innovation Way. The purpose-built flagship: three-storey HQ with a full-height glazed
## atrium, amber louvre fins, cantilevered upper floors, green roof and rooftop sign; reflecting-pool plaza;
## white production hall with solar roof, colour-coded zones, mezzanine, canteen and a branded feature wall.

const OUT := "res://assets/generated/facility/"


static func build() -> void:
	var root := Site.make_root("facility")
	Site.environment(root, "golden", 0.18)
	var k := BuildKit.new(root, OUT, 606)
	var hc := Vector3(0, 0, -5)
	var w := 50.0
	var d := 70.0
	var eave := 10.0
	var hw := w * 0.5
	var hd := d * 0.5
	var front := hc.z + hd            # 30
	var docks_x := [10.0, 14.2, 18.4]
	var apron := Rect2(8.0, front, 12.6, 18.0 + 14.0)
	Site.ground(k, 420.0, 320.0, -0.12, [apron])
	# ---------------------------------------------------------------- production hall
	k.box(Vector3(w, 0.2, d), hc + Vector3(0, -0.1, 0), "epoxy_light")
	var fops := []
	for x in docks_x:
		fops.append({"at": hw - float(x), "w": 3.0, "h": 3.2})
	fops.append({"at": hw - 22.6, "w": 3.6, "h": 4.5})
	fops.append({"at": hw + 9.0, "w": 3.0, "h": 3.0})      # opening into the HQ atrium
	k.portal_shed(hc, w, d, eave, 3.0, 10.0, {
		"wall": "panel_anthracite", "wall_lower": "panel_white", "lower_h": 8.0, "liner": "liner_white",
		"roof": "roof_sheet", "frame": "steel_white", "rooflights": 2, "spans": 2,
		"front": fops,
		"left": [{"at": 30.0, "w": 1.0, "h": 2.1}],
		"right": [{"at": 20.0, "w": 1.0, "h": 2.1}, {"at": 50.0, "w": 1.0, "h": 2.1}],
		"back": [{"at": 12.0, "w": 1.0, "h": 2.1}, {"at": 38.0, "w": 1.0, "h": 2.1}],
	})
	# Amber vertical accent fins along the right elevation & brand stripe
	for i in 14:
		k.box(Vector3(0.12, 8.0, 0.6), Vector3(hw + 0.36, 4.0, hc.z - 30.0 + i * 4.6), "panel_amber", Vector3.ZERO, false)
	k.box(Vector3(0.06, 0.6, d), Vector3(hw + 0.14, 8.3, hc.z), "clad_amber", Vector3.ZERO, false)
	k.box(Vector3(0.06, 0.6, d), Vector3(-hw - 0.14, 8.3, hc.z), "clad_amber", Vector3.ZERO, false)
	k.company_sign(Vector3(hw + 0.2, 6.0, hc.z - 4.0), Vector3(0, 90, 0), 520, Color(0.12, 0.14, 0.2), 0.009)
	# Solar roof
	for side in [-1, 1]:
		k.solar_array(Vector3(hc.x + side * hw * 0.5, eave + 0.55, hc.z), Vector2(hw - 3.0, d - 6.0), 3.0 * side, 0.0, 0.4)
	# Docks & level door
	k.dock_apron(apron.position.x, apron.end.x, front, 18.0, 14.0)
	var lvl := k.roller_door("LevelDoor", Vector3(22.6, 0, front + 0.12), 0, 3.6, 4.5, true)
	k.door_switch(Vector3(24.6, 1.35, front - 0.12), 180, lvl)
	# ---------------------------------------------------------------- hall interior
	var zones := [["TEST", "epoxy_blue", Rect2(-21.0, -22.0, 15.0, 13.0)], ["REPAIR", "epoxy_orange", Rect2(-4.0, -22.0, 13.0, 13.0)],
		["IMAGING", "epoxy_purple", Rect2(11.0, -22.0, 11.0, 13.0)], ["DISPATCH", "epoxy_green", Rect2(6.0, 12.0, 16.0, 12.0)],
		["STOCK", "epoxy_grey", Rect2(-22.0, 2.0, 12.0, 20.0)]]
	for z in zones:
		var r: Rect2 = z[2]
		k.box(Vector3(r.size.x, 0.012, r.size.y), Vector3(r.get_center().x, 0.006, r.get_center().y), z[1], Vector3.ZERO, false)
		k.label(z[0], Vector3(r.get_center().x, 0.016, r.position.y + 1.4), Vector3(-90, 0, 0), 300, Color(1, 1, 1, 0.85), 0.008)
	for x in [-24.0, 24.0]:
		pass
	k.box(Vector3(0.14, 0.01, 52.0), Vector3(-1.0 + 0.0, 0.007, 3.0), "line_yellow", Vector3(0, 90, 0), false)
	k.box(Vector3(0.14, 0.01, 52.0), Vector3(-1.0, 0.007, 5.0), "line_yellow", Vector3(0, 90, 0), false)
	# Linear LED lines
	for i in 7:
		var z := hc.z - 30.0 + i * 10.0
		k.box(Vector3(46.0, 0.08, 0.2), Vector3(0, eave - 0.4, z), "led_panel", Vector3.ZERO, false)
		k.box(Vector3(46.0, 0.1, 0.25), Vector3(0, eave - 0.32, z), "alu_dark", Vector3.ZERO, false)
	for i in 3:
		for x in [-12.0, 12.0]:
			k.omni(Vector3(x, eave - 1.5, hc.z - 22.0 + i * 20.0), 3.6, 24.0, Color(0.97, 0.98, 1.0))
	# Column guards (internal column line x=0)
	for i in 8:
		k.box(Vector3(0.8, 1.0, 0.8), Vector3(0, 0.5, clampf(hc.z - hd + 10.0 * i, hc.z - hd + 0.35, front - 0.35)), "steel_yellow")
	# Mezzanine along the back wall: steel frame, grating deck, glass balustrade, stair
	var mz0 := hc.z - hd
	var mz1 := mz0 + 10.0
	var mh := 4.0
	k.box(Vector3(w - 0.6, 0.25, 10.0), Vector3(0, mh, (mz0 + mz1) * 0.5), "grating")
	k.box(Vector3(w - 0.6, 0.3, 0.3), Vector3(0, mh - 0.25, mz1), "steel_white", Vector3.ZERO, false)
	for i in 10:
		var x := -22.5 + i * 5.0
		k.box(Vector3(0.3, mh, 0.3), Vector3(x, mh * 0.5, mz1 - 0.3), "steel_white")
	k.box(Vector3(w - 6.0, 1.1, 0.03), Vector3(-3.0, mh + 0.68, mz1 - 0.02), "glass", Vector3.ZERO, true)
	k.box(Vector3(w - 6.0, 0.06, 0.08), Vector3(-3.0, mh + 1.25, mz1 - 0.02), "alu", Vector3.ZERO, false)
	for i in 14:
		k.box(Vector3(1.6, 0.18, 0.3), Vector3(23.3, 0.1 + i * 0.285, mz1 + 3.6 - i * 0.28), "steel_white", Vector3.ZERO, false)
	k.beam(Vector3(22.5, 1.0, mz1 + 3.6), Vector3(22.5, mh + 1.0, mz1), Vector2(0.05, 0.05), "alu")
	# Glass meeting pods on the mezzanine
	for i in 3:
		var cx := -18.0 + i * 9.0
		var pc := Vector3(cx, mh + 0.13, mz0 + 4.0)
		for s in [-1, 1]:
			k.box(Vector3(6.0, 2.8, 0.04), pc + Vector3(0, 1.4, s * 2.6), "glass", Vector3.ZERO, false)
			k.box(Vector3(0.04, 2.8, 5.2), pc + Vector3(s * 3.0, 1.4, 0), "glass", Vector3.ZERO, false)
		k.box(Vector3(6.0, 0.06, 5.2), pc + Vector3(0, 2.8, 0), "alu_dark", Vector3.ZERO, false)
		k.box(Vector3(3.0, 0.05, 1.2), pc + Vector3(0, 0.75, 0), "timber", Vector3.ZERO, false)
		k.box(Vector3(0.1, 0.75, 0.1), pc + Vector3(0, 0.37, 0), "alu_dark", Vector3.ZERO, false)
	# Canteen under the mezzanine
	for r in 3:
		for c in 6:
			var tp := Vector3(-20.0 + c * 7.0, 0, mz0 + 2.5 + r * 2.8)
			k.box(Vector3(1.8, 0.04, 0.9), tp + Vector3(0, 0.74, 0), "paint_white")
			k.box(Vector3(0.08, 0.72, 0.08), tp + Vector3(0, 0.37, 0), "alu_dark", Vector3.ZERO, false)
			for s in [-1, 1]:
				k.box(Vector3(0.45, 0.45, 0.45), tp + Vector3(s * 0.45, 0.22, 0.75), ["clad_amber", "paint_white"][c % 2], Vector3.ZERO, false)
				k.box(Vector3(0.45, 0.45, 0.45), tp + Vector3(s * 0.45, 0.22, -0.75), ["clad_amber", "paint_white"][(c + 1) % 2], Vector3.ZERO, false)
		k.box(Vector3(46.0, 0.05, 0.3), Vector3(0, mh - 0.4, mz0 + 2.5 + r * 2.8), "led_warm", Vector3.ZERO, false)
	k.omni(Vector3(-8, 3.2, mz0 + 5.0), 1.8, 14.0, Color(1.0, 0.92, 0.8))
	k.omni(Vector3(10, 3.2, mz0 + 5.0), 1.8, 14.0, Color(1.0, 0.92, 0.8))
	# Branded feature wall above the mezzanine
	k.box(Vector3(30.0, 4.0, 0.15), Vector3(0, mh + 3.6, mz0 + 0.3), "brand_dark", Vector3.ZERO, false)
	k.box(Vector3(3.2, 3.2, 0.1), Vector3(-11.5, mh + 3.6, mz0 + 0.42), "panel_amber", Vector3.ZERO, false)
	k.label("R", Vector3(-11.5, mh + 3.6, mz0 + 0.5), Vector3.ZERO, 480, Color.WHITE, 0.006)
	k.company_sign(Vector3(2.0, mh + 4.1, mz0 + 0.42), Vector3.ZERO, 320, Color(1.0, 0.76, 0.3), 0.008)
	k.label("BUY IT.  FIX IT.  FLIP IT.  SCALE IT.", Vector3(2.0, mh + 2.6, mz0 + 0.42), Vector3.ZERO, 140, Color(0.92, 0.94, 0.97), 0.008)
	# Fire exits
	for p in [[Vector3(-hw + 0.2, 2.6, hc.z + 15.0), 90], [Vector3(hw - 0.2, 2.6, hc.z), -90], [Vector3(hw - 0.2, 2.6, hc.z - 30.0 + 20.0), -90]]:
		k.box(Vector3(0.6, 0.3, 0.06), p[0], "emit_green", Vector3(0, p[1], 0), false)
	# ---------------------------------------------------------------- HQ block
	var q0 := -hw
	var q1 := 7.0
	var qz0 := front
	var qz1 := front + 16.0
	var qh := 12.6
	var qc := Vector3((q0 + q1) * 0.5, 0, (qz0 + qz1) * 0.5)
	k.box(Vector3(q1 - q0, 0.2, qz1 - qz0), qc + Vector3(0, -0.1, 0), "polished")
	# Ground floor: recessed glazing; upper floors cantilever 2 m forward over the plaza
	k.window_band(Vector2(q1 - 0.2, qz1 - 1.0), Vector2(q0 + 0.2, qz1 - 1.0), 0.0, 4.2, 2.0, "alu_dark", "glass")
	k.wall(Vector2(q0, qz1 - 1.0), Vector2(q0, qz0), qh, 0.3, "timber")
	k.wall(Vector2(q1, qz0), Vector2(q1, qz1 + 2.0), qh, 0.3, "panel_anthracite", [{"at": 6.0, "w": 9.0, "h": 3.2, "sill": 0.9}])
	k.box(Vector3(q1 - q0, 0.45, qz1 - qz0 + 2.0), Vector3(qc.x, 4.4, qc.z + 1.0), "panel_white")
	k.box(Vector3(q1 - q0, 0.45, qz1 - qz0 + 2.0), Vector3(qc.x, 8.4, qc.z + 1.0), "panel_white")
	k.box(Vector3(q1 - q0 + 0.4, 0.5, qz1 - qz0 + 2.4), Vector3(qc.x, qh, qc.z + 1.0), "panel_anthracite")
	k.box(Vector3(q1 - q0 - 1.0, 0.15, qz1 - qz0 + 1.0), Vector3(qc.x, qh + 0.32, qc.z + 1.0), "grass", Vector3.ZERO, false)   # green roof
	for i in 30:
		k.shrub(Vector3(k.rng.randf_range(q0 + 1, q1 - 1), qh + 0.3, k.rng.randf_range(qz0 + 1, qz1 + 1)), 0.4)
	k.box(Vector3(q1 - q0 + 0.4, 0.8, 0.2), Vector3(qc.x, qh + 0.65, qz1 + 2.1), "panel_anthracite")
	for fl in [4.62, 8.62]:
		k.window_band(Vector2(q1 - 0.2, qz1 + 2.0), Vector2(q0 + 0.3, qz1 + 2.0), fl, fl + 3.55, 1.6, "alu_dark", "glass_blue")
		k.box(Vector3(q1 - q0, 0.25, qz1 - qz0 + 1.5), Vector3(qc.x, fl - 0.1, qc.z + 0.75), "plaster_white")
	# Atrium: full-height glazing over x[-14, -4]
	k.box(Vector3(10.0, qh - 0.3, 0.04), Vector3(-9.0, qh * 0.5, qz1 + 2.05), "glass", Vector3.ZERO, false)
	for i in 6:
		k.box(Vector3(0.08, qh - 0.3, 0.12), Vector3(-14.0 + i * 2.0, qh * 0.5, qz1 + 2.06), "alu_dark", Vector3.ZERO, false)
	# Amber louvre fins over the upper floors (both sides of the atrium)
	for i in 34:
		var fx := q0 + 1.0 + i * 0.95
		if fx > -14.5 and fx < -3.5:
			continue
		k.box(Vector3(0.1, 7.8, 0.55), Vector3(fx, 8.4, qz1 + 2.45), "panel_amber", Vector3.ZERO, false)
	# Timber soffit + entrance canopy + revolving door
	k.box(Vector3(q1 - q0, 0.06, 3.0), Vector3(qc.x, 4.15, qz1 + 0.5), "timber", Vector3.ZERO, false)
	k.box(Vector3(10.0, 0.25, 6.0), Vector3(-9.0, 4.2, qz1 + 5.0), "panel_white", Vector3.ZERO, false)
	k.box(Vector3(10.0, 0.04, 6.0), Vector3(-9.0, 4.05, qz1 + 5.0), "led_warm", Vector3.ZERO, false)
	k.cyl(1.4, 2.6, Vector3(-9.0, 1.3, qz1 - 1.0), "glass", Vector3.ZERO, 20)
	k.cyl(1.45, 0.15, Vector3(-9.0, 2.65, qz1 - 1.0), "alu_dark", Vector3.ZERO, 20)
	# Rooftop sign
	k.box(Vector3(18.0, 2.0, 0.25), Vector3(-6.0, qh + 2.6, qz1 + 1.8), "brand_dark", Vector3.ZERO, false)
	k.company_sign(Vector3(-6.0, qh + 2.6, qz1 + 1.96), Vector3.ZERO, 300, Color(1.0, 0.76, 0.3), 0.01)
	for cx in [-13.0, -6.0, 1.0]:
		k.beam(Vector3(cx, qh + 0.5, qz1 + 1.8), Vector3(cx, qh + 1.6, qz1 + 1.8), Vector2(0.15, 0.15), "steel_black")
	# HQ interior: reception, feature wall, lounge, plants, lights
	k.box(Vector3(5.0, 1.1, 0.9), Vector3(-9.0, 0.55, qz0 + 6.0), "timber")
	k.box(Vector3(5.2, 0.06, 1.0), Vector3(-9.0, 1.13, qz0 + 6.0), "paint_white", Vector3.ZERO, false)
	k.box(Vector3(12.0, 4.0, 0.2), Vector3(-9.0, 2.0, qz0 + 0.4), "brand_dark", Vector3.ZERO, false)
	k.company_sign(Vector3(-9.0, 2.6, qz0 + 0.55), Vector3.ZERO, 200, Color(1.0, 0.76, 0.3), 0.007)
	k.label("Buy it. Fix it. Flip it. Scale it.", Vector3(-9.0, 1.7, qz0 + 0.55), Vector3.ZERO, 90, Color(0.9, 0.92, 0.95), 0.007)
	for i in 3:
		var sp := Vector3(-20.0 + i * 3.5, 0, qz0 + 10.0)
		k.box(Vector3(2.2, 0.45, 0.9), sp + Vector3(0, 0.22, 0), "panel_anthracite")
		k.box(Vector3(2.2, 0.5, 0.2), sp + Vector3(0, 0.6, -0.35), "panel_anthracite", Vector3.ZERO, false)
	for p in [Vector3(-23.0, 0, qz0 + 13.5), Vector3(4.0, 0, qz0 + 13.5), Vector3(-15.0, 0, qz0 + 2.0)]:
		k.cyl(0.35, 0.6, p + Vector3(0, 0.3, 0), "panel_anthracite", Vector3.ZERO, 14, true)
		k.sphere(0.6, p + Vector3(0, 1.2, 0), "foliage", Vector3(1, 1.4, 1), 10)
	for i in 4:
		k.box(Vector3(6.0, 0.04, 0.5), Vector3(-17.0 + i * 7.0, 4.12, qc.z), "led_panel", Vector3.ZERO, false)
	k.omni(Vector3(-9.0, 3.6, qc.z), 2.2, 16.0, Color(1.0, 0.96, 0.9))
	k.omni(Vector3(-20.0, 3.6, qc.z), 1.6, 12.0, Color(1.0, 0.96, 0.9))
	k.omni(Vector3(2.0, 3.6, qc.z), 1.6, 12.0, Color(1.0, 0.96, 0.9))
	for fl in [4.62, 8.62]:
		for i in 5:
			k.box(Vector3(3.2, 0.04, 1.4), Vector3(-21.0 + i * 6.0, fl + 0.75, qc.z + 2.0), "paint_white", Vector3.ZERO, false)
			k.box(Vector3(5.0, 0.04, 0.4), Vector3(-21.0 + i * 6.0, fl + 3.4, qc.z + 2.0), "led_panel", Vector3.ZERO, false)
		k.omni(Vector3(-9.0, fl + 3.0, qc.z + 3.0), 1.4, 18.0, Color(1.0, 0.97, 0.93))
	# ---------------------------------------------------------------- plaza
	k.box(Vector3(45.0, 0.1, 30.0), Vector3(-12.5, -0.05, qz1 + 15.0), "paving_light", Vector3.ZERO, false)
	k.box(Vector3(16.0, 0.35, 10.0), Vector3(-17.0, 0.12, qz1 + 13.0), "kerb")
	k.box(Vector3(15.2, 0.05, 9.2), Vector3(-17.0, 0.28, qz1 + 13.0), "water", Vector3.ZERO, false)
	for i in 5:
		k.cyl(0.05, 1.4, Vector3(-23.0 + i * 3.0, 1.0, qz1 + 13.0), "paint_white", Vector3.ZERO, 6)
		k.sphere(0.25, Vector3(-23.0 + i * 3.0, 1.75, qz1 + 13.0), "glass", Vector3(1, 0.6, 1), 8)
	for i in 4:
		k.planter(Vector3(-30.0 + i * 6.0, 0, qz1 + 22.0), Vector3(3.0, 0.6, 3.0), true)
	for i in 3:
		k.planter(Vector3(-2.0, 0, qz1 + 8.0 + i * 6.0), Vector3(2.4, 0.5, 2.4), i == 1)
	for i in 4:
		k.bench_seat(Vector3(-28.0 + i * 6.0, 0, qz1 + 18.0), 0)
	for i in 5:
		k.flagpole(Vector3(0.0 + i * 2.2, 0, qz1 + 27.0), ["brand", "paint_white", "brand", "paint_white", "brand"][i], 11.0)
	for i in 10:
		k.cyl(0.08, 0.9, Vector3(-34.0 + i * 3.5, 0.45, qz1 + 29.0), "alu_dark", Vector3.ZERO, 8)
		k.cyl(0.07, 0.08, Vector3(-34.0 + i * 3.5, 0.92, qz1 + 29.0), "led_warm", Vector3.ZERO, 8)
	# Totem
	k.box(Vector3(1.6, 6.0, 1.6), Vector3(-36.0, 3.0, qz1 + 28.0), "panel_anthracite")
	k.box(Vector3(1.62, 1.6, 1.62), Vector3(-36.0, 5.0, qz1 + 28.0), "panel_amber", Vector3.ZERO, false)
	k.label("R", Vector3(-36.0, 5.0, qz1 + 28.82), Vector3.ZERO, 260, Color.WHITE, 0.006)
	k.company_sign(Vector3(-36.0, 3.2, qz1 + 28.82), Vector3(0, 0, 90), 140, Color.WHITE, 0.008)
	# ---------------------------------------------------------------- yard, car park with solar car ports
	k.box(Vector3(20.0, 0.1, 30.0), Vector3(30.0, -0.05, front + 15.0), "concrete_yard", Vector3.ZERO, false)
	k.box(Vector3(12.6, 0.1, 18.0), Vector3(14.3, -0.05, front + 41.0), "concrete_yard", Vector3.ZERO, false)
	k.box(Vector3(4.0, 0.1, 50.0), Vector3(6.0, -0.05, front + 25.0), "concrete_yard", Vector3.ZERO, false)
	k.box(Vector3(46.0, 0.1, 80.0), Vector3(63.0, -0.05, hc.z + 20.0), "tarmac_new", Vector3.ZERO, false)
	for r in 4:
		var rz := hc.z - 10.0 + r * 15.0
		k.parking_bays(Vector3(44.0, 0, rz), 16, Vector3.RIGHT, 2.5, 5.0, "line_white", {14: "ev", 15: "ev", 13: "ev"} if r % 2 == 0 else {})
		k.solar_carport(Vector3(44.0, 0, rz + 2.5), 40.0, 0)
		for i in 16:
			if k.rng.randf() < 0.55:
				k.car(Vector3(45.25 + i * 2.5, 0, rz + 2.6), 180 if k.rng.randf() < 0.5 else 0)
	# Wildflower meadow and tree belts
	k.box(Vector3(60, 0.06, 40), Vector3(-60, -0.08, 20), "grass", Vector3.ZERO, false)
	for i in 40:
		k.shrub(Vector3(k.rng.randf_range(-85, -35), 0, k.rng.randf_range(0, 40)), k.rng.randf_range(0.3, 0.7))
	k.treeline(Vector3(-90, 0, -60), Vector3(110, 0, -60), 8.0, 10.0, 17.0)
	k.treeline(Vector3(-95, 0, -50), Vector3(-95, 0, 75), 8.0, 9.0, 15.0)
	k.treeline(Vector3(100, 0, -50), Vector3(100, 0, 70), 8.0, 9.0, 15.0)
	for x in range(-100, 101, 12):
		if absf(x - 26) < 10:
			continue
		k.tree(Vector3(x, 0, front + 50.0), k.rng.randf_range(7.0, 10.0), 0)
	k.road_x(front + 56.0, -220, 220, 8.0, 2.4, 2.0)
	k.box(Vector3(14.0, 0.1, 6.0), Vector3(26.0, -0.05, front + 49.0), "tarmac_new", Vector3.ZERO, false)
	k.box(Vector3(14.0, 0.1, 3.5), Vector3(-10.0, -0.05, front + 51.0), "tarmac_new", Vector3.ZERO, false)
	k.palisade(Vector2(19.0, front + 47.0), Vector2(30.0 - 10.0, front + 47.0))
	k.palisade(Vector2(32.5, front + 47.0), Vector2(100, front + 47.0))
	k.gatehouse(Vector3(36.0, 0, front + 45.0), 0, 7.0)
	k.hills(330.0, 18, -2.0)
	k.blocker(Vector3(160, 4, 0.5), Vector3(-20, 2, front + 48.0))
	k.finish()
	# ---------------------------------------------------------------- docks & wiring
	var vd := Site.vehicles(root,
		[Vector3(200, 0, front + 54.2), Vector3(32, 0, front + 54.2), Vector3(26, 0, front + 46.0), Vector3(28, 0, front + 38.0), Vector3(38, 0, front + 34.0), Vector3(42, 0, front + 34.0)],
		[],
		[Vector3(16.0, 0, front + 30.0), Vector3(22.0, 0, front + 40.0), Vector3(26.0, 0, front + 48.0), Vector3(18.0, 0, front + 54.2), Vector3(-260, 0, front + 54.2)],
		[Vector3(200, 0, front + 57.8), Vector3(0, 0, front + 57.8)], [Vector3(-10.0, 0, front + 51.0), -90], [Vector3(-260, 0, front + 57.8)],
		null)
	var dk: Node3D = vd.get_node("Docks")
	for i in docks_x.size():
		k.dock(dk, "Dock%d" % (i + 1), float(docks_x[i]), front, "DockDoor%d" % (i + 1), -1.2, i + 1)
	Site.dressing_scene(root, "res://scenes/stations/kettle_corner.tscn", "KettleCorner", Vector3(-22.0, 0, hc.z - hd + 9.0), 0)
	var mk := Site.markers(root, Vector3(-9.0, 0, 26.0), 0, Vector3(-9.0, 1.0, 24.0), Vector3(14.0, 0.02, 22.0), Vector3(-6.0, 0.3, qz1 - 2.0))
	vd.set("parcel_drop", mk.ParcelDrop)
	var lay := {
		"office_desk": [Vector3(-20.0, 0, qz0 + 3.0), 0], "office_desk_2": [Vector3(-17.0, 0, qz0 + 3.0), 0],
		"office_desk_3": [Vector3(1.0, 0, qz0 + 3.0), 0], "office_desk_4": [Vector3(4.0, 0, qz0 + 3.0), 0],
		"parts_shelf": [Vector3(-3.0, 0, -22.4), 0], "parts_shelf_2": [Vector3(-1.6, 0, -22.4), 0], "parts_shelf_3": [Vector3(-0.2, 0, -22.4), 0],
		"courier_shelf": [Vector3(20.5, 0, 22.5), 180], "courier_shelf_2": [Vector3(19.0, 0, 22.5), 180],
	}
	for i in 6:
		lay["test_bench" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(-19.5 + (i % 3) * 2.0 + (i / 3) * 6.8, 0, -18.0), 0]
	for i in 4:
		lay["repair_bench" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(-2.0 + (i % 2) * 2.2, 0, -16.5 + (i / 2) * 3.2), 0]
	for i in 3:
		lay["grading_desk" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(13.0 + i * 2.0, 0, -16.0), 0]
	for i in 3:
		lay["packing_table" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(9.0 + i * 2.4, 0, 18.0), 180]
	for i in 8:
		lay["metal_shelf" + ("" if i == 0 else "_%d" % (i + 1))] = [Vector3(-20.5 + (i / 4) * 5.0, 0, 5.0 + (i % 4) * 1.4), 90 if i < 4 else -90]
	Site.layout(root, lay)
	var keep := [Rect2(8.0, front - 5.0, 12.6, 5.0), Rect2(20.6, front - 3.0, 4.2, 3.0), Rect2(-1.6, -30.0, 2.4, 60.0), Rect2(-12.0, qz0 + 5.0, 6.0, 2.0), Rect2(-15.5, qz0, 13.0, 1.0), Rect2(-21.0, qz0 + 9.4, 9.0, 1.4), Rect2(-10.6, qz0 + 13.6, 3.2, 3.2)]
	for i in 8:
		keep.append(Rect2(-0.6, clampf(hc.z - hd + 10.0 * i, hc.z - hd + 0.35, front - 0.35) - 0.6, 1.2, 1.2))
	Site.area(root, [Rect2(-hw + 0.4, hc.z - hd + 10.6, w - 0.8, d - 11.0), Rect2(q0 + 0.4, qz0 + 0.4, q1 - q0 - 0.8, qz1 - qz0 - 1.8)], keep,
		Vector3(0, 2.0, front + 45.0), Vector3(0, 7.0, 0))
	Site.save(root, "res://scenes/levels/facility/facility.tscn")
