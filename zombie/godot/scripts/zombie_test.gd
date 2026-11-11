extends SceneTree
## Headless behaviour test for Zombies against the real world data:
##   godot --headless --path zombie/godot -s res://scripts/zombie_test.gd
## Drops zombies near the player / a registered Prey and steps the simulation, checking chase, attack signals,
## howl, burst, death, limb damage, knock-downs, feeding, dormancy, bashing, hordes, and a save/load round trip.

const ZombiesScript := preload("res://scripts/zombies.gd")

var W: Node
var zs
var host := {}
var ev := {}
var fails := 0
var passes := 0
var started := false
var C := Vector3.ZERO            # a wide open spot in the real map

func _process(_d: float) -> bool:
	if started:
		return true
	started = true
	W = root.get_node("WorldData")
	run()
	zs.clear(); root.remove_child(zs); zs.free()
	ev.clear()
	print("\n==== %d passed, %d failed ====" % [passes, fails])
	quit(1 if fails > 0 else 0)
	return true

func ok(cond: bool, msg: String) -> void:
	if cond: passes += 1
	else: fails += 1
	print(("  ok    " if cond else "  FAIL  ") + msg)

func fresh(seed := 7) -> void:
	if zs != null:
		zs.clear(); root.remove_child(zs); zs.free()
	zs = ZombiesScript.new()
	root.add_child(zs)
	zs.seed_rng(seed)
	zs.director_enabled = false
	ev = {"player": [], "human": [], "burst": [], "howl": [], "killed": [], "structure": [], "horde": []}
	zs.player_attacked.connect(func(z, dmg): ev.player.append([z, dmg]))
	zs.human_attacked.connect(func(p, z, dmg): ev.human.append([p, z, dmg]))
	zs.burst.connect(func(pos, r, z): ev.burst.append([pos, r, z]))
	zs.howl.connect(func(pos, r, z): ev.howl.append([pos, r, z]))
	zs.killed.connect(func(z, head): ev.killed.append([z, head]))
	zs.structure_hit.connect(_on_structure)
	zs.horde_spawned.connect(func(n, tb): ev.horde.append([n, tb]))
	host = {"player_pos": at(0, 0), "player_noise": 0.0, "player_visibility": 1.0, "player_alive": true, "player_inside": false,
		"player_bleeding": false, "base_center": null, "base_heat": 0.0, "daylight": 1.0, "hours": 12.0, "day": 1, "luck": 0.0}
	_set_player(at(0, 0))

func _on_structure(ref, dmg) -> void:
	ev.structure.append([ref, dmg])
	if ref is Dictionary and ref.get("kind", "") == "door":
		# what the game does with a door: wear it down, then it swings open for good
		ref.hp -= dmg
		if ref.hp <= 0.0 and not ref.open:
			ref.open = true; W.set_solid(ref.box, false, 30)
	elif ref is Dictionary and ref.has("hp"):
		ref.hp -= dmg
		if ref.hp <= 0.0 and not ref.get("destroyed", false):
			ref["destroyed"] = true
			if ref.get("box") != null: W.remove_box(ref.box)

func _set_player(p: Vector3) -> void:
	host["player_pos"] = p; host["player_eye"] = p + Vector3(0, 1.6, 0); host["camera_pos"] = p + Vector3(0, 1.6, 0)

## a point dx, dz metres from the open spot, on the ground
func at(dx: float, dz: float) -> Vector3:
	return Vector3(C.x + dx, W.ground_y(C.x + dx, C.z + dz), C.z + dz)

func step(sec: float, dt := 1.0 / 60.0) -> void:
	for i in int(round(sec / dt)):
		zs.update(dt, dt * 1.0, host)
		zs.bodies._process(dt)

## step until cond() or the time runs out; returns the seconds it took (or -1)
func until(cond: Callable, max_sec: float, dt := 1.0 / 60.0) -> float:
	var t := 0.0
	while t < max_sec:
		zs.update(dt, dt * 1.0, host)
		zs.bodies._process(dt)
		t += dt
		if cond.call():
			return t
	return -1.0

func face_to(z, p: Vector3) -> void:
	z.yaw = atan2(p.x - z.pos.x, p.z - z.pos.z)

func find_open(r: int) -> Vector3:
	# the most open ground within the town outskirts: no solid cell within r metres, and dry
	var best := Vector3.ZERO; var bd := 1e9
	for gx in range(-330, 331, 6):
		for gz in range(-330, 331, 6):
			if W.water_depth(gx, gz) > 0.0:
				continue
			var clear := true
			for i in range(-r, r + 1, 2):
				for j in range(-r, r + 1, 2):
					if W.nav_at(gx + i, gz + j) != 0:
						clear = false; break
				if not clear: break
			if not clear:
				continue
			var d := Vector2(gx + 168.0, gz + 196.0).length()
			if d < bd and d > 80.0:
				bd = d; best = Vector3(gx, 0, gz)
	return best

func states() -> Dictionary:
	var out := {}
	for z in zs.list:
		out[z.state] = int(out.get(z.state, 0)) + 1
	return out

func run() -> void:
	C = find_open(44)
	print("open ground at ", C, "  (nav ", W.nav.size(), " cells, ", W.info.boxes.size(), " boxes)")
	ok(C != Vector3.ZERO, "found open ground in the real map")
	t_chase_attack()
	t_prey_and_feeding()
	t_howler()
	t_bloat()
	t_raycast_and_death()
	t_limbs()
	t_knockdown()
	t_search()
	t_bash()
	t_structures()
	t_real_door()
	t_turned()
	t_noise_and_smell()
	t_day_night_and_save()
	t_freeze()
	t_director()
	t_populate()
	t_types()

