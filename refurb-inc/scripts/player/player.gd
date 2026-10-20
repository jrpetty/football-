class_name Player
extends CharacterBody3D
## First-person player. The local authority gets camera + input; remote players are interpolated avatars.
## Interaction: look at something → hover card. Tap [E] = interact / pick up / place. Hold [E] = timed action
## (repair, grade, pack, unbox, brew). [Q] / right-click = put down what you're carrying.

signal hover_changed(info: Dictionary)
signal hold_changed(progress: float, label: String)

const WALK_SPEED := 3.4
const SPRINT_SPEED := 5.6
const CROUCH_SPEED := 1.7
const ACCEL := 10.0
const DECEL := 13.0
const AIR_CONTROL := 0.35
const JUMP_VELOCITY := 3.9
const STAND_HEIGHT := 1.78
const CROUCH_HEIGHT := 1.15
const EYE_OFFSET := 0.12
const REACH := 2.7
const TAP_TIME := 0.2
const POSE_HZ := 20.0
const TEA_MINUTES := 60.0

var peer_id := 1
var is_local := false
var ui_mode := false                       # office PC / menus: no movement, mouse free

var head: Node3D
var camera: Camera3D
var hold_node: Node3D
var ray: RayCast3D
var shape: CollisionShape3D
var avatar: Node3D
var name_tag: Label3D

var _yaw := 0.0
var _pitch := 0.0
var _gravity: float = ProjectSettings.get_setting("physics/3d/default_gravity", 9.8)
var _bob_t := 0.0
var _step_accum := 0.0
var _crouch := 0.0
var _land_dip := 0.0
var _was_on_floor := true
var _fov_base := 75.0

var _hover_node: Node = null
var _hover_info: Dictionary = {}
var _hover_hit := Vector3.ZERO
var _place_slot: SnapSlot = null
var _press_t := -1.0
var _hold_progress := 0.0
var _hold_consumed := false
var _hold_target: Node = null
var _pose_accum := 0.0
var _net_pos := Vector3.ZERO
var _net_yaw := 0.0
var _net_pitch := 0.0
var _tea_until := -1.0


func _ready() -> void:
	peer_id = get_multiplayer_authority()
	is_local = peer_id == Net.local_peer()
	collision_layer = 1 << 1
	collision_mask = 1 | (1 << 4)
	floor_snap_length = 0.3
	floor_max_angle = deg_to_rad(50)
	shape = CollisionShape3D.new()
	var cap := CapsuleShape3D.new()
	cap.radius = 0.3
	cap.height = STAND_HEIGHT
	shape.shape = cap
	shape.position.y = STAND_HEIGHT / 2
	add_child(shape)
	head = Node3D.new()
	head.name = "Head"
	head.position.y = STAND_HEIGHT - EYE_OFFSET
	add_child(head)
	camera = Camera3D.new()
	camera.name = "Camera"
	camera.near = 0.03
	camera.far = 400.0
	head.add_child(camera)
	hold_node = Node3D.new()
	hold_node.name = "HoldPoint"
	head.add_child(hold_node)
	_yaw = rotation.y
	rotation.y = 0.0
	if is_local:
		camera.current = true
		hold_node.position = Vector3(0.16, -0.4, -0.52)
		hold_node.rotation_degrees = Vector3(8, -12, 0)
		ray = RayCast3D.new()
		ray.target_position = Vector3(0, 0, -REACH)
		ray.collision_mask = 1 | (1 << 2) | (1 << 3)
		ray.add_exception(self)
		ray.collide_with_areas = false
		camera.add_child(ray)
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
		Settings.settings_changed.connect(_apply_settings)
		_apply_settings()
		Game.command_result.connect(_on_command_result)
	else:
		hold_node.position = Vector3(0.0, -0.45, -0.45)
		avatar = Placeholders.build("person", {"color": _peer_colour()})
		avatar.rotation_degrees.y = 180
		add_child(avatar)
		name_tag = Label3D.new()
		name_tag.text = Net.display_name(peer_id)
		name_tag.billboard = BaseMaterial3D.BILLBOARD_ENABLED
		name_tag.position.y = 2.0
		name_tag.font_size = 48
		name_tag.pixel_size = 0.004
		name_tag.outline_size = 10
		add_child(name_tag)
	_net_pos = global_position
	_apply_look()


