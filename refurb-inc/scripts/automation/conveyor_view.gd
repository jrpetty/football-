class_name ConveyorView
extends StationBase
## A conveyor fixture: straight, curve, flexible bend, or a device section (goods-in scanner, sorter, vision
## grader, merger, splitter / balancer, stop gate, people's hatch). The host's Automation moves the items; this
## draws the moving belt, the speed-tier livery and the device effects, and handles the player side: put kit on
## the loading point, read what's on it, change sorter rules / splitter and gate modes ([E]), speed the run up
## (hold [E]).

const BELT_SHADER := preload("res://shaders/belt.gdshader")
const BELT_W := 0.58
const BELT_Y := 0.858
const SCANNER_MODEL := "res://assets/models/props/barcode_scanner/barcode_scanner.tscn"
const STRAIGHT_MODEL := "res://assets/models/automation/conveyor_straight/conveyor_straight.tscn"
## Livery per speed tier (index = tier): Standard keeps the plain yellow flanges.
const TIER_COLOURS := [Color(0.06, 0.06, 0.07), Color(0.16, 0.56, 1.0), Color(1.0, 0.3, 0.14)]
const TIER_ARROWS := ["", "▶▶", "▶▶▶"]

@export var fixture_type: String = "conveyor"

var auto: Dictionary = {}
var lb: Dictionary = {}
var dev := ""
var _belt: MeshInstance3D
var _offset := 0.0
var _speed := 0.0
var _loop: AudioStreamPlayer3D
var _seen := {}              # item id -> last s (for device triggers)
var _fx_t := 0.0
var _laser: MeshInstance3D
var _laser_mat: StandardMaterial3D
var _flash: OmniLight3D
var _screen: Label3D
var _paddle: Node3D
var _paddle_target := 0.0
var _signs := {}
var _tier := 0
var _chev_mat: ShaderMaterial
var _tier_vis: Node3D
var _plan_ms := -100000
var _plan := {}
var _ports: Array = []
var _lamp_mat: StandardMaterial3D         # gate / hatch stack light
var _lamp2_mat: StandardMaterial3D
var _gate_bar: Node3D
var _gate_y := 0.0
var _exit_mats := {}                      # splitter: "left"/"right" -> arrow material
var _mode_sign: Label3D
var _waiting := 0                         # hatch: kit waiting at the pick face

static var _idx_frame := -1
static var _idx := {}        # belt fid -> [Item]


func _ready() -> void:
	var f: Dictionary = Game.state.fixtures.get(station_id, {})
	auto = Automation.auto_for(f) if not f.is_empty() else Automation.def_of(fixture_type)
	lb = Automation.belt_local(auto)
	dev = str(auto.get("device", ""))
	_tier = int(auto.get("tier", 0))
	set_meta("bend_sig", str(f.get("bend", {})))
	if str(auto.get("origin", "")) == "in":
		# A flexible bend: build its frame, rollers and legs to this angle and radius.
		var m := get_node_or_null("Model")
		if m:
			m.queue_free()
			remove_child(m)
		var vis := Node3D.new()
		vis.name = "Bend"
		add_child(vis)
		ConveyorBendMesh.build(lb, vis)
		_bend_collision()
	if lb.kind == "line":
		var s := SnapSlot.new()
		s.name = "Load"
		s.slot_name = "load"
		s.accepts = PackedStringArray(["unit", "unit_large", "package", "package_large"])
		s.location_label = "People's hatch · send on" if dev == "hatch" else "Conveyor loading point"
		var p := Automation.path_point(lb, Automation.load_slot_s(auto))
		s.position = p.origin + Vector3(0, float(auto.get("height", 0.84)) + 0.013, 0)
		s.hint_size = Vector3(BELT_W - 0.04, 0.01, 0.72 if dev != "hatch" else 0.62)
		add_child(s)
	super._ready()
	_build_belt()
	_chev_mat = ConveyorBendMesh.chevrons(lb, self)
	if lb.kind == "line" and model:
		_far_lod()
	match dev:
		"scanner":
			_build_scanner()
		"sorter":
			_build_sorter()
		"grader":
			_build_grader()
		"merge":
			_build_merge()
		"splitter":
			_build_splitter()
		"gate":
			_build_gate()
		"hatch":
			_build_hatch()
	_apply_tier()
	_loop = Sfx.make_loop("conveyor_loop", self, -60.0)
	_loop.position = Vector3(0, 0.6, 0)


func _frame_lb() -> Dictionary:
	## The piece's whole frame (a splitter's path stops short of its front; its frame doesn't).
	if lb.kind == "line":
		return {"kind": "line", "length": float(auto.get("length", lb.length))}
	return lb


func _far_lod() -> void:
	## Beyond ~16 m the 77k-triangle model swaps for a ~1k-triangle built copy with the same outline.
	var far := Node3D.new()
	far.name = "FarLOD"
	add_child(far)
	ConveyorBendMesh.build(_frame_lb(), far)
	for gi: GeometryInstance3D in far.find_children("*", "GeometryInstance3D", true, false):
		gi.visibility_range_begin = 16.0
		gi.visibility_range_begin_margin = 1.0
		gi.visibility_range_fade_mode = GeometryInstance3D.VISIBILITY_RANGE_FADE_SELF
		gi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	for gi: GeometryInstance3D in model.find_children("*", "GeometryInstance3D", true, false):
		gi.visibility_range_end = 17.0
		gi.visibility_range_end_margin = 1.0
		gi.visibility_range_fade_mode = GeometryInstance3D.VISIBILITY_RANGE_FADE_SELF


