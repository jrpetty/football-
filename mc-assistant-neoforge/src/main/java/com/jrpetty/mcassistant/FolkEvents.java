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
        // The one it had cornered owes them its life.
        String what = event.getEntity().getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
        boolean saved = false;
        for (VillageFolkEntity f : saw) {
            if (!f.besetBy(event.getEntity().getUUID())) continue;
            f.rescuedBy(player, what);
            saved = true;
        }
        if (saved) return;
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
        if (now.equals(was)) {
            // Still here: they have not been away, so there is nothing to catch up on.
            if (player.tickCount % 400 == 0)
                com.jrpetty.mcassistant.village.Chronicle.visited(now, player.getUUID(), player.level().getDayTime() / 24000L);
            return;
        }
        IN.put(player.getUUID(), now);
        Standing.View view = Standing.of(now, player.getUUID(), player.level().getGameTime());
        Component banner = Component.literal(Villages.name(now)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal(" · " + Villages.ageOf(now).label + " · " + Villages.headcount(now) + " people · you are "
                + view.title().words).withStyle(ChatFormatting.WHITE));
        player.displayClientMessage(banner, true);
        welcomeBack((ServerLevel) player.level(), player, now);
    }

    /** Back after a while away: somebody who knows you catches you up on the news. */
    private static void welcomeBack(ServerLevel level, ServerPlayer player, UUID village) {
        long day = level.getDayTime() / 24000L;
        long last = com.jrpetty.mcassistant.village.Chronicle.visited(village, player.getUUID(), day);
        if (last < 0 || day - last < 1) return;
        java.util.List<String> news = new java.util.ArrayList<>();
        java.util.List<com.jrpetty.mcassistant.village.Chronicle.Entry> past = com.jrpetty.mcassistant.village.Chronicle.of(village);
        for (int i = past.size() - 1; i >= 0 && news.size() < 3; i--) {
            com.jrpetty.mcassistant.village.Chronicle.Entry e = past.get(i);
            if (e.day() <= last) break;
            news.add(e.text());
        }
        VillageFolkEntity greeter = null;
        double nearest = 24.0 * 24.0;
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, player.getBoundingBox().inflate(24.0),
                f -> f.isAlive() && !f.isSleeping() && !f.isBaby() && f.persona().rolled() && f.persona().knows(player.getUUID())
                    && f.persona().affinity(player.getUUID()) >= 0)) {
            double d = f.distanceToSqr(player);
            if (d < nearest) { nearest = d; greeter = f; }
        }
        if (greeter == null) return;
        String you = player.getName().getString();
        StringBuilder said = new StringBuilder("Welcome back, " + you + "! ");
        if (news.isEmpty()) said.append("Quiet while you were away.");
        else {
            said.append("While you were away, ");
            for (int i = 0; i < news.size(); i++) {
                if (i > 0) said.append(i == news.size() - 1 ? ", and " : ", ");
                said.append(news.get(i));
            }
            said.append('.');
        }
        FolkTalk.speak(greeter, said.toString());
    }
}
