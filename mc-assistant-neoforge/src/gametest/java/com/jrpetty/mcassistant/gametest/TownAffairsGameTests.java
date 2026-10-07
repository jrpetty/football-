package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Assemblies;
import com.jrpetty.mcassistant.entity.Civics;
import com.jrpetty.mcassistant.entity.DoorWays;
import com.jrpetty.mcassistant.entity.Favours;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Petitions;
import com.jrpetty.mcassistant.entity.Post;
import com.jrpetty.mcassistant.entity.PublicFund;
import com.jrpetty.mcassistant.entity.Quarters;
import com.jrpetty.mcassistant.entity.SearchParties;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.TownLife;
import com.jrpetty.mcassistant.entity.TownMeeting;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wardens;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Districts;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Town life and governance (entity/Civics and the rest): the post, petitions, the town meeting, the
 * quarters' wardens, the public works fund, search parties and good turns between neighbours.
 *
 * <ul>
 * <li><b>tg01</b>: two towns. A folk writes to its friend in the other town on a sheet of its stores' paper,
 *     and a player posts a book and quill ("Dear Quillan,") at the same counter; a carrier setting out for
 *     the other town takes both in its bag, leaves them at that town's counter, and its postman walks them
 *     round; read, the friend remembers it and the player's letter is answered, in the book, back the way it
 *     came, and handed over at the counter it was posted at.</li>
 * <li><b>tg02</b>: a house with no seat near it: a petition for a bench by its door; neighbours sign it (a
 *     rival will not); the council puts it on the town's works, and a hand makes a bench of the stores' planks.</li>
 * <li><b>tg03</b>: the town meeting: the elder's account of the week (the treasury, what went up, the birth,
 *     the death, what is next), two folk have their say; held, the chronicle has it and those who came are
 *     the happier.</li>
 * <li><b>tg04</b>: a warden for the homes quarter; at a stop of its round a blocked door is sent to the works and
 *     mended, a dark street wants a lamp (and gets one, of the stores' fence and torch), litter goes to the
 *     stores; and two neighbours at odds are talked round.</li>
 * <li><b>tg05</b>: the statue fund: a player's coins and a generous folk's savings go into it; at sixty it is
 *     raised, the coins go to the treasury and the statue onto the build list; built, the givers' names go up
 *     on a sign before it.</li>
 * <li><b>tg06</b>: a folk not seen for a day, stuck in a pit out past the town: its partner and friend go out,
 *     find it (calling its name), cut it a step out and bring it home; the chronicle tells it.</li>
 * <li><b>tg07</b>: good turns: a bite borrowed (by a folk that missed a meal) and paid back, a tool lent and handed back, a load carried to
 *     the stores, and five good turns make a good neighbour.</li>
 * </ul>
 *
 * <p>Each runs on its own ground in the band x 700,000 to 719,999, z 50,000, in a batch of its own, calling the
 * town's logic directly where it can and logging what it sees.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TownAffairsGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 50000;

    // ---------------------------------------------------------------- ground and stores

    /** Flat grass round here, clear air above it. Returns the ground's top (the first free block). */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        return flatAt(level, cx - r, cx + r, cz - r, cz + r, Kit.surface(level, cx, cz).getY());
    }

    private static BlockPos flatAt(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 20; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos((x0 + x1) / 2, y, (z0 + z1) / 2);
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

    private static int count(VillageFolkEntity f, java.util.function.Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && what.test(s)) n += s.getCount();
        return n;
    }

    // ============================================================ tg01: the post

    /**
     * Two towns, two hundred and twenty blocks apart, each with a post office. Ash of the west town is fond of
     * Quillan of the east: it writes to it on a sheet of the west's paper. A player posts a book and quill
     * "Dear Quillan," at the west counter. A folk of the west setting out for the east takes both letters in
     * its bag and leaves them at the east counter; the east's postman walks them round to Quillan, who reads
     * them: it remembers Ash's, and answers the player's in the player's own book. The answer goes into the
     * same carrier's bag at the east (the post for home), out at the west counter, and into the player's
     * hands when they right-click it with nothing in hand.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "tg01_post")
    public static void tg01_post(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int ax = 700000, bx = 700220;
        for (int x : new int[]{ ax, bx }) {
            Kit.hold(level, x, Z, 30);
            Kit.prepare(level, x, Z, 30);
        }
        BlockPos a = flat(level, ax, Z, 22), b = flat(level, bx, Z, 22);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 3000L);
        List<VillageFolkEntity> west = raise(helper, level, a, 3), east = raise(helper, level, b, 3);
        UUID wa = west.get(0).ownerId(), ea = east.get(0).ownerId();
        helper.assertTrue(wa != null && ea != null && !wa.equals(ea), "two towns: " + wa + " and " + ea);
        Villages.Village vw = Villages.get(wa), ve = Villages.get(ea);
        VillageFolkEntity ash = west.get(0), carrier = west.get(1), quillan = east.get(0);
        quillan.rename("Quillan");
        ash.life().feel(quillan.getUUID(), quillan.displayNameCap(), 50);
        for (int i = 0; i < 2; i++) {
            BlockPos heart = i == 0 ? a : b;
            UUID id = i == 0 ? wa : ea;
            BlockPos anchor = heart.offset(0, 0, -12);
            BuildGoal.stamp(level, Post.STRUCTURE, anchor, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, Post.STRUCTURE, anchor, Direction.NORTH);
        }
        Container westStores = chestAt(level, a.offset(3, 0, 3), new ItemStack(Items.PAPER, 4));
        chestAt(level, b.offset(3, 0, 3), new ItemStack(Items.PAPER, 4));
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(
            Filterable.passThrough("Dear Quillan,\nHow are you getting on? I hear the east has a post office now."))));
        CompoundTag[] letters = new CompoundTag[2];
        String[] posted = { "" };
        int[] paperLeft = { 0 };
        int[] phase = { 0 };
        long[] mark = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t < 10) return;
            switch (phase[0]) {
                case 0 -> {
                    Post.tickForTests(level, vw);
                    Post.tickForTests(level, ve);
                    List<String> away = Post.elsewhereForTests(ash, level.getGameTime());
                    Kit.log("tg01 Ash's friends and family elsewhere: " + away);
                    helper.assertTrue(away.stream().anyMatch(s -> s.startsWith("Quillan")), "Ash knows Quillan lives in the other town: " + away);
                    int paper = Market.stock(level, wa, s -> s.is(Items.PAPER));
                    letters[0] = Post.writeForTests(level, ash, quillan);
                    helper.assertTrue(letters[0] != null, "Ash writes to Quillan");
                    paperLeft[0] = Market.stock(level, wa, s -> s.is(Items.PAPER));
                    helper.assertTrue(paperLeft[0] == paper - 1, "on a sheet of the west's paper");
                    you.setItemInHand(InteractionHand.MAIN_HAND, book);
                    posted[0] = Post.counter(level, vw, you);
                    Kit.log("tg01 the player at the west counter: " + posted[0]);
                    helper.assertTrue(posted[0].startsWith("Posted to Quillan"), "the player's letter is posted to Quillan: " + posted[0]);
                    helper.assertTrue(you.getMainHandItem().isEmpty(), "the book is handed in");
                    List<CompoundTag> waiting = Post.counterForTests(wa);
                    helper.assertTrue(waiting.size() == 2, "two letters wait at the west counter: " + waiting.size());
                    letters[1] = waiting.stream().filter(c -> !c.getString("player").isEmpty()).findFirst().orElse(null);
                    helper.assertTrue(letters[1] != null, "the player's letter is among them");
                    // A folk of the west sets out for the east: the post for the east goes in its bag.
                    Post.setOutForTests(carrier, vw, ve);
                    Post.tickForTests(level, vw);
                    helper.assertTrue(Post.heldForTests(carrier, true) == 2, "both letters in the carrier's bag: " + Post.heldForTests(carrier, true));
                    // ...and walks them over (the road is the caravans' business): out at the east counter, and round.
                    carrier.teleportTo(b.getX() + 2.5, b.getY(), b.getZ() + 2.5);
                    Post.tickForTests(level, ve);
                    String w0 = Post.whereForTests(letters[0]);
                    Kit.log("tg01 at the east: Ash's letter is " + w0 + "; the player's " + Post.whereForTests(letters[1]));
                    helper.assertTrue(w0.startsWith("bag:") || w0.startsWith("office:" + ea) || w0.startsWith("hands:"),
                        "Ash's letter is at the east counter, or out on its round: " + w0);
                    helper.assertFalse(w0.equals("bag:" + carrier.getUUID()), "out of the carrier's bag");
                    Post.setOutForTests(carrier, null, null);              // home again (by the time the answer is written)
                    mark[0] = t;
                    phase[0] = 1;
                }
                case 1 -> {
                    String w0 = Post.whereForTests(letters[0]), w1 = Post.whereForTests(letters[1]);
                    if (t % 40 == 0) {
                        VillageFolkEntity pm = Post.postmanForTests(level, ea);
                        Kit.log("tg01 tick " + t + ": Ash's " + w0 + ", the player's " + w1 + "; postman " + (pm == null ? "-" : pm.displayNameCap()
                            + " at " + pm.blockPosition().toShortString() + " round " + Post.onRoundForTests(pm)) + "; Quillan at " + quillan.blockPosition().toShortString());
                        if (w0.startsWith("office:") || w1.startsWith("office:")) Post.tickForTests(level, ve);    // the next round
                    }
                    // In Quillan's hands (or read already: the town looks at its readers every few seconds).
                    String mine = "hands:" + quillan.getUUID();
                    boolean both = (w0.equals(mine) || w0.equals("read")) && (w1.equals(mine) || w1.equals("read"));
                    if (!both) {
                        if (t - mark[0] > 1200) helper.fail("tg01 the letters never reached Quillan: " + w0 + ", " + w1);
                        return;
                    }
                    Post.readForTests(level, quillan);
                    Post.readForTests(level, quillan);
                    helper.assertTrue("read".equals(Post.whereForTests(letters[0])) && "read".equals(Post.whereForTests(letters[1])), "both read");
                    boolean remembers = quillan.persona().memories().stream().anyMatch(m -> m.text().contains(ash.displayNameCap()));
                    Kit.log("tg01 Quillan remembers: " + quillan.persona().memories().stream().map(m -> m.text()).toList()
                        + "; its card: " + Civics.cardLine(quillan));
                    helper.assertTrue(remembers, "Quillan remembers Ash's letter");
                    helper.assertTrue(Civics.gladForTests(quillan, "letter"), "and is the happier for it: " + quillan.persona().moodWhy());
                    // The answer to the player: posted at the east, for the west.
                    CompoundTag answer = Post.answerForTests(you.getUUID());
                    helper.assertTrue(answer != null, "Quillan answers the player's letter, by post");
                    helper.assertTrue(answer.getString("toTown").equals(wa.toString()), "back to the town it was posted in");
                    // The carrier out to the east again: there, it takes the post for home; home, it leaves it at the west counter.
                    Post.setOutForTests(carrier, vw, ve);
                    carrier.teleportTo(b.getX() + 2.5, b.getY(), b.getZ() + 2.5);
                    Post.tickForTests(level, ve);
                    helper.assertTrue(Post.whereForTests(answer).equals("bag:" + carrier.getUUID()), "the answer in the carrier's bag: " + Post.whereForTests(answer));
                    carrier.teleportTo(a.getX() + 2.5, a.getY(), a.getZ() + 2.5);
                    Post.tickForTests(level, vw);
                    helper.assertTrue(Post.whereForTests(answer).equals("office:" + wa), "at the west counter: " + Post.whereForTests(answer));
                    you.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    String collected = Post.counter(level, vw, you);
                    ItemStack got = ItemStack.EMPTY;
                    for (int i = 0; i < you.getInventory().getContainerSize(); i++) {
                        if (you.getInventory().getItem(i).is(Items.WRITTEN_BOOK)) got = you.getInventory().getItem(i);
                    }
                    WrittenBookContent content = got.get(DataComponents.WRITTEN_BOOK_CONTENT);
                    String text = content == null ? "" : content.pages().stream().map(p -> p.raw().getString()).reduce("", String::concat);
                    Kit.log("tg01 at the west counter: " + collected + " — it reads: " + text.replace('\n', '/'));
                    helper.assertTrue(content != null && content.author().equals("Quillan"), "a letter from Quillan, in the player's hands");
                    helper.assertTrue(text.contains("Dear " + you.getName().getString()) && text.contains("You asked how I am"),
                        "written to the player, answering what they asked: " + text);
                    helper.assertTrue(Market.stock(level, wa, s -> s.is(Items.PAPER)) == paperLeft[0], "no more of the west's paper used: the answer is in the player's own book");
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    // ============================================================ tg02: a petition

    /**
     * A house with three folk in it and no seat anywhere near its door: Ash gets up a petition for a bench by
     * it. Its housemates put their names to it; a folk who cannot abide Ash will not. With names enough the
     * council puts it on the town's works, and a hand makes the bench of two of the stores' planks: a stair
     * the right way up by the door, its back to the house. Done, it is in the chronicle and on the board.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tg02_petition")
    public static void tg02_petition(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 702000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 32);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 3000L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 8);
        UUID id = folk.get(0).ownerId();
        BlockPos anchor = heart.offset(0, 0, 20);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            BuildGoal.stamp(level, "house", anchor, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", anchor, Direction.NORTH);
            VillageFolkEntity ash = folk.get(1), bea = folk.get(2), cal = folk.get(3), dun = folk.get(4);
            for (VillageFolkEntity f : List.of(ash, bea, cal)) helper.assertTrue(f.claimBedNear(anchor), f.displayNameCap() + " takes a bed in the house");
            dun.life().feel(ash.getUUID(), ash.displayNameCap(), -60);
            Container stores = chestAt(level, heart.offset(-3, 0, -3), new ItemStack(Items.OAK_PLANKS, 2));
            int planks = Market.stock(level, id, s -> s.is(ItemTags.PLANKS)), stairs = Market.stock(level, id, s -> s.is(ItemTags.WOODEN_STAIRS));
            String g = Petitions.grievanceForTests(level, ash);
            Kit.log("tg02 Ash's grievance: " + g);
            helper.assertTrue(g != null && g.startsWith("BENCH"), "no seat near its door: a bench wanted: " + g);
            int n = Petitions.raiseForTests(level, ash);
            helper.assertTrue(n > 0, "Ash gets up a petition");
            helper.assertTrue(Petitions.raiseForTests(level, bea) < 0, "Bea's grievance is the same one: not petitioned twice");
            helper.assertTrue(Petitions.signForTests(bea, n) && Petitions.signForTests(cal, n), "its housemates sign");
            helper.assertFalse(Petitions.signForTests(dun, n), "a rival will not");
            String state = Petitions.stateForTests(id, n);
            Kit.log("tg02 the petition: " + state + " (needs " + Petitions.needed(id) + ")");
            helper.assertTrue(state.startsWith("open 3"), "open with three names: " + state);
            List<String> board = Civics.board(level, id);
            helper.assertTrue(board.stream().anyMatch(l -> l.contains("Petitions:") && l.contains("a bench")), "on the board: " + board);
            helper.assertTrue(Petitions.councilForTests(level, id) == 1, "the council hears it");
            helper.assertTrue(Petitions.stateForTests(id, n).startsWith("approved"), "and puts it on the works: " + Petitions.stateForTests(id, n));
            BlockPos at = Petitions.atForTests(id, n);
            int made = Petitions.workForTests(level, v);
            BlockState bench = level.getBlockState(at);
            Kit.log("tg02 the works: " + made + "; at " + at.toShortString() + " " + bench + "; planks left " + Market.stock(level, id, s -> s.is(ItemTags.PLANKS)));
            helper.assertTrue(bench.getBlock() instanceof StairBlock && bench.getValue(StairBlock.HALF) == Half.BOTTOM, "a bench by the door: " + bench);
            helper.assertTrue(bench.getValue(StairBlock.FACING) == Direction.NORTH, "its back to the house: " + bench.getValue(StairBlock.FACING));
            helper.assertTrue(Market.stock(level, id, s -> s.is(ItemTags.PLANKS)) == planks - 2
                || Market.stock(level, id, s -> s.is(ItemTags.WOODEN_STAIRS)) == stairs - 1, "made of the stores' two planks (or a stair put by)");
            helper.assertTrue(Petitions.stateForTests(id, n).startsWith("done"), "done: " + Petitions.stateForTests(id, n));
            helper.assertTrue(chronicle(id).contains("the bench " + ash.displayNameCap() + "'s petition asked for"), "in the chronicle: " + chronicle(id));
            helper.assertTrue(Petitions.grievanceForTests(level, ash) == null || !Petitions.grievanceForTests(level, ash).startsWith("BENCH"),
                "Ash has its seat now: " + Petitions.grievanceForTests(level, ash));
            helper.assertTrue(stores != null, "the stores");
            helper.succeed();
        });
    }

    // ============================================================ tg03: the town meeting

    /**
     * A town of six with twenty-five coins in its treasury, a well that went up this week, a birth and a
     * death in its chronicle, a grump and a curious soul among its folk. The meeting's script: the treasury,
     * the well, the birth, the death, what is next, and two folk having their say. Called, they gather, it is
     * held, the chronicle has its summary, and those who came are the happier for it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6400, batch = "tg03_meeting")
    public static void tg03_meeting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 704000;
        Kit.hold(level, x, Z, 36);
        Kit.prepare(level, x, Z, 36);
        BlockPos heart = flat(level, x, Z, 28);
        long day = level.getDayTime() / 24000L + 3;
        level.setDayTime(day * 24000L + 3000L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 6);
        UUID id = folk.get(0).ownerId();
        Ledger.takeCoins(id, Ledger.coins(id));
        Ledger.addCoins(id, 25);
        Villages.tell(id, day - 1, "the well went up");
        Villages.tell(id, day - 2, "Ada and Bert had a child, Cora");
        Villages.tell(id, day - 3, "Old Tom died, aged 80, of old age");
        folk.get(2).life().setTraitsForTests(Social.Trait.GRUMPY, Social.Trait.HARDWORKING);
        folk.get(3).life().setTraitsForTests(Social.Trait.CURIOUS, Social.Trait.SOCIABLE);
        boolean[] started = { false };
        int[] best = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t < 10) return;
            Villages.Village v = Villages.get(id);
            if (!started[0]) {
                started[0] = true;
                boolean due = false;
                for (long d = day; d < day + 7; d++) due |= TownMeeting.dueForTests(id, d);
                helper.assertTrue(due, "a meeting is due on one evening of the week");
                for (VillageFolkEntity f : folk) f.ensurePersona();
                List<String> lines = TownMeeting.scriptForTests(level, v);
                Kit.log("tg03 the script: " + String.join(" / ", lines));
                String all = String.join("\n", lines);
                helper.assertTrue(all.contains("25 coins"), "the treasury");
                helper.assertTrue(all.contains("the well"), "what went up this week");
                helper.assertTrue(all.contains("Cora"), "the birth");
                helper.assertTrue(all.contains("Old Tom"), "the death");
                helper.assertTrue(all.contains("Next we build") || all.contains("Next — nothing"), "what is next");
                long said = lines.stream().filter(l -> !l.startsWith("(elder)")).count();
                helper.assertTrue(said >= 2, "two folk have their say: " + said);
                helper.assertTrue(Assemblies.startNow(level, v, Assemblies.Kind.MEETING), "the meeting is called");
                return;
            }
            int[] p = Assemblies.progress(id);
            if (p != null) {
                best[0] = Math.max(best[0], p[1]);
                if (t % 200 == 0) Kit.log("tg03 tick " + t + ": " + Assemblies.debug(id));
                if (t > 6200) helper.fail("tg03 the meeting never closed: " + Assemblies.debug(id));
                return;
            }
            String summary = TownMeeting.lastSummaryForTests(id);
            List<String> said = TownMeeting.saidForTests(id);
            Kit.log("tg03 held: " + summary + "; said " + said.size() + " lines; most in their places " + best[0]);
            helper.assertTrue(!summary.isEmpty() && summary.contains("the treasury at"), "the books have it: " + summary);
            helper.assertTrue(chronicle(id).contains("the town meeting was held"), "and the chronicle: " + chronicle(id));
            helper.assertTrue(said.size() >= 8, "the lines were said: " + said);
            int glad = 0;
            for (VillageFolkEntity f : folk) if (Civics.gladForTests(f, "meeting")) glad++;
            helper.assertTrue(best[0] >= 3 && glad >= 3, "those who came are the happier: " + glad + " of " + best[0]);
            helper.succeed();
        });
    }

    // ============================================================ tg04: a warden

    /**
     * A house in the homes quarter, its door banked up with earth, a heap of cobblestone lying in the street
     * before it, no light anywhere. Its three folk are the quarter's; the one the rest think most of is made
     * its warden. At the stop by the house it sends the door to the works (dug out), asks for a lamp (put up
     * of the stores' fence and torch), and picks up the litter (into the stores); and two of its quarter who
     * cannot abide each other are talked round.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "tg04_warden")
    public static void tg04_warden(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 706000;
        Kit.hold(level, x, Z, 56);
        Kit.prepare(level, x, Z, 56);
        BlockPos heart = flat(level, x, Z, 48);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 3000L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 6);
        UUID id = folk.get(0).ownerId();
        Villages.setFieldsSide(id, TownPlan.NORTH);
        BlockPos[] house = { null };
        BlockPos[] stop = { null };
        helper.runAtTickTime(5, () -> {
            // A spot in the homes quarter, thirty out from the heart.
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos p = heart.relative(d, 30);
                if (Quarters.districtOf(id, heart, p) == Districts.District.HOMES) { house[0] = p; break; }
            }
            helper.assertTrue(house[0] != null, "the town has a homes quarter");
            BuildGoal.stamp(level, "house", house[0], Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", house[0], Direction.NORTH);
            // No light anywhere: the house's own lanterns and torches taken down.
            for (BlockPos p : BlockPos.betweenClosed(house[0].offset(-6, -1, -6), house[0].offset(6, 8, 6))) {
                BlockState st = level.getBlockState(p);
                if (st.is(Blocks.LANTERN) || st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            }
            for (int i = 1; i <= 3; i++) helper.assertTrue(folk.get(i).claimBedNear(house[0]), "a bed in the house");
            Ledger.Building b = new Ledger.Building("house", house[0], Direction.NORTH);
            BlockPos door = TownLife.fittings(b).door();
            helper.assertTrue(door != null, "the house has a door");
            // Its way in banked up with earth, two high.
            BlockPos step = door.relative(Direction.SOUTH);
            level.setBlock(step, Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(step.above(), Blocks.DIRT.defaultBlockState(), 3);
            boolean blocked = DoorWays.doorsOf(level, b).stream().noneMatch(d -> DoorWays.walkable(level, d));
            helper.assertTrue(blocked, "nobody can walk in at the door");
            stop[0] = door.relative(Direction.SOUTH, 5);
            ItemEntity litter = new ItemEntity(level, stop[0].getX() + 2.5, stop[0].getY() + 0.5, stop[0].getZ() + 0.5, new ItemStack(Items.COBBLESTONE, 5));
            litter.setNeverPickUp();                     // (nobody passing picks it up first: it is the warden's to find)
            level.addFreshEntity(litter);
            chestAt(level, heart.offset(-3, 0, -3), new ItemStack(Items.TORCH, 2), new ItemStack(Items.OAK_PLANKS, 8));
        });
        // The litter lies a while first (the warden takes nothing a player might have dropped a moment ago).
        helper.runAtTickTime(260, () -> {
            Villages.Village v = Villages.get(id);
            Map<Districts.District, VillageFolkEntity> wardens = Wardens.appointForTests(level, v);
            VillageFolkEntity w = wardens.get(Districts.District.HOMES);
            Kit.log("tg04 the wardens: " + wardens.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().displayNameCap()).toList());
            helper.assertTrue(w != null && folk.subList(1, 4).contains(w) && !w.isElder(), "the homes quarter's warden is one of its own, not the elder: "
                + (w == null ? "none" : w.displayNameCap()));
            helper.assertTrue(chronicle(id).contains(w.displayNameCap() + " was made warden of the homes quarter"), "in the chronicle");
            int cobble = Market.stock(level, id, s -> s.is(Items.COBBLESTONE));
            List<String> found = Wardens.inspectForTests(level, v, w, Districts.District.HOMES, stop[0]);
            Ledger.Building b = new Ledger.Building("house", house[0], Direction.NORTH);
            boolean open = DoorWays.doorsOf(level, b).stream().allMatch(d -> DoorWays.walkable(level, d));
            Kit.log("tg04 at the stop the warden found: " + found + "; the door walkable " + open + "; lamps wanted " + Wardens.darkForTests(id));
            helper.assertTrue(found.stream().anyMatch(s -> s.startsWith("a blocked door")), "the blocked door: " + found);
            helper.assertTrue(open, "sent to the works, and mended");
            helper.assertTrue(found.stream().anyMatch(s -> s.startsWith("litter")), "the litter: " + found);
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.COBBLESTONE)) >= cobble + 5, "into the stores");
            helper.assertTrue(found.stream().anyMatch(s -> s.startsWith("a dark corner")), "a dark street: " + found);
            List<BlockPos> dark = Wardens.darkForTests(id);
            helper.assertFalse(dark.isEmpty(), "a lamp wanted");
            BlockPos lamp = dark.get(0);
            helper.assertTrue(Wardens.lightsForTests(level, v) == 1, "the works put it up");
            helper.assertTrue(level.getBlockState(lamp).getBlock() instanceof FenceBlock && level.getBlockState(lamp.above()).getBlock() instanceof TorchBlock,
                "a fence post and a torch: " + level.getBlockState(lamp) + " / " + level.getBlockState(lamp.above()));
            // A quarrel in the quarter (the other two of the house), talked round.
            List<VillageFolkEntity> others = new ArrayList<>(folk.subList(1, 4));
            others.remove(w);
            VillageFolkEntity c = others.get(0), d = others.get(1);
            c.life().feel(d.getUUID(), d.displayNameCap(), -60);
            d.life().feel(c.getUUID(), c.displayNameCap(), -60);
            int before = c.life().affinity(d.getUUID());
            UUID[] settled = Wardens.settleForTests(level, v, w, Districts.District.HOMES);
            Kit.log("tg04 settled: " + (settled == null ? "none" : settled[0] + " & " + settled[1]) + "; " + before + " -> " + c.life().affinity(d.getUUID()));
            helper.assertTrue(settled != null, "a quarrel found in the quarter");
            helper.assertTrue(c.life().affinity(d.getUUID()) > before, "and the two think the better of each other");
            helper.assertTrue(Civics.cardLine(w).contains("warden of the homes quarter"), "the warden's card: " + Civics.cardLine(w));
            helper.succeed();
        });
    }

    // ============================================================ tg05: the statue fund

    /**
     * A Stone Age town of six: the fund is open. A player gives twenty-five coins (and is a little better
     * thought of); a generous folk gives of its savings; the player gives the rest. At sixty the fund is
     * raised: the sixty go into the treasury and the statue onto the build list. Put up, a sign before it
     * names its givers.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "tg05_fund")
    public static void tg05_fund(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 708000;
        Kit.hold(level, x, Z, 36);
        Kit.prepare(level, x, Z, 36);
        BlockPos heart = flat(level, x, Z, 28);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 7000L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 6);
        UUID id = folk.get(0).ownerId();
        Villages.ageForTests(id, Villages.Age.STONE);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            Player you = helper.makeMockPlayer(GameType.SURVIVAL);
            you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 70));
            int treasury = Ledger.coins(id);
            String said = PublicFund.donate(level, v, you, 25);
            int[] s = PublicFund.stateForTests(id);
            Kit.log("tg05 the player gives: " + said + " -> " + java.util.Arrays.toString(s));
            helper.assertTrue(s[0] == 25 && s[2] == 1 && s[3] == 0, "twenty-five raised, one giver, not yet funded");
            helper.assertTrue(Market.coinsHeld(you) == 45, "out of the player's pack: " + Market.coinsHeld(you));
            helper.assertTrue(Ledger.coins(id) == treasury, "kept in the fund, not spent: the treasury untouched");
            helper.assertTrue(folk.get(0).persona().affinity(you.getUUID()) > 0, "the town thinks the better of the player");
            // A generous folk with savings gives a little (not every day: one of two days running).
            VillageFolkEntity giver = folk.get(1);
            giver.life().setTraitsForTests(Social.Trait.GENEROUS, Social.Trait.CHEERFUL);
            giver.earn(40);
            int purse = giver.purse();
            PublicFund.folkGiveForTests(level, v);
            level.setDayTime(level.getDayTime() + 24000L);
            PublicFund.folkGiveForTests(level, v);
            s = PublicFund.stateForTests(id);
            Kit.log("tg05 the folk give: purse " + purse + " -> " + giver.purse() + "; fund " + java.util.Arrays.toString(s));
            helper.assertTrue(giver.purse() < purse && s[0] > 25, "the generous folk gives of its savings");
            int before = PublicFund.stateForTests(id)[0], held = Market.coinsHeld(you);
            String rest = PublicFund.donate(level, v, you, 60);
            s = PublicFund.stateForTests(id);
            Kit.log("tg05 the rest: " + rest + " -> " + java.util.Arrays.toString(s) + "; treasury " + Ledger.coins(id));
            helper.assertTrue(s[0] == 60 && s[3] == 1, "sixty raised: funded");
            helper.assertTrue(Ledger.coins(id) == treasury + 60, "the sixty into the treasury: " + Ledger.coins(id));
            helper.assertTrue(Market.coinsHeld(you) == held - (60 - before), "only what the fund wanted was taken: " + Market.coinsHeld(you));
            helper.assertTrue(Villages.projectsWanted(id).contains(PublicFund.STRUCTURE), "the statue on the build list: " + Villages.projectsWanted(id));
            helper.assertTrue(chronicle(id).contains("the statue fund was raised"), "in the chronicle");
            // Built: the givers' names before it.
            BlockPos at = heart.offset(18, 0, 6);
            BuildGoal.stamp(level, PublicFund.STRUCTURE, at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, PublicFund.STRUCTURE, at, Direction.NORTH);
            chestAt(level, heart.offset(3, 0, 3), new ItemStack(Items.OAK_PLANKS, 2));
            int planks = Market.stock(level, id, st -> st.is(ItemTags.PLANKS)), signs = Market.stock(level, id, st -> st.is(ItemTags.SIGNS));
            helper.assertTrue(PublicFund.plaqueForTests(level, v), "a sign before the statue");
            BlockPos sign = PublicFund.plaqueAtForTests(id);
            helper.assertTrue(sign != null && level.getBlockState(sign).getBlock() instanceof StandingSignBlock, "standing before it");
            SignBlockEntity be = (SignBlockEntity) level.getBlockEntity(sign);
            String face = be.getFrontText().getMessage(0, false).getString();
            String back = be.getBackText().getMessage(0, false).getString();
            Kit.log("tg05 the sign: " + face + " / back: " + back);
            helper.assertTrue(face.equals("Raised by"), "its face: " + face);
            helper.assertTrue(back.contains(you.getName().getString()), "the biggest giver's name first on the back: " + back);
            helper.assertTrue(Market.stock(level, id, st -> st.is(ItemTags.PLANKS)) == planks - 2
                || Market.stock(level, id, st -> st.is(ItemTags.SIGNS)) == signs - 1, "the sign out of the stores (two planks, or a sign put by)");
            helper.succeed();
        });
    }

    // ============================================================ tg06: a search party

    /**
     * A folk stuck in a pit two blocks deep, seventy blocks out past the town, last seen a whole day ago by the
     * pit. Its partner and its friend go out, calling its name, find it, cut it a step out of the pit and bring
     * it home; the chronicle tells who found it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3200, batch = "tg06_search")
    public static void tg06_search(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 710000;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        Kit.hold(level, x + 50, Z, 40);
        Kit.prepare(level, x + 50, Z, 40);
        BlockPos heart = flat(level, x, Z, 22);
        flatAt(level, x, x + 85, Z - 5, Z + 5, heart.getY());
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 2500L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 5);
        UUID id = folk.get(0).ownerId();
        VillageFolkEntity lost = folk.get(4), partner = folk.get(1), friend = folk.get(2);
        BlockPos pit = new BlockPos(x + 72, heart.getY() - 2, Z);
        int[] phase = { 0 };
        long[] mark = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t < 10) return;
            Villages.Village v = Villages.get(id);
            switch (phase[0]) {
                case 0 -> {
                    level.setBlock(pit.above(), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(pit, Blocks.AIR.defaultBlockState(), 3);
                    lost.clearQueue();
                    lost.teleportTo(pit.getX() + 0.5, pit.getY(), pit.getZ() + 0.5);
                    lost.life().partnerWith(partner.getUUID(), partner.displayNameCap());
                    partner.life().partnerWith(lost.getUUID(), lost.displayNameCap());
                    lost.life().feel(friend.getUUID(), friend.displayNameCap(), 50);
                    friend.life().feel(lost.getUUID(), lost.displayNameCap(), 50);
                    phase[0] = 1;
                }
                case 1 -> {
                    helper.assertFalse(SearchParties.seenForTests(level, v, lost), "in a pit out past the town is nowhere a folk should be");
                    SearchParties.missingForTests(lost, SearchParties.MISSING + 1000, pit.offset(-6, 2, 0));
                    SearchParties.lookForTests(level, v);
                    List<UUID> party = SearchParties.partyForTests(lost.getUUID());
                    Kit.log("tg06 the search party: " + party.size() + "; " + SearchParties.stageForTests(lost.getUUID()) + "; " + chronicle(id));
                    helper.assertTrue(party.contains(partner.getUUID()) && party.contains(friend.getUUID()), "its partner and its friend go out: " + party);
                    helper.assertTrue(chronicle(id).contains(lost.displayNameCap() + " has not been seen since"), "the chronicle says it is missed");
                    mark[0] = t;
                    phase[0] = 2;
                }
                case 2 -> {
                    String stage = SearchParties.stageForTests(lost.getUUID());
                    if (t % 100 == 0) {
                        Kit.log("tg06 tick " + t + ": " + stage + "; lost at " + lost.blockPosition().toShortString() + "; partner at "
                            + partner.blockPosition().toShortString() + " doing " + partner.hobbyNow() + "; friend at " + friend.blockPosition().toShortString());
                    }
                    if (!stage.equals("none")) {
                        if (t - mark[0] > 3000) helper.fail("tg06 never brought home: " + stage + " | lost " + lost.debugLine()
                            + " | partner " + partner.debugLine());
                        return;
                    }
                    String chron = chronicle(id);
                    double home = Math.sqrt(lost.distanceToSqr(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5));
                    Kit.log("tg06 home after " + (t - mark[0]) + " ticks, " + String.format("%.1f", home) + " from the heart: " + chron);
                    helper.assertTrue(chron.contains("was found") && chron.contains("brought home"), "the chronicle tells it: " + chron);
                    helper.assertTrue(home < 16, "home: " + String.format("%.1f", home));
                    helper.assertTrue(lost.persona().memories().stream().anyMatch(m -> m.text().contains("came and found me")), "it remembers who came for it");
                    helper.assertTrue(lost.life().affinity(partner.getUUID()) > 0 && lost.life().affinity(friend.getUUID()) > 50, "and thinks the better of them");
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    // ============================================================ tg07: good turns

    /**
     * Neighbours: a folk with nothing to eat borrows a bite and pays it back once it has food again; a folk
     * without an axe borrows a neighbour's spare one and hands it back once it has its own; a neighbour carries
     * half a full pack into the stores; and five good turns done make a good neighbour, in the chronicle.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "tg07_favours")
    public static void tg07_favours(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 712000;
        Kit.hold(level, x, Z, 30);
        Kit.prepare(level, x, Z, 30);
        BlockPos heart = flat(level, x, Z, 20);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 3000L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 5);
        UUID id = folk.get(0).ownerId();
        helper.runAtTickTime(10, () -> {
            VillageFolkEntity ann = folk.get(1), ben = folk.get(2), col = folk.get(3), dot = folk.get(4);
            for (VillageFolkEntity f : folk) f.getInventoryItems().replaceAll(s -> ItemStack.EMPTY);
            chestAt(level, heart.offset(-3, 0, -3));
            // A bite borrowed.
            ben.insertItem(new ItemStack(Items.BREAD, 5));
            Favours.hungryForTests(ann);
            String need = Favours.needForTests(level, ann);
            Kit.log("tg07 Ann with nothing to eat would ask: " + need);
            helper.assertTrue(need != null && need.startsWith("FOOD"), "a bite of a neighbour: " + need);
            helper.assertTrue(Favours.askForTests(level, ann, ben, "food"), "Ben lends Ann a bite");
            helper.assertTrue(count(ann, s -> s.is(Items.BREAD)) == 1 && count(ben, s -> s.is(Items.BREAD)) == 4, "out of Ben's pack into Ann's");
            helper.assertTrue(Favours.owesForTests(ann).stream().anyMatch(s -> s.startsWith("FOOD")), "Ann owes Ben a bite: " + Favours.owesForTests(ann));
            helper.assertTrue(ann.life().affinity(ben.getUUID()) >= 10, "and thinks the better of Ben");
            // Paid back once Ann has food again.
            ann.insertItem(new ItemStack(Items.BREAD, 3));
            helper.assertTrue(Favours.repayForTests(level, ann, ben, day), "Ann pays Ben back");
            helper.assertTrue(count(ben, s -> s.is(Items.BREAD)) == 5 && Favours.owesForTests(ann).isEmpty(), "a bite back; nothing owed");
            // A tool lent: Col has a spare axe (two: whatever its trade, one is to spare).
            col.insertItem(new ItemStack(Items.IRON_AXE));
            col.insertItem(new ItemStack(Items.IRON_AXE));
            helper.assertTrue(Favours.askForTests(level, dot, col, "an axe"), "Col lends Dot its spare axe");
            helper.assertTrue(count(dot, s -> s.is(Items.IRON_AXE)) == 1 && count(col, s -> s.is(Items.IRON_AXE)) == 1, "the axe changes packs");
            helper.assertTrue(Favours.owesForTests(dot).stream().anyMatch(s -> s.startsWith("TOOL minecraft:iron_axe")), "Dot owes Col an axe: " + Favours.owesForTests(dot));
            dot.insertItem(new ItemStack(Items.STONE_AXE));
            helper.assertTrue(Favours.repayForTests(level, dot, col, day), "with an axe of its own, Dot hands Col's back");
            helper.assertTrue(count(col, s -> s.is(Items.IRON_AXE)) == 2 && count(dot, s -> s.is(Items.IRON_AXE)) == 0, "Col has its axe again");
            // A load carried to the stores.
            ann.getInventoryItems().replaceAll(s -> ItemStack.EMPTY);
            ann.insertItem(new ItemStack(Items.COBBLESTONE, 60));
            int stores = Market.stock(level, id, s -> s.is(Items.COBBLESTONE));
            int carried = Favours.carryForTests(level, ben, ann);
            Kit.log("tg07 Ben carried " + carried + " of Ann's 60 cobblestone; stores " + stores + " -> " + Market.stock(level, id, s -> s.is(Items.COBBLESTONE)));
            helper.assertTrue(carried == 30 && Market.stock(level, id, s -> s.is(Items.COBBLESTONE)) == stores + 30, "half the load into the stores");
            helper.assertTrue(count(ann, s -> s.is(Items.COBBLESTONE)) == 30 && count(ben, s -> s.is(Items.COBBLESTONE)) == 0, "out of Ann's pack, through Ben's");
            // Ben has done two good turns; three more and it is a good neighbour.
            for (int i = 0; i < 3; i++) {
                for (ItemStack s : dot.getInventoryItems()) if (s.is(Items.BREAD)) s.setCount(0);
                helper.assertTrue(Favours.askForTests(level, dot, ben, "food"), "another bite for Dot");
            }
            int[] st = Favours.standingForTests(ben);
            Kit.log("tg07 Ben's good turns: " + st[0] + ", good neighbour " + st[1] + "; card: " + Civics.cardLine(ben));
            helper.assertTrue(st[0] >= Favours.GOOD && st[1] == 1, "five good turns: a good neighbour");
            helper.assertTrue(chronicle(id).contains(ben.displayNameCap() + " has done so many good turns"), "the chronicle says so");
            helper.assertTrue(Civics.cardLine(ben).contains("a good neighbour"), "and its card");
            helper.succeed();
        });
    }
}
