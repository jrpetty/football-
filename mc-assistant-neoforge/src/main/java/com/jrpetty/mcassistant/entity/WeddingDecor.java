package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [featfolk] A wedding dressed for what the town has: its chapel (or the square), its stores and its purse.
 *
 * <p>The afternoon of a wedding day (from {@link #FROM}) a guest goes out to the place it is held (the chapel's door,
 * else the court or the square: Assemblies.weddingSite) and dresses it, one thing at a time, walking from spot to spot
 * with each in hand. How it is dressed is the town's wealth ({@link Tier}):
 * <ul>
 * <li><b>Plain</b> (a poor town): wildflowers along the aisle and a few candles.</li>
 * <li><b>Fine</b>: a carpet down the aisle, flowers in pots, lanterns, banners at the door, and the bell rung.</li>
 * <li><b>Grand</b> (a rich town): all that, a floral arch with coloured glass over the couple, a feast table with cakes for
 *     afterwards, music, and the fireworks maker's rockets (FireworkShows, as ever) if the town has them.</li>
 * </ul>
 * Everything but a wildflower comes out of the stores (what the stores have not got is left out), and the couple pay the
 * treasury for it out of their purses; a couple who cannot pay for the tier have the one below. After the wedding the
 * decorator takes it all down again and back into the stores: candles burnt down, cakes eaten and flowers given away
 * excepted ({@link #tidy}).
 *
 * <p>The ceremony itself is Assemblies': the officiant at the front, the guests in the pews (Seats), the couple facing each
 * other, then the vows, the rings, a kiss, petals and the bell ({@link #rings}, {@link #kiss}), a reception (the feast
 * table and music on a grand day), and the couple walked out down the aisle ({@link #procession}).
 */
public final class WeddingDecor {

    private WeddingDecor() {}

    /** The day's time the dressing starts. */
    static final long FROM = 9000L;

    public enum Tier {
        PLAIN("plain", 3), FINE("fine", 12), GRAND("grand", 40);

        public final String word;
        /** What the couple pay the treasury for the goods. */
        public final int cost;

        Tier(String word, int cost) {
            this.word = word;
            this.cost = cost;
        }
    }

    /** One thing to put down: where, what, out of the stores by what (null: picked, free), and whether it comes back. */
    record Piece(BlockPos at, BlockState state, @Nullable Predicate<ItemStack> from, boolean returns, String what) {}

    /** A wedding's dressing: its tier, its pieces, how far on, and what was put down (to take up again). */
    static final class Plan {
        final UUID village;
        final long day;
        final Tier tier;
        final BlockPos focus;
        final Direction aisle;
        final List<Piece> todo = new ArrayList<>();
        final List<Piece> placed = new ArrayList<>();
        @Nullable UUID decorator;
        boolean down;
        int music;

        Plan(UUID village, long day, Tier tier, BlockPos focus, Direction aisle) {
            this.village = village;
            this.day = day;
            this.tier = tier;
            this.focus = focus;
            this.aisle = aisle;
        }
    }

    private static final Map<UUID, Plan> PLANS = new ConcurrentHashMap<>();

    public static void resetForTests() { PLANS.clear(); }

    // ------------------------------------------------------------------ the tier

    /** What the town and the couple can run to. */
    public static Tier tier(ServerLevel level, UUID village, @Nullable VillageFolkEntity a, @Nullable VillageFolkEntity b) {
        int coins = com.jrpetty.mcassistant.village.Ledger.coins(village);
        int purses = (a == null ? 0 : a.purse()) + (b == null ? 0 : b.purse());
        Tier t = coins >= 800 ? Tier.GRAND : coins >= 200 ? Tier.FINE : Tier.PLAIN;
        while (t != Tier.PLAIN && purses < t.cost) t = Tier.values()[t.ordinal() - 1];
        return t;
    }

    // ------------------------------------------------------------------ planning

    /** The dressing for tonight's wedding, worked out once (the afternoon, or as the wedding is called). */
    @Nullable
    static Plan plan(ServerLevel level, Villages.Village v, Gatherings.Wedding w) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Plan p = PLANS.get(id);
        if (p != null && p.day == day) return p;
        Object[] site = Assemblies.weddingSite(level, v, w);
        BlockPos focus = (BlockPos) site[0];
        Direction aisle = (Direction) site[1];
        VillageFolkEntity a = level.getEntity(w.a()) instanceof VillageFolkEntity x ? x : null;
        VillageFolkEntity b = level.getEntity(w.b()) instanceof VillageFolkEntity x ? x : null;
        Tier tier = tier(level, id, a, b);
        // The couple pay for it: half each, what one cannot the other makes up.
        int owed = tier.cost;
        for (VillageFolkEntity f : new VillageFolkEntity[]{ a, b }) {
            if (f == null || owed <= 0) continue;
            int share = Math.min(f.purse(), f == a ? (owed + 1) / 2 : owed);
            if (share > 0 && f.spend(share)) {
                com.jrpetty.mcassistant.village.Ledger.addCoins(id, share);
                owed -= share;
            }
        }
        if (owed > 0 && b != null && b.purse() >= owed && b.spend(owed)) { com.jrpetty.mcassistant.village.Ledger.addCoins(id, owed); owed = 0; }
        p = new Plan(id, day, tier, focus, aisle);
        lay(level, v, p);
        PLANS.put(id, p);
        Villages.tell(id, day, "the " + tier.word + " wedding of " + w.names() + " is being made ready" + (tier == Tier.PLAIN ? ", with wildflowers and candles"
            : tier == Tier.FINE ? ", with a carpet, flowers, lanterns and banners" : ": an arch of flowers, a feast table and music"));
        return p;
    }

    /** Where each thing goes, by the tier, in the order it is put down. */
    static void lay(ServerLevel level, Villages.Village v, Plan p) {
        Direction fwd = p.aisle, right = fwd.getClockWise();
        RandomSource r = level.getRandom();
        int y = p.focus.getY();
        // Along the aisle, both sides, every other step.
        for (int k = 2; k <= 10; k += 2) {
            for (int side : new int[]{ -2, 2 }) {
                BlockPos col = p.focus.relative(fwd, k).relative(right, side);
                BlockPos at = ground(level, col, y);
                if (at == null) continue;
                switch (p.tier) {
                    case PLAIN -> {
                        if (k % 4 == 2) add(p, at, wildflower(r), null, false, "wildflowers");
                        else add(p, at, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3).setValue(CandleBlock.LIT, true),
                            s -> s.is(Items.CANDLE), false, "candles");
                    }
                    case FINE, GRAND -> {
                        if (k % 4 == 2) add(p, at, potted(r), s -> s.is(Items.FLOWER_POT), true, "flowers in pots");
                        else add(p, at, Blocks.LANTERN.defaultBlockState(), s -> s.is(Items.LANTERN), true, "lanterns");
                    }
                }
            }
        }
        if (p.tier != Tier.PLAIN) {
            // The carpet down the aisle, and banners at its far end.
            for (int k = 1; k <= 10; k++) {
                BlockPos at = ground(level, p.focus.relative(fwd, k), y);
                if (at != null) add(p, at, Blocks.RED_CARPET.defaultBlockState(), s -> s.is(ItemTags.WOOL_CARPETS), true, "carpet");
            }
            for (int side : new int[]{ -3, 3 }) {
                BlockPos at = ground(level, p.focus.relative(fwd, 11).relative(right, side), y);
                if (at != null) add(p, at, Blocks.WHITE_BANNER.defaultBlockState().setValue(BannerBlock.ROTATION, rotationFacing(fwd)),
                    s -> s.is(ItemTags.BANNERS), true, "banners");
            }
        }
        if (p.tier == Tier.GRAND) {
            // The arch over the couple: flowering leaves up either side, coloured glass across the top.
            BlockPos base = p.focus.relative(fwd, 1);
            BlockPos l = ground(level, base.relative(right, -2), y), rr = ground(level, base.relative(right, 2), y);
            if (l != null && rr != null && l.getY() == rr.getY()) {
                for (int h = 0; h < 3; h++) {
                    add(p, l.above(h), Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true),
                        s -> s.is(ItemTags.LEAVES), true, "an arch of flowers");
                    add(p, rr.above(h), Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true),
                        s -> s.is(ItemTags.LEAVES), true, "an arch of flowers");
                }
                for (int side = -2; side <= 2; side++) {
                    BlockPos top = base.relative(right, side).atY(l.getY() + 3);
                    add(p, top, (side % 2 == 0 ? Blocks.PINK_STAINED_GLASS : Blocks.WHITE_STAINED_GLASS).defaultBlockState(),
                        s -> s.is(Items.GLASS) || s.getItem().toString().contains("stained_glass"), true, "coloured glass");
                }
            }
            // The feast table to one side, with cakes on it, and a note block for the music.
            for (int i = 0; i < 3; i++) {
                BlockPos at = ground(level, p.focus.relative(fwd, 4 + i).relative(right, 6), y);
                if (at == null) continue;
                add(p, at, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP), s -> s.is(ItemTags.WOODEN_SLABS), true, "a feast table");
                add(p, at.above(), Blocks.CAKE.defaultBlockState(), s -> s.is(Items.CAKE), false, "cakes");
            }
            BlockPos nb = ground(level, p.focus.relative(fwd, 3).relative(right, -6), y);
            if (nb != null) add(p, nb, Blocks.NOTE_BLOCK.defaultBlockState(), s -> s.is(Items.NOTE_BLOCK), true, "music");
        } else if (p.tier == Tier.FINE) {
            // A pair of candles at the front, either side of the couple.
            for (int side : new int[]{ -2, 2 }) {
                BlockPos at = ground(level, p.focus.relative(right, side), y);
                if (at != null) add(p, at, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 4).setValue(CandleBlock.LIT, true),
                    s -> s.is(Items.CANDLE), false, "candles");
            }
        }
    }

    private static void add(Plan p, BlockPos at, BlockState s, @Nullable Predicate<ItemStack> from, boolean returns, String what) {
        for (Piece x : p.todo) if (x.at().equals(at)) return;
        p.todo.add(new Piece(at.immutable(), s, from, returns, what));
    }

    private static int rotationFacing(Direction d) {
        return switch (d) {
            case SOUTH -> 0;
            case WEST -> 4;
            case NORTH -> 8;
            default -> 12;
        };
    }

    private static BlockState wildflower(RandomSource r) {
        Block[] f = { Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.AZURE_BLUET };
        return f[r.nextInt(f.length)].defaultBlockState();
    }

    private static BlockState potted(RandomSource r) {
        Block[] f = { Blocks.POTTED_POPPY, Blocks.POTTED_DANDELION, Blocks.POTTED_CORNFLOWER, Blocks.POTTED_ALLIUM, Blocks.POTTED_AZURE_BLUET };
        return f[r.nextInt(f.length)].defaultBlockState();
    }

    /** The free space on the ground at this column, near this height: air with something sturdy under it. Null if none. */
    @Nullable
    static BlockPos ground(ServerLevel level, BlockPos col, int y) {
        if (!level.isLoaded(col)) return null;
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos at = col.atY(y + dy);
            if (level.getBlockState(at).isAir() && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)
                    && level.getFluidState(at.below()).isEmpty()) return at;
        }
        return null;
    }

    // ------------------------------------------------------------------ dressing it, visibly

    /**
     * Each village's look at its wedding (Assemblies.tick): the afternoon of the day, a guest walks out and dresses the
     * place, one thing at a time; the evening after, it takes it down again.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long dayTime = level.getDayTime(), t = dayTime % 24000L, day = dayTime / 24000L;
        Plan p = PLANS.get(id);
        if (p != null && (p.down || p.day < day)) {
            if (p.day < day - 2 || t >= 1000L && t < 12000L) tidy(level, v, p);   // the morning after, by daylight
            return;
        }
        if (Gatherings.tonight(id, day) != Gatherings.Kind.WEDDING || t < FROM || t >= 13800L) return;
        Gatherings.Wedding w = Gatherings.wedding(id);
        if (w == null) return;
        if (p == null || p.day != day) p = plan(level, v, w);
        if (p != null && level.getGameTime() % 10 == 0) dress(level, v, p, t >= 12100L);
    }

    /** One step of the dressing: the decorator to the next spot, and the thing put down when it is there. */
    static void dress(ServerLevel level, Villages.Village v, Plan p, boolean hurry) {
        if (p.todo.isEmpty()) return;
        VillageFolkEntity d = decorator(level, v, p);
        Piece next = p.todo.get(0);
        boolean there = d == null || d.blockPosition().distSqr(next.at()) <= 3.5 * 3.5;
        if (!there && !hurry) {
            if (d.getNavigation().isDone() || d.tickCount % 40 == 0) d.walkTo(next.at(), 0.9D);
            d.hobbyNow = "dressing the place for the wedding";
            return;
        }
        // Not if somebody is stood there, or it is no longer clear.
        if (!level.getBlockState(next.at()).isAir() || !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(next.at())).isEmpty()) {
            p.todo.remove(0);
            return;
        }
        p.todo.remove(0);
        if (next.from() != null) {
            ItemStack got = Crafts.takeOne(level, v, next.from());
            if (got.isEmpty()) return;                                    // the stores have none: it goes without
        }
        level.setBlock(next.at(), next.state(), 3);
        p.placed.add(next);
        if (d != null) {
            d.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            d.getLookControl().setLookAt(next.at().getX() + 0.5, next.at().getY() + 0.5, next.at().getZ() + 0.5);
            if (level.random.nextInt(8) == 0 && level.getNearestPlayer(d, 20.0) != null) {
                FolkTalk.speak(d, FolkTalk.pick(level.random, "A little to the left. No. There.", "If it rains, I'm not doing this twice.",
                    "Somebody hold this. Nobody? Fine.", "That'll do. It'll more than do.", "They'd better turn up after all this.",
                    "Straight? It's straight enough."));
            }
        }
    }

    /** Who dresses it: a guest off work, near the place (the same one throughout, while it can). */
    @Nullable
    static VillageFolkEntity decorator(ServerLevel level, Villages.Village v, Plan p) {
        if (p.decorator != null && level.getEntity(p.decorator) instanceof VillageFolkEntity d && d.isAlive() && !d.isSleeping()) return d;
        VillageFolkEntity best = null;
        double near = Double.MAX_VALUE;
        Gatherings.Wedding w = Gatherings.wedding(v.id());
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isSleeping() || f.isShowcase() || f.isHired()) continue;
            if (w != null && (f.getUUID().equals(w.a()) || f.getUUID().equals(w.b()))) continue;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD) continue;
            double dd = f.distanceToSqr(p.focus.getX(), p.focus.getY(), p.focus.getZ());
            if (dd < near) { near = dd; best = f; }
        }
        p.decorator = best == null ? null : best.getUUID();
        return best;
    }

    /** Is this folk dressing a wedding just now (its day waits)? */
    public static boolean dressing(VillageFolkEntity f) {
        UUID id = f.ownerId();
        Plan p = id == null ? null : PLANS.get(id);
        return p != null && f.getUUID().equals(p.decorator) && (p.down ? !p.placed.isEmpty() : !p.todo.isEmpty())
            && !f.level().isNight() && (p.down || f.level().getDayTime() % 24000L >= FROM);
    }

    /**
     * The morning after: the decorator walks round and takes it all up again, last put down first, back into the stores
     * (what was used up excepted).
     */
    static void tidy(ServerLevel level, Villages.Village v, Plan p) {
        if (!p.down) {
            p.down = true;
            p.decorator = null;
        }
        if (level.getGameTime() % 10 != 0) return;
        if (p.placed.isEmpty()) {
            PLANS.remove(v.id(), p);
            return;
        }
        Piece last = p.placed.get(p.placed.size() - 1);
        VillageFolkEntity d = p.day < level.getDayTime() / 24000L - 2 ? null : decorator(level, v, p);
        if (d != null && d.blockPosition().distSqr(last.at()) > 3.5 * 3.5) {
            if (d.getNavigation().isDone() || d.tickCount % 40 == 0) d.walkTo(last.at(), 0.9D);
            d.hobbyNow = "taking down the wedding things";
            return;
        }
        p.placed.remove(p.placed.size() - 1);
        BlockState now = level.getBlockState(last.at());
        if (now.getBlock() != last.state().getBlock()) return;           // already gone (eaten, knocked down)
        level.setBlock(last.at(), Blocks.AIR.defaultBlockState(), 3);
        if (d != null) d.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (last.returns()) {
            ItemStack back = last.state().getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock
                ? new ItemStack(Items.FLOWER_POT) : new ItemStack(last.state().getBlock().asItem());
            if (!back.isEmpty()) Crafts.store(level, v, back);
        }
    }

    // ------------------------------------------------------------------ the ceremony's moments

    /** The rings, exchanged: a glint over the couple, and a line for it. */
    static void rings(ServerLevel level, Assemblies.Assembly a) {
        for (UUID u : a.principals) {
            if (level.getEntity(u) instanceof net.minecraft.world.entity.LivingEntity e) {
                level.sendParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + 1.2, e.getZ(), 6, 0.2, 0.2, 0.2, 0.01);
                if (e instanceof VillageFolkEntity f) FolkAction.play(f, FolkAction.Act.HAND_OVER, 40);
            }
        }
        level.playSound(null, a.focus, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.0F, 1.4F);
    }

    /** The kiss: the two face each other, hearts, petals over the aisle, and the bell. */
    static void kiss(ServerLevel level, Assemblies.Assembly a) {
        List<net.minecraft.world.entity.LivingEntity> two = new ArrayList<>();
        for (UUID u : a.principals) if (level.getEntity(u) instanceof net.minecraft.world.entity.LivingEntity e) two.add(e);
        for (net.minecraft.world.entity.LivingEntity e : two) {
            if (e instanceof VillageFolkEntity f) {
                FolkAction.mark(f, FolkAction.Mark.HEART);
                for (net.minecraft.world.entity.LivingEntity o : two) if (o != e) f.getLookControl().setLookAt(o, 30.0F, 30.0F);
            }
            level.sendParticles(ParticleTypes.HEART, e.getX(), e.getY() + 2.1, e.getZ(), 5, 0.4, 0.3, 0.4, 0.0);
        }
        confetti(level, a.focus, a.audience);
        level.playSound(null, a.focus, SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 2.5F, 1.0F);
        level.playSound(null, a.focus, SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 2.5F, 1.2F);
    }

    /** Petals down the aisle. */
    static void confetti(ServerLevel level, BlockPos focus, Direction aisle) {
        for (int k = 0; k <= 8; k += 2) {
            BlockPos p = focus.relative(aisle, k);
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, p.getX() + 0.5, p.getY() + 2.5, p.getZ() + 0.5, 12, 1.2, 0.4, 1.2, 0.0);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX() + 0.5, p.getY() + 1.5, p.getZ() + 0.5, 4, 1.0, 0.3, 1.0, 0.0);
        }
    }

    /** Each step of the wedding (Assemblies.step): music at a grand reception, petals now and then, the couple walked out. */
    static void step(ServerLevel level, Assemblies.Assembly a, long now) {
        Plan p = PLANS.get(a.village);
        if (p == null) return;
        if (a.phase == Assemblies.Phase.SPEECH && !p.todo.isEmpty()) {
            // Whatever is not down by now goes down at once: the couple are at the front.
            Villages.Village v = Villages.get(a.village);
            if (v != null) for (int i = 0; i < 4 && !p.todo.isEmpty(); i++) dress(level, v, p, true);
        }
        if (a.phase == Assemblies.Phase.MINGLE && p.tier == Tier.GRAND && now % 8 == 0) {
            // A tune on the note block: up and down the scale.
            int[] tune = { 0, 4, 7, 12, 7, 4, 2, 5, 9, 12, 9, 5 };
            float pitch = (float) Math.pow(2.0, (tune[p.music++ % tune.length] - 12) / 12.0);
            BlockPos nb = null;
            for (Piece x : p.placed) if (x.state().is(Blocks.NOTE_BLOCK)) nb = x.at();
            level.playSound(null, nb != null ? nb : a.focus, SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, 1.2F, pitch);
            if (nb != null && now % 16 == 0) level.sendParticles(ParticleTypes.NOTE, nb.getX() + 0.5, nb.getY() + 1.2, nb.getZ() + 0.5, 1, 0, 0, 0, 1.0);
        }
        if ((a.phase == Assemblies.Phase.CLOSE || a.phase == Assemblies.Phase.DISPERSE) && now - a.phaseAt < 200) procession(level, a, p);
    }

    /** The couple walk out down the aisle together, petals over them. */
    static void procession(ServerLevel level, Assemblies.Assembly a, Plan p) {
        BlockPos out = a.focus.relative(a.audience, 12);
        for (UUID u : a.principals) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
            if (f.blockPosition().distSqr(out) > 4.0 && (f.getNavigation().isDone() || f.tickCount % 30 == 0)) f.walkTo(out, 0.6D);
            if (f.tickCount % 20 == 0) level.sendParticles(ParticleTypes.CHERRY_LEAVES, f.getX(), f.getY() + 2.4, f.getZ(), 6, 0.6, 0.2, 0.6, 0.0);
        }
    }

    // ------------------------------------------------------------------ for the board and the tests

    /** "a grand wedding: 14 of 20 things put down" or "". */
    public static String line(UUID village) {
        Plan p = PLANS.get(village);
        if (p == null) return "";
        return "a " + p.tier.word + " wedding: " + p.placed.size() + " things put down" + (p.todo.isEmpty() ? "" : ", " + p.todo.size() + " to go");
    }

    @Nullable
    public static Tier tierForTests(UUID village) {
        Plan p = PLANS.get(village);
        return p == null ? null : p.tier;
    }

    /** Tests: what is put down, by what it is ("carpet", "lanterns"...). */
    public static List<String> placedForTests(UUID village) {
        Plan p = PLANS.get(village);
        List<String> out = new ArrayList<>();
        if (p != null) for (Piece x : p.placed) out.add(x.what());
        return out;
    }

    public static List<BlockPos> placedAtForTests(UUID village) {
        Plan p = PLANS.get(village);
        List<BlockPos> out = new ArrayList<>();
        if (p != null) for (Piece x : p.placed) out.add(x.at());
        return out;
    }

    /** Tests: plan it now and put it all down at once (the decorator's walk skipped). */
    public static int dressNowForTests(ServerLevel level, Villages.Village v) {
        Gatherings.Wedding w = Gatherings.wedding(v.id());
        if (w == null) return -1;
        PLANS.remove(v.id());
        Plan p = plan(level, v, w);
        if (p == null) return -1;
        int guard = 400;
        while (!p.todo.isEmpty() && guard-- > 0) dress(level, v, p, true);
        return p.placed.size();
    }

    /** Tests: take it all down now. */
    public static int tidyNowForTests(ServerLevel level, Villages.Village v) {
        Plan p = PLANS.get(v.id());
        if (p == null) return 0;
        int n = p.placed.size();
        p.down = true;
        while (!p.placed.isEmpty()) {
            Piece last = p.placed.remove(p.placed.size() - 1);
            if (level.getBlockState(last.at()).getBlock() == last.state().getBlock()) level.setBlock(last.at(), Blocks.AIR.defaultBlockState(), 3);
            if (last.returns()) Crafts.store(level, v, new ItemStack(last.state().getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock
                ? Items.FLOWER_POT : last.state().getBlock().asItem()));
        }
        PLANS.remove(v.id());
        return n;
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
