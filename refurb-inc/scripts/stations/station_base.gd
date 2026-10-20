class_name StationBase
extends Node3D
## One interaction pattern for every workstation / shelf / drop point:
##   • hover text (hover_info)            • press E with empty hands (interact)
##   • accept a carried item (best_slot_for → SnapSlot)   • hold E for timed actions on the unit (hold_action)
## Child SnapSlots become "<station_id>:<slot_name>". Child ModelSlot is the swappable visual.
## If there is no CollisionShape3D under a StaticBody3D child, one is generated from the model's bounds.

signal refreshed

@export var station_id: String = ""
## "" = plain storage (shelves). Otherwise a type Sim understands: bench, repair, grade, pack, courier.
@export var station_type: String = ""
@export var display_name: String = "Station"
@export_multiline var description: String = ""

var slots: Array = []
var model: ModelSlot


func _ready() -> void:
	add_to_group("slot_hosts")
	model = _find_model()
	if model and not model.visual:
		model.rebuild()
	_ensure_collision()
	slots = SlotHost.collect(self)
	register_slots()
	Game.state_reset.connect(_reconnect)
	_reconnect()
	_station_ready()


func _station_ready() -> void:
	pass   # subclass hook


func _find_model() -> ModelSlot:
	for c in get_children():
		if c is ModelSlot:
			return c
	return null


func _ensure_collision() -> void:
	for c in get_children():
		if c is StaticBody3D:
			for cc in c.get_children():
				if cc is CollisionShape3D:
					return
	if model == null:
		return
	var aabb := model.local_aabb()
	if aabb.size == Vector3.ZERO:
		return
	var body := StaticBody3D.new()
	body.name = "AutoBody"
	body.collision_layer = 1 | (1 << 3)     # world + interact
	var cs := CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = aabb.size
	cs.shape = bs
	cs.position = aabb.get_center()
	body.add_child(cs)
	add_child(body)


func register_slots() -> void:
	if station_type != "":
		Game.state.register_station(station_id, station_type)
	for s: SnapSlot in slots:
		s.setup(station_id, station_id if station_type != "" else "")


func _reconnect() -> void:
	if not Game.state.changed.is_connected(_on_state_changed):
		Game.state.changed.connect(_on_state_changed)
	_refresh.call_deferred()


func _on_state_changed(kind: String, id: Variant) -> void:
	match kind:
		"station":
			if str(id) == station_id:
				_refresh()
		"slot":
			if str(id).begins_with(station_id + ":"):
				_refresh()
		"item", "company", "player":
			if _cares_about(kind, id):
				_refresh()


func _cares_about(kind: String, id: Variant) -> bool:
	if kind == "item":
		for s: SnapSlot in slots:
			if Game.state.slots.get(s.full_id()) == int(id):
				return true
	return false


func _refresh() -> void:
	refreshed.emit()


# ---------------------------------------------------------------- helpers

func state() -> Dictionary:
	return Game.state.stations.get(station_id, {})


func slot_item(slot_name: String) -> Item:
	return Game.state.station_slot_item(station_id, slot_name)


func item_view(it: Item) -> ItemView:
	if it == null or LevelRoot.current == null:
		return null
	return LevelRoot.current.item_views.get(it.id)


# ---------------------------------------------------------------- interaction contract

func hover_info(_player: Node) -> Dictionary:
	var lines: Array = []
	if description != "":
		lines.append(description)
	return {"title": display_name, "lines": lines, "prompt": "[F] Move"}


func item_view_near(hit_pos: Vector3, max_dist: float = 0.5) -> ItemView:
	## The item sitting in the slot closest to where the player is aiming (shelves enclose their stock in
	## one collision box, so the ray lands on the shelf rather than the laptop inside it).
	if LevelRoot.current == null:
		return null
	var best: ItemView = null
	var best_d := max_dist
	for s: SnapSlot in slots:
		var it := Game.state.item_in_slot(s.full_id())
		if it == null:
			continue
		var v: ItemView = LevelRoot.current.item_views.get(it.id)
		if v == null or not v.visible:
			continue
		var centre := s.global_position + Vector3(0, v.visual_size.y * 0.5, 0)
		var d := centre.distance_to(hit_pos)
		if d < best_d:
			best_d = d
			best = v
	return best


func best_slot_for(kind: String, hit_pos: Vector3) -> SnapSlot:
	return SlotHost.best_slot(slots, kind, hit_pos)


func host_label() -> String:
	return display_name


func hold_action(_player: Node, _it: Item) -> Dictionary:
	return {}
