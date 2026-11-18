class_name RobotCellView
extends StationBase
## Repair robot cell / packing robot cell: a bench with a small articulated arm (Jon's robot arm at 42%),
## light curtain posts and a stack light. Sim runs the jobs; this animates them:
##   repair — the arm works over the unit with a driver, sparks off the contacts, part swaps.
##   pack   — the arm fetches a box, lowers it over the unit, tapes it and slaps the label on.

const BOX_MODEL := "res://assets/models/props/box_laptop/box_laptop.tscn"
const BIG_BOX_MODEL := "res://assets/models/props/box_heavy/box_heavy.tscn"
const BIN_MODEL := "res://assets/models/props/parts_bin/parts_bin.tscn"
const PAPER_MODEL := "res://assets/models/props/paper_roll_stand/paper_roll_stand.tscn"
const PRINTER_MODEL := "res://assets/models/props/label_printer/label_printer.tscn"
const TOP := 0.832
const PACK_STACK := Vector3(-0.52, TOP, -0.2)

@export var cell_kind: String = "repair"

var arm: ArmRig
var _lamps: Array = []
var _sparks: CPUParticles3D
var _loop: AudioStreamPlayer3D
var _box: Node3D
var _label: MeshInstance3D
var _rest := Vector3(-0.35, 0.55, 0.0)
var _tip := Vector3()
var _phase_mark := -1
var _was_busy := false


func _station_ready() -> void:
	arm = ArmRig.new()
	arm.scale = Vector3.ONE * 0.42
	arm.position = Vector3(0.5, TOP, -0.22) if cell_kind == "repair" else Vector3(0.0, TOP, -0.24)
	add_child(arm)
	_rest = arm.position + Vector3(-0.3, 0.42, 0.12) if cell_kind == "repair" else arm.position + Vector3(0.0, 0.45, 0.2)
	_tip = _rest
	_build_props()
	_build_fence()
	_build_lamp()
	_loop = Sfx.make_loop("repair_loop", self, -60.0)
	_loop.position = Vector3(0, 1.0, 0)


func _prop(path: String, pos: Vector3, rot_y: float = 0.0, fit: float = 0.0) -> Node3D:
	if not ResourceLoader.exists(path):
		return null
	var n: Node3D = load(path).instantiate()
	add_child(n)
	if fit > 0.0:
		var fp: Vector3 = n.get_meta("footprint", Vector3.ONE)
		n.scale = Vector3.ONE * (fit / maxf(fp.x, 0.01))
	n.position = pos
	n.rotation_degrees.y = rot_y
	return n


func _build_props() -> void:
	if cell_kind == "repair":
		for i in 3:
			_prop(BIN_MODEL, Vector3(-0.66 + i * 0.24, TOP, -0.27), 0.0)
		_sparks = CPUParticles3D.new()
		_sparks.emitting = false
		_sparks.amount = 24
		_sparks.lifetime = 0.35
		_sparks.one_shot = false
		_sparks.explosiveness = 0.2
		_sparks.direction = Vector3(0, 1, 0)
		_sparks.spread = 70.0
		_sparks.initial_velocity_min = 0.6
		_sparks.initial_velocity_max = 1.6
		_sparks.gravity = Vector3(0, -6, 0)
		_sparks.scale_amount_min = 0.006
		_sparks.scale_amount_max = 0.012
		var sm := SphereMesh.new()
		sm.radius = 0.5
		sm.height = 1.0
		sm.radial_segments = 4
		sm.rings = 2
		_sparks.mesh = sm
		var mat := Mats.emissive(Color(1.0, 0.7, 0.25), 6.0)
		_sparks.material_override = mat
		add_child(_sparks)
	else:
		_prop(PRINTER_MODEL, Vector3(0.55, TOP, -0.25), -20.0)
		# A stack of flat-packed boxes for the arm to take from.
		for i in 4:
			var flat := MeshInstance3D.new()
			var bm := BoxMesh.new()
			bm.size = Vector3(0.46, 0.012, 0.36)
			flat.mesh = bm
			flat.material_override = Mats.color(Color(0.62, 0.47, 0.3), 0.85)
			flat.position = PACK_STACK + Vector3(0, 0.006 + i * 0.013, 0)
			flat.rotation_degrees.y = randf_range(-4, 4)
			add_child(flat)


