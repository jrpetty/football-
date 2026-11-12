class_name TeaStation
extends StationBase
## The kitchen corner, as a proper tea round for the team (co-op):
##   cupboard → get mugs out onto the worktop (up to 6) · kettle → fill & boil (you can walk off; it clicks
##   off when done) · kettle again → pour the round (the kettle lifts and pours each mug in turn, the brew
##   rises and steeps) · fridge → a splash of milk in each · then carry mugs to colleagues ([E] on them) and
##   drink ([E] with a mug in hand) · empties go in the sink to wash up.
## Sim keeps the state (station "kettle" / "pour" / "milk" / "wash", mug items); this animates it.

const MUG_SPOTS := [Vector3(-0.12, 0, 0.16), Vector3(-0.02, 0, 0.16), Vector3(0.08, 0, 0.16),
	Vector3(-0.07, 0, 0.05), Vector3(0.03, 0, 0.05), Vector3(0.13, 0, 0.05)]
const TOP := 0.8975
const KETTLE_REST := Vector3(-0.3, 0.9, -0.08)
const SINK := Vector3(0.3, 0.9, 0.0)
const FRIDGE := Vector3(0.72, 0.0, 0.0)

var _kettle: Node3D
var _led: StandardMaterial3D
var _spout_steam: GPUParticles3D
var _stream: MeshInstance3D
var _stream_mat: StandardMaterial3D
var _bottle: Node3D
var _tap_stream: MeshInstance3D
var _boil: AudioStreamPlayer3D
var _was_boiling := false
var _poured := {}               # mug id -> true once its pour sound played
var _milked := {}
var _wash_seen := 0.0


func _ready() -> void:
	station_type = "tea"
	if station_id == "":
		station_id = "kitchen"
	for i in MUG_SPOTS.size():
		var s := SnapSlot.new()
		s.slot_name = "mug%d" % i
		s.accepts = PackedStringArray(["mug"])
		s.location_label = "Kitchen worktop"
		s.hint_size = Vector3(0.09, 0.01, 0.09)
		s.position = MUG_SPOTS[i] + Vector3(0, TOP, 0)
		add_child(s)
	var sink := SnapSlot.new()
	sink.slot_name = "sink"
	sink.accepts = PackedStringArray(["mug"])
	sink.location_label = "Sink"
	sink.hint_size = Vector3(0.2, 0.01, 0.24)
	sink.position = SINK + Vector3(0, -0.04, 0)
	add_child(sink)
	super._ready()


func _station_ready() -> void:
	if model and model.visual:
		_kettle = model.visual.find_child("Kettle", true, false)
		var led: MeshInstance3D = model.visual.find_child("KettleLED", true, false)
		if led:
			_led = led.material_override as StandardMaterial3D
	_build_parts()
	_build_effects()
	_boil = Sfx.make_loop("kettle_boil", self, -60.0)
	_boil.position = KETTLE_REST + Vector3(0, 0.15, 0)


func _part(id: String, size: Vector3, pos: Vector3) -> void:
	var body := TeaPart.new()
	body.part = id
	var cs := CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = size
	cs.shape = bs
	body.add_child(cs)
	body.position = pos
	add_child(body)


func _build_parts() -> void:
	_part("kettle", Vector3(0.24, 0.26, 0.24), KETTLE_REST + Vector3(0, 0.13, 0))
	_part("cupboard", Vector3(0.88, 0.8, 0.04), Vector3(0, 0.44, 0.3))
	_part("fridge", Vector3(0.46, 0.8, 0.04), FRIDGE + Vector3(0, 0.42, 0.27))
	_part("sink", Vector3(0.26, 0.05, 0.3), SINK + Vector3(0, 0.02, 0))
	_part("tray", Vector3(0.36, 0.012, 0.24), Vector3(0.0, TOP + 0.006, 0.1))


