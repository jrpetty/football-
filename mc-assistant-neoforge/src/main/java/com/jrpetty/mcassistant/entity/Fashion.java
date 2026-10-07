package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.Garment;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [fashion] What the town wears, and why: every folk's own style, the season's fashion, and how it goes round.
 *
 * <ul>
 * <li><b>Its own style.</b> Every folk has two colours of its own, the first its favourite (Decor.colour), and wears
 *     them: its trade's dyed cloth in the first, the trimmings of what it buys in the second (Style). What it buys
 *     is real: a coat, a jacket, a shawl or a waistcoat; a hat for its time off; a scarf, a brooch (Garment). All of
 *     it is drawn on it (client/FashionLayer), and its trade's working clothes stay as they are underneath.</li>
 * <li><b>The season's fashion.</b> As each season turns (Seasons), the town's most admired sets the look: the
 *     wealthiest, the leader and the leader's partner, the best liked, the young (who set fashions), and a player who
 *     is famous here and has been about the town in dyed leather. Its colour is the season's, and the thing it wears
 *     it as ("crimson long coats"). The old look fades as the new one comes in.</li>
 * <li><b>How it goes round.</b> Day by day each folk comes round to it (spread): quicker the more of its friends
 *     wear it and the more of the town does; the vain (who love fine things: wool, gems, gold) and the sociable
 *     quickest, the young quicker, the poor slower, and a Traditionalist (Values) hardly at all: it keeps its own.
 *     Come round, it wants the look in what it runs to: a wealthy folk the season's long coat, a poor one a scarf.</li>
 * <li><b>Getting it.</b> A folk that wants a thing and finds none in the town's stock puts it on the tailor's book
 *     (Tailoring), and the shop's books hear of it as a sale it had not got: the price of a thing everybody wants goes
 *     up (PriceIndex). Off work it goes to the shop (the store, or the stores) and buys it, out of its own purse, the
 *     way every folk buys its own (Purchases: the town's price, weighed against what it expects to pay; too dear, it
 *     leaves it and waits). Before the town has a shop the stores clothe it, as they feed it.</li>
 * <li><b>The old one.</b> What it had on in that place goes to somebody who has less: a poor neighbour with none,
 *     its friends first, through the poor box; or, nobody wanting it, sold second-hand to the stores for a third of
 *     its worth, where a poor folk buys it for a third of the price. The poor wear last season's colours.</li>
 * </ul>
 * The town's word on it: the gazette, the board, the town's books (the Fashion page), the folk's cards and their
 * talk, the tailor's book, and a fashion show at the May dance, the fair and the harvest festival (FashionShow).
 */
public final class Fashion {

    private Fashion() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The ledger's notes: the season's fashion, its days, the guests' finery seen, the week's hand-me-downs. */
    static final String NOTE = "fashion", DAYS = "fashion.days", GUESTS = "fashion.guests", PASSED = "fashion.passed";
    /** How quickly the town comes round, a day, before its friends, the town and its nature have their say. */
    static final double PACE = 0.35;
    /** Days a want is kept up when nothing has come of it (nothing on order, nothing in the stock). */
    static final int WANT_DAYS = 9;
    /** The days of a fashion's going round kept for the books. */
    static final int HISTORY = 14;

    // ------------------------------------------------------------------ the season's fashion

    /** A town's fashion this season: its colour, the thing to wear it as (or anything), who set it and why. */
    static final class Trend {
        int colour = -1;
        @Nullable Garment kind;
        String setter = "";
        @Nullable UUID setterId;
        String why = "";
        boolean byPlayer;
        long since = -1;
        int season = -1;
        int lastColour = -1;
        @Nullable Garment lastKind;
        long lastSince = -1;
        /** Told the chronicle that most of the town wears it. */
        boolean toldMost;
        /** Day by day: {day, wearing it, wearing the last one, grown folk}. */
        final List<long[]> days = new ArrayList<>();

        String key() {
            return season + ":" + colour;
        }

        boolean set() {
            return colour >= 0;
        }
    }

    private static final Map<UUID, Trend> TRENDS = new ConcurrentHashMap<>();
    /** The day each town's fashion was last gone round. */
    private static final Map<UUID, Long> DONE = new ConcurrentHashMap<>();
    /** A folk's errand to the shop for what it wants: when it set off, its last step, the day it gave up. */
    private static final Map<UUID, long[]> ERRANDS = new ConcurrentHashMap<>();
    /** The players seen about each town in their finery: by the town, by the player. */
    private static final Map<UUID, Map<UUID, Guest>> GUESTS_SEEN = new ConcurrentHashMap<>();

    /** A player seen in the town: its name, the days it was about, and its dyed leather (colour, pieces). */
    static final class Guest {
        String name = "";
        final List<Long> days = new ArrayList<>();
        int colour = -1;
        int pieces;
        boolean chest;
        long lastDyed = -100;
    }

    public static void resetForTests() {
        TRENDS.clear();
        DONE.clear();
        ERRANDS.clear();
        GUESTS_SEEN.clear();
        Tailoring.resetForTests();
        FashionShow.resetForTests();
    }

    static Trend trend(UUID village) {
        return TRENDS.computeIfAbsent(village, Fashion::load);
    }

    /** The season's colour (a dye id), or -1 before there is a fashion. */
    public static int trendColour(UUID village) {
        return trend(village).colour;
    }

    /** The thing the season's colour is worn as, or null for anything. */
    @Nullable
    public static Garment trendKind(UUID village) {
        return trend(village).kind;
    }

    /** Who set the season's fashion, and why: "Ada, the leader's partner". */
    public static String setBy(UUID village) {
        Trend t = trend(village);
        return t.setter.isEmpty() ? "" : t.setter + (t.why.isEmpty() ? "" : ", " + t.why);
    }

    /** Does this folk wear the season's fashion (or set it)? */
    public static boolean inFashion(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return false;
        Trend t = trend(id);
        return t.set() && (f.style().wears(t.colour) || f.getUUID().equals(t.setterId) && f.style().main == t.colour)
            || Weave.wonFinery(f);                                          // [weave] what it won at the auction this season
    }

    /** The season's key: its year and its season. */
    static int seasonOf(UUID village, long day) {
        return Seasons.year(village, day) * 4 + Seasons.season(village, day).ordinal();
    }

    static String seasonWord(UUID village, long day) {
        return Seasons.season(village, day).word;
    }

    // ------------------------------------------------------------------ the town, every ten seconds (TownLife)

