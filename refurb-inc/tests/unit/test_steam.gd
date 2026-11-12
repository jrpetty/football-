extends TestCase
## Steam co-op plumbing that can be checked without the Steam client (tests run headless and never start Steam;
## tools/steam_check.tscn does the live check). Lobby joins from invites, failure messages, LAN fallback, and the
## achievement / leaderboard names that have to match Steamworks.


func test_invite_launch_arguments() -> void:
	eq(SteamBridge.lobby_from_args(PackedStringArray(["--path", ".", "+connect_lobby", "109775241696597984"])), 109775241696597984, "Steam's +connect_lobby")
	eq(SteamBridge.lobby_from_args(PackedStringArray(["+connect_lobby"])), 0, "missing id")
	eq(SteamBridge.lobby_from_args(PackedStringArray([])), 0, "normal start")


func test_join_failures_read_well() -> void:
	for code in [2, 3, 4, 5, 6, 7, 8, 9, 42]:
		var t := SteamBridge._enter_response_text(code)
		check(t.length() > 10 and t.ends_with("."), "code %d: %s" % [code, t])
	check("full" in SteamBridge._enter_response_text(4), "a full game says so")


func test_without_steam_lan_and_solo_still_work() -> void:
	# Steam is never started headless, so hosting on Steam fails politely and the game stays usable offline.
	check(not Net.steam.available, "Steam not started in tests")
	var reason := [""]
	var cb := func(r): reason[0] = r
	Net.connection_failed.connect(cb, CONNECT_ONE_SHOT)
	eq(Net.host_steam(true), ERR_UNAVAILABLE, "can't host on Steam without it")
	check(reason[0] != "", "and says why: %s" % reason[0])
	if Net.connection_failed.is_connected(cb):
		Net.connection_failed.disconnect(cb)
	eq(Net.join_steam(123), ERR_UNAVAILABLE)
	check(not Net.invite_friends(), "no invite dialog")
	check(not Net.is_online() and Net.is_host(), "still a solo host")
	Net.steam.unlock_achievement("ACH_OPEN_FOR_BUSINESS")     # no-ops, no errors
	Net.steam.submit_score(Campaign.steam_board("lockup"), 12)


func test_steamworks_names_are_valid_and_unique() -> void:
	var re := RegEx.create_from_string("^[A-Z0-9_]{3,64}$")
	var seen := {}
	for d: Dictionary in Achievements.defs():
		var n := Achievements.steam_name(str(d.id))
		check(re.search(n) != null, "achievement API name %s" % n)
		check(not seen.has(n), "unique %s" % n)
		seen[n] = true
	for m: Dictionary in Campaign.milestones():
		var b := Campaign.steam_board(str(m.id))
		check(re.search(b) != null, "leaderboard name %s" % b)
		check(not seen.has(b), "unique %s" % b)
		seen[b] = true
