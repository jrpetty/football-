package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featlife] The fair: the fourth Saturday of each month (Calendar), in a town of four folk or more that keeps a working week.
 * <ul>
 * <li><b>The traders.</b> One to four (one more for every eight folk), each from a neighbouring town (or "from over the
 *     hill" with none near), walking in from the road out of anybody's sight (Blink: the edge it starts from is past where
 *     any player could see), with a stall's worth of its town's goods: bread, apples, wool, honey, leather, pots, lanterns.
 *     It puts up a stall in the square (Stalls) and sells to the town's folk out of their own purses, and to a player who
 *     asks; it packs up in the late afternoon and walks home with its takings.</li>
 * <li><b>The town's own stalls.</b> Everybody with something spare sets up (WeekendLife: SELL), and the rest come to look
 *     (WeekendLife: FAIR).</li>
 * <li><b>Music and games.</b> A tune on the square through the day, a few bars every few seconds (note-block voices, the
 *     louder for a bigger town), and races across the square that the fair-goers run and the rest cheer.</li>
 * <li><b>Dressed for it</b> by what the town can afford: lanterns on posts round the square, more of them the more the
 *     treasury holds (none if it is nearly empty). Taken down at dusk.</li>
 * </ul>
 */
public final class Fairground {

    private Fairground() {}

    static final long OPEN_FROM = 600L, TRADERS_FROM = 800L, PACK_UP = 10500L, DUSK = 11800L;

    private static final Map<UUID, Long> TOLD = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SENT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SENT_DAY = new ConcurrentHashMap<>(), LAST_SENT = new ConcurrentHashMap<>();
    private static final Map<UUID, List<BlockPos>> LAMPS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> TUNE = new ConcurrentHashMap<>();
    /** Tests: a fair today whatever the day. */
    private static final java.util.Set<UUID> FORCED = ConcurrentHashMap.newKeySet();

    public static void resetForTests() {
        TOLD.clear();
        SENT.clear();
        SENT_DAY.clear();
        LAST_SENT.clear();
        LAMPS.clear();
        TUNE.clear();
        FORCED.clear();
    }

    public static void forceForTests(UUID village, boolean on) {
        if (on) FORCED.add(village);
        else FORCED.remove(village);
    }

    /** Is the fair on in this town today? */
    public static boolean on(UUID village, long day) {
        if (FORCED.contains(village)) return true;
        return Calendar.isFairDay(day) && Villages.headcount(village) >= 4 && WorkHours.kept(village, day);
    }

    /** How many traders come: one, and one more for every eight folk, four at most. */
    public static int traders(UUID village) {
        return Math.max(1, Math.min(4, 1 + Villages.headcount(village) / 8));
    }

    /** The lanterns it can afford round the square: none nearly broke, up to twelve well off. */
    static int lamps(UUID village) {
        long coins = Ledger.coins(village);
        return coins < 20 ? 0 : (int) Math.min(12, 4 + coins / 50);
    }

    // ------------------------------------------------------------------ the town's round

    static void round(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        if (!on(id, day)) {
            if (LAMPS.containsKey(id) || Ledger.note(id, "life.lamps") != null && !Ledger.note(id, "life.lamps").isEmpty()) takeDown(level, id);
            return;
        }
        if (t >= DUSK) {
            takeDown(level, id);
            return;
        }
        if (t < OPEN_FROM) return;
        if (!Long.valueOf(day).equals(TOLD.get(id))) {
            TOLD.put(id, day);
            dress(level, v);
            Villages.tell(id, day, "the fair came to " + Villages.name(id));
            for (ServerPlayer p : level.players()) {
                if (p.blockPosition().closerThan(v.centre(), 160)) {
                    p.displayClientMessage(Component.literal("Fair day in " + Villages.name(id) + ": stalls in the square, traders in from round about."), true);
                    Planner.add(p, day * 24000L + PACK_UP, "fair", "The fair in " + Villages.name(id) + " packs up", v.centre());
                }
            }
        }
        if (t >= TRADERS_FROM && t < PACK_UP - 2000) sendTraders(level, v, day);
        if (t < PACK_UP) music(level, v);
    }

