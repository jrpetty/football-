package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [war-prep] The town's fortifications, its armoury and its training yard, on a war footing.
 *
 * <h2>The projects</h2>
 * On its guard the leader puts the town's defences at the head of what it builds (Villages.projectsWanted):
 * the wall first of all (in stone, from the Stone Age; until then the palisade below), then the armoury,
 * then a training yard for the watch and the militia, and at war the well, if the town has none, for
 * water in a siege. Each says why on the board and the books (Villages.whyBuild). A building begun before
 * the peace is finished after it.
 *
 * <h2>The works on the wall</h2>
 * What the wall wants besides is done by hand, a few blocks at a time, as the gates and the posts' ladders
 * are (Watch.keep, TownJobs): a hand the town can spare walks to the spot and the stone comes out of the
 * stores, and the earth it digs goes into them.
 * <ul>
 * <li><b>The palisade.</b> A town with no wall and too young to build one in stone stands a ring of logs
 *     two high on the wall's line, the avenues left open. When the stone wall comes to be built the
 *     palisade is taken down first and its logs go back into the stores (the wall waits for it).</li>
 * <li><b>Corner towers.</b> Each corner of the wall built out to a tower three across, two higher than the
 *     wall, with battlements at its corners.</li>
 * <li><b>Gatehouses</b> (at war). Over each gate a walk two deep at the height of the wall, crenellated
 *     on its outer face, so the gate is covered from above.</li>
 * <li><b>The ditch</b> (at war). Two deep, along the foot of the wall on the outside, a causeway left at
 *     every avenue and the towers' corners left be; only plain ground is dug, never a path somebody laid,
 *     a lamp post, a field or a building.</li>
 * </ul>
 *
 * <h2>The armoury</h2>
 * A small stone house (blueprints/armoury.txt) with racks of chests, an anvil and a grindstone. Its chests
 * are its own: they are not the stores (Villages.storeChests passes them over), so nothing in them is
 * sold, counted against the smith's work, or spent on anything else. On a war footing the couriers carry
 * the smith's swords, armour, bows and arrows from the stores to it, up to what the militia wants, and the
 * smith, finding the stores' rack empty again, makes more. The militia is armed out of it when it is
 * called up, and hands its arms back at peace (Militia). With no armoury, the stores do.
 */
public final class WarWorks {

    private WarWorks() {}

