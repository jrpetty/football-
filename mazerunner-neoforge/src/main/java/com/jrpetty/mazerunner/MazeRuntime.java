package com.jrpetty.mazerunner;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.jrpetty.mazerunner.config.GrieverAudio;
import com.jrpetty.mazerunner.config.MazeClock;
import com.jrpetty.mazerunner.config.MazeConfigData;
import com.jrpetty.mazerunner.config.MazeConfigData.StateBox;
import com.jrpetty.mazerunner.config.MazeConfigs;
import com.jrpetty.mazerunner.config.RunScoring;
import com.jrpetty.mazerunner.config.MazeStructures;
import com.jrpetty.mazerunner.gen.MazeChunkGenerator;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives the living maze: the custom 60min-day/30min-night clock, Glade doors
 * at dawn/dusk, the overnight layout shift (animated wall diff), exit portal
 * activation, chunk-load state snapping, weekly chest rerolls, the escape
 * timer, and death → Box respawn.
 *
 * <p>Daily timeline (in day-time ticks, one 24000-tick day = 90 real minutes):
 * 1000 doors open · 12500 doors seal · 18000 the maze shifts to the next
 * day's layout · 24000 dawn, the day counter advances.
 */
public final class MazeRuntime {

    // Clock constants and event scheduling live in MazeClock (pure, unit-tested).
    private static final int DOORS_OPEN_AT = MazeClock.DOORS_OPEN_AT;
    private static final int DOORS_CLOSE_AT = MazeClock.DOORS_CLOSE_AT;
    private static final int DAY_TICKS = MazeClock.DAY_TICKS;

    // ---- Grievers: the maze's nocturnal hunters -----------------------------
    /** How often (game ticks) we try to top up the Griever population at night. */
    private static final int GRIEVER_SPAWN_INTERVAL = 100;
    /** Closest a Griever spawns to a player (blocks) — out of immediate reach. */
    private static final int GRIEVER_MIN_DIST = 14;
    /** Farthest a Griever spawns from a player (blocks). */
    private static final int GRIEVER_MAX_DIST = 40;
    /** Grievers this far from every player (blocks) are culled. */
    private static final int GRIEVER_DESPAWN_DIST = 72;
    /** Base per-player Griever cap in week 1; grows by one each week. */
    private static final int GRIEVER_BASE_CAP = 2;
    /** Hard per-player Griever cap however many weeks pass. */
    private static final int GRIEVER_MAX_CAP = 7;

    public static final ResourceKey<LootTable> MAZE_CACHE_LOOT = ResourceKey.create(
        Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath(MazeRunnerMod.MODID, "chests/maze_cache"));

    /** Spawn point on the grate inside the Box elevator at the Glade centre. */
    public static BlockPos gladeSpawn() {
        MazeConfigData cfg = MazeConfigs.get();
        int c = (cfg.gladeBlockMin + cfg.gladeBlockMax) / 2;
        return new BlockPos(c, cfg.floorY + 2, c);
    }

    // Chunk events can fire off-thread during generation; buffer, drain on tick.
    private static final ConcurrentLinkedQueue<Long> pendingLoads = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Long> pendingUnloads = new ConcurrentLinkedQueue<>();
    private static final Set<Long> loadedChunks = new HashSet<>();
    private static final Map<UUID, Long> portalCooldown = new HashMap<>();

    /** Always-visible clock: day number + real time until the doors seal / dawn. */
    private static final ServerBossEvent CLOCK_BAR = new ServerBossEvent(
        Component.empty(), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);

    private MazeRuntime() {}

    // ------------------------------------------------------------- helpers

    public static boolean isMazeLevel(ServerLevel level) {
        return level.getChunkSource().getGenerator() instanceof MazeChunkGenerator;
    }

    private static ServerLevel mazeLevel(net.minecraft.server.MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld != null && isMazeLevel(overworld) ? overworld : null;
    }

