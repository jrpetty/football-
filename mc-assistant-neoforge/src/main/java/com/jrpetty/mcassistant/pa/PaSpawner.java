package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * [pa33] The Assistant Spawner, for the Player AI Assistant: place it and click it to bring out your assistant (made
 * there the first time); it becomes its home, and the place it comes back to after a fall. (A sneak-click still hires a
 * crew hand, the spawner's old use.)
 */
public final class PaSpawner {

    private PaSpawner() {}

    /** Just placed. Always true: the spawner is kept, to be clicked. */
    public static boolean placed(ServerPlayer p, BlockPos pos) {
        PaData.Rec r = PaData.first(p.getUUID());
        if (r != null) {
            bind(p, r, pos);
            PaLife.tell(p, "This spawner is " + PaNet.cap(r.name) + "'s home now, where they come back to. Click it to call them here.");
        } else {
            PaLife.tell(p, "Click the spawner to bring out your Player AI Assistant. (Sneak-click hires a crew hand instead.)");
        }
        return true;
    }

    public static void clicked(ServerPlayer p, BlockPos pos) {
        PaData.Rec r = PaData.first(p.getUUID());
        if (r == null) {
            PlayerAssistantEntity a = PaLife.create(p, pos.above(), "spawner");
            PaData.Rec made = PaData.first(p.getUUID());
            if (made != null) bind(p, made, pos);
            if (a != null) a.setHome(pos);
            return;
        }
        bind(p, r, pos);
        PaLife.call(p, r, true);
        PlayerAssistantEntity a = PaLife.entity(p.server, r);
        if (a != null) {
            a.setHome(pos);
            if (a.level() == p.level()) {
                net.minecraft.world.phys.Vec3 spot = PaLife.safeSpot(p.serverLevel(), pos.above());
                if (spot != null) a.teleportTo(spot.x, spot.y, spot.z);
            }
        }
    }

    private static void bind(ServerPlayer p, PaData.Rec r, BlockPos pos) {
        r.spawner = pos.immutable();
        r.spawnerDim = p.serverLevel().dimension().location().toString();
        PaData.touch();
    }
}