func _peer_colour() -> Color:
	var cols := [Color(0.2, 0.35, 0.6), Color(0.6, 0.2, 0.2), Color(0.2, 0.55, 0.3), Color(0.55, 0.4, 0.15)]
	return cols[(peer_id % 7) % cols.size()]


func _apply_settings() -> void:
	_fov_base = float(Settings.get_value("fov"))
	camera.fov = _fov_base


func hold_point() -> Node3D:
	return hold_node


# ================================================================= input

func _unhandled_input(event: InputEvent) -> void:
	if not is_local or ui_mode:
		return
	if event is InputEventMouseMotion and Input.mouse_mode == Input.MOUSE_MODE_CAPTURED:
		var sens := float(Settings.get_value("mouse_sensitivity")) * 0.01
		_yaw -= event.relative.x * sens
		var inv := -1.0 if Settings.get_value("invert_y") else 1.0
		_pitch = clampf(_pitch - event.relative.y * sens * inv, deg_to_rad(-88), deg_to_rad(88))
		_apply_look()
	elif event.is_action_pressed("interact"):
		_press_t = 0.0
		_hold_consumed = false
		_hold_progress = 0.0
		_hold_target = _hover_node
	elif event.is_action_released("interact"):
		if _press_t >= 0.0 and _press_t < TAP_TIME and not _hold_consumed:
			_tap()
		_cancel_hold()
		_press_t = -1.0
	elif event.is_action_pressed("drop"):
		_drop()
	elif event.is_action_pressed("jump") and is_on_floor():
		velocity.y = JUMP_VELOCITY


func _apply_look() -> void:
	rotation.y = _yaw
	head.rotation.x = _pitch


func set_ui_mode(on: bool) -> void:
	ui_mode = on
	if is_local:
		Input.mouse_mode = Input.MOUSE_MODE_VISIBLE if on else Input.MOUSE_MODE_CAPTURED
		_cancel_hold()
		_press_t = -1.0
		if on:
			_set_hover(null, {})


# ================================================================= movement

func _physics_process(delta: float) -> void:
	if not is_local:
		_remote_update(delta)
		return
	var on_floor := is_on_floor()
	if not on_floor:
		velocity.y -= _gravity * delta
	var input := Vector2.ZERO
	if not ui_mode:
		input = Input.get_vector("move_left", "move_right", "move_forward", "move_back")
	var crouching := not ui_mode and Input.is_action_pressed("crouch")
	_crouch = move_toward(_crouch, 1.0 if crouching else 0.0, delta * 6.0)
	var sprinting := not crouching and not ui_mode and Input.is_action_pressed("sprint") and input.y < 0.1
	var speed := CROUCH_SPEED if crouching else (SPRINT_SPEED if sprinting else WALK_SPEED)
	speed *= _carry_speed_mult() * (1.12 if has_tea() else 1.0)
	var wish := (transform.basis * Vector3(input.x, 0, input.y))
	wish.y = 0
	wish = wish.normalized() * speed * minf(input.length(), 1.0)
	var horiz := Vector3(velocity.x, 0, velocity.z)
	var rate := (ACCEL if wish.length() > 0.01 else DECEL) * (1.0 if on_floor else AIR_CONTROL)
	horiz = horiz.move_toward(wish, rate * speed * delta)
	velocity.x = horiz.x
	velocity.z = horiz.z
	move_and_slide()
	# Landing dip
	if on_floor and not _was_on_floor and velocity.y <= 0.0:
		_land_dip = 0.08
		Sfx.play_at("footstep", global_position, -8.0)
	_was_on_floor = on_floor
	_update_head(delta, horiz.length(), sprinting, on_floor)
	_update_interaction(delta)
	_send_pose(delta)


func _carry_speed_mult() -> float:
	var n := Game.state.held_by(peer_id).size()
	return 1.0 - 0.05 * n


