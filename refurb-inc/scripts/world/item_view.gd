class_name ItemView
extends RigidBody3D
## 3D presence of one Item. Reads Game.state; never mutates it directly (all changes go via Game.request).
## Host simulates loose physics; clients follow the host's transforms.

const KIND_VISUAL := {
	"pallet": {"placeholder": "pallet", "model": "res://assets/models/props/pallet/pallet.tscn", "size": Vector3(1.2, 0.144, 0.8), "mass": 25.0},
	"usb_test": {"placeholder": "usb_stick", "params": {"color": Color(0.1, 0.45, 0.9)}, "model": "res://assets/models/items/usb_stick/usb_stick.tscn", "tint": Color(0.35, 0.6, 1.0), "size": Vector3(0.022, 0.012, 0.06), "mass": 0.02, "label": "DIAG"},
	"usb_image": {"placeholder": "usb_stick", "params": {"color": Color(0.95, 0.5, 0.1)}, "model": "res://assets/models/items/usb_stick/usb_stick.tscn", "size": Vector3(0.022, 0.012, 0.06), "mass": 0.02, "label": "PANE"},
	"parcel": {"placeholder": "cardboard_box", "params": {"size": Vector3(0.42, 0.26, 0.32)}, "model": "res://assets/models/props/box_parcel/box_parcel.tscn", "size": Vector3(0.42, 0.26, 0.32), "mass": 2.0},
	"pallet_truck": {"placeholder": "pallet", "model": "res://assets/models/vehicles/pallet_truck/pallet_truck.tscn", "size": Vector3(0.48, 1.03, 1.55), "mass": 60.0},
	"package": {"placeholder": "cardboard_box", "params": {"size": Vector3(0.44, 0.12, 0.34), "label": true}, "model": "res://assets/models/props/box_laptop/box_laptop.tscn", "size": Vector3(0.44, 0.12, 0.34), "mass": 2.2},
}
const LARGE_BOX_MODEL := "res://assets/models/props/box_heavy/box_heavy.tscn"
## Where a jacked-up pallet sits relative to the pallet truck (forks run along the pallet's 1.2 m side).
const ON_FORKS := Transform3D(Basis(Vector3.UP, PI * 0.5), Vector3(0, 0.035, 0.22))
## Where a pushed pallet truck sits relative to the player (forks ahead, handle at the player's hands).
const PUSHED := Transform3D(Basis(Vector3.UP, PI), Vector3(0, 0.0, -1.02))

var item_id: int = 0
var kind: String = ""
var laptop: LaptopView = null
var visual_size := Vector3(0.3, 0.02, 0.2)
var pallet_slots: Array = []           # SnapSlots for pallet stacks
var _target_xf: Transform3D
var _following := false                # true when held or in a slot (kinematic follow)
var _follow_node: Node3D = null
var _follow_offset := Transform3D.IDENTITY
var _shape: CollisionShape3D
var _settle_reported := false
var _screen_tex: Texture2D = null
var cinematic := false                 # an animation (lorry unloading…) is driving this view; state wins after
var _ride := ""                        # "belt" / "arm": position comes from the conveyor path / the arm each frame
var mug: TeaMug = null
var _belt_geo := {}
var _belt_key := ""
## Where this item was when a robot arm took it (the arm reaches for it there).
var arm_pick_xf := Transform3D.IDENTITY
## Forklifts: the visual rig (moving forks) and a solid body that stops people walking through it when parked.
var forklift: ForkliftRig = null
var _blocker: CollisionShape3D = null
var _rigid := false                    # follow exactly (riding a vehicle), no smoothing lag


