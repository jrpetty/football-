package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StocksBlock;
import com.jrpetty.mcassistant.entity.Crime.Case;
import com.jrpetty.mcassistant.entity.Crime.Clue;
import com.jrpetty.mcassistant.entity.Crime.Kind;
import com.jrpetty.mcassistant.entity.Crime.Stage;
import com.jrpetty.mcassistant.entity.Crime.Statement;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [crime] The court, and what follows a verdict (Crime).
 *
 * <p>The morning after the watch names somebody, in the hours the leader holds court, the council sits: at the hall
 * if the town has one, else on the square. The leader (or, if it is the accused or the victim, the first councillor
 * who is neither) presides; the accused, the investigator, the victim and the witnesses who spoke are called, and the
 * council stands by. Once the accused is in the dock (or after a while, whoever has come), it is heard a line at a
 * time: the charge, what the watch found, what the witnesses saw, the victim, the accused (who denies it, or owns
 * up). Each councillor weighs the evidence (a friend of the accused wants more of it, a record counts against it),
 * and the verdict goes by their votes.
 *
 * <p>The sentence by the offence and the record: a first theft is paid back with a fine on top; a first vandal mends
 * what it broke, at its own cost, and sweeps the streets till sundown; a second offence (or a forger or smuggler's
 * first) sits in the stocks on the square till sundown, the town putting up the stocks out of its stores' timber if it
 * has none; a third is banished. The victim is made whole out of the culprit's purse, or with its own things back,
 * and what cannot be paid now is paid out of its wages as it earns. Everybody thinks the less of it, the victim most;
 * some turn over a new leaf. Acquitted, the accused is cleared, and remembers who named it; the watch looks again.
 */
final class Trial {

    private Trial() {}

    /** How long the court waits for the accused to come (ticks), and between its lines. */
    static final int GATHER = 1200, LINE = 70;
    /** The evidence a councillor wants before it finds guilty. */
    static final double GUILTY = 4.5;
    /** Where the town keeps its stocks (Ledger note): "x,y,z,facing". */
    static final String STOCKS = "crime.stocks";

    /** A sitting of the court on one case. */
    static final class Sitting {
        final int caseId;
        final UUID village;
        final BlockPos at, dock;
        final Direction faces;
        final boolean hall;
        final long opened;
        @Nullable UUID judge;
        String judgeName = "the council";
        final List<UUID> called = new ArrayList<>();
        /** Who says each line ("" for the judge) and the words. */
        final List<String[]> lines = new ArrayList<>();
        int next;
        long nextAt;
        boolean heard, guilty;
        int ayes, noes;
        Sentence sentence = Sentence.FINE;
        String sentenceWords = "";

        Sitting(int caseId, UUID village, BlockPos at, BlockPos dock, Direction faces, boolean hall, long opened) {
            this.caseId = caseId;
            this.village = village;
            this.at = at;
            this.dock = dock;
            this.faces = faces;
            this.hall = hall;
            this.opened = opened;
        }
    }

    enum Sentence { FINE, WORK, STOCKS, BANISH }

    private static final Map<UUID, Sitting> SITTINGS = new ConcurrentHashMap<>();
    /** Folk sat in the stocks just now: when they were last set down there. */
    private static final Map<UUID, Long> SEATED = new ConcurrentHashMap<>();
    /** A sweeper on its community work: the stop it is making for, and since when it has stood at it. */
    private static final Map<UUID, long[]> SWEEP = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PATHED = new ConcurrentHashMap<>();

    static void resetForTests() {
        SITTINGS.clear();
        SEATED.clear();
        SWEEP.clear();
        PATHED.clear();
        STOPS.clear();
        STOPS_AT.clear();
    }

    // ------------------------------------------------------------------ the round

    /** The round (Crime.tick): a sitting opened for the oldest case awaiting trial, the sentenced seen to, the stocks jeered. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        // A sitting is not kept over a restart: a case left before the council goes back to await it.
        Sitting sat = SITTINGS.get(id);
        for (Case c : Crime.open(id)) {
            if (c.stage == Stage.TRIAL && (sat == null || sat.caseId != c.id)) {
                c.stage = Stage.ACCUSED;
                Crime.changed();
            }
        }
        if (!SITTINGS.containsKey(id) && courtHours(level)) {
            for (Case c : Crime.open(id)) {
                if (c.stage != Stage.ACCUSED) continue;
                open(level, v, c);
                break;
            }
        }
        jeers(level, v);
    }

    /** The hours the court sits: the leader's morning (not the assembly), into the afternoon. Any time, hurried. */
    static boolean courtHours(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        return Crime.hurry || t >= 2000L && t < 10000L;
    }

    /** Every second: each sitting's next line, and at the end its verdict carried out. */
    static void sittings(ServerLevel level) {
        for (Sitting s : SITTINGS.values()) {
            Villages.Village v = Villages.get(s.village);
            if (v == null || !v.dim().equals(level.dimension())) continue;
            run(level, v, s);
        }
    }

