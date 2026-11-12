class_name PremisesStyle
extends Node3D
## Makes whichever premises you're in look like YOUR business (choices: GameState.premises, data/premises.json,
## Fit-out app). Added to every level by LevelRoot, so the look follows the company when it moves:
##   • wall paint  — the building's inside wall materials (buildings.json paint_mats → Geometry/Geo_<mat>) get a
##                   painted copy of their own material (same texture, new colour);
##   • floor lines — walkway lines 0.9 m in from the walls and/or painted boxes round every piece of equipment,
##                   plus a hatched pallet zone at goods-in;
##   • door signs  — a fascia sign above each roller door (logo + company name, accent colour) and the logo on
##                   the shutter itself.
## Everything is rebuilt from replicated state, so every player in a co-op session sees the same premises.

const LINE_W := 0.08
const LINE_Y := 0.0035
const WALKWAY_INSET := 0.9

var level: LevelRoot
var _paint_sig := ""
var _lines_sig := ""
var _doors_sig := ""
var _orig := {}                 # MeshInstance3D -> original material
var _lines: Node3D
var _signs: Node3D
var _rebuild_t := -1.0


func setup(lr: LevelRoot) -> void:
	level = lr
	name = "PremisesStyle"
	_lines = Node3D.new()
	_lines.name = "FloorLines"
	add_child(_lines)
	_signs = Node3D.new()
	_signs.name = "DoorSigns"
	add_child(_signs)


func _ready() -> void:
	Game.state_reset.connect(_reconnect)
	_reconnect()


func _reconnect() -> void:
	if not Game.state.changed.is_connected(_on_changed):
		Game.state.changed.connect(_on_changed)
	_paint_sig = ""
	_lines_sig = ""
	_doors_sig = ""
	refresh()


func _on_changed(kind: String, _id: Variant) -> void:
	if kind in ["premises", "company", "fixture", "building"]:
		_rebuild_t = 0.25          # batch bursts (auto-layout places many fixtures at once)
		set_process(true)


func _process(delta: float) -> void:
	if _rebuild_t < 0.0:
		set_process(false)
		return
	_rebuild_t -= delta
	if _rebuild_t <= 0.0:
		_rebuild_t = -1.0
		refresh()


func refresh() -> void:
	if level == null or not is_inside_tree():
		return
	apply_paint()
	build_lines()
	build_door_signs()


# ---------------------------------------------------------------- colours

static func _hex(c: String, fallback: Color) -> Color:
	return Color.html(c) if c != "" and Color.html_is_valid(c) else fallback


static func accent_colour(st: GameState) -> Color:
	return _hex(str(Content.premises_option("accent", str(st.style("accent"))).get("colour", "")), Color(0.91, 0.5, 0.18))


static func line_colour(st: GameState) -> Color:
	return _hex(str(Content.premises_option("line_colour", str(st.style("line_colour"))).get("colour", "")), Color(0.95, 0.76, 0.09))


static func paint_colour(st: GameState) -> Variant:
	## null = leave the walls as the building came.
	var c := str(Content.premises_option("paint", str(st.style("paint"))).get("colour", ""))
	return Color.html(c) if c != "" and Color.html_is_valid(c) else null


# ---------------------------------------------------------------- paint

func paint_targets() -> Array:
	## The MeshInstance3Ds that are this building's inside walls.
	var out: Array = []
	var geo := level.get_node_or_null("Geometry")
	if geo == null:
		return out
	for m in Content.building(level.building_id).get("paint_mats", []):
		var mi := geo.get_node_or_null("Geo_" + str(m)) as MeshInstance3D
		if mi:
			out.append(mi)
	return out


