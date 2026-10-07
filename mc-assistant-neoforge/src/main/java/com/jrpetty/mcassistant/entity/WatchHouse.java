package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [police] The watch house, the town's police station, and the prisoners in its keeping.
 *
 * <p>From the Stone Age, a town that keeps a watch of three builds one (watchhouse.txt): a front desk, the records
 * shelf, a notice board of the wanted, and two cells with a bed apiece. The watch fits it out itself: the cells' fronts
 * are put up as fence posts and a wooden door, and as soon as the town can spare the iron (six ingots for sixteen bars,
 * six for three doors) the posts give way to iron bars and the doors to iron ones, which nobody inside can open; the
 * smith forges them if the town has one (Crafts.smith), else they are cast at the watch house out of the ingots. The
 * notice board is three of the stores' signs (the wanted, and the day's roster), written up as they change, and the
 * casebook on the desk's lectern is a book of the stores' paper, ink and quill, written up every day with the week.
 *
 * <p>A culprit the watch arrests is walked to it on a lead (one of the stores', else held by the arm) and locked in a
 * cell to wait for the council, with a bed, and its dinner out of the stores at the town's cost. When the council sits
 * on its case the watch walks it there on the lead and stands by; sentenced to the cells it is walked back and serves
 * its time there (a day or two), and at the end the door is opened for it with a word. Its family may stand bail out of
 * their own purse in the morning (given back when the case is heard). Now and then a prisoner breaks out (rarely from
 * iron, never with a guard sitting up with it), and the watch is after it. A town with no watch house holds its
 * prisoners at the hall till the council has heard them, on their word. Kept with the world (Police.custody).
 */
final class WatchHouse {

    private WatchHouse() {}

    static final String STRUCTURE = "watchhouse";
    /** A watch this big wants a house of its own. */
    static final int GUARDS = 3;

    /** One cell: where a prisoner stands, its bed (foot and head), its door, the passage before it, and its fronts' bars. */
    record Cell(int index, BlockPos inside, BlockPos bedFoot, BlockPos bedHead, BlockPos door, BlockPos front, List<BlockPos> bars) {}

    /** The cells in the drawing's terms (across, up, toward the back): watchhouse.txt. */
    private static final int[][][] CELLS = {
        { { -2, 0, 2 }, { -3, 0, 2 }, { -3, 0, 3 }, { -2, 0, 1 }, { -2, 0, 0 }, { -3, 0, 1 }, { -1, 0, 1 }, { -3, 1, 1 }, { -1, 1, 1 } },
        { { 2, 0, 2 }, { 3, 0, 2 }, { 3, 0, 3 }, { 2, 0, 1 }, { 2, 0, 0 }, { 1, 0, 1 }, { 3, 0, 1 }, { 1, 1, 1 }, { 3, 1, 1 } } };
    /** The front desk's lectern, where the desk's guard stands behind the counter, and where a visitor stands before it. */
    static final int[] LECTERN = { 2, 0, -1 }, DESK = { 3, 0, -1 }, COUNTER = { 2, 0, -3 }, INSIDE = { 0, 0, -3 }, OUTSIDE = { 0, 0, -5 };
    /** The notice board: two signs of the wanted on the right wall, the roster on the left. */
    static final int[] WANTED_A = { 3, 1, -3 }, WANTED_B = { 3, 1, -1 }, ROSTER = { -3, 1, -3 };

    /** By guard: the prisoner it has in hand. */
    private static final Map<UUID, UUID> ESCORTS = new ConcurrentHashMap<>();
    /** Doors to be shut again, by where they are: when. */
    private static final Map<BlockPos, Long> TO_SHUT = new ConcurrentHashMap<>();
    /** A step's start, by prisoner: when it was led in or let out (the walk into a cell, the release). */
    private static final Map<UUID, Long> STEP = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PATHED = new ConcurrentHashMap<>();
    /** The notice board as last written, by town (so it is written again only when it changes). */
    private static final Map<UUID, String> BOARD = new ConcurrentHashMap<>();
    /** The last fitting looked for, by town. */
    private static final Map<UUID, Long> FITTED = new ConcurrentHashMap<>();

    static void resetForTests() {
        ESCORTS.clear();
        TO_SHUT.clear();
        STEP.clear();
        PATHED.clear();
        BOARD.clear();
        FITTED.clear();
    }

    // ------------------------------------------------------------------ the building

    /** Does the town want a watch house: the Stone Age, a watch of three, and none yet? */
    static boolean wanted(UUID village) {
        if (Villages.ageOf(village).ordinal() < Villages.Age.STONE.ordinal()) return false;
        return Patrols.watch(village).size() >= GUARDS && Villages.builtStructure(village, STRUCTURE) == null;
    }

    static String why(UUID village) {
        return "The watch is " + Patrols.watch(village).size() + " strong and has nowhere of its own: a watch house, with a front desk for "
            + "folk to report to, a casebook, a notice board of the wanted, and two cells to hold a prisoner till the council sits.";
    }

    /** The town's watch house, standing (not still going up), or null. */
    @Nullable
    static Ledger.Building of(@Nullable UUID village) {
        if (village == null) return null;
        Ledger.Building b = Villages.builtStructure(village, STRUCTURE);
        if (b == null || Ledger.raising(village, b.anchor())) return null;
        return b;
    }

    /** A spot of the drawing in the world. */
    static BlockPos at(Ledger.Building b, int[] c) {
        return at(b, c[0], c[1], c[2]);
    }

    static BlockPos at(Ledger.Building b, int across, int up, int back) {
        Direction right = b.facing().getClockWise();
        return b.anchor().relative(right, across).relative(b.facing(), back).above(up);
    }

    static List<Cell> cells(Ledger.Building b) {
        List<Cell> out = new ArrayList<>();
        for (int i = 0; i < CELLS.length; i++) {
            int[][] c = CELLS[i];
            List<BlockPos> bars = new ArrayList<>();
            for (int k = 5; k < c.length; k++) bars.add(at(b, c[k]));
            out.add(new Cell(i, at(b, c[0]), at(b, c[1]), at(b, c[2]), at(b, c[3]), at(b, c[4]), bars));
        }
        return out;
    }

    @Nullable
    static Cell cell(Ledger.Building b, int index) {
        List<Cell> all = cells(b);
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    /** A cell with nobody in it (or on the way to it), or null. */
    @Nullable
    static Cell freeCell(ServerLevel level, UUID village) {
        Ledger.Building b = of(village);
        if (b == null || !level.isLoaded(b.anchor())) return null;
        for (Cell c : cells(b)) {
            if (occupied(village, c.index())) continue;
            if (PlayerLaw.cellTaken(village, c.index())) continue;
            return c;
        }
        return null;
    }

    static boolean occupied(UUID village, int cell) {
        CompoundTag all = Police.custody();
        for (String key : all.getAllKeys()) {
            CompoundTag t = all.getCompound(key);
            if (t.hasUUID("village") && t.getUUID("village").equals(village) && t.getInt("cell") == cell && !t.getString("state").equals("HALL")) return true;
        }
        return false;
    }

    /** Is the cell's front iron (bars and the iron door), as the watch fits it? */
    static boolean ironClad(ServerLevel level, Cell c) {
        if (!level.getBlockState(c.door()).is(Blocks.IRON_DOOR)) return false;
        for (BlockPos p : c.bars()) if (!level.getBlockState(p).is(Blocks.IRON_BARS)) return false;
        return true;
    }

    // ------------------------------------------------------------------ custody, kept with the world

    @Nullable
    static CompoundTag custodyOf(UUID folk) {
        CompoundTag all = Police.custody();
        String key = folk.toString();
        return all.contains(key) ? all.getCompound(key) : null;
    }

    /** How many of the town's folk the watch holds. */
    static int count(UUID village) {
        int n = 0;
        CompoundTag all = Police.custody();
        for (String key : all.getAllKeys()) {
            CompoundTag t = all.getCompound(key);
            if (t.hasUUID("village") && t.getUUID("village").equals(village)) n++;
        }
        return n;
    }

    /** The prisoner a guard has in hand just now, or null. */
    @Nullable
    static UUID escorting(VillageFolkEntity g) {
        return ESCORTS.get(g.getUUID());
    }

    private static void free(ServerLevel level, VillageFolkEntity f, @Nullable VillageFolkEntity g) {
        CompoundTag t = custodyOf(f.getUUID());
        unlead(level, f, g, t);
        Police.custody().remove(f.getStringUUID());
        ESCORTS.values().removeIf(u -> u.equals(f.getUUID()));
        STEP.remove(f.getUUID());
        if (f.isSleeping()) f.stopSleeping();
        if (f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
        Police.changed();
    }

    // ------------------------------------------------------------------ the arrest

    /**
     * A folk taken into the watch's keeping by a guard: on a lead (the guard's, or one of the stores'; else by the arm)
     * to a cell of the watch house, or with none to be had, to the hall to wait for the council on its word. {@code until}
     * is the day time its time is up (a sentence, a night's lock-up), or -1 to wait for the council.
     */
    static boolean arrest(ServerLevel level, Villages.Village v, VillageFolkEntity guard, VillageFolkEntity f, int caseId, String why, long until) {
        if (custodyOf(f.getUUID()) != null) return false;
        Cell cell = freeCell(level, v.id());
        CompoundTag t = new CompoundTag();
        t.putUUID("village", v.id());
        t.putString("name", f.displayNameCap());
        t.putString("state", cell != null ? "LED" : "HALL");
        t.putInt("cell", cell != null ? cell.index() : -1);
        t.putInt("case", caseId);
        t.putString("why", why);
        t.putLong("since", level.getDayTime());
        t.putLong("until", until);
        t.putUUID("escort", guard.getUUID());
        t.putUUID("by", guard.getUUID());
        Police.custody().put(f.getStringUUID(), t);
        ESCORTS.put(guard.getUUID(), f.getUUID());
        Mischief.PLANS.remove(f.getUUID());
        f.clearQueue();
        f.getNavigation().stop();
        if (f.isSleeping()) f.stopSleeping();
        lead(level, v, guard, f, t);
        CompoundTag r = Police.folk(f.getUUID());
        r.putInt("arrested", r.getInt("arrested") + 1);
        Police.count(guard, "arrests", 1);
        long now = level.getDayTime();
        String where = cell != null ? "to the cells at the watch house" : "to the hall, to wait for the council";
        Police.log(v.id(), now, "arrest", guard.displayNameCap() + " arrested " + f.displayNameCap() + " for " + why + " and took them " + where, guard, 0);
        FolkTalk.speak(guard, FolkTalk.pick(guard.getRandom(), "You're coming with me, " + f.displayNameCap() + ".",
            f.displayNameCap() + ", I'm taking you in. Come quietly.", "Hands where I can see them. You're under arrest."));
        f.sayLater(FolkTalk.pick(f.getRandom(), "All right, all right. I'm coming.", "You've got the wrong end of it, I tell you!", "...I'll come quietly."), 30);
        Police.changed();
        return true;
    }

    /** A night in the cells for the disorderly, no trial: out in the morning (Incidents: a third fight). */
    static boolean lockUp(ServerLevel level, Villages.Village v, VillageFolkEntity guard, VillageFolkEntity f, String why) {
        long morning = (level.getDayTime() / 24000L + 1) * 24000L + 1000L;
        if (freeCell(level, v.id()) == null) return false;
        return arrest(level, v, guard, f, 0, why, morning);
    }

    /** The prisoner on a lead: the guard's own, or one out of the stores. False if there is none (then it is held by the arm). */
    static boolean lead(ServerLevel level, Villages.Village v, VillageFolkEntity guard, VillageFolkEntity f, CompoundTag t) {
        if (f.isLeashed() && f.getLeashHolder() == guard) return true;
        if (t.getBoolean("lead") && f.isLeashed()) {
            f.dropLeash(true, false);
            f.setLeashedTo(guard, true);
            return true;
        }
        boolean got = guard.removeMatching(s -> s.is(Items.LEAD), 1) == 1 || t.getBoolean("lead") || Crafts.take(level, v, s -> s.is(Items.LEAD), 1);
        if (!got) return false;
        t.putBoolean("lead", true);
        f.setLeashedTo(guard, true);
        Police.changed();
        return true;
    }

    /** Off the lead, and the lead back in the guard's pack (or, the guard gone, the stores). */
    static void unlead(ServerLevel level, VillageFolkEntity f, @Nullable VillageFolkEntity g, @Nullable CompoundTag t) {
        boolean had = t != null && t.getBoolean("lead");
        if (f.isLeashed()) f.dropLeash(true, false);
        else if (had) {
            // The lead snapped (the prisoner too far behind): it lies on the ground by them. Picked up.
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, f.getBoundingBox().inflate(12.0), e -> e.isAlive() && e.getItem().is(Items.LEAD))) {
                e.getItem().shrink(1);
                if (e.getItem().isEmpty()) e.discard();
                break;
            }
        }
        if (!had) return;
        t.putBoolean("lead", false);
        ItemStack lead = new ItemStack(Items.LEAD);
        if (g != null) lead = g.insertItem(lead);
        if (!lead.isEmpty()) {
            Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
            if (v != null) Crafts.store(level, v, lead);
        }
    }

    // ------------------------------------------------------------------ the prisoner's part

    /** From Police.hold: a prisoner, on the lead, in its cell, or at the council (which has it then). What it is doing, or null. */
    @Nullable
    static String prisonerHold(VillageFolkEntity f, ServerLevel level) {
        CompoundTag t = custodyOf(f.getUUID());
        if (t == null) return null;
        UUID village = t.hasUUID("village") ? t.getUUID("village") : null;
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || !village.equals(f.ownerId())) {
            Police.custody().remove(f.getStringUUID());
            Police.changed();
            return null;
        }
        String state = t.getString("state");
        Ledger.Building b = of(village);
        Cell cell = b == null ? null : cell(b, t.getInt("cell"));
        if (cell == null && !state.equals("HALL") && !state.equals("COURT")) {
            // The watch house gone (pulled down, burnt): held at the hall instead.
            t.putString("state", "HALL");
            t.putInt("cell", -1);
            state = "HALL";
        }
        long now = level.getDayTime();
        switch (state) {
            case "CELL" -> {
                if (cell == null) return null;
                return inCell(level, v, f, t, cell, now);
            }
            case "COURT" -> {
                Trial.Sitting s = Trial.sitting(village);
                if (s == null || s.caseId != t.getInt("case")) {
                    // Adjourned (the verdict would have set it free or sent it back): back to the cell.
                    t.putString("state", cell != null ? "BACK" : "HALL");
                    Police.changed();
                }
                return null;                                         // the court has it (Trial.hold)
            }
            default -> {
                // On the lead: it keeps up with its guard; with nobody to walk it, it waits where it is.
                VillageFolkEntity g = t.hasUUID("escort") ? Civics.find(level, t.getUUID("escort")) : null;
                if (g == null || Raids.underAlarm(village) && g.stationTask() == AssistantEntity.StationTask.GUARD) {
                    f.getNavigation().stop();
                    if (g == null) recover(level, v, f, t, cell);
                    return "waiting under guard";
                }
                if (f.isSleeping()) f.stopSleeping();
                double d = f.distanceTo(g);
                if (d > 2.6) walk(f, g.blockPosition(), d > 6 ? 1.0D : 0.8D);
                else f.getNavigation().stop();
                f.getLookControl().setLookAt(g, 20.0F, 20.0F);
                return switch (state) {
                    case "COURT_WALK" -> "being walked to the council by the watch";
                    case "HALL" -> "being taken to the hall by the watch";
                    default -> "being walked to the cells by the watch";
                };
            }
        }
    }

    /** With its guard gone (unloaded, sent to the walls for good): straight to its cell, or let wait at the hall. */
    private static void recover(ServerLevel level, Villages.Village v, VillageFolkEntity f, CompoundTag t, @Nullable Cell cell) {
        if (Raids.underAlarm(v.id())) return;
        long since = STEP.computeIfAbsent(f.getUUID(), k -> level.getGameTime());
        if (level.getGameTime() - since < 400) return;
        STEP.remove(f.getUUID());
        unlead(level, f, null, t);
        if (cell != null && !t.getString("state").equals("COURT_WALK")) {
            f.moveTo(cell.inside().getX() + 0.5, cell.inside().getY(), cell.inside().getZ() + 0.5, f.getYRot(), 0.0F);
            setDoor(level, cell.door(), false);
            t.putString("state", "CELL");
        } else if (t.getString("state").equals("COURT_WALK")) {
            t.putString("state", "COURT");                          // it walks itself to the dock (Trial.hold)
        } else {
            t.putString("state", "HALL");
        }
        Police.changed();
    }

    /** In its cell: kept in, a bed at night, its trial, its time, its dinner. */
    private static String inCell(ServerLevel level, Villages.Village v, VillageFolkEntity f, CompoundTag t, Cell cell, long now) {
        UUID village = v.id();
        // Called to the council: the watch walks it there.
        Trial.Sitting s = Trial.sitting(village);
        if (s != null && s.caseId == t.getInt("case") && t.getInt("case") > 0) {
            if (f.isSleeping()) f.stopSleeping();
            VillageFolkEntity g = escortFor(level, v, f);
            setDoor(level, cell.door(), true);
            TO_SHUT.put(cell.door(), level.getGameTime() + 100);
            if (g != null) {
                t.putUUID("escort", g.getUUID());
                ESCORTS.put(g.getUUID(), f.getUUID());
                t.putString("state", "COURT_WALK");
                FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Up you get, " + f.displayNameCap() + ". The council's sitting.", "Time to face the council. Come on."));
            } else {
                t.putString("state", "COURT");
            }
            Police.changed();
            return "called before the council";
        }
        // Time served.
        long until = t.getLong("until");
        if (until > 0 && now >= until) {
            release(level, v, f, cell, t.getInt("case") > 0 ? "Your time's served, " + f.displayNameCap() + ". Keep out of trouble, mind."
                : "Morning, " + f.displayNameCap() + ". Sober and sorry, I hope. Off you go.", "release");
            return null;
        }
        // Kept in: a prisoner found outside its cell (after a restart, or pushed out) is put back.
        double dx = f.getX() - (cell.inside().getX() + 0.5), dz = f.getZ() - (cell.inside().getZ() + 0.5);
        if (dx * dx + dz * dz > 2.4 * 2.4 || Math.abs(f.getY() - cell.inside().getY()) > 1.5) {
            if (f.isSleeping()) f.stopSleeping();
            f.moveTo(cell.inside().getX() + 0.5, cell.inside().getY(), cell.inside().getZ() + 0.5, f.getYRot(), 0.0F);
            setDoor(level, cell.door(), false);
        }
        f.getNavigation().stop();
        long tod = Math.floorMod(now, 24000L);
        boolean night = tod >= 13500L && tod < 23200L;
        if (night) {
            if (!f.isSleeping() && level.getBlockState(cell.bedHead()).getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                f.startSleeping(cell.bedHead());
            }
            return "asleep in the cells";
        }
        if (f.isSleeping()) f.stopSleeping();
        if (f.getRandom().nextInt(400) == 0) {
            FolkTalk.speak(f, until > 0 ? FolkTalk.pick(f.getRandom(), "How long's left? It feels like a week.", "I'll not do it again, I swear.")
                : FolkTalk.pick(f.getRandom(), "When does the council sit? I want this over.", "Is anybody coming for me?"));
        }
        if (f.getRandom().nextInt(20) == 0) {
            BlockPos door = cell.door();
            f.getLookControl().setLookAt(door.getX() + 0.5, door.getY() + 1.4, door.getZ() + 0.5);
        }
        return until > 0 ? "in the cells, serving " + (t.getInt("case") > 0 ? "its sentence" : "a night for disorder") : "in the cells, waiting for the council";
    }

    /** Let out: the door opened, a word from the watch, and off it goes; its record kept. */
    static void release(ServerLevel level, Villages.Village v, VillageFolkEntity f, @Nullable Cell cell, String words, String kind) {
        VillageFolkEntity g = nearestGuard(level, v, f.blockPosition(), 24.0, null);
        if (cell != null) {
            setDoor(level, cell.door(), true);
            TO_SHUT.put(cell.door(), level.getGameTime() + 140);
        }
        free(level, f, g);
        if (g != null) {
            g.getLookControl().setLookAt(f, 30.0F, 30.0F);
            FolkTalk.speak(g, words);
        } else {
            FolkTalk.speak(f, "Free at last.");
        }
        Ledger.Building b = of(v.id());
        if (b != null) f.walkTo(at(b, OUTSIDE), 0.8D);
        if (Crime.known(f.getUUID()) && "jail".equals(Crime.folk(f.getUUID()).getString("sentence"))) {
            Crime.folk(f.getUUID()).remove("sentence");
            Crime.changed();
        }
        Police.log(v.id(), level.getDayTime(), kind, f.displayNameCap() + " was let out of the cells" + (kind.equals("bail") ? " on bail" : ""), g, 0);
        f.persona().remember(level.getDayTime() / 24000L, "I was let out of the cells at the watch house", 3);
        f.refreshMood();
    }

    /** The guard to walk a prisoner: the escort's, else the desk's, else the nearest free one. */
    @Nullable
    static VillageFolkEntity escortFor(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        for (Roster.Duty d : new Roster.Duty[]{ Roster.Duty.ESCORT, Roster.Duty.DESK }) {
            for (VillageFolkEntity g : Roster.on(level, v, d)) if (freeFor(g)) return g;
        }
        return nearestGuard(level, v, f.blockPosition(), 64.0, null);
    }

    static boolean freeFor(VillageFolkEntity g) {
        return g.isAlive() && !g.isSleeping() && !g.isBaby() && !Police.engaged(g) && !Patrols.away(g) && !g.onWatch()
            && (g.getTarget() == null || !g.getTarget().isAlive());
    }

    /** The nearest guard of the town who is free, within reach of a spot. */
    @Nullable
    static VillageFolkEntity nearestGuard(ServerLevel level, Villages.Village v, BlockPos at, double reach, @Nullable VillageFolkEntity not) {
        VillageFolkEntity best = null;
        double bd = reach * reach;
        for (VillageFolkEntity g : Patrols.watch(v.id())) {
            if (g == not || !freeFor(g)) continue;
            double d = g.blockPosition().distSqr(at);
            if (d < bd) { bd = d; best = g; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the guard's part: the escort

    /** From Police.hold: a guard with a prisoner in hand. What it is doing, or null. */
    @Nullable
    static String escortHold(VillageFolkEntity g, ServerLevel level) {
        UUID pid = ESCORTS.get(g.getUUID());
        if (pid == null) return null;
        CompoundTag t = custodyOf(pid);
        VillageFolkEntity f = Civics.find(level, pid);
        if (t == null || f == null || !t.hasUUID("escort") || !t.getUUID("escort").equals(g.getUUID())) {
            ESCORTS.remove(g.getUUID());
            return null;
        }
        UUID village = t.getUUID("village");
        Villages.Village v = Villages.get(village);
        if (v == null || Raids.underAlarm(village)) return null;       // the bell: to the walls; the prisoner waits under guard
        String state = t.getString("state");
        Ledger.Building b = of(village);
        Cell cell = b == null ? null : cell(b, t.getInt("cell"));
        lead(level, v, g, f, t);
        long gt = level.getGameTime();
        switch (state) {
            case "LED", "BACK" -> {
                if (cell == null) {
                    t.putString("state", "HALL");
                    return "taking " + f.displayNameCap() + " to the hall";
                }
                if (!Mischief.near(g, cell.front(), 1.6) || f.distanceTo(g) > 3.5) {
                    if (f.distanceTo(g) > 5.0) g.getNavigation().stop();      // waits for the prisoner to catch up
                    else walk(g, cell.front(), 0.7D);
                    return "walking " + f.displayNameCap() + " to the cells";
                }
                // At the cell: the door opened, in it goes, off the lead, the door shut.
                g.getNavigation().stop();
                g.getLookControl().setLookAt(cell.inside().getX() + 0.5, cell.inside().getY() + 1.0, cell.inside().getZ() + 0.5);
                long since = STEP.computeIfAbsent(pid, k -> gt);
                if (gt == since) {
                    setDoor(level, cell.door(), true);
                    FolkTalk.speak(g, state.equals("BACK") ? FolkTalk.pick(g.getRandom(), "Back in you go.", "Home sweet home, " + f.displayNameCap() + ".")
                        : FolkTalk.pick(g.getRandom(), "In you go. The council will hear you soon enough.", "In. There's a bed and you'll be fed."));
                }
                unlead(level, f, g, t);
                walk(f, cell.inside(), 0.8D);
                double fx = f.getX() - (cell.inside().getX() + 0.5), fz = f.getZ() - (cell.inside().getZ() + 0.5);
                if (fx * fx + fz * fz > 0.8 && gt - since < 80) return "putting " + f.displayNameCap() + " in the cells";
                f.moveTo(cell.inside().getX() + 0.5, cell.inside().getY(), cell.inside().getZ() + 0.5, f.getYRot(), 0.0F);
                setDoor(level, cell.door(), false);
                level.playSound(null, cell.door(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.8F, 0.8F);
                STEP.remove(pid);
                t.putString("state", "CELL");
                t.remove("escort");
                ESCORTS.remove(g.getUUID());
                Police.log(village, level.getDayTime(), state.equals("BACK") ? "jail" : "cells", f.displayNameCap() + " was locked in the cells"
                    + (t.getLong("until") > 0 && t.getInt("case") > 0 ? " to serve " + daysWords(t) : t.getInt("case") > 0 ? " to wait for the council" : " for the night"), g, 0);
                Police.changed();
                return "locking the cell";
            }
            case "COURT_WALK" -> {
                Trial.Sitting s = Trial.sitting(village);
                if (s == null || s.caseId != t.getInt("case")) {
                    t.putString("state", cell != null ? "BACK" : "HALL");
                    return "taking " + f.displayNameCap() + " back";
                }
                if (!Mischief.near(f, s.dock, 1.8)) {
                    if (f.distanceTo(g) > 5.0) g.getNavigation().stop();
                    else walk(g, s.dock, 0.7D);
                    return "walking " + f.displayNameCap() + " to the council";
                }
                // In the dock: off the lead; the court has it (Trial), the guard stands by.
                unlead(level, f, g, t);
                t.putString("state", "COURT");
                Police.changed();
                return "standing by the dock";
            }
            case "COURT" -> {
                Trial.Sitting s = Trial.sitting(village);
                if (s == null) return null;
                BlockPos by = s.dock.relative(s.faces.getClockWise(), 2);
                if (!Mischief.near(g, by, 1.5)) walk(g, by, 0.8D);
                else {
                    g.getNavigation().stop();
                    g.getLookControl().setLookAt(f, 20.0F, 20.0F);
                }
                return "standing by " + f.displayNameCap() + " at the council";
            }
            case "HALL" -> {
                BlockPos hall = Villages.builtAt(village, "hall");
                BlockPos to = hall != null ? hall : VillageBoards.lectern(village) != null ? VillageBoards.lectern(village) : v.centre();
                if (!Mischief.near(g, to, 3.5)) {
                    if (f.distanceTo(g) > 5.0) g.getNavigation().stop();
                    else walk(g, to, 0.7D);
                    return "taking " + f.displayNameCap() + " to the hall";
                }
                // No cell to hold it: on its word, it waits at home for the council.
                FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "No cells to put you in. Stay where we can find you — the council sits soon.",
                    "On your word, then. Don't make me come looking for you."));
                Police.log(village, level.getDayTime(), "word", f.displayNameCap() + " was let go on their word to wait for the council", g, 0);
                free(level, f, g);
                return null;
            }
            default -> {
                ESCORTS.remove(g.getUUID());
                return null;
            }
        }
    }

    static String daysWords(CompoundTag t) {
        long days = Math.max(1, Math.round((t.getLong("until") - t.getLong("since")) / 24000.0));
        return days == 1 ? "a day" : days + " days";
    }

    // ------------------------------------------------------------------ the desk

    /** The station desk's guard (Police.duty): at the desk behind the counter, its eye on the door. False with no watch house. */
    static boolean desk(VillageFolkEntity g, ServerLevel level, Villages.Village v) {
        Ledger.Building b = of(v.id());
        if (b == null || !level.isLoaded(b.anchor())) return false;
        BlockPos stand = at(b, DESK);
        if (!Mischief.near(g, stand, 1.2)) {
            walk(g, stand, 0.8D);
            g.brain("to the desk at the watch house");
            return true;
        }
        g.getNavigation().stop();
        BlockPos door = at(b, INSIDE);
        if (g.getRandom().nextInt(4) == 0) g.getLookControl().setLookAt(door.getX() + 0.5, door.getY() + 1.5, door.getZ() + 0.5);
        if (g.getRandom().nextInt(300) == 0) FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Quiet day on the desk.", "Casebook's up to date, cells are swept."));
        g.brain("on the desk at the watch house");
        return true;
    }

    // ------------------------------------------------------------------ the round: fitting out, the board, the prisoners

    /** Every second (Police.tick): doors shut behind, the cells fitted, the notice board, and the prisoners' day. */
    static void tick(ServerLevel level, Villages.Village v) {
        long gt = level.getGameTime();
        for (Map.Entry<BlockPos, Long> e : TO_SHUT.entrySet()) {
            if (gt < e.getValue()) continue;
            TO_SHUT.remove(e.getKey());
            if (level.isLoaded(e.getKey())) setDoor(level, e.getKey(), false);
        }
        Ledger.Building b = of(v.id());
        if (b != null && level.isLoaded(b.anchor())) {
            long last = FITTED.getOrDefault(v.id(), -100000L);
            if (gt - last >= 200 || gt < last) {
                FITTED.put(v.id(), gt);
                com.jrpetty.mcassistant.Guard.run("the watch house fitted", () -> fit(level, v, b));
                com.jrpetty.mcassistant.Guard.run("the notice board", () -> noticeBoard(level, v, b));
                com.jrpetty.mcassistant.Guard.run("the casebook", () -> casebook(level, v, b));
            }
        }
        prisonersDay(level, v);
    }

    /** The prisoners' day: dinner at noon from the stores, bail in the morning, and, now and then at night, a break for it. */
    static void prisonersDay(ServerLevel level, Villages.Village v) {
        long now = level.getDayTime(), day = now / 24000L, tod = Math.floorMod(now, 24000L);
        CompoundTag all = Police.custody();
        for (String key : new ArrayList<>(all.getAllKeys())) {
            CompoundTag t = all.getCompound(key);
            if (!t.hasUUID("village") || !t.getUUID("village").equals(v.id()) || !t.getString("state").equals("CELL")) continue;
            VillageFolkEntity f = Civics.find(level, UUID.fromString(key));
            if (f == null) continue;
            if (tod >= 6000 && tod < 7000 && t.getLong("fed") != day) {
                t.putLong("fed", day);
                feed(level, v, f);
            }
            if (tod >= 1000 && tod < 6000 && t.getLong("bailLooked") != day && t.getLong("until") < 0 && t.getInt("case") > 0) {
                t.putLong("bailLooked", day);
                bail(level, v, f, t, null);
            }
            if (tod >= 17000 && tod < 22000 && t.getLong("breakLooked") != day) {
                t.putLong("breakLooked", day);
                jailbreak(level, v, f, t);
            }
        }
    }

    /** Dinner, at the town's cost: bread (or whatever the stores have) to the prisoner, brought by the desk if it is there. */
    static void feed(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        ItemStack food = Crafts.takeOne(level, v, s -> s.is(Items.BREAD));
        if (food.isEmpty()) food = Crafts.takeOne(level, v, s -> s.get(DataComponents.FOOD) != null && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE));
        if (food.isEmpty()) return;
        String what = Crafts.named(food.copy());
        ItemStack left = f.insertItem(food);
        if (!left.isEmpty()) Crafts.store(level, v, left);
        VillageFolkEntity g = nearestGuard(level, v, f.blockPosition(), 16.0, null);
        if (g != null) FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Your dinner, " + f.displayNameCap() + ". Courtesy of the town.", "Here. Eat up."));
        Police.log(v.id(), level.getDayTime(), "fed", f.displayNameCap() + " in the cells was given " + what + " out of the stores", g, 0);
    }

    /** What bail is asked for a case: the fine it would cost, and a little more. */
    static int bailFor(int caseId) {
        Crime.Case c = Crime.get(caseId);
        return c == null ? 5 : Math.max(5, Trial.fine(c) + 3);
    }

    /**
     * Bail stood for a prisoner waiting for the council: by its partner, a parent or a grown child with the coin in their
     * purse (or by a player, with {@code player}). Into the treasury; given back when the case is heard. True if paid.
     */
    static boolean bail(ServerLevel level, Villages.Village v, VillageFolkEntity f, CompoundTag t, @Nullable ServerPlayer player) {
        int bail = bailFor(t.getInt("case"));
        String payer = null;
        UUID payerId = null;
        if (player != null) {
            if (Market.coinsHeld(player) < bail) return false;
            Market.payOut(player, bail);
            payer = player.getName().getString();
            payerId = player.getUUID();
        } else {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (!(a instanceof VillageFolkEntity k) || k == f || k.isBaby() || k.purse() < bail + 2) continue;
                boolean kin = k.getUUID().equals(f.life().partner()) || f.parentIds().contains(k.getUUID()) || k.parentIds().contains(f.getUUID());
                if (!kin || !k.spend(bail)) continue;
                payer = k.displayNameCap();
                payerId = k.getUUID();
                break;
            }
        }
        if (payer == null) return false;
        Ledger.addCoins(v.id(), bail);
        CompoundTag bails = Police.town(v.id()).getCompound("bails");
        CompoundTag one = new CompoundTag();
        one.putUUID("payer", payerId);
        one.putString("payerName", payer);
        one.putBoolean("player", player != null);
        one.putInt("coins", bail);
        bails.put(Integer.toString(t.getInt("case")), one);
        Police.town(v.id()).put("bails", bails);
        Ledger.Building b = of(v.id());
        Cell cell = b == null ? null : cell(b, t.getInt("cell"));
        release(level, v, f, cell, "Bail's paid, " + f.displayNameCap() + " — " + payer + " stood it. Don't you go far: the council will want you.", "bail");
        Police.log(v.id(), level.getDayTime(), "bail", payer + " stood bail of " + bail + " coins for " + f.displayNameCap(), null, 0);
        return true;
    }

    /** The case heard: whoever stood bail on it has it back out of the treasury. */
    static void bailBack(ServerLevel level, Villages.Village v, int caseId) {
        CompoundTag bails = Police.town(v.id()).getCompound("bails");
        String key = Integer.toString(caseId);
        if (!bails.contains(key)) return;
        CompoundTag one = bails.getCompound(key);
        bails.remove(key);
        int coins = Ledger.takeCoins(v.id(), one.getInt("coins"));
        if (coins <= 0) return;
        if (one.getBoolean("player")) {
            net.minecraft.world.entity.player.Player p = level.getPlayerByUUID(one.getUUID("payer"));
            if (p != null) PlayerLaw.giveCoins(p, coins);
            else Ledger.addCoins(v.id(), coins);
        } else {
            VillageFolkEntity k = Civics.find(level, one.getUUID("payer"));
            if (k != null) k.earn(coins);
            else Ledger.addCoins(v.id(), coins);
        }
        Police.log(v.id(), level.getDayTime(), "bail", one.getString("payerName") + " had the bail back: " + coins + " coins", null, 0);
    }

    /** "I'll stand bail for Fen": a player pays it at the watch. */
    static String bailTalk(VillageFolkEntity f, ServerPlayer p, String text) {
        if (!(p.level() instanceof ServerLevel level) || f.ownerId() == null) return "Bail? For whom?";
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return "Bail? For whom?";
        VillageFolkEntity who = FolkTalk.mentioned(f, text.toLowerCase(java.util.Locale.ROOT));
        CompoundTag t = who == null ? null : custodyOf(who.getUUID());
        if (who == null || t == null) {
            List<String> held = prisoners(level, v.id());
            return held.isEmpty() ? "There's nobody in the cells to stand bail for." : "Bail for whom? " + String.join(" ", held);
        }
        if (t.getLong("until") > 0) return who.displayNameCap() + " is serving a sentence. There's no bail for that.";
        if (!t.getString("state").equals("CELL")) return "Not now — " + who.displayNameCap() + " isn't in the cells just now.";
        int bail = bailFor(t.getInt("case"));
        if (Market.coinsHeld(p) < bail) return "Bail for " + who.displayNameCap() + " is " + bail + " coins. You've not got it on you.";
        return bail(level, v, who, t, p) ? "Bail of " + bail + " paid. " + who.displayNameCap() + " can go home and wait for the council. You'll have it back when the case is heard."
            : "The bail couldn't be taken just now.";
    }

    /**
     * A break for it, once a night at most for each prisoner: one in forty from a cell of wood, one in three hundred from
     * iron, never with a guard of the watch awake within ten blocks. Out it runs; a guard in sight gives chase; else it
     * is wanted, with a bounty on it.
     */
    static void jailbreak(ServerLevel level, Villages.Village v, VillageFolkEntity f, CompoundTag t) {
        Ledger.Building b = of(v.id());
        Cell cell = b == null ? null : cell(b, t.getInt("cell"));
        if (cell == null) return;
        for (VillageFolkEntity g : Patrols.watch(v.id())) {
            if (!g.isSleeping() && g.distanceToSqr(f) < 10 * 10) return;
        }
        int odds = ironClad(level, cell) ? 300 : 40;
        if (level.getRandom().nextInt(odds) != 0) return;
        breakOut(level, v, f, t, cell);
    }

    /** Out of the cell and away: the door forced, a run for it, the watch after it or the town warned. */
    static void breakOut(ServerLevel level, Villages.Village v, VillageFolkEntity f, CompoundTag t, Cell cell) {
        boolean iron = level.getBlockState(cell.door()).is(Blocks.IRON_DOOR);
        int caseId = t.getInt("case");
        String why = t.getString("why");
        free(level, f, null);
        setDoor(level, cell.door(), true);
        level.playSound(null, cell.door(), iron ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 0.6F);
        long now = level.getDayTime();
        Police.log(v.id(), now, "break", f.displayNameCap() + " broke out of the cells at the watch house" + (iron ? ", the lock picked" : ", the door forced"), null, 0);
        Villages.tell(v.id(), now / 24000L, f.displayNameCap() + " broke out of the cells at the watch house; the watch is after them");
        Police.trust(v.id(), -2);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Now's my chance...", "They'll not hold me!"));
        Incidents.escaped(level, v, f, caseId, why);
    }

    // ------------------------------------------------------------------ the verdict and the sentence

    /** How many days in the cells a case is worth, sentenced to them: two for the graver, else one. */
    static int jailDays(Crime.Case c) {
        return c.kind.grave() || c.worth >= 16 ? 2 : 1;
    }

    /**
     * The court's sentence (Trial.sentence): with a watch house to hold it, the graver crimes (forgery, smuggling, ten coins'
     * worth or more) are served in the cells, not the stocks; a third offence is still banishment, and a lesser first or
     * second offence still a fine, work or the stocks. Null to leave it to the court's own reckoning.
     */
    @Nullable
    static Trial.Sentence sentence(ServerLevel level, Villages.Village v, Crime.Case c, int priors) {
        if (priors >= 2 || !Police.active()) return null;
        if (!c.kind.grave() && c.worth < 10) return null;
        boolean held = c.accused != null && custodyOf(c.accused) != null;
        if (!held && freeCell(level, v.id()) == null) return null;
        return Trial.Sentence.JAIL;
    }

    /**
     * The council has spoken (Trial.convict, Trial.acquit): a prisoner sentenced to the cells walked back to one (or, not
     * held, arrested now); anybody else in the watch's keeping let go at the court. A folk the watch arrested and the
     * council cleared is a wrong arrest: on the guard's record, and the town trusts its watch the less.
     */
    static void verdict(ServerLevel level, Villages.Village v, Crime.Case c, VillageFolkEntity f, int jailDays, boolean acquitted) {
        if (!Police.active()) return;
        CompoundTag t = custodyOf(f.getUUID());
        long now = level.getDayTime();
        UUID by = t != null && t.hasUUID("by") ? t.getUUID("by") : null;
        if (acquitted && by != null) {
            VillageFolkEntity g = Civics.find(level, by);
            CompoundTag r = Police.guard(by);
            r.putInt("wrong", r.getInt("wrong") + 1);
            Police.trust(v.id(), -6);
            Police.log(v.id(), now, "wrong", (g == null ? "The watch" : g.displayNameCap()) + " had arrested " + f.displayNameCap()
                + ", and the council cleared them", g, 0);
            f.persona().remember(now / 24000L, "The watch locked me up for something I never did", -6);
            if (g != null) f.life().feel(g.getUUID(), g.displayNameCap(), -20);
        }
        if (jailDays > 0) {
            long until = now + jailDays * 24000L;
            Crime.folk(f.getUUID()).putLong("until", until);
            Crime.changed();
            if (t != null) {
                t.putLong("until", until);
                t.putLong("since", now);
                Ledger.Building b = of(v.id());
                if (b != null && t.getInt("cell") >= 0) {
                    VillageFolkEntity g = t.hasUUID("escort") ? Civics.find(level, t.getUUID("escort")) : null;
                    if (g == null) g = escortFor(level, v, f);
                    t.putString("state", g != null ? "BACK" : "CELL");
                    if (g != null) {
                        t.putUUID("escort", g.getUUID());
                        ESCORTS.put(g.getUUID(), f.getUUID());
                    }
                }
            } else {
                VillageFolkEntity g = escortFor(level, v, f);
                if (g != null) arrest(level, v, g, f, c.id, "a sentence of " + (jailDays == 1 ? "a day" : jailDays + " days") + " in the cells", until);
            }
            Police.log(v.id(), now, "jail", f.displayNameCap() + " was sentenced to " + (jailDays == 1 ? "a day" : jailDays + " days")
                + " in the cells for " + c.kind.word, null, 0);
            Police.changed();
            return;
        }
        if (t != null) {
            VillageFolkEntity g = t.hasUUID("escort") ? Civics.find(level, t.getUUID("escort")) : null;
            free(level, f, g);
            if (g != null) FolkTalk.speak(g, acquitted ? "You heard the council. You're free to go." : "The council's done with you. Off you go.");
            Police.log(v.id(), now, "release", f.displayNameCap() + " was let go at the council" + (acquitted ? ", cleared" : ""), g, 0);
        }
    }

    // ------------------------------------------------------------------ fitting out

    /**
     * The cells fitted, a piece at a time by a hand on the town's works: the fence posts of the fronts given way to iron
     * bars, the wooden doors to iron ones, out of the stores (the smith's forging, or cast here of six ingots the town can
     * spare). What was taken down goes back to the stores.
     */
    static void fit(ServerLevel level, Villages.Village v, Ledger.Building b) {
        UUID id = v.id();
        List<BlockPos> bars = new ArrayList<>();
        List<BlockPos> doors = new ArrayList<>();
        for (Cell c : cells(b)) {
            for (BlockPos p : c.bars()) {
                BlockState st = level.getBlockState(p);
                if (!st.is(Blocks.IRON_BARS) && (st.canBeReplaced() || st.is(net.minecraft.tags.BlockTags.WOODEN_FENCES))) bars.add(p);
            }
            BlockState d = level.getBlockState(c.door());
            if (!d.is(Blocks.IRON_DOOR) && (d.getBlock() instanceof DoorBlock || d.canBeReplaced() && level.getBlockState(c.door().above()).canBeReplaced())) {
                doors.add(c.door());
            }
        }
        if (bars.isEmpty() && doors.isEmpty()) return;
        long tod = Math.floorMod(level.getDayTime(), 24000L);
        if (!TownJobs.instantNow() && (tod >= 12000L || Raids.underAlarm(id))) return;
        if (!bars.isEmpty()) {
            if (Market.stock(level, id, s -> s.is(Items.IRON_BARS)) < bars.size() && !cast(level, v, Items.IRON_BARS, 16)) return;
            if (!TownJobs.atWork(level, v, "watch house", bars.get(0), "setting iron bars in the cells", AssistantEntity.StationTask.SMITH)) return;
            int set = 0;
            for (BlockPos p : bars) {
                ItemStack bar = Crafts.takeOne(level, v, s -> s.is(Items.IRON_BARS));
                if (bar.isEmpty()) break;
                BlockState was = level.getBlockState(p);
                if (was.is(net.minecraft.tags.BlockTags.WOODEN_FENCES)) Crafts.store(level, v, new ItemStack(was.getBlock().asItem()));
                level.setBlock(p, Block.updateFromNeighbourShapes(Blocks.IRON_BARS.defaultBlockState(), level, p), 3);
                set++;
            }
            if (set > 0) Police.log(id, level.getDayTime(), "house", "iron bars were set in the cells of the watch house", null, 0);
            return;
        }
        if (Market.stock(level, id, s -> s.is(Items.IRON_DOOR)) < 1 && !cast(level, v, Items.IRON_DOOR, 3)) return;
        BlockPos door = doors.get(0);
        if (!TownJobs.atWork(level, v, "watch house", door, "hanging the cells' iron doors", AssistantEntity.StationTask.SMITH)) return;
        ItemStack iron = Crafts.takeOne(level, v, s -> s.is(Items.IRON_DOOR));
        if (iron.isEmpty()) return;
        hangIronDoor(level, v, door);
        Police.log(id, level.getDayTime(), "house", "an iron door was hung on a cell of the watch house", null, 0);
    }

    /**
     * Bars or doors for the cells cast of the town's iron, when the stores have none and nobody is forging them: six ingots
     * the town can spare (not its age's saving, nor the smith's last), sixteen bars or three doors. With a smith in the
     * town the smith forges them instead (Crafts.smith: {@link #smith}). True if there are some now.
     */
    static boolean cast(ServerLevel level, Villages.Village v, net.minecraft.world.item.Item what, int n) {
        if (Crafts.savingIron(level, v) || Market.stock(level, v.id(), s -> s.is(Items.IRON_INGOT)) < 6 + Crafts.IRON_KEPT) return false;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.stationTask() == AssistantEntity.StationTask.SMITH && !a.isBaby()) return false;   // the smith's work (Crafts.smith)
        }
        if (!TownWork.take(level, v, s -> s.is(Items.IRON_INGOT), 6)) return false;
        Crafts.store(level, v, new ItemStack(what, n));
        return true;
    }

    /** The smith's turn at the watch house (Crafts.smith): the cells' bars and doors forged of six ingots the town can spare. */
    @Nullable
    static String smith(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Ledger.Building b = of(v.id());
        if (b == null || !level.isLoaded(b.anchor())) return null;
        int barsWanted = 0, doorsWanted = 0;
        for (Cell c : cells(b)) {
            for (BlockPos p : c.bars()) if (!level.getBlockState(p).is(Blocks.IRON_BARS)) barsWanted++;
            if (!level.getBlockState(c.door()).is(Blocks.IRON_DOOR)) doorsWanted++;
        }
        UUID id = v.id();
        boolean bars = barsWanted > Market.stock(level, id, s -> s.is(Items.IRON_BARS));
        boolean doors = doorsWanted > Market.stock(level, id, s -> s.is(Items.IRON_DOOR));
        if (!bars && !doors) return null;
        if (Crafts.savingIron(level, v) || Market.stock(level, id, s -> s.is(Items.IRON_INGOT)) < 6 + Crafts.IRON_KEPT) return null;
        if (!TownWork.take(level, v, s -> s.is(Items.IRON_INGOT), 6)) return null;
        if (bars) {
            Crafts.store(level, v, new ItemStack(Items.IRON_BARS, 16));
            return "sixteen iron bars for the cells at the watch house";
        }
        Crafts.store(level, v, new ItemStack(Items.IRON_DOOR, 3));
        return "three iron doors for the cells at the watch house";
    }

    /** A cell's wooden door taken down (back to the stores) and an iron one hung in its place, the same way round. */
    static void hangIronDoor(ServerLevel level, Villages.Village v, BlockPos lower) {
        BlockState was = level.getBlockState(lower);
        Direction facing = Direction.NORTH;
        net.minecraft.world.level.block.state.properties.DoorHingeSide hinge = net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT;
        if (was.getBlock() instanceof DoorBlock) {
            facing = was.getValue(DoorBlock.FACING);
            hinge = was.getValue(DoorBlock.HINGE);
            Crafts.store(level, v, new ItemStack(was.getBlock().asItem()));
        } else {
            Ledger.Building b = of(v.id());
            if (b != null) facing = b.facing().getOpposite();
        }
        int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(lower.above(), Blocks.AIR.defaultBlockState(), quiet);
        level.setBlock(lower, Blocks.AIR.defaultBlockState(), quiet);
        BlockState iron = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HINGE, hinge)
            .setValue(DoorBlock.OPEN, false);
        level.setBlock(lower, iron.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), quiet);
        level.setBlock(lower.above(), iron.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), quiet);
        level.playSound(null, lower, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.5F, 1.4F);
    }

    /** A cell door opened or shut (iron or wood alike: the watch has the key). */
    static void setDoor(ServerLevel level, BlockPos lower, boolean open) {
        BlockState st = level.getBlockState(lower);
        if (!(st.getBlock() instanceof DoorBlock door)) return;
        if (st.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            lower = lower.below();
            st = level.getBlockState(lower);
            if (!(st.getBlock() instanceof DoorBlock)) return;
        }
        if (st.getValue(DoorBlock.OPEN) == open) return;
        door.setOpen(null, level, st, lower, open);
    }

    /** The notice board: the wanted on two signs, the day's roster on the third; of the stores' signs (or two planks apiece). */
    static void noticeBoard(ServerLevel level, Villages.Village v, Ledger.Building b) {
        List<String> wanted = PlayerLaw.wantedNames(v.id());
        String rosterWords = Roster.words(level, v);
        String key = String.join("|", wanted) + "#" + rosterWords + "#" + Police.town(v.id()).getString("captainName");
        Direction left = b.facing().getCounterClockWise(), right = b.facing().getClockWise();
        String[][] boards = new String[3][];
        boards[0] = wanted.isEmpty() ? new String[]{ "WANTED", "by the watch:", "nobody, today.", "" }
            : new String[]{ "WANTED", clip(wanted.get(0)), wanted.size() > 1 ? clip(wanted.get(1)) : "", "Ask at the desk" };
        boards[1] = new String[]{ "REPORT A CRIME", "at the desk.", "Bail and fines", "paid here." };
        String captain = Police.town(v.id()).getString("captainName");
        List<String> roster = rosterLines(level, v);
        boards[2] = new String[]{ "TODAY'S ROSTER", roster.size() > 0 ? roster.get(0) : (captain.isEmpty() ? "" : "Capt. " + captain),
            roster.size() > 1 ? roster.get(1) : "", roster.size() > 2 ? roster.get(2) : "" };
        int[][] spots = { WANTED_A, WANTED_B, ROSTER };
        Direction[] faces = { left, left, right };
        boolean placed = false;
        for (int i = 0; i < 3; i++) {
            BlockPos at = at(b, spots[i]);
            if (!(level.getBlockEntity(at) instanceof SignBlockEntity)) {
                if (!level.getBlockState(at).canBeReplaced()) continue;
                BlockPos wall = at.relative(faces[i].getOpposite());
                if (!level.getBlockState(wall).isFaceSturdy(level, wall, faces[i])) continue;
                if (!Crafts.sign(level, v)) continue;
                level.setBlock(at, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, faces[i]), 3);
                placed = true;
            }
        }
        if (placed) Police.log(v.id(), level.getDayTime(), "house", "the notice board went up in the watch house", null, 0);
        if (!placed && key.equals(BOARD.get(v.id()))) return;
        BOARD.put(v.id(), key);
        for (int i = 0; i < 3; i++) {
            if (level.getBlockEntity(at(b, spots[i])) instanceof SignBlockEntity sign) {
                TownLife.write(sign, boards[i]);
                sign.setChanged();
                BlockState st = level.getBlockState(at(b, spots[i]));
                level.sendBlockUpdated(at(b, spots[i]), st, st, 3);
            }
        }
    }

    /** The roster in three short lines of a sign: "Walls: Bram", "Beat: Ash, Rook", "Desk: Wren". */
    static List<String> rosterLines(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (Roster.Duty d : new Roster.Duty[]{ Roster.Duty.WALLS, Roster.Duty.BEAT, Roster.Duty.DESK, Roster.Duty.CASES, Roster.Duty.EVENT }) {
            List<VillageFolkEntity> on = Roster.on(level, v, d);
            if (on.isEmpty()) continue;
            List<String> names = new ArrayList<>();
            for (VillageFolkEntity g : on) names.add(g.displayNameCap());
            out.add(clip(Police.capital(Roster.short_(d)) + ": " + String.join(", ", names)));
            if (out.size() >= 3) break;
        }
        return out;
    }

    private static String clip(String s) {
        return s.length() <= 15 ? s : s.substring(0, 14) + ".";
    }

    /** The casebook on the desk's lectern: a book of the stores' paper, ink and quill, written up with the week every day. */
    static void casebook(ServerLevel level, Villages.Village v, Ledger.Building b) {
        BlockPos at = at(b, LECTERN);
        BlockState st = level.getBlockState(at);
        if (!(st.getBlock() instanceof LecternBlock)) return;
        long day = level.getDayTime() / 24000L;
        CompoundTag t = Police.town(v.id());
        if (st.getValue(LecternBlock.HAS_BOOK)) {
            if (t.getLong("casebookDay") == day || !(level.getBlockEntity(at) instanceof LecternBlockEntity le) || !isCasebook(le.getBook())) return;
            le.setBook(book(level, v));
            le.setChanged();
            t.putLong("casebookDay", day);
            Police.changed();
            return;
        }
        if (Market.stock(level, v.id(), x -> x.is(Items.BOOK)) < 1 || Market.stock(level, v.id(), x -> x.is(Items.INK_SAC)) < 1
                || Market.stock(level, v.id(), x -> x.is(Items.FEATHER)) < 1) return;
        ItemStack paper = Crafts.takeOne(level, v, x -> x.is(Items.BOOK));
        ItemStack ink = Crafts.takeOne(level, v, x -> x.is(Items.INK_SAC));
        ItemStack quill = Crafts.takeOne(level, v, x -> x.is(Items.FEATHER));
        if (paper.isEmpty() || ink.isEmpty() || quill.isEmpty()) {
            for (ItemStack back : List.of(paper, ink, quill)) if (!back.isEmpty()) Crafts.store(level, v, back);
            return;
        }
        if (!LecternBlock.tryPlaceBook(null, level, at, st, book(level, v))) {
            for (ItemStack back : List.of(paper, ink, quill)) Crafts.store(level, v, back);
            return;
        }
        t.putLong("casebookDay", day);
        Police.log(v.id(), level.getDayTime(), "house", "the watch's casebook was laid open on the desk", null, 0);
    }

    static final String TITLE = "The Watch's Casebook";

    static boolean isCasebook(ItemStack s) {
        WrittenBookContent c = s.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return c != null && TITLE.equals(c.title().raw());
    }

    /** The casebook as it stands: the roster, who is in the cells, the wanted, and the week's incidents. */
    static ItemStack book(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        String captain = Police.town(id).getString("captainName");
        String front = "The Watch of " + Villages.name(id) + "\n" + (captain.isEmpty() ? "" : "Captain " + captain + "\n") + "Day " + day
            + "\n\nRoster:\n" + String.join("\n", rosterLines(level, v));
        List<String> entries = new ArrayList<>();
        List<String> held = prisoners(level, id);
        entries.add("§lIn the cells§r\n" + (held.isEmpty() ? "Nobody." : String.join("\n", held)));
        List<String> wanted = PlayerLaw.wantedLines(id);
        entries.add("§lWanted§r\n" + (wanted.isEmpty() ? "Nobody." : String.join("\n", wanted)));
        StringBuilder sb = new StringBuilder("§lThe week§r");
        int n = 0;
        for (CompoundTag one : Police.logSince(id, day - 6)) {
            if (n++ >= 14) break;
            sb.append("\nDay ").append(one.getLong("day")).append(": ").append(one.getString("text")).append('.');
        }
        if (n == 0) sb.append("\nA quiet week.");
        entries.add(sb.toString());
        return Services.book(TITLE, captain.isEmpty() ? "the watch" : captain, front, entries);
    }

    // ------------------------------------------------------------------ telling

    /** Its card: in the cells (for what, how long), on the lead, at the council. */
    static String cardLine(VillageFolkEntity f) {
        CompoundTag t = custodyOf(f.getUUID());
        if (t == null) return "";
        String why = t.getString("why");
        return switch (t.getString("state")) {
            case "CELL" -> t.getLong("until") > 0 ? "in the cells for " + why + ", " + daysWords(t) : "in the cells for " + why + ", waiting for the council";
            case "COURT", "COURT_WALK" -> "before the council, in the watch's keeping";
            case "HALL" -> "arrested for " + why + ", taken to the hall";
            default -> "arrested for " + why + ", on the way to the cells";
        };
    }

    /** "In the cells: Fen (theft at the market, waiting for the council); Bree (two days)." */
    @Nullable
    static String boardLine(ServerLevel level, UUID village) {
        List<String> held = prisoners(level, village);
        return held.isEmpty() ? null : "In the cells at the watch house: " + String.join(" ", held);
    }

    static List<String> prisoners(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        long now = level.getDayTime();
        CompoundTag all = Police.custody();
        for (String key : all.getAllKeys()) {
            CompoundTag t = all.getCompound(key);
            if (!t.hasUUID("village") || !t.getUUID("village").equals(village)) continue;
            String state = t.getString("state");
            String when = t.getLong("until") > 0 ? "out " + (t.getLong("until") - now > 24000 ? "in " + ((t.getLong("until") - now) / 24000 + 1) + " days"
                : "by " + Crime.hour(t.getLong("until"))) : "waiting for the council";
            out.add(t.getString("name") + " (" + t.getString("why") + "; " + (state.equals("CELL") ? when : state.equals("HALL") ? "on the way to the hall"
                : state.startsWith("COURT") ? "before the council" : "on the way to the cells") + ").");
        }
        return out;
    }

    /** The watch house in a line, for the books. */
    static String statusLine(ServerLevel level, Villages.Village v) {
        Ledger.Building b = of(v.id());
        if (b == null) {
            if (Villages.builtStructure(v.id(), STRUCTURE) != null) return "The watch house is going up.";
            return wanted(v.id()) ? "The town wants a watch house: it is on the building list." : Patrols.watch(v.id()).size() < GUARDS
                ? "No watch house: a town builds one from the Stone Age once its watch is " + GUARDS + " strong. Prisoners wait at the hall."
                : "No watch house yet.";
        }
        if (!level.isLoaded(b.anchor())) return "The watch house stands at " + b.anchor().toShortString() + ".";
        int iron = 0;
        List<Cell> cells = cells(b);
        for (Cell c : cells) if (ironClad(level, c)) iron++;
        int held = count(v.id());
        return "The watch house: " + cells.size() + " cells, " + (iron == cells.size() ? "all of iron" : iron == 0 ? "fronted with timber till the town can spare iron"
            : iron + " of iron") + "; " + (held == 0 ? "nobody held." : held + " held.");
    }

    // ------------------------------------------------------------------ helpers

    static void walk(VillageFolkEntity f, BlockPos to, double speed) {
        Integer last = PATHED.get(f.getUUID());
        if (last == null || f.tickCount - last > 30 || f.tickCount < last || f.getNavigation().isDone()) {
            f.walkTo(to, speed);
            PATHED.put(f.getUUID(), f.tickCount);
        }
    }

    // ------------------------------------------------------------------ the tests

    static String stateOf(UUID folk) {
        CompoundTag t = custodyOf(folk);
        return t == null ? "" : t.getString("state");
    }

    static Vec3 centre(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
    }

    static AABB box(Cell c) {
        return new AABB(c.inside()).inflate(1.5, 1.0, 1.5);
    }
}
