package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [featcivic] How a new leader takes the seat where nobody votes for it, and the show at an election's end where they do.
 * <ul>
 * <li><b>A coronation</b> (a lordship): the town gathers before the board, kneels, the eldest reads the line and puts
 *     the circlet on; cheers, and the rockets if the town has any.</li>
 * <li><b>The party congress</b> (a planned state): the committee stands before the town, the chair is proposed, the
 *     committee's hands go up as one, and the week's plan is read out.</li>
 * <li><b>The change of command</b> (military rule): the officers in a line, the senior one steps forward and takes
 *     command, the watch salutes, the curfew read out.</li>
 * <li><b>The chapel's choice</b> (the chaplain's rule): the town prays, the new chaplain is named.</li>
 * <li><b>An election won</b>: the outgoing leader hands over the chain of office, the winner speaks, the rockets go up if
 *     the stores have them, and the losers' posters come down.</li>
 * </ul>
 * A council of elders, a guild and a commune choose by their own votes: their count is the election's (Elections.script).
 */
public final class Ceremonies {

    private Ceremonies() {}

    /** A leader into the seat without a vote (Government.install): its ceremony in the square, now or as soon as nothing else is on. */
    static void installed(ServerLevel level, UUID village, Government.Form form, VillageFolkEntity f, String how, long day) {
        if (Identity.neutral() || !Government.appointed(form) || Villages.headcount(village) < 3) return;
        Ledger.note(village, "ceremony.due", form.name() + "|" + f.getUUID() + "|" + f.displayNameCap().replace("|", " ") + "|" + how.replace("|", " ") + "|" + day);
        begin(level, village);
    }

    /** The ceremony due, begun if nothing else is on (Politics, a few times a minute, until it has been held or a day has gone). */
    static boolean begin(ServerLevel level, UUID village) {
        String due = Ledger.note(village, "ceremony.due");
        if (due == null || due.isEmpty()) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        String[] p = due.split("\\|", -1);
        long day = level.getDayTime() / 24000L;
        try {
            if (p.length < 5 || day - Long.parseLong(p[4]) > 1) { Ledger.note(village, "ceremony.due", ""); return false; }
        } catch (NumberFormatException e) {
            Ledger.note(village, "ceremony.due", "");
            return false;
        }
        if (Assemblies.underWay(village) != null || Raids.underAlarm(village)) return false;
        BlockPos at = Polls.speechSpot(level, v);
        Direction face = VillageBoards.facingOf(village);
        Assemblies.Assembly a = new Assemblies.Assembly(village, Assemblies.Kind.CEREMONY, due, day, at, face != null ? face : Direction.SOUTH,
            Assemblies.Layout.ARC);
        boolean began = Assemblies.beginNow(level, a);
        if (began) Ledger.note(village, "ceremony.due", "");
        return began;
    }

    static Government.Form formOf(Assemblies.Assembly a) {
        Government.Form f = Government.Form.named(a.subject.split("\\|", 2)[0]);
        return f == null ? Government.Form.LORD : f;
    }

    @Nullable
    static UUID installedOf(Assemblies.Assembly a) {
        String[] p = a.subject.split("\\|", -1);
        try { return p.length > 1 ? UUID.fromString(p[1]) : null; } catch (IllegalArgumentException e) { return null; }
    }

    static String nameOf(Assemblies.Assembly a) {
        String[] p = a.subject.split("\\|", -1);
        return p.length > 2 ? p[2] : "";
    }

    /** Does the crowd kneel through it (a coronation)? */
    static boolean kneels(Assemblies.Assembly a) {
        return formOf(a) == Government.Form.LORD;
    }

    /** Who leads it and who stands at the front (Assemblies.prepare). */
    static void prepare(ServerLevel level, Assemblies.Assembly a) {
        Government.Form form = formOf(a);
        UUID who = installedOf(a);
        a.principals.clear();
        VillageFolkEntity officiant = null;
        int oldest = -1;
        for (VillageFolkEntity f : Identity.grown(a.village)) {
            if (f.getUUID().equals(who)) continue;
            if (form == Government.Form.CHAPLAIN && Values.weight(f, Values.Value.TRADITION) < 30) continue;
            if (f.ageYears() > oldest) { oldest = f.ageYears(); officiant = f; }
        }
        switch (form) {
            case JUNTA -> {
                a.host = who;
                for (VillageFolkEntity f : Elections.voters(a.village)) {
                    if (!f.getUUID().equals(who) && a.principals.size() < 4) a.principals.add(f.getUUID());
                }
            }
            case PLANNED -> {
                a.host = officiant != null ? officiant.getUUID() : who;
                if (who != null) a.principals.add(who);
                for (VillageFolkEntity f : Elections.voters(a.village)) {
                    if (!f.getUUID().equals(who) && !f.getUUID().equals(a.host) && a.principals.size() < 5) a.principals.add(f.getUUID());
                }
            }
            default -> {
                a.host = officiant != null ? officiant.getUUID() : who;
                if (who != null && !who.equals(a.host)) a.principals.add(who);
            }
        }
    }

