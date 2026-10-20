@tool
class_name SnapSlot
extends Marker3D
## A place an item can sit (bench surface, shelf position, USB port, pallet position, courier shelf…).
## Its transform is where the item's origin (bottom-centre) ends up. The full slot id is "<host_id>:<slot_name>".
## Hosts (stations/shelves/pallets) expose slots; players target the HOST and it picks the best free slot.

@export var slot_name: String = "0"
@export var accepts: PackedStringArray = PackedStringArray(["unit"])
## Shown in the Stock app, e.g. "Shelf A · level 3".
@export var location_label: String = ""
## Visual hint drawn when a player carries something this slot accepts.
@export var hint_size: Vector3 = Vector3(0.34, 0.01, 0.24)

var host_id: String = ""
var station_id: String = ""
var _hint: MeshInstance3D


func full_id() -> String:
	return "%s:%s" % [host_id, slot_name]


func _ready() -> void:
	gizmo_extents = 0.08
	if Engine.is_editor_hint():
		return
	_hint = MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = hint_size
	_hint.mesh = bm
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.albedo_color = Color(0.3, 0.9, 0.5, 0.25)
	m.no_depth_test = false
	_hint.material_override = m
	_hint.position = Vector3(0, hint_size.y / 2, 0)
	_hint.visible = false
	_hint.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(_hint)


func setup(p_host_id: String, p_station_id: String) -> void:
	host_id = p_host_id
	station_id = p_station_id
	register()


func register() -> void:
	if host_id == "" or Engine.is_editor_hint():
		return
	Game.state.register_slot(full_id(), Array(accepts), station_id)
	if LevelRoot.current:
		LevelRoot.current.slot_nodes[full_id()] = self


func unregister() -> void:
	if LevelRoot.current and LevelRoot.current.slot_nodes.get(full_id()) == self:
		LevelRoot.current.slot_nodes.erase(full_id())


func _exit_tree() -> void:
	if not Engine.is_editor_hint():
		unregister()


func is_free() -> bool:
	return Game.state.item_in_slot(full_id()) == null


func can_take(kind: String) -> bool:
	return kind in accepts and is_free()


func show_hint(on: bool, good: bool = true) -> void:
	if _hint:
		_hint.visible = on
		(_hint.material_override as StandardMaterial3D).albedo_color = Color(0.3, 0.9, 0.5, 0.28) if good else Color(0.95, 0.3, 0.25, 0.28)