func t_chase_attack() -> void:
	print("\n-- chase and attack the player")
	fresh()
	var z = zs.spawn(C.x + 14.0, C.z, 0, 0, {"decay": 0.4})
	face_to(z, host.player_pos)
	var seq: Array = [z.state]
	var t := until(func():
		if seq.back() != z.state: seq.append(z.state)
		return not ev.player.is_empty(), 30.0)
	print("    states: ", seq, "   first hit after %.1f s, chasing=%d seen=%d" % [t, zs.chasing, zs.seen_count])
	ok(seq.has(&"chase"), "sees the player in daylight and gives chase")
	ok(seq.has(&"attack"), "closes and attacks")
	ok(t > 0.0, "player_attacked fired")
	if t > 0.0:
		ok(ev.player[0][0] == z and ev.player[0][1] > 3.0 and ev.player[0][1] < 20.0, "signal carries the zombie and a sane damage (%.1f)" % ev.player[0][1])
	ok(z.body >= 0 and zs.bodies.current_clip(z.body) == &"attack", "its body is playing the attack clip (%s)" % (zs.bodies.current_clip(z.body) if z.body >= 0 else &"-"))
	var d: float = Vector2(z.pos.x - host.player_pos.x, z.pos.z - host.player_pos.z).length()
	ok(d < 2.2 and d > 0.5, "stands at arm's length (%.2f m)" % d)
	var n0: int = ev.player.size()
	step(6.0)
	ok(ev.player.size() >= n0 + 3, "keeps striking: %d blows in 6 s" % (ev.player.size() - n0))
	# at night the same walker starts facing away and must not notice a silent player at 14 m
	fresh()
	host.daylight = 0.0; host.hours = 1.0; host.player_visibility = 0.4
	var z2 = zs.spawn(C.x + 14.0, C.z, 0, 0, {"decay": 0.4})
	z2.yaw = PI * 0.5
	z2.state = &"dormant"
	step(4.0)
	ok(z2.state != &"chase" and z2.state != &"attack", "a walker facing away at night does not notice a quiet player 14 m off (state %s)" % z2.state)

func t_prey_and_feeding() -> void:
	print("\n-- hunt a registered person, then feed on the body")
	fresh()
	_set_player(at(-120, 0))
	var p = zs.add_prey(null, "Marcus")
	p.pos = at(0, 0); p.move = 1.0
	var a = zs.spawn(C.x + 10.0, C.z + 1.0, 0, 0, {"decay": 0.3})
	var b = zs.spawn(C.x - 9.0, C.z - 3.0, 0, 0, {"decay": 0.5})
	var c = zs.spawn(C.x + 2.0, C.z + 12.0, 0, 0, {"decay": 0.5})
	for z in [a, b, c]: face_to(z, p.pos)
	var t := until(func(): return not ev.human.is_empty(), 30.0)
	ok(t > 0.0, "human_attacked fired after %.1f s" % t)
	if t > 0.0:
		ok(ev.human[0][0] == p and ev.human[0][2] > 2.0, "signal carries the Prey, the zombie and damage (%.1f)" % ev.human[0][2])
	ok(ev.player.is_empty(), "the far-off player was not attacked")
	ok(a.prey == p or b.prey == p or c.prey == p, "zombies record the Prey they are after")
	step(3.0)
	ok(p.hunters >= 2, "hunters counted on the prey: %d" % p.hunters)
	ok(zs.chasing == 0, "and they do not count as chasing the player")
	# the person dies and is left lying: the dead stop attacking and gather to eat
	p.alive = false; p.corpse = true; p.move = 0.0
	var n0: int = ev.human.size()
	var tf := until(func(): return (a.state == &"feed" and a.anim == &"feed") or (b.state == &"feed" and b.anim == &"feed") or (c.state == &"feed" and c.anim == &"feed"), 60.0)
	print("    after death: states ", [a.state, b.state, c.state], "  carrion ", zs.carrion.size(), "  feeding after %.1f s" % tf)
	ok(ev.human.size() <= n0 + 1, "no more attacks on the dead")
	ok(tf > 0.0 and tf < 6.0, "the killers fall on the body at once (%.1f s)" % tf)
	step(20.0)
	var feeding := 0
	for z in [a, b, c]:
		if z.state == &"feed": feeding += 1
	ok(feeding >= 2, "%d of 3 are feeding" % feeding)
	var fz = a if a.state == &"feed" else b
	ok(fz.body >= 0 and zs.bodies.current_clip(fz.body) == &"feed", "feed clip on the body")
	# the body is taken away: they get up and drift off
	zs.remove_prey(p)
	step(4.0)
	ok(a.state != &"feed" and b.state != &"feed" and c.state != &"feed", "feeding ends when the body is gone (%s, %s, %s)" % [a.state, b.state, c.state])
	# plain carrion (a dropped carcass)
	fresh()
	_set_player(at(-120, 0))
	host.daylight = 0.0
	var z = zs.spawn(C.x + 6.0, C.z, 0, 0, {"decay": 0.5})
	z.state_t = 100.0
	zs.add_carrion(at(0, 0), 120.0)
	var t2 := until(func(): return z.state == &"feed" and z.anim == &"feed", 40.0)
	ok(t2 > 0.0, "add_carrion draws an idle zombie in to feed (%.1f s)" % t2)

func t_howler() -> void:
	print("\n-- howler")
	fresh()
	var h = zs.spawn(C.x + 16.0, C.z, 3)
	face_to(h, host.player_pos)
	var others: Array = []
	for i in 6:
		var o = zs.spawn(C.x + 30.0 + i * 2.0, C.z + 14.0 - i * 5.0, 0, 0, {"decay": 0.5})
		o.yaw = PI * 0.5            # backs to the player
		o.pause = 999.0
		others.append(o)
	var t := until(func(): return not ev.howl.is_empty(), 8.0)
	ok(t > 0.0, "howl fired %.2f s after it saw the player" % t)
	if t > 0.0:
		ok(ev.howl[0][2] == h and ev.howl[0][1] > 30.0, "howl carries position, radius %.0f and the howler" % ev.howl[0][1])
	var roused := 0; var enraged := 0
	for o in others:
		if o.state == &"investigate" or o.state == &"chase": roused += 1
		if o.enraged > 0.0: enraged += 1
	ok(roused == 6 and enraged == 6, "the pack answers: %d roused, %d enraged" % [roused, enraged])
	ok(h.body >= 0 and zs.bodies.current_clip(h.body) == &"scream", "scream clip playing")
	var d0: Array = []
	for o in others: d0.append(Vector2(o.pos.x - host.player_pos.x, o.pos.z - host.player_pos.z).length())
	step(8.0)
	var d: float = Vector2(h.pos.x - host.player_pos.x, h.pos.z - host.player_pos.z).length()
	ok(d > 4.5, "the howler hangs back (%.1f m) while the others close" % d)
	var closing := 0; var gained := 0.0
	for i in others.size():
		var dn: float = Vector2(others[i].pos.x - host.player_pos.x, others[i].pos.z - host.player_pos.z).length()
		gained += d0[i] - dn
		if dn < d0[i] - 5.0: closing += 1
	ok(closing >= 5, "%d of 6 called walkers have closed at least 5 m in 8 s (mean %.1f m)" % [closing, gained / 6.0])
	until(func(): return not ev.player.is_empty(), 30.0)
	ok(not ev.player.is_empty() and ev.player[0][0] != h, "and one of them lands the first blow")

