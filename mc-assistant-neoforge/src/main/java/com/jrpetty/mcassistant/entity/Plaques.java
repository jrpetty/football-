package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plaques [batchD] (Culture): oak signs put up where something happened that the town wants remembered.
 * <ul>
 * <li><b>The founding spot</b>: by the heart of the town, a post and a sign, "Here Ashford was founded, day
 *     1", once the town is a day old;</li>
 * <li><b>the first house</b>: on its front wall beside the door, "The first house of Ashford";</li>
 * <li><b>where a hero fell</b>: a guard killed at its post, or anybody killed while the raiders came, on the
 *     ground where it fell (VillageFolkEntity.die: Plaques.fell), "Here fell Ada the guard, defending the
 *     town, day 41"; three in a town at most;</li>
 * <li><b>the record harvest</b>: once the town's fields bring in more in a day than ever (a good day's worth,
 *     and the town a few days old), at the edge of the field of the farmer who brought in most, "Record
 *     harvest, 46 coins' worth, day 12, Bert's field"; written up again where it stands when the record is
 *     beaten.</li>
 * </ul>
 * Sourced from the town's chronicle and its ledger. Put up by a hand at the town's works, out of the stores (a
 * sign or two planks, and for a plaque on open ground a fence post or two more planks), and never on a street,
 * a worn path or in a doorway: on open ground off the street, a plaque goes on the nearest free ground to its
 * place that is none of those. The Culture page of the books lists them, and where each stands.
 */
public final class Plaques {

    private Plaques() {}

    /** Where a hero fell, three in a town at most. */
    static final int FALLEN = 3;

    public enum Site { FOUNDING, FIRST_HOUSE, FELL, HARVEST,
        /** [war-peace] A war's memorial, by the chapel or the graveyard (WarAndPeace). */
        MEMORIAL,
        /** [perks] A leader's reign and its legacy, before the hall (Reigns). */
        LEGACY }

    /** A plaque: what it marks, where (the sign's cell), on a wall (or on a post), its lines, and whether it is up. */
    public record Plaque(Site site, BlockPos at, boolean wall, String[] lines, boolean up, long day) {
        String[] row() {
            return new String[]{ site.name(), Integer.toString(at.getX()), Integer.toString(at.getY()), Integer.toString(at.getZ()),
                wall ? "1" : "0", up ? "1" : "0", Long.toString(day), lines[0], lines[1], lines[2], lines[3] };
        }
    }

    private static final String NOTE = "culture.plaques";

    public static List<Plaque> plaques(UUID village) {
        List<Plaque> out = new ArrayList<>();
        for (String[] r : Culture.rows(village, NOTE)) {
            if (r.length < 11) continue;
            try {
                out.add(new Plaque(Site.valueOf(r[0]), new BlockPos((int) Culture.num(r[1], 0), (int) Culture.num(r[2], 0), (int) Culture.num(r[3], 0)),
                    r[4].equals("1"), new String[]{ r[7], r[8], r[9], r[10] }, r[5].equals("1"), Culture.num(r[6], 0)));
            } catch (IllegalArgumentException ignored) {
                // a kind from a later build
            }
        }
        return out;
    }

    private static void save(UUID village, List<Plaque> all) {
        List<String[]> rows = new ArrayList<>();
        for (Plaque p : all) rows.add(p.row());
        Culture.rows(village, NOTE, rows);
    }

    private static final Map<UUID, Long> DUE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SHORT = new ConcurrentHashMap<>();

    static void resetForTests() {
        DUE.clear();
        LOOKED.clear();
        SHORT.clear();
    }

    // ------------------------------------------------------------------ what is to be marked

