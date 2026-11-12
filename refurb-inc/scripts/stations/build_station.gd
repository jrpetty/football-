class_name BuildStation
extends StationBase
## Server build bench: custom server builds for business buyers, and RAM/SSD upgrades like the repair bench.
##   • Put a server on it — from stock (it's reserved for the earliest-due build that needs that chassis) — or hold E
##     on the empty bench to unbox a barebones chassis from the parts bins.
##   • Hold E on the server: each hold fits the next part from the bins (CPUs, memory, drives, RAID card, PSUs).
##     The bench monitor ticks off the customer's spec, line by line, until it's all green.
##   • Then test it and wipe & image it as usual — once it passes it gets its order and is ready to pack.
## A unit with no build (any laptop, desktop or server) can have RAM / storage upgrades fitted instead ([R] picks).

var _vp: SubViewport
var _screen: BuildScreen
var _status: Label3D
var _lamp: SpotLight3D
var _loop: AudioStreamPlayer3D
var _opt := 0                 # this PC's chosen upgrade option ([R] cycles it)
var _upgrade_key := ""
var _redraw_t := 0.0


func _station_ready() -> void:
	if station_type == "":
		station_type = "build"
	_vp = SubViewport.new()
	_vp.size = Vector2i(640, 400)
	_vp.render_target_update_mode = SubViewport.UPDATE_ONCE
	_vp.disable_3d = true
	add_child(_vp)
	_screen = BuildScreen.new()
	_screen.station_id = station_id
	_screen.size = Vector2(640, 400)
	_vp.add_child(_screen)
	_status = Label3D.new()
	_status.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	_status.font_size = 64
	_status.pixel_size = 0.0022
	_status.outline_size = 12
	_status.position = Vector3(-0.2, 2.05, 0)
	add_child(_status)
	_lamp = SpotLight3D.new()
	_lamp.position = Vector3(-0.55, 1.42, 0.08)
	_lamp.rotation_degrees = Vector3(-80, 0, 0)
	_lamp.light_color = Color(0.85, 0.92, 1.0)
	_lamp.light_energy = 2.2
	_lamp.spot_range = 1.4
	_lamp.spot_angle = 38
	_lamp.shadow_enabled = false
	add_child(_lamp)
	var sign := Placeholders.text(self, "SERVER BUILD", Vector3(-0.2, 1.62, -0.345), 46, Color(0.75, 0.88, 1.0), Vector3.ZERO, 0.0016)
	sign.outline_size = 8
	sign.outline_modulate = Color(0.05, 0.12, 0.3)
	_loop = Sfx.make_loop("repair_loop", self, -8.0)
	_loop.position = Vector3(0, 1.0, 0.2)
	if model:
		model.rebuilt.connect(_apply_monitor)
	_apply_monitor()


func _apply_monitor() -> void:
	if model == null:
		return
	var scr := model.find_part("Screen") as MeshInstance3D
	if scr:
		scr.material_override = Mats.screen_texture(_vp.get_texture(), 1.0)


func _process(delta: float) -> void:
	## Redraw the bench monitor a few times a second when someone is near, rarely otherwise.
	_redraw_t -= delta
	if _redraw_t > 0.0:
		return
	var cam := get_viewport().get_camera_3d()
	var near := cam != null and cam.global_position.distance_to(global_position) < 12.0
	_redraw_t = 0.25 if near else 2.0
	if near:
		_vp.render_target_update_mode = SubViewport.UPDATE_ONCE


func _cares_about(kind: String, id: Variant) -> bool:
	return kind == "company" or super._cares_about(kind, id)     # parts bins changed → the checklist/prompt may change


func _on_state_changed(kind: String, id: Variant) -> void:
	if kind == "contract":
		_refresh()
		return
	super._on_state_changed(kind, id)


func _refresh() -> void:
	_vp.render_target_update_mode = SubViewport.UPDATE_ONCE
	_update_status()
	super._refresh()


func contract_for(u: UnitData) -> Dictionary:
	if u == null or u.contract == 0:
		return {}
	var c: Dictionary = Game.state.contracts.get(u.contract, {})
	return c if c.get("kind", "") == "build" and c.get("status", "") == "active" else {}


