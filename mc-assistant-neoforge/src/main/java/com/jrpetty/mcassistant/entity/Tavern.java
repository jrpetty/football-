package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tavern, of an evening.
 * <ul>
 * <li>Off-work folk drop in two evenings in five, stand about the tables and the fire, and
 *     talk — and somebody always tells a story: something that really happened, out of the
 *     village's history ("Remember the night the raiders came at the north gate?").</li>
 * <li>Once there are a few in, the music starts: a tune on the note blocks in the corner,
 *     the bass on the blocks themselves and the melody over it.</li>
 * <li>A player can <b>buy a round</b>: right-click the board on the bar ("Buy a round, a
 *     coin a head"). Everybody in the tavern raises a glass to you and thinks the better of
 *     you, and the village remembers it.</li>
 * </ul>
 */
public final class Tavern {

    private Tavern() {}

    private static final Map<BlockPos, Integer> BEAT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STORY = new ConcurrentHashMap<>();
    private static final Set<String> TOASTED = ConcurrentHashMap.newKeySet();

    public static void resetForTests() {
        BEAT.clear();
        STORY.clear();
        TOASTED.clear();
    }

    /** The village's tavern, or null. */
    @Nullable
    public static Ledger.Building of(@Nullable UUID village) {
        if (village == null) return null;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals("tavern")) return b;
        return null;
    }

    /** Is this folk in for the evening (two evenings in five)? */
    static boolean goingTonight(VillageFolkEntity f, long day) {
        return Math.floorMod(day * 7 + f.getUUID().hashCode(), 5) < 2;
    }

    /** An off-work folk's evening at the tavern. True while it is going or there. */
    public static boolean evening(VillageFolkEntity f, long t) {
        UUID village = f.ownerId();
        Ledger.Building tav = of(village);
        if (tav == null || f.isBaby() || !(f.level() instanceof ServerLevel level) || !level.isLoaded(tav.anchor())) return false;
        long day = level.getDayTime() / 24000L;
        if (!goingTonight(f, day)) return false;
        Direction back = tav.facing(), right = back.getClockWise();
        int h = f.getUUID().hashCode();
        BlockPos spot = tav.anchor().relative(right, Math.floorMod(h, 4)).relative(back, Math.floorMod(h >> 3, 5) - 2);
        if (!RestDay.arrive(f, spot)) return true;
        f.hobbyNow = "at the tavern";
        f.lastLeisureTick = f.tickCount;
        BlockPos hearth = tav.anchor().relative(back, 4);
        f.getLookControl().setLookAt(hearth.getX() + 0.5, hearth.getY() + 1.0, hearth.getZ() + 0.5);
        tell(f, level, village);
        return true;
    }

    /** Somebody tells a story, every so often: a thing out of the village's history. */
    static void tell(VillageFolkEntity f, ServerLevel level, UUID village) {
        long now = level.getGameTime();
        if (now - STORY.getOrDefault(village, -100000L) < 260L) return;
        RandomSource r = f.getRandom();
        if (r.nextInt(3) != 0) return;
        STORY.put(village, now);
        List<Chronicle.Entry> past = Chronicle.of(village);
        if (past.isEmpty()) return;
        Chronicle.Entry e = past.get(r.nextInt(past.size()));
        long ago = level.getDayTime() / 24000L - e.day();
        String when = ago <= 1 ? "just yesterday" : ago <= 7 ? ago + " days back" : "back on day " + e.day();
        FolkTalk.speak(f, FolkTalk.pick(r, "Remember when " + e.text() + "? That was " + when + ".",
            "I'll never forget it: " + e.text() + ", " + when + ".", "Did I ever tell you how " + e.text() + "?"));
        // And somebody answers.
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity o && o != f && o.distanceToSqr(f) < 36.0 && !o.isSleeping()) {
                o.sayLater(FolkTalk.pick(r, "Aye, I remember!", "Those were the days.", "You tell it better every time.",
                    "Ha! I was there!", "Not this one again..."), 50);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ the music

    /** A jig in G, a beat to a note (-1 a rest): note-block pitches, 0 the lowest F sharp. */
    static final int[] JIG = { 13, 15, 17, 18, 17, 15, 13, -1, 13, 15, 13, 10, 8, 10, 13, -1,
        18, 20, 22, 20, 18, 17, 15, -1, 13, 15, 17, 15, 13, 10, 13, -1 };
    /** And a slow air for late in the evening. */
    static final int[] AIR = { 6, -1, 10, 13, -1, 11, 10, -1, 8, -1, 6, 8, 10, -1, -1, -1,
        13, -1, 15, 13, -1, 11, 10, -1, 8, 10, 11, 10, 6, -1, -1, -1 };

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 5 != 2) return;
        Guard.run("tavern", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (v.dim().equals(level.dimension())) play(level, v);
                }
            }
        });
    }

    /** A beat of music, if there is company enough in the tavern of an evening. */
    static void play(ServerLevel level, Villages.Village v) {
        long t = level.getDayTime() % 24000L;
        if (t < 12500L || t > 17000L) return;
        Ledger.Building tav = of(v.id());
        if (tav == null || !level.isLoaded(tav.anchor())) return;
        if (company(level, tav) < 2) return;
        int beat = BEAT.merge(tav.anchor(), 1, Integer::sum);
        int[] tune = t < 15000L ? JIG : AIR;
        int note = tune[Math.floorMod(beat, tune.length)];
        if (note < 0) return;
        List<BlockPos> blocks = noteBlocks(tav);
        float pitch = (float) Math.pow(2.0, (note - 12) / 12.0);
        BlockPos at = blocks.isEmpty() ? tav.anchor() : blocks.get(0);
        level.playSound(null, at, t < 15000L ? SoundEvents.NOTE_BLOCK_FLUTE.value() : SoundEvents.NOTE_BLOCK_HARP.value(),
            SoundSource.RECORDS, 1.2F, pitch);
        level.sendParticles(ParticleTypes.NOTE, at.getX() + 0.5, at.getY() + 1.2, at.getZ() + 0.5, 0, note / 24.0, 0.0, 0.0, 1.0);
        // The bass, every other beat, on the second block itself.
        if (blocks.size() > 1 && beat % 2 == 0) {
            BlockPos bass = blocks.get(1);
            BlockState s = level.getBlockState(bass);
            if (s.getBlock() instanceof NoteBlock) {
                level.setBlock(bass, s.setValue(NoteBlock.NOTE, Math.floorMod(note - 12, 25)), 2);
                level.blockEvent(bass, s.getBlock(), 0, 0);
            }
        }
    }

    /** Folk in the tavern now. */
    static int company(ServerLevel level, Ledger.Building tav) {
        return level.getEntitiesOfClass(VillageFolkEntity.class, new AABB(tav.anchor()).inflate(5, 3, 5),
            f -> f.isAlive() && !f.isSleeping()).size();
    }

    private static final Map<Ledger.Building, List<BlockPos>> NOTES = new ConcurrentHashMap<>();

    static List<BlockPos> noteBlocks(Ledger.Building tav) {
        return NOTES.computeIfAbsent(tav, k -> {
            List<BlockPos> out = new ArrayList<>();
            for (BuildGoal.Placement p : BuildGoal.plan(k.structure(), k.anchor(), k.facing(), 13)) {
                if (p.part() == BuildGoal.Part.NOTE_BLOCK) out.add(p.pos());
            }
            return List.copyOf(out);
        });
    }

    // ------------------------------------------------------------------ a round

    /** The board on the bar that says a round may be bought, put up if it is missing, for nothing
     *  (the showcase and the tests). */
    public static void board(ServerLevel level, Ledger.Building tav) {
        board(level, null, tav, true);
    }

    /** The board on the bar, put up if it is missing: a sign out of the village's stores (or two
     *  planks), unless {@code free}; no board till there is one. */
    static void board(ServerLevel level, @javax.annotation.Nullable Villages.Village v, Ledger.Building tav, boolean free) {
        Direction back = tav.facing(), right = back.getClockWise();
        // The bar runs down the left of the room; the board hangs on its front, facing the tables.
        BlockPos barrel = tav.anchor().relative(right, -2);
        if (!level.getBlockState(barrel).is(Blocks.BARREL)) return;
        BlockPos at = barrel.relative(right);
        BlockState s = level.getBlockState(at);
        if (!(s.getBlock() instanceof WallSignBlock)) {
            if (!s.isAir()) return;
            if (!free && (v == null || !Crafts.sign(level, v))) return;
            level.setBlock(at, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, right), 3);
        }
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
            TownLife.write(sign, new String[]{ "Buy a round", "a coin a head", "", "(right-click)" });
        }
    }

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        if (!(level.getBlockState(pos).getBlock() instanceof WallSignBlock)) return;
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        if (!"Buy a round".equals(sign.getFrontText().getMessage(0, false).getString())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        Villages.Village v = Villages.nearest(level, pos, 96);
        if (v == null) return;
        e.getEntity().displayClientMessage(Component.literal(round(level, v, e.getEntity())), true);
    }

    /** A player buys a round for everybody in the tavern. Returns what to tell them. */
    public static String round(ServerLevel level, Villages.Village v, Player p) {
        Ledger.Building tav = of(v.id());
        if (tav == null) return "There's no tavern here.";
        List<VillageFolkEntity> in = level.getEntitiesOfClass(VillageFolkEntity.class, new AABB(tav.anchor()).inflate(5, 3, 5),
            f -> f.isAlive() && !f.isSleeping() && !f.isBaby() && v.id().equals(f.ownerId()));
        if (in.isEmpty()) return "Nobody's in to drink it.";
        int price = in.size();
        int coins = Market.coinsHeld(p);
        if (coins < price) return "A round for " + in.size() + " is " + price + " coins. You have " + coins + ".";
        Market.payOut(p, price);
        com.jrpetty.mcassistant.village.Ledger.addCoins(v.id(), price);
        Economy.sold(v.id(), price);
        long day = level.getDayTime() / 24000L;
        String name = p.getName().getString();
        for (VillageFolkEntity f : in) {
            boolean first = TOASTED.add(f.getUUID() + "/" + p.getUUID() + "/" + day);
            if (first) {
                f.persona().feelFor(p.getUUID(), name, 4);
                f.persona().remember(day, name + " bought a round at the tavern", 3);
            }
            f.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 1200, 0));
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            f.sayLater(FolkTalk.pick(f.getRandom(), "To " + name + "!", "Cheers, " + name + "!", "Good health!",
                "A gentleman and a scholar!", "Well, I never — thank you!"), 10 + f.getRandom().nextInt(40));
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 2.0, f.getZ(), 4, 0.3, 0.2, 0.3, 0.0);
        }
        if (TOASTED.size() > 4096) TOASTED.clear();
        level.playSound(null, tav.anchor(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1.0F, 1.0F);
        Villages.tell(v.id(), day, name + " bought a round at the tavern");
        Standing.stir(v.id(), p.getUUID());
        return "You bought a round for " + in.size() + " (" + price + (price == 1 ? " coin" : " coins") + "). Cheers!";
    }

    /** For the status line. */
    static Component line(String s) { return Component.literal(s).withStyle(ChatFormatting.GOLD); }
}