func t_bloat() -> void:
	print("\n-- bloat")
	fresh()
	_set_player(at(-60, 0))
	var bl = zs.spawn(C.x, C.z, 4)
	var n1 = zs.spawn(C.x + 1.6, C.z, 0, 0, {"decay": 0.5})
	var n2 = zs.spawn(C.x - 1.2, C.z + 1.5, 0, 0, {"decay": 0.5})
	var far = zs.spawn(C.x + 9.0, C.z, 0, 0, {"decay": 0.5})
	for z in [bl, n1, n2, far]: z.yaw = 0.0
	var ref := {"kind": "wall", "hp": 300.0}
	ref["box"] = W.add_box(Vector3(C.x - 1.0, at(0, 3).y, C.z + 3.0), Vector3(C.x + 1.0, at(0, 3).y + 2.5, C.z + 3.3), "wall", ref, false)
	W._raster_box(ref.box, 40, ref, false)              # as World does for doors
	step(0.5)
	var hp1: float = n1.hp; var hpf: float = far.hp
	var died: bool = zs.damage(bl, 2000.0, Vector3(1, 0, 0), {"part": 1, "by_player": true})
	ok(died and bl.state == &"dead", "bloat dies to a body shot")
	ok(ev.burst.size() == 1 and ev.burst[0][2] == bl and ev.burst[0][1] > 3.0, "burst fired with radius %.1f" % (ev.burst[0][1] if ev.burst.size() > 0 else 0.0))
	ok(n1.hp < hp1 - 20.0 and n2.hp < n2.max_hp, "neighbours caught in it: %.0f -> %.0f hp" % [hp1, n1.hp])
	ok(far.hp == hpf, "a zombie 9 m away is untouched")
	ok(n1.state == &"down" or n1.state == &"stagger" or n1.state == &"dead", "the nearest is thrown down (%s)" % n1.state)
	var hit_wall := false
	for e in ev.structure:
		if is_same(e[0], ref) and e[1] > 20.0: hit_wall = true
	ok(hit_wall, "structure_hit on the wall beside it (%d structure events)" % ev.structure.size())
	ok(ev.killed.size() >= 1 and ev.killed[0][0] == bl and ev.killed[0][1] == false, "killed(z, headshot=false) fired")
	W.remove_box(ref.box)
	# a head shot does not set it off
	fresh()
	var b2 = zs.spawn(C.x + 5.0, C.z, 4)
	zs.damage(b2, 2000.0, Vector3(1, 0, 0), {"head": true})
	ok(b2.state == &"dead" and ev.burst.is_empty(), "a head shot kills a bloat cleanly")

func t_raycast_and_death() -> void:
	print("\n-- raycast, head shots, corpses")
	fresh()
	var z = zs.spawn(C.x + 5.0, C.z, 0, 0, {"decay": 0.5})
	face_to(z, host.player_pos)
	z.state = &"dormant"
	step(0.2)
	var eye: Vector3 = host.player_eye
	var parts := {}
	for e in [["head", 1.64, 0], ["chest", 1.2, 1], ["shin", 0.3, 3]]:
		var tgt: Vector3 = z.pos + Vector3(-0.08, float(e[1]) * z.scale, 0)
		var r: Dictionary = zs.raycast(eye, (tgt - eye).normalized(), 30.0)
		parts[e[0]] = r.get("part", -1)
		ok(r.get("zombie") == z and int(r.get("part", -1)) == int(e[2]), "ray at the %s -> part %d at %.2f m" % [e[0], r.get("part", -1), r.get("t", -1.0)])
	var side: Vector3 = z.pos + Vector3(-0.08, 1.2 * z.scale, 0.24)
	var rs: Dictionary = zs.raycast(eye, (side - eye).normalized(), 30.0)
	ok(rs.get("zombie") == z and int(rs.part) == 2, "ray at the shoulder -> arm (part %d)" % rs.get("part", -1))
	ok(zs.raycast(eye, Vector3(0, 0, 1), 30.0).is_empty(), "a ray the other way misses ({})")
	ok(zs.raycast(eye, (z.pos + Vector3(0, 1.2, 0) - eye).normalized(), 3.0).is_empty(), "max distance is respected")
	var hp0: float = z.hp
	var k1: bool = zs.damage(z, hp0 / 3.0 - 2.0, Vector3(1, 0, 0), {"head": true})
	ok(not k1 and absf(z.hp - (hp0 - (hp0 / 3.0 - 2.0) * 3.0)) < 0.01, "head damage is tripled (%.0f -> %.0f)" % [hp0, z.hp])
	ok(z.state != &"dormant" or z.alerted > 0.0, "being shot wakes it (%s)" % z.state)
	var k2: bool = zs.damage(z, 10.0, Vector3(1, 0, 0), {"head": true})
	ok(k2 and z.state == &"dead" and zs.kills == 1, "second head shot kills; kills = %d" % zs.kills)
	ok(ev.killed.size() == 1 and ev.killed[0][0] == z and ev.killed[0][1] == true, "killed(z, headshot=true)")
	ok(zs.list.has(z) and zs.to_rows().is_empty(), "the corpse stays in the world but not in the save")
	ok(zs.raycast(eye, (z.pos + Vector3(0, 1.2, 0) - eye).normalized(), 30.0).is_empty(), "rays pass over corpses")
	print("    crawler ", z.crawler, " style ", z.death_style, " anim ", z.anim, " body ", z.body)
	ok(z.body >= 0 and String(zs.bodies.current_clip(z.body)).begins_with("death"), "death clip: %s" % zs.bodies.current_clip(z.body))
	ok(zs.corpse_near(host.player_pos, 8.0) == z, "corpse_near finds it")
	ok(not zs.damage(z, 50.0, Vector3(1, 0, 0)), "damage on a corpse is ignored")
	# corpses rot away after two game days
	for i in 400:
		zs.update(1.0 / 30.0, 10.0, host)
	ok(not zs.list.has(z), "the corpse is gone after two game days")
	ok(zs.bodies.body_count() == 0, "and its body was released (%d bodies)" % zs.bodies.body_count())