    /** What is said, each form its own. */
    static void script(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        Government.Form form = formOf(a);
        UUID who = installedOf(a);
        String name = nameOf(a), town = Villages.name(a.village);
        Villages.Village v = Villages.get(a.village);
        switch (form) {
            case LORD -> {
                String house = Identity.known(a.village) == null ? "" : Identity.known(a.village).house;
                s.add(new Assemblies.Line(null, "Kneel, all of you, for the lord of " + town + ".", '~', null));
                s.add(new Assemblies.Line(null, house.isEmpty() ? name + " takes the seat today." : name + ", of " + house + ", takes the seat today.", '~', null));
                s.add(new Assemblies.Line(who, FolkTalk.pick(r, "I'll hold it as it was held before me.", "I'll be fair, or try to be.",
                    "My door is open. Mostly.", "The seat is heavy. I'll carry it.", "I'll keep the town safe and the roads open.",
                    "I didn't ask for this. I'll do it properly."), ' ', null));
                s.add(new Assemblies.Line(null, "Then wear it, and rule well.", '!', () -> crown(level, v, who)));
            }
            case PLANNED -> {
                s.add(new Assemblies.Line(null, "The congress is in session. One item: the chair of the committee.", '~', null));
                s.add(new Assemblies.Line(null, "The committee proposes " + name + ". Those in favour?", ' ', () -> hands(level, a)));
                s.add(new Assemblies.Line(null, "Carried. " + name + " chairs the committee from today.", '!', () -> crown(level, v, who)));
                s.add(new Assemblies.Line(who, FolkTalk.pick(r, "The plan for the week: the fields first, then the timber, then the forge.",
                    "Every hand has its place on the board by the hall. Read it.", "Quotas are up by the hall. We met last week's; we'll meet this one.",
                    "Nobody goes short. That is the whole of the plan.", "The rations are fair and the work is shared.",
                    "Questions go to the committee, in writing."), '?', null));
            }
            case JUNTA -> {
                s.add(new Assemblies.Line(who, "Officers, to me.", '~', () -> salute(level, a)));
                s.add(new Assemblies.Line(who, "I take command of " + town + " from today.", '?', null));
                s.add(new Assemblies.Line(who, FolkTalk.pick(r, "The curfew stands. Indoors by dark.", "The watch walks every road, day and night.",
                    "No gatherings without leave. That's for everyone's good.", "Keep to your work and you'll not see much of me.",
                    "The gates shut at sunset.", "Report anything amiss to an officer."), '?', () -> crown(level, v, who)));
                s.add(new Assemblies.Line(null, "Yes, General.", ' ', null));
            }
            case CHAPLAIN -> {
                s.add(new Assemblies.Line(null, "Let us be still a moment.", '~', null));
                s.add(new Assemblies.Line(null, "The chapel has chosen " + name + " to lead us.", '~', () -> crown(level, v, who)));
                s.add(new Assemblies.Line(who, FolkTalk.pick(r, "I'll keep the day of rest, and the town with it.", "Come to the chapel, or don't; my door is open.",
                    "The old ways have served us. They will again.", "I'll lead gently.", "Mind each other, and I'll mind the rest.",
                    "Feasts mid-week, as ever."), '!', null));
            }
            default -> s.add(new Assemblies.Line(null, name + " takes the seat.", '!', () -> crown(level, v, who)));
        }
    }

