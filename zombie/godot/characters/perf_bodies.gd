extends SceneTree
## Measures the GDScript cost of Bodies with a crowd (no rendering needed):
##   godot --headless --path zombie/godot -s res://characters/perf_bodies.gd

const BodiesScript := preload("res://characters/bodies.gd")

func _init() -> void:
	var bodies = BodiesScript.new()
	root.add_child(bodies)
	bodies._load()
	var n := 200
	var ids: Array[int] = []
	var t0 := Time.get_ticks_usec()
	for i in n:
		ids.append(bodies.add_body(10 if i % 3 == 0 else (i % 7) % 6, 1.7 + i * 0.91))
	var t_add := (Time.get_ticks_usec() - t0) / float(n)
	# a frame in which every body moves and every body's gait speed changes a little
	var frames := 300
	var acc_pose := 0; var acc_anim := 0; var acc_proc := 0
	for f in frames:
		var a := Time.get_ticks_usec()
		for i in n:
			bodies.set_pose(ids[i], Vector3(i * 0.5, 0, f * 0.02), 0.01 * f)
		var b := Time.get_ticks_usec()
		for i in n:
			bodies.set_anim(ids[i], &"walk", {"speed": 1.0 + 0.3 * sin(f * 0.05 + i)})
		var c := Time.get_ticks_usec()
		bodies._process(1.0 / 60.0)
		var d := Time.get_ticks_usec()
		acc_pose += b - a; acc_anim += c - b; acc_proc += d - c
	# typical frame: everybody moves, a tenth change clip
	var acc_typ := 0
	for f in frames:
		var a := Time.get_ticks_usec()
		for i in n:
			bodies.set_pose(ids[i], Vector3(i * 0.5, 0, f * 0.02), 0.0)
			if (i + f) % 10 == 0:
				bodies.set_anim(ids[i], &"chase" if (i + f) % 20 == 0 else &"idle", {"speed": 2.0})
		bodies._process(1.0 / 60.0)
		acc_typ += Time.get_ticks_usec() - a
	# one-shots report when they finish
	bodies.set_anim(ids[1], &"attack", {"restart": true, "loop": false})
	var len: float = bodies.clip_length(ids[1], &"attack")
	var steps := 0
	while not bodies.anim_done(ids[1]) and steps < 1000:
		bodies._process(1.0 / 60.0); steps += 1
	print("perf_bodies: %d bodies; add %.0f us each; per frame: set_pose all %.2f ms, set_anim(speed) all %.2f ms, _process %.3f ms; typical frame (all move, 10%% change clip) %.2f ms" % [n, t_add, acc_pose / float(frames) / 1000.0, acc_anim / float(frames) / 1000.0, acc_proc / float(frames) / 1000.0, acc_typ / float(frames) / 1000.0])
	print("attack clip %.2f s, anim_done after %.2f s; clips for a walker: %d, for a person: %d" % [len, steps / 60.0, bodies.clip_names(ids[1]).size(), bodies.clip_names(ids[0]).size()])
	quit()