func t_limbs() -> void:
	print("\n-- limb damage")
	fresh()
	_set_player(at(-150, 0))
	var crawlers := 0; var arms := 0; var n := 200
	var one = null
	for i in n:
		var z = zs.spawn(C.x + (i % 20) * 2.0 - 20.0, C.z + (i / 20) * 2.0 - 10.0, 0, 0, {"decay": 0.5})
		z.arm_loss_l = false; z.arm_loss_r = false
		zs.damage(z, 30.0, Vector3(1, 0, 0), {"part": 3, "by_player": false})
		if z.crawler:
			crawlers += 1; one = z
		zs.damage(z, 30.0, Vector3(1, 0, 0), {"part": 2, "side": 1, "by_player": false})
		if z.arm_loss_l: arms += 1
	ok(crawlers > 60 and crawlers < 170, "%d of %d crippled by a 30-damage leg hit" % [crawlers, n])
	ok(arms > 40 and arms < 150, "%d of %d lost the arm to a 30-damage arm hit" % [arms, n])
	ok(one != null and one.speed < 0.5 and one.run_speed < 1.5, "crawlers are slow (walk %.2f, run %.2f)" % [one.speed, one.run_speed])
	var br = zs.spawn(C.x, C.z - 20.0, 2)
	for i in 6: zs.damage(br, 40.0, Vector3(1, 0, 0), {"part": 3, "by_player": false})
	ok(not br.crawler, "a brute cannot be crippled")
	# a crawler still comes for you, on its belly
	fresh()
	var cz = zs.spawn(C.x + 5.0, C.z, 0, 0, {"decay": 0.4})
	face_to(cz, host.player_pos)
	cz.crawler = true; zs._apply_decay(cz)
	var t := until(func(): return not ev.player.is_empty(), 40.0)
	ok(t > 3.0, "a crawler drags itself 5 m and bites (%.1f s)" % t)
	ok(cz.body >= 0 and zs.bodies.current_clip(cz.body) == &"crawl", "it stays prone to bite: clip %s" % (zs.bodies.current_clip(cz.body) if cz.body >= 0 else &"-"))
	var r: Dictionary = zs.raycast(host.player_eye, (cz.pos + Vector3(0, 0.22, 0) - host.player_eye).normalized(), 10.0)
	ok(r.get("zombie") == cz, "rays hit a crawler low on the ground")
	ok(zs.raycast(host.player_eye, (cz.pos + Vector3(0, 1.3, 0) - host.player_eye).normalized(), 10.0).is_empty(), "and pass over it at chest height")

func t_knockdown() -> void:
	print("\n-- stagger and knock-down")
	fresh()
	var z = zs.spawn(C.x + 9.0, C.z, 0, 0, {"decay": 0.4})
	face_to(z, host.player_pos)
	until(func(): return z.state == &"chase", 5.0)
	step(0.5)
	zs.damage(z, 4.0, Vector3(1, 0, 0), {"knock": 1.4})
	ok(z.state == &"stagger", "a firm shove staggers it (%s)" % z.state)
	ok(z.body >= 0 and String(zs.bodies.current_clip(z.body)).begins_with("hit"), "flinch clip: %s" % zs.bodies.current_clip(z.body))
	var t := until(func(): return z.state == &"chase", 3.0)
	ok(t > 0.4 and t < 1.6, "back in the chase after %.2f s" % t)
	var x0: float = z.pos.x
	zs.damage(z, 4.0, Vector3(1, 0, 0), {"knock": 3.2})
	ok(z.state == &"down", "a hard blow puts it on the ground (%s)" % z.state)
	ok(z.body >= 0 and String(zs.bodies.current_clip(z.body)).begins_with("fall"), "fall clip: %s" % zs.bodies.current_clip(z.body))
	step(0.4)
	ok(z.pos.x > x0 + 0.1, "and shoves it back %.2f m" % (z.pos.x - x0))
	var seq: Array = []
	var t2 := until(func():
		var c: StringName = zs.bodies.current_clip(z.body)
		if seq.is_empty() or seq.back() != c: seq.append(c)
		return z.state == &"chase" or z.state == &"attack", 12.0)
	print("    clips while down: ", seq)
	ok(t2 > 1.5, "gets back up and comes on after %.1f s" % t2)
	var has_rise := false
	for c in seq:
		if String(c).begins_with("rise"): has_rise = true
	ok(has_rise, "rise clip played")
	var br = zs.spawn(C.x + 6.0, C.z + 6.0, 2)
	zs.damage(br, 4.0, Vector3(1, 0, 0), {"knock": 3.2})
	ok(br.state != &"down" and br.state != &"stagger", "a brute shrugs the same blow off (%s)" % br.state)

func t_search() -> void:
	print("\n-- losing the quarry")
	fresh()
	var z = zs.spawn(C.x + 12.0, C.z, 0, 0, {"decay": 0.4})
	face_to(z, host.player_pos)
	until(func(): return z.state == &"chase", 5.0)
	step(1.0)
	var seen_at: Vector3 = host.player_pos
	_set_player(at(-150, 30))               # gone
	var seq: Array = [z.state]
	var t := until(func():
		if seq.back() != z.state: seq.append(z.state)
		return z.state == &"idle" or z.state == &"wander" or z.state == &"dormant", 60.0)
	print("    states: ", seq, "  gave up after %.1f s, %.1f m from where it last saw the player" % [t, z.pos.distance_to(seen_at)])
	ok(seq.has(&"search"), "goes to where it last saw the player and searches")
	ok(t > 5.0, "then gives up")
	ok(z.pos.distance_to(seen_at) < 12.0, "it searched around the last-seen spot")
	ok(zs.chasing == 0, "chasing count back to 0")

