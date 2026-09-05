package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Work Zone Marker — the surveyor's wand for laying out a specialist's patch.
 *
 * Two ways round, and they meet in the middle.
 *
 *   Mark the ground first, then hire to it:
 *     right-click a block ..... first click drops a corner stake, the second
 *                                closes the plot. Further clicks widen it.
 *     right-click an assistant  puts THAT one on the plot. Do it again for the
 *                                next, and the next — one field, marked once,
 *                                worked by as many hands as you like. Widening
 *                                it afterwards moves everyone already on it.
 *
 *   Or start from a bot:
 *     right-click an assistant  (with an empty wand) take charge of ITS area.
 *                                If it already has one, this shows it.
 *     right-click a block ..... maps that bot's patch, as above.
 *
 *   sneak + right-click ....... clear the wand: plot, links and all.
 *
 * The outline is drawn while you're marking, and for a few seconds after each
 * change, so you can see the shape as you build it. Once it's set it stops
 * drawing — right-click the bot again with the wand whenever you want another
 * look.
 */
public class ZoneMarkerItem extends Item {

    /** How long the outline keeps drawing after you change it (ticks). */
    private static final int SHOW_TICKS = 160;
    /** How far from a clicked block we'll look for the bot the wand is bound to. */
    private static final double BIND_RANGE = 96.0;

