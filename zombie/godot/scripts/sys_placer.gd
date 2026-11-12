extends Node3D
## Build mode for sys_build.gd: the ghost that follows your aim and snaps to the 2 m grid, whether it may stand
## there and why not, the click that builds or dismantles, the build bar and the build menu (B).
## A port of the web game's Placer.

const U := preload("res://scripts/sys_util.gd")
const REACH := 9.0
const OK_COL := Color(0.3, 0.82, 1.0, 0.38)
const BAD_COL := Color(1.0, 0.25, 0.25, 0.38)
const DISMANTLE_COL := Color(1.0, 0.48, 0.13, 0.45)
const TABS := [["structure", "Structures"], ["station", "Survival"]]

var build                          # sys_build.gd
var active := false
var selected := ""
var dismantle := false
var rot := 0
var valid := false
var reason := ""
var target: Dictionary = {}        # {id, tx, tz, axis, rot, y, cx, cz}
var hover = null                   # the piece dismantle mode is pointing at
var tab := "structure"

var _ghost: Node3D = null
var _ghost_key := ""
var _ghost_ok := true
var _grid: MeshInstance3D
var _mats: Dictionary = {}
var _pending = null                # the target being hammered together
var _hammer_t := 0.0
var _layer: CanvasLayer
var _bar: PanelContainer
var _bar_box: VBoxContainer
var _bar_sig := ""

func _ready() -> void:
	for k in [["ok", OK_COL], ["bad", BAD_COL], ["dis", DISMANTLE_COL]]:
		var m := StandardMaterial3D.new()
		m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		m.albedo_color = k[1]
		m.cull_mode = BaseMaterial3D.CULL_DISABLED
		_mats[k[0]] = m
	_build_grid()
	_layer = CanvasLayer.new(); _layer.layer = 4
	add_child(_layer)
	_bar = PanelContainer.new()
	_bar.anchor_left = 0.5; _bar.anchor_right = 0.5; _bar.anchor_top = 1.0; _bar.anchor_bottom = 1.0
	_bar.offset_left = 0; _bar.offset_right = 0; _bar.offset_top = -88; _bar.offset_bottom = -88
	_bar.grow_horizontal = Control.GROW_DIRECTION_BOTH; _bar.grow_vertical = Control.GROW_DIRECTION_BEGIN
	_bar.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_bar.visible = false
	_bar_box = VBoxContainer.new(); _bar_box.add_theme_constant_override("separation", 4); _bar_box.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_bar.add_child(_bar_box)
	_layer.add_child(_bar)

func _process(_dt: float) -> void:
	# the bar belongs to play: no menu, no window, not dead
	var g = build.game
	var show: bool = active and g != null and g.state == "play" and not g.hud.is_open()
	if _bar.visible != show:
		_bar.visible = show
	if not show and (_grid.visible or (_ghost != null and _ghost.visible)):
		_grid.visible = false
		if _ghost != null: _ghost.visible = false

func _build_grid() -> void:
	var im := ImmediateMesh.new()
	im.surface_begin(Mesh.PRIMITIVE_LINES)
	for i in range(-6, 7):
		im.surface_add_vertex(Vector3(i * 2.0, 0, -12.0)); im.surface_add_vertex(Vector3(i * 2.0, 0, 12.0))
		im.surface_add_vertex(Vector3(-12.0, 0, i * 2.0)); im.surface_add_vertex(Vector3(12.0, 0, i * 2.0))
	im.surface_end()
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED; m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.albedo_color = Color(0.6, 0.85, 1.0, 0.18)
	_grid = MeshInstance3D.new(); _grid.mesh = im; _grid.material_override = m
	_grid.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_grid.visible = false
	add_child(_grid)

# ------------------------------------------------------------------ mode
func set_active(on: bool) -> void:
	if active == on:
		return
	active = on
	if not on:
		_clear_ghost(); _grid.visible = false; _set_hover(null)
		dismantle = false
		if _pending != null and build.game.action != null:
			build.game.cancel_action()
		_pending = null
	_bar_sig = ""

func select(id: String) -> void:
	selected = id; dismantle = false
	_clear_ghost(); _set_hover(null)
	set_active(true)
	_bar_sig = ""

func _clear_ghost() -> void:
	if _ghost != null:
		_ghost.queue_free()
	_ghost = null; _ghost_key = ""

func _paint(n: Node, mat: Material) -> void:
	if n is MeshInstance3D:
		n.material_override = mat
		n.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	for c in n.get_children():
		_paint(c, mat)

