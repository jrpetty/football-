extends SceneTree
## Headless check that the GDScript port of the look / silhouette code matches the web version:
##   godot --headless --path zombie/godot -s res://characters/check_bodies.gd
## Compares Bodies.compute_def() against the reference bodies the exporter wrote into body.json.

const BodiesScript := preload("res://characters/bodies.gd")

func _init() -> void:
	var meta: Dictionary = JSON.parse_string(FileAccess.get_file_as_string("res://characters/data/body.json"))
	var worst := 0.0
	var bad := 0
	for t in meta.tests:
		var b = BodiesScript.Body.new()
		b.kind = int(t.kind); b.human = b.kind == 10; b.seed = float(t.seed)
		var base: Dictionary = BodiesScript.look_from_seed(b.seed)
		var look: Dictionary = t.look if t.look != null else {}
		base.merge(look, true)
		b.skin = int(base.skin); b.shirt = int(base.shirt); b.pants = int(base.pants); b.hair = int(base.hair)
		b.build = clampf(float(base.build), 0.7, 1.4); b.height = clampf(float(base.height), 0.8, 1.25); b.hair_style = int(base.hairStyle)
		var ho: float = BodiesScript.hash1(b.seed * 41.3)
		b.outfit = int(look.outfit) if look.has("outfit") else (1 if ho < 0.22 else 2 if ho < 0.32 else 3 if ho < 0.46 else 0)
		b.jacket = int(look.jacket) if look.has("jacket") else BodiesScript.J_COL[int(floor(BodiesScript.hash1(b.seed * 42.7) * 8.0)) % 8]
		b.gear = int(look.gear) if look.has("gear") else ((1 if BodiesScript.hash1(b.seed * 43.1) < 0.18 else 0) | (2 if BodiesScript.hash1(b.seed * 44.7) < 0.15 else 0))
		var st: Dictionary = t.state
		b.decay = float(st.get("decay", 0.0)); b.sick = float(st.get("sick", 0.0)); b.tool = int(st.get("tool", 0))
		b.arm_loss_l = bool(st.get("armLossL", false)); b.arm_loss_r = bool(st.get("armLossR", false))
		b.gait = BodiesScript.gait_for(b.kind, b.seed)
		BodiesScript.compute_def(b)
		var e := 0.0
		for i in 40:
			e = maxf(e, absf(b.look[i] - float(t.out[i])))
		for i in 54:
			e = maxf(e, absf(b.shape[i] - float(t.shape[i])))
		e = maxf(e, absf(b.height - float(t.height)))
		if b.kind != 10 and b.gait != int(t.gait): e = 9.0
		if e > 1e-4:
			bad += 1
			print("MISMATCH kind=", b.kind, " seed=", b.seed, " err=", e)
			for i in 40:
				if absf(b.look[i] - float(t.out[i])) > 1e-4: print("   look[", i, "] ", b.look[i], " vs ", t.out[i])
			for i in 54:
				if absf(b.shape[i] - float(t.shape[i])) > 1e-4: print("   shape[", i, "] ", b.shape[i], " vs ", t.shape[i])
		worst = maxf(worst, e)
	print("check_bodies: ", meta.tests.size(), " reference bodies, ", bad, " mismatched, worst error ", worst)
	quit(1 if bad > 0 else 0)