func _bend_collision() -> void:
	var body := StaticBody3D.new()
	body.collision_layer = 1 | (1 << 3)
	add_child(body)
	var length: float = lb.length
	var n := maxi(int(ceil(length / 0.5)), 1)
	for i in n:
		var pp := Automation.path_point(lb, (i + 0.5) * length / n)
		var cs := CollisionShape3D.new()
		var bs := BoxShape3D.new()
		bs.size = Vector3(0.95, 0.9, length / n + 0.05)
		cs.shape = bs
		cs.transform = Transform3D(pp.basis, pp.origin + Vector3(0, 0.45, 0))
		body.add_child(cs)


func _build_merge() -> void:
	# Short transfer rollers bringing the side conveyor in, with arrows showing the way it joins.
	var side_in: Dictionary = lb.in[1]
	var straight: PackedScene = load(STRAIGHT_MODEL)
	var stub: Node3D = straight.instantiate()
	add_child(stub)
	var d: Vector3 = side_in.d
	stub.rotation.y = atan2(d.x, d.z)
	stub.scale = Vector3(0.75, 1.0, 0.14)
	stub.position = (side_in.p as Vector3) + d * 0.14
	var sign := Placeholders.text(self, "MERGE", Vector3(0, 1.1, -0.1), 34, Color(1, 1, 1), Vector3.ZERO, 0.0012)
	sign.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
	sign.shaded = false
	sign.outline_size = 8
	sign.outline_modulate = Color(0.05, 0.05, 0.08)


# ================================================================= speed tier

func _apply_tier() -> void:
	## Tint the flow chevrons, and give Fast / Express pieces their livery: coloured flange lines and side bands,
	## plus a speed plate on each side. Standard stays plain.
	if _tier_vis:
		_tier_vis.queue_free()
		_tier_vis = null
	var col: Color = TIER_COLOURS[clampi(_tier, 0, TIER_COLOURS.size() - 1)]
	if _chev_mat:
		_chev_mat.set_shader_parameter("tint", col)
		_chev_mat.set_shader_parameter("glow", 0.0 if _tier == 0 else 1.4 + 0.5 * _tier)
	if _tier == 0:
		return
	_tier_vis = Node3D.new()
	_tier_vis.name = "TierLivery"
	add_child(_tier_vis)
	var flb := _frame_lb()
	ConveyorBendMesh.tier_trim(flb, _tier_vis, col)
	var info := Automation.tier_info(_tier)
	var pp := Automation.path_point(flb, float(flb.length) * (0.3 if flb.kind == "line" else 0.5))
	for side in [-1.0, 1.0]:
		var plate := MeshInstance3D.new()
		var pm := BoxMesh.new()
		pm.size = Vector3(0.006, 0.07, 0.3)
		plate.mesh = pm
		plate.material_override = Mats.color(Color(0.04, 0.05, 0.07), 0.35)
		plate.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		plate.transform = Transform3D(pp.basis, pp.origin + pp.basis.x * side * (ConveyorBendMesh.FRAME_OUT + 0.016) + Vector3(0, 0.69, 0))
		_tier_vis.add_child(plate)
		var lbl := Placeholders.text(_tier_vis, "%s %s" % [str(info.get("name", "")).to_upper(), TIER_ARROWS[clampi(_tier, 0, 2)]], Vector3.ZERO, 30, col.lightened(0.35), Vector3.ZERO, 0.0009)
		lbl.shaded = false
		lbl.transform = Transform3D(pp.basis * Basis(Vector3.UP, side * PI * 0.5), pp.origin + pp.basis.x * side * (ConveyorBendMesh.FRAME_OUT + 0.021) + Vector3(0, 0.69, 0))


func _open_ports() -> Array:
	## Open conveyor ends (re-read at most twice a second while someone's looking at this piece).
	_refresh_hover_cache()
	return _ports


func _refresh_hover_cache() -> void:
	var now := Time.get_ticks_msec()
	if now - _plan_ms < 500:
		return
	_plan_ms = now
	_plan = Automation.upgrade_plan(Game.state, station_id)
	_ports = AutoLayout.open_ports()


func _upgrade_hold() -> Dictionary:
	## Hold [E] on any belt piece: speed up its whole run one tier (or just this piece if the run's too dear).
	_refresh_hover_cache()
	if _plan.is_empty() or _plan.get("max", false):
		return {}
	var n: int = _plan.ids.size()
	var label := "Speed up to %s (%.1f m/s)" % [_plan.name, float(_plan.speed)]
	if _plan.short:
		return {"label": label, "blocked": "Speeding up to %s costs %s a piece" % [_plan.name, GameState.format_money(int(_plan.step))]}
	var sub := ("Whole run, %d pieces" % n if n > 1 else "This piece") + " · " + GameState.format_money(int(_plan.cost))
	if int(_plan.run) > n:
		sub += " (the whole run of %d is more than you can spend)" % int(_plan.run)
	return {"secs": 0.9, "label": label, "sub": sub, "action": "upgrade"}


func hold_complete(_player: Node, action: String) -> void:
	if action == "upgrade":
		Game.request("upgrade_belt", {"id": station_id})
		_plan_ms = -100000
		Sfx.play_at("hydraulic", global_position + Vector3(0, 0.9, 0), -10.0)


# ================================================================= belt

func _build_belt() -> void:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var length: float = lb.length
	var n := 1 if lb.kind == "line" else 24
	for i in n:
		var s0 := length * i / n
		var s1 := length * (i + 1) / n
		var a := Automation.path_point(lb, s0)
		var b := Automation.path_point(lb, s1)
		var al := a.origin - a.basis.x * BELT_W * 0.5
		var ar := a.origin + a.basis.x * BELT_W * 0.5
		var bl := b.origin - b.basis.x * BELT_W * 0.5
		var br := b.origin + b.basis.x * BELT_W * 0.5
		for v in [[al, 0.0, s0], [br, 1.0, s1], [bl, 0.0, s1], [al, 0.0, s0], [ar, 1.0, s0], [br, 1.0, s1]]:
			st.set_normal(Vector3.UP)
			st.set_uv(Vector2(v[1], v[2]))
			st.add_vertex((v[0] as Vector3) + Vector3(0, BELT_Y, 0))
	st.generate_tangents()
	_belt = MeshInstance3D.new()
	_belt.mesh = st.commit()
	var mat := ShaderMaterial.new()
	mat.shader = BELT_SHADER
	_belt.material_override = mat
	_belt.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(_belt)


