class_name ShelfUnit
extends StationBase
## Storage racking. Generates a grid of SnapSlots (levels × per_level) sized for laptops lying flat.
## station_type "" = storage; "courier" = the OUTGOING shelf the courier collects from.

@export var levels: int = 5
@export var per_level: int = 3
@export var width: float = 1.2
@export var depth: float = 0.45
@export var height: float = 1.9
@export var accepts: PackedStringArray = PackedStringArray(["unit"])
@export var short_label: String = "Shelf A"


func _ready() -> void:
	if Engine.is_editor_hint():
		return
	_build_slots()
	super._ready()


func _build_slots() -> void:
	for c in get_children():
		if c is SnapSlot:
			c.queue_free()
			remove_child(c)
	var k := 0
	for l in levels:
		var y := 0.08 + l * (height - 0.15) / maxf(levels - 1, 1) + 0.012
		for i in per_level:
			var s := SnapSlot.new()
			s.slot_name = str(k)
			s.accepts = accepts
			s.position = Vector3(-width / 2 + width * (i + 0.5) / per_level, y, 0.0)
			s.location_label = "%s · level %d · bay %d" % [short_label, l + 1, i + 1]
			s.hint_size = Vector3(width / per_level - 0.04, 0.01, depth - 0.06)
			add_child(s)
			k += 1


func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	var used := 0
	for s: SnapSlot in slots:
		if not s.is_free():
			used += 1
	info.lines = ["%d / %d spaces used" % [used, slots.size()]]
	if station_type == "courier":
		var times: PackedStringArray = []
		for m in Content.econ("courier_collections", [720, 990]):
			times.append("%02d:%02d" % [int(m) / 60, int(m) % 60])
		info.lines.append("Courier collects at " + ", ".join(times))
	return info


func host_label() -> String:
	return short_label
