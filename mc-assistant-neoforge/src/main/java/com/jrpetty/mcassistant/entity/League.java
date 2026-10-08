package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The town's sporting books [batchC], kept with the world: the league between its teams, season by season,
 * and the cup; the friendlies it has played with its neighbours; the latest winners of its contests.
 *
 * <p><b>The league.</b> A town's teams are its four ends (Football): every rest day two of them meet on the
 * pitch, three points for a win and one each for a draw. A season is the town's own year (TownCalendar's
 * twenty-eight days, counted from its founding as Founding Day counts them). When the year turns, whoever
 * stands top (on points, then goal difference, then goals) are the champions, written into the chronicle,
 * and the table starts again.
 *
 * <p><b>The cup.</b> The champions get the cup: a gold ingot out of the stores (or nine nuggets), named "The
 * &lt;Town&gt; Cup" with every year's winners engraved on it, set in an item frame (the stores', or made of
 * eight sticks and a leather) on the back wall of the leader's hall, or the meeting hall. It is made once; each
 * year after, the same cup has the new champions' name put on it. A town with no hall, or not the gold, keeps
 * its champions waiting for their cup and puts it up when it can.
 */
public final class League extends SavedData {

    private static final String ID = "mc_assistant_sport";
    public static final int WIN = 3, DRAW = 1;

    /** One team's line in the table. */
    public static final class Row {
        public final String team;
        public int played, won, drawn, lost, scored, conceded;

        Row(String team) { this.team = team; }

        public int points() { return won * WIN + drawn * DRAW; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Team", team);
            t.putIntArray("N", new int[]{ played, won, drawn, lost, scored, conceded });
            return t;
        }

        static Row load(CompoundTag t) {
            Row r = new Row(t.getString("Team"));
            int[] n = t.getIntArray("N");
            if (n.length >= 6) { r.played = n[0]; r.won = n[1]; r.drawn = n[2]; r.lost = n[3]; r.scored = n[4]; r.conceded = n[5]; }
            return r;
        }
    }

    /** One town's sporting books. */
    static final class Town {
        long season = -1;
        final Map<String, Row> table = new LinkedHashMap<>();
        final List<String> results = new ArrayList<>();
        final List<String> champions = new ArrayList<>();
        final List<String> friendlies = new ArrayList<>();
        final Map<String, String> records = new LinkedHashMap<>();
        @Nullable UUID cupFrame;
        @Nullable String cupHolder;
        boolean cupPending;
        @Nullable String cupWaits;
        long lastAway = -1000, lastHome = -1000;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Season", season);
            ListTag rows = new ListTag();
            for (Row r : table.values()) rows.add(r.save());
            t.put("Table", rows);
            t.put("Results", strings(results));
            t.put("Champions", strings(champions));
            t.put("Friendlies", strings(friendlies));
            CompoundTag rec = new CompoundTag();
            for (Map.Entry<String, String> e : records.entrySet()) rec.putString(e.getKey(), e.getValue());
            t.put("Records", rec);
            if (cupFrame != null) t.putUUID("CupFrame", cupFrame);
            if (cupHolder != null) t.putString("CupHolder", cupHolder);
            t.putBoolean("CupPending", cupPending);
            t.putLong("LastAway", lastAway);
            t.putLong("LastHome", lastHome);
            return t;
        }

