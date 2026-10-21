class_name ServerBuild
extends RefCounted
## Custom server builds at the build bench. The customer's spec (Business contract `spec`) is a checklist; every
## hold-E step fits (or swaps) one part from the company parts bins until the checklist is all green:
##   CPUs → memory → drives → RAID controller → power supplies.
## Wrong CPUs come out first (back into the bins); full memory slots or bays swap their smallest module for a bigger
## one (the old one goes back in the bins if PartsHub sells that part). Shared by the host (Sim.c_build, which
## re-checks everything) and the UI (bench screen, hover prompts, Business app) — it only reads state.

const ORDER := ["chassis", "cpus", "memory", "drives", "raid", "psus"]


static func checklist(u: UnitData, spec: Dictionary) -> Array:
	## [{key, label, have, need, ok}] — one row per line of the customer's spec.
	var rows: Array = []
	if u == null:
		return rows
	var l := u.layout()
	var m := u.model()
	var chassis_ok: bool = u.model_id in spec.get("chassis", [])
	rows.append({"key": "chassis", "label": "Chassis: " + Business.chassis_name(spec), "have": 1 if chassis_ok else 0, "need": 1, "ok": chassis_ok,
		"detail": u.display_name()})
	var need_cpu := int(spec.get("cpus", 1))
	var good_cpu := int(l.cpus) if u.cpu_id in spec.get("cpu_ids", []) else 0
	rows.append({"key": "cpus", "label": (("%d× %s" % [need_cpu, spec.get("cpu_label", "")]) if need_cpu > 1 else str(spec.get("cpu_label", ""))) + " CPU" + ("s" if need_cpu > 1 else ""),
		"have": good_cpu, "need": need_cpu, "ok": good_cpu >= need_cpu,
		"detail": ("%d× %s" % [int(l.cpus), u.cpu_name()]) if int(l.cpus) > 0 and u.cpu_id != "" else "empty sockets"})
	var need_ram := int(spec.get("ram_gb", 0))
	rows.append({"key": "memory", "label": Business.ram_label(spec) + " memory", "have": u.ram_gb, "need": need_ram, "ok": u.ram_gb >= need_ram,
		"detail": "%dGB in %d of %d slots" % [u.ram_gb, (l.dimms as Array).size(), u.ram_slots()]})
	var need_drives := int(spec.get("drives", 0))
	var good_drives := (l.drives as Array).filter(func(d): return int(d) >= int(spec.get("drive_gb", 0))).size()
	rows.append({"key": "drives", "label": Business.drive_label(spec), "have": good_drives, "need": need_drives, "ok": good_drives >= need_drives,
		"detail": (UnitData.drives_text(l.drives) if not (l.drives as Array).is_empty() else "no drives") + " in %d bays" % u.drive_bays()})
	if spec.get("raid", false):
		rows.append({"key": "raid", "label": "RAID controller", "have": 1 if l.raid else 0, "need": 1, "ok": bool(l.raid),
			"detail": "fitted" if l.raid else "none"})
	var need_psu := int(spec.get("psus", 1))
	rows.append({"key": "psus", "label": ("%d PSUs (redundant)" % need_psu) if need_psu > 1 else "1 PSU", "have": int(l.psus), "need": need_psu,
		"ok": int(l.psus) >= need_psu, "detail": "%d of %d bays" % [int(l.psus), int(m.get("psu_bays", 1))]})
	return rows


static func complete(u: UnitData, spec: Dictionary) -> bool:
	for r in checklist(u, spec):
		if not r.ok:
			return false
	return not spec.is_empty()


static func progress(u: UnitData, spec: Dictionary) -> Array:
	## [rows done, rows total]
	var rows := checklist(u, spec)
	return [rows.filter(func(r): return r.ok).size(), rows.size()]


static func chassis_plan(st: GameState) -> Dictionary:
	## Which barebones chassis an empty build bench would unbox: one for the earliest-due build still waiting for a
	## server, else any chassis in the bins. {part, model, contract (0 = none)} or {}.
	var waiting: Array = []
	for c: Dictionary in st.contracts.values():
		if c.get("kind", "") != "build" or c.get("status", "") != "active":
			continue
		var held: Item = st.get_item(int(c.get("unit", 0)))
		if held and held.unit and int(held.unit.contract) == int(c.id):
			continue
		waiting.append(c)
	waiting.sort_custom(func(a, b): return float(a.due) < float(b.due))
	for c: Dictionary in waiting:
		for mid in c.spec.get("chassis", []):
			var pid := Business.chassis_part(mid)
			if _stock(st, pid) > 0:
				return {"part": pid, "model": mid, "contract": int(c.id)}
	for pid in st.parts:
		if int(st.parts[pid]) > 0 and Content.part(pid).get("category") == "chassis":
			return {"part": pid, "model": str(Content.part(pid).get("model", "")), "contract": 0}
	return {}


