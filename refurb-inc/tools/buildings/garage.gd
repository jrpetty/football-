extends RefCounted
## Tier 1 — Home garage, 14 Sycamore Close. Cramped, grimy, lived-in. Semi-detached street outside.

const OUT := "res://assets/generated/garage/"


static func build() -> void:
	var root := Site.make_root("garage")
	Site.environment(root, "suburb")
	var k := BuildKit.new(root, OUT, 101)
	Site.ground(k, 250.0, 180.0)
	var W := 6.0
	var D := 7.0
	var H := 2.7
	var T := 0.2
	var hw := W * 0.5
	var hd := D * 0.5
	# ---------------------------------------------------------------- shell
	k.box(Vector3(W + 2 * T, 0.2, D + 2 * T), Vector3(0, -0.1, 0), "concrete_dirty")
	# side & back walls: brick outside, painted block inside
	k.wall(Vector2(-hw - T * 0.5, -hd - T * 0.5), Vector2(hw + T * 0.5, -hd - T * 0.5), H + 0.25, T, "brick", [], 0.0, "block_cream")
	k.wall(Vector2(-hw - T * 0.5, hd), Vector2(-hw - T * 0.5, -hd - T * 0.5), H + 0.25, T, "brick", [], 0.0, "block_cream")
	k.wall(Vector2(hw + T * 0.5, -hd - T * 0.5), Vector2(hw + T * 0.5, hd), H + 0.25, T, "brick", [], 0.0, "block_cream")
	# front: brick piers + lintel around a 4.2 m door
	k.wall(Vector2(hw + T, hd + T * 0.5), Vector2(-hw - T, hd + T * 0.5), H + 0.25, T, "brick", [{"at": hw + T, "w": 4.2, "h": 2.25}], 0.0, "block_cream")
	# painted dado band
	for side in [[Vector3(W, 0.32, 0.01), Vector3(0, 0.16, -hd + 0.006)], [Vector3(0.01, 0.32, D), Vector3(-hw + 0.006, 0.16, 0)], [Vector3(0.01, 0.32, D), Vector3(hw - 0.006, 0.16, 0)]]:
		k.box(side[0], side[1], "dado", Vector3.ZERO, false)
	# ceiling, joists, flat felt roof with fascia & gutter
	k.box(Vector3(W, 0.02, D), Vector3(0, H + 0.01, 0), "plaster_white", Vector3.ZERO, false)
	for i in 6:
		k.box(Vector3(W, 0.15, 0.05), Vector3(0, H - 0.075, -hd + 0.6 + i * 1.15), "plywood", Vector3.ZERO, false)
	k.box(Vector3(W + 2 * T + 0.3, 0.12, D + 2 * T + 0.3), Vector3(0, H + 0.31, 0.05), "roof_felt")
	k.box(Vector3(W + 2 * T + 0.3, 0.22, 0.03), Vector3(0, H + 0.26, hd + T + 0.16), "paint_white", Vector3.ZERO, false)
	k.cyl(0.06, 0.12, Vector3(0, H + 0.18, hd + T + 0.22), "alu_dark", Vector3(0, 0, 90), 8)
	k.cyl(0.04, H + 0.2, Vector3(hw + T + 0.1, (H + 0.2) * 0.5, hd + T + 0.1), "alu_dark", Vector3.ZERO, 8)
	k.box(Vector3(4.2, 0.02, 0.12), Vector3(0, 0.01, hd + T * 0.5), "galv", Vector3.ZERO, false)
	k.box(Vector3(0.36, 0.3, 0.1), Vector3(-hw + 0.06, 1.9, hd - 0.5), "paint_white", Vector3(0, 90, 0), false)
	# ---------------------------------------------------------------- clutter (lived-in)
	# Coiled hosepipe and a step ladder hung on the wall above the shelves
	k.cyl(0.28, 0.08, Vector3(-hw + 0.08, 2.2, -0.2), "line_green", Vector3(0, 0, 90), 16)
	k.cyl(0.18, 0.09, Vector3(-hw + 0.08, 2.2, -0.2), "paint_dark", Vector3(0, 0, 90), 12)
	for s2 in [-0.18, 0.18]:
		k.beam(Vector3(-hw + 0.08, 1.75, 0.7 + s2), Vector3(-hw + 0.08, 2.55, 0.7 + s2), Vector2(0.04, 0.03), "alu")
	for i in 4:
		k.beam(Vector3(-hw + 0.08, 1.85 + i * 0.2, 0.52), Vector3(-hw + 0.08, 1.85 + i * 0.2, 0.88), Vector2(0.03, 0.03), "alu")
	# Stacked tyres & paint tins in the back corner
	for i in 4:
		k.cyl(0.32, 0.2, Vector3(hw - 0.4, 0.1 + i * 0.205, 1.75), "tyre", Vector3(0, i * 20, 0), 14, i == 0)
	for i in 3:
		k.cyl(0.1, 0.18, Vector3(-1.9 + i * 0.22, 0.09, -hd + 0.2), ["paint_white", "steel_orange", "steel_blue"][i], Vector3.ZERO, 12)
	# Shelf above the door with boxes
	k.box(Vector3(1.6, 0.03, 0.4), Vector3(-hw + 0.9, 2.2, hd - 0.25), "plywood", Vector3.ZERO, false)
	for i in 3:
		k.box(Vector3(0.42, 0.3, 0.34), Vector3(-hw + 0.4 + i * 0.48, 2.37, hd - 0.25), "cardboard", Vector3(0, k.rng.randf_range(-8, 8), 0), false)
	# Pegboard of garden tools near the door
	k.box(Vector3(0.02, 0.9, 1.0), Vector3(hw - 0.01, 1.4, 1.0), "plywood", Vector3.ZERO, false)
	for i in 3:
		k.beam(Vector3(hw - 0.06, 0.25, 0.7 + i * 0.3), Vector3(hw - 0.06, 1.75, 0.7 + i * 0.3), Vector2(0.03, 0.03), "timber")
	# ---------------------------------------------------------------- grime
	k.decal("oil_stain", Vector3(0.4, 0.0, 1.2), Vector3(1.6, 0.4, 1.4), 20)
	k.decal("oil_stain", Vector3(-0.8, 0.0, 2.4), Vector3(0.9, 0.4, 0.8), 70, Color(1, 1, 1, 0.7))
	k.decal("tyre_marks", Vector3(0.2, 0.0, 1.0), Vector3(2.0, 0.4, 4.5), 4, Color(1, 1, 1, 0.55))
	k.decal("scuffs", Vector3(0.0, 0.0, -1.0), Vector3(3.0, 0.4, 3.0), 0, Color(1, 1, 1, 0.8))
	k.decal("damp_patch", Vector3(hw - 0.02, 0.6, -2.6), Vector3(1.4, 0.3, 1.2), 0, Color(1, 1, 1, 0.85), Vector3.LEFT)
	k.decal("damp_patch", Vector3(-1.5, 0.5, -hd + 0.02), Vector3(1.2, 0.3, 1.0), 0, Color(1, 1, 1, 0.6), Vector3.FORWARD)
	k.decal("rust_streak", Vector3(1.8, 2.0, hd + T + 0.02), Vector3(0.4, 0.3, 0.9), 0, Color(1, 1, 1, 0.8), Vector3.BACK)
	k.decal("oil_stain", Vector3(0.2, -0.04, 6.5), Vector3(1.4, 0.5, 1.2), 0, Color(1, 1, 1, 0.8))
	k.decal("tyre_marks", Vector3(0.0, -0.04, 7.5), Vector3(2.0, 0.5, 6.0), 0, Color(1, 1, 1, 0.5))
	# ---------------------------------------------------------------- lights (two fluorescent strips + one near the door)
	for i in 3:
		var sp: Vector3 = [Vector3(-1.3, H, -1.6), Vector3(1.3, H, -1.6), Vector3(0, H, 1.2)][i]
		k.box(Vector3(1.5, 0.05, 0.16), sp + Vector3(0, -0.025, 0), "paint_white", Vector3.ZERO, false)
		for z in [-0.035, 0.035]:
			k.cyl(0.013, 1.42, sp + Vector3(0, -0.065, z), "led_panel", Vector3(0, 0, 90), 8)
		k.omni(sp + Vector3(0, -0.15, 0), 1.45, 6.0, Color(0.93, 0.96, 1.0), i == 0)
	# ---------------------------------------------------------------- door & switch
	var door := k.roller_door("RollerDoor", Vector3(0, 0, hd + 0.05), 0, 4.2, 2.25, true)
	k.door_switch(Vector3(2.15, 1.3, hd - 0.02), 180, door)
	# ---------------------------------------------------------------- the house next door (we're the garage of a semi)
	var brick := "brick"
	k.box(Vector3(9.0, 5.4, 10.0), Vector3(-7.7, 2.7, -1.3), brick)
	k.prism(Vector3(10.4, 2.6, 9.6), Vector3(-7.7, 6.7, -1.3), "roof_felt", Vector3(0, 90, 0))
	k.box(Vector3(0.7, 1.4, 0.7), Vector3(-10.5, 7.6, -0.6), brick)
	for wi in 4:
		var wx := -10.6 + (wi % 2) * 4.2
		var wy := 1.5 + (wi / 2) * 2.6
		k.box(Vector3(1.4, 1.25, 0.1), Vector3(wx, wy, 3.72), "paint_white", Vector3.ZERO, false)
		k.box(Vector3(1.22, 1.08, 0.02), Vector3(wx, wy, 3.78), "glass_dark", Vector3.ZERO, false)
		k.box(Vector3(0.05, 1.08, 0.04), Vector3(wx, wy, 3.79), "paint_white", Vector3.ZERO, false)
		k.box(Vector3(1.5, 0.08, 0.2), Vector3(wx, wy - 0.68, 3.82), "kerb", Vector3.ZERO, false)
	k.box(Vector3(1.0, 2.1, 0.08), Vector3(-5.2, 1.05, 3.72), "steel_blue", Vector3.ZERO, false)
	k.box(Vector3(1.6, 0.1, 1.0), Vector3(-5.2, 2.5, 4.2), "paint_white", Vector3.ZERO, false)
	k.cyl(0.035, 0.9, Vector3(-4.95, 1.0, 3.8), "galv", Vector3.ZERO, 6)
	k.cyl(0.3, 0.06, Vector3(-9.2, 4.6, 3.75), "paint_white", Vector3(90, 0, 0), 14)   # satellite dish
	k.cyl(0.04, 5.4, Vector3(-3.35, 2.7, 3.72), "alu_dark", Vector3.ZERO, 6)
	# Front garden: low wall, hedge, path
	k.box(Vector3(5.5, 0.6, 0.25), Vector3(-9.0, 0.3, 8.6), brick)
	k.box(Vector3(5.5, 0.06, 0.3), Vector3(-9.0, 0.63, 8.6), "kerb", Vector3.ZERO, false)
	k.box(Vector3(5.5, 0.05, 4.8), Vector3(-9.0, -0.04, 6.1), "grass", Vector3.ZERO, false)
	k.box(Vector3(1.2, 0.05, 4.8), Vector3(-5.2, -0.03, 6.1), "paving", Vector3.ZERO, false)
	k.box(Vector3(0.6, 1.1, 4.0), Vector3(-11.6, 0.55, 6.0), "hedge")
	# Wheelie bins by the garage
	k.wheelie_bin(Vector3(-3.65, 0, 5.0), "bin_green", 90)
	k.wheelie_bin(Vector3(-3.65, 0, 5.8), "bin_blue", 90)
	k.wheelie_bin(Vector3(-3.65, 0, 6.6), "bin_black", 90)
	# Neighbour's fence, drive and car on the right
	for fi in 5:
		k.box(Vector3(0.05, 1.8, 1.8), Vector3(3.75, 0.9, 4.5 + fi * 1.85), "timber")
		k.box(Vector3(0.1, 1.9, 0.1), Vector3(3.75, 0.95, 3.6 + fi * 1.85), "timber", Vector3.ZERO, false)
	k.box(Vector3(5.0, 0.1, 9.0), Vector3(7.0, -0.05, 8.0), "paving", Vector3.ZERO, false)
	k.car(Vector3(7.0, 0, 8.0), 180, "car_red")
	k.box(Vector3(8.0, 5.2, 9.0), Vector3(10.0, 2.6, -0.8), "brick_dark")
	k.prism(Vector3(9.4, 2.4, 8.4), Vector3(10.0, 6.4, -0.8), "roof_felt", Vector3(0, 90, 0))
	for wi in 4:
		var wx := 8.0 + (wi % 2) * 3.8
		var wy := 1.5 + (wi / 2) * 2.5
		k.box(Vector3(1.3, 1.2, 0.1), Vector3(wx, wy, 3.72), "paint_white", Vector3.ZERO, false)
		k.box(Vector3(1.15, 1.05, 0.02), Vector3(wx, wy, 3.78), "glass_dark", Vector3.ZERO, false)
	# Drive
	k.box(Vector3(7.0, 0.1, 10.3), Vector3(0, -0.05, 8.85), "tarmac", Vector3.ZERO, false)
	# ---------------------------------------------------------------- street
	k.road_x(17.7, -150, 150, 7.0, 2.2, 0.6)
	k.box(Vector3(300, 0.12, 2.2), Vector3(0, -0.04, 13.0), "paving_light", Vector3.ZERO, false)
	# Red post box and a telegraph pole with wires
	k.cyl(0.25, 1.5, Vector3(-14, 0.75, 13.3), "car_red", Vector3.ZERO, 16)
	k.cyl(0.27, 0.12, Vector3(-14, 1.55, 13.3), "car_red", Vector3.ZERO, 16, false, 0.6)
	k.cyl(0.13, 9.0, Vector3(-6.5, 4.5, 13.8), "bark", Vector3.ZERO, 8, true, 0.7)
	k.box(Vector3(1.6, 0.1, 0.1), Vector3(-6.5, 8.6, 13.8), "bark", Vector3.ZERO, false)
	for wx in [-0.7, 0.7]:
		k.beam(Vector3(-6.5 + wx, 8.6, 13.8), Vector3(-50.0, 8.0, 13.8), Vector2(0.015, 0.015), "steel_black")
		k.beam(Vector3(-6.5 + wx, 8.6, 13.8), Vector3(48.0, 8.2, 13.8), Vector2(0.015, 0.015), "steel_black")
	k.beam(Vector3(-6.5, 8.4, 13.8), Vector3(-7.0, 4.8, 3.8), Vector2(0.012, 0.012), "steel_black")
	for lx in [-30.0, 6.5, 40.0]:
		k.lamp_column(Vector3(lx, 0, 14.2), 180, 6.0)
	# Parked cars along the far kerb
	for cx in [-24.0, -12.5, 14.0, 30.0]:
		k.car(Vector3(cx, 0, 20.3), 90 if cx < 0 else -90)
	# Terraced houses opposite with front gardens
	for hi in 8:
		var hx := -44.0 + hi * 11.0
		k.box(Vector3(10.4, 6.0, 8.0), Vector3(hx, 3.0, 29.5), "brick" if hi % 3 else "brick_dark", Vector3.ZERO, false)
		k.prism(Vector3(11.0, 2.6, 8.6), Vector3(hx, 7.3, 29.5), "roof_felt", Vector3(0, 90, 0))
		for wi in 4:
			var wx := hx - 2.5 + (wi % 2) * 5.0
			var wy := 1.6 + (wi / 2) * 2.7
			k.box(Vector3(1.4, 1.2, 0.1), Vector3(wx, wy, 25.45), "paint_white", Vector3.ZERO, false)
			k.box(Vector3(1.25, 1.05, 0.02), Vector3(wx, wy, 25.4), "glass_dark", Vector3.ZERO, false)
		k.box(Vector3(1.0, 2.1, 0.08), Vector3(hx, 1.05, 25.45), ["steel_blue", "car_red", "steel_green", "paint_dark"][hi % 4], Vector3.ZERO, false)
		k.box(Vector3(9.0, 1.1, 0.8), Vector3(hx, 0.55, 24.2), "hedge", Vector3.ZERO, false)
		k.box(Vector3(10.0, 0.05, 1.8), Vector3(hx, -0.04, 24.6), "grass", Vector3.ZERO, false)
	for tx in [-36.0, -16.0, 22.0, 46.0]:
		k.tree(Vector3(tx, 0, 23.6), 7.5, 0)
	k.treeline(Vector3(-60, 0, -14), Vector3(60, 0, -14), 6.0, 8.0, 14.0)
	k.box(Vector3(120, 0.05, 30), Vector3(0, -0.06, -14), "grass", Vector3.ZERO, false)
	# Boundaries so players stay on the drive
	k.blocker(Vector3(30, 3, 0.2), Vector3(0, 1.5, 14.25))
	k.blocker(Vector3(0.2, 3, 12), Vector3(-3.6, 1.5, 8.0))
	k.finish()
	# ---------------------------------------------------------------- dressing scenes (interactive)
	Site.dressing_scene(root, "res://scenes/stations/kettle_corner.tscn", "KettleCorner", Vector3(-2.68, 0, 2.6), 90)
	# ---------------------------------------------------------------- wiring
	var mk := Site.markers(root, Vector3(0, 0, 1.6), 0, Vector3(0, 1.0, 1.0), Vector3(0, 0.02, 5.6), Vector3(1.4, 0.3, 4.4))
	Site.layout(root, {
		"office_desk": [Vector3(-2.63, 0, -2.55), 90],
		"test_bench": [Vector3(-0.85, 0, -3.1), 0],
		"repair_bench": [Vector3(0.95, 0, -3.1), 0],
		"parts_shelf": [Vector3(2.45, 0, -3.27), 0],
		"grading_desk": [Vector3(2.63, 0, -1.25), -90],
		"packing_table": [Vector3(2.58, 0, 0.55), -90],
		"courier_shelf": [Vector3(2.73, 0, 2.6), -90],
		"metal_shelf": [Vector3(-2.75, 0, -0.85), 90],
		"metal_shelf_2": [Vector3(-2.75, 0, 0.55), 90],
	})
	Site.area(root, [Rect2(-3.0, -3.5, 6.0, 7.0)], [Rect2(-3.0, 1.6, 0.8, 1.9), Rect2(-2.1, 2.9, 4.2, 0.6)], Vector3(0, 1.5, 8.0), Vector3(0, 2.5, -1.0))
	Site.vehicles(root,
		[Vector3(60, 0, 15.9), Vector3(-7, 0, 15.9)],
		[[Vector3(0, 0, 9.5), 0, false, Vector3(0, 0.02, -4.3)]],
		[Vector3(-10, 0, 15.9), Vector3(-70, 0, 15.9)],
		[Vector3(60, 0, 15.6), Vector3(8, 0, 15.6)], [Vector3(1.5, 0, 15.6), -90], [Vector3(-70, 0, 15.6)],
		mk.ParcelDrop)
	Site.save(root, "res://scenes/levels/garage/garage.tscn")