    /** How often the works on the wall and the armoury are seen to (ticks), and how many blocks a hand
     *  lays (or digs) at a time. */
    static final long EVERY = 100L;
    static final int BATCH = 6;
    /** Whether the palisade stands (Ledger note "up"): its logs are the palisade's plan. */
    static final String PALISADE = "war.palisade";

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LAST.clear();
    }

    // ------------------------------------------------------------------ the projects

    /** Has the town got (or got up) this building? */
    static boolean has(UUID village, String structure) {
        return Villages.hasBuilt(village, structure) || Villages.builtStructure(village, structure) != null;
    }

    /**
     * [war-prep] The town's project list on a war footing (Villages.projectsWanted): the wall at its head (after
     * the storehouse, if even that is still to come), the armoury, the training yard, and at war the well. The
     * stone wall waits while the palisade still stands on its line; a defence begun goes on after the peace.
     */
    public static List<String> wanted(UUID village, List<String> list) {
        List<String> out = new ArrayList<>(list);
        if (palisadeStands(village)) out.remove("fortify");        // the logs come down before the stone goes up
        Wars.Footing f = WarFooting.footing(village);
        Map<String, Villages.Site> begun = Villages.sitesOf(village);
        Villages.Age age = Villages.ageOf(village);
        int folk = Villages.headcount(village);
        if (f == Wars.Footing.PEACE) {
            // Finished after the peace, if the builders had begun it (its ground chosen).
            for (String s : new String[]{ "armoury", "trainingyard" }) {
                if (begun.containsKey(s) && !has(village, s) && !out.contains(s)) out.add(s);
            }
            return out;
        }
        int at = !out.isEmpty() && (out.get(0).equals("storage") || out.get(0).equals("storehouse")) ? 1 : 0;
        // The wall (and at war the well) first of all; the armoury and the yard after the next house, so a town on
        // its guard for weeks goes on housing its children.
        List<String> first = new ArrayList<>(), soon = new ArrayList<>();
        if (f == Wars.Footing.WAR && !has(village, "well")) first.add("well");
        if (age.ordinal() >= Villages.Age.STONE.ordinal()) {
            if (!has(village, "fortify") && !palisadeStands(village)) first.add("fortify");
            if (!has(village, "armoury") && (f == Wars.Footing.WAR || folk >= 16 || begun.containsKey("armoury"))) soon.add("armoury");
            if (!has(village, "trainingyard") && (folk >= 12 || begun.containsKey("trainingyard"))) soon.add("trainingyard");
        }
        out.removeAll(first);
        out.removeAll(soon);
        at = Math.min(at, out.size());
        out.addAll(at, first);
        int house = out.indexOf("house");
        out.addAll(house >= at + first.size() ? house + 1 : at + first.size(), soon);
        return out;
    }

    /** [war-prep] Why the town wants a defence (Villages.whyBuild), or null to say it the usual way. */
    @Nullable
    public static String whyBuild(UUID village, String project) {
        Wars.Footing f = WarFooting.footing(village);
        String against = WarFooting.names(WarFooting.foes(village));
        String stand = f == Wars.Footing.WAR ? "we are at war with " + against : "we stand on our guard against " + against;
        return switch (project) {
            case "armoury" -> "an armoury, where the town's arms are kept apart from its stores and issued to the militia"
                + (f == Wars.Footing.PEACE ? "" : ": " + stand);
            case "trainingyard" -> "a training yard, dummies and butts for the watch and the militia to drill at"
                + (f == Wars.Footing.PEACE ? "" : ": " + stand);
            case "fortify" -> f == Wars.Footing.PEACE ? null : "a wall round the village before anything else: " + stand;
            case "well" -> f == Wars.Footing.WAR ? "a well at the heart, for water if we are besieged: " + stand : null;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ the works on the wall

    /** The works, in the order the leader has them done. */
    enum Work {
        DOWN("taking the palisade down"), PALISADE("standing the palisade"), TOWERS("raising the corner towers"),
        GATEHOUSE("building the gatehouses"), DITCH("digging the ditch");

        final String doing;

        Work(String doing) { this.doing = doing; }

        String title() {
            return switch (this) {
                case DOWN, PALISADE -> "the palisade";
                case TOWERS -> "the corner towers";
                case GATEHOUSE -> "the gatehouses";
                case DITCH -> "the ditch";
            };
        }

        /** Dug out, not built up. */
        boolean digging() {
            return this == DITCH;
        }
    }

    /** Does the palisade (or any of it) still stand on the wall's line? */
    public static boolean palisadeStands(UUID village) {
        return "up".equals(Ledger.note(village, PALISADE));
    }

    /** The works this town has in hand now, in order. */
    static List<Work> works(UUID village) {
        List<Work> out = new ArrayList<>();
        boolean walled = Watch.wall(village) != null;
        boolean stone = Villages.ageOf(village).ordinal() >= Villages.Age.STONE.ordinal();
        if (palisadeStands(village) && (stone || walled)) out.add(Work.DOWN);   // the stone wall wants its line
        Wars.Footing f = WarFooting.footing(village);
        if (f == Wars.Footing.PEACE) return out;
        if (!walled && !stone) out.add(Work.PALISADE);
        if (walled) {
            out.add(Work.TOWERS);
            if (f == Wars.Footing.WAR) {
                out.add(Work.GATEHOUSE);
                out.add(Work.DITCH);
            }
        }
        return out;
    }

    /**
     * [war-prep] Every few seconds (from Raids.tick): the work in hand on the wall, a batch of blocks at a time,
     * and the armoury filled. The palisade comes down whatever the footing, once the stone wall wants its line.
     */
    public static void keep(ServerLevel level, Villages.Village v) {
        com.jrpetty.mcassistant.Guard.run("war works", () -> keepNow(level, v));
    }

    private static void keepNow(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = LAST.get(id);
        if (last != null && now - last < EVERY && now >= last) return;
        LAST.put(id, now);
        for (Work w : works(id)) {
            int r = work(level, v, w);
            if (r == 1 || r == -1) break;              // at it, or a hand on its way: the rest wait (done, or short of stone: the next)
        }
        if (WarFooting.ready(id)) fill(level, v);
    }

    /**
     * A work's plan: every block of it, laid out once from the wall (the palisade from the heart) as the
     * ground stood when it was begun, and kept with the world (Ledger "war.plan/WORK"), so that what is built
     * or dug is never measured again from what was built or dug. Null while the ground is not all loaded.
     */
    @Nullable
    static List<BlockPos> plan(ServerLevel level, Villages.Village v, Work w) {
        String key = "war.plan/" + w.name();
        String s = Ledger.note(v.id(), key);
        if (s != null && !s.isEmpty()) return decode(s);
        List<BlockPos> fresh = switch (w) {
            case PALISADE -> layPalisade(level, v);
            case TOWERS -> layTowers(level, v);
            case GATEHOUSE -> layGatehouses(level, v);
            case DITCH -> layDitch(level, v);
            case DOWN -> null;
        };
        if (fresh != null && !fresh.isEmpty()) Ledger.note(v.id(), key, encode(fresh));
        return fresh;
    }

    static String encode(List<BlockPos> all) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : all) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p.asLong());
        }
        return sb.toString();
    }

    static List<BlockPos> decode(String s) {
        List<BlockPos> out = new ArrayList<>();
        for (String p : s.split(",")) {
            try {
                out.add(BlockPos.of(Long.parseLong(p.trim())));
            } catch (NumberFormatException ignored) {
                // not a place
            }
        }
        return out;
    }

    /** The blocks of a plan still to do: open air to build in (a cell somebody has filled meanwhile is left
     *  be), or ground still to dig. */
    static List<BlockPos> undone(ServerLevel level, Work w, List<BlockPos> plan) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : plan) {
            if (!level.isLoaded(p)) continue;
            BlockState there = level.getBlockState(p);
            if (w.digging() ? diggable(there) : BuildGoal.soft(there) && there.getFluidState().isEmpty()) out.add(p);
        }
        return out;
    }

    /**
     * What the stores keep back from a work for the builders and everything else: sixty-four stone or logs, and
     * on its guard (short of war) a Wood Age town stands its palisade only out of a good pile of logs.
     */
    static int keepBack(UUID village, Work w) {
        if (w == Work.PALISADE) return WarFooting.footing(village) == Wars.Footing.WAR ? 32 : 192;
        return 64;
    }

    /** One batch of a work: 1 if blocks went in, -1 if it waits on a hand or the ground, 2 if the stores cannot
     *  spare the stone (or the logs) for it yet, 0 if it is done. */
    static int work(ServerLevel level, Villages.Village v, Work w) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        if (w == Work.DOWN) return takeDown(level, v);
        List<BlockPos> plan = plan(level, v, w);
        if (plan == null) return -1;
        List<BlockPos> left = undone(level, w, plan);
        if (left.isEmpty()) {
            if (Ledger.note(id, "war.done/" + w) == null) {
                Ledger.note(id, "war.done/" + w, Long.toString(day));
                if (!plan.isEmpty()) {
                    Villages.tell(id, day, capital(w.title()) + (w == Work.TOWERS || w == Work.GATEHOUSE ? " were" : " was") + " finished");
                }
            }
            return 0;
        }
        BlockPos first = left.get(0);
        int keep = keepBack(id, w);
        // Nobody sent to stand about at the wall waiting on stone the stores have not got to spare.
        if (!w.digging() && Crafts.stock(level, v, material(w)) <= keep) return 2;
        if (!TownJobs.atWork(level, v, "war works", first, w.doing)) return -1;
        int done = 0;
        for (BlockPos p : left) {
            if (done >= BATCH) break;
            if (p.distManhattan(first) > 8) continue;
            if (w.digging()) {
                if (dig(level, v, p)) done++;
                continue;
            }
            if (Crafts.stock(level, v, material(w)) <= keep) break;
            ItemStack made = Crafts.takeOne(level, v, material(w));
            if (made.isEmpty()) break;                                      // the stores have no more to give
            BlockState place = made.getItem() instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            level.setBlock(p, place, 3);
            if (w == Work.PALISADE) Ledger.note(id, PALISADE, "up");
            done++;
        }
        return done > 0 ? 1 : -1;
    }

    /** What a work is built of: the palisade of logs, the rest of the masons' stone bricks or cobblestone. */
    static Predicate<ItemStack> material(Work w) {
        return w == Work.PALISADE ? s -> s.is(ItemTags.LOGS) : s -> s.is(Items.STONE_BRICKS) || s.is(Items.COBBLESTONE);
    }

    /** The palisade: logs two high round the wall's line, the avenues open, only on plain open ground. */
    @Nullable
    static List<BlockPos> layPalisade(ServerLevel level, Villages.Village v) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos c = v.centre();
        int r = Watch.R;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int along = Math.abs(dx) == r ? dz : dx;
                if (Math.abs(along) <= TownPlan.AVENUE && !(Math.abs(dx) == r && Math.abs(dz) == r)) continue;
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) return null;
                int g = BuildGoal.groundTop(level, x, z);
                if (Math.abs(g - c.getY()) > 8 || !BuildGoal.wallFits(level, x, z, g)) continue;
                out.add(new BlockPos(x, g, z));
                out.add(new BlockPos(x, g + 1, z));
            }
        }
        return out;
    }

    /** The palisade down, a batch at a time, its logs into the stores. 1 at it, -1 waiting on a hand, 0 when gone. */
    static int takeDown(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        String s = Ledger.note(id, "war.plan/" + Work.PALISADE.name());
        List<BlockPos> logs = new ArrayList<>();
        if (s != null && !s.isEmpty()) {
            for (BlockPos p : decode(s)) if (!level.isLoaded(p) || level.getBlockState(p).is(BlockTags.LOGS)) logs.add(p);
        }
        long day = level.getDayTime() / 24000L;
        if (logs.isEmpty()) {
            Ledger.forget(id, PALISADE);
            Ledger.forget(id, "war.plan/" + Work.PALISADE.name());
            Ledger.forget(id, "war.done/" + Work.PALISADE);
            Villages.tell(id, day, "the palisade was taken down, so the stone wall can go up on its line");
            return 0;
        }
        BlockPos first = logs.get(0);
        if (!level.isLoaded(first)) return -1;
        if (!TownJobs.atWork(level, v, "war works", first, Work.DOWN.doing)) return -1;
        int n = 0;
        for (BlockPos p : logs) {
            if (n >= BATCH * 3) break;
            if (!level.isLoaded(p)) continue;
            BlockState st = level.getBlockState(p);
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            TownWork.give(level, v, new ItemStack(st.getBlock().asItem()));
            n++;
        }
        return 1;
    }

    /** Wall, or what a wall is capped with: what a tower's column may stand on and around. */
    private static boolean wallish(BlockState st) {
        return Watch.masonry(st) || st.getBlock() instanceof net.minecraft.world.level.block.SlabBlock;
    }

    /**
     * The corner towers: three across at each corner of the wall, from the wall's foot up to a block over the
     * corner's lantern, battlements at the four outer corners. A column with anything in it besides the wall
     * (a house, a lamp post, a post's ladder) is left out.
     */
    @Nullable
    static List<BlockPos> layTowers(ServerLevel level, Villages.Village v) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos a = Watch.wall(v.id());
        if (a == null) return out;
        int r = Watch.R;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int cx = a.getX() + sx * r, cz = a.getZ() + sz * r;
                if (!level.hasChunk((cx - 1) >> 4, (cz - 1) >> 4) || !level.hasChunk((cx + 1) >> 4, (cz + 1) >> 4)) return null;
                BlockPos top = Watch.wallTop(level, cx, cz, a.getY());
                if (top == null) continue;                              // no corner standing to build out from
                int foot = top.getY() - 4, crown = top.getY() + 1;      // the corner is five high, its lantern over it
                for (int i = -1; i <= 1; i++) {
                    for (int j = -1; j <= 1; j++) {
                        if (i == 0 && j == 0) continue;                 // the corner itself, and its lantern, stay
                        int x = cx + i, z = cz + j;
                        List<BlockPos> col = new ArrayList<>();
                        boolean clear = true;
                        for (int y = foot - 2; y <= crown; y++) {
                            BlockPos p = new BlockPos(x, y, z);
                            BlockState st = level.getBlockState(p);
                            if (BuildGoal.soft(st) && st.getFluidState().isEmpty()) { col.add(p); continue; }
                            if (y >= foot && !wallish(st)) { clear = false; break; }   // somebody's building in the way
                        }
                        if (!clear) continue;
                        // No stone hung in the air: only cells over something solid or over another of the column's.
                        for (BlockPos p : col) {
                            BlockState under = level.getBlockState(p.below());
                            if (under.isSolid() || col.contains(p.below())) out.add(p);
                        }
                        BlockPos crenel = new BlockPos(x, crown + 1, z);
                        if (i != 0 && j != 0 && BuildGoal.soft(level.getBlockState(crenel))) out.add(crenel);
                    }
                }
            }
        }
        return out;
    }

    /** The gatehouses: over each hung gate, a walk two deep at the wall's height, crenellated outside. */
    @Nullable
    static List<BlockPos> layGatehouses(ServerLevel level, Villages.Village v) {
        List<BlockPos> out = new ArrayList<>();
        if (Watch.wall(v.id()) == null) return out;
        if (Watch.gates(level, v.id()).isEmpty()) return null;            // not looked over yet (Watch.keep)
        for (Watch.Gate g : Watch.gates(level, v.id())) {
            if (g.doors().isEmpty()) continue;
            BlockPos mid = g.doors().get(g.doors().size() / 2);
            Direction along = g.out().getClockWise(), in = g.out().getOpposite();
            for (int k = -2; k <= 2; k++) {
                BlockPos col = mid.relative(along, k);
                for (BlockPos p : new BlockPos[]{ col.above(3), col.relative(in).above(3), k % 2 == 0 ? col.above(4) : null }) {
                    if (p != null && BuildGoal.soft(level.getBlockState(p))) out.add(p);
                }
            }
        }
        return out;
    }

    /**
     * The ditch: two deep along the outside foot of the wall (where the wall itself stands; where it went round a
     * house there is no ditch either), a causeway at each avenue, the towers' corners left be. Only plain ground
     * level with the wall's foot with nothing on it is dug.
     */
    @Nullable
    static List<BlockPos> layDitch(ServerLevel level, Villages.Village v) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos a = Watch.wall(v.id());
        if (a == null) return out;
        int r = Watch.R;
        for (Direction side : Watch.SIDES) {
            for (int along = -(r - 2); along <= r - 2; along++) {
                if (Math.abs(along) <= TownPlan.AVENUE + 1) continue;      // the causeway over it to the gate
                BlockPos wall = Watch.cell(a, side, along);
                if (!level.hasChunk(wall.getX() >> 4, wall.getZ() >> 4)) return null;
                BlockPos top = Watch.wallTop(level, wall.getX(), wall.getZ(), a.getY());
                if (top == null) continue;
                BlockPos ground = new BlockPos(wall.getX(), top.getY() - 3, wall.getZ()).relative(side);   // level with the wall's foot
                BlockState over = level.getBlockState(ground.above());
                if (!over.isAir() && !(BuildGoal.soft(over) && over.getFluidState().isEmpty())) continue;
                if (!diggable(level.getBlockState(ground))) continue;
                out.add(ground);
                if (diggable(level.getBlockState(ground.below()))) out.add(ground.below());
            }
        }
        return out;
    }

    /** Ground a ditch may be dug through: earth, sand, gravel, stone; never a floor, a path laid in stone, water. */
    static boolean diggable(BlockState st) {
        return st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.PODZOL)
            || st.is(Blocks.DIRT_PATH) || st.is(Blocks.ROOTED_DIRT) || st.is(Blocks.MYCELIUM) || st.is(Blocks.SAND)
            || st.is(Blocks.GRAVEL) || st.is(Blocks.STONE) || st.is(Blocks.CLAY) || st.is(Blocks.RED_SAND);
    }

    /** Dig one block of the ditch: what it gives into the stores. */
    static boolean dig(ServerLevel level, Villages.Village v, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (!diggable(st)) return false;
        for (ItemStack drop : Block.getDrops(st, level, p, null)) TownWork.give(level, v, drop);
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        return true;
    }

    /** Tests: the next batch of a work, now (palisade, towers, gatehouse, ditch, down). Returns blocks left to do. */
    public static int workForTests(ServerLevel level, Villages.Village v, String work) {
        Work w = Work.valueOf(work.toUpperCase(Locale.ROOT));
        work(level, v, w);
        if (w == Work.DOWN) return palisadeStands(v.id()) ? 1 : 0;
        List<BlockPos> plan = plan(level, v, w);
        return plan == null ? -1 : undone(level, w, plan).size();
    }

    /** Tests: the works this town has in hand, by name. */
    public static List<String> worksForTests(UUID village) {
        List<String> out = new ArrayList<>();
        for (Work w : works(village)) out.add(w.name().toLowerCase(Locale.ROOT));
        return out;
    }

    // ------------------------------------------------------------------ the armoury

    /** The town's armoury, if it stands. */
    @Nullable
    public static Ledger.Building armoury(UUID village) {
        return Villages.builtStructure(village, "armoury");
    }

    /** [war-prep] Is this chest the armoury's (Villages.storeChests passes it over: the arms are not the stores')? */
    public static boolean inArmoury(UUID village, BlockPos p) {
        Ledger.Building b = armoury(village);
        if (b == null) return false;
        int[] half = com.jrpetty.mcassistant.entity.goal.Blueprints.fullHalf("armoury");
        int reach = Math.max(half[0], half[1]);
        BlockPos a = b.anchor();
        return Math.abs(p.getX() - a.getX()) <= reach && Math.abs(p.getZ() - a.getZ()) <= reach
            && p.getY() >= a.getY() - 1 && p.getY() <= a.getY() + 3;
    }

    /** The armoury's chests. */
    static List<Container> racks(ServerLevel level, UUID village) {
        List<Container> out = new ArrayList<>();
        Ledger.Building b = armoury(village);
        if (b == null || !level.isLoaded(b.anchor())) return out;
        int[] half = com.jrpetty.mcassistant.entity.goal.Blueprints.fullHalf("armoury");
        int reach = Math.max(half[0], half[1]);
        for (BlockPos p : BlockPos.betweenClosed(b.anchor().offset(-reach, 0, -reach), b.anchor().offset(reach, 2, reach))) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) out.add(c);
        }
        return out;
    }

    /** How many of what matches the armoury holds. */
    public static int inArmoury(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        int n = 0;
        for (Container c : racks(level, village)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && what.test(s)) n += s.getCount();
            }
        }
        return n;
    }

    /** One of what matches out of the armoury, else out of the stores; empty if neither has one. */
    static ItemStack takeArms(ServerLevel level, Villages.Village v, Predicate<ItemStack> what) {
        for (Container c : racks(level, v.id())) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                ItemStack one = s.split(1);
                c.setChanged();
                return one;
            }
        }
        return Crafts.takeOne(level, v, what);
    }

    /** Arms handed back: into the armoury's racks, or the stores if it has none (or no room). */
    static void returnArms(ServerLevel level, Villages.Village v, ItemStack stack) {
        ItemStack left = stack.copy();
        for (Container c : racks(level, v.id())) {
            for (int i = 0; i < c.getContainerSize() && !left.isEmpty(); i++) {
                if (c.getItem(i).isEmpty()) {
                    c.setItem(i, left.copy());
                    left = ItemStack.EMPTY;
                    c.setChanged();
                }
            }
            if (left.isEmpty()) return;
        }
        if (!left.isEmpty()) TownWork.give(level, v, left);
        if (!left.isEmpty()) Block.popResource(level, v.centre().above(), left);
    }

    /** What the armoury keeps against the militia: a blade each, armour for half, a bow for a third, arrows. */
    record Want(String what, Predicate<ItemStack> is, int keep) {}

    static List<Want> wants(UUID village) {
        int militia = Math.max(0, Militia.members(village).size() - Militia.called(village));
        List<Want> out = new ArrayList<>();
        out.add(new Want("blades", s -> s.getItem() instanceof SwordItem, militia));
        out.add(new Want("armour", s -> s.getItem() instanceof ArmorItem, (militia + 1) / 2));
        out.add(new Want("bows", s -> s.is(Items.BOW) || s.is(Items.CROSSBOW), militia / 3));
        out.add(new Want("arrows", s -> s.is(Items.ARROW), 16 * ((militia + 1) / 2)));
        return out;
    }

    /** The blades the militia is short of, armoury and stores together: the elder digs for iron while it is. */
    static int armsShort(ServerLevel level, Villages.Village v) {
        int militia = Math.max(0, Militia.members(v.id()).size() - Militia.called(v.id()));
        Predicate<ItemStack> blade = s -> s.getItem() instanceof SwordItem;
        return Math.max(0, militia - inArmoury(level, v.id(), blade) - Crafts.stock(level, v, blade));
    }

    /**
     * The couriers carry the smith's arms from the stores to the armoury, up to what it keeps against the
     * militia, leaving the stores one of each for the watch's own kit (VillageFolkEntity.guardKitFromTheStores).
     */
    static int fill(ServerLevel level, Villages.Village v) {
        Ledger.Building b = armoury(v.id());
        if (b == null || !level.isLoaded(b.anchor()) || racks(level, v.id()).isEmpty()) return 0;
        for (Want w : wants(v.id())) {
            int have = inArmoury(level, v.id(), w.is());
            int spare = Crafts.stock(level, v, w.is()) - (w.what().equals("arrows") ? 32 : 1);
            if (have >= w.keep() || spare <= 0) continue;
            if (!TownJobs.atWork(level, v, "armoury", b.anchor(), "carrying " + w.what() + " to the armoury", AssistantEntity.StationTask.HAUL)) return 0;
            int moved = 0;
            int n = Math.min(Math.min(w.keep() - have, spare), w.what().equals("arrows") ? 32 : 3);
            for (int i = 0; i < n; i++) {
                ItemStack one = Crafts.takeOne(level, v, w.is());
                if (one.isEmpty()) break;
                returnArms(level, v, one);
                moved++;
            }
            return moved;
        }
        return 0;
    }

    /** Tests: fill the armoury from the stores now (a courier there at once). Returns what was carried. */
    public static int fillForTests(ServerLevel level, Villages.Village v) {
        int n = 0, got;
        while ((got = fill(level, v)) > 0 && n < 200) n += got;
        return n;
    }

    // ------------------------------------------------------------------ the morning, and telling

    /** The leader's morning on a war footing: nothing to order that the project list and the works do not. */
    static void morning(ServerLevel level, Villages.Village v, long day, Wars.Footing footing) {
        UUID id = v.id();
        // The works ordered, once each footing: told in the chronicle.
        String key = "war.works.ordered";
        String ordered = footing.name() + "|" + WarFooting.footingSince(id);
        if (ordered.equals(Ledger.note(id, key))) return;
        Ledger.note(id, key, ordered);
        List<String> works = new ArrayList<>();
        for (Work w : works(id)) if (w != Work.DOWN) works.add(w.title());
        String next = Villages.nextProject(id);
        if (next != null && (next.equals("fortify") || next.equals("armoury") || next.equals("trainingyard") || next.equals("well"))) {
            works.add(0, Villages.spoken(next));
        }
        if (!works.isEmpty()) Villages.tell(id, day, "the leader set the town to its defences: " + String.join(", ", works));
    }

    /** The war page's lines: the wall and its works, the armoury and the yard. */
    static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        List<String> wall = new ArrayList<>();
        wall.add(Watch.wall(id) != null ? "the wall stands" : palisadeStands(id) ? "a palisade of logs" : "no wall yet");
        for (Work w : new Work[]{ Work.TOWERS, Work.GATEHOUSE, Work.DITCH }) {
            String done = Ledger.note(id, "war.done/" + w);
            if (done != null && !done.isEmpty()) wall.add(w.title() + " finished");
        }
        for (Work w : works(id)) {
            if (w == Work.DOWN) { wall.add("the palisade coming down"); continue; }
            List<BlockPos> all = plan(level, v, w);
            if (all == null) continue;
            int left = undone(level, w, all).size();
            if (left > 0) wall.add(w.title() + ": " + (all.size() - left) + " of " + all.size() + " blocks");
        }
        out.add("Fortifications: " + String.join("; ", wall) + ".");
        Ledger.Building b = armoury(id);
        if (b == null) {
            out.add("The armoury: " + (WarFooting.ready(id) ? "not built yet; the militia is armed out of the stores." : "none."));
        } else {
            List<String> held = new ArrayList<>();
            for (Want w : wants(id)) held.add(inArmoury(level, id, w.is()) + " " + w.what() + (w.keep() > 0 ? " (keeps " + w.keep() + ")" : ""));
            out.add("The armoury: " + String.join(", ", held) + ".");
        }
        out.add("The training yard: " + (Villages.builtStructure(id, "trainingyard") != null ? "built; the watch takes a turn at the dummies every day."
            : "none yet; the militia drills " + (Villages.builtStructure(id, "barracks") != null ? "before the barracks." : "on the square.")));
        return out;
    }

    /** The board's line for the works in hand, or null. */
    @Nullable
    static String boardLine(UUID village) {
        List<String> doing = new ArrayList<>();
        for (Work w : works(village)) doing.add(w.title());
        return doing.isEmpty() ? null : "Our defences: " + String.join(", then ", doing) + ".";
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
