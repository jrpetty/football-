class_name Fx
extends Node3D
## Small one-shot particle bursts: blood, dust, sparks. Emitters are pooled and reused.

var _pools: Dictionary = {}
var _next: Dictionary = {}

func _make(col_a: Color, col_b: Color, size: float, speed: float, grav: float, life: float, n: int, spread: float) -> CPUParticles3D:
	var p := CPUParticles3D.new()
	p.emitting = false; p.one_shot = true; p.explosiveness = 0.95; p.amount = n; p.lifetime = life
	p.direction = Vector3.UP; p.spread = spread
	p.initial_velocity_min = speed * 0.4; p.initial_velocity_max = speed
	p.gravity = Vector3(0, -grav, 0)
	p.scale_amount_min = size * 0.5; p.scale_amount_max = size
	var grad := Gradient.new(); grad.set_color(0, col_a); grad.set_color(1, Color(col_b.r, col_b.g, col_b.b, 0.0))
	p.color_ramp = grad
	var q := QuadMesh.new(); q.size = Vector2.ONE
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED; m.vertex_color_use_as_albedo = true
	m.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES; m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	q.material = m; p.mesh = q
	p.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(p)
	return p

func _emit(kind: String, at: Vector3, dir: Vector3) -> void:
	if not _pools.has(kind):
		var list: Array = []
		for i in 6:
			match kind:
				"blood": list.append(_make(Color(0.45, 0.02, 0.02), Color(0.2, 0.0, 0.0), 0.07, 3.2, 9.0, 0.6, 18, 38.0))
				"dust": list.append(_make(Color(0.5, 0.46, 0.38, 0.7), Color(0.5, 0.46, 0.38), 0.16, 1.2, 1.5, 0.8, 10, 60.0))
				_: list.append(_make(Color(1.0, 0.8, 0.4), Color(1.0, 0.4, 0.1), 0.03, 5.0, 9.0, 0.35, 10, 50.0))
		_pools[kind] = list; _next[kind] = 0
	var p: CPUParticles3D = _pools[kind][_next[kind]]
	_next[kind] = (int(_next[kind]) + 1) % 6
	p.global_position = at
	p.direction = dir.normalized() if dir.length() > 0.01 else Vector3.UP
	p.restart(); p.emitting = true

func blood(at: Vector3, dir: Vector3) -> void: _emit("blood", at, dir + Vector3(0, 0.4, 0))
func dust(at: Vector3) -> void: _emit("dust", at, Vector3.UP)
func sparks(at: Vector3, dir: Vector3) -> void: _emit("sparks", at, dir)

# ------------------------------------------------------------------ blood on the ground
var _pools_gnd: Array = []
var _pool_i := 0
var _pool_mat: Array = []
const MAX_POOLS := 48

static func _splat_texture(seed_v: int) -> ImageTexture:
	var n := 96
	var img := Image.create(n, n, false, Image.FORMAT_RGBA8)
	var rng := RandomNumberGenerator.new(); rng.seed = seed_v
	var blobs: Array = []
	for i in 9:
		var a := rng.randf() * TAU; var d := rng.randf() * 0.28
		blobs.append([0.5 + cos(a) * d, 0.5 + sin(a) * d, 0.06 + rng.randf() * (0.22 if i < 3 else 0.09)])
	for y in n:
		for x in n:
			var u := (x + 0.5) / n; var v := (y + 0.5) / n
			var f := 0.0
			for b in blobs:
				var dx: float = u - b[0]; var dy: float = v - b[1]
				f += b[2] * b[2] / (dx * dx + dy * dy + 0.0006)
			var a2 := smoothstep(1.0, 1.5, f)
			img.set_pixel(x, y, Color(0.16 + 0.05 * rng.randf(), 0.006, 0.006, a2 * 0.92))
	img.generate_mipmaps()
	return ImageTexture.create_from_image(img)

## a dark pool where something bled out; the oldest is reused when there are too many
func blood_pool(at: Vector3, size := 1.0) -> void:
	if _pool_mat.is_empty():
		for i in 4:
			var m := StandardMaterial3D.new()
			m.albedo_texture = _splat_texture(100 + i * 17)
			m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
			m.roughness = 0.25; m.metallic = 0.0; m.specular = 0.6
			m.cull_mode = BaseMaterial3D.CULL_DISABLED
			m.render_priority = 1
			_pool_mat.append(m)
	var mi: MeshInstance3D
	if _pools_gnd.size() < MAX_POOLS:
		mi = MeshInstance3D.new()
		var q := PlaneMesh.new(); q.size = Vector2.ONE
		mi.mesh = q; mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		add_child(mi); _pools_gnd.append(mi)
	else:
		mi = _pools_gnd[_pool_i]; _pool_i = (_pool_i + 1) % MAX_POOLS
	mi.material_override = _pool_mat[randi() % _pool_mat.size()]
	var y := WorldData.ground_y(at.x, at.z, at.y + 0.6)
	var n := Vector3.UP
	if WorldData.floor_at(at.x, at.z).is_empty() or absf(y - WorldData.height_at(at.x, at.z)) < 0.05:
		n = WorldData.normal_at(at.x, at.z)
	var x_axis := n.cross(Vector3(sin(randf() * TAU), 0, cos(randf() * TAU))).normalized()
	var z_axis := x_axis.cross(n).normalized()
	var sc := size * randf_range(0.8, 1.5)
	mi.global_transform = Transform3D(Basis(x_axis * sc, n, z_axis * sc), Vector3(at.x, y + 0.025, at.z))
	mi.visible = true
