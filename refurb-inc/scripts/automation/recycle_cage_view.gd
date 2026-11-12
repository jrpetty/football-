class_name RecycleCageView
extends StationBase
## WEEE recycling cage: anything put in here (by a person or a robot arm) is collected for licensed
## recycling and paid as scrap. For kit that can never be fixed — locked accounts, boards beyond repair.

const BIN_MODEL := "res://assets/models/props/weee_bin/weee_bin.tscn"


func _ready() -> void:
	station_type = "recycle"
	var s := SnapSlot.new()
	s.slot_name = "in"
	s.accepts = PackedStringArray(["unit", "unit_large"])
	s.location_label = "Recycling cage"
	s.position = Vector3(0, 1.0, 0.0)
	s.hint_size = Vector3(0.6, 0.01, 0.5)
	add_child(s)
	super._ready()
	var mesh := Mats.color(Color(0.3, 0.32, 0.34), 0.5, 0.7)
	# Mesh-sided cage frame round the bin.
	for sx in [-1.0, 1.0]:
		for sz in [-1.0, 1.0]:
			var post := MeshInstance3D.new()
			var pm := BoxMesh.new()
			pm.size = Vector3(0.04, 1.25, 0.04)
			post.mesh = pm
			post.material_override = mesh
			post.position = Vector3(sx * 0.48, 0.625, sz * 0.38)
			add_child(post)
	var sign := Placeholders.text(self, "WEEE RECYCLING", Vector3(0, 1.32, 0.0), 40, Color(0.55, 1.0, 0.55), Vector3.ZERO, 0.0013)
	sign.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
	sign.shaded = false
	sign.outline_size = 8
	sign.outline_modulate = Color(0.05, 0.12, 0.05)


func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	var scrap: Dictionary = Content.econ("scrap_value", {})
	info.lines = ["Put kit that can't be fixed in here — paid as scrap (£%d–£%d a unit)." % [int(scrap.values().min()) if not scrap.is_empty() else 3, int(scrap.values().max()) if not scrap.is_empty() else 30]]
	return info
