class_name LevelRoot
extends Node3D
## Root of a playable location (any of the 7 premises; seller sites later reuse these scenes re-dressed).
## Keeps 3D in sync with GameState: equipment (fixtures), one ItemView per world item, one Player per peer.

static var current: LevelRoot
## Title-screen backdrop: build the level but don't start a game or spawn players.
static var menu_mode := false

const PLAYER_SCENE := "res://scenes/player/player.tscn"
const XFORM_HZ := 15.0
const FIT_TOLERANCE := 0.06

@export var building_id: String = "garage"
@export var spawn_points: Array[Node3D] = []
@export var safe_drop: Node3D
@export var lorry_drop: Node3D
@export var parcel_drop: Node3D
## Goods-in area where loose stock/pallets are dropped after a move (centre of a grid).
@export var goods_in: Node3D
@export var environment: WorldEnvironment
@export var sun: DirectionalLight3D
## Default equipment positions: Marker3D children named "<fixture_type>" / "<fixture_type>_2"…
@export var layout: Node3D
## Usable floor, as XZ rectangles in world space. Equipment must sit fully inside one of them.
@export var floor_rects: Array[Rect2] = []
## No-build zones (door aprons, walkways, dressing like the kettle corner).
@export var keepout_rects: Array[Rect2] = []
## Ambient emitter positions (street noise, light hum).
@export var street_sound_pos := Vector3(0, 1.5, 8.0)
@export var hum_sound_pos := Vector3(0, 2.5, -1.0)

var slot_nodes: Dictionary = {}      # full slot id -> SnapSlot
var item_views: Dictionary = {}      # item id -> ItemView
var fixture_nodes: Dictionary = {}   # fixture id -> Node3D
var players: Dictionary = {}         # peer id -> Player
var pending_views: Dictionary = {}   # item ids waiting for their slot node to exist
var items_root: Node3D
var players_root: Node3D
var fixtures_root: Node3D
var _xf_accum := 0.0
var _model_cache: Dictionary = {}    # fixture type -> {placeholder, params, scene}
var site: SiteBounds                 # invisible site fence + guaranteed ground (see site_bounds.gd)


func _enter_tree() -> void:
	current = self


func _exit_tree() -> void:
	if current == self:
		current = null
	if Game.state.changed.is_connected(_on_state_changed):
		Game.state.changed.disconnect(_on_state_changed)


func _ready() -> void:
	fixtures_root = Node3D.new()
	fixtures_root.name = "Fixtures"
	add_child(fixtures_root)
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
	site = SiteBounds.new()
	add_child(site)
	site.build(self)
	_fill_site_ground()
	if menu_mode:
		_preview_fixtures()
		return
	if not Game.in_game:
		# Running this level directly from the editor (F6): start a fresh solo game here.
		Game.new_game()
		if Game.state.building_id != building_id:
			Game.state.building_id = building_id
	else:
		_on_state_reset()


func _fill_site_ground() -> void:
	await get_tree().physics_frame
	await get_tree().physics_frame
	if is_inside_tree():
		site.fill_ground(get_world_3d().direct_space_state)


func respawn_point(i: int = 0) -> Vector3:
	## Where a player who somehow left the world is put back.
	if not spawn_points.is_empty() and spawn_points[i % spawn_points.size()]:
		return spawn_points[i % spawn_points.size()].global_position + Vector3(0, 0.1, 0)
	return safe_drop_point()


func _start_ambience() -> void:
	var street := Sfx.make_loop("street_ambience", self, -8.0, "Ambience")
	street.position = street_sound_pos
	street.unit_size = 10.0
	street.max_distance = 80.0
	var hum := Sfx.make_loop("fluorescent_hum", self, -20.0, "Ambience")
	hum.position = hum_sound_pos
	if street.stream:
		street.play()
	if hum.stream:
		hum.play()


func _apply_graphics() -> void:
	if environment:
		Settings.apply_graphics(environment.environment, sun)