func _overlay(n: Node, mat: Material) -> void:
	if n is MeshInstance3D:
		n.material_overlay = mat
	for c in n.get_children():
		_overlay(c, mat)

func _set_hover(p) -> void:
	if hover != null and p != null and is_same(hover, p):
		return
	if hover != null and hover.get("node") != null and is_instance_valid(hover.node):
		_overlay(hover.node, null)
	hover = p
	if p != null and p.get("node") != null and is_instance_valid(p.node):
		_overlay(p.node, _mats.dis)
	_bar_sig = ""

# ------------------------------------------------------------------ input
func input(ev: InputEvent) -> bool:
	var g = build.game
	if g.state != "play" or g.player.sleeping:
		return false
	var hud = g.hud
	if ev is InputEventKey and ev.pressed and not ev.echo:
		var k: int = ev.physical_keycode
		if k == KEY_B:
			if hud.is_open():
				if hud.window_kind == "build":
					U.close(g)
					return true
				if hud.window_kind in ["main", "pause", "dead"]:
					return false
			open_menu()
			return true
		if hud.is_open():
			return false
		if active:
			if k == KEY_R:
				rot = (rot + 1) % 4; Sfx.play("ui_click"); return true
			if k == KEY_X:
				dismantle = not dismantle; Sfx.play("ui_click")
				_clear_ghost(); _set_hover(null); _bar_sig = ""
				return true
			if k == KEY_ESCAPE:
				set_active(false); Sfx.play("ui_close"); return true
	elif ev is InputEventMouseButton and ev.pressed and active and not hud.is_open() and Input.mouse_mode == Input.MOUSE_MODE_CAPTURED:
		if ev.button_index == MOUSE_BUTTON_LEFT:
			click(); return true
		if ev.button_index == MOUSE_BUTTON_RIGHT:
			set_active(false); Sfx.play("ui_close"); return true
	return false

# ------------------------------------------------------------------ every frame of play
func frame(dt: float, ui_open: bool) -> void:
	if not active:
		return
	var g = build.game
	if _pending != null:
		if g.action == null:
			_pending = null
		else:
			_hammer_t -= dt
			if _hammer_t <= 0.0:
				_hammer_t = 0.55
				Sfx.play("build_hammer", {"pos": Vector3(float(_pending.cx), float(_pending.y) + 1.0, float(_pending.cz)), "volume": 0.8})
	if ui_open or g.player.sleeping:
		return
	if _pending == null:
		_update_aim()
	_update_bar()

## where the look ray first comes down onto ground or a floor (never up through one), or null
func aim_point(eye: Vector3, dir: Vector3, max_d: float):
	var prev := 0.0
	var prev_y := eye.y
	var t := 0.5
	while t < max_d:
		var p := eye + dir * t
		if p.y <= WorldData.ground_y(p.x, p.z, prev_y - 0.55):
			var a := prev; var b := t
			for _i in 8:
				var m := (a + b) * 0.5
				var q := eye + dir * m
				if q.y <= WorldData.ground_y(q.x, q.z, prev_y - 0.55): b = m
				else: a = m
			return eye + dir * b
		prev = t; prev_y = p.y
		t += 0.25
	return null

func _update_aim() -> void:
	var g = build.game
	var eye: Vector3 = g.player.eye(); var fwd: Vector3 = g.player.forward()
	var p = aim_point(eye, fwd, 10.0 if dismantle else REACH)
	_grid.visible = p != null and not dismantle
	if p != null:
		_grid.position = Vector3(round(p.x / 2.0) * 2.0, p.y + 0.03, round(p.z / 2.0) * 2.0)
	if dismantle:
		_clear_ghost()
		_set_hover(_dismantle_target(eye, fwd, p))
		return
	if selected == "" or p == null:
		if _ghost != null: _ghost.visible = false
		valid = false; target = {}
		return
	if _ghost_key != selected:
		_clear_ghost()
		_ghost = build.make_model(selected)
		_ghost_key = selected; _ghost_ok = true
		_paint(_ghost, _mats.ok)
		add_child(_ghost)
	target = target_for(selected, p, rot)
	reason = check(target)
	valid = reason == ""
	_ghost.visible = true
	_ghost.position = Vector3(float(target.cx), float(target.y), float(target.cz))
	var edge: bool = build.EDGE_KINDS.has(String(build.defs[selected].kind))
	_ghost.rotation.y = (PI / 2.0 if int(target.axis) == 1 else 0.0) if edge else int(target.rot) * PI / 2.0
	if valid != _ghost_ok:
		_ghost_ok = valid
		_paint(_ghost, _mats.ok if valid else _mats.bad)

