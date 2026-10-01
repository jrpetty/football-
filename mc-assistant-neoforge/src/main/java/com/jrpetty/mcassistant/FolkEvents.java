package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Errands;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a village sees a player do for it, and how it greets a player who comes in.
 *
 * <p>A monster killed near folk is a monster that is not coming for them tonight:
 * everybody who saw it thinks a little better of the player, somebody says so, and
 * it counts toward any hunt a folk there asked of them. And walking into a village
 * you are told where you are — its name, its age, how many live there — and what you
 * are to it.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class FolkEvents {

    private FolkEvents() {}

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || !(event.getEntity() instanceof Enemy)) return;
        if (!(event.getSource().getEntity() instanceof Player player)) return;
        Errands.monsterKilled(level, player, event.getEntity().blockPosition());
        List<VillageFolkEntity> saw = level.getEntitiesOfClass(VillageFolkEntity.class,
            event.getEntity().getBoundingBox().inflate(24.0), f -> f.isAlive() && f.persona().rolled() && !f.isSleeping());
        if (saw.isEmpty()) return;
        String who = player.getName().getString();
        for (VillageFolkEntity f : saw) f.persona().feelFor(player.getUUID(), who, 3);
        VillageFolkEntity first = saw.get(player.getRandom().nextInt(saw.size()));
        FolkTalk.speak(first, FolkTalk.thanks(first, player));
        if (first.ownerId() != null) Standing.stir(first.ownerId(), player.getUUID());
    }

    /** The village each player was last in, so it is announced once on the way in. */
    private static final Map<UUID, UUID> IN = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 40 != 0) return;
        Villages.Village v = Villages.nearest(player.level(), player.blockPosition(), 64);
        UUID now = v == null ? null : v.id();
        UUID was = IN.get(player.getUUID());
        if (now == null) {
            IN.remove(player.getUUID());
            return;
        }
        com.jrpetty.mcassistant.entity.Welcome.check((ServerLevel) player.level(), player, now);
        if (now.equals(was)) return;
        IN.put(player.getUUID(), now);
        Standing.View view = Standing.of(now, player.getUUID(), player.level().getGameTime());
        Component banner = Component.literal(Villages.name(now)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal(" · " + Villages.ageOf(now).label + " · " + Villages.headcount(now) + " people · you are "
                + view.title().words).withStyle(ChatFormatting.WHITE));
        player.displayClientMessage(banner, true);
    }
}