func _build_fence() -> void:
	# Light curtain posts at the front corners (yellow, with a red beam strip) — the cell is guarded.
	var yellow := Mats.painted_metal(Color(0.95, 0.75, 0.1))
	for sx in [-1.0, 1.0]:
		var post := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = Vector3(0.045, 1.5, 0.045)
		post.mesh = bm
		post.material_override = yellow
		post.position = Vector3(0.79 * sx, 0.75, 0.43)
		add_child(post)
		var strip := MeshInstance3D.new()
		var sm := BoxMesh.new()
		sm.size = Vector3(0.012, 1.2, 0.012)
		strip.mesh = sm
		strip.material_override = Mats.emissive(Color(1.0, 0.1, 0.05), 1.2)
		strip.position = Vector3(0.79 * sx - 0.025 * sx, 0.8, 0.43)
		add_child(strip)
	var sign := Placeholders.text(self, "REPAIR ROBOT" if cell_kind == "repair" else "PACKING ROBOT", Vector3(0, 1.8, -0.34), 40, Color(0.95, 0.95, 0.95), Vector3.ZERO, 0.0013)
	sign.shaded = false
	sign.outline_size = 8
	sign.outline_modulate = Color(0.1, 0.1, 0.12)


func _build_lamp() -> void:
	var cols := [Color(1.0, 0.15, 0.1), Color(1.0, 0.62, 0.1), Color(0.2, 0.95, 0.35)]
	for i in 3:
		var l := MeshInstance3D.new()
		var cm := CylinderMesh.new()
		cm.top_radius = 0.035
		cm.bottom_radius = 0.035
		cm.height = 0.07
		l.mesh = cm
		l.position = Vector3(-0.79, 1.55 + i * 0.075, 0.43)
		var mat := StandardMaterial3D.new()
		mat.albedo_color = (cols[i] as Color).darkened(0.6)
		mat.emission_enabled = true
		mat.emission = cols[i]
		mat.emission_energy_multiplier = 0.0
		l.material_override = mat
		add_child(l)
		_lamps.append(mat)


func _set_lamp(idx: int) -> void:
	for i in _lamps.size():
		(_lamps[i] as StandardMaterial3D).emission_energy_multiplier = 2.4 if i == idx else 0.0


# ================================================================= animation

func _process(delta: float) -> void:
	var st := state()
	var jobs: Dictionary = st.get("jobs", {})
	var bay := "unit"
	var job: Dictionary = jobs.get(bay, {})
	if job.is_empty() and not jobs.is_empty():
		job = jobs.values()[0]
	var target := _rest
	if not job.is_empty():
		var pct := clampf((Game.state.minute - float(job.start)) / maxf(float(job.dur), 0.01), 0.0, 1.0)
		var it := Game.state.get_item(int(job.unit))
		var v := item_view(it)
		var top := TOP + (v.visual_size.y if v else 0.03)
		var c := to_local(v.global_position) if v else Vector3(-0.2, TOP, 0.0)
		var t := Time.get_ticks_msec() / 1000.0
		if cell_kind == "repair":
			target = _repair_motion(c, top, t, pct)
		else:
			target = _pack_motion(c, top, pct, it, v)
		_set_lamp(1)
		_was_busy = true
		var want := -18.0 if cell_kind == "repair" else -60.0
		_loop.volume_db = lerpf(_loop.volume_db, want, clampf(delta * 4.0, 0.0, 1.0))
		if want > -50.0 and not _loop.playing:
			_loop.play()
	else:
		if _was_busy:
			_was_busy = false
			_phase_mark = -1
			Sfx.play_at("repair_done" if cell_kind == "repair" else "label_print", global_position + Vector3(0, 1, 0), -8.0)
			if _box:
				_box.queue_free()
				_box = null
		if _sparks:
			_sparks.emitting = false
		_loop.volume_db = lerpf(_loop.volume_db, -60.0, clampf(delta * 4.0, 0.0, 1.0))
		if _loop.playing and _loop.volume_db < -55.0:
			_loop.stop()
		_set_lamp(0 if str(st.get("msg", "")) != "" else 2)
	_tip = _tip.lerp(target, clampf(delta * 7.0, 0.0, 1.0))
	arm.solve((_tip - arm.position) / 0.42)