    /** The court called: where it sits, who presides, who is called. */
    static Sitting open(ServerLevel level, Villages.Village v, Case c) {
        long day = level.getDayTime() / 24000L;
        Ledger.Building hall = Court.hall(v.id());
        BlockPos at, dock;
        Direction faces;
        boolean inHall = hall != null && !Ledger.raising(v.id(), hall.anchor()) && level.isLoaded(hall.anchor());
        if (inHall) {
            at = Court.seat(hall);
            dock = hall.anchor().relative(hall.facing(), 2).above();
            faces = hall.facing();
        } else {
            BlockPos heart = v.centre();
            at = ground(level, heart.relative(Direction.NORTH, 4));
            dock = ground(level, heart.relative(Direction.SOUTH, 1));
            faces = Direction.NORTH;
        }
        Sitting s = new Sitting(c.id, v.id(), at, dock, faces, inHall, level.getGameTime());
        UUID elder = Villages.elder(v.id());
        if (elder != null && !elder.equals(c.accused) && !elder.equals(c.victim) && Civics.find(level, elder) != null) {
            s.judge = elder;
        } else {
            for (VillageFolkEntity m : Council.members(v.id())) {
                if (m.getUUID().equals(c.accused) || m.getUUID().equals(c.victim)) continue;
                s.judge = m.getUUID();
                break;
            }
        }
        if (s.judge != null) {
            s.judgeName = Mischief.nameOf(level, v.id(), s.judge);
            s.called.add(s.judge);
        }
        if (c.accused != null) s.called.add(c.accused);
        if (c.investigator != null && !s.called.contains(c.investigator)) s.called.add(c.investigator);
        if (c.victim != null && !s.called.contains(c.victim)) s.called.add(c.victim);
        int witnesses = 0;
        for (Statement st : c.statements) {
            if (st.player || witnesses >= 3 || s.called.contains(st.from)) continue;
            if (st.named == null && st.outfit.isEmpty()) continue;
            s.called.add(st.from);
            witnesses++;
        }
        int councillors = 0;
        for (VillageFolkEntity m : Council.members(v.id())) {
            if (councillors >= 3 || s.called.contains(m.getUUID())) continue;
            s.called.add(m.getUUID());
            councillors++;
        }
        SITTINGS.put(v.id(), s);
        c.stage = Stage.TRIAL;
        c.note(day, "Before the council " + (inHall ? "at the hall" : "on the square") + ", " + s.judgeName + " presiding.");
        VillageFolkEntity judge = s.judge == null ? null : Civics.find(level, s.judge);
        if (judge != null) FolkTalk.speak(judge, "The council will sit " + (inHall ? "at the hall" : "on the square") + ": " + c.accusedName + ", for "
            + c.kind.word + ".");
        Crime.changed();
        return s;
    }

