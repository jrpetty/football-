extends RefCounted
## Tier 2 — Unit 7, Railway Arches Lock-ups. Double-width end lock-up in a gated court under a brick viaduct.
## Rougher than an estate unit, a big step up from the garage: steel beams, corrugated roof, proper roller door.

const OUT := "res://assets/generated/lockup/"


static func build() -> void:
	var root := Site.make_root("lockup")
	Site.environment(root, "overcast", 0.05)
	var k := BuildKit.new(root, OUT, 202)
	Site.ground(k, 280.0, 200.0)
	var hw := 3.5
	var hd := 7.0
	var H := 3.4
	var Hb := 2.95
	var T := 0.215
	# ---------------------------------------------------------------- our unit
	k.box(Vector3(2 * hw + 2 * T, 0.2, 2 * hd + 2 * T), Vector3(0, -0.1, 0), "concrete_sealed")
	k.wall(Vector2(-hw - T * 0.5, -hd - T * 0.5), Vector2(hw + T * 0.5, -hd - T * 0.5), Hb, T, "block_grey", [], 0.0, "block_cream")
	k.wall(Vector2(-hw - T * 0.5, hd), Vector2(-hw - T * 0.5, -hd - T * 0.5), Hb, T, "block_grey", [], 0.0, "block_cream")
	k.wall(Vector2(hw + T * 0.5, -hd - T * 0.5), Vector2(hw + T * 0.5, hd), Hb, T, "block_grey", [], 0.0, "block_cream")
	# Mono-pitch: side walls rise toward the front (prism infill)
	for sx in [-hw - T * 0.5, hw + T * 0.5]:
		k.prism(Vector3(2 * hd + T, H - Hb, T), Vector3(sx, Hb + (H - Hb) * 0.5, 0), "block_grey", Vector3(0, 90, 0))
	k.wall(Vector2(hw + T, hd + T * 0.5), Vector2(-hw - T, hd + T * 0.5), H + 0.15, T, "block_grey", [{"at": hw + T, "w": 4.6, "h": 3.0}], 0.0, "block_cream")
	var pitch := rad_to_deg(atan2(H - Hb, 2 * hd))
	k.box(Vector3(2 * hw + 0.8, 0.06, 2 * hd + 0.9), Vector3(0, (H + Hb) * 0.5 + 0.12, 0.1), "corrugated_grey", Vector3(-pitch, 0, 0))
	k.box(Vector3(2 * hw + 0.7, 0.02, 2 * hd), Vector3(0, (H + Hb) * 0.5 + 0.06, 0), "galv", Vector3(-pitch, 0, 0), false)
	k.box(Vector3(2 * hw + 0.8, 0.25, 0.05), Vector3(0, H + 0.12, hd + T + 0.25), "alu_dark", Vector3.ZERO, false)
	# Steel roof beams inside
	for i in 4:
		var z := -hd + 1.75 + i * 3.5
		var y := Hb + (H - Hb) * ((z + hd) / (2 * hd)) - 0.15
		k.i_beam(Vector3(-hw, y, z), Vector3(hw, y, z), 0.25, 0.14, "steel_grey")
	# Dado and damp (less grimy than the garage)
	for side in [[Vector3(2 * hw, 0.6, 0.01), Vector3(0, 0.3, -hd + 0.006)], [Vector3(0.01, 0.6, 2 * hd), Vector3(-hw + 0.006, 0.3, 0)], [Vector3(0.01, 0.6, 2 * hd), Vector3(hw - 0.006, 0.3, 0)]]:
		k.box(side[0], side[1], "dado", Vector3.ZERO, false)
	k.decal("damp_patch", Vector3(-hw + 0.02, 0.8, -5.0), Vector3(1.6, 0.3, 1.4), 0, Color(1, 1, 1, 0.6), Vector3.RIGHT)
	k.decal("oil_stain", Vector3(0.6, 0.0, 3.0), Vector3(1.2, 0.4, 1.0), 40, Color(1, 1, 1, 0.55))
	k.decal("scuffs", Vector3(0, 0, 1.0), Vector3(4.0, 0.4, 5.0), 0, Color(1, 1, 1, 0.6))
	# Plywood office partition in the back-left corner with a window and doorway
	var ox := -0.9
	var oz := -4.4
	k.wall(Vector2(ox, -hd), Vector2(ox, oz), 2.4, 0.08, "plywood", [{"at": 1.3, "w": 1.4, "h": 1.0, "sill": 1.0}])
	k.window_band(Vector2(ox, -hd + 0.6), Vector2(ox, -hd + 2.0), 1.0, 2.0, 0.7, "alu", "glass")
	k.wall(Vector2(-hw, oz), Vector2(ox, oz), 2.4, 0.08, "plywood", [{"at": 1.6, "w": 0.9, "h": 2.05}])
	k.box(Vector3(0.95, 0.06, 0.1), Vector3(-hw + 1.6, 2.1, oz), "timber", Vector3.ZERO, false)
	k.label("OFFICE", Vector3(-hw + 1.6, 2.25, oz + 0.05), Vector3.ZERO, 64, Color(0.15, 0.15, 0.15), 0.003)
	# Strip lights
	for i in 3:
		for x in [-1.6, 1.6]:
			var sp := Vector3(x, Hb + (H - Hb) * ((-4.5 + i * 4.5 + hd) / (2 * hd)) - 0.3, -4.5 + i * 4.5)
			k.box(Vector3(1.5, 0.05, 0.16), sp, "paint_white", Vector3.ZERO, false)
			k.cyl(0.013, 1.42, sp + Vector3(0, -0.04, 0), "led_panel", Vector3(0, 0, 90), 8)
		k.omni(Vector3(0, 2.6, -4.5 + i * 4.5), 1.6, 7.5, Color(0.93, 0.96, 1.0), i == 1)
	# Front: roller door, sign board, security light, CCTV
	var door := k.roller_door("RollerDoor", Vector3(0, 0, hd + 0.05), 0, 4.6, 3.0, true)
	k.door_switch(Vector3(2.55, 1.35, hd - 0.03), 180, door)
	k.box(Vector3(4.2, 0.55, 0.04), Vector3(0, 3.33, hd + T + 0.04), "paint_white", Vector3.ZERO, false)
	k.company_sign(Vector3(-0.3, 3.33, hd + T + 0.07), Vector3.ZERO, 120, Color(0.12, 0.2, 0.42), 0.0035)
	k.box(Vector3(0.6, 0.55, 0.04), Vector3(1.75, 3.33, hd + T + 0.05), "car_red", Vector3.ZERO, false)
	k.label("7", Vector3(1.75, 3.33, hd + T + 0.08), Vector3.ZERO, 160, Color.WHITE, 0.0035)
	k.box(Vector3(0.3, 0.18, 0.2), Vector3(-2.9, 3.0, hd + T + 0.12), "alu_dark", Vector3(-20, 0, 0), false)
	k.cyl(0.05, 0.25, Vector3(2.9, 3.1, hd + T + 0.15), "paint_white", Vector3(70, 20, 0), 10)
	k.decal("rust_streak", Vector3(-2.4, 2.4, hd + T + 0.02), Vector3(0.5, 0.3, 1.2), 0, Color(1, 1, 1, 0.7), Vector3.BACK)
	# ---------------------------------------------------------------- neighbouring lock-ups
	var colours := ["steel_green", "steel_blue", "paint_white", "corrugated", "steel_orange", "steel_green", "steel_blue", "paint_white", "car_red"]
	for i in 9:
		var left := i < 6
		var cx: float = (-hw - T - 1.65 - i * 3.3) if left else (hw + T + 1.65 + (i - 6) * 3.3)
		var cz := hd - 3.0
		k.box(Vector3(3.3, 2.5, 6.0), Vector3(cx, 1.25, cz), "block_grey")
		k.box(Vector3(3.5, 0.12, 6.4), Vector3(cx, 2.56, cz + 0.1), "roof_felt")
		k.box(Vector3(2.4, 2.05, 0.06), Vector3(cx, 1.03, hd + 0.03), colours[i], Vector3.ZERO, false)
		k.box(Vector3(0.12, 0.06, 0.04), Vector3(cx, 1.0, hd + 0.08), "galv", Vector3.ZERO, false)
		var num := (6 - i) if left else (i + 2)
		k.label(str(num), Vector3(cx, 2.25, hd + 0.04), Vector3.ZERO, 120, Color(0.95, 0.95, 0.9), 0.003)
		if i in [1, 4, 7]:
			k.decal("rust_streak", Vector3(cx + 0.6, 1.4, hd + 0.08), Vector3(0.5, 0.3, 1.6), 0, Color(1, 1, 1, 0.8), Vector3.BACK)
	# ---------------------------------------------------------------- railway viaduct behind (Victorian brick arches)
	var vz := -14.5
	var vdepth := 9.0
	var deck := 10.0
	var span := 8.0
	var pier := 2.0
	for i in 15:
		var x0 := -60.0 + i * span
		k.box(Vector3(pier, deck, vdepth), Vector3(x0, deck * 0.5, vz), "brick_dark")
		var open_w := span - pier
		var xc := x0 + span * 0.5
		var r := open_w * 0.5
		var spring := 5.0
		# stepped arch infill (voussoirs approximated) + spandrel
		for sl in 10:
			var y0 := spring + sl * (r / 10.0)
			var y1 := y0 + r / 10.0
			var ym := (y0 + y1) * 0.5 - spring
			var half := sqrt(maxf(r * r - ym * ym, 0.0))
			var fill := r - half
			for sgn in [-1, 1]:
				k.box(Vector3(fill, y1 - y0, vdepth), Vector3(xc + sgn * (r - fill * 0.5), (y0 + y1) * 0.5, vz), "brick_dark", Vector3.ZERO, false)
		k.box(Vector3(open_w, deck - spring - r, vdepth), Vector3(xc, spring + r + (deck - spring - r) * 0.5, vz), "brick_dark", Vector3.ZERO, false)
		# Arch business fronts: some bricked up with shutters & signs
		if i % 3 == 1:
			k.box(Vector3(open_w, spring + r, 0.3), Vector3(xc, (spring + r) * 0.5, vz + vdepth * 0.5 - 0.6), "panel_anthracite", Vector3.ZERO, false)
			k.box(Vector3(3.6, 3.0, 0.08), Vector3(xc, 1.5, vz + vdepth * 0.5 - 0.42), "steel_grey", Vector3.ZERO, false)
			var biz: String = ["TYRES & EXHAUSTS", "ARCH 12 MOTORS", "MICRO-BREWERY", "BOXING GYM", "STONE & TILE"][(i / 3) % 5]
			k.box(Vector3(4.5, 0.7, 0.06), Vector3(xc, 3.6, vz + vdepth * 0.5 - 0.38), "paint_dark", Vector3.ZERO, false)
			k.label(biz, Vector3(xc, 3.6, vz + vdepth * 0.5 - 0.34), Vector3.ZERO, 96, Color(1.0, 0.85, 0.4), 0.004)
	k.box(Vector3(120, 1.2, 0.4), Vector3(0, deck + 0.6, vz + vdepth * 0.5 - 0.2), "brick_dark", Vector3.ZERO, false)
	k.box(Vector3(120, 0.15, 0.6), Vector3(0, deck + 1.25, vz + vdepth * 0.5 - 0.2), "kerb", Vector3.ZERO, false)
	k.box(Vector3(120, 0.4, vdepth), Vector3(0, deck + 0.2, vz), "gravel", Vector3.ZERO, false)
	for rz in [-1.5, 1.5]:
		k.box(Vector3(120, 0.12, 0.1), Vector3(0, deck + 0.46, vz + rz - 0.72), "galv", Vector3.ZERO, false)
		k.box(Vector3(120, 0.12, 0.1), Vector3(0, deck + 0.46, vz + rz + 0.72), "galv", Vector3.ZERO, false)
	for i in 7:
		var px := -54.0 + i * 18.0
		k.box(Vector3(0.3, 6.0, 0.3), Vector3(px, deck + 3.4, vz + 4.0), "steel_grey", Vector3.ZERO, false)
		k.box(Vector3(0.2, 0.2, 7.0), Vector3(px, deck + 6.0, vz + 1.0), "steel_grey", Vector3.ZERO, false)
	for rz in [-1.5, 1.5]:
		k.beam(Vector3(-60, deck + 5.6, vz + rz), Vector3(60, deck + 5.6, vz + rz), Vector2(0.02, 0.02), "steel_black")
	# ---------------------------------------------------------------- the court
	k.box(Vector3(46, 0.1, 19.5), Vector3(-4, -0.05, hd + 9.75), "concrete_stained", Vector3.ZERO, false)
	for p in [[Vector3(-12, 0, 12), Vector2(3, 2)], [Vector3(4, 0, 18), Vector2(4, 2.5)], [Vector3(-2, 0, 22), Vector2(2.5, 3)], [Vector3(10, 0, 11), Vector2(2, 2)]]:
		k.box(Vector3(p[1].x, 0.01, p[1].y), p[0] + Vector3(0, 0.003, 0), "tarmac", Vector3(0, k.rng.randf_range(-20, 20), 0), false)
	for p in [Vector3(-6, 0, 14), Vector3(3, 0, 21), Vector3(-15, 0, 19), Vector3(8, 0, 16)]:
		k.decal("puddle", p, Vector3(k.rng.randf_range(1.5, 3.0), 0.4, k.rng.randf_range(1.2, 2.4)), k.rng.randf_range(0, 180))
	k.decal("leaves", Vector3(-20, 0, 9), Vector3(4, 0.4, 3), 0)
	k.decal("leaves", Vector3(12, 0, 24), Vector3(4, 0.4, 3), 40)
	k.decal("tyre_marks", Vector3(0, 0, 13), Vector3(3, 0.4, 10), 0, Color(1, 1, 1, 0.5))
	for x in range(-25, 17, 2):
		k.box(Vector3(1.6, 0.05, 0.3), Vector3(x + k.rng.randf_range(-0.4, 0.4), -0.01, hd + 0.35), "grass", Vector3.ZERO, false)
	# Fence: chain-link sides and front, palisade gate (open) at the entrance
	k.chainlink(Vector2(-26, 26.5), Vector2(-26, hd))
	k.chainlink(Vector2(18, hd), Vector2(18, 26.5))
	k.chainlink(Vector2(-26, 26.5), Vector2(-5, 26.5))
	k.chainlink(Vector2(5, 26.5), Vector2(18, 26.5))
	k.palisade(Vector2(-12, 26.0), Vector2(-5.2, 26.0), 2.2, "steel_grey")
	k.box(Vector3(0.25, 2.6, 0.25), Vector3(-5.1, 1.3, 26.5), "steel_grey")
	k.box(Vector3(0.25, 2.6, 0.25), Vector3(5.1, 1.3, 26.5), "steel_grey")
	k.box(Vector3(2.4, 1.2, 0.05), Vector3(-7.5, 1.6, 25.9), "paint_white", Vector3.ZERO, false)
	k.label("RAILWAY ARCHES\nLOCK-UPS", Vector3(-7.5, 1.7, 25.86), Vector3(0, 180, 0), 72, Color(0.1, 0.1, 0.12), 0.0035)
	# Skip, abandoned van, pallets, CCTV pole, security light
	k.box(Vector3(3.6, 1.4, 1.8), Vector3(-18, 0.7, 20), "steel_yellow")
	k.box(Vector3(3.0, 0.4, 1.4), Vector3(-18, 1.5, 20), "cardboard", Vector3(0, 0, 6), false)
	k.pallet_stack(Vector3(-21.5, 0, 12.5), 6, 12)
	k.pallet_stack(Vector3(-22.0, 0, 14.0), 3, -20)
	k.box(Vector3(1.95, 2.0, 5.2), Vector3(-11.0, 1.15, 17.5), "paint_white")
	k.box(Vector3(1.9, 0.6, 1.0), Vector3(-11.0, 1.6, 20.6), "glass_dark", Vector3.ZERO, false)
	for sx in [-0.85, 0.85]:
		for sz in [-1.6, 1.8]:
			k.cyl(0.35, 0.24, Vector3(-11.0 + sx, 0.35, 17.5 + sz), "tyre", Vector3(0, 0, 90), 12)
	k.decal("rust_streak", Vector3(-10.02, 1.0, 16.0), Vector3(0.6, 0.3, 1.0), 0, Color(1, 1, 1, 0.7), Vector3.RIGHT)
	k.cyl(0.1, 6.0, Vector3(16.5, 3.0, 25.0), "galv", Vector3.ZERO, 8, true)
	k.box(Vector3(0.18, 0.18, 0.4), Vector3(16.5, 5.9, 24.7), "paint_white", Vector3(-20, 30, 0), false)
	for i in 4:
		k.car(Vector3(-22.0 + i * 2.7, 0, 23.5), 0)
	# ---------------------------------------------------------------- street outside
	k.road_x(31.0, -150, 150, 7.3, 2.0, 1.2)
	for lx in [-40.0, -8.0, 24.0, 56.0]:
		k.lamp_column(Vector3(lx, 0, 27.4), 180, 7.0)
	# Industrial frontage opposite: corrugated fence, sheds and trees
	k.box(Vector3(120, 2.6, 0.1), Vector3(0, 1.3, 36.5), "corrugated", Vector3.ZERO, false)
	for i in 6:
		k.box(Vector3(14.0, 6.0 + (i % 3), 12.0), Vector3(-45 + i * 18, 3.0 + (i % 3) * 0.5, 45), ["clad_goosewing", "corrugated_grey", "clad_blue"][i % 3], Vector3.ZERO, false)
	k.treeline(Vector3(-60, 0, 38), Vector3(60, 0, 38), 9.0, 6.0, 10.0)
	k.box(Vector3(160, 0.05, 30), Vector3(0, -0.06, -30), "grass", Vector3.ZERO, false)
	k.treeline(Vector3(-70, 0, -28), Vector3(70, 0, -28), 7.0, 9.0, 15.0)
	k.blocker(Vector3(30, 3, 0.3), Vector3(0, 1.5, 27.0))
	k.finish()
	# ---------------------------------------------------------------- dressing & wiring
	Site.dressing_scene(root, "res://scenes/stations/kettle_corner.tscn", "KettleCorner", Vector3(-hw + 0.3, 0, 5.4), 90)
	var mk := Site.markers(root, Vector3(0, 0, 4.0), 0, Vector3(0, 1.0, 3.0), Vector3(0, 0.02, 10.5), Vector3(2.0, 0.3, 9.2))
	Site.layout(root, {
		"office_desk": [Vector3(-2.2, 0, -6.6), 0],
		"test_bench": [Vector3(0.4, 0, -6.6), 0],
		"repair_bench": [Vector3(2.35, 0, -6.6), 0],
		"parts_shelf": [Vector3(3.25, 0, -5.0), -90],
		"grading_desk": [Vector3(3.12, 0, -3.2), -90],
		"packing_table": [Vector3(3.08, 0, -1.0), -90],
		"courier_shelf": [Vector3(3.2, 0, 5.8), -90],
		"metal_shelf": [Vector3(-3.22, 0, -2.6), 90],
		"metal_shelf_2": [Vector3(-3.22, 0, -1.2), 90],
		"metal_shelf_3": [Vector3(-3.22, 0, 0.2), 90],
		"test_bench_2": [Vector3(0.4, 0, -4.9), 180],
	})
	Site.area(root, [Rect2(-hw, oz, 2 * hw, hd - oz), Rect2(ox + 0.05, -hd, hw - ox - 0.05, oz + hd), Rect2(-hw, -hd, ox - 0.05 + hw, oz + hd - 0.05)],
		[Rect2(-hw, 4.4, 0.8, 2.6), Rect2(-2.3, 6.2, 4.6, 0.8)], Vector3(0, 1.5, 20.0), Vector3(0, 2.8, -1.0))
	Site.vehicles(root,
		[Vector3(70, 0, 29.2), Vector3(16, 0, 29.2), Vector3(-9, 0, 29.2)],
		[[Vector3(0, 0, 15.4), 0, false, Vector3(0, 0.02, -5.4)]],
		[Vector3(0, 0, 23.0), Vector3(-6, 0, 29.2), Vector3(-80, 0, 29.2)],
		[Vector3(70, 0, 29.6), Vector3(4, 0, 29.6), Vector3(1.5, 0, 25.0), Vector3(-6, 0, 18.5), Vector3(-3, 0, 13.0)],
		[Vector3(2.5, 0, 14.0), 0], [Vector3(2.5, 0, 22.0), Vector3(0, 0, 27.5), Vector3(-8, 0, 29.6), Vector3(-80, 0, 29.6)],
		mk.ParcelDrop)
	Site.save(root, "res://scenes/levels/lockup/lockup.tscn")
