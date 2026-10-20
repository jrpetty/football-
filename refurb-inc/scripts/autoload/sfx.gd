extends Node
## Sound effects by NAME, not path, so final audio can replace placeholders without touching code.
## A sound "foo" plays res://assets/audio/sfx/foo.wav (or .ogg). Variants foo_1, foo_2… are picked at random.

const DIRS := ["res://assets/audio/sfx/", "res://assets/audio/ui/", "res://assets/audio/ambience/"]
const POOL_3D := 24
const POOL_2D := 8

var _cache: Dictionary = {}       # name -> Array[AudioStream]
var _pool3d: Array[AudioStreamPlayer3D] = []
var _pool2d: Array[AudioStreamPlayer] = []
var _i3 := 0
var _i2 := 0


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	for i in POOL_3D:
		var p := AudioStreamPlayer3D.new()
		p.bus = "SFX"
		p.unit_size = 4.0
		p.max_distance = 40.0
		p.attenuation_filter_cutoff_hz = 8000.0
		add_child(p)
		_pool3d.append(p)
	for i in POOL_2D:
		var p2 := AudioStreamPlayer.new()
		p2.bus = "UI"
		add_child(p2)
		_pool2d.append(p2)


func stream(sound: String) -> AudioStream:
	var variants := _variants(sound)
	return variants[randi() % variants.size()] if not variants.is_empty() else null


func _variants(sound: String) -> Array:
	if _cache.has(sound):
		return _cache[sound]
	var out: Array = []
	for dir in DIRS:
		for ext in [".wav", ".ogg"]:
			var base: String = dir + sound + ext
			if ResourceLoader.exists(base):
				out.append(load(base))
			for i in range(1, 9):
				var v: String = dir + sound + "_%d" % i + ext
				if ResourceLoader.exists(v):
					out.append(load(v))
	_cache[sound] = out
	return out


func play_at(sound: String, pos: Vector3, volume_db: float = 0.0, pitch_jitter: float = 0.06) -> void:
	var s := stream(sound)
	if s == null:
		return
	var p := _pool3d[_i3]
	_i3 = (_i3 + 1) % _pool3d.size()
	if not p.is_inside_tree():
		return
	p.stream = s
	p.global_position = pos
	p.volume_db = volume_db
	p.pitch_scale = 1.0 + randf_range(-pitch_jitter, pitch_jitter)
	p.play()


func play_ui(sound: String, volume_db: float = 0.0) -> void:
	var s := stream(sound)
	if s == null:
		return
	var p := _pool2d[_i2]
	_i2 = (_i2 + 1) % _pool2d.size()
	p.stream = s
	p.volume_db = volume_db
	p.play()


func make_loop(sound: String, parent: Node3D, volume_db: float = 0.0, bus: String = "SFX") -> AudioStreamPlayer3D:
	## Attach a looping emitter (fans, engines, hum). Caller controls play()/stop().
	var p := AudioStreamPlayer3D.new()
	p.stream = stream(sound)
	if p.stream is AudioStreamWAV:
		p.stream = p.stream.duplicate()
		p.stream.loop_mode = AudioStreamWAV.LOOP_FORWARD
		p.stream.loop_end = int(p.stream.get_length() * p.stream.mix_rate)
	p.volume_db = volume_db
	p.bus = bus
	p.unit_size = 3.0
	p.max_distance = 35.0
	parent.add_child(p)
	return p