func t_bash() -> void:
	print("\n-- walls and doors")
	fresh()
	# the player inside a ring of four breakable walls
	var y: float = at(0, 0).y
	var refs: Array = []
	for w in [[-3.0, -3.0, 3.0, -2.7], [-3.0, 2.7, 3.0, 3.0], [-3.0, -3.0, -2.7, 3.0], [2.7, -3.0, 3.0, 3.0]]:
		var ref := {"kind": "wall", "hp": 60.0}
		ref["box"] = W.add_box(Vector3(C.x + w[0], y - 0.5, C.z + w[1]), Vector3(C.x + w[2], y + 2.6, C.z + w[3]), "wall", ref, false)
		W._raster_box(ref.box, 40, ref, false)
		refs.append(ref)
	host.player_inside = true
	var z = zs.spawn(C.x + 10.0, C.z + 0.5, 0, 0, {"decay": 0.4})
	var br = zs.spawn(C.x - 11.0, C.z + 0.5, 5)
	face_to(z, host.player_pos); face_to(br, host.player_pos)
	step(1.0)
	ok(z.state != &"chase", "a wall hides the player (state %s)" % z.state)
	zs.damage(z, 1.0, Vector3(1, 0, 0), {"knock": 0.2})          # now it knows
	zs.damage(br, 1.0, Vector3(-1, 0, 0), {"knock": 0.2})
	var seq: Array = [z.state]
	var t := until(func():
		if seq.back() != z.state: seq.append(z.state)
		return not ev.structure.is_empty(), 30.0)
	ok(t > 0.0, "structure_hit fired after %.1f s (states %s)" % [t, seq])
	if t > 0.0:
		ok(refs.has(ev.structure[0][0]) and ev.structure[0][1] > 3.0, "it carries the wall's ref and damage %.1f" % ev.structure[0][1])
	var t2 := until(func(): return not ev.player.is_empty(), 90.0)
	var broken := 0
	for r in refs:
		if r.get("destroyed", false): broken += 1
	var biggest := 0.0
	for e in ev.structure: biggest = maxf(biggest, e[1])
	print("    %d blows on the walls, heaviest %.0f; %d walls down; player reached after %.1f s" % [ev.structure.size(), biggest, broken, t2])
	ok(broken >= 1, "they break through")
	ok(biggest > 60.0, "the breaker hits structures far harder (%.0f)" % biggest)
	ok(t2 > 0.0, "and reach the player inside")
	for r in refs:
		if not r.get("destroyed", false): W.remove_box(r.box)
	ok(W.nav_at(C.x + 2.8, C.z) == 0 and W.nav_ref.is_empty(), "test walls removed from the world again")

func t_structures() -> void:
	print("\n-- breakers, and prey out of reach")
	fresh()
	_set_player(at(-90, 0))
	host.daylight = 0.0; host.hours = 23.0
	step(0.1)
	var y: float = at(0, 0).y
	# something the player built, a little off the breaker's road
	var built := {"kind": "wall", "built": true, "hp": 400.0}
	built["box"] = W.add_box(Vector3(C.x + 3.0, y - 0.5, C.z + 3.0), Vector3(C.x + 5.0, y + 2.5, C.z + 3.3), "wall", built, false)
	W._raster_box(built.box, 40, built, false)
	var h = zs.spawn_horde(C.x + 4.0, C.z - 14.0, 1, at(4, 20), true)
	var br = zs.list[0]
	br.type = 5; zs._apply_decay(br, true)
	var t := until(func(): return not ev.structure.is_empty(), 40.0)
	ok(t > 0.0 and is_same(ev.structure[0][0], built) and ev.structure[0][1] > 60.0, "a breaker in a horde turns aside to smash a built wall (%.0f damage after %.1f s)" % [ev.structure[0][1] if t > 0.0 else 0.0, t])
	var t2 := until(func(): return built.get("destroyed", false), 60.0)
	ok(t2 > 0.0, "and keeps at it until it is down (%.1f s more, %d blows)" % [t2, ev.structure.size()])
	if not built.get("destroyed", false): W.remove_box(built.box)
	# a person up out of reach, on nothing they can break
	fresh()
	_set_player(at(-90, 0))
	step(0.1)
	var p = zs.add_prey(null, "Ana")
	p.pos = at(0, 0) + Vector3(0, 2.6, 0); p.move = 0.0
	var z = zs.spawn(C.x + 8.0, C.z, 0, 0, {"decay": 0.4})
	face_to(z, p.pos)
	var seq: Array = [z.state]
	var t3 := until(func():
		if seq.back() != z.state: seq.append(z.state)
		return z.ignore == p, 30.0)
	ok(t3 > 0.0 and ev.human.is_empty(), "prey 2.6 m up: it comes, cannot reach, and gives up on her for a while (%s, %.1f s)" % [seq, t3])
	step(6.0)
	ok(z.prey == null and ev.human.is_empty(), "it does not come straight back (state %s)" % z.state)

## doors set up from the town's own file, exactly as World does it
func doors_like_world() -> Dictionary:
	var town = JSON.parse_string(FileAccess.get_file_as_string("res://assets/world/town.json"))
	var out := {}
	for d in town.doors:
		var box = W.export_box.get(int(d.box))
		if box == null:
			continue
		var ref := {"kind": "door", "id": int(d.id), "data": d, "box": box, "open": bool(d.open), "hp": float(d.hp), "locked": bool(d.locked)}
		box.ref = ref; box.tag = "door"
		if not ref.open: W._raster_box(box, 30, ref, false)
		out[int(d.id)] = ref
	return out

