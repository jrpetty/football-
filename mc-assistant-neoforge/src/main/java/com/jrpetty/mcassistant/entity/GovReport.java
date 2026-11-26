package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [featcivic] Everything the Government page of the town's books, the Town Hall screen and the ballot screen show, in one
 * compound: the form and its rules, the comparison of every form, the policy, the election (who stands, the queue, the
 * count, the last result as a chart), the parties and the council's seats, the unrest, the neighbours and their
 * governments, and what the reader (a player) may do here and why not.
 */
public final class GovReport {

    private GovReport() {}

    public static CompoundTag report(ServerLevel level, Villages.Village v, @Nullable ServerPlayer p) {
        UUID id = v.id();
        CompoundTag t = new CompoundTag();
        long day = level.getDayTime() / 24000L;
        t.putString("town", Villages.name(id));
        t.putLong("day", day);
        Identity.Rec r = Identity.known(id);
        if (r == null) {
            t.putString("form", "");
            t.putString("pending", Villages.name(id) + " settles how it is governed on its first morning.");
            return t;
        }
        Government.Form form = r.gov;
        t.putString("form", form.name());
        t.putString("formWords", Regimes.capital(form.words));
        t.putString("short", Regimes.shortName(form));
        t.putString("how", form.how);
        t.putString("title", Regimes.capital(Government.title(id)));
        t.putString("leader", Villages.elderName(id));
        t.putLong("since", r.govSince);
        t.putString("phrase", Regimes.capital(Government.phrase(id, r)));
        t.putInt("colour", Regimes.colour(form).getTextColor());
        t.putBoolean("elected", !Government.appointed(form));
        t.put("rules", rules(Regimes.rules(form)));
        t.put("compare", compare());
        t.put("policy", policy(level, id));
        t.put("election", election(level, v, p, day));
        t.put("parties", parties(id));
        t.put("unrest", unrest(id, form, p));
        t.put("relations", relations(id));
        t.put("you", you(level, v, p, form));
        String changed = Ledger.note(id, "dissent.lastChange");
        if (changed != null && !changed.isEmpty()) t.putString("lastChange", changed);
        return t;
    }

    static CompoundTag rules(Regimes.Rules x) {
        CompoundTag t = new CompoundTag();
        t.putString("ownership", x.ownership());
        t.putString("business", x.business());
        t.putString("homes", x.homes());
        t.putString("vote", x.vote());
        t.putString("speech", x.speech());
        t.putString("market", x.market());
        t.putString("wages", x.wages());
        t.putString("taxes", x.taxes());
        t.putString("laws", x.laws());
        t.putString("style", x.style());
        t.put("strengths", strings(x.strengths()));
        t.put("weaknesses", strings(x.weaknesses()));
        t.put("player", strings(x.player()));
        return t;
    }

    /** Every form side by side: and how many of the towns about are under it now. */
    static ListTag compare() {
        Map<Government.Form, Integer> towns = new EnumMap<>(Government.Form.class);
        int known = 0;
        for (Villages.Village o : Villages.every()) {
            Identity.Rec r = Identity.known(o.id());
            if (r == null) continue;
            towns.merge(r.gov, 1, Integer::sum);
            known++;
        }
        ListTag l = new ListTag();
        for (Government.Form f : Government.Form.values()) {
            Regimes.Rules x = Regimes.rules(f);
            CompoundTag c = rules(x);
            c.putString("form", f.name());
            c.putString("short", Regimes.shortName(f));
            c.putString("words", Regimes.capital(f.words));
            c.putInt("colour", Regimes.colour(f).getTextColor());
            c.putInt("towns", towns.getOrDefault(f, 0));
            c.putInt("known", known);
            c.putInt("unrest", (int) Math.round(Dissent.amplifier(f) * 100));
            c.putBoolean("elected", !Government.appointed(f));
            l.add(c);
        }
        return l;
    }

    static CompoundTag policy(ServerLevel level, UUID id) {
        CompoundTag t = new CompoundTag();
        Policy.Stance s = Policy.of(id);
        if (s != null) {
            t.putString("who", s.who());
            t.putInt("tax", s.tax());
            t.putString("taxWords", Policy.taxWords(s.tax()));
            t.putString("taxWhy", s.taxWhy());
            t.putString("build", Policy.buildWords(s.build()));
            t.putString("buildWhy", s.buildWhy());
            t.putInt("wage", s.wage());
            t.putString("wageWords", Policy.wageWords(s.wage()));
            t.putString("wageWhy", s.wageWhy());
            t.putString("style", s.style());
        } else if (PlayerLeader.leaderId(id) != null) {
            t.putString("player", PlayerLeader.leaderName(id));
        }
        String since = Ledger.note(id, "policy.since");
        if (since != null && !since.isEmpty()) t.putLong("since", Long.parseLong(since));
        t.put("history", strings(Policy.history(id)));
        Orders.Order o = Orders.current(id);
        t.putString("order", o == null ? "" : o.title + ": " + o.words);
        t.putString("laws", LawBook.notice(id, 9));
        t.putString("taxes", TaxBook.summary(id));
        String chosen = Council.chosen(id);
        t.putString("buildingNext", chosen == null ? "" : Policy.buildWords(chosen));
        return t;
    }

