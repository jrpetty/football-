class_name StaffPlanner
extends RefCounted
## Decides a staff member's next job and turns it into steps the body can walk through:
##   ["walk", Vector3]  ·  ["cmd", name, args]  ·  ["hold", seconds, cmd, args]  ·  ["wait", seconds]
## Every job uses real host commands as the staff member's own peer, exactly like a player would.

var peer: int
var role: String
var sim: Sim
var s: GameState


func _init(p_peer: int, p_role: String) -> void:
	peer = p_peer
	role = p_role
	sim = Game.sim
	s = Game.state


func next_plan() -> Array:
	var order: Array
	match role:
		"hauler":
			order = ["ship", "unbox", "haul"]
		"tester":
			order = ["bench"]
		"repair_tech":
			order = ["repair"]
		"grader":
			order = ["grade"]
		"packer":
			order = ["ship", "pack"]
		"buyer":
			order = ["buy"]
		"salesperson":
			order = ["sell"]
		_:
			order = ["ship", "pack", "bench", "repair", "grade", "unbox", "haul"]
	for job in order:
		var p: Array = call("_plan_" + job)
		if not p.is_empty():
			return p
	return []


# ================================================================= helpers

func _hosts(type: String) -> Array:
	var out: Array = []
	for sid in s.stations:
		if s.stations[sid].get("type", "") == type and s.fixtures.get(sid, {}).get("placed", true):
			out.append(sid)
	return out


func _slot_pos(slot: String) -> Vector3:
	## Where to stand to reach a slot: in front of its host, level with the slot.
	var lr := LevelRoot.current
	var node: SnapSlot = lr.slot_nodes.get(slot) if lr else null
	if node == null:
		return Vector3.INF
	var host := lr.host_of(node) as Node3D
	var front := host.global_basis.z.normalized() if host else Vector3.FORWARD
	var p := node.global_position + front * 0.75
	p.y = 0.0
	return p


func _item_pos(it: Item) -> Vector3:
	var lr := LevelRoot.current
	match str(it.loc.get("t")):
		"slot":
			var sl := str(it.loc.slot)
			if sl.begins_with("i"):
				var v: ItemView = lr.item_views.get(it.id) if lr else null
				if v:
					var p := v.global_position
					return Vector3(p.x, 0, p.z) + Vector3(0, 0, 0.9)
			return _slot_pos(sl)
		"loose":
			var pp: Array = it.loc.get("p", [0, 0, 0])
			return Vector3(pp[0], 0, pp[2])
	return Vector3.INF


func _free_slot(host: String, name: String = "unit") -> String:
	var sid := "%s:%s" % [host, name]
	return sid if s.slot_defs.has(sid) and s.item_in_slot(sid) == null else ""


func _shelf_slot(it: Item) -> String:
	## A free storage space that fits (big kit only on heavy racking).
	var kind := it.slot_kind()
	for sid in s.slot_defs:
		var host := str(sid).get_slice(":", 0)
		if host.begins_with("i") or s.stations.get(host, {}).get("type", "") != "":
			continue
		if s.stations.has(host) and s.stations[host].get("type", "") != "":
			continue
		if not s.fixtures.has(host) or s.item_in_slot(sid) != null or not s.slot_accepts(sid, kind):
			continue
		if str(sid).ends_with(":load"):
			continue
		return sid
	return ""


func _move(it: Item, to_slot: String) -> Array:
	var from := _item_pos(it)
	var to := _slot_pos(to_slot)
	if from == Vector3.INF or to == Vector3.INF:
		return []
	return [["walk", from], ["cmd", "pick_up", {"item": it.id}], ["walk", to], ["cmd", "place", {"slot": to_slot}]]


func _units(pred: Callable) -> Array:
	return s.units_in_stock().filter(func(i): return i.loc.get("t") in ["slot", "loose"] and pred.call(i.unit) and not _busy(i))


func _busy(it: Item) -> bool:
	## Already on a station that's working on it, or claimed by another staff member this moment.
	if StaffView.claimed.has(it.id) and StaffView.claimed[it.id] != peer:
		return true
	var sl := str(it.loc.get("slot", ""))
	var host := sl.get_slice(":", 0)
	var st: Dictionary = s.stations.get(host, {})
	return not st.get("job", {}).is_empty()


func _on_type(it: Item, type: String) -> bool:
	var sl := str(it.loc.get("slot", ""))
	return s.stations.get(sl.get_slice(":", 0), {}).get("type", "") == type


# ================================================================= jobs