func setup(it: Item) -> void:
	item_id = it.id
	kind = it.kind
	name = "Item_%d" % it.id
	collision_layer = 1 << 2          # "items"
	collision_mask = 1 | (1 << 2)     # world + items
	continuous_cd = false
	can_sleep = true
	contact_monitor = true
	max_contacts_reported = 1
	body_entered.connect(_on_body_entered)
	_shape = CollisionShape3D.new()
	add_child(_shape)
	if kind == Item.KIND_UNIT:
		laptop = LaptopView.new()
		add_child(laptop)
		laptop.configure(it.unit)
		visual_size = laptop.footprint()
		mass = float(it.unit.model().get("weight_kg", 1.5))
	elif kind == Item.KIND_MUG:
		mug = TeaMug.make(int(it.data.get("c", 0)), int(it.data.get("s", 0)))
		add_child(mug)
		visual_size = Vector3(0.095, 0.1, 0.095)
		mass = 0.35
	elif kind == Item.KIND_FORKLIFT:
		forklift = ForkliftRig.new()
		forklift.name = "Forklift"
		add_child(forklift)
		visual_size = LevelRoot.FORKLIFT_BOX
		mass = 2600.0
		process_physics_priority = -5          # after the driver moves, before the pallet on its forks
		var sb := StaticBody3D.new()
		sb.name = "Blocker"
		sb.collision_layer = 1                 # parked: people and other vehicles can't go through it
		sb.collision_mask = 0
		_blocker = CollisionShape3D.new()
		var bb := BoxShape3D.new()
		bb.size = Vector3(1.15, 2.0, 2.35)
		_blocker.shape = bb
		_blocker.position = Vector3(0, 1.0, -0.45)
		sb.add_child(_blocker)
		add_child(sb)
	else:
		var def: Dictionary = KIND_VISUAL.get(kind, {}).duplicate(true)
		if kind == Item.KIND_PACKAGE and it.data.has("size"):
			# Big kit ships in a box sized to it.
			var sz: Array = it.data.size
			def.size = Vector3(float(sz[0]), float(sz[1]), float(sz[2]))
			def.params = {"size": def.size, "label": true}
			def.mass = float(it.data.get("carry", 3)) * 6.0
		var ms := ModelSlot.new()
		ms.placeholder = def.get("placeholder", "")
		ms.placeholder_params = def.get("params", {})
		var model_path: String = LARGE_BOX_MODEL if (kind == Item.KIND_PACKAGE and it.data.get("large", false)) else def.get("model", "")
		var use_model := model_path != "" and ResourceLoader.exists(model_path)
		if use_model:
			# Jon's model, stretched to this item's exact size (boxes are sized per order).
			var ps: PackedScene = load(model_path)
			ms.model_scene = ps
			var probe := ps.instantiate()
			var fp: Vector3 = probe.get_meta("footprint", def.get("size", Vector3.ONE))
			probe.free()
			var want: Vector3 = def.get("size", fp)
			ms.model_scale = Vector3(want.x / maxf(fp.x, 0.001), want.y / maxf(fp.y, 0.001), want.z / maxf(fp.z, 0.001))
		add_child(ms)
		if use_model and def.has("tint"):
			for mi in ms.find_meshes():
				var m: Material = mi.get_active_material(0)
				if m is StandardMaterial3D:
					var tinted := (m as StandardMaterial3D).duplicate() as StandardMaterial3D
					tinted.albedo_color = def.tint
					mi.material_override = tinted
		visual_size = def.get("size", Vector3(0.3, 0.3, 0.3))
		mass = def.get("mass", 1.0)
		if def.has("label") and not use_model:
			Placeholders.text(self, def.label, Vector3(0, 0.0086, 0.008), 24, Color(0.1, 0.1, 0.1), Vector3(-90, 0, 0), 0.0006)
		if kind == Item.KIND_PALLET:
			_build_pallet_slots(it)
			process_physics_priority = -2      # pallets move before the units stacked on them
		if kind == Item.KIND_PACKAGE:
			var lbl := Placeholders.text(self, "#%d\n%s" % [int(it.data.get("order_id", 0)), str(it.data.get("buyer", ""))], Vector3(0.09, visual_size.y + 0.003, 0), 22, Color(0.05, 0.05, 0.05), Vector3(-90, 0, 0), 0.0012)
			lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	var bs := BoxShape3D.new()
	bs.size = Vector3(maxf(visual_size.x, 0.05), maxf(visual_size.y, 0.02), maxf(visual_size.z, 0.05))
	_shape.shape = bs
	_shape.position = Vector3(0, visual_size.y / 2, LevelRoot.FORKLIFT_BOX_Z if kind == Item.KIND_FORKLIFT else 0.0)
	physics_material_override = PhysicsMaterial.new()
	physics_material_override.friction = 0.9
	physics_material_override.bounce = 0.05
	apply_state(it, true)


var _built_cell = null


