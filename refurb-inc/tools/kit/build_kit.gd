class_name BuildKit
extends RefCounted
## Build-time architecture kit. Every primitive is batched per material into ONE mesh (saved as .res), so a whole
## distribution centre is a handful of draw calls. Box colliders are gathered onto a single StaticBody3D.
## Interactive/animated pieces (doors, lights, signs, decals, markers) are real nodes.
## Coordinates: metres, Y up, building fronts face +Z toward the road.

var root: Node3D
var out_dir: String
var rng := RandomNumberGenerator.new()
var _st: Dictionary = {}          # mat key -> SurfaceTool
var _shapes: Array = []           # [Transform3D, Vector3 size]
var _no_shadow: Dictionary = {"glass": true, "glass_blue": true, "glass_dark": true, "rooflight": true, "chainlink": true, "line_white": true, "line_yellow": true, "line_blue": true, "line_green": true, "line_red": true, "led_panel": true, "led_warm": true, "water": true}
var nodes: Node3D                 # container for interactive/special nodes
var signs: Node3D
var lights: Node3D
var decals: Node3D

static var _unit_box: BoxMesh
static var _unit_prism: PrismMesh
static var _right_prism: PrismMesh
static var _cyl_cache: Dictionary = {}
static var _sph_cache: Dictionary = {}
static var _shape_cache: Dictionary = {}


func _init(r: Node3D, dir: String, seed_value: int = 1) -> void:
	root = r
	out_dir = dir
	rng.seed = seed_value
	DirAccess.make_dir_recursive_absolute(dir)
	if _unit_box == null:
		_unit_box = BoxMesh.new()
		_unit_box.size = Vector3.ONE
		_unit_prism = PrismMesh.new()
		_unit_prism.size = Vector3.ONE
		_unit_prism.left_to_right = 0.5
		_right_prism = PrismMesh.new()
		_right_prism.size = Vector3.ONE
		_right_prism.left_to_right = 0.0
	nodes = _group("Nodes")
	signs = _group("Signs")
	lights = _group("Lights")
	decals = _group("Decals")


func _group(n: String) -> Node3D:
	var g := Node3D.new()
	g.name = n
	root.add_child(g)
	return g


static func _rot(rot_deg: Vector3) -> Basis:
	return Basis.from_euler(rot_deg * (PI / 180.0))


func _add(mesh: Mesh, xf: Transform3D, key: String) -> void:
	var st: SurfaceTool = _st.get(key)
	if st == null:
		st = SurfaceTool.new()
		st.begin(Mesh.PRIMITIVE_TRIANGLES)
		_st[key] = st
	st.append_from(mesh, 0, xf)


func _collide(size: Vector3, xf: Transform3D) -> void:
	_shapes.append([xf, size])


# ================================================================= primitives

func box(size: Vector3, pos: Vector3, key: String, rot_deg: Vector3 = Vector3.ZERO, collide: bool = true) -> void:
	var b := _rot(rot_deg)
	_add(_unit_box, Transform3D(b * Basis.from_scale(size), pos), key)
	if collide:
		_collide(size, Transform3D(b, pos))


func box_xf(size: Vector3, xf: Transform3D, key: String, collide: bool = true) -> void:
	_add(_unit_box, Transform3D(xf.basis * Basis.from_scale(size), xf.origin), key)
	if collide:
		_collide(size, xf)


func box_ab(a: Vector3, b: Vector3, key: String, collide: bool = true) -> void:
	## Axis-aligned box from two opposite corners.
	var lo := Vector3(minf(a.x, b.x), minf(a.y, b.y), minf(a.z, b.z))
	var hi := Vector3(maxf(a.x, b.x), maxf(a.y, b.y), maxf(a.z, b.z))
	box(hi - lo, (lo + hi) * 0.5, key, Vector3.ZERO, collide)


func beam(a: Vector3, b: Vector3, thick: Vector2, key: String, collide: bool = false) -> void:
	## A member from a to b with cross-section thick (x = width, y = depth).
	var d := b - a
	var length := d.length()
	if length < 0.001:
		return
	var z := d / length
	var up := Vector3.UP if absf(z.dot(Vector3.UP)) < 0.95 else Vector3.RIGHT
	var x := up.cross(z).normalized()
	var y := z.cross(x).normalized()
	var basis := Basis(x, y, z)
	box_xf(Vector3(thick.x, thick.y, length), Transform3D(basis, (a + b) * 0.5), key, collide)


func i_beam(a: Vector3, b: Vector3, depth: float, flange: float, key: String) -> void:
	## Steel I-section (web + two flanges) between two points, web vertical-ish.
	var d := b - a
	var length := d.length()
	if length < 0.001:
		return
	var z := d / length
	var up := Vector3.UP if absf(z.dot(Vector3.UP)) < 0.95 else Vector3.FORWARD
	var x := up.cross(z).normalized()
	var y := z.cross(x).normalized()
	var basis := Basis(x, y, z)
	var c := (a + b) * 0.5
	var t := maxf(depth * 0.06, 0.012)
	box_xf(Vector3(t, depth, length), Transform3D(basis, c), key, false)
	box_xf(Vector3(flange, t * 1.4, length), Transform3D(basis, c + y * (depth * 0.5)), key, false)
	box_xf(Vector3(flange, t * 1.4, length), Transform3D(basis, c - y * (depth * 0.5)), key, false)


func cyl(radius: float, height: float, pos: Vector3, key: String, rot_deg: Vector3 = Vector3.ZERO, sides: int = 12, collide: bool = false, top_ratio: float = 1.0) -> void:
	var ck := "%d|%f" % [sides, top_ratio]
	var m: CylinderMesh = _cyl_cache.get(ck)
	if m == null:
		m = CylinderMesh.new()
		m.top_radius = 0.5 * top_ratio
		m.bottom_radius = 0.5
		m.height = 1.0
		m.radial_segments = sides
		m.rings = 1
		_cyl_cache[ck] = m
	var b := _rot(rot_deg)
	_add(m, Transform3D(b * Basis.from_scale(Vector3(radius * 2, height, radius * 2)), pos), key)
	if collide:
		_collide(Vector3(radius * 1.6, height, radius * 1.6), Transform3D(b, pos))


func sphere(radius: float, pos: Vector3, key: String, squash: Vector3 = Vector3.ONE, segs: int = 10) -> void:
	var m: SphereMesh = _sph_cache.get(segs)
	if m == null:
		m = SphereMesh.new()
		m.radius = 0.5
		m.height = 1.0
		m.radial_segments = segs
		m.rings = maxi(segs / 2, 4)
		_sph_cache[segs] = m
	_add(m, Transform3D(Basis.from_scale(squash * radius * 2), pos), key)