func _update_status() -> void:
	var it := slot_item("unit")
	if it == null or it.unit == null:
		_status.text = ""
		return
	var c := contract_for(it.unit)
	if c.is_empty():
		_status.text = ""
		return
	var pr := ServerBuild.progress(it.unit, c.spec)
	if int(pr[0]) >= int(pr[1]):
		var u := it.unit
		_status.text = "✓ BUILT" + ("  ·  TEST IT" if not u.tested else ("  ·  IMAGE IT" if not u.imaged else ""))
		_status.modulate = Color(0.4, 1.0, 0.55)
	else:
		_status.text = "BUILD %d/%d" % [int(pr[0]), int(pr[1])]
		_status.modulate = Color(0.55, 0.8, 1.0)


func _peer(player: Node) -> int:
	return player.peer_id if player and "peer_id" in player else Net.local_peer()


# ---------------------------------------------------------------- interaction

func hold_action(player: Node, it: Item) -> Dictionary:
	if it.kind != Item.KIND_UNIT or it.unit == null:
		return {}
	var u := it.unit
	var peer := _peer(player)
	var c := contract_for(u)
	if not c.is_empty():
		var step := ServerBuild.next_step(Game.state, peer, u, c.spec)
		if step.has("act"):
			var pr := ServerBuild.progress(u, c.spec)
			return {"label": "Build: %s" % step.text, "secs": float(step.secs), "action": "build",
				"sub": "%s's %s · %d of %d spec lines done · parts from the bins" % [c.buyer, c.name, int(pr[0]), int(pr[1])]}
		if step.get("done", false):
			return {}
		return {"label": "", "blocked": str(step.get("blocked", ""))}
	var up := Upgrades.hold(Game.state, peer, u, _opt)
	if not up.is_empty():
		_upgrade_key = str(up.key)
		return up
	var why := Upgrades.blocked_reason(u)
	if why == "" and u.hw_type() == "server":
		why = "No custom build needs a %s right now (Business app)." % u.display_name()
	return {"label": "", "blocked": why} if why != "" else {}


func cycle_option() -> void:
	_opt += 1
	Sfx.play_ui("ui_click", -10.0)


func hold_progress(active: bool) -> void:
	if active and _loop.stream and not _loop.playing:
		_loop.play()
	elif not active:
		_loop.stop()


func hold_complete(_player: Node, action: String) -> void:
	match action:
		"build":
			Game.request("build", {"station": station_id, "act": "step"})
			Sfx.play_at("repair_done", global_position + Vector3(0, 1, 0))
		"chassis":
			Game.request("build", {"station": station_id, "act": "chassis"})
			Sfx.play_at("box_open", global_position + Vector3(0, 1, 0))
		"upgrade":
			Game.request("upgrade", {"station": station_id, "key": _upgrade_key})
			Sfx.play_at("repair_done", global_position + Vector3(0, 1, 0))


func extra_lines(it: Item) -> Array:
	var out: Array = []
	if it.unit == null:
		return out
	var c := contract_for(it.unit)
	if not c.is_empty():
		out.append("For %s — %s, due %s" % [c.buyer, c.name, GameState.format_minute(float(c.due))])
		if ServerBuild.complete(it.unit, c.spec):
			var u := it.unit
			out.append("Built to spec. Next: %s." % ("test it on a test bench" if not u.tested else ("wipe & image it" if not u.imaged else "pack it")))
	return out


func hover_info(player: Node) -> Dictionary:
	var info := super.hover_info(player)
	var it := slot_item("unit")
	if it:
		info.lines = ["On bench: " + it.display_name()]
		return info
	info.lines = ["Put a server here to build it to a customer's spec, or any unit for RAM/SSD upgrades."]
	var plan := ServerBuild.chassis_plan(Game.state)
	if not plan.is_empty():
		var c: Dictionary = Game.state.contracts.get(int(plan.get("contract", 0)), {})
		info["hold"] = {"label": "Unbox a barebones %s%s" % [Content.model_display_name(str(plan.model)), (" for %s" % c.buyer) if not c.is_empty() else ""],
			"secs": 1.6, "action": "chassis", "sub": "%d in the parts bins" % int(Game.state.parts.get(str(plan.part), 0))}
	else:
		var waiting := Game.state.contracts.values().filter(func(c): return c.get("kind", "") == "build" and c.get("status", "") == "active")
		if not waiting.is_empty():
			info.lines.append("Builds waiting: %d. Bring a matching server, or order a barebones chassis (PartsHub → Server build)." % waiting.size())
	return info
