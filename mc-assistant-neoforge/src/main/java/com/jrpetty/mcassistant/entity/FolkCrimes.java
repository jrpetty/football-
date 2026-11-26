package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.JusticeConfig;
import com.jrpetty.mcassistant.entity.Justice.Charge;
import com.jrpetty.mcassistant.entity.Justice.JCase;
import com.jrpetty.mcassistant.entity.Justice.Stage;
import com.jrpetty.mcassistant.entity.Justice.Wit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpe] The serious crimes of folk, rare: a grudge that comes to blows (an assault, its victim and whoever saw it the
 * witnesses), and, only for the most dishonest and only when its own skin is at stake, a witness to that assault
 * silenced before it tells the watch: a killing done where nobody sees, investigated as a murder like any other (Justice).
 * The watch takes a folk assailant into the cells (on the lead, in irons) for a few days, and makes it pay its victim;
 * a folk murderer, if the court convicts, is banished.
 */
final class FolkCrimes {

    private FolkCrimes() {}

    /** By town: the day last looked at. */
    private static final Map<UUID, Long> DAY = new ConcurrentHashMap<>();

    static void resetForTests() {
        DAY.clear();
    }

    /** Once a day a town (Justice.round): perhaps a brawl; perhaps, after one, a witness silenced. */
    static void daily(ServerLevel level, Villages.Village v) {
        if (!JusticeConfig.folkSeriousCrime()) return;
        long day = level.getDayTime() / 24000L, t = Math.floorMod(level.getDayTime(), 24000L);
        Long last = DAY.get(v.id());
        if (last != null && last == day) return;
        if (t < 6000 || t > 11000) return;                                  // in the afternoon, when tempers fray
        DAY.put(v.id(), day);
        RandomSource r = level.getRandom();
        if (r.nextInt(14) == 0) brawl(level, v, null, null);
    }

    /** The night after a brawl: the assailant, if it is rotten enough, sees to the one witness who hasn't yet talked. */
    static void night(ServerLevel level, Villages.Village v) {
        if (!JusticeConfig.folkSeriousCrime()) return;
        long t = Math.floorMod(level.getDayTime(), 24000L);
        if (t < 13500 || t > 22000) return;
        for (JCase c : Justice.cases(v.id())) {
            if (c.player || c.charge != Charge.ASSAULT || c.stage != Stage.SEEN && c.stage != Stage.REPORTED) continue;
            VillageFolkEntity a = Civics.find(level, c.accused);
            if (a == null || !a.isAlive() || Mischief.honesty(a) >= 15) continue;
            Wit only = null;
            int unseen = 0;
            for (Wit w : c.witnesses) if (!w.victim && !w.reported && !w.lost) { only = w; unseen++; }
            boolean told = false;
            for (Wit w : c.witnesses) if (w.reported) told = true;
            if (only == null || unseen != 1 || told || c.notes.contains("silencing tried")) continue;
            c.notes.add("silencing tried");
            if (level.getRandom().nextInt(4) != 0 && !Justice.hurry) continue;
            silence(level, v, a, only);
            return;
        }
    }