func _build_effects() -> void:
	_spout_steam = GPUParticles3D.new()
	_spout_steam.amount = 24
	_spout_steam.lifetime = 1.4
	_spout_steam.emitting = false
	_spout_steam.local_coords = false
	var pm := ParticleProcessMaterial.new()
	pm.direction = Vector3(0, 1, 0.3)
	pm.spread = 15.0
	pm.initial_velocity_min = 0.15
	pm.initial_velocity_max = 0.3
	pm.gravity = Vector3(0, 0.05, 0)
	pm.scale_min = 0.8
	pm.scale_max = 1.8
	var grad := Gradient.new()
	grad.set_color(0, Color(1, 1, 1, 0.3))
	grad.set_color(1, Color(1, 1, 1, 0.0))
	var gt := GradientTexture1D.new()
	gt.gradient = grad
	pm.color_ramp = gt
	_spout_steam.process_material = pm
	var probe := TeaMug.make(0, 0)
	_spout_steam.draw_pass_1 = probe.steam.draw_pass_1
	probe.free()
	add_child(_spout_steam)
	# Pour stream (water from the kettle / milk from the bottle / the tap), stretched between two points.
	_stream = MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = 0.0045
	cm.bottom_radius = 0.0035
	cm.height = 1.0
	cm.radial_segments = 8
	_stream.mesh = cm
	_stream_mat = StandardMaterial3D.new()
	_stream_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	_stream_mat.albedo_color = Color(0.85, 0.9, 0.95, 0.55)
	_stream_mat.roughness = 0.05
	_stream.material_override = _stream_mat
	_stream.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_stream.visible = false
	add_child(_stream)
	_tap_stream = _stream.duplicate()
	_tap_stream.material_override = _stream_mat
	add_child(_tap_stream)
	# Milk bottle (comes out of the fridge for the milk round).
	_bottle = Node3D.new()
	var b := MeshInstance3D.new()
	var bm := CylinderMesh.new()
	bm.top_radius = 0.032
	bm.bottom_radius = 0.042
	bm.height = 0.2
	b.mesh = bm
	b.position.y = 0.1
	b.material_override = Mats.color(Color(0.97, 0.97, 0.96), 0.2)
	_bottle.add_child(b)
	var cap := MeshInstance3D.new()
	var capm := CylinderMesh.new()
	capm.top_radius = 0.02
	capm.bottom_radius = 0.02
	capm.height = 0.02
	cap.mesh = capm
	cap.position.y = 0.21
	cap.material_override = Mats.color(Color(0.15, 0.4, 0.85), 0.4)
	_bottle.add_child(cap)
	_bottle.visible = false
	add_child(_bottle)


# ================================================================= animation

func _process(delta: float) -> void:
	var st := state()
	var now := Game.state.belt_now(Game.is_host())
	var k: Dictionary = st.get("kettle", {})
	var boiling := not k.is_empty() and now < float(k.t0) + float(k.dur)
	var boiled := not k.is_empty() and not boiling
	# Boil: blue light, building rumble, steam at the end, click off.
	if _led:
		_led.emission_energy_multiplier = 2.5 if boiling else 0.0
	var p := clampf((now - float(k.get("t0", now))) / maxf(float(k.get("dur", 1.0)), 0.1), 0.0, 1.0) if not k.is_empty() else 0.0
	var vol := lerpf(-34.0, -14.0, p) if boiling else -60.0
	_boil.volume_db = lerpf(_boil.volume_db, vol, clampf(delta * 3.0, 0.0, 1.0))
	if _boil.volume_db > -55.0 and not _boil.playing:
		_boil.play()
	elif _boil.volume_db <= -55.0 and _boil.playing:
		_boil.stop()
	if _was_boiling and not boiling and boiled:
		Sfx.play_at("kettle_click", global_position + KETTLE_REST, -4.0)
	_was_boiling = boiling
	var hot := (boiling and p > 0.55) or (boiled and now - float(k.t0) - float(k.dur) < 60.0)
	_spout_steam.emitting = hot
	var kx := _kettle_motion(st, now)
	if _kettle:
		_kettle.transform = kx
		if boiling and p > 0.8:
			_kettle.position += Vector3(randf_range(-1, 1), 0, randf_range(-1, 1)) * 0.0015
	_spout_steam.position = (kx * Vector3(0, 0.19, 0.09))
	_milk_motion(st, now)
	_wash_motion(st, now)


func _mug_local(id: int) -> Vector3:
	var it := Game.state.get_item(id)
	if it and it.loc.get("t") == "slot":
		var nm := str(it.loc.slot).get_slice(":", 1)
		if nm.begins_with("mug"):
			return MUG_SPOTS[int(nm.substr(3))] + Vector3(0, TOP, 0)
	return KETTLE_REST