func _plan_ship() -> Array:
	for host in _hosts("pack"):
		var it := s.station_slot_item(host, "unit")
		if it and it.kind == Item.KIND_PACKAGE:
			for courier in _hosts("courier"):
				for sid in s.slot_defs:
					if str(sid).begins_with(courier + ":") and s.item_in_slot(sid) == null and s.slot_accepts(sid, it.slot_kind()):
						StaffView.claim(it.id, peer)
						return _move(it, sid)
	return []


func _plan_pack() -> Array:
	for host in _hosts("pack"):
		var on := s.station_slot_item(host, "unit")
		if on and on.kind == Item.KIND_UNIT and on.unit.order_id != 0:
			return [["walk", _slot_pos(host + ":unit")], ["hold", float(Content.econ("pack_secs", 5.0)) / sim._skill_speed(peer, "packing"), "pack", {"station": host}]]
		if on == null:
			var todo := _units(func(u): return u.order_id != 0 and s.orders.get(u.order_id, {}).get("status", "") == "to_pack")
			if not todo.is_empty():
				StaffView.claim(todo[0].id, peer)
				var p := _move(todo[0], host + ":unit")
				if not p.is_empty():
					p.append(["hold", float(Content.econ("pack_secs", 5.0)) / sim._skill_speed(peer, "packing"), "pack", {"station": host}])
				return p
	return []


func _plan_bench() -> Array:
	for host in _hosts("bench"):
		var st: Dictionary = s.stations.get(host, {})
		if not st.get("job", {}).is_empty():
			continue
		var on := s.station_slot_item(host, "unit")
		var usb := s.station_slot_item(host, "usb")
		if on and on.unit:
			var u := on.unit
			if u.tested and not u.has_open_faults() and not u.imaged:
				if usb == null or usb.kind != Item.KIND_USB_IMAGE:
					return _swap_usb(host, Item.KIND_USB_IMAGE)
				continue
			# Finished here: faulty → repair bench / shelf, imaged → grading desk / shelf.
			var dest := ""
			if u.tested and u.has_open_faults():
				for r in _hosts("repair"):
					dest = _free_slot(r)
					if dest != "":
						break
			elif u.imaged:
				for g in _hosts("grade"):
					dest = _free_slot(g)
					if dest != "":
						break
			if dest == "":
				dest = _shelf_slot(on)
			if dest != "":
				StaffView.claim(on.id, peer)
				return _move(on, dest)
			continue
		if on == null:
			var nxt := _units(func(u): return u.tested and not u.has_open_faults() and not u.imaged and u.grade == "")
			var want := Item.KIND_USB_IMAGE
			if nxt.is_empty():
				nxt = _units(func(u): return not u.tested)
				want = Item.KIND_USB_TEST
			nxt = nxt.filter(func(i): return not _on_type(i, "bench"))
			if nxt.is_empty():
				continue
			if usb == null or usb.kind != want:
				var sw := _swap_usb(host, want)
				if not sw.is_empty():
					return sw
				continue
			StaffView.claim(nxt[0].id, peer)
			return _move(nxt[0], host + ":unit")
	return []


func _swap_usb(host: String, kind: String) -> Array:
	var cur := s.station_slot_item(host, "usb")
	var steps: Array = []
	if cur:
		for park in ["park_a", "park_b"]:
			var ps := _free_slot(host, park)
			if ps != "":
				steps.append_array(_move(cur, ps))
				break
		if steps.is_empty():
			return []
	for park in ["park_a", "park_b"]:
		var it := s.station_slot_item(host, park)
		if it and it.kind == kind:
			var p := _slot_pos(host + ":usb")
			steps.append_array([["walk", p], ["cmd", "pick_up", {"item": it.id}], ["cmd", "place", {"slot": host + ":usb"}]])
			return steps
	return []


func _plan_repair() -> Array:
	for host in _hosts("repair"):
		var on := s.station_slot_item(host, "unit")
		if on and on.unit and on.unit.tested:
			if not on.unit.has_open_faults():
				var dest := _shelf_slot(on)
				for b in _hosts("bench"):
					if not on.unit.imaged and _free_slot(b) != "":
						dest = _free_slot(b)
						break
				return _move(on, dest) if dest != "" else []
			var pv := sim.repair_preview(peer, on)
			if pv.get("can", false):
				return [["walk", _slot_pos(host + ":unit")], ["hold", float(pv.secs), "repair", {"station": host}]]
			var why: String = pv.get("why", "")
			if why.begins_with("Need part"):
				for f in on.unit.open_faults():
					var pid := Valuation.repair_part_for(on.unit, f)
					if int(s.parts.get(pid, 0)) <= 0 and not _part_on_order(pid) and s.cash > 600:
						sim.execute(peer, "shop_buy", {"id": pid, "qty": 2})
			var shelf := _shelf_slot(on)
			return _move(on, shelf) if shelf != "" else []
		if on == null:
			var level := s.skill_level(Sim.player_key(peer), "repair")
			var tiers: Dictionary = Content.skills_data.get("repair_tiers", {"1": 1, "2": 4, "3": 7})
			var faulty := _units(func(u): return u.tested and u.has_open_faults() and u.grade == "")
			for it: Item in faulty:
				if _on_type(it, "repair"):
					continue
				var fixable := it.unit.open_faults().any(func(f):
					var fd := Content.fault(f.id)
					return not fd.get("unrepairable", false) and level >= int(tiers.get(str(int(fd.get("repair_level", 1))), 1)) and int(s.parts.get(Valuation.repair_part_for(it.unit, f), 0)) > 0)
				if fixable:
					StaffView.claim(it.id, peer)
					return _move(it, host + ":unit")
	return []