func t_real_door() -> void:
	print("\n-- a real house: a horde at the front door")
	fresh()
	var doors := doors_like_world()
	ok(doors.size() > 100 and not W.nav_ref.is_empty(), "%d doors of the town in the nav grid" % doors.size())
	# house 1 (-105, -72): the player just inside its front door (door 3, on the north wall)
	var inside := Vector3(-108.1, 3.508, -68.8)
	_set_player(inside)
	host.player_inside = true; host.daylight = 0.0; host.hours = 23.0; host.day = 9
	host.base_center = inside; host.base_heat = 60.0
	step(0.1)
	var h = zs.spawn_horde(-108.0, -48.0, 14, inside, true)
	var types := {}
	for z in zs.list: types[z.type] = int(types.get(z.type, 0)) + 1
	var t := until(func(): return not ev.structure.is_empty(), 120.0, 1.0 / 30.0)
	ok(t > 0.0 and ev.structure[0][0].get("kind", "") == "door", "the horde (%s) reaches the house and starts on a door after %.0f s" % [types, t])
	var house_doors := [doors[3], doors[10]]
	var t2 := until(func(): return house_doors[0].open or house_doors[1].open, 240.0, 1.0 / 30.0)
	var hits := {}
	for e in ev.structure: hits[int(e[0].id)] = int(hits.get(int(e[0].id), 0)) + 1
	ok(t2 > 0.0, "an outer door gives way %.0f s later (blows per door id: %s)" % [t2, hits])
	var t3 := until(func(): return not ev.player.is_empty(), 180.0, 1.0 / 30.0)
	var in_house := 0
	for z in zs.list:
		if z.pos.x > -112.0 and z.pos.x < -98.0 and z.pos.z > -77.0 and z.pos.z < -67.0: in_house += 1
	ok(t3 > 0.0, "they come in and attack the player (%.0f s after the door; %d inside the house, states %s)" % [t3, in_house, states()])
	var through_walls := 0
	for z in zs.list:
		if W.resolve(z.pos, 0.02, 1.7).pos.distance_to(z.pos) > 0.01: through_walls += 1
	ok(through_walls == 0, "nobody is standing in a wall (%d)" % through_walls)

func t_turned() -> void:
	print("\n-- the newly turned")
	fresh()
	_set_player(at(-100, 0))
	step(0.1)
	var look := {"skin": 2, "shirt": 3, "pants": 1, "hair": 4, "build": 1.1, "height": 1.04, "hairStyle": 2}
	var z = zs.spawn_turned({"pos": at(3, 2), "yaw": 1.0, "seed": 42.5, "look": look, "name": "Marcus"})
	ok(z != null and z.turned and z.state == &"rise" and z.decay == 0.0, "spawn_turned(dict): lying, fresh, about to rise")
	ok(zs.describe(z) == "what's left of Marcus" and zs.stage(z) == "fresh", "describe: \"%s\", stage %s" % [zs.describe(z), zs.stage(z)])
	ok(z.body >= 0 and String(zs.bodies.current_clip(z.body)).begins_with("lie"), "it has a body at once, lying where it fell (%s)" % (zs.bodies.current_clip(z.body) if z.body >= 0 else &"-"))
	var t := until(func(): return z.state != &"rise", 10.0)
	ok(t > 2.0 and t < 6.0, "rises after %.1f s (now %s)" % [t, z.state])
	var b = zs.bodies.get_body(z.body)
	ok(int(b.shirt) == 3 and int(b.hair) == 4, "keeps the clothes and hair it died in")
	ok(z.run_speed > 2.6 and z.max_hp > 100.0, "the fresh are fast and strong (run %.1f m/s, %.0f hp)" % [z.run_speed, z.max_hp])
	# from a Prey with a body of its own
	var hb: int = zs.bodies.add_body(10, 12.25, {"shirt": 5})
	var p = zs.add_prey(null, "Ana", hb)
	p.pos = at(-4, 0)
	p.alive = false
	var z2 = zs.spawn_turned(p)
	ok(z2 != null and z2.name == "Ana" and z2.seed == 12.25 and int(z2.look.get("shirt", -1)) == 5, "spawn_turned(prey) copies the person's seed and look")
	ok(zs.describe(zs.spawn(C.x, C.z + 30.0, 0, 0, {"decay": 0.9})) == "Husk" and zs.describe(zs.spawn(C.x, C.z + 32.0, 3)) == "Howler", "describe: Husk, Howler")

func t_noise_and_smell() -> void:
	print("\n-- sound and the smell of blood")
	fresh()
	host.daylight = 0.0; host.hours = 1.0; host.player_visibility = 0.3
	var near: Array = []
	for i in 5:
		var z = zs.spawn(C.x + 22.0 + i * 1.5, C.z + i * 3.0 - 6.0, 0, 0, {"decay": 0.5})
		z.yaw = PI * 0.5; z.pause = 999.0
		near.append(z)
	var farz = zs.spawn(C.x + 40.0, C.z + 30.0, 0, 0, {"decay": 0.5})
	farz.yaw = PI * 0.5; farz.pause = 999.0
	step(2.0)
	var idle := 0
	for z in near:
		if z.state == &"idle": idle += 1
	ok(idle == 5, "at night, unseen at 22 m, they stand idle")
	zs.noise(host.player_pos, 30.0)
	step(0.2)
	var inv := 0
	for z in near:
		if z.state == &"investigate" and z.target.distance_to(host.player_pos) < 6.0: inv += 1
	ok(inv == 5 and farz.state == &"idle", "noise(pos, 30): the 5 within earshot investigate, the one at 50 m does not")
	fresh()
	host.daylight = 0.0; host.hours = 1.0; host.player_visibility = 0.3
	var s = zs.spawn(C.x + 18.0, C.z, 0, 0, {"decay": 0.5})
	s.yaw = PI * 0.5; s.pause = 999.0
	step(2.0)
	ok(s.state == &"idle", "18 m off in the dark: nothing")
	host.player_bleeding = true
	step(2.0)
	ok(s.state == &"investigate", "bleeding draws it in (%s)" % s.state)
	fresh()
	host.daylight = 0.0; host.hours = 1.0; host.player_visibility = 0.3
	var q = zs.spawn(C.x + 14.0, C.z, 0, 0, {"decay": 0.5})
	q.yaw = PI * 0.5; q.pause = 999.0
	step(1.5)
	host.player_noise = 18.0                  # running footsteps
	step(1.5)
	ok(q.state == &"investigate" or q.state == &"chase", "footsteps (player_noise 18) at 14 m are heard (%s)" % q.state)

