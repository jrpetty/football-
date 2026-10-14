package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.client.TalkMenu;
import com.jrpetty.mcassistant.client.TalkMenu.Category;
import com.jrpetty.mcassistant.client.TalkMenu.Context;
import com.jrpetty.mcassistant.client.TalkMenu.Entry;
import com.jrpetty.mcassistant.client.TalkMenu.Kind;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.net.FolkReplyPayload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The talk screen's menu (client/TalkMenu), reorganised from seven tabs and some ninety buttons into six categories of
 * a few options each: nothing the old screen offered fell out of it (every topic, every phrase said for you, the board,
 * the pages, the record and the pack); no category's own list runs past six; what only applies sometimes (a fine, a
 * godparent, a cartographer's maps, a Nether runner's chart) shows only then, and everything shows to an older server
 * that sends no context; the suggestions put the pressing thing first; every option has its words and its tooltip in
 * the language file; and the server's side (FolkTalk.talkContext, FolkReplyPayload) sends what the menu reads.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TalkMenuGameTests {

    private static final String EMPTY = "empty";
    /** Ground of its own for the one test that needs a folk. */
    private static final int X = -236000, Z = -61000;

    /** Every topic the old screen had a button for (TalkScreen before the menu), and the pack it opened. */
    private static final Set<TalkTopic> OLD_TOPICS = EnumSet.of(
        TalkTopic.HOW, TalkTopic.DOING, TalkTopic.ABOUT, TalkTopic.PEOPLE, TalkTopic.PET, TalkTopic.HOUSE, TalkTopic.VILLAGE,
        TalkTopic.DREAMS, TalkTopic.MEMORY, TalkTopic.HOBBY, TalkTopic.GOSSIP, TalkTopic.JOKE, TalkTopic.PRAISE, TalkTopic.JOBS,
        TalkTopic.HELP, TalkTopic.FAVOUR, TalkTopic.SHORT, TalkTopic.WORKINGS, TalkTopic.KNACK, TalkTopic.WORTH, TalkTopic.GUIDE,
        TalkTopic.STORES, TalkTopic.ORDERS, TalkTopic.QUESTS, TalkTopic.LEDGER, TalkTopic.TOWN_MAP, TalkTopic.LOST,
        TalkTopic.CHRONICLE, TalkTopic.CENSUS, TalkTopic.COUNCIL, TalkTopic.RIVALS, TalkTopic.LETTER, TalkTopic.ATLAS,
        TalkTopic.CAVES, TalkTopic.FASHION, TalkTopic.NETHER, TalkTopic.REPUTE, TalkTopic.CITIZEN, TalkTopic.FINE, TalkTopic.WATCH,
        TalkTopic.BUILD, TalkTopic.GIFT, TalkTopic.DELIVER, TalkTopic.TRADE, TalkTopic.FOR_SALE, TalkTopic.HIRE,
        TalkTopic.COMMISSION, TalkTopic.STAY, TalkTopic.FOLLOW, TalkTopic.MEAL, TalkTopic.DICE, TalkTopic.TEACH,
        TalkTopic.GODPARENT, TalkTopic.KEEPSAKE, TalkTopic.SPONSOR, TalkTopic.BULK, TalkTopic.CONTRACT, TalkTopic.STALL,
        TalkTopic.BANK, TalkTopic.INVEST, TalkTopic.AUCTION, TalkTopic.ESCORT, TalkTopic.CHARTER, TalkTopic.HOUSING,
        TalkTopic.PRICES, TalkTopic.HAGGLE, TalkTopic.ORDER, TalkTopic.REPAIR, TalkTopic.MAPS);

    /** Every phrase the old screen said (or put in the box) for you. */
    private static final List<String> OLD_PHRASES = List.of(
        "I'm sorry", "What's this town like?", "Can I come through with you?", "Could I buy a copy of your chart?",
        "Can I come along with the cave team?", "Could I buy a copy of the cave map?", "When are the next fireworks?",
        "Could I buy some rockets for my elytra?", "I want to report a crime", "Who's on the roster today?",
        "Swear me in as a special constable", "I'd like to stand for election", "Will you vote for me?",
        "Show me the leader's page", "Any interviews coming up?", "Will you take me as your apprentice?",
        "What's my next lesson?", "I'd like an ocean explorer map", "I'd like a woodland explorer map", "I'd like a treasure map",
        "A copy of the town's map, please", "Could I have 8 bread?", "I'd like to order 64 ", "I'd like to invest 50 coins",
        "Make me an iron sword", "Map me the land to the east", "Runners, bring us blaze rods", "Cave team, look east",
        "You should build a ");

    private static Context ctx(String line) {
        return Context.of(line, 0, false, false, false);
    }

    private static boolean shows(Category c, Context x, String id) {
        for (Entry e : TalkMenu.visible(c.entries(), x)) {
            if (e.id().equals(id)) return true;
            if (e.isGroup()) for (Entry k : TalkMenu.visible(e.children(), x)) if (k.id().equals(id)) return true;
        }
        return false;
    }

    /**
     * The menu as data: nothing lost, six categories of six at most, ids unique, the language file complete, what
     * applies only sometimes shown only then (and everything to an older server), and the suggestions in order.
     */
    @GameTest(template = EMPTY, timeoutTicks = 40, batch = "tm01_menu")
    public static void tm01_menu(GameTestHelper helper) {
        // Nothing the old screen offered fell out.
        Set<TalkTopic> topics = TalkMenu.topics();
        Set<TalkTopic> lost = EnumSet.noneOf(TalkTopic.class);
        for (TalkTopic t : OLD_TOPICS) if (!topics.contains(t) && t != TalkTopic.GUIDE) lost.add(t);
        helper.assertTrue(lost.isEmpty(), "every topic the old screen offered is in the menu; missing: " + lost);
        Set<String> phrases = TalkMenu.phrases();
        for (String p : OLD_PHRASES) {
            helper.assertTrue(phrases.contains(p.toLowerCase(Locale.ROOT)), "the menu still says \"" + p + "\"");
        }
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        for (Entry e : TalkMenu.all()) kinds.add(e.kind());
        helper.assertTrue(kinds.containsAll(EnumSet.of(Kind.BOARD, Kind.PAGE, Kind.RECORD, Kind.PACK, Kind.PLACES, Kind.PROMPT)),
            "the board, the profile and skills pages, the work record, the pack and the way somewhere are all still there: " + kinds);

        // Six categories, none past six options of its own, whatever stands (an errand makes Trade's sixth).
        helper.assertTrue(Category.values().length == 6, "six categories");
        Context[] cases = { Context.NONE, ctx("v1 trade=FARM standing=FRIEND"), Context.of("v1 trade=CARTOGRAPHER fine", 0, true, true, true) };
        for (Context c : cases) {
            for (Category cat : Category.values()) {
                int n = TalkMenu.visible(cat.entries(), c).size();
                helper.assertTrue(n >= 3 && n <= 6, cat + " has " + n + " options of its own (3 to 6) for " + c);
            }
        }

        // Ids unique; every option and category has its words and its tooltip in the language file.
        List<String> ids = TalkMenu.ids();
        helper.assertTrue(new HashSet<>(ids).size() == ids.size(), "every option's id is its own: " + ids);
        JsonObject lang;
        try (var in = TalkMenuGameTests.class.getClassLoader().getResourceAsStream("assets/mc_assistant/lang/en_us.json")) {
            helper.assertTrue(in != null, "the language file is on the classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        for (Entry e : TalkMenu.all()) {
            helper.assertTrue(lang.has(e.labelKey()) && lang.has(e.tipKey()), "words and a tooltip for " + e.id());
        }
        for (Category c : Category.values()) {
            helper.assertTrue(lang.has(c.labelKey()) && lang.has(c.tipKey()), "words and a tooltip for the " + c.id + " category");
        }

        // Only when it applies.
        Context farmer = ctx("v1 trade=FARM standing=FRIEND");
        Context mapper = ctx("v1 trade=CARTOGRAPHER standing=FRIEND");
        Context child = ctx("v1 trade=CHILD child standing=VISITOR");
        Context runner = ctx("v1 trade=NETHER standing=FRIEND");
        helper.assertTrue(!shows(Category.WORLD, farmer, "map_ocean") && shows(Category.WORLD, mapper, "map_ocean"),
            "an explorer map is the cartographer's to sell");
        helper.assertTrue(shows(Category.WORLD, farmer, "town_map") && shows(Category.WORLD, farmer, "maps"),
            "the town map and the maps' report from anyone");
        helper.assertTrue(!shows(Category.WORLD, farmer, "nether_chart") && shows(Category.WORLD, runner, "nether_chart")
            && shows(Category.WORLD, farmer, "nether_report"), "the runners' chart from a runner; their report from anyone");
        helper.assertTrue(!shows(Category.ABOUT, farmer, "godparent") && shows(Category.ABOUT, child, "godparent"),
            "a godparent is a child's to have");
        helper.assertTrue(!shows(Category.ASK, child, "hire") && !shows(Category.ABOUT, child, "teach"), "nobody hires or teaches a child");
        helper.assertTrue(!shows(Category.TOWN, farmer, "fine") && shows(Category.TOWN, ctx("v1 trade=FARM fine"), "fine"),
            "a fine to pay only when one is owed");
        helper.assertTrue(!shows(Category.TRADE, farmer, "deliver")
            && shows(Category.TRADE, Context.of("v1 trade=FARM", 0, false, true, false), "deliver"), "a hand-over only with an errand");
        helper.assertTrue(shows(Category.ASK, Context.NONE, "follow") && !shows(Category.ASK, Context.NONE, "stay")
            && shows(Category.ASK, Context.of("", 0, true, false, false), "stay"), "come with me, or go home when it is with you");
        for (Entry e : TalkMenu.all()) {
            if (e.kind() == Kind.ASK && e.topic() == TalkTopic.STAY) continue;   // the one shown only while it walks with you
            helper.assertTrue(e.shows(Context.NONE) || e.id().equals("deliver"), "an older server (no context) sees " + e.id());
        }

        // The suggestions: the pressing thing first, three at most, the trade's own last.
        Context busy = Context.of("v1 trade=CARTOGRAPHER standing=FRIEND fine birthday", 0, false, true, false);
        List<String> sugg = TalkMenu.suggested(busy).stream().map(Entry::id).toList();
        helper.assertTrue(sugg.equals(List.of("deliver", "fine", "birthday")), "its request, the fine, its birthday: " + sugg);
        List<String> quiet = TalkMenu.suggested(mapper).stream().map(Entry::id).toList();
        helper.assertTrue(quiet.equals(List.of("maps")), "nothing pressing: what the cartographer is the one to ask about: " + quiet);
        List<String> outcast = TalkMenu.suggested(Context.of("v1 trade=FARM standing=UNWELCOME", -30, false, false, false))
            .stream().map(Entry::id).toList();
        helper.assertTrue(outcast.contains("sorry"), "out of favour: an apology: " + outcast);
        Context shunned = Context.of("v1 trade=FARM standing=OUTCAST", -30, false, false, false);
        List<String> shunnedSugg = TalkMenu.suggested(shunned).stream().map(Entry::id).toList();
        helper.assertTrue(shunned.shunned() && shunnedSugg.contains("gift") && !shunnedSugg.contains("sorry"),
            "an outcast it dislikes is shunned: a gift may be heard, an apology is not (FolkTalk.answer): " + shunnedSugg);
        helper.assertTrue(TalkMenu.suggested(Context.NONE).isEmpty(), "nothing suggested from an older server's silence");
        Kit.log("tm01 " + Category.values().length + " categories, " + ids.size() + " options; suggested " + sugg);
        helper.succeed();
    }

    /** The server's half: a folk's context line, carried by the reply's codec, and an older reply's empty one. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "tm02_context")
    public static void tm02_context(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 8 + 7000);
        Kit.hold(level, X, Z, 32);
        Kit.prepare(level, X, Z, 32);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, X, Z), 0.0F);
        helper.assertTrue(f != null, "a village");
        helper.runAtTickTime(10, () -> {
            f.setJob(StationTask.CARTOGRAPHER);
            ServerPlayer you = helper.makeMockServerPlayerInLevel();
            String line = FolkTalk.talkContext(f, you);
            Kit.log("tm02 context: " + line);
            Context c = Context.of(line, 0, false, false, false);
            helper.assertTrue(c.known() && c.tradeIs("CARTOGRAPHER") && !c.standing().isEmpty(),
                "its trade and your standing: " + line);
            helper.assertTrue(!c.has("child") && !c.has("fine") && !c.has("leader"), "nothing that isn't so: " + line);
            helper.assertTrue(TalkMenu.suggested(c).stream().anyMatch(e -> e.id().equals("maps")), "the cartographer's maps suggested");
            FolkReplyPayload reply = new FolkReplyPayload(f.getId(), true, "n", "a", "s", 60, "content", 0, "a stranger", false,
                "", "", "", false, "", "", "", "", line + " show=trade guiscale=3");
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
            FolkReplyPayload.STREAM_CODEC.encode(buf, reply);
            FolkReplyPayload got = FolkReplyPayload.STREAM_CODEC.decode(buf);
            helper.assertTrue(got.context().equals(reply.context()), "the context through the codec");
            Context shown = Context.of(got.context(), 0, false, false, false);
            helper.assertTrue(shown.value("show").equals("trade") && shown.value("guiscale").equals("3"), "the operator's view asked for");
            FolkReplyPayload older = new FolkReplyPayload(f.getId(), true, "n", "a", "s", 60, "content", 0, "a stranger", false,
                "", "", "", false, "", "", "", "");
            helper.assertTrue(older.context().isEmpty() && !Context.of(older.context(), 0, false, false, false).known(),
                "a reply made the old way has no context: the screen offers everything");
            f.discard();
            helper.succeed();
        });
    }
}
