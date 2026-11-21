package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * [realm] The threads between the 0.309 round's pieces, tied once at the mod's start: an outlawed knight loses the
 * title; a town's tax unrest pushes its folk to leave; outlaws and battles on the road turn migrants back.
 */
public final class RealmLinks {
    private RealmLinks() {}

    /** How near (blocks) an outlaw, or a battle's field, makes the road unsafe for folk on the move. */
    static final int OUTLAW_NEAR = 32, BATTLE_NEAR = 96;

    public static void wire() {
        Outlaws.listen(Knights::onOutlawed);
        Migration.TAXES = RealmLinks::taxPush;
        Migration.ROAD_DANGER = RealmLinks::roadDanger;
    }

    /** Unrest below grumbling pushes nobody; a revolt (75) pushes about as hard as a war, a town past bearing harder. */
    static int taxPush(java.util.UUID town) {
        int unrest = TaxUnrest.level(town);
        return unrest < TaxUnrest.GRUMBLE ? 0 : (unrest - 30) / 3;
    }

    /** An outlaw of any town about, or a battle being fought near the road: in words, or null if the way is clear. */
    static String roadDanger(ServerLevel level, BlockPos at) {
        for (ServerPlayer p : level.players()) {
            if (!p.blockPosition().closerThan(at, OUTLAW_NEAR)) continue;
            for (Villages.Village v : Villages.every()) {
                if (Outlaws.isOutlaw(v.id(), p.getUUID())) return "an outlaw was on the road";
            }
        }
        for (Campaigns.Campaign c : Campaigns.active()) {
            if (c.battle() != null && c.ground() != null && c.ground().centre().closerThan(at, BATTLE_NEAR)) return "there was fighting on the road";
        }
        return null;
    }
}
