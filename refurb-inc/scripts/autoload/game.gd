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
signal achievement_unlocked(def: Dictionary)   # this PC unlocked an achievement (HUD toast; Steam later)

const REPLICATION_HZ := 20.0

## This PC's achievements (data/achievements.json → user://achievements.json). Evaluated on every machine,
## host and guests, from the replicated state; only live during real play (see achievements_active()).
var achievements := Achievements.new()

var state: GameState = GameState.new()
var sim: Sim = null                      # host only
var in_game := false
var records_enabled := false            # real play (started from the menu) posts milestone times to local records
var looks := {}                          # peer id -> {"look": Dictionary, "face": PackedByteArray} (character menu)
signal looks_changed(peer: int)
var _rep_accum := 0.0


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	Net.peers_changed.connect(_on_peers_changed)
	multiplayer.peer_connected.connect(_on_peer_connected)
	multiplayer.peer_disconnected.connect(_on_peer_disconnected)
	achievements.unlocked.connect(func(def: Dictionary): achievement_unlocked.emit(def))
	day_summary.connect(_on_day_summary_achievements)     # fires on the host and on every guest


func _exit_tree() -> void:
	achievements.flush()


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
	achievements.active = false
	achievements.flush()
	sim = null
	_install_state(GameState.new())
	Net.go_offline()


func _install_state(s: GameState) -> void:
	state = s
	state.notify.connect(_on_state_notify)
	state.goal_reached.connect(_on_goal_reached)
	state.day_ended.connect(_on_day_ended)
	state.vehicle.connect(_on_vehicle)
	achievements.watch(state)


func _on_goal_reached(goal_id: String, day: int) -> void:
	if not records_enabled or not is_host():
		return
	var run_id := str(state.totals.get("run_id", ""))
	var previous := Campaign.best(goal_id)
	var place := Campaign.submit(goal_id, day, state.company_name, run_id)
	if place == 1 and not previous.is_empty() and str(previous.get("run", "")) != run_id:
		achievement_event("beat_record")
	if place == 1:
		toast.emit("New record! Fastest ever to %s: %d days." % [Campaign.name_of(goal_id), day], "good")
	elif place > 1:
		toast.emit("#%d on your leaderboard for %s." % [place, Campaign.name_of(goal_id)], "info")


func is_host() -> bool:
	return Net.is_host()


func local_player_key() -> String:
	return Sim.player_key(Net.local_peer())


# ================================================================= achievements

func achievements_active() -> bool:
	## Only real play unlocks achievements: a run started from the menu, never headless tools or tests.
	return in_game and records_enabled and DisplayServer.get_name() != "headless"


func achievement_event(event_name: String, n: int = 1) -> void:
	## Count an event-style achievement step on this PC (e.g. "beat_record").
	if achievements_active():
		achievements.active = true
		achievements.record_event(event_name, n)


func achievement_context() -> Dictionary:
	## Things achievements can't read from GameState: who's here, and this player's character settings.
	var me := Net.local_peer()
	return {
		"peers": Net.all_peers().size(),
		"player_key": local_player_key(),
		"flags": {
			"big_head": bool(Settings.get_value("char_bighead")),
			"photo_face": not (looks.get(me, {}).get("face", PackedByteArray()) as PackedByteArray).is_empty(),
		},
	}


func _on_day_summary_achievements(summary: Dictionary) -> void:
	if achievements_active():
		achievements.active = true
		achievements.on_day_end(state, summary, achievement_context())


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

func request_report(live_only: bool = false) -> void:
	## The full history lives on the host; it computes the report and sends it back. Arrives via report_ready.
	## live_only = just the fast-changing sections (machines, power, staff, counters) — cheap, for live pages.
	if is_host():
		if sim:
			report_ready.emit(BusinessReport.compute_live(state) if live_only else BusinessStats.compute(state))
	else:
		_rpc_report_request.rpc_id(1, live_only)


@rpc("any_peer", "call_remote", "reliable")
func _rpc_report_request(live_only: bool = false) -> void:
	if is_host() and sim:
		_rpc_report.rpc_id(multiplayer.get_remote_sender_id(), BusinessReport.compute_live(state) if live_only else BusinessStats.compute(state))


@rpc("authority", "call_remote", "reliable")
func _rpc_report(report: Dictionary) -> void:
	report_ready.emit(report)


# ================================================================= simulation + replication

func _unhandled_key_input(event: InputEvent) -> void:
	# Debug builds only: F9 = +30 reputation and +£5,000 (try business buyers and builds without the grind).
	if OS.is_debug_build() and in_game and event is InputEventKey and event.pressed and not event.echo and event.keycode == KEY_F9:
		request("dev_boost")


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
	achievements.active = achievements_active()
	achievements.tick(delta, state, achievement_context)


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
	share_look()


func _on_peer_connected(id: int) -> void:
	if is_host() and in_game:
		_flush_replication()
		state.player(Sim.player_key(id))
		_rpc_snapshot.rpc_id(id, state.snapshot())
		# Everyone's look (and photo face) for the newcomer.
		set_local_look()
		for peer in looks:
			_rpc_look.rpc_id(id, peer, looks[peer].look, looks[peer].face)


# ================================================================= character looks (co-op)

func set_local_look() -> void:
	looks[Net.local_peer()] = {"look": Looks.local_look(), "face": Looks.face_png()}
	looks_changed.emit(Net.local_peer())


func share_look() -> void:
	## Send my look + photo face to everyone (via the host).
	set_local_look()
	if not Net.is_online():
		return
	var mine: Dictionary = looks[Net.local_peer()]
	if is_host():
		_rpc_look.rpc(Net.local_peer(), mine.look, mine.face)
	else:
		_rpc_submit_look.rpc_id(1, mine.look, mine.face)


@rpc("any_peer", "call_remote", "reliable")
func _rpc_submit_look(look: Dictionary, face: PackedByteArray) -> void:
	if not is_host():
		return
	var from := multiplayer.get_remote_sender_id()
	if face.size() > 200000:
		face = PackedByteArray()      # keep it sane: faces are ~30 KB
	looks[from] = {"look": look, "face": face}
	looks_changed.emit(from)
	_rpc_look.rpc(from, look, face)


@rpc("authority", "call_remote", "reliable")
func _rpc_look(peer: int, look: Dictionary, face: PackedByteArray) -> void:
	looks[peer] = {"look": look, "face": face}
	looks_changed.emit(peer)


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
