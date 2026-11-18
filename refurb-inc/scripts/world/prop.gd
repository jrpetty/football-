class_name Prop
extends Node3D
## Static set-dressing prop. Visual = child ModelSlot. If `solid`, a box collider is fitted to the model bounds
## (or provide your own StaticBody3D/CollisionShape3D child for an exact shape).

@export var solid := true


func _ready() -> void:
	if not solid:
		return
	for c in get_children():
		if c is StaticBody3D:
			return
	var ms: ModelSlot = null
	for c in get_children():
		if c is ModelSlot:
			ms = c
	if ms == null:
		return
	if ms.visual == null:
		ms.rebuild()
	var aabb := ms.local_aabb()
	if aabb.size == Vector3.ZERO:
		return
	var body := StaticBody3D.new()
	body.collision_layer = 1
	var cs := CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = aabb.size
	cs.shape = bs
	cs.position = aabb.get_center()
	body.add_child(cs)
	add_child(body)
