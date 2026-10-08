package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.VillageSpawner;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Craftsmanship;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.InterviewBook;
import com.jrpetty.mcassistant.entity.Interviews;
import com.jrpetty.mcassistant.entity.JobMarket;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Names;
import com.jrpetty.mcassistant.entity.School;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.item.InterviewItems;
import com.jrpetty.mcassistant.item.LetterOfApplicationItem;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * [interviews] Job interviews a player can watch (entity/Interviews, InterviewScript, InterviewPosts, InterviewTable):
 * <ul>
 * <li><b>iv01</b>: a notice for a miner with four applicants from the next town: the best three shortlisted, the fourth
 * told no on paper; the three write their letters, walk the road, gather on the bench, and sit across the table one at a
 * time while the panel asks about the mines and a creeper in the gallery; every line said is captured.</li>
 * <li><b>iv02</b>: the best by the combined score is chosen and takes the post: three of the town's own interviewed for
 * the smith's place, the best smith holding up a sword of her own make for the master to look over; she takes up the
 * forge.</li>
 * <li><b>iv03</b>: the others' cards and spirits show it: "not chosen" and why on each card, their spirits a little
 * down, a week's harder work; the chosen's up.</li>
 * <li><b>iv04</b>: the chronicle tells it, and the board, the crier and the gazette: the interview set, and who got the
 * constable's post after an interview with whom; the watch's own reckoning then keeps the panel's choice.</li>
 * <li><b>iv05</b>: a reference changes a close result: a guard behind on paper is chosen on the word of a close friend
 * and of a player the town thinks the world of.</li>
 * <li><b>iv06</b>: a boast is seen through: a guard of level three says there is no better in three towns, and the panel
 * counts; a boast the record bears out counts for its maker.</li>
 * <li><b>iv07</b>: an internal post, the teacher: the post falls vacant, is held open for its interview (two of the town's
 * own want it), and the best gets the school.</li>
 * <li><b>iv08</b>: a player who leads the town chairs it and chooses the weakest of three through the command its screen
 * runs; its choice stands.</li>
 * <li><b>iv09</b>: the letter of application is made by its real recipe from the stores' paper and ink, paid for out of
 * each candidate's purse, and held in the hand while it waits on the bench; the chair holds it while it reads.</li>
 * <li><b>iv10</b>: an outside candidate walks the road from a neighbouring town (two hundred blocks) for its interview
 * for a new smithy's first keeper, stands with one of the town's own, and is interviewed.</li>
 * </ul>
 * Each on its own ground in x 1300000-1319999, z 66000, in a batch of its own, the clock held at mid-morning.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class InterviewGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    /** Mid-morning: past the assembly, the interviews' own hour. */
    private static final long HOUR = 3000L;

    // ------------------------------------------------------------------ the scene

    /**
     * The tests' folk go by fixed names, none the same in one test (two towns' name lists can both hand out "Quill",
     * and a test reading the transcript by name would mistake one for the other).
     */
    private static final String[] NAMES = { "Ada", "Bram", "Cole", "Dara", "Edda", "Finch", "Garth", "Hale", "Ivy", "Juno", "Kit",
        "Lark", "Moss", "Nell", "Orrin", "Quince", "Rook", "Sorrel", "Tamsin", "Umber", "Vale", "Wren", "Yarrow", "Zeb", "Alder",
        "Briar", "Clove", "Dell", "Elm", "Fern" };
    private static int named;

    private static long start(ServerLevel level) {
        named = 0;
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Interviews.hurryForTests(true);
        Interviews.fairDayForTests(true);
        level.setWeatherParameters(6000, 0, false, false);
        long base = (level.getDayTime() / 24000L) * 24000L;
        level.setDayTime(base + HOUR);
        return base;
    }

    /** A town founded here, its builders kept from starting anything for the length of the test. */
    private static Villages.Village town(ServerLevel level, int x) {
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        Villages.Village v = Villages.found(level, Kit.surface(level, x, Z));
        Villages.noteAttempt(v.id(), level.getGameTime() + 400000L);
        return v;
    }

    /** The ground between two towns held awake, for a walk between them. */
    private static void road(ServerLevel level, int ax, int bx) {
        for (int x = Math.min(ax, bx) + 32; x < Math.max(ax, bx) - 16; x += 32) {
            Kit.hold(level, x, Z, 24);
            Kit.prepare(level, x, Z, 24);
        }
    }

    /** A grown folk of this town standing here, at this trade and level, so many years old, of this nature, a little coin in its purse. */
    private static VillageFolkEntity folk(ServerLevel level, Villages.Village v, int x, int z, StationTask trade, int lv, int years,
                                          Social.Trait... traits) {
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        BlockPos at = Kit.surface(level, x, z);
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        f.rename(NAMES[named++ % NAMES.length]);
        VillageSpawner.starterKit(f);
        f.joinVillage(v.id(), v.centre());
        level.addFreshEntity(f);
        Villages.recordBirth(v.id());
        f.setAgeForTests(Math.max(18, years));
        if (traits.length > 0) f.life().setTraitsForTests(traits);
        f.ensurePersona();
        if (trade != StationTask.NONE) {
            f.setJob(trade);
            if (lv > 0) f.tradeXpForTests(trade, lv * lv * Math.max(1, AssistantConfig.levelCurveFactor()) + 1);
        }
        f.earn(8);
        return f;
    }

    /** So much experience at a trade it does not work at now (an old hand at the forge, farming today). */
    private static void knows(VillageFolkEntity f, StationTask t, int lv) {
        StationTask was = f.stationTask();
        f.tradeXpForTests(t, lv * lv * Math.max(1, AssistantConfig.levelCurveFactor()) + 1);
        if (was != f.stationTask()) f.setJob(was);
    }

    /** A marked store chest of the town's, filled with these. */
    private static BlockPos stores(ServerLevel level, int x, int dx, int dz, ItemStack... goods) {
        BlockPos chest = Kit.surface(level, x + dx, Z + dz);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        return chest;
    }

    /** The stores for an interview: planks for the table and its chairs, paper and ink for the letters. */
    private static void makings(ServerLevel level, int x) {
        stores(level, x, 7, 7, new ItemStack(Items.OAK_PLANKS, 32), new ItemStack(Items.PAPER, 8), new ItemStack(Items.INK_SAC, 8));
    }

    /** Beds made up round the heart: room for newcomers. */
    private static void beds(ServerLevel level, BlockPos heart, int n) {
        for (int i = 0; i < n; i++) {
            BlockPos foot = Kit.surface(level, heart.getX() - 5 + (i % 4) * 3, heart.getZ() + (i < 4 ? 4 : -4));
            level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT), 3);
            level.setBlock(foot.east(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD), 3);
        }
    }

    private static void pact(Villages.Village a, Villages.Village b) {
        Ledger.note(a.id(), "pact/" + b.id(), "trade");
        Ledger.note(b.id(), "pact/" + a.id(), "trade");
        Ledger.relate(a.id(), b.id(), 25);
    }

    @SafeVarargs
    private static <T> List<T> list(T... xs) {
        return new ArrayList<>(List.of(xs));
    }

    @javax.annotation.Nullable
    private static InterviewBook.Cand cand(InterviewBook.Interview iv, VillageFolkEntity f) {
        for (InterviewBook.Cand c : iv.cands()) if (c.id().equals(f.getUUID())) return c;
        return null;
    }

    private static InterviewBook.Cand best(InterviewBook.Interview iv) {
        InterviewBook.Cand best = null;
        for (InterviewBook.Cand c : iv.cands()) if (best == null || c.total() > best.total()) best = c;
        return best;
    }

    /** Somebody said something with these words in it (the transcript's "speaker|words"). */
    private static boolean said(InterviewBook.Interview iv, String words) {
        for (String l : iv.said()) if (l.toLowerCase(Locale.ROOT).contains(words.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** How many lines this folk said. */
    /** The lines this folk said itself, by its identity (the script keeps who said what, not only the name). */
    private static int linesBy(InterviewBook.Interview iv, VillageFolkEntity f) {
        return iv.linesBy(f.getUUID());
    }

    private static void transcript(String tag, InterviewBook.Interview iv) {
        Kit.log(tag + " transcript (" + iv.said().size() + " lines) of the interviews for " + iv.title() + ":");
        for (String l : iv.said()) {
            int bar = l.indexOf('|');
            Kit.log(tag + "   " + (bar < 0 ? l : l.substring(0, bar) + ": " + l.substring(bar + 1)));
        }
        for (InterviewBook.Cand c : iv.cands()) {
            Kit.log(tag + " " + c.name() + ": paper " + c.paper() + ", " + c.parts() + " = " + c.total() + " — " + c.outcome()
                + (c.why().isEmpty() ? "" : " (" + c.why() + ")") + (c.refNames().isEmpty() ? "" : "; words: " + c.refNames()));
        }
        Kit.log(tag + " chosen: " + iv.winnerName() + " — " + iv.reason());
    }

    private static boolean newsHas(Villages.Village v, String words) {
        for (Villages.News n : Villages.news(v.id())) if (n.text().contains(words)) return true;
        return false;
    }

    // ============================================================ iv01: four apply, three are interviewed

    /**
     * Ashford (A) wants a miner; Brookby (B), ninety blocks off and at a trade pact with it, has four idle miners of
     * levels twelve, nine, seven and one, and all four apply. The leader's look sends them to interview: the three best
     * shortlisted, the learner told no on paper. Each writes its letter out of B's stores, walks to A, waits about the
     * board, and when the interviews begin sits on the bench; each in turn crosses to the candidate's chair and the talk
     * goes back and forth — the mines, its best work, a creeper in the gallery, why it wants the place. Every line said
     * is captured in the log.
     */
    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "iv01_shortlist_gathers_and_speaks")
    public static void iv01_shortlist_gathers_and_speaks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long base = start(level);
        int ax = 1300000, bx = 1300090;
        Villages.Village a = town(level, ax), b = town(level, bx);
        road(level, ax, bx);
        VillageFolkEntity elderA = folk(level, a, ax - 4, Z + 6, StationTask.FARM, 2, 52, Social.Trait.GENEROUS, Social.Trait.HARDWORKING);
        folk(level, a, ax - 1, Z + 6, StationTask.FARM, 2, 30);
        folk(level, a, ax + 2, Z + 6, StationTask.WOOD, 2, 33);
        folk(level, a, ax + 5, Z + 6, StationTask.WOOD, 2, 41);
        folk(level, a, ax + 5, Z - 6, StationTask.SMELT, 2, 37);
        makings(level, ax);
        beds(level, a.centre(), 8);
        Villages.recountBeds(a.id());
        VillageFolkEntity elderB = folk(level, b, bx - 4, Z + 6, StationTask.FARM, 2, 50);
        folk(level, b, bx - 1, Z + 6, StationTask.FARM, 2, 34);
        folk(level, b, bx + 2, Z + 6, StationTask.FARM, 2, 39);
        VillageFolkEntity bram = folk(level, b, bx - 3, Z - 5, StationTask.MINE, 12, 30, Social.Trait.HARDWORKING, Social.Trait.SOCIABLE);
        VillageFolkEntity fen = folk(level, b, bx, Z - 5, StationTask.MINE, 9, 28, Social.Trait.SHY, Social.Trait.GENEROUS);
        VillageFolkEntity wick = folk(level, b, bx + 3, Z - 5, StationTask.MINE, 7, 35, Social.Trait.GRUMPY, Social.Trait.CURIOUS);
        VillageFolkEntity pip = folk(level, b, bx + 6, Z - 5, StationTask.MINE, 1, 20, Social.Trait.CHEERFUL, Social.Trait.EASYGOING);
        stores(level, bx, 7, 7, new ItemStack(Items.PAPER, 8), new ItemStack(Items.INK_SAC, 8));
        long day = base / 24000L;
        Villages.electElder(a.id(), elderA, day);
        Villages.electElder(b.id(), elderB, day);
        pact(a, b);
        JobMarket.Opening o = JobMarket.postFor(level, a, StationTask.MINE);
        for (VillageFolkEntity f : List.of(bram, fen, wick, pip)) Interviews.applyForTests(level, f, a.id(), o);
        int paperB = Market.stock(level, b.id(), s -> s.is(Items.PAPER));
        Interviews.considerForTests(level, a);
        InterviewBook.Interview iv = Interviews.pendingForTests(a.id(), "opening:" + o.id());
        for (String l : Interviews.status(level, a.id())) Kit.log("iv01 " + l);
        helper.assertTrue(iv != null, "four applicants: the notice goes to interview, not decided on paper");
        helper.assertTrue(iv.cands().size() == 3, "three shortlisted of four: " + iv.cands().size());
        helper.assertTrue(cand(iv, bram) != null && cand(iv, fen) != null && cand(iv, wick) != null && cand(iv, pip) == null,
            "the best three on paper are shortlisted, the learner is not");
        JobMarket.Application pa = null;
        for (JobMarket.Application ap : JobMarket.applications(a.id())) if (ap.folk().equals(pip.getUUID())) pa = ap;
        helper.assertTrue(pa != null && pa.verdict() == JobMarket.Verdict.REFUSED && pa.why().contains("shortlisted"),
            "the learner is told no on paper: " + (pa == null ? "none" : pa.verdict() + " — " + pa.why()));
        int paperAfter = Market.stock(level, b.id(), s -> s.is(Items.PAPER));
        for (InterviewBook.Cand c : iv.cands()) helper.assertTrue(c.letter(), c.name() + " wrote its letter of application");
        helper.assertTrue(paperB - paperAfter == 3, "three letters, three sheets of B's paper: " + paperB + " -> " + paperAfter);
        Kit.log("iv01 " + Interviews.nowForTests(level, a));
        Set<UUID> onBench = new HashSet<>();
        int[] stage = { 0 };
        long[] since = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);
            Interviews.stepForTests(level, a);
            switch (stage[0]) {
                case 0 -> {
                    boolean all = true;
                    for (InterviewBook.Cand c : iv.cands()) all &= c.arrived();
                    if (t % 100 == 0) Kit.log("iv01 tick " + t + ": on the road — " + bram.blockPosition().toShortString() + " ("
                        + Interviews.roleForTests(bram) + "), " + fen.blockPosition().toShortString() + ", " + wick.blockPosition().toShortString());
                    if (all) {
                        Kit.log("iv01 tick " + t + ": all three arrived in " + Villages.name(a.id()) + "; " + Interviews.nowForTests(level, a));
                        stage[0] = 1;
                        since[0] = t;
                    } else if (t > 4000) {
                        helper.fail("the three never all got to " + Villages.name(a.id()) + ": " + bram.debugLine() + " | " + fen.debugLine()
                            + " | " + wick.debugLine());
                    }
                }
                case 1 -> {
                    if (iv.stage() == InterviewBook.Stage.SET && t - since[0] > 40) Kit.log("iv01 " + Interviews.nowForTests(level, a));
                    if (iv.stage() == InterviewBook.Stage.SITTING) {
                        for (VillageFolkEntity f : List.of(bram, fen, wick)) if (Interviews.onTheBenchForTests(f)) onBench.add(f.getUUID());
                    }
                    if (iv.stage() == InterviewBook.Stage.DONE) {
                        transcript("iv01", iv);
                        List<UUID> sat = Interviews.satForTests(iv);
                        helper.assertTrue(sat.size() == 3 && sat.containsAll(List.of(bram.getUUID(), fen.getUUID(), wick.getUUID())),
                            "each of the three sat across the table in turn: " + sat.size());
                        helper.assertTrue(onBench.size() >= 2, "the others waited on the bench in plain view: " + onBench.size());
                        helper.assertTrue(said(iv, "How long have you worked the mines?"), "the panel asks about the mines");
                        helper.assertTrue(said(iv, "creeper"), "a situation the post really meets: the creeper in the gallery");
                        helper.assertTrue(said(iv, "Why do you want it") || said(iv, "why this post") || said(iv, "makes you want it"),
                            "why it wants the post");
                        for (VillageFolkEntity f : List.of(bram, fen, wick)) {
                            helper.assertTrue(linesBy(iv, f) >= 4, f.displayNameCap() + " spoke for itself: "
                                + linesBy(iv, f) + " lines");
                        }
                        helper.assertTrue(iv.said().size() >= 30, "a real conversation: " + iv.said().size() + " lines");
                        InterviewBook.Cand top = best(iv);
                        helper.assertTrue(iv.winner().equals(top.id()), "the best by the combined score is chosen: " + iv.winnerName()
                            + " against " + top.name());
                        boolean open = false;
                        for (JobMarket.Opening x : JobMarket.open(a.id())) if (x.id() == o.id()) open = true;
                        helper.assertTrue(!open, "the notice is filled");
                        helper.succeed();
                    } else if (t - since[0] > 4500) {
                        transcript("iv01", iv);
                        helper.fail("the interview never finished: " + iv.stage() + ", " + Interviews.satForTests(iv).size() + " sat");
                    }
                }
                default -> { }
            }
        });
    }

    // ============================================================ iv02: the best is chosen and takes the forge

    /**
     * A new forge wants its smith. Three of the town's own stand: Ada, sixteen levels at the forge (farming now), with a
     * sword of her own make in her pack; Bram, ten levels; and Fen, five. Each is interviewed; Ada holds her sword up for
     * the master to look over ("my mark's on it"). The best by paper and interview together is chosen, takes up the
     * forge at once, and the notice comes down.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "iv02_best_combined_takes_the_post")
    public static void iv02_best_combined_takes_the_post(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long base = start(level);
        int x = 1302000;
        Villages.Village v = town(level, x);
        VillageFolkEntity elder = folk(level, v, x - 4, Z + 6, StationTask.FARM, 3, 55, Social.Trait.GENEROUS, Social.Trait.EASYGOING);
        VillageFolkEntity ada = folk(level, v, x - 1, Z + 6, StationTask.FARM, 4, 34, Social.Trait.HARDWORKING, Social.Trait.CHEERFUL);
        VillageFolkEntity bram = folk(level, v, x + 2, Z + 6, StationTask.WOOD, 4, 29, Social.Trait.GRUMPY, Social.Trait.HARDWORKING);
        VillageFolkEntity fen = folk(level, v, x + 5, Z + 6, StationTask.FARM, 2, 24, Social.Trait.SHY, Social.Trait.CURIOUS);
        folk(level, v, x + 5, Z - 6, StationTask.WOOD, 2, 44);
        knows(ada, StationTask.SMITH, 16);
        knows(bram, StationTask.SMITH, 10);
        knows(fen, StationTask.SMITH, 5);
        ItemStack sword = Craftsmanship.finish(level, new ItemStack(Items.IRON_SWORD), 16, ada.displayNameCap());
        ada.insertItem(sword);
        makings(level, x);
        Villages.electElder(v.id(), elder, base / 24000L);
        InterviewBook.Interview iv = Interviews.stageForTests(level, v, "smith");
        helper.assertTrue(iv != null && iv.cands().size() == 3, "the smith's place set for interview with three of the town's own");
        Kit.log("iv02 " + Interviews.nowForTests(level, v));
        boolean[] heldUp = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);
            Interviews.stepForTests(level, v);
            if (!heldUp[0] && iv.stage() == InterviewBook.Stage.SITTING && ada.getMainHandItem().is(Items.IRON_SWORD)
                    && ada.getMainHandItem().getHoverName().getString().length() > 0
                    && FolkTalk.card(ada).length() > 0 && said(iv, "My mark's on it")) {
                heldUp[0] = true;
                Kit.log("iv02 tick " + t + ": " + ada.displayNameCap() + " holds up " + ada.getMainHandItem().getHoverName().getString()
                    + " at " + ada.blockPosition().toShortString());
            }
            if (iv.stage() != InterviewBook.Stage.DONE) {
                if (t > 5500) {
                    transcript("iv02", iv);
                    helper.fail("the interview never finished: " + iv.stage());
                }
                return;
            }
            transcript("iv02", iv);
            InterviewBook.Cand top = best(iv), a = cand(iv, ada);
            helper.assertTrue(iv.winner().equals(top.id()), "the best by the combined score is chosen: " + iv.winnerName() + " against " + top.name());
            helper.assertTrue(a != null && a.evidence() > 0 && a.evidenceWords().contains("sword"), "her sword counted: " + (a == null ? "?" : a.evidenceWords()));
            helper.assertTrue(heldUp[0], "she held her sword up across the table while the master looked it over");
            VillageFolkEntity winner = top.id().equals(ada.getUUID()) ? ada : top.id().equals(bram.getUUID()) ? bram : fen;
            helper.assertTrue(winner.stationTask() == StationTask.SMITH, "the chosen takes up the forge: " + winner.stationTask());
            boolean open = false;
            for (JobMarket.Opening o : JobMarket.open(v.id())) if (o.task() == StationTask.SMITH) open = true;
            helper.assertTrue(!open, "the notice comes down");
            int marked = 0;
            for (ItemStack s : ada.getInventoryItems()) if (s.is(Items.IRON_SWORD)) marked++;
            if (ada.getMainHandItem().is(Items.IRON_SWORD)) marked++;
            helper.assertTrue(marked == 1, "her sword is back with her, once: " + marked);
            helper.succeed();
        });
    }

    // ============================================================ iv03: the cards and the spirits

    /**
     * Three stand for the town librarian. When it is done, every card says how it went ("Interviewed for town
     * librarian on day N: chosen" / "not chosen — why"), the others' spirits are a little down ("passedover") and they
     * work the harder for a week (a fifth more at their trade), and the chosen is glad of it ("interviewed").
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "iv03_cards_and_moods")
    public static void iv03_cards_and_moods(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long base = start(level);
        int x = 1304000;
        Villages.Village v = town(level, x);
        VillageFolkEntity elder = folk(level, v, x - 4, Z + 6, StationTask.FARM, 3, 58, Social.Trait.GENEROUS, Social.Trait.EASYGOING);
        VillageFolkEntity ivy = folk(level, v, x - 1, Z + 6, StationTask.FARM, 6, 46, Social.Trait.CURIOUS, Social.Trait.HARDWORKING);
        VillageFolkEntity rook = folk(level, v, x + 2, Z + 6, StationTask.WOOD, 5, 31, Social.Trait.SOCIABLE, Social.Trait.CHEERFUL);
        VillageFolkEntity moss = folk(level, v, x + 5, Z + 6, StationTask.FISH, 4, 27, Social.Trait.GRUMPY, Social.Trait.EASYGOING);
        folk(level, v, x + 5, Z - 6, StationTask.GUARD, 2, 40);                 // the watch keeps no library
        makings(level, x);
        Villages.electElder(v.id(), elder, base / 24000L);
        InterviewBook.Interview iv = Interviews.stageForTests(level, v, "librarian");
        helper.assertTrue(iv != null && iv.cands().size() == 3, "three stand for the librarian's post");
        Kit.log("iv03 " + Interviews.nowForTests(level, v));
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);
            Interviews.stepForTests(level, v);
            if (iv.stage() != InterviewBook.Stage.DONE) {
                if (t > 5500) {
                    transcript("iv03", iv);
                    helper.fail("the interview never finished: " + iv.stage());
                }
                return;
            }
            transcript("iv03", iv);
            int losers = 0;
            long day = level.getDayTime() / 24000L;
            for (VillageFolkEntity f : List.of(ivy, rook, moss)) {
                InterviewBook.Cand c = cand(iv, f);
                f.refreshMood();
                String card = Interviews.cardLine(f), full = FolkTalk.card(f);
                List<Object[]> parts = new ArrayList<>();
                int m = Interviews.mood(f, day, 50, parts);
                List<String> why = new ArrayList<>();
                for (Object[] o : parts) why.add((String) o[0]);
                Kit.log("iv03 " + f.displayNameCap() + ": its spirits from the interview " + (m - 50) + " " + why + "; says \""
                    + Interviews.moodWords(f, why.isEmpty() ? "" : why.get(0)) + "\"; its mood now " + f.persona().mood() + " " + f.persona().moodWhy());
                Kit.log("iv03 " + f.displayNameCap() + ": card \"" + card + "\"; mood " + f.persona().mood() + " " + why + "; extra xp "
                    + Interviews.extraXp(f, 10));
                helper.assertTrue(full.contains("Interviews|"), f.displayNameCap() + "'s card has its interview: " + full);
                if (c.id().equals(iv.winner())) {
                    helper.assertTrue(card.contains("town librarian") && card.endsWith("chosen"), "the chosen's card: " + card);
                    helper.assertTrue(why.contains("interviewed") && m > 50, "the chosen is glad of it: " + why);
                } else {
                    losers++;
                    helper.assertTrue(card.contains("not chosen") && card.contains(c.why()), "the card says not chosen, and why: " + card);
                    helper.assertTrue(why.contains("passedover") && m < 50 && m >= 44, "its spirits a little down: " + (m - 50) + " " + why);
                    helper.assertTrue(Interviews.extraXp(f, 10) == 2, "it works the harder for a week: " + Interviews.extraXp(f, 10));
                    boolean remembers = false;
                    for (var mem : f.persona().memories()) if (mem.text().contains("not chosen")) remembers = true;
                    helper.assertTrue(remembers, "it remembers being turned down");
                }
            }
            helper.assertTrue(losers == 2, "two not chosen: " + losers);
            helper.succeed();
        });
    }

    // ============================================================ iv04: the chronicle tells it

    /**
     * An Iron Age town's watch of three: the constable's post goes to interview. The board says so before it
     * ("Interviews at … this morning: the post of constable. Three candidates: …"), and so does the crier. After it, the
     * chronicle has who got it after an interview with whom, the board and the crier tell it, the next gazette carries
     * it, and the watch's own reckoning of its constable keeps the panel's choice.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "iv04_chronicle_tells_it")
    public static void iv04_chronicle_tells_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long base = start(level);
        int x = 1306000;
        Villages.Village v = town(level, x);
        Villages.ageForTests(v.id(), Villages.Age.IRON);
        VillageFolkEntity elder = folk(level, v, x - 4, Z + 6, StationTask.FARM, 3, 57, Social.Trait.GENEROUS, Social.Trait.HARDWORKING);
        VillageFolkEntity g1 = folk(level, v, x - 1, Z + 6, StationTask.GUARD, 11, 33, Social.Trait.HARDWORKING, Social.Trait.GRUMPY);
        VillageFolkEntity g2 = folk(level, v, x + 2, Z + 6, StationTask.GUARD, 8, 26, Social.Trait.CHEERFUL, Social.Trait.SOCIABLE);
        VillageFolkEntity g3 = folk(level, v, x + 5, Z + 6, StationTask.GUARD, 4, 22, Social.Trait.SHY, Social.Trait.CURIOUS);
        folk(level, v, x + 5, Z - 6, StationTask.FARM, 2, 40);
        folk(level, v, x + 2, Z - 6, StationTask.WOOD, 2, 38);
        makings(level, x);
        long day = base / 24000L;
        Villages.electElder(v.id(), elder, day);
        InterviewBook.Interview iv = Interviews.stageForTests(level, v, "constable");
        helper.assertTrue(iv != null && iv.cands().size() == 3, "the watch's three stand for constable");
        List<String> board = Interviews.board(level, v.id());
        List<String> crier = Interviews.crierLines(level, v, day);
        Kit.log("iv04 board before: " + board + "; crier: " + crier);
        helper.assertTrue(board.stream().anyMatch(l -> l.contains("Interviews at") && l.contains("constable") && l.contains("candidates")),
            "the board shows the coming interview: " + board);
        helper.assertTrue(crier.stream().anyMatch(l -> l.contains("constable")), "the crier cries it: " + crier);
        Kit.log("iv04 " + Interviews.nowForTests(level, v));
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);
            Interviews.stepForTests(level, v);
            if (iv.stage() != InterviewBook.Stage.DONE) {
                if (t > 5500) {
                    transcript("iv04", iv);
                    helper.fail("the interview never finished: " + iv.stage());
                }
                return;
            }
            transcript("iv04", iv);
            String winner = iv.winnerName();
            List<String> others = new ArrayList<>();
            for (InterviewBook.Cand c : iv.cands()) if (!c.id().equals(iv.winner())) others.add(c.name());
            String told = winner + " got the post of constable after";
            helper.assertTrue(newsHas(v, told) && newsHas(v, others.get(0)) && newsHas(v, "interview with"),
                "the chronicle tells who got it after an interview with whom: " + Villages.news(v.id()));
            boolean inChronicle = false;
            for (Chronicle.Entry e : Chronicle.of(v.id())) if (e.text().contains(told)) inChronicle = true;
            helper.assertTrue(inChronicle, "and it is written in the town's chronicle");
            List<String> after = Interviews.board(level, v.id());
            helper.assertTrue(after.stream().anyMatch(l -> l.contains("Interviewed day") && l.contains(winner)), "the board tells it: " + after);
            List<String> cried = Interviews.crierLines(level, v, day);
            helper.assertTrue(cried.stream().anyMatch(l -> l.contains(winner + " was chosen as our constable")), "the crier tells it: " + cried);
            String gazette = Interviews.gazette(v.id(), day + 1);
            helper.assertTrue(gazette != null && gazette.contains(winner + " got the post of constable"), "the next gazette carries it: " + gazette);
            VillageFolkEntity kept = Interviews.constableForTests(v.id());
            helper.assertTrue(kept != null && kept.getUUID().equals(iv.winner()), "the watch's constable is the panel's choice: "
                + (kept == null ? "none" : kept.displayNameCap()));
            helper.succeed();
        });
    }

    // ============================================================ iv05: a reference turns a close one

    /**
     * Two guards stand for constable, both of level six, cheerful and easygoing; Hale has eighteen hostiles on the
     * watch's tally and Juno six, so Hale is ahead on paper and with its tally. Juno's closest friend comes over to speak
     * for it, and a player the town counts its hero puts in a good word at the talk screen. Juno is chosen — and without
     * the references, Hale would have been.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "iv05_reference_turns_a_close_one")
    public static void iv05_reference_turns_a_close_one(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long base = start(level);
        int x = 1308000;
        Villages.Village v = town(level, x);
        Villages.ageForTests(v.id(), Villages.Age.IRON);
        VillageFolkEntity elder = folk(level, v, x - 4, Z + 6, StationTask.FARM, 3, 58, Social.Trait.GENEROUS, Social.Trait.HARDWORKING);
        VillageFolkEntity hale = folk(level, v, x - 1, Z + 6, StationTask.GUARD, 6, 30, Social.Trait.CHEERFUL, Social.Trait.EASYGOING);
        VillageFolkEntity juno = folk(level, v, x + 2, Z + 6, StationTask.GUARD, 6, 30, Social.Trait.CHEERFUL, Social.Trait.EASYGOING);
        VillageFolkEntity friend = folk(level, v, x + 5, Z + 6, StationTask.FARM, 3, 32, Social.Trait.GENEROUS, Social.Trait.SOCIABLE);
        List<VillageFolkEntity> rest = list(folk(level, v, x + 5, Z - 6, StationTask.FARM, 2, 40), folk(level, v, x + 2, Z - 6, StationTask.WOOD, 2, 38),
            folk(level, v, x - 1, Z - 6, StationTask.FISH, 2, 36));
        hale.note(AssistantEntity.Deed.MOBS_KILLED, 18);
        juno.note(AssistantEntity.Deed.MOBS_KILLED, 6);
        // Juno's friend, of long standing; the rest of the town cool on the friend (it sits on no council).
        friend.life().feel(juno.getUUID(), juno.displayNameCap(), 100);
        for (VillageFolkEntity o : rest) o.life().feel(friend.getUUID(), friend.displayNameCap(), -45);
        elder.life().feel(friend.getUUID(), friend.displayNameCap(), -45);
        makings(level, x);
        Villages.electElder(v.id(), elder, base / 24000L);
        // A player the town thinks the world of: its hero.
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        BlockPos heart = Kit.surface(level, x, Z);
        p.moveTo(heart.getX() + 12.5, heart.getY(), heart.getZ() + 12.5);
        String pn = p.getName().getString();
        List<VillageFolkEntity> all = list(elder, hale, juno, friend);
        all.addAll(rest);
        for (VillageFolkEntity f : all) {
            f.persona().feelFor(p.getUUID(), pn, 100);
            f.persona().met(p.getUUID());
        }
        Standing.stir(v.id(), p.getUUID());
        Kit.log("iv05 the town counts " + pn + " " + Standing.of(v.id(), p.getUUID(), level.getGameTime()).title().words);
        InterviewBook.Interview iv = Interviews.stageForTests(level, v, "constable");
        helper.assertTrue(iv != null && iv.cands().size() == 2, "the two guards stand for constable: " + (iv == null ? 0 : iv.cands().size()));
        String word = Interviews.recommendForTests(level, p, v.id(), "I'd recommend " + juno.displayNameCap() + " for the post");
        Kit.log("iv05 the player's word: " + word);
        helper.assertTrue(cand(iv, juno).refNames().stream().anyMatch(n -> n.contains(pn)), "the player's word is put in for Juno");
        Kit.log("iv05 " + Interviews.nowForTests(level, v));
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);
            Interviews.stepForTests(level, v);
            if (iv.stage() != InterviewBook.Stage.DONE) {
                if (t > 5500) {
                    transcript("iv05", iv);
                    Kit.noLeftoverPlayers(level);
                    helper.fail("the interview never finished: " + iv.stage());
                }
                return;
            }
            transcript("iv05", iv);
            Kit.noLeftoverPlayers(level);
            InterviewBook.Cand h = cand(iv, hale), j = cand(iv, juno);
            helper.assertTrue(h.paper() > j.paper(), "Hale is ahead on paper: " + h.paper() + " against " + j.paper());
            helper.assertTrue(j.refs() > 0 && j.refNames().size() >= 2, "Juno's friend and the player spoke for it: " + j.refNames());
            helper.assertTrue(said(iv, friend.displayNameCap() + "|"), "the friend walked over and spoke");
            helper.assertTrue(iv.winner().equals(juno.getUUID()), "the references carry it: " + iv.winnerName() + " (" + h.total() + " against " + j.total() + ")");
            helper.assertTrue(j.total() - j.refs() < h.total(), "without them Hale would have had it: " + (j.total() - j.refs()) + " against " + h.total());
            helper.succeed();
        });
    }

    // ============================================================ iv06: a boast seen through

    /**
     * Three guards stand for constable. Cole, three levels on the watch and the proud sort, says there is no better guard
     * in three towns; the panel counts ("Level 3, Cole. I can count."), it costs it, the huddle remembers it, and Cole is
     * told to let the work do the talking. Dara, sixteen levels and the best of the town, says much the same, and the
     * record bears her out.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "iv06_boast_seen_through")
    public static void iv06_boast_seen_through(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long base = start(level);
        int x = 1310000;
        Villages.Village v = town(level, x);
        Villages.ageForTests(v.id(), Villages.Age.IRON);
        VillageFolkEntity elder = folk(level, v, x - 4, Z + 6, StationTask.FARM, 3, 58, Social.Trait.GENEROUS, Social.Trait.EASYGOING);
        VillageFolkEntity cole = folk(level, v, x - 1, Z + 6, StationTask.GUARD, 3, 24, Social.Trait.SOCIABLE, Social.Trait.HARDWORKING);
        VillageFolkEntity dara = folk(level, v, x + 2, Z + 6, StationTask.GUARD, 16, 36, Social.Trait.HARDWORKING, Social.Trait.GRUMPY);
        VillageFolkEntity edda = folk(level, v, x + 5, Z + 6, StationTask.GUARD, 8, 29, Social.Trait.EASYGOING, Social.Trait.GENEROUS);
        folk(level, v, x + 5, Z - 6, StationTask.FARM, 2, 40);
        makings(level, x);
        Interviews.proudForTests(cole);
        Interviews.proudForTests(dara);
        Villages.electElder(v.id(), elder, base / 24000L);
        InterviewBook.Interview iv = Interviews.stageForTests(level, v, "constable");
        helper.assertTrue(iv != null && iv.cands().size() == 3, "three guards stand");
        Kit.log("iv06 " + Interviews.nowForTests(level, v));
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);
            Interviews.stepForTests(level, v);
            if (iv.stage() != InterviewBook.Stage.DONE) {
                if (t > 5500) {
                    transcript("iv06", iv);
                    helper.fail("the interview never finished: " + iv.stage());
                }
                return;
            }
            transcript("iv06", iv);
            InterviewBook.Cand c = cand(iv, cole), d = cand(iv, dara);
            String cn = cole.displayNameCap();
            boolean boasted = false;
            for (String l : iv.said()) {
                if (l.startsWith(cn + "|") && (l.contains("no better guard in three towns") || l.contains("knows guard duty like I do"))) boasted = true;
            }
            helper.assertTrue(boasted, "Cole boasts");
            helper.assertTrue(said(iv, "Level 3, " + cn + ". I can count."), "the panel counts");
            helper.assertTrue(c.boast() < 0, "the boast seen through costs it: " + c.boast());
            helper.assertTrue(said(iv, cn + " — the best in three towns, it says"), "the huddle remembers it");
            helper.assertTrue(!iv.winner().equals(cole.getUUID()) && c.why().contains("let the work do the talking"),
                "Cole is told to let the work do the talking: " + c.why());
            helper.assertTrue(d.boast() > 0 && said(iv, "That's no idle boast"), "a boast the record bears out counts: " + d.boast());
            helper.succeed();
        });
    }

    // ============================================================ iv07: the teacher, from the town's own

    /**
     * The town's school wants a teacher. With the towns holding their interviews, the post falls vacant: the school's
     * own choosing picks its best, but two more of the town's own want it, so the post is held open for interview. The
     * three are interviewed ("A child won't learn its letters. What then?"), and the best of them takes the school.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "iv07_teacher_from_the_towns_own")
    public static void iv07_teacher_from_the_towns_own(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long base = start(level);
        int x = 1312000;
        Villages.Village v = town(level, x);
        VillageFolkEntity elder = folk(level, v, x - 4, Z + 6, StationTask.FARM, 3, 60, Social.Trait.GENEROUS, Social.Trait.EASYGOING);
        VillageFolkEntity ona = folk(level, v, x - 1, Z + 6, StationTask.FARM, 9, 52, Social.Trait.CURIOUS, Social.Trait.EASYGOING);
        VillageFolkEntity tam = folk(level, v, x + 2, Z + 6, StationTask.WOOD, 6, 41, Social.Trait.SOCIABLE, Social.Trait.CHEERFUL);
        VillageFolkEntity rue = folk(level, v, x + 5, Z + 6, StationTask.FISH, 4, 33, Social.Trait.GRUMPY, Social.Trait.HARDWORKING);
        folk(level, v, x + 5, Z - 6, StationTask.GUARD, 2, 40);                 // the watch does not teach
        makings(level, x);
        Villages.electElder(v.id(), elder, base / 24000L);
        for (VillageFolkEntity f : List.of(ona, tam, rue)) Interviews.keenForTests(f);
        Interviews.liveForTests(true);
        VillageFolkEntity now = School.teacher(level, v.id(), true);
        InterviewBook.Interview iv = Interviews.pendingForTests(v.id(), "teacher");
        Interviews.liveForTests(false);
        helper.assertTrue(now == null && iv != null, "the post is held open for its interview, not given: " + (now == null ? "nobody yet" : now.displayNameCap()));
        helper.assertTrue(iv.cands().size() == 3, "the three who want it stand: " + iv.cands().size());
        helper.assertTrue(Interviews.board(level, v.id()).stream().anyMatch(l -> l.contains("schoolteacher")), "the board shows it");
        Kit.log("iv07 " + Interviews.nowForTests(level, v));
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);
            Interviews.stepForTests(level, v);
            if (iv.stage() != InterviewBook.Stage.DONE) {
                if (t > 5500) {
                    transcript("iv07", iv);
                    helper.fail("the interview never finished: " + iv.stage());
                }
                return;
            }
            transcript("iv07", iv);
            InterviewBook.Cand top = best(iv);
            helper.assertTrue(iv.winner().equals(top.id()), "the best of them is chosen: " + iv.winnerName() + " against " + top.name());
            helper.assertTrue(said(iv, "won't learn its letters"), "a question a teacher really meets");
            for (VillageFolkEntity f : List.of(ona, tam, rue)) {
                boolean chosen = f.getUUID().equals(iv.winner());
                helper.assertTrue(School.isTeacher(f) == chosen, f.displayNameCap() + (chosen ? " takes the school" : " does not"));
            }
            helper.succeed();
        });
    }

    // ============================================================ iv08: a player who leads chooses

    /**
     * A player leads the town. The schoolteacher's post goes to interview with the player in the chair (a councillor
     * puts the questions in its name). The player stands by the table, is greeted, and when the panel has heard them
     * all chooses — through the command its interview page's buttons run — the weakest of the three. Its choice stands.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "iv08_player_leader_chooses")
    public static void iv08_player_leader_chooses(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long base = start(level);
        int x = 1314000;
        Villages.Village v = town(level, x);
        List<VillageFolkEntity> everyone = list(
            folk(level, v, x - 4, Z + 6, StationTask.GUARD, 3, 58, Social.Trait.GENEROUS, Social.Trait.EASYGOING),
            folk(level, v, x - 1, Z + 6, StationTask.FARM, 12, 55, Social.Trait.CURIOUS, Social.Trait.EASYGOING),
            folk(level, v, x + 2, Z + 6, StationTask.WOOD, 6, 41, Social.Trait.SOCIABLE, Social.Trait.CHEERFUL),
            folk(level, v, x + 5, Z + 6, StationTask.FISH, 2, 23, Social.Trait.GRUMPY, Social.Trait.SHY),
            folk(level, v, x + 5, Z - 6, StationTask.GUARD, 2, 40));
        makings(level, x);
        long day = base / 24000L;
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        BlockPos heart = Kit.surface(level, x, Z);
        p.moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5);
        String pn = p.getName().getString();
        Ledger.addCitizen(v.id(), p.getUUID(), pn);
        Ledger.note(v.id(), "civic.leader", p.getUUID() + "|" + pn + "|" + day);
        Villages.electElder(v.id(), p.getUUID(), pn, day, null);
        InterviewBook.Interview iv = Interviews.stageForTests(level, v, "teacher");
        helper.assertTrue(iv != null && iv.cands().size() == 3, "three stand for the schoolteacher's post");
        // The leader stands by the table to watch, and to choose.
        BlockPos[] table = Interviews.tableForTests(iv);
        if (table != null) p.moveTo(table[0].getX() + 2.5, table[0].getY(), table[0].getZ() + 2.5);
        Kit.log("iv08 " + Interviews.nowForTests(level, v) + " at " + Interviews.whereForTests(iv));
        String[] chose = { null };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);
            Interviews.stepForTests(level, v);
            if (chose[0] == null && iv.stage() == InterviewBook.Stage.CONFERRING) {
                // The weakest on paper and at interview together: the leader's own reasons.
                InterviewBook.Cand low = null;
                for (InterviewBook.Cand c : iv.cands()) if (low == null || c.score() < low.score()) low = c;
                chose[0] = low.name();
                Kit.log("iv08 tick " + t + ": the leader chooses " + low.name() + ": " + Interviews.choose(level, p, low.name()));
            }
            if (iv.stage() != InterviewBook.Stage.DONE) {
                if (t > 5500) {
                    transcript("iv08", iv);
                    Kit.noLeftoverPlayers(level);
                    helper.fail("the interview never finished: " + iv.stage());
                }
                return;
            }
            transcript("iv08", iv);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(chose[0] != null && iv.winnerName().equals(chose[0]), "the leader's choice stands: " + iv.winnerName() + " (chose " + chose[0] + ")");
            InterviewBook.Cand top = best(iv);
            helper.assertTrue(!top.name().equals(chose[0]), "it was not the panel's best (" + top.name() + "): the choice was the leader's");
            helper.assertTrue(said(iv, pn + " has chosen"), "the chair says it is the leader's choice");
            helper.assertTrue(said(iv, pn), "the leader was greeted at the table");
            VillageFolkEntity won = null;
            for (VillageFolkEntity f : everyone) if (f.getUUID().equals(iv.winner())) won = f;
            helper.assertTrue(won != null && School.isTeacher(won), (won == null ? "?" : won.displayNameCap()) + " takes the school");
            helper.assertTrue(iv.reason().contains("own choice"), "the reason given is the leader's own choice: " + iv.reason());
            helper.succeed();
        });
    }

    // ============================================================ iv09: the letter of application

    /**
     * Three stand for the schoolteacher, each with a little coin. Each writes its letter of application by the real
     * recipe (a paper and an ink sac, shapeless) out of the town's stores, paying the stores' price into the treasury;
     * the letter names its writer and the post. While each waits on the bench, its letter is in its hand; across the
     * table it hands it over and the chair holds it while it reads. Afterwards each still has its own letter.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "iv09_letter_from_the_stores")
    public static void iv09_letter_from_the_stores(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long base = start(level);
        int x = 1316000;
        Villages.Village v = town(level, x);
        VillageFolkEntity elder = folk(level, v, x - 4, Z + 6, StationTask.FARM, 3, 58, Social.Trait.GENEROUS, Social.Trait.EASYGOING);
        VillageFolkEntity ona = folk(level, v, x - 1, Z + 6, StationTask.FARM, 9, 52, Social.Trait.CURIOUS, Social.Trait.EASYGOING);
        VillageFolkEntity tam = folk(level, v, x + 2, Z + 6, StationTask.WOOD, 6, 41, Social.Trait.SOCIABLE, Social.Trait.CHEERFUL);
        VillageFolkEntity rue = folk(level, v, x + 5, Z + 6, StationTask.FISH, 4, 33, Social.Trait.HARDWORKING, Social.Trait.GENEROUS);
        folk(level, v, x + 5, Z - 6, StationTask.GUARD, 2, 40);
        stores(level, x, 7, 7, new ItemStack(Items.OAK_PLANKS, 32), new ItemStack(Items.PAPER, 4), new ItemStack(Items.INK_SAC, 4));
        Villages.electElder(v.id(), elder, base / 24000L);
        // The recipe is a real one: a paper and an ink sac, shapeless.
        boolean recipe = false;
        for (var h : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (!h.value().getResultItem(level.registryAccess()).is(InterviewItems.LETTER_OF_APPLICATION.get())) continue;
            List<Ingredient> in = h.value().getIngredients();
            recipe = in.size() == 2 && in.stream().anyMatch(i -> i.test(new ItemStack(Items.PAPER))) && in.stream().anyMatch(i -> i.test(new ItemStack(Items.INK_SAC)));
        }
        helper.assertTrue(recipe, "the letter has a real recipe: a paper and an ink sac");
        int paper = Market.stock(level, v.id(), s -> s.is(Items.PAPER)), ink = Market.stock(level, v.id(), s -> s.is(Items.INK_SAC));
        int coins = Ledger.coins(v.id());
        int purses = ona.purse() + tam.purse() + rue.purse();
        InterviewBook.Interview iv = Interviews.stageForTests(level, v, "teacher");
        helper.assertTrue(iv != null && iv.cands().size() == 3, "three stand");
        int paper2 = Market.stock(level, v.id(), s -> s.is(Items.PAPER)), ink2 = Market.stock(level, v.id(), s -> s.is(Items.INK_SAC));
        int purses2 = ona.purse() + tam.purse() + rue.purse();
        Kit.log("iv09 paper " + paper + " -> " + paper2 + ", ink " + ink + " -> " + ink2 + ", purses " + purses + " -> " + purses2
            + ", treasury " + coins + " -> " + Ledger.coins(v.id()));
        helper.assertTrue(paper - paper2 == 3 && ink - ink2 == 3, "a sheet and an ink sac out of the stores for each letter");
        helper.assertTrue(purses2 < purses && Ledger.coins(v.id()) - coins == purses - purses2, "paid for out of their purses, into the treasury");
        for (VillageFolkEntity f : List.of(ona, tam, rue)) {
            ItemStack letter = ItemStack.EMPTY;
            for (ItemStack s : f.getInventoryItems()) if (s.is(InterviewItems.LETTER_OF_APPLICATION.get())) letter = s;
            CompoundTag w = LetterOfApplicationItem.words(letter);
            Kit.log("iv09 " + f.displayNameCap() + "'s letter: " + w.getString("Words"));
            helper.assertTrue(!letter.isEmpty() && w.getString("Name").equals(f.displayNameCap()) && w.getString("Post").equals("schoolteacher"),
                f.displayNameCap() + " carries its own letter for the schoolteacher's post");
        }
        Kit.log("iv09 " + Interviews.nowForTests(level, v));
        Set<UUID> heldWaiting = new HashSet<>();
        boolean[] chairHeld = { false };
        VillageFolkEntity chair = elder;
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);
            Interviews.stepForTests(level, v);
            if (iv.stage() == InterviewBook.Stage.SITTING) {
                for (VillageFolkEntity f : List.of(ona, tam, rue)) {
                    ItemStack off = f.getOffhandItem();
                    if (Interviews.onTheBenchForTests(f) && off.is(InterviewItems.LETTER_OF_APPLICATION.get())
                            && LetterOfApplicationItem.words(off).getString("Name").equals(f.displayNameCap()) && heldWaiting.add(f.getUUID())) {
                        Kit.log("iv09 tick " + t + ": " + f.displayNameCap() + " waits on the bench, its letter in its hand");
                    }
                }
                if (!chairHeld[0] && chair.getOffhandItem().is(InterviewItems.LETTER_OF_APPLICATION.get())) {
                    chairHeld[0] = true;
                    Kit.log("iv09 tick " + t + ": the chair holds " + chair.getOffhandItem().getHoverName().getString() + " while it reads");
                }
            }
            if (iv.stage() != InterviewBook.Stage.DONE) {
                if (t > 5500) {
                    transcript("iv09", iv);
                    helper.fail("the interview never finished: " + iv.stage());
                }
                return;
            }
            transcript("iv09", iv);
            helper.assertTrue(heldWaiting.size() >= 2, "they held their letters while waiting on the bench: " + heldWaiting.size());
            helper.assertTrue(chairHeld[0], "the chair held a letter while it read it");
            for (VillageFolkEntity f : List.of(ona, tam, rue)) {
                int n = 0;
                for (ItemStack s : f.getInventoryItems()) if (s.is(InterviewItems.LETTER_OF_APPLICATION.get())) n++;
                if (f.getOffhandItem().is(InterviewItems.LETTER_OF_APPLICATION.get())) n++;
                helper.assertTrue(n == 1, f.displayNameCap() + " keeps its own letter: " + n);
            }
            helper.succeed();
        });
    }

    // ============================================================ iv10: one from away comes for its interview

    /**
     * A new smithy in Ashford (A) wants its first keeper. Pell, a smith of level eight in Brookby (B), two hundred blocks
     * off, applies; one of A's own, idle but with six levels at the forge, wants it too. The two are shortlisted together.
     * Pell writes its letter out of B's stores, sets off along the road, walks the two hundred blocks (the ground it
     * crosses kept awake), waits by A's board, and when the interviews begin sits on the bench and is called across the
     * table.
     */
    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "iv10_outside_candidate_arrives")
    public static void iv10_outside_candidate_arrives(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long base = start(level);
        int ax = 1318000, bx = 1318200;
        Villages.Village a = town(level, ax), b = town(level, bx);
        road(level, ax, bx);
        VillageFolkEntity elderA = folk(level, a, ax - 4, Z + 6, StationTask.FARM, 3, 56, Social.Trait.GENEROUS, Social.Trait.HARDWORKING);
        folk(level, a, ax - 1, Z + 6, StationTask.FARM, 2, 30);
        folk(level, a, ax + 2, Z + 6, StationTask.WOOD, 2, 33);
        folk(level, a, ax + 5, Z + 6, StationTask.FARM, 2, 41);
        VillageFolkEntity own = folk(level, a, ax + 5, Z - 6, StationTask.NONE, 0, 27, Social.Trait.HARDWORKING, Social.Trait.CURIOUS);
        knows(own, StationTask.SMITH, 6);
        own.setJob(StationTask.NONE);
        Interviews.keenForTests(own);
        makings(level, ax);
        beds(level, a.centre(), 8);
        Villages.recountBeds(a.id());
        VillageFolkEntity elderB = folk(level, b, bx - 4, Z + 6, StationTask.FARM, 2, 50);
        folk(level, b, bx - 1, Z + 6, StationTask.FARM, 2, 34);
        folk(level, b, bx + 2, Z + 6, StationTask.WOOD, 2, 39);
        folk(level, b, bx + 5, Z + 6, StationTask.FARM, 2, 45);
        VillageFolkEntity pell = folk(level, b, bx - 3, Z - 5, StationTask.SMITH, 8, 31, Social.Trait.CHEERFUL, Social.Trait.SOCIABLE);
        pell.rename("Pell");
        stores(level, bx, 7, 7, new ItemStack(Items.PAPER, 4), new ItemStack(Items.INK_SAC, 4));
        long day = base / 24000L;
        Villages.electElder(a.id(), elderA, day);
        Villages.electElder(b.id(), elderB, day);
        pact(a, b);
        JobMarket.Opening o = JobMarket.postFor(level, a, StationTask.SMITH);
        Interviews.applyForTests(level, pell, a.id(), o);
        JobMarket.decideAfterForTests(0L);
        Interviews.considerForTests(level, a);
        InterviewBook.Interview iv = Interviews.pendingForTests(a.id(), "opening:" + o.id());
        for (String l : Interviews.status(level, a.id())) Kit.log("iv10 " + l);
        helper.assertTrue(iv != null && iv.cands().size() == 2 && cand(iv, pell) != null && cand(iv, own) != null,
            "the applicant from Brookby and one of Ashford's own are shortlisted together: " + (iv == null ? 0 : iv.cands().size()));
        helper.assertTrue(cand(iv, pell).outside() && cand(iv, pell).letter(), "Pell is from away, and wrote its letter at home");
        String card = Interviews.cardLine(pell);
        helper.assertTrue(card.contains("Shortlisted") && card.contains(Villages.name(a.id())), "its card says it is shortlisted there: " + card);
        Kit.log("iv10 " + Interviews.nowForTests(level, a));
        int[] stage = { 0 };
        long[] since = { 0 };
        double[] start = { pell.distanceToSqr(a.centre().getX(), pell.getY(), a.centre().getZ()) };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(base + HOUR);
            Interviews.stepForTests(level, a);
            InterviewBook.Cand pc = cand(iv, pell);
            switch (stage[0]) {
                case 0 -> {
                    if (t % 100 == 0) Kit.log("iv10 tick " + t + ": Pell at " + pell.blockPosition().toShortString() + ", "
                        + String.format(Locale.ROOT, "%.0f", Math.sqrt(pell.distanceToSqr(a.centre().getX(), pell.getY(), a.centre().getZ())))
                        + " from Ashford's heart (" + Interviews.roleForTests(pell) + "); " + FolkTalk.nowDoing(pell));
                    if (pc.arrived()) {
                        double d = Math.sqrt(pell.distanceToSqr(a.centre().getX(), pell.getY(), a.centre().getZ()));
                        Kit.log("iv10 tick " + t + ": Pell arrived, " + String.format(Locale.ROOT, "%.0f", d) + " from the heart (it set out "
                            + String.format(Locale.ROOT, "%.0f", Math.sqrt(start[0])) + " off); " + Interviews.nowForTests(level, a));
                        helper.assertTrue(d < 24, "it walked all the way to Ashford: " + d);
                        helper.assertTrue(pell.ownerId().equals(b.id()), "still Brookby's own while it is only visiting");
                        stage[0] = 1;
                        since[0] = t;
                    } else if (t > 5000) {
                        helper.fail("Pell never got to Ashford: " + pell.debugLine() + " at " + pell.blockPosition().toShortString());
                    }
                }
                case 1 -> {
                    if (iv.stage() == InterviewBook.Stage.SET && t - since[0] > 40) Kit.log("iv10 " + Interviews.nowForTests(level, a));
                    // Sat across the table, it greets the panel and answers the master: two lines of its own, at least.
                    if (Interviews.satForTests(iv).contains(pell.getUUID()) && linesBy(iv, pell) >= 2) {
                        transcript("iv10", iv);
                        InterviewBook.Cand oc = cand(iv, own);
                        Kit.log("iv10 Pell said " + linesBy(iv, pell) + " lines; " + own.displayNameCap() + " (Ashford's own) "
                            + (oc.absent() ? "absent" : "here") + ", said " + linesBy(iv, own));
                        // (Laid up with a cold is the one good reason not to come: Health.)
                        helper.assertTrue(!oc.absent() || com.jrpetty.mcassistant.entity.Health.laidUp(own), own.displayNameCap()
                            + ", one of Ashford's own, was there for its interview, not off on its own business: " + FolkTalk.nowDoing(own));
                        helper.succeed();
                    } else if (t - since[0] > 3500) {
                        transcript("iv10", iv);
                        helper.fail("Pell never sat across the table and spoke: " + iv.stage() + ", " + linesBy(iv, pell) + " lines");
                    }
                }
                default -> { }
            }
        });
    }
}
