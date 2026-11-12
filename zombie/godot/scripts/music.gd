extends Node
## The score and the world's sound bed. Two music layers — calm and threat — cross-fade with how hunted you
## are; ambience beds (wind, rain, night insects, room tone) mix from the weather, the hour and whether you
## are indoors; birds by day, owls and distant things by night.

var game
var calm: AudioStreamPlayer
var threat: AudioStreamPlayer
var beds: Dictionary = {}
var danger := 0.0
var _calm_gap := 20.0             # seconds of quiet before the calm theme comes in
var _calm_on := false
var _calm_vol := 0.0
var _spot_t := 6.0

static func ensure_buses() -> void:
	for b in ["Music", "SFX", "Ambience"]:
		if AudioServer.get_bus_index(b) < 0:
			AudioServer.add_bus()
			var i := AudioServer.bus_count - 1
			AudioServer.set_bus_name(i, b)
			AudioServer.set_bus_send(i, "Master")

func _player(path: String, bus: String) -> AudioStreamPlayer:
	var p := AudioStreamPlayer.new()
	p.bus = bus; p.volume_db = -80.0
	if ResourceLoader.exists(path):
		var st = load(path)
		if st is AudioStreamMP3: st.loop = true
		elif st is AudioStreamWAV:
			st.loop_mode = AudioStreamWAV.LOOP_FORWARD
			st.loop_end = int(st.get_length() * st.mix_rate)
		p.stream = st
	add_child(p)
	return p

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	ensure_buses()
	calm = _player("res://assets/music/calm.mp3", "Music")
	threat = _player("res://assets/music/threat.mp3", "Music")
	for b in ["wind", "rain", "night", "room"]:
		beds[b] = _player("res://assets/sfx/amb_%s.wav" % b, "Ambience")
		if beds[b].stream != null: beds[b].play()

static func _fade(p: AudioStreamPlayer, target: float, dt: float, rate := 1.2) -> void:
	var cur := db_to_linear(p.volume_db)
	cur = move_toward(cur, target, dt * rate)
	p.volume_db = linear_to_db(maxf(cur, 0.0001))

func _process(dt: float) -> void:
	var mv := float(Settings.v("music_vol")); var sv := float(Settings.v("sfx_vol"))
	var playing: bool = game != null and game.state == "play"
	var menu: bool = game != null and game.state == "menu"
	# danger: how many are on you, smoothed so the music doesn't flicker
	var want := 0.0
	if playing and game.zombies != null:
		var chasing = game.zombies.get("chasing")
		if chasing != null:
			want = clampf(float(chasing) / 4.0, 0.0, 1.0)
	danger = move_toward(danger, want, dt * (0.5 if want > danger else 0.12))
	# calm theme: comes and goes, never during a chase; it is the menu's music too
	if menu:
		_calm_on = true
	elif playing:
		_calm_gap -= dt
		if _calm_gap <= 0.0:
			_calm_on = not _calm_on
			_calm_gap = randf_range(70.0, 150.0) if _calm_on else randf_range(90.0, 240.0)
	else:
		_calm_on = false
	var calm_target := (0.75 if _calm_on else 0.0) * (1.0 - danger) * mv
	if calm_target > 0.001 and not calm.playing and calm.stream != null: calm.play()
	_fade(calm, calm_target, dt, 0.35)
	if calm.playing and db_to_linear(calm.volume_db) < 0.002 and calm_target <= 0.0: calm.stop()
	var threat_target := smoothstep(0.15, 0.8, danger) * 0.85 * mv
	if threat_target > 0.001 and not threat.playing and threat.stream != null: threat.play()
	_fade(threat, threat_target, dt, 0.6)
	if threat.playing and db_to_linear(threat.volume_db) < 0.002 and threat_target <= 0.0: threat.stop()
	# ambience
	var wind := 0.3; var rain := 0.0; var indoor := false; var night := 0.0
	if game != null:
		night = 1.0 - game.sky.daylight
		if game.modules.has("weather"):
			wind = game.modules.weather.wind; rain = game.modules.weather.rain
		indoor = playing and game.player.inside
	var out := 0.35 if indoor else 1.0
	var on := 1.0 if (playing or menu) else 0.0
	_fade(beds.wind, (0.12 + wind * 0.75) * out * sv * on, dt)
	_fade(beds.rain, rain * (0.55 if indoor else 0.9) * sv * on, dt)
	_fade(beds.night, night * (1.0 - rain) * 0.5 * out * sv * on, dt)
	_fade(beds.room, (0.5 if indoor else 0.0) * sv * on, dt)
	# now and then, something out there
	if playing:
		_spot_t -= dt
		if _spot_t <= 0.0:
			_spot_t = randf_range(9.0, 28.0)
			var a := randf() * TAU
			var pos: Vector3 = game.player.pos + Vector3(cos(a), 0.3, sin(a)) * randf_range(25.0, 60.0)
			var name := "amb_bird"
			if night > 0.6: name = ["amb_owl", "amb_owl", "amb_dog", "amb_creak", "amb_scream"][randi() % 5]
			elif rain > 0.3: name = "amb_creak"
			if not (indoor and name == "amb_bird"):
				Sfx.play(name, {"pos": pos, "volume": 0.8})