    static CompoundTag election(ServerLevel level, Villages.Village v, @Nullable ServerPlayer p, long day) {
        UUID id = v.id();
        CompoundTag t = new CompoundTag();
        boolean holds = Government.holdsElections(id);
        t.putBoolean("holds", holds);
        t.putString("office", Government.title(id));
        if (!holds) {
            List<String> lines = new ArrayList<>();
            for (String s : Government.board(id)) lines.add(s.contains("|") ? s.substring(s.indexOf('|') + 1) : s);
            t.put("instead", strings(lines));
        }
        Elections.Campaign c = Elections.campaign(id);
        long t24 = level.getDayTime() % 24000L;
        String state = c == null || c.counted || c.candidates.isEmpty() ? "none" : c.voteDay == day ? (t24 >= Elections.CLOSE ? "closed" : t24 >= Elections.OPEN ? "voting" : "today") : "called";
        if (Polls.counting(id)) state = "counting";
        t.putString("state", state);
        t.putLong("next", holds ? Elections.nextVote(id, day) : -1);
        if (c != null && !c.counted) {
            t.putLong("voteDay", c.voteDay);
            t.putInt("voted", c.ballots.size());
            t.putInt("voters", Elections.voters(id).size() + Polls.players(c));
            t.putInt("queue", Polls.queued(id));
            ListTag cands = new ListTag();
            Map<UUID, Integer> posters = Polls.posters(id);
            int i = 0;
            for (Elections.Candidate k : c.candidates) {
                CompoundTag ct = new CompoundTag();
                ct.putString("id", k.id().toString());
                ct.putString("name", k.name());
                ct.putString("cares", k.platform().cares);
                ct.putString("type", k.platform().type);
                ct.putString("pledge", k.pledge());
                Factions.Party party = Government.of(id) == Government.Form.DEMOCRACY ? Factions.partyOfCandidate(id, k.id()) : null;
                ct.putString("party", party == null ? (Hustings.isPlayer(id, k.id()) ? "Independent" : "") : party.words);
                ct.putInt("colour", Polls.colourOf(id, k, i++).getTextColor());
                ct.putInt("posters", posters.getOrDefault(k.id(), 0));
                ct.putBoolean("player", Hustings.isPlayer(id, k.id()));
                VillageFolkEntity f = Elections.loaded(id, k.id());
                if (f != null) ct.putString("nature", Values.describe(f));
                long at = 3000L + (i - 1) * 2200L;
                ct.putString("rally", c.voteDay > day ? "speaks in the square at " + clock(at) + " each campaign day" : "");
                cands.add(ct);
            }
            t.put("candidates", cands);
            if (p != null) {
                UUID voted = c.ballots.get(p.getUUID());
                if (voted != null) {
                    Elections.Candidate k = Elections.find(c, voted);
                    t.putString("youVoted", k == null ? "a candidate" : k.name());
                }
            }
        }
        BlockPos box = Polls.station(level, v, false);
        if (box != null) t.put("station", new IntArrayTag(new int[]{ box.getX(), box.getY(), box.getZ() }));
        BlockPos speech = Polls.speechSpot(level, v);
        t.put("speech", new IntArrayTag(new int[]{ speech.getX(), speech.getY(), speech.getZ() }));
        // The last result, for the chart.
        List<String[]> rows = Polls.lastRows(id);
        if (!rows.isEmpty()) {
            String[] head = rows.get(0);
            CompoundTag last = new CompoundTag();
            last.putLong("day", Fame.number(head[0]));
            last.putString("winner", head[1]);
            last.putString("how", head[2]);
            last.putInt("voted", Fame.number(head[3]));
            last.putInt("voters", Fame.number(head[4]));
            last.putString("office", head[5]);
            ListTag lr = new ListTag();
            for (int i = 1; i < rows.size(); i++) {
                String[] x = rows.get(i);
                if (x.length < 3) continue;
                CompoundTag one = new CompoundTag();
                one.putString("name", x[0]);
                one.putInt("votes", Fame.number(x[1]));
                net.minecraft.world.item.DyeColor d = net.minecraft.world.item.DyeColor.byName(x[2], net.minecraft.world.item.DyeColor.GRAY);
                one.putInt("colour", d.getTextColor());
                Factions.Party party = x.length > 3 ? Factions.Party.named(x[3]) : null;
                one.putString("party", party == null ? "" : party.words);
                lr.add(one);
            }
            last.put("rows", lr);
            t.put("last", last);
        }
        return t;
    }

