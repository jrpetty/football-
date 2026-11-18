class_name Upgrades
extends RefCounted
## Upgrade to sell: at the repair bench (or the build bench) fit more memory into a free slot, swap a stick for a
## bigger one, or swap the SSD/drive for a bigger one — using parts from the bins. The model's slots, bays and
## maximum memory are respected; soldered memory and storage can't be touched. Every option says what the part
## costs against what it adds to the unit's value, so the player decides (no haggling, no mini-game: hold E).
## A pulled stick or drive goes back into the bins when PartsHub sells that size. A swapped drive gets the PaneOS
## image cloned onto it as part of the job, so a graded, listed unit stays graded and listed at its new price.
## Shared by host (Sim.c_upgrade re-validates) and clients (hover prompt); it only reads state.


static func blocked_reason(u: UnitData) -> String:
	## Why this unit can't be upgraded right now ("" = it can, if there are options).
	if u == null:
		return "No unit on the bench."
	if not u.tested:
		return "Test it first so you know what you're upgrading."
	if u.has_open_faults():
		return "Fix the faults first."
	if u.order_id != 0:
		return "Already sold — ship it as described."
	if u.contract != 0:
		return "Reserved for a business order."
	if u.grade == "S":
		return "Spares/repairs units aren't worth upgrading."
	return ""


static func options(st: GameState, peer: int, u: UnitData) -> Array:
	## [{key, kind, text, part, part_name, cost, back, gain, net, secs, layout}] — best value first.
	var out: Array = []
	if u == null or blocked_reason(u) != "":
		return out
	var m := u.model()
	var l := u.layout()
	if UnitData.ram_upgradable(m):
		var dimms: Array = l.dimms
		var cap := u.ram_max()
		var rt := str(m.get("ram_type", ""))
		for pid in ServerBuild.parts_in_stock(st, "ram", "fits_ram_type", rt):
			var size := int(Content.part(pid).get("size_gb", 0))
			if dimms.size() < u.ram_slots():
				if u.ram_gb + size <= cap:
					var nl := l.duplicate(true)
					(nl.dimms as Array).append(size)
					out.append(_opt("ram_add:" + pid, "ram", pid, nl, "",
						"Fit %dGB stick in a free slot → %dGB RAM" % [size, u.ram_gb + size]))
			elif not dimms.is_empty():
				var smallest := int(dimms.min())
				if size > smallest and u.ram_gb - smallest + size <= cap:
					var nl := l.duplicate(true)
					(nl.dimms as Array).erase(smallest)
					(nl.dimms as Array).append(size)
					out.append(_opt("ram_swap:" + pid, "ram", pid, nl, ServerBuild.part_for_size("ram", "fits_ram_type", rt, smallest),
						"Swap the %dGB stick for %dGB → %dGB RAM" % [smallest, size, u.ram_gb - smallest + size]))
	if UnitData.storage_swappable(m):
		var drives: Array = l.drives
		var stype := str(m.get("storage_type", ""))
		for pid in ServerBuild.parts_in_stock(st, "storage", "fits_storage_type", stype):
			var size := int(Content.part(pid).get("size_gb", 0))
			if not drives.is_empty() and drives.size() >= u.drive_bays():
				var smallest := int(drives.min())
				if size > smallest:
					var nl := l.duplicate(true)
					(nl.drives as Array).erase(smallest)
					(nl.drives as Array).append(size)
					var text := ("Swap the %s %s for %s" % [UnitData.storage_text(smallest), u.storage_label(), UnitData.storage_text(size)]) \
						if drives.size() == 1 else ("Swap a %s drive for %s" % [UnitData.storage_text(smallest), UnitData.storage_text(size)])
					out.append(_opt("drive_swap:" + pid, "storage", pid, nl, ServerBuild.part_for_size("storage", "fits_storage_type", stype, smallest), text))
			elif drives.size() < u.drive_bays() and not drives.is_empty():
				var nl := l.duplicate(true)
				(nl.drives as Array).append(size)
				out.append(_opt("drive_add:" + pid, "storage", pid, nl, "", "Fit a %s drive in an empty bay" % UnitData.storage_text(size)))
	var grade := u.grade if u.grade != "" else Valuation.predicted_grade(u)
	var before := Valuation.market_value(u, grade)
	for o: Dictionary in out:
		var after := UnitData.from_dict(u.to_dict())
		after.set_layout(o.layout)
		o.gain = Valuation.market_value(after, grade) - before
		o.cost = int(Content.part(o.part).get("price", 0))
		o.back = int(Content.part(o.pull).get("price", 0)) if o.pull != "" else 0
		o.net = int(o.gain) - int(o.cost) + int(o.back)
		o.part_name = str(Content.part(o.part).get("name", o.part))
		o.secs = ServerBuild.step_secs(st, peer, o.part)
	out.sort_custom(func(a, b): return int(a.net) > int(b.net))
	return out


static func _opt(key: String, kind: String, pid: String, layout: Dictionary, pull: String, text: String) -> Dictionary:
	return {"key": key, "kind": kind, "part": pid, "pull": pull, "layout": layout, "text": text}


static func hold(st: GameState, peer: int, u: UnitData, index: int) -> Dictionary:
	## The hold-E prompt for the chosen option (R cycles `index`), or {} when there's nothing to fit.
	var opts := options(st, peer, u)
	if opts.is_empty():
		return {}
	var i := posmod(index, opts.size())
	var o: Dictionary = opts[i]
	var sub := sub_text(o)
	if opts.size() > 1:
		sub += "\n[R] next upgrade (%d of %d)" % [i + 1, opts.size()]
	return {"label": "Upgrade: " + str(o.text), "secs": float(o.secs), "action": "upgrade", "sub": sub, "key": str(o.key)}


static func sub_text(o: Dictionary) -> String:
	## "Part £26 · adds £31 to its value" — the cost against the gain, shown before you commit.
	var s := "Part £%d" % int(o.cost)
	if int(o.back) > 0:
		s += " (old one back in the bins, £%d)" % int(o.back)
	s += " · value %s£%d" % ["+" if int(o.gain) >= 0 else "-", absi(int(o.gain))]
	s += "  →  %s" % ("worth it (+£%d)" % int(o.net) if int(o.net) > 0 else ("break-even" if int(o.net) == 0 else "costs more than it adds (-£%d)" % absi(int(o.net))))
	return s