func _rebuild_pallet_slots(it: Item) -> void:
	for s: SnapSlot in pallet_slots:
		s.unregister()
		s.queue_free()
	pallet_slots.clear()
	_build_pallet_slots(it)
	# Re-seat anything already on the pallet onto the new slot nodes.
	if LevelRoot.current:
		for other: Item in Game.state.items.values():
			if other.loc.get("t") == "slot" and str(other.loc.get("slot", "")).begins_with("i%d:" % item_id):
				var v: ItemView = LevelRoot.current.item_views.get(other.id)
				if v:
					v.apply_state(other)


func _build_pallet_slots(it: Item) -> void:
	# Grid sized by the delivery (Sim.pallet_layout): cols × rows per layer, layers stacked by the tallest unit.
	# Old saves without a layout get the original laptop grid (3 × 2, 26 mm layers).
	_built_cell = it.data.get("cell", null)
	var cell: Array = it.data.get("cell", [0.4, 0.026, 0.4])
	var grid: Array = it.data.get("grid", [3, 2])
	var cols := int(grid[0])
	var rows := int(grid[1])
	var per_layer := cols * rows
	var n := maxi(24, per_layer * 2)
	for k in n:
		var s := SnapSlot.new()
		s.slot_name = str(k)
		s.accepts = PackedStringArray(["unit", "unit_large"])
		var col := k % cols
		var row := (k / cols) % rows
		var layer := k / per_layer
		s.position = Vector3(-0.6 + (col + 0.5) * float(cell[0]), 0.144 + layer * float(cell[1]), -0.4 + (row + 0.5) * float(cell[2]))
		s.hint_size = Vector3(float(cell[0]) - 0.05, 0.01, float(cell[2]) - 0.05)
		add_child(s)
		s.setup("i%d" % item_id, "")
		pallet_slots.append(s)


# ================================================================= state → visuals

func state_transform(it: Item) -> Transform3D:
	## Where the state says a loose item is (used to end animations exactly on it).
	var p: Array = it.loc.get("p", [0, 0, 0])
	var r: Array = it.loc.get("r", [0, 0, 0, 1])
	return Transform3D(Basis(Quaternion(r[0], r[1], r[2], r[3]).normalized()), Vector3(p[0], p[1], p[2]))


const STORAGE_TYPES := ["metal_shelf", "heavy_rack", "courier_shelf", "parts_shelf", "stock_tower", "pallet_rack", "pallet_rack_low"]
var _shadows_on := true


func _update_shadows(it: Item) -> void:
	## Stock sitting in storage (shelving, racking, pallets, stock towers) doesn't cast shadows: hundreds of detailed
	## models in every light's shadow pass were most of the frame in a full building. Ambient occlusion still grounds
	## them; anything loose, carried, on a bench or on a belt keeps its shadow.
	var on := true
	if it.loc.get("t") == "slot":
		var host := str(it.loc.get("slot", "")).get_slice(":", 0)
		var on_pallet := host.begins_with("i") and host.substr(1).is_valid_int()
		on = not on_pallet and not str(Game.state.fixtures.get(host, {}).get("type", "")) in STORAGE_TYPES
	if on == _shadows_on:
		return
	_shadows_on = on
	for gi: GeometryInstance3D in find_children("*", "GeometryInstance3D", true, false):
		if not gi.has_meta("cast0"):
			gi.set_meta("cast0", gi.cast_shadow)
		gi.cast_shadow = gi.get_meta("cast0") if on else GeometryInstance3D.SHADOW_CASTING_SETTING_OFF