## the grid slot a piece would take when aiming at `p`
func target_for(id: String, p: Vector3, r: int) -> Dictionary:
	var kind := String(build.defs[id].kind)
	var feet := p.y + 0.6
	if build.EDGE_KINDS.has(kind):
		var e: Dictionary = build.nearest_edge(p.x, p.z)
		var cx := float(e.cx); var cz := float(e.cz)
		if kind == "gate":
			if int(e.axis) == 0: cx += 1.0
			else: cz += 1.0
		return {"id": id, "tx": int(e.tx), "tz": int(e.tz), "axis": int(e.axis), "rot": 0, "cx": cx, "cz": cz, "y": build.edge_base_y(cx, cz, int(e.axis), feet)}
	var tx: int = build.tile_of(p.x); var tz: int = build.tile_of(p.z)
	var y: float
	if kind == "foundation":
		y = build.tile_base_y(tx, tz, false)
		# next to a floor already laid, carry on at its height when the ground allows: one level platform
		for n in [[1, 0], [-1, 0], [0, 1], [0, -1]]:
			for q in build.tile_pieces(tx + n[0], tz + n[1]):
				if String(q.pkind) == "foundation" and float(q.y) >= y - 0.15 and float(q.y) - y <= 1.2:
					y = maxf(y, float(q.y))
	elif kind == "roof": y = build.roof_height(tx, tz, feet)
	else: y = build.tile_base_y(tx, tz, true, feet)
	return {"id": id, "tx": tx, "tz": tz, "axis": 0, "rot": r, "cx": (tx + 0.5) * 2.0, "cz": (tz + 0.5) * 2.0, "y": y}

func _near_building(x: float, z: float, m: float) -> bool:
	for pl in WorldData.info.get("plots", []):
		var hw := float(pl.w) / 2.0 + 1.0 + m; var hd := float(pl.d) / 2.0 + 1.0 + m
		if absf(x - float(pl.x)) < hw and absf(z - float(pl.z)) < hd:
			return true
	return false

## the box a piece would fill, for the checks below: [min, max]
func _volume(t: Dictionary) -> Array:
	var def: Dictionary = build.defs[String(t.id)]
	var kind := String(def.kind)
	var cx := float(t.cx); var cz := float(t.cz); var y := float(t.y)
	if build.EDGE_KINDS.has(kind):
		var wide := 4.0 if kind == "gate" else 2.0
		var hw := wide / 2.0 - 0.05 if int(t.axis) == 0 else 0.1; var hd := 0.1 if int(t.axis) == 0 else wide / 2.0 - 0.05
		return [Vector3(cx - hw, y + 0.1, cz - hd), Vector3(cx + hw, y + 2.9, cz + hd)]
	if kind == "station":
		var sz: Vector3 = build.station_size(def)
		var r90 := int(t.rot) % 2 == 1
		var hw2 := (sz.z if r90 else sz.x) / 2.0 - 0.04; var hd2 := (sz.x if r90 else sz.z) / 2.0 - 0.04
		return [Vector3(cx - hw2, y + 0.08, cz - hd2), Vector3(cx + hw2, y + maxf(0.3, sz.y), cz + hd2)]
	var h := 3.0 if kind == "stairs" else (5.0 if kind == "tower" else 0.3)
	return [Vector3(cx - 0.95, y + 0.05, cz - 0.95), Vector3(cx + 0.95, y + h, cz + 0.95)]

## what in the world stands where the piece would go ("" for nothing); bushes do not count, they are cleared
func _obstruction(mn: Vector3, mx: Vector3) -> String:
	for o in WorldData.query(mn.x, mn.z, mx.x, mx.z):
		if o is WorldData.Box:
			if o.ref is Dictionary and o.ref.get("built", false):
				continue
			if o.max.y <= mn.y or o.min.y >= mx.y:
				continue
			return "Something is in the way"
		else:
			if o.y1 <= mn.y or o.y0 >= mx.y:
				continue
			var qx := clampf(o.x, mn.x, mx.x); var qz := clampf(o.z, mn.z, mx.z)
			if (o.x - qx) * (o.x - qx) + (o.z - qz) * (o.z - qz) >= o.r * o.r:
				continue
			match String(o.kind):
				"bush": continue
				"tree": return "A tree is in the way"
				"rock": return "A boulder is in the way"
				"log": return "A fallen log is in the way"
				"ore": return "An ore seam is in the way"
			return "Something is in the way"
	return ""

