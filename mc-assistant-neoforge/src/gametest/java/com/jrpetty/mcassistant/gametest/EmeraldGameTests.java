package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.VillagerTakeover;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Budget;
import com.jrpetty.mcassistant.entity.EmeraldStage;
import com.jrpetty.mcassistant.entity.EmeraldTrader;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Scouts;
import com.jrpetty.mcassistant.entity.TwoPeoples;
import com.jrpetty.mcassistant.entity.VanillaVillages;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * [emerald] Two peoples (TwoPeoples, VanillaVillages) and the emerald trader (EmeraldTrader). Each test on its own
 * ground (x 1420000 to 1439999, z 66000), in a batch of its own. The game's villagers here are real villagers with their
 * own brains and their own offers; the little villages of villagers are a bell on a post with a workstation or two round
 * it, as one a player builds is, and are known the way the mod knows one in a world without structures: by the bell the
 * villagers have taken for their meeting place.
 *
 * <ul>
 * <li>ep01: a villager free to wander beside a town for three minutes stays a villager: it tries for the town's
 *     bed-less composter and its bell, and every claim is undone within a second; its own bed outside the town is left
 *     its own; it takes up no trade at the town's composter, joins nobody, and nobody harms it.</li>
 * <li>ep02: claims on the town's bed, composter and bell undone at once (tickets handed back, memories let go, even a
 *     job site whose ticket the game's own release would not hand back); a villager asleep in the town's bed woken;
 *     a villager of a village outside the town keeps all its claims, and its bell makes its village known.</li>
 * <li>ep03: no town is founded on a village of villagers' doorstep; one far enough off is not stood in the way of; a
 *     town standing near one puts no lot within its margin and no wall whose ring would come within it.</li>
 * <li>ep04: the old takeover is a switch, off unless it is turned on: off, nobody is turned; on, the villager is turned
 *     into folk and its village becomes a town, and the two peoples are not kept apart.</li>
 * <li>ep05: the trade opens from the Stone Age in a town of twelve or more that knows of a village of villagers within
 *     reach and has goods to spare (not before, not with the takeover on); the right folk takes it; the Trading Post is
 *     wanted, goes up on a lot of the plan, and is the trader's post; the Trading Post page.</li>
 * <li>ep06: the trader explores like a scout, finds the village of villagers from the road, writes it into the atlas
 *     and its book, walks to it and records its villagers, their trades and levels, and every offer they make.</li>
 * <li>ep07: the trader sells the town's real surplus of wheat and potatoes to a real farmer villager through the
 *     farmer's own offers: the uses go up, the farmer gets its trade XP and goes up a level, and the emeralds come home
 *     to the stores and the account; the Trading Post page lists the offers as they stand.</li>
 * <li>ep08: asked for a Mending book, the trader buys one from a real librarian with the town's emeralds and a book,
 *     brings it home to the stores, and the smith lays it on the town's diamond pickaxe.</li>
 * <li>ep09: the trader never sells what the town needs (a thin larder: no wheat goes); never trades on a used-up offer;
 *     never trades with a villager a player is trading with; never takes anything for nothing; and pays the price as
 *     the game sets it, demand and all.</li>
 * <li>ep10: a village of villagers under a raid is left alone: the trader turns for home without a trade, tells the
 *     town, and does not walk back to it while it is lately raided.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class EmeraldGameTests {

    static final String EMPTY = "empty";
    static final int Z = 66000;
    /** How far east of the heart the tests' villages of villagers stand: well out of the town's ground. */
    static final int OUT = 150;

    private static final List<MemoryModuleType<GlobalPos>> CLAIMS = List.of(MemoryModuleType.HOME, MemoryModuleType.JOB_SITE,
        MemoryModuleType.POTENTIAL_JOB_SITE, MemoryModuleType.MEETING_POINT);

    // ================================================================== the ground, the town, the villagers

    record Town(UUID id, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, List<VillageFolkEntity> folk) {}

    /** A town of so many folk (the first founding it), its stores a storehouse and nothing else, in this age; the first
     *  few given these trades. The morning: a trader sets out in the morning. */
    static Town town(GameTestHelper helper, int x, int n, Villages.Age age, StationTask... trades) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1300);
        Kit.hold(level, x, Z, 80);
        Kit.prepare(level, x, Z, 80);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos at = i == 0 ? heart : Kit.surface(level, x - 2 - (i % 4) * 2, Z + 2 + (i / 4) * 2);
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
            helper.assertTrue(f != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "test setup: folk " + i + " raised in the town");
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        StorehouseBlockEntity store = CaveDwellerGameTests.storehouse(helper, level, heart, -14, -10);
        CaveDwellerGameTests.onlyTheStorehouse(level, id);
        Villages.ageForTests(id, age);
        for (int i = 0; i < trades.length && i < n; i++) if (trades[i] != StationTask.NONE) folk.get(i).setJob(trades[i]);
        return new Town(id, Villages.get(id), heart, store, folk);
    }

    /** The storehouse filled with these, in its first slots, and the town's counts made afresh. */
    static void fill(Town t, ItemStack... goods) {
        for (int i = 0; i < t.store().getContainerSize(); i++) t.store().setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < goods.length; i++) t.store().setItem(i, goods[i]);
        fresh(t);
    }

    static void fresh(Town t) {
        Villages.forgetStock();
        Villages.forgetStores(t.id());
        Budget.forget(t.id());
    }

    /** A full larder and then some: eight stacks of bread, four of wheat, three of potatoes, and these besides. */
    static void surplus(Town t, ItemStack... more) {
        List<ItemStack> goods = new ArrayList<>();
        for (int i = 0; i < 8; i++) goods.add(new ItemStack(Items.BREAD, 64));
        for (int i = 0; i < 4; i++) goods.add(new ItemStack(Items.WHEAT, 64));
        for (int i = 0; i < 3; i++) goods.add(new ItemStack(Items.POTATO, 64));
        goods.addAll(List.of(more));
        fill(t, goods.toArray(new ItemStack[0]));
    }

    static int stock(ServerLevel level, UUID village, Item it) {
        Villages.forgetStock();
        return Market.stock(level, village, s -> s.is(it));
    }

    static int has(VillageFolkEntity f, Item it) {
        return CaveDwellerGameTests.has(f, s -> s.is(it));
    }

    /** A bell on a stone post, its foot at the ground here; the bell. */
    static BlockPos bellOnPost(ServerLevel level, int x, int z) {
        BlockPos g = Kit.surface(level, x, z);
        level.setBlock(g, Blocks.COBBLESTONE_WALL.defaultBlockState(), 3);
        BlockPos b = g.above();
        level.setBlock(b, Blocks.BELL.defaultBlockState().setValue(BellBlock.FACING, Direction.NORTH)
            .setValue(BellBlock.ATTACHMENT, BellAttachType.FLOOR), 3);
        return b;
    }

    /** A bed, its foot here and its head the way it faces. Its head (where the game's point of interest is). */
    static BlockPos bed(ServerLevel level, BlockPos foot, Direction facing) {
        BlockState b = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, facing);
        level.setBlock(foot, b.setValue(BedBlock.PART, BedPart.FOOT), 3);
        BlockPos head = foot.relative(facing);
        level.setBlock(head, b.setValue(BedBlock.PART, BedPart.HEAD), 3);
        return head;
    }

    static BlockPos place(ServerLevel level, int x, int z, Block block) {
        BlockPos p = Kit.surface(level, x, z);
        level.setBlock(p, block.defaultBlockState(), 3);
        return p;
    }

    /** A villager of this trade standing here, at its first level with no trade XP, with these offers (or the game's
     *  own, if none are given); held where it stands (no walking off), its brain its own unless {@code ai} is false. */
    static Villager villager(ServerLevel level, BlockPos stand, VillagerProfession p, boolean ai, MerchantOffer... offers) {
        Villager v = EntityType.VILLAGER.create(level);
        v.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 180.0F, 0.0F);
        // The trade first: a villager given a new trade forgets its offers.
        v.setVillagerData(v.getVillagerData().setType(VillagerType.PLAINS).setProfession(p).setLevel(1));
        v.setVillagerXp(0);
        // Its brain made for its trade, as the game makes it (a brain made for no trade lets its job site go at once,
        // and with it its trade and offers).
        v.refreshBrain(level);
        if (offers.length > 0) {
            MerchantOffers mo = new MerchantOffers();
            for (MerchantOffer o : offers) mo.add(o);
            v.setOffers(mo);
        }
        v.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);
        v.setPersistenceRequired();
        if (!ai) v.setNoAi(true);
        level.addFreshEntity(v);
        return v;
    }

    /** A villager at its stall: its workstation here, the villager standing before it, its job site the workstation and
     *  its meeting place the bell (EmeraldStage.employ), as one in a village the world built has them. */
    static Villager atStall(ServerLevel level, BlockPos station, Block block, VillagerProfession p, @Nullable BlockPos bell, boolean ai,
                            MerchantOffer... offers) {
        level.setBlock(station, block.defaultBlockState(), 3);
        Villager v = villager(level, station.north(), p, ai, offers);
        EmeraldStage.employ(level, v, station, bell);
        return v;
    }

    /** An offer of the game's kind in which the villager buys so many of this for an emerald. */
    static MerchantOffer buys(Item what, int n, int maxUses, int xp) {
        return new MerchantOffer(new ItemCost(what, n), new ItemStack(Items.EMERALD), maxUses, xp, 0.05F);
    }

    static Holder<Enchantment> mending(ServerLevel level) {
        return level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.MENDING);
    }

    /** A librarian's Mending: so many emeralds and a book, as the game makes the offer (twelve uses, a price multiplier
     *  of a fifth), used so many times, with so much demand on it. */
    static MerchantOffer mendingOffer(ServerLevel level, int emeralds, int uses, int demand) {
        ItemStack book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(mending(level), 1));
        return new MerchantOffer(new ItemCost(Items.EMERALD, emeralds), Optional.of(new ItemCost(Items.BOOK)), book, uses, 12, 1, 0.2F, demand);
    }

    /** A claim on a point of interest, as a villager's brain makes one: the ticket taken and the memory set. Whether a
     *  ticket was there to take. */
    static boolean claim(ServerLevel level, Villager v, MemoryModuleType<GlobalPos> m, BlockPos at) {
        var poi = level.getPoiManager();
        boolean took = poi.exists(at, h -> true) && poi.take(h -> true, (h, p) -> p.equals(at), at, 1).isPresent();
        v.getBrain().setMemory(m, GlobalPos.of(level.dimension(), at.immutable()));
        return took;
    }

    @Nullable
    static BlockPos memory(Villager v, MemoryModuleType<GlobalPos> m) {
        return v.getBrain().hasMemoryValue(m) ? v.getBrain().getMemory(m).map(GlobalPos::pos).orElse(null) : null;
    }

    /** The villager's claims on anything of a town's. */
    static List<String> townClaims(ServerLevel level, Villager v) {
        List<String> out = new ArrayList<>();
        for (MemoryModuleType<GlobalPos> m : CLAIMS) {
            BlockPos p = memory(v, m);
            if (p != null && TwoPeoples.townGround(level, p)) out.add(m + " " + p.toShortString());
        }
        return out;
    }

    /** How many times this offer of the villager's has been used; -1 if it has no such offer (it lost its trade). */
    static int uses(Villager v, int i) {
        MerchantOffers o = v.getOffers();
        return i < o.size() ? o.get(i).getUses() : -1;
    }

    static int free(ServerLevel level, BlockPos p) {
        return level.getPoiManager().getFreeTickets(p);
    }

    static int most(ServerLevel level, BlockPos p) {
        return level.getPoiManager().getType(p).map(h -> h.value().maxTickets()).orElse(-1);
    }

    static boolean newsHas(UUID id, String words) {
        for (Villages.News n : Villages.news(id)) if (n.text().contains(words)) return true;
        return false;
    }

    static boolean logHas(UUID id, String words) {
        for (String l : EmeraldTrader.log(id)) if (l.contains(words)) return true;
        return false;
    }

    @Nullable
    static EmeraldTrader.Seller seller(EmeraldTrader.Hamlet h, String trade) {
        for (EmeraldTrader.Seller s : h.sellers()) if (s.trade().equals(trade)) return s;
        return null;
    }

    @Nullable
    static EmeraldTrader.Hamlet booked(UUID id, String key) {
        for (EmeraldTrader.Hamlet h : EmeraldTrader.book(id)) if (h.key().equals(key)) return h;
        return null;
    }

    /** Every offer of every villager on the Trading Post page, in its words. */
    static List<String> pageDeals(CompoundTag page) {
        List<String> out = new ArrayList<>();
        ListTag villages = page.getList("villages", Tag.TAG_COMPOUND);
        for (int i = 0; i < villages.size(); i++) {
            ListTag folk = villages.getCompound(i).getList("folk", Tag.TAG_COMPOUND);
            for (int j = 0; j < folk.size(); j++) {
                CompoundTag s = folk.getCompound(j);
                ListTag deals = s.getList("deals", Tag.TAG_STRING);
                for (int k = 0; k < deals.size(); k++) out.add(s.getString("who") + ": " + deals.getString(k));
            }
        }
        return out;
    }

    static boolean anyHas(List<String> lines, String... words) {
        for (String l : lines) {
            boolean all = true;
            for (String w : words) if (!l.contains(w)) { all = false; break; }
            if (all) return true;
        }
        return false;
    }

    static void done(GameTestHelper helper, Kit.Expect ex) {
        if (ex.clean()) helper.succeed();
        else helper.fail(ex.summary());
    }

    // ================================================================== Part 1: two peoples

    /**
     * A villager free to wander beside a town for three minutes. The town has a guard, a composter, a bed and a bell on
     * its square; the villager has a bed of its own just outside. It tries for the town's composter and bell (a jobless
     * villager looks for a workstation, every villager for a bell), and each claim is undone within a second. At the end
     * it is still a villager, the same one, with no trade, its own bed still its own, unharmed, and nobody has joined.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4600, batch = "ep01_villager_stays_a_villager")
    public static void ep01_villager_stays_a_villager(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1420000;
        Town t = town(helper, x, 3, Villages.Age.WOOD, StationTask.NONE, StationTask.GUARD, StationTask.NONE);
        Kit.hold(level, x + 40, Z, 72);
        Kit.prepare(level, x + 40, Z, 72);
        // The town's own, on its square.
        BlockPos ourBed = bed(level, Kit.surface(level, x + 10, Z - 8), Direction.NORTH);
        BlockPos ourJob = place(level, x + 10, Z + 9, Blocks.COMPOSTER);
        BlockPos ourBell = bellOnPost(level, x + 11, Z + 3);
        // The villager's own bed, outside the town's ground, and the villager beside the town, free to go where it likes.
        BlockPos itsBed = bed(level, Kit.surface(level, x + 62, Z + 4), Direction.EAST);
        BlockPos stand = Kit.surface(level, x + 56, Z);
        Villager v = EntityType.VILLAGER.create(level);
        v.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 90.0F, 0.0F);
        v.setVillagerData(v.getVillagerData().setType(VillagerType.PLAINS));
        v.setPersistenceRequired();
        level.addFreshEntity(v);
        helper.assertTrue(Kit.live(level, x + 40, Z, 72), "test setup: the ground is live");
        helper.assertTrue(TwoPeoples.townGround(level, ourBed) && TwoPeoples.townGround(level, ourJob) && TwoPeoples.townGround(level, ourBell)
            && !TwoPeoples.townGround(level, stand) && !TwoPeoples.townGround(level, itsBed),
            "test setup: the town's things on its ground, the villager and its bed off it");
        final UUID vid = v.getUUID();
        final int heads = Villages.headcount(t.id());
        final long day = level.getDayTime() / 24000L;
        final int minutes = 3 * 1200;
        // {(unused), the longest any one claim was held, claims seen, ticks since the town was last claimed}
        final int[] run = { 0, 0, 0, 0 };
        // Each claim as the villager's brain set it (a new claim is a new value), and the tick it was first seen.
        final java.util.Map<MemoryModuleType<GlobalPos>, Object[]> held = new java.util.HashMap<>();
        Kit.log("ep01 a villager beside " + Villages.name(t.id()) + " (" + heads + " folk, a guard among them); the town's bed "
            + ourBed.toShortString() + ", composter " + ourJob.toShortString() + ", bell " + ourBell.toShortString());
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            // Its own bed claimed once the bed's point of interest is surely in (the tick after it was set down).
            if (tick <= 1) {
                if (tick == 1) helper.assertTrue(claim(level, v, MemoryModuleType.HOME, itsBed), "test setup: the villager has a bed of its own");
                return;
            }
            Entity e = level.getEntity(vid);
            if (!(e instanceof Villager vg) || !vg.isAlive()) {
                helper.fail("the villager is gone at tick " + tick + ": " + e);
                return;
            }
            List<String> claims = townClaims(level, vg);
            for (MemoryModuleType<GlobalPos> m : CLAIMS) {
                GlobalPos g = vg.getBrain().hasMemoryValue(m) ? vg.getBrain().getMemory(m).orElse(null) : null;
                if (g == null || !TwoPeoples.townGround(level, g.pos())) {
                    held.remove(m);
                    continue;
                }
                Object[] was = held.get(m);
                if (was == null || was[0] != g) {
                    held.put(m, new Object[]{ g, tick });
                    run[2]++;
                    Kit.log("ep01 @" + tick + " the villager claimed " + m + " " + g.pos().toShortString());
                } else {
                    run[1] = (int) Math.max(run[1], tick - (Long) was[1] + 1);
                }
            }
            if (claims.isEmpty()) run[3]++;
            else run[3] = 0;
            if (tick % 600 == 0) {
                Kit.log("ep01 @" + tick + " the villager at " + vg.blockPosition().toShortString() + ", "
                    + vg.getVillagerData().getProfession() + "; claims seen " + run[2] + ", longest held " + run[1]
                    + " ticks; undone in the town today " + TwoPeoples.turnedAway(t.id(), day)[0]);
            }
            if (tick < minutes || run[3] < 5) return;
            Kit.Expect ex = new Kit.Expect();
            ex.that(!AssistantConfig.replaceVillagers() && VanillaVillages.apart(), "the old takeover is off unless it is turned on");
            ex.that(vg.getType() == EntityType.VILLAGER && vg.getUUID().equals(vid),
                "after three minutes beside the town it is still a villager, the same one");
            ex.that(Villages.headcount(t.id()) == heads, "nobody joined the town: " + Villages.headcount(t.id()) + " folk (" + heads + " before)");
            ex.that(run[2] >= 1, "it did try for the town's things (" + run[2] + " claims), so keeping it off was put to the test");
            ex.that(run[1] <= 30, "it never held a claim on the town's things longer than a second and a half: the longest " + run[1] + " ticks");
            ex.that(TwoPeoples.turnedAway(t.id(), day)[1] >= 1, "the claims were undone by the sweep: " + TwoPeoples.turnedAway(t.id(), day)[1]);
            ex.that(level.getBlockState(ourJob).is(Blocks.COMPOSTER) && level.getBlockState(ourBell).is(Blocks.BELL)
                && level.getBlockState(ourBed).getBlock() instanceof BedBlock, "test setup: the town's bed, composter and bell still stand");
            ex.that(free(level, ourBed) == most(level, ourBed) && free(level, ourJob) == most(level, ourJob) && free(level, ourBell) == most(level, ourBell),
                "the town's bed, composter and bell are all free: " + free(level, ourBed) + "/" + free(level, ourJob) + "/" + free(level, ourBell));
            ex.that(vg.getVillagerData().getProfession() == VillagerProfession.NONE,
                "it took up no trade at the town's composter: " + vg.getVillagerData().getProfession());
            ex.that(itsBed.equals(memory(vg, MemoryModuleType.HOME)) && free(level, itsBed) == 0, "its own bed outside the town is still its own");
            ex.that(vg.getHealth() >= vg.getMaxHealth(), "nobody harmed it, the watch included: " + vg.getHealth());
            Kit.log("ep01 done at tick " + tick + ": " + ex.summary());
            done(helper, ex);
        });
    }

    /**
     * Claims undone at once, deterministically (the villagers' brains held still): a farmer with the town's bed, composter
     * and bell; a villager asleep in another of the town's beds; one whose job site is the town's second composter with
     * the ticket taken though its trade does not match (the game's own release would not hand that ticket back); and a
     * librarian of a village outside the town with its own bed, lectern and bell. One sweep: the town's claims undone and
     * the tickets back, the sleeper woken, the librarian's claims untouched and its village known by its bell. Then the
     * sweep the server runs every second does the same unasked.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ep02_claims_undone")
    public static void ep02_claims_undone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1422000;
        Town t = town(helper, x, 2, Villages.Age.WOOD);
        Kit.hold(level, x + 120, Z, 32);
        Kit.prepare(level, x + 120, Z, 32);
        BlockPos bedA = bed(level, Kit.surface(level, x + 10, Z - 8), Direction.NORTH);
        BlockPos bedC = bed(level, Kit.surface(level, x - 10, Z - 8), Direction.NORTH);
        BlockPos jobA = place(level, x + 10, Z + 9, Blocks.COMPOSTER);
        BlockPos jobD = place(level, x - 10, Z + 9, Blocks.COMPOSTER);
        BlockPos bell = bellOnPost(level, x + 11, Z + 3);
        Villager a = villager(level, Kit.surface(level, x + 8, Z), VillagerProfession.FARMER, false);
        Villager c = villager(level, Kit.surface(level, x - 8, Z - 3), VillagerProfession.NONE, false);
        Villager d = villager(level, Kit.surface(level, x - 8, Z + 3), VillagerProfession.NONE, false);
        // Their own village, well outside the town.
        BlockPos bellB = bellOnPost(level, x + 120, Z);
        BlockPos lecternB = place(level, x + 125, Z, Blocks.LECTERN);
        BlockPos bedB = bed(level, Kit.surface(level, x + 116, Z + 5), Direction.SOUTH);
        Villager b = villager(level, Kit.surface(level, x + 123, Z - 2), VillagerProfession.LIBRARIAN, false);
        helper.runAtTickTime(2, () -> {
            helper.assertTrue(claim(level, a, MemoryModuleType.HOME, bedA) && claim(level, a, MemoryModuleType.JOB_SITE, jobA)
                && claim(level, a, MemoryModuleType.MEETING_POINT, bell), "test setup: the farmer claims the town's bed, composter and bell");
            helper.assertTrue(claim(level, c, MemoryModuleType.HOME, bedC), "test setup: the sleeper claims the town's other bed");
            c.startSleeping(bedC);
            helper.assertTrue(c.isSleeping() && level.getBlockState(bedC).getValue(BedBlock.OCCUPIED), "test setup: the villager is asleep in it");
            helper.assertTrue(claim(level, d, MemoryModuleType.JOB_SITE, jobD), "test setup: a jobless villager holds the second composter's ticket");
            helper.assertTrue(claim(level, b, MemoryModuleType.HOME, bedB) && claim(level, b, MemoryModuleType.JOB_SITE, lecternB)
                && claim(level, b, MemoryModuleType.MEETING_POINT, bellB), "test setup: the librarian claims its own village's things");
            helper.assertTrue(free(level, bedA) == 0 && free(level, jobA) == 0 && free(level, bell) == most(level, bell) - 1 && free(level, jobD) == 0,
                "test setup: the tickets are taken");
        });
        helper.runAtTickTime(4, () -> {
            long day = level.getDayTime() / 24000L;
            TwoPeoples.sweepForTests(level);
            Kit.Expect ex = new Kit.Expect();
            ex.that(memory(a, MemoryModuleType.HOME) == null && memory(a, MemoryModuleType.JOB_SITE) == null
                && memory(a, MemoryModuleType.MEETING_POINT) == null, "the farmer's claims on the town's things are let go");
            ex.that(free(level, bedA) == 1 && free(level, jobA) == 1 && free(level, bell) == most(level, bell),
                "and their tickets handed back: bed " + free(level, bedA) + ", composter " + free(level, jobA) + ", bell " + free(level, bell));
            ex.that(!c.isSleeping() && !level.getBlockState(bedC).getValue(BedBlock.OCCUPIED) && memory(c, MemoryModuleType.HOME) == null
                && free(level, bedC) == 1, "the villager asleep in the town's bed is woken and the bed is the town's again");
            ex.that(memory(d, MemoryModuleType.JOB_SITE) == null && free(level, jobD) == 1,
                "a job site whose ticket the game's own release would keep is handed back all the same: " + free(level, jobD));
            ex.that(bedB.equals(memory(b, MemoryModuleType.HOME)) && lecternB.equals(memory(b, MemoryModuleType.JOB_SITE))
                && bellB.equals(memory(b, MemoryModuleType.MEETING_POINT)) && free(level, bedB) == 0 && free(level, lecternB) == 0,
                "the librarian of the village outside keeps its own bed, lectern and bell");
            VanillaVillages.Known k = VanillaVillages.at(level, bellB.getX(), bellB.getZ(), 0);
            ex.that(k != null && k.within(bedB.getX(), bedB.getZ(), 0) && k.within(lecternB.getX(), lecternB.getZ(), 0),
                "its bell makes its village known, its ground as far as their beds and work: " + (k == null ? "none" : k.words() + " " + k.across() + "x" + k.deep()));
            ex.that(TwoPeoples.turnedAway(t.id(), day)[1] >= 5, "five claims undone in the town: " + TwoPeoples.turnedAway(t.id(), day)[1]);
            ex.that(a.isAlive() && c.isAlive() && d.isAlive() && b.isAlive() && Villages.headcount(t.id()) == 2,
                "every villager left a villager, none counted among the town's folk");
            Kit.log("ep02 after the sweep: " + ex.summary());
            if (!ex.clean()) {
                helper.fail(ex.summary());
                return;
            }
            // Once more, and this time the sweep the server runs every second is left to find it.
            helper.assertTrue(claim(level, a, MemoryModuleType.HOME, bedA), "test setup: the farmer claims the town's bed again");
        });
        helper.runAtTickTime(40, () -> {
            helper.assertTrue(memory(a, MemoryModuleType.HOME) == null && free(level, bedA) == 1,
                "the server's own sweep undoes the claim within a second, unasked");
            helper.succeed();
        });
    }

    /**
     * A village of villagers forty blocks across, ninety blocks east of a town. A founding on its doorstep is refused,
     * with the reason, and puts nothing up; one past {@link VanillaVillages#CLEAR} is not stood in the way of. The town
     * standing near it: its founding's levelled ground would stop at the margin; every house lot it finds keeps the
     * margin and the lot's own width off the villagers' ground (it grows the other way); and with a village of villagers
     * near enough that the wall's ring would come within the margin, no wall.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ep03_towns_keep_clear")
    public static void ep03_towns_keep_clear(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1424000;
        Town t = town(helper, x, 3, Villages.Age.WOOD);
        Founding.resetForTests(level.getServer());
        Kit.hold(level, x + 90, Z, 32);
        Kit.prepare(level, x + 90, Z, 32);
        BlockPos theirBell = bellOnPost(level, x + 90, Z);
        VanillaVillages.Known k = VanillaVillages.recordForTests(level, theirBell, 16);
        Kit.Expect ex = new Kit.Expect();
        // A founding on their doorstep.
        BlockPos near = new BlockPos(k.x1() + 24, theirBell.getY(), Z);
        Founding.Outcome o = Founding.propose(level, near, null, 0.0F);
        Kit.log("ep03 a founding 24 blocks past their edge: " + o.message());
        ex.that(!o.ok() && o.message().contains("villagers' own"), "a founding on a village of villagers' doorstep is refused, and says why");
        ex.that(Founding.boardNear(level, near, 64) == null && Founding.status(level.getServer()).isEmpty(), "and puts nothing up");
        ex.that(VanillaVillages.inTheWayOfFounding(level, new BlockPos(k.x1() + VanillaVillages.CLEAR - 4, theirBell.getY(), Z)) != null,
            "still in the way just inside the clear ground");
        ex.that(VanillaVillages.inTheWayOfFounding(level, new BlockPos(k.x1() + VanillaVillages.CLEAR + 4, theirBell.getY(), Z)) == null,
            "not in the way of one past it");
        // The town standing near: its levelled ground stops at the margin.
        int edge = k.edge(t.heart().getX(), t.heart().getZ());
        ex.that(VanillaVillages.roomFrom(level, t.heart()) == edge - VanillaVillages.MARGIN,
            "the town's ground may be levelled only to " + VanillaVillages.roomFrom(level, t.heart()) + " blocks out (their edge " + edge + " off)");
        // Its lots: never within the margin, nor the lot's own half-width.
        List<BlockPos> sites = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            Villages.Site s = Villages.siteFor(level, t.id(), "house");
            if (s == null) break;
            sites.add(s.anchor());
            Villages.noteProject(t.id(), "house", level.getGameTime());
        }
        int east = 0, west = 0, worst = Integer.MAX_VALUE;
        for (BlockPos s : sites) {
            if (s.getX() > t.heart().getX()) east = Math.max(east, s.getX() - t.heart().getX());
            else west = Math.max(west, t.heart().getX() - s.getX());
            worst = Math.min(worst, k.edge(s.getX(), s.getZ()));
        }
        Kit.log("ep03 " + sites.size() + " house lots; furthest east " + east + ", west " + west + "; the nearest to their ground " + worst);
        ex.that(sites.size() >= 6, "the town still finds lots for its houses: " + sites.size());
        ex.that(worst > VanillaVillages.MARGIN + TownPlan.LOT / 2, "no lot within the margin of their ground: the nearest " + worst + " off");
        // A village of villagers forty blocks west: the wall's ring would come within its margin. No wall.
        VanillaVillages.recordForTests(level, new BlockPos(t.heart().getX() - 44, t.heart().getY(), Z), 4);
        ex.that(Villages.siteFor(level, t.id(), "fortify") == null, "no wall round a town whose ring would come within a village of villagers' margin");
        done(helper, ex);
    }

    /**
     * The switch. Off (as it is unless somebody turns it on): the takeover's sweep turns nobody, the villagers' village is
     * known and kept clear. On: the two peoples are not kept apart (no village of villagers stands in a town's way, the
     * two peoples' sweep has nothing to do) and the takeover's sweep turns the villager into a folk of a town of ours,
     * as it always did. Off again after.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ep04_takeover_switch")
    public static void ep04_takeover_switch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1426000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos bell = bellOnPost(level, x, Z);
        Villager lib = atStall(level, Kit.surface(level, x + 5, Z), Blocks.LECTERN, VillagerProfession.LIBRARIAN, bell, false);
        BlockPos spot = lib.blockPosition();
        helper.runAtTickTime(2, () -> {
            Kit.Expect ex = new Kit.Expect();
            ex.that(!AssistantConfig.replaceVillagers() && VanillaVillages.apart(), "the takeover is off by default");
            TwoPeoples.sweepForTests(level);
            ex.that(VanillaVillages.at(level, bell.getX(), bell.getZ(), 0) != null, "off: the villagers' bell makes their village known");
            ex.that(VillagerTakeover.sweep(level) == 0 && lib.isAlive() && !lib.isRemoved(), "off: the takeover's sweep turns nobody");
            ex.that(VanillaVillages.inTheWayOfFounding(level, bell.east(40)) != null, "off: no town is founded on their doorstep");
            ex.that(Villages.nearest(level, spot, 64) == null, "off: their village is no town of ours");
            AssistantConfig.replaceVillagersForTests(true);
            try {
                ex.that(AssistantConfig.replaceVillagers() && !VanillaVillages.apart()
                    && VanillaVillages.at(level, bell.getX(), bell.getZ(), 0) == null, "on: the two peoples are not kept apart");
                ex.that(VanillaVillages.inTheWayOfFounding(level, bell.east(40)) == null, "on: their village stands in no town's way");
                ex.that(TwoPeoples.sweepForTests(level) == 0, "on: the two peoples' sweep has nothing to do");
                int turned = VillagerTakeover.sweep(level);
                List<VillageFolkEntity> folk = level.getEntitiesOfClass(VillageFolkEntity.class, new AABB(spot).inflate(6));
                Kit.log("ep04 the takeover turned " + turned + "; folk where the villager stood: " + folk.size());
                ex.that(turned == 1 && lib.isRemoved(), "on: the takeover's sweep turns the villager");
                ex.that(folk.size() == 1, "on: a folk stands where the villager stood: " + folk.size());
                ex.that(Villages.nearest(level, spot, 64) != null, "on: and its village is a town of ours");
            } finally {
                AssistantConfig.replaceVillagersForTests(null);
            }
            ex.that(!AssistantConfig.replaceVillagers() && VanillaVillages.apart(), "off again after");
            done(helper, ex);
        });
    }

    // ================================================================== Part 2: the emerald trader

    /**
     * When the trade opens, and who takes it. A Stone Age town of thirteen with a full larder and more: no trade while
     * it knows of no village of villagers (the page says why); none in the Wood Age; one once a village of villagers lies
     * within a day's walk; none with the takeover on. The town's choice takes it (not a guard, not the smith). The Trading
     * Post is wanted, goes up on a lot of the plan, is wanted no more, and is the trader's post. The page: open, apart,
     * the buying list, the trader.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ep05_trade_opens")
    public static void ep05_trade_opens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1428000;
        Town t = town(helper, x, 13, Villages.Age.STONE, StationTask.NONE, StationTask.GUARD, StationTask.GUARD,
            StationTask.FARM, StationTask.FARM, StationTask.SMITH);
        surplus(t);
        Kit.Expect ex = new Kit.Expect();
        ex.that(EmeraldTrader.reckonForTests(level, t.v()) == 0, "no village of villagers known: no trade");
        String why = EmeraldTrader.report(level, t.v()).getString("why");
        ex.that(why.contains("No village of villagers"), "the page says why: " + why);
        VanillaVillages.Known k = VanillaVillages.recordForTests(level, new BlockPos(x + 300, t.heart().getY(), Z), 16);
        Villages.ageForTests(t.id(), Villages.Age.WOOD);
        ex.that(EmeraldTrader.reckonForTests(level, t.v()) == 0, "the Wood Age: not yet");
        Villages.ageForTests(t.id(), Villages.Age.STONE);
        ex.that(EmeraldTrader.reckonForTests(level, t.v()) == 1,
            "the Stone Age, thirteen strong, a village of villagers " + k.edge(x, Z) + " blocks off and goods to spare: one trader");
        AssistantConfig.replaceVillagersForTests(true);
        try {
            ex.that(EmeraldTrader.reckonForTests(level, t.v()) == 0 && !EmeraldTrader.report(level, t.v()).getBoolean("apart"),
                "with the takeover on there is nobody to trade with");
        } finally {
            AssistantConfig.replaceVillagersForTests(null);
        }
        ex.that(EmeraldTrader.reckonForTests(level, t.v()) == 1, "off again: the trade is open");
        ex.that(EmeraldTrader.postWanted(t.id()), "the Trading Post is wanted");
        Kit.log("ep05 the town would build: " + Villages.projectsWanted(t.id()));
        VillageFolkEntity f = EmeraldTrader.appointForTests(level, t.v());
        int who = t.folk().indexOf(f);
        ex.that(f != null && f.stationTask() == StationTask.EMERALD, "a folk takes up the trade: " + (f == null ? "nobody" : f.displayNameCap()));
        ex.that(who != 1 && who != 2 && who != 5, "not a guard and not the smith: folk " + who);
        ex.that(f != null && f.ageYears() >= 18 && f.ageYears() <= 65, "grown and fit for the road: " + (f == null ? 0 : f.ageYears()));
        ex.that(f != null && EmeraldTrader.cardLine(f) != null, "its card says what it does");
        BlockPos post = EmeraldTrader.buildPostForTests(level, t.v());
        Kit.log("ep05 the Trading Post at " + post);
        ex.that(post != null && Villages.builtStructure(t.id(), EmeraldTrader.POST) != null, "the Trading Post goes up on a lot of the town's plan");
        ex.that(!EmeraldTrader.postWanted(t.id()), "and once it stands it is wanted no more");
        ex.that(post != null && post.equals(EmeraldTrader.postForTests(t.v())), "it is the trader's post");
        // The afternoon at home: the trader's ground is the post.
        level.setDayTime(6000);
        if (f != null) {
            EmeraldTrader.work(f, level);                       // (what it carries of the town's goes into the stores first)
            EmeraldTrader.work(f, level);
        }
        ex.that(f != null && post != null && f.workZone() != null && f.workZone().center().distManhattan(post) <= 3,
            "in the afternoon its ground is the Trading Post: " + (f == null || f.workZone() == null ? "none" : f.workZone().center().toShortString()));
        CompoundTag page = EmeraldTrader.report(level, t.v());
        List<String> wants = new ArrayList<>();
        ListTag w = page.getList("wants", Tag.TAG_STRING);
        for (int i = 0; i < w.size(); i++) wants.add(w.getString(i));
        Kit.log("ep05 the page: " + page);
        ex.that(page.getBoolean("open") && page.getBoolean("apart") && !page.contains("why"), "the Trading Post page: the trade open, the peoples apart");
        ex.that(anyHas(wants, "Mending") && anyHas(wants, "bell"), "its buying list: a Mending book, a bell for the town: " + wants);
        ex.that(page.getList("traders", Tag.TAG_STRING).size() == 1, "its trader");
        String board = EmeraldTrader.boardLine(t.id());
        ex.that(board != null && board.contains("Trading Post"), "the board has the trade: " + board);
        done(helper, ex);
    }

    /**
     * Exploring like a scout. A Stone Age town of thirteen with goods to spare, and a little village of villagers a
     * hundred and fifty blocks east (a farmer and a librarian at their stalls round a bell). The book is empty; the
     * villagers' bell makes their village known on the land, which opens the trade. The trader sets out exploring (no
     * village in its book to go to), drawn the way the village lies; sees it from the road; writes it into the scouts'
     * atlas, its book and the town's news; walks to it; and records its villagers, their trades and levels, and every
     * offer they make. The page lists them.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "ep06_trader_finds_villagers")
    public static void ep06_trader_finds_villagers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1430000;
        Town t = town(helper, x, 13, Villages.Age.STONE, StationTask.NONE, StationTask.GUARD, StationTask.GUARD, StationTask.FARM);
        surplus(t);
        Kit.hold(level, x + OUT / 2, Z, OUT / 2 + 48);
        Kit.prepare(level, x + OUT / 2, Z, OUT / 2 + 48);
        BlockPos bell = bellOnPost(level, x + OUT, Z);
        Villager farmer = atStall(level, Kit.surface(level, x + OUT + 5, Z), Blocks.COMPOSTER, VillagerProfession.FARMER, bell, true,
            buys(Items.WHEAT, 20, 16, 2), buys(Items.POTATO, 26, 16, 2));
        Villager librarian = atStall(level, Kit.surface(level, x + OUT - 5, Z), Blocks.LECTERN, VillagerProfession.LIBRARIAN, bell, true,
            buys(Items.PAPER, 24, 16, 2), mendingOffer(level, 12, 0, 0));
        helper.assertTrue(Kit.live(level, x + OUT / 2, Z, OUT / 2 + 48), "test setup: the ground is live");
        TwoPeoples.sweepForTests(level);
        VanillaVillages.Known k = VanillaVillages.at(level, bell.getX(), bell.getZ(), 0);
        Kit.Expect ex = new Kit.Expect();
        ex.that(k != null, "the villagers' bell makes their village known on the land");
        ex.that(EmeraldTrader.book(t.id()).isEmpty() && Scouts.atlas(t.id()).isEmpty(), "the book and the atlas are empty: nobody has found it yet");
        ex.that(EmeraldTrader.reckonForTests(level, t.v()) == 1, "a village of villagers plainly on the land round the town opens the trade");
        VillageFolkEntity f = EmeraldTrader.appointForTests(level, t.v());
        ex.that(f != null && EmeraldTrader.setOutForTests(f, level, null), "the trader sets out");
        EmeraldTrader.Venture vt = f == null ? null : EmeraldTrader.ventureForTests(f);
        ex.that(vt != null && vt.purpose() == EmeraldTrader.Venture.Purpose.EXPLORE && vt.hamlet() == null, "exploring: there is no village in its book to go to");
        ex.that(f != null && has(f, Items.WHEAT) > 0, "with a little of the town's surplus in case: " + (f == null ? 0 : has(f, Items.WHEAT)) + " wheat");
        if (!ex.clean() || f == null || k == null) {
            helper.fail(ex.summary());
            return;
        }
        final long[] foundAt = { -1 };
        final BlockPos[] foundFrom = { null };
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            EmeraldTrader.Venture now = EmeraldTrader.ventureForTests(f);
            if (foundAt[0] < 0 && now != null && now.hamlet() != null) {
                foundAt[0] = tick;
                foundFrom[0] = f.blockPosition();
                Kit.log("ep06 @" + tick + " found " + now.hamlet() + " from " + f.blockPosition().toShortString() + ", "
                    + k.edge(f.getBlockX(), f.getBlockZ()) + " blocks from its edge");
            }
            if (tick % 200 == 0) Kit.log("ep06 @" + tick + " " + f.debugLine());
            EmeraldTrader.Hamlet h = booked(t.id(), k.key());
            if (h == null || h.sellers().size() < 2) return;
            Kit.Expect end = new Kit.Expect();
            end.that(foundAt[0] >= 0 && foundFrom[0] != null && k.edge(foundFrom[0].getX(), foundFrom[0].getZ()) > 4,
                "it saw the village from the road before it reached it");
            boolean inAtlas = false;
            for (Scouts.Find find : Scouts.atlas(t.id())) {
                if (find.kind() == Scouts.Kind.SETTLEMENT && find.at().distManhattan(bell) <= 48) inAtlas = true;
            }
            end.that(inAtlas, "the village is in the scouts' atlas");
            end.that(newsHas(t.id(), "found") && logHas(t.id(), "found"), "the town is told, and the book's log has it");
            EmeraldTrader.Seller fs = seller(h, "farmer"), ls = seller(h, "librarian");
            end.that(fs != null && fs.level() == 1 && fs.deals().size() == farmer.getOffers().size(),
                "the farmer recorded, its level and every offer: " + (fs == null ? "none" : fs.deals().size() + " offers"));
            end.that(fs != null && fs.deals().stream().anyMatch(d -> d.cost().equals("minecraft:wheat") && d.costN() == 20 && d.result().equals("minecraft:emerald")),
                "the farmer buys twenty wheat for an emerald");
            end.that(ls != null && ls.deals().stream().anyMatch(d -> d.ench().contains("minecraft:mending") && d.costN() == 12
                && d.cost2().equals("minecraft:book")), "the librarian sells Mending for twelve emeralds and a book");
            end.that(ls != null && !ls.name().isEmpty(), "each villager has a name in the book: " + (ls == null ? "" : ls.name()));
            List<String> deals = pageDeals(EmeraldTrader.report(level, t.v()));
            Kit.log("ep06 the page's offers: " + deals);
            end.that(anyHas(deals, "librarian", "sells Mending (a book) for 12 emeralds and a book", "(12 of 12 left)"),
                "the Trading Post page lists the offers as they stand");
            Kit.log("ep06 recorded at tick " + tick + ": " + end.summary());
            Scouts.abandon(level, f);
            done(helper, end);
        });
    }

    /**
     * Selling the town's real surplus. A Stone Age town of thirteen with a full larder and more; a farmer of a village
     * of villagers that buys twenty wheat for an emerald and twenty-six potatoes for an emerald (the game's own novice
     * farmer's offers, sixteen uses, two XP each). The trader takes only what the stores can spare, trades at the
     * farmer's stall one offer at a time, and comes home: the offers' uses are up by the trades, the farmer has its
     * trade XP and goes up a level with the fifth, the emeralds go into the stores and the account, the unsold goods back.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "ep07_trader_sells_surplus")
    public static void ep07_trader_sells_surplus(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1432000;
        Town t = town(helper, x, 13, Villages.Age.STONE, StationTask.NONE, StationTask.GUARD, StationTask.GUARD, StationTask.FARM);
        surplus(t);
        Kit.hold(level, x + OUT, Z, 40);
        Kit.prepare(level, x + OUT, Z, 40);
        BlockPos bell = bellOnPost(level, x + OUT, Z);
        Villager farmer = atStall(level, Kit.surface(level, x + OUT + 5, Z), Blocks.COMPOSTER, VillagerProfession.FARMER, bell, true,
            buys(Items.WHEAT, 20, 16, 2), buys(Items.POTATO, 26, 16, 2));
        helper.assertTrue(Kit.live(level, x + OUT, Z, 40), "test setup: the ground is live");
        TwoPeoples.sweepForTests(level);
        VanillaVillages.Known k = VanillaVillages.at(level, bell.getX(), bell.getZ(), 0);
        helper.assertTrue(k != null, "test setup: their village is known by its bell");
        long day = level.getDayTime() / 24000L;
        EmeraldTrader.Hamlet h = EmeraldTrader.knowForTests(t.v(), k, day);
        Kit.Expect ex = new Kit.Expect();
        ex.that(EmeraldTrader.reckonForTests(level, t.v()) == 1, "the trade is open");
        VillageFolkEntity f = EmeraldTrader.appointForTests(level, t.v());
        helper.assertTrue(f != null, "test setup: a trader");
        final int heldWheat = stock(level, t.id(), Items.WHEAT), heldPotatoes = stock(level, t.id(), Items.POTATO);
        final int spareWheat = Budget.spare(level, t.id(), new ItemStack(Items.WHEAT));
        final int sparePotatoes = Budget.spare(level, t.id(), new ItemStack(Items.POTATO));
        final int hadWheat = has(f, Items.WHEAT), hadPotatoes = has(f, Items.POTATO);       // (a founder's kit: a few potatoes)
        ex.that(EmeraldTrader.setOutForTests(f, level, h.key()), "it sets out for the village");
        final int wheat = has(f, Items.WHEAT), potatoes = has(f, Items.POTATO);
        final int tookWheat = wheat - hadWheat, tookPotatoes = potatoes - hadPotatoes;
        Kit.log("ep07 took " + tookWheat + " wheat (" + spareWheat + " spare of " + heldWheat + ") and " + tookPotatoes + " potatoes ("
            + sparePotatoes + " spare of " + heldPotatoes + "); it had " + hadWheat + " and " + hadPotatoes + " of its own");
        ex.that(tookWheat >= 20 && tookWheat <= spareWheat && tookWheat <= 64, "it takes wheat the town can spare, and no more: " + tookWheat + " of " + spareWheat);
        ex.that(tookPotatoes >= 26 && tookPotatoes <= sparePotatoes && tookPotatoes <= 64, "and potatoes: " + tookPotatoes + " of " + sparePotatoes);
        ex.that(stock(level, t.id(), Items.WHEAT) == heldWheat - tookWheat, "the rest stays in the stores");
        ex.that(farmer.getVillagerData().getProfession() == VillagerProfession.FARMER, "test setup: the farmer is a farmer");
        if (!ex.clean()) {
            helper.fail(ex.summary());
            return;
        }
        // It sells only what it took for the trip (toSell), never a bite of its own.
        final int wheatSales = Math.min(16, tookWheat / 20), potatoSales = Math.min(16, tookPotatoes / 26);
        final int sales = wheatSales + potatoSales;
        EmeraldTrader.arriveForTests(level, f);
        final boolean[] home = { false };
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            EmeraldTrader.Venture now = EmeraldTrader.ventureForTests(f);
            if (tick % 40 == 0) Kit.log("ep07 @" + tick + " " + (now == null ? "home" : now.stage() + ", " + now.trades() + " trades") + "; " + f.debugLine());
            if (home[0] || now == null || now.stage() != EmeraldTrader.Stage.HOME) return;
            home[0] = true;
            Kit.Expect end = new Kit.Expect();
            MerchantOffers offers = farmer.getOffers();
            end.that(now.trades() == sales && now.earned() == sales, "it sold " + sales + " times for " + sales + " emeralds: " + now.trades() + " trades, " + now.earned() + " earned");
            end.that(uses(farmer, 0) == wheatSales && uses(farmer, 1) == potatoSales,
                "the farmer's own offers are used: wheat " + uses(farmer, 0) + " of " + wheatSales + ", potatoes " + uses(farmer, 1) + " of " + potatoSales);
            end.that(farmer.getVillagerXp() == 2 * sales, "the farmer has its trade XP: " + farmer.getVillagerXp());
            if (2 * sales >= 10) {
                end.that(farmer.getVillagerData().getLevel() == 2 && offers.size() > 2,
                    "and went up a level with the trade, new offers on its stall: level " + farmer.getVillagerData().getLevel() + ", " + offers.size() + " offers");
                end.that(logHas(t.id(), "apprentice"), "the trader's log has it: " + EmeraldTrader.log(t.id()));
            }
            end.that(has(f, Items.EMERALD) == sales && has(f, Items.WHEAT) == wheat - 20 * wheatSales,
                "it carries the emeralds and what it did not sell: " + has(f, Items.EMERALD) + " emeralds, " + has(f, Items.WHEAT) + " wheat");
            end.that(farmer.isAlive() && farmer.getHealth() >= farmer.getMaxHealth(), "the farmer is none the worse");
            EmeraldTrader.Hamlet hb = booked(t.id(), k.key());
            EmeraldTrader.Seller s = hb == null ? null : seller(hb, "farmer");
            end.that(s != null && s.level() == farmer.getVillagerData().getLevel(), "the book has the farmer at its new level");
            // Home: into the stores, the account and the log. (What the stores hold is compared in the one tick: the
            // town's own folk take from them as they work.)
            int emeraldsBefore = stock(level, t.id(), Items.EMERALD), wheatBefore = stock(level, t.id(), Items.WHEAT);
            EmeraldTrader.homeForTests(level, f);
            fresh(t);
            int[] account = EmeraldTrader.accountForTests(t.id());
            end.that(stock(level, t.id(), Items.EMERALD) - emeraldsBefore == sales, "the emeralds go into the stores: "
                + (stock(level, t.id(), Items.EMERALD) - emeraldsBefore));
            end.that(stock(level, t.id(), Items.WHEAT) - wheatBefore == wheat - 20 * wheatSales && has(f, Items.WHEAT) == 0,
                "the unsold wheat goes back: " + (stock(level, t.id(), Items.WHEAT) - wheatBefore));
            end.that(account[0] == sales && account[1] == 0 && account[2] == 1, "the account: " + account[0] + " earned, " + account[1] + " spent, " + account[2] + " trips");
            end.that(logHas(t.id(), sales + " emeralds for"), "the log: " + EmeraldTrader.log(t.id()));
            end.that(newsHas(t.id(), "came home from"), "the town hears of the first trade with the village");
            List<String> deals = pageDeals(EmeraldTrader.report(level, t.v()));
            Kit.log("ep07 the page's offers: " + deals);
            end.that(anyHas(deals, "farmer", "buys 20 ", "(" + (16 - wheatSales) + " of 16 left)"), "the Trading Post page lists the offers as they stand");
            end.that(EmeraldTrader.bookNotes(t.id()).stream().anyMatch(n -> n.contains("best customer")), "the trade book notes its best customer");
            done(helper, end);
        });
    }

    /**
     * Buying by need. A Stone Age town of thirteen with forty emeralds, two books and a diamond pickaxe in its stores; a
     * player asks the trader for a Mending book. A librarian of a village of villagers sells Mending for twelve emeralds
     * and a book. The trader buys it through the librarian's own offer (its use, the librarian's XP), brings it home to
     * the stores, the ask comes off the list, and the smith lays it on the town's diamond pickaxe.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "ep08_trader_buys_mending")
    public static void ep08_trader_buys_mending(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1434000;
        Town t = town(helper, x, 13, Villages.Age.STONE, StationTask.NONE, StationTask.SMITH, StationTask.GUARD);
        fill(t, new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64), new ItemStack(Items.EMERALD, 40), new ItemStack(Items.BOOK, 2),
            new ItemStack(Items.DIAMOND_PICKAXE));
        Kit.hold(level, x + OUT, Z, 40);
        Kit.prepare(level, x + OUT, Z, 40);
        BlockPos bell = bellOnPost(level, x + OUT, Z);
        Villager librarian = atStall(level, Kit.surface(level, x + OUT - 5, Z), Blocks.LECTERN, VillagerProfession.LIBRARIAN, bell, true,
            buys(Items.PAPER, 24, 16, 2), mendingOffer(level, 12, 0, 0));
        helper.assertTrue(Kit.live(level, x + OUT, Z, 40), "test setup: the ground is live");
        TwoPeoples.sweepForTests(level);
        VanillaVillages.Known k = VanillaVillages.at(level, bell.getX(), bell.getZ(), 0);
        helper.assertTrue(k != null, "test setup: their village is known by its bell");
        EmeraldTrader.Hamlet h = EmeraldTrader.knowForTests(t.v(), k, level.getDayTime() / 24000L);
        Kit.Expect ex = new Kit.Expect();
        ex.that(EmeraldTrader.reckonForTests(level, t.v()) == 1, "emeralds to spend on what the town wants open the trade");
        VillageFolkEntity f = EmeraldTrader.appointForTests(level, t.v());
        helper.assertTrue(f != null, "test setup: a trader");
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        String reply = EmeraldTrader.askForTests(f, you, "Could you find us a Mending book?");
        Kit.log("ep08 asked for Mending: " + reply);
        ex.that(reply.contains("Mending") && EmeraldTrader.asks(t.id()).contains("book:minecraft:mending"), "a player's ask goes on the buying list");
        List<EmeraldTrader.Want> wants = EmeraldTrader.wantsForTests(level, t.v());
        ex.that(!wants.isEmpty() && wants.get(0).asked() && wants.get(0).item() == Items.ENCHANTED_BOOK,
            "first on the list: " + (wants.isEmpty() ? "nothing" : wants.get(0).words()));
        ex.that(EmeraldTrader.setOutForTests(f, level, h.key()), "it sets out for the village");
        ex.that(has(f, Items.EMERALD) == 40 && has(f, Items.BOOK) == 2, "with the town's emeralds and its books: " + has(f, Items.EMERALD) + ", " + has(f, Items.BOOK));
        if (!ex.clean()) {
            helper.fail(ex.summary());
            return;
        }
        EmeraldTrader.arriveForTests(level, f);
        final boolean[] home = { false };
        final VillageFolkEntity smith = t.folk().get(1);
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            EmeraldTrader.Venture now = EmeraldTrader.ventureForTests(f);
            if (tick % 40 == 0) Kit.log("ep08 @" + tick + " " + (now == null ? "home" : now.stage() + ", " + now.trades() + " trades") + "; " + f.debugLine());
            if (home[0] || now == null || now.stage() != EmeraldTrader.Stage.HOME) return;
            home[0] = true;
            Kit.Expect end = new Kit.Expect();
            end.that(uses(librarian, 1) == 1 && librarian.getVillagerXp() == 1, "the librarian's own offer is used, and it has its XP: "
                + uses(librarian, 1) + " uses, " + librarian.getVillagerXp() + " XP (" + librarian.getVillagerData().getProfession() + ")");
            end.that(now.spent() == 12 && now.got().stream().anyMatch(g -> g.contains("Mending")), "it bought Mending for twelve: " + now.got());
            end.that(has(f, Items.EMERALD) == 28 && has(f, Items.BOOK) == 1, "paid twelve emeralds and a book: " + has(f, Items.EMERALD) + " left, "
                + has(f, Items.BOOK) + " book");
            end.that(CaveDwellerGameTests.has(f, s -> s.is(Items.ENCHANTED_BOOK)
                && EnchantmentHelper.getItemEnchantmentLevel(mending(level), s) + storedMending(level, s) > 0) == 1, "it carries the Mending book");
            int emeraldsBefore = stock(level, t.id(), Items.EMERALD);
            EmeraldTrader.homeForTests(level, f);
            fresh(t);
            int books = Market.stock(level, t.id(), s -> s.is(Items.ENCHANTED_BOOK) && storedMending(level, s) > 0);
            end.that(books == 1, "the Mending book is in the stores: " + books);
            int[] account = EmeraldTrader.accountForTests(t.id());
            end.that(account[1] == 12 && stock(level, t.id(), Items.EMERALD) - emeraldsBefore == 28, "the account: " + account[1]
                + " spent, the other " + (stock(level, t.id(), Items.EMERALD) - emeraldsBefore) + " emeralds back in the stores");
            end.that(!EmeraldTrader.asks(t.id()).contains("book:minecraft:mending"), "the player's ask is met and comes off the list");
            end.that(newsHas(t.id(), "Mending"), "the town hears what it brought home");
            List<String> deals = pageDeals(EmeraldTrader.report(level, t.v()));
            Kit.log("ep08 the page's offers: " + deals);
            end.that(anyHas(deals, "librarian", "sells Mending (a book) for 12 emeralds and a book", "(11 of 12 left)"),
                "the Trading Post page has the offer as it stands now");
            String after = EmeraldTrader.askForTests(f, you, "Who sells mending?");
            end.that(after.contains("librarian sells Mending (a book) for 12 emeralds and a book"), "asked who sells Mending, it says: " + after);
            // The smith lays it on the town's best tool (its own round may have got there first).
            String laid = EmeraldTrader.layBookForTests(level, t.v(), smith);
            fresh(t);
            int picks = Market.stock(level, t.id(), s -> s.is(Items.DIAMOND_PICKAXE)
                && EnchantmentHelper.getItemEnchantmentLevel(mending(level), s) > 0);
            Kit.log("ep08 laid: " + laid + "; mended picks " + picks);
            end.that(picks == 1, "the diamond pickaxe has Mending on it: " + laid);
            end.that(Market.stock(level, t.id(), s -> s.is(Items.ENCHANTED_BOOK)) == 0, "and the book is used up");
            done(helper, end);
        });
    }

    static int storedMending(ServerLevel level, ItemStack s) {
        var stored = s.getOrDefault(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS,
            net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        return stored.getLevel(mending(level));
    }

    /**
     * What the trader never does. A town with a thin larder (forty wheat, nothing else to eat) and thirty emeralds: no
     * wheat goes out, though a farmer buys it. A farmer's wheat offer used up: no trade on it. A librarian whose Mending has
     * demand on it (twenty-four emeralds, not twelve): no trade while a player is at its stall; none with too few
     * emeralds (nothing for nothing); and one with enough, at the price as it stands, demand and all.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ep09_trader_never")
    public static void ep09_trader_never(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1436000;
        Town t = town(helper, x, 13, Villages.Age.STONE, StationTask.NONE, StationTask.GUARD);
        fill(t, new ItemStack(Items.WHEAT, 40), new ItemStack(Items.EMERALD, 30), new ItemStack(Items.BOOK, 1));
        Kit.hold(level, x + OUT, Z, 40);
        Kit.prepare(level, x + OUT, Z, 40);
        BlockPos bell = bellOnPost(level, x + OUT, Z);
        Villager farmer = atStall(level, Kit.surface(level, x + OUT + 5, Z), Blocks.COMPOSTER, VillagerProfession.FARMER, bell, true,
            new MerchantOffer(new ItemCost(Items.WHEAT, 20), Optional.empty(), new ItemStack(Items.EMERALD), 16, 16, 2, 0.05F));
        Villager librarian = atStall(level, Kit.surface(level, x + OUT - 5, Z), Blocks.LECTERN, VillagerProfession.LIBRARIAN, bell, true,
            mendingOffer(level, 12, 0, 5));
        TwoPeoples.sweepForTests(level);
        VanillaVillages.Known k = VanillaVillages.at(level, bell.getX(), bell.getZ(), 0);
        helper.assertTrue(k != null, "test setup: their village is known by its bell");
        EmeraldTrader.Hamlet h = EmeraldTrader.knowForTests(t.v(), k, level.getDayTime() / 24000L);
        Kit.Expect ex = new Kit.Expect();
        ex.that(Budget.spare(level, t.id(), new ItemStack(Items.WHEAT)) == 0, "a town with a thin larder has no wheat to spare");
        ex.that(EmeraldTrader.reckonForTests(level, t.v()) == 1, "emeralds to spend on what it wants open the trade");
        VillageFolkEntity f = EmeraldTrader.appointForTests(level, t.v());
        helper.assertTrue(f != null, "test setup: a trader");
        ex.that(EmeraldTrader.setOutForTests(f, level, h.key()), "it sets out with the emeralds");
        ex.that(has(f, Items.WHEAT) == 0 && stock(level, t.id(), Items.WHEAT) == 40, "it takes no wheat: the town needs it");
        ex.that(has(f, Items.EMERALD) == 30 && has(f, Items.BOOK) == 1, "it carries the emeralds and the book: " + has(f, Items.EMERALD) + ", " + has(f, Items.BOOK));
        // A used-up offer.
        MerchantOffer used = farmer.getOffers().get(0);
        f.insertGiven(new ItemStack(Items.WHEAT, 20));
        ex.that(used.isOutOfStock() && !EmeraldTrader.tradeForTests(level, f, farmer, 0), "no trade on an offer that is used up");
        ex.that(used.getUses() == 16 && has(f, Items.WHEAT) == 20 && has(f, Items.EMERALD) == 30, "nothing changed hands");
        f.removeMatching(s -> s.is(Items.WHEAT), 20);
        // The price as it stands: twelve, and twelve more for the demand on it.
        MerchantOffer m = librarian.getOffers().get(0);
        ex.that(m.getCostA().getCount() == 24, "the librarian's Mending costs " + m.getCostA().getCount() + " with the demand on it");
        // A player at the librarian's stall.
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        librarian.setTradingPlayer(you);
        ex.that(!EmeraldTrader.tradeForTests(level, f, librarian, 0) && m.getUses() == 0 && has(f, Items.EMERALD) == 30,
            "no trade with a villager a player is trading with");
        librarian.setTradingPlayer(null);
        // Too few emeralds: nothing for nothing.
        f.removeMatching(s -> s.is(Items.EMERALD), 20);
        ex.that(!EmeraldTrader.tradeForTests(level, f, librarian, 0) && m.getUses() == 0 && has(f, Items.EMERALD) == 10 && has(f, Items.BOOK) == 1,
            "with ten emeralds it takes nothing, and pays nothing");
        // Enough: the whole price.
        f.insertGiven(new ItemStack(Items.EMERALD, 20));
        boolean traded = EmeraldTrader.tradeForTests(level, f, librarian, 0);
        int got = CaveDwellerGameTests.has(f, s -> s.is(Items.ENCHANTED_BOOK) && storedMending(level, s) > 0);
        ex.that(traded && has(f, Items.EMERALD) == 6 && has(f, Items.BOOK) == 0 && got == 1,
            "with thirty it pays the twenty-four and the book, and has the Mending book: " + has(f, Items.EMERALD) + " left, " + got + " book");
        ex.that(m.getUses() == 1 && librarian.getVillagerXp() == 1, "the offer is used once, the librarian has its XP");
        Scouts.abandon(level, f);
        done(helper, ex);
    }

    /**
     * A raided village. Pillagers about a village of villagers when the trader arrives: it turns for home at once without
     * a trade, the book marks the village raided, the town is told (the news, the log, the board), and the farmer's
     * offers are untouched. The goods come home. Sent out again, it does not go back there while the raid is fresh: it
     * goes looking for another village instead.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ep10_raided_village_left_alone")
    public static void ep10_raided_village_left_alone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1438000;
        Town t = town(helper, x, 13, Villages.Age.STONE, StationTask.NONE, StationTask.GUARD, StationTask.GUARD, StationTask.FARM);
        surplus(t);
        Kit.hold(level, x + OUT, Z, 40);
        Kit.prepare(level, x + OUT, Z, 40);
        BlockPos bell = bellOnPost(level, x + OUT, Z);
        Villager farmer = atStall(level, Kit.surface(level, x + OUT + 5, Z), Blocks.COMPOSTER, VillagerProfession.FARMER, bell, true,
            buys(Items.WHEAT, 20, 16, 2), buys(Items.POTATO, 26, 16, 2));
        // A pillager at the far side of the village, out of the trader's way (it is not hunting anybody: held still).
        BlockPos at = Kit.surface(level, x + OUT + 12, Z - 12);
        Pillager raider = EntityType.PILLAGER.create(level);
        raider.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        raider.setNoAi(true);
        raider.setPersistenceRequired();
        level.addFreshEntity(raider);
        helper.assertTrue(Kit.live(level, x + OUT, Z, 40), "test setup: the ground is live");
        TwoPeoples.sweepForTests(level);
        VanillaVillages.Known k = VanillaVillages.at(level, bell.getX(), bell.getZ(), 0);
        helper.assertTrue(k != null, "test setup: their village is known by its bell");
        long day = level.getDayTime() / 24000L;
        EmeraldTrader.Hamlet h = EmeraldTrader.knowForTests(t.v(), k, day);
        helper.assertTrue(EmeraldTrader.reckonForTests(level, t.v()) == 1, "test setup: the trade is open");
        VillageFolkEntity f = EmeraldTrader.appointForTests(level, t.v());
        helper.assertTrue(f != null && EmeraldTrader.setOutForTests(f, level, h.key()), "test setup: the trader sets out");
        final int wheat = has(f, Items.WHEAT);
        EmeraldTrader.arriveForTests(level, f);
        final boolean[] home = { false };
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            EmeraldTrader.Venture now = EmeraldTrader.ventureForTests(f);
            if (home[0] || now == null || now.stage() != EmeraldTrader.Stage.HOME) return;
            home[0] = true;
            Kit.Expect end = new Kit.Expect();
            Kit.log("ep10 @" + tick + " turned for home: " + f.debugLine());
            end.that(now.trades() == 0 && uses(farmer, 0) == 0 && farmer.getVillagerXp() == 0, "not a trade at a raided village");
            EmeraldTrader.Hamlet hb = booked(t.id(), k.key());
            end.that(hb != null && hb.raided() == day, "the book marks the village raided on day " + day + ": " + (hb == null ? "?" : hb.raided()));
            end.that(newsHas(t.id(), "pillagers raiding") && logHas(t.id(), "pillagers"), "the town is told");
            String board = EmeraldTrader.boardLine(t.id());
            end.that(board != null && board.contains("pillagers were raiding"), "the board has it: " + board);
            end.that(has(f, Items.WHEAT) == wheat, "the goods are still in its pack");
            end.that(raider.isAlive() && raider.getHealth() >= raider.getMaxHealth() && farmer.isAlive(),
                "it went to war with nobody, and nobody with it");
            int wheatBefore = stock(level, t.id(), Items.WHEAT);
            EmeraldTrader.homeForTests(level, f);
            fresh(t);
            end.that(stock(level, t.id(), Items.WHEAT) - wheatBefore == wheat, "home, the wheat goes back into the stores: "
                + (stock(level, t.id(), Items.WHEAT) - wheatBefore) + " of " + wheat);
            end.that(EmeraldTrader.accountForTests(t.id())[0] == 0, "nothing earned");
            // Out again the same day: not back to the raided village.
            end.that(EmeraldTrader.setOutForTests(f, level, null), "sent out again");
            EmeraldTrader.Venture again = EmeraldTrader.ventureForTests(f);
            end.that(again != null && again.hamlet() == null && again.purpose() == EmeraldTrader.Venture.Purpose.EXPLORE,
                "it goes looking for another village, not back to the raided one: " + (again == null ? "?" : again.purpose() + " " + again.hamlet()));
            Scouts.abandon(level, f);
            done(helper, end);
        });
    }
}
