package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.List;

/**
 * What a village sees a player do for it. A monster killed near folk is a monster
 * that is not coming for them tonight: everybody who saw it thinks a little better
 * of the player, and somebody says so.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class FolkEvents {

    private FolkEvents() {}

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide || !(event.getEntity() instanceof Enemy)) return;
        if (!(event.getSource().getEntity() instanceof Player player)) return;
        List<VillageFolkEntity> saw = event.getEntity().level().getEntitiesOfClass(VillageFolkEntity.class,
            event.getEntity().getBoundingBox().inflate(24.0), f -> f.isAlive() && f.persona().rolled() && !f.isSleeping());
        if (saw.isEmpty()) return;
        String who = player.getName().getString();
        for (VillageFolkEntity f : saw) f.persona().feelFor(player.getUUID(), who, 3);
        VillageFolkEntity first = saw.get(player.getRandom().nextInt(saw.size()));
        FolkTalk.speak(first, FolkTalk.thanks(first, player));
    }
}