func _part_on_order(pid: String) -> bool:
	for ev in s.schedule:
		if ev.type == "parcel" and int(ev.data.get("parts", {}).get(pid, 0)) > 0:
			return true
	return false


func _plan_grade() -> Array:
	for host in _hosts("grade"):
		var on := s.station_slot_item(host, "unit")
		if on and on.unit:
			if on.unit.grade == "":
				return [["walk", _slot_pos(host + ":unit")], ["hold", float(Content.econ("grade_secs", 4.0)) / sim._skill_speed(peer, "grading"), "grade", {"station": host}],
					["cmd", "list", {"item": on.id, "listed": true}]]
			if not on.unit.listed:
				return [["walk", _slot_pos(host + ":unit")], ["cmd", "list", {"item": on.id, "listed": true}]]
			var shelf := _shelf_slot(on)
			return _move(on, shelf) if shelf != "" else []
		var ready := _units(func(u): return u.imaged and u.grade == "")
		ready = ready.filter(func(i): return not _on_type(i, "grade") and not _on_type(i, "bench"))
		if not ready.is_empty():
			StaffView.claim(ready[0].id, peer)
			return _move(ready[0], host + ":unit")
	return []


func _plan_unbox() -> Array:
	for it: Item in s.items.values():
		if it.kind == Item.KIND_PARCEL and it.loc.get("t") in ["loose", "slot"] and not _busy(it):
			var p := _item_pos(it)
			if p != Vector3.INF:
				StaffView.claim(it.id, peer)
				return [["walk", p], ["hold", 1.5, "unbox", {"item": it.id}]]
	return []


func _plan_haul() -> Array:
	# Off pallets / the floor onto shelving.
	for it: Item in s.units_in_stock():
		if _busy(it):
			continue
		var t := str(it.loc.get("t"))
		var on_pallet := t == "slot" and str(it.loc.slot).begins_with("i")
		if not (on_pallet or t == "loose"):
			continue
		var shelf := _shelf_slot(it)
		if shelf != "":
			StaffView.claim(it.id, peer)
			return _move(it, shelf)
	return []


func _office_spot() -> Vector3:
	for sid in s.stations:
		if s.stations[sid].get("type", "") == "office":
			var lr := LevelRoot.current
			var n: Node3D = lr.fixture_nodes.get(sid) if lr else null
			if n:
				var p := n.global_position + n.global_basis.z * 0.7
				return Vector3(p.x, 0, p.z)
	return Vector3.INF


func _plan_buy() -> Array:
	var spot := _office_spot()
	var steps: Array = []
	if spot != Vector3.INF:
		steps.append(["walk", spot])
	steps.append(["wait", 6.0])
	# Keep the pipeline fed: buy when little is waiting to be tested and cash is healthy.
	var waiting := s.units_in_stock().filter(func(i): return not i.unit.tested).size()
	if waiting < 8 and s.cash > 1500:
		var best := {}
		var best_r := 0.85
		for lot in s.lots.values():
			if lot.get("status", "") != "open":
				continue
			var guide := float(Valuation.guide_price(str(lot.get("model_id", "")), "B")) * float(lot.get("count", 1))
			var r := float(lot.get("total_price", 1e9)) / maxf(guide, 1.0)
			if r < best_r and float(lot.get("total_price", 0)) < s.cash * 0.45:
				best_r = r
				best = lot
		if not best.is_empty():
			steps.append(["cmd", "buy_lot", {"lot": best.id}])
	steps.append(["wait", 20.0])
	return steps


func _plan_sell() -> Array:
	var spot := _office_spot()
	var steps: Array = []
	if spot != Vector3.INF:
		steps.append(["walk", spot])
	steps.append(["wait", 30.0])
	return steps
