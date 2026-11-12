class_name UiMap
## The valley on paper. The ground (height shading, contour lines, forest, the lake) is baked once into a
## texture on a worker thread; roads, buildings, cars and markers are drawn over it as shapes, so they stay
## sharp at any zoom. `View` is the control that shows it — the corner minimap and the full map both use it.

const HALF := 400.0
const NAMES := {"supermarket": "Supermarket", "police": "Police station", "clinic": "Clinic", "hardware": "Hardware store", "gas_station": "Gas station", "barn": "Barn", "warehouse_small": "Warehouse", "cabin": "Cabin"}
const C_ROAD := Color(0.6, 0.58, 0.53)
const C_STREET := Color(0.5, 0.485, 0.45)
const C_DIRT := Color(0.47, 0.4, 0.3)
const C_BUILD := Color(0.74, 0.69, 0.58)
const C_DONE := Color(0.4, 0.385, 0.35)
const C_VOID := Color(0.035, 0.04, 0.038)

static var base: ImageTexture = null
static var _task := -1
static var _img: Image = null
static var _world = null
static var markers: Array = []          # the player's own pins: [{x, z}]
static var buildings: Array = []        # [{id, rect, kind, name, total, done}]
static var roads: Array = []            # [{a, b, w, col}]
static var cars: Array = []             # PackedVector2Array quads, in metres
static var home := Vector2.ZERO
static var town := Rect2()
static var searched_stamp := 0

## gather what the map draws and start baking the ground; safe to call again
static func prepare(world) -> void:
	if world == null:
		return
	if not is_same(world, _world):
		_world = world
		var info: Dictionary = WorldData.info
		roads.clear(); buildings.clear(); cars.clear()
		for r in info.get("roads", []):
			var kind := String(r.get("kind", "street"))
			roads.append({"a": Vector2(float(r.ax), float(r.az)), "b": Vector2(float(r.bx), float(r.bz)), "w": float(r.w), "col": C_ROAD if kind == "highway" else (C_DIRT if kind == "dirt" else C_STREET)})
		var cab: Dictionary = info.get("cabin", {"x": 0, "z": 0})
		home = Vector2(float(cab.x), float(cab.z))
		var t: Dictionary = info.get("town", {"x0": -150, "x1": 150, "z0": -120, "z1": 120})
		town = Rect2(float(t.x0), float(t.z0), float(t.x1) - float(t.x0), float(t.z1) - float(t.z0))
		for id in world.buildings:
			var b: Dictionary = world.buildings[id].data
			var kind2 := String(b.get("kind", "house"))
			var nm := String(NAMES.get(kind2, ""))
			if kind2 == "cabin" and Vector2(float(b.x), float(b.z)).distance_to(home) < 4.0:
				nm = "Your cabin"
			buildings.append({"id": int(id), "rect": Rect2(float(b.x) - float(b.w) * 0.5, float(b.z) - float(b.d) * 0.5, float(b.w), float(b.d)), "kind": kind2, "name": nm, "total": 0, "done": 0})
		var td = world.get("town_data")
		if td is Dictionary:
			for c in td.get("cars", []):
				var p := Vector2(float(c.pos[0]), float(c.pos[2]))
				var hx := float(c.size[0]) * 0.5; var hz := float(c.size[2]) * 0.5
				var rot := -float(c.get("yaw", 0.0))
				var quad := PackedVector2Array()
				for k in [Vector2(-hx, -hz), Vector2(hx, -hz), Vector2(hx, hz), Vector2(-hx, hz)]:
					quad.append(p + k.rotated(rot))
				cars.append(quad)
		count_searched()
	if base == null and _task < 0 and WorldData.heights.size() > 0:
		_task = WorkerThreadPool.add_task(UiMap._bake.bind(WorldData.heights, WorldData.hn, WorldData.hres, WorldData.forest, WorldData.forest_n, WorldData.lake.duplicate()))

## true once the ground texture exists
static func ready() -> bool:
	if base != null:
		return true
	if _task >= 0 and WorkerThreadPool.is_task_completed(_task):
		WorkerThreadPool.wait_for_task_completion(_task)
		_task = -1
		if _img != null:
			base = ImageTexture.create_from_image(_img)
			_img = null
	return base != null

