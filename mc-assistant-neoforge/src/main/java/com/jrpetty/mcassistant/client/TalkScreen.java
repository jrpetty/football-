package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.net.FolkReplyPayload;
import com.jrpetty.mcassistant.net.FolkTalkPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Face to face with a folk.
 *
 * <p>Along the top: its likeness, who it is, how it feels, what it thinks of you, and what
 * it is doing this minute. Then the tabs — <b>Talk</b> (how it is, its family, its news,
 * a joke, a kind word), <b>Ask</b> (help, favours, what it is good at, what it earns, the
 * way somewhere, the stores, the elder's orders, the quest board, the ledger, the history),
 * <b>Village</b> (who lives here, the council, the neighbours, your standing, citizenship,
 * the board), <b>Deal</b> (gifts, errands, trade, hiring, a house, a walk together) and
 * <b>About</b> (its card: trade and levels, how its nature suits its work, what it is
 * worth, its home, family, friends, hopes, needs and latest memory) and <b>Skills</b> (the
 * knacks it chose for itself: its trades' levels and how near its next knack point is, the
 * points earned, spent and free, each knack it chose as a card with what it does, why it
 * chose it and when, and its tree of knacks, the ones still open to it greyed) — and its Pack
 * and its Work record a click away. The conversation runs down the middle and is kept while you
 * are in the world, so you can pick up where you left off; anything can be typed at the foot.
 */
public class TalkScreen extends Screen {

    private static final int PAD = 8, HEADER = 56, TAB_H = 16, ROW = 16, LINE = 10;

    private static final int BG = 0xF0181B22, BAND = 0xFF20242E, EDGE = 0xFF8A7A50, SOFT = 0xFF3A3F4C,
        GOLD = 0xFFE8C46A, INK = 0xFFEDEDED, MUTED = 0xFF9AA3B2, YOU = 0xFF9EC3EA, LOG = 0x80000000,
        TAB = 0xFF2A2F3A, TAB_ON = 0xFF51441F, NAV = 0xFF22303A, ERRAND = 0xFFFFC857, PINK = 0xFFF0A0B8;

    private enum Tab {
        TALK("Talk"), ASK("Ask"), VILLAGE("Village"), DEAL("Deal"), MONEY("Money"), ABOUT("About"), SKILLS("Skills");
        final String label;
        Tab(String label) { this.label = label; }
    }

    /** One thing said, by you or by it. */
    private record Line(boolean you, String text) {}

    /** What has been said with each folk this session, so a conversation picks up again. */
    private static final Map<Integer, List<Line>> HISTORY = new HashMap<>();
    private static Tab lastTab = Tab.TALK;

    private FolkReplyPayload last;
    private Tab tab = lastTab;
    private boolean places;
    private int scroll, aboutScroll, skillsScroll, skillsMax;
    private EditBox say;
    private String draft = "";
    private Button gift, deliver;
    private boolean saidBye;
    private int w, h, left, top;
    private final List<int[]> tabRects = new ArrayList<>();     // x, y, w, h, tab index, or PACK / WORK
    /** The pack and the work record's places in tabRects: well clear of the tabs' own numbers. (They were
     *  5 and 6, which the About tab became when the Money tab came in: clicking About opened the pack.) */
    private static final int PACK = 100, WORK = 101;
    /** [teleport] Its name, for a creative player not beside it: Teleport to it (FolkLinks). */
    private final FolkLinks links = new FolkLinks(true);

    public TalkScreen(FolkReplyPayload first) {
        super(Component.literal(first.name()));
        this.last = first;
        remember(first);
        toSkillsIfAsked(first);
    }

    /**
     * Asked about its skills or its knacks: the Skills page, where they are laid out. Asked anything
     * else with the About or Skills page open (which have no conversation on them): back to the talk,
     * where the answer is — "my account" said to the banker came back to a Skills page last left open.
     */
    private void toSkillsIfAsked(FolkReplyPayload reply) {
        String asked = reply.asked() == null ? "" : reply.asked().toLowerCase(java.util.Locale.ROOT);
        if (asked.contains("skill") || asked.contains("knack")) tab = lastTab = Tab.SKILLS;
        else if (asked.contains("your wage")) tab = lastTab = Tab.ABOUT;      // [econ-wages] its card: its wage and why
        else if (asked.contains("who you are")) tab = lastTab = Tab.ABOUT;    // [individual] its card: its looks, its life
        else if (!reply.open() && !asked.isBlank() && (tab == Tab.ABOUT || tab == Tab.SKILLS)) tab = lastTab = Tab.TALK;
    }

    public int entityId() { return last.entityId(); }

    public void update(FolkReplyPayload reply) {
        this.last = reply;
        remember(reply);
        toSkillsIfAsked(reply);
        this.scroll = 0;
        if (say != null) draft = say.getValue();
        rebuildWidgets();
    }

    private void remember(FolkReplyPayload r) {
        List<Line> log = HISTORY.computeIfAbsent(r.entityId(), k -> new ArrayList<>());
        if (!r.asked().isEmpty()) log.add(new Line(true, r.asked()));
        if (!r.said().isEmpty()) log.add(new Line(false, r.said()));
        while (log.size() > 80) log.remove(0);
        if (HISTORY.size() > 64) HISTORY.keySet().removeIf(id -> id != r.entityId() && HISTORY.size() > 48);
    }

    private record Choice(String label, TalkTopic topic, String text, String tip) {
        static Choice of(String label, TalkTopic topic) { return new Choice(label, topic, "", topic.line); }
        static Choice of(String label, TalkTopic topic, String tip) { return new Choice(label, topic, "", tip); }
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        w = Math.min(384, width - 12);
        h = Math.min(280, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        tabRects.clear();
        gift = null;
        deliver = null;

        int inputY = top + h - PAD - 18;
        say = new EditBox(font, left + PAD, inputY, w - 2 * PAD - 2 * 46 - 6, 18, Component.literal("Say something"));
        say.setMaxLength(FolkTalkPayload.MAX_TEXT);
        say.setHint(Component.literal("Say anything… (\"show me the café\", \"8 bread, please?\")"));
        say.setValue(draft);
        addRenderableWidget(say);
        addRenderableWidget(Button.builder(Component.literal("Say"), b -> sayTyped())
            .bounds(left + w - PAD - 2 * 46 - 3, inputY, 46, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Bye"), b -> onClose())
            .bounds(left + w - PAD - 46, inputY, 46, 18).build());

        if (tab == Tab.ABOUT || tab == Tab.SKILLS) return;
        List<Choice> choices = places ? placeChoices() : choices(tab);
        int cols = w >= 340 ? 4 : 3;
        int bw = (w - 2 * PAD - (cols - 1) * 3) / cols;
        int rows = (choices.size() + cols - 1) / cols;
        int y0 = inputY - 6 - rows * ROW;
        for (int i = 0; i < choices.size(); i++) {
            Choice c = choices.get(i);
            int x = left + PAD + (i % cols) * (bw + 3), y = y0 + (i / cols) * ROW;
            Button b = Button.builder(Component.literal(c.label()), btn -> choose(c)).bounds(x, y, bw, ROW - 2).build();
            if (!c.tip().isEmpty()) b.setTooltip(Tooltip.create(Component.literal(c.tip())));
            addRenderableWidget(b);
            if (c.topic() == TalkTopic.GIFT) gift = b;
            if (c.topic() == TalkTopic.DELIVER) deliver = b;
        }
    }

    private int logBottom() {
        int inputY = top + h - PAD - 18;
        if (tab == Tab.ABOUT || tab == Tab.SKILLS) return inputY - 6;
        int cols = w >= 340 ? 4 : 3;
        int n = places ? placeChoices().size() : choices(tab).size();
        int rows = (n + cols - 1) / cols;
        return inputY - 10 - rows * ROW;
    }

    private int bodyTop() {
        return top + HEADER + 4 + TAB_H + 4;
    }

    private List<Choice> choices(Tab t) {
        List<Choice> out = new ArrayList<>(questChoices());     // [quests] take it on, not now, a choice, hand it over: on every tab
        switch (t) {
            case TALK -> {
                out.add(Choice.of("How are you?", TalkTopic.HOW));
                out.add(Choice.of("Your work?", TalkTopic.DOING));
                out.add(Choice.of("About you", TalkTopic.ABOUT));
                out.add(Choice.of("Family?", TalkTopic.PEOPLE));
                out.add(Choice.of("Your pet?", TalkTopic.PET, "Its household's dog or cat: how it is, what it gets up to; a pup or a kitten looking for a home"));   // [pets]
                out.add(Choice.of("Your home?", TalkTopic.HOUSE, "Where it lives, who with, and whether the house is its own"));
                out.add(Choice.of("Any news?", TalkTopic.VILLAGE));
                out.add(Choice.of("Your hopes?", TalkTopic.DREAMS));
                out.add(Choice.of("Memories?", TalkTopic.MEMORY));
                out.add(Choice.of("Pastimes?", TalkTopic.HOBBY));
                out.add(Choice.of("Gossip?", TalkTopic.GOSSIP));
                out.add(Choice.of("A joke!", TalkTopic.JOKE));
                out.add(Choice.of("Well done!", TalkTopic.PRAISE, "Tell it it's doing a fine job — it will warm to you"));
                out.add(new Choice("I'm sorry", TalkTopic.SAY, "I'm sorry", "Apologise for whatever you did"));
            }
            case ASK -> {
                out.add(Choice.of("Any work?", TalkTopic.JOBS, "Ask if it has a quest for you: a favour of its own, the town's work, the war's, the caves', a story"));   // [quests]
                out.add(Choice.of("Can I help?", TalkTopic.HELP, "Ask if there's an errand you could run for it"));
                out.add(Choice.of("A favour?", TalkTopic.FAVOUR));
                out.add(Choice.of("What's short?", TalkTopic.SHORT, "What the village is short of, and how you could help"));
                out.add(Choice.of("Your trade?", TalkTopic.WORKINGS, "How its work goes: tools, where it works, what it needs, where its work goes"));
                out.add(Choice.of("Good at?", TalkTopic.KNACK, "Its level in every trade it has worked, how its nature suits its work, and the knacks it chose (its Skills page)"));
                out.add(Choice.of("Money?", TalkTopic.WORTH, "What it earns and what it is worth"));
                out.add(new Choice("Show me…", TalkTopic.GUIDE, "", "Ask it to walk you somewhere: the stores, the board, the elder, any building"));
                out.add(new Choice("Ask the stores", TalkTopic.STORES, "", "Ask the storekeeper for something: type what and how many"));
                out.add(Choice.of("Elder's orders", TalkTopic.ORDERS, "What the elder wants the village to put its back into"));
                out.add(Choice.of("Quest board", TalkTopic.QUESTS, "What the village wants done, and what it pays"));
                out.add(Choice.of("Town ledger", TalkTopic.LEDGER, "A book of the village's affairs"));
                out.add(Choice.of("Town map", TalkTopic.TOWN_MAP, "A map of the town, centred on its heart: for citizens and honoured guests, from the storekeeper or the elder, one a day"));
                out.add(Choice.of("Lost & found", TalkTopic.LOST, "Anything of yours the sweeper found lying in the streets, kept for you in the Lost and Found by the storehouse"));
                out.add(Choice.of("History", TalkTopic.CHRONICLE, "Ask for a copy of the village's chronicle"));
            }
            case VILLAGE -> {
                out.add(new Choice("Town's ways", TalkTopic.SAY, "What's this town like?", "Its character, who rules it, its laws, what it is famous for and what history has made of it — in this folk's own words"));   // [identity]
                out.add(Choice.of("Residents", TalkTopic.CENSUS));
                out.add(Choice.of("The council", TalkTopic.COUNCIL, "Who sits on the council, and what it voted. Say \"you should build a tavern\" to put it to the vote"));
                out.add(Choice.of("Neighbours", TalkTopic.RIVALS, "What this village thinks of the villages round about, who leads them, and who trades with whom"));
                out.add(Choice.of("Carry a letter", TalkTopic.LETTER, "Take a letter from the elder to the nearest neighbour's: both villages will think the better of you, and of each other"));
                out.add(Choice.of("Out there", TalkTopic.ATLAS, "What the village's scouts have found: towns, ruins, peaks, ore — and which way"));
                out.add(Choice.of("Underground", TalkTopic.CAVES, "What the town's cave dwellers have found: caves, ore, mineshafts, spawners, old chests — and where"));   // [caves]
                out.add(Choice.of("Fashion", TalkTopic.FASHION, "What the town is wearing this season, who set it, and what this folk thinks of it"));   // [fashion]
                // [nether] The Nether runners: their report, an ask, going through with them, their chart (NetherGuests).
                out.add(Choice.of("The Nether", TalkTopic.NETHER, "What the town's Nether runners have found through the gateway: the outpost, the fortress, the hauls"));
                out.add(new Choice("Ask the runners", TalkTopic.NETHER, "ask", "Ask the Nether runners to bring something on their next run: type it (\"bring us blaze rods\")"));
                out.add(new Choice("Go through", TalkTopic.SAY, "Can I come through with you?", "Go through the gateway with the Nether runners: they wait for you there in the morning. Say \"for a share\" to take a share of the haul"));
                out.add(new Choice("Runners' chart", TalkTopic.SAY, "Could I buy a copy of your chart?", "A copy of the runners' chart of the Nether round their outpost, their finds marked: a few coins, from one of the runners"));
                // [caves] The cave team: ask it a way or an ore, go along with it, buy its map (CaveGuests, Lodge).
                out.add(new Choice("Ask the delvers", TalkTopic.CAVES, "ask", "Ask the cave team to look a way or find something on its next trip: type it (\"look east\", \"find us diamonds\")"));
                out.add(new Choice("Go caving", TalkTopic.SAY, "Can I come along with the cave team?", "Go down the caves with the cave team: it waits for you at its lodge at first light. Say \"for a share\" to take a share of the haul"));
                out.add(new Choice("Cave map", TalkTopic.SAY, "Could I buy a copy of the cave map?", "A copy of the cave team's map, every cave it has found marked: a few coins, from one of the team at its lodge"));
                // [fireworks] The fireworks maker's rockets: the next display, and rockets for an elytra (FireworksMaker).
                out.add(new Choice("Fireworks", TalkTopic.SAY, "When are the next fireworks?", "The town's next display, and the rockets it has ready"));
                out.add(new Choice("Elytra rockets", TalkTopic.SAY, "Could I buy some rockets for my elytra?", "Rockets to fly with, of the stores' paper and gunpowder: eight to a lot, from the fireworks maker at its powder hut or the shop. Say \"flight two\" or \"flight three\" for the longer ones"));
                out.add(Choice.of("My standing", TalkTopic.REPUTE));
                out.add(Choice.of("Live here?", TalkTopic.CITIZEN, "Ask to become a citizen: a vote on the council and a house of your own"));
                out.add(Choice.of("Pay a fine", TalkTopic.FINE, "Pay what you owe the village"));
                out.add(Choice.of("Seen anything?", TalkTopic.WATCH, "A theft or a vandal: ask what it saw (a friend tells you what it would not "
                    + "tell the watch). To a guard: the case it is on; hold what was dropped at the scene to hand it in, or type \"I saw Fen take it\""));   // [crime]
                // [police] The watch as the town's police: a crime reported, the day's roster, sworn in as a special constable.
                out.add(new Choice("Report a crime", TalkTopic.SAY, "I want to report a crime",
                    "Tell a guard (or the desk at the watch house) what you saw: it goes on the books and the watch goes at a run"));
                out.add(new Choice("The roster", TalkTopic.SAY, "Who's on the roster today?", "Who of the watch is on the walls, the beat, the desk and the cases today, and who drew it"));
                out.add(new Choice("Swear me in", TalkTopic.SAY, "Swear me in as a special constable",
                    "A citizen or a friend of the town with a clean record can be sworn in: a Constable's Badge, an arrest by right-clicking a culprit with it, the beat with the watch, and a wage for every case closed"));
                out.add(new Choice("The board", TalkTopic.OPEN, "", "Read the village board: what it is doing, how it is getting on, what it is working towards"));
                out.add(new Choice("Suggest a build", TalkTopic.BUILD, "", "Type what you think the village should build next"));
                // [player-civic] Standing for leader, the campaign, and the Leader's page.
                out.add(new Choice("Stand for leader", TalkTopic.SAY, "I'd like to stand for election",
                    "Put your name forward at the next election (a citizen the town counts a friend, at the board or the hall)"));
                out.add(new Choice("Vote for me?", TalkTopic.SAY, "Will you vote for me?", "Canvass: it weighs what you stand for against what it cares for"));
                out.add(new Choice("Leader's page", TalkTopic.SAY, "Show me the leader's page", "Your promises and powers, or the hustings if you don't lead"));
                // [interviews] The town's interviews: who stands for what, when; then a good word put in ("I'd recommend Ada for the post").
                out.add(new Choice("Interviews?", TalkTopic.SAY, "Any interviews coming up?",
                    "Who stands for which post at the town's interviews, and when. Then type \"I'd recommend <name> for the post\" to put in a good word"));
            }
            case DEAL -> {
                out.add(new Choice("Give…", TalkTopic.GIFT, "", "Give it what you are holding"));
                out.add(new Choice("Hand over", TalkTopic.DELIVER, "", "Give it what it asked you for, or pay for what it offered"));
                out.add(Choice.of("Trade?", TalkTopic.TRADE));
                out.add(Choice.of("For sale?", TalkTopic.FOR_SALE, "What the village can spare, and at what price: it sees to itself first"));
                out.add(Choice.of("Hire you?", TalkTopic.HIRE, "Four coins a day: it goes with you, fights for you, carries for you"));
                out.add(Choice.of("Build my house", TalkTopic.COMMISSION, "Bring 64 planks, 32 cobblestone and 8 glass, and the builders put up a house for you"));
                out.add(last.following() ? Choice.of("Go home", TalkTopic.STAY) : Choice.of("Come along", TalkTopic.FOLLOW));
                out.add(new Choice("Show me…", TalkTopic.GUIDE, "", "Ask it to walk you somewhere"));
                out.add(Choice.of("Eat together", TalkTopic.MEAL, "Share the food in your hand: it eats with you, and likes you the better"));
                out.add(Choice.of("Dice?", TalkTopic.DICE, "A game of dice for three coins, out of each other's purses (type \"dice for 10\" to raise it)"));
                out.add(Choice.of("A lesson", TalkTopic.TEACH, "Show it a trick of its trade with the tool of it in your hand: once a day, it learns"));
                out.add(Choice.of("Godparent", TalkTopic.GODPARENT, "Ask a child if you may be its godparent (a friend of the village only)"));
                out.add(Choice.of("Keepsake?", TalkTopic.KEEPSAKE, "A close friend gives you something of its own to remember it by"));
                out.add(Choice.of("Feast on me", TalkTopic.SPONSOR, "Pay for a feast for the whole village tonight: ten coins and one for every mouth"));
                // [player-civic] An apprenticeship with a master of its trade.
                out.add(new Choice("Apprentice me", TalkTopic.SAY, "Will you take me as your apprentice?",
                    "Learn its trade (a master of level 25 or more): lessons that open recipes, bonuses and titles"));
                out.add(new Choice("My lesson", TalkTopic.SAY, "What's my next lesson?", "Your master's next lesson; hand over what it asked for"));
            }
            case MONEY -> {
                out.add(new Choice("Order goods…", TalkTopic.BULK, "", "Order a quantity of anything at a tenth off: type what and how many"));
                out.add(Choice.of("Contract?", TalkTopic.CONTRACT, "Bring the village what it is short of every week, at a third over its worth (say \"I'll sign\")"));
                out.add(Choice.of("Rent a stall", TalkTopic.STALL, "A stall of your own on the square, a week at a time: stock it, set your prices, and the folk buy from it with their own coin. Ask again for how it's doing, \"take the till\" or \"pay the rent\""));
                out.add(Choice.of("The bank", TalkTopic.BANK, "Your account: at the town's bank once it has one (see the banker: \"deposit 20\", \"withdraw 10\", "
                    + "\"a mortgage on this house\" standing in an empty one), else at the treasury; \"borrow 30\", \"repay\" for the treasury's small loans"));
                out.add(new Choice("Invest…", TalkTopic.INVEST, "", "Put coin into the village's works: two weeks' share of what it takes each day"));
                out.add(Choice.of("Auction", TalkTopic.AUCTION, "Market day's auction: the lots, the bids, your own goods put up (\"I bid 30\", \"put it up\")"));   // [fleet]
                out.add(Choice.of("Escort", TalkTopic.ESCORT, "Guard the next caravan: walk with it and be paid at the other end"));
                out.add(Choice.of("Charter route", TalkTopic.CHARTER, "Fifty coins for a trade route to the nearest neighbour: a tenth of every load sold on it is yours"));
                out.add(Choice.of("Buy a house", TalkTopic.HOUSING, "The village's empty houses and their prices. Say \"buy this house\" standing in one, \"let my house for 3\", or \"my rent\""));
                out.add(Choice.of("Prices", TalkTopic.PRICES, "Where things are dear and where cheap, round about: buy cheap, sell dear"));
                out.add(Choice.of("Haggle", TalkTopic.HAGGLE, "Ask the storekeeper or the shopkeeper to do it cheaper: a discount for the day, if they like you"));
                out.add(new Choice("Make me…", TalkTopic.ORDER, "", "Ask a smith or a tailor to make you something: from your makings and the village's spare, for a fee"));
                out.add(Choice.of("Mend this", TalkTopic.REPAIR, "The smith (or, with no smith, a smelter at its forge) mends the worn thing in your hand: its metal from the stores at the market's price, a unit a quarter of the wear, and a fee"));
                // [cartographer] The cartographer's maps (Cartographers): what it has and their prices, the explorer maps, a copy of
                // the hall's map, and a map made to order.
                out.add(Choice.of("Maps", TalkTopic.MAPS, "The cartographer's maps and their prices: explorer maps, a copy of the hall's map, a map to any place the town knows of"));
                out.add(new Choice("Ocean map", TalkTopic.SAY, "I'd like an ocean explorer map", "A real ocean explorer map to the nearest monument the town's land reaches: priced by how far"));
                out.add(new Choice("Woodland map", TalkTopic.SAY, "I'd like a woodland explorer map", "A real woodland explorer map to a mansion, if the town's scouts have been that far"));
                out.add(new Choice("Treasure map", TalkTopic.SAY, "I'd like a treasure map", "A buried treasure map: one the town brought home from a wreck, or treasure it found itself"));
                out.add(new Choice("Town map copy", TalkTopic.SAY, "A copy of the town's map, please", "Every sheet of the hall's map of the town, copied for you: hang them as they hang in the hall"));
                out.add(new Choice("Commission…", TalkTopic.MAPS, "commission", "Have the cartographer walk and draw the land any way you like, ready by nightfall: type \"map me the land to the east\""));
            }
            default -> { }
        }
        return out;
    }

    /** [quests] The quest buttons the folk sent with its last answer (QuestClient): "label|TOPIC|text|tip" a line. */
    private List<Choice> questChoices() {
        List<Choice> out = new ArrayList<>();
        for (String line : QuestClient.choicesFor(last.entityId()).split("\n")) {
            String[] f = line.split("\\|", -1);
            if (f.length < 4) continue;
            try {
                out.add(new Choice(f[0], TalkTopic.valueOf(f[1]), f[2], f[3]));
            } catch (IllegalArgumentException ignored) {
                // a topic from a newer server
            }
        }
        return out;
    }

    private List<Choice> placeChoices() {
        List<Choice> out = new ArrayList<>();
        out.add(new Choice("‹ Back", TalkTopic.BYE, "back", ""));
        if (!last.places().isEmpty()) {
            for (String pair : last.places().split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                out.add(new Choice(pair.substring(eq + 1), TalkTopic.GUIDE, pair.substring(0, eq), "Walk there together"));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ doing

    private void choose(Choice c) {
        if (c.topic() == TalkTopic.BYE && "back".equals(c.text())) {
            places = false;
            rebuildWidgets();
            return;
        }
        if (c.topic() == TalkTopic.GUIDE && c.text().isEmpty()) {
            places = true;
            rebuildWidgets();
            return;
        }
        if (c.topic() == TalkTopic.STORES) {                  // a question you finish yourself
            say.setValue("Could I have 8 bread?");
            setFocused(say);
            return;
        }
        if (c.topic() == TalkTopic.BULK || c.topic() == TalkTopic.INVEST || c.topic() == TalkTopic.ORDER) {
            say.setValue(c.topic() == TalkTopic.BULK ? "I'd like to order 64 " : c.topic() == TalkTopic.INVEST ? "I'd like to invest 50 coins"
                : "Make me an iron sword");
            setFocused(say);
            return;
        }
        if (c.topic() == TalkTopic.MAPS && "commission".equals(c.text())) {    // [cartographer] a commission, the way typed yourself
            say.setValue("Map me the land to the east");
            setFocused(say);
            return;
        }
        if (c.topic() == TalkTopic.NETHER && "ask".equals(c.text())) {         // [nether] an ask of the runners, finished yourself
            say.setValue("Runners, bring us blaze rods");
            setFocused(say);
            return;
        }
        if (c.topic() == TalkTopic.CAVES && "ask".equals(c.text())) {          // [caves] an ask of the cave team, finished yourself
            say.setValue("Cave team, look east");
            setFocused(say);
            return;
        }
        if (c.topic() == TalkTopic.BUILD) {
            say.setValue("You should build a ");
            setFocused(say);
            return;
        }
        if (tab == Tab.VILLAGE && c.topic() == TalkTopic.OPEN) {     // the board: the village journal
            PacketDistributor.sendToServer(new com.jrpetty.mcassistant.net.VillageAskPayload(1));
            return;
        }
        if (c.topic() == TalkTopic.GUIDE) {
            places = false;
            ask(TalkTopic.GUIDE, c.text(), "Could you show me the way to " + c.label().toLowerCase(java.util.Locale.ROOT) + "?");
            return;
        }
        ask(c.topic(), c.text(), c.topic() == TalkTopic.SAY ? c.text() : c.topic().line);
    }

    private void ask(TalkTopic topic, String text, String shown) {
        PacketDistributor.sendToServer(new FolkTalkPayload(last.entityId(), topic.ordinal(), text));
    }

    private void sayTyped() {
        String text = say.getValue().trim();
        if (text.isEmpty()) return;
        say.setValue("");
        draft = "";
        ask(TalkTopic.SAY, text, text);
    }

    private void openPack() {
        saidBye = true;                       // not a goodbye: the pack screen takes over
        PacketDistributor.sendToServer(new FolkTalkPayload(last.entityId(), TalkTopic.PACK.ordinal(), ""));
    }

    private void openWork() {
        if (minecraft == null || minecraft.level == null) return;
        if (minecraft.level.getEntity(last.entityId()) instanceof com.jrpetty.mcassistant.entity.AssistantEntity a) {
            saidBye = true;
            minecraft.setScreen(new RecordScreen(a));
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (links.click(mx, my, button)) return true;                    // [teleport] its name, or its button
        for (int[] r : tabRects) {
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                if (r[4] == PACK) { openPack(); return true; }
                if (r[4] == WORK) { openWork(); return true; }
                Tab t = Tab.values()[r[4]];
                if (t != tab || places) {
                    tab = t;
                    lastTab = t;
                    places = false;
                    if (say != null) draft = say.getValue();
                    rebuildWidgets();
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (say != null && say.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            sayTyped();
            return true;
        }
        if (say != null && !say.isFocused() && key == GLFW.GLFW_KEY_TAB) {
            tab = Tab.values()[(tab.ordinal() + 1) % Tab.values().length];
            lastTab = tab;
            places = false;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.ABOUT) aboutScroll = Math.max(0, aboutScroll - (int) Math.signum(scrollY));
        else if (tab == Tab.SKILLS) skillsScroll = Math.max(0, Math.min(skillsMax, skillsScroll - 12 * (int) Math.signum(scrollY)));
        else scroll = Math.max(0, scroll + (int) Math.signum(scrollY));
        return true;
    }

    @Override
    public void onClose() {
        if (!saidBye) {
            saidBye = true;
            PacketDistributor.sendToServer(new FolkTalkPayload(last.entityId(), TalkTopic.BYE.ordinal(), ""));
        }
        super.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        Entity e = minecraft == null || minecraft.level == null ? null : minecraft.level.getEntity(last.entityId());
        if (e == null || !e.isAlive() || minecraft.player == null || e.distanceToSqr(minecraft.player) > 10.0 * 10.0) {
            saidBye = true;               // it has gone, or you have: nobody to say goodbye to
            onClose();
            return;
        }
        if (gift != null) {
            ItemStack held = minecraft.player.getMainHandItem();
            gift.active = !held.isEmpty();
            gift.setMessage(Component.literal(held.isEmpty() ? "Give…" : "Give " + held.getHoverName().getString()));
        }
        if (deliver != null) deliver.active = last.canDeliver();
    }

    // ------------------------------------------------------------------ painting

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + w, top + h, BG);
        g.fill(left, top, left + w, top + HEADER, BAND);
        g.fill(left, top, left + w, top + 1, EDGE);
        g.fill(left, top + h - 1, left + w, top + h, EDGE);
        g.fill(left, top, left + 1, top + h, EDGE);
        g.fill(left + w - 1, top, left + w, top + h, EDGE);
        g.fill(left + 1, top + HEADER, left + w - 1, top + HEADER + 1, SOFT);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        links.begin(mouseX, mouseY);                                      // [teleport]
        header(g, mouseX, mouseY);
        tabs(g, mouseX, mouseY);
        if (tab == Tab.ABOUT) about(g);
        else if (tab == Tab.SKILLS) skills(g, mouseX, mouseY);
        else conversation(g);
        links.draw(g, font);                                              // [teleport] its name's button, over everything
    }

    private void header(GuiGraphics g, int mouseX, int mouseY) {
        int px = left + PAD, py = top + 4;
        g.fill(px - 1, py - 1, px + 45, py + HEADER - 7, SOFT);
        g.fill(px, py, px + 44, py + HEADER - 8, 0xFF141821);
        if (minecraft != null && minecraft.level != null
                && minecraft.level.getEntity(last.entityId()) instanceof LivingEntity folk) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, px, py, px + 44, py + HEADER - 8, 20, 0.0625F,
                mouseX, mouseY, folk);
        }
        int tx = px + 52, tw = left + w - PAD - tx;
        g.drawString(font, last.name(), tx, top + 6, GOLD, true);
        links.addCard(g, last.entityId(), last.name(), tx, top + 6, font.width(last.name()), 8, GOLD);   // [teleport]
        String where = Ui.clip(font, last.village(), Math.max(40, tw - font.width(last.name()) - 10));
        g.drawString(font, where, left + w - PAD - font.width(where), top + 6, MUTED, false);
        g.drawString(font, Ui.clip(font, last.about(), tw), tx, top + 17, MUTED, false);
        // Mood, as a word and a bar; what it thinks of you, as hearts.
        String mood = face(last.mood()) + " " + last.moodWord();
        g.drawString(font, mood, tx, top + 28, moodColour(last.mood()), false);
        int bx = tx + font.width(mood) + 6;
        g.fill(bx, top + 31, bx + 40, top + 34, 0x40FFFFFF);
        g.fill(bx, top + 31, bx + Math.max(1, 40 * Math.max(0, Math.min(100, last.mood())) / 100), top + 34, moodColour(last.mood()));
        String heart = hearts(last.affinity()) + " " + last.standing();
        g.drawString(font, Ui.clip(font, heart, Math.max(30, left + w - PAD - (bx + 48))), bx + 48, top + 28,
            last.affinity() < -10 ? 0xFFE07070 : PINK, false);
        String now = last.doing().isEmpty() ? "" : "Now: " + last.doing();
        g.drawString(font, Ui.clip(font, now, tw), tx, top + 40, INK, false);
    }

    private void tabs(GuiGraphics g, int mouseX, int mouseY) {
        tabRects.clear();
        int y = top + HEADER + 4;
        int x = left + PAD;
        // Seven tabs and the two links on the right fill a narrow window: when they would run into
        // each other the tabs pad less, and then the links shorten.
        String[] nav = { "Work done ›", "Pack ›" };
        int pad = 14, navPad = 12;
        if (tabsWidth(pad, nav, navPad) > w - 2 * PAD) { pad = 8; navPad = 8; }
        if (tabsWidth(pad, nav, navPad) > w - 2 * PAD) nav = new String[]{ "Work ›", "Pack ›" };
        for (Tab t : Tab.values()) {
            int tw = font.width(t.label) + pad;
            boolean on = t == tab && !places;
            boolean hover = mouseX >= x && mouseX < x + tw && mouseY >= y && mouseY < y + TAB_H;
            g.fill(x, y, x + tw, y + TAB_H, on ? TAB_ON : hover ? 0xFF353B48 : TAB);
            if (on) g.fill(x, y + TAB_H - 2, x + tw, y + TAB_H, GOLD);
            g.drawString(font, t.label, x + pad / 2, y + 4, on ? GOLD : INK, false);
            tabRects.add(new int[]{ x, y, tw, TAB_H, t.ordinal() });
            x += tw + 2;
        }
        // The pack and the work record, on the right.
        int rx = left + w - PAD;
        for (int i = 0; i < nav.length; i++) {
            int tw = font.width(nav[i]) + navPad;
            rx -= tw;
            boolean hover = mouseX >= rx && mouseX < rx + tw && mouseY >= y && mouseY < y + TAB_H;
            g.fill(rx, y, rx + tw, y + TAB_H, hover ? 0xFF2F4350 : NAV);
            g.drawString(font, nav[i], rx + navPad / 2, y + 4, MUTED, false);
            tabRects.add(new int[]{ rx, y, tw, TAB_H, i == 0 ? WORK : PACK });
            rx -= 2;
        }
    }

    /** How wide the tabs and the links on the right come to, with this much padding. */
    private int tabsWidth(int pad, String[] nav, int navPad) {
        int n = 0;
        for (Tab t : Tab.values()) n += font.width(t.label) + pad + 2;
        for (String s : nav) n += font.width(s) + navPad + 2;
        return n + 6;
    }

    private void conversation(GuiGraphics g) {
        int x = left + PAD, top0 = bodyTop(), bottom = logBottom();
        g.fill(x, top0, left + w - PAD, bottom, LOG);
        int y = top0 + 4;
        if (!last.errand().isEmpty()) {
            String e = Ui.clip(font, "★ " + last.errand(), w - 2 * PAD - 8);
            g.drawString(font, e, x + 4, y, ERRAND, false);
            y += LINE + 2;
        }
        if (places) {
            g.drawString(font, "Where would you like to go? It will walk you there.", x + 4, y, MUTED, false);
            y += LINE + 2;
        }
        // The conversation, newest at the bottom.
        List<FormattedCharSequence> lines = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        int width = w - 2 * PAD - 10;
        for (Line l : HISTORY.getOrDefault(last.entityId(), List.of())) {
            String text = l.you() ? "You: " + l.text() : l.text();
            for (FormattedCharSequence s : TextCache.splitPlain(font, text, width)) {
                lines.add(s);
                colours.add(l.you() ? YOU : INK);
            }
        }
        int room = Math.max(1, (bottom - y - 3) / LINE);
        int maxScroll = Math.max(0, lines.size() - room);
        if (scroll > maxScroll) scroll = maxScroll;
        int start = Math.max(0, lines.size() - room - scroll);
        for (int i = 0; i < room && start + i < lines.size(); i++) {
            g.drawString(font, lines.get(start + i), x + 5, y + i * LINE, colours.get(start + i), false);
        }
        if (maxScroll > 0) {
            g.drawString(font, scroll < maxScroll ? "▲" : " ", left + w - PAD - 9, top0 + 2, MUTED, false);
            g.drawString(font, scroll > 0 ? "▼" : " ", left + w - PAD - 9, bottom - 10, MUTED, false);
        }
    }

    private void about(GuiGraphics g) {
        int x = left + PAD, top0 = bodyTop(), bottom = logBottom();
        g.fill(x, top0, left + w - PAD, bottom, LOG);
        int labelW = 66;
        int width = w - 2 * PAD - labelW - 12;
        List<String[]> rows = new ArrayList<>();
        for (String l : last.card().split("\n")) {
            int bar = l.indexOf('|');
            if (bar <= 0) continue;
            rows.add(new String[]{ l.substring(0, bar), l.substring(bar + 1) });
        }
        // Lay it out, then scroll it.
        List<Object[]> out = new ArrayList<>();
        for (String[] r : rows) {
            List<FormattedCharSequence> wrapped = TextCache.splitPlain(font, r[1], width);
            for (int i = 0; i < wrapped.size(); i++) out.add(new Object[]{ i == 0 ? r[0] : "", wrapped.get(i) });
            out.add(new Object[]{ "", null });
        }
        int room = Math.max(1, (bottom - top0 - 6) / LINE);
        aboutScroll = Math.min(aboutScroll, Math.max(0, out.size() - room));
        int y = top0 + 4;
        for (int i = 0; i < room && aboutScroll + i < out.size(); i++) {
            Object[] o = out.get(aboutScroll + i);
            String label = (String) o[0];
            if (!label.isEmpty()) g.drawString(font, label, x + 5, y, GOLD, false);
            if (o[1] != null) g.drawString(font, (FormattedCharSequence) o[1], x + 5 + labelW, y, INK, false);
            y += o[1] == null ? 3 : LINE;
        }
        if (rows.isEmpty()) g.drawString(font, "It has not said much about itself yet.", x + 5, top0 + 4, MUTED, false);
    }

    // ------------------------------------------------------------------ the Skills page

    /** The knacks' colours, by family: a trade's amber, a nature's green, a purse's blue; and the greys of what is still open. */
    private static final int TRADE_INK = 0xFFD9A441, NATURE_INK = 0xFF86C98A, PURSE_INK = 0xFF7FB2E5,
        OPEN_EDGE = 0xFF3C424E, OPEN_NAME = 0xFFA4ABB8, OPEN_DIM = 0xFF7A808C, OPEN_TEXT = 0xFF6E7480,
        TRACK = 0x40FFFFFF, FILL = 0xFFB8963A, CARD = 0x26FFFFFF;

    /** What the folk sent for its Skills page (FolkSkills.encode), read once a reply. */
    private record Points(int earned, int spent, int free, int nextAt, int pct, String best, int bestLevel, boolean child) {}
    private record TradeRow(String title, int level, boolean now) {}
    private record KnackRow(String key, String title, String family, String effect, String why, String day, boolean active) {}
    private record OpenRow(String key, String title, String family, String effect, int fit) {}
    private record SkillsPage(Points points, List<TradeRow> trades, List<KnackRow> chosen, List<OpenRow> open, int[] nest) {}

    private String skillsRead;
    private SkillsPage skillsPage;

    private SkillsPage skillsPage() {
        String raw = last.skills();
        if (raw == null || raw.isEmpty()) return null;
        if (raw.equals(skillsRead)) return skillsPage;
        Points points = null;
        List<TradeRow> trades = new ArrayList<>();
        List<KnackRow> chosen = new ArrayList<>();
        List<OpenRow> open = new ArrayList<>();
        int[] nest = null;
        for (String line : raw.split("\n")) {
            String[] f = line.split("\\|", -1);
            try {
                switch (f[0]) {
                    case "P" -> points = new Points(num(f[1]), num(f[2]), num(f[3]), num(f[4]), num(f[5]), f[6], num(f[7]), "1".equals(f[8]));
                    case "T" -> trades.add(new TradeRow(f[1], num(f[2]), "1".equals(f[3])));
                    case "K" -> chosen.add(new KnackRow(f[1], f[2], f[3], f[4], f[5], f[6], "1".equals(f[7])));
                    case "O" -> open.add(new OpenRow(f[1], f[2], f[3], f[4], num(f[5])));
                    case "N" -> nest = new int[]{ num(f[1]), num(f[2]) };
                    default -> { }
                }
            } catch (RuntimeException ignored) {
                // a line from a newer or older folk than this screen knows: left out
            }
        }
        skillsRead = raw;
        skillsPage = points == null ? null : new SkillsPage(points, trades, chosen, open, nest);
        return skillsPage;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int familyInk(String family) {
        return switch (family) {
            case "NATURE" -> NATURE_INK;
            case "PURSE" -> PURSE_INK;
            default -> TRADE_INK;
        };
    }

    private static String familyName(String family) {
        return switch (family) {
            case "NATURE" -> "Nature";
            case "PURSE" -> "Purse";
            default -> "Trade";
        };
    }

    /**
     * The folk's knacks, drawn: along the top its knack points (a pip each: gold for one spent, a
     * gold ring for one to spend, grey for one still to earn) and a bar to its next point; its
     * trades as rulers marked at every fifth level, where the points come; a card for each knack
     * it chose (what it does, why it chose it, the day, and whether it works in the trade it has
     * now); and its tree, three branches (its trade, its nature, its purse) with what it chose in
     * gold and what is still open to it greyed, the mouse over any of them for the whole of it.
     * Scrolls with the wheel.
     */
    private void skills(GuiGraphics g, int mouseX, int mouseY) {
        int x0 = left + PAD, x1 = left + w - PAD, top0 = bodyTop(), bottom = logBottom();
        g.fill(x0, top0, x1, bottom, LOG);
        SkillsPage page = skillsPage();
        if (page == null) {
            g.drawString(font, "It has said nothing yet of what it is good at.", x0 + 5, top0 + 4, MUTED, false);
            return;
        }
        Points pt = page.points();
        int cx = x0 + 6, inner = x1 - x0 - 12;
        boolean hovering = mouseX >= x0 && mouseX < x1 && mouseY >= top0 && mouseY < bottom;
        List<FormattedCharSequence> tip = null;
        g.enableScissor(x0, top0, x1, bottom);
        int y = top0 + 5 - skillsScroll;

        // ---- the points: six pips, and what they come to
        g.drawString(font, "Knack points", cx, y, GOLD, false);
        int px = cx + font.width("Knack points") + 6;
        for (int i = 0; i < 6; i++) {
            int bx = px + i * 10;
            if (i < pt.spent()) {
                g.fill(bx, y, bx + 7, y + 7, GOLD);
            } else if (i < pt.earned()) {
                g.fill(bx, y, bx + 7, y + 7, GOLD);
                g.fill(bx + 1, y + 1, bx + 6, y + 6, 0xFF141821);
            } else {
                g.fill(bx, y, bx + 7, y + 7, SOFT);
                g.fill(bx + 1, y + 1, bx + 6, y + 6, 0xFF1E222B);
            }
        }
        String tally = pt.child() ? "none yet: a child" : pt.earned() + " earned · " + pt.spent() + " chosen · " + pt.free() + " to choose";
        g.drawString(font, Ui.clip(font, tally, Math.max(20, x1 - 6 - (px + 64))), px + 64, y, pt.free() > 0 ? INK : MUTED, false);
        y += 12;

        // ---- how near the next point is
        if (pt.child()) {
            y = wrapped(g, "Its knacks come with a trade: a point at every fifth level of the trade it is best at, six in all.",
                cx, y, inner, MUTED);
        } else if (pt.nextAt() <= 0) {
            g.drawString(font, "All six points earned" + (pt.best().isEmpty() ? "" : " — " + pt.best().toLowerCase(java.util.Locale.ROOT)
                + " " + pt.bestLevel()), cx, y, MUTED, false);
            y += LINE + 2;
        } else {
            String label = (pt.best().isEmpty() ? "No trade" : pt.best()) + " " + pt.bestLevel();
            String after = "next point at level " + pt.nextAt();
            int lw = font.width(label) + 6, aw = font.width(after) + 6;
            int bx = cx + lw, bw = Math.max(20, inner - lw - aw);
            g.drawString(font, label, cx, y, INK, false);
            g.fill(bx, y + 2, bx + bw, y + 7, TRACK);
            g.fill(bx, y + 2, bx + Math.max(1, bw * Math.max(0, Math.min(100, pt.pct())) / 100), y + 7, FILL);
            g.drawString(font, after, bx + bw + 6, y, MUTED, false);
            if (hovering && mouseX >= bx && mouseX < bx + bw && mouseY >= y && mouseY < y + 9) {
                tip = TextCache.splitPlain(font, pt.pct() + "% of the way from level " + (pt.nextAt() - 5) + " to level " + pt.nextAt()
                    + ", by its experience at its best trade.", 200);
            }
            y += LINE + 2;
        }

        // ---- its trades, each a ruler to level thirty marked where the points come
        if (!page.trades().isEmpty()) {
            int nameW = 0;
            for (TradeRow t : page.trades()) nameW = Math.max(nameW, font.width(t.title()));
            nameW += 6;
            int tx = cx + nameW, tw = Math.max(30, inner - nameW - 30);
            for (TradeRow t : page.trades()) {
                g.drawString(font, t.title(), cx, y, t.now() ? GOLD : INK, false);
                g.fill(tx, y + 3, tx + tw, y + 6, TRACK);
                int lv = Math.min(30, t.level());
                g.fill(tx, y + 3, tx + tw * lv / 30, y + 6, t.now() ? FILL : 0xFF7D6A3A);
                for (int n = 5; n <= 30; n += 5) {
                    int nx = tx + tw * n / 30 - 1;
                    g.fill(nx, y + 1, nx + 1, y + 8, t.level() >= n ? GOLD : SOFT);
                }
                g.drawString(font, "lv " + t.level(), tx + tw + 5, y, t.now() ? INK : MUTED, false);
                y += LINE;
            }
            y += 4;
        }

        // ---- what it chose: a card each
        y = heading(g, "Chosen", cx, y, inner);
        if (page.chosen().isEmpty()) {
            y = wrapped(g, pt.child() ? "Nothing yet." : pt.free() > 0
                ? "Nothing yet. It has a point to spend, and chooses for itself at a quiet moment: its break, or the evening."
                : "Nothing yet. Its first point comes at level 5 of its best trade.", cx, y, inner, MUTED);
        }
        for (KnackRow k : page.chosen()) {
            List<FormattedCharSequence> effect = TextCache.splitPlain(font, k.effect(), inner - 12);
            List<FormattedCharSequence> why = TextCache.splitPlain(font, "Why: " + k.why(), inner - 12);
            int h = 4 + LINE + effect.size() * LINE + why.size() * LINE + (k.active() ? 0 : LINE) + 3;
            int ink = familyInk(k.family());
            g.fill(cx, y, cx + inner, y + h, CARD);
            g.fill(cx, y, cx + 2, y + h, ink);
            int ty = y + 4;
            g.drawString(font, k.title(), cx + 7, ty, GOLD, false);
            String when = familyName(k.family()) + " · day " + k.day();
            g.drawString(font, when, cx + inner - 4 - font.width(when), ty, ink, false);
            ty += LINE;
            for (FormattedCharSequence l : effect) { g.drawString(font, l, cx + 7, ty, INK, false); ty += LINE; }
            for (FormattedCharSequence l : why) { g.drawString(font, l, cx + 7, ty, MUTED, false); ty += LINE; }
            if (!k.active()) g.drawString(font, "Resting: it works another trade now", cx + 7, ty, 0xFFB0A080, false);
            y += h + 4;
        }
        if (page.nest() != null) {
            y = wrapped(g, "Nest Egg: " + page.nest()[0] + " of " + page.nest()[1]
                + " coins paid so far; the treasury pays the rest as it can spare it.", cx, y, inner, PURSE_INK);
        }
        y += 2;

        // ---- the tree: three branches, what it chose in gold, what is open to it in grey
        y = heading(g, "Its knacks", cx, y, inner);
        String[] families = { "TRADE", "NATURE", "PURSE" };
        int gap = 6, cw = (inner - 2 * gap) / 3;
        // The root and its three branches.
        int rootX = cx + inner / 2;
        g.fill(rootX, y, rootX + 1, y + 4, SOFT);
        g.fill(cx + cw / 2, y + 4, cx + 2 * (cw + gap) + cw / 2 + 1, y + 5, SOFT);
        y += 5;
        int colTop = y, deepest = y;
        for (int c = 0; c < 3; c++) {
            String fam = families[c];
            int colX = cx + c * (cw + gap), ink = familyInk(fam);
            int yy = colTop;
            g.fill(colX + cw / 2, yy, colX + cw / 2 + 1, yy + 3, SOFT);
            yy += 3;
            String head = familyName(fam);
            g.fill(colX, yy, colX + cw, yy + 12, 0xFF262B35);
            g.fill(colX, yy + 11, colX + cw, yy + 12, ink);
            g.drawString(font, head, colX + (cw - font.width(head)) / 2, yy + 2, ink, false);
            yy += 15;
            int stem = colX + 3, stemTop = yy;
            List<Object[]> nodes = new ArrayList<>();       // {title, effect, chosen?, row}
            for (KnackRow k : page.chosen()) if (k.family().equals(fam)) nodes.add(new Object[]{ k.title(), k.effect(), true, k });
            for (OpenRow o : page.open()) if (o.family().equals(fam)) nodes.add(new Object[]{ o.title(), o.effect(), false, o });
            if (nodes.isEmpty()) {
                g.drawString(font, Ui.clip(font, "nothing open", cw - 10), colX + 9, yy, OPEN_DIM, false);
                yy += LINE + 2;
            }
            int lastMid = yy;
            for (Object[] n : nodes) {
                boolean chosen = (Boolean) n[2];
                String title = (String) n[0];
                List<FormattedCharSequence> body = chosen
                    ? TextCache.splitPlain(font, "✓ day " + ((KnackRow) n[3]).day(), cw - 14)
                    : TextCache.splitPlain(font, (String) n[1], cw - 14);
                int nh = 3 + LINE + body.size() * 9 + 2;
                int nx = colX + 9;
                lastMid = yy + 6;
                g.fill(stem, lastMid, nx, lastMid + 1, SOFT);           // the twig to the stem
                if (chosen) {
                    g.fill(nx, yy, colX + cw, yy + nh, GOLD);
                    g.fill(nx + 1, yy + 1, colX + cw - 1, yy + nh - 1, 0xFF3A3220);
                } else {
                    g.fill(nx, yy, colX + cw, yy + nh, OPEN_EDGE);
                    g.fill(nx + 1, yy + 1, colX + cw - 1, yy + nh - 1, 0xFF1A1E26);
                }
                int fit = chosen ? 2 : ((OpenRow) n[3]).fit();
                String name = Ui.clip(font, title, cw - 16);
                g.drawString(font, name, nx + 3, yy + 3, chosen ? GOLD : fit >= 1 ? OPEN_NAME : OPEN_DIM, false);
                if (!chosen && fit >= 2) g.drawString(font, "•", colX + cw - 7, yy + 3, ink, false);   // it leans this way
                int by = yy + 3 + LINE;
                for (FormattedCharSequence l : body) { g.drawString(font, l, nx + 3, by, chosen ? MUTED : OPEN_TEXT, false); by += 9; }
                if (hovering && mouseX >= nx && mouseX < colX + cw && mouseY >= yy && mouseY < yy + nh) {
                    StringBuilder t = new StringBuilder(title).append(" — ").append((String) n[1]).append('.');
                    if (chosen) {
                        KnackRow k = (KnackRow) n[3];
                        t.append(" Chosen on day ").append(k.day()).append(": ").append(k.why()).append('.');
                        if (!k.active()) t.append(" Resting while it works another trade.");
                    } else {
                        t.append(fit >= 2 ? " Open to it, and it leans this way." : fit == 1 ? " Open to it." : " Open to it, though it hardly wants it.");
                    }
                    tip = TextCache.splitPlain(font, t.toString(), 220);
                }
                yy += nh + 3;
            }
            g.fill(stem, stemTop - 3, stem + 1, lastMid + 1, SOFT);     // the branch's stem
            deepest = Math.max(deepest, yy);
        }
        y = deepest + 4;
        y = wrapped(g, "It chooses for itself when a point is free, by its trade, its nature and what it cares about. "
            + "A trade's knacks work only in that trade; the rest go with it everywhere.", cx, y, inner, MUTED);
        g.disableScissor();

        // Scrolling: how far the page runs past the bottom.
        int content = y + skillsScroll - top0 + 2;
        skillsMax = Math.max(0, content - (bottom - top0));
        if (skillsScroll > skillsMax) skillsScroll = skillsMax;
        if (skillsMax > 0) {
            g.drawString(font, skillsScroll > 0 ? "▲" : " ", x1 - 9, top0 + 2, MUTED, false);
            g.drawString(font, skillsScroll < skillsMax ? "▼" : " ", x1 - 9, bottom - 10, MUTED, false);
        }
        if (tip != null) g.renderTooltip(font, tip, mouseX, mouseY);
    }

    /** A section heading with a rule after it; returns where the section starts. */
    private int heading(GuiGraphics g, String text, int x, int y, int width) {
        g.drawString(font, text, x, y, GOLD, false);
        int rx = x + font.width(text) + 6;
        g.fill(rx, y + 4, x + width, y + 5, SOFT);
        return y + LINE + 3;
    }

    /** Text wrapped to a width; returns the line after it. */
    private int wrapped(GuiGraphics g, String text, int x, int y, int width, int colour) {
        for (FormattedCharSequence l : TextCache.splitPlain(font, text, width)) {
            g.drawString(font, l, x, y, colour, false);
            y += LINE;
        }
        return y + 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String face(int mood) {
        return mood >= 70 ? "☺" : mood >= 40 ? "•" : "☹";
    }

    private static int moodColour(int mood) {
        return mood >= 70 ? 0xFF8BE08B : mood >= 40 ? 0xFFE0D68B : 0xFFE08B8B;
    }

    private static String hearts(int affinity) {
        if (affinity <= -15) return "✗";
        int n = Math.max(0, Math.min(5, (affinity + 9) / 20));
        return "♥".repeat(n) + "♡".repeat(5 - n);
    }
}
