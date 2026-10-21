class_name Placeholders
extends RefCounted
## Procedural placeholder models, built from primitives with real PBR materials.
## Convention for EVERY prop (placeholder or final model): 1 unit = 1 m, origin at floor contact,
## FRONT (the side a player uses) faces +Z, centred on X. Replace by setting ModelSlot.model_scene.

const STEEL_BLUE := Color(0.20, 0.33, 0.52)
const STEEL_GREY := Color(0.42, 0.44, 0.46)
const STEEL_ORANGE := Color(0.86, 0.43, 0.12)
const BLACK_PLASTIC := Color(0.06, 0.06, 0.065)
const GREY_PLASTIC := Color(0.32, 0.33, 0.35)


static func build(id: String, params: Dictionary = {}) -> Node3D:
	var root := Node3D.new()
	root.name = "Placeholder_" + id
	match id:
		"workbench": _b_workbench(root, params)
		"test_bench": _b_test_bench(root, params)
		"repair_bench": _b_repair_bench(root, params)
		"grading_desk": _b_grading_desk(root, params)
		"label_printer": _b_label_printer(root, params)
		"packing_table": _b_packing_table(root, params)
		"metal_shelf": _b_metal_shelf(root, params)
		"parts_shelf": _b_parts_shelf(root, params)
		"courier_shelf": _b_courier_shelf(root, params)
		"heavy_rack": _b_heavy_rack(root, params)
		"office_desk": _b_office_desk(root, params)
		"monitor": _b_monitor(root, params)
		"kettle_corner": _b_kettle_corner(root, params)
		"pallet": _b_pallet(root, params)
		"cardboard_box": _b_cardboard_box(root, params)
		"usb_stick": _b_usb_stick(root, params)
		"strip_light": _b_strip_light(root, params)
		"bin": _b_bin(root, params)
		"ladder": _b_ladder(root, params)
		"paint_tins": _b_paint_tins(root, params)
		"cable_reel": _b_cable_reel(root, params)
		"poster": _b_poster(root, params)
		"person": _b_person(root, params)
		"lorry": _b_lorry(root, params)
		"van": _b_van(root, params)
		_:
			push_warning("No placeholder named '%s'" % id)
			box(root, Vector3(0.5, 0.5, 0.5), Vector3(0, 0.25, 0), Mats.color(Color.MAGENTA))
	return root


# ================================================================= primitives

static func box(parent: Node3D, size: Vector3, pos: Vector3, mat: Material, rot_deg: Vector3 = Vector3.ZERO, name: String = "") -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	mi.mesh = bm
	mi.material_override = mat
	mi.position = pos
	mi.rotation_degrees = rot_deg
	if name != "":
		mi.name = name
	parent.add_child(mi)
	return mi


static func cyl(parent: Node3D, radius: float, height: float, pos: Vector3, mat: Material, rot_deg: Vector3 = Vector3.ZERO, sides: int = 16, top_r: float = -1.0) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = radius if top_r < 0 else top_r
	cm.bottom_radius = radius
	cm.height = height
	cm.radial_segments = sides
	cm.rings = 1
	mi.mesh = cm
	mi.material_override = mat
	mi.position = pos
	mi.rotation_degrees = rot_deg
	parent.add_child(mi)
	return mi


static func sphere(parent: Node3D, radius: float, pos: Vector3, mat: Material) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var sm := SphereMesh.new()
	sm.radius = radius
	sm.height = radius * 2
	sm.radial_segments = 16
	sm.rings = 8
	mi.mesh = sm
	mi.material_override = mat
	mi.position = pos
	parent.add_child(mi)
	return mi


static func text(parent: Node3D, txt: String, pos: Vector3, size: int = 32, col: Color = Color.BLACK, rot_deg: Vector3 = Vector3.ZERO, pixel: float = 0.001) -> Label3D:
	var l := Label3D.new()
	l.text = txt
	l.font_size = size
	l.pixel_size = pixel
	l.modulate = col
	l.outline_size = 0
	l.position = pos
	l.rotation_degrees = rot_deg
	l.double_sided = false
	l.shaded = true
	parent.add_child(l)
	return l


# ================================================================= furniture

static func _bench_frame(root: Node3D, w: float, d: float, h: float, top_mat: Material, frame_col: Color) -> void:
	var t := 0.04
	box(root, Vector3(w, t, d), Vector3(0, h - t / 2, 0), top_mat, Vector3.ZERO, "Top")
	var fm := Mats.painted_metal(frame_col)
	for sx in [-1, 1]:
		for sz in [-1, 1]:
			box(root, Vector3(0.05, h - t, 0.05), Vector3(sx * (w / 2 - 0.05), (h - t) / 2, sz * (d / 2 - 0.05)), fm)
	box(root, Vector3(w - 0.1, 0.05, 0.05), Vector3(0, h - t - 0.05, d / 2 - 0.05), fm)
	box(root, Vector3(w - 0.1, 0.05, 0.05), Vector3(0, h - t - 0.05, -d / 2 + 0.05), fm)
	box(root, Vector3(w - 0.1, 0.025, d - 0.12), Vector3(0, 0.16, 0), Mats.surface("plywood", 1.2, Color(0.85, 0.85, 0.85)))