func apply_paint() -> void:
	var col = paint_colour(Game.state)
	var sig := str(col)
	if sig == _paint_sig:
		return
	_paint_sig = sig
	for mi: MeshInstance3D in paint_targets():
		if not _orig.has(mi):
			_orig[mi] = mi.material_override
		if col == null:
			mi.material_override = _orig[mi]
			continue
		var base: Material = _orig[mi] if _orig[mi] != null else mi.get_active_material(0)
		if base is StandardMaterial3D:
			var painted := (base as StandardMaterial3D).duplicate() as StandardMaterial3D
			# Fresh emulsion: the block/cladding texture still shows through, in the new colour.
			painted.albedo_color = (col as Color) * 1.06
			painted.set_meta("painted", true)
			mi.material_override = painted
		else:
			mi.material_override = Mats.color(col, 0.85)


func painted() -> bool:
	for mi: MeshInstance3D in paint_targets():
		if mi.material_override and mi.material_override.has_meta("painted"):
			return true
	return false


# ---------------------------------------------------------------- floor lines

func build_lines() -> void:
	var mode := str(Game.state.style("markings"))
	var col := line_colour(Game.state)
	var fx_sig := ""
	if mode in ["bays", "both"]:
		for fid in Game.state.fixtures:
			var f: Dictionary = Game.state.fixtures[fid]
			if f.get("placed", false):
				fx_sig += "%s%s%s;" % [fid, str(f.p), str(f.r)]
	var sig := "%s|%s|%s|%s" % [mode, col.to_html(), level.building_id, fx_sig.md5_text()]
	if sig == _lines_sig:
		return
	_lines_sig = sig
	for c in _lines.get_children():
		c.queue_free()
	if mode == "none":
		return
	var bb := BoxBatch.new()
	var mat := Mats.color(col, 0.62)
	if mode in ["walkways", "both"]:
		for fr: Rect2 in level.floor_rects:
			var r := fr.grow(-WALKWAY_INSET)
			if r.size.x < 1.0 or r.size.y < 1.0:
				continue
			_outline(bb, r, mat)
	if mode in ["bays", "both"]:
		for fid in Game.state.fixtures:
			var f: Dictionary = Game.state.fixtures[fid]
			if not f.get("placed", false):
				continue
			var def := Content.fixture(str(f.type))
			if def.get("decor", false) or Automation.def_of(str(f.type)).get("kind", "") == "belt" or str(f.type) == "pallet_bay":
				continue
			var lr := Automation.local_rect(str(f.type), f.get("bend", {})).grow(0.08)
			_outline_xf(bb, lr, Automation.fixture_xf(f), mat)
		if level.goods_in:
			var g := level.goods_in.global_position
			var zone := Rect2(g.x - 2.6, g.z - 1.6, 5.2, 3.2)
			if level.floor_rects.any(func(fr: Rect2): return fr.encloses(zone)):
				_outline(bb, zone, mat)
				_hatch(bb, zone, mat)
	if not bb.is_empty():
		for mi: MeshInstance3D in bb.commit(_lines, "Lines", false):
			mi.gi_mode = GeometryInstance3D.GI_MODE_DISABLED


func _outline(bb: BoxBatch, r: Rect2, mat: Material) -> void:
	bb.box(Vector3(r.size.x + LINE_W, 0.003, LINE_W), Vector3(r.get_center().x, LINE_Y, r.position.y), mat)
	bb.box(Vector3(r.size.x + LINE_W, 0.003, LINE_W), Vector3(r.get_center().x, LINE_Y, r.end.y), mat)
	bb.box(Vector3(LINE_W, 0.003, r.size.y), Vector3(r.position.x, LINE_Y, r.get_center().y), mat)
	bb.box(Vector3(LINE_W, 0.003, r.size.y), Vector3(r.end.x, LINE_Y, r.get_center().y), mat)