## how much of each building has been gone through
static func count_searched() -> void:
	if _world == null:
		return
	var total := {}; var done := {}
	for c in _world.containers:
		var b := int(c.get("building", 0))
		total[b] = int(total.get(b, 0)) + 1
		if c.get("searched", false):
			done[b] = int(done.get(b, 0)) + 1
	for e in buildings:
		e.total = int(total.get(e.id, 0)); e.done = int(done.get(e.id, 0))
	searched_stamp += 1

static func _bake(h: PackedFloat32Array, hn: int, hres: float, forest: PackedByteArray, fn: int, lake: Dictionary) -> void:
	const N := 512
	var px := HALF * 2.0 / N
	var hs := PackedFloat32Array(); hs.resize(N * N)
	for j in N:
		var gz := clampf((-HALF + (j + 0.5) * px + HALF) / hres, 0.0, hn - 1.001)
		var j0 := int(gz); var fz := gz - j0
		for i in N:
			var gx := clampf((-HALF + (i + 0.5) * px + HALF) / hres, 0.0, hn - 1.001)
			var i0 := int(gx); var fx := gx - i0
			var k := j0 * hn + i0
			hs[j * N + i] = lerpf(lerpf(h[k], h[k + 1], fx), lerpf(h[k + hn], h[k + hn + 1], fx), fz)
	var data := PackedByteArray(); data.resize(N * N * 3)
	var lx := float(lake.x); var lz := float(lake.z); var lr := float(lake.r); var ll := float(lake.level)
	for j in N:
		var z := -HALF + (j + 0.5) * px
		var fj := clampi(int(round((z + HALF) / 4.0)), 0, fn - 1)
		for i in N:
			var x := -HALF + (i + 0.5) * px
			var k2 := j * N + i
			var hh := hs[k2]
			var hr := hs[k2 + 1] if i < N - 1 else hh
			var hd := hs[k2 + N] if j < N - 1 else hh
			var f := forest[fj * fn + clampi(int(round((x + HALF) / 4.0)), 0, fn - 1)] / 255.0
			var r := 0.25; var g := 0.285; var b := 0.2
			r = lerpf(r, 0.125, f * 0.9); g = lerpf(g, 0.185, f * 0.9); b = lerpf(b, 0.13, f * 0.9)
			var lift := clampf(hh * 0.0045, -0.03, 0.12)
			r += lift; g += lift; b += lift * 0.8
			var sx := (hr - hh) / px; var sz := (hd - hh) / px
			var steep := sqrt(sx * sx + sz * sz)
			if steep > 0.45:
				var rk := clampf((steep - 0.45) * 3.0, 0.0, 1.0)
				r = lerpf(r, 0.36, rk); g = lerpf(g, 0.35, rk); b = lerpf(b, 0.32, rk)
			var shade := clampf(1.0 + (sx + sz) * 0.55, 0.62, 1.4)       # lit from the north-west
			r *= shade; g *= shade; b *= shade
			if int(floor(hh / 4.0)) != int(floor(hr / 4.0)) or int(floor(hh / 4.0)) != int(floor(hd / 4.0)):
				r *= 0.82; g *= 0.82; b *= 0.82
			var dl := sqrt((x - lx) * (x - lx) + (z - lz) * (z - lz))
			if hh < ll and dl < lr:
				var deep := clampf((ll - hh) * 0.25, 0.0, 1.0)
				r = lerpf(0.2, 0.1, deep); g = lerpf(0.32, 0.2, deep); b = lerpf(0.38, 0.28, deep)
			data[k2 * 3] = int(clampf(r, 0.0, 1.0) * 255.0)
			data[k2 * 3 + 1] = int(clampf(g, 0.0, 1.0) * 255.0)
			data[k2 * 3 + 2] = int(clampf(b, 0.0, 1.0) * 255.0)
	var img := Image.create_from_data(N, N, false, Image.FORMAT_RGB8, data)
	img.generate_mipmaps()
	_img = img

static func place_name(x: float, z: float) -> String:
	var p := Vector2(x, z)
	if p.distance_to(home) < 16.0: return "YOUR CABIN"
	for e in buildings:
		if e.rect.grow(1.5).has_point(p) and String(e.name) != "":
			return String(e.name).to_upper()
	if town.has_point(p): return "THE TOWN"
	var lake: Dictionary = WorldData.lake
	if p.distance_to(Vector2(float(lake.x), float(lake.z))) < float(lake.r) + 14.0: return "LAKESHORE"
	if absf(z) < 9.0: return "THE HIGHWAY"
	if WorldData.forest_at(x, z) > 0.45: return "FOREST"
	return "THE VALLEY"

