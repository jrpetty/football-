class_name SteamBridge
extends Node
## Everything Steam (GodotSteam 4.23, addons/godotsteam): start-up, lobbies, invites, the P2P MultiplayerPeer,
## friends' avatars, achievements and leaderboards. Net owns one of these and turns it on from the title screen.
##
## GodotSteam is reached only through Engine.get_singleton("Steam") / ClassDB, never the bare `Steam` identifier,
## so the game still parses and runs (LAN + solo) in a build without the plug-in, and in tests/tools.
##
## App id: 480 is Valve's public test app ("Spacewar") — lobbies, P2P and invites all work with it, so co-op can
## be built and tested before Refurb Inc. has its own Steamworks app. Swap APP_ID for the real id when it exists
## (and add the achievements/leaderboards in Steamworks; see docs/STEAM.md).

signal lobby_created(lobby_id: int)
signal lobby_joined(lobby_id: int, is_owner: bool)
signal lobby_failed(reason: String)
signal lobbies_found(lobbies: Array)          # [{id, name, host, members, max, day}]
signal invite_received(lobby_id: int, from_name: String)
signal member_left(steam_id: int)
signal avatar_ready(steam_id: int, image: Image)

const APP_ID := 480
const GAME_KEY := "refurb_inc"                 # lobby data tag, so lobby searches only find Refurb Inc. games
const LOBBY_VERSION := 1                       # bump when the network protocol changes

var steam: Object = null
var available := false
var status_text := "Steam isn't running."
var lobby_id := 0
var my_id := 0
var _boards := {}                              # leaderboard name -> handle (0 while being found)
var _pending_scores := {}                      # leaderboard name -> score waiting for its handle
var _board_queue: Array = []                   # names waiting for a find result (one request at a time)
var _descending := {}                          # boards where higher is better (daily-run scores)
var _avatars := {}                             # steam id -> Image


func start() -> bool:
	## Initialise Steam (once). False when the plug-in or the Steam client isn't there; LAN and solo still work.
	if available or steam != null:
		return available
	if not Engine.has_singleton("Steam"):
		status_text = "This build has no Steam support."
		return false
	steam = Engine.get_singleton("Steam")
	var r: Dictionary = steam.steamInitEx(APP_ID, true)       # embed_callbacks: GodotSteam pumps callbacks itself
	if int(r.get("status", -1)) != int(_const("STEAM_API_INIT_RESULT_OK", 0)):
		status_text = "Steam isn't running (%s)." % str(r.get("verbal", "init failed"))
		return false
	available = true
	status_text = ""
	my_id = int(steam.getSteamID())
	steam.lobby_created.connect(_on_lobby_created)
	steam.lobby_joined.connect(_on_lobby_joined)
	steam.lobby_match_list.connect(_on_lobby_match_list)
	steam.join_requested.connect(_on_join_requested)
	steam.lobby_invite.connect(_on_lobby_invite)
	steam.lobby_chat_update.connect(_on_lobby_chat_update)
	steam.avatar_loaded.connect(_on_avatar_loaded)
	steam.leaderboard_find_result.connect(_on_leaderboard_found)
	return true


func persona_name() -> String:
	return str(steam.getPersonaName()) if available else ""


func friend_name(steam_id: int) -> String:
	return str(steam.getFriendPersonaName(steam_id)) if available else ""


func _const(name: String, fallback: int) -> int:
	return ClassDB.class_get_integer_constant("Steam", name) if ClassDB.class_has_integer_constant("Steam", name) else fallback


# ================================================================= lobbies

func create_lobby(friends_only: bool, max_members: int) -> void:
	if not available:
		lobby_failed.emit(status_text)
		return
	leave_lobby()
	steam.createLobby(_const("LOBBY_TYPE_FRIENDS_ONLY", 1) if friends_only else _const("LOBBY_TYPE_PUBLIC", 2), max_members)


func join_lobby(id: int) -> void:
	if not available:
		lobby_failed.emit(status_text)
		return
	if lobby_id != 0 and lobby_id != id:
		leave_lobby()
	steam.joinLobby(id)


func leave_lobby() -> void:
	if available and lobby_id != 0:
		steam.leaveLobby(lobby_id)
		steam.clearRichPresence()
	lobby_id = 0


