class_name StockTowerView
extends StationBase
## Automated stock tower: a tall cabinet with a glass front, 24 trays (2 columns × 12) and a lift that
## shuttles kit between the conveyor hatches and the trays. Conveyors feed the in-hatch (front-left); sold
## units come out of the out-hatch (front-right) on their own. People can load and pick by hand as well.

const W := 1.3
const D := 0.95
const H := 2.6
const COLS := 2
const ROWS := 12

var _lift: Node3D
var _lift_y := 0.6
var _lift_target := 0.6
var _screen: Label3D
var _tick := 0.0
var _last_count := -1
var _hum: AudioStreamPlayer3D


func _ready() -> void:
	station_type = "stock_tower"
	for k in Automation.TOWER_SLOTS:
		var s := SnapSlot.new()
		s.slot_name = "t%d" % k
		s.accepts = PackedStringArray(["unit", "unit_large"])
		s.location_label = "Stock tower · tray %d" % (k + 1)
		var col := k % COLS
		var row := k / COLS
		s.position = Vector3(-0.3 + col * 0.6, 0.22 + row * 0.19, -0.05)
		s.hint_size = Vector3(0.5, 0.01, 0.5)
		add_child(s)
	super._ready()
	_build()


func _build() -> void:
	var steel := Mats.painted_metal(Color(0.78, 0.8, 0.83))
	var dark := Mats.painted_metal(Color(0.16, 0.17, 0.2))
	var accent := Mats.painted_metal(Color(0.12, 0.42, 0.85))
	# Carcass: back, sides, roof, plinth.
	_box(Vector3(W, H, 0.04), Vector3(0, H * 0.5, -D * 0.5 + 0.02), steel)
	for sx in [-1.0, 1.0]:
		_box(Vector3(0.04, H, D), Vector3(sx * (W * 0.5 - 0.02), H * 0.5, 0), steel)
	_box(Vector3(W, 0.06, D), Vector3(0, H - 0.03, 0), dark)
	_box(Vector3(W, 0.12, D), Vector3(0, 0.06, 0), dark)
	_box(Vector3(W - 0.02, 0.06, 0.02), Vector3(0, H - 0.12, D * 0.5 - 0.01), accent)
	# Trays.
	for row in ROWS:
		for col in COLS:
			_box(Vector3(0.52, 0.012, 0.6), Vector3(-0.3 + col * 0.6, 0.212 + row * 0.19, -0.05), Mats.color(Color(0.55, 0.57, 0.6), 0.4, 0.6))
	# Glass front with a frame, and the two conveyor hatches below it.
	var glass := MeshInstance3D.new()
	var gm := BoxMesh.new()
	gm.size = Vector3(W - 0.1, H - 0.75, 0.01)
	glass.mesh = gm
	glass.material_override = Mats.glass(Color(0.75, 0.85, 0.95, 0.15))
	glass.position = Vector3(0, 0.55 + (H - 0.75) * 0.5 + 0.03, D * 0.5 - 0.01)
	glass.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(glass)
	_box(Vector3(W, 0.5, 0.04), Vector3(0, 0.37, D * 0.5 - 0.02), dark)
	for hx in [-0.35, 0.35]:
		_box(Vector3(0.62, 0.26, 0.05), Vector3(hx, 0.88 + 0.13 - 0.02, D * 0.5 - 0.01), Mats.color(Color(0.05, 0.05, 0.06), 0.8))
		var lbl := Placeholders.text(self, "IN" if hx < 0 else "OUT", Vector3(hx, 1.2, D * 0.5 + 0.005), 40, Color(1, 1, 1), Vector3.ZERO, 0.0012)
		lbl.shaded = false
	# Lift carriage on a mast in the middle.
	_box(Vector3(0.05, H - 0.2, 0.05), Vector3(0, H * 0.5, -0.38), dark)
	_lift = Node3D.new()
	add_child(_lift)
	var plat := MeshInstance3D.new()
	var pm := BoxMesh.new()
	pm.size = Vector3(1.12, 0.03, 0.62)
	plat.mesh = pm
	plat.material_override = Mats.painted_metal(Color(0.95, 0.6, 0.1))
	_lift.add_child(plat)
	# Status screen on the top fascia.
	_box(Vector3(0.44, 0.2, 0.02), Vector3(0, H - 0.3, D * 0.5 + 0.005), Mats.color(Color(0.02, 0.03, 0.05), 0.2))
	_screen = Placeholders.text(self, "", Vector3(0, H - 0.3, D * 0.5 + 0.018), 28, Color(0.45, 0.85, 1.0), Vector3.ZERO, 0.0009)
	_screen.shaded = false
	_screen.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	var sign := Placeholders.text(self, "STOCK TOWER", Vector3(0, H - 0.12, D * 0.5 + 0.02), 40, Color(1, 1, 1), Vector3.ZERO, 0.0012)
	sign.shaded = false
	_hum = Sfx.make_loop("servo_loop", self, -60.0)
	_hum.position = Vector3(0, 1.3, 0)


func _box(size: Vector3, pos: Vector3, mat: Material) -> MeshInstance3D:
	var m := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	m.mesh = bm
	m.material_override = mat
	m.position = pos
	add_child(m)
	return m


func _process(delta: float) -> void:
	_tick -= delta
	if _tick <= 0.0:
		_tick = 0.3
		var n := 0
		var sold := 0
		for s: SnapSlot in slots:
			var it := slot_item(s.slot_name)
			if it:
				n += 1
				if it.unit and it.unit.order_id != 0:
					sold += 1
		if n != _last_count:
			if _last_count >= 0:
				# Something came in or went out: send the lift to the busiest end.
				_lift_target = 0.88 if n < _last_count else 0.22 + randi_range(0, ROWS - 1) * 0.19
			_last_count = n
		_screen.text = "STORED %d / %d\n%s" % [n, Automation.TOWER_SLOTS, ("%d to dispatch" % sold) if sold > 0 else "ready"]
	var was := _lift_y
	_lift_y = move_toward(_lift_y, _lift_target, delta * 0.9)
	_lift.position = Vector3(0, _lift_y, -0.05)
	var moving := absf(_lift_y - was) > 0.0001
	_hum.volume_db = lerpf(_hum.volume_db, -22.0 if moving else -60.0, clampf(delta * 6.0, 0.0, 1.0))
	if moving and not _hum.playing:
		_hum.play()
	elif not moving and _hum.playing and _hum.volume_db < -55.0:
		_hum.stop()
	if not moving and absf(_lift_y - 0.6) > 0.01 and randf() < delta * 0.2:
		_lift_target = 0.6


func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	var n := 0
	for s: SnapSlot in slots:
		if not s.is_free():
			n += 1
	info.lines = ["%d of %d trays in use" % [n, Automation.TOWER_SLOTS], "Conveyor in on the left hatch · sold kit comes out on the right"]
	return info
