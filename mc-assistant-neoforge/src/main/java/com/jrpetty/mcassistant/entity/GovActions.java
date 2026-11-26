package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.net.GovNetwork;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;

/**
 * [featcivic] What a player does from the Town Hall and the ballot screen (client/GovScreen), on the server: vote, stand,
 * promise, withdraw, make a speech, sign the petition, and be shown the polling station or the speech spot. Every action
 * answers with the page again and a line on its foot: what happened, why, and what comes next.
 */
public final class GovActions {

    private GovActions() {}

    /** The ballot box used: its town's ballot screen (or the Town Hall's overview, with no election on). */
    public static void openBallot(ServerPlayer p, BlockPos at) {
        Villages.Village v = Villages.nearest(p.serverLevel(), at, Villages.VILLAGE_RANGE);
        if (v == null) {
            p.displayClientMessage(net.minecraft.network.chat.Component.literal("This ballot box stands in no town. Set it down in one to make it the town's polling station."), true);
            return;
        }
        Elections.Campaign c = Elections.campaign(v.id());
        send(p, v, c != null && !c.counted && !c.candidates.isEmpty() ? "ballot" : "overview", "", "");
    }

    /** The Town Hall screen for the town the player is in (the board's button, a folk's talk). */
    public static void openHall(ServerPlayer p, String tab) {
        Villages.Village v = here(p);
        if (v == null) {
            p.displayClientMessage(net.minecraft.network.chat.Component.literal("There is no town near enough. Walk into one first."), true);
            return;
        }
        send(p, v, tab, "", "");
    }

    @Nullable
    static Villages.Village here(ServerPlayer p) {
        return Villages.nearest(p.serverLevel(), p.blockPosition(), Villages.VILLAGE_RANGE * 2);
    }

    static void send(ServerPlayer p, Villages.Village v, String tab, String said, String kind) {
        CompoundTag t = GovReport.report(p.serverLevel(), v, p);
        t.putString("tab", tab);
        t.putString("said", said);
        t.putString("saidKind", kind);
        GovNetwork.send(p, t);
    }

    /** A button pressed on the screen. */
    public static void act(ServerPlayer p, String action, CompoundTag args) {
        Villages.Village v = here(p);
        if (v == null) {
            p.displayClientMessage(net.minecraft.network.chat.Component.literal("You have left the town. Walk back in to use its Town Hall."), true);
            return;
        }
        ServerLevel level = p.serverLevel();
        UUID id = v.id();
        String tab = args.getString("tab");
        String said, kind = "ok";
        switch (action) {
            case "open" -> { send(p, v, tab.isEmpty() ? "overview" : tab, "", ""); return; }
            case "vote" -> {
                UUID cand;
                try { cand = UUID.fromString(args.getString("candidate")); } catch (IllegalArgumentException e) { cand = null; }
                String[] r = vote(level, v, p, cand);
                said = r[0];
                kind = r[1];
            }
            case "stand" -> {
                said = Hustings.stand(level, id, p, null);
                kind = Hustings.of(id, p.getUUID()) != null && !Hustings.of(id, p.getUUID()).out() ? "ok" : "bad";
                if (kind.equals("ok")) said = said + " Your name is on the ballot; put up to three promises below.";
            }
            case "pledge" -> {
                Pledges.Pledge pl = Pledges.parse(args.getString("key"));
                said = pl == null ? "That promise is not on offer now." : Hustings.promise(level, id, p.getUUID(), p.getName().getString(), pl);
                kind = said.startsWith("You're not") || said.startsWith("You've promised") || pl == null ? "bad" : "ok";
            }
            case "withdraw" -> {
                said = Hustings.withdraw(level, id, p.getUUID(), p.getName().getString()) + " You can stand again at the next election.";
                kind = "warn";
            }
            case "speech" -> {
                said = Hustings.speech(level, p);
                kind = said.startsWith("You spoke to ") ? "ok" : "bad";
            }
            case "sign" -> {
                String[] r = sign(level, v, p);
                said = r[0];
                kind = r[1];
            }
            case "show" -> {
                String what = args.getString("what");
                BlockPos at = what.equals("station") ? Polls.station(level, v, false) : what.equals("flag") ? Regimes.parse(Ledger.note(id, "gov.flag"))
                    : Polls.speechSpot(level, v);
                String label = what.equals("station") ? "the polling station (the ballot box)" : what.equals("flag") ? "the government's flag" : "the speech spot before the board";
                if (at == null) {
                    said = what.equals("station") ? "There is no polling station yet: the town sets its ballot box out when an election is called." : "Nothing to show.";
                    kind = "warn";
                } else {
                    com.jrpetty.mcassistant.pa.PaNet.ping(p, at, label, "MOVE", "show");
                    int d = (int) Math.sqrt(at.distSqr(p.blockPosition()));
                    said = "Marked " + label + " on your screen, " + d + " blocks away. The marker fades in under a minute.";
                }
            }
            case "books" -> {
                CompoundTag books = Annals.snapshot(level, v);
                books.putString("page", "Government");
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
                return;
            }
            default -> { said = "That button does nothing here."; kind = "bad"; }
        }
        send(p, v, tab.isEmpty() ? "overview" : tab, said, kind);
    }

