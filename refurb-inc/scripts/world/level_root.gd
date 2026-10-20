class_name LevelRoot
extends Node3D
## Root of a playable location (garage now; bigger premises and seller sites later reuse this).
## Keeps 3D in sync with GameState: one ItemView per world item, one Player per peer.

static var current: LevelRoot
## Title-screen backdrop: build the level but don't start a game or spawn players.
static var menu_mode := false

const PLAYER_SCENE := "res://scenes/player/player.tscn"
const XFORM_HZ := 15.0

@export var spawn_points: Array[Node3D] = []
@export var safe_drop: Node3D
@export var lorry_drop: Node3D
@export var parcel_drop: Node3D
@export var environment: WorldEnvironment
@export var sun: DirectionalLight3D

var slot_nodes: Dictionary = {}      # full slot id -> SnapSlot
var item_views: Dictionary = {}      # item id -> ItemView
var players: Dictionary = {}         # peer id -> Player
var pending_views: Dictionary = {}   # item ids waiting for their slot node to exist
var items_root: Node3D
var players_root: Node3D
var _xf_accum := 0.0


func _enter_tree() -> void:
	current = self


func _exit_tree() -> void:
	if current == self:
		current = null


func _ready() -> void:
	items_root = Node3D.new()
	items_root.name = "Items"
	add_child(items_root)
	players_root = Node3D.new()
	players_root.name = "Players"
	add_child(players_root)
	Game.state_reset.connect(_on_state_reset)
	Net.peers_changed.connect(_sync_players)
	multiplayer.peer_disconnected.connect(_on_peer_left)
	Settings.settings_changed.connect(_apply_graphics)
	_apply_graphics()
	_start_ambience()
	if menu_mode:
		return
	if not Game.in_game:
		# Running the level directly from the editor (F6): start a fresh solo game.
		Game.new_game()
	else:
		_on_state_reset()


func _start_ambience() -> void:
	var street := Sfx.make_loop("street_ambience", self, -8.0, "Ambience")
	street.position = Vector3(0, 1.5, 8.0)
	street.unit_size = 10.0
	street.max_distance = 60.0
	var hum := Sfx.make_loop("fluorescent_hum", self, -20.0, "Ambience")
	hum.position = Vector3(0, 2.5, -1.0)
	if street.stream:
		street.play()
	if hum.stream:
		hum.play()


func _apply_graphics() -> void:
	if environment:
		Settings.apply_graphics(environment.environment, sun)


func _on_state_reset() -> void:
	if not Game.state.changed.is_connected(_on_state_changed):
		Game.state.changed.connect(_on_state_changed)
	# Stations/shelves (re)register their slots into the new state first.
	get_tree().call_group("slot_hosts", "register_slots")
	if Game.is_host() and Game.sim:
		Game.sim.visual_vehicles = has_node("VehicleDirector")
	_rebuild_items()
	_sync_players()


func _rebuild_items() -> void:
	for v in item_views.values():
		v.queue_free()
	item_views.clear()
	pending_views.clear()
	# Pallets first so their slots exist before the units on them.
	var ids: Array = Game.state.items.keys()
	ids.sort_custom(func(a, b):
		var ka: bool = Game.state.items[a].kind == Item.KIND_PALLET
		var kb: bool = Game.state.items[b].kind == Item.KIND_PALLET
		return ka and not kb)
	for id in ids:
		_update_item(id)


func _on_state_changed(kind: String, id: Variant) -> void:
	if kind == "item":
		_update_item(int(id))


func _update_item(id: int) -> void:
	var it: Item = Game.state.items.get(id)
	var view: ItemView = item_views.get(id)
	var visible_loc: bool = it != null and it.loc.get("t") in ["slot", "held", "loose"]
	if not visible_loc:
		if view:
			view.queue_free()
			item_views.erase(id)
		pending_views.erase(id)
		return
	if view == null:
		view = ItemView.new()
		items_root.add_child(view)
		view.setup(it)
		item_views[id] = view
	else:
		view.apply_state(it)
	if it.kind == Item.KIND_PALLET and not pending_views.is_empty():
		_retry_pending.call_deferred()


func _retry_pending() -> void:
	var ids := pending_views.keys()
	pending_views.clear()
	for id in ids:
		_update_item(id)


func host_of(slot: Node) -> Node:
	var n: Node = slot
	while n:
		if n.has_method("best_slot_for"):
			return n
		n = n.get_parent()
	return null


func safe_drop_point() -> Vector3:
	return safe_drop.global_position if safe_drop else Vector3(0, 1, 0)


func slot_label(slot_id: String) -> String:
	var s: SnapSlot = slot_nodes.get(slot_id)
	if s == null:
		return "?"
	if s.location_label != "":
		return s.location_label
	var host := host_of(s)
	if host and host.has_method("host_label"):
		return host.host_label()
	return slot_id


# ================================================================= players

func _sync_players() -> void:
	if not Game.in_game:
		return
	var want: Array = Net.all_peers()
	for peer in players.keys():
		if not peer in want:
			players[peer].queue_free()
			players.erase(peer)
	var i := 0
	for peer in want:
		if not players.has(peer):
			var p: Node3D = load(PLAYER_SCENE).instantiate()
			p.name = "Player_%d" % peer
			p.set_multiplayer_authority(peer)
			players_root.add_child(p)
			var sp: Node3D = spawn_points[i % spawn_points.size()] if not spawn_points.is_empty() else null
			if sp:
				p.global_transform = sp.global_transform
			players[peer] = p
		i += 1
	# Items held by players that just appeared can now attach to their hands.
	for it: Item in Game.state.items.values():
		if it.loc.get("t") == "held":
			_update_item(it.id)


func _on_peer_left(peer: int) -> void:
	if Game.is_host() and Game.sim and players.has(peer):
		var pos: Vector3 = players[peer].global_position + Vector3(0, 0.5, 0)
		Game.sim.c_drop_all_held(peer, {"p": [pos.x, pos.y, pos.z]})
	_sync_players()


func local_player() -> Node:
	return players.get(Net.local_peer())


# ================================================================= loose-item transform streaming (host → clients)

func _physics_process(delta: float) -> void:
	if not Game.is_host() or not Net.is_online() or multiplayer.get_peers().is_empty():
		return
	_xf_accum += delta
	if _xf_accum < 1.0 / XFORM_HZ:
		return
	_xf_accum = 0.0
	var ids := PackedInt32Array()
	var data := PackedFloat32Array()
	for id in item_views:
		var v: ItemView = item_views[id]
		if v.freeze or v.sleeping:
			continue
		ids.append(id)
		var p := v.global_position
		var q := v.global_transform.basis.get_rotation_quaternion()
		data.append_array([p.x, p.y, p.z, q.x, q.y, q.z, q.w])
	if not ids.is_empty():
		_rpc_xforms.rpc(ids, data)


@rpc("authority", "call_remote", "unreliable_ordered")
func _rpc_xforms(ids: PackedInt32Array, data: PackedFloat32Array) -> void:
	for i in ids.size():
		var v: ItemView = item_views.get(ids[i])
		if v == null:
			continue
		var o := i * 7
		var xf := Transform3D(Basis(Quaternion(data[o + 3], data[o + 4], data[o + 5], data[o + 6])), Vector3(data[o], data[o + 1], data[o + 2]))
		v.net_target(xf)
