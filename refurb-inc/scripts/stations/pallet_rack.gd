class_name PalletRack
extends StationBase
## Pallet racking (and the pallet unload bay, its one-bay floor cousin). Bays for whole pallets: one SnapSlot per
## bay per level ("b<bay>l<level>", geometry from fixtures.json "pallet" via Logistics), so pallets sit where the
## host says they are. Collision is the steelwork only — uprights, frames and beams — so a forklift can drive its
## forks (and a pallet) into a bay. Visual: blue upright frames with bracing, orange step beams, yellow column
## guards, location labels on every beam, a load notice. The bay is painted floor lines and yellow corner guides.

@export var fixture_type := "pallet_rack"

const BLUE := Color(0.12, 0.27, 0.56)
const ORANGE := Color(0.9, 0.42, 0.08)
const YELLOW := Color(0.98, 0.78, 0.08)
const POST := Vector2(0.08, 0.07)        # upright section (x, z)

var _labels: Array = []


func _ready() -> void:
	if Engine.is_editor_hint():
		return
	if station_id != "" and Game.state.fixtures.has(station_id):
		fixture_type = str(Game.state.fixtures[station_id].type)
	_build_collision()
	_build_slots()
	super._ready()
	_build_labels()


func _station_ready() -> void:
	Game.state.changed.connect(_on_fixture_changed)


func _on_fixture_changed(kind: String, id: Variant) -> void:
	if kind == "fixture" and str(id) == station_id:
		_update_label_text()


# ---------------------------------------------------------------- slots & collision

func _build_slots() -> void:
	var d := Logistics.rack_def(fixture_type)
	for n in Logistics.slot_names(fixture_type):
		var s := SnapSlot.new()
		s.slot_name = n
		s.accepts = PackedStringArray([Item.KIND_PALLET])
		s.transform = Logistics.slot_local(fixture_type, n)
		s.hint_size = Vector3(1.18, 0.01, 0.78)
		var bl := Logistics.parse_slot(n)
		s.location_label = "%s · level %d · bay %d" % [display_name, bl.y + 1, bl.x + 1] if (d.get("levels", []) as Array).size() > 1 else display_name
		add_child(s)


func _build_collision() -> void:
	var d := Logistics.rack_def(fixture_type)
	var body := StaticBody3D.new()
	body.name = "Steelwork"
	body.collision_layer = 1 | (1 << 3)     # world + interact
	add_child(body)
	var uh := float(d.get("upright_h", 0.0))
	var bays := int(d.get("bays", 1))
	var bw := float(d.get("bay_w", 1.0))
	var depth := float(d.get("depth", 1.1))
	var w := bays * bw
	if uh <= 0.0:
		# Unload bay: just the four corner guides.
		for c in _bay_corners(d):
			_shape(body, Vector3(0.12, 0.32, 0.12), Vector3(c.x, 0.16, c.y))
		return
	for i in bays + 1:
		var x := -w * 0.5 + i * bw
		_shape(body, Vector3(POST.x, uh, depth), Vector3(x, uh * 0.5, 0))           # a whole upright frame
	for l in (d.get("levels", []) as Array).size():
		var h := float(d.levels[l])
		if h <= 0.01:
			continue
		for sz in [-1.0, 1.0]:
			_shape(body, Vector3(w, 0.1, 0.05), Vector3(0, h - 0.05, sz * (depth * 0.5 - 0.04)))


func _shape(body: StaticBody3D, size: Vector3, pos: Vector3) -> void:
	var cs := CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = size
	cs.shape = bs
	cs.position = pos
	body.add_child(cs)


static func _bay_corners(d: Dictionary) -> Array:
	var hw := float(d.get("bay_w", 1.3)) * 0.5 + 0.05
	var hd := float(d.get("depth", 1.0)) * 0.5 + 0.02
	return [Vector2(-hw, -hd), Vector2(hw, -hd), Vector2(-hw, hd), Vector2(hw, hd)]


# ---------------------------------------------------------------- labels (instance only: they show the fixture label)

