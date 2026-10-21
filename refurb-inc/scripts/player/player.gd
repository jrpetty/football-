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
const AIM_ASSIST_DEG := 9.0
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
var _drink_pitch := 0.0      # head tips back while sipping (added to the look pitch)
var _drinking := false

# Placement mode (moving / installing equipment)
var placing_id := ""
var _ghost: Node3D
var _ghost_mat: StandardMaterial3D
var _ghost_rot := 0.0
var _ghost_pos := Vector3.ZERO
var _ghost_valid := false
var _guides: Node3D = null            # automation layout guides while placing (open ends, arm reach)
var _bend := {}                       # flexible bend being placed: {angle, radius, turn}
var _bend_vis: Node3D = null
var _snapped := {}

# Forklift driving (scripts/player/player.gd § forklift): the player node IS the driver's seat while driving.
const FL_MAX_FWD := 3.2
const FL_MAX_REV := 2.2
const FL_ACCEL := 2.4
const FL_BRAKE := 4.5
const FL_STEER_MAX := deg_to_rad(58.0)
const FL_LIFT_SPEED := 0.5
var fork_h := 0.0                     # fork height (m): live for the driver, smoothed from the network for others
var _net_fork := -1.0
var _drive_id := 0                    # forklift item id we're sat in (0 = on foot)
var _drive_v := 0.0
var _steer := 0.0
var _look_yaw := 0.0                  # head turned relative to the truck
var _fork_target := 0.0
var _fork_wait := 0.0                 # waiting for the host to confirm a lift / set-down
var _fork_held := false
var _seat_blend := 0.0
var _seat_from := Vector3.ZERO
var _drive_hint := ""
var _remote_fl_t := 0.0
var _remote_fl := 0


func _ready() -> void:
	peer_id = get_multiplayer_authority()
	is_local = peer_id == Net.local_peer()
	process_physics_priority = -10        # move before the things that follow us (carried items, vehicles)
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
		var rig := CharacterRig.new()
		rig.shirt = _peer_colour()
		rig.hair = [Color(0.2, 0.14, 0.1), Color(0.55, 0.38, 0.2), Color(0.08, 0.07, 0.07), Color(0.7, 0.6, 0.45)][peer_id % 4]
		avatar = rig
		avatar.rotation_degrees.y = 180
		add_child(avatar)
		_apply_remote_look(peer_id)
		Game.looks_changed.connect(_apply_remote_look)
		Net.avatar_changed.connect(_apply_remote_look)
		Net.peers_changed.connect(func():
			if is_instance_valid(name_tag):
				name_tag.text = Net.display_name(peer_id))
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
	if placing_id != "":
		_placement_input(event)
		return
	if _drive_id != 0:
		_drive_input(event)
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
		# A press counts as a tap unless a hold action actually got going — so a relaxed press of E
		# still picks things up; only targets with a hold (repair, grade, pack…) need a quick tap.
		if _press_t >= 0.0 and not _hold_consumed and (_press_t < TAP_TIME or _hold_progress <= 0.0):
			_tap()
		_cancel_hold()
		_press_t = -1.0
	elif event.is_action_pressed("drop"):
		_drop()
	elif event.is_action_pressed("move_fixture") and _hover_node and LevelRoot.current:
		var fid := LevelRoot.current.fixture_at(_hover_node)
		if fid != "" and Game.state.held_by(peer_id).is_empty():
			start_placement(fid)
	elif event.is_action_pressed("jump") and is_on_floor():
		velocity.y = JUMP_VELOCITY


func _apply_look() -> void:
	rotation.y = _yaw
	head.rotation.x = _pitch + _drink_pitch


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
	if global_position.y < -12.0 and LevelRoot.current:
		# Safety net: never fall out of the world.
		global_position = LevelRoot.current.respawn_point()
		velocity = Vector3.ZERO
	if _check_driving():
		_drive_physics(delta)
		_send_pose(delta)
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
	if my_truck() and _truck_blocked(Vector3(velocity.x, 0, velocity.z) * delta * 3.0):
		# The forks/pallet would hit something: stop the part of the motion that drives into it.
		var fwd := -global_transform.basis.z
		var along := Vector3(velocity.x, 0, velocity.z).dot(fwd)
		if along > 0.0:
			velocity.x -= fwd.x * along
			velocity.z -= fwd.z * along
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
	var truck := my_truck()
	if truck:
		# Pallet trucks are slower than walking, slower again with a load; Pallet pro (Hauling 4) helps.
		var m := 0.82 if Game.sim == null or Game.sim.truck_load(truck) == null else 0.6
		if Game.state.has_perk(Sim.player_key(peer_id), "pallet_pro"):
			m *= 1.3
		return minf(m, 1.0)
	var n := Game.state.held_by(peer_id).size()
	return 1.0 - 0.05 * n


func my_truck() -> Item:
	for it: Item in Game.state.items.values():
		if it.kind == Item.KIND_PALLET_TRUCK and it.loc.get("t") == "pushed" and int(it.loc.get("peer", 0)) == peer_id:
			return it
	return null


func _truck_load_item(truck: Item) -> Item:
	for it: Item in Game.state.items.values():
		if it.kind == Item.KIND_PALLET and it.loc.get("t") == "on_truck" and int(it.loc.get("truck", 0)) == truck.id:
			return it
	return null


func _fork_centre() -> Vector3:
	# World position of the fork centre of the truck we're pushing.
	return (global_transform * ItemView.PUSHED * ItemView.ON_FORKS).origin


func _truck_target_pallet() -> ItemView:
	## The loose pallet the forks are under (close to the fork centre), if any.
	var lr := LevelRoot.current
	if lr == null:
		return null
	var fc := _fork_centre()
	var best: ItemView = null
	var best_d := 0.75
	for v: ItemView in lr.item_views.values():
		if not is_instance_valid(v) or v.kind != Item.KIND_PALLET or not v.visible:
			continue
		var it := Game.state.get_item(v.item_id)
		if it == null:
			continue
		if it.loc.get("t") == "slot":
			# A pallet in the floor-level bay of racking / an unload bay: the truck reaches it.
			var sid := str(it.loc.get("slot", ""))
			var f: Dictionary = Game.state.fixtures.get(sid.get_slice(":", 0), {})
			if Logistics.slot_world(Game.state, sid) == null or Logistics.level_height(str(f.get("type", "")), sid.get_slice(":", 1)) > Logistics.TRUCK_MAX_LEVEL:
				continue
		elif it.loc.get("t") != "loose":
			continue
		var pc := v.global_position
		var d := Vector2(pc.x - fc.x, pc.z - fc.z).length()
		if d < best_d and absf(pc.y - global_position.y) < 0.6:
			best_d = d
			best = v
	return best