func apply_state(it: Item, initial: bool = false) -> void:
	var loc := it.loc
	var lr := LevelRoot.current
	if lr == null or not is_inside_tree():
		return
	# Any change wakes the item; it goes back to sleep once it has settled where the state says.
	set_process(true)
	set_physics_process(true)
	_update_shadows(it)
	if cinematic and loc.get("t") == "loose":
		visible = true
		freeze = true
		return
	var was_ride := _ride
	_ride = ""
	_rigid = false
	if forklift:
		forklift.driven = loc.get("t") == "driven"
		if _blocker:
			_blocker.disabled = loc.get("t") != "loose"
		if loc.get("t") != "driven":
			forklift.set_fork_height(float(it.data.get("h", 0.0)))
	if kind == Item.KIND_PALLET:
		# Riding a truck or the forks: the stock on it follows exactly instead of easing after it.
		var riding: bool = loc.get("t") in ["on_truck", "on_forks"]
		for s: SnapSlot in pallet_slots:
			s.rigid = riding
	match loc.get("t"):
		"driven":
			# Someone's in the seat: it goes where they drive (their player node), exactly.
			var driver: Node3D = lr.players.get(int(loc.get("peer", 0))) if lr else null
			if driver == null:
				visible = false
				return
			_follow(driver, Logistics.drive_xf())
			_rigid = true
			collision_layer = 0
		"on_forks":
			var fork_view: ItemView = lr.item_views.get(int(loc.get("fork", 0))) if lr else null
			if fork_view == null or fork_view.forklift == null:
				lr.pending_views[item_id] = true
				visible = false
				return
			_follow(fork_view.forklift.fork_point(), Logistics.pallet_on_forks(0.0, float(loc.get("yaw", 0.0))))
			_rigid = true
			collision_layer = 0
		"belt":
			_ride = "belt"
			_following = false
			_follow_node = null
			freeze = true
			freeze_mode = RigidBody3D.FREEZE_MODE_KINEMATIC
			collision_layer = 1 << 2
			visible = true
			if initial:
				global_transform = _belt_xf(it)
		"arm":
			_ride = "arm"
			if was_ride != "arm":
				arm_pick_xf = global_transform
			_following = false
			_follow_node = null
			freeze = true
			freeze_mode = RigidBody3D.FREEZE_MODE_KINEMATIC
			collision_layer = 0
			visible = true
		"slot":
			var slot: SnapSlot = lr.slot_nodes.get(loc.slot) if lr else null
			if slot == null:
				lr.pending_views[item_id] = true
				visible = false
				return
			_follow(slot, Transform3D.IDENTITY)
			collision_layer = 1 << 2
		"pushed":
			var pusher: Node3D = lr.players.get(int(loc.get("peer", 0))) if lr else null
			if pusher == null:
				visible = false
				return
			_follow(pusher, PUSHED)
			collision_layer = 0
		"on_truck":
			var truck_view: ItemView = lr.item_views.get(int(loc.get("truck", 0))) if lr else null
			if truck_view == null:
				lr.pending_views[item_id] = true
				visible = false
				return
			var on_forks := ON_FORKS
			on_forks.basis = Basis(Vector3.UP, deg_to_rad(float(loc.get("yaw", 90.0))))
			_follow(truck_view, on_forks)
			_rigid = true
			collision_layer = 1 << 2
		"held":
			var peer := int(loc.get("peer", 0))
			var player: Node3D = lr.players.get(peer) if lr else null
			if player == null or not player.has_method("hold_point"):
				visible = false
				return
			var idx := int(loc.get("i", 0))
			var stack_h := visual_size.y + 0.004
			var off := Vector3(0, idx * stack_h, 0)
			if kind == Item.KIND_MUG:
				var n := Game.state.held_by(peer).size()
				off = Vector3((idx - (n - 1) * 0.5) * 0.11, 0, 0.02 * absf(idx - (n - 1) * 0.5))
			_follow(player.hold_point(), Transform3D(Basis.IDENTITY, off))
			collision_layer = 0   # don't block our own interaction ray
		"loose":
			_following = false
			_follow_node = null
			if loc.get("relocate", false):
				visible = false      # moving premises — the new level will place it
				freeze = true
				return
			visible = true
			collision_layer = 1 << 2
			var p: Array = loc.get("p", [0, 0, 0])
			var r: Array = loc.get("r", [0, 0, 0, 1])
			var xf := Transform3D(Basis(Quaternion(r[0], r[1], r[2], r[3]).normalized()), Vector3(p[0], p[1], p[2]))
			_settle_reported = false
			if Game.is_host():
				freeze = kind in [Item.KIND_PALLET, Item.KIND_PALLET_TRUCK, Item.KIND_FORKLIFT]   # moved by trucks, not shoved
				if freeze:
					freeze_mode = RigidBody3D.FREEZE_MODE_KINEMATIC
				global_transform = xf
				if not freeze:
					sleeping = false
			else:
				freeze = true
				freeze_mode = RigidBody3D.FREEZE_MODE_KINEMATIC
				if initial:
					global_transform = xf
				_target_xf = xf
		_:
			visible = false
	if kind == Item.KIND_PALLET and it.data.get("cell", null) != _built_cell:
		_rebuild_pallet_slots(it)
	if it.unit and laptop:
		laptop.refresh(it.unit)
		var sid := Game.state.slot_station(loc.get("slot", "")) if loc.get("t") == "slot" else ""
		# Lids open on benches and racks (the screen shows the job); the packing robot boxes them shut.
		var on_station: bool = sid != "" and Game.state.stations.get(sid, {}).get("type", "") != "robot_pack"
		laptop.set_lid_open(on_station, not initial)
		if not on_station:
			set_screen(null)


