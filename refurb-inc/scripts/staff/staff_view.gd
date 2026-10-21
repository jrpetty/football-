class_name StaffView
extends Node3D
## One member of staff in the world. On the host it thinks (StaffPlanner) and acts through real commands as
## its own peer, walking the StaffNav grid between jobs; on co-op guests it's a puppet following the host.
## Registered in LevelRoot.players under its peer id, so items it carries ride in its hands like a player's.

const POSE_HZ := 8.0

static var claimed := {}            # item id -> staff peer currently fetching it (stops two staff grabbing one)

var staff_id := 0
var peer := 0
var rig: CharacterRig
var hold_node: Node3D
var _plan: Array = []
var _step := 0
var _path := PackedVector3Array()
var _pi := 0
var _wait := 0.0
var _speed := 1.25
var _idle_t := 0.0
var _pose_t := 0.0
var _net_pos := Vector3.ZERO
var _net_yaw := 0.0
var _planner: StaffPlanner
var status := "Starting their shift"
var _fail_count := 0
var _cur_speed := 0.0


static func claim(item_id: int, by: int) -> void:
	claimed[item_id] = by


static func peer_of(id: int) -> int:
	return -(1000 + id)


func setup(id: int) -> void:
	staff_id = id
	peer = peer_of(id)
	name = "Staff_%d" % id
	var d: Dictionary = Game.state.staff.get(id, {})
	rig = CharacterRig.new()
	rig.rotation_degrees.y = 0.0
	add_child(rig)
	Looks.apply(rig, d.get("look", {}))
	hold_node = Node3D.new()
	hold_node.position = Vector3(0, 1.0, 0.38)
	add_child(hold_node)
	var tag := Label3D.new()
	tag.text = "%s\n%s" % [str(d.get("name", "Staff")), _role_name(str(d.get("role", "")))]
	tag.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	tag.position.y = 2.05
	tag.font_size = 36
	tag.pixel_size = 0.0035
	tag.outline_size = 8
	tag.modulate = Color(0.85, 0.95, 1.0)
	add_child(tag)
	var p: Array = d.get("pos", [])
	if p.size() == 3:
		global_position = Vector3(p[0], 0, p[2])
	_net_pos = global_position
	_speed = float(Content_staff().get("walk_speed", 1.25)) * (0.55 + 0.08 * Game.state.skill_level(Sim.player_key(peer), "hauling"))


static func Content_staff() -> Dictionary:
	return Staff.data()


static func _role_name(role: String) -> String:
	for r: Dictionary in Staff.data().get("roles", []):
		if r.id == role:
			return str(r.name)
	return role.capitalize()


func hold_point() -> Node3D:
	return hold_node


func _physics_process(delta: float) -> void:
	if not Game.state.staff.has(staff_id):
		return
	if Game.is_host() and Game.sim:
		_think(delta)
		_pose_t -= delta
		if _pose_t <= 0.0:
			_pose_t = 1.0 / POSE_HZ
			var d: Dictionary = Game.state.staff[staff_id]
			d["pos"] = [snappedf(global_position.x, 0.01), 0.0, snappedf(global_position.z, 0.01)]
			d["yaw"] = snappedf(rotation.y, 0.01)
			d["spd"] = snappedf(_cur_speed, 0.05)
			d["status"] = status
			Game.state.mark("staff", staff_id)
	else:
		var d2: Dictionary = Game.state.staff[staff_id]
		var p: Array = d2.get("pos", [global_position.x, 0, global_position.z])
		_net_pos = Vector3(p[0], 0, p[2])
		_net_yaw = float(d2.get("yaw", rotation.y))
		global_position = global_position.lerp(_net_pos, clampf(delta * 8.0, 0.0, 1.0))
		rotation.y = lerp_angle(rotation.y, _net_yaw, clampf(delta * 8.0, 0.0, 1.0))
		_cur_speed = float(d2.get("spd", 0.0))
		status = str(d2.get("status", ""))
	rig.move_speed = _cur_speed
	var carrying := not Game.state.held_by(peer).is_empty()
	if rig.anim != "push":
		rig.anim = "carry" if carrying else ("work" if status.begins_with("Working") else "idle")


# ================================================================= host brain

