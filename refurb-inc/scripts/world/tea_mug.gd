class_name TeaMug
extends Node3D
## A Refurb Inc. mug: open-topped body (you can see in), a slogan, a handle, and real liquid inside that
## rises when poured, steeps from water to tea, goes milky, steams while hot and drops as you drink.
## Used both as the world item (origin at the base) and for the first-person drink (origin at the middle).

const COLOURS := [Color(0.96, 0.95, 0.93), Color(0.13, 0.2, 0.42), Color(0.16, 0.36, 0.22), Color(0.88, 0.66, 0.16),
	Color(0.72, 0.14, 0.12), Color(0.1, 0.5, 0.52), Color(0.9, 0.55, 0.65), Color(0.12, 0.12, 0.13)]
const SLOGANS := ["REFURB INC.", "WORLD'S OKAYEST\nTECHNICIAN", "IT'S NOT A BUG", "KEEP CALM\nAND RE-IMAGE",
	"I SURVIVED THE\nBIOS UPDATE", "GRADE A\nHUMAN", "HAVE YOU TRIED\nTURNING IT OFF\nAND ON AGAIN?", "TEA > COFFEE"]
const WATER := Color(0.62, 0.7, 0.76)
const TEA := Color(0.29, 0.13, 0.045)
const MILKY := Color(0.64, 0.43, 0.25)
const H := 0.1
const R_TOP := 0.043
const R_BOT := 0.038

var liquid: MeshInstance3D
var _liquid_mat: StandardMaterial3D
var steam: GPUParticles3D
var bag: Node3D
var _base_y := 0.0
var level_liquid := false     # first-person: the brew's surface stays level as you tip the mug


static func make(colour: int, slogan: int, centred: bool = false) -> TeaMug:
	var m := TeaMug.new()
	m._build(colour, slogan, centred)
	return m


func _build(colour: int, slogan: int, centred: bool) -> void:
	name = "TeaMug"
	_base_y = -H * 0.5 if centred else 0.0
	var col: Color = COLOURS[clampi(colour, 0, COLOURS.size() - 1)]
	var glaze := Mats.color(col, 0.22)
	glaze.cull_mode = BaseMaterial3D.CULL_DISABLED     # open top: the inside of the wall shows
	var body := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = R_TOP
	cm.bottom_radius = R_BOT
	cm.height = H
	cm.radial_segments = 28
	cm.cap_top = false
	body.mesh = cm
	body.position.y = _base_y + H * 0.5
	body.material_override = glaze
	add_child(body)
	# A thin rim ring so the open top reads as a lip, not a cut.
	var rim := MeshInstance3D.new()
	var tm := TorusMesh.new()
	tm.inner_radius = R_TOP - 0.0035
	tm.outer_radius = R_TOP + 0.0005
	tm.rings = 28
	tm.ring_segments = 6
	rim.mesh = tm
	rim.position.y = _base_y + H
	rim.material_override = glaze
	add_child(rim)
	var handle := MeshInstance3D.new()
	var hm := TorusMesh.new()
	hm.inner_radius = 0.017
	hm.outer_radius = 0.026
	hm.rings = 16
	hm.ring_segments = 8
	handle.mesh = hm
	handle.rotation_degrees = Vector3(90, 0, 0)
	handle.position = Vector3(R_TOP + 0.004, _base_y + H * 0.5, 0)
	handle.material_override = glaze
	add_child(handle)
	var dark := col.get_luminance() < 0.45
	var logo := Label3D.new()
	logo.text = SLOGANS[clampi(slogan, 0, SLOGANS.size() - 1)]
	logo.font_size = 18
	logo.pixel_size = 0.00042
	logo.outline_size = 0
	logo.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	logo.modulate = Color(0.95, 0.95, 0.95) if dark else Color(0.12, 0.12, 0.14)
	logo.position = Vector3(0, _base_y + H * 0.5, R_TOP - 0.0015)
	logo.rotation_degrees.x = -3.0
	add_child(logo)
	# The brew.
	liquid = MeshInstance3D.new()
	var lc := CylinderMesh.new()
	lc.top_radius = R_TOP - 0.003
	lc.bottom_radius = R_TOP - 0.003
	lc.height = 0.002
	lc.radial_segments = 24
	liquid.mesh = lc
	_liquid_mat = StandardMaterial3D.new()
	_liquid_mat.albedo_color = TEA
	_liquid_mat.roughness = 0.06
	_liquid_mat.metallic_specular = 0.8
	liquid.material_override = _liquid_mat
	liquid.visible = false
	liquid.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(liquid)
	_build_bag()
	_build_steam()