static func _b_workbench(root: Node3D, p: Dictionary) -> void:
	_bench_frame(root, p.get("w", 1.6), p.get("d", 0.75), p.get("h", 0.9), Mats.surface("plywood", 1.0), STEEL_GREY)


static func _b_test_bench(root: Node3D, p: Dictionary) -> void:
	var w: float = p.get("w", 1.4)
	var d := 0.75
	var h := 0.9
	_bench_frame(root, w, d, h, Mats.surface("plywood", 1.0), STEEL_BLUE)
	box(root, Vector3(w * 0.7, 0.004, 0.55), Vector3(-0.08, h + 0.002, 0.02), Mats.surface("antistatic_mat", 0.8))
	# Back riser with power strip & cable tidy
	box(root, Vector3(w, 0.35, 0.03), Vector3(0, h + 0.175, -d / 2 + 0.015), Mats.surface("pegboard", 0.6))
	box(root, Vector3(0.45, 0.04, 0.06), Vector3(0.3, h + 0.05, -d / 2 + 0.06), Mats.color(Color(0.9, 0.9, 0.88), 0.5))
	for i in 4:
		box(root, Vector3(0.03, 0.02, 0.01), Vector3(0.15 + i * 0.09, h + 0.06, -d / 2 + 0.095), Mats.color(Color(0.15, 0.15, 0.15)))
	# Small status monitor on an arm (results display)
	box(root, Vector3(0.04, 0.32, 0.04), Vector3(w / 2 - 0.2, h + 0.16, -d / 2 + 0.1), Mats.painted_metal(STEEL_GREY))
	var mon := box(root, Vector3(0.42, 0.27, 0.03), Vector3(w / 2 - 0.2, h + 0.38, -d / 2 + 0.14), Mats.color(BLACK_PLASTIC, 0.4), Vector3(-8, -12, 0), "StatusMonitor")
	box(mon, Vector3(0.39, 0.235, 0.002), Vector3(0, 0.005, 0.016), Mats.screen_off(), Vector3.ZERO, "Screen")
	# USB hub tray
	box(root, Vector3(0.14, 0.025, 0.08), Vector3(w / 2 - 0.2, h + 0.0125, 0.18), Mats.color(GREY_PLASTIC, 0.5), Vector3.ZERO, "UsbHub")


static func _b_repair_bench(root: Node3D, p: Dictionary) -> void:
	var w: float = p.get("w", 1.6)
	var d := 0.75
	var h := 0.92
	_bench_frame(root, w, d, h, Mats.surface("plywood", 1.0, Color(0.92, 0.9, 0.86)), STEEL_ORANGE)
	box(root, Vector3(w * 0.75, 0.004, 0.6), Vector3(0, h + 0.002, 0.02), Mats.surface("antistatic_mat", 0.8))
	# Pegboard with tools
	box(root, Vector3(w, 0.9, 0.02), Vector3(0, h + 0.55, -d / 2 + 0.01), Mats.surface("pegboard", 0.6))
	var tool_cols := [Color(0.85, 0.1, 0.1), Color(0.95, 0.75, 0.1), Color(0.1, 0.3, 0.8), Color(0.15, 0.6, 0.2), Color(0.1, 0.1, 0.1)]
	for i in 9:
		var x := -w / 2 + 0.15 + i * (w - 0.3) / 8.0
		var c: Color = tool_cols[i % tool_cols.size()]
		cyl(root, 0.012, 0.12, Vector3(x, h + 0.62, -d / 2 + 0.04), Mats.color(c, 0.4))
		cyl(root, 0.003, 0.1, Vector3(x, h + 0.51, -d / 2 + 0.04), Mats.color(Color(0.75, 0.75, 0.78), 0.25, 0.9))
	# Spool of solder, multimeter, small parts trays
	box(root, Vector3(0.09, 0.03, 0.16), Vector3(w / 2 - 0.15, h + 0.015, -0.15), Mats.color(Color(0.95, 0.75, 0.1), 0.5))
	box(root, Vector3(0.3, 0.04, 0.12), Vector3(-w / 2 + 0.25, h + 0.02, -0.2), Mats.color(Color(0.2, 0.25, 0.35), 0.6))
	# Desk lamp (the light itself is added by the station)
	cyl(root, 0.07, 0.02, Vector3(-w / 2 + 0.2, h + 0.01, 0.2), Mats.color(BLACK_PLASTIC, 0.5))
	box(root, Vector3(0.02, 0.45, 0.02), Vector3(-w / 2 + 0.2, h + 0.24, 0.15), Mats.color(BLACK_PLASTIC, 0.5), Vector3(-20, 0, 0))
	cyl(root, 0.06, 0.08, Vector3(-w / 2 + 0.25, h + 0.45, 0.05), Mats.color(BLACK_PLASTIC, 0.5), Vector3(60, 0, 0), 16, 0.03)


