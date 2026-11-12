class_name RobotArmView
extends Node3D
## The pick-and-place robot arm fixture. The host's Automation decides what moves where (station job
## {item, to, t0, dur} on the belt clock); this animates it: swing over, dip, grip, lift, swing, place, release.
## Carried items ask carry_xf() each frame, so the laptop really travels in the gripper.

const GRIP_AT := 0.35
const RELEASE_AT := 0.92
const HOVER := 0.26
const EFF_H := 0.045     # suction plate + cups below the gripper

@export var station_id: String = ""

var rig: ArmRig
var _job_t0 := -1.0
var _dur := 3.6
var _item := 0
var _from := Vector3()
var _pick := Vector3()           # local tip targets
var _place := Vector3()
var _pick_xf := Transform3D()    # world transforms of the item at pick / place
var _place_xf := Transform3D()
var _h := 0.2
var _u := 0.0
var _last_u := 0.0
var _idle_from := Vector3()
var _idle_t := 99.0
var _servo: AudioStreamPlayer3D
var _last_tip := Vector3()
var _ring: MeshInstance3D
var _ring_t := 0.0
var _lamps: Array = []
var _lamp_state := ""
var _eff: Node3D
var _eff_plate: MeshInstance3D
var _cups: Array = []
var _eff_size := Vector2(0.24, 0.16)


func _ready() -> void:
	var m := get_node_or_null("Model")
	if m:
		m.queue_free()     # the static model is only for the placement ghost
	rig = ArmRig.new()
	add_child(rig)
	_build_body()
	_build_lamp()
	_build_ring()
	_build_effector()
	_servo = Sfx.make_loop("servo_loop", rig, -60.0)
	_servo.position = Vector3(0, 1.2, 0)


func _build_body() -> void:
	var body := StaticBody3D.new()
	body.collision_layer = 1 | (1 << 3)
	var cs := CollisionShape3D.new()
	var cyl := CylinderShape3D.new()
	cyl.radius = 0.42
	cyl.height = 1.0
	cs.shape = cyl
	cs.position = Vector3(0, 0.5, 0)
	body.add_child(cs)
	add_child(body)


func _build_lamp() -> void:
	# Three-tier stack light on a post at the base: green ready · blue moving · amber waiting.
	var post := MeshInstance3D.new()
	var pm := CylinderMesh.new()
	pm.top_radius = 0.012
	pm.bottom_radius = 0.012
	pm.height = 0.5
	post.mesh = pm
	post.position = Vector3(0.36, 0.25, 0.36)
	post.material_override = Mats.color(Color(0.2, 0.2, 0.22), 0.4, 0.6)
	add_child(post)
	var cols := [Color(1.0, 0.62, 0.1), Color(0.2, 0.55, 1.0), Color(0.2, 0.95, 0.35)]
	for i in 3:
		var l := MeshInstance3D.new()
		var cm := CylinderMesh.new()
		cm.top_radius = 0.035
		cm.bottom_radius = 0.035
		cm.height = 0.07
		l.mesh = cm
		l.position = Vector3(0.36, 0.55 + i * 0.075, 0.36)
		var mat := StandardMaterial3D.new()
		mat.albedo_color = (cols[i] as Color).darkened(0.6)
		mat.emission_enabled = true
		mat.emission = cols[i]
		mat.emission_energy_multiplier = 0.0
		l.material_override = mat
		add_child(l)
		_lamps.append(mat)


func _set_lamp(which: String) -> void:
	if which == _lamp_state:
		return
	_lamp_state = which
	var idx: int = {"amber": 0, "blue": 1, "green": 2}.get(which, -1)
	for i in _lamps.size():
		(_lamps[i] as StandardMaterial3D).emission_energy_multiplier = 2.2 if i == idx else 0.0


func _build_effector() -> void:
	# Vacuum gripper plate: sizes itself to what it's lifting (a phone-sized pad for a laptop, a long frame
	# for a 2U server), with a suction cup at each corner.
	_eff = Node3D.new()
	add_child(_eff)
	var boss := MeshInstance3D.new()
	var bm := CylinderMesh.new()
	bm.top_radius = 0.035
	bm.bottom_radius = 0.045
	bm.height = 0.05
	boss.mesh = bm
	boss.position = Vector3(0, 0.0, 0)
	boss.material_override = Mats.color(Color(0.15, 0.15, 0.16), 0.4, 0.6)
	_eff.add_child(boss)
	_eff_plate = MeshInstance3D.new()
	var pm := BoxMesh.new()
	pm.size = Vector3.ONE
	_eff_plate.mesh = pm
	_eff_plate.material_override = Mats.color(Color(0.74, 0.76, 0.79), 0.3, 0.85)
	_eff_plate.position = Vector3(0, -0.03, 0)
	_eff.add_child(_eff_plate)
	var rubber := Mats.color(Color(0.06, 0.06, 0.07), 0.8)
	for i in 4:
		var cup := MeshInstance3D.new()
		var cm := CylinderMesh.new()
		cm.top_radius = 0.018
		cm.bottom_radius = 0.028
		cm.height = 0.022
		cup.mesh = cm
		cup.material_override = rubber
		_eff.add_child(cup)
		_cups.append(cup)


