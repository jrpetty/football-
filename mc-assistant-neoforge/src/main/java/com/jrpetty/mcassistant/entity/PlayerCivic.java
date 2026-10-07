package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.net.CivicPagePayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [player-civic] A player in a town's civic life: standing for leader and leading (Hustings, Pledges, PlayerLeader),
 * and learning a trade from a master (PlayerTrades, Lessons, TradeGoods). The few places the rest of the mod calls
 * in come through here: what a folk makes of a player's words on these things, the town's look at it all every few
 * seconds, the title over a player's name, and the pages a player reads (CivicPagePayload).
 */
public final class PlayerCivic {

    private PlayerCivic() {}

    /** The last game tick each town was looked at. */
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** The day each town's young apprentices last had their journals seen to. */
    private static final Map<UUID, Long> JOURNALS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKED.clear();
        JOURNALS.clear();
        Hustings.resetForTests();
        PlayerTrades.resetForTests();
        TradeGoods.resetForTests();
    }

    // ------------------------------------------------------------------ talk

    /**
     * What a folk says to a player's words on the election, the leader's work or an apprenticeship (FolkTalk.answer,
     * before its own topics), or null if the words are about none of these.
     */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, TalkTopic topic, String text) {
        if (text == null || text.isBlank()) return null;
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ").replaceAll("\\s+", " ") + " ";
        // Standing for leader, and the campaign.
        if (has(t, " coin", " coins", " pay you", "bribe", " buy your vote") && has(t, " vote")) return Hustings.bribe(f, p, t);
        if (has(t, "stand for election", "stand for leader", "stand in the election", "stand for elder", "stand for mayor", "stand for thane",
                "run for leader", "run for election", "run for office", "put my name forward", "i'll stand", "ill stand", "stand as candidate",
                "be a candidate", "i want to lead", "i'd like to lead", "id like to lead")) return Hustings.stand(f, p);
        if (has(t, "i promise", "i pledge", "my promise is", "i'll promise", "ill promise")) return Hustings.promise(f, p, t);
        if (has(t, "vote for me", "your vote", "support me", "back me", "my campaign")) return Hustings.canvass(f, p);
        if (has(t, "make a speech", "give a speech", "my speech", "a speech") && f.level() instanceof ServerLevel level) {
            return "You make a speech, then: " + Hustings.speech(level, p);
        }
        if (has(t, "withdraw from the election", "i withdraw", "won't stand", "wont stand", "not stand again")
                && f.ownerId() != null && f.level() instanceof ServerLevel level) {
            return Hustings.withdraw(level, f.ownerId(), p.getUUID(), p.getName().getString());
        }
        if (has(t, "leader's page", "leaders page", "my office", "my powers", "as your leader", "the leader's book", "hustings")
                && p instanceof ServerPlayer sp) {
            PlayerLeader.openPage(sp);
            return "Here's how things stand.";
        }
        // Learning a trade.
        if (has(t, "apprentice", "take me on", "teach me your trade", "learn your trade", "teach me the trade", "train me")) {
            if (has(t, "my lesson", "next lesson") || isApprenticeOf(f, p)) return PlayerTrades.lesson(f, p);
            return PlayerTrades.ask(f, p);
        }
        if (has(t, "my lesson", "next lesson", "the lesson", "lesson done", "my progress", "graduation", "my piece", "here's the",
                "heres the", "i brought the") && isApprenticeOf(f, p)) return PlayerTrades.lesson(f, p);
        if (has(t, "my lesson", "next lesson", "my trades", "my journal") && p instanceof ServerPlayer sp) {
            PlayerTrades.openPage(sp);
            return "Your journal has it all: who you're learning from, and the next lesson.";
        }
        return null;
    }

    static boolean isApprenticeOf(VillageFolkEntity f, Player p) {
        for (PlayerTrades.Course c : PlayerTrades.mine(p.getUUID())) if (f.getUUID().equals(c.master)) return true;
        return false;
    }

    private static boolean has(String text, String... words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    // ------------------------------------------------------------------ the town's look

    /** Every few seconds for each town (a folk's agenda): the campaign, the leader's morning, the young apprentices' journals. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = LOOKED.get(id);
        if (last != null && now - last < 200L && now >= last) return;
        LOOKED.put(id, now);
        long day = Civics.day(level), t = level.getDayTime() % 24000L;
        Hustings.tick(level, v, day, t);
        PlayerLeader.tick(level, v, day, t);
        if (t >= 2000L && JOURNALS.getOrDefault(id, -1L) != day) {
            JOURNALS.put(id, day);
            TradeGoods.journals(level, v, day);
        }
    }

    // ------------------------------------------------------------------ the title

    /** The title over a player's name (Citizens.best): the office it holds first, and the trade it has made its own. */
    @Nullable
    public static String titled(ServerPlayer p, @Nullable String best) {
        UUID town = PlayerLeader.townLedBy(p.getUUID());
        String office = town == null ? null : Homeland.leaderTitle(town).substring(0, 1).toUpperCase(Locale.ROOT)
            + Homeland.leaderTitle(town).substring(1) + " of " + Villages.name(town);
        String base = office != null ? office : best;
        String trade = PlayerTrades.title(p.getUUID());
        if (trade == null) return base;
        return base == null ? trade : base + " · " + trade;
    }

    // ------------------------------------------------------------------ pages

    /** A page for a player (the Leader's page, the hustings, the trades): its words, and buttons that run commands. */
    public static void send(ServerPlayer p, String title, String text, List<String> buttons) {
        PacketDistributor.sendToPlayer(p, new CivicPagePayload(title, text.length() > 30000 ? text.substring(0, 30000) : text,
            buttons.size() > 24 ? buttons.subList(0, 24) : buttons));
    }
}
