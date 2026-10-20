extends RefCounted
## Tier 4 — Warehouse, Harbour Road Distribution Park. Standalone 30 x 48 m, 9 m eaves, three dock levellers on a
## sunken apron, level-access door, two-storey glazed office, secure palisade yard with gatehouse, staff car park.

const OUT := "res://assets/generated/warehouse/"


static func build() -> void:
	var root := Site.make_root("warehouse")
	Site.environment(root, "clear", 0.12)
	var k := BuildKit.new(root, OUT, 404)
	var w := 30.0
	var d := 48.0
	var eave := 9.0
	var hw := w * 0.5
	var hd := d * 0.5
	var docks_x := [-10.0, -5.5, -1.0]
	Site.ground(k, 380.0, 280.0, -0.12, [Rect2(-12.8, 24.0, 14.6, 32.0)])
	# ---------------------------------------------------------------- shed
	k.box(Vector3(w, 0.2, d), Vector3(0, -0.1, 0), "epoxy_grey")
	var front_ops := []
	for x in docks_x:
		front_ops.append({"at": hw - x, "w": 3.0, "h": 3.2})
	front_ops.append({"at": hw - 8.0, "w": 4.2, "h": 5.0})
	front_ops.append({"at": hw - 12.5, "w": 1.1, "h": 2.2})
	k.portal_shed(Vector3.ZERO, w, d, eave, 6.0, 6.0, {
		"wall": "clad_anthracite", "wall_lower": "clad_silver", "lower_h": 7.0, "liner": "liner_white",
		"roof": "roof_sheet", "frame": "steel_grey", "rooflights": 2,
		"front": front_ops,
		"right": [{"at": hd + 18.0, "w": 1.2, "h": 2.2}],
		"back": [{"at": 6.0, "w": 1.0, "h": 2.1}, {"at": w - 6.0, "w": 1.0, "h": 2.1}],
	})
	# Façade branding: big letters + amber logo tile
	k.box(Vector3(3.0, 3.0, 0.15), Vector3(-10.5, 7.6, hd + 0.2), "panel_amber", Vector3.ZERO, false)
	k.label("R", Vector3(-10.5, 7.6, hd + 0.3), Vector3.ZERO, 400, Color.WHITE, 0.006)
	k.company_sign(Vector3(0.0, 7.6, hd + 0.22), Vector3.ZERO, 340, Color(0.97, 0.97, 0.98), 0.0075)
	# Dock run: apron + 3 docks
	k.dock_apron(-12.6, 1.6, hd, 18.0, 14.0)
	var vd_docks: Array = []
	var dock_markers: Array = []
	# Level access door + personnel door canopy
	var lvl := k.roller_door("LevelDoor", Vector3(8.0, 0, hd + 0.12), 0, 4.2, 5.0, true)
	k.door_switch(Vector3(10.6, 1.35, hd - 0.12), 180, lvl)
	k.box(Vector3(1.8, 0.12, 1.2), Vector3(12.5, 2.6, hd + 0.65), "alu_dark", Vector3.ZERO, false)
	k.box(Vector3(5.0, 0.01, 0.3), Vector3(8.0, 0.004, hd + 1.0), "line_yellow", Vector3.ZERO, false)
	k.decal("tyre_marks", Vector3(8.0, 0, hd + 6.0), Vector3(4.0, 0.4, 10.0), 0, Color(1, 1, 1, 0.35))
	# Interior: walkway lines, zones, lights
	for x in [11.8, 13.2]:
		k.box(Vector3(0.1, 0.01, 22.0), Vector3(x, 0.006, hd - 11.0), "line_yellow", Vector3.ZERO, false)
	k.box(Vector3(1.3, 0.01, 0.1), Vector3(12.5, 0.006, 2.0), "line_yellow", Vector3.ZERO, false)
	_zone(k, Vector3(-5.5, 0, 16.5), Vector2(14.0, 5.0), "GOODS IN", "line_yellow")
	_zone(k, Vector3(6.5, 0, 16.0), Vector2(7.0, 6.0), "DISPATCH", "line_green")
	for i in 4:
		for x in [-9.0, 0.0, 9.0]:
			var p := Vector3(x, eave - 0.4, -18.0 + i * 12.0)
			k.cyl(0.35, 0.16, p, "alu_dark", Vector3.ZERO, 16)
			k.cyl(0.32, 0.02, p + Vector3(0, -0.09, 0), "led_panel", Vector3.ZERO, 16)
		for x in [-7.0, 7.0]:
			k.omni(Vector3(x, eave - 1.0, -18.0 + i * 12.0), 3.0, 17.0, Color(0.95, 0.97, 1.0), false)
	for z in [-12.0, 12.0]:
		k.box(Vector3(0.5, 0.3, 0.06), Vector3(-hw + 0.2, 2.6, z), "emit_green", Vector3(0, 90, 0), false)
	for i in 2:
		k.box(Vector3(0.4, 0.2, 0.05), Vector3(-hw + 6.0 + i * (w - 12.0), 2.45, -hd + 0.15), "emit_green", Vector3.ZERO, false)
	k.decal("scuffs", Vector3(-5.0, 0, 14.0), Vector3(12, 0.4, 8), 0, Color(1, 1, 1, 0.35))
	# ---------------------------------------------------------------- two-storey office block on the right
	var oc := Vector3(hw + 4.6, 0, hd - 7.0)
	var osz := Vector3(9.0, 7.2, 14.0)
	k.box(Vector3(osz.x, 0.2, osz.z), oc + Vector3(0, -0.1, 0), "polished")
	k.flat_box_building(oc, osz, "panel_silver", "roof_felt", 0.6, {
		"front": [{"at": 4.5, "w": 2.2, "h": 2.6}, {"at": 1.6, "w": 2.2, "h": 1.6, "sill": 0.9}, {"at": 7.6, "w": 1.8, "h": 1.6, "sill": 0.9}, {"at": 4.5, "w": 8.2, "h": 1.8, "sill": 4.2}],
		"right": [{"at": 7.0, "w": 12.0, "h": 1.6, "sill": 0.9}, {"at": 7.0, "w": 12.0, "h": 1.8, "sill": 4.2}],
	}, "plaster_white")
	k.box(Vector3(osz.x - 0.3, 0.25, osz.z - 0.3), oc + Vector3(0, 3.6, 0), "plaster_white")
	k.window_band(Vector2(oc.x + 4.1, oc.z + 7.02), Vector2(oc.x - 4.1, oc.z + 7.02), 4.2, 6.0, 1.4, "alu_dark", "glass_blue")
	k.window_band(Vector2(oc.x + 2.7, oc.z + 7.02), Vector2(oc.x + 0.5, oc.z + 7.02), 0.9, 2.5, 1.1, "alu_dark", "glass_blue")
	k.window_band(Vector2(oc.x - 2.3, oc.z + 7.02), Vector2(oc.x - 4.0, oc.z + 7.02), 0.9, 2.5, 0.85, "alu_dark", "glass_blue")
	k.window_band(Vector2(oc.x + 4.52, oc.z - 6.0), Vector2(oc.x + 4.52, oc.z + 6.0), 0.9, 2.5, 1.5, "alu_dark", "glass_blue")
	k.window_band(Vector2(oc.x + 4.52, oc.z - 6.0), Vector2(oc.x + 4.52, oc.z + 6.0), 4.2, 6.0, 1.5, "alu_dark", "glass_blue")
	k.box(Vector3(3.2, 0.2, 2.2), Vector3(oc.x, 3.0, oc.z + 8.1), "alu_dark", Vector3.ZERO, false)
	k.box(Vector3(2.2, 2.6, 0.05), Vector3(oc.x, 1.3, oc.z + 7.02), "glass", Vector3.ZERO, false)
	k.company_sign(Vector3(oc.x, 6.9, oc.z + 7.33), Vector3.ZERO, 120, Color(0.12, 0.14, 0.2), 0.006)
	# Office interior: reception desk, stair, lights
	k.box(Vector3(2.4, 1.05, 0.7), Vector3(oc.x + 1.8, 0.52, oc.z + 4.6), "timber")
	k.box(Vector3(2.5, 0.05, 0.8), Vector3(oc.x + 1.8, 1.08, oc.z + 4.6), "paint_white", Vector3.ZERO, false)
	for i in 12:
		k.box(Vector3(1.1, 0.18, 0.3), Vector3(oc.x + 3.7, 0.09 + i * 0.3, oc.z - 6.4 + i * 0.28), "panel_anthracite")
	for p in [Vector3(oc.x - 1.5, 3.4, oc.z + 3.0), Vector3(oc.x - 1.5, 3.4, oc.z - 3.0)]:
		k.box(Vector3(1.2, 0.04, 0.6), p, "led_panel", Vector3.ZERO, false)
		k.omni(p - Vector3(0, 0.4, 0), 1.0, 6.0, Color(1.0, 0.97, 0.93))
	# ---------------------------------------------------------------- yard, car park & landscaping
	k.box(Vector3(27.2, 0.1, 42), Vector3(-26.2, -0.05, hd + 21.0), "concrete_yard", Vector3.ZERO, false)
	k.box(Vector3(14.2, 0.1, 10.0), Vector3(-5.5, -0.05, hd + 37.0), "concrete_yard", Vector3.ZERO, false)
	k.box(Vector3(12.0, 0.1, 42), Vector3(7.8, -0.05, hd + 21.0), "concrete_yard", Vector3.ZERO, false)
	k.box(Vector3(26.0, 0.1, 30), Vector3(27.0, -0.05, hd + 15.0 + 12.0), "tarmac_new", Vector3.ZERO, false)
	k.box(Vector3(26.0, 0.1, 12.0), Vector3(27.0, -0.05, hd + 6.0), "tarmac_new", Vector3.ZERO, false)
	k.box(Vector3(1.6, 0.12, 12.0), Vector3(oc.x - 3.0, -0.03, oc.z + 13.0), "paving_light", Vector3.ZERO, false)
	k.parking_bays(Vector3(16.0, 0, hd + 14.0), 9, Vector3.RIGHT, 2.5, 5.0, "line_white", {0: "disabled", 1: "disabled", 8: "ev", 7: "ev"})
	k.parking_bays(Vector3(16.0, 0, hd + 31.0), 9, Vector3.RIGHT, 2.5, -5.0, "line_white")
	for i in 7:
		if i in [2, 5]:
			continue
		k.car(Vector3(17.25 + i * 2.5, 0, hd + 16.6), 180)
	for i in 4:
		k.car(Vector3(19.75 + i * 5.0, 0, hd + 28.4), 0)
	k.hgv_trailer(Vector3(-30.0, 0, hd + 26.0), 90, "paint_white", "PENNINE FREIGHT")
	k.hgv_trailer(Vector3(-30.0, 0, hd + 14.0), 90, "clad_blue", "")
	k.pallet_stack(Vector3(-20.0, 0, hd + 3.0), 9, 0)
	k.pallet_stack(Vector3(-18.6, 0, hd + 3.0), 6, 10)
	k.euro_bin(Vector3(-15.5, 0, hd + 2.0))
	# Palisade fence & gate, gatehouse
	k.palisade(Vector2(-40, hd + 42), Vector2(-14, hd + 42))
	k.palisade(Vector2(3, hd + 42), Vector2(40, hd + 42))
	k.palisade(Vector2(-40, hd + 42), Vector2(-40, -hd - 6))
	k.palisade(Vector2(40, -hd - 6), Vector2(40, hd + 42))
	k.palisade(Vector2(-40, -hd - 6), Vector2(40, -hd - 6))
	k.gatehouse(Vector3(-17.0, 0, hd + 40.0), 0)
	k.box(Vector3(17.0, 0.1, 8.0), Vector3(-5.5, -0.05, hd + 46.0), "tarmac_new", Vector3.ZERO, false)
	for f in 3:
		k.flagpole(Vector3(22.0 + f * 1.8, 0, hd + 9.0), ["brand", "paint_white", "brand"][f], 8.0)
	for i in 8:
		k.lamp_column(Vector3(-36.0 + i * 10.0, 0, hd + 40.5), 180, 10.0)
	for i in 4:
		k.lamp_column(Vector3(-hw - 1.0, 0, -18.0 + i * 12.0), -90, 10.0)
	# Landscaping strip & road
	k.box(Vector3(380, 0.08, 8.0), Vector3(0, -0.06, hd + 46.0), "grass", Vector3.ZERO, false)
	for x in range(-60, 61, 9):
		if absf(x + 5.5) < 10:
			continue
		k.tree(Vector3(x, 0, hd + 46.5), k.rng.randf_range(6.0, 9.0), 0 if (x / 9) % 3 else 1)
	k.road_x(hd + 54.0, -190, 190, 8.0, 2.0, 1.5)
	k.box(Vector3(18.0, 0.1, 6.0), Vector3(-5.5, -0.05, hd + 49.0), "tarmac", Vector3.ZERO, false)
	# Neighbouring sheds across the road and a business park sign
	for i in 4:
		var cx := -70.0 + i * 45.0
		k.portal_shed(Vector3(cx, 0, hd + 95.0), 36.0, 40.0, 10.0, 6.0, 8.0, {"wall": ["clad_white", "clad_silver", "clad_goosewing", "clad_blue"][i], "roof": "roof_sheet", "frame": "steel_grey", "rooflights": 2})
	k.box(Vector3(5.0, 2.2, 0.5), Vector3(46.0, 1.1, hd + 47.0), "panel_anthracite")
	k.label("HARBOUR ROAD\nDISTRIBUTION PARK", Vector3(46.0, 1.3, hd + 47.27), Vector3.ZERO, 64, Color.WHITE, 0.006)
	k.treeline(Vector3(-110, 0, -hd - 20), Vector3(110, 0, -hd - 20), 8.0, 9.0, 16.0)
	k.blocker(Vector3(80, 4, 0.4), Vector3(0, 2, hd + 42.4))
	k.finish()
	# ---------------------------------------------------------------- docks (nodes) & wiring
	var vd := Site.vehicles(root,
		[Vector3(120, 0, hd + 52.2), Vector3(-2, 0, hd + 52.2), Vector3(-6.5, 0, hd + 44.0), Vector3(-14.0, 0, hd + 36.0), Vector3(-22.0, 0, hd + 33.5)],
		[],
		[Vector3(-5.5, 0, hd + 36.0), Vector3(-5.5, 0, hd + 46.0), Vector3(-12.0, 0, hd + 52.2), Vector3(-200, 0, hd + 52.2)],
		[Vector3(120, 0, hd + 52.8), Vector3(32, 0, hd + 52.8), Vector3(28.5, 0, hd + 44.0), Vector3(28.5, 0, hd + 26.0)],
		[Vector3(22.0, 0, hd + 19.0), 180, true], [Vector3(28.5, 0, hd + 30.0), Vector3(28.5, 0, hd + 44.0), Vector3(26.0, 0, hd + 52.8), Vector3(-200, 0, hd + 52.8)],
		null)
	var dk: Node3D = vd.get_node("Docks")
	var i := 1
	for x in docks_x:
		k.dock(dk, "Dock%d" % i, x, hd, "DockDoor%d" % i, -1.2, i)
		i += 1
	Site.dressing_scene(root, "res://scenes/stations/kettle_corner.tscn", "KettleCorner", Vector3(oc.x + 3.9, 0, oc.z + 1.5), -90)
	var mk := Site.markers(root, Vector3(10.0, 0, 18.0), 0, Vector3(10.0, 1.0, 17.0), Vector3(-5.5, 0.02, 14.0), Vector3(12.5, 0.3, hd + 1.8))
	vd.set("parcel_drop", mk.ParcelDrop)
	Site.layout(root, {
		"office_desk": [Vector3(oc.x - 1.4, 0, oc.z - 1.0), 0],
		"office_desk_2": [Vector3(oc.x - 1.4, 0, oc.z - 4.0), 0],
		"test_bench": [Vector3(-6.0, 0, -6.0), 0],
		"test_bench_2": [Vector3(-4.0, 0, -6.0), 0],
		"test_bench_3": [Vector3(-2.0, 0, -6.0), 0],
		"test_bench_4": [Vector3(-6.0, 0, -8.6), 180],
		"test_bench_5": [Vector3(-4.0, 0, -8.6), 180],
		"repair_bench": [Vector3(1.0, 0, -6.0), 0],
		"repair_bench_2": [Vector3(3.2, 0, -6.0), 0],
		"parts_shelf": [Vector3(5.2, 0, -6.0), 0],
		"grading_desk": [Vector3(1.0, 0, 2.0), 0],
		"grading_desk_2": [Vector3(3.0, 0, 2.0), 0],
		"packing_table": [Vector3(5.0, 0, 11.0), 180],
		"packing_table_2": [Vector3(7.3, 0, 11.0), 180],
		"courier_shelf": [Vector3(9.5, 0, 14.0), 180],
		"metal_shelf": [Vector3(-13.0, 0, -6.0), 90],
		"metal_shelf_2": [Vector3(-13.0, 0, -4.6), 90],
		"metal_shelf_3": [Vector3(-13.0, 0, -3.2), 90],
		"metal_shelf_4": [Vector3(-13.0, 0, -1.8), 90],
		"metal_shelf_5": [Vector3(-13.0, 0, -0.4), 90],
		"metal_shelf_6": [Vector3(-13.0, 0, 1.0), 90],
	})
	Site.area(root, [Rect2(-hw + 0.35, -hd + 0.35, w - 0.7, d - 0.7), Rect2(oc.x - 4.3, oc.z - 6.6, 8.2, 13.3)],
		[Rect2(-12.5, hd - 4.5, 14.0, 4.5), Rect2(5.5, hd - 3.0, 5.0, 3.0), Rect2(11.7, -2.0, 1.6, hd + 2.0), Rect2(oc.x + 3.0, oc.z - 6.8, 1.6, 4.0)],
		Vector3(0, 2.0, hd + 30.0), Vector3(0, 6.0, 0))
	Site.save(root, "res://scenes/levels/warehouse/warehouse.tscn")


static func _zone(k: BuildKit, c: Vector3, size: Vector2, text: String, key: String) -> void:
	for s in [-1, 1]:
		k.box(Vector3(size.x, 0.01, 0.12), c + Vector3(0, 0.007, s * size.y * 0.5), key, Vector3.ZERO, false)
		k.box(Vector3(0.12, 0.01, size.y), c + Vector3(s * size.x * 0.5, 0.007, 0), key, Vector3.ZERO, false)
	k.label(text, c + Vector3(0, 0.012, size.y * 0.5 - 0.6), Vector3(-90, 0, 0), 180, Color(0.97, 0.8, 0.12) if key == "line_yellow" else Color(0.3, 0.75, 0.4), 0.006)