        static Town load(CompoundTag t) {
            Town w = new Town();
            w.season = t.getLong("Season");
            for (Tag x : t.getList("Table", Tag.TAG_COMPOUND)) {
                Row r = Row.load((CompoundTag) x);
                w.table.put(r.team, r);
            }
            read(t.getList("Results", Tag.TAG_STRING), w.results);
            read(t.getList("Champions", Tag.TAG_STRING), w.champions);
            read(t.getList("Friendlies", Tag.TAG_STRING), w.friendlies);
            CompoundTag rec = t.getCompound("Records");
            for (String k : rec.getAllKeys()) w.records.put(k, rec.getString(k));
            if (t.hasUUID("CupFrame")) w.cupFrame = t.getUUID("CupFrame");
            if (t.contains("CupHolder")) w.cupHolder = t.getString("CupHolder");
            w.cupPending = t.getBoolean("CupPending");
            w.lastAway = t.contains("LastAway") ? t.getLong("LastAway") : -1000;
            w.lastHome = t.contains("LastHome") ? t.getLong("LastHome") : -1000;
            return w;
        }
    }

    private final Map<UUID, Town> towns = new HashMap<>();
    /** Without a world to keep them in (a unit of work run before the server starts): kept here. */
    private static final Map<UUID, Town> LOOSE = new HashMap<>();

    public League() {}

    public static void resetForTests() {
        League l = com.jrpetty.mcassistant.SessionReset.opening() ? null : of();   // a world opening keeps its table
        if (l != null) l.towns.clear();
        LOOSE.clear();
    }

    @Nullable
    static League of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(League::new, League::load, null), ID);
    }

    static synchronized Town town(UUID village) {
        League l = of();
        Map<UUID, Town> m = l == null ? LOOSE : l.towns;
        return m.computeIfAbsent(village, k -> new Town());
    }

    static void changed() {
        League l = of();
        if (l != null) l.setDirty();
    }

    // ------------------------------------------------------------------ the league

    /** The town's season for a day: its own year, counted from its founding. */
    static long seasonOf(UUID village, long day) {
        return FoundingDay.years(village, day);
    }

    /** "the town's second year" */
    static String yearWords(long season) {
        return "the town's " + TownCalendar.ordinal(season + 1) + " year";
    }

    /**
     * A match played: into the town's results, and (a league match) into its table. A match from a new year
     * crowns last year's champions first.
     */
    public static void played(ServerLevel level, Villages.Village v, long day, String home, String away, int hg, int ag, boolean counts) {
        Town t = town(v.id());
        long season = seasonOf(v.id(), day);
        if (t.season < 0) t.season = season;
        if (counts && season > t.season) crown(level, v, day);
        if (counts) {
            Row h = t.table.computeIfAbsent(home, Row::new), a = t.table.computeIfAbsent(away, Row::new);
            h.played++; a.played++;
            h.scored += hg; h.conceded += ag;
            a.scored += ag; a.conceded += hg;
            if (hg > ag) { h.won++; a.lost++; } else if (ag > hg) { a.won++; h.lost++; } else { h.drawn++; a.drawn++; }
        }
        t.results.add(0, "Day " + day + ": " + capital(home) + " " + hg + "-" + ag + " " + away + (counts ? "" : " (not in the league)"));
        while (t.results.size() > 12) t.results.remove(t.results.size() - 1);
        changed();
    }

    /** A friendly with another town, in this town's books. */
    static void friendly(UUID village, long day, String line, boolean home) {
        Town t = town(village);
        t.friendlies.add(0, "Day " + day + ": " + line);
        while (t.friendlies.size() > 8) t.friendlies.remove(t.friendlies.size() - 1);
        if (home) t.lastHome = day; else t.lastAway = day;
        changed();
    }

    /** The day a town last sent a side away (or played host), or long ago. */
    static long lastFriendly(UUID village, boolean home) {
        Town t = town(village);
        return home ? t.lastHome : t.lastAway;
    }

    /** The latest of a kind of contest ("fishing", "race", "archery"): who won it, for the books and the board. */
    static void record(UUID village, String what, String line) {
        town(village).records.put(what, line);
        changed();
    }

    @Nullable
    static String record(UUID village, String what) {
        return town(village).records.get(what);
    }

    /** The table, top first: points, goal difference, goals, then by name. */
    public static List<Row> table(UUID village) {
        List<Row> out = new ArrayList<>(town(village).table.values());
        out.sort(Comparator.comparingInt((Row r) -> -r.points()).thenComparingInt(r -> -(r.scored - r.conceded))
            .thenComparingInt(r -> -r.scored).thenComparing(r -> r.team));
        return out;
    }

    /** How many times these two have met this season. */
    static int met(UUID village, String a, String b) {
        int n = 0;
        for (String r : town(village).results) {
            if (r.contains(" (not in the league)")) continue;
            String low = r.toLowerCase(java.util.Locale.ROOT);
            if (low.contains(a.toLowerCase(java.util.Locale.ROOT)) && low.contains(b.toLowerCase(java.util.Locale.ROOT))) n++;
        }
        return n;
    }

    /** Who holds the cup, or null. */
    @Nullable
    public static String cupHolder(UUID village) {
        return town(village).cupHolder;
    }

    /** Once a day (Sport): a year turned with a table to crown, or a cup still waiting to go up. */
    public static void daily(ServerLevel level, Villages.Village v, long day) {
        Town t = town(v.id());
        long season = seasonOf(v.id(), day);
        if (t.season < 0) { t.season = season; changed(); }
        if (season > t.season) {
            if (!t.table.isEmpty()) crown(level, v, day);
            else { t.season = season; changed(); }
        }
        if (t.cupPending) putUpCup(level, v, day);
    }

    /**
     * The year is over: whoever stands top of the table are the champions, into the chronicle, and the cup is
     * theirs. The table starts again for the new year. Returns the champions, or null with no table.
     */
    @Nullable
    public static String crown(ServerLevel level, Villages.Village v, long day) {
        Town t = town(v.id());
        List<Row> rows = table(v.id());
        long was = t.season;
        t.season = seasonOf(v.id(), day);
        if (rows.isEmpty()) { changed(); return null; }
        Row top = rows.get(0);
        t.table.clear();
        t.champions.add(0, "Year " + (was + 1) + ": " + top.team + " (" + top.points() + " points from " + top.played + " games)");
        t.cupHolder = top.team;
        t.cupPending = true;
        String town = Villages.name(v.id());
        Villages.tell(v.id(), day, capital(top.team) + " won the league in " + yearWords(was) + ", " + top.points()
            + " points from " + top.played + " games, and with it the " + town + " Cup");
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && top.team.equals(Football.teamOf(f))) {
                f.persona().remember(day, "my end won the " + town + " Cup", 6);
            }
        }
        putUpCup(level, v, day);
        changed();
        return top.team;
    }

    // ------------------------------------------------------------------ the cup

    private static final String CUP = "mca_cup";

    /** Is this the town's cup? */
    static boolean isCup(ItemStack s, UUID village) {
        return !s.isEmpty() && village.toString().equals(s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString(CUP));
    }

    /** The cup with this year's champions engraved under the years before. */
    static ItemStack engraved(ItemStack gold, UUID village, List<String> champions) {
        ItemStack cup = gold.copyWithCount(1);
        cup.set(DataComponents.CUSTOM_NAME, Component.literal("The " + Villages.name(village) + " Cup"));
        List<Component> lines = new ArrayList<>();
        for (int i = champions.size() - 1; i >= 0 && lines.size() < 16; i--) {
            String c = champions.get(i);
            int paren = c.indexOf(" (");
            lines.add(Component.literal(paren > 0 ? c.substring(0, paren) : c));
        }
        cup.set(DataComponents.LORE, new ItemLore(lines));
        CompoundTag tag = new CompoundTag();
        tag.putString(CUP, village.toString());
        cup.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return cup;
    }

    /** The cup's frame, if it still hangs. */
    @Nullable
    static ItemFrame frame(ServerLevel level, UUID village) {
        Town t = town(village);
        if (t.cupFrame != null && level.getEntity(t.cupFrame) instanceof ItemFrame fr && fr.isAlive() && isCup(fr.getItem(), village)) return fr;
        return null;
    }

    /** Where the cup goes: the leader's hall, else the meeting hall. */
    @Nullable
    static Ledger.Building hall(UUID village) {
        Ledger.Building b = Villages.builtStructure(village, "townhall");
        return b != null ? b : Villages.builtStructure(village, "hall");
    }

    /**
     * The cup put up, or engraved again: the champions' name on the cup in its frame; or, with no cup yet (or
     * the old one gone from its frame), a new one made of the stores' gold and hung in a frame from the stores
     * on the hall's back wall. True once it stands with this year's champions on it.
     */
    public static boolean putUpCup(ServerLevel level, Villages.Village v, long day) {
        Town t = town(v.id());
        if (t.cupHolder == null) return false;
        ItemFrame have = frame(level, v.id());
        if (have != null) {
            have.setItem(engraved(have.getItem(), v.id(), t.champions), false);
            t.cupPending = false;
            t.cupWaits = null;
            changed();
            return true;
        }
        Ledger.Building hall = hall(v.id());
        if (hall == null) { t.cupWaits = "a hall to stand in"; return false; }
        if (!Land.areaLoaded(level, hall.anchor(), 12)) { t.cupWaits = "the hall to be in sight"; return false; }
        boolean ingot = Market.stock(level, v.id(), s -> s.is(Items.GOLD_INGOT)) > 0;
        boolean nuggets = !ingot && Market.stock(level, v.id(), s -> s.is(Items.GOLD_NUGGET)) >= 9;
        boolean frameIn = Market.stock(level, v.id(), s -> s.is(Items.ITEM_FRAME)) > 0;
        boolean makings = !frameIn && Market.stock(level, v.id(), s -> s.is(Items.STICK)) >= 8
            && Market.stock(level, v.id(), s -> s.is(Items.LEATHER)) >= 1;
        if (!ingot && !nuggets) { t.cupWaits = "a gold ingot in the stores"; return false; }
        if (!frameIn && !makings) { t.cupWaits = "an item frame in the stores (or eight sticks and a leather)"; return false; }
        Spot spot = wallSpot(level, hall);
        if (spot == null) { t.cupWaits = "a bare wall in the hall"; return false; }
        ItemFrame fr = new ItemFrame(level, spot.at(), spot.out());
        if (!fr.survives()) { t.cupWaits = "a bare wall in the hall"; return false; }
        // All there: out of the stores now, and made up.
        if (ingot ? !Crafts.take(level, v, s -> s.is(Items.GOLD_INGOT), 1) : !Crafts.take(level, v, s -> s.is(Items.GOLD_NUGGET), 9)) return false;
        if (frameIn ? !Crafts.take(level, v, s -> s.is(Items.ITEM_FRAME), 1)
                : !(Crafts.take(level, v, s -> s.is(Items.STICK), 8) && Crafts.take(level, v, s -> s.is(Items.LEATHER), 1))) {
            Crafts.store(level, v, ingot ? new ItemStack(Items.GOLD_INGOT) : new ItemStack(Items.GOLD_NUGGET, 9));
            return false;
        }
        level.addFreshEntity(fr);
        fr.setItem(engraved(new ItemStack(Items.GOLD_INGOT), v.id(), t.champions), false);
        fr.playPlacementSound();
        t.cupFrame = fr.getUUID();
        t.cupPending = false;
        t.cupWaits = null;
        Villages.tell(v.id(), day, "the " + Villages.name(v.id()) + " Cup was put up in " + Villages.spoken(hall.structure())
            + ", " + t.cupHolder + "'s name on it");
        changed();
        return true;
    }

    /** A place for the cup's frame, and the way it faces. */
    record Spot(BlockPos at, Direction out) {}

    /**
     * A place on the hall's back wall for the cup: a free block inside it, at head height, with a whole wall
     * behind it and room in front, nearest the middle; or null.
     */
    @Nullable
    static Spot wallSpot(ServerLevel level, Ledger.Building b) {
        int[] half = Blueprints.has(b.structure()) ? Blueprints.fullHalf(b.structure()) : new int[]{ 3, 3 };
        Direction back = b.facing(), right = back.getClockWise();
        for (int h : new int[]{ 2, 1, 3 }) {
            for (int dz = half[1]; dz >= -half[1]; dz--) {
                for (int i = 0; i <= 2 * half[0]; i++) {
                    int dx = (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2);
                    if (Math.abs(dx) > half[0] - 1) continue;
                    BlockPos p = b.anchor().relative(right, dx).relative(back, dz).above(h);
                    BlockPos behind = p.relative(back), front = p.relative(back.getOpposite());
                    if (!level.getBlockState(p).isAir() || !level.getBlockState(front).isAir()) continue;
                    if (!level.getBlockState(behind).isFaceSturdy(level, behind, back.getOpposite())) continue;
                    if (!level.getEntitiesOfClass(HangingEntity.class, new AABB(p)).isEmpty()) continue;
                    // Inside: a roof over it somewhere above.
                    boolean roofed = false;
                    for (int up = 1; up <= 8 && !roofed; up++) roofed = !level.getBlockState(p.above(up)).isAir();
                    if (!roofed) continue;
                    return new Spot(p, back.getOpposite());
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ where the player sees it

    /** The books' lines: the table, the cup, the last results, the friendlies, the contests. */
    static List<String> book(UUID village, long day) {
        Town t = town(village);
        List<String> out = new ArrayList<>();
        List<Row> rows = table(village);
        String town = Villages.name(village);
        out.add("The league, " + yearWords(t.season < 0 ? seasonOf(village, day) : t.season) + " (three points for a win, one for a draw):");
        if (rows.isEmpty()) out.add("  No league games yet this year.");
        int i = 1;
        for (Row r : rows) {
            out.add("  " + (i++) + ". " + capital(r.team) + ": played " + r.played + ", won " + r.won + ", drawn " + r.drawn
                + ", lost " + r.lost + ", goals " + r.scored + "-" + r.conceded + ", " + r.points() + (r.points() == 1 ? " point" : " points"));
        }
        if (t.cupHolder != null) {
            out.add("The " + town + " Cup: held by " + t.cupHolder + (t.cupPending ? " (waiting for " + (t.cupWaits == null ? "its makings" : t.cupWaits) + ")" : ", in the hall") + ".");
        } else {
            out.add("The " + town + " Cup: to whoever tops the league at the year's end.");
        }
        for (String c : t.champions) out.add("  " + c);
        if (!t.results.isEmpty()) {
            out.add("Results, latest first:");
            for (int k = 0; k < Math.min(6, t.results.size()); k++) out.add("  " + t.results.get(k));
        }
        if (!t.friendlies.isEmpty()) {
            out.add("Friendlies with the neighbours:");
            for (int k = 0; k < Math.min(4, t.friendlies.size()); k++) out.add("  " + t.friendlies.get(k));
        }
        for (Map.Entry<String, String> e : t.records.entrySet()) {
            String what = switch (e.getKey()) {
                case "fishing" -> "The fishing contest";
                case "race" -> "The children's race";
                case "archery" -> "The archery contest";
                default -> e.getKey();
            };
            out.add(what + ": " + e.getValue() + ".");
        }
        return out;
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ kept with the world

    private static ListTag strings(List<String> l) {
        ListTag out = new ListTag();
        for (String s : l) out.add(StringTag.valueOf(s));
        return out;
    }

    private static void read(ListTag l, List<String> into) {
        for (int i = 0; i < l.size(); i++) into.add(l.getString(i));
    }

    public static League load(CompoundTag tag, HolderLookup.Provider registries) {
        League l = new League();
        for (Tag x : tag.getList("Towns", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) x;
            if (one.hasUUID("Id")) l.towns.put(one.getUUID("Id"), Town.load(one.getCompound("Books")));
        }
        return l;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag all = new ListTag();
        for (Map.Entry<UUID, Town> e : towns.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", e.getKey());
            one.put("Books", e.getValue().save());
            all.add(one);
        }
        tag.put("Towns", all);
        return tag;
    }
}
