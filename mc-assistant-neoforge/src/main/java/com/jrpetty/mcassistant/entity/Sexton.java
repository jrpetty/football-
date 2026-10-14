package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [roles-a] The sexton: a post, not a trade (its holder keeps its own trade and gives the chapel part of its day), in a
 * town of eight or more with a chapel.
 *
 * <ul>
 * <li><b>Who.</b> The town reckons it each day from who is best (an older, steady, quiet hand, never the elder, the
 *     watch or a hand whose day is out of the town), and keeps the one an interview chose while it is fit
 *     (InterviewPosts, kind SEXTON; Interviews.daily).</li>
 * <li><b>The rites.</b> It leads the faith's rites in the elder's place (Beliefs.leader).</li>
 * <li><b>The graves.</b> It is the town's first hand for the graves (TownJobs "graves": {@link #lean}), and over a grave
 *     newly dug it says the words, the folk near by standing with it (Graves.headstone: {@link #laidToRest}).</li>
 * <li><b>The bell.</b> It rings the noon and dusk bells before anybody (TownBell.appoint: {@link #ringLean}).</li>
 * <li><b>The candles.</b> A candle out of the stores on the chapel's altar if it has none; lit at dusk, snuffed in the
 *     morning, by its own hand (TownJobs "chapel", the sexton first).</li>
 * <li><b>Its pay.</b> An office's allowance on its wage (Offices), out of the treasury the week's tithe fills.</li>
 * </ul>
 * Cost: the holder looked up at most every five seconds a town; the candles looked at once a second, only at dusk and
 * in the morning.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Sexton {

    private Sexton() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The post's key in the interviews' books. */
    static final String POST = "sexton";
    static final String CHAPEL = "chapel";
    /** The smallest town that keeps a sexton. */
    static final int FROM_FOLK = 8;
    /** The candles lit from (a little before the dusk bell, so the chapel is lit by it) and snuffed from (after the dawn bell). */
    static final long LIGHT_FROM = 11400L, LIGHT_TILL = 12450L, SNUFF_FROM = 1000L, SNUFF_TILL = 4000L;
    /** How near a grave the sexton must be to say the words over it, and how near the folk who stand with it. */
    static final double AT_GRAVE = 24.0, MOURNERS = 16.0;

    /** Each town's sexton, looked up at most every five seconds: {when, who}. */
    private static final Map<UUID, Object[]> HOLDER = new ConcurrentHashMap<>();

    public static void resetForTests() {
        HOLDER.clear();
    }

    /** The town's sexton looked up afresh at the next ask (a new one chosen at interview). */
    static void forget(UUID id) {
        HOLDER.remove(id);
    }

    // ------------------------------------------------------------------ who

    /** Does the town keep a sexton: a chapel standing, and eight folk or more? */
    public static boolean stands(@Nullable UUID id) {
        return id != null && Villages.builtAt(id, CHAPEL) != null && Villages.headcount(id) >= FROM_FOLK;
    }

    /** Free for the post: grown and steady, at home, not the elder, nor a hand whose day is the watch's or out of the town. */
    static boolean fit(VillageFolkEntity f, UUID id) {
        if (!f.isAlive() || f.isBaby() || f.isShowcase() || f.isHired() || f.isElder() || !id.equals(f.ownerId())) return false;
        if (f.trip() != null || f.expedition() != null || f.ageYears() < 25) return false;
        StationTask t = f.stationTask();
        return t != StationTask.GUARD && t != StationTask.NETHER && t != StationTask.CAVE && t != StationTask.SCOUT
            && t != StationTask.EMERALD && t != StationTask.FERRY && t != StationTask.DIVER && t != StationTask.HEAL;
    }

    /** How well suited to the chapel: an older head, a steady, quiet nature. */
    static int score(VillageFolkEntity f) {
        int s = f.ageYears() * 3 / 5;
        if (f.life().has(Social.Trait.EASYGOING)) s += 15;
        if (f.life().has(Social.Trait.SHY)) s += 10;
        if (f.life().has(Social.Trait.HARDWORKING)) s += 10;
        if (f.life().has(Social.Trait.GRUMPY)) s -= 10;
        return s;
    }

    /** The town's sexton: the interview's choice while it is fit, else the best suited; null with no chapel. */
    @Nullable
    public static VillageFolkEntity holder(@Nullable UUID id) {
        if (id == null) return null;
        Villages.Village v = Villages.get(id);
        if (v == null) return null;
        Object[] was = HOLDER.get(id);
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        long now = server == null ? 0L : server.getTickCount();
        if (was != null && now - (Long) was[0] < 100L && now >= (Long) was[0]) {
            VillageFolkEntity f = (VillageFolkEntity) was[1];
            if (f == null || f.isAlive() && fit(f, id)) return f;
        }
        VillageFolkEntity got = reckon(id);
        HOLDER.put(id, new Object[]{ now, got });
        return got;
    }

    @Nullable
    private static VillageFolkEntity reckon(UUID id) {
        if (!stands(id)) return null;
        VillageFolkEntity chosen = Interviews.holder(id, POST);
        if (chosen != null && fit(chosen, id)) return chosen;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !fit(f, id)) continue;
            int s = score(f);
            if (s > bestScore) { bestScore = s; best = f; }
        }
        return best;
    }

    /** Is this folk its town's sexton? */
    public static boolean is(VillageFolkEntity f) {
        return f.ownerId() != null && f == holder(f.ownerId());
    }

    // ------------------------------------------------------------------ its part in the others' work

    /** [TownJobs.choose] The sexton first to the graves and the chapel's candles. */
    static double lean(VillageFolkEntity f, String works) {
        return (works.equals("graves") || works.equals(CHAPEL)) && is(f) ? 90.0 : 0.0;
    }

    /** [TownBell.appoint] The sexton rings the bell at noon and dusk before anybody (at dawn it is abed like the rest). */
    static double ringLean(VillageFolkEntity f, TownBell.Peal p) {
        return p != TownBell.Peal.DAWN && !f.isSleeping() && is(f) ? 150.0 : 0.0;
    }

    /**
     * [Graves.headstone] A grave newly dug and its stone set: the sexton, if it is near, says the words over it, and the
     * folk near by stand with it. Into the chronicle; the dead one's friends remember it.
     */
    static void laidToRest(ServerLevel level, Villages.Village v, String dead, BlockPos grave) {
        VillageFolkEntity s = holder(v.id());
        if (s == null || s.distanceToSqr(grave.getX() + 0.5, grave.getY(), grave.getZ() + 0.5) > AT_GRAVE * AT_GRAVE) return;
        long day = level.getDayTime() / 24000L;
        int mourners = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f == s || f.isBaby() || !f.isAlive()) continue;
            if (f.distanceToSqr(grave.getX() + 0.5, grave.getY(), grave.getZ() + 0.5) > MOURNERS * MOURNERS) continue;
            mourners++;
            f.getLookControl().setLookAt(grave.getX() + 0.5, grave.getY() + 0.5, grave.getZ() + 0.5);
            if (f.persona().rolled()) f.persona().remember(day, "we laid " + dead + " to rest", 2);
        }
        s.getLookControl().setLookAt(grave.getX() + 0.5, grave.getY() + 0.5, grave.getZ() + 0.5);
        FolkTalk.speak(s, FolkTalk.pick(s.getRandom(), "Rest well, " + dead + ". The town won't forget you.",
            "Here lies " + dead + ". Go gently.", "We give " + dead + " back to the earth, and keep the rest in our hearts."));
        level.playSound(null, grave, SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 0.6F, 0.6F);
        Villages.tell(v.id(), day, s.displayNameCap() + " the sexton laid " + dead + " to rest"
            + (mourners > 0 ? ", " + mourners + " of the town standing by" : ""));
        s.persona().remember(day, "I said the words over " + dead + "'s grave", 3);
        LOG.info("[MCA-SEXTON] {} laid {} to rest in {} ({} mourners)", s.displayNameCap(), dead, Villages.name(v.id()), mourners);
    }

    // ------------------------------------------------------------------ the chapel's candles

    /** The chapel's altar (the crafting table of its drawing), or null with no chapel. */
    @Nullable
    static BlockPos altar(UUID id) {
        Ledger.Building b = Villages.builtStructure(id, CHAPEL);
        if (b == null) return null;
        for (BuildGoal.Placement p : BuildGoal.plan(CHAPEL, b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CRAFTING_TABLE) return p.pos().immutable();
        }
        return null;
    }

    /** The candle on the altar, or null. */
    @Nullable
    static BlockPos candle(ServerLevel level, UUID id) {
        BlockPos a = altar(id);
        if (a == null || !level.isLoaded(a)) return null;
        return level.getBlockState(a.above()).is(BlockTags.CANDLES) ? a.above() : null;
    }

    /**
     * At dusk and in the morning: the altar's candle lit, or snuffed, by the sexton's hand (the town's works, the sexton
     * first); a candle out of the stores set on it when it has none. True if anything was done.
     */
    static boolean candles(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!stands(id)) return false;
        long t = level.getDayTime() % 24000L;
        boolean light = t >= LIGHT_FROM && t < LIGHT_TILL, snuff = t >= SNUFF_FROM && t < SNUFF_TILL;
        if (!light && !snuff) return false;
        BlockPos altar = altar(id);
        if (altar == null || !level.isLoaded(altar) || !level.getBlockState(altar).isFaceSturdy(level, altar, net.minecraft.core.Direction.UP)) return false;
        BlockPos at = altar.above();
        BlockState st = level.getBlockState(at);
        boolean has = st.is(BlockTags.CANDLES);
        if (!has && !st.isAir()) return false;
        if (has && st.getValue(CandleBlock.LIT) == light) return false;   // as it should be
        if (!has && (snuff || Market.stock(level, id, s -> s.is(ItemTags.CANDLES)) <= 0)) return false;
        String what = !has ? "setting a candle on the chapel's altar" : light ? "lighting the chapel's candle" : "snuffing the chapel's candle";
        if (!TownJobs.atWork(level, v, CHAPEL, at, what)) return false;
        if (!has) {
            ItemStack c = Crafts.takeOne(level, v, s -> s.is(ItemTags.CANDLES));
            if (c.isEmpty() || !(c.getItem() instanceof BlockItem bi)) {
                if (!c.isEmpty()) Crafts.store(level, v, c);
                return false;
            }
            st = bi.getBlock().defaultBlockState();
            if (!st.hasProperty(CandleBlock.LIT)) st = Blocks.CANDLE.defaultBlockState();
        }
        level.setBlock(at, st.setValue(CandleBlock.LIT, light), 3);
        level.playSound(null, at, light ? SoundEvents.FLINTANDSTEEL_USE : SoundEvents.CANDLE_EXTINGUISH, SoundSource.BLOCKS, 0.6F, 1.0F);
        if (light) level.sendParticles(ParticleTypes.SMALL_FLAME, at.getX() + 0.5, at.getY() + 0.6, at.getZ() + 0.5, 3, 0.05, 0.05, 0.05, 0.0);
        return true;
    }

    // ------------------------------------------------------------------ the town's tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 != 11) return;
        com.jrpetty.mcassistant.Guard.run("sexton", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                long t = level.getDayTime() % 24000L;
                if (!(t >= LIGHT_FROM && t < LIGHT_TILL) && !(t >= SNUFF_FROM && t < SNUFF_TILL)) continue;
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    candles(level, v);
                }
            }
        });
    }

    /** Tests: the chapel's candle seen to now (by the works, done at once with TownJobs.instantForTests). */
    public static boolean candlesForTests(ServerLevel level, Villages.Village v) {
        return candles(level, v);
    }

    /** Tests: the altar's candle, or null. */
    @Nullable
    public static BlockPos candleForTests(ServerLevel level, UUID id) {
        return candle(level, id);
    }

    /** The books' lines ("/village offices"): who keeps the chapel, and its altar's candle. */
    static List<String> lines(ServerLevel level, UUID id) {
        List<String> out = new ArrayList<>();
        if (!stands(id)) return out;
        VillageFolkEntity s = holder(id);
        BlockPos c = candle(level, id);
        out.add("The chapel is kept by " + (s == null ? "nobody yet" : s.displayNameCap() + ", its sexton") + "; the altar "
            + (c == null ? "wants a candle" : level.getBlockState(c).getValue(CandleBlock.LIT) ? "candle is lit" : "candle is out") + ".");
        return out;
    }
}