func _kettle_motion(st: Dictionary, now: float) -> Transform3D:
	var rest := Transform3D(Basis.IDENTITY, KETTLE_REST)
	var pour: Dictionary = st.get("pour", {})
	if pour.is_empty():
		_stream.visible = false
		return rest
	var ids: Array = pour.get("ids", [])
	var t := now - float(pour.t0)
	var n := ids.size()
	if t < 0.0 or t > 1.0 + n * 0.95 + 0.9:
		_stream.visible = false
		return rest
	var yaw := Basis(Vector3.UP, PI * 0.5)          # spout faces the mugs (+X)
	var above := func(i: int) -> Vector3: return _mug_local(int(ids[clampi(i, 0, n - 1)])) + Vector3(-0.115, 0.13, 0)
	var pos: Vector3
	var tilt := 0.0
	if t < 1.0:
		pos = KETTLE_REST.lerp(above.call(0), _ease(t / 1.0))
		pos.y += sin(t * PI) * 0.06
	elif t < 1.0 + n * 0.95:
		var i := int((t - 1.0) / 0.95)
		var tau := t - 1.0 - i * 0.95
		pos = above.call(i)
		if i > 0 and tau < 0.3:
			pos = (above.call(i - 1) as Vector3).lerp(above.call(i), _ease(tau / 0.3))
		if tau >= 0.25 and tau < 0.4:
			tilt = (tau - 0.25) / 0.15
		elif tau >= 0.4 and tau < 0.8:
			tilt = 1.0
		elif tau >= 0.8:
			tilt = 1.0 - (tau - 0.8) / 0.15
		var mid := int(ids[i])
		if tau >= 0.35 and not _poured.has(mid):
			_poured[mid] = true
			Sfx.play_at("tea_pour", global_position + pos, -8.0, 0.1)
	else:
		var back := (t - 1.0 - n * 0.95) / 0.9
		pos = (above.call(n - 1) as Vector3).lerp(KETTLE_REST, _ease(back))
		pos.y += sin(back * PI) * 0.05
		if back > 0.95:
			_poured.clear()
	var xf := Transform3D(yaw * Basis(Vector3.RIGHT, deg_to_rad(62.0) * _ease(tilt)), pos)
	if tilt > 0.55:
		var spout := xf * Vector3(0, 0.17, 0.09)
		var i2 := clampi(int((t - 1.0) / 0.95), 0, n - 1)
		_show_stream(_stream, spout, _mug_local(int(ids[i2])) + Vector3(0, 0.06, 0), Color(0.82, 0.88, 0.94, 0.55))
	else:
		_stream.visible = false
	return xf


func _milk_motion(st: Dictionary, now: float) -> void:
	var milk: Dictionary = st.get("milk", {})
	var ids: Array = milk.get("ids", [])
	var t := now - float(milk.get("t0", -100.0))
	var n := ids.size()
	if milk.is_empty() or t < 0.0 or t > 0.9 + n * 0.45 + 0.7:
		_bottle.visible = false
		if milk.is_empty() or t > 0.9 + n * 0.45 + 0.7:
			_milked.clear()
		return
	_bottle.visible = true
	var from := FRIDGE + Vector3(-0.15, 0.95, 0.25)
	var above := func(i: int) -> Vector3: return _mug_local(int(ids[clampi(i, 0, n - 1)])) + Vector3(0.09, 0.1, 0)
	var pos: Vector3
	var tilt := 0.0
	if t < 0.9:
		pos = from.lerp(above.call(0), _ease(t / 0.9))
	elif t < 0.9 + n * 0.45:
		var i := int((t - 0.9) / 0.45)
		var tau := t - 0.9 - i * 0.45
		pos = above.call(i)
		if i > 0 and tau < 0.15:
			pos = (above.call(i - 1) as Vector3).lerp(above.call(i), _ease(tau / 0.15))
		tilt = clampf(sin(clampf((tau - 0.1) / 0.3, 0.0, 1.0) * PI) * 1.4, 0.0, 1.0)
		var mid := int(ids[i])
		if tau > 0.15 and not _milked.has(mid):
			_milked[mid] = true
			Sfx.play_at("milk_pour", global_position + pos, -10.0, 0.1)
	else:
		pos = (above.call(n - 1) as Vector3).lerp(from, _ease((t - 0.9 - n * 0.45) / 0.7))
	_bottle.transform = Transform3D(Basis(Vector3.FORWARD, deg_to_rad(105.0) * tilt), pos)
	if tilt > 0.6:
		var mouth := _bottle.transform * Vector3(0, 0.21, 0)
		_show_stream(_stream, mouth, mouth + Vector3(0.0, -0.09, 0.0), Color(0.97, 0.97, 0.95, 0.9))


func _wash_motion(st: Dictionary, now: float) -> void:
	var w := float(st.get("wash", -100.0))
	var on := now - w >= 0.0 and now - w < 1.6
	if on:
		_show_stream(_tap_stream, SINK + Vector3(0, 0.22, -0.1), SINK + Vector3(0, 0.0, -0.1), Color(0.8, 0.88, 0.95, 0.5))
		if w != _wash_seen:
			_wash_seen = w
			Sfx.play_at("tea_pour", global_position + SINK, -10.0, 0.05)
	else:
		_tap_stream.visible = false


