package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * [pa33b] Its sense of humour, at its place in the personality triangle: remarks on what is going on (always a line
 * of their own, after anything useful, held to its chattiness and a rate limit), its reaction when its player dies
 * (by the cause, then where the things are, in plain words, with an offer to fetch them), comebacks when its player
 * gives it lip, jokes on request, and, at the top chattiness at the Funny corner, the Joke Machine's running commentary.
 * Warnings are never replaced by a joke: they are said plainly first (PaPhrases.DANGER), and a remark may follow.
 */
public final class PaBanter {

    private PaBanter() {}

    /** How likely a remark is at this blend: the Serious corner keeps quiet, the Funny and Gloomy corners talk. */
    static int chance(PaPersona.Tri t) {
        return (int) Math.round(100 * Math.min(1.0, t.funny() + t.gloomy() * 0.7));
    }

    /** Joke Machine: the top chattiness with the dot at (or near) the Funny corner. */
    public static boolean jokeMachine(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        return r != null && r.chatty >= 4 && r.tri().funny() >= 0.8;
    }

    /**
     * A remark on a situation ("quip.rain", "jm.mine"), if its blend and chattiness allow and it has not just spoken.
     * {@code least} is the lowest chattiness at which it may say it. True when it said something.
     */
    public static boolean quip(PlayerAssistantEntity a, String key, int least, @Nullable String moment, String... kv) {
        PaData.Rec r = a.rec();
        if (r == null || r.chatty < least) return false;
        PaPersona.Tri t = r.tri();
        if (a.getRandom().nextInt(100) >= chance(t)) return false;
        String raw = PaPersona.remark(key, t, r.look, moment, a.getRandom()::nextInt, a.mind.recent);
        if (raw == null) return false;
        return PaTalk.comment(a, least, fill(a, raw, kv));
    }

    /** A remark straight after a warning (the warning has just been said plainly): no wait, but still its blend and chattiness. */
    public static boolean after(PlayerAssistantEntity a, String key, @Nullable String moment) {
        PaData.Rec r = a.rec();
        if (r == null || r.chatty < 1 || a.getRandom().nextInt(100) >= chance(r.tri())) return false;
        String raw = PaPersona.remark(key, r.tri(), r.look, moment, a.getRandom()::nextInt, a.mind.recent);
        if (raw == null) return false;
        PaTalk.reply(a, fill(a, raw));
        return true;
    }

    static String fill(PlayerAssistantEntity a, String raw, String... kv) {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("owner", a.ownerName().isEmpty() ? "you" : a.ownerName());
        m.put("name", a.displayNameCap());
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return PaPersona.fill(raw, m);
    }

    // ------------------------------------------------------------------ its player dies

    /** The cause, as the lines know it ("death.lava"), from the damage and whatever dealt it. */
    public static String cause(DamageSource s) {
        Entity by = s.getEntity() != null ? s.getEntity() : s.getDirectEntity();
        if (s.is(DamageTypes.LAVA)) return "lava";
        if (s.is(DamageTypes.FELL_OUT_OF_WORLD)) return "void";
        if (s.is(DamageTypes.FALL) || s.is(DamageTypes.FLY_INTO_WALL)) return "fall";
        if (s.is(DamageTypes.DROWN)) return "drown";
        if (s.is(DamageTypes.STARVE)) return "starve";
        if (s.is(DamageTypes.IN_WALL) || s.is(DamageTypes.CRAMMING)) return "suffocate";
        if (s.is(DamageTypes.CACTUS)) return "cactus";
        if (s.is(DamageTypes.SWEET_BERRY_BUSH)) return "berry";
        if (s.is(DamageTypeTags.IS_FIRE)) return "fire";
        if (by instanceof net.minecraft.world.entity.monster.Creeper) return "creeper";
        if (by instanceof net.minecraft.world.entity.monster.AbstractSkeleton) return "skeleton";
        if (by instanceof net.minecraft.world.entity.monster.Zombie) return "zombie";
        if (by instanceof net.minecraft.world.entity.monster.EnderMan) return "enderman";
        if (by instanceof net.minecraft.world.entity.monster.warden.Warden) return "warden";
        if (by instanceof net.minecraft.world.entity.animal.IronGolem) return "golem";
        if (by instanceof net.minecraft.world.entity.npc.AbstractVillager || by instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity) return "villager";
        return "generic";
    }

    /** Things that went where nobody can fetch them back from. */
    static boolean lost(String cause) {
        return PaQuips.lost(cause);
    }

    /** Its player has died: one remark (lightly rated), then where their things are and an offer to go and get them. */
    public static void ownerDied(ServerPlayer p, DamageSource source) {
        PlayerAssistantEntity a = PaLife.find(p);
        if (a == null) return;
        PaData.Rec r = a.rec();
        if (r == null) return;
        long now = p.serverLevel().getGameTime();
        String cause = cause(source);
        boolean diamonds = p.getInventory().countItem(Items.DIAMOND) > 0 || p.getInventory().hasAnyMatching(st -> st.getItem().toString().contains("diamond"));
        boolean repeat = a.mind.deathAt > 0 && now - a.mind.deathAt < 60 * 20;
        BlockPos bed = p.getRespawnPosition();
        boolean nearBed = bed != null && p.getRespawnDimension() == p.level().dimension() && bed.distSqr(p.blockPosition()) < 6 * 6;
        a.mind.deathsRecent = now - a.mind.deathAt < 10 * 60 * 20 ? a.mind.deathsRecent + 1 : 1;
        a.mind.prevDeathAt = a.mind.deathAt;
        a.mind.deathAt = now;
        a.mind.deathPos = p.blockPosition().immutable();
        a.mind.deathDim = p.level().dimension().location().toString();
        a.mind.deathCause = cause;
        String key = deathKey(cause, repeat, nearBed, diamonds, a.getRandom()::nextInt);
        // lightly rated: dying a lot, it doesn't remark on every one
        boolean remark = r.chatty > 0 && a.getRandom().nextInt(100) < chance(r.tri()) && (a.mind.deathsRecent < 3 || a.getRandom().nextInt(2) == 0);
        if (remark) {
            String raw = PaPersona.remark("death." + key, r.tri(), r.look, cause, a.getRandom()::nextInt, a.mind.recent);
            if (raw != null) PaTalk.reply(a, fill(a, raw));
        }
        PaTalk.reply(a, whereThings(a, p, cause), fetchChoices(cause));
    }