static func items_by_belt() -> Dictionary:
	## One pass over the items per frame, shared by every conveyor.
	var f := Engine.get_process_frames()
	if f != _idx_frame:
		_idx_frame = f
		_idx = {}
		for it: Item in Game.state.items.values():
			if it.loc.get("t") == "belt":
				var k := str(it.loc.get("belt", ""))
				if not _idx.has(k):
					_idx[k] = []
				_idx[k].append(it)
	return _idx


func _process(delta: float) -> void:
	var list: Array = items_by_belt().get(station_id, [])
	var moving := false
	for it: Item in list:
		if float(it.loc.get("v", 0.0)) > 0.0:
			moving = true
			break
	var top := float(auto.get("speed", 0.6))
	var want := top if moving else 0.0
	var was := _speed
	_speed = move_toward(_speed, want, delta * 1.8 * maxf(top / 0.6, 1.0))
	if _speed > 0.0 or was > 0.0:
		_offset = fmod(_offset + _speed * delta, 1000.0)
		_belt.set_instance_shader_parameter("belt_offset", _offset)
		_belt.set_instance_shader_parameter("belt_run", _speed / maxf(top, 0.01))
		if _tier > 0 and _chev_mat:
			_chev_mat.set_shader_parameter("chase", _offset)
	var vol := -60.0 if _speed <= 0.01 else lerpf(-34.0, -22.0 + 2.0 * _tier, _speed / maxf(top, 0.01))
	_loop.volume_db = vol
	_loop.pitch_scale = 1.0 + 0.12 * _tier
	if vol > -55.0 and not _loop.playing:
		_loop.play()
	elif vol <= -55.0 and _loop.playing:
		_loop.stop()
	if dev != "":
		_device_tick(delta, list)


# ================================================================= devices

func _device_tick(delta: float, list: Array) -> void:
	var now := Game.state.belt_now(Game.is_host())
	var mid: float = float(lb.length) * 0.5
	var seen := {}
	var waiting := 0
	for it: Item in list:
		var s := float(it.loc.get("s", 0.0)) + float(it.loc.get("v", 0.0)) * (now - float(it.loc.get("at", 0.0)))
		var before := float(_seen.get(it.id, -1.0))
		seen[it.id] = s
		if before >= 0.0 and before < mid and s >= mid:
			_trigger(it)
		if dev == "sorter":
			_aim_paddle(it, s)
		elif dev == "hatch" and float(it.loc.get("v", 0.0)) == 0.0 and not it.loc.get("sent", false):
			waiting += 1
	_seen = seen
	if _fx_t > 0.0:
		_fx_t -= delta
	var blink := fmod(Time.get_ticks_msec() / 1000.0, 0.8) < 0.45
	match dev:
		"scanner":
			var on := not list.is_empty()
			_laser.visible = on
			if on:
				_laser_mat.emission_energy_multiplier = 3.0 + sin(Time.get_ticks_msec() * 0.05) * 1.2
			_flash.light_energy = maxf(_fx_t, 0.0) * 4.0
		"grader":
			_flash.light_energy = maxf(_fx_t, 0.0) * 30.0
		"sorter":
			if list.is_empty():
				_paddle_target = 0.0
			_paddle.rotation.y = lerp_angle(_paddle.rotation.y, _paddle_target, clampf(delta * 9.0, 0.0, 1.0))
		"splitter":
			_paddle.rotation.y = lerp_angle(_paddle.rotation.y, _paddle_target, clampf(delta * 9.0, 0.0, 1.0))
		"gate":
			var hold := Automation.belt_mode(Game.state, station_id, "gate") == "hold"
			var y_to := BELT_Y + 0.05 if hold else BELT_Y + 0.5
			_gate_y = move_toward(_gate_y, y_to, delta * 1.6)
			_gate_bar.position.y = _gate_y
			_set_lamp(_lamp_mat, Color(1.0, 0.15, 0.1), hold)
			_set_lamp(_lamp2_mat, Color(0.2, 1.0, 0.35), not hold)
		"hatch":
			if waiting != _waiting:
				if waiting > _waiting:
					Sfx.play_at("rack_chirp", global_position + Vector3(0, 1.5, 0), -12.0, 0.0)
				_waiting = waiting
			_set_lamp(_lamp_mat, Color(1.0, 0.62, 0.08), waiting > 0 and blink)
			_set_lamp(_lamp2_mat, Color(0.2, 1.0, 0.35), waiting == 0)


func _set_lamp(m: StandardMaterial3D, col: Color, on: bool) -> void:
	if m == null:
		return
	m.emission = col
	m.albedo_color = col.darkened(0.2) if on else col.darkened(0.75)
	m.emission_energy_multiplier = 3.0 if on else 0.0


func _trigger(it: Item) -> void:
	var pos := global_position + Vector3(0, 1.0, 0)
	match dev:
		"scanner":
			_fx_t = 0.35
			Sfx.play_at("scan_beep", pos, -6.0, 0.0)
			if it.unit:
				_screen.text = "BOOKED IN\n%s\n#%d" % [it.unit.model().get("name", "Unit"), it.id]
				_screen.modulate = Color(0.35, 1.0, 0.5)
		"grader":
			_fx_t = 0.25
			Sfx.play_at("grader_flash", pos, -8.0, 0.0)
			if it.unit:
				var u := it.unit
				var g := u.grade if u.grade != "" else Valuation.grade_for(u)
				if u.imaged and not u.has_open_faults():
					_screen.text = "GRADE %s\n%s\nLISTED %s" % [g, u.model().get("name", ""), GameState.format_money(Valuation.market_value(u, g))]
					_screen.modulate = Color(0.45, 0.85, 1.0)
				else:
					_screen.text = "NOT READY\n%s\n%s" % [u.model().get("name", ""), "needs imaging" if not u.imaged else "fault open"]
					_screen.modulate = Color(1.0, 0.7, 0.3)


