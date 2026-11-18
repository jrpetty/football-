class_name LineMonitorView
extends StationBase
## Floor-standing line monitor: a big screen on a pole showing the automation live (LineMonitorScreen) with a
## stack light on top for the line's overall state — green flowing, amber backing up, red stopped.
## The screen is a 2D SubViewport redrawn a few times a second only while a camera is near (like the benches).

const NEAR := 16.0
const MID := 40.0

var _vp: SubViewport
var _screen: LineMonitorScreen
var _redraw_t := 0.0
var _lamps := {}                 # "red"/"amber"/"green" -> StandardMaterial3D
var _flow := ""
var _data := {}


func _station_ready() -> void:
	_vp = SubViewport.new()
	_vp.size = Vector2i(960, 540)
	_vp.render_target_update_mode = SubViewport.UPDATE_ONCE
	_vp.transparent_bg = false
	_vp.disable_3d = true
	add_child(_vp)
	_screen = LineMonitorScreen.new()
	_screen.size = Vector2(960, 540)
	_vp.add_child(_screen)
	if model:
		model.rebuilt.connect(_apply_screen)
	_apply_screen()
	_build_stack_light()
	_redraw()


func _apply_screen() -> void:
	if model == null:
		return
	var scr := model.find_part("Screen") as MeshInstance3D
	if scr:
		scr.material_override = Mats.screen_texture(_vp.get_texture(), 1.1)


func _build_stack_light() -> void:
	## Three-lamp stack light on the top right of the screen housing.
	var head := model.find_part("Head") as Node3D if model else null
	var parent: Node3D = head if head else self
	var base := Vector3(0.56, 0.4, -0.01) if head else Vector3(0.56, 2.42, 0)
	var dark := Mats.color(Color(0.08, 0.08, 0.09), 0.5, 0.3)
	var stem := MeshInstance3D.new()
	var sm := BoxMesh.new()
	sm.size = Vector3(0.04, 0.05, 0.04)
	stem.mesh = sm
	stem.material_override = dark
	stem.position = base + Vector3(0, 0.025, 0)
	parent.add_child(stem)
	var k := 0
	for key in ["green", "amber", "red"]:
		var m := StandardMaterial3D.new()
		m.emission_enabled = true
		var lamp := MeshInstance3D.new()
		var cm := CylinderMesh.new()
		cm.top_radius = 0.032
		cm.bottom_radius = 0.032
		cm.height = 0.06
		cm.radial_segments = 16
		lamp.mesh = cm
		lamp.material_override = m
		lamp.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		lamp.position = base + Vector3(0, 0.085 + k * 0.064, 0)
		parent.add_child(lamp)
		_lamps[key] = m
		k += 1
	var cap := MeshInstance3D.new()
	var cb := BoxMesh.new()
	cb.size = Vector3(0.07, 0.014, 0.07)
	cap.mesh = cb
	cap.material_override = dark
	cap.position = base + Vector3(0, 0.27, 0)
	parent.add_child(cap)
	_set_flow("idle")


func _set_flow(flow: String) -> void:
	if flow == _flow:
		return
	_flow = flow
	var on: String = {"flowing": "green", "waiting": "amber", "stopped": "red", "idle": ""}.get(flow, "")
	var cols := {"green": Color(0.2, 1.0, 0.35), "amber": Color(1.0, 0.62, 0.08), "red": Color(1.0, 0.15, 0.1)}
	for key in _lamps:
		var m: StandardMaterial3D = _lamps[key]
		var c: Color = cols[key]
		var lit: bool = key == on
		m.emission = c
		m.albedo_color = c.darkened(0.2) if lit else c.darkened(0.78)
		m.emission_energy_multiplier = 3.0 if lit else 0.0


func _process(delta: float) -> void:
	_redraw_t -= delta
	if _redraw_t > 0.0:
		return
	var cam := get_viewport().get_camera_3d()
	var d := cam.global_position.distance_to(global_position) if cam else INF
	if d > MID:
		_redraw_t = 0.5          # only a distance check out here: redraws resume as soon as someone walks up
		return
	_redraw_t = 0.33 if d < NEAR else 2.0
	_redraw()


func _redraw() -> void:
	_data = Automation.line_status(Game.state, station_id)
	_screen.title = str(Game.state.fixtures.get(station_id, {}).get("label", "Line monitor")).to_upper()
	_screen.data = _data
	_screen.queue_redraw()
	_vp.render_target_update_mode = SubViewport.UPDATE_ONCE
	_set_flow(str(_data.get("flow", "idle")))


func hover_info(_player: Node) -> Dictionary:
	var f: Dictionary = Game.state.fixtures.get(station_id, {})
	var lines: Array = []
	if _data.is_empty():
		_data = Automation.line_status(Game.state, station_id)
	lines.append("%d machine%s · %d busy · %d on the belts · %d moves an hour" % [int(_data.get("total", 0)), "" if int(_data.get("total", 0)) == 1 else "s", int(_data.get("busy", 0)), int(_data.get("on_belts", 0)), int(_data.get("mph", 0))])
	lines.append(str(_data.get("bottleneck_text", "")))
	lines.append("Green: flowing · Amber: backing up · Red: stopped.")
	return {"title": str(f.get("label", "Line monitor")), "lines": lines, "prompt": "[F] Move"}
