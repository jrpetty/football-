package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Districts;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [batchF] Petitions: what a folk thinks its street wants, put to the town.
 *
 * <p><b>A grievance.</b> Once a day a few folk look about them: no bench to sit on anywhere near the house;
 * the street at its door dark after nightfall (no lamp lights it); no well in its quarter of the town; or
 * its plot out past the town with no road to it, only grass. A folk with one of these gets up a petition —
 * a line on the board ("A bench by No. 3 Elm Row, asks Ash") and in the town's books — unless one asking the
 * same for the same street is up already. One a day in a town, three open at once at most.
 *
 * <p><b>Names.</b> Over the next days, folk who agree walk to the board of an evening (or on the day of rest) and
 * put their names to it: the neighbours, for a bench or a light by their own doors; the quarter, for a well;
 * the hands who work out that way, for a road; and the raiser's friends, whatever it asks (never its rivals).
 * It wants a quarter of the town's grown folk (three at least, eight at most). A petition that has not got
 * them in a week lapses.
 *
 * <p><b>The council.</b> At its weekly sitting the council hears every petition with names enough and puts
 * it on the town's works; one that has waited two days with its names and no sitting is put there by the
 * elder. Then a hand on the town's works (TownJobs) does it with the stores' materials: a stair for the bench
 * (or two planks), a fence and a torch for the light (the torch made of coal and a stick if there is none),
 * eight cobblestone and a bucket of water for the well (the bucket filled at a pond, if there is no full one,
 * and put back empty), a worn path all the way out for the road. What the stores cannot run to waits.
 * Done, it is in the chronicle and on the board's list of what the town's petitions have won.
 */
public final class Petitions {

    private Petitions() {}

    public enum Kind {
        BENCH("a bench", "somewhere to sit"),
        LIGHT("a street light", "a light in the street"),
        WELL("a well", "water near home"),
        ROAD("a road", "a road out to the work");

        public final String words, want;

        Kind(String words, String want) {
            this.words = words;
            this.want = want;
        }
    }

    static final String OPEN = "open", APPROVED = "approved", DONE = "done", LAPSED = "lapsed";
    /** How far from home a seat, a lit street or a well still counts as near. */
    static final int SEAT_NEAR = 8, WELL_NEAR = 40;
    /** Block light under which a street counts as dark. */
    static final int DARK = 8;

    /** A folk on its way to the board to sign: which petition, since when. */
    record Signing(int petition, int since) {}

