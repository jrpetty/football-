package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [player-civic] Standing for leader: a player in the town's election (Elections).
 *
 * <p><b>Standing.</b> A citizen of the town that the town counts at least a friend (Standing), owing it nothing, may
 * put its name forward at the board or in the hall (anywhere in a town that has neither yet): to any folk there, "I'd
 * like to stand for election". Its name goes down for the next vote, or into the one under way until the polls open.
 * A player who leads stands again of its own accord, on its record.
 *
 * <p><b>Promises.</b> Up to three, out of what the town wants (Pledges): "I promise to lower the tithe". They are its
 * platform: what the folk hear in them (Values) is what it stands for, and the board, the gossip and the count say
 * them as they say a folk candidate's pledge.
 *
 * <p><b>The campaign.</b> Talk to the folk ("Will you vote for me?"): each hears what you stand for against what it
 * cares for and what the others stand for, and how it likes you, and leans your way or not (once each, a campaign).
 * A speech at the board (once a day) is heard by all within earshot, the more by those who care for what you
 * promise. Gifts help the way gifts always do. A vote can be bought, from a folk short of coin or keen on it; but a
 * bribe refused is told, a bribe seen is an offence under the town's laws (Laws), and a bribe taken may come out any
 * day after (the folk who took it boasts): a scandal, a fine, and every voter turns from you. The folk who stand
 * canvass too, each day of the campaign, among the folk who care for what they stand for.
 *
 * <p><b>The vote.</b> Each folk weighs a player as it weighs a folk (Elections.judge): what it stands for, what the
 * town needs; and then what the folk think of the player (Persona), the campaign, and the record of a player who led:
 * its promises kept and broken (PlayerLeader). The count goes as ever.
 *
 * <p><b>After.</b> The winner, player or folk, takes office (Elections.install, PlayerLeader.take). A player who lost
 * is thought the better of for standing, and the winner may offer it a seat on the council (a councillor's vote on
 * what is built next) until the next election.
 */
public final class Hustings {

    private Hustings() {}

    /** A player's candidacy: who, its promises, since when; {@code out}: a leader who will not stand again. */
    record Stand(UUID player, String name, List<Pledges.Pledge> pledges, long since, boolean out) {}