func _on_state_reset() -> void:
	if not is_inside_tree():
		return
	if not Game.state.changed.is_connected(_on_state_changed):
		Game.state.changed.connect(_on_state_changed)
	if Game.is_host() and Game.sim:
		Game.sim.visual_vehicles = has_node("VehicleDirector")
	_rebuild_fixtures()
	# Static slot hosts that aren't fixtures (none in M1, but kept for dressing stations).
	get_tree().call_group("slot_hosts", "register_slots")
	if Game.is_host() and Game.sim:
		_auto_layout()
		_relocate_loose_items()
	_rebuild_items()
	_sync_players()


func _on_state_changed(kind: String, id: Variant) -> void:
	match kind:
		"item":
			_update_item(int(id))
		"fixture":
			_update_fixture(str(id))


# ================================================================= equipment (fixtures)

func _rebuild_fixtures() -> void:
	for n in fixture_nodes.values():
		n.queue_free()
	fixture_nodes.clear()
	for id in Game.state.fixtures:
		_update_fixture(id)


func _update_fixture(id: String) -> void:
	var f: Dictionary = Game.state.fixtures.get(id, {})
	var node: Node3D = fixture_nodes.get(id)
	if f.is_empty() or not f.get("placed", false):
		if node:
			fixture_nodes.erase(id)
			node.queue_free()
			fixtures_root.remove_child(node)
			# Whatever sat on it disappears until it is placed again.
			for it in Game.state.fixture_items(id):
				_update_item(it.id)
		return
	var xf := Transform3D(Basis(Vector3.UP, deg_to_rad(float(f.r))), Vector3(f.p[0], f.p[1], f.p[2]))
	if node:
		node.global_transform = xf
		return
	var def := Content.fixture(f.type)
	if def.is_empty() or not ResourceLoader.exists(def.scene):
		push_warning("Fixture %s has no scene" % id)
		return
	node = load(def.scene).instantiate()
	node.name = "F_" + id
	node.set_meta("fixture_id", id)
	if "station_id" in node:
		node.set("station_id", id)
	if "display_name" in node and str(f.get("label", "")) != "":
		node.set("display_name", f.label)
	if "short_label" in node and str(f.get("label", "")) != "":
		node.set("short_label", f.label)
	fixtures_root.add_child(node)
	node.global_transform = xf
	fixture_nodes[id] = node
	if not pending_views.is_empty():
		_retry_pending.call_deferred()


func fixture_at(n: Node) -> String:
	while n:
		if n.has_meta("fixture_id"):
			return n.get_meta("fixture_id")
		n = n.get_parent()
	return ""


func fixture_model_info(type: String) -> Dictionary:
	## Placeholder/model of a fixture type without running its scripts (for placement ghosts).
	if _model_cache.has(type):
		return _model_cache[type]
	var out := {}
	var def := Content.fixture(type)
	if not def.is_empty() and ResourceLoader.exists(def.scene):
		var inst: Node = load(def.scene).instantiate()
		var ms := inst.get_node_or_null("Model")
		if ms:
			out = {"placeholder": ms.get("placeholder"), "params": ms.get("placeholder_params"), "scene": ms.get("model_scene")}
		inst.free()
	_model_cache[type] = out
	return out


static func footprint_rect(type: String, pos: Vector3, rot_deg: float) -> Rect2:
	## World XZ bounds of a fixture's footprint (rotations snap to 90° steps for the bounds).
	var def := Content.fixture(type)
	var fp: Array = def.get("footprint", [1.0, 1.0])
	var w := float(fp[0])
	var back := float(def.get("back", float(fp[1]) * 0.5))
	var front := float(def.get("front", float(fp[1]) * 0.5))
	var corners := [Vector3(-w / 2, 0, -back), Vector3(w / 2, 0, -back), Vector3(-w / 2, 0, front), Vector3(w / 2, 0, front)]
	var b := Basis(Vector3.UP, deg_to_rad(rot_deg))
	var r := Rect2()
	for i in corners.size():
		var c: Vector3 = b * corners[i] + pos
		if i == 0:
			r = Rect2(c.x, c.z, 0, 0)
		else:
			r = r.expand(Vector2(c.x, c.z))
	return r