    public static String deathKey(String cause, boolean repeat, boolean nearBed, boolean diamonds, PaPhrases.Dice d) {
        return PaQuips.deathKey(cause, repeat, nearBed, diamonds, d);
    }

    /** The useful part, in plain words: where the things are, which world, how long they'll last, or that they're gone. */
    static String whereThings(PlayerAssistantEntity a, ServerPlayer p, String cause) {
        BlockPos at = a.mind.deathPos;
        String world = a.mind.deathDim.endsWith("the_nether") ? " in the Nether" : a.mind.deathDim.endsWith("the_end") ? " in the End" : "";
        if (cause.equals("void")) return "Your things fell into the void with you, " + p.getGameProfile().getName() + ". There's nothing left to fetch, I'm afraid.";
        if (cause.equals("lava")) return "Your things went into the lava at " + at.getX() + " " + at.getY() + " " + at.getZ() + world
            + ". Most will have burnt; anything left will be at the edge. Too dangerous for me to fish about in.";
        return "Your things are at " + at.getX() + " " + at.getY() + " " + at.getZ() + world + ". They'll vanish in about five minutes. Shall I fetch them?";
    }

    static List<PaUnderstand.Choice> fetchChoices(String cause) {
        if (lost(cause)) return List.of(new PaUnderstand.Choice("OK", "thanks"));
        return List.of(new PaUnderstand.Choice("Yes, fetch them", "#fetchdeath"), new PaUnderstand.Choice("No, I'll go", "#nofetch"));
    }

    /** "Yes, fetch them": off it goes to sweep them up, if it can safely; else it says why not. */
    public static void fetchDeath(PlayerAssistantEntity a) {
        BlockPos at = a.mind.deathPos;
        if (at == null || a.mind.deathAt < 0) { PaTalk.reply(a, "I don't know where you fell. Sorry."); return; }
        if (lost(a.mind.deathCause)) { PaTalk.reply(a, "I can't get them back from there. Not safely, and not at all, really."); return; }
        if (!a.level().dimension().location().toString().equals(a.mind.deathDim)) {
            PaTalk.reply(a, "Your things are in another world from me. Take me there and I'll fetch them.");
            return;
        }
        if (a.level().getGameTime() - a.mind.deathAt > 5 * 60 * 20) { PaTalk.reply(a, "It's been too long, I'm afraid. They'll have vanished."); return; }
        PaPlan.Step s = PaOrders.step("fetch your things", PaIntent.Kind.FETCH, 60);
        s.jobs.add(com.jrpetty.mcassistant.entity.Job.recover(at.getX() + " " + at.getY() + " " + at.getZ()));
        a.plan.steps.add(0, s);
        a.plan.paused = false;
        PaTalk.reply(a, "Going for your things at " + at.getX() + " " + at.getY() + " " + at.getZ() + ". They'll be in my pack.");
    }

    /** A while after its player respawns: a follow-up, sometimes. */
    public static void respawned(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null || a.getRandom().nextInt(100) >= 60) return;
        String raw = PaPersona.remark("respawn.follow", r.tri(), r.look, null, a.getRandom()::nextInt, a.mind.recent);
        if (raw != null) PaTalk.comment(a, 1, fill(a, raw));
    }

    // ------------------------------------------------------------------ comebacks and jokes

    public static PaQuips.Comeback comeback(String text) {
        return PaQuips.comeback(text);
    }

    /** Its answer to lip or praise: in kind, never sulking; "shut up" turns its chatter down a notch. */
    public static String answer(PlayerAssistantEntity a, PaQuips.Comeback cb) {
        PaData.Rec r = a.rec();
        PaPersona.Tri t = r == null ? a.tri() : r.tri();
        if (cb == PaQuips.Comeback.JOKE) {
            String intro = PaPersona.from(PaLines.get("comeback.joke"), a.getRandom()::nextInt, a.mind.recent);
            String joke = PaPersona.from(PaLines.get("joke"), a.getRandom()::nextInt, a.mind.recent);
            return (intro == null ? "" : intro + " ") + (joke == null ? "I've forgotten all my jokes. That's the joke." : joke);
        }
        if (cb == PaQuips.Comeback.SHUTUP && r != null) {
            r.chatty = Math.max(0, r.chatty - 1);
            PaData.touch();
        }
        String key = "comeback." + cb.name().toLowerCase(Locale.ROOT);
        String raw = PaPersona.remark(key, t, r == null ? 0 : r.look, null, a.getRandom()::nextInt, a.mind.recent);
        if (raw == null) raw = PaPersona.from(PaLines.get(key + "@serious"), a.getRandom()::nextInt, a.mind.recent);
        return raw == null ? "Noted." : fill(a, raw);
    }

    /** A line names something it can be told apart by: kept for tests (the patterns compile). */
    static final Pattern WORD = Pattern.compile("[a-z']+");
}