    /**
     * A folk has died (VillageFolkEntity.die): a guard, or anybody killed while the raiders came, fell as a hero,
     * and a plaque is wanted where it fell. Three in a town at most.
     */
    public static void fell(VillageFolkEntity f, String how, long day) {
        UUID village = f.ownerId();
        if (village == null || f.isShowcase() || !(f.level() instanceof ServerLevel level)) return;
        boolean raid = how.contains("raiders");
        boolean guard = f.stationTask() == AssistantEntity.StationTask.GUARD && !how.contains("old age");
        if (!raid && !guard) return;
        List<Plaque> all = plaques(village);
        int fallen = 0;
        for (Plaque p : all) if (p.site() == Site.FELL) fallen++;
        if (fallen >= FALLEN) return;
        String name = f.displayNameCap();
        String who = f.isBaby() ? "a child of the town" : "the " + f.stationTask().title.toLowerCase(Locale.ROOT);
        String[] lines = { "Here fell", name, raid ? "against raiders" : who, "day " + (day + 1) };
        BlockPos at = f.blockPosition();
        BlockPos ground = Watch.floorAt(level, at.getX(), at.getZ(), at.getY());
        all.add(new Plaque(Site.FELL, ground != null ? ground : at, false, lines, false, day));
        save(village, all);
    }

    /**
     * [war-peace] A war's memorial wanted (WarAndPeace, at the peace): a post and a sign near this mark (before the
     * chapel or the graveyard), with whoever the war cost or, with nobody lost, the war itself and the peace. Put
     * up as every plaque is, by a hand at the town's works out of the stores.
     */
    public static void memorial(UUID village, BlockPos mark, String[] lines, long day) {
        List<Plaque> all = plaques(village);
        all.add(new Plaque(Site.MEMORIAL, mark, false, lines, false, day));
        save(village, all);
    }

    /**
     * [perks] A reign's plaque wanted (Reigns, as a leader leaves office with a legacy): a post and a sign near the
     * hall, with the leader's name, its days and what the town keeps of its time. Put up as any plaque is, out of the
     * stores by a hand at the town's works, so a town without a sign to spare remembers its leaders later, not for free.
     */
    public static void legacy(UUID village, BlockPos mark, String[] lines, long day) {
        List<Plaque> all = plaques(village);
        all.add(new Plaque(Site.LEGACY, mark, false, lines, false, day));
        save(village, all);
    }

    /** The plaques the town wants as it stands now: its founding, its first house, its record harvest. */
    static void look(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<Plaque> all = plaques(id);
        boolean changed = false;
        String town = Villages.name(id);
        long founded = FoundingDay.founded(id);
        if (founded >= 0 && day > founded && !has(all, Site.FOUNDING)) {
            BlockPos c = v.centre();
            all.add(new Plaque(Site.FOUNDING, c, false, new String[]{ "Here", town, "was founded", "day " + (founded + 1) }, false, day));
            changed = true;
        }
        if (!has(all, Site.FIRST_HOUSE)) {
            for (Ledger.Building b : Ledger.buildings(id)) {
                if (!b.structure().equals("house")) continue;
                long built = builtOn(id, "house");
                all.add(new Plaque(Site.FIRST_HOUSE, b.anchor(), true,
                    new String[]{ "The first", "house of", town, built >= 0 ? "built day " + (built + 1) : "" }, false, day));
                changed = true;
                break;
            }
        }
        changed |= harvest(level, v, all, day);
        if (changed) save(id, all);
    }

    private static boolean has(List<Plaque> all, Site s) {
        for (Plaque p : all) if (p.site() == s) return true;
        return false;
    }

    /** The day the chronicle first tells of a building of this kind going up, or -1. */
    private static long builtOn(UUID village, String structure) {
        String spoken = Villages.spoken(structure).toLowerCase(Locale.ROOT);
        for (Chronicle.Entry e : Chronicle.of(village)) {
            String t = e.text().toLowerCase(Locale.ROOT);
            if (t.contains(spoken) && (t.contains("went up") || t.contains("was opened") || t.contains("was built") || t.contains("finished"))) return e.day();
        }
        return -1;
    }