func _update_effector(delta: float) -> void:
	var want := Vector2(0.24, 0.16)
	var basis := Basis(Vector3.UP, rig.turret.global_rotation.y)
	if _item != 0 and _job_t0 >= 0.0:
		var lr := LevelRoot.current
		var view: ItemView = lr.item_views.get(_item) if lr else null
		if view:
			want = Vector2(clampf(view.visual_size.x * 0.8, 0.16, 0.5), clampf(view.visual_size.z * 0.8, 0.12, 0.62))
			if _u >= GRIP_AT - 0.08 and _u < 0.97:
				basis = view.global_basis.orthonormalized()
	_eff_size = _eff_size.lerp(want, clampf(delta * 6.0, 0.0, 1.0))
	_eff.global_transform = Transform3D(basis, rig.tip_global())
	_eff_plate.scale = Vector3(_eff_size.x, 0.016, _eff_size.y)
	for i in 4:
		var sx := -1.0 if i % 2 == 0 else 1.0
		var sz := -1.0 if i < 2 else 1.0
		(_cups[i] as Node3D).position = Vector3(sx * (_eff_size.x * 0.5 - 0.035), -0.049, sz * (_eff_size.y * 0.5 - 0.035))


func _build_ring() -> void:
	var reach := float(Automation.def_of("robot_arm").get("reach", 1.8))
	_ring = MeshInstance3D.new()
	var tm := TorusMesh.new()
	tm.inner_radius = reach - 0.03
	tm.outer_radius = reach + 0.03
	tm.rings = 64
	_ring.mesh = tm
	_ring.scale = Vector3(1, 0.05, 1)
	_ring.position = Vector3(0, 0.01, 0)
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	mat.albedo_color = Color(0.3, 0.8, 1.0, 0.5)
	_ring.material_override = mat
	_ring.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_ring.visible = false
	add_child(_ring)


# ================================================================= animation

func _process(delta: float) -> void:
	var st: Dictionary = Game.state.stations.get(station_id, {})
	var job: Dictionary = st.get("job", {})
	var now := Game.state.belt_now(Game.is_host())
	if not job.is_empty():
		if float(job.t0) != _job_t0:
			_begin(job)
		_last_u = _u
		_u = clampf((now - _job_t0) / maxf(_dur, 0.1), 0.0, 1.0)
		rig.solve(_path(_u))
		if _last_u < GRIP_AT and _u >= GRIP_AT:
			Sfx.play_at("gripper", rig.tip_global(), -8.0)
		if _last_u < RELEASE_AT and _u >= RELEASE_AT:
			Sfx.play_at("gripper", rig.tip_global(), -9.0)
			Sfx.play_at("place", rig.tip_global(), -10.0)
		_set_lamp("blue")
	else:
		if _job_t0 >= 0.0:
			_job_t0 = -1.0
			_item = 0
			_idle_from = rig.tip
			_idle_t = 0.0
		if _idle_t < 1.4:
			_idle_t += delta
			rig.solve(_cyl_lerp(_idle_from, rig.home, _ease(_idle_t / 1.4)))
		_set_lamp("green" if str(st.get("msg", "")) == "" else "amber")
	# Servo whine follows how fast the tip is moving.
	var tip := rig.tip
	var speed := tip.distance_to(_last_tip) / maxf(delta, 0.001)
	_last_tip = tip
	var want_db := -60.0 if speed < 0.05 else lerpf(-26.0, -14.0, clampf(speed / 2.0, 0.0, 1.0))
	_servo.volume_db = lerpf(_servo.volume_db, want_db, clampf(delta * 10.0, 0.0, 1.0))
	_servo.pitch_scale = lerpf(0.8, 1.35, clampf(speed / 2.0, 0.0, 1.0))
	if _servo.volume_db > -55.0 and not _servo.playing:
		_servo.play()
	elif _servo.volume_db <= -58.0 and _servo.playing:
		_servo.stop()
	_update_effector(delta)
	if _ring.visible:
		_ring_t -= delta
		if _ring_t <= 0.0:
			_ring.visible = false