func _truck_blocked(motion: Vector3) -> bool:
	## Would the truck (and its pallet) hit a wall, shelf or bench if we moved by `motion`?
	var truck := my_truck()
	if truck == null or motion.length() < 0.0001:
		return false
	var loaded := _truck_load_item(truck) != null
	var shape := BoxShape3D.new()
	shape.size = Vector3(0.82 if loaded else 0.55, 0.32, 1.25 if loaded else 1.0)
	var q := PhysicsShapeQueryParameters3D.new()
	q.shape = shape
	q.collision_mask = 1
	var xf := global_transform * ItemView.PUSHED
	xf.origin += motion + Vector3(0, 0.28, 0) + xf.basis.z.normalized() * (0.22 if loaded else 0.25)
	q.transform = xf
	q.exclude = [get_rid()]
	return not get_world_3d().direct_space_state.intersect_shape(q, 1).is_empty()


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
	if _seat_blend > 0.0:
		# Just climbed out of a forklift: the view eases down from the seat.
		_seat_blend = maxf(0.0, _seat_blend - delta / 0.45)
		head.position = head.position.lerp(_seat_from, ease(_seat_blend, 2.0))
	var target_fov := _fov_base + (6.0 if sprinting and hspeed > WALK_SPEED else 0.0)
	camera.fov = lerpf(camera.fov, target_fov, delta * 6.0)


# ================================================================= interaction

func _update_interaction(delta: float) -> void:
	if ui_mode:
		return
	if placing_id != "":
		_update_placement()
		return
	var node: Node = null
	var hit := Vector3.ZERO
	ray.force_raycast_update()
	if ray.is_colliding():
		hit = ray.get_collision_point()
		node = _find_interactable(ray.get_collider())
	var held := Game.state.held_by(peer_id)
	var truck_now := my_truck()
	if truck_now:
		var loaded := _truck_load_item(truck_now)
		var tinfo := {"title": "Pallet truck", "lines": [], "prompt": "[Q] Let go"}
		if loaded:
			tinfo.lines = ["Carrying %s" % loaded.display_name()]
			tinfo.prompt = "[E] Lower pallet · [Q] Let go"
		elif _truck_target_pallet():
			tinfo.lines = ["Forks are under a pallet."]
			tinfo.prompt = "[E] Jack it up · [Q] Let go"
		else:
			tinfo.lines = ["Line the forks up under a pallet."]
		_set_place_slot(null)
		if tinfo.hash() != _hover_info.hash():
			_set_hover(null, tinfo)
		return
	# Empty-handed and looking at a shelf/bench: target the item in the nearest space, not the furniture.
	if held.is_empty() and node and node.has_method("item_view_near"):
		var near: ItemView = node.item_view_near(hit)
		if near:
			node = near
	# Look-to-pick: with empty hands, anything carryable close to the crosshair is targeted, so you don't
	# have to line up exactly on a laptop on a shelf or pallet (stations like the PC keep their focus).
	if held.is_empty() and (node == null or node.has_method("item_view_near") or (node is ItemView and (node as ItemView).kind == Item.KIND_PALLET)):
		var assist := _aim_assist_item(hit if ray.is_colliding() else Vector3.INF)
		if assist:
			node = assist
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
			place_slot = host.best_slot_for(top.slot_kind(), hit)
			var where: String = host.host_label() if host.has_method("host_label") else "here"
			if place_slot:
				info = {"title": top.display_name(), "lines": ["Carrying %d" % held.size()] if held.size() > 1 else [], "prompt": "[E] Place on %s" % where}
			elif top.is_large() and host.best_slot_for(top.kind, hit) != null:
				info = {"title": top.display_name(), "lines": ["Too big for %s — use heavy-duty racking (Shop → Equipment)." % where], "prompt": "[Q] Put down"}
			else:
				info = {"title": top.display_name(), "lines": ["No free space for that on %s." % where], "prompt": "[Q] Put down"}
		else:
			info = {"title": top.display_name(), "lines": ["Carrying %d" % held.size()] if held.size() > 1 else [], "prompt": "[Q] Put down"}
			if top.kind == Item.KIND_MUG:
				var mate := _give_target()
				_give_to = mate.peer_id if mate else 0
				if mate:
					info.prompt = "[E] Give %s this brew  ·  [Q] Put down" % Net.display_name(mate.peer_id)
				elif float(top.data.get("fill", 0.0)) > 0.05:
					info.prompt = "[E] Drink  ·  [Q] Put down"
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


func _aim_assist_item(ray_hit: Vector3) -> ItemView:
	var lr := LevelRoot.current
	if lr == null or camera == null:
		return null
	var origin := camera.global_position
	var fwd := -camera.global_basis.z
	var hit_d := origin.distance_to(ray_hit) if ray_hit != Vector3.INF else INF
	var best: ItemView = null
	var best_ang := deg_to_rad(AIM_ASSIST_DEG)
	for v: ItemView in lr.item_views.values():
		if not is_instance_valid(v) or not v.visible or v.kind == Item.KIND_PALLET:
			continue
		var it := Game.state.get_item(v.item_id)
		if it == null or not it.is_carryable() or it.loc.get("t") == "held":
			continue
		var centre := v.global_position + Vector3(0, v.visual_size.y * 0.5, 0)
		var to := centre - origin
		var dist := to.length()
		if dist > REACH + 0.3 or dist < 0.05 or hit_d < dist - 0.6:     # out of reach, or behind a wall
			continue
		var ang := fwd.angle_to(to / dist)
		if ang < best_ang:
			best_ang = ang
			best = v
	return best


func _find_interactable(collider: Object) -> Node:
	var n := collider as Node
	while n:
		if n.has_method("hover_info") or n.has_method("best_slot_for"):
			return n
		n = n.get_parent()
	return null


func _tap() -> void:
	var truck := my_truck()
	if truck:
		var load_it := _truck_load_item(truck)
		if load_it:
			var v: ItemView = LevelRoot.current.item_views.get(load_it.id)
			var xf := v.global_transform if v else global_transform
			var q := xf.basis.get_rotation_quaternion()
			var args := {"p": [xf.origin.x, maxf(xf.origin.y - 0.035, 0.0), xf.origin.z], "r": [q.x, q.y, q.z, q.w]}
			# Wheeled into a floor-level bay (racking or an unload bay)? It drops into the bay.
			var sd := Logistics.find_set_down(Game.state, xf.origin + Vector3(0, 0.05, 0))
			if sd.has("slot") and float(sd.h) <= Logistics.TRUCK_MAX_LEVEL:
				var so: Vector3 = (sd.xf as Transform3D).origin
				args = {"slot": sd.slot, "p": [so.x, so.y, so.z]}
			Game.request("truck_lower", args)
			Sfx.play_at("hydraulic", xf.origin, -10.0)
		else:
			var target := _truck_target_pallet()
			if target:
				var lr2 := LevelRoot.current
				var tv2: ItemView = lr2.item_views.get(truck.id) if lr2 else null
				var truck_yaw := tv2.global_basis.get_euler().y if tv2 else rotation.y
				var rel := rad_to_deg(wrapf(target.global_basis.get_euler().y - truck_yaw, -PI, PI))
				Game.request("truck_lift", {"pallet": target.item_id, "yaw": snappedf(rel, 90.0)})
				Sfx.play_at("hydraulic", target.global_position, -10.0)
		return
	var held := Game.state.held_by(peer_id)
	if not held.is_empty():
		if _place_slot:
			Game.request("place", {"slot": _place_slot.full_id()})
			Sfx.play_at("mug_down" if held.back().kind == Item.KIND_MUG else "place", _place_slot.global_position, -4.0)
		elif held.back().kind == Item.KIND_MUG:
			var mate := _give_target()
			if mate:
				Game.request("give_mug", {"to": mate.peer_id})
				Sfx.play_at("mug_down", mate.global_position + Vector3(0, 1.2, 0), -8.0)
			elif float(held.back().data.get("fill", 0.0)) > 0.05:
				drink_tea(held.back())
		return
	if _hover_node and _hover_node.has_method("interact"):
		_hover_node.interact(self)
		if _hover_node is ItemView:
			Sfx.play_at("pickup", (_hover_node as Node3D).global_position, -6.0)