func prism(size: Vector3, pos: Vector3, key: String, rot_deg: Vector3 = Vector3.ZERO) -> void:
	## Triangular prism (gable ends): apex at top centre, base width = size.x, height = size.y, depth = size.z.
	var b := _rot(rot_deg)
	_add(_unit_prism, Transform3D(b * Basis.from_scale(size), pos), key)


func rprism(size: Vector3, pos: Vector3, key: String, rot_deg: Vector3 = Vector3.ZERO) -> void:
	## Right-angled prism (apex above the local -X edge) — sawtooth roof ends.
	var b := _rot(rot_deg)
	_add(_right_prism, Transform3D(b * Basis.from_scale(size), pos), key)


# ================================================================= nodes

func label(text: String, pos: Vector3, rot_deg: Vector3, font_size: int, col: Color, pixel: float = 0.004, outline: int = 0, parent: Node3D = null) -> Label3D:
	var l := Label3D.new()
	l.text = text
	l.font_size = font_size
	l.pixel_size = pixel
	l.modulate = col
	l.outline_size = outline
	l.position = pos
	l.rotation_degrees = rot_deg
	l.double_sided = false
	l.shaded = true
	(parent if parent else signs).add_child(l)
	return l


func company_sign(pos: Vector3, rot_deg: Vector3, font_size: int, col: Color, pixel: float, upper: bool = true, suffix: String = "") -> Label3D:
	## Shows the player's company name (updates if renamed). Rendered unshaded so it reads like an illuminated sign.
	var l := label("REFURB INC.", pos, rot_deg, font_size, col, pixel, 0)
	l.set_script(load("res://scripts/world/company_sign.gd"))
	l.set("caps", upper)
	l.set("suffix", suffix)
	l.shaded = false
	return l


func omni(pos: Vector3, energy: float, rng_m: float, col: Color = Color(0.95, 0.97, 1.0), shadow: bool = false) -> OmniLight3D:
	var o := OmniLight3D.new()
	o.position = pos
	o.light_energy = energy
	o.omni_range = rng_m
	o.light_color = col
	o.shadow_enabled = shadow
	o.light_volumetric_fog_energy = 0.15
	lights.add_child(o)
	return o


func spot(pos: Vector3, rot_deg: Vector3, energy: float, rng_m: float, angle: float, col: Color = Color(0.95, 0.97, 1.0), shadow: bool = false) -> SpotLight3D:
	var o := SpotLight3D.new()
	o.position = pos
	o.rotation_degrees = rot_deg
	o.light_energy = energy
	o.spot_range = rng_m
	o.spot_angle = angle
	o.light_color = col
	o.shadow_enabled = shadow
	o.light_volumetric_fog_energy = 0.3
	lights.add_child(o)
	return o


func decal(name: String, pos: Vector3, size: Vector3, rot_y: float = 0.0, modulate: Color = Color.WHITE, wall_dir: Vector3 = Vector3.ZERO) -> Decal:
	## Floor decal by default (projects down). For walls pass wall_dir = the direction the wall faces.
	var d := Decal.new()
	var path := "res://assets/textures/decals/%s/albedo.png" % name
	if ResourceLoader.exists(path):
		d.texture_albedo = load(path)
	var npath := "res://assets/textures/decals/%s/normal.png" % name
	if ResourceLoader.exists(npath):
		d.texture_normal = load(npath)
	d.size = size
	d.modulate = modulate
	d.position = pos
	if wall_dir != Vector3.ZERO:
		# Decal projects along its local -Y; point that into the wall.
		var y := wall_dir.normalized()
		var x := Vector3.UP.cross(y).normalized() if absf(y.y) < 0.9 else Vector3.RIGHT
		var z := x.cross(y).normalized()
		d.basis = Basis(x, y, z)
	else:
		d.rotation_degrees.y = rot_y
	d.cull_mask = 1
	d.upper_fade = 0.2
	d.lower_fade = 0.2
	decals.add_child(d)
	return d


func roller_door(name: String, pos: Vector3, rot_y: float, w: float, h: float, open: bool) -> Node3D:
	var door := Node3D.new()
	door.name = name
	door.set_script(load("res://scripts/world/roller_door.gd"))
	door.set("width", w)
	door.set("height", h)
	door.set("start_open", open)
	door.position = pos
	door.rotation_degrees.y = rot_y
	nodes.add_child(door)
	return door


func door_switch(pos: Vector3, rot_y: float, door: Node3D) -> void:
	var sw := StaticBody3D.new()
	sw.name = "Switch_" + door.name
	sw.set_script(load("res://scripts/stations/simple_interactables.gd"))
	sw.set("kind", "door_switch")
	sw.set("title", "Roller door switch")
	sw.position = pos
	sw.rotation_degrees.y = rot_y
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(0.12, 0.2, 0.06)
	mi.mesh = bm
	mi.material_override = MatLib.mat("paint_white")
	sw.add_child(mi)
	var btn := MeshInstance3D.new()
	var bb := BoxMesh.new()
	bb.size = Vector3(0.05, 0.05, 0.02)
	btn.mesh = bb
	btn.position = Vector3(0, 0.04, 0.035)
	btn.material_override = MatLib.mat("line_green")
	sw.add_child(btn)
	var cs := CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = Vector3(0.18, 0.26, 0.12)
	cs.shape = bs
	sw.add_child(cs)
	nodes.add_child(sw)
	sw.set("target", door)


func marker(parent: Node, name: String, pos: Vector3, rot_y: float = 0.0) -> Marker3D:
	var m := Marker3D.new()
	m.name = name
	m.position = pos
	m.rotation_degrees.y = rot_y
	parent.add_child(m)
	return m


# ================================================================= architecture

