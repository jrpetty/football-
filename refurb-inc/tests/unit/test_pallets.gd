extends TestCase
## Pallet trucks (take, jack up, lower, let go; one truck per player; no hand-carrying while pushing),
## empty pallets adopting the grid of the first unit stacked on them, and bulky Shop deliveries.

const P := 1


func test_start_kit_has_a_pallet_truck() -> void:
	var sim := fresh_sim(41)
	var trucks := sim.state.items.values().filter(func(i): return i.kind == Item.KIND_PALLET_TRUCK)
	eq(trucks.size(), 1, "one second-hand pallet truck in the garage")
	eq(trucks[0].loc.t, "loose")


func test_truck_lifts_moves_and_lowers_a_pallet() -> void:
	var sim := fresh_sim(42)
	var s := sim.state
	var truck: Item = s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET_TRUCK)[0]
	var pallet := s.new_item(Item.KIND_PALLET)
	s.set_loc(pallet, {"t": "loose", "p": [0, 0, 2], "r": [0, 0, 0, 1]})
	var laptop := s.new_item(Item.KIND_UNIT)
	laptop.unit = sim.lots.roll_unit("kestrel_swift_14", 0.5)
	s.set_loc(laptop, {"t": "loose", "p": [3, 0, 3], "r": [0, 0, 0, 1]})
	check(sim.execute(P, "take_truck", {"item": truck.id}).ok, "take the truck")
	eq(truck.loc.t, "pushed")
	check(not sim.execute(P, "pick_up", {"item": laptop.id}).ok, "can't hand-carry while pushing")
	check(not sim.execute(2, "take_truck", {"item": truck.id}).ok, "someone else can't grab it")
	check(sim.execute(P, "truck_lift", {"pallet": pallet.id}).ok, "jack the pallet up")
	eq(pallet.loc.t, "on_truck")
	check(not sim.execute(P, "truck_lift", {"pallet": pallet.id}).ok, "only one pallet on the forks")
	check(sim.execute(P, "truck_lower", {"p": [5, 0, 5], "r": [0, 0, 0, 1]}).ok, "lower it somewhere else")
	eq(pallet.loc.t, "loose")
	eq(pallet.loc.p, [5, 0, 5])
	check(sim.execute(P, "truck_lift", {"pallet": pallet.id}).ok)
	check(sim.execute(P, "release_truck", {"p": [6, 0, 6], "pallet_p": [6, 0, 7.3]}).ok, "let go with a load")
	eq(truck.loc.t, "loose")
	eq(pallet.loc.p, [6, 0, 7.3], "pallet set down in front")
	check(sim.execute(P, "pick_up", {"item": laptop.id}).ok, "hands free again")


func test_empty_pallet_takes_the_first_units_grid() -> void:
	var sim := fresh_sim(43)
	var s := sim.state
	var pallet := s.new_item(Item.KIND_PALLET)
	s.set_loc(pallet, {"t": "loose", "p": [0, 0, 0], "r": [0, 0, 0, 1]})
	check(not pallet.data.has("cell"), "a new pallet has no layout")
	var srv := s.new_item(Item.KIND_UNIT)
	srv.unit = sim.lots.roll_unit("corvex_rampart_2200", 0.5)
	s.set_loc(srv, {"t": "loose", "p": [1, 0, 1], "r": [0, 0, 0, 1]})
	sim.execute(P, "pick_up", {"item": srv.id})
	check(sim.execute(P, "place", {"slot": "i%d:0" % pallet.id}).ok, "stack a server on the empty pallet")
	eq(pallet.data.get("grid"), [2, 1], "pallet now lays out for 2U servers")


func test_bulky_shop_items_arrive_loose() -> void:
	var sim := fresh_sim(44)
	var s := sim.state
	var before := s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET).size()
	var parcel = sim.deliver_parcel({"items": ["pallet", "pallet", "usb_test"], "parts": {}, "consumables": {}}, Vector3(0, 0, 3))
	eq(s.items.values().filter(func(i): return i.kind == Item.KIND_PALLET).size(), before + 2, "two loose pallets")
	check(parcel != null and parcel.data.items == ["usb_test"], "the USB still comes boxed")