func _drop() -> void:
	var truck := my_truck()
	if truck:
		var lr := LevelRoot.current
		var tv: ItemView = lr.item_views.get(truck.id) if lr else null
		var txf := tv.global_transform if tv else global_transform * ItemView.PUSHED
		var tq := txf.basis.get_rotation_quaternion()
		var args := {"p": [txf.origin.x, maxf(txf.origin.y, 0.0), txf.origin.z], "r": [tq.x, tq.y, tq.z, tq.w]}
		var load_it := _truck_load_item(truck)
		if load_it and lr and lr.item_views.has(load_it.id):
			var pxf: Transform3D = lr.item_views[load_it.id].global_transform
			var pq := pxf.basis.get_rotation_quaternion()
			args["pallet_p"] = [pxf.origin.x, maxf(pxf.origin.y - 0.035, 0.0), pxf.origin.z]
			args["pallet_r"] = [pq.x, pq.y, pq.z, pq.w]
		Game.request("release_truck", args)
		return
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

var _give_to := 0


func _apply_remote_look(peer: int) -> void:
	if peer != peer_id or not (avatar is CharacterRig) or not Game.looks.has(peer):
		return
	var look: Dictionary = Game.looks[peer].look
	var face: PackedByteArray = Game.looks[peer].face
	if face.is_empty() and look.get("steamface", false):
		face = Net.avatar_png(peer)        # they chose their Steam profile picture as their face
	Looks.apply(avatar as CharacterRig, look, face)


func _give_target() -> Node3D:
	## The colleague you're looking at (within arm's reach-ish), for handing over a brew.
	var lr := LevelRoot.current
	if lr == null or camera == null:
		return null
	var best: Node3D = null
	var best_d := 2.6
	for p in lr.players.values():
		if p == self or not is_instance_valid(p):
			continue
		var chest: Vector3 = (p as Node3D).global_position + Vector3(0, 1.25, 0)
		var to := chest - camera.global_position
		var d := to.length()
		if d < best_d and (-camera.global_basis.z).dot(to.normalized()) > 0.93:
			best_d = d
			best = p
	return best


func has_tea() -> bool:
	return _tea_until > Game.state.minute


func give_tea(minutes: float = TEA_MINUTES, msg: String = "Lovely brew. You feel sharper for the next hour.") -> void:
	_tea_until = maxf(_tea_until, Game.state.minute + minutes)
	Game.toast.emit(msg, "good")


func drink_tea(mug_item: Item = null) -> void:
	## First-person tea break with the actual mug in your hand: it comes up into view, tilts to your lips
	## (head tips back a touch), three sips as the level drops, an "ahh", then it's lowered. Co-op players
	## see your avatar drink; a brew a colleague made you lasts longer.
	if _drinking:
		return
	_drinking = true
	var minutes := TEA_MINUTES
	var msg := "Lovely brew. You feel sharper for the next hour."
	var c := 0
	var sl := 0
	var start_fill := 0.86
	var colour := TeaMug.TEA
	if mug_item:
		var d := mug_item.data
		var now := Game.state.belt_now(Game.is_host())
		c = int(d.get("c", 0))
		sl = int(d.get("s", 0))
		start_fill = TeaMug.shown_fill(d, now)
		colour = TeaMug.brew_colour(d, now)
		var by := int(d.get("by", 0))
		if by != 0 and by != peer_id:
			minutes = TEA_MINUTES * 1.5
			msg = "A brew made by %s. Proper teamwork — you're flying for 90 minutes." % Net.display_name(by)
		elif not d.get("milk", false):
			msg = "Black tea. Bold. You feel sharper for the next hour."
		if now - float(d.get("t", now)) > 420.0:
			minutes *= 0.5
			msg = "Stone cold. You drink it anyway. Half the effect."
		Game.request("drink_mug", {})
	if is_local and multiplayer.has_multiplayer_peer():
		_rpc_drink.rpc()
	if not is_local or camera == null:
		_drinking = false
		give_tea(minutes, msg)
		return
	var held_view: ItemView = LevelRoot.current.item_views.get(mug_item.id) if mug_item and LevelRoot.current else null
	if held_view:
		held_view.visible = false
	var mug := TeaMug.make(c, sl, true)
	mug.level_liquid = true
	mug.show_state(start_fill, colour, true, false)
	var level := {"v": start_fill}
	camera.add_child(mug)
	var rest := Vector3(0.34, -0.46, -0.42)
	mug.position = rest
	mug.rotation_degrees = Vector3(8, -20, -6)
	var tw := create_tween()
	tw.set_parallel(true)
	tw.tween_property(mug, "position", Vector3(0.07, -0.14, -0.28), 0.75).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
	tw.tween_property(mug, "rotation_degrees", Vector3(4, -8, 4), 0.75).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
	await tw.finished
	# Tip it to the lips.
	var tw2 := create_tween()
	tw2.set_parallel(true)
	tw2.tween_property(mug, "position", Vector3(0.05, -0.115, -0.2), 0.55).set_trans(Tween.TRANS_SINE)
	tw2.tween_property(mug, "rotation_degrees", Vector3(38, -14, 6), 0.55).set_trans(Tween.TRANS_SINE)
	tw2.tween_property(self, "_drink_pitch", deg_to_rad(13.0), 0.55).set_trans(Tween.TRANS_SINE)
	await tw2.finished
	for sip in 3:
		Sfx.play_at("tea_sip", camera.global_position, -4.0 - sip * 1.5)
		var tws := create_tween()
		tws.tween_method(func(v: float):
			level.v = v
			mug.show_state(v, colour, true, false), level.v, start_fill * (1.0 - (sip + 1) / 3.0), 0.55)
		tws.parallel().tween_property(mug, "rotation_degrees:x", 38.0 + (sip + 1) * 12.0, 0.55).set_trans(Tween.TRANS_SINE)
		tws.parallel().tween_property(self, "_drink_pitch", deg_to_rad(13.0 + (sip + 1) * 3.0), 0.55)
		await tws.finished
		await get_tree().create_timer(0.15).timeout
	give_tea(minutes, msg)
	# Down again.
	var tw3 := create_tween()
	tw3.set_parallel(true)
	tw3.tween_property(mug, "position", Vector3(0.07, -0.15, -0.3), 0.5).set_trans(Tween.TRANS_SINE)
	tw3.tween_property(mug, "rotation_degrees", Vector3(2, -10, 4), 0.5).set_trans(Tween.TRANS_SINE)
	tw3.tween_property(self, "_drink_pitch", 0.0, 0.5).set_trans(Tween.TRANS_SINE)
	await tw3.finished
	Sfx.play_at("tea_ahh", camera.global_position, -6.0)
	await get_tree().create_timer(0.5).timeout
	var tw4 := create_tween()
	tw4.set_parallel(true)
	tw4.tween_property(mug, "position", rest + Vector3(0.05, -0.15, 0.0), 0.6).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_IN)
	tw4.tween_property(mug, "rotation_degrees", Vector3(10, -25, -10), 0.6)
	await tw4.finished
	mug.queue_free()
	if is_instance_valid(held_view):
		held_view.visible = true
	_drinking = false