func wall(a: Vector2, b: Vector2, height: float, thick: float, key: String, openings: Array = [], base_y: float = 0.0, inner_key: String = "") -> void:
	## Straight wall in XZ from a to b. openings: [{"at": dist from a to opening centre, "w", "h", "sill"}].
	## inner_key: optional different finish on the inside face (adds a thin liner on the left-hand side).
	var dir := (b - a)
	var length := dir.length()
	if length < 0.01:
		return
	dir /= length
	var yaw := atan2(dir.x, dir.y)        # local +Z along the wall
	var basis := Basis(Vector3.UP, yaw)
	var ops := openings.duplicate()
	ops.sort_custom(func(p, q): return float(p.at) < float(q.at))
	var cur := 0.0
	var pieces: Array = []                 # [start, end, y0, y1]
	for o in ops:
		var s := float(o.at) - float(o.w) * 0.5
		var e := float(o.at) + float(o.w) * 0.5
		if s > cur:
			pieces.append([cur, s, 0.0, height])
		var sill := float(o.get("sill", 0.0))
		if sill > 0.0:
			pieces.append([s, e, 0.0, sill])
		if float(o.h) + sill < height:
			pieces.append([s, e, sill + float(o.h), height])
		cur = e
	if cur < length:
		pieces.append([cur, length, 0.0, height])
	for p in pieces:
		var mid := (float(p[0]) + float(p[1])) * 0.5
		var c2 := a + dir * mid
		var size := Vector3(thick, float(p[3]) - float(p[2]), float(p[1]) - float(p[0]))
		var pos := Vector3(c2.x, base_y + (float(p[2]) + float(p[3])) * 0.5, c2.y)
		box_xf(size, Transform3D(basis, pos), key, true)
		if inner_key != "":
			var n := -basis.x      # interior side (walls are traced with the outside on basis.x)
			box_xf(Vector3(0.012, size.y, size.z), Transform3D(basis, pos + n * (thick * 0.5 + 0.006)), inner_key, false)


func window_band(a: Vector2, b: Vector2, sill: float, head: float, mullion: float, frame_key: String, glass_key: String, depth: float = 0.08, base_y: float = 0.0, transoms: Array = []) -> void:
	## Glazing between a and b (frames + glass). Pair with a wall opening of the same size.
	var dir := b - a
	var length := dir.length()
	if length < 0.01:
		return
	dir /= length
	var basis := Basis(Vector3.UP, atan2(dir.x, dir.y))
	var h := head - sill
	var mid := a + dir * (length * 0.5)
	box_xf(Vector3(0.012, h, length), Transform3D(basis, Vector3(mid.x, base_y + sill + h * 0.5, mid.y)), glass_key, true)
	var n := int(ceil(length / maxf(mullion, 0.3)))
	for i in n + 1:
		var p := a + dir * (length * i / float(n))
		box_xf(Vector3(depth, h, 0.06), Transform3D(basis, Vector3(p.x, base_y + sill + h * 0.5, p.y)), frame_key, false)
	for y in [sill, head] + transoms:
		box_xf(Vector3(depth, 0.07, length), Transform3D(basis, Vector3(mid.x, base_y + float(y), mid.y)), frame_key, false)


func portal_shed(c: Vector3, w: float, d: float, eave: float, pitch_deg: float, bay: float, opts: Dictionary) -> Dictionary:
	## Steel portal-frame shed centred at c (floor level), long axis along Z, front gable at +Z.
	## opts: wall, wall_lower, lower_h, liner, roof, frame, rooflights (every n-th roof bay), front/back/left/right openings,
	##       gutter, fascia, skip_front(bool) for an attached office.
	var hw := w * 0.5
	var hd := d * 0.5
	var rise := hw * tan(deg_to_rad(pitch_deg))
	var t := 0.18
	var wall_key: String = opts.get("wall", "clad_goosewing")
	var lower_key: String = opts.get("wall_lower", "")
	var lower_h: float = opts.get("lower_h", 0.0)
	var liner: String = opts.get("liner", "")
	var frame: String = opts.get("frame", "steel_blue")
	var roof: String = opts.get("roof", "roof_sheet")
	# --- walls (lower plinth + upper cladding)
	var sides := {
		"front": [Vector2(c.x + hw, c.z + hd), Vector2(c.x - hw, c.z + hd)],
		"back": [Vector2(c.x - hw, c.z - hd), Vector2(c.x + hw, c.z - hd)],
		"left": [Vector2(c.x - hw, c.z + hd), Vector2(c.x - hw, c.z - hd)],
		"right": [Vector2(c.x + hw, c.z - hd), Vector2(c.x + hw, c.z + hd)],
	}
	for s in sides:
		if opts.get("skip_" + s, false):
			continue
		var ab: Array = sides[s]
		var ops: Array = opts.get(s, [])
		if lower_key != "" and lower_h > 0.0:
			var low_ops: Array = []
			var up_ops: Array = []
			for o in ops:
				var sill := float(o.get("sill", 0.0))
				if sill < lower_h:
					low_ops.append({"at": o.at, "w": o.w, "h": minf(float(o.h), lower_h - sill), "sill": sill})
				var top := sill + float(o.h)
				if top > lower_h:
					up_ops.append({"at": o.at, "w": o.w, "h": top - maxf(sill, lower_h), "sill": maxf(sill - lower_h, 0.0)})
			wall(ab[0], ab[1], lower_h, t, lower_key, low_ops, c.y, liner)
			wall(ab[0], ab[1], eave - lower_h, t, wall_key, up_ops, c.y + lower_h, liner)
		else:
			wall(ab[0], ab[1], eave, t, wall_key, ops, c.y, liner)
	# Gable triangles (front/back)
	for z in ([c.z + hd] if not opts.get("skip_front", false) else []) + [c.z - hd]:
		prism(Vector3(w, rise, t), Vector3(c.x, c.y + eave + rise * 0.5, z), wall_key)
		if liner != "":
			prism(Vector3(w - 0.1, rise - 0.05, 0.02), Vector3(c.x, c.y + eave + rise * 0.5, z - signf(z - c.z) * 0.11), liner)
	# --- roof (two slopes) with roof lights
	var slope_len := hw / cos(deg_to_rad(pitch_deg)) + 0.35
	var n_bays := maxi(int(round(d / bay)), 1)
	var bay_len := d / n_bays
	var rl_every: int = opts.get("rooflights", 3)
	for side in [-1, 1]:
		var ang: float = -side * pitch_deg
		var mid := Vector3(c.x + side * hw * 0.5, c.y + eave + rise * 0.5 + 0.08, c.z)
		for i in n_bays:
			var zc := c.z - hd + bay_len * (i + 0.5)
			var key := roof
			if rl_every > 0 and i % rl_every == 1:
				# translucent strip in the middle of the slope
				box(Vector3(slope_len * 0.35, 0.03, bay_len - 0.05), Vector3(mid.x, mid.y, zc), "rooflight", Vector3(0, 0, ang), false)
				var part := slope_len * 0.325
				for k in [-1, 1]:
					var off: Vector3 = Vector3(cos(deg_to_rad(ang)), sin(deg_to_rad(ang)), 0) * (k * (slope_len * 0.175 + part * 0.5))
					box(Vector3(part, 0.08, bay_len), Vector3(mid.x, mid.y, zc) + off, key, Vector3(0, 0, ang), false)
			else:
				box(Vector3(slope_len, 0.08, bay_len + 0.01), Vector3(mid.x, mid.y, zc), key, Vector3(0, 0, ang), false)
		# gutters
		box(Vector3(0.25, 0.18, d + 0.4), Vector3(c.x + side * (hw + 0.2), c.y + eave + 0.02, c.z), opts.get("gutter", "alu_dark"), Vector3.ZERO, false)
	box(Vector3(0.4, 0.12, d + 0.2), Vector3(c.x, c.y + eave + rise + 0.1, c.z), opts.get("gutter", "alu_dark"), Vector3.ZERO, false)
	# --- frame: columns, rafters, haunches, purlins, eaves beams
	var col_depth := clampf(w / 60.0, 0.3, 0.7)
	for i in n_bays + 1:
		var z := c.z - hd + bay_len * i
		z = clampf(z, c.z - hd + 0.35, c.z + hd - 0.35)
		for side in [-1, 1]:
			var x: float = c.x + side * (hw - 0.35)
			i_beam(Vector3(x, c.y, z), Vector3(x, c.y + eave, z), col_depth, col_depth * 0.6, frame)
			i_beam(Vector3(x, c.y + eave - 0.2, z), Vector3(c.x, c.y + eave + rise - 0.2, z), col_depth * 0.85, col_depth * 0.5, frame)
		# internal columns for very wide spans
		var spans: int = opts.get("spans", 1)
		for k in range(1, spans):
			var xi := c.x - hw + w * k / float(spans)
			var top := c.y + eave + rise * (1.0 - absf(xi - c.x) / hw) - 0.3
			i_beam(Vector3(xi, c.y, z), Vector3(xi, top, z), col_depth * 0.8, col_depth * 0.5, frame)
			_collide(Vector3(0.4, top - c.y, 0.4), Transform3D(Basis.IDENTITY, Vector3(xi, c.y + (top - c.y) * 0.5, z)))
	var n_purlins := maxi(int(slope_len / 1.6), 2)
	for side in [-1, 1]:
		for k in n_purlins:
			var f := (k + 0.5) / n_purlins
			var x: float = c.x + side * hw * f
			var y := c.y + eave + rise * (1.0 - f) - 0.12
			beam(Vector3(x, y, c.z - hd), Vector3(x, y, c.z + hd), Vector2(0.06, 0.18), "galv")
		beam(Vector3(c.x + side * (hw - 0.35), c.y + eave - 0.2, c.z - hd), Vector3(c.x + side * (hw - 0.35), c.y + eave - 0.2, c.z + hd), Vector2(0.15, 0.25), frame)
	return {"rise": rise, "bays": n_bays, "bay_len": bay_len}


