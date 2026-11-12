class_name AutoLayout
extends RefCounted
## Helpers that make laying out automation easy (they read state only, so they work for every player):
##   • open conveyor ports, and snapping a conveyor being placed onto the nearest one (it turns to match)
##   • what a robot arm at a spot can reach, and whether a conveyor end has an arm to empty it
##   • on-floor guides while placing: arrows on open conveyor ends, reach rings round every arm

const SNAP_RADIUS := 1.1


static func _belt_fixtures(ignore_id: String = "") -> Array:
	var out: Array = []
	for fid in Game.state.fixtures:
		if fid == ignore_id:
			continue
		var f: Dictionary = Game.state.fixtures[fid]
		if f.get("placed", false) and Automation.def_of(f.type).get("kind", "") in ["belt", "store"]:
			out.append(fid)
	return out


static func open_ports(ignore_id: String = "") -> Array:
	## Conveyor ends nothing is joined to: [{p, d, in, fid, name}] in world space.
	var all: Array = []
	for fid in _belt_fixtures(ignore_id):
		var f: Dictionary = Game.state.fixtures[fid]
		for prt in Automation.ports_of(f.type, Automation.fixture_xf(f), f.get("bend", {})):
			prt["fid"] = fid
			all.append(prt)
	var out: Array = []
	for a in all:
		var joined := false
		for b in all:
			if b.fid == a.fid or b["in"] == a["in"]:
				continue
			if (a.p as Vector3).distance_to(b.p) < Automation.PORT_SNAP and (a.d as Vector3).dot(b.d) > 0.7:
				joined = true
				break
		if not joined:
			out.append(a)
	return out


static func snap(type: String, aim: Vector3, ignore_id: String = "", settings: Dictionary = {}) -> Dictionary:
	## Where to put a conveyor piece so it joins the open end nearest `aim` (and which way it must face).
	var auto := Automation.def_of(type)
	if auto.get("kind", "") != "belt":
		return {}
	if not settings.is_empty():
		auto = auto.duplicate()
		auto.merge(settings, true)
	var lb := Automation.belt_local(auto)
	var best := {}
	var best_d := SNAP_RADIUS
	for prt in open_ports(ignore_id):
		var dist := Vector2(aim.x - prt.p.x, aim.z - prt.p.z).length()
		if dist >= best_d:
			continue
		var d: Vector3 = prt.d
		var mine: Dictionary
		if prt["in"]:
			mine = lb.out[0]          # our main exit feeds their start
		else:
			mine = lb.in[0]           # their exit feeds our start
		var md: Vector3 = mine.d
		var rot := rad_to_deg(atan2(d.x, d.z) - atan2(md.x, md.z))
		# 15° steps: flexible bends end at any multiple of 15°, and the next piece must follow them exactly.
		rot = fposmod(snappedf(rot, 15.0), 360.0)
		var b := Basis(Vector3.UP, deg_to_rad(rot))
		var pos: Vector3 = (prt.p as Vector3) - b * (mine.p as Vector3)
		pos.y = 0.0
		best_d = dist
		best = {"pos": pos, "rot": rot, "port": prt}
	return best


static func reach_of(type: String) -> float:
	return float(Automation.def_of(type).get("reach", 1.8))


static func in_reach_of_arm(arm_pos: Vector3, p: Vector3, slack: float = 0.0) -> bool:
	return Vector2(p.x - arm_pos.x, p.z - arm_pos.z).length() <= reach_of("robot_arm") + slack


static func arm_serves(arm_pos: Vector3, ignore_id: String = "") -> Array:
	## Names of what an arm standing at arm_pos could work with.
	var names: Array = []
	var ends := 0
	var starts := 0
	for prt in open_ports(ignore_id):
		if in_reach_of_arm(arm_pos, prt.p):
			if prt["in"]:
				starts += 1
			elif prt.name == "out":
				ends += 1
	for fid in Game.state.fixtures:
		if fid == ignore_id:
			continue
		var f: Dictionary = Game.state.fixtures[fid]
		if not f.get("placed", false) or f.type == "robot_arm":
			continue
		var def := Content.fixture(f.type)
		if def.get("auto", {}).get("kind", "") in ["belt", "monitor"]:
			continue
		var fp: Array = def.get("footprint", [1, 1])
		var p := Automation.fixture_xf(f).origin
		if in_reach_of_arm(arm_pos, p, minf(float(fp[0]), float(fp[1])) * 0.5):
			names.append(str(f.get("label", def.get("name", f.type))))
	if ends > 0:
		names.append("%d conveyor end%s (pick-up)" % [ends, "" if ends == 1 else "s"])
	if starts > 0:
		names.append("%d conveyor start%s (drop-off)" % [starts, "" if starts == 1 else "s"])
	return names


static func arm_covering(p: Vector3, ignore_id: String = "") -> String:
	for fid in Game.state.fixtures:
		if fid == ignore_id:
			continue
		var f: Dictionary = Game.state.fixtures[fid]
		if f.get("placed", false) and f.type == "robot_arm" and in_reach_of_arm(Automation.fixture_xf(f).origin, p):
			return fid
	return ""


static func build_guides(ignore_id: String) -> Node3D:
	## Floor guides for placement mode: green arrows on open conveyor ends (pointing the way kit flows),
	## a faint cyan ring for each robot arm's reach.
	var root := Node3D.new()
	root.name = "AutomationGuides"
	var arrow_mat := StandardMaterial3D.new()
	arrow_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	arrow_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	arrow_mat.albedo_color = Color(0.3, 1.0, 0.45, 0.85)
	arrow_mat.no_depth_test = true
	for prt in open_ports(ignore_id):
		var a := _arrow(arrow_mat)
		var d: Vector3 = prt.d
		a.global_transform = Transform3D(Basis(Vector3.UP, atan2(d.x, d.z)), (prt.p as Vector3) + Vector3(0, 0.93, 0))
		root.add_child(a)
	var ring_mat := StandardMaterial3D.new()
	ring_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	ring_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	ring_mat.albedo_color = Color(0.3, 0.8, 1.0, 0.4)
	for fid in Game.state.fixtures:
		if fid == ignore_id:
			continue
		var f: Dictionary = Game.state.fixtures[fid]
		if f.get("placed", false) and f.type == "robot_arm":
			var ring := reach_ring(ring_mat)
			ring.position = Automation.fixture_xf(f).origin + Vector3(0, 0.012, 0)
			root.add_child(ring)
	return root


static func reach_ring(mat: Material) -> MeshInstance3D:
	var r := reach_of("robot_arm")
	var ring := MeshInstance3D.new()
	var tm := TorusMesh.new()
	tm.inner_radius = r - 0.03
	tm.outer_radius = r + 0.03
	tm.rings = 72
	ring.mesh = tm
	ring.scale = Vector3(1, 0.05, 1)
	ring.material_override = mat
	ring.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	return ring


static func _arrow(mat: Material) -> Node3D:
	var n := Node3D.new()
	var shaft := MeshInstance3D.new()
	var sm := BoxMesh.new()
	sm.size = Vector3(0.06, 0.01, 0.26)
	shaft.mesh = sm
	shaft.material_override = mat
	n.add_child(shaft)
	for sx in [-1.0, 1.0]:
		var head := MeshInstance3D.new()
		var hm := BoxMesh.new()
		hm.size = Vector3(0.05, 0.01, 0.16)
		head.mesh = hm
		head.material_override = mat
		head.position = Vector3(0.045 * sx, 0, 0.08)
		head.rotation_degrees.y = -35.0 * sx
		n.add_child(head)
	return n
