package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.UUID;

/**
 * [wpc] Operators only (permission 2), for the camera and the screenshot rig: {@code /village wpcrig <player> <what>} sets a
 * player's property pages up as a player would leave them — the builder at its third step on the first free lot that takes
 * a cottage ("build"), the same walked round with the screen closed and the player stood where the camera wants it ("walk"),
 * the land office with a lot chosen ("land"), some debts on the ledger ("debts"), and so on — and says where. Never a
 * player's way to do anything: every player action is a button on the pages.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PropertyRig {

    private PropertyRig() {}

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("village").then(Commands.literal("wpcrig").requires(s -> s.hasPermission(2))
            .then(Commands.argument("who", EntityArgument.player()).then(Commands.argument("what", com.mojang.brigadier.arguments.StringArgumentType.word())
                .executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "who");
                    String what = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "what");
                    String said = rig(p, what);
                    c.getSource().sendSuccess(() -> Component.literal("WPCRIG " + said), false);
                    return 1;
                })))));
    }

    /** The first free plan lot near the square a cottage fits, as offsets, or null. */
    static int[] freeLot(UUID village, BlockPos heart) {
        for (TownPlan.Lot l : TownPlan.lots()) {
            if (l.kind() != TownPlan.Kind.LOT) continue;
            if (!BuildPicker.lotProblem(village, heart, l, false).isEmpty()) continue;
            if (!LandPlots.lotProblem(village, heart, l).isEmpty()) continue;
            return new int[]{ l.x(), l.z() };
        }
        return null;
    }

    static String rig(ServerPlayer p, String what) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "no town near";
        UUID id = v.id();
        BlockPos heart = v.centre();
        int[] lot = freeLot(id, heart);
        switch (what) {
            case "build", "walk" -> {
                if (lot == null) return "no free lot";
                PlayerLife.open(p, "build", LifePage.args("for", "home", "site", BuildPicker.lotSite(lot[0], lot[1]), "bp", "house", "step", 3));
                if (what.equals("walk")) {
                    PlayerLife.act(p, "build", "walk", new net.minecraft.nbt.CompoundTag());
                    TownPlan.Lot l = LandPlots.lotAt(lot[0], lot[1]);
                    Direction door = LandPlots.door(l.back());
                    int ax = heart.getX() + lot[0], az = heart.getZ() + lot[1];
                    int vx = ax + door.getStepX() * 14 + door.getClockWise().getStepX() * 7, vz = az + door.getStepZ() * 14 + door.getClockWise().getStepZ() * 7;
                    int vy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, vx, vz) + 4;
                    double dx = ax - vx, dz = az - vz;
                    float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
                    p.teleportTo(level, vx + 0.5, vy, vz + 0.5, yaw, 18f);
                    return "walk lot " + lot[0] + "," + lot[1] + " at " + ax + "," + az + " viewer " + vx + "," + vy + "," + vz;
                }
                return "build lot " + lot[0] + "," + lot[1];
            }
            case "land", "landwalk" -> {
                if (lot == null) return "no free lot";
                PlayerLife.open(p, "land", LifePage.args("tab", "buy", "kind", "LOT"));
                PlayerLife.act(p, "land", "pick", LifePage.args("ox", lot[0], "oz", lot[1]));
                if (what.equals("landwalk")) {
                    int ax = heart.getX() + lot[0], az = heart.getZ() + lot[1];
                    int vx = ax + 12, vz = az + 12;
                    int vy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, vx, vz) + 7;
                    p.teleportTo(level, vx + 0.5, vy, vz + 0.5, 135f, 30f);
                }
                return "land lot " + lot[0] + "," + lot[1];
            }
            case "debts" -> {
                long day = PropertyBook.day(level);
                Debts.owe(level, id, p.getUUID(), Belonging.called(p), "rates", "the town", "town", null, 14, 0, 0, "rig:rates");
                Debts.owe(level, id, p.getUUID(), Belonging.called(p), "tab", "the tavern", "town", null, 9, 0, 3, "rig:tab");
                Debts.owe(level, id, p.getUUID(), Belonging.called(p), "fine", "the watch", "town", null, 25, 0, 0, "rig:fine");
                for (Debts.Debt d : Debts.of(id, p.getUUID())) {
                    if (d.ref().equals("rig:rates")) Debts.dueForTests(id, p.getUUID(), d.id(), day - 4);
                    if (d.ref().equals("rig:fine")) Debts.dueForTests(id, p.getUUID(), d.id(), day - 7);
                }
                Debts.morning(level, v, day);
                PlayerLife.open(p, "debts", null);
                return "debts seeded";
            }
            case "mortgage" -> {
                Long h = Mortgages.firstForSaleForTests(id);
                PlayerLife.open(p, "mortgage", h == null ? LifePage.args("tab", "get") : LifePage.args("tab", "get", "house", Long.toString(h), "ltv", "80", "term", "12"));
                return "mortgage " + h;
            }
            default -> {
                PlayerLife.open(p, what, null);
                return "opened " + what;
            }
        }
    }
}