func _build_labels() -> void:
	var d := Logistics.rack_def(fixture_type)
	var depth := float(d.get("depth", 1.1))
	if float(d.get("upright_h", 0.0)) <= 0.0:
		var sign := Label3D.new()
		sign.font_size = 64
		sign.pixel_size = 0.0028
		sign.modulate = Color(0.1, 0.1, 0.1)
		sign.outline_size = 0
		sign.rotation_degrees = Vector3(-90, 0, 0)
		sign.position = Vector3(0, 0.006, float(d.get("depth", 1.0)) * 0.5 + 0.09)
		add_child(sign)
		_labels.append(sign)
		_update_label_text()
		return
	for n in Logistics.slot_names(fixture_type):
		var bl := Logistics.parse_slot(n)
		var xf := Logistics.slot_local(fixture_type, n)
		var plate := Label3D.new()
		plate.font_size = 44
		plate.pixel_size = 0.0016
		plate.modulate = Color(0.08, 0.08, 0.1)
		plate.outline_size = 8
		plate.outline_modulate = Color(0.97, 0.97, 0.95)
		var y := xf.origin.y - 0.05 if xf.origin.y > 0.01 else 0.22
		plate.position = Vector3(xf.origin.x, y, depth * 0.5 - 0.01)
		plate.set_meta("bl", bl)
		add_child(plate)
		_labels.append(plate)
	_update_label_text()


func _update_label_text() -> void:
	var label := str(Game.state.fixtures.get(station_id, {}).get("label", display_name))
	var short := label.replace("Racking ", "")
	for plate: Label3D in _labels:
		if plate.has_meta("bl"):
			var bl: Vector2i = plate.get_meta("bl")
			plate.text = "%s-%d-%d" % [short, bl.y + 1, bl.x + 1]
		else:
			plate.text = label.to_upper()
	display_name = label


# ---------------------------------------------------------------- interaction

func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	var used := 0
	for s: SnapSlot in slots:
		if not s.is_free():
			used += 1
	var d := Logistics.rack_def(fixture_type)
	info.title = str(Game.state.fixtures.get(station_id, {}).get("label", display_name))
	if float(d.get("upright_h", 0.0)) > 0.0:
		info.lines = ["%d / %d pallet bays used" % [used, slots.size()],
			"Forklift: every level · pallet truck: floor level only"]
	else:
		info.lines = ["Pallet in the bay: %s" % ("yes" if used > 0 else "empty"),
			"Robot arms in reach unload it onto the line"]
	return info


func item_view_near(_hit_pos: Vector3, _max_dist: float = 0.5) -> ItemView:
	return null       # pallets are moved with trucks; units on them are targeted directly


func best_slot_for(_kind: String, _hit_pos: Vector3) -> SnapSlot:
	return null       # nothing goes in by hand — pallets arrive on forks


# ---------------------------------------------------------------- visual (static: also the placement ghost / menu)