    public ZoneMarkerItem(Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.UNCOMMON));
    }

    // ---------------------------------------------------------------- binding

    /** Right-click an assistant: take charge of its area (or re-show it). */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide) return InteractionResult.SUCCESS;
        if (!(target instanceof AssistantEntity bot)) return InteractionResult.PASS;
        if (!bot.isOwner(player)) {
            tell(player, "That's not your assistant.");
            return InteractionResult.CONSUME;
        }
        CompoundTag data = read(stack);

        // Holding a plot? Then this click puts THIS bot on it. Clicking a second
        // and a third adds them to the same ground, which is the whole point:
        // one field, marked once, worked by as many hands as you like.
        WorkZone plot = plotIn(data);
        if (plot != null) {
            bot.setWorkZone(plot);
            link(data, bot);
            data.putLong("ShowUntil", player.level().getGameTime() + SHOW_TICKS);
            write(stack, data);
            bot.showZoneTo(player);
            int crew = onPlot(player, plot);
            tell(player, bot.displayNameCap() + " is on this plot now — "
                + crew + (crew == 1 ? " working it." : " working it together.")
                + " Right-click another assistant to add them, or a block to redraw it for all of them.");
            return InteractionResult.CONSUME;
        }

        boolean already = bot.getUUID().toString().equals(data.getString("Bot"));

        CompoundTag next = new CompoundTag();
        next.putString("Bot", bot.getUUID().toString());
        next.putLong("ShowUntil", player.level().getGameTime() + SHOW_TICKS);
        write(stack, next); // binding always clears any half-finished perimeter

        if (bot.workZone() != null) {
            bot.showZoneTo(player);
            tell(player, (already ? "" : "Marking out " + bot.displayNameCap() + ". ")
                + "Its patch is " + bot.workZone().describe()
                + " — showing it now. Click two blocks to redraw it.");
        } else {
            tell(player, "Marking out " + bot.displayNameCap()
                + " — click one corner of its patch, then the opposite corner.");
        }
        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------ the mapping

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (ctx.getLevel().isClientSide || player == null) return InteractionResult.SUCCESS;
        ItemStack stack = ctx.getItemInHand();
        BlockPos pos = ctx.getClickedPos();
        CompoundTag data = read(stack);

        if (player.isShiftKeyDown()) {
            write(stack, new CompoundTag());
            tell(player, "Wand cleared.");
            return InteractionResult.CONSUME;
        }

        AssistantEntity bot = boundBot(ctx.getLevel(), data, pos);
        long now = ctx.getLevel().getGameTime();

        // A bound bot and a clicked CHEST: that chest is now the bot's own.
        // Restocks open it first and its output banks there — "this bot uses
        // this chest", said with one click instead of hoping proximity gets it
        // right. Clicking a chest never counts as a plot corner while a bot is
        // bound, because nobody means the corner of their field to be a chest.
        if (bot != null && ctx.getLevel().getBlockEntity(pos) instanceof net.minecraft.world.Container) {
            // The bot says what the link means for ITS trade — a hauler builds
            // its pickup-to-delivery route out of these clicks.
            bot.linkChestSmart(pos);
            return InteractionResult.CONSUME;
        }

        // No bot bound: lay the plot out on its own, and hand it to whoever you
        // like afterwards. Marking the same field once per worker was the thing
        // that made a crew tedious to set up.
        if (bot == null) {
            if (!data.contains("Corner")) {
                data.putLong("Corner", pos.asLong());
                data.putLong("ShowUntil", now + SHOW_TICKS);
                write(stack, data);
                tell(player, "Corner one at " + pos.getX() + ", " + pos.getZ()
                    + " — now click the opposite corner to close the plot.");
                return InteractionResult.CONSUME;
            }
            WorkZone had = plotIn(data);
            WorkZone marked = had != null
                ? had.including(pos)
                : WorkZone.of(BlockPos.of(data.getLong("Corner")), pos, WorkZone.DEFAULT_DEPTH);
            data.putLong("PlotMin", marked.min().asLong());
            data.putLong("PlotMax", marked.max().asLong());
            data.putInt("PlotDepth", marked.depth());
            data.putLong("Corner", pos.asLong());
            data.putLong("ShowUntil", now + SHOW_TICKS);
            // Anyone already put on this plot moves with it, so widening a field
            // after the fact does not mean re-linking the whole crew.
            int moved = retarget(ctx.getLevel(), data, marked);
            write(stack, data);
            outline((ServerLevel) ctx.getLevel(), marked, ParticleTypes.WAX_ON);
            // Into the plot book, named for you. Re-marking or widening the
            // same ground keeps its name — it is still the same field.
            com.jrpetty.mcassistant.ZonePresets.Preset staked =
                com.jrpetty.mcassistant.ZonePresets.stake(player, marked,
                    AssistantEntity.StationTask.NONE);
            tell(player, "\"" + staked.name() + "\" is " + marked.describe()
                + (moved > 0 ? " — moved " + moved + " with it." : "")
                + " Click more blocks to widen it.");
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("Press ")
                .append(net.minecraft.network.chat.Component.keybind("key.mc_assistant.plots"))
                .append(net.minecraft.network.chat.Component.literal(
                    " to crew \"" + staked.name() + "\" from the plot book.")), true);
            return InteractionResult.CONSUME;
        }

        if (!data.contains("Corner")) {
            data.putLong("Corner", pos.asLong());
            data.putLong("ShowUntil", now + SHOW_TICKS);
            write(stack, data);
            tell(player, "Corner one at " + pos.getX() + ", " + pos.getZ()
                + " — now click the opposite corner.");
            return InteractionResult.CONSUME;
        }

        // Second click closes the perimeter; later clicks widen it.
        WorkZone existing = bot.workZone();
        WorkZone zone;
        if (data.getBoolean("Closed") && existing != null) {
            zone = existing.including(pos);
        } else {
            zone = WorkZone.of(BlockPos.of(data.getLong("Corner")), pos,
                existing != null ? existing.depth() : WorkZone.DEFAULT_DEPTH);
        }
        com.jrpetty.mcassistant.ZonePresets.Preset staked =
            com.jrpetty.mcassistant.ZonePresets.stake(player, zone, bot.stationTask());
        bot.assignPlot(zone, staked.name());

        data.putBoolean("Closed", true);
        data.putLong("Corner", pos.asLong());
        data.putLong("ShowUntil", now + SHOW_TICKS);
        write(stack, data);

        bot.showZoneTo(player);
        tell(player, bot.displayNameCap() + "'s patch is now " + zone.describe()
            + ". Click more blocks to widen it"
            + (bot.missingEssentials().isEmpty() ? "." : " — it still needs "
                + String.join(", ", bot.missingEssentials()) + "."));
        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------- the preview

    /**
     * Draw the outline while you're actually working on it — a stake on the
     * pending corner, the full perimeter for a few seconds after each change.
     * It deliberately stops once you're done, so a set patch isn't permanently
     * glittering; right-clicking the bot brings it back.
     */
    @Override
    public void inventoryTick(ItemStack stack, net.minecraft.world.level.Level level,
                              net.minecraft.world.entity.Entity holder, int slot, boolean selected) {
        if (!selected || level.isClientSide || !(level instanceof ServerLevel sl)) return;
        if (holder.tickCount % 10 != 0) return;
        CompoundTag data = read(stack);
        if (level.getGameTime() > data.getLong("ShowUntil")) return;

        WorkZone plot = plotIn(data);
        if (plot != null) {
            outline(sl, plot, ParticleTypes.WAX_ON);
        }
        AssistantEntity bot = boundBot(level, data, holder.blockPosition());
        if (bot != null && bot.workZone() != null && data.getBoolean("Closed")) {
            outline(sl, bot.workZone(), ParticleTypes.WAX_ON);
        }
        if (data.contains("Corner") && !data.getBoolean("Closed")) {
            BlockPos c = BlockPos.of(data.getLong("Corner"));
            for (int dy = 0; dy < 3; dy++) {
                sl.sendParticles(ParticleTypes.WAX_ON, c.getX() + 0.5, c.getY() + 1.0 + dy, c.getZ() + 0.5,
                    2, 0.05, 0.05, 0.05, 0.0);
            }
        }
    }

    /**
     * Trace a zone's footprint in particles — the four edges, spaced out so a
     * big patch stays cheap to draw, and draped over the ground so it reads as
     * a fence line rather than floating in the air.
     */
    public static void outline(ServerLevel level, WorkZone zone, net.minecraft.core.particles.SimpleParticleType particle) {
        int y = zone.min().getY() + 1;
        int step = Math.max(1, Math.max(zone.sizeX(), zone.sizeZ()) / 24);
        for (int x = zone.min().getX(); x <= zone.max().getX(); x += step) {
            spark(level, particle, x, y, zone.min().getZ());
            spark(level, particle, x, y, zone.max().getZ());
        }
        for (int z = zone.min().getZ(); z <= zone.max().getZ(); z += step) {
            spark(level, particle, zone.min().getX(), y, z);
            spark(level, particle, zone.max().getX(), y, z);
        }
        // Always mark the four corners, whatever the spacing worked out to.
        spark(level, particle, zone.min().getX(), y, zone.min().getZ());
        spark(level, particle, zone.max().getX(), y, zone.min().getZ());
        spark(level, particle, zone.min().getX(), y, zone.max().getZ());
        spark(level, particle, zone.max().getX(), y, zone.max().getZ());
    }

    private static void spark(ServerLevel level, net.minecraft.core.particles.SimpleParticleType particle, int x, int y, int z) {
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        double drawY = Math.abs(surface - y) <= 12 ? surface + 0.6 : y + 0.6;
        level.sendParticles(particle, x + 0.5, drawY, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
    }

    // ------------------------------------------------------------------ plumbing

    /** The assistant this wand is working on, if it's still around. */
    @Nullable
    private static AssistantEntity boundBot(net.minecraft.world.level.Level level, CompoundTag data, BlockPos near) {
        String id = data.getString("Bot");
        if (id.isEmpty()) return null;
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        List<AssistantEntity> found = level.getEntitiesOfClass(AssistantEntity.class,
            new net.minecraft.world.phys.AABB(near).inflate(BIND_RANGE),
            a -> a.getUUID().equals(uuid));
        return found.isEmpty() ? null : found.get(0);
    }

    /** The plot this wand is carrying, if it has closed one. */
    @Nullable
    private static WorkZone plotIn(CompoundTag data) {
        if (!data.contains("PlotMin") || !data.contains("PlotMax")) return null;
        return new WorkZone(BlockPos.of(data.getLong("PlotMin")), BlockPos.of(data.getLong("PlotMax")),
            data.contains("PlotDepth") ? data.getInt("PlotDepth") : WorkZone.DEFAULT_DEPTH);
    }

    /** Remember that this bot is on the wand's plot. */
    private static void link(CompoundTag data, AssistantEntity bot) {
        net.minecraft.nbt.ListTag linked = data.getList("Linked", net.minecraft.nbt.Tag.TAG_STRING);
        String id = bot.getUUID().toString();
        for (int i = 0; i < linked.size(); i++) {
            if (linked.getString(i).equals(id)) return;
        }
        linked.add(net.minecraft.nbt.StringTag.valueOf(id));
        data.put("Linked", linked);
    }

    /** Move everyone already on this wand's plot onto its new shape. */
    private static int retarget(net.minecraft.world.level.Level level, CompoundTag data, WorkZone plot) {
        net.minecraft.nbt.ListTag linked = data.getList("Linked", net.minecraft.nbt.Tag.TAG_STRING);
        if (linked.isEmpty()) return 0;
        int moved = 0;
        for (int i = 0; i < linked.size(); i++) {
            UUID uuid;
            try {
                uuid = UUID.fromString(linked.getString(i));
            } catch (IllegalArgumentException e) {
                continue;
            }
            for (AssistantEntity a : level.getEntitiesOfClass(AssistantEntity.class,
                    new net.minecraft.world.phys.AABB(plot.center()).inflate(BIND_RANGE),
                    x -> x.isAlive() && x.getUUID().equals(uuid))) {
                a.setWorkZone(plot);
                moved++;
            }
        }
        return moved;
    }

    /** How many of this player's crew are working exactly this ground. */
    private static int onPlot(Player player, WorkZone plot) {
        int n = 0;
        for (AssistantEntity a : AssistantEntity.allFor(player.getUUID())) {
            if (a.isAlive() && plot.equals(a.workZone())) n++;
        }
        return n;
    }

    private static void tell(Player player, String message) {
        player.sendSystemMessage(Component.literal("<Zone Wand> " + message));
    }

    private static CompoundTag read(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static void write(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
}
