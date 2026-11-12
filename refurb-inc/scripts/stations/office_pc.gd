class_name OfficePC
extends Node3D
## The in-world office computer. Interact → camera glides to the monitor and the PaneOS desktop takes the mouse.
## The desktop is rendered into a SubViewport shown on the monitor mesh; mouse events are projected onto the
## screen plane and pushed into the viewport, so the UI is genuinely diegetic. Time keeps running.

const VP_SIZE := Vector2i(1600, 900)
const MONITOR_SCENE := "res://scenes/stations/visuals/monitor_visual.tscn"

@export var monitor_width := 0.62

var _vp: SubViewport
var _os: PaneOS
var _screen: MeshInstance3D
var _cam: Camera3D
var _view_point: Node3D
var _user: Player = null
var _tween: Tween
var _model: ModelSlot


func _ready() -> void:
	_model = ModelSlot.new()
	_model.placeholder = "monitor"
	_model.placeholder_params = {"w": monitor_width}
	# Jon's 27in monitor with the PaneOS screen fitted to its panel (placeholder if the model is missing).
	if ResourceLoader.exists(MONITOR_SCENE):
		_model.model_scene = load(MONITOR_SCENE)
	add_child(_model)
	_vp = SubViewport.new()
	_vp.size = VP_SIZE
	_vp.disable_3d = true
	_vp.gui_embed_subwindows = true
	_vp.render_target_update_mode = SubViewport.UPDATE_ONCE
	add_child(_vp)
	_os = PaneOS.new()
	_os.size = Vector2(VP_SIZE)
	_os.pc = self
	_vp.add_child(_os)
	# Collision so the player can look at it
	var body := StaticBody3D.new()
	body.collision_layer = 1 | (1 << 3)
	var cs := CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = Vector3(monitor_width + 0.1, 0.5, 0.25)
	cs.shape = bs
	cs.position = Vector3(0, 0.33, 0)
	body.add_child(cs)
	add_child(body)
	_view_point = Node3D.new()
	_view_point.name = "ViewPoint"
	add_child(_view_point)
	_cam = Camera3D.new()
	_cam.fov = 40
	_cam.near = 0.02
	add_child(_cam)
	_model.rebuilt.connect(_bind_screen)
	_bind_screen()


func _bind_screen() -> void:
	_screen = _model.find_part("Screen") as MeshInstance3D
	if _screen:
		_screen.material_override = Mats.screen_texture(_vp.get_texture(), 0.9)
		# Frame the screen: camera sits on the screen normal at a distance that fits it in view.
		var h: float = (_screen.mesh as QuadMesh).size.y if _screen.mesh is QuadMesh else monitor_width * 9.0 / 16.0
		var dist := (h * 0.5 / tan(deg_to_rad(40.0) * 0.5)) * 1.06
		_view_point.global_transform = Transform3D(_screen.global_basis, _screen.global_position + _screen.global_basis.z * dist)


func hover_info(_player: Node) -> Dictionary:
	return {"title": "Office PC", "lines": ["Purchasing · Flogit · Business · Shop · Stock · Finance · Mail"], "prompt": "[E] Use computer  ·  [F] Move desk"}


func interact(player: Node) -> void:
	if _user != null or not (player is Player):
		return
	_user = player
	_user.set_ui_mode(true)
	_bind_screen()
	var from: Transform3D = _user.camera.global_transform
	_cam.global_transform = from
	_cam.fov = _user.camera.fov
	_cam.current = true
	if _tween:
		_tween.kill()
	_tween = create_tween().set_parallel(true).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_IN_OUT)
	_tween.tween_property(_cam, "global_transform", _view_point.global_transform, 0.55)
	_tween.tween_property(_cam, "fov", 40.0, 0.55)
	_os.on_opened()
	Sfx.play_ui("pc_open", -6.0)


func leave_then(cb: Callable) -> void:
	## Step away from the PC, then run cb(player) (e.g. start placing equipment).
	var p := _user
	leave()
	if p:
		await get_tree().create_timer(0.45).timeout
		cb.call(p)


func leave() -> void:
	if _user == null:
		return
	var p := _user
	_user = null
	if _tween:
		_tween.kill()
	_tween = create_tween().set_parallel(true).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_IN_OUT)
	_tween.tween_property(_cam, "global_transform", p.camera.global_transform, 0.4)
	_tween.tween_property(_cam, "fov", p.camera.fov, 0.4)
	_tween.chain().tween_callback(func():
		p.camera.current = true
		p.set_ui_mode(false))
	Sfx.play_ui("pc_close", -8.0)


var _redraw_t := 0.0


func _process(delta: float) -> void:
	if in_use():
		if _vp.render_target_update_mode != SubViewport.UPDATE_ALWAYS:
			_vp.render_target_update_mode = SubViewport.UPDATE_ALWAYS
		return
	if _vp.render_target_update_mode == SubViewport.UPDATE_ALWAYS:
		_vp.render_target_update_mode = SubViewport.UPDATE_ONCE
	_redraw_t -= delta
	if _redraw_t > 0.0:
		return
	var cam := get_viewport().get_camera_3d()
	var near := cam != null and cam.global_position.distance_to(global_position) < 10.0
	_redraw_t = 1.0 if near else 4.0
	if near:
		_vp.render_target_update_mode = SubViewport.UPDATE_ONCE


func in_use() -> bool:
	return _user != null


func _input(event: InputEvent) -> void:
	if _user == null:
		return
	if event.is_action_pressed("pause") or (event is InputEventKey and event.pressed and event.physical_keycode == KEY_TAB):
		if not _os.consume_escape():
			leave()
		get_viewport().set_input_as_handled()
		return
	if event is InputEventMouse:
		var ev := event.duplicate() as InputEventMouse
		var uv := _screen_uv(event.position)
		if uv.x < 0:
			return
		ev.position = uv * Vector2(VP_SIZE)
		ev.global_position = ev.position
		_vp.push_input(ev)
		get_viewport().set_input_as_handled()
	elif event is InputEventKey:
		_vp.push_input(event)
		get_viewport().set_input_as_handled()


func _screen_uv(mouse: Vector2) -> Vector2:
	if _screen == null or not (_screen.mesh is QuadMesh):
		return Vector2(-1, -1)
	var origin := _cam.project_ray_origin(mouse)
	var dir := _cam.project_ray_normal(mouse)
	var plane := Plane(_screen.global_basis.z.normalized(), _screen.global_position)
	var hit: Variant = plane.intersects_ray(origin, dir)
	if hit == null:
		return Vector2(-1, -1)
	var local: Vector3 = _screen.global_transform.affine_inverse() * (hit as Vector3)
	var qs: Vector2 = (_screen.mesh as QuadMesh).size
	var uv := Vector2(local.x / qs.x + 0.5, 0.5 - local.y / qs.y)
	if uv.x < 0 or uv.x > 1 or uv.y < 0 or uv.y > 1:
		return Vector2(-1, -1)
	return uv