@rpc("authority", "call_remote", "reliable")
func _rpc_drink() -> void:
	if avatar is CharacterRig:
		(avatar as CharacterRig).play_drink()


func _make_mug() -> Node3D:
	## A proper Refurb Inc mug: tapered white body, orange band, handle, a brew inside and a wisp of steam.
	var mug := Node3D.new()
	mug.name = "TeaMug"
	var body := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = 0.043
	cm.bottom_radius = 0.038
	cm.height = 0.1
	cm.radial_segments = 28
	body.mesh = cm
	body.material_override = Mats.color(Color(0.96, 0.95, 0.93), 0.25)
	body.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	mug.add_child(body)
	var band := MeshInstance3D.new()
	var bc := CylinderMesh.new()
	bc.top_radius = 0.0425
	bc.bottom_radius = 0.0415
	bc.height = 0.022
	bc.radial_segments = 28
	band.mesh = bc
	band.position.y = 0.012
	band.material_override = Mats.color(Color(0.95, 0.5, 0.12), 0.35)
	mug.add_child(band)
	var tea := MeshInstance3D.new()
	var tc := CylinderMesh.new()
	tc.top_radius = 0.039
	tc.bottom_radius = 0.039
	tc.height = 0.004
	tea.mesh = tc
	tea.position.y = 0.04
	tea.material_override = Mats.color(Color(0.42, 0.25, 0.12), 0.08)
	mug.add_child(tea)
	var handle := MeshInstance3D.new()
	var tm := TorusMesh.new()
	tm.inner_radius = 0.018
	tm.outer_radius = 0.028
	tm.rings = 16
	tm.ring_segments = 8
	handle.mesh = tm
	handle.rotation_degrees = Vector3(90, 0, 0)
	handle.position = Vector3(0.046, 0.0, 0)
	handle.material_override = body.material_override
	mug.add_child(handle)
	var logo := Label3D.new()
	logo.text = "REFURB INC."
	logo.font_size = 22
	logo.pixel_size = 0.00045
	logo.modulate = Color(0.1, 0.1, 0.12)
	logo.position = Vector3(0, -0.012, 0.0435)
	mug.add_child(logo)
	var steam := GPUParticles3D.new()
	steam.amount = 14
	steam.lifetime = 1.6
	steam.local_coords = false
	steam.position.y = 0.05
	var pm := ParticleProcessMaterial.new()
	pm.direction = Vector3(0, 1, 0)
	pm.spread = 12.0
	pm.initial_velocity_min = 0.03
	pm.initial_velocity_max = 0.07
	pm.gravity = Vector3(0, 0.02, 0)
	pm.scale_min = 0.6
	pm.scale_max = 1.2
	var grad := Gradient.new()
	grad.set_color(0, Color(1, 1, 1, 0.16))
	grad.set_color(1, Color(1, 1, 1, 0.0))
	var gt := GradientTexture1D.new()
	gt.gradient = grad
	pm.color_ramp = gt
	steam.process_material = pm
	var quad := QuadMesh.new()
	quad.size = Vector2(0.035, 0.035)
	var sm := StandardMaterial3D.new()
	sm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	sm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	sm.vertex_color_use_as_albedo = true
	sm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	# Soft round puff (radial gradient) so the steam reads as wisps, not squares.
	var puff := GradientTexture2D.new()
	puff.fill = GradientTexture2D.FILL_RADIAL
	puff.fill_from = Vector2(0.5, 0.5)
	puff.fill_to = Vector2(0.5, 0.0)
	var pg := Gradient.new()
	pg.set_color(0, Color(1, 1, 1, 1))
	pg.set_color(1, Color(1, 1, 1, 0))
	puff.gradient = pg
	puff.width = 64
	puff.height = 64
	sm.albedo_texture = puff
	quad.material = sm
	steam.draw_pass_1 = quad
	mug.add_child(steam)
	return mug


# ================================================================= networking

func _send_pose(delta: float) -> void:
	if not Net.is_online():
		return
	_pose_accum += delta
	if _pose_accum < 1.0 / POSE_HZ:
		return
	_pose_accum = 0.0
	_rpc_pose.rpc(global_position, _yaw, _pitch, _crouch, fork_h if _drive_id != 0 else -1.0)


@rpc("authority", "call_remote", "unreliable_ordered")
func _rpc_pose(pos: Vector3, yaw: float, pitch: float, crouch: float, fork: float = -1.0) -> void:
	_net_pos = pos
	_net_yaw = yaw
	_net_pitch = pitch
	_crouch = crouch
	_net_fork = fork


func _remote_update(delta: float) -> void:
	var before := global_position
	global_position = global_position.lerp(_net_pos, clampf(delta * 14.0, 0, 1))
	# Which forklift (if any) this player is driving — looked up a few times a second, not every frame.
	_remote_fl_t -= delta
	if _remote_fl_t <= 0.0:
		_remote_fl_t = 0.25
		var fl := _driven_forklift()
		_remote_fl = fl.id if fl else 0
	if _remote_fl != 0 and _net_fork >= 0.0:
		fork_h = lerpf(fork_h, _net_fork, clampf(delta * 12.0, 0, 1))
		_push_fork_height()
	if avatar is CharacterRig:
		var rig := avatar as CharacterRig
		var v := Vector2(global_position.x - before.x, global_position.z - before.z).length() / maxf(delta, 0.001)
		rig.move_speed = lerpf(rig.move_speed, v, clampf(delta * 8.0, 0, 1))
		rig.anim = "carry" if not Game.state.held_by(peer_id).is_empty() else "idle"
		if _remote_fl != 0:
			# Sat in the seat, hands on the wheel.
			rig.anim = "sit"
			rig.move_speed = 0.0
			rig.position = Vector3(0, Logistics.FL_SEAT.y + 0.1 - rig.hip_height(), 0.08)
		else:
			rig.position = Vector3.ZERO
	_yaw = lerp_angle(_yaw, _net_yaw, clampf(delta * 14.0, 0, 1))
	_pitch = lerpf(_pitch, _net_pitch, clampf(delta * 14.0, 0, 1))
	_apply_look()
	head.position.y = lerpf(STAND_HEIGHT, CROUCH_HEIGHT, _crouch) - EYE_OFFSET
	if name_tag:
		name_tag.text = Net.display_name(peer_id)


# ================================================================= forklift
## Get in with [E] on a parked forklift. W/S drive, A/D steer (rear-wheel steering: the back swings out, it turns
## tight), R/F or the mouse wheel raise and lower the forks, H for the horn, E to climb out. Drive the forks under
## a pallet and raise them to pick it up; lower it onto the floor or into a pallet-racking bay it's lined up with.
## The host decides who's driving and where pallets go (forklift_enter / fork_lift / fork_set / forklift_exit);
## the truck moves with the driver like a player does, and everyone sees it through the driver's pose.