func flat_box_building(c: Vector3, size: Vector3, wall_key: String, roof_key: String = "roof_felt", parapet: float = 0.6, openings: Dictionary = {}, liner: String = "") -> void:
	## Rectangular flat-roofed block (offices, gatehouses). openings keyed by side like portal_shed.
	var hw := size.x * 0.5
	var hd := size.z * 0.5
	var sides := {
		"front": [Vector2(c.x + hw, c.z + hd), Vector2(c.x - hw, c.z + hd)],
		"back": [Vector2(c.x - hw, c.z - hd), Vector2(c.x + hw, c.z - hd)],
		"left": [Vector2(c.x - hw, c.z + hd), Vector2(c.x - hw, c.z - hd)],
		"right": [Vector2(c.x + hw, c.z - hd), Vector2(c.x + hw, c.z + hd)],
	}
	for s in sides:
		var ab: Array = sides[s]
		wall(ab[0], ab[1], size.y + parapet, 0.25, wall_key, openings.get(s, []), c.y, liner)
	box(Vector3(size.x - 0.1, 0.25, size.z - 0.1), Vector3(c.x, c.y + size.y, c.z), roof_key)
	box(Vector3(size.x + 0.1, 0.08, size.z + 0.1), Vector3(c.x, c.y + size.y + parapet + 0.04, c.z), "alu_dark", Vector3.ZERO, false)


# ================================================================= site furniture

func road_x(z: float, x0: float, x1: float, width: float = 7.3, pave: float = 2.0, verge: float = 1.5, dashes: bool = true) -> void:
	var len_x := x1 - x0
	var cx := (x0 + x1) * 0.5
	box(Vector3(len_x, 0.1, width), Vector3(cx, -0.1, z), "tarmac", Vector3.ZERO, false)
	for s in [-1, 1]:
		var kz: float = z + s * (width * 0.5 + 0.075)
		box(Vector3(len_x, 0.16, 0.15), Vector3(cx, -0.03, kz), "kerb", Vector3.ZERO, false)
		box(Vector3(len_x, 0.12, verge), Vector3(cx, -0.06, kz + s * (0.075 + verge * 0.5)), "grass", Vector3.ZERO, false)
		box(Vector3(len_x, 0.12, pave), Vector3(cx, -0.04, kz + s * (0.075 + verge + pave * 0.5)), "paving_light", Vector3.ZERO, false)
	if dashes:
		var x := x0 + 2.0
		while x < x1 - 2.0:
			box(Vector3(3.0, 0.01, 0.1), Vector3(x + 1.5, -0.044, z), "line_white", Vector3.ZERO, false)
			x += 9.0
	# Double yellows on the near side kerb — very British.
	for off in [0.25, 0.4]:
		box(Vector3(len_x, 0.01, 0.07), Vector3(cx, -0.044, z - width * 0.5 + off), "line_yellow", Vector3.ZERO, false)


func parking_bays(origin: Vector3, count: int, along: Vector3, bay_w: float = 2.5, bay_l: float = 5.0, line_key: String = "line_white", special: Dictionary = {}) -> void:
	## Bays side by side along `along` (unit vector); bays extend toward the +perpendicular side.
	var perp := Vector3(along.z, 0, -along.x)
	var yaw := rad_to_deg(atan2(along.x, along.z))
	for i in count + 1:
		var p := origin + along * (bay_w * i) + perp * (bay_l * 0.5)
		box(Vector3(0.1, 0.01, bay_l), p + Vector3(0, 0.006, 0), line_key, Vector3(0, yaw + 90, 0), false)
	box(Vector3(0.1, 0.01, bay_w * count), origin + along * (bay_w * count * 0.5) + Vector3(0, 0.006, 0), line_key, Vector3(0, yaw, 0), false)
	for i in special:
		var p2 := origin + along * (bay_w * (int(i) + 0.5)) + perp * (bay_l * 0.5)
		match special[i]:
			"disabled":
				box(Vector3(bay_w - 0.2, 0.012, bay_l - 0.2), p2 + Vector3(0, 0.004, 0), "line_blue", Vector3(0, yaw + 90, 0), false)
			"ev":
				box(Vector3(bay_w - 0.2, 0.012, bay_l - 0.2), p2 + Vector3(0, 0.004, 0), "line_green", Vector3(0, yaw + 90, 0), false)
				var pc := origin + along * (bay_w * (int(i) + 0.5)) + perp * (bay_l + 0.4)
				box(Vector3(0.4, 1.5, 0.3), pc + Vector3(0, 0.75, 0), "paint_white", Vector3(0, yaw, 0))
				box(Vector3(0.3, 0.12, 0.02), pc + Vector3(0, 1.3, 0) - perp * 0.16, "emit_green", Vector3(0, yaw, 0), false)


