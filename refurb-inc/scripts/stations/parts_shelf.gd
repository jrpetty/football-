class_name PartsShelf
extends StationBase
## Parts bins. Spare parts live here (company inventory). Repair benches draw from them.
## Bins show live counts per category; hover lists everything in stock.

const CATEGORIES := [
	["battery", "BATTERIES", Color(0.85, 0.2, 0.15)],
	["ram", "MEMORY", Color(0.15, 0.45, 0.85)],
	["storage", "SSD / DRIVES", Color(0.15, 0.6, 0.3)],
	["keys", "KEYCAPS", Color(0.9, 0.7, 0.1)],
	["fan", "FANS", Color(0.5, 0.5, 0.55)],
	["screen", "SCREENS", Color(0.5, 0.25, 0.7)],
	["hinge", "HINGES", Color(0.7, 0.4, 0.2)],
	["keyboard", "KB DECKS", Color(0.3, 0.3, 0.35)],
]

var _labels: Dictionary = {}


func _station_ready() -> void:
	# Bins: 4 per level, 2 levels used, laid out on the placeholder shelf (1.0 m wide).
	for i in CATEGORIES.size():
		var cat: Array = CATEGORIES[i]
		var bin := Node3D.new()
		bin.position = Vector3(-0.375 + (i % 4) * 0.25, 0.6 + (i / 4) * 0.48, 0.02)
		add_child(bin)
		Placeholders.box(bin, Vector3(0.22, 0.14, 0.32), Vector3(0, 0.07, 0), Mats.color(cat[2], 0.55))
		Placeholders.box(bin, Vector3(0.2, 0.012, 0.3), Vector3(0, 0.135, 0), Mats.color(cat[2].darkened(0.4), 0.7))
		var card := Placeholders.box(bin, Vector3(0.16, 0.06, 0.003), Vector3(0, 0.08, 0.162), Mats.color(Color(0.97, 0.97, 0.95), 0.8))
		var l := Placeholders.text(card, "%s\n0" % cat[1], Vector3(0, 0, 0.002), 28, Color(0.1, 0.1, 0.1), Vector3.ZERO, 0.0007)
		l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		_labels[cat[0]] = l


func _cares_about(kind: String, _id: Variant) -> bool:
	return kind == "company"


func _refresh() -> void:
	var counts := {}
	for p in Game.state.parts:
		var cat: String = Content.part(p).get("category", "")
		counts[cat] = int(counts.get(cat, 0)) + int(Game.state.parts[p])
	for cat in _labels:
		var name_txt: String = ""
		for c in CATEGORIES:
			if c[0] == cat:
				name_txt = c[1]
		_labels[cat].text = "%s\n%d" % [name_txt, int(counts.get(cat, 0))]
	super._refresh()


func hover_info(_player: Node) -> Dictionary:
	var lines: Array = []
	var ids: Array = Game.state.parts.keys()
	ids.sort()
	for p in ids:
		if int(Game.state.parts[p]) > 0:
			lines.append("%d× %s" % [int(Game.state.parts[p]), Content.part(p).get("name", p)])
	if lines.is_empty():
		lines.append("Empty. Order parts in the office PC Shop — they arrive with the courier.")
	return {"title": "Parts bins", "lines": lines, "prompt": ""}