static func build_visual(root: Node3D, type: String) -> void:
	var d := Logistics.rack_def(type)
	var uh := float(d.get("upright_h", 0.0))
	var bb := BoxBatch.new()
	if uh <= 0.0:
		_bay_visual(bb, d)
		bb.commit(root, "Bay")
		return
	var blue := Mats.painted_metal(BLUE)
	var orange := Mats.painted_metal(ORANGE)
	var yellow := Mats.painted_metal(YELLOW)
	var galv := Mats.color(Color(0.66, 0.68, 0.7), 0.45, 0.7)
	var bays := int(d.get("bays", 1))
	var bw := float(d.get("bay_w", 1.0))
	var depth := float(d.get("depth", 1.1))
	var w := bays * bw
	var zf := depth * 0.5 - 0.04
	for i in bays + 1:
		var x := -w * 0.5 + i * bw
		for sz in [-1.0, 1.0]:
			bb.box(Vector3(POST.x, uh, POST.y), Vector3(x, uh * 0.5, sz * zf), blue)
			bb.box(Vector3(0.16, 0.012, 0.14), Vector3(x, 0.006, sz * zf), galv)          # base plate
			bb.box(Vector3(0.02, 0.02, 0.02), Vector3(x + 0.05, 0.016, sz * zf), galv)    # anchor bolt
		# Frame bracing: horizontals and alternating diagonals between front and back posts.
		var n := maxi(2, int(uh / 0.75))
		for k in n + 1:
			var y := 0.12 + k * (uh - 0.25) / n
			bb.beam(Vector3(x, y, -zf), Vector3(x, y, zf), Vector2(0.03, 0.03), blue)
			if k < n:
				var y2 := 0.12 + (k + 1) * (uh - 0.25) / n
				var za := -zf if k % 2 == 0 else zf
				bb.beam(Vector3(x, y, za), Vector3(x, y2, -za), Vector2(0.025, 0.025), blue)
		# Column guards on the front posts of the end frames (forklift bumps).
		if i == 0 or i == bays:
			bb.box(Vector3(0.16, 0.4, 0.14), Vector3(x, 0.2, zf + 0.04), yellow)
			bb.box(Vector3(0.162, 0.05, 0.142), Vector3(x, 0.32, zf + 0.04), Mats.color(Color(0.08, 0.08, 0.08), 0.6))
			bb.box(Vector3(0.162, 0.05, 0.142), Vector3(x, 0.12, zf + 0.04), Mats.color(Color(0.08, 0.08, 0.08), 0.6))
	# Step beams for every raised level, with a little safety lip; timber pallet-support bars across them.
	for l in (d.get("levels", []) as Array).size():
		var h := float(d.levels[l])
		if h <= 0.01:
			continue
		for b in bays:
			var cx := -w * 0.5 + bw * (b + 0.5)
			for sz in [-1.0, 1.0]:
				bb.box(Vector3(bw - POST.x - 0.005, 0.1, 0.05), Vector3(cx, h - 0.05, sz * zf), orange)
				bb.box(Vector3(0.05, 0.12, 0.012), Vector3(cx - bw * 0.5 + 0.06, h - 0.05, sz * (zf + 0.03)), galv)   # connector
				bb.box(Vector3(0.05, 0.12, 0.012), Vector3(cx + bw * 0.5 - 0.06, h - 0.05, sz * (zf + 0.03)), galv)
			for k in 2:
				bb.box(Vector3(0.05, 0.03, depth - 0.1), Vector3(cx + (k - 0.5) * 0.5, h - 0.015, 0), galv)
	# Safe working load notice on the left end frame.
	bb.box(Vector3(0.008, 0.3, 0.42), Vector3(-w * 0.5 - 0.045, 1.5, 0), Mats.color(Color(0.98, 0.98, 0.96), 0.6))
	bb.commit(root, "Rack")
	var notice := Label3D.new()
	notice.text = "SAFE WORKING LOAD\n1000 kg PER LEVEL\nEVENLY DISTRIBUTED\n\nDO NOT CLIMB"
	notice.font_size = 40
	notice.pixel_size = 0.0011
	notice.modulate = Color(0.1, 0.1, 0.1)
	notice.position = Vector3(-w * 0.5 - 0.05, 1.5, 0)
	notice.rotation_degrees = Vector3(0, -90, 0)
	notice.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	root.add_child(notice)


static func _bay_visual(bb: BoxBatch, d: Dictionary) -> void:
	## Painted bay on the floor: a yellow box with hatched ends, yellow/black steel corner guides.
	var paint := Mats.color(YELLOW, 0.75)
	var black := Mats.color(Color(0.07, 0.07, 0.08), 0.6)
	var hw := float(d.get("bay_w", 1.3)) * 0.5 + 0.05
	var hd := float(d.get("depth", 1.0)) * 0.5 + 0.02
	var t := 0.06
	var y := 0.003
	bb.box(Vector3(hw * 2 + t, 0.004, t), Vector3(0, y, -hd), paint)
	bb.box(Vector3(hw * 2 + t, 0.004, t), Vector3(0, y, hd), paint)
	bb.box(Vector3(t, 0.004, hd * 2), Vector3(-hw, y, 0), paint)
	bb.box(Vector3(t, 0.004, hd * 2), Vector3(hw, y, 0), paint)
	# Hatching across the front edge (where the forks come in), clipped inside the box.
	var k := -hw + 0.12
	while k < hw - 0.05:
		bb.box(Vector3(0.035, 0.004, 0.22), Vector3(k, y, hd + 0.16), paint, Vector3(0, 40, 0))
		k += 0.16
	for c in _bay_corners(d):
		# Steel angle guide: a short post, striped.
		bb.box(Vector3(0.1, 0.3, 0.1), Vector3(c.x, 0.15, c.y), Mats.painted_metal(YELLOW))
		bb.box(Vector3(0.102, 0.05, 0.102), Vector3(c.x, 0.2, c.y), black)
		bb.box(Vector3(0.102, 0.05, 0.102), Vector3(c.x, 0.08, c.y), black)
		bb.box(Vector3(0.16, 0.01, 0.16), Vector3(c.x, 0.005, c.y), Mats.color(Color(0.6, 0.62, 0.64), 0.4, 0.7))
