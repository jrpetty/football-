extends TestCase


func test_roundtrip_preserves_state() -> void:
	var sim := fresh_sim(77)
	var s := sim.state
	var lot: Dictionary = s.lots.values()[0]
	sim.execute(1, "buy_lot", {"lot": lot.id})
	sim.end_day()
	sim.start_next_day()
	sim.advance_minutes(120)
	var d := s.to_dict()
	var json := JSON.stringify(d)
	var back := GameState.new()
	back.load_dict(JSON.parse_string(json))
	eq(back.cash, s.cash)
	eq(back.items.size(), s.items.size())
	eq(back.slots.size(), s.slots.size())
	near(back.minute, s.minute, 0.01)
	eq(back.lots.size(), s.lots.size())
	for id in s.items:
		var a: Item = s.items[id]
		var b: Item = back.items[id]
		var na := JSON.stringify(JSON.parse_string(JSON.stringify(a.to_dict())))
		check(na == JSON.stringify(JSON.parse_string(JSON.stringify(b.to_dict()))), "item %d differs after save/load" % id)


func test_replication_import_matches_host() -> void:
	var sim := fresh_sim(31)
	var host := sim.state
	var client := GameState.new()
	client.load_dict(host.snapshot())
	host.dirty.clear()
	var lot: Dictionary = host.lots.values()[0]
	sim.execute(1, "buy_lot", {"lot": lot.id})
	sim.deliver_lot(lot.id)
	for key in host.dirty:
		var kv: Array = host.dirty[key]
		client.import_entity(kv[0], kv[1], host.export_entity(kv[0], kv[1]))
	host.dirty.clear()
	eq(client.cash, host.cash)
	eq(client.items.size(), host.items.size())
	eq(client.slots.size(), host.slots.size())
	check(not client.lots[lot.id].has("units"), "hidden data must not reach clients")