func _build_bag() -> void:
	# Teabag string over the rim with the paper tag hanging outside — while it's steeping.
	bag = Node3D.new()
	add_child(bag)
	var string := MeshInstance3D.new()
	var sm := CylinderMesh.new()
	sm.top_radius = 0.0007
	sm.bottom_radius = 0.0007
	sm.height = 0.07
	string.mesh = sm
	string.position = Vector3(-R_TOP - 0.001, _base_y + H - 0.03, 0.012)
	string.material_override = Mats.color(Color(0.95, 0.95, 0.9), 0.9)
	bag.add_child(string)
	var tag := MeshInstance3D.new()
	var tb := BoxMesh.new()
	tb.size = Vector3(0.002, 0.022, 0.018)
	tag.mesh = tb
	tag.position = Vector3(-R_TOP - 0.003, _base_y + H - 0.075, 0.012)
	tag.material_override = Mats.color(Color(0.9, 0.35, 0.12), 0.8)
	bag.add_child(tag)
	bag.visible = false


func _build_steam() -> void:
	steam = GPUParticles3D.new()
	steam.amount = 12
	steam.lifetime = 1.6
	steam.local_coords = false
	steam.emitting = false
	steam.position.y = _base_y + H
	var pm := ParticleProcessMaterial.new()
	pm.direction = Vector3(0, 1, 0)
	pm.spread = 12.0
	pm.initial_velocity_min = 0.03
	pm.initial_velocity_max = 0.07
	pm.gravity = Vector3(0, 0.02, 0)
	pm.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_SPHERE
	pm.emission_sphere_radius = 0.02
	pm.scale_min = 0.6
	pm.scale_max = 1.2
	var grad := Gradient.new()
	grad.set_color(0, Color(1, 1, 1, 0.16))
	grad.set_color(1, Color(1, 1, 1, 0.0))
	var gt := GradientTexture1D.new()
	gt.gradient = grad
	pm.color_ramp = gt
	steam.process_material = pm
	var quad := QuadMesh.new()
	quad.size = Vector2(0.035, 0.035)
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	mat.vertex_color_use_as_albedo = true
	mat.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	var puff := GradientTexture2D.new()
	puff.fill = GradientTexture2D.FILL_RADIAL
	puff.fill_from = Vector2(0.5, 0.5)
	puff.fill_to = Vector2(0.5, 0.0)
	var pg := Gradient.new()
	pg.set_color(0, Color(1, 1, 1, 1))
	pg.set_color(1, Color(1, 1, 1, 0))
	puff.gradient = pg
	puff.width = 64
	puff.height = 64
	mat.albedo_texture = puff
	quad.material = mat
	steam.draw_pass_1 = quad
	add_child(steam)


func show_state(fill: float, colour: Color, hot: bool, steeping: bool) -> void:
	## fill 0..1 of the mug's height (a full brew is ~0.86).
	liquid.visible = fill > 0.015
	if liquid.visible:
		liquid.position.y = _base_y + 0.006 + fill * (H - 0.012)
		var r := lerpf(R_BOT, R_TOP, fill) - 0.003
		(liquid.mesh as CylinderMesh).top_radius = r
		(liquid.mesh as CylinderMesh).bottom_radius = r
		if _liquid_mat.albedo_color != colour:
			_liquid_mat.albedo_color = colour
		if level_liquid and liquid.is_inside_tree():
			var o := liquid.global_position
			liquid.global_transform = Transform3D(Basis.IDENTITY, o)
	if steam.emitting != (hot and fill > 0.05):
		steam.emitting = hot and fill > 0.05
	bag.visible = steeping and fill > 0.05


func _process(_delta: float) -> void:
	if level_liquid and liquid.visible and liquid.is_inside_tree():
		liquid.global_transform = Transform3D(Basis.IDENTITY, liquid.global_position)


static func brew_colour(data: Dictionary, now: float) -> Color:
	## Water darkens to tea as it steeps (~5 s), then a splash of milk lightens it.
	if str(data.get("brew", "")) == "":
		return WATER
	var t := float(data.get("t", now))
	var c := WATER.lerp(TEA, clampf((now - t - 0.4) / 5.0, 0.0, 1.0))
	if data.get("milk", false):
		c = c.lerp(MILKY, clampf((now - float(data.get("mt", now))) / 0.9, 0.0, 1.0))
	return c


static func shown_fill(data: Dictionary, now: float) -> float:
	## The level as it's poured (rises over ~0.55 s from the moment the kettle reaches this mug).
	var fill := float(data.get("fill", 0.0))
	if data.has("t") and fill > 0.0 and not data.get("drunk", false):
		fill *= clampf((now - float(data.t)) / 0.55, 0.0, 1.0)
	if data.get("milk", false):
		fill = minf(fill + 0.07 * clampf((now - float(data.get("mt", now))) / 0.9, 0.0, 1.0), 0.95)
	return fill