func _build_scanner() -> void:
	var steel := Mats.painted_metal(Color(0.16, 0.3, 0.52))
	var h := 1.45
	for sx in [-1.0, 1.0]:
		var post := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = Vector3(0.07, h, 0.12)
		post.mesh = bm
		post.material_override = steel
		post.position = Vector3(0.58 * sx, h * 0.5, 0)
		add_child(post)
		if ResourceLoader.exists(SCANNER_MODEL):
			var sc: Node3D = load(SCANNER_MODEL).instantiate()
			add_child(sc)
			var fp: Vector3 = sc.get_meta("footprint", Vector3(0.08, 0.18, 0.08))
			sc.scale = Vector3.ONE * (0.2 / maxf(fp.y, 0.01))
			sc.position = Vector3(0.53 * sx, 1.08, 0.0)
			sc.rotation_degrees = Vector3(0, -90.0 * sx, 35.0 * sx)
	var beam := MeshInstance3D.new()
	var bb := BoxMesh.new()
	bb.size = Vector3(1.26, 0.16, 0.22)
	beam.mesh = bb
	beam.material_override = steel
	beam.position = Vector3(0, h + 0.08, 0)
	add_child(beam)
	# Status screen on the beam.
	var panel := MeshInstance3D.new()
	var pb := BoxMesh.new()
	pb.size = Vector3(0.42, 0.13, 0.01)
	panel.mesh = pb
	panel.material_override = Mats.color(Color(0.02, 0.03, 0.04), 0.3)
	panel.position = Vector3(0, h + 0.08, 0.116)
	add_child(panel)
	_screen = Placeholders.text(self, "GOODS IN\nready", Vector3(0, h + 0.08, 0.123), 34, Color(0.6, 0.8, 1.0), Vector3.ZERO, 0.0011)
	_screen.shaded = false
	_screen.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	Placeholders.text(self, "GOODS-IN SCANNER", Vector3(0, h + 0.08, -0.112), 30, Color(0.95, 0.95, 0.95), Vector3(0, 180, 0), 0.0012)
	# Red scan line across the belt.
	_laser = MeshInstance3D.new()
	var lm := BoxMesh.new()
	lm.size = Vector3(0.6, 0.004, 0.006)
	_laser.mesh = lm
	_laser_mat = Mats.emissive(Color(1.0, 0.08, 0.05), 3.0)
	_laser.material_override = _laser_mat
	_laser.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_laser.position = Vector3(0, BELT_Y + 0.004, 0)
	add_child(_laser)
	_flash = OmniLight3D.new()
	_flash.light_color = Color(0.4, 1.0, 0.5)
	_flash.omni_range = 1.4
	_flash.light_energy = 0.0
	_flash.shadow_enabled = false
	_flash.position = Vector3(0, 1.2, 0)
	add_child(_flash)


func _build_grader() -> void:
	var frame := Mats.painted_metal(Color(0.82, 0.84, 0.86))
	var accent := Mats.painted_metal(Color(0.12, 0.42, 0.85))
	var length: float = lb.length
	var tl := length - 0.3
	var top := 1.62
	# Corner posts and roof: a light frame you can see into through perspex sides.
	for sx in [-1.0, 1.0]:
		for sz in [-1.0, 1.0]:
			_box(Vector3(0.05, top - BELT_Y + 0.05, 0.05), Vector3(0.58 * sx, (top + BELT_Y) * 0.5, tl * 0.5 * sz), frame)
		var glass := MeshInstance3D.new()
		var gm := BoxMesh.new()
		gm.size = Vector3(0.01, top - BELT_Y - 0.1, tl - 0.05)
		glass.mesh = gm
		glass.material_override = Mats.glass(Color(0.75, 0.85, 0.95, 0.18))
		glass.position = Vector3(0.58 * sx, (top + BELT_Y) * 0.5 + 0.03, 0)
		glass.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		add_child(glass)
		_box(Vector3(0.012, 0.06, tl), Vector3(0.587 * sx, top - 0.12, 0), accent)
		var lbl := Placeholders.text(self, "VISION GRADER", Vector3(0.594 * sx, top - 0.12, 0), 36, Color(1, 1, 1), Vector3(0, 90.0 * sx, 0), 0.0012)
		lbl.shaded = false
	_box(Vector3(1.22, 0.08, tl + 0.06), Vector3(0, top + 0.04, 0), frame)
	# Ring lights at both ends of the tunnel and a camera looking down.
	for z in [-tl * 0.35, tl * 0.35]:
		for strip in [[Vector3(0.012, 0.6, 0.04), Vector3(-0.55, 1.24, z)], [Vector3(0.012, 0.6, 0.04), Vector3(0.55, 1.24, z)], [Vector3(1.06, 0.012, 0.04), Vector3(0, top - 0.02, z)]]:
			var l := _box(strip[0], strip[1], Mats.emissive(Color(0.9, 0.95, 1.0), 2.5))
			l.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	var cam := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = 0.06
	cm.bottom_radius = 0.075
	cm.height = 0.14
	cam.mesh = cm
	cam.material_override = Mats.color(Color(0.05, 0.05, 0.06), 0.25, 0.5)
	cam.position = Vector3(0, top - 0.08, 0)
	add_child(cam)
	_flash = OmniLight3D.new()
	_flash.light_color = Color(0.85, 0.92, 1.0)
	_flash.omni_range = 1.6
	_flash.light_energy = 0.0
	_flash.shadow_enabled = false
	_flash.position = Vector3(0, 1.3, 0)
	add_child(_flash)
	# Result display on the roof, facing back up the line (the way kit arrives).
	var disp_pos := Vector3(0, top + 0.24, -tl * 0.5 + 0.04)
	_box(Vector3(0.5, 0.3, 0.05), disp_pos, Mats.color(Color(0.08, 0.08, 0.09), 0.4, 0.3))
	_box(Vector3(0.04, 0.2, 0.04), disp_pos + Vector3(0, -0.2, 0.0), frame)
	var scr := _box(Vector3(0.46, 0.26, 0.004), disp_pos + Vector3(0, 0, -0.027), Mats.emissive(Color(0.02, 0.05, 0.1), 1.0))
	scr.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_screen = Placeholders.text(self, "VISION GRADER\nready", disp_pos + Vector3(0, 0, -0.031), 30, Color(0.45, 0.85, 1.0), Vector3(0, 180, 0), 0.0009)
	_screen.shaded = false
	_screen.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER


