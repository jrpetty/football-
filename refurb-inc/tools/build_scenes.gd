extends Node
## Generates the station/prop/vehicle wrapper scenes and the garage level as real, editable .tscn files.
##   godot --headless --path . res://tools/build_scenes.tscn
## Re-running OVERWRITES those scenes — once you start hand-editing the garage in the editor, stop running this
## (or pass --only-props to regenerate just the small wrapper scenes).

const S := "res://scenes/"

var _only_props := false


func _ready() -> void:
	_only_props = "--only-props" in OS.get_cmdline_user_args()
	_build_props()
	_build_stations()
	_build_vehicles()
	if not _only_props:
		_build_levels()
	print("Scenes built.")
	get_tree().quit()


# ================================================================= helpers

func _save(root: Node, path: String) -> void:
	_own(root, root)
	var ps := PackedScene.new()
	var err := ps.pack(root)
	if err != OK:
		push_error("pack failed for %s: %d" % [path, err])
		return
	DirAccess.make_dir_recursive_absolute(path.get_base_dir())
	err = ResourceSaver.save(ps, path)
	if err != OK:
		push_error("save failed for %s: %d" % [path, err])
	else:
		print("  wrote ", path)
	root.free()


func _own(n: Node, root: Node) -> void:
	for c in n.get_children():
		if c.owner == null:
			c.owner = root
		if c.scene_file_path == "":
			_own(c, root)


func _model(parent: Node, placeholder: String, params: Dictionary = {}) -> ModelSlot:
	var ms := ModelSlot.new()
	ms.name = "Model"
	ms.placeholder = placeholder
	ms.placeholder_params = params
	parent.add_child(ms)
	return ms


func _slot(parent: Node, name: String, slot_name: String, accepts: Array, pos: Vector3, hint := Vector3(0.36, 0.01, 0.26), label := "") -> SnapSlot:
	var s := SnapSlot.new()
	s.name = name
	s.slot_name = slot_name
	s.accepts = PackedStringArray(accepts)
	s.position = pos
	s.hint_size = hint
	s.location_label = label
	parent.add_child(s)
	return s


func _collider(parent: Node, size: Vector3, pos: Vector3, interact := true, name := "Body") -> StaticBody3D:
	var b := StaticBody3D.new()
	b.name = name
	b.collision_layer = 1 | ((1 << 3) if interact else 0)
	var cs := CollisionShape3D.new()
	cs.name = "Shape"
	var bs := BoxShape3D.new()
	bs.size = size
	cs.shape = bs
	cs.position = pos
	b.add_child(cs)
	parent.add_child(b)
	return b


func _box(parent: Node, name: String, size: Vector3, pos: Vector3, mat: Material, rot_deg := Vector3.ZERO, collide := true) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	mi.name = name
	var bm := BoxMesh.new()
	bm.size = size
	bm.add_uv2 = true
	mi.mesh = bm
	mi.material_override = mat
	mi.position = pos
	mi.rotation_degrees = rot_deg
	parent.add_child(mi)
	if collide:
		var b := StaticBody3D.new()
		b.name = "Col"
		b.collision_layer = 1
		var cs := CollisionShape3D.new()
		var bs := BoxShape3D.new()
		bs.size = size
		cs.shape = bs
		b.add_child(cs)
		mi.add_child(b)
	return mi


func _inst(parent: Node, path: String, name: String, pos: Vector3, rot_y := 0.0, props := {}) -> Node3D:
	var n: Node3D = load(path).instantiate()
	n.name = name
	n.position = pos
	n.rotation_degrees.y = rot_y
	for k in props:
		n.set(k, props[k])
	parent.add_child(n)
	return n


func _marker(parent: Node, name: String, pos: Vector3, rot_y := 0.0) -> Marker3D:
	var m := Marker3D.new()
	m.name = name
	m.position = pos
	m.rotation_degrees.y = rot_y
	parent.add_child(m)
	return m


# ================================================================= props

const PROPS := {
	"bin": {}, "ladder": {}, "paint_tins": {}, "cable_reel": {}, "pallet": {},
	"cardboard_box": {"size": Vector3(0.45, 0.3, 0.35)}, "strip_light": {"length": 1.5},
	"workbench": {}, "metal_shelf_empty": {},
}