func _outline_xf(bb: BoxBatch, r: Rect2, xf: Transform3D, mat: Material) -> void:
	var w := LINE_W * 0.75
	var c := r.get_center()
	for e in [[Vector3(r.size.x + w, 0.003, w), Vector3(c.x, LINE_Y, r.position.y)], [Vector3(r.size.x + w, 0.003, w), Vector3(c.x, LINE_Y, r.end.y)],
			[Vector3(w, 0.003, r.size.y), Vector3(r.position.x, LINE_Y, c.y)], [Vector3(w, 0.003, r.size.y), Vector3(r.end.x, LINE_Y, c.y)]]:
		bb.box_xf(e[0], Transform3D(xf.basis, xf * (e[1] as Vector3)), mat)


func _hatch(bb: BoxBatch, r: Rect2, mat: Material) -> void:
	## Diagonal hatching inside a box (45°), clipped to it.
	var step := 0.5
	var k := r.position.x - r.size.y
	while k < r.end.x:
		var a := Vector2(k, r.position.y)
		var b := Vector2(k + r.size.y, r.end.y)
		# Clip the diagonal to the rectangle.
		var t0 := maxf(0.0, (r.position.x - a.x) / (b.x - a.x))
		var t1 := minf(1.0, (r.end.x - a.x) / (b.x - a.x))
		if t1 > t0 + 0.02:
			var p0 := a.lerp(b, t0)
			var p1 := a.lerp(b, t1)
			bb.beam(Vector3(p0.x, LINE_Y, p0.y), Vector3(p1.x, LINE_Y, p1.y), Vector2(LINE_W * 0.6, 0.003), mat)
		k += step


func line_count() -> int:
	var n := 0
	for c in _lines.get_children():
		if c is MeshInstance3D and not c.is_queued_for_deletion():
			n += 1
	return n


# ---------------------------------------------------------------- door signage

func doors() -> Array:
	var out: Array = []
	if level == null:
		return out
	var stack: Array = [level]
	while not stack.is_empty():
		var n: Node = stack.pop_back()
		if n is RollerDoor:
			out.append(n)
		elif n != level and (n is ItemView or n.name in ["Items", "Players", "Fixtures"]):
			continue
		stack.append_array(n.get_children())
	return out


func build_door_signs() -> void:
	var on := bool(Game.state.style("door_sign"))
	var sig := "%s|%s|%s|%s" % [on, Game.state.company_name, accent_colour(Game.state).to_html(), str(Game.state.style("logo"))]
	if sig == _doors_sig:
		return
	_doors_sig = sig
	for c in _signs.get_children():
		c.queue_free()
	for d: Node in doors():
		var old := d.get_node_or_null("ShutterLogo")
		if old:
			old.queue_free()
	if not on:
		return
	_place_signs.call_deferred()


func _place_signs() -> void:
	for i in 2:
		if not is_inside_tree():
			return
		await get_tree().physics_frame
	if not is_inside_tree():
		return
	var accent := accent_colour(Game.state)
	var logo := str(Game.state.style("logo"))
	var name_text := Game.state.company_name
	for door: RollerDoor in doors():
		var w := minf(door.width * 0.78, 4.2)
		# Fascia sign on the wall above the opening (found by looking for the wall from outside).
		var q := PhysicsRayQueryParameters3D.new()
		q.from = door.global_transform * Vector3(0, door.height + 0.42, 1.6)
		q.to = door.global_transform * Vector3(0, door.height + 0.42, -0.4)
		q.collision_mask = 1
		var hit := get_world_3d().direct_space_state.intersect_ray(q)
		if not hit.is_empty() and (hit.normal as Vector3).dot(door.global_basis.z) > 0.5:
			var sign := Node3D.new()
			sign.name = "Sign"
			_signs.add_child(sign)
			sign.global_transform = Transform3D(door.global_basis, (hit.position as Vector3) + door.global_basis.z * 0.012)
			_sign_panel(sign, w, 0.46, accent, logo, name_text)
		# The logo on the shutter itself (rolls up with it).
		var curtain: Node3D = door.get("_curtain")
		if curtain and logo != "plain":
			var badge := Node3D.new()
			badge.name = "ShutterLogo"
			curtain.add_child(badge)
			badge.position = Vector3(0, -door.height * 0.42, 0.018)
			_logo(badge, logo, accent, Game.state.company_name, 0.5)