func tree(pos: Vector3, h: float = 6.0, kind: int = -1) -> void:
	if kind < 0:
		kind = rng.randi() % 3
	var trunk_h := h * 0.42
	cyl(0.08 + h * 0.018, trunk_h, pos + Vector3(0, trunk_h * 0.5, 0), "bark", Vector3.ZERO, 7, true, 0.7)
	var leaf: String = ["foliage", "foliage_dark", "foliage_light"][kind]
	if kind == 1:
		# conifer
		for i in 4:
			var r := h * (0.32 - i * 0.065)
			cyl(r, h * 0.28, pos + Vector3(0, trunk_h * 0.6 + i * h * 0.16 + h * 0.14, 0), leaf, Vector3.ZERO, 9, false, 0.25)
	else:
		var r := h * 0.28
		for i in 5:
			var off := Vector3(rng.randf_range(-0.6, 0.6), rng.randf_range(-0.3, 0.5), rng.randf_range(-0.6, 0.6)) * r
			sphere(r * rng.randf_range(0.7, 1.0), pos + Vector3(0, trunk_h + r * 0.8, 0) + off, leaf, Vector3(1, 0.85, 1), 9)


func shrub(pos: Vector3, r: float = 0.6) -> void:
	sphere(r, pos + Vector3(0, r * 0.6, 0), ["hedge", "foliage", "foliage_dark"][rng.randi() % 3], Vector3(1, 0.75, 1), 8)


func car(pos: Vector3, yaw: float, paint: String = "") -> void:
	if paint == "":
		paint = ["car_red", "car_blue", "car_silver", "car_black", "car_white", "car_grey", "car_silver", "car_white"][rng.randi() % 8]
	var b := Basis(Vector3.UP, deg_to_rad(yaw))
	var xf := func(lp: Vector3) -> Vector3: return pos + b * lp
	box_xf(Vector3(1.78, 0.62, 4.3), Transform3D(b, xf.call(Vector3(0, 0.62, 0))), paint, true)
	box_xf(Vector3(1.6, 0.55, 2.2), Transform3D(b, xf.call(Vector3(0, 1.18, -0.25))), paint, false)
	box_xf(Vector3(1.62, 0.42, 2.0), Transform3D(b, xf.call(Vector3(0, 1.2, -0.25))), "glass_dark", false)
	for sx in [-0.8, 0.8]:
		for sz in [-1.35, 1.3]:
			cyl(0.33, 0.24, xf.call(Vector3(sx, 0.33, sz)), "tyre", Vector3(0, yaw, 90), 12)
	for sx in [-0.6, 0.6]:
		box_xf(Vector3(0.35, 0.12, 0.05), Transform3D(b, xf.call(Vector3(sx, 0.75, 2.16))), "alu", false)
		box_xf(Vector3(0.35, 0.1, 0.05), Transform3D(b, xf.call(Vector3(sx, 0.82, -2.16))), "line_red", false)


func lamp_column(pos: Vector3, yaw: float, h: float = 8.0) -> void:
	var b := Basis(Vector3.UP, deg_to_rad(yaw))
	cyl(0.08, h, pos + Vector3(0, h * 0.5, 0), "galv", Vector3.ZERO, 8, true, 0.6)
	box_xf(Vector3(0.08, 0.08, 1.2), Transform3D(b, pos + Vector3(0, h, 0) + b * Vector3(0, 0, 0.55)), "galv", false)
	box_xf(Vector3(0.35, 0.08, 0.6), Transform3D(b, pos + Vector3(0, h - 0.05, 0) + b * Vector3(0, 0, 1.15)), "alu_dark", false)


func palisade(a: Vector2, b: Vector2, h: float = 2.4, key: String = "steel_green", y: float = 0.0) -> void:
	var dir := b - a
	var length := dir.length()
	if length < 0.1:
		return
	dir /= length
	var basis := Basis(Vector3.UP, atan2(dir.x, dir.y))
	var n_posts := int(length / 2.75) + 1
	for i in n_posts + 1:
		var p := a + dir * minf(i * 2.75, length)
		box(Vector3(0.1, h + 0.1, 0.1), Vector3(p.x, y + (h + 0.1) * 0.5, p.y), key, Vector3.ZERO, false)
	for ry in [0.4, h - 0.35]:
		var m := a + dir * (length * 0.5)
		box_xf(Vector3(0.05, 0.05, length), Transform3D(basis, Vector3(m.x, y + ry, m.y)), key, false)
	var n_pales := int(length / 0.17)
	for i in n_pales:
		var p := a + dir * (0.085 + i * 0.17)
		box_xf(Vector3(0.07, h, 0.012), Transform3D(basis, Vector3(p.x, y + h * 0.5, p.y)), key, false)
		box_xf(Vector3(0.07, 0.12, 0.012), Transform3D(basis * Basis(Vector3.FORWARD, 0.0), Vector3(p.x, y + h + 0.05, p.y)), key, false)
	var mid := a + dir * (length * 0.5)
	_collide(Vector3(0.2, h, length), Transform3D(basis, Vector3(mid.x, y + h * 0.5, mid.y)))


func chainlink(a: Vector2, b: Vector2, h: float = 2.0, y: float = 0.0) -> void:
	var dir := b - a
	var length := dir.length()
	if length < 0.1:
		return
	dir /= length
	var basis := Basis(Vector3.UP, atan2(dir.x, dir.y))
	var n := int(length / 3.0) + 1
	for i in n + 1:
		var p := a + dir * minf(i * 3.0, length)
		cyl(0.04, h + 0.2, Vector3(p.x, y + (h + 0.2) * 0.5, p.y), "galv", Vector3.ZERO, 6)
	var m := a + dir * (length * 0.5)
	box_xf(Vector3(0.015, h, length), Transform3D(basis, Vector3(m.x, y + h * 0.5, m.y)), "chainlink", true)
	beam(Vector3(a.x, y + h, a.y), Vector3(b.x, y + h, b.y), Vector2(0.04, 0.04), "galv")