func _build_props() -> void:
	for id in PROPS:
		var root := Node3D.new()
		root.name = id.to_pascal_case()
		root.set_script(load("res://scripts/world/prop.gd"))
		var ph: String = "metal_shelf" if id == "metal_shelf_empty" else id
		_model(root, ph, PROPS[id])
		if id == "strip_light":
			root.set("solid", false)
		_save(root, S + "props/%s.tscn" % id)


# ================================================================= stations

func _station_root(name: String, script_path: String, type: String, display: String, desc: String) -> Node3D:
	var root := Node3D.new()
	root.name = name
	root.set_script(load(script_path))
	root.set("station_type", type)
	root.set("display_name", display)
	root.set("description", desc)
	return root


func _build_stations() -> void:
	# Test / imaging bench
	var tb := _station_root("TestBench", "res://scripts/stations/bench_station.gd", "bench", "Test bench",
		"Unit + DiagnostiKit USB = full test. Unit + PaneOS USB = wipe & image.")
	_model(tb, "test_bench", {"w": 1.4})
	_collider(tb, Vector3(1.4, 0.92, 0.75), Vector3(0, 0.46, 0))
	_collider(tb, Vector3(1.4, 0.4, 0.06), Vector3(0, 1.1, -0.35), true, "RiserBody")
	_slot(tb, "UnitSlot", "unit", ["unit"], Vector3(-0.12, 0.904, 0.06), Vector3(0.38, 0.01, 0.27), "Test bench")
	_slot(tb, "UsbPort", "usb", ["usb_test", "usb_image"], Vector3(0.5, 0.926, 0.18), Vector3(0.05, 0.01, 0.08), "Test bench USB")
	_slot(tb, "UsbParkA", "park_a", ["usb_test", "usb_image"], Vector3(0.38, 0.904, 0.28), Vector3(0.05, 0.01, 0.08), "Test bench")
	_slot(tb, "UsbParkB", "park_b", ["usb_test", "usb_image"], Vector3(0.6, 0.904, 0.28), Vector3(0.05, 0.01, 0.08), "Test bench")
	_save(tb, S + "stations/test_bench.tscn")

	var rb := _station_root("RepairBench", "res://scripts/stations/repair_station.gd", "repair", "Repair bench",
		"Hold E on a faulty unit to repair it. Parts come from the bins.")
	_model(rb, "repair_bench", {"w": 1.6})
	_collider(rb, Vector3(1.6, 0.94, 0.75), Vector3(0, 0.47, 0))
	_collider(rb, Vector3(1.6, 0.9, 0.06), Vector3(0, 1.45, -0.35), true, "PegboardBody")
	_slot(rb, "UnitSlot", "unit", ["unit"], Vector3(0.05, 0.924, 0.06), Vector3(0.38, 0.01, 0.27), "Repair bench")
	_save(rb, S + "stations/repair_bench.tscn")

	var gd := _station_root("GradingDesk", "res://scripts/stations/grade_station.gd", "grade", "Grading desk",
		"Hold E to grade an imaged unit. Prints a grade sticker.")
	_model(gd, "grading_desk", {"w": 1.2})
	_collider(gd, Vector3(1.2, 0.92, 0.7), Vector3(0, 0.46, 0))
	_slot(gd, "UnitSlot", "unit", ["unit"], Vector3(-0.15, 0.922, 0.05), Vector3(0.38, 0.01, 0.27), "Grading desk")
	_save(gd, S + "stations/grading_desk.tscn")

	var pt := _station_root("PackingTable", "res://scripts/stations/pack_station.gd", "pack", "Packing table",
		"Hold E to box a SOLD unit. Then put it on the OUTGOING shelf.")
	_model(pt, "packing_table", {"w": 1.6})
	_collider(pt, Vector3(1.6, 0.94, 0.8), Vector3(0, 0.47, 0))
	_slot(pt, "UnitSlot", "unit", ["unit", "package"], Vector3(-0.15, 0.924, 0.06), Vector3(0.44, 0.01, 0.34), "Packing table")
	_save(pt, S + "stations/packing_table.tscn")

	var sh := _station_root("MetalShelf", "res://scripts/stations/shelf_unit.gd", "", "Stock shelving", "")
	sh.set("levels", 5)
	sh.set("per_level", 3)
	sh.set("width", 1.2)
	sh.set("depth", 0.45)
	sh.set("height", 1.9)
	_model(sh, "metal_shelf", {"w": 1.2, "d": 0.45, "h": 1.9, "levels": 5})
	_collider(sh, Vector3(1.2, 1.9, 0.45), Vector3(0, 0.95, 0))
	_save(sh, S + "stations/metal_shelf.tscn")

	var cs := _station_root("CourierShelf", "res://scripts/stations/shelf_unit.gd", "courier", "OUTGOING shelf",
		"Packed orders go here. The courier collects them.")
	cs.set("levels", 3)
	cs.set("per_level", 2)
	cs.set("width", 1.0)
	cs.set("depth", 0.5)
	cs.set("height", 1.0)
	cs.set("accepts", PackedStringArray(["package"]))
	cs.set("short_label", "Outgoing")
	_model(cs, "courier_shelf", {"w": 1.0})
	_collider(cs, Vector3(1.0, 1.0, 0.5), Vector3(0, 0.5, 0))
	_save(cs, S + "stations/courier_shelf.tscn")

	var ps := _station_root("PartsShelf", "res://scripts/stations/parts_shelf.gd", "", "Parts bins", "")
	_model(ps, "parts_shelf", {"w": 1.0})
	_collider(ps, Vector3(1.0, 1.6, 0.4), Vector3(0, 0.8, 0))
	_save(ps, S + "stations/parts_shelf.tscn")

	# Office desk with the PC
	var od := Node3D.new()
	od.name = "OfficeDesk"
	_model(od, "office_desk")
	_collider(od, Vector3(1.4, 0.75, 0.7), Vector3(0, 0.375, 0), false)
	var pc := Node3D.new()
	pc.name = "OfficePC"
	pc.set_script(load("res://scripts/stations/office_pc.gd"))
	pc.position = Vector3(0, 0.74, -0.14)
	od.add_child(pc)
	_save(od, S + "stations/office_desk.tscn")

	# Kettle corner
	var kc := Node3D.new()
	kc.name = "KettleCorner"
	_model(kc, "kettle_corner")
	_collider(kc, Vector3(1.4, 0.9, 0.58), Vector3(0.25, 0.45, 0), false)
	var kettle := StaticBody3D.new()
	kettle.name = "Kettle"
	kettle.set_script(load("res://scripts/stations/simple_interactables.gd"))
	kettle.set("kind", "kettle")
	kettle.set("title", "Kettle")
	kettle.position = Vector3(-0.22, 0.9, 0)
	var kcs := CollisionShape3D.new()
	var kbs := BoxShape3D.new()
	kbs.size = Vector3(0.22, 0.26, 0.26)
	kcs.shape = kbs
	kcs.position = Vector3(0, 0.13, 0)
	kettle.add_child(kcs)
	kc.add_child(kettle)
	_save(kc, S + "stations/kettle_corner.tscn")