func set_lobby_info(company: String, day: int) -> void:
	## Shown in other players' lobby lists and Steam's friends list.
	if not available or lobby_id == 0:
		return
	steam.setLobbyData(lobby_id, "name", company)
	steam.setLobbyData(lobby_id, "day", str(day))
	steam.setRichPresence("status", "Running %s — day %d" % [company, day])


func set_joinable(joinable: bool) -> void:
	if available and lobby_id != 0:
		steam.setLobbyJoinable(lobby_id, joinable)


func invite_friends() -> bool:
	## Steam overlay's invite dialog for this lobby (needs the overlay: Shift+Tab must work in the game).
	if not available or lobby_id == 0:
		return false
	steam.activateGameOverlayInviteDialog(lobby_id)
	return true


func find_lobbies() -> void:
	## Public Refurb Inc. lobbies anywhere (friends-only ones are joined through Steam invites / "Join game").
	if not available:
		lobbies_found.emit([])
		return
	steam.addRequestLobbyListStringFilter("game", GAME_KEY, 0)     # 0 = equal
	steam.addRequestLobbyListDistanceFilter(_const("LOBBY_DISTANCE_FILTER_WORLDWIDE", 3))
	steam.requestLobbyList()


func lobby_members() -> Array:
	var out: Array = []
	if not available or lobby_id == 0:
		return out
	for i in int(steam.getNumLobbyMembers(lobby_id)):
		out.append(int(steam.getLobbyMemberByIndex(lobby_id, i)))
	return out


func is_lobby_owner() -> bool:
	return available and lobby_id != 0 and int(steam.getLobbyOwner(lobby_id)) == my_id


static func lobby_from_args(args: PackedStringArray) -> int:
	## Steam starts the game with "+connect_lobby <id>" when a friend accepts an invite before it's running.
	var i := args.find("+connect_lobby")
	return int(args[i + 1]) if i >= 0 and i + 1 < args.size() else 0


func launch_lobby() -> int:
	var id := lobby_from_args(OS.get_cmdline_args())
	if id == 0 and available:
		var line := str(steam.getLaunchCommandLine())
		id = lobby_from_args(PackedStringArray(line.split(" ", false)))
	return id


func _on_lobby_created(result: int, id: int) -> void:
	if result != _const("RESULT_OK", 1) or id == 0:
		lobby_failed.emit("Steam couldn't create a lobby (error %d)." % result)
		return
	lobby_id = id
	steam.setLobbyData(id, "game", GAME_KEY)
	steam.setLobbyData(id, "version", str(LOBBY_VERSION))
	steam.setLobbyData(id, "host", persona_name())
	steam.setRichPresence("steam_player_group", str(id))
	steam.setRichPresence("steam_player_group_size", "1")
	lobby_created.emit(id)


func _on_lobby_joined(id: int, _permissions: int, _locked: bool, response: int) -> void:
	if response != _const("CHAT_ROOM_ENTER_RESPONSE_SUCCESS", 1):
		lobby_failed.emit(_enter_response_text(response))
		return
	if str(steam.getLobbyData(id, "game")) != GAME_KEY:
		steam.leaveLobby(id)
		lobby_failed.emit("That isn't a Refurb Inc. game.")
		return
	if int(steam.getLobbyData(id, "version")) != LOBBY_VERSION:
		steam.leaveLobby(id)
		lobby_failed.emit("That game is on a different version of Refurb Inc. — update both copies.")
		return
	lobby_id = id
	steam.setRichPresence("steam_player_group", str(id))
	lobby_joined.emit(id, int(steam.getLobbyOwner(id)) == my_id)


static func _enter_response_text(response: int) -> String:
	match response:
		2: return "That game no longer exists."
		3: return "That game isn't open to you."
		4: return "That game is full."
		5: return "Steam had a problem joining. Try again."
		6: return "You're banned from that game."
		7: return "You need a Steam account in good standing to join."
		8: return "That game is friends only."
		9: return "That game is locked."
	return "Couldn't join that game (Steam code %d)." % response


func _on_lobby_match_list(lobbies: Array) -> void:
	var out: Array = []
	for id in lobbies:
		var lid := int(id)
		if int(steam.getLobbyData(lid, "version")) != LOBBY_VERSION:
			continue
		out.append({"id": lid, "name": str(steam.getLobbyData(lid, "name")), "host": str(steam.getLobbyData(lid, "host")),
			"day": int(steam.getLobbyData(lid, "day")), "members": int(steam.getNumLobbyMembers(lid)), "max": 4})
	lobbies_found.emit(out)


