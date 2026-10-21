extends Node
## Networking front-end. The rest of the game only asks "am I the host?" and "who is here?".
## Transports: OFFLINE (solo — still runs as host), ENET (LAN / direct IP), STEAM (lobbies + P2P via GodotSteam,
## see SteamBridge). All game state is host-authoritative; see Game.request() and Game._flush_replication().

signal peers_changed
signal joined_game          # client: connection established and ready to receive a snapshot
signal connection_failed(reason: String)
signal server_closed
signal hosting_ready        # Steam: lobby created and we're the host
signal invite_received(lobby_id: int, from_name: String)
signal avatar_changed(peer: int)

enum Transport { OFFLINE, ENET, STEAM }

const DEFAULT_PORT := 24680
const MAX_PLAYERS := 4
const NAME_MAX := 32           # Steam display names are up to 32 characters

var transport: Transport = Transport.OFFLINE
var player_names: Dictionary = {}   # peer id -> display name
var player_steam: Dictionary = {}   # peer id -> Steam id (Steam games only)
var local_name: String = "Player"
var steam := SteamBridge.new()
var pending_invite := {}            # {lobby_id, from} — an invite that arrived mid-game
var _steam_role := ""               # "host" | "join" while a Steam lobby is being set up
var _avatar_png := {}               # peer id -> PNG bytes of their Steam avatar


func _ready() -> void:
	multiplayer.peer_connected.connect(_on_peer_connected)
	multiplayer.peer_disconnected.connect(_on_peer_disconnected)
	multiplayer.connected_to_server.connect(_on_connected)
	multiplayer.connection_failed.connect(func(): connection_failed.emit("Could not reach host."))
	multiplayer.server_disconnected.connect(_on_server_closed)
	add_child(steam)
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


func is_steam() -> bool:
	return transport == Transport.STEAM


func all_peers() -> Array:
	var out: Array = [local_peer()]
	if is_online() and _peer_active():
		out.append_array(multiplayer.get_peers())
	out.sort()
	return out


func display_name(peer: int) -> String:
	return player_names.get(peer, "Player %d" % peer)


# ---------------------------------------------------------------- Steam

func start_steam() -> bool:
	## Called once by the title screen. Uses the Steam name for this player when Steam is running.
	if steam.available:
		return true
	if not steam.start():
		return false
	var n := steam.persona_name()
	if n != "" and str(Settings.values.get("player_name", "")) == "":
		local_name = n.substr(0, NAME_MAX)
		player_names[local_peer()] = local_name
	player_steam[local_peer()] = steam.my_id
	steam.lobby_created.connect(_on_steam_lobby_created)
	steam.lobby_joined.connect(_on_steam_lobby_joined)
	steam.lobby_failed.connect(func(reason: String):
		_steam_role = ""
		connection_failed.emit(reason))
	steam.invite_received.connect(func(id: int, from: String):
		pending_invite = {"lobby_id": id, "from": from}
		invite_received.emit(id, from))
	steam.avatar_ready.connect(_on_steam_avatar)
	steam.request_avatar(steam.my_id)
	return true


func host_steam(friends_only: bool = true) -> Error:
	## Opens a Steam lobby; hosting_ready (or connection_failed) follows once Steam has made it.
	if not steam.available:
		connection_failed.emit(steam.status_text)
		return ERR_UNAVAILABLE
	go_offline()
	_steam_role = "host"
	steam.create_lobby(friends_only, MAX_PLAYERS)
	return OK


func join_steam(lobby_id: int) -> Error:
	## Joins a friend's lobby; joined_game (or connection_failed) follows.
	if not steam.available:
		connection_failed.emit(steam.status_text)
		return ERR_UNAVAILABLE
	go_offline()
	pending_invite = {}
	_steam_role = "join"
	steam.join_lobby(lobby_id)
	return OK


