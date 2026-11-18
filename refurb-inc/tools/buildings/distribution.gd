extends RefCounted
## Tier 5 — Distribution centre, M62 Gateway Plot 3. 60 x 90 m, 12 m eaves, eight dock levellers, two level doors,
## three-storey curtain-wall office, HGV yard with trailers, gatehouse, sprinkler tank, landscaped car park.

const OUT := "res://assets/generated/distribution/"


static func build() -> void:
	var root := Site.make_root("distribution")
	Site.environment(root, "clear", 0.15)
	var k := BuildKit.new(root, OUT, 505)
	var w := 60.0
	var d := 90.0
	var eave := 12.0
	var hw := w * 0.5
	var hd := d * 0.5
	var docks_x: Array = []
	for i in 8:
		docks_x.append(-26.0 + i * 4.2)
	var apron := Rect2(-28.6, hd, 36.4, 22.0 + 16.0)
	Site.ground(k, 420.0, 330.0, -0.12, [apron])
	# ---------------------------------------------------------------- shed
	k.box(Vector3(w, 0.2, d), Vector3(0, -0.1, 0), "epoxy_grey")
	var front_ops := []
	for x in docks_x:
		front_ops.append({"at": hw - float(x), "w": 3.0, "h": 3.2})
	for x in [11.0, 17.0]:
		front_ops.append({"at": hw - x, "w": 4.5, "h": 5.5})
	front_ops.append({"at": hw - 24.0, "w": 1.1, "h": 2.2})
	k.portal_shed(Vector3.ZERO, w, d, eave, 5.0, 9.0, {
		"wall": "clad_anthracite", "wall_lower": "clad_white", "lower_h": 10.0, "liner": "liner_white",
		"roof": "roof_sheet", "frame": "steel_grey", "rooflights": 2, "spans": 2,
		"front": front_ops,
		"right": [{"at": hd + 40.0, "w": 1.5, "h": 2.4}],
		"back": [{"at": 10.0, "w": 1.0, "h": 2.1}, {"at": 30.0, "w": 1.0, "h": 2.1}, {"at": 50.0, "w": 1.0, "h": 2.1}],
		"left": [{"at": 20.0, "w": 1.0, "h": 2.1}, {"at": 70.0, "w": 1.0, "h": 2.1}],
	})
	# Amber brand band wrapping the building
	for side in [[Vector3(w + 0.3, 1.0, 0.06), Vector3(0, 9.5, hd + 0.13)], [Vector3(w + 0.3, 1.0, 0.06), Vector3(0, 9.5, -hd - 0.13)],
			[Vector3(0.06, 1.0, d + 0.3), Vector3(-hw - 0.13, 9.5, 0)], [Vector3(0.06, 1.0, d + 0.3), Vector3(hw + 0.13, 9.5, 0)]]:
		k.box(side[0], side[1], "clad_amber", Vector3.ZERO, false)
	# Façade sign
	k.box(Vector3(4.2, 4.2, 0.15), Vector3(-15.0, 6.4, hd + 0.2), "panel_amber", Vector3.ZERO, false)
	k.label("R", Vector3(-15.0, 6.4, hd + 0.3), Vector3.ZERO, 560, Color.WHITE, 0.006)
	k.company_sign(Vector3(-1.0, 6.4, hd + 0.22), Vector3.ZERO, 420, Color(0.12, 0.14, 0.2), 0.008)
	k.company_sign(Vector3(0, 6.4, -hd - 0.22), Vector3(0, 180, 0), 420, Color(0.12, 0.14, 0.2), 0.008)
	# Docks apron
	k.dock_apron(apron.position.x, apron.end.x, hd, 22.0, 16.0)
	# Level doors
	for i in 2:
		var x: float = [11.0, 17.0][i]
		var lv := k.roller_door("LevelDoor%d" % (i + 1), Vector3(x, 0, hd + 0.12), 0, 4.5, 5.5, i == 0)
		k.door_switch(Vector3(x + 2.7, 1.35, hd - 0.12), 180, lv)
		k.bollard(Vector3(x - 2.6, 0, hd + 0.6))
		k.bollard(Vector3(x + 2.6, 0, hd + 0.6))
	# Interior: walkway, zones, column guards, high-bays
	for x in [22.6, 24.6]:
		k.box(Vector3(0.12, 0.01, 30.0), Vector3(x, 0.006, hd - 15.0), "line_yellow", Vector3.ZERO, false)
	k.box(Vector3(55.0, 0.01, 0.12), Vector3(-2.0, 0.006, hd - 12.0), "line_yellow", Vector3.ZERO, false)
	_zone(k, Vector3(-11.3, 0, hd - 7.0), Vector2(35.0, 8.0), "GOODS IN", "line_yellow")
	_zone(k, Vector3(14.0, 0, hd - 7.5), Vector2(11.0, 9.0), "DISPATCH", "line_green")
	_zone(k, Vector3(-22.0, 0, -30.0), Vector2(12.0, 10.0), "QUARANTINE", "line_red")
	var n_bays := 10
	for i in n_bays + 1:
		var z := clampf(-hd + 9.0 * i, -hd + 0.35, hd - 0.35)
		k.box(Vector3(0.9, 1.2, 0.9), Vector3(0, 0.6, z), "steel_yellow")
	for i in 8:
		for j in 5:
			var p := Vector3(-24.0 + j * 12.0, eave - 0.5, -38.0 + i * 11.0)
			k.cyl(0.42, 0.18, p, "alu_dark", Vector3.ZERO, 16)
			k.cyl(0.38, 0.02, p + Vector3(0, -0.1, 0), "led_panel", Vector3.ZERO, 16)
	for i in 4:
		for x in [-15.0, 15.0]:
			k.omni(Vector3(x, eave - 1.5, -33.0 + i * 22.0), 4.0, 26.0, Color(0.95, 0.97, 1.0))
	for z in [-30.0, 0.0, 30.0]:
		k.box(Vector3(0.6, 0.3, 0.06), Vector3(-hw + 0.25, 2.6, z), "emit_green", Vector3(0, 90, 0), false)
	k.decal("tyre_marks", Vector3(-10.0, 0, hd - 10.0), Vector3(6, 0.4, 14), 0, Color(1, 1, 1, 0.25))
	# ---------------------------------------------------------------- three-storey office block
	var oc := Vector3(hw + 8.0, 0, hd - 10.0)
	var osz := Vector3(16.0, 11.5, 20.0)
	k.box(Vector3(osz.x, 0.2, osz.z), oc + Vector3(0, -0.1, 0), "polished")
	k.flat_box_building(oc, osz, "panel_white", "roof_felt", 1.0, {
		"front": [{"at": 8.0, "w": 15.0, "h": 11.0, "sill": 0.1}],
		"right": [{"at": 10.0, "w": 19.0, "h": 11.0, "sill": 0.1}],
		"left": [{"at": 10.0, "w": 1.5, "h": 2.4}],
	}, "plaster_white")
	for fl in [3.8, 7.6]:
		k.box(Vector3(osz.x - 0.3, 0.3, osz.z - 0.3), oc + Vector3(0, fl, 0), "plaster_white")
	k.window_band(Vector2(oc.x + 7.5, oc.z + 10.02), Vector2(oc.x - 7.5, oc.z + 10.02), 0.1, 11.1, 1.5, "alu_dark", "glass_blue", 0.12, 0.0, [3.8, 7.6])
	k.window_band(Vector2(oc.x + 8.02, oc.z - 9.5), Vector2(oc.x + 8.02, oc.z + 9.5), 0.1, 11.1, 1.5, "alu_dark", "glass_blue", 0.12, 0.0, [3.8, 7.6])
	# floors visible through the glass: desks, lights
	for fl in [0.0, 3.95, 7.75]:
		for r in 3:
			for c in 2:
				var dp := oc + Vector3(-3.0 + c * 5.0, fl + 0.38, -5.0 + r * 4.5)
				k.box(Vector3(3.2, 0.04, 1.4), dp + Vector3(0, 0.35, 0), "paint_white")
				k.box(Vector3(0.5, 0.35, 0.03), dp + Vector3(-0.8, 0.6, -0.3), "paint_dark", Vector3.ZERO, false)
				k.box(Vector3(0.5, 0.35, 0.03), dp + Vector3(0.8, 0.6, -0.3), "paint_dark", Vector3.ZERO, false)
		for r in 3:
			k.box(Vector3(6.0, 0.04, 0.4), oc + Vector3(1.0, fl + 3.45, -5.0 + r * 4.5), "led_panel", Vector3.ZERO, false)
		k.omni(oc + Vector3(1.0, fl + 3.0, 0), 1.6, 12.0, Color(1.0, 0.97, 0.93))
	k.box(Vector3(5.0, 0.3, 3.5), oc + Vector3(1.0, 3.9, 11.5), "alu_dark", Vector3.ZERO, false)
	for cx in [-1.0, 3.0]:
		k.cyl(0.12, 3.9, oc + Vector3(cx, 1.95, 13.0), "alu", Vector3.ZERO, 12, true)
	# Rooftop illuminated sign on a frame
	k.box(Vector3(14.0, 2.4, 0.3), oc + Vector3(0, osz.y + 2.4, 9.0), "brand_dark", Vector3.ZERO, false)
	for cx in [-6.0, 0.0, 6.0]:
		k.beam(oc + Vector3(cx, osz.y, 9.0), oc + Vector3(cx, osz.y + 1.2, 9.0), Vector2(0.15, 0.15), "steel_black")
	k.company_sign(oc + Vector3(0, osz.y + 2.4, 9.2), Vector3.ZERO, 300, Color(1.0, 0.75, 0.3), 0.009)
	# ---------------------------------------------------------------- yard & site
	var yard_key := "concrete_yard"
	k.box(Vector3(31.4, 0.1, 75.0), Vector3(-44.3, -0.05, hd + 37.5), yard_key, Vector3.ZERO, false)
	k.box(Vector3(36.4, 0.1, 37.0), Vector3(-10.4, -0.05, hd + 56.5), yard_key, Vector3.ZERO, false)
	k.box(Vector3(18.0, 0.1, 75.0), Vector3(16.8, -0.05, hd + 37.5), yard_key, Vector3.ZERO, false)
	k.box(Vector3(18.0, 0.1, 20.0), Vector3(-44.0, -0.05, -20.0), "concrete_yard", Vector3.ZERO, false)
	# HGV parking (herringbone trailers) and tractor units
	for i in 5:
		k.hgv_trailer(Vector3(-50.0, 0, hd + 14.0 + i * 9.0), 60, ["paint_white", "clad_blue", "paint_white", "clad_amber", "paint_white"][i], "REFURB INC." if i == 3 else "")
	k.tractor_unit(Vector3(-30.0, 0, hd + 70.0), 90, "car_red")
	k.tractor_unit(Vector3(-30.0, 0, hd + 64.0), 90, "car_blue")
	for i in 6:
		k.box(Vector3(0.15, 0.01, 16.0), Vector3(-44.0 + 0.0, 0.004, hd + 10.0 + i * 9.0), "line_yellow", Vector3(0, 60, 0), false)
	# Car park with EV & disabled bays and a footpath
	k.box(Vector3(58.0, 0.1, 52.0), Vector3(55.0, -0.05, hd + 33.0), "tarmac_new", Vector3.ZERO, false)
	k.parking_bays(Vector3(30.0, 0, hd + 14.0), 20, Vector3.RIGHT, 2.5, 5.0, "line_white", {0: "disabled", 1: "disabled", 2: "disabled", 17: "ev", 18: "ev", 19: "ev"})
	k.parking_bays(Vector3(30.0, 0, hd + 31.0), 20, Vector3.RIGHT, 2.5, -5.0, "line_white")
	k.parking_bays(Vector3(30.0, 0, hd + 38.0), 20, Vector3.RIGHT, 2.5, 5.0, "line_white")
	var parked := 0
	for i in 20:
		if k.rng.randf() < 0.55:
			k.car(Vector3(31.25 + i * 2.5, 0, hd + 16.6), 180)
			parked += 1
		if k.rng.randf() < 0.4:
			k.car(Vector3(31.25 + i * 2.5, 0, hd + 28.4), 0)
		if k.rng.randf() < 0.3:
			k.car(Vector3(31.25 + i * 2.5, 0, hd + 40.6), 180)
	k.box(Vector3(2.5, 0.12, 14.0), Vector3(oc.x - 2.0, -0.03, hd + 7.0), "paving_light", Vector3.ZERO, false)
	for i in 6:
		k.box(Vector3(0.5, 0.01, 2.5), Vector3(oc.x - 3.2 + i * 0.75, 0.004, hd + 11.5), "line_white", Vector3.ZERO, false)
	for i in 3:
		k.flagpole(Vector3(oc.x + 5.0 + i * 2.0, 0, hd + 4.0), ["brand", "paint_white", "brand"][i], 10.0)
	# Sprinkler tank + pump house, cycle shelter, smoking shelter
	k.sprinkler_tank(Vector3(-45.0, 0, -22.0), 6.0, 12.0)
	k.box(Vector3(6.0, 0.1, 2.5), Vector3(hw + 4.0, 2.4, hd + 2.0), "glass", Vector3.ZERO, false)
	for i in 4:
		k.cyl(0.04, 2.4, Vector3(hw + 1.2 + i * 1.9, 1.2, hd + 3.0), "galv", Vector3.ZERO, 6)
	for i in 6:
		k.cyl(0.35, 0.04, Vector3(hw + 1.6 + i * 0.85, 0.5, hd + 2.2), "steel_black", Vector3(0, 0, 90), 14)
	# Lamp columns, fence, gatehouse, grassy screening bunds, trees
	for i in 10:
		k.lamp_column(Vector3(-70.0 + i * 15.0, 0, hd + 74.5), 180, 12.0)
	for i in 6:
		k.lamp_column(Vector3(-hw - 2.0, 0, -40.0 + i * 16.0), -90, 12.0)
	k.palisade(Vector2(-75, hd + 76), Vector2(-26, hd + 76))
	k.palisade(Vector2(-14, hd + 76), Vector2(18, hd + 76))
	k.palisade(Vector2(30, hd + 76), Vector2(85, hd + 76))
	k.palisade(Vector2(-75, -hd - 25), Vector2(-75, hd + 76))
	k.palisade(Vector2(85, hd + 76), Vector2(85, -hd - 25))
	k.palisade(Vector2(85, -hd - 25), Vector2(-75, -hd - 25))
	k.gatehouse(Vector3(-29.0, 0, hd + 74.0), 0, 8.0)
	k.gatehouse(Vector3(33.0, 0, hd + 74.0), 0, 8.0)
	k.box(Vector3(14.0, 0.1, 12.0), Vector3(-20.0, -0.05, hd + 81.0), "tarmac_new", Vector3.ZERO, false)
	k.box(Vector3(14.0, 0.1, 12.0), Vector3(24.0, -0.05, hd + 81.0), "tarmac_new", Vector3.ZERO, false)
	for x in range(-200, 201, 28):
		if absf(x + 20) < 14 or absf(x - 24) < 14:
			continue
		k.sphere(9.0, Vector3(x, -3.2, hd + 82.0), "grass", Vector3(1.6, 0.5, 0.5), 12)
	for x in range(-180, 181, 11):
		if absf(x + 20) < 12 or absf(x - 24) < 12:
			continue
		k.tree(Vector3(x + k.rng.randf_range(-2, 2), 0, hd + 84.5), k.rng.randf_range(7.0, 11.0))
	k.road_x(hd + 92.0, -220, 220, 8.5, 2.0, 2.0)
	k.treeline(Vector3(-150, 0, -hd - 40), Vector3(150, 0, -hd - 40), 9.0, 10.0, 17.0)
	k.treeline(Vector3(-100, 0, -hd - 30), Vector3(-100, 0, hd + 60), 9.0, 9.0, 15.0)
	# Neighbouring DC sheds in the distance
	k.portal_shed(Vector3(180, 0, -10), 80, 110, 14.0, 5.0, 10.0, {"wall": "clad_silver", "roof": "roof_sheet", "frame": "steel_grey", "rooflights": 0})
	k.portal_shed(Vector3(-200, 0, 30), 70, 100, 13.0, 5.0, 10.0, {"wall": "clad_white", "roof": "roof_sheet", "frame": "steel_grey", "rooflights": 0})
	k.hills(330.0, 18, -2.0)
	k.blocker(Vector3(220, 5, 0.5), Vector3(0, 2.5, hd + 76.5))
	k.finish()
	# ---------------------------------------------------------------- docks & wiring
	var vd := Site.vehicles(root,
		[Vector3(200, 0, hd + 90.0), Vector3(-10, 0, hd + 90.0), Vector3(-20, 0, hd + 80.0), Vector3(-20, 0, hd + 66.0), Vector3(-36, 0, hd + 56.0), Vector3(-44, 0, hd + 55.0)],
		[],
		[Vector3(-12.0, 0, hd + 54.0), Vector3(-20.0, 0, hd + 68.0), Vector3(-20.0, 0, hd + 82.0), Vector3(-32.0, 0, hd + 90.0), Vector3(-260, 0, hd + 90.0)],
		[Vector3(200, 0, hd + 93.5), Vector3(30, 0, hd + 93.5), Vector3(24, 0, hd + 82.0), Vector3(24, 0, hd + 50.0)],
		[Vector3(oc.x - 2.0, 0, hd + 9.5), 180, true], [Vector3(24, 0, hd + 26.0), Vector3(24, 0, hd + 80.0), Vector3(18, 0, hd + 93.5), Vector3(-260, 0, hd + 93.5)],
		null)
	var dk: Node3D = vd.get_node("Docks")
	for i in docks_x.size():
		k.dock(dk, "Dock%d" % (i + 1), float(docks_x[i]), hd, "DockDoor%d" % (i + 1), -1.2, i + 1)
	Site.dressing_scene(root, "res://scenes/stations/kettle_corner.tscn", "KettleCorner", Vector3(oc.x + 7.3, 0, oc.z - 6.0), -90)
	var mk := Site.markers(root, Vector3(18.0, 0, 34.0), 0, Vector3(18.0, 1.0, 33.0), Vector3(-10.0, 0.02, 30.0), Vector3(24.0, 0.3, hd + 1.8))
	vd.set("parcel_drop", mk.ParcelDrop)
	Site.layout(root, {
		"office_desk": [Vector3(oc.x - 3.0, 0, oc.z - 2.0), 0],
		"office_desk_2": [Vector3(oc.x + 2.0, 0, oc.z - 2.0), 0],
		"office_desk_3": [Vector3(oc.x - 3.0, 0, oc.z - 6.5), 0],
		"test_bench": [Vector3(-12.0, 0, 18.0), 0], "test_bench_2": [Vector3(-10.0, 0, 18.0), 0], "test_bench_3": [Vector3(-8.0, 0, 18.0), 0],
		"test_bench_4": [Vector3(-6.0, 0, 18.0), 0], "test_bench_5": [Vector3(-12.0, 0, 15.4), 180], "test_bench_6": [Vector3(-10.0, 0, 15.4), 180],
		"repair_bench": [Vector3(-2.5, 0, 18.0), 0], "repair_bench_2": [Vector3(-0.3 + 0.0, 0, 15.0), 180], "repair_bench_3": [Vector3(2.0, 0, 18.0), 0],
		"parts_shelf": [Vector3(4.3, 0, 18.0), 0], "parts_shelf_2": [Vector3(5.6, 0, 18.0), 0],
		"grading_desk": [Vector3(8.0, 0, 22.0), 0], "grading_desk_2": [Vector3(10.0, 0, 22.0), 0],
		"packing_table": [Vector3(12.0, 0, 30.0), 180], "packing_table_2": [Vector3(14.4, 0, 30.0), 180], "packing_table_3": [Vector3(16.8, 0, 30.0), 180],
		"courier_shelf": [Vector3(20.0, 0, 38.0), 180], "courier_shelf_2": [Vector3(21.4, 0, 38.0), 180],
		"metal_shelf": [Vector3(-27.0, 0, 6.0), 90], "metal_shelf_2": [Vector3(-27.0, 0, 7.4), 90], "metal_shelf_3": [Vector3(-27.0, 0, 8.8), 90],
		"metal_shelf_4": [Vector3(-27.0, 0, 10.2), 90], "metal_shelf_5": [Vector3(-27.0, 0, 11.6), 90], "metal_shelf_6": [Vector3(-27.0, 0, 13.0), 90],
	})
	var keep := [Rect2(-28.6, hd - 5.0, 36.4, 5.0), Rect2(8.4, hd - 3.5, 11.2, 3.5), Rect2(22.4, hd - 30.0, 2.4, 30.0), Rect2(oc.x + 6.0, oc.z - 7.0, 1.8, 2.4)]
	for i in 11:
		keep.append(Rect2(-0.6, clampf(-hd + 9.0 * i, -hd + 0.35, hd - 0.35) - 0.6, 1.2, 1.2))
	Site.area(root, [Rect2(-hw + 0.4, -hd + 0.4, w - 0.8, d - 0.8), Rect2(oc.x - 7.7, oc.z - 9.7, 15.4, 19.4)], keep, Vector3(0, 2.0, hd + 70.0), Vector3(0, 8.0, 0))
	Site.save(root, "res://scenes/levels/distribution/distribution.tscn")


static func _zone(k: BuildKit, c: Vector3, size: Vector2, text: String, key: String) -> void:
	for s in [-1, 1]:
		k.box(Vector3(size.x, 0.01, 0.14), c + Vector3(0, 0.007, s * size.y * 0.5), key, Vector3.ZERO, false)
		k.box(Vector3(0.14, 0.01, size.y), c + Vector3(s * size.x * 0.5, 0.007, 0), key, Vector3.ZERO, false)
	var col: Color = {"line_yellow": Color(0.97, 0.8, 0.12), "line_green": Color(0.3, 0.75, 0.4), "line_red": Color(0.9, 0.25, 0.2)}.get(key, Color.WHITE)
	k.label(text, c + Vector3(0, 0.012, size.y * 0.5 - 0.8), Vector3(-90, 0, 0), 240, col, 0.008)
