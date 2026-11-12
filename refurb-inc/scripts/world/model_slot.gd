@tool
class_name ModelSlot
extends Node3D
## The swappable visual of any prop/station/vehicle.
##   • Leave `model_scene` empty → a procedural placeholder (`placeholder` id) is built.
##   • Drag your imported .glb / .tscn into `model_scene` → it replaces the placeholder. Nothing else changes:
##     collisions, snap slots and scripts live on the parent scene, not in the model.
## Use model_offset / model_rotation_deg / model_scale to line a model up (1 unit = 1 m, front faces +Z).
## Contract nodes (optional, looked up by name inside the model): "Screen", "Lid", "TailLift", "RearDoor", "LabelPrinter".

signal rebuilt

@export var model_scene: PackedScene:
	set(v):
		model_scene = v
		_queue_rebuild()
@export var placeholder: String = "":
	set(v):
		placeholder = v
		_queue_rebuild()
@export var placeholder_params: Dictionary = {}:
	set(v):
		placeholder_params = v
		_queue_rebuild()
@export var model_offset: Vector3 = Vector3.ZERO:
	set(v):
		model_offset = v
		_apply_xform()
@export var model_rotation_deg: Vector3 = Vector3.ZERO:
	set(v):
		model_rotation_deg = v
		_apply_xform()
@export var model_scale: Vector3 = Vector3.ONE:
	set(v):
		model_scale = v
		_apply_xform()
@export var cast_shadows := true

var visual: Node3D = null
var _pending := false


func _ready() -> void:
	rebuild()


func _queue_rebuild() -> void:
	if not is_inside_tree() or _pending:
		return
	_pending = true
	(func():
		_pending = false
		rebuild()).call_deferred()


func rebuild() -> void:
	if visual and is_instance_valid(visual):
		visual.queue_free()
		remove_child(visual)
	visual = null
	if model_scene:
		visual = model_scene.instantiate()
	elif placeholder != "":
		visual = Placeholders.build(placeholder, placeholder_params)
	if visual == null:
		return
	visual.name = "Visual"
	add_child(visual)   # no owner → never saved into the scene file
	_apply_xform()
	if not cast_shadows:
		for mi in find_meshes():
			mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	rebuilt.emit()


func _apply_xform() -> void:
	if visual:
		visual.position = model_offset
		visual.rotation_degrees = model_rotation_deg
		visual.scale = model_scale


func find_part(part_name: String) -> Node:
	if visual == null:
		return null
	if visual.name == part_name:
		return visual
	return visual.find_child(part_name, true, false)


func find_meshes() -> Array:
	var out: Array = []
	if visual:
		_collect(visual, out)
	return out


func _collect(n: Node, out: Array) -> void:
	if n is MeshInstance3D:
		out.append(n)
	for c in n.get_children():
		_collect(c, out)


func local_aabb() -> AABB:
	## Combined AABB of the visual in this node's space (used to auto-size collision for unknown models).
	var have := false
	var box := AABB()
	for mi: MeshInstance3D in find_meshes():
		if mi.mesh == null:
			continue
		var xf := global_transform.affine_inverse() * mi.global_transform if is_inside_tree() else _rel_xform(mi)
		var a := xf * mi.mesh.get_aabb()
		box = a if not have else box.merge(a)
		have = true
	return box


func _rel_xform(n: Node3D) -> Transform3D:
	var xf := Transform3D.IDENTITY
	var cur: Node = n
	while cur and cur != self:
		if cur is Node3D:
			xf = (cur as Node3D).transform * xf
		cur = cur.get_parent()
	return xf
