extends RefCounted
## Tier 3 — Unit 4, Brookfield Business Park. A clean, modern terraced industrial unit: 6 m eaves, roof lights,
## front office, brick plinth, goosewing-grey cladding with blue trims, forecourt parking and an estate road.

const OUT := "res://assets/generated/industrial/"


static func build() -> void:
	var root := Site.make_root("industrial")
	Site.environment(root, "morning", 0.08)
	var k := BuildKit.new(root, OUT, 303)
	Site.ground(k, 300.0, 220.0)
	var w := 12.0
	var d := 20.0
	var eave := 6.0
	var hw := w * 0.5
	var hd := d * 0.5
	var neighbours := [
		{"x": -24.0, "name": "PENNINE PRINT LTD", "door": "clad_blue"},
		{"x": -12.0, "name": "CALDER JOINERY", "door": "clad_blue"},
		{"x": 12.0, "name": "TO LET — 2,150 sq ft", "door": "clad_blue"},
	]
	# ---------------------------------------------------------------- our unit
	k.box(Vector3(w, 0.2, d), Vector3(0, -0.1, 0), "concrete_sealed")
	k.portal_shed(Vector3(0, 0, 0), w, d, eave, 6.0, 5.0, {
		"wall": "clad_goosewing", "wall_lower": "brick_dark", "lower_h": 1.2, "liner": "liner_white",
		"roof": "roof_sheet", "frame": "steel_blue", "rooflights": 2,
		"front": [{"at": hw - 2.5, "w": 4.0, "h": 4.5}, {"at": hw + 4.0, "w": 1.1, "h": 2.2}, {"at": hw + 2.0, "w": 1.8, "h": 1.4, "sill": 0.9}],
		"back": [{"at": 3.0, "w": 1.0, "h": 2.1}],
	})
	k.window_band(Vector2(-1.1, hd + 0.02), Vector2(-2.9, hd + 0.02), 0.9, 2.3, 0.9, "steel_blue", "glass_blue")
	k.window_band(Vector2(-3.45, hd + 0.02), Vector2(-4.55, hd + 0.02), 0.0, 2.2, 1.1, "steel_blue", "glass_blue")
	# Blue door surround & corner flashings
	for fx in [0.5, 4.5]:
		k.box(Vector3(0.15, 4.6, 0.12), Vector3(fx, 2.3, hd + 0.12), "clad_blue", Vector3.ZERO, false)
	k.box(Vector3(4.15, 0.15, 0.12), Vector3(2.5, 4.55, hd + 0.12), "clad_blue", Vector3.ZERO, false)
	for cx in [-hw, hw]:
		k.box(Vector3(0.22, eave, 0.22), Vector3(cx, eave * 0.5, hd), "clad_blue", Vector3.ZERO, false)
	# Entrance canopy
	k.box(Vector3(2.4, 0.15, 1.4), Vector3(-4.0, 2.75, hd + 0.75), "alu_dark", Vector3.ZERO, false)
	for cx in [-5.0, -3.0]:
		k.beam(Vector3(cx, 2.7, hd + 0.05), Vector3(cx, 3.6, hd + 0.1), Vector2(0.03, 0.03), "galv")
	# Fascia sign board + unit number
	k.box(Vector3(7.0, 1.0, 0.08), Vector3(-1.5, 5.0, hd + 0.18), "paint_white", Vector3.ZERO, false)
	k.box(Vector3(0.3, 1.0, 0.09), Vector3(-4.85, 5.0, hd + 0.2), "brand", Vector3.ZERO, false)
	k.company_sign(Vector3(-1.2, 5.0, hd + 0.24), Vector3.ZERO, 200, Color(0.1, 0.12, 0.17), 0.0035)
	k.box(Vector3(1.0, 1.0, 0.08), Vector3(4.6, 5.0, hd + 0.18), "clad_blue", Vector3.ZERO, false)
	k.label("4", Vector3(4.6, 5.0, hd + 0.23), Vector3.ZERO, 320, Color.WHITE, 0.0025)
	# Office inside the front-left corner (blockwork, internal window, door into the warehouse)
	var ox := -2.0
	var oz := 6.0
	k.wall(Vector2(ox, hd), Vector2(ox, oz), 2.8, 0.2, "block_white", [{"at": 2.0, "w": 1.6, "h": 1.0, "sill": 1.0}])
	k.window_band(Vector2(ox + 0.1, hd - 1.2), Vector2(ox + 0.1, hd - 2.8), 1.0, 2.0, 0.8, "alu", "glass")
	k.wall(Vector2(-hw, oz), Vector2(ox, oz), 2.8, 0.2, "block_white", [{"at": 1.4, "w": 1.0, "h": 2.1}])
	k.box(Vector3(4.0, 0.12, 4.0), Vector3(-4.0, 2.86, 8.0), "plaster_white", Vector3.ZERO, false)
	k.box(Vector3(1.2, 0.04, 0.6), Vector3(-4.0, 2.78, 8.0), "led_panel", Vector3.ZERO, false)
	k.omni(Vector3(-4.0, 2.5, 8.0), 0.9, 4.5, Color(1.0, 0.97, 0.92))
	k.box(Vector3(4.0, 0.02, 4.0), Vector3(-4.0, 0.011, 8.0), "epoxy_grey", Vector3.ZERO, false)
	# Fire exit sign & extinguishers by the back door
	k.box(Vector3(0.4, 0.2, 0.05), Vector3(3.0, 2.45, -hd + 0.15), "emit_green", Vector3.ZERO, false)
	k.label("FIRE EXIT", Vector3(3.0, 2.45, -hd + 0.18), Vector3.ZERO, 40, Color.WHITE, 0.003)
	for i in 2:
		k.cyl(0.08, 0.55, Vector3(1.9 + i * 0.25, 0.65, -hd + 0.25), "car_red", Vector3.ZERO, 10)
	k.box(Vector3(0.5, 0.6, 0.02), Vector3(1.6, 1.4, -hd + 0.12), "line_green", Vector3.ZERO, false)
	k.label("+", Vector3(1.6, 1.4, -hd + 0.14), Vector3.ZERO, 120, Color.WHITE, 0.003)
	# High-bay lights
	for i in 3:
		for x in [-3.0, 3.0]:
			var p := Vector3(x, eave - 0.3, -6.0 + i * 6.0)
			k.cyl(0.28, 0.12, p, "alu_dark", Vector3.ZERO, 14)
			k.cyl(0.25, 0.02, p + Vector3(0, -0.07, 0), "led_panel", Vector3.ZERO, 14)
		k.omni(Vector3(0, eave - 0.8, -6.0 + i * 6.0), 2.2, 11.0, Color(0.95, 0.97, 1.0), i == 1)
	# Roller door
	var door := k.roller_door("RollerDoor", Vector3(2.5, 0, hd + 0.1), 0, 4.0, 4.5, true)
	k.door_switch(Vector3(5.0, 1.35, hd - 0.12), 180, door)
	# ---------------------------------------------------------------- neighbouring units (same estate spec)
	for n in neighbours:
		var cx: float = n.x
		k.portal_shed(Vector3(cx, 0, 0), w, d, eave, 6.0, 5.0, {
			"wall": "clad_goosewing", "wall_lower": "brick_dark", "lower_h": 1.2, "roof": "roof_sheet", "frame": "steel_blue", "rooflights": 2,
			"front": [{"at": hw - 2.5, "w": 4.0, "h": 4.5}, {"at": hw + 4.0, "w": 1.1, "h": 2.2}, {"at": hw + 2.0, "w": 1.8, "h": 1.4, "sill": 0.9}],
		})
		k.box(Vector3(4.0, 4.5, 0.08), Vector3(cx + 2.5, 2.25, hd + 0.02), n.door)
		k.window_band(Vector2(cx - 1.1, hd + 0.02), Vector2(cx - 2.9, hd + 0.02), 0.9, 2.3, 0.9, "steel_blue", "glass_dark")
		k.box(Vector3(1.1, 2.2, 0.06), Vector3(cx - 4.0, 1.1, hd + 0.02), "glass_dark")
		k.box(Vector3(7.0, 1.0, 0.08), Vector3(cx - 1.5, 5.0, hd + 0.18), "paint_white", Vector3.ZERO, false)
		k.label(n.name, Vector3(cx - 1.5, 5.0, hd + 0.23), Vector3.ZERO, 120 if n.name.length() > 16 else 160, Color(0.15, 0.17, 0.2), 0.0035)
		for fx in [0.5, 4.5]:
			k.box(Vector3(0.15, 4.6, 0.12), Vector3(cx + fx, 2.3, hd + 0.12), "clad_blue", Vector3.ZERO, false)
	# ---------------------------------------------------------------- forecourt & estate
	k.box(Vector3(50, 0.1, 13), Vector3(-6, -0.05, hd + 6.5), "tarmac_new", Vector3.ZERO, false)
	k.box(Vector3(50, 0.12, 1.0), Vector3(-6, -0.04, hd + 0.5), "paving_light", Vector3.ZERO, false)
	for n in [{"x": -24.0}, {"x": -12.0}, {"x": 0.0}, {"x": 12.0}]:
		k.parking_bays(Vector3(float(n.x) - 6.0, 0.0, hd + 1.4), 3, Vector3.RIGHT, 2.5, 5.0, "line_white", {0: "disabled"} if float(n.x) == 0.0 else {})
		k.decal("hazard_stripes", Vector3(float(n.x) + 2.5, 0.0, hd + 1.2), Vector3(4.0, 0.3, 0.3))
		for bx in [0.3, 4.7]:
			k.bollard(Vector3(float(n.x) + bx, 0, hd + 0.8))
	k.car(Vector3(-4.75, 0, hd + 3.9), 0, "car_white")
	k.car(Vector3(-21.0, 0, hd + 3.9), 0)
	k.car(Vector3(-16.0, 0, hd + 3.9), 0)
	k.car(Vector3(-8.5, 0, hd + 3.9), 0)
	k.decal("oil_stain", Vector3(-12.0, -0.04, hd + 3.0), Vector3(1.0, 0.4, 0.8), 0, Color(1, 1, 1, 0.5))
	k.road_x(27.0, -170, 170, 7.3, 2.0, 2.5)
	for lx in [-40.0, -18.0, 4.0, 26.0, 48.0]:
		k.lamp_column(Vector3(lx, 0, 22.0), 180, 8.0)
	for tx in [-34.0, -28.0, 18.0, 24.0]:
		k.tree(Vector3(tx, 0, 21.0), 5.0, 0)
	# Estate totem sign at the entrance
	k.box(Vector3(2.4, 3.2, 0.4), Vector3(22.0, 1.6, 21.2), "panel_anthracite")
	k.box(Vector3(2.2, 0.6, 0.02), Vector3(22.0, 2.75, 21.41), "clad_blue", Vector3.ZERO, false)
	k.label("BROOKFIELD\nBUSINESS PARK", Vector3(22.0, 2.75, 21.43), Vector3.ZERO, 56, Color.WHITE, 0.004)
	var lines := ["1  PENNINE PRINT", "2  CALDER JOINERY", "4  " + "REFURB INC.", "5  TO LET"]
	for i in lines.size():
		k.label(lines[i], Vector3(21.2, 2.1 - i * 0.35, 21.42), Vector3.ZERO, 44, Color(0.9, 0.92, 0.95), 0.004).horizontal_alignment = HORIZONTAL_ALIGNMENT_LEFT
	# Bin store (timber enclosure) and Euro bins
	k.wall(Vector2(-32, 18), Vector2(-32, 12), 1.8, 0.08, "timber")
	k.wall(Vector2(-37, 12), Vector2(-32, 12), 1.8, 0.08, "timber")
	k.euro_bin(Vector3(-34.0, 0, 13.4))
	k.euro_bin(Vector3(-35.6, 0, 13.4))
	# Across the road: grass bank, trees, another terrace of units
	k.box(Vector3(340, 1.0, 14), Vector3(0, 0.3, 39), "grass", Vector3(-4, 0, 0), false)
	k.treeline(Vector3(-80, 0.6, 36), Vector3(80, 0.6, 36), 7.0, 6.0, 11.0)
	for i in 5:
		var cx := -48.0 + i * 24.0
		k.portal_shed(Vector3(cx, 0.8, 58), 22.0, 26.0, 7.0, 6.0, 6.5, {"wall": ["clad_silver", "clad_goosewing", "clad_white"][i % 3], "roof": "roof_sheet", "frame": "steel_grey", "rooflights": 0})
	# Behind: hedge and fields
	k.box(Vector3(160, 1.6, 1.2), Vector3(0, 0.8, -16), "hedge", Vector3.ZERO, false)
	k.treeline(Vector3(-80, 0, -24), Vector3(80, 0, -24), 8.0, 9.0, 15.0)
	k.blocker(Vector3(60, 3, 0.3), Vector3(-6, 1.5, 23.3))
	k.finish()
	# ---------------------------------------------------------------- dressing & wiring
	Site.dressing_scene(root, "res://scenes/stations/kettle_corner.tscn", "KettleCorner", Vector3(-3.0, 0, 5.62), 180)
	var mk := Site.markers(root, Vector3(1.5, 0, 7.0), 0, Vector3(1.5, 1.0, 6.0), Vector3(2.5, 0.02, 12.6), Vector3(-2.6, 0.3, 11.2))
	Site.layout(root, {
		"office_desk": [Vector3(-4.0, 0, 6.5), 0],
		"test_bench": [Vector3(-3.4, 0, -9.42), 0],
		"test_bench_2": [Vector3(-1.4, 0, -9.42), 0],
		"repair_bench": [Vector3(0.9, 0, -9.42), 0],
		"repair_bench_2": [Vector3(2.9, 0, -9.42), 0],
		"parts_shelf": [Vector3(4.9, 0, -9.6), 0],
		"grading_desk": [Vector3(5.42, 0, -6.4), -90],
		"packing_table": [Vector3(5.38, 0, -3.6), -90],
		"courier_shelf": [Vector3(5.52, 0, 6.6), -90],
		"metal_shelf": [Vector3(-5.5, 0, -6.6), 90],
		"metal_shelf_2": [Vector3(-5.5, 0, -5.2), 90],
		"metal_shelf_3": [Vector3(-5.5, 0, -3.8), 90],
		"metal_shelf_4": [Vector3(-5.5, 0, -2.4), 90],
	})
	Site.area(root, [Rect2(-5.82, -9.82, 11.64, 15.72), Rect2(ox + 0.12, oz - 0.1, hw - ox - 0.3, hd - oz - 0.08), Rect2(-hw + 0.15, oz + 0.12, ox - 0.12 + hw - 0.15, hd - oz - 0.27)],
		[Rect2(0.3, 8.4, 4.4, 1.6), Rect2(-4.0, 5.1, 2.0, 0.9)], Vector3(0, 1.5, 22.0), Vector3(0, 4.5, -2.0))
	Site.vehicles(root,
		[Vector3(90, 0, 25.3), Vector3(25, 0, 25.3), Vector3(-9, 0, 25.3)],
		[[Vector3(2.5, 0, 17.0), 0, false, Vector3(0, 0.02, -6.0)]],
		[Vector3(2.5, 0, 23.0), Vector3(-6, 0, 25.3), Vector3(-100, 0, 25.3)],
		[Vector3(90, 0, 25.8), Vector3(10, 0, 25.8)], [Vector3(-3.0, 0, 24.4), -90], [Vector3(-100, 0, 25.8)],
		mk.ParcelDrop)
	Site.save(root, "res://scenes/levels/industrial/industrial.tscn")