    /**
     * Yesterday's harvest against the town's record (the fields' output in the books, Economy): a record of a good
     * day's worth, in a town a few days old, marked at the edge of the field of the farmer who brought in most.
     */
    private static boolean harvest(ServerLevel level, Villages.Village v, List<Plaque> all, long day) {
        UUID id = v.id();
        Economy.Day d = Economy.yesterdayBooks(id);
        if (d == null) return false;
        double farm = d.trades.getOrDefault(AssistantEntity.StationTask.FARM, 0.0);
        String[] rec = String.valueOf(Ledger.note(id, "culture.harvest")).split("\t");
        double best = rec.length >= 2 ? Culture.num(rec[0], 0) : 0;
        if (Ledger.note(id, "culture.harvest") != null && Culture.num(rec.length > 1 ? rec[1] : "", -1) == day - 1) return false;
        if (farm < Math.max(12.0, best * 1.1)) return false;
        long founded = FoundingDay.founded(id);
        if (founded >= 0 && day - founded < 4) return false;
        // The farmer who brought in most, and its field.
        VillageFolkEntity farmer = null;
        double most = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.FARM || f.workZone() == null) continue;
            double made = d.folk.getOrDefault(f.getUUID(), 0.0);
            if (farmer == null || made > most) { farmer = f; most = made; }
        }
        if (farmer == null) return false;
        return record(level, v, all, (int) Math.round(farm), day - 1, farmer.displayNameCap(), farmer.workZone().center());
    }

    /** A new record harvest: written down, and its plaque wanted (or its words written up again where it stands). */
    static boolean record(ServerLevel level, Villages.Village v, List<Plaque> all, int worth, long onDay, String farmer, BlockPos field) {
        UUID id = v.id();
        Ledger.note(id, "culture.harvest", worth + "\t" + onDay + "\t" + farmer);
        String[] lines = { "Record harvest", worth + " coins' worth", "day " + (onDay + 1), farmer + "'s field" };
        for (int i = 0; i < all.size(); i++) {
            Plaque p = all.get(i);
            if (p.site() != Site.HARVEST) continue;
            all.set(i, new Plaque(p.site(), p.at(), p.wall(), lines, p.up(), p.day()));
            if (p.up() && level.getBlockEntity(p.at()) instanceof SignBlockEntity s) TownLife.write(s, lines);
            return true;
        }
        all.add(new Plaque(Site.HARVEST, field, false, lines, false, onDay + 1));
        Villages.tell(id, onDay + 1, "the town brought in its record harvest, " + worth + " coins' worth in a day, from " + farmer + "'s field");
        return true;
    }

    // ------------------------------------------------------------------ putting them up

    /** The town's look at its plaques (Culture, each second, by day): what is to be marked, and the next put up. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime(), day = level.getDayTime() / 24000L;
        if (LOOKED.getOrDefault(id, -1L) != day) {
            LOOKED.put(id, day);
            look(level, v, day);
        }
        Long due = DUE.get(id);
        if (due != null && now < due) return;
        long t = level.getDayTime() % 24000L;
        boolean byDay = t < 12500L || t >= 23500L;
        DUE.put(id, now + (byDay && putUp(level, v) ? 100L : 1200L));
    }

    /** The next plaque wanted put up: a sign on its wall, or a post and a sign on open ground. True while there is more to do. */
    static boolean putUp(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<Plaque> all = plaques(id);
        for (int i = 0; i < all.size(); i++) {
            Plaque p = all.get(i);
            if (p.up()) continue;
            Culture.Spot spot = p.wall() ? wallSpot(level, id, p) : groundSpot(level, v, p.at());
            if (spot == null) {
                SHORT.put(id, "a place for the plaque at " + where(p));
                continue;
            }
            boolean sign = Heraldry.signInStores(level, v);
            boolean post = p.wall() || Crafts.stock(level, v, s -> s.is(ItemTags.WOODEN_FENCES)) > 0
                || Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS)) >= 4;
            if (!sign || !post) {
                SHORT.put(id, p.wall() ? "a sign (or two planks)" : "a sign and a post (or four planks)");
                return false;
            }
            SHORT.remove(id);
            BlockPos work = p.wall() ? spot.at() : spot.at().above();
            if (!TownJobs.atWork(level, v, "plaques", work, "putting up a plaque at " + where(p))) return true;
            if (!place(level, v, p, spot, false)) return false;
            all.set(i, new Plaque(p.site(), spot.at(), p.wall(), p.lines(), true, p.day()));
            save(id, all);
            Villages.tell(id, level.getDayTime() / 24000L, "a plaque was put up at " + where(p));
            return true;
        }
        return false;
    }

    /** The plaque made and set: out of the stores, unless free. */
    private static boolean place(ServerLevel level, Villages.Village v, Plaque p, Culture.Spot spot, boolean free) {
        if (p.wall()) {
            BlockState s = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, spot.facing());
            if (!s.canSurvive(level, spot.at())) return false;
            if (!free && !Crafts.sign(level, v)) return false;
            level.setBlock(spot.at(), s, 3);
            if (level.getBlockEntity(spot.at()) instanceof SignBlockEntity e) TownLife.write(e, p.lines());
            return true;
        }
        if (!free) {
            if (!Crafts.fence(level, v)) return false;
            if (!Crafts.sign(level, v)) {
                Crafts.giveBack(level, v, net.minecraft.world.item.Items.OAK_FENCE, 1);
                return false;
            }
        }
        BlockPos post = spot.at(), top = post.above();
        level.setBlock(post, Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(top, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION,
            RotationSegment.convertToSegment(spot.facing())), 3);
        if (level.getBlockEntity(top) instanceof SignBlockEntity e) TownLife.write(e, p.lines());
        return true;
    }

    /** What a plaque marks, in a few words. */
    static String where(Plaque p) {
        return switch (p.site()) {
            case FOUNDING -> "the spot where the town was founded";
            case FIRST_HOUSE -> "the town's first house";
            case FELL -> "the place where " + p.lines()[1] + " fell";
            case HARVEST -> "the field of the record harvest";
            case MEMORIAL -> "the war memorial";
            case LEGACY -> "the plaque for " + p.lines()[0];
        };
    }

    /** A plaque on a building: on its front wall beside the door, at eye height. */
    @Nullable
    private static Culture.Spot wallSpot(ServerLevel level, UUID village, Plaque p) {
        Ledger.Building b = null;
        for (Ledger.Building x : Ledger.buildings(village)) if (x.anchor().equals(p.at())) { b = x; break; }
        if (b == null) return null;
        for (Culture.Spot s : Culture.front(level, b, 1, new int[]{ -2, 2, -3, 3, -1, 1 }, false)) {
            BlockState here = level.getBlockState(s.at());
            if (here.isAir() || here.getBlock() instanceof WallSignBlock && p.up()) return s;
        }
        return null;
    }

    /**
     * A place on open ground for a post and its sign, as near its mark as can be: the ground solid, two cells
     * of air over it, not on a street, a worn path, the square's middle or a doorway, nor where a thing stands.
     * Facing the heart of the town.
     */
    @Nullable
    static Culture.Spot groundSpot(ServerLevel level, Villages.Village v, BlockPos mark) {
        BlockPos c = v.centre();
        for (int r = 0; r <= 5; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = mark.getX() + dx, z = mark.getZ() + dz;
                    int ox = x - c.getX(), oz = z - c.getZ();
                    if (TownPlan.isStreet(ox, oz)) continue;                       // never on a street
                    if (Math.abs(ox) <= 2 && Math.abs(oz) <= 2) continue;          // nor in the square's very middle
                    BlockPos f = Watch.floorAt(level, x, z, mark.getY());
                    if (f == null || !free(level, f)) continue;
                    Direction face = Direction.getNearest(c.getX() - x, 0, c.getZ() - z);
                    if (face.getAxis() == Direction.Axis.Y) face = Direction.NORTH;
                    return new Culture.Spot(f.immutable(), face);
                }
            }
        }
        return null;
    }

    /** Free for a post: air, and air over it; on firm ground that is not a path; no door beside it. */
    private static boolean free(ServerLevel level, BlockPos f) {
        if (!level.getBlockState(f).isAir() || !level.getBlockState(f.above()).isAir()) return false;
        BlockState under = level.getBlockState(f.below());
        if (!under.isSolid() || under.is(Blocks.DIRT_PATH) || under.is(Blocks.FARMLAND) || under.is(BlockTags.LEAVES)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState n = level.getBlockState(f.relative(d));
            if (n.getBlock() instanceof DoorBlock || n.getBlock() instanceof FenceBlock || level.getBlockState(f.relative(d).below()).is(Blocks.DIRT_PATH)) {
                return false;
            }
        }
        return true;
    }

    /**
     * [perks] For the pictures (PerksStage): every plaque of this kind not yet up, put up at once out of nothing,
     * as the showcase's buildings are. The spot of the last one put up, or null.
     */
    @Nullable
    static Culture.Spot upForStage(ServerLevel level, Villages.Village v, Site site) {
        List<Plaque> all = plaques(v.id());
        Culture.Spot last = null;
        for (int i = 0; i < all.size(); i++) {
            Plaque p = all.get(i);
            if (p.up() || p.site() != site) continue;
            Culture.Spot spot = p.wall() ? wallSpot(level, v.id(), p) : groundSpot(level, v, p.at());
            if (spot == null || !place(level, v, p, spot, true)) continue;
            all.set(i, new Plaque(p.site(), spot.at(), p.wall(), p.lines(), true, p.day()));
            last = spot;
        }
        save(v.id(), all);
        return last;
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: what the town wants marked looked at now, and every plaque the stores run to put up. The plaques. */
    public static List<Plaque> putForTests(ServerLevel level, Villages.Village v) {
        look(level, v, level.getDayTime() / 24000L);
        for (int i = 0; i < 8; i++) if (!putUp(level, v)) break;
        return plaques(v.id());
    }

    /** Tests: a record harvest of so much, on this day, in this farmer's field (as the books would have it). */
    public static void harvestForTests(ServerLevel level, Villages.Village v, int worth, long onDay, String farmer, BlockPos field) {
        List<Plaque> all = plaques(v.id());
        record(level, v, all, worth, onDay, farmer, field);
        Culture.rows(v.id(), NOTE, toRows(all));
    }

    private static List<String[]> toRows(List<Plaque> all) {
        List<String[]> rows = new ArrayList<>();
        for (Plaque p : all) rows.add(p.row());
        return rows;
    }

    /** Tests: what the plaques wait on, in words. */
    @Nullable
    public static String shortForTests(UUID village) {
        return SHORT.get(village);
    }

    // ------------------------------------------------------------------ what the player reads

    /** The Culture page's plaques: what each marks, its words, where it stands (or what it waits on). */
    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        List<String> lines = new ArrayList<>();
        for (Plaque p : plaques(id)) {
            String words = String.join(" ", p.lines()).trim().replaceAll(" +", " ");
            lines.add(Culture.capital(where(p)) + ": “" + words + "”" + (p.up()
                ? " — at " + p.at().getX() + ", " + p.at().getY() + ", " + p.at().getZ() : " — to be put up"));
        }
        if (lines.isEmpty()) lines.add("none yet: the first goes up at the founding spot once the town is a day old");
        String s = SHORT.get(id);
        if (s != null) lines.add("waiting on " + s);
        out.put("lines", Culture.strings(lines));
        return out;
    }
}