func _think(delta: float) -> void:
	if not Game.state.day_running:
		_cur_speed = 0.0
		status = "Off shift"
		return
	if _wait > 0.0:
		_wait -= delta
		_cur_speed = 0.0
		if _wait <= 0.0 and _step < _plan.size() and str(_plan[_step][0]) == "hold":
			var st: Array = _plan[_step]
			_do_cmd(str(st[2]), st[3])
			_step += 1
		return
	if not _path.is_empty():
		_walk(delta)
		return
	if _step >= _plan.size():
		_plan = []
		_step = 0
		_idle_t -= delta
		if _idle_t > 0.0:
			_cur_speed = 0.0
			return
		if _planner == null or _planner.role != str(Game.state.staff[staff_id].get("role", "")):
			_planner = StaffPlanner.new(peer, str(Game.state.staff[staff_id].get("role", "all_rounder")))
		_plan = _planner.next_plan()
		# Less reliable people take longer to get going on the next job.
		var key := Sim.player_key(peer)
		var levels := {}
		for sk: Dictionary in Content.skill_defs():
			levels[sk.id] = Game.state.skill_level(key, sk.id)
		if not _plan.is_empty():
			_plan.push_front(["wait", lerpf(3.5, 0.3, Staff.reliability(levels))])
		if _plan.is_empty():
			status = _waiting_status()
			_idle_t = 2.0
			_release_claims()
			_cur_speed = 0.0
			return
	var step: Array = _plan[_step]
	match str(step[0]):
		"walk":
			var target: Vector3 = step[1]
			if target == Vector3.INF:
				_abort("Can't reach that")
				return
			_path = LevelRoot.current.staff_nav.path(global_position, target) if LevelRoot.current and LevelRoot.current.staff_nav else PackedVector3Array()
			_pi = 0
			status = "Walking"
			if _path.is_empty():
				# Already there, or no route: if close enough, carry on; otherwise give up on this job.
				if Vector2(global_position.x, global_position.z).distance_to(Vector2(target.x, target.z)) < 1.2:
					_step += 1
				else:
					_abort("No way through")
				return
			_step += 1
		"cmd":
			_do_cmd(str(step[1]), step[2])
			_step += 1
		"hold":
			status = "Working: %s" % str(step[2])
			_wait = float(step[1])
		"wait":
			status = "At the office PC" if Staff.office_role(str(Game.state.staff[staff_id].get("role", ""))) else "Getting ready"
			_wait = float(step[1])
			_step += 1


func _waiting_status() -> String:
	## Idle, but say what they're waiting on (a test running, nothing to pack…).
	var role := str(Game.state.staff[staff_id].get("role", ""))
	if role in ["tester", "all_rounder"]:
		for sid in Game.state.stations:
			var st: Dictionary = Game.state.stations[sid]
			if st.get("type", "") == "bench" and not st.get("job", {}).is_empty():
				var job: Dictionary = st.job
				var pct := clampf((Game.state.minute - float(job.get("start", Game.state.minute))) / maxf(float(job.get("minutes", job.get("dur", 1.0))), 0.01), 0.0, 1.0)
				return "Watching the test bench (%d%%)" % int(pct * 100.0)
	match role:
		"packer":
			return "Nothing to pack yet"
		"grader":
			return "Waiting for imaged units"
		"repair_tech":
			return "No repairs they can do right now"
		"hauler":
			return "Nothing to haul"
	return "Waiting for work"


func _walk(delta: float) -> void:
	var target := _path[_pi]
	var to := Vector3(target.x - global_position.x, 0, target.z - global_position.z)
	var dist := to.length()
	if dist < 0.08:
		_pi += 1
		if _pi >= _path.size():
			_path = PackedVector3Array()
			_cur_speed = 0.0
		return
	var dir := to / dist
	var sp := _speed * (0.8 if not Game.state.held_by(peer).is_empty() else 1.0)
	_cur_speed = lerpf(_cur_speed, sp, clampf(delta * 5.0, 0.0, 1.0))
	global_position += dir * minf(_cur_speed * delta, dist)
	rotation.y = lerp_angle(rotation.y, atan2(dir.x, dir.z), clampf(delta * 8.0, 0.0, 1.0))


func _do_cmd(cmd: String, args: Dictionary) -> void:
	var res := Game.sim.execute(peer, cmd, args)
	if not res.get("ok", false):
		_abort(str(res.get("msg", "")))
		return
	_fail_count = 0
	match cmd:
		"place":
			Sfx.play_at("place", global_position + Vector3(0, 1, 0), -10.0)
		"pick_up":
			Sfx.play_at("pickup", global_position + Vector3(0, 1, 0), -10.0)


func _abort(why: String) -> void:
	## Plans go stale (someone else moved the unit, a slot filled up): put down anything carried and re-plan.
	_fail_count += 1
	status = "Re-thinking" if why == "" else why
	var held := Game.state.held_by(peer)
	if not held.is_empty():
		var shelf := ""
		if _planner:
			shelf = _planner._shelf_slot(held.back())
		if shelf != "" and Game.sim.execute(peer, "place", {"slot": shelf}).ok:
			pass
		else:
			var p := global_position + global_basis.z * 0.6
			Game.sim.execute(peer, "drop", {"p": [p.x, 0.3, p.z], "r": [0, 0, 0, 1]})
	_plan = []
	_step = 0
	_path = PackedVector3Array()
	_wait = 0.0
	_idle_t = minf(1.0 + _fail_count, 6.0)
	_release_claims()


func _release_claims() -> void:
	for k in claimed.keys():
		if claimed[k] == peer:
			claimed.erase(k)
