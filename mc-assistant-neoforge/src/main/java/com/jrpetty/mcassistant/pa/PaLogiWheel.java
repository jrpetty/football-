package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.pa.PaPings.Option;
import com.jrpetty.mcassistant.pa.PaPings.Sort;

import java.util.List;

/**
 * [pa36] The ping wheel for containers. A chest (barrel, shulker box, hopper, dispenser, dropper, its player's ender
 * chest): put stuff in (a pick-what screen of its pack and yours), take out, fill it with something, keep it stocked,
 * sort it, name it, empty it into storage, make it the drop-off. A furnace: fuel it, smelt its ores, collect the output.
 * A brewing stand: brew healing. A composter: fill it. Options with no words open a screen on the client (PaLogiClient);
 * the rest are said, read by the same reader as anything typed. Pure Java.
 */
public final class PaLogiWheel {

    private PaLogiWheel() {}

    /** Option ids that open a screen rather than say something. */
    public static boolean screen(String id) {
        return id.equals("lput") || id.equals("ltake") || id.equals("lfill") || id.equals("lstock") || id.equals("lname");
    }

    public static List<Option> chest() {
        return List.of(
            new Option("lput", "Put stuff in...", Sort.GATHER, ""),
            new Option("ltake", "Take out...", Sort.GATHER, ""),
            new Option("lfill", "Fill this with...", Sort.GATHER, ""),
            new Option("lstock", "Keep stocked with...", Sort.BUILD, ""),
            new Option("sort", "Sort", Sort.GATHER, "sort this chest"),
            new Option("lname", "Name this chest", Sort.BUILD, ""),
            new Option("lempty", "Empty into my storage", Sort.GATHER, "empty this chest into my storage"),
            new Option("ldrop", "Make this my drop-off", Sort.BUILD, "make this chest the drop off"));
    }

    public static List<Option> furnace() {
        return List.of(
            new Option("fuel", "Fuel it", Sort.GATHER, "fuel this furnace"),
            new Option("smelt", "Smelt my ores", Sort.GATHER, "smelt the ores in this furnace"),
            new Option("lcollect", "Collect output", Sort.GATHER, "collect the output from this furnace"),
            new Option("lkeepfuel", "Keep it fuelled", Sort.BUILD, "keep this furnace going"));
    }

    public static List<Option> brewing() {
        return List.of(new Option("lbrew", "Brew healing", Sort.BUILD, "brew healing potions"));
    }

    public static List<Option> composter() {
        return List.of(new Option("lcompost", "Fill", Sort.GATHER, "fill this composter"));
    }
}
