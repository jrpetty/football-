package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [roles-d] The outdoor trades' own places in the town: the hunter's lodge, the rancher's barn, and the scouts' desk.
 * <ul>
 * <li><b>The hunter's lodge</b> (blueprints/huntlodge.txt): a timber cabin at the town's edge, built once a town of ten
 *     keeps a hunter. A smoker at the back is its smoking rack, a chest its makings (arrows, string and the smoker's coal,
 *     kept topped up from the stores by the couriers: WorkStock), a barrel for the hides, a hide rug and a drying fire by
 *     the door. With a pack heavy with game (or any at all on its way home in the afternoon) the hunter walks in, takes
 *     out what the smoker has done (into its pack, and so to the stores), loads its raw game into it with coal from the
 *     chest, and tops up its arrows. The game's own smoker does the smoking, so nothing is made from nothing; with no coal
 *     the meat goes to the stores raw as before.</li>
 * <li><b>The rancher's barn</b> (blueprints/barn.txt): a timber barn with a hay loft, built near the pen once a town of
 *     twelve has a pen and a rancher. Its chest keeps the herd's feed (wheat, seeds, carrots) and a spare pair of shears
 *     (WorkStock); a rancher short of feed or without shears walks over for them before it goes to the stores.</li>
 * <li><b>The scouts' desk</b>: once the map room stands, a scout home from the land writes its finds up at the
 *     cartographer's table (Scouts.work) instead of standing at the board.</li>
 * </ul>
 * Cost: a hunter or rancher looks once every {@link #LOOK_EVERY} ticks (a map read between), and walks only when there
 * is work there; an errand is given up after {@link #ERRAND_FOR}.
 */
public final class OutdoorBases {

    private OutdoorBases() {}

    /** The buildings. */
    public static final String LODGE = "huntlodge", BARN = "barn";
    /** A town of so many with a hunter builds the lodge; with a pen and a rancher, the barn. */
    public static final int LODGE_FROM = 10, BARN_FROM = 12;
    /** How often a hunter or rancher looks at its base's work, and how long an errand there may take (ticks). */
    static final long LOOK_EVERY = 200L, ERRAND_FOR = 900L;
    /** Game meat carried that sends a hunter to the smoker at any hour; after this hour any at all does (its way home). */
    static final int LOAD_AT = 6;
    static final long HOMEWARD = 9000L;
    /** Coal into the smoker at a time, and the arrows a hunter with a bow keeps. */
    static final int FUEL_AT_ONCE = 4, ARROWS_KEPT = 16;
    /** Feed a rancher fetches from the barn, and the least it carries before it goes. */
    public static final int FEED_TAKE = 16, FEED_LOW = 6;

    /** What goes in the smoker, what burns in it, and the herd's feed. */
    static final Predicate<ItemStack> GAME = OutdoorTalk.GAME;
    static final Predicate<ItemStack> FUEL = s -> s.is(ItemTags.COALS);
    static final Predicate<ItemStack> FEED = s -> s.is(Items.WHEAT) || s.is(Items.WHEAT_SEEDS) || s.is(Items.CARROT) || s.is(Items.POTATO)
        || s.is(Items.BEETROOT) || s.is(Items.BEETROOT_SEEDS);

    /** An errand at a base: the hunter to the smoker, the rancher to the barn's chest; where, and since when. */
    record Errand(StationTask trade, BlockPos at, @Nullable BlockPos chest, long since) {}

    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** Game put in a town's smoker and smoked meat taken out, all told (kept in the ledger). */
    private static final Map<UUID, int[]> SMOKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ERRANDS.clear();
        LOOKED.clear();
        SMOKED.clear();
    }

    // ------------------------------------------------------------------ the buildings

    /** A trade's base out on its ground (where its makings chest is: WorkStock), or null. */
    @Nullable
    public static String baseOf(StationTask t) {
        return switch (t) {
            case HUNT -> LODGE;
            case RANCH -> BARN;
            default -> null;
        };
    }

    /** The words for a base ("Hunter's lodge"), for its chest's name; null for any other building. */
    @Nullable
    public static String label(String structure) {
        return switch (structure) {
            case LODGE -> "Hunter's lodge";
            case BARN -> "Barn";
            default -> null;
        };
    }

    private static boolean keeps(UUID village, StationTask t) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isBaby() && f.stationTask() == t) return true;
        }
        return false;
    }

    /** Does the town want its hunter's lodge: ten folk, a hunter, and none standing? */
    public static boolean lodgeWanted(@Nullable UUID village) {
        return village != null && Villages.headcount(village) >= LODGE_FROM && !Villages.hasBuilt(village, LODGE)
            && Villages.builtAt(village, LODGE) == null && keeps(village, StationTask.HUNT);
    }

    /** Does the town want a barn: twelve folk, its pen built, a rancher, and none standing? */
    public static boolean barnWanted(@Nullable UUID village) {
        return village != null && Villages.headcount(village) >= BARN_FROM && !Villages.hasBuilt(village, BARN)
            && Villages.builtAt(village, BARN) == null && Drover.pen(village) != null && keeps(village, StationTask.RANCH);
    }

    public static String why(String structure) {
        return structure.equals(LODGE)
            ? "a hunter's lodge, its smoking rack and its hides: the hunter's game smoked, and its arrows kept"
            : "a barn by the pen, a loft of hay and the herd's feed kept dry";
    }

    @Nullable
    static Ledger.Building building(ServerLevel level, UUID village, String structure) {
        Ledger.Building b = Visitors.building(village, structure);
        return b == null || !level.isLoaded(b.anchor()) ? null : b;
    }

    /** A part of a building's drawing (the lodge's smoker), where it stands, or null. */
    @Nullable
    static BlockPos part(Ledger.Building b, BuildGoal.Part part) {
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) if (p.part() == part) return p.pos();
        return null;
    }

    // ------------------------------------------------------------------ the hunter at its lodge

    /**
     * The hunter's turn at the lodge (VillageFolkEntity.huntWork): looked at every few seconds; with game to smoke or the
     * smoker's work to take out, it walks there, and at the smoker does both. True while it is about it.
     */
    public static boolean hunterDuty(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || OutdoorTalk.partying(f)) return false;
        Errand e = ERRANDS.get(f.getUUID());
        long now = level.getGameTime();
        if (e == null) {
            if (!due(f, now)) return false;
            Ledger.Building lodge = building(level, id, LODGE);
            BlockPos smoker = lodge == null ? null : part(lodge, BuildGoal.Part.SMOKER);
            if (smoker == null || !(level.getBlockEntity(smoker) instanceof AbstractFurnaceBlockEntity sm)) return false;
            if (!smokerWants(f, level, sm, WorkStock.chestOf(level, id, StationTask.HUNT))) return false;
            e = new Errand(StationTask.HUNT, smoker, WorkStock.chestOf(level, id, StationTask.HUNT), now);
            ERRANDS.put(f.getUUID(), e);
            f.brain("to the lodge: the smoker");
        }
        if (!walk(f, e, now)) return ERRANDS.containsKey(f.getUUID());
        if (level.getBlockEntity(e.at()) instanceof AbstractFurnaceBlockEntity sm) atSmoker(f, level, id, sm, e.chest());
        ERRANDS.remove(f.getUUID());
        return true;
    }

    private static boolean due(VillageFolkEntity f, long now) {
        Long last = LOOKED.get(f.getUUID());
        if (last != null && now - last < LOOK_EVERY && now >= last) return false;
        LOOKED.put(f.getUUID(), now);
        return true;
    }

    /** Smoked meat waiting in it, or game to put in (with coal in it or in the chest, and room in its input). */
    static boolean smokerWants(VillageFolkEntity f, ServerLevel level, AbstractFurnaceBlockEntity sm, @Nullable BlockPos chest) {
        if (!sm.getItem(2).isEmpty()) return true;
        int game = f.countCarried(GAME);
        long tod = level.getDayTime() % 24000L;
        if (game < LOAD_AT && (game == 0 || tod < HOMEWARD)) return false;
        ItemStack in = sm.getItem(0);
        if (!in.isEmpty() && (!GAME.test(in) || in.getCount() >= in.getMaxStackSize())) return false;
        return !sm.getItem(1).isEmpty() || chest != null && level.getBlockEntity(chest) instanceof Container c && count(c, FUEL) > 0;
    }

    /** Walking to the errand's place: true once there; given up (false, the errand gone) if it takes too long. */
    private static boolean walk(VillageFolkEntity f, Errand e, long now) {
        if (now - e.since() > ERRAND_FOR) {
            ERRANDS.remove(f.getUUID());
            f.brain("could not get to the " + (e.trade() == StationTask.HUNT ? "lodge" : "barn") + "; left it");
            return false;
        }
        if (f.distanceToSqr(e.at().getX() + 0.5, e.at().getY(), e.at().getZ() + 0.5) > 2.6 * 2.6) {
            if (f.getNavigation().isDone() || (now - e.since()) % 60L == 0L) f.walkTo(e.at(), 1.0D);
            return false;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(e.at().getX() + 0.5, e.at().getY() + 0.5, e.at().getZ() + 0.5);
        return true;
    }

    /**
     * At the smoker: what is smoked out into its pack; its raw game in, one kind to a load, with coal from the lodge's
     * chest under it; and its arrows topped up from the chest. What will not fit stays where it was.
     */
    static void atSmoker(VillageFolkEntity f, ServerLevel level, UUID village, AbstractFurnaceBlockEntity sm, @Nullable BlockPos chestAt) {
        int[] books = SMOKED.computeIfAbsent(village, OutdoorBases::load);
        Container chest = chestAt != null && level.getBlockEntity(chestAt) instanceof Container c ? c : null;
        // The smoked meat out.
        ItemStack done = sm.getItem(2);
        int out = 0;
        if (!done.isEmpty()) {
            ItemStack left = f.insertItem(done.copy());
            out = done.getCount() - left.getCount();
            sm.setItem(2, left);
        }
        // Coal under it.
        if (sm.getItem(1).isEmpty() && chest != null) {
            List<ItemStack> coal = take(chest, FUEL, FUEL_AT_ONCE);
            if (!coal.isEmpty()) sm.setItem(1, coal.get(0));
        }
        // The raw game in: the kind already in, or the kind it carries most of.
        int in = 0;
        if (!sm.getItem(1).isEmpty()) {
            ItemStack slot = sm.getItem(0);
            ItemStack kind = slot.isEmpty() ? most(f) : slot;
            if (!kind.isEmpty()) {
                int room = kind.getMaxStackSize() - slot.getCount();
                int n = Math.min(room, f.countCarried(s -> ItemStack.isSameItemSameComponents(s, kind)));
                if (n > 0 && f.removeMatching(s -> ItemStack.isSameItemSameComponents(s, kind), n) == n) {
                    sm.setItem(0, kind.copyWithCount(slot.getCount() + n));
                    in = n;
                }
            }
        }
        // Arrows for the bow.
        if (chest != null && f.hasBow()) {
            int want = ARROWS_KEPT - f.countCarried(s -> s.is(Items.ARROW));
            for (ItemStack s : take(chest, st -> st.is(Items.ARROW), Math.max(0, want))) {
                ItemStack left = f.insertItem(s);
                if (!left.isEmpty()) chest.setItem(firstFree(chest), left);
            }
        }
        sm.setChanged();
        books[0] += in;
        books[1] += out;
        Ledger.note(village, "outdoor.smoked", books[0] + "," + books[1]);
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, sm.getBlockPos(), SoundEvents.SMOKER_SMOKE, SoundSource.BLOCKS, 0.6F, 1.0F);
        f.brain("at the lodge's smoker: " + (in > 0 ? in + " game in" : "nothing to put in") + (out > 0 ? ", " + out + " smoked out" : "")
            + (sm.getItem(1).isEmpty() && in == 0 && f.countCarried(GAME) > 0 ? " (no coal in the chest: the game goes to the stores raw)" : ""));
        if (in > 0 && f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Into the smoke with you. It'll keep the winter through.", "A good haul for the rack."));
        }
    }

    /** The game it carries most of, one of it as a sample; empty if none. */
    private static ItemStack most(VillageFolkEntity f) {
        ItemStack best = ItemStack.EMPTY;
        int most = 0;
        for (ItemStack s : f.getInventoryItems()) {
            if (s.isEmpty() || !GAME.test(s)) continue;
            int n = f.countCarried(x -> ItemStack.isSameItemSameComponents(x, s));
            if (n > most) { most = n; best = s.copyWithCount(1); }
        }
        return best;
    }

    private static int[] load(UUID village) {
        String s = Ledger.note(village, "outdoor.smoked");
        int[] out = new int[2];
        if (s == null || s.isEmpty()) return out;
        String[] p = s.split(",");
        try {
            out[0] = Integer.parseInt(p[0].trim());
            out[1] = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
        } catch (NumberFormatException ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ the rancher at its barn

    /**
     * The rancher's turn at the barn (VillageFolkEntity.cullWork): short of feed (or without shears) and the barn's chest
     * holding some, it walks over and takes what it wants. True while it is about it.
     */
    public static boolean rancherDuty(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null) return false;
        Errand e = ERRANDS.get(f.getUUID());
        long now = level.getGameTime();
        if (e == null) {
            if (!due(f, now)) return false;
            boolean feed = f.countCarried(FEED) < FEED_LOW, shears = f.countCarried(s -> s.is(Items.SHEARS)) == 0;
            if (!feed && !shears) return false;
            BlockPos at = WorkStock.chestOf(level, id, StationTask.RANCH);
            if (at == null || !(level.getBlockEntity(at) instanceof Container c)) return false;
            if (!(feed && count(c, FEED) > 0 || shears && count(c, s -> s.is(Items.SHEARS)) > 0)) return false;
            e = new Errand(StationTask.RANCH, at, at, now);
            ERRANDS.put(f.getUUID(), e);
            f.brain("to the barn: the herd's feed");
        }
        if (!walk(f, e, now)) return ERRANDS.containsKey(f.getUUID());
        ERRANDS.remove(f.getUUID());
        if (!(level.getBlockEntity(e.at()) instanceof Container c)) return true;
        int fed = 0;
        for (ItemStack s : take(c, FEED, Math.max(0, FEED_TAKE - f.countCarried(FEED)))) {
            ItemStack left = f.insertItem(s);
            fed += s.getCount() - left.getCount();
            if (!left.isEmpty()) c.setItem(firstFree(c), left);
        }
        boolean gotShears = false;
        if (f.countCarried(st -> st.is(Items.SHEARS)) == 0) {
            for (ItemStack s : take(c, st -> st.is(Items.SHEARS), 1)) {
                ItemStack left = f.insertItem(s);
                gotShears = left.isEmpty();
                if (!left.isEmpty()) c.setItem(firstFree(c), left);
            }
        }
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, e.at(), SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
        f.brain("at the barn: " + fed + " feed" + (gotShears ? " and the spare shears" : "") + " for the herd");
        return true;
    }

    /** Off on an errand to its base (its patch's leash let go: VillageFolkEntity.walksAbroad)? */
    public static boolean abroad(VillageFolkEntity f) {
        return !ERRANDS.isEmpty() && ERRANDS.containsKey(f.getUUID());
    }

    // ------------------------------------------------------------------ the scouts' desk

    /** Where a scout stands to write up its finds (beside the map room's table), and the table; or null with no map room. */
    public record Desk(BlockPos stand, BlockPos table) {}

    @Nullable
    public static Desk scoutDesk(ServerLevel level, UUID village) {
        BlockPos table = Cartographers.tableAt(level, village);
        if (table == null) return null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos s = table.relative(d);
            if (level.getBlockState(s).isAir() && level.getBlockState(s.above()).isAir() && !level.getBlockState(s.below()).isAir()) {
                return new Desk(s, table);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ chests

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    private static List<ItemStack> take(Container c, Predicate<ItemStack> what, int n) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize() && n > 0; i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int k = Math.min(n, s.getCount());
            out.add(s.split(k));
            n -= k;
        }
        if (!out.isEmpty()) c.setChanged();
        return out;
    }

    private static int firstFree(Container c) {
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) return i;
        return 0;
    }

    // ------------------------------------------------------------------ the books and the tests

    /** The bases in words (/village outdoor): the lodge and what its smoker has done, the barn, the scouts' desk. */
    public static List<String> lines(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Ledger.Building lodge = Visitors.building(village, LODGE);
        int[] smoked = SMOKED.computeIfAbsent(village, OutdoorBases::load);
        out.add(lodge == null ? (lodgeWanted(village) ? "Hunter's lodge: wanted (" + why(LODGE) + ")" : "Hunter's lodge: none")
            : "Hunter's lodge at " + lodge.anchor().toShortString() + ": " + smoked[0] + " game into the smoker, " + smoked[1] + " smoked out");
        Ledger.Building barn = Visitors.building(village, BARN);
        out.add(barn == null ? (barnWanted(village) ? "Barn: wanted (" + why(BARN) + ")" : "Barn: none")
            : "Barn at " + barn.anchor().toShortString());
        Desk desk = scoutDesk(level, village);
        out.add(desk == null ? "Scouts' desk: none (the map room's table)" : "Scouts' desk: the map room's table at " + desk.table().toShortString());
        return out;
    }

    /** Tests: the hunter's look at the lodge now (no wait), and its errand run through if it is already there. */
    public static boolean hunterForTests(VillageFolkEntity f, ServerLevel level) {
        LOOKED.remove(f.getUUID());
        return hunterDuty(f, level);
    }

    /** Tests: the rancher's look at the barn now (no wait). */
    public static boolean rancherForTests(VillageFolkEntity f, ServerLevel level) {
        LOOKED.remove(f.getUUID());
        return rancherDuty(f, level);
    }
}
