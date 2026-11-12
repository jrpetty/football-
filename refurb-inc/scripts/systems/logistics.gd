class_name Logistics
extends RefCounted
## Whole-pallet logistics: pallet racking and unload bays (slot geometry from fixtures.json "pallet"), the
## forklift's geometry, and where a unit on a pallet is (Stock app). Everything here is a pure function of
## GameState + data, so Sim (host validation), the views, the driving code and headless tests all agree on
## where every pallet bay is without needing a level loaded.
##
## Pallet slots are "<fixture id>:b<bay>l<level>" (bay 0 = left as you face the rack, level 0 = the floor).
## A pallet's origin is its bottom-centre; in a bay it sits on the beam (or the floor) facing out of the rack.

const PALLET := Vector3(1.2, 0.144, 0.8)
## Fork travel (m above the floor the pallet's base can be lifted to).
const FORK_MAX := 3.25
## A hand pallet truck only reaches bays at floor level.
const TRUCK_MAX_LEVEL := 0.25
## A carried pallet whose centre is within this (XZ) of a free bay drops into it as it's lowered.
const SNAP_XZ := 0.4
## Host check: what the driver says vs where the pallet / bay really is.
const VERIFY_XZ := 0.85
const VERIFY_Y := 0.5

# ---------------------------------------------------------------- forklift geometry (model space: +Z = forks)
## Jon's counterbalance model is a touch small for a seated adult; scaled up it's a typical 1.5 t truck.
const FL_SCALE := 1.12
## Seat cushion (top) — the driver's hips sit here.
const FL_SEAT := Vector3(0.0, 1.0 * FL_SCALE, -0.56 * FL_SCALE)
## Face of the fork backrest (pallets are pushed up against it) and the forks' centre line.
const FL_HEEL_Z := 0.74 * FL_SCALE
const FL_FORK_X := -0.02 * FL_SCALE
const FL_FORK_LEN := 0.82 * FL_SCALE
## Driver's eye above the floor.
const FL_EYE := 1.86
## Body box for collisions (x half-width, z from back to the mast) — forks/pallet are checked separately.
const FL_HALF_W := 0.56
const FL_BACK_Z := -1.62
const FL_FRONT_Z := 0.84          # = the backrest: a pallet you drive the forks into stops against it
const FL_WHEELBASE := 1.25
const FL_FRONT_AXLE_Z := 0.25 * FL_SCALE


static func drive_xf() -> Transform3D:
	## Forklift model → driver (player) space: the driver faces -Z and sits at the origin.
	return Transform3D(Basis(Vector3.UP, PI), Vector3(0, 0, FL_SEAT.z))


static func pallet_on_forks(h: float, yaw_deg: float) -> Transform3D:
	## Where a carried pallet sits in forklift model space: base at fork height h, pushed against the backrest.
	var across := absf(fposmod(yaw_deg, 180.0) - 90.0) < 45.0      # turned 90°: its 1.2 m side runs along the forks
	var half := PALLET.x * 0.5 if across else PALLET.z * 0.5
	return Transform3D(Basis(Vector3.UP, deg_to_rad(yaw_deg)), Vector3(FL_FORK_X, h, FL_HEEL_Z + half))


static func fork_centre_local(h: float) -> Vector3:
	## Middle of the forks (model space) — where a pallet's centre needs to be to be picked up.
	return Vector3(FL_FORK_X, h, FL_HEEL_Z + FL_FORK_LEN * 0.5)


# ---------------------------------------------------------------- racks & bays

static func rack_def(type: String) -> Dictionary:
	return Content.fixture(type).get("pallet", {})


static func is_pallet_fixture(type: String) -> bool:
	return not rack_def(type).is_empty()


static func slot_names(type: String) -> Array:
	var d := rack_def(type)
	var out: Array = []
	for l in (d.get("levels", []) as Array).size():
		for b in int(d.get("bays", 0)):
			out.append("b%dl%d" % [b, l])
	return out


static func parse_slot(name: String) -> Vector2i:
	## "b2l1" → (bay 2, level 1); (-1, -1) if it isn't a pallet bay name.
	if not name.begins_with("b") or not "l" in name:
		return Vector2i(-1, -1)
	var b := name.substr(1).get_slice("l", 0)
	var l := name.get_slice("l", 1)
	if not b.is_valid_int() or not l.is_valid_int():
		return Vector2i(-1, -1)
	return Vector2i(int(b), int(l))