static func _stock(st: GameState, pid: String) -> int:
	return int(st.parts.get(pid, 0)) if pid != "" else 0


static func parts_in_stock(st: GameState, category: String, fit_key: String, fit_val: String) -> Array:
	## Part ids in the bins that fit, smallest size first.
	var out: Array = []
	for pid in st.parts:
		var p := Content.part(pid)
		if int(st.parts[pid]) > 0 and p.get("category") == category and str(p.get(fit_key, "")) == fit_val:
			out.append(pid)
	out.sort_custom(func(a, b): return int(Content.part(a).get("size_gb", 0)) < int(Content.part(b).get("size_gb", 0)))
	return out


static func part_for_size(category: String, fit_key: String, fit_val: String, size: int) -> String:
	## The PartsHub part a pulled module counts as (so it can go back in the bins), or "".
	for p: Dictionary in Content.parts.values():
		if p.get("category") == category and str(p.get(fit_key, "")) == fit_val and int(p.get("size_gb", 0)) == size:
			return p.id
	return ""


static func step_secs(st: GameState, peer: int, pid: String, fallback: float = 3.0) -> float:
	var secs := float(Content.part(pid).get("repair_secs", fallback)) * float(Content.econ("build_secs_mult", 0.8))
	if peer != 0:
		secs /= st.skill_speed(Sim.player_key(peer), "repair")
	return maxf(secs, 0.6)


static func next_step(st: GameState, peer: int, u: UnitData, spec: Dictionary) -> Dictionary:
	## The next hold-E job towards the spec: {act, part, pull, text, secs} — or {done: true} / {blocked: why}.
	if u == null or spec.is_empty():
		return {"blocked": "No build on the bench."}
	if not u.model_id in spec.get("chassis", []):
		return {"blocked": "Wrong chassis — this build needs a %s." % Business.chassis_name(spec)}
	var m := u.model()
	var l := u.layout()
	# --- CPUs: wrong ones out first, then fill the sockets with one matching kind.
	var need_cpu := int(spec.get("cpus", 1))
	var cpu_ids: Array = spec.get("cpu_ids", [])
	if int(l.cpus) > 0 and not u.cpu_id in cpu_ids:
		return {"act": "cpu_out", "part": Business.cpu_part(u.cpu_id), "text": "Remove %d× %s" % [int(l.cpus), u.cpu_name()],
			"secs": step_secs(st, peer, Business.cpu_part(u.cpu_id), 3.0)}
	if int(l.cpus) < need_cpu:
		var want := u.cpu_id if int(l.cpus) > 0 and u.cpu_id != "" else ""
		if want == "":
			# The acceptable CPU we hold most of (ideally enough for every socket).
			var best := -1
			for cid in cpu_ids:
				var n := _stock(st, Business.cpu_part(cid))
				if n > best:
					best = n
					want = cid
		var pid := Business.cpu_part(want)
		if _stock(st, pid) <= 0:
			return {"blocked": "Need part: %s CPU (0 in bins) — PartsHub → Server build." % str(spec.get("cpu_label", "a matching"))}
		return {"act": "cpu_in", "part": pid, "text": "Fit CPU %d of %d: %s" % [int(l.cpus) + 1, need_cpu, Content.cpu(want).get("name", want)],
			"secs": step_secs(st, peer, pid)}
	# --- Memory: fill free slots with modules just big enough, else swap the smallest for a bigger one.
	var need_ram := int(spec.get("ram_gb", 0))
	if u.ram_gb < need_ram:
		var rt := str(m.get("ram_type", ""))
		var dimms: Array = l.dimms
		var stock := parts_in_stock(st, "ram", "fits_ram_type", rt)
		if stock.is_empty():
			return {"blocked": "Need memory: %s modules (none in bins)." % ("ECC RDIMM" if rt == "ddr4_rdimm" else "DIMM")}
		var free := u.ram_slots() - dimms.size()
		var short := need_ram - u.ram_gb
		if free > 0:
			var per := int(ceil(float(short) / free))
			var pick := ""
			for pid in stock:
				if int(Content.part(pid).get("size_gb", 0)) >= per:
					pick = pid
					break
			if pick == "":
				pick = stock.back()
			return {"act": "dimm_in", "part": pick, "text": "Fit %s (slot %d of %d)" % [Content.part(pick).get("name", pick), dimms.size() + 1, u.ram_slots()],
				"secs": step_secs(st, peer, pick)}
		var smallest := int(dimms.min())
		var bigger := stock.filter(func(pid): return int(Content.part(pid).get("size_gb", 0)) > smallest)
		if bigger.is_empty():
			return {"blocked": "Memory slots full — need modules bigger than %dGB in the bins." % smallest}
		var swap: String = bigger.back()
		return {"act": "dimm_swap", "part": swap, "pull": smallest,
			"text": "Swap a %dGB module for %s" % [smallest, Content.part(swap).get("name", swap)], "secs": step_secs(st, peer, swap)}
	# --- Drives: fill empty bays first, then swap undersized drives.
	var need_drives := int(spec.get("drives", 0))
	var dgb := int(spec.get("drive_gb", 0))
	var drives: Array = l.drives
	var good := drives.filter(func(d): return int(d) >= dgb).size()
	if good < need_drives:
		var stype := str(m.get("storage_type", ""))
		var fits := parts_in_stock(st, "storage", "fits_storage_type", stype).filter(func(pid): return int(Content.part(pid).get("size_gb", 0)) >= dgb)
		if fits.is_empty():
			return {"blocked": "Need drives: %s %s+ (none in bins)." % [Business.STORAGE_NAMES.get(stype, "drive"), UnitData.storage_text(dgb)]}
		var pid: String = fits[0]
		if drives.size() < u.drive_bays():
			return {"act": "drive_in", "part": pid, "text": "Fit %s (bay %d of %d)" % [Content.part(pid).get("name", pid), drives.size() + 1, u.drive_bays()],
				"secs": step_secs(st, peer, pid)}
		var small := drives.filter(func(d): return int(d) < dgb)
		if small.is_empty():
			return {"blocked": "No free drive bays."}
		var out_gb := int(small.min())
		return {"act": "drive_swap", "part": pid, "pull": out_gb,
			"text": "Swap a %s drive for %s" % [UnitData.storage_text(out_gb), Content.part(pid).get("name", pid)], "secs": step_secs(st, peer, pid)}
	# --- RAID controller.
	if spec.get("raid", false) and not l.raid:
		if _stock(st, "raid_card_12g") <= 0:
			return {"blocked": "Need part: RAID controller (0 in bins) — PartsHub → Server build."}
		return {"act": "raid_in", "part": "raid_card_12g", "text": "Fit the RAID controller", "secs": step_secs(st, peer, "raid_card_12g")}
	# --- Power supplies.
	if int(l.psus) < int(spec.get("psus", 1)):
		var pp := str(m.get("psu_part", ""))
		if _stock(st, pp) <= 0:
			return {"blocked": "Need part: %s (0 in bins)." % Content.part(pp).get("name", pp)}
		return {"act": "psu_in", "part": pp, "text": "Fit PSU %d of %d: %s" % [int(l.psus) + 1, int(spec.get("psus", 1)), Content.part(pp).get("name", pp)],
			"secs": step_secs(st, peer, pp)}
	return {"done": true}


