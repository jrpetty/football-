class_name Site
extends RefCounted
## Shared level wiring for every premises scene: environment presets, ground/horizon, vehicle routes,
## LevelRoot exports (spawns, layouts, floor rects), and saving.

const PRESETS := {
	# sky top, horizon, sun colour, sun energy, sun rot (x, y), ambient, fog density, exposure, saturation
	"suburb": {"top": Color(0.3, 0.47, 0.72), "hor": Color(0.7, 0.76, 0.84), "sun": Color(1.0, 0.9, 0.78), "sun_e": 1.6, "rot": Vector2(-24, 205), "amb": 0.3, "fog": 0.0065, "exp": 1.0, "sat": 1.06},
	"overcast": {"top": Color(0.42, 0.5, 0.6), "hor": Color(0.72, 0.74, 0.76), "sun": Color(0.95, 0.94, 0.9), "sun_e": 1.15, "rot": Vector2(-38, 215), "amb": 0.42, "fog": 0.008, "exp": 1.05, "sat": 0.95},
	"morning": {"top": Color(0.28, 0.48, 0.78), "hor": Color(0.74, 0.8, 0.88), "sun": Color(1.0, 0.94, 0.85), "sun_e": 1.7, "rot": Vector2(-30, 145), "amb": 0.38, "fog": 0.005, "exp": 1.0, "sat": 1.08},
	"clear": {"top": Color(0.22, 0.44, 0.8), "hor": Color(0.7, 0.8, 0.9), "sun": Color(1.0, 0.95, 0.88), "sun_e": 1.8, "rot": Vector2(-42, 200), "amb": 0.42, "fog": 0.004, "exp": 1.0, "sat": 1.1},
	"golden": {"top": Color(0.25, 0.42, 0.75), "hor": Color(0.88, 0.78, 0.66), "sun": Color(1.0, 0.82, 0.6), "sun_e": 1.9, "rot": Vector2(-18, 215), "amb": 0.4, "fog": 0.004, "exp": 1.0, "sat": 1.14},
	"epic": {"top": Color(0.2, 0.38, 0.72), "hor": Color(0.86, 0.8, 0.72), "sun": Color(1.0, 0.85, 0.66), "sun_e": 2.0, "rot": Vector2(-22, 230), "amb": 0.42, "fog": 0.0028, "exp": 1.0, "sat": 1.12},
}


static func make_root(building_id: String) -> Node3D:
	var root := Node3D.new()
	root.name = building_id.capitalize().replace(" ", "")
	root.set_script(load("res://scripts/world/level_root.gd"))
	root.set("building_id", building_id)
	return root


static func environment(root: Node3D, preset: String, interior_boost: float = 0.0) -> void:
	var p: Dictionary = PRESETS[preset]
	var we := WorldEnvironment.new()
	we.name = "WorldEnvironment"
	var env := Environment.new()
	var sky := Sky.new()
	var sm := ProceduralSkyMaterial.new()
	sm.sky_top_color = p.top
	sm.sky_horizon_color = p.hor
	sm.ground_horizon_color = p.hor.darkened(0.15)
	sm.ground_bottom_color = Color(0.2, 0.22, 0.18)
	sm.sun_angle_max = 25.0
	sm.sky_curve = 0.12
	sky.sky_material = sm
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = float(p.amb) + interior_boost
	env.ambient_light_sky_contribution = 0.85
	env.reflected_light_source = Environment.REFLECTION_SOURCE_SKY
	env.tonemap_mode = Environment.TONE_MAPPER_AGX
	env.tonemap_exposure = float(p.exp)
	env.tonemap_white = 6.0
	env.ssao_enabled = true
	env.ssao_radius = 1.4
	env.ssao_intensity = 2.6
	env.ssao_power = 1.5
	env.ssil_enabled = true
	env.ssil_radius = 5.0
	env.ssil_intensity = 1.4
	env.glow_enabled = true
	env.glow_intensity = 0.4
	env.glow_bloom = 0.05
	env.glow_hdr_threshold = 1.1
	env.volumetric_fog_enabled = true
	env.volumetric_fog_density = float(p.fog)
	env.volumetric_fog_albedo = Color(0.95, 0.93, 0.9)
	env.volumetric_fog_anisotropy = 0.6
	env.volumetric_fog_length = 40.0
	env.fog_enabled = true
	env.fog_light_color = p.hor
	env.fog_density = 0.0004
	env.fog_sky_affect = 0.0
	env.fog_aerial_perspective = 0.2
	env.adjustment_enabled = true
	env.adjustment_contrast = 1.08
	env.adjustment_saturation = float(p.sat)
	we.environment = env
	root.add_child(we)
	root.set("environment", we)
	var sun := DirectionalLight3D.new()
	sun.name = "Sun"
	sun.light_color = p.sun
	sun.light_energy = float(p.sun_e)
	sun.shadow_enabled = true
	sun.shadow_blur = 1.5
	sun.directional_shadow_max_distance = 140.0
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_PARALLEL_4_SPLITS
	sun.light_volumetric_fog_energy = 1.3
	sun.rotation_degrees = Vector3(p.rot.x, p.rot.y, 0)
	root.add_child(sun)
	root.set("sun", sun)