var _drive_scan := 0.0
var _exit_to := Vector3.INF


func _driven_forklift() -> Item:
	for it: Item in Game.state.items.values():
		if it.kind == Item.KIND_FORKLIFT and it.loc.get("t") == "driven" and int(it.loc.get("peer", 0)) == peer_id:
			return it
	return null


func is_driving() -> bool:
	return _drive_id != 0


func _fork_load() -> Item:
	for it: Item in Game.state.items.values():
		if it.kind == Item.KIND_PALLET and it.loc.get("t") == "on_forks" and int(it.loc.get("fork", 0)) == _drive_id:
			return it
	return null


func _push_fork_height() -> void:
	## Tell the forklift being driven how high its forks are (the rig moves them; pallets ride the fork point).
	var id := _drive_id if is_local else _remote_fl
	var lr := LevelRoot.current
	var v: ItemView = lr.item_views.get(id) if lr and id != 0 else null
	if v and v.forklift:
		v.forklift.set_fork_height(fork_h)


func _check_driving() -> bool:
	## Notice getting in / out: the host decides, we follow the state.
	if _drive_id != 0:
		var it := Game.state.get_item(_drive_id)
		if it == null or it.loc.get("t") != "driven" or int(it.loc.get("peer", 0)) != peer_id:
			_leave_seat()
			return false
		return true
	_drive_scan -= get_physics_process_delta_time()
	if _drive_scan > 0.0:
		return false
	_drive_scan = 0.12
	var fl := _driven_forklift()
	if fl == null:
		return false
	_take_seat(fl)
	return true


func _take_seat(fl: Item) -> void:
	_drive_id = fl.id
	var p: Array = fl.loc.get("p", [global_position.x, global_position.y, global_position.z])
	var r: Array = fl.loc.get("r", [0, 0, 0, 1])
	var model := Transform3D(Basis(Quaternion(r[0], r[1], r[2], r[3]).normalized()), Vector3(p[0], p[1], p[2]))
	var seat := model * Logistics.drive_xf().affine_inverse()
	var cam_from := camera.global_position
	global_position = seat.origin
	_yaw = seat.basis.get_euler().y
	rotation.y = _yaw
	_look_yaw = 0.0
	_pitch = clampf(_pitch, deg_to_rad(-50), deg_to_rad(25))
	head.rotation = Vector3(_pitch, 0, 0)
	fork_h = float(fl.data.get("h", 0.0))
	_fork_target = fork_h
	_drive_v = 0.0
	_steer = 0.0
	_fork_wait = 0.0
	_exit_to = Vector3.INF
	velocity = Vector3.ZERO
	shape.disabled = true
	_seat_from = to_local(cam_from)
	_seat_blend = 1.0
	_cancel_hold()
	_set_place_slot(null)
	Sfx.play_at("van_door", global_position + Vector3(0, 1.0, 0), -10.0)


func _leave_seat() -> void:
	var cam_from := camera.global_position
	_drive_id = 0
	shape.disabled = false
	if _exit_to != Vector3.INF:
		global_position = _exit_to
	_yaw = rotation.y + _look_yaw
	_look_yaw = 0.0
	rotation.y = _yaw
	head.rotation = Vector3(_pitch, 0, 0)
	velocity = Vector3.ZERO
	_seat_from = to_local(cam_from)
	_seat_blend = 1.0
	_exit_to = Vector3.INF
	_set_hover(null, {})


func _drive_input(event: InputEvent) -> void:
	if event is InputEventMouseMotion and Input.mouse_mode == Input.MOUSE_MODE_CAPTURED:
		var sens := float(Settings.get_value("mouse_sensitivity")) * 0.01
		_look_yaw = clampf(_look_yaw - event.relative.x * sens, deg_to_rad(-140), deg_to_rad(140))
		var inv := -1.0 if Settings.get_value("invert_y") else 1.0
		_pitch = clampf(_pitch - event.relative.y * sens * inv, deg_to_rad(-75), deg_to_rad(60))
		head.rotation = Vector3(_pitch, _look_yaw, 0)
	elif event is InputEventKey and event.is_action_pressed("interact") and not event.echo:
		_climb_out()
	elif event is InputEventMouseButton and event.pressed and event.button_index in [MOUSE_BUTTON_WHEEL_UP, MOUSE_BUTTON_WHEEL_DOWN]:
		_fork_target = clampf(_fork_target + (0.15 if event.button_index == MOUSE_BUTTON_WHEEL_UP else -0.15), 0.0, Logistics.FORK_MAX)
	elif event.is_action_pressed("horn"):
		Sfx.play_at("forklift_horn", global_position + Vector3(0, 1.2, 0), -2.0, 0.02)


func _climb_out() -> void:
	if _drive_v * _drive_v > 0.25:
		Game.toast.emit("Stop first.", "warn")
		return
	var model := global_transform * Logistics.drive_xf()
	var q := model.basis.get_rotation_quaternion()
	_exit_to = _exit_spot()
	Game.request("forklift_exit", {"p": [model.origin.x, model.origin.y, model.origin.z], "r": [q.x, q.y, q.z, q.w], "h": fork_h})
	Sfx.play_at("van_door", global_position + Vector3(0, 1.0, 0), -10.0)


func _exit_spot() -> Vector3:
	## Step down on the left of the seat (or the right, or behind) wherever there's room to stand.
	var space := get_world_3d().direct_space_state
	var cap := CapsuleShape3D.new()
	cap.radius = 0.3
	cap.height = 1.6
	var q := PhysicsShapeQueryParameters3D.new()
	q.shape = cap
	q.collision_mask = 1
	for off in [Vector3(-1.0, 0, 0.1), Vector3(1.0, 0, 0.1), Vector3(0, 0, 2.0), Vector3(-1.2, 0, 1.2)]:
		var p: Vector3 = global_transform * off
		q.transform = Transform3D(Basis.IDENTITY, p + Vector3(0, 0.95, 0))
		if space.intersect_shape(q, 1).is_empty():
			return p
	return global_transform * Vector3(-1.0, 0, 0.1)