func _clear_bushes(mn: Vector3, mx: Vector3) -> void:
	var world = build.game.world
	for o in WorldData.query(mn.x, mn.z, mx.x, mx.z):
		if o is WorldData.Circle and String(o.kind) == "bush":
			world.kill_veg(int(o.ref))

## why this slot will not do ("" when it will)
func check(t: Dictionary) -> String:
	var g = build.game
	var id := String(t.id)
	var def: Dictionary = build.defs[id]
	var kind := String(def.kind)
	var cx := float(t.cx); var cz := float(t.cz); var y := float(t.y)
	var tx := int(t.tx); var tz := int(t.tz); var axis := int(t.axis)
	var why := ""
	if not WorldData.in_bounds(cx, cz):
		why = "Too close to the edge of the valley"
	elif build.EDGE_KINDS.has(kind):
		if build.edge_taken(tx, tz, axis) or (kind == "gate" and build.edge_taken(tx + 1 if axis == 0 else tx, tz + 1 if axis == 1 else tz, axis)):
			why = "Edge occupied"
		elif _near_building(cx, cz, 0.5):
			why = "Too close to a building"
		elif absf(WorldData.height_at(cx - (1.0 if axis == 0 else 0.0), cz - (1.0 if axis == 1 else 0.0)) - WorldData.height_at(cx + (1.0 if axis == 0 else 0.0), cz + (1.0 if axis == 1 else 0.0))) > 2.5:
			why = "Too steep"
	else:
		if build.tile_taken(tx, tz, kind):
			why = "Tile occupied"
		elif kind != "station" and _near_building(cx, cz, 0.5):
			why = "Too close to a building"
		elif WorldData.water_depth(cx, cz) > 0.4:
			why = "In water"
		elif kind == "foundation" and build.terrain_spread(cx, cz, 0.95, 0.95) > 2.5:
			why = "Too steep"
		elif kind != "foundation" and kind != "roof" and absf(y - build.tile_base_y(tx, tz, false)) < 0.01 and build.terrain_spread(cx, cz, 0.9, 0.9) > (0.35 if kind == "stairs" else 0.7):
			why = "Too steep — lay a foundation first"
	if why == "":
		var vol := _volume(t)
		why = _obstruction(vol[0], vol[1])
		if why == "" and (build.EDGE_KINDS.has(kind) or kind == "station"):
			var pp: Vector3 = g.player.pos
			var qx := clampf(pp.x, vol[0].x, vol[1].x); var qz := clampf(pp.z, vol[0].z, vol[1].z)
			if Vector2(pp.x - qx, pp.z - qz).length() < 0.36 and pp.y < vol[1].y and pp.y + 1.8 > vol[0].y:
				why = "You are standing in the way"
	if why == "" and not build.unlocked(id):
		var mi: int = build.unlock_milestone(id)
		why = "Unlocks when your settlement becomes a %s" % String(build.milestones[mi].name) if mi >= 0 else "Not available yet"
	if why == "":
		why = build.skill_missing(id)
	if why == "" and not build.free_build and not U.has_all(g.player.inv, U.cost_of(def.cost)):
		why = "Missing materials"
	return why

# ------------------------------------------------------------------ clicking
func click() -> void:
	var g = build.game
	if g.action != null:
		return
	if dismantle:
		if hover != null:
			var h: Dictionary = hover
			_set_hover(null)
			build.dismantle(h)
		return
	if selected == "" or target.is_empty():
		return
	if not valid:
		Sfx.play("ui_error")
		g.hud.notify((reason if reason != "" else "Cannot place here") + ".", "bad")
		return
	var t := target.duplicate()
	_pending = t; _hammer_t = 0.0
	g.start_action("Building the %s…" % String(build.defs[selected].name).to_lower(), build.build_time(selected), func() -> void: finish(t), true)