static func _b_grading_desk(root: Node3D, p: Dictionary) -> void:
	var w: float = p.get("w", 1.2)
	var h := 0.9
	_bench_frame(root, w, 0.7, h, Mats.color(Color(0.86, 0.87, 0.88), 0.5), STEEL_GREY)
	# Light box / inspection mat
	box(root, Vector3(0.6, 0.02, 0.45), Vector3(-0.15, h + 0.01, 0.05), Mats.emissive(Color(0.95, 0.96, 1.0), 0.4))
	# Label printer
	var lp := Node3D.new()
	lp.name = "LabelPrinter"
	lp.position = Vector3(w / 2 - 0.2, h, -0.1)
	root.add_child(lp)
	_b_label_printer(lp, {})


static func _b_label_printer(root: Node3D, _p: Dictionary) -> void:
	box(root, Vector3(0.18, 0.12, 0.22), Vector3(0, 0.06, 0), Mats.color(Color(0.92, 0.92, 0.9), 0.45))
	box(root, Vector3(0.16, 0.03, 0.18), Vector3(0, 0.135, -0.01), Mats.color(Color(0.2, 0.2, 0.22), 0.4))
	box(root, Vector3(0.1, 0.004, 0.02), Vector3(0, 0.08, 0.112), Mats.color(Color(0.1, 0.1, 0.1)))
	box(root, Vector3(0.012, 0.012, 0.004), Vector3(0.06, 0.1, 0.111), Mats.emissive(Color(0.2, 1.0, 0.3), 2.0))


static func _b_packing_table(root: Node3D, p: Dictionary) -> void:
	var w: float = p.get("w", 1.6)
	var h := 0.92
	_bench_frame(root, w, 0.8, h, Mats.surface("plywood", 1.0), STEEL_GREY)
	# Flat-packed boxes underneath
	for i in 6:
		box(root, Vector3(0.62, 0.012, 0.45), Vector3(-0.3, 0.19 + i * 0.013, 0.0), Mats.surface("cardboard", 0.5))
	# Bubble wrap roll on a bar
	cyl(root, 0.15, 0.55, Vector3(w / 2 - 0.4, h + 0.55, -0.32), Mats.color(Color(0.85, 0.9, 0.95, 0.6), 0.2), Vector3(0, 0, 90))
	box(root, Vector3(0.03, 0.6, 0.03), Vector3(w / 2 - 0.1, h + 0.3, -0.35), Mats.painted_metal(STEEL_GREY))
	box(root, Vector3(0.03, 0.6, 0.03), Vector3(w / 2 - 0.7, h + 0.3, -0.35), Mats.painted_metal(STEEL_GREY))
	# Tape gun
	var tg := Node3D.new()
	tg.name = "TapeGun"
	tg.position = Vector3(w / 2 - 0.25, h, 0.2)
	root.add_child(tg)
	cyl(tg, 0.07, 0.05, Vector3(0, 0.12, 0), Mats.color(Color(0.7, 0.55, 0.3, 0.85), 0.3), Vector3(0, 0, 90))
	box(tg, Vector3(0.03, 0.14, 0.03), Vector3(0, 0.05, 0.06), Mats.color(Color(0.8, 0.1, 0.1), 0.5), Vector3(20, 0, 0))
	# Label printer
	var lp := Node3D.new()
	lp.name = "LabelPrinter"
	lp.position = Vector3(-w / 2 + 0.2, h, -0.22)
	root.add_child(lp)
	_b_label_printer(lp, {})


static func _b_metal_shelf(root: Node3D, p: Dictionary) -> void:
	var w: float = p.get("w", 1.2)
	var d: float = p.get("d", 0.45)
	var h: float = p.get("h", 1.9)
	var levels: int = p.get("levels", 5)
	var col: Color = p.get("color", STEEL_BLUE)
	var fm := Mats.painted_metal(col)
	for sx in [-1, 1]:
		for sz in [-1, 1]:
			box(root, Vector3(0.035, h, 0.035), Vector3(sx * (w / 2 - 0.02), h / 2, sz * (d / 2 - 0.02)), fm)
	for i in levels:
		var y := 0.08 + i * (h - 0.15) / maxf(levels - 1, 1)
		box(root, Vector3(w, 0.02, d), Vector3(0, y, 0), Mats.painted_metal(Color(0.62, 0.64, 0.66)))
		box(root, Vector3(w, 0.04, 0.01), Vector3(0, y - 0.01, d / 2), fm)


static func _b_parts_shelf(root: Node3D, p: Dictionary) -> void:
	_b_metal_shelf(root, {"w": p.get("w", 1.0), "d": 0.4, "h": 1.6, "levels": 4, "color": STEEL_GREY})


