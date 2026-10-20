class_name BusinessBot
extends RefCounted
## Plays the business headlessly through the real host commands: buys lots, tests, repairs, images, grades,
## lists, packs and ships, day after day. Used to fill the Insights app with believable history for screenshots,
## and as a quick economy check:  godot --headless --path . res://tools/economy_sim.tscn -- --days 20
##
## It works on the garage's starting equipment ids, so its saves load straight into any premises.

const P := 1

var sim: Sim
var s: GameState
var rng := RandomNumberGenerator.new()
var price_mult := Vector2(0.88, 1.02)    # list at this fraction of guide


func _init(p_sim: Sim, seed_value: int = 7) -> void:
	sim = p_sim
	s = p_sim.state
	rng.seed = seed_value
	sim.visual_vehicles = false
	sim.active_peers = [P]
	_register()


func _register() -> void:
	## Mirror the slots the garage scene registers, so the bot can run without a scene tree.
	s.register_station("bench_1", "bench")
	for n in ["unit"]:
		s.register_slot("bench_1:" + n, ["unit"], "bench_1")
	for n in ["usb", "park_a", "park_b"]:
		s.register_slot("bench_1:" + n, ["usb_test", "usb_image"], "bench_1")
	s.register_station("repair_1", "repair")
	s.register_slot("repair_1:unit", ["unit"], "repair_1")
	s.register_station("grade_1", "grade")
	s.register_slot("grade_1:unit", ["unit"], "grade_1")
	s.register_station("pack_1", "pack")
	s.register_slot("pack_1:unit", ["unit", "package"], "pack_1")
	s.register_station("courier_1", "courier")
	for k in 6:
		s.register_slot("courier_1:%d" % k, ["package"], "courier_1")
	for shelf in ["shelf_a", "shelf_b"]:
		for k in 15:
			s.register_slot("%s:%d" % [shelf, k], ["unit"], "")


func run_days(days: int) -> void:
	for d in days:
		if not s.day_running:
			sim.start_next_day()
		_morning()
		while s.day_running:
			sim.advance_minutes(10.0)
			_work()


# ---------------------------------------------------------------- decisions

func _morning() -> void:
	# Keep the pipeline fed: buy the best-value affordable lot when stock runs low.
	var stock := s.units_in_stock().size()
	var incoming := 0
	for l: Dictionary in s.lots.values():
		if l.status == "bought":
			incoming += int(l.count)
	if stock + incoming < 14:
		var best: Dictionary = {}
		for l: Dictionary in s.lots.values():
			if l.status != "open" or int(l.total_price) > s.cash - 300:
				continue
			if best.is_empty() or float(l.unit_price) < float(best.unit_price) * (0.9 if int(l.count) > int(best.count) else 1.0):
				best = l
		if not best.is_empty():
			sim.execute(P, "buy_lot", {"lot": best.id})
	for k in ["boxes", "boxes_large", "labels", "tape"]:
		if int(s.consumables.get(k, 0)) < (4 if k == "boxes_large" else 8) and not _supply_on_order(k):
			sim.execute(P, "shop_buy", {"id": {"boxes": "boxes_laptop", "boxes_large": "boxes_large", "labels": "labels", "tape": "tape"}[k], "qty": 1})


func _work() -> void:
	for it: Item in s.items.values().filter(func(i): return i.kind == Item.KIND_PARCEL):
		sim.execute(P, "unbox", {"item": it.id})
	_ship()
	_grade()
	_repair()
	_bench()


func _units(pred: Callable) -> Array:
	return s.units_in_stock().filter(func(i): return i.loc.get("t") != "in" and pred.call(i.unit))


func _move(it: Item, slot: String) -> bool:
	if it == null or s.item_in_slot(slot) != null:
		return false
	if sim.execute(P, "pick_up", {"item": it.id}).ok:
		if sim.execute(P, "place", {"slot": slot}).ok:
			return true
		sim.execute(P, "drop", {"p": [0, 0.5, 0]})
	return false


func _free_shelf() -> String:
	for shelf in ["shelf_a", "shelf_b"]:
		for k in 15:
			var sid := "%s:%d" % [shelf, k]
			if s.item_in_slot(sid) == null:
				return sid
	return ""


func _shelve(it: Item) -> void:
	var sid := _free_shelf()
	if sid != "":
		_move(it, sid)


