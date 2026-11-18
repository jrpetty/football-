class_name RackView
extends ShelfUnit
## Network test / imaging rack: heavy-duty racking with a switch on top, a cable to every bay and a status
## light + readout per bay. Units in the bays boot over the network (no USB); Sim runs one job per bay.

const SWITCH_MODEL := "res://assets/models/network/switch_24/switch_24.tscn"

var _bay_lamps := {}         # slot name -> StandardMaterial3D
var _bay_labels := {}        # slot name -> Label3D
var _was_busy := {}
var _tick := 0.0
var _viewport: SubViewport
var _screen_tex: Texture2D


func _station_ready() -> void:
	_build_extras()
	_build_screen()


func _build_extras() -> void:
	var dark := Mats.color(Color(0.06, 0.06, 0.07), 0.5)
	if ResourceLoader.exists(SWITCH_MODEL):
		for i in 2:
			var sw: Node3D = load(SWITCH_MODEL).instantiate()
			add_child(sw)
			sw.position = Vector3(-0.5 + i * 1.0, height + 0.005, -0.1)
	var sign := Placeholders.text(self, "NETWORK TEST RACK" if station_type == "rack_test" else "NETWORK IMAGING RACK", Vector3(0, height + 0.16, 0.0), 44, Color(0.95, 0.97, 1.0), Vector3.ZERO, 0.0014)
	sign.shaded = false
	sign.outline_size = 8
	sign.outline_modulate = Color(0.05, 0.1, 0.2) if station_type == "rack_test" else Color(0.25, 0.12, 0.02)
	# Cable riser up the back of the left upright, and a patch lead + status light per bay.
	var riser := MeshInstance3D.new()
	var rm := BoxMesh.new()
	rm.size = Vector3(0.05, height, 0.05)
	riser.mesh = rm
	riser.material_override = Mats.color(Color(0.15, 0.35, 0.8) if station_type == "rack_test" else Color(0.9, 0.5, 0.1), 0.6)
	riser.position = Vector3(-width * 0.5 + 0.06, height * 0.5, -depth * 0.5 + 0.06)
	add_child(riser)
	for s: SnapSlot in slots:
		var lead := MeshInstance3D.new()
		var lm := CylinderMesh.new()
		lm.top_radius = 0.006
		lm.bottom_radius = 0.006
		lm.height = absf(s.position.x - riser.position.x)
		lead.mesh = lm
		lead.material_override = riser.material_override
		lead.rotation_degrees.z = 90.0
		lead.position = Vector3((s.position.x + riser.position.x) * 0.5, s.position.y + 0.05, -depth * 0.5 + 0.08)
		add_child(lead)
		var lamp := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = Vector3(0.05, 0.025, 0.012)
		lamp.mesh = bm
		var mat := StandardMaterial3D.new()
		mat.albedo_color = Color(0.1, 0.1, 0.1)
		mat.emission_enabled = true
		mat.emission = Color(0.2, 0.2, 0.2)
		mat.emission_energy_multiplier = 0.0
		lamp.material_override = mat
		lamp.position = Vector3(s.position.x - 0.36, s.position.y - 0.035, depth * 0.5 - 0.02)
		add_child(lamp)
		_bay_lamps[s.slot_name] = mat
		var lbl := Placeholders.text(self, "", Vector3(s.position.x + 0.06, s.position.y - 0.035, depth * 0.5 - 0.012), 22, Color(0.8, 0.85, 0.9), Vector3.ZERO, 0.0011)
		lbl.shaded = false
		lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_LEFT
		_bay_labels[s.slot_name] = lbl
		var plate := MeshInstance3D.new()
		var pm := BoxMesh.new()
		pm.size = Vector3(0.5, 0.045, 0.01)
		plate.mesh = pm
		plate.material_override = dark
		plate.position = Vector3(s.position.x - 0.12, s.position.y - 0.035, depth * 0.5 - 0.022)
		add_child(plate)