static func _b_courier_shelf(root: Node3D, p: Dictionary) -> void:
	var w: float = p.get("w", 1.0)
	var h: float = p.get("h", 1.0)
	var d: float = p.get("d", 0.5)
	_b_metal_shelf(root, {"w": w, "d": d, "h": h, "levels": 3, "color": Color(0.15, 0.45, 0.25)})
	var sign := box(root, Vector3(0.7, 0.16, 0.01), Vector3(0, h + 0.12, -d / 2 + 0.03), Mats.color(Color(0.98, 0.8, 0.1), 0.5))
	text(sign, "OUTGOING", Vector3(0, 0, 0.006), 72, Color(0.08, 0.08, 0.08))
	if d > 0.6:
		# Floor-bay marking for big boxes: yellow/black hazard edge on the bottom deck.
		var hz := Mats.color(Color(0.95, 0.75, 0.1), 0.6)
		box(root, Vector3(w - 0.08, 0.004, 0.05), Vector3(0, 0.093, d / 2 - 0.05), hz)
		var tag := box(root, Vector3(0.36, 0.07, 0.005), Vector3(0, 0.05, d / 2 + 0.003), Mats.color(Color(0.97, 0.97, 0.95), 0.7))
		text(tag, "LARGE BOXES", Vector3(0, 0, 0.004), 40, Color(0.1, 0.1, 0.1))


static func _b_heavy_rack(root: Node3D, p: Dictionary) -> void:
	## Pallet-racking style heavy-duty bays: blue upright frames with bracing, orange box beams, chipboard decks.
	var w: float = p.get("w", 2.0)
	var d: float = p.get("d", 0.9)
	var h: float = p.get("h", 1.8)
	var levels: int = p.get("levels", 3)
	var upright := Mats.painted_metal(Color(0.13, 0.27, 0.55))
	var beam := Mats.painted_metal(STEEL_ORANGE)
	var deck := Mats.surface("plywood", 1.2, Color(0.86, 0.78, 0.62))
	var bays := 2
	for f in bays + 1:
		var x := -w / 2 + 0.04 + f * (w - 0.08) / bays
		for sz in [-1, 1]:
			box(root, Vector3(0.07, h, 0.06), Vector3(x, h / 2, sz * (d / 2 - 0.03)), upright)
			box(root, Vector3(0.09, 0.012, 0.1), Vector3(x, 0.006, sz * (d / 2 - 0.03)), upright)   # base plate
		# Side bracing: horizontals + diagonals between front and back posts.
		var span := d - 0.06
		var n_br := 4
		for i in n_br + 1:
			var y := 0.15 + i * (h - 0.3) / n_br
			box(root, Vector3(0.03, 0.03, span), Vector3(x, y, 0), upright)
			if i < n_br:
				var dy := (h - 0.3) / n_br
				var ang := rad_to_deg(atan2(dy, span))
				box(root, Vector3(0.025, 0.025, sqrt(dy * dy + span * span)), Vector3(x, y + dy / 2, 0), upright, Vector3(ang if i % 2 == 0 else -ang, 0, 0))
	for i in levels:
		var y := 0.08 + i * (h - 0.15) / maxf(levels - 1, 1)
		for sz in [-1, 1]:
			box(root, Vector3(w - 0.06, 0.09, 0.05), Vector3(0, y - 0.035, sz * (d / 2 - 0.03)), beam)
		box(root, Vector3(w - 0.08, 0.018, d - 0.04), Vector3(0, y + 0.003, 0), deck)
	var sign := box(root, Vector3(0.42, 0.14, 0.004), Vector3(0, 0.08 + (h - 0.15) / maxf(levels - 1, 1) - 0.06, d / 2 + 0.002), Mats.color(Color(0.98, 0.8, 0.1), 0.5))
	text(sign, "MAX 300 kg / LEVEL", Vector3(0, 0, 0.003), 40, Color(0.08, 0.08, 0.08))


