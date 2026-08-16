package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.AssistantEntity;

import javax.annotation.Nullable;

/**
 * The half of a specialist's state that only exists server-side, unpacked from
 * the one synched string it travels in. Everything the screens want to show
 * about a career — branch, service, the whole work record, the patch's
 * footprint — parsed once, in one place, rather than in each screen.
 */
public record BotInfo(String branch, int daysServed, String topDeed,
                      int[] deeds, @Nullable int[] zone) {

    /** "branch|days|topDeed|deedCsv|zoneCsv", written by publishJobState. */
    public static BotInfo of(AssistantEntity bot) {
        String[] f = bot.clientExtra().split("\\|", -1);
        String branch = f.length > 0 ? f[0] : "";
        int days = f.length > 1 ? parse(f[1]) : 0;
        String top = f.length > 2 ? f[2] : "";

        int[] deeds = new int[AssistantEntity.Deed.values().length];
        if (f.length > 3 && !f[3].isEmpty()) {
            for (String pair : f[3].split(",")) {
                int colon = pair.indexOf(':');
                if (colon <= 0) continue;
                int idx = parse(pair.substring(0, colon));
                if (idx >= 0 && idx < deeds.length) deeds[idx] = parse(pair.substring(colon + 1));
            }
        }

        int[] zone = null;
        if (f.length > 4 && !f[4].isEmpty()) {
            String[] c = f[4].split(",");
            if (c.length == 4) {
                zone = new int[]{ parse(c[0]), parse(c[1]), parse(c[2]), parse(c[3]) };
            }
        }
        return new BotInfo(branch, days, top, deeds, zone);
    }

    /** True once it has picked a trade to deepen. */
    public boolean hasBranch() {
        return !branch.isEmpty() && !branch.equals("no speciality");
    }

    /** Does its career show anything at all yet? */
    public boolean hasRecord() {
        for (int d : deeds) if (d > 0) return true;
        return false;
    }

    public int deed(AssistantEntity.Deed d) {
        return deeds[d.ordinal()];
    }

    /** The biggest single tally, for scaling the record bars. */
    public int busiest() {
        int max = 0;
        for (int d : deeds) max = Math.max(max, d);
        return max;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