func _drive_physics(delta: float) -> void:
	var throttle := 0.0
	var steer_in := 0.0
	var lift_in := 0.0
	if not ui_mode:
		throttle = Input.get_axis("move_back", "move_forward")
		steer_in = Input.get_axis("move_right", "move_left")
		lift_in = Input.get_axis("fork_down", "fork_up")
	var load_it := _fork_load()
	var top := FL_MAX_FWD * (0.8 if load_it else 1.0)
	var target_v := throttle * (top if throttle >= 0.0 else FL_MAX_REV)
	var speeding_up := absf(target_v) > absf(_drive_v) and (signf(target_v) == signf(_drive_v) or is_zero_approx(_drive_v))
	_drive_v = move_toward(_drive_v, target_v, (FL_ACCEL if speeding_up else FL_BRAKE) * delta)
	_steer = move_toward(_steer, steer_in, delta * 2.4)
	var dxf := Logistics.drive_xf()
	var model := global_transform * dxf
	# Rear-wheel steering: the truck pivots about its front (drive) axle and the back swings out.
	var yaw_rate := _drive_v * tan(_steer * FL_STEER_MAX) / Logistics.FL_WHEELBASE
	if absf(_drive_v) > 0.0005 or absf(yaw_rate) > 0.00001:
		var axle_local := Vector3(0, 0, Logistics.FL_FRONT_AXLE_Z)
		var axle := model * axle_local + model.basis.z.normalized() * _drive_v * delta
		var new_yaw := _yaw + yaw_rate * delta
		var mb := Basis(Vector3.UP, new_yaw) * dxf.basis
		var new_model := Transform3D(mb, axle - mb * axle_local)
		# Blocked unless we're already touching it (a pallet just set down can settle against the forks' face):
		# then any move is allowed, so you can always back away.
		if _forklift_blocked(new_model, load_it) and not _forklift_blocked(model, load_it):
			if absf(_drive_v) > 1.2:
				Sfx.play_at("impact_heavy", model.origin + Vector3(0, 0.6, 0), -4.0)
			_drive_v = 0.0
		else:
			var seat := new_model * dxf.affine_inverse()
			var gy := _ground_y(new_model, seat.origin.y)
			global_position = Vector3(seat.origin.x, lerpf(global_position.y, gy, clampf(delta * 10.0, 0.0, 1.0)), seat.origin.z)
			_yaw = new_yaw
			rotation.y = _yaw
			model = global_transform * dxf
	_drive_forks(delta, lift_in, model, load_it)
	_push_fork_height()
	# Camera: settle into the seat (or out of it) smoothly; a little bounce with the truck.
	var eye := Vector3(0, Logistics.FL_EYE, 0)
	if _seat_blend > 0.0:
		_seat_blend = maxf(0.0, _seat_blend - delta / 0.45)
		head.position = eye.lerp(_seat_from, ease(_seat_blend, 2.0))
	else:
		head.position = eye + Vector3(0, sin(Time.get_ticks_msec() * 0.018) * 0.004 * clampf(absf(_drive_v) / 3.0, 0.0, 1.0), 0)
	camera.fov = lerpf(camera.fov, _fov_base, delta * 6.0)
	_update_drive_hud(load_it)


func _drive_forks(delta: float, lift_in: float, model: Transform3D, load_it: Item) -> void:
	var rate := FL_LIFT_SPEED * (0.75 if load_it else 1.0)
	if lift_in != 0.0:
		# Held R/F: the forks move while it's held and stop the moment it's let go (the wheel nudges instead).
		_fork_target = clampf(fork_h + lift_in * rate * delta * 1.01, 0.0, Logistics.FORK_MAX)
		_fork_held = true
	elif _fork_held:
		_fork_held = false
		_fork_target = fork_h
	var new_h := move_toward(fork_h, _fork_target, rate * delta)
	var lowering := _fork_target < fork_h - 0.0005
	var raising := _fork_target > fork_h + 0.0005
	_fork_wait = maxf(0.0, _fork_wait - delta)
	var ground := global_position.y
	_drive_hint = ""
	if load_it == null:
		var tgt := _fork_target_pallet(model)
		if not tgt.is_empty():
			var base: float = float(tgt.base) - ground
			_drive_hint = "Forks under the pallet — raise them to lift it"
			if raising and new_h >= base + 0.02:
				if _fork_wait <= 0.0:
					var o: Vector3 = tgt.origin
					Game.request("fork_lift", {"pallet": tgt.id, "at": [o.x, o.y, o.z], "yaw": tgt.yaw, "h": base})
					Sfx.play_at("hydraulic", o + Vector3(0, 0.3, 0), -10.0)
					_fork_wait = 0.8
				new_h = minf(new_h, base + 0.03)        # until the host says it's on the forks
	else:
		var yaw := float(load_it.loc.get("yaw", 0.0))
		var pal := model * Logistics.pallet_on_forks(new_h, yaw)
		var sd := Logistics.find_set_down(Game.state, pal.origin + Vector3(0, ground - pal.origin.y + new_h, 0))
		if sd.has("slot"):
			var hs: float = float(sd.h) - ground
			_drive_hint = "Lined up with %s — lower to set it down" % Logistics.slot_label(Game.state, str(sd.slot))
			if new_h <= hs + 0.004:
				new_h = maxf(new_h, hs)
				if lowering and _fork_wait <= 0.0:
					var so: Vector3 = (sd.xf as Transform3D).origin
					Game.request("fork_set", {"slot": sd.slot, "p": [so.x, so.y, so.z], "h": hs})
					Sfx.play_at("pallet_drop", so, -8.0)
					_fork_wait = 0.8
		elif sd.has("blocked"):
			var hb: float = float(sd.blocked) - ground
			if new_h < hb:
				new_h = maxf(new_h, minf(hb, fork_h))
				_drive_hint = str(sd.why)
		elif new_h <= 0.004:
			new_h = 0.0
			if lowering and _fork_wait <= 0.0:
				var q := pal.basis.get_rotation_quaternion()
				Game.request("fork_set", {"p": [pal.origin.x, ground, pal.origin.z], "r": [q.x, q.y, q.z, q.w], "h": 0.0})
				Sfx.play_at("pallet_drop", pal.origin, -8.0)
				_fork_wait = 0.8
		if _fork_wait > 0.0 and lowering:
			new_h = maxf(new_h, fork_h - 0.001)     # hold it there until the host has taken it off the forks
	fork_h = clampf(new_h, 0.0, Logistics.FORK_MAX)
	if absf(fork_h - _fork_target) < 0.0005 and lift_in == 0.0:
		_fork_target = fork_h


func _fork_target_pallet(model: Transform3D) -> Dictionary:
	## The pallet the forks are under right now (lined up, at the right height), if any.
	var lr := LevelRoot.current
	if lr == null:
		return {}
	var inv := model.affine_inverse()
	var ground := global_position.y
	var best := {}
	var best_d := INF
	var my_yaw := model.basis.get_euler().y
	for v: ItemView in lr.item_views.values():
		if not is_instance_valid(v) or v.kind != Item.KIND_PALLET or not v.visible:
			continue
		var it := Game.state.get_item(v.item_id)
		if it == null:
			continue
		var t := str(it.loc.get("t", ""))
		if t != "loose" and not (t == "slot" and Logistics.slot_world(Game.state, str(it.loc.get("slot", ""))) != null):
			continue
		var o := v.global_position
		var lp := inv * o
		if absf(lp.x - Logistics.FL_FORK_X) > 0.3 or lp.z < Logistics.FL_HEEL_Z + 0.2 or lp.z > Logistics.FL_HEEL_Z + Logistics.FL_FORK_LEN + 0.3:
			continue
		if absf(o.y - (ground + fork_h)) > 0.14:
			continue
		var rel := wrapf(v.global_basis.get_euler().y - my_yaw, -PI, PI)
		if absf(rel - snappedf(rel, PI * 0.5)) > deg_to_rad(22.0):
			continue
		var d := absf(lp.x - Logistics.FL_FORK_X) + absf(lp.z - (Logistics.FL_HEEL_Z + 0.5))
		if d < best_d:
			best_d = d
			best = {"id": it.id, "origin": o, "base": o.y, "yaw": snappedf(rad_to_deg(rel), 90.0)}
	return best


