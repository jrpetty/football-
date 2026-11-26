package com.jrpetty.mcassistant.entity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpa] Jury duty: a citizen puts its name on the jury roll at the Town Hall, and when the council sits on a case (Trial)
 * up to three on the roll who are in the town are called. Each hears the charge, the watch's evidence and the
 * witnesses on its Town Hall page and gives its verdict, guilty or not; the council waits for the jury's word (two
 * minutes at most) before it gives its own, and each juror's vote counts as a councillor's. Serving is warmly seen.
 */
public final class Jury {

    private Jury() {}

    /** Most jurors on a case; how long the court waits on them once it has heard everything (ticks). */
    public static final int MOST = 3, WAIT = 2400;

    /** Case to its jurors (player to vote: null not yet, true guilty, false not guilty). */
    private static final Map<Integer, Map<UUID, Boolean>> SEATS = new ConcurrentHashMap<>();
    /** Case to the town it is in, and where the court sits. */
    private static final Map<Integer, Object[]> WHERE = new ConcurrentHashMap<>();

    /** The court has opened on a case: the jurors called. Returns how many. */
    static int summon(ServerLevel level, Villages.Village v, Crime.Case c, BlockPos court, boolean hall) {
        Map<UUID, Boolean> seats = new LinkedHashMap<>();
        for (ServerPlayer p : level.players()) {
            if (seats.size() >= MOST) break;
            if (!Belonging.onJuryRoll(v.id(), p.getUUID()) || !Citizens.is(v.id(), p.getUUID())) continue;
            if (p.blockPosition().distSqr(v.centre()) > 4.0 * Villages.VILLAGE_RANGE * Villages.VILLAGE_RANGE) continue;
            seats.put(p.getUUID(), null);
        }
        if (seats.isEmpty()) return 0;
        SEATS.put(c.id, seats);
        WHERE.put(c.id, new Object[]{ v.id(), court.immutable() });
        long now = level.getDayTime();
        for (UUID u : seats.keySet()) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(u);
            if (p == null) continue;
            p.sendSystemMessage(Component.literal("Jury duty: the council sits " + (hall ? "at the hall" : "on the square") + " on the case against "
                + c.accusedName + " (" + c.kind.word + "). Hear it and give your verdict: U, then the Town Hall, Duties.").withStyle(ChatFormatting.GOLD));
            Planner.add(p, now, "jury", "Jury duty: " + c.accusedName + ", " + c.kind.word, court);
        }
        return seats.size();
    }

    public static boolean seated(int caseId) {
        Map<UUID, Boolean> s = SEATS.get(caseId);
        return s != null && !s.isEmpty();
    }

    /** Has every juror given its word? */
    public static boolean ready(int caseId) {
        Map<UUID, Boolean> s = SEATS.get(caseId);
        if (s == null) return true;
        for (Boolean b : s.values()) if (b == null) return false;
        return true;
    }

    /** The jury's votes: {guilty, not guilty}. */
    public static int[] tally(int caseId) {
        int g = 0, n = 0;
        Map<UUID, Boolean> s = SEATS.get(caseId);
        if (s != null) for (Boolean b : s.values()) {
            if (b == null) continue;
            if (b) g++; else n++;
        }
        return new int[]{ g, n };
    }

    /** The case this player is called to and hasn't yet given its verdict on, or -1. */
    public static int calledTo(UUID player) {
        for (Map.Entry<Integer, Map<UUID, Boolean>> e : SEATS.entrySet()) {
            if (e.getValue().containsKey(player) && e.getValue().get(player) == null) return e.getKey();
        }
        return -1;
    }

    /** The case this player sits on (voted or not), or -1. */
    public static int sittingOn(UUID player) {
        for (Map.Entry<Integer, Map<UUID, Boolean>> e : SEATS.entrySet()) if (e.getValue().containsKey(player)) return e.getKey();
        return -1;
    }

    /** Where the court sits for this case, or null. */
    @Nullable
    public static BlockPos court(int caseId) {
        Object[] w = WHERE.get(caseId);
        return w == null ? null : (BlockPos) w[1];
    }

    /** A juror's verdict. Returns what happened, "!" first for a refusal. */
    public static String vote(ServerPlayer p, int caseId, boolean guilty) {
        Map<UUID, Boolean> s = SEATS.get(caseId);
        if (s == null || !s.containsKey(p.getUUID())) return "!You're not on the jury for that case.";
        if (s.get(p.getUUID()) != null) return "!You've given your verdict already: " + (s.get(p.getUUID()) ? "guilty" : "not guilty") + ".";
        s.put(p.getUUID(), guilty);
        int left = 0;
        for (Boolean b : s.values()) if (b == null) left++;
        return "Your verdict is in: " + (guilty ? "guilty" : "not guilty") + ". " + (left == 0 ? "The council gives its word once it has heard the case."
            : left + " more on the jury to give theirs; the council waits a little for them.");
    }

    /** What a juror hears: the charge, the evidence and the witnesses. */
    public static List<String> hearing(int caseId) {
        List<String> out = new ArrayList<>();
        Crime.Case c = Crime.get(caseId);
        if (c == null) return out;
        out.add("The charge: " + c.kind.word + " at " + c.place + " on day " + c.day + ". " + Character.toUpperCase(c.what().charAt(0)) + c.what().substring(1) + ".");
        out.add("The accused: " + c.accusedName + ".");
        UUID accused = c.accused;
        int shown = 0;
        for (Crime.Clue k : c.clues) {
            if (shown >= 3 || accused == null || !k.points.contains(accused)) continue;
            out.add("The watch found: " + k.text());
            shown++;
        }
        if (shown == 0) out.add("The watch found little: the evidence is thin.");
        int said = 0;
        for (Crime.Statement st : c.statements) {
            if (said >= 3) break;
            out.add((st.player ? st.fromName + " told the watch" : st.fromName + " says") + ": \"" + st.text + "\"");
            said++;
        }
        if (said == 0) out.add("No witness has come forward.");
        return out;
    }

    /** Cases the court has heard and now waits on the jury for. */
    private static final java.util.Set<Integer> WAITING = ConcurrentHashMap.newKeySet();

    /** The court has heard the case and waits on the jury: told to the jurors who haven't given their word. */
    static void heard(int caseId) {
        WAITING.add(caseId);
        Map<UUID, Boolean> s = SEATS.get(caseId);
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (s == null || server == null) return;
        for (Map.Entry<UUID, Boolean> e : s.entrySet()) {
            if (e.getValue() != null) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) p.sendSystemMessage(Component.literal("The court has heard the case and waits on the jury: give your verdict on the Town Hall's Duties tab.")
                .withStyle(ChatFormatting.GOLD));
        }
    }

    /** Is the court waiting on this case's jury now? */
    public static boolean waiting(int caseId) {
        return WAITING.contains(caseId);
    }

    /** The verdict given: the jury thanked and stood down. */
    static void close(ServerLevel level, int caseId, boolean guilty) {
        Map<UUID, Boolean> s = SEATS.remove(caseId);
        Object[] w = WHERE.remove(caseId);
        WAITING.remove(caseId);
        if (s == null || w == null) return;
        UUID village = (UUID) w[0];
        for (Map.Entry<UUID, Boolean> e : s.entrySet()) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            if (e.getValue() != null) {
                for (AssistantEntity a : Villages.folkOf(village)) {
                    if (a instanceof VillageFolkEntity f && f.persona().knows(p.getUUID())) f.persona().feelFor(p.getUUID(), p.getGameProfile().getName(), 1);
                }
                Standing.stir(village, p.getUUID());
            }
            p.sendSystemMessage(Component.literal("The court has given its verdict: " + (guilty ? "guilty" : "not guilty") + ". "
                + (e.getValue() == null ? "The council went on without your word." : "Thank you for serving on the jury.")).withStyle(ChatFormatting.GOLD));
        }
    }

    /** Tests: seat this player on this case. */
    public static void seatForTests(int caseId, UUID village, UUID player, BlockPos court) {
        SEATS.computeIfAbsent(caseId, k -> new LinkedHashMap<>()).put(player, null);
        WHERE.put(caseId, new Object[]{ village, court });
    }

    public static void resetForTests() {
        SEATS.clear();
        WHERE.clear();
        WAITING.clear();
    }
}