func _build_screen() -> void:
	# What a unit's display shows while it's network-booted (rendered once, shared by every bay).
	_viewport = SubViewport.new()
	_viewport.size = Vector2i(480, 300)
	_viewport.render_target_update_mode = SubViewport.UPDATE_ONCE
	_viewport.transparent_bg = false
	add_child(_viewport)
	var bg := ColorRect.new()
	bg.color = Color(0.03, 0.09, 0.2) if station_type == "rack_test" else Color(0.12, 0.06, 0.02)
	bg.size = Vector2(480, 300)
	_viewport.add_child(bg)
	var t := Label.new()
	t.text = "PXE · NETWORK DIAGNOSTICS\n\nCPU ✓  Memory ✓  Storage …\nBattery · Display · Ports\n\nRunning — do not power off" if station_type == "rack_test" else "PANEOS DEPLOY\n\nSecure wipe (3 passes) ✓\nImage 24H2 · drivers …\n\nRunning — do not power off"
	t.position = Vector2(26, 26)
	t.add_theme_font_size_override("font_size", 24)
	t.add_theme_color_override("font_color", Color(0.85, 0.92, 1.0))
	_viewport.add_child(t)
	_screen_tex = _viewport.get_texture()


func _process(delta: float) -> void:
	_tick -= delta
	if _tick > 0.0:
		return
	_tick = 0.2
	var st := state()
	var jobs: Dictionary = st.get("jobs", {})
	var blink := fmod(Time.get_ticks_msec() / 1000.0, 1.0) < 0.5
	for s: SnapSlot in slots:
		var bay := s.slot_name
		var mat: StandardMaterial3D = _bay_lamps.get(bay)
		var lbl: Label3D = _bay_labels.get(bay)
		var it := slot_item(bay)
		var col := Color(0.2, 0.2, 0.2)
		var energy := 0.0
		var text := "free"
		if jobs.has(bay):
			var job: Dictionary = jobs[bay]
			var pct := clampf((Game.state.minute - float(job.start)) / maxf(float(job.dur), 0.01), 0.0, 1.0)
			col = Color(1.0, 0.65, 0.1)
			energy = 2.5 if blink else 1.2
			text = "%s %d%%" % ["TESTING" if job.kind == "test" else "IMAGING", int(pct * 100.0)]
			var v := item_view(it)
			if v:
				v.set_screen(_screen_tex)
			_was_busy[bay] = true
		elif it and it.unit:
			var u := it.unit
			var done := u.tested if station_type == "rack_test" else u.imaged
			if done:
				var bad := u.has_open_faults()
				col = Color(1.0, 0.2, 0.15) if bad and station_type == "rack_test" else Color(0.2, 1.0, 0.35)
				energy = 2.5
				text = ("FAULT · %d" % u.open_faults().size()) if bad and station_type == "rack_test" else "DONE ✓"
			else:
				col = Color(0.4, 0.6, 1.0)
				energy = 1.2
				text = "waiting"
			if _was_busy.get(bay, false):
				_was_busy[bay] = false
				Sfx.play_at("rack_chirp", s.global_position, -10.0, 0.0)
		if mat:
			mat.emission = col
			mat.albedo_color = col.darkened(0.5)
			mat.emission_energy_multiplier = energy
		if lbl and lbl.text != text:
			lbl.text = text
			lbl.modulate = col.lightened(0.3) if energy > 0.0 else Color(0.55, 0.6, 0.65)


func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	var jobs: Dictionary = state().get("jobs", {})
	var done := 0
	var free := 0
	for s: SnapSlot in slots:
		var it := slot_item(s.slot_name)
		if it == null:
			free += 1
		elif not jobs.has(s.slot_name):
			done += 1
	var speed := float(Automation.def_of(station_type).get("speed", 1.0))
	info.lines = ["%d running · %d done · %d free" % [jobs.size(), done, free],
		"Network boot — no USB stick needed · %.1f× bench speed" % speed]
	info.prompt = "[F] Move"
	return info
