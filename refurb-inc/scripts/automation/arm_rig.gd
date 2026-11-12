class_name ArmRig
extends Node3D
## Jon's robot arm, cut at its joints by the model pipeline (base · turret · upper arm · forearm · gripper)
## and driven by two-bone IK: give it a point for the gripper tip (in this node's space, base axis at the
## origin) and it yaws, reaches and keeps the gripper hanging level. Scale the node for smaller arms.

const MODEL := "res://assets/models/automation/robot_arm/robot_arm.tscn"

var turret: Node3D
var upper: Node3D
var fore: Node3D
var grip: Node3D
var tip_marker: Node3D

var _s := Vector2()          # shoulder in the reach plane: (h = distance out from the axis, y)
var _tip_off := Vector2()    # gripper tip − wrist, with the gripper at its modelled angle
var _tip_z := 0.0            # the reach plane sits slightly off the axis
var _l1 := 1.0
var _l2 := 1.0
var _ua0 := 0.0
var _fa0 := 0.0
var _h_min := 0.3
var tip := Vector3(-1.0, 1.0, 0.0)   # last solved tip (local)
var home := Vector3(-0.85, 1.25, 0.0)


func _ready() -> void:
	var m: Node3D = load(MODEL).instantiate()
	add_child(m)
	var j: Dictionary = m.get_meta("joints", {})
	var axis := float(j.base_axis[0])
	m.position.x = -axis
	turret = m.get_node("Base/Turret")
	upper = turret.get_node("Upper")
	fore = upper.get_node("Fore")
	grip = fore.get_node("Grip")
	tip_marker = grip.get_node("Tip")
	var sh := Vector2(axis - float(j.shoulder[0]), float(j.shoulder[1]))
	var el := Vector2(axis - float(j.elbow[0]), float(j.elbow[1]))
	var wr := Vector2(axis - float(j.wrist[0]), float(j.wrist[1]))
	var tip_model := Vector3(float(j.wrist[0]), float(j.wrist[1]), 0.0) + tip_marker.position
	var tp := Vector2(axis - tip_model.x, tip_model.y)
	_s = sh
	_tip_off = tp - wr
	_tip_z = tip_model.z
	_l1 = sh.distance_to(el)
	_l2 = el.distance_to(wr)
	_ua0 = atan2(el.y - sh.y, el.x - sh.x)
	_fa0 = atan2(wr.y - el.y, wr.x - el.x)
	for mi: MeshInstance3D in m.find_children("*", "MeshInstance3D", true, false):
		mi.visibility_range_end = 45.0
	solve(home)


func reach_ok(p: Vector3) -> bool:
	var h := Vector2(p.x, p.z).length()
	var w := Vector2(h, p.y) - _tip_off - _s
	return w.length() < _l1 + _l2 - 0.02


func solve(target: Vector3) -> void:
	## Point the gripper tip at `target` (local space). Out-of-reach targets get as close as they can.
	var r := Vector2(target.x, target.z).length()
	var h := sqrt(maxf(r * r - _tip_z * _tip_z, _h_min * _h_min))
	var yaw := atan2(target.z, -target.x) - atan2(_tip_z, h)
	var wt := Vector2(h, target.y) - _tip_off
	var d := wt - _s
	var dist := clampf(d.length(), absf(_l1 - _l2) + 0.02, _l1 + _l2 - 0.005)
	var phi := atan2(d.y, d.x)
	var ca := clampf((_l1 * _l1 + dist * dist - _l2 * _l2) / (2.0 * _l1 * dist), -1.0, 1.0)
	var ua := phi + acos(ca)
	var e := _s + Vector2(cos(ua), sin(ua)) * _l1
	var w := _s + d.normalized() * dist
	var fa := atan2(w.y - e.y, w.x - e.x)
	var r_upper := -(ua - _ua0)
	var r_fore := -(fa - _fa0)
	turret.rotation.y = yaw
	upper.rotation.z = r_upper
	fore.rotation.z = wrapf(r_fore - r_upper, -PI, PI)
	grip.rotation.z = -r_fore
	tip = target


func tip_global() -> Vector3:
	return tip_marker.global_position


func tip_basis_yaw() -> float:
	return turret.rotation.y