    /** The circlet, the sash, the mantle: the regalia worn from now (the court looked at afresh), a star over its head, a few rockets. */
    static void crown(ServerLevel level, @Nullable Villages.Village v, @Nullable UUID who) {
        if (v == null) return;
        Regalia.freshForTests(v.id());
        if (who != null && level.getEntity(who) instanceof VillageFolkEntity f) {
            FolkAction.mark(f, FolkAction.Mark.STAR);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, f.getX(), f.getY() + 2.0, f.getZ(), 30, 0.4, 0.5, 0.4, 0.2);
            level.playSound(null, f.blockPosition(), SoundEvents.ARMOR_EQUIP_GOLD.value(), SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
        FireworkShows.salute(level, v, Polls.speechSpot(level, v), 3);
    }

    /** The committee's hands up together. */
    static void hands(ServerLevel level, Assemblies.Assembly a) {
        for (UUID u : a.principals) {
            if (level.getEntity(u) instanceof VillageFolkEntity f) FolkAction.play(f, FolkAction.Act.CHEER, 40);
        }
    }

    /** The officers salute. */
    static void salute(ServerLevel level, Assemblies.Assembly a) {
        for (UUID u : a.principals) {
            if (level.getEntity(u) instanceof VillageFolkEntity f) FolkAction.play(f, FolkAction.Act.SALUTE, 60);
        }
        level.playSound(null, a.focus, SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 2.0F, 0.8F);
    }

    static void closed(ServerLevel level, Assemblies.Assembly a) {
        long day = level.getDayTime() / 24000L;
        Villages.tell(a.village, day, describe(a) + " was held before the board, " + a.seated.size() + " folk looking on");
    }

    static String describe(Assemblies.Assembly a) {
        String name = nameOf(a);
        return switch (formOf(a)) {
            case LORD -> "the coronation of " + name;
            case PLANNED -> "the party congress";
            case JUNTA -> "the change of command";
            case CHAPLAIN -> "the chapel's choice of " + name;
            default -> name + "'s taking of the seat";
        };
    }

    // ------------------------------------------------------------------ an election won

    /** The winner installed at the count: the rockets, the regalia, a star, the losers' posters down, the notice says who won. */
    static void elected(ServerLevel level, Villages.Village v, UUID winner, @Nullable UUID before, Elections.Result res, long day) {
        Regalia.freshForTests(v.id());
        if (Identity.neutral() && !Polls.visualForTests) return;           // a test's plain town: no rockets out of its stores
        if (level.getEntity(winner) instanceof VillageFolkEntity f) {
            FolkAction.mark(f, FolkAction.Mark.STAR);
            FolkAction.play(f, FolkAction.Act.WAVE, 60);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, f.getX(), f.getY() + 2.0, f.getZ(), 24, 0.4, 0.5, 0.4, 0.2);
        }
        FireworkShows.salute(level, v, Polls.speechSpot(level, v), 4);
        Polls.tearDown(level, v, k -> !k.equals(winner), "the losing candidates' posters");
    }

    /** The chain of office from the one who led to the one who leads. */
    static void handOver(ServerLevel level, UUID village, UUID from, UUID to) {
        Entity2 a = Entity2.of(level, from), b = Entity2.of(level, to);
        if (a.f != null) FolkAction.play(a.f, FolkAction.Act.HAND_OVER, 50);
        if (b.f != null) FolkAction.play(b.f, FolkAction.Act.HAND_OVER, 50);
        if (a.f != null && b.f != null) {
            for (int i = 0; i <= 6; i++) {
                double t = i / 6.0;
                level.sendParticles(ParticleTypes.WAX_ON, a.f.getX() + (b.f.getX() - a.f.getX()) * t, a.f.getY() + 1.3,
                    a.f.getZ() + (b.f.getZ() - a.f.getZ()) * t, 2, 0.05, 0.05, 0.05, 0.0);
            }
            level.playSound(null, b.f.blockPosition(), SoundEvents.ARMOR_EQUIP_CHAIN.value(), SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
        Regalia.freshForTests(village);
    }

    private record Entity2(@Nullable VillageFolkEntity f) {
        static Entity2 of(ServerLevel level, UUID u) {
            return new Entity2(level.getEntity(u) instanceof VillageFolkEntity f ? f : null);
        }
    }

    /** The winner's speech: thanks, plainly, and what it will do first. */
    static String victorySpeech(RandomSource r, Elections.Candidate win, String title) {
        String open = FolkTalk.pick(r, "Thank you. Those who voted for me, and those who didn't: I'm your " + title + " too.",
            "Well. That's settled, then.", "Thank you all for voting. It was close enough to keep me honest.",
            "I'll say this once: I work for all of you now.", "Thank you. The counters did a fine job.", "Right. Thank you. Now the work.");
        String first = switch (win.platform()) {
            case FOOD -> " The fields come first.";
            case HOMES -> " Houses first: I'll see the lots marked out this week.";
            case PROGRESS -> " First, the workshops and the school.";
            case SAFETY -> " First, the watch, and lamps on the streets.";
            case WEALTH -> " First, a look at the taxes.";
            case LEISURE -> " First, a rest day. You've earned it.";
            case TRADITION -> " Nothing much changes, and that's the point.";
        };
        return open + first;
    }

    /** Tests: a ceremony due, its subject, or "". */
    public static String dueForTests(UUID village) {
        String d = Ledger.note(village, "ceremony.due");
        return d == null ? "" : d;
    }

    /** Lines of the last ceremony's script for a form, for tests (no gathering needed). */
    public static List<String> linesForTests(ServerLevel level, UUID village, Government.Form form, VillageFolkEntity f) {
        Assemblies.Assembly a = new Assemblies.Assembly(village, Assemblies.Kind.CEREMONY, form.name() + "|" + f.getUUID() + "|" + f.displayNameCap() + "|test|0",
            0, f.blockPosition(), Direction.SOUTH, Assemblies.Layout.ARC);
        List<Assemblies.Line> s = new ArrayList<>();
        script(level, a, s, level.getRandom());
        List<String> out = new ArrayList<>();
        for (Assemblies.Line l : s) out.add(l.text());
        return out;
    }
}
