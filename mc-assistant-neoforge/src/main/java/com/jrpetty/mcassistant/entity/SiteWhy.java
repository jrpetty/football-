package com.jrpetty.mcassistant.entity;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ32b] Why a building's look for a lot found none: every lot of the plan passed over, counted by the reason it was
 * passed over (Villages.siteFor). The status's "no lot" line counted only the lots turned down for wet, cliff or height
 * ground, and a Diamond Age town's lighthouse and chapel read "no lot, turned down: wet 0, cliff 0, too high or low 0" for
 * days with every lot passed over for some other reason nobody could see. The last look's reasons are kept for each
 * building (the tests, the status); with {@code -Dmcassistant.sitewhy=true} each look that finds nothing is logged
 * ("[MCA-SITEWHY]").
 */
public final class SiteWhy {

    private SiteWhy() {}

    static final boolean LOG = Boolean.getBoolean("mcassistant.sitewhy");

    /** The look under way (the server's one thread looks for one lot at a time). */
    private static Map<String, Integer> now = new TreeMap<>();
    /** Each town's last look that found nothing, by building. */
    private static final Map<UUID, Map<String, String>> LAST = new ConcurrentHashMap<>();
    /** Towns whose walk has been put in the log once (sitewhy). */
    private static final java.util.Set<UUID> WALKED = ConcurrentHashMap.newKeySet();

    public static void resetForTests() {
        LAST.clear();
        now = new TreeMap<>();
    }

    /** [econ32d] Is the lot being looked at big enough for the building? Its reasons are counted again under "big enough". */
    private static boolean big;
    private static Map<String, Integer> bigNow = new TreeMap<>();

    /** A look begins. */
    static void begin() {
        now = new TreeMap<>();
        bigNow = new TreeMap<>();
        big = false;
    }

    /** [econ32d] The next lot looked at, and whether the building fits it (Villages.siteFor). */
    static void lot(boolean fits) {
        big = fits;
    }

    /** A lot passed over, for this reason. */
    static void count(String why) {
        now.merge(why, 1, Integer::sum);
        if (big) bigNow.merge(why, 1, Integer::sum);
    }

    /** The look is over: kept (and logged) if it found nothing. */
    static void end(UUID village, String project, boolean found, int candidates) {
        String line = "of " + candidates + " lots: " + now + (bigNow.isEmpty() ? "" : "; of the lots big enough for it: " + bigNow);
        if (found) {
            Map<String, String> m = LAST.get(village);
            if (m != null) m.remove(project);
            return;
        }
        LAST.computeIfAbsent(village, k -> new ConcurrentHashMap<>()).put(project, line);
        if (LOG) com.mojang.logging.LogUtils.getLogger().info("[MCA-SITEWHY] {}: no lot for the {} {}", Villages.name(village), project, line);
        if (LOG && now.containsKey("street not walked") && WALKED.add(village)) {
            net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            Villages.Village v = Villages.get(village);
            if (server != null && v != null) com.mojang.logging.LogUtils.getLogger().info("[MCA-SITEWHY] {}: {}", Villages.name(village),
                CityPlan.walkWords(server.overworld(), v));
        }
    }

    /** Why the last look for this building found nothing, or null. */
    public static String last(UUID village, String project) {
        Map<String, String> m = LAST.get(village);
        return m == null ? null : m.get(project);
    }
}