func _box(size: Vector3, pos: Vector3, mat: Material, parent: Node3D = null) -> MeshInstance3D:
	var m := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	m.mesh = bm
	m.material_override = mat
	m.position = pos
	(parent if parent else self).add_child(m)
	return m


func _hazard_bar(size: Vector3, pos: Vector3, parent: Node3D = null, stripes: int = 8) -> void:
	## Yellow and black striped bar (end stops, gate blades, pick-face stops).
	var yellow := Mats.color(Color(1.0, 0.78, 0.06), 0.45, 0.2)
	var black := Mats.color(Color(0.05, 0.05, 0.06), 0.6)
	var w := size.x / stripes
	for i in stripes:
		_box(Vector3(w, size.y, size.z), pos + Vector3(-size.x * 0.5 + w * (i + 0.5), 0, 0), yellow if i % 2 == 0 else black, parent)


func _stack_light(pos: Vector3, parent: Node3D = null) -> void:
	## Two-lamp stack light (lamp 1 on top, lamp 2 below) on a short black stem.
	var dark := Mats.color(Color(0.08, 0.08, 0.09), 0.5, 0.3)
	_box(Vector3(0.04, 0.06, 0.04), pos + Vector3(0, 0.03, 0), dark, parent)
	_lamp_mat = StandardMaterial3D.new()
	_lamp_mat.emission_enabled = true
	_lamp2_mat = StandardMaterial3D.new()
	_lamp2_mat.emission_enabled = true
	for k in 2:
		var lamp := MeshInstance3D.new()
		var cm := CylinderMesh.new()
		cm.top_radius = 0.035
		cm.bottom_radius = 0.035
		cm.height = 0.07
		cm.radial_segments = 16
		lamp.mesh = cm
		lamp.material_override = _lamp_mat if k == 1 else _lamp2_mat
		lamp.position = pos + Vector3(0, 0.1 + k * 0.075, 0)
		lamp.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		(parent if parent else self).add_child(lamp)
	_box(Vector3(0.075, 0.015, 0.075), pos + Vector3(0, 0.25, 0), dark, parent)


func _build_sorter() -> void:
	var steel := Mats.painted_metal(Color(0.18, 0.2, 0.23))
	var length: float = lb.length
	var z_exit := length * 0.5 - 0.3
	# Transfer rollers out to each side exit (Jon's conveyor, short and turned 90°).
	var straight: PackedScene = load(STRAIGHT_MODEL)
	for sx in [-1.0, 1.0]:
		var stub: Node3D = straight.instantiate()
		add_child(stub)
		stub.rotation_degrees.y = 90.0
		stub.scale = Vector3(0.75, 1.0, 0.14)
		stub.position = Vector3(0.64 * sx, 0, z_exit)
	# Diverter paddle (pivots at the upstream edge of the exit zone).
	_paddle = Node3D.new()
	_paddle.position = Vector3(0, BELT_Y + 0.06, z_exit - 0.4)
	add_child(_paddle)
	var pad := MeshInstance3D.new()
	var pm := BoxMesh.new()
	pm.size = Vector3(0.03, 0.1, 0.72)
	pad.mesh = pm
	pad.position = Vector3(0, 0, 0.36)
	pad.material_override = Mats.painted_metal(Color(0.95, 0.55, 0.1))
	_paddle.add_child(pad)
	var hub := MeshInstance3D.new()
	var hm := CylinderMesh.new()
	hm.top_radius = 0.035
	hm.bottom_radius = 0.035
	hm.height = 0.16
	hub.mesh = hm
	hub.material_override = steel
	_paddle.add_child(hub)
	# Exit signs, one per output: what goes that way. Look at one and press E to change it.
	for o in ["left", "right", "out"]:
		var lbl := Placeholders.text(self, "", Vector3.ZERO, 40, Color(1, 1, 1), Vector3.ZERO, 0.0013)
		lbl.shaded = false
		lbl.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		lbl.outline_size = 10
		lbl.outline_modulate = Color(0.05, 0.05, 0.08)
		lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		match o:
			"left":
				lbl.position = Vector3(-0.9, 1.25, z_exit)
			"right":
				lbl.position = Vector3(0.9, 1.25, z_exit)
			"out":
				lbl.position = Vector3(0, 1.25, length * 0.5 + 0.05)
		_signs[o] = lbl
	_refresh_signs()


func _refresh_signs() -> void:
	if _signs.is_empty() or dev != "sorter":
		return
	var rules: Dictionary = Game.state.stations.get(station_id, {}).get("rules", {"left": "faulty", "right": "passed", "out": "any"})
	# Billboard signs sit right over their exits, so no side arrows (they'd point the wrong way from half the floor).
	var arrow := {"left": "", "right": "", "out": "▲ "}
	var tail := {"left": "", "right": "", "out": ""}
	for o in _signs:
		var r := str(rules.get(o, "any"))
		(_signs[o] as Label3D).text = "%s%s%s" % [arrow[o], RULE_NAMES.get(r, r.to_upper()), tail[o]]
		(_signs[o] as Label3D).modulate = RULE_COLOURS.get(r, Color.WHITE)