func _belt_xf(it: Item) -> Transform3D:
	## Where the state says this item is on its conveyor right now (clients extrapolate the belt clock).
	var loc := it.loc
	var fid := str(loc.get("belt", ""))
	var f: Dictionary = Game.state.fixtures.get(fid, {})
	if f.is_empty():
		return global_transform
	var key := "%s|%s|%s" % [fid, str(f.get("p")), str(f.get("r"))]
	if key != _belt_key:
		_belt_key = key
		_belt_geo = Automation.belt_world(f)
	var now := Game.state.belt_now(Game.is_host())
	var s := float(loc.get("s", 0.0)) + float(loc.get("v", 0.0)) * (now - float(loc.get("at", 0.0)))
	return Automation.belt_point(_belt_geo, minf(s, float(_belt_geo.lb.length)))


func _process(_delta: float) -> void:
	if mug and visible:
		var mi := Game.state.get_item(item_id)
		if mi:
			var now := Game.state.belt_now(Game.is_host())
			var poured := float(mi.data.get("t", -1e9))
			mug.show_state(TeaMug.shown_fill(mi.data, now), TeaMug.brew_colour(mi.data, now), now - poured < 420.0, str(mi.data.get("brew", "")) != "" and not mi.data.get("milk", false) and now - poured < 45.0)
	if _ride == "" or cinematic:
		if mug == null:
			set_process(false)     # nothing to animate until the state changes again
		return
	var it := Game.state.get_item(item_id)
	if it == null:
		return
	if _ride == "belt":
		var target := _belt_xf(it)
		# Hand-overs between conveyors and sorter pushes glide rather than snap.
		var d := global_position.distance_to(target.origin)
		global_transform = target if d < 0.03 else global_transform.interpolate_with(target, clampf(_delta * 14.0, 0.0, 1.0))
	elif _ride == "arm":
		var lr := LevelRoot.current
		var arm: Node = lr.fixture_nodes.get(str(it.loc.get("arm", ""))) if lr else null
		if arm and arm.has_method("carry_xf"):
			global_transform = arm.carry_xf(item_id, arm_pick_xf, visual_size)


func _follow(node: Node3D, offset: Transform3D) -> void:
	freeze = true
	freeze_mode = RigidBody3D.FREEZE_MODE_KINEMATIC
	visible = true
	var first := _follow_node == null
	if _follow_node != node:
		if _follow_node is SnapSlot and is_instance_valid(_follow_node) and _follow_node.moved.is_connected(_wake):
			_follow_node.moved.disconnect(_wake)
		if node is SnapSlot:
			node.moved.connect(_wake)
	_following = true
	_follow_node = node
	_follow_offset = offset
	if first and is_inside_tree():
		global_transform = node.global_transform * offset


func _wake() -> void:
	set_physics_process(true)


func set_screen(tex: Texture2D) -> void:
	if laptop and tex != _screen_tex:
		_screen_tex = tex
		laptop.set_screen_texture(tex)