    /** Why this player may not vote here now, or null if it may. */
    @Nullable
    public static String whyNotVote(ServerLevel level, Villages.Village v, ServerPlayer p) {
        UUID id = v.id();
        Government.Form form = Government.of(id);
        if (!Government.holdsElections(id)) return "nobody votes for the " + Government.title(id) + " in " + Regimes.shortName(form).toLowerCase(java.util.Locale.ROOT) + ".";
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        Elections.Campaign c = Elections.campaign(id);
        if (c == null || c.counted || c.candidates.isEmpty()) return "no election is under way. The next is on day " + Elections.nextVote(id, day) + ".";
        UUID voted = c.ballots.get(p.getUUID());
        if (voted != null) {
            Elections.Candidate k = Elections.find(c, voted);
            return "you have voted already" + (k == null ? "" : ", for " + k.name()) + ". A ballot cannot be changed.";
        }
        if (day > c.voteDay || day == c.voteDay && t >= Elections.CLOSE) return "the polls have closed. The count is at the board at dusk.";
        if (!Citizens.is(id, p.getUUID())) return "only a citizen votes. Ask any folk if you may live here.";
        if (form == Government.Form.COUNCIL || form == Government.Form.GUILD) {
            if (!Standing.of(id, p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.HONOURED)) {
                return form == Government.Form.COUNCIL ? "only the elders vote here (an honoured guest may sit with them)." : "only the masters vote here (an honoured guest may vote with them).";
            }
        }
        if (Laws.banished(id, p.getUUID(), day)) return "you are banished from the town.";
        if (!nearPolls(level, v, p)) return "votes are cast at the ballot box, the board or the hall. Go there (Show me marks the box).";
        return null;
    }

    static boolean nearPolls(ServerLevel level, Villages.Village v, ServerPlayer p) {
        BlockPos box = Polls.station(level, v, false);
        if (box != null && p.blockPosition().closerThan(box, 8)) return true;
        return Hustings.atTheBoard(v.id(), p);
    }

    /** A citizen's ballot: into the box, secretly, for good. Returns {what was said, ok|bad}. */
    static String[] vote(ServerLevel level, Villages.Village v, ServerPlayer p, @Nullable UUID cand) {
        String why = whyNotVote(level, v, p);
        if (why != null) return new String[]{ "Not cast: " + why, "bad" };
        UUID id = v.id();
        Elections.Campaign c = Elections.campaign(id);
        Elections.Candidate k = cand == null || c == null ? null : Elections.find(c, cand);
        if (k == null) return new String[]{ "Not cast: choose a candidate first.", "bad" };
        long day = level.getDayTime() / 24000L;
        c.ballots.put(p.getUUID(), k.id());
        c.why.put(p.getUUID(), "my own choice");
        c.atTheBoard.add(p.getUUID());
        Elections.save(c);
        BlockPos box = Polls.station(level, v, false);
        if (box != null && p.blockPosition().closerThan(box, 8)) {
            Polls.dropBallot(level, p, box);
            Polls.notice(level, v, box, Polls.turnoutText(id, c));
        }
        Villages.tell(id, day, p.getName().getString() + " cast a ballot" + (day < c.voteDay ? ", early" : ""));
        boolean early = day < c.voteDay;
        String when = "the count is at the board at dusk on day " + c.voteDay + "; watch the notice over the ballot box";
        return new String[]{ "Your ballot is in the box: a vote for " + k.name() + ". It is secret and cannot be changed. "
            + (early ? "You voted early, so it counts even if you are away on polling day; " : "") + when + ".", "ok" };
    }

    /** A citizen puts its name to the petition. Returns {said, kind}. */
    static String[] sign(ServerLevel level, Villages.Village v, ServerPlayer p) {
        UUID id = v.id();
        String[] pet = Dissent.petition(id);
        if (pet == null) return new String[]{ "There is no petition going round now. One goes round when the town's unrest passes 45.", "warn" };
        if (!Citizens.is(id, p.getUUID())) return new String[]{ "Not signed: only a citizen may sign the town's petition. Ask any folk if you may live here.", "bad" };
        Set<String> signed = Dissent.signers(id);
        if (!signed.add(p.getUUID().toString())) return new String[]{ "You have signed it already.", "warn" };
        Ledger.note(id, "dissent.signers", String.join(",", signed));
        Villages.tell(id, level.getDayTime() / 24000L, p.getName().getString() + " signed the petition for " + pet[0]);
        return new String[]{ "Signed: your name is on the petition for " + pet[0] + ". It goes to the " + Government.title(id) + " once "
            + pet[2] + " have signed; the names are counted each morning.", "ok" };
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a player's ballot for a candidate, as the screen would cast it; returns what was said. */
    public static String voteForTests(ServerLevel level, Villages.Village v, ServerPlayer p, UUID cand) {
        return vote(level, v, p, cand)[0];
    }
}