func _on_join_requested(id: int, friend_id: int) -> void:
	## Accepted an invite / pressed "Join game" in Steam while the game is running.
	invite_received.emit(int(id), friend_name(friend_id))


func _on_lobby_invite(inviter: int, id: int, _game: int) -> void:
	## Raw in-game invite notification (Steam also shows its own popup); surfaced the same way.
	invite_received.emit(int(id), friend_name(inviter))


func _on_lobby_chat_update(id: int, changed_id: int, _making_change_id: int, chat_state: int) -> void:
	if id != lobby_id:
		return
	# Left, disconnected, kicked (8) or banned (16).
	if chat_state & (_const("CHAT_MEMBER_STATE_CHANGE_LEFT", 2) | _const("CHAT_MEMBER_STATE_CHANGE_DISCONNECTED", 4) | 8 | 16):
		member_left.emit(changed_id)
	steam.setRichPresence("steam_player_group_size", str(lobby_members().size()))


# ================================================================= P2P

func make_host_peer() -> MultiplayerPeer:
	var p: MultiplayerPeer = ClassDB.instantiate("SteamMultiplayerPeer")
	if p == null or int(p.call("host_with_lobby", lobby_id)) != OK:
		return null
	return p


func make_client_peer() -> MultiplayerPeer:
	var p: MultiplayerPeer = ClassDB.instantiate("SteamMultiplayerPeer")
	if p == null or int(p.call("connect_to_lobby", lobby_id)) != OK:
		return null
	return p


func steam_id_of(peer: MultiplayerPeer, peer_id: int) -> int:
	if peer == null or not peer.has_method("get_steam_id_for_peer_id"):
		return 0
	return int(peer.call("get_steam_id_for_peer_id", peer_id))


# ================================================================= avatars

func request_avatar(steam_id: int) -> void:
	## Medium (64×64) avatar → avatar_ready. Cached; used as the in-game face when a player has no photo.
	if not available or steam_id == 0:
		return
	if _avatars.has(steam_id):
		avatar_ready.emit(steam_id, _avatars[steam_id])
		return
	steam.getPlayerAvatar(_const("AVATAR_MEDIUM", 2), steam_id)


func _on_avatar_loaded(steam_id: int, size: int, data: PackedByteArray) -> void:
	if size <= 0 or data.size() < size * size * 4:
		return
	var img := Image.create_from_data(size, size, false, Image.FORMAT_RGBA8, data)
	_avatars[int(steam_id)] = img
	avatar_ready.emit(int(steam_id), img)


# ================================================================= achievements & leaderboards

func unlock_achievement(api_name: String) -> void:
	if not available:
		return
	if steam.setAchievement(api_name):
		steam.storeStats()


func submit_score(board: String, score: int, ascending: bool = true) -> void:
	## Fewer days is better (ascending); daily-run scores are higher-is-better (ascending = false). Keeps the
	## player's best. Boards are created on first use.
	if not available:
		return
	var handle := int(_boards.get(board, 0))
	if handle != 0:
		steam.uploadLeaderboardScore(score, true, PackedInt32Array(), handle)
		return
	var prev := int(_pending_scores.get(board, score))
	_pending_scores[board] = mini(prev, score) if ascending else maxi(prev, score)
	if not ascending:
		_descending[board] = true
	if not board in _board_queue:
		_board_queue.append(board)
		if _board_queue.size() == 1:
			_find_next_board()


func _find_next_board() -> void:
	if _board_queue.is_empty():
		return
	var sort := _const("LEADERBOARD_SORT_METHOD_DESCENDING", 2) if _descending.has(_board_queue[0]) else _const("LEADERBOARD_SORT_METHOD_ASCENDING", 1)
	steam.findOrCreateLeaderboard(_board_queue[0], sort, _const("LEADERBOARD_DISPLAY_TYPE_NUMERIC", 1))


func _on_leaderboard_found(handle: int, found: int) -> void:
	if _board_queue.is_empty():
		return
	var board: String = _board_queue.pop_front()
	if found != 0 and handle != 0:
		_boards[board] = handle
		if _pending_scores.has(board):
			steam.uploadLeaderboardScore(int(_pending_scores[board]), true, PackedInt32Array(), handle)
			_pending_scores.erase(board)
	_find_next_board()