    private static BlockPos ground(ServerLevel level, BlockPos p) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p);
    }

    /** A sitting a step on: gathered, heard line by line, and its verdict carried out. */
    private static void run(ServerLevel level, Villages.Village v, Sitting s) {
        Case c = Crime.get(s.caseId);
        if (c == null || c.stage != Stage.TRIAL || c.accused == null) {
            SITTINGS.remove(s.village, s);
            return;
        }
        long gt = level.getGameTime(), day = level.getDayTime() / 24000L;
        VillageFolkEntity accused = Civics.find(level, c.accused);
        if (accused == null) {
            boolean still = false;
            for (AssistantEntity a : Villages.folkOf(v.id())) if (a.getUUID().equals(c.accused)) still = true;
            if (!still) {
                c.note(day, c.accusedName + " is gone from the town; the case cannot be tried.");
                c.stage = Stage.UNSOLVED;
                c.closedDay = day;
                c.verdict = "never tried: " + c.accusedName + " is gone";
                SITTINGS.remove(s.village, s);
                returnEvidence(level, v, c);
                Crime.closed(c, false);
                Crime.changed();
            } else if (gt - s.opened > 24000L) {
                c.stage = Stage.ACCUSED;                 // adjourned till it can be found
                SITTINGS.remove(s.village, s);
            }
            return;
        }
        if (!s.heard) {
            VillageFolkEntity judge = s.judge == null ? null : Civics.find(level, s.judge);
            boolean ready = Mischief.near(accused, s.dock, 4.0) && (judge == null || Mischief.near(judge, s.at, 4.0));
            if (ready || gt - s.opened > (Crime.hurry ? 300 : GATHER)) {
                s.heard = true;
                script(level, v, c, s, accused);
                s.nextAt = gt;
            }
            return;
        }
        if (gt < s.nextAt) return;
        if (s.next < s.lines.size()) {
            String[] line = s.lines.get(s.next++);
            VillageFolkEntity who = line[0].isEmpty() ? null : Civics.find(level, UUID.fromString(line[0]));
            VillageFolkEntity judge = s.judge == null ? null : Civics.find(level, s.judge);
            VillageFolkEntity speaker = who != null ? who : judge != null ? judge : accused;
            FolkTalk.speak(speaker, line[1]);
            s.nextAt = gt + Crime.pause(LINE);
            return;
        }
        SITTINGS.remove(s.village, s);
        if (s.guilty) convict(level, v, c, accused, s);
        else acquit(level, v, c, accused, s);
    }

    /**
     * What is said at the sitting, and the verdict: the charge, the watch's evidence, the witnesses, the victim, the
     * accused; each councillor's vote by the evidence's weight (more wanted against a friend; a record against it).
     */
    static void script(ServerLevel level, Villages.Village v, Case c, Sitting s, VillageFolkEntity accused) {
        String name = accused.displayNameCap();
        s.lines.add(line(null, "The council sits. " + name + ", you stand accused of " + c.kind.word + " at " + c.place + " on day " + c.day + ": "
            + c.what() + "."));
        List<Clue> against = new ArrayList<>();
        for (Clue k : c.clues) if (k.points.contains(accused.getUUID()) && k.weight > 0) against.add(k);
        against.sort((a, b) -> Double.compare(b.weight, a.weight));
        String found = against.isEmpty() ? "The evidence is thin, I'll own." : against.get(0).text() + (against.size() > 1 ? " " + against.get(1).text() : "");
        if (c.investigator != null) s.lines.add(line(c.investigator, "I looked into it. " + found));
        else s.lines.add(line(null, "Nobody from the watch looked into it: the council has the charge and no more."));
        int spoke = 0;
        for (Statement st : c.statements) {
            if (spoke >= 3) break;
            if (st.from.equals(accused.getUUID())) continue;
            if (!accused.getUUID().equals(st.named) && (st.outfit.isEmpty() || !accused.stationTask().name().equals(st.outfit))) continue;
            s.lines.add(st.player ? line(null, st.fromName + " told the watch: \"" + st.text + "\"") : line(st.from, st.text));
            spoke++;
        }
        if (c.victim != null) s.lines.add(line(c.victim, FolkTalk.pick(level.getRandom(), "I want what's mine back, that's all.",
            "I just want to feel safe in my own town again.")));
        double weight = Inquiry.against(c, accused.getUUID());
        boolean did = accused.getUUID().equals(c.culprit);
        int honesty = Mischief.honesty(accused);
        boolean ownUp = did && (c.confessed || weight >= 6.0 && honesty >= 40 || honesty >= 65);
        if (ownUp) {
            c.confessed = true;
            weight = Math.max(weight, 8.0);
            s.lines.add(line(accused.getUUID(), FolkTalk.pick(accused.getRandom(), "It was me. I'm sorry. I'll make it right.",
                "I did it. I've no excuse — I was desperate.")));
        } else {
            s.lines.add(line(accused.getUUID(), did ? FolkTalk.pick(accused.getRandom(), "I never did it! Whoever says so is lying.", "You can't prove a thing.")
                : FolkTalk.pick(accused.getRandom(), "I never did it! I wasn't even there!", "On my life, it wasn't me.")));
        }
        int priors = Crime.convictions(accused.getUUID());
        List<VillageFolkEntity> council = new ArrayList<>();
        for (VillageFolkEntity m : Council.members(v.id())) {
            if (m.getUUID().equals(c.accused) || m.getUUID().equals(c.victim)) continue;
            council.add(m);
        }
        int ayes = 0, noes = 0;
        // The weight already counts a record against it (Inquiry.score); a councillor who is fond of the accused wants more.
        if (council.isEmpty()) {
            if (weight >= GUILTY) ayes = 1; else noes = 1;
        }
        for (VillageFolkEntity m : council) {
            double lean = weight - m.life().affinity(accused.getUUID()) / 30.0;
            if (lean >= GUILTY) ayes++;
            else noes++;
        }
        s.ayes = ayes;
        s.noes = noes;
        s.guilty = ayes > noes;
        if (s.guilty) {
            s.sentence = sentence(level, v, c, priors);
            s.sentenceWords = words(s.sentence, c, level);
            s.lines.add(line(null, "The council has heard it. Guilty, " + ayes + " to " + noes + ". " + s.sentenceWords));
        } else {
            s.lines.add(line(null, "The council finds you not guilty, " + noes + " to " + ayes + ". You're cleared, " + name + ", and free to go."));
        }
        c.note(level.getDayTime() / 24000L, "The council weighed the evidence against " + name + " at " + String.format(Locale.ROOT, "%.1f", weight)
            + " and voted " + ayes + " to " + noes + (s.guilty ? " to convict." : " to clear."));
    }

    private static String[] line(@Nullable UUID who, String text) {
        return new String[]{ who == null ? "" : who.toString(), text };
    }

    /** The sentence by the offence and the record. */
    static Sentence sentence(ServerLevel level, Villages.Village v, Case c, int priors) {
        if (priors >= 2) return Sentence.BANISH;
        if (priors == 1 || c.kind.grave() || c.worth >= 10) return stocksAt(level, v) != null || canMake(level, v) ? Sentence.STOCKS : Sentence.WORK;
        if (c.kind == Kind.VANDALISM) return Sentence.WORK;
        return Sentence.FINE;
    }

    static int fine(Case c) {
        return Math.max(2, c.worth / 2 + 1);
    }

    static String words(Sentence s, Case c, ServerLevel level) {
        String till = level.getDayTime() % 24000L >= 8000L ? "till sundown tomorrow" : "till sundown";
        String back = c.kind == Kind.VANDALISM ? "" : "You'll pay back what was taken";
        return switch (s) {
            case FINE -> back + " and a fine of " + fine(c) + " coins to the town.";
            case WORK -> (c.kind == Kind.VANDALISM ? "You'll mend what you broke, at your own cost, and sweep the streets " : back + ", and work for the town, sweeping its streets, ")
                + till + ".";
            case STOCKS -> (back.isEmpty() ? "You'll pay for the damage" : back) + ", and sit in the stocks on the square " + till + ".";
            case BANISH -> "This is your third time before us. You'll pay back what you can, and leave " + Villages.name(c.village) + " for good.";
        };
    }

    // ------------------------------------------------------------------ guilty

    /** Guilty: the victim made whole, the fine, the sentence begun; the town told; the record written; perhaps a new leaf. */
    static void convict(ServerLevel level, Villages.Village v, Case c, VillageFolkEntity f, Sitting s) {
        long now = level.getDayTime(), day = now / 24000L;
        UUID id = f.getUUID();
        String name = f.displayNameCap();
        CompoundTag r = Crime.folk(id);
        int priors = r.getInt("convictions");
        r.putInt("convictions", priors + 1);
        r.putLong("lastConvicted", day);
        r.putString("lastCrime", c.kind.word);
        r.putLong("shamedDay", day);
        String paid = restitution(level, v, c, f);
        Sentence sentence = s.sentence;
        String done = paid;
        switch (sentence) {
            case FINE -> {
                int fine = fine(c);
                int took = Math.min(f.purse(), fine);
                if (took > 0 && f.spend(took)) Ledger.addCoins(v.id(), took);
                if (fine - took > 0) owe(f, null, "the town", fine - took);
                done += (done.isEmpty() ? "" : "; ") + "fined " + fine + (fine - took > 0 ? " (" + (fine - took) + " owed)" : "");
            }
            case STOCKS -> {
                r.putString("sentence", "stocks");
                r.putLong("until", until(now));
                r.putInt("sentenceCase", c.id);
                done += (done.isEmpty() ? "" : "; ") + "the stocks";
            }
            case WORK -> {
                r.putString("sentence", "work");
                r.putLong("until", until(now));
                r.putInt("sentenceCase", c.id);
                done += (done.isEmpty() ? "" : "; ") + (c.kind == Kind.VANDALISM ? "to mend it and sweep the streets" : "community work");
            }
            case BANISH -> done += (done.isEmpty() ? "" : "; ") + "banished";
        }
        c.stage = Stage.CONVICTED;
        c.closedDay = day;
        c.verdict = "guilty, " + s.ayes + " to " + s.noes + ": " + name;
        c.sentence = done;
        c.note(day, "Guilty, " + s.ayes + " to " + s.noes + ". " + Mischief.capital(done) + ".");
        f.persona().remember(day, "I was found guilty of " + c.kind.word + " before the council", -6);
        // What the town thinks of it now.
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity o) || o == f) continue;
            boolean partner = id.equals(o.life().partner());
            int close = o.life().affinity(id);
            int sting = o.getUUID().equals(c.victim) ? 20 : partner ? 0 : close >= Social.CLOSE ? 3 : 8;
            if (sting > 0) o.life().feel(id, name, -sting);
        }
        if (c.investigator != null) {
            CompoundTag g = Crime.folk(c.investigator);
            g.putInt("solved", g.getInt("solved") + 1);
            VillageFolkEntity victim = c.victim == null ? null : Civics.find(level, c.victim);
            if (victim != null) victim.life().feel(c.investigator, c.investigatorName, 4);
        }
        Villages.tell(v.id(), day, name + " was tried for " + c.kind.word + " at " + c.place + " and found guilty: " + done);
        returnEvidence(level, v, c);
        if (c.confessed && id.equals(c.culprit)) ownsUpToTheRest(level, v, c, f, day);
        if (sentence == Sentence.BANISH) banish(level, v, c, f, day);
        else reform(level, v, f, priors, day);
        f.refreshMood();
        Crime.closed(c, id.equals(c.culprit));
        Crime.changed();
    }

    /** Sundown today, or (sentenced late in the day) tomorrow's. */
    static long until(long now) {
        long day = now / 24000L, t = now % 24000L;
        return (t >= 8000L ? day + 1 : day) * 24000L + 12000L;
    }

    /**
     * The victim made whole: its own things back out of the culprit's pack or chest; what is not found, and a purse's
     * coins, out of the culprit's purse; the rest owed, paid out of its wages as it earns. Returns what was done, in words.
     */
    static String restitution(ServerLevel level, Villages.Village v, Case c, VillageFolkEntity f) {
        List<String> said = new ArrayList<>();
        VillageFolkEntity victim = c.victim == null ? null : Civics.find(level, c.victim);
        int back = 0;
        if (!c.goodsId.isEmpty()) {
            List<ItemStack> found = takeMarked(level, v, f, c.id);
            for (ItemStack s : found) {
                Crime.mark(s, Crime.STOLEN, 0);
                back += s.getCount();
                if (c.kind == Kind.FORGERY) {
                    Crafts.store(level, v, s);                 // the watch hands the forged coins in to the stores, out of harm's way
                } else if (victim != null) {
                    BlockPos chest = Mischief.chestOf(level, victim);
                    ItemStack left = chest != null && level.getBlockEntity(chest) instanceof Container box ? Mischief.into(box, s) : s;
                    if (!left.isEmpty()) left = victim.insertItem(left);
                    if (!left.isEmpty()) Crafts.store(level, v, left);
                } else {
                    Crafts.store(level, v, s);
                }
            }
            if (back > 0 && c.kind != Kind.FORGERY) said.add(c.goods + " returned to " + c.victimName);
            if (back > 0 && c.kind == Kind.FORGERY) said.add(back + " forged coins taken");
        }
        int owed;
        if (c.kind == Kind.PICKPOCKET) owed = c.coins;
        else if (c.kind == Kind.VANDALISM) owed = 0;                       // the mending is paid for as it is done
        else if (c.kind == Kind.FORGERY) owed = c.worth;
        else owed = c.goodsCount <= 0 ? 0 : (int) Math.ceil(c.worth * Math.max(0, c.goodsCount - back) / (double) c.goodsCount);
        if (owed > 0) {
            int took = Math.min(f.purse(), owed);
            if (took > 0 && f.spend(took)) {
                if (victim != null) victim.earn(took);
                else Ledger.addCoins(v.id(), took);
            }
            said.add(took + (took == 1 ? " coin" : " coins") + " paid back to " + c.victimName);
            if (owed - took > 0) {
                owe(f, c.victim, c.victimName, owed - took);
                said.add((owed - took) + " more owed");
            }
            if (victim != null && took >= owed) Crime.folk(victim.getUUID()).putLong("repaidDay", level.getDayTime() / 24000L);
        } else if (back > 0 && victim != null) {
            Crime.folk(victim.getUUID()).putLong("repaidDay", level.getDayTime() / 24000L);
        }
        if (victim != null) victim.refreshMood();
        return String.join(", ", said);
    }

    /** Everything marked as this case's, out of the culprit's pack and its home chest. */
    static List<ItemStack> takeMarked(ServerLevel level, Villages.Village v, VillageFolkEntity f, int caseId) {
        List<ItemStack> out = new ArrayList<>();
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            if (Crime.stolenCase(pack.get(i)) != caseId) continue;
            out.add(pack.get(i).copy());
            pack.set(i, ItemStack.EMPTY);
        }
        BlockPos chest = Mischief.chestOf(level, f);
        if (chest != null && level.getBlockEntity(chest) instanceof Container box) {
            for (int i = 0; i < box.getContainerSize(); i++) {
                if (Crime.stolenCase(box.getItem(i)) != caseId) continue;
                out.add(box.getItem(i).copy());
                box.setItem(i, ItemStack.EMPTY);
            }
            box.setChanged();
        }
        return out;
    }

    /**
     * A debt on its record, paid back out of its purse each morning (Trial.debts): to the one it robbed ("owes", first),
     * or to the town, a fine or the cost of what it mended ("owesTown").
     */
    static void owe(VillageFolkEntity f, @Nullable UUID to, String toName, int coins) {
        CompoundTag r = Crime.folk(f.getUUID());
        if (to == null) {
            r.putInt("owesTown", r.getInt("owesTown") + coins);
            return;
        }
        r.putInt("owes", r.getInt("owes") + coins);
        r.putUUID("owesTo", to);
        r.putString("owesToName", toName);
    }

    /** Each morning: what is owed, paid back out of whatever is in the purse, the one it robbed before the town. */
    static void debts(ServerLevel level, Villages.Village v, long day) {
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !Crime.known(f.getUUID())) continue;
            CompoundTag r = Crime.folk(f.getUUID());
            int owes = r.getInt("owes");
            if (owes > 0 && f.purse() > 0) {
                int pay = Math.min(owes, f.purse());
                VillageFolkEntity to = r.hasUUID("owesTo") ? Civics.find(level, r.getUUID("owesTo")) : null;
                if (f.spend(pay)) {
                    if (to != null) to.earn(pay);
                    else Ledger.addCoins(v.id(), pay);          // gone from the town: what it is owed goes to the town's poor and all
                    r.putInt("owes", owes - pay);
                    if (owes - pay == 0) {
                        if (to != null) Crime.folk(to.getUUID()).putLong("repaidDay", day);
                        f.persona().remember(day, "I paid back the last of what I owed " + r.getString("owesToName"), 3);
                    }
                }
            }
            int town = r.getInt("owesTown");
            if (town > 0 && f.purse() > 0) {
                int pay = Math.min(town, f.purse());
                if (f.spend(pay)) {
                    Ledger.addCoins(v.id(), pay);
                    r.putInt("owesTown", town - pay);
                }
            }
            Crime.changed();
        }
    }

    /** What was dropped at the scene, back to whose it is (or the stores) once the case is closed. */
    static void returnEvidence(ServerLevel level, Villages.Village v, Case c) {
        ItemStack s = Inquiry.CompoundTagHolder.load(level, c.evidence);
        if (s.isEmpty() && c.droppedEntity != null && level.getEntity(c.droppedEntity) instanceof ItemEntity e && e.isAlive()) {
            s = e.getItem().copy();
            e.discard();
        }
        c.evidence = new CompoundTag();
        if (s.isEmpty()) return;
        Crime.mark(s, Crime.CLUE, 0);
        UUID owner = c.droppedOwner != null ? c.droppedOwner : c.culprit;
        VillageFolkEntity o = Civics.find(level, owner);
        if (o != null) s = o.insertItem(s);
        if (!s.isEmpty()) Crafts.store(level, v, s);
    }

    /**
     * It owned up: and to what else it did, the cases nobody solved and any for which somebody else was wrongly
     * convicted (who is cleared, and its fine given back out of the treasury).
     */
    private static void ownsUpToTheRest(ServerLevel level, Villages.Village v, Case now, VillageFolkEntity f, long day) {
        for (Case c : Crime.cases(v.id())) {
            if (c == now || !c.culprit.equals(f.getUUID())) continue;
            if (c.stage == Stage.UNSOLVED || c.stage == Stage.ACQUITTED) {
                c.stage = Stage.CONVICTED;
                c.verdict = "solved: " + f.displayNameCap() + " owned up to it, tried on day " + day + " for another";
                c.note(day, f.displayNameCap() + " owned up to this too, before the council.");
                Crime.closed(c, true);
            } else if (c.stage == Stage.CONVICTED && c.accused != null && !c.accused.equals(f.getUUID())) {
                UUID wronged = c.accused;
                String wrongedName = c.accusedName;
                CompoundTag r = Crime.folk(wronged);
                r.putInt("convictions", Math.max(0, r.getInt("convictions") - 1));
                r.putLong("clearedDay", day);
                VillageFolkEntity w = Civics.find(level, wronged);
                int back = Math.min(fine(c), Ledger.coins(v.id()));
                if (w != null && back > 0) {
                    Ledger.takeCoins(v.id(), back);
                    w.earn(back);
                }
                c.verdict = "overturned: " + wrongedName + " cleared, " + f.displayNameCap() + " owned up to it";
                c.note(day, wrongedName + " was cleared: " + f.displayNameCap() + " owned up to it." + (back > 0 ? " " + back + " coins given back." : ""));
                Villages.tell(v.id(), day, wrongedName + " was cleared of the " + c.title().toLowerCase(Locale.ROOT) + ": " + f.displayNameCap()
                    + " owned up to it");
            }
        }
    }

    /** A third offence: out of the town, to the happiest neighbour with room, or off into the world. */
    private static void banish(ServerLevel level, Villages.Village v, Case c, VillageFolkEntity f, long day) {
        String name = f.displayNameCap();
        CompoundTag r = Crime.folk(f.getUUID());
        r.putLong("banishedDay", day);
        Villages.tell(v.id(), day, name + " was banished from " + Villages.name(v.id()) + " for " + c.kind.word + ": a third offence");
        Villages.Village to = Contentment.happiest(v);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Fine. I'll go. You'll not see me again.", "I've nobody to blame but myself."));
        if (to != null) f.leaveFor(level, to);
        else f.walkOut();
    }

    /** Some turn over a new leaf: more if somebody stands by them, if they are no longer poor, if they are good-hearted. */
    private static void reform(ServerLevel level, Villages.Village v, VillageFolkEntity f, int priors, long day) {
        int chance = 35;
        UUID best = f.life().bestFriend();
        if (f.life().partner() != null || best != null && f.life().affinity(best) >= Social.CLOSE) chance += 20;
        if (Wealth.tier(f) != Wealth.Tier.POOR) chance += 15;
        if (f.life().has(Social.Trait.GENEROUS) || f.life().has(Social.Trait.CHEERFUL)) chance += 10;
        chance -= priors * 20;
        if (f.getRandom().nextInt(100) >= chance) return;
        CompoundTag r = Crime.folk(f.getUUID());
        r.putLong("reformed", day);
        f.persona().remember(day, "I turned over a new leaf after I was caught, on day " + day, 6);
        f.sayLater(FolkTalk.pick(f.getRandom(), "Never again. I mean it.", "I'll make it up to everybody. You'll see."), 160);
    }

    // ------------------------------------------------------------------ not guilty

    /** Cleared: it remembers who named it; the watch looks again (once), or the case is closed. */
    static void acquit(ServerLevel level, Villages.Village v, Case c, VillageFolkEntity f, Sitting s) {
        Weave.acquitting(level, c, f.getUUID());                            // [weave] cleared: a player's word that put it here is paid for
        long day = level.getDayTime() / 24000L;
        UUID id = f.getUUID();
        String name = f.displayNameCap();
        c.cleared.add(id);
        if (!id.equals(c.culprit)) {
            Crime.folk(id).putLong("clearedDay", day);
            if (c.investigator != null) f.life().feel(c.investigator, c.investigatorName, -15);
            for (Statement st : c.statements) if (id.equals(st.named) && !st.player) f.life().feel(st.from, st.fromName, -30);
            f.persona().remember(day, "I was accused of " + c.kind.word + " and cleared by the council", 5);
        }
        c.note(day, "Not guilty, " + s.noes + " to " + s.ayes + ": " + name + " is cleared.");
        Villages.tell(v.id(), day, "the council found " + name + " not guilty of the " + c.title().toLowerCase(Locale.ROOT));
        c.accused = null;
        c.accusedName = "";
        boolean again = !c.retried && c.investigator != null && Civics.find(level, c.investigator) != null;
        if (again) {
            c.retried = true;
            c.stage = Stage.SEARCHING;
            c.progressDay = day;
            c.note(day, "The watch looks again.");
        } else {
            c.stage = Stage.ACQUITTED;
            c.closedDay = day;
            c.verdict = "not guilty: " + name + " cleared";
            returnEvidence(level, v, c);
            Crime.closed(c, false);
        }
        f.refreshMood();
        Crime.changed();
    }

    // ------------------------------------------------------------------ the folk's part

    /** From Crime.hold: called to a sitting; in the stocks; at its community work. What it is doing, or null. */
    @Nullable
    static String hold(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null) return null;
        Sitting s = SITTINGS.get(village);
        if (s != null && s.called.contains(f.getUUID()) && !f.isSleeping()) {
            standUp(f);
            Crime.takeOver(f);
            return court(f, level, s);
        }
        if (!Crime.known(f.getUUID())) return null;
        CompoundTag r = Crime.folk(f.getUUID());
        String sentence = r.getString("sentence");
        if (sentence.isEmpty()) {
            standUp(f);
            return null;
        }
        long now = level.getDayTime(), t = now % 24000L;
        if (now >= r.getLong("until")) {
            r.remove("sentence");
            standUp(f);
            SWEEP.remove(f.getUUID());
            FolkTalk.speak(f, r.getLong("reformed") > 0 ? FolkTalk.pick(f.getRandom(), "That's my debt to the town paid. Never again.",
                "I've had a long day to think. I'll do better.") : FolkTalk.pick(f.getRandom(), "Done. Don't look at me like that.", "At last."));
            Crime.changed();
            return null;
        }
        if (t >= 12500L || t < 1000L || f.isSleeping()) {
            standUp(f);
            return null;
        }
        Crime.takeOver(f);
        if (sentence.equals("stocks")) return stocks(f, level, v, r);
        return work(f, level, v, r);
    }

    /** Is it serving a sentence (and so not out to do anything else)? */
    static boolean sentenced(VillageFolkEntity f) {
        return Crime.known(f.getUUID()) && !Crime.folk(f.getUUID()).getString("sentence").isEmpty();
    }

    private static String court(VillageFolkEntity f, ServerLevel level, Sitting s) {
        Case c = Crime.get(s.caseId);
        String what = "at the trial of " + (c == null ? "the accused" : c.accusedName) + (s.hall ? " in the hall" : " on the square");
        BlockPos stand = standFor(s, f, c);
        if (!Mischief.near(f, stand, 1.8)) {
            walk(f, stand, 0.85D);
            return "on its way to the " + (s.hall ? "hall" : "square") + " for the trial";
        }
        f.getNavigation().stop();
        if (s.next > 0 && s.next <= s.lines.size()) {
            String[] last = s.lines.get(s.next - 1);
            UUID speaker = last[0].isEmpty() ? s.judge : UUID.fromString(last[0]);
            VillageFolkEntity sp = speaker == null ? null : Civics.find(level, speaker);
            if (sp != null && sp != f) f.getLookControl().setLookAt(sp, 30.0F, 30.0F);
        } else {
            BlockPos look = f.getUUID().equals(s.judge) ? s.dock : s.at;
            f.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 1.5, look.getZ() + 0.5);
        }
        return what;
    }

    /** Where each stands: the judge at the head, the accused in the dock before it, the rest round the dock. */
    private static BlockPos standFor(Sitting s, VillageFolkEntity f, @Nullable Case c) {
        if (f.getUUID().equals(s.judge)) return s.at;
        if (c != null && f.getUUID().equals(c.accused)) return s.dock;
        Direction side = s.faces.getClockWise();
        int i = Math.max(0, s.called.indexOf(f.getUUID()));
        int along = (i % 2 == 0 ? 2 : -2) * (1 + i / 4);
        int back = (i / 2) % 2 == 0 ? 0 : -2;
        return s.dock.relative(side, along).relative(s.faces, back);
    }

    // ------------------------------------------------------------------ the stocks

    private static String stocks(VillageFolkEntity f, ServerLevel level, Villages.Village v, CompoundTag r) {
        BlockPos at = stocksAt(level, v);
        if (at == null) {
            if (!putUp(level, v)) {
                if (!canMake(level, v)) {
                    // Nothing to make them of: the town sets it to work instead.
                    r.putString("sentence", "work");
                    Crime.changed();
                    return work(f, level, v, r);
                }
                return "waiting to be put in the stocks";
            }
            at = stocksAt(level, v);
            if (at == null) return "waiting to be put in the stocks";
        }
        BlockState stood = level.getBlockState(at);
        if (!(stood.getBlock() instanceof StocksBlock)) return "waiting to be put in the stocks";
        Direction facing = stood.getValue(StocksBlock.FACING);
        Vec3 spot = sitSpot(level, at);
        if (f.position().distanceToSqr(spot) > 0.3 * 0.3 || f.getPose() != Pose.SITTING) {
            if (f.position().distanceToSqr(spot) > 2.5 * 2.5) {
                standUp(f);
                walk(f, at.relative(facing.getOpposite()), 0.8D);
                return "on its way to the stocks";
            }
            f.getNavigation().stop();
            f.moveTo(spot.x, spot.y, spot.z, facing.toYRot(), 0.0F);
            f.setPose(Pose.SITTING);
            if (!SEATED.containsKey(f.getUUID())) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Go on, then. Get it over with.", "I deserve this, I suppose."));
            }
        }
        f.setYBodyRot(facing.toYRot());
        f.setYHeadRot(facing.toYRot());
        SEATED.put(f.getUUID(), level.getGameTime());
        return "sitting in the stocks on the square";
    }

    /** Sat in the stocks just now. */
    static boolean seated(VillageFolkEntity f) {
        Long at = SEATED.get(f.getUUID());
        return at != null && f.level().getGameTime() - at <= 40 && f.getPose() == Pose.SITTING;
    }

    private static void standUp(VillageFolkEntity f) {
        if (SEATED.remove(f.getUUID()) != null && f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
    }

    /** On the stocks' bench: its back half, looking out the way they face. */
    static Vec3 sitSpot(ServerLevel level, BlockPos at) {
        BlockState st = level.getBlockState(at);
        Direction facing = st.getBlock() instanceof StocksBlock ? st.getValue(StocksBlock.FACING) : Direction.NORTH;
        return new Vec3(at.getX() + 0.5 - facing.getStepX() * 0.22, at.getY() + 0.5, at.getZ() + 0.5 - facing.getStepZ() * 0.22);
    }

    /** The town's stocks, where they stand (and still stand), or null. */
    @Nullable
    static BlockPos stocksAt(ServerLevel level, Villages.Village v) {
        String note = Ledger.note(v.id(), STOCKS);
        if (note == null || note.isEmpty()) return null;
        String[] p = note.split(",");
        if (p.length < 3) return null;
        try {
            BlockPos at = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            if (!level.isLoaded(at)) return at;
            if (level.getBlockState(at).getBlock() instanceof StocksBlock) return at;
        } catch (NumberFormatException ignored) { }
        Ledger.forget(v.id(), STOCKS);
        return null;
    }

    /** Can the stores run to the stocks: a pair put by, or three planks and two logs (the recipe's)? */
    static boolean canMake(ServerLevel level, Villages.Village v) {
        if (Crafts.stock(level, v, s -> s.is(McAssistantMod.STOCKS_ITEM.get())) > 0) return true;
        int logs = Crafts.stock(level, v, s -> s.is(ItemTags.LOGS)), planks = Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS));
        return logs >= 2 && (planks >= 3 || logs >= 3);
    }

    /**
     * The stocks put up on the square, out of the stores (a pair put by, or made of three planks and two logs), by a
     * hand sent to the spot (TownJobs). True once they stand.
     */
    static boolean putUp(ServerLevel level, Villages.Village v) {
        if (stocksAt(level, v) != null) return true;
        BlockPos at = spot(level, v);
        if (at == null || !canMake(level, v)) return false;
        if (!TownJobs.atWork(level, v, "stocks", at, "putting up the stocks on the square")) return false;
        boolean paid = Crafts.take(level, v, s -> s.is(McAssistantMod.STOCKS_ITEM.get()), 1);
        if (!paid) {
            if (Crafts.usePlanks(level, v, 3)) {
                if (Crafts.take(level, v, s -> s.is(ItemTags.LOGS), 2)) paid = true;
                else Crafts.store(level, v, new ItemStack(Items.OAK_PLANKS, 3));
            }
        }
        if (!paid) return false;
        Direction facing = towards(at, v.centre());
        level.setBlockAndUpdate(at, McAssistantMod.STOCKS.get().defaultBlockState().setValue(StocksBlock.FACING, facing));
        Ledger.note(v.id(), STOCKS, at.getX() + "," + at.getY() + "," + at.getZ() + "," + facing.getName());
        Villages.tell(v.id(), level.getDayTime() / 24000L, "the stocks were put up on the square");
        return true;
    }

    private static Direction towards(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) return Direction.NORTH;
        return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /** A place for the stocks on the square: open ground, a block clear round it, not in anybody's way at the very middle. */
    @Nullable
    static BlockPos spot(ServerLevel level, Villages.Village v) {
        int[][] tries = { { 6, -9 }, { -6, -9 }, { 9, 6 }, { -9, 6 }, { 6, 9 }, { -6, 9 }, { 9, -6 }, { -9, -6 }, { 4, -10 }, { -4, 10 } };
        BlockPos heart = v.centre();
        for (int[] t : tries) {
            BlockPos at = ground(level, heart.offset(t[0], 0, t[1]));
            if (Math.abs(at.getY() - heart.getY()) > 4) continue;
            if (!level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) continue;
            if (!level.getBlockState(at.below()).getFluidState().isEmpty()) continue;
            boolean clear = true;
            for (int dx = -1; dx <= 1 && clear; dx++) {
                for (int dz = -1; dz <= 1 && clear; dz++) {
                    for (int dy = 0; dy <= 2 && clear; dy++) {
                        BlockState s = level.getBlockState(at.offset(dx, dy, dz));
                        clear = s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty();
                    }
                }
            }
            if (clear) return at;
        }
        return null;
    }

    /** Passers-by have their say to whoever is in the stocks. */
    private static void jeers(ServerLevel level, Villages.Village v) {
        for (Map.Entry<UUID, Long> e : SEATED.entrySet()) {
            VillageFolkEntity f = Civics.find(level, e.getKey());
            if (f == null || !v.id().equals(f.ownerId()) || !seated(f) || level.getRandom().nextInt(3) != 0) continue;
            VillageFolkEntity by = null;
            for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(8.0),
                    o -> o != f && o.isAlive() && !o.isSleeping() && v.id().equals(o.ownerId()))) {
                by = o;
                if (level.getRandom().nextBoolean()) break;
            }
            if (by == null) continue;
            String name = f.displayNameCap();
            Case c = Crime.get(Crime.folk(f.getUUID()).getInt("sentenceCase"));
            String line;
            if (c != null && by.getUUID().equals(c.victim)) line = FolkTalk.pick(by.getRandom(), "That'll teach you, " + name + "!", "Not so clever now, are you?");
            else if (by.isBaby()) line = FolkTalk.pick(by.getRandom(), name + "'s in the stocks! " + name + "'s in the stocks!", "Ha! Look at " + name + "!");
            else if (by.getUUID().equals(f.life().partner()) || by.life().affinity(f.getUUID()) >= Social.FRIEND) {
                line = FolkTalk.pick(by.getRandom(), "Chin up, " + name + ". It'll pass.", "I brought you a drink of water, " + name + ".");
            } else line = FolkTalk.pick(by.getRandom(), "Serves you right, " + name + ".", "Shame on you.", "Let that be a lesson to you.");
            by.getLookControl().setLookAt(f, 30.0F, 30.0F);
            FolkTalk.speak(by, line);
        }
    }

    // ------------------------------------------------------------------ community work

    private static String work(VillageFolkEntity f, ServerLevel level, Villages.Village v, CompoundTag r) {
        Case c = Crime.get(r.getInt("sentenceCase"));
        if (c != null && c.kind == Kind.VANDALISM && !c.mended && !r.getBoolean("noMaterial")) {
            if (!Mischief.near(f, c.where, 3.2)) {
                walk(f, c.where, 0.8D);
                return "on its way to mend " + c.brokeWhat + " (its community work)";
            }
            f.getNavigation().stop();
            f.getLookControl().setLookAt(c.where.getX() + 0.5, c.where.getY() + 0.5, c.where.getZ() + 0.5);
            f.swing(InteractionHand.MAIN_HAND);
            if (mend(level, v, f, c)) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There. Good as new.", "Mended. I'm sorry I broke it."));
            } else {
                r.putBoolean("noMaterial", true);
                c.note(level.getDayTime() / 24000L, "Nothing in the stores to mend " + c.brokeWhat + " with; " + f.displayNameCap() + " sweeps the streets instead.");
            }
            Crime.changed();
            return "mending " + c.brokeWhat;
        }
        // Sweeping the streets: the watch's stops, one after another, a few minutes' sweeping at each.
        List<BlockPos> stops = stops(level, v);
        if (stops.isEmpty()) return null;
        long gt = level.getGameTime();
        long[] sw = SWEEP.computeIfAbsent(f.getUUID(), k -> new long[]{ f.getRandom().nextInt(stops.size()), -1 });
        BlockPos stop = stops.get((int) Math.floorMod(sw[0], (long) stops.size()));
        if (sw[1] < 0) {
            if (!Mischief.near(f, stop, 2.5)) {
                walk(f, stop, 0.7D);
                return "sweeping the streets (community work)";
            }
            sw[1] = gt;
        }
        f.getNavigation().stop();
        if (gt % 20 < 5) f.swing(InteractionHand.MAIN_HAND);
        if (gt % 40 < 5) sweepUp(level, v, f);
        if (gt - sw[1] > Crime.pause(200)) {
            sw[0]++;
            sw[1] = -1;
        }
        return "sweeping the streets (community work)";
    }

    /** The streets to sweep (the watch's stops), worked out once in a while. */
    private static final Map<UUID, List<BlockPos>> STOPS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STOPS_AT = new ConcurrentHashMap<>();

    private static List<BlockPos> stops(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        List<BlockPos> known = STOPS.get(v.id());
        if (known != null && now - STOPS_AT.getOrDefault(v.id(), -100000L) < 2400L) return known;
        List<BlockPos> fresh = List.copyOf(Patrols.stops(level, v));
        STOPS.put(v.id(), fresh);
        STOPS_AT.put(v.id(), now);
        return fresh;
    }

    /** What lies loose in the street by it (never a player's, never a clue), into the stores. */
    private static void sweepUp(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(f.blockPosition()).inflate(3.0),
                e -> e.isAlive() && !e.hasPickUpDelay() && !Sweepers.playersOwn(e) && !e.getTags().contains(Crime.CLUE))) {
            ItemStack s = e.getItem().copy();
            e.discard();
            Crafts.store(level, v, s);
        }
    }

    /** What was broken put back, out of the stores, at the culprit's cost. */
    static boolean mend(ServerLevel level, Villages.Village v, VillageFolkEntity f, Case c) {
        BlockState now = level.getBlockState(c.where);
        if (!now.isAir() && !now.canBeReplaced()) {
            c.mended = true;
            return true;
        }
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(c.broke));
        if (block == Blocks.AIR) return false;
        Block put = block;
        boolean paid;
        if (Mischief.isLight(block.defaultBlockState())) {
            Block light = Masonry.light(level, v);
            paid = light != null;
            if (paid) put = light;
        } else if (block == Blocks.GLASS_PANE || block instanceof net.minecraft.world.level.block.StainedGlassPaneBlock) {
            paid = Crafts.take(level, v, s -> s.is(block.asItem()), 1);
            if (!paid && Crafts.take(level, v, s -> s.is(Items.GLASS_PANE), 1)) { paid = true; put = Blocks.GLASS_PANE; }
            if (!paid && Crafts.take(level, v, s -> s.is(Items.GLASS), 3)) {
                paid = true;
                put = Blocks.GLASS_PANE;
                Crafts.store(level, v, new ItemStack(Items.GLASS_PANE, 7));    // a pane of the eight the three made
            }
        } else {
            paid = Crafts.take(level, v, s -> s.is(block.asItem()), 1) || Crafts.fence(level, v);
        }
        if (!paid) return false;
        BlockState st = Block.updateFromNeighbourShapes(put.defaultBlockState(), level, c.where);
        level.setBlockAndUpdate(c.where, st);
        int cost = Math.max(1, (int) Math.round(Prices.of(new ItemStack(put.asItem()))));
        int took = Math.min(f.purse(), cost);
        if (took > 0 && f.spend(took)) Ledger.addCoins(v.id(), took);
        if (cost - took > 0) owe(f, null, "the town", cost - took);
        c.mended = true;
        c.note(level.getDayTime() / 24000L, f.displayNameCap() + " mended " + c.brokeWhat + " at " + c.place + ", and paid " + cost + " for it.");
        return true;
    }

    // ------------------------------------------------------------------ the board

    /** Who is in the stocks or at its community work, for the board. */
    @Nullable
    static String boardLine(ServerLevel level, UUID village) {
        List<String> stocks = new ArrayList<>(), work = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || !Crime.known(f.getUUID())) continue;
            String s = Crime.folk(f.getUUID()).getString("sentence");
            if (s.equals("stocks")) stocks.add(f.displayNameCap());
            else if (s.equals("work")) work.add(f.displayNameCap());
        }
        if (stocks.isEmpty() && work.isEmpty()) return null;
        return (stocks.isEmpty() ? "" : "In the stocks on the square: " + Civics.names(stocks) + ". ")
            + (work.isEmpty() ? "" : "Working off a sentence for the town: " + Civics.names(work) + ".");
    }

    // ------------------------------------------------------------------ helpers

    private static void walk(VillageFolkEntity f, BlockPos to, double speed) {
        Integer last = PATHED.get(f.getUUID());
        if (last == null || f.tickCount - last > 40 || f.tickCount < last || f.getNavigation().isDone()) {
            f.walkTo(to, speed);
            PATHED.put(f.getUUID(), f.tickCount);
        }
    }

    /** Tests and the stage: a sitting opened now on the oldest accused case. */
    @Nullable
    static Sitting openNow(ServerLevel level, Villages.Village v) {
        if (SITTINGS.containsKey(v.id())) return SITTINGS.get(v.id());
        for (Case c : Crime.open(v.id())) if (c.stage == Stage.ACCUSED) return open(level, v, c);
        return null;
    }

    @Nullable
    static Sitting sitting(UUID village) {
        return SITTINGS.get(village);
    }
}