func _forklift_blocked(model: Transform3D, load_it: Item) -> bool:
	## Would the truck (or the pallet on its forks) hit a wall, equipment, racking or stock there?
	var space := get_world_3d().direct_space_state
	var q := PhysicsShapeQueryParameters3D.new()
	var body := BoxShape3D.new()
	var zb := Logistics.FL_BACK_Z
	var zf := Logistics.FL_FRONT_Z
	# From just above the floor (so a loaded pallet stops the truck at the backrest) to the overhead guard.
	body.size = Vector3(Logistics.FL_HALF_W * 2.0 - 0.04, 1.9, zf - zb - 0.04)
	q.shape = body
	q.transform = model * Transform3D(Basis.IDENTITY, Vector3(0, 0.1 + 0.95, (zf + zb) * 0.5))
	q.collision_mask = 1 | (1 << 2)
	var ex: Array[RID] = [get_rid()]
	var fv := _fork_view_node()
	if fv:
		ex.append(fv.get_rid())
	if load_it and LevelRoot.current and LevelRoot.current.item_views.has(load_it.id):
		ex.append((LevelRoot.current.item_views[load_it.id] as ItemView).get_rid())
	q.exclude = ex
	if not space.intersect_shape(q, 1).is_empty():
		return true
	if load_it == null:
		# Bare forks: walls and steelwork stop them (pallets and stock don't — that's how you get under a pallet).
		var fb := BoxShape3D.new()
		fb.size = Vector3(0.62, 0.08, Logistics.FL_FORK_LEN - 0.04)
		var fq := PhysicsShapeQueryParameters3D.new()
		fq.shape = fb
		fq.transform = model * Transform3D(Basis.IDENTITY, Vector3(Logistics.FL_FORK_X, fork_h + 0.07, Logistics.FL_HEEL_Z + Logistics.FL_FORK_LEN * 0.5 + 0.02))
		fq.collision_mask = 1
		fq.exclude = ex
		if not space.intersect_shape(fq, 1).is_empty():
			return true
	else:
		var pal := model * Logistics.pallet_on_forks(fork_h, float(load_it.loc.get("yaw", 0.0)))
		var lb := BoxShape3D.new()
		var load_h := maxf(Logistics.load_height(Game.state, load_it) - 0.08, 0.1)
		lb.size = Vector3(Logistics.PALLET.x - 0.1, load_h, Logistics.PALLET.z - 0.1)
		var lq := PhysicsShapeQueryParameters3D.new()
		lq.shape = lb
		lq.transform = pal * Transform3D(Basis.IDENTITY, Vector3(0, 0.06 + load_h * 0.5, 0))
		lq.collision_mask = 1
		lq.exclude = ex
		if not space.intersect_shape(lq, 1).is_empty():
			return true
	return false


func _fork_view_node() -> ItemView:
	var lr := LevelRoot.current
	return lr.item_views.get(_drive_id) if lr and _drive_id != 0 else null


func _ground_y(model: Transform3D, fallback: float) -> float:
	var q := PhysicsRayQueryParameters3D.new()
	q.from = model * Vector3(0, 1.0, -0.4)
	q.to = model * Vector3(0, -1.5, -0.4)
	q.collision_mask = 1
	q.exclude = [get_rid()]
	var hit := get_world_3d().direct_space_state.intersect_ray(q)
	return (hit.position as Vector3).y if not hit.is_empty() else fallback


func _update_drive_hud(load_it: Item) -> void:
	var lines: Array = ["Forks %.2f m · %.1f km/h" % [fork_h, absf(_drive_v) * 3.6]]
	if load_it:
		lines.append("Carrying %s (%d units)" % [load_it.display_name(), Logistics.units_on(Game.state, load_it.id).size()])
	if _drive_hint != "":
		lines.append(_drive_hint)
	var info := {"title": "Forklift", "lines": lines, "prompt": "[W/S] Drive · [A/D] Steer · [R/F or wheel] Forks · [H] Horn · [E] Get out"}
	if info.hash() != _hover_info.hash():
		_set_hover(null, info)


# ================================================================= placement mode

func start_placement(fixture_id: String) -> void:
	var f: Dictionary = Game.state.fixtures.get(fixture_id, {})
	if f.is_empty() or LevelRoot.current == null:
		return
	cancel_placement()
	placing_id = fixture_id
	_ghost_rot = float(f.get("r", 0.0)) if f.get("placed", false) else _yaw_deg_snapped()
	_ghost = Node3D.new()
	_ghost.name = "PlacementGhost"
	var info := LevelRoot.current.fixture_model_info(f.type)
	var ms := ModelSlot.new()
	var def := Content.fixture(f.type)
	_bend = {}
	if str(def.get("auto", {}).get("origin", "")) == "in":
		# Flexible bend: the ghost is the real bend, rebuilt as you change its angle/radius/side.
		_bend = Automation.clamp_bend(f.get("bend", def.auto))
	else:
		ms.placeholder = str(info.get("placeholder", ""))
		ms.placeholder_params = info.get("params", {})
		ms.model_scene = info.get("scene")
		LevelRoot.apply_model_info(ms, info)
	ms.cast_shadows = false
	if _bend.is_empty():
		_ghost.add_child(ms)
	# Footprint outline on the floor
	var lr := Automation.local_rect(f.type, _bend)
	var pad := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(lr.size.x, 0.01, lr.size.y)
	pad.mesh = bm
	pad.position = Vector3(lr.get_center().x, 0.006, lr.get_center().y)
	pad.name = "Footprint"
	_ghost.add_child(pad)
	var front := lr.end.y
	var arrow := MeshInstance3D.new()
	var am := BoxMesh.new()
	am.size = Vector3(0.12, 0.012, 0.35)
	arrow.mesh = am
	arrow.position = Vector3(0, 0.012, front + 0.25)
	_ghost.add_child(arrow)
	_ghost_mat = StandardMaterial3D.new()
	_ghost_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	_ghost_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	_ghost_mat.albedo_color = Color(0.3, 0.95, 0.5, 0.35)
	_ghost_mat.no_depth_test = false
	LevelRoot.current.add_child(_ghost)
	ms.rebuilt.connect(_tint_ghost)
	if not _bend.is_empty():
		arrow.visible = false
		_rebuild_bend_ghost()
	_tint_ghost()
	if def.has("auto") or f.type == "courier_shelf" or f.type == "heavy_rack":
		_guides = AutoLayout.build_guides(fixture_id)
		LevelRoot.current.add_child(_guides)
	if f.type == "robot_arm":
		var rm := StandardMaterial3D.new()
		rm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		rm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		rm.albedo_color = Color(0.3, 0.95, 1.0, 0.6)
		var ring := AutoLayout.reach_ring(rm)
		ring.name = "Reach"
		ring.position.y = 0.015
		_ghost.add_child(ring)
	Sfx.play_ui("ui_click", -6.0)


func _tint_ghost() -> void:
	if _ghost == null:
		return
	var stack: Array = [_ghost]
	while not stack.is_empty():
		var n: Node = stack.pop_back()
		if n is GeometryInstance3D:
			(n as GeometryInstance3D).material_override = _ghost_mat
			(n as GeometryInstance3D).cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		if n is Label3D:
			(n as Label3D).modulate = _ghost_mat.albedo_color
		stack.append_array(n.get_children())