    static String clock(long t) {
        long h = (t / 1000 + 6) % 24, m = (t % 1000) * 60 / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", h, m);
    }

    static CompoundTag parties(UUID id) {
        CompoundTag t = new CompoundTag();
        boolean democracy = Government.of(id) == Government.Form.DEMOCRACY;
        t.putBoolean("democracy", democracy);
        ListTag fol = new ListTag();
        for (Map.Entry<Factions.Party, Integer> e : Factions.following(id).entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("party", e.getKey().words);
            c.putInt("n", e.getValue());
            c.putInt("colour", e.getKey().colour.getTextColor());
            c.putString("pledge", e.getKey().pledge);
            c.putString("tax", e.getKey().tax > 0 ? "for higher taxes" : e.getKey().tax < 0 ? "for lower taxes" : "open on taxes");
            c.putString("build", Policy.buildWords(e.getKey().build));
            fol.add(c);
        }
        t.put("following", fol);
        ListTag seats = new ListTag();
        LinkedHashMap<Factions.Party, Integer> s = Factions.seats(id);
        LinkedHashMap<Factions.Party, Integer> pv = Factions.partyVotes(id);
        List<Factions.Party> gov = Factions.governing(id);
        int total = 0;
        for (Map.Entry<Factions.Party, Integer> e : s.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("party", e.getKey().words);
            c.putInt("seats", e.getValue());
            c.putInt("votes", pv.getOrDefault(e.getKey(), 0));
            c.putInt("colour", e.getKey().colour.getTextColor());
            c.putBoolean("governing", gov.contains(e.getKey()));
            seats.add(c);
            total += e.getValue();
        }
        t.put("seats", seats);
        t.putInt("total", total);
        ListTag members = new ListTag();
        for (VillageFolkEntity f : Factions.memberFolk(id)) {
            CompoundTag c = new CompoundTag();
            c.putString("name", f.displayNameCap());
            Factions.Party p = Factions.partyOf(f);
            c.putString("party", p.shortName);
            c.putInt("colour", p.colour.getTextColor());
            members.add(c);
        }
        t.put("members", members);
        if (gov.size() > 1) t.putString("coalition", gov.get(0).words + " governs with " + gov.get(1).words);
        else if (gov.size() == 1) t.putString("coalition", gov.get(0).words + " governs alone, with a majority");
        return t;
    }

    static CompoundTag unrest(UUID id, Government.Form form, @Nullable ServerPlayer p) {
        CompoundTag t = new CompoundTag();
        int l = Dissent.level(id);
        t.putInt("level", l);
        t.putString("stage", Dissent.stage(id).words);
        t.putInt("amp", (int) Math.round(Dissent.amplifier(form) * 100));
        t.putString("why", Dissent.why(id));
        t.putBoolean("capped", Dissent.cap(form) < 100);
        t.putString("wanted", Dissent.wanted(id).words);
        String[] pet = Dissent.petition(id);
        if (pet != null) {
            CompoundTag c = new CompoundTag();
            c.putString("asks", pet[0]);
            c.putInt("signed", Fame.number(pet[1]));
            c.putInt("needed", Fame.number(pet[2]));
            c.putString("by", pet[3]);
            if (p != null) c.putBoolean("youSigned", Dissent.signers(id).contains(p.getUUID().toString()));
            t.put("petition", c);
        }
        List<int[]> h = Dissent.historyOf(id);
        int[] hv = new int[h.size()];
        for (int i = 0; i < hv.length; i++) hv[i] = h.get(i)[1];
        t.putIntArray("history", hv);
        t.put("log", strings(Dissent.logOf(id)));
        String next = l < Dissent.GRUMBLE ? "At 25 folk start to grumble; at 45 a petition goes round."
            : l < Dissent.PETITION ? "At 45 a petition goes round, and is put to the " + Government.title(id) + " once a third of the town signs."
            : l < Dissent.PROTEST ? "At 60 folk protest in the square."
            : l < Dissent.STRIKE ? (Dissent.cap(form) < 100 ? "Here it goes no further: protests bring the election forward." : "At 75, refused, the town strikes.")
            : l < Dissent.UPRISING ? "At 90, two mornings running, the town rises." : "The town is on the edge of revolt.";
        t.putString("next", next);
        t.putInt("tax", TaxUnrest.level(id));
        return t;
    }