func bollard(pos: Vector3, key: String = "steel_yellow") -> void:
	cyl(0.1, 1.0, pos + Vector3(0, 0.5, 0), key, Vector3.ZERO, 10, true)
	cyl(0.105, 0.08, pos + Vector3(0, 0.85, 0), "paint_dark", Vector3.ZERO, 10)


func flagpole(pos: Vector3, flag_key: String, h: float = 8.0, rot_y: float = 0.0) -> void:
	cyl(0.05, h, pos + Vector3(0, h * 0.5, 0), "paint_white", Vector3.ZERO, 8, true, 0.6)
	box(Vector3(1.6, 1.0, 0.02), pos + Vector3(0.82, h - 0.6, 0), flag_key, Vector3(0, rot_y, 0), false)


func wheelie_bin(pos: Vector3, key: String, yaw: float = 0.0) -> void:
	box(Vector3(0.58, 1.0, 0.72), pos + Vector3(0, 0.5, 0), key, Vector3(0, yaw, 0))
	box(Vector3(0.6, 0.05, 0.76), pos + Vector3(0, 1.02, 0), key, Vector3(0, yaw, 0), false)


func euro_bin(pos: Vector3, yaw: float = 0.0) -> void:
	box(Vector3(1.3, 1.1, 1.0), pos + Vector3(0, 0.6, 0), "galv", Vector3(0, yaw, 0))
	box(Vector3(1.32, 0.12, 1.02), pos + Vector3(0, 1.2, 0), "bin_green", Vector3(0, yaw, 0), false)


func pallet_stack(pos: Vector3, n: int, yaw: float = 0.0) -> void:
	for i in n:
		box(Vector3(1.2, 0.144, 0.8), pos + Vector3(0, 0.072 + i * 0.146, 0), "pallet_wood", Vector3(0, yaw + rng.randf_range(-4, 4), 0), i == 0)
	_collide(Vector3(1.2, n * 0.146, 0.8), Transform3D(Basis(Vector3.UP, deg_to_rad(yaw)), pos + Vector3(0, n * 0.073, 0)))


func bench_seat(pos: Vector3, yaw: float) -> void:
	box(Vector3(1.8, 0.06, 0.45), pos + Vector3(0, 0.45, 0), "timber", Vector3(0, yaw, 0))
	for s in [-0.75, 0.75]:
		box(Vector3(0.06, 0.45, 0.4), pos + Basis(Vector3.UP, deg_to_rad(yaw)) * Vector3(s, 0.22, 0), "alu_dark", Vector3(0, yaw, 0), false)


func planter(pos: Vector3, size: Vector3, with_tree: bool = false) -> void:
	box(size, pos + Vector3(0, size.y * 0.5, 0), "panel_anthracite")
	box(Vector3(size.x - 0.12, 0.05, size.z - 0.12), pos + Vector3(0, size.y - 0.02, 0), "soil", Vector3.ZERO, false)
	if with_tree:
		tree(pos + Vector3(0, size.y, 0), 4.5, 0)
	else:
		for i in int(size.x * size.z * 2):
			shrub(pos + Vector3(rng.randf_range(-size.x, size.x) * 0.35, size.y, rng.randf_range(-size.z, size.z) * 0.35), 0.35)


func ground_with_holes(bounds: Rect2, holes: Array, key: String = "grass", y: float = -0.17, thick: float = 0.1) -> void:
	## Ground plane made of rectangles that avoid `holes` (e.g. sunken dock aprons).
	var xs: Array = [bounds.position.x, bounds.end.x]
	for h: Rect2 in holes:
		xs.append(clampf(h.position.x, bounds.position.x, bounds.end.x))
		xs.append(clampf(h.end.x, bounds.position.x, bounds.end.x))
	xs.sort()
	for i in xs.size() - 1:
		var x0: float = xs[i]
		var x1: float = xs[i + 1]
		if x1 - x0 < 0.01:
			continue
		var zs: Array = [[bounds.position.y, bounds.end.y]]
		for h: Rect2 in holes:
			if h.position.x <= x0 + 0.001 and h.end.x >= x1 - 0.001:
				var nz: Array = []
				for seg in zs:
					if h.end.y <= seg[0] or h.position.y >= seg[1]:
						nz.append(seg)
						continue
					if h.position.y > seg[0]:
						nz.append([seg[0], h.position.y])
					if h.end.y < seg[1]:
						nz.append([h.end.y, seg[1]])
				zs = nz
		for seg in zs:
			box(Vector3(x1 - x0, thick, seg[1] - seg[0]), Vector3((x0 + x1) * 0.5, y - thick * 0.5, (seg[0] + seg[1]) * 0.5), key, Vector3.ZERO, false)


func hills(radius: float, count: int, y: float = -1.0) -> void:
	## Low rolling hills ringing the site so the horizon isn't a flat line.
	for i in count:
		var a := TAU * i / count + rng.randf_range(-0.1, 0.1)
		var r := radius * rng.randf_range(0.9, 1.25)
		var p := Vector3(cos(a) * r, y, sin(a) * r)
		sphere(rng.randf_range(radius * 0.18, radius * 0.32), p, "hills", Vector3(1.0, rng.randf_range(0.18, 0.32), 1.0), 14)


func treeline(a: Vector3, b: Vector3, spacing: float, h_min: float = 7.0, h_max: float = 13.0) -> void:
	var d := b - a
	var n := int(d.length() / spacing)
	for i in n:
		var p := a + d * (i / float(maxi(n - 1, 1))) + Vector3(rng.randf_range(-2, 2), 0, rng.randf_range(-2, 2))
		tree(p, rng.randf_range(h_min, h_max))


func hgv_trailer(pos: Vector3, yaw: float, livery: String = "paint_white", brand: String = "") -> void:
	## 13.6 m curtain-side trailer parked on its landing legs. Local +Z = front (kingpin end).
	var b := Basis(Vector3.UP, deg_to_rad(yaw))
	var at := func(lp: Vector3) -> Vector3: return pos + b * lp
	box_xf(Vector3(2.55, 2.75, 13.6), Transform3D(b, at.call(Vector3(0, 2.6, 0))), livery, true)
	box_xf(Vector3(2.4, 0.3, 13.4), Transform3D(b, at.call(Vector3(0, 1.1, 0))), "steel_black", false)
	for z in [-5.2, -4.2, -3.2]:
		for sx in [-0.95, 0.95]:
			cyl(0.5, 0.3, at.call(Vector3(sx, 0.5, z)), "tyre", Vector3(0, yaw, 90), 14)
	for sx in [-0.9, 0.9]:
		box_xf(Vector3(0.12, 1.0, 0.12), Transform3D(b, at.call(Vector3(sx, 0.5, 4.8))), "steel_grey", false)
	box_xf(Vector3(2.0, 0.12, 0.05), Transform3D(b, at.call(Vector3(0, 1.0, -6.82))), "emit_red", false)
	if brand != "":
		for s in [-1, 1]:
			label(brand, at.call(Vector3(s * 1.29, 2.7, 0)), Vector3(0, yaw + s * 90, 0), 240, Color(0.1, 0.12, 0.2), 0.006)