func _update_head(delta: float, hspeed: float, sprinting: bool, on_floor: bool) -> void:
	var eye := lerpf(STAND_HEIGHT, CROUCH_HEIGHT, _crouch) - EYE_OFFSET
	(shape.shape as CapsuleShape3D).height = lerpf(STAND_HEIGHT, CROUCH_HEIGHT, _crouch)
	shape.position.y = (shape.shape as CapsuleShape3D).height / 2
	_land_dip = move_toward(_land_dip, 0.0, delta * 0.5)
	var bob := Vector3.ZERO
	if on_floor and hspeed > 0.3:
		_bob_t += delta * hspeed * 2.1
		if Settings.get_value("head_bob"):
			var s := float(Settings.get_value("head_bob_strength"))
			bob = Vector3(cos(_bob_t * 0.5) * 0.025, abs(sin(_bob_t * 0.5)) * 0.035, 0) * s * clampf(hspeed / WALK_SPEED, 0.0, 1.4)
		_step_accum += hspeed * delta
		var stride := 0.75 if sprinting else 0.62
		if _step_accum >= stride:
			_step_accum = 0.0
			Sfx.play_at("footstep", global_position, -14.0 if not sprinting else -10.0, 0.12)
	else:
		_bob_t = lerpf(_bob_t, roundf(_bob_t / PI) * PI, delta * 4.0)
	head.position = Vector3(bob.x, eye + bob.y - _land_dip, 0)
	var target_fov := _fov_base + (6.0 if sprinting and hspeed > WALK_SPEED else 0.0)
	camera.fov = lerpf(camera.fov, target_fov, delta * 6.0)


# ================================================================= interaction

func _update_interaction(delta: float) -> void:
	if ui_mode:
		return
	var node: Node = null
	var hit := Vector3.ZERO
	ray.force_raycast_update()
	if ray.is_colliding():
		hit = ray.get_collision_point()
		node = _find_interactable(ray.get_collider())
	var held := Game.state.held_by(peer_id)
	var info: Dictionary = {}
	var place_slot: SnapSlot = null
	if not held.is_empty():
		var top: Item = held.back()
		var host: Node = null
		if node and node.has_method("slot_host_for_placing"):
			host = node.slot_host_for_placing()
		elif node and node.has_method("best_slot_for"):
			host = node
		if host:
			place_slot = host.best_slot_for(top.kind, hit)
			var where: String = host.host_label() if host.has_method("host_label") else "here"
			if place_slot:
				info = {"title": top.display_name(), "lines": ["Carrying %d" % held.size()] if held.size() > 1 else [], "prompt": "[E] Place on %s" % where}
			else:
				info = {"title": top.display_name(), "lines": ["No free space for that on %s." % where], "prompt": "[Q] Put down"}
		else:
			info = {"title": top.display_name(), "lines": ["Carrying %d" % held.size()] if held.size() > 1 else [], "prompt": "[Q] Put down"}
			if node and node.has_method("hover_info"):
				var hi: Dictionary = node.hover_info(self)
				if hi.has("hold") and not hi.hold.has("blocked"):
					info["hold"] = hi.hold
					_hover_node = node
	elif node and node.has_method("hover_info"):
		info = node.hover_info(self)
	_set_place_slot(place_slot)
	_hover_hit = hit
	if node != _hover_node or info.hash() != _hover_info.hash():
		_set_hover(node, info)
	# Hold-to-act
	if _press_t >= 0.0:
		_press_t += delta
		var ha: Dictionary = _hover_info.get("hold", {})
		if _hover_node != _hold_target:
			_cancel_hold()
			_press_t = -1.0
		elif _press_t >= TAP_TIME and not _hold_consumed and ha.has("secs") and not ha.has("blocked"):
			if _hold_progress == 0.0 and _hold_target and _hold_target.has_method("hold_progress"):
				_hold_target.hold_progress(true)
			var host_station := _station_for(_hold_target)
			if host_station and host_station.has_method("hold_progress") and _hold_progress == 0.0:
				host_station.hold_progress(true)
			_hold_progress += delta / maxf(float(ha.secs), 0.05)
			hold_changed.emit(minf(_hold_progress, 1.0), str(ha.get("label", "")))
			if _hold_progress >= 1.0:
				_hold_consumed = true
				var target := _hold_target
				var action: String = ha.get("action", "")
				_cancel_hold()
				if target and target.has_method("hold_complete"):
					target.hold_complete(self, action)


