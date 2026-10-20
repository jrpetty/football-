class_name ItemView
extends RigidBody3D
## 3D presence of one Item. Reads Game.state; never mutates it directly (all changes go via Game.request).
## Host simulates loose physics; clients follow the host's transforms.

const KIND_VISUAL := {
	"pallet": {"placeholder": "pallet", "size": Vector3(1.2, 0.144, 0.8), "mass": 25.0},
	"usb_test": {"placeholder": "usb_stick", "params": {"color": Color(0.1, 0.45, 0.9)}, "size": Vector3(0.05, 0.03, 0.08), "mass": 0.02, "label": "DIAG"},
	"usb_image": {"placeholder": "usb_stick", "params": {"color": Color(0.95, 0.5, 0.1)}, "size": Vector3(0.05, 0.03, 0.08), "mass": 0.02, "label": "PANE"},
	"parcel": {"placeholder": "cardboard_box", "params": {"size": Vector3(0.42, 0.26, 0.32)}, "size": Vector3(0.42, 0.26, 0.32), "mass": 2.0},
	"package": {"placeholder": "cardboard_box", "params": {"size": Vector3(0.44, 0.12, 0.34), "label": true}, "size": Vector3(0.44, 0.12, 0.34), "mass": 2.2},
}

var item_id: int = 0
var kind: String = ""
var laptop: UnitView = null
var visual_size := Vector3(0.3, 0.02, 0.2)
var pallet_slots: Array = []           # SnapSlots for pallet stacks
var _target_xf: Transform3D
var _following := false                # true when held or in a slot (kinematic follow)
var _follow_node: Node3D = null
var _follow_offset := Transform3D.IDENTITY
var _shape: CollisionShape3D
var _settle_reported := false
var _screen_tex: Texture2D = null


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
		laptop = UnitView.new()
		add_child(laptop)
		laptop.configure(it.unit)
		visual_size = laptop.footprint()
		mass = float(it.unit.model().get("weight_kg", 1.5))
	else:
		var def: Dictionary = KIND_VISUAL.get(kind, {}).duplicate(true)
		if kind == Item.KIND_PACKAGE:
			# Shipping box sized for what's inside (laptop box, big tower box…).
			var ps: Array = Content.form(String(it.data.get("form", "laptop"))).get("package", [0.44, 0.12, 0.34])
			def.size = Vector3(float(ps[0]), float(ps[1]), float(ps[2]))
			def.params = {"size": def.size, "label": true}
		var ms := ModelSlot.new()
		ms.placeholder = def.get("placeholder", "")
		ms.placeholder_params = def.get("params", {})
		add_child(ms)
		visual_size = def.get("size", Vector3(0.3, 0.3, 0.3))
		mass = def.get("mass", 1.0)
		if def.has("label"):
			Placeholders.text(self, def.label, Vector3(0, 0.0086, 0.008), 24, Color(0.1, 0.1, 0.1), Vector3(-90, 0, 0), 0.0006)
		if kind == Item.KIND_PALLET:
			_build_pallet_slots(String(it.data.get("form", "laptop")))
		if kind == Item.KIND_PACKAGE:
			var lbl := Placeholders.text(self, "#%d\n%s" % [int(it.data.get("order_id", 0)), str(it.data.get("buyer", ""))], Vector3(0.09, visual_size.y + 0.003, 0), 22, Color(0.05, 0.05, 0.05), Vector3(-90, 0, 0), 0.0012)
			lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	var bs := BoxShape3D.new()
	bs.size = Vector3(maxf(visual_size.x, 0.05), maxf(visual_size.y, 0.02), maxf(visual_size.z, 0.05))
	_shape.shape = bs
	_shape.position = Vector3(0, visual_size.y / 2, 0)
	physics_material_override = PhysicsMaterial.new()
	physics_material_override.friction = 0.9
	physics_material_override.bounce = 0.05
	apply_state(it, true)