func _rebuild_bend_ghost() -> void:
	if _ghost == null:
		return
	if _bend_vis:
		_bend_vis.queue_free()
	_bend_vis = Node3D.new()
	_ghost.add_child(_bend_vis)
	var f: Dictionary = Game.state.fixtures.get(placing_id, {})
	var auto := Automation.def_of(str(f.get("type", "conveyor_bend"))).duplicate()
	auto.merge(_bend, true)
	var lb := Automation.belt_local(auto)
	ConveyorBendMesh.build(lb, _bend_vis)
	ConveyorBendMesh.chevrons(lb, _bend_vis)
	var lr := Automation.local_rect(str(f.get("type", "")), _bend)
	var pad: MeshInstance3D = _ghost.get_node_or_null("Footprint")
	if pad:
		(pad.mesh as BoxMesh).size = Vector3(lr.size.x, 0.01, lr.size.y)
		pad.position = Vector3(lr.get_center().x, 0.006, lr.get_center().y)
	_tint_ghost()


func cancel_placement() -> void:
	if _ghost:
		_ghost.queue_free()
	_ghost = null
	if _guides:
		_guides.queue_free()
	_guides = null
	_snapped = {}
	_bend = {}
	_bend_vis = null
	placing_id = ""
	_set_hover(null, {})


func _yaw_deg_snapped() -> float:
	return snappedf(rad_to_deg(_yaw) + 180.0, 90.0)


func _update_placement() -> void:
	if not Game.state.fixtures.has(placing_id):
		cancel_placement()
		return
	var f: Dictionary = Game.state.fixtures[placing_id]
	var origin := camera.global_position
	var dir := -camera.global_basis.z
	var pos := global_position + Vector3(-sin(_yaw), 0, -cos(_yaw)) * 2.0
	if dir.y < -0.05:
		var t := -origin.y / dir.y
		pos = origin + dir * minf(t, 9.0)
	pos.y = 0.0
	var aim := pos
	pos.x = snappedf(pos.x, 0.25)
	pos.z = snappedf(pos.z, 0.25)
	# Conveyors snap onto the nearest open end and turn to match — no fiddling with rotation.
	_snapped = AutoLayout.snap(f.type, aim, placing_id, _bend)
	if not _snapped.is_empty():
		pos = _snapped.pos
		_ghost_rot = float(_snapped.rot)
	_ghost_pos = pos
	_ghost.global_transform = Transform3D(Basis(Vector3.UP, deg_to_rad(_ghost_rot)), pos)
	_ghost_valid = LevelRoot.current.can_place(f.type, pos, _ghost_rot, placing_id, _bend)
	var c := Color(0.3, 0.95, 0.5, 0.35) if _ghost_valid else Color(1.0, 0.3, 0.25, 0.35)
	if _ghost_mat.albedo_color != c:
		_ghost_mat.albedo_color = c
	var info := {"title": "Placing: %s" % f.get("label", f.type), "lines": [],
		"prompt": "[E] Place  ·  [R] Rotate  ·  [Q] Cancel"}
	if not _ghost_valid:
		info.lines.append("✖ Doesn't fit here (walls, doorways or other equipment)")
	if not _snapped.is_empty():
		var other: Dictionary = Game.state.fixtures.get(str(_snapped.port.fid), {})
		info.lines.append("⇄ Joins %s %s" % ["the start of" if _snapped.port["in"] else "the end of", str(other.get("label", "the conveyor"))])
		info.prompt = "[E] Place  ·  [Q] Cancel"
	elif Automation.def_of(f.type).get("kind", "") == "belt":
		info.lines.append("Aim near a green arrow to join on. Open ends wait for a robot arm.")
	if not _bend.is_empty():
		info.lines.push_front("Bend %d° %s · radius %.2f m · %.1f m of belt" % [int(_bend.angle), _bend.turn, float(_bend.radius), float(_bend.radius) * deg_to_rad(float(_bend.angle))])
		info.lines.append("[Scroll] angle · [Shift+Scroll] radius · [T] left/right")
	if f.type == "robot_arm":
		var serves := AutoLayout.arm_serves(pos, placing_id)
		info.lines.append("Reaches: " + (", ".join(serves) if not serves.is_empty() else "nothing yet — put racks, robots, shelves and conveyor ends inside the ring"))
	elif Content.fixture(f.type).has("auto") or f.type in ["heavy_rack", "courier_shelf"]:
		var arm := AutoLayout.arm_covering(pos, placing_id)
		if arm != "":
			info.lines.append("✓ In reach of %s" % str(Game.state.fixtures[arm].get("label", "a robot arm")))
		elif Automation.def_of(f.type).get("kind", "") != "belt":
			info.lines.append("Put it inside a robot arm's ring (cyan) to automate it.")
	if info.hash() != _hover_info.hash():
		_set_hover(null, info)


func _placement_input(event: InputEvent) -> void:
	if event is InputEventMouseMotion and Input.mouse_mode == Input.MOUSE_MODE_CAPTURED:
		var sens := float(Settings.get_value("mouse_sensitivity")) * 0.01
		_yaw -= event.relative.x * sens
		_pitch = clampf(_pitch - event.relative.y * sens, deg_to_rad(-88), deg_to_rad(88))
		_apply_look()
	elif not _bend.is_empty() and event is InputEventMouseButton and event.pressed and event.button_index in [MOUSE_BUTTON_WHEEL_UP, MOUSE_BUTTON_WHEEL_DOWN]:
		var up: bool = event.button_index == MOUSE_BUTTON_WHEEL_UP
		if event.shift_pressed:
			_bend.radius = float(_bend.radius) + (0.25 if up else -0.25)
		else:
			_bend.angle = float(_bend.angle) + (15.0 if up else -15.0)
		_bend = Automation.clamp_bend(_bend)
		_rebuild_bend_ghost()
		Sfx.play_ui("ui_click", -12.0)
	elif not _bend.is_empty() and event is InputEventKey and event.pressed and not event.echo and event.keycode == KEY_T:
		_bend.turn = "left" if _bend.turn == "right" else "right"
		_rebuild_bend_ghost()
		Sfx.play_ui("ui_click", -12.0)
	elif event.is_action_pressed("rotate") or (event is InputEventMouseButton and event.pressed and event.button_index == MOUSE_BUTTON_WHEEL_UP):
		_ghost_rot = fmod(_ghost_rot + 90.0, 360.0)
	elif event is InputEventMouseButton and event.pressed and event.button_index == MOUSE_BUTTON_WHEEL_DOWN:
		_ghost_rot = fmod(_ghost_rot + 270.0, 360.0)
	elif event.is_action_pressed("interact"):
		if _ghost_valid:
			var args := {"id": placing_id, "p": [_ghost_pos.x, 0.0, _ghost_pos.z], "r": _ghost_rot}
			if not _bend.is_empty():
				args["bend"] = _bend.duplicate()
			Game.request("place_fixture", args)
			Sfx.play_at("place", _ghost_pos, 0.0)
			cancel_placement()
		else:
			Sfx.play_ui("error", -6.0)
	elif event.is_action_pressed("drop") or event.is_action_pressed("pause"):
		cancel_placement()
		get_viewport().set_input_as_handled()
