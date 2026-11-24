package com.jrpetty.mcassistant.pa;

import java.util.ArrayList;
import java.util.List;

/**
 * [pa33d] Its ten levels: a real grind. The experience each level takes climbs steeply (about five thousand to reach
 * ten, many game days of steady use at Normal), what it is paid for its work is scaled by the difficulty and the
 * server's multiplier, and every level makes it better at what it does (quicker work and walking, a steadier aim,
 * harder hits, a bigger pack, more hearts) and opens new skills. Plain Java, so it is tested on its own.
 */
public final class PaLevels {

    private PaLevels() {}

    public static final int MAX = 10;

    /** The experience a level takes, all told, at a multiplier of one (level 1 takes none). */
    public static int xpFor(int level) {
        return PaXp.xpFor(level);                                     // [pa35] tuned as a real grind (PaXp: about 50 hours to level 10)
    }

    /**
     * [pa35] Experience an hour of ordinary mixed use earns at a multiplier of one (tuned with the levelling curve: see
     * PaLevelsTest's simulation). Used only for estimates ("about three hours of graft").
     */
    public static final double XP_PER_HOUR = PaXp.perHour();

    /** [pa35] About how long until a level, in words ("about three hours", "under an hour"). */
    public static String timeTo(long xp, int level) {
        double left = Math.max(0, xpFor(level) - xp) / Math.max(1e-6, XP_PER_HOUR * Math.max(0.01, PaConfig.xpMultiplier()));
        if (left <= 0) return "no time at all";
        if (left < 0.75) return Math.max(5, (int) Math.round(left * 60 / 5) * 5) + " minutes";
        int h = (int) Math.round(left);
        return h <= 1 ? "about an hour" : "about " + com.jrpetty.mcassistant.pa.voice.PaSpeech.words(h) + " hours";
    }

    /** The level so much experience makes. */
    public static int level(long xp) {
        int l = 1;
        while (l < MAX && xp >= xpFor(l + 1)) l++;
        return l;
    }

    /** How far through its level it is, 0..1 (1 at the top). */
    public static float progress(long xp) {
        int l = level(xp);
        if (l >= MAX) return 1.0F;
        int lo = xpFor(l), hi = xpFor(l + 1);
        return Math.max(0, Math.min(1, (xp - lo) / (float) (hi - lo)));
    }

    /** What the difficulty does to its pay: a gentler world teaches less. 0 peaceful .. 3 hard. */
    public static double difficulty(int diff) {
        return switch (diff) { case 0 -> 0.5; case 1 -> 0.8; case 2 -> 1.0; default -> 1.25; };
    }

    /** What it is paid for some work, after the difficulty and the server's multiplier; at least one for anything. */
    public static int pay(int base, int diff, double multiplier) {
        if (base <= 0) return 0;
        return Math.max(1, (int) Math.round(base * difficulty(diff) * Math.max(0.0, multiplier)));
    }

    // ------------------------------------------------------------------ [pa35] the mining ladder (PaMineLadder)