    private static void broadcast(ServerLevel level, Component msg) {
        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(msg, false);
        }
    }

    private static void rumble(ServerLevel level, float pitch) {
        for (ServerPlayer player : level.players()) {
            player.playNotifySound(SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 2.0F, pitch);
        }
    }

    // ------------------------------------------------------------- lifecycle

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel level = mazeLevel(event.getServer());
        if (level == null) return;
        MazeConfigData cfg = MazeConfigs.get();
        MazeWorldState state = MazeWorldState.get(level);

        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, event.getServer());
        boolean fresh = state.ensureSchedule(level.getSeed());
        if (fresh) {
            // Start moments before dawn's door-opening so new worlds come alive quickly.
            state.setVirtualSixths((DOORS_OPEN_AT - 6L) * 6);
            StringBuilder order = new StringBuilder();
            for (int idx : state.schedule()) order.append(cfg.layout(idx).name()).append(' ');
            MazeRunnerMod.LOGGER.info("Maze Runner: world schedule rolled: {}", order.toString().trim());
        }
        level.setDefaultSpawnPos(gladeSpawn(), 0.0F);
        MazeRunnerMod.LOGGER.info("Maze Runner world active — day {}, layout {}, fixed exit {}",
            state.dayNumber(), cfg.layout(state.physicalLayout()).name(), cfg.fixedExitId);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        pendingLoads.clear();
        pendingUnloads.clear();
        loadedChunks.clear();
        portalCooldown.clear();
        WallAnimator.clear();
        CLOCK_BAR.removeAllPlayers();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CLOCK_BAR.removePlayer(player);
            portalCooldown.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        // Only fully-loaded chunks — this event also fires for proto-chunks mid-worldgen.
        if (event.getLevel() instanceof ServerLevel level && isMazeLevel(level)
            && event.getChunk() instanceof LevelChunk) {
            pendingLoads.add(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && isMazeLevel(level)) {
            pendingUnloads.add(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isMazeLevel(level)) return;
        if (event.getPos().getY() > MazeConfigData.BUILD_LIMIT_Y
            && event.getEntity() instanceof ServerPlayer player && !player.isCreative()) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal(
                "You can't build above y" + MazeConfigData.BUILD_LIMIT_Y + " in the Maze.")
                .withStyle(ChatFormatting.RED), true);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        // Death sends runners back to the Box, always.
        if (event.getEntity() instanceof ServerPlayer player
            && player.serverLevel() != null && isMazeLevel(player.serverLevel())) {
            BlockPos s = gladeSpawn();
            player.teleportTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5);
        }
    }

    /** Dying doesn't end your run — it just costs you. */
    @SubscribeEvent
    public static void onPlayerDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if (level == null || !isMazeLevel(level)) return;
        MazeWorldState state = MazeWorldState.get(level);
        if (!state.isRunning(player.getUUID())) return;

        int deaths = state.addDeath(player.getUUID());
        player.displayClientMessage(Component.literal(
            "Death " + deaths + " — " + RunScoring.format(RunScoring.penaltyMillis(deaths))
                + " added to your run. The clock is still going.")
            .withStyle(ChatFormatting.RED), false);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
            && player.serverLevel() != null && isMazeLevel(player.serverLevel())) {
            MazeAdvancements.award(player, MazeAdvancements.GREENIE);
        }
    }

    // ------------------------------------------------------------- main tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = mazeLevel(event.getServer());
        if (level == null) return;
        MazeWorldState state = MazeWorldState.get(level);

        drainChunkEvents(level, state);
        advanceClock(level, state);
        WallAnimator.tick(level);

        long gameTime = level.getGameTime();
        if (gameTime % 20 == 0) updateClockBar(level, state);
        if (gameTime % 100 == 0) sweepGlade(level);
        if (gameTime % 20 == 0) manageGrievers(level, state);
        if (gameTime % 20 == 0) enforceWallTops(level);
        if (gameTime % 20 == 0) grieverAudio(level, state);
        if (gameTime % 20 == 0) updateRuns(level, state);
        if (gameTime % GRIEVER_SPAWN_INTERVAL == 0) spawnGrievers(level, state);
    }

    /** The Glade is safe ground — hostile mobs never survive inside it. */
    private static void sweepGlade(ServerLevel level) {
        MazeConfigData cfg = MazeConfigs.get();
        AABB glade = new AABB(cfg.gladeBlockMin, cfg.floorY - 4, cfg.gladeBlockMin,
            cfg.gladeBlockMax + 1, cfg.wallTopY + 2, cfg.gladeBlockMax + 1);
        for (Monster monster : level.getEntitiesOfClass(Monster.class, glade)) {
            // Grievers never belong in the Glade; ordinary naturally-spawned mobs
            // are cleared too (named/persistent set-pieces are left alone).
            if (monster instanceof GrieverEntity
                || (!monster.isPersistenceRequired() && !monster.hasCustomName())) {
                monster.discard();
            }
        }
    }

    /**
     * Nobody runs the maze along the roof. Greenery already stops well below
     * the crest and the build limit sits under it, so this is a backstop: any
     * survival player who ends up above the wall tops inside the maze is
     * dropped back into the corridor beneath them.
     */
    private static void enforceWallTops(ServerLevel level) {
        MazeConfigData cfg = MazeConfigs.get();
        for (ServerPlayer player : level.players()) {
            if (player.isCreative() || player.isSpectator()) continue;
            if (player.getBlockY() <= cfg.wallTopY) continue;
            int cellX = Math.floorDiv(player.getBlockX(), cfg.cellSize);
            int cellZ = Math.floorDiv(player.getBlockZ(), cfg.cellSize);
            if (!cfg.inGrid(cellX, cellZ)) continue; // exit pad / outside the maze
            int bx = cellX * cfg.cellSize + cfg.cellSize / 2;
            int bz = cellZ * cfg.cellSize + cfg.cellSize / 2;
            player.teleportTo(bx + 0.5, cfg.floorY + 1, bz + 0.5);
            player.resetFallDistance();
            player.displayClientMessage(Component.literal(
                "The walls are not a road — you slip back into the Maze.")
                .withStyle(ChatFormatting.RED), true);
        }
    }

    // ------------------------------------------------------------- Grievers

    /**
     * Every loaded Griever. Uses the level's type index rather than a
     * maze-sized AABB — scanning a 576×576 box every second would walk
     * thousands of empty entity sections for nothing.
     */
    private static List<? extends GrieverEntity> allGrievers(ServerLevel level) {
        return level.getEntities(ModEntities.GRIEVER.get(), g -> true);
    }

    /** Per-player Griever cap for the current week (grows one per completed cycle). */
    private static int grieverCap(MazeWorldState state) {
        int week = (int) ((state.dayNumber() - 1) / MazeWorldState.LAYOUT_COUNT);
        return Math.min(GRIEVER_BASE_CAP + week, GRIEVER_MAX_CAP);
    }

    /**
     * Housekeeping run once a second: purge Grievers caught in daylight, wandered
     * into the Glade, or stranded far from every runner, and keep the survivors'
     * eerie glow topped up so they read as a moving threat in the dark corridors.
     */
    private static void manageGrievers(ServerLevel level, MazeWorldState state) {
        MazeConfigData cfg = MazeConfigs.get();
        List<? extends GrieverEntity> grievers = allGrievers(level);
        if (grievers.isEmpty()) return;
        boolean day = state.doorsOpen();
        for (GrieverEntity g : grievers) {
            if (day) { g.discard(); continue; } // the Grievers retreat by daylight
            int gcx = Math.floorDiv(g.getBlockX(), cfg.cellSize);
            int gcz = Math.floorDiv(g.getBlockZ(), cfg.cellSize);
            if (cfg.inGlade(gcx, gcz)) { g.discard(); continue; }
            if (level.getNearestPlayer(g, GRIEVER_DESPAWN_DIST) == null) { g.discard(); continue; }
            g.addEffect(new MobEffectInstance(MobEffects.GLOWING,
                MobEffectInstance.INFINITE_DURATION, 0, false, false));
        }
    }

    /**
     * The Maze's soundtrack. Each second every runner hears the nearest Griever
     * at a volume and cadence set by how close it is and whether it has their
     * scent: distant grinding somewhere in the corridors, heavy footfalls you
     * can take a bearing from, or — once it is hunting you — your own heartbeat.
     */
    private static void grieverAudio(ServerLevel level, MazeWorldState state) {
        if (state.doorsOpen()) return;
        List<? extends GrieverEntity> grievers = allGrievers(level);
        if (grievers.isEmpty()) return;
        long second = level.getGameTime() / 20;

        for (ServerPlayer player : level.players()) {
            if (player.isCreative() || player.isSpectator()) continue;
            GrieverEntity nearest = null;
            double bestSqr = Double.MAX_VALUE;
            for (GrieverEntity g : grievers) {
                double d = g.distanceToSqr(player);
                if (d < bestSqr) {
                    bestSqr = d;
                    nearest = g;
                }
            }
            if (nearest == null) continue;

            boolean hunting = nearest.getTarget() == player;
            GrieverAudio.Cue cue = GrieverAudio.cueFor(Math.sqrt(bestSqr), hunting);
            if (cue == GrieverAudio.Cue.SILENT) continue;
            if (second % GrieverAudio.intervalSeconds(cue) != 0) continue;

            float volume = GrieverAudio.volumeFor(cue);
            float pitch = GrieverAudio.pitchFor(cue);
            switch (cue) {
                // Heard by the hunted runner alone — it is their pulse, not a place.
                case HUNTING -> player.playNotifySound(
                    SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, volume, pitch);
                // Positional, so you can work out which way to run.
                case STALKING -> level.playSound(null, nearest.getX(), nearest.getY(), nearest.getZ(),
                    SoundEvents.IRON_GOLEM_STEP, SoundSource.HOSTILE, volume, pitch);
                case DISTANT -> level.playSound(null, nearest.getX(), nearest.getY(), nearest.getZ(),
                    SoundEvents.GRINDSTONE_USE, SoundSource.HOSTILE, volume, pitch);
                default -> { }
            }
        }
    }

    /** At night, top each nearby runner's corridors up to the weekly Griever cap. */
    private static void spawnGrievers(ServerLevel level, MazeWorldState state) {
        if (state.doorsOpen()) return; // Grievers only roam once the doors seal
        MazeConfigData cfg = MazeConfigs.get();
        int cap = grieverCap(state);
        var rng = level.random;
        for (ServerPlayer player : level.players()) {
            if (player.isCreative() || player.isSpectator()) continue;
            int pcx = Math.floorDiv(player.getBlockX(), cfg.cellSize);
            int pcz = Math.floorDiv(player.getBlockZ(), cfg.cellSize);
            if (!cfg.inGrid(pcx, pcz) || cfg.inGlade(pcx, pcz)) continue; // safe in the Glade
            AABB near = player.getBoundingBox().inflate(GRIEVER_MAX_DIST);
            if (level.getEntitiesOfClass(GrieverEntity.class, near).size() >= cap) continue;
            trySpawnNear(level, player.getBlockX(), player.getBlockZ(), rng);
        }
    }

    /** Attempts (a few times) to place one Griever in an open corridor around a point. */
    private static boolean trySpawnNear(ServerLevel level, int ox, int oz,
            net.minecraft.util.RandomSource rng) {
        MazeConfigData cfg = MazeConfigs.get();
        for (int attempt = 0; attempt < 10; attempt++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            int dist = GRIEVER_MIN_DIST + rng.nextInt(GRIEVER_MAX_DIST - GRIEVER_MIN_DIST + 1);
            int wx = ox + (int) Math.round(Math.cos(ang) * dist);
            int wz = oz + (int) Math.round(Math.sin(ang) * dist);
            int ccx = Math.floorDiv(wx, cfg.cellSize);
            int ccz = Math.floorDiv(wz, cfg.cellSize);
            if (!cfg.inGrid(ccx, ccz) || cfg.inGlade(ccx, ccz)) continue;
            // Cell centres are always open corridor space in every layout.
            int bx = ccx * cfg.cellSize + cfg.cellSize / 2;
            int bz = ccz * cfg.cellSize + cfg.cellSize / 2;
            if (!cfg.isBaseOpen(bx, bz)) continue;
            if (!level.hasChunk(bx >> 4, bz >> 4)) continue;
            BlockPos feet = new BlockPos(bx, cfg.floorY + 1, bz);
            if (!level.getBlockState(feet.below()).blocksMotion()) continue;
            if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) continue;
            spawnGrieverAt(level, feet, rng);
            return true;
        }
        return false;
    }

    private static void spawnGrieverAt(ServerLevel level, BlockPos feet,
            net.minecraft.util.RandomSource rng) {
        GrieverEntity griever = ModEntities.GRIEVER.get().create(level);
        if (griever == null) return;
        griever.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, rng.nextFloat() * 360.0F, 0.0F);
        griever.finalizeSpawn(level, level.getCurrentDifficultyAt(feet), MobSpawnType.EVENT, null);
        griever.addEffect(new MobEffectInstance(MobEffects.GLOWING,
            MobEffectInstance.INFINITE_DURATION, 0, false, false));
        level.addFreshEntity(griever);
    }

    /** Removes every loaded Griever from the maze; returns how many. */
    private static int despawnGrievers(ServerLevel level) {
        int count = 0;
        for (GrieverEntity g : allGrievers(level)) {
            g.discard();
            count++;
        }
        return count;
    }

    /** Loaded Griever count, for the status command. */
    public static int countGrievers(ServerLevel level) {
        return allGrievers(level).size();
    }

    /** Debug helper: force one Griever to spawn near a player. */
    public static boolean spawnGrieverNear(ServerLevel level, ServerPlayer player) {
        return trySpawnNear(level, player.getBlockX(), player.getBlockZ(), level.random);
    }

    private static String mmss(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static void updateClockBar(ServerLevel level, MazeWorldState state) {
        for (ServerPlayer player : level.players()) {
            CLOCK_BAR.addPlayer(player); // set-backed, idempotent
        }
        int t = (int) ((state.virtualSixths() / 6) % DAY_TICKS);
        if (t < DOORS_CLOSE_AT) {
            int remain = MazeClock.realSecondsUntil(t, DOORS_CLOSE_AT);
            CLOCK_BAR.setName(Component.literal(
                "☀ Day " + state.dayNumber() + " — doors seal in " + mmss(remain))
                .withStyle(ChatFormatting.GOLD));
            CLOCK_BAR.setColor(BossEvent.BossBarColor.YELLOW);
            CLOCK_BAR.setProgress(Math.max(0.0F, Math.min(1.0F,
                (DOORS_CLOSE_AT - t) / (float) DOORS_CLOSE_AT)));
        } else {
            int remain = MazeClock.realSecondsUntil(t, DAY_TICKS);
            CLOCK_BAR.setName(Component.literal(
                "☾ Night " + state.dayNumber() + " — the Maze shifts. Dawn in " + mmss(remain))
                .withStyle(ChatFormatting.DARK_PURPLE));
            CLOCK_BAR.setColor(BossEvent.BossBarColor.PURPLE);
            CLOCK_BAR.setProgress(Math.max(0.0F, Math.min(1.0F,
                (DAY_TICKS - t) / (float) (DAY_TICKS - DOORS_CLOSE_AT))));
        }
    }

    private static void drainChunkEvents(ServerLevel level, MazeWorldState state) {
        Long key;
        while ((key = pendingUnloads.poll()) != null) {
            loadedChunks.remove(key);
        }
        while ((key = pendingLoads.poll()) != null) {
            loadedChunks.add(key);
            ChunkPos pos = new ChunkPos(key);
            snapChunk(level, state, pos.x, pos.z);
        }
    }

    /**
     * Custom clock: a 24000-tick day over 90 real minutes — daytime (0..12000)
     * at 1/6 tick per tick (60 min), night at 1/3 (30 min).
     */
    private static void advanceClock(ServerLevel level, MazeWorldState state) {
        long from = state.virtualSixths();
        int t = (int) ((from / 6) % DAY_TICKS);
        applyTimeAdvance(level, state, from, from + MazeClock.sixthsPerTick(t));
    }

    /**
     * Advances the clock to {@code toSixths}, firing every day-event crossed on
     * the way (dawn, doors open, dusk warning, doors seal, maze shift). Because
     * {@link MazeClock#eventsCrossed} enumerates every whole tick passed, a big
     * jump from a command triggers all the events it passes — so skipping to
     * night actually seals the doors and skipping to morning actually reshapes
     * the maze.
     */
    static void applyTimeAdvance(ServerLevel level, MazeWorldState state, long fromSixths, long toSixths) {
        state.setVirtualSixths(toSixths);
        for (MazeClock.Event event : MazeClock.eventsCrossed(fromSixths / 6, toSixths / 6)) {
            switch (event) {
                case DAWN -> onDawn(level, state);
                case DOORS_OPEN -> onDoorsOpen(level, state);
                case DUSK_WARNING -> broadcast(level, Component.literal(
                    "⚠ The sun is setting — the Glade doors seal soon. Get back.")
                    .withStyle(ChatFormatting.RED));
                case DOORS_CLOSE -> onDoorsClose(level, state);
                case MAZE_SHIFT -> onMazeShift(level, state);
            }
        }
        int nowT = (int) ((toSixths / 6) % DAY_TICKS);
        level.setDayTime((state.dayNumber() - 1) * DAY_TICKS + nowT);
    }

    /** Jumps forward to the next occurrence of a day-tick, firing events crossed. */
    static void jumpToDayTick(ServerLevel level, MazeWorldState state, int targetTick) {
        long target = MazeClock.nextOccurrence(state.virtualSixths() / 6, targetTick);
        applyTimeAdvance(level, state, state.virtualSixths(), target * 6);
    }

    // ------------------------------------------------------------- day events

    private static void onDoorsOpen(ServerLevel level, MazeWorldState state) {
        MazeConfigData cfg = MazeConfigs.get();
        state.setDoorsOpen(true);
        for (MazeConfigData.DoorDef door : cfg.doors) {
            WallAnimator.enqueue(door.box(), false);
        }
        MazeConfigData.LayoutDef layout = cfg.layout(state.physicalLayout());
        broadcast(level, Component.literal(
            "☀ Day " + state.dayNumber() + " — the Glade doors are opening. Today's exit is out there ("
                + layout.name() + ").").withStyle(ChatFormatting.GOLD));
        rumble(level, 0.7F);

        awardNightSurvivors(level);

        int retreated = despawnGrievers(level);
        if (retreated > 0) {
            broadcast(level, Component.literal(
                "The Grievers slink back into the walls as the sun rises.")
                .withStyle(ChatFormatting.GRAY));
        }
    }

    private static void onDoorsClose(ServerLevel level, MazeWorldState state) {
        MazeConfigData cfg = MazeConfigs.get();
        state.setDoorsOpen(false);
        for (MazeConfigData.DoorDef door : cfg.doors) {
            WallAnimator.enqueue(door.box(), true);
        }
        broadcast(level, Component.literal(
            "☾ Dusk — the Glade doors are sealing. The Grievers are waking in the Maze…")
            .withStyle(ChatFormatting.DARK_PURPLE));
        rumble(level, 0.5F);

        // Somewhere out in the corridors, something wakes up.
        for (ServerPlayer player : level.players()) {
            player.playNotifySound(SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 0.7F, 0.5F);
        }
    }

    /** Overnight: diff the walls to the NEXT day's layout, swap gates and portals. */
    private static void onMazeShift(ServerLevel level, MazeWorldState state) {
        MazeConfigData cfg = MazeConfigs.get();
        MazeConfigData.LayoutDef current = cfg.layout(state.physicalLayout());
        MazeConfigData.LayoutDef next = cfg.layout(state.layoutIndexForDay(state.dayNumber() + 1));
        if (next.index() == current.index()) return;

        // Only the walls move — the exit portal stays fixed. Toggle points that
        // differ between today's and tomorrow's layout animate open/closed,
        // reshaping the route to the same exit.
        java.util.Set<String> openNow = cfg.open(current.index());
        java.util.Set<String> openNext = cfg.open(next.index());
        int changes = 0;
        for (MazeConfigData.TogglePoint tp : cfg.togglePoints.values()) {
            if (openNow.contains(tp.id()) != openNext.contains(tp.id())) {
                WallAnimator.enqueue(tp.box(), !openNext.contains(tp.id()));
                changes++;
            }
        }

        state.setPhysicalLayout(next.index());

        broadcast(level, Component.literal(
            "The Maze is shifting — " + changes + " passages are moving in the dark.")
            .withStyle(ChatFormatting.DARK_AQUA));
        rumble(level, 0.4F);
        MazeRunnerMod.LOGGER.info("Maze shift: {} -> {} ({} passages changed; exit fixed at {})",
            current.name(), next.name(), changes, cfg.fixedExitId);
    }

    private static void onDawn(ServerLevel level, MazeWorldState state) {
        state.setDayNumber(state.dayNumber() + 1);
        int cycle = (int) ((state.dayNumber() - 1) / MazeWorldState.LAYOUT_COUNT);
        if (cycle != state.chestCycle()) {
            state.setChestCycle(cycle);
            int rerolled = rerollLoadedChests(level, state);
            broadcast(level, Component.literal(
                "A new week begins — the Maze returns to its first form, and the caches are restocked ("
                    + rerolled + " nearby).").withStyle(ChatFormatting.AQUA));
        }
    }

    // ------------------------------------------------------------- chunk snap

    /** Put every mutable segment/portal/chest in a freshly-loaded chunk into its current state. */
    private static void snapChunk(ServerLevel level, MazeWorldState state, int cx, int cz) {
        MazeConfigData cfg = MazeConfigs.get();
        MazeConfigData.LayoutDef layout = cfg.layout(state.physicalLayout());

        java.util.Set<String> openToggles = cfg.open(layout.index());
        for (StateBox sb : cfg.boxesIn(cx, cz)) {
            if (WallAnimator.isAnimating(sb.box())) continue;
            boolean open = switch (sb.kind()) {
                case TOGGLE -> openToggles.contains(sb.id());
                case DOOR -> state.doorsOpen();
                case EXIT_GAP -> cfg.fixedExitId.equals(sb.id()); // only the fixed exit is open
            };
            applyBoxInChunk(level, sb.box(), open, cx, cz);
        }

        // Only the fixed exit has a portal, always lit.
        MazeConfigData.ExitDef fixed = cfg.fixedExit();
        if (fixed.portalX() >> 4 == cx && fixed.portalZ() >> 4 == cz) {
            setPortalActive(level, fixed, true);
        }

        // Chest sites are indexed by the chunk that CONTAINS them — at a 6-block
        // cell a cell index is not a chunk index.
        for (int[] c : cfg.chestSitesIn(cx, cz)) {
            if (MazeStructures.plazaAtCell(cfg, c[3], c[4]) != null) continue;
            ensureChestAt(level, state, new BlockPos(c[0], c[1], c[2]));
        }
        for (int[] c : MazeStructures.chestsIn(cfg, cx, cz)) {
            ensureChestAt(level, state, new BlockPos(c[0], c[1], c[2]));
        }
        for (int[] s : MazeStructures.spawnersIn(cfg, cx, cz)) {
            ensureSpawner(level, new BlockPos(s[0], s[1], s[2]));
        }
    }

    /** Applies open/closed to the slice of a wall box inside one chunk. */
    private static void applyBoxInChunk(ServerLevel level, MazeConfigData.Box box, boolean open,
            int cx, int cz) {
        int x0 = Math.max(box.x0(), cx << 4);
        int x1 = Math.min(box.x1(), (cx << 4) + 15);
        int z0 = Math.max(box.z0(), cz << 4);
        int z1 = Math.min(box.z1(), (cz << 4) + 15);
        if (x0 > x1 || z0 > z1) return;
        MazeConfigData cfg = MazeConfigs.get();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = box.y0(); y <= box.y1(); y++) {
                    BlockState target = open ? air : WallPalette.stateAt(cfg, x, y, z);
                    pos.set(x, y, z);
                    if (level.getBlockState(pos) != target) {
                        level.setBlock(pos, target, flags);
                    }
                }
            }
        }
    }

    private static void setPortalActive(ServerLevel level, MazeConfigData.ExitDef exit, boolean active) {
        MazeConfigData cfg = MazeConfigs.get();
        BlockPos pos = new BlockPos(exit.portalX(), cfg.floorY + 2, exit.portalZ());
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return; // snapped on load instead
        BlockState current = level.getBlockState(pos);
        BlockState want = ModBlocks.EXIT_PORTAL.get().defaultBlockState()
            .setValue(ExitPortalBlock.ACTIVE, active);
        if (current != want) {
            level.setBlock(pos, want, Block.UPDATE_ALL);
        }
    }

    private static void ensureChestAt(ServerLevel level, MazeWorldState state, BlockPos pos) {
        int cycle = state.chestCycle();
        if (state.chestRolledCycle(pos.asLong()) == cycle) return;
        if (!(level.getBlockState(pos).getBlock() instanceof ChestBlock)) {
            level.setBlock(pos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        }
        if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            chest.clearContent();
            chest.setLootTable(MAZE_CACHE_LOOT, pos.asLong() ^ (cycle * 0x9E3779B97F4A7C15L));
            state.markChestRolled(pos.asLong(), cycle);
        }
    }

    private static void ensureSpawner(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.SPAWNER)) {
            level.setBlock(pos, Blocks.SPAWNER.defaultBlockState(), Block.UPDATE_ALL);
        }
        if (level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) {
            spawner.setEntityId(EntityType.ZOMBIE, level.random);
        }
    }

    private static int rerollLoadedChests(ServerLevel level, MazeWorldState state) {
        MazeConfigData cfg = MazeConfigs.get();
        int count = 0;
        for (int[] c : cfg.chestSites()) {
            if (MazeStructures.plazaAtCell(cfg, c[3], c[4]) != null) continue;
            if (loadedChunks.contains(ChunkPos.asLong(c[0] >> 4, c[2] >> 4))) {
                ensureChestAt(level, state, new BlockPos(c[0], c[1], c[2]));
                count++;
            }
        }
        for (int[] c : MazeStructures.allChests(cfg)) {
            if (loadedChunks.contains(ChunkPos.asLong(c[0] >> 4, c[2] >> 4))) {
                ensureChestAt(level, state, new BlockPos(c[0], c[1], c[2]));
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------- escape

    /** Called by the portal block when a player runs into an ACTIVE portal. */
    public static void onPortalTouched(ServerLevel level, BlockPos pos, ServerPlayer player) {
        long now = level.getGameTime();
        Long last = portalCooldown.get(player.getUUID());
        if (last != null && now - last < 100) return; // debounce — they stand in it for many ticks
        portalCooldown.put(player.getUUID(), now);

        MazeWorldState state = MazeWorldState.get(level);
        MazeAdvancements.award(player, MazeAdvancements.ESCAPED);

        MazeWorldState.RunResult result = state.finishRun(
            player.getUUID(), player.getName().getString(), System.currentTimeMillis());
        if (result == null) {
            player.displayClientMessage(Component.literal(
                "You found the exit — but you were never on the clock. Leave the Glade to start a run.")
                .withStyle(ChatFormatting.GREEN), true);
            return;
        }

        String tail = result.worldBest() ? " — NEW WORLD RECORD!"
            : result.personalBest() ? " — a personal best!" : "!";
        broadcast(level, Component.literal(
            "🏁 " + player.getName().getString() + " ran the Maze in "
                + RunScoring.format(result.finalMillis()) + tail)
            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        if (result.deaths() > 0) {
            broadcast(level, Component.literal("   " + RunScoring.format(result.elapsedMillis())
                + " on the clock + " + result.deaths() + " death" + (result.deaths() == 1 ? "" : "s")
                + " (" + RunScoring.format(RunScoring.penaltyMillis(result.deaths())) + " penalty)")
                .withStyle(ChatFormatting.GRAY));
        }
        // If a synchronised race is on, the first runner out takes it.
        if (state.raceRunning()) {
            long now = System.currentTimeMillis();
            boolean record = state.isRaceRecord(state.raceElapsed(now));
            long winning = state.finishRace(player.getName().getString(), now);
            broadcast(level, Component.literal(
                "🏆 RACE OVER — " + player.getName().getString() + " is out first in "
                    + RunScoring.format(winning) + (record ? " — a new race record!" : "!"))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        }

        for (ServerPlayer p : level.players()) {
            p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.0F, 1.0F);
        }

        // Back to the Glade, so the world keeps playing instead of stranding
        // the winner on the exit pad.
        BlockPos home = gladeSpawn();
        player.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
        player.resetFallDistance();
    }

    // ------------------------------------------------------------- the run

    /**
     * A run starts by itself the moment a runner first steps out of the Glade —
     * no command, no lobby. Also grants the "Runner" advancement.
     */
    private static void updateRuns(ServerLevel level, MazeWorldState state) {
        MazeConfigData cfg = MazeConfigs.get();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            int cellX = Math.floorDiv(player.getBlockX(), cfg.cellSize);
            int cellZ = Math.floorDiv(player.getBlockZ(), cfg.cellSize);
            boolean inMaze = cfg.inGrid(cellX, cellZ) && !cfg.inGlade(cellX, cellZ);
            if (!inMaze || state.isRunning(player.getUUID())) continue;

            state.startRun(player.getUUID(), System.currentTimeMillis());
            MazeAdvancements.award(player, MazeAdvancements.RUNNER);
            player.displayClientMessage(Component.literal(
                "⏱ Your run has begun — find the exit. Every death costs "
                    + RunScoring.format(RunScoring.DEATH_PENALTY_MS) + ".")
                .withStyle(ChatFormatting.YELLOW), false);
        }
    }

    /**
     * Starts a synchronised race: every online runner's clock is reset and
     * started together, so the times are directly comparable and the first one
     * out of the Maze wins.
     */
    public static int beginRace(ServerLevel level) {
        MazeWorldState state = MazeWorldState.get(level);
        long now = System.currentTimeMillis();
        state.startRace(now);

        int racers = 0;
        BlockPos home = gladeSpawn();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            player.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            player.resetFallDistance();
            state.abandonRun(player.getUUID());
            state.startRun(player.getUUID(), now);
            racers++;
        }

        broadcast(level, Component.literal(
            "🏁 RACE — everyone back to the Box, clocks start NOW. First runner out of the Maze wins.")
            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        long best = state.raceBestMillis();
        if (best >= 0) {
            broadcast(level, Component.literal("   Record to beat: "
                + RunScoring.format(best) + " by " + state.raceBestHolder())
                .withStyle(ChatFormatting.GRAY));
        }
        for (ServerPlayer p : level.players()) {
            p.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 1.0F, 1.5F);
        }
        return racers;
    }

    /** Dawn: anyone still out in the Maze lived through a night. */
    private static void awardNightSurvivors(ServerLevel level) {
        MazeConfigData cfg = MazeConfigs.get();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) continue;
            int cellX = Math.floorDiv(player.getBlockX(), cfg.cellSize);
            int cellZ = Math.floorDiv(player.getBlockZ(), cfg.cellSize);
            if (cfg.inGrid(cellX, cellZ) && !cfg.inGlade(cellX, cellZ)) {
                MazeAdvancements.award(player, MazeAdvancements.NIGHT_SURVIVOR);
                player.displayClientMessage(Component.literal(
                    "You survived a night in the Maze. Nobody does that.")
                    .withStyle(ChatFormatting.GOLD), false);
            }
        }
    }
}