static func slot_local(type: String, name: String) -> Transform3D:
	## A bay's pallet transform in the fixture's own space (origin = pallet bottom-centre).
	var d := rack_def(type)
	var bl := parse_slot(name)
	var levels: Array = d.get("levels", [0.0])
	if bl.x < 0 or bl.y >= levels.size():
		return Transform3D.IDENTITY
	var bay_w := float(d.get("bay_w", 1.0))
	var w := bay_w * int(d.get("bays", 1))
	var x := -w * 0.5 + bay_w * (bl.x + 0.5)
	# Narrow racking bays take the pallet end-on (its 1.2 m length runs into the rack); a wide floor bay lies it flat.
	var basis := Basis.IDENTITY if bay_w >= PALLET.x else Basis(Vector3.UP, PI * 0.5)
	return Transform3D(basis, Vector3(x, float(levels[bl.y]), 0.0))


static func level_height(type: String, name: String) -> float:
	var levels: Array = rack_def(type).get("levels", [0.0])
	var bl := parse_slot(name)
	return float(levels[bl.y]) if bl.y >= 0 and bl.y < levels.size() else 0.0


static func slot_world(st: GameState, slot_id: String) -> Variant:
	## World transform of a pallet bay (null if it isn't one, or its racking is in storage).
	var fid := slot_id.get_slice(":", 0)
	var f: Dictionary = st.fixtures.get(fid, {})
	if f.is_empty() or not f.get("placed", false) or not is_pallet_fixture(str(f.type)):
		return null
	var name := slot_id.get_slice(":", 1)
	if parse_slot(name).x < 0:
		return null
	return Automation.fixture_xf(f) * slot_local(str(f.type), name)


static func pallet_slots(st: GameState, placed_only: bool = true) -> Array:
	## Every pallet bay in the premises: [{id, fid, type, xf, level, h, free}].
	var out: Array = []
	for fid in st.fixtures:
		var f: Dictionary = st.fixtures[fid]
		if not is_pallet_fixture(str(f.get("type", ""))) or (placed_only and not f.get("placed", false)):
			continue
		var fxf := Automation.fixture_xf(f)
		for n in slot_names(str(f.type)):
			var sid := "%s:%s" % [fid, n]
			out.append({"id": sid, "fid": fid, "type": str(f.type), "xf": fxf * slot_local(str(f.type), n),
				"level": parse_slot(n).y, "h": level_height(str(f.type), n), "free": st.item_in_slot(sid) == null,
				"racked": float(rack_def(str(f.type)).get("upright_h", 0.0)) > 0.0})
	return out


static func pallet_pos(st: GameState, it: Item) -> Variant:
	## Where a pallet's base centre is (loose or in a bay); null while it's being carried.
	match str(it.loc.get("t", "")):
		"loose":
			var p: Array = it.loc.get("p", [0, 0, 0])
			return Vector3(float(p[0]), float(p[1]), float(p[2]))
		"slot":
			var xf = slot_world(st, str(it.loc.get("slot", "")))
			return (xf as Transform3D).origin if xf != null else null
	return null


static func bay_station(st: GameState, slot_id: String) -> String:
	## The "station" kind of the fixture a pallet slot belongs to ("pallet_bay" for unload bays, "" for racking).
	var f: Dictionary = st.fixtures.get(slot_id.get_slice(":", 0), {})
	return str(rack_def(str(f.get("type", ""))).get("station", ""))


static func find_set_down(st: GameState, base: Vector3, yaw_deg: float = 0.0) -> Dictionary:
	## Where a pallet lowered from `base` (its bottom-centre, world) would land: the free bay it's lined up with,
	## or {"blocked": h, "why": …} when it's over a beam it can't go through, else {} (the floor).
	var column := {}             # the highest bay it's lined up with, at or below it
	var column_d := INF
	var block := {}
	for s in pallet_slots(st):
		var o: Vector3 = (s.xf as Transform3D).origin
		var d := Vector2(o.x - base.x, o.z - base.z).length()
		var h := float(s.h)
		if h > base.y + 0.02:
			continue                 # a level above the pallet
		if d <= SNAP_XZ:
			if column.is_empty() or h > float(column.h) + 0.001 or (absf(h - float(column.h)) <= 0.001 and d < column_d):
				column = s
				column_d = d
		elif s.racked and h > 0.01 and d < 0.75:
			# Over a beam of this level but not lined up with a bay: it would rest on the beam, not go through it.
			if block.is_empty() or h + 0.12 > float(block.blocked):
				block = {"blocked": h + 0.12, "why": "Line it up with a bay"}
	if not column.is_empty():
		var ch := float(column.h)
		if not block.is_empty() and float(block.blocked) > ch + 0.12:
			return block
		if column.free:
			return {"slot": column.id, "h": ch, "xf": column.xf}
		return {"blocked": ch + 0.12, "why": "Bay taken"}
	return block


