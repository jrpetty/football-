package com.jrpetty.mcassistant.entity;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.DyeColor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [regalia19] Who rules a town, worn: the crown, the chain of office, the elder's circlet, the chief's feathers. The town's
 * government (Government) decides who leads it and what it is called; this says what each of its folk wears for it, and
 * sends that to the client in one number (the kind, and the town's two banner colours to dye it in: client/RegaliaLayer).
 *
 * <ul>
 * <li><b>A monarch</b>, the hereditary lord: a gold crown with its gems over a velvet cap, a long cape of the town's colours
 *     with an ermine collar and border, a sceptre carried when its hands are free, and a robe of the colours trimmed in
 *     gold. Called lord, and king once its house holds a city, a capital or a vassal town.</li>
 * <li><b>The royal house</b>: the lord's heir and its consort, a gold coronet and a short cape.</li>
 * <li><b>An elected leader</b> (the land's harbourmaster, reeve, thane, mayor; the guildmaster, the commune's steward): a
 *     gold chain of office and its medallion, a dark formal coat faced in the town's colour, a fine hat.</li>
 * <li><b>An elder</b> (the speaker of the council, the plains' elder, the chaplain): a silver circlet, a staff, a robe and
 *     a stole of the town's colours. The council's other elders wear the circlet.</li>
 * <li><b>A chief</b> (the jungle's chief, the mesa's headman): a band of feathers in the town's colours, a beaded mantle
 *     and a feathered staff.</li>
 * </ul>
 *
 * <p>Worked out for a whole town at a time, a couple of seconds at most behind a death, an election or a coup, and sent
 * only when it changes: the old ruler takes it off as the new one puts it on.
 */
public final class Regalia {

    private Regalia() {}

    public enum Kind { NONE, MONARCH, ROYAL, ELECTED, ELDER, COUNCILLOR, CHIEF }

    private static final Kind[] KINDS = Kind.values();

    // ------------------------------------------------------------------ the number the client gets

    /** The kind in the low four bits, the main colour (a DyeColor's id) in the next four, the second in the four after. */
    public static int pack(Kind k, int main, int accent) {
        return k == Kind.NONE ? 0 : k.ordinal() | (main & 15) << 4 | (accent & 15) << 8;
    }

    public static Kind kindOf(int packed) {
        int k = packed & 15;
        return k < KINDS.length ? KINDS[k] : Kind.NONE;
    }

    public static int mainOf(int packed) { return packed >>> 4 & 15; }

    public static int accentOf(int packed) { return packed >>> 8 & 15; }

    /** Dressed for office over its own clothes: its trade's kit and its own coat put away (all but a councillor's circlet). */
    public static boolean robed(int packed) {
        Kind k = kindOf(packed);
        return k != Kind.NONE && k != Kind.COUNCILLOR;
    }

    /** Something on its head in place of a hat. */
    public static boolean crowned(int packed) {
        return kindOf(packed) != Kind.NONE;
    }

    // ------------------------------------------------------------------ who wears what, a town at a time

    /** A town's court as last worked out: when, its leader and the leader's title, and everybody who wears something. */
    private record Court(long at, @Nullable UUID leader, String title, Map<UUID, Kind> worn, int main, int accent, String town) {}

    private static final Map<UUID, Court> COURTS = new ConcurrentHashMap<>();
    /** How long a court is kept before it is worked out again (game ticks). */
    private static final long FRESH = 40;

    public static void resetForTests() {
        COURTS.clear();
    }

    private static Court court(UUID village, long now) {
        Court c = COURTS.get(village);
        if (c != null && now - c.at() < FRESH && now >= c.at()) return c;
        c = work(village, now);
        COURTS.put(village, c);
        return c;
    }

    private static Court work(UUID village, long now) {
        Map<UUID, Kind> worn = new HashMap<>();
        int[] colours = colours(village);
        String town = Villages.name(village);
        UUID leader = Villages.elder(village);
        if (leader == null) return new Court(now, null, "", worn, colours[0], colours[1], town);
        Government.Form form = Government.of(village);
        String land = Homeland.leaderTitle(village);
        Kind lead = switch (form) {
            case LORD -> Kind.MONARCH;
            case COUNCIL, CHAPLAIN -> Kind.ELDER;
            case GUILD, COMMUNE, DEMOCRACY -> Kind.ELECTED;
            case PLANNED -> Kind.ELDER;                                       // [featcivic] the chair's sober robe
            case JUNTA -> Kind.CHIEF;                                         // [featcivic] the general's mantle
            case REEVE -> "chief".equals(land) || "headman".equals(land) ? Kind.CHIEF : "elder".equals(land) ? Kind.ELDER : Kind.ELECTED;
        };
        worn.put(leader, lead);
        if (form == Government.Form.LORD) {
            VillageFolkEntity partner = Government.partnerOf(village, leader);
            if (partner != null) worn.putIfAbsent(partner.getUUID(), Kind.ROYAL);
            UUID heir = Government.heirId(village);
            if (heir != null) worn.putIfAbsent(heir, Kind.ROYAL);
        } else if (form == Government.Form.COUNCIL || form == Government.Form.PLANNED) {
            for (VillageFolkEntity e : Elections.voters(village)) worn.putIfAbsent(e.getUUID(), Kind.COUNCILLOR);
        } else if (form == Government.Form.DEMOCRACY) {                   // [featcivic] the elected members of the town council
            for (UUID m : Factions.members(village)) worn.putIfAbsent(m, Kind.COUNCILLOR);
        }
        return new Court(now, leader, title(village, form, land), worn, colours[0], colours[1], town);
    }

    /** What the leader is called before its name: "King", "Lord", "Mayor", "Speaker", "Fen-reeve". */
    static String title(UUID village, Government.Form form, String land) {
        String t = switch (form) {
            case LORD -> kingdom(village) ? "king" : "lord";
            case COUNCIL -> "speaker";
            case REEVE -> land;
            case DEMOCRACY -> "mayor";                                        // [featcivic]
            case PLANNED -> "chair";
            case JUNTA -> "general";
            default -> Government.title(village);
        };
        return t.isEmpty() ? "" : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** A lordship that is a kingdom: its house holds a city or a capital, or another town as its vassal. */
    static boolean kingdom(UUID village) {
        Villages.Rank r = Villages.rank(village);
        return r == Villages.Rank.CITY || r == Villages.Rank.CAPITAL || !Vassals.vassals(village).isEmpty();
    }

    /**
     * The town's two colours for its ruler's cloth: its banner's field and its first charge (Heraldry), a pale field giving
     * way to the charge so a cape is never white on white; a town without a banner yet in a colour of its own choosing.
     */
    static int[] colours(UUID village) {
        Heraldry.Design d = Heraldry.design(village);
        DyeColor main, accent;
        if (d != null) {
            main = d.field();
            accent = d.layers().isEmpty() ? DyeColor.YELLOW : d.layers().get(0).colour();
        } else {
            DyeColor[] royal = { DyeColor.RED, DyeColor.BLUE, DyeColor.PURPLE, DyeColor.GREEN, DyeColor.CYAN, DyeColor.BLACK };
            main = royal[Math.floorMod(village.hashCode(), royal.length)];
            accent = DyeColor.YELLOW;
        }
        if (pale(main)) {
            DyeColor m = main;
            main = pale(accent) ? DyeColor.RED : accent;
            accent = m;
        }
        if (accent == main) accent = main == DyeColor.YELLOW ? DyeColor.WHITE : DyeColor.YELLOW;
        return new int[]{ main.getId(), accent.getId() };
    }

    private static boolean pale(DyeColor c) {
        return c == DyeColor.WHITE || c == DyeColor.LIGHT_GRAY || c == DyeColor.YELLOW || c == DyeColor.LIME
            || c == DyeColor.PINK || c == DyeColor.LIGHT_BLUE;
    }

    // ------------------------------------------------------------------ a folk's

    public static Kind kind(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.showcaseFolk()) return Kind.NONE;
        return court(village, f.level().getGameTime()).worn().getOrDefault(f.getUUID(), Kind.NONE);
    }

    /** The number its clothes are drawn from (client/RegaliaLayer), 0 for none. */
    public static int packed(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.showcaseFolk()) return 0;
        Court c = court(village, f.level().getGameTime());
        return pack(c.worn().getOrDefault(f.getUUID(), Kind.NONE), c.main(), c.accent());
    }

    /**
     * Its name as the nameplate gives it: "King Alder of Rushscar", "Mayor Bryn of Cherrywick", "Fen, heir of Rushscar",
     * "Elder Quill" — the title in gold — or its bare name for everybody else.
     */
    public static Component plate(VillageFolkEntity f) {
        String name = f.displayNameCap();
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.showcaseFolk()) return Component.literal(name);
        Court c = court(village, f.level().getGameTime());
        Kind k = c.worn().getOrDefault(f.getUUID(), Kind.NONE);
        String of = c.town().isEmpty() ? "" : " of " + c.town();
        MutableComponent out = Component.literal("");
        switch (k) {
            case NONE -> { return Component.literal(name); }
            case ROYAL -> {
                boolean consort = !f.getUUID().equals(Government.heirId(village));
                out.append(Component.literal(name)).append(Component.literal(", " + (consort ? "consort" : "heir") + of)
                    .withStyle(ChatFormatting.GOLD));
            }
            case COUNCILLOR -> out.append(Component.literal("Elder ").withStyle(ChatFormatting.GOLD)).append(Component.literal(name));
            default -> {
                if (!c.title().isEmpty()) out.append(Component.literal(c.title() + " ").withStyle(ChatFormatting.GOLD));
                out.append(Component.literal(name)).append(Component.literal(of).withStyle(ChatFormatting.GOLD));
            }
        }
        return out;
    }

    /** The title alone for its card and the tests: "King", "Mayor", "Elder", "heir", "consort", or "". */
    public static String titleOf(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return "";
        Court c = court(village, f.level().getGameTime());
        Kind k = c.worn().getOrDefault(f.getUUID(), Kind.NONE);
        return switch (k) {
            case NONE -> "";
            case ROYAL -> f.getUUID().equals(Government.heirId(village)) ? "heir" : "consort";
            case COUNCILLOR -> switch (Government.of(village)) {               // [featcivic] the elected council's members, the committee
                case DEMOCRACY -> "Councillor";
                case PLANNED -> "Committee";
                default -> "Elder";
            };
            default -> c.title();
        };
    }

    /** Tests: the court worked out afresh now. */
    public static void freshForTests(UUID village) {
        COURTS.remove(village);
    }
}
