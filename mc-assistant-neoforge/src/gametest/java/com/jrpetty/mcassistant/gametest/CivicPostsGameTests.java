package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.client.OutfitIndex;
import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Beliefs;
import com.jrpetty.mcassistant.entity.Graves;
import com.jrpetty.mcassistant.entity.Healers;
import com.jrpetty.mcassistant.entity.Health;
import com.jrpetty.mcassistant.entity.Infirmary;
import com.jrpetty.mcassistant.entity.JobWorth;
import com.jrpetty.mcassistant.entity.Lamplighter;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Offices;
import com.jrpetty.mcassistant.entity.Sexton;
import com.jrpetty.mcassistant.entity.ShiftRole;
import com.jrpetty.mcassistant.entity.Shifts;
import com.jrpetty.mcassistant.entity.TownJobs;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.Weave;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [roles-a] The town's civic, care and faith posts (Healers, Offices, Sexton, Lamplighter, OutfitIndex).
 *
 * <ul>
 * <li><b>cp01</b>: a Stone Age town with its infirmary wants a healer (one to forty folk, three at most) and takes the
 *     hand it can spare; the healer is paid as a healer and dresses for it. Sent to a patient laid up at home it walks
 *     there and sees to it at the bedside with the stores' honey (the honey out, the bottle back); with the stores bare
 *     it sits with the next one all the same. It is at a birth (the chronicle, the parents gladder). A hurt player is
 *     seen to for a coin with the stores' golden carrot. Worst case: the healer dead, the town's care crew sees to the
 *     next patient as before.</li>
 * <li><b>cp02</b>: a town of eight with a chapel has its sexton: it leads the rites in the elder's place, its office's
 *     allowance is on its wage and in the breakdown; it sets a candle from the stores on the altar and lights it at dusk,
 *     snuffs it in the morning; a folk dies and, the headstone set, the sexton lays it to rest in the chronicle.</li>
 * <li><b>cp03</b>: the lamplighter: a Wood Age town's street torch on its post is doused in the morning (kept in the
 *     books, counted as lit), lit again before dusk; doused again the next morning and the round never comes, the night
 *     lights it at once.</li>
 * <li><b>cp04</b>: every trade has an outfit picture of its own, the banker, the ferryman and the healer their own at
 *     last; every trade has words for a witness; the operator's "people trade heal" finds the healer.</li>
 * <li><b>cp05</b>: a player's paid shift as the healer's assistant: a patient marked, seen to at its bedside with the
 *     stores' honey in the player's name, and the task paid.</li>
 * </ul>
 * Band x 1,980,000 to 1,999,999, z 66,000; a batch each.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CivicPostsGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    private static final long DAY = 24000L * 4;

    // ------------------------------------------------------------------ helpers

    /** Flat grass round here, clear air above it. Returns the ground's top (the first air). */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        BlockPos mid = Kit.surface(level, cx, cz);
        int y = mid.getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** A clean slate and a village of this many folk on flat ground at x, in clear weather, at this time. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, int x, long time, int folk) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Health.resetForTests();
        Infirmary.resetForTests();
        Shifts.resetForTests();
        level.setDayTime(time);
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, Z, 44);
        Kit.prepare(level, x, Z, 44);
        BlockPos heart = flat(level, x, Z, 36);
        List<VillageFolkEntity> out = new ArrayList<>();
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null && first.ownerId() != null, "a village");
        out.add(first);
        for (int i = 1; i < folk; i++) {
            BlockPos at = Kit.surface(level, x + 2 + (i % 5), Z + 2 + i / 5);
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
            helper.assertTrue(f != null && first.ownerId().equals(f.ownerId()), "folk " + i + " of the village");
            f.setAgeForTests(30 + i);
            f.insertItem(new ItemStack(Items.COOKED_BEEF, 16));
            out.add(f);
        }
        return out;
    }

    private static BlockPos stamp(ServerLevel level, UUID village, String structure, BlockPos at) {
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        BuildGoal.stamp(level, structure, p, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, structure, p, Direction.NORTH);
        return p;
    }

    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        return box;
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        Villages.forgetStock();
        return Market.stock(level, village, what);
    }

    private static void moveTo(VillageFolkEntity f, BlockPos p) {
        f.getNavigation().stop();
        f.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.0F, 0.0F);
    }

    private static boolean told(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains(words)) return true;
        return false;
    }

    private static String history(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Chronicle.Entry e : Chronicle.of(village)) sb.append(" | ").append(e.text());
        return sb.toString();
    }

    // ============================================================ cp01: the healer

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cp01_healer")
    public static void cp01_healer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1980000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, 7);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            boolean instant = TownJobs.instantNow();
            Health.roundsOffForTests(true);
            try {
                Villages.ageForTests(id, Villages.Age.STONE);
                helper.assertTrue(Healers.wanted(id) == 0, "no healer before the infirmary stands");
                stamp(level, id, Infirmary.STRUCTURE, heart.offset(0, 0, -16));
                helper.assertTrue(Healers.wanted(id) == 1, "one healer once the infirmary stands: " + Healers.wanted(id));
                helper.assertTrue(Healers.wantedAt(20) == 1 && Healers.wantedAt(41) == 2 && Healers.wantedAt(81) == 3 && Healers.wantedAt(400) == 3,
                    "one to every forty, three at most");
                for (VillageFolkEntity f : folk) if (!f.isElder()) f.setJob(StationTask.NONE);
                Healers.fillForTests(level, v);
                List<VillageFolkEntity> hs = Healers.healers(id);
                helper.assertTrue(hs.size() == 1, "a healer appointed: " + hs.size());
                VillageFolkEntity healer = hs.get(0);
                JobWorth.Pay pay = JobWorth.payOf(healer);
                Kit.log("cp01 healer " + healer.displayNameCap() + ": " + Wealth.breakdown(healer) + "; dresses as '" + Weave.roleForTests(healer) + "'");
                helper.assertTrue(healer.stationTask() == StationTask.HEAL && !healer.isElder(), "the hand the town could spare took it up");
                helper.assertTrue(pay.post().key.equals("HEAL") && Wealth.wage(healer) > 0, "paid as a healer: " + pay.post().key);
                helper.assertTrue(Weave.roleForTests(healer).equals("healer"), "dresses for the office");

                // A patient laid up at home, the healer across the town: sent, and seen to when it is at the bedside.
                VillageFolkEntity p = folk.get(folk.size() - 1) == healer ? folk.get(folk.size() - 2) : folk.get(folk.size() - 1);
                chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.HONEY_BOTTLE));
                int honey = stock(level, id, s -> s.is(Items.HONEY_BOTTLE)), bottles = stock(level, id, s -> s.is(Items.GLASS_BOTTLE));
                Health.catchForTests(p, "caught out in the rain");
                Health.patientForTests(p, 6000L, "cold");
                moveTo(healer, heart.offset(20, 0, 20));
                Kit.log("cp01 the healer before its round: '" + Healers.unfitForTests(healer) + "'");
                VillageFolkEntity first = Health.careForTests(level, id);
                Kit.log("cp01 first call: seen " + (first == null ? "nobody" : first.displayNameCap()) + ", the healer going to " + Healers.goingTo(healer)
                    + " (the patient " + p.getUUID() + "), " + String.format("%.1f", healer.distanceTo(p)) + " blocks off; lying " + Health.lyingForTests(p)
                    + "; " + Health.tendedForTests(p));
                helper.assertTrue(first == null && p.getUUID().equals(Healers.goingTo(healer)), "the healer sent to the bedside first");
                moveTo(healer, p.blockPosition().east());
                int cold0 = p.health().coldLeft();
                VillageFolkEntity seen = Health.careForTests(level, id);
                Kit.log("cp01 at the bedside: " + Health.tendedForTests(p) + "; cold " + cold0 + " -> " + p.health().coldLeft());
                helper.assertTrue(seen == p && Health.tendedForTests(p).startsWith(healer.displayNameCap()) && Health.tendedForTests(p).contains("honey"),
                    "the healer at the bedside, with the stores' honey: " + Health.tendedForTests(p));
                helper.assertTrue(stock(level, id, s -> s.is(Items.HONEY_BOTTLE)) == honey - 1 && stock(level, id, s -> s.is(Items.GLASS_BOTTLE)) == bottles + 1,
                    "the honey out of the stores and the bottle back");
                helper.assertTrue(p.health().coldLeft() < cold0 && healer.deedCount(AssistantEntity.Deed.FOLK_HEALED) == 1, "its cold eased; one seen to");

                Health.layUpForTests(p, 0L, "cold");                      // up and about again: off the round
                // The stores bare of remedies: company all the same.
                VillageFolkEntity q = folk.get(1) == healer ? folk.get(2) : folk.get(1);
                Health.catchForTests(q, "worn out at its work");
                Health.patientForTests(q, 6000L, "cold");
                moveTo(healer, q.blockPosition().west());
                int remedies = stock(level, id, Health::isRemedy);
                VillageFolkEntity again = Health.careForTests(level, id);
                Kit.log("cp01 bare stores (" + remedies + " remedies left): " + Health.tendedForTests(q));
                helper.assertTrue(again == q && (remedies > 0 || Health.tendedForTests(q).contains("rest and company")),
                    "seen to with company when the stores have nothing: " + Health.tendedForTests(q));

                Health.layUpForTests(q, 0L, "cold");
                // A birth: the healer at it.
                VillageFolkEntity mother = folk.get(3) == healer ? folk.get(4) : folk.get(3), father = folk.get(5) == healer ? folk.get(6) : folk.get(5);
                Healers.attendBirthForTests(level, mother, father, q);
                List<Object[]> why = new ArrayList<>();
                int lift = Healers.mood(mother, level.getDayTime() / 24000L, 0, why);
                helper.assertTrue(told(id, healer.displayNameCap() + " the healer saw") && lift > 0, "the healer at the birth: " + history(id) + "; mood +" + lift);

                // A hurt player, seen to for a coin with the stores' golden carrot.
                chestAt(level, heart.offset(-3, 0, -3), new ItemStack(Items.GOLDEN_CARROT));
                ServerPlayer pl = helper.makeMockServerPlayerInLevel();
                pl.teleportTo(healer.getX() + 1, healer.getY(), healer.getZ());
                pl.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 20));
                pl.setHealth(8.0F);
                int coins = Market.coinsHeld(pl), carrots = stock(level, id, s -> s.is(Items.GOLDEN_CARROT));
                String said = Healers.seeTo(healer, pl);
                Kit.log("cp01 the player: '" + said + "'; health " + pl.getHealth() + ", coins " + coins + " -> " + Market.coinsHeld(pl));
                helper.assertTrue(pl.getHealth() > 8.0F && Market.coinsHeld(pl) < coins, "the player seen to, and paid for it");
                helper.assertTrue(stock(level, id, s -> s.is(Items.GOLDEN_CARROT)) == carrots - 1, "the carrot out of the stores");
                Kit.noLeftoverPlayers(level);

                // Worst case: the healer dies; the town's care crew sees to the next patient as it always did.
                healer.kill();
                VillageFolkEntity r = folk.get(2) == healer || folk.get(2) == q ? folk.get(4) : folk.get(2);
                Health.catchForTests(r, "caught out in the rain");
                Health.patientForTests(r, 6000L, "cold");
                TownJobs.instantForTests(true);
                VillageFolkEntity crew = Health.careForTests(level, id);
                Kit.log("cp01 no healer: " + (crew == null ? "nobody" : crew.displayNameCap()) + " — " + Health.tendedForTests(r));
                helper.assertTrue(crew == r && !Health.tendedForTests(r).startsWith(healer.displayNameCap()), "the care crew sees to it");
            } finally {
                TownJobs.instantForTests(instant);                         // as it was (other tests lean on it)
                Health.roundsOffForTests(false);
            }
            helper.succeed();
        });
    }

    // ============================================================ cp02: the sexton

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cp02_sexton")
    public static void cp02_sexton(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1984000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 11500, 9);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            boolean instant = TownJobs.instantNow();
            try {
                helper.assertTrue(Sexton.holder(id) == null, "no sexton without a chapel");
                stamp(level, id, "chapel", heart.offset(-16, 0, -18));
                Sexton.resetForTests();
                Offices.forgetForTests(id);
                VillageFolkEntity sx = Sexton.holder(id);
                helper.assertTrue(sx != null && !sx.isElder(), "a sexton for the chapel");
                sx.setJob(StationTask.FARM);                                  // it keeps a trade of its own: the office is on top
                Offices.forgetForTests(id);
                helper.assertTrue(Beliefs.leaderForTests(id) == sx, "the sexton leads the rites in the elder's place");
                JobWorth.Pay pay = JobWorth.payOf(sx);
                String breakdown = Wealth.breakdown(sx);
                Kit.log("cp02 sexton " + sx.displayNameCap() + ": offices " + Offices.of(sx) + "; " + breakdown);
                helper.assertTrue(Offices.of(sx).contains(Offices.Office.SEXTON) && pay.office() >= 1 && breakdown.contains("as the sexton"),
                    "its office's allowance on its wage, and in the breakdown: " + breakdown);
                helper.assertTrue(pay.total() == pay.worthPart() + pay.teaching() + pay.elder() + pay.office() + pay.deeds(), "in the total");

                // The altar's candle: set from the stores and lit at dusk, snuffed in the morning.
                chestAt(level, heart.offset(3, 0, 3), new ItemStack(Items.CANDLE, 2));
                int candles = stock(level, id, s -> s.is(Items.CANDLE));
                TownJobs.instantForTests(true);
                boolean lit = Sexton.candlesForTests(level, v);
                BlockPos c = Sexton.candleForTests(level, id);
                helper.assertTrue(lit && c != null && level.getBlockState(c).getValue(CandleBlock.LIT), "a candle on the altar, lit at dusk");
                helper.assertTrue(stock(level, id, s -> s.is(Items.CANDLE)) == candles - 1, "out of the stores");
                level.setDayTime(level.getDayTime() / 24000L * 24000L + 24000L + 2000L);
                boolean snuffed = Sexton.candlesForTests(level, v);
                helper.assertTrue(snuffed && !level.getBlockState(c).getValue(CandleBlock.LIT), "snuffed in the morning");

                // A death, its grave dug and its stone set: the sexton lays it to rest.
                BlockPos yard = stamp(level, id, "graveyard", heart.offset(16, 0, -16));
                chestAt(level, heart.offset(-3, 0, 3), new ItemStack(Items.COBBLESTONE, 16), new ItemStack(Items.OAK_SIGN, 4));
                VillageFolkEntity gone = folk.get(folk.size() - 1) == sx ? folk.get(folk.size() - 2) : folk.get(folk.size() - 1);
                String name = gone.displayNameCap();
                gone.kill();
                moveTo(sx, yard.east(2));
                int stones = Graves.tend(level, id);
                Kit.log("cp02 the grave: " + stones + " stone(s); " + history(id));
                helper.assertTrue(stones >= 1, "the headstone set");
                helper.assertTrue(told(id, sx.displayNameCap() + " the sexton laid " + name + " to rest"), "the sexton laid it to rest: " + history(id));
            } finally {
                TownJobs.instantForTests(instant);                         // as it was (other tests lean on it)
            }
            helper.succeed();
        });
    }

    // ============================================================ cp03: the lamplighter

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cp03_lamplighter")
    public static void cp03_lamplighter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1988000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 1500, 6);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        helper.runAtTickTime(5, () -> {
            boolean instant = TownJobs.instantNow();
            try {
                Villages.ageForTests(id, Villages.Age.WOOD);
                int[] spot = Lamplighter.spotForTests(v);
                helper.assertTrue(spot != null, "a lamp spot in the town's plan");
                BlockPos ground = Kit.surface(level, v.centre().getX() + spot[0], v.centre().getZ() + spot[1]);
                BlockPos fence = ground;
                level.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState(), 3);
                level.setBlock(fence.above(), Blocks.TORCH.defaultBlockState(), 3);
                TownJobs.instantForTests(true);
                long day = level.getDayTime() / 24000L;
                level.setDayTime(day * 24000L + 1500L);
                boolean doused = Lamplighter.roundForTests(level, v);
                Kit.log("cp03 morning at " + fence.toShortString() + ": doused " + doused + ", the books " + Lamplighter.dousedForTests(id));
                helper.assertTrue(doused && level.getBlockState(fence.above()).isAir() && Lamplighter.dousedForTests(id).contains(fence),
                    "the torch taken down off its post in the morning, and kept in the books");
                helper.assertTrue(Lamplighter.dousedNear(id, fence.east(3)), "the street by it counted lit for the day");
                level.setDayTime(day * 24000L + 11500L);
                boolean lit = Lamplighter.roundForTests(level, v);
                helper.assertTrue(lit && level.getBlockState(fence.above()).is(Blocks.TORCH) && Lamplighter.dousedForTests(id).isEmpty(),
                    "lit again before dusk");
                // Worst case: doused the next morning, and the round never comes: the night lights it at once.
                level.setDayTime((day + 1) * 24000L + 1500L);
                helper.assertTrue(Lamplighter.roundForTests(level, v) && level.getBlockState(fence.above()).isAir(), "doused the next morning");
                level.setDayTime((day + 1) * 24000L + 13000L);
                Lamplighter.roundForTests(level, v);
                helper.assertTrue(level.getBlockState(fence.above()).is(Blocks.TORCH) && Lamplighter.dousedForTests(id).isEmpty(),
                    "never dark at night: lit at once when the round has not come");
                helper.assertTrue(level.getBlockState(fence).is(BlockTags.FENCES), "its post where it was");
            } finally {
                TownJobs.instantForTests(instant);                         // as it was (other tests lean on it)
            }
            helper.succeed();
        });
    }

    // ============================================================ cp04: every trade's clothes

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "cp04_outfits")
    public static void cp04_outfits(GameTestHelper helper) {
        helper.runAtTickTime(2, () -> {
            List<String> plain = new ArrayList<>(), missing = new ArrayList<>();
            for (StationTask t : StationTask.values()) {
                if (t == StationTask.NONE) continue;
                int o = OutfitIndex.of(t.ordinal());
                helper.assertTrue(o >= 0 && o < OutfitIndex.NAMES.length && !OutfitIndex.NAMES[o].equals("none"), t + " wears an outfit of its own: " + o);
                if (Offices.clothesForTests(t).equals("plain clothes")) plain.add(t.name());
            }
            for (String n : OutfitIndex.NAMES) {
                if (CivicPostsGameTests.class.getResource("/assets/mc_assistant/textures/entity/folk/" + n + ".png") == null) missing.add(n);
            }
            helper.assertTrue(missing.isEmpty(), "every outfit has its picture: missing " + missing);
            helper.assertTrue(OutfitIndex.NAMES[OutfitIndex.of(StationTask.BANK.ordinal())].equals("banker")
                && OutfitIndex.NAMES[OutfitIndex.of(StationTask.FERRY.ordinal())].equals("ferryman")
                && OutfitIndex.NAMES[OutfitIndex.of(StationTask.HEAL.ordinal())].equals("healer"),
                "the banker, the ferryman and the healer in their own clothes");
            helper.assertTrue(plain.isEmpty(), "a witness has words for every trade's clothes: " + plain);
            helper.assertTrue(AdminTools.trade("heal") == StationTask.HEAL && AdminTools.trade("healer") == StationTask.HEAL,
                "the operator's \"people trade heal\" finds the healer");
            helper.assertTrue(ShiftRole.of("heal") == ShiftRole.HEALER, "the healer's assistant is a shift");
            helper.succeed();
        });
    }

    // ============================================================ cp05: a shift as the healer's assistant

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "cp05_healer_shift")
    public static void cp05_healer_shift(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1992000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 3000, 5);
        UUID id = folk.get(0).ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            Health.roundsOffForTests(true);
            try {
                Villages.ageForTests(id, Villages.Age.STONE);
                stamp(level, id, Infirmary.STRUCTURE, heart.offset(0, 0, -16));
                Ledger.addCoins(id, 500);
                chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.HONEY_BOTTLE, 2));
                VillageFolkEntity p = folk.get(folk.size() - 1);
                Health.catchForTests(p, "caught out in the rain");
                Health.patientForTests(p, 6000L, "cold");
                ServerPlayer pl = helper.makeMockServerPlayerInLevel();
                pl.getAbilities().instabuild = false;
                pl.teleportTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 4.5);
                String took = Shifts.take(pl, id, ShiftRole.HEALER.id, false);
                Shifts.secondForTests(level, pl);
                String task = Shifts.taskForTests(pl);
                int honey = stock(level, id, s -> s.is(Items.HONEY_BOTTLE));
                pl.teleportTo(p.getX() + 1.0, p.getY(), p.getZ());
                Shifts.secondForTests(level, pl);
                int[] st = Shifts.stateForTests(pl);
                Kit.log("cp05 took '" + took + "'; task '" + task + "'; " + Health.tendedForTests(p) + "; state " + java.util.Arrays.toString(st)
                    + "; " + Shifts.lastForTests(pl));
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(took.isEmpty() && task != null && task.contains(p.displayNameCap()), "a patient to see to: " + took + " / " + task);
                helper.assertTrue(Health.tendedForTests(p).startsWith(pl.getName().getString()) && Health.tendedForTests(p).contains("honey"),
                    "seen to in the player's name with the stores' honey: " + Health.tendedForTests(p));
                helper.assertTrue(stock(level, id, s -> s.is(Items.HONEY_BOTTLE)) == honey - 1, "the honey out of the stores");
                helper.assertTrue(st != null && st[0] >= 1, "the task done and paid");
            } finally {
                Health.roundsOffForTests(false);
            }
            helper.succeed();
        });
    }
}