func _on_steam_lobby_created(_id: int) -> void:
	if _steam_role != "host":
		return
	var peer := steam.make_host_peer()
	if peer == null:
		_steam_role = ""
		steam.leave_lobby()
		connection_failed.emit("Steam couldn't start the game's network connection.")
		return
	multiplayer.multiplayer_peer = peer
	transport = Transport.STEAM
	_steam_role = ""
	player_names = {1: local_name}
	player_steam = {1: steam.my_id}
	peers_changed.emit()
	hosting_ready.emit()


func _on_steam_lobby_joined(_id: int, is_owner: bool) -> void:
	if _steam_role != "join" or is_owner:
		return
	var peer := steam.make_client_peer()
	if peer == null:
		_steam_role = ""
		steam.leave_lobby()
		connection_failed.emit("Couldn't connect to the host over Steam.")
		return
	multiplayer.multiplayer_peer = peer
	transport = Transport.STEAM
	_steam_role = ""
	# connected_to_server → _on_connected → hello → the host sends a snapshot.


func invite_friends() -> bool:
	return steam.invite_friends()


func avatar_png(peer: int) -> PackedByteArray:
	return _avatar_png.get(peer, PackedByteArray())


func _on_steam_avatar(steam_id: int, image: Image) -> void:
	for peer in player_steam:
		if int(player_steam[peer]) == steam_id:
			_avatar_png[peer] = Looks.mask_face(image).save_png_to_buffer()
			avatar_changed.emit(peer)


# ---------------------------------------------------------------- session control

func go_offline() -> void:
	if multiplayer.multiplayer_peer and not multiplayer.multiplayer_peer is OfflineMultiplayerPeer:
		multiplayer.multiplayer_peer.close()
	multiplayer.multiplayer_peer = OfflineMultiplayerPeer.new()
	if transport == Transport.STEAM:
		steam.leave_lobby()
	transport = Transport.OFFLINE
	player_names = {1: local_name}
	player_steam = {1: steam.my_id} if steam.available else {}
	_avatar_png = {}
	_fetch_avatars()
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


# ---------------------------------------------------------------- events

func _on_peer_connected(id: int) -> void:
	if is_host() and multiplayer.get_peers().size() > MAX_PLAYERS - 1:
		multiplayer.multiplayer_peer.disconnect_peer(id)
		return
	if is_host() and is_steam():
		steam.set_joinable(multiplayer.get_peers().size() < MAX_PLAYERS - 1)
	peers_changed.emit()


func _on_peer_disconnected(id: int) -> void:
	player_names.erase(id)
	player_steam.erase(id)
	_avatar_png.erase(id)
	if is_host() and is_steam():
		steam.set_joinable(true)
	peers_changed.emit()


func _on_connected() -> void:
	_rpc_hello.rpc_id(1, local_name, steam.my_id if steam.available else 0)
	joined_game.emit()


func _on_server_closed() -> void:
	go_offline()
	server_closed.emit()


@rpc("any_peer", "call_remote", "reliable")
func _rpc_hello(name_str: String, steam_id: int = 0) -> void:
	if not is_host():
		return
	var id := multiplayer.get_remote_sender_id()
	player_names[id] = name_str.substr(0, NAME_MAX)
	if steam_id != 0:
		player_steam[id] = steam_id
	_rpc_names.rpc(player_names, player_steam)
	peers_changed.emit()
	_fetch_avatars()


@rpc("authority", "call_remote", "reliable")
func _rpc_names(names: Dictionary, steam_ids: Dictionary = {}) -> void:
	player_names = names
	player_steam = steam_ids
	_avatar_png = {}          # peer ids changed meaning; re-map (the bridge caches the images)
	peers_changed.emit()
	_fetch_avatars()


func _fetch_avatars() -> void:
	if not steam.available:
		return
	for peer in player_steam:
		if not _avatar_png.has(peer):
			steam.request_avatar(int(player_steam[peer]))
