extends Node
## Two-process co-op smoke test over ENet on localhost.
##   godot --headless --path . res://tools/net_test.tscn -- --role host &
##   godot --headless --path . res://tools/net_test.tscn -- --role client
## Each prints "NET <role> PASS|FAIL" and exits with 0/1.

const PORT := 24711
var role := "host"
var _fails: PackedStringArray = []
var _level: LevelRoot


func _check(c: bool, m: String) -> void:
	print("  [%s] %s %s" % [role, "✔" if c else "✖", m])
	if not c:
		_fails.append(m)


func _ready() -> void:
	var a := OS.get_cmdline_user_args()
	if a.find("--role") >= 0:
		role = a[a.find("--role") + 1]
	if role == "host":
		await _host()
	else:
		await _client()
	print("NET %s %s" % [role, "PASS" if _fails.is_empty() else "FAIL"])
	get_tree().quit(0 if _fails.is_empty() else 1)


func _wait(cond: Callable, secs: float) -> bool:
	var t := 0.0
	while not cond.call() and t < secs:
		await get_tree().process_frame
		t += get_process_delta_time()
	return cond.call()


func _host() -> void:
	Net.local_name = "Host"
	_check(Net.host_enet(PORT) == OK, "server listening")
	Game.new_game(55)
	_level = load("res://scenes/levels/garage/garage.tscn").instantiate()
	add_child(_level)
	_check(await _wait(func(): return multiplayer.get_peers().size() == 1, 20.0), "client connected")
	_check(await _wait(func(): return _level.players.size() == 2, 5.0), "two players in the world")
	var usb := Game.state.item_in_slot("bench_1:park_a")
	var client_id: int = multiplayer.get_peers()[0]
	_check(await _wait(func(): return usb.loc.get("t") == "held" and int(usb.loc.get("peer", 0)) == client_id, 15.0), "client's pick-up command executed on host")
	await get_tree().create_timer(1.0).timeout
	var lot: Dictionary = Game.state.lots.values().filter(func(l): return l.status == "open")[0]
	Game.request("buy_lot", {"lot": lot.id})
	_check(Game.state.cash < int(Content.econ("start_cash", 2500)), "host bought a lot")
	# Give the client time to observe, then finish.
	await _wait(func(): return multiplayer.get_peers().is_empty(), 15.0)


func _client() -> void:
	Net.local_name = "Client"
	await get_tree().create_timer(1.5).timeout
	_check(Net.join_enet("127.0.0.1", PORT) == OK, "client socket created")
	var got := {"v": false}
	Game.state_reset.connect(func(): got.v = true)
	_check(await _wait(func(): return got.v, 20.0), "received snapshot from host")
	_level = load("res://scenes/levels/garage/garage.tscn").instantiate()
	add_child(_level)
	_check(Game.state.items.size() >= 2, "replicated items present (%d)" % Game.state.items.size())
	_check(await _wait(func(): return _level.players.size() == 2, 5.0), "sees both players")
	_check(Game.sim == null, "client runs no simulation")
	var usb := Game.state.item_in_slot("bench_1:park_a")
	_check(usb != null, "sees the starting USB")
	Game.request("pick_up", {"item": usb.id})
	var me := multiplayer.get_unique_id()
	_check(await _wait(func():
		var it := Game.state.get_item(usb.id)
		return it != null and it.loc.get("t") == "held" and int(it.loc.get("peer", 0)) == me, 10.0), "own pick-up replicated back")
	var cash0 := Game.state.cash
	_check(await _wait(func(): return Game.state.cash < cash0, 15.0), "host's purchase replicated (cash %d → %d)" % [cash0, Game.state.cash])
	_check(await _wait(func(): return Game.state.schedule.any(func(e): return e.type == "lorry"), 5.0), "lorry schedule replicated")
	multiplayer.multiplayer_peer.close()
