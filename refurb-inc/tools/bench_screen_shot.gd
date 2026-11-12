extends Node
## Renders the test-bench screen for every kind of kit at a few moments (diagnostics, report, wipe, ready) into
## tools/out/bench_<model>_<n>.png — visual QA for bench_screen.gd without walking round a level.
## Run: Godot --path . res://tools/bench_screen_shot.tscn

const CASES := ["kestrel_swift_14", "corvex_rampart_2200", "halden_viewline_24", "linkwell_ls24p", "linkwell_air_ax3000",
	"corvex_dock_wd22", "brightline_slate_11", "kestrel_pulse_6"]


func _ready() -> void:
	DirAccess.make_dir_recursive_absolute("res://tools/out")
	Game.new_game(7)
	var s := Game.state
	s.register_station("shot", "bench")
	s.register_slot("shot:unit", ["unit", "unit_large"], "shot")
	s.register_slot("shot:usb", ["usb_test", "usb_image"], "shot")
	var usb_t := s.new_item(Item.KIND_USB_TEST)
	var usb_i := s.new_item(Item.KIND_USB_IMAGE)
	var vp := SubViewport.new()
	vp.size = Vector2i(640, 400)
	vp.disable_3d = true
	vp.render_target_update_mode = SubViewport.UPDATE_ALWAYS
	add_child(vp)
	var scr := BenchScreen.new()
	scr.station_id = "shot"
	scr.size = Vector2(640, 400)
	vp.add_child(scr)
	for model_id in CASES:
		var it := s.new_item(Item.KIND_UNIT)
		it.unit = Game.sim.lots.roll_unit(model_id, 0.5)
		s.set_loc(it, {"t": "slot", "slot": "shot:unit"})
		var u := it.unit
		# 1: diagnostics running, most of the inventory detected.
		s.set_loc(usb_t, {"t": "slot", "slot": "shot:usb"})
		s.stations.shot["job"] = {"kind": "test", "start": s.minute - 40.0, "dur": 60.0, "unit": it.id}
		await _save(vp, "bench_%s_1" % model_id)
		# 2: report.
		s.stations.shot["job"] = {}
		Game.sim._complete_test(it, 0)
		s.stations.shot["msg"] = "PASS" if not u.has_open_faults() else "FAULTS FOUND"
		await _save(vp, "bench_%s_2" % model_id)
		# 3: wipe / image running (kit with data only).
		if u.needs_wipe():
			for f in u.faults:
				f["fixed"] = true
			s.set_loc(usb_t, {"t": "gone"})
			s.set_loc(usb_i, {"t": "slot", "slot": "shot:usb"})
			s.stations.shot["job"] = {"kind": "image", "start": s.minute - 25.0, "dur": 40.0, "unit": it.id}
			await _save(vp, "bench_%s_3" % model_id)
			s.stations.shot["job"] = {}
			u.imaged = true
		# 4: ready.
		for f in u.faults:
			f["fixed"] = true
		await _save(vp, "bench_%s_4" % model_id)
		s.set_loc(it, {"t": "gone"})
		s.set_loc(usb_i, {"t": "gone"})
	get_tree().quit()


func _save(vp: SubViewport, n: String) -> void:
	for i in 4:
		await get_tree().process_frame
	await RenderingServer.frame_post_draw
	vp.get_texture().get_image().save_png("res://tools/out/%s.png" % n)
	print("saved ", n)
