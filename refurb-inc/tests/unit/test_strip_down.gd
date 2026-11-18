extends TestCase
## Strip-down station (brief §9): dead or uneconomic units → donor parts in the bins.

const P := 1


func _bench(sim: Sim) -> String:
	sim.state.register_station("strip_1", "strip")
	sim.state.register_slot("strip_1:unit", ["unit", "unit_large"], "strip_1")
	return "strip_1"


func _unit(sim: Sim, model := "corvex_prospect_5420") -> Item:
	var it := sim.state.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit(model, 1.0)
	it.unit.cosmetic = []
	it.unit.faults = []
	it.unit.battery_pct = 95
	it.unit.tested = true
	return it


func test_working_parts_go_to_the_bins_and_the_unit_is_gone() -> void:
	var sim := fresh_sim(61)
	var s := sim.state
	var b := _bench(sim)
	var it := _unit(sim)
	var parts := StripDown.donor_parts(it.unit)
	check(parts.size() >= 4, "a working laptop has plenty inside (%s)" % ", ".join(parts))
	s.set_loc(it, {"t": "slot", "slot": b + ":unit"})
	s.players[sim.player_key(P)] = {"name": "Tester", "xp": {"repair": 999999}}     # top level: near-perfect removal
	var before := 0
	for p in s.parts.values():
		before += int(p)
	check(sim.execute(P, "strip", {"station": b}).ok)
	check(not s.items.has(it.id) or s.items[it.id].loc.t == "gone", "the unit has been taken apart")
	var after := 0
	for p in s.parts.values():
		after += int(p)
	check(after > before, "donor parts in the bins (%d → %d)" % [before, after])


func test_broken_components_are_not_saved() -> void:
	var sim := fresh_sim(62)
	var it := _unit(sim)
	var all := StripDown.donor_parts(it.unit)
	var dead := Valuation.repair_part_for(it.unit, {"id": "storage_dead"})
	check(dead in all, "a good SSD would be kept")
	it.unit.faults = [{"id": "storage_dead", "detail": {}, "fixed": false}]
	check(not dead in StripDown.donor_parts(it.unit), "a dead SSD isn't")
	it.unit.battery_pct = 40
	check(not Content.model(it.unit.model_id).get("battery_part", "") in StripDown.donor_parts(it.unit), "nor a worn battery")


func test_untested_or_sold_units_cannot_be_stripped() -> void:
	var sim := fresh_sim(63)
	var s := sim.state
	var b := _bench(sim)
	var it := _unit(sim)
	it.unit.tested = false
	s.set_loc(it, {"t": "slot", "slot": b + ":unit"})
	check(not sim.execute(P, "strip", {"station": b}).ok, "test it first")
	it.unit.tested = true
	it.unit.order_id = 999
	check(not sim.execute(P, "strip", {"station": b}).ok, "never strip a sold unit")
	eq(String(s.items[it.id].loc.t), "slot", "still on the bench")


func test_servers_give_their_drives_and_memory() -> void:
	var sim := fresh_sim(64)
	var it := _unit(sim, "corvex_rampart_2200")
	var parts := StripDown.donor_parts(it.unit)
	var m := Content.model("corvex_rampart_2200")
	var ram := Valuation.repair_part_for(it.unit, {"id": "ram_faulty"})
	eq(parts.count(ram), clampi(int(m.get("ram_slots", 1)), 1, 4), "one DIMM per slot")


func test_skill_raises_the_keep_chance() -> void:
	check(StripDown.keep_chance(10) > StripDown.keep_chance(1))
	check(StripDown.keep_chance(10) <= 0.98)