## The checks, the cost, and the piece itself. Returns the piece, or null with `reason` set.
func finish(t: Dictionary):
	var g = build.game
	_pending = null
	reason = check(t)
	if reason != "":
		Sfx.play("ui_error"); g.hud.notify(reason + ".", "bad")
		return null
	var id := String(t.id)
	var def: Dictionary = build.defs[id]
	if not build.free_build and not U.take_all(g.player.inv, U.cost_of(def.cost)):
		return null
	var vol := _volume(t)
	_clear_bushes(vol[0], vol[1])
	var p: Dictionary = build.place(id, int(t.tx), int(t.tz), int(t.axis), int(t.rot), float(t.y))
	var pos := Vector3(float(t.cx), float(t.y), float(t.cz))
	Sfx.play("build_place", {"pos": pos + Vector3(0, 1, 0)})
	g.make_noise(g.player.pos, 12.0)
	g.player.gain_xp("carpentry", 4.0, g.game_min)
	if g.get("fx") != null:
		g.fx.dust(pos + Vector3(0, 0.2, 0))
	_bar_sig = ""
	return p

## Build without the hammering: aim at a point on the ground and the piece goes up if it may (tests, scripts).
func build_at(id: String, at: Vector3, r := 0):
	return finish(target_for(id, at, r))

func _dismantle_target(eye: Vector3, fwd: Vector3, p):
	var hit: Dictionary = WorldData.raycast(eye, fwd, 10.0)
	var floor_t := INF if p == null else eye.distance_to(p)
	if hit.obj != null and hit.obj is WorldData.Box and hit.obj.ref is Dictionary and hit.obj.ref.get("built", false) and float(hit.t) < floor_t + 0.3:
		return hit.obj.ref
	# a roof seen from underneath
	var t := 0.5
	while t < minf(10.0, floor_t):
		var q := eye + fwd * t
		for pc in build.tile_pieces(build.tile_of(q.x), build.tile_of(q.z)):
			if String(pc.pkind) == "roof" and q.y > float(pc.y) - 0.1 and q.y < float(pc.y) + 0.35:
				return pc
		t += 0.2
	if p == null:
		return null
	# whatever lies under the aim: the thing whose top is nearest
	var best = null; var bd := 1.2
	for pc in build.tile_pieces(build.tile_of(p.x), build.tile_of(p.z)):
		var top := float(pc.y)
		match String(pc.pkind):
			"foundation": top += 0.3
			"roof": top += 0.15
			"station": top += float(pc.half.y) * 2.0
		var d := absf(top - p.y)
		if String(pc.pkind) == "stairs": d = 0.0 if p.y >= float(pc.y) - 0.1 and p.y <= float(pc.y) + 3.3 else 9.0
		elif String(pc.pkind) == "station": d = minf(d, absf(float(pc.y) - p.y))
		if d < bd:
			bd = d; best = pc
	return best

# ------------------------------------------------------------------ the build bar
func _update_bar() -> void:
	var g = build.game
	var inv: Inventory = g.player.inv
	var sig := "%s|%s|%s|%s|%s" % [selected, dismantle, valid, reason, target.is_empty()]
	if dismantle:
		sig += "|" + (str(int(hover.id)) if hover != null else "-")
	elif selected != "":
		for k in build.defs[selected].cost:
			sig += "|%d" % inv.count(String(k))
	if sig == _bar_sig:
		return
	_bar_sig = sig
	for c in _bar_box.get_children():
		c.queue_free()
	var hud = g.hud
	if dismantle:
		var title: Label = hud.label("DISMANTLE", 17, Color(1.0, 0.63, 0.38)); title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		_bar_box.add_child(title)
		var line := "Point at something you built"
		if hover != null:
			var why: String = build.dismantle_blocked(hover)
			line = "%s  ·  %s" % [String(hover.name), why if why != "" else ("gives back " + U.cost_text(build.refund_of(hover)) if not build.free_build else "free build")]
		var l: Label = hud.label(line, 14); l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		_bar_box.add_child(l)
		var hint: Label = hud.label("LMB take down   ·   X back to building   ·   B menu   ·   RMB / Esc leave", 12, Hud.DIM); hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		_bar_box.add_child(hint)
		return
	if selected == "":
		_bar_box.add_child(hud.label("Pick something from the build menu (B)", 14, Hud.DIM))
		return
	var def: Dictionary = build.defs[selected]
	var name_l: Label = hud.label(String(def.name), 17); name_l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_bar_box.add_child(name_l)
	var cc := CenterContainer.new(); cc.mouse_filter = Control.MOUSE_FILTER_IGNORE
	cc.add_child(U.cost_row(hud, inv, U.cost_of(def.cost), 14, true))
	_bar_box.add_child(cc)
	var state := "LMB build" if valid else reason
	if target.is_empty(): state = "Aim at the ground within reach"
	var st: Label = hud.label(state, 14, Hud.DIM if target.is_empty() else (Hud.GOOD if valid else Hud.BAD)); st.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_bar_box.add_child(st)
	var hint2: Label = hud.label("R rotate   ·   X dismantle   ·   B menu   ·   RMB / Esc leave", 12, Hud.DIM); hint2.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_bar_box.add_child(hint2)