static func _b_office_desk(root: Node3D, _p: Dictionary) -> void:
	var w := 1.4
	var d := 0.7
	var h := 0.74
	box(root, Vector3(w, 0.03, d), Vector3(0, h - 0.015, 0), Mats.color(Color(0.55, 0.42, 0.3), 0.55), Vector3.ZERO, "Top")
	for sx in [-1, 1]:
		box(root, Vector3(0.03, h - 0.03, d - 0.05), Vector3(sx * (w / 2 - 0.02), (h - 0.03) / 2, 0), Mats.color(Color(0.2, 0.2, 0.22), 0.5))
	box(root, Vector3(w - 0.06, 0.4, 0.02), Vector3(0, h - 0.25, -d / 2 + 0.03), Mats.color(Color(0.2, 0.2, 0.22), 0.5))
	# PC tower on floor
	box(root, Vector3(0.2, 0.42, 0.42), Vector3(w / 2 - 0.2, 0.21, -0.05), Mats.color(BLACK_PLASTIC, 0.35))
	box(root, Vector3(0.006, 0.006, 0.006), Vector3(w / 2 - 0.25, 0.38, 0.165), Mats.emissive(Color(0.3, 0.7, 1.0), 3.0))
	# Keyboard, mouse, mug, paperwork
	box(root, Vector3(0.44, 0.02, 0.14), Vector3(0, h + 0.01, 0.17), Mats.color(Color(0.1, 0.1, 0.11), 0.45))
	box(root, Vector3(0.06, 0.03, 0.1), Vector3(0.32, h + 0.015, 0.17), Mats.color(Color(0.1, 0.1, 0.11), 0.35))
	cyl(root, 0.04, 0.1, Vector3(-0.5, h + 0.05, 0.1), Mats.color(Color(0.85, 0.2, 0.15), 0.35))
	box(root, Vector3(0.21, 0.01, 0.3), Vector3(-0.45, h + 0.005, -0.1), Mats.color(Color(0.95, 0.95, 0.93), 0.8), Vector3(0, 8, 0))
	# Office chair (in front of desk)
	var chair := Node3D.new()
	chair.position = Vector3(0, 0, 0.75)
	chair.rotation_degrees = Vector3(0, 170, 0)
	root.add_child(chair)
	cyl(chair, 0.3, 0.05, Vector3(0, 0.05, 0), Mats.color(BLACK_PLASTIC, 0.4), Vector3.ZERO, 5)
	cyl(chair, 0.03, 0.4, Vector3(0, 0.25, 0), Mats.color(Color(0.6, 0.6, 0.62), 0.3, 0.8))
	box(chair, Vector3(0.48, 0.08, 0.46), Vector3(0, 0.48, 0), Mats.color(Color(0.12, 0.12, 0.14), 0.8))
	box(chair, Vector3(0.44, 0.55, 0.06), Vector3(0, 0.82, 0.22), Mats.color(Color(0.12, 0.12, 0.14), 0.8), Vector3(-8, 0, 0))


static func _b_monitor(root: Node3D, p: Dictionary) -> void:
	var w: float = p.get("w", 0.6)
	var hh: float = w * 9.0 / 16.0
	box(root, Vector3(0.22, 0.015, 0.18), Vector3(0, 0.0075, 0), Mats.color(BLACK_PLASTIC, 0.35))
	box(root, Vector3(0.05, 0.3, 0.03), Vector3(0, 0.15, -0.04), Mats.color(BLACK_PLASTIC, 0.35))
	var body := box(root, Vector3(w + 0.03, hh + 0.04, 0.035), Vector3(0, 0.12 + hh / 2 + 0.03, 0), Mats.color(BLACK_PLASTIC, 0.35), Vector3.ZERO, "Body")
	var scr := MeshInstance3D.new()
	scr.name = "Screen"
	var qm := QuadMesh.new()
	qm.size = Vector2(w, hh)
	scr.mesh = qm
	scr.position = Vector3(0, 0.003, 0.0185)
	scr.material_override = Mats.screen_off()
	body.add_child(scr)


static func _b_kettle_corner(root: Node3D, _p: Dictionary) -> void:
	# Base cabinet (the mug cupboard) with worktop
	box(root, Vector3(0.9, 0.86, 0.55), Vector3(0, 0.43, 0), Mats.color(Color(0.93, 0.93, 0.9), 0.6))
	box(root, Vector3(0.92, 0.035, 0.58), Vector3(0, 0.88, 0), Mats.color(Color(0.25, 0.25, 0.27), 0.35))
	box(root, Vector3(0.004, 0.8, 0.01), Vector3(0.0, 0.43, 0.277), Mats.color(Color(0.8, 0.8, 0.78), 0.5))
	for hx in [-0.04, 0.04]:
		box(root, Vector3(0.008, 0.12, 0.02), Vector3(hx, 0.7, 0.285), Mats.color(Color(0.7, 0.7, 0.72), 0.3, 0.8))
	# Kettle base (stays put) and the jug (lifts off to pour; the kitchen animates "Kettle").
	cyl(root, 0.085, 0.015, Vector3(-0.3, 0.9075, -0.08), Mats.color(BLACK_PLASTIC, 0.4))
	var k := Node3D.new()
	k.name = "Kettle"
	k.position = Vector3(-0.3, 0.9, -0.08)
	root.add_child(k)
	var white := Mats.color(Color(0.95, 0.95, 0.95), 0.3)
	cyl(k, 0.075, 0.2, Vector3(0, 0.115, 0), white, Vector3.ZERO, 20, 0.06)
	box(k, Vector3(0.025, 0.16, 0.04), Vector3(0.0, 0.12, -0.085), white)
	box(k, Vector3(0.03, 0.025, 0.06), Vector3(0, 0.17, 0.08), white, Vector3(-30, 0, 0))
	cyl(k, 0.062, 0.012, Vector3(0, 0.221, 0), Mats.color(Color(0.2, 0.2, 0.22), 0.4))
	var led := box(k, Vector3(0.02, 0.012, 0.004), Vector3(0, 0.03, 0.074), Mats.color(Color(0.1, 0.1, 0.12), 0.4), Vector3.ZERO, "KettleLED")
	var lm := StandardMaterial3D.new()
	lm.albedo_color = Color(0.1, 0.2, 0.5)
	lm.emission_enabled = true
	lm.emission = Color(0.25, 0.55, 1.0)
	lm.emission_energy_multiplier = 0.0
	led.material_override = lm
	# Sink with a tap
	var steel := Mats.color(Color(0.78, 0.8, 0.82), 0.25, 0.9)
	box(root, Vector3(0.28, 0.006, 0.32), Vector3(0.3, 0.9, 0.0), steel)
	box(root, Vector3(0.22, 0.004, 0.26), Vector3(0.3, 0.9035, 0.0), Mats.color(Color(0.42, 0.44, 0.46), 0.3, 0.9))
	cyl(root, 0.012, 0.002, Vector3(0.3, 0.906, 0.0), Mats.color(Color(0.15, 0.15, 0.16), 0.4, 0.8))
	cyl(root, 0.012, 0.24, Vector3(0.3, 1.02, -0.14), steel)
	box(root, Vector3(0.02, 0.02, 0.06), Vector3(0.3, 1.13, -0.115), steel)
	# Tea caddy and sugar
	box(root, Vector3(0.1, 0.13, 0.09), Vector3(-0.08, 0.965, -0.2), Mats.color(Color(0.1, 0.25, 0.15), 0.5))
	text(root, "TEA", Vector3(-0.08, 0.97, -0.154), 26, Color(0.95, 0.85, 0.5), Vector3.ZERO, 0.0008)
	cyl(root, 0.035, 0.07, Vector3(0.05, 0.935, -0.2), Mats.color(Color(0.95, 0.95, 0.92), 0.3))
	# Mini fridge beside (the milk)
	box(root, Vector3(0.48, 0.84, 0.5), Vector3(0.72, 0.42, 0), Mats.color(Color(0.92, 0.93, 0.94), 0.35))
	box(root, Vector3(0.02, 0.2, 0.02), Vector3(0.52, 0.6, 0.26), Mats.color(Color(0.7, 0.7, 0.72), 0.3, 0.8))