# ------------------------------------------------------------------ the control
class View extends Control:
	signal marker_changed
	var game
	var mini := false
	var center := Vector2.ZERO           # world x/z shown at the middle
	var zoom := 1.0                      # pixels per metre
	var turn := false                    # turn the map so that ahead is up (minimap)
	var yaw := 0.0
	var player := Vector2.ZERO
	var follow := true
	var hover_building := -1
	var _drag := false
	var _stamp := -1
	var _had_base := false
	var _last := Vector3(INF, 0, 0)
	var _idle := 0.0
	var chasers := PackedVector2Array()

	func _init(g, is_mini: bool) -> void:
		game = g; mini = is_mini
		clip_contents = true
		mouse_filter = Control.MOUSE_FILTER_IGNORE if is_mini else Control.MOUSE_FILTER_STOP
		texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
		if not is_mini:
			size_flags_horizontal = Control.SIZE_EXPAND_FILL; size_flags_vertical = Control.SIZE_EXPAND_FILL
			mouse_default_cursor_shape = Control.CURSOR_DRAG
			zoom = 1.6

	## called every frame by whoever owns the view; redraws only when something moved
	func track(pos: Vector3, yaw_: float, dt: float) -> void:
		player = Vector2(pos.x, pos.z); yaw = yaw_
		if follow:
			center = player
		_idle += dt
		var ok := UiMap.ready()
		if ok != _had_base or _stamp != UiMap.searched_stamp or absf(pos.x - _last.x) > 0.12 or absf(pos.z - _last.y) > 0.12 or absf(yaw_ - _last.z) > 0.004 or _idle > 0.5:
			_had_base = ok; _stamp = UiMap.searched_stamp; _last = Vector3(pos.x, pos.z, yaw_); _idle = 0.0
			queue_redraw()

	func to_screen(w: Vector2) -> Vector2:
		var v := (w - center) * zoom
		if turn: v = v.rotated(yaw)
		return size * 0.5 + v

	func to_world(s: Vector2) -> Vector2:
		var v := s - size * 0.5
		if turn: v = v.rotated(-yaw)
		return center + v / zoom

	func _gui_input(ev: InputEvent) -> void:
		if mini:
			return
		if ev is InputEventMouseButton:
			if ev.button_index == MOUSE_BUTTON_LEFT:
				_drag = ev.pressed
			elif ev.pressed and (ev.button_index == MOUSE_BUTTON_WHEEL_UP or ev.button_index == MOUSE_BUTTON_WHEEL_DOWN):
				zoom_at(ev.position, 1.18 if ev.button_index == MOUSE_BUTTON_WHEEL_UP else 1.0 / 1.18)
			elif ev.pressed and ev.button_index == MOUSE_BUTTON_RIGHT:
				_toggle_marker(to_world(ev.position))
			accept_event()
		elif ev is InputEventMouseMotion:
			if _drag:
				follow = false
				center -= ev.relative / zoom
				_clamp()
				queue_redraw()
			else:
				var w := to_world(ev.position)
				var hb := -1
				for i in UiMap.buildings.size():
					if UiMap.buildings[i].rect.has_point(w):
						hb = i; break
				if hb != hover_building:
					hover_building = hb; queue_redraw()

	func zoom_at(at: Vector2, factor: float) -> void:
		var before := to_world(at)
		zoom = clampf(zoom * factor, minf(size.x, size.y) / (UiMap.HALF * 2.0), 8.0)
		center += before - to_world(at)
		follow = false
		_clamp(); queue_redraw()

	func recenter() -> void:
		follow = true; center = player; queue_redraw()

	func _clamp() -> void:
		center.x = clampf(center.x, -UiMap.HALF, UiMap.HALF); center.y = clampf(center.y, -UiMap.HALF, UiMap.HALF)

	func _toggle_marker(w: Vector2) -> void:
		for i in UiMap.markers.size():
			var m: Dictionary = UiMap.markers[i]
			if Vector2(float(m.x), float(m.z)).distance_to(w) * zoom < 14.0:
				UiMap.markers.remove_at(i)
				marker_changed.emit(); queue_redraw()
				return
		if absf(w.x) <= UiMap.HALF and absf(w.y) <= UiMap.HALF and UiMap.markers.size() < 12:
			UiMap.markers.append({"x": snappedf(w.x, 0.1), "z": snappedf(w.y, 0.1)})
			marker_changed.emit(); queue_redraw()

	func _draw() -> void:
		draw_rect(Rect2(Vector2.ZERO, size), UiMap.C_VOID)
		var xf := Transform2D(yaw if turn else 0.0, Vector2(zoom, zoom), 0.0, size * 0.5) * Transform2D(0.0, -center)
		draw_set_transform_matrix(xf)
		var world_rect := Rect2(-UiMap.HALF, -UiMap.HALF, UiMap.HALF * 2.0, UiMap.HALF * 2.0)
		if UiMap.base != null:
			draw_texture_rect(UiMap.base, world_rect, false)
		else:
			draw_rect(world_rect, Color(0.2, 0.23, 0.17))
		var reach := (size.length() * 0.5) / zoom + 20.0
		for r in UiMap.roads:
			draw_line(r.a, r.b, Color(0, 0, 0, 0.35), float(r.w) + 2.0 / zoom, true)
		for r in UiMap.roads:
			draw_line(r.a, r.b, r.col, float(r.w), true)
		if zoom > 1.1:
			for q in UiMap.cars:
				if absf(q[0].x - center.x) < reach and absf(q[0].y - center.y) < reach:
					draw_colored_polygon(q, Color(0.3, 0.31, 0.32))
		var edge := 1.0 / zoom
		for i in UiMap.buildings.size():
			var e: Dictionary = UiMap.buildings[i]
			var rc: Rect2 = e.rect
			if absf(rc.position.x - center.x) > reach + 20.0 or absf(rc.position.y - center.y) > reach + 20.0:
				continue
			var cleared: bool = int(e.total) > 0 and int(e.done) >= int(e.total)
			draw_rect(rc, UiMap.C_DONE if cleared else UiMap.C_BUILD)
			if not cleared and int(e.done) > 0:
				draw_rect(Rect2(rc.position, Vector2(rc.size.x * float(e.done) / float(e.total), rc.size.y)), UiMap.C_DONE.lerp(UiMap.C_BUILD, 0.45))
			draw_rect(rc, UiTheme.ACCENT2 if i == hover_building else Color(0.08, 0.08, 0.07, 0.9), false, edge * (2.0 if i == hover_building else 1.0))
		draw_rect(world_rect, Color(0, 0, 0, 0.6), false, 2.0 / zoom)
		draw_set_transform_matrix(Transform2D.IDENTITY)

		# from here on, things that keep their size on screen
		if not mini:
			for e in UiMap.buildings:
				var cleared2: bool = int(e.total) > 0 and int(e.done) >= int(e.total)
				var c := to_screen(e.rect.get_center())
				if c.x < -60.0 or c.y < -20.0 or c.x > size.x + 60.0 or c.y > size.y + 20.0:
					continue
				if cleared2 and zoom > 0.9:
					UiKit.glyph(self, "check", Rect2(c - Vector2(5, 5), Vector2(10, 10)), Color(0.1, 0.1, 0.09))
				if String(e.name) != "" and zoom > 1.25:
					UiKit.text(self, "cap", Vector2(c.x - 90.0, c.y + e.rect.size.y * 0.5 * zoom + 3.0), String(e.name).to_upper(), 11, UiTheme.INK, HORIZONTAL_ALIGNMENT_CENTER, 180)
			if zoom < 1.25:
				var tc := to_screen(UiMap.town.get_center())
				UiKit.text(self, "head", Vector2(tc.x - 100.0, tc.y - 10.0), "THE TOWN", 15, Color(UiTheme.INK, 0.9), HORIZONTAL_ALIGNMENT_CENTER, 200)
			var lake: Dictionary = WorldData.lake
			var lc := to_screen(Vector2(float(lake.x), float(lake.z)))
			UiKit.text(self, "cap", Vector2(lc.x - 60.0, lc.y - 7.0), "LAKE", 12, Color(0.72, 0.82, 0.9, 0.9), HORIZONTAL_ALIGNMENT_CENTER, 120)
		# markers
		var hs := to_screen(UiMap.home)
		if mini:
			hs = _pin_to_edge(hs)
		_marker(hs, "house", UiTheme.ACCENT2)
		for m in UiMap.markers:
			var ms := to_screen(Vector2(float(m.x), float(m.z)))
			if mini:
				ms = _pin_to_edge(ms)
			_marker(ms, "pin", UiTheme.BLUE)
		for cpos in chasers:
			var cs := to_screen(cpos)
			draw_circle(cs, 2.6, Color(0, 0, 0, 0.6), true, -1.0, true)
			draw_circle(cs, 1.8, UiTheme.BAD, true, -1.0, true)
		# you
		var ps := to_screen(player)
		var rot := 0.0 if turn else -yaw
		draw_set_transform(ps, rot, Vector2.ONE)
		var s := 15.0 if not mini else 13.0
		UiKit.glyph(self, "arrow", Rect2(-s * 0.5 - 1.0, -s * 0.5 - 1.0, s + 2.0, s + 2.0), Color(0, 0, 0, 0.7))
		draw_set_transform(ps, rot, Vector2.ONE)
		UiKit.glyph(self, "arrow", Rect2(-s * 0.5 + 1.0, -s * 0.5 + 1.0, s - 2.0, s - 2.0), UiTheme.ACCENT2)
		draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
		if mini:
			# which way is north
			var nv := Vector2(0, -1).rotated(yaw if turn else 0.0)
			var np := size * 0.5 + nv * (minf(size.x, size.y) * 0.5 - 11.0)
			UiKit.text(self, "head", np - Vector2(20, 9), "N", 12, UiTheme.ACCENT2, HORIZONTAL_ALIGNMENT_CENTER, 40)
		else:
			_scale_bar()
			if hover_building >= 0 and hover_building < UiMap.buildings.size():
				var hb: Dictionary = UiMap.buildings[hover_building]
				var nm := String(hb.name) if String(hb.name) != "" else "House"
				var line := "%s  ·  %s" % [nm.to_upper(), ("NOTHING TO SEARCH" if int(hb.total) == 0 else ("SEARCHED %d OF %d" % [int(hb.done), int(hb.total)]))]
				var tw := UiKit.text_w("cap", line, 12) + 20.0
				var hp := to_screen(hb.rect.get_center()) - Vector2(tw * 0.5, hb.rect.size.y * 0.5 * zoom + 30.0)
				hp.x = clampf(hp.x, 6.0, size.x - tw - 6.0); hp.y = maxf(hp.y, 6.0)
				draw_rect(Rect2(hp, Vector2(tw, 22.0)), Color(0.03, 0.03, 0.03, 0.92))
				draw_rect(Rect2(hp, Vector2(tw, 22.0)), UiTheme.LINE2, false, 1.0)
				UiKit.text(self, "cap", hp + Vector2(10, 3), line, 12, UiTheme.INK, HORIZONTAL_ALIGNMENT_LEFT, -1, false)

	func _pin_to_edge(p: Vector2) -> Vector2:
		var m := 9.0
		return Vector2(clampf(p.x, m, size.x - m), clampf(p.y, m, size.y - m))

	func _marker(p: Vector2, glyph_: String, col: Color) -> void:
		if p.x < -10.0 or p.y < -10.0 or p.x > size.x + 10.0 or p.y > size.y + 10.0:
			return
		var s := 14.0 if not mini else 11.0
		draw_circle(p, s * 0.82, Color(0.03, 0.03, 0.03, 0.82), true, -1.0, true)
		UiKit.glyph(self, glyph_, Rect2(p - Vector2(s, s) * 0.5, Vector2(s, s)), col)

	func _scale_bar() -> void:
		var metres := 100.0
		if zoom > 3.0: metres = 20.0
		elif zoom > 1.4: metres = 50.0
		elif zoom < 0.7: metres = 200.0
		var w := metres * zoom
		var o := Vector2(16.0, size.y - 22.0)
		draw_line(o + Vector2(0, 1), o + Vector2(w, 1), Color(0, 0, 0, 0.6), 4.0)
		draw_line(o, o + Vector2(w, 0), UiTheme.INK, 2.0)
		draw_line(o + Vector2(0, -4), o + Vector2(0, 2), UiTheme.INK, 2.0)
		draw_line(o + Vector2(w, -4), o + Vector2(w, 2), UiTheme.INK, 2.0)
		UiKit.text(self, "cap", o + Vector2(w + 8.0, -9.0), "%d M" % int(metres), 11, UiTheme.INK)
		UiKit.text(self, "head", Vector2(size.x - 34.0, 10.0), "N", 15, UiTheme.ACCENT2, HORIZONTAL_ALIGNMENT_CENTER, 24)
		draw_line(Vector2(size.x - 22.0, 34.0), Vector2(size.x - 22.0, 50.0), UiTheme.ACCENT2, 2.0)