func _begin(job: Dictionary) -> void:
	_job_t0 = float(job.t0)
	_dur = float(job.get("dur", 3.6))
	_item = int(job.item)
	_from = rig.tip
	_u = 0.0
	var lr := LevelRoot.current
	var view: ItemView = lr.item_views.get(_item) if lr else null
	_pick_xf = view.arm_pick_xf if view else Transform3D(Basis.IDENTITY, to_global(rig.home))
	_h = view.visual_size.y if view else 0.2
	_place_xf = _target_xf(job.get("to", {}))
	_pick = to_local(_pick_xf.origin + Vector3(0, _h + EFF_H, 0))
	_place = to_local(_place_xf.origin + Vector3(0, _h + EFF_H, 0))


func _target_xf(to: Dictionary) -> Transform3D:
	var lr := LevelRoot.current
	match to.get("kind", ""):
		"slot":
			var slot: SnapSlot = lr.slot_nodes.get(str(to.slot)) if lr else null
			if slot:
				return slot.global_transform
		"belt":
			var f: Dictionary = Game.state.fixtures.get(str(to.belt), {})
			if not f.is_empty():
				return Automation.belt_point(Automation.belt_world(f), 0.0)
	return global_transform * Transform3D(Basis.IDENTITY, Vector3(0.0, 0.0, 0.9))


func _path(u: float) -> Vector3:
	var hp := _pick + Vector3(0, HOVER, 0)
	var hq := _place + Vector3(0, HOVER, 0)
	if u < 0.24:
		var k := _ease(u / 0.24)
		var p := _cyl_lerp(_from, hp, k)
		p.y += sin(k * PI) * 0.08
		return p
	if u < 0.33:
		return hp.lerp(_pick, _ease((u - 0.24) / 0.09))
	if u < 0.40:
		return _pick
	if u < 0.50:
		return _pick.lerp(hp, _ease((u - 0.40) / 0.10))
	if u < 0.80:
		var k2 := _ease((u - 0.50) / 0.30)
		var q := _cyl_lerp(hp, hq, k2)
		q.y += sin(k2 * PI) * 0.12
		return q
	if u < 0.89:
		return hq.lerp(_place, _ease((u - 0.80) / 0.09))
	if u < 0.95:
		return _place
	return _place.lerp(hq, _ease((u - 0.95) / 0.05) * 0.5)


static func _ease(x: float) -> float:
	x = clampf(x, 0.0, 1.0)
	return x * x * x * (x * (x * 6.0 - 15.0) + 10.0)     # smootherstep: no jerk at either end


static func _cyl_lerp(a: Vector3, b: Vector3, k: float) -> Vector3:
	## Swing round the base (angle, radius, height) rather than cutting straight through the column.
	var aa := atan2(a.z, -a.x)
	var ab := atan2(b.z, -b.x)
	var ra := Vector2(a.x, a.z).length()
	var rb := Vector2(b.x, b.z).length()
	var ang := lerp_angle(aa, ab, k)
	var r := lerpf(ra, rb, k)
	return Vector3(-cos(ang) * r, lerpf(a.y, b.y, k), sin(ang) * r)


func carry_xf(item_id: int, pick_xf: Transform3D, size: Vector3) -> Transform3D:
	## Where an item the arm has hold of should be drawn this frame.
	if item_id != _item or _job_t0 < 0.0:
		return pick_xf
	if _u < GRIP_AT:
		return pick_xf
	if _u >= RELEASE_AT:
		return _place_xf
	var k := _ease((_u - 0.50) / 0.30)
	var b := _pick_xf.basis.orthonormalized().slerp(_place_xf.basis.orthonormalized(), k)
	return Transform3D(b, rig.tip_global() - Vector3(0, size.y + EFF_H, 0))


# ================================================================= interaction

func hover_info(_player: Node) -> Dictionary:
	_ring.visible = true
	_ring_t = 0.25
	var def := Content.fixture("robot_arm")
	var st: Dictionary = Game.state.stations.get(station_id, {})
	var job: Dictionary = st.get("job", {})
	var lines: Array = []
	if job.is_empty():
		lines.append("Waiting — nothing in reach needs moving.")
	else:
		var it := Game.state.get_item(int(job.item))
		lines.append("Moving %s → %s" % [it.display_name() if it else "kit", _target_label(job.get("to", {}))])
	var serves := AutoLayout.arm_serves(global_position, station_id)
	lines.append("Reaches: " + (", ".join(serves) if not serves.is_empty() else "nothing — move kit inside the ring"))
	return {"title": str(Game.state.fixtures.get(station_id, {}).get("label", def.get("name", "Robot arm"))), "lines": lines, "prompt": "[F] Move"}


func _target_label(to: Dictionary) -> String:
	match to.get("kind", ""):
		"belt":
			return "conveyor"
		"slot":
			var host := str(to.slot).get_slice(":", 0)
			var f: Dictionary = Game.state.fixtures.get(host, {})
			if not f.is_empty():
				return str(f.get("label", Content.fixture(f.type).get("name", host)))
	return "the floor"