static func _b_pallet(root: Node3D, _p: Dictionary) -> void:
	# EUR pallet 1.2 x 0.8 x 0.144 (UK standard 1.2 x 1.0 is used for lorries; this is the common Euro size)
	var wood := Mats.surface("plywood", 0.5, Color(0.85, 0.75, 0.6), 1.0)
	var w := 1.2
	var d := 0.8
	for i in 5:
		box(root, Vector3(w, 0.022, 0.1), Vector3(0, 0.133, -d / 2 + 0.05 + i * (d - 0.1) / 4.0), wood)
	for i in 3:
		box(root, Vector3(w, 0.022, 0.1), Vector3(0, 0.1, -d / 2 + 0.05 + i * (d - 0.1) / 2.0), wood)
		for j in 3:
			box(root, Vector3(0.14, 0.078, 0.1), Vector3(-w / 2 + 0.07 + j * (w - 0.14) / 2.0, 0.05, -d / 2 + 0.05 + i * (d - 0.1) / 2.0), wood)
	for i in 3:
		box(root, Vector3(0.1, 0.022, d), Vector3(-w / 2 + 0.07 + i * (w - 0.14) / 2.0, 0.011, 0), wood)


static func _b_cardboard_box(root: Node3D, p: Dictionary) -> void:
	var s: Vector3 = p.get("size", Vector3(0.45, 0.3, 0.35))
	box(root, s, Vector3(0, s.y / 2, 0), Mats.surface("cardboard", 0.4))
	box(root, Vector3(0.05, 0.002, s.z + 0.002), Vector3(0, s.y + 0.001, 0), Mats.color(Color(0.75, 0.6, 0.38, 0.9), 0.3))
	if p.get("label", false):
		box(root, Vector3(0.1, 0.002, 0.15), Vector3(s.x * 0.2, s.y + 0.002, 0), Mats.color(Color(0.97, 0.97, 0.95), 0.7))


static func _b_usb_stick(root: Node3D, p: Dictionary) -> void:
	var col: Color = p.get("color", Color(0.1, 0.45, 0.9))
	box(root, Vector3(0.018, 0.008, 0.055), Vector3(0, 0.004, 0), Mats.color(col, 0.35))
	box(root, Vector3(0.012, 0.0045, 0.013), Vector3(0, 0.004, -0.034), Mats.color(Color(0.8, 0.8, 0.82), 0.2, 1.0))
	box(root, Vector3(0.014, 0.0005, 0.02), Vector3(0, 0.0083, 0.008), Mats.color(Color(0.97, 0.97, 0.97), 0.6))


static func _b_strip_light(root: Node3D, p: Dictionary) -> void:
	var l: float = p.get("length", 1.5)
	box(root, Vector3(l, 0.05, 0.16), Vector3(0, -0.025, 0), Mats.color(Color(0.9, 0.9, 0.88), 0.4, 0.3))
	for z in [-0.035, 0.035]:
		cyl(root, 0.013, l - 0.08, Vector3(0, -0.065, z), Mats.emissive(Color(0.98, 0.98, 1.0), 6.0), Vector3(0, 0, 90), 10)


