package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.JusticeConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.Justice.Charge;
import com.jrpetty.mcassistant.entity.Justice.Ev;
import com.jrpetty.mcassistant.entity.Justice.JCase;
import com.jrpetty.mcassistant.entity.Justice.Stage;
import com.jrpetty.mcassistant.entity.Justice.Wit;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpe] The court for the graver cases (Justice), and the small claims.
 *
 * <p>It sits in the courthouse (courthouse.txt: the judge's bench on its dais, the witness stand, the jury's benches,
 * the dock, the tables of the watch and the defence, the public benches), else at the Town Hall, else on the square.
 * The judge is whoever the town's Government gives the bench to: the mayor, the speaker of the elders, the lord, the
 * guildmaster, the steward or the chaplain (each a little harsher or gentler). A prisoner on remand is walked from its
 * cell to the dock in irons; a player summoned must come by itself, and not coming is a warrant.
 *
 * <p>A line at a time, to everybody in the room (and on the accused's Case tab): the gavel; the charges read; the watch's
 * case (its officer prosecutes); the witnesses called to the stand in turn, each saying what it saw, hearsay said as
 * such; the evidence shown; the defence (the player's own account, chosen on the Case tab; a lawyer folk it has hired,
 * who picks at the weaker evidence; a public defender for one who cannot pay); the jury (players on the jury roll,
 * through Jury, and folk jurors weighing the evidence); the verdict, a killing found accident, manslaughter or murder;
 * and the judge's sentence: a fine, compensation to the victim or the dead one's family, community work with the
 * watch's broom, the stocks, days in the cells, banishment. Every part of it is told to the player plainly: what, why,
 * and what next.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Assize {

    private Assize() {}

    public static final String COURTHOUSE = "courthouse";
    /** The courthouse's places (across, up, toward the back). */
    static final int[] JUDGE = { 0, 1, 7 }, WITNESS = { -4, 0, 4 }, DOCK = { 0, 0, 2 }, GUARD = { 0, 0, 0 }, PROSECUTOR = { -4, 0, -2 },
        DEFENCE = { 4, 0, -2 }, JURY = { -4, 0, 2 }, DOOR = { 0, 0, -9 };
    /** What a lawyer asks, and how long the court waits for a defence once it is asked for (ticks). */
    static final int LAWYER_FEE = 15, DEFENCE_WAIT = 900, LINE = 70;

    @Nullable
    public static Ledger.Building courthouse(@Nullable UUID village) {
        return Jail.standing(village, COURTHOUSE, new int[]{ 0, 0, 3 }, s -> s.is(net.minecraft.tags.BlockTags.FENCES));
    }

    /** Does the town want a courthouse: the Iron Age, a jail (or watch house), and thirty folk; none yet. */
    public static boolean courthouseWanted(UUID village) {
        if (!JusticeConfig.enabled() || Villages.builtStructure(village, COURTHOUSE) != null) return false;
        if (Villages.ageOf(village).ordinal() < Villages.Age.IRON.ordinal()) return false;
        return (Jail.of(village) != null || WatchHouse.of(village) != null) && Villages.headcount(village) >= 30;
    }

    public static String courthouseWhy(UUID village) {
        return "The court sits in the hall between the town's other business: a courthouse of its own, with a judge's bench, a dock, "
            + "a witness stand, a jury box and benches for the public, so the graver cases are heard properly.";
    }

    static BlockPos at(Ledger.Building b, int[] c) {
        return WatchHouse.at(b, c[0], c[1], c[2]);
    }

    /** Where the court sits: the courthouse's dock, the Town Hall, the square. */
    public static BlockPos courtPlace(ServerLevel level, Villages.Village v) {
        Ledger.Building b = courthouse(v.id());
        if (b != null) return at(b, DOCK);
        Ledger.Building hall = TownHallDesk.hall(v.id());
        if (hall != null) return hall.anchor();
        return v.centre();
    }

    public static String courtWords(ServerLevel level, Villages.Village v) {
        return courthouse(v.id()) != null ? "the courthouse" : TownHallDesk.hall(v.id()) != null ? "the Town Hall" : "the square";
    }

    /** "the mayor", "the lord": who sits in judgement under the town's Government. */
    static String judgeTitle(UUID village) {
        return "the " + Government.title(village);
    }

    /** How the bench's own leaning moves a sentence: a lord harsher, a commune or a chaplain gentler. */
    static double benchFactor(UUID village) {
        return switch (Government.of(village)) {
            case LORD -> 1.25;
            case GUILD -> 1.1;
            case COMMUNE -> 0.85;
            case CHAPLAIN -> 0.8;
            default -> 1.0;
        };
    }

    // ------------------------------------------------------------------ a sitting

    enum Phase { GATHER, HEARING, DEFENCE, JURY, DONE }

    static final class Sitting {
        final int caseId;
        final UUID village;
        final BlockPos dock, judgeAt, witnessAt, prosAt, defAt;
        final boolean house;
        @Nullable UUID judge;
        String judgeName;
        Phase phase = Phase.GATHER;
        final long opened;
        long nextAt, waitFrom;
        final List<String[]> lines = new ArrayList<>();
        int next;
        boolean asked, jury;

        Sitting(int caseId, UUID village, BlockPos dock, BlockPos judgeAt, BlockPos witnessAt, BlockPos prosAt, BlockPos defAt, boolean house, long opened) {
            this.caseId = caseId;
            this.village = village;
            this.dock = dock;
            this.judgeAt = judgeAt;
            this.witnessAt = witnessAt;
            this.prosAt = prosAt;
            this.defAt = defAt;
            this.house = house;
            this.opened = opened;
        }
    }

    private static final Map<UUID, Sitting> SITTINGS = new ConcurrentHashMap<>();
    /** Folk the court has called, by folk: where to stand. */
    private static final Map<UUID, BlockPos> CALLED = new ConcurrentHashMap<>();

    static void resetForTests() {
        SITTINGS.clear();
        CALLED.clear();
    }

    @Nullable
    public static Sitting sitting(UUID village) {
        return SITTINGS.get(village);
    }

    /** Each second (Justice's round): sittings opened when a case's day comes, and moved on. */
    static void sittings(ServerLevel level) {
        long now = level.getGameTime();
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
            Sitting s = SITTINGS.get(v.id());
            if (s != null) {
                com.jrpetty.mcassistant.Guard.run("the court sitting", () -> run(level, v, s, now));
                continue;
            }
            for (JCase c : Justice.cases(v.id())) {
                if (c.joinedTo != 0 || c.courtAt <= 0 || now < c.courtAt) continue;
                if (c.stage != Stage.REMAND && c.stage != Stage.SUMMONED) continue;
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(c.accused);
                if (c.player && (p == null || p.level() != level)) continue;     // tried with it present: it waits on remand
                open(level, v, c, p);
                break;
            }
            claims(level, v);
        }
    }

    static Sitting open(ServerLevel level, Villages.Village v, JCase c, @Nullable ServerPlayer p) {
        Ledger.Building b = courthouse(v.id());
        BlockPos dock, judge, wit, pros, def;
        if (b != null) {
            dock = at(b, DOCK);
            judge = at(b, JUDGE);
            wit = at(b, WITNESS);
            pros = at(b, PROSECUTOR);
            def = at(b, DEFENCE);
        } else {
            BlockPos base = courtPlace(level, v);
            dock = base;
            judge = base.relative(Direction.NORTH, 4);
            wit = base.relative(Direction.NORTH, 2).relative(Direction.WEST, 3);
            pros = base.relative(Direction.WEST, 3);
            def = base.relative(Direction.EAST, 3);
        }
        Sitting s = new Sitting(c.id, v.id(), dock, judge, wit, pros, def, b != null, level.getGameTime());
        UUID elder = Villages.elder(v.id());
        VillageFolkEntity j = elder == null ? null : Civics.find(level, elder);
        if (j != null && (j.getUUID().equals(c.victim) || !j.isAlive())) j = null;
        s.judge = j == null ? null : j.getUUID();
        s.judgeName = j == null ? cap(judgeTitle(v.id())) : cap(judgeTitle(v.id())) + " " + j.displayNameCap();
        SITTINGS.put(v.id(), s);
        c.stage = Stage.TRIAL;
        for (int id : c.joined) { JCase o = Justice.get(id); if (o != null) o.stage = Stage.TRIAL; }
        c.transcript.clear();
        if (j != null) CALLED.put(j.getUUID(), judge);
        if (c.officer != null) CALLED.put(c.officer, pros);
        for (Wit w : c.witnesses) if (w.reported && !w.lost) CALLED.put(w.id, s.witnessAt.relative(Direction.SOUTH, 1 + CALLED.size() % 3));
        // The jury: players on the roll, called by Jury.
        s.jury = Jury.summonFor(level, v, c.id, c.accused, c.accusedName, c.chargeWords() + " at " + c.place, dock, hearing(c)) > 0;
        if (p != null) {
            if (Jail.held(p.getUUID()) && p.distanceToSqr(Vec3.atBottomCenterOf(dock)) > 3.0 * 3.0) Arrests.toCourt(level, v, c, p, dock);
            Justice.notice(p, "Your case is called", "The court sits now at " + courtWords(level, v) + " on the charge of " + c.chargeWords() + " at " + c.place + ". "
                    + s.judgeName + " presides" + (s.jury ? ", with a jury." : "."),
                Jail.held(p.getUUID()) ? "A guard is walking you to the dock. Choose your defence on the Case tab if you haven't."
                    : "Go to the dock now: the court waits a little, then issues a warrant. Choose your defence on the Case tab.");
        }
        Police.log(v.id(), level.getDayTime(), "court", "the court sat on " + c.title() + " against " + c.accusedName, j, 0);
        gavel(level, dock);
        Justice.changed();
        return s;
    }

    /** What the jury hears, on the Town Hall's Duties tab (Jury). */
    static List<String> hearing(JCase c) {
        List<String> out = new ArrayList<>();
        out.add("The charge: " + c.chargeWords() + " at " + c.place + " on day " + c.day + ".");
        out.add("The accused: " + c.accusedName + ".");
        int n = 0;
        for (Wit w : c.witnesses) {
            if (!w.reported || w.lost) continue;
            out.add(w.own ? w.name + " saw it with their own eyes." : w.name + " heard it from " + w.from + " (hearsay, weaker).");
            n++;
        }
        for (Ev e : c.evidence) if (e.found) { out.add("The watch found: " + e.text + "."); n++; }
        for (int id : c.joined) { JCase o = Justice.get(id); if (o != null) out.add("Also charged: " + o.title() + "."); }
        if (n == 0) out.add("The evidence is thin.");
        return out;
    }

    static void say(ServerLevel level, Sitting s, JCase c, @Nullable UUID who, String speaker, String text) {
        String line = speaker + ": " + text;
        c.transcript.add(line);
        VillageFolkEntity f = who == null ? null : Civics.find(level, who);
        if (f != null && f.isAlive() && f.distanceToSqr(Vec3.atCenterOf(s.dock)) < 32 * 32) FolkTalk.speak(f, text);
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(s.dock) < 28 * 28 || p.getUUID().equals(c.accused) || Jury.sittingOn(p.getUUID()) == c.id) {
                p.sendSystemMessage(Component.literal("[Court] " + line).withStyle(ChatFormatting.GRAY));
            }
        }
        Justice.changed();
    }

    static void gavel(ServerLevel level, BlockPos at) {
        level.playSound(null, at, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 1.4F, 0.6F);
        level.playSound(null, at, SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.BLOCKS, 1.0F, 0.7F);
    }

    // ------------------------------------------------------------------ the sitting, a step at a time

    static void run(ServerLevel level, Villages.Village v, Sitting s, long now) {
        JCase c = Justice.get(s.caseId);
        if (c == null) { SITTINGS.remove(v.id()); return; }
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(c.accused);
        switch (s.phase) {
            case GATHER -> {
                boolean present = p != null && p.distanceToSqr(Vec3.atBottomCenterOf(s.dock)) < (s.house ? 3.0 * 3.0 : 6.0 * 6.0);
                long waited = now - s.opened;
                // The bench waits a little for the judge to take its seat.
                VillageFolkEntity judge = s.judge == null ? null : Civics.find(level, s.judge);
                if (present && judge != null && judge.isAlive() && judge.distanceToSqr(Vec3.atBottomCenterOf(s.judgeAt)) > 3.0 * 3.0
                        && waited < Justice.pause(2400)) return;
                if (present || waited > Justice.pause(2400) && !Jail.held(c.accused) || waited > Justice.pause(4800)) {
                    if (!present && !Jail.held(c.accused)) {
                        // Did not come: a warrant.
                        c.stage = Stage.WARRANT;
                        c.notes.add("failed to appear in court");
                        Arrests.addCharge(c, Charge.RESISTING, "failed to appear in court");
                        say(level, s, c, s.judge, s.judgeName, c.accusedName + " has not come. A warrant is issued.");
                        if (p != null) Justice.notice(p, "Warrant: you did not come to court", "You were summoned to court for " + c.title() + " and did not come.",
                            "A warrant is out: the watch will arrest you when it sees you. Giving yourself up to a guard counts in your favour.");
                        close(level, v, s);
                        return;
                    }
                    if (p != null && present) Restraint.pullTo(p, s.dock);
                    script(level, v, s, c, p);
                    s.phase = Phase.HEARING;
                    s.nextAt = now;
                }
            }
            case HEARING -> {
                if (p != null && Restraint.restrained(p)) Restraint.pullTo(p, s.dock);
                if (now < s.nextAt) return;
                if (s.next >= s.lines.size()) {
                    s.phase = Phase.DEFENCE;
                    s.waitFrom = now;
                    return;
                }
                String[] l = s.lines.get(s.next++);
                if (l[0].equals("#witness")) {
                    UUID w = UUID.fromString(l[1]);
                    VillageFolkEntity f = Civics.find(level, w);
                    if (f != null) CALLED.put(w, s.witnessAt);
                    s.nextAt = now + Justice.pause(LINE);
                    return;
                }
                say(level, s, c, l[0].isEmpty() ? null : UUID.fromString(l[0]), l[1], l[2]);
                s.nextAt = now + Justice.pause(LINE);
            }
            case DEFENCE -> {
                if (!s.asked && p != null && c.player && c.defence.isEmpty() && !c.plea.equals("guilty")) {
                    s.asked = true;
                    say(level, s, c, s.judge, s.judgeName, c.accusedName + ", what do you say to the charge?");
                    Justice.notice(p, "The court asks for your defence", "The judge asks what you say to the charge of " + c.chargeWords() + ".",
                        "Choose on the Case tab: plead guilty (a lighter sentence), give your account, or have a lawyer speak for you. The court waits "
                            + Justice.minutes(Justice.pause(DEFENCE_WAIT)) + ", then goes on as if you said nothing.");
                }
                if (!c.defence.isEmpty() || c.plea.equals("guilty") || now - s.waitFrom > Justice.pause(DEFENCE_WAIT)) {
                    for (String[] l : defenceLines(level, v, c, p)) say(level, s, c, l[0].isEmpty() ? null : UUID.fromString(l[0]), l[1], l[2]);
                    s.phase = Phase.JURY;
                    s.waitFrom = now;
                    if (s.jury) {
                        Jury.heard(c.id);
                        say(level, s, c, s.judge, s.judgeName, "Members of the jury, you have heard it all. How do you find?");
                    }
                }
            }
            case JURY -> {
                if (s.jury && !Jury.ready(c.id) && now - s.waitFrom < (Justice.hurry ? 200 : Jury.WAIT)) return;
                verdict(level, v, s, c, p);
                close(level, v, s);
            }
            case DONE -> close(level, v, s);
        }
    }

    static void close(ServerLevel level, Villages.Village v, Sitting s) {
        SITTINGS.remove(v.id());
        CALLED.values().removeIf(b -> b.distSqr(s.dock) < 40 * 40);
        Justice.changed();
    }

    /** The hearing's lines: the charges, the watch's case, the witnesses, the evidence. */
    static void script(ServerLevel level, Villages.Village v, Sitting s, JCase c, @Nullable ServerPlayer p) {
        String judge = s.judge == null ? "" : s.judge.toString();
        String pros = c.officer == null ? "" : c.officer.toString();
        String prosName = c.officerName.isEmpty() ? "The watch" : c.officerName + " of the watch";
        gavel(level, s.dock);
        s.lines.add(new String[]{ judge, s.judgeName, "This court is in session. " + c.accusedName + ", you stand charged with " + c.chargeWords() + "." });
        for (int id : c.joined) {
            JCase o = Justice.get(id);
            if (o != null) s.lines.add(new String[]{ judge, s.judgeName, "And with " + o.title() + ", on day " + o.day + "." });
        }
        s.lines.add(new String[]{ pros, prosName, "On day " + c.day + ", at " + c.place + ", " + what(c) + "." });
        for (JCase k : withJoined(c)) {
            for (Wit w : k.witnesses) {
                if (!w.reported || w.lost) continue;
                s.lines.add(new String[]{ "#witness", w.id.toString(), "" });
                VillageFolkEntity f = Civics.find(level, w.id);
                String said = w.own ? FolkTalk.pick(level.getRandom(), "I saw it. " + c.accusedName + ", " + k.charge.word + ", plain as day.",
                        "I was there. It was " + c.accusedName + ".", "I know what I saw: " + c.accusedName + ".", "It was " + c.accusedName + ". I'd swear to it.",
                        "I saw " + c.accusedName + " do it, and I told the watch.", "No doubt in my mind. " + c.accusedName + ".")
                    : FolkTalk.pick(level.getRandom(), w.from + " told me they saw " + c.accusedName + " do it.", "I only know what " + w.from + " told me. It was " + c.accusedName + ".",
                        "I didn't see it. " + w.from + " did, and told me.", w.from + " can't say it now. But they said it to me.");
                s.lines.add(new String[]{ f != null && f.isAlive() ? w.id.toString() : "", w.name + (w.own ? "" : " (hearsay)"), f != null && f.isAlive() ? said : "(read from their statement) " + said });
            }
            for (Ev e : k.evidence) if (e.found) s.lines.add(new String[]{ pros, prosName, "The court will see the evidence: " + e.text + "." });
        }
        if (c.score() < 0.1) s.lines.add(new String[]{ pros, prosName, "The watch has little more to put before the court." });
    }

    static List<JCase> withJoined(JCase c) {
        List<JCase> out = new ArrayList<>();
        out.add(c);
        for (int id : c.joined) { JCase o = Justice.get(id); if (o != null) out.add(o); }
        return out;
    }

    static String what(JCase c) {
        return switch (c.charge) {
            case THEFT, BURGLARY -> c.accusedName + " took " + Justice.nonEmpty(c.goods, "what was not theirs") + " from " + c.victimName;
            case VANDALISM -> c.accusedName + " did damage to " + c.victimName;
            case FRAUD -> c.accusedName + " passed " + Justice.nonEmpty(c.goods, "forged coin") + " to " + c.victimName + " as good";
            case ASSAULT -> c.accusedName + " struck " + c.victimName + (c.minor ? ", after complaints" : "");
            case MANSLAUGHTER, MURDER -> c.victimName + " was killed, and the watch says by " + c.accusedName;
            case RESISTING -> c.accusedName + " resisted the watch";
            case JAILBREAK -> c.accusedName + " broke out of custody";
            case UNPAID_FINE -> c.accusedName + " did not pay the court's fine";
        };
    }

    /** The defence as chosen: the player's own words, a lawyer, a public defender, or nothing. */
    static List<String[]> defenceLines(ServerLevel level, Villages.Village v, JCase c, @Nullable ServerPlayer p) {
        List<String[]> out = new ArrayList<>();
        String me = c.accusedName;
        if (c.plea.equals("guilty")) {
            out.add(new String[]{ "", me, "I did it. I plead guilty, and I'm sorry." });
            return out;
        }
        switch (c.account) {
            case "accident" -> out.add(new String[]{ "", me, "It was an accident. I never meant anyone harm." });
            case "elsewhere" -> out.add(new String[]{ "", me, "I wasn't there. They have the wrong person." });
            case "provoked" -> out.add(new String[]{ "", me, "I was provoked. I was defending myself." });
            case "mercy" -> out.add(new String[]{ "", me, "I ask the court's mercy. I'll make it right." });
            default -> { }
        }
        if (c.defence.equals("lawyer") || c.defence.equals("public")) {
            String who = c.lawyerName.isEmpty() ? "The defence" : c.lawyerName;
            int soft = 0;
            for (JCase k : withJoined(c)) {
                for (Wit w : k.witnesses) if (w.reported && !w.lost && !w.own) soft++;
                for (Ev e : k.evidence) if (e.found && e.weight < 2) soft++;
            }
            out.add(new String[]{ "", who, soft > 0 ? FolkTalk.pick(level.getRandom(), "Hearsay and a muddy footprint. Is that what the watch calls proof?",
                    "Not one of these things puts my client there on its own.", "The court should weigh what was seen, not what was said about it.")
                : FolkTalk.pick(level.getRandom(), "My client has been a good neighbour to this town.", "The court should consider the whole of my client's life.",
                    "I ask the court to be fair, and to be lenient.") });
        }
        if (out.isEmpty()) out.add(new String[]{ "", me, "(says nothing)" });
        return out;
    }

    // ------------------------------------------------------------------ the verdict and the sentence

    /** The evidence, less what the defence takes from it; a record counts against it. */
    static double weigh(JCase c, JCase k) {
        double s = k.score();
        double soft = 0;
        for (Wit w : k.witnesses) if (w.reported && !w.lost && !w.own) soft += w.weight();
        for (Ev e : k.evidence) if (e.found && e.weight < 2) soft += e.weight;
        if (c.defence.equals("lawyer")) s -= Math.min(1.5, soft * 0.6) + 0.3;
        else if (c.defence.equals("public")) s -= Math.min(1.0, soft * 0.4);
        if (c.account.equals("elsewhere")) {
            boolean seen = false;
            for (Wit w : k.witnesses) if (w.reported && !w.lost && w.own) seen = true;
            s += seen ? 0.5 : -0.5;                                           // a lie against an eyewitness costs it
        }
        s += 0.5 * Math.min(4, Justice.convictions(c.accused).size());
        return s;
    }

    static void verdict(ServerLevel level, Villages.Village v, Sitting s, JCase c, @Nullable ServerPlayer p) {
        double need = 2.5 / Math.sqrt(Math.max(0.25, JusticeConfig.harshness()));
        int[] jt = Jury.tally(c.id);
        List<JCase> all = withJoined(c);
        List<JCase> guilty = new ArrayList<>(), cleared = new ArrayList<>();
        for (JCase k : all) {
            boolean g;
            if (c.plea.equals("guilty")) g = true;
            else {
                double w = weigh(c, k);
                int ayes = 0, noes = 0;
                for (int i = 0; i < 3; i++) {
                    double lean = Justice.hurry ? (i - 1) * 0.4 : level.getRandom().nextGaussian() * 0.7;
                    if (w + lean >= need) ayes++; else noes++;
                }
                if (k == c) { ayes += jt[0]; noes += jt[1]; }
                g = ayes > noes;
            }
            (g ? guilty : cleared).add(k);
        }
        Jury.close(level, c.id, guilty.contains(c));
        gavel(level, s.dock);
        String judge = s.judgeName;
        if (guilty.isEmpty()) {
            say(level, s, c, s.judge, judge, "The court finds " + c.accusedName + " not guilty. You are free to go.");
            for (JCase k : all) acquit(level, v, k);
            if (!c.player) FolkCrimes.CompoundTagHolder.release(level, Civics.find(level, c.accused));
            if (p != null) {
                Jail.release(level, p.getUUID(), "The court found you not guilty of " + c.chargeWords() + ".");
                Restraint.off(p);
                if (!Jail.held(p.getUUID())) Justice.notice(p, "Not guilty", "The court found you not guilty of " + c.chargeWords() + " at " + c.place + ".",
                    "You are free. Nothing goes on your record for it.");
            }
            return;
        }
        for (JCase k : cleared) acquit(level, v, k);
        Sentence total = new Sentence();
        StringBuilder found = new StringBuilder();
        for (JCase k : guilty) {
            String finding = finding(c, k);
            k.finding = finding;
            k.verdict = "guilty";
            if (found.length() > 0) found.append("; ");
            found.append(finding);
            total.add(sentenceFor(level, v, c, k, finding));
        }
        total.scale(benchFactor(v.id()) * JusticeConfig.harshness() * (c.plea.equals("guilty") ? 0.75 : 1.0) * (c.notes.contains("surrendered") ? 0.85 : 1.0));
        double maxDays = JusticeConfig.maxJailMinutes() / Math.max(0.1, JusticeConfig.jailDayMinutes());
        total.jail = Math.min(total.jail, maxDays);
        String words = total.words();
        say(level, s, c, s.judge, judge, "The court finds " + c.accusedName + " guilty of " + found + ".");
        say(level, s, c, s.judge, judge, "The sentence of this court: " + words + ".");
        gavel(level, s.dock);
        for (JCase k : guilty) {
            k.stage = Stage.CONVICTED;
            k.sentence = words;
            Justice.convicted(k, words);
        }
        c.fine = total.fine;
        c.comp = total.comp;
        c.jailDays = (int) Math.ceil(total.jail);
        c.sweeps = total.sweeps;
        c.stocksMinutes = total.stocks;
        c.banishDays = total.banish;
        apply(level, v, c, p, total);
    }

    /** For a killing: accident, manslaughter or murder; else the charge itself. */
    static String finding(JCase c, JCase k) {
        if (!k.charge.killing()) return k.charge.word + (k.minor ? " (a minor assault)" : "");
        int intent = k.intent;
        for (Ev e : k.evidence) if (e.found && e.kind.startsWith("MOTIVE")) intent += 2;
        if (intent >= 2) return "murder";
        if (intent == 0 && c.account.equals("accident") && k.charge == Charge.MANSLAUGHTER) return "causing death by accident";
        return "manslaughter";
    }

    /** A sentence, in parts: a fine, compensation, jail days, community work, the stocks, banishment. */
    static final class Sentence {
        int fine, comp, sweeps, stocks, banish;
        double jail;

        void add(Sentence o) {
            fine += o.fine;
            comp += o.comp;
            sweeps = Math.max(sweeps, o.sweeps);
            stocks = Math.max(stocks, o.stocks);
            banish = Math.max(banish, o.banish);
            jail += o.jail;
        }

        void scale(double f) {
            fine = (int) Math.round(fine * f);
            jail = jail * f;
            sweeps = (int) Math.round(sweeps * Math.min(1.5, f));
        }

        String words() {
            List<String> w = new ArrayList<>();
            if (fine > 0) w.add("a fine of " + fine + " coins");
            if (comp > 0) w.add(comp + " coins compensation");
            if (jail > 0) w.add(String.format(Locale.ROOT, "%s in the cells", days(jail)));
            if (sweeps > 0) w.add("community work: " + sweeps + " sweeps of the streets with the watch's broom");
            if (stocks > 0) w.add(stocks + " minutes in the stocks");
            if (banish > 0) w.add("banishment from the town for " + banish + " days");
            return w.isEmpty() ? "a warning" : String.join(", ", w);
        }
    }

    static String days(double d) {
        if (d < 1.05) return "one day";
        return (Math.abs(d - Math.round(d)) < 0.05 ? String.valueOf(Math.round(d)) : String.format(Locale.ROOT, "%.1f", d)) + " days";
    }

    static Sentence sentenceFor(ServerLevel level, Villages.Village v, JCase c, JCase k, String finding) {
        Sentence s = new Sentence();
        int priors = Justice.convictions(c.accused).size();
        switch (k.charge) {
            case THEFT -> { s.fine = 2 * k.worth + 3; s.comp = k.worth; if (priors > 0) s.stocks = 3; }
            case BURGLARY -> { s.comp = k.worth + 5; s.fine = 8; s.jail = priors > 0 ? 2 : 1; }
            case VANDALISM -> { s.comp = 8; s.sweeps = 30; if (priors > 0) { s.stocks = 3; s.fine = 5; } }
            case FRAUD -> { s.comp = k.worth * 2; s.fine = 10; s.jail = 1; }
            case ASSAULT -> { if (k.minor) { s.fine = 6; s.comp = 4; } else { s.comp = 10; s.jail = 2; } }
            case MANSLAUGHTER, MURDER -> {
                if (finding.startsWith("causing death by accident")) { s.comp = 20; s.fine = 5; }
                else if (finding.equals("manslaughter")) { s.comp = 30; s.jail = 4; }
                else { s.comp = 40; s.jail = 10; if (priors > 1) s.banish = 7; }
            }
            case RESISTING -> s.jail = 1;
            case JAILBREAK -> s.jail = 2;
            case UNPAID_FINE -> { s.fine = 0; s.sweeps = 40; }
        }
        for (Charge x : k.extra) {
            if (x == Charge.RESISTING) s.jail += 1;
            if (x == Charge.JAILBREAK) s.jail += 2;
        }
        return s;
    }

    /** The sentence carried out: purse, compensation, record, standing; the cells, the stocks, the broom, the gates. */
    static void apply(ServerLevel level, Villages.Village v, JCase c, @Nullable ServerPlayer p, Sentence s) {
        long day = level.getDayTime() / 24000L;
        int paid = 0, compPaid = 0;
        if (p != null) {
            int have = Market.coinsHeld(p);
            // Belongings held by the jail count: what it has in its purse there is taken first.
            compPaid = Math.min(have, s.comp);
            if (compPaid > 0) Market.payOut(p, compPaid);
            have -= compPaid;
            paid = Math.min(have, s.fine);
            if (paid > 0) {
                Market.payOut(p, paid);
                Ledger.addCoins(v.id(), paid);
            }
        }
        // Compensation: to the victim, or to its family, or held by the town for them.
        String to = compensate(level, v, c, compPaid);
        int owed = (s.fine - paid) + (s.comp - compPaid);
        c.finePaid = owed <= 0;
        c.fine = owed;
        c.fineDue = owed > 0 ? day + JusticeConfig.fineDays() : 0;
        // What the town thinks of it now.
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f) f.persona().feelFor(c.accused, c.accusedName, -(2 + c.charge.weight * 2));
        }
        Standing.stir(v.id(), c.accused);
        Villages.tell(v.id(), day, c.accusedName + " was convicted of " + c.finding + " and sentenced to " + c.sentence);
        Police.log(v.id(), level.getDayTime(), "verdict", c.accusedName + " convicted: " + c.sentence, null, paid);
        if (p == null) {
            if (!c.player && c.charge.killing()) FolkCrimes.banish(level, v, c);   // a folk killer: banished
            return;
        }
        Restraint.off(p);
        if (s.sweeps > 0) {
            Justice.record(p.getUUID()).putInt("work", Justice.record(p.getUUID()).getInt("work") + s.sweeps);
            Justice.record(p.getUUID()).putUUID("workTown", v.id());
            ItemStack broom = broom();
            if (!p.getInventory().add(broom)) p.drop(broom, false);
        }
        if (s.banish > 0) {
            int[] r = Ledger.record(v.id(), p.getUUID());
            r[2] = (int) Math.max(r[2], day + s.banish);
            Ledger.record(v.id(), p.getUUID(), r);
        }
        String money = (paid + compPaid > 0 ? (paid + compPaid) + " coins were taken from your purse" + (to.isEmpty() ? "" : " (compensation to " + to + ")") + ". " : "")
            + (owed > 0 ? owed + " coins are still owed: pay them on the Record page by day " + c.fineDue + ", or it becomes a charge of its own. " : "");
        if (s.jail > 0) {
            double secs = s.jail * JusticeConfig.jailDayMinutes() * 60.0;
            Jail.sentence(level, v, p, c, secs);
            Arrests.toCells(level, v, c, p);
            Justice.notice(p, "Sentenced", "Guilty of " + c.finding + ". The sentence: " + c.sentence + ". " + money,
                "You're being walked back to the cells to serve " + days(s.jail) + " (about " + Math.round(secs / 60) + " minutes of play; time counts "
                    + (JusticeConfig.offlineRate() <= 0 ? "only while you're online" : "at a reduced rate while you're away") + "). Your belongings come back when you leave.");
            return;
        }
        if (s.stocks > 0) {
            Jail.release(level, p.getUUID(), "The court has passed sentence.");
            stocks(level, v, p, c, s.stocks);
            return;
        }
        Jail.release(level, p.getUUID(), "The court has passed sentence.");
        String next = s.sweeps > 0 ? "Community work: right-click the ground in town with the watch's broom " + s.sweeps + " times (it's in your pack). "
            : "";
        next += s.banish > 0 ? "You are banished: leave " + Villages.name(v.id()) + " now; its gates and shops are closed to you for " + s.banish + " days. " : "";
        next += "The conviction stays on your record, and the town thinks less of you for a while.";
        Justice.notice(p, "Sentenced", "Guilty of " + c.finding + ". The sentence: " + c.sentence + ". " + money, next);
    }

    /** Compensation paid on: to a living victim, a dead one's partner, or held by the town. Returns to whom, in words. */
    static String compensate(ServerLevel level, Villages.Village v, JCase c, int coins) {
        if (coins <= 0) return "";
        if (c.victim != null) {
            VillageFolkEntity f = Civics.find(level, c.victim);
            if (f != null && f.isAlive()) { f.earn(coins); return c.victimName; }
            ServerPlayer vp = level.getServer().getPlayerList().getPlayer(c.victim);
            if (vp != null) { PlayerLaw.giveCoins(vp, coins); return c.victimName; }
            for (String n : c.notes) {
                if (!n.startsWith("family:")) continue;
                String[] p = n.split(":", 3);
                try {
                    VillageFolkEntity kin = Civics.find(level, UUID.fromString(p[1]));
                    if (kin != null && kin.isAlive()) { kin.earn(coins); return c.victimName + "'s family (" + kin.displayNameCap() + ")"; }
                } catch (IllegalArgumentException ignored) { }
            }
        }
        Ledger.addCoins(v.id(), coins);
        return c.victimName.isEmpty() ? "the town" : "the town, held for " + c.victimName + "'s family";
    }

    static void acquit(ServerLevel level, Villages.Village v, JCase k) {
        k.stage = Stage.ACQUITTED;
        k.verdict = "not guilty";
        k.notes.add("acquitted");
        Police.log(v.id(), level.getDayTime(), "verdict", k.accusedName + " acquitted of " + k.title(), null, 0);
        Justice.changed();
    }

    /** The stocks: so many minutes held by the stocks on the square, the town looking on. */
    static void stocks(ServerLevel level, Villages.Village v, ServerPlayer p, JCase c, int minutes) {
        BlockPos at = Trial.stocksAt(level, v);
        BlockPos spot = at == null ? v.centre() : at.relative(Direction.SOUTH);
        Jail.Spot s = new Jail.Spot(Jail.Kind.SQUARE, spot, null, spot, 0, at == null ? "the square, under the watch's eye" : "the stocks on the square");
        Jail.lockUp(level, v, p.getUUID(), c, s, null);
        Jail.sentence(level, v, p, c, minutes * 60.0);
        Restraint.on(p, null, Restraint.Kind.STOCKS);
        Restraint.pullTo(p, spot);
        Justice.notice(p, "Sentenced: the stocks", "Guilty of " + c.finding + ". The sentence: " + c.sentence + ".",
            "Stand at the stocks on the square for " + minutes + " minutes. The folk will have their say. Walking off is a jail-break.");
    }

    // ------------------------------------------------------------------ community work: the watch's broom

    static final String BROOM = "mca_broom";

    static ItemStack broom() {
        ItemStack b = new ItemStack(Items.BRUSH);
        b.set(DataComponents.CUSTOM_NAME, Component.literal("The Watch's Broom"));
        CustomData.update(DataComponents.CUSTOM_DATA, b, t -> t.putBoolean(BROOM, true));
        return b;
    }

    static boolean isBroom(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.copyTag().getBoolean(BROOM);
    }

    private static final Map<UUID, Long> SWEPT = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onSweep(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel level) || !isBroom(e.getItemStack())) return;
        sweep(level, p, e.getPos());
    }

    static boolean sweep(ServerLevel level, ServerPlayer p, BlockPos at) {
        CompoundTag r = Justice.record(p.getUUID());
        int left = r.getInt("work");
        if (left <= 0) {
            p.displayClientMessage(Component.literal("Your community work is done. Keep the broom, or hand it in.").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        long now = level.getGameTime();
        Long last = SWEPT.get(p.getUUID());
        if (last != null && now - last < 12 && now >= last) return false;
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE);
        if (v == null || r.hasUUID("workTown") && !r.getUUID("workTown").equals(v.id())) {
            p.displayClientMessage(Component.literal("Sweep the streets of the town that sentenced you.").withStyle(ChatFormatting.YELLOW), true);
            return false;
        }
        SWEPT.put(p.getUUID(), now);
        left--;
        r.putInt("work", left);
        Justice.changed();
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD, at.getX() + 0.5, at.getY() + 1.05, at.getZ() + 0.5, 4, 0.3, 0.05, 0.3, 0.01);
        level.playSound(null, at, SoundEvents.BRUSH_GENERIC, SoundSource.PLAYERS, 0.6F, 1.0F);
        if (left == 0) {
            Justice.notice(p, "Community work done", "You've swept the streets as the court ordered.", "That part of your sentence is served. The town has noticed.");
            for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && f.distanceTo(p) < 24) f.persona().feelFor(p.getUUID(), p.getName().getString(), 2);
        } else if (left % 10 == 0) {
            p.displayClientMessage(Component.literal(left + " sweeps of community work left.").withStyle(ChatFormatting.GRAY), true);
        }
        return true;
    }

    // ------------------------------------------------------------------ the court's folk: the judge, the officer, the witnesses

    /** Called to court (VillageFolkEntity's round): to its place, and stays there while the court sits. */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        BlockPos at = CALLED.get(f.getUUID());
        if (at == null) return false;
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        if (v == null || !SITTINGS.containsKey(v.id())) { CALLED.remove(f.getUUID()); return false; }
        Sitting s = SITTINGS.get(v.id());
        f.lastLeisureTick = f.tickCount;
        if (f.distanceToSqr(Vec3.atBottomCenterOf(at)) > 1.6 * 1.6) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(at, 0.75);
            f.hobbyNow = "called to court";
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(s.dock.getX() + 0.5, s.dock.getY() + 1.5, s.dock.getZ() + 0.5);
        f.hobbyNow = f.getUUID().equals(s.judge) ? "presiding over the court" : "at court";
        return true;
    }

    // ------------------------------------------------------------------ the defence, chosen on the Case tab

    /** The player's defence (RecordPage): "plead" guilty, an "account", a "lawyer" hired or the "public" defender. */
    static String defend(ServerPlayer p, JCase c, String how, String detail) {
        if (c.stage.ordinal() > Stage.TRIAL.ordinal()) return "!That case is closed.";
        switch (how) {
            case "plead" -> {
                c.plea = "guilty";
                c.defence = "plead";
                Justice.changed();
                return "You will plead guilty. The court gives a lighter sentence for it, about a quarter off.";
            }
            case "account" -> {
                c.plea = "not guilty";
                c.account = detail;
                if (c.defence.isEmpty() || c.defence.equals("plead")) c.defence = "self";
                Justice.changed();
                return "You'll tell the court: \"" + accountWords(detail) + "\" " + (detail.equals("elsewhere") ? "If an eyewitness says otherwise, the court will hold it against you."
                    : "The court hears it before the verdict.");
            }
            case "lawyer" -> {
                if (Market.coinsHeld(p) < LAWYER_FEE && !Jail.held(p.getUUID())) return "!A lawyer asks " + LAWYER_FEE + " coins and you have " + Market.coinsHeld(p) + ". Ask for the public defender instead.";
                VillageFolkEntity l = lawyer(p.serverLevel(), c);
                int fee = LAWYER_FEE;
                int have = Market.coinsHeld(p);
                if (have < fee) return "!A lawyer asks " + fee + " coins, and you have " + have + " on you. The public defender costs nothing.";
                Market.payOut(p, fee);
                if (l != null) l.earn(fee);
                c.defence = "lawyer";
                c.lawyerName = l == null ? "Your lawyer" : l.displayNameCap() + ", for the defence";
                if (c.plea.isEmpty()) c.plea = "not guilty";
                Justice.changed();
                return (l == null ? "A lawyer" : l.displayNameCap()) + " will speak for you, for " + fee + " coins. A lawyer picks holes in hearsay and weak evidence.";
            }
            case "public" -> {
                if (Market.coinsHeld(p) >= LAWYER_FEE * 2) return "!The public defender is for those who can't pay, and you have " + Market.coinsHeld(p) + " coins. Hire a lawyer instead.";
                c.defence = "public";
                c.lawyerName = "The public defender";
                if (c.plea.isEmpty()) c.plea = "not guilty";
                Justice.changed();
                return "The town gives you a public defender at no cost. They'll do their best with what there is.";
            }
            default -> { return "!That isn't a defence the court knows."; }
        }
    }

    static String accountWords(String a) {
        return switch (a) {
            case "accident" -> "It was an accident.";
            case "elsewhere" -> "I wasn't there.";
            case "provoked" -> "I was provoked; I was defending myself.";
            case "mercy" -> "I ask the court's mercy.";
            default -> "Nothing to say.";
        };
    }

    /** A folk to take the case: a scholar or a clerk if the town has one, else any grown folk who isn't involved. */
    @Nullable
    static VillageFolkEntity lawyer(ServerLevel level, JCase c) {
        VillageFolkEntity best = null;
        int bestScore = -1;
        for (AssistantEntity a : Villages.folkOf(c.village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || f.getUUID().equals(c.victim)) continue;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD || f.getUUID().equals(Villages.elder(c.village))) continue;
            boolean witness = false;
            for (Wit w : c.witnesses) if (w.id.equals(f.getUUID())) witness = true;
            if (witness) continue;
            String job = f.stationTask().name();
            int score = job.contains("LIBR") || job.contains("TEACH") || job.contains("SCHOL") || job.contains("CLERK") ? 3 : job.contains("SHOP") || job.contains("STORE") ? 2 : 1;
            if (score > bestScore) { bestScore = score; best = f; }
        }
        return best;
    }

    // ------------------------------------------------------------------ small claims

    /**
     * A small claim before the court (Court.smallClaim): heard at the next sitting the court has free, a few lines read,
     * and ruled by the strength of the claimant's evidence (0 to 10: a signed contract with escrow is 8, a witness 5, a
     * word against a word 3). Ruled for the claimant, the defendant pays from its purse if it can, the rest owed.
     */
    static int claim(ServerLevel level, UUID village, UUID claimant, String claimantName, UUID defendant, String defendantName, int coins,
                     String about, int evidence, @Nullable java.util.function.Consumer<CompoundTag> ruled) {
        int id = Justice.nextId();
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putUUID("v", village);
        t.putUUID("c", claimant);
        t.putString("cn", claimantName);
        t.putUUID("d", defendant);
        t.putString("dn", defendantName);
        t.putInt("coins", coins);
        t.putString("about", about);
        t.putInt("ev", Math.max(0, Math.min(10, evidence)));
        t.putLong("at", level.getGameTime() + Math.max(Justice.pause(1200), (long) (JusticeConfig.courtDelayMinutes() * 1200 * (Justice.hurry ? 0.05 : 1.0))));
        t.putString("state", "listed");
        Justice.of().claims.put(String.valueOf(id), t);
        Justice.changed();
        if (ruled != null) RULED.put(id, ruled);
        Villages.Village v = Villages.get(village);
        BlockPos court = v == null ? null : courtPlace(level, v);
        long when = level.getDayTime() + (t.getLong("at") - level.getGameTime());
        for (UUID u : new UUID[]{ claimant, defendant }) {
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(u);
            if (sp == null) continue;
            Planner.add(sp, when, "court", "Small claim: " + claimantName + " v " + defendantName, court);
            sp.sendSystemMessage(Component.literal("Small claims court: " + claimantName + " claims " + coins + " coins from " + defendantName + " (" + about
                + "). Heard in " + Justice.minutes(t.getLong("at") - level.getGameTime()) + "; it's on your planner.").withStyle(ChatFormatting.GOLD));
        }
        return id;
    }

    private static final Map<Integer, java.util.function.Consumer<CompoundTag>> RULED = new ConcurrentHashMap<>();

    /** The claim's money is in escrow: the ruling releases it (the caller's), nobody's purse is touched. */
    static void escrow(int id) {
        CompoundTag t = Justice.of().claims.getCompound(String.valueOf(id));
        t.putBoolean("escrow", true);
        Justice.changed();
    }

    static void claims(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        CompoundTag all = Justice.of().claims;
        for (String k : new ArrayList<>(all.getAllKeys())) {
            CompoundTag t = all.getCompound(k);
            if (!t.getString("state").equals("listed") || !t.getUUID("v").equals(v.id()) || now < t.getLong("at")) continue;
            hearClaim(level, v, t);
            return;                                                              // one a round
        }
    }

    static void hearClaim(ServerLevel level, Villages.Village v, CompoundTag t) {
        boolean forClaimant = t.getInt("ev") >= 5;
        int coins = t.getInt("coins");
        String judge = cap(judgeTitle(v.id()));
        BlockPos court = courtPlace(level, v);
        gavel(level, court);
        int paid = 0;
        boolean escrow = t.getBoolean("escrow");
        if (forClaimant && !escrow) {
            ServerPlayer d = level.getServer().getPlayerList().getPlayer(t.getUUID("d"));
            if (d != null) {
                paid = Math.min(Market.coinsHeld(d), coins);
                if (paid > 0) Market.payOut(d, paid);
            } else {
                VillageFolkEntity f = Civics.find(level, t.getUUID("d"));
                if (f != null) { paid = Math.min(f.purse(), coins); f.earn(-0); }
            }
            ServerPlayer c = level.getServer().getPlayerList().getPlayer(t.getUUID("c"));
            if (c != null && paid > 0) PlayerLaw.giveCoins(c, paid);
            else if (paid > 0) { VillageFolkEntity f = Civics.find(level, t.getUUID("c")); if (f != null) f.earn(paid); }
        }
        t.putString("state", forClaimant ? "for claimant" : "for defendant");
        t.putInt("paid", paid);
        t.putInt("owed", forClaimant && !escrow ? coins - paid : 0);
        String line = judge + ": In " + t.getString("cn") + " against " + t.getString("dn") + ", over " + t.getString("about") + ", the court finds for the "
            + (escrow ? (forClaimant ? "claimant" : "defendant") + ": the escrow held at the hall goes to " + (forClaimant ? t.getString("cn") : t.getString("dn")) + "."
            : forClaimant ? "claimant: " + coins + " coins are owed" + (paid > 0 ? ", " + paid + " paid now." : ".") : "defendant: the claim is dismissed.");
        for (UUID u : new UUID[]{ t.getUUID("c"), t.getUUID("d") }) {
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(u);
            if (sp != null) sp.sendSystemMessage(Component.literal("[Small claims] " + line).withStyle(ChatFormatting.GOLD));
        }
        Police.log(v.id(), level.getDayTime(), "claim", line, null, paid);
        Justice.changed();
        java.util.function.Consumer<CompoundTag> cb = RULED.remove(t.getInt("id"));
        if (cb != null) com.jrpetty.mcassistant.Guard.run("a small claim's ruling", () -> cb.accept(t.copy()));
    }

    @Nullable
    static CompoundTag claimOf(int id) {
        CompoundTag all = Justice.of().claims;
        return all.contains(String.valueOf(id)) ? all.getCompound(String.valueOf(id)).copy() : null;
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests

    public static String phaseForTests(UUID village) {
        Sitting s = SITTINGS.get(village);
        return s == null ? "" : s.phase.name();
    }

    /** Tests: the court sits on the case now, wherever things stand. */
    public static void sitNowForTests(ServerLevel level, int caseId) {
        JCase c = Justice.get(caseId);
        if (c != null) c.courtAt = level.getGameTime();
    }

    public static Ledger.Building courthouseForTests(ServerLevel level, Villages.Village v, BlockPos site) {
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, COURTHOUSE, site, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(v.id(), COURTHOUSE, site, Direction.NORTH);
        Villages.builtAtForTests(v.id(), COURTHOUSE, site);
        return courthouse(v.id());
    }

    public static String defendForTests(ServerPlayer p, int caseId, String how, String detail) {
        JCase c = Justice.get(caseId);
        return c == null ? "!no case" : defend(p, c, how, detail);
    }

    public static boolean sweepForTests(ServerLevel level, ServerPlayer p, BlockPos at) {
        SWEPT.remove(p.getUUID());
        return sweep(level, p, at);
    }

    public static BlockPos dockForTests(Ledger.Building b) {
        return at(b, DOCK);
    }
}