static func ground(kit: BuildKit, half: float = 300.0, hill_r: float = 0.0, y: float = -0.12, holes: Array = []) -> void:
	kit.ground_with_holes(Rect2(-half, -half, half * 2, half * 2), holes, "grass", y)
	if hill_r > 0.0:
		kit.hills(hill_r, 16, -2.0)


static func layout(root: Node3D, spec: Dictionary) -> void:
	## spec: { "office_desk": [Vector3 pos, rot_y], "test_bench": [...], "test_bench_2": [...] … }
	var lay := Node3D.new()
	lay.name = "Layout"
	root.add_child(lay)
	for k in spec:
		var m := Marker3D.new()
		m.name = k
		m.position = spec[k][0]
		m.rotation_degrees.y = float(spec[k][1])
		lay.add_child(m)
	root.set("layout", lay)


static func markers(root: Node3D, spawn: Vector3, spawn_yaw: float, safe: Vector3, goods_in: Vector3, parcel: Vector3) -> Dictionary:
	var mk := Node3D.new()
	mk.name = "Markers"
	root.add_child(mk)
	var spawns: Array[Node3D] = []
	var b := Basis(Vector3.UP, deg_to_rad(spawn_yaw))
	for i in 4:
		var m := Marker3D.new()
		m.name = "Spawn%d" % i
		m.position = spawn + b * Vector3(-0.7 + i * 0.47, 0.05, 0)
		m.rotation_degrees.y = spawn_yaw
		mk.add_child(m)
		spawns.append(m)
	root.set("spawn_points", spawns)
	var out := {}
	for pair in [["SafeDrop", safe, "safe_drop"], ["GoodsIn", goods_in, "goods_in"], ["ParcelDrop", parcel, "parcel_drop"]]:
		var m2 := Marker3D.new()
		m2.name = pair[0]
		m2.position = pair[1]
		mk.add_child(m2)
		root.set(pair[2], m2)
		out[pair[0]] = m2
	return out


static func vehicles(root: Node3D, lorry_in: Array, docks: Array, lorry_out: Array, van_in: Array, van_park: Array, van_out: Array, parcel_drop: Node3D) -> Node3D:
	## docks: [[Vector3 pos, yaw_deg, is_dock(bool), Vector3 drop_local]] for tail-lift spots; dock markers from kit.dock()
	## can be appended later under VehicleDirector/Docks.
	var vd := Node3D.new()
	vd.name = "VehicleDirector"
	vd.set_script(load("res://scripts/vehicles/vehicle_director.gd"))
	root.add_child(vd)
	for grp in [["LorryIn", lorry_in], ["LorryOut", lorry_out], ["VanIn", van_in], ["VanOut", van_out]]:
		var g := Node3D.new()
		g.name = grp[0]
		vd.add_child(g)
		var i := 0
		for p in grp[1]:
			var m := Marker3D.new()
			m.name = "P%d" % i
			m.position = p
			g.add_child(m)
			i += 1
	var dk := Node3D.new()
	dk.name = "Docks"
	vd.add_child(dk)
	for d in docks:
		var m := Marker3D.new()
		m.name = "Spot%d" % dk.get_child_count()
		m.position = d[0]
		m.rotation_degrees.y = float(d[1])
		m.set_meta("dock", bool(d[2]))
		dk.add_child(m)
		var drop := Marker3D.new()
		drop.name = "Drop"
		drop.position = d[3]
		m.add_child(drop)
	var vp := Marker3D.new()
	vp.name = "VanPark"
	vp.position = van_park[0]
	vp.rotation_degrees.y = float(van_park[1])
	if van_park.size() > 2:
		vp.set_meta("reverse_out", bool(van_park[2]))
	vd.add_child(vp)
	vd.set("parcel_drop", parcel_drop)
	var lorry: Node3D = load("res://scenes/vehicles/lorry.tscn").instantiate()
	lorry.name = "Lorry"
	lorry.position = Vector3(0, -50, 0)
	vd.add_child(lorry)
	var van: Node3D = load("res://scenes/vehicles/courier_van.tscn").instantiate()
	van.name = "CourierVan"
	van.position = Vector3(0, -50, 0)
	vd.add_child(van)
	return vd


static func area(root: Node3D, floors: Array, keepouts: Array, street_sound: Vector3, hum: Vector3) -> void:
	var fr: Array[Rect2] = []
	for r in floors:
		fr.append(r)
	var kr: Array[Rect2] = []
	for r in keepouts:
		kr.append(r)
	root.set("floor_rects", fr)
	root.set("keepout_rects", kr)
	root.set("street_sound_pos", street_sound)
	root.set("hum_sound_pos", hum)


static func dressing_scene(root: Node3D, path: String, name: String, pos: Vector3, rot_y: float) -> Node3D:
	var n: Node3D = load(path).instantiate()
	n.name = name
	n.position = pos
	n.rotation_degrees.y = rot_y
	root.add_child(n)
	return n


static func save(root: Node3D, path: String) -> void:
	_own(root, root)
	var ps := PackedScene.new()
	var err := ps.pack(root)
	if err != OK:
		push_error("pack failed %s" % path)
		return
	DirAccess.make_dir_recursive_absolute(path.get_base_dir())
	err = ResourceSaver.save(ps, path)
	print("  wrote ", path, " (", err, ")")
	root.free()


static func _own(n: Node, root: Node) -> void:
	for c in n.get_children():
		if c.owner == null:
			c.owner = root
		if c.scene_file_path == "":
			_own(c, root)