static func _b_bin(root: Node3D, _p: Dictionary) -> void:
	cyl(root, 0.2, 0.6, Vector3(0, 0.3, 0), Mats.color(Color(0.18, 0.2, 0.22), 0.6), Vector3.ZERO, 20, 0.22)


static func _b_ladder(root: Node3D, _p: Dictionary) -> void:
	var al := Mats.color(Color(0.75, 0.76, 0.78), 0.3, 0.9)
	for sx in [-0.2, 0.2]:
		box(root, Vector3(0.05, 2.2, 0.025), Vector3(sx, 1.1, 0), al)
	for i in 7:
		cyl(root, 0.015, 0.4, Vector3(0, 0.25 + i * 0.3, 0), al, Vector3(0, 0, 90))


static func _b_paint_tins(root: Node3D, _p: Dictionary) -> void:
	var cols := [Color(0.9, 0.9, 0.88), Color(0.85, 0.5, 0.2), Color(0.3, 0.45, 0.6)]
	for i in 3:
		cyl(root, 0.1, 0.18, Vector3(i * 0.22 - 0.22, 0.09, (i % 2) * 0.05), Mats.color(cols[i], 0.4, 0.5))


static func _b_cable_reel(root: Node3D, _p: Dictionary) -> void:
	cyl(root, 0.15, 0.04, Vector3(0, 0.15, -0.06), Mats.color(Color(0.9, 0.3, 0.05), 0.5), Vector3(90, 0, 0), 20)
	cyl(root, 0.15, 0.04, Vector3(0, 0.15, 0.06), Mats.color(Color(0.9, 0.3, 0.05), 0.5), Vector3(90, 0, 0), 20)
	cyl(root, 0.11, 0.1, Vector3(0, 0.15, 0), Mats.color(Color(0.1, 0.1, 0.1), 0.5), Vector3(90, 0, 0), 20)


static func _b_poster(root: Node3D, p: Dictionary) -> void:
	var s: Vector2 = p.get("size", Vector2(0.42, 0.59))
	var bg: Color = p.get("bg", Color(0.95, 0.95, 0.92))
	box(root, Vector3(s.x, s.y, 0.003), Vector3(0, 0, 0.0015), Mats.color(bg, 0.85))
	var lines: Array = p.get("lines", ["POSTER"])
	var y := s.y / 2 - 0.07
	for i in lines.size():
		var sz := 64 if i == 0 else 36
		text(root, lines[i], Vector3(0, y, 0.004), sz, p.get("fg", Color(0.1, 0.1, 0.1)))
		y -= 0.075 if i == 0 else 0.05


static func _b_person(root: Node3D, p: Dictionary) -> void:
	var shirt: Color = p.get("color", Color(0.2, 0.35, 0.6))
	cyl(root, 0.13, 0.82, Vector3(0, 0.41, 0), Mats.color(Color(0.15, 0.17, 0.22), 0.8), Vector3.ZERO, 12)
	cyl(root, 0.19, 0.6, Vector3(0, 1.12, 0), Mats.color(shirt, 0.8), Vector3.ZERO, 12, 0.17)
	box(root, Vector3(0.4, 0.3, 0.02), Vector3(0, 1.2, 0.17), Mats.color(Color(0.95, 0.85, 0.1), 0.6))
	sphere(root, 0.11, Vector3(0, 1.58, 0), Mats.color(Color(0.86, 0.67, 0.55), 0.6))
	box(root, Vector3(0.12, 0.03, 0.02), Vector3(0, 1.6, 0.1), Mats.color(Color(0.1, 0.1, 0.1), 0.3))


# ================================================================= vehicles

static func _wheel(root: Node3D, pos: Vector3, r: float) -> void:
	cyl(root, r, 0.24, pos, Mats.color(Color(0.05, 0.05, 0.05), 0.85), Vector3(0, 0, 90), 20)
	cyl(root, r * 0.55, 0.25, pos, Mats.color(Color(0.6, 0.6, 0.62), 0.3, 0.8), Vector3(0, 0, 90), 12)