const RULE_NAMES := {"any": "EVERYTHING ELSE", "untested": "UNTESTED", "faulty": "FAULTY", "passed": "PASSED TEST", "imaged": "IMAGED", "graded": "GRADED STOCK", "sold": "SOLD", "large": "BIG KIT", "none": "CLOSED"}
const RULE_COLOURS := {"faulty": Color(1.0, 0.45, 0.4), "passed": Color(0.5, 1.0, 0.6), "imaged": Color(0.5, 0.8, 1.0), "graded": Color(0.85, 0.7, 1.0), "sold": Color(1.0, 0.85, 0.3), "none": Color(0.6, 0.6, 0.6)}


func _aim_paddle(it: Item, s: float) -> void:
	# Swing the paddle towards the side an item is heading for as it reaches the exit zone.
	var z_exit: float = float(lb.length) * 0.5 - 0.3
	var local_z := -float(lb.length) * 0.5 + s
	if local_z < z_exit - 0.7:
		return
	var rules: Dictionary = Game.state.stations.get(station_id, {}).get("rules", {"left": "faulty", "right": "passed", "out": "any"})
	var target := 0.0
	for o in ["left", "right", "out"]:
		if Automation.rule_matches(str(rules.get(o, "any")), it):
			target = {"left": deg_to_rad(-38.0), "right": deg_to_rad(38.0), "out": 0.0}[o]
			break
	if absf(target - _paddle_target) > 0.01 and target != 0.0:
		Sfx.play_at("sorter_push", global_position + Vector3(0, 1, 0), -10.0)
	_paddle_target = target


# ================================================================= splitter / balancer

func _build_splitter() -> void:
	## One line in, two out: transfer rollers to each side, a flap that swings to the side the next item takes,
	## arrows on each exit (the live one lit), a buffer stop across the front, and the mode on a sign.
	var steel := Mats.painted_metal(Color(0.18, 0.2, 0.23))
	var module: float = float(auto.get("length", 1.4))
	var z_exit: float = float(lb.z0) + float(lb.length)
	var straight: PackedScene = load(STRAIGHT_MODEL)
	for sx in [-1.0, 1.0]:
		var side := "right" if sx < 0.0 else "left"      # named from the kit's point of view (+X is its left)
		var stub: Node3D = straight.instantiate()
		add_child(stub)
		stub.rotation_degrees.y = 90.0
		stub.scale = Vector3(0.75, 1.0, 0.14)
		stub.position = Vector3(0.64 * sx, 0, z_exit)
		# Exit arrows on the stub's flanges, pointing out along it.
		var m := StandardMaterial3D.new()
		m.albedo_color = Color(0.06, 0.06, 0.07)
		m.emission_enabled = true
		m.emission = Color(0.3, 1.0, 0.45)
		m.emission_energy_multiplier = 0.0
		_exit_mats[side] = m
		var arrows := Node3D.new()
		arrows.position = Vector3(0.64 * sx, 0, z_exit)
		arrows.rotation.y = sx * PI * 0.5
		add_child(arrows)
		for fl in [-0.285, 0.285]:
			for k in 2:
				var a := MeshInstance3D.new()
				a.mesh = ConveyorBendMesh._arrow if ConveyorBendMesh._arrow else ConveyorBendMesh._arrow_mesh()
				a.material_override = m
				a.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
				a.position = Vector3(fl, ConveyorBendMesh.FRAME_HI + ConveyorBendMesh.CAP + 0.0015, -0.07 + k * 0.1)
				arrows.add_child(a)
		var lbl := Placeholders.text(self, "", Vector3(0.95 * sx, 1.22, z_exit), 40, Color(1, 1, 1), Vector3.ZERO, 0.0013)
		lbl.shaded = false
		lbl.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		lbl.outline_size = 10
		lbl.outline_modulate = Color(0.05, 0.05, 0.08)
		lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		_signs[side] = lbl
	# Flap (pivots upstream, swings its tip towards the side the next item takes).
	_paddle = Node3D.new()
	_paddle.position = Vector3(0, BELT_Y + 0.06, z_exit - 0.42)
	add_child(_paddle)
	var pad := MeshInstance3D.new()
	var pm := BoxMesh.new()
	pm.size = Vector3(0.03, 0.1, 0.58)
	pad.mesh = pm
	pad.position = Vector3(0, 0, 0.29)
	pad.material_override = Mats.painted_metal(Color(0.12, 0.42, 0.85))
	_paddle.add_child(pad)
	var hub := MeshInstance3D.new()
	var hm := CylinderMesh.new()
	hm.top_radius = 0.035
	hm.bottom_radius = 0.035
	hm.height = 0.16
	hub.mesh = hm
	hub.material_override = steel
	_paddle.add_child(hub)
	# Buffer stop across the front: nothing goes straight on.
	_hazard_bar(Vector3(0.64, 0.07, 0.04), Vector3(0, BELT_Y + 0.04, module * 0.5 - 0.04))
	_mode_sign = Placeholders.text(self, "", Vector3(0, 1.42, z_exit - 0.2), 36, Color(1, 1, 1), Vector3.ZERO, 0.0012)
	_mode_sign.shaded = false
	_mode_sign.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
	_mode_sign.outline_size = 10
	_mode_sign.outline_modulate = Color(0.04, 0.08, 0.18)
	_mode_sign.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_refresh_splitter()


