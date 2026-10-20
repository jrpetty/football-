extends Node
## Networking front-end. The rest of the game only asks "am I the host?" and "who is here?".
## Transports: OFFLINE (solo — still runs as host), ENET (LAN/dev testing), STEAM (GodotSteam, Milestone 2).
## All game state is host-authoritative; see Game.request() and Game._flush_replication().

signal peers_changed
signal joined_game          # client: connection established and ready to receive a snapshot
signal connection_failed(reason: String)
signal server_closed

enum Transport { OFFLINE, ENET, STEAM }

const DEFAULT_PORT := 24680
const MAX_PLAYERS := 4

var transport: Transport = Transport.OFFLINE
var player_names: Dictionary = {}   # peer id -> display name
var local_name: String = "Player"


func _ready() -> void:
	multiplayer.peer_connected.connect(_on_peer_connected)
	multiplayer.peer_disconnected.connect(_on_peer_disconnected)
	multiplayer.connected_to_server.connect(_on_connected)
	multiplayer.connection_failed.connect(func(): connection_failed.emit("Could not reach host."))
	multiplayer.server_disconnected.connect(_on_server_closed)
	local_name = _default_name()
	player_names[1] = local_name


func _default_name() -> String:
	var n := OS.get_environment("USERNAME")
	if n == "":
		n = OS.get_environment("USER")
	return n.capitalize() if n != "" else "Player"


func _peer_active() -> bool:
	var p := multiplayer.multiplayer_peer
	return p != null and (p is OfflineMultiplayerPeer or p.get_connection_status() == MultiplayerPeer.CONNECTION_CONNECTED)


func is_host() -> bool:
	if not _peer_active():
		return false
	return multiplayer.is_server()


func local_peer() -> int:
	if not _peer_active():
		return 1
	return multiplayer.get_unique_id()


func is_online() -> bool:
	return transport != Transport.OFFLINE


func all_peers() -> Array:
	var out: Array = [local_peer()]
	if is_online() and _peer_active():
		out.append_array(multiplayer.get_peers())
	out.sort()
	return out


func display_name(peer: int) -> String:
	return player_names.get(peer, "Player %d" % peer)


# ---------------------------------------------------------------- session control

func go_offline() -> void:
	if multiplayer.multiplayer_peer and not multiplayer.multiplayer_peer is OfflineMultiplayerPeer:
		multiplayer.multiplayer_peer.close()
	multiplayer.multiplayer_peer = OfflineMultiplayerPeer.new()
	transport = Transport.OFFLINE
	player_names = {1: local_name}
	peers_changed.emit()


func host_enet(port: int = DEFAULT_PORT) -> Error:
	var peer := ENetMultiplayerPeer.new()
	var err := peer.create_server(port, MAX_PLAYERS - 1)
	if err != OK:
		return err
	multiplayer.multiplayer_peer = peer
	transport = Transport.ENET
	player_names = {1: local_name}
	peers_changed.emit()
	return OK


func join_enet(address: String, port: int = DEFAULT_PORT) -> Error:
	var peer := ENetMultiplayerPeer.new()
	var err := peer.create_client(address, port)
	if err != OK:
		return err
	multiplayer.multiplayer_peer = peer
	transport = Transport.ENET
	return OK


func host_steam() -> Error:
	## Milestone 2: create a Steam lobby and a SteamMultiplayerPeer (GodotSteam). Everything else is unchanged.
	if not Engine.has_singleton("Steam"):
		connection_failed.emit("Steam is not available in this build.")
		return ERR_UNAVAILABLE
	return ERR_UNAVAILABLE


# ---------------------------------------------------------------- events

func _on_peer_connected(id: int) -> void:
	if is_host() and multiplayer.get_peers().size() > MAX_PLAYERS - 1:
		multiplayer.multiplayer_peer.disconnect_peer(id)
		return
	peers_changed.emit()


func _on_peer_disconnected(id: int) -> void:
	player_names.erase(id)
	peers_changed.emit()


func _on_connected() -> void:
	_rpc_hello.rpc_id(1, local_name)
	joined_game.emit()


func _on_server_closed() -> void:
	go_offline()
	server_closed.emit()


@rpc("any_peer", "call_remote", "reliable")
func _rpc_hello(name_str: String) -> void:
	if not is_host():
		return
	var id := multiplayer.get_remote_sender_id()
	player_names[id] = name_str.substr(0, 24)
	_rpc_names.rpc(player_names)
	peers_changed.emit()


@rpc("authority", "call_remote", "reliable")
func _rpc_names(names: Dictionary) -> void:
	player_names = names
	peers_changed.emit()
