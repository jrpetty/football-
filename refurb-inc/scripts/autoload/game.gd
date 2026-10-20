extends Node
## Game — owns the GameState, routes player commands to the host, and replicates state to clients.
##
##   UI / player code  ──Game.request("place", {...})──►  host Sim.execute()  ──mutates──►  GameState
##        ▲                                                                                   │ dirty set
##        └──────────── state.changed signals ◄── import on clients ◄── _rpc_apply(deltas) ◄──┘
##
## Solo play uses OfflineMultiplayerPeer, which makes this peer the host — the exact same code path as co-op.

signal state_reset                       # a new GameState object is live (new game, load, or joined a host)
signal toast(text: String, level: String)
signal day_summary(summary: Dictionary)
signal vehicle_event(kind: String, payload: Dictionary)
signal command_result(cmd: String, result: Dictionary)
signal report_ready(report: Dictionary)   # Insights app data (computed on the host)

const REPLICATION_HZ := 20.0

var state: GameState = GameState.new()
var sim: Sim = null                      # host only
var in_game := false
var _rep_accum := 0.0


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	Net.peers_changed.connect(_on_peers_changed)
	multiplayer.peer_connected.connect(_on_peer_connected)
	multiplayer.peer_disconnected.connect(_on_peer_disconnected)


# ================================================================= lifecycle

func new_game(seed_value: int = 0) -> void:
	_install_state(GameState.new())
	sim = Sim.new(state)
	sim.new_game(seed_value)
	state.player(Sim.player_key(Net.local_peer()))["name"] = Net.local_name
	in_game = true
	state_reset.emit()


func load_from_dict(d: Dictionary) -> void:
	_install_state(GameState.new())
	state.load_dict(d)
	sim = Sim.new(state)
	sim.migrate_save()
	in_game = true
	state_reset.emit()
	if not state.day_running:
		# Saves are made at end of day; resume into the next morning.
		sim.start_next_day()


func leave_game() -> void:
	in_game = false
	sim = null
	_install_state(GameState.new())
	Net.go_offline()


func _install_state(s: GameState) -> void:
	state = s
	state.notify.connect(_on_state_notify)
	state.day_ended.connect(_on_day_ended)
	state.vehicle.connect(_on_vehicle)


func is_host() -> bool:
	return Net.is_host()


func local_player_key() -> String:
	return Sim.player_key(Net.local_peer())


# ================================================================= commands

func request(cmd: String, args: Dictionary = {}) -> void:
	## Every gameplay action goes through here. Host executes immediately; clients forward to the host.
	if is_host():
		_execute(Net.local_peer(), cmd, args)
	else:
		_rpc_request.rpc_id(1, cmd, args)


func _execute(peer: int, cmd: String, args: Dictionary) -> Dictionary:
	if sim == null:
		return {"ok": false, "msg": "No game running."}
	var res := sim.execute(peer, cmd, args)
	if peer == Net.local_peer():
		command_result.emit(cmd, res)
	elif Net.is_online():
		_rpc_result.rpc_id(peer, cmd, res)
	return res


@rpc("any_peer", "call_remote", "reliable")
func _rpc_request(cmd: String, args: Dictionary) -> void:
	if not is_host():
		return
	_execute(multiplayer.get_remote_sender_id(), cmd, args)


@rpc("authority", "call_remote", "reliable")
func _rpc_result(cmd: String, res: Dictionary) -> void:
	command_result.emit(cmd, res)


# ================================================================= business insights

func request_report() -> void:
	## The full history lives on the host; it computes the report and sends it back. Arrives via report_ready.
	if is_host():
		if sim:
			report_ready.emit(BusinessStats.compute(state))
	else:
		_rpc_report_request.rpc_id(1)


@rpc("any_peer", "call_remote", "reliable")
func _rpc_report_request() -> void:
	if is_host() and sim:
		_rpc_report.rpc_id(multiplayer.get_remote_sender_id(), BusinessStats.compute(state))


@rpc("authority", "call_remote", "reliable")
func _rpc_report(report: Dictionary) -> void:
	report_ready.emit(report)


# ================================================================= simulation + replication

func _process(delta: float) -> void:
	if not in_game:
		return
	if is_host() and sim:
		sim.active_peers = Net.all_peers()
		sim.tick(delta)
		_rep_accum += delta
		if _rep_accum >= 1.0 / REPLICATION_HZ:
			_rep_accum = 0.0
			_flush_replication()


func _flush_replication() -> void:
	if state.dirty.is_empty():
		return
	if not Net.is_online() or multiplayer.get_peers().is_empty():
		state.dirty.clear()
		return
	var changes: Array = []
	for key in state.dirty:
		var kv: Array = state.dirty[key]
		changes.append([kv[0], kv[1], state.export_entity(kv[0], kv[1])])
	state.dirty.clear()
	_rpc_apply.rpc(changes)


@rpc("authority", "call_remote", "reliable")
func _rpc_apply(changes: Array) -> void:
	for c in changes:
		state.import_entity(c[0], c[1], c[2])


@rpc("authority", "call_remote", "reliable")
func _rpc_snapshot(snap: Dictionary) -> void:
	_install_state(GameState.new())
	state.load_dict(snap)
	sim = null
	in_game = true
	state_reset.emit()


func _on_peer_connected(id: int) -> void:
	if is_host() and in_game:
		_flush_replication()
		state.player(Sim.player_key(id))
		_rpc_snapshot.rpc_id(id, state.snapshot())


func _on_peer_disconnected(_id: int) -> void:
	pass   # The level drops their carried items via the "drop_all_held" command.


func _on_peers_changed() -> void:
	if is_host() and in_game:
		for peer in Net.all_peers():
			var key := Sim.player_key(peer)
			state.player(key)["name"] = Net.display_name(peer)
			state.mark("player", key)


# ================================================================= notifications

func _on_state_notify(text: String, level: String, peer: int) -> void:
	if not is_host():
		return
	if peer == 0 or peer == Net.local_peer():
		toast.emit(text, level)
	if Net.is_online():
		if peer == 0:
			_rpc_toast.rpc(text, level)
		elif peer != Net.local_peer():
			_rpc_toast.rpc_id(peer, text, level)


@rpc("authority", "call_remote", "reliable")
func _rpc_toast(text: String, level: String) -> void:
	toast.emit(text, level)


func _on_day_ended(summary: Dictionary) -> void:
	day_summary.emit(summary)
	if Net.is_online():
		_rpc_day_summary.rpc(summary)
	SaveManager.autosave()


@rpc("authority", "call_remote", "reliable")
func _rpc_day_summary(summary: Dictionary) -> void:
	day_summary.emit(summary)


func _on_vehicle(kind: String, payload: Dictionary) -> void:
	vehicle_event.emit(kind, payload)
	if Net.is_online():
		_rpc_vehicle.rpc(kind, payload)


@rpc("authority", "call_remote", "reliable")
func _rpc_vehicle(kind: String, payload: Dictionary) -> void:
	vehicle_event.emit(kind, payload)