func _refresh_splitter() -> void:
	if dev != "splitter" or _paddle == null:
		return
	var st: Dictionary = Game.state.stations.get(station_id, {})
	var overflow := Automation.belt_mode(Game.state, station_id, "splitter") == "overflow"
	var aim := str(st.get("aim", st.get("flip", "left")))
	_mode_sign.text = "BALANCER\n%s" % ("OVERFLOW · LEFT FIRST" if overflow else "ALTERNATE ⇄")
	_mode_sign.modulate = Color(1.0, 0.85, 0.4) if overflow else Color(0.55, 0.85, 1.0)
	for side in ["left", "right"]:
		var live: bool = side == aim
		var lbl: Label3D = _signs.get(side)
		if lbl:
			lbl.text = ("LEFT" if side == "left" else "RIGHT") + ("\n▼ NEXT" if live else "")
			lbl.modulate = Color(0.45, 1.0, 0.6) if live else Color(0.62, 0.65, 0.7)
		var m: StandardMaterial3D = _exit_mats.get(side)
		if m:
			m.emission_energy_multiplier = 2.2 if live else 0.0
			m.albedo_color = Color(0.2, 0.75, 0.35) if live else Color(0.06, 0.06, 0.07)
	var target := deg_to_rad(34.0) if aim == "left" else deg_to_rad(-34.0)
	if absf(target - _paddle_target) > 0.01 and is_inside_tree():
		Sfx.play_at("sorter_push", global_position + Vector3(0, 1, 0), -12.0)
	_paddle_target = target


# ================================================================= stop gate

func _build_gate() -> void:
	## Two posts at the out end, a beam with an air cylinder, and a striped blade that drops across the belt to
	## hold the line. Red/green stack light on the left post.
	var steel := Mats.painted_metal(Color(0.2, 0.22, 0.26))
	var z_g: float = float(lb.length) * 0.5 - 0.06
	var top := 1.48
	for sx in [-1.0, 1.0]:
		_box(Vector3(0.06, top - 0.74, 0.06), Vector3(0.5 * sx, (top + 0.74) * 0.5, z_g), steel)
		_box(Vector3(0.1, 0.04, 0.12), Vector3(0.5 * sx, 0.76, z_g), steel)
	_box(Vector3(1.08, 0.09, 0.1), Vector3(0, top, z_g), steel)
	_box(Vector3(1.08, 0.012, 0.102), Vector3(0, top + 0.05, z_g), Mats.color(Color(1.0, 0.78, 0.06), 0.45, 0.2))
	var cyl := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = 0.035
	cm.bottom_radius = 0.035
	cm.height = 0.3
	cyl.mesh = cm
	cyl.material_override = Mats.color(Color(0.75, 0.77, 0.8), 0.3, 0.8)
	cyl.position = Vector3(0, top - 0.2, z_g)
	add_child(cyl)
	_gate_bar = Node3D.new()
	add_child(_gate_bar)
	_gate_y = BELT_Y + 0.5
	_gate_bar.position = Vector3(0, _gate_y, z_g)
	_hazard_bar(Vector3(0.9, 0.07, 0.035), Vector3.ZERO, _gate_bar, 10)
	var rod := MeshInstance3D.new()
	var rm := CylinderMesh.new()
	rm.top_radius = 0.012
	rm.bottom_radius = 0.012
	rm.height = 0.7
	rod.mesh = rm
	rod.material_override = Mats.color(Color(0.85, 0.86, 0.88), 0.2, 0.9)
	rod.position = Vector3(0, 0.38, 0)
	_gate_bar.add_child(rod)
	_stack_light(Vector3(-0.5, top + 0.06, z_g))
	for face in [1.0, -1.0]:
		var lbl := Placeholders.text(self, "STOP GATE", Vector3(0, top, z_g + 0.052 * face), 30, Color(1, 1, 1), Vector3(0, 0 if face > 0 else 180, 0), 0.0011)
		lbl.shaded = false


# ================================================================= people's hatch

func _build_hatch() -> void:
	## A gantry over the middle of a 1.6 m section: kit coming in stops against the pick-face blade for a person
	## to take; anything put on the far half rides on. Amber light while kit is waiting for someone.
	var steel := Mats.painted_metal(Color(0.12, 0.42, 0.85))
	var dark := Mats.painted_metal(Color(0.16, 0.17, 0.2))
	var top := 1.86
	for sx in [-1.0, 1.0]:
		_box(Vector3(0.07, top - 0.02, 0.07), Vector3(0.565 * sx, top * 0.5, 0), steel)
		_box(Vector3(0.14, 0.02, 0.14), Vector3(0.565 * sx, 0.01, 0), dark)
	_box(Vector3(1.24, 0.16, 0.1), Vector3(0, top, 0), steel)
	for face in [1.0, -1.0]:
		var lbl := Placeholders.text(self, "PEOPLE'S HATCH", Vector3(0, top, 0.052 * face), 34, Color(1, 1, 1), Vector3(0, 0 if face > 0 else 180, 0), 0.0012)
		lbl.shaded = false
	# Pick-face blade between the rails.
	_hazard_bar(Vector3(0.6, 0.06, 0.025), Vector3(0, BELT_Y + 0.03, 0.0125))
	# What each half is for.
	var take := Placeholders.text(self, "TAKE ▼", Vector3(0, 1.32, -0.45), 46, Color(1.0, 0.72, 0.25), Vector3.ZERO, 0.0015)
	var send := Placeholders.text(self, "SEND ON ▼", Vector3(0, 1.32, 0.45), 46, Color(0.45, 1.0, 0.6), Vector3.ZERO, 0.0015)
	for lbl: Label3D in [take, send]:
		lbl.shaded = false
		lbl.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		lbl.outline_size = 10
		lbl.outline_modulate = Color(0.05, 0.05, 0.08)
	_stack_light(Vector3(0.565, top + 0.08, 0))