static func apply_step(st: GameState, u: UnitData, step: Dictionary) -> Dictionary:
	## Does one step to the unit and the parts bins. Returns {spent: £ of new parts used, back: £ of parts returned}.
	var m := u.model()
	var l := u.layout()
	var spent := 0
	var back := 0
	var pid := str(step.get("part", ""))
	match str(step.get("act", "")):
		"cpu_out":
			if pid != "":
				st.parts[pid] = int(st.parts.get(pid, 0)) + int(l.cpus)
				back += int(Content.part(pid).get("price", 0)) * int(l.cpus)
			l.cpus = 0
			u.cpu_id = ""
		"cpu_in":
			u.cpu_id = str(Content.part(pid).get("cpu", ""))
			l.cpus = int(l.cpus) + 1
		"dimm_in":
			(l.dimms as Array).append(int(Content.part(pid).get("size_gb", 0)))
		"dimm_swap":
			var dimms: Array = l.dimms
			dimms.erase(int(step.pull))
			dimms.append(int(Content.part(pid).get("size_gb", 0)))
			back += _return_part(st, part_for_size("ram", "fits_ram_type", str(m.get("ram_type", "")), int(step.pull)))
		"drive_in":
			(l.drives as Array).append(int(Content.part(pid).get("size_gb", 0)))
		"drive_swap":
			var drives: Array = l.drives
			drives.erase(int(step.pull))
			drives.append(int(Content.part(pid).get("size_gb", 0)))
			back += _return_part(st, part_for_size("storage", "fits_storage_type", str(m.get("storage_type", "")), int(step.pull)))
		"raid_in":
			l.raid = true
		"psu_in":
			l.psus = int(l.psus) + 1
	if str(step.get("act", "")) != "cpu_out" and pid != "":
		st.parts[pid] = int(st.parts.get(pid, 0)) - 1
		if int(st.parts[pid]) <= 0:
			st.parts.erase(pid)
		spent += int(Content.part(pid).get("price", 0))
	(l.drives as Array).sort()
	(l.drives as Array).reverse()
	(l.dimms as Array).sort()
	(l.dimms as Array).reverse()
	u.set_layout(l)
	if u.cpu_id == "" and int(l.cpus) > 0:
		u.cpu_id = str(Content.part(pid).get("cpu", ""))
	return {"spent": spent, "back": back}


static func _return_part(st: GameState, pid: String) -> int:
	if pid == "":
		return 0
	st.parts[pid] = int(st.parts.get(pid, 0)) + 1
	return int(Content.part(pid).get("price", 0))