    /** Each town's campaign leanings: voter>candidate to how far the campaign has swayed it, and the vote they are for. */
    private static final Map<UUID, Map<String, Integer>> LEAN = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LEAN_FOR = new ConcurrentHashMap<>();
    /** The day each folk candidate last canvassed. */
    private static final Map<UUID, Long> CANVASSED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LEAN.clear();
        LEAN_FOR.clear();
        CANVASSED.clear();
    }

    // ------------------------------------------------------------------ kept with the town

    static List<Stand> stands(UUID village) {
        List<Stand> out = new ArrayList<>();
        String saved = Ledger.note(village, "civic.stand");
        if (saved == null || saved.isEmpty()) return out;
        for (String one : saved.split(";")) {
            String[] q = one.split("~", -1);
            if (q.length < 5) continue;
            try {
                List<Pledges.Pledge> pl = new ArrayList<>();
                for (String k : q[2].split(",")) {
                    Pledges.Pledge p = Pledges.parse(k);
                    if (p != null) pl.add(p);
                }
                out.add(new Stand(UUID.fromString(q[0]), q[1], pl, Long.parseLong(q[3]), "1".equals(q[4])));
            } catch (RuntimeException ignored) {
                // an old line: dropped
            }
        }
        return out;
    }

    static void save(UUID village, List<Stand> list) {
        StringBuilder sb = new StringBuilder();
        for (Stand s : list) {
            if (sb.length() > 0) sb.append(';');
            List<String> keys = new ArrayList<>();
            for (Pledges.Pledge p : s.pledges()) keys.add(p.key());
            sb.append(s.player()).append('~').append(s.name().replace("~", "-").replace(";", ",")).append('~')
                .append(String.join(",", keys)).append('~').append(s.since()).append('~').append(s.out() ? "1" : "0");
        }
        Ledger.note(village, "civic.stand", sb.toString());
    }

    @Nullable
    static Stand of(UUID village, UUID player) {
        for (Stand s : stands(village)) if (s.player().equals(player)) return s;
        return null;
    }

    static void put(UUID village, Stand s) {
        List<Stand> list = stands(village);
        list.removeIf(x -> x.player().equals(s.player()));
        list.add(s);
        save(village, list);
    }

    /** Is this candidate a player (one standing, or the one who leads)? */
    static boolean isPlayer(UUID village, UUID id) {
        if (id.equals(PlayerLeader.leaderId(village))) return true;
        Stand s = of(village, id);
        return s != null && !s.out();
    }

    // ------------------------------------------------------------------ the campaign's leanings

    private static Map<String, Integer> leans(UUID village) {
        Elections.Campaign c = Elections.campaign(village);
        long vote = c == null ? -1 : c.voteDay;
        Map<String, Integer> m = LEAN.get(village);
        Long forVote = LEAN_FOR.get(village);
        if (m != null && forVote != null && forVote == vote) return m;
        m = new HashMap<>();
        String saved = Ledger.note(village, "civic.lean");
        if (saved != null && !saved.isEmpty()) {
            String[] head = saved.split("\\|", 2);
            if (head.length == 2 && head[0].equals(Long.toString(vote))) {
                for (String one : head[1].split(",")) {
                    int at = one.lastIndexOf('>');
                    if (at <= 0) continue;
                    try { m.put(one.substring(0, at), Integer.parseInt(one.substring(at + 1))); } catch (NumberFormatException ignored) { }
                }
            }
        }
        LEAN.put(village, m);
        LEAN_FOR.put(village, vote);
        return m;
    }

    /** How far the campaign has swayed this folk toward this candidate. */
    static int lean(UUID village, UUID voter, UUID cand) {
        return leans(village).getOrDefault(voter + ">" + cand, 0);
    }

    static void sway(UUID village, UUID voter, UUID cand, int n) {
        leans(village).merge(voter + ">" + cand, n, Integer::sum);
        saveLeans(village);
    }

    private static void saveLeans(UUID village) {
        Map<String, Integer> m = leans(village);
        StringBuilder sb = new StringBuilder(Long.toString(LEAN_FOR.getOrDefault(village, -1L))).append('|');
        boolean first = true;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue() == 0) continue;
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('>').append(e.getValue());
            first = false;
        }
        Ledger.note(village, "civic.lean", sb.toString());
    }

    // ------------------------------------------------------------------ standing

    /** Is the player where a candidate is put forward: at the board, in the hall, or (a town with neither) in the town? */
    static boolean atTheBoard(UUID village, Player p) {
        BlockPos board = VillageBoards.lectern(village);
        if (board != null && p.blockPosition().closerThan(board, 12)) return true;
        for (String hall : new String[]{ "townhall", "hall" }) {
            BlockPos at = Villages.builtAt(village, hall);
            if (at != null && p.blockPosition().closerThan(at, 12)) return true;
        }
        Villages.Village v = Villages.get(village);
        return board == null && Villages.builtAt(village, "townhall") == null && Villages.builtAt(village, "hall") == null
            && v != null && p.blockPosition().closerThan(v.centre(), Villages.VILLAGE_RANGE);
    }

    /** "I'd like to stand for election." */
    public static String stand(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Stand for what? I've no village.";
        return stand(level, village, p, f);
    }

    /** Standing, said to a folk ({@code heard}) or from the hustings page (/village civic stand). */
    public static String stand(ServerLevel level, UUID village, Player p, @Nullable VillageFolkEntity heard) {
        String title = Homeland.leaderTitle(village);
        String name = p.getName().getString();
        long day = Civics.day(level);
        if (PlayerLeader.leads(village, p.getUUID())) {
            Stand s = of(village, p.getUUID());
            if (s != null && s.out()) put(village, new Stand(p.getUUID(), name, s.pledges(), day, false));
            return "You're our " + title + " already. You'll stand again at the election on day " + Elections.nextVote(village, day)
                + ", and we'll judge you on your record.";
        }
        if (!Citizens.is(village, p.getUUID())) return "Only a citizen of " + Villages.name(village) + " may stand. Ask to live here first.";
        String barred = Government.standBarred(level, village, p);     // [identity] a lordship, the chaplain's, the elders', the guild's
        if (barred != null) return barred;
        Standing.View view = Standing.of(village, p.getUUID(), level.getGameTime());
        if (!view.title().atLeast(Standing.Title.FRIEND)) {
            return "Stand for " + title + "? The town hardly knows you. Be a friend to it first — you're " + view.title().words + ".";
        }
        if (Laws.owes(village, p.getUUID()) > 0 || Laws.banished(village, p.getUUID(), day)) return "Not while you owe the town. Settle up first.";
        if (!atTheBoard(village, p)) return "You put your name forward at the board, or in the hall. Come and say it there.";
        Elections.Campaign c = Elections.campaign(village);
        long t = level.getDayTime() % 24000L;
        Stand was = of(village, p.getUUID());
        List<Pledges.Pledge> pledges = was == null ? new ArrayList<>() : new ArrayList<>(was.pledges());
        if (c != null && !c.counted && c.voteDay == day && t >= Elections.OPEN) return "Too late for this one — the polls are open. Stand at the next.";
        put(village, new Stand(p.getUUID(), name, pledges, day, false));
        long vote = c != null && !c.counted ? c.voteDay : Elections.nextVote(village, day);
        if (c != null && !c.counted) enterNow(c, village, p.getUUID());
        boolean fresh = was == null || was.out();
        if (fresh) {
            Villages.tell(village, day, name + " put their name forward to be " + title);
            if (heard != null) heard.persona().remember(day, name + " is standing to be our " + title, 3);
        }
        StringBuilder sb = new StringBuilder(fresh ? "You're standing? Good for you! " : "You're standing already. ");
        sb.append("The vote's on day ").append(vote).append(". ");
        sb.append(pledges.isEmpty() ? "Now — what do you promise us? Say \"I promise to …\". The town wants: "
            : "You've promised " + nouns(pledges) + ". You can promise up to " + Pledges.MOST + ": ");
        List<String> on = new ArrayList<>();
        for (Pledges.Pledge pl : Pledges.offered(level, village)) on.add(pl.words(village));
        sb.append(String.join("; ", on)).append(".");
        return sb.toString();
    }

    /** "I promise to lower the tithe." */
    public static String promise(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Promise who?";
        Stand s = of(village, p.getUUID());
        if (s == null || s.out()) return "Promises are for those standing for " + Homeland.leaderTitle(village) + ". Put your name forward first.";
        Pledges.Pledge want = Pledges.named(level, village, text);
        if (want == null) {
            List<String> on = new ArrayList<>();
            for (Pledges.Pledge pl : Pledges.offered(level, village)) on.add(pl.words(village));
            return "Promise what? The town wants: " + String.join("; ", on) + ".";
        }
        return promise(level, village, p.getUUID(), p.getName().getString(), want);
    }

    /** A promise added to a candidacy (by talk or by the hustings page). */
    public static String promise(ServerLevel level, UUID village, UUID player, String name, Pledges.Pledge want) {
        Stand s = of(village, player);
        if (s == null || s.out()) return "You're not standing.";
        List<Pledges.Pledge> list = new ArrayList<>(s.pledges());
        for (Pledges.Pledge pl : list) if (pl.key().equals(want.key())) return "You've promised " + want.noun() + " already.";
        if (list.size() >= Pledges.MOST) return "You've promised " + Pledges.MOST + " things already: " + nouns(list) + ". Keep them, and that's plenty.";
        list.add(want);
        put(village, new Stand(player, name, list, s.since(), false));
        Elections.Campaign c = Elections.campaign(village);
        if (c != null && !c.counted) enterNow(c, village, player);
        long day = Civics.day(level);
        Villages.tell(village, day, name + " promised the town " + want.noun() + " if elected");
        return FolkTalk.pick(level.getRandom(), "Promised, then: ", "We'll hold you to it: ", "Fine words: ") + want.words(village)
            + ". That's " + list.size() + " of " + Pledges.MOST + ". Folk who care for " + want.value().word + " will like that.";
    }

    /** "I withdraw." — or, for one who leads, not standing again. */
    public static String withdraw(ServerLevel level, UUID village, UUID player, String name) {
        Stand s = of(village, player);
        boolean leads = PlayerLeader.leads(village, player);
        if (s == null && !leads) return "You weren't standing.";
        put(village, new Stand(player, name, s == null ? List.of() : s.pledges(), Civics.day(level), true));
        Elections.Campaign c = Elections.campaign(village);
        if (c != null && !c.counted) {
            c.candidates.removeIf(k -> k.id().equals(player));
            Elections.save(c);
        }
        Villages.tell(village, Civics.day(level), name + " withdrew from the election");
        return "Your name's off the list.";
    }

    /** "the schoolhouse within ten days, a lower tithe and more guards on the walls". */
    static String nouns(List<Pledges.Pledge> list) {
        if (list.isEmpty()) return "steady hands and an honest word";
        List<String> n = new ArrayList<>();
        for (Pledges.Pledge p : list) n.add(p.noun());
        if (n.size() == 1) return n.get(0);
        return String.join(", ", n.subList(0, n.size() - 1)) + " and " + n.get(n.size() - 1);
    }

    /** The candidate a player stands as: what its promises come to (its platform), and its pledge in words. */
    static Elections.Candidate candidate(UUID village, UUID player, String name, List<Pledges.Pledge> pledges) {
        Map<Values.Value, Integer> count = new EnumMap<>(Values.Value.class);
        for (Pledges.Pledge p : pledges) count.merge(p.value(), 1, Integer::sum);
        List<Values.Value> order = new ArrayList<>(count.keySet());
        order.sort((a, b) -> count.get(b).equals(count.get(a)) ? Integer.compare(a.ordinal(), b.ordinal()) : Integer.compare(count.get(b), count.get(a)));
        Values.Value top = order.isEmpty() ? Values.Value.TRADITION : order.get(0);
        Values.Value second = order.size() > 1 ? order.get(1) : top == Values.Value.TRADITION ? Values.Value.WEALTH : Values.Value.TRADITION;
        return new Elections.Candidate(player, name, top, second, nouns(pledges));
    }

    /** Into the election under way now: as a candidate, or its platform brought up to date. */
    static void enterNow(Elections.Campaign c, UUID village, UUID player) {
        Stand s = of(village, player);
        if (s == null || s.out()) return;
        Elections.Candidate k = candidate(village, player, s.name(), s.pledges());
        boolean found = false;
        for (int i = 0; i < c.candidates.size(); i++) {
            if (c.candidates.get(i).id().equals(player)) { c.candidates.set(i, k); found = true; }
        }
        if (!found) c.candidates.add(k);
        Elections.save(c);
    }

    // ------------------------------------------------------------------ the election's hooks

    /** The election is called (Elections.call): the players standing, and a player who leads, go on the list. */
    static void enter(ServerLevel level, Villages.Village v, Elections.Campaign c, long day) {
        UUID id = v.id();
        UUID leader = PlayerLeader.leaderId(id);
        if (leader != null) {
            Stand s = of(id, leader);
            if (s == null) {
                // One who leads stands again on its record: what it promised last time.
                List<Pledges.Pledge> again = new ArrayList<>();
                for (Pledges.Promise pr : PlayerLeader.promises(id)) if (again.size() < Pledges.MOST) again.add(pr.pledge);
                put(id, new Stand(leader, PlayerLeader.leaderName(id), again, day, false));
            }
        }
        for (Stand s : stands(id)) {
            if (s.out() || !Citizens.is(id, s.player()) && !s.player().equals(leader)) continue;
            boolean already = false;
            for (Elections.Candidate k : c.candidates) if (k.id().equals(s.player())) already = true;
            if (!already) c.candidates.add(candidate(id, s.player(), s.name(), s.pledges()));
        }
    }

    /**
     * What the campaign and the player add to a folk's weighing of a candidate (Elections.judge): its leaning from the
     * canvass, the speeches and the bribes for anybody; and for a player, what the folk thinks of it and its record.
     */
    static double lean(ServerLevel level, VillageFolkEntity voter, Elections.Candidate c) {
        UUID village = voter.ownerId();
        if (village == null) return 0;
        double n = lean(village, voter.getUUID(), c.id());
        if (isPlayer(village, c.id())) {
            n += voter.persona().affinity(c.id()) * 0.6;
            n += PlayerLeader.record(village, c.id());
            if (scandal(village, c.id())) n -= 25;
        }
        return n;
    }

    /** The reason a folk gives for voting for a player, when it is the player's own doing. */
    static String why(VillageFolkEntity voter, Elections.Candidate c, String why) {
        UUID village = voter.ownerId();
        if (village == null || !isPlayer(village, c.id())) return why;
        if (bribed(village, voter.getUUID(), c.id())) return "it seemed right";              // it won't say
        int rec = PlayerLeader.record(village, c.id());
        if (rec >= 8) return "they kept their word to us";
        if (rec <= -8) return why;
        if (lean(village, voter.getUUID(), c.id()) >= 8) return "they came and asked me themselves, and I liked what I heard";
        if (voter.persona().affinity(c.id()) >= 40) return "they've been a true friend to this town";
        return why;
    }

    /**
     * A line of the count's assembly said by a player candidate (Assemblies): spoken in its own name to the players
     * gathered there, or read out for it if it is not there. True if it was a player's line.
     */
    public static boolean says(ServerLevel level, UUID village, BlockPos at, UUID who, String text) {
        if (level.getEntity(who) instanceof VillageFolkEntity) return false;
        Stand s = of(village, who);
        String name = s != null ? s.name() : who.equals(PlayerLeader.leaderId(village)) ? PlayerLeader.leaderName(village) : null;
        if (name == null) return false;
        Player there = level.getPlayerByUUID(who);
        boolean present = there != null && there.blockPosition().closerThan(at, 48);
        Component line = Component.literal(present ? "<" + name + "> " + text : "(" + name + "'s words, read out for them) " + text)
            .withStyle(ChatFormatting.GOLD);
        for (Player p : level.players()) if (p.blockPosition().closerThan(at, 64)) p.sendSystemMessage(line);
        return true;
    }

    /** The count is done and the winner installed (Elections.install): the players who stood, won or lost. */
    static void installed(ServerLevel level, Villages.Village v, Elections.Candidate winner, long day) {
        UUID id = v.id();
        List<Stand> stood = stands(id);
        UUID before = PlayerLeader.officeHolder(id);
        Stand won = null;
        for (Stand s : stood) if (s.player().equals(winner.id()) && !s.out()) won = s;
        if (won != null || winner.id().equals(before)) {
            List<Pledges.Pledge> pledges = won != null ? won.pledges() : List.of();
            PlayerLeader.take(level, v, winner.id(), winner.name(), pledges, day);
        } else if (before != null) {
            PlayerLeader.leave(level, v, day, "lost the election to " + winner.name());
        }
        Ledger.note(id, "civic.seat", "");
        for (Stand s : stood) {
            if (s.out() || s.player().equals(winner.id())) continue;
            lost(level, v, s, winner, day);
        }
        // A new campaign begins from nothing: no promises on file, no leanings, no scandal.
        save(id, new ArrayList<>());
        Ledger.note(id, "civic.lean", "");
        Ledger.note(id, "civic.canvass", "");
        Ledger.note(id, "civic.scandal", "");
        Ledger.note(id, "civic.bribes", "");                       // what never came out by the count, never will
        LEAN.remove(id);
        LEAN_FOR.remove(id);
    }

    /** A player who stood and lost: the town thinks the better of it for standing, and the winner may offer it a seat. */
    static void lost(ServerLevel level, Villages.Village v, Stand s, Elections.Candidate winner, long day) {
        UUID id = v.id();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby()) f.persona().feelFor(s.player(), s.name(), 3);
        }
        Standing.stir(id, s.player());
        VillageFolkEntity w = Elections.loaded(id, winner.id());
        int share = share(id, s.name());
        boolean seat = w != null && (w.persona().affinity(s.player()) >= 10 || share >= 25);
        if (seat) {
            Ledger.note(id, "civic.seat", s.player() + "~" + s.name() + "~" + winner.name() + "~" + day);
            Villages.tell(id, day, winner.name() + " offered " + s.name() + " a seat on the council");
            FolkTalk.speak(w, FolkTalk.pick(level.getRandom(), "Well fought, " + s.name() + ". Sit on the council with us.",
                s.name() + " — the council could use you. Take a seat."));
        }
        Player p = level.getPlayerByUUID(s.player());
        if (p != null) {
            p.sendSystemMessage(Component.literal("You lost the election in " + Villages.name(id) + " to " + winner.name()
                + (share >= 0 ? " (" + share + "% of the vote)" : "") + ". The town thinks the better of you for standing"
                + (seat ? ", and " + winner.name() + " has offered you a seat on the council." : ".")).withStyle(ChatFormatting.GOLD));
        }
    }

    /** A candidate's share of the last count, in the hundred, from the count written down (Elections), or -1. */
    static int share(UUID village, String name) {
        String last = Ledger.note(village, "election.last");
        if (last == null) return -1;
        String[] p = last.split("\\|", 3);
        if (p.length < 3) return -1;
        int mine = -1, all = 0;
        String tally = p[2].replaceAll(" \\(.*\\)$", "");
        for (String one : tally.split(", ")) {
            int sp = one.lastIndexOf(' ');
            if (sp <= 0) continue;
            try {
                int n = Integer.parseInt(one.substring(sp + 1));
                all += n;
                if (one.substring(0, sp).equals(name)) mine = n;
            } catch (NumberFormatException ignored) { }
        }
        return mine < 0 || all == 0 ? -1 : 100 * mine / all;
    }

    // ------------------------------------------------------------------ the council seat

    /** Does this player sit on the council (offered a seat by the winner it stood against)? */
    public static boolean seated(UUID village, UUID player) {
        String s = Ledger.note(village, "civic.seat");
        return s != null && s.startsWith(player.toString());
    }

    /** The council's vote (Council.vote): a seated player's proposal has a councillor's vote as well as a citizen's. */
    static void seatVotes(UUID village, Map<UUID, String> proposals, Map<String, Integer> tally) {
        String s = Ledger.note(village, "civic.seat");
        if (s == null || s.isEmpty()) return;
        try {
            UUID who = UUID.fromString(s.split("~", 2)[0]);
            String theirs = proposals.get(who);
            if (theirs != null && tally.containsKey(theirs)) tally.merge(theirs, 1, Integer::sum);
        } catch (RuntimeException ignored) { }
    }

    /** "Steve sits on the council too, at Bryn's invitation." for the council's news, or "". */
    public static String seatNews(UUID village) {
        String s = Ledger.note(village, "civic.seat");
        if (s == null || s.isEmpty()) return "";
        String[] q = s.split("~");
        return q.length >= 3 ? q[1] + " sits on the council too, at " + q[2] + "'s invitation. " : "";
    }

    // ------------------------------------------------------------------ the campaign

    @Nullable
    private static Elections.Candidate find(Elections.Campaign c, UUID id) {
        for (Elections.Candidate k : c.candidates) if (k.id().equals(id)) return k;
        return null;
    }

    /** How a candidate's platform sits with what this folk cares for. */
    static double fit(VillageFolkEntity f, Elections.Candidate k) {
        int[] w = Values.of(f);
        return w[k.platform().ordinal()] + 0.4 * w[k.second().ordinal()];
    }

    /** "Will you vote for me?" — once a campaign, a folk hears you out. */
    public static String canvass(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Vote? I've no village to vote in.";
        Elections.Campaign c = Elections.campaign(village);
        Elections.Candidate me = c == null || c.counted ? null : find(c, p.getUUID());
        if (me == null) {
            Stand s = of(village, p.getUUID());
            return s != null && !s.out() ? "The election isn't called yet — ask me again when it is, nearer day "
                + Elections.nextVote(village, Civics.day(level)) + "." : "Vote for you? You're not standing.";
        }
        if (find(c, f.getUUID()) != null) return "I'm standing myself! I'll be voting for me, thank you.";
        UUID voted = c.ballots.get(f.getUUID());
        if (voted != null) {
            Elections.Candidate k = find(c, voted);
            return "I've voted already" + (k == null ? "." : voted.equals(p.getUUID()) ? " — for you, as it happens." : " — for " + k.name() + ".");
        }
        String key = c.voteDay + ":" + f.getUUID() + ">" + p.getUUID();
        String done = Ledger.note(village, "civic.canvass");
        if (done != null && done.contains(key)) return "You asked me already. I've not forgotten what you stand for.";
        Ledger.note(village, "civic.canvass", (done == null || done.isEmpty() ? "" : done + ",") + key);
        double mine = fit(f, me), rival = 0;
        Elections.Candidate best = null;
        for (Elections.Candidate k : c.candidates) {
            if (k.id().equals(p.getUUID())) continue;
            double r = fit(f, k);
            if (best == null || r > rival) { rival = r; best = k; }
        }
        int aff = f.persona().affinity(p.getUUID());
        int sway = 4 + (mine >= rival ? 8 : mine >= rival - 8 ? 3 : 0) + (aff >= 30 ? 4 : aff < 0 ? -4 : 0)
            + (f.life().has(Social.Trait.SOCIABLE) ? 1 : 0);
        sway(village, f.getUUID(), p.getUUID(), sway);
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 1);
        Values.Value care = Values.top(f);
        if (mine >= rival + 5) return "You stand for " + me.platform().cares + " — and that's what I care about. You'll have my vote.";
        if (mine >= rival - 5 || best == null) return "You and " + (best == null ? "the others" : best.name()) + " both talk sense. I'll think on it.";
        return "Fair words. But I care for " + care.cares + ", and " + best.name() + " stands for that.";
    }

    /** A speech at the board (once a day): heard by everybody within earshot, the more by those who care for what you promise. */
    public static String speech(ServerLevel level, Player p) {
        Villages.Village v = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE);
        if (v == null) return "There's nobody here to make a speech to.";
        UUID village = v.id();
        Elections.Campaign c = Elections.campaign(village);
        Elections.Candidate me = c == null || c.counted ? null : find(c, p.getUUID());
        if (me == null) return "There's no election under way for you to speak in.";
        BlockPos board = VillageBoards.lectern(village);
        BlockPos at = board != null ? board : v.centre();
        if (!p.blockPosition().closerThan(at, board != null ? 10 : 24)) return "Speeches are made at the board, in the square.";
        long day = Civics.day(level);
        String said = Ledger.note(village, "civic.speech." + p.getUUID());
        if (said != null && said.equals(Long.toString(day))) return "You've made your speech today. Let it sink in.";
        Ledger.note(village, "civic.speech." + p.getUUID(), Long.toString(day));
        String name = p.getName().getString();
        String words = "Folk of " + Villages.name(village) + "! Vote for me, and I'll give you " + me.pledge() + "!";
        Component line = Component.literal("<" + name + "> " + words).withStyle(ChatFormatting.GOLD);
        for (Player o : level.players()) if (o.blockPosition().closerThan(at, 64)) o.sendSystemMessage(line);
        int heard = 0, cheered = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isSleeping() || f.distanceToSqr(p) > 24.0 * 24.0) continue;
            if (find(c, f.getUUID()) != null) continue;
            Values.Value top = Values.top(f), second = Values.second(f);
            boolean cares = top == me.platform() || top == me.second() || second == me.platform();
            int aff = f.persona().affinity(p.getUUID());
            sway(village, f.getUUID(), p.getUUID(), 3 + (cares ? 5 : 0) + (aff >= 20 ? 2 : 0));
            heard++;
            f.getLookControl().setLookAt(p, 30.0F, 30.0F);
            if (cares && cheered < 3 && level.getRandom().nextInt(2) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Hear, hear!", "That's what we need!", "Well said, " + name + "!"));
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 2.1, f.getZ(), 4, 0.3, 0.2, 0.3, 0.02);
                cheered++;
            } else if (!cares && f.life().has(Social.Trait.GRUMPY) && level.getRandom().nextInt(3) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Hmph. Words are cheap.", "We'll see."));
            }
        }
        level.playSound(null, p.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1.0F, 1.2F);
        Villages.tell(village, day, name + " made a speech at the board, promising " + me.pledge());
        return heard == 0 ? "You spoke to an empty square. Come back when folk are about."
            : "You spoke to " + heard + " folk at the board" + (cheered > 0 ? ", and " + cheered + " cheered" : "") + ".";
    }

    /** "I'll give you five coins for your vote." */
    public static String bribe(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Buy what?";
        Elections.Campaign c = Elections.campaign(village);
        Elections.Candidate me = c == null || c.counted ? null : find(c, p.getUUID());
        if (me == null) return "My vote? You're not standing.";
        if (find(c, f.getUUID()) != null) return "I'm standing myself. Keep your money.";
        int coins = Math.max(1, Math.min(50, Commerce.number(text, 5)));
        if (Market.coinsHeld(p) < coins) return "You've not got " + coins + " coins on you.";
        String name = p.getName().getString();
        long day = Civics.day(level);
        Villages.Village v = Villages.get(village);
        int aff = f.persona().affinity(p.getUUID());
        Values.Value top = Values.top(f);
        boolean greedy = top == Values.Value.WEALTH || Wealth.tier(f) == Wealth.Tier.POOR || f.purse() < coins * 2;
        boolean honest = top == Values.Value.TRADITION || f.life().has(Social.Trait.GENEROUS) || aff < -10 || !greedy && coins < 15;
        if (honest) {
            f.persona().feelFor(p.getUUID(), name, -8);
            f.persona().remember(day, name + " tried to buy my vote", 5);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Buy my vote? I'll not be bought!", "Put that away. Shame on you."));
            // It tells: half the time the whole town hears of it.
            if (v != null && f.getRandom().nextBoolean()) scandal(level, v, p.getUUID(), name, "trying to buy " + f.displayNameCap() + "'s vote", coins, p, false);
            return "Buy my vote? I'll not be bought, and I'll not forget you tried.";
        }
        Market.payOut(p, coins);
        f.earn(coins);
        sway(village, f.getUUID(), p.getUUID(), Math.min(30, 10 + coins * 2));
        // Seen by another folk near enough: an offence under the town's laws, there and then (and out already).
        boolean seen = v != null && seenBy(level, village, f, p) != null;
        String list = Ledger.note(village, "civic.bribes");
        Ledger.note(village, "civic.bribes", (list == null || list.isEmpty() ? "" : list + ";") + f.getUUID() + ">" + p.getUUID() + ">" + day + ">"
            + coins + ">" + (seen ? "1" : "0"));
        f.persona().remember(day, name + " paid me " + coins + " coins for my vote", 2);
        if (seen) {
            scandal(level, v, p.getUUID(), name, "buying " + f.displayNameCap() + "'s vote", 5 + coins, p, true);
            return "…" + FolkTalk.pick(f.getRandom(), "Oh no. Somebody saw that.", "Put it away — we're seen!");
        }
        return FolkTalk.pick(f.getRandom(), "Well… I could be persuaded. Our secret.", "Say no more. Our secret, mind.");
    }

    /** Another folk, awake and near enough to see it. */
    @Nullable
    static VillageFolkEntity seenBy(ServerLevel level, UUID village, VillageFolkEntity bribed, Player p) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f == bribed || f.isSleeping() || f.isBaby()) continue;
            if (f.distanceToSqr(p) < 12.0 * 12.0 && f.hasLineOfSight(p)) return f;
        }
        return null;
    }

    static boolean bribed(UUID village, UUID voter, UUID player) {
        String list = Ledger.note(village, "civic.bribes");
        return list != null && list.contains(voter + ">" + player + ">");
    }

    static boolean scandal(UUID village, UUID player) {
        String s = Ledger.note(village, "civic.scandal");
        return s != null && s.contains(player.toString());
    }

    /**
     * A bribe come out: in front of a witness, an offence under the town's laws (Laws.offence: the fine, the trial or
     * banishment); found out later, a fine owed all the same. Either way every voter turns from the player, the bought
     * leanings are undone, and the town's chronicle has it.
     */
    static void scandal(ServerLevel level, Villages.Village v, UUID player, String name, String what, int fine, @Nullable Player there, boolean seen) {
        UUID id = v.id();
        long day = Civics.day(level);
        if (seen && there != null && !Laws.exempt(there)) {
            Laws.offence(level, v, there, what, fine);
        } else {
            int[] r = Ledger.record(id, player);
            r[0]++;
            r[1] += fine;
            Ledger.record(id, player, r);
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && !f.isBaby()) f.persona().feelFor(player, name, -6);
            }
            Standing.stir(id, player);
            Player p = level.getPlayerByUUID(player);
            if (p != null) p.sendSystemMessage(Component.literal("It came out in " + Villages.name(id) + ": " + what
                + ". You owe the town a fine of " + fine + " coins.").withStyle(ChatFormatting.RED));
        }
        String s = Ledger.note(id, "civic.scandal");
        if (s == null || !s.contains(player.toString())) Ledger.note(id, "civic.scandal", (s == null || s.isEmpty() ? "" : s + ";") + player + ">" + day);
        Map<String, Integer> m = leans(id);
        for (String k : new ArrayList<>(m.keySet())) {
            if (k.endsWith(">" + player) && bribed(id, UUID.fromString(k.substring(0, k.indexOf('>'))), player)) m.put(k, 0);
        }
        saveLeans(id);
        Villages.tell(id, day, name + " was found out " + what);
    }

    // ------------------------------------------------------------------ every so often

    /** A few times a day (PlayerCivic.tick): the folk candidates canvass, and a bribe taken may come out. */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        Elections.Campaign c = Elections.campaign(id);
        if (c == null || c.counted || c.candidates.isEmpty() || t < 2000L || t > 11000L) return;
        // The folk who stand canvass among those who care for what they stand for: once a day each.
        List<VillageFolkEntity> voters = Elections.voters(id);
        for (Elections.Candidate k : c.candidates) {
            if (isPlayer(id, k.id())) continue;
            if (CANVASSED.getOrDefault(k.id(), -1L) == day) continue;
            CANVASSED.put(k.id(), day);
            VillageFolkEntity self = Elections.loaded(id, k.id());
            int asked = 0;
            for (VillageFolkEntity f : voters) {
                if (f.getUUID().equals(k.id()) || c.ballots.containsKey(f.getUUID()) || find(c, f.getUUID()) != null) continue;
                Values.Value top = Values.top(f);
                if (top != k.platform() && top != k.second() && level.getRandom().nextInt(4) != 0) continue;
                sway(id, f.getUUID(), k.id(), 5);
                if (++asked >= 3) break;
            }
            if (self != null && asked > 0) FolkTalk.speak(self, "Vote for me: " + k.pledge() + "!");
        }
        // A bribe taken may come out: the folk who took it boasts.
        String list = Ledger.note(id, "civic.bribes");
        if (list == null || list.isEmpty() || level.getRandom().nextInt(6) != 0) return;
        StringBuilder kept = new StringBuilder();
        boolean changed = false;
        for (String one : list.split(";")) {
            String[] q = one.split(">");
            if (q.length < 5) continue;
            if ("0".equals(q[4]) && level.getRandom().nextInt(4) == 0) {
                try {
                    UUID who = UUID.fromString(q[1]);
                    Stand s = of(id, who);
                    VillageFolkEntity f = Elections.loaded(id, UUID.fromString(q[0]));
                    scandal(level, v, who, s == null ? "a candidate" : s.name(), "buying " + (f == null ? "a" : f.displayNameCap() + "'s") + " vote",
                        5 + Integer.parseInt(q[3]), null, false);
                    q[4] = "1";
                    changed = true;
                } catch (RuntimeException ignored) { }
            }
            if (kept.length() > 0) kept.append(';');
            kept.append(String.join(">", q));
        }
        if (changed) Ledger.note(id, "civic.bribes", kept.toString());
    }

    // ------------------------------------------------------------------ the page

    /** The candidate's page: the vote, the promises made and on offer (buttons), a speech, and how it is going. */
    public static void openPage(ServerPlayer p) {
        Villages.Village v = Villages.nearest(p.level(), p.blockPosition(), Villages.VILLAGE_RANGE * 2);
        if (v == null) {
            PlayerCivic.send(p, "The election", "There is no town near enough. Walk into one.", List.of());
            return;
        }
        ServerLevel level = (ServerLevel) p.level();
        UUID id = v.id();
        long day = Civics.day(level);
        String title = Homeland.leaderTitle(id);
        StringBuilder sb = new StringBuilder();
        List<String> buttons = new ArrayList<>();
        Elections.Campaign c = Elections.campaign(id);
        sb.append("Election: ").append(Elections.line(id, day)).append("\n");
        Stand s = of(id, p.getUUID());
        if (s == null || s.out()) {
            sb.append("\nYou are not standing. A citizen the town counts a friend may stand for ").append(title)
                .append(": say \"I'd like to stand for election\" to any folk at the board or in the hall.\n");
            buttons.add("Stand for " + title + "\tvillage civic stand\tPut your name forward (at the board or in the hall)");
        } else {
            sb.append("\nYou are standing for ").append(title).append(". Your promises: ").append(nouns(s.pledges())).append(".\n");
            if (c != null && !c.counted) {
                Elections.Candidate me = find(c, p.getUUID());
                if (me != null) sb.append("You stand for ").append(me.platform().cares).append(" (a ").append(me.platform().type)
                    .append("'s platform). Votes so far: ").append(c.votesFor(p.getUUID())).append(".\n");
            }
            if (s.pledges().size() < Pledges.MOST) {
                sb.append("\nThe town wants (promise up to ").append(Pledges.MOST).append("):\n");
                for (Pledges.Pledge pl : Pledges.offered(level, id)) {
                    boolean made = false;
                    for (Pledges.Pledge q : s.pledges()) if (q.key().equals(pl.key())) made = true;
                    if (made) continue;
                    sb.append("- ").append(pl.words(id)).append(" (").append(pl.value().type).append("s care for it)\n");
                    buttons.add("Promise: " + pl.words(id) + "\tvillage civic promise " + pl.key() + "\t" + capital(pl.words(id)) + ": "
                        + pl.value().cares + " is what it means to the folk");
                }
            }
            buttons.add("Make a speech\tvillage civic speech\tAt the board, once a day: everyone within earshot hears you");
            buttons.add("Withdraw\tvillage civic withdraw\tTake your name off the list");
            sb.append("\nCampaign: talk to the folk (\"Will you vote for me?\"); make a speech at the board once a day; gifts help. "
                + "Bribes are noticed, and the law punishes them.\n");
        }
        PlayerCivic.send(p, "Standing for " + title + " — " + Villages.name(id), sb.toString(), buttons);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: this player's candidacy's promises, as keys, or null if it is not standing. */
    @Nullable
    public static List<String> promisesForTests(UUID village, UUID player) {
        Stand s = of(village, player);
        if (s == null) return null;
        List<String> out = new ArrayList<>();
        for (Pledges.Pledge p : s.pledges()) out.add(p.key());
        return out;
    }

    /** Tests: how far the campaign has swayed this folk toward this candidate. */
    public static int leanForTests(UUID village, UUID voter, UUID cand) {
        return lean(village, voter, cand);
    }
}
