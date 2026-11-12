extends Node
## Sfx (autoload): plays the sound effects exported from the web game's synthesiser (assets/sfx/*.wav).
## Sfx.play("door_open", {"pos": Vector3, "volume": 1.0, "pitch": 1.0})

var index: Dictionary = {}
var _streams: Dictionary = {}
var _flat: Array = []
var _spatial: Array = []
var _fi := 0
var _si := 0
var master := 0.9
var _reverb: AudioEffectReverb
var indoor := false

func _process(dt: float) -> void:
	master = float(Settings.v("sfx_vol"))
	AudioServer.set_bus_volume_db(0, linear_to_db(clampf(float(Settings.v("master_vol")), 0.0001, 1.5)))
	if _reverb != null:
		_reverb.wet = move_toward(_reverb.wet, 0.22 if indoor else 0.0, dt * 0.8)

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	for b in ["Music", "SFX", "Ambience"]:
		if AudioServer.get_bus_index(b) < 0:
			AudioServer.add_bus()
			AudioServer.set_bus_name(AudioServer.bus_count - 1, b)
			AudioServer.set_bus_send(AudioServer.bus_count - 1, "Master")
	# rooms answer back: a short reverb on effects, mixed in only while indoors
	_reverb = AudioEffectReverb.new()
	_reverb.room_size = 0.35; _reverb.damping = 0.6; _reverb.wet = 0.0; _reverb.dry = 1.0; _reverb.spread = 0.6
	AudioServer.add_bus_effect(AudioServer.get_bus_index("SFX"), _reverb)
	var f := FileAccess.open("res://assets/sfx/index.json", FileAccess.READ)
	if f != null:
		index = JSON.parse_string(f.get_as_text())
	for i in 10:
		var p := AudioStreamPlayer.new(); p.bus = "SFX"; add_child(p); _flat.append(p)
	for i in 20:
		var p3 := AudioStreamPlayer3D.new()
		p3.attenuation_model = AudioStreamPlayer3D.ATTENUATION_INVERSE_DISTANCE
		p3.unit_size = 4.0; p3.max_distance = 80.0; p3.panning_strength = 1.2
		p3.bus = "SFX"
		add_child(p3); _spatial.append(p3)

func _stream(name: String, v: int) -> AudioStream:
	var key := "%s_%d" % [name, v]
	if not _streams.has(key):
		var path := "res://assets/sfx/%s.wav" % key
		_streams[key] = load(path) if ResourceLoader.exists(path) else null
	return _streams[key]

func play(name: String, opts: Dictionary = {}) -> void:
	var def = index.get(name)
	if def == null:
		return
	var st := _stream(name, randi() % maxi(1, int(def.get("vars", 1))))
	if st == null:
		return
	var vol: float = float(def.get("vol", 1.0)) * float(opts.get("volume", 1.0)) * master
	var jitter := float(def.get("jitter", 0.0))
	var pitch: float = float(opts.get("pitch", 1.0)) * (1.0 + randf_range(-jitter, jitter))
	var db := linear_to_db(clampf(vol, 0.0001, 4.0)) + 6.0
	if opts.has("pos"):
		var p3: AudioStreamPlayer3D = _spatial[_si]; _si = (_si + 1) % _spatial.size()
		p3.stream = st; p3.volume_db = db + 4.0; p3.pitch_scale = pitch
		p3.max_distance = maxf(12.0, float(def.get("maxDist", 40.0)) * 1.5)
		p3.unit_size = clampf(float(def.get("maxDist", 40.0)) * 0.12, 2.0, 30.0)
		p3.global_position = opts.pos
		p3.play()
	else:
		var p: AudioStreamPlayer = _flat[_fi]; _fi = (_fi + 1) % _flat.size()
		p.stream = st; p.volume_db = db; p.pitch_scale = pitch
		p.play()