    /** The level this block needs before an assistant may break it (1 for most; the ladder's rung for ores). */
    public static int mineLevel(net.minecraft.world.level.block.state.BlockState state) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return PaMineLadder.levelFor(path, PaConfig.ladderOn(), PaConfig.ladderLevels());
    }

    /**
     * [pa35] May this assistant break this block? The one gate every break path asks (its gathering, mining, tunnelling,
     * clearing, orders, pings, and the logistics jobs). Only what it breaks: it carries, smelts and crafts with any ore.
     * Vanilla's tool rules still apply on top. Anything that isn't a Player AI Assistant may.
     */
    public static boolean canMine(net.minecraft.world.entity.Entity assistant, net.minecraft.world.level.block.state.BlockState state) {
        if (!(assistant instanceof PlayerAssistantEntity a) || state.isAir()) return true;
        int need = mineLevel(state);
        return need <= 1 || a.veteranLevel() >= need;
    }

    /** [pa35] The level this item needs before an assistant equips or uses it (the gear ladder, PaGearLadder). */
    public static int gearLevel(net.minecraft.world.item.ItemStack s) {
        if (s == null || s.isEmpty()) return 1;
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return PaGearLadder.levelFor(path, PaConfig.ladderOn(), PaConfig.ladderLevels());
    }

    /**
     * [pa35] May this assistant equip or use this item? The gate auto-equip, "equip best", the shield, the bow and
     * crossbow, armour and thrown things ask. Below the level it carries the item and gives it back, never uses it.
     */
    public static boolean canUse(net.minecraft.world.entity.Entity assistant, net.minecraft.world.item.ItemStack s) {
        if (!(assistant instanceof PlayerAssistantEntity a)) return true;
        int need = gearLevel(s);
        return need <= 1 || a.veteranLevel() >= need;
    }

    // ------------------------------------------------------------------ what each level does

    /** Work time taken off, percent: about four and a half a level (level 10: 40% off). */
    public static int workPercent(int level) {
        return Math.round((Math.max(1, Math.min(MAX, level)) - 1) * 40F / 9F);
    }

    /** [pa33g] Its hands' shake on a settled full draw (degrees, one sigma): from the archery table (PaArchery.TABLE). */
    public static float spread(int level) {
        return (float) PaArchery.row(level).swayDeg();
    }

    /** Does it lead a moving target? From level 4. */
    public static boolean leads(int level) {
        return level >= 4;
    }

    /** [pa33g] How far it will shoot (blocks), from the archery table: 16 at level 1 to 43 at level 10. */
    public static int range(int level) {
        return PaArchery.row(level).range();
    }

    /** [pa33g] Its reach for a block: a player's 4.5 at level 1, a tenth more a level (5.4 at level 10). */
    public static double reach(int level) {
        return 4.5 + 0.1 * (Math.max(1, Math.min(MAX, level)) - 1);
    }

    /** [pa33g] How long it takes to react (ticks): to a target changing course (its bow) and to a threat (its shield). */
    public static int reactTicks(int level) {
        return PaArchery.row(level).reactionTicks();
    }

    /** [pa33g] How fast its head turns (degrees a tick) when it aims: no snapping onto a target. */
    public static double turnDegrees(int level) {
        return PaArchery.row(level).turnDeg();
    }

    /** [pa33g] One row of the level table the guide and the Stats tab show: what each level does, all of it real. */
    public static String tableRow(int level) {
        int[][] all = PaArchery.at30();
        String bow = all == null ? "bow ..." : "bow " + all[Math.max(1, Math.min(MAX, level)) - 1][0] + "%/" + all[Math.max(1, Math.min(MAX, level)) - 1][1] + "% at 30";
        return "L" + level + "  work -" + workPercent(level) + "%  walk +" + speedPercent(level) + "%  reach " + String.format(java.util.Locale.ROOT, "%.1f", reach(level))
            + "  react " + reactTicks(level) * 50 + "ms  " + bow + "  range " + range(level);
    }

    /** Extra walking speed, percent: 2 a level. */
    public static int speedPercent(int level) {
        return 2 * (Math.max(1, Math.min(MAX, level)) - 1);
    }

    /** Extra damage in a fight: a third of a heart a level. */
    public static double damage(int level) {
        return 0.33 * (Math.max(1, Math.min(MAX, level)) - 1);
    }

    /** How far it looks for a path (blocks). */
    public static int followRange(int level) {
        return 32 + 4 * (Math.max(1, Math.min(MAX, level)) - 1);
    }

    // ------------------------------------------------------------------ skills by level

    /** One skill: its id (what the code asks for), what the Stats tab calls it, and the level it opens at. */
    public record Skill(String id, String label, int level) {}

    public static final List<Skill> SKILLS = List.of(
        new Skill("basics", "follow, guard, gather, farm, build, craft", 1),
        new Skill("pings", "pings and waypoints", 1),
        new Skill("bridge", "bridging and pillaring up", 2),
        new Skill("tame", "taming and breeding animals", 2),
        new Skill("pack3", "a third row in its pack", 3),
        new Skill("trade", "trading with villagers (your emeralds)", 3),
        new Skill("shield", "blocking with a shield", 4),
        new Skill("lead", "reading a moving target's lead (the bow)", 4),
        new Skill("redstone", "levers, buttons and doors on order", 4),
        new Skill("potions", "drinking healing potions", 5),
        new Skill("brew", "brewing basic potions", 5),
        new Skill("explore", "finding villages and mapping the area", 5),
        new Skill("enchant", "enchanting at a table", 6),
        new Skill("nether", "Nether trips with you", 6),
        new Skill("upgrade", "crafting better tools and armour for itself", 6),
        new Skill("trick", "trick shots (long range, at a ping)", 7),
        new Skill("parkour", "sprint-jumping after you", 7),
        new Skill("clutch", "a water-bucket clutch on a long fall", 8),
        new Skill("elytra", "following you on elytra", 8),
        new Skill("pearl", "ender pearls", 9),
        new Skill("end", "the End: crystals and the dragon", 10));

    public static boolean has(int level, String id) {
        for (Skill s : SKILLS) if (s.id().equals(id)) return level >= s.level();
        return false;
    }

    /** The level a skill opens at (or 0 for none such). */
    public static int levelOf(String id) {
        for (Skill s : SKILLS) if (s.id().equals(id)) return s.level();
        return 0;
    }

    /** What opens at exactly this level. */
    public static List<Skill> at(int level) {
        List<Skill> out = new ArrayList<>();
        for (Skill s : SKILLS) if (s.level() == level) out.add(s);
        return out;
    }

    /** "Next at L4: blocking with a shield, leading moving targets..." (or "" at the top). */
    public static String next(int level) {
        if (level >= MAX) return "";
        List<Skill> n = at(level + 1);
        StringBuilder b = new StringBuilder("Next at L" + (level + 1) + ": ");
        for (int i = 0; i < n.size(); i++) b.append(i > 0 ? ", " : "").append(n.get(i).label());
        return b.toString();
    }

    // ------------------------------------------------------------------ what it is paid for

    /** Experience for a kind of work (before difficulty and multiplier). */
    public static int baseFor(String what) {
        return switch (what) {
            case "block" -> 1;          // a block mined or a log felled
            case "crop" -> 1;
            case "place" -> 0;          // laying blocks is paid by the job
            case "kill" -> 4;
            case "kill_big" -> 12;
            case "job" -> 6;
            case "build" -> 30;
            case "craft" -> 1;
            case "smelt" -> 1;
            case "trade" -> 4;
            case "tame" -> 5;
            case "breed" -> 3;
            case "enchant" -> 8;
            case "brew" -> 6;
            case "explore" -> 10;
            case "trick" -> 6;
            case "challenge" -> 8;
            default -> 0;
        };
    }
}