func tractor_unit(pos: Vector3, yaw: float, cab_key: String = "car_blue") -> void:
	var b := Basis(Vector3.UP, deg_to_rad(yaw))
	var at := func(lp: Vector3) -> Vector3: return pos + b * lp
	box_xf(Vector3(2.5, 2.4, 2.3), Transform3D(b, at.call(Vector3(0, 2.25, 1.6))), cab_key, true)
	box_xf(Vector3(2.3, 0.9, 0.05), Transform3D(b, at.call(Vector3(0, 2.7, 2.77))), "glass_dark", false)
	box_xf(Vector3(2.2, 0.4, 5.6), Transform3D(b, at.call(Vector3(0, 0.95, 0.2))), "steel_black", false)
	for z in [1.7, -1.3, -2.4]:
		for sx in [-0.95, 0.95]:
			cyl(0.5, 0.3, at.call(Vector3(sx, 0.5, z)), "tyre", Vector3(0, yaw, 90), 14)


func gatehouse(pos: Vector3, yaw: float, barrier_len: float = 6.0) -> void:
	var b := Basis(Vector3.UP, deg_to_rad(yaw))
	var at := func(lp: Vector3) -> Vector3: return pos + b * lp
	box_xf(Vector3(3.0, 2.8, 3.0), Transform3D(b, at.call(Vector3(0, 1.4, 0))), "panel_white", true)
	box_xf(Vector3(3.6, 0.25, 3.6), Transform3D(b, at.call(Vector3(0, 2.9, 0))), "panel_anthracite", false)
	for s in [-1, 1]:
		box_xf(Vector3(2.4, 1.2, 0.05), Transform3D(b, at.call(Vector3(0, 1.7, s * 1.52))), "glass_dark", false)
		box_xf(Vector3(0.05, 1.2, 2.4), Transform3D(b, at.call(Vector3(s * 1.52, 1.7, 0))), "glass_dark", false)
	# Barrier post and raised arm
	box_xf(Vector3(0.4, 1.1, 0.4), Transform3D(b, at.call(Vector3(2.2, 0.55, 0))), "steel_yellow", true)
	var arm_b := b * Basis(Vector3.FORWARD, deg_to_rad(-70))
	box_xf(Vector3(barrier_len, 0.1, 0.1), Transform3D(arm_b, at.call(Vector3(2.2, 1.0, 0)) + b * (Basis(Vector3.FORWARD, deg_to_rad(-70)) * Vector3(barrier_len * 0.5, 0, 0))), "paint_white", false)


func sprinkler_tank(pos: Vector3, r: float = 6.0, h: float = 11.0) -> void:
	cyl(r, h, pos + Vector3(0, h * 0.5, 0), "steel_green", Vector3.ZERO, 32, true)
	cyl(r + 0.1, 0.4, pos + Vector3(0, h + 0.2, 0), "steel_green", Vector3.ZERO, 32, false, 0.2)
	for i in 6:
		cyl(r + 0.03, 0.06, pos + Vector3(0, 1.5 + i * 1.6, 0), "galv", Vector3.ZERO, 32)
	box(Vector3(5.0, 3.2, 4.0), pos + Vector3(r + 3.2, 1.6, 0), "clad_goosewing")
	box(Vector3(5.2, 0.2, 4.2), pos + Vector3(r + 3.2, 3.3, 0), "roof_felt", Vector3.ZERO, false)
	beam(pos + Vector3(r, 0.8, 0), pos + Vector3(r + 0.7, 0.8, 0), Vector2(0.3, 0.3), "car_red")


func solar_array(c: Vector3, size: Vector2, tilt: float = 12.0, yaw: float = 0.0, row_gap: float = 0.6) -> void:
	## Rows of tilted PV panels (roof or ground). size = area covered (x across, y along rows).
	var b := Basis(Vector3.UP, deg_to_rad(yaw))
	var rows := int(size.y / (1.7 + row_gap))
	for r in rows:
		var lz := -size.y * 0.5 + 0.85 + r * (1.7 + row_gap)
		box_xf(Vector3(size.x, 0.04, 1.7), Transform3D(b * Basis(Vector3.RIGHT, deg_to_rad(-tilt)), c + b * Vector3(0, 0.45, lz)), "solar", false)
		box_xf(Vector3(size.x, 0.05, 0.05), Transform3D(b, c + b * Vector3(0, 0.25, lz + 0.7)), "galv", false)


func solar_carport(origin: Vector3, length: float, yaw: float = 0.0) -> void:
	## Steel carport canopy over a row of bays with PV on top. origin = start of the row centre line.
	var b := Basis(Vector3.UP, deg_to_rad(yaw))
	var n := int(length / 7.5) + 1
	for i in n:
		var p := origin + b * Vector3(minf(i * 7.5, length), 0, 0)
		box_xf(Vector3(0.25, 3.2, 0.25), Transform3D(b, p + Vector3(0, 1.6, 0)), "galv", true)
		box_xf(Vector3(0.2, 0.3, 5.6), Transform3D(b * Basis(Vector3.RIGHT, deg_to_rad(-6)), p + Vector3(0, 3.2, 0)), "galv", false)
	var mid := origin + b * Vector3(length * 0.5, 0, 0)
	box_xf(Vector3(length + 1.0, 0.08, 6.0), Transform3D(b * Basis(Vector3.RIGHT, deg_to_rad(-6)), mid + Vector3(0, 3.42, 0)), "solar", false)
	box_xf(Vector3(length + 1.0, 0.06, 6.0), Transform3D(b * Basis(Vector3.RIGHT, deg_to_rad(-6)), mid + Vector3(0, 3.36, 0)), "galv", false)


# ================================================================= loading docks