static func _b_lorry(root: Node3D, p: Dictionary) -> void:
	## 7.5 t box lorry, rear (tail-lift) faces -Z, cab at +Z.
	var livery: Color = p.get("livery", Color(0.93, 0.93, 0.92))
	var cab_col: Color = p.get("cab", Color(0.85, 0.15, 0.12))
	var body := Mats.color(livery, 0.5, 0.1)
	box(root, Vector3(2.45, 2.4, 5.2), Vector3(0, 2.0, -0.9), body, Vector3.ZERO, "Box")
	box(root, Vector3(2.3, 0.25, 7.2), Vector3(0, 0.75, 0.1), Mats.color(Color(0.1, 0.1, 0.1), 0.7))
	var cab := box(root, Vector3(2.4, 2.0, 1.7), Vector3(0, 1.85, 2.6), Mats.color(cab_col, 0.35, 0.2), Vector3.ZERO, "Cab")
	box(cab, Vector3(2.2, 0.8, 0.02), Vector3(0, 0.35, 0.86), Mats.glass(Color(0.1, 0.12, 0.15, 0.9)))
	box(cab, Vector3(0.02, 0.6, 0.9), Vector3(1.21, 0.35, 0.2), Mats.glass(Color(0.1, 0.12, 0.15, 0.9)))
	box(cab, Vector3(0.02, 0.6, 0.9), Vector3(-1.21, 0.35, 0.2), Mats.glass(Color(0.1, 0.12, 0.15, 0.9)))
	for sx in [-1, 1]:
		box(cab, Vector3(0.25, 0.12, 0.05), Vector3(sx * 0.95, -0.7, 0.86), Mats.emissive(Color(1, 0.97, 0.85), 2.0))
	for z in [2.4, -2.2]:
		for sx in [-1.05, 1.05]:
			_wheel(root, Vector3(sx, 0.5, z), 0.5)
	# Rear doors, tail lights and tail-lift platform
	var rear := box(root, Vector3(2.4, 2.3, 0.04), Vector3(0, 2.0, -3.52), Mats.color(livery.darkened(0.08), 0.5), Vector3.ZERO, "RearDoor")
	for sx in [-1, 1]:
		box(root, Vector3(0.2, 0.12, 0.05), Vector3(sx * 1.0, 0.8, -3.55), Mats.emissive(Color(1, 0.1, 0.05), 2.5), Vector3.ZERO, "TailLight")
		box(root, Vector3(0.12, 0.12, 0.05), Vector3(sx * 0.75, 0.8, -3.55), Mats.emissive(Color(1, 0.6, 0.1), 0.2), Vector3.ZERO, "Indicator")
	var lift := Node3D.new()
	lift.name = "TailLift"
	lift.position = Vector3(0, 0.85, -3.55)
	root.add_child(lift)
	box(lift, Vector3(2.2, 0.06, 1.4), Vector3(0, 0, -0.7), Mats.painted_metal(STEEL_GREY), Vector3.ZERO, "Platform")
	var sign := Node3D.new()
	sign.position = Vector3(1.23, 2.3, -0.9)
	sign.rotation_degrees = Vector3(0, 90, 0)
	root.add_child(sign)
	text(sign, p.get("brand", "PENNINE FREIGHT"), Vector3.ZERO, 260, Color(0.12, 0.2, 0.45), Vector3.ZERO, 0.002)
	var sign2 := Node3D.new()
	sign2.position = Vector3(-1.23, 2.3, -0.9)
	sign2.rotation_degrees = Vector3(0, -90, 0)
	root.add_child(sign2)
	text(sign2, p.get("brand", "PENNINE FREIGHT"), Vector3.ZERO, 260, Color(0.12, 0.2, 0.45), Vector3.ZERO, 0.002)


static func _b_van(root: Node3D, p: Dictionary) -> void:
	## Courier van, rear faces -Z.
	var col: Color = p.get("color", Color(0.95, 0.75, 0.1))
	box(root, Vector3(2.0, 1.9, 3.4), Vector3(0, 1.35, -0.5), Mats.color(col, 0.4, 0.15), Vector3.ZERO, "Body")
	var cab := box(root, Vector3(1.95, 1.3, 1.3), Vector3(0, 1.05, 1.85), Mats.color(col, 0.4, 0.15), Vector3.ZERO, "Cab")
	box(cab, Vector3(1.8, 0.6, 0.02), Vector3(0, 0.3, 0.55), Mats.glass(Color(0.1, 0.12, 0.15, 0.9)), Vector3(-25, 0, 0))
	for z in [1.8, -1.5]:
		for sx in [-0.9, 0.9]:
			_wheel(root, Vector3(sx, 0.36, z), 0.36)
	for sx in [-1, 1]:
		box(root, Vector3(0.15, 0.2, 0.04), Vector3(sx * 0.85, 0.95, -2.21), Mats.emissive(Color(1, 0.1, 0.05), 2.0))
	var sign := Node3D.new()
	sign.position = Vector3(1.01, 1.5, -0.5)
	sign.rotation_degrees = Vector3(0, 90, 0)
	root.add_child(sign)
	text(sign, p.get("brand", "ZIPPD"), Vector3.ZERO, 220, Color(0.1, 0.1, 0.12), Vector3.ZERO, 0.002)
	var sign2 := Node3D.new()
	sign2.position = Vector3(-1.01, 1.5, -0.5)
	sign2.rotation_degrees = Vector3(0, -90, 0)
	root.add_child(sign2)
	text(sign2, p.get("brand", "ZIPPD"), Vector3.ZERO, 220, Color(0.1, 0.1, 0.12), Vector3.ZERO, 0.002)
