extends TestCase
## Your own website (brief §12): unlocked by reputation, bought once, a second stream of buyers on lower fees.

const P := 1


func _listed(sim: Sim, grade := "B") -> Item:
	var s := sim.state
	var it := s.new_item(Item.KIND_UNIT)
	it.unit = sim.lots.roll_unit("corvex_prospect_5420", 0.9)
	it.unit.tested = true
	it.unit.imaged = true
	it.unit.grade = grade
	it.unit.list_price = Valuation.market_value(it.unit, grade)
	it.unit.listed = true
	s.set_loc(it, {"t": "slot", "slot": "shelf_1:0"})
	return it


func test_website_needs_reputation_then_money() -> void:
	var sim := fresh_sim(51)
	var s := sim.state
	s.cash = 100000
	s.reputation = 10.0
	check(not sim.execute(P, "shop_buy", {"id": "website_build"}).ok, "locked below reputation 30")
	check(not s.has_licence("website"))
	s.reputation = 31.0
	var cash := s.cash
	check(sim.execute(P, "shop_buy", {"id": "website_build"}).ok, "can build it at reputation 30")
	check(s.has_licence("website"))
	eq(cash - s.cash, int(Content.shop_items.website_build.price), "one-off price paid")
	check(not sim.execute(P, "shop_buy", {"id": "website_build"}).ok, "only once")


func test_offer_mail_arrives_once_at_reputation_30() -> void:
	var sim := fresh_sim(52)
	var s := sim.state
	s.reputation = 30.0
	var before: int = s.totals.get("inbox", []).size()
	sim.website_hourly()
	sim.website_hourly()
	eq(s.totals.get("inbox", []).size(), before + 1, "one offer mail")


func test_no_website_orders_without_the_website() -> void:
	var sim := fresh_sim(53)
	var s := sim.state
	s.reputation = 60.0
	var it := _listed(sim)
	for h in 50:
		sim.website_hourly()
	eq(it.unit.order_id, 0, "nothing sells on a website you haven't got")
	eq(Sim.website_chance_per_hour(s, it.unit), 0.0)


func test_website_brings_orders_with_a_small_card_fee() -> void:
	var sim := fresh_sim(54)
	var s := sim.state
	s.reputation = 60.0
	s.totals["licences"] = {"website": true}
	var it := _listed(sim)
	check(Sim.website_chance_per_hour(s, it.unit) > 0.0, "website buyers exist")
	var spares := _listed(sim, "S")
	eq(Sim.website_chance_per_hour(s, spares.unit), 0.0, "spares stay on Flogit")
	var guard := 0
	while it.unit.order_id == 0 and guard < 400:
		sim.website_hourly()
		guard += 1
	check(it.unit.order_id != 0, "a website buyer ordered it")
	var order: Dictionary = s.orders[it.unit.order_id]
	eq(String(order.channel), "website")
	var pkg := s.new_item(Item.KIND_PACKAGE)
	pkg.data = {"order_id": order.id, "unit": it.id}
	s.set_loc(pkg, {"t": "loose", "p": [0, 0, 0], "r": [0, 0, 0, 1]})
	var cash := s.cash
	sim._ship_package(pkg)
	var fee := int(round(int(order.price) * float(Content.econ("website_fee_pct", 0.02))))
	eq(s.cash - cash, int(order.price) - fee - int(Content.econ("flogit_postage", 9)), "2% card fee, no Flogit fee")


func test_hosting_is_billed_weekly() -> void:
	var sim := fresh_sim(55)
	var s := sim.state
	s.totals["licences"] = {"website": true}
	var friday := int(Content.econ("rent_due_weekday", 4))
	while s.day_index() % 7 != friday:
		sim.end_day()
		sim.start_next_day()
	var got := {}
	s.day_ended.connect(func(sm): got.merge(sm))
	sim.end_day()
	var summary := got
	var b := s.building()
	eq(int(summary.rent_paid), int(b.get("rent_weekly", 150)) + int(b.get("utilities_weekly", 25)) + int(Content.econ("website_weekly", 15)),
		"rent + bills + website hosting")


func test_website_name_comes_from_the_company() -> void:
	var s := GameState.new()
	s.company_name = "Jon's Kit & Co."
	eq(Sim.website_name(s), "jonskitandco.co.uk")