func dock(dock_parent: Node3D, name: String, x: float, wall_z: float, door_parent_name: String, apron_y: float = -1.2, number: int = 1) -> Marker3D:
	## A dock door at x on a wall facing +Z at wall_z: shelter, bumpers, leveller, dock light, traffic light, bay number.
	## Returns the lorry park marker (rear at the dock).
	var dw := 3.0
	var dh := 3.2
	var door := roller_door(door_parent_name, Vector3(x, 0, wall_z + 0.12), 0, dw, dh, false)
	# Dock shelter: black rubber curtains framing the opening outside
	box(Vector3(dw + 1.0, 0.35, 0.9), Vector3(x, dh + 0.25, wall_z + 0.5), "rubber", Vector3.ZERO, false)
	for s in [-1, 1]:
		box(Vector3(0.45, dh + 0.4, 0.9), Vector3(x + s * (dw * 0.5 + 0.25), dh * 0.5 + 0.1, wall_z + 0.5), "rubber", Vector3.ZERO, false)
	# Bumpers at dock face (outside, below floor level)
	for s in [-1, 1]:
		box(Vector3(0.25, 0.45, 0.15), Vector3(x + s * 1.0, -0.3, wall_z + 0.18), "rubber")
	# Leveller lip inside + hazard edging
	box(Vector3(2.2, 0.04, 2.6), Vector3(x, 0.02, wall_z - 1.3), "steel_grey", Vector3.ZERO, false)
	decal("hazard_stripes", Vector3(x, 0.05, wall_z - 0.12), Vector3(2.4, 0.3, 0.25))
	# Dock face concrete below floor (outside)
	box(Vector3(dw + 1.2, -apron_y, 0.3), Vector3(x, apron_y * 0.5, wall_z + 0.15), "concrete_yard")
	# Dock light on an arm and traffic light beside the door
	box(Vector3(0.06, 0.06, 0.9), Vector3(x + dw * 0.5 + 0.7, dh + 0.3, wall_z + 0.45), "steel_black", Vector3.ZERO, false)
	box(Vector3(0.22, 0.16, 0.22), Vector3(x + dw * 0.5 + 0.7, dh + 0.18, wall_z + 0.9), "alu_dark", Vector3.ZERO, false)
	box(Vector3(0.22, 0.5, 0.12), Vector3(x - dw * 0.5 - 0.8, 2.0, wall_z + 0.08), "steel_black", Vector3.ZERO, false)
	box(Vector3(0.12, 0.12, 0.02), Vector3(x - dw * 0.5 - 0.8, 2.12, wall_z + 0.15), "emit_red", Vector3.ZERO, false)
	box(Vector3(0.12, 0.12, 0.02), Vector3(x - dw * 0.5 - 0.8, 1.88, wall_z + 0.15), "line_green", Vector3.ZERO, false)
	# Bay number
	box(Vector3(0.8, 0.8, 0.03), Vector3(x, dh + 0.85, wall_z + 0.95), "paint_white", Vector3.ZERO, false)
	label(str(number), Vector3(x, dh + 0.85, wall_z + 0.97), Vector3.ZERO, 260, Color(0.08, 0.08, 0.1), 0.002)
	# Lorry park marker: rear at dock face, cab toward +Z
	var m := marker(dock_parent, name, Vector3(x, apron_y, wall_z + 0.35 + 3.55), 0)
	m.set_meta("dock", true)
	marker(m, "Drop", Vector3(0, -apron_y + 0.02, -6.0), 0)
	m.set_meta("door", NodePath("../../Nodes/" + door_parent_name))
	return m


func dock_apron(x0: float, x1: float, wall_z: float, depth: float, ramp: float, apron_y: float = -1.2, key: String = "concrete_yard") -> void:
	## Sunken apron in front of a dock run, with retaining walls and a ramp back up to yard level (y = 0).
	var w := x1 - x0
	var cx := (x0 + x1) * 0.5
	box(Vector3(w, 0.3, depth), Vector3(cx, apron_y - 0.15, wall_z + depth * 0.5), key)
	var ang := rad_to_deg(atan2(-apron_y, ramp))
	var rl := sqrt(ramp * ramp + apron_y * apron_y)
	box(Vector3(w, 0.3, rl), Vector3(cx, apron_y * 0.5 - 0.15, wall_z + depth + ramp * 0.5), key, Vector3(ang, 0, 0))
	for s in [x0 - 0.15, x1 + 0.15]:
		box(Vector3(0.3, -apron_y + 0.9, depth + ramp), Vector3(s, (apron_y + 0.9) * 0.5, wall_z + (depth + ramp) * 0.5), "concrete_yard")
		box(Vector3(0.32, 0.9, depth + ramp), Vector3(s, 0.45 + 0.0, wall_z + (depth + ramp) * 0.5), "steel_yellow", Vector3.ZERO, false)


func blocker(size: Vector3, pos: Vector3, rot_deg: Vector3 = Vector3.ZERO) -> void:
	## Invisible collision only (keeps players on site).
	_collide(size, Transform3D(_rot(rot_deg), pos))


func node_box(parent: Node3D, size: Vector3, pos: Vector3, key: String, rot_deg: Vector3 = Vector3.ZERO) -> MeshInstance3D:
	## Unbatched box (for animated parts like turbine blades or barrier arms).
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	mi.mesh = bm
	mi.material_override = MatLib.mat(key)
	mi.position = pos
	mi.rotation_degrees = rot_deg
	parent.add_child(mi)
	return mi


# ================================================================= finish

func finish() -> void:
	## Commit batched meshes (saved to out_dir/*.res) and colliders.
	var geo := Node3D.new()
	geo.name = "Geometry"
	root.add_child(geo)
	root.move_child(geo, 0)
	var keys := _st.keys()
	keys.sort()
	for key in keys:
		var st: SurfaceTool = _st[key]
		var mesh := st.commit()
		if mesh.get_surface_count() == 0:
			continue
		mesh.surface_set_material(0, MatLib.mat(key))
		var path := out_dir.path_join("geo_%s.res" % key)
		ResourceSaver.save(mesh, path, ResourceSaver.FLAG_COMPRESS)
		var mi := MeshInstance3D.new()
		mi.name = "Geo_" + key
		mi.mesh = load(path)
		if _no_shadow.has(key):
			mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		geo.add_child(mi)
	var body := StaticBody3D.new()
	body.name = "Collision"
	body.collision_layer = 1
	geo.add_child(body)
	for sh in _shapes:
		var size: Vector3 = sh[1]
		var k := "%.2f|%.2f|%.2f" % [size.x, size.y, size.z]
		var bs: BoxShape3D = _shape_cache.get(k)
		if bs == null:
			bs = BoxShape3D.new()
			bs.size = size
			_shape_cache[k] = bs
		var cs := CollisionShape3D.new()
		cs.shape = bs
		cs.transform = sh[0]
		body.add_child(cs)
	_st.clear()
	_shapes.clear()
