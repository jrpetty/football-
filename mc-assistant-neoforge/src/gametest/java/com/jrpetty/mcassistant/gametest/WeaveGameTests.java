package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Auctions;
import com.jrpetty.mcassistant.entity.Budget;
import com.jrpetty.mcassistant.entity.Crime;
import com.jrpetty.mcassistant.entity.Disasters;
import com.jrpetty.mcassistant.entity.Families;
import com.jrpetty.mcassistant.entity.Fashion;
import com.jrpetty.mcassistant.entity.FireBrigade;
import com.jrpetty.mcassistant.entity.Heraldry;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Library;
import com.jrpetty.mcassistant.entity.Lodge;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Newcomers;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.Pets;
import com.jrpetty.mcassistant.entity.QuestBook;
import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.entity.QuestBook.State;
import com.jrpetty.mcassistant.entity.QuestMaker;
import com.jrpetty.mcassistant.entity.QuestRun;
import com.jrpetty.mcassistant.entity.Rebuilding;
import com.jrpetty.mcassistant.entity.SearchParties;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Weave;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.Garment;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.LibraryRecords;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [weave] The town's systems feeding each other (entity/Weave): the watch asking for help with a case and the treasury
 * paying when the right one is convicted, and a player's wrong word costing it; a pet really lost and found by
 * following its finder home, and let go when the quest is given up; water carried to a fire, a flooded household's things
 * carried up out of its chest, the rebuilding's planks brought; the burnt-out with nowhere to sleep sent as refugees to the
 * next town; short rations tempting; nothing stolen or forged sold or auctioned, the tailor's finest under the hammer and
 * worn by its winner; the new offices' clothes; the pledge's guards in their kit; the gazette's front page; and the
 * library's new books.
 *
 * <ul>
 * <li><b>w01</b>: a purse picked; the guard on it offers "help the watch" with the real ways to help as its steps; the
 *     player follows the footprints, hands in what was dropped, asks those who were about, tells the guard; the council
 *     convicts the culprit, and the treasury pays (counted).</li>
 * <li><b>w02</b>: the player tells the watch it saw an innocent do it; on that word alone the council has the innocent up
 *     and clears it: the innocent and the town think the less of the player, the chronicle says so, and the quest ends
 *     unpaid.</li>
 * <li><b>w03</b>: a dog goes off after a rabbit for the quest board (held out there); a player takes the household's
 *     quest, finds the dog, walks home with it at heel (Pets.onFound crosses the step off) and is paid out of the
 *     household's purse.</li>
 * <li><b>w04</b>: the weave's own morning round sends the dog off before long (and notes the day, for the fortnight's
 *     gap); the household's quest taken and given up: the dog is let go to find its own way home.</li>
 * <li><b>w05</b>: a fire in the town: the weave's round has the elder ask for water at once; each bucket handed over is thrown on the flames
 *     (the nearest three go out), the empty bucket comes back, another is asked for while it burns; the brigade has the
 *     rest out, and the treasury pays for the two buckets poured.</li>
 * <li><b>w06</b>: a burnt wall waits on planks the stores have not got: the elder asks for them; the planks handed over go
 *     into the stores and the wall goes back up at once, out of them.</li>
 * <li><b>w07</b>: the river in a house: the household's things carried out of its chest (marked as the quest's) and up to
 *     one of the household on the high ground, theirs again; the treasury pays.</li>
 * <li><b>w08</b>: a household's house burnt, and no bed in the town for it (no neighbour's, no inn, no hall): at dusk
 *     they go as refugees to the town at peace next door, in their old town's colours.</li>
 * <li><b>w09</b>: a drought's short rations make a contented folk the more tempted.</li>
 * <li><b>w10</b>: stolen goods and a forged coin are never put up or sold; a master's coat and a gold brooch are the
 *     auction's lots, a plain coat not.</li>
 * <li><b>w11</b>: a coat won at the auction is put on at once and counts as the season's fashion, whatever its colour.</li>
 * <li><b>w12</b>: the librarian wants a waistcoat in the season's colour (its office), the constable and the auctioneer
 *     are known by their offices and looked to, a player leader's steward wants a long coat and sets the fashion, and a
 *     newcomer comes in its old town's colours, quicker to the new town's fashion.</li>
 * <li><b>w13</b>: a leader's "more guards" counts only the guards in their kit.</li>
 * <li><b>w14</b>: the gazette's biggest story leads its front page.</li>
 * <li><b>w15</b>: the library's new books: the great flood's and the great fire's histories, the life of a cave dweller
 *     lost below, a poem for the bridge's opening, a ballad of the famous sale; the cave team's and the watch's notes.</li>
 * <li><b>w16</b>: the lodge's trophy wall full (six finds hung, out of the stores' spares): the seventh kind of find goes
 *     under the hammer, its provenance the finder's and the cave's.</li>
 * <li><b>w17</b>: a flooded household's things carried out and the quest given up: they are handed back to the
 *     household, never kept.</li>
 * </ul>
 *
 * <p>Each on its own ground (x 1180000 to 1199999, z 66000), in a batch of its own, most of it called directly.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WeaveGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    private static final int RED = DyeColor.RED.getId(), BLUE = DyeColor.BLUE.getId();

    // ------------------------------------------------------------------ helpers

    private static void clean(ServerLevel level) {
        Kit.reset(level);
        QuestBook.resetForTests();
        QuestRun.resetForTests();
        Standing.resetForTests();
        SearchParties.resetForTests();
        Weave.liveForTests(false);
        level.setWeatherParameters(24000, 0, false, false);
    }

    /** Level, open ground: earth under it and nothing over it. Returns the ground's walking level at the middle. */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        int y = Kit.surface(level, cx, cz).getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
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

    private static BlockPos heart(ServerLevel level, int x, int hold, int r) {
        Kit.hold(level, x, Z, hold);
        Kit.prepare(level, x, Z, hold);
        return flat(level, x, Z, r);
    }

    private static VillageFolkEntity raise(GameTestHelper helper, BlockPos at, String who) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(helper.getLevel(), at, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "raised " + who);
        f.ensurePersona();
        return f;
    }

    private static List<VillageFolkEntity> town(GameTestHelper helper, BlockPos heart, int n) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(raise(helper, i == 0 ? heart : heart.offset(-6 + (i % 5) * 3, 0, 4 + (i / 5) * 3), "folk " + i));
            helper.assertTrue(out.get(0).ownerId().equals(out.get(i).ownerId()), "one town");
        }
        return out;
    }

    /** The town's stores: every chest of them emptied, and one marked chest here holding these. */
    private static Container stores(ServerLevel level, UUID village, BlockPos at, ItemStack... goods) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i].copy());
        box.setChanged();
        Villages.forgetStores(village);
        Villages.forgetStock();
        return box;
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    private static int count(Player p, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (what.test(p.getInventory().getItem(i))) n += p.getInventory().getItem(i).getCount();
        return n;
    }

    private static int count(VillageFolkEntity f, Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : f.getInventoryItems()) if (what.test(s)) n += s.getCount();
        return n;
    }

    private static int questOf(ItemStack s) {
        var d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? 0 : d.copyTag().getInt("mca_quest");
    }

    private static void at(Player p, BlockPos pos) {
        p.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
    }

    private static String chronicle(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Chronicle.Entry e : Chronicle.of(village)) sb.append(e.text()).append(" | ");
        return sb.toString();
    }

    private static String steps(Quest q) {
        StringBuilder sb = new StringBuilder();
        for (QuestBook.Step s : q.steps) sb.append(s.done ? "[x] " : "[ ] ").append(s.key).append(": ").append(s.text).append(s.note.isEmpty() ? "" : " (" + s.note + ")").append("; ");
        return sb.toString();
    }

    private static VillageFolkEntity folkOf(ServerLevel level, UUID id) {
        return level.getEntity(id) instanceof VillageFolkEntity f ? f : null;
    }

    /** The town's open quest of this script (offered or under way), or null. */
    private static Quest offered(UUID village, String script) {
        for (Quest q : QuestBook.open(village)) if (script.equals(q.script)) return q;
        return null;
    }

    /** The weave's own two-second round for the town, as in a real game (not the tests' quiet): what it asks for at once. */
    private static Quest urgent(ServerLevel level, Villages.Village v, String script) {
        Weave.liveForTests(true);
        try {
            Weave.tickForTests(level, v);
        } finally {
            Weave.liveForTests(false);
        }
        return offered(v.id(), script);
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        Villages.forgetStock();
        return Market.stock(level, village, what);
    }

    /** A level patch of grass at height y (its top), stone under it, cleared above. */
    private static void ground(ServerLevel level, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = 1; dy <= 14; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState(), 2);
                for (int dy = -4; dy < 0; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
        }
    }

    /** Fire held where it is set (no spreading, no burning away) while the test runs. */
    private static boolean holdFire(ServerLevel level) {
        GameRules.BooleanValue rule = level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        boolean was = rule.get();
        rule.set(false, level.getServer());
        return was;
    }

    private static void letFire(ServerLevel level, boolean was) {
        level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(was, level.getServer());
    }

    private static int fires(ServerLevel level, List<BlockPos> at) {
        int n = 0;
        for (BlockPos p : at) if (level.getBlockState(p).is(BlockTags.FIRE)) n++;
        return n;
    }

    /** A plain house of four beds, its back to the north, here; on the books. */
    private static BlockPos house(ServerLevel level, UUID village, BlockPos p) {
        BuildGoal.stamp(level, "house", p, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "house", p, Direction.NORTH);
        Villages.recountBeds(village);
        return p;
    }

    /** The house's back wall burnt (six planks gone), as the fire brigade's books see it: written down to be rebuilt. */
    private static int burnBackWall(GameTestHelper helper, ServerLevel level, UUID id, BlockPos anchor) {
        List<BlockPos> wall = new ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan("house", anchor, Direction.NORTH, 13)) {
            if (level.getBlockState(p.pos()).is(Blocks.OAK_PLANKS) && p.pos().getZ() == anchor.getZ() - 3) wall.add(p.pos());
        }
        helper.assertTrue(wall.size() >= 6, "the house's back wall is of planks: " + wall.size());
        BlockPos flame = wall.get(0).north();
        level.setBlock(flame, BaseFireBlock.getState(level, flame), 3);
        FireBrigade.watchForTests(level, id);
        for (int i = 0; i < 6; i++) level.setBlock(wall.get(i), Blocks.AIR.defaultBlockState(), 3);
        level.removeBlock(flame, false);
        FireBrigade.watchForTests(level, id);
        return Rebuilding.waitingForTests(id).getOrDefault(anchor, 0);
    }

    /** The garment as the tailor's master made it: its maker's mark. */
    private static ItemStack masterMade(ItemStack s, String by) {
        CustomData.update(DataComponents.CUSTOM_DATA, s, t -> {
            CompoundTag m = new CompoundTag();
            m.putInt("grade", 4);                        // Craftsmanship.Grade.MASTER
            m.putString("by", by);
            t.put("mca_made", m);
        });
        return s;
    }

    // ------------------------------------------------------------------ the watch's case

    /** A town of five round its market, a purse picked in it, the case on the books and the guard on it. {victim, culprit, seer, guard, fifth}. */
    private record Scene(List<VillageFolkEntity> folk, VillageFolkEntity guard, VillageFolkEntity culprit, UUID village, Villages.Village v) {}

    private static Scene scene(GameTestHelper helper, int x) {
        ServerLevel level = helper.getLevel();
        clean(level);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 2500L);
        BlockPos heart = heart(level, x, 64, 50);
        VillageFolkEntity victim = raise(helper, heart, "the victim");
        VillageFolkEntity culprit = raise(helper, heart.offset(-2, 0, 0), "the culprit");
        VillageFolkEntity seer = raise(helper, heart.offset(7, 0, 0), "a witness");
        VillageFolkEntity guard = raise(helper, heart.offset(0, 0, -18), "the guard");
        VillageFolkEntity fifth = raise(helper, heart.offset(0, 0, 18), "a fifth folk");
        UUID village = victim.ownerId();
        guard.setJob(StationTask.GUARD);
        culprit.setJob(StationTask.FARM);
        for (VillageFolkEntity f : List.of(victim, seer, fifth)) {
            f.setNoAi(true);
            f.spend(f.purse());
            f.earn(10);
        }
        Ledger.addCoins(village, 80);
        return new Scene(new ArrayList<>(List.of(victim, culprit, seer, guard, fifth)), guard, culprit, village, Villages.get(village));
    }

    /** The purse picked now, reported, and given to the watch. */
    private static Crime.Case caseOn(GameTestHelper helper, Scene s) {
        ServerLevel level = helper.getLevel();
        VillageFolkEntity victim = s.folk().get(0);
        s.culprit().moveTo(victim.getX() - 1.0, victim.getY(), victim.getZ(), 0.0F, 0.0F);
        Crime.Case c = Crime.commitForTests(level, s.culprit(), Crime.Kind.PICKPOCKET, victim, null);
        helper.assertTrue(c != null, "the purse is picked");
        Crime.reportForTests(level, c);
        helper.assertTrue(Weave.assignForTests(level, c) && s.guard().getUUID().equals(c.investigator()), "the guard takes the case: " + c.investigatorName());
        return c;
    }

    // ============================================================ w01: help the watch, and the treasury pays

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "weave_w01_help_the_watch")
    public static void w01_help_the_watch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene s = scene(helper, 1180000);
        helper.runAtTickTime(20, () -> {
            Crime.Case c = caseOn(helper, s);
            Quest q = QuestMaker.offerForTests(level, s.v(), "watch");
            Kit.log("w01 offer: " + (q == null ? "none" : q.title + " by " + q.giverName + ", " + q.coins + " coins from the " + q.payer + "; " + steps(q)));
            helper.assertTrue(q != null && s.guard().getUUID().equals(q.giver) && "treasury".equals(q.payer) && q.coins > 0,
                "the guard on the case asks for help, the treasury to pay: " + (q == null ? "none" : q.giverName));
            helper.assertTrue(q.step("tell") != null && q.step("verdict") != null && q.step("ask") != null, "its steps are the ways to help: " + steps(q));
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            String took = QuestRun.accept(level, s.guard(), p);
            Kit.log("w01 taken -> " + took);
            helper.assertTrue(q.state == State.ACTIVE, "taken: " + q.state + " — " + took);
            // The footprints, while they are fresh: from the scene to where they lead.
            if (q.step("trail") != null) {
                List<BlockPos> trail = c.trail();
                BlockPos end = trail.get(trail.size() - 1);
                p.teleportTo(level, end.getX() + 0.5, end.getY(), end.getZ() + 0.5, Set.of(), 0.0F, 0.0F);
                QuestRun.checkForTests(level, p);
                helper.assertTrue(q.step("trail").done, "the footprints followed: " + steps(q));
            }
            // What was dropped, handed to the guard.
            if (q.step("dropped") != null) {
                ItemEntity e = Weave.droppedForTests(level, c);
                helper.assertTrue(e != null, "something dropped at the scene");
                p.setItemInHand(InteractionHand.MAIN_HAND, e.getItem().copy());
                Kit.log("w01 handed in -> " + Crime.talk(s.guard(), p, "I found this by the market."));
                helper.assertTrue(q.step("dropped").done, "what was dropped handed in: " + steps(q));
            }
            // Those who were about, asked.
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            for (VillageFolkEntity f : s.folk()) {
                if (q.step("ask").done) break;
                if (f == s.guard() || f == s.culprit()) continue;
                Kit.log("w01 asked " + f.displayNameCap() + " -> " + Crime.talk(f, p, "Seen anything amiss?"));
            }
            helper.assertTrue(q.step("ask").done, "those who were about asked: " + steps(q));
            String told = QuestRun.talk(level, s.guard(), p, null);
            Kit.log("w01 told the guard -> " + told);
            helper.assertTrue(q.step("tell").done && "verdict".equals(q.current().key), "told the guard; the council's verdict next: " + steps(q));
            // The council convicts the culprit: the treasury pays.
            int treasury0 = Ledger.coins(s.village()), coins0 = Market.coinsHeld(p);
            Crime.accuseForTests(c, s.culprit());
            Weave.verdictForTests(level, c, true);
            int treasury1 = Ledger.coins(s.village()), coins1 = Market.coinsHeld(p);
            Kit.log("w01 the verdict: " + c.verdict() + " | treasury " + treasury0 + " -> " + treasury1 + ", player " + coins0 + " -> " + coins1 + "; " + q.state + "; " + steps(q));
            Kit.log("w01 the chronicle: " + chronicle(s.village()));
            helper.assertTrue(c.stage() == Crime.Stage.CONVICTED && q.state == State.DONE, "convicted, and the quest done: " + c.stage() + " " + q.state);
            helper.assertTrue(treasury0 - treasury1 == q.coins && coins1 - coins0 == q.coins, "paid out of the treasury: " + q.coins + "; treasury " + treasury0 + " -> "
                + treasury1 + ", player " + coins0 + " -> " + coins1);
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    // ============================================================ w02: a wrong word, and what it costs

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "weave_w02_a_wrong_word")
    public static void w02_a_wrong_word(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene s = scene(helper, 1181000);
        BlockPos far = Kit.surface(level, s.v().centre().getX() + 44, s.v().centre().getZ());
        VillageFolkEntity innocent = raise(helper, far, "the innocent");
        innocent.setNoAi(true);
        helper.runAtTickTime(20, () -> {
            Crime.Case c = caseOn(helper, s);
            Quest q = QuestMaker.offerForTests(level, s.v(), "watch");
            helper.assertTrue(q != null, "the guard asks for help");
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            List<VillageFolkEntity> all = new ArrayList<>(s.folk());
            all.add(innocent);
            for (VillageFolkEntity f : all) f.persona().feelFor(p.getUUID(), p.getName().getString(), 20);
            QuestRun.accept(level, s.guard(), p);
            helper.assertTrue(q.state == State.ACTIVE, "taken");
            Standing.stir(s.village(), p.getUUID());
            int score0 = Standing.of(s.village(), p.getUUID(), level.getGameTime()).score(), aff0 = innocent.persona().affinity(p.getUUID());
            int treasury0 = Ledger.coins(s.village()), coins0 = Market.coinsHeld(p);
            // "I saw it": the player's word, and nothing else, against the innocent; the council clears it.
            Weave.wordForTests(c, p, innocent, 70);
            Crime.accuseForTests(c, innocent);
            Weave.verdictForTests(level, c, false);
            Standing.stir(s.village(), p.getUUID());
            int score1 = Standing.of(s.village(), p.getUUID(), level.getGameTime()).score(), aff1 = innocent.persona().affinity(p.getUUID());
            Kit.log("w02 cleared: " + c.stage() + "; the innocent's feeling for the player " + aff0 + " -> " + aff1 + ", the town's standing " + score0 + " -> " + score1
                + "; the quest " + q.state + " (" + q.outcome + "); treasury " + treasury0 + " -> " + Ledger.coins(s.village()));
            Kit.log("w02 the chronicle: " + chronicle(s.village()));
            helper.assertTrue(c.cleared().contains(innocent.getUUID()), "the innocent cleared");
            helper.assertTrue(aff0 - aff1 >= 20 && score1 < score0, "the innocent and the town think the less of the player: " + aff0 + " -> " + aff1 + ", " + score0 + " -> " + score1);
            helper.assertTrue(chronicle(s.village()).contains("wrongly accused"), "the chronicle has it");
            helper.assertTrue(q.state == State.FAILED && q.outcome.contains("wrong one"), "the quest over, unpaid: " + q.state + " " + q.outcome);
            helper.assertTrue(Ledger.coins(s.village()) == treasury0 && Market.coinsHeld(p) == coins0, "nothing paid");
            // Tried again on the same word: it is paid for once.
            int aff2 = innocent.persona().affinity(p.getUUID());
            Crime.accuseForTests(c, innocent);
            Weave.verdictForTests(level, c, false);
            helper.assertTrue(innocent.persona().affinity(p.getUUID()) == aff2, "one word is paid for once: " + aff2 + " -> " + innocent.persona().affinity(p.getUUID()));
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    // ============================================================ the pets

    private static final long DAY = 24000L * 4;

    /** A household of two with a child and a dog of its own, in a house of its own, in a town of three. {mother, father, dog}. */
    private static Object[] household(GameTestHelper helper, int x) {
        ServerLevel level = helper.getLevel();
        clean(level);
        level.setDayTime(DAY + 3000);
        BlockPos heart = heart(level, x, 112, 48);
        Pets.calmForTests(true);
        Pets.settledForTests(true);
        VillageFolkEntity mother = raise(helper, heart, "the mother");
        UUID id = mother.ownerId();
        VillageFolkEntity father = raise(helper, Kit.surface(level, x + 2, Z), "the father");
        Villages.Village v = Villages.get(id);
        mother.life().widowed();
        father.life().widowed();
        mother.life().partnerWith(father.getUUID(), father.displayNameCap());
        father.life().partnerWith(mother.getUUID(), mother.displayNameCap());
        BlockPos home = house(level, id, Kit.surface(level, x - 16, Z + 16));
        Homes.tickForTests(level, v);
        helper.assertTrue(home.equals(Homes.homeOf(mother)) && home.equals(Homes.homeOf(father)), "the couple's house");
        mother.insertItem(new ItemStack(Items.BREAD, 2));
        father.insertItem(new ItemStack(Items.BREAD, 2));
        VillageFolkEntity child = mother.raiseChildWith(father);
        helper.assertTrue(child != null, "a child born");
        Homes.tickForTests(level, v);
        BlockPos chestAt = Families.homeChestForTests(level, mother);
        helper.assertTrue(chestAt != null && level.getBlockEntity(chestAt) instanceof Container, "the household's chest");
        Container chest = (Container) level.getBlockEntity(chestAt);
        chest.setItem(0, new ItemStack(Items.BONE, 6));
        chest.setChanged();
        Pets.wantForTests(id, home, true);
        TamableAnimal stray = Pets.strayForTests(level, id, false, Kit.surface(level, home.getX() + 8, home.getZ() + 6));
        helper.assertTrue(stray != null, "a stray about");
        Pets.takeInForTests(level, id, home);
        helper.assertTrue(stray.getUUID().equals(Families.petForTests(id, home)), "the household took in the stray");
        Pets.wantForTests(id, home, null);
        mother.earn(20);
        father.earn(20);
        return new Object[]{ mother, father, stray };
    }

    // ============================================================ w03: a lost dog, found by a player

    @GameTest(template = EMPTY, timeoutTicks = 1400, batch = "weave_w03_lost_dog_found")
    public static void w03_lost_dog_found(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Object[][] state = new Object[1][];
        String[] said = { null };
        int[] before = new int[2];
        helper.runAtTickTime(5, () -> {
            Object[] h = household(helper, 1182000);
            VillageFolkEntity mother = (VillageFolkEntity) h[0];
            TamableAnimal dog = (TamableAnimal) h[2];
            UUID id = mother.ownerId();
            Quest q = Weave.lostPetForTests(level, Villages.get(id));
            Kit.log("w03 offer: " + (q == null ? "none" : q.title + " by " + q.giverName + ", " + q.coins + " coins; " + steps(q) + " — " + q.offer));
            helper.assertTrue(q != null && dog.getUUID().toString().equals(q.flag("pet")), "the dog gone off, and its household asks: " + q);
            helper.assertTrue(Pets.isLost(dog.getUUID()) && Weave.heldForTests(id, dog.getUUID()), "lost, and held out there for whoever takes it on");
            VillageFolkEntity giver = folkOf(level, q.giver);
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            p.teleportTo(level, dog.getX() + 2, dog.getY(), dog.getZ(), Set.of(), 0.0F, 0.0F);
            String took = QuestRun.accept(level, giver, p);
            helper.assertTrue(q.state == State.ACTIVE, "taken: " + took);
            QuestRun.checkForTests(level, p);
            helper.assertTrue(q.step("find").done && "home".equals(q.current().key), "found; home next: " + steps(q));
            state[0] = new Object[]{ q, p, dog, mother, giver };
        });
        // The player walks home, six blocks a second, and the dog keeps at heel.
        helper.onEachTick(() -> {
            if (state[0] == null) return;
            Quest q = (Quest) state[0][0];
            ServerPlayer p = (ServerPlayer) state[0][1];
            VillageFolkEntity mother = (VillageFolkEntity) state[0][3], giver = (VillageFolkEntity) state[0][4];
            if (said[0] == null && q.current() != null && "back".equals(q.current().key)) {
                before[0] = giver.purse();
                before[1] = Market.coinsHeld(p);
                said[0] = QuestRun.talk(level, giver, p, null);
                Kit.log("w03 told " + giver.displayNameCap() + " -> " + said[0]);
                return;
            }
            if (level.getGameTime() % 20 == 0) {
                BlockPos hearth = Families.hearthForTests(level, mother);
                double dx = hearth.getX() + 2.5 - p.getX(), dz = hearth.getZ() + 0.5 - p.getZ(), d = Math.sqrt(dx * dx + dz * dz);
                if (d > 1.0) {
                    double step = Math.min(6.0, d);
                    int nx = (int) Math.floor(p.getX() + dx / d * step), nz = (int) Math.floor(p.getZ() + dz / d * step);
                    BlockPos g = Kit.surface(level, nx, nz);
                    p.teleportTo(level, nx + 0.5, g.getY(), nz + 0.5, Set.of(), 0.0F, 0.0F);
                }
            }
            if (level.getGameTime() % 10 == 0) Families.walkPetForTests(level, mother);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(state[0] != null && said[0] != null, "home, and the household told");
            Quest q = (Quest) state[0][0];
            ServerPlayer p = (ServerPlayer) state[0][1];
            TamableAnimal dog = (TamableAnimal) state[0][2];
            VillageFolkEntity giver = (VillageFolkEntity) state[0][4];
            int purses = giver.purse();
            Kit.log("w03 done: " + q.state + "; " + steps(q) + " purses " + before[0] + " -> " + purses + ", player " + before[1] + " -> " + Market.coinsHeld(p));
            helper.assertFalse(Pets.isLost(dog.getUUID()), "the dog is home");
            helper.assertTrue(q.state == State.DONE && q.coins > 0 && before[0] - purses == q.coins && Market.coinsHeld(p) - before[1] == q.coins,
                "done, and paid out of the household's purse: " + q.state + " " + q.coins);
            Kit.noLeftoverPlayers(level);
        });
    }

    // ============================================================ w04: given up, and the dog let go

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "weave_w04_lost_dog_given_up")
    public static void w04_lost_dog_given_up(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.runAtTickTime(5, () -> {
            Object[] h = household(helper, 1183000);
            VillageFolkEntity mother = (VillageFolkEntity) h[0];
            TamableAnimal dog = (TamableAnimal) h[2];
            UUID id = mother.ownerId();
            Villages.Village v = Villages.get(id);
            // The weave's own round, a morning at a time: before long the dog is off after a rabbit, and its household asks.
            Weave.liveForTests(true);
            long first = QuestRun.day(level);
            Quest q = null;
            int mornings = 0;
            for (int d = 0; d < 80 && q == null; d++) {
                level.setDayTime((first + d) * 24000L + 4000L);
                Weave.tickForTests(level, v);
                q = offered(id, "favour.pet");
                mornings = d + 1;
            }
            Weave.liveForTests(false);
            String noted = Ledger.note(id, "weave.petLost");
            Kit.log("w04 after " + mornings + " mornings: " + (q == null ? "nothing" : q.title + " by " + q.giverName + "; " + steps(q)) + "; noted " + noted);
            helper.assertTrue(q != null && dog.getUUID().toString().equals(q.flag("pet")), "a morning's round sends the dog off, and its household asks: " + mornings);
            helper.assertTrue(Pets.isLost(dog.getUUID()) && Weave.heldForTests(id, dog.getUUID()), "lost, held for the quest");
            helper.assertTrue(Long.toString(QuestRun.day(level)).equals(noted), "the day noted, for the fortnight's gap: " + noted);
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            QuestRun.accept(level, folkOf(level, q.giver), p);
            String gaveUp = QuestRun.abandon(level, q, p);
            Kit.log("w04 given up -> " + gaveUp + "; " + q.state + "; held " + Weave.heldForTests(id, dog.getUUID()) + ", lost " + Pets.isLost(dog.getUUID()));
            helper.assertTrue(q.state == State.ABANDONED, "given up");
            helper.assertTrue(Pets.isLost(dog.getUUID()) && !Weave.heldForTests(id, dog.getUUID()), "let go: it finds its own way home in the morning");
            helper.succeed();
        });
    }

    // ============================================================ fire, flood and the rebuilding

    /** A town of four on clean ground, its first folk the elder, the treasury forty, disasters on. */
    private static List<VillageFolkEntity> disasterTown(GameTestHelper helper, int x) {
        ServerLevel level = helper.getLevel();
        clean(level);
        level.setDayTime(2000);
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        Disasters.onForTests(true);
        BlockPos heart = Kit.surface(level, x, Z);
        ground(level, x - 6, Z - 8, x + 24, Z + 16, heart.getY() - 1);
        List<VillageFolkEntity> folk = new ArrayList<>();
        folk.add(raise(helper, Kit.surface(level, x, Z), "the elder"));
        for (int i = 0; i < 3; i++) folk.add(raise(helper, Kit.surface(level, x - 3 + 2 * i, Z + 3), "folk " + i));
        UUID id = folk.get(0).ownerId();
        for (VillageFolkEntity f : folk) f.removeMatching(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET), 64);
        Villages.electElder(id, folk.get(0), QuestRun.day(level));
        Ledger.addCoins(id, 40);
        return folk;
    }

    // ============================================================ w05: water for the fire

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "weave_w05_water_for_the_fire")
    public static void w05_water_for_the_fire(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1184000;
        List<VillageFolkEntity> folk = disasterTown(helper, x);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, Kit.surface(level, x - 4, Z - 4), new ItemStack(Items.BREAD, 16));
        int gy = Kit.surface(level, x, Z).getY() - 1;
        List<BlockPos> lit = new ArrayList<>();
        final boolean was = holdFire(level);
        helper.runAtTickTime(5, () -> {
            // Eight blocks alight in a row on a timber floor.
            for (int dx = 12; dx <= 19; dx++) {
                BlockPos plank = new BlockPos(x + dx, gy, Z - 5);
                level.setBlock(plank, Blocks.OAK_PLANKS.defaultBlockState(), 3);
                BlockPos f = plank.above();
                level.setBlock(f, BaseFireBlock.getState(level, f), 3);
                lit.add(f);
            }
            FireBrigade.watchForTests(level, id);
            Quest q = urgent(level, v, "town.fire");
            Kit.log("w05 offer: " + (q == null ? "none" : q.title + " by " + q.giverName + ", " + q.coins + " coins from the " + q.payer + "; " + steps(q) + " — " + q.offer));
            helper.assertTrue(q != null && "treasury".equals(q.payer) && "minecraft:water_bucket".equals(q.steps.get(0).item), "water asked for at once: " + q);
            VillageFolkEntity chief = folkOf(level, q.giver);
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            at(p, chief.blockPosition().east(2));
            QuestRun.accept(level, chief, p);
            p.getInventory().add(new ItemStack(Items.WATER_BUCKET));
            p.getInventory().add(new ItemStack(Items.WATER_BUCKET));
            int f0 = fires(level, lit);
            String first = QuestRun.talk(level, chief, p, null);
            int f1 = fires(level, lit);
            Kit.log("w05 the first bucket -> " + first + " | alight " + f0 + " -> " + f1 + "; " + steps(q));
            helper.assertTrue(f1 < f0 && count(p, st -> st.is(Items.BUCKET)) == 1, "poured on the fire, the empty bucket back: " + f0 + " -> " + f1);
            helper.assertTrue(q.step("water2") != null && "water2".equals(q.current().key), "still burning: another bucket asked for: " + steps(q));
            String second = QuestRun.talk(level, chief, p, null);
            int f2 = fires(level, lit);
            Kit.log("w05 the second -> " + second + " | alight " + f1 + " -> " + f2);
            helper.assertTrue(f2 < f1 && count(p, st -> st.is(Items.BUCKET)) == 2 && count(p, st -> st.is(Items.WATER_BUCKET)) == 0, "the second poured: " + f1 + " -> " + f2);
            // The brigade has the rest out.
            for (BlockPos b : lit) if (level.getBlockState(b).is(BlockTags.FIRE)) level.removeBlock(b, false);
            FireBrigade.watchForTests(level, id);
            int treasury0 = Ledger.coins(id), coins0 = Market.coinsHeld(p);
            QuestRun.checkForTests(level, p);
            int treasury1 = Ledger.coins(id), coins1 = Market.coinsHeld(p);
            letFire(level, was);
            Kit.log("w05 the fire out: " + q.state + " (" + q.ending + "); treasury " + treasury0 + " -> " + treasury1 + ", player " + coins0 + " -> " + coins1);
            helper.assertTrue(q.state == State.DONE && q.coins == 6, "done: two buckets poured, paid for two: " + q.state + " " + q.coins);
            helper.assertTrue(treasury0 - treasury1 == 6 && coins1 - coins0 == 6, "out of the treasury: " + treasury0 + " -> " + treasury1);
            helper.succeed();
        });
    }

    // ============================================================ w06: planks for the rebuilding

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "weave_w06_planks_for_the_rebuilding")
    public static void w06_planks_for_the_rebuilding(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1185000;
        List<VillageFolkEntity> folk = disasterTown(helper, x);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        int gy = Kit.surface(level, x, Z).getY() - 1;
        BlockPos anchor = house(level, id, new BlockPos(x + 14, gy + 1, Z + 6));
        Homes.tickForTests(level, v);
        stores(level, id, Kit.surface(level, x - 4, Z - 4), new ItemStack(Items.OAK_PLANKS, 3), new ItemStack(Items.BREAD, 16));
        final boolean was = holdFire(level);
        helper.runAtTickTime(5, () -> {
            int burnt = burnBackWall(helper, level, id, anchor);
            letFire(level, was);
            int put = Rebuilding.workForTests(level, id);
            Kit.log("w06 burnt " + burnt + "; " + put + " put back, waits for '" + Rebuilding.waitsForTests(id) + "'");
            helper.assertTrue(burnt == 6 && put == 3 && Rebuilding.waitsForTests(id).contains("plank"), "three back, then it waits on planks");
            Quest q = urgent(level, v, "town.rebuild");
            Kit.log("w06 offer: " + (q == null ? "none" : q.title + " by " + q.giverName + ", " + q.coins + " coins; " + steps(q) + " — " + q.offer));
            helper.assertTrue(q != null && "planks".equals(q.steps.get(0).item) && q.steps.get(0).count == 4, "the elder asks for the planks it waits on: " + q);
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            QuestRun.accept(level, folkOf(level, q.giver), p);
            p.getInventory().add(new ItemStack(Items.OAK_PLANKS, 4));
            int treasury0 = Ledger.coins(id), coins0 = Market.coinsHeld(p);
            String said = QuestRun.talk(level, folkOf(level, q.giver), p, null);
            int left = stock(level, id, st -> st.is(ItemTags.PLANKS));
            Kit.log("w06 brought -> " + said + " | waiting " + Rebuilding.waitingForTests(id) + ", planks left in the stores " + left + "; treasury " + treasury0 + " -> "
                + Ledger.coins(id) + ", player " + coins0 + " -> " + Market.coinsHeld(p) + "; " + steps(q));
            helper.assertTrue(Rebuilding.waitingForTests(id).isEmpty() && left == 1, "into the stores, and the wall up out of them at once: " + left + " left");
            helper.assertTrue(q.state == State.DONE && treasury0 - Ledger.coins(id) == q.coins && Market.coinsHeld(p) - coins0 == q.coins && q.coins > 0,
                "done, paid out of the treasury: " + q.coins);
            helper.succeed();
        });
    }

    // ============================================================ w07: a flooded household's things

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "weave_w07_things_out_of_the_flood")
    public static void w07_things_out_of_the_flood(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1186000;
        List<VillageFolkEntity> folk = disasterTown(helper, x);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        int gy = Kit.surface(level, x, Z).getY() - 1;
        VillageFolkEntity wife = folk.get(2), husband = folk.get(3);
        wife.life().partnerWith(husband.getUUID(), husband.displayNameCap());
        husband.life().partnerWith(wife.getUUID(), wife.displayNameCap());
        BlockPos anchor = house(level, id, new BlockPos(x + 14, gy + 1, Z + 6));
        Homes.tickForTests(level, v);
        helper.runAtTickTime(5, () -> {
            List<UUID> members = Homes.membersForTests(id, anchor);
            helper.assertTrue(!members.isEmpty(), "a household in the house");
            VillageFolkEntity one = folkOf(level, members.get(0));
            BlockPos chestAt = Families.homeChestForTests(level, one);
            helper.assertTrue(chestAt != null && level.getBlockEntity(chestAt) instanceof Container, "its chest");
            Container chest = (Container) level.getBlockEntity(chestAt);
            chest.clearContent();
            chest.setItem(0, new ItemStack(Items.BOOK, 2));
            chest.setItem(1, new ItemStack(Items.CANDLE, 3));
            chest.setItem(2, new ItemStack(Items.WHITE_WOOL, 5));
            chest.setChanged();
            Weave.floodForTests(id, anchor);
            Quest q = urgent(level, v, "town.flood");
            Kit.log("w07 offer: " + (q == null ? "none" : q.title + " by " + q.giverName + ", " + q.coins + " coins; " + steps(q) + " — " + q.offer));
            helper.assertTrue(q != null && "treasury".equals(q.payer), "the household's things to save: " + q);
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            QuestRun.accept(level, folkOf(level, q.giver), p);
            at(p, chestAt);
            QuestRun.checkForTests(level, p);
            int carried = count(p, st -> questOf(st) == q.id);
            Kit.log("w07 at the chest: " + steps(q) + "; carried " + carried + ", the chest " + count(chest, st -> !st.isEmpty()));
            helper.assertTrue(carried == 10 && count(chest, st -> !st.isEmpty()) == 0 && q.step("things").count == 10, "the chest emptied into the player's arms: " + carried);
            VillageFolkEntity member = folkOf(level, UUID.fromString(q.flag("member")));
            int books0 = count(member, st -> st.is(Items.BOOK) && questOf(st) == 0);
            int treasury0 = Ledger.coins(id), coins0 = Market.coinsHeld(p);
            String said = QuestRun.talk(level, member, p, null);
            int books1 = count(member, st -> st.is(Items.BOOK) && questOf(st) == 0), marked = count(member, st -> questOf(st) == q.id);
            Kit.log("w07 up to " + member.displayNameCap() + " -> " + said + " | books " + books0 + " -> " + books1 + ", still marked " + marked + "; treasury " + treasury0
                + " -> " + Ledger.coins(id) + ", player " + coins0 + " -> " + Market.coinsHeld(p));
            Weave.floodForTests(id, null);
            helper.assertTrue(books1 - books0 == 2 && marked == 0 && count(p, st -> questOf(st) == q.id) == 0, "theirs again, in their own pack: " + books0 + " -> " + books1);
            helper.assertTrue(q.state == State.DONE && treasury0 - Ledger.coins(id) == q.coins && Market.coinsHeld(p) - coins0 == q.coins, "done, out of the treasury: " + q.coins);
            helper.succeed();
        });
    }

    // ============================================================ w17: given up with their things in your arms

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "weave_w17_things_handed_back")
    public static void w17_things_handed_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 1196000;
        List<VillageFolkEntity> folk = disasterTown(helper, x);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        int gy = Kit.surface(level, x, Z).getY() - 1;
        folk.get(2).life().partnerWith(folk.get(3).getUUID(), folk.get(3).displayNameCap());
        folk.get(3).life().partnerWith(folk.get(2).getUUID(), folk.get(2).displayNameCap());
        BlockPos anchor = house(level, id, new BlockPos(x + 14, gy + 1, Z + 6));
        Homes.tickForTests(level, v);
        helper.runAtTickTime(5, () -> {
            List<UUID> members = Homes.membersForTests(id, anchor);
            helper.assertTrue(!members.isEmpty(), "a household in the house");
            BlockPos chestAt = Families.homeChestForTests(level, folkOf(level, members.get(0)));
            helper.assertTrue(chestAt != null && level.getBlockEntity(chestAt) instanceof Container, "its chest");
            Container chest = (Container) level.getBlockEntity(chestAt);
            chest.clearContent();
            chest.setItem(0, new ItemStack(Items.BOOK, 2));
            chest.setItem(1, new ItemStack(Items.CANDLE, 3));
            chest.setChanged();
            Weave.floodForTests(id, anchor);
            Quest q = urgent(level, v, "town.flood");
            helper.assertTrue(q != null, "the household's things to save");
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            QuestRun.accept(level, folkOf(level, q.giver), p);
            p.teleportTo(level, chestAt.getX() + 0.5, chestAt.getY(), chestAt.getZ() + 0.5, Set.of(), 0.0F, 0.0F);
            QuestRun.checkForTests(level, p);
            int carried = count(p, st -> questOf(st) == q.id);
            VillageFolkEntity member = folkOf(level, UUID.fromString(q.flag("member")));
            int books0 = count(member, st -> st.is(Items.BOOK)), candles0 = count(member, st -> st.is(Items.CANDLE));
            String gaveUp = QuestRun.abandon(level, q, p);
            int books1 = count(member, st -> st.is(Items.BOOK) && questOf(st) == 0), candles1 = count(member, st -> st.is(Items.CANDLE) && questOf(st) == 0);
            Kit.log("w17 carried " + carried + "; given up -> " + gaveUp + " | the player still holds " + count(p, st -> questOf(st) == q.id) + "; "
                + member.displayNameCap() + "'s books " + books0 + " -> " + books1 + ", candles " + candles0 + " -> " + candles1);
            Weave.floodForTests(id, null);
            helper.assertTrue(carried == 5 && q.state == State.ABANDONED, "carried out, then given up: " + carried + " " + q.state);
            helper.assertTrue(count(p, st -> questOf(st) == q.id) == 0 && books1 - books0 == 2 && candles1 - candles0 == 3,
                "the household's things handed back to it, never kept: books " + books0 + " -> " + books1 + ", candles " + candles0 + " -> " + candles1);
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    // ============================================================ w08: refugees from the fire

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "weave_w08_refugees_from_the_fire")
    public static void w08_refugees_from_the_fire(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        Disasters.onForTests(true);
        final int ax = 1187000, bx = ax + 300;
        long day = QuestRun.day(level) + 1;
        level.setDayTime(day * 24000L + 2000L);
        Kit.hold(level, ax, Z, 48);
        Kit.prepare(level, ax, Z, 48);
        Kit.hold(level, bx, Z, 24);
        Kit.prepare(level, bx, Z, 24);
        BlockPos a = Kit.surface(level, ax, Z);
        ground(level, ax - 12, Z - 8, ax + 24, Z + 16, a.getY() - 1);
        BlockPos b = flat(level, bx, Z, 16);
        List<VillageFolkEntity> aFolk = new ArrayList<>();
        for (int i = 0; i < 8; i++) aFolk.add(raise(helper, Kit.surface(level, ax - 9 + (i % 4) * 3, Z - 4 + (i / 4) * 3), "of the first town " + i));
        List<VillageFolkEntity> bFolk = new ArrayList<>();
        for (int i = 0; i < 4; i++) bFolk.add(raise(helper, b.offset(-4 + i * 3, 0, 3), "of the next town " + i));
        UUID aid = aFolk.get(0).ownerId(), bid = bFolk.get(0).ownerId();
        helper.assertTrue(!aid.equals(bid), "two towns");
        Villages.Village av = Villages.get(aid);
        VillageFolkEntity wife = aFolk.get(1), husband = aFolk.get(2);
        wife.life().partnerWith(husband.getUUID(), husband.displayNameCap());
        husband.life().partnerWith(wife.getUUID(), wife.displayNameCap());
        BlockPos anchor = house(level, aid, new BlockPos(ax + 14, a.getY(), Z + 6));
        Homes.tickForTests(level, av);
        stores(level, aid, Kit.surface(level, ax - 10, Z - 6), new ItemStack(Items.BREAD, 16));
        final boolean was = holdFire(level);
        List<VillageFolkEntity> out = new ArrayList<>();
        helper.runAtTickTime(5, () -> {
            int burnt = burnBackWall(helper, level, aid, anchor);
            letFire(level, was);
            for (UUID m : Homes.membersForTests(aid, anchor)) {
                VillageFolkEntity f = folkOf(level, m);
                if (f != null && Rebuilding.displacedForTests(f)) out.add(f);
            }
            Kit.log("w08 burnt " + burnt + " of the house; its household, burnt out: " + out.size() + " " + out.stream().map(VillageFolkEntity::displayNameCap).toList()
                + "; waits for '" + Rebuilding.waitsForTests(aid) + "'");
            helper.assertTrue(burnt == 6 && !out.isEmpty(), "the house burnt and its household out of it");
            // Dusk: nowhere in the town to sleep.
            Weave.liveForTests(true);
            level.setDayTime(day * 24000L + 13000L);
            level.updateSkyBrightness();
        });
        helper.onEachTick(() -> {
            if (out.isEmpty() || level.getDayTime() % 24000L < 12500L) return;
            for (VillageFolkEntity f : out) if (f.isAlive() && f.ownerId() != null) Weave.lodgingForTests(f, level);
            if (level.getGameTime() % 10 == 0) Weave.tickForTests(level, av);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(!out.isEmpty(), "set up");
            VillageFolkEntity gone = null;
            for (VillageFolkEntity f : out) if (Newcomers.is(f)) gone = f;
            helper.assertTrue(gone != null, "a household gone as refugees");
            CompoundTag from = gone.getPersistentData().getCompound("mca_weave_from");
            Kit.log("w08 gone: " + out.stream().map(f -> f.displayNameCap() + (Newcomers.is(f) ? " (on the road)" : "")).toList() + "; from " + from + "; colours "
                + gone.style().main() + "/" + gone.style().accent() + "; the first town has " + Villages.headcount(aid) + " left; " + chronicle(aid));
            helper.assertTrue(gone.ownerId() == null && Villages.name(aid).equals(from.getString("town")), "on the road from its old town, in its colours: " + from);
            if (out.contains(wife) && out.contains(husband)) {
                helper.assertTrue(Newcomers.is(wife) && Newcomers.is(husband), "the couple go together");
            }
            helper.assertTrue(Villages.headcount(aid) >= 3, "the town is never emptied: " + Villages.headcount(aid));
            Weave.liveForTests(false);
        });
    }

    // ============================================================ w09: short rations

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "weave_w09_short_rations")
    public static void w09_short_rations(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        level.setDayTime(2000);
        BlockPos heart = heart(level, 1188000, 24, 16);
        List<VillageFolkEntity> folk = town(helper, heart, 3);
        VillageFolkEntity f = folk.get(1);
        UUID id = f.ownerId();
        f.spend(f.purse());
        f.earn(40);
        f.persona().setMood(80, List.of("well fed"));
        Crime.honestyForTests(f, 50);
        int before = Crime.temptationForTests(level, f);
        Weave.rationsForTests(id, true);
        int on = Crime.temptationForTests(level, f);
        Weave.rationsForTests(id, false);
        int after = Crime.temptationForTests(level, f);
        Crime.honestyForTests(f, null);
        Kit.log("w09 temptation: " + before + " -> " + on + " on short rations -> " + after + " after");
        helper.assertTrue(on - before >= 10 && after == before, "short rations tempt a contented folk: " + before + " -> " + on + " -> " + after);
        helper.succeed();
    }

    // ============================================================ the auction and the shop

    /** A town of four in the Iron Age that holds auctions, its stores one marked chest of these. */
    private static List<VillageFolkEntity> marketTown(GameTestHelper helper, int x, ItemStack... goods) {
        ServerLevel level = helper.getLevel();
        clean(level);
        level.setDayTime(2000);
        BlockPos heart = heart(level, x, 32, 20);
        List<VillageFolkEntity> folk = town(helper, heart, 4);
        UUID id = folk.get(0).ownerId();
        Villages.ageForTests(id, Villages.Age.IRON);
        Auctions.fromForTests(3);
        stores(level, id, heart.offset(4, 0, -4), goods);
        for (VillageFolkEntity f : folk) f.setJob(StationTask.FARM);
        return folk;
    }

    // ============================================================ w10: never stolen, never forged; the tailor's finest

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "weave_w10_nothing_stolen_sold")
    public static void w10_nothing_stolen_sold(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack coat = masterMade(Garment.LONG_COAT.dyed(RED), "Ada");
        ItemStack plain = Garment.LONG_COAT.dyed(BLUE);
        ItemStack brooch = new ItemStack(Garment.BROOCH.item());
        ItemStack stolen = new ItemStack(Items.SPYGLASS);
        CustomData.update(DataComponents.CUSTOM_DATA, stolen, t -> t.putInt("mca_stolen", 7));
        ItemStack forged = new ItemStack(McAssistantMod.FORGED_COIN.get(), 3);
        List<VillageFolkEntity> folk = marketTown(helper, 1189000, coat, plain, brooch, stolen, forged, new ItemStack(Items.BREAD, 16));
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        helper.assertTrue(Weave.unsellable(stolen) && Weave.unsellable(forged) && !Weave.unsellable(coat), "stolen goods and forged coin are never to be sold");
        helper.assertTrue(Weave.lotForTests(level, id, coat) && Weave.lotForTests(level, id, brooch) && !Weave.lotForTests(level, id, plain)
            && !Weave.lotForTests(level, id, stolen), "the master's coat and the brooch for the auction; not the plain coat, nor the stolen glass");
        List<String> lots = Auctions.openForTests(level, v);
        Kit.log("w10 the town's lots: " + lots);
        helper.assertTrue(lots.stream().anyMatch(l -> l.contains("coat")) && lots.stream().anyMatch(l -> l.contains("brooch")), "the coat and the brooch go under the hammer: " + lots);
        helper.assertTrue(lots.stream().noneMatch(l -> l.contains("spyglass")), "the stolen spyglass never: " + lots);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        p.setItemInHand(InteractionHand.MAIN_HAND, stolen.copy());
        String put = Auctions.putUp(level, v, p, 0);
        Kit.log("w10 a player puts up the stolen glass -> " + put);
        helper.assertTrue(put.contains("stolen") && p.getMainHandItem().is(Items.SPYGLASS), "the town will not sell it for a player either: " + put);
        List<Budget.Offer> offers = Budget.forSale(level, id, 40);
        Kit.log("w10 for sale: " + offers.stream().map(Budget.Offer::name).toList());
        helper.assertTrue(offers.stream().noneMatch(o -> o.item() == McAssistantMod.FORGED_COIN.get()), "no forged coin for sale");
        Auctions.fromForTests(-1);
        helper.succeed();
    }

    // ============================================================ w11: won, and worn

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "weave_w11_won_and_worn")
    public static void w11_won_and_worn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = marketTown(helper, 1190000, new ItemStack(Items.BREAD, 16));
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        VillageFolkEntity bea = folk.get(1);
        Fashion.coloursForTests(bea, DyeColor.WHITE, DyeColor.GRAY);
        Fashion.setTrend(level, v, BLUE, Garment.WOOL_SCARF, "Ada", "the test's");
        boolean before = Fashion.inFashion(bea);
        ItemStack coat = masterMade(Garment.LONG_COAT.dyed(RED), "Ada");
        Weave.deliverForTests(level, bea, coat, 30);
        ItemStack on = bea.style().worn(Garment.Slot.BODY);
        CustomData d = on.get(DataComponents.CUSTOM_DATA);
        boolean marked = d != null && d.copyTag().getInt("mca_auction_won") > 0;
        boolean remembers = false;
        for (Persona.Memory m : bea.persona().memories()) remembers |= m.text().contains("auction");
        Kit.log("w11 Bea wears " + on.getHoverName().getString() + " (won " + marked + "); in fashion " + before + " -> " + Fashion.inFashion(bea) + "; remembers " + remembers);
        helper.assertTrue(Garment.of(on) == Garment.LONG_COAT && marked, "the coat won put on at once: " + on);
        helper.assertTrue(!before && Fashion.inFashion(bea), "the talk of the town this season, red among the blue");
        helper.assertTrue(count(bea, st -> Garment.of(st) == Garment.LONG_COAT) == 0 && remembers, "worn, not packed; and remembered");
        Auctions.fromForTests(-1);
        helper.succeed();
    }

    // ============================================================ w12: dressed for the office

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "weave_w12_dressed_for_the_office")
    public static void w12_dressed_for_the_office(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> folk = marketTown(helper, 1191000, new ItemStack(Items.BREAD, 16));
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        VillageFolkEntity elder = folk.get(0), lib = folk.get(1), constable = folk.get(2), newcomer = folk.get(3);
        Villages.electElder(id, elder, QuestRun.day(level));
        constable.setJob(StationTask.GUARD);
        LibraryRecords.shelf(id).librarian = lib.getUUID();
        LibraryRecords.touch();
        lib.spend(lib.purse());
        lib.earn(40);
        // The season's thing is a top hat, beyond a librarian's purse: its office wants a waistcoat in the colour.
        Fashion.setTrend(level, v, RED, Garment.TOP_HAT, "Ada", "the test's");
        Fashion.wantForTests(level, lib);
        Kit.log("w12 roles: " + elder.displayNameCap() + " " + Weave.roleForTests(elder) + ", " + lib.displayNameCap() + " " + Weave.roleForTests(lib) + ", "
            + constable.displayNameCap() + " " + Weave.roleForTests(constable) + "; the librarian wants " + lib.style().wants() + " in " + lib.style().wantColour()
            + "; setters " + Fashion.settersForTests(level, v));
        helper.assertTrue("librarian".equals(Weave.roleForTests(lib)) && lib.style().wants() == Garment.WAISTCOAT && lib.style().wantColour() == RED,
            "the librarian wants its waistcoat, in the season's red: " + lib.style().wants());
        helper.assertTrue("constable".equals(Weave.roleForTests(constable)) && "auctioneer".equals(Weave.roleForTests(elder)), "the constable and the auctioneer");
        helper.assertTrue(Weave.admiredForTests(elder).contains("the auctioneer") && Weave.admiredForTests(lib).contains("the librarian"),
            "the town looks to them for its look: " + Weave.admiredForTests(elder) + "; " + Weave.admiredForTests(lib));
        // A player elected leader: the old elder its steward, wanting a long coat of the season's red, and the town's eye on it.
        UUID player = UUID.randomUUID();
        long day = QuestRun.day(level);
        Villages.electElder(id, player, "Steve", day, null);
        Ledger.note(id, "civic.leader", player + "|Steve|" + day);
        Ledger.note(id, "civic.steward", elder.getUUID() + "|" + elder.displayNameCap());
        elder.spend(elder.purse());
        elder.earn(40);
        Garment coat = Weave.roleGarmentForTests(elder);
        String admired = Weave.admiredForTests(elder);
        Kit.log("w12 the steward " + elder.displayNameCap() + ": " + Weave.roleForTests(elder) + ", wants " + coat + ", admired " + admired);
        helper.assertTrue("steward".equals(Weave.roleForTests(elder)) && coat == Garment.LONG_COAT && admired.startsWith("1.5") && admired.contains("steward"),
            "the leader's steward dresses for it, and sets the fashion: " + coat + " " + admired);
        // A newcomer from the town next door: in its old town's colours, and quick to come round to the new town's look.
        BlockPos there = heart(level, 1191300, 16, 8);
        VillageFolkEntity other = raise(helper, there, "of the next town");
        Villages.Village from = Villages.get(other.ownerId());
        helper.assertTrue(from != null && !from.id().equals(id), "a town of its own next door");
        level.setDayTime(level.getDayTime() + 24000L);
        Heraldry.chooseForTests(level, from);
        Weave.oldColoursForTests(newcomer, from);
        Heraldry.Design arms = Heraldry.design(from.id());
        CompoundTag tag = newcomer.getPersistentData().getCompound("mca_weave_from");
        double fit = Weave.fitInForTests(newcomer);
        Kit.log("w12 the newcomer: " + tag + ", colours " + newcomer.style().main() + "/" + newcomer.style().accent() + ", the old town's arms "
            + (arms == null ? "none" : arms.field() + " " + arms.layers()) + "; fit in x" + fit);
        helper.assertTrue(Villages.name(from.id()).equals(tag.getString("town")) && fit == 2.0, "from the old town, and keen to fit in: " + tag + " " + fit);
        if (arms != null) helper.assertTrue(newcomer.style().main() == arms.field().getId(), "in its old town's colours: " + newcomer.style().main());
        Auctions.fromForTests(-1);
        helper.succeed();
    }

    // ============================================================ w13: more guards, in their kit

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "weave_w13_more_guards_in_kit")
    public static void w13_more_guards_in_kit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        level.setDayTime(2000);
        BlockPos heart = heart(level, 1192000, 24, 16);
        List<VillageFolkEntity> folk = town(helper, heart, 3);
        UUID id = folk.get(0).ownerId();
        VillageFolkEntity kitted = folk.get(1), bare = folk.get(2);
        kitted.setJob(StationTask.GUARD);
        bare.setJob(StationTask.GUARD);
        kitted.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        kitted.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        for (EquipmentSlot s : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) bare.setItemSlot(s, ItemStack.EMPTY);
        Kit.log("w13 kitted " + Weave.kittedForTests(kitted) + ", bare " + Weave.kittedForTests(bare) + "; the pledge counts " + Weave.guardsForTests(id));
        helper.assertTrue(Weave.kittedForTests(kitted) && !Weave.kittedForTests(bare) && Weave.guardsForTests(id) == 1, "only the guard in its kit counts");
        helper.succeed();
    }

    // ============================================================ w14: the gazette's front page

    @GameTest(template = EMPTY, timeoutTicks = 40, batch = "weave_w14_front_page")
    public static void w14_front_page(GameTestHelper helper) {
        List<String> entries = List.of("§lBorn§r\nNobody.", "§lDied§r\nOld Tam died in his sleep, aged eighty-one.", "§lBuilt§r\nThe smithy went up.",
            "§lThe elder's order§r\nNone given.", "§lPrices§r\nBread 1, wool 2.",
            "§lFire, flood and drought§r\nThe fire burnt 14 blocks of Ada's house on day 12; the bucket chain had it out.",
            "§lThe watch and the court§r\nBram was found guilty of theft at the market.");
        List<String> front = Weave.frontPageForTests(entries);
        String headline = Weave.headlineForTests(entries);
        Kit.log("w14 front page: " + front.stream().map(e -> e.substring(2, e.indexOf("§r"))).toList() + "; headline: " + headline.trim());
        helper.assertTrue(front.get(0).startsWith("§lFire, flood") && front.get(1).startsWith("§lDied") && front.get(2).startsWith("§lThe watch"),
            "the biggest story leads: " + front);
        List<String> tail = front.subList(front.size() - 2, front.size());
        helper.assertTrue(tail.stream().anyMatch(e -> e.startsWith("§lBorn")) && tail.stream().anyMatch(e -> e.startsWith("§lThe elder's order")),
            "nothing to say comes last: " + tail);
        helper.assertTrue(headline.contains("The fire burnt 14 blocks"), "the headline is the fire's: " + headline);
        helper.assertTrue(Weave.headlineForTests(List.of("§lBorn§r\nNobody.", "§lPrices§r\nBread 1.")).isEmpty(), "no headline on a quiet day");
        helper.succeed();
    }

    // ============================================================ w15: the library's new books

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "weave_w15_the_new_books")
    public static void w15_the_new_books(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clean(level);
        level.setDayTime(24000L * 20 + 3000L);
        BlockPos heart = heart(level, 1193000, 24, 16);
        List<VillageFolkEntity> folk = town(helper, heart, 4);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        long day = QuestRun.day(level);
        String town = Villages.name(id);
        Villages.tell(id, day - 6, "The great flood of day " + (day - 5) + ": the river came up over the low ground, 140 cells under water, 2 houses flooded");
        Villages.tell(id, day - 5, "The river began to go down: the rain stopped");
        Villages.tell(id, day - 4, "the fire burnt 14 blocks of the smithy: it will be rebuilt from the stores");
        Villages.tell(id, day - 3, "The smithy was rebuilt after the fire of day " + (day - 3) + ": 14 blocks put back out of the stores");
        Ledger.buried(id, new Ledger.Grave("Wick Hollow", day - 400, day - 2, "lost in the caves", "", "", "CAVE"));
        Villages.tell(id, day - 2, "Wick Hollow was lost in the caves, and never came home");
        Weave.workDoneForTests(id, "BRIDGE", day - 1, 7, 120);
        Ledger.note(id, "auction.sales", (day - 1) + "|diamond|48|Mara the smith|" + town + "|from the stores");
        List<String> ideas = Library.ideasForTests(level, v);
        Kit.log("w15 the town could write: " + ideas);
        for (String want : new String[]{ "history:FLOOD:", "history:GREATFIRE:", "life:Wick Hollow", "poem:WORKS:the bridge", "poem:AUCTION:Mara the smith" }) {
            helper.assertTrue(ideas.stream().anyMatch(i -> i.startsWith(want)), "a book of " + want + " in mind: " + ideas);
        }
        List<String> caves = Weave.notesForTests(level, v, StationTask.CAVE, folk.get(1));
        List<String> watch = Weave.notesForTests(level, v, StationTask.GUARD, folk.get(2));
        Kit.log("w15 the cave dwellers' notes: " + caves + " | the watch's: " + watch);
        helper.assertTrue(caves.stream().anyMatch(n -> n.contains("Light every fifteen blocks")) && caves.stream().anyMatch(n -> n.contains("of us below")),
            "the cave dwellers' book: light every fifteen blocks, and the one lost below");
        helper.assertTrue(watch.stream().anyMatch(n -> n.contains("footprints wash out")), "the watch's book: its tips");
        helper.succeed();
    }

    // ============================================================ w16: the cave team's finds past the lodge's six, at auction

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "weave_w16_finds_past_the_six")
    public static void w16_finds_past_the_six(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CaveDwellerGameTests.Town t = CaveDwellerGameTests.town(helper, 1194000, Villages.Age.IRON, StationTask.CAVE, StationTask.CAVE, StationTask.MINE);
        UUID id = t.village();
        BlockPos ground = Kit.surface(level, t.heart().getX() - 20, t.heart().getZ() - 24);
        Showcase.stage(level, ground.getX() - 8, ground.getX() + 8, ground.getZ() - 8, ground.getZ() + 12, ground.getY());
        BuildGoal.stamp(level, Lodge.STRUCTURE, ground, Direction.NORTH, 13, Showcase.painter(Showcase.SPRUCE));
        Ledger.built(id, Lodge.STRUCTURE, ground, Direction.NORTH);
        BlockPos hall = Lodge.hall(id);
        helper.assertTrue(hall != null, "the Delvers' Lodge stands");
        // Six kinds of find for the wall, two of each in the stores (the wall never takes the last), and a seventh it has no room for.
        Item[] six = { Items.DIAMOND, Items.EMERALD, Items.GOLDEN_APPLE, Items.NAME_TAG, Items.SADDLE, Items.RAW_GOLD };
        List<ItemStack> goods = new ArrayList<>();
        for (Item k : six) goods.add(new ItemStack(k, 2));
        goods.add(new ItemStack(Items.AMETHYST_SHARD, 2));
        goods.add(new ItemStack(Items.ITEM_FRAME, 12));
        goods.add(new ItemStack(Items.PAPER, 64));
        goods.add(new ItemStack(Items.BOOK, 3));
        goods.add(new ItemStack(Items.BREAD, 64));
        CaveDwellerGameTests.fill(t, goods.toArray(new ItemStack[0]));
        long day = QuestRun.day(level);
        for (Item k : six) Lodge.broughtUp(id, new ItemStack(k), "Ada", "the big cave east", day);
        Lodge.broughtUp(id, new ItemStack(Items.AMETHYST_SHARD), "Bram", "the crystal cave", day);
        VillageFolkEntity d = t.folk().get(0);
        d.moveTo(hall.getX() + 0.5, hall.getY(), hall.getZ() + 0.5, 0.0F, 0.0F);
        for (int i = 0; i < 10; i++) Lodge.tick(level, t.v(), day);
        ItemStack shard = new ItemStack(Items.AMETHYST_SHARD);
        boolean lot = Weave.lotForTests(level, id, shard), diamond = Weave.lotForTests(level, id, new ItemStack(Items.DIAMOND));
        String from = Weave.provenanceForTests(level, id, shard);
        Auctions.fromForTests(3);
        List<String> lots = Auctions.openForTests(level, t.v());
        Auctions.fromForTests(-1);
        Kit.log("w16 the wall full; the amethyst a lot " + lot + " (" + from + "), the diamond (on the wall) " + diamond + "; the town's lots " + lots);
        helper.assertTrue(lot && !diamond, "the find the full wall has no room for goes to auction; the wall's own do not, by the weave");
        helper.assertTrue(from != null && from.contains("Bram") && from.contains("the crystal cave") && from.contains("no room"), "and the auction says whose find it was: " + from);
        helper.assertTrue(lots.stream().anyMatch(l -> l.contains("amethyst")), "under the hammer: " + lots);
        helper.succeed();
    }
}
