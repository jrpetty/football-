package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [trade-world] A merchant ship's people ashore (ShipCalls): its captain, who trades from the quay, and a sailor who sees to
 * the ropes. They are visitors (Visitors.Kind.SEAFARER): folk of no village, never on the town's roll, dressed in their
 * land's colour (a dyed coat and cap bought at home, which go home with them), named as their land names people. By day
 * they keep the pier, the captain calling its wares; after dark they stand by the gangplank; the morning the ship sails
 * they go aboard, and are gone with it. Killed in the harbour, their land remembers it (ShipCalls.crewLost).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Seafarers {

    private Seafarers() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    static final String CAPTAIN = "captain", SAILOR = "sailor";

    /** Come ashore off the ship: its captain and a sailor, at the gangway (the ground there awake). */
    static void comeAshore(ServerLevel level, Villages.Village v, Harbours.Shore s, ShipCalls.Ship ship) {
        BlockPos at = ShipHull.gangway(s, ship.pos, ship.bowIn);
        if (ship.anchored) at = s.onDeck(Harbours.PIER - 2, 0);         // rowed ashore in the ship's boat
        if (!level.isLoaded(at)) return;
        ship.crew.removeIf(u -> !(level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive()));
        boolean haveCaptain = captain(level, ship) != null;
        while (ship.crew.size() < 2) {
            VillageFolkEntity f = spawn(level, v, ship, at, !haveCaptain);
            if (f == null) break;
            ship.crew.add(f.getUUID());
            haveCaptain = true;
        }
    }

    /** Its people kept: any lost while the ground slept found again or replaced (a ship is never without its captain). */
    static void keepAshore(ServerLevel level, Villages.Village v, Harbours.Shore s, ShipCalls.Ship ship) {
        BlockPos at = ShipHull.gangway(s, ship.pos, ship.bowIn);
        if (!level.isLoaded(at) || ship.called >= 0) return;
        if (captain(level, ship) == null) {
            // Not about (unloaded and not yet read back, or gone astray): a few minutes' grace, then the mate comes ashore.
            if (ship.waitedSince < 0) ship.waitedSince = level.getGameTime();
            if (level.getGameTime() - ship.waitedSince < 1200L) return;
            ship.waitedSince = -1;
            ship.crew.removeIf(u -> !(level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive()));
            comeAshore(level, v, s, ship);
            ShipCalls.save(v.id(), ship);
        } else {
            ship.waitedSince = -1;
        }
        VillageFolkEntity c = captain(level, ship);
        if (c != null) ShipTrade.closeFar(c);
    }

    /** How many of its people are ashore and about. */
    static int ashore(ServerLevel level, ShipCalls.Ship ship) {
        int n = 0;
        for (UUID u : ship.crew) if (level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive()) n++;
        return n;
    }

    /** All aboard: whoever is still ashore goes with the ship. */
    static void allAboard(ServerLevel level, ShipCalls.Ship ship) {
        for (UUID u : ship.crew) {
            if (level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive()) {
                Visitors.forget(u);
                f.discard();
            }
        }
        ship.crew.clear();
    }

    /** The ship's captain, if it is ashore. */
    @Nullable
    static VillageFolkEntity captain(ServerLevel level, ShipCalls.Ship ship) {
        for (UUID u : ship.crew) {
            if (level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive()) {
                Visitors.Visit vis = Visitors.visit(f);
                if (vis != null && CAPTAIN.equals(vis.wares)) return f;
            }
        }
        return null;
    }

    /** One of the ship's people, ashore at the gangway in its land's colours. */
    @Nullable
    static VillageFolkEntity spawn(ServerLevel level, Villages.Village v, ShipCalls.Ship ship, BlockPos at, boolean captain) {
        ForeignShips.Land land = ship.land();
        if (land == null) return null;
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        RandomSource r = level.getRandom();
        long day = level.getDayTime() / 24000L;
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360.0F, 0.0F);
        String name = ForeignShips.personName(land, r);
        f.rename(name + (captain ? " the captain" : " the sailor"));
        f.addTag(Visitors.TAG);
        f.setPersistenceRequired();
        f.life().roll(r, null, null);
        f.persona().roll(r, f.life(), AssistantEntity.StationTask.NONE, day, "a seafarer from " + land.name() + ", " + land.coast());
        // Its land's colours: a coat and a cap dyed at home (they come and go with it, and are never dropped here).
        f.setItemSlot(EquipmentSlot.CHEST, ForeignShips.dyed(Items.LEATHER_CHESTPLATE, land.colour()));
        f.setItemSlot(EquipmentSlot.HEAD, ForeignShips.dyed(Items.LEATHER_HELMET, land.colour()));
        f.setDropChance(EquipmentSlot.CHEST, 0.0F);
        f.setDropChance(EquipmentSlot.HEAD, 0.0F);
        Visitors.Visit vis = new Visitors.Visit();
        vis.kind = Visitors.Kind.SEAFARER;
        vis.town = v.id();
        vis.edge = at.immutable();
        vis.name = name;
        vis.arrived = day;
        vis.leave = ship.leave;
        vis.stage = "stay";
        vis.told = true;
        vis.wares = captain ? CAPTAIN : SAILOR;
        vis.seen = level.getGameTime();
        Visitors.save(f, vis);
        level.addFreshEntity(f);
        FolkTalk.speak(f, captain ? FolkTalk.pick(r, "Goods from " + land.name() + "! Come and see what's in the hold!",
                "Fair winds, " + Villages.name(v.id()) + "! Who'll trade with " + ship.name + "?")
            : FolkTalk.pick(r, "Dry land at last!", "Make fast there! Ah, " + Villages.name(v.id()) + " again."));
        LOG.info("[MCA-HARBOUR] {} the {} of {} came ashore at {}", name, captain ? CAPTAIN : SAILOR, ship.name, at.toShortString());
        return f;
    }

    // ------------------------------------------------------------------ its day (Visitors)

    /** Its day ashore: the pier by day (the captain calling its wares), the gangplank after dark. True when it goes aboard. */
    static boolean stay(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit vis, long day, long t) {
        ShipCalls.Ship ship = ShipCalls.ship(town.id());
        Harbours.Shore s = Harbours.shore(town.id());
        if (ship == null || s == null || !ship.docked() || ship.called >= 0 || !ship.crew.contains(f.getUUID())) return true;
        boolean captain = CAPTAIN.equals(vis.wares);
        BlockPos spot = t >= 13000L || t < 500L ? vis.edge : captain ? s.onDeck(2, -1) : s.onDeck(Harbours.PIER - 2, 0);
        Visitors.walk(f, level, spot, 1.5, 0.6D, vis.walk);
        long now = level.getGameTime();
        if (now - vis.lastSaid > 900L && t < 12500L) {
            vis.lastSaid = now;
            ForeignShips.Land land = ship.land();
            if (captain && land != null) {
                ShipCalls.Lot lot = null;
                for (ShipCalls.Lot l : ship.hold) if (l.lotsLeft() > 0) { lot = l; break; }
                FolkTalk.speak(f, lot == null ? "We've sold the hold bare. We'll buy, though: ask me to trade!"
                    : FolkTalk.pick(f.getRandom(), FolkTalk.cap(lot.words) + " from " + land.coast() + "! " + lot.lot + " for " + lot.price + " coins!",
                        "Trade with " + ship.name + "! We buy as well as sell."));
            } else if (f.getRandom().nextInt(3) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Fine weather for it.", "Mind the ropes there.",
                    "We sail " + (ship.leave <= day ? "in the morning" : "in a day or two") + "."));
            }
        }
        return false;
    }

    /** What it is doing, for the top of its card. */
    static String doing(VillageFolkEntity f, Visitors.Visit vis) {
        ShipCalls.Ship ship = ShipCalls.ship(vis.town);
        String name = ship == null ? "its ship" : ship.name;
        return CAPTAIN.equals(vis.wares) ? "Trading off " + name + " on the quay" : "Seeing to " + name + "'s ropes on the pier";
    }

    /** Its card's lines: its ship and land, when it sails, what the hold has left. */
    static String card(VillageFolkEntity f, Visitors.Visit vis) {
        ShipCalls.Ship ship = ShipCalls.ship(vis.town);
        ForeignShips.Land land = ship == null ? null : ship.land();
        StringBuilder sb = new StringBuilder();
        sb.append("Visitor|").append(CAPTAIN.equals(vis.wares) ? "The captain" : "A sailor").append(" of ")
            .append(ship == null ? "a merchant ship" : ship.name).append(land == null ? "" : ", out of " + land.name() + " (" + land.coast() + ")")
            .append("; of no village, so not one of the town's people");
        long day = f.level().getDayTime() / 24000L;
        if (ship != null) {
            sb.append("\nSails|").append(ship.leave <= day ? "this morning" : "in " + (ship.leave - day) + (ship.leave - day == 1 ? " day" : " days"));
            if (CAPTAIN.equals(vis.wares)) {
                StringBuilder lots = new StringBuilder();
                for (ShipCalls.Lot l : ship.hold) {
                    if (l.lotsLeft() <= 0) continue;
                    if (lots.length() > 0) lots.append(", ");
                    lots.append(l.words).append(" (").append(l.lot).append(" for ").append(l.price).append("c)");
                }
                sb.append("\nSells|").append(lots.length() == 0 ? "nothing left in the hold" : lots);
                sb.append("\nBuys|").append(land == null ? "" : wants(land)).append("; the ship's purse holds ").append(ship.purse).append("c");
            }
        }
        if (f.persona().rolled()) sb.append("\nNature|").append(f.life().traitsLabel());
        return sb.toString();
    }

    static String wants(ForeignShips.Land land) {
        java.util.List<String> w = new java.util.ArrayList<>();
        for (net.minecraft.world.item.Item i : land.wants()) w.add(ShipCalls.words(i));
        return Merchants.list(w);
    }

    /** What it says (Visitors.talk): its greeting, its land, the ship's trade at the trading table. Null to let the rest stand. */
    @Nullable
    static String talk(VillageFolkEntity f, Player p, TalkTopic topic, Visitors.Visit vis) {
        ShipCalls.Ship ship = ShipCalls.ship(vis.town);
        ForeignShips.Land land = ship == null ? null : ship.land();
        String from = land == null ? "over the sea" : land.name();
        boolean captain = CAPTAIN.equals(vis.wares);
        RandomSource r = f.getRandom();
        return switch (topic) {
            case GIFT, BYE, SAY -> null;
            case OPEN -> captain
                ? FolkTalk.pick(r, "Fair winds, friend! " + vis.name + ", captain of " + (ship == null ? "the ship" : ship.name) + ", out of "
                    + from + ". Ask me to trade and I'll show you the hold.", "Welcome aboard, near enough! We've goods from " + from + ", and we buy too.")
                : FolkTalk.pick(r, "Ahoy! You'll want the captain for trading: I only haul the ropes.", "Fine harbour, this. Better than most.");
            case HOW -> FolkTalk.pick(r, "Glad of dry land under my feet.", "Salt in my bones and coin in my purse. Can't complain.");
            case ABOUT, DREAMS, HOBBY -> land == null ? "I go where the ship goes." : "I'm " + land.people() + ", from " + land.coast()
                + ". We sail from port to port with what our land makes, and take home what yours does. " + (captain
                ? "At home they'd pay well for " + wants(land) + "." : "One day I'll have a ship of my own.");
            case VILLAGE, GOSSIP, MEMORY -> "The harbour here's a good one, and " + Villages.name(vis.town) + " pays fair. Ask the folk ashore for the news.";
            case TRADE -> captain && p instanceof ServerPlayer sp ? ShipTrade.open(sp, vis.town, f) : "The captain does the trading. Up there on the quay!";
            case DELIVER -> "Set the price on the trading table, friend: ask me to trade.";
            default -> FolkTalk.pick(r, "That's one for the people of " + Villages.name(vis.town) + ", not for a sailor.",
                "I couldn't say. We only put in for a day or two.");
        };
    }

    /** One of a ship's people killed: by a hand (a player's or a folk's) its land takes it hard. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof VillageFolkEntity f) || !(f.level() instanceof ServerLevel level) || !Visitors.is(f)) return;
        Visitors.Visit vis = Visitors.visit(f);
        if (vis == null || vis.kind != Visitors.Kind.SEAFARER) return;
        net.minecraft.world.entity.Entity by = e.getSource().getEntity();
        boolean byHand = by instanceof Player || by instanceof AssistantEntity;
        ShipCalls.crewLost(level, vis.town, f, byHand);
    }
}
