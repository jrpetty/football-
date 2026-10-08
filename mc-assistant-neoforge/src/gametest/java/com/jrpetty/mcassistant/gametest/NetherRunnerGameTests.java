package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CaveDwellers;
import com.jrpetty.mcassistant.entity.Crafts;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.JobWorth;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Nether;
import com.jrpetty.mcassistant.entity.NetherGuests;
import com.jrpetty.mcassistant.entity.NetherHome;
import com.jrpetty.mcassistant.entity.NetherOutpost;
import com.jrpetty.mcassistant.entity.NetherPlan;
import com.jrpetty.mcassistant.entity.NetherRunners;
import com.jrpetty.mcassistant.entity.NetherRuns;
import com.jrpetty.mcassistant.entity.NetherWork;
import com.jrpetty.mcassistant.entity.SearchParties;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WatchKit;
import com.jrpetty.mcassistant.entity.Workshop;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.NetherItems;
import com.jrpetty.mcassistant.item.RunnersSatchelItem;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [nether] The Nether runners (NetherRunners, NetherRuns, NetherWork, NetherOutpost, NetherPlan, NetherGuests, NetherHome)
 * and their two things (the Gold Charm, the Runner's Satchel). Each test on its own ground (x 1320000 to 1338000, z 66000),
 * in a batch of its own. The trips go through the server's own Nether: the gateway at home is a real lit portal, and the
 * runners go through it (Entity.changeDimension, by the portal's own rule) into a pocket cut out of the real Nether over
 * it at an eighth of the distance, a portal waiting there for them as a player's would be, and come back the same way.
 *
 * <ul>
 * <li>nr01: the trade opens in the Nether Age once the gateway is lit, one runner for a town of twenty-two, the best
 *     veteran picked (the guard of seven, not the miner of ten, never the new hand, the farmer or the idle one), with a
 *     head start; the most dangerous, most skilled post in the town.</li>
 * <li>nr02: the smith beats two gold charms and the tailor stitches two satchels by their recipes, out of the stores; the
 *     kit (the watch's iron, a bow and arrows, a shield, fire resistance, the satchel, the leader's flint and steel) with
 *     the charm on the brow; a piglin leaves the runner in gold alone and turns on a farmer without; the satchel packs and
 *     unpacks, and floats on lava whole.</li>
 * <li>nr03: through the gateway into the Nether and back: the same folk on both sides, its ground kept awake while it is
 *     there and let go after, the town knowing where it is all the while.</li>
 * <li>nr04: a day's run: quartz dug, glowstone knocked down, the fortress's wart picked, blazes shot from range; the haul
 *     (the satchel's too) into the storehouse, counted, told.</li>
 * <li>nr05: a barter: gold thrown to a piglin by a runner in gold, what the game's bartering table throws back picked up
 *     and brought home.</li>
 * <li>nr06: the wart farm laid from the runners' soul sand, planted, grown and picked; the brewer makes magma cream of the
 *     runners' blaze powder and a slime ball, and brews fire resistance.</li>
 * <li>nr07: a first run walls the portal in on the far side: cobblestone walls and roof, a door, soul lanterns.</li>
 * <li>nr08: a runner on fire drinks its fire resistance; one badly hurt falls back to the outpost.</li>
 * <li>nr09: a runner out of sight is missed and searched for, and found; lost, the town sends a rescue party through, the
 *     watch with it, and brings it home.</li>
 * <li>nr10: a player goes along: booked, waited for at the gateway and on the far side, a share of the haul kept for it.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class NetherRunnerGameTests {

    static final String EMPTY = "empty";
    static final int Z = 66000;
    /** The Nether pocket's floor. */
    static final int NY = 70;

    record Town(UUID village, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, List<VillageFolkEntity> folk, BlockPos gate) {}

    record Pocket(ServerLevel nether, BlockPos portal, int nx, int nz, NetherOutpost.Room room) {}

    /** A Nether Age town of these trades (the founder first), its stores a storehouse, its gateway built (lit, or not). */
    static Town town(GameTestHelper helper, int x, boolean lit, StationTask... trades) {
        ServerLevel level = helper.getLevel();
        Kit.noLeftoverPlayers(level);
        Kit.reset(level);
        CaveDwellers.resetForTests();
        WatchKit.resetForTests();
        Workshop.resetForTests();
        SearchParties.resetForTests();
        Nether.resetForTests();
        level.setDayTime(2000);
        level.updateSkyBrightness();
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "folk " + i);
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        StorehouseBlockEntity store = CaveDwellerGameTests.storehouse(helper, level, heart, 8, 8);
        CaveDwellerGameTests.onlyTheStorehouse(level, village);
        Villages.ageForTests(village, Villages.Age.NETHER);
        for (int i = 0; i < trades.length; i++) {
            folk.get(i).setAgeForTests(30);
            folk.get(i).setJob(trades[i]);
        }
        BlockPos gate = Kit.surface(level, x - 12, Z);
        gateway(level, gate, lit);
        Villages.builtAtForTests(village, "gateway", gate);
        Ledger.built(village, "gateway", gate, Direction.EAST);
        Villages.noteProject(village, "gateway", level.getGameTime());
        if (lit) Ledger.note(village, "nether.opened", "0");
        return new Town(village, Villages.get(village), heart, store, folk, gate);
    }

    /** An obsidian frame four wide and five high along x, its lowest inside block here, lit (the game's own way). */
    static void gateway(ServerLevel level, BlockPos at, boolean lit) {
        for (int dx = -1; dx <= 2; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                boolean edge = dx == -1 || dx == 2 || dy == -1 || dy == 3;
                level.setBlock(at.offset(dx, dy, 0), edge ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            }
        }
        for (int dx = -1; dx <= 2; dx++) for (int dz = -3; dz <= 3; dz++) if (dz != 0) for (int dy = 0; dy <= 3; dy++) {
            level.setBlock(at.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
        }
        if (lit) PortalShape.findEmptyPortalShape(level, at, Direction.Axis.X).ifPresent(PortalShape::createPortalBlocks);
    }

    /**
     * The far side: a pocket forty by forty and eight high cut out of the real Nether over the gateway (an eighth of the
     * distance), netherrack all round it, glowstone in its roof for light, its ground kept loaded; a portal in it where
     * the gateway comes out (lit, as a player's would be); with the outpost already walled round it, or not.
     */
    static Pocket pocket(GameTestHelper helper, Town t, boolean outpost) {
        ServerLevel home = helper.getLevel();
        ServerLevel nether = home.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "the server has its Nether");
        int nx = Math.floorDiv(t.gate().getX(), 8), nz = Math.floorDiv(t.gate().getZ(), 8);
        for (int cx = (nx - 24) >> 4; cx <= (nx + 24) >> 4; cx++) {
            for (int cz = (nz - 24) >> 4; cz <= (nz + 24) >> 4; cz++) {
                nether.setChunkForced(cx, cz, true);
                nether.getChunk(cx, cz);
            }
        }
        for (int x = nx - 21; x <= nx + 21; x++) {
            for (int z = nz - 21; z <= nz + 21; z++) {
                boolean edge = Math.abs(x - nx) >= 20 || Math.abs(z - nz) >= 20;
                for (int y = NY - 3; y <= NY + 10; y++) {
                    boolean rock = y < NY || y >= NY + 9 || edge;
                    var st = rock ? Blocks.NETHERRACK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    if (y == NY + 9 && !edge && Math.floorMod(x * 7 + z * 13, 19) == 0) st = Blocks.GLOWSTONE.defaultBlockState();
                    nether.setBlock(new BlockPos(x, y, z), st, 2 | 16);
                }
            }
        }
        for (Entity e : nether.getEntitiesOfClass(Entity.class, new AABB(nx - 22, NY - 4, nz - 22, nx + 22, NY + 12, nz + 22),
                e -> !(e instanceof Player))) e.discard();
        BlockPos portal = new BlockPos(nx, NY, nz);
        gateway(nether, portal, true);
        NetherOutpost.Room room = outpost ? NetherOutpost.stampForTests(nether, t.village(), portal, home.getDayTime() / 24000L) : null;
        // The fortress the runs make for, in the pocket (the server's own may be anywhere, past a sea of lava).
        NetherWork.fortressForTests(portal.offset(-6, 0, -12));
        // Its look round kept inside the pocket: the server's own Nether past the walls is different on every machine.
        NetherWork.boundsForTests(new net.minecraft.world.level.levelgen.structure.BoundingBox(nx - 19, NY - 3, nz - 19, nx + 19, NY + 9, nz + 19));
        if (outpost) helper.assertTrue(room != null && NetherOutpost.built(t.village()), "the outpost stands round the portal on the far side");
        return new Pocket(nether, portal, nx, nz, room);
    }

    /** The pocket's ground let go again. */
    static void release(Pocket p) {
        NetherWork.boundsForTests(null);
        for (int cx = (p.nx() - 24) >> 4; cx <= (p.nx() + 24) >> 4; cx++) {
            for (int cz = (p.nz() - 24) >> 4; cz <= (p.nz() + 24) >> 4; cz++) p.nether().setChunkForced(cx, cz, false);
        }
    }

    static ItemStack fireRes() {
        return PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE);
    }

    /** The runners' kit for so many in the stores: the watch's iron, a sword, a shield, a bow, a pick, bread, three fire
     *  resistance, a gold charm and a satchel each; arrows, cobblestone, torches, a flint and steel, a door, soul lanterns. */
    static List<ItemStack> kit(int n, boolean charms) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new ItemStack(Items.IRON_HELMET));
            out.add(new ItemStack(Items.IRON_CHESTPLATE));
            out.add(new ItemStack(Items.IRON_LEGGINGS));
            out.add(new ItemStack(Items.IRON_BOOTS));
            out.add(new ItemStack(Items.IRON_SWORD));
            out.add(new ItemStack(Items.SHIELD));
            out.add(new ItemStack(Items.BOW));
            out.add(new ItemStack(Items.IRON_PICKAXE));
            out.add(new ItemStack(Items.BREAD, 32));
            out.add(new ItemStack(Items.ARROW, 64));
            for (int k = 0; k < 3; k++) out.add(fireRes());
            if (charms) out.add(new ItemStack(NetherItems.GOLD_CHARM.get()));
            out.add(new ItemStack(NetherItems.RUNNERS_SATCHEL.get()));
        }
        for (int k = 0; k < 4; k++) out.add(new ItemStack(Items.COBBLESTONE, 64));
        out.add(new ItemStack(Items.TORCH, 64));
        out.add(new ItemStack(Items.FLINT_AND_STEEL));
        out.add(new ItemStack(Items.OAK_DOOR));
        out.add(new ItemStack(Items.SOUL_LANTERN, 3));
        return out;
    }

    /** The storehouse filled with these, and the village's counts made afresh. */
    static void fill(Town t, List<ItemStack> goods) {
        for (int i = 0; i < t.store().getContainerSize(); i++) t.store().setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < goods.size() && i < t.store().getContainerSize(); i++) t.store().setItem(i, goods.get(i));
        Villages.forgetStock();
        Villages.forgetStores(t.village());
        com.jrpetty.mcassistant.entity.Budget.forget(t.village());
    }

    static int stock(ServerLevel level, UUID village, Item it) {
        return Market.stock(level, village, s -> s.is(it));
    }

    /** A runner wherever it is (at home or through the gateway), alive; or null. */
    static VillageFolkEntity find(ServerLevel home, UUID u) {
        for (ServerLevel l : new ServerLevel[]{ home, home.getServer().getLevel(Level.NETHER) }) {
            if (l != null && l.getEntity(u) instanceof VillageFolkEntity f && f.isAlive()) return f;
        }
        return null;
    }

    static boolean through(ServerLevel home, UUID u) {
        VillageFolkEntity f = find(home, u);
        return f != null && f.level().dimension() == Level.NETHER;
    }

    static boolean homeAgain(ServerLevel home, UUID u) {
        VillageFolkEntity f = find(home, u);
        return f != null && f.level() == home;
    }

    /** Carried at all: worn, in a hand, or in the pack. */
    static int has(VillageFolkEntity f, Predicate<ItemStack> what) {
        int n = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) if (what.test(f.getItemBySlot(slot))) n += f.getItemBySlot(slot).getCount();
        for (ItemStack s : f.getInventoryItems()) if (what.test(s)) n += s.getCount();
        return n;
    }

    /** The runners of the test, made runners now (the trade's own way, a level of it). */
    static void runners(VillageFolkEntity... team) {
        for (int i = 0; i < team.length; i++) {
            team[i].setJob(StationTask.NETHER);
            team[i].tradeXpForTests(StationTask.NETHER, AssistantEntity.xpForLevel(team.length - i + 4));
        }
    }

    static void log(String test, ServerLevel level, NetherRuns.Run r, UUID... team) {
        StringBuilder sb = new StringBuilder(test + " tick " + level.getGameTime() + ": ");
        sb.append(r == null ? "no run" : r.phaseWords() + (r.task() != null ? " (" + r.task().words() + ")" : "") + ", mined " + r.mined() + ", blazes "
            + r.blazes() + ", barters " + r.barters() + ", haul " + r.haul());
        if (r != null && r.task() != null && r.task().at() != null) sb.append(" at ").append(r.task().at().toShortString());
        for (UUID u : team) {
            VillageFolkEntity f = find(level, u);
            sb.append("; ").append(f == null ? "gone" : f.displayNameCap() + " in " + f.level().dimension().location().getPath() + " at "
                + f.blockPosition().toShortString() + " " + f.hobbyNow() + " (" + (int) f.getHealth() + " hp"
                + (f.getLastDamageSource() != null ? ", hurt by " + f.getLastDamageSource().getMsgId() : "")
                + (f.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE) ? ", fire resistant" : "") + ")");
        }
        Kit.log(sb.toString());
    }

    // ============================================================ nr01: the trade opens, and who is picked

    /**
     * A town of twenty-two on the roll in the Nether Age: five guards (one of seven years' level, one a new hand), a cave
     * dweller of five, two miners (one of ten), a farmer and an idle hand. Its gateway built and dark, it wants no runner;
     * lit (by its own flint and steel, out of the stores), it wants one, and picks the guard of seven with a head start at
     * the runs; a second look takes nobody. Two at sixty, three at a hundred, never more; and the runs are the most
     * dangerous and skilled post in the town.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "nr01_trade")
    public static void nr01_trade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1320000, false, StationTask.GUARD, StationTask.GUARD, StationTask.GUARD, StationTask.GUARD, StationTask.GUARD,
            StationTask.CAVE, StationTask.MINE, StationTask.MINE, StationTask.FARM, StationTask.NONE);
        UUID id = t.village();
        for (int i = 0; i < 12; i++) Villages.recordBirth(id);
        int heads = Villages.headcount(id);
        VillageFolkEntity ace = null, fresh = null;
        for (int i = 0; i < 5; i++) {
            VillageFolkEntity g = t.folk().get(i);
            if (g.isElder()) continue;
            if (ace == null) ace = g;
            else if (fresh == null) fresh = g;
        }
        VillageFolkEntity caver = t.folk().get(5), miner = t.folk().get(6), farmer = t.folk().get(8), idle = t.folk().get(9);
        helper.assertTrue(ace != null && fresh != null, "two guards who are not the elder");
        for (VillageFolkEntity f : t.folk()) {
            StationTask job = f.stationTask();
            int lv = f == ace ? 7 : f == fresh ? 1 : f == caver ? 5 : f == miner ? 10 : 2;
            if (job != StationTask.NONE) f.tradeXpForTests(job, AssistantEntity.xpForLevel(lv));
        }
        fill(t, List.of(new ItemStack(Items.FLINT), new ItemStack(Items.IRON_INGOT, 4)));
        int darkWanted = NetherRunners.wanted(id);
        boolean darkReady = NetherRunners.ready(id);
        Nether.tickForTests(level, t.v());                                // the gateway lit with the stores' flint and iron
        boolean opened = Nether.opened(id);
        boolean lit = false;
        for (BlockPos p : BlockPos.betweenClosed(t.gate().offset(-1, 0, -1), t.gate().offset(2, 3, 1))) if (level.getBlockState(p).is(Blocks.NETHER_PORTAL)) lit = true;
        int wanted = NetherRunners.wanted(id);
        List<VillageFolkEntity> team = NetherRunners.runners(id);
        VillageFolkEntity first = team.isEmpty() ? null : team.get(0);
        VillageFolkEntity second = NetherRunners.appoint(level, t.v(), level.getDayTime() / 24000L);
        Kit.log("nr01 " + heads + " on the roll; dark: wanted " + darkWanted + " (ready " + darkReady + "); lit " + lit + ", opened " + opened
            + "; wanted " + wanted + "; picked " + (first == null ? "nobody" : first.displayNameCap() + " (was the guard of seven: " + (first == ace) + ")")
            + ", then " + (second == null ? "nobody" : second.displayNameCap()) + "; runs level " + (first == null ? -1 : first.tradeLevel(StationTask.NETHER))
            + "; guard share " + Villages.share(id, StationTask.GUARD));
        helper.assertTrue(heads >= 20, "a town of twenty or more on the roll: " + heads);
        helper.assertTrue(darkWanted == 0 && !darkReady, "no runners while the gateway is dark");
        helper.assertTrue(lit && opened, "the gateway lit, with the stores' flint and iron, and the Nether open");
        helper.assertTrue(wanted == 1, "a town of " + heads + " wants one runner: " + wanted);
        helper.assertTrue(first == ace && team.size() == 1, "the guard of seven picked, before the miner of ten and the cave dweller of five");
        helper.assertTrue(second == null, "one runner, and no more");
        helper.assertTrue(fresh.stationTask() == StationTask.GUARD && idle.stationTask() == StationTask.NONE && farmer.stationTask() == StationTask.FARM
            && miner.stationTask() == StationTask.MINE, "never the new hand, the idle one or the farmer; the miner kept at the rock");
        helper.assertTrue(ace.tradeLevel(StationTask.NETHER) >= 5, "a head start at the runs: " + ace.tradeLevel(StationTask.NETHER));
        helper.assertTrue(NetherRunners.team(19) == 0 && NetherRunners.team(20) == 1 && NetherRunners.team(60) == 2 && NetherRunners.team(100) == 3
            && NetherRunners.team(400) == 3, "one from twenty, two at sixty, three at a hundred, never more");
        double hardest = JobWorth.postFor(StationTask.NETHER, null).difficulty();
        for (StationTask s : StationTask.values()) {
            if (s == StationTask.NONE || s == StationTask.NETHER) continue;
            helper.assertTrue(JobWorth.postFor(s, null).difficulty() <= hardest, "the runs as hard and skilled as anything: " + s);
        }
        // The watch's health is twice a plain folk's (more for a sturdy one, or a loyal one); a runner keeps the doubling.
        Kit.log("nr01 health: the runner " + ace.getMaxHealth() + ", a guard " + t.folk().get(1).getMaxHealth() + ", the farmer " + farmer.getMaxHealth());
        helper.assertTrue(ace.getMaxHealth() >= 40.0F, "a runner as hardy as the watch: "
            + ace.getMaxHealth());
        helper.succeed();
    }

    // ============================================================ nr02: the charm, the satchel, the kit, the piglins

    /**
     * The smith beats two gold charms (an ingot, three nuggets of another and a string each) and a flint and steel; the
     * tailor stitches two satchels (five leather, a string, a magma cream). The two runners are fitted out: the charm on
     * the brow and the watch's iron elsewhere, a bow, arrows, a shield, fire resistance, the satchel; the leader the
     * flint and steel. A piglin leaves the runner in gold alone, and turns on a farmer near another with none. A satchel
     * takes the haul in and gives it back out; dropped in lava it floats, whole.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "nr02_kit")
    public static void nr02_kit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1322000, true, StationTask.NETHER, StationTask.NETHER, StationTask.SMITH, StationTask.TAILOR, StationTask.FARM);
        UUID id = t.village();
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1), smith = t.folk().get(2), tailor = t.folk().get(3), farmer = t.folk().get(4);
        runners(a, b);
        List<ItemStack> goods = new ArrayList<>(kit(2, false));
        goods.add(new ItemStack(Items.GOLD_INGOT, 4));
        goods.add(new ItemStack(Items.STRING, 4));
        goods.add(new ItemStack(Items.LEATHER, 16));
        goods.add(new ItemStack(Items.MAGMA_CREAM, 2));
        goods.add(new ItemStack(Items.FLINT, 2));
        goods.add(new ItemStack(Items.IRON_INGOT, 24));
        goods.removeIf(s -> s.is(Items.FLINT_AND_STEEL) || s.getItem() instanceof RunnersSatchelItem);
        fill(t, goods);
        int gold0 = stock(level, id, Items.GOLD_INGOT), string0 = stock(level, id, Items.STRING), leather0 = stock(level, id, Items.LEATHER);
        int cream0 = stock(level, id, Items.MAGMA_CREAM);
        // The makers, by their recipes.
        String c1 = NetherRunners.smith(level, t.v(), smith);
        String c2 = NetherRunners.smith(level, t.v(), smith);
        String c3 = NetherRunners.smith(level, t.v(), smith);
        String s1 = NetherRunners.tailor(level, t.v(), tailor);
        String s2 = NetherRunners.tailor(level, t.v(), tailor);
        String s3 = NetherRunners.tailor(level, t.v(), tailor);
        int charms = stock(level, id, NetherItems.GOLD_CHARM.get()), satchels = stock(level, id, NetherItems.RUNNERS_SATCHEL.get());
        Kit.log("nr02 smith: " + c1 + " | " + c2 + " | " + c3 + "; tailor: " + s1 + " | " + s2 + " | " + s3 + "; charms " + charms + ", satchels "
            + satchels + "; gold " + gold0 + " -> " + stock(level, id, Items.GOLD_INGOT) + " (nuggets " + stock(level, id, Items.GOLD_NUGGET) + "), string "
            + string0 + " -> " + stock(level, id, Items.STRING) + ", leather " + leather0 + " -> " + stock(level, id, Items.LEATHER) + ", cream " + cream0
            + " -> " + stock(level, id, Items.MAGMA_CREAM));
        helper.assertTrue(charms == 2 && c1 != null && c1.contains("charm") && c2 != null && c2.contains("charm"), "two gold charms, one a runner");
        helper.assertTrue(c3 != null && c3.contains("flint and steel") && stock(level, id, Items.FLINT_AND_STEEL) == 1, "then the leader's flint and steel");
        helper.assertTrue(stock(level, id, Items.GOLD_INGOT) == gold0 - 3 && stock(level, id, Items.GOLD_NUGGET) == 3,
            "an ingot beaten into nuggets, an ingot and three nuggets a charm");
        helper.assertTrue(satchels == 2 && s3 == null && stock(level, id, Items.LEATHER) == leather0 - 10 && stock(level, id, Items.MAGMA_CREAM) == cream0 - 2
            && stock(level, id, Items.STRING) == string0 - 4, "two satchels of five leather, a string and a magma cream each; no third");
        // The kit.
        NetherPlan.Plan plan = NetherPlan.plan(level, t.v(), List.of(a, b));
        List<String> gotA = NetherRunners.kitUp(level, t.v(), a, plan, true, true);
        List<String> gotB = NetherRunners.kitUp(level, t.v(), b, plan, false, true);
        Kit.log("nr02 plan: " + plan.words() + " | " + String.join("; ", plan.reckoning()));
        Kit.log("nr02 " + a.displayNameCap() + ": " + gotA + " | " + NetherRunners.cardLine(a));
        Kit.log("nr02 " + b.displayNameCap() + ": " + gotB + " | " + NetherRunners.cardLine(b));
        for (VillageFolkEntity f : new VillageFolkEntity[]{ a, b }) {
            helper.assertTrue(f.getItemBySlot(EquipmentSlot.HEAD).is(NetherItems.GOLD_CHARM.get()) && NetherRunners.wearsGold(f), "the gold charm on the brow");
            helper.assertTrue(f.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE) && f.getItemBySlot(EquipmentSlot.FEET).is(Items.IRON_BOOTS),
                "the watch's iron elsewhere");
            helper.assertTrue(has(f, s -> s.is(Items.BOW)) == 1 && has(f, s -> s.is(Items.ARROW)) >= 16 && has(f, s -> s.is(Items.SHIELD)) == 1,
                "a bow, arrows and a shield");
            helper.assertTrue(has(f, NetherPlanAccess::fireResistance) >= 1, "fire resistance");
            helper.assertTrue(has(f, s -> s.getItem() instanceof RunnersSatchelItem) == 1, "its satchel");
        }
        helper.assertTrue(has(a, s -> s.is(Items.FLINT_AND_STEEL)) == 1, "the leader's flint and steel");
        helper.assertTrue(stock(level, id, Items.IRON_HELMET) == 2, "the helmets back in the stores for the charm");
        // The piglins: one by the runner in gold, one by a farmer with none.
        // Out past the town's folk, out of each other's hearing (a piglin tells the others within sixteen blocks whom it is
        // angry at), and out of earshot of each other's folk (a folk set on calls the others within thirty-two to help, and
        // the runner would come to the farmer's and strike its piglin).
        BlockPos p1 = t.heart().east(30).north(18), p2 = t.heart().east(30).south(18);
        a.moveTo(p1.getX() + 3.5, p1.getY(), p1.getZ() + 0.5, 90.0F, 0.0F);
        farmer.moveTo(p2.getX() + 3.5, p2.getY(), p2.getZ() + 0.5, 90.0F, 0.0F);
        // Struck, not killed: the piglin's anger is what is looked at. Not made invulnerable: a piglin gives up its anger at
        // what it cannot hurt (the game's own "no valid target"); Resistance V takes every blow instead.
        farmer.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 600, 4));
        Piglin near = piglin(level, p1), other = piglin(level, p2);
        final int[] phase = { 0 };
        final long[] at = { helper.getTick() };
        // Watched every tick for the two seconds after: the farmer, once struck, runs, and a piglin lets go of its anger
        // at what has run out of its reach, so what it did is looked at as it did it.
        final boolean[] turned = { false, false };
        helper.onEachTick(() -> {
            if (phase[0] == 0 && helper.getTick() - at[0] >= 10) {
                Kit.log("nr02 the piglins: by the runner at " + near.blockPosition().toShortString() + " (sees it " + near.hasLineOfSight(a) + ", the runner at "
                    + a.blockPosition().toShortString() + "), by the farmer at " + other.blockPosition().toShortString() + " (sees it " + other.hasLineOfSight(farmer)
                    + ", the farmer at " + farmer.blockPosition().toShortString() + ", in gold " + NetherRunners.wearsGold(farmer) + ")");
                NetherWork.mannersForTests(level, a);
                NetherWork.mannersForTests(level, farmer);
                phase[0] = 1;
                at[0] = helper.getTick();
                // The satchel in lava.
                BlockPos pool = t.heart().west(4).north(10);
                level.setBlock(pool.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(pool, Blocks.LAVA.defaultBlockState(), 3);
                ItemStack satchel = new ItemStack(NetherItems.RUNNERS_SATCHEL.get());
                RunnersSatchelItem.pack(satchel, new ItemStack(Items.QUARTZ, 20));
                ItemEntity drop = new ItemEntity(level, pool.getX() + 0.5, pool.getY() + 0.3, pool.getZ() + 0.5, satchel);
                drop.addTag("nr02_satchel");
                level.addFreshEntity(drop);
                ItemEntity plain = new ItemEntity(level, pool.getX() + 0.5, pool.getY() + 0.3, pool.getZ() + 0.5, new ItemStack(Items.LEATHER));
                plain.addTag("nr02_leather");
                level.addFreshEntity(plain);
                return;
            }
            if (phase[0] == 1) {
                if (near.getBrain().getMemory(MemoryModuleType.ANGRY_AT).filter(a.getUUID()::equals).isPresent() || near.getTarget() == a) turned[0] = true;
                if (other.getBrain().getMemory(MemoryModuleType.ANGRY_AT).filter(farmer.getUUID()::equals).isPresent()) turned[1] = true;
            }
            if (phase[0] != 1 || helper.getTick() - at[0] < 40) return;
            phase[0] = 2;
            var angryNear = near.getBrain().getMemory(MemoryModuleType.ANGRY_AT);
            var angryOther = other.getBrain().getMemory(MemoryModuleType.ANGRY_AT);
            boolean satchelLives = !level.getEntitiesOfClass(ItemEntity.class, new AABB(t.heart()).inflate(40), e -> e.getTags().contains("nr02_satchel")).isEmpty();
            boolean leatherLives = !level.getEntitiesOfClass(ItemEntity.class, new AABB(t.heart()).inflate(40), e -> e.getTags().contains("nr02_leather")).isEmpty();
            ItemStack test = new ItemStack(NetherItems.RUNNERS_SATCHEL.get());
            ItemStack left = RunnersSatchelItem.pack(test, new ItemStack(Items.QUARTZ, 64));
            RunnersSatchelItem.pack(test, new ItemStack(Items.GLOWSTONE_DUST, 30));
            int in = RunnersSatchelItem.count(test);
            List<ItemStack> out = RunnersSatchelItem.unpack(test);
            Kit.log("nr02 the piglin by the runner in gold: turned on it " + turned[0] + ", angry at " + angryNear + ", target " + near.getTarget() + "; by the farmer: turned on it " + turned[1] + ", angry at " + angryOther
                + ", target " + (other.getTarget() == null ? "none" : other.getTarget().getName().getString()) + "; satchel in the lava " + (satchelLives ? "whole" : "burnt")
                + ", the leather " + (leatherLives ? "whole" : "burnt") + "; satchel took " + in + ", gave back " + out);
            near.discard();
            other.discard();
            helper.assertTrue(!turned[0] && near.getTarget() != a, "a piglin leaves the runner in gold alone");
            helper.assertTrue(turned[1], "and turns on a folk with no gold on");
            helper.assertTrue(left.isEmpty() && in == 94 && out.size() == 2 && RunnersSatchelItem.count(test) == 0, "the satchel takes the haul and gives it back");
            helper.assertTrue(satchelLives && !leatherLives, "a satchel in the lava floats there whole, where leather burns");
            helper.succeed();
        });
    }

    /** A piglin set down here that does not turn into a zombie in this world. */
    static Piglin piglin(ServerLevel level, BlockPos at) {
        Piglin p = EntityType.PIGLIN.create(level);
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, -90.0F, 0.0F);
        p.setImmuneToZombification(true);
        p.setPersistenceRequired();
        p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD));
        level.addFreshEntity(p);
        return p;
    }

    /** The fire resistance test, for a predicate outside the entity package. */
    static final class NetherPlanAccess {
        static boolean fireResistance(ItemStack s) {
            return s.is(Items.POTION) && s.getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.FIRE_RESISTANCE);
        }
    }

    // ============================================================ nr03: through the gateway, and back

    /**
     * Two runners, the outpost already walled in on the far side: they walk to the gateway, the leader first, and go
     * through into the server's own Nether (the same two folk on the far side), their ground there kept awake while they
     * are there and the town counting them away; then home through the portal again, their ground let go, the run told.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "nr03_through")
    public static void nr03_through(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1324000, true, StationTask.NETHER, StationTask.NETHER, StationTask.FARM);
        UUID id = t.village();
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
        runners(a, b);
        fill(t, kit(2, true));
        Pocket pk = pocket(helper, t, true);
        NetherPlan.daysForTests(0.5);
        UUID ua = a.getUUID(), ub = b.getUUID();
        String nameA = a.displayNameCap();
        NetherRuns.Run r = NetherRuns.sendForTests(level, t.v());
        helper.assertTrue(r != null && r.members().size() == 2 && r.leader().equals(ua), "the two set out, the more experienced leading");
        final int[] phase = { 0 };
        final boolean[] seen = { false, false, false };
        helper.onEachTick(() -> {
            if (helper.getTick() % 100 == 0) log("nr03", level, r, ua, ub);
            if (phase[0] == 0) {
                if (!through(level, ua) || !through(level, ub)) return;
                VillageFolkEntity na = find(level, ua);
                seen[0] = na != null && na.displayNameCap().equals(nameA) && na != a;
                seen[1] = NetherRuns.windowForTests(ua) != null || NetherRuns.windowForTests(ub) != null;
                seen[2] = Nether.away(na) && Villages.folkOf(id).contains(na);
                if (!seen[1] && helper.getTick() % 20 != 0) return;
                phase[0] = 1;
                Kit.log("nr03 both through: the same folk " + seen[0] + "; ground awake " + seen[1] + "; the town counts it away " + seen[2]);
                NetherRuns.homeForTests(pk.nether(), r, "the test's run was done");
                return;
            }
            if (!r.reported()) return;
            boolean home = homeAgain(level, ua) && homeAgain(level, ub);
            boolean let = NetherRuns.windowForTests(ua) == null && NetherRuns.windowForTests(ub) == null;
            String hauls = String.join(" / ", NetherRuns.hauls(id));
            VillageFolkEntity ha = find(level, ua);
            Kit.log("nr03 home: " + home + "; ground let go " + let + "; on a run " + (ha != null && NetherRuns.on(ha)) + "; hauls " + hauls + "; why " + r.why());
            NetherPlan.daysForTests(null);
            release(pk);
            helper.assertTrue(seen[0], "the same folk on the far side of the portal");
            helper.assertTrue(seen[1], "its ground in the Nether kept awake while it is there");
            helper.assertTrue(seen[2], "the town knows where it is: away through the gateway, still one of its own");
            helper.assertTrue(home, "both home again through the portal");
            helper.assertTrue(let, "their ground in the Nether let go");
            helper.assertTrue(ha != null && !NetherRuns.on(ha) && !Nether.away(ha), "off the run, and home");
            helper.assertTrue(!hauls.isEmpty(), "the run told in the town's books");
            helper.succeed();
        });
    }

    // ============================================================ nr04: a day's run, and the haul

    /**
     * A day's run with the outpost standing: six quartz in the floor outside it, glowstone on a pillar, a fortress's
     * corner of nether brick with four ripe wart beds and spare soul sand, and three blazes. The runners dig, pick and
     * shoot (fire resistance drunk first); one of them carries a nearly full pack, so the haul goes into its satchel. Home,
     * everything into the storehouse, counted: the quartz, the glowstone, the wart, what rods the blazes gave.
     */
    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "nr04_haul")
    public static void nr04_haul(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1326000, true, StationTask.NETHER, StationTask.NETHER, StationTask.FARM);
        UUID id = t.village();
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
        runners(a, b);
        fill(t, kit(2, true));
        Pocket pk = pocket(helper, t, true);
        ServerLevel nether = pk.nether();
        BlockPos o = pk.portal();
        List<BlockPos> quartz = new ArrayList<>(), glow = new ArrayList<>(), wart = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            BlockPos q = o.offset(8 + (i % 3), -1, 6 + i / 3);
            nether.setBlock(q, Blocks.NETHER_QUARTZ_ORE.defaultBlockState(), 3);
            quartz.add(q);
        }
        for (int i = 0; i < 3; i++) nether.setBlock(o.offset(-9, i, 7), Blocks.NETHERRACK.defaultBlockState(), 3);
        for (int i = 0; i < 2; i++) {
            BlockPos g = o.offset(-9, i, 8);
            nether.setBlock(g, Blocks.GLOWSTONE.defaultBlockState(), 3);
            glow.add(g);
        }
        // The fortress's corner: nether brick, wart beds, soul sand.
        BlockPos fort = o.offset(-6, 0, -12);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -2; dz <= 2; dz++) nether.setBlock(fort.offset(dx, -1, dz), Blocks.NETHER_BRICKS.defaultBlockState(), 3);
        for (int dx = -3; dx <= 3; dx++) nether.setBlock(fort.offset(dx, 0, -3), Blocks.NETHER_BRICKS.defaultBlockState(), 3);
        for (int i = 0; i < 4; i++) {
            BlockPos s = fort.offset(-1 + i, -1, 0);
            nether.setBlock(s, Blocks.SOUL_SAND.defaultBlockState(), 3);
            nether.setBlock(s.above(), Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3), 3);
            wart.add(s.above());
        }
        nether.setBlock(fort.offset(-2, -1, 2), Blocks.SOUL_SAND.defaultBlockState(), 3);
        nether.setBlock(fort.offset(2, -1, 2), Blocks.SOUL_SAND.defaultBlockState(), 3);
        NetherPlan.daysForTests(1.0);
        NetherWork.quickForTests(true);
        UUID ua = a.getUUID(), ub = b.getUUID();
        int quartz0 = stock(level, id, Items.QUARTZ), dust0 = stock(level, id, Items.GLOWSTONE_DUST), wart0 = stock(level, id, Items.NETHER_WART);
        int rods0 = stock(level, id, Items.BLAZE_ROD);
        NetherRuns.Run r = NetherRuns.sendForTests(level, t.v());
        helper.assertTrue(r != null && r.members().size() == 2, "the two set out");
        Kit.log("nr04 plan: " + r.planWords());
        final int[] phase = { 0 };
        final int[] satchelMost = { 0 };
        final long[] since = { -1 };
        final List<Blaze> blazes = new ArrayList<>();
        helper.onEachTick(() -> {
            if (helper.getTick() % 200 == 0) log("nr04", level, r, ua, ub);
            for (UUID u : new UUID[]{ ua, ub }) {
                VillageFolkEntity fb = find(level, u);
                if (fb != null) for (ItemStack s : fb.getInventoryItems()) if (s.getItem() instanceof RunnersSatchelItem) satchelMost[0] = Math.max(satchelMost[0], RunnersSatchelItem.count(s));
            }
            if (phase[0] == 0) {
                if (r.phase() != NetherRuns.Run.Phase.WORK) return;
                // Both packs full now (stone they were carrying home anyway: every empty place in them): what either digs
                // goes into its satchel.
                for (UUID u : new UUID[]{ ua, ub }) {
                    VillageFolkEntity full = find(level, u);
                    if (full == null) continue;
                    for (int i = 0; i < full.getInventoryItems().size(); i++) {
                        if (full.getInventoryItems().get(i).isEmpty()) full.getInventoryItems().set(i, new ItemStack(Items.STONE, 64));
                    }
                }
                // At work on the far side: the blazes come, by the fortress.
                for (int i = 0; i < 3; i++) {
                    Blaze bl = EntityType.BLAZE.create(nether);
                    bl.moveTo(fort.getX() + 0.5 + 2 * i - 2, NY + 3, fort.getZ() + 0.5, 0.0F, 0.0F);
                    bl.setPersistenceRequired();
                    nether.addFreshEntity(bl);
                    blazes.add(bl);
                }
                phase[0] = 1;
                since[0] = helper.getTick();
                return;
            }
            if (phase[0] == 1) {
                boolean dug = true;
                for (BlockPos q : quartz) if (nether.getBlockState(q).is(Blocks.NETHER_QUARTZ_ORE)) dug = false;
                for (BlockPos g : glow) if (nether.getBlockState(g).is(Blocks.GLOWSTONE)) dug = false;
                for (BlockPos w : wart) if (nether.getBlockState(w).is(Blocks.NETHER_WART) && nether.getBlockState(w).getValue(NetherWartBlock.AGE) >= 3) dug = false;
                boolean shot = true;
                for (Blaze bl : blazes) if (bl.isAlive()) shot = false;
                if (!(dug && shot) && helper.getTick() - since[0] < 5000) return;
                Kit.log("nr04 the work done: dug " + dug + ", blazes down " + shot + " after " + (helper.getTick() - since[0]) + " ticks; satchel held " + satchelMost[0]);
                for (Blaze bl : blazes) if (bl.isAlive()) bl.discard();
                phase[0] = 2;
                NetherRuns.homeForTests(nether, r, "the test's work was done");
                return;
            }
            if (!r.reported()) return;
            int quartz1 = stock(level, id, Items.QUARTZ) - quartz0, dust1 = stock(level, id, Items.GLOWSTONE_DUST) - dust0;
            int wart1 = stock(level, id, Items.NETHER_WART) - wart0, rods1 = stock(level, id, Items.BLAZE_ROD) - rods0;
            Map<Item, Integer> stored = r.storedItems();
            Kit.log("nr04 home (" + r.why() + "): stored " + stored + "; storehouse +" + quartz1 + " quartz, +" + dust1 + " glowstone dust, +" + wart1 + " wart, +"
                + rods1 + " blaze rods; blazes " + r.blazes() + ", mined " + r.mined() + ", potions " + r.potions() + "; events " + r.events());
            for (String line : NetherRuns.hauls(id)) Kit.log("nr04 haul: " + line);
            NetherPlan.daysForTests(null);
            release(pk);
            helper.assertTrue(quartz1 >= 6, "the six quartz in the storehouse: +" + quartz1);
            helper.assertTrue(dust1 >= 4, "the glowstone's dust: +" + dust1);
            helper.assertTrue(wart1 >= 4, "the wart picked (one put back in each bed): +" + wart1);
            helper.assertTrue(r.blazes() >= 2, "blazes shot down: " + r.blazes());
            helper.assertTrue(rods1 == stored.getOrDefault(Items.BLAZE_ROD, 0), "every rod the blazes gave, home: " + rods1);
            helper.assertTrue(r.potions() >= 1, "fire resistance drunk before the blazes");
            helper.assertTrue(satchelMost[0] > 0, "the haul into the satchel when the pack was full: " + satchelMost[0]);
            helper.assertTrue(stored.getOrDefault(Items.QUARTZ, 0) >= 6 && !NetherRuns.hauls(id).isEmpty(), "the haul counted and told");
            helper.succeed();
        });
    }

    // ============================================================ nr05: a barter

    /**
     * A run to barter: no pearls or obsidian in the stores and gold to spare; a piglin in the pocket by the outpost. The
     * leader (a gold charm on) throws it gold, it admires it, and throws back what the game's bartering table gives;
     * the runners pick it up, and home it goes into the storehouse.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "nr05_barter")
    public static void nr05_barter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1328000, true, StationTask.NETHER, StationTask.NETHER, StationTask.FARM);
        UUID id = t.village();
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
        runners(a, b);
        List<ItemStack> goods = new ArrayList<>(kit(2, true));
        goods.add(new ItemStack(Items.GOLD_INGOT, 24));
        fill(t, goods);
        Pocket pk = pocket(helper, t, true);
        NetherPlan.daysForTests(0.5);
        UUID ua = a.getUUID(), ub = b.getUUID();
        int gold0 = stock(level, id, Items.GOLD_INGOT);
        NetherRuns.Run r = NetherRuns.sendForTests(level, t.v());
        helper.assertTrue(r != null && r.planWords().contains("barter"), "the run goes to barter: " + (r == null ? "none" : r.planWords()));
        final int[] phase = { 0 };
        final long[] since = { -1 };
        final Piglin[] pig = { null };
        helper.onEachTick(() -> {
            if (helper.getTick() % 100 == 0) log("nr05", level, r, ua, ub);
            if (phase[0] == 0) {
                if (r.phase() != NetherRuns.Run.Phase.WORK) return;
                Piglin p = EntityType.PIGLIN.create(pk.nether());
                BlockPos at = pk.portal().offset(-8, 0, 8);
                p.moveTo(at.getX() + 0.5, NY, at.getZ() + 0.5, 0.0F, 0.0F);
                p.setPersistenceRequired();
                pk.nether().addFreshEntity(p);
                pig[0] = p;
                phase[0] = 1;
                since[0] = helper.getTick();
                return;
            }
            if (phase[0] == 1) {
                boolean done = r.barters() >= 2 && !r.haul().isEmpty() && pig[0].getOffhandItem().isEmpty();
                if (!done && helper.getTick() - since[0] < 3000) return;
                Kit.log("nr05 bartered " + r.barters() + " after " + (helper.getTick() - since[0]) + " ticks; haul " + r.haul());
                phase[0] = 2;
                NetherRuns.homeForTests(pk.nether(), r, "the test's barter was done");
                return;
            }
            if (!r.reported()) return;
            Map<Item, Integer> stored = r.storedItems();
            int gold1 = stock(level, id, Items.GOLD_INGOT);
            Kit.log("nr05 home: stored " + stored + "; gold " + gold0 + " -> " + gold1 + "; events " + r.events());
            NetherPlan.daysForTests(null);
            if (pig[0] != null) pig[0].discard();
            release(pk);
            helper.assertTrue(r.barters() >= 1, "gold thrown to the piglin and taken");
            helper.assertTrue(!stored.isEmpty(), "what the piglin threw back, picked up and in the storehouse: " + stored);
            helper.assertTrue(gold1 <= gold0 - r.barters() && gold1 >= gold0 - 16, "an ingot a barter, the rest back in the stores: " + gold0 + " -> " + gold1);
            helper.succeed();
        });
    }

    // ============================================================ nr06: the wart farm, and fire resistance

    /**
     * A Nether Age town with a runner, a brewer and a brewery: the brewer lays the runners' soul sand by the brewery, a
     * farm of eight, plants it with the stores' wart; grown, it picks it into the stores and plants again. It grinds the
     * runners' blaze rods to powder for its stand, makes magma cream of a powder and a slime ball, and brews fire
     * resistance (the runners' potion, two a runner kept).
     */
    @GameTest(template = EMPTY, timeoutTicks = 3200, batch = "nr06_wart_farm")
    public static void nr06_wart_farm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1330000, true, StationTask.BREW, StationTask.NETHER, StationTask.FARM);
        UUID id = t.village();
        VillageFolkEntity brewer = t.folk().get(0), runner = t.folk().get(1);
        runners(runner);
        fill(t, List.of(new ItemStack(Items.SOUL_SAND, 8), new ItemStack(Items.NETHER_WART, 12), new ItemStack(Items.GLASS_BOTTLE, 9),
            new ItemStack(Items.BLAZE_ROD, 2), new ItemStack(Items.SLIME_BALL, 2)));
        brewer.insertGiven(new ItemStack(Items.BREWING_STAND));
        BlockPos at = Kit.surface(level, t.heart().getX() + 14, t.heart().getZ());
        BuildGoal.stampOnly(level, "brewery", at, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK), p -> p.part() != BuildGoal.Part.BREWING);
        Ledger.built(id, "brewery", at, Direction.NORTH);
        Villages.builtAtForTests(id, "brewery", at);
        int slime0 = stock(level, id, Items.SLIME_BALL);
        // A town of three wants no runner of its own (one from twenty), so in time it puts this one to other work: the
        // potions kept for the runners are reckoned now, while it is one.
        int kept = NetherHome.fireResistanceKept(id);
        final int[] phase = { 0 };
        final long[] mark = { helper.getTick() };
        final int[] wartAtHarvest = { 0 };
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 != 0) return;
            Crafts.now(brewer, level, t.v());
            List<BlockPos> farm = NetherHome.farmForTests(level, t.v());
            if (phase[0] == 0) {
                if (farm == null || NetherHome.wartPlants(id) < 8) {
                    if (helper.getTick() - mark[0] > 1200) helper.fail("the farm was not laid and planted: " + NetherHome.wartPlants(id) + " plants, farm " + farm);
                    return;
                }
                int laid = 0;
                for (BlockPos g : farm) if (level.getBlockState(g).is(Blocks.SOUL_SAND)) laid++;
                Kit.log("nr06 the farm laid (" + laid + " soul sand) and planted: " + NetherHome.wartPlants(id) + " plants; soul sand wanted now " + NetherHome.farmWants(id));
                helper.assertTrue(laid == 8 && NetherHome.farmWants(id) == 0, "eight soul sand laid, none more wanted");
                // The wart grows (the game's own growing, hurried).
                for (BlockPos g : farm) {
                    for (int k = 0; k < 400 && level.getBlockState(g.above()).is(Blocks.NETHER_WART)
                            && level.getBlockState(g.above()).getValue(NetherWartBlock.AGE) < 3; k++) {
                        level.getBlockState(g.above()).randomTick(level, g.above(), level.getRandom());
                    }
                }
                wartAtHarvest[0] = stock(level, id, Items.NETHER_WART);
                phase[0] = 1;
                mark[0] = helper.getTick();
                return;
            }
            if (phase[0] == 1) {
                int ripe = 0;
                for (BlockPos g : farm) if (level.getBlockState(g.above()).is(Blocks.NETHER_WART) && level.getBlockState(g.above()).getValue(NetherWartBlock.AGE) >= 3) ripe++;
                if (ripe > 0 && helper.getTick() - mark[0] < 600) return;
                int got = stock(level, id, Items.NETHER_WART) - wartAtHarvest[0];
                Kit.log("nr06 picked: +" + got + " wart into the stores; plants " + NetherHome.wartPlants(id));
                helper.assertTrue(ripe == 0 && got >= 8 && NetherHome.wartPlants(id) == 8, "every ripe plant picked into the stores, and planted again: +" + got);
                phase[0] = 2;
                mark[0] = helper.getTick();
                return;
            }
            int fire = Market.stock(level, id, NetherPlanAccess::fireResistance);
            if (fire < 3 && helper.getTick() - mark[0] < 2400) return;
            Kit.log("nr06 the runner: " + runner.stationTask() + ", alive " + runner.isAlive() + ", runners " + NetherRunners.runners(id).size()
                + ", in " + runner.level().dimension().location().getPath());
            Kit.log("nr06 fire resistance in the stores: " + fire + "; slime balls " + slime0 + " -> " + stock(level, id, Items.SLIME_BALL) + "; blaze rods "
                + stock(level, id, Items.BLAZE_ROD) + ", powder " + stock(level, id, Items.BLAZE_POWDER) + "; kept " + NetherHome.fireResistanceKept(id));
            helper.assertTrue(fire >= 3, "three potions of fire resistance brewed for the runners");
            helper.assertTrue(stock(level, id, Items.SLIME_BALL) == slime0 - 1, "a magma cream made of a slime ball and the runners' blaze powder");
            helper.assertTrue(kept == 4, "two a runner kept, and a rescue's: " + kept);
            helper.succeed();
        });
    }

    // ============================================================ nr07: the outpost

    /**
     * A first run: nothing on the far side but the portal. The runners wall it in before anything else: cobblestone walls
     * round it from the floor to over the portal's top, a roof, the netherrack inside cut away, a wooden door in the front
     * wall, soul lanterns; kept with the town as the outpost, and told.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "nr07_outpost")
    public static void nr07_outpost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1332000, true, StationTask.NETHER, StationTask.NETHER, StationTask.FARM);
        UUID id = t.village();
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
        runners(a, b);
        fill(t, kit(2, true));
        Pocket pk = pocket(helper, t, false);
        NetherPlan.daysForTests(0.5);
        NetherOutpost.quickForTests(true);
        NetherWork.quickForTests(true);
        UUID ua = a.getUUID(), ub = b.getUUID();
        NetherRuns.Run r = NetherRuns.sendForTests(level, t.v());
        helper.assertTrue(r != null && r.planWords().contains("outpost"), "a first run plans the outpost first: " + (r == null ? "none" : r.planWords()));
        final int[] phase = { 0 };
        helper.onEachTick(() -> {
            if (helper.getTick() % 100 == 0) log("nr07", level, r, ua, ub);
            if (phase[0] == 0) {
                if (!NetherOutpost.built(id)) return;
                NetherOutpost.Room room = NetherOutpost.room(id);
                int cobble = 0, door = 0, lights = 0;
                for (BlockPos p : BlockPos.betweenClosed(pk.portal().offset(-8, -2, -8), pk.portal().offset(8, 8, 8))) {
                    var st = pk.nether().getBlockState(p);
                    if (st.is(Blocks.COBBLESTONE)) cobble++;
                    if (st.is(net.minecraft.tags.BlockTags.WOODEN_DOORS)) door++;
                    if (st.is(Blocks.SOUL_LANTERN) || st.is(Blocks.LANTERN) || st.is(Blocks.TORCH)) lights++;
                }
                int left = NetherOutpost.leftForTests(pk.nether(), id);
                Kit.log("nr07 the outpost walled in: " + cobble + " cobblestone, " + door + " door blocks, " + lights + " lights; " + left + " of the work left; placed "
                    + r.placed() + "; events " + r.events());
                helper.assertTrue(cobble >= 120 && left == 0, "walls and roof of cobblestone round the portal: " + cobble);
                helper.assertTrue(door == 2, "a door in the front wall");
                helper.assertTrue(lights >= 1, "a light in it");
                helper.assertTrue(r.outpostBuilt(), "the run knows it built it");
                phase[0] = 1;
                NetherRuns.homeForTests(pk.nether(), r, "the outpost was built");
                return;
            }
            if (!r.reported()) return;
            boolean told = false;
            for (NetherRuns.Find f : NetherRuns.report(id)) if (f.kind() == NetherRuns.Kind.OUTPOST) told = true;
            Kit.log("nr07 home: the outpost in the report " + told + "; the chart: " + NetherGuests.chartBookForTests(id));
            NetherPlan.daysForTests(null);
            release(pk);
            helper.assertTrue(told, "the outpost in the town's report");
            helper.succeed();
        });
    }

    // ============================================================ nr08: fire, and falling back

    /**
     * Two runners at work by the outpost. One catches fire: it drinks its fire resistance. The other, badly hurt and on
     * fire with no potion left, falls back to the outpost, eats, and is better.
     */
    @GameTest(template = EMPTY, timeoutTicks = 5000, batch = "nr08_retreat")
    public static void nr08_retreat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1334000, true, StationTask.NETHER, StationTask.NETHER, StationTask.FARM);
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
        runners(a, b);
        fill(t, kit(2, true));
        Pocket pk = pocket(helper, t, true);
        NetherPlan.daysForTests(0.5);
        UUID ua = a.getUUID(), ub = b.getUUID();
        NetherRuns.Run r = NetherRuns.sendForTests(level, t.v());
        helper.assertTrue(r != null, "the two set out");
        final int[] phase = { 0 };
        final long[] since = { -1 };
        final double[] far = { 0 };
        final boolean[] seen = { false, false };
        helper.onEachTick(() -> {
            if (helper.getTick() % 50 == 0) log("nr08", level, r, ua, ub);
            VillageFolkEntity fa = find(level, ua), fb = find(level, ub);
            if (phase[0] == 0) {
                if (r.phase() != NetherRuns.Run.Phase.WORK || fa == null || fb == null || !through(level, ua) || !through(level, ub)) return;
                // The first on fire, its potions in its pack; the second badly hurt and burning, its potions gone, twelve blocks out.
                fa.setRemainingFireTicks(120);
                fb.removeMatching(NetherPlanAccess::fireResistance, 64);
                BlockPos out = pk.portal().offset(12, 0, 12);
                fb.moveTo(out.getX() + 0.5, NY, out.getZ() + 0.5, 0.0F, 0.0F);
                fb.setHealth(Math.max(2.0F, fb.getMaxHealth() * 0.2F));
                fb.setRemainingFireTicks(40);
                far[0] = Math.sqrt(fb.blockPosition().distSqr(pk.room().inside()));
                phase[0] = 1;
                since[0] = helper.getTick();
                return;
            }
            if (phase[0] == 1) {
                if (fa != null && fa.hasEffect(MobEffects.FIRE_RESISTANCE)) seen[0] = true;
                if (fb != null && NetherRuns.retreatingForTests(r, ub)) seen[1] = true;
                double d = fb == null ? 99 : Math.sqrt(fb.blockPosition().distSqr(pk.room().inside()));
                boolean back = fb != null && d <= 3.0;
                if (!(seen[0] && seen[1] && back) && helper.getTick() - since[0] < 1200) return;
                Kit.log("nr08 " + (fa == null ? "?" : fa.displayNameCap()) + " drank: " + seen[0] + " (potions " + r.potions() + "); "
                    + (fb == null ? "?" : fb.displayNameCap()) + " fell back: " + seen[1] + ", " + String.format(java.util.Locale.ROOT, "%.1f", far[0]) + " -> "
                    + String.format(java.util.Locale.ROOT, "%.1f", d) + " blocks from the outpost's inside; retreats " + r.retreats() + "; events " + r.events());
                helper.assertTrue(seen[0] && r.potions() >= 1, "on fire, it drinks its fire resistance");
                helper.assertTrue(seen[1] && r.retreats() >= 1, "badly hurt, it falls back");
                helper.assertTrue(back, "back inside the outpost");
                helper.assertTrue(fb != null && fb.isAlive(), "and lives");
                phase[0] = 2;
                NetherRuns.homeForTests(pk.nether(), r, "the test was done");
                return;
            }
            if (!r.reported()) return;
            NetherPlan.daysForTests(null);
            release(pk);
            helper.succeed();
        });
    }

    // ============================================================ nr09: missed, searched for, lost, rescued

    /**
     * Two runners at work. The second goes out of sight of the leader behind a wall a minute: the leader misses it, goes
     * back for it calling, and finds it. Then it is lost (the search given up): the leader goes home without it, the town
     * is told; the next run is a rescue, two of the watch with the runners, that goes through, finds it, and brings it home.
     */
    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "nr09_lost")
    public static void nr09_lost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1336000, true, StationTask.NETHER, StationTask.NETHER, StationTask.GUARD, StationTask.GUARD, StationTask.FARM);
        UUID id = t.village();
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
        runners(a, b);
        for (int i = 2; i <= 3; i++) t.folk().get(i).tradeXpForTests(StationTask.GUARD, AssistantEntity.xpForLevel(6));
        List<ItemStack> goods = new ArrayList<>(kit(4, true));
        fill(t, goods);
        Pocket pk = pocket(helper, t, true);
        // A wall across the pocket's east end, to be out of sight behind.
        for (int z = pk.nz() - 19; z <= pk.nz() + 19; z++) for (int y = NY; y < NY + 9; y++) {
            if (z == pk.nz() + 17) continue;                                      // a way round it, at the end
            pk.nether().setBlock(new BlockPos(pk.nx() + 9, y, z), Blocks.NETHERRACK.defaultBlockState(), 3);
        }
        NetherPlan.daysForTests(1.0);
        UUID ua = a.getUUID(), ub = b.getUUID();
        NetherRuns.Run r = NetherRuns.sendForTests(level, t.v());
        helper.assertTrue(r != null, "the two set out");
        final int[] phase = { 0 };
        final long[] since = { -1 };
        final boolean[] missed = { false };
        final NetherRuns.Run[] rescue = { null };
        helper.onEachTick(() -> {
            if (helper.getTick() % 100 == 0) log("nr09", level, rescue[0] != null ? rescue[0] : r, ua, ub);
            VillageFolkEntity fb = find(level, ub);
            switch (phase[0]) {
                case 0 -> {
                    if (r.phase() != NetherRuns.Run.Phase.WORK || !through(level, ua) || !through(level, ub)) return;
                    BlockPos behind = new BlockPos(pk.nx() + 15, NY, pk.nz() - 10);
                    fb.moveTo(behind.getX() + 0.5, NY, behind.getZ() + 0.5, 0.0F, 0.0F);
                    NetherRuns.outOfSightForTests(fb, 1300);
                    phase[0] = 1;
                    since[0] = helper.getTick();
                }
                case 1 -> {
                    if (r.missing() != null) missed[0] = true;
                    boolean found = missed[0] && r.missing() == null;
                    if (!found && helper.getTick() - since[0] < 3000) return;
                    Kit.log("nr09 missed " + missed[0] + ", found " + found + "; events " + r.events());
                    helper.assertTrue(missed[0], "out of sight a minute, it is missed");
                    helper.assertTrue(found && String.join(" ", r.events()).contains("found"), "searched for, and found");
                    // Lost now: the search given up.
                    NetherRuns.loseForTests(pk.nether(), find(level, ub), r);
                    NetherRuns.homeForTests(pk.nether(), r, "the test's search was done");
                    phase[0] = 2;
                }
                case 2 -> {
                    if (!r.reported()) return;
                    VillageFolkEntity lostOne = find(level, ub);
                    Kit.log("nr09 home without it: lost " + NetherRuns.lost(ub) + " (" + (lostOne == null ? "gone" : lostOne.hobbyNow()) + "); story " + r.lost());
                    helper.assertTrue(NetherRuns.lost(ub) && lostOne != null && through(level, ub) && Nether.away(lostOne), "lost in the Nether, waiting, and counted away");
                    helper.assertTrue(homeAgain(level, ua), "the leader home");
                    // The rescue, the next morning: the runner home and two of the watch.
                    NetherRuns.forgetWentForTests();
                    // A town of five has more watch than it needs and in time puts a guard to the mine: the two are
                    // the watch again tonight, as a town big enough for runners keeps them.
                    for (int i = 2; i <= 3; i++) if (t.folk().get(i).stationTask() != StationTask.GUARD) t.folk().get(i).setJob(StationTask.GUARD);
                    for (int i = 2; i <= 3; i++) {
                        VillageFolkEntity g = t.folk().get(i);
                        Kit.log("nr09 the watch: " + g.displayNameCap() + " " + g.stationTask() + ", " + g.getHealth() + "/" + g.getMaxHealth() + " hp, on watch "
                            + g.onWatch() + ", sleeping " + g.isSleeping());
                    }
                    rescue[0] = NetherRuns.sendForTests(level, t.v());
                    helper.assertTrue(rescue[0] != null && rescue[0].rescue() && rescue[0].members().size() >= 3,
                        "a rescue party through the gateway, the watch with it: " + (rescue[0] == null ? "none" : rescue[0].members().size()));
                    phase[0] = 3;
                    since[0] = helper.getTick();
                }
                default -> {
                    NetherRuns.Run rr = rescue[0];
                    if (!rr.reported()) {
                        if (helper.getTick() - since[0] > 6000) helper.fail("the rescue never came home: " + rr.phaseWords() + ", events " + rr.events());
                        return;
                    }
                    Kit.log("nr09 the rescue home (" + rr.why() + "): events " + rr.events() + "; lost " + NetherRuns.lost(ub));
                    NetherPlan.daysForTests(null);
                    release(pk);
                    helper.assertTrue(!NetherRuns.lost(ub) && homeAgain(level, ub), "found, and brought home");
                    helper.succeed();
                }
            }
        });
    }

    // ============================================================ nr10: a player along

    /**
     * A player asks to come through for a share. The team waits for it at the gateway; sets out with it; on the far side
     * the leader waits for it to come through. Home with the quartz, a share is kept for it and handed over when it asks.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "nr10_along")
    public static void nr10_along(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1338000, true, StationTask.NETHER, StationTask.NETHER, StationTask.FARM);
        UUID id = t.village();
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1);
        runners(a, b);
        fill(t, kit(2, true));
        Pocket pk = pocket(helper, t, true);
        List<BlockPos> quartz = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            BlockPos q = pk.portal().offset(-7 + i % 3, -1, 7 + i / 3);
            pk.nether().setBlock(q, Blocks.NETHER_QUARTZ_ORE.defaultBlockState(), 3);
            quartz.add(q);
        }
        NetherPlan.daysForTests(0.5);
        NetherWork.quickForTests(true);
        long day = level.getDayTime() / 24000L;
        level.setDayTime(day * 24000L + 800);
        level.updateSkyBrightness();
        ServerPlayer you = helper.makeMockServerPlayerInLevel();
        you.teleportTo(a.getX() + 1.5, a.getY(), a.getZ() + 0.5);
        Player talker = helper.makeMockPlayer(GameType.SURVIVAL);
        talker.setUUID(you.getUUID());
        talker.setPos(you.getX(), you.getY(), you.getZ());
        a.ensurePersona();
        a.persona().feelFor(you.getUUID(), you.getName().getString(), 12);
        String said = FolkTalk.answer(a, talker, TalkTopic.SAY, "Can I come through with you, for a share?");
        NetherGuests.Guest g = NetherGuests.guest(id);
        level.setDayTime(day * 24000L + 2000);
        level.updateSkyBrightness();
        Kit.log("nr10 booked: " + said + " | " + g);
        helper.assertTrue(g != null && g.share() && g.player().equals(you.getUUID()), "booked to go through, for a share");
        UUID ua = a.getUUID(), ub = b.getUUID();
        NetherRuns.Run r = NetherRuns.sendForTests(level, t.v());
        helper.assertTrue(r != null && you.getName().getString().equals(r.guestName()), "it goes with the runners");
        final int[] phase = { 0 };
        final long[] since = { helper.getTick() };
        final boolean[] waited = { false };
        helper.onEachTick(() -> {
            if (helper.getTick() % 100 == 0) log("nr10", level, r, ua, ub);
            VillageFolkEntity lead = find(level, ua);
            if (phase[0] == 0) {
                if (lead != null && through(level, ua) && lead.hobbyNow() != null && lead.hobbyNow().contains("to come through")) waited[0] = true;
                if (!waited[0] && helper.getTick() - since[0] < 2000) return;
                Kit.log("nr10 the leader on the far side: " + (lead == null ? "?" : lead.hobbyNow()));
                helper.assertTrue(waited[0], "on the far side, the leader waits for the player to come through");
                Kit.noLeftoverPlayers(level);                                  // the player gone again, before anything can fail
                phase[0] = 1;
                since[0] = helper.getTick();
                return;
            }
            if (phase[0] == 1) {
                boolean dug = true;
                for (BlockPos q : quartz) if (pk.nether().getBlockState(q).is(Blocks.NETHER_QUARTZ_ORE)) dug = false;
                if (!dug && helper.getTick() - since[0] < 3000) return;
                NetherGuests.alongForTests(r, talker, true);
                NetherRuns.homeForTests(pk.nether(), r, "the quartz was dug");
                phase[0] = 2;
                return;
            }
            if (!r.reported()) return;
            int before = talker.getInventory().countItem(Items.QUARTZ);
            VillageFolkEntity other = find(level, ub);
            String handed = FolkTalk.answer(other, talker, TalkTopic.SAY, "Is my share of the haul ready?");
            int after = talker.getInventory().countItem(Items.QUARTZ);
            Kit.log("nr10 home: stored " + r.storedItems() + "; handed: " + handed + "; quartz " + before + " -> " + after);
            NetherPlan.daysForTests(null);
            release(pk);
            helper.assertTrue(after - before >= 1 && handed.contains("share"), "a share of the haul kept for it, and handed over: " + handed);
            helper.succeed();
        });
    }
}