func _show_stream(m: MeshInstance3D, a: Vector3, b: Vector3, col: Color) -> void:
	var d := b - a
	if d.length() < 0.005:
		m.visible = false
		return
	m.visible = true
	var mid := (a + b) * 0.5
	var y := d.normalized()
	var x := y.cross(Vector3.FORWARD if absf(y.dot(Vector3.FORWARD)) < 0.9 else Vector3.RIGHT).normalized()
	var z := x.cross(y)
	m.transform = Transform3D(Basis(x, y * d.length(), z), mid)
	if (m.material_override as StandardMaterial3D).albedo_color != col:
		(m.material_override as StandardMaterial3D).albedo_color = col


static func _ease(x: float) -> float:
	x = clampf(x, 0.0, 1.0)
	return x * x * (3.0 - 2.0 * x)


# ================================================================= interaction (via TeaPart bodies)

func _mugs() -> Array:
	var out: Array = []
	for i in MUG_SPOTS.size():
		var it := slot_item("mug%d" % i)
		if it and it.kind == Item.KIND_MUG:
			out.append(it)
	return out


func part_hover(part: String, _player: Node) -> Dictionary:
	var st := state()
	var now := Game.state.belt_now(Game.is_host())
	var k: Dictionary = st.get("kettle", {})
	var mugs := _mugs()
	var empties := mugs.filter(func(m): return float(m.data.get("fill", 0.0)) <= 0.05).size()
	match part:
		"kettle":
			var pour: Dictionary = st.get("pour", {})
			if not pour.is_empty() and now - float(pour.t0) < 1.0 + pour.ids.size() * 0.95 + 0.9:
				return {"title": "Kettle", "lines": ["Pouring…"], "prompt": ""}
			if not k.is_empty() and now < float(k.t0) + float(k.dur):
				return {"title": "Kettle", "lines": ["Boiling… %d s — go and do something useful." % int(ceil(float(k.t0) + float(k.dur) - now))], "prompt": ""}
			if not k.is_empty():
				var lines: Array = ["Boiled."]
				if empties == 0:
					lines.append("Get mugs out of the cupboard (below) — one each for the team.")
					return {"title": "Kettle", "lines": lines, "prompt": ""}
				lines.append("%d mug%s ready." % [empties, "" if empties == 1 else "s"])
				return {"title": "Kettle", "lines": lines, "hold": {"label": "Pour the round", "secs": 0.8, "action": "pour"}}
			return {"title": "Kettle", "lines": ["Fill it and stick it on. You don't have to wait by it."], "hold": {"label": "Fill & switch on", "secs": 0.8, "action": "boil"}}
		"cupboard":
			return {"title": "Mug cupboard", "lines": ["%d of %d mugs out" % [mugs.size(), MUG_SPOTS.size()], "Making a round? Get one out for everyone."], "prompt": "[E] Get a mug out" if mugs.size() < MUG_SPOTS.size() else ""}
		"fridge":
			var need := mugs.filter(func(m): return float(m.data.get("fill", 0.0)) > 0.05 and str(m.data.get("brew", "")) != "" and not m.data.get("milk", false)).size()
			return {"title": "Fridge", "lines": ["Milk, and someone's sandwich (don't)."], "prompt": "[E] Splash of milk in each" if need > 0 else ""}
		"sink":
			return {"title": "Sink", "lines": ["Put mugs here to wash up."], "prompt": ""}
		"tray":
			return {"title": "Worktop", "lines": ["%d mug%s" % [mugs.size(), "" if mugs.size() == 1 else "s"]], "prompt": ""}
	return {}


func part_interact(part: String, _player: Node) -> void:
	match part:
		"cupboard":
			Game.request("tea", {"station": station_id, "act": "mug"})
			Sfx.play_at("mug_down", global_position + Vector3(0, 0.95, 0.1), -6.0)
		"fridge":
			Game.request("tea", {"station": station_id, "act": "milk"})


func part_hold(part: String, _player: Node, action: String) -> void:
	if part == "kettle" and action in ["boil", "pour"]:
		Game.request("tea", {"station": station_id, "act": action})
		if action == "boil":
			Sfx.play_at("kettle_click", global_position + KETTLE_REST, -4.0)


func hover_info(_player: Node) -> Dictionary:
	return {"title": "Kitchen", "lines": ["Kettle, mugs, fridge and a sink. Make a round for the team."], "prompt": ""}