func _physics_process(delta: float) -> void:
	if cinematic or _ride != "":
		return
	if _following and not is_instance_valid(_follow_node):
		_following = false
		visible = false
		return
	if _following and is_instance_valid(_follow_node):
		var target := _follow_node.global_transform * _follow_offset
		if _rigid or (_follow_node is SnapSlot and (_follow_node as SnapSlot).rigid):
			# Riding a vehicle (or stacked on a pallet that is): exactly where it should be, every frame.
			global_transform = target
			return
		var held := not (_follow_node is SnapSlot)
		if held:
			# Smooth, slightly laggy follow = weight and sway when carrying.
			var t := clampf(delta * 18.0, 0.0, 1.0)
			global_transform = global_transform.interpolate_with(target, t)
		else:
			if global_transform.origin.distance_squared_to(target.origin) < 1e-8 and global_transform.basis.is_equal_approx(target.basis):
				# Settled in its slot: stop updating until the slot moves (SnapSlot.moved) or the state changes.
				global_transform = target
				set_physics_process(false)
			else:
				global_transform = global_transform.interpolate_with(target, clampf(delta * 22.0, 0.0, 1.0))
		return
	if not Game.is_host():
		if freeze:
			global_transform = global_transform.interpolate_with(_target_xf, clampf(delta * 12.0, 0.0, 1.0))
		return
	if global_position.y < -12.0 and LevelRoot.current:
		# Safety net: anything that leaves the world comes back to goods-in.
		global_position = LevelRoot.current.safe_drop_point() + Vector3(0, 0.5, 0)
		linear_velocity = Vector3.ZERO
		angular_velocity = Vector3.ZERO
		_settle_reported = false
		return
	# Host: report when a loose item comes to rest so saves / late joiners see the right spot.
	if not freeze and sleeping and not _settle_reported:
		_settle_reported = true
		var q := global_transform.basis.get_rotation_quaternion()
		var p := global_position
		Game.request("settle", {"item": item_id, "p": [p.x, p.y, p.z], "r": [q.x, q.y, q.z, q.w]})
	elif not sleeping:
		_settle_reported = false
	if global_position.y < -5.0:
		# Fell out of the world: put it back on the floor near the door.
		global_position = LevelRoot.current.safe_drop_point() if LevelRoot.current else Vector3(0, 1, 0)
		linear_velocity = Vector3.ZERO


func net_target(xf: Transform3D) -> void:
	_target_xf = xf


func _on_body_entered(_b: Node) -> void:
	if linear_velocity.length() > 0.8:
		Sfx.play_at("impact_light" if mass < 3.0 else "impact_heavy", global_position, -6.0)


# ================================================================= interaction contract

func hover_info(player: Node) -> Dictionary:
	var it := Game.state.get_item(item_id)
	if it == null:
		return {}
	var info := {"title": it.display_name(), "lines": [], "prompt": ""}
	match kind:
		Item.KIND_UNIT:
			var u := it.unit
			info.lines.append(u.cosmetic_text())
			if u.tested and u.has_open_faults():
				for f in u.open_faults():
					info.lines.append("⚠ " + LotGenerator.fault_text(f))
			info.lines.append(u.stage_text())
			if u.tested:
				info.lines.append(u.spec_line())
			var me := Game.local_player_key()
			if not u.tested and Game.state.has_perk(me, "pre_check"):
				var dead := u.faults.any(func(f): return f.id == "board_no_post")
				var pc := "Pre-check: %s" % ("no power — likely board fault" if dead else "powers on")
				if u.is_laptop() and not dead:
					pc += " · battery reads ~%d%%" % (int(round(u.battery_pct / 5.0)) * 5)
				info.lines.append(pc)
			if u.imaged and u.grade == "" and Game.state.has_perk(me, "keen_eye"):
				info.lines.append("Keen eye: will grade %s" % UnitData.grade_label(Valuation.predicted_grade(u)))
			info["subtitle"] = u.serial
		Item.KIND_PALLET_TRUCK:
			if it.loc.get("t") == "loose":
				info.lines.append("Grab the handle, slide the forks under a pallet and jack it up.")
				info.prompt = "[E] Take the pallet truck"
			return info
		Item.KIND_FORKLIFT:
			var t := str(it.loc.get("t", ""))
			if t == "driven":
				info.lines.append("%s is driving" % Net.display_name(int(it.loc.get("peer", 0))))
			else:
				info.lines.append("Electric counterbalance · forks at %.1f m" % float(it.data.get("h", 0.0)))
				for p: Item in Game.state.items.values():
					if p.kind == Item.KIND_PALLET and p.loc.get("t") == "on_forks" and int(p.loc.get("fork", 0)) == item_id:
						info.lines.append("Carrying %s" % p.display_name())
				info.prompt = "[E] Get in"
			return info
		Item.KIND_PALLET:
			var n := 0
			for s: SnapSlot in pallet_slots:
				if not s.is_free():
					n += 1
			info.lines.append("%d unit(s) on this pallet" % n)
			if it.loc.get("t") == "slot":
				info.lines.append(Logistics.pallet_location(Game.state, it))
			info.prompt = "Look at a unit to pick it up" if n > 0 else "Move it with a pallet truck or forklift"
			return info
		Item.KIND_PARCEL:
			info.lines.append(_parcel_summary(it))
			info["hold"] = {"label": "Unbox", "secs": 1.2, "action": "unbox"}
		Item.KIND_PACKAGE:
			info.lines.append("For %s — %s" % [it.data.get("buyer", "?"), it.data.get("model", "")])
			info.lines.append("Put it on the OUTGOING shelf for the courier.")
		Item.KIND_MUG:
			var d := it.data
			var now := Game.state.belt_now(Game.is_host())
			if float(d.get("fill", 0.0)) > 0.05:
				var by := int(d.get("by", 0))
				if by != 0:
					info.lines.append("Made by %s" % ("you" if by == multiplayer.get_unique_id() else Net.display_name(by)))
				var hot := now - float(d.get("t", now)) < 420.0
				info.lines.append("%s · %s" % ["Hot" if hot else "Gone cold", "milk" if d.get("milk", false) else "no milk yet (fridge)"])
			else:
				info.lines.append("Empty. Fill it at the kettle or wash it up in the sink.")
			info.lines.append("\"%s\"" % str(TeaMug.SLOGANS[clampi(int(d.get("s", 0)), 0, TeaMug.SLOGANS.size() - 1)]).replace("\n", " "))
		Item.KIND_USB_TEST:
			info.lines.append("Plug into a test bench to run full diagnostics.")
		Item.KIND_USB_IMAGE:
			info.lines.append("Plug into a test bench to wipe & install PaneOS.")
	if it.is_carryable():
		info.prompt = "[E] Pick up"
	# Station hold actions apply to the unit sitting on them (repair, grade, pack).
	if it.loc.get("t") == "slot":
		var slot: SnapSlot = LevelRoot.current.slot_nodes.get(it.loc.slot)
		var host := LevelRoot.current.host_of(slot)
		if host and host.has_method("hold_action"):
			var ha: Dictionary = host.hold_action(player, it)
			if not ha.is_empty():
				info["hold"] = ha
			if host.has_method("extra_lines"):
				info.lines.append_array(host.extra_lines(it))
	return info


