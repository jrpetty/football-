extends Node3D
## Jon's box lorry with the moving parts the delivery needs: a roller-shutter RearDoor (slides up) and a
## steel TailLift (lowers to the ground), plus a lined cargo box so you see inside when the shutter's up.
## Rear faces -Z, cab +Z (vehicle contract). VehicleDirector animates "RearDoor" and "TailLift" by name.

@export var brand := "PENNINE FREIGHT"
const BOX_HALF_W := 1.075
const BOX_FLOOR := 1.0
const BOX_TOP := 3.33
const REAR_Z := -3.66


func _ready() -> void:
	var model: PackedScene = load("res://assets/models/vehicles/box_lorry/box_lorry.tscn")
	add_child(model.instantiate())
	# Cargo liner: dark plywood box seen from inside (front-face culling off).
	var liner := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(BOX_HALF_W * 2 - 0.06, BOX_TOP - BOX_FLOOR - 0.05, 5.2)
	bm.flip_faces = true
	liner.mesh = bm
	var lm := Mats.surface("plywood", 1.0, Color(0.55, 0.5, 0.42))
	liner.material_override = lm
	liner.position = Vector3(0, (BOX_TOP + BOX_FLOOR) * 0.5, REAR_Z + 2.62)
	liner.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(liner)
	# Roller shutter: ribbed aluminium slats that roll up into the top of the box (open_frac 0 → 1).
	var door := RollerShutter.new()
	door.name = "RearDoor"
	door.width = BOX_HALF_W * 2 - 0.1
	door.height = BOX_TOP - BOX_FLOOR - 0.04
	door.position = Vector3(0, BOX_TOP - 0.02, REAR_Z + 0.06)
	add_child(door)
	# Tail lift: folds at the rear sill, lowered to the ground during a drop.
	var lift := Node3D.new()
	lift.name = "TailLift"
	lift.position = Vector3(0, 0.85, REAR_Z - 0.02)
	add_child(lift)
	Placeholders.box(lift, Vector3(BOX_HALF_W * 2 - 0.1, 0.06, 1.4), Vector3(0, 0, -0.7), Mats.painted_metal(Placeholders.STEEL_GREY), Vector3.ZERO, "Platform")
	Placeholders.box(lift, Vector3(BOX_HALF_W * 2 - 0.1, 0.012, 0.18), Vector3(0, 0.035, -1.32), Mats.color(Color(0.95, 0.75, 0.1), 0.6))
	for sx in [-1, 1]:
		Placeholders.box(lift, Vector3(0.06, 0.5, 0.06), Vector3(sx * (BOX_HALF_W - 0.12), -0.25, -0.05), Mats.painted_metal(Color(0.15, 0.15, 0.16)))
	# Tail lights.
	for sx in [-1, 1]:
		Placeholders.box(self, Vector3(0.18, 0.1, 0.04), Vector3(sx * 0.9, 0.78, REAR_Z - 0.03), Mats.emissive(Color(1, 0.08, 0.04), 2.5), Vector3.ZERO, "TailLight")
	# Livery on both sides of the box.
	for side in [-1, 1]:
		var sign := Node3D.new()
		sign.position = Vector3(side * (BOX_HALF_W + 0.012), 2.35, -1.0)
		sign.rotation_degrees = Vector3(0, 90 * side, 0)
		add_child(sign)
		Placeholders.text(sign, brand, Vector3.ZERO, 240, Color(0.12, 0.2, 0.45), Vector3.ZERO, 0.0019)


class RollerShutter extends Node3D:
	## Slats hang from the top; opening rolls them up (the lowest slats disappear first) like a real shutter.
	var width := 2.0
	var height := 2.3
	var slats := 16
	var open_frac := 0.0:
		set(v):
			open_frac = clampf(v, 0.0, 1.0)
			_apply()
	var _slat_nodes: Array = []

	func _ready() -> void:
		var mat := Mats.painted_metal(Color(0.9, 0.91, 0.9))
		for i in slats:
			var mi := Placeholders.box(self, Vector3(width, height / slats - 0.008, 0.03), Vector3.ZERO, mat)
			_slat_nodes.append(mi)
		var handle := Placeholders.box(self, Vector3(0.28, 0.05, 0.05), Vector3.ZERO, Mats.painted_metal(Color(0.2, 0.2, 0.22)), Vector3.ZERO, "Handle")
		_slat_nodes.append(handle)
		_apply()

	func _apply() -> void:
		if _slat_nodes.is_empty():
			return
		var pitch := height / slats
		var visible_h := height * (1.0 - open_frac)
		for i in slats:
			var mi: Node3D = _slat_nodes[i]
			var y := -(i + 0.5) * pitch               # slat i counted down from the top
			mi.visible = (i + 1) * pitch <= visible_h + 0.001
			mi.position = Vector3(0, y, 0)
		var handle: Node3D = _slat_nodes[slats]
		handle.position = Vector3(0, -visible_h + 0.12, -0.03)
		handle.visible = visible_h > 0.2