# ================================================================= vehicles

func _build_vehicles() -> void:
	var l := Node3D.new()
	l.name = "Lorry"
	_model(l, "lorry", {"brand": "PENNINE FREIGHT"})
	_save(l, S + "vehicles/lorry.tscn")
	var v := Node3D.new()
	v.name = "CourierVan"
	_model(v, "van", {"brand": "ZIPPD"})
	_save(v, S + "vehicles/courier_van.tscn")


# ================================================================= premises levels

const LEVELS := ["garage", "lockup", "industrial", "warehouse", "distribution", "facility", "megaplant"]


func _build_levels() -> void:
	MatLib.save_all()
	var args := OS.get_cmdline_user_args()
	var only := ""
	if args.find("--only") >= 0:
		only = args[args.find("--only") + 1]
	for id in LEVELS:
		if only != "" and id != only:
			continue
		var path := "res://tools/buildings/%s.gd" % id
		if not ResourceLoader.exists(path):
			print("  (skip %s — no builder yet)" % id)
			continue
		var t := Time.get_ticks_msec()
		load(path).build()
		print("  built %s in %d ms" % [id, Time.get_ticks_msec() - t])


# ================================================================= legacy garage level (superseded by tools/buildings/garage.gd)

func _build_garage() -> void:
	var root := Node3D.new()
	root.name = "Garage"
	root.set_script(load("res://scripts/world/level_root.gd"))

	# ---------- environment & sun
	var we := WorldEnvironment.new()
	we.name = "WorldEnvironment"
	var env := Environment.new()
	var sky := Sky.new()
	var sm := ProceduralSkyMaterial.new()
	sm.sky_top_color = Color(0.3, 0.47, 0.72)
	sm.sky_horizon_color = Color(0.7, 0.76, 0.84)
	sm.ground_horizon_color = Color(0.6, 0.62, 0.62)
	sm.ground_bottom_color = Color(0.22, 0.22, 0.2)
	sm.sun_angle_max = 20.0
	sm.sky_energy_multiplier = 1.0
	sky.sky_material = sm
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.3
	env.ambient_light_sky_contribution = 0.85
	env.reflected_light_source = Environment.REFLECTION_SOURCE_SKY
	env.tonemap_mode = Environment.TONE_MAPPER_AGX
	env.tonemap_exposure = 1.0
	env.tonemap_white = 6.0
	env.ssao_enabled = true
	env.ssao_radius = 1.2
	env.ssao_intensity = 2.8
	env.ssao_power = 1.6
	env.ssil_enabled = true
	env.ssil_radius = 4.0
	env.ssil_intensity = 1.6
	env.glow_enabled = true
	env.glow_intensity = 0.35
	env.glow_bloom = 0.04
	env.glow_hdr_threshold = 1.2
	env.volumetric_fog_enabled = true
	env.volumetric_fog_density = 0.0065
	env.volumetric_fog_albedo = Color(0.95, 0.93, 0.9)
	env.volumetric_fog_anisotropy = 0.55
	env.volumetric_fog_length = 24.0
	env.volumetric_fog_ambient_inject = 0.0
	env.adjustment_enabled = true
	env.adjustment_contrast = 1.08
	env.adjustment_saturation = 1.08
	we.environment = env
	root.add_child(we)
	root.set("environment", we)

	var sun := DirectionalLight3D.new()
	sun.name = "Sun"
	sun.light_color = Color(1.0, 0.92, 0.82)
	sun.light_energy = 1.6
	sun.shadow_enabled = true
	sun.shadow_blur = 1.5
	sun.directional_shadow_max_distance = 80.0
	sun.light_volumetric_fog_energy = 1.4
	# Low afternoon sun from the street side so light pours in through the roller door.
	sun.rotation_degrees = Vector3(-24, 205, 0)
	root.add_child(sun)
	root.set("sun", sun)

	# ---------- garage shell
	var shell := Node3D.new()
	shell.name = "Shell"
	root.add_child(shell)
	var W := 6.0
	var D := 7.0
	var H := 2.7
	var T := 0.2
	var conc := Mats.surface("concrete_floor", 2.0)
	var block := Mats.surface("blockwork_painted", 1.8, Color(0.93, 0.9, 0.83))
	var brick := Mats.surface("brick_red", 0.9)
	_box(shell, "Floor", Vector3(W + 2 * T, 0.2, D + 2 * T), Vector3(0, -0.1, 0), conc)
	_box(shell, "WallBack", Vector3(W + 2 * T, H + 0.25, T), Vector3(0, (H + 0.25) / 2, -D / 2 - T / 2), block)
	_box(shell, "WallLeft", Vector3(T, H + 0.25, D), Vector3(-W / 2 - T / 2, (H + 0.25) / 2, 0), block)
	_box(shell, "WallRight", Vector3(T, H + 0.25, D), Vector3(W / 2 + T / 2, (H + 0.25) / 2, 0), block)
	var door_w := 4.2
	var door_h := 2.25
	var pier := (W + 2 * T - door_w) / 2
	_box(shell, "FrontPierL", Vector3(pier, H + 0.25, T), Vector3(-(door_w / 2 + pier / 2), (H + 0.25) / 2, D / 2 + T / 2), brick)
	_box(shell, "FrontPierR", Vector3(pier, H + 0.25, T), Vector3(door_w / 2 + pier / 2, (H + 0.25) / 2, D / 2 + T / 2), brick)
	_box(shell, "Lintel", Vector3(door_w, H + 0.25 - door_h, T), Vector3(0, door_h + (H + 0.25 - door_h) / 2, D / 2 + T / 2), brick)
	# Exterior brick skin on the side/back walls
	_box(shell, "SkinBack", Vector3(W + 2 * T + 0.04, H + 0.3, 0.02), Vector3(0, (H + 0.3) / 2, -D / 2 - T - 0.01), brick, Vector3.ZERO, false)
	_box(shell, "SkinRight", Vector3(0.02, H + 0.3, D + 2 * T), Vector3(W / 2 + T + 0.01, (H + 0.3) / 2, 0), brick, Vector3.ZERO, false)
	# Ceiling boards + joists, flat felt roof
	_box(shell, "Ceiling", Vector3(W, 0.02, D), Vector3(0, H + 0.01, 0), Mats.color(Color(0.86, 0.85, 0.82), 0.9), Vector3.ZERO, false)
	for i in 6:
		_box(shell, "Joist%d" % i, Vector3(W, 0.15, 0.05), Vector3(0, H - 0.075, -D / 2 + 0.6 + i * 1.15), Mats.surface("plywood", 0.45, Color(0.62, 0.52, 0.4)), Vector3.ZERO, false)
	_box(shell, "Roof", Vector3(W + 2 * T + 0.3, 0.12, D + 2 * T + 0.3), Vector3(0, H + 0.31, 0.05), Mats.color(Color(0.12, 0.12, 0.13), 0.85))
	_box(shell, "Fascia", Vector3(W + 2 * T + 0.3, 0.22, 0.03), Vector3(0, H + 0.26, D / 2 + T + 0.16), Mats.color(Color(0.95, 0.95, 0.95), 0.5), Vector3.ZERO, false)
	_box(shell, "Threshold", Vector3(door_w, 0.02, 0.12), Vector3(0, 0.01, D / 2 + T / 2), Mats.color(Color(0.25, 0.25, 0.26), 0.4, 0.6), Vector3.ZERO, false)
	# Painted dado band (garages often have the bottom of the walls painted dark) + grime line
	var dado := Mats.surface("painted_metal", 0.8, Color(0.36, 0.38, 0.4))
	_box(shell, "DadoBack", Vector3(W, 0.32, 0.01), Vector3(0, 0.16, -D / 2 + 0.006), dado, Vector3.ZERO, false)
	_box(shell, "DadoLeft", Vector3(0.01, 0.32, D), Vector3(-W / 2 + 0.006, 0.16, 0), dado, Vector3.ZERO, false)
	_box(shell, "DadoRight", Vector3(0.01, 0.32, D), Vector3(W / 2 - 0.006, 0.16, 0), dado, Vector3.ZERO, false)
	# Consumer unit by the door
	_box(shell, "ConsumerUnit", Vector3(0.36, 0.3, 0.1), Vector3(-W / 2 + 0.06, 1.9, D / 2 - 0.5), Mats.color(Color(0.95, 0.95, 0.95), 0.4), Vector3(0, 90, 0), false)

	var door := Node3D.new()
	door.name = "RollerDoor"
	door.set_script(load("res://scripts/world/roller_door.gd"))
	door.set("width", door_w)
	door.set("height", door_h)
	door.position = Vector3(0, 0, D / 2 + 0.05)
	root.add_child(door)
	var sw := StaticBody3D.new()
	sw.name = "DoorSwitch"
	sw.set_script(load("res://scripts/stations/simple_interactables.gd"))
	sw.set("kind", "door_switch")
	sw.set("title", "Roller door switch")
	sw.position = Vector3(2.15, 1.3, D / 2 - 0.02)
	_box(sw, "Box", Vector3(0.1, 0.16, 0.05), Vector3.ZERO, Mats.color(Color(0.9, 0.9, 0.88), 0.4), Vector3.ZERO, false)
	_box(sw, "Button", Vector3(0.04, 0.04, 0.02), Vector3(0, 0.03, -0.03), Mats.color(Color(0.1, 0.6, 0.2), 0.4), Vector3.ZERO, false)
	var swc := CollisionShape3D.new()
	var swb := BoxShape3D.new()
	swb.size = Vector3(0.16, 0.22, 0.1)
	swc.shape = swb
	sw.add_child(swc)
	root.add_child(sw)
	sw.set("target", door)

	# ---------- lighting (fluorescent strips)
	var lights := Node3D.new()
	lights.name = "Lights"
	root.add_child(lights)
	var strip_pos := [Vector3(-1.3, H, -1.6), Vector3(1.3, H, -1.6), Vector3(0, H, 1.2)]
	for i in strip_pos.size():
		var sp: Vector3 = strip_pos[i]
		_inst(lights, S + "props/strip_light.tscn", "StripLight%d" % i, sp)
		var ol := OmniLight3D.new()
		ol.name = "StripGlow%d" % i
		ol.position = sp + Vector3(0, -0.15, 0)
		ol.light_color = Color(0.93, 0.96, 1.0)
		ol.light_energy = 1.45
		ol.omni_range = 6.0
		ol.omni_attenuation = 1.1
		ol.shadow_enabled = i == 0
		ol.light_volumetric_fog_energy = 0.15
		lights.add_child(ol)
	var rp := ReflectionProbe.new()
	rp.name = "ReflectionProbe"
	rp.size = Vector3(W, H, D)
	rp.position = Vector3(0, H / 2, 0)
	rp.box_projection = true
	rp.update_mode = ReflectionProbe.UPDATE_ONCE
	lights.add_child(rp)

	# ---------- stations
	var st := Node3D.new()
	st.name = "Stations"
	root.add_child(st)
	_inst(st, S + "stations/office_desk.tscn", "OfficeDesk", Vector3(-2.63, 0, -2.55), 90)
	_inst(st, S + "stations/test_bench.tscn", "TestBench1", Vector3(-0.85, 0, -3.1), 0, {"station_id": "bench_1", "display_name": "Test bench 1"})
	_inst(st, S + "stations/repair_bench.tscn", "RepairBench1", Vector3(0.95, 0, -3.1), 0, {"station_id": "repair_1"})
	_inst(st, S + "stations/parts_shelf.tscn", "PartsShelf", Vector3(2.45, 0, -3.27), 0, {"station_id": "parts"})
	_inst(st, S + "stations/grading_desk.tscn", "GradingDesk", Vector3(2.63, 0, -1.25), -90, {"station_id": "grade_1"})
	_inst(st, S + "stations/packing_table.tscn", "PackingTable", Vector3(2.58, 0, 0.55), -90, {"station_id": "pack_1"})
	_inst(st, S + "stations/courier_shelf.tscn", "CourierShelf", Vector3(2.73, 0, 2.6), -90, {"station_id": "courier_1"})
	_inst(st, S + "stations/metal_shelf.tscn", "ShelfA", Vector3(-2.75, 0, -0.85), 90, {"station_id": "shelf_a", "short_label": "Shelf A", "display_name": "Shelf A"})
	_inst(st, S + "stations/metal_shelf.tscn", "ShelfB", Vector3(-2.75, 0, 0.55), 90, {"station_id": "shelf_b", "short_label": "Shelf B", "display_name": "Shelf B"})
	_inst(st, S + "stations/kettle_corner.tscn", "KettleCorner", Vector3(-2.68, 0, 2.6), 90)

	# ---------- set dressing
	var pr := Node3D.new()
	pr.name = "Props"
	root.add_child(pr)
	_inst(pr, S + "props/bin.tscn", "Bin", Vector3(1.95, 0, 1.75))
	_inst(pr, S + "props/ladder.tscn", "Ladder", Vector3(2.9, 0, -2.3), -90).rotation_degrees.z = 0
	(pr.get_node("Ladder") as Node3D).rotation_degrees = Vector3(-8, -90, 0)
	_inst(pr, S + "props/paint_tins.tscn", "PaintTins", Vector3(-1.9, 0, -3.35))
	_inst(pr, S + "props/cable_reel.tscn", "CableReel", Vector3(1.8, 0, -2.45), 30)
	_inst(pr, S + "props/cardboard_box.tscn", "Boxes1", Vector3(-2.65, 1.94, -1.25), 90)
	_inst(pr, S + "props/cardboard_box.tscn", "Boxes2", Vector3(-2.7, 1.94, -0.5), 75)
	_inst(pr, S + "props/cardboard_box.tscn", "Boxes3", Vector3(2.55, 0.0, 2.0), 12)
	_inst(pr, S + "props/pallet.tscn", "SparePallet", Vector3(2.9, 0.62, 1.4), 0).rotation_degrees = Vector3(0, -90, 82)
	# Posters & signs (fictional, UK)
	_poster(pr, "PosterESD", Vector3(-0.85, 1.85, -D / 2 + 0.005), 0, ["ESD PROTECTED AREA", "Wear a wrist strap", "Handle boards by the edges"], Color(0.98, 0.84, 0.12), Color(0.1, 0.1, 0.1))
	_poster(pr, "PosterWipe", Vector3(W / 2 - 0.005, 1.7, -1.25), -90, ["DATA WIPED.", "TRUST EARNED.", "Every drive. Every time."], Color(0.12, 0.18, 0.28), Color(0.95, 0.95, 0.95))
	_poster(pr, "PosterGig", Vector3(-W / 2 + 0.005, 1.6, 2.0), 90, ["THE SOLDER", "IRONS", "Live at the Crown · Fri"], Color(0.8, 0.25, 0.2), Color(1, 0.95, 0.85))
	_poster(pr, "Whiteboard", Vector3(-W / 2 + 0.005, 1.5, -2.55), 90, ["TO DO", "- rent FRIDAY", "- order more boxes", "- ring Gareth re: bank lot"], Color(0.97, 0.97, 0.97), Color(0.15, 0.25, 0.6))

	# ---------- outside: drive, pavement, road, houses
	var ext := Node3D.new()
	ext.name = "Exterior"
	root.add_child(ext)
	var tar := Mats.surface("tarmac", 3.0)
	_box(ext, "Drive", Vector3(7.0, 0.1, 8.3), Vector3(0, -0.05, D / 2 + T + 4.15), tar)
	_box(ext, "Pavement", Vector3(120, 0.12, 2.2), Vector3(0, -0.04, 13.0), Mats.surface("concrete_floor", 1.2, Color(0.82, 0.82, 0.8)))
	_box(ext, "KerbNear", Vector3(120, 0.14, 0.15), Vector3(0, -0.03, 14.15), Mats.color(Color(0.6, 0.6, 0.58), 0.8))
	_box(ext, "Road", Vector3(120, 0.1, 7.0), Vector3(0, -0.1, 17.7), tar)
	for i in 24:
		_box(ext, "Dash%d" % i, Vector3(1.6, 0.005, 0.1), Vector3(-57 + i * 5, -0.045, 17.7), Mats.color(Color(0.92, 0.92, 0.9), 0.7), Vector3.ZERO, false)
	_box(ext, "KerbFar", Vector3(120, 0.14, 0.15), Vector3(0, -0.03, 21.25), Mats.color(Color(0.6, 0.6, 0.58), 0.8))
	_box(ext, "PavementFar", Vector3(120, 0.12, 2.2), Vector3(0, -0.04, 22.4), Mats.surface("concrete_floor", 1.2, Color(0.82, 0.82, 0.8)))
	_box(ext, "GrassL", Vector3(50, 0.1, 9.0), Vector3(-28.5, -0.06, 7.6), Mats.color(Color(0.24, 0.36, 0.16), 0.95))
	_box(ext, "GrassR", Vector3(50, 0.1, 9.0), Vector3(28.5, -0.06, 7.6), Mats.color(Color(0.24, 0.36, 0.16), 0.95))
	_box(ext, "GrassBack", Vector3(120, 0.1, 30), Vector3(0, -0.07, -12), Mats.color(Color(0.22, 0.33, 0.15), 0.95))
	# The family house to the left of the garage
	_box(ext, "House", Vector3(9.0, 5.4, 10.0), Vector3(-7.7, 2.7, -1.3), brick)
	_box(ext, "HouseRoof", Vector3(9.6, 0.25, 6.4), Vector3(-7.7, 6.3, 0.9), Mats.color(Color(0.25, 0.22, 0.22), 0.8), Vector3(32, 0, 0))
	_box(ext, "HouseRoof2", Vector3(9.6, 0.25, 6.4), Vector3(-7.7, 6.3, -3.5), Mats.color(Color(0.25, 0.22, 0.22), 0.8), Vector3(-32, 0, 0))
	for wi in 4:
		var wx := -10.6 + (wi % 2) * 4.2
		var wy := 1.5 + (wi / 2) * 2.6
		_box(ext, "Window%d" % wi, Vector3(1.3, 1.2, 0.06), Vector3(wx, wy, 3.72), Mats.color(Color(0.95, 0.95, 0.95), 0.4), Vector3.ZERO, false)
		_box(ext, "Glass%d" % wi, Vector3(1.15, 1.05, 0.02), Vector3(wx, wy, 3.76), Mats.glass(Color(0.2, 0.25, 0.3, 0.85)), Vector3.ZERO, false)
	_box(ext, "FrontDoor", Vector3(1.0, 2.1, 0.08), Vector3(-5.2, 1.05, 3.72), Mats.color(Color(0.1, 0.25, 0.4), 0.35), Vector3.ZERO, false)
	# Neighbour fence on the right
	for fi in 5:
		_box(ext, "Fence%d" % fi, Vector3(0.05, 1.8, 1.8), Vector3(3.75, 0.9, 4.5 + fi * 1.85), Mats.surface("plywood", 0.7, Color(0.55, 0.4, 0.28)))
	# Houses across the road (simple terraced block) and hedges
	for hi in 6:
		var hx := -30 + hi * 11.0
		_box(ext, "Opposite%d" % hi, Vector3(10.4, 6.0, 8.0), Vector3(hx, 3.0, 29.5), brick, Vector3.ZERO, false)
		_box(ext, "OppRoof%d" % hi, Vector3(10.6, 0.25, 5.2), Vector3(hx, 6.8, 27.8), Mats.color(Color(0.3, 0.25, 0.24), 0.8), Vector3(-34, 0, 0), false)
		_box(ext, "Hedge%d" % hi, Vector3(9.0, 1.2, 0.9), Vector3(hx, 0.6, 24.2), Mats.color(Color(0.16, 0.3, 0.12), 0.95), Vector3.ZERO, false)
	var lamp := Node3D.new()
	lamp.name = "StreetLamp"
	lamp.position = Vector3(5.5, 0, 13.6)
	ext.add_child(lamp)
	_box(lamp, "Post", Vector3(0.12, 6.0, 0.12), Vector3(0, 3.0, 0), Mats.painted_metal(Color(0.2, 0.22, 0.2)))
	_box(lamp, "Head", Vector3(0.3, 0.12, 0.9), Vector3(0, 6.0, 0.4), Mats.painted_metal(Color(0.2, 0.22, 0.2)), Vector3.ZERO, false)
	# Invisible boundary so players can't wander off down the street
	_box(ext, "BoundaryRoad", Vector3(30, 3, 0.2), Vector3(0, 1.5, 14.25), Mats.color(Color(0, 0, 0, 0)), Vector3.ZERO, true).visible = false
	_box(ext, "BoundaryL", Vector3(0.2, 3, 12), Vector3(-3.6, 1.5, 8.0), Mats.color(Color(0, 0, 0, 0)), Vector3.ZERO, true).visible = false

	# ---------- markers
	var mk := Node3D.new()
	mk.name = "Markers"
	root.add_child(mk)
	var spawns: Array[Node3D] = []
	for i in 4:
		spawns.append(_marker(mk, "Spawn%d" % i, Vector3(-0.6 + i * 0.45, 0.05, 1.6), 0))
	root.set("spawn_points", spawns)
	var safe := _marker(mk, "SafeDrop", Vector3(0, 1.0, 1.0))
	root.set("safe_drop", safe)
	var pallet_drop := _marker(mk, "PalletDrop", Vector3(0, 0.02, 5.2))
	root.set("lorry_drop", pallet_drop)
	var parcel_drop := _marker(mk, "ParcelDrop", Vector3(1.4, 0.3, 4.4))
	root.set("parcel_drop", parcel_drop)

	# ---------- vehicles
	var vd := Node3D.new()
	vd.name = "VehicleDirector"
	vd.set_script(load("res://scripts/vehicles/vehicle_director.gd"))
	root.add_child(vd)
	var lorry := _inst(vd, S + "vehicles/lorry.tscn", "Lorry", Vector3(48, 0, 15))
	var van := _inst(vd, S + "vehicles/courier_van.tscn", "CourierVan", Vector3(48, 0, 13.6))
	vd.set("pallet_drop", pallet_drop)
	vd.set("parcel_drop", parcel_drop)
	_save_garage(root, vd, lorry, van)


func _save_garage(root: Node3D, vd: Node3D, lorry: Node3D, van: Node3D) -> void:
	# ModelSlot refs inside instanced vehicle scenes can't be exported across instance boundaries before packing,
	# so the director looks them up by path at runtime instead.
	vd.set("lorry_model", null)
	vd.set("van_model", null)
	_save(root, S + "levels/garage/garage.tscn")


func _poster(parent: Node, name: String, pos: Vector3, rot_y: float, lines: Array, bg: Color, fg: Color) -> void:
	var holder := Node3D.new()
	holder.name = name
	holder.position = pos
	holder.rotation_degrees.y = rot_y
	parent.add_child(holder)
	var ms := ModelSlot.new()
	ms.name = "Model"
	ms.placeholder = "poster"
	ms.placeholder_params = {"lines": lines, "bg": bg, "fg": fg, "size": Vector2(0.6, 0.8) if name == "Whiteboard" else Vector2(0.42, 0.59)}
	holder.add_child(ms)