func t_day_night_and_save() -> void:
	print("\n-- day and night, packs, save / load")
	fresh()
	_set_player(at(-110, 0))
	for i in 140:
		var a := i * 2.399; var r := 4.0 + sqrt(i) * 3.4
		zs.spawn(C.x + cos(a) * r, C.z + sin(a) * r, 1 if i % 12 == 0 else 0, 0, {"decay": 0.3 + 0.5 * fmod(i * 0.37, 1.0)})
	step(150.0, 1.0 / 20.0)
	var day_states := states()
	var lying := 0
	for z in zs.list:
		if z.state == &"dormant" and z.lying: lying += 1
	print("    noon:     ", day_states, "  (lying ", lying, ")")
	ok(int(day_states.get(&"dormant", 0)) > 40, "by day many go dormant: %d of 140" % int(day_states.get(&"dormant", 0)))
	ok(lying > 3, "some of them lie down among the dead (%d)" % lying)
	var cl: int = 0
	for z in zs.list:
		if z.state == &"dormant" and z.lying and z.body >= 0 and (String(zs.bodies.current_clip(z.body)).begins_with("drop") or String(zs.bodies.current_clip(z.body)).begins_with("lie")): cl += 1
	ok(cl == lying, "all the lying ones use the lying clips (%d)" % cl)
	# ---- save here, with a mixed crowd
	var special = zs.list[3]; special.crawler = true; special.arm_loss_r = true; zs._apply_decay(special)
	var turned = zs.spawn_turned({"pos": at(50, 50), "yaw": 0.0, "seed": 9.5, "look": {"shirt": 4, "hair": 2}, "name": "Ana"})
	var rows: Array = zs.to_rows()
	var json := JSON.stringify(rows)
	var before: Array = []
	for z in zs.list:
		before.append([z.type, snappedf(z.decay, 0.001), z.crawler, z.arm_loss_l, z.arm_loss_r, z.turned, z.name, z.state == &"dormant", z.state == &"dormant" and z.lying, round(z.hp), round(z.max_hp), snappedf(z.run_speed, 0.05), snappedf(z.pos.x, 0.1), snappedf(z.pos.z, 0.1), snappedf(z.scale, 0.001), z.gait])
	# ---- night
	host.daylight = 0.0; host.hours = 23.0
	var p0: Array = []
	for z in zs.list: p0.append(z.pos)
	step(120.0, 1.0 / 20.0)
	var night_states := states()
	print("    midnight: ", night_states)
	ok(int(night_states.get(&"dormant", 0)) < 6, "after dark they wake: %d dormant" % int(night_states.get(&"dormant", 0)))
	var moved := 0.0
	for i in p0.size(): moved += zs.list[i].pos.distance_to(p0[i])
	ok(moved / p0.size() > 2.0, "and roam (mean %.1f m in two minutes)" % (moved / p0.size()))
	ok(ev.player.is_empty() and ev.human.is_empty(), "nobody was attacked meanwhile")
	var bad := 0
	for z in zs.list:
		if W.resolve(z.pos, 0.02, 1.7).pos.distance_to(z.pos) > 0.01 or absf(z.pos.y - W.ground_y(z.pos.x, z.pos.z, z.pos.y)) > 0.4:
			bad += 1
			print("      bad: ", z.pos, " state ", z.state, " moving ", z.moving, " resolve moves it ", W.resolve(z.pos, 0.1, 1.7).pos.distance_to(z.pos), " ground ", W.ground_y(z.pos.x, z.pos.z, z.pos.y), " z.ground ", z.ground, " nav ", W.nav_at(z.pos.x, z.pos.z), " vel ", z.vel, " push ", z.push)
	ok(bad == 0, "no zombie ended up inside something solid or off the ground (%d)" % bad)
	# ---- load
	print("    save: %d rows, %d bytes of JSON; first row %s" % [rows.size(), json.length(), JSON.stringify(rows[0])])
	zs.load_rows(JSON.parse_string(json))
	ok(zs.list.size() == before.size(), "load_rows restores all %d" % before.size())
	var diff := 0
	for i in mini(before.size(), zs.list.size()):
		var z = zs.list[i]
		var now := [z.type, snappedf(z.decay, 0.001), z.crawler, z.arm_loss_l, z.arm_loss_r, z.turned, z.name, z.state == &"dormant", z.state == &"dormant" and z.lying, round(z.hp), round(z.max_hp), snappedf(z.run_speed, 0.05), snappedf(z.pos.x, 0.1), snappedf(z.pos.z, 0.1), snappedf(z.scale, 0.001), z.gait]
		if str(now) != str(before[i]):
			diff += 1
			if diff < 4: print("      differs: ", before[i], " -> ", now)
	ok(diff == 0, "type, decay, limbs, crawler, turned, name, dormancy, hp, position and build all survive the round trip")
	var t2 = zs.list.back()
	ok(t2.turned and t2.name == "Ana" and int(t2.look.get("shirt", -1)) == 4, "the turned keep their name and look")
	step(1.0)
	ok(zs.bodies.body_count() == zs.list.filter(func(z): return z.body >= 0).size(), "bodies and zombies agree after the reload (%d)" % zs.bodies.body_count())
	# the web game's old saves
	zs.load_rows([[C.x, C.z, 1, 55], [C.x + 3.0, C.z, 2, 400.0], [C.x + 6.0, C.z, 0, 9999]])
	ok(zs.list.size() == 3 and zs.list[0].type == 1 and zs.list[0].hp == 55.0 and zs.list[2].hp == zs.list[2].max_hp, "old [x, z, type, hp] rows load")

func t_freeze() -> void:
	print("\n-- far away")
	fresh()
	var z = zs.spawn(C.x + 5.0, C.z, 0, 0, {"decay": 0.5})
	z.state = &"dormant"
	step(0.5)
	ok(z.body >= 0 and not z.frozen, "near: it has a body")
	_set_player(at(-260, 0))
	step(2.0)
	ok(z.frozen and z.body < 0 and zs.bodies.body_count() == 0, "260 m away: frozen, no body")
	_set_player(at(-200, 0))
	step(3.0)
	ok(not z.frozen and z.body < 0, "200 m: thinking again, still not drawn")
	_set_player(at(-150, 0))
	step(3.0)
	ok(z.body >= 0, "150 m: drawn again")