# ---------------------------------------------------------------- labels (Stock app)

static func fixture_number_label(st: GameState, type: String) -> String:
	## "Racking R3" / "Unload bay 2": racks of every size share one numbering.
	var pat := str(rack_def(type).get("label", ""))
	if pat == "":
		return ""
	var n := 1
	for f in st.fixtures.values():
		if str(rack_def(str(f.get("type", ""))).get("label", "")) == pat:
			n += 1
	return pat % n


static func slot_label(st: GameState, slot_id: String) -> String:
	## "Racking R1 · level 2 · bay 3" for a pallet bay (the floor is level 1).
	var fid := slot_id.get_slice(":", 0)
	var f: Dictionary = st.fixtures.get(fid, {})
	if f.is_empty():
		return ""
	var label := str(f.get("label", fid))
	var bl := parse_slot(slot_id.get_slice(":", 1))
	var d := rack_def(str(f.type))
	if bl.x < 0:
		return label
	var parts: PackedStringArray = [label]
	if (d.get("levels", []) as Array).size() > 1:
		parts.append("level %d" % (bl.y + 1))
	if int(d.get("bays", 1)) > 1:
		parts.append("bay %d" % (bl.x + 1))
	var out := " · ".join(parts)
	if not f.get("placed", false):
		out += " (in storage)"
	return out


static func pallet_location(st: GameState, pallet: Item) -> String:
	match str(pallet.loc.get("t", "")):
		"slot":
			return slot_label(st, str(pallet.loc.get("slot", "")))
		"on_forks":
			return "On the forklift"
		"on_truck":
			return "On a pallet truck"
		"loose":
			return "Pallet (goods-in)" if pallet.loc.get("relocate", false) else "Pallet (on the floor)"
	return "Pallet"


static func unit_location(st: GameState, it: Item) -> String:
	## Where a unit sitting on a pallet is ("" if it isn't on a pallet).
	if it.loc.get("t") != "slot":
		return ""
	var sid := str(it.loc.get("slot", ""))
	if not sid.begins_with("i"):
		return ""
	var pallet := st.get_item(int(sid.substr(1).get_slice(":", 0)))
	return pallet_location(st, pallet) if pallet else "Pallet"


static func within_reach_on_foot(st: GameState, it: Item) -> bool:
	## A unit on a pallet stored up in the racking (or raised high on the forks) can't be picked off by hand —
	## bring the pallet down first. Everything else is fine.
	if it.loc.get("t") != "slot" or not str(it.loc.get("slot", "")).begins_with("i"):
		return true
	var pallet := st.get_item(int(str(it.loc.slot).substr(1).get_slice(":", 0)))
	if pallet == null:
		return true
	match str(pallet.loc.get("t", "")):
		"slot":
			var sid := str(pallet.loc.get("slot", ""))
			var f: Dictionary = st.fixtures.get(sid.get_slice(":", 0), {})
			return f.is_empty() or level_height(str(f.get("type", "")), sid.get_slice(":", 1)) <= TRUCK_MAX_LEVEL
		"on_forks":
			var fork := st.get_item(int(pallet.loc.get("fork", 0)))
			return fork == null or float(fork.data.get("h", 0.0)) <= 1.2
	return true


static func load_height(st: GameState, pallet: Item) -> float:
	## How tall a pallet and its stack are (m): the pallet plus every layer that has something on it.
	var cell: Array = pallet.data.get("cell", [0.4, 0.026, 0.4])
	var grid: Array = pallet.data.get("grid", [3, 2])
	var per_layer := maxi(int(grid[0]) * int(grid[1]), 1)
	var top := -1
	var prefix := "i%d:" % pallet.id
	for it: Item in st.items.values():
		if it.loc.get("t") == "slot" and str(it.loc.get("slot", "")).begins_with(prefix):
			top = maxi(top, int(str(it.loc.slot).get_slice(":", 1)) / per_layer)
	return PALLET.y + (top + 1) * float(cell[1])


static func units_on(st: GameState, pallet_id: int) -> Array:
	var out: Array = []
	var prefix := "i%d:" % pallet_id
	for it: Item in st.items.values():
		if it.loc.get("t") == "slot" and str(it.loc.get("slot", "")).begins_with(prefix):
			out.append(it)
	return out