    static ListTag relations(UUID id) {
        ListTag l = new ListTag();
        for (String[] r : Ideology.relations(id)) {
            CompoundTag c = new CompoundTag();
            c.putString("name", r[0]);
            c.putString("form", r[1]);
            c.putString("terms", r[2]);
            c.putInt("relation", Integer.parseInt(r[3]));
            c.putString("note", r[4]);
            c.putBoolean("embargo", "1".equals(r[5]));
            Government.Form f = Government.Form.named(r[6]);
            c.putInt("colour", f == null ? 0x888888 : Regimes.colour(f).getTextColor());
            l.add(c);
        }
        return l;
    }

    /** What this player may do here, each a line, and why not where it may not. */
    static CompoundTag you(ServerLevel level, Villages.Village v, @Nullable ServerPlayer p, Government.Form form) {
        CompoundTag t = new CompoundTag();
        if (p == null) return t;
        UUID id = v.id();
        List<String> can = new ArrayList<>(), cannot = new ArrayList<>();
        boolean citizen = Citizens.is(id, p.getUUID());
        String vote = GovActions.whyNotVote(level, v, p);
        if (vote == null) can.add("Vote in this election: at the ballot box or here.");
        else cannot.add("Vote: " + vote);
        String stand = Government.holdsElections(id) ? standWhy(level, id, p) : Government.standBarred(level, id, p);
        if (stand == null) can.add("Stand for " + Government.title(id) + " (a citizen the town counts a friend).");
        else cannot.add("Stand for " + Government.title(id) + ": " + stand);
        String biz = Regimes.businessBarred(id);
        if (biz == null) can.add("Open a business: the board's Start a business button, or the bank.");
        else cannot.add(biz);
        if (LawBook.value(id, LawBook.Law.HOUSES) == 2) cannot.add("Own a house: nobody does here; the houses are the town's.");
        else if (LawBook.value(id, LawBook.Law.HOUSES) == 1 && !citizen) cannot.add("Own a house: citizens only. Ask to live here first.");
        else can.add("Own a house.");
        if (form == Government.Form.PLANNED) can.add("Work for the state: take a shift on the jobs board; the state pays every hand the same.");
        if (form == Government.Form.JUNTA) cannot.add("Gather a crowd or stay out after dark: the general's curfew.");
        if (Dissent.petition(id) != null) {
            if (citizen) can.add("Sign the petition (" + Dissent.petition(id)[0] + ").");
            else cannot.add("Sign the petition: citizens only.");
        }
        t.put("can", strings(can));
        t.put("cannot", strings(cannot));
        t.putBoolean("citizen", citizen);
        Hustings.Stand s = Hustings.of(id, p.getUUID());
        t.putBoolean("standing", s != null && !s.out());
        if (s != null && !s.out()) {
            List<String> made = new ArrayList<>();
            for (Pledges.Pledge pl : s.pledges()) made.add(pl.words(id));
            t.put("pledges", strings(made));
        }
        ListTag offered = new ListTag();
        for (Pledges.Pledge pl : Pledges.offered(level, id)) {
            CompoundTag c = new CompoundTag();
            c.putString("key", pl.key());
            c.putString("words", pl.words(id));
            c.putString("cares", pl.value().cares);
            offered.add(c);
        }
        t.put("offered", offered);
        t.putString("name", p.getName().getString());
        return t;
    }

    /** Why this player may not stand at this town's election, or null. */
    @Nullable
    static String standWhy(ServerLevel level, UUID id, ServerPlayer p) {
        if (PlayerLeader.leads(id, p.getUUID())) return null;
        if (!Citizens.is(id, p.getUUID())) return "only a citizen may stand. Ask any folk if you may live here.";
        String barred = Government.standBarred(level, id, p);
        if (barred != null) return barred;
        Standing.View view = Standing.of(id, p.getUUID(), level.getGameTime());
        if (!view.title().atLeast(Standing.Title.FRIEND)) return "the town hardly knows you yet (you are " + view.title().words + "). Be a friend to it first.";
        if (Laws.owes(id, p.getUUID()) > 0) return "not while you owe the town. Settle up first.";
        return null;
    }

    static ListTag strings(List<String> lines) {
        ListTag l = new ListTag();
        for (String s : lines) l.add(StringTag.valueOf(s.length() > 400 ? s.substring(0, 400) : s));
        return l;
    }
}