func _build_pallet_slots(form: String) -> void:
	# Grid from economy.json forms.<form>.pallet: laptops lie 3 x 2 x 4 high, towers stand 5 x 2 x 2, …
	# Slot k → column k % cols, row (k / cols) % rows, layer k / (cols * rows).
	var lay: Dictionary = Content.form(form).get("pallet", {})
	var cols := int(lay.get("cols", 3))
	var rows := int(lay.get("rows", 2))
	var layers := int(lay.get("layers", 4))
	var dx := float(lay.get("dx", 0.39))
	var dz := float(lay.get("dz", 0.4))
	var dy := float(lay.get("dy", 0.026))
	for k in cols * rows * layers:
		var s := SnapSlot.new()
		s.slot_name = str(k)
		s.accepts = PackedStringArray(["unit"])
		var col := k % cols
		var row := (k / cols) % rows
		var layer := k / (cols * rows)
		s.position = Vector3((col - (cols - 1) / 2.0) * dx, 0.144 + layer * dy, (row - (rows - 1) / 2.0) * dz)
		s.hint_size = Vector3(dx - 0.04, 0.01, dz - 0.04)
		add_child(s)
		s.setup("i%d" % item_id, "")
		pallet_slots.append(s)


# ================================================================= state → visuals

func apply_state(it: Item, initial: bool = false) -> void:
	var loc := it.loc
	var lr := LevelRoot.current
	if lr == null or not is_inside_tree():
		return
	match loc.get("t"):
		"slot":
			var slot: SnapSlot = lr.slot_nodes.get(loc.slot) if lr else null
			if slot == null:
				lr.pending_views[item_id] = true
				visible = false
				return
			_follow(slot, Transform3D.IDENTITY)
			collision_layer = 1 << 2
		"held":
			var peer := int(loc.get("peer", 0))
			var player: Node3D = lr.players.get(peer) if lr else null
			if player == null or not player.has_method("hold_point"):
				visible = false
				return
			var idx := int(loc.get("i", 0))
			var stack_h := visual_size.y + 0.004
			_follow(player.hold_point(), Transform3D(Basis.IDENTITY, Vector3(0, idx * stack_h, 0)))
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
				freeze = kind == Item.KIND_PALLET   # pallets are moved by machines, not shoved by players
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
	if it.unit and laptop:
		laptop.refresh(it.unit)
		var on_station: bool = loc.get("t") == "slot" and Game.state.slot_station(loc.get("slot", "")) != ""
		laptop.set_lid_open(on_station, not initial)
		if not on_station:
			set_screen(null)


func _follow(node: Node3D, offset: Transform3D) -> void:
	freeze = true
	freeze_mode = RigidBody3D.FREEZE_MODE_KINEMATIC
	visible = true
	var first := _follow_node == null
	_following = true
	_follow_node = node
	_follow_offset = offset
	if first and is_inside_tree():
		global_transform = node.global_transform * offset


func set_screen(tex: Texture2D) -> void:
	if laptop and tex != _screen_tex:
		_screen_tex = tex
		laptop.set_screen_texture(tex)


func _physics_process(delta: float) -> void:
	if _following and not is_instance_valid(_follow_node):
		_following = false
		visible = false
		return
	if _following and is_instance_valid(_follow_node):
		var target := _follow_node.global_transform * _follow_offset
		var held := not (_follow_node is SnapSlot)
		if held:
			# Smooth, slightly laggy follow = weight and sway when carrying.
			var t := clampf(delta * 18.0, 0.0, 1.0)
			global_transform = global_transform.interpolate_with(target, t)
		else:
			global_transform = global_transform.interpolate_with(target, clampf(delta * 22.0, 0.0, 1.0))
		return
	if not Game.is_host():
		if freeze:
			global_transform = global_transform.interpolate_with(_target_xf, clampf(delta * 12.0, 0.0, 1.0))
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
			info["subtitle"] = u.serial
		Item.KIND_PALLET:
			var n := 0
			for s: SnapSlot in pallet_slots:
				if not s.is_free():
					n += 1
			info.lines.append("%d unit(s) on this pallet" % n)
			info.prompt = "Look at a unit to pick it up"
			return info
		Item.KIND_PARCEL:
			info.lines.append(_parcel_summary(it))
			info["hold"] = {"label": "Unbox", "secs": 1.2, "action": "unbox"}
		Item.KIND_PACKAGE:
			info.lines.append("For %s — %s" % [it.data.get("buyer", "?"), it.data.get("model", "")])
			info.lines.append("Put it on the OUTGOING shelf for the courier.")
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
	if it and it.is_carryable():
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
