class_name ConveyorView
extends StationBase
## A conveyor fixture: straight, curve, or a device section (goods-in scanner, sorter, vision grader).
## The host's Automation moves the items; this draws the moving belt, the device effects and handles the
## player side: put kit on the loading point at the start, read what's on it, change sorter rules.

const BELT_SHADER := preload("res://shaders/belt.gdshader")
const BELT_W := 0.58
const BELT_Y := 0.858
const SCANNER_MODEL := "res://assets/models/props/barcode_scanner/barcode_scanner.tscn"

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

static var _idx_frame := -1
static var _idx := {}        # belt fid -> [Item]


func _ready() -> void:
	auto = Automation.def_of(fixture_type)
	lb = Automation.belt_local(auto)
	dev = str(auto.get("device", ""))
	if lb.kind == "line":
		var s := SnapSlot.new()
		s.name = "Load"
		s.slot_name = "load"
		s.accepts = PackedStringArray(["unit", "unit_large", "package", "package_large"])
		s.location_label = "Conveyor loading point"
		var p := Automation.path_point(lb, Automation.LOAD_S)
		s.position = p.origin + Vector3(0, float(auto.get("height", 0.84)) + 0.013, 0)
		s.hint_size = Vector3(BELT_W - 0.04, 0.01, 0.72)
		add_child(s)
	super._ready()
	_build_belt()
	match dev:
		"scanner":
			_build_scanner()
		"sorter":
			_build_sorter()
		"grader":
			_build_grader()
	_loop = Sfx.make_loop("conveyor_loop", self, -60.0)
	_loop.position = Vector3(0, 0.6, 0)


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
	var want := float(auto.get("speed", 0.6)) if moving else 0.0
	var was := _speed
	_speed = move_toward(_speed, want, delta * 1.8)
	if _speed > 0.0 or was > 0.0:
		_offset = fmod(_offset + _speed * delta, 1000.0)
		_belt.set_instance_shader_parameter("belt_offset", _offset)
		_belt.set_instance_shader_parameter("belt_run", _speed / maxf(float(auto.get("speed", 0.6)), 0.01))
	var vol := -60.0 if _speed <= 0.01 else lerpf(-34.0, -22.0, _speed / maxf(float(auto.get("speed", 0.6)), 0.01))
	_loop.volume_db = vol
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
	for it: Item in list:
		var s := float(it.loc.get("s", 0.0)) + float(it.loc.get("v", 0.0)) * (now - float(it.loc.get("at", 0.0)))
		var before := float(_seen.get(it.id, -1.0))
		seen[it.id] = s
		if before >= 0.0 and before < mid and s >= mid:
			_trigger(it)
		if dev == "sorter":
			_aim_paddle(it, s)
	_seen = seen
	if _fx_t > 0.0:
		_fx_t -= delta
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


func _box(size: Vector3, pos: Vector3, mat: Material) -> MeshInstance3D:
	var m := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	m.mesh = bm
	m.material_override = mat
	m.position = pos
	add_child(m)
	return m


func _build_sorter() -> void:
	var steel := Mats.painted_metal(Color(0.18, 0.2, 0.23))
	var length: float = lb.length
	var z_exit := length * 0.5 - 0.3
	# Transfer rollers out to each side exit (Jon's conveyor, short and turned 90°).
	var straight: PackedScene = load("res://assets/models/automation/conveyor_straight/conveyor_straight.tscn")
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
	if _signs.is_empty():
		return
	var rules: Dictionary = Game.state.stations.get(station_id, {}).get("rules", {"left": "faulty", "right": "passed", "out": "any"})
	var arrow := {"left": "◀ ", "right": "", "out": "▲ "}
	var tail := {"left": "", "right": " ▶", "out": ""}
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


func _on_state_changed(kind: String, id: Variant) -> void:
	super._on_state_changed(kind, id)
	if dev == "sorter" and kind == "station" and str(id) == station_id:
		_refresh_signs()


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
			lines.append("Left: %s · Right: %s · Straight on: %s" % [RULE_NAMES.get(rules.get("left", "faulty")), RULE_NAMES.get(rules.get("right", "passed")), RULE_NAMES.get(rules.get("out", "any"))])
			prompt = "[E] Change the %s exit  ·  [F] Move" % {"left": "left", "right": "right", "out": "straight-on"}[exit]
	if lb.kind == "line":
		lines.append("Put kit on the near end to send it along.")
	for prt in AutoLayout.open_ports():
		if prt.fid == station_id and not prt["in"] and prt.name == "out":
			if AutoLayout.arm_covering(prt.p) == "":
				lines.append("⚠ Open end with no robot arm in reach — kit will wait here.")
			else:
				lines.append("Open end: a robot arm picks up here.")
	return {"title": str(f.get("label", def.get("name", "Conveyor"))), "lines": lines, "prompt": prompt}


func _exit_at(player: Node) -> String:
	var hit: Vector3 = player.get("_hover_hit") if player and player.get("_hover_hit") != null else global_position
	var lp := to_local(hit)
	if lp.x < -0.3:
		return "left"
	if lp.x > 0.3:
		return "right"
	return "out"


func interact(player: Node) -> void:
	if dev != "sorter":
		return
	var exit := _exit_at(player)
	var rules: Dictionary = Game.state.stations.get(station_id, {}).get("rules", {"left": "faulty", "right": "passed", "out": "any"})
	var cur := str(rules.get(exit, "any"))
	var order: Array = Automation.SORTER_RULES
	var nxt: String = order[(order.find(cur) + 1) % order.size()]
	Game.request("set_sorter_rule", {"sorter": station_id, "out": exit, "rule": nxt})
	Sfx.play_ui("ui_click", -6.0)
