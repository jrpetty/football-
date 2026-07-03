package com.jrpetty.mazerunner;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.jrpetty.mazerunner.config.MazeConfigData;
import com.jrpetty.mazerunner.config.MazeConfigData.StateBox;
import com.jrpetty.mazerunner.config.MazeConfigs;
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

    private static final int DOORS_OPEN_AT = 1000;
    private static final int DOORS_WARN_AT = 11500; // "get back to the Glade" warning
    private static final int DOORS_CLOSE_AT = 12500;
    private static final int SHIFT_AT = 18000;
    private static final int DAY_TICKS = 24000;

    public static final ResourceKey<LootTable> MAZE_CACHE_LOOT = ResourceKey.create(
        Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath(MazeRunnerMod.MODID, "chests/maze_cache"));

    /** Spawn point at the centre of the (fresh-slate) Glade. */
    public static final BlockPos GLADE_SPAWN = new BlockPos(768, 61, 768);

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

    public static String formatMs(long ms) {
        long tenths = (ms / 100) % 10;
        long secs = (ms / 1000) % 60;
        long mins = ms / 60000;
        return String.format("%d:%02d.%d", mins, secs, tenths);
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
        level.setDefaultSpawnPos(GLADE_SPAWN, 0.0F);
        MazeRunnerMod.LOGGER.info("Maze Runner world active — day {}, layout {}, exit {}",
            state.dayNumber(), cfg.layout(state.physicalLayout()).name(),
            cfg.layout(state.physicalLayout()).exitId());
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
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        // Death sends runners back to the Box, always.
        if (event.getEntity() instanceof ServerPlayer player
            && player.serverLevel() != null && isMazeLevel(player.serverLevel())) {
            player.teleportTo(GLADE_SPAWN.getX() + 0.5, GLADE_SPAWN.getY(), GLADE_SPAWN.getZ() + 0.5);
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
    }

    /** The Glade is safe ground — hostile mobs never survive inside it. */
    private static void sweepGlade(ServerLevel level) {
        MazeConfigData cfg = MazeConfigs.get();
        AABB glade = new AABB(cfg.gladeBlockMin, cfg.floorY - 4, cfg.gladeBlockMin,
            cfg.gladeBlockMax + 1, cfg.wallTopY + 2, cfg.gladeBlockMax + 1);
        for (Monster monster : level.getEntitiesOfClass(Monster.class, glade)) {
            if (!monster.isPersistenceRequired() && !monster.hasCustomName()) {
                monster.discard();
            }
        }
    }

    /** Real seconds until a future day-time tick, accounting for the 1/6 day and 1/3 night rates. */
    private static int realSecondsUntil(int t, int target) {
        long realTicks = 0;
        if (t < 12000) {
            int dayTicks = Math.min(target, 12000) - t;
            realTicks += Math.max(0, dayTicks) * 6L;
            if (target > 12000) realTicks += (target - 12000) * 3L;
        } else {
            realTicks += (target - t) * 3L;
        }
        return (int) (realTicks / 20);
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
            int remain = realSecondsUntil(t, DOORS_CLOSE_AT);
            CLOCK_BAR.setName(Component.literal(
                "☀ Day " + state.dayNumber() + " — doors seal in " + mmss(remain))
                .withStyle(ChatFormatting.GOLD));
            CLOCK_BAR.setColor(BossEvent.BossBarColor.YELLOW);
            CLOCK_BAR.setProgress(Math.max(0.0F, Math.min(1.0F,
                (DOORS_CLOSE_AT - t) / (float) DOORS_CLOSE_AT)));
        } else {
            int remain = realSecondsUntil(t, DAY_TICKS);
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
        long sixths = state.virtualSixths();
        int prevT = (int) ((sixths / 6) % DAY_TICKS);
        sixths += prevT < 12000 ? 1 : 2;
        state.setVirtualSixths(sixths);
        int newT = (int) ((sixths / 6) % DAY_TICKS);
        if (newT == prevT) return;

        boolean wrapped = newT < prevT;
        if (crossed(prevT, newT, wrapped, DOORS_OPEN_AT)) onDoorsOpen(level, state);
        if (crossed(prevT, newT, wrapped, DOORS_WARN_AT)) {
            broadcast(level, Component.literal(
                "⚠ The sun is setting — the Glade doors seal soon. Get back.")
                .withStyle(ChatFormatting.RED));
        }
        if (crossed(prevT, newT, wrapped, DOORS_CLOSE_AT)) onDoorsClose(level, state);
        if (crossed(prevT, newT, wrapped, SHIFT_AT)) onMazeShift(level, state);
        if (wrapped) onDawn(level, state);

        level.setDayTime((state.dayNumber() - 1) * DAY_TICKS + newT);
    }

    private static boolean crossed(int prev, int next, boolean wrapped, int threshold) {
        if (wrapped) return threshold > prev || threshold <= next;
        return threshold > prev && threshold <= next;
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
    }

    private static void onDoorsClose(ServerLevel level, MazeWorldState state) {
        MazeConfigData cfg = MazeConfigs.get();
        state.setDoorsOpen(false);
        for (MazeConfigData.DoorDef door : cfg.doors) {
            WallAnimator.enqueue(door.box(), true);
        }
        broadcast(level, Component.literal(
            "☾ Dusk — the Glade doors are sealing. Nobody survives a night in the Maze…")
            .withStyle(ChatFormatting.DARK_PURPLE));
        rumble(level, 0.5F);
    }

    /** Overnight: diff the walls to the NEXT day's layout, swap gates and portals. */
    private static void onMazeShift(ServerLevel level, MazeWorldState state) {
        MazeConfigData cfg = MazeConfigs.get();
        MazeConfigData.LayoutDef current = cfg.layout(state.physicalLayout());
        MazeConfigData.LayoutDef next = cfg.layout(state.layoutIndexForDay(state.dayNumber() + 1));
        if (next.index() == current.index()) return;

        int changes = 0;
        for (MazeConfigData.TogglePoint tp : cfg.togglePoints.values()) {
            boolean openNow = current.open().contains(tp.id());
            boolean openNext = next.open().contains(tp.id());
            if (openNow != openNext) {
                WallAnimator.enqueue(tp.box(), !openNext);
                changes++;
            }
        }
        // exit gates: seal the old exit, open the new one
        MazeConfigData.ExitDef oldExit = cfg.exits.get(current.exitId());
        MazeConfigData.ExitDef newExit = cfg.exits.get(next.exitId());
        WallAnimator.enqueue(oldExit.gapBox(), true);
        WallAnimator.enqueue(newExit.gapBox(), false);

        state.setPhysicalLayout(next.index());
        setPortalActive(level, oldExit, false);
        setPortalActive(level, newExit, true);

        broadcast(level, Component.literal(
            "The Maze is shifting — " + changes + " passages are moving in the dark.")
            .withStyle(ChatFormatting.DARK_AQUA));
        rumble(level, 0.4F);
        MazeRunnerMod.LOGGER.info("Maze shift: {} -> {} ({} toggle changes, exit {} -> {})",
            current.name(), next.name(), changes, oldExit.id(), newExit.id());
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

        for (StateBox sb : cfg.boxesIn(cx, cz)) {
            if (WallAnimator.isAnimating(sb.box())) continue;
            boolean open = switch (sb.kind()) {
                case TOGGLE -> layout.open().contains(sb.id());
                case DOOR -> state.doorsOpen();
                case EXIT_GAP -> layout.exitId().equals(sb.id());
            };
            applyBoxInChunk(level, sb.box(), open, cx, cz);
        }

        for (MazeConfigData.ExitDef exit : cfg.exits.values()) {
            if (exit.portalX() >> 4 == cx && exit.portalZ() >> 4 == cz) {
                setPortalActive(level, exit, layout.exitId().equals(exit.id()));
            }
        }

        for (int[] cell : cfg.chestCells) {
            if (cell[0] == cx && cell[1] == cz
                && MazeStructures.plazaAtCell(cfg, cell[0], cell[1]) == null) {
                ensureChestAt(level, state,
                    new BlockPos(cell[0] * cfg.cellSize + 7, cfg.floorY + 1, cell[1] * cfg.cellSize + 7));
            }
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
        for (int[] cell : cfg.chestCells) {
            if (MazeStructures.plazaAtCell(cfg, cell[0], cell[1]) != null) continue;
            if (loadedChunks.contains(ChunkPos.asLong(cell[0], cell[1]))) {
                ensureChestAt(level, state,
                    new BlockPos(cell[0] * cfg.cellSize + 7, cfg.floorY + 1, cell[1] * cfg.cellSize + 7));
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
        if (state.timerRunning()) {
            long elapsed = state.stopTimer(System.currentTimeMillis());
            boolean record = state.recordRun(elapsed);
            broadcast(level, Component.literal(
                "🏁 " + player.getName().getString() + " reached the exit — the Maze is beaten in "
                    + formatMs(elapsed) + (record ? " — NEW WORLD RECORD!" : "!"))
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            for (ServerPlayer p : level.players()) {
                p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.0F, 1.0F);
            }
        } else {
            player.displayClientMessage(Component.literal(
                "You found the exit! Start a timed run with /maze start.")
                .withStyle(ChatFormatting.GREEN), true);
        }
    }
}
