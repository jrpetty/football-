extends TestCase


func test_lot_prices_swing_around_value() -> void:
	var sim := fresh_sim(7)
	var seller: Dictionary = Content.sellers["northgate_bank"]
	var ratios: Array = []
	for i in 300:
		var lot := sim.lots.create_lot(seller)
		var nominal := 0.0
		for ud in lot.units:
			nominal += Valuation.a_grade_value(UnitData.from_dict(ud)) * 0.4 * 0.95
		nominal /= lot.count
		ratios.append(float(lot.unit_price) / nominal)
	ratios.sort()
	check(ratios[0] >= 0.58 and ratios.back() <= 1.32, "swing out of range %f..%f" % [ratios[0], ratios.back()])
	check(ratios[0] < 0.75 and ratios.back() > 1.15, "swing too narrow — need real good and bad deals")


func test_roughly_seven_or_eight_in_ten_work() -> void:
	var sim := fresh_sim(99)
	var working := 0
	var total := 0
	for s in Content.sellers.values():
		for i in 30:
			var lot := sim.lots.create_lot(s)
			for ud in lot.units:
				total += 1
				if (ud.faults as Array).is_empty():
					working += 1
	var pct := float(working) / total
	check(pct > 0.62 and pct < 0.85, "working fraction %.2f should be ~0.7-0.8" % pct)


func test_cheap_lots_are_worse_on_average() -> void:
	var sim := fresh_sim(3)
	var cheap_faults := 0.0
	var cheap_n := 0
	var dear_faults := 0.0
	var dear_n := 0
	for i in 400:
		var lot := sim.lots.create_lot(Content.sellers["loopwise"])
		var f := 0
		for ud in lot.units:
			f += (ud.faults as Array).size()
		var rate: float = float(f) / float(lot.count)
		if lot.quality < 0.35:
			cheap_faults += rate
			cheap_n += 1
		elif lot.quality > 0.65:
			dear_faults += rate
			dear_n += 1
	check(cheap_n > 10 and dear_n > 10)
	check(cheap_faults / cheap_n > dear_faults / dear_n * 1.5, "cheap lots should have clearly more faults")


func test_hidden_fields_not_replicated() -> void:
	var sim := fresh_sim(5)
	var lot := sim.lots.create_lot(Content.sellers["ashby_council"])
	var pub: Dictionary = sim.state.export_entity("lot", lot.id)
	check(not pub.has("units"), "units leaked")
	check(not pub.has("quality"), "quality leaked")
	var snap := sim.state.snapshot()
	check(not snap.lots[str(lot.id)].has("units"))


func test_board_has_lots_on_day_one() -> void:
	var sim := fresh_sim(11)
	var open := sim.state.lots.values().filter(func(l): return l.status == "open")
	check(open.size() >= 4, "expected a few lots on day one, got %d" % open.size())