func _on_state_changed(kind: String, id: Variant) -> void:
	super._on_state_changed(kind, id)
	if str(id) != station_id:
		return
	if kind == "station":
		match dev:
			"sorter":
				_refresh_signs()
			"splitter":
				_refresh_splitter()
	elif kind == "fixture":
		_plan_ms = -100000
		var f: Dictionary = Game.state.fixtures.get(station_id, {})
		if f.is_empty() or Automation.tier_of(f) == _tier:
			return
		auto = Automation.auto_for(f)
		_tier = Automation.tier_of(f)
		_apply_tier()
		Sfx.play_at("repair_done", global_position + Vector3(0, 0.9, 0), -8.0)


# ================================================================= interaction

func hover_info(player: Node) -> Dictionary:
	var f: Dictionary = Game.state.fixtures.get(station_id, {})
	var def := Content.fixture(fixture_type)
	var list: Array = items_by_belt().get(station_id, [])
	var lines: Array = []
	lines.append("%d on the belt" % list.size() if not list.is_empty() else "Empty")
	var load_it := slot_item("load")
	if load_it:
		lines.append("Waiting to load: %s" % load_it.display_name())
	var prompt := "[F] Move"
	match dev:
		"scanner":
			lines.append("Units through here are booked into stock automatically.")
		"grader":
			lines.append("Grades imaged units and lists them on Flogit as they pass.")
		"sorter":
			var exit := _exit_at(player)
			var rules: Dictionary = Game.state.stations.get(station_id, {}).get("rules", {"left": "faulty", "right": "passed", "out": "any"})
			# The sorter's internal exit names are mirrored (its "left" is the kit's right); say it the kit's way.
			lines.append("Left: %s · Right: %s · Straight on: %s" % [RULE_NAMES.get(rules.get("right", "passed")), RULE_NAMES.get(rules.get("left", "faulty")), RULE_NAMES.get(rules.get("out", "any"))])
			prompt = "[E] Change the %s exit  ·  [F] Move" % {"left": "right-hand", "right": "left-hand", "out": "straight-on"}[exit]
		"splitter":
			var overflow := Automation.belt_mode(Game.state, station_id, "splitter") == "overflow"
			lines.append("Overflow: fills the left line, uses the right only when the left backs up." if overflow else "Alternate: shares kit left, right, left… (skips a side that's full).")
			var open: Array = []
			for prt in _open_ports():
				if prt.fid == station_id and not prt["in"]:
					open.append(prt.name)
			if open.size() == 2:
				lines.append("⚠ Nothing joined to either side yet — put a conveyor on each exit.")
			elif open.size() == 1:
				lines.append("⚠ Nothing joined on the %s — everything goes the other way." % open[0])
			prompt = "[E] Switch to %s  ·  [F] Move" % ("alternate" if overflow else "overflow")
		"gate":
			var hold := Automation.belt_mode(Game.state, station_id, "gate") == "hold"
			lines.append("HOLDING — kit waits behind the gate." if hold else "OPEN — kit flows through.")
			prompt = "[E] %s  ·  [F] Move" % ("Open the gate" if hold else "Hold the line")
		"hatch":
			var waiting := 0
			for it: Item in list:
				if float(it.loc.get("v", 0.0)) == 0.0 and not it.loc.get("sent", false):
					waiting += 1
			lines.append("%d waiting for a person — take kit off the near half." % waiting if waiting > 0 else "Kit coming down the line stops here for a person to take.")
			lines.append("Put kit on the far half (past the stop) to send it on down the line.")
	if lb.kind == "line" and dev not in ["hatch", "splitter"]:
		lines.append("Put kit on the near end to send it along.")
	for prt in _open_ports():
		if prt.fid == station_id and not prt["in"] and prt.name == "out":
			if AutoLayout.arm_covering(prt.p) == "":
				lines.append("⚠ Open end with no robot arm in reach — kit will wait here.")
			else:
				lines.append("Open end: a robot arm picks up here.")
	var info := Automation.tier_info(_tier)
	lines.append("Speed: %s · %.1f m/s" % [info.get("name", "Standard"), float(auto.get("speed", 0.6))])
	var out := {"title": str(f.get("label", def.get("name", "Conveyor"))), "lines": lines, "prompt": prompt}
	var hold := _upgrade_hold()
	if not hold.is_empty():
		out["hold"] = hold
	return out


func _s_of(it: Item) -> float:
	var now := Game.state.belt_now(Game.is_host())
	return float(it.loc.get("s", 0.0)) + float(it.loc.get("v", 0.0)) * (now - float(it.loc.get("at", 0.0)))


func _exit_at(player: Node) -> String:
	var hit: Vector3 = player.get("_hover_hit") if player and player.get("_hover_hit") != null else global_position
	var lp := to_local(hit)
	if lp.x < -0.3:
		return "left"
	if lp.x > 0.3:
		return "right"
	return "out"


func interact(player: Node) -> void:
	match dev:
		"sorter":
			var exit := _exit_at(player)
			var rules: Dictionary = Game.state.stations.get(station_id, {}).get("rules", {"left": "faulty", "right": "passed", "out": "any"})
			var cur := str(rules.get(exit, "any"))
			var order: Array = Automation.SORTER_RULES
			var nxt: String = order[(order.find(cur) + 1) % order.size()]
			Game.request("set_sorter_rule", {"sorter": station_id, "out": exit, "rule": nxt})
		"splitter", "gate":
			var modes: Array = Automation.BELT_MODES[dev]
			var cur := Automation.belt_mode(Game.state, station_id, dev)
			Game.request("set_belt_mode", {"id": station_id, "mode": modes[(modes.find(cur) + 1) % modes.size()]})
			if dev == "gate":
				Sfx.play_at("hydraulic", global_position + Vector3(0, 1.2, 0), -10.0)
		_:
			return
	Sfx.play_ui("ui_click", -6.0)