func t_director() -> void:
	print("\n-- hordes and stragglers")
	fresh()
	zs.director_enabled = true
	_set_player(Vector3(0, W.ground_y(0, 0), 0))
	host.daylight = 0.0; host.hours = 23.0; host.day = 6
	for i in 30:
		zs.update(1.0 / 30.0, 30.0, host)
	ok(ev.horde.size() >= 1, "a horde sets out at night: %s" % str(ev.horde))
	var members := 0; var inv := 0; var far := 0
	for z in zs.list:
		if z.horde != 0:
			members += 1
			if z.state == &"investigate": inv += 1
			if Vector2(z.pos.x, z.pos.z).length() > 60.0: far += 1
	ok(members >= 18, "%d in it (day 6: 18 + 4 a day)" % members)
	ok(inv > members * 0.8, "they are walking toward their target (%d investigating)" % inv)
	var stragglers := 0
	for z in zs.list:
		if z.horde == 0: stragglers += 1
	ok(stragglers > 10, "%d stragglers have wandered in from the wilds meanwhile" % stragglers)
	var types := {}
	for z in zs.list: types[z.type] = int(types.get(z.type, 0)) + 1
	print("    population ", zs.list.size(), ", by type ", types)
	# by day with no heat: no horde
	fresh()
	zs.director_enabled = true
	_set_player(Vector3(0, W.ground_y(0, 0), 0))
	for i in 30:
		zs.update(1.0 / 30.0, 30.0, host)
	ok(ev.horde.is_empty(), "no horde by day with a quiet base")
	# a horde reaches where it is going
	fresh()
	_set_player(at(-90, 0))
	host.daylight = 0.0; host.hours = 23.0
	var h = zs.spawn_horde(C.x + 30.0, C.z, 20, at(0, 0))
	var t := until(func():
		var n := 0
		for z in zs.list:
			if Vector2(z.pos.x - C.x, z.pos.z - C.z).length() < 14.0: n += 1
		return n >= 14, 90.0, 1.0 / 30.0)
	ok(h.members >= 18 and t > 0.0, "a horde of %d walks 30 m to its target in %.0f s" % [h.members, t])

func t_populate() -> void:
	print("\n-- the starting population (real town data)")
	fresh()
	_set_player(Vector3(W.info.spawn.x, 0, W.info.spawn.z))
	var t0 := Time.get_ticks_msec()
	zs.populate()
	var ms := Time.get_ticks_msec() - t0
	var n: int = zs.list.size()
	var types := {}; var stage := {}; var solid := 0; var upstairs := 0; var near_spawn := 0; var in_town := 0
	var town: Dictionary = W.info.town
	for z in zs.list:
		types[z.type] = int(types.get(z.type, 0)) + 1
		stage[zs.stage(z)] = int(stage.get(zs.stage(z), 0)) + 1
		if W.nav_at(z.pos.x, z.pos.z) != 0: solid += 1
		if z.pos.y > W.height_at(z.pos.x, z.pos.z) + 1.5: upstairs += 1
		if Vector2(z.pos.x - W.info.spawn.x, z.pos.z - W.info.spawn.z).length() < 55.0: near_spawn += 1
		if z.pos.x > town.x0 and z.pos.x < town.x1 and z.pos.z > town.z0 and z.pos.z < town.z1: in_town += 1
	print("    %d zombies in %d ms: types %s, stages %s, %d in town, %d on upper floors" % [n, ms, types, stage, in_town, upstairs])
	ok(n > 220 and n < 420, "population %d" % n)
	ok(solid == 0, "none standing in a wall (%d)" % solid)
	ok(near_spawn == 0, "none within 55 m of where the player starts (%d)" % near_spawn)
	ok(in_town > n / 2, "most are in the town")
	# the whole world ticking for a minute with the player at the start
	_set_player(Vector3(W.info.spawn.x, W.ground_y(W.info.spawn.x, W.info.spawn.z), W.info.spawn.z))
	step(60.0, 1.0 / 30.0)
	var active := 0
	for z in zs.list:
		if not z.frozen: active += 1
	print("    after a minute at the start: ", states(), "; ", active, " not frozen, ", zs.bodies.body_count(), " bodies")
	ok(ev.player.is_empty(), "the player is left alone at the cabin for the first minute")
	var rows: Array = zs.to_rows()
	zs.load_rows(JSON.parse_string(JSON.stringify(rows)))
	ok(zs.list.size() == rows.size(), "the whole population saves and loads (%d rows, %d KB)" % [rows.size(), JSON.stringify(rows).length() / 1024])

func t_types() -> void:
	print("\n-- strains by day")
	fresh()
	for day in [1, 4, 10, 20]:
		var c := [0, 0, 0, 0, 0, 0]
		for i in 4000: c[zs.pick_type(day)] += 1
		print("    day %2d: walker %d  runner %d  brute %d  howler %d  bloat %d  breaker %d  (of 4000)" % [day, c[0], c[1], c[2], c[3], c[4], c[5]])
		if day == 1: ok(c[3] == 0 and c[4] == 0 and c[5] == 0 and c[0] > 3000, "day 1: walkers, a few runners and brutes only")
		if day == 4: ok(c[3] > 0 and c[4] == 0 and c[5] == 0, "day 4: howlers have appeared")
		if day == 10: ok(c[3] > 0 and c[4] > 0 and c[5] > 0, "day 10: all six strains")
	var s = zs.spawn(C.x, C.z, 0, 0, {"decay": 0.02}); var hk = zs.spawn(C.x + 3.0, C.z, 0, 0, {"decay": 0.95})
	ok(s.run_speed > hk.run_speed * 1.8 and s.max_hp > hk.max_hp * 1.5, "fresh vs husk: run %.1f vs %.1f m/s, hp %.0f vs %.0f" % [s.run_speed, hk.run_speed, s.max_hp, hk.max_hp])
	# rot over the days
	_set_player(at(-100, 0))
	var d0: float = s.decay
	for i in 600:
		zs.update(1.0 / 30.0, 24.0, host)            # ten game days
	ok(s.decay > d0 + 0.3 and zs.stage(s) != "fresh", "ten days on, the fresh one has rotted: decay %.2f -> %.2f (%s)" % [d0, s.decay, zs.stage(s)])
