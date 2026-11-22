package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.net.VillagePagePayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * [guide31] The few facts the Guide (client/guide) uses to answer a player as themselves, sent once when it opens, on the
 * village journal's own packet (VillageAskPayload {@link #ASK}, answered with a VillagePagePayload titled {@link #TITLE}):
 * the town the player stands in, what they are to it, whether they are its citizen or lead it, whether they have a town
 * of their own anywhere, a shop or a business there. A line of key=value pairs; nothing the player could not see on
 * their U page already, and nothing is kept.
 */
public final class GuideFacts {

    private GuideFacts() {}

    /** The journal packet's page number that asks for these. */
    public static final int ASK = 5;
    /** The title the answer carries, so the client hands it to the Guide and not to the journal. */
    public static final String TITLE = "#guide";

    public static void send(ServerPlayer p) {
        PacketDistributor.sendToPlayer(p, new VillagePagePayload(TITLE, line(p)));
    }

    /** The facts as one line: "town=Oakford;title=a friend of the village;citizen=1;own=1;shop=0;firms=0;leader=0". */
    public static String line(ServerPlayer p) {
        StringBuilder sb = new StringBuilder();
        Villages.Village v = PlayerLife.here(p);
        boolean own = FoundingEasy.hasOwn(p);
        if (v != null) {
            Standing.View view = Standing.of(v.id(), p.getUUID(), p.level().getGameTime());
            sb.append("town=").append(clean(Villages.name(v.id())))
                .append(";title=").append(clean(view.title().words))
                .append(";citizen=").append(Citizens.is(v.id(), p.getUUID()) ? 1 : 0)
                .append(";shop=").append(PlayerShops.of(v.id(), p.getUUID()) != null ? 1 : 0)
                .append(";firms=").append(MyBusinesses.mine(v.id(), p.getUUID()).size())
                .append(";leader=").append(PlayerLeader.leads(v.id(), p.getUUID()) ? 1 : 0)
                .append(';');
        }
        sb.append("own=").append(own ? 1 : 0);
        return sb.toString();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(';', ',').replace('=', '-');
    }
}
