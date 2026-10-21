extends Node
## Live Steam check on one PC (needs the Steam client running): start Steam, read the player's name and avatar,
## open a friends-only lobby, become its host over SteamMultiplayerPeer, set lobby data, then close it again.
## A second account on another PC is needed to test joining; see docs/STEAM.md.
##   Godot --headless --path . res://tools/steam_check.tscn

var _fails: Array = []


func _check(cond: bool, msg: String) -> void:
	print(("  ✔ " if cond else "  ✖ ") + msg)
	if not cond:
		_fails.append(msg)


func _ready() -> void:
	var ok := Net.start_steam()
	_check(ok, "Steam started (%s)" % ("app %d" % SteamBridge.APP_ID if ok else Net.steam.status_text))
	if not ok:
		_finish()
		return
	_check(Net.steam.persona_name() != "", "Steam name: %s" % Net.steam.persona_name())
	_check(Net.local_name != "", "player name in game: %s" % Net.local_name)
	var got_avatar := [false]
	Net.avatar_changed.connect(func(_p): got_avatar[0] = true)
	Net.steam.request_avatar(Net.steam.my_id)
	var ready := [false]
	var failed := [""]
	Net.hosting_ready.connect(func(): ready[0] = true, CONNECT_ONE_SHOT)
	Net.connection_failed.connect(func(r): failed[0] = r, CONNECT_ONE_SHOT)
	_check(Net.host_steam(true) == OK, "lobby requested")
	var t := 0.0
	while not ready[0] and failed[0] == "" and t < 20.0:
		await get_tree().process_frame
		t += get_process_delta_time()
	_check(ready[0], "hosting over Steam %s" % ("in %.1f s" % t if ready[0] else "failed: " + failed[0]))
	if ready[0]:
		_check(Net.is_steam() and Net.is_host(), "Net reports a Steam host")
		_check(multiplayer.multiplayer_peer.get_class() == "SteamMultiplayerPeer", "multiplayer peer is %s" % multiplayer.multiplayer_peer.get_class())
		_check(Net.steam.lobby_id != 0 and Net.steam.is_lobby_owner(), "we own lobby %d" % Net.steam.lobby_id)
		Net.steam.set_lobby_info("Refurb Inc. (test)", 1)
		_check(Net.steam.lobby_members().size() == 1, "one member (us)")
	for i in 30:
		await get_tree().process_frame
	_check(got_avatar[0], "Steam avatar loaded as a face picture (%d bytes)" % Net.avatar_png(Net.local_peer()).size())
	Net.go_offline()
	_check(Net.steam.lobby_id == 0 and not Net.is_online(), "lobby closed, back offline")
	_finish()


func _finish() -> void:
	print("\nSTEAM CHECK %s (%d failures)" % ["PASSED" if _fails.is_empty() else "FAILED", _fails.size()])
	get_tree().quit(0 if _fails.is_empty() else 1)
