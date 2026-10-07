package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.BigWorks;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Newcomers;
import com.jrpetty.mcassistant.entity.Referendums;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wars;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [civic] Referendums on the great works, and newcomers and refugees (entity/Referendums, BigWorks, WorksPlans,
 * Newcomers).
 *
 * <ul>
 * <li><b>civ01</b>: a river cuts the town off on its east side. The leader puts a stone bridge over it to the town,
 *     with its cost from the stores; folk walk to the board to vote; the Visionaries say aye and the thrifty
 *     Traditionalists nay, each by what it cares about; a citizen player votes too, and the count is every ballot,
 *     once. Carried: on the works day most of the town turns out, the bridge goes up out of the stores' stone bricks
 *     and lanterns with many hands laying it (piers, deck, parapets), the stores pay what it cost, the ribbon is made
 *     of the stores' string and dye and strung, and cut at the opening.</li>
 * <li><b>civ02</b>: the same, put to a thrifty town: voted down, the bridge waits a season (seven days) before the town
 *     may be asked again, and is asked again once the season is out.</li>
 * <li><b>civ03</b>: a town worn out by its war sends a family (a smith and a brewer) to the nearest town at peace: off
 *     its old town's roll and nobody's on the road; camped at the new town's edge, they ask to settle ("from Ashford,
 *     fleeing the war, ask to settle"); a town with room and food and a welcoming nature votes them in, and they are
 *     its folk, at the trades they bring, and the town they left thinks the better of it.</li>
 * <li><b>civ04</b>: the same family at a town full to its beds and in famine: even its generous folk vote nay; turned
 *     away, with nowhere else at peace to go, they set out for home, on no town's roll, and the town they left thinks
 *     the worse of the one that sent them on.</li>
 * <li><b>civ05</b>: the works fit the land: an aqueduct from a lake over a knoll keeps its channel level and high
 *     enough to walk under, its piers down to the ground, and is built with water running in it; one side of the town
 *     wall follows the ground over a mound and leaves a gateway where a path goes through.</li>
 * <li><b>civ06</b>: a family of three from outside (a couple and a child), the grown each bringing a trade the town has
 *     nobody at, a few years at it; nobody's on the road; voted in, the town's folk.</li>
 * </ul>
 *
 * <p>Each runs on its own ground in the band x 1,080,000 to 1,099,999, z 66,000, in a batch of its own, calling the
 * town's logic directly where it can and logging what it sees ("civNN ...").
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CivicGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ---------------------------------------------------------------- ground, water and stores

    /** Flat grass round here, clear air above it. Returns the ground's walking level at the middle. */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        int y = Kit.surface(level, cx, cz).getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -4; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 16; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** Water from x0 to x1, z0 to z1, its surface a block under the grass, three deep on gravel. */
    private static void water(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                level.setBlock(new BlockPos(x, y - 1, z), Blocks.AIR.defaultBlockState(), 2 | 16);
                for (int dy = 2; dy <= 4; dy++) level.setBlock(new BlockPos(x, y - dy, z), Blocks.WATER.defaultBlockState(), 2 | 16);
                level.setBlock(new BlockPos(x, y - 5, z), Blocks.GRAVEL.defaultBlockState(), 2 | 16);
            }
        }
    }

    /** A marked store chest at this spot, filled with these. */
    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        return box;
    }

    private static List<VillageFolkEntity> raise(GameTestHelper helper, ServerLevel level, BlockPos heart, int n) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.offset(-6 + (i % 5) * 3, 0, 4 + (i / 5) * 3), 0.0F);
            helper.assertTrue(f != null, "a folk of the town (" + i + ")");
            f.ensurePersona();
            out.add(f);
        }
        return out;
    }

    private static String chronicle(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Chronicle.Entry e : Chronicle.of(village)) sb.append(e.text()).append(" | ");
        return sb.toString();
    }

    private static boolean onRoll(UUID village, VillageFolkEntity f) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a == f) return true;
        return false;
    }

    /** A Visionary through and through (every great work is the town going forward), or a thrifty Traditionalist. */
    private static void visionary(VillageFolkEntity f) {
        Values.setForTests(f, Values.Value.PROGRESS, 95);
        Values.setForTests(f, Values.Value.TRADITION, 5);
    }

    private static void thrifty(VillageFolkEntity f) {
        Values.setForTests(f, Values.Value.TRADITION, 95);
        Values.setForTests(f, Values.Value.PROGRESS, 5);
        Values.setForTests(f, Values.Value.HOMES, 5);
        Values.setForTests(f, Values.Value.WEALTH, 5);
    }

    /** A town of {@code n} on flat ground with a river seven wide down its east side, its fields to the west, in the Stone Age. */
    private static Villages.Village riverTown(GameTestHelper helper, ServerLevel level, int cx, int n, List<VillageFolkEntity> folk) {
        Kit.hold(level, cx, Z, 64);
        Kit.prepare(level, cx, Z, 64);
        BlockPos heart = flat(level, cx, Z, 56);
        water(level, cx + 23, cx + 29, Z - 48, Z + 48, heart.getY());
        folk.addAll(raise(helper, level, heart, n));
        UUID id = folk.get(0).ownerId();
        helper.assertTrue(id != null, "a town");
        Villages.ageForTests(id, Villages.Age.STONE);
        Villages.setFieldsSide(id, TownPlan.WEST);
        long day = level.getDayTime() / 24000L;
        Leader.booksForTests(id, new Leader.Books(400, 40, 20, 40, 20, 20.0, Leader.Plan.PLENTY, day));
        return Villages.get(id);
    }

    // ============================================================ civ01: the bridge, voted and built together

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "civ01_bridge")
    public static void civ01_bridge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setWeatherParameters(12000, 0, false, false);
        final int cx = 1080000;
        long day = level.getDayTime() / 24000L + 3;
        level.setDayTime(day * 24000L + 3000L);
        List<VillageFolkEntity> folk = new ArrayList<>();
        Villages.Village v = riverTown(helper, level, cx, 10, folk);
        UUID id = v.id();
        BlockPos heart = v.centre();
        Container stores = chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
            new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
            new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.LANTERN, 8), new ItemStack(Items.STRING, 2), new ItemStack(Items.RED_DYE, 1));
        int bricksBefore = Market.stock(level, id, s -> s.is(Items.STONE_BRICKS));
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        Ledger.addCitizen(id, you.getUUID(), "Tamsin");
        int[] q = { -1 };
        List<VillageFolkEntity> ayes = new ArrayList<>(), nays = new ArrayList<>();
        helper.runAtTickTime(20, () -> {
            List<String> proposals = BigWorks.proposalsForTests(level, v);
            Kit.log("civ01 the town could put: " + proposals);
            q[0] = Referendums.callForTests(level, v, BigWorks.Work.BRIDGE, day);
            helper.assertTrue(q[0] > 0, "a bridge put to the town: " + proposals);
            List<String> board = Referendums.board(level, id);
            Kit.log("civ01 the board: " + board);
            helper.assertTrue(board.stream().anyMatch(l -> l.contains("VOTE TODAY") && l.contains("stone bridge")), "the board calls the town to vote: " + board);
            helper.assertTrue(board.stream().anyMatch(l -> l.contains("costs") && l.contains("stone bricks") && l.contains("lantern")),
                "with its cost from the stores: " + board);
            // Six for the town going forward (the one who put it among them), four thrifty folk who count the cost.
            UUID by = Referendums.callerForTests(id, q[0]);
            List<VillageFolkEntity> order = new ArrayList<>(folk);
            order.sort((a, b) -> a.getUUID().equals(by) ? -1 : b.getUUID().equals(by) ? 1 : 0);
            for (int i = 0; i < order.size(); i++) {
                VillageFolkEntity f = order.get(i);
                if (i < 6) { visionary(f); ayes.add(f); } else { thrifty(f); nays.add(f); }
            }
            for (VillageFolkEntity f : ayes) {
                String j = Referendums.judgeForTests(level, f, id, q[0]);
                Kit.log("civ01 " + f.displayNameCap() + " (" + Values.brief(f) + "): " + j);
                helper.assertTrue(j.startsWith("aye"), f.displayNameCap() + ", a Visionary, would say aye: " + j);
            }
            for (VillageFolkEntity f : nays) {
                String j = Referendums.judgeForTests(level, f, id, q[0]);
                Kit.log("civ01 " + f.displayNameCap() + " (" + Values.brief(f) + "): " + j);
                helper.assertTrue(j.startsWith("nay"), f.displayNameCap() + ", thrifty, would say nay: " + j);
            }
            String said = Referendums.playerVote(level, v, you, true);
            Kit.log("civ01 the citizen votes: " + said);
            helper.assertTrue(said.startsWith("Your vote is in"), "a citizen has a vote: " + said);
            // The polls: late in the day every folk's hour has come, and they walk to the board.
            level.setDayTime(day * 24000L + 10300L);
        });
        helper.runAtTickTime(560, () -> {
            int walked = 0;
            for (VillageFolkEntity f : folk) if (Referendums.ballotForTests(id, q[0], f.getUUID())[2]) walked++;
            Kit.log("civ01 walked to the board to vote: " + walked + " of " + folk.size());
            helper.assertTrue(walked >= 1, "folk walk to the board to vote: " + walked);
            Referendums.castAllForTests(level, v, q[0]);
            for (VillageFolkEntity f : ayes) {
                boolean[] b = Referendums.ballotForTests(id, q[0], f.getUUID());
                helper.assertTrue(b[0] && b[1], f.displayNameCap() + " voted aye");
            }
            for (VillageFolkEntity f : nays) {
                boolean[] b = Referendums.ballotForTests(id, q[0], f.getUUID());
                helper.assertTrue(b[0] && !b[1], f.displayNameCap() + " voted nay");
            }
            int[] count = Referendums.countForTests(level, v, q[0]);
            Kit.log("civ01 the count: aye " + count[0] + ", nay " + count[1] + (count[2] == 1 ? ", carried" : ", lost"));
            helper.assertTrue(count[0] == ayes.size() + 1 && count[1] == nays.size(), "every ballot counted once, and the citizen's: "
                + count[0] + "/" + count[1]);
            helper.assertTrue(count[2] == 1, "carried");
            String state = BigWorks.stateForTests(id);
            helper.assertTrue(state.startsWith("BRIDGE|building"), "the bridge to be built: " + state);
            // The works day: the morning after.
            level.setDayTime((day + 1) * 24000L + 2500L);
            List<VillageFolkEntity> hands = BigWorks.helpersForTests(level, id);
            Kit.log("civ01 the works day: " + hands.size() + " of " + folk.size() + " turn out");
            helper.assertTrue(hands.size() >= 6, "most of the town turns out on the works day: " + hands.size());
            Map<String, Integer> laid = BigWorks.buildForTests(level, v, hands, 400);
            state = BigWorks.stateForTests(id);
            Kit.log("civ01 laid, by hand: " + laid + "; " + state);
            helper.assertTrue(laid.size() >= 5, "many hands laid it: " + laid);
            String[] st = state.split("\\|");
            helper.assertTrue(st[2].equals(st[3]), "every piece set: " + state);
            // The bridge stands over the river: a deck the length of it, piers in the water.
            int deck = 0, over = 0;
            for (int x = cx + 22; x <= cx + 30; x++) {
                BlockState s = level.getBlockState(new BlockPos(x, heart.getY(), Z));
                if (s.is(Blocks.STONE_BRICKS)) deck++;
                if (x >= cx + 23 && x <= cx + 29 && s.is(Blocks.STONE_BRICKS)) over++;
            }
            BlockState pier = level.getBlockState(new BlockPos(cx + 26, heart.getY() - 3, Z));
            BlockState parapet = level.getBlockState(new BlockPos(cx + 26, heart.getY() + 1, Z + 2));
            Kit.log("civ01 the deck: " + deck + " blocks, " + over + " over the water; the pier " + pier + "; the parapet " + parapet);
            helper.assertTrue(over == 7, "the deck spans the river: " + over);
            helper.assertTrue(pier.is(Blocks.STONE_BRICKS), "a pier stands in the river: " + pier);
            helper.assertTrue(parapet.is(Blocks.STONE_BRICK_WALL), "a parapet along it: " + parapet);
            int bricksAfter = Market.stock(level, id, s -> s.is(Items.STONE_BRICKS));
            int paid = bricksBefore - bricksAfter;
            Kit.log("civ01 the stores paid " + paid + " stone bricks (" + bricksBefore + " -> " + bricksAfter + "); lanterns left "
                + Market.stock(level, id, s -> s.is(Items.LANTERN)));
            helper.assertTrue(paid >= 100 && paid < bricksBefore, "the stores paid for it: " + paid);
            String opened = BigWorks.finishAndOpenForTests(level, v);
            String story = chronicle(id);
            Kit.log("civ01 opened: " + opened + "; chronicle: " + story);
            helper.assertTrue(opened.contains("strung"), "a ribbon strung, of the stores' string and dye: " + opened);
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.RED_DYE)) == 0, "the dye went into the ribbon");
            helper.assertTrue(story.contains("was opened"), "the opening in the chronicle: " + story);
            helper.assertTrue(BigWorks.doneForTests(id).contains("BRIDGE"), "the bridge among the town's works");
            helper.assertTrue(!level.getBlockState(new BlockPos(cx + 22, heart.getY() + 1, Z)).is(McAssistantMod.RIBBON.get()), "the ribbon cut");
            helper.succeed();
        });
    }

    // ============================================================ civ02: voted down, it waits a season

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "civ02_rejected")
    public static void civ02_rejected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setWeatherParameters(12000, 0, false, false);
        final int cx = 1082000;
        long day = level.getDayTime() / 24000L + 3;
        level.setDayTime(day * 24000L + 3000L);
        List<VillageFolkEntity> folk = new ArrayList<>();
        Villages.Village v = riverTown(helper, level, cx, 9, folk);
        UUID id = v.id();
        chestAt(level, v.centre().offset(3, 0, -3), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
            new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.LANTERN, 4));
        helper.runAtTickTime(20, () -> {
            int q = Referendums.callForTests(level, v, BigWorks.Work.BRIDGE, day);
            helper.assertTrue(q > 0, "a bridge put to the town");
            UUID by = Referendums.callerForTests(id, q);
            for (VillageFolkEntity f : folk) if (!f.getUUID().equals(by)) thrifty(f);
            Referendums.castAllForTests(level, v, q);
            int[] count = Referendums.countForTests(level, v, q);
            long waits = Referendums.waitsForTests(id, BigWorks.Work.BRIDGE);
            Kit.log("civ02 the count: aye " + count[0] + ", nay " + count[1] + "; waits till day " + waits + "; " + chronicle(id));
            helper.assertTrue(count[2] == 0 && count[1] > count[0], "voted down: " + count[0] + "/" + count[1]);
            helper.assertTrue(BigWorks.stateForTests(id).isEmpty(), "nothing built");
            helper.assertTrue(waits == day + 7, "it waits a season: " + waits + " (today " + day + ")");
            // Asked about the works in the days after: not the bridge again.
            level.setDayTime((day + 4) * 24000L + 3000L);
            String again = Referendums.considerForTests(level, v);
            Kit.log("civ02 four days on, the town is asked: '" + again + "'");
            helper.assertTrue(!again.contains("stone bridge"), "the bridge waits: " + again);
            // The season out: it may be put again.
            level.setDayTime((day + 8) * 24000L + 3000L);
            String later = Referendums.considerForTests(level, v);
            Kit.log("civ02 eight days on, the town is asked: '" + later + "'");
            helper.assertTrue(later.contains("stone bridge"), "the season out, the bridge is put again: " + later);
            helper.succeed();
        });
    }

    // ============================================================ refugees: two towns, one at war

    /** Three towns: Ashford at war with Thornby and worn out by it, and Brookley at peace three hundred blocks east. */
    private static Villages.Village[] warTowns(GameTestHelper helper, ServerLevel level, int ax, List<VillageFolkEntity> aFolk, List<VillageFolkEntity> bFolk,
                                                long day) {
        int bx = ax + 300, tx = ax + 700;
        for (int x : new int[]{ ax, bx, tx }) {
            Kit.hold(level, x, Z, 64);
            Kit.prepare(level, x, Z, 64);
        }
        BlockPos a = flat(level, ax, Z, 30), b = flat(level, bx, Z, 64), t = flat(level, tx, Z, 12);
        aFolk.addAll(raise(helper, level, a, 10));
        bFolk.addAll(raise(helper, level, b, 8));
        List<VillageFolkEntity> thornby = raise(helper, level, t, 2);
        UUID aid = aFolk.get(0).ownerId(), bid = bFolk.get(0).ownerId(), tid = thornby.get(0).ownerId();
        helper.assertTrue(aid != null && bid != null && tid != null && !aid.equals(bid) && !bid.equals(tid) && !aid.equals(tid), "three towns");
        Villages.ageForTests(bid, Villages.Age.IRON);
        Wars.begin(aid, tid, day - 8);
        Ledger.note(aid, "wp.weary", "80");
        return new Villages.Village[]{ Villages.get(aid), Villages.get(bid), Villages.get(tid) };
    }

    /** The family that will go: two of Ashford's folk (not its leader, nor a builder at its build) made partners, a smith and a brewer by their years. */
    private static void family(ServerLevel level, Villages.Village a, List<VillageFolkEntity> aFolk) {
        List<VillageFolkEntity> free = new ArrayList<>();
        UUID elder = Villages.elder(a.id());
        for (VillageFolkEntity f : aFolk) {
            if (!f.getUUID().equals(elder) && !Villages.holdsTheLead(a.id(), f.getUUID(), level.getGameTime())) free.add(f);
        }
        VillageFolkEntity smith = free.get(0), brewer = free.get(1);
        smith.life().partnerWith(brewer.getUUID(), brewer.displayNameCap());
        brewer.life().partnerWith(smith.getUUID(), smith.displayNameCap());
        smith.tradeXpForTests(AssistantEntity.StationTask.SMITH, AssistantEntity.xpForLevel(12));
        brewer.tradeXpForTests(AssistantEntity.StationTask.BREW, AssistantEntity.xpForLevel(9));
    }

    /** Brookley's folk: generous and cheerful, every one of them. */
    private static void welcoming(List<VillageFolkEntity> folk) {
        for (VillageFolkEntity f : folk) f.life().setTraitsForTests(Social.Trait.GENEROUS, Social.Trait.CHEERFUL);
    }

    // ============================================================ civ03: refugees from the war, voted in

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "civ03_refugees_in")
    public static void civ03_refugees_in(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setWeatherParameters(12000, 0, false, false);
        long day = level.getDayTime() / 24000L + 10;
        level.setDayTime(day * 24000L + 10000L);                  // past the towns' own daily look at who leaves
        List<VillageFolkEntity> aFolk = new ArrayList<>(), bFolk = new ArrayList<>();
        Villages.Village[] towns = warTowns(helper, level, 1084000, aFolk, bFolk, day);
        Villages.Village a = towns[0], b = towns[1];
        welcoming(bFolk);
        Villages.bedsForTests(b.id(), level.getGameTime(), 30);                       // room for thirty, eight living there
        Leader.booksForTests(b.id(), new Leader.Books(400, 40, 20, 40, 20, 20.0, Leader.Plan.PLENTY, day));
        String[] pid = { "" };
        helper.runAtTickTime(20, () -> {
            family(level, a, aFolk);
            String cause = Newcomers.causeForTests(level, a);
            helper.assertTrue(cause.equals("WAR"), "Ashford is worn out by its war: " + cause);
            pid[0] = Newcomers.leavingForTests(level, a);
            helper.assertTrue(!pid[0].isEmpty(), "a household leaves Ashford");
            List<VillageFolkEntity> party = Newcomers.membersForTests(level, pid[0]);
            Kit.log("civ03 they leave: " + party.size() + " — " + Newcomers.stageForTests(pid[0]));
            helper.assertTrue(party.size() == 2, "the smith and the brewer go together: " + party.size());
            for (VillageFolkEntity f : party) {
                helper.assertTrue(f.ownerId() == null && Newcomers.is(f), f.displayNameCap() + " is nobody's on the road");
                helper.assertTrue(!onRoll(a.id(), f), f.displayNameCap() + " is off Ashford's roll");
            }
            helper.assertTrue(Newcomers.stageForTests(pid[0]).endsWith(b.id().toString()), "making for Brookley: " + Newcomers.stageForTests(pid[0]));
            int q = Newcomers.arriveForTests(level, pid[0]);
            helper.assertTrue(q > 0, "at Brookley's edge, they ask to settle");
            List<String> board = Referendums.board(level, b.id());
            Kit.log("civ03 Brookley's board: " + board);
            helper.assertTrue(board.stream().anyMatch(l -> l.contains("from " + Villages.name(a.id()) + ", fleeing the war, ask to settle")),
                "the board says who asks: " + board);
            for (VillageFolkEntity f : bFolk) Kit.log("civ03 " + f.displayNameCap() + ": " + Referendums.judgeForTests(level, f, b.id(), q));
            int relBefore = Ledger.relation(a.id(), b.id());
            Referendums.castAllForTests(level, b, q);
            int[] count = Referendums.countForTests(level, b, q);
            Kit.log("civ03 the count: aye " + count[0] + ", nay " + count[1]);
            helper.assertTrue(count[2] == 1, "a town with room and food votes them in: " + count[0] + "/" + count[1]);
            boolean smith = false, brewer = false;
            for (VillageFolkEntity f : party) {
                Kit.log("civ03 " + f.displayNameCap() + ": of " + (f.ownerId() == null ? "nobody" : Villages.name(f.ownerId())) + ", "
                    + f.stationTask() + " level " + f.tradeLevel(f.stationTask()) + "; " + Newcomers.settledForTests(f));
                helper.assertTrue(b.id().equals(f.ownerId()) && onRoll(b.id(), f), f.displayNameCap() + " is Brookley's now");
                helper.assertTrue(!onRoll(a.id(), f) && !Newcomers.is(f), f.displayNameCap() + " is off Ashford's roll for good");
                if (f.stationTask() == AssistantEntity.StationTask.SMITH) smith = true;
                if (f.stationTask() == AssistantEntity.StationTask.BREW) brewer = true;
            }
            helper.assertTrue(smith && brewer, "they bring the trades Brookley lacked: a smith and a brewer");
            String story = chronicle(b.id());
            Kit.log("civ03 Brookley's chronicle: " + story + " relations " + relBefore + " -> " + Ledger.relation(a.id(), b.id()));
            helper.assertTrue(story.contains("taken in"), "the chronicle has it: " + story);
            helper.assertTrue(Ledger.relation(a.id(), b.id()) > relBefore, "Ashford thinks the better of Brookley");
            helper.assertTrue(Referendums.book(level, b.id()).stream().anyMatch(l -> l.startsWith("Newcomers:")), "the books keep a newcomers line");
            helper.succeed();
        });
    }

    // ============================================================ civ04: a full and hungry town turns them away

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "civ04_turned_away")
    public static void civ04_turned_away(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setWeatherParameters(12000, 0, false, false);
        long day = level.getDayTime() / 24000L + 10;
        level.setDayTime(day * 24000L + 10000L);
        List<VillageFolkEntity> aFolk = new ArrayList<>(), bFolk = new ArrayList<>();
        Villages.Village[] towns = warTowns(helper, level, 1086000, aFolk, bFolk, day);
        Villages.Village a = towns[0], b = towns[1];
        welcoming(bFolk);
        Villages.bedsForTests(b.id(), level.getGameTime(), Villages.headcount(b.id()));   // every bed taken
        Leader.booksForTests(b.id(), new Leader.Books(2, 0, 20, 0, 20, 0.1, Leader.Plan.FAMINE, day));
        helper.runAtTickTime(20, () -> {
            family(level, a, aFolk);
            String pid = Newcomers.leavingForTests(level, a);
            helper.assertTrue(!pid.isEmpty(), "a household leaves Ashford");
            List<VillageFolkEntity> party = Newcomers.membersForTests(level, pid);
            int q = Newcomers.arriveForTests(level, pid);
            helper.assertTrue(q > 0, "they ask to settle");
            int relBefore = Ledger.relation(a.id(), b.id());
            for (VillageFolkEntity f : bFolk) Kit.log("civ04 " + f.displayNameCap() + ": " + Referendums.judgeForTests(level, f, b.id(), q));
            Referendums.castAllForTests(level, b, q);
            int[] count = Referendums.countForTests(level, b, q);
            String stage = Newcomers.stageForTests(pid);
            Kit.log("civ04 the count: aye " + count[0] + ", nay " + count[1] + "; the party: " + stage);
            helper.assertTrue(count[2] == 0 && count[0] == 0, "a full, hungry town turns them away, its generous folk too: " + count[0] + "/" + count[1]);
            for (VillageFolkEntity f : party) {
                helper.assertTrue(!onRoll(b.id(), f) && f.ownerId() == null && Newcomers.is(f), f.displayNameCap() + " is not Brookley's");
            }
            helper.assertTrue(stage.startsWith("home|"), "nowhere else at peace to go: they make for home: " + stage);
            String story = chronicle(b.id());
            Kit.log("civ04 Brookley's chronicle: " + story + "; Ashford's: " + chronicle(a.id()));
            helper.assertTrue(story.contains("turned away"), "the chronicle remembers it: " + story);
            helper.assertTrue(Ledger.relation(a.id(), b.id()) < relBefore, "Ashford thinks the worse of Brookley: " + Ledger.relation(a.id(), b.id()));
            helper.succeed();
        });
    }

    // ============================================================ civ05: the works fit the land

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "civ05_fit_the_land")
    public static void civ05_fit_the_land(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setWeatherParameters(12000, 0, false, false);
        final int cx = 1088000;
        long day = level.getDayTime() / 24000L + 3;
        level.setDayTime(day * 24000L + 3000L);
        Kit.hold(level, cx, Z, 72);
        Kit.prepare(level, cx, Z, 72);
        BlockPos heart = flat(level, cx, Z, 64);
        int y = heart.getY();
        // A lake forty blocks east, and a knoll three high on the way to it.
        water(level, cx + 40, cx + 54, Z - 9, Z + 9, y);
        for (int x = cx + 20; x <= cx + 27; x++) {
            for (int z = Z - 4; z <= Z + 4; z++) {
                for (int dy = 0; dy < 3; dy++) level.setBlock(new BlockPos(x, y + dy, z), dy == 2 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                level.setBlock(new BlockPos(x, y - 1, z), Blocks.DIRT.defaultBlockState(), 2 | 16);
            }
        }
        List<VillageFolkEntity> folk = raise(helper, level, heart, 8);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        Villages.setFieldsSide(id, TownPlan.SOUTH);                 // the fields to the south: the wall's first side is the north
        int ring = Villages.townReach(id) + 6;
        // A mound on the north line of the wall, and a path out through it.
        int north = Z - ring;
        for (int x = cx - 12; x <= cx - 4; x++) {
            for (int z = north - 1; z <= north + 1; z++) {
                for (int dy = 0; dy < 2; dy++) level.setBlock(new BlockPos(x, y + dy, z), dy == 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                level.setBlock(new BlockPos(x, y - 1, z), Blocks.DIRT.defaultBlockState(), 2 | 16);
            }
        }
        for (int z = north - 3; z <= north + 3; z++) level.setBlock(new BlockPos(cx + 10, y - 1, z), Blocks.DIRT_PATH.defaultBlockState(), 2 | 16);
        helper.runAtTickTime(20, () -> {
            // The aqueduct: its channel level from end to end, high enough over the knoll to walk under, its piers to the ground.
            List<String> aq = BigWorks.planForTests(level, v, BigWorks.Work.AQUEDUCT);
            helper.assertTrue(!aq.isEmpty(), "an aqueduct drawn from the lake");
            int channel = Integer.MIN_VALUE, waters = 0, piersToGround = 0;
            Map<String, Integer> lowest = new HashMap<>();
            for (String l : aq) {
                String[] p = l.split(" ");
                int px = Integer.parseInt(p[1]), py = Integer.parseInt(p[2]), pz = Integer.parseInt(p[3]);
                if (p[0].equals("WATER") && pz == Z && px > cx + 9) {
                    if (channel == Integer.MIN_VALUE) channel = py;
                    helper.assertTrue(py == channel, "the channel is level: " + py + " against " + channel);
                    waters++;
                }
                if (p[0].equals("BLOCK")) lowest.merge(px + "," + pz, py, Math::min);
            }
            for (int x = cx + 9; x < cx + 40; x += 4) {
                Integer low = lowest.get(x + "," + Z);
                int ground = Kit.surface(level, x, Z).getY();
                if (low != null && low == ground) piersToGround++;
            }
            Kit.log("civ05 aqueduct: " + aq.size() + " pieces, channel at " + channel + " (ground " + y + ", knoll " + (y + 3) + "), "
                + waters + " of water, " + piersToGround + " piers down to the ground");
            helper.assertTrue(channel >= y + 3 + 3, "the channel clears the knoll with room to walk under: " + channel);
            helper.assertTrue(waters >= 25, "water runs the length of it: " + waters);
            helper.assertTrue(piersToGround >= 6, "its piers stand on the ground: " + piersToGround);
            // Built: the stores pay, the water runs in the channel over the knoll.
            chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
                new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
                new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
                new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.BUCKET), new ItemStack(Items.LANTERN, 4));
            int q = Referendums.callForTests(level, v, BigWorks.Work.AQUEDUCT, day);
            helper.assertTrue(q > 0, "the aqueduct put to the town");
            for (VillageFolkEntity f : folk) visionary(f);
            Referendums.castAllForTests(level, v, q);
            int[] count = Referendums.countForTests(level, v, q);
            helper.assertTrue(count[2] == 1, "carried: " + count[0] + "/" + count[1]);
            Map<String, Integer> laid = BigWorks.buildForTests(level, v, folk, 600);
            String state = BigWorks.stateForTests(id);
            BlockState overKnoll = level.getBlockState(new BlockPos(cx + 23, channel, Z));
            Kit.log("civ05 built by " + laid + ": " + state + "; over the knoll " + overKnoll);
            helper.assertTrue(overKnoll.is(Blocks.WATER), "water carried on arches over the knoll: " + overKnoll);
            // The wall's north side: on the ground over the mound, a gateway where the path goes through.
            List<String> wall = BigWorks.planForTests(level, v, BigWorks.Work.WALL);
            int onMound = 0, onFlat = 0, inGate = 0;
            for (String l : wall) {
                String[] p = l.split(" ");
                int px = Integer.parseInt(p[1]), py = Integer.parseInt(p[2]), pz = Integer.parseInt(p[3]);
                if (pz != north || !p[0].equals("BLOCK")) continue;
                if (px >= cx - 12 && px <= cx - 4 && py == y + 2) onMound++;
                if (px >= cx + 14 && px <= cx + 30 && py == y) onFlat++;
                if (px >= cx + 9 && px <= cx + 11) inGate++;
            }
            Kit.log("civ05 the wall: " + wall.size() + " pieces; " + onMound + " founded on the mound, " + onFlat + " on the flat, " + inGate + " in the gateway");
            helper.assertTrue(onMound >= 8 && onFlat >= 10, "the wall follows the ground: " + onMound + " on the mound, " + onFlat + " on the flat");
            helper.assertTrue(inGate == 0, "a gateway left where the path goes through: " + inGate);
            helper.succeed();
        });
    }

    // ============================================================ civ06: newcomers from outside

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "civ06_from_outside")
    public static void civ06_from_outside(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setWeatherParameters(12000, 0, false, false);
        final int cx = 1090000;
        long day = level.getDayTime() / 24000L + 3;
        level.setDayTime(day * 24000L + 10000L);
        Kit.hold(level, cx, Z, 72);
        Kit.prepare(level, cx, Z, 72);
        BlockPos heart = flat(level, cx, Z, 70);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 8);
        welcoming(folk);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        Villages.bedsForTests(id, level.getGameTime(), 30);
        Leader.booksForTests(id, new Leader.Books(400, 40, 20, 40, 20, 20.0, Leader.Plan.PLENTY, day));
        helper.runAtTickTime(20, () -> {
            String pid = Newcomers.outsideForTests(level, v, 3);
            helper.assertTrue(pid != null, "a family from outside comes");
            List<VillageFolkEntity> party = Newcomers.membersForTests(level, pid);
            int children = 0;
            for (VillageFolkEntity f : party) {
                if (f.isBaby()) children++;
                helper.assertTrue(f.ownerId() == null && Newcomers.is(f), f.displayNameCap() + " is nobody's on the road");
            }
            Kit.log("civ06 " + Newcomers.stageForTests(pid) + "; " + party.size() + " of them, " + children + " a child");
            helper.assertTrue(party.size() == 3 && children == 1, "a couple and a child: " + party.size() + ", " + children);
            int q = Newcomers.arriveForTests(level, pid);
            Referendums.castAllForTests(level, v, q);
            int[] count = Referendums.countForTests(level, v, q);
            helper.assertTrue(count[2] == 1, "voted in: " + count[0] + "/" + count[1]);
            for (VillageFolkEntity f : party) {
                Kit.log("civ06 " + f.displayNameCap() + ": " + f.stationTask() + " level " + f.tradeLevel(f.stationTask()) + "; " + Newcomers.settledForTests(f));
                helper.assertTrue(id.equals(f.ownerId()), f.displayNameCap() + " is the town's");
                if (!f.isBaby()) {
                    helper.assertTrue(f.stationTask() != AssistantEntity.StationTask.NONE && f.tradeLevel(f.stationTask()) >= 8,
                        f.displayNameCap() + " brings a trade with years at it: " + f.stationTask() + " " + f.tradeLevel(f.stationTask()));
                }
            }
            helper.succeed();
        });
    }
}
