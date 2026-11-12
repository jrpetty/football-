class_name Looks
extends RefCounted
## Your character: colours, hi-vis, cap, height, big-head mode — and a photo face. Pick any picture (a mate,
## your nan, the cat) and it's cropped to an oval and stuck on your character's face like a paper mask.
## Saved in Settings + user://face.png; sent to everyone in co-op (Game.looks).

const FACE_PATH := "user://face.png"
const FACE_SIZE := 160
const SHIRTS := ["#3359a0", "#c0392b", "#27ae60", "#f39c12", "#8e44ad", "#16a085", "#2c3e50", "#e84393"]
const TROUSERS := ["#292b33", "#3d4b63", "#5b4632", "#6b6f75"]
const SKINS := ["#f2d3bd", "#dbb08f", "#c68e6a", "#a26a48", "#7b4a2e", "#4f2f1d"]
const HAIRS := ["#33241a", "#8c6233", "#141211", "#b39a73", "#c4462b", "#8a8a8a"]
const SHOES := ["#1a1a1c", "#5a3a22", "#e8e6e1", "#2c3e66"]
const HAIR_STYLES := ["short", "side", "long", "bun", "buzz", "curly", "bald"]


static func local_look() -> Dictionary:
	return {
		"shirt": str(Settings.get_value("char_shirt")), "trousers": str(Settings.get_value("char_trousers")),
		"skin": str(Settings.get_value("char_skin")), "hair": str(Settings.get_value("char_hair")),
		"hivis": bool(Settings.get_value("char_hivis")), "cap": bool(Settings.get_value("char_cap")),
		"height": float(Settings.get_value("char_height")), "bighead": bool(Settings.get_value("char_bighead")),
		"body": str(Settings.get_value("char_body")), "hairstyle": str(Settings.get_value("char_hairstyle")), "shoes": str(Settings.get_value("char_shoes")),
		"steamface": bool(Settings.get_value("char_steamface")),
	}


static func face_png() -> PackedByteArray:
	if not FileAccess.file_exists(FACE_PATH):
		return PackedByteArray()
	return FileAccess.get_file_as_bytes(FACE_PATH)


static func texture_from_png(png: PackedByteArray) -> Texture2D:
	if png.is_empty():
		return null
	var img := Image.new()
	if img.load_png_from_buffer(png) != OK:
		return null
	img.generate_mipmaps()
	return ImageTexture.create_from_image(img)


static func set_photo(path: String) -> String:
	## Load any picture, crop the middle square (slightly above centre, where faces usually are), shrink it,
	## and cut a soft oval. Returns "" or an error message.
	var img := Image.load_from_file(path)
	if img == null or img.is_empty():
		return "Couldn't read that picture."
	img = mask_face(img)
	if img.save_png(FACE_PATH) != OK:
		return "Couldn't save the face."
	return ""


static func mask_face(src: Image) -> Image:
	## Crop the middle square (slightly above centre for portraits), shrink, and cut a soft oval: a paper mask.
	var img := src.duplicate() as Image
	img.convert(Image.FORMAT_RGBA8)
	var side := int(mini(img.get_width(), img.get_height()) * 0.86)
	var x0 := (img.get_width() - side) / 2
	var y0 := maxi(0, (img.get_height() - side) / 2 - int(side * 0.06)) if img.get_height() > img.get_width() else (img.get_height() - side) / 2
	img = img.get_region(Rect2i(x0, y0, side, side))
	img.resize(FACE_SIZE, FACE_SIZE, Image.INTERPOLATE_LANCZOS)
	var c := FACE_SIZE * 0.5
	for y in FACE_SIZE:
		for x in FACE_SIZE:
			var d := Vector2((x - c) / (c * 0.86), (y - c) / (c * 0.98)).length()
			var a := clampf((1.0 - d) / 0.06, 0.0, 1.0)
			var px := img.get_pixel(x, y)
			px.a *= a
			img.set_pixel(x, y, px)
	return img


static func clear_photo() -> void:
	if FileAccess.file_exists(FACE_PATH):
		DirAccess.remove_absolute(ProjectSettings.globalize_path(FACE_PATH))


static func apply(rig: CharacterRig, look: Dictionary, face_png: PackedByteArray = PackedByteArray()) -> void:
	if look.is_empty():
		return
	rig.shirt = Color(str(look.get("shirt", "#3359a0")))
	rig.trousers = Color(str(look.get("trousers", "#292b33")))
	rig.skin = Color(str(look.get("skin", "#dbb08f")))
	rig.hair = Color(str(look.get("hair", "#33241a")))
	rig.hi_vis = bool(look.get("hivis", false))
	rig.cap = bool(look.get("cap", false))
	rig.height = clampf(float(look.get("height", 1.78)), 1.5, 2.0)
	rig.big_head = bool(look.get("bighead", false))
	rig.body = str(look.get("body", "m"))
	rig.hair_style = str(look.get("hairstyle", "short"))
	rig.shoes = Color(str(look.get("shoes", "#1a1a1c")))
	rig.rebuild()
	rig.set_face_texture(texture_from_png(face_png), not face_png.is_empty())