    private static final Map<UUID, Signing> SIGNING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SIGNING.clear();
        LOOKED.clear();
    }

    static ListTag all(UUID village) {
        return Civics.list(Civics.town(village), "petitions");
    }

    static List<CompoundTag> in(UUID village, String state) {
        List<CompoundTag> out = new ArrayList<>();
        for (Tag t : all(village)) if (t instanceof CompoundTag c && (state == null || state.equals(c.getString("state")))) out.add(c);
        return out;
    }

    @Nullable
    static CompoundTag byId(UUID village, int n) {
        for (Tag t : all(village)) if (t instanceof CompoundTag c && c.getInt("n") == n) return c;
        return null;
    }

    static Kind kind(CompoundTag c) {
        try {
            return Kind.valueOf(c.getString("kind"));
        } catch (IllegalArgumentException e) {
            return Kind.BENCH;
        }
    }

    static int signed(CompoundTag c) {
        return c.getList("names", Tag.TAG_STRING).size();
    }

    static boolean hasSigned(CompoundTag c, UUID folk) {
        for (Tag t : c.getList("names", Tag.TAG_STRING)) if (t.getAsString().startsWith(folk.toString())) return true;
        return false;
    }

    /** Names enough for the council: a quarter of the grown folk, three at least, eight at most. */
    public static int needed(UUID village) {
        int grown = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (!a.isBaby()) grown++;
        return Math.max(3, Math.min(8, grown / 4));
    }

    // ------------------------------------------------------------------ the round of the town

    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = Civics.day(level), t = level.getDayTime() % 24000L;
        if (t >= 2000 && LOOKED.getOrDefault(id, -1L) != day) {
            LOOKED.put(id, day);
            raise(level, v, day);
            review(level, v, day);
        }
        signers(level, v);
        for (CompoundTag c : in(id, APPROVED)) {
            if (work(level, v, c) != 0) break;              // one piece of the works a look
        }
    }

    /** A few folk look about them, and the first with a grievance nobody has petitioned for gets one up. */
    static void raise(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (in(id, OPEN).size() + in(id, APPROVED).size() >= 3 || Villages.headcount(id) < 4) return;
        List<VillageFolkEntity> folk = Civics.grown(id);
        java.util.Collections.shuffle(folk, new java.util.Random(level.getGameTime()));
        for (int i = 0; i < Math.min(6, folk.size()); i++) {
            CompoundTag c = raiseFor(level, v, folk.get(i), day);
            if (c != null) return;
        }
    }

    /** "/village civic petition": the first grievance in the town got up as a petition now. */
    public static String raiseNow(ServerLevel level, Villages.Village v) {
        for (VillageFolkEntity f : Civics.grown(v.id())) {
            CompoundTag c = raiseFor(level, v, f, Civics.day(level));
            if (c != null) return c.getString("byName") + " asks for " + c.getString("words");
        }
        return "nobody here has a grievance the town could put right";
    }

    /** This folk's grievance got up as a petition, if it has one nobody has petitioned for yet. */
    @Nullable
    static CompoundTag raiseFor(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        Object[] g = grievance(level, v, f);
        if (g == null) return null;
        Kind k = (Kind) g[0];
        BlockPos at = (BlockPos) g[1];
        for (CompoundTag o : in(v.id(), null)) {
            if (!kind(o).equals(k) || LAPSED.equals(o.getString("state"))) continue;
            if (BlockPos.of(o.getLong("at")).distSqr(at) < (k == Kind.WELL ? 30 * 30 : 10 * 10)) return null;   // asked for already
        }
        CompoundTag c = new CompoundTag();
        c.putInt("n", Civics.nextId());
        c.putString("kind", k.name());
        c.putString("by", f.getUUID().toString());
        c.putString("byName", f.displayNameCap());
        c.putLong("at", at.asLong());
        c.putString("words", k.words + " " + (String) g[2]);
        c.putLong("raised", day);
        c.putString("state", OPEN);
        c.put("names", new ListTag());
        all(v.id()).add(c);
        sign(c, f);
        Civics.changed();
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'm getting up a petition: " + c.getString("words") + ".",
            "Somebody has to ask. I'll put it on the board: " + c.getString("words") + "."));
        f.persona().remember(day, "I got up a petition for " + c.getString("words"), 3);
        Villages.tell(v.id(), day, f.displayNameCap() + " got up a petition for " + c.getString("words"));
        return c;
    }

    // ------------------------------------------------------------------ grievances

    /** What this folk thinks its street wants: {kind, where it would go, "by No. 3 Elm Row"}, or null. */
    @Nullable
    static Object[] grievance(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        BlockPos home = Civics.home(f);
        UUID id = v.id();
        if (home != null && level.isLoaded(home)) {
            Ledger.Building house = houseAt(id, home);
            BlockPos door = house == null ? null : TownLife.fittings(house).door();
            String by = house == null ? "near " + f.displayNameCap() + "'s bed" : "by " + where(id, v.centre(), house);
            BlockPos from = door != null ? door : home;
            if (!seatNear(level, from)) {
                BlockPos spot = benchSpot(level, house, from);
                if (spot != null) return new Object[]{ Kind.BENCH, spot, by };
            }
            if (dark(level, from, house == null ? Direction.SOUTH : house.facing().getOpposite())) {
                BlockPos spot = lampSpot(level, house, from);
                if (spot != null) return new Object[]{ Kind.LIGHT, spot, by };
            }
            Districts.District d = Quarters.districtOf(id, v.centre(), home);
            if ((d == Districts.District.HOMES || d == Districts.District.CRAFTS || d == Districts.District.MARKET) && !wellNear(level, v, home, d)) {
                BlockPos spot = wellSpot(level, v, home);
                if (spot != null) return new Object[]{ Kind.WELL, spot, "in " + d.words };
            }
        }
        WorkZone z = f.workZone();
        if (z != null && !f.isBaby() && f.stationTask() != AssistantEntity.StationTask.GUARD) {
            BlockPos plot = z.center();
            double far = Math.sqrt(plot.distSqr(new BlockPos(v.centre().getX(), plot.getY(), v.centre().getZ())));
            if (far > Villages.townReach(id) + 8 && far < 160 && level.isLoaded(plot) && roadCells(level, v, plot, false).size() > 4) {
                return new Object[]{ Kind.ROAD, plot.immutable(), "out to " + f.displayNameCap() + "'s " + plotWord(f) };
            }
        }
        return null;
    }

    private static String plotWord(VillageFolkEntity f) {
        return switch (f.stationTask()) {
            case FARM -> "fields";
            case WOOD -> "woods";
            case MINE -> "mine";
            case FISH -> "fishing water";
            case HUNT -> "hunting grounds";
            default -> "plot";
        };
    }

    /** The house this bed is in (by the buildings the town has in its books), or null. */
    @Nullable
    static Ledger.Building houseAt(UUID village, BlockPos bed) {
        Ledger.Building best = null;
        double bd = Double.MAX_VALUE;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!Homes.isHome(b.structure())) continue;
            int[] half = TownLife.fittings(b).half();
            int r = Math.max(half[0], half[1]) + 1;
            if (Math.abs(bed.getX() - b.anchor().getX()) > r || Math.abs(bed.getZ() - b.anchor().getZ()) > r
                    || Math.abs(bed.getY() - b.anchor().getY()) > 6) continue;
            double d = bed.distSqr(b.anchor());
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    /** "No. 3 Elm Row", or the quarter it stands in. */
    static String where(UUID village, BlockPos heart, Ledger.Building b) {
        String[] a = TownLife.address(village, heart, b);
        return a != null ? a[0] + " " + a[1] : "the houses in " + Quarters.districtOf(village, heart, b).words;
    }

    /** Is there a seat (a stair the right way up: Seats) within a few blocks of here? */
    static boolean seatNear(ServerLevel level, BlockPos from) {
        for (BlockPos p : BlockPos.betweenClosed(from.offset(-SEAT_NEAR, -2, -SEAT_NEAR), from.offset(SEAT_NEAR, 2, SEAT_NEAR))) {
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof StairBlock && Seats.seat(level, p, st)) return true;
        }
        return false;
    }

    /**
     * Is the street at this door dark: no lamp's light worth the name on the ground out in front of it (the
     * house's own lamps, inside, do not count; nor do its windows, which are lit only after dark)?
     */
    static boolean dark(ServerLevel level, BlockPos door, Direction out) {
        Direction side = out.getClockWise();
        for (int along = 1; along <= 4; along++) {
            for (int across = -3; across <= 3; across++) {
                BlockPos p = door.relative(out, along).relative(side, across);
                for (int up = 0; up <= 1; up++) {
                    BlockPos q = p.above(up);
                    if (level.getBlockState(q).isAir() && level.getBrightness(LightLayer.BLOCK, q) >= DARK) return false;
                }
            }
        }
        return true;
    }

    /** A well, a fountain or a park's water in this quarter of the town, or near enough home. */
    static boolean wellNear(ServerLevel level, Villages.Village v, BlockPos home, Districts.District d) {
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (!b.structure().equals("well") && !b.structure().equals("fountain") && !b.structure().equals(Park.STRUCTURE)) continue;
            if (b.anchor().distSqr(home) < WELL_NEAR * WELL_NEAR || Quarters.districtOf(v.id(), v.centre(), b.anchor()) == d) return true;
        }
        for (CompoundTag c : in(v.id(), DONE)) {
            if (kind(c) == Kind.WELL && BlockPos.of(c.getLong("at")).distSqr(home) < WELL_NEAR * WELL_NEAR) return true;
        }
        return false;
    }

    /** Somewhere a thing can stand: solid ground under it, and the spot and the one over it clear (grass and flowers are cleared). */
    static boolean clear(ServerLevel level, BlockPos p) {
        BlockState below = level.getBlockState(p.below());
        if (!below.isFaceSturdy(level, p.below(), Direction.UP) || below.getBlock() instanceof StairBlock) return false;
        BlockState here = level.getBlockState(p), over = level.getBlockState(p.above());
        return (here.isAir() || here.canBeReplaced() && here.getFluidState().isEmpty())
            && (over.isAir() || over.canBeReplaced() && over.getFluidState().isEmpty());
    }

    /** Before the house, off to one side of its door (never in the way in): where the bench goes. */
    @Nullable
    static BlockPos benchSpot(ServerLevel level, @Nullable Ledger.Building house, BlockPos from) {
        Direction out = house == null ? Direction.SOUTH : house.facing().getOpposite();
        Direction side = out.getClockWise();
        for (int along : new int[]{ 2, 3 }) {
            for (int across : new int[]{ 2, -2, 3, -3 }) {
                BlockPos p = from.relative(out, along).relative(side, across);
                if (clear(level, p) && clear(level, p.relative(out))) return p.immutable();
            }
        }
        return null;
    }

    /** By the door, on the other side from where a bench would go: where the street light goes. */
    @Nullable
    static BlockPos lampSpot(ServerLevel level, @Nullable Ledger.Building house, BlockPos from) {
        Direction out = house == null ? Direction.SOUTH : house.facing().getOpposite();
        Direction side = out.getCounterClockWise();
        for (int along : new int[]{ 2, 1, 3 }) {
            for (int across : new int[]{ 2, 3, -3 }) {
                BlockPos p = from.relative(out, along).relative(side, across);
                if (clear(level, p) && level.getBlockState(p.above(2)).canBeReplaced()) return p.immutable();
            }
        }
        return null;
    }

    /** A flat three-by-three off the streets near home, nothing built on it: where the well goes (its middle). */
    @Nullable
    static BlockPos wellSpot(ServerLevel level, Villages.Village v, BlockPos home) {
        BlockPos heart = v.centre();
        for (int r = 5; r <= 12; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = home.getX() + dx, z = home.getZ() + dz;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos mid = new BlockPos(x, y, z);
                    if (Math.abs(y - home.getY()) > 3) continue;
                    if (flatPatch(level, v, heart, mid)) return mid;
                }
            }
        }
        return null;
    }

    private static boolean flatPatch(ServerLevel level, Villages.Village v, BlockPos heart, BlockPos mid) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = mid.offset(dx, 0, dz);
                int ox = p.getX() - heart.getX(), oz = p.getZ() - heart.getZ();
                if (TownPlan.isStreet(ox, oz) || TownPlan.isSquare(ox, oz) || Villages.onFarmland(v.id(), ox, oz, 0, 0)) return false;
                BlockState g = level.getBlockState(p.below());
                if (!(g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.DIRT) || g.is(Blocks.COARSE_DIRT) || g.is(Blocks.PODZOL))) return false;
                if (!clear(level, p) || !level.getBlockState(p.above(2)).canBeReplaced()) return false;
            }
        }
        return true;
    }

    /** The cells of the way from the town's edge out to a plot that are not yet a path (or, {@code all}, every cell). */
    static List<BlockPos> roadCells(ServerLevel level, Villages.Village v, BlockPos plot, boolean all) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos c = v.centre();
        double dx = plot.getX() - c.getX(), dz = plot.getZ() - c.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) return out;
        int edge = Villages.townReach(v.id());
        int sx = c.getX() + (int) Math.round(dx / len * edge), sz = c.getZ() + (int) Math.round(dz / len * edge);
        for (int[] cell : Roads.bresenham(sx, sz, plot.getX(), plot.getZ())) {
            if (!level.hasChunk(cell[0] >> 4, cell[1] >> 4)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cell[0], cell[1]) - 1;
            BlockPos top = new BlockPos(cell[0], y, cell[1]);
            BlockState g = level.getBlockState(top);
            boolean path = g.is(Blocks.DIRT_PATH) || g.is(Blocks.GRAVEL) || g.is(Blocks.COBBLESTONE) || g.is(Blocks.STONE_BRICKS);
            boolean earth = g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.DIRT) || g.is(Blocks.COARSE_DIRT) || g.is(Blocks.PODZOL);
            if (all || !path && earth) out.add(top);
        }
        return out;
    }

    // ------------------------------------------------------------------ names

    static void sign(CompoundTag c, VillageFolkEntity f) {
        if (hasSigned(c, f.getUUID())) return;
        ListTag names = c.getList("names", Tag.TAG_STRING);
        names.add(StringTag.valueOf(f.getUUID() + "|" + f.displayNameCap()));
        c.put("names", names);
        CompoundTag me = Civics.folk(f.getUUID());
        me.putInt("signed", me.getInt("signed") + 1);
        Civics.changed();
    }

    /** Would this folk put its name to it? */
    static boolean agrees(Villages.Village v, VillageFolkEntity f, CompoundTag c) {
        if (hasSigned(c, f.getUUID())) return false;
        UUID by;
        try { by = UUID.fromString(c.getString("by")); } catch (IllegalArgumentException e) { return false; }
        int warmth = f.life().affinity(by);
        if (warmth <= Social.RIVAL) return false;
        if (warmth >= Social.FRIEND) return true;
        BlockPos at = BlockPos.of(c.getLong("at"));
        BlockPos home = Civics.home(f);
        boolean kindly = f.life().has(Social.Trait.GENEROUS) || f.life().has(Social.Trait.SOCIABLE);
        return switch (kind(c)) {
            case BENCH, LIGHT -> home != null && (home.distSqr(at) < 24 * 24 || kindly && home.distSqr(at) < 48 * 48);
            case WELL -> home != null && (Quarters.districtOf(v.id(), v.centre(), home) == Quarters.districtOf(v.id(), v.centre(), at)
                || kindly && home.distSqr(at) < 48 * 48);
            case ROAD -> f.workZone() != null && f.workZone().center().distSqr(at) < 40 * 40 || kindly;
        };
    }

    /** Of an evening (or on the day of rest), folk free to who agree with an open petition go and sign it: two at a time. */
    static void signers(ServerLevel level, Villages.Village v) {
        List<CompoundTag> open = in(v.id(), OPEN);
        long t = level.getDayTime() % 24000L;
        boolean evening = t >= 11000 && t < 13000 || RestDay.today(v.id(), Civics.day(level)) && t >= 3600 && t < 12000;
        if (open.isEmpty() || !evening || SIGNING.size() > 64) return;
        int going = 0;
        for (VillageFolkEntity f : Civics.grown(v.id())) {
            if (SIGNING.containsKey(f.getUUID())) { going++; continue; }
        }
        for (VillageFolkEntity f : Civics.grown(v.id())) {
            if (going >= 2) return;
            if (SIGNING.containsKey(f.getUUID()) || !Civics.free(f)) continue;
            CompoundTag me = Civics.folk(f.getUUID());
            if (me.getLong("signedOn") == Civics.day(level) + 1) continue;          // one a day
            for (CompoundTag c : open) {
                if (!agrees(v, f, c) || signed(c) >= needed(v.id()) + 2) continue;
                SIGNING.put(f.getUUID(), new Signing(c.getInt("n"), f.tickCount));
                going++;
                break;
            }
        }
    }

    /** The folk's hold (Civics.hold): to the board, and its name put down. */
    @Nullable
    static String hold(VillageFolkEntity f, ServerLevel level) {
        Signing s = SIGNING.get(f.getUUID());
        if (s == null) return null;
        UUID id = f.ownerId();
        CompoundTag c = id == null ? null : byId(id, s.petition());
        Villages.Village v = id == null ? null : Villages.get(id);
        if (c == null || v == null || !OPEN.equals(c.getString("state")) || f.tickCount - s.since() > 1600 || f.tickCount < s.since()
                || Raids.underAlarm(id) || !f.offWorkNow()) {
            SIGNING.remove(f.getUUID());
            return null;
        }
        BlockPos board = VillageBoards.lectern(id);
        if (board == null) board = v.centre();
        if (!Civics.goTo(f, board, 2.5, 0.8)) return "on the way to sign the petition for " + c.getString("words");
        SIGNING.remove(f.getUUID());
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        sign(c, f);
        Civics.folk(f.getUUID()).putLong("signedOn", Civics.day(level) + 1);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'll put my name to that.", "There. My name's on it.",
            "About time somebody asked for " + kind(c).words + "."));
        return "signing a petition";
    }

    // ------------------------------------------------------------------ the council, and the elder

    /** Once a day: a week without its names and it lapses; two days with them and no sitting, the elder puts it on the works. */
    static void review(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        for (CompoundTag c : in(id, OPEN)) {
            boolean enough = signed(c) >= needed(id);
            if (enough && !c.contains("enough")) c.putLong("enough", day);
            if (enough && (day - c.getLong("enough") >= 2 || Council.members(id).size() < 3)) {
                approve(level, v, c, "the elder");
            } else if (!enough && day - c.getLong("raised") > 7) {
                c.putString("state", LAPSED);
                c.putLong("lapsed", day);
                Villages.tell(id, day, c.getString("byName") + "'s petition for " + c.getString("words") + " lapsed with "
                    + signed(c) + (signed(c) == 1 ? " name" : " names"));
            }
        }
        // On the works ten days and not done (something has been put where it was to go, or the stores never ran
        // to it): let go, and the grievance free to be got up again, somewhere it can be done.
        for (CompoundTag c : in(id, APPROVED)) {
            if (day - c.getLong("approved") <= 10) continue;
            c.putString("state", LAPSED);
            c.putLong("lapsed", day);
            Villages.tell(id, day, c.getString("byName") + "'s petition for " + c.getString("words") + " could not be done, and was let go");
        }
        // The books keep a lapsed petition a week and a granted one four (the chronicle keeps them for good).
        ListTag all = all(id);
        for (int i = all.size() - 1; i >= 0; i--) {
            CompoundTag c = all.getCompound(i);
            if (LAPSED.equals(c.getString("state")) && day - Math.max(c.getLong("raised"), c.getLong("lapsed")) > 7
                    || DONE.equals(c.getString("state")) && day - c.getLong("done") > 28) all.remove(i);
        }
        Civics.changed();
    }

    static void approve(ServerLevel level, Villages.Village v, CompoundTag c, String by) {
        if (!OPEN.equals(c.getString("state"))) return;
        long day = Civics.day(level);
        c.putString("state", APPROVED);
        c.putLong("approved", day);
        c.putString("approvedBy", by);
        Civics.changed();
        Villages.tell(v.id(), day, by + " put " + c.getString("byName") + "'s petition for " + c.getString("words")
            + " on the town's works (" + signed(c) + " names)");
    }

    /** The council's sitting (Assemblies, the COUNCIL script): every petition with its names read out and put on the works. */
    static void council(ServerLevel level, UUID village, List<Assemblies.Line> s) {
        Villages.Village v = Villages.get(village);
        if (v == null) return;
        for (CompoundTag c : in(village, OPEN)) {
            if (signed(c) < needed(village)) continue;
            String words = c.getString("words");
            s.add(new Assemblies.Line(null, "A petition, from " + c.getString("byName") + " and " + (signed(c) - 1) + " more: "
                + words + ". It goes on the town's works.", '!', () -> approve(level, v, c, "the council")));
        }
    }

    // ------------------------------------------------------------------ the works

    /** One piece of an approved petition's works, by a hand at it (TownJobs). 1 done or done a piece, 0 nothing to do, -1 waiting. */
    static int work(ServerLevel level, Villages.Village v, CompoundTag c) {
        BlockPos at = BlockPos.of(c.getLong("at"));
        if (!level.isLoaded(at)) return 0;
        Kind k = kind(c);
        String what = switch (k) {
            case BENCH -> "putting up the bench the petition asked for";
            case LIGHT -> "putting up the street light the petition asked for";
            case WELL -> "digging the well the petition asked for";
            case ROAD -> "laying the road the petition asked for";
        };
        if (k == Kind.ROAD) return road(level, v, c, at, what);
        if (!afford(level, v, k)) return 0;
        if (!TownJobs.atWork(level, v, "petitions", at, what)) return -1;
        boolean ok = switch (k) {
            case BENCH -> bench(level, v, at);
            case LIGHT -> lamp(level, v, at);
            case WELL -> well(level, v, at);
            default -> false;
        };
        if (!ok) return 0;
        done(level, v, c);
        return 1;
    }

    /** Can the stores run to it (before anybody is sent)? */
    static boolean afford(ServerLevel level, Villages.Village v, Kind k) {
        UUID id = v.id();
        return switch (k) {
            case BENCH -> Market.stock(level, id, s -> s.is(ItemTags.WOODEN_STAIRS)) > 0 || Market.stock(level, id, s -> s.is(ItemTags.PLANKS)) >= 2;
            case LIGHT -> (Market.stock(level, id, s -> s.is(Items.TORCH)) > 0 || Market.stock(level, id, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) > 0)
                && Market.stock(level, id, s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.WOODEN_FENCES)) >= 2;
            case WELL -> Market.stock(level, id, s -> s.is(Items.COBBLESTONE)) >= 8
                && (Market.stock(level, id, s -> s.is(Items.WATER_BUCKET)) > 0 || Market.stock(level, id, s -> s.is(Items.BUCKET)) > 0);
            case ROAD -> true;
        };
    }

    /** The bench: a stair out of the stores (or two planks), its back to the house and its seat to the street. */
    static boolean bench(ServerLevel level, Villages.Village v, BlockPos at) {
        if (!Petitions.clear(level, at)) return false;
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState();
        if (TownWork.take(level, v, s -> s.is(Items.SPRUCE_STAIRS), 1)) stair = Blocks.SPRUCE_STAIRS.defaultBlockState();
        else if (!TownWork.take(level, v, s -> s.is(ItemTags.WOODEN_STAIRS), 1) && !TownWork.take(level, v, s -> s.is(ItemTags.PLANKS), 2)) return false;
        Ledger.Building house = houseAt(v.id(), at);
        Direction back = house == null ? facingAway(v.centre(), at).getOpposite() : house.facing();
        level.setBlockAndUpdate(at, stair.setValue(StairBlock.FACING, back).setValue(StairBlock.HALF, Half.BOTTOM));
        return true;
    }

    private static Direction facingAway(BlockPos heart, BlockPos at) {
        int dx = at.getX() - heart.getX(), dz = at.getZ() - heart.getZ();
        return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /** The street light: a fence post (one put by, or two planks) and a torch on it (one put by, or made of coal and a stick). */
    static boolean lamp(ServerLevel level, Villages.Village v, BlockPos at) {
        if (!Petitions.clear(level, at) || !level.getBlockState(at.above(2)).canBeReplaced()) return false;
        if (!TownWork.take(level, v, s -> s.is(Items.TORCH), 1)) {
            if (!TownWork.take(level, v, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 1)) return false;
            if (!TownWork.take(level, v, s -> s.is(ItemTags.PLANKS), 1)) {
                TownWork.give(level, v, new ItemStack(Items.COAL));
                return false;
            }
            TownWork.give(level, v, new ItemStack(Items.TORCH, 3));          // four made, one used
        }
        if (!Crafts.fence(level, v)) {
            TownWork.give(level, v, new ItemStack(Items.TORCH));
            return false;
        }
        level.setBlockAndUpdate(at, Blocks.OAK_FENCE.defaultBlockState());
        level.setBlockAndUpdate(at.above(), Blocks.TORCH.defaultBlockState());
        return true;
    }

    /**
     * The well: the middle dug out and filled from a bucket of water (the stores' full one, or their empty one
     * filled at a pond near the town, the pond left as it was), the bucket put back empty, and a ring of eight
     * of the stores' cobblestone round it for a parapet.
     */
    static boolean well(ServerLevel level, Villages.Village v, BlockPos mid) {
        if (Market.stock(level, v.id(), s -> s.is(Items.COBBLESTONE)) < 8) return false;
        boolean full = TownWork.take(level, v, s -> s.is(Items.WATER_BUCKET), 1);
        if (!full) {
            if (pond(level, v.centre()) == null || !TownWork.take(level, v, s -> s.is(Items.BUCKET), 1)) return false;
        }
        if (!TownWork.take(level, v, s -> s.is(Items.COBBLESTONE), 8)) {
            TownWork.give(level, v, new ItemStack(full ? Items.WATER_BUCKET : Items.BUCKET));
            return false;
        }
        BlockPos ground = mid.below();
        for (net.minecraft.world.item.ItemStack drop : net.minecraft.world.level.block.Block.getDrops(level.getBlockState(ground), level, ground, null)) {
            TownWork.give(level, v, drop);
        }
        level.setBlockAndUpdate(ground, Blocks.WATER.defaultBlockState());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                level.setBlockAndUpdate(mid.offset(dx, 0, dz), Blocks.COBBLESTONE.defaultBlockState());
            }
        }
        TownWork.give(level, v, new ItemStack(Items.BUCKET));
        return true;
    }

    /** Water a bucket can be filled at and not run dry: a still block with still water on two sides, near the town. */
    @Nullable
    static BlockPos pond(ServerLevel level, BlockPos heart) {
        for (int r = 4; r <= 64; r += 4) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) < r - 3) continue;
                    int x = heart.getX() + dx, z = heart.getZ() + dz;
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
                    if (!level.getFluidState(top).isSource() || !level.getFluidState(top).is(net.minecraft.tags.FluidTags.WATER)) continue;
                    int around = 0;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        if (level.getFluidState(top.relative(d)).isSource() && level.getFluidState(top.relative(d)).is(net.minecraft.tags.FluidTags.WATER)) around++;
                    }
                    if (around >= 2) return top;
                }
            }
        }
        return null;
    }

    /** The road: a worn path out from the town's edge to the plot, a stretch at a time, as the hand walks it. */
    static int road(ServerLevel level, Villages.Village v, CompoundTag c, BlockPos plot, String what) {
        List<BlockPos> todo = roadCells(level, v, plot, false);
        if (todo.isEmpty()) {
            done(level, v, c);
            return 1;
        }
        BlockPos first = todo.get(0);
        if (!TownJobs.atWork(level, v, "petitions", first.above(), what)) return -1;
        int laid = 0;
        for (BlockPos p : todo) {
            if (p.distSqr(first) > 12 * 12 || laid >= 16) break;
            BlockState over = level.getBlockState(p.above());
            if (!over.isAir() && !(over.canBeReplaced() && over.getFluidState().isEmpty())) continue;
            if (!over.isAir()) level.removeBlock(p.above(), false);
            level.setBlockAndUpdate(p, Blocks.DIRT_PATH.defaultBlockState());
            laid++;
        }
        if (roadCells(level, v, plot, false).isEmpty()) done(level, v, c);
        return 1;
    }

    static void done(ServerLevel level, Villages.Village v, CompoundTag c) {
        long day = Civics.day(level);
        c.putString("state", DONE);
        c.putLong("done", day);
        Civics.changed();
        Kind k = kind(c);
        String what = c.getString("words");
        Villages.tell(v.id(), day, k.words.replaceFirst("^an? ", "the ") + " " + c.getString("byName") + "'s petition asked for "
            + (k == Kind.ROAD ? "was laid " : k == Kind.WELL ? "was dug " : "was put up ") + what.substring(k.words.length()).trim());
        for (Tag t : c.getList("names", Tag.TAG_STRING)) {
            String[] p = t.getAsString().split("\\|", 2);
            try {
                VillageFolkEntity f = Civics.find(level, UUID.fromString(p[0]));
                if (f != null) f.persona().remember(day, "the town did what our petition asked: " + what, 3);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    // ------------------------------------------------------------------ where the player sees it

    static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return "";
        List<String> parts = new ArrayList<>();
        for (CompoundTag c : in(id, null)) {
            if (!c.getString("by").equals(f.getUUID().toString()) || LAPSED.equals(c.getString("state"))) continue;
            parts.add("petitioned for " + c.getString("words") + " (" + signed(c) + " names, " + c.getString("state") + ")");
        }
        int signed = Civics.folk(f.getUUID()).getInt("signed");
        if (signed > 0 && parts.isEmpty()) parts.add("signed " + signed + (signed == 1 ? " petition" : " petitions"));
        return String.join("; ", parts);
    }

    static List<String> board(ServerLevel level, UUID village) {
        List<String> open = new ArrayList<>(), won = new ArrayList<>();
        int need = needed(village);
        for (CompoundTag c : in(village, null)) {
            switch (c.getString("state")) {
                case OPEN -> open.add(c.getString("words") + " (" + c.getString("byName") + "; " + signed(c) + " of " + need + " names)");
                case APPROVED -> open.add(c.getString("words") + " (on the works)");
                case DONE -> { if (Civics.day(level) - c.getLong("done") <= 7) won.add(c.getString("words")); }
                default -> { }
            }
        }
        List<String> out = new ArrayList<>();
        if (!open.isEmpty()) out.add("RN|Petitions: " + String.join("; ", open) + ". Folk sign them at the board.");
        if (!won.isEmpty()) out.add("RG|Petitions granted: " + String.join("; ", won) + ".");
        return out;
    }

    static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (CompoundTag c : in(village, null)) {
            String st = c.getString("state");
            out.add("Petition: " + c.getString("words") + ", got up by " + c.getString("byName") + " on day " + (c.getLong("raised") + 1)
                + "; " + signed(c) + " names; " + (DONE.equals(st) ? "done on day " + (c.getLong("done") + 1)
                : APPROVED.equals(st) ? "put on the works by " + c.getString("approvedBy") : st) + ".");
        }
        return out;
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: this folk's grievance in words ("BENCH by No. 3 Elm Row at 1,2,3"), or null. */
    @Nullable
    public static String grievanceForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        Object[] g = v == null ? null : grievance(level, v, f);
        return g == null ? null : g[0] + " " + g[2] + " at " + ((BlockPos) g[1]).toShortString();
    }

    /** Tests: this folk's grievance got up as a petition now (its id), or -1. */
    public static int raiseForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        CompoundTag c = v == null ? null : raiseFor(level, v, f, Civics.day(level));
        return c == null ? -1 : c.getInt("n");
    }

    /** Tests: would this folk sign it, and (if so) its name put to it now. */
    public static boolean signForTests(VillageFolkEntity f, int petition) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        CompoundTag c = v == null ? null : byId(v.id(), petition);
        if (c == null || !agrees(v, f, c)) return false;
        sign(c, f);
        return true;
    }

    /** Tests: the council sits now and hears every petition with names enough (the lines run as the sitting would). */
    public static int councilForTests(ServerLevel level, UUID village) {
        List<Assemblies.Line> lines = new ArrayList<>();
        council(level, village, lines);
        for (Assemblies.Line l : lines) if (l.effect() != null) l.effect().run();
        return lines.size();
    }

    /** Tests: the town's works on its petitions, a look now. */
    public static int workForTests(ServerLevel level, Villages.Village v) {
        int n = 0;
        for (CompoundTag c : in(v.id(), APPROVED)) n += Math.max(0, work(level, v, c));
        return n;
    }

    /** Tests: a petition's state, names and place: "open 3 1,2,3". */
    public static String stateForTests(UUID village, int petition) {
        CompoundTag c = byId(village, petition);
        return c == null ? "none" : c.getString("state") + " " + signed(c) + " " + BlockPos.of(c.getLong("at")).toShortString();
    }

    /** Tests: where a petition's thing goes. */
    public static BlockPos atForTests(UUID village, int petition) {
        CompoundTag c = byId(village, petition);
        return c == null ? BlockPos.ZERO : BlockPos.of(c.getLong("at"));
    }

    /** Tests: is this folk off to the board to sign? */
    public static boolean signingForTests(VillageFolkEntity f) {
        return SIGNING.containsKey(f.getUUID());
    }

    /** Tests: the folk who agree with a petition sent to sign it now (whatever the hour). */
    public static int sendSignersForTests(ServerLevel level, Villages.Village v, int petition) {
        CompoundTag c = byId(v.id(), petition);
        if (c == null) return 0;
        int n = 0;
        for (VillageFolkEntity f : Civics.grown(v.id())) {
            if (agrees(v, f, c)) {
                SIGNING.put(f.getUUID(), new Signing(petition, f.tickCount));
                n++;
            }
        }
        return n;
    }
}
