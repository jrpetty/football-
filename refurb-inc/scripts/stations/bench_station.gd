class_name BenchStation
extends StationBase
## Test/imaging bench. Put a unit on it and plug in a USB: DiagnostiKit = full test, PaneOS Deploy = wipe & image.
## Runs on the game clock by itself — walk away and do something else.

var _vp: SubViewport
var _screen_ui: BenchScreen
var _status: Label3D
var _fan: AudioStreamPlayer3D
var _was_running := false
var _last_msg := ""


func _station_ready() -> void:
	if station_type == "":
		station_type = "bench"
	_vp = SubViewport.new()
	_vp.size = Vector2i(640, 400)
	_vp.render_target_update_mode = SubViewport.UPDATE_ALWAYS
	_vp.transparent_bg = false
	_vp.disable_3d = true
	add_child(_vp)
	_screen_ui = BenchScreen.new()
	_screen_ui.station_id = station_id
	_screen_ui.size = Vector2(640, 400)
	_vp.add_child(_screen_ui)
	_status = Label3D.new()
	_status.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	_status.font_size = 64
	_status.pixel_size = 0.0022
	_status.outline_size = 12
	_status.position = Vector3(0, 1.75, 0)
	_status.no_depth_test = false
	_status.fixed_size = false
	add_child(_status)
	_fan = Sfx.make_loop("fan_loop", self, -12.0)
	_fan.position = Vector3(0, 1.0, 0)
	if model:
		model.rebuilt.connect(_apply_monitor)
	_apply_monitor()


func _apply_monitor() -> void:
	if model == null:
		return
	var scr := model.find_part("Screen") as MeshInstance3D
	if scr:
		scr.material_override = Mats.screen_texture(_vp.get_texture(), 1.0)


func _refresh() -> void:
	var st := state()
	var unit_it := slot_item("unit")
	var job: Dictionary = st.get("job", {})
	var view := item_view(unit_it)
	if view:
		view.set_screen(_vp.get_texture())
	var running := not job.is_empty()
	if running and not _was_running:
		Sfx.play_at("post_beep", global_position + Vector3(0, 1, 0))
		if _fan.stream:
			_fan.play()
	elif not running and _was_running:
		_fan.stop()
	_was_running = running
	var msg: String = st.get("msg", "")
	if msg != _last_msg and msg in ["PASS", "IMAGED"]:
		Sfx.play_at("test_pass", global_position + Vector3(0, 1, 0))
	elif msg != _last_msg and msg == "FAULTS FOUND":
		Sfx.play_at("test_fail", global_position + Vector3(0, 1, 0))
	_last_msg = msg
	_update_status_label()
	super._refresh()


func _process(_delta: float) -> void:
	if not state().get("job", {}).is_empty():
		_update_status_label()


func _update_status_label() -> void:
	var st := state()
	var job: Dictionary = st.get("job", {})
	var unit_it := slot_item("unit")
	if unit_it == null:
		_status.text = ""
		return
	if not job.is_empty():
		var p := clampf((Game.state.minute - float(job.start)) / maxf(float(job.dur), 0.01), 0.0, 1.0)
		var left := maxf(float(job.dur) - (Game.state.minute - float(job.start)), 0.0)
		_status.text = "%s %d%%  ·  %d min" % ["TESTING" if job.kind == "test" else "IMAGING", int(p * 100), int(ceil(left))]
		_status.modulate = Color(0.55, 0.8, 1.0)
		return
	var u := unit_it.unit
	if u == null:
		_status.text = ""
		return
	match u.stage():
		UnitData.STAGE_FAULTY:
			_status.text = "✖ %d FAULT%s" % [u.open_faults().size(), "" if u.open_faults().size() == 1 else "S"]
			_status.modulate = Color(1.0, 0.4, 0.35)
		UnitData.STAGE_TO_IMAGE:
			_status.text = "✔ PASS"
			_status.modulate = Color(0.4, 1.0, 0.55)
		UnitData.STAGE_TO_GRADE:
			_status.text = "✔ IMAGED"
			_status.modulate = Color(0.45, 0.9, 0.95)
		_:
			_status.text = st.get("msg", "") if st.get("msg", "") not in ["PASS", "IMAGED", "FAULTS FOUND"] else ""
			_status.modulate = Color(1.0, 0.85, 0.35)


func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	var unit_it := slot_item("unit")
	var usb_it := slot_item("usb")
	info.lines = []
	info.lines.append("Unit: " + (unit_it.display_name() if unit_it else "— empty —"))
	info.lines.append("USB: " + (usb_it.display_name() if usb_it else "— none —"))
	var job: Dictionary = state().get("job", {})
	if not job.is_empty():
		var left := maxf(float(job.dur) - (Game.state.minute - float(job.start)), 0.0)
		info.lines.append("%s — %d game minutes left" % ["Testing" if job.kind == "test" else "Wipe & image", int(ceil(left))])
	elif state().get("msg", "") != "":
		info.lines.append(state().msg)
	return info


func extra_lines(it: Item) -> Array:
	var job: Dictionary = state().get("job", {})
	if not job.is_empty() and int(job.unit) == it.id:
		return ["Job running — picking it up cancels the job"]
	return []