# ------------------------------------------------------------------ the build menu
func open_menu() -> void:
	var g = build.game
	var hud = g.hud
	var v: VBoxContainer = U.open(g, "build", 1020.0, 640.0, "Build", not (hud.is_open() and hud.window_kind == "build"))
	var head := HBoxContainer.new(); head.add_theme_constant_override("separation", 8)
	for tb in TABS:
		var key := String(tb[0])
		head.add_child(U.tab_button(hud, String(tb[1]), tab == key, func() -> void:
			tab = key; Sfx.play("ui_click"); open_menu()))
	var gap := Control.new(); gap.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	head.add_child(gap)
	head.add_child(U.tab_button(hud, "Dismantle…", false, func() -> void:
		U.close(g, false); set_active(true); dismantle = true; _clear_ghost(); _bar_sig = ""; Sfx.play("ui_click")))
	v.add_child(head)
	var carp: int = g.player.level("carpentry")
	v.add_child(hud.label("Carpentry %d   ·   pieces snap to a 2 m grid: floors to tiles, walls and doors to tile edges" % carp, 13, Hud.DIM))
	var sc := ScrollContainer.new(); sc.size_flags_vertical = Control.SIZE_EXPAND_FILL; sc.custom_minimum_size = Vector2(0, 430)
	sc.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	var grid := GridContainer.new(); grid.columns = 3
	grid.add_theme_constant_override("h_separation", 8); grid.add_theme_constant_override("v_separation", 8)
	grid.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	sc.add_child(grid); v.add_child(sc)
	for id in build.defs:
		var def: Dictionary = build.defs[id]
		if ("station" if String(def.kind) == "station" else "structure") != tab:
			continue
		grid.add_child(_card(String(id), def))
	v.add_child(hud.label("Click a piece to carry its plan into the world   ·   there: LMB build · R rotate · X dismantle · RMB leave   ·   B / Esc: close", 12, Hud.DIM))

func _card(id: String, def: Dictionary) -> Control:
	var g = build.game
	var hud = g.hud
	var inv: Inventory = g.player.inv
	var cost := U.cost_of(def.cost)
	var open: bool = build.unlocked(id)
	var skill: String = build.skill_missing(id)
	var afford: bool = build.free_build or U.has_all(inv, cost)
	var b: Button = U.row_button(hud, 128.0)
	b.custom_minimum_size = Vector2(312, 128)
	b.disabled = not open
	var box := VBoxContainer.new(); box.set_anchors_preset(Control.PRESET_FULL_RECT)
	box.offset_left = 10; box.offset_right = -10; box.offset_top = 7; box.offset_bottom = -7
	box.add_theme_constant_override("separation", 3); box.mouse_filter = Control.MOUSE_FILTER_IGNORE
	box.add_child(hud.label(String(def.name), 17, Hud.INK if open and skill == "" and afford else Hud.DIM))
	var desc: Label = hud.label(_blurb(def), 12, Hud.DIM)
	desc.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART; desc.custom_minimum_size = Vector2(290, 42); desc.max_lines_visible = 2
	desc.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	box.add_child(desc)
	if open:
		box.add_child(U.cost_row(hud, inv, cost, 13))
		var note := ""
		var col := Hud.DIM
		if skill != "":
			note = skill; col = Hud.BAD
		elif def.has("skill"):
			note = "%s %d" % [U.skill_name(String(def.skill[0])), int(def.skill[1])]
		elif not afford:
			note = "Missing materials"; col = Hud.BAD
		box.add_child(hud.label(note, 12, col))
	else:
		var mi: int = build.unlock_milestone(id)
		box.add_child(hud.label("Unlocks when your settlement becomes a %s" % String(build.milestones[mi].name) if mi >= 0 else "Not available yet", 12, Hud.DIM))
	b.add_child(box)
	b.pressed.connect(func() -> void:
		U.close(g, false); Sfx.play("ui_click"); select(id))
	return b

## the web game's descriptions, less the jokes that only made sense there
func _blurb(def: Dictionary) -> String:
	match String(def.id):
		"bed": return "Sleep to pass the night and recover."
		"watchtower": return "A 5 m lookout platform. Climb the ladder (E)."
		"rain_collector": return "Collects clean rainwater. Fill bottles or drink (E)."
	return String(def.desc)