func _station_for(n: Node) -> Node:
	if n is ItemView:
		var it := Game.state.get_item((n as ItemView).item_id)
		if it and it.loc.get("t") == "slot" and LevelRoot.current:
			return LevelRoot.current.host_of(LevelRoot.current.slot_nodes.get(it.loc.slot))
	return null


func _cancel_hold() -> void:
	if _hold_progress > 0.0:
		var st := _station_for(_hold_target)
		if st and st.has_method("hold_progress"):
			st.hold_progress(false)
		if _hold_target and is_instance_valid(_hold_target) and _hold_target.has_method("hold_progress"):
			_hold_target.hold_progress(false)
	_hold_progress = 0.0
	hold_changed.emit(0.0, "")


func _set_hover(node: Node, info: Dictionary) -> void:
	_hover_node = node
	_hover_info = info
	hover_changed.emit(info)


func _set_place_slot(s: SnapSlot) -> void:
	if s == _place_slot:
		return
	if _place_slot and is_instance_valid(_place_slot):
		_place_slot.show_hint(false)
	_place_slot = s
	if s:
		s.show_hint(true)


func _find_interactable(collider: Object) -> Node:
	var n := collider as Node
	while n:
		if n.has_method("hover_info") or n.has_method("best_slot_for"):
			return n
		n = n.get_parent()
	return null


func _tap() -> void:
	var held := Game.state.held_by(peer_id)
	if not held.is_empty():
		if _place_slot:
			Game.request("place", {"slot": _place_slot.full_id()})
			Sfx.play_at("place", _place_slot.global_position, -4.0)
		return
	if _hover_node and _hover_node.has_method("interact"):
		_hover_node.interact(self)
		if _hover_node is ItemView:
			Sfx.play_at("pickup", (_hover_node as Node3D).global_position, -6.0)


func _drop() -> void:
	var held := Game.state.held_by(peer_id)
	if held.is_empty():
		return
	var pos: Vector3
	if ray.is_colliding() and ray.get_collision_normal().y > 0.6:
		pos = ray.get_collision_point() + Vector3(0, 0.02, 0)
	else:
		var fwd := -global_transform.basis.z
		fwd.y = 0
		pos = global_position + fwd.normalized() * 0.7 + Vector3(0, 0.6, 0)
	var q := Quaternion(Vector3.UP, _yaw)
	Game.request("drop", {"p": [pos.x, pos.y, pos.z], "r": [q.x, q.y, q.z, q.w]})
	Sfx.play_at("place", pos, -6.0)


func _on_command_result(cmd: String, res: Dictionary) -> void:
	if not res.get("ok", false) and cmd in ["place", "pick_up"]:
		Sfx.play_ui("error", -6.0)


# ================================================================= tea

func has_tea() -> bool:
	return _tea_until > Game.state.minute


func give_tea() -> void:
	_tea_until = Game.state.minute + TEA_MINUTES
	Game.toast.emit("Lovely brew. You feel sharper for the next hour.", "good")


# ================================================================= networking

func _send_pose(delta: float) -> void:
	if not Net.is_online():
		return
	_pose_accum += delta
	if _pose_accum < 1.0 / POSE_HZ:
		return
	_pose_accum = 0.0
	_rpc_pose.rpc(global_position, _yaw, _pitch, _crouch)


@rpc("authority", "call_remote", "unreliable_ordered")
func _rpc_pose(pos: Vector3, yaw: float, pitch: float, crouch: float) -> void:
	_net_pos = pos
	_net_yaw = yaw
	_net_pitch = pitch
	_crouch = crouch


func _remote_update(delta: float) -> void:
	global_position = global_position.lerp(_net_pos, clampf(delta * 14.0, 0, 1))
	_yaw = lerp_angle(_yaw, _net_yaw, clampf(delta * 14.0, 0, 1))
	_pitch = lerpf(_pitch, _net_pitch, clampf(delta * 14.0, 0, 1))
	_apply_look()
	head.position.y = lerpf(STAND_HEIGHT, CROUCH_HEIGHT, _crouch) - EYE_OFFSET
	if name_tag:
		name_tag.text = Net.display_name(peer_id)
