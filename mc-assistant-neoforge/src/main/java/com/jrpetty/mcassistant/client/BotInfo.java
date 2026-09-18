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
                      int[] deeds, @Nullable int[] zone, int[] wages,
                      String trait, String traitBlurb, int teamwork,
                      int diet, String lastMeal,
                      @Nullable int[] deathSpot, int perk, String patchName,
                      boolean quiet, int ripe, boolean quarry, int stance, int carry) {

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
        // iron, gold, diamonds paid, then ticks until the next payday.
        int[] wages = new int[]{ 0, 0, 0, 0 };
        if (f.length > 5 && !f[5].isEmpty()) {
            String[] w = f[5].split(",");
            for (int i = 0; i < 4 && i < w.length; i++) wages[i] = parse(w[i]);
        }
        // The quirk it turned up with, and how well it knows its crewmates.
        String trait = "", blurb = "";
        int teamwork = 0;
        if (f.length > 6 && !f[6].isEmpty()) {
            // Limit of 3: the blurb is last precisely because it contains commas.
            String[] t = f[6].split(",", 3);
            if (t.length > 0) trait = t[0];
            if (t.length > 1) teamwork = parse(t[1]);
            if (t.length > 2) blurb = t[2];
        }
        // What it last ate, and the pace that bought.
        int diet = 100;
        String meal = "";
        if (f.length > 7 && !f[7].isEmpty()) {
            String[] d = f[7].split(",", 2);
            if (d.length > 0) diet = parse(d[0]);
            if (d.length > 1) meal = d[1];
        }
        // Where it last died (x, y, z, age in ticks) — present only while the
        // server still considers the grief fresh — and the level-30 perk.
        int[] death = null;
        if (f.length > 8 && !f[8].isEmpty()) {
            String[] ds = f[8].split(",");
            if (ds.length == 4) {
                death = new int[]{ parse(ds[0]), parse(ds[1]), parse(ds[2]), parse(ds[3]) };
            }
        }
        int perk = f.length > 9 ? parse(f[9]) : 0;
        String patchName = f.length > 10 ? f[10] : "";
        boolean quiet = false, quarry = false;
        int ripe = 0, stance = 0, carry = 24;
        if (f.length > 11 && !f[11].isEmpty()) {
            String[] qr = f[11].split(",");
            if (qr.length > 0) quiet = "1".equals(qr[0]);
            if (qr.length > 1) ripe = parse(qr[1]);
            if (qr.length > 2) quarry = "1".equals(qr[2]);
            if (qr.length > 3) stance = parse(qr[3]);
            if (qr.length > 4) carry = parse(qr[4]);
        }
        return new BotInfo(branch, days, top, deeds, zone, wages, trait, blurb, teamwork,
            diet <= 0 ? 100 : diet, meal, death, perk, patchName, quiet, ripe, quarry, stance, carry);
    }

    /** How full the pack gets before a stash run, as its button label. */
    public String carryLabel() {
        return carry == 0 ? "Full pack" : "Bank " + carry;
    }

    /** The guard's combat stance, as its button label. */
    public String stanceLabel() {
        return AssistantEntity.Stance.byOrdinal(stance).label;
    }

    /** The perk's display name, or empty while undecided. */
    public String perkLabel() {
        AssistantEntity.Perk[] all = AssistantEntity.Perk.values();
        return perk > 0 && perk < all.length ? all[perk].label : "";
    }

    /** How the diet reads on screen: the pace, and what bought it. */
    public String dietLine() {
        return AssistantEntity.foodLabel(diet) + " — working at " + diet + "%"
            + (lastMeal.isEmpty() ? "" : ", last ate " + lastMeal);
    }

    /** Green when well fed, red when it is living on scraps. */
    public int dietColour() {
        if (diet >= 95) return Ui.GOOD;
        if (diet >= 70) return Ui.INK;
        if (diet >= 45) return Ui.WARN;
        return Ui.BAD;
    }

    public int ironPaid()    { return wages[0]; }
    public int goldPaid()    { return wages[1]; }
    public int diamondPaid() { return wages[2]; }
    public int wageDueTicks() { return wages[3]; }

    /** Has it been paid anything at all? */
    public boolean hasWages() {
        return wages[0] > 0 || wages[1] > 0 || wages[2] > 0;
    }

    /** What it is owed, in words. */
    public String wageStatus() {
        int due = wages[3];
        if (due <= 0) return "wage overdue";
        int mins = due / 1200;
        return mins <= 0 ? "payday now" : "paid for " + mins + " min";
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
