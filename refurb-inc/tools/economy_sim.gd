extends Node
## Runs the BusinessBot for N working days on a fresh company and prints the Insights headline numbers.
##   godot --headless --path . res://tools/economy_sim.tscn -- --days 20 --seed 3


func _ready() -> void:
	var args := OS.get_cmdline_user_args()
	var days := int(args[args.find("--days") + 1]) if args.has("--days") else 15
	var seed_value := int(args[args.find("--seed") + 1]) if args.has("--seed") else 3
	Game.new_game(seed_value)
	var bot := BusinessBot.new(Game.sim, seed_value)
	bot.run_days(days)
	var r := BusinessStats.compute(Game.state)
	var o: Dictionary = r.overview
	print("After %d working days (seed %d):" % [days, seed_value])
	print("  cash %s · net profit %s · net worth %s · reputation %.1f" % [_m(o.cash), _m(o.net), _m(o.net_worth), o.reputation])
	print("  bought %d · sold %d · in stock %d · sell-through %.0f%%" % [o.units_bought, o.units_sold, o.in_stock, o.sell_through_pct])
	print("  avg buy %s · avg sale %s · avg profit/unit %s · margin %.0f%%" % [_m(o.avg_buy), _m(o.avg_sale), _m(o.avg_profit), o.margin_pct])
	print("  pass rate %.0f%% · on time %.0f%% · avg days in stock %.1f · %.1f shipped/day" % [o.pass_pct, o.on_time_pct, r.sales.avg_days_in_stock, r.operations.throughput.shipped])
	print("  hours worked %.1f · profit per worker-hour %s" % [r.people.hours, _m(r.people.profit_per_hour)])
	# Sanity: a competent player should trade profitably and the report should reconcile with the ledger.
	var ok: bool = int(o.units_sold) > 0 and int(o.net_worth) > int(Content.econ("start_cash", 2500)) and int(r.overview.cash) == Game.state.cash
	print("ECONOMY SIM %s" % ("PASSED" if ok else "FAILED"))
	get_tree().quit(0 if ok else 1)


func _m(v: Variant) -> String:
	return GameState.format_money(int(round(float(v))))