func _bench() -> void:
	if not s.stations.bench_1.job.is_empty():
		return
	var on := s.item_in_slot("bench_1:unit")
	if on:
		var u := on.unit
		if u.tested and not u.has_open_faults() and not u.imaged:
			_use_usb(Item.KIND_USB_IMAGE)
			if s.stations.bench_1.job.is_empty():
				sim.try_start_bench("bench_1", P)
			return
		if u.tested and u.has_open_faults():
			if not _move(on, "repair_1:unit"):
				_shelve(on)
		elif u.imaged:
			if not _move(on, "grade_1:unit"):
				_shelve(on)
		if s.item_in_slot("bench_1:unit") != null:
			return
	# Next job: anything repaired and waiting for an image first, then untested stock.
	var nxt: Array = _units(func(u): return u.tested and not u.has_open_faults() and not u.imaged and u.grade == "")
	var usb := Item.KIND_USB_IMAGE
	if nxt.is_empty():
		nxt = _units(func(u): return not u.tested)
		usb = Item.KIND_USB_TEST
	if nxt.is_empty():
		return
	_use_usb(usb)
	_move(nxt[0], "bench_1:unit")


func _use_usb(kind: String) -> void:
	var cur := s.item_in_slot("bench_1:usb")
	if cur and cur.kind == kind:
		return
	if cur:
		for park in ["bench_1:park_a", "bench_1:park_b"]:
			if _move(cur, park):
				break
	for park in ["bench_1:park_a", "bench_1:park_b"]:
		var it := s.item_in_slot(park)
		if it and it.kind == kind:
			_move(it, "bench_1:usb")
			return


func _repair() -> void:
	var on := s.item_in_slot("repair_1:unit")
	if on == null:
		var waiting: Array = _units(func(u): return u.tested and u.has_open_faults() and u.grade == "")
		waiting = waiting.filter(func(i): return i.loc.get("slot", "") != "bench_1:unit")
		if waiting.is_empty():
			return
		on = waiting[0]
		if not _move(on, "repair_1:unit"):
			return
	var guard := 0
	while on.unit.has_open_faults() and guard < 6:
		guard += 1
		var pv := sim.repair_preview(P, on)
		if pv.get("can", false):
			sim.execute(P, "repair", {"station": "repair_1"})
			continue
		# Missing a part we could fit → order it and park the unit; otherwise it's spares/repairs.
		var why: String = pv.get("why", "")
		if why.begins_with("Need part"):
			for f in on.unit.open_faults():
				var pid := Valuation.repair_part_for(on.unit, f)
				if int(s.parts.get(pid, 0)) <= 0 and not _part_on_order(pid):
					sim.execute(P, "shop_buy", {"id": pid, "qty": 2})
			_shelve(on)
		elif not _move(on, "grade_1:unit"):
			_shelve(on)
		return
	_shelve(on)


func _supply_on_order(k: String) -> bool:
	for ev in s.schedule:
		if ev.type == "parcel" and int(ev.data.get("consumables", {}).get(k, 0)) > 0:
			return true
	return false


func _part_on_order(pid: String) -> bool:
	for ev in s.schedule:
		if ev.type == "parcel" and int(ev.data.get("parts", {}).get(pid, 0)) > 0:
			return true
	return false


func _grade() -> void:
	var on := s.item_in_slot("grade_1:unit")
	if on == null:
		var ready: Array = _units(func(u): return u.imaged and u.grade == "")
		if ready.is_empty():
			return
		on = ready[0]
		if not _move(on, "grade_1:unit"):
			return
	if on.unit.grade == "":
		sim.execute(P, "grade", {"station": "grade_1"})
	var guide := on.unit.list_price
	sim.execute(P, "set_price", {"item": on.id, "price": int(round(guide * rng.randf_range(price_mult.x, price_mult.y)))})
	_shelve(on)
	sim.execute(P, "list", {"item": on.id, "listed": true})


func _ship() -> void:
	var guard := 0
	while guard < 8:
		guard += 1
		var on := s.item_in_slot("pack_1:unit")
		if on and on.kind == Item.KIND_PACKAGE:
			var moved := false
			for k in 6:
				if _move(on, "courier_1:%d" % k):
					moved = true
					break
			if not moved:
				return
			continue
		if on and on.kind == Item.KIND_UNIT:
			if not sim.execute(P, "pack", {"station": "pack_1"}).ok:
				if on.unit.order_id == 0:
					_shelve(on)          # order was cancelled — back to stock
				return
			continue
		var todo: Array = _units(func(u): return u.order_id != 0 and s.orders.get(u.order_id, {}).get("status", "") == "to_pack")
		if todo.is_empty() or not _move(todo[0], "pack_1:unit"):
			return


func finish_for_level() -> void:
	## Hand the world to a real level: loose things get dropped in goods-in.
	for it: Item in s.items.values():
		if it.loc.get("t") == "loose":
			s.set_loc(it, {"t": "loose", "relocate": true, "p": [0, 1, 0], "r": [0, 0, 0, 1]})
		elif it.loc.get("t") == "held":
			s.set_loc(it, {"t": "loose", "relocate": true, "p": [0, 1, 0], "r": [0, 0, 0, 1]})
