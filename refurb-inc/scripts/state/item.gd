class_name Item
extends RefCounted
## One physical object in the world (laptop, pallet, USB stick, parcel, outgoing package…).
## Every system reads/changes this data; 3D nodes (ItemView) only display it.
##
## Location `loc` is one of:
##   {"t": "slot",  "slot": "bench_1:unit"}         – sitting in a snap slot (stations, shelves, pallets)
##   {"t": "held",  "peer": 1, "i": 0}              – carried by a player (i = position in their stack)
##   {"t": "loose", "p": [x,y,z], "r": [x,y,z,w]}   – resting in the world under physics
##   {"t": "in",    "item": 42}                     – packed inside another item (unit inside a package)
##   {"t": "gone"}                                  – shipped / consumed (kept for records until pruned)

const KIND_UNIT := "unit"
const KIND_PALLET := "pallet"
const KIND_USB_TEST := "usb_test"
const KIND_USB_IMAGE := "usb_image"
const KIND_PARCEL := "parcel"        # incoming supplies from the Shop
const KIND_PACKAGE := "package"      # outgoing customer order

var id: int = 0
var kind: String = KIND_UNIT
var loc: Dictionary = {"t": "gone"}
var unit: UnitData = null            # only for KIND_UNIT
var data: Dictionary = {}            # kind-specific extras (parcel contents, package order id, pallet lot id…)


static func carry_weight(kind_name: String) -> int:
	## How many "carry slots" this kind uses. Players carry up to their capacity (default 3).
	match kind_name:
		KIND_UNIT, KIND_PARCEL, KIND_PACKAGE:
			return 1
		KIND_USB_TEST, KIND_USB_IMAGE:
			return 0
		_:
			return 99   # pallets: need a pallet truck (later milestone)


func is_carryable() -> bool:
	return carry_weight(kind) < 99


func display_name() -> String:
	match kind:
		KIND_UNIT:
			return unit.display_name() if unit else "Unit"
		KIND_PALLET:
			return "Pallet — %s" % data.get("seller_name", "delivery")
		KIND_USB_TEST:
			return "DiagnostiKit Pro USB"
		KIND_USB_IMAGE:
			return "PaneOS Deploy USB"
		KIND_PARCEL:
			return "Parcel — supplies"
		KIND_PACKAGE:
			return "Packed order #%d" % int(data.get("order_id", 0))
	return kind.capitalize()


func to_dict() -> Dictionary:
	var d := {"id": id, "kind": kind, "loc": loc.duplicate(true), "data": data.duplicate(true)}
	if unit:
		d["unit"] = unit.to_dict()
	return d


static func from_dict(d: Dictionary) -> Item:
	var it := Item.new()
	it.id = int(d.get("id", 0))
	it.kind = d.get("kind", KIND_UNIT)
	it.loc = d.get("loc", {"t": "gone"}).duplicate(true)
	it.data = d.get("data", {}).duplicate(true)
	if d.has("unit"):
		it.unit = UnitData.from_dict(d["unit"])
	return it