    /**
     * The town's look at its fashion (TownLife.tick): the players about it in their finery noted; once a day, from the
     * morning, the season's fashion set (as a season turns) and gone round a day (spread); and the tailor's errands
     * for it (Tailoring: flowers for dyes, a rosette before a show).
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long dt = level.getDayTime(), day = Math.floorDiv(dt, 24000L);
        watchGuests(level, v, day);
        if (Math.floorMod(dt, 24000L) >= 1000L && DONE.getOrDefault(id, Long.MIN_VALUE) != day) {
            DONE.put(id, day);
            daily(level, v, day);
        }
        Tailoring.tick(level, v, day);
    }

    /** A day of the town's fashion: a new season's look set if the season has turned, then a day of its going round. */
    static void daily(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Villages.headcount(id) < 3) return;
        Trend t = trend(id);
        int season = seasonOf(id, day);
        if (!t.set() || t.season != season) newTrend(level, v, day, season);
        spread(level, v, day);
        save(id, t);
    }

    // ------------------------------------------------------------------ who sets it

    /** One who might set the season's look: a folk or a player, how much the town looks to it, why, and its look. */
    record Setter(@Nullable VillageFolkEntity folk, @Nullable UUID player, String name, double score, String why, int colour,
                  @Nullable Garment kind) {}

    /**
     * Everybody the town looks to, the most admired first: its folk by their wealth, their office, how well liked they
     * are and their youth; and the players it has seen about it in their finery, by their standing here and how often
     * they come. A Traditionalist sets nothing.
     */
    static List<Setter> setters(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Trend t = trend(id);
        List<VillageFolkEntity> folk = grown(id);
        Map<VillageFolkEntity, Double> score = new HashMap<>();
        Map<VillageFolkEntity, List<String>> why = new HashMap<>();
        for (VillageFolkEntity f : folk) { score.put(f, 0.0); why.put(f, new ArrayList<>()); }
        // The wealthiest.
        List<VillageFolkEntity> rich = new ArrayList<>(folk);
        Map<VillageFolkEntity, Integer> worth = new HashMap<>();
        for (VillageFolkEntity f : folk) worth.put(f, Wealth.worth(f));
        rich.sort((a, b) -> Integer.compare(worth.get(b), worth.get(a)));
        if (!rich.isEmpty() && worth.get(rich.get(0)) > 0) add(score, why, rich.get(0), 2.0, "the wealthiest in town");
        if (rich.size() > 1 && worth.get(rich.get(1)) > 0) add(score, why, rich.get(1), 1.0, "one of the wealthiest");
        // The leader, and its partner.
        UUID elder = Villages.elder(id);
        for (VillageFolkEntity f : folk) {
            if (f.getUUID().equals(elder)) {
                add(score, why, f, 2.0, "the leader");
                for (VillageFolkEntity p : folk) if (p.getUUID().equals(f.life().partner())) add(score, why, p, 1.5, "the leader's partner");
            }
        }
        // The best liked: how many count it a friend.
        Map<VillageFolkEntity, Integer> liked = new HashMap<>();
        for (VillageFolkEntity f : folk) {
            int n = 0;
            for (VillageFolkEntity o : folk) if (o != f && o.life().affinity(f.getUUID()) >= Social.FRIEND) n++;
            liked.put(f, n);
        }
        List<VillageFolkEntity> loved = new ArrayList<>(folk);
        loved.sort((a, b) -> Integer.compare(liked.get(b), liked.get(a)));
        if (!loved.isEmpty() && liked.get(loved.get(0)) > 0) add(score, why, loved.get(0), 2.0, "the best liked in town");
        if (loved.size() > 1 && liked.get(loved.get(1)) > 0) add(score, why, loved.get(1), 1.0, "well liked");
        List<Setter> out = new ArrayList<>();
        for (VillageFolkEntity f : folk) {
            double s = score.get(f);
            s += Weave.admired(f, why.get(f));                              // [weave] the steward, the auctioneer: the town looks to them
            if (f.ageYears() < 28) { s += 1.0; why.get(f).add("young"); }
            if (f.life().has(Social.Trait.SOCIABLE)) s += 0.5;
            if (vain(f)) s += 0.5;
            if (traditional(f)) s -= 3.0;
            if (f.getUUID().equals(t.setterId)) s -= 1.5;                   // fashion moves on: not the same one twice running
            if (s < 1.0) continue;
            List<String> w = why.get(f);
            String words = w.isEmpty() ? "much admired" : w.size() == 1 ? w.get(0) : w.get(0) + " and " + w.get(1);
            int colour = lookOf(f);
            if (colour < 0 || colour >= Garment.NATURAL || colour == t.colour) colour = fresh(f, Seasons.season(id, day), t.colour);
            out.add(new Setter(f, null, f.displayNameCap(), s, words, colour, grandestBody(f, Villages.ageOf(id))));
        }
        // The players the town has seen about it, in dyed leather, lately.
        Map<UUID, Guest> seen = guests(id);
        for (Map.Entry<UUID, Guest> e : seen.entrySet()) {
            Guest g = e.getValue();
            if (g.pieces <= 0 || g.colour < 0 || day - g.lastDyed > 2) continue;
            Standing.Title title = Standing.of(id, e.getKey(), level.getGameTime()).title();
            double s = switch (title) {
                case HERO -> 5.0;
                case HONOURED -> 3.5;
                case FRIEND -> 2.0;
                default -> 0.0;
            };
            if (s <= 0) continue;
            int recent = 0;
            for (long d : g.days) if (day - d < 7) recent++;
            s += Math.min(3.0, 0.5 * recent) + 0.5 * Math.min(4, g.pieces);
            Garment kind = g.chest && Villages.ageOf(id).ordinal() >= Garment.LEATHER_JACKET.age.ordinal() ? Garment.LEATHER_JACKET : null;
            out.add(new Setter(null, e.getKey(), g.name, s, title.words.replace("the village's", "the town's"), g.colour, kind));
        }
        out.sort(Comparator.comparingDouble(Setter::score).reversed());
        return out;
    }

    private static void add(Map<VillageFolkEntity, Double> score, Map<VillageFolkEntity, List<String>> why, VillageFolkEntity f,
                            double d, String w) {
        score.merge(f, d, Double::sum);
        why.get(f).add(w);
    }

    /** The colour of what it wears, its finest thing first (its coat, its hat, its scarf), else its own colour. */
    static int lookOf(VillageFolkEntity f) {
        Style s = f.style();
        for (Garment.Slot slot : new Garment.Slot[]{ Garment.Slot.BODY, Garment.Slot.HEAD, Garment.Slot.NECK }) {
            int c = s.colour(slot);
            if (c >= 0 && c < Garment.NATURAL) return c;
        }
        return s.main;
    }

    /** What the season wears, as the dyers have it: spring's soft colours, summer's bright, autumn's warm, winter's deep. */
    static final Map<Seasons.Season, DyeColor[]> PALETTE = Map.of(
        Seasons.Season.SPRING, new DyeColor[]{ DyeColor.PINK, DyeColor.LIGHT_BLUE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.WHITE, DyeColor.MAGENTA },
        Seasons.Season.SUMMER, new DyeColor[]{ DyeColor.YELLOW, DyeColor.ORANGE, DyeColor.LIGHT_BLUE, DyeColor.RED, DyeColor.CYAN, DyeColor.WHITE },
        Seasons.Season.AUTUMN, new DyeColor[]{ DyeColor.ORANGE, DyeColor.RED, DyeColor.BROWN, DyeColor.YELLOW, DyeColor.PURPLE, DyeColor.GREEN },
        Seasons.Season.WINTER, new DyeColor[]{ DyeColor.BLUE, DyeColor.PURPLE, DyeColor.RED, DyeColor.WHITE, DyeColor.GREEN, DyeColor.BLACK });

    /** Something new for the season, not the look it is tired of: its second colour or its favourite if the season wears it, else the season's own. */
    static int fresh(VillageFolkEntity f, Seasons.Season season, int last) {
        DyeColor[] pal = PALETTE.get(season);
        Style s = f.style();
        int fav = Decor.colour(f).getId();
        for (int c : new int[]{ s.accent, fav, s.main }) {
            if (c < 0 || c == last) continue;
            for (DyeColor d : pal) if (d.getId() == c) return c;
        }
        int start = Math.floorMod(f.getUUID().hashCode(), pal.length);
        for (int i = 0; i < pal.length; i++) {
            int c = pal[(start + i) % pal.length].getId();
            if (c != last) return c;
        }
        return pal[0].getId();
    }

    /** The grandest thing for the body its standing and the town's age run to (the setter's look), or null. */
    @Nullable
    static Garment grandestBody(VillageFolkEntity f, Villages.Age age) {
        Garment worn = f.style().garment(Garment.Slot.BODY);
        if (worn != null) return worn;
        Garment best = null;
        int reach = reach(f);
        for (Garment g : Garment.values()) {
            if (g.slot != Garment.Slot.BODY || g.rank > reach || g.age.ordinal() > age.ordinal()) continue;
            if (best == null || g.rank > best.rank) best = g;
        }
        return best;
    }

    /** A new season's look: the most admired's, its colour and what it wears it as; the old one fades. */
    static void newTrend(ServerLevel level, Villages.Village v, long day, int season) {
        UUID id = v.id();
        Trend t = trend(id);
        List<Setter> who = setters(level, v, day);
        Setter top = who.isEmpty() ? null : who.get(0);
        if (t.set()) {
            t.lastColour = t.colour;
            t.lastKind = t.kind;
            t.lastSince = t.since;
        }
        t.season = season;
        t.since = day;
        t.toldMost = false;
        t.days.clear();
        if (top == null) {
            // Nobody stands out: the season's own colour, and anything to wear it in.
            DyeColor[] pal = PALETTE.get(Seasons.season(id, day));
            int c = pal[Math.floorMod(id.hashCode() + season, pal.length)].getId();
            if (c == t.lastColour) c = pal[Math.floorMod(id.hashCode() + season + 1, pal.length)].getId();
            t.colour = c;
            t.kind = null;
            t.setter = "";
            t.setterId = null;
            t.why = "the season";
            t.byPlayer = false;
        } else {
            t.colour = top.colour();
            t.kind = top.kind();
            t.setter = top.name();
            t.setterId = top.folk() != null ? top.folk().getUUID() : top.player();
            t.why = top.why();
            t.byPlayer = top.player() != null;
        }
        String when = seasonWord(id, day);
        String line = Garment.colourWord(t.colour) + (t.kind != null ? " " + t.kind.plural() : "") + " became the fashion this " + when
            + (t.setter.isEmpty() ? "" : ", set by " + t.setter + ", " + t.why);
        Villages.tell(id, day, line);
        LOG.info("[MCA-FASHION] {}: {}", Villages.name(id), line);
        if (top != null && top.folk() != null) {
            VillageFolkEntity f = top.folk();
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), Garment.colourCap(t.colour) + " — that's the colour this " + when + ". Mark my words.",
                "I fancy something " + Garment.colourWord(t.colour) + " this " + when + ".", "Out with the old! " + Garment.colourCap(t.colour)
                    + " is the thing now."));
            if (!f.style().wears(t.colour)) want(level, v, f, t, day, true);      // and has one made first
        }
        if (top != null && top.player() != null && level.getServer().getPlayerList().getPlayer(top.player()) instanceof ServerPlayer p) {
            p.sendSystemMessage(Component.literal(Villages.name(id) + " has taken to " + Garment.colourWord(t.colour)
                + " this " + when + " — after you. The tailor will be busy.").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
        }
        // The old look's wants that came to nothing fade with it; what is on the tailor's book stands.
        for (VillageFolkEntity f : grown(id)) {
            Style s = f.style();
            if (s.want != null && !s.ordered && s.wantColour >= 0 && s.wantColour != t.colour) s.want = null;
        }
        save(id, t);
    }

    // ------------------------------------------------------------------ how it goes round

    /**
     * A day of the fashion going round. Each grown folk not yet in it comes round a little: PACE, by how many of its
     * friends wear it and how much of the town does, by its nature, its years and its means (pull). Come round (its
     * pull at one), it wants the look. Whoever wears it is counted, and the day goes into the books.
     */
    static void spread(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Trend t = trend(id);
        if (!t.set()) return;
        if (!t.days.isEmpty() && t.days.get(t.days.size() - 1)[0] >= day) return;       // gone round today already (a restart)
        List<VillageFolkEntity> folk = grown(id);
        Map<UUID, Boolean> in = new HashMap<>();
        int wearing = 0, last = 0;
        for (VillageFolkEntity f : folk) {
            roll(f);
            boolean yes = inFashion(f);
            in.put(f.getUUID(), yes);
            if (yes) wearing++;
            if (t.lastColour >= 0 && f.style().wears(t.lastColour)) last++;
        }
        double town = folk.isEmpty() ? 0 : wearing / (double) folk.size();
        for (VillageFolkEntity f : folk) {
            Style s = f.style();
            if (in.get(f.getUUID())) {
                if (!s.followed.equals(t.key())) {
                    s.followed = t.key();
                    s.followedOn = day;
                }
                continue;
            }
            if (s.want != null && s.wantColour == t.colour) continue;              // wants it already
            if (!s.pullFor.equals(t.key())) {
                s.pull = 0;
                s.pullFor = t.key();
            }
            s.pull += pull(f, friendsIn(f, in), town);
            if (s.pull >= 1.0) want(level, v, f, t, day, false);
        }
        t.days.add(new long[]{ day, wearing, last, folk.size() });
        while (t.days.size() > HISTORY) t.days.remove(0);
        if (!t.toldMost && folk.size() >= 4 && wearing * 2 > folk.size()) {
            t.toldMost = true;
            Villages.tell(id, day, "most of the town wears " + Garment.colourWord(t.colour) + " now: " + wearing + " of " + folk.size());
        }
    }

    /** How many of its friends (and its partner) wear the season's look, of how many: nought with no friends. */
    static double friendsIn(VillageFolkEntity f, Map<UUID, Boolean> in) {
        int friends = 0, wearing = 0;
        for (Map.Entry<UUID, Social.Bond> e : f.life().bonds.entrySet()) {
            boolean close = e.getValue().affinity >= Social.FRIEND || e.getKey().equals(f.life().partner());
            if (!close || !in.containsKey(e.getKey())) continue;
            friends++;
            if (in.get(e.getKey())) wearing++;
        }
        return friends == 0 ? 0.0 : wearing / (double) friends;
    }

    /** A day's coming round to the season's look, for this folk, with so many of its friends and of the town in it. */
    static double pull(VillageFolkEntity f, double friends, double town) {
        double p = PACE * (0.3 + 1.2 * friends + 1.2 * town);
        return p * nature(f) * youth(f) * means(f) * Ethos.fashionPace(f.ownerId());   // [identity] a forward-looking town quick to it
    }

    /** Its nature's say: the sociable and the vain quick, the shy and the grumpy slow, a Traditionalist hardly moved. */
    static double nature(VillageFolkEntity f) {
        double n = 1.0;
        Social.Life life = f.life();
        if (life.has(Social.Trait.SOCIABLE)) n *= 1.5;
        if (life.has(Social.Trait.CHEERFUL)) n *= 1.15;
        if (life.has(Social.Trait.SHY)) n *= 0.75;
        if (life.has(Social.Trait.GRUMPY)) n *= 0.7;
        if (vain(f)) n *= 1.5;
        if (traditional(f)) n *= 0.05;
        else if (f.persona().rolled() && Values.top(f) == Values.Value.LEISURE) n *= 1.2;
        n *= Weave.fitIn(f);                                                // [weave] a newcomer wants to fit in
        return n;
    }

    /** The young set fashions and follow them; the old let them pass. */
    static double youth(VillageFolkEntity f) {
        int age = f.ageYears();
        return age < 28 ? 1.4 : age >= 55 ? 0.6 : 1.0;
    }

    /** Its means: the poor come round slowly (what would they buy it with?), the well-off quickly. */
    static double means(VillageFolkEntity f) {
        return switch (Wealth.tier(f)) {
            case POOR -> 0.4;
            case GETTING_BY -> 0.7;
            case COMFORTABLE -> 1.0;
            case WELL_OFF -> 1.2;
            case WEALTHY -> 1.3;
        };
    }

    /** A little vain: it loves fine things (wool, gems or gold). */
    public static boolean vain(VillageFolkEntity f) {
        if (!f.persona().rolled()) return false;
        Persona.Gift g = f.persona().loves();
        return g == Persona.Gift.WOOL || g == Persona.Gift.GEMS || g == Persona.Gift.GOLD;
    }

    /** A Traditionalist (Values): it keeps to its own. */
    public static boolean traditional(VillageFolkEntity f) {
        return f.persona().rolled() && Values.top(f) == Values.Value.TRADITION;
    }

    /** How grand a thing its standing runs to (Garment.rank): the poor a scarf, the wealthy a top hat. */
    static int reach(VillageFolkEntity f) {
        return switch (Wealth.tier(f)) {
            case POOR -> 0;
            case GETTING_BY -> 1;
            case COMFORTABLE -> 2;
            case WELL_OFF -> 3;
            case WEALTHY -> 4;
        };
    }

    /**
     * It wants the season's look: the season's thing if it runs to it, else the grandest it runs to in a place it has
     * nothing in (or in the place it would change), in the season's colour. {@code setter}: the one who set it, whose
     * order the tailor makes first.
     */
    static void want(ServerLevel level, Villages.Village v, VillageFolkEntity f, Trend t, long day, boolean setter) {
        Garment g = choose(f, t, Villages.ageOf(v.id()));
        if (g == null) return;
        Style s = f.style();
        s.want = g;
        s.wantColour = t.colour;
        s.wantSince = day;
        s.ordered = false;
        s.pull = 0;
        if (setter) Tailoring.order(level, v, f, g, t.colour, day, true);
        else if (f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I simply must have " + g.a(t.colour) + ".",
                "Everybody's in " + Garment.colourWord(t.colour) + ". I'll have " + g.a(t.colour) + ", I think.",
                Garment.colourCap(t.colour) + ", is it? Well, " + g.a(t.colour) + " wouldn't hurt."));
        }
        f.brain("wants " + g.a(t.colour) + ", the fashion this " + seasonWord(v.id(), day));
    }

    /** What it would have of the season's look (see want), or null if nothing will do. */
    @Nullable
    static Garment choose(VillageFolkEntity f, Trend t, Villages.Age age) {
        int reach = reach(f);
        if (t.kind != null && t.kind.rank <= reach && t.kind.age.ordinal() <= age.ordinal()) return t.kind;
        Garment office = Weave.roleGarment(f, t.colour, age, reach);       // [weave] its office's garment: the librarian's waistcoat
        if (office != null) return office;
        Style s = f.style();
        Garment best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Garment g : Garment.values()) {
            if (!g.dyeable() || g == Garment.ROSETTE || g.rank > reach || g.age.ordinal() > age.ordinal()) continue;
            int score = g.rank * 2;
            if (s.worn(g.slot).isEmpty()) score += 3;                         // something it has not got
            if (t.kind != null && g.slot == t.kind.slot) score += 2;           // as near the season's thing as it runs to
            score += Math.floorMod(f.getUUID().hashCode() >> g.ordinal(), 2);  // its own taste
            if (score > bestScore) { bestScore = score; best = g; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the folk, every few seconds (its agenda)

    /** Its own colours, the first time it is looked at: its favourite and one that goes with it. */
    static void roll(VillageFolkEntity f) {
        Style s = f.style();
        if (s.rolled()) return;
        s.main = f.persona().rolled() ? Decor.colour(f).getId() : Math.floorMod(f.getUUID().hashCode(), 16);
        int[] goes = GOES[s.main];
        s.accent = goes[(int) Math.floorMod(f.getUUID().getLeastSignificantBits() >> 3, (long) goes.length)];
    }

    /** The colours that go with each (a dye id's second colours), for its accent and the show's judges. */
    static final int[][] GOES = {
        /* white */ { 11, 14, 15, 13 }, /* orange */ { 12, 0, 11 }, /* magenta */ { 10, 0, 6 }, /* light blue */ { 0, 11, 4 },
        /* yellow */ { 11, 12, 0, 13 }, /* lime */ { 13, 0, 9 }, /* pink */ { 0, 2, 7 }, /* grey */ { 0, 14, 15, 4 },
        /* light grey */ { 11, 15, 6 }, /* cyan */ { 0, 11, 1 }, /* purple */ { 4, 0, 2 }, /* blue */ { 0, 4, 3, 14 },
        /* brown */ { 1, 4, 0 }, /* green */ { 4, 0, 12 }, /* red */ { 0, 4, 15 }, /* black */ { 0, 14, 7 } };

    /** Do these two colours go together? */
    public static boolean goes(int a, int b) {
        if (a < 0 || a > 15 || b < 0 || b > 15) return false;
        for (int c : GOES[a]) if (c == b) return true;
        for (int c : GOES[b]) if (c == a) return true;
        return false;
    }

    /**
     * Its look at itself, every few seconds (VillageFolkEntity's agenda): its own colours chosen the first time; its
     * hat on off work, at a gathering and on the rest day; what it wants put on the tailor's book if the town has
     * none of it, or let go when nothing has come of it for days; and all of it shown to the client.
     */
    public static void look(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return;
        Style s = f.style();
        if (f.isBaby()) {
            f.showStyle(0L);
            return;
        }
        roll(f);
        UUID id = f.ownerId();
        long day = level.getDayTime() / 24000L;
        boolean watch = f.stationTask() == AssistantEntity.StationTask.GUARD && f.onWatch();
        s.dressed = !watch && (f.offWorkNow() || id != null && (Assemblies.now(id) != null || RestDay.today(id, day)));
        if (!watch && Weave.dressedForRole(f)) s.dressed = true;           // [weave] its office's hat on: the constable on a case
        Villages.Village v = id == null ? null : Villages.get(id);
        // A garment of its own it is carrying (a treat from the shop, a thing from a player's stall) goes on, if it has
        // nothing in that place or it is the season's colour and what it has on is not. Never a courier's load.
        if (v != null) {
            for (ItemStack st : f.getInventoryItems()) {
                Garment g = Garment.of(st);
                if (g == null || g == Garment.ROSETTE || !Homes.isKeepsake(st) || !Homes.ownedBy(st, f)) continue;
                int c = Garment.colourOf(st);
                boolean better = s.worn(g.slot).isEmpty() || trend(id).set() && c == trend(id).colour && s.colour(g.slot) != c;
                if (!better) continue;
                ItemStack one = st.split(1);
                wear(level, v, f, one, "bought");
                break;
            }
        }
        // A vain folk with a felt hat sticks a feather in its band: one of the stores' (the pens' and the hunt's), bought.
        if (v != null && s.feather.isEmpty() && s.garment(Garment.Slot.HEAD) == Garment.FELT_HAT && vain(f) && s.featherTry != day) {
            s.featherTry = day;
            ItemStack feather = buy(level, v, f, st -> st.is(Items.FEATHER) && st.getComponentsPatch().isEmpty());
            if (!feather.isEmpty()) {
                s.feather = feather.copyWithCount(1);
                f.brain("stuck a feather in its felt hat");
            }
        }
        // A vain folk that is well off likes a brooch to set it all off: gold and lapis, off the tailor's book, once the
        // town has the Iron Age's gold to make one of. A day in the week it thinks of it.
        if (v != null && s.want == null && s.worn(Garment.Slot.PIN).isEmpty() && vain(f) && reach(f) >= Garment.BROOCH.rank
                && Villages.ageOf(id).ordinal() >= Garment.BROOCH.age.ordinal() && Math.floorMod(f.getUUID().hashCode() + day, 7L) == 0) {
            s.want = Garment.BROOCH;
            s.wantColour = -1;
            s.wantSince = day;
            s.ordered = false;
        }
        if (v != null && s.want != null) {
            Trend t = trend(id);
            boolean stale = !s.ordered && day - s.wantSince > WANT_DAYS;
            boolean passed = t.set() && s.wantColour >= 0 && s.wantColour != t.colour && !s.ordered;
            if (stale || passed) {
                s.want = null;
            } else if (!s.ordered && stockOf(level, id, wanted(s), false) <= 0) {
                Tailoring.order(level, v, f, s.want, s.wantColour, day, false);
            } else if (s.ordered && !Tailoring.onBook(id, f.getUUID()) && stockOf(level, id, wanted(s), false) <= 0) {
                s.ordered = false;          // made, and somebody else bought it first: on the book again at its next look
            }
        }
        f.showStyle(s.pack(inFashion(f)));
    }

    /** What would do for what it wants: the thing, in the colour, new (never a second-hand one). */
    static Predicate<ItemStack> wanted(Style s) {
        Garment g = s.want;
        int c = s.wantColour;
        return st -> g != null && Garment.of(st) == g && Garment.colourOf(st) == c && !secondHand(st);
    }

    /** How many of what matches the town has to sell: in the stores and on the store's own shelves. */
    static int stockOf(ServerLevel level, UUID village, Predicate<ItemStack> what, boolean storesOnly) {
        int n = Market.stock(level, village, what);
        return storesOnly ? n : n + Store.count(level, village, what);
    }

    // ------------------------------------------------------------------ to the shop for it (its time off)

    /**
     * Off work, wanting something the town has in stock: to the shop's counter (the store's, else the stores) and buy
     * it, then put it on (VillageFolkEntity.socialise). A poor folk makes do with a second-hand one of the colour.
     * Once a day at most it gives up (too dear, or it could not get there). True while it is about it.
     */
    public static boolean shopping(VillageFolkEntity f, ServerLevel level) {
        Style s = f.style();
        UUID id = f.ownerId();
        if (s.want == null || id == null || f.isBaby()) return false;
        Villages.Village v = Villages.get(id);
        if (v == null) return false;
        long day = level.getDayTime() / 24000L;
        long[] e = ERRANDS.computeIfAbsent(f.getUUID(), k -> new long[]{ -1, -1000, -100 });
        if (e[2] == day) return false;
        Predicate<ItemStack> what = wanted(s);
        boolean second = false;
        if (stockOf(level, id, what, false) <= 0) {
            Predicate<ItemStack> used = secondHandOf(s.want.slot, s.wantColour);
            if (Wealth.tier(f).ordinal() > Wealth.Tier.GETTING_BY.ordinal() || stockOf(level, id, used, false) <= 0) return false;
            what = used;
            second = true;
        }
        BlockPos counter = counter(level, id);
        if (counter != null && f.blockPosition().distSqr(counter) > 16.0) {
            if (e[0] < 0) e[0] = f.tickCount;
            if (f.tickCount - e[0] > 1800) {                                  // could not get there
                e[0] = -1;
                e[2] = day;
                return false;
            }
            if (f.getNavigation().isDone() || f.tickCount - e[1] >= 100) {
                f.walkTo(counter, 0.9D);
                e[1] = f.tickCount;
            }
            f.hobbyNow = "off to the shop for " + s.want.a(s.wantColour);
            return true;
        }
        e[0] = -1;
        e[2] = day;
        f.getNavigation().stop();
        ItemStack got = second ? buySecondHand(level, v, f, what) : buy(level, v, f, what);
        if (!got.isEmpty()) wear(level, v, f, got, second ? "second-hand" : "bought");
        return true;
    }

    /** Where it goes to buy: the store's door, else the shop's, else the stores. */
    @Nullable
    static BlockPos counter(ServerLevel level, UUID village) {
        if (Store.stands(village)) {
            BlockPos at = Villages.builtAt(village, Store.STRUCTURE);
            if (at != null) return at;
        }
        BlockPos shop = Villages.builtAt(village, "shop");
        if (shop != null && Purchases.open(village)) return shop;
        List<BlockPos> stores = Villages.storeChests(level, village);
        return stores.isEmpty() ? null : stores.get(0);
    }

    /**
     * One of what it wants, bought the way a folk buys its own (Purchases): out of the stores before the shop opens,
     * as the town's; from then on at the town's price (dearer the more it is wanted: PriceIndex), weighed against what
     * it expects to pay (too dear, it is left on the shelf, and the price hears of it), paid out of its purse, taken
     * off the store's shelves or out of the stores. What it came away with, or nothing.
     */
    static ItemStack buy(ServerLevel level, Villages.Village v, VillageFolkEntity f, Predicate<ItemStack> what) {
        UUID id = v.id();
        ItemStack sample = sampleOf(level, id, what);
        if (sample.isEmpty()) return ItemStack.EMPTY;
        Predicate<ItemStack> exact = s -> ItemStack.isSameItemSameComponents(s, sample);
        if (!Purchases.pays(id, f, Purchases.Need.CLOTHES)) {
            // Before the shop opens the stores clothe the town, as they feed it: wanted all the same (the prices).
            if (!TownWork.take(level, v, exact, 1)) return ItemStack.EMPTY;
            PriceIndex.drawn(id, sample, 1);
            return sample.copy();
        }
        double each = Purchases.priceEach(level, id, sample, f);
        if (Purchases.decide(level, f, sample, each, Purchases.Need.CLOTHES, 1) <= 0) return ItemStack.EMPTY;
        if (!Purchases.canPay(f, each)) {
            Purchases.refuse(level, f, sample, each, 1);
            return ItemStack.EMPTY;
        }
        List<ItemStack> got = ShopStock.takeStacks(level, v, exact, 1, f);
        if (got.isEmpty()) return ItemStack.EMPTY;
        int coins = Purchases.charge(level, f, id, each, false, sample, 1);
        if (coins < 0) {
            for (ItemStack s : got) Crafts.store(level, v, s);
            return ItemStack.EMPTY;
        }
        Stockroom.sold(level, id, Stockroom.Seller.SHOP, sample, 1, coins);
        PriceIndex.bought(id, sample, 1);
        f.brain("bought " + sample.getHoverName().getString().toLowerCase(Locale.ROOT) + String.format(Locale.ROOT, " for %.2f coins", each));
        return got.get(0).copyWithCount(1);
    }

    /** A second-hand one, for a third of the price (free before the shop opens): the poor's way into the fashion. */
    static ItemStack buySecondHand(ServerLevel level, Villages.Village v, VillageFolkEntity f, Predicate<ItemStack> what) {
        UUID id = v.id();
        ItemStack sample = sampleOf(level, id, what);
        if (sample.isEmpty()) return ItemStack.EMPTY;
        Predicate<ItemStack> exact = s -> ItemStack.isSameItemSameComponents(s, sample);
        double each = Math.max(0.2, Purchases.priceEach(level, id, sample, f) / 3.0);
        boolean pays = Purchases.pays(id, f, Purchases.Need.CLOTHES);
        if (pays && !Purchases.canPay(f, each)) return ItemStack.EMPTY;
        List<ItemStack> got = ShopStock.takeStacks(level, v, exact, 1, f);
        if (got.isEmpty()) return ItemStack.EMPTY;
        if (pays) {
            int coins = Purchases.charge(level, f, id, each, false, sample, 1);
            if (coins < 0) {
                for (ItemStack s : got) Crafts.store(level, v, s);
                return ItemStack.EMPTY;
            }
            Stockroom.sold(level, id, Stockroom.Seller.SHOP, sample, 1, coins);
        }
        return got.get(0).copyWithCount(1);
    }

    /** One of what matches, as the store's shelves or the stores hold it, or nothing. */
    static ItemStack sampleOf(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        for (net.minecraft.world.Container c : Store.containers(level, village, true)) {
            for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty() && what.test(c.getItem(i))) return c.getItem(i).copyWithCount(1);
        }
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty() && what.test(c.getItem(i))) return c.getItem(i).copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ wearing it, and the old one

    /**
     * It puts this on (bought, given, won, or come to it through the poor box): in its place, whatever was there going
     * to somebody with less (passOn). It shows it off, remembers it, and is in the season's fashion if it is the
     * season's colour.
     */
    static void wear(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack thing, String how) {
        Garment g = Garment.of(thing);
        if (g == null) return;
        Style s = f.style();
        long day = level.getDayTime() / 24000L;
        int colour = Garment.colourOf(thing);
        ItemStack old = s.worn[g.slot.ordinal()];
        s.worn[g.slot.ordinal()] = thing.copyWithCount(1);
        s.newOn = day;
        s.newWhat = g.a(colour);
        if (s.want == g && s.wantColour == colour) {
            s.want = null;
            s.ordered = false;
            Tailoring.done(v.id(), f.getUUID());
        }
        Trend t = trend(v.id());
        if (t.set() && colour == t.colour) {
            s.followed = t.key();
            s.followedOn = day;
        }
        if (!how.equals("won")) {
            f.persona().remember(day, how.equals("given") ? "I was given " + s.newWhat : "I got " + s.newWhat + (how.equals("bought") ? "" : ", " + how), 2);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Have you seen my new " + g.noun + "?",
                "New " + g.noun + " — " + Garment.colourWord(colour) + ", see? What do you think?",
                t.set() && colour == t.colour ? "All the rage, " + Garment.colourWord(colour) + ". I had to have it."
                    : "I do like " + s.newWhat.replaceFirst("^an? ", "") + "."));
        }
        f.brain("put on " + s.newWhat + " (" + how + ")");
        if (!old.isEmpty()) passOn(level, v, f, old);
        f.showStyle(s.pack(inFashion(f)));
    }

    /** Marked second-hand: a lore line, and its first wearer's name in the fashion's own mark. */
    static void markSecondHand(ItemStack s, String from) {
        CompoundTag mark = new CompoundTag();
        mark.putString("from", from);
        CustomData.update(DataComponents.CUSTOM_DATA, s, tag -> tag.put(SECOND_HAND, mark));
        ItemLore lore = s.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        s.set(DataComponents.LORE, lore.withLineAdded(Component.literal("Second-hand, once " + from + "'s")
            .withStyle(net.minecraft.ChatFormatting.GRAY)));
    }

    static final String SECOND_HAND = "mca_second_hand";

    public static boolean secondHand(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.contains(SECOND_HAND);
    }

    /** A second-hand thing for this place, of this colour (any colour, for NATURAL or none). */
    static Predicate<ItemStack> secondHandOf(Garment.Slot slot, int colour) {
        return s -> {
            Garment g = Garment.of(s);
            return g != null && g.slot == slot && secondHand(s) && (colour < 0 || Garment.colourOf(s) == colour);
        };
    }

    /**
     * What it had on goes to somebody with less: a poor neighbour (or one getting by) with nothing in that place, its
     * friends first, through the poor box, free; else sold second-hand to the stores for a third of its worth (out of
     * the treasury into its purse), for a poor folk to buy cheap. Booked for the week.
     */
    static void passOn(ServerLevel level, Villages.Village v, VillageFolkEntity giver, ItemStack old) {
        Garment g = Garment.of(old);
        if (g == null || g == Garment.ROSETTE) {
            // An old rosette is a keepsake, never passed on: into its pack (its own, and it moves house with it).
            if (g == Garment.ROSETTE) Homes.keepsake(old, giver);
            ItemStack left = g == Garment.ROSETTE ? giver.insertGiven(old) : old;
            if (!left.isEmpty()) Crafts.store(level, v, left);
            return;
        }
        UUID id = v.id();
        if (!secondHand(old)) markSecondHand(old, giver.displayNameCap());
        VillageFolkEntity taker = null;
        int best = Integer.MIN_VALUE;
        for (VillageFolkEntity f : grown(id)) {
            if (f == giver || !f.style().worn(g.slot).isEmpty() || traditional(f)) continue;
            Wealth.Tier tier = Wealth.tier(f);
            if (tier.ordinal() > Wealth.Tier.GETTING_BY.ordinal()) continue;
            int score = (tier == Wealth.Tier.POOR ? 20 : 10) + Math.max(0, giver.life().affinity(f.getUUID())) / 5;
            if (score > best) { best = score; taker = f; }
        }
        long day = level.getDayTime() / 24000L;
        if (taker != null) {
            String words = g.a(Garment.colourOf(old));
            booked(id, day, 0);
            giver.sayLater(FolkTalk.pick(giver.getRandom(), "My old " + g.noun + "'s gone to the poor box. " + taker.displayNameCap() + " has it now.",
                "There — " + words + " for the poor box. Somebody may as well be warm in it."), 40);
            taker.persona().remember(day, giver.displayNameCap() + "'s old " + g.noun + " came to me through the poor box", 2);
            wear(level, v, taker, old, "through the poor box, from " + giver.displayNameCap());
            return;
        }
        int coins = Math.max(0, (int) Math.floor(Prices.of(old) / 3.0));
        coins = coins > 0 ? Ledger.takeCoins(id, coins) : 0;
        if (coins > 0) {
            giver.earn(coins);
            Economy.spent(id, coins);
        }
        booked(id, day, 1);
        Crafts.store(level, v, old);
        giver.brain("sold its old " + g.noun + " second-hand to the stores for " + coins + " coins");
    }

    /** The week's old things passed on: {through the poor box, sold second-hand}, a day each, today first. */
    private static void booked(UUID village, long day, int which) {
        long[][] w = week(village, day);
        w[which][0]++;
        StringBuilder sb = new StringBuilder().append(day);
        for (int r = 0; r < 2; r++) {
            sb.append(';');
            for (int i = 0; i < 7; i++) sb.append(i == 0 ? "" : ",").append(w[r][i]);
        }
        Ledger.note(village, PASSED, sb.toString());
    }

    static long[][] week(UUID village, long today) {
        long[][] w = new long[2][7];
        String s = Ledger.note(village, PASSED);
        if (s == null || s.isEmpty()) return w;
        try {
            String[] p = s.split(";");
            long day = Long.parseLong(p[0]);
            int shift = (int) Math.max(0, Math.min(7, today - day));
            for (int r = 0; r < 2 && r + 1 < p.length; r++) {
                String[] xs = p[r + 1].split(",");
                for (int i = 0; i < 7 && i < xs.length; i++) if (i + shift < 7) w[r][i + shift] = Long.parseLong(xs[i].trim());
            }
        } catch (RuntimeException e) {
            return new long[2][7];
        }
        return w;
    }

    /** The week's: {passed on through the poor box, sold second-hand}. */
    public static int[] passedThisWeek(UUID village, long today) {
        long[][] w = week(village, today);
        int a = 0, b = 0;
        for (int i = 0; i < 7; i++) { a += (int) w[0][i]; b += (int) w[1][i]; }
        return new int[]{ a, b };
    }

    // ------------------------------------------------------------------ a player's part

    /**
     * The players about the town, looked at every ten seconds: one in dyed leather is noted (its colour, how many
     * pieces, whether a jacket), with the days it has been about, for the town to take its fashions from (setters).
     */
    static void watchGuests(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        int reach = Villages.townReach(id) + 8;
        Map<UUID, Guest> seen = guests(id);
        boolean changed = false;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || Math.abs(p.getX() - v.centre().getX()) > reach || Math.abs(p.getZ() - v.centre().getZ()) > reach) continue;
            changed |= see(seen, p, day);
        }
        if (changed) saveGuests(id, seen);
    }

    /** One player seen about the town today: the day noted, and its dyed leather (the colour it wears most of). */
    private static boolean see(Map<UUID, Guest> seen, Player p, long day) {
        boolean changed = false;
        Guest g = seen.computeIfAbsent(p.getUUID(), k -> new Guest());
        g.name = p.getName().getString();
        if (!g.days.contains(day)) {
            g.days.add(day);
            while (g.days.size() > 10) g.days.remove(0);
            changed = true;
        }
        int[] count = new int[16];
        int pieces = 0;
        boolean chest = false;
        for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            ItemStack s = p.getItemBySlot(slot);
            DyedItemColor c = s.is(ItemTags.DYEABLE) ? s.get(DataComponents.DYED_COLOR) : null;
            if (c == null) continue;
            int dye = Garment.nearest(c.rgb()).getId();
            count[dye] += slot == EquipmentSlot.CHEST ? 2 : 1;
            pieces++;
            if (slot == EquipmentSlot.CHEST) chest = true;
        }
        if (pieces > 0) {
            int best = 0;
            for (int i = 1; i < 16; i++) if (count[i] > count[best]) best = i;
            if (g.colour != best || g.pieces != pieces || g.lastDyed != day) changed = true;
            g.colour = best;
            g.pieces = pieces;
            g.chest = chest;
            g.lastDyed = day;
        }
        return changed;
    }

    static Map<UUID, Guest> guests(UUID village) {
        return GUESTS_SEEN.computeIfAbsent(village, Fashion::loadGuests);
    }

    /**
     * A player gives a folk a garment (FolkTalk.gift): it puts it on there and then, what it had on going to somebody
     * with less, and it thinks the better of the player for it: the more if it is the season's colour, or its own
     * favourite. Its words, or null if this is no garment (the gift goes the usual way).
     */
    @Nullable
    public static String gifted(VillageFolkEntity f, Player p, ItemStack held) {
        Garment g = Garment.of(held);
        UUID id = f.ownerId();
        if (g == null || id == null || f.isBaby() || !(f.level() instanceof ServerLevel level)) return null;
        Villages.Village v = Villages.get(id);
        if (v == null) return null;
        roll(f);
        ItemStack one = held.split(1);
        int colour = Garment.colourOf(one);
        boolean season = colour >= 0 && colour == trendColour(id);
        boolean mine = colour == f.style().main || colour == f.style().accent;
        int delta = season || mine ? 14 : 9;
        String you = p.getName().getString();
        f.persona().feelFor(p.getUUID(), you, delta);
        long day = level.getDayTime() / 24000L;
        f.persona().gotAGift(day, you);
        f.persona().remember(day, you + " gave me " + g.a(colour), delta >= 12 ? 6 : 3);
        wear(level, v, f, one, "given");
        level.sendParticles(delta >= 12 ? net.minecraft.core.particles.ParticleTypes.HEART : net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
            f.getX(), f.getY() + 2.1, f.getZ(), 4, 0.3, 0.2, 0.3, 0.0);
        f.playSound(net.minecraft.sounds.SoundEvents.VILLAGER_YES, 0.8F, 1.1F);
        Standing.stir(id, p.getUUID());
        if (season) return "Oh! " + Garment.colourCap(colour) + " — and " + Garment.colourWord(colour) + "'s all the rage! I'll wear it now. Thank you, " + you + "!";
        if (mine) return g.a(colour).substring(0, 1).toUpperCase(Locale.ROOT) + g.a(colour).substring(1) + " — my own colour! How did you know? I'll put it on now.";
        return "For me? " + g.a(colour).substring(0, 1).toUpperCase(Locale.ROOT) + g.a(colour).substring(1) + ". I'll wear it — thank you, " + you + ".";
    }

    // ------------------------------------------------------------------ what folk say

    /** The folk card's Style line: what it wears, its colours, and how it stands with the season's fashion. */
    public static String cardLine(VillageFolkEntity f) {
        if (f.isBaby()) return "";
        Style s = f.style();
        if (!s.rolled()) return "";
        StringBuilder sb = new StringBuilder();
        String wears = wearing(s);
        sb.append(wears.isEmpty() ? "Nothing of its own over its trade's clothes" : capital(wears));
        sb.append("; its colours ").append(Garment.colourWord(s.main)).append(" and ").append(Garment.colourWord(s.accent)).append(".");
        UUID id = f.ownerId();
        Trend t = id == null ? null : trend(id);
        if (t != null && t.set()) {
            String colour = Garment.colourWord(t.colour);
            if (inFashion(f)) {
                sb.append(f.getUUID().equals(t.setterId) ? " Set this " + seasonWord(id, t.since) + "'s fashion: " + colour + "."
                    : " In fashion: " + colour + (s.followedOn >= 0 ? ", since day " + s.followedOn : "") + ".");
            } else if (s.want != null) {
                sb.append(" Wants ").append(s.want.a(s.wantColour)).append(s.ordered ? ": on the tailor's book" + Tailoring.statusFor(id, f.getUUID()) : "").append(".");
            } else if (traditional(f)) {
                sb.append(" Keeps to its own colours: a Traditionalist.");
            } else {
                sb.append(" Not in ").append(colour).append(" yet (").append((int) Math.round(Math.min(1.0, s.pull) * 100)).append("% come round).");
            }
        }
        if (!s.rosette.isEmpty()) sb.append(" Won the rosette for the best-dressed at ").append(s.rosette).append(".");
        return sb.toString();
    }

    /** "a crimson long coat, a felt hat with a feather and a white scarf". */
    static String wearing(Style s) {
        List<String> out = new ArrayList<>();
        for (Garment.Slot slot : Garment.Slot.values()) {
            Garment g = s.garment(slot);
            if (g == null) continue;
            String w = g.a(s.colour(slot));
            if (slot == Garment.Slot.HEAD && !s.feather.isEmpty() && g == Garment.FELT_HAT) w += " with a feather";
            out.add(w);
        }
        if (out.isEmpty()) return "";
        if (out.size() == 1) return out.get(0);
        return String.join(", ", out.subList(0, out.size() - 1)) + " and " + out.get(out.size() - 1);
    }

    /** "What's in fashion?" in its own words. */
    public static String talk(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return "Fashion? I've only the clothes I came in.";
        if (f.isBaby()) return "Mum says I'll grow out of it before I wear it out.";
        Trend t = trend(id);
        Style s = f.style();
        roll(f);
        if (!t.set()) return "Fashion? We've not got that far. We're a working town just now.";
        String colour = Garment.colourWord(t.colour);
        String season = seasonWord(id, f.level().getDayTime() / 24000L);
        String thing = t.kind == null ? "" : ", " + t.kind.plural() + " especially";
        String who = t.setter.isEmpty() ? "nobody in particular started it" : t.setter + " started it — " + t.why;
        String head = capital(colour) + " is all the rage this " + season + thing + ". " + capital(who) + ".";
        if (f.getUUID().equals(t.setterId)) return "Well, " + colour + "'s the thing this " + season + ", and you could say I started it. "
            + (wearing(s).isEmpty() ? "" : "This is " + wearing(s) + ".");
        if (inFashion(f)) return head + " I've " + wearing(s) + " — what do you think?";
        if (traditional(f)) return capital(colour) + ", they tell me. Not for me: I've worn " + Garment.colourWord(s.main)
            + " all my life, and " + Garment.colourWord(s.main) + " I'll wear.";
        if (s.want != null) {
            String price = "";
            if (f.level() instanceof ServerLevel level) {
                double each = Purchases.priceEach(level, id, s.want.dyed(s.wantColour), f);
                price = String.format(Locale.ROOT, " %s costs %.2f at the shop, and dearer by the day.", capital(s.want.a(s.wantColour)), each);
            }
            return head + (s.ordered ? " I've " + s.want.a(s.wantColour) + " on order with the tailor." : " I'm after " + s.want.a(s.wantColour) + ".") + price;
        }
        if (Wealth.tier(f) == Wealth.Tier.POOR) return head + " Who can afford it? I'll wait for somebody's old one.";
        return head + " I'm not sure it's me. We'll see.";
    }

    /**
     * A word with a friend about what it has on (FolkTalk.smallTalk): something new these two days, or the season's
     * colour on the other. {what it says, what the other says back}, or null.
     */
    @Nullable
    public static String[] smallTalk(VillageFolkEntity a, VillageFolkEntity b) {
        long day = a.level().getDayTime() / 24000L;
        Style s = a.style();
        if (day - s.newOn <= 1 && !s.newWhat.isEmpty() && a.getRandom().nextBoolean()) {
            String colour = s.newWhat.replaceFirst("^an? ", "");
            boolean likes = b.life().affinity(a.getUUID()) >= 0;
            return new String[]{ "Have you seen my new " + colour + ", " + b.displayNameCap() + "?",
                likes ? FolkTalk.pick(b.getRandom(), "Very smart!", "Ooh, " + colour + "! Suits you.", "Now that's the thing this season.")
                    : FolkTalk.pick(b.getRandom(), "Hmph. Bit much, isn't it?", "If you like that sort of thing.") };
        }
        UUID id = a.ownerId();
        Trend t = id == null ? null : trend(id);
        if (t != null && t.set() && inFashion(b) && !inFashion(a) && a.getRandom().nextInt(4) == 0) {
            return new String[]{ "That's a fine bit of " + Garment.colourWord(t.colour) + ", " + b.displayNameCap() + ". Where did you get it?",
                FolkTalk.pick(b.getRandom(), "The tailor's — you should get yourself some.", "The shop had a few. Go on, treat yourself.") };
        }
        return null;
    }

    // ------------------------------------------------------------------ the town's word on it

    /** The board's line: "Fashion: crimson is all the rage this autumn (9 of 14), set by Ada, the leader's partner". */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        Trend t = trend(village);
        if (!t.set()) return null;
        long day = level.getDayTime() / 24000L;
        int[] n = counts(village);
        return "Fashion: " + Garment.colourWord(t.colour) + (t.kind != null ? " " + t.kind.plural() : "") + " all the rage this " + seasonWord(village, day)
            + " (" + n[0] + " of " + n[2] + " wear it)" + (t.setter.isEmpty() ? "" : ", set by " + t.setter + ", " + t.why) + ".";
    }

    /** {wearing the season's look, wearing the last one, grown folk}, now. */
    public static int[] counts(UUID village) {
        Trend t = trend(village);
        int in = 0, last = 0, all = 0;
        for (VillageFolkEntity f : grown(village)) {
            all++;
            if (t.set() && inFashion(f)) in++;
            if (t.lastColour >= 0 && f.style().wears(t.lastColour)) last++;
        }
        return new int[]{ in, last, all };
    }

    /** The gazette's fashion column: the season's look and who set it, who wears it, the tailor's book, the last show. */
    @Nullable
    public static String gazette(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Trend t = trend(id);
        if (!t.set()) return null;
        int[] n = counts(id);
        StringBuilder sb = new StringBuilder("§lFashion§r\n");
        sb.append(capital(Garment.colourWord(t.colour))).append(" is all the rage this ").append(seasonWord(id, day))
            .append(t.setter.isEmpty() ? "" : ", set by " + t.setter + ", " + t.why).append(".");
        sb.append("\n").append(n[0]).append(" of ").append(n[2]).append(" wear it").append(t.kind != null ? "; " + t.kind.plural() + " are the thing" : "").append(".");
        if (t.lastColour >= 0 && n[1] > 0) sb.append(" Still in ").append(Garment.colourWord(t.lastColour)).append(": ").append(n[1]).append(".");
        int orders = Tailoring.book(id).size();
        if (orders > 0) sb.append("\nThe tailor has ").append(orders).append(orders == 1 ? " garment" : " garments").append(" on order.");
        if (t.kind != null) {
            double each = Purchases.priceEach(level, id, t.kind.dyed(t.colour), null);
            int pct = (int) Math.round((PriceIndex.factor(id, t.kind.dyed(t.colour)) - 1.0) * 100);
            sb.append(String.format(Locale.ROOT, "\nA %s %s: %.2fc%s.", Garment.colourWord(t.colour), t.kind.noun, each,
                pct >= 5 ? ", up " + pct + "%" : pct <= -5 ? ", down " + (-pct) + "%" : ""));
        }
        String show = FashionShow.lastLine(id, day);
        if (show != null) sb.append("\n").append(show);
        return sb.toString();
    }

    /** The Fashion page of the town's books (client/FashionPage). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Trend t = trend(id);
        CompoundTag out = new CompoundTag();
        out.putInt("colour", t.colour);
        out.putString("colourWord", t.colour >= 0 ? Garment.colourWord(t.colour) : "");
        out.putString("kind", t.kind == null ? "" : t.kind.plural());
        out.putString("setter", t.setter);
        out.putString("why", t.why);
        out.putBoolean("byPlayer", t.byPlayer);
        out.putLong("since", t.since);
        out.putString("season", seasonWord(id, day));
        out.putInt("lastColour", t.lastColour);
        out.putString("lastWord", t.lastColour >= 0 ? Garment.colourWord(t.lastColour) : "");
        int[] n = counts(id);
        out.putInt("wearing", n[0]);
        out.putInt("wearingLast", n[1]);
        out.putInt("grown", n[2]);
        ListTag days = new ListTag();
        for (long[] d : t.days) {
            CompoundTag c = new CompoundTag();
            c.putLong("day", d[0]);
            c.putInt("in", (int) d[1]);
            c.putInt("last", (int) d[2]);
            c.putInt("all", (int) d[3]);
            days.add(c);
        }
        out.put("days", days);
        // What the town wears, colour by colour (the season's first): everything of its own anybody has on.
        int[] colours = new int[17];
        ListTag folk = new ListTag();
        for (VillageFolkEntity f : grown(id)) {
            Style s = f.style();
            for (Garment.Slot slot : Garment.Slot.values()) {
                int c = s.colour(slot);
                if (c >= 0 && slot != Garment.Slot.RIBBON) colours[Math.min(16, c)]++;
            }
            CompoundTag c = new CompoundTag();
            c.putString("name", f.displayNameCap());
            c.putInt("main", s.main);
            c.putString("wears", wearing(s));
            c.putBoolean("in", inFashion(f));
            c.putBoolean("setter", f.getUUID().equals(t.setterId));
            c.putBoolean("holdout", traditional(f));
            c.putString("want", s.want == null ? "" : s.want.a(s.wantColour) + (s.ordered ? " (on order)" : ""));
            c.putInt("pull", (int) Math.round(Math.min(1.0, s.pull) * 100));
            folk.add(c);
        }
        out.putIntArray("colours", colours);
        out.put("folk", folk);
        ListTag who = new ListTag();
        for (Setter s : setters(level, v, day)) {
            if (who.size() >= 4) break;
            who.add(StringTag.valueOf(String.format(Locale.ROOT, "%s (%s): %s, %.1f", s.name(), s.why(), Garment.colourWord(s.colour()), s.score())));
        }
        out.put("setters", who);
        out.put("orders", Tailoring.report(level, v));
        int[] passed = passedThisWeek(id, day);
        out.putInt("poorBox", passed[0]);
        out.putInt("secondHand", passed[1]);
        String shown = FashionShow.lastEver(id);
        out.putString("show", shown == null ? "" : shown);
        out.putString("nextShow", FashionShow.nextLine(level, id, day));
        if (t.kind != null || t.set()) {
            Garment g = t.kind != null ? t.kind : Garment.WOOL_SCARF;
            ItemStack one = g.dyed(t.colour);
            out.putString("price", String.format(Locale.ROOT, "%s %s: %.2fc, %.0f%% of its usual worth", capital(Garment.colourWord(t.colour)), g.noun,
                Purchases.priceEach(level, id, one, null), PriceIndex.factor(id, one) * 100));
        }
        return out;
    }

    /** /village fashion: the season's look, who set it, who wears it, the tailor's book, the next show. */
    static List<String> page(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        List<String> out = new ArrayList<>();
        Trend t = trend(id);
        out.add("FASHION " + Villages.name(id) + ", " + seasonWord(id, day) + " of year " + Seasons.year(id, day));
        if (!t.set()) out.add("No fashion yet: the town sets one each morning once it has three folk.");
        else {
            int[] n = counts(id);
            out.add("This " + seasonWord(id, day) + ": " + Garment.colourWord(t.colour) + (t.kind == null ? "" : " " + t.kind.plural())
                + (t.setter.isEmpty() ? "" : ", set by " + t.setter + " (" + t.why + ")") + " on day " + t.since + ". " + n[0] + " of " + n[2] + " wear it"
                + (t.lastColour >= 0 ? "; " + n[1] + " still in last season's " + Garment.colourWord(t.lastColour) : "") + ".");
            StringBuilder days = new StringBuilder("Day by day:");
            for (long[] d : t.days) days.append(' ').append(d[0]).append(':').append(d[1]).append('/').append(d[3]);
            out.add(days.toString());
        }
        List<String> who = new ArrayList<>();
        for (Setter s : setters(level, v, day)) {
            if (who.size() >= 4) break;
            who.add(String.format(Locale.ROOT, "%s (%s, %s, %.1f)", s.name(), s.why(), Garment.colourWord(s.colour()), s.score()));
        }
        out.add("The town looks to: " + (who.isEmpty() ? "nobody in particular" : String.join("; ", who)) + ".");
        for (VillageFolkEntity f : grown(id)) out.add(" " + f.displayNameCap() + ": " + cardLine(f));
        out.addAll(Tailoring.lines(level, v));
        int[] passed = passedThisWeek(id, day);
        out.add("This week: " + passed[0] + " old things through the poor box, " + passed[1] + " sold second-hand.");
        String show = FashionShow.lastEver(id);
        if (show != null) out.add(show);
        out.add(FashionShow.nextLine(level, id, day));
        return out;
    }

    /** What the clothes on its back are worth (Wealth.belongings): its own, at the price list's worth. */
    public static double wornWorth(VillageFolkEntity f) {
        double sum = 0;
        Style s = f.style();
        for (ItemStack st : s.worn) if (!st.isEmpty()) sum += Prices.of(st) * (secondHand(st) ? 0.34 : 1.0);
        return sum;
    }

    /** It has died: what it wore falls where it fell, as its pack does, for the sweeper or the finder. */
    public static void died(VillageFolkEntity f) {
        Style s = f.style();
        for (int i = 0; i < s.worn.length; i++) {
            if (!s.worn[i].isEmpty()) f.spawnAtLocation(s.worn[i].copy());
            s.worn[i] = ItemStack.EMPTY;
        }
        if (!s.feather.isEmpty()) f.spawnAtLocation(s.feather.copy());
        s.feather = ItemStack.EMPTY;
        if (f.ownerId() != null) Tailoring.done(f.ownerId(), f.getUUID());
    }

    // ------------------------------------------------------------------ the price list and the store

    /**
     * A garment's worth (Prices): its cloth, as its recipe prices it (the wool, the leather, the string, the gold), a
     * quarter more and a little for the tailor's cutting and stitching, and a dye's worth for the colour it takes.
     */
    public static double worth(Garment g, double cloth, double dye) {
        return cloth * 1.25 + 0.15 + (g.dyeable() ? dye : 0.0);
    }

    /**
     * The garments the store keeps (StockKeeper.range): every one the town's age allows, the season's thing (or a scarf,
     * when the season names none) opened with a couple in the season's colour. Never the crafters' to make: the tailor
     * dyes them at the loom (Tailoring); the stock keeper fetches them from the stores.
     */
    static Map<String, StockKeeper.Ware> wares(ServerLevel level, Villages.Village v) {
        Map<String, StockKeeper.Ware> out = new LinkedHashMap<>();
        Trend t = trend(v.id());
        Villages.Age age = Villages.ageOf(v.id());
        for (Garment g : Garment.values()) {
            if (g == Garment.ROSETTE || g.age.ordinal() > age.ordinal()) continue;
            ItemStack sample = t.set() && g.dyeable() ? g.dyed(t.colour) : new ItemStack(g.item());
            String key = Stockroom.key(sample);
            net.minecraft.world.item.Item it = g.item();
            int opening = t.set() && (t.kind == g || t.kind == null && g == Garment.WOOL_SCARF) ? 2 : 0;
            out.put(key, new StockKeeper.Ware(key, s -> s.is(it), sample, false, null, 1, opening));
        }
        return out;
    }

    // ------------------------------------------------------------------ the town's folk

    /** The town's grown folk, as they stand (never a visitor, a showcase or a child). */
    static List<VillageFolkEntity> grown(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isBaby() && !f.isShowcase() && !Visitors.is(f)) out.add(f);
        }
        return out;
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ kept with the folk and the town

    /** Into the folk's save (VillageFolkEntity.addAdditionalSaveData). */
    public static void save(VillageFolkEntity f, CompoundTag tag) {
        if (f.style().rolled() || f.style().count() > 0) tag.put("Fashion", f.style().save(f.registryAccess()));
    }

    /** Out of the folk's save. */
    public static void load(VillageFolkEntity f, CompoundTag tag) {
        if (tag.contains("Fashion")) f.style().load(tag.getCompound("Fashion"), f.registryAccess());
    }

    private static void save(UUID village, Trend t) {
        if (!t.set()) return;
        Ledger.note(village, NOTE, String.join("|", Integer.toString(t.colour), t.kind == null ? "" : t.kind.name(), t.setter.replace('|', '/'),
            t.setterId == null ? "" : t.setterId.toString(), t.why.replace('|', '/'), t.byPlayer ? "1" : "0", Long.toString(t.since),
            Integer.toString(t.season), Integer.toString(t.lastColour), t.lastKind == null ? "" : t.lastKind.name(), Long.toString(t.lastSince),
            t.toldMost ? "1" : "0"));
        StringBuilder sb = new StringBuilder();
        for (long[] d : t.days) sb.append(sb.length() == 0 ? "" : ",").append(d[0]).append(':').append(d[1]).append(':').append(d[2]).append(':').append(d[3]);
        Ledger.note(village, DAYS, sb.toString());
    }

    private static Trend load(UUID village) {
        Trend t = new Trend();
        String s = Ledger.note(village, NOTE);
        if (s == null || s.isEmpty()) return t;
        try {
            String[] p = s.split("\\|", -1);
            t.colour = Integer.parseInt(p[0]);
            t.kind = p[1].isEmpty() ? null : Garment.valueOf(p[1]);
            t.setter = p[2];
            t.setterId = p[3].isEmpty() ? null : UUID.fromString(p[3]);
            t.why = p[4];
            t.byPlayer = p[5].equals("1");
            t.since = Long.parseLong(p[6]);
            t.season = Integer.parseInt(p[7]);
            t.lastColour = Integer.parseInt(p[8]);
            t.lastKind = p[9].isEmpty() ? null : Garment.valueOf(p[9]);
            t.lastSince = Long.parseLong(p[10]);
            t.toldMost = p.length > 11 && p[11].equals("1");
            String days = Ledger.note(village, DAYS);
            if (days != null && !days.isEmpty()) {
                for (String d : days.split(",")) {
                    String[] q = d.split(":");
                    t.days.add(new long[]{ Long.parseLong(q[0]), Long.parseLong(q[1]), Long.parseLong(q[2]), Long.parseLong(q[3]) });
                }
            }
        } catch (RuntimeException e) {
            return new Trend();
        }
        return t;
    }

    private static void saveGuests(UUID village, Map<UUID, Guest> seen) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Guest> e : seen.entrySet()) {
            Guest g = e.getValue();
            StringBuilder days = new StringBuilder();
            for (long d : g.days) days.append(days.length() == 0 ? "" : ".").append(d);
            sb.append(sb.length() == 0 ? "" : ";").append(e.getKey()).append(',').append(g.name.replace(',', ' ').replace(';', ' ')).append(',')
                .append(days).append(',').append(g.colour).append(',').append(g.pieces).append(',').append(g.chest ? 1 : 0).append(',').append(g.lastDyed);
        }
        Ledger.note(village, GUESTS, sb.toString());
    }

    private static Map<UUID, Guest> loadGuests(UUID village) {
        Map<UUID, Guest> out = new LinkedHashMap<>();
        String s = Ledger.note(village, GUESTS);
        if (s == null || s.isEmpty()) return new ConcurrentHashMap<>(out);
        for (String one : s.split(";")) {
            try {
                String[] p = one.split(",", -1);
                Guest g = new Guest();
                g.name = p[1];
                if (!p[2].isEmpty()) for (String d : p[2].split("\\.")) g.days.add(Long.parseLong(d));
                g.colour = Integer.parseInt(p[3]);
                g.pieces = Integer.parseInt(p[4]);
                g.chest = p[5].equals("1");
                g.lastDyed = Long.parseLong(p[6]);
                out.put(UUID.fromString(p[0]), g);
            } catch (RuntimeException e) {
                // A guest the town cannot read back is forgotten.
            }
        }
        return new ConcurrentHashMap<>(out);
    }

    // ------------------------------------------------------------------ the commands

    /**
     * /village fashion: the season's look and who set it, everybody's style, the tailor's book, the shows. {@code books}
     * opens the town's books at the Fashion page. Operators: {@code now} goes round a day of it now (the season's look
     * set if there is none, a day's coming round, the tailor's turns, and every folk that wants something in stock off
     * to buy it); {@code set <colour> [garment]} makes a colour the season's look; {@code show} holds the fashion show
     * now; {@code stage} stands a crowd up on the spot, most in the season's colour and a few holding out, and the
     * tailor at a loom, for the pictures.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("fashion")
            .executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                String text = String.join("\n", page(ctx.getSource().getLevel(), v));
                ctx.getSource().sendSuccess(() -> Component.literal(text), false);
                return 1;
            })
            .then(Commands.literal("books").executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
                CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
                books.putString("page", "Fashion");
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
                return 1;
            }))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                List<String> said = dayNow(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal("FASHION NOW " + String.join("; ", said)), false);
                return said.size();
            }))
            .then(Commands.literal("set").requires(src -> src.hasPermission(2))
                .then(Commands.argument("colour", StringArgumentType.word())
                    .executes(ctx -> setCmd(ctx, StringArgumentType.getString(ctx, "colour"), ""))
                    .then(Commands.argument("garment", StringArgumentType.word())
                        .executes(ctx -> setCmd(ctx, StringArgumentType.getString(ctx, "colour"), StringArgumentType.getString(ctx, "garment"))))))
            .then(Commands.literal("show").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                String said = FashionShow.holdNow(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal("FASHION SHOW " + said), false);
                return 1;
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                List<String> out = FashionShow.stage(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }));
    }

    private static int setCmd(CommandContext<CommandSourceStack> ctx, String colour, String garment) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        DyeColor c = DyeColor.byName(colour.toLowerCase(Locale.ROOT), null);
        if (c == null) {
            for (DyeColor d : DyeColor.values()) if (Garment.colourWord(d.getId()).replace(" ", "_").equalsIgnoreCase(colour)) c = d;
        }
        if (c == null) {
            ctx.getSource().sendFailure(Component.literal("No such colour: " + colour));
            return 0;
        }
        Garment g = null;
        if (!garment.isEmpty()) {
            for (Garment x : Garment.values()) if (x.id.equalsIgnoreCase(garment) || x.name().equalsIgnoreCase(garment)) g = x;
        }
        ServerPlayer p = ctx.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
        setTrend(ctx.getSource().getLevel(), v, c.getId(), g, p == null ? "the operator" : p.getName().getString(), "by order");
        String said = boardLine(ctx.getSource().getLevel(), v.id());
        ctx.getSource().sendSuccess(() -> Component.literal("FASHION SET " + said), false);
        return 1;
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    /**
     * A day of the fashion, now (/village fashion now; the tests and the pictures): the season's look set if there is
     * none, a day's coming round, the tailor's turns at the book (a few pieces), and every folk that wants something
     * the town now has buying it at the counter. What happened, in a few words each.
     */
    public static List<String> dayNow(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        List<String> said = new ArrayList<>();
        Trend t = trend(id);
        if (!t.set()) newTrend(level, v, day, seasonOf(id, day));
        // A day's going round: today's if it has not had one, else the next (it is a day of it, now).
        long next = t.days.isEmpty() ? day : Math.max(day, t.days.get(t.days.size() - 1)[0] + 1);
        spread(level, v, next);
        for (VillageFolkEntity f : grown(id)) look(f);
        for (VillageFolkEntity f : grown(id)) {
            if (f.stationTask() != AssistantEntity.StationTask.TAILOR) continue;
            for (int i = 0; i < 4; i++) {
                String made = Tailoring.work(level, v, f, null);
                if (made == null) break;
                said.add(f.displayNameCap() + " made " + made);
            }
        }
        for (VillageFolkEntity f : grown(id)) {
            Style s = f.style();
            if (s.want == null) continue;
            ItemStack got = buy(level, v, f, wanted(s));
            if (got.isEmpty() && Wealth.tier(f).ordinal() <= Wealth.Tier.GETTING_BY.ordinal()) got = buySecondHand(level, v, f, secondHandOf(s.want.slot, s.wantColour));
            if (!got.isEmpty()) {
                wear(level, v, f, got, "bought");
                said.add(f.displayNameCap() + " bought " + Garment.of(got).a(Garment.colourOf(got)));
            }
        }
        save(id, t);
        int[] n = counts(id);
        said.add(n[0] + " of " + n[2] + " in " + (t.set() ? Garment.colourWord(t.colour) : "nothing"));
        return said;
    }

    /** The season's look set by hand (the command, the tests): this colour, as this thing, by this one. */
    public static void setTrend(ServerLevel level, Villages.Village v, int colour, @Nullable Garment kind, String by, String why) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Trend t = trend(id);
        if (t.set() && t.colour != colour) {
            t.lastColour = t.colour;
            t.lastKind = t.kind;
            t.lastSince = t.since;
        }
        t.colour = colour;
        t.kind = kind;
        t.setter = by;
        t.setterId = null;
        t.why = why;
        t.byPlayer = false;
        t.since = day;
        t.season = seasonOf(id, day);
        t.toldMost = false;
        t.days.clear();
        save(id, t);
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: the season's look chosen now, as a season's turn would choose it. */
    public static void newTrendForTests(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        newTrend(level, v, day, seasonOf(v.id(), day));
    }

    /** Tests: a day of the fashion's going round, on this day. */
    public static void spreadForTests(ServerLevel level, Villages.Village v, long day) {
        spread(level, v, day);
    }

    /** Tests: this folk's own colours set. */
    public static void coloursForTests(VillageFolkEntity f, DyeColor main, DyeColor accent) {
        f.style().main = main.getId();
        f.style().accent = accent.getId();
    }

    /** Tests: this put on, as if bought. */
    public static void wearForTests(ServerLevel level, VillageFolkEntity f, ItemStack thing) {
        Villages.Village v = Villages.get(f.ownerId());
        if (v != null) wear(level, v, f, thing, "bought");
    }

    /** Tests: at the counter now, for what it wants. What it came away with (and put on), or nothing. */
    public static ItemStack buyForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = Villages.get(f.ownerId());
        Style s = f.style();
        if (v == null || s.want == null) return ItemStack.EMPTY;
        ItemStack got = buy(level, v, f, wanted(s));
        if (!got.isEmpty()) wear(level, v, f, got, "bought");
        return got;
    }

    /** Tests: what the season's setters are, the first first: "name|why|colour|score". */
    public static List<String> settersForTests(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (Setter s : setters(level, v, level.getDayTime() / 24000L)) out.add(s.name() + "|" + s.why() + "|" + s.colour() + "|" + s.score());
        return out;
    }

    /** Tests: a player seen about the town today in what it has on, as the town's look round would see it. */
    public static void seenForTests(ServerLevel level, Villages.Village v, Player p) {
        Map<UUID, Guest> seen = guests(v.id());
        see(seen, p, level.getDayTime() / 24000L);
        saveGuests(v.id(), seen);
    }

    /** Tests: this folk come round to the season's look now: it wants it (the thing it runs to, in the colour). */
    public static void wantForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = Villages.get(f.ownerId());
        Trend t = v == null ? null : trend(v.id());
        if (t != null && t.set()) want(level, v, f, t, level.getDayTime() / 24000L, false);
    }

    /** Tests: this folk's pull toward the season's look so far (one: it wants it). */
    public static double pullForTests(VillageFolkEntity f) {
        return f.style().pull;
    }

    /** Tests: what this folk loves to be given (wool, gems or gold: a little vain). */
    public static void lovesForTests(VillageFolkEntity f, Persona.Gift g) {
        f.ensurePersona();
        f.persona().loves = g;
    }

    /** Tests: how this folk feels about another, set outright (feelings it came with put aside). */
    public static void feelForTests(VillageFolkEntity f, VillageFolkEntity other, int affinity) {
        f.life().feel(other.getUUID(), other.displayNameCap(), affinity - f.life().affinity(other.getUUID()));
    }

    /** Tests: the client's number for this folk, as it would be sent now. */
    public static long packedForTests(VillageFolkEntity f) {
        return f.style().pack(inFashion(f));
    }
}