    /** A grudge comes to blows: one folk strikes another, in sight of whoever is about. Returns the case, or null. */
    @Nullable
    static JCase brawl(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity attacker, @Nullable VillageFolkEntity victim) {
        if (attacker == null) {
            List<VillageFolkEntity> bad = new ArrayList<>();
            for (AssistantEntity x : Villages.folkOf(v.id())) {
                if (!(x instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive() || f.isSleeping()) continue;
                if (f.stationTask() == AssistantEntity.StationTask.GUARD || f.getUUID().equals(Villages.elder(v.id()))) continue;
                if (Mischief.honesty(f) < 35 && f.life().has(Social.Trait.GRUMPY)) bad.add(f);
            }
            if (bad.isEmpty()) return null;
            attacker = bad.get(level.getRandom().nextInt(bad.size()));
        }
        if (victim == null) {
            VillageFolkEntity a = attacker;
            for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, a.getBoundingBox().inflate(12.0),
                    f -> f != a && !f.isBaby() && f.isAlive() && f.stationTask() != AssistantEntity.StationTask.GUARD)) {
                if (victim == null || f.distanceTo(a) < victim.distanceTo(a)) victim = f;
            }
        }
        if (victim == null) return null;
        FolkTalk.speak(attacker, FolkTalk.pick(attacker.getRandom(), "I've had enough of you, " + victim.displayNameCap() + "!",
            "Say that again. Go on.", "You've had this coming.", "That's the last time you cross me.", "Right. Outside. Now. Never mind, here's fine.",
            "Think you're better than me?"));
        attacker.getLookControl().setLookAt(victim, 30.0F, 30.0F);
        attacker.swing(InteractionHand.MAIN_HAND);
        victim.hurt(level.damageSources().mobAttack(attacker), 3.0F);
        victim.sayLater(FolkTalk.pick(victim.getRandom(), "Ow! Help! Watch!", "You'll hang for that! Well. Be fined.", "What's got into you?",
            "Somebody fetch the watch!", "Have you gone mad?", "Get off me!"), 20);
        JCase c = Justice.open(level, v.id(), Charge.ASSAULT, attacker.getUUID(), attacker.displayNameCap(), false, victim.getUUID(),
            victim.displayNameCap(), false, Justice.placeOf(level, v, victim.blockPosition()), victim.blockPosition());
        c.intent = 1;
        c.notes.add(attacker.displayNameCap() + " struck " + victim.displayNameCap() + " in a quarrel");
        long now = level.getGameTime();
        for (VillageFolkEntity f : seers(level, attacker, victim)) Justice.addWitness(c, f, false, now, level.getRandom());
        Justice.addWitness(c, victim, true, now, level.getRandom());
        Villages.tell(v.id(), c.day, attacker.displayNameCap() + " struck " + victim.displayNameCap() + " in a quarrel");
        Justice.changed();
        return c;
    }

    /** Folk who can see this folk now. */
    static List<VillageFolkEntity> seers(ServerLevel level, VillageFolkEntity who, @Nullable VillageFolkEntity not) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, who.getBoundingBox().inflate(Witness.SIGHT),
                f -> f != who && f != not && !f.isBaby() && f.isAlive() && !f.isSleeping() && f.hasLineOfSight(who))) out.add(f);
        return out;
    }

    /** The witness killed where nobody sees, if nobody does; the killing a case of its own (Justice.died). */
    static void silence(ServerLevel level, Villages.Village v, VillageFolkEntity a, Wit w) {
        VillageFolkEntity target = Civics.find(level, w.id);
        if (target == null || !target.isAlive()) return;
        List<VillageFolkEntity> seen = seers(level, a, target);
        JCase k = Justice.open(level, v.id(), Charge.MURDER, a.getUUID(), a.displayNameCap(), false, target.getUUID(), target.displayNameCap(), false,
            Justice.placeOf(level, v, target.blockPosition()), target.blockPosition());
        k.intent = 2;
        k.notes.add(target.displayNameCap() + " was found dead");
        long now = level.getGameTime();
        for (VillageFolkEntity f : seen) Justice.addWitness(k, f, false, now, level.getRandom());
        target.hurt(level.damageSources().mobAttack(a), 1000.0F);
        if (seen.isEmpty()) k.notes.add("nobody saw it");
        Justice.changed();
    }

    /** A folk named by the watch (Justice.name): an assailant into the cells for a few days and its victim paid; a killer held for the court. */
    static void named(ServerLevel level, Villages.Village v, JCase c) {
        VillageFolkEntity f = Civics.find(level, c.accused);
        VillageFolkEntity g = Arrests.officerFor(level, v, c.where);
        if (f == null || !f.isAlive() || g == null) return;
        if (c.charge.killing()) {
            if (WatchHouse.arrest(level, v, g, f, 0, c.charge.word, -1)) {
                c.stage = Stage.REMAND;
                c.courtAt = level.getGameTime() + Justice.pause(2400);
            }
            Justice.changed();
            return;
        }
        long until = level.getDayTime() + 24000L * 2;
        WatchHouse.arrest(level, v, g, f, 0, c.charge.word, until);
        int comp = Math.min(f.purse(), 6);
        if (comp > 0 && c.victim != null) {
            f.spend(comp);
            VillageFolkEntity vic = Civics.find(level, c.victim);
            if (vic != null) vic.earn(comp);
        }
        c.stage = Stage.CONVICTED;
        c.verdict = "guilty";
        c.finding = "assault";
        c.sentence = "two days in the cells" + (comp > 0 ? ", " + comp + " coins to " + c.victimName : "");
        Justice.convicted(c, c.sentence);
        Police.log(v.id(), level.getDayTime(), "verdict", c.accusedName + " was taken in for assault: " + c.sentence, g, comp);
        Justice.changed();
    }

    /** A folk convicted of a killing (Assize): banished. */
    static void banish(ServerLevel level, Villages.Village v, JCase c) {
        VillageFolkEntity f = Civics.find(level, c.accused);
        CompoundTagHolder.release(level, f);
        if (f == null) return;
        Villages.tell(v.id(), c.day, c.accusedName + " was banished from " + Villages.name(v.id()) + " for " + c.finding);
        Villages.Village to = Contentment.happiest(v);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Fine. I'll go.", "You'll not see me again.", "I've nobody to blame but myself.",
            "This town was never mine.", "Keep it, then.", "Don't follow me."));
        if (to != null && to != v) f.leaveFor(level, to);
        else f.walkOut();
    }

    /** Out of the watch's keeping, for the road. */
    static final class CompoundTagHolder {
        static void release(ServerLevel level, @Nullable VillageFolkEntity f) {
            if (f == null) return;
            Police.custody().remove(f.getStringUUID());
            WatchHouse.unlead(level, f, null, null);
            Police.changed();
        }
    }
}
