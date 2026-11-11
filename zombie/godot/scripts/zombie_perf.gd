extends SceneTree
## Measures the GDScript cost of Zombies.update() per frame on the real map (no rendering):
##   godot --headless --path zombie/godot -s res://scripts/zombie_perf.gd

const ZombiesScript := preload("res://scripts/zombies.gd")

var W: Node
var zs
var host := {}
var started := false

func _process(_d: float) -> bool:
	if started:
		return true
	started = true
	W = root.get_node("WorldData")
	run()
	quit(0)
	return true

func fresh() -> void:
	if zs != null:
		zs.clear(); root.remove_child(zs); zs.free()
	zs = ZombiesScript.new()
	root.add_child(zs)
	zs.seed_rng(3)
	zs.director_enabled = false
	host = {"player_pos": Vector3.ZERO, "player_noise": 0.0, "player_visibility": 1.0, "player_alive": true, "player_inside": false,
		"player_bleeding": false, "base_center": null, "base_heat": 0.0, "daylight": 1.0, "hours": 12.0, "day": 5, "luck": 0.0}

func set_player(x: float, z: float) -> void:
	var p := Vector3(x, W.ground_y(x, z, W.height_at(x, z) + 0.6), z)
	host["player_pos"] = p; host["player_eye"] = p + Vector3(0, 1.6, 0); host["camera_pos"] = p + Vector3(0, 1.6, 0)

## run `sec` seconds at 60 fps, moving the player along `vel`; prints the cost of update() per frame
func measure(label: String, sec: float, vel := Vector2.ZERO) -> void:
	var dt := 1.0 / 60.0
	var n := int(sec * 60.0)
	var times := PackedFloat32Array(); times.resize(n)
	var body_us := 0
	for i in n:
		if vel != Vector2.ZERO:
			var x: float = host.player_pos.x + vel.x * dt; var z: float = host.player_pos.z + vel.y * dt
			if W.nav_at(x, z) == 0: set_player(x, z)
		var a := Time.get_ticks_usec()
		zs.update(dt, dt, host)
		var b := Time.get_ticks_usec()
		zs.bodies._process(dt)
		body_us += Time.get_ticks_usec() - b
		times[i] = (b - a) / 1000.0
	var sorted := times.duplicate(); sorted.sort()
	var sum := 0.0
	for t in times: sum += t
	var active := 0; var st := {}
	for z in zs.list:
		if not z.frozen and z.state != &"dead":
			active += 1
			st[z.state] = int(st.get(z.state, 0)) + 1
	print("%-46s mean %.2f ms   median %.2f   95%% %.2f   worst %.2f   (+ bodies %.3f ms)  | %d of %d active, %d bodies, chasing %d  %s" % [
		label, sum / n, sorted[n / 2], sorted[int(n * 0.95)], sorted[n - 1], body_us / 1000.0 / n, active, zs.list.size(), zs.bodies.body_count(), zs.chasing, st])

func ring(n: int, r0: float, r1: float, type := -1) -> void:
	var pp: Vector3 = host.player_pos
	var made := 0; var tries := 0
	while made < n and tries < n * 30:
		tries += 1
		var a := tries * 2.399963; var r := lerpf(r0, r1, fmod(tries * 0.6180339, 1.0))
		var x := pp.x + cos(a) * r; var z := pp.z + sin(a) * r
		if W.nav_at(x, z) != 0:
			continue
		zs.spawn(x, z, type if type >= 0 else zs.pick_type(8), 0, {"y": W.height_at(x, z) + 0.6})
		made += 1

## how steady this machine is: the same fixed piece of arithmetic timed 3000 times
func jitter() -> void:
	var times := PackedFloat32Array()
	for k in 3000:
		var a := Time.get_ticks_usec()
		var x := 0.0
		for i in 12000: x += sin(i * 0.001) * 0.5
		times.append((Time.get_ticks_usec() - a) / 1000.0)
	times.sort()
	print("machine check: a fixed loop takes median %.2f ms, 95%% %.2f, worst %.2f — spikes beyond that ratio below are the machine's, not the code's\n" % [times[1500], times[2850], times[2999]])

func run() -> void:
	print("Zombies.update() cost per frame, 60 fps steps, real map\n")
	jitter()
	# 1. the game as it starts: whole population, player at the cabin, then walking into town
	fresh()
	set_player(W.info.spawn.x, W.info.spawn.z)
	zs.populate()
	measure("start: whole population, player at the cabin", 10.0)
	set_player(-60.0, -40.0)
	for i in 120: zs.update(1.0 / 60.0, 1.0 / 60.0, host)
	measure("whole population, player standing in town (day)", 10.0)
	host.daylight = 0.0; host.hours = 23.0; host.player_visibility = 0.6
	measure("... the same at night", 10.0)
	measure("... walking through town at night (1.5 m/s)", 15.0, Vector2(1.5, 0.3))
	host.player_noise = 40.0
	measure("... after firing a shot (noise 40)", 10.0)
	host.player_noise = 0.0
	# 2. 150 close by on open ground
	for mode in ["day", "night"]:
		fresh()
		set_player(-126.0, -78.0)
		if mode == "night":
			host.daylight = 0.0; host.hours = 23.0
		zs.update(1.0 / 60.0, 1.0 / 60.0, host)
		ring(150, 45.0, 110.0)
		measure("150 within 110 m, not yet aware (%s)" % mode, 10.0)
	# 3. 150 all hunting the player
	fresh()
	set_player(-126.0, -78.0)
	zs.update(1.0 / 60.0, 1.0 / 60.0, host)
	ring(150, 12.0, 38.0)
	measure("150 within 38 m, daylight: first 3 s", 3.0)
	measure("... all hunting, seconds 3-8", 5.0)
	measure("... pressing in / attacking, seconds 8-20", 12.0)
	measure("... player running away (5 m/s), 10 s", 10.0, Vector2(-5.0, 0.0))
	# 4. 150 hunting in the streets (walls, doors, flow field among buildings)
	fresh()
	set_player(-60.0, -40.0)
	zs.update(1.0 / 60.0, 1.0 / 60.0, host)
	ring(150, 10.0, 45.0)
	host.player_noise = 60.0
	measure("150 in the town streets, alerted by a shot: 10 s", 10.0)
	host.player_noise = 0.0
	measure("... next 10 s, player walking", 10.0, Vector2(1.5, 0.0))
	# 5. a horde of 110 walking in on top of the population
	fresh()
	set_player(-60.0, -40.0)
	zs.populate()
	host.daylight = 0.0; host.hours = 23.0
	zs.update(1.0 / 60.0, 1.0 / 60.0, host)
	zs.spawn_horde(-60.0 + 70.0, -40.0, 110, host.player_pos, true)
	measure("population + a horde of 110 arriving: 20 s", 20.0)
	measure("... next 20 s", 20.0)
	# 6. people around: 12 prey for 100 zombies
	fresh()
	set_player(-126.0, -78.0)
	zs.update(1.0 / 60.0, 1.0 / 60.0, host)
	for i in 12:
		var p = zs.add_prey(null, "p%d" % i)
		p.pos = Vector3(-126.0 + cos(i * 0.52) * 25.0, 0, -78.0 + sin(i * 0.52) * 25.0)
		p.pos.y = W.ground_y(p.pos.x, p.pos.z); p.move = 1.0
	ring(100, 30.0, 60.0)
	measure("100 zombies, 12 people and the player: 15 s", 15.0)
	zs.clear(); root.remove_child(zs); zs.free()
