extends TestCase
## The business name: Refurb Inc. by default, chosen at the start or renamed on the office PC, saved, and sent to
## co-op guests.


func test_names_are_tidied() -> void:
	eq(Sim.clean_company_name(""), "Refurb Inc.", "blank → the default")
	eq(Sim.clean_company_name("   "), "Refurb Inc.")
	eq(Sim.clean_company_name("  Byte  Back   Ltd "), "Byte Back Ltd", "trimmed, single spaces")
	eq(Sim.clean_company_name("Fix\nIt\tCo"), "FixItCo", "no control characters")
	eq(Sim.clean_company_name("X".repeat(80)).length(), Sim.COMPANY_NAME_MAX, "capped length")
	eq(Sim.clean_company_name("Ünïcode & Sons"), "Ünïcode & Sons", "accents and symbols are fine")


func test_new_company_is_refurb_inc() -> void:
	eq(fresh_sim(1).state.company_name, "Refurb Inc.")


func test_rename_saves_and_reaches_guests() -> void:
	var sim := fresh_sim(2)
	var host := sim.state
	var guest := GameState.new()
	guest.load_dict(host.snapshot())
	host.dirty.clear()
	check(sim.execute(1, "rename_company", {"name": "  Second Life   IT "}).ok, "renamed")
	eq(host.company_name, "Second Life IT")
	check(host.stats.events.any(func(e): return "Second Life IT" in str(e.get("text", ""))), "on the company timeline")
	for key in host.dirty:
		var kv: Array = host.dirty[key]
		guest.import_entity(kv[0], kv[1], host.export_entity(kv[0], kv[1]))
	eq(guest.company_name, "Second Life IT", "guests see the new name")
	var back := GameState.new()
	back.load_dict(JSON.parse_string(JSON.stringify(host.to_dict())))
	eq(back.company_name, "Second Life IT", "kept in the save")
	check(sim.execute(1, "rename_company", {"name": ""}).ok)
	eq(host.company_name, "Refurb Inc.", "clearing it goes back to Refurb Inc.")
