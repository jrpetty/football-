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