static func _sign_panel(root: Node3D, w: float, h: float, accent: Color, logo: String, company: String) -> void:
	var bb := BoxBatch.new()
	bb.box(Vector3(w, h, 0.03), Vector3(0, 0, 0.015), Mats.color(accent.darkened(0.15), 0.45, 0.1))
	bb.box(Vector3(w - 0.04, h - 0.04, 0.004), Vector3(0, 0, 0.032), Mats.color(accent, 0.45, 0.05))
	bb.commit(root, "Panel")
	var x0 := -w * 0.5 + h * 0.55
	var text_l := -w * 0.5 + 0.08
	if logo != "plain":
		var lb := Node3D.new()
		lb.position = Vector3(x0, 0, 0.036)
		root.add_child(lb)
		_logo(lb, logo, Color(0.98, 0.98, 0.96), company, h * 0.74, accent)
		text_l = x0 + h * 0.48
	var text_r := w * 0.5 - 0.08
	var t := Label3D.new()
	t.text = company.to_upper()
	t.font_size = 96
	t.pixel_size = clampf(h * 0.0042, 0.001, 0.003)
	t.modulate = Color(0.98, 0.98, 0.96)
	t.outline_size = 0
	t.shaded = true
	t.position = Vector3((text_l + text_r) * 0.5, 0, 0.037)
	t.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	root.add_child(t)
	# Keep long names inside the panel.
	var max_w := text_r - text_l
	var est := float(company.length()) * float(t.font_size) * 0.62 * t.pixel_size
	if est > max_w:
		t.pixel_size *= max_w / est


static func _logo(root: Node3D, style: String, col: Color, company: String, size: float, ink: Color = Color(1, 1, 1)) -> void:
	## The company logo, `size` metres across, facing +Z. col = the mark, ink = the initials on it.
	var bb := BoxBatch.new()
	var mark := Mats.color(col, 0.45)
	var initials := DecorProp._initials(company)
	match style:
		"badge":
			bb.box(Vector3(size, size, 0.008), Vector3(0, 0, 0.004), mark)
			for c in [Vector2(-1, -1), Vector2(1, -1), Vector2(-1, 1), Vector2(1, 1)]:
				bb.cyl(size * 0.06, 0.009, Transform3D(Basis(Vector3.RIGHT, PI * 0.5), Vector3(c.x * size * 0.44, c.y * size * 0.44, 0.0045)), mark, 10)
		"ring":
			bb.cyl(size * 0.5, 0.008, Transform3D(Basis(Vector3.RIGHT, PI * 0.5), Vector3(0, 0, 0.004)), mark, 40)
			bb.cyl(size * 0.4, 0.009, Transform3D(Basis(Vector3.RIGHT, PI * 0.5), Vector3(0, 0, 0.0045)), Mats.color(ink, 0.45), 40)
			ink = col
		"chevron":
			for i in 3:
				var x := (i - 1) * size * 0.3
				bb.beam(Vector3(x - size * 0.12, size * 0.32, 0.006), Vector3(x + size * 0.12, 0, 0.006), Vector2(size * 0.1, 0.008), mark)
				bb.beam(Vector3(x + size * 0.12, 0, 0.006), Vector3(x - size * 0.12, -size * 0.32, 0.006), Vector2(size * 0.1, 0.008), mark)
			bb.commit(root, "Logo")
			return
	bb.commit(root, "Logo")
	var t := Label3D.new()
	t.text = initials
	t.font_size = 128
	t.pixel_size = size * 0.0042
	t.modulate = ink
	t.outline_size = 0
	t.shaded = true
	t.position = Vector3(0, -size * 0.02, 0.011)
	root.add_child(t)