func _repair_motion(c: Vector3, top: float, t: float, pct: float) -> Vector3:
	# Work over the unit in a slow figure-of-eight, dipping onto screws now and then; sparks on each dip.
	var p := c + Vector3(sin(t * 1.3) * 0.12, 0.0, sin(t * 2.6) * 0.08)
	var dip := pow(maxf(sin(t * 3.1), 0.0), 6.0)
	p.y = top + 0.14 - dip * 0.11
	if _sparks:
		_sparks.position = Vector3(p.x, top + 0.01, p.z)
		_sparks.emitting = dip > 0.6 and pct < 0.95
		if dip > 0.95 and int(t * 3.1 / TAU) != _phase_mark:
			_phase_mark = int(t * 3.1 / TAU)
			Sfx.play_at("impact_light", to_global(p), -22.0)
	return p


func _pack_motion(c: Vector3, top: float, pct: float, it: Item, v: ItemView) -> Vector3:
	var stack := PACK_STACK + Vector3(0, 0.08, 0)
	var size := v.visual_size if v else Vector3(0.36, 0.03, 0.25)
	var phase := 0 if pct < 0.15 else (1 if pct < 0.35 else (2 if pct < 0.55 else (3 if pct < 0.8 else 4)))
	if phase != _phase_mark:
		_phase_mark = phase
		match phase:
			1:
				Sfx.play_at("box_open", global_position + Vector3(0, 1, 0), -8.0)
			3:
				Sfx.play_at("tape_gun", global_position + Vector3(0, 1, 0), -6.0)
			4:
				Sfx.play_at("label_print", global_position + Vector3(0, 1, 0), -8.0)
	if _box == null and phase >= 1:
		_box = Node3D.new()
		add_child(_box)
		var big := it != null and it.is_large()
		var path := BIG_BOX_MODEL if big else BOX_MODEL
		if ResourceLoader.exists(path):
			var m: Node3D = load(path).instantiate()
			_box.add_child(m)
			var fp: Vector3 = m.get_meta("footprint", Vector3(0.44, 0.12, 0.34))
			m.scale = Vector3((size.x + 0.1) / fp.x, (size.y + 0.08) / fp.y, (size.z + 0.1) / fp.z)
		_label = MeshInstance3D.new()
		var lm := BoxMesh.new()
		lm.size = Vector3(0.1, 0.002, 0.15)
		_label.mesh = lm
		_label.material_override = Mats.color(Color(0.96, 0.96, 0.94), 0.7)
		_label.visible = false
		_box.add_child(_label)
	match phase:
		0:
			return stack + Vector3(0, 0.1 + sin(pct / 0.15 * PI) * 0.05, 0)
		1:
			# Carry the box over and lower it down over the unit.
			var k := (pct - 0.15) / 0.2
			var from := stack + Vector3(0, 0.3, 0)
			var over := c + Vector3(0, size.y + 0.25, 0)
			var p := from.lerp(over, clampf(k * 1.6, 0.0, 1.0))
			if k > 0.6:
				p = over.lerp(c + Vector3(0, size.y + 0.08, 0), (k - 0.6) / 0.4)
			_box.position = p - Vector3(0, size.y + 0.08, 0)
			return p
		2:
			_box.position = c
			return c + Vector3(0, size.y + 0.2, 0)
		3:
			# Tape run along the lid seam.
			var k3 := (pct - 0.55) / 0.25
			_box.position = c
			return c + Vector3(lerpf(-size.x * 0.5, size.x * 0.5, k3), size.y + 0.1, 0.0)
		_:
			_box.position = c
			_label.visible = pct > 0.9
			_label.position = Vector3(0.05, size.y + 0.081, 0.02)
			return c + Vector3(0.05, size.y + 0.1 + (0.0 if pct > 0.9 else 0.06), 0.02)
	return _rest


func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	var st := state()
	var jobs: Dictionary = st.get("jobs", {})
	var lines: Array = []
	if not jobs.is_empty():
		var job: Dictionary = jobs.values()[0]
		var pct := clampf((Game.state.minute - float(job.start)) / maxf(float(job.dur), 0.01), 0.0, 1.0)
		var it := Game.state.get_item(int(job.unit))
		lines.append("%s %s — %d%%" % ["Repairing" if cell_kind == "repair" else "Packing", it.display_name() if it else "", int(pct * 100)])
	elif str(st.get("msg", "")) != "":
		lines.append(str(st.msg))
	else:
		lines.append("Ready")
	if cell_kind == "repair":
		lines.append("Fixes level 1 faults with parts from the bins (batteries, RAM, drives, keys, fans, PSUs).")
	else:
		lines.append("Boxes, tapes and labels sold units. Uses boxes, labels and tape from stock.")
	info.lines = lines
	return info
