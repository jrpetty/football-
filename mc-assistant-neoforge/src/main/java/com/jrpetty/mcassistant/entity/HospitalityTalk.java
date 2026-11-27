package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * [roles-c] What a player can ask of the town's food and hospitality posts, each of its own work (the talk menu's
 * buttons for them, client/TalkMenu, and the same words typed):
 * <ul>
 * <li><b>The innkeeper</b> (or whoever keeps the inn): a room for the night (Inn.let: its bed till morning, its share of
 *     the fee). Asked of anybody else in a town with an inn, it says who keeps it.</li>
 * <li><b>The barkeep</b>: a pint (one of the café's drinks out of the stores, Tavern.pint), a round for the house when
 *     it is behind its bar (Tavern.round), and the player's empty bottles taken back, a coin for every three
 *     (Tavern.bottlesBack). Asked of anybody else in a town with a barkeep, it says who.</li>
 * <li><b>The baker</b>: fresh bread, cookies, a pie or a cake out of the stores, at the town's price.</li>
 * <li><b>The miller</b>: a sack of the mill's own wheat (Windmill.sellSack), never the bakery's two sacks.</li>
 * <li><b>The rancher</b>, at its butcher's block: meat of the herd's cull out of the stores.</li>
 * </ul>
 * Everything sold comes out of the stores (or the mill) at the town's price, into its treasury, booked as a sale; what
 * the town cannot spare it says so. Somebody the town has turned out is sold nothing (a room is the inn's own to refuse).
 *
 * <p>Cost: nothing ticks; a reply reads the town's books once.
 */
public final class HospitalityTalk {

    private HospitalityTalk() {}

    /** What a player is asking of a hospitality post. */
    public enum Ask { ROOM, PINT, ROUND, BOTTLES, BREAD, SACK, MEAT }

    /** The bakery's goods, by the lot of four. */
    static final OutdoorTalk.Wares BAKED = new OutdoorTalk.Wares("bread", List.of(Items.BREAD, Items.COOKIE, Items.PUMPKIN_PIE, Items.CAKE), 4);
    /** The herd's cull, dressed at the butcher's block, by the lot of eight. */
    static final OutdoorTalk.Wares MEAT = new OutdoorTalk.Wares("meat", List.of(Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN,
        Items.RABBIT), 8);

    private static final Pattern DIGITS = Pattern.compile("\\b\\d{1,4}\\b");

    private static boolean has(String t, String... words) {
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    /** What these words ask of a hospitality post, or null if they ask nothing of one. Pure: the tests check every menu
     *  phrase is understood. */
    @Nullable
    public static Ask ask(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (has(t, "room for the night", "a room for tonight", "bed for the night", "stay the night", "room at the inn", "night at the inn",
            "rent a room", "take a room")) return Ask.ROOM;
        if (has(t, "round for", "buy a round", "a round on me", "drinks all round", "drinks on me")) return Ask.ROUND;
        if (has(t, "empty bottles", "bottles back", "my bottles", "return the bottles", "bring back the bottles")) return Ask.BOTTLES;
        if (has(t, "a pint", "pour me", "a drink, please", "a drink please", "something to drink", "a glass of")) return Ask.PINT;
        if (has(t, "sack of wheat", "wheat from the mill", "the mill's wheat", "buy wheat", "buy some wheat")) return Ask.SACK;
        if (has(t, "fresh bread", "a loaf", "some bread", "buy bread", "from the oven", "your bread", "a cake", "a pie", "some cookies")) {
            return Ask.BREAD;
        }
        if (has(t, "some meat", "buy meat", "the butcher", "raw meat", "some beef", "some pork", "some mutton", "chops")) return Ask.MEAT;
        return null;
    }

    /**
     * Said to a folk of the town about a room, a drink, bread, the mill's wheat or meat: the answer, and whatever comes of
     * it. Null when the words ask nothing of hospitality, or of this folk and nobody else of its town (the rest of the
     * talk hears them).
     */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, String text) {
        if (f.isBaby() || !(f.level() instanceof ServerLevel level) || f.ownerId() == null) return null;
        Ask a = ask(text);
        if (a == null) return null;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return null;
        if (a != Ask.ROOM && a != Ask.BOTTLES && shunned(level, v.id(), p) && answers(f, a)) return "Not for you. Not after what you've done.";
        return switch (a) {
            case ROOM -> room(level, v, f, p);
            case PINT, ROUND, BOTTLES -> bar(level, v, f, p, a);
            case BREAD -> Hospitality.role(f) == Hospitality.Role.BAKER
                ? OutdoorTalk.sell(level, v, f, p, text, BAKED, "out of the oven this morning") : null;
            case SACK -> Windmill.isMiller(f) ? Windmill.sellSack(level, v, f, p, DIGITS.matcher(text).find() ? Services.countIn(text)
                : Windmill.PLAYERS_SACK) : null;
            case MEAT -> f.stationTask() == StationTask.RANCH
                ? OutdoorTalk.sell(level, v, f, p, text, MEAT, "dressed at the butcher's block from our own herd's cull") : null;
        };
    }

    /** Is this folk the one to answer this ask itself (not to send the player on)? */
    private static boolean answers(VillageFolkEntity f, Ask a) {
        return switch (a) {
            case PINT, ROUND, BOTTLES -> Hospitality.role(f) == Hospitality.Role.BARKEEP;
            case BREAD -> Hospitality.role(f) == Hospitality.Role.BAKER;
            case SACK -> Windmill.isMiller(f);
            case MEAT -> f.stationTask() == StationTask.RANCH;
            case ROOM -> true;
        };
    }

    private static boolean shunned(ServerLevel level, UUID village, Player p) {
        Standing.Title title = Standing.of(village, p.getUUID(), level.getGameTime()).title();
        return title == Standing.Title.OUTCAST || Laws.banished(village, p.getUUID(), level.getDayTime() / 24000L);
    }

    /** A room: let by the inn's keeper; anybody else says who keeps it. Null in a town with no inn. */
    @Nullable
    static String room(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p) {
        if (Inn.inn(v.id()) == null) return null;
        VillageFolkEntity keeper = Inn.keeper(v.id());
        if (keeper == f) return Inn.let(level, v, p).said();
        return keeper == null ? "The inn's shut: there's nobody to keep it." : "You'll want " + keeper.displayNameCap()
            + ": " + (keeper.isSleeping() ? "asleep now, but " : "") + "it keeps the inn. Ask it for a room, or ring at the inn's sign.";
    }

    /** The bar: the barkeep pours, takes a round's order at its bar, takes the bottles back; anybody else says who.
     *  Null in a town with no tavern. */
    @Nullable
    static String bar(ServerLevel level, Villages.Village v, VillageFolkEntity f, Player p, Ask a) {
        Ledger.Building tav = Tavern.of(v.id());
        if (tav == null) return null;
        if (Hospitality.role(f) != Hospitality.Role.BARKEEP) {
            VillageFolkEntity barkeep = Hospitality.first(v.id(), Hospitality.Role.BARKEEP);
            return barkeep == null ? null : "That's for " + barkeep.displayNameCap() + ", behind the tavern's bar.";
        }
        return switch (a) {
            case PINT -> Tavern.pint(level, v, p, null, f).said();
            case BOTTLES -> Tavern.bottlesBack(level, v, p, f).said();
            default -> Tavern.barkeep(level, v.id(), tav) != f ? "Come in to the tavern and I'll pour it."
                : Tavern.round(level, v, p);
        };
    }
}