    /** A trader on the road in, one a minute, till the day's are all sent. */
    static void sendTraders(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!Long.valueOf(day).equals(SENT_DAY.get(id))) {
            SENT_DAY.put(id, day);
            SENT.put(id, 0);
        }
        int sent = SENT.getOrDefault(id, 0);
        if (sent >= traders(id) || level.getGameTime() - LAST_SENT.getOrDefault(id, -10000L) < 1200L) return;
        if (Raids.underAlarm(id) || Weather.stormy(level)) return;
        LAST_SENT.put(id, level.getGameTime());
        if (comeIn(level, v, day, sent) != null) SENT.put(id, sent + 1);
    }

    /** The neighbouring towns, nearest first. */
    static List<Villages.Village> neighbours(Villages.Village v) {
        List<Villages.Village> out = new ArrayList<>();
        for (Villages.Village o : Villages.every()) if (!o.id().equals(v.id())) out.add(o);
        out.sort(java.util.Comparator.comparingDouble(o -> o.centre().distSqr(v.centre())));
        return out;
    }

    /** What a neighbouring town sends to a fair: three lots of its goods. */
    static List<ItemStack> goods(RandomSource r) {
        List<ItemStack> all = new ArrayList<>(List.of(new ItemStack(Items.BREAD, 6), new ItemStack(Items.APPLE, 8), new ItemStack(Items.HONEY_BOTTLE, 2),
            new ItemStack(Items.WHITE_WOOL, 4), new ItemStack(Items.LEATHER, 3), new ItemStack(Items.FLOWER_POT, 2), new ItemStack(Items.LANTERN, 1),
            new ItemStack(Items.PUMPKIN_PIE, 2), new ItemStack(Items.CAKE, 1), new ItemStack(Items.BOOK, 1), new ItemStack(Items.CANDLE, 3)));
        java.util.Collections.shuffle(all, new java.util.Random(r.nextLong()));
        return new ArrayList<>(all.subList(0, 3));
    }

    /**
     * A trader on its way in: from a neighbouring town, from an edge out of everybody's sight. Null if there is nowhere
     * out of sight to come in from just now (it is tried again in a minute).
     */
    @Nullable
    static VillageFolkEntity comeIn(ServerLevel level, Villages.Village v, long day, int index) {
        BlockPos edge = unseenEdge(level, v, level.getRandom());
        if (edge == null) return null;
        List<Villages.Village> near = neighbours(v);
        String from = index < near.size() ? Villages.name(near.get(index).id()) : "over the hill";
        List<ItemStack> kit = goods(level.getRandom());
        VillageFolkEntity f = Visitors.arriveFrom(level, v, edge, Visitors.Kind.TRADER, day, 0, 0, kit, "a trader from " + from);
        if (f == null) return null;
        Visitors.Visit vis = Visitors.visit(f);
        if (vis != null) {
            vis.wares = "from " + from;
            Visitors.save(f, vis);
        }
        return f;
    }

    /** An edge to come in from: loaded, dry, solid ground out past the town, where no player could see a body appear. */
    @Nullable
    static BlockPos unseenEdge(ServerLevel level, Villages.Village v, RandomSource r) {
        BlockPos c = v.centre();
        int near = Math.max(24, Villages.townReach(v.id()) + 8);
        int start = r.nextInt(8);
        for (int out = 176; out >= near; out -= 8) {
            for (int k = 0; k < 8; k++) {
                double a = Math.toRadians((start + k) * 45.0);
                int x = c.getX() + (int) Math.round(Math.sin(a) * out), z = c.getZ() + (int) Math.round(Math.cos(a) * out);
                if (!level.hasChunk(x >> 4, z >> 4) || Blink.seenAt(level, x, z, 8.0)) continue;
                BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (!level.getFluidState(top.below()).isEmpty() || !level.getFluidState(top).isEmpty()) continue;
                if (!level.getBlockState(top.below()).isFaceSturdy(level, top.below(), Direction.UP)) continue;
                return top;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ a trader's day (Visitors)

    /** At the square: its stall up. */
    static void arrived(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, long day) {
        List<ItemStack> lots = new ArrayList<>();
        for (ItemStack x : f.getInventoryItems()) if (!x.isEmpty() && lots.size() < 3) lots.add(x.copy());
        for (ItemStack s : lots) f.removeMatching(x -> ItemStack.isSameItem(x, s), s.getCount());
        Stalls.Stall s = Stalls.raise(level, town, f.getUUID(), v.name, lots, day, true);
        if (s == null) {
            for (ItemStack w : lots) f.insertItem(w);
        }
        Villages.tell(town.id(), day, v.name + ", a trader " + v.wares + ", set up a stall at the fair");
        FolkTalk.speak(f, hello(f, v.wares));
    }

    /** Its stay: behind its stall, calling its wares, till it packs up. True when it is time to go. */
    static boolean stay(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit v, long day, long t) {
        if (t >= PACK_UP || day > v.arrived) {
            packUp(level, town, f);
            return true;
        }
        BlockPos stall = Stalls.at(town.id(), f.getUUID());
        if (stall == null) {
            Visitors.walk(f, level, town.centre(), 4.0, 0.6D, v.walk);
            return false;
        }
        Stalls.Stall s = null;
        for (Stalls.Stall x : Stalls.of(town.id())) if (x.seller.equals(f.getUUID())) s = x;
        if (s == null) return false;
        if (!Visitors.walk(f, level, s.behind(), 1.2, 0.6D, v.walk)) return false;
        f.getLookControl().setLookAt(s.before().getX() + 0.5, s.before().getY() + 1.5, s.before().getZ() + 0.5);
        for (ItemStack w : s.wares) if (!w.isEmpty()) { f.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, w.copyWithCount(1)); break; }
        if (s.empty()) {
            packUp(level, town, f);
            return true;
        }
        long now = level.getGameTime();
        if (now - v.lastSaid > 500L) {
            v.lastSaid = now;
            for (int i = 0; i < s.wares.size(); i++) {
                if (s.wares.get(i).isEmpty()) continue;
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), FolkTalk.cap(Stalls.words(s.wares.get(i))) + ", " + s.prices.get(i) + "c. Brought it miles.",
                    "Fair prices, fair day.", "Same as last month. Better, if anything.", FolkTalk.cap(Stalls.words(s.wares.get(i))) + ". "
                        + s.prices.get(i) + "c, and I'm not haggling.", "All the way " + v.wares + ".", "Last one at this price."));
                break;
            }
        }
        return false;
    }

    /** Its stall down, what it has left back on its back. */
    static void packUp(ServerLevel level, Villages.Village town, VillageFolkEntity f) {
        List<Stalls.Stall> l = Stalls.of(town.id());
        for (Stalls.Stall s : new ArrayList<>(l)) {
            if (!s.seller.equals(f.getUUID())) continue;
            Stalls.takeDown(level, s.at, s.front);
            for (ItemStack w : s.wares) if (!w.isEmpty()) f.insertItem(w.copy());
            l.remove(s);
            Stalls.remember(town.id());
        }
        f.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    static String hello(VillageFolkEntity f, String from) {
        return FolkTalk.pick(f.getRandom(), "In for the fair, " + from + ". Have a look at the stall.", "Morning. Walked in " + from + " at first light.",
            "Fair day. Best day of the month for trade.", "You've a good square for it here.", "Mind the awning; it's new.",
            "Brought what we had spare " + from + ".");
    }

    static String goodbye(VillageFolkEntity f) {
        return FolkTalk.pick(f.getRandom(), "That's me done. Next month, then.", "Long walk home. Good day for it.", "Not a bad fair.",
            "I'll be back for the next one.", "Sold most of it. Can't complain.", "Off before the light goes.");
    }

    /** A fair-goer at a trader's stall: Stalls.buy covers the traders' stalls too. */
    @Nullable
    static String buy(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        return Stalls.buy(level, v, f, day);
    }

    // ------------------------------------------------------------------ music, games, lanterns

    private static final SoundEvent[] VOICES = { SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundEvents.NOTE_BLOCK_GUITAR.value(),
        SoundEvents.NOTE_BLOCK_BASS.value(), SoundEvents.NOTE_BLOCK_BELL.value() };
    /** A jig, in semitones above the low F sharp of the note block. */
    private static final int[] JIG = { 6, 10, 13, 10, 6, 10, 13, 18, 17, 13, 15, 11, 8, 11, 15, 13 };

    static void music(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        int i = TUNE.merge(id, 1, Integer::sum);
        if (i % 2 != 0) return;                                        // a note every other beat of the round
        int n = JIG[(i / 2) % JIG.length];
        float pitch = (float) Math.pow(2.0, (n - 12) / 12.0);
        float vol = Math.min(3.0F, 1.0F + Villages.headcount(id) / 12.0F);
        BlockPos c = v.centre();
        level.playSound(null, c, VOICES[0], SoundSource.RECORDS, vol, pitch);
        if ((i / 2) % 2 == 0) level.playSound(null, c, VOICES[2], SoundSource.RECORDS, vol * 0.8F, pitch * 0.5F);
        level.sendParticles(ParticleTypes.NOTE, c.getX() + 0.5, c.getY() + 2.2, c.getZ() + 0.5, 1, 0.6, 0.2, 0.6, n / 24.0);
    }

    /** A race across the square, for a fair-goer now and then (WeekendLife: FAIR): off at a run, and a cheer. */
    static void race(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        BlockPos c = v.centre();
        int side = f.getRandom().nextBoolean() ? 1 : -1;
        BlockPos to = f.surfaceAt(c.getX() + side * 7, c.getZ() + f.getRandom().nextInt(7) - 3);
        if (to == null) return;
        f.walkTo(to, 1.3D);
        f.getJumpControl().jump();
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Race you to the well!", "Last one there's buying.", "I'm not as quick as I was.",
            "Go on, then.", "Ready, steady...", "That's cheating, that is."));
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity o && o != f && o.distanceToSqr(f) < 100 && WeekendLife.now(o) == WeekendLife.Outing.FAIR && f.getRandom().nextInt(3) == 0) {
                o.sayLater(FolkTalk.pick(o.getRandom(), "Go on!", "Faster!", "Lift your knees!", "Ha!", "Nearly!", "Again!"), 10 + o.getRandom().nextInt(20));
            }
        }
    }

    /** Lanterns on posts round the square, by what the treasury can afford; written down so they come down after a restart. */
    static void dress(ServerLevel level, Villages.Village v) {
        int n = lamps(v.id());
        if (n <= 0) return;
        List<BlockPos> put = new ArrayList<>();
        BlockPos c = v.centre();
        for (int k = 0; k < n * 2 && put.size() < n; k++) {
            double a = Math.toRadians(k * (360.0 / (n * 2)) + 11);
            int x = c.getX() + (int) Math.round(Math.sin(a) * 11), z = c.getZ() + (int) Math.round(Math.cos(a) * 11);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (Math.abs(at.getY() - c.getY()) > 2) continue;
            if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.above()).canBeReplaced() || !level.getBlockState(at.above(2)).canBeReplaced()) continue;
            if (!level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) continue;
            level.setBlock(at, Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
            level.setBlock(at.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
            level.setBlock(at.above(2), Blocks.LANTERN.defaultBlockState(), 2);
            put.add(at);
        }
        LAMPS.put(v.id(), put);
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : put) sb.append(sb.length() == 0 ? "" : ";").append(p.asLong());
        Ledger.note(v.id(), "life.lamps", sb.toString());
    }

    static void takeDown(ServerLevel level, UUID village) {
        List<BlockPos> all = new ArrayList<>(LAMPS.getOrDefault(village, List.of()));
        String kept = Ledger.note(village, "life.lamps");
        if (kept != null && !kept.isEmpty()) {
            for (String s : kept.split(";")) {
                try { all.add(BlockPos.of(Long.parseLong(s))); } catch (RuntimeException ignored) { }
            }
        }
        for (BlockPos p : all) {
            if (!level.isLoaded(p)) continue;
            for (int h = 0; h <= 2; h++) {
                BlockState st = level.getBlockState(p.above(h));
                if (st.is(Blocks.SPRUCE_FENCE) || st.is(Blocks.LANTERN)) level.setBlock(p.above(h), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        LAMPS.remove(village);
        Ledger.note(village, "life.lamps", "");
    }

    /** Lanterns up today (tests, the week page). */
    public static int lampsUp(UUID village) {
        return LAMPS.getOrDefault(village, List.of()).size();
    }

    // ------------------------------------------------------------------ tests

    @Nullable
    public static VillageFolkEntity traderForTests(ServerLevel level, Villages.Village v) {
        return comeIn(level, v, level.getDayTime() / 24000L, 0);
    }

    public static void roundForTests(ServerLevel level, Villages.Village v) {
        round(level, v);
    }

    /** Who sells from a player's talk (a word with a trader: what it has). */
    static String offerTo(VillageFolkEntity f, Player p) {
        return "It's all on the stall.";
    }
}
