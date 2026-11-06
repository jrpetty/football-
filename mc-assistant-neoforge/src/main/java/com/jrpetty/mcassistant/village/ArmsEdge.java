package com.jrpetty.mcassistant.village;

/**
 * [tech-tree] What the new arms of the town's inventions (entity/InventionArms) are worth in a battle reckoned by the
 * model (BattleModel.resolve): the same edges a fight in front of a player gives them blow by blow, put as shares of a
 * side's bite. Plain Java, no game classes, so the unit tests weigh it as it stands.
 * <ul>
 * <li><b>Longbows and arbalests</b> in the archers' duel: a longbowman looses as one and a third of a bowman, an
 *     arbalester as one and a half (its bolt the harder; it spans the slower, so not twice).</li>
 * <li><b>Pikes and halberds</b> in the clash, against a side that sent its riders round: they are braced for them, the
 *     riders' charge comes on the points, and the flank's shock is blunted (a line of pikes all but stops it).</li>
 * <li><b>War hammers and halberds</b> against an armoured side: they go through the plate, the more of it the more.</li>
 * <li><b>Steel</b> needs no edge here: a steel blade and steel armour are worth what they are in a fighter's own power
 *     (BattleModel.power, by its armour's points; InventionArms.power for the blade).</li>
 * </ul>
 */
public final class ArmsEdge {

    private ArmsEdge() {}

    /** A side's new arms, counted among those on their feet: polearms and hammers in the line, the bows and arbalests. */
    public static final class Kit {
        public int pikes, halberds, hammers, longbows, arbalests;
        /** Its fighters' armour, the average in points (a full suit of iron is fifteen). */
        public int armour;

        public boolean any() {
            return pikes + halberds + hammers + longbows + arbalests > 0;
        }

        /** "3 pikes, 2 halberds, 4 longbows". */
        public String words() {
            StringBuilder sb = new StringBuilder();
            part(sb, pikes, "pike", "pikes");
            part(sb, halberds, "halberd", "halberds");
            part(sb, hammers, "war hammer", "war hammers");
            part(sb, longbows, "longbow", "longbows");
            part(sb, arbalests, "arbalest", "arbalests");
            return sb.length() == 0 ? "none of the new arms" : sb.toString();
        }

        private static void part(StringBuilder sb, int n, String one, String many) {
            if (n <= 0) return;
            if (sb.length() > 0) sb.append(", ");
            sb.append(n).append(' ').append(n == 1 ? one : many);
        }
    }

    /** A longbowman's worth in the duel (as a share of a bowman's), an arbalester's. */
    public static final double LONGBOW = 1.35, ARBALEST = 1.5;
    /** Pikes against riders: the most a side's bite is raised by its points, and how much of the flank's shock they take off. */
    public static final double BRACED = 0.6, BLUNTED = 0.7;
    /** Hammers against plate: the most a side's bite is raised, at a full suit of iron and over. */
    public static final double THROUGH = 0.5;

    /** The archers' duel: the side's missile arm's worth, as a share of its archers' count (1.0 with none of the new). */
    public static double volley(Kit us, int archers) {
        if (archers <= 0) return 1.0;
        int lb = Math.min(us.longbows, archers), ab = Math.min(us.arbalests, archers - lb);
        return (archers - lb - ab + lb * LONGBOW + ab * ARBALEST) / archers;
    }

    /**
     * The clash: the side's bite against them, as a share. {@code theyRide}: they came on with riders (the cavalry's
     * charge). Pikes and half the halberds count against riders; hammers and the other half against plate.
     */
    public static double clash(Kit us, int size, Kit them, boolean theyRide) {
        if (size <= 0) return 1.0;
        double mult = 1.0;
        if (theyRide) mult += BRACED * Math.min(1.0, (us.pikes + us.halberds * 0.5) / size * 2.0);
        double plate = Math.min(1.0, them.armour / 15.0);
        mult += THROUGH * plate * Math.min(1.0, (us.hammers + us.halberds * 0.5) / size * 2.0);
        return mult;
    }

    /** What is left of the flank's shock (1.0 all of it) when riders come round a side with this many points in its line. */
    public static double braced(Kit us, int size, boolean riders) {
        if (!riders || size <= 0) return 1.0;
        double share = Math.min(1.0, (us.pikes + us.halberds * 0.5) / size * 2.0);
        return 1.0 - BLUNTED * share;
    }

    /** The blows a ram wants to break a gate: ten; six with an iron-shod ram (Siege Engines); three with a powder keg. */
    public static int gateBlows(boolean siegeEngines, boolean powder) {
        return powder ? 3 : siegeEngines ? 6 : 10;
    }

    /** The heart defenders take from their own gate (its share of BattleModel's ten): half against siege engines, none against powder. */
    public static int gateHeart(int heart, boolean siegeEngines, boolean powder) {
        return powder ? 0 : siegeEngines ? heart / 2 : heart;
    }
}