func can_place(type: String, pos: Vector3, rot_deg: float, ignore_id: String = "") -> bool:
	var r := footprint_rect(type, pos, rot_deg).grow(-FIT_TOLERANCE)
	var inside := floor_rects.is_empty()
	for fr in floor_rects:
		if fr.encloses(r):
			inside = true
			break
	if not inside:
		return false
	for k in keepout_rects:
		if k.intersects(r):
			return false
	for id in Game.state.fixtures:
		if id == ignore_id:
			continue
		var f: Dictionary = Game.state.fixtures[id]
		if not f.get("placed", false):
			continue
		var o := footprint_rect(f.type, Vector3(f.p[0], f.p[1], f.p[2]), float(f.r)).grow(-FIT_TOLERANCE)
		if o.intersects(r):
			return false
	return true


func _auto_layout() -> void:
	## Host: put any equipment that isn't placed (new game, just moved in, just bought) in a sensible spot.
	var ids: Array = Game.state.fixtures.keys()
	var used := {}
	for id in ids:
		var f: Dictionary = Game.state.fixtures[id]
		if f.get("placed", false):
			continue
		var spot := _layout_spot(f.type, used)
		if spot.is_empty():
			spot = _free_spot(f.type)
		if spot.is_empty():
			Game.toast.emit("No room for %s — it's in storage (Building app)." % f.label, "warn")
			continue
		Game.sim.c_place_fixture(1, {"id": id, "p": spot.p, "r": spot.r})


func _layout_spot(type: String, used: Dictionary) -> Dictionary:
	if layout == null:
		return {}
	for m in layout.get_children():
		if not (m is Node3D) or used.has(m.name):
			continue
		var n := String(m.name)
		if n == type or n.begins_with(type + "_"):
			var p: Vector3 = m.global_position
			var r: float = rad_to_deg(m.global_rotation.y)
			used[m.name] = true
			if can_place(type, p, r):
				return {"p": [p.x, p.y, p.z], "r": snappedf(r, 1.0)}
	return {}


func _free_spot(type: String) -> Dictionary:
	for fr in floor_rects:
		var y := 0.0
		var z := fr.position.y + 0.5
		while z < fr.end.y - 0.4:
			var x := fr.position.x + 0.8
			while x < fr.end.x - 0.6:
				for r in [0.0, 90.0, 180.0, -90.0]:
					if can_place(type, Vector3(x, y, z), r):
						return {"p": [x, y, z], "r": r}
				x += 0.5
			z += 0.5
	return {}


func _relocate_loose_items() -> void:
	var base := goods_in.global_position if goods_in else safe_drop_point()
	var k := 0
	var pk := 0
	for it: Item in Game.state.items.values():
		if it.loc.get("t") != "loose" or not it.loc.get("relocate", false):
			continue
		var p: Vector3
		if it.kind == Item.KIND_PALLET:
			p = base + Vector3((pk % 4) * 1.5 - 2.25, 0.02, -(pk / 4) * 1.2)
			pk += 1
		else:
			p = base + Vector3((k % 6) * 0.5 - 1.25, 0.4 + (k / 24) * 0.3, 1.6 + ((k / 6) % 4) * 0.45)
			k += 1
		Game.state.set_loc(it, {"t": "loose", "p": [p.x, p.y, p.z], "r": [0, 0, 0, 1]})


func _preview_fixtures() -> void:
	## Title-screen backdrop: show the default layout's equipment as plain visuals.
	if layout == null:
		return
	for m in layout.get_children():
		var type := String(m.name).get_slice("_", 0)
		for t in Content.fixtures:
			if String(m.name) == t or String(m.name).begins_with(t + "_"):
				type = t
		var info := fixture_model_info(type)
		if info.is_empty():
			continue
		var ms := ModelSlot.new()
		ms.placeholder = str(info.get("placeholder", ""))
		ms.placeholder_params = info.get("params", {})
		ms.model_scene = info.get("scene")
		fixtures_root.add_child(ms)
		ms.global_transform = m.global_transform


# ================================================================= items

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
		var fid := slot_id.get_slice(":", 0)
		if Game.state.fixtures.has(fid):
			return "%s (in storage)" % Game.state.fixtures[fid].label
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