func _parcel_summary(it: Item) -> String:
	var parts: PackedStringArray = []
	for p in it.data.get("parts", {}):
		parts.append("%d× %s" % [int(it.data.parts[p]), Content.part(p).get("name", p)])
	for k in it.data.get("items", []):
		parts.append("1× %s" % ("Diag USB" if k == "usb_test" else "Imaging USB"))
	for c in it.data.get("consumables", {}):
		parts.append("%d %s" % [int(it.data.consumables[c]), c])
	return ", ".join(parts) if not parts.is_empty() else "Supplies"


func interact(_player: Node) -> void:
	var it := Game.state.get_item(item_id)
	if it and it.kind == Item.KIND_PALLET_TRUCK and it.loc.get("t") == "loose":
		Game.request("take_truck", {"item": item_id})
	elif it and it.kind == Item.KIND_FORKLIFT and it.loc.get("t") == "loose":
		Game.request("forklift_enter", {"item": item_id})
	elif it and it.is_carryable():
		Game.request("pick_up", {"item": item_id})


func hold_complete(player: Node, action: String) -> void:
	if action == "unbox":
		var p := global_position
		Game.request("unbox", {"item": item_id, "p": [p.x, p.y + 0.1, p.z]})
		Sfx.play_at("box_open", global_position)
		return
	var it := Game.state.get_item(item_id)
	if it and it.loc.get("t") == "slot":
		var host := LevelRoot.current.host_of(LevelRoot.current.slot_nodes.get(it.loc.slot))
		if host and host.has_method("hold_complete"):
			host.hold_complete(player, action)


func slot_host_for_placing() -> Node:
	## When a player looks at this item while carrying something, place into whatever holds this item.
	var it := Game.state.get_item(item_id)
	if kind == Item.KIND_PALLET:
		return self
	if it and it.loc.get("t") == "slot" and LevelRoot.current:
		return LevelRoot.current.host_of(LevelRoot.current.slot_nodes.get(it.loc.slot))
	return null


func best_slot_for(item_kind: String, hit_pos: Vector3) -> SnapSlot:
	if kind != Item.KIND_PALLET:
		return null
	return SlotHost.best_slot(pallet_slots, item_kind, hit_pos)


func host_label() -> String:
	return "pallet"
